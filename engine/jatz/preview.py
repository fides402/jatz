"""30-second preview finder — the piece that keeps YouTube out of the CI job.

digmore feeds CLAP by downloading a snippet from YouTube with yt-dlp. From a
GitHub Actions runner that fails almost every time ("Sign in to confirm you're
not a bot"), because YouTube blocks datacenter IP ranges, and the usual fix is
exactly the cookie file JATZ is meant to do without.

So the nightly job never touches YouTube. Previews come from:
  1. iTunes Search API  — free, no key, 30s AAC/M4A
  2. Deezer API         — free, no key, 30s MP3

YouTube only enters on the phone, where NewPipeExtractor resolves a playable
stream from a residential IP with no key and no cookies.
"""
from __future__ import annotations

import difflib
import re
import time
import unicodedata

import requests

ITUNES = "https://itunes.apple.com/search"
DEEZER = "https://api.deezer.com/search"

_UA = {"User-Agent": "JATZ/1.0 (+https://github.com/fides402/jatz)"}

# Bracketed qualifiers ("(Remastered 2011)", "[Live]", "- Take 3") wreck string
# matching against streaming catalogues, which title tracks differently.
_PAREN = re.compile(r"[\(\[\{].*?[\)\]\}]")
_NONWORD = re.compile(r"[^a-z0-9\s]")
_WS = re.compile(r"\s+")


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower()
    s = _PAREN.sub(" ", s)
    s = _NONWORD.sub(" ", s)
    return _WS.sub(" ", s).strip()


def _title_match(a: str, b: str) -> float:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    # A short title being a clean prefix/substring of a longer one is a match
    # ("Calm" vs "Calm - Remastered"), but only when the short one is
    # substantial enough that the containment isn't accidental.
    if len(na) >= 5 and (na in nb or nb in na):
        return 0.92
    return difflib.SequenceMatcher(None, na, nb).ratio()


def _artist_match(a: str, b: str) -> float:
    ta = set(normalize(a).split())
    tb = set(normalize(b).split())
    if not ta or not tb:
        return 0.0
    # Token overlap beats sequence ratio here: Discogs writes
    # "Lonnie Liston Smith & The Cosmic Echoes", iTunes writes
    # "Lonnie Liston Smith". Jaccard would punish that; coverage of the
    # smaller set does not.
    return len(ta & tb) / min(len(ta), len(tb))


def _score(cand_artist: str, cand_title: str, artist: str, title: str) -> float:
    t = _title_match(title, cand_title)
    a = _artist_match(artist, cand_artist)
    return 0.65 * t + 0.35 * a


def _from_itunes(artist: str, title: str, timeout: float) -> tuple[str, float] | None:
    try:
        r = requests.get(ITUNES, params={
            "term": f"{artist} {title}",
            "media": "music",
            "entity": "song",
            "limit": 12,
        }, headers=_UA, timeout=timeout)
        r.raise_for_status()
        # iTunes serves JSON with a text/javascript content type; .json() copes,
        # but an empty body does not.
        results = (r.json() or {}).get("results", [])
    except Exception:
        return None

    best, best_s = None, 0.0
    for it in results:
        url = it.get("previewUrl")
        if not url:
            continue
        s = _score(it.get("artistName", ""), it.get("trackName", ""), artist, title)
        if s > best_s:
            best, best_s = url, s
    return (best, best_s) if best else None


def _from_deezer(artist: str, title: str, timeout: float) -> tuple[str, float] | None:
    try:
        r = requests.get(DEEZER, params={
            "q": f'artist:"{artist}" track:"{title}"',
            "limit": 12,
        }, headers=_UA, timeout=timeout)
        r.raise_for_status()
        results = (r.json() or {}).get("data", [])
    except Exception:
        return None

    best, best_s = None, 0.0
    for it in results:
        url = it.get("preview")
        if not url:
            continue
        s = _score((it.get("artist") or {}).get("name", ""),
                   it.get("title", ""), artist, title)
        if s > best_s:
            best, best_s = url, s
    return (best, best_s) if best else None


def find_preview(artist: str, title: str, min_score: float = 0.62,
                 timeout: float = 12.0) -> dict | None:
    """Return {url, source, match} for a 30s preview, or None.

    `min_score` guards against the failure mode that would quietly poison the
    whole profile: a wrong-but-confident match means CLAP scores some unrelated
    record and JATZ recommends it. Refusing to score is better than scoring the
    wrong audio, so a weak match is discarded.
    """
    for name, fn in (("itunes", _from_itunes), ("deezer", _from_deezer)):
        hit = fn(artist, title, timeout)
        time.sleep(0.25)
        if hit and hit[1] >= min_score:
            return {"url": hit[0], "source": name, "match": round(hit[1], 3)}
    return None


def download(url: str, dest: str, timeout: float = 25.0) -> bool:
    try:
        r = requests.get(url, headers=_UA, timeout=timeout, stream=True)
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_content(65536):
                if chunk:
                    f.write(chunk)
        import os
        return os.path.getsize(dest) > 8192
    except Exception:
        return False
