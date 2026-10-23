"""Builds models/gun/awp.glb (VIEWMODEL_SPEC sections 3-6, 15-17, 19-21 and 23)."""
import numpy as np

import armrig
import awp_model as am
import awp_rig as rig
import meshkit as mk
import textures as tx
from gen_p9 import bake_clip
from glb import GLB
from vmath import quat_identity

# name: (base rgba (linear), metallic, roughness, extra) -- names are stable: texture sets (section 20) key on them
MATERIALS = {
    "receiver": ((0.045, 0.045, 0.048, 1), 0.65, 0.48, {"texture": "brushed"}),
    "barrel": ((0.055, 0.055, 0.058, 1), 0.85, 0.40, {"texture": "brushed"}),
    "bolt": ((0.55, 0.55, 0.57, 1), 1.0, 0.28, {}),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50, {}),
    "stock": ((0.160, 0.200, 0.080, 1), 0.0, 0.72, {"texture": "stipple"}),         # olive drab polymer
    "grip": ((0.150, 0.190, 0.075, 1), 0.0, 0.85, {"texture": "stipple"}),
    "pad": ((0.020, 0.020, 0.022, 1), 0.0, 0.92, {}),
    "mag": ((0.050, 0.050, 0.054, 1), 0.55, 0.55, {"texture": "brushed"}),
    "mag_base": ((0.045, 0.045, 0.048, 1), 0.40, 0.60, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),                                   # .338 Lapua brass
}
TEXTURES = {"stipple": lambda: tx.stipple(256), "brushed": lambda: tx.brushed(256)}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    stock = am.forend_mesh().merge(am.chassis_mesh()).merge(am.butt_mesh()).merge(am.pad_mesh())
    return {
        "receiver": am.receiver_mesh(),
        "barrel": am.barrel_mesh(),
        "muzzle_brake": d(am.muzzle_brake_mesh(), "muzzle_brake"),
        "stock": stock,
        "grip": am.grip_mesh(),
        "bolt": am.bolt_shroud_mesh(),
        "bolt_handle": am.bolt_body_mesh(),
        "trigger": d(am.trigger_mesh(), "trigger"),
        "mag_standard": am.mag_mesh(),
        "fx_shell": am.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png,
                       extras={"region": am.MATERIAL_REGIONS[name]})


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
