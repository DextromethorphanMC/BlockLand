"""USP and dual-USP item assets (VIEWMODEL_SPEC sections 1, 8 and 26): skin icons rendered from usp.glb /
usp_dual.glb (the Cerberus skin's from usp_dual_cerberus.glb), icon / held item models, held-model palettes and
items/usp.json, items/usp_dual.json. The dual item's icon shows the pair; its held model is one pistol (the runtime
draws it in both hands)."""
import os

import numpy as np

import skin_looks
import textures as tx
from gen_items import _element, palette, render_items_icon, write_json
from glbread import GLTF
from preview import GUN_DIR, load_materials
import third_person


def _skins(gun):
    return {k: v for k, v in skin_looks.weapon_skins(gun, "gun").items() if k != "default"}


def side_items(g, mats, root="gun", hide_names=("mag_extended", "fx_shell"), offset=(0.0, 0.0, 0.0)):
    """Meshes under `root` in its own space (rest pose, root reset), turned to a right-side view (muzzle right)."""
    ri = g.names[root]
    W = g.world_matrices({ri: {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}})
    view = np.array([[0, 0, -1.0], [0, 1.0, 0], [1.0, 0, 0]])
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or any(n["name"].startswith(h) for h in hide_names):
            continue
        j = i
        while j in g.parent and j != ri:
            j = g.parent[j]
        if j != ri:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T + np.asarray(offset, float)
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mats[p["material"]]})
    return items


def icon(items, size=32, margin=2):
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 1.0])
    return render_items_icon(items, allp.max(0) - allp.min(0), size, 8, margin)


def pair_icon(g, mats):
    """Two pistols: the second one behind the first, raised and turned a little (a crossed pair)."""
    front = side_items(g, mats)
    back = side_items(g, mats, offset=(-0.035, 0.040, -0.06))
    a = np.radians(-14.0)
    rot = np.array([[np.cos(a), -np.sin(a), 0.0], [np.sin(a), np.cos(a), 0.0], [0.0, 0.0, 1.0]])
    for it in back:
        it["pos"] = it["pos"] @ rot.T
        it["nrm"] = it["nrm"] @ rot.T
    return icon(front + back, margin=1)


# ------------------------------------------------------------------------------------ held models
DISPLAY = {
    "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, -1], "scale": [0.75, 0.75, 0.75]},
    "firstperson_lefthand": {"rotation": [0, 4, 0], "translation": [0, 2.5, -1], "scale": [0.75, 0.75, 0.75]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
    "gui": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1.0, 1.0, 1.0]},
}


def usp_held(texture):
    """Low-poly USP (muzzle north): long squared slide, deep dust cover with its rail, big guard, ring hammer."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 8.2, 11.2]}
    e = [
        _element([7.15, 9.0, 1.4], [8.85, 10.9, 13.4], "slide", top="slide_top"),
        _element([7.28, 8.1, 1.7], [8.72, 9.0, 13.8], "polymer"),
        _element([7.35, 7.6, 2.0], [8.65, 8.1, 5.8], "polymer"),                       # rail
        _element([7.6, 6.3, 5.9], [8.4, 6.75, 10.2], "polymer"),                       # guard
        _element([7.6, 6.3, 5.7], [8.4, 8.1, 6.25], "polymer"),
        _element([7.05, 3.2, 9.4], [8.95, 8.4, 13.0], "grip", rot=grip_rot),
        _element([6.98, 2.6, 9.3], [9.02, 3.2, 13.2], "mag_base", rot=grip_rot),
        _element([7.8, 7.1, 7.9], [8.2, 8.1, 8.6], "steel_dark"),                      # trigger
        _element([7.6, 9.55, 1.1], [8.4, 10.35, 1.4], "barrel"),
        _element([7.9, 10.9, 1.7], [8.1, 11.3, 2.2], "steel_dark"),                    # front sight
        _element([7.3, 10.9, 12.6], [8.7, 11.3, 13.3], "steel_dark"),                  # rear sight
        _element([7.75, 9.4, 13.4], [8.25, 10.8, 14.3], "steel_dark"),                 # hammer
    ]
    e[8]["faces"]["north"] = {"uv": _uv_patch("bore"), "texture": "#0"}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": DISPLAY}


def cerberus_held(texture):
    """Low-poly Cerberus (muzzle north): the long slab with the red inlay, the raked grip far back."""
    grip_rot = {"angle": -22.5, "axis": "x", "origin": [8, 8.0, 13.0]}
    e = [
        _element([7.3, 9.6, -2.5], [8.7, 11.2, 13.2], "slide", top="slide_top"),       # slide block
        _element([7.25, 9.0, -2.4], [8.75, 9.6, 13.0], "sight_dots"),                  # red inlay strip
        _element([7.3, 7.9, -2.4], [8.7, 9.0, 12.4], "polymer"),                       # lower bar
        _element([7.4, 6.4, 8.6], [8.6, 7.9, 12.6], "polymer"),                        # frame and guard
        _element([7.15, 2.9, 11.0], [8.85, 7.9, 14.4], "grip", rot=grip_rot),
        _element([7.3, 2.4, 11.4], [8.7, 2.9, 14.0], "mag_base", rot=grip_rot),
        _element([7.8, 6.9, 9.6], [8.2, 7.9, 10.2], "steel_dark"),                     # trigger
        _element([7.6, 8.1, -2.7], [8.4, 8.9, -2.4], "barrel"),
        _element([7.7, 11.2, 12.2], [8.3, 11.8, 13.4], "steel_dark"),                  # hammer spur
    ]
    e[7]["faces"]["north"] = {"uv": _uv_patch("bore"), "texture": "#0"}
    return {"textures": {"0": texture, "particle": texture}, "elements": e, "display": DISPLAY}


def _uv_patch(name):
    from gen_items import _uv
    return _uv(name)


def cerberus_palette(mats_by_name):
    """The P9 palette layout filled with the Cerberus materials (slide = body, sight_dots = red trim)."""
    alias = {"slide": "cerberus_body", "polymer": "cerberus_body", "grip": "cerberus_grip", "barrel": "barrel",
             "steel_dark": "steel_dark", "sight_dots": "cerberus_trim", "mag_base": "mag_base"}
    return palette({k: mats_by_name[v] for k, v in alias.items()})


def skin_select(x, skins):
    return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": s, "model": {"type": "minecraft:model", "model": f"blockland:item/{x}_{s}"}}
                      for s in skins],
            "fallback": {"type": "minecraft:model", "model": f"blockland:item/{x}"}}


def item_definition(gun, skins):
    return third_person.weapon_definition(skin_select(gun, skins), skin_select(f"{gun}_held", skins))


# ------------------------------------------------------------------------------------------- build
def _write_skin(path, gun, skin, icon_img, pal, held_fn, held_parent=None):
    suffix = "" if skin == "default" else f"_{skin}"
    tx.save_png(icon_img, path("textures", "item", f"{gun}{suffix}.png"))
    write_json(path("models", "item", f"{gun}{suffix}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{gun}{suffix}"}})
    tx.save_png(pal, path("textures", "item", f"{gun}_held{suffix}.png"))
    tex = f"blockland:item/{gun}_held{suffix}"
    if held_parent is None:
        write_json(path("models", "item", f"{gun}_held{suffix}.json"), held_fn(tex))
    else:
        write_json(path("models", "item", f"{gun}_held{suffix}.json"),
                   {"parent": held_parent, "textures": {"0": tex, "particle": tex}})


def build(path):
    usp = GLTF(os.path.join(GUN_DIR, "usp.glb"))
    base = load_materials(usp)
    skins = _skins("usp")
    for skin in ["default"] + list(skins):
        mats = skin_looks.skin_materials(base, skins[skin]) if skin != "default" else base
        _write_skin(path, "usp", skin, icon(side_items(usp, mats)), palette({m["name"]: m for m in mats}), usp_held,
                    None if skin == "default" else "blockland:item/usp_held")
    write_json(path("items", "usp.json"), item_definition("usp", list(skins)))

    dual = GLTF(os.path.join(GUN_DIR, "usp_dual.glb"))
    base = load_materials(dual)
    skins = _skins("usp_dual")
    for skin in ["default"] + list(skins):
        data = skins.get(skin, {})
        if data.get("model"):                      # a special skin with a model of its own (section 30)
            g = GLTF(os.path.join(GUN_DIR, "..", "..", *data["model"].split("/")))
            mats = load_materials(g)
            by_name = {m["name"]: m for m in mats}
            pal = cerberus_palette(by_name) if "cerberus_body" in by_name else palette(by_name)
            _write_skin(path, "usp_dual", skin, pair_icon(g, mats), pal, cerberus_held)
            continue
        mats = skin_looks.skin_materials(base, data) if skin != "default" else base
        _write_skin(path, "usp_dual", skin, pair_icon(dual, mats), palette({m["name"]: m for m in mats}), usp_held,
                    None if skin == "default" else "blockland:item/usp_dual_held")
    write_json(path("items", "usp_dual.json"), item_definition("usp_dual", list(skins)))
    print(f"items: usp, usp_dual icons/models ({len(_skins('usp')) + 1} + {len(skins) + 1} skins)")
