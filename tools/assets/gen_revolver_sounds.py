"""Synthesised revolver sounds (VIEWMODEL_SPEC section 49) -> sounds/gun/revolver_*.ogg + their events merged into
sounds.json. Same DSP toolkit as gen_sounds.py / gen_deagle_sounds.py. The .357 out of a 4-inch barrel: a hard,
ringing crack with a big, bright blast (the gap between the cylinder and the barrel spits gas too), a shorter body than
the Deagle's and no slide; the mechanism's sounds are small steel ones: the latch, the crane swinging out on its stop,
the ejector's punch and six cases tinkling, the loader's rounds dropping in and its knob's click, the cylinder snapping
shut, a spin's ratcheting whirr, the hammer's click on an empty chamber."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, t_axis


def shot(seed, pitch=1.0):
    rng = np.random.default_rng(seed)
    t = t_axis(1.10)
    crack = band(rng.standard_normal(len(t)), 2200 * pitch, 14000) * env_exp(t, 0.0013, 0.0001) * 1.0
    crack[:4] += np.array([0.9, -0.7, 0.35, -0.1])
    blast = band(rng.standard_normal(len(t)), 90 * pitch, 3400) * env_exp(t, 0.030, 0.0005)
    gap = band(rng.standard_normal(len(t)), 2500, 9000) * env_exp(t, 0.006, 0.0003, 0.0005) * 0.5   # cylinder gap
    f = 85 * pitch * np.exp(-t / 0.06) + 32 * pitch
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.09, 0.0015) * 1.4
    frame = ring(t, [2300, 3700, 5600], [0.020, 0.012, 0.008], [0.10, 0.07, 0.04], 0.002, rng)   # the frame rings
    dry = crack + 1.1 * blast + gap + boom + frame
    slap = np.zeros_like(dry)
    d = int(0.085 * SR)
    slap[d:] = band(dry[:-d], 120, 2200) * 0.25
    wet = reverb(dry + slap, rt=0.95, mix=0.50, hi=3000, seed=seed + 100, predelay=0.015)
    return finish(wet, -0.6, drive=2.6, fade=0.22)


def steel_tick(t, t0, rng, amp=1.0, bright=1.0):
    x = click(t, t0, rng, 1500, 11000, 0.0025, amp)
    x += ring(t, np.array([2900, 4600, 6900]) * bright * rng.uniform(0.97, 1.03), [0.020, 0.013, 0.008],
              np.array([0.45, 0.30, 0.18]) * amp, t0, rng)
    return x


def latch(seed=120):
    rng = np.random.default_rng(seed)
    t = t_axis(0.25)
    return finish(reverb(steel_tick(t, 0.002, rng, 0.7, 1.1) + steel_tick(t, 0.012, rng, 0.3, 0.9), 0.2, 0.12, seed=seed),
                  -8.0)


def cylinder_open(seed=121):
    """The crane swinging out and hitting its stop, the cylinder's free ring."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    slide = band(rng.standard_normal(len(t)), 1500, 7000) * env_exp(t, 0.025, 0.006) * 0.25
    x = slide + steel_tick(t, 0.045, rng, 1.0, 0.85)
    x += ring(t, [1900, 3300], [0.07, 0.04], [0.15, 0.08], 0.045, rng)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -5.0)


def ejector(seed=122):
    rng = np.random.default_rng(seed)
    t = t_axis(0.30)
    x = click(t, 0.001, rng, 800, 6000, 0.006, 1.0) + steel_tick(t, 0.004, rng, 0.6, 0.8)
    return finish(reverb(x, 0.2, 0.12, seed=seed), -4.0)


def case_out(seed):
    """One case falling out and bouncing: thin brass pings."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    x = np.zeros_like(t)
    t0, amp = rng.uniform(0.04, 0.12), 0.8
    for _ in range(3):
        x += ring(t, np.array([5200, 7900, 10400]) * rng.uniform(0.95, 1.05), [0.03, 0.02, 0.012],
                  np.array([0.5, 0.35, 0.2]) * amp, t0, rng)
        t0 += rng.uniform(0.08, 0.16)
        amp *= 0.5
    return finish(reverb(x, 0.3, 0.2, seed=seed), -10.0)


def speedloader(seed=127):
    """Six rounds dropping into their chambers."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.35)
    x = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.03, 0.003) * 0.3
    for k in range(6):
        x += steel_tick(t, 0.004 + k * rng.uniform(0.004, 0.009), rng, 0.35, 1.2)
    return finish(reverb(x, 0.2, 0.12, seed=seed), -6.0)


def speedloader_twist(seed=128):
    rng = np.random.default_rng(seed)
    t = t_axis(0.25)
    x = click(t, 0.002, rng, 1200, 7000, 0.003, 0.6) + click(t, 0.016, rng, 1500, 8000, 0.002, 0.4)
    return finish(reverb(x, 0.18, 0.1, seed=seed), -9.0)


def cylinder_close(seed=129):
    """The cylinder snapped into the frame: a solid clack and the bolt dropping into its notch."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    x = click(t, 0.002, rng, 600, 7000, 0.006, 1.0) + steel_tick(t, 0.003, rng, 0.9, 0.8)
    x += steel_tick(t, 0.028, rng, 0.4, 1.2)
    return finish(reverb(x, 0.25, 0.15, seed=seed), -3.5)


def cylinder_spin(seed=130):
    """The open cylinder spun: a fast ratcheting whirr slowing down."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.10)
    x = np.zeros_like(t)
    tt, gap = 0.01, 0.018
    while tt < 1.0:
        x += click(t, tt, rng, 2500, 9000, 0.0015, 0.25 * np.exp(-tt / 0.6))
        tt += gap
        gap *= 1.07
    x += band(rng.standard_normal(len(t)), 400, 3000) * env_exp(t, 0.5, 0.01) * 0.05
    return finish(reverb(x, 0.2, 0.12, seed=seed), -9.0)


def dry(seed=131):
    """The hammer falling on an empty chamber."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.25)
    return finish(reverb(steel_tick(t, 0.002, rng, 1.0, 0.75), 0.15, 0.1, seed=seed), -5.0)


FILES = {"revolver_fire_1": lambda: shot(111, 1.00), "revolver_fire_2": lambda: shot(112, 0.97),
         "revolver_fire_3": lambda: shot(113, 1.03), "revolver_latch": latch, "revolver_cylinder_open": cylinder_open,
         "revolver_ejector": ejector, "revolver_case_1": lambda: case_out(123), "revolver_case_2": lambda: case_out(124),
         "revolver_case_3": lambda: case_out(125), "revolver_speedloader": speedloader,
         "revolver_speedloader_twist": speedloader_twist, "revolver_cylinder_close": cylinder_close,
         "revolver_cylinder_spin": cylinder_spin, "revolver_dry": dry}
EVENTS = {   # event: (files, subtitle key); clip event <name> plays gun.<name> (section 4)
    "gun.revolver.fire": (["revolver_fire_1", "revolver_fire_2", "revolver_fire_3"], "subtitles.blockland.gun.revolver.fire"),
    "gun.revolver.dry": (["revolver_dry"], "subtitles.blockland.gun.dry_fire"),
    "gun.revolver_latch": (["revolver_latch"], "subtitles.blockland.gun.revolver_latch"),
    "gun.cylinder_open": (["revolver_cylinder_open"], "subtitles.blockland.gun.cylinder_open"),
    "gun.ejector": (["revolver_ejector"], "subtitles.blockland.gun.ejector"),
    "gun.case_out": (["revolver_case_1", "revolver_case_2", "revolver_case_3"], "subtitles.blockland.gun.case_out"),
    "gun.speedloader": (["revolver_speedloader"], "subtitles.blockland.gun.speedloader"),
    "gun.speedloader_twist": (["revolver_speedloader_twist"], "subtitles.blockland.gun.speedloader"),
    "gun.cylinder_close": (["revolver_cylinder_close"], "subtitles.blockland.gun.cylinder_close"),
    "gun.cylinder_spin": (["revolver_cylinder_spin"], "subtitles.blockland.gun.cylinder_spin"),
}


def entries():
    return {ev: {"sounds": [f"blockland:gun/{f}" for f in files], "subtitle": sub} for ev, (files, sub) in EVENTS.items()}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_revolver_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "gun", f"{name}.ogg"), tmp)
    merge_sounds_json(path("sounds.json"), entries())
    print(f"revolver sounds: {len(FILES)} files, {len(EVENTS)} events")
