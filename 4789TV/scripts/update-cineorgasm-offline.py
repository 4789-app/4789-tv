#!/usr/bin/env python3
"""Refresh the Cineorgasm release seed using only new list entries and newly liked lists."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from fcntl import LOCK_EX, LOCK_NB, flock
from importlib.util import module_from_spec, spec_from_file_location
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from tempfile import NamedTemporaryFile
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


PROFILE = "cineorgasm"
SCRIPTS = Path(__file__).resolve().parent
API = "https://api.letterboxd.com/api/v0"
LIST_PATH = re.compile(r"/([a-zA-Z0-9_]+)/list/([a-zA-Z0-9_:-]+)/?")
FILM_PATH = re.compile(r"/(?:film|tv)/([a-zA-Z0-9_-]+)/?")
LID = re.compile(r"[A-Za-z0-9]{1,32}")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Letterboxd:
    def __init__(self, token):
        self.opener = build_opener(NoRedirect())
        self.headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}

    def get(self, path, **params):
        request = Request(f"{API}/{path}?{urlencode(params)}", headers=self.headers)
        try:
            with self.opener.open(request, timeout=20) as response:
                content = response.read(4_000_001)
        except HTTPError as error:
            raise RuntimeError(f"Letterboxd API returned HTTP {error.code} for {path}.") from None
        if len(content) > 4_000_000:
            raise RuntimeError("Letterboxd API response exceeded the page limit.")
        return json.loads(content)


def web_path(link):
    url = urlsplit(link)
    if url.scheme != "https" or url.netloc.lower() not in {"letterboxd.com", "www.letterboxd.com"}:
        raise ValueError("Expected a public Letterboxd link.")
    return url.path


def list_key(record):
    ref = record["ref"]
    owner, slug = ref["owner"].lower(), ref["slug"].lower()
    if not LIST_PATH.fullmatch(f"/{owner}/list/{slug}/"):
        raise ValueError("Invalid saved list identity.")
    return owner, slug


def summary_key(summary):
    links = [summary.get("link", "")] + [link.get("url", "") for link in summary.get("links", [])]
    for link in links:
        try:
            match = LIST_PATH.fullmatch(web_path(link))
        except ValueError:
            continue
        if match:
            return match[1].lower(), match[2].lower()
    raise ValueError("Letterboxd list is missing its canonical link.")


def pages(api, path, **params):
    seen = set()
    cursor = None
    while True:
        page = api.get(path, perPage=100, **params, **({"cursor": cursor} if cursor else {}))
        yield page
        cursor = page.get("next")
        if not cursor:
            return
        if cursor in seen:
            raise ValueError("Letterboxd repeated a pagination cursor.")
        seen.add(cursor)


def roster(api, member):
    if not LID.fullmatch(member) or api.get(f"member/{member}").get("username", "").lower() != PROFILE:
        raise ValueError("The Letterboxd member ID must belong to Cineorgasm.")
    saved = {}
    liked = []
    for relationship, sort in (("Owner", "Date"), ("Liked", "WhenLiked")):
        for page in pages(api, "lists", member=member, memberRelationship=relationship,
                          includeFriends="None", where="Published", sort=sort):
            for summary in page["items"]:
                key = summary_key(summary)
                if not LID.fullmatch(summary["id"]):
                    raise ValueError("Invalid list ID from Letterboxd.")
                if relationship == "Owner" and key[0] != PROFILE:
                    raise ValueError("An owned-list response included another member.")
                if key in saved and saved[key]["id"] != summary["id"]:
                    raise ValueError("Conflicting list IDs for the same public link.")
                saved[key] = summary
                if relationship == "Liked" and key not in liked:
                    liked.append(key)
    return saved, liked


def entry_fields(entry):
    production = entry.get("production") or entry["film"]
    match = FILM_PATH.fullmatch(web_path(production["link"]))
    if not match or not production.get("name"):
        raise ValueError("A list entry is missing its title or public film identity.")
    result = {"slug": match[1].lower(), "name": production["name"], "filmID": production["id"]}
    year = production.get("releaseYear") or production.get("firstAirYear")
    if isinstance(year, int):
        result["year"] = year
    return result


def entries(api, list_id, known=None, boundary=()):
    new = []
    seen = set()
    sort = "ListRanking" if known is None else "WhenAddedToList"
    anchor = set(boundary)
    first_ids = []
    for page in pages(api, f"list/{list_id}/entries", sort=sort):
        if not first_ids:
            first_ids = [row["entryId"] for row in page["items"] if row.get("entryId")]
        for row in page["items"]:
            published = entry_fields(row)
            slug = published["slug"]
            if known is not None:
                if row.get("entryId") in anchor or (not anchor and slug in known):
                    return new, first_ids
                if slug in known:
                    continue
            if slug in seen:
                raise ValueError("Letterboxd repeated a title in the entry feed.")
            seen.add(slug)
            new.append((row.get("rank"), published))
        if not page["items"] and page.get("next"):
            raise ValueError("Letterboxd returned an empty page before the end of the feed.")
    return new, first_ids


def apply_changes(api, member, catalog):
    if catalog.get("usernames") != [PROFILE]:
        raise ValueError("The saved baseline must contain only Cineorgasm.")
    available, liked = roster(api, member)
    liked_ranks = {key: rank for rank, key in enumerate(liked)}
    synced_at = int(time.time() * 1000)
    updated = deepcopy(catalog)
    records = {list_key(row): row for row in updated["lists"]}
    if len(records) != len(updated["lists"]):
        raise ValueError("The saved baseline has duplicate lists.")
    added_entries = []
    changed_lists = 0
    imported_lists = 0
    for key, row in records.items():
        if row["ref"]["relation"] == "liked" or key in liked_ranks:
            row["ref"]["likedRank"] = liked_ranks.get(key)
        listing = available.get(key)
        list_id = listing["id"] if listing else row["ref"].get("letterboxdLID")
        if not list_id or not LID.fullmatch(list_id):
            continue
        source_updated = (listing or {}).get("whenUpdated")
        if source_updated and row["ref"].get("apiUpdatedAt") == source_updated:
            continue
        old = row.get("entries")
        if old is None:
            raise ValueError("The baseline must contain ordered entries for each saved list.")
        delta, first_ids = entries(api, list_id, {title["slug"] for title in old},
                                  row["ref"].get("apiEntryBoundary", []))
        if first_ids:
            row["ref"]["apiEntryBoundary"] = first_ids
        ranked = sorted(((rank, entry) for rank, entry in delta if isinstance(rank, int) and rank > 0),
                        key=lambda pair: pair[0])
        for rank, entry in ranked:
            old.insert(min(rank - 1, len(old)), entry)
        old.extend(entry for rank, entry in reversed(delta) if not isinstance(rank, int) or rank <= 0)
        if delta:
            row["ref"]["syncedAtMillis"] = synced_at
            changed_lists += 1
            added_entries.extend(entry for _, entry in delta)
        row["ref"]["letterboxdLID"] = list_id
        if source_updated:
            row["ref"]["apiUpdatedAt"] = source_updated
    for owner, slug in liked:
        key = owner, slug
        if key in records:
            continue
        listing = available[key]
        full, _ = entries(api, listing["id"])
        fresh = [entry for _, entry in full]
        count = listing.get("filmCount")
        if isinstance(count, int) and count != len(fresh):
            raise ValueError("A new list did not match Letterboxd’s published count.")
        ref = {"owner": owner, "slug": slug, "title": listing["name"], "relation": "liked",
               "letterboxdLID": listing["id"], "syncedAtMillis": synced_at, "likedRank": liked_ranks[key]}
        if listing.get("whenUpdated"):
            ref["apiUpdatedAt"] = listing["whenUpdated"]
        updated["lists"].append({"ref": ref, "entries": fresh, "items": []})
        added_entries.extend(fresh)
        imported_lists += 1
    return updated, added_entries, changed_lists, imported_lists


def save_list_order(assets, catalog):
    path = assets / "letterboxd-offline" / "list-information.json"
    if not path.parent.is_dir():
        return
    information = json.loads(path.read_bytes()) if path.is_file() else {}
    for row in catalog["lists"]:
        ref = row["ref"]
        key = "/".join(list_key(row))
        details = information.setdefault(key, {})
        if "likedRank" in ref:
            if ref["likedRank"] is None:
                details.pop("likedRank", None)
            else:
                details["likedRank"] = ref["likedRank"]
        if ref.get("syncedAtMillis"):
            details["syncedAtMillis"] = ref["syncedAtMillis"]
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(information, ensure_ascii=False, separators=(",", ":")))
    temporary.replace(path)


def load_resolver():
    path = SCRIPTS / "refresh-offline-letterboxd.py"
    spec = spec_from_file_location("refresh_offline_letterboxd", path)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.resolve_slug


def update(state, settings, member, api):
    baseline = state / "current-sync.json"
    if not baseline.is_file():
        raise ValueError("The state folder must have a current-sync.json baseline from the APK build.")
    with (state / ".incremental.lock").open("a") as lock:
        try:
            flock(lock, LOCK_EX | LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("A Cineorgasm update is already running.") from None
        catalog = json.loads(baseline.read_bytes())
        updated, added, changed, imported = apply_changes(api, member, catalog)
        checkpoint = state / "resolved-slugs.json"
        if checkpoint.exists():
            updated["slugToTMDB"].update({k: v for k, v in json.loads(checkpoint.read_bytes()).items() if v})
        candidates = [*updated.get("pendingCineorgasmSlugs", []), *(entry["slug"] for entry in added)]
        novel = [slug for slug in dict.fromkeys(candidates) if slug not in updated["slugToTMDB"]]
        resolved = 0
        pending = []
        if novel:
            with ThreadPoolExecutor(max_workers=6) as pool:
                for slug, match in pool.map(load_resolver(), novel):
                    if match and match.get("tmdbID", 0) > 0:
                        updated["slugToTMDB"][slug] = match
                        resolved += 1
                    else:
                        pending.append(slug)
        if pending:
            updated["pendingCineorgasmSlugs"] = pending
        else:
            updated.pop("pendingCineorgasmSlugs", None)
        if updated == catalog:
            print("Cineorgasm is current. No list entries or liked lists were added.")
            return
        with NamedTemporaryFile(mode="w", encoding="utf-8", dir=state, prefix=".cineorgasm-",
                                suffix=".json", delete=False) as temporary:
            stage = Path(temporary.name)
            json.dump(updated, temporary, ensure_ascii=False, separators=(",", ":"))
        try:
            if added or imported or resolved:
                if settings is None:
                    raise ValueError("Updates with new titles require --settings for TMDB enrichment.")
                builder = str(SCRIPTS / "build-offline-letterboxd.py")
                args = ["--export", str(stage), "--facts", str(state / "tmdb-facts.sqlite")]
                subprocess.run([sys.executable, builder, "enrich", *args, "--settings", str(settings)], check=True)
                ratings = state / "imdb-ratings.tsv.gz"
                if ratings.is_file():
                    args.extend(["--imdb-ratings", str(ratings)])
                subprocess.run([sys.executable, builder, "build", *args, "--assets",
                                str(state / "release-assets")], check=True)
            save_list_order(state / "release-assets", updated)
            stage.replace(baseline)
        finally:
            stage.unlink(missing_ok=True)
        print(f"Cineorgasm: {changed} existing lists gained entries, {imported} newly liked lists imported,"
              f" {len(added)} new placements.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True, help="State folder from the offline APK build")
    parser.add_argument("--settings", type=Path, help="Existing settings export for TMDB enrichment")
    parser.add_argument("--member-lid", default=os.environ.get("LETTERBOXD_MEMBER_LID", ""))
    args = parser.parse_args()
    token = os.environ.get("LETTERBOXD_ACCESS_TOKEN")
    if not token or not args.member_lid:
        parser.error("Set LETTERBOXD_ACCESS_TOKEN and provide the official Cineorgasm member LID.")
    try:
        update(args.state, args.settings, args.member_lid, Letterboxd(token))
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Cineorgasm update stopped. {error}\n")


if __name__ == "__main__":
    main()
