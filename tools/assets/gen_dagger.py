"""Builds models/knife/dagger.glb (VIEWMODEL_SPEC sections 11-12, 15-17, 19-21 and 27)."""
import numpy as np

import armrig
import dagger_clips
import dagger_model as dm
import dagger_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj

# name: (base rgba (linear), metallic, roughness, region) -- names are stable: texture sets (section 20) and the
# legacy skin tints in knives.json key on them
MATERIALS = {
    "blade": ((0.62, 0.62, 0.64, 1), 1.0, 0.24, "blade"),
    "blade_edge": ((0.86, 0.87, 0.89, 1), 1.0, 0.12, "blade"),
    "guard": ((0.050, 0.050, 0.054, 1), 0.75, 0.45, "hardware"),
    "handle": ((0.034, 0.036, 0.034, 1), 0.0, 0.85, "handle"),
    "pommel": ((0.050, 0.050, 0.054, 1), 0.75, 0.45, "hardware"),
}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    return {name: m.translated(-rig.ORIGINS[name]) for name, m in dm.dagger_parts().items()}


def build(path, clips=None):
    clips = clips or dagger_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    mesh_idx, tris = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.DaggerPose().solve()
    g.add_node("viewmodel")
    g.add_node("knife", "viewmodel", rest["knife"]["translation"], rest["knife"]["rotation"])
    for name in ("blade", "tip", "guard", "handle", "pommel"):
        parent, t = rig.NODES[name]
        g.add_node(name, parent, t, mesh=mesh_idx.get(name))
    # section 19: charm mount at the pommel's lanyard loop; its -Y hangs straight down in the rest pose
    g.add_node("mount_charm", "knife", rig.CHARM_MOUNT, quat_conj(rest["knife"]["rotation"]))
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r.get("translation", rig.SHOULDER_L if side == "L" else rig.SHOULDER_R),
                   r["rotation"], scale=r.get("scale"))
        g.add_node(f"arm_{side}_fore", f"arm_{side}", np.array([0.0, -UPPER_LEN, 0.0]),
                   rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    if "scale" in rest["arm_L"] and not np.any(rest["arm_L"]["scale"]):
        g.json["nodes"][g.node_index["arm_L"]]["scale"] = [0.0, 0.0, 0.0]
    rest_vals = {n: dict(v) for n, v in rest.items()}
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tris, info
