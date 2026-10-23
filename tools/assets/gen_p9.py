"""Builds models/gun/p9.glb (VIEWMODEL_SPEC sections 3-6)."""
import numpy as np

import armrig
import meshkit as mk
import p9_model as pm
import p9_parts as pp
import p9_rig as rig
import textures as tx
from anim import sample_times
from glb import GLB
from vmath import make_continuous, quat_axis_angle, quat_identity

# name: (base rgba, metallic, roughness, extra)
MATERIALS = {
    "polymer": ((0.050, 0.050, 0.053, 1), 0.0, 0.80, {}),
    "grip": ((0.052, 0.052, 0.055, 1), 0.0, 0.90, {"texture": "stipple"}),
    "slide": ((0.060, 0.060, 0.064, 1), 0.80, 0.45, {"texture": "brushed"}),
    "barrel": ((0.38, 0.37, 0.36, 1), 1.0, 0.32, {}),
    "steel_dark": ((0.035, 0.035, 0.038, 1), 0.70, 0.50, {}),
    "sight_dots": ((0.55, 1.0, 0.55, 1), 0.0, 0.40, {"emissive": (0.20, 0.75, 0.28)}),
    "mag": ((0.045, 0.045, 0.048, 1), 0.10, 0.70, {}),
    "mag_base": ((0.050, 0.050, 0.052, 1), 0.0, 0.75, {}),
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, {}),
}
TEXTURES = {"stipple": lambda: tx.stipple(256), "brushed": lambda: tx.brushed(256)}


def build_meshes():
    """node name -> Mesh in that node's local space."""
    d = lambda m, origin: m.translated(-np.asarray(origin, float))
    return {
        "frame": pm.frame_mesh(),
        "slide": pm.slide_mesh(),
        "barrel": pm.barrel_mesh(),
        "sight_iron": d(pp.iron_sights_mesh(), (0, pm.SY1, 0)),
        "hammer": d(pp.hammer_mesh(), pm.HAMMER_PIVOT),
        "trigger": d(pp.trigger_mesh(), pm.TRIGGER_PIVOT),
        "slide_stop": d(pp.slide_stop_mesh(), pm.STOP_PIVOT),
        "mag_standard": pp.mag_mesh(False),
        "mag_extended": pp.mag_mesh(True),
        "fx_shell": pp.shell_mesh(1.25),
    }


def add_materials(g):
    for name, (base, metal, rough, ex) in MATERIALS.items():
        png = tx.png_bytes(TEXTURES[ex["texture"]]()) if "texture" in ex else None
        g.add_material(name, base, metal, rough, emissive=ex.get("emissive"),
                       alpha_mode=ex.get("alpha_mode", "OPAQUE"), alpha_cutoff=ex.get("cutoff"),
                       double_sided=ex.get("double", False), texture_png=png)


def bake_clip(g, clip, rest):
    times = sample_times(clip.duration, clip.fps, clip.extra_times)
    frames = [clip.pose(float(t)).solve()[0] for t in times]
    channels = []
    for node, comps in rest.items():
        for path, rest_val in comps.items():
            vals = np.array([f[node][path] for f in frames])
            if path == "rotation":
                vals = np.array(make_continuous(list(vals)))
                same = np.all(np.abs(np.abs(vals @ rest_val) - 1) < 1e-7)
            else:
                same = np.all(np.abs(vals - rest_val) < 1e-7)
            if same and node not in clip.force_nodes:
                continue
            interp = "STEP" if path == "scale" else "LINEAR"
            ts, vs = times, vals
            if interp == "STEP":                      # keep only change points
                keep = [0] + [i for i in range(1, len(vs)) if np.any(np.abs(vs[i] - vs[i - 1]) > 1e-9)]
                ts, vs = times[keep], vs[keep]
                if len(ts) == 1 or ts[-1] < times[-1]:
                    ts = np.append(ts, times[-1])
                    vs = np.vstack([vs, vs[-1:]])
            channels.append((node, path, ts, vs, interp))
    extras = {"events": [{"time": round(float(t), 4), "name": n} for t, n in clip.events]}
    if clip.loop:
        extras["loop"] = True
    g.add_animation(clip.name, channels, extras)
    return len(channels)


def build(path, clips):
    g = GLB()
    add_materials(g)
    meshes = build_meshes()
    mesh_idx, tri_counts = {}, {}
    for name, m in meshes.items():
        prims = mk.build_primitives(m)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in ("frame", "slide", "barrel", "hammer", "trigger", "slide_stop", "mag", "muzzle", "ejection"):
        parent, t = rig.NODES[name]
        rot = rest.get(name, {}).get("rotation")
        if name == "mag":
            rot = rig.MAG_Q
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "slide":
            g.add_node("sight_iron", "slide", rig.NODES["sight_iron"][1], mesh=mesh_idx["sight_iron"])
            g.add_node("aim_iron", "slide", rig.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
            g.add_node("mag_extended", "mag", mesh=mesh_idx["mag_extended"])
    for name, (parent, t, rot, scale) in rig.MOUNTS.items():       # section 19: attachment mounts
        g.add_node(name, parent, t, rot, scale=scale)
    for side, shoulder in (("R", rig.SHOULDER_R), ("L", rig.SHOULDER_L)):
        g.add_node(f"arm_{side}", "viewmodel", shoulder, rest[f"arm_{side}"]["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", rig.ARM_FORE_T, rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    g.add_node("camera", "viewmodel")
    g.add_node("fx_shell", None, mesh=mesh_idx["fx_shell"], extras={"hidden": True})
    rest_vals = {
        "gun": {"translation": rest["gun"]["translation"], "rotation": rest["gun"]["rotation"]},
        "slide": {"translation": rest["slide"]["translation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "hammer": {"rotation": rest["hammer"]["rotation"]},
        "slide_stop": {"rotation": rest["slide_stop"]["rotation"]},
        "mag": {"translation": rig.NODES["mag"][1], "rotation": rig.MAG_Q, "scale": np.ones(3)},
        "arm_R": {"rotation": rest["arm_R"]["rotation"]},
        "arm_R_fore": {"rotation": rest["arm_R_fore"]["rotation"]},
        "arm_L": {"rotation": rest["arm_L"]["rotation"]},
        "arm_L_fore": {"rotation": rest["arm_L_fore"]["rotation"]},
        "camera": {"rotation": quat_identity()},
    }
    for side in ("R", "L"):
        rest_vals.update(armrig.rest_values(rest, side))
    info = {}
    for clip in clips:
        info[clip.name] = bake_clip(g, clip, rest_vals)
    g.save(path)
    return tri_counts, info
