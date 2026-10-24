"""P90 GLB checks (VIEWMODEL_SPEC section 40), used by check_glb.py (CHECK_MODULES): claims models/gun/p90.glb.
Nodes and parents (no stock mount: a bullpup), materials and regions (the translucent BLEND magazine shell), the data
contract with guns.json (magazine node, slide-lock node, reload lengths), clips (lengths, loops, camera, events, the
magazine's STEP scale, the non-reciprocating cocking handles: still in the shots, pulled and home again in the empty
reload and the inspect), the magazine leaving and coming back in both reloads, the ejection pointing down, the iron
sights at ADS, eye distance, framing, the triangle budget and every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import check_rifle as cr
from check_glb import (CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, euler_deg)
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("butt", "receiver", "frame", "forend", "housing", "barrel")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "trigger": "gun",
    "selector": "gun", "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_charm": "gun", "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "trigger", "selector", "sight_iron", "sight_rear", "muzzle_brake", "mag_standard",
                            "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_charm", "mount_underbarrel"}
ABSENT = ("mount_stock", "stock", "mag_extended")          # bullpup: no stock slot; one magazine
MATERIALS = {"polymer", "steel", "steel_dark", "barrel", "mag", "mag_window", "brass"}
SPLIT_MATERIALS = {"polymer_stock", "polymer_grip", "polymer_fore"}
REGIONS = {"polymer": "body", "polymer_stock": "stock", "polymer_grip": "grip", "polymer_fore": "handguard",
           "steel": "detail", "steel_dark": "detail", "barrel": "barrel", "mag": "magazine", "mag_window": "magazine",
           "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.05, 0.10), "fire_last": (0.05, 0.10),
             "reload": (2.0, 3.5), "reload_empty": (2.5, 4.0), "inspect": (3.4, 4.2), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
BUDGET = BUDGETS["long_gun"]
MIN_TRAVEL = 0.06                  # the cocking handles' stroke in the empty reload
GUNS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "mod", "src", "main", "resources",
                         "data", "blockland", "guns", "guns.json")
FREE = ("butt", "receiver", "frame", "forend", "mag_standard", "sight_rear", "charm_grass")


def claims(path, names):
    return os.path.basename(path) == "p90.glb"


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
        r.check(nm not in g.names, f"{label}: {nm} must not be in the P90 file")
    for n in g.nodes:
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
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
        region = (m.get("extras") or {}).get("region")
        if region is not None:
            r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
        blend = m.get("alphaMode", "OPAQUE") == "BLEND"
        r.check(blend == (nm == "mag_window"), f"{label}: material {nm} alpha mode {m.get('alphaMode', 'OPAQUE')}")
        r.check(not m.get("doubleSided"), f"{label}: material {nm} must not be double sided")
    if "mag_window" in mats:
        a = mats["mag_window"]["pbrMetallicRoughness"]["baseColorFactor"][3]
        r.check(0.25 <= a <= 0.7, f"{label}: mag_window alpha {a:.2f}: the rounds must show through it")
    applied = all("pbr" in (m.get("extras") or {}) for nm, m in mats.items() if nm not in ("brass", "mag_window"))
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def check_data(g, r, label):
    """The gun's entry in guns.json: magazine node, slide-lock node, the reload clips as long as the data says."""
    with open(GUNS_JSON, encoding="utf-8") as f:
        data = json.load(f)
    gun = data["guns"].get("p90")
    if not r.check(gun is not None, f"{label}: no p90 in guns.json"):
        return
    for mag in gun.get("magazines", []):
        node = data["magazines"][mag].get("node")
        r.check(node in g.names, f"{label}: magazine {mag} node {node} missing")
    lock = gun.get("slideLock", {}).get("node")
    r.check(lock in g.names, f"{label}: slideLock node {lock} missing")
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    for key, clip in (("tactical", "reload"), ("empty", "reload_empty")):
        want = gun.get("reload", {}).get(key)
        if clip in anims and want is not None:
            dur = g.duration(anims[clip])
            r.check(abs(dur - want) < 0.02, f"{label}: {clip} lasts {dur:.2f} s, guns.json reload.{key} = {want}")
    for slot, s in gun.get("slots", {}).items():
        if s.get("node"):
            r.check(s["node"] in g.names, f"{label}: slot {slot} mount {s['node']} missing")
        for h in s.get("hides", []):
            r.check(h in g.names, f"{label}: slot {slot} hides {h}, which is not in the file")
    r.check("stock" not in gun.get("slots", {}), f"{label}: guns.json offers a stock slot on the bullpup")


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
            if nm in cr.LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in cr.NO_CAMERA, f"{label}: {nm} must not animate the camera")
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
    # the cocking handles: non-reciprocating (still in the shots), along +Z only, pulled in the empty reload and the
    # inspect, home at the end of every clip
    rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
    for nm, a in anims.items():
        c = _channel(g, a, "bolt", "translation")
        z = np.zeros(1) if c is None else c[1][:, 2] - rest[2]
        if c is not None:
            r.check(np.abs(c[1][:, :2] - rest[:2]).max() < 1e-6, f"{label}: {nm} moves the cocking handles off "
                                                                    f"their rods")
            r.check(z.min() > -1e-4, f"{label}: {nm} pushes the cocking handles forward of their rest")
            r.check(abs(z[-1]) < 1e-4, f"{label}: {nm} ends with the cocking handles back")
        if nm in ("fire", "fire_last"):
            r.check(z.max() < 1e-4, f"{label}: {nm} moves the non-reciprocating cocking handles")
        if nm == "reload_empty":
            r.check(z.max() >= MIN_TRAVEL, f"{label}: {nm} cocking stroke {z.max():.3f} < {MIN_TRAVEL}")
        if nm == "inspect":
            r.check(z.max() > 0.01, f"{label}: {nm} does not work the cocking handle")
        if z.max() > 0:
            r.note(f"{nm}: cocking handle travel {z.max() * 1000:.1f} mm")


def check_magazine(g, r, label):
    """Both reloads take the magazine off the receiver (its rear lifted first) and put it back; every clip ends with
    it seated."""
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm, a in anims.items():
        t = _channel(g, a, "mag", "translation")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - rest) < 1e-4, f"{label}: {nm} does not seat the magazine at the end")
        if nm in ("reload", "reload_empty"):
            far = 0.0 if t is None else float(np.linalg.norm(t[1] - rest, axis=1).max())
            r.check(far > 0.10, f"{label}: {nm} does not take the magazine away ({far:.3f} m)")
            q = _channel(g, a, "mag", "rotation")
            lift = 0.0
            if q is not None:
                lift = max(float(np.degrees(2 * np.arcsin(np.clip(-v[0], -1, 1)))) for v in q[1])
            r.check(lift > 4.0, f"{label}: {nm} does not lift the magazine's rear ({lift:.1f} deg)")
            r.note(f"{nm}: magazine taken {far * 1000:.0f} mm away, rear lifted up to {lift:.0f} deg")


def check_sights(g, r, label):
    """aim_iron and the mounts that point down the bore are parallel to it; the ejection points down (the P90 drops
    its cases through the floor of the butt)."""
    W = g.world_matrices({})
    bore = -W[g.names["muzzle"]][:3, 2]
    for nm in ("aim_iron", "mount_optic", "mount_laser", "mount_barrel", "mount_underbarrel"):
        d = -W[g.names[nm]][:3, 2]
        r.check(np.dot(d, bore) / np.linalg.norm(d) > 0.9999, f"{label}: {nm} -Z not parallel to the bore")
    ej = W[g.names["ejection"]][:3, 0]
    down = -W[g.names["gun"]][:3, 1]
    r.check(np.dot(ej, down) / np.linalg.norm(ej) > 0.8, f"{label}: ejection +X does not point down out of the butt")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_data(g, r, label)
    check_clips(g, r, label)
    check_magazine(g, r, label)
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
    r.check(tris <= BUDGET, f"{label}: {tris} triangles over the budget of {BUDGET}")
    r.note(f"{label} triangles (weapon file, excluding fx_shell): {tris} (budget {BUDGET})")
