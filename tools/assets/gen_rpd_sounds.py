"""Synthesised RPD sounds (VIEWMODEL_SPEC section 33) -> sounds/gun/*.ogg + entries merged into sounds.json (locked).
Same DSP toolkit as gen_sounds.py. The RPD fires the AK's cartridge from a longer, heavier barrel: a fuller boom and a
bigger mechanical thud (the open bolt slamming forward to fire), less sharp crack. The belt change has its own sounds:
the feed cover flicked open, the belt dragged across the tray, the cover slammed shut."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def rpd_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.05)
    crack = band(rng.standard_normal(len(t)), 1800 * pitch, 13000) * env_exp(t, 0.0013, 0.00008)
    crack[:4] += np.array([0.9, -0.65, 0.35, -0.12])
    body = band(rng.standard_normal(len(t)), 95 * pitch, 3200) * env_exp(t, 0.036, 0.0006)
    body2 = band(rng.standard_normal(len(t)), 180, 1100) * env_exp(t, 0.11, 0.002, 0.006) * 0.55
    f = 74 * pitch * np.exp(-t / 0.07) + 30 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.10, 0.0016) * 1.5
    thud = click(t, 0.0, rng, 300, 2500, 0.010, 0.35)                  # the open bolt slamming home
    mech = ring(t, [1150, 1900, 3200], [0.014, 0.010, 0.006], [0.16, 0.11, 0.06], 0.030, rng)
    mech += click(t, 0.062, rng, 1100, 6000, 0.004, 0.12)
    dry = 0.85 * crack + 0.9 * body + body2 + boom + thud + mech
    slap = np.zeros_like(dry)
    d = int(0.09 * SR)
    slap[d:] = band(dry[:-d], 140, 2000) * 0.25
    wet = reverb(dry + slap, rt=0.85, mix=0.52, hi=2800, seed=seed + 100, predelay=0.014)
    return finish(wet, -0.7, drive=2.8, fade=0.2)


def rpd_shot_far(seed):
    x = rpd_shot(seed, 0.92)
    x = band(x, 45, 700)
    x = np.concatenate([np.zeros(int(0.028 * SR)), x, np.zeros(int(1.2 * SR))])
    x = reverb(x, rt=1.8, mix=0.95, hi=900, seed=seed + 7, predelay=0.045)
    return finish(x, -7.5, drive=1.4, fade=0.5)


def cover_open(seed=141):
    """Latch flicked and the cover springing up on its hinge: a sharp unlatch click, a hollow sheet-steel clang."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = click(t, 0.002, rng, 1500, 9000, 0.003, 0.9)
    x += ring(t, [820, 1460, 2650, 4100], [0.060, 0.040, 0.024, 0.012], [0.55, 0.40, 0.24, 0.12], 0.012, rng)
    x += click(t, 0.014, rng, 900, 7000, 0.004, 0.6)
    x += band(rng.standard_normal(len(t)), 700, 5000) * env_exp(t, 0.020, 0.002, 0.004) * 0.25
    return finish(reverb(x, 0.30, 0.28, seed=seed), -1.6, drive=1.5)


def cover_close(seed=142):
    """Cover slammed shut: a heavy steel clack as the latch catches, the belt rattling in the tray, a low thump."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.48)
    x = click(t, 0.002, rng, 800, 11000, 0.005, 1.4)
    x += ring(t, [700, 1300, 2350, 3800, 5600], [0.070, 0.045, 0.028, 0.016, 0.008], [1.0, 0.7, 0.42, 0.24, 0.1],
              0.002, rng)
    x += np.sin(2 * np.pi * 95 * t) * env_exp(t, 0.050, 0.001, 0.002) * 1.0
    for k in range(4):                                                   # the belt links settle
        x += click(t, 0.030 + 0.018 * k + 0.006 * rng.random(), rng, 2500, 9000, 0.002, 0.18 * (1 - 0.2 * k))
    return finish(reverb(x, 0.32, 0.30, seed=seed), -0.6, drive=1.8)


def belt(seed=143):
    """A belt of cartridges dragged and laid in the tray: metallic chinks of the links over a short scrape."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.34)
    x = band(rng.standard_normal(len(t)), 1200, 7000) * env_exp(t, 0.06, 0.005) * 0.25
    for k in range(7):
        at = 0.004 + 0.022 * k + 0.008 * rng.random()
        x += click(t, at, rng, 3000, 11000, 0.0015, 0.30 * (0.6 + 0.4 * rng.random()))
        x += ring(t, [3600 + 400 * rng.random(), 5200], [0.012, 0.008], [0.10, 0.05], at, rng)
    return finish(reverb(x, 0.22, 0.22, seed=seed), -3.0, drive=1.3)


FILES = {
    "rpd_fire_1": lambda: rpd_shot(151, 1.00), "rpd_fire_2": lambda: rpd_shot(152, 0.97),
    "rpd_fire_3": lambda: rpd_shot(153, 1.03), "rpd_fire_far": lambda: rpd_shot_far(154),
    "cover_open": cover_open, "cover_close": cover_close, "belt": belt,
}
EVENTS = {   # sound event -> (files, subtitle key); the cover and belt events are clip events (section 33)
    "gun.rpd.fire": (["rpd_fire_1", "rpd_fire_2", "rpd_fire_3"], "subtitles.blockland.gun.rpd.fire"),
    "gun.rpd.fire_far": (["rpd_fire_far"], "subtitles.blockland.gun.rpd.fire_far"),
    "gun.cover_open": (["cover_open"], "subtitles.blockland.gun.cover_open"),
    "gun.cover_close": (["cover_close"], "subtitles.blockland.gun.cover_close"),
    "gun.belt": (["belt"], "subtitles.blockland.gun.belt"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    tmp = tempfile.mkdtemp(prefix="blockland_rpd_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"rpd sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
