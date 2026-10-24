"""Builds models/knife/crowbar.glb (VIEWMODEL_SPEC sections 11-12, 15-17, 19-21, 31 and 41)."""
import numpy as np

import armrig
import crowbar_model as cm
import crowbar_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj

# name: (base rgba (linear), metallic, roughness, region) -- names are stable: texture sets (section 20) and the
# skins in knives.json key on them
MATERIALS = {
    "bar": ((0.42, 0.035, 0.025, 1), 0.0, 0.45, "blade"),
    "ends": ((0.56, 0.56, 0.58, 1), 1.0, 0.30, "hardware"),
    "grip": ((0.030, 0.030, 0.032, 1), 0.0, 0.70, "handle"),
}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    return {name: m.translated(-rig.ORIGINS[name]) for name, m in cm.parts().items()}


def build(path, clips=None):
    import crowbar_clips
    clips = clips or crowbar_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    mesh_idx, tris = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.CrowbarPose().solve()
    g.add_node("viewmodel")
    g.add_node("knife", "viewmodel", rest["knife"]["translation"], rest["knife"]["rotation"])
    for name in ("bar", "tip", "grip"):
        parent, t = rig.NODES[name]
        g.add_node(name, parent, t, mesh=mesh_idx.get(name))
    # section 19: the charm on a lanyard below the tape; its -Y hangs straight down in the rest pose
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
