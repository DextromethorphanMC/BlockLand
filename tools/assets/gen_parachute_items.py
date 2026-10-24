"""The parachute's assets: the canopy / pack texture the client draws its code-built meshes with
(`textures/entity/parachute.png`, layout below), a 32 px icon, the held model (cuboids + palette: the packed rig) and
the item definition (the icon in the GUI, the held model everywhere else).

Texture layout (64 x 64 px; `client/parachute/CanopyRenderer` reads these regions):
  x  0-31, y  0-31  upper surface, primary colour (each cell uses one half: x 0-15 / 16-31 rib to middle, middle to rib)
  x 32-63, y  0-31  upper surface, secondary colour
  x  0-63, y 32-47  lower surface (both colours, darker), rows 46-47 the dark inside of the cell mouths
  x  0- 7, y 48-55  suspension lines            x 16-19, y 56-63 webbing (straps, risers)
  x  0-15, y 56-63  the pack's canvas           x 24-27, y 56-59 the red ripcord handle
"""
import numpy as np

import textures as tx
from gen_equipment_items import _box
from gen_items import write_json

KEY = "parachute"
PRIMARY = np.array([0.80, 0.16, 0.10])        # sRGB, rescue red-orange nylon
SECONDARY = np.array([0.93, 0.92, 0.88])      # off-white
CANVAS = np.array([0.30, 0.33, 0.21])         # olive pack
WEBBING = np.array([0.10, 0.10, 0.09])
LINE = np.array([0.78, 0.78, 0.74])
HANDLE = np.array([0.85, 0.10, 0.08])
METAL = np.array([0.62, 0.63, 0.64])


def ripstop(h, w, colour, rng, grid=4, strength=0.06):
    """Nylon with the ripstop grid and a little weave noise."""
    img = np.tile(colour, (h, w, 1)) * (1 - strength * 0.5 + strength * rng.random((h, w, 1)))
    img[::grid, :, :] *= 0.9
    img[:, ::grid, :] *= 0.9
    return img


def canopy_texture(seed=91):
    rng = np.random.default_rng(seed)
    img = np.zeros((64, 64, 4))
    img[..., 3] = 1
    img[0:32, 0:32, :3] = ripstop(32, 32, PRIMARY, rng)
    img[0:32, 32:64, :3] = ripstop(32, 32, SECONDARY, rng)
    # a seam where each half meets the rib and the stitching of the reinforcing tape along the chord
    for x0 in (0, 15, 16, 31, 32, 47, 48, 63):
        img[0:32, x0, :3] *= 0.8
    # sun-faded at the front, shadowed towards the trailing edge (v runs front to back)
    img[0:32, :, :3] *= np.linspace(1.05, 0.9, 32)[:, None, None]
    img[32:48, 0:32, :3] = ripstop(16, 32, PRIMARY * 0.62, rng)
    img[32:48, 32:64, :3] = ripstop(16, 32, SECONDARY * 0.66, rng)
    img[46:48, :, :3] = np.array([0.06, 0.03, 0.03])
    img[48:56, 0:8, :3] = LINE * (0.92 + 0.08 * rng.random((8, 8, 1)))
    img[56:64, 0:16, :3] = ripstop(8, 16, CANVAS, rng, grid=2, strength=0.12)
    img[56:64, 16:20, :3] = WEBBING * (0.9 + 0.2 * rng.random((8, 4, 1)))
    img[56:60, 24:28, :3] = HANDLE
    img[..., :3] = np.clip(img[..., :3], 0, 1)
    return img


def palette():
    """4 x 4 patches per material index (as the other held models): canvas, webbing, handle, metal, red, white."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(92)
    for k, c in enumerate((CANVAS, WEBBING, HANDLE, METAL, PRIMARY, SECONDARY)):
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(c, (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.08
        patch[3] *= 0.86
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1
    return pal


def held_elements():
    """The packed rig in item units (16 per block): the container, its flap, straps, the ripcord handle, buckles."""
    canvas, webbing, handle, metal, red = 0, 1, 2, 3, 4
    return [
        _box((8.0, 8.0, 8.0), (3.4, 4.4, 1.9), canvas),            # container
        _box((8.0, 12.2, 8.3), (3.2, 0.5, 1.7), canvas),           # top flap
        _box((8.0, 8.0, 9.95), (2.6, 3.6, 0.08), red),             # the canopy peeking out along the closing seam
        _box((5.9, 8.0, 5.9), (0.45, 4.6, 0.25), webbing),         # shoulder straps
        _box((10.1, 8.0, 5.9), (0.45, 4.6, 0.25), webbing),
        _box((8.0, 6.2, 5.8), (2.6, 0.35, 0.25), webbing),         # chest strap
        _box((8.0, 6.2, 5.5), (0.5, 0.45, 0.12), metal),           # its buckle
        _box((10.1, 7.4, 5.5), (0.45, 0.65, 0.2), handle),         # ripcord handle
    ]


DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 1.5, 1], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 1.5, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -20, 0], "translation": [1, 2, -1], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [0, -20, 0], "translation": [1, 2, -1], "scale": [0.5, 0.5, 0.5]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [20, 205, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 6, 0], "scale": [1, 1, 1]},
}


def icon(seed=93):
    """32 px: an open red-and-white canopy over its lines and a small olive pack (drawn 4x and box-filtered)."""
    s = 4
    n = 32 * s
    y, x = np.mgrid[0:n, 0:n] / s + 0.5 / s
    img = np.zeros((n, n, 4))

    def put(mask, colour, shade=None):
        c = np.tile(colour, (n, n, 1)) if shade is None else colour * shade[..., None]
        img[mask, :3] = c[mask]
        img[mask, 3] = 1

    # the canopy: an arc band, cells alternating colour, shaded towards its lower edge
    cx, cy, r_out, r_in = 16, 22, 15.5, 10.5
    rr = np.hypot(x - cx, y - cy)
    ang = np.arctan2(y - cy, x - cx)
    band_mask = (rr < r_out) & (rr > r_in) & (ang < -0.35) & (ang > -np.pi + 0.35)
    cell = np.floor((ang + np.pi - 0.35) / ((np.pi - 0.7) / 7)).astype(int)
    shade = 0.75 + 0.3 * (rr - r_in) / (r_out - r_in)
    for parity, colour in ((0, PRIMARY), (1, SECONDARY)):
        put(band_mask & (cell % 2 == parity), colour, shade)
    edge = band_mask & ((rr > r_out - 0.6) | (rr < r_in + 0.6))
    img[edge, :3] *= 0.7
    # the pack
    pack = (np.abs(x - 16) < 3.6) & (y > 23.5) & (y < 30.5)
    put(pack, CANVAS * 1.1)
    strap = pack & (np.abs(x - 16) < 0.6)
    put(strap, WEBBING * 2)
    img = img.reshape(32, s, 32, s, 4).mean(axis=(1, 3))
    # straight colour where the edge is partly covered (premultiplied average back to straight alpha)
    a = img[..., 3:4]
    img[..., :3] = np.where(a > 0, img[..., :3] / np.maximum(a, 1e-6), 0)
    # a dark outline for readability on light slots
    alpha = img[..., 3]
    grown = np.zeros_like(alpha)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        grown = np.maximum(grown, np.roll(np.roll(alpha, dy, 0), dx, 1))
    outline = (grown > 0.4) & (alpha < 0.4)
    img[outline, :3] = 0.08
    img[outline, 3] = np.maximum(img[outline, 3], 0.85)
    # the lines, drawn last and only where nothing else is: from under the canopy's ends down to the pack
    yy, xx = np.mgrid[0:32, 0:32] + 0.5
    for x_top in (3.5, 9.5, 22.5, 28.5):
        x0, y0, x1, y1 = x_top, 15.5 if x_top in (3.5, 28.5) else 12.0, 16, 23.5
        d = np.abs((y1 - y0) * xx - (x1 - x0) * yy + x1 * y0 - y1 * x0) / np.hypot(y1 - y0, x1 - x0)
        on = (d < 0.5) & (yy > y0) & (yy < y1) & (img[..., 3] < 0.3)
        img[on, :3] = LINE * 0.8
        img[on, 3] = np.maximum(img[on, 3], np.clip(1.1 - d[on], 0, 1))
    return np.clip(img, 0, 1)


def build(path):
    tx.save_png(canopy_texture(), path("textures", "entity", "parachute.png"))
    tx.save_png(icon(), path("textures", "item", f"{KEY}.png"))
    write_json(path("models", "item", f"{KEY}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{KEY}"}})
    tx.save_png(palette(), path("textures", "item", f"{KEY}_held.png"))
    tex = f"blockland:item/{KEY}_held"
    write_json(path("models", "item", f"{KEY}_held.json"),
               {"textures": {"0": tex, "particle": tex}, "elements": held_elements(), "display": DISPLAY})
    write_json(path("items", f"{KEY}.json"), {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": ["gui"], "model": {"type": "minecraft:model", "model": f"blockland:item/{KEY}"}}],
        "fallback": {"type": "minecraft:model", "model": f"blockland:item/{KEY}_held"}}})
    print("parachute items: canopy texture, icon, item models, held palette, item definition")
