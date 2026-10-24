"""Equipment item assets (VIEWMODEL_SPEC sections 11 and 36): a 32 px icon rendered from each GLB (the item alone, in
its rest look: no flame, no lighter), the icon / held item models, the held model's palette and the item definition
(icon in gui / ground / fixed, the low-poly held model otherwise; the thrown entity draws the GLB itself)."""
import os

import numpy as np

import gen_knife_items as gki
import raster as rs
import textures as tx
from gen_equipment import ITEM_IDS
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import load_materials
import third_person

HERE = os.path.dirname(os.path.abspath(__file__))
EQUIPMENT_DIR = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models",
                             "equipment")
# icon view per item: (yaw, pitch, roll) degrees applied to the item's design frame
VIEWS = {"frag_grenade": (-35.0, 12.0, -12.0), "flashbang": (-35.0, 10.0, -18.0), "smoke_grenade": (-35.0, 10.0, -18.0),
         "molotov": (-30.0, 8.0, -30.0), "throwing_knife": (90.0, 0.0, 45.0), "adrenaline": (90.0, 0.0, 45.0)}


def _rot(yaw, pitch, roll):
    a, b, c = np.radians([yaw, pitch, roll])
    ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
    rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
    rz = np.array([[np.cos(c), -np.sin(c), 0], [np.sin(c), np.cos(c), 0], [0, 0, 1]])
    return rz @ rx @ ry


def icon_items(g, mats, key):
    """The `equipment` subtree in its design frame (its node reset to identity), turned for the icon."""
    ei = g.names["equipment"]
    W = g.world_matrices({ei: {"translation": np.zeros(3), "rotation": np.array([0.0, 0.0, 0.0, 1.0])}})
    view = _rot(*VIEWS[key])
    sub = set()
    for i in range(len(g.nodes)):
        j = i
        while j is not None and j != ei:
            j = g.parent.get(j)
        if j == ei:
            sub.add(i)
    items = []
    for i in sub:
        n = g.nodes[i]
        if "mesh" not in n or abs(np.linalg.det(W[i][:3, :3])) < 1e-12:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            mat = mats[p["material"]]
            if mat.get("name") == "glass":                 # the icon shows the bottle as its glass colour, opaque
                mat = dict(mat, base=np.array([0.20, 0.11, 0.035, 1.0]), alpha_mode="OPAQUE")
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mat})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 1.0])
    return items, allp.max(0) - allp.min(0)


def palette(mats, names):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(53)
    by_name = {m["name"]: m for m in mats}
    for k, name in enumerate(names):
        m = by_name[name]
        base = np.asarray(m["base"][:3], float)
        if m.get("tex") is not None:
            base = base * m["tex"][:, :, :3].reshape(-1, 3).mean(0)
        if name == "glass":
            base = np.array([0.20, 0.11, 0.035])
        c = rs.linear_to_srgb(base) * 0.95 + 0.05
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(c, (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.85
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def _el(frm, to, k):
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    uv = [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]
    return {"from": frm, "to": to, "faces": {f: {"uv": uv, "texture": "#0"}
                                             for f in ("north", "south", "east", "west", "up", "down")}}


def _box(c, half, k):
    return _el([c[0] - half[0], c[1] - half[1], c[2] - half[2]], [c[0] + half[0], c[1] + half[1], c[2] + half[2]], k)


def held_elements(key, names):
    """Low-poly cuboids in the hand's grip (16 units per block, the item's middle at (8, 8, 11))."""
    k = {n: i for i, n in enumerate(names)}
    if key in ("frag_grenade", "flashbang", "smoke_grenade"):
        h = {"frag_grenade": 1.6, "flashbang": 2.4, "smoke_grenade": 3.0}[key]
        r = {"frag_grenade": 1.6, "flashbang": 1.1, "smoke_grenade": 1.5}[key]
        e = [_box((8, 8, 11), (r, h, r), k["body"]), _box((8, 8 + h + 0.45, 11), (0.6, 0.45, 0.6), k["fuze"]),
             _box((8 + r + 0.15, 8 + h * 0.3, 11), (0.12, h * 0.8, 0.35), k["lever"]),
             _box((8, 8 + h + 0.4, 11 - 1.1), (0.08, 0.5, 0.5), k["pin"])]
        if "label" in k:
            e.append(_box((8, 8 - h * 0.55, 11), (r + 0.05, 0.4, r + 0.05), k["label"]))
        return e
    if key == "molotov":
        return [_box((8, 7.0, 11), (1.45, 3.2, 1.45), k["liquid"]), _box((8, 10.9, 11), (0.95, 0.7, 0.95), k["glass"]),
                _box((8, 12.9, 11), (0.6, 1.3, 0.6), k["glass"]), _box((8, 14.6, 11), (0.4, 0.6, 0.4), k["rag"]),
                _box((8.0, 13.6, 11.6), (0.25, 0.9, 0.25), k["rag"])]
    if key == "throwing_knife":
        return [_box((8, 8, 12.4), (0.6, 0.65, 1.6), k["grip"]), _box((8, 8, 14.3), (0.1, 0.5, 0.4), k["blade"]),
                _box((8, 8, 8.8), (0.1, 0.6, 2.0), k["blade"]), _box((8, 8, 6.3), (0.1, 0.35, 0.6), k["blade_edge"])]
    return [_box((8, 8, 11), (0.5, 0.5, 2.8), k["barrel"]), _box((8, 8, 11.3), (0.52, 0.52, 1.6), k["label"]),
            _box((8, 8, 7.6), (0.4, 0.4, 0.7), k["plunger"]), _box((8, 8, 14.3), (0.55, 0.55, 0.5), k["cap"])]


def build(path):
    for key in ITEM_IDS:
        g = GLTF(os.path.join(EQUIPMENT_DIR, f"{key}.glb"))
        mats = load_materials(g)
        items, ext = icon_items(g, mats, key)
        tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{key}.png"))
        write_json(path("models", "item", f"{key}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{key}"}})
        names = [m["name"] for m in g.json["materials"] if m["name"] not in ("flame", "lighter", "vent")]
        tx.save_png(palette(mats, names), path("textures", "item", f"{key}_held.png"))
        tex = f"blockland:item/{key}_held"
        write_json(path("models", "item", f"{key}_held.json"),
                   {"textures": {"0": tex, "particle": tex}, "elements": held_elements(key, names),
                    "display": gki.held_model(tex)["display"]})
        write_json(path("items", f"{key}.json"), third_person.weapon_definition(
            {"type": "minecraft:model", "model": f"blockland:item/{key}"},
            {"type": "minecraft:model", "model": f"blockland:item/{key}_held"}))
        print(f"equipment items: {key} icon, item models, held palette, item definition")
