"""AWP item assets (VIEWMODEL_SPEC sections 1, 8 and 23): skin icons rendered from awp.glb with its default sniper
scope, icon / held item models, held-model palettes and items/awp.json (the sniper_ammo item exists already)."""
import os

import numpy as np

import loadouts as lo
import skin_looks
import textures as tx
from gen_items import patch_colours, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, hidden_set, load_materials

GUN = "awp"
SCOPE = "scope_sniper"                                             # the default optic (guns.json)
DEFAULT_SKINS = {
    "black": {"tints": {"stock": [0.13, 0.13, 0.14], "grip": [0.13, 0.13, 0.14]}},
    "desert": {"tints": {"stock": [0.66, 0.55, 0.38], "grip": [0.66, 0.55, 0.38]}},
}


def load_skins():
    """Skins of the AWP as the runtime has them (guns.json + the shared craft/skins.json), else the defaults above."""
    skins = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
    return skins or DEFAULT_SKINS


SKIN_DATA = load_skins()
SKINS = list(SKIN_DATA)


def skin_materials(mats, skin):
    return skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))


def side_view_items(g, mats, scope_mats):
    """Rifle and its scope in gun space (rest pose), rotated to a right-side view, centred."""
    gi = g.names["gun"]
    W = g.world_matrices({gi: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    hide = hidden_set(g, {"fx_shell"})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])          # look from +X, muzzle to the right
    items = []

    def add(model, i, M, mat_list):
        for p in model.mesh_primitives(model.nodes[i]["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mat_list[p["material"]]})

    for i, n in enumerate(g.nodes):
        if "mesh" in n and i not in hide:
            add(g, i, W[i], mats)
    scope = lo.model(SCOPE)
    SW = scope.world_matrices({})
    mount = W[g.names["mount_optic"]]
    for i, n in enumerate(scope.nodes):
        if "mesh" in n:
            add(scope, i, mount @ SW[i], scope_mats)
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 1.0])
    return items, allp.max(0) - allp.min(0)


def render_icon(g, mats, scope_mats, size=32, angle=28.0):
    """Right-side view turned diagonally (muzzle up-right) so the long rifle fills the 32 px icon."""
    items, _ = side_view_items(g, mats, scope_mats)
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
ORDER = ["stock", "stock_top", "receiver", "receiver_top", "barrel", "scope", "mag", "bore", "bolt"]
PATCH = {n: k for k, n in enumerate(ORDER)}
SOURCE = {"stock_top": "stock", "receiver_top": "receiver", "scope": "scope", "bore": "steel_dark"}


def palette(by_name, scope_by_name):
    """16x16 palette: 4x4 px patches with a light top row and a dark bottom row."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(41)
    for k, name in enumerate(ORDER):
        src = SOURCE.get(name, name)
        m = scope_by_name[src] if name == "scope" else by_name[src]
        c = patch_colours(m, k) * 0.95 + 0.06
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


def _element(frm, to, mat, top=None, rot=None, front=None, dz=-4.5):
    """One box (model units, z shifted by dz so the grip sits where the AK's does in the hand)."""
    el = {"from": [frm[0], frm[1], frm[2] + dz], "to": [to[0], to[1], to[2] + dz], "faces": {}}
    for f in ("north", "south", "east", "west", "up", "down"):
        m = top if (f == "up" and top) else (front if (f == "north" and front) else mat)
        el["faces"][f] = {"uv": _uv(m), "texture": "#0"}
    if rot:
        el["rotation"] = dict(rot, origin=[rot["origin"][0], rot["origin"][1], rot["origin"][2] + dz])
    return el


def held_model(texture):
    """Low-poly AWP with its scope (muzzle towards north / -Z, ~32 units long) for third person and dropped items."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 7.3, 15.4]}
    e = [
        _element([7.6, 8.6, -8.2], [8.4, 9.4, -6.6], "barrel", front="bore"),        # muzzle brake
        _element([7.72, 8.72, -6.6], [8.28, 9.28, 2.0], "barrel"),                    # barrel (free floated)
        _element([7.68, 8.68, 2.0], [8.32, 9.32, 9.0], "barrel"),
        _element([7.3, 7.5, 1.6], [8.7, 9.15, 9.0], "stock", top="stock_top"),        # forend
        _element([7.45, 8.4, 9.0], [8.55, 9.95, 15.2], "receiver", top="receiver_top"),  # action with the rail
        _element([7.3, 7.0, 9.0], [8.7, 8.5, 15.6], "stock"),                          # chassis
        _element([7.55, 5.5, 10.0], [8.45, 7.0, 12.6], "mag"),                         # magazine
        _element([7.8, 6.4, 13.0], [8.2, 7.0, 15.0], "stock"),                         # trigger guard
        _element([7.45, 4.9, 15.0], [8.55, 7.2, 16.2], "stock", rot=grip_rot),        # thumbhole grip
        _element([7.5, 7.2, 15.6], [8.5, 9.0, 19.0], "stock", top="stock_top"),       # wrist / comb
        _element([7.45, 5.6, 19.0], [8.55, 9.8, 23.4], "stock", top="stock_top"),     # butt
        _element([7.6, 4.9, 16.0], [8.4, 5.6, 21.0], "stock"),                         # lower bar
        _element([7.5, 9.8, 18.4], [8.5, 10.2, 21.4], "stock", top="stock_top"),      # cheek piece
        _element([7.4, 5.5, 23.4], [8.6, 9.9, 24.0], "bore"),                          # butt pad
        _element([7.6, 8.5, 15.2], [8.4, 9.5, 16.4], "bolt"),                          # bolt shroud
        _element([8.55, 8.55, 13.9], [9.7, 8.85, 14.3], "bolt"),                       # bolt handle
        _element([9.5, 8.35, 13.75], [10.0, 8.95, 14.45], "bolt"),                     # knob
        _element([7.65, 10.2, 8.4], [8.35, 10.9, 15.0], "scope"),                      # scope tube
        _element([7.4, 9.95, 5.4], [8.6, 11.15, 8.4], "scope", front="bore"),         # objective bell
        _element([7.55, 10.1, 15.0], [8.45, 11.0, 16.6], "scope"),                     # eyepiece
        _element([7.8, 10.9, 11.2], [8.2, 11.5, 12.0], "scope"),                       # elevation turret
        _element([8.35, 10.35, 11.2], [8.9, 10.75, 12.0], "scope"),                    # windage turret
        _element([7.75, 9.9, 9.6], [8.25, 10.25, 10.3], "receiver"),                   # rings
        _element([7.75, 9.9, 13.4], [8.25, 10.25, 14.1], "receiver"),
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
    scope = lo.model(SCOPE)
    base_scope = load_materials(scope)
    for skin in ["default"] + SKINS:
        mats = skin_materials(base_mats, skin) if skin != "default" else base_mats
        smats = skin_materials(base_scope, skin) if skin != "default" else base_scope   # optic region skins too
        suffix = "" if skin == "default" else f"_{skin}"
        tx.save_png(render_icon(g, mats, smats), path("textures", "item", f"{GUN}{suffix}.png"))
        write_json(path("models", "item", f"{GUN}{suffix}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{GUN}{suffix}"}})
        tx.save_png(palette({m["name"]: m for m in mats}, {m["name"]: m for m in smats}),
                    path("textures", "item", f"{GUN}_held{suffix}.png"))
        if skin == "default":
            write_json(path("models", "item", f"{GUN}_held.json"), held_model(f"blockland:item/{GUN}_held"))
        else:
            tex = f"blockland:item/{GUN}_held{suffix}"
            write_json(path("models", "item", f"{GUN}_held{suffix}.json"),
                       {"parent": f"blockland:item/{GUN}_held", "textures": {"0": tex, "particle": tex}})
    write_json(path("items", f"{GUN}.json"), item_definition())
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins")
