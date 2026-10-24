"""Builds models/gun/p90.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 40).

python -I tools/assets/gen_p90.py   (or build_all.py p90)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import p90_model as pm                             # noqa: E402
import p90_rig as rig                              # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness, extra) -- factors only; pbr_models_p90.py binds the texture sets
MATERIALS = {
    "polymer": ((0.050, 0.050, 0.053, 1), 0.0, 0.62, {}),
    "steel": ((0.090, 0.090, 0.095, 1), 0.85, 0.45, {}),
    "steel_dark": ((0.040, 0.040, 0.043, 1), 0.70, 0.50, {}),
    "barrel": ((0.110, 0.108, 0.104, 1), 0.85, 0.42, {}),
    "mag": ((0.060, 0.045, 0.034, 1), 0.0, 0.40, {}),
    # the translucent smoky-brown magazine shell (check_orient allows this one BLEND material, section 40)
    "mag_window": ((0.30, 0.17, 0.075, 0.46), 0.0, 0.16, {"alpha_mode": "BLEND"}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "butt": pm.butt_mesh(),
        "receiver": pm.receiver_mesh(),
        "frame": pm.frame_mesh(),
        "forend": pm.forend_mesh(),
        "housing": pm.housing_mesh(),
        "barrel": pm.barrel_mesh(),
        "muzzle_brake": d(pm.muzzle_brake_mesh(), "muzzle_brake"),
        "bolt": pm.bolt_mesh(),
        "trigger": d(pm.trigger_mesh(), "trigger"),
        "selector": pm.selector_mesh(),
        "sight_rear": d(pm.rear_sight_mesh(), "sight_rear"),
        "sight_iron": d(pm.front_post_mesh(), "sight_iron"),
        "mag_standard": pm.mag_mesh(),
        "fx_shell": pm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        g.add_material(name, base, metal, rough, alpha_mode=ex.get("alpha_mode", "OPAQUE"))


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
    for name in rig.STATIC + ("bolt", "trigger", "selector", "sight_rear", "sight_iron", "muzzle_brake", "mag",
                              "muzzle", "ejection"):
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
    import p90_clips
    print(build(build_all.path("models", "gun", "p90.glb"), p90_clips.all_clips()))
