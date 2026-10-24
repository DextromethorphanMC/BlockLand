"""Kill icons of special skins (VIEWMODEL_SPEC section 38): from an artwork in source/kill_icons/<id>.(webp|png) (with
its own transparency), the three GUI textures client/hud/KillIcon plays on a confirmed kill, under
textures/gui/kill_icon/:

  <id>.png        the icon, 256 px (linear filtered: drawn at any size)
  <id>_glow.png   its glow, 128 px: white, alpha = the blurred bright and saturated parts (tinted by the skin's colour)
  <id>_burn.png   the burn-in, 4 x 4 frames of 256 px (1024 px): the icon igniting from its heart outwards along a noisy
                  front with a white-hot edge; played forwards as it appears, backwards as it burns away

python -I tools/assets/build_all.py kill_icons"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "source", "kill_icons")
ICONS = ("cerberus",)
SIZE = 256
GLOW = 128
FRAMES = 16                      # burn-in frames (a 4 x 4 sheet)
EDGE = 0.07                      # width of the burning front, in the burn field's units
HOT = np.array([1.0, 0.86, 0.55])
EMBER = np.array([1.0, 0.36, 0.06])


def _load(icon):
    for ext in (".webp", ".png"):
        p = os.path.join(SOURCE, icon + ext)
        if os.path.exists(p):
            return Image.open(p).convert("RGBA")
    raise FileNotFoundError(f"no source artwork for kill icon {icon} in {SOURCE}")


def _resize(img, size):
    """Premultiplied Lanczos resize (no dark fringes where the colour meets the transparency)."""
    a = np.asarray(img, np.float64) / 255.0
    pre = np.concatenate([a[..., :3] * a[..., 3:], a[..., 3:]], -1)
    chans = [np.asarray(Image.fromarray(pre[..., c].astype(np.float32), "F").resize((size, size), Image.LANCZOS))
             for c in range(4)]
    out = np.clip(np.stack(chans, -1), 0.0, 1.0)
    alpha = out[..., 3:]
    rgb = np.where(alpha > 1e-4, out[..., :3] / np.maximum(alpha, 1e-4), 0.0)
    return np.concatenate([np.clip(rgb, 0, 1), alpha], -1)


def _blur(img, sigma):
    h, w = img.shape
    pad = int(3 * sigma)
    big = np.pad(img, pad)
    H, W = big.shape
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.fftfreq(W)[None, :]
    g = np.exp(-2 * (np.pi ** 2) * sigma ** 2 * (fx ** 2 + fy ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(big) * g))[pad:pad + h, pad:pad + w]


def _noise(size, rng, octaves=5):
    """fBm value noise 0..1 over a size x size grid."""
    out = np.zeros((size, size))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = 4 * 2 ** o
        grid = rng.random((cells + 1, cells + 1))
        img = np.asarray(Image.fromarray(grid.astype(np.float32), "F").resize((size, size), Image.BICUBIC))
        out += amp * img
        total += amp
        amp *= 0.5
    out /= total
    return (out - out.min()) / max(out.max() - out.min(), 1e-6)


def _save(arr, path):
    Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").save(path)
    # drawn scaled and turned: linear filtering, not the GUI's nearest pixels
    with open(path + ".mcmeta", "w", encoding="utf-8") as f:
        json.dump({"texture": {"blur": True, "clamp": True}}, f)


def glow(icon):
    """White with the alpha of the bright, saturated parts of the icon, blurred wide."""
    a = icon[..., 3]
    rgb = icon[..., :3]
    bright = rgb.max(-1)
    sat = bright - rgb.min(-1)
    m = a * np.clip(0.35 * bright + 0.9 * sat - 0.15, 0, 1)
    small = np.asarray(Image.fromarray(m.astype(np.float32), "F").resize((GLOW, GLOW), Image.BILINEAR))
    g = _blur(small, GLOW * 0.035) * 0.65 + _blur(small, GLOW * 0.09) * 0.75
    g = np.clip(g / max(g.max(), 1e-6), 0, 1) ** 0.8
    return np.concatenate([np.ones((GLOW, GLOW, 3)), g[..., None]], -1)


def burn_sheet(icon, seed):
    """The icon igniting: a burn field (distance from the heart, broken up by noise) swept by a front; behind it the
    icon, on it a white-hot to ember band, ahead of it nothing."""
    rng = np.random.default_rng(seed)
    n = SIZE
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    radial = np.sqrt(xx ** 2 + yy ** 2) / np.sqrt(2)
    field = 0.78 * radial + 0.22 * _noise(n, rng)
    field = (field - field.min()) / (field.max() - field.min())
    sheet = np.zeros((4 * n, 4 * n, 4))
    for k in range(FRAMES):
        t = (k + 1) / FRAMES * (1 + EDGE * 1.5)                # the last frame is the whole icon, settled
        shown = np.clip((t - EDGE - field) / (EDGE * 0.35), 0, 1)
        front = np.clip(1 - np.abs(field - (t - EDGE * 0.5)) / (EDGE * 0.6), 0, 1) * (field < t)
        if k == FRAMES - 1:
            shown, front = np.ones_like(shown), np.zeros_like(front)
        rgb = icon[..., :3] * shown[..., None]
        heat = front[..., None] * np.where(front[..., None] > 0.6, HOT, EMBER)
        rgb = np.clip(rgb * (1 - 0.6 * front[..., None]) + heat, 0, 1)
        alpha = np.clip(icon[..., 3] * shown + front * np.clip(icon[..., 3] * 1.6, 0, 1), 0, 1)
        r, c = divmod(k, 4)
        sheet[r * n:(r + 1) * n, c * n:(c + 1) * n] = np.concatenate([rgb, alpha[..., None]], -1)
    return sheet


def build(path, only=None):
    for k, name in enumerate(ICONS):
        if only and name not in only:
            continue
        icon = _resize(_load(name), SIZE)
        _save(icon, path("textures", "gui", "kill_icon", f"{name}.png"))
        _save(glow(icon), path("textures", "gui", "kill_icon", f"{name}_glow.png"))
        _save(burn_sheet(icon, 9100 + k), path("textures", "gui", "kill_icon", f"{name}_burn.png"))
        print(f"kill icon {name}: icon {SIZE} px, glow {GLOW} px, burn-in {FRAMES} frames")


def steps(path, apply_pbr):
    """build_all.py steps (STEP_MODULES): kill_icons."""
    return {"kill_icons": lambda: build(path)}
