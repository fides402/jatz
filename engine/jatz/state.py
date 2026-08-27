"""Persistent curation state, committed back to the repo by the nightly job.

Two things have to survive between runs or the daily drop degenerates:
  * every release already delivered, so a record is never proposed twice
  * when each artist last appeared, so the drop doesn't become
    "Lonnie Liston Smith again, every Tuesday"
"""
from __future__ import annotations

import json
import os
from datetime import date, timedelta

_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE_DIR = os.path.join(_HERE, "state")

ARTIST_COOLDOWN_DAYS = 30


def _path(name: str) -> str:
    return os.path.join(STATE_DIR, name)


def _load(name: str, default):
    try:
        with open(_path(name), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def _save(name: str, data) -> None:
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(_path(name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)


def seen_release_ids() -> set[int]:
    return {int(x) for x in _load("seen_releases.json", [])}


def add_seen_releases(ids) -> None:
    cur = seen_release_ids() | {int(i) for i in ids}
    _save("seen_releases.json", sorted(cur))


def artist_last_seen() -> dict[str, str]:
    return _load("seen_artists.json", {})


def artists_on_cooldown(today: date | None = None) -> set[str]:
    """Lowercased artist names that appeared within the cooldown window."""
    today = today or date.today()
    cutoff = today - timedelta(days=ARTIST_COOLDOWN_DAYS)
    out = set()
    for name, iso in artist_last_seen().items():
        try:
            if date.fromisoformat(iso) >= cutoff:
                out.add(name)
        except ValueError:
            continue
    return out


def touch_artists(names, today: date | None = None) -> None:
    today = today or date.today()
    cur = artist_last_seen()
    for n in names:
        key = (n or "").strip().lower()
        if key:
            cur[key] = today.isoformat()
    _save("seen_artists.json", cur)
