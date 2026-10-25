"""Synthesised Vector sounds (VIEWMODEL_SPEC sections 10 and 44) -> sounds/gun/vector_fire_1..3.ogg, vector_fire_far.ogg
and the entries merged into sounds.json (locked: other weapons' generators may run at the same time). Same DSP toolkit
as gen_sounds.py. The Vector fires subsonic .45 ACP from a 140 mm barrel: no ballistic crack at all, a deep, round
thump with more low boom than a 9 mm SMG and a short, fat bark; the bolt's block running down its inclined track gives a
duller, lower clack than a straight blowback, and at 1150 rpm (52 ms) the shots run together into a buzz. The magazine
and charging handle events reuse gun.mag_* and gun.bolt_*."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def vector_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    # muzzle blast only (subsonic): a fat bark, a long body, a deep boom
    pop = band(rng.standard_normal(len(t)), 900 * pitch, 9000) * env_exp(t, 0.0018, 0.0001)
    pop[:3] += np.array([0.55, -0.40, 0.15])
    bark = band(rng.standard_normal(len(t)), 180 * pitch, 3600) * env_exp(t, 0.014, 0.0003)
    body = band(rng.standard_normal(len(t)), 80 * pitch, 1800) * env_exp(t, 0.034, 0.0007) * 0.95
    f = 72 * pitch * np.exp(-t / 0.05) + 36 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.070, 0.0010) * 1.1
    # the bolt: unlocking tick, the block hitting the buffer down its track, slamming home; a dull spring ring
    mech = click(t, 0.002, rng, 2000, 7000, 0.0015, 0.12)
    mech += click(t, 0.015, rng, 900, 5000, 0.0040, 0.24)
    mech += click(t, 0.036, rng, 800, 5500, 0.0040, 0.28)
    mech += ring(t, [1150, 1900, 3100], [0.010, 0.008, 0.005], [0.12, 0.08, 0.05], 0.036, rng)
    dry = 0.7 * pop + 1.0 * bark + body + boom + mech
    slap = np.zeros_like(dry)
    d = int(0.055 * SR)
    slap[d:] = band(dry[:-d], 150, 2200) * 0.24
    wet = reverb(dry + slap, rt=0.55, mix=0.40, hi=3000, seed=seed + 100, predelay=0.010)
    return finish(wet, -0.8, drive=2.4, fade=0.14)


def vector_shot_far(seed):
    x = vector_shot(seed, 0.94)
    x = band(x, 50, 800)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=1000, seed=seed + 7, predelay=0.035)
    return finish(x, -10.0, drive=1.4, fade=0.5)


FILES = {
    "vector_fire_1": lambda: vector_shot(611, 1.00), "vector_fire_2": lambda: vector_shot(612, 0.97),
    "vector_fire_3": lambda: vector_shot(613, 1.03), "vector_fire_far": lambda: vector_shot_far(614),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.vector.fire": (["vector_fire_1", "vector_fire_2", "vector_fire_3"], "subtitles.blockland.gun.vector.fire"),
    "gun.vector.fire_far": (["vector_fire_far"], "subtitles.blockland.gun.vector.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_vector_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"vector sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
