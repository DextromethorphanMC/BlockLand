"""Backpack assets (UI spec §2), vanilla style: 16 × 16 item icons, the 64 × 32 textures of the model on the player's back
(laid out as the cuboids of client/backpack/BackpackLayer unwrap: pack at (0, 0) 8 × 9 × depth, lid at (0, 16) 8 × 3 ×
depth, pocket at (32, 0) 6 × 4 × 1, strap at (48, 0) 1 × 8 × 1) and the item models /
item definitions. Reads data/blockland/backpack/backpacks.json for the ids and depths.

python -I tools/assets/gen_backpack_items.py"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "blockland")
DATA = os.path.join(RES, "data", "blockland", "backpack", "backpacks.json")

# canvas colours (sRGB 0..1) per backpack id; others fall back to the last
COLOURS = {
    "backpack_small": (0.66, 0.58, 0.42),     # tan canvas
    "backpack_medium": (0.43, 0.46, 0.29),    # olive drab
    "backpack_large": (0.31, 0.35, 0.31),     # dark grey-green
}
WEBBING = (0.16, 0.16, 0.15)
BUCKLE = (0.55, 0.55, 0.53)


def rgba(c, a=1.0):
    return [int(round(max(0, min(1, v)) * 255)) for v in c] + [int(round(a * 255))]


def shade(c, k):
    return tuple(v * k for v in c)


def canvas(w, h, base, rng, light=1.0):
    """Woven canvas: the base colour, a little per-pixel noise and a faint diagonal weave."""
    y, x = np.mgrid[0:h, 0:w]
    weave = 1.0 + 0.04 * (((x + y) % 2) * 2 - 1)
    noise = 1.0 + rng.normal(0, 0.035, (h, w))
    k = light * weave * noise
    out = np.zeros((h, w, 4))
    for i in range(3):
        out[..., i] = base[i] * k
    out[..., 3] = 1
    return out


def put(img, x, y, c, a=1.0):
    if 0 <= y < img.shape[0] and 0 <= x < img.shape[1]:
        img[y, x, :3] = c
        img[y, x, 3] = a


def to_image(arr):
    a = np.clip(arr, 0, 1)
    return Image.fromarray((a * 255 + 0.5).astype(np.uint8), "RGBA")


# ------------------------------------------------------------------------------------------- item icon
def icon(base, rows, rng):
    img = np.zeros((16, 16, 4))
    top = 4 - min(rows, 3) // 2           # bigger packs stand a little taller
    body = canvas(10, 16 - top - 1, base, rng)
    img[top:15, 3:13] = body
    # rounded lower corners
    for x, y in ((3, 14), (12, 14)):
        img[y, x] = 0
    # lid: a darker band over the top, its flap edge a shade lighter
    for x in range(3, 13):
        for y in range(top, top + 3):
            img[y, x, :3] = np.array(shade(base, 0.8)) * (1.0 + rng.normal(0, 0.03))
        img[top + 3, x, :3] = shade(base, 0.68)
    # carry loop
    for x in range(6, 10):
        put(img, x, top - 2, WEBBING)
    put(img, 6, top - 1, WEBBING)
    put(img, 9, top - 1, WEBBING)
    # front pocket
    py = 9 + (1 if rows < 2 else 0)
    for x in range(5, 11):
        for y in range(py, 14):
            img[y, x, :3] = np.array(shade(base, 1.1)) * (1.0 + rng.normal(0, 0.03))
    for x in range(5, 11):
        img[py, x, :3] = shade(base, 1.22)
    # straps down the front, buckles where the lid closes
    for x in (5, 10):
        for y in range(top + 1, py):
            put(img, x, y, WEBBING)
        put(img, x, top + 3, BUCKLE)
    # outline: one darker pixel round the silhouette
    alpha = img[..., 3] > 0
    edge = np.zeros_like(alpha)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        edge |= np.roll(np.roll(alpha, dx, axis=1), dy, axis=0) & ~alpha
    img[edge] = list(shade(base, 0.35)) + [1]
    return to_image(img)


# ------------------------------------------------------------------------------------- texture on the back
def unwrap(tex, u0, v0, w, h, d, paint):
    """Paints the six faces of a cuboid's standard unwrap; paint(face, width, height) -> RGBA array."""
    faces = {
        "up": (u0 + d, v0, w, d), "down": (u0 + d + w, v0, w, d),
        "west": (u0, v0 + d, d, h), "north": (u0 + d, v0 + d, w, h),
        "east": (u0 + d + w, v0 + d, d, h), "south": (u0 + d + w + d, v0 + d, w, h),
    }
    for face, (x, y, fw, fh) in faces.items():
        tex[y:y + fh, x:x + fw] = paint(face, fw, fh)


def back_texture(base, depth, rng):
    tex = np.zeros((32, 64, 4))

    def pack(face, w, h):
        px = canvas(w, h, base, rng, 0.92 if face in ("west", "east") else 0.86 if face == "down" else 1.0)
        if face == "south":                       # the outside: straps, a seam round the edge
            px[0, :, :3] *= 0.8
            px[-1, :, :3] *= 0.8
            for x in (1, w - 2):
                px[:, x, :3] = WEBBING
        if face in ("west", "east"):              # side seams
            px[:, 0, :3] *= 0.82
            px[:, -1, :3] *= 0.82
        return px

    def lid(face, w, h):
        px = canvas(w, h, shade(base, 0.82), rng)
        if face == "south" or face in ("west", "east"):
            px[-1, :, :3] = shade(base, 0.62)     # the flap's edge
        if face == "south":
            for x in (1, w - 2):
                px[:, x, :3] = WEBBING
                px[-1, x, :3] = BUCKLE
        return px

    def pocket(face, w, h):
        px = canvas(w, h, shade(base, 1.1), rng)
        if face == "south":
            px[0, :, :3] = shade(base, 1.25)      # the pocket's zip line
        return px

    def strap(face, w, h):
        px = np.zeros((h, w, 4))
        px[..., :3] = WEBBING
        px[..., 3] = 1
        if face in ("north", "south") and h > 4:
            px[h // 2, :, :3] = BUCKLE
        return px

    unwrap(tex, 0, 0, 8, 9, depth, pack)
    unwrap(tex, 0, 16, 8, 3, depth, lid)
    unwrap(tex, 32, 0, 6, 4, 1, pocket)
    unwrap(tex, 48, 0, 1, 8, 1, strap)
    return to_image(tex)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def main():
    with open(DATA, encoding="utf-8") as f:
        packs = json.load(f)["backpacks"]
    last = None
    for i, pack in enumerate(packs):
        pid, rows, depth = pack["id"], int(pack.get("rows", 1)), int(pack.get("depth", 4))
        base = COLOURS.get(pid, last or (0.4, 0.4, 0.35))
        last = base
        rng = np.random.default_rng(9100 + i)
        save(icon(base, rows, rng), os.path.join(ASSETS, "textures", "item", pid + ".png"))
        save(back_texture(base, depth, rng), os.path.join(ASSETS, "textures", "entity", "backpack", pid + ".png"))
        write_json(os.path.join(ASSETS, "models", "item", pid + ".json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": "blockland:item/" + pid}})
        write_json(os.path.join(ASSETS, "items", pid + ".json"),
                   {"model": {"type": "minecraft:model", "model": "blockland:item/" + pid}})
        print(f"{pid}: {rows} rows, depth {depth}")


if __name__ == "__main__":
    main()
