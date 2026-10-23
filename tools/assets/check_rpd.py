"""RPD GLB checks (VIEWMODEL_SPEC section 33), used by check_glb.py (CHECK_MODULES): claims files with a `feed_cover`
node. Nodes and parents, materials and regions, clips (lengths, events, the bolt riding its rail and closing again),
the feed cover (closed at rest and at the end of every clip, opened in the reloads), the belt (STEP scale 0/1, back
on the tray at the end of every clip), the drum leaving and returning in both reloads, the iron sights at ADS, eye
distance, framing, the triangle budget and every attachment on every mount (check_attach)."""
import numpy as np

import check_rifle as cr
import rpd_rig as rig
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       check_sights)
from glbread import GLTF

STATIC = ("receiver", "handguard", "barrel", "front_sight", "stock", "pistol_grip")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "feed_cover": "gun", "bolt": "gun",
    "trigger": "gun", "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun",
    "belt": "gun", "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
    "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | {"feed_cover", "bolt", "trigger", "sight_iron", "sight_rear", "muzzle_brake", "belt",
                            "mag_standard", "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm",
               "mount_underbarrel"}
MATERIALS = {"wood", "steel", "steel_dark", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = {"wood_handguard", "wood_grip", "steel_barrel", "steel_stock", "steel_dark_barrel"}
REGIONS = {"wood": "stock", "wood_handguard": "handguard", "wood_grip": "grip", "steel": "body",
           "steel_barrel": "barrel", "steel_stock": "stock", "steel_dark": "detail", "steel_dark_barrel": "barrel",
           "mag": "magazine", "mag_base": "magazine", "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.8, 1.1), "fire": (0.08, 0.10), "fire_last": (0.08, 0.10),
             "reload": (5.0, 6.0), "reload_empty": (5.5, 6.5), "inspect": (4.0, 5.0), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
EVENT_NAMES = {"mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip", "cover_open", "cover_close",
               "belt"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"cover_open", "belt", "mag_out", "mag_in", "cover_close"},
                   "reload_empty": {"cover_open", "belt", "mag_out", "mag_in", "cover_close", "bolt_back",
                                    "bolt_forward"},
                   "inspect": {"inspect_move", "cover_open", "cover_close"}}
BUDGET = 9000
MIN_TRAVEL = 0.08


def claims(path, names):
    return "feed_cover" in names


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
    for nm, m in mats.items():
        region = (m.get("extras") or {}).get("region")
        if region is not None:
            r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
    applied = all("pbr" in (m.get("extras") or {}) for nm, m in mats.items() if nm != "brass")
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def _channel(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0], g.accessor(s["output"]).astype(np.float64), s.get("interpolation")
    return None


def _angle_x(q, rest):
    """Signed angle (deg) about X of a rotation relative to the rest rotation."""
    x, y, z, w = np.asarray(rest, float)
    a, b = np.array([-x, -y, -z, w]), q                     # delta = conj(rest) * q; about X: 2 atan2(x, w)
    dx = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
    dw = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    return float(np.degrees(2 * np.arctan2(dx, dw)))


def check_mechanism(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    rest_cover = g.nodes[g.names["feed_cover"]].get("rotation", [0, 0, 0, 1])
    for nm, a in anims.items():
        c = _channel(g, a, "feed_cover", "rotation")
        if c is not None:
            angles = np.array([_angle_x(q, rest_cover) for q in c[1]])
            r.check(abs(angles[-1]) < 0.2 and abs(angles[0]) < 0.2, f"{label}: {nm} leaves the feed cover open")
            r.check(angles.min() > -80.0 and angles.max() < 3.0, f"{label}: {nm} swings the cover out of its range")
            if nm in ("reload", "reload_empty"):
                r.check(angles.min() < -50.0, f"{label}: {nm} does not open the cover ({angles.min():.0f} deg)")
            r.note(f"{nm}: feed cover {angles.min():.0f}..{angles.max():.1f} deg")
        elif nm in ("reload", "reload_empty", "inspect"):
            r.check(False, f"{label}: {nm} never opens the feed cover")
        s = _channel(g, a, "belt", "scale")
        if s is not None:
            r.check(s[2] == "STEP" and np.all(np.isin(np.round(s[1], 6), [0.0, 1.0])),
                    f"{label}: {nm} belt scale must be STEP 0 / 1")
            r.check(np.allclose(s[1][-1], 1.0), f"{label}: {nm} ends without the belt")
        t = _channel(g, a, "belt", "translation")
        if t is not None:
            rest = np.asarray(g.nodes[g.names["belt"]].get("translation", [0, 0, 0]), float)
            r.check(np.linalg.norm(t[1][-1] - rest) < 1e-3, f"{label}: {nm} does not put the belt back on the tray")
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        t = _channel(g, anims[nm], "mag", "translation")
        drop = 0.0 if t is None else float((rest[1] - t[1][:, 1]).max())
        r.check(drop > 0.05, f"{label}: {nm} does not take the drum off ({drop:.3f} m)")


def _clips(g, r, label):
    saved = cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS
    cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = (DURATIONS, MIN_TRAVEL, EVENT_NAMES,
                                                                            REQUIRED_EVENTS)
    try:
        cr.check_clips(g, r, label)
    finally:
        cr.DURATIONS, cr.MIN_BOLT_TRAVEL, cr.EVENT_NAMES, cr.REQUIRED_EVENTS = saved
    check_mechanism(g, r, label)


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
    cr.FREE = ("stock", "pistol_grip", "receiver", "feed_cover", "belt", *rig.BELT_NODES, "sight_rear", "mag_standard",
               "mag_extended",
               "tactical_stock", "heavy_stock", "wire_stock", "charm_grass")
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
