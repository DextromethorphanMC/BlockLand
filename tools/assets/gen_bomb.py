"""Builds models/bomb/c4.glb, the bomb's first-person file (VIEWMODEL_SPEC sections 15-17, 20-21, 31 and 39)."""
import numpy as np

import armrig
import bomb_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN

# name: (base rgba (linear), metallic, roughness, region, options) -- names are stable: texture sets (section 20) key
# on them
MATERIALS = {
    "explosive": ((0.52, 0.46, 0.31, 1), 0.0, 0.82, "body", {}),
    "tape": ((0.30, 0.31, 0.32, 1), 0.0, 0.62, "tape", {}),
    "housing": ((0.055, 0.058, 0.062, 1), 0.0, 0.48, "keypad", {}),
    "keys": ((0.42, 0.43, 0.45, 1), 0.0, 0.55, "keypad", {}),
    "screen": ((0.10, 0.22, 0.10, 1), 0.0, 0.25, "none", {"emissive": (0.16, 0.42, 0.14)}),
    "led": ((0.85, 0.05, 0.04, 1), 0.0, 0.30, "none", {"emissive": (1.0, 0.07, 0.04)}),
    "wire_red": ((0.55, 0.04, 0.03, 1), 0.0, 0.45, "wires", {}),
    "wire_yellow": ((0.70, 0.55, 0.05, 1), 0.0, 0.45, "wires", {}),
    "wire_blue": ((0.05, 0.14, 0.55, 1), 0.0, 0.45, "wires", {}),
}
PARTS = ("body", "keypad", "wires", "led")


def build(path, clips=None):
    import bomb_clips
    it = rig.item()
    clips = clips if clips is not None else bomb_clips.clips()
    g = GLB()
    for name, (base, metal, rough, region, opt) in MATERIALS.items():
        g.add_material(name, base, metal, rough, emissive=opt.get("emissive"), extras={"region": region})
    mesh_idx, tris = {}, {}
    for name in PARTS:
        m, origin = it.parts[name]
        prims = mk.build_primitives(m.translated(-origin), split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest = rig.rest_values(it)
    g.add_node("viewmodel")
    g.add_node(rig.ROOT, "viewmodel", rest[rig.ROOT]["translation"], rest[rig.ROOT]["rotation"])
    for name in PARTS:
        g.add_node(name, rig.ROOT, it.parts[name][1], mesh=mesh_idx[name])
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r.get("translation", rig.er.SHOULDER_L if side == "L" else rig.er.SHOULDER_R),
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
