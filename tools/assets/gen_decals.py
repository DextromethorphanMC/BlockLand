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


def bullet_wound(seed):
    """An entry wound on a body: a dark hole, a ring of bruised, torn skin, blood smeared round it (no run: body decals
    lie at any rotation)."""
    rng = np.random.default_rng(seed)
    x, y = _grid(64, 64)
    cx, cy = 32 + rng.uniform(-1.5, 1.5), 32 + rng.uniform(-1.5, 1.5)
    ang = np.arctan2(y - cy, x - cx)
    tilt = rng.uniform(0, np.pi)
    u, v = (x - cx) * np.cos(tilt) + (y - cy) * np.sin(tilt), -(x - cx) * np.sin(tilt) + (y - cy) * np.cos(tilt)
    r = np.hypot(u, v / 0.85) / 32
    coarse, fine = tx.tile_noise(64, 6.0, rng), tx.tile_noise(64, 1.5, rng)
    edge = r * (1 + 0.1 * np.sin(5 * ang + rng.uniform(0, 6))) + (coarse - 0.5) * 0.25 + (fine - 0.5) * 0.08
    hole = np.clip((0.16 - edge) / 0.04, 0, 1)
    ring = np.clip((0.42 - edge) / 0.08, 0, 1)
    smear = np.clip((0.62 - edge) / 0.2, 0, 1) * (0.35 + 0.65 * fine)
    rgb = np.zeros((64, 64, 3))
    rgb[:] = (0.50, 0.04, 0.04)
    rgb *= (0.8 + 0.25 * fine)[..., None]
    rgb = rgb * (1 - ring[..., None]) + np.array([0.36, 0.05, 0.05]) * ring[..., None]
    rgb = rgb * (1 - hole[..., None]) + np.array([0.10, 0.015, 0.015]) * hole[..., None]
    a = np.maximum.reduce([hole, ring * 0.95, smear * 0.6])
    return _finish(rgb, a)


def blunt_dent(seed):
    """A blunt blow on a block (crowbar, VIEWMODEL_SPEC section 41): a crushed, darker hollow, a pale ring of powdered
    surface round it, cracks running out of the rim and a few chips knocked off."""
    rng = np.random.default_rng(seed)
    x, y = _grid(64, 64)
    cx, cy = 32 + rng.uniform(-1.5, 1.5), 32 + rng.uniform(-1.5, 1.5)
    r = np.hypot((x - cx) / 1.25, y - cy)                                 # the head lands a little elongated
    ang = np.arctan2(y - cy, x - cx)
    noise = tx.tile_noise(64, 3.0, rng)
    fine = tx.tile_noise(64, 1.2, rng)
    jag = 1 + 0.12 * np.sin(4 * ang + rng.uniform(0, 6)) + 0.08 * (noise - 0.5)
    rgb = np.zeros((64, 64, 3))
    a = np.zeros((64, 64))
    rgb[:] = (0.62, 0.60, 0.56)                                           # powdered surface
    ring_t = np.clip(1 - np.abs(r - 11.0 * jag) / 6.0, 0, 1)
    a += 0.55 * ring_t * (0.5 + 0.5 * fine)
    hollow = np.clip((9.0 * jag - r) / 3.0, 0, 1)
    shade = 0.16 + 0.10 * noise + 0.05 * fine + 0.08 * np.clip((x - cx) / 12, -1, 1)   # one wall lit, one shaded
    rgb = rgb * (1 - hollow[..., None]) + np.stack([shade, shade * 0.96, shade * 0.9], -1) * hollow[..., None]
    a = np.maximum(a, 0.92 * hollow)
    for _ in range(int(rng.integers(5, 9))):                              # cracks out of the rim
        th = rng.uniform(0, 2 * np.pi)
        r0, length = 8.0, rng.uniform(8.0, 20.0)
        wob = rng.uniform(-0.25, 0.25)
        u = (x - cx) * np.cos(th) + (y - cy) * np.sin(th)
        v = -(x - cx) * np.sin(th) + (y - cy) * np.cos(th) - wob * np.clip(u - r0, 0, None) ** 1.3 * 0.15
        w = 0.7 * (1 - np.clip((u - r0) / length, 0, 1))
        crack = (u > r0) & (u < r0 + length) & (np.abs(v) < w + 0.15)
        rgb[crack] = (0.08, 0.075, 0.07)
        a[crack] = np.maximum(a[crack], 0.9)
    for _ in range(int(rng.integers(3, 6))):                              # chips knocked out at the rim
        th, rr = rng.uniform(0, 2 * np.pi), rng.uniform(9.0, 13.0)
        px, py = cx + 1.25 * rr * np.cos(th), cy + rr * np.sin(th)
        chip = np.hypot(x - px, y - py) < rng.uniform(1.0, 2.2)
        rgb[chip] = (0.22, 0.21, 0.20)
        a[chip] = np.maximum(a[chip], 0.95)
    return _finish(rgb, a)


def punch_mark(seed):
    """A knuckle-duster blow on a block (VIEWMODEL_SPEC section 42): four small scuffed dents in a row (the four
    knuckle bosses; the frame's X axis runs along the row) in a faint dusty print."""
    rng = np.random.default_rng(seed)
    x, y = _grid(32, 32)
    rgb = np.zeros((32, 32, 3))
    a = np.zeros((32, 32))
    rgb[:] = (0.55, 0.53, 0.50)
    print_ = np.hypot((x - 16) / 13.0, (y - 16) / 6.5)
    a += 0.30 * np.clip(1 - print_, 0, 1)
    cy = 16 + rng.uniform(-0.6, 0.6)
    for k in range(4):
        px = 16 + (k - 1.5) * 6.2 + rng.uniform(-0.5, 0.5)
        py = cy + 0.9 * np.sin(np.pi * k / 3) + rng.uniform(-0.4, 0.4)    # the knuckle line is slightly arched
        e = np.hypot((x - px) / 2.4, (y - py) / 2.0) * rng.uniform(0.9, 1.1)
        dent = e < 1.0
        rim = (e >= 1.0) & (e < 1.4)
        rgb[rim] = (0.70, 0.68, 0.64)
        a[rim] = np.maximum(a[rim], 0.75)
        rgb[dent] = (0.20, 0.19, 0.18)
        a[dent] = 0.95
    return _finish(rgb, a)


def bruise(seed):
    """A blunt hit on a body (crowbar, knuckles): a purple-red blotch, darker and redder in the middle, mottled, with a
    graze of broken skin in the centre (no cut, little blood)."""
    rng = np.random.default_rng(seed)
    x, y = _grid(64, 64)
    cx, cy = 32 + rng.uniform(-2, 2), 32 + rng.uniform(-2, 2)
    tilt = rng.uniform(0, np.pi)
    u, v = (x - cx) * np.cos(tilt) + (y - cy) * np.sin(tilt), -(x - cx) * np.sin(tilt) + (y - cy) * np.cos(tilt)
    coarse, fine = tx.tile_noise(64, 6.0, rng), tx.tile_noise(64, 1.5, rng)
    r = np.hypot(u / 1.2, v) / 28 + (coarse - 0.5) * 0.3
    body = np.clip((1.0 - r) / 0.45, 0, 1)
    core = np.clip((0.45 - r) / 0.25, 0, 1)
    rgb = np.zeros((64, 64, 3))
    rgb[:] = (0.30, 0.10, 0.22)                                           # blue-purple outer bruise
    rgb = rgb * (1 - core[..., None]) + np.array([0.42, 0.06, 0.08]) * core[..., None]
    rgb *= (0.85 + 0.25 * fine)[..., None]
    graze = (np.hypot(u / 1.6, v) < 5.5 + 2.0 * (fine - 0.5)) & (fine > 0.45)
    rgb[graze] = (0.55, 0.08, 0.07)
    a = 0.55 * body * (0.6 + 0.4 * fine)
    a[graze] = 0.9
    return _finish(rgb, a)


DECALS = {"bullet_hole": (bullet_hole, 4), "knife_slash": (knife_slash, 2), "knife_stab": (knife_stab, 2),
          "knife_cut": (knife_cut, 2), "bullet_wound": (bullet_wound, 4), "blunt_dent": (blunt_dent, 2),
          "punch_mark": (punch_mark, 2), "bruise": (bruise, 2)}


def build(path):
    for name, (fn, variants) in DECALS.items():
        strip = np.concatenate([fn(1000 + 17 * k + len(name)) for k in range(variants)], axis=1)
        tx.save_png(strip, path("textures", "decal", f"{name}.png"))
    print(f"decals: {', '.join(f'{n} x{v}' for n, (_, v) in DECALS.items())}")
