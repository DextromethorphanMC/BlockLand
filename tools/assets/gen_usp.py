"""Builds models/gun/usp.glb (VIEWMODEL_SPEC section 26): the USP's own shapes on the P9's contract — the P9 rig and
clips re-run against usp_model (variants.load), so node layout, hand anchors and clips follow the USP's dimensions."""
import numpy as np

import armrig
import meshkit as mk
import textures as tx
import usp_model as um
import usp_parts as up
import variants
from gen_p9 import bake_clip
from glb import GLB
from vmath import quat_identity

# name: (base rgba, metallic, roughness, extra) -- the P9's material names (section 6), the USP's colours
MATERIALS = {
    "polymer": ((0.042, 0.043, 0.045, 1), 0.0, 0.78, {}),
    "grip": ((0.044, 0.045, 0.047, 1), 0.0, 0.92, {"texture": "stipple"}),
    "slide": ((0.050, 0.050, 0.054, 1), 0.75, 0.42, {"texture": "brushed"}),
    "barrel": ((0.070, 0.070, 0.074, 1), 0.85, 0.38, {}),
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "sight_dots": ((0.92, 1.0, 0.90, 1), 0.0, 0.40, {"emissive": (0.55, 0.70, 0.52)}),
    "mag": ((0.040, 0.040, 0.043, 1), 0.15, 0.68, {}),
    "mag_base": ((0.045, 0.045, 0.047, 1), 0.0, 0.75, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
TEXTURES = {"stipple": lambda: tx.stipple(256), "brushed": lambda: tx.brushed(256)}


def _patch_rig(rig):
    # the optic cut sits on the USP's taller slide, just in front of its rear sight
    rig.MOUNTS["mount_optic"] = ("slide", np.array([0.0, um.OPTIC_Y, um.OPTIC_Z]), None, None)


_MODULES = None


def modules():
    """The P9 rig and clip modules bound to the USP's geometry (built once)."""
    global _MODULES
    if _MODULES is None:
        _MODULES = variants.load("usp__", {"p9_model": um}, ["p9_rig", "p9_clips", "p9_clips_long", "p9_loco"],
                                 after={"p9_rig": _patch_rig})
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
    return {
        "frame": um.frame_mesh(),
        "slide": um.slide_mesh(),
        "barrel": um.barrel_mesh(),
        "sight_iron": d(up.iron_sights_mesh(), (0, um.SY1, 0)),
        "hammer": d(up.hammer_mesh(), um.HAMMER_PIVOT),
        "trigger": d(up.trigger_mesh(), um.TRIGGER_PIVOT),
        "slide_stop": d(up.slide_stop_mesh(), um.STOP_PIVOT),
        "mag_standard": up.mag_mesh(False),
        "mag_extended": up.mag_mesh(True),
        "fx_shell": up.shell_mesh(1.25),
    }


def add_materials(g, materials=MATERIALS):
    for name, (base, metal, rough, ex) in materials.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png)


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
