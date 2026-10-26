"""The built-in optional pack "BlockLand 连接玻璃" (resourcepacks/blockland_connected, rendering spec section 25): vanilla
glass with connected textures — a window of glass blocks keeps its frame only round its outside.

python -I tools/assets/gen_connected_glass.py

47 ctm tiles (tools/ctm/ctm_layout.py), 16 px, cutout like vanilla's glass (alpha 0 or 1): a light frame with a dark
outer line on the free edges, a small corner piece where only the diagonal is missing, and a few diagonal glints that
continue from block to block (they repeat every 16 px). Own procedural art."""
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

PACK = os.path.join(REPO, "mod", "src", "main", "resources", "resourcepacks", "blockland_connected")
N = 16
FRAME = np.array([0.86, 0.9, 0.92, 1.0], dtype=np.float32)
OUTLINE = np.array([0.55, 0.62, 0.66, 1.0], dtype=np.float32)
GLINT = np.array([0.95, 0.97, 1.0, 1.0], dtype=np.float32)


def tile(mask):
    img = np.zeros((N, N, 4), dtype=np.float32)
    # glints: short diagonal streaks on lines x + y = const (mod 16), continuous across blocks
    for x in range(N):
        for y in range(N):
            d = (x + y) % N
            if (d == 5 and 3 <= x <= 6) or (d == 7 and 4 <= x <= 5) or (d == 13 and 9 <= x <= 13):
                img[y, x] = GLINT
    edges = {e for e in (L.UP, L.RIGHT, L.DOWN, L.LEFT) if not L.has(mask, e)}
    if L.UP in edges:
        img[0, :] = OUTLINE
        img[1, :] = FRAME
    if L.DOWN in edges:
        img[15, :] = OUTLINE
        img[14, :] = FRAME
    if L.LEFT in edges:
        img[:, 0] = OUTLINE
        img[1:15, 1] = FRAME
    if L.RIGHT in edges:
        img[:, 15] = OUTLINE
        img[1:15, 14] = FRAME
    # keep the outline unbroken where two frame edges meet
    if L.UP in edges:
        img[0, :] = OUTLINE
    if L.DOWN in edges:
        img[15, :] = OUTLINE
    for corner, (a, b) in L.CORNERS.items():
        if L.has(mask, a) and L.has(mask, b) and not L.has(mask, corner):
            dx, dy = L.DIRS[corner]
            x = 15 if dx > 0 else 0
            y = 0 if dy > 0 else 15
            img[y, x] = OUTLINE
            img[y, 14 if dx > 0 else 1] = FRAME
            img[1 if dy > 0 else 14, x] = FRAME
    return img


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def main():
    folder = os.path.join(PACK, "assets", "blockland", "textures", "block", "connected_glass")
    os.makedirs(folder, exist_ok=True)
    for i, mask in enumerate(L.CTM_MASKS):
        tx.save_png(tile(mask), os.path.join(folder, f"{i}.png"))
    write_json(os.path.join(PACK, "assets", "blockland", "ctm", "glass.json"), {
        "blocks": ["minecraft:glass"], "textures": ["minecraft:block/glass"], "method": "ctm",
        "tiles": "blockland:block/connected_glass",
    })
    write_json(os.path.join(PACK, "pack.mcmeta"), {"pack": {
        "description": {"translate": "blockland.graphics.connected_pack.description", "fallback": "BlockLand 连接玻璃"},
        "pack_format": 64}})
    print(f"connected glass: {len(L.CTM_MASKS)} tiles")


if __name__ == "__main__":
    main()
