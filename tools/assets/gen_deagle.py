"""Builds models/gun/deagle.glb (VIEWMODEL_SPEC section 48): the Desert Eagle's own shapes on the P9's contract — the
P9 rig and clips re-run against deagle_model (variants.load, as the USP: section 26), with the heavier gun's
differences patched in: a longer slide stroke, the optic plate on the slide, the gun held a little lower and further
right (it is longer), and a far heavier shot — the wrists driven back and the muzzle flipping high before the hands
bring it down."""
import numpy as np

import ads_fire
import armrig
import deagle_model as hm
import meshkit as mk
import variants
from anim import ring
from gen_p9 import bake_clip
from glb import GLB
from vmath import quat_identity

# name: (base rgba, metallic, roughness, extra) -- the P9's material names (section 6): `polymer` is the steel frame
# here and `grip` its rubber wrap; the texture sets (pbr_models_deagle) give them their look
MATERIALS = {
    "polymer": ((0.060, 0.060, 0.064, 1), 0.85, 0.40, {}),
    "grip": ((0.035, 0.035, 0.037, 1), 0.0, 0.90, {}),
    "slide": ((0.060, 0.060, 0.064, 1), 0.85, 0.36, {}),
    "barrel": ((0.062, 0.062, 0.066, 1), 0.85, 0.34, {}),
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "sight_dots": ((0.95, 0.95, 0.92, 1), 0.0, 0.40, {"emissive": (0.65, 0.65, 0.60)}),
    "mag": ((0.055, 0.055, 0.058, 1), 0.60, 0.50, {}),
    "mag_base": ((0.045, 0.045, 0.047, 1), 0.0, 0.75, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
SLIDE_STROKE = 0.040            # the slide's travel (P9: 34 mm)
GUN_SHIFT = np.array([0.006, -0.006, 0.010])     # hip pose: the long gun a little lower, further right and back


def _patch_rig(rig):
    rig.MOUNTS["mount_optic"] = ("slide", np.array([0.0, hm.OPTIC_Y, hm.OPTIC_Z]), None, None)
    rig.SLIDE_LOCK = SLIDE_STROKE
    rig.GUN_T = rig.GUN_T + GUN_SHIFT


def _patch_clips(clips):
    """The .50's shot: the clip's kick is the hands' reaction (the runtime adds the root recoil); a pistol twice as
    heavy firing a round three times as strong drives the wrists back 11 mm and flips the muzzle 7 degrees, slower
    (the arms, not the wrists, absorb it), the support hand clamping hard; the slide slamming home is a heavier knock."""
    V, until = clips.V, clips.until

    def fire_kick(t, last):
        fade = until(t, 0.200, 0.05)
        back = ring(t, 0.0, 0.0110, 7.5, 0.48)
        rise = ring(t, 0.005, 0.0042, 5.5, 0.45)
        flip = ring(t, 0.003, V(0.9, 7.0, -1.8), 5.0, 0.42)
        off = V(0.0009 * float(rise) / 0.0042, float(rise), float(back))
        if not last:
            off = off + ring(t, clips.T_HOME_F, V(0.0, -0.0008, -0.0016), 12.0, 0.4)
            flip = flip + ring(t, clips.T_HOME_F, V(0.10, -0.90, 0.20), 11.0, 0.4)
        view = ring(t, 0.003, V(0.95, 0.10, 0.18), 5.5, 0.50)
        return off * fade, flip * fade, view * fade

    clips.fire_kick = fire_kick
    # aimed (section 31.5): the heavier flip must not drag the sight picture off through a magnified optic
    aim = clips.rig.NODES["slide"][1] + clips.rig.AIM_IRON_T
    held = dict(clips.PISTOL_ADS, lateral=0.004, max_turn=0.004)
    clips.FIRE_ADS = ads_fire.aimed(clips.FIRE, clips.rig, aim, clips.IDLE, **held)
    clips.FIRE_LAST_ADS = ads_fire.aimed(clips.FIRE_LAST, clips.rig, aim, clips.IDLE, **held)


_MODULES = None


def modules():
    """The P9 rig and clip modules bound to the Desert Eagle's geometry (built once)."""
    global _MODULES
    if _MODULES is None:
        _MODULES = variants.load("deagle__", {"p9_model": hm}, ["p9_rig", "p9_clips", "p9_clips_long", "p9_loco"],
                                 after={"p9_rig": _patch_rig, "p9_clips": _patch_clips})
    return _MODULES


def rig():
    return modules()["p9_rig"]


def all_clips():
    m = modules()
    c, long, loco = m["p9_clips"], m["p9_clips_long"], m["p9_loco"]
    return [c.IDLE, c.DRAW, c.FIRE, c.FIRE_LAST, c.FIRE_ADS, c.FIRE_LAST_ADS, long.RELOAD, long.RELOAD_EMPTY,
            long.INSPECT, loco.WALK, loco.SPRINT, loco.TAC_SPRINT]


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, origin: m.translated(-np.asarray(origin, float))
    mag = hm.mag_mesh()
    return {
        "frame": hm.frame_mesh(),
        "slide": hm.slide_mesh(),
        "barrel": hm.barrel_mesh(),
        "sight_iron": d(hm.rear_sight_mesh(), (0, hm.SY1, 0)),
        "hammer": d(hm.hammer_mesh(), hm.HAMMER_PIVOT),
        "trigger": d(hm.trigger_mesh(), hm.TRIGGER_PIVOT),
        "slide_stop": d(hm.slide_stop_mesh(), hm.STOP_PIVOT),
        "mag_standard": mag,
        "mag_extended": mag.copy(),          # the contract's node; the gun has one magazine (guns.json)
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
