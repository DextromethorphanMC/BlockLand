"""The bomb's viewmodel rig (VIEWMODEL_SPEC section 39): the equipment rig (equip_rig: grip fit, held frame, `fly`,
free right hand, left hand target) driving a `bomb` node instead of `equipment`.

The right hand carries the bundle on its palm (the palm under the sticks, the fingers along them, the thumb up its
right side); at rest it is held low on the right with the keypad tilted towards the eye. The bomb has no moving parts:
its meshes (`body`, `keypad`, `wires`, `led`) are rigid children of `bomb`."""
import numpy as np

import bomb_model as bm
import equip_rig as er
from equip_rig import V, item_quat

ROOT = "bomb"
REST_T = V(0.124, -0.136, -0.375)
REST_UP, REST_FWD = (-0.10, 0.80, 0.59), (-0.30, 0.0, -0.95)


class BombDef(er.ItemDef):
    def parent(self, node):
        return ROOT


class BombPose(er.EquipPose):
    """EquipPose whose item node is `bomb`."""

    def solve(self):
        trs, info = super().solve()
        trs[ROOT] = trs.pop("equipment")
        return trs, info


_ITEM = None


def item():
    global _ITEM
    if _ITEM is None:
        parts, points = bm.c4()
        contact = V(0.012, bm.BOX_LO[1], 0.030)
        _ITEM = BombDef("c4", parts, points, contact, (0, 1, 0), (-0.30, 0.0, -1.0), bm.sdf, REST_T,
                        item_quat(REST_UP, REST_FWD))
    return _ITEM


def rest_values(it):
    trs, _ = BombPose(it).solve()
    return trs


def renamed(pose):
    """An EquipPose made by the shared clip helpers, solved as a BombPose."""
    pose.__class__ = BombPose
    return pose


def point(it, name):
    return np.asarray(it.points[name], float)
