"""Ambient prop GLB checks (RENDERING_SPEC section 29), used by check_glb.py (CHECK_MODULES; claims models/ambient/*.glb):
the container and meshes as for every model, one node `tumbleweed` with its mesh, a ball of about the models' radius
(0.5 m) round the origin, and the triangle budget per variant."""
import os

import numpy as np

from check_glb import check_container, check_meshes
from glbread import GLTF

RADIUS = 0.5
BUDGET = 24000


def claims(path, names):
    return os.path.basename(os.path.dirname(path)) == "ambient"


def check(path, arms, r):
    name = os.path.splitext(os.path.basename(path))[0]
    g = GLTF(path)
    check_container(g, r, name)
    check_meshes(g, r, name)
    if not r.check("tumbleweed" in g.names, f"{name}: node tumbleweed missing"):
        return
    node = g.nodes[g.names["tumbleweed"]]
    if not r.check("mesh" in node, f"{name}: node tumbleweed has no mesh"):
        return
    prims = g.mesh_primitives(node["mesh"])
    pts = np.concatenate([p["positions"] for p in prims])
    reach = float(np.max(np.linalg.norm(pts, axis=1)))
    centre = (pts.max(0) + pts.min(0)) / 2
    tris = sum(len(p["indices"]) // 3 if np.ndim(p["indices"]) == 1 else len(p["indices"]) for p in prims)
    r.check(RADIUS * 0.85 <= reach <= RADIUS * 1.2, f"{name}: reaches {reach:.3f} m from the origin (radius {RADIUS})")
    r.check(float(np.linalg.norm(centre)) < 0.06, f"{name}: centre {np.round(centre, 3).tolist()} not at the origin")
    r.check(tris <= BUDGET, f"{name}: {tris} triangles (budget {BUDGET})")
    r.note(f"{name}: {tris} triangles, reach {reach:.2f} m")
