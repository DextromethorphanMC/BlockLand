"""Revolver GLB checks (VIEWMODEL_SPEC section 49), used by check_glb.py (CHECK_MODULES): claims revolver.glb.
Nodes and parents, materials and regions, clips (lengths against guns.json, loops, camera limits, events and their
sound events: six `case_out` in the reload = the loader's capacity, the data's ejectEvent), the mechanism (the cylinder
indexed exactly one chamber per shot on a STEP channel and back at rest; the crane swung fully open in the reload and
the inspect and shut at the end of every clip; the ejector's stroke; the cartridges hidden from the punch to the loader
and shown at the end; the speed loader only in the reload, its rounds lined up with the chambers when they go in), the
iron sights at ADS, eye distance, framing, the triangle budget and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, check_sights, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("frame", "barrel", "grip", "sight_iron", "sight_rear")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "hammer": "gun", "trigger": "gun",
    "latch": "gun", "crane": "gun", "cylinder": "crane", "ejector": "cylinder", "rounds": "ejector",
    "ejection": "cylinder", "muzzle": "gun", "aim_iron": "gun", "mag": "gun", "mag_standard": "mag",
    "loader_rounds": "mag", "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L",
    "camera": "viewmodel", "fx_shell": None, "mount_optic": "gun", "mount_charm": "gun",
}
MESH_NODES = set(STATIC) | {"hammer", "trigger", "latch", "crane", "cylinder", "ejector", "rounds", "mag_standard",
                            "loader_rounds", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_charm"}
ABSENT = ("slide", "mount_laser", "mount_barrel", "mount_stock", "mount_underbarrel", "mag_extended")
REGIONS = {"steel": "body", "barrel": "barrel", "grip": "grip", "grip_checker": "grip", "steel_dark": "detail", "bore": "none",
           "insert": "none", "brass": "none", "bullet": "none", "loader": "magazine"}
NO_SET = {"bore", "insert"}             # flat colours (no texture set)
CLIPS = ("idle", "draw", "fire", "fire_ads", "reload", "inspect", "walk", "sprint", "tac_sprint")
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
LENGTHS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.25, 0.35), "fire_ads": (0.25, 0.35),
           "inspect": (3.0, 4.2), "walk": (0.6, 1.6), "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"equip", "inspect_move", "revolver_latch", "cylinder_open", "ejector", "case_out", "speedloader",
               "speedloader_twist", "cylinder_close", "cylinder_spin"}
REQUIRED_EVENTS = {"draw": {"equip"},
                   "reload": {"cylinder_open", "ejector", "case_out", "speedloader", "cylinder_close"},
                   "inspect": {"cylinder_open", "cylinder_spin", "cylinder_close"}}
INDEX = 60.0                            # one chamber per shot
CRANE_OPEN = 86.0
STROKE = 0.0220                         # the ejector's travel
BUDGET = BUDGETS["pistol"]
FREE = ("grip", "mag_standard", "loader_rounds", "charm_grass")     # the long grip may run off the bottom edge
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
GUNS_JSON = os.path.join(RES, "data", "blockland", "guns", "guns.json")
SOUNDS_JSON = os.path.join(RES, "assets", "blockland", "sounds.json")


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == "revolver"


def _data():
    with open(GUNS_JSON, encoding="utf-8") as f:
        guns = json.load(f)
    return guns["guns"]["revolver"], guns["magazines"]


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
    for nm in ABSENT + cr.REMOVED_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the file")
    for n in g.nodes:
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    r.check(np.allclose(g.nodes[g.names["mag"]].get("scale", [1, 1, 1]), 0.0), f"{label}: the speed loader shows at rest")
    gun, mags = _data()
    for slot, s in gun["slots"].items():
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names, f"{label}: slot {slot} hides {h}, which is not in the file")
    r.check(gun["slideLock"]["node"] in g.names, f"{label}: slideLock node {gun['slideLock']['node']} missing")
    for m in gun["magazines"]:
        r.check(mags[m]["node"] in g.names, f"{label}: magazine {m} node {mags[m]['node']} missing")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    r.check(set(mats) == set(REGIONS), f"{label}: materials {sorted(mats)}, expected {sorted(REGIONS)}")
    for nm, m in mats.items():
        extras = m.get("extras") or {}
        r.check(extras.get("region") == REGIONS.get(nm), f"{label}: material {nm} region {extras.get('region')}, "
                                                          f"expected {REGIONS.get(nm)}")
        if nm not in NO_SET:
            r.check("pbr" in extras, f"{label}: material {nm} has no texture set (pbr_apply)")
    r.note(f"materials: {', '.join(sorted(mats))}")


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def _angle(q, axis):
    """Signed angle (deg) of a rotation about one local axis (0 = X, 2 = Z) from identity."""
    q = np.asarray(q, float)
    return float(np.degrees(2 * np.arctan2(q[axis], q[3])))


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    gun, mags = _data()
    reload = gun["reload"]
    lengths = dict(LENGTHS, reload=(reload["tactical"] - 1e-3, reload["tactical"] + 1e-3))
    r.check(abs(reload["tactical"] - reload["empty"]) < 1e-6, f"{label}: a revolver reloads the same way loaded or empty")
    r.check(gun.get("ejectEvent") == "case_out", f"{label}: guns.json ejectEvent must be case_out (cases leave in the reload)")
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        sounds = json.load(f)
    for ev in sorted(EVENT_NAMES):
        r.check(f"gun.{ev}" in sounds, f"{label}: event {ev} has no sound event gun.{ev} in sounds.json")
    for key in ("fire", "dry"):
        r.check(gun["sounds"][key] in sounds, f"{label}: sound {gun['sounds'][key]} not in sounds.json")
    vm, cam = g.names["viewmodel"], g.names["camera"]
    for nm in CLIPS:
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        lo, hi = lengths[nm]
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
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond +-{CAM_TURN}/+-{CAM_ROLL} deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            names = [e["name"] for e in ev]
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= set(names), f"{label}: {nm} missing events {need - set(names)}")
            if nm == "reload":
                capacity = mags[gun["magazines"][0]]["capacity"]
                r.check(names.count("case_out") == capacity,
                        f"{label}: reload throws {names.count('case_out')} cases, the cylinder holds {capacity}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def _step_at(ch, u):
    return ch[1][max(0, int(np.searchsorted(ch[0], u, side="right")) - 1)]


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    for nm, a in anims.items():
        dur = g.duration(a)
        rot = _channel(g, a, "cylinder", "rotation")
        if nm.startswith("fire"):
            if not r.check(rot is not None, f"{label}: {nm} does not index the cylinder"):
                continue
            ang = np.array([_angle(q, 2) for q in rot[1]])
            r.check(rot[2] == "STEP", f"{label}: {nm} cylinder index must be a STEP channel")
            r.check(abs(abs(ang).max() - INDEX) < 1.5, f"{label}: {nm} indexes {abs(ang).max():.1f} deg, not {INDEX}")
            r.check(abs(ang[-1]) < 1e-3 and abs(ang[0]) < 1e-3, f"{label}: {nm} cylinder not back at rest at the end")
            settled = np.array([_angle(_step_at(rot, u), 2) for u in np.linspace(0.06, dur - 0.006, 12)])
            r.check(np.allclose(np.abs(settled), INDEX, atol=0.05),
                    f"{label}: {nm} cylinder not held one chamber on before the snap back")
            r.note(f"{nm}: cylinder indexed {abs(ang).max():.1f} deg (STEP, snaps back at {rot[0][-1]:.3f}s)")
            for part in ("hammer", "trigger"):
                ch = _channel(g, a, part, "rotation")
                if ch is not None:
                    r.check(abs(_angle(ch[1][-1], 0)) < 0.2, f"{label}: {nm} leaves the {part} off its rest")
        crane = _channel(g, a, "crane", "rotation")
        if nm in ("reload", "inspect"):
            if r.check(crane is not None, f"{label}: {nm} never swings the cylinder out"):
                ang = np.array([_angle(q, 2) for q in crane[1]])
                r.check(abs(ang.max() - CRANE_OPEN) < 7.0, f"{label}: {nm} opens the crane {ang.max():.1f} deg")
        if crane is not None:
            r.check(abs(_angle(crane[1][-1], 2)) < 0.05, f"{label}: {nm} leaves the cylinder open")
        ej = _channel(g, a, "ejector", "translation")
        if ej is not None:
            z = ej[1][:, 2]
            r.check(z.min() > -1e-4 and z.max() < STROKE + 0.0015, f"{label}: {nm} ejector outside its stroke")
            r.check(abs(z[-1]) < 1e-4, f"{label}: {nm} leaves the ejector out")
        if nm == "reload":
            r.check(ej is not None and ej[1][:, 2].max() > 0.9 * STROKE, f"{label}: reload does not punch the ejector")
        rounds = _channel(g, a, "rounds", "scale")
        if rounds is not None:
            r.check(rounds[2] == "STEP" and np.all(np.isin(np.round(rounds[1], 6), [0.0, 1.0])),
                    f"{label}: {nm} cartridges' scale must be STEP 0 / 1")
            r.check(np.allclose(rounds[1][-1], 1.0), f"{label}: {nm} ends with an empty cylinder")
        mag = _channel(g, a, "mag", "scale")
        if nm == "reload":
            r.check(rounds is not None, f"{label}: reload never takes the cartridges out")
            r.check(mag is not None and np.any(mag[1] > 0.5), f"{label}: reload never shows the speed loader")
        if mag is not None:
            r.check(nm == "reload", f"{label}: {nm} shows the speed loader")
            r.check(mag[2] == "STEP" and np.allclose(mag[1][-1], 0.0), f"{label}: {nm} leaves the speed loader shown")
    _check_loader(g, r, label, anims.get("reload"))


def _check_loader(g, r, label, a):
    """When the loader's rounds go into the chambers (the `speedloader` event) its frame sits on the cylinder's rear face
    with its rounds on the chambers."""
    if a is None:
        return
    ev = {e["name"]: e["time"] for e in a.get("extras", {}).get("events", [])}
    if "speedloader" not in ev:
        return
    W = g.world_matrices(g.sample(a, ev["speedloader"]))
    cyl, mag = W[g.names["cylinder"]], W[g.names["mag"]]
    length = 0.0410
    seat = cyl[:3, :3] @ np.array([0.0, 0.0, length + 0.0006]) + cyl[:3, 3]
    scale = np.linalg.norm(mag[:3, 0])
    err = float(np.linalg.norm(mag[:3, 3] - seat))
    axis = float(np.dot(mag[:3, 2] / max(scale, 1e-9), cyl[:3, 2]))
    r.check(scale > 0.5 and err < 0.002 and axis > 0.999,
            f"{label}: at the speedloader event the loader is {err * 1000:.1f} mm off the chambers (axis {axis:.4f})")
    r.note(f"reload: the loader's rounds on the chambers at {ev['speedloader']:.2f}s ({err * 1000:.2f} mm)")


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
    saved = cr.FREE
    cr.FREE = FREE
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    whole = sum(g.json["accessors"][p["indices"]]["count"] // 3 for m in g.json["meshes"] for p in m["primitives"])
    r.check(whole <= BUDGET, f"{label}: {whole} triangles in the file, over the budget of {BUDGET}")
    r.note(f"{label} triangles: {tris} drawn (excluding fx_shell), {whole} in the file (budget {BUDGET})")
