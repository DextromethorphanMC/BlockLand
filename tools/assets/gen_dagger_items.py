"""Dagger item assets (VIEWMODEL_SPEC sections 11 and 27): icon rendered from the GLB, icon / held item models, the
held model's palette and the item definition (icon in gui / ground / fixed, held model otherwise)."""
import os

import numpy as np

import gen_knife_items as gki
import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import KNIFE_DIR, load_materials
import third_person

KNIFE = "dagger"
PATCH = {"blade": 0, "blade_edge": 1, "guard": 2, "handle": 3, "pommel": 4}


def palette(mats):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(43)
    by_name = {m["name"]: m for m in mats}
    for name, k in PATCH.items():
        m = by_name[name]
        base = np.asarray(m["base"][:3], float)
        if m.get("tex") is not None:
            base = base * m["tex"][:, :, :3].reshape(-1, 3).mean(0)
        c = rs.linear_to_srgb(base) * 0.95 + 0.05
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(c, (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.85
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def _uv(name):
    k = PATCH[name]
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def _el(frm, to, mat):
    return {"from": frm, "to": to, "faces": {f: {"uv": _uv(mat), "texture": "#0"}
                                             for f in ("north", "south", "east", "west", "up", "down")}}


def held_model(texture):
    """The dagger point towards north (-Z), its 0.30 m at 50 units per metre."""
    e = [
        _el([7.85, 7.30, 2.6], [8.15, 8.70, 9.0], "blade"),             # blade body
        _el([7.88, 7.50, 1.4], [8.12, 8.50, 2.6], "blade"),             # taper
        _el([7.92, 7.75, 0.6], [8.08, 8.25, 1.4], "blade_edge"),        # point
        _el([7.90, 7.18, 2.8], [8.10, 7.32, 8.9], "blade_edge"),        # the two edges
        _el([7.90, 8.68, 2.8], [8.10, 8.82, 8.9], "blade_edge"),
        _el([7.70, 6.35, 9.0], [8.30, 9.65, 9.5], "guard"),             # crossguard
        _el([7.50, 7.35, 9.5], [8.50, 8.65, 14.0], "handle"),           # grip
        _el([7.45, 7.30, 10.6], [8.55, 8.70, 10.9], "handle"),          # ribs
        _el([7.45, 7.30, 12.0], [8.55, 8.70, 12.3], "handle"),
        _el([7.45, 7.30, 14.0], [8.55, 8.70, 14.9], "pommel"),
        _el([7.90, 7.70, 14.9], [8.10, 8.30, 15.5], "pommel"),          # lanyard loop
    ]
    return {"textures": {"0": texture, "particle": texture}, "elements": e,
            "display": gki.held_model(texture)["display"]}


def item_definition():
    return third_person.weapon_definition({"type": "minecraft:model", "model": f"blockland:item/{KNIFE}"},
                                          {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}_held"})


def build(path):
    g = GLTF(os.path.join(KNIFE_DIR, f"{KNIFE}.glb"))
    mats = load_materials(g)
    items, ext = gki.icon_items(g, mats)
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{KNIFE}.png"))
    write_json(path("models", "item", f"{KNIFE}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{KNIFE}"}})
    tx.save_png(palette(mats), path("textures", "item", f"{KNIFE}_held.png"))
    write_json(path("models", "item", f"{KNIFE}_held.json"), held_model(f"blockland:item/{KNIFE}_held"))
    write_json(path("items", f"{KNIFE}.json"), item_definition())
    print(f"knife items: {KNIFE} icon, item models, held palette, item definition")
