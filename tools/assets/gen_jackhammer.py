"""Builds models/gun/jackhammer.glb (VIEWMODEL_SPEC sections 3-6, 15-21 and 40) from jackhammer_model with the
jackhammer_rig / jackhammer_clips animation.

python -I tools/assets/build_all.py jackhammer"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import armrig                                      # noqa: E402
import jackhammer_model as jm                      # noqa: E402
import jackhammer_rig as rig                       # noqa: E402
import meshkit as mk                               # noqa: E402
from anim import sample_times                      # noqa: E402
from glb import GLB                                # noqa: E402
from vmath import make_continuous, quat_identity   # noqa: E402

# name: (base rgba (linear), metallic, roughness, region) -- names are stable: texture sets (section 20) key on them,
# the gold skin tints `steel`, `steel_dark` and `barrel`
MATERIALS = {
    "steel": ((0.030, 0.030, 0.033, 1), 0.70, 0.52, "body"),         # black anodised aluminium receiver, lower, handle
    "stock": ((0.024, 0.024, 0.026, 1), 0.0, 0.72, "stock"),         # black polymer butt and bay housing
    "forend": ((0.024, 0.024, 0.026, 1), 0.0, 0.70, "handguard"),    # polymer operating sleeve
    "grip": ((0.020, 0.020, 0.021, 1), 0.0, 0.88, "grip"),           # rubber grip panels
    "barrel": ((0.034, 0.031, 0.029, 1), 0.85, 0.45, "barrel"),      # dark steel barrel, collar, flash hider
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50, "detail"),  # trigger, selector, pins, screws, sights, rail
    "cassette": ((0.028, 0.028, 0.030, 1), 0.0, 0.62, "magazine"),   # the Ammo-Cassette
    "rubber": ((0.020, 0.020, 0.021, 1), 0.0, 0.90, "none"),         # butt pad
    "shell": ((0.45, 0.025, 0.020, 1), 0.0, 0.45, "none"),           # red plastic hulls
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30, "none"),
}
MOVING = ("bolt", "trigger", "latch", "sight_iron", "sight_rear", "muzzle_brake", "mag", "muzzle", "ejection")


def build_meshes():
    """node name -> Mesh in that node's local space (static parts and the bolt stay in the design frame: their nodes
    sit at -P0)."""
    d = lambda m, node: m.translated(-np.asarray(rig.MESH_ORIGIN.get(node, (0.0, 0.0, 0.0)), float))
    stock = jm.stock_mesh()
    stock.merge(jm.pad_mesh())
    return {
        "receiver": jm.receiver_mesh(),
        "handle": jm.handle_mesh(),
        "stock": stock,
        "lower": jm.lower_mesh(),
        "barrel": jm.barrel_mesh(),
        "bolt": jm.forend_mesh(),
        "trigger": d(jm.trigger_mesh(), "trigger"),
        "latch": d(jm.latch_mesh(), "latch"),
        "sight_iron": d(jm.front_sight_mesh(), "sight_iron"),
        "sight_rear": d(jm.rear_sight_mesh(), "sight_rear"),
        "muzzle_brake": d(jm.muzzle_brake_mesh(), "muzzle_brake"),
        "mag_standard": jm.cassette_mesh(local=True),
        "fx_shell": jm.shell_mesh(),
    }


def add_materials(g):
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})


def bake_clip(g, clip, rest):
    """gen_p9.bake_clip, plus STEP channels a clip asks for (clip.step_nodes: (node, path) pairs keyed at every
    sample): the cassette's index turn snaps back by one chamber at the end of a shot — invisible, the cassette is
    ten-fold symmetric — without passing through the angles in between."""
    times = sample_times(clip.duration, clip.fps, clip.extra_times)
    frames = [clip.pose(float(t)).solve()[0] for t in times]
    step = set(getattr(clip, "step_nodes", ()))
    channels = []
    for node, comps in rest.items():
        for path, rest_val in comps.items():
            vals = np.array([f[node][path] if path in f.get(node, {}) else rest_val for f in frames])
            if path == "rotation":
                vals = np.array(make_continuous(list(vals)))
                same = np.all(np.abs(np.abs(vals @ rest_val) - 1) < 1e-7)
            else:
                same = np.all(np.abs(vals - rest_val) < 1e-7)
            if same and node not in clip.force_nodes:
                continue
            interp = "STEP" if path == "scale" or (node, path) in step else "LINEAR"
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
    mesh_idx, tri_counts = {}, {}
    for name, m in build_meshes().items():
        prims = mk.build_primitives(m)
        tri_counts[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.Pose().solve()
    g.add_node("viewmodel")
    g.add_node("gun", "viewmodel", rest["gun"]["translation"], rest["gun"]["rotation"])
    for name in rig.STATIC + MOVING:
        parent, t = rig.NODES[name]
        rot = None
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
        if name == "mag":
            g.add_node("mag_standard", "mag", mesh=mesh_idx["mag_standard"])
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
        "bolt": {"translation": rest["bolt"]["translation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "latch": {"rotation": rest["latch"]["rotation"]},
        "mag": {"translation": rig.MAG_REST, "rotation": quat_identity(), "scale": np.ones(3)},
        "camera": {"rotation": quat_identity()},
    }
    for side in ("R", "L"):
        rest_vals[f"arm_{side}"] = {"translation": rest[f"arm_{side}"]["translation"],
                                    "rotation": rest[f"arm_{side}"]["rotation"]}
        rest_vals[f"arm_{side}_fore"] = {"rotation": rest[f"arm_{side}_fore"]["rotation"]}
        rest_vals.update(armrig.rest_values(rest, side))
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info


if __name__ == "__main__":
    import build_all
    import jackhammer_clips
    print(build(build_all.path("models", "gun", "jackhammer.glb"), jackhammer_clips.all_clips()))
