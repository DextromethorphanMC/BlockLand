"""Section 26 checks of models/gun/usp.glb, used by check_glb.py: the USP follows the P9 contract exactly (nodes,
materials, clips and events, mounts and every optic's ADS, framing), plus the pistol budget."""
import os

import numpy as np

from glbread import GLTF

from model_quality import BUDGETS

BUDGET = BUDGETS["pistol"]          # section 24 budgets: triangles of a pistol as drawn (one magazine fitted)


def claims(path, names):
    return os.path.basename(path) == "usp.glb"


def check(path, arms, r):
    import check_glb
    check_glb.check_gun(path, arms, r)
    g = GLTF(path)
    tris = {n["name"]: sum(len(p["indices"]) for p in g.mesh_primitives(n["mesh"])) for n in g.nodes if "mesh" in n}
    drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
    r.check(drawn <= BUDGET, f"usp: {drawn} triangles with the standard magazine (budget {BUDGET})")
    r.check(max(tris["mag_standard"], tris["mag_extended"]) + drawn - tris["mag_standard"] <= BUDGET + 300,
            "usp: too heavy with the extended magazine")
    W = g.world_matrices({})
    crown = W[g.names["muzzle"]][:3, 3]
    r.note(f"usp: {drawn} triangles (standard magazine), muzzle at {np.round(crown, 3)}")
