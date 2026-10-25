"""Synthesised ammo crate sounds (VIEWMODEL_SPEC section 46) -> sounds/supply/*.ogg + entries merged into sounds.json.
Same DSP toolkit as gen_knife_sounds.py; every transient sits near the start so it lines up with what plays it.
Wood is a dull, short body (low resonances, no ring); the latches and the hardware click; the magazines and cans inside
are steel and polymer knocking together."""
import tempfile

import numpy as np

from gen_sounds import SR, band, click, encode, env_exp, finish, merge_sounds_json, reverb, ring, rustle, t_axis


def wood_knock(t, t0, rng, amp=1.0, pitch=1.0):
    """A board struck: a thud with two damped low body modes and a short bright tap."""
    x = band(rng.standard_normal(len(t)), 120, 1800) * env_exp(t, 0.012, 0.0008, t0) * amp
    x += ring(t, np.array([165, 310, 520]) * pitch * rng.uniform(0.96, 1.04), [0.040, 0.028, 0.016],
              np.array([0.7, 0.45, 0.25]) * amp, t0, rng)
    return x


def latch(t, t0, rng, amp=1.0):
    """A steel over-centre latch snapping: a sharp click and a tiny spring ring."""
    x = click(t, t0, rng, 2500, 12000, 0.0025, amp)
    x += ring(t, np.array([3400, 5200]) * rng.uniform(0.95, 1.05), [0.012, 0.008], np.array([0.30, 0.18]) * amp, t0, rng)
    return x


def creak(t, t0, dur, rng, amp=1.0):
    """The hinges and the dry lid boards: a slow, rough rising squeak."""
    tt = np.clip(t - t0, 0, dur)
    f = 420 + 260 * tt / dur + 40 * np.sin(2 * np.pi * 7 * tt)
    stick = (rng.random(len(t)) < 0.06).astype(float)
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * (0.5 + 0.5 * band(stick, None, 200) * 6)
    e = np.where((t >= t0) & (t < t0 + dur), np.sin(np.pi * tt / dur) ** 0.7, 0.0)
    return amp * band(tone, 300, 3000) * e


def crate_open(seed=120):
    rng = np.random.default_rng(seed)
    t = t_axis(0.75)
    x = latch(t, 0.005, rng, 0.9) + latch(t, 0.030, rng, 0.8)
    x += creak(t, 0.12, 0.40, rng, 0.35)
    x += wood_knock(t, 0.50, rng, 0.35, 1.2)                         # the lid coming to rest open
    return finish(reverb(x, 0.25, 0.18, seed=seed), -3.0, fade=0.08)


def crate_close(seed=121):
    rng = np.random.default_rng(seed)
    t = t_axis(0.60)
    x = wood_knock(t, 0.005, rng, 1.0) + wood_knock(t, 0.045, rng, 0.25, 1.1)
    x += latch(t, 0.13, rng, 0.8) + latch(t, 0.16, rng, 0.7)
    return finish(reverb(x, 0.25, 0.2, seed=seed), -2.0)


def metal_clink(t, t0, rng, amp=1.0):
    x = click(t, t0, rng, 1500, 9000, 0.003, amp)
    x += ring(t, np.array([1900, 3100, 4700]) * rng.uniform(0.9, 1.1), [0.035, 0.022, 0.012],
              np.array([0.4, 0.3, 0.15]) * amp, t0, rng)
    return x


def rummage(seed):
    """A hand among the magazines and cans: knocks and clinks, the cloth of the sleeve."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.70)
    x = rustle(t, rng, 0.0, 0.5, 300, 3500, 0.35)
    tt = 0.03
    for _ in range(int(rng.integers(4, 7))):
        x += metal_clink(t, tt, rng, rng.uniform(0.35, 0.9))
        tt += rng.uniform(0.05, 0.11)
    x += wood_knock(t, rng.uniform(0.1, 0.3), rng, 0.25, 1.4)
    return finish(reverb(x, 0.25, 0.2, seed=seed), -5.0, fade=0.08)


def magazine(seed):
    """A magazine pulled out from between the others: a slide of polymer on steel and a firm grab."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.40)
    slide = band(rng.standard_normal(len(t)), 900, 6000) * env_exp(t, 0.06, 0.02, 0.0) * 0.4
    x = slide + metal_clink(t, 0.07, rng, 0.6)
    x += band(rng.standard_normal(len(t)), 150, 900) * env_exp(t, 0.02, 0.001, 0.10) * 0.5    # the hand closing on it
    return finish(reverb(x, 0.2, 0.15, seed=seed), -4.0, fade=0.06)


FILES = {"crate_open": crate_open, "crate_close": crate_close,
         "rummage_1": lambda: rummage(122), "rummage_2": lambda: rummage(123),
         "magazine_1": lambda: magazine(124), "magazine_2": lambda: magazine(125)}
EVENTS = {"supply.crate_open": ["crate_open"], "supply.crate_close": ["crate_close"],
          "supply.rummage": ["rummage_1", "rummage_2"], "supply.magazine": ["magazine_1", "magazine_2"]}


def build(path):
    tmp = tempfile.mkdtemp(prefix="blockland_supply_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "supply", f"{name}.ogg"), tmp)
    entries = {ev: {"sounds": [f"blockland:supply/{f}" for f in files],
                    "subtitle": f"subtitles.blockland.{ev}"} for ev, files in EVENTS.items()}
    merge_sounds_json(path("sounds.json"), entries)
    print(f"supply sounds: {len(FILES)} files, {len(EVENTS)} events")
