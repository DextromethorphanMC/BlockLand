"""Synthesised PP-19 Bizon sounds (VIEWMODEL_SPEC sections 10 and 54) -> sounds/gun/bizon_fire_1..3.ogg,
bizon_fire_far.ogg and the entries merged into sounds.json (locked: other weapons' generators may run at the same time).
Same DSP toolkit as gen_sounds.py. The Bizon fires 9x18 from a 230 mm barrel: a sharp, dry crack-bark a little lower
and rounder than the MP5's (the round is subsonic: no supersonic snap), and the heavy blowback bolt's deep clunk on the
buffer and slam home inside the 86 ms interval, ringing in the big AK receiver. The magazine and charging handle events
reuse gun.mag_* and gun.bolt_*."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def bizon_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.75)
    crack = band(rng.standard_normal(len(t)), 1900 * pitch, 11000) * env_exp(t, 0.0010, 0.00005)
    crack[:3] += np.array([0.65, -0.48, 0.18])
    bark = band(rng.standard_normal(len(t)), 220 * pitch, 4600) * env_exp(t, 0.014, 0.0003)
    body = band(rng.standard_normal(len(t)), 100 * pitch, 2300) * env_exp(t, 0.034, 0.0006) * 0.9
    f = 88 * pitch * np.exp(-t / 0.055) + 40 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.065, 0.0010) * 0.95
    # blowback: the bolt slams the buffer, then home; the receiver rings low, the recoil spring sings
    mech = click(t, 0.004, rng, 2200, 8000, 0.0016, 0.14)
    mech += click(t, 0.024, rng, 900, 6000, 0.0040, 0.30)
    mech += click(t, 0.061, rng, 800, 5500, 0.0045, 0.36)
    mech += ring(t, [1180, 2050, 3600], [0.014, 0.010, 0.006], [0.14, 0.09, 0.05], 0.061, rng)
    dry = 0.85 * crack + 1.0 * bark + body + boom + mech
    slap = np.zeros_like(dry)
    d = int(0.062 * SR)
    slap[d:] = band(dry[:-d], 170, 2400) * 0.22
    wet = reverb(dry + slap, rt=0.58, mix=0.42, hi=3200, seed=seed + 100, predelay=0.010)
    return finish(wet, -0.8, drive=2.5, fade=0.14)


def bizon_shot_far(seed):
    x = bizon_shot(seed, 0.93)
    x = band(x, 55, 850)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=1050, seed=seed + 7, predelay=0.035)
    return finish(x, -9.0, drive=1.4, fade=0.5)


FILES = {
    "bizon_fire_1": lambda: bizon_shot(911, 1.00), "bizon_fire_2": lambda: bizon_shot(912, 0.97),
    "bizon_fire_3": lambda: bizon_shot(913, 1.03), "bizon_fire_far": lambda: bizon_shot_far(914),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.bizon.fire": (["bizon_fire_1", "bizon_fire_2", "bizon_fire_3"], "subtitles.blockland.gun.bizon.fire"),
    "gun.bizon.fire_far": (["bizon_fire_far"], "subtitles.blockland.gun.bizon.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_bizon_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"bizon sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
