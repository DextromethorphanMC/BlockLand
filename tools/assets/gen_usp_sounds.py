"""Synthesised USP sounds (VIEWMODEL_SPEC section 26) -> sounds/gun/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_sounds.py. The .45 ACP is subsonic and heavy: less crack than the 9 mm, a deeper and longer
thump with a punchy low body. The Cerberus skin's report (gun.usp.fire_cerberus) is a bigger, metallic roar: a low
growl under the thump and a ringing steel overtone, with a longer tail."""
import tempfile

import numpy as np

from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def usp_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.75)
    crack = band(rng.standard_normal(len(t)), 1500 * pitch, 11000) * env_exp(t, 0.0012, 0.0001) * 0.7
    crack[:3] += np.array([0.7, -0.45, 0.2])
    body = band(rng.standard_normal(len(t)), 110 * pitch, 3200) * env_exp(t, 0.028, 0.0006)
    body2 = band(rng.standard_normal(len(t)), 220, 1300) * env_exp(t, 0.075, 0.002, 0.004) * 0.40
    f = 88 * pitch * np.exp(-t / 0.06) + 34 * pitch
    thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.075, 0.0015) * 1.35
    mech = ring(t, [1900, 3100, 4800], [0.009, 0.006, 0.004], [0.11, 0.07, 0.05], 0.046, rng)
    mech += ring(t, [1500, 2600, 4200], [0.013, 0.009, 0.005], [0.12, 0.08, 0.05], 0.095, rng)
    dry = 0.8 * crack + 0.9 * body + body2 + thump + mech
    slap = np.zeros_like(dry)
    d = int(0.078 * SR)
    slap[d:] = band(dry[:-d], 160, 2200) * 0.20
    wet = reverb(dry + slap, rt=0.62, mix=0.46, hi=2900, seed=seed + 100, predelay=0.013)
    return finish(wet, -1.0, drive=2.4, fade=0.14)


def cerberus_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.10)
    x = usp_shot(seed, 0.88 * pitch)
    x = np.concatenate([x, np.zeros(len(t) - len(x))]) if len(x) < len(t) else x[:len(t)]
    f = 52 * pitch * np.exp(-t / 0.12) + 26 * pitch
    growl = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.20, 0.003) * 0.9
    growl *= 1 + 0.35 * np.sin(2 * np.pi * 31 * t)                       # a snarl in the low end
    steel = ring(t, [740, 1480, 2960, 4420], [0.32, 0.20, 0.12, 0.07], [0.22, 0.14, 0.09, 0.05], 0.004, rng)
    roar = band(rng.standard_normal(len(t)), 80, 900) * env_exp(t, 0.18, 0.004, 0.01) * 0.45
    wet = reverb(0.85 * x + growl + steel + roar, rt=1.0, mix=0.5, hi=2600, seed=seed + 200, predelay=0.02)
    return finish(wet, -0.7, drive=2.8, fade=0.3)


FILES = {
    "usp_fire_1": lambda: usp_shot(51, 1.00), "usp_fire_2": lambda: usp_shot(52, 0.97),
    "usp_fire_3": lambda: usp_shot(53, 1.03),
    "usp_cerberus_1": lambda: cerberus_shot(56, 1.00), "usp_cerberus_2": lambda: cerberus_shot(57, 0.96),
    "usp_cerberus_3": lambda: cerberus_shot(58, 1.04),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.usp.fire": (["usp_fire_1", "usp_fire_2", "usp_fire_3"], "subtitles.blockland.gun.usp.fire"),
    "gun.usp.fire_cerberus": (["usp_cerberus_1", "usp_cerberus_2", "usp_cerberus_3"],
                              "subtitles.blockland.gun.usp.fire_cerberus"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    """merge=False only writes the .ogg files (sounds.json is then edited by hand)."""
    tmp = tempfile.mkdtemp(prefix="blockland_usp_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"usp sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
