"""Builds models/gun/uzi.glb (VIEWMODEL_SPEC sections 3-6, 15-19 and 40) from uzi_model.py / uzi_rig.py / uzi_clips.py.

python -I tools/assets/gen_uzi.py   (or build_all.py uzi)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import drum_mags                                   # noqa: E402
import meshkit as mk                               # noqa: E402
import textures as tx                              # noqa: E402
import uzi_model as um                             # noqa: E402
import uzi_rig as rig                              # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba (linear), metallic, roughness, extra) -- names are stable: texture sets (section 20) and the skins'
# tints (steel, steel_dark, barrel) key on them
MATERIALS = {
    "steel": ((0.085, 0.086, 0.088, 1), 0.55, 0.60, {"texture": "brushed"}),     # parkerised stamped steel
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "barrel": ((0.045, 0.045, 0.048, 1), 0.75, 0.45, {}),
    "polymer": ((0.030, 0.030, 0.032, 1), 0.0, 0.70, {}),                         # handguard and grip panels
    "mag": ((0.075, 0.076, 0.078, 1), 0.55, 0.55, {"texture": "brushed"}),
    "mag_base": ((0.055, 0.055, 0.058, 1), 0.55, 0.55, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),                               # 9x19 brass
}
TEXTURES = {"brushed": lambda: tx.brushed(256)}
MOVING = ("bolt", "trigger", "sight_iron", "sight_rear", "muzzle_brake", "mag", "muzzle", "ejection")


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": um.receiver_mesh(),
        "top_cover": um.top_cover_mesh(),
        "rail": um.rail_mesh(),
        "barrel": um.barrel_mesh(),
        "barrel_nut": um.nut_mesh(),
        "front_ears": um.front_ears_mesh(),
        "rear_ears": um.rear_ears_mesh(),
        "handguard": um.handguard_mesh(),
        "frame": um.frame_mesh(),
        "grip": um.grip_mesh(),
        "stock": um.stock_mesh(),
        "bolt": um.knob_mesh(),
        "trigger": d(um.trigger_mesh(), "trigger"),
        "sight_iron": d(um.front_post_mesh(), "sight_iron"),
        "sight_rear": d(um.rear_sight_mesh(), "sight_rear"),
        "muzzle_brake": d(um.muzzle_brake_mesh(), "muzzle_brake"),
        "mag_standard": um.mag_mesh(False),
        "mag_extended": um.mag_mesh(True),
        "mag_drum": drum()[0],
        "fx_shell": um.shell_mesh(),
    }


DRUM = (0.056, 0.052)        # the 100-round drum's radius and width (section 56)


def drum():
    """The drum magazine (mag space): the box magazine down to just under the grip, the drum seated there."""
    return drum_mags.side_drum(drum_mags.trim(um.mag_mesh(False), um.GRIP_BOTTOM_LOCAL - 0.006), *DRUM)


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
    for name in rig.STATIC + MOVING:
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
    # the drum's own reloads (section 56): the support hand on the drum, not round a box magazine's lower body
    clips = list(clips) + drum_mags.drum_clips(clips, rig.ANCHORS, drum_mags.uzi_holds(rig, drum()[1]))
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info


if __name__ == "__main__":
    import build_all
    import uzi_clips
    tris, chans = build(build_all.path("models", "gun", "uzi.glb"), uzi_clips.all_clips())
    print(f"uzi.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} triangles; per mesh {tris}")
