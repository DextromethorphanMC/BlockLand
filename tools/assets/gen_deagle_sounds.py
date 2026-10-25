"""Synthesised Desert Eagle sounds (VIEWMODEL_SPEC section 48) -> sounds/gun/deagle_fire_*.ogg + gun.deagle.fire
merged into sounds.json. Same DSP toolkit as gen_sounds.py / gen_usp_sounds.py. The .50 is supersonic and enormous for
a pistol: a sharp, loud crack, then a deep booming blast (a big muzzle-gas ball) with a long low body, the heavy slide
and bolt cycling, and a long rolling tail."""
import tempfile

import numpy as np

from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.25)
    crack = band(rng.standard_normal(len(t)), 1800 * pitch, 13000) * env_exp(t, 0.0016, 0.0001) * 1.0
    crack[:4] += np.array([0.9, -0.6, 0.3, -0.1])
    blast = band(rng.standard_normal(len(t)), 70 * pitch, 2600) * env_exp(t, 0.040, 0.0006)
    body = band(rng.standard_normal(len(t)), 150, 1100) * env_exp(t, 0.11, 0.002, 0.004) * 0.55
    f = 72 * pitch * np.exp(-t / 0.075) + 28 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.12, 0.0015) * 1.8
    # the heavy slide: back, the case flung out, home with a clack
    mech = ring(t, [1500, 2500, 3900], [0.012, 0.008, 0.005], [0.12, 0.08, 0.05], 0.060, rng)
    mech += ring(t, [1200, 2100, 3500], [0.018, 0.012, 0.007], [0.16, 0.10, 0.06], 0.120, rng)
    dry = crack + 1.1 * blast + body + boom + mech
    slap = np.zeros_like(dry)
    d = int(0.090 * SR)
    slap[d:] = band(dry[:-d], 120, 2000) * 0.28
    wet = reverb(dry + slap, rt=1.05, mix=0.55, hi=2600, seed=seed + 100, predelay=0.016)
    return finish(wet, -0.6, drive=2.9, fade=0.25)


FILES = {"deagle_fire_1": lambda: shot(91, 1.00), "deagle_fire_2": lambda: shot(92, 0.97),
         "deagle_fire_3": lambda: shot(93, 1.03)}
EVENTS = {"gun.deagle.fire": (list(FILES), "subtitles.blockland.gun.deagle.fire")}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_deagle_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), entries())
    print(f"deagle sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
