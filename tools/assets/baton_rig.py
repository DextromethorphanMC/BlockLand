"""Expandable baton viewmodel rig (VIEWMODEL_SPEC section 45): node layout, ready stance and pose -> node TRS.

One rigid `knife` node at the right-hand grip carries the handle; `shaft_1` slides in the handle and `shaft_2` in
shaft_1 along the baton's axis (`ext1`, `ext2`: 1 = locked open, 0 = collapsed). The right hand holds the grip in a
hammer grip, the thumb wrapped towards the tip; the knife follows the IK hand and the wrist limits turn the baton with
the hand (as the crowbar, crowbar_rig.py); it can `roll` in the fingers about its own axis."""
import numpy as np

import armrig
import baton_model as bm
import hands
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate, quat_to_mat

SHOULDER_R = np.array([0.200, -0.330, 0.060])      # knife stance
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ORIGINS = {"handle": bm.GRIP.copy(), "shaft_1": np.array([0.0, 0.0, bm.SHAFT1_Z[0]]),
           "shaft_2": np.array([0.0, 0.0, bm.SHAFT2_Z[0]])}
NODES = {   # name: (parent, translation) in the open baton
    "handle": ("knife", ORIGINS["handle"] - bm.GRIP),
    "shaft_1": ("knife", ORIGINS["shaft_1"] - bm.GRIP),
    "shaft_2": ("shaft_1", ORIGINS["shaft_2"] - ORIGINS["shaft_1"]),
    "tip": ("shaft_2", bm.TIP - ORIGINS["shaft_2"]),
}
MESH_NODES = ("handle", "shaft_1", "shaft_2")
TIP = bm.TIP - bm.GRIP                                   # knife space: the tip knob's point (open)
HIT = 0.85 * TIP                                         # knife space: the point centred on the screen at contact
CHARM_MOUNT = bm.LOOP_C + np.array([0.0, -0.0040, 0.0]) - bm.GRIP
ALIGN_R = 0.30
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))   # off screen, hidden


def knife_quat(tip_dir, top_hint):
    """Baton orientation from the direction towards the tip (knife -Z) and an up hint (knife +Y)."""
    z = -norm(tip_dir)
    y = norm(np.asarray(top_hint, float) - z * np.dot(top_hint, z))
    return frame_quat(np.cross(y, z), y, z)


# authored ready stance (made reachable below): held low on the right like the crowbar, the baton leaning up, forward
# and a little left
REST_T = np.array([0.158, -0.140, -0.300])
REST_DIR = norm((-0.11, 0.62, -0.78))
REST_UP = norm((0.85, 0.25, 0.45))
REST_Q = knife_quat(REST_DIR, REST_UP)


# ---- hands (section 16): a hammer grip round the rubber, the thumb wrapped towards the tip ---------------------
def _palm(centre, fwd, thumb, side):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q


def _diagonal(fwd, thumb, angle):
    q = quat_axis_angle((1, 0, 0), angle)
    return quat_rotate(q, fwd), quat_rotate(q, thumb)


R_GRIP = bm.R_GRIP * 1.06                               # the grip's swell under the palm
GRIP_ANGLE = 30.0
_GRIP_CENTRE = np.array([R_GRIP + hands.PALM_T / 2, -0.0100 + hands.PALM_LEN / 2, 0.010])
GRIP_PALM = _palm(_GRIP_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, -1.0), GRIP_ANGLE), "R")   # knife space
FIST = hands.adjust(hands.POSES["fist"], index=(8.0, 4.0), middle=(8.0, 4.0), ring=(8.0, 4.0), pinky=(7.0, 3.0))


class BatonPose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.roll = 0.0              # about the baton's axis (knife Z) through the grip, relative to the hand
        self.ext1 = 1.0              # shaft_1 out of the handle (1 = locked open, 0 = collapsed)
        self.ext2 = 1.0              # shaft_2 out of shaft_1
        self.grip = GRIP_PALM
        self.fingers_r = None
        self.hand_l = None           # (wrist, palm quat, curls) world target of the left hand
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)
        self.pole_r = None
        self.pole_l = None
        self.cam_q = quat_identity()

    def shaft_offsets(self):
        """Local translations of shaft_1 (in the knife) and shaft_2 (in shaft_1) for the current extension."""
        t1 = NODES["shaft_1"][1] + np.array([0.0, 0.0, bm.COLLAPSE[0] * (1.0 - self.ext1)])
        t2 = NODES["shaft_2"][1] + np.array([0.0, 0.0, bm.COLLAPSE[1] * (1.0 - self.ext2)])
        return t1, t2

    # ---------------------------------------------------------------------------------- hierarchy
    def knife_world_q(self):
        return quat_mul(self.knife_q, quat_axis_angle((0, 0, 1), self.roll)) if self.roll else self.knife_q

    def matrices(self):
        def T(t, q=None):
            m = np.eye(4)
            if q is not None:
                m[:3, :3] = quat_to_mat(q)
            m[:3, 3] = t
            return m
        t1, t2 = self.shaft_offsets()
        local = dict(NODES)
        local["shaft_1"] = ("knife", t1)
        local["shaft_2"] = ("shaft_1", t2)
        out = {"knife": T(self.knife_t, self.knife_world_q())}
        for name in ("handle", "shaft_1", "shaft_2", "tip"):
            parent, t = local[name]
            out[name] = out[parent] @ T(t)
        return out

    def knife_point(self, local):
        return self.knife_t + quat_rotate(self.knife_world_q(), local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else FIST
        return self.knife_t + quat_rotate(self.knife_q, w), quat_mul(self.knife_q, q), curls

    # -------------------------------------------------------------------------------------- solve
    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        pole_r = POLE_R if self.pole_r is None else self.pole_r
        wrist0, q_palm0, _ = self.grip_target()
        target = self.grip_target()
        tr, ar = armrig.solve_side("R", shoulder_r, pole_r, target, align=ALIGN_R, limit=True)
        q_lim = armrig.limit_palm(q_palm0, norm(ar["hand"] - ar["elbow"]), "R")
        corr = quat_mul(q_lim, quat_conj(q_palm0))
        self.knife_t = wrist0 + quat_rotate(corr, self.knife_t - wrist0)
        self.knife_q = quat_mul(corr, self.knife_q)
        self.knife_t = self.knife_t + (ar["hand"] - target[0])      # keep the baton in the hand even at full reach
        hl = self.hand_l if self.hand_l is not None else LEFT_REST
        tl, al = armrig.solve_side("L", SHOULDER_L, POLE_L if self.pole_l is None else self.pole_l, hl)
        t1, t2 = self.shaft_offsets()
        trs = {
            "knife": {"translation": self.knife_t, "rotation": self.knife_world_q()},
            "shaft_1": {"translation": t1},
            "shaft_2": {"translation": t2},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(t, q):
    """Knife root / orientation after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = BatonPose()
    p.knife_t, p.knife_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


REST_T, REST_Q = reachable(REST_T, REST_Q)


def rest_values():
    trs, _ = BatonPose().solve()
    return trs
