"""Desert Eagle item assets (VIEWMODEL_SPEC sections 1, 8 and 48): skin icons rendered from deagle.glb, icon /
held item models, held-model palettes and items/deagle.json (the USP's tools: gen_usp_items). It fires pistol
ammunition (no ammunition item of its own)."""
import os

import skin_looks
from gen_items import _element, _uv, palette, write_json
from gen_usp_items import DISPLAY, _write_skin, icon, item_definition, side_items
from glbread import GLTF
from preview import GUN_DIR, load_materials

GUN = "deagle"


def _skins():
    return {k: v for k, v in skin_looks.weapon_skins(GUN, "gun").items() if k != "default"}


def held(texture):
    """Low-poly Desert Eagle (muzzle north, ~67 units per metre like the USP's): the short slide, the long polygonal
    barrel on its dust cover with the rail and the front blade, the squared guard, the deep raked grip, the spur."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 7.8, 12.4]}
    e = [
        _element([6.94, 8.65, 6.84], [9.06, 11.0, 15.0], "slide", top="slide_top"),
        _element([7.09, 9.06, -3.2], [8.91, 11.0, 6.84], "barrel"),                    # polygonal barrel
        _element([7.6, 11.0, -2.1], [8.4, 11.25, 6.5], "barrel"),                      # its rail
        _element([7.08, 7.71, -1.9], [8.92, 9.06, 6.84], "polymer"),                   # dust cover
        _element([7.08, 7.36, 6.84], [8.92, 8.65, 15.2], "polymer"),                   # frame under the slide
        _element([7.54, 5.65, 5.97], [8.46, 6.05, 11.36], "polymer"),                  # guard
        _element([7.54, 5.65, 5.97], [8.46, 7.71, 6.6], "polymer"),
        _element([6.77, 1.6, 10.5], [9.23, 7.8, 14.3], "grip", rot=grip_rot),
        _element([6.9, 1.1, 10.7], [9.1, 1.6, 14.1], "mag_base", rot=grip_rot),
        _element([7.8, 6.3, 8.0], [8.2, 7.71, 8.6], "steel_dark"),                     # trigger
        _element([7.9, 11.0, -2.9], [8.1, 11.56, -2.2], "steel_dark"),                 # front blade
        _element([7.34, 11.0, 14.1], [8.66, 11.56, 14.7], "steel_dark"),               # rear sight
        _element([7.7, 8.7, 15.0], [8.3, 10.6, 15.9], "steel_dark"),                   # hammer spur
    ]
    e[1]["faces"]["north"] = {"uv": _uv("bore"), "texture": "#0"}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": DISPLAY}


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
    print(f"items: {GUN} icons/models ({len(skins) + 1} skins)")
