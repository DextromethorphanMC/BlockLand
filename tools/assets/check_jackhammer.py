"""Jackhammer GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims jackhammer.glb.
Nodes and parents, materials and regions, clips (lengths against guns.json, loops, camera limits, events), the
mechanism (the operating sleeve `bolt` on its rail, racked in the empty reload and home at the end of every clip; the
cassette indexed exactly one chamber per shot on a STEP channel and back at rest; the cassette pulled out and
re-seated in both reloads, its scale STEP 0 / 1; the latch back at rest), the iron sights at ADS, eye distance,
framing, the triangle budget and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, check_sights, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "handle", "stock", "lower", "barrel")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "trigger": "gun",
    "latch": "gun", "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun", "mag": "gun",
    "mag_standard": "mag", "muzzle": "gun", "ejection": "gun", "arm_R": "viewmodel", "arm_R_fore": "arm_R",
    "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel", "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "bolt", "mount_barrel": "gun", "mount_underbarrel": "bolt",
    "mount_charm": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "trigger", "latch", "sight_iron", "sight_rear", "muzzle_brake", "mag_standard",
                            "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_underbarrel", "mount_charm"}
ABSENT = ("mount_stock", "mag_extended", "stock_slot", "pump", "feed_cover")     # bullpup: no stock slot, one cassette
REGIONS = {"steel": "body", "stock": "stock", "forend": "handguard", "grip": "grip", "barrel": "barrel",
           "steel_dark": "detail", "cassette": "magazine", "rubber": "none", "shell": "none", "brass": "none"}
CLIPS = ("idle", "draw", "fire", "fire_last", "reload", "reload_empty", "inspect", "walk", "sprint", "tac_sprint")
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
LENGTHS = {"idle": (2.0, 2.0), "draw": (0.7, 1.0), "fire": (0.15, 0.25), "fire_last": (0.15, 0.25),
           "inspect": (3.5, 4.6), "walk": (0.6, 1.6), "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
TRAVEL = 0.032                      # the operating sleeve's cocking stroke
INDEX = 36.0                        # one chamber per shot
BUDGET = BUDGETS["long_gun"]
FREE = ("stock", "lower", "latch", "receiver", "handle", "sight_rear", "mag_standard", "charm_grass", "reddot", "holo",
        "mini_reddot", "reddot_rect")   # the bullpup's rear half runs off the bottom-right edge (COD-style hip)
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")


def claims(path, names):
    return os.path.splitext(os.path.basename(path))[0] == "jackhammer"


def _data():
    with open(GUNS_JSON, encoding="utf-8") as f:
        return json.load(f)["guns"]["jackhammer"]


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
    # the data contract: every slot's mount and hidden parts, the magazine node, the slide-lock node
    gun = _data()
    for slot, s in gun["slots"].items():
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names, f"{label}: slot {slot} hides {h}, which is not in the file")
    r.check(gun["slideLock"]["node"] in g.names, f"{label}: slideLock node {gun['slideLock']['node']} missing")
    r.check("mag_standard" in g.names, f"{label}: magazine node mag_standard missing")


def check_materials(g, r, label):
    mats = {m.get("name"): m for m in g.json.get("materials", [])}
    for nm in mats:
        r.check(nm in REGIONS, f"{label}: unexpected material {nm}")
    for nm in set(REGIONS) - set(mats):
        r.check(False, f"{label}: material {nm} missing")
    for nm, m in mats.items():
        region = (m.get("extras") or {}).get("region")
        r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
    applied = all("pbr" in (m.get("extras") or {}) for m in mats.values())
    r.check(applied, f"{label}: texture sets not applied (pbr_apply)")
    r.note(f"materials: {', '.join(sorted(mats))}")


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def _angle(q, rest, axis):
    """Signed angle (deg) of q relative to rest about a local axis (0 = X, 2 = Z)."""
    x, y, z, w = np.asarray(rest, float)
    a, b = np.array([-x, -y, -z, w]), np.asarray(q, float)
    d = np.array([a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                  a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                  a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                  a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]])
    return float(np.degrees(2 * np.arctan2(d[axis], d[3])))


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    reload = _data()["reload"]
    lengths = dict(LENGTHS, reload=(reload["tactical"] - 1e-3, reload["tactical"] + 1e-3),
                   reload_empty=(reload["empty"] - 1e-3, reload["empty"] + 1e-3))
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
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= {e["name"] for e in ev}, f"{label}: {nm} missing events {need - {e['name'] for e in ev}}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    bolt_rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
    mag_rest_t = np.asarray(g.nodes[g.names["mag"]].get("translation", [0, 0, 0]), float)
    latch_rest = g.nodes[g.names["latch"]].get("rotation", [0, 0, 0, 1])
    for nm, a in anims.items():
        b = _channel(g, a, "bolt", "translation")
        if b is not None:
            d = b[1] - bolt_rest
            r.check(np.abs(d[:, :2]).max() < 1e-6, f"{label}: {nm} moves the sleeve off its rail")
            r.check(d[:, 2].min() > -1e-4 and d[:, 2].max() < TRAVEL + 0.002,
                    f"{label}: {nm} sleeve outside its stroke ({d[:, 2].min():.4f}..{d[:, 2].max():.4f})")
            r.check(abs(d[-1, 2]) < 1e-4 and abs(d[0, 2]) < 1e-4, f"{label}: {nm} leaves the sleeve back")
            r.note(f"{nm}: sleeve stroke {d[:, 2].max() * 1000:.1f} mm")
        elif nm == "reload_empty":
            r.check(False, f"{label}: reload_empty never racks the sleeve")
        if nm == "reload_empty" and b is not None:
            r.check((b[1][:, 2] - bolt_rest[2]).max() >= 0.9 * TRAVEL, f"{label}: reload_empty racks the sleeve short")
        lt = _channel(g, a, "latch", "rotation")
        if lt is not None:
            ang = np.array([_angle(q, latch_rest, 0) for q in lt[1]])
            r.check(abs(ang[-1]) < 0.2 and abs(ang[0]) < 0.2, f"{label}: {nm} leaves the latch pressed")
        rot = _channel(g, a, "mag", "rotation")
        if nm in ("fire", "fire_last"):
            if not r.check(rot is not None, f"{label}: {nm} does not index the cassette"):
                continue
            ang = np.array([_angle(q, [0, 0, 0, 1], 2) for q in rot[1]])
            r.check(rot[2] == "STEP", f"{label}: {nm} cassette index must be a STEP channel")
            r.check(abs(abs(ang).max() - INDEX) < 1.5, f"{label}: {nm} indexes {abs(ang).max():.1f} deg, not {INDEX}")
            r.check(abs(ang[-1]) < 1e-3 and abs(ang[0]) < 1e-3, f"{label}: {nm} cassette not back at rest at the end")
            at = lambda u: ang[max(0, int(np.searchsorted(rot[0], u, side="right")) - 1)]     # STEP lookup
            settled = np.array([at(u) for u in np.linspace(0.07, g.duration(a) - 0.002, 12)])
            r.check(np.allclose(np.abs(settled), INDEX, atol=0.05),
                    f"{label}: {nm} cassette not held one chamber on before the snap back")
            r.note(f"{nm}: cassette indexed {abs(ang).max():.1f} deg (STEP, snaps back at {rot[0][-1]:.3f}s)")
        t = _channel(g, a, "mag", "translation")
        s = _channel(g, a, "mag", "scale")
        if s is not None:
            r.check(s[2] == "STEP" and np.all(np.isin(np.round(s[1], 6), [0.0, 1.0])),
                    f"{label}: {nm} cassette scale must be STEP 0 / 1")
            r.check(np.allclose(s[1][-1], 1.0), f"{label}: {nm} ends without the cassette")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - mag_rest_t) < 1e-4, f"{label}: {nm} does not re-seat the cassette")
        if nm in ("reload", "reload_empty"):
            drop = 0.0 if t is None else float((mag_rest_t[1] - t[1][:, 1]).max())
            r.check(drop > 0.08, f"{label}: {nm} does not take the cassette out ({drop:.3f} m)")
            r.check(s is not None, f"{label}: {nm} never hides the dropped cassette")


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
