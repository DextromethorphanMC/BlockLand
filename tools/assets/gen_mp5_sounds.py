"""Synthesised MP5 sounds (VIEWMODEL_SPEC sections 10 and 40) -> sounds/gun/mp5_fire_1..3.ogg, mp5_fire_far.ogg and the
entries merged into sounds.json (locked: other weapons' generators may run at the same time; the events already exist,
the names stay). Same DSP toolkit as gen_sounds.py. The MP5 fires 9x19 from a 225 mm barrel: a sharp, tight bark (the
bullet just supersonic: a small crack), less low boom than a rifle, and the roller-delayed bolt's bright metallic
clack and spring ring inside the 75 ms interval -- the MP5's sewing-machine chatter. The magazine and cocking handle
events reuse gun.mag_* and gun.bolt_*."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def mp5_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.75)
    crack = band(rng.standard_normal(len(t)), 2400 * pitch, 14000) * env_exp(t, 0.0009, 0.00005)
    crack[:3] += np.array([0.75, -0.55, 0.22])
    bark = band(rng.standard_normal(len(t)), 260 * pitch, 5200) * env_exp(t, 0.012, 0.0003)
    body = band(rng.standard_normal(len(t)), 120 * pitch, 2600) * env_exp(t, 0.030, 0.0006) * 0.85
    f = 95 * pitch * np.exp(-t / 0.05) + 42 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.060, 0.0010) * 0.95
    # roller-delayed bolt: unlocking tick, the carrier hitting the buffer, slamming home; the recoil spring rings
    mech = click(t, 0.003, rng, 2500, 9000, 0.0015, 0.16)
    mech += click(t, 0.021, rng, 1600, 8000, 0.0030, 0.22)
    mech += click(t, 0.051, rng, 1200, 7000, 0.0035, 0.30)
    mech += ring(t, [1650, 2700, 4300], [0.010, 0.008, 0.005], [0.12, 0.08, 0.05], 0.051, rng)
    dry = 0.9 * crack + 1.0 * bark + body + boom + mech
    slap = np.zeros_like(dry)
    d = int(0.060 * SR)
    slap[d:] = band(dry[:-d], 180, 2600) * 0.22
    wet = reverb(dry + slap, rt=0.55, mix=0.42, hi=3400, seed=seed + 100, predelay=0.010)
    return finish(wet, -0.8, drive=2.6, fade=0.14)


def mp5_shot_far(seed):
    x = mp5_shot(seed, 0.94)
    x = band(x, 60, 900)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=1100, seed=seed + 7, predelay=0.035)
    return finish(x, -9.0, drive=1.4, fade=0.5)


FILES = {
    "mp5_fire_1": lambda: mp5_shot(511, 1.00), "mp5_fire_2": lambda: mp5_shot(512, 0.97),
    "mp5_fire_3": lambda: mp5_shot(513, 1.03), "mp5_fire_far": lambda: mp5_shot_far(514),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.mp5.fire": (["mp5_fire_1", "mp5_fire_2", "mp5_fire_3"], "subtitles.blockland.gun.mp5.fire"),
    "gun.mp5.fire_far": (["mp5_fire_far"], "subtitles.blockland.gun.mp5.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_mp5_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"mp5 sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
