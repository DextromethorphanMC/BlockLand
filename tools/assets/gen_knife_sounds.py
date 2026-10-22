"""Synthesised knife sounds (VIEWMODEL_SPEC section 13) -> sounds/knife/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_sounds.py; every transient sits at the very start so it lines up with its event."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def whoosh(seed, dur=0.30, peak=0.06, f_lo=500, f_hi=3200, amp=1.0, whistle=0.15):
    """Air swish: noise through a band that sweeps up to the peak and back down, plus a faint blade whistle."""
    rng = np.random.default_rng(seed)
    t = t_axis(dur)
    env = np.where(t < peak, (t / peak) ** 1.6, np.exp(-(t - peak) / (0.28 * (dur - peak))))
    n = rng.standard_normal(len(t))
    out = np.zeros_like(t)
    edges = np.linspace(0, len(t), 9).astype(int)
    for k in range(8):                       # piecewise band sweep, crossfaded by the envelope
        tc = t[min((edges[k] + edges[k + 1]) // 2, len(t) - 1)]
        x = np.exp(-((tc - peak) / (0.45 * dur)) ** 2)
        fc = f_lo + (f_hi - f_lo) * x
        seg = band(n, fc * 0.55, fc * 1.6)
        w = np.zeros_like(t)
        w[edges[k]:edges[k + 1]] = 1.0
        w = band(w, None, 40)                # soft crossfade between segments
        out += seg * w
    tone = np.sin(2 * np.pi * np.cumsum(2100 + 900 * np.exp(-((t - peak) / 0.05) ** 2)) / SR)
    out = out + whistle * tone * np.max(np.abs(out)) * env
    return amp * out * env


def handle_clack(t, t0, rng, amp=1.0, bright=1.0):
    """Two balisong handles slapping together: sharp click + short metallic ring."""
    x = click(t, t0, rng, 2000, 12000, 0.0025, amp)
    x += ring(t, np.array([3150, 4900, 7100]) * bright * rng.uniform(0.97, 1.03), [0.022, 0.016, 0.010],
              np.array([0.55, 0.40, 0.25]) * amp, t0, rng)
    return x


def equip(seed=70):
    rng = np.random.default_rng(seed)
    t = t_axis(0.45)
    x = rustle(t, rng, 0.0, 0.22, 300, 3500, 0.6)
    x += ring(t, [2600, 4200], [0.010, 0.006], [0.30, 0.18], 0.17, rng)
    x += handle_clack(t, 0.24, rng, 0.45, 0.9)
    return finish(reverb(x, 0.2, 0.18, seed=seed), -5.0, fade=0.06)


def open_snap(seed=71):
    rng = np.random.default_rng(seed)
    t = t_axis(0.34)
    x = handle_clack(t, 0.002, rng, 1.0) + handle_clack(t, 0.013, rng, 0.6, 1.05)
    x += click(t, 0.040, rng, 3000, 10000, 0.002, 0.25)             # latch tick
    return finish(reverb(x, 0.22, 0.20, seed=seed), -1.5, drive=1.3)


def flip(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.26)
    x = whoosh(seed, 0.26, 0.04, 900, 4200, 0.45, 0.05)
    tt = 0.004
    for k in range(int(rng.integers(2, 4))):
        x += handle_clack(t, tt, rng, 0.55 * 0.7 ** k, rng.uniform(0.9, 1.15))
        tt += rng.uniform(0.035, 0.06)
    return finish(reverb(x, 0.2, 0.15, seed=seed), -4.0, fade=0.05)


def catch(seed=75):
    rng = np.random.default_rng(seed)
    t = t_axis(0.25)
    x = np.sin(2 * np.pi * 210 * t) * env_exp(t, 0.030, 0.001, 0.002) * 0.7
    x += band(rng.standard_normal(len(t)), 200, 1800) * env_exp(t, 0.025, 0.001, 0.002) * 0.5
    x += handle_clack(t, 0.010, rng, 0.8)
    return finish(reverb(x, 0.2, 0.15, seed=seed), -3.0)


def swing(seed):
    rng = np.random.default_rng(seed)
    x = whoosh(seed, 0.28, rng.uniform(0.045, 0.06), rng.uniform(450, 650), rng.uniform(2800, 3600), 1.0, 0.12)
    return finish(x, -4.0, fade=0.05)


def swing_heavy(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.46)
    x = whoosh(seed, 0.46, rng.uniform(0.10, 0.13), 260, 1900, 1.0, 0.08)
    x += rustle(t, rng, 0.0, 0.22, 150, 1500, 0.25) * np.max(np.abs(x))
    return finish(x, -2.5, drive=1.2, fade=0.08)


def hit_flesh(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.32)
    f = 120 * rng.uniform(0.9, 1.1) * np.exp(-t / 0.05) + 55
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.045, 0.002) * 0.9
    grains = (rng.random(len(t)) < 0.004) * rng.standard_normal(len(t))
    wet = band(grains + 0.3 * rng.standard_normal(len(t)), 300, 2600) * env_exp(t, 0.065, 0.003)
    x += wet / (np.max(np.abs(wet)) + 1e-9) * 0.7
    x += band(rng.standard_normal(len(t)), 2000, 6500) * env_exp(t, 0.014, 0.0005) * 0.35
    return finish(reverb(x, 0.15, 0.10, seed=seed), -2.0, drive=1.4)


def hit_wall(seed):
    rng = np.random.default_rng(seed)
    t = t_axis(0.42)
    x = click(t, 0.001, rng, 2000, 14000, 0.003, 1.0)
    x += ring(t, np.array([2800, 5100, 7600]) * rng.uniform(0.94, 1.06), [0.11, 0.07, 0.05], [0.45, 0.32, 0.2],
              0.001, rng)
    grit = band(rng.standard_normal(len(t)), 1000, 6000) * env_exp(t, 0.040, 0.001)
    grit *= 0.5 + (rng.random(len(t)) < 0.03)
    x += 0.35 * grit
    x += np.sin(2 * np.pi * 180 * t) * env_exp(t, 0.025, 0.001) * 0.5
    return finish(reverb(x, 0.3, 0.2, seed=seed), -1.5, drive=1.3)


FILES = {
    "equip": equip, "open": open_snap, "flip_1": lambda: flip(72), "flip_2": lambda: flip(73), "flip_3": lambda: flip(74),
    "catch": catch, "swing_1": lambda: swing(76), "swing_2": lambda: swing(77), "swing_3": lambda: swing(78),
    "swing_heavy_1": lambda: swing_heavy(79), "swing_heavy_2": lambda: swing_heavy(80),
    "hit_flesh_1": lambda: hit_flesh(81), "hit_flesh_2": lambda: hit_flesh(82), "hit_flesh_3": lambda: hit_flesh(83),
    "hit_wall_1": lambda: hit_wall(84), "hit_wall_2": lambda: hit_wall(85), "hit_wall_3": lambda: hit_wall(86),
}
EVENTS = {"knife.equip": ["equip"], "knife.open": ["open"], "knife.flip": ["flip_1", "flip_2", "flip_3"],
          "knife.catch": ["catch"], "knife.swing": ["swing_1", "swing_2", "swing_3"],
          "knife.swing_heavy": ["swing_heavy_1", "swing_heavy_2"],
          "knife.hit_flesh": ["hit_flesh_1", "hit_flesh_2", "hit_flesh_3"],
          "knife.hit_wall": ["hit_wall_1", "hit_wall_2", "hit_wall_3"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_knife_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "knife", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:knife/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"knife sounds: {len(FILES)} files, {len(EVENTS)} events")
