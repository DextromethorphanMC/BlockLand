"""Spray textures (VIEWMODEL_SPEC section 29): textures/spray/<id>.png, 256 x 256 RGBA with straight alpha.

Original sticker designs: a bold fill with light shading and a little spray-paint grain, a dark outline and a pale
die-cut border around it; drawn at 4x and box-filtered down, so every edge is soft (2-4 px), and kept inside a
transparent margin of at least MARGIN px. Deterministic."""
import math
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import textures as tx                     # noqa: E402

SIZE = 256
SS = 4                         # supersampling
N = SIZE * SS
MARGIN = 6                     # transparent border (px at 256); the design lives inside it
OUTLINE = 5.5 * SS             # dark outline width (supersampled px)
BORDER = 5.0 * SS              # pale die-cut border outside the outline


# ------------------------------------------------------------------------------------------------ drawing
class Canvas:
    """A supersampled RGBA canvas (float 0..1) with shape helpers in 0..1 design coordinates (y down)."""

    def __init__(self):
        self.rgb = np.zeros((N, N, 3))
        self.a = np.zeros((N, N))

    @staticmethod
    def _mask(draw_fn):
        img = Image.new("L", (N, N), 0)
        draw_fn(ImageDraw.Draw(img))
        return np.asarray(img, dtype=np.float64) / 255.0

    @staticmethod
    def p(x, y):
        return (x * N, y * N)

    def paint(self, mask, colour, shade=0.0, alpha=1.0):
        """Lays `colour` over the canvas through `mask`; `shade` lightens the top / darkens the bottom of the mask."""
        m = np.clip(mask, 0, 1) * alpha
        c = np.broadcast_to(np.asarray(colour, float), (N, N, 3)).copy()
        if shade:
            ys = np.nonzero(m.max(axis=1) > 0.01)[0]
            if len(ys):
                y0, y1 = ys[0], ys[-1] + 1
                t = np.clip((np.arange(N) - y0) / max(1, y1 - y0), 0, 1)[:, None, None]
                c = c * (1 + shade * (0.5 - t) * 2)
        self.rgb = self.rgb * (1 - m[..., None]) + np.clip(c, 0, 1) * m[..., None]
        self.a = np.maximum(self.a, m)

    def polygon(self, pts, colour, shade=0.0, alpha=1.0):
        mask = self._mask(lambda d: d.polygon([self.p(x, y) for x, y in pts], fill=255))
        self.paint(mask, colour, shade, alpha)
        return mask

    def ellipse(self, cx, cy, rx, ry, colour, shade=0.0, alpha=1.0):
        mask = self._mask(lambda d: d.ellipse([self.p(cx - rx, cy - ry), self.p(cx + rx, cy + ry)], fill=255))
        self.paint(mask, colour, shade, alpha)
        return mask

    def line(self, pts, width, colour, alpha=1.0):
        w = max(1, int(width * N))

        def draw(d):
            d.line([self.p(x, y) for x, y in pts], fill=255, width=w, joint="curve")
            for x, y in (pts[0], pts[-1]):                                   # round caps
                d.ellipse([self.p(x, y)[0] - w / 2, self.p(x, y)[1] - w / 2,
                           self.p(x, y)[0] + w / 2, self.p(x, y)[1] + w / 2], fill=255)
        mask = self._mask(draw)
        self.paint(mask, colour, 0.0, alpha)
        return mask

    def cut(self, mask):
        """Makes the canvas transparent where `mask` is (holes: eye sockets, the paw's gaps...)."""
        self.a = self.a * (1 - np.clip(mask, 0, 1))


def _dilate(a, radius):
    r = max(1, int(round(radius)))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    return cv2.dilate((a > 0.5).astype(np.uint8), k).astype(np.float64)


def finish(c, outline=(0.07, 0.06, 0.08), border=(0.96, 0.95, 0.92), seed=0, grain=0.06):
    """Outline and die-cut border around the drawn design, spray grain, 4x box filter, margin check."""
    rng = np.random.default_rng(seed)
    inner = c.a
    ring = _dilate(inner, OUTLINE)
    outer = _dilate(inner, OUTLINE + BORDER)
    rgb = np.zeros((N, N, 3))
    rgb[:] = border
    rgb = np.where(ring[..., None] > 0, np.asarray(outline), rgb)
    rgb = rgb * (1 - inner[..., None]) + c.rgb * inner[..., None]
    alpha = np.maximum(outer, inner)
    # spray-paint grain on the fill (not the border), very fine
    noise = rng.standard_normal((N // 2, N // 2))
    noise = cv2.resize(noise, (N, N), interpolation=cv2.INTER_LINEAR)
    rgb = rgb * (1 + grain * noise[..., None] * inner[..., None])
    # box filter in premultiplied alpha (no dark fringes), then un-premultiply; the alpha gets a slight extra blur
    small_rgb = cv2.resize(np.clip(rgb, 0, 1) * alpha[..., None], (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    coverage = cv2.resize(alpha, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    out_rgb = small_rgb / np.maximum(coverage, 1e-4)[..., None]
    small_a = cv2.GaussianBlur(coverage, (0, 0), 0.7)
    # colour bleeds a few pixels past the coverage so the soft alpha edge never samples black
    bleed = cv2.dilate(out_rgb * (coverage[..., None] > 0.02), np.ones((5, 5), np.uint8))
    out_rgb = np.where(coverage[..., None] > 0.02, out_rgb, bleed)
    small_a[:MARGIN, :] = 0
    small_a[-MARGIN:, :] = 0
    small_a[:, :MARGIN] = 0
    small_a[:, -MARGIN:] = 0
    return np.dstack([np.clip(out_rgb, 0, 1), np.clip(small_a, 0, 1)])


# Designs keep inside 0.12 .. 0.88 so outline + border (~0.04) + margin fit.
def star_points(cx, cy, r_out, r_in, n=5, rot=-math.pi / 2):
    pts = []
    for i in range(2 * n):
        r = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def bl_cube():
    """An isometric block: grass-green top, two earthy sides with a pixel grid, and a bright 'BL' cut into the top."""
    c = Canvas()
    cx, top, s = 0.5, 0.14, 0.36
    h = s * 0.5
    up = [(cx, top), (cx + s, top + h), (cx, top + 2 * h), (cx - s, top + h)]
    left = [(cx - s, top + h), (cx, top + 2 * h), (cx, top + 2 * h + 0.40), (cx - s, top + h + 0.40)]
    right = [(cx, top + 2 * h), (cx + s, top + h), (cx + s, top + h + 0.40), (cx, top + 2 * h + 0.40)]
    c.polygon(left, (0.55, 0.36, 0.20), shade=0.15)
    c.polygon(right, (0.40, 0.25, 0.13), shade=0.15)
    c.polygon(up, (0.36, 0.78, 0.30), shade=0.10)
    # grass overhang on the sides
    for side, col in ((left, (0.30, 0.66, 0.24)), (right, (0.24, 0.55, 0.20))):
        (x0, y0), (x1, y1) = side[0], side[1]
        drip = [(x0, y0), (x1, y1)]
        for k in range(6, -1, -1):
            t = k / 6
            x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            drip.append((x, y + (0.07 if k % 2 else 0.045)))
        c.polygon(drip, col)
    # pixel grid on the dirt
    for side, col in ((left, (0.47, 0.30, 0.17)), (right, (0.33, 0.20, 0.10))):
        (ax, ay), (bx, by) = side[0], side[1]
        for i in range(1, 4):
            t = i / 4
            x = ax + (bx - ax) * t
            y = ay + (by - ay) * t
            c.line([(x, y + 0.07), (x, y + 0.40)], 0.008, col)
        for j in range(1, 4):
            dy = 0.07 + 0.33 * j / 4
            c.line([(ax, ay + dy), (bx, by + dy)], 0.008, col)
    # pixel grass on the top face: a few lighter and darker cells
    def iso(u, v):                     # u, v in 0..1 across the top face
        return (cx + (u - v) * s, top + (u + v) * h)
    rng = np.random.default_rng(21)
    for gx in range(5):
        for gy in range(5):
            pick = rng.random()
            if pick > 0.45:
                continue
            u0, v0 = 0.04 + gx * 0.19, 0.04 + gy * 0.19
            quad = [iso(u0, v0), iso(u0 + 0.17, v0), iso(u0 + 0.17, v0 + 0.17), iso(u0, v0 + 0.17)]
            c.polygon(quad, (0.52, 0.90, 0.40) if pick < 0.25 else (0.27, 0.64, 0.22))
    return finish(c, seed=1)


def skull():
    c = Canvas()
    bone = (0.94, 0.92, 0.85)
    c.ellipse(0.5, 0.42, 0.30, 0.28, bone, shade=0.12)
    c.polygon([(0.30, 0.52), (0.70, 0.52), (0.66, 0.78), (0.34, 0.78)], bone, shade=0.12)
    c.ellipse(0.5, 0.78, 0.17, 0.08, bone)
    holes = np.zeros((N, N))
    holes = np.maximum(holes, Canvas._mask(lambda d: d.ellipse([Canvas.p(0.29, 0.38), Canvas.p(0.46, 0.57)], fill=255)))
    holes = np.maximum(holes, Canvas._mask(lambda d: d.ellipse([Canvas.p(0.54, 0.38), Canvas.p(0.71, 0.57)], fill=255)))
    holes = np.maximum(holes, Canvas._mask(lambda d: d.polygon([Canvas.p(0.5, 0.58), Canvas.p(0.455, 0.67),
                                                                  Canvas.p(0.545, 0.67)], fill=255)))
    c.paint(holes, (0.10, 0.08, 0.10))
    for k in range(5):                                       # teeth gaps
        x = 0.38 + k * 0.06
        c.line([(x, 0.73), (x, 0.84)], 0.012, (0.25, 0.22, 0.22))
    c.ellipse(0.42, 0.28, 0.07, 0.04, (1.0, 1.0, 0.97), alpha=0.6)   # highlight
    return finish(c, seed=2)


def flame():
    c = Canvas()

    def tongue(scale, col, sh):
        pts = []
        for i in range(41):
            t = i / 40
            a = t * 2 * math.pi
            r = 0.5 + 0.5 * math.cos(a)                      # teardrop
            x = 0.5 + scale * 0.33 * math.sin(a) * (0.55 + 0.45 * math.cos(a))
            y = 0.86 - scale * (0.70 * (1 - r) + 0.06) - 0.03 * math.sin(3 * a) * scale
            pts.append((x, y))
        c.polygon(pts, col, shade=sh)
    tongue(1.0, (0.86, 0.12, 0.06), 0.2)
    # side licks
    c.polygon([(0.24, 0.72), (0.14, 0.46), (0.30, 0.60), (0.34, 0.80)], (0.86, 0.12, 0.06))
    c.polygon([(0.74, 0.70), (0.86, 0.40), (0.70, 0.56), (0.64, 0.80)], (0.86, 0.12, 0.06))
    tongue(0.72, (1.0, 0.52, 0.08), 0.15)
    tongue(0.42, (1.0, 0.88, 0.30), 0.10)
    return finish(c, seed=3)


def lightning():
    c = Canvas()
    bolt = [(0.58, 0.12), (0.26, 0.54), (0.46, 0.54), (0.36, 0.88), (0.76, 0.42), (0.55, 0.42), (0.68, 0.12)]
    c.polygon(bolt, (1.0, 0.80, 0.10), shade=0.25)
    c.polygon([(0.58, 0.16), (0.36, 0.48), (0.42, 0.48), (0.60, 0.20)], (1.0, 0.97, 0.70), alpha=0.8)
    return finish(c, seed=4)


def star():
    c = Canvas()
    c.polygon(star_points(0.5, 0.53, 0.38, 0.16), (1.0, 0.72, 0.08), shade=0.25)
    c.polygon(star_points(0.5, 0.53, 0.22, 0.095), (1.0, 0.92, 0.45))
    c.ellipse(0.44, 0.42, 0.035, 0.035, (1, 1, 1), alpha=0.9)
    return finish(c, seed=5)


def heart():
    c = Canvas()
    red = (0.90, 0.10, 0.22)
    c.ellipse(0.355, 0.38, 0.19, 0.19, red, shade=0.2)
    c.ellipse(0.645, 0.38, 0.19, 0.19, red, shade=0.2)
    c.polygon([(0.17, 0.43), (0.83, 0.43), (0.5, 0.86)], red, shade=0.2)
    c.ellipse(0.33, 0.32, 0.07, 0.05, (1.0, 0.75, 0.80), alpha=0.85)
    return finish(c, seed=6)


def gg():
    """Two chunky pixel-art G letters, slightly slanted, purple to pink."""
    c = Canvas()
    glyph = ["01110", "10001", "10000", "10111", "10001", "10001", "01110"]
    cell = 0.054
    for k, (ox, col) in enumerate(((0.14, (0.62, 0.30, 0.95)), (0.52, (0.98, 0.32, 0.68)))):
        for row, bits in enumerate(glyph):
            for col_i, b in enumerate(bits):
                if b != "1":
                    continue
                x = ox + col_i * cell + (6 - row) * 0.012
                y = 0.31 + row * cell
                c.polygon([(x, y), (x + cell * 1.02, y), (x + cell * 1.02 - 0.012, y + cell * 1.02),
                           (x - 0.012, y + cell * 1.02)], col, shade=0.15)
    return finish(c, seed=7)


def crown():
    c = Canvas()
    gold = (1.0, 0.74, 0.12)
    body = [(0.16, 0.36), (0.32, 0.56), (0.5, 0.26), (0.68, 0.56), (0.84, 0.36), (0.78, 0.74), (0.22, 0.74)]
    c.polygon(body, gold, shade=0.25)
    c.polygon([(0.22, 0.74), (0.78, 0.74), (0.78, 0.84), (0.22, 0.84)], (0.86, 0.56, 0.06))
    for x, y in ((0.16, 0.33), (0.5, 0.23), (0.84, 0.33)):
        c.ellipse(x, y, 0.045, 0.045, (1.0, 0.88, 0.40))
    for x, col in ((0.34, (0.15, 0.45, 0.95)), (0.5, (0.90, 0.12, 0.18)), (0.66, (0.15, 0.75, 0.40))):
        c.ellipse(x, 0.79, 0.032, 0.032, col)
    return finish(c, seed=8)


def paw():
    c = Canvas()
    pad = (0.98, 0.55, 0.66)
    c.polygon([(0.30, 0.66), (0.40, 0.52), (0.60, 0.52), (0.70, 0.66), (0.66, 0.80), (0.5, 0.78), (0.34, 0.80)],
              pad, shade=0.15)
    c.ellipse(0.5, 0.67, 0.19, 0.13, pad, shade=0.15)
    for (x, y, rx, ry) in ((0.24, 0.42, 0.075, 0.095), (0.39, 0.29, 0.075, 0.10), (0.61, 0.29, 0.075, 0.10),
                           (0.76, 0.42, 0.075, 0.095)):
        c.ellipse(x, y, rx, ry, pad, shade=0.12)
    return finish(c, outline=(0.30, 0.10, 0.16), seed=9)


def target():
    c = Canvas()
    for r, col in ((0.37, (0.92, 0.12, 0.12)), (0.29, (0.98, 0.97, 0.94)), (0.21, (0.92, 0.12, 0.12)),
                   (0.13, (0.98, 0.97, 0.94)), (0.06, (0.92, 0.12, 0.12))):
        c.ellipse(0.5, 0.5, r, r, col)
    for (a, b) in (((0.5, 0.10), (0.5, 0.28)), ((0.5, 0.72), (0.5, 0.90)), ((0.10, 0.5), (0.28, 0.5)),
                   ((0.72, 0.5), (0.90, 0.5))):
        c.line([a, b], 0.03, (0.12, 0.10, 0.12))
    return finish(c, seed=10)


def wings():
    """Two spread wings of layered leaf-shaped feathers, white fading into sky blue, a golden gem in the middle."""
    c = Canvas()

    def feather(bx, by, ang, length, width, col):
        dx, dy = math.cos(ang), math.sin(ang)
        nx, ny = -dy, dx
        left, right = [], []
        for i in range(13):
            t = i / 12
            w = width * math.sin(math.pi * min(1.0, t * 1.15)) ** 0.6 if t < 0.999 else 0.0
            x, y = bx + dx * length * t, by + dy * length * t
            left.append((x + nx * w, y + ny * w))
            right.append((x - nx * w, y - ny * w))
        c.polygon(left + right[::-1], col, shade=0.10)

    for side in (-1, 1):
        rows = ((6, 0.34, 0.040, (0.58, 0.80, 1.0)), (5, 0.25, 0.042, (0.78, 0.90, 1.0)),
                (4, 0.15, 0.040, (0.97, 0.99, 1.0)))
        for row, (count, length, width, col) in enumerate(rows):
            for k in range(count):
                deg = -58 + k * (78 / max(1, count - 1)) + row * 8
                ang = math.radians(deg)
                ang = math.pi - ang if side < 0 else ang
                bx, by = 0.5 + side * (0.06 + row * 0.02), 0.50 - row * 0.015
                feather(bx, by, ang, length * (1.0 - 0.12 * k / count), width, col)
    c.polygon(star_points(0.5, 0.52, 0.08, 0.035, n=4, rot=-math.pi / 2), (1.0, 0.80, 0.20), shade=0.2)
    return finish(c, outline=(0.10, 0.16, 0.30), seed=11)


def dragon():
    """A coiled serpent dragon: a tapering crimson body with a golden belly line and back spines, horned head."""
    c = Canvas()
    path = []
    for i in range(160):
        t = i / 159
        a = 1.15 * math.pi + t * 2.7 * math.pi
        r = 0.30 - 0.17 * t
        path.append((0.5 + r * math.cos(a), 0.52 + r * math.sin(a) * 0.92, t))
    # spines on the outer side
    for i in range(6, 150, 9):
        x, y, t = path[i]
        x2, y2, _ = path[i + 3]
        dx, dy = x2 - x, y2 - y
        dl = math.hypot(dx, dy) or 1
        nx, ny = dy / dl, -dx / dl                                  # outward for this winding
        w = 0.05 * (1 - t) + 0.012
        c.polygon([(x - dx * 0.4, y - dy * 0.4), (x + nx * (w + 0.045 * (1 - t)), y + ny * (w + 0.045 * (1 - t))),
                   (x2, y2)], (0.98, 0.70, 0.12))
    for i in range(len(path) - 1):
        x, y, t = path[i]
        w = 0.055 * (1 - t) + 0.012
        c.ellipse(x, y, w, w, (0.78, 0.08, 0.10), shade=0.0)
    for i in range(0, len(path) - 1, 2):
        x, y, t = path[i]
        w = (0.055 * (1 - t) + 0.012) * 0.35
        c.ellipse(x, y, w, w, (1.0, 0.78, 0.25))
    # head at the outer start of the coil
    hx, hy, _ = path[0]
    c.polygon([(hx - 0.02, hy - 0.10), (hx + 0.12, hy - 0.04), (hx + 0.16, hy + 0.03), (hx + 0.04, hy + 0.07),
               (hx - 0.06, hy + 0.04)], (0.78, 0.08, 0.10), shade=0.2)
    c.polygon([(hx - 0.01, hy - 0.09), (hx - 0.08, hy - 0.18), (hx + 0.03, hy - 0.08)], (0.98, 0.70, 0.12))
    c.polygon([(hx + 0.05, hy - 0.07), (hx + 0.02, hy - 0.17), (hx + 0.09, hy - 0.06)], (0.98, 0.70, 0.12))
    c.ellipse(hx + 0.07, hy - 0.02, 0.018, 0.014, (1.0, 0.92, 0.30))
    return finish(c, outline=(0.12, 0.04, 0.04), seed=12)


SPRAYS = {"bl_cube": bl_cube, "skull": skull, "flame": flame, "lightning": lightning, "star": star, "heart": heart,
          "gg": gg, "crown": crown, "paw": paw, "target": target, "wings": wings, "dragon": dragon}


def build(path):
    for name, fn in SPRAYS.items():
        tx.save_png(fn(), path("textures", "spray", f"{name}.png"))
    print(f"sprays: {len(SPRAYS)} textures ({', '.join(SPRAYS)})")


if __name__ == "__main__":
    import build_all
    build(build_all.path)
