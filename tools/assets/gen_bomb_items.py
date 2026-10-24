"""The bomb's item assets (VIEWMODEL_SPEC sections 11 and 39): a 32 px icon rendered from the GLB (the `bomb` subtree in
its design frame), the icon item model, the held model (cuboids + palette) and the item definition: the icon in the
GUI, the held model everywhere else (in the hand in third person, on the ground, and lying on the floor once planted:
the planted bomb draws the stack in the FIXED context). The held model lies flat in its own frame — keypad up (+Y),
the long side along X — with its bottom face at y = 8 (the item's centre), so drawn FIXED at a point it rests on it."""
import os

import numpy as np

import raster as rs
import textures as tx
from gen_equipment_items import _box, _rot, palette
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import load_materials

HERE = os.path.dirname(os.path.abspath(__file__))
GLB_PATH = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "bomb",
                        "c4.glb")
KEY = "c4"
VIEW = (-35.0, 40.0, -10.0)               # icon view (yaw, pitch, roll) of the design frame: the keypad towards the eye


def icon_items(g, mats):
    root = g.names["bomb"]
    W = g.world_matrices({root: {"translation": np.zeros(3), "rotation": np.array([0.0, 0.0, 0.0, 1.0])}})
    view = _rot(*VIEW)
    items = []
    for i, n in enumerate(g.nodes):
        j = i
        while j is not None and j != root:
            j = g.parent.get(j)
        if j != root or "mesh" not in n:
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


def held_elements(names):
    """The bundle in item units (16 per block): 7 long (X), 4.65 wide (Z), the sticks 2.2 high, the unit on top."""
    k = {n: i for i, n in enumerate(names)}
    e = []
    for z in (6.45, 8.0, 9.55):                                                     # the three sticks
        e.append(_box((8.0, 9.1, z), (3.5, 1.1, 0.75), k["explosive"]))
    for x in (6.0, 10.0):                                                           # two bands of tape
        e.append(_box((x, 9.12, 8.0), (0.55, 1.17, 2.40), k["tape"]))
    e.append(_box((8.0, 10.7, 8.0), (2.6, 0.42, 1.6), k["housing"]))                # the timer unit
    e.append(_box((6.55, 11.16, 8.0), (0.65, 0.05, 1.1), k["screen"]))              # its LCD (far end, -X)
    e.append(_box((8.85, 11.19, 8.0), (1.35, 0.08, 1.1), k["keys"]))                # the keypad
    e.append(_box((7.55, 11.24, 9.25), (0.16, 0.12, 0.16), k["led"]))               # the status LED
    e.append(_box((9.0, 9.95, 10.42), (0.10, 0.75, 0.10), k["wire_red"]))            # wires down the side
    e.append(_box((8.3, 9.95, 10.42), (0.10, 0.75, 0.10), k["wire_yellow"]))
    e.append(_box((7.6, 9.95, 5.58), (0.10, 0.75, 0.10), k["wire_blue"]))
    return e


DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, -10], "translation": [0, 0.5, 1.5], "scale": [0.7, 0.7, 0.7]},
    "thirdperson_lefthand": {"rotation": [0, 90, 10], "translation": [0, 0.5, 1.5], "scale": [0.7, 0.7, 0.7]},
    "firstperson_righthand": {"rotation": [0, 70, 0], "translation": [1, 1, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [0, 70, 0], "translation": [1, 1, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, -1, 0], "scale": [0.6, 0.6, 0.6]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [1.3, 1.3, 1.3]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 6, 0], "scale": [1, 1, 1]},
}


def build(path):
    g = GLTF(GLB_PATH)
    mats = load_materials(g)
    items, ext = icon_items(g, mats)
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{KEY}.png"))
    write_json(path("models", "item", f"{KEY}.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{KEY}"}})
    names = [m["name"] for m in g.json["materials"]]
    tx.save_png(palette(mats, names), path("textures", "item", f"{KEY}_held.png"))
    tex = f"blockland:item/{KEY}_held"
    write_json(path("models", "item", f"{KEY}_held.json"),
               {"textures": {"0": tex, "particle": tex}, "elements": held_elements(names), "display": DISPLAY})
    write_json(path("items", f"{KEY}.json"), {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": ["gui"], "model": {"type": "minecraft:model", "model": f"blockland:item/{KEY}"}}],
        "fallback": {"type": "minecraft:model", "model": f"blockland:item/{KEY}_held"}}})
    print(f"bomb items: {KEY} icon, item models, held palette, item definition")
