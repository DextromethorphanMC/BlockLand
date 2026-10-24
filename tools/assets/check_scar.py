"""SCAR-H GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims models/gun/scar.glb.
Nodes and parents, materials, regions and texture sets, clips (lengths against guns.json, loops, events, the camera),
the mechanism (the carrier cycling in `fire`, held open on the bolt catch at the end of `fire_last` and at the start of
`reload_empty`, slammed home by the catch there and run back by the charging handle in `inspect`, never off its rail),
the magazine leaving and returning (STEP scale) in both reloads, the iron sights at ADS, eye distance, framing, the
whole-file triangle budget and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
import scar_model as sm
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       check_sights)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "lower", "barrel", "pistol_grip", "stock")
MOVING = ("bolt", "bolt_catch", "selector", "trigger", "sight_iron", "sight_rear", "muzzle_brake")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC + MOVING}, "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
    "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | set(MOVING) | {"mag_standard", "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm",
               "mount_underbarrel"}
REGIONS = {"upper": "body", "handguard": "handguard", "lower": "body", "grip": "grip", "stock": "stock",
           "stock_pad": "none", "barrel": "barrel", "steel": "detail", "steel_dark": "detail", "mag": "magazine",
           "mag_base": "magazine", "brass": "none"}
SETS = {"upper": "scar/anodized", "handguard": "scar/anodized", "lower": "scar/polymer", "grip": "scar/grip",
        "stock": "scar/polymer", "stock_pad": "common/rubber", "barrel": "scar/barrel", "steel": "scar/barrel",
        "steel_dark": "common/steel_dark", "mag": "scar/mag", "mag_base": "scar/mag", "brass": "common/brass"}
GOLD = ("steel", "steel_dark", "barrel")              # the gold skin tints these by name (section 40)
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.6, 0.8), "fire": (0.09, 0.11), "fire_last": (0.09, 0.11),
             "inspect": (3.5, 4.5), "walk": (0.6, 1.6), "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_forward"},
                   "inspect": {"inspect_move", "bolt_back", "bolt_forward"}}
BUDGET = BUDGETS["long_gun"]
HOLD_TOL = 0.002


def claims(path, names):
    return os.path.basename(path) == "scar.glb"


def _gun_data():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "guns",
                           "guns.json"), encoding="utf-8") as f:
        return json.load(f)["guns"]["scar"]


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
    data = _gun_data()
    for slot in data["slots"].values():                 # every slot's mount and every node a slot hides
        for nm in ([slot["node"]] if slot.get("node") else []) + list(slot.get("hides", [])):
            r.check(nm in g.names, f"{label}: guns.json names node {nm}, missing in the file")
    for mag in ("mag_standard", "mag_extended"):
        r.check(mag in g.names, f"{label}: magazine node {mag} missing")
    r.check(data.get("slideLock", {}).get("node") in g.names, f"{label}: slideLock node missing")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(REGIONS), f"{label}: materials {sorted(mats)} differ from {sorted(REGIONS)}")
    for nm, m in mats.items():
        ex = m.get("extras") or {}
        if "region" in ex:
            r.check(ex["region"] == REGIONS.get(nm), f"{label}: material {nm} region {ex['region']}")
        if "pbr" in ex:
            r.check(ex["pbr"] == "textures/pbr/" + SETS.get(nm, "?"), f"{label}: material {nm} set {ex['pbr']}")
            base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main",
                                "resources", "assets", "blockland", *ex["pbr"].split("/"))
            for suf in ("", "_n", "_s"):
                r.check(os.path.exists(base + suf + ".png"), f"{label}: {ex['pbr']}{suf}.png missing")
        r.check(m.get("alphaMode", "OPAQUE") == "OPAQUE" and not m.get("doubleSided"),
                f"{label}: material {nm} must be opaque and single sided")
    for nm in GOLD:
        r.check(nm in mats, f"{label}: the gold skin's material {nm} is missing")
    applied = all("pbr" in (m.get("extras") or {}) for m in mats.values())
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
    reload = _gun_data()["reload"]
    durations = dict(DURATIONS, reload=(reload["tactical"] - 1e-3, reload["tactical"] + 1e-3),
                     reload_empty=(reload["empty"] - 1e-3, reload["empty"] + 1e-3))
    from check_glb import CAM_ROLL, CAM_TURN, euler_deg
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
                r.check(np.allclose(vs[-1], 1.0), f"{label}: {nm} ends without the magazine")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))


def _angle_x(q, rest):
    x, y, z, w = np.asarray(rest, float)
    a, b = np.array([-x, -y, -z, w]), q
    dx = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
    dw = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    return float(np.degrees(2 * np.arctan2(dx, dw)))


def check_mechanism(g, r, label):
    """The carrier rides its rail (+Z only): a full cycle in fire, held open at the end of fire_last, open at the
    start of reload_empty and home at its end, a press-check in inspect; slideLock's offset is the hold-open."""
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
    lock = np.asarray(_gun_data().get("slideLock", {}).get("offset", [0, 0, 0]), float)
    for nm in ("fire", "fire_last", "reload_empty", "inspect"):
        c = _channel(g, anims[nm], "bolt", "translation") if nm in anims else None
        if not r.check(c is not None, f"{label}: {nm} does not move the bolt"):
            continue
        z = c[1][:, 2] - rest[2]
        r.check(np.abs(c[1][:, :2] - rest[:2]).max() < 1e-6, f"{label}: {nm} moves the bolt off its rail")
        r.check(z.min() > -1e-4 and z.max() <= sm.BOLT_TRAVEL + 1e-4, f"{label}: {nm} bolt outside its stroke")
        if nm == "fire":
            r.check(z.max() >= sm.BOLT_TRAVEL - 1e-3 and abs(z[-1]) < 1e-4 and abs(z[0]) < 1e-4,
                    f"{label}: fire does not cycle the carrier fully and home")
        if nm == "fire_last":
            r.check(z.max() >= sm.BOLT_TRAVEL - 1e-3 and abs(z[-1] - sm.BOLT_HOLD) < HOLD_TOL,
                    f"{label}: fire_last does not end held open ({z[-1]:.4f})")
        if nm == "reload_empty":
            r.check(abs(z[0] - sm.BOLT_HOLD) < HOLD_TOL and abs(z[-1]) < 1e-4,
                    f"{label}: reload_empty must start held open and end home ({z[0]:.4f} .. {z[-1]:.4f})")
        if nm == "inspect":
            r.check(z.max() > 0.02 and abs(z[0]) < 1e-4 and abs(z[-1]) < 1e-4, f"{label}: inspect press-check")
        r.note(f"{nm}: bolt {z.min() * 1000:.1f} .. {z.max() * 1000:.1f} mm, ends at {z[-1] * 1000:.1f} mm")
    if abs(lock[2] - sm.BOLT_HOLD) < HOLD_TOL and np.allclose(lock[:2], 0):
        r.note(f"slideLock offset {lock.tolist()} holds the carrier open like fire_last's end")
    else:                       # data owned by the coordinator: reported, the file itself is fine
        r.note(f"NOTE guns.json slideLock.offset {lock.tolist()} should be [0.0, 0.0, {sm.BOLT_HOLD}] (the hold-open "
               f"of fire_last), otherwise the empty gun shows the carrier closed")
    rest_c = g.nodes[g.names["bolt_catch"]].get("rotation", [0, 0, 0, 1])
    for nm in ("fire_last", "reload_empty"):
        c = _channel(g, anims[nm], "bolt_catch", "rotation")
        if r.check(c is not None, f"{label}: {nm} does not move the bolt catch"):
            ang = np.array([_angle_x(q, rest_c) for q in c[1]])
            r.check(np.abs(ang).max() < 12.0, f"{label}: {nm} swings the bolt catch too far")
            end = ang[-1] if nm == "fire_last" else ang[0]
            r.check(end > 3.0, f"{label}: {nm} catch not raised while the carrier is held")
    mi = g.names["mag"]
    rest_m = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        t = _channel(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float((rest_m[1] - t[1][:, 1]).max())
        r.check(drop > 0.06, f"{label}: {nm} does not take the magazine out ({drop:.3f} m)")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - rest_m) < 1e-4, f"{label}: {nm} does not seat the magazine at the end")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_mechanism(g, r, label)
    check_sights(g, r, label)
    cr.check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    saved_free = cr.FREE
    cr.FREE = ("stock", "pistol_grip", "lower", "receiver", "selector", "sight_rear", "mag_standard", "mag_extended",
               "tactical_stock", "heavy_stock", "wire_stock", "charm_grass")
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved_free
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= BUDGET, f"{label}: {tris} triangles (whole file) over the budget of {BUDGET}")
    r.note(f"{label} triangles (whole file: both magazines and fx_shell): {tris} (budget {BUDGET})")
