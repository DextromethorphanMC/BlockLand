"""Calico M950 GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims calico.glb.
Nodes and parents, materials, regions and texture sets, the gun's data (guns.json: magazine nodes, the slide-lock
node, slot mounts and hides, reload lengths), clips (lengths, loops, camera, events, the cocking handle riding the bolt
and closing again, the magazine's STEP scale), the magazine leaving and coming back in both reloads, the iron sights
at ADS, eye distance, framing, the triangle budget (pistol) and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       check_sights)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "forend", "barrel", "lower", "grip")
MOUNTS = ("mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm", "mount_underbarrel")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "trigger": "gun",
    "selector": "gun", "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None, **{m: "gun" for m in MOUNTS},
}
MESH_NODES = set(STATIC) | {"bolt", "trigger", "selector", "sight_iron", "sight_rear", "muzzle_brake", "mag_standard",
                            "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", *MOUNTS}
MATERIALS = {"steel", "steel_dark", "barrel", "polymer", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = {"polymer_forend"}               # pbr_apply.py (section 21)
REGIONS = {"steel": "body", "steel_dark": "detail", "barrel": "barrel", "polymer": "grip",
           "polymer_forend": "handguard", "mag": "magazine", "mag_base": "magazine", "brass": "none"}
TINTED = ("steel", "steel_dark", "barrel")         # the gold skin tints these by name
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.45, 0.75), "fire": (0.10, 0.16), "fire_last": (0.10, 0.16),
             "reload": (2.0, 3.2), "reload_empty": (2.4, 3.8), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move", "bolt_back", "bolt_forward"}}
MIN_TRAVEL = 0.030                # the cocking handle's stroke in fire and reload_empty
MIN_MAG_TRAVEL = 0.15             # the magazine leaves the gun in both reloads (drawn out of the bridge)
BUDGET = BUDGETS["pistol"]
# may run off the bottom / right edge at hip: the grip and lower with the hand on them, the magazines' rear caps,
# the stocks and the charm
FREE = ("grip", "lower", "receiver", "mag_standard", "mag_extended", "tactical_stock", "heavy_stock", "wire_stock",
        "charm_grass")
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == "calico"


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
    r.check(g.nodes[g.names["fx_shell"]].get("extras", {}).get("hidden") is True, f"{label}: fx_shell not hidden")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in MATERIALS or nm in SPLIT_MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    for nm in TINTED:
        r.check(nm in mats, f"{label}: the gold skin tints a material {nm}: missing")
    applied = all("pbr" in (m.get("extras") or {}) for m in mats.values())
    for nm, m in mats.items():
        ex = m.get("extras") or {}
        if "region" in ex:
            r.check(ex["region"] == REGIONS.get(nm), f"{label}: material {nm} region {ex['region']}, expected "
                                                     f"{REGIONS.get(nm)}")
        if applied:
            pbr = ex["pbr"]
            base = os.path.join(os.path.dirname(GUNS_JSON), "..", "..", "..", "assets", "blockland", *pbr.split("/"))
            r.check(os.path.exists(base + ".png") and os.path.exists(base + "_n.png") and os.path.exists(base + "_s.png"),
                    f"{label}: texture set {pbr} of {nm} missing")
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def check_data(g, r, label):
    """The gun's entry in guns.json against the file: magazine nodes, slide lock, mounts, hidden parts, reloads."""
    with open(GUNS_JSON, encoding="utf-8") as f:
        data = json.load(f)
    gun = data["guns"]["calico"]
    for mag in gun["magazines"]:
        node = data["magazines"][mag].get("node")
        r.check(node in g.names and g.nodes[g.parent[g.names[node]]]["name"] == "mag",
                f"{label}: magazine {mag} node {node} missing under mag")
    r.check(gun.get("slideLock", {}).get("node") in g.names, f"{label}: slideLock node missing")
    for slot, s in gun["slots"].items():
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names, f"{label}: slot {slot} hides {h}: no such node")
    anims = {a["name"]: g.duration(a) for a in g.json.get("animations", [])}
    for clip, key in (("reload", "tactical"), ("reload_empty", "empty")):
        want = gun["reload"][key]
        have = anims.get(clip, 0.0)
        r.check(abs(have - want) < 0.011, f"{label}: {clip} lasts {have:.3f} s, guns.json reload.{key} is {want}")
    r.note(f"data: reloads {gun['reload']}, draw {gun.get('drawTime')} s (clip {anims.get('draw', 0):.2f} s)")


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def check_magazine(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest = np.asarray(g.nodes[g.names["mag"]].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        a = anims.get(nm)
        if a is None:
            continue
        t = _channel(g, a, "mag", "translation")
        s = _channel(g, a, "mag", "scale")
        travel = 0.0 if t is None else float(np.linalg.norm(t[1] - rest, axis=1).max())
        r.check(travel > MIN_MAG_TRAVEL, f"{label}: {nm} does not take the magazine off ({travel:.3f} m)")
        r.check(t is not None and np.linalg.norm(t[1][-1] - rest) < 1e-4, f"{label}: {nm} leaves the magazine off")
        r.check(s is not None and np.any(s[1][:, 0] < 0.5) and s[1][-1, 0] > 0.5,
                f"{label}: {nm} must hide the old magazine (STEP scale 0) and show the new one")
        r.note(f"{nm}: magazine travels {travel * 1000:.0f} mm")
    for nm, a in anims.items():
        if nm not in ("reload", "reload_empty"):
            r.check(_channel(g, a, "mag", "translation") is None, f"{label}: {nm} moves the magazine")


def _clips(g, r, label):
    saved = cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS
    cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = (DURATIONS, MIN_TRAVEL, EVENT_NAMES,
                                                                            REQUIRED_EVENTS)
    try:
        cr.check_clips(g, r, label)
    finally:
        cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = saved
    check_magazine(g, r, label)


def check(path, arms, r):
    g = GLTF(path)
    label = os.path.basename(path)[:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_data(g, r, label)
    _clips(g, r, label)
    check_sights(g, r, label)
    cr.check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    saved_free = cr.FREE
    cr.FREE = FREE
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved_free
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.check(tris <= BUDGET, f"{label}: {tris} triangles over the budget of {BUDGET}")
    r.note(f"{label} triangles (whole file: both magazines and fx_shell): {tris} (budget {BUDGET})")
