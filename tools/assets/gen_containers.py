"""Shipping containers (集装箱, rendering spec section 25): textures, models, block states, item models, connected-texture
rules, loot tables and the pickaxe tag for every colour of cargo/CargoContent.

python -I tools/assets/gen_containers.py

Each colour has two 47-tile ctm sets (tools/ctm/ctm_layout.py): `side` (corrugated steel, vertical ribs) and `top`
(the roof panel, also used underneath). A tile draws the container's frame — top and bottom rails, corner posts, corner
castings where two frame edges meet, an inner-corner piece where only the diagonal is missing — on exactly the edges
that have no container of the same colour next to them, so a wall of containers reads as one big container. Tile 0 (a
container alone) is what the block model names; the ctm rule swaps it. 16 px, vanilla style; every pattern repeats every
16 px (the ribs every 4), so neighbouring tiles continue each other. Own procedural art, deterministic."""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, "tools", "ctm"))

import ctm_layout as L  # noqa: E402
import textures as tx  # noqa: E402
import filelock  # noqa: E402

RES = os.path.join(REPO, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "blockland")
DATA = os.path.join(RES, "data")
N = 16

# paint colours (sRGB 0..255), keep in step with CargoContent.COLOURS
COLOURS = {
    "red": (148, 44, 34),
    "orange": (206, 98, 32),
    "yellow": (214, 168, 42),
    "green": (52, 104, 62),
    "blue": (38, 74, 134),
    "white": (214, 214, 206),
    "gray": (112, 116, 120),
    "brown": (108, 70, 46),
}
CASTING = np.array([58, 58, 60], dtype=np.float32) / 255
RUST = np.array([112, 56, 30], dtype=np.float32) / 255


def periodic_noise(seed, scale):
    rng = np.random.default_rng(seed)
    return tx.tile_noise(N, scale, rng) * 2 - 1


# the same weathering on every tile and colour (16-periodic, so tiles join without a seam)
GRAIN = periodic_noise(41, 0.8)
BLOTCH = periodic_noise(42, 2.5)
_rust_rng = np.random.default_rng(43)
RUST_SPOTS = [(int(x), int(y), float(a)) for x, y, a in
              zip(_rust_rng.integers(3, 13, 4), _rust_rng.integers(3, 13, 4), _rust_rng.uniform(0.12, 0.26, 4))]


def panel(kind):
    """The panel's brightness (16 x 16): corrugated ribs on the sides, shallow roof bows on top."""
    img = np.ones((N, N), dtype=np.float32)
    if kind == "side":
        # trapezoid ribs lit from the upper left: ridge, lit flank, groove, shaded flank
        profile = np.array([1.05, 1.13, 0.86, 0.75], dtype=np.float32)
        img *= profile[np.arange(N) % 4][None, :]
    else:
        profile = np.array([1.0, 1.04, 0.98, 0.92], dtype=np.float32)
        img *= profile[np.arange(N) % 4][:, None]
    img *= 1 + 0.03 * GRAIN + 0.035 * BLOTCH
    return img


def tile(paint, kind, mask):
    """One ctm tile for a reduced mask in the texture's frame (row 0 = up)."""
    base = np.array(paint, dtype=np.float32) / 255
    shade = panel(kind)
    rgb = base[None, None, :] * shade[:, :, None]
    # a few rust pocks in the panel
    for x, y, a in RUST_SPOTS:
        rgb[y, x] = rgb[y, x] * (1 - a) + RUST * a
    edges = {e for e in (L.UP, L.RIGHT, L.DOWN, L.LEFT) if not L.has(mask, e)}
    if kind == "side":
        # grime run down above the bottom rail; shadows the rails and posts throw on the panel
        if L.DOWN in edges:
            for i, f in enumerate((0.95, 0.9, 0.86)):
                rgb[11 + i] *= f
        if L.UP in edges:
            rgb[2] *= 0.82
        if L.LEFT in edges:
            rgb[:, 2] *= 0.88
        if L.RIGHT in edges:
            rgb[:, 13] *= 0.9

    def paint_row(r, f):
        rgb[r, :] = base * f * (1 + 0.02 * GRAIN[r, :, None])

    def paint_col(c, f):
        rgb[:, c] = base * f * (1 + 0.02 * GRAIN[:, c, None])

    # rails (top and bottom) and posts (left and right): two pixels, the outer one darker
    if L.UP in edges:
        paint_row(0, 0.70)
        paint_row(1, 0.94)
    if L.DOWN in edges:
        paint_row(14, 0.84)
        paint_row(15, 0.60)
    if L.LEFT in edges:
        paint_col(0, 0.68)
        paint_col(1, 0.92)
    if L.RIGHT in edges:
        paint_col(14, 0.82)
        paint_col(15, 0.60)
    # corner castings where two frame edges meet: a dark steel block with its hole
    cast = base * 0.35 + CASTING * 0.65
    for (vert, horiz, ys, xs) in ((L.UP, L.LEFT, slice(0, 3), slice(0, 3)), (L.UP, L.RIGHT, slice(0, 3), slice(13, 16)),
                                  (L.DOWN, L.LEFT, slice(13, 16), slice(0, 3)), (L.DOWN, L.RIGHT, slice(13, 16), slice(13, 16))):
        if vert in edges and horiz in edges:
            rgb[ys, xs] = cast
            hy = 1 if ys.start == 0 else 14
            hx = 1 if xs.start == 0 else 14
            rgb[hy, hx] = cast * 0.35
            # its lit upper-left rim
            rgb[ys.start, xs] = cast * 1.25
    # inner corners: the frame of the missing diagonal neighbour reaches into this corner
    for corner, (a, b) in L.CORNERS.items():
        if L.has(mask, a) and L.has(mask, b) and not L.has(mask, corner):
            dx, dy = L.DIRS[corner]
            xs = slice(14, 16) if dx > 0 else slice(0, 2)
            ys = slice(0, 2) if dy > 0 else slice(14, 16)
            rgb[ys, xs] = base * 0.78
            rgb[0 if dy > 0 else 15, 15 if dx > 0 else 0] = base * 0.6
    alpha = np.ones((N, N, 1), dtype=np.float32)
    return np.concatenate([np.clip(rgb, 0, 1), alpha], axis=2)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def merge_tag(path, values):
    """Adds values to a block tag file (locked: other generators may add to the same tag)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not os.path.exists(path):
        write_json(path, {"replace": False, "values": []})
    with filelock.locked(path):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        for v in values:
            if v not in data["values"]:
                data["values"].append(v)
        write_json(path, data)


def main():
    blocks = []
    for name, paint in COLOURS.items():
        block = f"container_{name}"
        blocks.append(f"blockland:{block}")
        for kind in ("side", "top"):
            folder = os.path.join(ASSETS, "textures", "block", "container", name, kind)
            os.makedirs(folder, exist_ok=True)
            for i, mask in enumerate(L.CTM_MASKS):
                tx.save_png(tile(paint, kind, mask), os.path.join(folder, f"{i}.png"))
        side = f"blockland:block/container/{name}/side"
        top = f"blockland:block/container/{name}/top"
        write_json(os.path.join(ASSETS, "models", "block", f"{block}.json"), {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {"side": f"{side}/0", "top": f"{top}/0", "bottom": f"{top}/0"},
        })
        write_json(os.path.join(ASSETS, "blockstates", f"{block}.json"),
                   {"variants": {"": {"model": f"blockland:block/{block}"}}})
        write_json(os.path.join(ASSETS, "items", f"{block}.json"),
                   {"model": {"type": "minecraft:model", "model": f"blockland:block/{block}"}})
        write_json(os.path.join(ASSETS, "ctm", f"{block}.json"), {"rules": [
            {"blocks": [f"blockland:{block}"], "faces": ["sides"], "textures": [f"{side}/0"], "method": "ctm", "tiles": side},
            {"blocks": [f"blockland:{block}"], "faces": ["top", "bottom"], "textures": [f"{top}/0"], "method": "ctm", "tiles": top},
        ]})
        write_json(os.path.join(DATA, "blockland", "loot_table", "blocks", f"{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:item", "name": f"blockland:{block}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            "random_sequence": f"blockland:blocks/{block}",
        })
    merge_tag(os.path.join(DATA, "minecraft", "tags", "block", "mineable", "pickaxe.json"), blocks)
    print(f"containers: {len(COLOURS)} colours x 2 x {len(L.CTM_MASKS)} tiles")


if __name__ == "__main__":
    main()
