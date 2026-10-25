"""Body armour GLB checks (VIEWMODEL_SPEC sections 15-17, 20-21 and 50), used by check_glb.py (CHECK_MODULES).

vest.glb: the node contract (`armor` root, `carrier` and `plate_1` … `plate_3` meshes, nothing animated), materials and
regions, every vertex outside the torso the runtime draws it on (it never cuts into the body) and close round it, the
budget. plate.glb: nodes and parents, materials, the clip set (lengths, loops, camera rules, events and their sound
events), both hands fixed on the plate in every frame, eye distance, hand joints, curve rate, the budget."""
import json
import os

import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

HERE = os.path.dirname(os.path.abspath(__file__))
SOUNDS_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "sounds.json")
TORSO = (np.array([-0.25, -0.75, -0.125]), np.array([0.25, 0.0, 0.125]))
VEST_BOUNDS = (np.array([-0.30, -0.64, -0.23]), np.array([0.30, 0.04, 0.23]))
VEST_MATERIALS = {"carrier": "body", "webbing": "body", "hardware": "hardware"}
PLATE_MATERIALS = {"plate": "body", "label": "none"}
PLATE_PARENTS = {"viewmodel": None, "plate": "viewmodel", "plate_body": "plate", "camera": "viewmodel",
                 "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
CLIPS = {"idle": (2.0, 2.0), "draw": (0.4, 0.7), "insert": (0.8, 1.2), "walk": (0.6, 1.6), "sprint": (0.5, 0.8)}
LOOPING = {"idle", "walk", "sprint"}
NO_CAMERA = {"idle", "walk", "sprint"}
EVENTS = {"plate_draw"}
MAX_TRIS = BUDGETS["equipment"]


def claims(path, names):
    p = path.replace("\\", "/")
    return "/models/armor/" in p and os.path.basename(p) in ("vest.glb", "plate.glb")


def check_materials(g, r, label, wanted):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(wanted), f"{label}: materials {sorted(mats)}, expected {sorted(wanted)}")
    for nm, region in wanted.items():
        if nm not in mats:
            continue
        extras = mats[nm].get("extras") or {}
        r.check(extras.get("region") == region, f"{label}: material {nm} region {extras.get('region')}, expected {region}")
        if region != "none":
            r.check(bool(extras.get("pbr")), f"{label}: material {nm} has no texture set")


def tris_of(g):
    return sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])


def check_vest(g, r, label):
    names = g.names
    for nm in ("armor", "carrier", "plate_1", "plate_2", "plate_3"):
        r.check(nm in names, f"{label}: missing node {nm}")
    for nm in ("carrier", "plate_1", "plate_2", "plate_3"):
        if nm in names:
            par = g.parent.get(names[nm])
            r.check(par is not None and g.nodes[par]["name"] == "armor", f"{label}: {nm} must hang under armor")
            r.check("mesh" in g.nodes[names[nm]], f"{label}: {nm} must carry a mesh")
    r.check(not g.json.get("animations"), f"{label}: the carrier has no clips (it moves with the torso)")
    check_materials(g, r, label, VEST_MATERIALS)
    W = g.world_matrices({})
    inside, out_of_bounds = 0, 0
    lo, hi = TORSO[0] + 0.001, TORSO[1] - 0.001
    for i, n in enumerate(g.nodes):
        if "mesh" not in n:
            continue
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(n["mesh"])]) @ W[i][:3, :3].T + W[i][:3, 3]
        inside += int(np.all((pts > lo) & (pts < hi), axis=1).sum())
        out_of_bounds += int(np.any((pts < VEST_BOUNDS[0]) | (pts > VEST_BOUNDS[1]), axis=1).sum())
    r.check(inside == 0, f"{label}: {inside} vertices inside the torso (the carrier must lie on it)")
    r.check(out_of_bounds == 0, f"{label}: {out_of_bounds} vertices stand off the torso too far")
    tris = tris_of(g)
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"carrier triangles: {tris}; on the torso, nothing cuts into it")


def check_plate(g, r, label, arms):
    for nm, parent in PLATE_PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        r.check((g.nodes[par]["name"] if par is not None else None) == parent, f"{label}: node {nm} parent is wrong")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    check_materials(g, r, label, PLATE_MATERIALS)
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        sounds = json.load(f)
    for ev in EVENTS:
        r.check(f"armor.{ev}" in sounds, f"{label}: event {ev} has no sound event armor.{ev}")
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    cam = g.names.get("camera")
    for nm, (lo, hi) in CLIPS.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-3 <= dur <= hi + 1e-3, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            vs = g.accessor(s["output"]).astype(np.float64)
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in NO_CAMERA, f"{label}: {nm} must not animate the camera")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond its limits")
        for e in a.get("extras", {}).get("events", []):
            r.check(e.get("name") in EVENTS, f"{label}: {nm} unknown event {e.get('name')}")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels")
    # both hands stay where they hold the plate (the wrist in the plate's frame)
    plate = g.names["plate"]
    worst = 0.0
    for side in "RL":
        hand = g.names[f"arm_{side}_hand"]

        def local(W):
            return np.linalg.inv(W[plate])[:3, :3] @ (W[hand][:3, 3] - W[plate][:3, 3])
        ref = local(g.world_matrices({}))
        for nm, a in anims.items():
            for t in np.arange(0, g.duration(a) + 1e-9, 1 / 60):
                worst = max(worst, float(np.linalg.norm(local(g.world_matrices(g.sample(a, t))) - ref)))
    r.check(worst <= 0.003, f"{label}: a hand slips {worst * 1000:.1f} mm on the plate")
    r.note(f"both hands on the plate within {worst * 1000:.2f} mm in every frame")
    check_eye_distance(g, arms, r, anims, root="plate", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    tris = tris_of(g)
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"plate triangles: {tris}")


def check(path, arms, r):
    g = GLTF(path)
    label = os.path.basename(path)[:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    if label == "vest":
        check_vest(g, r, label)
    else:
        check_plate(g, r, label, arms)
