"""Crowbar GLB checks (VIEWMODEL_SPEC sections 11-12, 16-17, 19-21, 31 and 41), used by check_glb.py (CHECK_MODULES).

Nodes and parents, materials and their regions, the clip set (lengths, loops, camera rules, events and their sound
events), contact times at the fractions of knives.json (every swing: the bar's head over the screen centre), the right
hand on the tape and the left hand on the shaft while it holds it, eye distance, curve rate, the charm mount, the
triangle budget, and the hip pose leaving the crosshair area clear but for the head."""
import json
import os

import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (FOV_Y, check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
KNIVES_JSON = os.path.join(RES, "data", "blockland", "melee", "knives.json")
SOUNDS_JSON = os.path.join(RES, "assets", "blockland", "sounds.json")
CLIPS = {"idle": (2.0, 2.0), "draw": (0.75, 0.85), "light_1": (0.62, 0.62), "light_2": (0.62, 0.62),
         "heavy": (1.15, 1.15), "inspect": (3.5, 4.0), "walk": (0.6, 1.6), "sprint": (0.66, 0.66),
         "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENTS = {"crowbar_equip", "crowbar_swing", "crowbar_swing_heavy", "crowbar_tap"}
REQUIRED_EVENTS = {"draw": {"crowbar_equip"}, "light_1": {"crowbar_swing"}, "light_2": {"crowbar_swing"},
                   "heavy": {"crowbar_swing_heavy"}, "inspect": {"crowbar_tap"}}
MATERIALS = {"bar": "blade", "ends": "hardware", "grip": "handle"}
PARENTS = {"viewmodel": None, "knife": "viewmodel", "bar": "knife", "tip": "bar", "grip": "knife",
           "mount_charm": "knife", "camera": "viewmodel", "arm_R": "viewmodel", "arm_R_fore": "arm_R",
           "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
MESH_NODES = {"bar", "grip"}
MAX_TRIS = BUDGETS["knife"]            # section 24 budgets: knives
CROSSHAIR = 0.15                       # section 2: half side of the crosshair square, in screen heights
HEAD_SHARE = 0.15                      # the outer share of the bar towards the head may enter it at the hip
SUPPORT = (0.30, 0.62)                 # heavy: the left hand holds the shaft (knives json timing, crowbar_clips)


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == "crowbar"


def contacts(knife_id="crowbar"):
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
    for n in g.nodes:
        if "mesh" in n:
            r.check(n["name"] in MESH_NODES, f"{label}: unexpected mesh node {n.get('name')}")
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), f"{label}: viewmodel rest not identity")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    W = g.world_matrices({})
    down = W[g.names["mount_charm"]][:3, :3] @ np.array([0.0, -1.0, 0.0])
    r.check(down[1] < -0.999, f"{label}: mount_charm -Y does not hang straight down ({np.round(down, 3)})")
    # the tip is the goose-neck's nose: the bar's furthest point towards the head (knife -Z)
    pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[g.names["bar"]]["mesh"])])
    tip = np.asarray(g.nodes[g.names["tip"]].get("translation", [0, 0, 0]))
    r.check(abs(tip[2] - pts[:, 2].min()) < 0.0015, f"{label}: tip z {tip[2]:.4f} is not the nose "
                                                    f"(bar reaches {pts[:, 2].min():.4f})")
    length = pts[:, 2].max() - pts[:, 2].min()
    r.check(0.57 <= length <= 0.63, f"{label}: bar {length:.3f} m long, expected about 0.60 m")
    r.note(f"bar length {length:.3f} m (chisel tip to the goose-neck's nose)")


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
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        sounds = json.load(f)
    for ev in sorted(EVENTS):                  # section 12: event <name> plays blockland:knife.<name>
        r.check(f"knife.{ev}" in sounds, f"{label}: event {ev} has no sound event knife.{ev} in sounds.json")
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
                        f"{label}: {nm} camera beyond +-{CAM_TURN}/+-{CAM_ROLL} deg")
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
                pre = [e["time"] for e in ev if e["name"] in ("crowbar_swing", "crowbar_swing_heavy")]
                r.check(bool(pre) and all(t < hit for t in pre), f"{label}: {nm} swing event must precede contact")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def bar_crossing(g, a, dur, dt=0.0005):
    """Times at which the bar (the grip -> the goose-neck's nose) passes over the screen centre."""
    out, prev = [], None
    for t in np.arange(0, dur + 1e-9, dt):
        W = g.world_matrices(g.sample(a, t))
        A, B = W[g.names["bar"]][:3, 3], W[g.names["tip"]][:3, 3]
        if A[2] >= -0.02 or B[2] >= -0.02:
            prev = None
            continue
        pa, pb = A[:2] / -A[2], B[:2] / -B[2]
        d = pb - pa
        side = d[0] * (-pa[1]) - d[1] * (-pa[0])
        s = float(np.dot(-pa, d) / max(np.dot(d, d), 1e-12))
        if prev is not None and np.sign(side) != np.sign(prev[1]) and 0.5 <= s <= 1.0:   # the head half
            t0, s0 = prev[0], prev[1]
            out.append(t0 + (t - t0) * s0 / (s0 - side))
        prev = (t, side)
    return out


def head_closest(g, a, dur, dt=0.0005):
    """(time, distance) at which the head's hit point (88 % of the way from the grip to the nose) passes closest to
    the screen centre, in screen-height units (a vertical smash travels along the bar's own line on screen, so it
    never crosses it)."""
    best = (None, np.inf)
    for t in np.arange(0, dur + 1e-9, dt):
        W = g.world_matrices(g.sample(a, t))
        A, B = W[g.names["bar"]][:3, 3], W[g.names["tip"]][:3, 3]
        h = A + 0.88 * (B - A)
        if h[2] >= -0.02:
            continue
        d = float(np.linalg.norm(h[:2] / -h[2])) / (2 * np.tan(np.radians(FOV_Y / 2)))
        if d < best[1]:
            best = (float(t), d)
    return best


def check_contacts(g, r, label, anims):
    for nm, frac in contacts().items():
        if nm not in anims:
            continue
        a = anims[nm]
        dur = g.duration(a)
        want = frac * dur
        if nm.startswith("light"):
            hits = bar_crossing(g, a, dur)
            got = min(hits, key=lambda t: abs(t - want)) if hits else None
            r.check(got is not None and abs(got - want) <= 0.005,
                    f"{label}: {nm} head crosses the screen centre at {got} s, expected {want:.3f}")
            if got is not None:
                r.note(f"{nm}: the head over the screen centre at {got:.4f}s = {got / dur:.4f} of the clip "
                       f"(target {frac:.4f})")
        else:
            got, d = head_closest(g, a, dur)
            r.check(got is not None and abs(got - want) <= 0.01 and d <= 0.02,
                    f"{label}: {nm} head closest to the screen centre ({d:.3f}) at {got} s, expected {want:.3f}")
            r.note(f"{nm}: the head lands on the screen centre ({d:.4f} screen heights off) at {got:.4f}s = "
                   f"{got / dur:.4f} of the clip (target {frac:.4f})")


def check_hands(g, r, label, anims):
    """The right wrist keeps its place on the tape (distance to the bar's axis and along it) in every frame of every
    clip; the left wrist keeps its place on the shaft while the heavy holds it."""
    bar, hand_r, hand_l = g.names["bar"], g.names["arm_R_hand"], g.names["arm_L_hand"]

    def local(W, i):
        M = W[bar]
        return np.linalg.inv(M)[:3, :3] @ (W[i][:3, 3] - M[:3, 3])

    W0 = g.world_matrices({})
    ref = local(W0, hand_r)
    ref_r, ref_z = np.hypot(ref[0], ref[1]), ref[2]
    worst = 0.0
    for nm, a in anims.items():
        dur = g.duration(a)
        for t in np.arange(0, dur + 1e-9, 1 / 60):
            p = local(g.world_matrices(g.sample(a, t)), hand_r)
            worst = max(worst, abs(np.hypot(p[0], p[1]) - ref_r), abs(p[2] - ref_z))
    r.check(worst <= 0.002, f"{label}: the right hand slips {worst * 1000:.1f} mm on the tape")
    r.note(f"right hand on the tape: within {worst * 1000:.2f} mm in every frame")
    a = anims.get("heavy")
    if a is None:
        return
    ps = [local(g.world_matrices(g.sample(a, t)), hand_l) for t in np.arange(SUPPORT[0], SUPPORT[1], 1 / 60)]
    ps = np.array(ps)
    spread = float(np.max(np.ptp(np.stack([np.hypot(ps[:, 0], ps[:, 1]), ps[:, 2]], 1), axis=0)))
    r.check(spread <= 0.003, f"{label}: heavy: the left hand slides {spread * 1000:.1f} mm on the shaft")
    r.note(f"heavy: left hand on the shaft {SUPPORT[0]:.2f}-{SUPPORT[1]:.2f}s, within {spread * 1000:.2f} mm")


def check_crosshair(g, r, label, anims):
    """Hip pose (rest, idle, walk): only the outer share of the bar towards the head may enter the crosshair square."""
    k = 2 * CROSSHAIR * np.tan(np.radians(FOV_Y / 2))
    tip = np.asarray(g.nodes[g.names["tip"]].get("translation", [0, 0, 0]))
    parts = []
    for nm in MESH_NODES:
        i = g.names[nm]
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(g.nodes[i]["mesh"])])
        if nm == "bar":
            pts = pts[pts[:, 2] > (1 - HEAD_SHARE) * tip[2]]
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
        r.check(worst == 0.0, f"{label}: {nm}: {worst:.0%} of the crowbar (not the head) in the crosshair area")
    r.note(f"hip pose: the crosshair square ({2 * CROSSHAIR:.1f} screen heights) is clear but for the head")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_contacts(g, r, label, anims)
    check_hands(g, r, label, anims)
    check_crosshair(g, r, label, anims)
    check_eye_distance(g, arms, r, anims, root="knife", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"{label}: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"crowbar triangles: {tris}")
