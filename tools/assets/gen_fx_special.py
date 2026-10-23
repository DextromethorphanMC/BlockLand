"""Muzzle-flash sprites of special skins (VIEWMODEL_SPEC sections 9, 26 and 30), in the layout of gen_fx's shared ones:
a 64x64 front-on burst and a 64x32 side-on cone (origin at the left edge), RGBA with soft alpha.

* `muzzle_flash_cerberus.png` / `muzzle_flash_side_cerberus.png` (dual USP skin 地狱犬): a crimson flame with a
  white-hot core; front on it opens in three long prongs (the three heads), side on in three licking tongues."""
import numpy as np

import textures as tx
from gen_fx import _grid, _ramp

# deep crimson rim -> red -> salmon -> white-hot core
CERBERUS = [(0.0, (0.42, 0.01, 0.03)), (0.35, (0.82, 0.05, 0.05)), (0.62, (1.0, 0.24, 0.16)),
            (0.85, (1.0, 0.66, 0.56)), (1.0, (1.0, 0.97, 0.94))]


def cerberus_flash(size=64, seed=31):
    rng = np.random.default_rng(seed)
    x, y = _grid(size, size)
    c = size / 2
    r = np.hypot(x - c, y - c) / c
    a = np.arctan2(y - c, x - c)
    rays = np.zeros_like(r)
    # three long prongs 120 degrees apart, each with a forked tip; short flickering rays between them
    turn = rng.uniform(0, 2 * np.pi)
    for i in range(3):
        ang = turn + 2 * np.pi * i / 3
        d = np.angle(np.exp(1j * (a - ang)))
        bend = 0.10 * r * np.sin(6 * r + i)                       # a slight curl along the prong
        prong = np.exp(-((d - bend) / (0.42 * (1.15 - r))) ** 2) * np.clip(1 - r / 0.98, 0, 1) ** 0.8
        for side in (-1, 1):                                       # the tip forks in two
            spread = side * 0.30 * np.clip((r - 0.45) / 0.5, 0, 1)
            fork = (np.exp(-((d - spread - bend) / 0.11) ** 2) * np.clip((r - 0.4) / 0.2, 0, 1)
                    * np.clip(1 - r / 0.97, 0, 1) ** 0.6)
            prong = np.maximum(prong, 0.9 * fork)
        rays = np.maximum(rays, prong)
    for i in range(9):
        ang = turn + np.pi / 3 + 2 * np.pi * i / 9 + rng.uniform(-0.15, 0.15)
        d = np.angle(np.exp(1j * (a - ang)))
        length = rng.uniform(0.32, 0.5)
        rays = np.maximum(rays, 0.7 * np.exp(-(d / (0.18 * (1.1 - r))) ** 2) * np.clip(1 - r / length, 0, 1) ** 1.3)
    core = np.exp(-(r / 0.24) ** 2)
    noise = tx.tile_noise(size, 3.0, rng)
    heat = np.clip(np.maximum(core * 1.15, rays * (0.85 + 0.35 * noise)), 0, 1)
    rgb = _ramp(heat, CERBERUS)
    alpha = np.clip(heat * 1.3, 0, 1) ** 0.85
    alpha *= np.clip(1.0 - r, 0, 1) ** 0.25
    return np.dstack([rgb, alpha])


def cerberus_flash_side(w=64, h=32, seed=32):
    rng = np.random.default_rng(seed)
    x, y = _grid(w, h)
    u = x / w                                     # 0 at the muzzle (left edge)
    v = (y - h / 2) / (h / 2)
    heat = np.zeros_like(u)
    # three tongues: the middle one long and straight, the outer ones shorter and licking outwards
    for centre, spread, length, amp in ((0.0, 0.36, 1.0, 1.0), (-0.45, 0.30, 0.74, 0.9), (0.45, 0.30, 0.74, 0.9)):
        path = centre * np.sqrt(np.clip(u / length, 0, 1)) + 0.05 * np.sin(9 * u + 3 * centre)
        width = spread * (0.35 + 0.9 * np.sqrt(np.clip(u / length, 0, 1))) * (1 - 0.6 * np.clip(u / length, 0, 1))
        tongue = np.clip(1 - ((v - path) / np.maximum(width, 1e-3)) ** 2, 0, 1) * np.clip(1 - u / length, 0, 1) ** 0.8
        heat = np.maximum(heat, amp * tongue)
    noise = rng.random((h, w))
    noise = 0.5 * noise + 0.5 * np.roll(noise, 1, 1)
    heat = heat * (0.78 + 0.32 * noise)
    heat += 0.95 * np.exp(-((u / 0.11) ** 2 + (v / 0.32) ** 2))  # hot plug at the muzzle
    heat = np.clip(heat, 0, 1)
    rgb = _ramp(heat, CERBERUS)
    alpha = np.clip(heat * 1.35, 0, 1)
    alpha[:, 0] *= 0.85
    return np.dstack([rgb, alpha])


def build(path):
    out = {
        ("textures", "effect", "muzzle_flash_cerberus.png"): cerberus_flash(),
        ("textures", "effect", "muzzle_flash_side_cerberus.png"): cerberus_flash_side(),
    }
    for parts, img in out.items():
        tx.save_png(img, path(*parts))
    print(f"fx_special: wrote {len(out)} textures")
