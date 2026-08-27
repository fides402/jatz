"""CLAP scoring against the frozen jazz profile vector.

The profile vector (``profiles/jazz.npy``) is digmore's, unchanged: a 512-dim
L2-normalised mean of the CLAP embeddings of 9 reference tracks (Lonnie Liston
Smith, Wayne Shorter, Stanley Clarke, Joe Zawinul, Nancy Wilson...). Reusing it
verbatim is the whole point — it is the part of digmore that encodes the taste.
"""
from __future__ import annotations

import json
import os
import tempfile
import threading

import numpy as np
import soundfile as sf

_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE_DIR = os.path.join(_HERE, "profiles")

_MODEL = None
# laion-clap wraps a single shared torch module that is not thread-safe; every
# forward pass goes through this lock (same constraint digmore documents).
_LOCK = threading.Lock()

# Zero-shot text prompts, copied from digmore's clap_model.GENRE_PROMPTS["jazz"].
# Used only as the fallback when no audio preview exists for a record.
JAZZ_PROMPTS = [
    "soul jazz instrumental recording with piano upright bass and brushed drums",
    "1970s jazz fusion sample with electric piano saxophone and modal harmonies",
    "hard bop jazz with trumpet muted and walking bass line",
    "jazz funk groove with organ comping and crisp snare rim shots",
    "contemporary jazz with sophisticated chord voicings and vibraphone",
]

_NOT_JAZZ_PROMPTS = [
    "loud distorted rock guitar with screaming vocals",
    "four on the floor disco dance track with string stabs",
    "spoken word talking with no music",
    "traditional folk singing with acoustic guitar strumming",
]


def load_profile(name: str = "jazz") -> np.ndarray:
    vec = np.load(os.path.join(PROFILE_DIR, f"{name}.npy")).astype(np.float32)
    return vec / (np.linalg.norm(vec) + 1e-9)


def profile_meta(name: str = "jazz") -> dict:
    # Explicit utf-8: the reference list contains Japanese titles, and on
    # Windows the default cp1252 decode of this file raises.
    with open(os.path.join(PROFILE_DIR, f"{name}.json"), encoding="utf-8") as f:
        return json.load(f)


def _model():
    global _MODEL
    if _MODEL is None:
        import laion_clap
        print("[CLAP] loading music checkpoint (first run downloads ~2GB)...", flush=True)
        m = laion_clap.CLAP_Module(enable_fusion=False)
        m.load_ckpt()
        _MODEL = m
        print("[CLAP] ready.", flush=True)
    return _MODEL


def _ffmpeg_decode(src_path: str, sr: int = 48000):
    """Decode any container (m4a/AAC, mp3, ...) to mono float32 PCM via ffmpeg.

    librosa's audioread fallback turned out unreliable in CI for iTunes' .m4a
    previews (decode failures with an *empty* exception message — no usable
    diagnostic at all). ffmpeg is preinstalled on GitHub-hosted Ubuntu runners
    and decodes AAC/MP3 directly with no format-sniffing ambiguity, so this
    shells out to it instead of trusting librosa/audioread to pick a backend.
    """
    import subprocess

    fd, wav_path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        proc = subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-i", src_path,
             "-ac", "1", "-ar", str(sr), wav_path],
            capture_output=True, timeout=30,
        )
        if proc.returncode != 0 or not os.path.exists(wav_path):
            stderr = proc.stderr.decode("utf-8", "replace").strip()
            print(f"[CLAP] ffmpeg decode failed for {os.path.basename(src_path)}: {stderr}", flush=True)
            return None, None
        y, out_sr = sf.read(wav_path, dtype="float32", always_2d=False)
        return y, out_sr
    except Exception as e:
        print(f"[CLAP] ffmpeg decode raised for {os.path.basename(src_path)} "
              f"({type(e).__name__}): {e}", flush=True)
        return None, None
    finally:
        try:
            os.remove(wav_path)
        except OSError:
            pass


def embed_audio_file(path: str) -> np.ndarray | None:
    """512-dim L2-normalised embedding for an audio file of any format.

    Decoded via ffmpeg rather than handed straight to laion-clap: previews
    arrive as .m4a from iTunes and .mp3 from Deezer, and this normalises
    sample rate and channel count for both (see [_ffmpeg_decode]).
    """
    y, sr = _ffmpeg_decode(path, sr=48000)
    if y is None:
        return None
    if y.ndim > 1:               # ffmpeg -ac 1 should already guarantee mono
        y = y.mean(axis=1)
    if y.size < sr:               # under a second of audio — not worth scoring
        return None

    # CLAP's window is 10s. Take it from the middle of the preview: previews
    # often open on a fade-in or a count-in, and the centre is more
    # representative of the record.
    want = sr * 10
    if y.size > want:
        start = (y.size - want) // 2
        y = y[start:start + want]

    fd, tmp = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        sf.write(tmp, y, sr)
        m = _model()
        with _LOCK:
            emb = m.get_audio_embedding_from_filelist(x=[tmp], use_tensor=False)
        v = np.asarray(emb[0], dtype=np.float32)
        return v / (np.linalg.norm(v) + 1e-9)
    except Exception as e:
        print(f"[CLAP] embed failed ({type(e).__name__}): {e}", flush=True)
        return None
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


def embed_texts(prompts: list[str]) -> np.ndarray:
    m = _model()
    with _LOCK:
        emb = m.get_text_embedding(prompts, use_tensor=False)
    v = np.asarray(emb, dtype=np.float32)
    return v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-9)


_text_cache: dict[str, np.ndarray] = {}


def _mean_text(key: str, prompts: list[str]) -> np.ndarray:
    if key not in _text_cache:
        e = embed_texts(prompts).mean(axis=0)
        _text_cache[key] = e / (np.linalg.norm(e) + 1e-9)
    return _text_cache[key]


def text_affinity(styles: list[str], title: str, artist: str) -> float:
    """Fallback score in [0,1] for records with no obtainable preview.

    A margin between the jazz prompts and a set of deliberately-wrong ones,
    rather than a raw cosine: raw CLAP text-to-text cosines sit in a narrow band
    and are not comparable to the audio-side scores this feeds into.
    """
    desc = f"{artist} {title} {' '.join(styles)}".strip()
    try:
        d = embed_texts([desc])[0]
    except Exception as e:
        # Silently returning 0.0 here once hid a real problem (a missing
        # torchvision dependency broke every embedding call, and every
        # release quietly landed on the same flat fallback score) for a full
        # curation run before it surfaced. Never swallow this quietly again.
        print(f"[CLAP] text_affinity failed ({type(e).__name__}): {e}", flush=True)
        return 0.0
    pos = float(_mean_text("jazz", JAZZ_PROMPTS) @ d)
    neg = float(_mean_text("not", _NOT_JAZZ_PROMPTS) @ d)
    return max(0.0, min(1.0, 0.5 + (pos - neg) * 2.0))


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b))


def vibe_pct(sim: float) -> int:
    """Map a CLAP cosine onto a readable 0-100, the way digmore displays it.

    Real audio cosines against a profile mean live in roughly 0.55-0.95, so a
    raw percentage would read as "62%" for an excellent match. This stretches
    that band across the full scale.
    """
    return int(round(max(0.0, min(1.0, (sim - 0.50) / 0.42)) * 100))
