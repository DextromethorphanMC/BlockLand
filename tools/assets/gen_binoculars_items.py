"""The binoculars' item assets (VIEWMODEL_SPEC §52): a 32 px icon, the held model (cuboids + palette, as the parachute
and the equipment) and the item definition (the icon in the GUI, the held model everywhere else).

The held model is a roof-prism pair with a laser rangefinder: two rubber-armoured barrels along z — the objectives at
low z (north: away from the eye in first person), the eyecups at high z — joined by a hinge bridge with a focus wheel,
the rangefinder's window and red button on top. Item units, 16 per block, the middle at (8, 8, 8)."""
import numpy as np

import textures as tx
from gen_equipment_items import _box
from gen_items import write_json

KEY = "binoculars"
ARMOUR = np.array([0.24, 0.27, 0.19])      # sRGB, olive-drab rubber armour
RUBBER = np.array([0.07, 0.07, 0.07])      # eyecups, grips
METAL = np.array([0.36, 0.37, 0.38])       # hinge, focus wheel, trims
GLASS = np.array([0.10, 0.20, 0.27])       # coated objective glass
BUTTON = np.array([0.78, 0.12, 0.08])      # the rangefinder's button
WINDOW = np.array([0.05, 0.05, 0.08])      # the rangefinder's laser window
MATERIALS = (ARMOUR, RUBBER, METAL, GLASS, BUTTON, WINDOW)


def palette(seed=511):
    """4 x 4 patches per material index (as the other held models), lit from the top edge."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(seed)
    for k, c in enumerate(MATERIALS):
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        grain = 0.10 if k in (0, 1) else 0.05
        patch = np.tile(c, (4, 4, 1)) * (1 - grain / 2 + grain * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.84
        if k == 3:
            patch[1, 1] = np.clip(c * 3.2, 0, 1)     # the coating's glint
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1
    return pal


def held_elements():
    armour, rubber, metal, glass, button, window = range(6)
    els = []
    for side in (-1, 1):
        x = 8 + side * 2.55
        els += [
            _box((x, 8.0, 8.2), (1.55, 1.55, 3.0), armour),          # barrel
            _box((x, 8.0, 4.7), (1.75, 1.75, 0.8), armour),          # objective bell
            _box((x, 8.0, 3.88), (1.3, 1.3, 0.04), glass),           # objective glass
            _box((x, 8.0, 3.95), (1.78, 1.78, 0.12), rubber),        # objective rim
            _box((x, 8.0, 11.75), (1.1, 1.1, 0.55), rubber),         # eyecup
            _box((x + side * 1.58, 8.0, 8.4), (0.06, 1.2, 2.2), rubber),   # thumb grip on the outside
        ]
    els += [
        _box((8.0, 8.35, 8.3), (1.1, 0.7, 2.3), metal),               # hinge bridge
        _box((8.0, 9.25, 10.2), (0.55, 0.55, 0.65), rubber),          # focus wheel
        _box((8.0, 9.25, 10.2), (0.58, 0.2, 0.68), metal),            # its knurled band
        _box((8.0, 8.35, 5.75), (0.9, 0.55, 0.12), window),           # rangefinder window
        _box((10.55, 9.6, 7.0), (0.42, 0.08, 0.42), button),          # rangefinder button
        _box((5.45, 9.58, 9.2), (0.35, 0.06, 0.9), metal),            # diopter ring mark
    ]
    return els


DISPLAY = {
    # third person: objectives along the hand (forward while raised to the eyes, down while carried)
    "thirdperson_righthand": {"rotation": [0, 180, 0], "translation": [0, 1, 0], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 180, 0], "translation": [0, 1, 0], "scale": [0.55, 0.55, 0.55]},
    # first person: objectives away from the eye, the pair turned a little towards the middle of the screen
    "firstperson_righthand": {"rotation": [5, -10, 0], "translation": [-1, 3, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [5, 10, 0], "translation": [-1, 3, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [25, 210, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 6, 0], "scale": [1, 1, 1]},
}


def icon():
    """32 px: the pair seen from the front — two big objectives in rubber rims, the bridge, the eyecups behind them
    (drawn 4x and box-filtered, a dark outline for light slots)."""
    s = 4
    n = 32 * s
    y, x = np.mgrid[0:n, 0:n] / s + 0.5 / s
    img = np.zeros((n, n, 4))

    def put(mask, colour, shade=None):
        c = np.tile(colour, (n, n, 1)) if shade is None else colour * shade[..., None]
        img[mask, :3] = np.clip(c[mask], 0, 1)
        img[mask, 3] = 1

    # the eyecups and barrel tops behind, a little higher
    for cx in (9.5, 22.5):
        put((np.abs(x - cx) < 4.2) & (y > 6.5) & (y < 14), RUBBER * 2.2)
        put((np.abs(x - cx) < 5.0) & (y > 10) & (y < 21), ARMOUR, 0.85 + 0.25 * (21 - y) / 11)
    # the bridge with its focus wheel
    put((np.abs(x - 16) < 3.4) & (y > 10.5) & (y < 15.5), METAL)
    put((np.abs(x - 16) < 1.6) & (y > 7.5) & (y < 11), RUBBER * 2.5)
    put((np.abs(x - 16) < 1.6) & (np.abs(y - 9.2) < 0.4), METAL * 1.4)
    # the objectives: rubber rim, armour bell, coated glass with a glint
    for cx in (9.5, 22.5):
        rr = np.hypot(x - cx, y - 21)
        put(rr < 7.2, ARMOUR, 0.8 + 0.3 * np.clip((21 - y) / 7, -1, 1))
        put((rr < 6.0) & (rr > 5.1), RUBBER * 1.8)
        glass = rr < 5.1
        put(glass, GLASS, 0.75 + 0.5 * np.clip((y - 16) / 10, 0, 1))
        glint = glass & (np.hypot(x - (cx - 1.8), y - 19.2) < 1.3)
        put(glint, np.array([0.75, 0.88, 0.95]))
    # the red rangefinder button on the right barrel
    put(np.hypot(x - 25, y - 11.5) < 1.1, BUTTON)
    img = img.reshape(32, s, 32, s, 4).mean(axis=(1, 3))
    a = img[..., 3:4]
    img[..., :3] = np.where(a > 0, img[..., :3] / np.maximum(a, 1e-6), 0)
    alpha = img[..., 3]
    grown = np.zeros_like(alpha)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        grown = np.maximum(grown, np.roll(np.roll(alpha, dy, 0), dx, 1))
    outline = (grown > 0.4) & (alpha < 0.4)
    img[outline, :3] = 0.06
    img[outline, 3] = np.maximum(img[outline, 3], 0.85)
    return np.clip(img, 0, 1)


def build(path):
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
    print("binoculars items: icon, item models, held palette, item definition")
