"""Synthesised suppressed gunshot (VIEWMODEL_SPEC section 19: the suppressor's fire sound) -> sounds/gun/*.ogg and
the `gun.suppressed` entry merged into sounds.json. Same DSP toolkit as gen_sounds.py.

A can traps most of the muzzle blast: what is left is a short muffled thump, the gas puffing out of the baffles, a
faint ballistic crack and the gun's own action (slide / carrier) suddenly audible."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def suppressed_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.62)
    thump = band(rng.standard_normal(len(t)), 95 * pitch, 1500 * pitch) * env_exp(t, 0.020, 0.0007)
    f = 62 * pitch * np.exp(-t / 0.05) + 38 * pitch
    low = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.045, 0.0012) * 0.9
    puff = band(rng.standard_normal(len(t)), 420 * pitch, 3200) * env_exp(t, 0.055, 0.0025, 0.002) * 0.42
    crack = band(rng.standard_normal(len(t)), 2600, 11000) * env_exp(t, 0.0010, 0.00008) * 0.16
    # the action now stands out: slide / carrier back and forward
    action = click(t, 0.010, rng, 1800, 8000, 0.003, 0.30)
    action += ring(t, [1700, 2900, 4500], [0.012, 0.008, 0.005], [0.16, 0.11, 0.06], 0.010, rng)
    action += click(t, 0.052, rng, 1400, 7000, 0.003, 0.36)
    action += ring(t, [1500, 2600, 4100], [0.016, 0.011, 0.006], [0.20, 0.13, 0.07], 0.052, rng)
    dry = thump + low + puff + crack + action
    wet = reverb(dry, rt=0.42, mix=0.28, hi=2600, seed=seed + 100, predelay=0.010)
    return finish(wet, -5.0, drive=1.5, fade=0.14)


FILES = {"suppressed_1": lambda: suppressed_shot(31, 1.00), "suppressed_2": lambda: suppressed_shot(32, 0.96),
         "suppressed_3": lambda: suppressed_shot(33, 1.04)}
EVENTS = {"gun.suppressed": (["suppressed_1", "suppressed_2", "suppressed_3"], "subtitles.blockland.gun.suppressed")}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_sup_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"suppressor sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
