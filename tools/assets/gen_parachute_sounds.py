"""Synthesised parachute sounds -> sounds/parachute/*.ogg + entries merged into sounds.json (locked).
Same DSP toolkit as gen_sounds.py / gen_bomb_sounds.py: the canopy's opening crack sits at the very start (the client
plays it the moment the canopy is predicted open), and the wind loop cross-fades its end into its start."""
import tempfile

import numpy as np

from gen_equipment_sounds import _loop
from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def flap(t, rng, t0, dur, rate, lo=120, hi=1800, amp=1.0):
    """Fabric beating in the airflow: noise gated by a jittery fast flutter, fading out over `dur`."""
    tt = t - t0
    gate = (tt >= 0) * np.clip(1 - tt / max(dur, 1e-3), 0, 1)
    phase = 2 * np.pi * np.cumsum(rate * (1 + 0.25 * rng.standard_normal(len(t)) * 0.05)) / SR
    flutter = np.clip(np.sin(phase), 0, 1) ** 3
    return band(rng.standard_normal(len(t)), lo, hi) * flutter * gate * amp


def open_(seed=401):
    """The canopy opening: the pilot chute's whoosh, a rush of unfolding nylon, the deep snap as it fills, a flutter."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.6)
    x = band(rng.standard_normal(len(t)), 300, 4000) * env_exp(t, 0.22, 0.04, 0.0) * 0.55            # whoosh
    x += rustle(t, rng, 0.03, 0.35, 250, 5000, 0.9)                                                    # unfolding
    snap = band(rng.standard_normal(len(t)), 60, 900) * env_exp(t, 0.06, 0.002, 0.36) * 1.6           # the fill
    x += snap + ring(t, [55, 82], [0.18, 0.12], [0.7, 0.4], 0.36, rng)
    x += flap(t, rng, 0.40, 1.1, 18, 150, 2200, 0.35)
    return finish(reverb(x, 0.5, 0.2, seed=seed), -2.0)


def close(seed=402):
    """The canopy let go: the airflow dropping off, fabric collapsing and folding."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.0)
    x = band(rng.standard_normal(len(t)), 200, 2500) * env_exp(t, 0.18, 0.01, 0.0) * 0.5
    x += rustle(t, rng, 0.0, 0.55, 180, 3500, 0.8)
    x += flap(t, rng, 0.0, 0.6, 11, 120, 1500, 0.4)
    return finish(reverb(x, 0.4, 0.2, seed=seed), -5.0)


def wind(seed=403):
    """The wind past the jumper (loops): a broad roar that swells slowly, with a fabric flutter riding on it."""
    rng = np.random.default_rng(seed)
    t = t_axis(4.25)
    swell = 0.75 + 0.25 * np.sin(2 * np.pi * 0.35 * t + 0.6) * np.sin(2 * np.pi * 0.13 * t + 1.1)
    x = band(rng.standard_normal(len(t)), 80, 1200) * swell * 0.9
    x += band(rng.standard_normal(len(t)), 1500, 6000) * (0.25 + 0.1 * np.sin(2 * np.pi * 0.5 * t)) * 0.4
    x += flap(t, rng, 0.0, 99.0, 14, 200, 2500, 0.18)
    return finish(_loop(x, 0.25), -6.0, fade=0.0)


def equip(seed=404):
    """The rig put on: webbing pulled tight, two buckles clicking shut."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.7)
    x = rustle(t, rng, 0.0, 0.32, 200, 3200, 0.8)
    for at in (0.36, 0.5):
        x += click(t, at, rng, 1800, 7000, 0.006, 0.9) + ring(t, [2900, 4400], [0.02, 0.012], [0.12, 0.06], at, rng)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -6.0)


FILES = {"open": open_, "close": close, "wind": wind, "equip": equip}
EVENTS = {
    "parachute.open": ["open"],
    "parachute.close": ["close"],
    "parachute.wind": ["wind"],
    "parachute.equip": ["equip"],
}
#: subtitles (lang keys subtitles.blockland.<event>): Chinese, English
SUBTITLES = {
    "parachute.open": ("降落伞打开", "Parachute opens"), "parachute.close": ("降落伞收起", "Parachute folds"),
    "parachute.wind": ("呼啸的风", "Wind rushes"), "parachute.equip": ("背上降落伞", "Parachute strapped on"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_parachute_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "parachute", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {
        ev: {"sounds": [f"blockland:parachute/{f}" for f in files], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, files in EVENTS.items()})
    print(f"parachute sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
