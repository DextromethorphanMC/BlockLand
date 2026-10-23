"""Rifle GLB checks (VIEWMODEL_SPEC section 18: AK-47 pattern, bolt instead of slide), used by check_glb.py."""
import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (FOV_Y, check_container, check_curve_rate, check_eye_distance, check_hand_joints,
                       check_meshes, euler_deg)
from glbread import GLTF

STATIC = ("receiver", "dust_cover", "handguard", "gas_tube", "barrel", "front_sight", "stock", "pistol_grip")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "selector": "gun",
    "trigger": "gun", "sight_iron": "gun", "sight_rear": "gun", "front_sight_ears": "gun", "muzzle_brake": "gun",
    "aim_iron": "gun",
    "mag": "gun", "mag_standard": "mag", "mag_extended": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None,
    # section 19: attachment mounts
    "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun", "mount_stock": "gun", "mount_charm": "gun",
    "mount_underbarrel": "gun",                                      # section 35
}
MESH_NODES = set(STATIC) | {"bolt", "selector", "trigger", "sight_iron", "sight_rear", "front_sight_ears",
                            "muzzle_brake",
                            "mag_standard", "mag_extended", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore",
               "camera", "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm", "mount_underbarrel"}
REMOVED_NODES = ("sight_reddot", "reddot_reticle", "aim_reddot", "laser", "laser_emitter")
MATERIALS = {"wood", "steel", "steel_dark", "mag", "mag_base", "brass"}
# section 21: copies split off per region by pbr_apply.py (optional: a freshly generated file has none yet)
SPLIT_MATERIALS = {"wood_handguard", "wood_grip", "steel_barrel", "steel_stock", "steel_dark_barrel"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.9), "fire": (0.08, 0.15), "fire_last": (0.08, 0.15),
             "reload": (2.0, 2.8), "reload_empty": (2.5, 3.3), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward", "inspect_move", "equip"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "bolt_back", "bolt_forward"},
                   "inspect": {"inspect_move"}}
FRAMED = ("rest", "idle", "fire", "fire_last", "walk")          # must stay on screen with the margin
FREE = ("stock", "pistol_grip", "receiver", "dust_cover", "mag_standard", "mag_extended", "tactical_stock",
        "charm_grass")  # may run off the bottom / right edge (the receiver's rear end meets the stock there)
MIN_BOLT_TRAVEL = 0.07
ADS_CLEAR = 0.03          # at ADS no gun vertex in front of the eye may be closer than this (near plane 0.02)
ADS_PX = 2.0              # aiming point within this many pixels of the screen centre at 1920x1080
ADS_SIGHTS = {"aim_iron": ()}    # aim node -> subtrees not drawn (optics: check_attach, section 19)


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
    for nm in REMOVED_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the weapon file (section 19: separate attachment)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")


def check_materials(g, r, label):
    names = [m.get("name") for m in g.json.get("materials", [])]
    for nm in names:
        r.check(nm in MATERIALS or nm in SPLIT_MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(names):
        r.check(False, f"{label}: material {nm} missing")
    mats = {m["name"]: m for m in g.json.get("materials", [])}
    for nm in ("wood", "steel"):
        if nm in mats:      # an embedded texture, or (section 20) a texture set
            r.check("baseColorTexture" in mats[nm]["pbrMetallicRoughness"] or "pbr" in mats[nm].get("extras", {}),
                    f"{label}: {nm} lacks its texture")



def _bolt_z(g, a):
    """Bolt travel (+Z = rearward, relative to rest) over a clip, or None if the clip leaves the bolt at rest."""
    bi = g.names["bolt"]
    rest_z = g.nodes[bi].get("translation", [0, 0, 0])[2]
    for ch in a["channels"]:
        if ch["target"]["node"] == bi and ch["target"]["path"] == "translation":
            s = a["samplers"][ch["sampler"]]
            vs = g.accessor(s["output"]).astype(np.float64)
            rest = np.asarray(g.nodes[bi].get("translation", [0, 0, 0]))
            return vs[:, 2] - rest_z, np.abs(vs[:, :2] - rest[:2]).max()
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
                        f"{label}: {nm} camera beyond +-1.5/+-3 deg")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if g.nodes[node]["name"] == "mag" and path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} mag scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} mag scale not 0/1")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    # bolt: slides along +Z only; cycles fully in fire / fire_last (no hold-open), is pulled in reload_empty
    for nm in ("fire", "fire_last", "reload_empty", "inspect"):
        if nm not in anims:
            continue
        b = _bolt_z(g, anims[nm])
        if not r.check(b is not None, f"{label}: {nm} does not move the bolt"):
            continue
        z, side = b
        r.check(side < 1e-6, f"{label}: {nm} moves the bolt off its rail (x/y)")
        r.check(z.min() > -1e-4, f"{label}: {nm} pushes the bolt forward of its rest")
        if nm != "inspect":
            r.check(z.max() >= MIN_BOLT_TRAVEL, f"{label}: {nm} bolt travel {z.max():.3f} < {MIN_BOLT_TRAVEL}")
        r.check(abs(z[-1]) < 1e-4, f"{label}: {nm} ends with the bolt open (the AK has no hold-open)")
        r.note(f"{nm}: bolt travel {z.max() * 1000:.1f} mm")


def check_sights(g, r, label):
    from check_glb import check_sights as common
    common(g, r, label)


def _in_subtree(g, i, roots):
    while True:
        if g.nodes[i].get("name") in roots:
            return True
        if i not in g.parent:
            return False
        i = g.parent[i]


def check_ads(g, r, label):
    """Section 5 at the runtime projection: the viewmodel is moved so the aim node sits on the camera (54 deg
    vertical FOV, near plane 0.02, rest pose). No gun vertex in front of the eye may come closer than ADS_CLEAR,
    and the aiming point (top of the front post for the irons, the reticle for the red dot) must land on the
    screen centre within ADS_PX pixels at 1920x1080."""
    W = g.world_matrices({})
    focal = 540.0 / np.tan(np.radians(FOV_Y / 2))         # pixels per unit of x/-z and y/-z on 1080 lines
    for aim, not_fitted in ADS_SIGHTS.items():
        inv = np.linalg.inv(W[g.names[aim]])
        near, point = (np.inf, ""), None
        for i, n in enumerate(g.nodes):
            if "mesh" not in n or n["name"] == "fx_shell" or _in_subtree(g, i, not_fitted):
                continue
            M = inv @ W[i]
            v = np.concatenate([p["positions"] for p in g.mesh_primitives(n["mesh"])]) @ M[:3, :3].T + M[:3, 3]
            front = v[v[:, 2] < 0.0]
            if len(front):
                d = np.linalg.norm(front, axis=1)
                k = int(np.argmin(d))
                if d[k] < near[0]:
                    near = (float(d[k]), n["name"])
            if aim == "aim_iron" and n["name"] == "sight_iron":
                post = v[v[:, 2] <= v[:, 2].min() + 0.01]          # forward-most part of the irons: the front post
                point = post[post[:, 1] >= post[:, 1].max() - 1e-6].mean(0)
        r.check(near[0] >= ADS_CLEAR, f"{label}: at {aim} ADS {near[1]} is {near[0]:.3f} m from the eye "
                                      f"(needs >= {ADS_CLEAR})")
        if r.check(point is not None and point[2] < 0, f"{label}: {aim} has no aiming point in front of the eye"):
            px = focal * point[:2] / -point[2]
            r.check(bool(np.all(np.abs(px) <= ADS_PX)), f"{label}: {aim} aiming point {np.round(px, 2)} px off centre")
            what = "front post tip" if aim == "aim_iron" else "reticle"
            r.note(f"ADS {aim}: nearest gun part {near[1]} {near[0]:.3f} m; {what} {-point[2]:.3f} m ahead at "
                   f"({px[0]:+.2f}, {px[1]:+.2f}) px from centre (1920x1080, {FOV_Y:.0f} deg)")


def check_framing(path, r, label, margin=0.03):
    """Receiver, barrel, handguard, sights, bolt and attachments inside the frame at the runtime FOV (16:9 and 4:3),
    with `margin` (fraction of screen height) at the bottom and right for hip / idle / fire / walk; stock, pistol
    grip and magazines may run off the bottom-right edge; sprint loops are only reported."""
    import framing
    clips = FRAMED + ("sprint", "tac_sprint")
    for (name, asp), ((left, top, right, bottom), _) in framing.report(path, clips, exclude=FREE).items():
        aspect = framing.ASPECTS[asp]
        m_right, m_bottom = (1 - right) * aspect, 1 - bottom
        if name in FRAMED:
            r.check(left >= 0 and top >= 0 and m_right >= margin - 1e-4 and m_bottom >= margin - 1e-4,
                    f"{label}: {name} at {asp} leaves the frame (box {left:.3f},{top:.3f}..{right:.3f},{bottom:.3f})")
        r.note(f"framing {name:10s} {asp:4s}: x {left:.3f}..{right:.3f} y {top:.3f}..{bottom:.3f} "
               f"(right margin {m_right:.3f}H, bottom margin {m_bottom:.3f}H){'' if name in FRAMED else ' [report only]'}")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_sights(g, r, label)
    check_ads(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    check_framing(path, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.note(f"{label} triangles (all attachments, excluding fx_shell): {tris}")
