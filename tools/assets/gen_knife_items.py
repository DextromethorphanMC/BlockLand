"""Knife item assets (VIEWMODEL_SPEC section 11): icon rendered from the GLB, icon/held item models, held-model
palette and the item definition (icon in gui/ground/fixed, held model otherwise)."""
import os

import numpy as np

import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import KNIFE_DIR, load_materials

KNIFE = "butterfly"
PATCH = {"blade": 0, "blade_edge": 1, "handle": 2, "handle_inlay": 3, "pins": 4, "latch": 5}


def icon_items(g, mats, angle=45.0):
    """Open knife in its own frame, seen from the right flat, blade towards the upper right."""
    ki = g.names["knife"]
    W = g.world_matrices({ki: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    a = np.radians(angle)
    side = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])          # look from +X, blade to the right
    roll = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1.0]])
    view = roll @ side
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
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


def palette(mats):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(41)
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
    """Open balisong, blade towards north (-Z), ~0.23 m scaled to 14 units."""
    e = [
        _el([7.65, 8.02, 7.0], [8.35, 8.77, 15.0], "handle"),          # safe handle
        _el([7.65, 7.23, 7.0], [8.35, 7.98, 15.0], "handle"),          # bite handle
        _el([7.60, 8.17, 8.6], [8.40, 8.62, 14.0], "handle_inlay"),
        _el([7.60, 7.38, 8.6], [8.40, 7.83, 14.0], "handle_inlay"),
        _el([7.90, 7.45, 3.4], [8.10, 8.72, 7.4], "blade"),            # blade body
        _el([7.92, 7.70, 1.8], [8.08, 8.62, 3.4], "blade"),            # clip towards the tip
        _el([7.94, 8.00, 1.0], [8.06, 8.40, 1.8], "blade_edge"),       # point
        _el([7.93, 7.28, 2.2], [8.07, 7.50, 7.0], "blade_edge"),       # bevelled edge
        _el([7.58, 8.27, 7.15], [8.42, 8.57, 7.45], "pins"),
        _el([7.58, 7.43, 7.15], [8.42, 7.73, 7.45], "pins"),
        _el([7.80, 7.60, 15.0], [8.20, 8.45, 15.45], "latch"),
    ]
    display = {
        "thirdperson_righthand": {"rotation": [100, 0, 0], "translation": [0, 3.0, 0.5], "scale": [0.85, 0.85, 0.85]},
        "thirdperson_lefthand": {"rotation": [100, 0, 0], "translation": [0, 3.0, 0.5], "scale": [0.85, 0.85, 0.85]},
        "firstperson_righthand": {"rotation": [20, 10, 0], "translation": [1, 3, -1], "scale": [0.7, 0.7, 0.7]},
        "firstperson_lefthand": {"rotation": [20, 10, 0], "translation": [1, 3, -1], "scale": [0.7, 0.7, 0.7]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"rotation": [0, 90, 45], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "gui": {"rotation": [0, 90, 45], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1.0, 1.0, 1.0]},
    }
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def item_definition():
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui", "ground", "fixed"],
                                 "model": {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}"}}],
                      "fallback": {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}_held"}}}


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
