"""Karambit item assets (VIEWMODEL_SPEC sections 11 and 34): icon rendered from the GLB, icon / held item models, the
held model's palette and the item definition (icon in gui / ground / fixed, held model otherwise)."""
import os

import numpy as np

import gen_knife_items as gki
import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import KNIFE_DIR, load_materials

KNIFE = "karambit"
PATCH = {"blade": 0, "blade_edge": 1, "handle": 2, "ring": 3}


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
    """The karambit in the hand's grip, the claw towards north (-Z) and curving down, 50 units per metre."""
    e = [
        _el([7.80, 7.60, 9.2], [8.20, 8.60, 13.6], "handle"),           # handle
        _el([7.75, 7.45, 9.0], [8.25, 8.75, 9.5], "handle"),            # guard
        _el([7.88, 7.70, 13.6], [8.12, 9.60, 14.0], "ring"),            # the ring (a square loop)
        _el([7.88, 7.70, 15.0], [8.12, 9.60, 15.4], "ring"),
        _el([7.88, 9.30, 13.6], [8.12, 9.60, 15.4], "ring"),
        _el([7.88, 7.70, 13.6], [8.12, 8.00, 15.4], "ring"),
        _el([7.86, 7.70, 6.2], [8.14, 8.75, 9.0], "blade"),             # blade, curving down
        _el([7.88, 7.20, 4.2], [8.12, 8.40, 6.2], "blade"),
        _el([7.90, 6.60, 3.0], [8.10, 7.70, 4.2], "blade_edge"),
        _el([7.92, 6.10, 2.4], [8.08, 6.90, 3.0], "blade_edge"),        # the hooked point
    ]
    return {"textures": {"0": texture, "particle": texture}, "elements": e,
            "display": gki.held_model(texture)["display"]}


def item_definition():
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui", "ground", "fixed"],
                                 "model": {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}"}}],
                      "fallback": {"type": "minecraft:model", "model": f"blockland:item/{KNIFE}_held"}}}


def icon_items(g, mats, angle=45.0):
    """The shared knife icon view (gen_knife_items: seen from the right flat) with the karambit turned a quarter about
    its handle first: its flats face its Y, not its X."""
    ki = g.names["knife"]
    turn = np.array([0.0, 0.0, -np.sin(np.pi / 4), np.cos(np.pi / 4)])       # -90 deg about Z: knife Y -> X
    W = g.world_matrices({ki: {"translation": np.zeros(3), "rotation": turn}})
    a = np.radians(angle)
    side = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])
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
