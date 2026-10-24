"""Uzi GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims models/gun/uzi.glb.
Nodes and parents, materials, regions and texture sets, clips (lengths against the data's reload times, loops, the
camera's limits and rest at the ends, events, the magazine's STEP scale), the mechanism (the cocking knob `bolt` rides
its slot along +Z only: still in the shots - the Uzi's knob does not reciprocate - hauled back its whole stroke in the
empty reload and partly in the inspect, home at every clip's end; the trigger pulled in the shots; both reloads take
the magazine straight down out of the grip and put it back), the iron sights at ADS, eye distance, framing, the
triangle budget and every attachment on every mount (check_attach)."""
import json
import os
from pathlib import Path

import numpy as np

import check_rifle as cr
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, check_sights, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "top_cover", "rail", "barrel", "barrel_nut", "front_ears", "rear_ears", "handguard", "frame",
          "grip", "stock")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "trigger": "gun",
    "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
    "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "trigger", "sight_iron", "sight_rear", "muzzle_brake", "mag_standard",
                            "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm",
               "mount_underbarrel"}
MATERIALS = {"steel", "steel_dark", "barrel", "polymer", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = {"steel_stock", "polymer_grip", "barrel_knurl", "steel_dark_knurl"}   # pbr_apply.py (section 21)
REGIONS = {"steel": "body", "steel_stock": "stock", "steel_dark": "detail", "steel_dark_knurl": "detail",
           "barrel": "barrel", "barrel_knurl": "barrel", "polymer": "handguard", "polymer_grip": "grip",
           "mag": "magazine", "mag_base": "magazine", "brass": "none"}
SETS = {"steel": "uzi/parkerized", "steel_stock": "uzi/parkerized", "steel_dark": "common/steel_dark",
        "steel_dark_knurl": "uzi/knurl", "barrel": "uzi/barrel", "barrel_knurl": "uzi/knurl",
        "polymer": "uzi/polymer", "polymer_grip": "uzi/polymer", "mag": "uzi/mag", "mag_base": "uzi/mag",
        "brass": "common/brass"}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.45, 0.8), "fire": (0.06, 0.10), "fire_last": (0.06, 0.10),
             "inspect": (3.5, 4.5), "walk": (0.6, 1.6), "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move", "bolt_back", "bolt_forward"}}
BUDGET = BUDGETS["long_gun"]
KNOB_STROKE = 0.085                # the cocking knob's haul in reload_empty (the slot allows 0.090)
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")


def claims(path, names):
    return Path(path).stem == "uzi"


def _reload_times():
    try:
        with open(GUNS_JSON, encoding="utf-8") as f:
            r = json.load(f)["guns"]["uzi"]["reload"]
        return float(r["tactical"]), float(r["empty"])
    except (OSError, KeyError, ValueError):
        return 2.1, 2.6


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
    applied = all("pbr" in (m.get("extras") or {}) for m in mats.values())
    for nm, m in mats.items():          # section 21: regions and sets (once pbr_apply.py has run)
        ex = m.get("extras") or {}
        if applied:
            r.check(ex.get("region") == REGIONS.get(nm), f"{label}: material {nm} region {ex.get('region')}, "
                                                         f"expected {REGIONS.get(nm)}")
            r.check(ex.get("pbr") == f"textures/pbr/{SETS.get(nm)}", f"{label}: material {nm} set {ex.get('pbr')}")
    if applied:
        r.check(SPLIT_MATERIALS <= set(mats), f"{label}: split materials missing {SPLIT_MATERIALS - set(mats)}")
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def check_clips(g, r, label):
    tactical, empty = _reload_times()
    durations = dict(DURATIONS, reload=(tactical - 1e-3, tactical + 1e-3), reload_empty=(empty - 1e-3, empty + 1e-3))
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names["camera"]
    for nm, (lo, hi) in durations.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-4 <= dur <= hi + 1e-4, f"{label}: {nm} length {dur:.3f} outside [{lo:.3f}, {hi:.3f}]")
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
                r.check(path == "rotation", f"{label}: {nm} animates camera {path}")
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
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    check_mechanism(g, r, label, anims)


def check_mechanism(g, r, label, anims):
    bi = g.names["bolt"]
    rest = np.asarray(g.nodes[bi].get("translation", [0, 0, 0]), float)
    for nm, a in anims.items():
        c = _channel(g, a, "bolt", "translation")
        if nm in ("fire", "fire_last"):
            r.check(c is None or np.abs(c[1] - rest).max() < 1e-6,
                    f"{label}: {nm} moves the cocking knob (it does not reciprocate on the Uzi)")
            t = _channel(g, a, "trigger", "rotation")
            r.check(t is not None, f"{label}: {nm} does not pull the trigger")
            continue
        if c is None:
            r.check(nm not in ("reload_empty", "inspect"), f"{label}: {nm} never moves the cocking knob")
            continue
        dz = c[1][:, 2] - rest[2]
        side = np.abs(c[1][:, :2] - rest[:2]).max()
        r.check(side < 1e-6, f"{label}: {nm} moves the knob off its slot (x/y)")
        r.check(dz.min() > -1e-4, f"{label}: {nm} pushes the knob forward of its rest")
        r.check(dz.max() <= 0.0905, f"{label}: {nm} pulls the knob {dz.max():.3f} beyond its slot")
        r.check(abs(dz[-1]) < 1e-4, f"{label}: {nm} ends with the knob back")
        if nm == "reload_empty":
            r.check(dz.max() >= KNOB_STROKE, f"{label}: {nm} knob stroke {dz.max():.3f} < {KNOB_STROKE}")
        r.note(f"{nm}: cocking knob travel {dz.max() * 1000:.1f} mm")
    mi = g.names["mag"]
    rest_m = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        a = anims.get(nm)
        if a is None:
            continue
        t = _channel(g, a, "mag", "translation")
        drop = 0.0 if t is None else float((rest_m[1] - t[1][:, 1]).max())
        r.check(drop > 0.10, f"{label}: {nm} does not take the magazine out of the grip ({drop:.3f} m)")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - rest_m) < 1e-4, f"{label}: {nm} does not seat the magazine again")
            # it leaves straight down the well: while still in the grip (above its floor) it may not move sideways
            inside = (rest_m[1] - t[1][:, 1]) < 0.08
            lateral = np.abs(t[1][inside][:, [0, 2]] - rest_m[[0, 2]]).max() if inside.any() else 0.0
            r.check(lateral < 0.004, f"{label}: {nm} moves the magazine {lateral * 1000:.1f} mm sideways in the grip")
        s = _channel(g, a, "mag", "scale")
        r.check(s is not None and np.isclose(s[1][:, 0].min(), 0.0), f"{label}: {nm} never hides the magazine")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_sights(g, r, label)
    cr.check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    saved_free = cr.FREE
    # the stock, grip, frame, receiver (its rear end), rear sight and magazines may run off the bottom-right edge
    cr.FREE = ("stock", "grip", "frame", "receiver", "top_cover", "rail", "rear_ears", "sight_rear", "mag_standard",
               "mag_extended", "tactical_stock", "heavy_stock", "wire_stock", "charm_grass")
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved_free
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= BUDGET, f"{label}: {tris} triangles over the budget of {BUDGET}")
    r.note(f"{label} triangles (weapon file, excluding fx_shell): {tris} (budget {BUDGET})")
