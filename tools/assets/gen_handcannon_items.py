"""Hand cannon item assets (VIEWMODEL_SPEC sections 1, 8 and 43): skin icons rendered from handcannon.glb, icon / held
item models, held-model palettes and items/handcannon.json (the USP's tools: gen_usp_items), and the 40 mm
`grenade_ammo` item: a flat icon in the inventory, a small 3D round elsewhere (the launched grenade is drawn with it)."""
import os

import numpy as np

import skin_looks
import textures as tx
from gen_items import BRASS, _element, _outline, _put, _uv, palette, write_json
from gen_usp_items import DISPLAY, _write_skin, icon, item_definition, side_items
from glbread import GLTF
from preview import GUN_DIR, load_materials

GUN = "handcannon"
AMMO = "grenade_ammo"


def _skins():
    return {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}


def held(texture):
    """Low-poly hand cannon (muzzle north, 50 units per metre): the olive tube with its rib and tall front blade, the
    receiver with the rail and rear sight, the hammer, the squared guard, the raked grip."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 7.0, 15.0]}
    e = [
        _element([6.70, 7.70, 0.40], [9.30, 10.30, 12.80], "barrel"),                  # the tube
        _element([7.85, 10.30, 0.70], [8.15, 10.45, 12.20], "steel_dark"),              # rib
        _element([7.90, 10.30, 0.55], [8.10, 11.30, 1.35], "steel_dark"),               # front blade
        _element([6.62, 7.00, 12.80], [9.38, 10.40, 16.40], "polymer"),                # receiver
        _element([7.50, 10.40, 13.00], [8.50, 10.80, 16.00], "slide", top="slide_top"),  # rail
        _element([7.55, 10.80, 15.60], [8.45, 11.30, 16.20], "steel_dark"),             # rear sight
        _element([7.80, 9.40, 16.20], [8.20, 10.90, 16.90], "steel_dark"),              # hammer spur
        _element([7.70, 5.55, 12.60], [8.30, 5.85, 15.25], "polymer"),                  # guard
        _element([7.70, 5.55, 12.40], [8.30, 7.00, 12.80], "polymer"),
        _element([7.15, 2.40, 13.60], [8.85, 7.20, 16.30], "grip", rot=grip_rot),      # grip
        _element([7.80, 5.90, 13.40], [8.20, 6.90, 13.90], "steel_dark"),               # trigger
        _element([7.55, 6.40, 10.80], [8.45, 7.70, 12.90], "polymer"),                  # hinge knuckle
    ]
    e[0]["faces"]["north"] = {"uv": _uv("bore"), "texture": "#0"}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": DISPLAY}


# ------------------------------------------------------------------------------------------- ammo
OLIVE = {"o": (0.42, 0.46, 0.25), "O": (0.52, 0.56, 0.32), "q": (0.30, 0.33, 0.17), "s": (0.80, 0.81, 0.83),
         "S": (0.92, 0.93, 0.94), "t": (0.58, 0.59, 0.62), "y": (0.92, 0.78, 0.30), **BRASS}


def grenade_icon():
    """Two 40 mm grenades standing side by side: the silver case, the gilded band, the olive ogive, the gilded fuze."""
    img = np.zeros((16, 16, 4))
    rnd = ["..hh..", ".hbbd.", ".Oooq.", "OOoooq", "Ooooqq", "Ooooqq", "hbbbbd", "Ssssst", "Ssssst", "Ssssst",
           "Ssssst", "tttttt"]
    for x0, y0 in ((1, 2), (8, 3)):
        _put(img, x0, y0, rnd, OLIVE)
    return _outline(img)


ROUND_PATCH = {"case": (0, (0.80, 0.81, 0.83)), "rim": (1, (0.62, 0.63, 0.66)), "body": (2, (0.42, 0.46, 0.25)),
               "gold": (3, (0.90, 0.72, 0.30))}


def round_palette():
    pal = np.zeros((16, 16, 4))
    rng = np.random.default_rng(57)
    for k, rgb in ROUND_PATCH.values():
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        patch = np.tile(np.asarray(rgb, float), (4, 4, 1)) * (0.94 + 0.10 * rng.random((4, 4, 1)))
        pal[y0:y0 + 4, x0:x0 + 4, :3] = np.clip(patch, 0, 1)
        pal[y0:y0 + 4, x0:x0 + 4, 3] = 1.0
    return pal


def round_model(texture):
    """The 40 mm round in 3D (nose north, 64 units per metre): case, band, ogive in two steps, fuze."""
    def el(frm, to, part):
        k = ROUND_PATCH[part][0]
        x0, y0 = (k % 4) * 4, (k // 4) * 4
        uv = [x0 + 0.5, y0 + 0.5, x0 + 3.5, y0 + 3.5]
        return {"from": frm, "to": to, "faces": {f: {"uv": uv, "texture": "#0"}
                                                 for f in ("north", "south", "east", "west", "up", "down")}}
    e = [
        el([6.72, 6.72, 8.0], [9.28, 9.28, 10.70], "case"),
        el([6.63, 6.63, 10.55], [9.37, 9.37, 10.80], "rim"),
        el([6.70, 6.70, 7.65], [9.30, 9.30, 8.00], "gold"),
        el([6.78, 6.78, 6.30], [9.22, 9.22, 7.65], "body"),
        el([7.10, 7.10, 5.30], [8.90, 8.90, 6.30], "body"),
        el([7.55, 7.55, 4.40], [8.45, 8.45, 5.30], "gold"),
    ]
    display = {ctx: {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]}
               for ctx in ("fixed", "ground", "head")}
    display["ground"]["translation"] = [0, 2, 0]
    for hand in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand"):
        display[hand] = {"rotation": [-60, 0, 0], "translation": [0, 2, 1], "scale": [0.8, 0.8, 0.8]}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": display}


def ammo_definition():
    """The flat icon in the GUI, the 3D round everywhere else (in hand, on the ground, in flight)."""
    icon_model = {"type": "minecraft:model", "model": f"blockland:item/{AMMO}"}
    round_ = {"type": "minecraft:model", "model": f"blockland:item/{AMMO}_round"}
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui"], "model": icon_model}], "fallback": round_}}


# ------------------------------------------------------------------------------------------- build
def build(path):
    g = GLTF(os.path.join(GUN_DIR, f"{GUN}.glb"))
    base = load_materials(g)
    skins = _skins()
    for skin in ["default"] + list(skins):
        mats = skin_looks.skin_materials(base, skins[skin]) if skin != "default" else base
        _write_skin(path, GUN, skin, icon(side_items(g, mats)), palette({m["name"]: m for m in mats}), held,
                    None if skin == "default" else f"blockland:item/{GUN}_held")
    write_json(path("items", f"{GUN}.json"), item_definition(GUN, list(skins)))
    tx.save_png(grenade_icon(), path("textures", "item", f"{AMMO}.png"))
    write_json(path("models", "item", f"{AMMO}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{AMMO}"}})
    tx.save_png(round_palette(), path("textures", "item", f"{AMMO}_round.png"))
    write_json(path("models", "item", f"{AMMO}_round.json"), round_model(f"blockland:item/{AMMO}_round"))
    write_json(path("items", f"{AMMO}.json"), ammo_definition())
    print(f"items: {GUN} icons/models ({len(skins) + 1} skins), {AMMO} (icon + 3D round)")
