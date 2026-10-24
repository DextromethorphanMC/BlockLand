"""STEN Mk II GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims sten.glb.
Nodes and parents, materials and regions (texture sets), clips (lengths against guns.json, events, the open bolt: held
back at rest, forward and back again in `fire`, left forward by `fire_last` -- the slide-lock pose -- racked back in
`reload_empty`, only turned into the safety notch and eased in `inspect`), the magazine drawn out sideways (-X) and
seated again in both reloads, STEP magazine scale, the iron sights at ADS, eye distance, framing, the triangle budget
and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
import sten_model as sm
from check_glb import check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes, \
    check_sights
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "barrel", "trigger_housing", "stock")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "trigger": "gun",
    "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun", "mag": "gun",
    "mag_standard": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "trigger", "sight_iron", "sight_rear", "muzzle_brake", "mag_standard", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm"}
FORBIDDEN = ("mag_extended",) + cr.REMOVED_NODES          # one magazine only (guns.json sten_32)
MATERIALS = {"steel", "barrel", "steel_dark", "mag", "mag_base", "brass", "wood"}
SPLIT_MATERIALS = {"steel_stock", "steel_grip"}
REGIONS = {"steel": "body", "steel_stock": "stock", "steel_grip": "grip", "wood": "grip", "barrel": "barrel",
           "steel_dark": "detail", "mag": "magazine", "mag_base": "magazine", "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.55, 0.9), "fire": (0.09, 0.11), "fire_last": (0.09, 0.13),
             "reload": (2.4, 2.4), "reload_empty": (2.9, 2.9), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
BUDGET = BUDGETS["long_gun"]
TRAVEL = sm.BOLT_TRAVEL


def claims(path, names):
    return os.path.basename(path) == "sten.glb"


def _guns_json():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "guns",
                           "guns.json"), encoding="utf-8") as f:
        return json.load(f)["guns"]["sten"]


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
    for nm in FORBIDDEN:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the weapon file")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    data = _guns_json()
    for slot, s in data["slots"].items():                # every slot's mount and hidden parts exist
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names and "mesh" in g.nodes[g.names[h]], f"{label}: slot {slot} hides {h}: no such mesh")
    r.check(data["slideLock"]["node"] in g.names, f"{label}: slide-lock node {data['slideLock']['node']} missing")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in MATERIALS or nm in SPLIT_MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    applied = True
    for nm, m in mats.items():
        ex = m.get("extras") or {}
        if "region" in ex:
            r.check(ex["region"] == REGIONS.get(nm), f"{label}: material {nm} region {ex['region']}, "
                                                     f"expected {REGIONS.get(nm)}")
        applied &= "pbr" in ex
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def _angle_z(q):
    x, y, z, w = q
    return float(np.degrees(2 * np.arctan2(z, w)))


def check_mechanism(g, r, label):
    """The open bolt and the magazine through the clips."""
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    bi = g.names["bolt"]
    rest = np.asarray(g.nodes[bi].get("translation", [0, 0, 0]), float)
    data = _guns_json()
    lock = np.asarray(data["slideLock"]["offset"], float)
    for nm, a in anims.items():
        c = _channel(g, a, "bolt", "translation")
        dz = None
        if c is not None:
            off = c[1] - rest
            r.check(np.abs(off[:, :2]).max() < 1e-6, f"{label}: {nm} moves the bolt off the bore")
            dz = off[:, 2]
            r.check(dz.min() > -TRAVEL - 1e-4 and dz.max() < sm.OVERTRAVEL + 5e-4,
                    f"{label}: {nm} runs the bolt out of its slot ({dz.min():.4f} .. {dz.max():.4f})")
        rot = _channel(g, a, "bolt", "rotation")
        if rot is not None:
            ang = np.array([_angle_z(q) for q in rot[1]])
            r.check(nm == "inspect", f"{label}: {nm} turns the bolt (only the inspect's safety flick may)")
            r.check(ang.min() > -0.5 and ang.max() < sm.SAFETY_ROT + 1.5 and abs(ang[-1]) < 0.05,
                    f"{label}: {nm} bolt turn {ang.min():.1f}..{ang.max():.1f} deg, ends at {ang[-1]:.2f}")
            if nm == "inspect":
                r.note(f"inspect: handle into the safety notch {ang.max():.1f} deg")
        if nm == "fire":
            ok = dz is not None and dz.min() < -TRAVEL + 1e-3 and abs(dz[0]) < 1e-5 and abs(dz[-1]) < 1e-5
            r.check(ok, f"{label}: fire must run the bolt forward ({-TRAVEL:.3f}) from rest and back onto the sear")
        if nm == "fire_last":
            ok = dz is not None and abs(dz[0]) < 1e-5 and abs(dz[-1] + TRAVEL) < 1e-4
            r.check(ok, f"{label}: fire_last must start cocked and leave the bolt forward")
            if not (abs(lock[2] + TRAVEL) < 1e-4 and np.abs(lock[:2]).max() < 1e-9):   # data, applied separately
                r.note(f"{label}: NOTE guns.json slideLock.offset is {lock.tolist()}; the model wants "
                       f"[0, 0, {-TRAVEL}] (the bolt stays forward while empty, as fire_last leaves it)")
        if nm == "reload_empty":
            ok = dz is not None and abs(dz[0] + TRAVEL) < 1e-4 and abs(dz[-1]) < 1e-5 and dz.max() > 0.5 * sm.OVERTRAVEL
            r.check(ok, f"{label}: reload_empty must start with the bolt forward and rack it back onto the sear")
        if nm in ("reload", "idle", "walk", "sprint", "tac_sprint", "draw"):
            r.check(dz is None or np.abs(dz).max() < 1e-5, f"{label}: {nm} moves the bolt")
        if dz is not None:
            r.note(f"{nm}: bolt {dz.min() * 1000:+.1f} .. {dz.max() * 1000:+.1f} mm (ends {dz[-1] * 1000:+.1f})")
    mi = g.names["mag"]
    mrest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        t = _channel(g, anims[nm], "mag", "translation")
        out = 0.0 if t is None else float((mrest[0] - t[1][:, 0]).max())
        r.check(out > 0.07, f"{label}: {nm} does not draw the magazine out sideways ({out:.3f} m)")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - mrest) < 1e-4, f"{label}: {nm} does not seat the magazine again")
        s = _channel(g, anims[nm], "mag", "scale")
        r.check(s is not None and np.allclose(s[1][-1], 1.0), f"{label}: {nm} must hide the old and show the new "
                                                               f"magazine (STEP scale) and end with it shown")


def check_reload_lengths(g, r, label):
    data = _guns_json()["reload"]
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    for clip, key in (("reload", "tactical"), ("reload_empty", "empty")):
        if clip in anims:
            d = g.duration(anims[clip])
            r.check(abs(d - data[key]) < 0.02, f"{label}: {clip} is {d:.2f} s, guns.json reload.{key} {data[key]}")


def _clips(g, r, label):
    saved = cr.DURATIONS, cr.EVENT_NAMES, cr.REQUIRED_EVENTS
    cr.DURATIONS, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = DURATIONS, EVENT_NAMES, REQUIRED_EVENTS
    saved_bolt = cr._bolt_z
    cr._bolt_z = lambda g_, a_: None                     # the open bolt has its own rules (check_mechanism)
    try:
        _check_clips_no_bolt(g, r, label)
    finally:
        cr.DURATIONS, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = saved
        cr._bolt_z = saved_bolt
    check_mechanism(g, r, label)
    check_reload_lengths(g, r, label)


def _check_clips_no_bolt(g, r, label):
    """check_rifle.check_clips without its closed-bolt rules (its bolt loop is skipped by a None travel)."""
    errors_before = len(r.errors)
    cr.check_clips(g, r, label)
    # check_rifle reports "does not move the bolt" when _bolt_z is None: drop those (the open bolt is checked above)
    keep = [e for e in r.errors[errors_before:] if "does not move the bolt" not in e]
    del r.errors[errors_before:]
    r.errors.extend(keep)


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    _clips(g, r, label)
    check_sights(g, r, label)
    cr.check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    saved_free = cr.FREE
    cr.FREE = ("stock", "trigger_housing", "receiver", "mag_standard", "tactical_stock", "heavy_stock", "wire_stock",
               "charm_grass")
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
