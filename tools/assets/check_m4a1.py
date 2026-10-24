"""M4A1 GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims models/gun/m4a1.glb.
Nodes and parents, materials and regions, clips (lengths, interpolation, loops, camera range, events, magazine STEP
scale), the AR mechanism (the carrier cycling in `fire`, held open by the catch at the end of `fire_last`, starting
held open in `reload_empty` and slammed home when the thumb hits the catch, the charging handle pulled and returned
in `inspect`, never forward of rest), the magazine leaving in both reloads, the hands on the parts they work (index on
the magazine catch at mag_release, thumb on the bolt catch as the bolt goes home, fingers on the charging handle),
iron sights at ADS, eye distance, framing, the triangle budget and every attachment on every mount (check_attach)."""
from pathlib import Path

import numpy as np

import check_rifle as cr
import hands
import m4a1_model as mm
import m4a1_rig as rig
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, check_sights, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = rig.STATIC
MOVING = ("bolt", "charging_handle", "trigger", "mag_catch", "bolt_catch", "sight_iron", "sight_rear", "muzzle_brake")
MOUNTS = ("mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm", "mount_underbarrel")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC + MOVING}, "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None, **{m: "gun" for m in MOUNTS},
}
MESH_NODES = set(STATIC) | set(MOVING) | {"mag_standard", "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", *MOUNTS}
MATERIALS = {"steel", "steel_dark", "barrel", "polymer", "pad", "mag", "mag_base", "brass", "bore"}
SPLIT_MATERIALS = {"polymer_stock", "polymer_grip"}
REGIONS = {"steel": "body", "steel_dark": "detail", "barrel": "barrel", "polymer": "handguard", "polymer_stock": "stock",
           "polymer_grip": "grip", "pad": "none", "mag": "magazine", "mag_base": "magazine", "brass": "none",
           "bore": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.07, 0.10), "fire_last": (0.07, 0.10),
             "reload": (2.2, 2.2), "reload_empty": (2.7, 2.7), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = LOOPING
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_forward"},
                   "inspect": {"inspect_move", "bolt_back", "bolt_forward"}}
MIN_TRAVEL = 0.07
BUDGET = BUDGETS["long_gun"]
FREE = ("stock", "pistol_grip", "lower", "mag_standard", "mag_extended", "tactical_stock", "heavy_stock", "wire_stock",
        "charm_grass")


def claims(path, names):
    return Path(path).stem == "m4a1"


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        i = g.names[nm]
        par = g.parent.get(i)
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
        if nm in MESH_NODES:
            r.check("mesh" in g.nodes[i], f"{label}: node {nm} should carry a mesh")
        if nm in EMPTY_NODES:
            r.check("mesh" not in g.nodes[i], f"{label}: node {nm} should be empty")
    for n in g.nodes:
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    for nm in cr.REMOVED_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the weapon file (section 19: separate attachment)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in MATERIALS or nm in SPLIT_MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    for nm, m in mats.items():
        ex = m.get("extras") or {}
        if "region" in ex:
            r.check(ex["region"] == REGIONS.get(nm), f"{label}: material {nm} region {ex['region']}, "
                                                     f"expected {REGIONS.get(nm)}")
        if nm != "bore" and "region" in ex:
            r.check("pbr" in ex, f"{label}: material {nm} has no texture set")
    for nm in ("steel", "steel_dark", "barrel"):        # the gold skin tints these
        r.check(nm in mats, f"{label}: the gold skin needs material {nm}")
    applied = all("pbr" in (m.get("extras") or {}) for nm, m in mats.items() if nm != "bore")
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names["camera"]
    for nm, (lo, hi) in DURATIONS.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-4 <= dur <= hi + 1e-4, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
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
                r.check(nm not in NO_CAMERA, f"{label}: {nm} must not animate the camera")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond +-{CAM_TURN}/+-{CAM_ROLL} deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if g.nodes[node]["name"] == "mag" and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} mag scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} mag scale not 0/1")
                r.check(np.allclose(vs[-1], 1.0), f"{label}: {nm} ends without a magazine")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= {e["name"] for e in ev}, f"{label}: {nm} missing events {need - {e['name'] for e in ev}}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))


def _z_track(g, a, node):
    c = _channel(g, a, node, "translation")
    if c is None:
        return None
    rest = np.asarray(g.nodes[g.names[node]].get("translation", [0, 0, 0]), float)
    return c[0], c[1][:, 2] - rest[2], np.abs(c[1][:, :2] - rest[:2]).max()


def _event(a, name):
    return next((e["time"] for e in a.get("extras", {}).get("events", []) if e["name"] == name), None)


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    hold = mm.BOLT_HOLD
    for nm, a in anims.items():
        for node in ("bolt", "charging_handle"):
            z = _z_track(g, a, node)
            if z is None:
                continue
            ts, dz, side = z
            r.check(side < 1e-6, f"{label}: {nm} moves the {node} off its rail")
            r.check(dz.min() > -1e-4, f"{label}: {nm} pushes the {node} forward of its rest")
            if node == "charging_handle":
                r.check(abs(dz[-1]) < 1e-4 and abs(dz[0]) < 1e-4, f"{label}: {nm} leaves the charging handle back")
    for nm in ("fire", "fire_last", "reload_empty", "inspect"):
        z = _z_track(g, anims[nm], "bolt")
        if not r.check(z is not None, f"{label}: {nm} does not move the bolt carrier"):
            continue
        ts, dz, _ = z
        if nm in ("fire", "fire_last"):
            r.check(dz.max() >= MIN_TRAVEL, f"{label}: {nm} carrier travel {dz.max():.3f} < {MIN_TRAVEL}")
            r.check(abs(dz[0]) < 1e-4, f"{label}: {nm} starts with the carrier open")
        if nm == "fire":
            r.check(abs(dz[-1]) < 1e-4, f"{label}: fire ends with the carrier open")
        if nm == "fire_last":
            r.check(abs(dz[-1] - hold) < 1e-3, f"{label}: fire_last does not leave the carrier on the bolt catch "
                                               f"({dz[-1]:.4f} m, hold {hold})")
        if nm == "reload_empty":
            r.check(abs(dz[0] - hold) < 1e-3, f"{label}: reload_empty does not start held open")
            r.check(abs(dz[-1]) < 1e-4, f"{label}: reload_empty leaves the carrier open")
            fwd = _event(anims[nm], "bolt_forward")
            home = ts[np.argmax(dz < 1e-3)]
            r.check(fwd is not None and abs(home - fwd) < 0.02, f"{label}: reload_empty bolt home at {home:.3f}, "
                                                                f"bolt_forward at {fwd}")
            c = _channel(g, anims[nm], "bolt_catch", "rotation")
            if r.check(c is not None, f"{label}: reload_empty never presses the bolt catch"):
                press = c[0][np.argmax(np.abs(c[1][:, 2]) > 0.02)]
                r.check(press <= home, f"{label}: the bolt goes home before the catch is pressed")
        if nm == "inspect":
            ch = _z_track(g, anims[nm], "charging_handle")
            if r.check(ch is not None, f"{label}: inspect never pulls the charging handle"):
                r.check(ch[1].max() > 0.025, f"{label}: inspect pulls the handle only {ch[1].max():.3f} m")
                r.check(dz.max() >= ch[1].max() - 1e-4, f"{label}: inspect: the carrier does not follow the handle")
        r.note(f"{nm}: carrier {dz[0] * 1000:.0f} -> max {dz.max() * 1000:.1f} -> {dz[-1] * 1000:.1f} mm")
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        t = _channel(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float((rest[1] - t[1][:, 1]).max())
        r.check(drop > 0.05, f"{label}: {nm} does not take the magazine out ({drop:.3f} m)")
        b = _channel(g, anims[nm], "mag_catch", "translation")
        rel = _event(anims[nm], "mag_release")
        if r.check(b is not None and rel is not None, f"{label}: {nm} never presses the magazine catch"):
            k = int(np.argmin(np.abs(b[0] - rel)))
            rb = np.asarray(g.nodes[g.names["mag_catch"]]["translation"], float)
            r.check(rb[0] - b[1][k, 0] > 0.0015, f"{label}: {nm} catch not pressed at mag_release")


def _tip(g, W, side, finger):
    if finger == "heel":                                    # the heel of the palm (its face, near the wrist)
        M = W[g.names[f"arm_{side}_hand"]]
        return M[:3, :3] @ np.array([0.0, -rig.HEEL, hands.PALM_T / 2]) + M[:3, 3]
    M = W[g.names[f"arm_{side}_{finger}2"]]
    return M[:3, :3] @ np.array([0.0, -hands.LENGTHS[finger][1], 0.0]) + M[:3, 3]


def check_hands(g, r, label):
    """The fingers on the controls they work, at the moments the clips use them."""
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    cases = [("reload", "mag_release", "R", "index", "mag_catch", mm.MAG_CATCH + np.array([0.0028, 0, 0]), 0.006),
             ("reload_empty", "mag_release", "R", "index", "mag_catch", mm.MAG_CATCH + np.array([0.0028, 0, 0]), 0.006),
             ("reload_empty", "bolt_forward", "L", "heel", "bolt_catch", mm.CATCH_PAD, 0.008),
             ("inspect", "bolt_back", "L", "middle", "charging_handle", rig.CH_GRAB, 0.012)]
    for clip, ev, side, finger, node, point, tol in cases:
        t = _event(anims[clip], ev)
        if t is None:
            continue
        W = g.world_matrices(g.sample(anims[clip], t))
        origin = np.asarray(rig.MESH_ORIGIN.get(node, (0, 0, 0)), float)
        M = W[g.names[node]]
        target = M[:3, :3] @ (np.asarray(point, float) - origin) + M[:3, 3]
        d = float(np.linalg.norm(_tip(g, W, side, finger) - target))
        r.check(d <= tol, f"{label}: {clip} at {ev}: {side} {finger} tip {d * 1000:.1f} mm from the {node}")
        r.note(f"{clip} @ {ev}: {side} {finger} tip {d * 1000:.1f} mm from the {node}")


def check(path, arms, r):
    g = GLTF(path)
    label = Path(path).stem
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_mechanism(g, r, label)
    check_hands(g, r, label)
    check_sights(g, r, label)
    cr.check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    saved = cr.FREE
    cr.FREE = FREE
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= BUDGET, f"{label}: {tris} triangles over the budget of {BUDGET}")
    r.note(f"{label} triangles (whole file: both magazines and fx_shell included): {tris} (budget {BUDGET})")
