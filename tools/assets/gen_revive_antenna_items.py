"""Revive antenna block and item assets (VIEWMODEL_SPEC section 51): the inventory icon rendered from the GLB, the block
model (only its break particles: the block entity renderer of the lower half draws the whole antenna), the blockstate
of both halves, a low-poly cuboid antenna for hands, the ground and item frames, the item definition and the loot table
(the lower half drops the item, the upper half nothing)."""
import os

import numpy as np

import raster as rs
import revive_antenna_model as am
import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import load_materials

ID = "revive_antenna"
HERE = os.path.dirname(os.path.abspath(__file__))
GLB_PATH = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "block",
                        f"{ID}.glb")
DATA = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland")
# palette patch -> (material, brightness)
PATCH = {"case": ("case", 1.0), "case_dark": ("case", 0.75), "black": ("case_dark", 1.0), "metal": ("hardware", 1.0),
         "mast": ("mast", 1.0), "white": ("panel_white", 1.0), "red": ("panel_red", 1.0), "beacon": ("beacon", 1.0),
         "dish": ("dish", 1.0)}


def icon_items(g, mats):
    """The antenna (beacon lit) seen from the front left and a little above, centred in front of an orthographic camera."""
    W = g.world_matrices({})
    yaw, pitch = np.radians(-35.0), np.radians(18.0)
    ry = np.array([[np.cos(yaw), 0, np.sin(yaw)], [0, 1, 0], [-np.sin(yaw), 0, np.cos(yaw)]])
    rx = np.array([[1, 0, 0], [0, np.cos(pitch), -np.sin(pitch)], [0, np.sin(pitch), np.cos(pitch)]])
    turn = np.array([[-1.0, 0, 0], [0, 1, 0], [0, 0, -1.0]])          # look at the front (-Z) from the front
    view = rx @ ry @ turn
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or n.get("name") == "beacon_off":
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mats[p["material"]]})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 3.0])
    return items, allp.max(0) - allp.min(0)


def _colour(m):
    base = np.asarray(m["base"][:3], float)
    if m.get("tex") is not None:
        base = base * m["tex"][:, :, :3].reshape(-1, 3).mean(0)
    if np.any(m["emissive"] > 0):
        base = np.maximum(base, m["emissive"])
    return rs.linear_to_srgb(base)


def palette(mats):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(57)
    by_name = {m["name"]: m for m in mats}
    for k, (name, (mat, bright)) in enumerate(PATCH.items()):
        c = _colour(by_name[mat]) * bright * 0.95 + 0.05
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(c, (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.08
        patch[3] *= 0.86
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def particle(mats):
    """16 px of the olive case for the break particles (the set's albedo, averaged down), a rib across it."""
    m = {mm["name"]: mm for mm in mats}["case"]
    base = np.asarray(m["base"][:3], float)
    tex = m.get("tex")
    if tex is None:
        img = np.tile(base, (16, 16, 1))
    else:
        h, w = tex.shape[:2]
        img = tex[:h - h % 16, :w - w % 16, :3].reshape(16, h // 16, 16, w // 16, 3).mean((1, 3)) * base
    out = np.ones((16, 16, 4))
    out[:, :, :3] = np.clip(rs.linear_to_srgb(img), 0, 1)
    out[7, :, :3] *= 1.15
    out[8, :, :3] *= 0.72
    return out


def _uv(name):
    k = list(PATCH).index(name)
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def _el(frm, to, mat, top=None):
    faces = {f: {"uv": _uv(mat), "texture": "#0"} for f in ("north", "south", "east", "west", "up", "down")}
    if top:
        faces["up"]["uv"] = _uv(top)
    return {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}


def px(v, centre=8.0):
    """Block metres -> model units (x, z centred on 8)."""
    return round(centre + float(v) * 16, 3)


def held_model(texture):
    """A cuboid antenna at the block's scale (16 units = a block, two blocks tall): case, lid, panel, mast, head, dish,
    whip and beacon."""
    hx, z0, z1 = am.CW / 2, am.CZ - am.CD / 2, am.CZ + am.CD / 2
    mx, mz = am.MAST_X, am.MAST_Z
    e = [
        _el([px(-hx), px(am.FEET, 0), px(z0)], [px(hx), px(am.SHELL_TOP, 0), px(z1)], "case"),
        _el([px(-hx) + 0.1, px(am.SHELL_TOP, 0), px(z0) + 0.1], [px(hx) - 0.1, px(am.LID_TOP, 0), px(z1) - 0.1], "case",
            top="case_dark"),
        _el([px(-0.065), px(0.04, 0), px(z0) - 0.1], [px(0.065), px(0.15, 0), px(z0)], "white"),
        _el([px(-0.042), px(0.083, 0), px(z0) - 0.15], [px(0.042), px(0.107, 0), px(z0) - 0.1], "red"),
        _el([px(-0.012), px(0.053, 0), px(z0) - 0.15], [px(0.012), px(0.137, 0), px(z0) - 0.1], "red"),
        _el([px(-0.235), px(am.LID_TOP, 0), px(z0 + 0.025)], [px(-0.065), px(am.LID_TOP + 0.052, 0), px(z0 + 0.16)], "black"),
        _el([px(mx - 0.042), px(am.LID_TOP, 0), px(mz - 0.042)], [px(mx + 0.042), px(am.LID_TOP + 0.075, 0), px(mz + 0.042)],
            "metal"),
    ]
    for r, y0, y1 in am.MAST:
        e.append(_el([px(mx - r), px(y0, 0), px(mz - r)], [px(mx + r), px(y1, 0), px(mz + r)], "mast"))
    hx0, hy0, hz0, hx1, hy1, hz1 = am.HEAD
    e.append(_el([px(hx0), px(hy0, 0), px(hz0)], [px(hx1), px(hy1, 0), px(hz1)], "case"))
    dc, dr = am.DISH_C, am.DISH_R
    e.append(_el([px(dc[0] - dr * 0.85), px(dc[1] - dr * 0.85, 0), px(dc[2])],
                 [px(dc[0] + dr * 0.85), px(dc[1] + dr * 0.85, 0), px(dc[2] + am.DISH_DEPTH)], "dish"))
    e.append(_el([px(am.WHIP_X - 0.005), px(hy1, 0), px(mz - 0.005)], [px(am.WHIP_X + 0.005), px(am.WHIP_TOP, 0), px(mz + 0.005)],
                 "black"))
    b = am.BEACON
    e.append(_el([px(b[0] - am.BEACON_R), px(b[1], 0), px(b[2] - am.BEACON_R)],
                 [px(b[0] + am.BEACON_R), px(b[1] + 0.012 + am.BEACON_H, 0), px(b[2] + am.BEACON_R)], "beacon"))
    display = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, -4, 0], "scale": [0.4, 0.4, 0.4]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.2, 0.2, 0.2]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, -4, 0], "scale": [0.45, 0.45, 0.45]},
        "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -1, 1.5], "scale": [0.3, 0.3, 0.3]},
        "thirdperson_lefthand": {"rotation": [0, 45, 0], "translation": [0, -1, 1.5], "scale": [0.3, 0.3, 0.3]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -2, 0], "scale": [0.28, 0.28, 0.28]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -2, 0], "scale": [0.28, 0.28, 0.28]},
        "head": {"rotation": [0, 0, 0], "translation": [0, 6, 0], "scale": [0.4, 0.4, 0.4]},
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
               {"variants": {f"facing={f},half={h}": {"model": f"blockland:block/{ID}"}
                             for f in ("north", "east", "south", "west") for h in ("lower", "upper")}})
    write_json(path("items", f"{ID}.json"),
               {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                          "cases": [{"when": ["gui"], "model": {"type": "minecraft:model", "model": f"blockland:item/{ID}_icon"}}],
                          "fallback": {"type": "minecraft:model", "model": f"blockland:item/{ID}"}}})
    loot = os.path.join(DATA, "loot_table", "blocks", f"{ID}.json")
    os.makedirs(os.path.dirname(loot), exist_ok=True)
    write_json(loot, {"type": "minecraft:block", "pools": [{
        "rolls": 1, "bonus_rolls": 0,
        "entries": [{"type": "minecraft:item", "name": f"blockland:{ID}",
                     "conditions": [{"condition": "minecraft:block_state_property", "block": f"blockland:{ID}",
                                     "properties": {"half": "lower"}}]}],
        "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": f"blockland:blocks/{ID}"})
    print("revive antenna items: icon, held / block models, blockstate, item definition, loot table")
