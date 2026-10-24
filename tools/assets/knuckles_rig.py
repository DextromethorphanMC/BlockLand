"""Knuckles viewmodel rig (VIEWMODEL_SPEC section 42): node layout, the boxing guard and pose -> node TRS.

Two dusters, each rigid to its hand: the clips author each FIST (the middle of the ring row and the hand's palm frame:
-Y along the punch, +Z the palm), the arms solve to the wrists behind them (two-bone IK, the wrist kept inside its
anatomical range), and the dusters follow the hands as solved (hand frame times knuckles_model.CENTRE), so the fingers
sit in the rings in every frame whatever the IK did. `knife` is the right duster, `knife_L` the left (mirrored)."""
import numpy as np

import armrig
import hands
import knuckles_model as km
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_rotate

SHOULDER_R = np.array([0.200, -0.330, 0.060])
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ALIGN = 0.35
OFF = {"R": km.CENTRE.copy(), "L": km.CENTRE * np.array([-1.0, 1.0, 1.0])}     # fist centre in each hand's frame
TIP = {"R": km.TIP - km.CENTRE, "L": (km.TIP - km.CENTRE) * np.array([-1.0, 1.0, 1.0])}
CHARM_MOUNT = km.LANYARD - km.CENTRE              # right duster, knife space (the lanyard hole)
# a fist round the duster: the four first segments curled straight through the rings, the second ones folded back
# over the palm bar (the little finger's a little less, it is short), the thumb over the index and middle fingers
DUSTER_FIST = {"thumb": (-58.0, -40.0), "index": (-90.0, -92.0), "middle": (-90.0, -92.0), "ring": (-90.0, -90.0),
               "pinky": (-90.0, -70.0)}


def fist_q(fwd, palm):
    """Palm frame of a fist punching along `fwd` (hand -Y) with the palm facing `palm` (hand +Z, made square to fwd).
    Either hand: the thumb side follows from the two (right: -X, left: +X)."""
    y = -norm(fwd)
    z = norm(np.asarray(palm, float) - y * np.dot(palm, y))
    return frame_quat(np.cross(y, z), y, z)


def squeeze(k, curls=None):
    """The fist clenched (k < 0) or eased (k > 0) by k degrees; the rings keep the first segments, so mostly the
    second segments and the thumb move."""
    c = DUSTER_FIST if curls is None else curls
    return hands.adjust(c, index=(0.15 * k, k), middle=(0.15 * k, k), ring=(0.15 * k, k), pinky=(0.15 * k, k),
                        thumb=(0.8 * k, 0.6 * k))


# the boxing guard (orthodox: the left fist leads a little): fists at the cheeks' height of the frame, knuckles forward
# and up, palms turned in, thumbs up and in; both clear of the crosshair square
GUARD_R = (np.array([0.112, -0.090, -0.300]), fist_q((-0.18, 0.42, -0.89), (-0.95, -0.10, -0.25)))
GUARD_L = (np.array([-0.118, -0.080, -0.335]), fist_q((0.16, 0.44, -0.88), (0.95, -0.10, -0.25)))


class KnucklesPose:
    def __init__(self):
        self.fist = {"R": GUARD_R, "L": GUARD_L}       # side -> (centre, palm quat) in viewmodel space
        self.curls = {"R": DUSTER_FIST, "L": DUSTER_FIST}
        self.shoulder = {"R": np.zeros(3), "L": np.zeros(3)}    # body turn / lunge offsets of the shoulders
        self.pole = {"R": None, "L": None}
        self.cam_q = np.array([0.0, 0.0, 0.0, 1.0])

    def target(self, side):
        c, q = self.fist[side]
        return np.asarray(c, float) - quat_rotate(q, OFF[side]), q, self.curls[side]

    def solve(self):
        trs, info = {"camera": {"rotation": self.cam_q}}, {}
        for side, shoulder0, pole0, root in (("R", SHOULDER_R, POLE_R, "knife"), ("L", SHOULDER_L, POLE_L, "knife_L")):
            shoulder = shoulder0 + self.shoulder[side]
            pole = pole0 if self.pole[side] is None else self.pole[side]
            target = self.target(side)
            t, ik = armrig.solve_side(side, shoulder, pole, target, align=ALIGN, limit=True)
            t[f"arm_{side}"]["translation"] = shoulder
            trs.update(t)
            # the duster rides on the hand as solved
            trs[root] = {"translation": ik["hand"] + quat_rotate(ik["q_palm"], OFF[side]), "rotation": ik["q_palm"]}
            info[side] = ik
        return trs, info

    def fist_world(self, side):
        """(centre, palm quat) of the duster after the solve."""
        trs, _ = self.solve()
        r = trs["knife" if side == "R" else "knife_L"]
        return r["translation"], r["rotation"]


def rest_values():
    trs, _ = KnucklesPose().solve()
    return trs
