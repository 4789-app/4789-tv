#!/usr/bin/env python3
"""Build the TV's local Letterboxd database from a synced list export and TMDB facts."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import gzip
from hashlib import sha256
from http.client import HTTPSConnection, HTTPException
from io import BytesIO
import json
from pathlib import Path
import re
import sqlite3
import threading
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


ASSET_DIRECTORY = "letterboxd-offline"
POSTER_COUNT = 10  # Six grid positions, plus covers for the directory stack.
POSTER_PATH = re.compile(r"/[A-Za-z0-9]+\.(?:jpg|png)")


def read_export(path):
    export = json.loads(path.read_bytes())
    if export.get("usernames") != ["cineorgasm"]:
        raise ValueError("Expected an export scoped to the Cineorgasm profile.")
    return export


def items(export):
    titles = {}
    ordered_lists = []
    for row in export["lists"]:
        ref = row["ref"]
        if ref["relation"] not in ("liked", "owned"):
            continue
        key = f'{ref["owner"]}/{ref["slug"]}'.lower()
        existing = {}
        for item in row.get("items", []):
            if item.get("tmdbID"):
                media = "series" if item["type"] in ("series", "tv") else "movie"
                identity = media, item["tmdbID"]
                current = titles.setdefault(identity, {})
                for name, value in item.items():
                    if value and not current.get(name):
                        current[name] = value
                existing[identity] = identity
        members = []
        for entry in row.get("entries") or []:
            slug = entry["slug"]
            matched = export["slugToTMDB"].get(slug)
            if matched and matched.get("tmdbID"):
                identity = ("series" if matched["isTV"] else "movie"), matched["tmdbID"]
            else:
                identity = ("movie", f"letterboxd-film:{slug}")
            title = titles.setdefault(identity, {})
            title.setdefault("title", entry["name"])
            if entry.get("year"):
                title.setdefault("year", entry["year"])
            members.append((identity, slug))
        if not members:
            members = [(identity, None) for identity in existing]
        ordered_lists.append((key, ref["title"], members))
    used = {identity for _, _, members in ordered_lists for identity, _ in members}
    return {identity: title for identity, title in titles.items() if identity in used}, ordered_lists


class Tmdb:
    def __init__(self, key, rate):
        self.authorization = {"Authorization": "Bearer " + key} if len(key) > 64 else {}
        self.key = None if self.authorization else key
        self.interval = 1 / rate
        self.next_request = 0.0
        self.lock = threading.Lock()
        self.connections = threading.local()

    def get(self, media, tmdb):
        path = f"/3/{'tv' if media == 'series' else 'movie'}/{tmdb}"
        append = "credits,keywords,external_ids,videos," + ("content_ratings" if media == "series" else "release_dates")
        params = {"language": "en-US", "append_to_response": append}
        if self.key:
            params["api_key"] = self.key
        for attempt in range(4):
            with self.lock:
                target = max(time.monotonic(), self.next_request)
                self.next_request = target + self.interval
            time.sleep(max(0, target - time.monotonic()))
            connection = getattr(self.connections, "http", None)
            if connection is None:
                connection = self.connections.http = HTTPSConnection("api.themoviedb.org", timeout=14)
            try:
                connection.request("GET", f"{path}?{urlencode(params)}", headers=self.authorization)
                response = connection.getresponse()
                body = response.read()
                if response.status == 200:
                    return compact(json.loads(body), media)
                if response.status == 404:
                    return {}
                if response.status in (401, 403):
                    raise PermissionError(f"TMDB returned HTTP {response.status}.")
                if response.status == 429:
                    with self.lock:
                        self.next_request = max(self.next_request, time.monotonic() + 5)
            except (OSError, HTTPException):
                connection.close()
                self.connections.http = None
            time.sleep(attempt + 1)
        return None


def names(values, key="name"):
    return list(dict.fromkeys(row[key] for row in values if row.get(key)))


def compact(row, media):
    credits = row.get("credits") or {}
    keywords = row.get("keywords") or {}
    country_ratings = row.get("content_ratings", {}).get("results", [])
    us_releases = next((v["release_dates"] for v in row.get("release_dates", {}).get("results", [])
                        if v["iso_3166_1"] == "US"), [])
    certification = next((v["rating"] for v in country_ratings if v["iso_3166_1"] == "US" and v.get("rating")), None)
    certification = certification or next((v["certification"] for v in us_releases if v.get("certification")), None)
    date = row.get("release_date") or row.get("first_air_date")
    return {key: value for key, value in {
        "poster": row.get("poster_path"),
        "imdb": row.get("imdb_id") or row.get("external_ids", {}).get("imdb_id"),
        "overview": row.get("overview"),
        "year": int(date[:4]) if date and date[:4].isdigit() else None,
        "releaseDate": date,
        "runtime": row.get("runtime") or next(iter(row.get("episode_run_time", [])), None),
        "tmdbRating": row.get("vote_average") if row.get("vote_count") else None,
        "votes": row.get("vote_count"),
        "popularity": row.get("popularity"),
        "language": row.get("original_language"),
        "countries": names(row.get("production_countries", []), "iso_3166_1") or row.get("origin_country"),
        "spokenLanguages": names(row.get("spoken_languages", []), "iso_639_1"),
        "genres": names(row.get("genres", [])),
        "cast": names(credits.get("cast", []))[:200],
        "directors": names([v for v in credits.get("crew", []) if v.get("job") == "Director"]
                           + row.get("created_by", [])),
        "studios": names(row.get("production_companies", []))[:50],
        "keywords": names(keywords.get("keywords") or keywords.get("results") or [])[:200],
        "status": row.get("status"),
        "certification": certification,
        "hasTrailer": any(v.get("site") == "YouTube" and v.get("type") == "Trailer"
                          for v in row.get("videos", {}).get("results", [])),
    }.items() if value is not None and value != "" and value != []}


def facts_database(path):
    db = sqlite3.connect(path)
    db.execute("pragma journal_mode = wal")
    db.execute("create table if not exists facts (media text, tmdb int, data text, primary key (media, tmdb)) without rowid")
    return db


def enrich(titles, ordered_lists, db, key, rate):
    first = dict.fromkeys(identity for _, _, members in ordered_lists for identity, _ in members[:POSTER_COUNT])
    pending = [t for t in dict.fromkeys([*first, *titles]) if isinstance(t[1], int)]
    cached = set(db.execute("select media, tmdb from facts"))
    pending = [t for t in pending if t not in cached]
    print(f"TMDB details already cached: {len(cached)}; remaining: {len(pending)}", flush=True)
    api = Tmdb(key, rate)
    failed = 0
    with ThreadPoolExecutor(max_workers=20) as pool:
        for count, (identity, fact) in enumerate(zip(pending, pool.map(lambda t: api.get(*t), pending)), 1):
            if fact is None:
                failed += 1
            else:
                db.execute("insert into facts values (?,?,?)",
                           (*identity, json.dumps(fact, ensure_ascii=False, separators=(",", ":"))))
            if count % 250 == 0:
                db.commit()
            if count % 1000 == 0 or count == len(pending):
                print(f"TMDB detail requests: {count}/{len(pending)}; retryable failures: {failed}", flush=True)
    db.commit()


def encode_poster(path, directory, url=None):
    from PIL import Image, ImageOps

    file = directory / (path[1:].split(".")[0] + ".webp")
    if file.exists():
        return True
    try:
        source = url or "https://image.tmdb.org/t/p/w342" + path
        headers = {"User-Agent": "Mozilla/5.0", "Accept": "image/avif,image/webp,image/*,*/*;q=0.8"}
        if urlsplit(source).hostname == "a.ltrbxd.com":
            headers["Referer"] = "https://letterboxd.com/"
        with urlopen(Request(source, headers=headers), timeout=18) as response:
            data = response.read(1024 * 1024)
        with Image.open(BytesIO(data)) as image:
            if image.width > image.height:
                return False
            preview = ImageOps.contain(image.convert("RGB"), (240, 360))
            temp = file.with_suffix(".tmp")
            preview.save(temp, "WEBP", quality=75, method=4)
            temp.replace(file)
        return True
    except (OSError, ValueError):
        return False


def secondary_poster(slug, imdb, directory):
    if imdb and re.fullmatch(r"tt[0-9]{5,16}", imdb):
        path = "/IMDB" + imdb + ".jpg"
        if encode_poster(path, directory, f"https://images.metahub.space/poster/medium/{imdb}/img"):
            return path
    if not slug or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,220}", slug):
        return None
    try:
        request = Request(f"https://letterboxd.com/film/{slug}/", headers={"User-Agent": "Mozilla/5.0"})
        with urlopen(request, timeout=14) as response:
            if urlsplit(response.url).hostname != "letterboxd.com":
                return None
            html = response.read(1_500_000).decode()
        for match in re.finditer(r'"image"\s*:\s*("(?:[^"\\]|\\.)*")', html):
            url = json.loads(match[1])
            image = urlsplit(url)
            if image.scheme == "https" and image.netloc == "a.ltrbxd.com":
                path = "/LB" + sha256(slug.encode()).hexdigest()[:24] + ".jpg"
                if encode_poster(path, directory, url):
                    return path
    except (OSError, ValueError):
        pass
    path = "/LB" + sha256(slug.encode()).hexdigest()[:24] + ".jpg"
    if encode_poster(path, directory, f"https://letterboxd.com/film/{slug}/image-150/"):
        return path
    return None


def build(titles, ordered_lists, facts_db, destination, allow_incomplete, rating_file):
    target = destination / ASSET_DIRECTORY
    target.mkdir(parents=True, exist_ok=True)
    poster_directory = target / "posters"
    poster_directory.mkdir(exist_ok=True)
    facts = {(media, tmdb): json.loads(data) for media, tmdb, data in facts_db.execute("select * from facts")}
    ratings = {}
    if rating_file:
        known = {v["imdb"] for v in facts.values() if v.get("imdb")}
        known.update(v["imdbID"] for v in titles.values() if v.get("imdbID"))
        with gzip.open(rating_file, "rt", encoding="utf-8") as source:
            if next(source).strip() != "tconst\taverageRating\tnumVotes":
                raise ValueError("Unexpected IMDb ratings format")
            for line in source:
                key, rating, _ = line.split("\t")
                if key in known:
                    ratings[key] = float(rating)
    missing = [key for key in titles if isinstance(key[1], int) and key not in facts]
    if missing and not allow_incomplete:
        raise ValueError(f"TMDB facts still need to be fetched for {len(missing)} titles.")
    starter_paths = {}
    for _, _, members in ordered_lists:
        for identity, slug in members[:POSTER_COUNT]:
            raw = facts.get(identity, {}).get("poster") or titles[identity].get("posterURL", "").rsplit("/", 1)[-1]
            path = "/" + raw.lstrip("/") if raw else ""
            starter_paths.setdefault(identity, (path if POSTER_PATH.fullmatch(path) else None, slug))
    required = sorted({path for path, _ in starter_paths.values() if path})
    with ThreadPoolExecutor(max_workers=8) as pool:
        installed = {path for path, ok in zip(required, pool.map(lambda p: encode_poster(p, poster_directory), required)) if ok}
    missing_artwork = [(identity, slug) for identity, (path, slug) in starter_paths.items() if path not in installed]
    with ThreadPoolExecutor(max_workers=4) as pool:
        alternatives = {
            identity: path for (identity, _), path in zip(
                missing_artwork, pool.map(
                    lambda item: secondary_poster(item[1], facts.get(item[0], {}).get("imdb")
                                                 or titles[item[0]].get("imdbID"), poster_directory), missing_artwork
                )
            ) if path
        }
    unavailable = sum(identity not in alternatives for identity, _ in missing_artwork)
    print(f"Starter posters saved: {len(installed) + len(alternatives)} files; {unavailable} distinct starter titles without artwork.", flush=True)
    if not installed and not alternatives and starter_paths and not allow_incomplete:
        raise ValueError("No starter artwork could be included")
    keys = {identity: idx for idx, identity in enumerate(titles, 1)}
    temp = target / "catalog.tmp"
    temp.unlink(missing_ok=True)
    with sqlite3.connect(temp) as db:
        db.executescript("""
            pragma user_version=1;
            create table list (id text primary key, name text not null, count int not null) without rowid;
            create table title (id integer primary key, media text not null, canonical text not null,
              name text not null, year int, poster text, facts text not null);
            create table member (list_id text not null, position int not null, title_id int not null,
              slug text, primary key (list_id, position)) without rowid;
            create index title_poster on title(poster);
            create index member_slug on member(slug);
        """)
        def records():
            for identity, entry in titles.items():
                media, provider = identity
                fact = facts.get(identity, {}).copy()
                for field, maximum in (("cast", 200), ("directors", 50), ("studios", 50), ("keywords", 200)):
                    if field in fact:
                        fact[field] = fact[field][:maximum]
                raw = fact.pop("poster", None) or entry.get("posterURL", "").rsplit("/", 1)[-1]
                poster = alternatives.get(identity) or ("/" + raw.lstrip("/") if raw else "")
                for output, former in (("overview", "overview"), ("language", "languageRaw"), ("imdb", "imdbID"),
                                       ("tmdbRating", "rating"), ("votes", "votes"), ("releaseDate", "releasedAt"),
                                       ("genres", "genres")):
                    if output not in fact and entry.get(former):
                        value = entry[former]
                        if output == "releaseDate" and isinstance(value, (int, float)):
                            value = (datetime(2001, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=value)).date().isoformat()
                        fact[output] = value
                if fact.get("imdb") in ratings:
                    fact["imdbRating"] = ratings[fact["imdb"]]
                year = fact.pop("year", None) or entry.get("year")
                canonical = f"tmdb:{provider}" if isinstance(provider, int) else provider
                yield keys[identity], media, canonical, entry["title"], year, poster if POSTER_PATH.fullmatch(poster) else None, json.dumps(fact, ensure_ascii=False, separators=(",", ":"))
        db.executemany("insert into title values (?,?,?,?,?,?,?)", records())
        db.executemany("insert into list values (?,?,?)", [(key, name, len(members)) for key, name, members in ordered_lists])
        db.executemany("insert into member values (?,?,?,?)", [
            (key, position, keys[identity], slug) for key, _, members in ordered_lists
            for position, (identity, slug) in enumerate(members)
        ])
        if db.execute("pragma integrity_check").fetchone() != ("ok",):
            raise ValueError("Generated catalog failed integrity check.")
    temp.replace(target / "catalog.sqlite")
    print(f"Catalog: {len(ordered_lists)} lists, {len(titles)} titles, {sum(len(rows) for _, _, rows in ordered_lists)} placements.")
    print(f"TMDB rows unavailable: {sum(not facts.get(key) for key in titles if isinstance(key[1], int))}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["enrich", "build"])
    parser.add_argument("--export", type=Path, required=True)
    parser.add_argument("--facts", type=Path, required=True)
    parser.add_argument("--settings", type=Path)
    parser.add_argument("--resolved-slugs", type=Path)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--imdb-ratings", type=Path)
    parser.add_argument("--rate", type=float, default=25)
    parser.add_argument("--allow-incomplete", action="store_true", help="Build a local test fixture before enrichment finishes.")
    args = parser.parse_args()
    if not 0 < args.rate <= 30:
        parser.error("--rate must be above 0 and at most 30")
    export = read_export(args.export)
    if args.resolved_slugs:
        aliases = json.loads(args.resolved_slugs.read_text())
        export["slugToTMDB"].update({slug: value for slug, value in aliases.items() if value})
    titles, lists = items(export)
    with facts_database(args.facts) as db:
        if args.mode == "enrich":
            if args.settings is None:
                parser.error("enrich requires --settings")
            key = json.loads(args.settings.read_bytes())["tmdbAPIKey"].strip()
            if not key:
                parser.error("TMDB credentials are missing")
            enrich(titles, lists, db, key, args.rate)
        else:
            if args.assets is None:
                parser.error("build requires --assets")
            build(titles, lists, db, args.assets, args.allow_incomplete, args.imdb_ratings)


if __name__ == "__main__":
    main()
