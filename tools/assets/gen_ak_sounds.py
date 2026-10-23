"""Synthesised AK-47 sounds (VIEWMODEL_SPEC section 18) -> sounds/gun/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_sounds.py. Magazine events reuse gun.mag_* (the clip events are the shared names)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def rifle_shot(seed, pitch=1.0):
    """7.62x39: sharper supersonic crack, heavier muzzle boom, carrier slamming back/forward inside 0.1 s."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.95)
    crack = band(rng.standard_normal(len(t)), 2200 * pitch, 15000) * env_exp(t, 0.0012, 0.00008)
    crack[:4] += np.array([1.0, -0.7, 0.4, -0.15])
    body = band(rng.standard_normal(len(t)), 110 * pitch, 3600) * env_exp(t, 0.030, 0.0005)
    body2 = band(rng.standard_normal(len(t)), 220, 1300) * env_exp(t, 0.090, 0.002, 0.005) * 0.45
    f = 85 * pitch * np.exp(-t / 0.06) + 34 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.080, 0.0015) * 1.35
    carrier = ring(t, [1450, 2350, 3900], [0.010, 0.008, 0.005], [0.14, 0.10, 0.06], 0.028, rng)
    carrier += click(t, 0.028, rng, 1200, 6000, 0.004, 0.10)
    carrier += ring(t, [1250, 2100, 3600, 5200], [0.016, 0.011, 0.007, 0.004], [0.18, 0.12, 0.08, 0.04], 0.074, rng)
    carrier += click(t, 0.074, rng, 1500, 7000, 0.003, 0.14)
    dry = 0.95 * crack + 0.85 * body + body2 + boom + carrier
    slap = np.zeros_like(dry)
    d = int(0.085 * SR)
    slap[d:] = band(dry[:-d], 160, 2200) * 0.22
    wet = reverb(dry + slap, rt=0.75, mix=0.50, hi=3000, seed=seed + 100, predelay=0.014)
    return finish(wet, -0.8, drive=2.6, fade=0.18)


def rifle_shot_far(seed):
    x = rifle_shot(seed, 0.94)
    x = band(x, 50, 800)
    x = np.concatenate([np.zeros(int(0.025 * SR)), x, np.zeros(int(1.2 * SR))])
    x = reverb(x, rt=1.7, mix=0.95, hi=1000, seed=seed + 7, predelay=0.04)
    return finish(x, -8.0, drive=1.4, fade=0.5)


def bolt_back(seed=81):
    """Charging handle pulled: gritty carrier slide over ~9 cm, ending in a hard clack at the rear trunnion.
    The clack (the event moment) sits right at the start so it lines up with the event."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.32)
    x = click(t, 0.002, rng, 1200, 9000, 0.004, 1.1)
    x += ring(t, [1900, 3100, 4700, 6600], [0.030, 0.020, 0.012, 0.007], [0.55, 0.40, 0.26, 0.14], 0.002, rng)
    x += np.sin(2 * np.pi * 160 * t) * env_exp(t, 0.025, 0.001, 0.002) * 0.45
    pre = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.012, 0.0005, 0.0) * 0.35   # end of the slide
    x += pre
    return finish(reverb(x, 0.28, 0.25, seed=seed), -1.5, drive=1.5)


def bolt_forward(seed=82):
    """Carrier slams home under the recoil spring: heavy double clack (carrier, bolt rotating into lock) + ring."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.42)
    x = click(t, 0.002, rng, 1000, 12000, 0.004, 1.3)
    x += ring(t, [1350, 2450, 3800, 5900], [0.050, 0.034, 0.022, 0.012], [0.9, 0.65, 0.42, 0.22], 0.002, rng)
    x += click(t, 0.012, rng, 2500, 10000, 0.003, 0.55)
    x += np.sin(2 * np.pi * 120 * t) * env_exp(t, 0.040, 0.001, 0.002) * 0.85
    return finish(reverb(x, 0.32, 0.30, seed=seed), -0.8, drive=1.7)


FILES = {
    "ak47_fire_1": lambda: rifle_shot(11, 1.00), "ak47_fire_2": lambda: rifle_shot(12, 0.97),
    "ak47_fire_3": lambda: rifle_shot(13, 1.03), "ak47_fire_far": lambda: rifle_shot_far(14),
    "bolt_back": bolt_back, "bolt_forward": bolt_forward,
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.ak47.fire": (["ak47_fire_1", "ak47_fire_2", "ak47_fire_3"], "subtitles.blockland.gun.ak47.fire"),
    "gun.ak47.fire_far": (["ak47_fire_far"], "subtitles.blockland.gun.ak47.fire_far"),
    "gun.bolt_back": (["bolt_back"], "subtitles.blockland.gun.bolt_back"),
    "gun.bolt_forward": (["bolt_forward"], "subtitles.blockland.gun.bolt_forward"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_ak_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"ak sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
