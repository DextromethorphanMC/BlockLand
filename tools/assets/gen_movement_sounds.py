"""Synthesised movement-action sounds (VIEWMODEL_SPEC section 47) -> sounds/movement/{prone,prone_up,dive,dive_land}_*.ogg
and their `movement.*` events merged into sounds.json. Same DSP toolkit as gen_slide_sounds.py.

prone: the knees, then the elbows and chest meeting the ground (two soft thumps), cloth and the kit rattling once.
prone_up: hands pushing off, cloth, the kit settling as the body comes up. dive: the body thrown through the air (a low
rush), cloth flapping. dive_land: the chest slamming down (a heavy thump with the kit's clank), then a scrape that
slows as the body slides to a stop."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def _thump(t, at, rng, lo=50, hi=600, tau=0.04, amp=1.0):
    return band(rng.standard_normal(len(t)), lo, hi) * env_exp(t, tau, 0.003, at) * amp


def _kit(t, at, rng, amp=0.4):
    """Webbing, buckles and magazines knocking: a few small metallic ticks."""
    x = np.zeros(len(t))
    for k in range(int(rng.integers(2, 4))):
        t0 = at + k * rng.uniform(0.012, 0.03)
        x += click(t, t0, rng, 2500, 9000, 0.003, amp * rng.uniform(0.5, 1.0))
        x += ring(t, np.array([2300, 3700]) * rng.uniform(0.9, 1.1), [0.03, 0.02], [0.3 * amp, 0.15 * amp], t0, rng)
    return x


def prone(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.7)
    x = rustle(t, rng, 0.0, 0.45, 250 * pitch, 3200 * pitch, 0.5)
    x += _thump(t, 0.07, rng, 60 * pitch, 700 * pitch, 0.035, 0.7)        # knees
    x += _thump(t, 0.21, rng, 45 * pitch, 520 * pitch, 0.05, 1.0)         # elbows and chest
    x += _kit(t, 0.22, rng, 0.35)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -4.0, drive=1.2, fade=0.1)


def prone_up(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.65)
    x = rustle(t, rng, 0.0, 0.5, 300 * pitch, 3500 * pitch, 0.55)
    x += _thump(t, 0.03, rng, 80 * pitch, 900 * pitch, 0.02, 0.4)         # hands pushing off
    grit = band(rng.standard_normal(len(t)), 1500, 7000) * env_exp(t, 0.06, 0.01, 0.05) * 0.2
    x += grit + _kit(t, 0.32, rng, 0.25)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -6.0, fade=0.1)


def dive(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    env = np.clip(t / 0.08, 0, 1) * np.exp(-np.maximum(t - 0.1, 0) / 0.18)
    rush = band(rng.standard_normal(len(t)), 120 * pitch, 1400 * pitch) * env
    flap = rustle(t, rng, 0.02, 0.35, 400 * pitch, 4000 * pitch, 0.6)
    x = rush * 0.9 + flap + _kit(t, 0.05, rng, 0.2)
    return finish(reverb(x, 0.3, 0.15, seed=seed), -4.0, fade=0.1)


def dive_land(seed, pitch=1.0, length=0.9):
    rng = np.random.default_rng(seed)
    t = t_axis(length)
    x = _thump(t, 0.004, rng, 40 * pitch, 450 * pitch, 0.07, 1.3)
    x += _kit(t, 0.01, rng, 0.6)
    slow = np.clip(t / length, 0, 1)
    scrape = band(rng.standard_normal(len(t)), 700 * pitch, 5000 * pitch)
    dark = band(scrape, None, 1500 * pitch)
    swell = np.clip((t - 0.03) / 0.05, 0, 1) * np.exp(-np.maximum(t - 0.08, 0) / 0.22)
    x += (scrape * (1 - slow) + dark * slow) * swell * 0.55
    x += rustle(t, rng, 0.0, length * 0.6, 250, 2800, 0.35)
    return finish(reverb(x, 0.32, 0.18, seed=seed), -2.5, drive=1.3, fade=0.12)


FILES = {"prone_1": lambda: prone(171), "prone_2": lambda: prone(172, 0.94),
         "prone_up_1": lambda: prone_up(173), "prone_up_2": lambda: prone_up(174, 1.05),
         "dive_1": lambda: dive(175), "dive_2": lambda: dive(176, 0.95),
         "dive_land_1": lambda: dive_land(177), "dive_land_2": lambda: dive_land(178, 0.95, 0.8)}
EVENTS = {"movement.prone": ["prone_1", "prone_2"], "movement.prone_up": ["prone_up_1", "prone_up_2"],
          "movement.dive": ["dive_1", "dive_2"], "movement.dive_land": ["dive_land_1", "dive_land_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_movement_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "movement", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:movement/{f}" for f in files],
                                                 "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()})
    print(f"movement action sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
