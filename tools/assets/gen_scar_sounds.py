"""Synthesised SCAR-H sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/scar_fire_1..3.ogg, scar_fire_far.ogg; the
sounds.json entries (gun.scar.fire, gun.scar.fire_far) already exist and are merged unchanged in form (locked helper).
Same DSP toolkit as gen_sounds.py. The 7.62x51 NATO from a 16 in barrel: a harder, brighter supersonic crack than
the AK's, a deeper and longer muzzle boom, the flash hider's ring, and the SCAR's short-stroke piston action - the
carrier hitting the rear of the receiver and slamming home inside 0.1 s (600 rpm), with a little aluminium ring of
the upper. Magazine and bolt events reuse the shared gun.mag_* / gun.bolt_* sounds."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def scar_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.10)
    crack = band(rng.standard_normal(len(t)), 2600 * pitch, 16000) * env_exp(t, 0.0011, 0.00006)
    crack[:5] += np.array([1.0, -0.8, 0.5, -0.25, 0.08])
    body = band(rng.standard_normal(len(t)), 90 * pitch, 3400) * env_exp(t, 0.034, 0.0005)
    body2 = band(rng.standard_normal(len(t)), 200, 1200) * env_exp(t, 0.11, 0.002, 0.005) * 0.55
    f = 70 * pitch * np.exp(-t / 0.075) + 29 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.105, 0.0015) * 1.6
    hider = ring(t, [2900 * pitch, 4400 * pitch], [0.012, 0.008], [0.10, 0.06], 0.001, rng)      # prongs ringing
    carrier = ring(t, [1350, 2250, 3700], [0.010, 0.008, 0.005], [0.13, 0.09, 0.05], 0.026, rng)
    carrier += click(t, 0.026, rng, 1100, 6000, 0.004, 0.10)
    carrier += ring(t, [1150, 1950, 3400, 5000], [0.016, 0.011, 0.007, 0.004], [0.17, 0.11, 0.07, 0.035], 0.074,
                    rng)
    carrier += click(t, 0.074, rng, 1400, 7000, 0.003, 0.13)
    carrier += ring(t, [760, 1580], [0.040, 0.026], [0.05, 0.03], 0.003, rng)                       # the upper
    dry = 1.0 * crack + 0.85 * body + body2 + boom + hider + carrier
    slap = np.zeros_like(dry)
    d = int(0.092 * SR)
    slap[d:] = band(dry[:-d], 150, 2200) * 0.26
    wet = reverb(dry + slap, rt=0.85, mix=0.52, hi=3000, seed=seed + 100, predelay=0.015)
    return finish(wet, -0.6, drive=2.7, fade=0.2)


def scar_shot_far(seed):
    x = scar_shot(seed, 0.93)
    x = band(x, 45, 750)
    x = np.concatenate([np.zeros(int(0.028 * SR)), x, np.zeros(int(1.3 * SR))])
    x = reverb(x, rt=1.9, mix=0.95, hi=950, seed=seed + 7, predelay=0.045)
    return finish(x, -7.5, drive=1.4, fade=0.5)


FILES = {
    "scar_fire_1": lambda: scar_shot(171, 1.00), "scar_fire_2": lambda: scar_shot(172, 0.97),
    "scar_fire_3": lambda: scar_shot(173, 1.03), "scar_fire_far": lambda: scar_shot_far(174),
}
EVENTS = {
    "gun.scar.fire": (["scar_fire_1", "scar_fire_2", "scar_fire_3"], "subtitles.blockland.gun.scar.fire"),
    "gun.scar.fire_far": (["scar_fire_far"], "subtitles.blockland.gun.scar.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_scar_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"scar sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
