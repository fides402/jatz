"""Mainstream US hip-hop coverage — the side RapManiacZ (IT, underground)
and POW Mag (US, underground/mid-tier) don't reach: neither writes up
Kanye West, Denzel Curry, or any other headline-name drop reliably, so a
"famous to underground" catalogue needs a third source that actually
covers the famous end.

HotNewHipHop posts a dedicated, machine-parseable title for essentially
every notable single/album/EP announcement -- "{title} – Album by
{artist}", "{title} – Song by {artist}" -- alongside a much higher
volume of ordinary news/gossip. That structure means these can be
regex-parsed directly with no LLM call at all (more reliable than the
free-model extraction rap_extract.py needs for RapManiacZ/POW Mag's
freeform prose, and it doesn't burn free-tier quota).

The catch, confirmed live: the feed only exposes the last ~10 posts, and
at HotNewHipHop's publishing rate (10+ posts within a single day, mixed
release announcements and general news) that's a few hours of coverage,
not a week. A weekly check would silently skip almost everything, so
this runs on the DAILY cron instead (see .github/workflows/
daily_rap_mainstream.yml) -- the 10-item window comfortably covers one
day at this rate.
"""
from __future__ import annotations

import html
import re
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import requests

FEED_URL = "https://www.hotnewhiphop.com/feed"
_UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
}

# "{title} – Album by {artist}" / "... – Song by ..." / "... – EP by ..."
# En dash (U+2013), not a hyphen -- confirmed against the feed's actual bytes.
_RELEASE_TITLE = re.compile(
    r"^(?P<title>.+?)\s+–\s+(?P<kind>Album|Song|EP)\s+by\s+(?P<artist>.+)$"
)
_KIND_MAP = {"album": "album", "song": "single", "ep": "ep"}


def _strip_cdata(s: str) -> str:
    m = re.match(r"^<!\[CDATA\[(.*)\]\]>$", s.strip(), re.DOTALL)
    return html.unescape((m.group(1) if m else s).strip())


def fetch_structured_releases(within_hours: int = 30) -> list[dict]:
    """Returns [{artist, title, kind, region: "us", source_title, source_link}]
    parsed straight from the feed's own structured titles -- no LLM."""
    try:
        resp = requests.get(FEED_URL, headers=_UA, timeout=20)
        resp.raise_for_status()
        resp.encoding = "utf-8"
        xml = resp.text
    except Exception as e:
        print(f"[rap-mainstream] fetch failed for HotNewHipHop: {e}", flush=True)
        return []

    cutoff = datetime.now(timezone.utc) - timedelta(hours=within_hours)
    out = []
    for item_xml in re.findall(r"<item>(.*?)</item>", xml, re.DOTALL):
        title_m = re.search(r"<title>(.*?)</title>", item_xml, re.DOTALL)
        link_m = re.search(r"<link>(.*?)</link>", item_xml, re.DOTALL)
        date_m = re.search(r"<pubDate>(.*?)</pubDate>", item_xml, re.DOTALL)
        if not title_m:
            continue
        title = _strip_cdata(title_m.group(1))

        if date_m:
            try:
                published = parsedate_to_datetime(date_m.group(1).strip())
                if published.tzinfo is None:
                    published = published.replace(tzinfo=timezone.utc)
                if published < cutoff:
                    continue
            except Exception:
                pass

        m = _RELEASE_TITLE.match(title)
        if not m:
            continue

        out.append({
            "artist": m.group("artist").strip(),
            "title": m.group("title").strip(),
            "kind": _KIND_MAP.get(m.group("kind").lower(), "single"),
            "region": "us",
            "source_title": title,
            "source_link": _strip_cdata(link_m.group(1)) if link_m else "",
        })

    print(f"[rap-mainstream] HotNewHipHop: {len(out)} structured release post(s) "
          f"within {within_hours}h", flush=True)
    return out
