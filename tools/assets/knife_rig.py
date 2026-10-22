"""Butterfly knife viewmodel rig (VIEWMODEL_SPEC section 12): node layout, ready stance and pose -> node TRS.

Mechanism chain (real balisong kinematics, every flip is a rotation about a pivot pin, axis = local X):
  knife (root at the right-hand grip on the safe handle)
  └─ handle_safe            origin on the safe pin
     └─ blade               origin on the safe pin, angle `a` (0 open, 180 folded into the handles)
        ├─ tip              empty at the point
        └─ handle_bite      origin on the bite pin, angle `b` (0 open, 180 closed onto the edge side)
           └─ latch         origin on its pin at the butt, angle `c` (0 latched, ~150 swung open)
The knife follows the IK hand: if a target is out of reach the knife moves with the (soft-IK) fist, so it can
never float away from the hand."""
import numpy as np

import knife_model as km
from ik import solve_arm
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_axis_angle, quat_identity, quat_mul, quat_rotate, quat_to_mat

SHOULDER_R = np.array([0.200, -0.330, 0.060])      # knife stance: shoulders a little more forward than the P9
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
SAFE_PIN = np.array([0.0, km.PIN_Y, 0.0])
BITE_PIN = np.array([0.0, -km.PIN_Y, 0.0])
NODES = {   # name: (parent, translation)
    "handle_safe": ("knife", SAFE_PIN - km.GRIP),
    "blade": ("handle_safe", np.zeros(3)),
    "tip": ("blade", km.TIP - SAFE_PIN),
    "handle_bite": ("blade", BITE_PIN - SAFE_PIN),
    "latch": ("handle_bite", km.LATCH_PIVOT - BITE_PIN),
}
BLADE_MID = 0.5 * (SAFE_PIN + km.TIP) - km.GRIP       # knife space: middle of the pin->tip segment
GRIP_DEPTH = 0.030                                    # grip sits this far inside the fist box
LATCH_OPEN = 150.0
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), norm((-1.0, 0.1, -0.2)))   # off screen while hidden


def knife_quat(blade_dir, spine_hint):
    """Knife orientation from the blade direction (knife -Z) and a spine direction hint (knife +Y)."""
    z = -norm(blade_dir)
    y = norm(np.asarray(spine_hint, float) - z * np.dot(spine_hint, z))
    return frame_quat(np.cross(y, z), y, z)


REST_T = np.array([0.120, -0.088, -0.340])
REST_BLADE = norm((-0.30, 0.60, -0.74))
REST_SPINE = norm((0.10, 0.70, 0.70))
REST_Q = knife_quat(REST_BLADE, REST_SPINE)


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


class KnifePose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.a = 0.0                 # blade about the safe pin
        self.b = 0.0                 # bite handle about the bite pin
        self.c = 0.0                 # latch
        self.free_r = None           # (pos, back) world target for the right hand while the knife flies
        self.free_w = 0.0
        self.hand_l = None           # (pos, back) world target for the left hand
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)  # lunge offset of the right shoulder
        self.pole_r = None
        self.pole_l = None
        self.cam_q = quat_identity()

    # ---------------------------------------------------------------------------------- hierarchy
    def matrices(self, knife_t=None):
        def T(t, q=None):
            m = np.eye(4)
            if q is not None:
                m[:3, :3] = quat_to_mat(q)
            m[:3, 3] = t
            return m
        mk = T(self.knife_t if knife_t is None else knife_t, self.knife_q)
        ms = mk @ T(NODES["handle_safe"][1])
        mb = ms @ T(np.zeros(3), rx(self.a))
        mt = mb @ T(NODES["tip"][1])
        mh = mb @ T(NODES["handle_bite"][1], rx(self.b))
        ml = mh @ T(NODES["latch"][1], rx(self.c))
        return {"knife": mk, "handle_safe": ms, "blade": mb, "tip": mt, "handle_bite": mh, "latch": ml}

    def point(self, node, local):
        m = self.matrices()[node]
        return m[:3, :3] @ np.asarray(local, float) + m[:3, 3]

    def knife_point(self, local):
        return self.knife_t + quat_rotate(self.knife_q, local)

    # -------------------------------------------------------------------------------------- solve
    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        pole_r = POLE_R if self.pole_r is None else self.pole_r
        grip = self.knife_t.copy()
        back = quat_rotate(self.knife_q, (1.0, 0.0, 0.0))
        f = norm(shoulder_r - grip)                    # first guess of the forearm direction
        for _ in range(3):
            target = grip - GRIP_DEPTH * f
            if self.free_r is not None and self.free_w > 0:
                target = (1 - self.free_w) * target + self.free_w * self.free_r[0]
                bk = norm((1 - self.free_w) * back + self.free_w * self.free_r[1])
            else:
                bk = back
            ar = solve_arm(shoulder_r, target, pole_r, bk)
            f = norm(ar["elbow"] - ar["hand"])
        if self.free_w <= 0:                           # keep the knife in the fist even at full reach
            self.knife_t = self.knife_t + (ar["hand"] - target)
        hl = self.hand_l if self.hand_l is not None else LEFT_REST
        al = solve_arm(SHOULDER_L, hl[0], POLE_L if self.pole_l is None else self.pole_l, hl[1])
        trs = {
            "knife": {"translation": self.knife_t, "rotation": self.knife_q},
            "blade": {"rotation": rx(self.a)},
            "handle_bite": {"rotation": rx(self.b)},
            "latch": {"rotation": rx(self.c)},
            "arm_R": {"translation": shoulder_r, "rotation": ar["q_upper"]},
            "arm_R_fore": {"rotation": ar["q_fore"]},
            "arm_L": {"rotation": al["q_upper"], "scale": np.full(3, self.left_scale)},
            "arm_L_fore": {"rotation": al["q_fore"]},
            "camera": {"rotation": self.cam_q},
        }
        return trs, {"R": ar, "L": al}


def rest_values():
    trs, _ = KnifePose().solve()
    return trs
