"""Synthesised expandable-baton sounds (VIEWMODEL_SPEC sections 13 and 45) -> sounds/knife/baton_*.ogg + entries merged
into sounds.json. Same DSP toolkit as gen_crowbar_sounds.py; every transient sits at the very start so it lines up with
its event. The shafts are thin steel tubes: their ring is higher and dies faster than the crowbar's bar; the lock is
the baton's voice - the shafts hissing out of the handle and seating on their tapers with a hard double crack."""
import tempfile

import numpy as np

from gen_knife_sounds import whoosh
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis

TUBE_MODES = np.array([1.0, 2.756, 5.404, 8.933])
TUBE_F1 = 610.0


def tube_ring(t, t0, rng, amp=1.0, damp=1.0):
    f = TUBE_F1 * TUBE_MODES * rng.uniform(0.97, 1.03)
    taus = np.array([0.18, 0.12, 0.07, 0.045]) / damp
    return ring(t, f, taus, np.array([0.30, 0.50, 0.40, 0.22]) * amp, t0, rng)


def hiss(t, t0, dur, rng, amp=1.0):
    """Steel sliding in steel: a short rising friction hiss."""
    e = np.clip((t - t0) / dur, 0, 1) * (t >= t0) * (t < t0 + dur)
    return amp * band(rng.standard_normal(len(t)), 2500, 9000) * e ** 1.5


def equip(seed=120):
    """Drawn from the belt carrier: leather and cloth, the hand closing on the rubber, a faint tick of the shafts."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = rustle(t, rng, 0.0, 0.22, 250, 3000, 0.6)
    x += band(rng.standard_normal(len(t)), 150, 900) * env_exp(t, 0.025, 0.002, 0.20) * 0.5
    x += click(t, 0.23, rng, 2500, 9000, 0.002, 0.25)
    return finish(reverb(x, 0.2, 0.15, seed=seed), -6.0, fade=0.06)


def open_lock(seed):
    """The flick: the shafts hiss out and seat - two cracks a hair apart - and the tubes ring briefly."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = hiss(t, 0.0, 0.045, rng, 0.35)
    for t0, a in ((0.045, 1.0), (0.057, 0.75)):
        x += click(t, t0, rng, 1800, 14000, 0.003, a)
        x += tube_ring(t, t0, rng, 0.55 * a, damp=1.2)
    return finish(reverb(x, 0.25, 0.18, seed=seed), -1.0, drive=1.4)


def close(seed=124):
    """Collapsed on the floor: the tip's knock on the ground, the shafts driven home with a dull double clack."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = np.sin(2 * np.pi * 150 * t) * env_exp(t, 0.03, 0.001) * 0.7
    x += click(t, 0.001, rng, 800, 6000, 0.004, 0.8)
    x += hiss(t, 0.004, 0.025, rng, 0.25)
    for t0, a in ((0.030, 0.8), (0.040, 0.6)):
        x += click(t, t0, rng, 1200, 8000, 0.003, a)
        x += tube_ring(t, t0, rng, 0.25 * a, damp=2.5)
    return finish(reverb(x, 0.3, 0.2, seed=seed), -2.0, drive=1.2)


def swing(seed):
    """A thin rod through the air: a quick, whistling swish."""
    rng = np.random.default_rng(seed)
    x = whoosh(seed, 0.26, rng.uniform(0.045, 0.06), rng.uniform(380, 520), rng.uniform(2600, 3400), 1.0, 0.18)
    return finish(x, -4.0, fade=0.05)


def swing_heavy(seed):
    """The overhead strike: a longer, deeper whistle and the arm's sleeve."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = whoosh(seed, 0.40, rng.uniform(0.09, 0.12), 260, 2200, 1.0, 0.14)
    x += rustle(t, rng, 0.0, 0.20, 200, 2000, 0.3) * np.max(np.abs(x))
    return finish(x, -2.5, drive=1.15, fade=0.08)


def hit_flesh(seed):
    """Steel rod on a body: a sharp, slapping thwack over a short thump."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.32)
    f = 140 * rng.uniform(0.9, 1.1) * np.exp(-t / 0.04) + 60
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.040, 0.002) * 0.8
    slap = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.010, 0.0004)
    x += slap * 0.9 * (np.max(np.abs(x)) / (np.max(np.abs(slap)) + 1e-9))
    x += tube_ring(t, 0.002, rng, 0.12, damp=4.0)
    return finish(reverb(x, 0.15, 0.10, seed=seed), -1.5, drive=1.5)


def hit_wall(seed):
    """Steel rod on stone: a bright clank, the tubes ringing a moment, grit."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.50)
    x = click(t, 0.001, rng, 2000, 15000, 0.003, 1.1)
    x += tube_ring(t, 0.001, rng, 1.0, damp=rng.uniform(1.0, 1.4))
    grit = band(rng.standard_normal(len(t)), 1200, 7000) * env_exp(t, 0.035, 0.001)
    x += 0.25 * grit * (0.5 + (rng.random(len(t)) < 0.03))
    return finish(reverb(x, 0.35, 0.22, seed=seed), -1.0, drive=1.3)


FILES = {
    "baton_equip": equip,
    "baton_open_1": lambda: open_lock(121), "baton_open_2": lambda: open_lock(122), "baton_open_3": lambda: open_lock(123),
    "baton_close": close,
    "baton_swing_1": lambda: swing(125), "baton_swing_2": lambda: swing(126), "baton_swing_3": lambda: swing(127),
    "baton_swing_heavy_1": lambda: swing_heavy(128), "baton_swing_heavy_2": lambda: swing_heavy(129),
    "baton_hit_flesh_1": lambda: hit_flesh(130), "baton_hit_flesh_2": lambda: hit_flesh(131),
    "baton_hit_flesh_3": lambda: hit_flesh(132),
    "baton_hit_wall_1": lambda: hit_wall(133), "baton_hit_wall_2": lambda: hit_wall(134),
    "baton_hit_wall_3": lambda: hit_wall(135),
}
EVENTS = {"knife.baton_equip": ["baton_equip"],
          "knife.baton_open": ["baton_open_1", "baton_open_2", "baton_open_3"],
          "knife.baton_close": ["baton_close"],
          "knife.baton_swing": ["baton_swing_1", "baton_swing_2", "baton_swing_3"],
          "knife.baton_swing_heavy": ["baton_swing_heavy_1", "baton_swing_heavy_2"],
          "knife.baton_hit_flesh": ["baton_hit_flesh_1", "baton_hit_flesh_2", "baton_hit_flesh_3"],
          "knife.baton_hit_wall": ["baton_hit_wall_1", "baton_hit_wall_2", "baton_hit_wall_3"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_baton_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "knife", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:knife/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"baton sounds: {len(FILES)} files, {len(EVENTS)} events")
