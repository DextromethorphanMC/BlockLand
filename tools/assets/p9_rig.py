"""P9 viewmodel rig: node layout in gun space, hip pose, hand anchors and pose -> node TRS (with arm IK)."""
import numpy as np

import p9_model as pm
from ik import UPPER_LEN, solve_arm
from vmath import norm, quat_axis_angle, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = np.array([0.0, -0.0450, 0.0600])          # gun pivot (top of the grip) in the design frame


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.118, -0.100, -0.302])
GUN_EULER = (5.0, 1.0, 5.0)                     # yaw (inward), pitch, roll (top leans in)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.210, -0.335, 0.120])
SHOULDER_L = np.array([-0.210, -0.335, 0.120])
POLE_R = np.array([1.0, -0.60, -0.10])
POLE_L = np.array([-1.0, -0.60, -0.10])

HAMMER_COCKED = 32.0
STOP_UP = 5.0
SLIDE_LOCK = 0.034

NODES = {   # name: (parent, translation in parent space)
    "frame": ("gun", G((0, 0, 0))),
    "slide": ("gun", G((0, 0, 0))),
    "sight_iron": ("slide", np.array([0.0, pm.SY1, 0.0])),
    "sight_reddot": ("slide", np.array([0.0, pm.SY1, 0.0530])),
    "barrel": ("gun", G((0, 0, 0))),
    "hammer": ("gun", G(pm.HAMMER_PIVOT)),
    "trigger": ("gun", G(pm.TRIGGER_PIVOT)),
    "slide_stop": ("gun", G(pm.STOP_PIVOT)),
    "mag": ("gun", G(pm.MAG_ORIGIN)),
    "laser": ("gun", G(pm.LASER_ORIGIN)),
    "muzzle": ("gun", G((0, 0, pm.CROWN_Z))),
    "ejection": ("gun", G((pm.SW, 0.0060, 0.0080))),
}
RETICLE_T = np.array([0.0, pm.REDDOT_Y, pm.REDDOT_GLASS_Z + 0.0003]) - np.array([0.0, pm.SY1, 0.0530])
AIM_IRON_T = np.array([0.0, pm.SIGHT_Y, pm.REAR_SIGHT_Z + 0.110])          # slide space
AIM_REDDOT_T = np.array([0.0, pm.REDDOT_Y, pm.REDDOT_GLASS_Z + 0.0003 + 0.140])
LASER_EMITTER_T = pm.LASER_LENS - pm.LASER_ORIGIN
MAG_Q = quat_axis_angle((1, 0, 0), -18.0)       # mag local +Y runs up the grip axis
EJECT_DIR = norm((0.80, 0.50, 0.33))


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    z = np.cross(x, y)
    from vmath import frame_quat
    return frame_quat(x, y, z)


# ---- hand anchors: (position, back-of-hand direction) in the named space --------------------------
HAND_R_GRIP = (G((0.0040, -0.0640, 0.0520)), norm((1.0, 0.45, 0.10)))         # gun space
HAND_L_SUPPORT = (G((-0.0220, -0.0750, 0.0400)), norm((-1.0, -0.15, -0.30)))   # gun space
HAND_L_MAGREL = (G((-0.0330, -0.0360, 0.0430)), norm((-0.9, 0.35, 0.05)))     # gun space
MAG_HAND_BACK = norm((-1.0, 0.10, 0.25))                                       # gun space
HAND_L_MAG = (np.array([-0.0060, 0.0120, 0.0020]), MAG_HAND_BACK)             # pos: mag space
HAND_L_SLAP = (np.array([0.0000, -0.0330, 0.0000]), MAG_HAND_BACK)            # pos: mag space (heel of hand)
HAND_L_RACK = (np.array([-0.0030, 0.0360, 0.0720]), norm((-0.35, 1.0, 0.0)))   # slide space
HAND_L_STOP = (G((-0.0250, -0.0250, 0.0190)), norm((-1.0, 0.40, -0.20)))     # gun space: thumb on slide stop
HAND_L_PRESS = (np.array([-0.0160, 0.0080, -0.0500]), norm((-1.0, 0.45, 0.0)))  # slide space (front serrations)


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.slide_z = 0.0
        self.trigger = 0.0
        self.hammer = HAMMER_COCKED
        self.stop = 0.0
        self.mag_t = NODES["mag"][1].copy()
        self.mag_q = MAG_Q.copy()
        self.mag_s = 1.0
        self.hand_r = None          # (pos, back) in viewmodel space; default = grip anchor
        self.hand_l = None
        self.cam_q = quat_identity()
        self.pole_r = None          # optional elbow pole overrides (default POLE_R / POLE_L)
        self.pole_l = None

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def slide_point(self, p):
        return self.gun_point(NODES["slide"][1] + np.array([0, 0, self.slide_z]) + p)

    def mag_point(self, p):
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def mag_dir(self, d):
        return self.gun_dir(quat_rotate(self.mag_q, d))

    def anchor(self, which):
        """World (pos, back) of a named hand anchor."""
        if which == "grip":
            p, b = HAND_R_GRIP
            return self.gun_point(p), self.gun_dir(b)
        if which == "support":
            p, b = HAND_L_SUPPORT
            return self.gun_point(p), self.gun_dir(b)
        if which == "magrel":
            p, b = HAND_L_MAGREL
            return self.gun_point(p), self.gun_dir(b)
        if which == "mag":
            p, b = HAND_L_MAG
            return self.mag_point(p), self.gun_dir(b)
        if which == "slap":
            p, b = HAND_L_SLAP
            return self.mag_point(p), self.gun_dir(b)
        if which == "stop":
            p, b = HAND_L_STOP
            return self.gun_point(p), self.gun_dir(b)
        if which == "rack":
            p, b = HAND_L_RACK
            return self.slide_point(p), self.gun_dir(b)
        if which == "press":
            p, b = HAND_L_PRESS
            return self.slide_point(p), self.gun_dir(b)
        raise KeyError(which)

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        """weights: {anchor_name: w}; world: optional (pos, back) used with weight weights['world']."""
        tot, pos, back = 0.0, np.zeros(3), np.zeros(3)
        for name, w in weights.items():
            if w <= 1e-6:
                continue
            p, b = world if name == "world" else self.anchor(name)
            pos += w * p
            back += w * b
            tot += w
        return pos / tot + np.asarray(offset, float), norm(back)

    # node TRS -----------------------------------------------------------------------------------
    def solve(self):
        hr = self.hand_r if self.hand_r is not None else self.anchor("grip")
        hl = self.hand_l if self.hand_l is not None else self.anchor("support")
        ar = solve_arm(SHOULDER_R, hr[0], POLE_R if self.pole_r is None else self.pole_r, hr[1])
        al = solve_arm(SHOULDER_L, hl[0], POLE_L if self.pole_l is None else self.pole_l, hl[1])
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "slide": {"translation": NODES["slide"][1] + np.array([0, 0, self.slide_z])},
            "trigger": {"rotation": rx(self.trigger)},
            "hammer": {"rotation": rx(self.hammer)},
            "slide_stop": {"rotation": rx(self.stop)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "arm_R": {"rotation": ar["q_upper"]},
            "arm_R_fore": {"rotation": ar["q_fore"]},
            "arm_L": {"rotation": al["q_upper"]},
            "arm_L_fore": {"rotation": al["q_fore"]},
            "camera": {"rotation": self.cam_q},
        }
        return trs, {"R": ar, "L": al}


def rest_trs():
    trs, _ = Pose().solve()
    return trs


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
