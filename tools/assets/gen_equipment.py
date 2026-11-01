"""Builds models/equipment/<id>.glb for the equipment items (VIEWMODEL_SPEC sections 15-17, 19-21, 31 and 36)."""
import numpy as np

import armrig
import equip_rig as rig
from equip_rig import HIDDEN_AT_REST
import meshkit as mk
from gen_p9 import bake_clip
from glb import GLB
from ik import UPPER_LEN
from vmath import quat_conj

ITEM_IDS = ("frag_grenade", "flashbang", "smoke_grenade", "molotov", "throwing_knife", "adrenaline", "flashlight")

# name: (base rgba (linear), metallic, roughness, region, options) -- names are stable: texture sets (section 20) key
# on them, skins on the regions
_PAINT = ((0.20, 0.235, 0.15, 1), 0.0, 0.50)
_FUZE = ((0.13, 0.13, 0.13, 1), 0.8, 0.42)
_LEVER = ((0.46, 0.46, 0.43, 1), 1.0, 0.45)
_PIN = ((0.72, 0.72, 0.72, 1), 1.0, 0.30)
GRENADE = {"body": (*_PAINT, "body", {}), "fuze": (*_FUZE, "body", {}), "lever": (*_LEVER, "lever", {}),
           "pin": (*_PIN, "pin", {})}
VENTED = dict(GRENADE, label=((0.62, 0.52, 0.10, 1), 0.0, 0.55, "label", {}),
              vent=((0.015, 0.015, 0.015, 1), 0.0, 0.9, "body", {}))
MATERIALS = {
    "frag_grenade": GRENADE,
    "flashbang": VENTED,
    "smoke_grenade": VENTED,
    "molotov": {
        "glass": ((0.24, 0.13, 0.04, 0.62), 0.0, 0.06, "glass", {"alpha_mode": "BLEND", "double_sided": True}),
        "liquid": ((0.62, 0.30, 0.05, 1), 0.0, 0.15, "liquid", {}),
        "rag": ((0.62, 0.56, 0.44, 1), 0.0, 0.92, "rag", {}),
        "flame": ((1.0, 0.55, 0.12, 1), 0.0, 1.0, "none", {"emissive": (1.0, 0.50, 0.10)}),
        "lighter": ((0.70, 0.70, 0.72, 1), 1.0, 0.32, "none", {}),
    },
    "throwing_knife": {
        "blade": ((0.60, 0.60, 0.62, 1), 1.0, 0.26, "blade", {}),
        "blade_edge": ((0.86, 0.87, 0.89, 1), 1.0, 0.12, "blade", {}),
        "grip": ((0.05, 0.05, 0.05, 1), 0.0, 0.85, "grip", {}),
    },
    "adrenaline": {
        "barrel": ((0.80, 0.78, 0.72, 1), 0.0, 0.45, "barrel", {}),
        "label": ((0.88, 0.70, 0.16, 1), 0.0, 0.55, "label", {}),
        "plunger": ((0.95, 0.40, 0.06, 1), 0.0, 0.40, "plunger", {}),
        "cap": ((0.12, 0.30, 0.82, 1), 0.0, 0.40, "cap", {}),
    },
    "flashlight": {
        "body": ((0.022, 0.022, 0.024, 1), 0.7, 0.36, "body", {}),
        "bezel": ((0.28, 0.28, 0.29, 1), 1.0, 0.32, "bezel", {}),
        "clip": ((0.06, 0.06, 0.065, 1), 0.9, 0.40, "body", {}),
        "reflector": ((0.86, 0.86, 0.87, 1), 1.0, 0.05, "none", {}),
        "lens": ((0.62, 0.68, 0.72, 0.28), 0.0, 0.03, "none", {"alpha_mode": "BLEND"}),
        "button": ((0.025, 0.025, 0.025, 1), 0.0, 0.85, "button", {}),
        "lit_glow": ((1.0, 0.95, 0.86, 1), 0.0, 1.0, "none", {"emissive": (1.0, 0.93, 0.80)}),
    },
}
EMPTY_POINTS = {"throwing_knife": ("tip",), "adrenaline": ("tip",), "flashlight": ("tip",)}


def build_meshes(it):
    """node name -> Mesh in that node's local space."""
    return {name: m.translated(-origin) for name, (m, origin) in it.parts.items()}


def build(path, key, clips=None):
    import equip_clips
    it = rig.item(key)
    clips = clips if clips is not None else equip_clips.clips(key)
    g = GLB()
    for name, (base, metal, rough, region, opt) in MATERIALS[key].items():
        g.add_material(name, base, metal, rough, emissive=opt.get("emissive"),
                       alpha_mode=opt.get("alpha_mode", "OPAQUE"), double_sided=opt.get("double_sided", False),
                       extras={"region": region})
    mesh_idx, tris = {}, {}
    for name, m in build_meshes(it).items():
        prims = mk.build_primitives(m, split_materials=True)
        tris[name] = mk.triangle_count(prims)
        mesh_idx[name] = g.add_mesh(name, prims)
    rest, _ = rig.EquipPose(it).solve()
    g.add_node("viewmodel")
    g.add_node("equipment", "viewmodel", rest["equipment"]["translation"], rest["equipment"]["rotation"])
    order = [n for n in it.parts if it.parent(n) == "equipment"] + [n for n in it.parts if it.parent(n) != "equipment"]
    order.sort(key=lambda n: 0 if it.parent(n) in ("equipment", "viewmodel") else 1)
    for name in order:
        parent = it.parent(name)
        r = rest.get(name)
        if r is not None:
            g.add_node(name, parent, r["translation"], r["rotation"], mesh=mesh_idx[name])
        else:
            g.add_node(name, parent, it.parts[name][1], mesh=mesh_idx[name])
        if name in HIDDEN_AT_REST:
            g.json["nodes"][g.node_index[name]]["scale"] = [0.0, 0.0, 0.0]
    for pt in EMPTY_POINTS.get(key, ()):
        g.add_node(pt, "equipment", it.points[pt])
    # section 19: the charm mount; its -Y hangs straight down in the rest pose
    g.add_node("mount_charm", "equipment", it.points["charm"], quat_conj(rest["equipment"]["rotation"]))
    g.add_node("camera", "viewmodel")
    for side in ("R", "L"):
        r = rest[f"arm_{side}"]
        g.add_node(f"arm_{side}", "viewmodel", r.get("translation", rig.SHOULDER_L if side == "L" else rig.SHOULDER_R),
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
