#!/usr/bin/env python3
"""Entry point for the weekly hip-hop/rap section's GitHub Actions job.

Separate cadence and separate content stream from the jazz engine's daily
run_daily.py: this fetches two editorial RSS feeds (RapManiacZ for Italy,
POW Mag's "Rap-Up" column for the US -- see rap_sources.py for why these two
and not Spotify/Apple/Discogs), extracts structured releases from their
prose via an LLM, resolves each to real cover art via iTunes, and writes
drops_rap/<iso-week>.json + drops_rap/index.json, mirroring the shape
run_daily.py already produces so the Android app's existing DropDto/AlbumDto
model needs no new fields -- era holds "IT"/"US" instead of "VINTAGE"/
"MODERN" for this profile.

    python run_weekly_rap.py
    python run_weekly_rap.py --force   # even if this week's file exists
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jatz import rap_extract, rap_resolve, rap_sources, rap_state  # noqa: E402

REPO = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(REPO)
DROPS_DIR = os.path.join(REPO, "drops_rap")


def _iso_week_id(day: date) -> str:
    y, w, _ = day.isocalendar()
    return f"{y}-W{w:02d}"


def write_index() -> dict:
    os.makedirs(DROPS_DIR, exist_ok=True)
    weeks = sorted(
        f[:-5] for f in os.listdir(DROPS_DIR)
        if f.endswith(".json") and f != "index.json"
    )
    index = {"latest": weeks[-1] if weeks else None, "count": len(weeks), "weeks": weeks}
    with open(os.path.join(DROPS_DIR, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    return index


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true",
                    help="regenerate even if this week's file already exists")
    args = ap.parse_args()

    today = date.today()
    week_id = _iso_week_id(today)
    out_path = os.path.join(DROPS_DIR, f"{week_id}.json")

    if os.path.exists(out_path) and not args.force:
        print(f"[rap] {out_path} already exists — nothing to do.")
        write_index()
        return 0

    posts = rap_sources.fetch_all(within_days=10)
    if not posts:
        print("[rap] no posts fetched from either source — nothing to write this week.")
        return 0

    extracted: list[dict] = []
    for post in posts:
        found = rap_extract.extract_releases(post)
        if found:
            names = ", ".join(f"{r['artist']} - {r['title']}" for r in found)
            print(f"[rap] \"{post['title']}\" -> {len(found)} release(s): {names}", flush=True)
        extracted.extend(found)
        time.sleep(1.0)   # be polite to the free OpenRouter tier

    unseen = rap_state.filter_unseen(extracted)
    print(f"[rap] {len(extracted)} extracted, {len(unseen)} not seen before", flush=True)

    albums = rap_resolve.resolve_all(unseen)
    if not albums:
        print("[rap] nothing resolved to real metadata this week.", file=sys.stderr)
        return 0

    drop = {
        "date": week_id,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "profile": "rap",
        "albums": albums,
        "counts": {
            "vintage": sum(1 for a in albums if a["era"] == "IT"),
            "modern": sum(1 for a in albums if a["era"] == "US"),
        },
    }

    os.makedirs(DROPS_DIR, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(drop, f, ensure_ascii=False, indent=1)

    # Only mark releases as seen once the drop is safely on disk.
    rap_state.mark_seen(unseen)

    idx = write_index()
    print(f"\n[rap] wrote {out_path}")
    print(f"[rap] {len(albums)} releases ({drop['counts']['vintage']} IT / "
          f"{drop['counts']['modern']} US), {idx['count']} weeks total")
    for a in albums:
        print(f"   [{a['era']}] {a['artist']} - {a['title']} ({a['year']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
