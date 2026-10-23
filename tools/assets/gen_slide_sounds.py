"""Synthesised slide (滑铲) sound -> sounds/movement/slide_*.ogg and the `movement.slide` entry merged into sounds.json.
Same DSP toolkit as gen_sounds.py.

A body dropping into a slide: a soft thump as the hip meets the ground, then a gritty scrape of shoes and clothes
that starts bright and fast and slows down with the slide (the band falls and the grit thins out), with cloth rustle
over it."""
import tempfile

import numpy as np

from gen_sounds import SR, band, encode, env_exp, finish, merge_sounds_json, reverb, rustle, t_axis


def slide(seed, pitch=1.0, length=0.78):
    rng = np.random.default_rng(seed)
    t = t_axis(length)
    # the hip / knee hits the ground
    thump = band(rng.standard_normal(len(t)), 60 * pitch, 700 * pitch) * env_exp(t, 0.035, 0.004, 0.012) * 0.9
    # the scrape: noise whose band slides down as the body slows; amplitude rises fast, then fades with the speed
    slow = np.clip(t / length, 0, 1)
    scrape = np.zeros(len(t))
    for lo, hi, gain in ((900, 5200, 0.55), (2400, 9000, 0.35), (300, 1600, 0.45)):
        noise = band(rng.standard_normal(len(t)), lo * pitch, hi * pitch)
        # crude time-varying low-pass: blend towards a darker copy as the slide slows
        dark = band(noise, None, hi * pitch * 0.35)
        scrape += gain * (noise * (1 - slow) + dark * slow)
    swell = np.clip((t - 0.008) / 0.05, 0, 1) * np.exp(-np.maximum(t - 0.06, 0) / 0.26)
    # grit: sparse little ticks of sand and gravel, thinning out
    grit = np.zeros(len(t))
    count = int(140 * pitch)
    for at in np.sort(rng.uniform(0.01, length * 0.85, count)):
        if rng.uniform() > np.exp(-at / 0.35):
            continue
        i = int(at * SR)
        n = min(len(t) - i, int(0.003 * SR))
        grit[i:i + n] += rng.standard_normal(n) * np.exp(-np.arange(n) / (0.0007 * SR)) * rng.uniform(0.2, 0.7)
    grit = band(grit, 2500, 11000)
    cloth = rustle(t, rng, 0.0, length * 0.7, 250, 3000, 0.35)
    dry = thump + scrape * swell * 0.9 + grit * 0.5 + cloth
    wet = reverb(dry, rt=0.32, mix=0.18, hi=3200, seed=seed + 100, predelay=0.008)
    return finish(wet, -4.0, drive=1.2, fade=0.12)


FILES = {"slide_1": lambda: slide(71, 1.00), "slide_2": lambda: slide(72, 0.95, 0.82), "slide_3": lambda: slide(73, 1.05, 0.74)}
EVENTS = {"movement.slide": (["slide_1", "slide_2", "slide_3"], "subtitles.blockland.movement.slide")}


def encode_files(path):
    tmp = tempfile.mkdtemp(prefix="blockland_slide_snd_")
    for name, fn in FILES.items():
        encode(fn(), path("sounds", "movement", f"{name}.ogg"), tmp)


def build(path):
    encode_files(path)
    merge_sounds_json(path("sounds.json"), {ev: {"sounds": [f"blockland:movement/{f}" for f in files], "subtitle": sub}
                                            for ev, (files, sub) in EVENTS.items()})
    print(f"slide sounds: {len(FILES)} files, events {', '.join(EVENTS)}")


if __name__ == "__main__":
    import os
    import sys

    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources", "assets", "blockland")

    def path(*parts):
        full = os.path.join(root, *parts)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        return full

    # --files-only: write the .ogg files and leave sounds.json alone (it is edited by hand while others work on it)
    if "--files-only" in sys.argv:
        encode_files(path)
        print(f"slide sounds: {len(FILES)} files")
    else:
        build(path)
