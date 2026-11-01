"""Creature GLB checks (tools/creatures/SHARK_SPEC.md section 4), used by check_glb.py (CHECK_MODULES; claims
models/creature/*.glb):

* the contract's nodes with their parents (the shark: `body` the root, `jaw` under it, hinged behind the mouth);
* the space: front towards -Z, centred on X, the body's axis at the origin (the nose at about z -1.75, the length about
  3.5 m), nothing that the runtime bends (`body`) far off its axis sideways;
* materials: a region on every one, a texture set on the skins, opaque, baked AO on every primitive;
* the triangle budget (model_quality.json `creature_<id>`)."""
import os

import numpy as np

from check_glb import check_container, check_meshes
from glbread import GLTF
from model_quality import BUDGETS

PARENTS = {"shark": {"body": None, "jaw": "body"}}
LENGTH = {"shark": (3.3, 3.7)}
TEXTURED = {"skin_back", "skin_belly"}


def claims(path, names):
    return os.path.basename(os.path.dirname(path)) == "creature"


def _points(g, W, i):
    out = []
    for p in g.mesh_primitives(g.nodes[i]["mesh"]):
        out.append(p["positions"] @ W[i][:3, :3].T + W[i][:3, 3])
    return np.concatenate(out) if out else np.zeros((0, 3))


def check(path, arms, r):
    cid = os.path.splitext(os.path.basename(path))[0]
    g = GLTF(path)
    check_container(g, r, cid)
    check_meshes(g, r, cid)
    if not r.check(cid in PARENTS, f"{cid}: not a creature of the contract ({', '.join(PARENTS)})"):
        return
    names = g.names
    parent = {}
    for i, n in enumerate(g.nodes):
        for c in n.get("children", []):
            parent[c] = i
    for name, want in PARENTS[cid].items():
        if not r.check(name in names, f"{cid}: node {name} missing"):
            continue
        got = parent.get(names[name])
        got = g.nodes[got]["name"] if got is not None else None
        r.check(got == want, f"{cid}: {name} is under {got}, the contract wants {want}")
    W = g.world_matrices({})
    body = names.get("body")
    if body is not None:
        r.check(np.allclose(W[body], np.eye(4), atol=1e-6), f"{cid}: body must sit at the origin unturned (the runtime bends its vertices)")
    pts = np.concatenate([_points(g, W, i) for i, n in enumerate(g.nodes) if "mesh" in n])
    lo, hi = pts.min(0), pts.max(0)
    r.note(f"{cid}: bounds x {lo[0]:.2f}..{hi[0]:.2f}  y {lo[1]:.2f}..{hi[1]:.2f}  z {lo[2]:.2f}..{hi[2]:.2f}")
    r.check(abs(lo[0] + hi[0]) < 0.03, f"{cid}: not centred on x ({lo[0]:.2f}..{hi[0]:.2f})")
    length = hi[2] - lo[2]
    a, b = LENGTH[cid]
    r.check(a <= length <= b, f"{cid}: {length:.2f} m long, the contract wants {a}..{b}")
    r.check(abs(lo[2] + 1.75) < 0.05, f"{cid}: the nose at z {lo[2]:.2f}, the contract has it at -1.75 (front -Z)")
    if cid == "shark" and "jaw" in names:
        j = W[names["jaw"]][:3, 3]
        r.check(-1.1 < j[2] < -0.95 and j[1] < 0, f"shark: the jaw's hinge at {np.round(j, 3).tolist()} is not behind the mouth")
    mats = g.json.get("materials", [])
    for m in mats:
        nm, ex = m.get("name"), m.get("extras") or {}
        r.check("region" in ex, f"{cid}: material {nm} has no region")
        if nm in TEXTURED:
            r.check("pbr" in ex and "uvScale" in ex, f"{cid}: material {nm} has no texture set")
        r.check(m.get("alphaMode", "OPAQUE") == "OPAQUE", f"{cid}: {nm} must be opaque")
    tris = 0
    no_ao = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        for p, raw in zip(g.mesh_primitives(n["mesh"]), g.json["meshes"][n["mesh"]]["primitives"]):
            tris += len(p["indices"])
            if "COLOR_0" not in raw["attributes"]:
                no_ao.append(f"{n['name']}/{mats[raw['material']].get('name')}")
    r.check(not no_ao, f"{cid}: primitives without baked AO: {no_ao[:6]}")
    budget = BUDGETS.get(f"creature_{cid}")
    r.check(budget is not None and tris <= budget, f"{cid}: {tris} triangles over the budget {budget}")
    r.note(f"{cid}: {tris} triangles / {budget}; {len(mats)} materials; {len(g.nodes)} nodes")
