"""Revolver item assets (VIEWMODEL_SPEC sections 1, 8 and 49): skin icons rendered from revolver.glb, icon / held item
models, held-model palettes and items/revolver.json (the USP's tools: gen_usp_items). It fires pistol ammunition (no
ammunition item of its own)."""
import os

import skin_looks
from gen_items import _element, _uv, palette, write_json
from gen_usp_items import DISPLAY, _write_skin, icon, item_definition, side_items
from glbread import GLTF
from preview import GUN_DIR, load_materials

GUN = "revolver"
# the shared item palette's patches (gen_items.palette) from the revolver's materials
PATCH_SOURCE = {"slide": "steel", "polymer": "steel", "grip": "grip", "barrel": "barrel", "steel_dark": "steel_dark",
                "sight_dots": "insert", "mag_base": "loader"}


def _skins():
    return {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}


def held(texture):
    """Low-poly revolver (muzzle north, ~67 units per metre like the USP's): the barrel with its underlug and rib, the
    front blade, the frame, the round cylinder standing out both sides, the guard, the raked grip, the hammer spur."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 7.6, 13.3]}
    e = [
        _element([7.43, 8.33, 2.1], [8.57, 10.6, 8.97], "barrel"),                      # barrel and underlug
        _element([7.70, 10.6, 2.2], [8.30, 11.1, 8.97], "barrel"),                      # ventilated rib
        _element([7.90, 11.1, 2.3], [8.10, 11.45, 3.3], "sight_dots"),                  # front blade
        _element([7.43, 7.60, 8.97], [8.57, 11.1, 15.0], "polymer", top="slide_top"),   # frame and top strap
        _element([6.63, 7.73, 9.35], [9.37, 10.47, 12.1], "slide"),                     # the cylinder
        _element([7.70, 5.10, 9.60], [8.30, 7.60, 12.4], "polymer"),                    # trigger guard
        _element([7.80, 6.20, 10.5], [8.20, 7.60, 11.0], "steel_dark"),                 # trigger
        _element([7.00, 1.00, 12.0], [9.00, 7.60, 14.6], "grip", rot=grip_rot),         # walnut grip
        _element([7.80, 10.5, 14.6], [8.20, 11.6, 15.6], "steel_dark"),                 # hammer spur
        _element([7.60, 11.1, 13.3], [8.40, 11.45, 14.2], "steel_dark"),                # rear sight
    ]
    e[0]["faces"]["north"] = {"uv": _uv("bore"), "texture": "#0"}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": DISPLAY}


def _palette(mats):
    by_name = {m["name"]: m for m in mats}
    return palette({k: by_name[v] for k, v in PATCH_SOURCE.items()})


# ------------------------------------------------------------------------------------------- build
def build(path):
    g = GLTF(os.path.join(GUN_DIR, f"{GUN}.glb"))
    base = load_materials(g)
    skins = _skins()
    for skin in ["default"] + list(skins):
        mats = skin_looks.skin_materials(base, skins[skin]) if skin != "default" else base
        _write_skin(path, GUN, skin, icon(side_items(g, mats, hide_names=("fx_shell", "mag_standard", "loader_rounds"))),
                    _palette(mats), held, None if skin == "default" else f"blockland:item/{GUN}_held")
    write_json(path("items", f"{GUN}.json"), item_definition(GUN, list(skins)))
    print(f"items: {GUN} icons/models ({len(skins) + 1} skins)")
