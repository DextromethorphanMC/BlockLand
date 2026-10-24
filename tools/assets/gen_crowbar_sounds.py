"""Synthesised crowbar sounds (VIEWMODEL_SPEC sections 13 and 41) -> sounds/knife/crowbar_*.ogg + entries merged into
sounds.json. Same DSP toolkit as gen_knife_sounds.py; every transient sits at the very start so it lines up with its
event. The bar rings with the inharmonic partials of a free steel bar (1 : 2.76 : 5.40 : 8.93 over ≈ 240 Hz for 0.6 m
of 19 mm hex), damped by the hand that holds it."""
import tempfile

import numpy as np

from gen_knife_sounds import whoosh
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis

BAR_MODES = np.array([1.0, 2.756, 5.404, 8.933, 13.34])
BAR_F1 = 242.0


def bar_ring(t, t0, rng, amp=1.0, damp=1.0, bright=1.0):
    """The bar's own ring: its bending modes, the high ones dying first; `damp` > 1 = held tighter."""
    f = BAR_F1 * BAR_MODES * rng.uniform(0.985, 1.015)
    taus = np.array([0.55, 0.38, 0.22, 0.13, 0.08]) / damp
    amps = np.array([0.35, 0.55, 0.45, 0.30, 0.18]) * np.array([1, 1, bright, bright, bright ** 2]) * amp
    return ring(t, f, taus, amps, t0, rng)


def equip(seed=100):
    """Lifted off a surface: steel scraping and knocking once (a short damped ring), the tape gripped."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.60)
    scrape = band(rng.standard_normal(len(t)), 1500, 7000) * env_exp(t, 0.06, 0.02, 0.0) * 0.35
    x = scrape + click(t, 0.09, rng, 800, 6000, 0.004, 0.8)
    x += bar_ring(t, 0.09, rng, 0.55, damp=2.2)
    x += rustle(t, rng, 0.18, 0.18, 200, 2400, 0.35)                     # the hand closing on the tape
    return finish(reverb(x, 0.3, 0.2, seed=seed), -4.0, fade=0.08)


def swing(seed):
    """A heavy bar through the air: a deep, rounder swish than a blade's, the arm's sleeve with it."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.42)
    x = whoosh(seed, 0.42, rng.uniform(0.11, 0.14), rng.uniform(170, 230), rng.uniform(1300, 1700), 1.0, 0.03)
    x += rustle(t, rng, 0.0, 0.18, 300, 2500, 0.30) * np.max(np.abs(x))
    return finish(x, -3.5, fade=0.07)


def swing_heavy(seed):
    """The overhead smash: a long, low rush as the bar comes over, the body's effort in the cloth."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.60)
    x = whoosh(seed, 0.60, rng.uniform(0.08, 0.10), 120, 1100, 1.0, 0.02)
    x += rustle(t, rng, 0.0, 0.30, 150, 1800, 0.40) * np.max(np.abs(x))
    return finish(x, -2.0, drive=1.25, fade=0.10)


def hit_flesh(seed):
    """Steel into a body: a deep blunt thump, the crack of bone under it, the bar's ring choked off."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    f = 95 * rng.uniform(0.9, 1.1) * np.exp(-t / 0.06) + 45
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.060, 0.002) * 1.0
    x += band(rng.standard_normal(len(t)), 150, 1200) * env_exp(t, 0.035, 0.002) * 0.7      # the body's mass
    crack = band(rng.standard_normal(len(t)), 1800, 7000) * env_exp(t, 0.008, 0.0004, 0.004)
    x += crack * 0.55 * (np.max(np.abs(x)) / (np.max(np.abs(crack)) + 1e-9))
    x += bar_ring(t, 0.002, rng, 0.25, damp=4.0, bright=0.6)
    return finish(reverb(x, 0.18, 0.12, seed=seed), -1.5, drive=1.6)


def hit_wall(seed):
    """Steel on stone: a hard clang, the bar ringing on (held, so it dies within half a second), grit and chips."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.80)
    x = click(t, 0.001, rng, 1500, 14000, 0.004, 1.2)
    x += bar_ring(t, 0.001, rng, 1.0, damp=rng.uniform(1.1, 1.5))
    grit = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.05, 0.001)
    grit *= 0.5 + (rng.random(len(t)) < 0.03)
    x += 0.30 * grit
    x += np.sin(2 * np.pi * 140 * t) * env_exp(t, 0.03, 0.001) * 0.5                       # the wall's thud
    return finish(reverb(x, 0.4, 0.25, seed=seed), -1.0, drive=1.3)


def tap(seed):
    """The shaft slapped into the open palm: a dull smack, a faint muffled ring."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.35)
    x = band(rng.standard_normal(len(t)), 200, 2600) * env_exp(t, 0.012, 0.0006) * 1.0
    x += np.sin(2 * np.pi * 180 * t) * env_exp(t, 0.025, 0.001) * 0.6
    x += bar_ring(t, 0.001, rng, 0.18, damp=3.0, bright=0.7)
    return finish(reverb(x, 0.2, 0.12, seed=seed), -4.0)


FILES = {
    "crowbar_equip": equip,
    "crowbar_swing_1": lambda: swing(101), "crowbar_swing_2": lambda: swing(102), "crowbar_swing_3": lambda: swing(103),
    "crowbar_swing_heavy_1": lambda: swing_heavy(104), "crowbar_swing_heavy_2": lambda: swing_heavy(105),
    "crowbar_hit_flesh_1": lambda: hit_flesh(106), "crowbar_hit_flesh_2": lambda: hit_flesh(107),
    "crowbar_hit_flesh_3": lambda: hit_flesh(108),
    "crowbar_hit_wall_1": lambda: hit_wall(109), "crowbar_hit_wall_2": lambda: hit_wall(110),
    "crowbar_hit_wall_3": lambda: hit_wall(111),
    "crowbar_tap_1": lambda: tap(112), "crowbar_tap_2": lambda: tap(113),
}
EVENTS = {"knife.crowbar_equip": ["crowbar_equip"],
          "knife.crowbar_swing": ["crowbar_swing_1", "crowbar_swing_2", "crowbar_swing_3"],
          "knife.crowbar_swing_heavy": ["crowbar_swing_heavy_1", "crowbar_swing_heavy_2"],
          "knife.crowbar_hit_flesh": ["crowbar_hit_flesh_1", "crowbar_hit_flesh_2", "crowbar_hit_flesh_3"],
          "knife.crowbar_hit_wall": ["crowbar_hit_wall_1", "crowbar_hit_wall_2", "crowbar_hit_wall_3"],
          "knife.crowbar_tap": ["crowbar_tap_1", "crowbar_tap_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_crowbar_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "knife", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:knife/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"crowbar sounds: {len(FILES)} files, {len(EVENTS)} events")
