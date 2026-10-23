"""Thompson GLB checks (VIEWMODEL_SPEC section 24), used by check_glb.py (CHECK_MODULES): claims files with a
`mag_drum` node. Nodes and parents, materials and regions, clips (lengths, events, the cocking knob riding the bolt),
both magazines riding `mag`, the iron sights, eye distance, framing in 16:9 and 4:3, the triangle budget, and the
attachments on every mount (check_attach)."""
import numpy as np

import check_rifle as cr
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       check_sights)
from glbread import GLTF

STATIC = ("receiver", "barrel", "foregrip", "grip", "stock")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "selector": "gun",
    "trigger": "gun", "sight_iron": "gun", "sight_rear": "gun", "muzzle_brake": "gun", "aim_iron": "gun",
    "mag": "gun", "mag_stick": "mag", "mag_drum": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "selector", "trigger", "sight_iron", "sight_rear", "muzzle_brake", "mag_stick",
                            "mag_drum", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm"}
MATERIALS = {"wood", "steel", "steel_dark", "mag", "mag_base", "brass"}
SPLIT_MATERIALS = {"wood_foregrip", "wood_grip", "steel_barrel"}       # pbr_apply.py (section 21)
REGIONS = {"wood": "stock", "wood_foregrip": "handguard", "wood_grip": "grip", "steel": "body",
           "steel_barrel": "barrel", "steel_dark": "detail", "mag": "magazine", "mag_base": "magazine",
           "brass": "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.07, 0.10), "fire_last": (0.07, 0.10),
             "reload": (2.0, 2.6), "reload_empty": (2.5, 3.1), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
BUDGET = 9000                      # VIEWMODEL_SPEC Round G: rifles / SMGs / shotguns
MIN_TRAVEL = 0.07                  # the cocking knob's stroke in fire and reload_empty


def claims(path, names):
    return "mag_drum" in names


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
    for nm, m in mats.items():          # section 21: regions (once pbr_apply.py has run)
        region = (m.get("extras") or {}).get("region")
        if region is not None:
            r.check(region == REGIONS.get(nm), f"{label}: material {nm} region {region}, expected {REGIONS.get(nm)}")
    applied = all("pbr" in (m.get("extras") or {}) for nm, m in mats.items() if nm != "brass")
    r.note(f"materials: {', '.join(sorted(mats))}" + ("" if applied else " (texture sets not applied yet)"))


def _clips(g, r, label):
    """check_rifle's clip rules (lengths, loops, camera, events, the magazine's STEP scale, the bolt -- here the
    cocking knob -- sliding back along +Z and closing again) with our lengths, then the magazines."""
    saved = cr.DURATIONS, cr.MIN_BOLT_TRAVEL
    cr.DURATIONS, cr.MIN_BOLT_TRAVEL = DURATIONS, MIN_TRAVEL
    try:
        cr.check_clips(g, r, label)
    finally:
        cr.DURATIONS, cr.MIN_BOLT_TRAVEL = saved
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    # both magazines hang under `mag`, so every reload swaps either; they leave the well straight down
    mi = g.names["mag"]
    rest = np.asarray(g.nodes[mi].get("translation", [0, 0, 0]), float)
    for nm in ("reload", "reload_empty"):
        a = anims.get(nm)
        if a is None:
            continue
        drop = 0.0
        for ch in a["channels"]:
            if ch["target"]["node"] == mi and ch["target"]["path"] == "translation":
                vs = g.accessor(a["samplers"][ch["sampler"]]["output"]).astype(np.float64)
                drop = float((rest[1] - vs[:, 1]).max())
        r.check(drop > 0.05, f"{label}: {nm} does not take the magazine out of the well ({drop:.3f} m)")


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
    # the stock, grip, receiver (with the rear sight on its rear end), foregrip and magazines may run off the
    # bottom-right edge, as on the AK
    cr.FREE = ("stock", "grip", "receiver", "sight_rear", "foregrip", "mag_stick", "mag_drum", "tactical_stock",
               "heavy_stock", "wire_stock", "charm_grass")
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
