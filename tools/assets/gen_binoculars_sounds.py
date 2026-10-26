"""Synthesised binoculars sounds (VIEWMODEL_SPEC §52) -> sounds/binoculars/*.ogg + entries merged into sounds.json
(locked). Same DSP toolkit as gen_sounds.py / gen_parachute_sounds.py: raising them (rubber armour and the strap, the
eyecups meeting the brow), lowering them, and one detent of the zoom ring (the client plays it on every power step)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def raise_(seed=511):
    """Brought up to the eyes: a sleeve and strap rustle, the rubber body turned in the hands, the eyecups' soft tap."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.55)
    x = rustle(t, rng, 0.0, 0.30, 180, 3000, 0.8)
    x += band(rng.standard_normal(len(t)), 300, 1600) * env_exp(t, 0.05, 0.01, 0.12) * 0.25      # rubber squeak
    x += click(t, 0.30, rng, 400, 2200, 0.012, 0.55) + ring(t, [310, 520], [0.02, 0.012], [0.12, 0.05], 0.30, rng)
    return finish(reverb(x, 0.22, 0.12, seed=seed), -7.0)


def lower(seed=512):
    """Taken down from the eyes: a quicker rustle, the strap settling."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = rustle(t, rng, 0.0, 0.22, 160, 2600, 0.8)
    x += click(t, 0.24, rng, 250, 1400, 0.02, 0.35)
    return finish(reverb(x, 0.2, 0.1, seed=seed), -9.0)


def zoom(seed=513):
    """One detent of the zoom ring: a crisp plastic tick over a short grease-damped turn."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.16)
    x = band(rng.standard_normal(len(t)), 600, 3000) * env_exp(t, 0.03, 0.004, 0.0) * 0.18
    x += click(t, 0.035, rng, 2500, 9000, 0.0025, 1.0) + ring(t, [3600, 5200], [0.008, 0.005], [0.15, 0.08], 0.035, rng)
    return finish(x, -8.0, fade=0.01)


FILES = {"raise": raise_, "lower": lower, "zoom": zoom}
EVENTS = {
    "binoculars.raise": ["raise"],
    "binoculars.lower": ["lower"],
    "binoculars.zoom": ["zoom"],
}
#: subtitles (lang keys subtitles.blockland.<event>): Chinese, English
SUBTITLES = {
    "binoculars.raise": ("举起望远镜", "Binoculars raised"),
    "binoculars.lower": ("放下望远镜", "Binoculars lowered"),
    "binoculars.zoom": ("望远镜变倍", "Binoculars zoom"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_binoculars_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "binoculars", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {
        ev: {"sounds": [f"blockland:binoculars/{f}" for f in files], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, files in EVENTS.items()})
    print(f"binoculars sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
