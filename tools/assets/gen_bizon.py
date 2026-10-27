"""Builds models/gun/bizon.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 54).

python -I tools/assets/gen_bizon.py   (or build_all.py bizon)"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import bizon_model as bm                           # noqa: E402
import bizon_rig as rig                            # noqa: E402
import meshkit as mk                               # noqa: E402
from gen_p9 import bake_clip                       # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import quat_identity                    # noqa: E402

# name: (base rgba, metallic, roughness) -- the look before pbr_apply binds the texture sets (pbr_models_bizon.py)
MATERIALS = {
    "steel": ((0.050, 0.050, 0.054, 1), 0.25, 0.60),        # receiver, dust cover, front sight: black paint over steel
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.48),   # small steel parts, rails, sights, carrier and handle
    "barrel": ((0.060, 0.060, 0.064, 1), 0.65, 0.50),       # barrel and muzzle nut
    "polymer": ((0.036, 0.036, 0.038, 1), 0.0, 0.72),       # AK-74 grip
    "stock": ((0.030, 0.030, 0.032, 1), 0.0, 0.85),         # rubber butt pad
    "mag": ((0.044, 0.044, 0.047, 1), 0.0, 0.62),           # helical magazine body (glass-filled polymer)
    "mag_base": ((0.040, 0.040, 0.043, 1), 0.0, 0.66),      # its nose, rear end, bands, tower and lugs
    "brass": ((0.72, 0.52, 0.25, 1), 0.90, 0.35),           # 9 mm brass
}


def build_meshes():
    def d(m, node):
        return m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    return {
        "receiver": bm.receiver_mesh(),
        "dust_cover": bm.dust_cover_mesh(),
        "front_sight": bm.front_sight_mesh(),
        "barrel": bm.barrel_mesh(),
        "pistol_grip": bm.pistol_grip_mesh(),
        "stock": bm.stock_mesh(),
        "bolt": bm.bolt_mesh(),
        "trigger": d(bm.trigger_mesh(), "trigger"),
        "selector": d(bm.selector_mesh(), "selector"),
        "paddle": d(bm.paddle_mesh(), "paddle"),
        "sight_iron": d(bm.front_post_mesh(), "sight_iron"),
        "front_sight_hood": d(bm.front_hood_mesh(), "front_sight_hood"),
        "sight_rear": d(bm.rear_sight_mesh(), "sight_rear"),
        "muzzle_brake": d(bm.muzzle_nut_mesh(), "muzzle_brake"),
        "mag_standard": bm.mag_mesh(),                       # built in mag space already
        "fx_shell": bm.shell_mesh(),
    }


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
    order = rig.STATIC + ("stock", "bolt", "trigger", "selector", "paddle", "sight_iron", "front_sight_hood",
                          "sight_rear", "muzzle_brake", "mag", "muzzle", "ejection")
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
    import bizon_clips
    print(build(build_all.path("models", "gun", "bizon.glb"), bizon_clips.all_clips()))
