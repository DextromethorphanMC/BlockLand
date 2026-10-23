"""Synthesised KS-3 sounds (VIEWMODEL_SPEC section 25) -> sounds/gun/*.ogg + entries merged into sounds.json (locked).
Same DSP toolkit as gen_sounds.py. A 12-gauge shot is a deep, wide boom rather than a crack: a short dull report, a
big low thump from the gas and a broad mid-band blast, a long outdoor tail; the action is worked by hand, so its clip
events (pump back / forward, a shell thumbed into the tube) have their own sounds."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def ks3_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.50)
    crack = band(rng.standard_normal(len(t)), 900 * pitch, 9000) * env_exp(t, 0.0030, 0.00012)
    crack[:6] += np.array([1.1, -0.9, 0.6, -0.35, 0.18, -0.05])
    blast = band(rng.standard_normal(len(t)), 180 * pitch, 4200) * env_exp(t, 0.032, 0.0005) * 1.15
    f = 72 * pitch * np.exp(-t / 0.07) + 30 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.13, 0.0018) * 1.9
    rumble = band(rng.standard_normal(len(t)), 28, 240) * env_exp(t, 0.36, 0.008, 0.02) * 0.6
    wad = ring(t, [520, 860], [0.030, 0.020], [0.12, 0.08], 0.004, rng)          # hull and wad, a hollow tone
    dry = 0.8 * crack + blast + boom + rumble + wad
    echo = np.zeros_like(dry)
    for delay, gain in ((0.09, 0.30), (0.22, 0.17), (0.43, 0.09)):
        d = int(delay * SR)
        echo[d:] += band(dry[:-d], 80, 1400) * gain
    wet = reverb(dry + echo, rt=1.15, mix=0.58, hi=2400, seed=seed + 100, predelay=0.014)
    return finish(wet, -0.5, drive=3.2, fade=0.40)


def ks3_shot_far(seed):
    x = ks3_shot(seed, 0.9)
    x = band(x, 35, 600)
    x = np.concatenate([np.zeros(int(0.03 * SR)), x, np.zeros(int(1.2 * SR))])
    x = reverb(x, rt=2.0, mix=0.95, hi=800, seed=seed + 7, predelay=0.05)
    return finish(x, -7.0, drive=1.4, fade=0.6)


def pump_back(seed=95):
    """Forend racked back: a quick plastic-on-steel slide, the action bars rattling, the bolt slamming on its stop
    (the hull is thrown out); the click at the start marks the event."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.34)
    slide = band(rng.standard_normal(len(t)), 700, 6500) * env_exp(t, 0.045, 0.003) * 0.45
    slide *= 1 + 0.35 * np.sin(2 * np.pi * 52 * t)
    x = click(t, 0.002, rng, 1200, 8000, 0.003, 0.7) + slide
    x += click(t, 0.050, rng, 900, 9000, 0.004, 1.1)                            # bolt on the stop
    x += ring(t, [1350, 2400, 3900, 5600], [0.030, 0.020, 0.012, 0.007], [0.65, 0.42, 0.25, 0.12], 0.050, rng)
    x += np.sin(2 * np.pi * 140 * t) * env_exp(t, 0.020, 0.001, 0.050) * 0.6
    return finish(reverb(x, 0.28, 0.24, seed=seed), -1.0, drive=1.7)


def pump_forward(seed=96):
    """Forend driven home: the slide forward stripping a shell from the tube and the solid clack of the bolt locking."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.36)
    x = band(rng.standard_normal(len(t)), 600, 5500) * env_exp(t, 0.035, 0.003) * 0.35
    x += click(t, 0.040, rng, 800, 10000, 0.004, 1.3)
    x += ring(t, [1150, 2100, 3300, 5000], [0.040, 0.026, 0.016, 0.009], [0.85, 0.55, 0.33, 0.15], 0.040, rng)
    x += np.sin(2 * np.pi * 105 * t) * env_exp(t, 0.040, 0.001, 0.040) * 1.0
    return finish(reverb(x, 0.30, 0.26, seed=seed), -0.6, drive=1.8)


def shell_in(seed):
    """A shell thumbed past the loading gate: plastic scraping steel, the gate snapping back, the shell seating."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.26)
    x = band(rng.standard_normal(len(t)), 500, 4500) * env_exp(t, 0.030, 0.002) * 0.40
    x += click(t, 0.002, rng, 1500, 9000, 0.003, 0.6)
    x += click(t, 0.034 + 0.006 * rng.random(), rng, 1100, 8000, 0.003, 0.9)   # gate snaps back
    x += ring(t, [1900, 3100, 4700], [0.016, 0.010, 0.006], [0.40, 0.25, 0.14], 0.036, rng)
    x += np.sin(2 * np.pi * 190 * t) * env_exp(t, 0.018, 0.001, 0.036) * 0.45
    return finish(reverb(x, 0.22, 0.20, seed=seed), -2.0, drive=1.4)


FILES = {
    "ks3_fire_1": lambda: ks3_shot(51, 1.00), "ks3_fire_2": lambda: ks3_shot(52, 0.97),
    "ks3_fire_3": lambda: ks3_shot(53, 1.03), "ks3_fire_far": lambda: ks3_shot_far(54),
    "pump_back": pump_back, "pump_forward": pump_forward,
    "shell_in_1": lambda: shell_in(97), "shell_in_2": lambda: shell_in(98), "shell_in_3": lambda: shell_in(99),
}
EVENTS = {   # sound event -> (files, subtitle key); pump and shell events are the clip events (section 25)
    "gun.ks3.fire": (["ks3_fire_1", "ks3_fire_2", "ks3_fire_3"], "subtitles.blockland.gun.ks3.fire"),
    "gun.ks3.fire_far": (["ks3_fire_far"], "subtitles.blockland.gun.ks3.fire_far"),
    "gun.pump_back": (["pump_back"], "subtitles.blockland.gun.pump_back"),
    "gun.pump_forward": (["pump_forward"], "subtitles.blockland.gun.pump_forward"),
    "gun.shell_in": (["shell_in_1", "shell_in_2", "shell_in_3"], "subtitles.blockland.gun.shell_in"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    """merge=False only writes the .ogg files (sounds.json is then edited by hand)."""
    tmp = tempfile.mkdtemp(prefix="blockland_ks3_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"ks3 sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
