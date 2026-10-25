"""Builds the body armour models (VIEWMODEL_SPEC section 50): models/armor/vest.glb (the plate carrier drawn on the
player's torso, its plates as separate nodes the runtime shows by count) and models/armor/plate.glb (the armour plate
in first person: both arms on it, its clips)."""
import numpy as np

import armor_clips
import armor_model as am
import armor_rig as rig
import armrig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN

# name: (base rgba (linear), metallic, roughness, region)
VEST_MATERIALS = {
    "carrier": ((0.060, 0.075, 0.045, 1), 0.0, 0.85, "body"),
    "webbing": ((0.045, 0.055, 0.034, 1), 0.0, 0.80, "body"),
    "hardware": ((0.015, 0.015, 0.016, 1), 0.0, 0.55, "hardware"),
}
PLATE_MATERIALS = {
    "plate": ((0.018, 0.018, 0.019, 1), 0.0, 0.62, "body"),
    "label": ((0.42, 0.42, 0.38, 1), 0.0, 0.75, "none"),
}
VEST_NODES = ("carrier", "plate_1", "plate_2", "plate_3")


def build_vest(path):
    g = GLB()
    for name, (base, metal, rough, region) in VEST_MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    parts = am.vest_parts()
    tris = {}
    g.add_node("armor")
    for name in VEST_NODES:
        prims = mk.build_primitives(parts[name], split_materials=True)
        tris[name] = mk.triangle_count(prims)
        g.add_node(name, "armor", mesh=g.add_mesh(name, prims))
    g.save(path)
    return tris


def build_plate(path, clips=None):
    clips = clips or armor_clips.CLIPS
    g = GLB()
    for name, (base, metal, rough, region) in PLATE_MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    prims = mk.build_primitives(am.plate_mesh(), split_materials=True)
    tris = {"plate_body": mk.triangle_count(prims)}
    mesh = g.add_mesh("plate_body", prims)
    rest, _ = rig.PlatePose().solve()
    g.add_node("viewmodel")
    g.add_node("plate", "viewmodel", rest["plate"]["translation"], rest["plate"]["rotation"])
    g.add_node("plate_body", "plate", np.zeros(3), mesh=mesh)
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r["translation"], r["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", np.array([0.0, -UPPER_LEN, 0.0]), rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    rest_vals = {n: dict(v) for n, v in rest.items()}
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tris, info
