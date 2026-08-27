"""Resolves an extracted {artist, title, kind} triple to real metadata,
cover art, and — critically for "kind": "album" releases — the REAL
tracklist, not a single placeholder track (a one-track "album" gets
resolved by YoutubeResolver as a search for that one title, which on
YouTube usually turns up a single "full album" video instead of the
individual songs).

iTunes Search API first (same as preview.py's audio-preview matching, no
key, no auth): these are often brand-new singles that Discogs — a
physical-release-first, community-catalogued database — hasn't had time
to catalogue yet, while iTunes/Apple Music updates same-day for anything
properly distributed. Deezer's public search API (also no key) is tried
second, for the underground/regional releases iTunes has no listing for
at all -- confirmed live against both APIs before building this: iTunes
returns zero candidates for several real, currently-charting regional
singles that Deezer does have.
"""
from __future__ import annotations

import difflib
import re
import time
import unicodedata
from datetime import date

import requests

ITUNES_SEARCH = "https://itunes.apple.com/search"
ITUNES_LOOKUP = "https://itunes.apple.com/lookup"
DEEZER_SEARCH = "https://api.deezer.com/search"
DEEZER_ALBUM_SEARCH = "https://api.deezer.com/search/album"
DEEZER_ALBUM_TRACKS = "https://api.deezer.com/album/{id}/tracks"
_UA = {"User-Agent": "JATZ/1.0 (+https://github.com/fides402/jatz)"}

_NONWORD = re.compile(r"[^a-z0-9\s]")
_WS = re.compile(r"\s+")


def _normalize(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower()
    s = _NONWORD.sub(" ", s)
    return _WS.sub(" ", s).strip()


def _score(cand_artist: str, cand_title: str, artist: str, title: str) -> tuple[float, float]:
    """Returns (combined, title_score). Both matter separately: a same-
    artist candidate with an unrelated title can clear a combined-only
    threshold on artist match alone (confirmed live -- "Cartunez" by Ken
    Carson matched a completely different Ken Carson album, "XTENDED",
    at combined score 0.64, purely because artist_score=1.0 carried it),
    so callers must gate on title_score too, not just the blend."""
    ta = _normalize(title)
    ca = _normalize(cand_title)
    title_score = difflib.SequenceMatcher(None, ta, ca).ratio() if ta and ca else 0.0

    at = set(_normalize(artist).split())
    act = set(_normalize(cand_artist).split())
    artist_score = (len(at & act) / min(len(at), act and len(act) or 1)) if at and act else 0.0

    return 0.6 * title_score + 0.4 * artist_score, title_score


# A same-artist, wrong-title candidate can otherwise clear the combined
# threshold on artist match alone -- require the title to genuinely
# resemble the announced one too.
_MIN_TITLE_SCORE = 0.55


def _stub_tracks(title: str, artist: str) -> list[dict]:
    return [{"position": "1", "title": title, "artist": artist, "duration": ""}]


def _unresolved(artist: str, title: str, kind: str) -> dict:
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
        "tracks": _stub_tracks(title, artist),
    }


# ---- iTunes -----------------------------------------------------------

def _itunes_search(artist: str, title: str, entity: str, timeout: float) -> tuple[dict | None, float]:
    try:
        r = requests.get(ITUNES_SEARCH, params={
            "term": f"{artist} {title}",
            "media": "music",
            "entity": entity,
            "limit": 8,
        }, headers=_UA, timeout=timeout)
        r.raise_for_status()
        results = (r.json() or {}).get("results", [])
    except Exception as e:
        print(f"[rap-resolve] iTunes search failed for \"{artist} - {title}\" "
              f"(entity={entity}): {e}", flush=True)
        return None, 0.0

    best, best_score = None, 0.0
    for it in results:
        cand_title = it.get("collectionName") if entity == "album" else it.get("trackName")
        s, ts = _score(it.get("artistName", ""), cand_title or "", artist, title)
        if ts < _MIN_TITLE_SCORE:
            continue
        if s > best_score:
            best, best_score = it, s
    return best, best_score


def _itunes_album_tracklist(collection_id, timeout: float) -> list[dict]:
    """Real per-track listing for an iTunes album match via the Lookup API --
    the Search API's `entity=album` hits are collection records with no
    track list of their own."""
    if not collection_id:
        return []
    try:
        r = requests.get(ITUNES_LOOKUP, params={"id": collection_id, "entity": "song"},
                          headers=_UA, timeout=timeout)
        r.raise_for_status()
        results = (r.json() or {}).get("results", [])
    except Exception as e:
        print(f"[rap-resolve] iTunes tracklist lookup failed for collection "
              f"{collection_id}: {e}", flush=True)
        return []

    tracks = []
    for it in results:
        if it.get("wrapperType") != "track":
            continue
        tracks.append({
            "position": str(it.get("trackNumber") or len(tracks) + 1),
            "title": it.get("trackName") or "",
            "artist": it.get("artistName") or "",
            "duration": "",
        })
    tracks.sort(key=lambda t: int(t["position"]) if t["position"].isdigit() else 999)
    return tracks


def _resolve_itunes(artist: str, title: str, kind: str, timeout: float) -> dict | None:
    entity = "album" if kind == "album" else "song"
    best, best_score = _itunes_search(artist, title, entity, timeout)

    # An "album" search can miss on naming/reissue quirks that a plain song
    # search catches -- worth one more try before giving up. Re-point
    # `entity` at whichever search actually won, since the fields read
    # below (collectionName vs trackName) depend on it.
    if (best is None or best_score < 0.45) and entity == "album":
        fallback_best, fallback_score = _itunes_search(artist, title, "song", timeout)
        if fallback_best is not None and fallback_score > best_score:
            best, best_score, entity = fallback_best, fallback_score, "song"

    if best is None or best_score < 0.45:
        return None

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

    tracks = _stub_tracks(real_title or title, real_artist)
    if kind == "album" and entity == "album":
        looked_up = _itunes_album_tracklist(best.get("collectionId"), timeout)
        if looked_up:
            tracks = looked_up

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
        "tracks": tracks,
    }


# ---- Deezer (fallback for releases iTunes has no listing for at all) --

def _deezer_candidates(artist: str, title: str, timeout: float) -> list[tuple[str, dict]]:
    out: list[tuple[str, dict]] = []
    try:
        r = requests.get(DEEZER_SEARCH, params={"q": f"{artist} {title}"}, timeout=timeout)
        r.raise_for_status()
        out += [("track", it) for it in (r.json() or {}).get("data", [])[:8]]
    except Exception as e:
        print(f"[rap-resolve] Deezer track search failed for \"{artist} - {title}\": {e}", flush=True)
    try:
        r = requests.get(DEEZER_ALBUM_SEARCH, params={"q": f"{artist} {title}"}, timeout=timeout)
        r.raise_for_status()
        out += [("album", it) for it in (r.json() or {}).get("data", [])[:8]]
    except Exception as e:
        print(f"[rap-resolve] Deezer album search failed for \"{artist} - {title}\": {e}", flush=True)
    return out


def _deezer_album_tracklist(album_id, timeout: float) -> list[dict]:
    if not album_id:
        return []
    try:
        r = requests.get(DEEZER_ALBUM_TRACKS.format(id=album_id), timeout=timeout)
        r.raise_for_status()
        data = (r.json() or {}).get("data", [])
    except Exception as e:
        print(f"[rap-resolve] Deezer tracklist fetch failed for album {album_id}: {e}", flush=True)
        return []

    tracks = []
    for i, it in enumerate(data, start=1):
        tracks.append({
            "position": str(it.get("track_position") or i),
            "title": it.get("title") or "",
            "artist": it.get("artist", {}).get("name", ""),
            "duration": "",
        })
    return tracks


def _resolve_deezer(artist: str, title: str, kind: str, timeout: float) -> dict | None:
    candidates = _deezer_candidates(artist, title, timeout)
    best, best_score, best_kind = None, 0.0, None
    for kind_, it in candidates:
        cand_artist = it.get("artist", {}).get("name", "")
        cand_title = it.get("title") or ""
        s, ts = _score(cand_artist, cand_title, artist, title)
        if ts < _MIN_TITLE_SCORE:
            continue
        if s > best_score:
            best, best_score, best_kind = it, s, kind_

    if best is None or best_score < 0.45:
        return None

    real_title = best.get("title") or title
    real_artist = best.get("artist", {}).get("name", artist)

    if best_kind == "album":
        cover = best.get("cover_big") or best.get("cover_medium") or ""
        album_id = best.get("id")
    else:
        cover = (best.get("album") or {}).get("cover_big") \
            or (best.get("album") or {}).get("cover_medium") or ""
        album_id = (best.get("album") or {}).get("id")

    tracks = _stub_tracks(real_title, real_artist)
    if kind == "album" and album_id:
        looked_up = _deezer_album_tracklist(album_id, timeout)
        if looked_up:
            tracks = looked_up

    return {
        "id": f"rap-deezer-{best.get('id') or abs(hash(artist + title))}",
        "discogsId": 0,
        "title": real_title,
        "artist": real_artist,
        "year": 0,
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
        "tracks": tracks,
    }


def resolve(artist: str, title: str, kind: str, timeout: float = 12.0) -> dict | None:
    """Returns an AlbumDto-shaped dict, or None if no good enough match."""
    album = _resolve_itunes(artist, title, kind, timeout)
    if album is not None:
        return album

    album = _resolve_deezer(artist, title, kind, timeout)
    if album is not None:
        print(f"[rap-resolve] \"{artist} - {title}\" not on iTunes, matched on Deezer instead", flush=True)
        return album

    # Neither catalogue has it -- genuinely too new/underground/self-
    # released to be indexed anywhere yet. Keep it with the raw editorial
    # metadata rather than silently dropping it (see module docstring):
    # a "famous to underground" catalogue that drops everything it can't
    # find cover art for stops being an underground catalogue at all.
    print(f"[rap-resolve] no confident match anywhere for \"{artist} - {title}\" "
          f"-- keeping as unresolved (no cover art)", flush=True)
    return _unresolved(artist, title, kind)


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
