"""Builds models/gun/m4a1.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 40).

python -I tools/assets/gen_m4a1.py   (or build_all.py m4a1)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import m4a1_model as mm                            # noqa: E402
import m4a1_rig as rig                             # noqa: E402
import meshkit as mk                               # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- fallbacks; pbr_models_m4a1.py binds the texture sets and regions.
# `steel` is the hard-anodised aluminium of the receivers (named so the gold skin tints it), `barrel` the phosphated
# barrel, sight base, hider, delta ring, end plate and buffer tube, `steel_dark` the small black steel parts.
MATERIALS = {
    "steel": ((0.050, 0.050, 0.054, 1), 0.55, 0.50),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.45),
    "barrel": ((0.045, 0.045, 0.043, 1), 0.60, 0.60),
    "polymer": ((0.040, 0.040, 0.042, 1), 0.0, 0.70),
    "pad": ((0.030, 0.030, 0.030, 1), 0.0, 0.85),
    "mag": ((0.070, 0.072, 0.068, 1), 0.50, 0.55),
    "mag_base": ((0.040, 0.040, 0.042, 1), 0.60, 0.55),
    "brass": ((0.55, 0.40, 0.16, 1), 0.90, 0.35),
    "bore": ((0.012, 0.012, 0.013, 1), 0.0, 0.92),
}
MOVING = ("bolt", "charging_handle", "trigger", "mag_catch", "bolt_catch", "sight_iron", "sight_rear", "muzzle_brake")


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "upper": mm.upper_mesh(),
        "lower": mm.lower_mesh(),
        "handguard": mm.handguard_mesh(),
        "barrel": mm.barrel_mesh(),
        "stock": mm.stock_mesh(),
        "pistol_grip": mm.pistol_grip_mesh(),
        "bolt": mm.carrier_mesh(),
        "charging_handle": mm.charging_handle_mesh(),
        "trigger": d(mm.trigger_mesh(), "trigger"),
        "mag_catch": d(mm.mag_catch_mesh(), "mag_catch"),
        "bolt_catch": d(mm.bolt_catch_mesh(), "bolt_catch"),
        "sight_iron": d(mm.front_sight_mesh(), "sight_iron"),
        "sight_rear": d(mm.rear_sight_mesh(), "sight_rear"),
        "muzzle_brake": d(mm.muzzle_brake_mesh(), "muzzle_brake"),
        "mag_standard": mm.mag_mesh(False),
        "mag_extended": mm.mag_mesh(True),
        "fx_shell": mm.shell_mesh(),
    }


def build(path, clips):
    g = GLB()
    for name, (base, metal, rough) in MATERIALS.items():
        g.add_material(name, base, metal, rough)
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
        "charging_handle": {"translation": rest["charging_handle"]["translation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "mag_catch": {"translation": rest["mag_catch"]["translation"]},
        "bolt_catch": {"rotation": rest["bolt_catch"]["rotation"]},
        "mag": {"translation": rig.NODES["mag"][1], "rotation": quat_identity(), "scale": np.ones(3)},
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
    import m4a1_clips
    print(build(build_all.path("models", "gun", "m4a1.glb"), m4a1_clips.all_clips()))
