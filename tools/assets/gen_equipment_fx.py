"""Textures of what equipment does when it goes off (client/equipment/effect): smoke puffs, flame flipbook, fireball,
dust ring, the scorch decal, the adrenaline screen fallback (speed lines, vignette) and the two mob effect icons.

python tools/assets/gen_equipment_fx.py      (deterministic: the same files every run)
"""
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "textures")
OUT = os.path.join(TEX, "effect", "equipment")


def value_noise(w, h, cell, rng):
    """Smooth value noise in 0..1, one lattice point every `cell` pixels."""
    gw, gh = w // cell + 2, h // cell + 2
    grid = rng.random((gh, gw))
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    gx, gy = x / cell, y / cell
    x0, y0 = np.floor(gx).astype(int), np.floor(gy).astype(int)
    fx, fy = gx - x0, gy - y0
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = grid[y0, x0], grid[y0, x0 + 1]
    c, d = grid[y0 + 1, x0], grid[y0 + 1, x0 + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(w, h, cell, octaves, rng):
    total, amp, norm = np.zeros((h, w)), 1.0, 0.0
    for _ in range(octaves):
        total += value_noise(w, h, max(1, cell), rng) * amp
        norm += amp
        amp *= 0.5
        cell //= 2
    return total / norm


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def radial(w, h, cx=0.5, cy=0.5, sx=1.0, sy=1.0):
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    return np.sqrt(((x + 0.5) / w - cx) ** 2 / (0.25 * sx * sx) + ((y + 0.5) / h - cy) ** 2 / (0.25 * sy * sy))


def save(rgba, *path):
    full = os.path.join(*path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    Image.fromarray(np.clip(rgba * 255 + 0.5, 0, 255).astype(np.uint8), "RGBA").save(full, optimize=True)
    print("wrote", os.path.relpath(full, TEX))


def smoke_puffs(rng):
    """2×2 atlas of soft, billowy puffs (white; the renderer tints and lights them). Lit from above: the lower half
    a little darker, so a cloud of them reads as volume."""
    size = 128
    atlas = np.zeros((size * 2, size * 2, 4))
    for i in range(4):
        n = fbm(size, size, 32, 5, rng)
        lumps = fbm(size, size, 16, 3, rng)
        r = radial(size, size) + (n - 0.5) * 0.55 + (lumps - 0.5) * 0.25
        alpha = smoothstep(1.0, 0.35, r) * (0.75 + 0.25 * n)
        y = np.linspace(0, 1, size)[:, None]
        shade = 0.78 + 0.22 * (1 - y) + (n - 0.5) * 0.18
        rgb = np.repeat(np.clip(shade, 0, 1)[..., None], 3, axis=2)
        tile = np.dstack([rgb, alpha])
        ox, oy = (i % 2) * size, (i // 2) * size
        atlas[oy:oy + size, ox:ox + size] = tile
    save(atlas, OUT, "smoke_puff.png")


def flames(rng):
    """8-frame flame flipbook (64×128 each, side by side): white-hot core, orange body, a ragged dark-red tip; alpha
    is the flame shape (the renderer draws them bright, ignoring the light)."""
    fw, fh, frames = 64, 128, 8
    strip = np.zeros((fh, fw * frames, 4))
    big = fbm(fw, fh * frames * 2, 16, 4, rng)
    y, x = np.mgrid[0:fh, 0:fw].astype(np.float64)
    u = (x + 0.5) / fw - 0.5
    v = 1 - (y + 0.5) / fh                     # 0 at the base, 1 at the top
    for f in range(frames):
        off = (frames - f) * fh // 2            # the noise scrolls upward through the frames
        n = big[off:off + fh]
        width = 0.34 * (1 - v) ** 0.65 + 0.03
        wobble = (n - 0.5) * 0.7 * v + np.sin(v * 9 + f * 0.785) * 0.05 * v
        tongues = 0.75 + 0.5 * np.abs(np.sin((u + wobble) * 14 + n * 4))     # licks split the upper flame
        shape = smoothstep(width, width * 0.25, np.abs(u + wobble))
        shape *= smoothstep(1.0, 0.45, v * tongues + (n - 0.5) * 0.7)
        shape *= smoothstep(0.0, 0.08, v)
        heat = np.clip(shape * (1.05 - v * 1.0) + (n - 0.5) * 0.25, 0, 1)
        r = np.clip(0.55 + heat * 0.9, 0, 1)
        g = np.clip(heat ** 1.3 * 1.05, 0, 1)
        b = np.clip(heat ** 3.2 * 0.85, 0, 1)
        a = np.clip(shape * 1.2, 0, 1)
        strip[:, f * fw:(f + 1) * fw] = np.dstack([r, g, b, a])
    save(strip, OUT, "flame.png")


def fireballs(rng):
    """2×2 atlas of fireball blobs: hot centre, turbulent edge (colour shifts are the renderer's vertex colour)."""
    size = 128
    atlas = np.zeros((size * 2, size * 2, 4))
    for i in range(4):
        n = fbm(size, size, 24, 5, rng)
        r = radial(size, size) + (n - 0.5) * 0.7
        heat = smoothstep(1.0, 0.0, r)
        a = smoothstep(1.0, 0.55, r)
        rgb = np.dstack([np.clip(0.8 + heat * 0.4, 0, 1), np.clip(0.35 + heat * 0.75, 0, 1), np.clip(heat ** 2 * 0.8, 0, 1)])
        ox, oy = (i % 2) * size, (i // 2) * size
        atlas[oy:oy + size, ox:ox + size] = np.dstack([rgb, a])
    save(atlas, OUT, "fireball.png")


def dust_ring(rng):
    size = 256
    n = fbm(size, size, 32, 4, rng)
    r = radial(size, size) + (n - 0.5) * 0.18
    a = smoothstep(0.55, 0.85, r) * smoothstep(1.0, 0.86, r) * (0.6 + 0.4 * n)
    rgb = np.dstack([np.full((size, size), 0.72), np.full((size, size), 0.68), np.full((size, size), 0.62)])
    save(np.dstack([rgb, a]), OUT, "dust_ring.png")


def scorch(rng):
    """Blast mark decal (one variant): soot spokes around a dark centre."""
    size = 128
    n = fbm(size, size, 16, 5, rng)
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    ang = np.arctan2(y - size / 2, x - size / 2)
    spokes = 0.5 + 0.5 * np.sin(ang * 9 + n * 6)
    r = radial(size, size) * (0.85 + 0.3 * spokes * 0.5) + (n - 0.5) * 0.35
    a = smoothstep(1.0, 0.25, r) * (0.65 + 0.35 * n)
    rgb = np.dstack([0.06 + n * 0.06, 0.05 + n * 0.05, 0.045 + n * 0.04])
    save(np.dstack([rgb, np.clip(a, 0, 0.95)]), TEX, "decal", "scorch.png")


def speed_lines(rng):
    """Shader-pack fallback of the adrenaline rush: thin radial streaks, clear in the middle (white, tinted by the blit)."""
    size = 512
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    cx, cy = x + 0.5 - size / 2, y + 0.5 - size / 2
    ang = (np.arctan2(cy, cx) / (2 * np.pi) + 0.5) * 160
    cell = np.floor(ang).astype(int) % 160
    seeds = rng.random(160)
    starts = 0.45 + rng.random(160) * 0.35
    r = np.sqrt(cx * cx + cy * cy) / (size / 2)
    line = smoothstep(0.5, 0.0, np.abs(np.mod(ang, 1) - 0.5) * 2 / 0.25)
    on = seeds[cell] > 0.55
    a = line * on * smoothstep(starts[cell], starts[cell] + 0.25, r) * (0.4 + 0.6 * seeds[cell])
    save(np.dstack([np.ones((size, size, 3)), np.clip(a, 0, 1)]), OUT, "speed_lines.png")


def vignette():
    size = 256
    r = radial(size, size)
    a = smoothstep(0.55, 1.35, r)
    save(np.dstack([np.ones((size, size, 3)), a]), OUT, "vignette.png")


def icons():
    """18×18 mob effect icons: a syringe (adrenaline, red fluid) and a drooping heart line (fatigue)."""
    img = np.zeros((18, 18, 4))

    def px(im, x, y, c):
        if 0 <= x < 18 and 0 <= y < 18:
            im[y, x] = c

    outline, glass, fluid, steel = (0.12, 0.1, 0.12, 1), (0.85, 0.9, 0.95, 1), (0.9, 0.15, 0.2, 1), (0.7, 0.72, 0.76, 1)
    for t in range(10):                         # barrel along the diagonal
        x, y = 4 + t, 13 - t
        for w in (-1, 0, 1):
            px(img, x + w, y + w, glass if t > 2 else fluid)
        px(img, x - 2, y - 2 + 0, outline)
        px(img, x + 2, y + 2, outline)
    for t in range(3):
        px(img, 1 + t, 16 - t, steel)            # needle
    px(img, 14, 2, outline); px(img, 15, 3, outline); px(img, 13, 1, outline); px(img, 16, 4, outline)
    for t in range(4, 8):
        px(img, 4 + t, 13 - t, fluid)
    save(img, TEX, "mob_effect", "adrenaline.png")

    img = np.zeros((18, 18, 4))
    blue, dark = (0.45, 0.55, 0.75, 1), (0.15, 0.17, 0.25, 1)
    heart = ["..XX...XX..", ".XXXX.XXXX.", "XXXXXXXXXXX", "XXXXXXXXXXX", ".XXXXXXXXX.", "..XXXXXXX..",
             "...XXXXX...", "....XXX....", ".....X....."]
    for j, row in enumerate(heart):
        for i, ch in enumerate(row):
            if ch == "X":
                px(img, 3 + i, 3 + j, blue)
    for i in range(13):                          # flat line through it
        px(img, 2 + i, 8 if i < 5 or i > 8 else 10 - abs(i - 7), dark)
    save(img, TEX, "mob_effect", "adrenaline_fatigue.png")


def main():
    rng = np.random.default_rng(20261023)
    smoke_puffs(rng)
    flames(rng)
    fireballs(rng)
    dust_ring(rng)
    scorch(rng)
    speed_lines(rng)
    vignette()
    icons()


if __name__ == "__main__":
    main()
