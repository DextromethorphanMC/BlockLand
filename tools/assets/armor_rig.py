"""Armour plate viewmodel rig (VIEWMODEL_SPEC section 50): node layout, rest pose and pose -> node TRS.

One rigid `plate` node carries the plate; both hands hold it by its side edges (palms on the edges, the fingers round
onto the strike face, thumbs on the body side), so the arms follow the plate through two-bone IK. Plate frame: the
strike face towards -Z (away from the player), the body side +Z, x across, y up (armor_model.plate_mesh)."""
import numpy as np

import armrig
import armor_model as am
import hands
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_identity, quat_mul, quat_rotate

SHOULDER_R = np.array([0.200, -0.330, 0.060])
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
NODES = {"plate_body": ("plate", np.zeros(3))}


def plate_quat(face_dir, up_hint):
    """Plate orientation from where its strike face looks (plate -Z) and an up hint (plate +Y)."""
    z = -norm(face_dir)
    y = norm(np.asarray(up_hint, float) - z * np.dot(up_hint, z))
    return frame_quat(np.cross(y, z), y, z)


def _palm(centre, fwd, thumb, side):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q


# the hands at the side edges, a little below the middle: the palm against the edge, the fingers pointing past the
# strike face and curled round onto it, the thumb up along the body side
_EDGE_X = am.PLATE_W / 2 + hands.PALM_T / 2 - 0.004
_GRIP_Y = 0.03
GRIP_R = _palm((_EDGE_X, _GRIP_Y, -0.002 + hands.PALM_LEN / 2 - 0.012), (0.0, 0.0, -1.0), (0.0, 1.0, 0.25), "R")
GRIP_L = _palm((-_EDGE_X, _GRIP_Y, -0.002 + hands.PALM_LEN / 2 - 0.012), (0.0, 0.0, -1.0), (0.0, 1.0, 0.25), "L")
HOLD = hands.adjust(hands.POSES["grip"], index=(26.0, 30.0), middle=(26.0, 30.0), ring=(26.0, 30.0), pinky=(24.0, 28.0),
                    thumb=(30.0, 16.0))

# the plate held up before the chest, leaning back a little, turned a touch to the left
REST_T = np.array([0.050, -0.175, -0.400])
REST_Q = plate_quat((0.10, -0.28, -0.95), (0.0, 1.0, -0.2))


class PlatePose:
    def __init__(self):
        self.plate_t = REST_T.copy()
        self.plate_q = REST_Q.copy()
        self.fingers = None
        self.free_l = None            # (wrist, palm, curls) target the left hand leaves the plate for, and its weight
        self.free_w = 0.0
        self.shoulder_r = np.zeros(3)
        self.shoulder_l = np.zeros(3)
        self.cam_q = quat_identity()

    def grip(self, side):
        w, q = GRIP_R if side == "R" else GRIP_L
        curls = self.fingers if self.fingers is not None else HOLD
        return self.plate_t + quat_rotate(self.plate_q, w), quat_mul(self.plate_q, q), curls

    def solve(self):
        tr, ar = armrig.solve_side("R", SHOULDER_R + self.shoulder_r, POLE_R, self.grip("R"), align=0.3)
        target_l = self.grip("L")
        if self.free_l is not None and self.free_w > 0:
            target_l = armrig.blend_targets([(1 - self.free_w + 1e-6, target_l), (self.free_w, self.free_l)])
        tl, al = armrig.solve_side("L", SHOULDER_L + self.shoulder_l, POLE_L, target_l, align=0.3)
        trs = {"plate": {"translation": self.plate_t, "rotation": self.plate_q}, "camera": {"rotation": self.cam_q}}
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = SHOULDER_R + self.shoulder_r
        trs["arm_L"]["translation"] = SHOULDER_L + self.shoulder_l
        return trs, {"R": ar, "L": al}
