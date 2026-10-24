"""Synthesised SSG 08 fire sounds (VIEWMODEL_SPEC sections 10 and 40) -> sounds/gun/ssg08_fire_1..3.ogg and
ssg08_fire_far.ogg, replacing the placeholders (the sounds.json events gun.ssg08.fire / gun.ssg08.fire_far already
point at these names). Same DSP toolkit as gen_sounds.py. The shot is .308 Winchester from a 508 mm barrel through a
three-port brake: a hard supersonic crack, a strong, harsh side blast from the brake, a shorter and higher boom than
the AWP's .338, a tight mid rumble and an outdoor tail with a slapback; no action noise (the bolt is worked by hand:
its clip events play gun.bolt_back / gun.bolt_forward)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, t_axis


def ssg_shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.45)
    crack = band(rng.standard_normal(len(t)), 2100 * pitch, 16000) * env_exp(t, 0.0013, 0.00006)
    crack[:5] += np.array([1.15, -0.85, 0.50, -0.22, 0.06])
    blast = band(rng.standard_normal(len(t)), 380 * pitch, 7000) * env_exp(t, 0.018, 0.0003) * 1.15   # brake ports
    blast2 = band(rng.standard_normal(len(t)), 900 * pitch, 5000) * env_exp(t, 0.008, 0.0012, 0.0015) * 0.45
    body = band(rng.standard_normal(len(t)), 85 * pitch, 2800) * env_exp(t, 0.045, 0.0005)
    f = 74 * pitch * np.exp(-t / 0.075) + 30 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.120, 0.0016) * 1.35
    rumble = band(rng.standard_normal(len(t)), 35, 300) * env_exp(t, 0.30, 0.008, 0.015) * 0.45
    dry = 0.95 * crack + blast + blast2 + 0.9 * body + boom + rumble
    echo = np.zeros_like(dry)
    for delay, gain in ((0.095, 0.26), (0.23, 0.15), (0.46, 0.08)):             # slapback off far terrain
        d = int(delay * SR)
        echo[d:] += band(dry[:-d], 100, 1800) * gain
    wet = reverb(dry + echo, rt=1.15, mix=0.58, hi=2900, seed=seed + 100, predelay=0.016)
    return finish(wet, -0.6, drive=2.9, fade=0.4)


def ssg_shot_far(seed):
    x = ssg_shot(seed, 0.93)
    x = band(x, 45, 800)
    x = np.concatenate([np.zeros(int(0.03 * SR)), x, np.zeros(int(1.3 * SR))])
    x = reverb(x, rt=2.0, mix=0.95, hi=950, seed=seed + 7, predelay=0.05)
    return finish(x, -7.2, drive=1.4, fade=0.55)


FILES = {
    "ssg08_fire_1": lambda: ssg_shot(181, 1.00), "ssg08_fire_2": lambda: ssg_shot(182, 0.97),
    "ssg08_fire_3": lambda: ssg_shot(183, 1.03), "ssg08_fire_far": lambda: ssg_shot_far(184),
}
EVENTS = {   # sound event -> (files, subtitle key): as already registered in sounds.json
    "gun.ssg08.fire": (["ssg08_fire_1", "ssg08_fire_2", "ssg08_fire_3"], "subtitles.blockland.gun.ssg08.fire"),
    "gun.ssg08.fire_far": (["ssg08_fire_far"], "subtitles.blockland.gun.ssg08.fire_far"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path, merge=False):
    """Writes the .ogg files; the sounds.json entries already exist (merge=True rewrites them the same way)."""
    tmp = tempfile.mkdtemp(prefix="blockland_ssg08_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    if merge:
        merge_sounds_json(path("sounds.json"), entries())
    print(f"ssg08 sounds: {len(FILES)} files ({', '.join(FILES)})")
