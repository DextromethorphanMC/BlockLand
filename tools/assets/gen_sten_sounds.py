"""Synthesised STEN Mk II sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/sten_fire_1..3.ogg and sten_fire_far.ogg
(the sounds.json entries gun.sten.fire / gun.sten.fire_far keep their names; merged, locked). Same DSP toolkit as
gen_sounds.py. 9x19 mm from a short barrel in an open-bolt gun: a sharper, lighter crack than the Thompson's .45, a
quick hollow body, and the STEN's signature: the heavy bolt slamming forward and the thin rolled-steel receiver and
the loose magazine in its housing rattling with every shot -- a dry, tinny chatter at ~540 rpm. The magazine and
cocking-handle events reuse gun.mag_* and gun.bolt_* (the clip events are the shared names)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def sten_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(0.75)
    crack = band(rng.standard_normal(len(t)), 1500 * pitch, 11000) * env_exp(t, 0.0018, 0.00008)
    crack[:4] += np.array([0.85, -0.6, 0.3, -0.1])
    body = band(rng.standard_normal(len(t)), 120 * pitch, 2800) * env_exp(t, 0.026, 0.0005)
    f = 82 * pitch * np.exp(-t / 0.06) + 36 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.070, 0.0012) * 1.25
    slam = click(t, 0.0, rng, 400, 3800, 0.006, 0.30)                    # the open bolt closing as it fires
    # the thin receiver tube and the magazine in its housing ringing / rattling (tinny, high partials)
    tube = ring(t, [1650, 2480, 3900, 5600], [0.010, 0.008, 0.006, 0.004], [0.18, 0.13, 0.08, 0.05], 0.004, rng)
    rattle = sum(click(t, 0.018 + 0.011 * k + 0.004 * rng.random(), rng, 2500, 9000, 0.0015, 0.10 * (1 - 0.18 * k))
                 for k in range(4))
    back = ring(t, [1100, 2050], [0.010, 0.007], [0.12, 0.08], 0.058, rng)    # bolt into the buffer
    back += click(t, 0.058, rng, 900, 6000, 0.003, 0.14)
    dry = 0.9 * crack + 0.85 * body + boom + slam + tube + rattle + back
    slap = np.zeros_like(dry)
    d = int(0.075 * SR)
    slap[d:] = band(dry[:-d], 150, 2200) * 0.22
    wet = reverb(dry + slap, rt=0.62, mix=0.45, hi=3000, seed=seed + 100, predelay=0.011)
    return finish(wet, -0.9, drive=2.3, fade=0.15)


def sten_shot_far(seed):
    x = sten_shot(seed, 0.93)
    x = band(x, 55, 900)
    x = np.concatenate([np.zeros(int(0.022 * SR)), x, np.zeros(int(1.0 * SR))])
    x = reverb(x, rt=1.5, mix=0.95, hi=1000, seed=seed + 7, predelay=0.04)
    return finish(x, -9.0, drive=1.4, fade=0.5)


FILES = {
    "sten_fire_1": lambda: sten_shot(861, 1.00), "sten_fire_2": lambda: sten_shot(862, 0.97),
    "sten_fire_3": lambda: sten_shot(863, 1.03), "sten_fire_far": lambda: sten_shot_far(864),
}
EVENTS = {   # sound event -> (files, subtitle key)
    "gun.sten.fire": (["sten_fire_1", "sten_fire_2", "sten_fire_3"], "subtitles.blockland.gun.sten.fire"),
    "gun.sten.fire_far": (["sten_fire_far"], "subtitles.blockland.gun.sten.fire_far"),
}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_sten_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"sten sounds: {len(FILES)} files, events {', '.join(EVENTS)}")
