"""KS-3 item assets (VIEWMODEL_SPEC sections 1, 8 and 25): skin icons rendered from ks3.glb (iron sights, standard
tube), icon / held item models, held-model palettes and items/ks3.json (the shotgun_ammo item exists already)."""
import os

import numpy as np

import skin_looks
import textures as tx
from gen_items import patch_colours, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials

GUN = "ks3"
DEFAULT_SKINS = {"olive": {"tints": {"stock": [0.30, 0.34, 0.20], "pump": [0.30, 0.34, 0.20]}}}


def load_skins():
    """Skins of the KS-3 as the runtime has them (guns.json + the shared craft/skins.json), else the defaults above."""
    skins = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
    return skins or DEFAULT_SKINS


SKIN_DATA = load_skins()
SKINS = list(SKIN_DATA)


def skin_materials(mats, skin):
    return skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))


def side_view_items(g, mats):
    """The shotgun in gun space (rest pose), turned to a right-side view, centred (helpers and the extended tube
    left out)."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"fx_shell", "shell_load", "tube_extended"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])          # look from +X, muzzle to the right
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or i in hide or abs(np.linalg.det(W[i][:3, :3])) < 1e-9:
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


def render_icon(g, mats, size=32, angle=30.0):
    """Right-side view turned diagonally (muzzle up-right) so the long gun fills the 32 px icon."""
    items, _ = side_view_items(g, mats)
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
    return render_items_icon(items, allp.max(0) - allp.min(0), size, ss=8, margin=1)


# ------------------------------------------------------------------------------------ held model
ORDER = ["stock", "stock_top", "receiver", "receiver_top", "barrel", "barrel_top", "pump", "grip", "rail", "bore",
         "pad", "pump_top"]
PATCH = {n: k for k, n in enumerate(ORDER)}
SOURCE = {"stock_top": "stock", "receiver_top": "receiver", "barrel_top": "barrel", "rail": "receiver",
          "bore": "steel_dark", "pad": "steel_dark", "pump_top": "pump"}


def palette(by_name):
    """16x16 palette: 4x4 px patches with a light top row and a dark bottom row."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(43)
    for k, name in enumerate(ORDER):
        m = by_name[SOURCE.get(name, name)]
        c = patch_colours(m, k) * 0.95 + 0.06
        if name.endswith("_top"):
            c = c * 1.12 + 0.03
        if name == "rail":
            c = c * 0.8
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


def _element(frm, to, mat, top=None, rot=None, front=None, dz=-4.0):
    """One box (model units, z shifted by dz so the pistol grip sits where the AK's does in the hand)."""
    el = {"from": [frm[0], frm[1], frm[2] + dz], "to": [to[0], to[1], to[2] + dz], "faces": {}}
    for f in ("north", "south", "east", "west", "up", "down"):
        m = top if (f == "up" and top) else (front if (f == "north" and front) else mat)
        el["faces"][f] = {"uv": _uv(m), "texture": "#0"}
    if rot:
        el["rotation"] = dict(rot, origin=[rot["origin"][0], rot["origin"][1], rot["origin"][2] + dz])
    return el


def held_model(texture):
    """Low-poly KS-3 (muzzle towards north / -Z, ~30 units long) for third person and dropped items."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 7.6, 13.4]}
    strut_rot = {"angle": 22.5, "axis": "x", "origin": [8, 7.4, 14.0]}
    e = [
        _element([7.5, 9.0, -7.0], [8.5, 10.0, -5.6], "barrel", front="bore"),          # choke
        _element([7.62, 9.12, -5.6], [8.38, 9.88, 8.2], "barrel"),                        # barrel
        _element([7.75, 9.88, -5.4], [8.25, 10.15, 7.6], "barrel_top"),                   # vent rib
        _element([7.8, 10.15, -5.0], [8.2, 10.7, -4.6], "bore"),                          # front sight
        _element([7.6, 7.85, -2.6], [8.4, 8.75, 8.2], "barrel"),                          # magazine tube
        _element([7.25, 7.2, -0.6], [8.75, 9.15, 5.0], "pump", top="pump_top"),          # forend
        _element([7.35, 7.4, 8.0], [8.65, 10.25, 14.0], "receiver", top="receiver_top"),  # receiver
        _element([7.58, 10.25, 8.6], [8.42, 10.75, 13.6], "rail"),                         # picatinny rail
        _element([7.8, 6.7, 10.8], [8.2, 7.4, 12.8], "receiver"),                          # trigger guard
        _element([7.4, 3.0, 12.4], [8.6, 7.4, 14.4], "grip", rot=grip_rot),               # pistol grip
        _element([7.45, 9.0, 14.0], [8.55, 9.9, 21.4], "stock", top="stock_top"),         # comb bar
        _element([7.5, 6.6, 14.0], [8.5, 7.4, 20.2], "stock", rot=strut_rot),             # lower strut
        _element([7.4, 5.5, 20.9], [8.6, 9.9, 22.3], "stock", top="stock_top"),           # butt
        _element([7.35, 5.4, 22.3], [8.65, 10.0, 22.9], "pad"),                            # butt pad
    ]
    display = {
        "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 2.5], "scale": [0.55, 0.55, 0.55]},
        "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 2.5], "scale": [0.55, 0.55, 0.55]},
        "firstperson_righthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
        "firstperson_lefthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.55, 0.55, 0.55]},
        "gui": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.55, 0.55, 0.55]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.7, 0.7, 0.7]},
    }
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def skin_select(x):
    return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": s, "model": {"type": "minecraft:model", "model": f"blockland:item/{x}_{s}"}}
                      for s in SKINS],
            "fallback": {"type": "minecraft:model", "model": f"blockland:item/{x}"}}


def item_definition():
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui", "ground", "fixed"], "model": skin_select(GUN)}],
                      "fallback": skin_select(f"{GUN}_held")}}


# ------------------------------------------------------------------------------------------- build
def build(path):
    g = GLTF(os.path.join(GUN_DIR, f"{GUN}.glb"))
    base_mats = load_materials(g)
    for skin in ["default"] + SKINS:
        mats = skin_materials(base_mats, skin) if skin != "default" else base_mats
        suffix = "" if skin == "default" else f"_{skin}"
        tx.save_png(render_icon(g, mats), path("textures", "item", f"{GUN}{suffix}.png"))
        write_json(path("models", "item", f"{GUN}{suffix}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{GUN}{suffix}"}})
        tx.save_png(palette({m["name"]: m for m in mats}), path("textures", "item", f"{GUN}_held{suffix}.png"))
        if skin == "default":
            write_json(path("models", "item", f"{GUN}_held.json"), held_model(f"blockland:item/{GUN}_held"))
        else:
            tex = f"blockland:item/{GUN}_held{suffix}"
            write_json(path("models", "item", f"{GUN}_held{suffix}.json"),
                       {"parent": f"blockland:item/{GUN}_held", "textures": {"0": tex, "particle": tex}})
    write_json(path("items", f"{GUN}.json"), item_definition())
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins")
