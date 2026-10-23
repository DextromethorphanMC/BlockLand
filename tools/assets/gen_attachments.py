"""Builds the universal attachment models (VIEWMODEL_SPEC sections 19 and 22): models/attachment/<model>.glb, and
the scopes' reticles (textures/scope/reticle_<kind>.png).

Every file has the root node `attachment` (identity, origin = the mount point) and the helper nodes its slot needs
(optics `aim`, scopes also `lens_ocular` / `lens_objective`, laser `emitter`, suppressor `muzzle`, charm `pivot`)."""
import attach_models as am
import meshkit as mk
import textures as tx
from glb import GLB


def add_materials(g, used):
    for name, (base, metal, rough, ex) in am.MATERIALS.items():
        if name not in used:
            continue
        png = tx.png_bytes(am.TEXTURES[ex["texture"]]()) if "texture" in ex else None
        region = am.MATERIAL_REGIONS.get(name)
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png,
                       extras={"region": region} if region else None)


def build_one(name, path):
    part = am.MODELS[name]()
    used = set()
    for m in part.meshes.values():
        used.update(m.m)
    g = GLB()
    add_materials(g, used)
    mesh_idx, tris = {}, 0
    for node, m in part.meshes.items():
        prims = mk.build_primitives(m)
        tris += mk.triangle_count(prims)
        mesh_idx[node] = g.add_mesh(node, prims)
    for node, parent, t in part.nodes:
        g.add_node(node, parent, t, mesh=mesh_idx.get(node))
    g.save(path)
    return tris


def build(path, names=None):
    """path(*parts) -> absolute output path (see build_all); names: only these models (default: all)."""
    out = {}
    for name in names or am.MODELS:
        out[name] = build_one(name, path("models", "attachment", f"{name}.glb"))
        if name in am.RETICLES:
            kind = am.RETICLES[name]
            tx.save_png(am.reticle_image(kind), path("textures", "scope", f"reticle_{kind}.png"))
    return out
