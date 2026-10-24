"""Crowbar item assets (VIEWMODEL_SPEC sections 11, 37 and 41): icon rendered from the GLB, icon / held item models,
the held model's palette and the item definition (icon in the GUI, the held model in first person, the 3D bar
elsewhere)."""
import copy
import os

import numpy as np

import gen_knife_items as gki
import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import KNIFE_DIR, load_materials
import third_person

KNIFE = "crowbar"
PATCH = {"bar": 0, "ends": 1, "grip": 2}
SCALE = 1.8          # the bar is modelled at 25 units per metre (it would not fit the 48-unit model box at 50): its
                     # display scales are the balisong's times this, so it shows twice the dagger's size


def palette(mats):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(47)
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
    """The bar along Z, the goose-neck north (-Z) curling up (+Y), 25 units per metre: the chisel at z 15.5."""
    e = [
        _el([7.76, 7.76, 1.95], [8.24, 8.24, 11.0], "bar"),             # shaft
        _el([7.76, 7.76, 0.60], [8.24, 9.95, 1.10], "bar"),             # the goose-neck's front
        _el([7.76, 7.76, 1.05], [8.24, 8.40, 1.95], "bar"),             # its lower and upper turns
        _el([7.76, 9.45, 1.05], [8.24, 10.05, 2.10], "bar"),
        _el([7.70, 9.75, 2.10], [8.30, 10.15, 2.90], "ends"),           # the claw
        _el([7.70, 7.70, 11.0], [8.30, 8.30, 14.1], "grip"),            # tape
        _el([7.76, 7.76, 14.1], [8.24, 8.24, 14.7], "bar"),
        _el([7.71, 7.86, 14.7], [8.29, 8.14, 15.5], "ends"),            # the chisel
    ]
    display = copy.deepcopy(gki.held_model(texture)["display"])
    for ctx in ("thirdperson_righthand", "thirdperson_lefthand", "ground", "fixed", "head"):
        display[ctx]["scale"] = [round(s * SCALE, 4) for s in display[ctx]["scale"]]
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def item_definition():
    return third_person.weapon_definition({"type": "minecraft:model", "model": f"blockland:item/{KNIFE}"},
                                          {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}_held"},
                                          **third_person.TUNING.get(KNIFE, {}))


def build(path):
    g = GLTF(os.path.join(KNIFE_DIR, f"{KNIFE}.glb"))
    mats = load_materials(g)
    items, ext = gki.icon_items(g, mats, angle=40.0)
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{KNIFE}.png"))
    write_json(path("models", "item", f"{KNIFE}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{KNIFE}"}})
    tx.save_png(palette(mats), path("textures", "item", f"{KNIFE}_held.png"))
    write_json(path("models", "item", f"{KNIFE}_held.json"), held_model(f"blockland:item/{KNIFE}_held"))
    write_json(path("items", f"{KNIFE}.json"), item_definition())
    print(f"knife items: {KNIFE} icon, item models, held palette, item definition")
