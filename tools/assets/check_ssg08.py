"""SSG 08 GLB checks (VIEWMODEL_SPEC sections 23 and 40), used by check_glb.py (CHECK_MODULES): claims ssg08.glb.
Nodes and parents (the bolt split into `bolt` / `bolt_handle`, no iron sights, no laser or stock mount), materials and
regions (the gold skin's steel / steel_dark / barrel names, texture sets applied), the data contract in guns.json
(slide-lock node, eject event, reload lengths = the clips), clips (lengths, events and their order, camera limits,
loops), the bolt (slides along the bore and turns about it only, only slides while unlocked, cycles fully in fire and
reload_empty with bolt_back at the rear stop, untouched in fire_last, closed at every clip end), the magazine leaving
and returning in both reloads, the mounts parallel to the bore, eye distance, framing with the scopes, the hip muzzle
out of the crosshair area, the triangle budget and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_sniper as cs
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, check_sights, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "handguard", "barrel", "stock", "grip")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "bolt_handle": "bolt",
    "trigger": "gun", "muzzle_brake": "gun", "mag": "gun", "mag_standard": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None, "mount_optic": "gun", "mount_barrel": "gun", "mount_charm": "gun", "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "bolt_handle", "trigger", "muzzle_brake", "mag_standard", "fx_shell"}
EMPTY_NODES = {"viewmodel", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore", "camera",
               "mount_optic", "mount_barrel", "mount_charm", "mount_underbarrel"}
ABSENT_NODES = ("sight_reddot", "reddot_reticle", "aim_reddot", "laser", "laser_emitter", "aim_iron", "sight_iron",
                "sight_rear", "mount_laser", "mount_stock", "mag_extended")
REGIONS = {"steel": "body", "handguard": "handguard", "stock": "stock", "cheek": "stock", "grip": "grip",
           "pad": "none", "barrel": "barrel", "steel_dark": "detail", "mag": "magazine", "mag_base": "magazine",
           "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.7, 1.0), "fire": (0.9, 1.3), "fire_last": (0.2, 0.4),
             "reload": (2.4, 3.2), "reload_empty": (3.0, 3.8), "inspect": (3.3, 4.3), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
BOLT_EVENTS = ("bolt_back", "bolt_forward")
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "inspect_move", "equip"} | set(BOLT_EVENTS)
REQUIRED_EVENTS = {"draw": {"equip"}, "fire": set(BOLT_EVENTS), "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in"} | set(BOLT_EVENTS),
                   "inspect": {"inspect_move"}}
MIN_TRAVEL, MIN_LIFT = 0.080, 50.0
BUDGET = BUDGETS["long_gun"]


def claims(path, names):
    return os.path.basename(path)[:-4] == "ssg08"


def _guns_json():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "..", "..", "mod", "src", "main", "resources", "data", "blockland", "guns",
                           "guns.json"), encoding="utf-8") as f:
        return json.load(f)


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
    for nm in ABSENT_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the file (sections 19, 23, 40)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    W = g.world_matrices({})
    o, m = W[g.names["bolt_handle"]][:3, 3], W[g.names["muzzle"]]
    bore = -m[:3, 2] / np.linalg.norm(m[:3, 2])
    off = (o - m[:3, 3]) - bore * np.dot(o - m[:3, 3], bore)
    r.check(np.linalg.norm(off) < 0.0015, f"{label}: bolt_handle origin {np.linalg.norm(off) * 1000:.1f} mm off the bore")
    # the underbarrel mount ahead of the support hand (section 35) and below the bore
    gi = np.linalg.inv(W[g.names["gun"]])
    ub = (gi @ W[g.names["mount_underbarrel"]])[:3, 3]
    hand = (gi @ W[g.names["arm_L_hand"]])[:3, 3]
    r.check(ub[2] < hand[2] - 0.10, f"{label}: mount_underbarrel not well ahead of the support hand")
    r.note(f"mount_underbarrel {ub.round(4)} (gun space), support wrist {hand.round(3)}")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in REGIONS, f"{label}: unexpected material {nm}")
    for nm in set(REGIONS) - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    for nm, m in mats.items():
        region = (m.get("extras") or {}).get("region")
        r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
    unset = [nm for nm, m in mats.items() if nm != "brass" and "pbr" not in (m.get("extras") or {})]
    r.check(not unset, f"{label}: texture sets not applied to {unset} (build_all.py ssg08 applies them)")
    for nm in ("steel", "steel_dark", "barrel"):                 # the gold skin's tints
        r.check(nm in mats, f"{label}: the gold skin tints {nm}: material missing")
    r.note("regions: " + ", ".join(f"{nm}={(m.get('extras') or {}).get('region')}" for nm, m in mats.items()))


def check_data(g, r, label):
    """guns.json: slide-lock node, eject event, magazine node, reload lengths = the clips, the slots' mounts."""
    data = _guns_json()
    gun = data["guns"]["ssg08"]
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    r.check(gun.get("slideLock", {}).get("node") in g.names, f"{label}: slideLock node {gun.get('slideLock')} missing")
    r.check(gun.get("boltAction") is True, f"{label}: guns.json ssg08 is not boltAction")
    eject = gun.get("ejectEvent")
    for nm in ("fire", "reload_empty"):
        ev = {e["name"] for e in anims[nm].get("extras", {}).get("events", [])}
        r.check(eject in ev, f"{label}: {nm} lacks the ejectEvent {eject}")
    for mag in gun.get("magazines", []):
        r.check(data["magazines"][mag]["node"] in g.names, f"{label}: magazine node of {mag} missing")
    for key, clip in (("tactical", "reload"), ("empty", "reload_empty")):
        want, have = gun["reload"][key], g.duration(anims[clip])
        r.check(abs(want - have) < 0.02, f"{label}: {clip} is {have:.2f} s, guns.json reload.{key} {want}")
    for slot, s in gun.get("slots", {}).items():
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names, f"{label}: slot {slot} hides {h}, not in the file")
    dur = g.duration(anims["fire"])
    ready = 60.0 / gun["rpm"]
    r.note(f"data: slideLock {gun['slideLock']['node']}, ejectEvent {eject}, reloads {gun['reload']}, "
           f"fire clip {dur:.2f} s (shot interval {ready:.2f} s)")


def _track(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64)
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
                r.check(path == "rotation", f"{label}: {nm} animates camera {path}")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond limits (max {np.abs(eul).max(0).round(2)})")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if g.nodes[node]["name"] == "mag" and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} mag scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} mag scale not 0/1")
                r.check(np.allclose(vs[-1], 1.0), f"{label}: {nm} ends with the magazine hidden")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            if set(BOLT_EVENTS) <= have:
                at = {e["name"]: e["time"] for e in ev if e["name"] in BOLT_EVENTS}
                r.check(at["bolt_back"] < at["bolt_forward"], f"{label}: {nm} bolt events out of order")
            if {"mag_release", "mag_out", "mag_in"} <= have:
                at = {e["name"]: e["time"] for e in ev}
                r.check(at["mag_release"] < at["mag_out"] < at["mag_in"], f"{label}: {nm} magazine events out of order")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))


def check_bolt(g, r, label):
    """The bolt slides along the bore and turns about it only, ends closed; fire / reload_empty cycle it fully with
    bolt_back at the rear stop and bolt_forward when it is home; fire_last leaves it alone; it only slides unlocked."""
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
    for nm, a in anims.items():
        tz = _track(g, a, "bolt", "translation")
        tq = _track(g, a, "bolt_handle", "rotation")
        if tz is None and tq is None:
            if nm in ("fire", "reload_empty"):
                r.check(False, f"{label}: {nm} never works the bolt")
            continue
        z = np.zeros(1) if tz is None else tz[1][:, 2] - rest[2]
        side = 0.0 if tz is None else float(np.abs(tz[1][:, :2] - rest[:2]).max())
        lift = np.zeros(1) if tq is None else np.degrees(2 * np.arctan2(tq[1][:, 2], tq[1][:, 3]))
        drift = 0.0 if tq is None else float(np.abs(tq[1][:, :2]).max())
        r.check(side < 1e-6, f"{label}: {nm} moves the bolt off the bore (x/y)")
        r.check(drift < 1e-6, f"{label}: {nm} turns the bolt handle about another axis than the bore")
        r.check(z.min() > -1e-4, f"{label}: {nm} pushes the bolt forward of its rest")
        r.check(abs(z[-1]) < 1e-4 and abs(lift[-1]) < 0.05, f"{label}: {nm} ends with the bolt open")
        r.check(lift.max() < 70.0 and lift.min() > -0.5, f"{label}: {nm} turns the handle out of its range")
        if nm in ("fire", "reload_empty"):
            r.check(z.max() >= MIN_TRAVEL, f"{label}: {nm} bolt travel {z.max():.3f} < {MIN_TRAVEL}")
            r.check(lift.max() >= MIN_LIFT, f"{label}: {nm} handle lifted only {lift.max():.0f} deg")
            ev = {e["name"]: e["time"] for e in a.get("extras", {}).get("events", [])}
            for name, want in (("bolt_back", "rear"), ("bolt_forward", "home")):
                if name in ev:
                    s = g.sample(a, ev[name]).get(g.names["bolt"], {}).get("translation")
                    zz = (s[2] - rest[2]) if s is not None else 0.0
                    ok = zz > 0.85 * z.max() if want == "rear" else zz < 0.15 * z.max()
                    r.check(ok, f"{label}: {nm} {name} at {ev[name]:.3f} s with the bolt {zz * 1000:.0f} mm back")
        if nm == "fire_last":
            r.check(z.max() < 1e-6 and lift.max() < 0.05, f"{label}: fire_last must not cycle the bolt (section 23)")
        r.note(f"{nm}: bolt travel {z.max() * 1000:.1f} mm, handle lift {lift.max():.0f} deg")
    for a in g.json.get("animations", []):
        bad = 0
        for t in np.arange(0, g.duration(a) + 1e-9, 1 / 120):
            s = g.sample(a, t)
            b = s.get(g.names["bolt"], {}).get("translation")
            q = s.get(g.names["bolt_handle"], {}).get("rotation")
            if b is None:
                continue
            lift = 0.0 if q is None else np.degrees(2 * np.arctan2(q[2], q[3]))
            if b[2] - rest[2] > 0.003 and lift < 0.9 * MIN_LIFT:
                bad += 1
        r.check(bad == 0, f"{label}: {a['name']} slides the bolt while the handle is down ({bad} samples)")


def check_magazine(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest = np.asarray(g.nodes[g.names["mag"]].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        t = _track(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float((rest[1] - t[1][:, 1]).max())
        r.check(drop > 0.05, f"{label}: {nm} does not take the magazine out ({drop:.3f} m)")
        r.check(t is not None and np.linalg.norm(t[1][-1] - rest) < 1e-4, f"{label}: {nm} does not seat the magazine")
    for nm in ("fire", "fire_last", "inspect", "idle", "draw", "walk"):
        r.check(_track(g, anims[nm], "mag", "translation") is None, f"{label}: {nm} moves the magazine")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_data(g, r, label)
    check_bolt(g, r, label)
    check_magazine(g, r, label)
    check_sights(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    cs.check_muzzle(g, r, label)
    saved = cs.FREE
    cs.FREE = ("stock", "grip", "receiver", "bolt", "bolt_handle", "trigger", "mag_standard", "charm_grass")
    try:
        cs.check_framing(path, r, label)
    finally:
        cs.FREE = saved
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    whole = sum(len(g.accessor(p["indices"])) // 3 for m in g.json["meshes"] for p in m["primitives"])
    r.check(whole <= BUDGET, f"{label}: {whole} triangles in the file over the budget of {BUDGET}")
    r.note(f"{label} triangles (weapon, excluding fx_shell): {tris}; whole file {whole} (budget {BUDGET})")
