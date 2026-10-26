"""Builds models/block/revive_antenna.glb (VIEWMODEL_SPEC section 51): the antenna in its two-block frame, the case and
the mast as two meshes, the beacon lit and dark as two nodes at its centre (the renderer draws one of them)."""
import revive_antenna_model as am
import meshkit as mk
from glb import GLB
from vmath import quat_identity

# name: (base rgba (linear), metallic, roughness, emissive, region)
MATERIALS = {
    "case": ((0.10, 0.11, 0.06, 1), 0.0, 0.60, None, "body"),
    "case_dark": ((0.03, 0.03, 0.03, 1), 0.0, 0.55, None, "body"),
    "rubber": ((0.02, 0.02, 0.02, 1), 0.0, 0.90, None, "body"),
    "hardware": ((0.20, 0.20, 0.21, 1), 1.0, 0.45, None, "hardware"),
    "mast": ((0.22, 0.23, 0.20, 1), 0.6, 0.40, None, "detail"),
    "head": ((0.10, 0.11, 0.06, 1), 0.0, 0.60, None, "detail"),
    "dish": ((0.30, 0.30, 0.24, 1), 0.0, 0.65, None, "detail"),
    "whip": ((0.02, 0.02, 0.02, 1), 0.3, 0.50, None, "detail"),
    "panel_white": ((0.70, 0.70, 0.66, 1), 0.0, 0.55, None, "none"),
    "panel_red": ((0.55, 0.02, 0.02, 1), 0.0, 0.55, None, "none"),
    "screen": ((0.01, 0.03, 0.01, 1), 0.0, 0.20, (0.05, 0.30, 0.10), "none"),
    "beacon": ((0.60, 0.02, 0.01, 1), 0.0, 0.25, (1.0, 0.06, 0.03), "none"),
    "beacon_off": ((0.16, 0.012, 0.01, 1), 0.0, 0.18, None, "none"),
}
NODES = ("base", "mast", "beacon", "beacon_off")


def build(path):
    g = GLB()
    for name, (base, metal, rough, emissive, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, emissive=emissive, extras={"region": region})
    tris = {}
    meshes = {}
    for name, m in am.parts().items():
        prims = mk.build_primitives(m.translated(-am.PIVOTS[name]), split_materials=True)
        tris[name] = mk.triangle_count(prims)
        meshes[name] = g.add_mesh(name, prims)
    g.add_node("antenna")
    for name in NODES:
        g.add_node(name, "antenna", am.PIVOTS[name], quat_identity(), mesh=meshes[name])
    g.save(path)
    return tris
