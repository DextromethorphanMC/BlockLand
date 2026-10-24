"""Thompson item assets (VIEWMODEL_SPEC sections 1, 8 and 24): skin icons rendered from tommy.glb (drum fitted: the
classic silhouette), icon / held item models, held-model palettes and items/tommy.json. The ammo item (smg_ammo)
comes from gen_items.py."""
import os

import numpy as np

import skin_looks
import textures as tx
from gen_ak_items import _element
from gen_items import patch_colours, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials
import third_person

GUN = "tommy"
METALLIC_SKINS = {"chicago": ("steel", "steel_barrel", "steel_dark")}       # icon only: polished look
SKIN_DATA = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
SKINS = list(SKIN_DATA)
UNIT = 0.044                      # metres per item-model unit (the AK's scale: the guns line up in the hand)


def skin_materials(mats, skin):
    out = skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))
    for m in out:
        if m["name"] in METALLIC_SKINS.get(skin, ()):
            m["metallic"], m["roughness"] = 1.0, 0.3
    return out


def side_items(g, mats):
    """Gun meshes in gun space (rest pose), right-side view, with the drum (the stick magazine hidden)."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"mag_stick", "fx_shell"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])          # look from +X, muzzle to the right
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


def render_icon(g, mats, size=32, angle=30.0):
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
ORDER = ["wood", "wood_top", "steel", "steel_top", "steel_dark", "mag", "mag_base", "bore"]
PATCH = {n: k for k, n in enumerate(ORDER)}


def palette(by_name):
    """16x16 palette: 4x4 px patches with a light top row and a dark bottom row."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(41)
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


def _box(x, y, z, mat, **kw):
    """Element from design-frame ranges (metres; bore on x = y = 0, +Z rear) in the AK's held-model frame."""
    f = lambda v, c: round(c + v / UNIT, 3)
    frm = [f(x[0], 8.0), f(y[0], 8.95), f(z[0], 7.3)]
    to = [f(x[1], 8.0), f(y[1], 8.95), f(z[1], 7.3)]
    return _element(frm, to, mat, **kw)


def held_model(texture):
    """Low-poly Thompson (muzzle towards north / -Z, ~19 units long, stick magazine) for third person / dropped."""
    import tommy_model as tm
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, round(8.95 - 0.047 / UNIT, 3), round(7.3 + 0.152 / UNIT, 3)]}
    stock_rot = {"angle": 22.5, "axis": "x", "origin": [8, round(8.95 - 0.027 / UNIT, 3), round(7.3 + 0.228 / UNIT, 3)]}
    w = tm.REC_W
    e = [
        _box((-w, w), (tm.REC_Y0, tm.REC_TOP), (tm.REC_Z0, tm.REC_Z1), "steel", top="steel_top"),     # receiver
        _box((-0.012, 0.012), (-0.050, tm.REC_Y0), (0.067, 0.300), "steel"),                          # frame
        _box((-0.006, 0.006), (-0.079, -0.050), (0.070, 0.150), "steel"),                             # guard
        _box((-0.008, 0.008), (-0.008, 0.008), (tm.CROWN_Z, tm.REC_Z0), "steel_dark"),                # barrel
        _box((-0.0118, 0.0118), (-0.0118, 0.0118), (-0.168, -0.030), "steel"),                        # fins
        _box((-0.012, 0.012), (-0.012, 0.0074), (tm.COMP_Z1, tm.CROWN_Z), "steel_dark", front="bore"),
        _box((-0.0025, 0.0025), (0.008, tm.SIGHT_Y), (-0.274, -0.264), "steel_dark"),                 # front sight
        _box((-0.009, 0.009), (tm.REC_TOP, tm.SIGHT_Y), (tm.RS_Z0, tm.RS_Z1), "steel_dark"),         # rear sight
        _box((-tm.RAIL_W, tm.RAIL_W), (tm.REC_TOP, tm.RAIL_TOP), (tm.RAIL_Z0, tm.RAIL_Z1), "steel_dark"),
        _box((-0.005, 0.005), (tm.REC_TOP, 0.027), (0.138, 0.150), "steel_dark"),                     # cocking knob
        _box((-0.012, 0.012), (-0.135, tm.REC_Y0), (-0.150, -0.062), "wood", top="wood_top"),        # foregrip
        _box((-0.0135, 0.0135), (-0.150, -0.047), (0.128, 0.182), "wood", rot=grip_rot),             # pistol grip
        _box((-0.018, 0.018), (-0.095, -0.027), (0.228, 0.520), "wood", top="wood_top", rot=stock_rot),
        _box((-0.0118, 0.0118), (-0.188, -0.004), (0.028, 0.062), "mag"),                            # stick magazine
        _box((-0.0130, 0.0130), (-0.194, -0.188), (0.026, 0.064), "mag_base"),                       # floor plate
    ]
    display = {
        "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 2.5], "scale": [0.55, 0.55, 0.55]},
        "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 2.5], "scale": [0.55, 0.55, 0.55]},
        "firstperson_righthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
        "firstperson_lefthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.45, 0.45, 0.45]},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
        "gui": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
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
        for split, src in (("wood", "wood"), ("steel", "steel")):         # the palette keys on the base names
            by_name.setdefault(split, by_name.get(src))
        tx.save_png(palette(by_name), path("textures", "item", f"{GUN}_held{suffix}.png"))
        if skin == "default":
            write_json(path("models", "item", f"{GUN}_held.json"), held_model(f"blockland:item/{GUN}_held"))
        else:
            tex = f"blockland:item/{GUN}_held{suffix}"
            write_json(path("models", "item", f"{GUN}_held{suffix}.json"),
                       {"parent": f"blockland:item/{GUN}_held", "textures": {"0": tex, "particle": tex}})
    write_json(path("items", f"{GUN}.json"), item_definition())
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins ({', '.join(['default'] + SKINS)})")
