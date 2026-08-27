"""The nightly curator: produce one day's drop of 5 records.

3 from 1968-1983 + 2 from 2018-(current year), all judged against the same
frozen jazz profile vector. Album-level scoring: a record is sampled on a few
tracks, each track scored by CLAP cosine against the profile, and the record
takes the mean.
"""
from __future__ import annotations

import os
import random
import tempfile
import time
from dataclasses import dataclass, field
from datetime import date

from . import clap_score, discogs, preview, state

# How many tracks per record we try to score. Three is enough to characterise a
# record while keeping the nightly API + CLAP budget small; scoring an entire
# tracklist would multiply the run time for very little extra signal.
TRACKS_SAMPLED = 3
MIN_SCORED_TRACKS = 2      # below this the record is penalised as thin evidence

# Audio cosine thresholds, calibrated against the first real run with working
# preview scoring (2026-08-27): genuine 2-3-track-mean cosines against 30s
# iTunes/Deezer previews landed mostly in 0.35-0.66, not the 0.72-0.78 digmore
# sees scoring a single hand-picked full track. 0.74/0.72 let essentially
# nothing real through and every pick fell back to the (weaker) text-only
# guess -- exactly backwards, since real audio evidence should always be
# trusted over a guess when it's available. Revisit after more real runs
# accumulate; this is a taste parameter, not a constant to get "correct" once.
VINTAGE_MIN_SCORE = 0.50
MODERN_MIN_SCORE = 0.50


@dataclass
class EraConfig:
    name: str
    searches: list
    year_from: int
    year_to: int
    want: int
    max_have: int
    min_rating: float
    min_votes: int
    min_score: float
    pool: int                       # releases to evaluate to fill `want`
    diag: dict = field(default_factory=dict)


def vintage_config() -> EraConfig:
    return EraConfig(
        name="VINTAGE",
        searches=discogs.VINTAGE_SEARCHES,
        year_from=discogs.VINTAGE_FROM,
        year_to=discogs.VINTAGE_TO,
        want=3,
        # A hard obscurity gate. digmore also ran a Last.fm fame filter, but
        # that needs an API key we will not put in a public repo, and Discogs'
        # own "have" count is the better proxy anyway: it measures the record,
        # not the artist, so an obscure side project by a known name still gets
        # through.
        max_have=800,
        min_rating=3.8,
        min_votes=2,
        min_score=VINTAGE_MIN_SCORE,
        pool=26,
    )


def modern_config() -> EraConfig:
    return EraConfig(
        name="MODERN",
        searches=discogs.MODERN_SEARCHES,
        year_from=discogs.MODERN_FROM,
        year_to=discogs.modern_to(),
        want=2,
        # Recent records are collected far less on Discogs and rated by far
        # fewer people. Applying the vintage gates here would return an empty
        # set most nights, so both the popularity cap and the rating gate are
        # relaxed on this side.
        max_have=3000,
        min_rating=3.4,
        min_votes=5,
        min_score=MODERN_MIN_SCORE,
        pool=30,
    )


def _score_release(rel: dict, detail: dict, profile) -> dict:
    """Attach an audio (or fallback text) score to one release."""
    tracks = detail["tracks"]
    sampled = random.sample(tracks, min(TRACKS_SAMPLED, len(tracks)))

    sims: list[float] = []
    evidence: list[dict] = []
    tmpdir = tempfile.mkdtemp(prefix="jatz_")
    try:
        for t in sampled:
            artist = t["artist"] or detail["album_artist"]
            hit = preview.find_preview(artist, t["title"])
            if not hit:
                evidence.append({"title": t["title"], "preview": None})
                continue
            ext = ".m4a" if hit["source"] == "itunes" else ".mp3"
            path = os.path.join(tmpdir, f"{abs(hash(t['title'])) % 10**8}{ext}")
            if not preview.download(hit["url"], path):
                evidence.append({"title": t["title"], "preview": "download_failed"})
                continue
            emb = clap_score.embed_audio_file(path)
            if emb is None:
                evidence.append({"title": t["title"], "preview": "decode_failed"})
                continue
            sim = clap_score.cosine(emb, profile)
            sims.append(sim)
            evidence.append({
                "title": t["title"],
                "preview": hit["source"],
                "match": hit["match"],
                "clap": round(sim, 4),
            })
    finally:
        try:
            import shutil
            shutil.rmtree(tmpdir, ignore_errors=True)
        except Exception:
            pass

    if sims:
        mean = sum(sims) / len(sims)
        # Thin evidence (a single scoreable track) is real but weaker; nudge it
        # down so a record with 3 solid tracks outranks a one-track guess at the
        # same mean.
        if len(sims) < MIN_SCORED_TRACKS:
            mean -= 0.02
        return {
            "score": mean,
            "confidence": "high" if len(sims) >= MIN_SCORED_TRACKS else "low",
            "scored_tracks": len(sims),
            "evidence": evidence,
        }

    # No preview anywhere. Rather than dropping a potentially great obscure
    # record, fall back to CLAP's text side and mark it clearly.
    txt = clap_score.text_affinity(detail["styles"], rel.get("title", ""),
                                   detail["album_artist"])
    return {
        # Capped at 0.55 (below the ~0.55-0.66 range genuine good audio
        # matches land in — see VINTAGE_MIN_SCORE above) so a text-only guess
        # can pass the same bar on real confidence, but never outranks actual
        # audio evidence in the sort that follows.
        "score": 0.40 + 0.15 * txt,
        "confidence": "text_only",
        "scored_tracks": 0,
        "evidence": evidence,
    }


def curate_era(cfg: EraConfig, profile, exclude_ids: set[int],
               cooldown: set[str]) -> list[dict]:
    print(f"\n=== {cfg.name} {cfg.year_from}-{cfg.year_to} "
          f"(want {cfg.want}) ===", flush=True)

    releases = discogs.search_releases(
        cfg.searches, cfg.year_from, cfg.year_to,
        max_have=cfg.max_have, n=cfg.pool, exclude_ids=exclude_ids,
    )
    print(f"[{cfg.name}] {len(releases)} candidate releases", flush=True)

    diag = {"found": len(releases), "rating_rejected": 0, "cooldown_rejected": 0,
            "no_tracks": 0, "below_threshold": 0, "errors": 0}
    scored: list[dict] = []

    for rel in releases:
        if len(scored) >= cfg.want * 3:
            break                      # enough to rank from; stop spending API
        try:
            detail = discogs.get_release_detail(rel["id"])
            time.sleep(0.4)            # Discogs ~60 req/min

            if detail["rating_count"] >= cfg.min_votes and \
               detail["rating_avg"] < cfg.min_rating:
                diag["rating_rejected"] += 1
                continue
            if not detail["tracks"]:
                diag["no_tracks"] += 1
                continue

            artist = detail["album_artist"] or (rel.get("title", "").split(" - ")[0])
            if artist.strip().lower() in cooldown:
                diag["cooldown_rejected"] += 1
                continue

            res = _score_release(rel, detail, profile)
            print(f"  [{cfg.name}] {artist} - {rel.get('title','')} "
                  f"({detail['year']}) score={res['score']:.3f} "
                  f"({res['confidence']}, {res['scored_tracks']} tracks)", flush=True)

            if res["score"] < cfg.min_score:
                diag["below_threshold"] += 1
                continue

            scored.append({"rel": rel, "detail": detail, "artist": artist, **res})
        except Exception as e:
            diag["errors"] += 1
            print(f"  [{cfg.name}] error on {rel.get('id')}: {e}", flush=True)
            continue

    scored.sort(key=lambda x: x["score"], reverse=True)

    # Never two records by the same artist in one drop.
    picked, used = [], set()
    for s in scored:
        key = s["artist"].strip().lower()
        if key in used:
            continue
        used.add(key)
        picked.append(s)
        if len(picked) >= cfg.want:
            break

    cfg.diag = diag
    print(f"[{cfg.name}] picked {len(picked)}/{cfg.want}  diag={diag}", flush=True)
    return picked


def to_album_json(s: dict, era: str) -> dict:
    rel, detail = s["rel"], s["detail"]
    return {
        "id": f"d{rel['id']}",
        "discogsId": rel["id"],
        "title": (rel.get("title") or "").split(" - ", 1)[-1].strip() or rel.get("title", ""),
        "artist": s["artist"],
        "year": detail["year"] or int(rel.get("year") or 0),
        "era": era,
        "label": detail["label"],
        "country": detail["country"],
        "styles": detail["styles"],
        "coverUrl": detail["cover_image"],
        "ratingAvg": round(detail["rating_avg"], 2),
        "ratingCount": detail["rating_count"],
        "score": round(s["score"], 4),
        "vibe": clap_score.vibe_pct(s["score"]),
        "confidence": s["confidence"],
        "scoredTracks": s["scored_tracks"],
        "notes": detail["notes"],
        "tracks": [
            {
                "position": t["position"] or str(i + 1),
                "title": t["title"],
                "artist": t["artist"] or s["artist"],
                "duration": t["duration"],
            }
            for i, t in enumerate(detail["tracks"])
        ],
    }


def build_drop(day: date | None = None, seed: int | None = None) -> dict:
    day = day or date.today()
    if seed is not None:
        random.seed(seed)

    profile = clap_score.load_profile("jazz")
    meta = clap_score.profile_meta("jazz")
    print(f"[JATZ] jazz profile: {meta.get('n_tracks')} reference tracks", flush=True)

    exclude = state.seen_release_ids()
    cooldown = state.artists_on_cooldown(day)
    print(f"[JATZ] excluding {len(exclude)} seen releases, "
          f"{len(cooldown)} artists on cooldown", flush=True)

    albums: list[dict] = []
    for cfg, era in ((vintage_config(), "VINTAGE"), (modern_config(), "MODERN")):
        for s in curate_era(cfg, profile, exclude, cooldown):
            albums.append(to_album_json(s, era))
            exclude.add(s["rel"]["id"])
            cooldown.add(s["artist"].strip().lower())

    return {
        "date": day.isoformat(),
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "profile": "jazz",
        "albums": albums,
        "counts": {
            "vintage": sum(1 for a in albums if a["era"] == "VINTAGE"),
            "modern": sum(1 for a in albums if a["era"] == "MODERN"),
        },
    }
