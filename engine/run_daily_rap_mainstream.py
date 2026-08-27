#!/usr/bin/env python3
"""Daily mainstream-coverage top-up for the rap section (see
.github/workflows/daily_rap_mainstream.yml). Separate cadence from
run_weekly_rap.py (RapManiacZ/POW Mag, underground, checked weekly)
because HotNewHipHop's feed only exposes ~a day of posts at its
publishing rate -- a weekly check would miss almost everything (see
jatz/rap_mainstream.py's module docstring for the measurement behind
this).

Writes into the SAME drops_rap/<iso-week>.json the weekly job uses,
merging in any newly resolved mainstream releases rather than
replacing the file, since this can run several times within one ISO
week before the weekly underground job also touches it.

    python run_daily_rap_mainstream.py
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jatz import rap_mainstream, rap_resolve, rap_state  # noqa: E402

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
    week_id = _iso_week_id(date.today())
    out_path = os.path.join(DROPS_DIR, f"{week_id}.json")

    releases = rap_mainstream.fetch_structured_releases(within_hours=30)
    if not releases:
        print("[rap-mainstream] nothing structured fetched today.")
        return 0

    unseen = rap_state.filter_unseen(releases)
    print(f"[rap-mainstream] {len(releases)} parsed, {len(unseen)} not seen before", flush=True)
    if not unseen:
        return 0

    albums = rap_resolve.resolve_all(unseen)
    if not albums:
        print("[rap-mainstream] nothing resolved to real metadata today.", file=sys.stderr)
        return 0

    existing_albums: list[dict] = []
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            existing_albums = (json.load(f) or {}).get("albums", [])
    existing_ids = {a["id"] for a in existing_albums}
    merged = existing_albums + [a for a in albums if a["id"] not in existing_ids]

    drop = {
        "date": week_id,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "profile": "rap",
        "albums": merged,
        "counts": {
            "vintage": sum(1 for a in merged if a["era"] == "IT"),
            "modern": sum(1 for a in merged if a["era"] == "US"),
        },
    }

    os.makedirs(DROPS_DIR, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(drop, f, ensure_ascii=False, indent=1)

    # Only mark releases as seen once the drop is safely on disk.
    rap_state.mark_seen(unseen)

    idx = write_index()
    print(f"\n[rap-mainstream] wrote {out_path}")
    print(f"[rap-mainstream] {len(albums)} new this run, {len(merged)} total this week, "
          f"{idx['count']} weeks total")
    for a in albums:
        print(f"   [{a['era']}] {a['artist']} - {a['title']} ({a['year']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
