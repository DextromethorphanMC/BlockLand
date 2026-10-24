"""Bomb GLB checks (VIEWMODEL_SPEC sections 11-12, 15-17, 20-21, 31 and 39), used by check_glb.py (CHECK_MODULES).

Nodes and parents, materials and their regions, the clip set (lengths, loops, camera rules, STEP scales, events), the
draw time of bomb.json, the bomb in the right hand until it is set down in `plant` and lying still afterwards, the
right index fingertip on a key at every `key` event, the left hand by the bomb while it lies there, eye distance,
curve rate, the triangle budget and the hip pose leaving the crosshair area clear."""
import json
import os

import numpy as np

from check_equipment import check_crosshair
from check_glb import CAM_ROLL, CAM_TURN
from check_glb import check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes, euler_deg
from glbread import GLTF

HERE = os.path.dirname(os.path.abspath(__file__))
BOMB_JSON = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "bomb", "bomb.json")
CLIPS = {"idle": (2.0, 2.0), "walk": (0.6, 1.6), "sprint": (0.66, 0.66), "draw": (0.4, 0.8), "inspect": (2.0, 4.0),
         "plant": (2.5, 4.0)}
LOOPING = {"idle", "walk", "sprint"}
NO_CAMERA = {"idle", "walk", "sprint"}
EVENTS = {"draw", "place", "key"}
NEED = {"draw": {"draw"}, "plant": {"place", "key"}}
PARTS = {"bomb": "viewmodel", "body": "bomb", "keypad": "bomb", "wires": "bomb", "led": "bomb", "camera": "viewmodel",
         "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L"}
MATERIALS = {"explosive": "body", "tape": "tape", "housing": "keypad", "keys": "keypad", "screen": "none", "led": "none",
             "wire_red": "wires", "wire_yellow": "wires", "wire_blue": "wires"}
NO_SET = {"screen", "led", "wire_red", "wire_yellow", "wire_blue"}       # self-lit, or plain plastic insulation
MAX_TRIS = 4000
KEY_TOP = 0.0422                                  # the keys' top face in the keypad's space (bomb_model)


def claims(path, names):
    return "bomb" in names and "keypad" in names


def _visible(W, i):
    return abs(np.linalg.det(W[i][:3, :3])) > 1e-9


def check_nodes(g, r):
    for nm, parent in PARTS.items():
        if not r.check(nm in g.names, f"c4: missing node {nm}"):
            continue
        par = g.parent.get(g.names[nm])
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"c4: node {nm} parent is {pname}, expected {parent}")
    for nm in ("bomb", "camera", "viewmodel"):
        r.check("mesh" not in g.nodes[g.names[nm]], f"c4: {nm} must be empty")
    vm = g.nodes[g.names["viewmodel"]]
    r.check(not any(k in vm for k in ("translation", "rotation", "scale")), "c4: viewmodel rest not identity")
    W = g.world_matrices({})
    r.check(_visible(W, g.names["led"]), "c4: led must be drawn (scale 1) at rest")


def check_materials(g, r):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(MATERIALS), f"c4: materials {sorted(mats)}, expected {sorted(MATERIALS)}")
    for nm, region in MATERIALS.items():
        if nm not in mats:
            continue
        ex = mats[nm].get("extras") or {}
        r.check(ex.get("region") == region, f"c4: material {nm} region {ex.get('region')}, expected {region}")
        if nm not in NO_SET:
            r.check(bool(ex.get("pbr")), f"c4: material {nm} has no texture set")
    for nm in ("screen", "led"):
        if nm in mats:
            r.check(any(c > 0 for c in mats[nm].get("emissiveFactor", [0, 0, 0])), f"c4: {nm} must be emissive")


def check_clips(g, r):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names.get("camera")
    for nm, (lo, hi) in CLIPS.items():
        if not r.check(nm in anims, f"c4: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-3 <= dur <= hi + 1e-3, f"c4: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(node != vm, f"c4: {nm} animates viewmodel")
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"c4: {nm} bad key times")
            if path == "scale":
                r.check(s.get("interpolation") == "STEP", f"c4: {nm} {g.nodes[node]['name']} scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"c4: {nm} scale not 0/1")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"c4: {nm} quaternions")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"c4: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in NO_CAMERA, f"c4: {nm} must not animate the camera (section 17)")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"c4: {nm} camera beyond the section 4 limits")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"c4: {nm} camera not at rest at end")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"c4: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENTS, f"c4: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"c4: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            r.check(NEED.get(nm, set()) <= have, f"c4: {nm} missing events {NEED.get(nm, set()) - have}")
            r.note(f"{nm}: {dur:.3f}s" + ("; events " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev)
                                          if ev else ""))
    return anims


def _events(anims, clip, name):
    return [float(e["time"]) for e in anims.get(clip, {}).get("extras", {}).get("events", []) if e["name"] == name]


def check_timing(g, r, anims):
    with open(BOMB_JSON, encoding="utf-8") as f:
        d = json.load(f)
    dur = g.duration(anims["draw"]) if "draw" in anims else 0.0
    r.check(abs(d.get("drawTime", 0) - dur) < 0.011, f"c4: bomb.json drawTime {d.get('drawTime')} != draw clip {dur:.3f}")
    keys = _events(anims, "plant", "key")
    r.check(len(keys) >= 5, f"c4: plant has {len(keys)} key presses, expected at least 5")


def check_plant(g, r, anims):
    """In the hand until `place`, lying still from then on; the index fingertip on a key at every press; the left hand
    by the bomb while it lies there."""
    a = anims.get("plant")
    if a is None:
        return
    bomb, hand_r, idx2 = g.names["bomb"], g.names["arm_R_hand"], g.names["arm_R_index2"]
    t_place = _events(anims, "plant", "place")[0]
    worst = 0.0
    for t in np.arange(0.0, t_place, 1 / 60):
        W = g.world_matrices(g.sample(a, t))
        worst = max(worst, float(np.linalg.norm(W[bomb][:3, 3] - W[hand_r][:3, 3])))
    r.check(worst < 0.12, f"c4: plant: the bomb leaves the hand before it is set down ({worst:.3f} m)")
    W0 = g.world_matrices(g.sample(a, t_place))
    drift = 0.0
    for t in np.arange(t_place, g.duration(a) + 1e-9, 1 / 60):
        W = g.world_matrices(g.sample(a, t))
        drift = max(drift, float(np.linalg.norm(W[bomb][:3, 3] - W0[bomb][:3, 3])))
        r.check(_visible(W, bomb), f"c4: plant: the bomb is hidden at {t:.2f}")
    r.check(drift < 0.002, f"c4: plant: the bomb moves {drift * 1000:.1f} mm after it was set down")
    # the fingertip: end of the second index segment (local -Y, segment length from the finger's rest offset)
    keypad = g.names["keypad"]
    tops = []
    for t in _events(anims, "plant", "key"):
        W = g.world_matrices(g.sample(a, t))
        tip = W[idx2][:3, :3] @ np.array([0.0, -0.026 * 0.88, 0.0]) + W[idx2][:3, 3]
        local = np.linalg.inv(W[keypad]) @ np.append(tip, 1.0)
        tops.append(local[:3])
    if tops:
        tops = np.array(tops)
        r.check(np.all(np.abs(tops[:, 0]) < 0.034) and np.all(np.abs(tops[:, 2]) < 0.052),
                f"c4: plant: a key press lands off the keypad ({np.round(tops, 3).tolist()})")
        height = tops[:, 1] - KEY_TOP
        r.check(np.all(np.abs(height) < 0.016), f"c4: plant: the fingertip is not at the key tops on a press "
                                                f"({np.round(height * 1000, 1).tolist()} mm)")
        r.note(f"plant: fingertip at the key tops within {np.max(np.abs(height)) * 1000:.0f} mm on {len(tops)} presses")
    hand_l = g.names["arm_L_hand"]
    W = g.world_matrices(g.sample(a, 1.5))
    dl = float(np.linalg.norm(W[hand_l][:3, 3] - W[bomb][:3, 3]))
    r.check(_visible(W, g.names["arm_L"]) and dl < 0.15, f"c4: plant: the left hand is {dl:.3f} m from the bomb")
    r.note(f"plant: set down at {t_place:.2f}s, still within {drift * 1000:.1f} mm; left hand {dl * 1000:.0f} mm away")


def check(path, arms, r):
    g = GLTF(path)
    check_container(g, r, "c4")
    check_meshes(g, r, "c4")
    check_nodes(g, r)
    check_materials(g, r)
    anims = check_clips(g, r)
    check_timing(g, r, anims)
    check_plant(g, r, anims)
    g.names["equipment"] = g.names["bomb"]          # the shared crosshair check looks for the item's root by name
    check_crosshair(g, r, "c4", anims)
    check_eye_distance(g, arms, r, anims, root="bomb", label="c4")
    check_hand_joints(g, r, "c4")
    check_curve_rate(g, r, "c4")
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= MAX_TRIS, f"c4: {tris} triangles (budget {MAX_TRIS})")
    r.note(f"c4 triangles: {tris}")
