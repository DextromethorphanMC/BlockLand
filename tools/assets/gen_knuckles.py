"""Builds models/knife/knuckles.glb (VIEWMODEL_SPEC sections 11-12, 15-17, 19-21, 31 and 42)."""
import numpy as np

import armrig
import knuckles_clips
import knuckles_model as km
import knuckles_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj

# name: (base rgba (linear), metallic, roughness, region) -- names are stable: texture sets (section 20) and skins key
# on them
MATERIALS = {
    "frame": ((0.80, 0.56, 0.24, 1), 1.0, 0.38, "blade"),
    "face": ((0.90, 0.68, 0.34, 1), 1.0, 0.20, "blade"),
    "wrap": ((0.030, 0.030, 0.028, 1), 0.0, 0.88, "handle"),
}


def build_meshes():
    """node name -> Mesh in that node's local space (each duster about the middle of its ring row)."""
    right = km.duster_mesh().translated(-km.CENTRE)
    left = km.mirrored(km.duster_mesh()).translated(-rig.OFF["L"])
    return {"duster": right, "duster_L": left}


def build(path, clips=None):
    clips = clips or knuckles_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    mesh_idx, tris = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.KnucklesPose().solve()
    g.add_node("viewmodel")
    g.add_node("knife", "viewmodel", rest["knife"]["translation"], rest["knife"]["rotation"])
    g.add_node("duster", "knife", mesh=mesh_idx["duster"])
    g.add_node("tip", "knife", rig.TIP["R"])
    # section 19: charm mount in the lanyard hole; its -Y hangs straight down in the rest pose
    g.add_node("mount_charm", "knife", rig.CHARM_MOUNT, quat_conj(rest["knife"]["rotation"]))
    g.add_node("knife_L", "viewmodel", rest["knife_L"]["translation"], rest["knife_L"]["rotation"])
    g.add_node("duster_L", "knife_L", mesh=mesh_idx["duster_L"])
    g.add_node("tip_L", "knife_L", rig.TIP["L"])
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r["translation"], r["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", np.array([0.0, -UPPER_LEN, 0.0]),
                   rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    rest_vals = {n: dict(v) for n, v in rest.items()}
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tris, info
