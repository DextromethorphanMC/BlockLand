"""Builds models/gun/scar.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 40).

python -I tools/assets/gen_scar.py   (or build_all.py scar)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import scar_model as sm                            # noqa: E402
import scar_rig as rig                             # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- fallbacks; the texture sets (pbr_models_scar.py) carry the look
MATERIALS = {
    "upper": ((0.42, 0.33, 0.22, 1), 0.0, 0.55),          # FDE hard-anodised aluminium (receiver section)
    "handguard": ((0.42, 0.33, 0.22, 1), 0.0, 0.55),      # FDE hard-anodised aluminium (handguard section, rails)
    "lower": ((0.44, 0.35, 0.24, 1), 0.0, 0.70),          # FDE glass-filled polymer
    "grip": ((0.44, 0.35, 0.24, 1), 0.0, 0.78),
    "stock": ((0.44, 0.35, 0.24, 1), 0.0, 0.70),
    "stock_pad": ((0.04, 0.04, 0.04, 1), 0.0, 0.90),
    "barrel": ((0.050, 0.050, 0.055, 1), 0.60, 0.55),     # black phosphated / nitrided steel
    "steel": ((0.060, 0.060, 0.065, 1), 0.70, 0.50),      # black steel details (charging handle, hinge, pins)
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50),  # sights, trigger, screws
    "mag": ((0.055, 0.055, 0.058, 1), 0.40, 0.60),        # black coated steel magazines
    "mag_base": ((0.060, 0.060, 0.062, 1), 0.40, 0.60),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30),
}
MOVING = ("bolt", "bolt_catch", "selector", "trigger", "sight_iron", "sight_rear", "muzzle_brake")


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": sm.receiver_mesh(),
        "lower": sm.lower_mesh(),
        "barrel": sm.barrel_mesh(),
        "pistol_grip": sm.pistol_grip_mesh(),
        "stock": sm.stock_mesh(),
        "bolt": sm.bolt_mesh(),
        "bolt_catch": d(sm.bolt_catch_mesh(), "bolt_catch"),
        "selector": d(sm.selector_mesh(), "selector"),
        "trigger": d(sm.trigger_mesh(), "trigger"),
        "sight_iron": d(sm.front_sight_mesh(), "sight_iron"),
        "sight_rear": d(sm.rear_sight_mesh(), "sight_rear"),
        "muzzle_brake": d(sm.muzzle_brake_mesh(), "muzzle_brake"),
        "mag_standard": sm.mag_mesh(False),
        "mag_extended": sm.mag_mesh(True),
        "fx_shell": sm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough) in MATERIALS.items():
        g.add_material(name, base, metal, rough)


def build(path, clips):
    g = GLB()
    add_materials(g)
    mesh_idx, tri_counts = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in rig.STATIC + MOVING + ("mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
            g.add_node("mag_extended", "mag", mesh=mesh_idx["mag_extended"])
    for name, (parent, t, rot, scale) in rig.MOUNTS.items():       # section 19: attachment mounts
        g.add_node(name, parent, t, rot, scale=scale)
    for side, shoulder in (("R", rig.SHOULDER_R), ("L", rig.SHOULDER_L)):
        g.add_node(f"arm_{side}", "viewmodel", shoulder, rest[f"arm_{side}"]["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", rig.ARM_FORE_T, rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    g.add_node("camera", "viewmodel")
    g.add_node("fx_shell", None, mesh=mesh_idx["fx_shell"], extras={"hidden": True})
    rest_vals = {
        "gun": {"translation": rest["gun"]["translation"], "rotation": rest["gun"]["rotation"]},
        "bolt": {"translation": rest["bolt"]["translation"]},
        "bolt_catch": {"rotation": rest["bolt_catch"]["rotation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "selector": {"rotation": rest["selector"]["rotation"]},
        "mag": {"translation": rig.NODES["mag"][1], "rotation": rig.MAG_Q, "scale": np.ones(3)},
        "camera": {"rotation": quat_identity()},
    }
    for side in ("R", "L"):
        rest_vals[f"arm_{side}"] = {"translation": rest[f"arm_{side}"]["translation"],
                                    "rotation": rest[f"arm_{side}"]["rotation"]}
        rest_vals[f"arm_{side}_fore"] = {"rotation": rest[f"arm_{side}_fore"]["rotation"]}
        rest_vals.update(armrig.rest_values(rest, side))
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info


if __name__ == "__main__":
    import build_all
    import scar_clips
    print(build(build_all.path("models", "gun", "scar.glb"), scar_clips.all_clips()))
