#!/usr/bin/env python3
"""Entry point for the nightly GitHub Actions job.

Writes ``drops/<date>.json`` plus a refreshed ``drops/index.json``, and updates
the curation state so tomorrow's run doesn't repeat today's records.

    python run_daily.py                  # today's drop
    python run_daily.py --date 2026-08-28
    python run_daily.py --seed-build     # the drop bundled into the first APK
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jatz import curate, state  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DROPS = os.path.join(REPO, "drops")


def write_index() -> dict:
    os.makedirs(DROPS, exist_ok=True)
    dates = sorted(
        f[:-5] for f in os.listdir(DROPS)
        if f.endswith(".json") and f != "index.json"
    )
    index = {
        "latest": dates[-1] if dates else None,
        "count": len(dates),
        "dates": dates,
    }
    with open(os.path.join(DROPS, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    return index


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=None, help="YYYY-MM-DD (default: today)")
    ap.add_argument("--seed", type=int, default=None, help="RNG seed")
    ap.add_argument("--seed-build", action="store_true",
                    help="also write app/src/main/assets/seed_drop.json")
    ap.add_argument("--force", action="store_true",
                    help="regenerate even if today's drop already exists")
    args = ap.parse_args()

    day = date.fromisoformat(args.date) if args.date else date.today()
    out = os.path.join(DROPS, f"{day.isoformat()}.json")

    if os.path.exists(out) and not args.force:
        print(f"[JATZ] {out} already exists — nothing to do.")
        write_index()
        return 0

    drop = curate.build_drop(day, seed=args.seed)
    n = len(drop["albums"])
    if n == 0:
        print("[JATZ] FATAL: no albums survived curation.", file=sys.stderr)
        return 1

    os.makedirs(DROPS, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(drop, f, ensure_ascii=False, indent=1)

    # Commit the exclusions only after the drop is safely on disk, so a crash
    # mid-run can be retried without having burned the records it found.
    state.add_seen_releases(a["discogsId"] for a in drop["albums"])
    state.touch_artists((a["artist"] for a in drop["albums"]), day)

    idx = write_index()

    if args.seed_build:
        seed_path = os.path.join(REPO, "app", "src", "main", "assets", "seed_drop.json")
        os.makedirs(os.path.dirname(seed_path), exist_ok=True)
        with open(seed_path, "w", encoding="utf-8") as f:
            json.dump(drop, f, ensure_ascii=False, indent=1)
        print(f"[JATZ] seed written to {seed_path}")

    print(f"\n[JATZ] wrote {out}")
    print(f"[JATZ] {n} albums "
          f"({drop['counts']['vintage']} vintage / {drop['counts']['modern']} modern), "
          f"{idx['count']} drops total")
    for a in drop["albums"]:
        print(f"   [{a['era'][:4]}] {a['artist']} - {a['title']} ({a['year']}) "
              f"vibe {a['vibe']}% [{a['confidence']}]")

    # A short drop is worth flagging in the CI log without failing the run —
    # the app copes with fewer than 5, and failing would block the commit.
    if n < 5:
        print(f"\n[JATZ] WARNING: only {n}/5 albums.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
