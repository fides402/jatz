"""Dedup state for the weekly rap section — separate file from the jazz
engine's state.py (engine/state/seen_releases.json etc.) since this is a
different content stream on a different cadence, not the jazz library.
"""
from __future__ import annotations

import json
import os

_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE_FILE = os.path.join(_HERE, "state", "seen_rap_releases.json")


def _key(artist: str, title: str) -> str:
    return f"{artist.strip().lower()}::{title.strip().lower()}"


def load_seen() -> set[str]:
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return set(json.load(f))
    except (OSError, json.JSONDecodeError):
        return set()


def save_seen(keys: set[str]) -> None:
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(sorted(keys), f, ensure_ascii=False, indent=1)


def filter_unseen(releases: list[dict]) -> list[dict]:
    seen = load_seen()
    return [r for r in releases if _key(r["artist"], r["title"]) not in seen]


def mark_seen(releases: list[dict]) -> None:
    seen = load_seen()
    for r in releases:
        seen.add(_key(r["artist"], r["title"]))
    save_seen(seen)
