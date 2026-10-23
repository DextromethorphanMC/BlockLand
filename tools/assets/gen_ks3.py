"""Builds models/gun/ks3.glb (VIEWMODEL_SPEC sections 3-6, 15-17, 19-21 and 25) from ks3_model (the converted source
parts plus our own) with the ks3_rig / ks3_clips animation."""
import numpy as np

import armrig
import ks3_model as km
import ks3_rig as rig
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from vmath import quat_identity

# name: (base rgba (linear), metallic, roughness) -- names are stable: texture sets (section 20) key on them
MATERIALS = {
    "receiver": ((0.035, 0.035, 0.038, 1), 0.60, 0.50),     # black cerakote aluminium
    "barrel": ((0.030, 0.030, 0.032, 1), 0.85, 0.42),       # matte phosphated steel
    "pump": ((0.022, 0.022, 0.024, 1), 0.0, 0.70),          # black polymer forend
    "stock": ((0.022, 0.022, 0.024, 1), 0.0, 0.75),
    "grip": ((0.020, 0.020, 0.022, 1), 0.0, 0.85),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50),
    "shell": ((0.45, 0.025, 0.020, 1), 0.0, 0.45),          # red plastic hull
    "brass": ((0.78, 0.56, 0.24, 1), 1.0, 0.30),
}
STATIC = rig.STATIC
MOVING = ("pump", "loading_gate", "trigger", "sight_iron", "sight_rear", "muzzle", "ejection")


def build_meshes():
    """node name -> Mesh in that node's local space (static parts stay in the design frame: their nodes sit at -P0)."""
    return {
        "receiver": km.receiver_mesh(),
        "grip": km.grip_mesh(),
        "barrel": km.barrel_mesh(),
        "stock": km.stock_mesh(),
        "safety": km.safety_mesh(),
        "tube_standard": km.tube_mesh(km.TUBE_END, False),
        "tube_extended": km.tube_mesh(km.TUBE_EXT_END, True),
        "pump": km.pump_mesh(),
        "loading_gate": km.loading_gate_mesh(),
        "trigger": km.trigger_mesh(),
        "sight_iron": km.front_sight_mesh(),
        "sight_rear": km.rear_sight_mesh(),
        "shell_load": km.shell_mesh(),
        "fx_shell": km.shell_mesh(spent=True, seg=10),
    }


def add_materials(g):
    for name, (base, metal, rough) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": km.MATERIAL_REGIONS[name]})


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
    for name in STATIC + MOVING:
        parent, t = rig.NODES[name]
        rot = None
        if name == "ejection":
            rot = rig.ejection_quat()
        g.add_node(name, parent, t, rot, mesh=mesh_idx.get(name))
        if name == "sight_rear":
            g.add_node("aim_iron", "gun", rig.AIM_IRON_T)
    for name, (parent, t, rot, scale) in rig.MOUNTS.items():       # section 19: attachment mounts
        g.add_node(name, parent, t, rot, scale=scale)
    for side, shoulder in (("R", rig.SHOULDER_R), ("L", rig.SHOULDER_L)):
        g.add_node(f"arm_{side}", "viewmodel", shoulder, rest[f"arm_{side}"]["rotation"])
        g.add_node(f"arm_{side}_fore", f"arm_{side}", rig.ARM_FORE_T, rest[f"arm_{side}_fore"]["rotation"])
        armrig.add_hand_nodes(g, side, rest)
    # the shell the support hand carries to the port (collapsed except while it does, section 25)
    g.add_node("shell_load", "arm_L_hand", rig.HOLD_T, rig.HOLD_Q, scale=np.zeros(3), mesh=mesh_idx["shell_load"])
    g.add_node("camera", "viewmodel")
    g.add_node("fx_shell", None, mesh=mesh_idx["fx_shell"], extras={"hidden": True})
    rest_vals = {
        "gun": {"translation": rest["gun"]["translation"], "rotation": rest["gun"]["rotation"]},
        "pump": {"translation": rest["pump"]["translation"]},
        "loading_gate": {"rotation": rest["loading_gate"]["rotation"]},
        "trigger": {"rotation": rest["trigger"]["rotation"]},
        "camera": {"rotation": quat_identity()},
        "shell_load": {"translation": rig.HOLD_T, "rotation": rig.HOLD_Q, "scale": np.zeros(3)},
    }
    for side in ("R", "L"):
        rest_vals[f"arm_{side}"] = {"translation": rest[f"arm_{side}"]["translation"],
                                    "rotation": rest[f"arm_{side}"]["rotation"]}
        rest_vals[f"arm_{side}_fore"] = {"rotation": rest[f"arm_{side}_fore"]["rotation"]}
        rest_vals.update(armrig.rest_values(rest, side))
    info = {clip.name: bake_clip(g, clip, rest_vals) for clip in clips}
    g.save(path)
    return tri_counts, info
