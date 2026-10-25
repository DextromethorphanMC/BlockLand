"""Builds models/gun/revolver.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 49) from revolver_model with the revolver_rig
/ revolver_clips animation.

python -I tools/assets/build_all.py revolver"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import revolver_model as rm                        # noqa: E402
import revolver_rig as rig                         # noqa: E402
from gen_jackhammer import bake_clip               # noqa: E402  (STEP channels for the cylinder's index)
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba (linear), metallic, roughness, region, extra) -- names are stable: texture sets (section 20) and the
# skins key on them
MATERIALS = {
    "steel": ((0.030, 0.034, 0.045, 1), 1.0, 0.22, "body", {}),          # polished blued frame, cylinder, crane
    "barrel": ((0.030, 0.034, 0.045, 1), 1.0, 0.22, "barrel", {}),       # the barrel and its underlug and rib
    "grip": ((0.20, 0.085, 0.035, 1), 0.0, 0.55, "grip", {}),            # walnut target grips
    "steel_dark": ((0.022, 0.022, 0.025, 1), 0.85, 0.42, "detail", {}),  # hammer, trigger, latch, sights, screws
    "bore": ((0.006, 0.006, 0.006, 1), 0.5, 0.65, "none", {}),           # the bore and the chambers
    "insert": ((0.80, 0.04, 0.025, 1), 0.0, 0.40, "none", {"emissive": (0.35, 0.02, 0.01)}),   # the front sight's ramp
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, "none", {}),
    "bullet": ((0.62, 0.36, 0.22, 1), 1.0, 0.38, "none", {}),            # copper jackets
    "loader": ((0.022, 0.022, 0.024, 1), 0.0, 0.62, "magazine", {}),      # the speed loader's polymer
}
STATIC = ("frame", "barrel", "grip", "sight_iron", "sight_rear")
MOVING = ("hammer", "trigger", "latch", "crane", "cylinder", "ejector", "rounds", "ejection", "muzzle", "mag")


def build_meshes():
    grip = rm.grip_mesh()
    grip.merge(rm.charm_ring_mesh())
    return {
        "frame": rm.frame_mesh(), "barrel": rm.barrel_mesh(), "grip": grip,
        "sight_iron": rm.front_sight_mesh(), "sight_rear": rm.rear_sight_mesh(),
        "hammer": rm.hammer_mesh(), "trigger": rm.trigger_mesh(), "latch": rm.latch_mesh(),
        "crane": rm.crane_mesh(), "cylinder": rm.cylinder_mesh(), "ejector": rm.ejector_mesh(),
        "rounds": rm.rounds_mesh(), "mag_standard": rm.speedloader_mesh(), "loader_rounds": rm.loader_rounds_mesh(),
        "fx_shell": rm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, region, extra) in MATERIALS.items():
        g.add_material(name, base, metal, rough, emissive=extra.get("emissive"), extras={"region": region})


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
    for name in STATIC + MOVING:
        parent, t = rig.NODES[name]
        rot, scale = None, None
        if name == "ejection":
            rot = rig.ejection_quat()
        if name == "mag":
            scale = np.zeros(3)                       # the speed loader shows only in the reload
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name), scale=scale)
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
            g.add_node("loader_rounds", "mag", mesh=mesh_idx["loader_rounds"])
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
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "hammer": {"rotation": rest["hammer"]["rotation"]},
        "latch": {"translation": rest["latch"]["translation"]},
        "crane": {"rotation": rest["crane"]["rotation"]},
        "cylinder": {"rotation": rest["cylinder"]["rotation"]},
        "ejector": {"translation": rest["ejector"]["translation"]},
        "rounds": {"scale": rest["rounds"]["scale"]},
        "mag": {"translation": rig.MAG_REST, "rotation": quat_identity(), "scale": np.zeros(3)},
        "loader_rounds": {"scale": np.ones(3)},
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
    import revolver_clips
    print(build(build_all.path("models", "gun", "revolver.glb"), revolver_clips.all_clips()))
