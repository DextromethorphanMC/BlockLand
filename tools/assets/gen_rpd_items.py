"""RPD item assets (VIEWMODEL_SPEC sections 1, 8 and 33): skin icons rendered from rpd.glb, icon / held item models,
held-model palettes and items/rpd.json. The ammo item (rifle_ammo) is the AK's (gen_ak_items.py)."""
import os

import numpy as np

import skin_looks
import textures as tx
from gen_items import patch_colours, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials
import third_person

GUN = "rpd"
SKIN_DATA = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
SKINS = list(SKIN_DATA)
UNIT = 0.044                      # metres per item-model unit (the AK's scale)


def skin_materials(mats, skin):
    return skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))


def side_items(g, mats):
    """Gun meshes in gun space (rest pose), right-side view, 100-round drum fitted."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"mag_extended", "fx_shell"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or i in hide:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            items.append({"pos": (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T,
                          "nrm": p["normals"] @ M[:3, :3].T @ view.T, "uv": p["uvs"], "idx": p["indices"],
                          "mat": mats[p["material"]]})
    return items


def render_icon(g, mats, size=32, angle=28.0):
    """Right-side view turned diagonally (muzzle up-right) so the long gun fills the 32 px icon."""
    items = side_items(g, mats)
    a = np.radians(angle)
    rot = np.array([[np.cos(a), -np.sin(a), 0.0], [np.sin(a), np.cos(a), 0.0], [0.0, 0.0, 1.0]])
    for it in items:
        z = it["pos"][:, 2].copy()
        it["pos"] = it["pos"] @ rot.T
        it["pos"][:, 2] = z
        it["nrm"] = it["nrm"] @ rot.T
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"][:, :2] -= c[:2]
        it["pos"][:, 2] -= c[2] + 1.0
    return render_items_icon(items, allp.max(0) - allp.min(0), size, ss=8, margin=1)


# ------------------------------------------------------------------------------------ held model
ORDER = ["wood", "wood_top", "steel", "steel_top", "steel_dark", "mag", "mag_base", "bore", "brass"]
PATCH = {n: k for k, n in enumerate(ORDER)}


def palette(by_name):
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(43)
    for k, name in enumerate(ORDER):
        src = {"wood_top": "wood", "steel_top": "steel", "bore": "steel_dark"}.get(name, name)
        c = patch_colours(by_name[src], k) * 0.95 + 0.06
        if name.endswith("_top"):
            c = c * 1.12 + 0.03
        if name == "bore":
            c = np.full((4, 4, 3), 0.02)
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = c * (0.94 + 0.10 * rng.random((4, 4, 1)))
        patch[0] *= 1.10
        patch[3] *= 0.85
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def _uv(name):
    k = PATCH[name]
    x0, y0 = (k % 4) * 4, (k // 4) * 4
    return [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]


def _element(frm, to, mat, top=None, rot=None, front=None):
    el = {"from": frm, "to": to, "faces": {}}
    for f in ("north", "south", "east", "west", "up", "down"):
        m = top if (f == "up" and top) else (front if (f == "north" and front) else mat)
        el["faces"][f] = {"uv": _uv(m), "texture": "#0"}
    if rot:
        el["rotation"] = rot
    return el


def _box(x, y, z, mat, **kw):
    f = lambda v, c: round(c + v / UNIT, 3)
    return _element([f(x[0], 8.0), f(y[0], 8.95), f(z[0], 7.3)], [f(x[1], 8.0), f(y[1], 8.95), f(z[1], 7.3)], mat, **kw)


def held_model(texture):
    """Low-poly RPD (muzzle towards north / -Z) for third person / dropped: receiver, cover, barrel, gas tube, handguard,
    grip, stock and the drum."""
    import rpd_model as rm
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, round(8.95 - 0.036 / UNIT, 3), round(7.3 + 0.188 / UNIT, 3)]}
    stock_rot = {"angle": 22.5, "axis": "x", "origin": [8, round(8.95 - 0.020 / UNIT, 3), round(7.3 + 0.262 / UNIT, 3)]}
    w = rm.REC_W
    e = [
        _box((-w, w), (rm.REC_Y0, rm.REC_Y1), (0.0, rm.REC_Z1), "steel", top="steel_top"),
        _box((-w, w), (rm.REC_Y1, rm.COVER_TOP), (rm.COVER_Z0, rm.COVER_Z1), "steel", top="steel_top"),
        _box((-0.009, 0.009), (-0.009, 0.009), (rm.CROWN_Z, 0.0), "steel_dark"),
        _box((-0.0115, 0.0115), (-0.0115, 0.0115), (rm.MUZZLE_Z, rm.CROWN_Z), "steel_dark", front="bore"),
        _box((-0.0085, 0.0085), (rm.GAS_Y - 0.0085, rm.GAS_Y + 0.0085), (rm.GAS_Z0, rm.GAS_Z1), "steel"),
        _box((-0.023, 0.023), (rm.GAS_Y - 0.016, 0.012), (rm.HG_Z0, rm.HG_Z1), "wood", top="wood_top"),
        _box((-0.0025, 0.0025), (0.010, rm.SIGHT_Y), (rm.FRONT_POST_Z - 0.006, rm.FRONT_POST_Z + 0.006), "steel_dark"),
        _box((-0.013, 0.013), (-0.130, -0.036), (0.170, 0.210), "wood", rot=grip_rot),
        _box((-0.019, 0.019), (-0.105, -0.020), (0.262, 0.532), "wood", top="wood_top", rot=stock_rot),
        _box((-0.068, 0.060), (-0.172, -0.044), (0.029, 0.091), "mag"),                          # drum (axis along Z)
        _box((-0.052, 0.044), (-0.156, -0.060), (0.027, 0.093), "mag_base"),
        _box((-0.040, -0.022), (-0.044, 0.022), (0.034, 0.090), "brass"),                        # belt
    ]
    display = {
        "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 3.0], "scale": [0.52, 0.52, 0.52]},
        "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 3.0], "scale": [0.52, 0.52, 0.52]},
        "firstperson_righthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.56, 0.56, 0.56]},
        "firstperson_lefthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.56, 0.56, 0.56]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.42, 0.42, 0.42]},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
        "gui": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.8, 0.8, 0.8]},
    }
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def skin_select(x):
    return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": s, "model": {"type": "minecraft:model", "model": f"blockland:item/{x}_{s}"}}
                      for s in SKINS],
            "fallback": {"type": "minecraft:model", "model": f"blockland:item/{x}"}}


def item_definition():
    return third_person.weapon_definition(skin_select(GUN), skin_select(f"{GUN}_held"))


def build(path):
    g = GLTF(os.path.join(GUN_DIR, f"{GUN}.glb"))
    base_mats = load_materials(g)
    for skin in ["default"] + SKINS:
        mats = skin_materials(base_mats, skin) if skin != "default" else base_mats
        suffix = "" if skin == "default" else f"_{skin}"
        tx.save_png(render_icon(g, mats), path("textures", "item", f"{GUN}{suffix}.png"))
        write_json(path("models", "item", f"{GUN}{suffix}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{GUN}{suffix}"}})
        by_name = {m["name"]: m for m in mats}
        tx.save_png(palette(by_name), path("textures", "item", f"{GUN}_held{suffix}.png"))
        if skin == "default":
            write_json(path("models", "item", f"{GUN}_held.json"), held_model(f"blockland:item/{GUN}_held"))
        else:
            tex = f"blockland:item/{GUN}_held{suffix}"
            write_json(path("models", "item", f"{GUN}_held{suffix}.json"),
                       {"parent": f"blockland:item/{GUN}_held", "textures": {"0": tex, "particle": tex}})
    write_json(path("items", f"{GUN}.json"), item_definition())
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins ({', '.join(['default'] + SKINS)})")
