"""Synthesised body armour sounds (VIEWMODEL_SPEC section 50) -> sounds/armor/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_knife_sounds.py; every transient sits at the very start so it lines up with its event."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def velcro(t, t0, dur, rng, amp=1.0):
    """Hook-and-loop tearing: a dense crackle of tiny snaps."""
    e = ((t >= t0) & (t < t0 + dur)).astype(float) * np.sin(np.pi * np.clip((t - t0) / dur, 0, 1)) ** 0.5
    grains = (rng.random(len(t)) < 0.06) * rng.standard_normal(len(t))
    return amp * band(grains, 1500, 9000) * e


def vest_equip(seed=200):
    """Pulled on: nylon rustling, the cummerbund's hook-and-loop pressed shut, a buckle snapping."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    x = rustle(t, rng, 0.0, 0.45, 250, 3200, 0.7)
    x += velcro(t, 0.42, 0.22, rng, 0.45)
    x += click(t, 0.70, rng, 1800, 7000, 0.003, 0.7)
    return finish(reverb(x, 0.2, 0.15, seed=seed), -5.0, fade=0.08)


def plate_draw(seed=201):
    """Out of the pouch: a short hook-and-loop rip, the plate's coat brushing the nylon."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = velcro(t, 0.0, 0.16, rng, 0.8)
    x += rustle(t, rng, 0.12, 0.25, 200, 2200, 0.4)
    return finish(reverb(x, 0.18, 0.12, seed=seed), -6.0, fade=0.06)


def plate_insert(seed):
    """Into the bag: the plate sliding down the nylon, seating with a dull thud, the flap pressed shut."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.7)
    slide = band(rng.standard_normal(len(t)), 300, 2600) * np.clip(t / 0.05, 0, 1) * np.exp(-np.maximum(t - 0.18, 0) / 0.05)
    x = 0.5 * slide
    x += np.sin(2 * np.pi * 95 * t) * env_exp(t, 0.06, 0.003, 0.20) * 0.9
    x += band(rng.standard_normal(len(t)), 120, 900) * env_exp(t, 0.03, 0.002, 0.20) * 0.6
    x += velcro(t, 0.36, 0.14, rng, 0.35)
    return finish(reverb(x, 0.2, 0.12, seed=seed), -3.0, fade=0.06)


def plate_hit(seed):
    """A round stopped by the plate: a hard, dry ceramic crack with a flat thump of the body behind it."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.35)
    x = click(t, 0.001, rng, 1800, 12000, 0.004, 1.1)
    x += ring(t, np.array([1450, 2600, 4100]) * rng.uniform(0.95, 1.05), [0.025, 0.016, 0.010], [0.4, 0.3, 0.2], 0.001, rng)
    x += np.sin(2 * np.pi * 110 * t) * env_exp(t, 0.03, 0.001) * 0.7
    return finish(reverb(x, 0.2, 0.12, seed=seed), -2.0, drive=1.4)


def plate_break(seed):
    """A plate giving way: a loud crack, then the ceramic crumbling and grit trickling inside the bag."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    x = click(t, 0.001, rng, 1200, 12000, 0.006, 1.4)
    x += ring(t, np.array([900, 1700, 3100]) * rng.uniform(0.95, 1.05), [0.04, 0.03, 0.02], [0.5, 0.35, 0.25], 0.001, rng)
    crumble = (rng.random(len(t)) < 0.02) * rng.standard_normal(len(t))
    x += band(crumble, 1500, 8000) * env_exp(t, 0.25, 0.01, 0.02) * 1.2
    x += np.sin(2 * np.pi * 80 * t) * env_exp(t, 0.05, 0.002) * 0.8
    return finish(reverb(x, 0.3, 0.2, seed=seed), -1.0, drive=1.5, fade=0.1)


FILES = {
    "vest_equip": vest_equip, "plate_draw": plate_draw,
    "plate_insert_1": lambda: plate_insert(202), "plate_insert_2": lambda: plate_insert(203),
    "plate_hit_1": lambda: plate_hit(204), "plate_hit_2": lambda: plate_hit(205), "plate_hit_3": lambda: plate_hit(206),
    "plate_break_1": lambda: plate_break(207), "plate_break_2": lambda: plate_break(208),
}
EVENTS = {"armor.vest_equip": ["vest_equip"], "armor.plate_draw": ["plate_draw"],
          "armor.plate_insert": ["plate_insert_1", "plate_insert_2"],
          "armor.plate_hit": ["plate_hit_1", "plate_hit_2", "plate_hit_3"],
          "armor.plate_break": ["plate_break_1", "plate_break_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_armor_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "armor", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:armor/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"armour sounds: {len(FILES)} files, {len(EVENTS)} events")
