"""Synthesised AWP sounds (VIEWMODEL_SPEC section 23) -> sounds/gun/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_sounds.py. The shot is .338 Lapua through a muzzle brake: a hard supersonic crack, a deep
long boom and a harsh side blast, with a long outdoor tail; no action noise (the bolt is worked by hand: its four
clip events have their own sounds)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def awp_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.80)
    crack = band(rng.standard_normal(len(t)), 1800 * pitch, 16000) * env_exp(t, 0.0016, 0.00008)
    crack[:5] += np.array([1.2, -0.9, 0.55, -0.25, 0.08])
    blast = band(rng.standard_normal(len(t)), 300 * pitch, 6000) * env_exp(t, 0.022, 0.0004) * 1.05   # brake side blast
    body = band(rng.standard_normal(len(t)), 70 * pitch, 2400) * env_exp(t, 0.060, 0.0006)
    f = 62 * pitch * np.exp(-t / 0.09) + 24 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.160, 0.002) * 1.6
    rumble = band(rng.standard_normal(len(t)), 30, 260) * env_exp(t, 0.42, 0.010, 0.02) * 0.55
    dry = 0.95 * crack + blast + 0.9 * body + boom + rumble
    echo = np.zeros_like(dry)
    for delay, gain in ((0.11, 0.30), (0.27, 0.18), (0.52, 0.10)):              # slapback off far terrain
        d = int(delay * SR)
        echo[d:] += band(dry[:-d], 90, 1600) * gain
    wet = reverb(dry + echo, rt=1.35, mix=0.62, hi=2600, seed=seed + 100, predelay=0.018)
    return finish(wet, -0.5, drive=3.0, fade=0.45)


def awp_shot_far(seed):
    x = awp_shot(seed, 0.92)
    x = band(x, 40, 700)
    x = np.concatenate([np.zeros(int(0.03 * SR)), x, np.zeros(int(1.4 * SR))])
    x = reverb(x, rt=2.2, mix=0.95, hi=900, seed=seed + 7, predelay=0.05)
    return finish(x, -7.0, drive=1.4, fade=0.6)


def bolt_up(seed=91):
    """Handle lifted: the cocking cam snaps the firing pin back -- a short dry double click and a scrape."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.22)
    x = click(t, 0.002, rng, 1500, 9000, 0.003, 0.9)
    x += ring(t, [2300, 3700, 5600], [0.018, 0.012, 0.007], [0.40, 0.28, 0.16], 0.002, rng)
    x += click(t, 0.016, rng, 2500, 10000, 0.002, 0.45)
    x += band(rng.standard_normal(len(t)), 1800, 7000) * env_exp(t, 0.020, 0.002, 0.004) * 0.18     # cam scrape
    return finish(reverb(x, 0.22, 0.20, seed=seed), -2.0, drive=1.4)


def bolt_back(seed=92):
    """Bolt drawn back: a long steel-on-steel slide (the case extracting) ending on the bolt stop; the click at the
    start marks the event."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    slide = band(rng.standard_normal(len(t)), 1200, 8000) * (env_exp(t, 0.090, 0.004) * 0.35)
    slide *= 1 + 0.25 * np.sin(2 * np.pi * 38 * t)                                 # lugs riding the raceway
    x = click(t, 0.002, rng, 1400, 9000, 0.004, 1.0) + slide
    x += ring(t, [1700, 2900, 4300, 6100], [0.040, 0.026, 0.016, 0.009], [0.55, 0.38, 0.24, 0.12], 0.002, rng)
    x += ring(t, [3100, 5200], [0.06, 0.04], [0.10, 0.06], 0.010, rng)            # ejected case ringing
    return finish(reverb(x, 0.30, 0.25, seed=seed), -1.5, drive=1.5)


def bolt_forward(seed=93):
    """Bolt pushed home: the slide forward stripping a round, then the solid thunk of the round seating."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.42)
    x = band(rng.standard_normal(len(t)), 900, 6500) * env_exp(t, 0.030, 0.002) * 0.30
    x += click(t, 0.004, rng, 900, 11000, 0.004, 1.2)
    x += ring(t, [1250, 2300, 3600, 5400], [0.050, 0.034, 0.020, 0.011], [0.85, 0.60, 0.38, 0.18], 0.004, rng)
    x += np.sin(2 * np.pi * 110 * t) * env_exp(t, 0.045, 0.001, 0.004) * 0.9
    return finish(reverb(x, 0.32, 0.28, seed=seed), -0.8, drive=1.7)


def bolt_down(seed=94):
    """Handle pressed down: the lugs rotate into battery -- a firm low clack."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.30)
    x = click(t, 0.002, rng, 800, 8000, 0.004, 1.1)
    x += ring(t, [1450, 2600, 4100], [0.035, 0.024, 0.014], [0.70, 0.45, 0.25], 0.002, rng)
    x += np.sin(2 * np.pi * 150 * t) * env_exp(t, 0.030, 0.001, 0.002) * 0.6
    return finish(reverb(x, 0.26, 0.22, seed=seed), -1.2, drive=1.6)


FILES = {
    "awp_fire_1": lambda: awp_shot(41, 1.00), "awp_fire_2": lambda: awp_shot(42, 0.97),
    "awp_fire_3": lambda: awp_shot(43, 1.03), "awp_fire_far": lambda: awp_shot_far(44),
    "awp_bolt_up": bolt_up, "awp_bolt_back": bolt_back, "awp_bolt_forward": bolt_forward, "awp_bolt_down": bolt_down,
}
EVENTS = {   # sound event -> (files, subtitle key); the bolt events are the clip events (section 23)
    "gun.awp.fire": (["awp_fire_1", "awp_fire_2", "awp_fire_3"], "subtitles.blockland.gun.awp.fire"),
    "gun.awp.fire_far": (["awp_fire_far"], "subtitles.blockland.gun.awp.fire_far"),
    "gun.awp.bolt_up": (["awp_bolt_up"], "subtitles.blockland.gun.awp.bolt_up"),
    "gun.awp.bolt_back": (["awp_bolt_back"], "subtitles.blockland.gun.awp.bolt_back"),
    "gun.awp.bolt_forward": (["awp_bolt_forward"], "subtitles.blockland.gun.awp.bolt_forward"),
    "gun.awp.bolt_down": (["awp_bolt_down"], "subtitles.blockland.gun.awp.bolt_down"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=True):
    """merge=False only writes the .ogg files (sounds.json is then edited by hand)."""
    tmp = tempfile.mkdtemp(prefix="blockland_awp_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"awp sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
