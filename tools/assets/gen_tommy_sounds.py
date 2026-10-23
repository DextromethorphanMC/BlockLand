"""Synthesised Thompson sounds (VIEWMODEL_SPEC section 24) -> sounds/gun/*.ogg + entries merged into sounds.json
(locked: other weapons' generators may run at the same time). Same DSP toolkit as gen_sounds.py; the magazine and
cocking-knob events reuse gun.mag_* and gun.bolt_* (the clip events are the shared names)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def smg_shot(seed, pitch=1.0):
    """.45 ACP from a heavy open-bolt gun: a subsonic round (no sharp crack, a rounder, deeper thump than the pistol),
    the compensator's blast, and the heavy bolt slamming home inside the 0.083 s interval -- the chatter."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.80)
    pop = band(rng.standard_normal(len(t)), 900 * pitch, 7000) * env_exp(t, 0.0030, 0.00015)
    pop[:3] += np.array([0.8, -0.5, 0.2])
    body = band(rng.standard_normal(len(t)), 90 * pitch, 2400) * env_exp(t, 0.034, 0.0006)
    blast = band(rng.standard_normal(len(t)), 300, 1800) * env_exp(t, 0.060, 0.002, 0.003) * 0.5   # compensator
    f = 70 * pitch * np.exp(-t / 0.07) + 30 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.090, 0.0015) * 1.45
    bolt = ring(t, [980, 1700, 2900], [0.012, 0.009, 0.006], [0.20, 0.14, 0.08], 0.050, rng)
    bolt += click(t, 0.050, rng, 900, 5000, 0.005, 0.20)              # the heavy bolt closing (open-bolt gun)
    dry = 0.6 * pop + 0.95 * body + blast + boom + bolt
    slap = np.zeros_like(dry)
    d = int(0.070 * SR)
    slap[d:] = band(dry[:-d], 140, 1800) * 0.24
    wet = reverb(dry + slap, rt=0.70, mix=0.48, hi=2600, seed=seed + 100, predelay=0.012)
    return finish(wet, -0.9, drive=2.4, fade=0.16)


def smg_shot_far(seed):
    x = smg_shot(seed, 0.92)
    x = band(x, 45, 700)
    x = np.concatenate([np.zeros(int(0.025 * SR)), x, np.zeros(int(1.1 * SR))])
    x = reverb(x, rt=1.6, mix=0.95, hi=900, seed=seed + 7, predelay=0.04)
    return finish(x, -8.5, drive=1.4, fade=0.5)


FILES = {
    "tommy_fire_1": lambda: smg_shot(21, 1.00), "tommy_fire_2": lambda: smg_shot(22, 0.97),
    "tommy_fire_3": lambda: smg_shot(23, 1.03), "tommy_fire_far": lambda: smg_shot_far(24),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.tommy.fire": (["tommy_fire_1", "tommy_fire_2", "tommy_fire_3"], "subtitles.blockland.gun.tommy.fire"),
    "gun.tommy.fire_far": (["tommy_fire_far"], "subtitles.blockland.gun.tommy.fire_far"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_tommy_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"tommy sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
