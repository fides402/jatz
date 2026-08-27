"""Discogs search tuned for JATZ's two time windows.

Derived from digmore's ``discogs_ext.py`` + the vendored ``discogs_hunter``, but
album-centric instead of track-centric: JATZ's unit of delivery is a *record*,
so we keep the full tracklist and score the release as a whole.

The token comes from the environment only. digmore had a hardcoded fallback
token in ``engine_libs/discogs_hunter.py``; this repo is public, so there is no
fallback here — a missing DISCOGS_TOKEN is a hard error.
"""
from __future__ import annotations

import os
import random
import re
import time
from datetime import date

import requests

BASE = "https://api.discogs.com"
_DISAMBIG = re.compile(r"\s*\(\d+\)\s*$")

VINTAGE_FROM, VINTAGE_TO = 1968, 1983
MODERN_FROM = 2018


def modern_to() -> int:
    """Upper bound of the modern window = the current year, always.

    Deliberately computed at run time so the app keeps reaching for new records
    in 2027, 2030, ... without a code change.
    """
    return date.today().year


# Countries whose pressings are out of scope for this crate-digging vibe
# (kept identical to digmore's list).
EXCLUDED_COUNTRIES = frozenset({
    "India", "IN", "China", "CN", "People's Republic of China",
})

# The vintage recipe is digmore's jazz GENRE_MAP verbatim — it is already tuned
# against the same CLAP profile vector we score with.
VINTAGE_SEARCHES = [
    {"genre": "Jazz"},
    {"genre": "Jazz", "style": "Bossa Nova"},
    {"genre": "Jazz", "style": "Jazz-Funk"},
    {"genre": "Jazz", "style": "Fusion"},
    {"genre": "Jazz", "style": "Soul-Jazz"},
    {"genre": "Jazz", "style": "Modal"},
    {"genre": "Jazz", "style": "Latin Jazz"},
    {"genre": "Jazz", "style": "Cool Jazz"},
]

# Modern jazz sits under different Discogs styles than the 70s material: the
# UK/LA spiritual-jazz wave is tagged Contemporary Jazz / Post Bop, and the
# beat-adjacent side lands under Electronic > Nu Jazz rather than Jazz at all.
# Searching only the vintage styles would return almost nothing post-2018.
MODERN_SEARCHES = [
    {"genre": "Jazz"},
    {"genre": "Jazz", "style": "Contemporary Jazz"},
    {"genre": "Jazz", "style": "Jazz-Funk"},
    {"genre": "Jazz", "style": "Fusion"},
    {"genre": "Jazz", "style": "Modal"},
    {"genre": "Jazz", "style": "Post Bop"},
    {"genre": "Jazz", "style": "Soul-Jazz"},
    {"genre": "Jazz", "style": "Free Improvisation"},
    {"genre": "Electronic", "style": "Nu Jazz"},
]


def _headers() -> dict:
    token = os.environ.get("DISCOGS_TOKEN", "").strip()
    if not token:
        raise RuntimeError(
            "DISCOGS_TOKEN is not set. Add it to the repo's GitHub Secrets "
            "(Settings > Secrets and variables > Actions)."
        )
    return {
        "Authorization": f"Discogs token={token}",
        "User-Agent": "JATZ/1.0 +https://github.com/fides402/jatz",
    }


def clean_name(name: str) -> str:
    """Strip Discogs' ``Artist (2)`` disambiguation suffix."""
    return _DISAMBIG.sub("", name or "").strip()


def _get(path: str, params: dict | None = None, retries: int = 2) -> dict:
    for attempt in range(retries + 1):
        try:
            r = requests.get(f"{BASE}{path}", params=params,
                             headers=_headers(), timeout=15)
            if r.status_code == 429:
                # Discogs' documented limit is 60 req/min for authenticated
                # calls; back off a full window rather than hammering.
                time.sleep(60)
                continue
            r.raise_for_status()
            return r.json()
        except requests.RequestException:
            if attempt == retries:
                raise
            time.sleep(1.5)
    return {}


def search_releases(searches: list[dict], year_from: int, year_to: int,
                    max_have: int, n: int, exclude_ids: set[int],
                    combos: int = 4, pages_per_combo: int = 3) -> list[dict]:
    """Sample `n` releases across a few random (genre, style) combos.

    Randomising both the combo and the page means two consecutive days reach
    different slices of the catalogue, which matters a lot once the exclusion
    set grows.
    """
    chosen = random.sample(searches, min(combos, len(searches)))
    out: list[dict] = []
    seen_ids: set[int] = set()

    for s in chosen:
        params = {"per_page": 50, "type": "release", "page": 1}
        if s.get("genre"):
            params["genre"] = s["genre"]
        if s.get("style"):
            params["style"] = s["style"]
        # Discogs' year filter on /database/search accepts a "from-to" range.
        params["year"] = f"{year_from}-{year_to}"

        try:
            probe = _get("/database/search", params)
        except Exception:
            continue
        total_pages = min((probe.get("pagination") or {}).get("pages", 1), 50)
        pages = list(range(1, total_pages + 1))
        random.shuffle(pages)

        for pg in pages[:min(pages_per_combo, total_pages)]:
            params["page"] = pg
            try:
                results = _get("/database/search", params).get("results", [])
            except Exception:
                continue
            for r in results:
                rid = r.get("id")
                if not rid or rid in seen_ids or rid in exclude_ids:
                    continue
                # The `year` search param is honoured loosely by Discogs, so
                # re-check it locally.
                yr = int(r.get("year") or 0)
                if not (year_from <= yr <= year_to):
                    continue
                if int((r.get("community") or {}).get("have", 0)) > max_have:
                    continue
                if (r.get("country") or "") in EXCLUDED_COUNTRIES:
                    continue
                seen_ids.add(rid)
                out.append(r)
            time.sleep(0.4)

    random.shuffle(out)
    return out[:n]


def get_release_detail(release_id: int) -> dict:
    """Full tracklist + community rating + cover, in one detail call."""
    data = _get(f"/releases/{release_id}")
    comm = data.get("community") or {}
    rating = comm.get("rating") or {}

    album_artists = [clean_name(a["name"]) for a in data.get("artists", [])]
    album_artist = " & ".join(album_artists) if album_artists else ""

    tracks = []
    for t in data.get("tracklist", []):
        title = (t.get("title") or "").strip()
        if not title:
            continue
        # Skip headings and index entries — they have no playable content.
        if (t.get("type_") or "track") != "track":
            continue
        ta = t.get("artists", [])
        artist = clean_name(ta[0]["name"]) if ta else album_artist
        tracks.append({
            "position": (t.get("position") or "").strip(),
            "title": title,
            "artist": artist,
            "duration": (t.get("duration") or "").strip(),
        })

    images = data.get("images") or []
    cover = ""
    for im in images:
        if im.get("type") == "primary":
            cover = im.get("uri") or im.get("resource_url") or ""
            break
    if not cover and images:
        cover = images[0].get("uri") or ""

    labels = data.get("labels") or []
    return {
        "album_artist": album_artist,
        "tracks": tracks,
        "rating_avg": float(rating.get("average") or 0.0),
        "rating_count": int(rating.get("count") or 0),
        "cover_image": cover,
        "label": (labels[0].get("name") if labels else ""),
        "country": data.get("country", ""),
        "year": int(data.get("year") or 0),
        "genres": data.get("genres") or [],
        "styles": data.get("styles") or [],
        "notes": (data.get("notes") or "")[:600],
    }
