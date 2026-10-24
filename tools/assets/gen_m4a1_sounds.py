"""Synthesised M4A1 fire sounds (VIEWMODEL_SPEC sections 10 and 40) -> sounds/gun/m4a1_fire_1..3.ogg and
m4a1_fire_far.ogg (the sounds.json events gun.m4a1.fire / gun.m4a1.fire_far already name these files). Same DSP
toolkit as gen_sounds.py. 5.56x45 from a 14.5 inch carbine barrel: a very sharp, bright supersonic crack and a hard,
short muzzle blast (the short barrel burns powder past the muzzle: more high-mid bark, less low boom than the
AK), the light carrier and buffer of the direct-impingement action: the buffer's springy "sproing" and a quick
clack as the carrier returns inside the 75 ms cycle. The magazine, bolt and inspect sounds are the shared events."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, reverb, ring, t_axis


def m4_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.90)
    crack = band(rng.standard_normal(len(t)), 2600 * pitch, 16000) * env_exp(t, 0.0010, 0.00006)
    crack[:4] += np.array([1.0, -0.75, 0.42, -0.16])
    bark = band(rng.standard_normal(len(t)), 420 * pitch, 5200) * env_exp(t, 0.016, 0.0003)
    body = band(rng.standard_normal(len(t)), 130 * pitch, 2600) * env_exp(t, 0.034, 0.0005) * 0.75
    f = 96 * pitch * np.exp(-t / 0.045) + 40 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.060, 0.0012) * 1.05
    # the buffer spring "sproing" in the receiver extension and the carrier's return
    buffer = ring(t, [880 * pitch, 1760, 2730], [0.040, 0.024, 0.012], [0.10, 0.05, 0.025], 0.020, rng)
    carrier = click(t, 0.020, rng, 1600, 7000, 0.003, 0.10)
    carrier += ring(t, [1550, 2600, 4100, 6100], [0.014, 0.010, 0.006, 0.004], [0.15, 0.10, 0.06, 0.03], 0.055, rng)
    carrier += click(t, 0.055, rng, 1800, 8000, 0.0025, 0.13)
    dry = 1.0 * crack + 0.95 * bark + body + boom + buffer + carrier
    slap = np.zeros_like(dry)
    d = int(0.078 * SR)
    slap[d:] = band(dry[:-d], 180, 2600) * 0.20
    wet = reverb(dry + slap, rt=0.70, mix=0.48, hi=3400, seed=seed + 100, predelay=0.013)
    return finish(wet, -0.8, drive=2.7, fade=0.16)


def m4_shot_far(seed):
    """Distant: the crack's echo off terrain and the boom, highs gone, a long tail."""
    x = m4_shot(seed, 0.95)
    x = band(x, 55, 950)
    x = np.concatenate([np.zeros(int(0.025 * SR)), x, np.zeros(int(1.1 * SR))])
    x = reverb(x, rt=1.6, mix=0.95, hi=1100, seed=seed + 7, predelay=0.04)
    return finish(x, -8.5, drive=1.4, fade=0.5)


FILES = {"m4a1_fire_1": lambda: m4_shot(561, 1.00), "m4a1_fire_2": lambda: m4_shot(562, 0.97),
         "m4a1_fire_3": lambda: m4_shot(563, 1.03), "m4a1_fire_far": lambda: m4_shot_far(564)}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_m4a1_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    print(f"m4a1 sounds: {len(FILES)} files (events gun.m4a1.fire, gun.m4a1.fire_far already in sounds.json)")
