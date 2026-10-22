"""Effect sprites (VIEWMODEL_SPEC section 9) and tileable gun skin patterns."""
import numpy as np

import textures as tx


def _grid(w, h):
    y, x = np.mgrid[0:h, 0:w] + 0.5
    return x, y


def _ramp(t, stops):
    """Piecewise-linear colour ramp; stops = [(t, (r, g, b)), ...] with t ascending."""
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    ts = [s[0] for s in stops]
    for c in range(3):
        out[..., c] = np.interp(t, ts, [s[1][c] for s in stops])
    return out


FLAME = [(0.0, (0.95, 0.35, 0.08)), (0.45, (1.0, 0.62, 0.20)), (0.75, (1.0, 0.86, 0.55)), (1.0, (1.0, 1.0, 0.96))]


def muzzle_flash(size=64, seed=21):
    rng = np.random.default_rng(seed)
    x, y = _grid(size, size)
    cx = cy = size / 2
    r = np.hypot(x - cx, y - cy) / (size / 2)
    a = np.arctan2(y - cy, x - cx)
    rays = np.zeros_like(r)
    for k, (n, length, width) in enumerate(((5, 0.98, 0.30), (5, 0.66, 0.26), (9, 0.46, 0.16))):
        phase = rng.uniform(0, 2 * np.pi)
        lens = length * rng.uniform(0.80, 1.0, n)
        for i in range(n):
            ang = phase + 2 * np.pi * i / n + rng.uniform(-0.12, 0.12)
            d = np.angle(np.exp(1j * (a - ang)))
            ray = np.exp(-(d / (width * (1.1 - r))) ** 2) * np.clip(1 - r / lens[i], 0, 1) ** 1.3
            rays = np.maximum(rays, ray)
    core = np.exp(-(r / 0.27) ** 2)
    noise = tx.tile_noise(size, 3.0, rng)
    heat = np.clip(np.maximum(core * 1.1, rays * (0.75 + 0.35 * noise)), 0, 1)
    rgb = _ramp(heat, FLAME)
    alpha = np.clip(heat * 1.25, 0, 1) ** 0.9
    alpha *= np.clip(1.0 - r, 0, 1) ** 0.25
    return np.dstack([rgb, alpha])


def muzzle_flash_side(w=64, h=32, seed=22):
    rng = np.random.default_rng(seed)
    x, y = _grid(w, h)
    u = x / w                                     # 0 at the muzzle (left edge)
    v = (y - h / 2) / (h / 2)
    width = 0.18 + 0.80 * np.sqrt(u) * (1 - 0.55 * u)
    lobes = 1 + 0.25 * np.cos(2 * np.pi * (u * 3.2)) * (1 - u)
    shape = np.clip(1 - (np.abs(v) / (width * lobes)) ** 2, 0, 1)
    fall = np.clip(1 - u, 0, 1) ** 0.9
    noise = np.random.default_rng(seed).random((h, w))
    noise = 0.5 * noise + 0.5 * np.roll(noise, 1, 1)
    heat = shape * fall * (0.8 + 0.3 * noise)
    heat += 0.9 * np.exp(-((u / 0.10) ** 2 + (v / 0.30) ** 2))   # hot plug at the muzzle
    heat = np.clip(heat, 0, 1)
    rgb = _ramp(heat, FLAME)
    alpha = np.clip(heat * 1.3, 0, 1)
    alpha[:, 0] *= 0.85
    return np.dstack([rgb, alpha])


def glow(size=32):
    x, y = _grid(size, size)
    r = np.hypot(x - size / 2, y - size / 2) / (size / 2)
    a = np.clip(1 - r, 0, 1) ** 2.2
    return np.dstack([np.ones((size, size, 3)), a])


def bullet_hole(size=16, seed=23):
    rng = np.random.default_rng(seed)
    x, y = _grid(size, size)
    r = np.hypot(x - size / 2, y - size / 2)
    a = np.arctan2(y - size / 2, x - size / 2)
    jag = 1 + 0.18 * np.sin(5 * a + 1.3) + 0.10 * np.sin(11 * a)
    hole = r < 2.3 * jag
    ring = (r < 4.6 * jag) & ~hole
    crack = np.zeros_like(r, dtype=bool)
    for ang in rng.uniform(0, 2 * np.pi, 5):
        d = np.abs(np.angle(np.exp(1j * (a - ang))))
        crack |= (d < 0.16) & (r < rng.uniform(5.5, 7.5)) & ~hole
    rgba = np.zeros((size, size, 4))
    rgba[ring | crack] = (0.20, 0.19, 0.18, 0.80)
    rgba[ring & (r > 3.6 * jag)] = (0.33, 0.31, 0.29, 0.55)
    rgba[hole] = (0.03, 0.03, 0.03, 1.0)
    rgba[hole & (r > 1.6 * jag)] = (0.08, 0.075, 0.07, 1.0)
    return rgba


def reticle_dot(size=16):
    return tx.dot(size, radius=3.2, soft=0.8)


def smoke(size=32, seed=24):
    rng = np.random.default_rng(seed)
    x, y = _grid(size, size)
    r = np.hypot(x - size / 2, y - size / 2) / (size / 2)
    n = 0.6 * tx.tile_noise(size, 4.0, rng) + 0.4 * tx.tile_noise(size, 1.8, rng)
    a = np.clip(1 - r * (0.85 + 0.35 * n), 0, 1) ** 1.2 * (0.70 + 0.45 * n)
    shade = 0.78 + 0.18 * n - 0.08 * (y / size)
    return np.dstack([np.repeat(shade[:, :, None], 3, 2), np.clip(a, 0, 1)])


def build(path):
    out = {
        ("textures", "effect", "muzzle_flash.png"): muzzle_flash(),
        ("textures", "effect", "muzzle_flash_side.png"): muzzle_flash_side(),
        ("textures", "effect", "glow.png"): glow(),
        ("textures", "effect", "bullet_hole.png"): bullet_hole(),
        ("textures", "effect", "reticle_dot.png"): reticle_dot(),
        ("textures", "effect", "smoke.png"): smoke(),
        ("textures", "gun", "camo_digital.png"): tx.camo_digital(128),
    }
    for parts, img in out.items():
        tx.save_png(img, path(*parts))
    print(f"fx: wrote {len(out)} textures")
