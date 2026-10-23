"""Builds models/knife/butterfly.glb (VIEWMODEL_SPEC sections 11-12)."""
import numpy as np

import armrig
import knife_clips
import knife_model as km
import knife_rig as kr
import meshkit as mk
import textures as tx
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj, quat_identity, quat_mul

MATERIALS = {   # name: (base rgba, metallic, roughness, texture)
    "blade": ((0.66, 0.66, 0.68, 1), 1.0, 0.20, "brushed"),
    "blade_edge": ((0.90, 0.91, 0.93, 1), 1.0, 0.10, None),
    "handle": ((0.13, 0.14, 0.16, 1), 0.85, 0.35, None),
    "handle_inlay": ((0.34, 0.05, 0.04, 1), 0.0, 0.55, None),
    "pins": ((0.72, 0.72, 0.74, 1), 1.0, 0.28, None),
    "latch": ((0.58, 0.59, 0.61, 1), 1.0, 0.32, None),
}


def build_meshes():
    parts = km.knife_parts()
    return {"handle_safe": parts["handle_safe"].translated(-kr.SAFE_PIN),
            "blade": parts["blade"].translated(-kr.SAFE_PIN),
            "handle_bite": parts["handle_bite"].translated(-kr.BITE_PIN),
            "latch": parts["latch"].translated(-km.LATCH_PIVOT)}


def build(path, clips=None):
    clips = clips or knife_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, tex) in MATERIALS.items():
        png = tx.png_bytes(tx.brushed(256)) if tex == "brushed" else None
        g.add_material(name, base, metal, rough, texture_png=png)
    mesh_idx, tris = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = kr.KnifePose().solve()
    g.add_node("viewmodel")
    g.add_node("knife", "viewmodel", rest["knife"]["translation"], rest["knife"]["rotation"])
    for name in ("handle_safe", "blade", "tip", "handle_bite", "latch"):
        parent, t = kr.NODES[name]
        g.add_node(name, parent, t, rest.get(name, {}).get("rotation"), mesh=mesh_idx.get(name))
    # section 19: charm mount at the butt of the safe handle; its -Y hangs straight down in the rest pose
    q_world = quat_mul(rest["knife"]["rotation"], rest.get("handle_safe", {}).get("rotation", quat_identity()))
    g.add_node("mount_charm", "handle_safe", kr.CHARM_MOUNT, quat_conj(q_world))
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r.get("translation", kr.SHOULDER_L if side == "L" else kr.SHOULDER_R),
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
