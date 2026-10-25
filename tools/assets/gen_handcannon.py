"""Builds models/gun/handcannon.glb (VIEWMODEL_SPEC section 43): the 40 mm grenade pistol on the P9's contract — the P9
rig, idle, draw and locomotion re-run against handcannon_model (variants.load, as the Desert Eagle) with the launcher's
differences patched in: the barrel node pivots about the hinge (its rotation is part of the pose), the round sits in
the chamber with the gun's axes, the spent case is thrown from where the open chamber's mouth is, there is no slide
travel, and the long, heavy gun is held a little lower, further right and back. Its own fire, reload and inspect clips
are handcannon_clips."""
import numpy as np

import armrig
import handcannon_clips as hc
import handcannon_model as hm
import meshkit as mk
import variants
from glb import GLB
from gen_p9 import bake_clip
from vmath import norm, quat_axis_angle, quat_identity, quat_rotate

# name: (base rgba, metallic, roughness, extra) -- the P9's material names (section 6): `polymer` is the steel receiver
# and frame here, `slide` the top rail, `barrel` the olive aluminium tube, `mag` / `mag_base` the round's case and
# projectile; the texture sets (pbr_models_handcannon) give them their look
MATERIALS = {
    "polymer": ((0.050, 0.050, 0.054, 1), 0.80, 0.42, {}),
    "grip": ((0.035, 0.035, 0.037, 1), 0.0, 0.90, {}),
    "slide": ((0.040, 0.040, 0.043, 1), 0.70, 0.45, {}),
    "barrel": ((0.110, 0.125, 0.065, 1), 0.0, 0.60, {}),
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "sight_dots": ((0.95, 0.95, 0.92, 1), 0.0, 0.40, {"emissive": (0.65, 0.65, 0.60)}),
    "mag": ((0.62, 0.62, 0.64, 1), 1.0, 0.38, {}),
    "mag_base": ((0.110, 0.125, 0.065, 1), 0.0, 0.55, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
GUN_SHIFT = np.array([0.010, -0.004, 0.016])       # hip pose: lower, further right and back than the P9
EJECT_DIR = norm((0.62, 0.62, 0.48))                # the spent case springs right, up and back out of the chamber


def _patch_rig(rig):
    rig.MOUNTS["mount_optic"] = ("slide", np.array([0.0, hm.OPTIC_Y, hm.OPTIC_Z]), None, None)
    rig.SLIDE_LOCK = 0.0
    rig.GUN_T = rig.GUN_T + GUN_SHIFT
    rig.MAG_Q = quat_identity()
    rig.NODES["barrel"] = ("gun", rig.G(hm.HINGE))
    # the case leaves from the open chamber's mouth
    hinge = rig.G(hm.HINGE)
    mouth = hinge + quat_rotate(quat_axis_angle((1, 0, 0), hm.OPEN), rig.G(hm.MAG_ORIGIN) - hinge)
    rig.NODES["ejection"] = ("gun", mouth + np.array([0.0, 0.006, 0.004]))
    rig.EJECT_DIR = EJECT_DIR
    base = rig.Pose

    class Pose(base):
        """The P9's pose plus the barrel's opening about the hinge (degrees, negative = muzzle down)."""

        def __init__(self):
            super().__init__()
            self.barrel = 0.0

        def solve(self):
            trs, info = super().solve()
            trs["barrel"] = {"rotation": quat_axis_angle((1, 0, 0), self.barrel)}
            return trs, info

    rig.Pose = Pose


_MODULES = None


def modules():
    """The P9 rig and clip modules bound to the hand cannon's geometry (built once)."""
    global _MODULES
    if _MODULES is None:
        _MODULES = variants.load("handcannon__", {"p9_model": hm}, ["p9_rig", "p9_clips", "p9_loco"],
                                 after={"p9_rig": _patch_rig})
    return _MODULES


def rig():
    return modules()["p9_rig"]


def all_clips():
    m = modules()
    c, loco = m["p9_clips"], m["p9_loco"]
    kit = hc.Kit(m["p9_rig"], c)
    import ads_fire
    fire, fire_last = hc.fire_clips(kit, "fire"), hc.fire_clips(kit, "fire_last")
    aim = kit.rig.NODES["slide"][1] + kit.rig.AIM_IRON_T
    held = dict(c.PISTOL_ADS, lateral=0.004, max_turn=0.004)
    return [c.IDLE, c.DRAW, fire, fire_last, ads_fire.aimed(fire, kit.rig, aim, c.IDLE, **held),
            ads_fire.aimed(fire_last, kit.rig, aim, c.IDLE, **held),
            hc.reload_clip(kit, "reload", 2.20), hc.reload_clip(kit, "reload_empty", 2.45), hc.inspect_clip(kit),
            loco.WALK, loco.SPRINT, loco.TAC_SPRINT]


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, origin: m.translated(-np.asarray(origin, float))
    rnd = hm.round_mesh()
    return {
        "frame": hm.frame_mesh(),
        "slide": hm.rail_mesh(),
        "barrel": d(hm.barrel_mesh(), hm.HINGE),
        "sight_iron": d(hm.rear_sight_mesh(), (0, hm.SY1, 0)),
        "hammer": d(hm.hammer_mesh(), hm.HAMMER_PIVOT),
        "trigger": d(hm.trigger_mesh(), hm.TRIGGER_PIVOT),
        "slide_stop": d(hm.slide_stop_mesh(), hm.STOP_PIVOT),
        "mag_standard": rnd,
        "mag_extended": hm.round_mesh(10),   # the contract's node, never shown: one round, one magazine (guns.json)
        "fx_shell": hm.shell_mesh(),
    }


def add_materials(g, materials=MATERIALS):
    for name, (base, metal, rough, ex) in materials.items():
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"))


def build(path, clips=None):
    r = rig()
    clips = all_clips() if clips is None else clips
    g = GLB()
    add_materials(g)
    mesh_idx, tri_counts = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = r.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in ("frame", "slide", "barrel", "hammer", "trigger", "slide_stop", "mag", "muzzle", "ejection"):
        parent, t = r.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = r.MAG_Q
        if name == "ejection":
            rot = r.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "slide":
            g.add_node("sight_iron", "slide", r.NODES["sight_iron"][1], mesh=mesh_idx["sight_iron"])
            g.add_node("aim_iron", "slide", r.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
            g.add_node("mag_extended", "mag", mesh=mesh_idx["mag_extended"])
    for name, (parent, t, rot, scale) in r.MOUNTS.items():          # section 19: attachment mounts
        g.add_node(name, parent, t, rot, scale=scale)
    for side, shoulder in (("R", r.SHOULDER_R), ("L", r.SHOULDER_L)):
        g.add_node(f"arm_{side}", "viewmodel", shoulder, rest[f"arm_{side}"]["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", r.ARM_FORE_T, rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    g.add_node("camera", "viewmodel")
    g.add_node("fx_shell", None, mesh=mesh_idx["fx_shell"], extras={"hidden": True})
    rest_vals = {
        "gun": {"translation": rest["gun"]["translation"], "rotation": rest["gun"]["rotation"]},
        "slide": {"translation": rest["slide"]["translation"]},
        "barrel": {"rotation": rest["barrel"]["rotation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "hammer": {"rotation": rest["hammer"]["rotation"]},
        "slide_stop": {"rotation": rest["slide_stop"]["rotation"]},
        "mag": {"translation": r.NODES["mag"][1], "rotation": r.MAG_Q, "scale": np.ones(3)},
        "arm_R": {"rotation": rest["arm_R"]["rotation"]},
        "arm_R_fore": {"rotation": rest["arm_R_fore"]["rotation"]},
        "arm_L": {"rotation": rest["arm_L"]["rotation"]},
        "arm_L_fore": {"rotation": rest["arm_L_fore"]["rotation"]},
        "camera": {"rotation": quat_identity()},
    }
    for side in ("R", "L"):
        rest_vals.update(armrig.rest_values(rest, side))
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info
