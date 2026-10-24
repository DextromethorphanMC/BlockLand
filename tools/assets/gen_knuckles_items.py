"""Knuckles item assets (VIEWMODEL_SPEC sections 11, 37 and 42): icon rendered from the GLB (the right duster face on,
striking edge up), icon / held item models, the held model's palette and the item definition (icon in gui, held model
in first person, the 3D duster otherwise: the right one, its palm bar along the held model's grip axis)."""
import os

import numpy as np

import gen_knife_items as gki
import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import KNIFE_DIR, load_materials
import third_person

KNIFE = "knuckles"
PATCH = {"frame": 0, "face": 1, "wrap": 2}
# the duster's knife frame against the held model: the knuckle line (index -> little finger, knife +X) along the
# grip axis like a knife's handle (index towards north), the striking edge down (third_person.TUNING has the same)
ROTATION = [0, -90, 0]


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
    """The right duster at 50 units per metre: the ring row along Z (index end north), the striking edge down."""
    e = [
        _el([7.75, 6.95, 9.40], [8.25, 7.35, 13.60], "face"),           # striking edge
        _el([7.78, 6.75, 9.85], [8.22, 7.00, 10.35], "face"),           # the four bosses
        _el([7.78, 6.75, 10.90], [8.22, 7.00, 11.40], "face"),
        _el([7.78, 6.75, 11.80], [8.22, 7.00, 12.30], "face"),
        _el([7.78, 6.75, 12.70], [8.22, 7.00, 13.20], "face"),
        _el([7.77, 7.35, 9.40], [8.23, 8.55, 9.75], "frame"),           # ring ends and dividers
        _el([7.77, 7.35, 10.55], [8.23, 8.55, 10.75], "frame"),
        _el([7.77, 7.35, 11.50], [8.23, 8.55, 11.70], "frame"),
        _el([7.77, 7.35, 12.45], [8.23, 8.55, 12.65], "frame"),
        _el([7.77, 7.35, 13.25], [8.23, 8.55, 13.60], "frame"),
        _el([7.77, 8.55, 9.30], [8.23, 8.85, 13.70], "frame"),          # under the rings
        _el([7.77, 9.15, 9.00], [8.23, 9.50, 14.00], "frame"),          # palm bar, its ends past the hand
        _el([7.70, 9.10, 9.70], [8.30, 9.55, 13.30], "wrap"),
        _el([7.77, 8.85, 9.00], [8.23, 9.15, 9.40], "frame"),           # window ends
        _el([7.77, 8.85, 13.60], [8.23, 9.15, 14.00], "frame"),
    ]
    return {"textures": {"0": texture, "particle": texture}, "elements": e,
            "display": gki.held_model(texture)["display"]}


def item_definition():
    return third_person.weapon_definition({"type": "minecraft:model", "model": f"blockland:item/{KNIFE}"},
                                          {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}_held"},
                                          rotation=ROTATION)


def icon_items(g, mats):
    """The right duster alone in its own frame, face on: the ring row across, the striking edge up, tipped back a
    little to show the bosses and turned for a livelier silhouette."""
    ki = g.names["knife"]
    W = g.world_matrices({ki: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    face = np.array([[1.0, 0, 0], [0, -1.0, 0], [0, 0, -1.0]])         # knife -Y up, its -Z towards the viewer

    def rx(a):
        a = np.radians(a)
        return np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])

    def rz(a):
        a = np.radians(a)
        return np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1.0]])
    view = rz(18.0) @ rx(-28.0) @ face
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or n["name"] != "duster":
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mats[p["material"]]})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 1.0])
    return items, allp.max(0) - allp.min(0)


def build(path):
    g = GLTF(os.path.join(KNIFE_DIR, f"{KNIFE}.glb"))
    mats = load_materials(g)
    items, ext = icon_items(g, mats)
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{KNIFE}.png"))
    write_json(path("models", "item", f"{KNIFE}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{KNIFE}"}})
    tx.save_png(palette(mats), path("textures", "item", f"{KNIFE}_held.png"))
    write_json(path("models", "item", f"{KNIFE}_held.json"), held_model(f"blockland:item/{KNIFE}_held"))
    write_json(path("items", f"{KNIFE}.json"), item_definition())
    print(f"knife items: {KNIFE} icon, item models, held palette, item definition")
