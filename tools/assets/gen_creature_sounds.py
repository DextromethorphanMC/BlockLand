"""Synthesised creature sounds (tools/creatures/SHARK_SPEC.md section 5) -> sounds/creature/<name>.ogg and the sound
events `blockland:shark.<key>` merged into sounds.json (locked). Same DSP toolkit as gen_sounds.py.

A shark has no voice: its sounds are the water and its body. `bite` — the jaws snapping shut (a hard crunch of teeth on
teeth over a dull body thud, a burst of churned water); `hurt` — a startled thrash (the tail slapping the water twice,
a gurgle); `death` — a long weakening thrash fading into bubbles; `swim` — the tail sweeping through the surface (a
watery swish with a soft splash); `flop` — a heavy wet body slapping the ground out of the water."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, t_axis


def _water(rng, t, lo, hi, tau, t0=0.0, amp=1.0, attack=0.004):
    """A burst of moving water: band-limited noise with a slow random swell (churn)."""
    n = band(rng.standard_normal(len(t)), lo, hi)
    swell = band(rng.standard_normal(len(t)), 2, 18)
    churn = 0.6 + 0.4 * swell / max(1e-9, float(np.std(swell)))
    return amp * n * np.clip(churn, 0.2, 1.6) * env_exp(t, tau, attack, t0)


def _bubbles(rng, t, t0, count, dur, lo=300, hi=1400, amp=0.4):
    """Small bubbles rising: short chirps whose pitch climbs as each one pops."""
    out = np.zeros_like(t)
    for _ in range(count):
        at = t0 + rng.uniform(0, dur)
        f0 = rng.uniform(lo, hi)
        tt = t - at
        on = tt >= 0
        f = f0 * (1 + 6 * np.maximum(tt, 0))
        out += on * np.sin(2 * np.pi * np.cumsum(f * on) / SR) * np.exp(-np.maximum(tt, 0) / rng.uniform(0.01, 0.03))
    return amp * out / max(1, np.sqrt(count))


def bite(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    thud = band(rng.standard_normal(len(t)), 50, 500) * env_exp(t, 0.06, 0.0008, 0.0)
    crunch = sum(click(t, 0.012 + k * 0.009, rng, 1500, 7000, 0.006, 0.9 - 0.15 * k) for k in range(4))
    snap = band(rng.standard_normal(len(t)), 700, 3000) * env_exp(t, 0.02, 0.0003, 0.008) * 0.9
    x = 1.1 * thud + crunch + snap + _water(rng, t, 200, 2500, 0.18, 0.02, 0.7) + _bubbles(rng, t, 0.08, 10, 0.4)
    return finish(reverb(x, rt=0.5, mix=0.22, hi=3500, seed=seed), -1.5, drive=1.6)


def hurt(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(1.0)
    x = np.zeros_like(t)
    for k, at in enumerate((0.0, 0.17)):
        x += _water(rng, t, 150, 3500, 0.10, at, 1.0 - 0.3 * k, 0.002)
        x += band(rng.standard_normal(len(t)), 60, 400) * env_exp(t, 0.05, 0.001, at) * 0.8
    x += _bubbles(rng, t, 0.1, 14, 0.6, 250, 900, 0.5)
    return finish(reverb(x, rt=0.6, mix=0.25, hi=3000, seed=seed), -2.0, drive=1.4)


def death(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(2.4)
    x = np.zeros_like(t)
    for k, at in enumerate((0.0, 0.22, 0.48, 0.80, 1.18)):
        x += _water(rng, t, 120, 3000, 0.14, at, 1.0 * 0.72 ** k, 0.003)
        x += band(rng.standard_normal(len(t)), 50, 350) * env_exp(t, 0.06, 0.001, at) * 0.7 * 0.7 ** k
    x += _bubbles(rng, t, 0.3, 40, 1.9, 200, 1000, 0.6) * np.clip(1.4 - t / 2.4, 0, 1)
    return finish(reverb(x, rt=0.9, mix=0.3, hi=2800, seed=seed), -2.0, drive=1.3)


def swim(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    # the tail sweeping: a swish rising and falling, then a soft splash where it breaks the surface
    sweep = band(rng.standard_normal(len(t)), 300, 2200) * np.sin(np.pi * np.clip(t / 0.45, 0, 1)) ** 2
    splash = _water(rng, t, 800, 6000, 0.12, 0.30, 0.6, 0.002)
    x = 0.8 * sweep + splash + _bubbles(rng, t, 0.3, 8, 0.4, 500, 1600, 0.25)
    return finish(reverb(x, rt=0.5, mix=0.2, hi=4000, seed=seed), -4.0, drive=1.2)


def flop(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.6)
    slap = band(rng.standard_normal(len(t)), 120, 2500) * env_exp(t, 0.035, 0.0005)
    body = band(rng.standard_normal(len(t)), 45, 300) * env_exp(t, 0.08, 0.001) * 1.2
    wet = _water(rng, t, 1500, 7000, 0.05, 0.005, 0.5, 0.001)
    return finish(reverb(slap + body + wet, rt=0.35, mix=0.18, seed=seed), -2.0, drive=1.5)


def files():
    return {
        "shark_bite": bite(2101),
        "shark_hurt": hurt(2102),
        "shark_death": death(2103),
        "shark_swim": swim(2104),
        "shark_flop": flop(2105),
    }


EVENTS = ("bite", "hurt", "death", "swim", "flop")


def entries():
    return {f"shark.{key}": {"sounds": [f"blockland:creature/shark_{key}"], "subtitle": f"subtitles.blockland.shark.{key}"}
            for key in EVENTS}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_creature_snd_")
    made = files()
    for name, x in made.items():
        encode(x, path("sounds", "creature", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"creature sounds: {len(made)} files, {len(entries())} events")


if __name__ == "__main__":
    import os
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from build_all import path as _path
    build(_path)
