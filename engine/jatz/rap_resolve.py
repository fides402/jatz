"""Resolves an extracted {artist, title} pair to real metadata + cover art.

iTunes Search API again (same as preview.py's audio-preview matching, no
key, no auth) rather than Discogs: these are often brand-new singles that
Discogs — a physical-release-first, community-catalogued database — simply
hasn't been given time to catalogue yet. iTunes/Apple Music's catalogue
updates same-day for anything properly distributed, which new singles are.
"""
from __future__ import annotations

import difflib
import re
import time
import unicodedata
from datetime import date

import requests

ITUNES = "https://itunes.apple.com/search"
_UA = {"User-Agent": "JATZ/1.0 (+https://github.com/fides402/jatz)"}

_NONWORD = re.compile(r"[^a-z0-9\s]")
_WS = re.compile(r"\s+")


def _normalize(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower()
    s = _NONWORD.sub(" ", s)
    return _WS.sub(" ", s).strip()


def _score(cand_artist: str, cand_title: str, artist: str, title: str) -> float:
    ta = _normalize(title)
    ca = _normalize(cand_title)
    title_score = difflib.SequenceMatcher(None, ta, ca).ratio() if ta and ca else 0.0

    at = set(_normalize(artist).split())
    act = set(_normalize(cand_artist).split())
    artist_score = (len(at & act) / min(len(at), act and len(act) or 1)) if at and act else 0.0

    return 0.6 * title_score + 0.4 * artist_score


def _search(artist: str, title: str, entity: str, timeout: float) -> tuple[dict | None, float]:
    try:
        r = requests.get(ITUNES, params={
            "term": f"{artist} {title}",
            "media": "music",
            "entity": entity,
            "limit": 8,
        }, headers=_UA, timeout=timeout)
        r.raise_for_status()
        results = (r.json() or {}).get("results", [])
    except Exception as e:
        print(f"[rap-resolve] iTunes lookup failed for \"{artist} - {title}\" "
              f"(entity={entity}): {e}", flush=True)
        return None, 0.0

    best, best_score = None, 0.0
    for it in results:
        cand_title = it.get("collectionName") if entity == "album" else it.get("trackName")
        s = _score(it.get("artistName", ""), cand_title or "", artist, title)
        if s > best_score:
            best, best_score = it, s
    return best, best_score


def resolve(artist: str, title: str, kind: str, timeout: float = 12.0) -> dict | None:
    """Returns an AlbumDto-shaped dict, or None if no good enough match."""
    entity = "album" if kind == "album" else "song"
    best, best_score = _search(artist, title, entity, timeout)

    # An "album" search can miss on naming/reissue quirks that a plain song
    # search catches (e.g. the album is catalogued under a slightly
    # different collection name but the lead track matches cleanly) --
    # worth one more try before giving up on a real release. Re-point
    # `entity` at whichever search actually won, since the fields read
    # below (collectionName vs trackName) depend on it.
    if (best is None or best_score < 0.45) and entity == "album":
        fallback_best, fallback_score = _search(artist, title, "song", timeout)
        if fallback_best is not None and fallback_score > best_score:
            best, best_score, entity = fallback_best, fallback_score, "song"

    if best is None or best_score < 0.45:
        # No confident iTunes match -- often the release is real but too new
        # or too underground/self-distributed to be catalogued there yet.
        # Dropping it here would silently defeat the "famous to underground"
        # requirement this section exists for, so keep it with the raw
        # editorial metadata and no cover art rather than discarding it.
        found = "no candidates at all" if best is None else f"best candidate scored {best_score:.2f}"
        print(f"[rap-resolve] no confident iTunes match for \"{artist} - {title}\" "
              f"({found}) -- keeping as unresolved (no cover art)", flush=True)
        return {
            "id": f"rap-unresolved-{abs(hash(artist + title))}",
            "discogsId": 0,
            "title": title,
            "artist": artist,
            "year": date.today().year,
            "label": "",
            "country": "",
            "styles": [kind],
            "coverUrl": "",
            "ratingAvg": 0.0,
            "ratingCount": 0,
            "score": 0.0,
            "vibe": 0,
            "confidence": "editorial-unresolved",
            "scoredTracks": 0,
            "notes": "",
            "tracks": [{"position": "1", "title": title, "artist": artist, "duration": ""}],
        }

    cover = (best.get("artworkUrl100") or "").replace("100x100bb", "600x600bb")
    release_year = 0
    release_date = best.get("releaseDate", "")
    if release_date and len(release_date) >= 4:
        try:
            release_year = int(release_date[:4])
        except ValueError:
            pass

    real_title = best.get("collectionName") if entity == "album" else best.get("trackName")
    real_artist = best.get("artistName", artist)

    track = {
        "position": "1",
        "title": best.get("trackName", title),
        "artist": real_artist,
        "duration": "",
    }

    return {
        "id": f"rap-{best.get('trackId') or best.get('collectionId') or abs(hash(artist + title))}",
        "discogsId": 0,
        "title": real_title or title,
        "artist": real_artist,
        "year": release_year,
        "label": "",
        "country": "",
        "styles": [kind],
        "coverUrl": cover,
        "ratingAvg": 0.0,
        "ratingCount": 0,
        "score": round(best_score, 3),
        "vibe": 0,
        "confidence": "editorial",
        "scoredTracks": 0,
        "notes": "",
        "tracks": [track],
    }


def resolve_all(releases: list[dict]) -> list[dict]:
    out = []
    for rel in releases:
        album = resolve(rel["artist"], rel["title"], rel.get("kind", "single"))
        time.sleep(0.25)
        if album is None:
            continue
        album["era"] = rel["region"].upper()   # "IT" or "US", reusing AlbumDto's era field
        out.append(album)
    return out
