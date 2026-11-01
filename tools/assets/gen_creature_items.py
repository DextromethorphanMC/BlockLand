"""Creature item icons (tools/creatures/SHARK_SPEC.md section 5): the shark's spawn egg, `textures/item/shark_spawn_egg
.png` — a 16 px egg the way vanilla draws its eggs (an outlined egg lit from the top left, spots of a second colour), in
the shark's slate back with off-white spots. The item definition and model (`items/shark_spawn_egg.json`,
`models/item/shark_spawn_egg.json`, layer0 `blockland:item/shark_spawn_egg`) are the creature module's."""
import numpy as np
from PIL import Image

EGGS = {"shark": ((70, 88, 104), (214, 214, 204))}
# spots (x, y) on the 16 px egg, the same pattern for every egg
SPOTS = [(6, 3), (7, 3), (9, 5), (10, 5), (10, 6), (5, 7), (6, 7), (8, 9), (9, 9), (9, 10), (5, 11), (6, 11), (11, 11),
         (7, 13), (8, 13)]


def egg(base, spots):
    size = 16
    img = np.zeros((size, size, 4), np.uint8)
    yy, xx = np.mgrid[0:size, 0:size]
    cx, cy = 7.5, 8.6
    # an egg: wider below its middle than above
    ry = np.where(yy < cy, 6.9, 6.2)
    rx = 5.4 + 0.5 * np.clip((yy - 4) / 8, 0, 1)
    inside = ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2 <= 1.0
    edge = inside & ~(np.roll(inside, 1, 0) & np.roll(inside, -1, 0) & np.roll(inside, 1, 1) & np.roll(inside, -1, 1))
    b = np.array(base, float)
    light = 1.18 - 0.35 * ((xx - 4) ** 2 + (yy - 4) ** 2) / 120.0
    col = np.clip(b[None, None, :] * light[..., None], 0, 255)
    for x, y in SPOTS:
        if inside[y, x] and not edge[y, x]:
            col[y, x] = np.array(spots, float) * np.clip(light[y, x], 0.8, 1.05)
    col[edge] = b * 0.45
    hl = [(5, 4), (5, 5), (6, 4)]
    for x, y in hl:
        if inside[y, x] and not edge[y, x]:
            col[y, x] = np.clip(col[y, x] * 1.25 + 20, 0, 255)
    img[..., :3] = col.astype(np.uint8)
    img[..., 3] = np.where(inside, 255, 0)
    return Image.fromarray(img, "RGBA")


def build(path):
    for name, (base, spots) in EGGS.items():
        out = path("textures", "item", f"{name}_spawn_egg.png")
        egg(base, spots).save(out)
    print(f"creature items: {len(EGGS)} spawn egg icons")
