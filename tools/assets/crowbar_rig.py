"""Crowbar viewmodel rig (VIEWMODEL_SPEC section 41): node layout, ready stance and pose -> node TRS.

One rigid `knife` node at the right-hand grip (the middle of the tape wrap) carries the bar and the tape. The right
hand holds it in a hammer grip, the thumb wrapped towards the head; the knife follows the IK hand and the wrist
limits turn the bar with the hand (as the dagger, dagger_rig.py); the bar can `roll` in the fingers about its own
axis. The left hand can take the shaft above the right (`support`: 0 = free, 1 = gripping its place on the bar) for
the two-handed overhead smash."""
import numpy as np

import armrig
import crowbar_model as cm
import hands
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate, quat_slerp, quat_to_mat

SHOULDER_R = np.array([0.200, -0.330, 0.060])      # knife stance
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ORIGINS = {"bar": cm.GRIP.copy(), "grip": cm.GRIP.copy()}
NODES = {   # name: (parent, translation)
    "bar": ("knife", ORIGINS["bar"] - cm.GRIP),
    "tip": ("bar", cm.TIP - ORIGINS["bar"]),
    "grip": ("knife", ORIGINS["grip"] - cm.GRIP),
}
MESH_NODES = ("bar", "grip")
TIP = cm.TIP - cm.GRIP                                   # knife space: the striking point (outside of the neck)
HIT = 0.88 * TIP                                         # knife space: the point centred on the screen at contact
CHARM_MOUNT = cm.CHARM - cm.GRIP
ALIGN_R = 0.30
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))   # off screen, hidden


def knife_quat(bar_dir, claw_hint):
    """Bar orientation from the direction towards the head (knife -Z) and the claw side (knife +Y, where the neck
    curls to)."""
    z = -norm(bar_dir)
    y = norm(np.asarray(claw_hint, float) - z * np.dot(claw_hint, z))
    return frame_quat(np.cross(y, z), y, z)


# authored ready stance (made reachable below): held like a bat low on the right, the bar leaning up, forward and
# a little left, the neck curling right (its outer curve, the striking face, towards the left: ready to chop)
REST_T = np.array([0.158, -0.140, -0.300])
REST_BAR = norm((-0.11, 0.62, -0.78))
REST_CLAW = norm((0.85, 0.25, 0.45))
REST_Q = knife_quat(REST_BAR, REST_CLAW)


# ---- hands (section 16): a hammer grip round the tape, the thumb wrapped towards the head --------------------
def _palm(centre, fwd, thumb, side):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q


def _diagonal(fwd, thumb, angle):
    q = quat_axis_angle((1, 0, 0), angle)
    return quat_rotate(q, fwd), quat_rotate(q, thumb)


R_TAPE = cm.HEX_APOTHEM * 1.06 + cm.TAPE_T             # the wrapped shaft's radius
GRIP_ANGLE = 30.0                                       # the shaft crosses the palm a little diagonally
_GRIP_CENTRE = np.array([R_TAPE + hands.PALM_T / 2, -0.0100 + hands.PALM_LEN / 2, 0.012])
GRIP_PALM = _palm(_GRIP_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, -1.0), GRIP_ANGLE), "R")   # knife space
# the left hand 0.10 m further up the shaft, mirrored: palm on the -X side, the fingers wrapping round towards +X
_GRIP_L_CENTRE = np.array([-(cm.HEX_APOTHEM + hands.PALM_T / 2), -0.0100 + hands.PALM_LEN / 2, 0.012 - 0.100])
GRIP_L_PALM = _palm(_GRIP_L_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, -1.0), GRIP_ANGLE), "L")
FIST = hands.adjust(hands.POSES["fist"], index=(9.0, 4.0), middle=(9.0, 4.0), ring=(9.0, 4.0), pinky=(8.0, 3.0))
FIST_L = hands.adjust(hands.POSES["fist"], index=(14.0, 6.0), middle=(14.0, 6.0), ring=(14.0, 6.0), pinky=(12.0, 5.0))


class CrowbarPose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.roll = 0.0              # about the bar's axis (knife Z) through the grip, relative to the hand
        self.grip = GRIP_PALM        # right palm frame in knife space
        self.fingers_r = None        # finger curls of the right hand (default: FIST)
        self.hand_l = None           # (wrist, palm quat, curls) world target of the free left hand
        self.support = 0.0           # 0 = the left hand free (hand_l / off screen), 1 = on the shaft
        self.fingers_l = None        # curls of the left hand on the shaft (default FIST_L)
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)  # body offsets of the shoulders (lunges, the overhead raise)
        self.shoulder_l = np.zeros(3)
        self.pole_r = None
        self.pole_l = None
        self.cam_q = quat_identity()

    # ---------------------------------------------------------------------------------- hierarchy
    def matrices(self):
        def T(t, q=None):
            m = np.eye(4)
            if q is not None:
                m[:3, :3] = quat_to_mat(q)
            m[:3, 3] = t
            return m
        out = {"knife": T(self.knife_t, self.knife_world_q())}
        for name, (parent, t) in NODES.items():
            out[name] = out[parent] @ T(t)
        return out

    def knife_world_q(self):
        return quat_mul(self.knife_q, quat_axis_angle((0, 0, 1), self.roll)) if self.roll else self.knife_q

    def knife_point(self, local):
        return self.knife_t + quat_rotate(self.knife_world_q(), local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else FIST
        return self.knife_t + quat_rotate(self.knife_q, w), quat_mul(self.knife_q, q), curls

    def grip_l_target(self):
        w, q = GRIP_L_PALM
        curls = self.fingers_l if self.fingers_l is not None else FIST_L
        return self.knife_t + quat_rotate(self.knife_q, w), quat_mul(self.knife_q, q), curls

    # -------------------------------------------------------------------------------------- solve
    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        shoulder_l = SHOULDER_L + self.shoulder_l
        pole_r = POLE_R if self.pole_r is None else self.pole_r
        wrist0, q_palm0, _ = self.grip_target()
        target = self.grip_target()
        tr, ar = armrig.solve_side("R", shoulder_r, pole_r, target, align=ALIGN_R, limit=True)
        q_lim = armrig.limit_palm(q_palm0, norm(ar["hand"] - ar["elbow"]), "R")
        corr = quat_mul(q_lim, quat_conj(q_palm0))
        self.knife_t = wrist0 + quat_rotate(corr, self.knife_t - wrist0)
        self.knife_q = quat_mul(corr, self.knife_q)
        self.knife_t = self.knife_t + (ar["hand"] - target[0])      # keep the bar in the hand even at full reach
        free = self.hand_l if self.hand_l is not None else LEFT_REST
        if self.support > 1e-4:
            on_bar = self.grip_l_target()
            if free[1] is None:                           # a free hand reaching for the bar turns towards its grip
                free = (free[0], on_bar[1], free[2])
            hl = armrig.blend_targets([(1 - self.support + 1e-6, free), (self.support, on_bar)])
        else:
            hl = free
        tl, al = armrig.solve_side("L", shoulder_l, POLE_L if self.pole_l is None else self.pole_l, hl,
                                   limit=self.support > 0.5)
        trs = {
            "knife": {"translation": self.knife_t, "rotation": self.knife_world_q()},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["translation"] = shoulder_l
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(t, q):
    """Knife root / orientation after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = CrowbarPose()
    p.knife_t, p.knife_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


REST_T, REST_Q = reachable(REST_T, REST_Q)


def rest_values():
    trs, _ = CrowbarPose().solve()
    return trs
