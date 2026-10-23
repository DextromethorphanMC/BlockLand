"""Texture set of the neon shader skin 霓虹回路 (VIEWMODEL_SPEC section 30), plugged into pbr_textures.SETS.

`skin/neon/base`: a dark, glossy blue-black lacquer over a circuit board. In our pipeline the `circuit` effect draws the
glowing traces itself (in the weapon's rest-pose space) and ignores this set's emission; under an OptiFine shader pack
the effect does not run, so the `_s` emission carries a static trace network of the same look (same cell size as the
skin's default density of 40 cells per metre: 6 cells per 0.15 m repeat), and the traces show faintly in the albedo
and the height as copper lines under the lacquer."""
import numpy as np

CELLS = 6                       # cells per repeat (0.15 m tile x 40 cells / m)
TILE = 0.15
WIDTH = 0.055                   # trace half-width in cells (as the shader)


def _edge(rng, shape, p):
    """Which cell edges a trace crosses (tileable: indices wrap)."""
    return rng.random(shape) < p


def _spoke(fx, fy, ex, ey):
    """Distance from (fx, fy) to the segment from the cell centre to the edge midpoint (ex, ey), in cells."""
    bx, by = ex - 0.5, ey - 0.5
    px, py = fx - 0.5, fy - 0.5
    h = np.clip((px * bx + py * by) / (bx * bx + by * by), 0, 1)
    return np.hypot(px - bx * h, py - by * h)


def circuit(size, rng):
    """(core 0..1, halo 0..1, chip body 0..1) of a tileable Manhattan trace network, like the shader's circuitPlane."""
    cell_px = size / CELLS
    y, x = np.mgrid[0:size, 0:size] + 0.5
    cx, cy = (x // cell_px).astype(int), (y // cell_px).astype(int)
    fx, fy = x / cell_px - cx, y / cell_px - cy
    # edge k of column c = the edge between cells c and c + 1 (wraps); more traces run along u than across
    right_edges = _edge(rng, (CELLS, CELLS), 0.62)     # [row, col]: right edge of cell (col, row)
    lower_edges = _edge(rng, (CELLS, CELLS), 0.34)     # [row, col]: lower edge (towards +v) of cell (col, row)
    chips = rng.random((CELLS, CELLS)) > 0.55
    right = right_edges[cy, cx]
    left = right_edges[cy, (cx - 1) % CELLS]
    down = lower_edges[cy, cx]
    up = lower_edges[(cy - 1) % CELLS, cx]
    big = np.full(fx.shape, 1e3)
    d = big.copy()
    degree = np.zeros(fx.shape)
    for on, ex, ey in ((right, 1.0, 0.5), (left, 0.0, 0.5), (down, 0.5, 1.0), (up, 0.5, 0.0)):
        d = np.where(on, np.minimum(d, _spoke(fx, fy, ex, ey)), d)
        degree += on
    soft = 1.0 / cell_px
    smooth = lambda e0, e1, v: np.clip((v - e0) / (e1 - e0), 0, 1) ** 2 * (3 - 2 * np.clip((v - e0) / (e1 - e0), 0, 1))
    core = 1 - smooth(WIDTH - soft, WIDTH + soft, d)
    halo = np.where(degree > 0, np.exp(-np.maximum(d - WIDTH, 0) * 11), 0)
    r = np.hypot(fx - 0.5, fy - 0.5)
    # dead ends: a pad with a via; junctions: a solder dot
    ring = 1 - smooth(0.045 - soft, 0.045 + soft, np.abs(r - 0.13))
    pad = np.maximum(core, ring) * smooth(0.045 - soft, 0.045 + soft, r)
    core = np.where(degree == 1, pad, core)
    core = np.where(degree >= 3, np.maximum(core, 1 - smooth(0.1 - soft, 0.1 + soft, r)), core)
    # empty cells: sometimes a chip (dark package, lit outline, two rows of pins)
    qx, qy = np.abs(fx - 0.5) - 0.27, np.abs(fy - 0.5) - 0.17
    box = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0)
    is_chip = (degree == 0) & chips[cy, cx]
    body = np.where(is_chip, 1 - smooth(-soft, soft, box), 0)
    outline = 1 - smooth(0.025 - soft, 0.025 + soft, np.abs(box))
    pin_x = np.clip(np.floor((fx - 0.3) / 0.1333 + 0.5), 0, 3) * 0.1333 + 0.3
    pins = 1 - smooth(0.035 - soft, 0.035 + soft, np.hypot(fx - pin_x, np.minimum(np.abs(fy - 0.24), np.abs(fy - 0.76))))
    core = np.where(is_chip, np.maximum(core, np.maximum(outline, pins)), core)
    halo = np.where(is_chip, np.maximum(halo, outline * 0.6), halo)
    return core, halo, body


def neon_lacquer(size, rng, pmm, pt):
    """Blue-black lacquer, glossy (clear coat), a faint orange peel; copper traces under it; the trace network as
    emission for shader packs."""
    core, halo, body = circuit(size, rng)
    peel = pt.fbm(size, rng, size / 40, 3)
    base = pt.srgb([0.035, 0.042, 0.066])
    copper = pt.srgb([0.13, 0.095, 0.065])
    alb = pt.tint(base, 0.12 * peel, 1.0)
    alb = pt.lerp(alb, pt.srgb([0.065, 0.07, 0.085]), body * 0.8)
    alb = pt.lerp(alb, copper, core * 0.7)
    height = 0.012 * core - 0.004 * body          # mm: traces a hair proud under the lacquer
    relief = 0.0015 * peel                        # orange peel only tilts the normal
    rough = 0.10 + 0.02 * peel + 0.04 * core
    emission = np.clip(core * 0.85 + halo * 0.18, 0, 1)
    return pt.Layers(alb, height, rough, np.zeros((size, size), bool), f0=0.05, emission=emission, relief=relief)


def sets(pt):
    def recipe(d, rng):
        return neon_lacquer(d.size, rng, pt.px_mm(d), pt)
    return {"skin/neon/base": pt.SetDef(recipe, 512, TILE, 691)}
