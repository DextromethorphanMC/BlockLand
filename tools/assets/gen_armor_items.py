"""Body armour item assets (VIEWMODEL_SPEC sections 11 and 50): 32 x 32 icons rendered from the GLBs (the carrier from
the front, a little from the right and above, with all three plates in; the plate from its strike face), their item
models and item definitions. The carrier is drawn on the body by the runtime; in a hand or on the ground both show
their icons."""
import os

import numpy as np

import textures as tx
from gen_items import render_items_icon, write_json
from glbread import GLTF
from preview import load_materials

HERE = os.path.dirname(os.path.abspath(__file__))
ARMOR_DIR = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "armor")


def _rot(yaw, pitch):
    a, b = np.radians(yaw), np.radians(pitch)
    ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
    rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
    return rx @ ry


def icon_items(g, mats, view, root=None):
    """The model's meshes at rest, in its own frame (the `root` node reset), turned by `view`, centred."""
    reset = {}
    if root is not None:
        reset[g.names[root]] = {"translation": np.zeros(3), "rotation": np.array([0, 0, 0, 1.0])}
    W = g.world_matrices(reset)
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": mats[p["material"]]})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 2.0])
    return items, allp.max(0) - allp.min(0)


def item_definition(name):
    return {"model": {"type": "minecraft:model", "model": f"blockland:item/{name}"}}


def build(path):
    # the carrier: seen from the front (its -Z), turned a little, from a little above
    g = GLTF(os.path.join(ARMOR_DIR, "vest.glb"))
    items, ext = icon_items(g, load_materials(g), _rot(200, 12))
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", "vest.png"))
    # the plate: its strike face, tilted
    g = GLTF(os.path.join(ARMOR_DIR, "plate.glb"))
    items, ext = icon_items(g, load_materials(g), _rot(160, -8) @ np.array([[np.cos(0.3), -np.sin(0.3), 0],
                                                                            [np.sin(0.3), np.cos(0.3), 0], [0, 0, 1.0]]),
                            root="plate")
    tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", "armor_plate.png"))
    for name in ("vest", "armor_plate"):
        write_json(path("models", "item", f"{name}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"blockland:item/{name}"}})
        write_json(path("items", f"{name}.json"), item_definition(name))
    print("armour items: vest, armor_plate icons, item models, item definitions")
