"""Bolt-action sniper GLB checks (VIEWMODEL_SPEC section 23: the AWP), used by check_glb.py for weapon files with a
`bolt_handle` node. Like check_rifle, plus the bolt cycle (handle turned up, slid back and closed again in fire /
reload_empty, untouched in fire_last), section 21 regions on every material and no iron sights."""
import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       euler_deg)
from glbread import GLTF, quat_mat

STATIC = ("receiver", "barrel", "stock", "grip")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "bolt": "gun", "bolt_handle": "bolt",
    "trigger": "gun", "muzzle_brake": "gun", "mag": "gun", "mag_standard": "mag", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "fx_shell": None, "mount_optic": "gun", "mount_barrel": "gun", "mount_charm": "gun", "mount_underbarrel": "gun",
}
MESH_NODES = set(STATIC) | {"bolt", "bolt_handle", "trigger", "muzzle_brake", "mag_standard", "fx_shell"}
EMPTY_NODES = {"viewmodel", "mag", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore", "camera",
               "mount_optic", "mount_barrel", "mount_charm", "mount_underbarrel"}
REMOVED_NODES = ("sight_reddot", "reddot_reticle", "aim_reddot", "laser", "laser_emitter", "aim_iron", "sight_iron")
MATERIALS = {"receiver", "barrel", "bolt", "steel_dark", "stock", "grip", "pad", "mag", "mag_base", "brass"}
REGIONS = {"body", "barrel", "magazine", "stock", "grip", "handguard", "optic", "laser", "detail", "blade", "handle",
           "hardware", "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.7, 1.1), "fire": (1.1, 1.5), "fire_last": (0.2, 0.4),
             "reload": (2.6, 3.4), "reload_empty": (3.3, 4.1), "inspect": (3.5, 4.5), "walk": (0.6, 1.6),
             "sprint": (0.5, 0.8), "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
BOLT_EVENTS = {"awp.bolt_up", "awp.bolt_back", "awp.bolt_forward", "awp.bolt_down"}
EVENT_NAMES = {"mag_release", "mag_out", "mag_in", "inspect_move", "equip"} | BOLT_EVENTS
REQUIRED_EVENTS = {"draw": {"equip"}, "fire": BOLT_EVENTS, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in"} | BOLT_EVENTS, "inspect": {"inspect_move"}}
FRAMED = ("rest", "idle", "fire", "fire_last", "walk")
FREE = ("stock", "grip", "receiver", "bolt", "bolt_handle", "trigger", "mag_standard", "tactical_stock",
        "charm_grass")   # may run off the bottom / right edge (the action and stock sit low right, COD-style)
MIN_TRAVEL, MIN_LIFT = 0.09, 55.0


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
        r.check(nm not in g.names, f"{label}: {nm} must not be in the file (section 23: no iron sights; section 19)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    # the bolt turns about the bore: its origin sits on the bore axis (muzzle line)
    W = g.world_matrices({})
    o, m = W[g.names["bolt_handle"]][:3, 3], W[g.names["muzzle"]]
    bore = -m[:3, 2] / np.linalg.norm(m[:3, 2])
    off = (o - m[:3, 3]) - bore * np.dot(o - m[:3, 3], bore)
    r.check(np.linalg.norm(off) < 0.0015, f"{label}: bolt_handle origin {np.linalg.norm(off) * 1000:.1f} mm off the bore")


def check_materials(g, r, label):
    names = [m.get("name") for m in g.json.get("materials", [])]
    for nm in names:
        r.check(nm in MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(names):
        r.check(False, f"{label}: material {nm} missing")
    for m in g.json.get("materials", []):
        region = m.get("extras", {}).get("region")
        r.check(region in REGIONS, f"{label}: material {m.get('name')} has no valid extras.region ({region})")
    r.note("regions: " + ", ".join(f"{m['name']}={m.get('extras', {}).get('region')}" for m in g.json["materials"]))


def _track(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            return g.accessor(a["samplers"][ch["sampler"]]["output"]).astype(np.float64)
    return None


def _bolt(g, a):
    """(travel per key (+Z, metres), max off-axis drift, lift per key (degrees about +Z)) of a clip."""
    rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
    t = _track(g, a, "bolt", "translation")
    z = np.zeros(1) if t is None else t[:, 2] - rest[2]
    side = 0.0 if t is None else float(np.abs(t[:, :2] - rest[:2]).max())
    q = _track(g, a, "bolt_handle", "rotation")
    lift = np.zeros(1)
    axis_drift = 0.0
    if q is not None:
        lift = np.degrees(2 * np.arctan2(q[:, 2], q[:, 3]))
        axis_drift = float(np.abs(q[:, :2]).max())
    return z, side, lift, axis_drift


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
                        f"{label}: {nm} camera beyond +-1.5/+-3 deg (max {np.abs(eul).max(0).round(2)})")
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
            if BOLT_EVENTS <= have:                         # up, back, forward, down in this order
                at = {e["name"]: e["time"] for e in ev if e["name"] in BOLT_EVENTS}
                order = [at[n] for n in ("awp.bolt_up", "awp.bolt_back", "awp.bolt_forward", "awp.bolt_down")]
                r.check(order == sorted(order), f"{label}: {nm} bolt events out of order")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    # the bolt: slides along the bore only, turns about it only, cycles fully in fire / reload_empty, ends closed
    for nm in ("fire", "fire_last", "reload_empty", "inspect"):
        if nm not in anims:
            continue
        z, side, lift, drift = _bolt(g, anims[nm])
        r.check(side < 1e-6, f"{label}: {nm} moves the bolt off the bore (x/y)")
        r.check(drift < 1e-6, f"{label}: {nm} turns the bolt handle about another axis than the bore")
        r.check(z.min() > -1e-4, f"{label}: {nm} pushes the bolt forward of its rest")
        r.check(abs(z[-1]) < 1e-4 and abs(lift[-1]) < 0.05, f"{label}: {nm} ends with the bolt open")
        if nm in ("fire", "reload_empty"):
            r.check(z.max() >= MIN_TRAVEL, f"{label}: {nm} bolt travel {z.max():.3f} < {MIN_TRAVEL}")
            r.check(lift.max() >= MIN_LIFT, f"{label}: {nm} handle lifted only {lift.max():.0f} deg")
        if nm == "fire_last":
            r.check(z.max() < 1e-6 and lift.max() < 0.05, f"{label}: fire_last must not cycle the bolt (section 23)")
        r.note(f"{nm}: bolt travel {z.max() * 1000:.1f} mm, handle lift {lift.max():.0f} deg")


def check_bolt_locking(g, r, label):
    """In every clip the bolt slides only while its handle is turned up (a locked bolt cannot move)."""
    for a in g.json.get("animations", []):
        dur = g.duration(a)
        bad = 0
        for t in np.arange(0, dur + 1e-9, 1 / 120):
            s = g.sample(a, t)
            b = s.get(g.names["bolt"], {}).get("translation")
            q = s.get(g.names["bolt_handle"], {}).get("rotation")
            if b is None:
                continue
            rest = np.asarray(g.nodes[g.names["bolt"]].get("translation", [0, 0, 0]), float)
            lift = 0.0 if q is None else np.degrees(2 * np.arctan2(q[2], q[3]))
            if b[2] - rest[2] > 0.004 and lift < 0.9 * MIN_LIFT:
                bad += 1
        r.check(bad == 0, f"{label}: {a['name']} slides the bolt while the handle is down ({bad} samples)")


def check_framing(path, r, label, margin=0.03):
    """Barrel, brake and attachments (largest builds, the sniper scope included) inside the frame at the runtime FOV
    with a `margin` at the bottom and right in hip / idle / fire / walk; action, stock, grip and magazine may run off
    the bottom-right edge; sprint loops are only reported."""
    import framing
    clips = FRAMED + ("sprint", "tac_sprint")
    for loadout in ("largest", "sniper_full"):
        for (name, asp), ((left, top, right, bottom), _) in framing.report(path, clips, exclude=FREE,
                                                                          loadout=loadout).items():
            aspect = framing.ASPECTS[asp]
            m_right, m_bottom = (1 - right) * aspect, 1 - bottom
            if name in FRAMED:
                r.check(left >= 0 and top >= 0 and m_right >= margin - 1e-4 and m_bottom >= margin - 1e-4,
                        f"{label}: {name} at {asp} ({loadout}) leaves the frame "
                        f"(box {left:.3f},{top:.3f}..{right:.3f},{bottom:.3f})")
            r.note(f"framing {loadout:11s} {name:10s} {asp:4s}: x {left:.3f}..{right:.3f} y {top:.3f}..{bottom:.3f} "
                   f"(right margin {m_right:.3f}H, bottom margin {m_bottom:.3f}H)"
                   f"{'' if name in FRAMED else ' [report only]'}")


def check_muzzle(g, r, label):
    """The muzzle stays out of the crosshair area at the hip (rest pose): right of / below a box of 0.12 x 0.12
    screen heights around the centre (16:9, 54 deg)."""
    W = g.world_matrices({})
    p = W[g.names["muzzle"]][:3, 3]
    t = np.tan(np.radians(27.0))
    x, y = p[0] / -p[2] / t, p[1] / -p[2] / t          # in screen heights / 2 units
    r.check(abs(x) > 0.12 or abs(y) > 0.12, f"{label}: hip muzzle in the crosshair area ({x:+.3f}, {y:+.3f})")
    r.note(f"hip muzzle at ({x / 2:+.3f}, {-y / 2:+.3f}) screen heights from the centre")


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    check_clips(g, r, label)
    check_bolt_locking(g, r, label)
    from check_glb import check_sights
    check_sights(g, r, label)
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    check_muzzle(g, r, label)
    check_framing(path, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    r.note(f"{label} triangles (excluding attachments and fx_shell): {tris}")
