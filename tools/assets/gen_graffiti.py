"""Wall graffiti (喷漆) textures and sounds: textures/graffiti/<id>.png (256 x 256 RGBA, straight alpha; frame strips
256 x 256·n), square previews textures/graffiti/icon/<id>.png for the special ones, sounds/graffiti/*.ogg + sounds.json
(locked merge). Data: data/blockland/graffiti/graffiti.json (written by hand; ids and paths must match).

Normal graffiti look sprayed, not stuck on: soft edges with a fine overspray halo and speckle, a dark outline line, drips
running down from the lower edges and grain in the fill. Special ones carry what their shader needs:
  neon      R = the tube's core line, G = its glow (blurred), A = max — the shader colours and lights it
  hologram  plain RGBA art; the shader tints it and adds scan lines, flicker and the projector shimmer
  particles RGBA art the client samples into glowing points (only its alpha and colour matter)
  layered   three layers (_0 shadow, _1 body, _2 ink and highlights) drawn at different depths for parallax
  animated  a vertical strip of frames
Deterministic.

python -I gen_graffiti.py            (or build_all.py graffiti)"""
import math
import os
import sys
import tempfile

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import textures as tx                                                       # noqa: E402
from gen_equipment_sounds import _loop                                       # noqa: E402
from gen_sounds import SR, band, click, encode, env_exp, finish as finish_sound, merge_sounds_json, reverb, ring, t_axis  # noqa: E402
from gen_sprays import Canvas, N, SS, star_points                           # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources", "assets", "blockland")
SIZE = 256
MARGIN = 4


def _out(*parts):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def _blur(a, sigma):
    return cv2.GaussianBlur(a, (0, 0), sigma)


def _dilate(a, radius):
    r = max(1, int(round(radius)))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    return cv2.dilate((a > 0.5).astype(np.uint8), k).astype(np.float64)


def _down(rgb, alpha):
    """Box filter in premultiplied alpha to SIZE, colour bled past the coverage, margin cleared."""
    small_rgb = cv2.resize(np.clip(rgb, 0, 1) * alpha[..., None], (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    coverage = cv2.resize(alpha, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    out_rgb = small_rgb / np.maximum(coverage, 1e-4)[..., None]
    bleed = cv2.dilate(out_rgb * (coverage[..., None] > 0.02), np.ones((5, 5), np.uint8))
    out_rgb = np.where(coverage[..., None] > 0.02, out_rgb, bleed)
    coverage[:MARGIN, :] = 0
    coverage[-MARGIN:, :] = 0
    coverage[:, :MARGIN] = 0
    coverage[:, -MARGIN:] = 0
    return np.dstack([np.clip(out_rgb, 0, 1), np.clip(coverage, 0, 1)])


def sprayed(c, outline=(0.06, 0.05, 0.07), seed=0, drips=7, outline_px=4.0, overspray=0.22):
    """The drawn design as spray paint on a wall: outline, rough edges, overspray halo, drips, grain."""
    rng = np.random.default_rng(seed)
    inner = c.a
    ring_mask = _dilate(inner, outline_px * SS)
    paint = np.maximum(inner, ring_mask)
    rgb = np.zeros((N, N, 3))
    rgb[:] = outline
    rgb = rgb * (1 - inner[..., None]) + c.rgb * inner[..., None]

    # rough, slightly fuzzy edges: noise eats into the boundary
    noise = cv2.resize(rng.random((N // 8, N // 8)), (N, N), interpolation=cv2.INTER_CUBIC)
    soft = _blur(paint, 2.2 * SS)
    edge = np.clip((soft - 0.25 - 0.35 * (noise - 0.5)) * 3.0, 0, 1)
    alpha = np.maximum(edge * (soft > 0.05), paint * 0.0)

    # drips: from the lowest painted pixel of a few columns, a thin streak with a round bead at its end
    cols = np.nonzero(paint.max(axis=0) > 0.5)[0]
    for _ in range(drips if len(cols) else 0):
        x = int(rng.choice(cols))
        ys = np.nonzero(paint[:, x] > 0.5)[0]
        y0 = int(ys[-1])
        length = int(N * (0.04 + rng.random() * 0.14))
        width = (2.0 + rng.random() * 2.5) * SS
        # the fill just above the outline runs (most drips), else the outline itself
        above = max(0, y0 - int(outline_px * SS * 1.8))
        col = (rgb[above, x] if inner[above, x] > 0.5 and rng.random() < 0.75 else rgb[max(0, y0 - 2), x]).copy()
        y1 = min(N - 6 * SS, y0 + length)
        yy, xx = np.mgrid[0:N, 0:N]
        streak = ((np.abs(xx - x) <= width * (1 - 0.5 * np.clip((yy - y0) / max(1, length), 0, 1))) & (yy >= y0) & (yy <= y1)).astype(np.float64)
        bead = (((xx - x) ** 2 + (yy - y1) ** 2) <= (width * 1.4) ** 2).astype(np.float64)
        drop = np.maximum(streak, bead)
        rgb = rgb * (1 - drop[..., None]) + col * drop[..., None]
        alpha = np.maximum(alpha, drop * 0.95)

    # overspray: a faint halo of fine speckle around everything
    halo = _blur(paint, 9.0 * SS)
    speckle = (rng.random((N, N)) < 0.18).astype(np.float64)
    halo_a = np.clip(halo * 1.6, 0, 1) * speckle * overspray * (1 - alpha)
    halo_col = _blur(rgb * paint[..., None], 9.0 * SS) / np.maximum(halo, 1e-4)[..., None]
    rgb = np.where(alpha[..., None] > 0.02, rgb, halo_col)
    alpha = np.clip(alpha + halo_a, 0, 1)

    # grain in the fill
    grain = cv2.resize(rng.standard_normal((N // 2, N // 2)), (N, N), interpolation=cv2.INTER_LINEAR)
    rgb = rgb * (1 + 0.06 * grain[..., None] * inner[..., None])
    return _down(rgb, alpha)


# ---------------------------------------------------------------------------------------------------- normal designs
def smile():
    c = Canvas()
    c.ellipse(0.5, 0.48, 0.34, 0.34, (1.0, 0.84, 0.12), shade=0.18)
    c.ellipse(0.38, 0.40, 0.045, 0.075, (0.08, 0.06, 0.06))
    c.ellipse(0.62, 0.40, 0.045, 0.075, (0.08, 0.06, 0.06))
    pts = [(0.30 + 0.40 * i / 20, 0.56 + 0.12 * math.sin(math.pi * i / 20)) for i in range(21)]
    c.line(pts, 0.035, (0.08, 0.06, 0.06))
    return sprayed(c, seed=11, drips=8)


def _bubble_letters(c, glyphs, colours, x0, y0, cell, slant=0.01):
    for k, (glyph, col) in enumerate(zip(glyphs, colours)):
        ox = x0 + k * (len(glyph[0]) + 1) * cell
        for row, bits in enumerate(glyph):
            for ci, b in enumerate(bits):
                if b != "1":
                    continue
                x = ox + ci * cell + (len(glyph) - row) * slant
                y = y0 + row * cell
                c.ellipse(x + cell / 2, y + cell / 2, cell * 0.72, cell * 0.72, col, shade=0.2)


B = ["1110", "1001", "1110", "1001", "1001", "1110"]
L = ["1000", "1000", "1000", "1000", "1000", "1111"]
G = ["0111", "1000", "1000", "1011", "1001", "0111"]


def bl_tag():
    c = Canvas()
    _bubble_letters(c, [B, L], [(0.15, 0.62, 1.0), (0.98, 0.30, 0.62)], 0.16, 0.26, 0.072)
    c.line([(0.14, 0.78), (0.86, 0.72)], 0.02, (1.0, 0.92, 0.25))                    # the tag's swoosh
    return sprayed(c, seed=12, drips=9)


def gg():
    c = Canvas()
    _bubble_letters(c, [G, G], [(0.55, 0.95, 0.30), (0.20, 0.85, 0.65)], 0.17, 0.27, 0.07, slant=0.02)
    return sprayed(c, seed=13, drips=7)


def heart():
    c = Canvas()
    pts = []
    for i in range(80):
        t = 2 * math.pi * i / 80
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((0.5 + x * 0.019, 0.47 - y * 0.019))
    c.polygon(pts, (0.92, 0.10, 0.20), shade=0.25)
    c.ellipse(0.40, 0.36, 0.06, 0.035, (1.0, 0.7, 0.75), alpha=0.7)
    return sprayed(c, seed=14, drips=10)


def paw():
    c = Canvas()
    col = (0.55, 0.32, 0.80)
    c.ellipse(0.5, 0.60, 0.17, 0.14, col, shade=0.15)
    for x, y in ((0.30, 0.42), (0.42, 0.31), (0.58, 0.31), (0.70, 0.42)):
        c.ellipse(x, y, 0.065, 0.08, col, shade=0.15)
    return sprayed(c, seed=15, drips=5)


def skull():
    c = Canvas()
    bone = (0.94, 0.92, 0.86)
    c.ellipse(0.5, 0.42, 0.29, 0.27, bone, shade=0.12)
    c.polygon([(0.31, 0.52), (0.69, 0.52), (0.65, 0.76), (0.35, 0.76)], bone, shade=0.12)
    hole = (0.09, 0.07, 0.09)
    c.ellipse(0.39, 0.46, 0.08, 0.09, hole)
    c.ellipse(0.61, 0.46, 0.08, 0.09, hole)
    c.polygon([(0.5, 0.58), (0.46, 0.66), (0.54, 0.66)], hole)
    for k in range(5):
        c.line([(0.39 + k * 0.055, 0.70), (0.39 + k * 0.055, 0.78)], 0.012, (0.25, 0.22, 0.22))
    return sprayed(c, seed=16, drips=6)


def crown():
    c = Canvas()
    gold = (1.0, 0.76, 0.12)
    c.polygon([(0.20, 0.70), (0.22, 0.34), (0.36, 0.52), (0.50, 0.28), (0.64, 0.52), (0.78, 0.34), (0.80, 0.70)], gold, shade=0.25)
    c.polygon([(0.20, 0.70), (0.80, 0.70), (0.80, 0.78), (0.20, 0.78)], (0.86, 0.58, 0.08))
    for x in (0.22, 0.50, 0.78):
        c.ellipse(x, 0.31 if x == 0.5 else 0.34, 0.035, 0.035, (0.95, 0.20, 0.30))
    return sprayed(c, seed=17, drips=8)


def bolt():
    c = Canvas()
    c.polygon([(0.56, 0.14), (0.28, 0.54), (0.47, 0.54), (0.38, 0.86), (0.72, 0.42), (0.52, 0.42), (0.62, 0.14)],
              (1.0, 0.90, 0.15), shade=0.25)
    return sprayed(c, seed=18, drips=6)


def eye():
    c = Canvas()
    pts = [(0.14 + 0.72 * i / 30, 0.5 - 0.22 * math.sin(math.pi * i / 30)) for i in range(31)]
    pts += [(0.86 - 0.72 * i / 30, 0.5 + 0.22 * math.sin(math.pi * i / 30)) for i in range(31)]
    c.polygon(pts, (0.96, 0.96, 0.92))
    c.ellipse(0.5, 0.5, 0.15, 0.15, (0.20, 0.75, 0.55), shade=0.2)
    c.ellipse(0.5, 0.5, 0.065, 0.065, (0.05, 0.05, 0.06))
    c.ellipse(0.46, 0.45, 0.025, 0.025, (1, 1, 1))
    return sprayed(c, seed=19, drips=9)


NORMAL = {"smile": smile, "bl_tag": bl_tag, "gg": gg, "heart": heart, "paw": paw, "skull": skull, "crown": crown,
          "bolt": bolt, "eye": eye}


# --------------------------------------------------------------------------------------------------- special designs
def _neon(stroke_fn, seed):
    """Neon tube art: the core line (R) and its glow (G) from a stroke mask drawn by `stroke_fn(canvas)`."""
    c = Canvas()
    stroke_fn(c)
    core = c.a
    glow = np.clip(_blur(core, 10 * SS) * 2.2 + _blur(core, 3 * SS) * 0.8, 0, 1)
    rng = np.random.default_rng(seed)
    core = core * (0.85 + 0.15 * rng.random((N, N)))          # tiny unevenness of the glass
    rgb = np.dstack([core, glow, np.zeros_like(core)])
    alpha = np.maximum(core, glow * 0.95)
    small = cv2.resize(rgb, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    a = cv2.resize(alpha, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    return np.dstack([np.clip(small, 0, 1), np.clip(a, 0, 1)])


def neon_wave():
    def draw(c):
        pts = [(0.12 + 0.76 * i / 60, 0.5 + 0.13 * math.sin(2 * math.pi * i / 30)) for i in range(61)]
        c.line(pts, 0.022, (1, 1, 1))
        pts = [(0.18 + 0.64 * i / 50, 0.68 + 0.06 * math.sin(2 * math.pi * i / 25 + 1.3)) for i in range(51)]
        c.line(pts, 0.016, (1, 1, 1))
        c.line([(0.20, 0.30), (0.80, 0.30)], 0.016, (1, 1, 1))
    return _neon(draw, 31)


def neon_heart():
    def draw(c):
        pts = []
        for i in range(121):
            t = 2 * math.pi * i / 120
            x = 16 * math.sin(t) ** 3
            y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
            pts.append((0.5 + x * 0.019, 0.47 - y * 0.019))
        c.line(pts, 0.022, (1, 1, 1))
    return _neon(draw, 32)


def _tint_icon(img, colour, bg=(0.04, 0.05, 0.08)):
    """A neon texture as it looks lit: colour × (core + glow) over a dark tile."""
    core, glow, a = img[..., 0], img[..., 1], img[..., 3]
    col = np.asarray(colour, float)
    light = col[None, None, :] * (glow[..., None] * 0.9) + np.clip(core[..., None] * (0.6 + col * 0.6), 0, 1)
    out = np.asarray(bg, float)[None, None, :] * (1 - np.clip(a, 0, 1)[..., None] * 0.2) + light
    return np.dstack([np.clip(out, 0, 1), np.ones_like(a)])


def hologram_cube():
    """An isometric BlockLand cube with a ring around it (the shader adds scan lines and the flicker)."""
    c = Canvas()
    top, left, right = (0.80, 0.98, 1.0), (0.35, 0.75, 0.92), (0.20, 0.55, 0.80)
    cx, cy, s = 0.5, 0.50, 0.24
    c.polygon([(cx, cy - s), (cx + s * 0.87, cy - s * 0.5), (cx, cy), (cx - s * 0.87, cy - s * 0.5)], top)
    c.polygon([(cx - s * 0.87, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx - s * 0.87, cy + s * 0.5)], left)
    c.polygon([(cx + s * 0.87, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx + s * 0.87, cy + s * 0.5)], right)
    ring = [(0.5 + 0.38 * math.cos(2 * math.pi * i / 64), 0.82 + 0.06 * math.sin(2 * math.pi * i / 64)) for i in range(65)]
    c.line(ring, 0.010, (0.6, 0.95, 1.0))
    edge = [(cx, cy - s), (cx + s * 0.87, cy - s * 0.5), (cx + s * 0.87, cy + s * 0.5), (cx, cy + s),
            (cx - s * 0.87, cy + s * 0.5), (cx - s * 0.87, cy - s * 0.5), (cx, cy - s)]
    c.line(edge, 0.008, (0.9, 1.0, 1.0))
    c.line([(cx, cy), (cx, cy + s)], 0.006, (0.9, 1.0, 1.0))
    rgb, alpha = c.rgb, c.a
    return _down(rgb, alpha)


def particles_phoenix():
    """A rising phoenix of warm colours (only sampled into points by the client)."""
    c = Canvas()
    for side in (-1, 1):
        for k in range(5):
            r = 0.36 - k * 0.05
            pts = [(0.5, 0.52)]
            for i in range(13):
                a = math.radians(200 - 120 * i / 12) if side < 0 else math.radians(-20 + 120 * i / 12)
                pts.append((0.5 + side * abs(math.cos(a)) * r, 0.52 - abs(math.sin(a)) * r * 0.9 + k * 0.02))
            col = (1.0, 0.45 + k * 0.1, 0.10 + k * 0.08)
            c.polygon(pts, col)
    c.ellipse(0.5, 0.44, 0.06, 0.09, (1.0, 0.85, 0.35))
    c.polygon([(0.46, 0.56), (0.54, 0.56), (0.58, 0.86), (0.5, 0.78), (0.42, 0.86)], (1.0, 0.35, 0.10))
    c.polygon(star_points(0.5, 0.30, 0.05, 0.02), (1.0, 0.95, 0.6))
    return _down(c.rgb, c.a)


def layered_bubble():
    """Bubble letters 'BL' in three layers: drop shadow, body, ink + highlights."""
    shadow, body, ink = Canvas(), Canvas(), Canvas()
    for canvas, col, dx in ((shadow, (0.05, 0.04, 0.08), 0.02), (body, None, 0.0)):
        for k, (glyph, colour) in enumerate(zip([B, L], [(0.30, 0.85, 1.0), (1.0, 0.55, 0.20)])):
            ox = 0.17 + k * 5 * 0.07 + dx
            for row, bits in enumerate(glyph):
                for ci, b in enumerate(bits):
                    if b == "1":
                        canvas.ellipse(ox + ci * 0.07 + 0.035, 0.28 + dx + row * 0.07 + 0.035, 0.062, 0.062,
                                       col if col is not None else colour, shade=0.0 if col else 0.25)
    # ink: the body's outline and a few white highlights
    outline = _dilate(body.a, 3.5 * SS) - (body.a > 0.5)
    ink.paint(np.clip(outline, 0, 1), (0.06, 0.05, 0.08))
    for x, y in ((0.22, 0.31), (0.29, 0.45), (0.57, 0.31), (0.57, 0.52)):
        ink.ellipse(x, y, 0.022, 0.014, (1, 1, 1), alpha=0.9)
    shadow_img = _down(shadow.rgb, _blur(shadow.a, 2.0 * SS) * 0.85)
    return [shadow_img, sprayed(body, seed=41, drips=6, outline_px=0.5, overspray=0.12), _down(ink.rgb, ink.a)]


def _composite(layers, shift=6):
    """The layers stacked with a small offset each, as a square preview."""
    out = np.zeros((SIZE, SIZE, 4))
    for i, img in enumerate(layers):
        off = (len(layers) - 1 - i) * shift
        moved = np.zeros_like(img)
        moved[:SIZE - off, :SIZE - off] = img[off:, off:] if off else img
        a = moved[..., 3:4]
        out[..., :3] = out[..., :3] * (1 - a) + moved[..., :3] * a
        out[..., 3:4] = np.maximum(out[..., 3:4], a)
    return out


def animated_smile(frames=8):
    """A smiley that bounces, squashes and winks: a vertical strip of frames."""
    strip = []
    for f in range(frames):
        t = f / frames
        bounce = abs(math.sin(math.pi * t))
        squash = 1 + 0.12 * (1 - bounce)
        c = Canvas()
        cy = 0.52 - 0.08 * bounce
        c.ellipse(0.5, cy, 0.30 * squash, 0.30 / squash, (1.0, 0.80, 0.10), shade=0.18)
        wink = f in (5, 6)
        c.ellipse(0.40, cy - 0.07, 0.04, 0.010 if wink else 0.065, (0.08, 0.06, 0.06))
        c.ellipse(0.60, cy - 0.07, 0.04, 0.065, (0.08, 0.06, 0.06))
        pts = [(0.33 + 0.34 * i / 20, cy + 0.06 + (0.09 + 0.03 * bounce) * math.sin(math.pi * i / 20)) for i in range(21)]
        c.line(pts, 0.03, (0.08, 0.06, 0.06))
        strip.append(sprayed(c, seed=50 + f, drips=0, overspray=0.12))
    return np.concatenate(strip, axis=0)


# ------------------------------------------------------------------------------------------------------------ sounds
def spray(seed):
    """A short burst from a spray can: the ball rattling once, the valve's hiss with its pressure falling."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.95)
    x = sum(click(t, 0.01 + k * 0.045, rng, 2500, 9000, 0.004, 0.35) for k in range(3))
    hiss = band(rng.standard_normal(len(t)), 2500, 11000) * np.clip((t - 0.12) / 0.04, 0, 1) * np.exp(-np.clip(t - 0.12, 0, None) * 1.6)
    hiss *= 0.75 + 0.25 * np.sin(2 * np.pi * 23 * t)
    return finish_sound(reverb(x + hiss * 0.8, 0.25, 0.12, seed=seed), -6.0, fade=0.15)


def neon_hum(seed=60):
    """A neon tube's transformer hum with a faint buzz and an occasional crackle (loops)."""
    rng = np.random.default_rng(seed)
    t = t_axis(3.25)
    hum = sum(np.sin(2 * np.pi * f * t) * a for f, a in ((100, 0.5), (200, 0.25), (300, 0.12), (400, 0.06)))
    buzz = band(rng.standard_normal(len(t)), 1500, 6000) * 0.05 * (0.6 + 0.4 * np.sin(2 * np.pi * 100 * t))
    crackle = sum(click(t, s, rng, 3000, 9000, 0.002, 0.25) for s in rng.uniform(0.2, 3.0, 4))
    return finish_sound(_loop(hum * 0.4 + buzz + crackle), -14.0, fade=0.0)


def hologram(seed=61):
    """The projector starting: a rising sweep, a soft chirp and a shimmer."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.9)
    f = 300 + 1500 * np.clip(t / 0.35, 0, 1) ** 2
    sweep = np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(t, 0.35, 0.01)
    chirp = ring(t, [2400, 3600, 4800], [0.12, 0.09, 0.07], [0.3, 0.2, 0.12], 0.32, rng)
    shimmer = band(rng.standard_normal(len(t)), 5000, 12000) * env_exp(t, 0.25, 0.02, 0.3) * 0.15
    return finish_sound(reverb(sweep * 0.5 + chirp + shimmer, 0.6, 0.3, seed=seed), -8.0)


def sparkle(seed=62):
    """Little bells falling into place: the points of a particle piece assembling."""
    rng = np.random.default_rng(seed)
    t = t_axis(1.4)
    x = np.zeros_like(t)
    for k in range(14):
        t0 = 0.02 + k * 0.07 + rng.random() * 0.03
        f = 2600 + rng.random() * 3200
        x += ring(t, [f, f * 2.01], [0.18, 0.08], [0.2, 0.06], t0, rng)
    return finish_sound(reverb(x, 0.8, 0.35, seed=seed), -9.0)


def wheel(seed=63):
    """The radial menu ticking over to another slot."""
    rng = np.random.default_rng(seed)
    t = t_axis(0.08)
    return finish_sound(click(t, 0.002, rng, 1800, 6000, 0.005, 1.0), -14.0, fade=0.02)


FILES = {"spray_1": lambda: spray(70), "spray_2": lambda: spray(71), "spray_3": lambda: spray(72),
         "neon_hum": neon_hum, "hologram": hologram, "sparkle": sparkle, "wheel": wheel}
EVENTS = {"graffiti.spray": ["spray_1", "spray_2", "spray_3"], "graffiti.neon_hum": ["neon_hum"],
          "graffiti.hologram": ["hologram"], "graffiti.sparkle": ["sparkle"], "graffiti.wheel": ["wheel"]}
#: subtitles.blockland.<event>: Chinese, English (added with tools/lang_add.py)
SUBTITLES = {"graffiti.spray": ("喷漆", "Spray paint hisses"), "graffiti.neon_hum": ("霓虹灯嗡嗡作响", "Neon hums"),
             "graffiti.hologram": ("全息投影启动", "Hologram starts"), "graffiti.sparkle": ("光点汇聚", "Sparks gather"),
             "graffiti.wheel": ("切换喷漆", "Graffiti selected")}


def sounds():
    tmp = tempfile.mkdtemp(prefix="blockland_graffiti_snd_")
    for name, fn in FILES.items():
        encode(fn(), _out("sounds", "graffiti", f"{name}.ogg"), tmp)
    merge_sounds_json(os.path.join(ROOT, "sounds.json"), {
        ev: {"sounds": [f"blockland:graffiti/{f}" for f in files], "subtitle": f"subtitles.blockland.{ev}"}
        for ev, files in EVENTS.items()})
    return len(FILES)


def textures():
    count = 0
    for name, fn in NORMAL.items():
        tx.save_png(fn(), _out("textures", "graffiti", f"{name}.png"))
        count += 1
    for name, fn, colour in (("neon_wave", neon_wave, (0.21, 0.91, 1.0)), ("neon_heart", neon_heart, (1.0, 0.24, 0.60))):
        img = fn()
        tx.save_png(img, _out("textures", "graffiti", f"{name}.png"))
        tx.save_png(_tint_icon(img, colour), _out("textures", "graffiti", "icon", f"{name}.png"))
        count += 1
    holo = hologram_cube()
    tx.save_png(holo, _out("textures", "graffiti", "hologram_cube.png"))
    icon = holo.copy()
    icon[..., :3] = icon[..., :3] * np.array([0.55, 1.0, 1.05]) + 0.05
    icon[::4, :, 3] *= 0.55                                                   # the scan lines it will have
    tx.save_png(np.clip(icon, 0, 1), _out("textures", "graffiti", "icon", "hologram_cube.png"))
    phoenix = particles_phoenix()
    tx.save_png(phoenix, _out("textures", "graffiti", "particles_phoenix.png"))
    dots = phoenix.copy()
    yy, xx = np.mgrid[0:SIZE, 0:SIZE]
    dots[..., 3] *= (((xx % 6) < 3) & ((yy % 6) < 3)).astype(np.float64)    # the points it becomes
    tx.save_png(dots, _out("textures", "graffiti", "icon", "particles_phoenix.png"))
    layers = layered_bubble()
    for i, layer in enumerate(layers):
        tx.save_png(layer, _out("textures", "graffiti", f"layered_bubble_{i}.png"))
    tx.save_png(_composite(layers), _out("textures", "graffiti", "icon", "layered_bubble.png"))
    strip = animated_smile()
    tx.save_png(strip, _out("textures", "graffiti", "animated_smile.png"))
    tx.save_png(strip[:SIZE], _out("textures", "graffiti", "icon", "animated_smile.png"))
    return count + 4


def build(_path=None):
    print("graffiti textures:", textures())
    print("graffiti sounds:", sounds())


if __name__ == "__main__":
    build()
