"""Name card (名片) art: textures for data/blockland/cards/cards.json, written to assets/blockland/textures/card/.

python -I tools/assets/gen_name_cards.py

Every card is 384 x 120 (the client draws them at NameCards.ASPECT = 3.2). Animated cards are vertical strips of
frames (384 x 120 each, top to bottom); cards with a shader effect only carry the backdrop the effect is drawn over.
The left two thirds stay calm enough for the player's name, the right end for the tier badge."""
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "textures", "card")
W, H = 384, 120

rng = np.random.default_rng(20261024)
ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xs / (W - 1), ys / (H - 1)


def lerp(a, b, t):
    t = np.clip(t, 0, 1)[..., None] if np.ndim(t) else t
    return np.asarray(a, np.float32) * (1 - t) + np.asarray(b, np.float32) * t


def noise(scale, octaves=3, seed=0):
    """Value noise on the card grid, 0..1."""
    r = np.random.default_rng(seed)
    total = np.zeros((H, W), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        s = scale * (2 ** o)
        gw, gh = int(W / s) + 2, int(H / s) + 2
        grid = r.random((gh, gw)).astype(np.float32)
        gx, gy = xs / s, ys / s
        x0, y0 = gx.astype(int), gy.astype(int)
        fx, fy = gx - x0, gy - y0
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        a = grid[y0, x0] * (1 - fx) + grid[y0, x0 + 1] * fx
        b = grid[y0 + 1, x0] * (1 - fx) + grid[y0 + 1, x0 + 1] * fx
        total += (a * (1 - fy) + b * fy) * amp
        norm += amp
        amp *= 0.5
    return total / norm


def vignette(img, strength=0.45):
    d = np.sqrt(((u - 0.45) * 1.1) ** 2 + ((v - 0.5) * 0.9) ** 2)
    return img * (1 - strength * np.clip(d, 0, 1) ** 1.6)[..., None]


def frame_edge(img, color, alpha=0.55):
    """A thin inner rim, like a printed card's edge."""
    edge = np.zeros((H, W), np.float32)
    edge[0:2, :] = edge[-2:, :] = 1
    edge[:, 0:2] = edge[:, -2:] = 1
    return lerp(img, color, edge * alpha)


def save(name, frames):
    os.makedirs(OUT, exist_ok=True)
    strip = np.concatenate([np.clip(f, 0, 255) for f in frames], axis=0).astype(np.uint8)
    Image.fromarray(strip, "RGB").save(os.path.join(OUT, name + ".png"))
    print("card", name, len(frames), "frame(s)")


# ---- static cards ---------------------------------------------------------------------------------

def default():
    base = lerp([22, 28, 38], [40, 50, 66], u * 0.6 + v * 0.4)
    stripes = ((xs + ys * 1.2) % 22 < 1.2).astype(np.float32) * 0.06
    img = base * (1 + stripes[..., None])
    return frame_edge(vignette(img), [120, 132, 150])


def ocean():
    n = noise(48, 4, 1)
    wave = np.sin(xs / 34 + np.sin(xs / 61) * 2 + ys / 9 + n * 4) * 0.5 + 0.5
    deep = lerp([6, 30, 70], [10, 70, 130], v)
    img = lerp(deep, [70, 170, 220], (wave ** 6) * 0.55 * (0.4 + v))
    return frame_edge(vignette(img, 0.35), [120, 200, 240])


def forest():
    img = lerp([150, 190, 170], [230, 220, 180], 1 - v)
    for i, (shade, base, amp) in enumerate([([70, 110, 90], 0.45, 0.10), ([40, 80, 60], 0.62, 0.12), ([20, 48, 36], 0.8, 0.09)]):
        ridge = base + amp * (noise(60 - i * 15, 3, 10 + i) - 0.5) * 2 + 0.04 * np.sin(xs / (30 + i * 9))
        img = lerp(img, shade, (v > ridge).astype(np.float32))
    mist = np.exp(-((v - 0.55) / 0.12) ** 2) * 0.18
    img = lerp(img, [220, 230, 220], mist)
    return frame_edge(vignette(img, 0.4), [200, 220, 190], 0.4)


def sunset():
    sky = lerp([255, 150, 70], [70, 30, 110], v ** 0.8 * 0.5 + (1 - v) * 0.0)
    sky = lerp([255, 175, 90], [60, 24, 100], 1 - v)
    sun = np.exp(-(((xs - W * 0.78) / 26) ** 2 + ((ys - H * 0.62) / 26) ** 2))
    img = lerp(sky, [255, 240, 200], sun)
    bands = (np.sin(ys / 3.5) > 0.6) & (v > 0.55) & (np.abs(xs - W * 0.78) < 30)
    img = lerp(img, [255, 140, 90], bands.astype(np.float32) * 0.5 * sun * 4)
    sea = v > 0.82
    img = np.where(sea[..., None], lerp([40, 20, 70], [255, 160, 110], (np.sin(xs / 5 + ys) * 0.5 + 0.5) * 0.25 * sun * 3), img)
    return frame_edge(vignette(img, 0.3), [255, 200, 150], 0.45)


# ---- frame strips ---------------------------------------------------------------------------------

def pulse(frames=8):
    out = []
    base = lerp([10, 12, 20], [24, 18, 36], u)
    for f in range(frames):
        t = f / frames
        img = base.copy()
        # a heartbeat trace sweeping left to right
        x = (u - t) % 1.0
        trace_y = 0.55 - 0.32 * np.exp(-((x - 0.5) / 0.015) ** 2) + 0.18 * np.exp(-((x - 0.53) / 0.012) ** 2)
        line = np.exp(-((v - trace_y) / 0.018) ** 2) * np.clip(1 - x * 1.4, 0, 1)
        img = lerp(img, [255, 70, 110], line)
        ring = np.exp(-((np.sqrt((xs - W * 0.82) ** 2 + (ys - H * 0.5) ** 2) - (10 + 46 * t)) / 3) ** 2) * (1 - t)
        img = lerp(img, [255, 120, 160], ring * 0.7)
        out.append(frame_edge(vignette(img, 0.3), [255, 90, 130], 0.5))
    return out


def circuit(frames=12):
    out = []
    grid = np.zeros((H, W), np.float32)
    traces = []
    r = np.random.default_rng(5)
    for _ in range(26):
        y = int(r.integers(6, H - 6))
        x0 = int(r.integers(0, W - 80))
        x1 = min(W - 1, x0 + int(r.integers(40, 160)))
        grid[y, x0:x1] = 1
        ty = int(np.clip(y + r.integers(-30, 30), 4, H - 4))
        lo, hi = sorted((y, ty))
        grid[lo:hi + 1, x1] = 1
        traces.append((y, x0, x1))
        grid[max(0, ty - 1):ty + 2, max(0, x1 - 1):x1 + 2] = 1.0
    glow = np.zeros_like(grid)
    for k in (1, 2, 3):
        glow += np.roll(grid, k, 0) + np.roll(grid, -k, 0) + np.roll(grid, k, 1) + np.roll(grid, -k, 1)
    glow = np.clip(glow / 8, 0, 1)
    base = lerp([6, 16, 14], [10, 28, 26], u)
    for f in range(frames):
        t = f / frames
        img = lerp(base, [20, 120, 90], grid * 0.6 + glow * 0.15)
        for i, (y, x0, x1) in enumerate(traces):
            p = (t + i * 0.137) % 1.0
            px = x0 + (x1 - x0) * p
            spot = np.exp(-(((xs - px) / 6) ** 2 + ((ys - y) / 2.5) ** 2))
            img = lerp(img, [140, 255, 210], spot * 0.95)
        out.append(frame_edge(vignette(img, 0.35), [60, 220, 170], 0.5))
    return out


def inferno(frames=8):
    out = []
    for f in range(frames):
        t = f / frames
        n = noise(26, 4, 40 + f)
        heat = np.clip((v - 0.15) * 1.3 + (n - 0.5) * 0.7, 0, 1)
        img = lerp([20, 4, 2], [120, 20, 6], heat)
        img = lerp(img, [255, 140, 30], np.clip(heat - 0.6, 0, 1) * 2.0)
        # embers rising
        for k in range(30):
            ex = (math.sin(k * 12.9898) * 43758.5453) % 1.0 * W
            ey = (1 - ((t + k * 0.073) % 1.0)) * H
            img = lerp(img, [255, 210, 120], np.exp(-(((xs - ex) / 1.6) ** 2 + ((ys - ey) / 1.6) ** 2)))
        out.append(frame_edge(vignette(img, 0.25), [255, 170, 60], 0.65))
    return out


# ---- backdrops for shader effects -----------------------------------------------------------------

def aurora_base():
    sky = lerp([4, 10, 26], [10, 26, 44], 1 - v)
    stars = (rng.random((H, W)) > 0.996).astype(np.float32) * (1 - v)
    img = lerp(sky, [220, 230, 255], stars)
    ridge = 0.78 + 0.07 * (noise(40, 3, 77) - 0.5) * 2
    img = lerp(img, [6, 12, 18], (v > ridge).astype(np.float32))
    return frame_edge(img, [120, 220, 200], 0.4)


def holo_base():
    n = noise(30, 3, 90)
    img = lerp([150, 156, 170], [210, 214, 226], u * 0.5 + n * 0.5)
    lines = ((xs - ys * 0.6) % 9 < 1).astype(np.float32) * 0.08
    img = img * (1 - lines[..., None])
    return frame_edge(img, [240, 240, 255], 0.6)


def neon_base():
    img = lerp([8, 6, 18], [20, 10, 36], 1 - v)
    r = np.random.default_rng(8)
    x = 0
    while x < W:
        bw = int(r.integers(14, 34))
        bh = r.uniform(0.25, 0.7)
        mask = (xs >= x) & (xs < x + bw) & (v > 1 - bh)
        img = np.where(mask[..., None], [14, 10, 26], img)
        win = mask & ((xs - x) % 6 < 2) & ((ys % 8) < 3) & (r.random((H, W)) > 0.55)
        img = np.where(win[..., None], [60, 40, 90], img)
        x += bw + int(r.integers(1, 5))
    return frame_edge(img, [255, 60, 220], 0.5)


if __name__ == "__main__":
    save("default", [default()])
    save("ocean", [ocean()])
    save("forest", [forest()])
    save("sunset", [sunset()])
    save("pulse", pulse())
    save("circuit", circuit())
    save("inferno", inferno())
    save("aurora", [aurora_base()])
    save("holo", [holo_base()])
    save("neon", [neon_base()])
