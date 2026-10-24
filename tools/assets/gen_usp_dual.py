"""Builds models/gun/usp_dual.glb and, from the same rig and clip code, the Cerberus skin's
models/gun/usp_dual_cerberus.glb (VIEWMODEL_SPEC section 26): a pair of pistols, the right one under `gun`, its
left-handed twin under `gun_L` (every node suffixed `_L`, see dual_rig)."""
import numpy as np

import armrig
import dual_clips
import dual_rig as dr
import meshkit as mk
import usp_model as um
import usp_parts as up
from gen_p9 import bake_clip
from gen_usp import MATERIALS as USP_MATERIALS, add_materials
from glb import GLB
from vmath import quat_identity

MIRROR = np.diag([-1.0, 1.0, 1.0, 1.0])
# right gun's nodes in build order: (name, parent, has a mesh)
GUN_NODES = ("frame", "slide", "barrel", "hammer", "trigger", "slide_stop", "mag", "muzzle", "ejection")


def usp_layout():
    return dr.PistolLayout(
        name="usp", p0=np.array([0.0, -0.0450, 0.0600]), grip_angle=um.GRIP_ANGLE, grip_c0=um.GRIP_C0,
        grip_len=um.GRIP_LEN, mag_origin=um.MAG_ORIGIN, hammer_pivot=um.HAMMER_PIVOT,
        trigger_pivot=um.TRIGGER_PIVOT, stop_pivot=um.STOP_PIVOT, crown_z=um.CROWN_Z, slide_front=um.SZ0,
        laser_origin=um.LASER_ORIGIN, eject_pos=np.array([um.SW, 0.0060, 0.0100]), release=um.RELEASE,
        stop_tab=np.array([-0.0145, -0.0197, 0.0152]), charm_pos=np.array([-0.0124, -0.0300, -0.0700]))


def usp_gunset():
    """The USP's meshes in their nodes' local spaces (right gun), its materials and layout."""
    d = lambda m, origin: m.translated(-np.asarray(origin, float))
    meshes = {
        "frame": um.frame_mesh(),
        "slide": um.slide_mesh(),
        "sight_iron": up.iron_sights_mesh(),               # slide space (the node sits at the slide's origin)
        "barrel": um.barrel_mesh(),
        "hammer": d(up.hammer_mesh(), um.HAMMER_PIVOT),
        "trigger": d(up.trigger_mesh(), um.TRIGGER_PIVOT),
        "slide_stop": d(up.slide_stop_mesh(), um.STOP_PIVOT),
        "mag_standard": up.mag_mesh(False),
        "mag_extended": up.mag_mesh(True),
    }
    return {"meshes": meshes, "shell": up.shell_mesh(1.25), "materials": USP_MATERIALS, "layout": usp_layout()}


def _mirrored(mesh):
    return mesh.transformed(MIRROR)


def build(path, gunset=None, clips=None):
    """A gunset may add a skin's own clips (`extra_clips`: rig -> clips) and effect nodes (`fx`: materials with their
    PNGs, meshes, nodes and their rest values; VIEWMODEL_SPEC section 38)."""
    gunset = gunset or usp_gunset()
    rig = dr.DualRig(gunset["layout"])
    clips = dual_clips.make_clips(rig) if clips is None else clips
    clips = clips + gunset.get("extra_clips", lambda r: [])(rig)
    fx = gunset.get("fx")
    g = GLB()
    add_materials(g, gunset["materials"])
    if fx:
        for name, (base, metal, rough, ex) in fx["materials"].items():
            g.add_material(name, base, metal, rough, emissive=ex.get("emissive"), alpha_mode=ex.get("alpha_mode", "OPAQUE"),
                           double_sided=ex.get("double", False), texture_png=fx["textures"][ex["png"]])
    mesh_idx, tri_counts = {}, {}
    for name, m in gunset["meshes"].items():
        for sfx, mesh in (("", m), (dr.SUFFIX, _mirrored(m))):
            prims = mk.build_primitives(mesh)
            tri_counts[name + sfx] = mk.triangle_count(prims)
            mesh_idx[name + sfx] = g.add_mesh(name + sfx, prims)
    for name, mesh in (fx or {}).get("meshes", {}).items():
        prims = mk.build_primitives(mesh)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    shell = mk.build_primitives(gunset["shell"])
    tri_counts["fx_shell"] = mk.triangle_count(shell)
    mesh_idx["fx_shell"] = g.add_mesh("fx_shell", shell)

    rest, _ = rig.pose().solve()
    g.add_node("viewmodel")
    for sfx in ("", dr.SUFFIX):
        left = sfx == dr.SUFFIX
        mt = dr.mirror_t if left else (lambda t: t)
        mq = dr.mirror_q if left else (lambda q: q)
        g.add_node("gun" + sfx, "viewmodel", rest["gun" + sfx]["translation"], rest["gun" + sfx]["rotation"])
        for name in GUN_NODES:
            parent, t = rig.nodes[name]
            rot = rest.get(name + sfx, {}).get("rotation")
            if name == "mag":
                rot = mq(rig.mag_q)
            if name == "ejection":
                rot = rig.ejection_quat(left)
            g.add_node(name + sfx, parent + sfx, mt(t), rot, mesh=mesh_idx.get(name + sfx))
            if name == "slide":
                g.add_node("sight_iron" + sfx, "slide" + sfx, mesh=mesh_idx["sight_iron" + sfx])
            if name == "mag":
                for mag in ("mag_standard", "mag_extended"):
                    g.add_node(mag + sfx, "mag" + sfx, mesh=mesh_idx[mag + sfx])
        for name, (parent, t, rot) in rig.mounts.items():        # section 19 mounts (and their `_L` twins)
            if left and name == "mount_charm":
                continue
            g.add_node(name + sfx, parent + sfx, mt(t), mq(rot) if rot is not None else None)
    for name, parent, t, rot, scale, mesh in (fx or {}).get("nodes", ()):       # effect nodes (collapsed at rest)
        g.add_node(name, parent, t, rot, scale=scale, mesh=mesh_idx.get(mesh) if mesh else None)
    for side, shoulder in (("R", dr.SHOULDER_R), ("L", dr.SHOULDER_L)):
        g.add_node(f"arm_{side}", "viewmodel", shoulder, rest[f"arm_{side}"]["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", dr.ARM_FORE_T, rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    g.add_node("camera", "viewmodel")
    g.add_node("fx_shell", None, mesh=mesh_idx["fx_shell"], extras={"hidden": True})

    rest_vals = {"camera": {"rotation": quat_identity()}}
    for sfx in ("", dr.SUFFIX):
        left = sfx == dr.SUFFIX
        rest_vals["gun" + sfx] = {"translation": rest["gun" + sfx]["translation"], "rotation": rest["gun" + sfx]["rotation"]}
        rest_vals["slide" + sfx] = {"translation": rest["slide" + sfx]["translation"]}
        for name in ("trigger", "hammer", "slide_stop"):
            rest_vals[name + sfx] = {"rotation": rest[name + sfx]["rotation"]}
        mt = dr.mirror_t if left else (lambda t: t)
        mq = dr.mirror_q if left else (lambda q: q)
        rest_vals["mag" + sfx] = {"translation": mt(rig.nodes["mag"][1]), "rotation": mq(rig.mag_q), "scale": np.ones(3)}
    for side in ("R", "L"):
        rest_vals[f"arm_{side}"] = {"rotation": rest[f"arm_{side}"]["rotation"]}
        rest_vals[f"arm_{side}_fore"] = {"rotation": rest[f"arm_{side}_fore"]["rotation"]}
        rest_vals.update(armrig.rest_values(rest, side))
    if fx:
        rest_vals.update(fx["rest"])
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info
