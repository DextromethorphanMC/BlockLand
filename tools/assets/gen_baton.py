"""Builds models/knife/baton.glb (VIEWMODEL_SPEC sections 11-12, 15-17, 19-21, 31 and 45)."""
import numpy as np

import armrig
import baton_model as bm
import baton_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj

# name: (base rgba (linear), metallic, roughness, region) -- names are stable: texture sets (section 20) and the
# skins in knives.json key on them
MATERIALS = {
    "shaft": ((0.035, 0.036, 0.040, 1), 1.0, 0.22, "blade"),
    "grip": ((0.030, 0.030, 0.032, 1), 0.0, 0.80, "handle"),
    "hardware": ((0.045, 0.045, 0.050, 1), 0.8, 0.40, "hardware"),
}


def build_meshes():
    """node name -> Mesh in that node's local space (the open baton)."""
    return {name: m.translated(-rig.ORIGINS[name]) for name, m in bm.parts().items()}


def build(path, clips=None):
    import baton_clips
    clips = clips or baton_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    mesh_idx, tris = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.BatonPose().solve()
    g.add_node("viewmodel")
    g.add_node("knife", "viewmodel", rest["knife"]["translation"], rest["knife"]["rotation"])
    for name in ("handle", "shaft_1", "shaft_2", "tip"):
        parent, t = rig.NODES[name]
        if name in rest:
            t = rest[name]["translation"]
        g.add_node(name, parent, t, mesh=mesh_idx.get(name))
    # section 19: the charm through the lanyard loop behind the cap; its -Y hangs straight down in the rest pose
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
