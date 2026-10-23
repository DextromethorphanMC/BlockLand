"""Tileable knife skin patterns (VIEWMODEL_SPEC section 19) -> textures/knife/<pattern>.png.

Knife meshes are box-projected like the guns (1 UV = 0.05 m), so a skin in knives.json can put any of these on a
material (`textures`: {material: "textures/knife/<pattern>.png"}). Linear colour is multiplied by the material."""
import numpy as np

import textures as tx

SIZE = 256


def damascus(size=SIZE, seed=21):
    """Folded steel: wavy light / dark layers, warped by smooth noise, with a faint etch grain."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / size
    warp = 0.22 * (tx.tile_noise(size, 28.0, rng) - 0.5) + 0.08 * (tx.tile_noise(size, 9.0, rng) - 0.5)
    layers = 0.5 + 0.5 * np.sin(2 * np.pi * (7 * y + 2 * np.sin(2 * np.pi * x) * 0.15 + warp * 3.0))
    layers = layers ** 1.6
    grain = tx.tile_noise(size, 1.2, rng)
    v = 0.48 + 0.40 * layers + 0.06 * (grain - 0.5)
    rgb = np.stack([v * 0.97, v * 0.99, v * 1.03], -1)
    return np.clip(rgb, 0, 1)


def fade(size=SIZE, seed=22):
    """Anodised fade: gold -> magenta -> violet -> blue along U (mirrored so it tiles), slightly mottled."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / size
    stops = np.array([(0.95, 0.78, 0.30), (0.93, 0.36, 0.62), (0.55, 0.30, 0.85), (0.25, 0.45, 0.95)])
    u = 0.5 - 0.5 * np.cos(2 * np.pi * (x + 0.06 * (tx.tile_noise(size, 40.0, rng) - 0.5)))
    pos = u * (len(stops) - 1)
    i = np.clip(np.floor(pos).astype(int), 0, len(stops) - 2)
    f = (pos - i)[..., None]
    f = f * f * (3 - 2 * f)
    rgb = stops[i] * (1 - f) + stops[i + 1] * f
    rgb *= (0.94 + 0.10 * tx.tile_noise(size, 6.0, rng))[..., None]
    return np.clip(rgb, 0, 1)


def tiger(size=SIZE, seed=23):
    """Tiger tooth: amber with dark brush strokes running diagonally."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:size, 0:size] / size
    n = tx.tile_noise(size, 18.0, rng)
    stripes = np.sin(2 * np.pi * (3 * (x + y) + 1.6 * (n - 0.5)))
    dark = np.clip((stripes - 0.35) * 2.5, 0, 1)
    base = np.array([0.92, 0.62, 0.18]) * (0.9 + 0.15 * tx.tile_noise(size, 3.0, rng))[..., None]
    rgb = base * (1 - 0.85 * dark[..., None])
    return np.clip(rgb, 0, 1)


PATTERNS = {"damascus": damascus, "fade": fade, "tiger": tiger}


def build(path):
    for name, fn in PATTERNS.items():
        tx.save_png(fn(), path("textures", "knife", f"{name}.png"))
    print(f"knife patterns: {', '.join(f'textures/knife/{n}.png' for n in PATTERNS)}")
