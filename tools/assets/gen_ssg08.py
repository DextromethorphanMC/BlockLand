"""Builds models/gun/ssg08.glb (VIEWMODEL_SPEC sections 3-6, 15-17, 19-21, 23 and 40).

python -I tools/assets/gen_ssg08.py   (or build_all.py ssg08)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import ssg08_model as sm                           # noqa: E402
import ssg08_rig as rig                            # noqa: E402
import textures as tx                              # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba (linear), metallic, roughness, extra) -- names are stable: texture sets (section 20), regions
# (section 21) and the gold skin's tints (steel, steel_dark, barrel) key on them
MATERIALS = {
    "steel": ((0.050, 0.051, 0.054, 1), 0.55, 0.50, {"texture": "brushed"}),      # anodised aluminium upper/lower
    "handguard": ((0.050, 0.051, 0.054, 1), 0.55, 0.50, {"texture": "brushed"}),  # anodised aluminium fore-end
    "stock": ((0.050, 0.051, 0.054, 1), 0.55, 0.52, {"texture": "brushed"}),      # anodised aluminium stock
    "cheek": ((0.030, 0.030, 0.032, 1), 0.0, 0.70, {"texture": "stipple"}),        # polymer cheek piece
    "grip": ((0.028, 0.028, 0.030, 1), 0.0, 0.82, {"texture": "stipple"}),         # polymer grip
    "pad": ((0.020, 0.020, 0.022, 1), 0.0, 0.92, {}),                              # rubber butt pad
    "barrel": ((0.035, 0.035, 0.038, 1), 0.85, 0.36, {"texture": "brushed"}),     # black stainless
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.75, 0.45, {}),
    "mag": ((0.032, 0.032, 0.034, 1), 0.0, 0.62, {}),                              # polymer magazine
    "mag_base": ((0.030, 0.030, 0.032, 1), 0.0, 0.66, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
TEXTURES = {"stipple": lambda: tx.stipple(256), "brushed": lambda: tx.brushed(256)}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    receiver = sm.upper_mesh().merge(sm.lower_mesh()).merge(sm.top_rail_mesh())
    stock = sm.stock_mesh().merge(sm.cheek_mesh()).merge(sm.pad_mesh())
    return {
        "receiver": receiver,
        "handguard": sm.foreend_mesh(),
        "barrel": sm.barrel_mesh(),
        "muzzle_brake": d(sm.muzzle_brake_mesh(), "muzzle_brake"),
        "stock": stock,
        "grip": sm.grip_mesh(),
        "bolt": sm.bolt_shroud_mesh(),
        "bolt_handle": sm.bolt_body_mesh(),
        "trigger": d(sm.trigger_mesh(), "trigger"),
        "mag_standard": sm.mag_mesh(),
        "fx_shell": sm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png,
                       extras={"region": sm.MATERIAL_REGIONS[name]})


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
    for name in rig.STATIC + ("muzzle_brake", "bolt", "bolt_handle", "trigger", "mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
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
        "bolt_handle": {"rotation": rest["bolt_handle"]["rotation"]},
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
    import ssg08_clips
    print(build(build_all.path("models", "gun", "ssg08.glb"), ssg08_clips.all_clips()))
