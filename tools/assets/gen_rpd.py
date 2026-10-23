"""Builds models/gun/rpd.glb (VIEWMODEL_SPEC sections 3-6, 15-19 and 33).

python -I tools/assets/gen_rpd.py   (or build_all.py rpd)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import rpd_model as rm                             # noqa: E402
import rpd_rig as rig                              # noqa: E402
import textures as tx                              # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness, extra) -- the AK's materials (and texture sets, pbr_models_rpd.py)
MATERIALS = {
    "wood": ((0.36, 0.16, 0.070, 1), 0.0, 0.55, {"texture": "wood"}),
    "steel": ((0.080, 0.080, 0.088, 1), 0.85, 0.42, {"texture": "brushed"}),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50, {}),
    "mag": ((0.070, 0.072, 0.066, 1), 0.60, 0.55, {"texture": "brushed"}),
    "mag_base": ((0.060, 0.060, 0.058, 1), 0.50, 0.60, {}),
    "brass": ((0.30, 0.28, 0.17, 1), 0.55, 0.45, {}),           # lacquered steel cases
}
TEXTURES = {"wood": lambda: tx.wood(256), "brushed": lambda: tx.brushed(256)}


def build_meshes():
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": rm.receiver_mesh(),
        "handguard": rm.handguard_mesh(),
        "barrel": rm.barrel_mesh(),
        "front_sight": rm.front_sight_mesh(),
        "stock": rm.stock_mesh(),
        "pistol_grip": rm.pistol_grip_mesh(),
        "feed_cover": d(rm.feed_cover_mesh(), "feed_cover"),
        "muzzle_brake": d(rm.muzzle_brake_mesh(), "muzzle_brake"),
        "bolt": rm.bolt_mesh(),
        "trigger": d(rm.trigger_mesh(), "trigger"),
        "sight_iron": d(rm.front_post_mesh(), "sight_iron"),
        "sight_rear": d(rm.rear_sight_mesh(), "sight_rear"),
        "belt": rm.belt_mesh(),
        "belt_piece": rm.belt_piece(),
        "mag_standard": rm.drum_mesh(False),
        "mag_extended": rm.drum_mesh(True),
        "fx_shell": rm.shell_mesh(),
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
    for name in rig.STATIC + ("feed_cover", "muzzle_brake", "bolt", "trigger", "sight_iron", "sight_rear", "belt",
                              "mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
        if name == "belt":
            for piece in rig.BELT_NODES:                   # the belt behind its end piece (rpd_rig.belt_chain)
                g.add_node(piece, "gun", rest[piece]["translation"], rest[piece]["rotation"],
                           mesh=mesh_idx["belt_piece"])
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
        "feed_cover": {"rotation": rest["feed_cover"]["rotation"]},
        "belt": {"translation": rig.NODES["belt"][1], "rotation": quat_identity(), "scale": np.ones(3)},
        "mag": {"translation": rig.NODES["mag"][1], "rotation": rig.MAG_Q, "scale": np.ones(3)},
        **{piece: {"translation": rest[piece]["translation"], "rotation": rest[piece]["rotation"],
                   "scale": np.ones(3)} for piece in rig.BELT_NODES},
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
    import rpd_clips
    print(build(build_all.path("models", "gun", "rpd.glb"), rpd_clips.all_clips()))
