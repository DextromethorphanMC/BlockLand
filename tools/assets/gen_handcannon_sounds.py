"""Synthesised hand cannon sounds (VIEWMODEL_SPEC section 43) -> sounds/gun/handcannon_*.ogg + gun.handcannon.fire and
gun.handcannon.dud merged into sounds.json. Same DSP toolkit as gen_sounds.py. A 40 mm launcher is low pressure: no
supersonic crack, a deep hollow "thoomp" of the gas leaving the wide tube, the tube ringing a little, a soft tail; the
dud is the round striking short of its arming distance: a dull metal clunk and a bounce."""
import tempfile

import numpy as np

from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def launch(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.95)
    f = 95 * pitch * np.exp(-t / 0.05) + 38 * pitch
    thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.070, 0.0015) * 1.6
    gas = band(rng.standard_normal(len(t)), 120 * pitch, 1500) * env_exp(t, 0.035, 0.0010) * 0.9
    pop = band(rng.standard_normal(len(t)), 600, 4000) * env_exp(t, 0.006, 0.0003) * 0.6
    tube = ring(t, np.array([420, 1130, 2210]) * pitch, [0.08, 0.05, 0.03], [0.10, 0.06, 0.03], 0.002, rng)
    dry = thump + gas + pop + tube
    wet = reverb(dry, rt=0.8, mix=0.40, hi=2200, seed=seed + 100, predelay=0.014)
    return finish(wet, -1.0, drive=2.2, fade=0.2)


def dud(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    knock = band(rng.standard_normal(len(t)), 300, 3500) * env_exp(t, 0.012, 0.0004) * 0.9
    body = ring(t, [610, 1480, 2650], [0.05, 0.03, 0.02], [0.35, 0.22, 0.12], 0.001, rng)
    bounce = 0.4 * band(rng.standard_normal(len(t)), 400, 3000) * env_exp(t, 0.008, 0.0004, 0.16)
    bounce += 0.25 * ring(t, [640, 1520], [0.03, 0.02], [0.2, 0.1], 0.16, rng)
    return finish(reverb(knock + body + bounce, 0.4, 0.25, seed=seed), -3.0)


FILES = {"handcannon_fire_1": lambda: launch(141, 1.00), "handcannon_fire_2": lambda: launch(142, 0.96),
         "handcannon_fire_3": lambda: launch(143, 1.04), "handcannon_dud_1": lambda: dud(144),
         "handcannon_dud_2": lambda: dud(145)}
EVENTS = {"gun.handcannon.fire": (["handcannon_fire_1", "handcannon_fire_2", "handcannon_fire_3"],
                                  "subtitles.blockland.gun.handcannon.fire"),
          "gun.handcannon.dud": (["handcannon_dud_1", "handcannon_dud_2"], "subtitles.blockland.gun.handcannon.dud")}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_handcannon_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), entries())
    print(f"handcannon sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
