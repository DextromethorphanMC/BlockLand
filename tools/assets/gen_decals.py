"""Damage decal strips (VIEWMODEL_SPEC section 14): textures/decal/<type>.png, frames laid out horizontally,
RGBA straight alpha, colours as seen in daylight, every frame's alpha fading to 0 at its edges.
The frame's X axis is the cut direction."""
import numpy as np

import textures as tx


def _grid(w, h):
    y, x = np.mgrid[0:h, 0:w] + 0.5
    return x, y


def _edge_window(w, h, px=3.0):
    """1 inside, smoothly 0 on the outermost pixel ring of the frame."""
    xi, yi = np.arange(w), np.arange(h)
    dx = np.minimum(xi, w - 1 - xi) / px
    dy = np.minimum(yi, h - 1 - yi) / px
    s = lambda d: np.clip(d, 0, 1) ** 2 * (3 - 2 * np.clip(d, 0, 1))
    return s(dy)[:, None] * s(dx)[None, :]


def _finish(rgb, a):
    h, w = a.shape
    a = np.clip(a, 0, 1) * _edge_window(w, h)
    return np.dstack([np.clip(rgb, 0, 1), a])


def bullet_hole(seed):
    rng = np.random.default_rng(seed)
    x, y = _grid(32, 32)
    cx, cy = 16 + rng.uniform(-0.8, 0.8), 16 + rng.uniform(-0.8, 0.8)
    r = np.hypot(x - cx, y - cy)
    ang = np.arctan2(y - cy, x - cx)
    jag = 1 + 0.16 * np.sin(5 * ang + rng.uniform(0, 6)) + 0.09 * np.sin(11 * ang + rng.uniform(0, 6))
    noise = tx.tile_noise(32, 1.5, rng)
    hole = r < 3.4 * jag
    chips = (r < (6.0 + 1.6 * noise) * jag) & ~hole
    rgb = np.zeros((32, 32, 3))
    a = np.zeros((32, 32))
    ring_t = np.clip(1 - np.abs(r - 9.0) / 5.5, 0, 1)                    # faint dust / scorch ring
    rgb[:] = (0.20, 0.18, 0.16)
    a += 0.30 * ring_t * (0.6 + 0.4 * noise)
    chip_c = 0.42 + 0.25 * noise[..., None]
    rgb[chips] = (chip_c * np.array([1.0, 0.96, 0.90]))[chips]
    a[chips] = np.maximum(a[chips], 0.85)
    rim = (r < 4.4 * jag) & ~hole
    rgb[rim] = (0.12, 0.11, 0.10)
    a[rim] = 0.95
    rgb[hole] = (0.035, 0.03, 0.03)
    rgb[hole & (r < 2.0)] = (0.01, 0.01, 0.01)
    a[hole] = 1.0
    return _finish(rgb, a)


def _gouge_profile(x, w, x0, x1, width):
    """Tapered half-width along X (0 at the tips)."""
    u = np.clip((x - x0) / (x1 - x0), 0, 1)
    return width * np.sin(np.pi * u) ** 0.7 * (u > 0) * (u < 1)


def knife_slash(seed):
    rng = np.random.default_rng(seed)
    w, h = 128, 32
    x, y = _grid(w, h)
    x0, x1 = 6 + rng.uniform(0, 6), 122 - rng.uniform(0, 6)
    bend = rng.uniform(-3.0, 3.0)
    yc = 16 + bend * np.sin(np.pi * np.clip((x - x0) / (x1 - x0), 0, 1)) + rng.uniform(-1, 1)
    hw = _gouge_profile(x, w, x0, x1, rng.uniform(1.8, 2.4))
    d = y - yc
    inside = np.abs(d) < hw
    rgb = np.zeros((h, w, 3))
    a = np.zeros((h, w))
    halo = np.clip(1 - np.abs(d) / (hw + 4.0), 0, 1) * (hw > 0)
    rgb[:] = (0.30, 0.28, 0.26)
    a += 0.35 * halo
    depth = 1 - np.abs(d) / np.maximum(hw, 1e-6)
    rgb[inside] = (np.array([0.10, 0.09, 0.085]) + 0.06 * (1 - depth[..., None]))[inside]
    a[inside] = 1.0
    lip = 0.3 + 0.9 * hw / max(hw.max(), 1e-6)
    scrape = (d > hw - 0.2) & (d < hw + lip) & (hw > 0.5)            # bright scraped lip on one side
    rgb[scrape] = (0.86, 0.85, 0.82)
    a[scrape] = 0.95
    burr = (d < -hw + 0.1) & (d > -hw - 0.6 * lip) & (hw > 0.5)
    rgb[burr] = (0.55, 0.53, 0.50)
    a[burr] = np.maximum(a[burr], 0.75)
    return _finish(rgb, a)


def knife_stab(seed):
    rng = np.random.default_rng(seed)
    x, y = _grid(32, 32)
    cx, cy = 16 + rng.uniform(-1, 1), 16 + rng.uniform(-0.6, 0.6)
    e = np.hypot((x - cx) / 4.2, (y - cy) / 1.8)
    tear_len = rng.uniform(6.0, 8.5)
    side = rng.choice([-1, 1])
    tx_ = (x - cx) * side
    tear = (tx_ > 3.2) & (tx_ < 3.2 + tear_len) & (np.abs(y - cy) < 0.75 * (1 - (tx_ - 3.2) / tear_len) + 0.15)
    crush = np.hypot((x - cx) / 7.5, (y - cy) / 4.2)
    rgb = np.zeros((32, 32, 3))
    a = np.zeros((32, 32))
    rgb[:] = (0.24, 0.22, 0.20)
    a += 0.55 * np.clip(1 - crush, 0, 1)
    lip = (e < 1.45) & (e >= 1.0)
    rgb[lip] = (0.62, 0.60, 0.56)
    a[lip] = 0.9
    rgb[tear] = (0.06, 0.055, 0.05)
    a[tear] = 0.95
    hole = e < 1.0
    rgb[hole] = (0.02, 0.02, 0.02)
    a[hole] = 1.0
    return _finish(rgb, a)


def knife_cut(seed):
    rng = np.random.default_rng(seed)
    w, h = 128, 32
    x, y = _grid(w, h)
    x0, x1 = 10 + rng.uniform(0, 8), 118 - rng.uniform(0, 8)
    yc = 15 + rng.uniform(-1.5, 1.5) + rng.uniform(-2, 2) * np.sin(np.pi * np.clip((x - x0) / (x1 - x0), 0, 1))
    hw = _gouge_profile(x, w, x0, x1, rng.uniform(1.4, 1.9))
    d = y - yc
    rgb = np.zeros((h, w, 3))
    a = np.zeros((h, w))
    bruise = np.clip(1 - np.abs(d) / (hw + 3.0), 0, 1) * (hw > 0)
    rgb[:] = (0.45, 0.06, 0.05)
    a += 0.30 * bruise
    edge = (np.abs(d) < hw + 0.6 * hw / max(hw.max(), 1e-6)) & (hw > 0.15)
    rgb[edge] = (0.62, 0.07, 0.06)
    a[edge] = np.maximum(a[edge], 0.9)
    core = np.abs(d) < hw * 0.6
    rgb[core] = (0.22, 0.01, 0.01)
    a[core] = 1.0
    for _ in range(int(rng.integers(4, 8))):                            # droplets
        px, py = rng.uniform(x0 + 8, x1 - 8), 0
        k = int(np.clip(px, 0, w - 1))
        py = yc[0, k] + rng.choice([-1, 1]) * rng.uniform(3.0, 8.0)
        rr = rng.uniform(0.7, 1.6)
        drop = np.hypot(x - px, (y - py) * 0.9) < rr
        rgb[drop] = (0.50, 0.03, 0.03)
        a[drop] = np.maximum(a[drop], 0.95)
    return _finish(rgb, a)


DECALS = {"bullet_hole": (bullet_hole, 4), "knife_slash": (knife_slash, 2), "knife_stab": (knife_stab, 2),
          "knife_cut": (knife_cut, 2)}


def build(path):
    for name, (fn, variants) in DECALS.items():
        strip = np.concatenate([fn(1000 + 17 * k + len(name)) for k in range(variants)], axis=1)
        tx.save_png(strip, path("textures", "decal", f"{name}.png"))
    print(f"decals: {', '.join(f'{n} x{v}' for n, (_, v) in DECALS.items())}")
