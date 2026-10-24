"""Builds models/gun/tommy.glb (VIEWMODEL_SPEC sections 3-6, 15-19 and 24) from the source model (tommy_model.py).

python -I tools/assets/gen_tommy.py [path/to/gangster_tommy_gun.glb]   (or build_all.py tommy)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import meshkit as mk                               # noqa: E402
import textures as tx                              # noqa: E402
import tommy_model as tm                           # noqa: E402
import tommy_rig as rig                            # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba (linear), metallic, roughness, extra) -- names are stable: texture sets (section 20) key on them
MATERIALS = {
    "wood": ((0.30, 0.14, 0.065, 1), 0.0, 0.50, {"texture": "wood"}),        # oiled walnut
    "steel": ((0.060, 0.062, 0.070, 1), 0.85, 0.40, {"texture": "brushed"}),  # blued steel
    "steel_dark": ((0.035, 0.035, 0.040, 1), 0.70, 0.50, {}),
    "mag": ((0.050, 0.051, 0.056, 1), 0.65, 0.50, {"texture": "brushed"}),
    "mag_base": ((0.045, 0.045, 0.050, 1), 0.55, 0.55, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),                          # .45 ACP brass
}
TEXTURES = {"wood": lambda: tx.wood(256), "brushed": lambda: tx.brushed(256)}


def build_meshes(src):
    """node name -> Mesh in that node's local space."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": tm.receiver_mesh(src),
        "barrel": tm.barrel_mesh(),
        "grip": tm.grip_mesh(src),
        "stock": tm.stock_mesh(src),
        "muzzle_brake": d(tm.muzzle_brake_mesh(), "muzzle_brake"),
        "bolt": tm.bolt_mesh(src),
        "selector": d(tm.selector_mesh(src), "selector"),
        "trigger": d(tm.trigger_mesh(src), "trigger"),
        "sight_iron": d(tm.front_post_mesh(), "sight_iron"),
        "sight_rear": d(tm.rear_sight_mesh(), "sight_rear"),
        "mag_stick": tm.stick_mag_mesh(),
        "mag_drum": tm.drum_mesh(),
        "fx_shell": tm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png)


def build(path, clips, src=None):
    src = src or tm.default_source()
    g = GLB()
    add_materials(g)
    mesh_idx, tri_counts = {}, {}
    for name, m in build_meshes(src).items():
        prims = mk.build_primitives(m)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in rig.STATIC + ("muzzle_brake", "bolt", "selector", "trigger", "sight_iron", "sight_rear", "mag",
                              "muzzle", "ejection"):
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
            g.add_node("mag_stick", "mag", mesh=mesh_idx["mag_stick"])
            g.add_node("mag_drum", "mag", mesh=mesh_idx["mag_drum"])
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
    import tommy_clips
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "gun",
                       "tommy.glb")
    tris, chans = build(out, tommy_clips.all_clips(), sys.argv[1] if len(sys.argv) > 1 else None)
    print(f"tommy.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} triangles; per mesh {tris}")
