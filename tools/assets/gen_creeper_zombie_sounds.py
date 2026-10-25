"""Synthesised sounds of the creeper zombie (infection monster) -> sounds/infection/creeper_fuse_*.ogg + the event
`blockland:infection.creeper_fuse` merged into sounds.json. The blast itself is the frag grenade's
(`blockland:equipment.frag_explode`). Same DSP toolkit as gen_knife_sounds.py; the transient sits at the very start."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, t_axis

DUR = 1.45                     # covers the skill's 1.3 s fuse and the blast's onset


def fuse(seed):
    """A lit fuse: a spitting, crackling hiss that rises and quickens as it burns down, sparks popping in it, a low
    swelling rumble under it (the gunpowder catching) towards the end."""
    rng = np.random.default_rng(seed)
    t = t_axis(DUR)
    u = t / DUR
    hiss = rng.standard_normal(len(t))
    # the hiss brightens as it burns down: two bands crossfaded along the fuse
    lo, hi = band(hiss, 1500, 5000), band(hiss, 3500, 11000)
    x = (lo * (1 - u) + hi * u) * (0.35 + 0.65 * u ** 1.5)
    # crackle: sparse sharp pops, denser towards the end
    rate = 18 + 140 * u ** 2
    pops = (rng.random(len(t)) < rate / SR) * rng.standard_normal(len(t))
    x += band(pops, 1200, 9000) * 6.0 * np.max(np.abs(lo))
    # spitting flutter in the hiss
    flutter = band(rng.standard_normal(len(t)), None, 24)
    x *= 0.7 + 0.6 * np.abs(flutter) / (np.max(np.abs(flutter)) + 1e-9)
    # the swell under it (a creeper's breath before it blows)
    f = 55 + 70 * u ** 2
    x += np.sin(2 * np.pi * np.cumsum(f) / SR) * (u ** 3) * 0.6 * np.max(np.abs(x))
    x += click(t, 0.0, rng, 1500, 9000, 0.004, 0.6 * np.max(np.abs(x)))          # the light catching
    x *= np.clip(t / 0.02, 0, 1)
    return finish(x, -2.0, fade=0.04)


FILES = {"creeper_fuse_1": lambda: fuse(121), "creeper_fuse_2": lambda: fuse(122)}
EVENTS = {"infection.creeper_fuse": ["creeper_fuse_1", "creeper_fuse_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_creeper_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "infection", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:infection/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"creeper zombie sounds: {len(FILES)} files, {len(EVENTS)} events")
