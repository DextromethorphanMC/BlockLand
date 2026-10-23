"""Builds models/gun/ak47.glb (VIEWMODEL_SPEC sections 3-6, 15-18)."""
import numpy as np

import ak_model as am
import ak_rig as rig
import armrig
import meshkit as mk
import textures as tx
from gen_p9 import bake_clip
from glb import GLB
from vmath import quat_identity

# name: (base rgba, metallic, roughness, extra)
MATERIALS = {
    "wood": ((0.40, 0.17, 0.075, 1), 0.0, 0.55, {"texture": "wood"}),
    "steel": ((0.085, 0.085, 0.092, 1), 0.85, 0.42, {"texture": "brushed"}),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50, {}),
    "mag": ((0.070, 0.070, 0.075, 1), 0.60, 0.55, {"texture": "brushed"}),
    "mag_base": ((0.060, 0.060, 0.064, 1), 0.50, 0.60, {}),
    "brass": ((0.30, 0.28, 0.17, 1), 0.55, 0.45, {}),           # lacquered steel case
}
TEXTURES = {"wood": lambda: tx.wood(256), "brushed": lambda: tx.brushed(256)}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": am.receiver_mesh(),
        "dust_cover": am.dust_cover_mesh(),
        "handguard": am.handguard_mesh(),
        "gas_tube": am.gas_tube_mesh(),
        "barrel": am.barrel_mesh(),
        "muzzle_brake": d(am.muzzle_brake_mesh(), "muzzle_brake"),
        "front_sight": am.front_sight_mesh(),
        "stock": am.stock_mesh(),
        "pistol_grip": am.pistol_grip_mesh(),
        "bolt": am.bolt_mesh(),
        "selector": d(am.selector_mesh(), "selector"),
        "trigger": d(am.trigger_mesh(), "trigger"),
        "sight_iron": d(am.front_post_mesh(), "sight_iron"),
        "sight_rear": d(am.rear_sight_mesh(), "sight_rear"),
        "front_sight_ears": d(am.front_sight_ears_mesh(), "front_sight_ears"),
        "mag_standard": am.mag_mesh(False),
        "mag_extended": am.mag_mesh(True),
        "fx_shell": am.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png)


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
    for name in rig.STATIC + ("muzzle_brake", "bolt", "selector", "trigger", "sight_iron", "sight_rear",
                              "front_sight_ears", "mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_iron":
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
