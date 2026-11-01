"""Vehicle spawn item icons (VIEWMODEL_SPEC section 57): `textures/item/<id>.png` rendered from models/vehicle/<id>.glb
the way the other BlockLand icons are (gen_items.render_items_icon: orthographic, flat-shaded, 32 px, majority-vote
downsampled, a 1 px outline), the vehicle seen from the front left and a little above. The item definitions and item
models (`items/<id>.json`, `models/item/<id>.json`, layer0 `blockland:item/<id>`) are the vehicle module's and stay
as they are."""
import os

import numpy as np

import textures as tx
from gen_items import render_items_icon
from glbread import GLTF
from preview import load_materials

VEHICLES = ("motorcycle", "offroad", "speedboat", "tank", "fighter_jet", "helicopter")
HERE = os.path.dirname(os.path.abspath(__file__))
VEHICLE_DIR = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "vehicle")
#: (yaw, pitch) of the view per vehicle: nearly side-on (front to the left) like the weapon icons, a little from above
VIEWS = {"motorcycle": (-78.0, 10.0), "offroad": (-66.0, 16.0), "speedboat": (-68.0, 20.0), "tank": (-66.0, 12.0),
         "fighter_jet": (-62.0, 24.0), "helicopter": (-64.0, 16.0)}
#: nodes posed for the icon (degrees about the node's local X): the tank's gun raised, so the long barrel does not
#: stretch the icon flat
POSES = {"tank": {"cannon": 12.0}}


def icon_items(g, mats, yaw_deg, pitch_deg, pose=None):
    """The vehicle (at rest, or with `pose`: node -> degrees about its local X), its front (-Z) towards the viewer's
    left, centred in front of an orthographic camera."""
    overrides = {}
    for name, deg in (pose or {}).items():
        t, r, s = g.local_trs(g.names[name])
        a = np.radians(deg) / 2
        q = np.array([np.sin(a), 0.0, 0.0, np.cos(a)])
        x1, y1, z1, w1 = r
        x2, y2, z2, w2 = q
        overrides[g.names[name]] = {"rotation": np.array([w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
                                                         w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
                                                         w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
                                                         w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2])}
    W = g.world_matrices(overrides)
    yaw, pitch = np.radians(yaw_deg), np.radians(pitch_deg)
    ry = np.array([[np.cos(yaw), 0, np.sin(yaw)], [0, 1, 0], [-np.sin(yaw), 0, np.cos(yaw)]])
    rx = np.array([[1, 0, 0], [0, np.cos(pitch), -np.sin(pitch)], [0, np.sin(pitch), np.cos(pitch)]])
    turn = np.array([[-1.0, 0, 0], [0, 1, 0], [0, 0, -1.0]])          # look at the front (-Z) from the front
    view = rx @ ry @ turn
    items = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        M = W[i]
        for p in g.mesh_primitives(n["mesh"]):
            m = mats[p["material"]]
            if m.get("name") == "glass":                              # see-through: the icon shows what is behind
                continue
            pos = (p["positions"] @ M[:3, :3].T + M[:3, 3]) @ view.T
            nrm = p["normals"] @ M[:3, :3].T @ view.T
            items.append({"pos": pos, "nrm": nrm, "uv": p["uvs"], "idx": p["indices"], "mat": m})
    allp = np.concatenate([it["pos"] for it in items])
    c = 0.5 * (allp.min(0) + allp.max(0))
    for it in items:
        it["pos"] = it["pos"] - np.array([c[0], c[1], c[2] + 10.0])
    return items, allp.max(0) - allp.min(0)


def build(path):
    for vid in VEHICLES:
        g = GLTF(os.path.join(VEHICLE_DIR, f"{vid}.glb"))
        items, ext = icon_items(g, load_materials(g), *VIEWS[vid], pose=POSES.get(vid))
        tx.save_png(render_items_icon(items, ext, 32, 8, 1), path("textures", "item", f"{vid}.png"))
    print(f"vehicle icons: {', '.join(VEHICLES)}")
