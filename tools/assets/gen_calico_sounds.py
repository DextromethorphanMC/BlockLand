"""Synthesised Calico M950 fire sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/calico_fire_1..3.ogg and
calico_fire_far.ogg (the sounds.json entries gun.calico.fire / fire_far already exist; merged unchanged, locked).
Same DSP toolkit as gen_sounds.py. The 9 mm from a 15 cm barrel: a sharper, louder crack than the P9's and a fuller
body (more powder burns in the barrel), a short pistol thump; the roller-delayed bolt's double clack (unlock, then
the slam home) and, unique to the gun, the helical magazine's rotor answering every shot: a quick, bright spring zing
and the rounds shuffling a step round the helix."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def calico_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.72)
    crack = band(rng.standard_normal(len(t)), 2000 * pitch, 15000) * env_exp(t, 0.0015, 0.0001)
    crack[:4] += np.array([1.0, -0.7, 0.35, -0.1])
    body = band(rng.standard_normal(len(t)), 140 * pitch, 4600) * env_exp(t, 0.026, 0.0005)
    body2 = band(rng.standard_normal(len(t)), 280, 1800) * env_exp(t, 0.070, 0.002, 0.004) * 0.38
    f = 98 * pitch * np.exp(-t / 0.055) + 40 * pitch
    thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.062, 0.0014) * 1.15
    # the roller-delayed bolt: unlock clack, then the slam into battery a few hundredths later
    mech = click(t, 0.006, rng, 1500, 9000, 0.003, 0.20)
    mech += ring(t, [1850, 3050, 4700], [0.010, 0.007, 0.004], [0.11, 0.07, 0.05], 0.034, rng)
    mech += ring(t, [1450, 2550, 4050, 6100], [0.014, 0.010, 0.006, 0.004], [0.14, 0.09, 0.06, 0.03], 0.074, rng)
    # the helical magazine: the rotor spring's bright zing and the rounds clicking a step round the helix
    zing = ring(t, [3350 * pitch, 5150 * pitch, 7300], [0.030, 0.022, 0.014], [0.050, 0.034, 0.020], 0.052, rng)
    shuffle = sum(click(t, 0.056 + 0.0045 * k, rng, 3800, 12000, 0.0012, 0.05 * (1 - 0.18 * k)) for k in range(4))
    dry = 0.95 * crack + 0.85 * body + body2 + thump + mech + zing + shuffle
    slap = np.zeros_like(dry)
    d = int(0.070 * SR)
    slap[d:] = band(dry[:-d], 200, 2600) * 0.20
    wet = reverb(dry + slap, rt=0.58, mix=0.46, hi=3300, seed=seed + 100, predelay=0.012)
    return finish(wet, -0.9, drive=2.4, fade=0.13)


def calico_shot_far(seed):
    x = calico_shot(seed, 0.94)
    x = band(x, 60, 950)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(0.95 * SR))])
    x = reverb(x, rt=1.35, mix=0.9, hi=1150, seed=seed + 7, predelay=0.032)
    return finish(x, -8.5, drive=1.3, fade=0.42)


FILES = {
    "calico_fire_1": lambda: calico_shot(231, 1.00), "calico_fire_2": lambda: calico_shot(232, 0.97),
    "calico_fire_3": lambda: calico_shot(233, 1.03), "calico_fire_far": lambda: calico_shot_far(234),
}
EVENTS = {   # sound event -> (files, subtitle key): the entries already in sounds.json (names kept)
    "gun.calico.fire": (["calico_fire_1", "calico_fire_2", "calico_fire_3"], "subtitles.blockland.gun.calico.fire"),
    "gun.calico.fire_far": (["calico_fire_far"], "subtitles.blockland.gun.calico.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_calico_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"calico sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
