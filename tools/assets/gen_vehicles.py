"""Builds models/vehicle/<id>.glb (tools/vehicles/VEHICLE_SPEC.md section 3, VIEWMODEL_SPEC section 57): the vehicle's
parts modelled in vehicle space by its model script (`vehicle_<id>.py`: build(rig)), written as nodes with local
translations and rotations (pivots and axes as the contract names them) and meshes relative to their node. Materials
come from one table (colours are the fallback; pbr_models_vehicles binds the texture sets and regions)."""
import numpy as np

import meshkit as mk
import vehicle_parts as vp
from glb import GLB

# name: (base rgba (linear), metallic, roughness, emissive, region, alpha mode)
MATERIALS = {
    "paint": ((0.40, 0.04, 0.03, 1), 0.0, 0.25, None, "body", "OPAQUE"),
    "paint_accent": ((0.02, 0.02, 0.022, 1), 0.0, 0.30, None, "body", "OPAQUE"),
    "frame": ((0.03, 0.03, 0.032, 1), 0.6, 0.40, None, "hardware", "OPAQUE"),
    "metal": ((0.45, 0.45, 0.46, 1), 1.0, 0.40, None, "hardware", "OPAQUE"),
    "chrome": ((0.80, 0.80, 0.82, 1), 1.0, 0.08, None, "hardware", "OPAQUE"),
    "alu": ((0.55, 0.56, 0.57, 1), 1.0, 0.45, None, "detail", "OPAQUE"),
    "exhaust": ((0.20, 0.19, 0.18, 1), 1.0, 0.45, None, "hardware", "OPAQUE"),
    "rubber": ((0.02, 0.02, 0.02, 1), 0.0, 0.85, None, "detail", "OPAQUE"),
    "tire": ((0.025, 0.025, 0.025, 1), 0.0, 0.88, None, "detail", "OPAQUE"),
    "plastic": ((0.03, 0.03, 0.03, 1), 0.0, 0.60, None, "detail", "OPAQUE"),
    "seat": ((0.03, 0.025, 0.02, 1), 0.0, 0.55, None, "detail", "OPAQUE"),
    "fabric": ((0.08, 0.08, 0.08, 1), 0.0, 0.90, None, "detail", "OPAQUE"),
    "deck": ((0.80, 0.80, 0.78, 1), 0.0, 0.70, None, "body", "OPAQUE"),
    "gelcoat": ((0.85, 0.85, 0.84, 1), 0.0, 0.15, None, "body", "OPAQUE"),
    "armor": ((0.12, 0.13, 0.07, 1), 0.0, 0.65, None, "body", "OPAQUE"),
    "track": ((0.10, 0.10, 0.10, 1), 1.0, 0.55, None, "detail", "OPAQUE"),
    "gunmetal": ((0.06, 0.06, 0.065, 1), 0.8, 0.45, None, "hardware", "OPAQUE"),
    "propeller": ((0.75, 0.62, 0.40, 1), 1.0, 0.25, None, "hardware", "OPAQUE"),
    "glass": ((0.35, 0.40, 0.42, 0.25), 0.0, 0.05, None, "none", "BLEND"),
    "lens": ((0.02, 0.025, 0.03, 1), 0.0, 0.05, None, "none", "OPAQUE"),
    "headlight": ((0.95, 0.95, 0.90, 1), 0.0, 0.10, (1.0, 0.96, 0.85), "none", "OPAQUE"),
    "taillight": ((0.55, 0.02, 0.02, 1), 0.0, 0.15, (0.9, 0.03, 0.02), "none", "OPAQUE"),
    "indicator": ((0.80, 0.40, 0.02, 1), 0.0, 0.15, None, "none", "OPAQUE"),
    "gauge": ((0.02, 0.02, 0.02, 1), 0.0, 0.25, (0.10, 0.22, 0.30), "none", "OPAQUE"),
    "reflector": ((0.50, 0.02, 0.02, 1), 0.0, 0.20, None, "none", "OPAQUE"),
    # the fighter jet: its afterburner's flame core (shown only while boosting) and the green starboard nav light
    "afterburner": ((1.00, 0.62, 0.25, 1), 0.0, 0.90, (6.0, 3.4, 1.4), "none", "OPAQUE"),
    "nav_green": ((0.03, 0.45, 0.10, 1), 0.0, 0.15, (0.05, 0.85, 0.20), "none", "OPAQUE"),
}
#: the track's v advances this many units per metre of track before uvScale (the runtime scrolls it with the speed)
TRACK_V_PER_METRE = 20.0
#: vehicle-scale modelling (VIEWMODEL_SPEC section 57): parts seen from a metre or more away get bigger edge rounds
#: (up to 6 mm, so the highlight on an edge still reads) and a coarser chord error than weapons held at the eye; applied
#: to the shared settings in memory only while a vehicle builds (model_quality.json keeps the weapons' values)
VEHICLE_QUALITY = {"curve_error_m": 0.0004, "max_radial_segments": 48,
                   "edges": {"max_radius_m": 0.006, "min_radius_m": 0.0005, "fillet_error_m": 0.0004,
                             "skip_radius_m": 0.0004}}


class vehicle_quality:
    """`with vehicle_quality():` builds with VEHICLE_QUALITY, restoring the weapons' settings afterwards."""

    def __enter__(self):
        import model_quality as mq
        self.saved = {k: mq.SETTINGS[k] for k in VEHICLE_QUALITY if k != "edges"}
        self.saved_edges = {k: mq.EDGES[k] for k in VEHICLE_QUALITY["edges"]}
        for k, v in VEHICLE_QUALITY.items():
            if k != "edges":
                mq.SETTINGS[k] = v
        mq.EDGES.update(VEHICLE_QUALITY["edges"])
        return self

    def __exit__(self, *exc):
        import model_quality as mq
        mq.SETTINGS.update(self.saved)
        mq.EDGES.update(self.saved_edges)
        return False


def _quat(r):
    """3x3 rotation -> (x, y, z, w)."""
    m = np.asarray(r, float)
    tr = m[0, 0] + m[1, 1] + m[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        w, x, y, z = 0.25 * s, (m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        w, x, y, z = (m[2, 1] - m[1, 2]) / s, 0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        w, x, y, z = (m[0, 2] - m[2, 0]) / s, (m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        w, x, y, z = (m[1, 0] - m[0, 1]) / s, (m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s
    q = np.array([x, y, z, w])
    return q / np.linalg.norm(q)


def write(rig, path, extras=None):
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
        p = mk.build_primitives(mesh.transformed(local), split_materials=True)
        prims[name] = p
        for q in p:
            if q["material"] not in used:
                used.append(q["material"])
    for name in used:
        base, metal, rough, emissive, region, alpha = MATERIALS[name]
        ex = {"region": region}
        if name == "track":
            ex["scroll"] = {"vPerMetre": TRACK_V_PER_METRE}
        g.add_material(name, base, metal, rough, emissive=emissive, alpha_mode=alpha, extras=ex)
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
        g.add_node(name, parent, t, _quat(r), mesh=mesh, extras=(extras or {}).get(name))
    g.save(path)
    return tris


def build(vehicle_id, path):
    import importlib
    mod = importlib.import_module(f"vehicle_{vehicle_id}")
    rig = vp.Rig("body")
    with vehicle_quality():
        mod.build(rig)
        return write(rig, path, getattr(mod, "NODE_EXTRAS", None))
