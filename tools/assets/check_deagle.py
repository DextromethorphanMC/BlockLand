"""Section 48 checks of models/gun/deagle.glb, used by check_glb.py (CHECK_MODULES): the Desert Eagle follows the P9
contract (nodes, materials, clips and events, mounts and every optic's ADS, framing; check_glb.check_gun), plus the
pistol budget, the slide stroke matching guns.json's slide lock, the front sight on the fixed barrel lined up with the
rear one (the iron sight line parallel to the bore), and the data's single optic-only slot."""
import json
import os

import numpy as np

from glbread import GLTF
from model_quality import BUDGETS

BUDGET = BUDGETS["pistol"]          # section 24 budgets: triangles of a pistol as drawn (one magazine fitted)
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")


def claims(path, names):
    return os.path.basename(path) == "deagle.glb"


def check(path, arms, r):
    import check_glb
    import deagle_model as hm
    check_glb.check_gun(path, arms, r)
    g = GLTF(path)
    label = "deagle"
    tris = {n["name"]: sum(len(p["indices"]) for p in g.mesh_primitives(n["mesh"])) for n in g.nodes if "mesh" in n}
    drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
    r.check(drawn <= BUDGET, f"{label}: {drawn} triangles drawn (budget {BUDGET})")
    with open(GUNS_JSON, encoding="utf-8") as f:
        gun = json.load(f)["guns"].get("deagle")
    if r.check(gun is not None, f"{label}: no guns.json entry"):
        r.check(set(gun["slots"]) == {"optic"}, f"{label}: slots {sorted(gun['slots'])}, expected only the optic")
        r.check(len(gun["magazines"]) == 1, f"{label}: magazines {gun['magazines']} (one, not selectable)")
        lock = gun.get("slideLock", {}).get("offset", [0, 0, 0])[2]
        fire = next(a for a in g.json["animations"] if a["name"] == "fire_last")
        slide = g.names["slide"]
        end = g.sample(fire, g.duration(fire)).get(slide, {}).get("translation")
        rest = np.asarray(g.nodes[slide].get("translation", [0, 0, 0]))
        stroke = float(end[2] - rest[2]) if end is not None else 0.0
        r.check(abs(stroke - lock) < 0.0015, f"{label}: fire_last locks the slide back {stroke:.4f}, guns.json {lock}")
        r.note(f"{label}: slide locked back {stroke * 1000:.1f} mm (guns.json slideLock {lock * 1000:.1f} mm)")
    # iron sights: the front blade's top (on the fixed barrel, meshes in the design frame) at the rear notch's height
    bpts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names["barrel"]]["mesh"])])
    top = float(bpts[:, 1].max())
    r.check(abs(top - hm.SIGHT_Y) < 0.0003, f"{label}: front blade top {top:.4f}, the sight line is {hm.SIGHT_Y:.4f}")
    W = g.world_matrices({})
    crown = W[g.names["muzzle"]][:3, 3]
    r.note(f"{label}: {drawn} triangles drawn, muzzle at {np.round(crown, 3)}")
