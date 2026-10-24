"""Builds models/gun/calico.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 40).

python -I tools/assets/gen_calico.py   (or build_all.py calico)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import calico_model as cm                          # noqa: E402
import calico_rig as rig                           # noqa: E402
import meshkit as mk                               # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- fallbacks; the texture sets (pbr_models_calico.py) replace them
MATERIALS = {
    "steel": ((0.050, 0.050, 0.055, 1), 0.65, 0.45),           # black-anodised aluminium receiver, bridge, tower
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.75, 0.50),      # small steel parts, rails, sights, handle
    "barrel": ((0.060, 0.060, 0.064, 1), 0.85, 0.40),          # barrel and thread protector
    "polymer": ((0.060, 0.060, 0.062, 1), 0.0, 0.70),          # lower, grip, forend
    "mag": ((0.070, 0.070, 0.072, 1), 0.0, 0.62),              # magazine bodies (polymer)
    "mag_base": ((0.050, 0.050, 0.052, 1), 0.0, 0.66),         # caps, keys, latch parts
    "brass": ((0.62, 0.46, 0.22, 1), 1.0, 0.35),
}
SMOOTH = {"forend": 50.0, "lower": 50.0, "grip": 50.0}         # polymer: two-facet bevels shade as one curve


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    mag = lambda ext: cm.mag_mesh(ext)                            # built in magazine space already
    return {
        "receiver": cm.receiver_mesh(),
        "forend": cm.forend_mesh(),
        "barrel": cm.barrel_mesh(),
        "lower": cm.lower_mesh(),
        "grip": cm.grip_mesh(),
        "muzzle_brake": d(cm.muzzle_brake_mesh(), "muzzle_brake"),
        "bolt": cm.bolt_mesh(),
        "trigger": d(cm.trigger_mesh(), "trigger"),
        "selector": d(cm.selector_mesh(), "selector"),
        "sight_iron": d(cm.front_post_mesh(), "sight_iron"),
        "sight_rear": d(cm.rear_sight_mesh(), "sight_rear"),
        "mag_standard": mag(False),
        "mag_extended": mag(True),
        "fx_shell": cm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough) in MATERIALS.items():
        g.add_material(name, base, metal, rough)


def build(path, clips):
    g = GLB()
    add_materials(g)
    mesh_idx, tri_counts = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, hard_deg=SMOOTH.get(name, 35.0))
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in rig.STATIC + ("muzzle_brake", "bolt", "trigger", "selector", "sight_iron", "sight_rear", "mag",
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
    import calico_clips
    print(build(build_all.path("models", "gun", "calico.glb"), calico_clips.all_clips()))
