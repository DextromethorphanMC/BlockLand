"""Vector GLB checks (VIEWMODEL_SPEC section 44), used by check_glb.py (CHECK_MODULES): claims vector.glb. Nodes and
parents, materials and regions, clips (lengths against guns.json, events and their sounds, loops, camera range), the
mechanism (the bolt cycling in the fire clips while the non-reciprocating charging handle stays put; the slingshot in
the empty reload: the knob unfolded, the handle hauled back and let fly home; the press check in the inspect; nothing
left open or unfolded at a clip's end), the magazine leaving the deep well and coming back in both reloads (STEP scale),
the release lever pressed, the iron sights at ADS, eye distance, framing, the triangle budget and every attachment on
every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
import vector_model as vm
from check_glb import CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints, \
    check_meshes, check_sights, euler_deg
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "lower", "pistol_grip", "barrel")
MOUNTS = ("mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm", "mount_underbarrel")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "stock": "gun", "bolt": "gun",
    "handle_lever": "bolt", "carrier": "gun", "trigger": "gun",
    "selector": "gun", "paddle": "gun", "sight_iron": "gun", "front_sight_hood": "sight_iron", "sight_rear": "gun",
    "muzzle_brake": "gun", "aim_iron": "gun", "mag": "gun", "mag_standard": "mag", "mag_extended": "mag",
    "muzzle": "gun", "ejection": "gun", "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel",
    "arm_L_fore": "arm_L", "camera": "viewmodel", "fx_shell": None, **{m: "gun" for m in MOUNTS},
}
MESH_NODES = set(STATIC) | {"stock", "bolt", "handle_lever", "carrier", "trigger", "selector",
                            "paddle", "sight_iron", "front_sight_hood", "sight_rear", "muzzle_brake", "mag_standard",
                            "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", *MOUNTS}
MATERIALS = {"upper", "lower", "rail", "barrel", "stock", "pad", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = set()
REGIONS = {"upper": "body", "lower": "grip", "rail": "detail", "barrel": "barrel", "stock": "stock", "pad": "stock",
           "mag": "magazine", "mag_base": "magazine", "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.045, 0.06), "fire_last": (0.045, 0.06),
             "fire_ads": (0.045, 0.06), "fire_last_ads": (0.045, 0.06),
             "reload": (2.0, 2.4), "reload_empty": (2.4, 2.8), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
BUDGET = BUDGETS["long_gun"]
CARRIER_MIN, HANDLE_MIN = 0.040, 0.050
FREE = ("stock", "pistol_grip", "receiver", "lower", "carrier", "sight_rear", "trigger", "paddle", "selector",
        "mag_standard", "mag_extended",
        "tactical_stock", "heavy_stock", "wire_stock", "charm_grass")
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")


def claims(path, names):
    return os.path.basename(str(path)).lower() == "vector.glb"


def _gun_data():
    with open(os.path.join(DATA, "data", "blockland", "guns", "guns.json"), encoding="utf-8") as f:
        return json.load(f)["guns"]["vector"]


def _sounds():
    with open(os.path.join(DATA, "assets", "blockland", "sounds.json"), encoding="utf-8") as f:
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
        nm = str(n.get("name", ""))
        if nm.startswith(("mag_", "tube_")):     # loadouts.hidden_nodes treats these prefixes as magazines
            r.check(nm in ("mag_standard", "mag_extended"), f"{label}: node {nm} would be hidden as a magazine")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    for nm in cr.REMOVED_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the weapon file (section 19: separate attachment)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    gun = _gun_data()
    r.check(gun["slideLock"]["node"] in g.names, f"{label}: slideLock node {gun['slideLock']['node']} missing")
    for slot in gun["slots"].values():
        if slot.get("node"):
            r.check(slot["node"] in g.names, f"{label}: slot mount {slot['node']} missing")
        for h in slot.get("hides", []):
            r.check(h in g.names, f"{label}: hidden part {h} missing")
    with open(os.path.join(DATA, "data", "blockland", "guns", "guns.json"), encoding="utf-8") as f:
        mags = json.load(f)["magazines"]
    for mag in gun["magazines"]:
        node = mags[mag].get("node")
        r.check(node in g.names, f"{label}: magazine {mag}: node {node} missing")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in MATERIALS or nm in SPLIT_MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    applied = True
    for nm, m in mats.items():
        ex = m.get("extras") or {}
        region = ex.get("region")
        if region is not None:
            r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
        applied &= "pbr" in ex and region is not None
        if "pbr" in ex:
            base = os.path.join(DATA, "assets", "blockland", ex["pbr"])
            for suffix in ("", "_n", "_s"):
                r.check(os.path.exists(base + suffix + ".png"), f"{label}: {nm}: texture {ex['pbr']}{suffix}.png missing")
    r.check(applied, f"{label}: texture sets / regions not applied (run build_all.py vector)")
    r.note(f"materials: {', '.join(sorted(mats))}")


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def _angle(q, rest, axis):
    """Signed angle (deg) of a rotation relative to the rest rotation, about a unit axis (of the parent's frame)."""
    x, y, z, w = np.asarray(rest, float)
    a = np.array([-x, -y, -z, w])
    b = np.asarray(q, float)
    d = np.array([a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                  a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                  a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                  a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]])
    return float(np.degrees(2 * np.arctan2(np.dot(d[:3], axis), d[3])))


def _travel(g, a, node):
    """+Z travel of a sliding node relative to its rest (and its largest x / y drift), or None if not animated."""
    c = _channel(g, a, node, "translation")
    if c is None:
        return None
    rest = np.asarray(g.nodes[g.names[node]].get("translation", [0, 0, 0]), float)
    return c[0], c[1][:, 2] - rest[2], float(np.abs(c[1][:, :2] - rest[:2]).max())


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names["camera"]
    gun = _gun_data()
    sounds = _sounds()
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
                r.check(f"gun.{e.get('name')}" in sounds, f"{label}: {nm} event {e.get('name')} has no gun.* sound")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    for nm, key in (("reload", "tactical"), ("reload_empty", "empty")):
        if nm in anims:
            dur = g.duration(anims[nm])
            r.check(abs(dur - gun["reload"][key]) < 0.011, f"{label}: {nm} lasts {dur:.2f} s, guns.json reload.{key} "
                                                           f"is {gun['reload'][key]}")


UNFOLD_AXIS = (0.0, 1.0, 0.0)


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest_fold = g.nodes[g.names["handle_lever"]].get("rotation", [0, 0, 0, 1])
    for nm, a in anims.items():
        for node in ("bolt", "carrier"):
            tr = _travel(g, a, node)
            if tr is not None:
                _, z, side = tr
                r.check(side < 1e-6, f"{label}: {nm} moves the {node} off its rail (x/y)")
                r.check(z.min() > -1e-4, f"{label}: {nm} pushes the {node} forward of its rest")
                r.check(abs(z[0]) < 1e-4 and abs(z[-1]) < 1e-4, f"{label}: {nm} leaves the {node} open")
                r.note(f"{nm}: {node} travel {z.max() * 1000:.1f} mm")
        fold = _channel(g, a, "handle_lever", "rotation")
        if fold is not None:
            ang = np.array([_angle(q, rest_fold, UNFOLD_AXIS) for q in fold[1]])
            r.check(abs(ang[0]) < 0.2 and abs(ang[-1]) < 0.2, f"{label}: {nm} leaves the knob unfolded")
            r.check(ang.min() > -5.0 and ang.max() < 95.0, f"{label}: {nm} swings the knob out of its range")
    for nm in ("fire", "fire_last", "fire_ads", "fire_last_ads"):
        if nm not in anims:
            continue
        tr = _travel(g, anims[nm], "carrier")
        r.check(tr is not None and tr[1].max() >= CARRIER_MIN, f"{label}: {nm} does not cycle the bolt")
        r.check(_travel(g, anims[nm], "bolt") is None, f"{label}: {nm} moves the charging handle (it does not "
                                                        f"reciprocate)")
    if "reload_empty" in anims:
        a = anims["reload_empty"]
        tr = _travel(g, a, "bolt")
        fold = _channel(g, a, "handle_lever", "rotation")
        if r.check(tr is not None and fold is not None, f"{label}: reload_empty does not work the charging handle"):
            r.check(tr[1].max() >= HANDLE_MIN, f"{label}: reload_empty pulls the handle only {tr[1].max():.3f} m")
            fa = np.array([_angle(q, rest_fold, UNFOLD_AXIS) for q in fold[1]])
            r.check(fa.max() > 60.0, f"{label}: reload_empty does not unfold the knob ({fa.max():.0f} deg)")
            back = tr[0][tr[1] > HANDLE_MIN - 0.002]
            if len(back):
                unf = np.interp(back, fold[0], fa)
                r.check(unf.min() > 40.0, f"{label}: the handle is hauled back with the knob folded")
                r.note(f"reload_empty: handle back {back.min():.2f} .. {back.max():.2f} s, knob out {fa.max():.0f} deg")
        trc = _travel(g, a, "carrier")
        r.check(trc is not None and trc[1].max() >= CARRIER_MIN, f"{label}: reload_empty leaves the bolt forward")
    if "reload" in anims:
        r.check(_travel(g, anims["reload"], "bolt") is None, f"{label}: the tactical reload works the handle")
    if "inspect" in anims:
        tr = _travel(g, anims["inspect"], "bolt")
        r.check(tr is not None and tr[1].max() > 0.010, f"{label}: inspect has no press check")
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    rest_p = g.nodes[g.names["paddle"]].get("rotation", [0, 0, 0, 1])
    clear = vm.WELL_DEPTH                             # the magazine's top must leave the well's mouth (down its rake)
    for nm in ("reload", "reload_empty"):
        if nm not in anims:
            continue
        t = _channel(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float(((t[1] - rest) @ vm.MAG_DOWN).max())
        r.check(drop > clear, f"{label}: {nm} does not take the magazine out of the well ({drop:.3f} m, needs {clear:.3f})")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - rest) < 1e-4, f"{label}: {nm} does not seat the magazine")
        sc = _channel(g, anims[nm], "mag", "scale")
        r.check(sc is not None and np.any(sc[1] < 0.5), f"{label}: {nm} never swaps the magazine (scale)")
        pc = _channel(g, anims[nm], "paddle", "rotation")
        pa = [] if pc is None else [_angle(q, rest_p, (1, 0, 0)) for q in pc[1]]
        r.check(len(pa) and max(pa) > 8.0 and abs(pa[-1]) < 0.2, f"{label}: {nm} does not press the release lever")


def check(path, arms, r):
    g = GLTF(path)
    label = str(path).replace("\\", "/").rsplit("/", 1)[-1][:-4]
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
    cr.FREE = FREE
    try:
        cr.check_framing(path, r, label)
    finally:
        cr.FREE = saved_free
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    whole = sum(g.json["accessors"][p["indices"]]["count"] // 3 for m in g.json["meshes"] for p in m["primitives"])
    r.check(tris <= BUDGET, f"{label}: {tris} triangles over the budget of {BUDGET}")
    r.check(whole <= BUDGET, f"{label}: {whole} triangles in the whole file over the ceiling of {BUDGET}")
    r.note(f"{label} triangles: {tris} drawn (both magazines), {whole} in the file with fx_shell (budget {BUDGET})")
