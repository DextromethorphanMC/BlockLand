"""AK-47 item assets (VIEWMODEL_SPEC sections 1, 8 and 18): skin icons rendered from ak47.glb, icon / held item
models, held-model palettes, items/ak47.json, and the rifle_ammo item."""
import os

import numpy as np

import raster as rs
import skin_looks
import textures as tx
from gen_items import _outline, _put, patch_colours, render_items_icon, side_view_items, write_json
from glbread import GLTF
from preview import GUN_DIR, load_materials

GUN = "ak47"
# fallback skin data (display sRGB replacement colours / retextures); the gameplay data in guns.json wins when present
DEFAULT_SKINS = {
    "black": {"tints": {"wood": [0.16, 0.16, 0.17]}},
    "desert": {"tints": {"wood": [0.66, 0.55, 0.38], "steel": [0.60, 0.55, 0.45]}},
    "camo": {"textures": {"wood": "textures/gun/camo_digital.png"}},
    "gold": {"tints": {"steel": [1.0, 0.77, 0.34], "steel_dark": [1.0, 0.77, 0.34]}},
}
METALLIC_SKINS = {"gold": ("steel", "steel_dark")}                # icon only: polished look
AMMO = "rifle_ammo"


def load_skins():
    """Skins of the AK as the runtime has them (guns.json + the shared craft/skins.json), else the defaults above."""
    skins = {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}
    return skins or DEFAULT_SKINS


SKIN_DATA = load_skins()
SKINS = list(SKIN_DATA)


def skin_materials(mats, skin):
    out = skin_looks.skin_materials(mats, SKIN_DATA.get(skin, {}))
    for m in out:
        if m["name"] in METALLIC_SKINS.get(skin, ()):        # icon only: polished look
            m["metallic"], m["roughness"] = 1.0, 0.3
    return out


def render_icon(g, mats, size=32, angle=32.0):
    """Right-side view turned diagonally (muzzle up-right) so the long rifle fills the 32 px icon."""
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
ORDER = ["wood", "wood_top", "steel", "steel_top", "steel_dark", "mag", "mag_base", "bore"]
PATCH = {n: k for k, n in enumerate(ORDER)}


def palette(by_name):
    """16x16 palette: 4x4 px patches with a light top row and a dark bottom row."""
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(37)
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


def held_model(texture):
    """Low-poly AK (muzzle towards north / -Z, ~21 units long) for third person and dropped items."""
    stock_rot = {"angle": 22.5, "axis": "x", "origin": [8, 9.0, 12.6]}
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 8.1, 11.1]}
    mag_rot = {"angle": 22.5, "axis": "x", "origin": [8, 7.0, 8.0]}
    e = [
        _element([7.4, 8.1, 7.3], [8.6, 9.4, 12.6], "steel"),                       # receiver
        _element([7.45, 9.4, 8.3], [8.55, 9.8, 12.6], "steel", top="steel_top"),     # dust cover
        _element([7.6, 9.4, 6.5], [8.4, 10.0, 7.5], "steel"),                        # rear sight block
        _element([7.3, 8.3, 3.2], [8.7, 9.4, 7.3], "wood", top="wood_top"),          # lower handguard
        _element([7.6, 9.4, 3.2], [8.4, 10.0, 6.5], "wood", top="wood_top"),         # upper handguard
        _element([7.75, 8.7, -1.4], [8.25, 9.2, 3.2], "steel_dark"),                 # barrel
        _element([7.7, 8.6, 2.4], [8.3, 9.9, 3.2], "steel"),                         # gas block
        _element([7.8, 9.2, 0.3], [8.2, 10.4, 0.9], "steel_dark"),                   # front sight
        _element([7.7, 8.65, -2.2], [8.3, 9.25, -1.4], "steel_dark", front="bore"),  # slant brake
        _element([7.5, 6.6, 12.6], [8.5, 9.0, 18.0], "wood", top="wood_top", rot=stock_rot),
        _element([7.45, 5.9, 10.6], [8.55, 8.1, 11.6], "wood", rot=grip_rot),       # pistol grip
        _element([7.8, 7.6, 9.0], [8.2, 8.1, 10.6], "steel"),                        # trigger guard
        _element([7.4, 6.8, 7.6], [8.6, 8.1, 8.9], "mag"),                           # magazine (top)
        _element([7.4, 4.7, 7.4], [8.6, 7.0, 8.7], "mag", rot=mag_rot),              # magazine (curve)
        _element([7.35, 4.4, 7.3], [8.65, 4.8, 8.8], "mag_base", rot=mag_rot),       # floor plate
        _element([8.6, 9.0, 8.7], [9.2, 9.3, 9.0], "steel_dark"),                    # charging handle
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
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui", "ground", "fixed"], "model": skin_select(GUN)}],
                      "fallback": skin_select(f"{GUN}_held")}}


# ------------------------------------------------------------------------------------------- ammo
LACQUER = {"G": (0.60, 0.57, 0.36), "g": (0.44, 0.42, 0.25), "q": (0.29, 0.27, 0.15), "C": (0.92, 0.62, 0.42),
           "c": (0.80, 0.48, 0.30), "k": (0.55, 0.30, 0.18), "s": (0.62, 0.64, 0.66), "S": (0.40, 0.41, 0.44)}


def rifle_ammo_icon():
    """Three 7.62x39 rounds (lacquered steel cases, copper-jacketed bullets) in a steel stripper clip."""
    img = np.zeros((16, 16, 4))
    rnd = [".C.", "Cck", "Cck", ".g.", "Ggq", "Ggq", "Ggq", "Ggq", "Ggq", "Ggq", "qqq"]
    for x0, y0 in ((2, 2), (6, 1), (10, 2)):
        _put(img, x0, y0, rnd, LACQUER)
    _put(img, 1, 13, ["SsssssssssssS", "SSSSSSSSSSSSS"], LACQUER)
    return _outline(img)


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
    tx.save_png(rifle_ammo_icon(), path("textures", "item", f"{AMMO}.png"))
    write_json(path("models", "item", f"{AMMO}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{AMMO}"}})
    write_json(path("items", f"{AMMO}.json"), {"model": {"type": "minecraft:model", "model": f"blockland:item/{AMMO}"}})
    print(f"items: {GUN} icons/models for {1 + len(SKINS)} skins, {AMMO}")
