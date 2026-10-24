"""Knuckles GLB checks (VIEWMODEL_SPEC sections 11-12, 16-17, 19-21, 31 and 42), used by check_glb.py (CHECK_MODULES).

Nodes and parents (two dusters, each rigid on its hand), materials and their regions, the clip set (lengths, loops,
camera rules, events), contact times at the fractions of knives.json (the punching fist's striking face at full
extension, in front of the screen centre), the four fingers of each fist through their rings in every frame, both
arms always shown, the hip pose clear of the crosshair, eye distance, curve rate, the charm mount and the budget."""
import json
import os

import numpy as np

import knuckles_model as km
from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (FOV_Y, check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

HERE = os.path.dirname(os.path.abspath(__file__))
KNIVES_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "melee",
                           "knives.json")
CLIPS = {"idle": (2.0, 2.0), "draw": (0.45, 0.65), "light_1": (0.30, 0.30), "light_2": (0.30, 0.30),
         "heavy": (0.62, 0.62), "inspect": (2.6, 3.4), "walk": (0.6, 1.6), "sprint": (0.66, 0.66),
         "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENTS = {"knuckles_equip", "knuckles_swing", "knuckles_swing_heavy", "knuckles_clink"}
REQUIRED_EVENTS = {"draw": {"knuckles_equip", "knuckles_clink"}, "light_1": {"knuckles_swing"},
                   "light_2": {"knuckles_swing"}, "heavy": {"knuckles_swing_heavy"}, "inspect": {"knuckles_clink"}}
SWINGS = ("knuckles_swing", "knuckles_swing_heavy")
PUNCHING_TIP = {"light_1": "tip_L", "light_2": "tip", "heavy": "tip"}     # which fist lands in each attack
MATERIALS = {"frame": "blade", "face": "blade", "wrap": "handle"}
PARENTS = {"viewmodel": None, "knife": "viewmodel", "duster": "knife", "tip": "knife", "mount_charm": "knife",
           "knife_L": "viewmodel", "duster_L": "knife_L", "tip_L": "knife_L", "camera": "viewmodel",
           "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
MESH_NODES = {"duster", "duster_L"}
MAX_TRIS = BUDGETS["knife"]
CROSSHAIR = 0.15            # section 2: half side of the crosshair square, in screen heights
CENTRE_DEG = 10.0           # the landing fist's striking face within this angle of the screen centre
FIT_OFFSET = 0.0015         # each finger's centre line within this of its ring's centre (m) ...
FIT_ANGLE = 10.0            # ... and within this angle of the ring's axis (deg)
FINGERS = ("index", "middle", "ring", "pinky")


def claims(path, names):
    return os.path.basename(path) == "knuckles.glb"


def contacts():
    with open(KNIVES_JSON, encoding="utf-8") as f:
        knife = json.load(f)["knives"]["knuckles"]
    out = {}
    for attack in ("light", "heavy"):
        a = knife[attack]
        for swing in a.get("swings", []):
            out[swing["clip"]] = a["impact"] / a["duration"]
    return out


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
    for nm in ("ring", "pommel"):
        r.check(nm not in g.names, f"{label}: node {nm} would make another knife's checker claim the file")
    for nm in MESH_NODES:
        if nm in g.names:
            r.check("mesh" in g.nodes[g.names[nm]], f"{label}: {nm} must carry a mesh")
    for nm in ("tip", "tip_L", "mount_charm", "camera", "viewmodel", "knife", "knife_L"):
        if nm in g.names:
            r.check("mesh" not in g.nodes[g.names[nm]], f"{label}: {nm} must be empty")
    for n in g.nodes:
        if "mesh" in n:
            r.check(n["name"] in MESH_NODES, f"{label}: unexpected mesh node {n.get('name')}")
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
        if n.get("name") == "arm_L":
            r.check("scale" not in n, f"{label}: arm_L must not be hidden (both fists are always shown)")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    W = g.world_matrices({})
    down = W[g.names["mount_charm"]][:3, :3] @ np.array([0.0, -1.0, 0.0])
    r.check(down[1] < -0.999, f"{label}: mount_charm -Y does not hang straight down ({np.round(down, 3)})")
    # the tips sit on the striking faces, ahead of the rings; the two dusters mirror each other
    for tip, mesh in (("tip", "duster"), ("tip_L", "duster_L")):
        tl = np.asarray(g.nodes[g.names[tip]].get("translation", [0, 0, 0]))
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names[mesh]]["mesh"])])
        r.check(tl[1] < -(km.HOLE_B + km.BAND) + 0.003 and tl[1] > pts[:, 1].min() - 1e-4,
                f"{label}: {tip} {np.round(tl, 4)} is not on the striking face")
    a = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names["duster"]]["mesh"])])
    b = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names["duster_L"]]["mesh"])])
    mirror = np.array([-1.0, 1.0, 1.0])
    r.check(np.allclose(a.min(0), (b * mirror).min(0), atol=1e-5) and np.allclose(a.max(0), (b * mirror).max(0), atol=1e-5)
            and abs(len(a) - len(b)) <= 0.01 * len(a), f"{label}: duster_L is not duster mirrored")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(MATERIALS), f"{label}: materials {sorted(mats)}, expected {sorted(MATERIALS)}")
    for nm, region in MATERIALS.items():
        if nm in mats:
            got = (mats[nm].get("extras") or {}).get("region")
            r.check(got == region, f"{label}: material {nm} region {got}, expected {region}")
            r.check(bool((mats[nm].get("extras") or {}).get("pbr")), f"{label}: material {nm} has no texture set")


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names.get("camera")
    contact = contacts()
    for nm, (lo, hi) in CLIPS.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-3 <= dur <= hi + 1e-3, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(node != vm, f"{label}: {nm} animates viewmodel")
            r.check(path != "scale", f"{label}: {nm} scales {g.nodes[node]['name']} (nothing is hidden)")
            r.check(s.get("interpolation", "LINEAR") == "LINEAR", f"{label}: {nm} interpolation")
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"{label}: {nm} bad key times")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"{label}: {nm} quaternions")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in NO_CAMERA, f"{label}: {nm} must not animate the camera (section 17)")
                r.check(path == "rotation", f"{label}: {nm} animates camera {path}")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond +-{CAM_TURN}/+-{CAM_ROLL} deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENTS, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            if nm in contact:
                hit = contact[nm] * dur
                pre = [e["time"] for e in ev if e["name"] in SWINGS]
                r.check(bool(pre) and all(t < hit for t in pre), f"{label}: {nm} swing event must precede contact")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def check_contacts(g, r, label, anims):
    """The landing fist's striking face is furthest from the eye at the contact time, in front of the screen centre."""
    for nm, frac in contacts().items():
        if nm not in anims:
            continue
        a = anims[nm]
        dur = g.duration(a)
        want = frac * dur
        tip = g.names[PUNCHING_TIP[nm]]
        ts = np.arange(0, dur + 1e-9, 0.001)
        pos = np.array([g.world_matrices(g.sample(a, t))[tip][:3, 3] for t in ts])
        reach = np.linalg.norm(pos, axis=1)
        k = int(np.argmax(reach))
        got = float(ts[k])
        r.check(abs(got - want) <= 0.01, f"{label}: {nm} full extension at {got:.3f} s, expected {want:.3f}")
        at = pos[int(round(want / 0.001))]
        off = float(np.degrees(np.arctan2(np.hypot(at[0], at[1]), -at[2])))
        r.check(at[2] < 0 and off <= CENTRE_DEG,
                f"{label}: {nm} striking face {off:.1f} deg from the screen centre at the contact (max {CENTRE_DEG})")
        r.note(f"{nm}: {PUNCHING_TIP[nm]} furthest from the eye ({reach[k]:.3f} m) at {got:.3f}s = {got / dur:.4f} of "
               f"the clip (target {frac:.4f}); {off:.1f} deg off the centre at the contact")


def check_fit(g, r, label, anims):
    """Every frame: each finger's first segment runs through its ring (the duster rides on the hand)."""
    worst = (0.0, 0.0, "")
    for side, root in (("R", "knife"), ("L", "knife_L")):
        sx = 1.0 if side == "R" else -1.0
        joints = [g.names[f"arm_{side}_{f}"] for f in FINGERS]
        holes = [np.array([sx * x, 0.0, 0.0]) for x in km.RING_X]
        for nm, a in [("rest", None)] + list(anims.items()):
            dur = g.duration(a) if a is not None else 0.0
            for t in np.arange(0, dur + 1e-9, 1 / 30):
                W = g.world_matrices(g.sample(a, t) if a is not None else {})
                inv = np.linalg.inv(W[g.names[root]])
                for f, j, hole in zip(FINGERS, joints, holes):
                    M = inv @ W[j]
                    p0, d = M[:3, 3], M[:3, :3] @ np.array([0.0, -1.0, 0.0])
                    if abs(d[2]) < 1e-6:
                        worst = max(worst, (1.0, 90.0, f"{side} {f} {nm} {t:.2f}"))
                        continue
                    s = -p0[2] / d[2]
                    cross = p0 + s * d
                    off = float(np.hypot(cross[0] - hole[0], cross[1] - hole[1]))
                    ang = float(np.degrees(np.arccos(min(1.0, abs(d[2]) / np.linalg.norm(d)))))
                    if off > worst[0] or ang > worst[1]:
                        worst = (max(off, worst[0]), max(ang, worst[1]), f"{side} {f} {nm} t={t:.2f}")
    r.check(worst[0] <= FIT_OFFSET and worst[1] <= FIT_ANGLE,
            f"{label}: a finger leaves its ring ({worst[0] * 1000:.2f} mm off, {worst[1]:.1f} deg; {worst[2]})")
    r.note(f"fit: every finger through its ring (worst {worst[0] * 1000:.2f} mm off centre, {worst[1]:.1f} deg)")


def check_crosshair(g, r, label, anims):
    """Hip pose (rest, idle, walk): no part of either duster in the crosshair square."""
    k = 2 * CROSSHAIR * np.tan(np.radians(FOV_Y / 2))
    parts = [(g.names[nm], np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names[nm]]["mesh"])]))
             for nm in MESH_NODES]
    for nm in ("rest", "idle", "walk"):
        a = anims.get(nm)
        dur = g.duration(a) if a is not None else 0.0
        worst = 0.0
        for t in np.arange(0, dur + 1e-9, 1 / 30):
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            for i, pts in parts:
                w = pts @ W[i][:3, :3].T + W[i][:3, 3]
                zf = np.maximum(-w[:, 2], 1e-6)
                inside = (np.abs(w[:, 0]) <= k * zf) & (np.abs(w[:, 1]) <= k * zf) & (w[:, 2] < 0)
                worst = max(worst, float(inside.mean()))
        r.check(worst == 0.0, f"{label}: {nm}: {worst:.0%} of a duster in the crosshair area")
    r.note(f"hip pose: the crosshair square ({2 * CROSSHAIR:.1f} screen heights) is clear")


def check(path, arms, r):
    g = GLTF(path)
    label = "knuckles"
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_contacts(g, r, label, anims)
    check_fit(g, r, label, anims)
    check_crosshair(g, r, label, anims)
    check_eye_distance(g, arms, r, anims, root="knife", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"knuckles triangles: {tris}")
