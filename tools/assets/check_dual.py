"""Section 26 checks of dual-gun files (usp_dual.glb and the Cerberus skin's usp_dual_cerberus.glb), used by
check_glb.py: every node of the right gun and its `_L` twin under `gun_L` (the twin the mirror image at rest), no
sights to aim with, both hands on their own guns, the clip set with its events (each shot clip moves only its gun),
the slide lock on both slides, eye distance with the attachments on both guns, framing of both guns and the
crosshair area, curve rate and the pistol budget."""
import os

import numpy as np

import check_attach
import check_glb as cg
from check_glb import CAM_ROLL, CAM_TURN
import loadouts as lo
from glbread import GLTF

SUFFIX = "_L"
GUN_PARENTS = {   # right gun node -> parent (the twin's parent is the parent's twin)
    "gun": "viewmodel", "frame": "gun", "slide": "gun", "sight_iron": "slide", "barrel": "gun", "hammer": "gun",
    "trigger": "gun", "slide_stop": "gun", "mag": "gun", "mag_standard": "mag", "mag_extended": "mag",
    "muzzle": "gun", "ejection": "gun", "mount_laser": "gun", "mount_barrel": "gun",
}
RIGHT_ONLY = {"mount_charm": "gun"}
MESHES = {"frame", "slide", "sight_iron", "barrel", "hammer", "trigger", "slide_stop", "mag_standard", "mag_extended"}
FORBIDDEN = ("aim_iron", "mount_optic", "mount_stock")         # dual guns do not aim and carry no stock
MATERIALS = {
    "usp_dual": {"polymer", "grip", "slide", "barrel", "steel_dark", "sight_dots", "mag", "mag_base", "brass"},
    "usp_dual_cerberus": {"cerberus_body", "cerberus_trim", "cerberus_grip", "barrel", "steel_dark", "mag", "mag_base",
                          "brass"},
}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.5, 0.8), "fire": (0.15, 0.3), "fire_L": (0.15, 0.3),
             "fire_last": (0.15, 0.3), "fire_last_L": (0.15, 0.3), "fire_both": (0.15, 0.3), "reload": (2.0, 2.8),
             "reload_empty": (2.4, 3.2), "inspect": (3.0, 4.5), "walk": (0.6, 1.6), "sprint": (0.5, 0.9),
             "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
EVENTS = {"mag_release", "mag_out", "mag_in", "slide_back", "slide_forward", "slide_release", "inspect_move", "equip",
          "hammer"}
REQUIRED_EVENTS = {"draw": {"equip"}, "reload": {"mag_release", "mag_out", "mag_in"},
                   "reload_empty": {"mag_release", "mag_out", "mag_in", "slide_release", "slide_forward"},
                   "inspect": {"inspect_move"}}
SHOTS = {"fire": ("gun",), "fire_last": ("gun",), "fire_L": ("gun_L",), "fire_last_L": ("gun_L",),
         "fire_both": ("gun", "gun_L")}
MIRROR = np.diag([-1.0, 1.0, 1.0, 1.0])
BUDGET = 5000                    # section 24: triangles of one pistol as drawn (one magazine fitted)
HAND_REACH = 0.075               # wrist within this of its gun's grip pivot while holding it
CROSSHAIR = 0.15                 # section 2: half side of the crosshair square, in screen heights
MUZZLE_TIP = 0.04                # the last few centimetres of the barrel may reach into it
FRAMED = ("rest", "idle", "fire", "fire_L", "fire_both", "walk")


def claims(path, names):
    return "gun_L" in names


def _rest_world(g):
    return g.world_matrices({})


def check_nodes(g, r, label):
    for nm, parent in list(GUN_PARENTS.items()) + list(RIGHT_ONLY.items()):
        twins = [(nm, parent)] + ([(nm + SUFFIX, parent + SUFFIX if parent != "viewmodel" else parent)]
                                  if nm in GUN_PARENTS else [])
        for name, par in twins:
            if not r.check(name in g.names, f"{label}: missing node {name}"):
                continue
            i = g.names[name]
            p = g.parent.get(i)
            r.check(p is not None and g.nodes[p]["name"] == par, f"{label}: {name} must hang under {par}")
            base = name[:-len(SUFFIX)] if name.endswith(SUFFIX) else name
            r.check(("mesh" in g.nodes[i]) == (base in MESHES), f"{label}: {name} mesh presence")
    r.check("mount_charm" + SUFFIX not in g.names, f"{label}: the charm hangs on the right gun only")
    for nm in FORBIDDEN:
        for name in (nm, nm + SUFFIX):
            r.check(name not in g.names, f"{label}: {name} must not exist (dual guns do not aim / carry no stock)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest not identity")
    r.check("fx_shell" in g.names and g.names["fx_shell"] not in g.parent, f"{label}: fx_shell must be a root")
    # the left gun is the mirror image of the right one at rest, part for part
    W = _rest_world(g)
    worst = 0.0
    for nm in GUN_PARENTS:
        if nm in ("ejection",):                    # the case throw is turned to the left gun's outer side instead
            continue
        a, b = W[g.names[nm]], W[g.names[nm + SUFFIX]]
        worst = max(worst, float(np.abs(MIRROR @ a @ MIRROR - b).max()))
        if nm in MESHES:
            # vertex positions (UV seams split vertices differently on the mirrored mesh)
            pa = np.unique(np.round(np.concatenate(cg.mesh_points(g, g.nodes[g.names[nm]]["mesh"])) * [-1, 1, 1], 5), axis=0)
            pb = np.unique(np.round(np.concatenate(cg.mesh_points(g, g.nodes[g.names[nm + SUFFIX]]["mesh"])), 5), axis=0)
            r.check(len(pa) == len(pb) and np.allclose(pa, pb, atol=2e-5),
                    f"{label}: mesh of {nm + SUFFIX} is not the mirror of {nm}'s")
    r.check(worst < 1e-4, f"{label}: the left gun's rest pose is not the mirror of the right's (off by {worst:.5f})")
    for gun in ("gun", "gun_L"):
        ej = W[g.names["ejection" + ("" if gun == "gun" else SUFFIX)]][:3, 0]
        gx = W[g.names[gun]][:3, 0] * (1 if gun == "gun" else -1)        # the gun's outer side
        r.check(np.dot(ej, gx) > 0.5, f"{label}: {gun} throws its cases inwards")
        bore = -W[g.names["muzzle" + ("" if gun == "gun" else SUFFIX)]][:3, 2]
        for m in ("mount_laser", "mount_barrel"):
            d = -W[g.names[m + ("" if gun == "gun" else SUFFIX)]][:3, 2]
            r.check(np.dot(d, bore) / np.linalg.norm(d) > 0.9999, f"{label}: {m} of {gun} not parallel to the bore")
    r.note(f"left gun mirrors the right one within {worst:.1e}")


def check_materials(g, r, label, name):
    names = {m.get("name") for m in g.json.get("materials", [])}
    want = MATERIALS.get(name, MATERIALS["usp_dual"])
    r.check(names == want, f"{label}: materials {sorted(names)} != {sorted(want)}")


def check_animations(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    cam = g.names["camera"]
    vm = g.names["viewmodel"]
    for nm, (lo_, hi) in DURATIONS.items():
        if not r.check(nm in anims, f"{label}: animation {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo_ - 1e-4 <= dur <= hi + 1e-4, f"{label}: {nm} duration {dur:.3f} outside [{lo_}, {hi}]")
        targets = set()
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            targets.add(g.nodes[node]["name"])
            r.check(node != vm, f"{label}: {nm} animates viewmodel")
            s = a["samplers"][ch["sampler"]]
            r.check(s.get("interpolation", "LINEAR") in ("LINEAR", "STEP"), f"{label}: {nm} interpolation")
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"{label}: {nm} key times")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
                r.check(node != cam, f"{label}: {nm} must not animate the camera")
            if node == cam:
                eul = np.array([cg.euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera rotation exceeds limits")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
            if g.nodes[node]["name"] in ("mag", "mag_L") and path == "scale":
                r.check(s.get("interpolation") == "STEP" and np.all(np.isin(np.round(vs, 6), [0.0, 1.0])),
                        f"{label}: {nm} magazine scale must be STEP 0/1")
        if nm in SHOTS:                            # a shot clip moves its own gun(s) and leaves the other alone
            for gun in ("gun", "gun_L"):
                moves = gun in targets
                r.check(moves == (gun in SHOTS[nm]), f"{label}: {nm} {'must' if gun in SHOTS[nm] else 'must not'} "
                                                     f"animate {gun}")
        ev = a.get("extras", {}).get("events", [])
        for e in ev:
            r.check(e.get("name") in EVENTS and 0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} bad event {e}")
        have = {e["name"] for e in ev}
        r.check(REQUIRED_EVENTS.get(nm, set()) <= have, f"{label}: {nm} missing events {REQUIRED_EVENTS.get(nm, set()) - have}")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels"
               + (", events " + ", ".join(f"{e['name']}@{e['time']:.2f}" for e in ev) if ev else ""))
    return anims


def check_slides(g, r, label, anims):
    """Both slides blow back in their shot clips and stay back at the end of fire_last / fire_last_L."""
    for clip, slide in (("fire", "slide"), ("fire_L", "slide_L"), ("fire_last", "slide"), ("fire_last_L", "slide_L")):
        a = anims.get(clip)
        if a is None:
            continue
        i = g.names[slide]
        rest = np.array(g.nodes[i].get("translation", [0, 0, 0]))
        dur = g.duration(a)
        zs = [g.sample(a, t).get(i, {}).get("translation", rest)[2] - rest[2] for t in np.linspace(0, dur, 41)]
        r.check(max(zs) > 0.02, f"{label}: {slide} does not blow back in {clip}")
        if clip.startswith("fire_last"):
            r.check(zs[-1] > 0.02, f"{label}: {slide} does not stay locked back after {clip}")


def check_hands(g, r, label, anims):
    """Each wrist stays at its own gun's grip while the gun is held (rest, idle, shots, walk)."""
    for clip in ("rest", "idle", "fire", "fire_L", "fire_both", "walk", "sprint", "tac_sprint"):
        a = None if clip == "rest" else anims.get(clip)
        dur = 0.0 if a is None else g.duration(a)
        worst = 0.0
        for t in np.linspace(0, dur, 13):
            W = g.world_matrices(g.sample(a, t) if a is not None else {})
            for side, gun in (("R", "gun"), ("L", "gun_L")):
                d = float(np.linalg.norm(W[g.names[f"arm_{side}_hand"]][:3, 3] - W[g.names[gun]][:3, 3]))
                worst = max(worst, d)
        r.check(worst <= HAND_REACH, f"{label}: in {clip} a hand leaves its gun's grip ({worst:.3f} m)")


def check_framing(path, g, r, label, margin=0.03):
    import framing
    free = ("mag_standard", "mag_extended", "mag_standard_L", "mag_extended_L", "charm_grass")
    t = np.tan(np.radians(cg.FOV_Y) / 2)
    for gun, side in (("gun", "right"), ("gun_L", "left")):
        rep = framing.report(path, FRAMED + ("sprint", "tac_sprint"), root=gun, exclude=free)
        for (name, asp), ((left, top, right, bottom), _) in rep.items():
            aspect = framing.ASPECTS[asp]
            edge = (1 - right) * aspect if side == "right" else left * aspect
            if name in FRAMED:
                r.check(edge >= margin - 1e-4 and 1 - bottom >= margin - 1e-4 and top >= 0,
                        f"{label}: {gun} {name} at {asp} leaves the frame ({left:.3f},{top:.3f}..{right:.3f},{bottom:.3f})")
            if asp == "16:9" and name in ("rest", "fire_both", "sprint"):
                r.note(f"framing {gun:5s} {name:10s} {asp}: x {left:.3f}..{right:.3f} y {top:.3f}..{bottom:.3f}")
    # the crosshair square stays clear (muzzle tips excepted) at the hip and in the carries
    for clip in ("rest", "idle", "fire", "fire_L", "fire_both", "walk", "sprint", "tac_sprint"):
        a = None if clip == "rest" else g.animation(clip)
        dur = 0.0 if a is None else g.duration(a)
        worst = 0
        for tt in np.linspace(0, dur, 9):
            W = g.world_matrices(g.sample(a, tt) if a is not None else {})
            for gun in ("gun", "gun_L"):
                sfx = "" if gun == "gun" else SUFFIX
                muzzle = W[g.names["muzzle" + sfx]][:3, 3]
                pts = framing.weapon_points(g, a, tt, gun, exclude=(), loadout="default")   # muzzle devices may reach in
                pts = pts[np.linalg.norm(pts - muzzle, axis=1) > MUZZLE_TIP]
                pts = pts[-pts[:, 2] > 0.05]
                d = -pts[:, 2]
                inside = (np.abs(pts[:, 0] / d / t) < CROSSHAIR * 2) & (np.abs(pts[:, 1] / d / t) < CROSSHAIR * 2)
                worst = max(worst, int(inside.sum()))
        r.check(worst == 0, f"{label}: {clip} puts {worst} weapon vertices into the crosshair area")


def check(path, arms, r):
    name = os.path.basename(path)[:-4]
    label = name
    g = GLTF(path)
    cg.check_container(g, r, label)
    cg.check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label, name)
    anims = check_animations(g, r, label)
    check_slides(g, r, label, anims)
    check_hands(g, r, label, anims)
    cg.check_hand_joints(g, r, label)
    cg.check_curve_rate(g, r, label)
    cg.check_eye_distance(g, arms, r, anims, root="gun", label=label)
    lg = lo.load(path)
    for m in lo.MOUNTS_OF.get(name, ()):
        r.check(m in lg.names and "mesh" not in lg.nodes[lg.names[m]], f"{label}: empty mount node {m} missing")
    for build in check_attach.BUILDS:
        check_attach._eye_distance(lg, r, label, build)
    check_framing(path, lg, r, label)
    tris = {n["name"]: sum(len(p["indices"]) for p in g.mesh_primitives(n["mesh"])) for n in g.nodes if "mesh" in n}
    drawn = sum(v for k, v in tris.items() if k in MESHES and k != "mag_extended")
    r.check(drawn <= BUDGET, f"{label}: {drawn} triangles per gun (budget {BUDGET})")
    r.note(f"{label}: {drawn} triangles per gun with the standard magazine, {sum(tris.values())} in the file")
