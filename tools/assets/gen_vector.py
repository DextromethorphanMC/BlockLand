"""Builds models/gun/vector.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 44).

python -I tools/assets/gen_vector.py   (or build_all.py vector)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import vector_model as vm                          # noqa: E402
import vector_rig as rig                           # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- the look before pbr_apply binds the texture sets (pbr_models_vector.py)
MATERIALS = {
    "upper": ((0.045, 0.045, 0.050, 1), 0.55, 0.50),        # hard-anodised aluminium upper and hinge block
    "lower": ((0.036, 0.036, 0.038, 1), 0.0, 0.72),         # polymer lower, guard and grip
    "rail": ((0.040, 0.040, 0.044, 1), 0.70, 0.48),         # rails, sights, small steel parts
    "barrel": ((0.060, 0.060, 0.064, 1), 0.65, 0.50),       # barrel and flash hider
    "stock": ((0.036, 0.036, 0.038, 1), 0.0, 0.72),         # folding stock frame
    "pad": ((0.030, 0.030, 0.032, 1), 0.0, 0.85),           # rubber butt pad
    "mag": ((0.034, 0.034, 0.036, 1), 0.0, 0.70),           # polymer magazine body
    "mag_base": ((0.040, 0.040, 0.044, 1), 0.0, 0.66),      # extension floor plate
    "brass": ((0.72, 0.52, 0.25, 1), 0.90, 0.35),           # .45 ACP brass
}


def build_meshes():
    out = {}
    for name, m in vm.parts().items():
        origin = rig.MESH_ORIGIN.get(name)
        out[name] = m.translated(-np.asarray(origin, float)) if origin is not None else m
    return out


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
    order = rig.STATIC + ("stock", "bolt", "handle_lever", "carrier", "trigger", "selector", "paddle", "sight_iron",
                          "front_sight_hood", "sight_rear", "muzzle_brake", "mag", "muzzle", "ejection")
    for name in order:
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
        "handle_lever": {"rotation": rest["handle_lever"]["rotation"]},
        "carrier": {"translation": rest["carrier"]["translation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "selector": {"rotation": rest["selector"]["rotation"]},
        "paddle": {"rotation": rest["paddle"]["rotation"]},
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
    import vector_clips
    print(build(build_all.path("models", "gun", "vector.glb"), vector_clips.all_clips()))
