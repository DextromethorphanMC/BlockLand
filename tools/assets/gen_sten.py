"""Builds models/gun/sten.glb (VIEWMODEL_SPEC sections 3-6, 15-19 and 40).

python -I tools/assets/gen_sten.py   (or build_all.py sten)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import sten_model as sm                            # noqa: E402
import sten_rig as rig                             # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- fallbacks; the texture sets (pbr_models_sten.py) carry the look
MATERIALS = {
    "steel": ((0.060, 0.060, 0.064, 1), 0.70, 0.55),         # black-painted / phosphated receiver steel
    "barrel": ((0.070, 0.072, 0.080, 1), 0.85, 0.42),        # blued barrel, nut and sleeve
    "steel_dark": ((0.045, 0.045, 0.050, 1), 0.80, 0.45),    # small blued parts, bolt, sights
    "mag": ((0.055, 0.055, 0.058, 1), 0.65, 0.55),
    "mag_base": ((0.050, 0.050, 0.052, 1), 0.65, 0.55),
    "brass": ((0.62, 0.45, 0.20, 1), 0.95, 0.30),
    "wood": ((0.20, 0.11, 0.06, 1), 0.0, 0.60),                # the stained grip
}
MESH_NODES = ("receiver", "barrel", "trigger_housing", "stock", "bolt", "trigger", "sight_iron", "sight_rear",
              "muzzle_brake")


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": sm.receiver_mesh(),
        "barrel": sm.barrel_mesh(),
        "trigger_housing": sm.trigger_housing_mesh().merge(sm.grip_mesh()),
        "stock": sm.stock_mesh(),
        "bolt": sm.bolt_mesh(),
        "trigger": d(sm.trigger_mesh(), "trigger"),
        "sight_iron": d(sm.front_blade_mesh(), "sight_iron"),
        "sight_rear": d(sm.rear_peep_mesh(), "sight_rear"),
        "muzzle_brake": d(sm.muzzle_brake_mesh(), "muzzle_brake"),
        "mag_standard": sm.mag_mesh(),
        "fx_shell": sm.shell_mesh(),
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
    for name in MESH_NODES + ("mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "bolt":
            t = rest["bolt"]["translation"]
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
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
        "bolt": {"translation": rest["bolt"]["translation"], "rotation": quat_identity()},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
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
    import sten_clips
    print(build(build_all.path("models", "gun", "sten.glb"), sten_clips.all_clips()))
