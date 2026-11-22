#!/usr/bin/env python3
"""Refresh the public list roster and ordered entries for a Cineorgasm seed."""

import argparse
import gzip
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from html.parser import HTMLParser
from io import BytesIO
import json
from pathlib import Path
import re
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


class ListPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.films = []
        self.next_url = None
        self.link = None
        self.owner_rating = None
        self.list_member = False
        self.title = ""
        self.heading = False
        self.count = None

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if tag == "meta" and data.get("name") == "description":
            value = re.match(r"A list of ([0-9,]+) films? compiled on Letterboxd", data.get("content", ""))
            if value:
                self.count = int(value[1].replace(",", ""))
        if tag == "h1" and "title-1" in data.get("class", "").split():
            self.heading = True
        if tag == "a":
            self.link = data.get("href")
        if tag == "li":
            self.list_member = "posteritem" in data.get("class", "").split() and data.get("data-object-id", "").startswith("filmListEntry:")
            rating = data.get("data-owner-rating", "")
            self.owner_rating = int(rating) if rating.isdigit() and int(rating) > 0 else None
        slug = data.get("data-item-slug")
        display = data.get("data-item-name")
        if not self.list_member or not slug or not display or data.get("data-component-class") != "LazyPoster":
            return
        if not (data.get("data-item-link") or "").startswith("/film/"):
            return
        year = re.search(r" \((\d{4})\)$", display)
        entry = {"slug": slug, "name": display[:year.start()] if year else display}
        if year:
            entry["year"] = int(year[1])
        if self.owner_rating:
            entry["ownerRating"] = self.owner_rating
        try:
            entry["filmID"] = json.loads(data.get("data-postered-identifier", "{}"))["uid"]
        except (KeyError, ValueError):
            pass
        self.films.append(entry)
        self.list_member = False
        self.owner_rating = None

    def handle_endtag(self, tag):
        if tag == "h1":
            self.heading = False
        if tag == "a":
            self.link = None

    def handle_data(self, data):
        if self.heading:
            self.title += data
        if self.link and data.strip() == "Next":
            self.next_url = self.link


lock = threading.Lock()
next_read = 0.0
read_interval = 0.25


def read(path, *, film=False):
    global next_read
    for attempt in range(3):
        with lock:
            start = max(time.monotonic(), next_read)
            next_read = start + read_interval
        time.sleep(max(0, start - time.monotonic()))
        try:
            request = Request("https://letterboxd.com" + path,
                              headers={"User-Agent": "Mozilla/5.0", "Accept": "text/html", "Accept-Encoding": "gzip"})
            with urlopen(request, timeout=16) as response:
                if urlsplit(response.url).hostname != "letterboxd.com":
                    raise ValueError("Unexpected Letterboxd redirect")
                if response.headers.get("Content-Encoding") == "gzip":
                    body = gzip.GzipFile(fileobj=response).read(1_500_001)
                else:
                    body = response.read(1_500_001)
            if len(body) > 1_500_000:
                raise ValueError("Oversized Letterboxd page")
            html = body.decode()
            if film:
                return html
            page = ListPage()
            page.feed(html)
            return page
        except HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504):
                raise
            if error.code == 429:
                with lock:
                    next_read = max(next_read, time.monotonic() + 10)
        except OSError:
            if attempt == 2:
                raise
        time.sleep(attempt + 1)
    raise ValueError("Letterboxd requests failed")


def download(key, checkpoints):
    owner, slug = key.split("/")
    dest = checkpoints / f"{owner}--{slug}.json"
    if dest.exists():
        return key, json.loads(dest.read_text()), None
    root = f"/{owner}/list/{slug}/"
    path = root
    seen = set()
    entries = []
    heading = ""
    declared = None
    try:
        while path:
            if path in seen or len(seen) >= 400:
                raise ValueError("Invalid list pagination")
            seen.add(path)
            page = read(path)
            heading = heading or page.title.strip()
            if declared is None:
                declared = page.count
            if not page.films:
                raise ValueError("Letterboxd page contained no list entries")
            entries.extend(page.films)
            path = page.next_url
            if path and not re.fullmatch(re.escape(root) + r"page/\d+/", path):
                raise ValueError("Unexpected next-page path")
        if not heading:
            raise ValueError("No list title")
        if declared is not None and declared != len(entries):
            raise ValueError(f"Expected {declared} entries, got {len(entries)}")
        result = (heading, entries)
        dest.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return key, result, None
    except HTTPError as error:
        return key, None, f"HTTP {error.code}"
    except (OSError, ValueError) as error:
        return key, None, str(error) if isinstance(error, ValueError) else type(error).__name__


def resolve_slug(slug):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,220}", slug):
        return slug, None
    try:
        html = read(f"/film/{slug}/", film=True)
    except (OSError, ValueError):
        return slug, None
    links = set(re.findall(r'https?://(?:www\.)?themoviedb\.org/(movie|tv)/(\d+)(?:[/?"\'])', html))
    if len(links) != 1:
        return slug, None
    kind, number = next(iter(links))
    return slug, {"tmdbID": int(number), "isTV": kind == "tv"}


def store_resolved(checkpoint, found):
    temp = checkpoint.with_suffix(".tmp")
    temp.write_text(json.dumps(found, separators=(",", ":")))
    temp.replace(checkpoint)


def resolve(saved_path, output, working):
    saved = json.loads(saved_path.read_bytes())
    aliases = saved["slugToTMDB"]
    checkpoint = output.parent / "resolved-slugs.json"
    found = json.loads(checkpoint.read_text()) if checkpoint.exists() else {}
    while True:
        rows = []
        for file in (output.parent / "list-pages").glob("*.json"):
            try:
                rows.extend(json.loads(file.read_text())[1])
            except (OSError, ValueError):
                continue
        pending = list(dict.fromkeys(entry["slug"] for entry in rows
                                     if entry["slug"] not in aliases and entry["slug"] not in found))
        if pending:
            with ThreadPoolExecutor(max_workers=16) as pool:
                for index, (slug, match) in enumerate(pool.map(resolve_slug, pending), 1):
                    found[slug] = match
                    if index % 50 == 0:
                        store_resolved(checkpoint, found)
                    if index % 500 == 0:
                        print(f"Current link batch: {index}/{len(pending)}", flush=True)
                store_resolved(checkpoint, found)
            print(f"New film links checked: {len(found)}; exact matches: {sum(bool(v) for v in found.values())}.", flush=True)
        if output.exists() or not working:
            return
        time.sleep(20)


def verify_head(record):
    ref = record["ref"]
    key = f'{ref["owner"]}/{ref["slug"]}'
    try:
        page = read(f'/{ref["owner"]}/list/{ref["slug"]}/')
        latest = [v["slug"] for v in page.films]
        saved = [v["slug"] for v in record["entries"][:len(latest)]]
        if not latest or latest != saved or (page.count is not None and page.count != len(record["entries"])):
            return key, "Published head or count differs"
        return key, None
    except HTTPError as error:
        return key, f"HTTP {error.code}"
    except (OSError, ValueError) as error:
        return key, type(error).__name__


class ListInformation(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tags = {}
        self.dates = {}
        self.date_kind = None

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "meta" and attributes.get("property", "").startswith("og:"):
            self.tags[attributes["property"]] = attributes.get("content", "")
        if tag == "span":
            classes = attributes.get("class", "").split()
            self.date_kind = next((name for name in ("published", "updated") if name in classes), None)
        if tag == "time" and self.date_kind:
            value = attributes.get("datetime", "")
            if re.fullmatch(r"[0-9T:.Z+-]{10,40}", value):
                self.dates[self.date_kind] = value

    def handle_endtag(self, tag):
        if tag == "span":
            self.date_kind = None


def capture_information(export_path, assets):
    from PIL import Image, ImageOps

    data = json.loads(export_path.read_bytes())
    if data.get("usernames") != ["cineorgasm"]:
        raise ValueError("Expected the verified Cineorgasm export")
    root = assets / "letterboxd-offline"
    backdrops = root / "backdrops"
    backdrops.mkdir(parents=True, exist_ok=True)
    saved_at = int(export_path.stat().st_mtime * 1000)
    roster = []
    rank = 0
    for row in data["lists"]:
        ref = row["ref"]
        key = f'{ref["owner"]}/{ref["slug"]}'.lower()
        info = {"syncedAtMillis": ref.get("syncedAtMillis") or saved_at}
        if ref["relation"] == "liked":
            info["likedRank"] = ref.get("likedRank", rank)
            rank += 1
        roster.append((key, info))

    def capture(row):
        key, info = row
        owner, slug = key.split("/")
        if not re.fullmatch(r"[a-z0-9_]+/[a-z0-9_:-]+", key):
            return key, info, False
        try:
            page = ListInformation()
            page.feed(read(f"/{owner}/list/{slug}/", film=True))
            if page.tags.get("og:type") != "letterboxd:list":
                return key, info, False
            info["sourceAvailable"] = True
            description = re.sub(r"\s+", " ", page.tags.get("og:description", "")).strip()
            if description:
                info["description"] = description if len(description) <= 900 else description[:897].rsplit(" ", 1)[0] + "…"
            for kind, field in (("published", "publishedAt"), ("updated", "updatedAt")):
                if kind in page.dates:
                    info[field] = page.dates[kind]
            width = int(page.tags.get("og:image:width", 0) or 0)
            height = int(page.tags.get("og:image:height", 0) or 0)
            url = page.tags.get("og:image", "")
            source = urlsplit(url)
            if width > height > 0 and source.scheme == "https" and source.netloc == "a.ltrbxd.com":
                name = sha256(key.encode()).hexdigest()[:24] + ".webp"
                file = backdrops / name
                if not file.is_file():
                    try:
                        request = Request(url, headers={"User-Agent":"Mozilla/5.0", "Referer":"https://letterboxd.com/",
                                                        "Accept":"image/webp,image/*,*/*;q=0.8"})
                        with urlopen(request, timeout=14) as response:
                            if urlsplit(response.url).netloc != "a.ltrbxd.com":
                                raise ValueError("Unexpected artwork origin")
                            raw = response.read(1024 * 1024 + 1)
                        if len(raw) > 1024 * 1024:
                            raise ValueError("Oversized backdrop")
                        with Image.open(BytesIO(raw)) as image:
                            if image.width <= image.height or image.width > 2400 or image.height > 1600:
                                raise ValueError("Unexpected backdrop dimensions")
                            preview = ImageOps.contain(image.convert("RGB"), (760, 428))
                            temporary = file.with_suffix(".tmp")
                            preview.save(temporary, "WEBP", quality=73, method=4)
                            temporary.replace(file)
                    except (OSError, ValueError):
                        pass
                if file.is_file():
                    info["backdropAsset"] = name
            return key, info, True
        except (OSError, ValueError):
            return key, info, False

    results = {}
    success = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        for index, (key, info, ok) in enumerate(pool.map(capture, roster), 1):
            results[key] = info
            success += int(ok)
            if index % 50 == 0:
                print(f"List information checked: {index}/{len(roster)}", flush=True)
    target = root / "list-information.json"
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(results, ensure_ascii=False, separators=(",", ":")))
    temporary.replace(target)
    print(f"Read {success}/{len(roster)} list headers, {sum('description' in x for x in results.values())} descriptions, "
          f"{sum('backdropAsset' in x for x in results.values())} offline backdrops.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, required=True)
    parser.add_argument("--saved", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resolve", action="store_true")
    parser.add_argument("--list-info-assets", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.list_info_assets:
        capture_information(args.saved, args.list_info_assets)
        return
    if args.resolve:
        global read_interval
        read_interval = 0.125
        resolve(args.saved, args.output, working=True)
        return
    if args.verify:
        export = json.loads(args.output.read_bytes())
        with ThreadPoolExecutor(max_workers=6) as pool:
            failures = {key: error for key, error in pool.map(verify_head, export["lists"]) if error}
        args.output.with_suffix(".head-check.json").write_text(json.dumps(failures, indent=2))
        print(f"Published list heads and counts verified: {len(export['lists']) - len(failures)}/{len(export['lists'])}.")
        if failures:
            raise SystemExit(1)
        return
    roster = args.roster.read_text().splitlines()
    if len(roster) != len(set(roster)) or any(not re.fullmatch(r"[a-z0-9_]+/[a-z0-9_:-]+", key) for key in roster):
        raise ValueError("Invalid public roster")
    checkpoints = args.output.parent / "list-pages"
    checkpoints.mkdir(exist_ok=True)
    saved = json.loads(args.saved.read_bytes())
    previous = {f'{row["ref"]["owner"]}/{row["ref"]["slug"]}'.lower(): row for row in saved["lists"]}
    records = {}
    failures = {}
    with ThreadPoolExecutor(max_workers=6) as pool:
        for n, (key, result, error) in enumerate(pool.map(lambda key: download(key, checkpoints), roster), 1):
            if error:
                failures[key] = error
                print(f"List refresh failed for {key}: {error}", flush=True)
                continue
            heading, entries = result
            owner, slug = key.split("/")
            records[key] = {
                "ref": {"owner": owner, "slug": slug, "title": heading, "relation": "owned" if owner == "cineorgasm" else "liked"},
                "entries": entries,
                "items": previous.get(key, {}).get("items", []),
            }
            if n % 10 == 0 or n == len(roster):
                print(f"List pages checked: {n}/{len(roster)}; complete lists: {len(records)}", flush=True)
    result = {
        "usernames": ["cineorgasm"], "slugToTMDB": saved["slugToTMDB"],
        "lists": [records[key] for key in roster if key in records],
    }
    if failures:
        args.output.with_suffix(".failures.json").write_text(json.dumps(failures, indent=2))
        result["lists"] = [records.get(key) or previous[key] for key in roster if key in records or key in previous]
    args.output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    print(f"Roster: {len(roster)}; direct complete copies: {len(records)}; delivery candidates: {len(result['lists'])}.")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
