"""Procedural LabPBR texture sets (VIEWMODEL_SPEC sections 20 / 21) for the weapons, attachments and skins.

python -I tools/assets/pbr_textures.py [set ...]   -> textures/pbr/<set>.png, <set>_n.png, <set>_s.png
                                                     (no arguments: every set; a prefix such as "skin/" selects a group)

Every set tiles. Each recipe works at real scale: a set covers TILE metres per repeat (its material's `uvScale` is
0.05 / TILE, see uv_scale()), heights are in millimetres and turned into the normal map with the texel size, so a
1 mm stipple looks like one on every weapon; micro texture below the height channel's interest (grain, blast, brush
lines) only tilts the normal (Layers.relief). Channel layout (LabPBR 1.3):
  <set>.png    albedo, sRGB (metals: the F0 colour)
  <set>_n.png  R = normal X (right), G = normal Y in the DirectX convention (+ = down the image = +v), B = AO, A = height
  <set>_s.png  R = perceptual smoothness, G = F0 0..229 (dielectrics) / 255 (metal: albedo is F0), B = porosity
               (0..64), A = emission 0..254 (255 = none)
Deterministic (fixed seeds)."""
import os
import sys
from dataclasses import dataclass
from typing import Callable

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland")
UV_UNIT = 0.05                      # metres per UV unit on the box-projected meshes (section 6)


# ============================================================================================ noise helpers
def blur(img, sx, sy=None):
    """Gaussian blur with wrap-around (tileable), sigma in pixels (sx along x / columns, sy along y / rows)."""
    sy = sx if sy is None else sy
    h, w = img.shape
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    g = np.exp(-2 * (np.pi ** 2) * ((fx * sx) ** 2 + (fy * sy) ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(img) * g))


def std(a):
    a = a - a.mean()
    return a / (a.std() + 1e-12)


def norm01(a):
    a = a - a.min()
    return a / (a.max() + 1e-12)


def noise(size, rng, sx, sy=None):
    """Zero-mean, unit-variance tileable noise with feature size ~sigma pixels (anisotropic when sy differs)."""
    return std(blur(rng.standard_normal((size, size)), sx, sy))


def fbm(size, rng, base, octaves=5, gain=0.55, aniso=1.0):
    """Fractal noise: octaves from `base` px features down, normalised to zero mean / unit variance."""
    out = np.zeros((size, size))
    amp, s = 1.0, float(base)
    for _ in range(octaves):
        out += amp * noise(size, rng, max(0.5, s * aniso), max(0.5, s))
        amp *= gain
        s /= 2
        if s < 0.5:
            break
    return std(out)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def warp(img, dx, dy):
    """Samples img at (x + dx, y + dy) with wrap-around (bilinear)."""
    h, w = img.shape[:2]
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    mx = ((x + dx) % w).astype(np.float32)
    my = ((y + dy) % h).astype(np.float32)
    return cv2.remap(img.astype(np.float32), mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def worley(size, rng, cells, jitter=1.0):
    """Tileable cellular noise: (F1, F2, cell id) with `cells` x `cells` feature points; distances in cell units."""
    pts = (rng.random((cells, cells, 2)) - 0.5) * jitter + 0.5
    ids = rng.permutation(cells * cells).reshape(cells, cells)
    y, x = (np.mgrid[0:size, 0:size] + 0.5) * (cells / size)
    cx, cy = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    f1 = np.full((size, size), 9.0)
    f2 = np.full((size, size), 9.0)
    id1 = np.zeros((size, size), np.int64)
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            gx, gy = cx + ox, cy + oy
            p = pts[gy % cells, gx % cells]
            d = np.hypot(gx + p[..., 0] - x, gy + p[..., 1] - y)
            closer = d < f1
            f2 = np.where(closer, f1, np.minimum(f2, d))
            id1 = np.where(closer, ids[gy % cells, gx % cells], id1)
            f1 = np.where(closer, d, f1)
    return f1, f2, id1


def tile_draw(size, draw):
    """Runs draw(canvas, offset) on a 3x3 tiled canvas and folds it back, so marks crossing an edge wrap around."""
    canvas = np.zeros((size * 3, size * 3), np.float32)
    draw(canvas, size)
    out = np.zeros((size, size), np.float32)
    for j in range(3):
        for i in range(3):
            out += canvas[j * size:(j + 1) * size, i * size:(i + 1) * size]
    return out


def scratches(size, rng, count, length, width=1.0, angle=None, spread=np.pi, curve=0.0, strength=(0.4, 1.0)):
    """Thin anti-aliased scratch lines (0..1 mask, tileable). length in px (lo, hi); angle = main direction (rad,
    None = any), spread = +/- range around it; curve bends each scratch a little."""
    sub = 4                                                     # sub-pixel precision of cv2 (2^4)

    def draw(canvas, off):
        for _ in range(count):
            x0, y0 = off + rng.random() * size, off + rng.random() * size
            a = rng.random() * 2 * np.pi if angle is None else angle + (rng.random() * 2 - 1) * spread
            ln = rng.uniform(*length)
            bend = (rng.random() * 2 - 1) * curve
            n = 6
            pts = []
            for k in range(n + 1):
                t = k / n
                aa = a + bend * (t - 0.5)
                pts.append((x0 + np.cos(aa) * ln * t, y0 + np.sin(aa) * ln * t))
            val = rng.uniform(*strength)
            thick = max(1, int(round(width * rng.uniform(0.7, 1.3))))
            p = (np.array(pts) * (1 << sub)).astype(np.int32)
            cv2.polylines(canvas, [p], False, float(val), thick, cv2.LINE_AA, sub)
    return np.clip(tile_draw(size, draw), 0, 1)


def dots(size, rng, count, radius, height_fn="dome", jitter_r=0.3):
    """Tileable field of round bumps (sum of domes), radius in px (mean); returns height 0..~1 per bump."""
    def draw(canvas, off):
        for _ in range(count):
            x, y = off + rng.random() * size, off + rng.random() * size
            r = radius * (1 + (rng.random() * 2 - 1) * jitter_r)
            cv2.circle(canvas, (int(x * 16), int(y * 16)), int(r * 16), 1.0, -1, cv2.LINE_AA, 4)
    mask = tile_draw(size, draw)
    # round the plateaus into domes: blur the coverage a little, keep it bounded
    return blur(np.clip(mask, 0, 1.6), radius * 0.45)


# ============================================================================================ wear & handling helpers
def scratch_field(size, rng, pmm, count, length_mm=(0.8, 18.0), angle=None, spread=np.pi, curve=0.3,
                  cluster=None, width=1.0, strength=(0.25, 1.0)):
    """Real-scale scratches (0..1 mask, tileable): lengths log-distributed (many short nicks, few long drags), each
    one fading in and out along its length; `cluster` (0..1 map) makes them gather where parts get handled instead
    of sprinkling evenly. angle/spread as in scratches()."""
    sub = 4
    lo, hi = np.log(max(1.0, length_mm[0] / pmm)), np.log(max(2.0, length_mm[1] / pmm))

    def draw(canvas, off):
        n = tries = 0
        while n < count and tries < count * 40:
            tries += 1
            px, py = rng.random() * size, rng.random() * size
            if cluster is not None and rng.random() > cluster[int(py) % size, int(px) % size]:
                continue
            n += 1
            a = rng.random() * 2 * np.pi if angle is None else angle + (rng.random() * 2 - 1) * spread
            ln = np.exp(lo + (hi - lo) * rng.random() ** 1.7)
            bend = (rng.random() * 2 - 1) * curve
            val = rng.uniform(*strength)
            thick = 1 if rng.random() > 0.08 * width else 2
            seg = 8
            for k in range(seg):
                t0, t1 = k / seg, (k + 1) / seg
                pts = []
                for t in (t0, t1):
                    aa = a + bend * (t - 0.5)
                    pts.append((off + px + np.cos(aa) * ln * t, off + py + np.sin(aa) * ln * t))
                fade = np.sin(np.pi * (k + 0.5) / seg) ** 0.6
                p = (np.array(pts) * (1 << sub)).astype(np.int32)
                cv2.polylines(canvas, [p], False, float(val * fade), thick, cv2.LINE_AA, sub)
    return np.clip(tile_draw(size, draw), 0, 1)


def groove(mask, depth_mm, burr=0.25):
    """Height of scratches: a V-groove with a faint raised burr along both sides (mm, <= 0 inside)."""
    soft = blur(mask, 0.6)
    rim = np.clip(blur(mask, 1.6) - soft, 0, None)
    return -depth_mm * soft + burr * depth_mm * rim


def smudges(size, rng, scale_px, coverage, aniso=1.0):
    """Soft 0..1 patches (oil, grime, handling polish) covering about `coverage` of the tile."""
    f = fbm(size, rng, scale_px, 4, aniso=aniso)
    thr = np.quantile(f, 1 - coverage)
    return smoothstep(thr - 0.35, thr + 0.45, f)


def prints(size, rng, pmm, count, strength=(0.4, 0.9)):
    """Partial fingerprints (0..1 oil mask): whorls of ~0.48 mm ridges in an ~16 x 20 mm oval, broken up so they
    never read as stamps."""
    out = np.zeros((size, size))
    y, x = np.mgrid[0:size, 0:size].astype(float)
    wob = fbm(size, rng, 1.6 / pmm, 3)
    for _ in range(count):
        cx, cy = rng.random() * size, rng.random() * size
        a = 8.0 / pmm * rng.uniform(0.85, 1.15)
        b = a * rng.uniform(1.15, 1.35)
        ang = rng.uniform(0, np.pi)
        dx = (x - cx + size / 2) % size - size / 2
        dy = (y - cy + size / 2) % size - size / 2
        rx = dx * np.cos(ang) + dy * np.sin(ang)
        ry = -dx * np.sin(ang) + dy * np.cos(ang)
        r = np.sqrt((rx / a) ** 2 + (ry / b) ** 2)
        ridges = 0.5 + 0.5 * np.cos(2 * np.pi * (r * a * pmm / 0.48 + 1.1 * wob))
        keep = smoothstep(0.0, 0.9, fbm(size, rng, 2.5 / pmm, 3))
        out = np.maximum(out, ridges * smoothstep(1.0, 0.55, r) * keep * rng.uniform(*strength))
    return out


def pits(size, rng, pmm, per_cm2, radius_mm=(0.06, 0.25)):
    """Sparse tiny pits (corrosion pinholes, casting / blast pits): 0..1 depth mask."""
    count = int(per_cm2 * (size * pmm / 10.0) ** 2)

    def draw(canvas, off):
        for _ in range(count):
            x, y = off + rng.random() * size, off + rng.random() * size
            r = max(0.5, rng.uniform(*radius_mm) / pmm)
            cv2.circle(canvas, (int(x * 16), int(y * 16)), int(r * 16), float(rng.uniform(0.5, 1.0)), -1,
                       cv2.LINE_AA, 4)
    return np.clip(blur(tile_draw(size, draw), 0.5), 0, 1)


def specks(size, rng, pmm, per_cm2):
    """Tiny loose particles (dust, lint) as a 0..1 mask, one or two texels each."""
    count = int(per_cm2 * (size * pmm / 10.0) ** 2)
    m = np.zeros((size, size))
    m[rng.integers(0, size, count), rng.integers(0, size, count)] = rng.random(count)
    return np.clip(blur(m, 0.55) * 3, 0, 1)


def milling(size, rng, pmm, pitch_mm=0.35, sweep=0.18):
    """Face-milling / fly-cut marks: fine parallel cuts along u that sweep in shallow arcs (one bend per tile, so
    the set still tiles), with a little chatter. Returns -1..1."""
    y, x = np.mgrid[0:size, 0:size] / size
    k = max(1, int(round(size * pmm / pitch_mm)))
    phase = k * (y + sweep * 0.5 * (1 - np.cos(2 * np.pi * x))) + 0.6 * fbm(size, rng, size / 6, 3) \
        + 0.15 * noise(size, rng, 6, 1)
    cut = np.cos(2 * np.pi * phase)
    return cut * (0.7 + 0.3 * smoothstep(-1.5, 1.5, noise(size, rng, size / 10, size / 30)))


# ============================================================================================ colour helpers
def srgb(rgb):
    return np.asarray(rgb, float)


def lerp(a, b, t):
    t = np.asarray(t, float)
    if t.ndim == 2:
        t = t[..., None]
    return np.asarray(a, float) * (1 - t) + np.asarray(b, float) * t


def solid(size, rgb):
    return np.ones((size, size, 3)) * np.asarray(rgb, float)


def tint(base, variation, amount):
    """base colour (3,) or (H, W, 3) scaled by 1 + amount * variation."""
    return base * (1 + amount * variation)[..., None]


def palette(t, stops):
    """Piecewise-linear colour ramp over t in 0..1; stops = [(pos, rgb), ...]."""
    pos = np.array([p for p, _ in stops])
    cols = np.array([c for _, c in stops], float)
    out = np.zeros(t.shape + (3,))
    for c in range(3):
        out[..., c] = np.interp(t, pos, cols[:, c])
    return out


# ============================================================================================ a material in layers
@dataclass
class Layers:
    albedo: np.ndarray                  # (H, W, 3) sRGB 0..1 (metal: F0 colour)
    height: np.ndarray                  # (H, W) millimetres
    rough: np.ndarray                   # (H, W) roughness 0..1
    metal: np.ndarray                   # (H, W) bool
    f0: np.ndarray | float = 0.04       # dielectric reflectance (linear)
    porosity: np.ndarray | float = 0.0  # 0..1 (LabPBR 0..64)
    emission: np.ndarray | None = None  # 0..1
    ao: np.ndarray | None = None        # extra baked cavity darkening 0..1 (multiplies the height-derived AO)
    relief: np.ndarray | None = None    # mm of relief for the normal map only: micro texture (grain, blast, brush
                                        # lines) and gentle sheet waviness that should not fill the height channel


@dataclass
class SetDef:
    recipe: Callable
    size: int                           # pixels
    tile: float                         # metres per repeat
    seed: int
    detail: float = 1.0                 # how much pack() lifts the visible detail band (0 = keep the recipe as it is)


def px_mm(d: SetDef):
    return d.tile * 1000.0 / d.size


def uv_scale(name):
    """The `uvScale` of a material using this set (one repeat every SETS[name].tile metres)."""
    return round(UV_UNIT / SETS[name].tile, 6)


def normal_from_height(h_mm, pmm, slope_limit=None):
    """DirectX-convention tangent-space normal (x right, y down the image) from a height map in millimetres; with
    `slope_limit`, slopes ease into that value (tanh) instead of growing without bound."""
    dx = (np.roll(h_mm, -1, 1) - np.roll(h_mm, 1, 1)) / (2 * pmm)
    dy = (np.roll(h_mm, -1, 0) - np.roll(h_mm, 1, 0)) / (2 * pmm)
    if slope_limit is not None:
        s = np.hypot(dx, dy)
        k = np.where(s > 1e-9, slope_limit * np.tanh(s / slope_limit) / np.maximum(s, 1e-9), 1.0)
        dx, dy = dx * k, dy * k
    n = np.stack([-dx, -dy, np.ones_like(dx)], -1)
    return n / np.linalg.norm(n, axis=-1, keepdims=True)


#: Height encoding: A = 255 at the highest point, one step per HEIGHT_STEP_MM below it (so micro-texture well under a
#: step stays a flat 255, and real relief - stipples, knurls, cracks - is stored at its true depth, ~0.5 mm full range).
HEIGHT_STEP_MM = 0.002
#: Material AO: a crevice this deep (mm) below its surroundings is fully occluded (times AO_STRENGTH).
AO_DEPTH_MM = 0.12
AO_STRENGTH = 0.6


def cavity_ao(h_mm, pmm, radius_mm=0.6):
    """Darkens what lies below its surroundings (crevices between stipples, wood pores, cracks), by absolute depth."""
    r = max(0.6, radius_mm / pmm)
    depth = np.clip(blur(h_mm, r) - h_mm, 0, None)
    return np.clip(1 - AO_STRENGTH * np.clip(depth / AO_DEPTH_MM, 0, 1), 0.0, 1.0)


#: What of a set reaches the screen: the first-person weapon gets about 0.2 mm per screen pixel at 4K and 0.4 mm at DLSS
#: Performance, so marks finer than VISIBLE_MM average away in the mip chain (the runtime keeps them as roughness:
#: Textures' Toksvig mips). The band from there up to DETAIL_MM - grain, blast, orange peel, grinding marks, scratches,
#: wear - is what reads as the material, and the recipes' true-to-scale amplitudes leave it too faint to see in the game.
#: pack() lifts that band (times the set's `detail`) in the normal map, the albedo and the roughness; the height channel
#: and the AO stay true. Soft limits keep strong edges (camo patterns, stipples, cracks) from overshooting.
VISIBLE_MM = 0.25
DETAIL_MM = 4.0
RELIEF_GAIN = 6.0           # the band's relief (normal map only)
SLOPE_LIMIT = 1.2           # normal slopes ease into ~50 degrees
ALBEDO_GAIN = 2.2           # the band's contrast (log of linear light: a ratio, never darker than black)
ALBEDO_LIMIT = 0.25         # ... eased into this much (log units: faint grain is lifted, two-tone patterns barely)
ROUGH_GAIN = 2.4            # the band's roughness variation
ROUGH_LIMIT = 0.12          # ... eased into this much roughness


def detail_band(img, pmm):
    """The VISIBLE_MM..DETAIL_MM band of a map (per channel), as a deviation around its local mean."""
    lo, hi = VISIBLE_MM / pmm * 0.5, DETAIL_MM / pmm * 0.5
    if img.ndim == 3:
        return np.stack([detail_band(img[..., c], pmm) for c in range(img.shape[2])], -1)
    return blur(img, lo) - blur(img, hi)


def soft(x, limit):
    return limit * np.tanh(x / limit)


def lift_detail(lay: Layers, d: SetDef):
    """-> (relief for the normal map in mm, albedo sRGB, roughness) with the visible band lifted (see VISIBLE_MM)."""
    pmm = px_mm(d)
    relief = lay.height if lay.relief is None else lay.height + lay.relief
    alb = np.clip(lay.albedo, 0, 1)
    rough = np.clip(lay.rough, 0.02, 1)
    if d.detail <= 0:
        return relief, alb, rough
    relief = relief + (RELIEF_GAIN - 1) * d.detail * detail_band(relief, pmm)
    if lay.emission is None:                                    # glowing patterns keep their exact colours
        lin = alb ** 2.2 + 0.005
        lifted = lin * np.exp((ALBEDO_GAIN - 1) * d.detail * soft(detail_band(np.log(lin), pmm), ALBEDO_LIMIT))
        alb = np.clip(lifted - 0.005, 0, 1) ** (1 / 2.2)
    rough = np.clip(rough + (ROUGH_GAIN - 1) * d.detail * soft(detail_band(rough, pmm), ROUGH_LIMIT), 0.02, 1)
    return relief, alb, rough


def pack(lay: Layers, d: SetDef):
    """-> (albedo uint8 (H,W,3), _n uint8 (H,W,4), _s uint8 (H,W,4))."""
    pmm = px_mm(d)
    relief, alb, rough = lift_detail(lay, d)
    n = normal_from_height(relief, pmm, SLOPE_LIMIT if d.detail > 0 else None)
    ao = cavity_ao(lay.height, pmm)
    if lay.ao is not None:
        ao = ao * lay.ao
    depth = (lay.height.max() - lay.height) / HEIGHT_STEP_MM
    hgt = np.clip(255 - np.round(depth), 1, 255) / 255
    nmap = np.stack([n[..., 0] * 0.5 + 0.5, n[..., 1] * 0.5 + 0.5, np.clip(ao, 0, 1), hgt], -1)
    smooth = 1 - np.sqrt(rough)
    f0 = np.broadcast_to(np.asarray(lay.f0, float), rough.shape)
    g = np.where(lay.metal, 255, np.clip(np.round(f0 * 255), 0, 229))
    poro = np.where(lay.metal, 0, np.clip(np.broadcast_to(np.asarray(lay.porosity, float), rough.shape), 0, 1) * 64)
    if lay.emission is None:
        a = np.full(rough.shape, 255.0)
    else:
        e = np.clip(lay.emission, 0, 1)
        a = np.where(e * 254 < 0.5, 255, np.round(e * 254))
    smap = np.stack([np.round(smooth * 255), g, np.round(poro), a], -1)
    to8 = lambda x: (np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)
    return to8(alb), to8(nmap), np.clip(smap, 0, 255).astype(np.uint8)


# ============================================================================================ recipes: metals
def _wear_mask(size, rng, coverage, blob_px, scratch_count=0, scratch_len=(20, 80)):
    """Sparse 0..1 mask of small rubbed-through spots and chips (crisp, ragged edges) plus optional scratches."""
    f = fbm(size, rng, blob_px, 4) + 0.6 * fbm(size, rng, max(1.0, blob_px / 4), 3)
    thr = np.quantile(f, 1 - coverage)
    m = smoothstep(thr, thr + 0.12, f)
    if scratch_count:
        m = np.maximum(m, scratches(size, rng, scratch_count, scratch_len, 1.0, strength=(0.5, 1.0)))
    return m


def steel_dark(size, rng, pmm):
    """Blued / black-oxide small parts and the AK barrel: dark steel with fine grinding lines along u, soft polish
    bands, a blue-to-plum cast drifting across the part, spots rubbed back to bright steel, handling scratches,
    pinholes and oily patches."""
    grind = 0.6 * noise(size, rng, 9.0 / pmm, 0.55) + 0.4 * noise(size, rng, 2.5 / pmm, 0.7)
    bands = noise(size, rng, 30.0 / pmm, 2.5 / pmm)
    mott = fbm(size, rng, size / 5, 4)
    hue = fbm(size, rng, size / 4, 3)
    handled = smudges(size, rng, size / 7, 0.35)
    rub = smoothstep(0.3, 1.0, smudges(size, rng, 1.5 / pmm, 0.02))
    sc = scratch_field(size, rng, pmm, 160, (0.5, 14), angle=0, spread=0.5, cluster=0.25 + 0.75 * handled)
    sc2 = scratch_field(size, rng, pmm, 50, (0.4, 6), strength=(0.2, 0.6))
    s = np.maximum(sc, sc2)
    pit = pits(size, rng, pmm, 0.4, (0.05, 0.15))
    oil = np.clip(0.6 * smudges(size, rng, size / 10, 0.2, aniso=1.6) + 0.35 * prints(size, rng, pmm, 2), 0, 1)
    base = lerp(srgb([0.215, 0.225, 0.26]), srgb([0.25, 0.225, 0.225]), smoothstep(-1.2, 1.2, hue))
    alb = tint(base, 0.05 * mott + 0.01 * bands + 0.02 * grind, 1.0)
    alb = lerp(alb, srgb([0.46, 0.46, 0.47]), 0.5 * rub)
    alb = lerp(alb, srgb([0.52, 0.52, 0.53]), 0.65 * s)
    alb = lerp(alb, alb * 0.7, 0.6 * pit)
    h = groove(s, 0.006) - 0.01 * pit
    rough = 0.34 + 0.035 * mott + 0.025 * bands + 0.02 * grind - 0.08 * handled - 0.08 * rub - 0.10 * s \
        - 0.10 * oil + 0.2 * pit
    return Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0018 * grind)


def brass(size, rng, pmm):
    """Cartridge brass: drawn and polished (fine lines along the case), the alloy drifting from yellow to a pinker
    tone, tarnish blooms and spots, fingerprints, chamber / extractor scratches along u."""
    draw = 0.7 * noise(size, rng, 6.0 / pmm, 0.6) + 0.3 * noise(size, rng, 1.5 / pmm, 0.8)
    alloy = fbm(size, rng, size / 4, 3)
    tarn = smoothstep(0.4, 1.7, fbm(size, rng, size / 7, 4))
    spots = smoothstep(0.6, 1.0, smudges(size, rng, 0.6 / pmm, 0.012))
    oil = np.clip(0.7 * prints(size, rng, pmm, 2) + 0.5 * smudges(size, rng, size / 8, 0.2), 0, 1)
    sc = scratch_field(size, rng, pmm, 60, (0.8, 10), angle=0, spread=0.12, strength=(0.2, 0.7))
    alb = lerp(srgb([0.91, 0.76, 0.46]), srgb([0.90, 0.70, 0.47]), smoothstep(-1, 1, alloy))
    alb = tint(alb, 0.03 * draw, 1.0)
    alb = lerp(alb, srgb([0.70, 0.52, 0.30]), 0.45 * tarn)
    alb = lerp(alb, srgb([0.60, 0.44, 0.26]), 0.4 * spots)
    alb = lerp(alb, srgb([0.95, 0.83, 0.58]), 0.35 * sc)
    h = groove(sc, 0.004)
    rough = 0.17 + 0.025 * draw + 0.16 * tarn + 0.15 * spots + 0.10 * oil - 0.05 * sc
    return Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0012 * draw)


def polished_steel(size, rng, pmm):
    """Pins, latches, the AWP bolt: bright polished steel - fine polishing lines along u, faint buffing swirls,
    hairline scratches, oily and carbon-dulled patches."""
    lines = 0.7 * noise(size, rng, 5.0 / pmm, 0.55) + 0.3 * noise(size, rng, 1.2 / pmm, 0.7)
    swirl = scratch_field(size, rng, pmm, 90, (2, 9), curve=1.6, strength=(0.1, 0.35))
    sc = scratch_field(size, rng, pmm, 70, (0.4, 6), strength=(0.25, 0.8))
    dull = smudges(size, rng, size / 6, 0.3)
    carbon = smudges(size, rng, size / 14, 0.15)
    oil = 0.6 * prints(size, rng, pmm, 1)
    alb = tint(srgb([0.74, 0.745, 0.76]), 0.015 * lines + 0.02 * fbm(size, rng, size / 6, 3), 1.0)
    alb = lerp(alb, srgb([0.60, 0.59, 0.58]), 0.2 * carbon)
    h = groove(np.maximum(sc, 0.6 * swirl), 0.003)
    rough = 0.14 + 0.02 * lines + 0.04 * dull + 0.05 * carbon + 0.12 * sc + 0.06 * swirl + 0.06 * oil
    return Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0006 * lines)


def dark_chrome(size, rng):
    """Black-chrome hardware for skins: very smooth dark mirror metal."""
    sc = scratches(size, rng, 120, (6, 40), strength=(0.2, 0.7))
    alb = tint(srgb([0.40, 0.41, 0.45]), noise(size, rng, size / 10), 0.03)
    return Layers(alb, -0.0015 * sc, 0.08 + 0.10 * sc, np.ones((size, size), bool))


def stainless_blasted(size, rng, pmm):
    """Bead-blasted stainless barrel: light matte metal with a sparkling blast grain over faint turning marks,
    carbon fouling blooms, a few brass smears and scratches from the slide."""
    blast = 0.7 * noise(size, rng, 0.65) + 0.3 * noise(size, rng, 1.6)
    lathe = noise(size, rng, 12.0 / pmm, 0.6)
    mott = fbm(size, rng, size / 6, 4)
    foul = smoothstep(0.3, 1.8, fbm(size, rng, size / 18, 5))
    smear = smoothstep(0.7, 1.0, smudges(size, rng, 2.0 / pmm, 0.02, aniso=3.0))
    sc = scratch_field(size, rng, pmm, 60, (0.5, 8), angle=0, spread=0.3)
    alb = tint(srgb([0.62, 0.62, 0.61]), 0.03 * blast + 0.012 * mott + 0.01 * lathe, 1.0)
    alb = lerp(alb, srgb([0.42, 0.41, 0.40]), 0.2 * foul)
    alb = lerp(alb, srgb([0.72, 0.60, 0.40]), 0.35 * smear)
    alb = lerp(alb, srgb([0.72, 0.72, 0.72]), 0.4 * sc)
    h = groove(sc, 0.004)
    rough = 0.45 + 0.02 * blast + 0.03 * mott + 0.08 * foul - 0.15 * sc - 0.05 * smear
    return Layers(alb, h, rough, np.ones((size, size), bool), relief=0.003 * blast + 0.0008 * lathe)


def nitride_slide(size, rng, pmm):
    """Black-nitrided steel slide: dark satin metal with fly-cut milling arcs along the slide, a fine speckled
    grain, holster-polished patches, spots rubbed bright, scratches mostly along the slide, fingerprints."""
    mill = milling(size, rng, pmm, 0.4, 0.15)
    grain = noise(size, rng, 0.7)
    mott = fbm(size, rng, size / 5, 4)
    holster = smudges(size, rng, size / 6, 0.3, aniso=2.0)
    rub = smoothstep(0.3, 1.0, smudges(size, rng, 1.5 / pmm, 0.02))
    sc = scratch_field(size, rng, pmm, 140, (0.6, 20), angle=0, spread=0.3, cluster=0.3 + 0.7 * holster)
    sc2 = scratch_field(size, rng, pmm, 50, (0.4, 5), strength=(0.2, 0.6))
    s = np.maximum(sc, sc2)
    oil = np.clip(0.7 * prints(size, rng, pmm, 2) + 0.5 * smudges(size, rng, size / 9, 0.15), 0, 1)
    alb = tint(srgb([0.235, 0.238, 0.25]), 0.05 * mott + 0.02 * mill + 0.03 * grain, 1.0)
    alb = lerp(alb, srgb([0.40, 0.40, 0.41]), 0.45 * rub)
    alb = lerp(alb, srgb([0.48, 0.48, 0.49]), 0.55 * s)
    h = groove(s, 0.005)
    rough = 0.42 + 0.04 * mott + 0.04 * mill + 0.015 * grain - 0.10 * holster - 0.12 * rub - 0.12 * s - 0.10 * oil
    return Layers(alb, h, rough, np.ones((size, size), bool), relief=0.004 * mill + 0.0006 * grain)


def titanium_blasted(size, rng):
    """Dark bead-blasted titanium (knife handle channels): soft grey metal, rubbed smoother where held."""
    blast = noise(size, rng, 0.8)
    held = smoothstep(0.5, 1.6, fbm(size, rng, size / 6, 3))
    alb = tint(srgb([0.37, 0.38, 0.41]), 0.6 * blast + 0.5 * fbm(size, rng, size / 10, 3), 0.03)
    h = 0.003 * blast + 0.001 * noise(size, rng, 3)
    rough = 0.47 + 0.03 * blast - 0.14 * held
    return Layers(alb, h, rough, np.ones((size, size), bool))


def satin_blade(size, rng, pmm):
    """Satin-finished stainless blade: fine grind lines along the blade (u), faint tarnish, hairline scratches."""
    # Grinding follows physical millimetres, not a fixed pixel count. Broad belt passes carry
    # the sheen; hairlines live in normals, never in a deep displacement or painted black stripe.
    lines = 0.7 * noise(size, rng, 12.0 / pmm, max(0.65, 0.12 / pmm)) \
        + 0.3 * noise(size, rng, 3.0 / pmm, max(0.65, 0.22 / pmm))
    passes = noise(size, rng, 28.0 / pmm, 2.4 / pmm)
    tarn = smoothstep(0.8, 2.0, fbm(size, rng, size / 6, 3))
    sc = scratch_field(size, rng, pmm, 32, (0.6, 8.0), angle=0, spread=0.18, strength=(0.15, 0.5))
    alb = tint(srgb([0.745, 0.75, 0.765]), 0.008 * lines + 0.012 * passes, 1.0)
    alb = lerp(alb, srgb([0.66, 0.62, 0.55]), 0.12 * tarn)
    rough = 0.255 + 0.035 * lines + 0.045 * passes + 0.045 * tarn + 0.025 * sc
    return Layers(alb, groove(sc, 0.002), rough, np.ones((size, size), bool), relief=0.0008 * lines)



def polished_edge(size, rng):
    """Sharpened bevel: mirror-ish steel with stone marks across the edge (along v)."""
    lines = noise(size, rng, 0.5, 30)
    alb = tint(srgb([0.80, 0.805, 0.82]), lines, 0.015)
    h = 0.0008 * lines
    rough = 0.13 + 0.04 * lines
    return Layers(alb, h, rough, np.ones((size, size), bool))


# ============================================================================================ recipes: coatings & plastics
def parkerized(size, rng, pmm):
    """Manganese phosphate (parkerising): ~0.3 mm crystal clusters of slightly different tone, a grey that drifts
    between greenish and brownish, oil-darkened glossy patches and fingerprints (phosphate holds oil), burnished
    handling zones, scratches that crush the coating lighter and the deepest ones bright steel, pinholes."""
    cells = max(8, int(round(size * pmm / 0.3)))
    f1, _, cid = worley(size, rng, cells)
    facet = std(rng.random(cells * cells)[cid])
    crystal = 0.6 * facet + 0.4 * std(f1)
    grain = noise(size, rng, 0.6)
    mott = fbm(size, rng, size / 5, 4)
    hue = fbm(size, rng, size / 4, 3)
    smear = smudges(size, rng, size / 14, 0.25)
    oil = np.clip(0.6 * smear + 0.25 * prints(size, rng, pmm, 2), 0, 1)
    burnish = smudges(size, rng, size / 10, 0.15, aniso=1.8)
    sc = scratch_field(size, rng, pmm, 90, (0.5, 12), cluster=0.3 + 0.7 * burnish)
    bare = smoothstep(0.55, 0.9, sc)
    pit = pits(size, rng, pmm, 0.8, (0.05, 0.15))
    base = lerp(srgb([0.20, 0.205, 0.195]), srgb([0.215, 0.205, 0.19]), smoothstep(-1.2, 1.2, hue))
    alb = tint(base, 0.07 * crystal + 0.05 * mott + 0.03 * grain, 1.0)
    alb = alb * (1 - 0.05 * smear - 0.03 * burnish)[..., None]
    alb = lerp(alb, srgb([0.31, 0.31, 0.30]), 0.5 * sc)
    metal = bare > 0.5
    alb = np.where(metal[..., None], srgb([0.56, 0.56, 0.56]), alb)
    alb = lerp(alb, alb * 0.7, 0.6 * pit)
    h = groove(sc, 0.006) - 0.01 * pit
    rough = np.where(metal, 0.30, 0.74 + 0.04 * crystal + 0.03 * mott - 0.20 * oil - 0.18 * burnish - 0.15 * sc
                     + 0.10 * pit)
    return Layers(alb, h, rough, metal, f0=0.045, relief=0.004 * crystal + 0.0015 * grain)


def anodized_black(size, rng, pmm):
    """Hard-anodised, bead-blasted aluminium (optics, laser, stock): fine blast grain, a black that drifts a little
    blue or warm, oily fingerprints and smudges (glossier), rubbed scuffs, scratches that craze the coating lighter
    and the deepest ones bright aluminium, a few dust specks."""
    blast = 0.7 * noise(size, rng, 0.65) + 0.3 * noise(size, rng, 1.5)
    mott = fbm(size, rng, size / 5, 4)
    hue = fbm(size, rng, size / 3, 3)
    oil = np.clip(0.8 * prints(size, rng, pmm, 3) + 0.6 * smudges(size, rng, size / 8, 0.25), 0, 1)
    zone = smudges(size, rng, size / 8, 0.25)
    scuff = blur(scratch_field(size, rng, pmm, 50, (3, 25), width=3, curve=0.5, cluster=zone,
                               strength=(0.15, 0.45)), 1.2)
    sc = scratch_field(size, rng, pmm, 45, (0.4, 10), cluster=0.2 + 0.8 * zone, strength=(0.3, 1.0))
    bare = smoothstep(0.6, 0.95, sc)
    dust = specks(size, rng, pmm, 0.6)
    base = lerp(srgb([0.125, 0.127, 0.137]), srgb([0.132, 0.128, 0.13]), smoothstep(-1, 1, hue))
    alb = tint(base, 0.05 * mott + 0.035 * blast, 1.0)
    alb = lerp(alb, srgb([0.19, 0.19, 0.20]), 0.6 * scuff)
    alb = lerp(alb, srgb([0.26, 0.26, 0.27]), 0.5 * sc)
    metal = bare > 0.5
    alb = np.where(metal[..., None], srgb([0.80, 0.81, 0.82]), alb)
    alb = lerp(alb, srgb([0.42, 0.40, 0.37]), 0.7 * dust)
    h = groove(sc, 0.006) - 0.002 * scuff + 0.004 * dust
    rough = np.where(metal, 0.28, 0.50 + 0.04 * blast + 0.03 * mott - 0.18 * oil - 0.14 * scuff - 0.10 * sc
                     + 0.25 * dust)
    # Machining visible through the hard coat, kept below the bead-blast grain. The broad
    # cut pass modulates specular response rather than making black optics look dusty grey.
    tool = milling(size, rng, pmm, pitch_mm=0.65, sweep=0.08)
    rough += 0.022 * tool
    return Layers(alb, h, rough, metal, f0=0.05, relief=0.0014 * blast + 0.0008 * tool)


def cerakote(size, rng, pmm, colour, heat=False):
    """Sprayed ceramic coating: multi-scale orange peel and overspray grain, faint mottling, a satin sheen that is
    glossier where handled, fingerprints, scratches showing the lighter undercoat, dust; optional heat tint and
    carbon soot streaks along the bore axis (suppressor)."""
    peel = 0.5 * noise(size, rng, 0.9 / pmm) + 0.35 * noise(size, rng, 0.35 / pmm) + 0.15 * noise(size, rng, 2.5 / pmm)
    spray = noise(size, rng, 0.6)
    mott = fbm(size, rng, size / 5, 4)
    handled = smudges(size, rng, size / 7, 0.3)
    oil = np.clip(0.6 * prints(size, rng, pmm, 2) + 0.4 * smudges(size, rng, size / 9, 0.2), 0, 1)
    sc = scratch_field(size, rng, pmm, 70, (0.6, 15), cluster=0.25 + 0.75 * handled, strength=(0.2, 0.8))
    dust = specks(size, rng, pmm, 0.5)
    alb = tint(srgb(colour), 0.025 * mott + 0.012 * peel + 0.02 * spray, 1.0)
    rough = 0.60 + 0.025 * peel + 0.03 * mott + 0.03 * spray - 0.10 * handled - 0.12 * oil
    if heat:
        hb = smoothstep(0.2, 1.6, fbm(size, rng, size / 5, 5))
        soot = smoothstep(0.5, 1.7, fbm(size, rng, size / 9, 5))
        soot = soot * (0.55 + 0.45 * smoothstep(-1, 1, noise(size, rng, size / 8, size / 40)))
        alb = lerp(alb, srgb([0.22, 0.17, 0.13]), 0.5 * hb)
        alb = lerp(alb, srgb([0.095, 0.09, 0.085]), 0.7 * soot)
        rough = rough + 0.12 * soot - 0.04 * hb
    alb = lerp(alb, alb * 1.6 + 0.05, 0.5 * sc)
    alb = lerp(alb, srgb([0.40, 0.38, 0.35]), 0.6 * dust)
    h = groove(sc, 0.005) + 0.004 * dust
    rough = rough - 0.10 * sc + 0.20 * dust
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.045, relief=0.006 * peel + 0.0015 * spray)


def polymer_textured(size, rng, colour=(0.135, 0.135, 0.14)):
    """Moulded polymer frame with a fine EDM texture: tiny round bumps, matte, a few scuffs."""
    f1, _, _ = worley(size, rng, size // 4)
    bumps = smoothstep(0.55, 0.0, f1)
    mott = fbm(size, rng, size / 6, 4)
    scuff = scratches(size, rng, 50, (20, 90), width=2, strength=(0.2, 0.6))
    alb = tint(srgb(colour), 0.05 * mott + 0.03 * std(bumps), 1.0)
    alb = lerp(alb, alb * 1.35 + 0.02, 0.5 * scuff)
    h = 0.018 * bumps + 0.002 * noise(size, rng, 1) - 0.004 * scuff
    rough = 0.72 - 0.08 * bumps + 0.04 * mott - 0.18 * scuff
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.04)


def polymer_moulded(size, rng, pmm, colour=(0.165, 0.165, 0.17)):
    """Injection-moulded, glass-filled polymer (P9 frame, AWP stock): an EDM mould texture (sand-like grain over
    soft ~0.5 mm bumps), faint flow lines and sink, glass-fibre flecks, peaks polished glossier where hands rub,
    dust settled in the grain, stress-whitened scuffs and scratches."""
    cells = max(8, int(round(size * pmm / 0.55)))
    f1, _, _ = worley(size, rng, cells)
    bumps = smoothstep(0.75, 0.0, f1)
    grain = 0.6 * noise(size, rng, 0.7) + 0.4 * noise(size, rng, 1.3)
    flow = noise(size, rng, 25.0 / pmm, 4.0 / pmm)
    sink = fbm(size, rng, size / 4, 3)
    mott = fbm(size, rng, size / 6, 4)
    fib = scratch_field(size, rng, pmm, int((size * pmm) ** 2 / 40), (0.2, 0.5), curve=0.0, strength=(0.2, 0.6))
    handled = smudges(size, rng, size / 7, 0.35)
    peak = smoothstep(0.5, 1.0, bumps) * handled
    dust = smoothstep(0.45, 0.0, bumps) * smoothstep(-0.3, 1.2, fbm(size, rng, size / 9, 3))
    sc = scratch_field(size, rng, pmm, 80, (0.6, 12), cluster=0.2 + 0.8 * handled, strength=(0.2, 0.7))
    scuff = blur(scratch_field(size, rng, pmm, 30, (4, 20), curve=0.6, cluster=handled, strength=(0.1, 0.35)), 1.5)
    c = srgb(colour)
    alb = tint(c, 0.022 * mott + 0.02 * grain + 0.015 * std(bumps), 1.0)
    alb = lerp(alb, c * 1.12 + 0.012, 0.5 * fib)
    alb = lerp(alb, srgb([0.36, 0.34, 0.31]), 0.22 * dust)
    alb = lerp(alb, c * 1.3 + 0.045, np.clip(0.6 * sc + 0.45 * scuff, 0, 1))
    h = 0.010 * bumps + groove(sc, 0.006)
    rough = 0.68 + 0.04 * mott + 0.03 * grain - 0.05 * bumps - 0.16 * peak + 0.02 * flow + 0.08 * dust \
        - 0.12 * scuff - 0.06 * sc
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.04,
                  relief=0.004 * grain + 0.004 * sink + 0.001 * flow)


def stipple(size, rng, pmm, colour=(0.15, 0.15, 0.155)):
    """Grip stippling: overlapping ~1.15 mm round dimples with raised rims where they meet, a second shallower
    pass, fine grain inside; the rims polished by hands, dust in the bottoms."""
    cells = max(8, int(round(size * pmm / 1.15)))
    f1, f2, cid = worley(size, rng, cells)
    rv = rng.random(cells * cells)[cid]
    wx, wy = fbm(size, rng, 0.8 / pmm, 3), fbm(size, rng, 0.8 / pmm, 3)
    f1 = warp(f1, 0.12 / pmm * wx, 0.12 / pmm * wy)
    f2 = warp(f2, 0.12 / pmm * wx, 0.12 / pmm * wy)
    radius = 0.42 + 0.14 * rv
    bowl = (1 - smoothstep(0.0, 1.0, f1 / radius)) ** 0.8
    rim = np.exp(-((f1 - radius) / 0.07) ** 2) * smoothstep(0.02, 0.2, f2 - f1)
    f1b, _, _ = worley(size, rng, int(cells * 1.7))
    bowl2 = 1 - smoothstep(0.0, 0.5, f1b)
    hb = 0.05 * rim - 0.26 * bowl - 0.05 * bowl2
    micro = 0.012 * noise(size, rng, 0.8) + 0.006 * noise(size, rng, 2)
    peak = smoothstep(0.55, 0.95, norm01(hb))
    low = smoothstep(0.35, 0.0, norm01(hb))
    dust = low * smoothstep(-0.5, 1.0, fbm(size, rng, size / 8, 3))
    c = srgb(colour)
    alb = tint(c, 0.04 * fbm(size, rng, size / 6, 3) + 0.02 * noise(size, rng, 0.8), 1.0)
    alb = lerp(alb, c * 1.25 + 0.015, 0.6 * peak)
    alb = lerp(alb, srgb([0.36, 0.34, 0.31]), 0.3 * dust)
    rough = 0.82 - 0.22 * peak + 0.06 * dust
    return Layers(alb, hb, rough, np.zeros((size, size), bool), f0=0.04, relief=micro)


def rubber(size, rng, pmm):
    """Butt pad / eyepiece rubber: a moulded pebble grain (two sizes), fine pores, chalky bloom, slick patches
    where it is handled, dust between the pebbles."""
    cells = max(8, int(round(size * pmm / 0.7)))
    f1, f2, _ = worley(size, rng, cells)
    pebble = smoothstep(0.0, 0.22, f2 - f1) ** 0.6
    f1b, f2b, _ = worley(size, rng, int(cells * 2.3))
    pebble2 = smoothstep(0.0, 0.25, f2b - f1b) ** 0.6
    pore = pits(size, rng, pmm, 8, (0.04, 0.12))
    grain = noise(size, rng, 0.7)
    mott = fbm(size, rng, size / 5, 4)
    bloom = smoothstep(0.3, 1.6, fbm(size, rng, size / 7, 4))
    shine = smudges(size, rng, size / 8, 0.25)
    dust = (1 - pebble) * smoothstep(-0.3, 1.2, fbm(size, rng, size / 10, 3))
    h = 0.035 * pebble + 0.012 * pebble2 - 0.03 * pore
    alb = tint(srgb([0.135, 0.135, 0.14]), 0.05 * mott + 0.03 * grain, 1.0)
    alb = lerp(alb, srgb([0.25, 0.25, 0.25]), 0.25 * bloom)
    alb = lerp(alb, srgb([0.33, 0.31, 0.28]), 0.35 * dust)
    alb = lerp(alb, alb * 0.7, pore)
    rough = 0.86 + 0.04 * bloom - 0.16 * shine * pebble + 0.05 * dust
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.035, relief=0.003 * grain)


def painted_steel(size, rng, pmm, paint=(0.13, 0.13, 0.14), coverage=0.003, vertical=False):
    """Semi-gloss enamel over phosphated, stamped steel (AK receiver and magazines, P9 magazine): orange peel and a
    gentle waviness of the sheet in the reflections, uneven sheen, dust caught in the paint, a few rust blisters,
    and layered wear - scuffs polish the paint, deeper marks show the grey phosphate primer, the deepest bright
    steel. A tiling texture cannot know where the edges are, so wear stays sparse (it reads as specks at a
    distance)."""
    ang = np.pi / 2 if vertical else 0.0
    peel = 0.6 * noise(size, rng, 0.7 / pmm) + 0.4 * noise(size, rng, 2.0 / pmm)
    sheet = fbm(size, rng, 14.0 / pmm, 3)
    mott = fbm(size, rng, size / 5, 4)
    sheen = smudges(size, rng, size / 6, 0.4)
    handled = smudges(size, rng, size / 7, 0.3)
    sc = scratch_field(size, rng, pmm, 70, (1, 25), angle=ang, spread=0.4, cluster=0.25 + 0.75 * handled,
                       strength=(0.15, 0.6))
    sc2 = scratch_field(size, rng, pmm, 40, (0.4, 5), strength=(0.2, 0.8))
    scuff = blur(scratch_field(size, rng, pmm, 25, (4, 25), angle=ang, spread=0.5, curve=0.5, cluster=handled,
                               strength=(0.1, 0.35)), 1.5)
    s = np.maximum(sc, sc2)
    chips = _wear_mask(size, rng, coverage, size / 90)
    w = np.maximum(s, blur(chips, 1.2))
    primer = smoothstep(0.45, 0.55, w)
    bare = smoothstep(0.8, 0.9, w)
    metal = bare > 0.5
    blister = pits(size, rng, pmm, 0.6, (0.15, 0.4))
    dust = specks(size, rng, pmm, 1.0)
    alb = tint(srgb(paint), 0.05 * mott + 0.015 * peel, 1.0)
    alb = lerp(alb, alb * 1.3 + 0.012, np.clip(0.7 * scuff + 0.5 * s, 0, 1))
    alb = lerp(alb, srgb([0.28, 0.285, 0.28]), primer)
    alb = np.where(metal[..., None], tint(srgb([0.52, 0.52, 0.53]), noise(size, rng, 2), 0.04), alb)
    alb = lerp(alb, srgb([0.36, 0.34, 0.31]), 0.5 * dust)
    alb = lerp(alb, srgb([0.25, 0.17, 0.12]), 0.25 * blister)
    h = -0.012 * primer - 0.004 * bare + 0.015 * blister + groove(s, 0.004) + 0.003 * dust
    rough = 0.40 + 0.04 * mott + 0.02 * peel - 0.07 * sheen - 0.06 * scuff - 0.05 * s + 0.30 * dust + 0.05 * blister
    rough = np.where(metal, 0.32, rough * (1 - primer) + 0.62 * primer)
    relief = 0.025 * sheet + 0.0025 * peel
    if vertical:
        # Drawn magazine sheet and insertion wear: longitudinal, softly broken polishing
        # lanes, not extra tiling silver chips. Keep coating/primer/substrate masks intact.
        draw = noise(size, rng, 0.45 / pmm, 22.0 / pmm)
        lane = smoothstep(0.3, 1.3, noise(size, rng, 2.5 / pmm, 32.0 / pmm))
        contact = lane * (0.4 + 0.6 * handled) * (1 - primer)
        rough = rough - 0.085 * contact + 0.018 * draw
        alb = lerp(alb, alb * 1.08, contact)
        relief += 0.0012 * draw
    return Layers(alb, h, rough, metal, f0=0.045, porosity=0.05 + 0.25 * primer, relief=relief)


def g10(size, rng, colour=(0.56, 0.12, 0.10)):
    """G10 scales: layers of glass cloth in epoxy (contour bands), fine weave texture, matte."""
    lay = fbm(size, rng, size / 4, 4)
    bands = 0.5 + 0.5 * np.sin(lay * 6.0)
    period = 6
    k = size // period
    y, x = np.mgrid[0:size, 0:size] / size * 2 * np.pi * k
    weave = np.sin(x) * np.sin(y)
    alb = tint(srgb(colour), 0.035 * (bands - 0.5) + 0.04 * weave, 1.0)
    h = 0.012 * weave + 0.003 * noise(size, rng, 2) + 0.002 * bands
    rough = 0.60 + 0.05 * (bands - 0.5)
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.045)


def lacquered_wood(size, rng, pmm):
    """AK furniture: stained birch under a red-brown lacquer, grain along u (the stock's length). Mostly straight,
    unevenly spaced growth rings (soft earlywood, crisp latewood); fibres, fine pores and ray flecks all follow the
    same flow; faint curl figure and blotchy stain uptake. The lacquer has brush texture and uneven sheen, with
    dents, finish-deep scratches, rubbed-through patches of paler bare wood and grime in pores and dents."""
    y, x = np.mgrid[0:size, 0:size] / size
    tile_mm = size * pmm
    rings = max(8, int(round(tile_mm / 2.4)))
    sway = std(blur(rng.standard_normal((size, size)), size / 3, size / 14))
    years = std(blur(rng.standard_normal((size, size)), size, 3.0 / pmm))
    wig = std(blur(rng.standard_normal((size, size)), 12.0 / pmm, 1.5 / pmm))
    disp = 2.2 * sway + 0.35 * years + 0.06 * wig                 # in rings
    phase = rings * y + disp
    t = phase - np.floor(phase)
    late = smoothstep(0.45, 0.93, t) ** 1.6 * (1 - smoothstep(0.95, 1.0, t))
    dpx = (disp / rings * size).astype(np.float32)

    def along(img):                                                 # into the grain's flow
        return warp(img, np.zeros_like(dpx), dpx)
    fibre = along(0.6 * noise(size, rng, 6.0 / pmm, 0.55) + 0.4 * noise(size, rng, 1.5 / pmm, 0.8))
    pores = along(np.clip(std(blur(rng.standard_normal((size, size)), 0.9 / pmm, 0.6)) - 2.3, 0, None))
    rays = along(np.clip(std(blur(rng.standard_normal((size, size)), 0.35 / pmm, 0.6)) - 2.6, 0, None))
    curl = np.sin(2 * np.pi * (max(1, int(round(tile_mm / 9.0))) * x + 0.3 * fbm(size, rng, size / 10, 3)))
    curl = curl * smoothstep(0.0, 1.3, fbm(size, rng, size / 4, 3))
    streak = along(noise(size, rng, 60.0 / pmm, 4.0 / pmm))
    blotch = fbm(size, rng, size / 5, 4)
    warmth = fbm(size, rng, size / 3, 3)
    early = lerp(srgb([0.47, 0.245, 0.125]), srgb([0.43, 0.21, 0.11]), smoothstep(-1, 1, warmth))
    latec = srgb([0.29, 0.13, 0.063])
    alb = lerp(early, latec, 0.62 * late)
    alb = tint(alb, 0.05 * fibre + 0.06 * blotch + 0.02 * curl + 0.07 * streak, 1.0)
    alb = lerp(alb, latec * 0.7, np.clip(0.5 * pores, 0, 0.7))
    alb = lerp(alb, early * 1.15, np.clip(0.5 * rays, 0, 0.5))
    # the finish and its wear
    handled = smudges(size, rng, size / 6, 0.3)
    worn = smoothstep(0.4, 1.0, smudges(size, rng, size / 30, 0.03))
    dents = np.clip(dots(size, rng, int(25 * (tile_mm / 100) ** 2), 0.9 / pmm), 0, 1.2)
    sc = scratch_field(size, rng, pmm, int(60 * (tile_mm / 100) ** 2), (1, 30), cluster=0.2 + 0.8 * handled,
                       strength=(0.2, 0.8))
    brush = noise(size, rng, 4.0 / pmm, 0.8 / pmm)
    alb = lerp(alb, alb * 1.2 + srgb([0.05, 0.035, 0.02]), 0.45 * worn)
    alb = lerp(alb, alb * 0.8, 0.4 * np.clip(dents, 0, 1))
    alb = lerp(alb, alb * 1.18 + 0.02, 0.5 * sc)
    h = 0.006 * late - 0.018 * np.clip(pores, 0, 2) - 0.045 * dents + groove(sc, 0.012) - 0.004 * worn
    rough = 0.30 + 0.04 * late + 0.03 * brush + 0.10 * np.clip(pores, 0, 1) - 0.06 * handled + 0.32 * worn \
        + 0.15 * sc + 0.04 * blotch
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.045, porosity=0.15 + 0.45 * worn,
                  relief=0.002 * fibre + 0.0015 * brush)


def digital_camo(size, rng, pmm):
    """Digital camo paint (4 colours, ~4.7 mm pixels with half-size detail) on a textured polymer surface."""
    block = max(4, int(round(4.7 / pmm)))
    nb = size // block
    big = fbm(nb * 4, rng, nb * 0.6, 3)[::4, ::4]
    mid = fbm(nb * 4, rng, nb * 0.25, 3)[::4, ::4]
    field = 0.65 * big + 0.35 * mid
    q = np.quantile(field, [0.30, 0.58, 0.82])
    idx = np.digitize(field, q)
    img = np.kron(idx, np.ones((block, block), int))
    half = block // 2
    sub = size // half
    flip = rng.random((sub, sub)) < 0.18
    alt = np.roll(img, half, axis=int(rng.integers(0, 2)))
    img = np.where(np.kron(flip, np.ones((half, half), bool)), alt, img)
    cols = np.array([[0.74, 0.67, 0.51], [0.60, 0.51, 0.36], [0.40, 0.31, 0.21], [0.33, 0.36, 0.23]])
    base = polymer_textured(size, rng)
    alb = cols[img] * (1 + 0.04 * fbm(size, rng, size / 8, 3))[..., None]
    alb = lerp(alb, alb * 1.25 + 0.03, 0.5 * scratches(size, rng, 40, (20, 80), strength=(0.2, 0.6)))
    return Layers(alb, base.height, np.clip(base.rough - 0.04, 0, 1), base.metal, f0=0.045)


# ============================================================================================ recipes: skins
def iridescent_titanium(size, rng):
    """aurora body: pale polished titanium (the shader adds the thin-film colours), fine brushing along u."""
    lines = noise(size, rng, 50, 0.6)
    drift = fbm(size, rng, size / 3, 3)
    hue = fbm(size, rng, size / 4, 3)
    alb = srgb([0.78, 0.79, 0.83]) * (1 + 0.03 * drift)[..., None]
    alb = alb + 0.025 * np.stack([hue, -hue * 0.3, -hue], -1)
    h = 0.0012 * lines + 0.0006 * noise(size, rng, 1)
    rough = 0.20 + 0.03 * lines + 0.03 * drift
    return Layers(alb, h, rough, np.ones((size, size), bool))


def aurora_accent(size, rng, pmm):
    """aurora accents: smoked carbon base with flowing light streams (emission mask for the flow effect)."""
    weave = carbon_weave(size, pmm)
    y, x = np.mgrid[0:size, 0:size] / size
    w = fbm(size, rng, size / 5, 3)
    streams = np.zeros((size, size))
    for k, (freq, width, amp) in enumerate(((3, 0.018, 1.0), (5, 0.010, 0.8), (2, 0.026, 0.6))):
        phase = freq * x + 0.18 * np.sin(2 * np.pi * (y * 2 + k * 0.3)) + 0.25 * w + rng.random()
        d = np.abs(phase - np.round(phase))
        streams = np.maximum(streams, amp * np.exp(-(d / width) ** 2))
    glow = np.clip(streams, 0, 1)
    alb = tint(srgb([0.055, 0.06, 0.075]), 0.25 * weave, 1.0)
    alb = lerp(alb, srgb([0.45, 0.95, 1.0]), smoothstep(0.15, 0.8, glow))
    h = 0.008 * weave - 0.004 * glow
    rough = 0.24 + 0.04 * weave
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.05, emission=glow ** 1.3)


def carbon_weave(size, pmm):
    """2x2 twill carbon weave height (-1..1), ~3 mm tows."""
    tow = max(6, int(round(3.0 / pmm)))
    k = size // tow
    y, x = np.mgrid[0:size, 0:size]
    cx, cy = (x // tow), (y // tow)
    fx, fy = (x % tow) / tow, (y % tow) / tow
    over = ((cx + cy) // 2) % 2 == 0                    # twill: diagonal steps of two
    bump_x = np.sin(np.pi * fy)                         # warp tow runs along x, rounded across y
    bump_y = np.sin(np.pi * fx)
    h = np.where(over, bump_x * 0.8 + 0.2 * np.sin(np.pi * fx), bump_y * 0.8 + 0.2 * np.sin(np.pi * fy))
    return h * 2 - 1


def carbon(size, rng, pmm):
    """Clear-coated carbon fibre (aurora / spare): twill weave, glossy."""
    weave = carbon_weave(size, pmm)
    alb = tint(srgb([0.07, 0.072, 0.078]), 0.35 * weave, 1.0)
    return Layers(alb, 0.01 * weave, 0.18 + 0.03 * weave, np.zeros((size, size), bool), f0=0.05)


def satin_titanium_dark(size, rng):
    """aurora fallback metal: satin dark titanium."""
    lines = noise(size, rng, 40, 0.6)
    alb = tint(srgb([0.42, 0.44, 0.49]), 0.03 * lines + 0.03 * fbm(size, rng, size / 6, 3), 1.0)
    return Layers(alb, 0.0012 * lines, 0.30 + 0.04 * lines, np.ones((size, size), bool))


def magma_rock(size, rng, pmm):
    """magma: black volcanic rock split by glowing cracks (emission mask), hot pores, rough basalt surface."""
    f1, f2, cid = worley(size, rng, 9)
    wx, wy = fbm(size, rng, size / 10, 4), fbm(size, rng, size / 10, 4)
    f1, f2 = warp(f1, 9 * wx, 9 * wy), warp(f2, 9 * wx, 9 * wy)
    cid = warp(cid.astype(np.float32), 9 * wx, 9 * wy)
    edge = f2 - f1
    wob = fbm(size, rng, size / 16, 4)
    crack = np.exp(-((edge + 0.03 * wob) / 0.045) ** 2)
    f1b, f2b, _ = worley(size, rng, 23)
    crack2 = np.exp(-(((f2b - f1b) + 0.04 * wob) / 0.035) ** 2) * smoothstep(0.2, 1.2, fbm(size, rng, size / 6, 3))
    cracks = np.clip(np.maximum(crack, 0.75 * crack2), 0, 1)
    rock = fbm(size, rng, size / 10, 6)
    pores = np.clip(-std(blur(rng.standard_normal((size, size)), 1.2)) - 1.6, 0, None)
    plate = std((np.round(cid) * 37 % 11).astype(float))
    alb = tint(srgb([0.085, 0.078, 0.074]), 0.18 * rock + 0.06 * plate, 1.0)
    hot = smoothstep(0.25, 0.95, cracks)
    core = smoothstep(0.7, 1.0, cracks)
    alb = lerp(alb, srgb([0.95, 0.35, 0.06]), hot)
    alb = lerp(alb, srgb([1.0, 0.85, 0.40]), core)
    h = 0.12 * rock + 0.04 * plate - 0.35 * cracks - 0.05 * np.clip(pores, 0, 1)
    rough = np.clip(0.86 - 0.12 * smoothstep(0.5, 1.5, rock), 0, 1)
    glow = np.clip(cracks ** 1.4 + 0.5 * np.clip(pores, 0, 1) * smoothstep(0.2, 0.6, crack + crack2), 0, 1)
    return Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.04, emission=glow)


def heat_tinted_steel(size, rng):
    """magma metal: charred steel with heat colours (straw, bronze, purple, blue) and soot."""
    t = norm01(fbm(size, rng, size / 4, 4))
    cols = palette(t, [(0.0, [0.22, 0.22, 0.24]), (0.35, [0.58, 0.47, 0.27]), (0.55, [0.50, 0.30, 0.22]),
                       (0.72, [0.42, 0.26, 0.42]), (0.88, [0.24, 0.30, 0.55]), (1.0, [0.28, 0.32, 0.40])])
    soot = smoothstep(0.4, 1.8, fbm(size, rng, size / 16, 4))
    lines = noise(size, rng, 30, 0.7)
    alb = tint(cols, 0.03 * lines, 1.0)
    alb = lerp(alb, alb * 0.35, 0.8 * soot)
    rough = 0.36 + 0.04 * lines + 0.35 * soot
    h = 0.0015 * lines + 0.003 * soot
    return Layers(alb, h, rough, np.ones((size, size), bool))


def galaxy_blade(size, rng):
    """galaxy blade: deep-space nebula under a glossy clear coat (the shader adds parallax stars)."""
    q = np.stack([fbm(size, rng, size / 5, 5), fbm(size, rng, size / 5, 5)], -1)
    neb = fbm(size, rng, size / 4, 6)
    neb = warp(neb, 30 * q[..., 0], 30 * q[..., 1])
    hue = norm01(warp(fbm(size, rng, size / 3, 4), 20 * q[..., 1], 20 * q[..., 0]))
    dens = smoothstep(-0.4, 1.6, neb)
    dust = smoothstep(0.5, 1.6, fbm(size, rng, size / 10, 5))
    cloud = palette(hue, [(0.0, [0.10, 0.16, 0.62]), (0.3, [0.42, 0.12, 0.62]), (0.55, [0.88, 0.22, 0.60]),
                          (0.75, [0.20, 0.62, 0.72]), (1.0, [0.12, 0.20, 0.58])])
    space = srgb([0.018, 0.016, 0.05])
    alb = lerp(space, cloud, 0.85 * dens ** 1.2)
    alb = lerp(alb, space * 0.6, 0.7 * dust)
    stars = np.zeros((size, size), np.float32)
    n = int(size * size / 900)
    ys, xs = rng.integers(0, size, n), rng.integers(0, size, n)
    stars[ys, xs] = rng.random(n) ** 3
    stars = np.clip(blur(stars, 0.6) * 6, 0, 1)
    alb = np.clip(alb + stars[..., None] * srgb([0.9, 0.92, 1.0]), 0, 1)
    h = 0.0005 * noise(size, rng, 1)
    return Layers(alb, h, np.full((size, size), 0.06), np.zeros((size, size), bool), f0=0.05,
                  emission=np.clip(stars * 0.5, 0, 1))


def galaxy_handle(size, rng):
    """galaxy handle: violet-black anodised finish with a fine metal-flake sparkle."""
    blast = noise(size, rng, 0.8)
    flakes = np.zeros((size, size))
    n = size * size // 70
    flakes[rng.integers(0, size, n), rng.integers(0, size, n)] = rng.random(n)
    metal = flakes > 0.55
    alb = tint(srgb([0.055, 0.045, 0.085]), 0.05 * blast + 0.06 * fbm(size, rng, size / 6, 3), 1.0)
    alb = np.where(metal[..., None], srgb([0.72, 0.62, 0.95]), alb)
    rough = np.where(metal, 0.16, 0.34 + 0.03 * blast)
    return Layers(alb, 0.0015 * blast, rough, metal, f0=0.05)


def doppler_blade(size, rng):
    """doppler blade: candy-anodised swirls (black, ruby, pink, violet, blue) on polished steel."""
    q = np.stack([fbm(size, rng, size / 4, 4), fbm(size, rng, size / 4, 4)], -1)
    r = np.stack([warp(q[..., 0], 40 * q[..., 1], 40 * q[..., 0]), warp(q[..., 1], 40 * q[..., 0], 40 * q[..., 1])], -1)
    f = norm01(warp(fbm(size, rng, size / 3, 5), 60 * r[..., 0], 60 * r[..., 1]))
    t = (f * 1.6 + 0.25 * r[..., 0]) % 1.0
    cols = palette(t, [(0.0, [0.08, 0.03, 0.06]), (0.18, [0.55, 0.05, 0.14]), (0.36, [0.95, 0.36, 0.62]),
                       (0.55, [0.55, 0.22, 0.86]), (0.74, [0.18, 0.36, 0.92]), (0.90, [0.10, 0.06, 0.20]),
                       (1.0, [0.08, 0.03, 0.06])])
    lines = noise(size, rng, 50, 0.6)
    alb = tint(cols, 0.02 * lines, 1.0)
    h = 0.0006 * lines
    rough = 0.08 + 0.02 * lines
    return Layers(alb, h, rough, np.ones((size, size), bool))


def dlc_black(size, rng):
    """doppler handle: polished black DLC-coated steel, hairline scratches."""
    sc = scratches(size, rng, 140, (8, 60), strength=(0.2, 0.7))
    alb = tint(srgb([0.20, 0.20, 0.22]), noise(size, rng, size / 10), 0.03)
    alb = lerp(alb, srgb([0.35, 0.35, 0.37]), 0.5 * sc)
    return Layers(alb, -0.0015 * sc, 0.11 + 0.10 * sc, np.ones((size, size), bool))


def damascus(size, rng, pmm):
    """Etched damascus: wavy folded layers; the dark etched layers sit lower and are rougher."""
    y, x = np.mgrid[0:size, 0:size] / size
    warp1 = 0.22 * fbm(size, rng, size / 9, 4) + 0.08 * fbm(size, rng, size / 30, 3)
    layers = 0.5 + 0.5 * np.sin(2 * np.pi * (24 * y + 0.3 * np.sin(2 * np.pi * x * 2) + warp1 * 3.2))
    ladder = 0.5 + 0.5 * np.sin(2 * np.pi * (6 * x + 0.4 * fbm(size, rng, size / 12, 3)))
    layers = np.clip(layers + 0.18 * (ladder - 0.5), 0, 1) ** 1.4
    grain = noise(size, rng, 1.2)
    bright = smoothstep(0.35, 0.65, layers)
    alb = lerp(srgb([0.30, 0.30, 0.31]), srgb([0.80, 0.80, 0.82]), bright)
    alb = tint(alb, grain, 0.03)
    h = 0.006 * bright + 0.0008 * grain
    rough = 0.36 - 0.18 * bright + 0.03 * grain
    return Layers(alb, h, rough, np.ones((size, size), bool))


def tiger_tooth(size, rng):
    """Tiger tooth: amber-gold anodised steel with dark diagonal brush strokes, glossy."""
    y, x = np.mgrid[0:size, 0:size] / size
    n = fbm(size, rng, size / 10, 4)
    m = fbm(size, rng, size / 40, 3)
    stripes = np.sin(2 * np.pi * (5 * (x + y) + 0.22 * n + 0.05 * m))
    breakup = smoothstep(-0.6, 0.4, fbm(size, rng, size / 12, 3))
    dark = smoothstep(0.55, 0.92, stripes) * breakup
    lines = noise(size, rng, 40, 0.6)
    base = srgb([0.96, 0.66, 0.20]) * (1 + 0.06 * fbm(size, rng, size / 6, 3))[..., None]
    alb = lerp(base, srgb([0.20, 0.08, 0.02]), 0.85 * dark)
    alb = tint(alb, lines, 0.02)
    rough = 0.13 + 0.03 * lines + 0.03 * dark
    return Layers(alb, 0.0006 * lines - 0.002 * dark, rough, np.ones((size, size), bool))


def fade(size, rng):
    """Fade: gold -> magenta -> violet -> blue along u (mirrored so it tiles), anodised polish."""
    y, x = np.mgrid[0:size, 0:size] / size
    u = 0.5 - 0.5 * np.cos(2 * np.pi * (x + 0.04 * fbm(size, rng, size / 6, 3)))
    cols = palette(u, [(0.0, [0.97, 0.80, 0.32]), (0.35, [0.95, 0.38, 0.64]), (0.68, [0.56, 0.30, 0.88]),
                       (1.0, [0.25, 0.46, 0.96])])
    lines = noise(size, rng, 40, 0.6)
    alb = tint(cols, 0.03 * fbm(size, rng, size / 10, 3) + 0.015 * lines, 1.0)
    return Layers(alb, 0.0006 * lines, 0.12 + 0.03 * lines, np.ones((size, size), bool))


# ============================================================================================ the sets
def _r(fn, *args, **kw):
    """Recipe adapter: fn(size, rng[, pmm], ...) -> Layers; pmm is passed when the recipe asks for it."""
    def run(d: SetDef, rng):
        import inspect
        params = inspect.signature(fn).parameters
        if "pmm" in params:
            return fn(d.size, rng, *args, pmm=px_mm(d), **kw)
        return fn(d.size, rng, *args, **kw)
    return run


SETS = {
    # shared by several models
    "common/steel_dark": SetDef(_r(steel_dark), 1024, 0.10, 101),
    "common/brass": SetDef(_r(brass), 512, 0.05, 102),
    "common/polished_steel": SetDef(_r(polished_steel), 512, 0.04, 103),
    "common/parkerized": SetDef(_r(parkerized), 1024, 0.12, 104),
    "common/anodized_black": SetDef(_r(anodized_black), 1024, 0.12, 105),
    "common/rubber": SetDef(_r(rubber), 1024, 0.10, 106),
    # P9
    "p9/polymer": SetDef(_r(polymer_moulded), 1024, 0.15, 201),
    "p9/grip": SetDef(_r(stipple), 1024, 0.15, 202),
    "p9/slide": SetDef(_r(nitride_slide), 1024, 0.15, 203),
    "p9/barrel": SetDef(_r(stainless_blasted), 1024, 0.12, 204),
    "p9/mag": SetDef(_r(painted_steel, paint=(0.135, 0.135, 0.14), coverage=0.0015, vertical=True), 1024, 0.12, 205),
    # AK-47
    "ak47/wood": SetDef(_r(lacquered_wood), 2048, 0.30, 301),
    "ak47/steel": SetDef(_r(painted_steel, coverage=0.003), 1024, 0.15, 302),
    "ak47/mag": SetDef(_r(painted_steel, paint=(0.14, 0.13, 0.125), coverage=0.003, vertical=True), 1024, 0.15, 303),
    # butterfly knife
    "butterfly/blade": SetDef(_r(satin_blade), 1024, 0.15, 401),
    "butterfly/blade_edge": SetDef(_r(polished_edge), 512, 0.075, 402),
    "butterfly/handle": SetDef(_r(titanium_blasted), 512, 0.075, 403),
    "butterfly/handle_inlay": SetDef(_r(g10), 512, 0.075, 404),
    # AWP (section 23): black cerakote action, olive-drab chassis stock and stippled grip
    "awp/receiver": SetDef(_r(cerakote, colour=(0.13, 0.13, 0.135)), 1024, 0.15, 701),
    "awp/stock": SetDef(_r(polymer_moulded, colour=(0.40, 0.45, 0.27)), 1024, 0.20, 702),
    "awp/grip": SetDef(_r(stipple, colour=(0.38, 0.43, 0.26)), 1024, 0.15, 703),
    # attachments
    "attachment/suppressor": SetDef(_r(cerakote, colour=(0.14, 0.14, 0.145), heat=True), 1024, 0.12, 501),
    # skins (section 21)
    "skin/aurora/body": SetDef(_r(iridescent_titanium), 512, 0.15, 601),
    "skin/aurora/accent": SetDef(_r(aurora_accent), 512, 0.15, 602),
    "skin/aurora/metal": SetDef(_r(satin_titanium_dark), 512, 0.12, 603),
    "skin/magma/rock": SetDef(_r(magma_rock), 512, 0.20, 611),
    "skin/magma/metal": SetDef(_r(heat_tinted_steel), 512, 0.15, 612),
    "skin/galaxy/blade": SetDef(_r(galaxy_blade), 512, 0.15, 621),
    "skin/galaxy/handle": SetDef(_r(galaxy_handle), 512, 0.075, 622),
    "skin/doppler/blade": SetDef(_r(doppler_blade), 512, 0.15, 631),
    "skin/doppler/handle": SetDef(_r(dlc_black), 512, 0.075, 632),
    "skin/common/dark_chrome": SetDef(_r(dark_chrome), 256, 0.04, 641),
    "skin/camo/polymer": SetDef(_r(digital_camo), 512, 0.15, 651),
    "skin/damascus/blade": SetDef(_r(damascus), 512, 0.10, 661),
    "skin/tiger/blade": SetDef(_r(tiger_tooth), 512, 0.10, 671),
    "skin/fade/blade": SetDef(_r(fade), 512, 0.15, 681),
}

# Texture sets of later weapons live in modules of their own (so several weapons can be worked on at once): each
# module has `sets(pt)` -> {name: SetDef}, `pt` being this module (its recipes and helpers). Missing modules are skipped.
SET_MODULES = ("pbr_sets_tommy", "pbr_sets_ks3", "pbr_sets_usp", "pbr_sets_dagger", "pbr_sets_stocks",
               "pbr_sets_neon", "pbr_sets_karambit", "pbr_sets_equipment",
               "pbr_sets_bomb", "pbr_sets_camos",
               "pbr_sets_uzi", "pbr_sets_p90", "pbr_sets_mp5", "pbr_sets_sten", "pbr_sets_ssg08", "pbr_sets_calico", "pbr_sets_scar", "pbr_sets_m4a1", "pbr_sets_jackhammer",
               "pbr_sets_crowbar", "pbr_sets_knuckles",
               "pbr_sets_handcannon", "pbr_sets_vector", "pbr_sets_baton", "pbr_sets_ammo_crate", "pbr_sets_blood",
               "pbr_sets_deagle", "pbr_sets_revolver", "pbr_sets_armor")


def _load_set_modules():
    import importlib
    if HERE not in sys.path:                # run as a script under `python -I`: the folder is not on the path
        sys.path.insert(0, HERE)
    me = sys.modules[__name__]
    for module in SET_MODULES:
        try:
            mod = importlib.import_module(module)
        except ModuleNotFoundError as e:
            if e.name == module:
                continue
            raise
        SETS.update(mod.sets(me))


_load_set_modules()

# Offline art direction only: retain shared textures, dimensions and runtime material bindings.
# Fine polished surfaces must not receive the same sixfold normal gain as coarse moulded grips.
import json as _json
with open(os.path.join(HERE, "surface_quality.json"), encoding="utf-8") as _f:
    SURFACE_QUALITY = _json.load(_f)
for _name, _detail in SURFACE_QUALITY["detail"].items():
    if _name not in SETS or not 0 <= _detail <= 1:
        raise ValueError(f"Invalid surface detail profile: {_name}")
    SETS[_name].detail = _detail


def set_path(name, suffix=""):
    return os.path.join(ASSETS, "textures", "pbr", *name.split("/")[:-1], name.split("/")[-1] + suffix + ".png")


def _save(arr, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.ascontiguousarray(arr)).save(path, format="PNG", optimize=True, compress_level=9)


def build_set(name):
    d = SETS[name]
    rng = np.random.default_rng(d.seed)
    lay = d.recipe(d, rng)
    alb, nmap, smap = pack(lay, d)
    _save(alb, set_path(name))
    _save(nmap, set_path(name, "_n"))
    _save(smap, set_path(name, "_s"))
    return sum(os.path.getsize(set_path(name, s)) for s in ("", "_n", "_s"))


def build(names=None):
    want = [n for n in SETS if not names or any(n == a or n.startswith(a) for a in names)]
    total = 0
    for name in want:
        size = build_set(name)
        total += size
        print(f"pbr/{name}: {SETS[name].size}px, {SETS[name].tile:.3f} m per repeat (uvScale {uv_scale(name)}), "
              f"{size / 1024:.0f} KiB")
    print(f"{len(want)} texture sets, {total / 1024 / 1024:.1f} MiB")


if __name__ == "__main__":
    build(sys.argv[1:])
