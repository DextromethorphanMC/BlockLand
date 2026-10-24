"""Synthesised knuckles sounds (VIEWMODEL_SPEC sections 13 and 42) -> sounds/knife/knuckles_*.ogg + entries merged into
sounds.json. Same DSP toolkit as gen_knife_sounds.py; every transient sits at the very start so it lines up with its
event. Brass is a dull, short-ringing alloy: its clinks sit lower and die faster than a steel blade's."""
import tempfile

import numpy as np

from gen_knife_sounds import whoosh
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def brass_tick(t, t0, rng, amp=1.0, pitch=1.0):
    """Two brass parts knocking: a dull click and a short, low, inharmonic ring."""
    x = click(t, t0, rng, 1200, 7000, 0.003, amp)
    x += ring(t, np.array([1650, 2900, 4300]) * pitch * rng.uniform(0.97, 1.03), [0.030, 0.018, 0.010],
              np.array([0.50, 0.30, 0.16]) * amp, t0, rng)
    return x


def equip(seed=90):
    """The fingers slipping into the rings (skin and cloth), the fist closing on the palm bar, a small brass tick."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = rustle(t, rng, 0.0, 0.20, 250, 3000, 0.55)
    x += rustle(t, rng, 0.12, 0.12, 200, 2200, 0.45)
    x += band(rng.standard_normal(len(t)), 120, 900) * env_exp(t, 0.03, 0.002, 0.24) * 0.5    # the fist closing
    x += brass_tick(t, 0.26, rng, 0.40)
    return finish(reverb(x, 0.2, 0.15, seed=seed), -5.0, fade=0.06)


def clink(seed):
    """The two dusters knocked together."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = brass_tick(t, 0.002, rng, 1.0, rng.uniform(0.95, 1.05)) + brass_tick(t, 0.007, rng, 0.55, 1.12)
    x += band(rng.standard_normal(len(t)), 150, 700) * env_exp(t, 0.020, 0.001) * 0.35       # the hands behind them
    return finish(reverb(x, 0.25, 0.18, seed=seed), -3.0)


def swing(seed):
    """A punch: a short, low air swish and the sleeve's snap."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.22)
    x = whoosh(seed, 0.22, rng.uniform(0.035, 0.05), rng.uniform(250, 380), rng.uniform(1500, 2100), 1.0, 0.0)
    x += rustle(t, rng, 0.0, 0.08, 600, 4000, 0.30) * np.max(np.abs(x))
    return finish(x, -5.0, fade=0.04)


def swing_heavy(seed):
    """The uppercut: a longer, heavier swish with the body's turn in it."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = whoosh(seed, 0.40, rng.uniform(0.09, 0.12), 180, 1400, 1.0, 0.0)
    x += rustle(t, rng, 0.0, 0.25, 150, 1800, 0.35) * np.max(np.abs(x))
    return finish(x, -3.0, drive=1.15, fade=0.07)


def hit_flesh(seed):
    """Brass into a body: a heavy, meaty thud with a hard edge (no cut, no squelch of a blade), the bone's knock."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.30)
    f = 95 * rng.uniform(0.9, 1.1) * np.exp(-t / 0.04) + 48
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.060, 0.0015) * 1.0
    x += band(rng.standard_normal(len(t)), 200, 1600) * env_exp(t, 0.030, 0.001) * 0.6
    x += band(rng.standard_normal(len(t)), 1800, 5200) * env_exp(t, 0.008, 0.0004) * 0.45      # the knuckle's crack
    x += brass_tick(t, 0.001, rng, 0.12, 0.9)
    return finish(reverb(x, 0.15, 0.10, seed=seed), -1.5, drive=1.6)


def hit_wall(seed):
    """Brass on stone: a hard knock, a short brass ring and a little grit."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.36)
    x = click(t, 0.001, rng, 1500, 9000, 0.004, 1.0)
    x += brass_tick(t, 0.001, rng, 0.8, rng.uniform(0.85, 0.95))
    grit = band(rng.standard_normal(len(t)), 900, 5000) * env_exp(t, 0.030, 0.001)
    x += 0.30 * grit * (0.5 + (rng.random(len(t)) < 0.03))
    x += np.sin(2 * np.pi * 140 * t) * env_exp(t, 0.030, 0.001) * 0.6
    return finish(reverb(x, 0.3, 0.2, seed=seed), -1.5, drive=1.3)


FILES = {
    "knuckles_equip": equip, "knuckles_clink_1": lambda: clink(91), "knuckles_clink_2": lambda: clink(92),
    "knuckles_swing_1": lambda: swing(93), "knuckles_swing_2": lambda: swing(94), "knuckles_swing_3": lambda: swing(95),
    "knuckles_swing_heavy_1": lambda: swing_heavy(96), "knuckles_swing_heavy_2": lambda: swing_heavy(97),
    "knuckles_hit_flesh_1": lambda: hit_flesh(98), "knuckles_hit_flesh_2": lambda: hit_flesh(99),
    "knuckles_hit_flesh_3": lambda: hit_flesh(100),
    "knuckles_hit_wall_1": lambda: hit_wall(101), "knuckles_hit_wall_2": lambda: hit_wall(102),
}
EVENTS = {"knife.knuckles_equip": ["knuckles_equip"], "knife.knuckles_clink": ["knuckles_clink_1", "knuckles_clink_2"],
          "knife.knuckles_swing": ["knuckles_swing_1", "knuckles_swing_2", "knuckles_swing_3"],
          "knife.knuckles_swing_heavy": ["knuckles_swing_heavy_1", "knuckles_swing_heavy_2"],
          "knife.knuckles_hit_flesh": ["knuckles_hit_flesh_1", "knuckles_hit_flesh_2", "knuckles_hit_flesh_3"],
          "knife.knuckles_hit_wall": ["knuckles_hit_wall_1", "knuckles_hit_wall_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_knuckles_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "knife", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:knife/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"knuckles sounds: {len(FILES)} files, {len(EVENTS)} events")
