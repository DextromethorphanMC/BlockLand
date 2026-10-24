"""Synthesised Uzi sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/uzi_fire_1..3.ogg, uzi_fire_far.ogg (the
placeholders' names; their sounds.json entries already exist and are merged unchanged, locked). Same DSP toolkit as
gen_sounds.py. 9x19 from a 260 mm barrel and an open bolt at 600-760 rpm: a short supersonic crack and a tight, punchy
boom with little low end (a small round), and the Uzi's signature - the heavy telescoping bolt slamming home a few
milliseconds before the shot (open bolt: the clack leads the bang) and hammering back into its buffer after it, the
stamped box receiver ringing thinly. The knob and magazine events reuse gun.bolt_* and gun.mag_*."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def uzi_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    lead = 0.004                                                      # the bolt slams home just before the shot
    pre = click(t, 0.0, rng, 900, 5200, 0.0035, 0.32)                 # bolt face into battery
    pre += ring(t, [1350, 2250, 3700], [0.008, 0.006, 0.004], [0.10, 0.07, 0.04], 0.0, rng)
    tt = np.clip(t - lead, 0.0, None)
    on = (t >= lead).astype(float)
    crack = band(rng.standard_normal(len(t)), 2000 * pitch, 14000) * env_exp(tt, 0.0011, 0.00006) * on
    k = int(lead * SR)
    crack[k:k + 4] += np.array([0.95, -0.65, 0.35, -0.12])
    body = band(rng.standard_normal(len(t)), 130 * pitch, 3800) * env_exp(tt, 0.022, 0.0004) * on
    body2 = band(rng.standard_normal(len(t)), 260, 1500) * env_exp(tt, 0.060, 0.0015, 0.004) * 0.42 * on
    f = 92 * pitch * np.exp(-tt / 0.05) + 40 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(tt, 0.060, 0.0012) * 1.20 * on
    buffer = ring(t, [1100, 1850, 3100, 4600], [0.012, 0.009, 0.006, 0.004], [0.20, 0.14, 0.09, 0.05], 0.042, rng)
    buffer += click(t, 0.042, rng, 1000, 6500, 0.004, 0.22)            # the bolt driven back into its buffer
    box = ring(t, [620, 980, 1720], [0.030, 0.022, 0.015], [0.10, 0.07, 0.05], lead, rng)   # the box receiver rings
    dry = pre + 0.95 * crack + 0.85 * body + body2 + boom + buffer + box
    slap = np.zeros_like(dry)
    d = int(0.066 * SR)
    slap[d:] = band(dry[:-d], 160, 2200) * 0.22
    wet = reverb(dry + slap, rt=0.62, mix=0.46, hi=3000, seed=seed + 100, predelay=0.011)
    return finish(wet, -0.9, drive=2.5, fade=0.15)


def uzi_shot_far(seed):
    x = uzi_shot(seed, 0.94)
    x = band(x, 55, 800)
    x = np.concatenate([np.zeros(int(0.025 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=950, seed=seed + 7, predelay=0.040)
    return finish(x, -8.5, drive=1.4, fade=0.5)


FILES = {
    "uzi_fire_1": lambda: uzi_shot(331, 1.00), "uzi_fire_2": lambda: uzi_shot(332, 0.97),
    "uzi_fire_3": lambda: uzi_shot(333, 1.03), "uzi_fire_far": lambda: uzi_shot_far(334),
}
EVENTS = {   # sound event -> (files, subtitle key): the entries that already exist, unchanged
    "gun.uzi.fire": (["uzi_fire_1", "uzi_fire_2", "uzi_fire_3"], "subtitles.blockland.gun.uzi.fire"),
    "gun.uzi.fire_far": (["uzi_fire_far"], "subtitles.blockland.gun.uzi.fire_far"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_uzi_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"uzi sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
