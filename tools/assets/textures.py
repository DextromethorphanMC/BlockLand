"""Procedural, tileable textures (numpy -> PNG bytes). All deterministic."""
import io

import numpy as np
from PIL import Image


def png_bytes(arr):
    arr = np.asarray(arr)
    if arr.dtype != np.uint8:
        arr = (np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8)
    if arr.ndim == 3 and arr.shape[2] == 1:
        arr = arr[:, :, 0]
    buf = io.BytesIO()
    Image.fromarray(np.ascontiguousarray(arr)).save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def save_png(arr, path):
    with open(path, "wb") as f:
        f.write(png_bytes(arr))


def _blur_wrap(img, sx, sy=None):
    """Gaussian blur with wrap-around (tileable) via FFT."""
    sy = sx if sy is None else sy
    h, w = img.shape
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    g = np.exp(-2 * (np.pi ** 2) * ((fx * sx) ** 2 + (fy * sy) ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(img) * g))


def tile_noise(size, scale, rng):
    """Smooth tileable noise in [0, 1] with feature size ~scale px."""
    n = _blur_wrap(rng.standard_normal((size, size)), scale)
    n -= n.min()
    return n / max(n.max(), 1e-9)


def stipple(size=256, seed=11):
    """Grey stippled-polymer albedo (values ~0.55..1)."""
    rng = np.random.default_rng(seed)
    h = np.zeros((size, size))
    count = size * size // 9
    ys, xs = rng.integers(0, size, count), rng.integers(0, size, count)
    np.add.at(h, (ys, xs), rng.uniform(0.6, 1.0, count))
    h = _blur_wrap(h, 1.1)
    h += 0.15 * tile_noise(size, 6, rng)
    gx = np.roll(h, -1, 1) - np.roll(h, 1, 1)
    gy = np.roll(h, -1, 0) - np.roll(h, 1, 0)
    shade = -(gx * -0.6 + gy * -0.8) * 1.6               # light from the top-left
    v = 0.80 + shade - 0.10 * (h - h.mean()) / (h.std() + 1e-9) * 0.4
    v = np.clip(v, 0.50, 1.0)
    return np.repeat(v[:, :, None], 3, 2)


def brushed(size=256, seed=12):
    """Subtle brushed/noisy metal albedo (values ~0.84..1)."""
    rng = np.random.default_rng(seed)
    streak = _blur_wrap(rng.standard_normal((size, size)), 24.0, 0.5)
    streak = (streak - streak.mean()) / (streak.std() + 1e-9)
    grain = _blur_wrap(rng.standard_normal((size, size)), 0.7)
    grain = (grain - grain.mean()) / (grain.std() + 1e-9)
    blotch = tile_noise(size, 30, rng)
    v = 0.93 + 0.030 * streak + 0.018 * grain - 0.05 * (blotch - 0.5)
    v = np.clip(v, 0.80, 1.0)
    rgb = np.stack([v, v, v * 1.01], 2)
    return np.clip(rgb, 0, 1)


def dot(size=32, radius=None, soft=1.0):
    """White dot on transparent (RGBA float)."""
    r = radius if radius is not None else size * 0.30
    y, x = np.mgrid[0:size, 0:size] + 0.5
    d = np.hypot(x - size / 2, y - size / 2)
    a = np.clip((r - d) / soft + 0.5, 0, 1)
    out = np.ones((size, size, 4))
    out[:, :, 3] = a
    return out


CAMO_COLORS = np.array([[0.74, 0.67, 0.51],    # khaki
                        [0.60, 0.51, 0.36],    # tan
                        [0.40, 0.31, 0.21],    # brown
                        [0.33, 0.36, 0.23]])   # olive


def camo_digital(size=128, block=8, seed=13):
    """Tileable digital camo: quantised multi-scale noise on an 8 px grid with 4 px detail."""
    rng = np.random.default_rng(seed)
    nb = size // block
    big = tile_noise(nb * 4, 5.0, rng)[::4, ::4]
    mid = tile_noise(nb * 4, 2.2, rng)[::4, ::4]
    field = 0.65 * big + 0.35 * mid
    q = np.quantile(field, [0.30, 0.58, 0.82])
    idx = np.digitize(field, q)
    img = np.kron(idx, np.ones((block, block), dtype=int))
    # 4 px detail: flip some sub-blocks to a neighbouring colour
    sub = size // (block // 2)
    flip = rng.random((sub, sub)) < 0.18
    alt = np.roll(img, block // 2, axis=int(rng.integers(0, 2)))
    det = np.kron(flip, np.ones((block // 2, block // 2), dtype=bool))
    img = np.where(det, alt, img)
    rgb = CAMO_COLORS[img]
    rgb *= (0.97 + 0.06 * rng.random((size, size, 1)))
    return np.clip(rgb, 0, 1)
