"""Turns one RSS post's prose into structured (artist, title) releases via
an LLM — the posts themselves are hand-written editorial text ("we break
down new singles from X, Y, Z"), not structured data, so there's no
Discogs-style API to parse instead.

Same OpenRouter free-model pattern as digmore's DigChat (engine/app.py):
a fallback list of free models, one call at a time, short backoff on
429/502/503. Reused deliberately rather than reinvented.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time

import requests

_OR_SEM = threading.Semaphore(1)

# A hardcoded fallback list is not enough on its own: OpenRouter's free tier
# rotates models often enough that this exact list went 100% 404 (every
# single one deprecated/renamed) between when it was first written and when
# it was tested minutes later. So this is refreshed from the live
# /v1/models endpoint at the start of every run and used FIRST; the
# hardcoded names below are only the last resort if that fetch itself fails
# (e.g. no network), not the primary source of truth.
_FALLBACK_MODELS = [
    "z-ai/glm-5.2:free",
    "google/gemma-4-31b-it:free",
    "minimax/minimax-m3:free",
    "nvidia/nemotron-3-super-120b-a12b:free",
]


# Confirmed live, twice, to return an empty `content` for every single
# request regardless of prompt -- not transient flakiness, these two
# specific free models just don't work for a plain chat completion the way
# this is called (possibly they need a different request shape). Skipped
# outright rather than burning a real attempt (and real free-tier quota) on
# them every single time.
_KNOWN_EMPTY_MODELS = {
    "dots-studio/dots-3-note-preview:free",
    "liquid/lfm-2.5-2.6b:free",
}


def _live_free_models(key: str) -> list[str]:
    try:
        resp = requests.get(
            "https://openrouter.ai/api/v1/models",
            headers={"Authorization": f"Bearer {key}"},
            timeout=15,
        )
        resp.raise_for_status()
        models = resp.json().get("data", [])
        free = [
            m["id"] for m in models
            if m.get("id", "").endswith(":free") and m["id"] not in _KNOWN_EMPTY_MODELS
        ]
        if free:
            print(f"[rap-llm] {len(free)} free models fetched live", flush=True)
            return free
    except Exception as e:
        print(f"[rap-llm] could not fetch live model list, using fallback: {e}", flush=True)
    return list(_FALLBACK_MODELS)

# Plain concatenation, not str.format()/an f-string template: the JSON
# example below contains literal { } that .format() would try to parse as
# fields (and did, the first time -- KeyError: '"artist"').
_PROMPT_HEADER = """You read one post from a hip-hop/rap news site and extract every \
NEW release it announces (a single, EP, album, or music video for a song). \
The post may announce several releases at once, or none at all (e.g. it \
might be an interview, a news story, an award, a festival lineup, a \
retrospective/history piece) -- in that case return an empty list.

Only include something that is genuinely a NEW song/release being \
announced or reviewed, not a mention of an old classic in passing.

Reply with ONLY a JSON array (no prose, no markdown fences), each item:
{"artist": "...", "title": "...", "kind": "single|ep|album"}
"""


def _build_prompt(post_title: str, post_text: str) -> str:
    return f"{_PROMPT_HEADER}\nPost title: {post_title}\nPost text: {post_text}\n"


_models_cache: list[str] | None = None


def _call_openrouter(messages: list, max_tokens: int = 1600) -> str:
    global _models_cache
    key = os.environ.get("OPENROUTER_KEY", "").strip()
    if not key:
        raise RuntimeError(
            "OPENROUTER_KEY is not set. Add it to the repo's GitHub Secrets."
        )
    if _models_cache is None:
        # Cap at 6: with 17+ free models in rotation, trying all of them
        # sequentially on a bad day would burn the run's time budget for
        # little benefit over the first few.
        _models_cache = _live_free_models(key)[:6]

    with _OR_SEM:
        last_err = None
        for attempt, model in enumerate(_models_cache):
            try:
                resp = requests.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    headers={
                        "Authorization": f"Bearer {key}",
                        "HTTP-Referer": "https://github.com/fides402/jatz",
                        "X-Title": "JATZ",
                        "Content-Type": "application/json",
                    },
                    json={"model": model, "messages": messages,
                          "temperature": 0.2, "max_tokens": max_tokens},
                    timeout=45,
                )
                if resp.status_code == 429:
                    wait = 15 + attempt * 10
                    print(f"[rap-llm] 429 on {model}, waiting {wait}s", flush=True)
                    time.sleep(wait)
                    continue
                if resp.status_code in (502, 503):
                    time.sleep(3)
                    continue
                resp.raise_for_status()
                content = resp.json()["choices"][0]["message"]["content"]
                if not content:
                    # Seen with some free "reasoning" models that return a
                    # populated `reasoning` field but leave `content` null --
                    # a real reply we can't use, not an HTTP-level failure.
                    print(f"[rap-llm] {model} returned empty content, trying next", flush=True)
                    continue
                return content
            except Exception as e:
                last_err = e
                print(f"[rap-llm] error on {model}: {e}", flush=True)
                time.sleep(2)
                continue
    raise RuntimeError(f"OpenRouter unavailable after trying all models: {last_err}")


def _parse_json_array(text: str) -> list[dict]:
    # Models sometimes wrap the array in ```json fences despite instructions
    # not to -- strip those before parsing rather than failing on them.
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.MULTILINE)
    match = re.search(r"\[.*\]", cleaned, re.DOTALL)
    if not match:
        return []
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError:
        return []
    out = []
    for item in data if isinstance(data, list) else []:
        artist = (item.get("artist") or "").strip()
        title = (item.get("title") or "").strip()
        kind = (item.get("kind") or "single").strip().lower()
        if artist and title:
            out.append({"artist": artist, "title": title, "kind": kind})
    return out


def extract_releases(post: dict) -> list[dict]:
    """post: {title, description, ...} from rap_sources.fetch_all()."""
    prompt = _build_prompt(post["title"], post.get("description", "")[:1500])
    try:
        reply = _call_openrouter([{"role": "user", "content": prompt}])
    except Exception as e:
        print(f"[rap-llm] extraction failed for \"{post['title']}\": {e}", flush=True)
        return []

    releases = _parse_json_array(reply)
    for r in releases:
        r["region"] = post["region"]
        r["source_title"] = post["title"]
        r["source_link"] = post.get("link", "")
    return releases
