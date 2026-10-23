"""Dagger GLB checks (VIEWMODEL_SPEC sections 11-12, 16-17, 19-21 and 27), used by check_glb.py (CHECK_MODULES).

Nodes and parents, materials and their regions, the clip set (lengths, loops, camera rules, events), contact times
at the fractions of knives.json (light: the blade over the screen centre; heavy: full extension), hands on the
grip, eye distance, curve rate, the charm mount, the triangle budget, and the hip pose leaving the crosshair area
clear but for the point."""
import json
import os

import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (FOV_Y, check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       euler_deg)
from check_knife import blade_crossing
from glbread import GLTF

HERE = os.path.dirname(os.path.abspath(__file__))
KNIVES_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "melee",
                           "knives.json")
CLIPS = {"idle": (2.0, 2.0), "draw": (0.55, 0.65), "light_1": (0.36, 0.36), "light_2": (0.36, 0.36),
         "heavy": (0.80, 0.80), "inspect": (3.0, 3.8), "walk": (0.6, 1.6), "sprint": (0.66, 0.66),
         "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENTS = {"swing", "swing_heavy", "flip", "catch", "equip"}
REQUIRED_EVENTS = {"draw": {"equip", "flip", "catch"}, "light_1": {"swing"}, "light_2": {"swing"},
                   "heavy": {"swing_heavy"}, "inspect": {"flip", "catch"}}
MATERIALS = {"blade": "blade", "blade_edge": "blade", "guard": "hardware", "pommel": "hardware", "handle": "handle"}
PARENTS = {"viewmodel": None, "knife": "viewmodel", "blade": "knife", "tip": "blade", "guard": "knife",
           "handle": "knife", "pommel": "knife", "mount_charm": "knife", "camera": "viewmodel",
           "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
MESH_NODES = {"blade", "guard", "handle", "pommel"}
MAX_TRIS = 3000             # section 24 budgets: knives
CROSSHAIR = 0.15            # section 2: half side of the crosshair square, in screen heights
POINT_SHARE = 0.40          # the outer share of the blade towards the point may enter it (the balisong: its whole blade)


def claims(path, names):
    return "knife" in names and "pommel" in names


def contacts(knife_id="dagger"):
    """{clip: contact fraction} from knives.json (impact / duration of the attack that plays the clip)."""
    with open(KNIVES_JSON, encoding="utf-8") as f:
        knife = json.load(f)["knives"][knife_id]
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
    for nm in MESH_NODES:
        if nm in g.names:
            r.check("mesh" in g.nodes[g.names[nm]], f"{label}: {nm} must carry a mesh")
    for nm in ("tip", "mount_charm", "camera", "viewmodel", "knife"):
        if nm in g.names:
            r.check("mesh" not in g.nodes[g.names[nm]], f"{label}: {nm} must be empty")
    for i, n in enumerate(g.nodes):
        if "mesh" in n:
            r.check(n["name"] in MESH_NODES, f"{label}: unexpected mesh node {n.get('name')}")
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    # section 19: the charm mount's -Y hangs straight down in the rest pose
    W = g.world_matrices({})
    down = W[g.names["mount_charm"]][:3, :3] @ np.array([0.0, -1.0, 0.0])
    r.check(down[1] < -0.999, f"{label}: mount_charm -Y does not hang straight down ({np.round(down, 3)})")
    # the point is the tip of the blade (furthest blade vertex from the guard)
    blade = g.names["blade"]
    pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[blade]["mesh"])])
    tip_local = np.asarray(g.nodes[g.names["tip"]].get("translation", [0, 0, 0]))
    far = pts[int(np.argmax(np.linalg.norm(pts, axis=1)))]
    r.check(np.linalg.norm(far - tip_local) < 0.002, f"{label}: tip {np.round(tip_local, 4)} is not at the point "
                                                     f"{np.round(far, 4)}")


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
    vm, cam, arm_l = g.names["viewmodel"], g.names.get("camera"), g.names["arm_L"]
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
            r.check(s.get("interpolation", "LINEAR") in ("LINEAR", "STEP"), f"{label}: {nm} interpolation")
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
                        f"{label}: {nm} camera beyond +-1.5/+-3 deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if node == arm_l and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} arm_L scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} arm_L scale not 0/1")
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
                pre = [e["time"] for e in ev if e["name"] in ("swing", "swing_heavy")]
                r.check(bool(pre) and all(t < hit for t in pre), f"{label}: {nm} swing event must precede contact")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def check_contacts(g, r, label, anims):
    for nm, frac in contacts().items():
        if nm not in anims:
            continue
        a = anims[nm]
        dur = g.duration(a)
        want = frac * dur
        if nm.startswith("light"):
            hits = blade_crossing(g, a, dur)
            got = min(hits, key=lambda t: abs(t - want)) if hits else None
            r.check(got is not None and abs(got - want) <= 0.005,
                    f"{label}: {nm} blade crosses the screen centre at {got} s, expected {want:.3f}")
            if got is not None:
                r.note(f"{nm}: blade over the screen centre at {got:.4f}s = {got / dur:.4f} of the clip "
                       f"(target {frac:.4f})")
        else:
            ts = np.arange(0, dur + 1e-9, 0.001)
            reach = [np.linalg.norm(g.world_matrices(g.sample(a, t))[g.names["tip"]][:3, 3]) for t in ts]
            got = float(ts[int(np.argmax(reach))])
            r.check(abs(got - want) <= 0.01, f"{label}: {nm} full extension at {got:.3f} s, expected {want:.3f}")
            r.note(f"{nm}: tip furthest from the eye ({max(reach):.3f} m) at {got:.3f}s = {got / dur:.4f} of the clip "
                   f"(target {frac:.4f})")


def check_crosshair(g, r, label, anims):
    """Hip pose (rest, idle, walk): only the outer part of the blade may enter the crosshair square."""
    k = 2 * CROSSHAIR * np.tan(np.radians(FOV_Y / 2))
    blade = g.names["blade"]
    tip_local = np.asarray(g.nodes[g.names["tip"]].get("translation", [0, 0, 0]))
    parts = []
    for nm in MESH_NODES:
        i = g.names[nm]
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[i]["mesh"])])
        if i == blade:                     # leave out the outer share towards the point
            along = pts @ tip_local / max(np.dot(tip_local, tip_local), 1e-12)
            pts = pts[along < 1 - POINT_SHARE]
        parts.append((i, pts))
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
        r.check(worst == 0.0, f"{label}: {nm}: {worst:.0%} of the dagger (not the point) in the crosshair area")
    r.note(f"hip pose: the crosshair square ({2 * CROSSHAIR:.1f} screen heights) is clear but for the point")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_contacts(g, r, label, anims)
    check_crosshair(g, r, label, anims)
    check_eye_distance(g, arms, r, anims, root="knife", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"dagger triangles: {tris}")
