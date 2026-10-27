"""PP-19 Bizon GLB checks (VIEWMODEL_SPEC section 54), used by check_glb.py (CHECK_MODULES): claims bizon.glb. Nodes and
parents, materials and regions, clips (lengths against guns.json, events and their sounds, loops, camera range), the
mechanism (the carrier and its handle cycling in the fire clips; the empty reload's pull of the handle; the press check
in the inspect; nothing left open at a clip's end), the helical magazine rocking out about its nose lug and back in in
both reloads (STEP scale), the release pressed, the iron sights at ADS, eye distance, framing, the triangle budget and
every attachment on every mount (check_attach)."""
import json
import os

import numpy as np

import bizon_model as bm
import check_rifle as cr
from check_glb import CAM_ROLL, CAM_TURN, check_container, check_curve_rate, check_eye_distance, check_hand_joints, \
    check_meshes, check_sights, euler_deg
from glbread import GLTF
from model_quality import BUDGETS

STATIC = ("receiver", "dust_cover", "front_sight", "barrel", "pistol_grip")
MOUNTS = ("mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "stock": "gun", "bolt": "gun",
    "trigger": "gun", "selector": "gun", "paddle": "gun", "sight_iron": "gun", "front_sight_hood": "sight_iron",
    "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun", "mag": "gun", "mag_standard": "mag",
    "muzzle": "gun", "ejection": "gun", "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel",
    "arm_L_fore": "arm_L", "camera": "viewmodel", "fx_shell": None, **{m: "gun" for m in MOUNTS},
}
MESH_NODES = set(STATIC) | {"stock", "bolt", "trigger", "selector", "paddle", "sight_iron", "front_sight_hood",
                            "sight_rear", "muzzle_brake", "mag_standard", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", *MOUNTS}
MATERIALS = {"steel", "steel_dark", "barrel", "polymer", "stock", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = {"steel_stock"}
REGIONS = {"steel": "body", "steel_stock": "stock", "steel_dark": "detail", "barrel": "barrel", "polymer": "grip",
           "stock": "stock", "mag": "magazine", "mag_base": "magazine", "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.07, 0.09), "fire_last": (0.07, 0.09),
             "reload": (2.2, 2.6), "reload_empty": (2.7, 3.1), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
BUDGET = BUDGETS["long_gun"]
BOLT_MIN = 0.060
FREE = ("stock", "pistol_grip", "receiver", "dust_cover", "sight_rear", "trigger", "paddle", "selector", "mag_standard",
        "tactical_stock", "heavy_stock", "wire_stock", "charm_grass")
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")


def claims(path, names):
    return os.path.basename(str(path)).lower() == "bizon.glb"


def _gun_data():
    with open(os.path.join(DATA, "data", "blockland", "guns", "guns.json"), encoding="utf-8") as f:
        return json.load(f)["guns"]["bizon"]


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
            r.check(nm == "mag_standard", f"{label}: node {nm} would be hidden as a magazine")
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
    r.check(applied, f"{label}: texture sets / regions not applied (run build_all.py bizon)")
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
    r.check("gun.bizon.fire" in sounds and "gun.bizon.fire_far" in sounds, f"{label}: gun.bizon.fire / fire_far missing "
                                                                            f"from sounds.json")


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    for nm, a in anims.items():
        tr = _travel(g, a, "bolt")
        if tr is not None:
            _, z, side = tr
            r.check(side < 1e-6, f"{label}: {nm} moves the bolt off its rail (x/y)")
            r.check(z.min() > -1e-4, f"{label}: {nm} pushes the bolt forward of its rest")
            r.check(z.max() <= bm.BOLT_TRAVEL + 1e-4, f"{label}: {nm} pulls the bolt past its stroke")
            r.check(abs(z[0]) < 1e-4 and abs(z[-1]) < 1e-4, f"{label}: {nm} leaves the bolt open")
            r.note(f"{nm}: bolt travel {z.max() * 1000:.1f} mm")
    for nm in ("fire", "fire_last"):
        if nm in anims:
            tr = _travel(g, anims[nm], "bolt")
            r.check(tr is not None and tr[1].max() >= BOLT_MIN, f"{label}: {nm} does not cycle the bolt")
    if "reload_empty" in anims:
        tr = _travel(g, anims["reload_empty"], "bolt")
        r.check(tr is not None and tr[1].max() >= BOLT_MIN, f"{label}: reload_empty does not pull the charging handle")
    if "reload" in anims:
        r.check(_travel(g, anims["reload"], "bolt") is None, f"{label}: the tactical reload works the handle")
    if "inspect" in anims:
        tr = _travel(g, anims["inspect"], "bolt")
        r.check(tr is not None and tr[1].max() > 0.010, f"{label}: inspect has no press check")
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    rest_p = g.nodes[g.names["paddle"]].get("rotation", [0, 0, 0, 1])
    for nm in ("reload", "reload_empty"):
        if nm not in anims:
            continue
        t = _channel(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float((rest[1] - t[1][:, 1]).max())
        r.check(drop > 0.05, f"{label}: {nm} does not take the magazine out ({drop:.3f} m)")
        if t is not None:
            r.check(np.linalg.norm(t[1][-1] - rest) < 1e-4, f"{label}: {nm} does not seat the magazine")
        q = _channel(g, anims[nm], "mag", "rotation")
        if r.check(q is not None, f"{label}: {nm} does not rock the magazine"):
            ang = np.array([_angle(v, [0, 0, 0, 1], (1, 0, 0)) for v in q[1]])
            r.check(ang.max() > 8.0, f"{label}: {nm} rocks the magazine's rear only {ang.max():.1f} deg down")
            r.check(abs(ang[-1]) < 0.2, f"{label}: {nm} leaves the magazine turned")
        s = _channel(g, anims[nm], "mag", "scale")
        r.check(s is not None and np.any(s[1] < 0.5), f"{label}: {nm} never swaps the magazine (scale)")
        pc = _channel(g, anims[nm], "paddle", "rotation")
        pa = [] if pc is None else [_angle(v, rest_p, (1, 0, 0)) for v in pc[1]]
        r.check(len(pa) and max(pa) > 8.0 and abs(pa[-1]) < 0.2, f"{label}: {nm} does not press the release")


#: the sight picture above the front post at ADS (pixels at 1920x1080): nothing nearer than the post may cover it
PICTURE_HALF_W, PICTURE_H = 70.0, 140.0
#: the front sight assembly frames the post itself and is not in its way
FRONT_SIGHT = ("sight_iron", "front_sight_hood", "front_sight")


def check_sight_picture(g, r, label):
    """At the iron-sight ADS pose, every triangle between the eye and the front post (sampled across its face) must
    stay out of the picture above the post: a box PICTURE_HALF_W either side of the screen centre, from just above the
    sight line up PICTURE_H pixels. The rear notch's shoulders sit on the line itself; a hood, ear or leaf rising
    above it would hide the post (the user saw exactly that)."""
    W = g.world_matrices({})
    inv = np.linalg.inv(W[g.names["aim_iron"]])
    focal = 540.0 / np.tan(np.radians(cr.FOV_Y / 2))
    post_z = None
    n = g.nodes[g.names["sight_iron"]]
    M = inv @ W[g.names["sight_iron"]]
    v = np.concatenate([p["positions"] for p in g.mesh_primitives(n["mesh"])]) @ M[:3, :3].T + M[:3, 3]
    post_z = float(v[:, 2].min())
    # barycentric samples over each triangle (corners, edges, inside)
    k = np.array([(a, b, 6 - a - b) for a in range(7) for b in range(7 - a)], dtype=np.float64) / 6.0
    worst = (0.0, "")
    for i, node in enumerate(g.nodes):
        if "mesh" not in node or node["name"] == "fx_shell" or node["name"] in FRONT_SIGHT:
            continue
        M = inv @ W[i]
        for p in g.mesh_primitives(node["mesh"]):
            pos = p["positions"] @ M[:3, :3].T + M[:3, 3]
            tri = pos[p["indices"]]                                  # (n, 3, 3)
            pts = np.einsum("sk,nkd->nsd", k, tri).reshape(-1, 3)
            pts = pts[(pts[:, 2] < -0.02) & (pts[:, 2] > post_z)]   # in front of the eye, nearer than the post
            if not len(pts):
                continue
            px = focal * pts[:, :2] / -pts[:, 2:3]
            inside = (np.abs(px[:, 0]) <= PICTURE_HALF_W) & (px[:, 1] > 0.5) & (px[:, 1] <= PICTURE_H)
            if inside.any():
                h = float(px[inside, 1].max())
                if h > worst[0]:
                    worst = (h, node["name"])
    r.check(not worst[1], f"{label}: {worst[1]} rises {worst[0]:.0f} px into the sight picture above the front post at ADS")


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
    check_sight_picture(g, r, label)
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
    r.note(f"{label} triangles: {tris} drawn, {whole} in the file with fx_shell (budget {BUDGET})")
