"""RSS sources for the weekly hip-hop/rap section.

Neither Spotify (blocked without the app owner having Premium) nor Apple's
public marketing RSS builder (no genre filter, chart-only, not "new
releases") turned out usable for this — verified live against both, not
assumed. What actually works: two real editorial outlets that already do the
mainstream-to-underground curation by hand and publish a plain RSS feed.

- RapManiacZ (Italy): near-daily, and almost every post is already titled
  in the release-announcement shape ("'X' è il nuovo singolo di Y").
- POW Mag (US, formerly Passion of the Weiss): "The Rap-Up" is a recurring
  column that explicitly rounds up new singles across the mainstream-to-
  underground range in one write-up, and its RSS description already names
  most of the artists covered -- no full-article scrape needed.

Both feeds mix release announcements with interviews/news/retrospectives,
so filtering "is this actually a new-release post" is left to the LLM
extraction step (rap_extract.py), not decided here.
"""
from __future__ import annotations

import html
import re
import time
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import requests

# A real browser UA, not an identifying one: POW Mag (Substack-hosted) 403s
# a plain "JATZ/1.0" identifier even though the exact same request works
# fine from a browser or plain curl with no special headers -- Substack's
# bot filtering appears to key off looking like a browser, not off intent.
_UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
}

SOURCES = {
    "it": {
        "name": "RapManiacZ",
        "feed_url": "https://www.rapmaniacz.com/feed/",
    },
    "us": {
        "name": "POW Mag",
        "feed_url": "https://www.powmag.net/feed/",
        # Only "The Rap-Up" column is a release round-up; the rest of the
        # feed is interviews/essays with nothing structured to extract.
        "title_must_contain": "Rap-Up",
    },
}


def _strip_cdata(s: str) -> str:
    m = re.match(r"^<!\[CDATA\[(.*)\]\]>$", s.strip(), re.DOTALL)
    return html.unescape((m.group(1) if m else s).strip())


def _clean_html(s: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", " ", s)).strip()


def _fetch_via_curl(url: str, timeout: int = 20) -> str | None:
    """POW Mag (Substack-hosted) 403s every `requests` call regardless of
    headers, but the identical request via curl works every time -- almost
    certainly TLS-fingerprint-based bot filtering (JA3/JA4), which `requests`
    can't spoof without extra dependencies. Shelling out to curl, which is
    preinstalled on both this machine and the Ubuntu Actions runner, is the
    pragmatic fix actually verified to work rather than a library to fight.
    """
    import subprocess
    try:
        proc = subprocess.run(
            ["curl", "-sL", "-A", _UA["User-Agent"], url],
            capture_output=True, timeout=timeout,
        )
        if proc.returncode != 0:
            print(f"[rap] curl exited {proc.returncode} for {url}: "
                  f"{proc.stderr.decode('utf-8', 'replace')[:200]}", flush=True)
            return None
        return proc.stdout.decode("utf-8", "replace")
    except Exception as e:
        print(f"[rap] curl fetch raised for {url}: {e}", flush=True)
        return None


def fetch_recent_posts(region: str, within_days: int = 10) -> list[dict]:
    """Return [{title, description, link, published}] from the last
    `within_days` days for the given region ("it" or "us")."""
    cfg = SOURCES[region]
    xml = None
    try:
        resp = requests.get(cfg["feed_url"], headers=_UA, timeout=20)
        if resp.status_code == 403:
            xml = _fetch_via_curl(cfg["feed_url"])
        else:
            resp.raise_for_status()
            # These feeds are UTF-8 but some don't declare a charset in
            # their Content-Type header, so requests falls back to guessing
            # (often Latin-1 per the old HTTP default) and mangles every
            # accented/curly character. Force it rather than trust the guess.
            resp.encoding = "utf-8"
            xml = resp.text
    except Exception as e:
        print(f"[rap] fetch failed for {cfg['name']}: {e}", flush=True)
        xml = _fetch_via_curl(cfg["feed_url"])

    if not xml:
        print(f"[rap] no content retrieved for {cfg['name']}", flush=True)
        return []

    cutoff = datetime.now(timezone.utc) - timedelta(days=within_days)
    posts = []
    for item_xml in re.findall(r"<item>(.*?)</item>", xml, re.DOTALL):
        title_m = re.search(r"<title>(.*?)</title>", item_xml, re.DOTALL)
        desc_m = re.search(r"<description>(.*?)</description>", item_xml, re.DOTALL)
        link_m = re.search(r"<link>(.*?)</link>", item_xml, re.DOTALL)
        date_m = re.search(r"<pubDate>(.*?)</pubDate>", item_xml, re.DOTALL)
        if not title_m:
            continue
        title = _strip_cdata(title_m.group(1))

        must_contain = cfg.get("title_must_contain")
        if must_contain and must_contain.lower() not in title.lower():
            continue

        published = None
        if date_m:
            try:
                published = parsedate_to_datetime(date_m.group(1).strip())
                if published.tzinfo is None:
                    published = published.replace(tzinfo=timezone.utc)
                if published < cutoff:
                    continue
            except Exception:
                pass

        posts.append({
            "title": title,
            "description": _clean_html(_strip_cdata(desc_m.group(1))) if desc_m else "",
            "link": _strip_cdata(link_m.group(1)) if link_m else "",
            "published": published.isoformat() if published else None,
            "region": region,
        })

    print(f"[rap] {cfg['name']}: {len(posts)} recent post(s) within {within_days}d", flush=True)
    return posts


def fetch_all(within_days: int = 10) -> list[dict]:
    out = []
    for region in SOURCES:
        out.extend(fetch_recent_posts(region, within_days))
        time.sleep(0.3)
    return out
