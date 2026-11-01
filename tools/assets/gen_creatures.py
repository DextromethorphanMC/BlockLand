"""Builds models/creature/<id>.glb (tools/creatures/SHARK_SPEC.md section 4): the creature's parts modelled in its own
space by its model script (`creature_<id>.py`: build(rig)), written as nodes with local translations and rotations and
meshes relative to their node, like the vehicles (gen_vehicles). Unlike the vehicles, material boundaries are not hard
edges: a creature's countershading meets on a smooth body. Colours are the fallback; pbr_models_creatures binds the
texture sets."""
import numpy as np

import meshkit as mk
import vehicle_parts as vp
from gen_vehicles import _quat, vehicle_quality
from glb import GLB

# name: (base rgba (linear), metallic, roughness, emissive, region, alpha mode)
MATERIALS = {
    "skin_back": ((0.16, 0.20, 0.24, 1), 0.0, 0.55, None, "body", "OPAQUE"),
    "skin_belly": ((0.70, 0.70, 0.66, 1), 0.0, 0.60, None, "body", "OPAQUE"),
    "mouth": ((0.15, 0.025, 0.03, 1), 0.0, 0.40, None, "none", "OPAQUE"),
    "tooth": ((0.82, 0.79, 0.70, 1), 0.0, 0.32, None, "none", "OPAQUE"),
    "eye": ((0.008, 0.008, 0.010, 1), 0.0, 0.04, None, "none", "OPAQUE"),
    "gill": ((0.04, 0.05, 0.06, 1), 0.0, 0.70, None, "none", "OPAQUE"),
}


def write(rig, path):
    """Writes the rig's nodes and meshes; returns {node: triangles}."""
    g = GLB()
    used = []
    prims = {}
    for name in rig.order:
        mesh = rig.meshes.get(name)
        if mesh is None or not mesh.f:
            continue
        pivot, rot = rig.world(name)
        local = np.eye(4)
        local[:3, :3] = rot.T
        local[:3, 3] = -rot.T @ pivot
        p = mk.build_primitives(mesh.transformed(local), hard_deg=40.0, split_materials=False)
        prims[name] = p
        for q in p:
            if q["material"] not in used:
                used.append(q["material"])
    for name in used:
        base, metal, rough, emissive, region, alpha = MATERIALS[name]
        g.add_material(name, base, metal, rough, emissive=emissive, alpha_mode=alpha, extras={"region": region})
    tris = {}
    for name in rig.order:
        parent, pivot, rot = rig.nodes[name]
        if parent is None:
            t, r = pivot, rot
        else:
            pp, pr = rig.world(parent)
            t, r = pr.T @ (pivot - pp), pr.T @ rot
        mesh = None
        if name in prims:
            mesh = g.add_mesh(name, prims[name])
            tris[name] = mk.triangle_count(prims[name])
        g.add_node(name, parent, t, _quat(r), mesh=mesh)
    g.save(path)
    return tris


def build(creature_id, path):
    import importlib
    mod = importlib.import_module(f"creature_{creature_id}")
    rig = vp.Rig("body")
    with vehicle_quality():
        mod.build(rig)
        return write(rig, path)
