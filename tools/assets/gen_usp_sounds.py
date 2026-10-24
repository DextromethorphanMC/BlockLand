"""Synthesised USP sounds (VIEWMODEL_SPEC section 26) -> sounds/gun/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_sounds.py. The .45 ACP is subsonic and heavy: less crack than the 9 mm, a deeper and longer
thump with a punchy low body. The Cerberus skin's report (gun.usp.fire_cerberus) is a bigger, metallic roar: a low
growl under the thump and a ringing steel overtone, with a longer tail. The skin's own inspect and kill (section 38): a fire catching (release),
the fingertip's ignition crackle with a heartbeat thump (touch), a heavy rack wrapped in a whoosh, the catch's slap and
flare, the roar (a beast's growl and a blast of flame) and a short howl on a kill."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


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


def _fire(t, rng, t0, dur, lo=180, hi=2600, amp=1.0):
    """A burst of flame: rushing noise that swells and flutters (gas, not grain)."""
    e = np.clip((t - t0) / (dur * 0.25), 0, 1) * np.exp(-np.maximum(t - t0 - dur * 0.25, 0) / (dur * 0.45)) * (t >= t0)
    flutter = band(rng.standard_normal(len(t)), None, 18)
    flutter = 0.55 + 0.9 * np.abs(flutter) / (np.max(np.abs(flutter)) + 1e-9)
    return amp * band(rng.standard_normal(len(t)), lo, hi) * e * flutter


def _growl(t, t0, dur, f0, f1, amp=1.0, snarl=31.0):
    """A low voiced growl gliding from f0 to f1 Hz, rough (amplitude snarl) and rich in harmonics."""
    tt = np.maximum(t - t0, 0)
    f = f0 + (f1 - f0) * np.clip(tt / dur, 0, 1)
    ph = 2 * np.pi * np.cumsum(f) / SR
    voice = np.sin(ph) + 0.55 * np.sin(2 * ph + 0.4) + 0.35 * np.sin(3 * ph + 1.1) + 0.2 * np.sin(5 * ph)
    env = np.clip(tt / 0.06, 0, 1) * np.exp(-np.maximum(tt - dur * 0.6, 0) / (dur * 0.25)) * (t >= t0)
    return amp * np.tanh(1.6 * voice) * env * (1 + 0.45 * np.sin(2 * np.pi * snarl * tt))


def cerberus_release(seed=70):
    rng = np.random.default_rng(seed)
    t = t_axis(1.0)
    x = _fire(t, rng, 0.0, 0.7, 120, 1800, 0.8) + rustle(t, rng, 0.0, 0.25, 300, 3000, 0.35)
    x += np.sin(2 * np.pi * (55 + 25 * t) * t) * env_exp(t, 0.45, 0.08) * 0.5        # the hum of the fire taking hold
    return finish(reverb(x, 0.8, 0.4, hi=2500, seed=seed), -4.0, drive=1.6, fade=0.2)


def cerberus_touch(seed=71):
    rng = np.random.default_rng(seed)
    t = t_axis(1.3)
    crackle = np.zeros_like(t)
    for _ in range(26):                                   # the ignition: a fast crackle of sparks
        t0 = 0.004 + 0.32 * rng.random() ** 2
        crackle += click(t, t0, rng, 2500, 11000, 0.0025, 0.5 + rng.random())
    thump = np.sin(2 * np.pi * np.cumsum(70 * np.exp(-t / 0.08) + 38) / SR) * env_exp(t, 0.16, 0.004) * 1.4
    beat = np.sin(2 * np.pi * np.cumsum(60 * np.exp(-np.maximum(t - 0.32, 0) / 0.07) + 34) / SR) \
        * env_exp(t, 0.12, 0.004, 0.32) * 0.9
    chime = ring(t, [880, 1320, 1975, 2960], [0.5, 0.35, 0.22, 0.12], [0.35, 0.25, 0.15, 0.08], 0.0, rng)
    x = 0.7 * crackle + thump + beat + chime + _fire(t, rng, 0.05, 0.9, 150, 2200, 0.6)
    return finish(reverb(x, 1.1, 0.5, hi=3000, seed=seed, predelay=0.015), -2.0, drive=2.0, fade=0.3)


def cerberus_rack(seed=72):
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    back = click(t, 0.0, rng, 900, 6000, 0.03, 0.8) + ring(t, [1500, 2600], [0.04, 0.03], [0.3, 0.2], 0.0, rng)
    home = click(t, 0.075, rng, 600, 7000, 0.012, 1.4) \
        + ring(t, [1200, 2300, 3900], [0.09, 0.06, 0.04], [0.6, 0.4, 0.25], 0.075, rng)
    thud = np.sin(2 * np.pi * 95 * t) * env_exp(t, 0.06, 0.002, 0.075) * 0.9
    x = back + home + thud + _fire(t, rng, 0.0, 0.5, 200, 2600, 0.55)
    return finish(reverb(x, 0.6, 0.35, seed=seed), -2.5, drive=1.8, fade=0.15)


def cerberus_catch(seed=73):
    rng = np.random.default_rng(seed)
    t = t_axis(0.7)
    slap = click(t, 0.0, rng, 400, 4000, 0.02, 1.2) + np.sin(2 * np.pi * 120 * t) * env_exp(t, 0.05, 0.002) * 0.8
    x = slap + ring(t, [1700, 2900], [0.05, 0.03], [0.25, 0.15], 0.0, rng) + _fire(t, rng, 0.0, 0.4, 250, 3000, 0.5)
    return finish(reverb(x, 0.5, 0.3, seed=seed), -3.0, drive=1.5, fade=0.12)


def cerberus_roar(seed=74):
    rng = np.random.default_rng(seed)
    t = t_axis(2.0)
    growl = _growl(t, 0.0, 1.1, 92, 58, 1.0) + _growl(t, 0.02, 1.0, 138, 84, 0.45, 23.0)
    throat = band(rng.standard_normal(len(t)), 250, 1600) * env_exp(t, 0.5, 0.05) * 0.5
    blast = _fire(t, rng, 0.0, 1.1, 90, 3000, 1.2)
    boom = np.sin(2 * np.pi * np.cumsum(60 * np.exp(-t / 0.15) + 28) / SR) * env_exp(t, 0.3, 0.004) * 1.5
    x = band(growl, 50, 2400) + throat + blast + boom
    return finish(reverb(x, 1.6, 0.55, hi=2400, seed=seed, predelay=0.025), -0.8, drive=2.6, fade=0.5)


def cerberus_kill(seed=75):
    rng = np.random.default_rng(seed)
    t = t_axis(1.2)
    howl = _growl(t, 0.0, 0.7, 210, 150, 0.6, 12.0) + _growl(t, 0.0, 0.7, 105, 76, 0.5, 19.0)
    x = band(howl, 70, 2600) + _fire(t, rng, 0.0, 0.6, 150, 2400, 0.7)
    x += ring(t, [660, 990, 1480], [0.4, 0.25, 0.15], [0.25, 0.15, 0.08], 0.0, rng)
    return finish(reverb(x, 1.2, 0.5, hi=2600, seed=seed, predelay=0.02), -3.0, drive=2.0, fade=0.35)


FILES = {
    "usp_fire_1": lambda: usp_shot(51, 1.00), "usp_fire_2": lambda: usp_shot(52, 0.97),
    "usp_fire_3": lambda: usp_shot(53, 1.03),
    "usp_cerberus_1": lambda: cerberus_shot(56, 1.00), "usp_cerberus_2": lambda: cerberus_shot(57, 0.96),
    "usp_cerberus_3": lambda: cerberus_shot(58, 1.04),
    "cerberus_release": cerberus_release, "cerberus_touch": cerberus_touch, "cerberus_rack": cerberus_rack,
    "cerberus_catch": cerberus_catch, "cerberus_roar": cerberus_roar, "cerberus_kill": cerberus_kill,
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.usp.fire": (["usp_fire_1", "usp_fire_2", "usp_fire_3"], "subtitles.blockland.gun.usp.fire"),
    "gun.usp.fire_cerberus": (["usp_cerberus_1", "usp_cerberus_2", "usp_cerberus_3"],
                              "subtitles.blockland.gun.usp.fire_cerberus"),
    # section 38: the Cerberus skin's inspect events (a clip event plays "gun." + its name) and its kill
    "gun.cerberus.release": (["cerberus_release"], "subtitles.blockland.gun.cerberus.release"),
    "gun.cerberus.touch": (["cerberus_touch"], "subtitles.blockland.gun.cerberus.touch"),
    "gun.cerberus.rack": (["cerberus_rack"], "subtitles.blockland.gun.cerberus.rack"),
    "gun.cerberus.catch": (["cerberus_catch"], "subtitles.blockland.gun.cerberus.catch"),
    "gun.cerberus.roar": (["cerberus_roar"], "subtitles.blockland.gun.cerberus.roar"),
    "gun.cerberus.kill": (["cerberus_kill"], "subtitles.blockland.gun.cerberus.kill"),
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
