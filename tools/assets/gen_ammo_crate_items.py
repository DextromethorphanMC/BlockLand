"""Ammo crate block and item assets (VIEWMODEL_SPEC section 46): the inventory icon rendered from the GLB, the block
model (only its break particles: the block entity renderer draws the crate), the blockstate, a low-poly cuboid crate
for hands, the ground and item frames, the item definition and the loot table (the crate drops itself)."""
import json
import os

import numpy as np

import ammo_crate_model as cm
import raster as rs
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import load_materials

ID = "ammo_crate"
HERE = os.path.dirname(os.path.abspath(__file__))
GLB_PATH = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "block",
                        f"{ID}.glb")
DATA = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland")
PATCH = {"wood": 0, "wood_dark": 1, "marking": 2, "metal": 3, "rope": 4}


def icon_items(g, mats):
    """The crate at rest seen from the front left and above (a 3/4 view), centred in front of an orthographic camera."""
    W = g.world_matrices({})
    yaw, pitch = np.radians(-35.0), np.radians(28.0)
    ry = np.array([[np.cos(yaw), 0, np.sin(yaw)], [0, 1, 0], [-np.sin(yaw), 0, np.cos(yaw)]])
    rx = np.array([[1, 0, 0], [0, np.cos(pitch), -np.sin(pitch)], [0, np.sin(pitch), np.cos(pitch)]])
    turn = np.array([[-1.0, 0, 0], [0, 1, 0], [0, 0, -1.0]])          # look at the front (-Z) from the front
    view = rx @ ry @ turn
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
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 2.0])
    return items, allp.max(0) - allp.min(0)


def palette(mats):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(53)
    by_name = {m["name"]: m for m in mats}
    for name, k in PATCH.items():
        m = by_name[name.replace("_dark", "")]
        base = np.asarray(m["base"][:3], float)
        if m.get("tex") is not None:
            base = base * m["tex"][:, :, :3].reshape(-1, 3).mean(0)
        c = rs.linear_to_srgb(base) * (0.8 if name.endswith("_dark") else 1.0) * 0.95 + 0.05
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(c, (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.08
        patch[3] *= 0.86
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def particle(mats):
    """16 px of painted boards for the break particles (the wood set's albedo, averaged down)."""
    m = {mm["name"]: mm for mm in mats}["wood"]
    base = np.asarray(m["base"][:3], float)
    tex = m.get("tex")
    if tex is None:
        img = np.tile(base, (16, 16, 1))
    else:
        h, w = tex.shape[:2]
        img = tex[:h - h % 16, :w - w % 16, :3].reshape(16, h // 16, 16, w // 16, 3).mean((1, 3)) * base
    out = np.ones((16, 16, 4))
    out[:, :, :3] = np.clip(rs.linear_to_srgb(img), 0, 1)
    for y in (5, 10):                                                       # the board seams
        out[y, :, :3] *= 0.7
    return out


def _uv(name):
    k = PATCH[name]
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def _el(frm, to, mat, top=None):
    faces = {f: {"uv": _uv(mat), "texture": "#0"} for f in ("north", "south", "east", "west", "up", "down")}
    if top:
        faces["up"]["uv"] = _uv(top)
    return {"from": frm, "to": to, "faces": faces}


def u(v):
    """Block metres -> model units (x, z centred on 8)."""
    return round(float(v) * 16, 3)


def held_model(texture):
    """A cuboid crate at the block's scale (16 units = the block): skids, body, lid, band, latches, handles."""
    hx, hz = cm.W / 2, cm.D / 2
    e = [
        _el([8 - u(hx) + 0.2, 0, 8 - u(0.205)], [8 + u(hx) - 0.2, u(cm.SKID), 8 - u(0.135)], "wood_dark"),
        _el([8 - u(hx) + 0.2, 0, 8 + u(0.135)], [8 + u(hx) - 0.2, u(cm.SKID), 8 + u(0.205)], "wood_dark"),
        _el([8 - u(hx), u(cm.SKID), 8 - u(hz)], [8 + u(hx), u(cm.BODY_TOP), 8 + u(hz)], "wood"),
        _el([8 - u(hx) - 0.07, u(cm.BODY_TOP), 8 - u(hz) - 0.1], [8 + u(hx) + 0.07, u(cm.LID_TOP), 8 + u(hz) + 0.1],
            "wood", top="wood"),
        _el([8 - u(0.09), u(cm.LID_TOP), 8 - u(hz) + 0.5], [8 + u(0.09), u(cm.LID_TOP) + 0.01, 8 + u(hz) - 0.5], "marking"),
        _el([8 - u(0.09), u(cm.SKID) + 0.3, 8 - u(hz) - 0.02], [8 + u(0.09), u(cm.BODY_TOP) - 0.3, 8 - u(hz)], "marking"),
    ]
    for x in cm.LATCH_X:
        e.append(_el([8 + u(x) - 0.2, u(cm.LATCH_PIVOT_Y) - 0.4, 8 - u(hz) - 0.25], [8 + u(x) + 0.2, u(cm.LID_TOP) - 0.1,
                                                                                  8 - u(hz)], "metal"))
    for sx in (-1, 1):
        x0 = 8 + sx * u(hx)
        e.append(_el([min(x0, x0 + sx * 0.5), u(0.215), 8 - u(0.12)], [max(x0, x0 + sx * 0.5), u(0.30), 8 + u(0.12)], "rope"))
    display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        "head": {"rotation": [0, 0, 0], "translation": [0, 4, 0], "scale": [0.6, 0.6, 0.6]},
    }
    return {"textures": {"0": texture, "particle": f"blockland:block/{ID}"}, "elements": e, "display": display}


def build(path):
    g = GLTF(GLB_PATH)
    mats = load_materials(g)
    items, ext = icon_items(g, mats)
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{ID}.png"))
    write_json(path("models", "item", f"{ID}_icon.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{ID}"}})
    tx.save_png(palette(mats), path("textures", "item", f"{ID}_held.png"))
    write_json(path("models", "item", f"{ID}.json"), held_model(f"blockland:item/{ID}_held"))
    tx.save_png(particle(mats), path("textures", "block", f"{ID}.png"))
    write_json(path("models", "block", f"{ID}.json"), {"textures": {"particle": f"blockland:block/{ID}"}})
    write_json(path("blockstates", f"{ID}.json"),
               {"variants": {f"facing={f}": {"model": f"blockland:block/{ID}"} for f in ("north", "east", "south", "west")}})
    write_json(path("items", f"{ID}.json"),
               {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                          "cases": [{"when": ["gui"], "model": {"type": "minecraft:model", "model": f"blockland:item/{ID}_icon"}}],
                          "fallback": {"type": "minecraft:model", "model": f"blockland:item/{ID}"}}})
    loot = os.path.join(DATA, "loot_table", "blocks", f"{ID}.json")
    os.makedirs(os.path.dirname(loot), exist_ok=True)
    write_json(loot, {"type": "minecraft:block", "pools": [{"rolls": 1, "bonus_rolls": 0,
                                                            "entries": [{"type": "minecraft:item", "name": f"blockland:{ID}"}],
                                                            "conditions": [{"condition": "minecraft:survives_explosion"}]}],
                      "random_sequence": f"blockland:blocks/{ID}"})
    print(f"ammo crate items: icon, held / block models, blockstate, item definition, loot table")
