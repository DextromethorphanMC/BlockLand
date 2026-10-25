"""Builds models/block/ammo_crate.glb (VIEWMODEL_SPEC section 46): the crate in its block frame, the lid and the two
latch levers on pivots of their own, and the `open` / `close` clips the block entity renderer plays."""
import numpy as np

import ammo_crate_model as cm
import meshkit as mk
from anim import Curve
from gen_p9 import bake_clip
from glb import GLB
from p9_clips import Clip
from vmath import quat_axis_angle, quat_identity

# name: (base rgba (linear), metallic, roughness, region)
MATERIALS = {
    "wood": ((0.10, 0.12, 0.05, 1), 0.0, 0.70, "body"),
    "marking": ((0.62, 0.45, 0.03, 1), 0.0, 0.60, "body"),
    "metal": ((0.20, 0.20, 0.21, 1), 1.0, 0.45, "hardware"),
    "rope": ((0.45, 0.33, 0.18, 1), 0.0, 0.90, "body"),
    "can": ((0.07, 0.09, 0.04, 1), 0.3, 0.55, "contents"),
    "mag": ((0.03, 0.03, 0.03, 1), 0.6, 0.45, "contents"),
    "brass": ((0.80, 0.55, 0.25, 1), 1.0, 0.30, "contents"),
}
NODES = ("body", "contents", "lid", "latch_L", "latch_R")

# open (0.55 s): the latches snap down and out first, the lid swings up and settles open with a small overshoot
OPEN_LATCH = Curve((0.0, 0.0), (0.04, -12.0), (0.13, cm.LATCH_OPEN_DEG - 6.0, ("hit", 1.2)), (0.20, cm.LATCH_OPEN_DEG),
                   (0.55, cm.LATCH_OPEN_DEG))
OPEN_LID = Curve((0.0, 0.0), (0.10, 0.0), (0.16, 4.0), (0.40, cm.OPEN_DEG + 5.0, ("hit", 1.4)), (0.48, cm.OPEN_DEG - 1.5),
                 (0.55, cm.OPEN_DEG))
# close (0.42 s): the lid falls shut (accelerating), bounces once, the latches are pushed back up over the catches
CLOSE_LID = Curve((0.0, cm.OPEN_DEG), (0.05, cm.OPEN_DEG - 4.0), (0.24, 0.0, ("hit", 2.0)), (0.28, 3.0), (0.32, 0.0),
                  (0.42, 0.0))
CLOSE_LATCH = Curve((0.0, cm.LATCH_OPEN_DEG), (0.30, cm.LATCH_OPEN_DEG), (0.37, 8.0, ("hit", 1.3)), (0.40, -2.0),
                    (0.42, 0.0))


class CratePose:
    def __init__(self, lid=0.0, latch=0.0):
        self.lid, self.latch = lid, latch

    def solve(self):
        out = {"lid": {"rotation": quat_axis_angle((1, 0, 0), self.lid)}}
        for name in ("latch_L", "latch_R"):
            out[name] = {"rotation": quat_axis_angle((1, 0, 0), self.latch)}
        return out, None


CLIPS = [
    Clip("open", 0.55, lambda t: CratePose(float(OPEN_LID(t)), float(OPEN_LATCH(t))),
         events=[(0.0, "latch"), (0.12, "lid_open")], fps=60, extra_times=(0.13, 0.40)),
    Clip("close", 0.42, lambda t: CratePose(float(CLOSE_LID(t)), float(CLOSE_LATCH(t))),
         events=[(0.24, "lid_shut"), (0.37, "latch")], fps=60, extra_times=(0.24, 0.37)),
]


def build(path):
    g = GLB()
    for name, (base, metal, rough, region) in MATERIALS.items():
        g.add_material(name, base, metal, rough, extras={"region": region})
    tris = {}
    meshes = {}
    for name, m in cm.parts().items():
        prims = mk.build_primitives(m.translated(-cm.PIVOTS[name]), split_materials=True)
        tris[name] = mk.triangle_count(prims)
        meshes[name] = g.add_mesh(name, prims)
    g.add_node("crate")
    for name in NODES:
        g.add_node(name, "crate", cm.PIVOTS[name], quat_identity(), mesh=meshes[name])
    rest = {"lid": {"rotation": quat_identity()}, "latch_L": {"rotation": quat_identity()},
            "latch_R": {"rotation": quat_identity()}}
    info = {clip.name: bake_clip(g, clip, rest) for clip in CLIPS}
    g.save(path)
    return tris, info
