"""Synthesised Jackhammer fire sounds (VIEWMODEL_SPEC section 40) -> sounds/gun/jackhammer_fire_1..3.ogg and
jackhammer_fire_far.ogg (the sounds.json events gun.jackhammer.fire / fire_far already exist and are left alone). Same
DSP toolkit as gen_sounds.py. A 12-gauge boom like the KS-3's, but from a shorter barrel and a revolver: a harder,
brighter blast (more gas leaves at the cylinder gap), a little less low boom, and the action's own mechanical voice
right after the shot: the gas piston and operating rod clacking back and the cassette's cam snapping it round one
chamber (a sharp ratchet click and a hollow aluminium knock)."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, reverb, ring, t_axis


def shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.40)
    crack = band(rng.standard_normal(len(t)), 1100 * pitch, 11000) * env_exp(t, 0.0026, 0.0001)
    crack[:6] += np.array([1.15, -0.95, 0.62, -0.36, 0.18, -0.06])
    blast = band(rng.standard_normal(len(t)), 220 * pitch, 5200) * env_exp(t, 0.028, 0.0004) * 1.25
    gap = band(rng.standard_normal(len(t)), 2400, 9500) * env_exp(t, 0.012, 0.0003, 0.0008) * 0.55   # cylinder gap
    f = 78 * pitch * np.exp(-t / 0.06) + 32 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.11, 0.0016) * 1.6
    rumble = band(rng.standard_normal(len(t)), 30, 260) * env_exp(t, 0.30, 0.008, 0.02) * 0.5
    # the action: operating rod back (a dull clack), the cassette indexed (ratchet click + hollow knock)
    mech = click(t, 0.016, rng, 500, 4000, 0.008, 0.30)
    mech += click(t, 0.040 + 0.002 * rng.random(), rng, 1500, 10000, 0.003, 0.32)
    mech += ring(t, [640, 1180, 2050, 3400], [0.040, 0.028, 0.016, 0.009], [0.16, 0.12, 0.08, 0.04], 0.040, rng)
    dry = 0.85 * crack + blast + gap + boom + rumble + mech
    echo = np.zeros_like(dry)
    for delay, gain in ((0.08, 0.30), (0.20, 0.16), (0.39, 0.08)):
        d = int(delay * SR)
        echo[d:] += band(dry[:-d], 90, 1500) * gain
    wet = reverb(dry + echo, rt=1.05, mix=0.55, hi=2600, seed=seed + 100, predelay=0.013)
    return finish(wet, -0.5, drive=3.4, fade=0.38)


def shot_far(seed):
    x = shot(seed, 0.9)
    x = band(x, 38, 650)
    x = np.concatenate([np.zeros(int(0.03 * SR)), x, np.zeros(int(1.2 * SR))])
    x = reverb(x, rt=2.0, mix=0.95, hi=850, seed=seed + 7, predelay=0.05)
    return finish(x, -7.0, drive=1.4, fade=0.6)


FILES = {
    "jackhammer_fire_1": lambda: shot(4201, 1.00), "jackhammer_fire_2": lambda: shot(4202, 0.97),
    "jackhammer_fire_3": lambda: shot(4203, 1.03), "jackhammer_fire_far": lambda: shot_far(4204),
}


def build(path):
    """Writes the four .ogg files over the placeholders (sounds.json already names them)."""
    tmp = tempfile.mkdtemp(prefix="blockland_jackhammer_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    print(f"jackhammer sounds: {len(FILES)} files (gun.jackhammer.fire x3, gun.jackhammer.fire_far)")
