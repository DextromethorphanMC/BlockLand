"""Synthesised P90 sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/p90_fire_1..3.ogg and p90_fire_far.ogg (the
gun.p90.fire / fire_far events already exist in sounds.json; the names are kept, nothing is merged). Same DSP toolkit
as gen_sounds.py. The 5.7x28 is a small, very fast (supersonic, ~715 m/s from the 263 mm barrel) bottlenecked
round: a sharp, high crack with a thin, short body and little low boom, the flash hider's hiss, and the light
closed bolt cycling inside the butt (900 rpm: everything mechanical is over inside 0.066 s) - a snappy, buzzy shot,
not the thump of a 9 mm or .45."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, reverb, ring, t_axis


def p90_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    crack = band(rng.standard_normal(len(t)), 2600 * pitch, 15000) * env_exp(t, 0.0010, 0.00006)   # sonic crack
    crack[:3] += np.array([1.0, -0.75, 0.30])
    body = band(rng.standard_normal(len(t)), 150 * pitch, 4200) * env_exp(t, 0.022, 0.0004)
    hiss = band(rng.standard_normal(len(t)), 2400, 9000) * env_exp(t, 0.016, 0.001, 0.002) * 0.35   # flash hider
    f = 105 * pitch * np.exp(-t / 0.045) + 48 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.055, 0.0012) * 0.95
    mech = ring(t, [1500, 2600, 4100], [0.010, 0.007, 0.004], [0.13, 0.09, 0.05], 0.018, rng)   # bolt back
    mech += click(t, 0.040, rng, 1600, 8000, 0.003, 0.14)                                       # bolt home
    mech += click(t, 0.0045, rng, 3000, 9000, 0.0015, 0.06)                                     # trigger break
    dry = 1.0 * crack + 0.85 * body + hiss + boom + mech
    slap = np.zeros_like(dry)
    d = int(0.060 * SR)
    slap[d:] = band(dry[:-d], 200, 2600) * 0.20
    wet = reverb(dry + slap, rt=0.62, mix=0.44, hi=3400, seed=seed + 100, predelay=0.010)
    return finish(wet, -0.9, drive=2.6, fade=0.14)


def p90_shot_far(seed):
    """Far away: the crack carries (high frequencies of a supersonic round), the body is gone, a long tail."""
    x = p90_shot(seed, 0.95)
    x = band(x, 120, 1600)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=1300, seed=seed + 7, predelay=0.038)
    return finish(x, -9.5, drive=1.3, fade=0.5)


FILES = {
    "p90_fire_1": lambda: p90_shot(901, 1.00), "p90_fire_2": lambda: p90_shot(902, 0.97),
    "p90_fire_3": lambda: p90_shot(903, 1.03), "p90_fire_far": lambda: p90_shot_far(904),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_p90_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    print(f"p90 sounds: {len(FILES)} files (events gun.p90.fire / gun.p90.fire_far already in sounds.json)")
