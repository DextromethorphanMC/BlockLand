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

import armrig
import hands
import knife_model as km
from p9_rig import POLE_L, POLE_R
from vmath import (frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate,
                   quat_slerp, quat_to_mat)

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
# section 19: charm mount (lanyard hole) just past the butt of the safe handle, in handle_safe space
CHARM_MOUNT = np.array([0.0, km.HANDLE_GAP + km.HANDLE_H / 2 - km.PIN_Y, km.HANDLE_LEN + 0.0015])
GRIP_DEPTH = 0.030                                    # grip sits this far inside the fist box
LATCH_OPEN = 150.0
ALIGN_R = 0.30                                        # how much the right elbow lines the forearm up with the palm
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))   # off screen, hidden


def knife_quat(blade_dir, spine_hint):
    """Knife orientation from the blade direction (knife -Z) and a spine direction hint (knife +Y)."""
    z = -norm(blade_dir)
    y = norm(np.asarray(spine_hint, float) - z * np.dot(spine_hint, z))
    return frame_quat(np.cross(y, z), y, z)


REST_T = np.array([0.120, -0.088, -0.340])        # authored ready stance (made reachable below)
REST_BLADE = norm((-0.30, 0.60, -0.74))
REST_SPINE = norm((0.10, 0.70, 0.70))
REST_Q = knife_quat(REST_BLADE, REST_SPINE)


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


# ---- hands (section 16): palm frames in knife space -----------------------------------------------
# the handle is held like a pistol grip: spine side = backstrap (wrist), edge side = front strap (fingers),
# thumb side towards the blade, palm on the right flat
def _palm(centre, fwd, thumb, side):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q


GRIP_ANGLE = 55.0   # handle diagonal across the palm (saber grip): index wraps at the blade end, heel at the butt
_GRIP_CENTRE = np.array([km.LINER_X[1] + hands.PALM_T / 2, -0.0130 + hands.PALM_LEN / 2, 0.0])


def _diagonal(fwd, thumb, angle):
    q = quat_axis_angle((1, 0, 0), angle)
    return quat_rotate(q, fwd), quat_rotate(q, thumb)


GRIP_PALM = _palm(_GRIP_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, -1.0), GRIP_ANGLE), "R")  # knife space
REVERSE_PALM = _palm(_GRIP_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, 1.0), -GRIP_ANGLE * 0.5), "R")  # ice-pick


class KnifePose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.a = 0.0                 # blade about the safe pin
        self.b = 0.0                 # bite handle about the bite pin
        self.c = 0.0                 # latch
        self.roll = 0.0              # knife rolled about its handle axis relative to the hand (rollover tricks)
        self.grip = GRIP_PALM        # right palm frame in knife space
        self.fingers_r = None        # finger curls of the right hand (default: fist)
        self.free_r = None           # (wrist, palm quat, curls) world target for the right hand while the knife flies
        self.free_w = 0.0
        self.hand_l = None           # (wrist, palm quat, curls) world target for the left hand
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
        mk = T(self.knife_t if knife_t is None else knife_t, self.knife_world_q())
        ms = mk @ T(NODES["handle_safe"][1])
        mb = ms @ T(np.zeros(3), rx(self.a))
        mt = mb @ T(NODES["tip"][1])
        mh = mb @ T(NODES["handle_bite"][1], rx(self.b))
        ml = mh @ T(NODES["latch"][1], rx(self.c))
        return {"knife": mk, "handle_safe": ms, "blade": mb, "tip": mt, "handle_bite": mh, "latch": ml}

    def point(self, node, local):
        m = self.matrices()[node]
        return m[:3, :3] @ np.asarray(local, float) + m[:3, 3]

    def knife_world_q(self):
        return quat_mul(self.knife_q, quat_axis_angle((0, 0, 1), self.roll)) if self.roll else self.knife_q

    def knife_point(self, local):
        return self.knife_t + quat_rotate(self.knife_world_q(), local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else hands.blend({"fist": 1.0})
        return self.knife_t + quat_rotate(self.knife_q, w), quat_mul(self.knife_q, q), curls   # un-rolled frame

    # -------------------------------------------------------------------------------------- solve
    def _hand_target(self):
        target = self.grip_target()
        if self.free_r is not None and self.free_w > 0:
            target = armrig.blend_targets([(1 - self.free_w, target), (self.free_w, self.free_r)])
        return target

    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        pole_r = POLE_R if self.pole_r is None else self.pole_r
        # the wrist cannot fold past its range: where the authored knife angle asks for more, the knife turns
        # with the hand about the wrist (fading out while the hand lets go of the knife)
        wrist0, q_palm0, _ = self.grip_target()
        target = self._hand_target()
        tr, ar = armrig.solve_side("R", shoulder_r, pole_r, target, align=ALIGN_R, limit=True)
        hold = 1.0 - float(np.clip(self.free_w, 0.0, 1.0))
        if hold > 0:
            q_lim = armrig.limit_palm(q_palm0, norm(ar["hand"] - ar["elbow"]), "R")
            corr = quat_slerp(quat_identity(), quat_mul(q_lim, quat_conj(q_palm0)), hold)
            self.knife_t = wrist0 + quat_rotate(corr, self.knife_t - wrist0)
            self.knife_q = quat_mul(corr, self.knife_q)
        if self.free_w <= 0:                           # keep the knife in the hand even at full reach
            self.knife_t = self.knife_t + (ar["hand"] - target[0])
        hl = self.hand_l if self.hand_l is not None else LEFT_REST
        tl, al = armrig.solve_side("L", SHOULDER_L, POLE_L if self.pole_l is None else self.pole_l, hl)
        trs = {
            "knife": {"translation": self.knife_t, "rotation": self.knife_world_q()},
            "blade": {"rotation": rx(self.a)},
            "handle_bite": {"rotation": rx(self.b)},
            "latch": {"rotation": rx(self.c)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(t, q):
    """Knife root / orientation after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = KnifePose()
    p.knife_t, p.knife_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


REST_T, REST_Q = reachable(REST_T, REST_Q)


def rest_values():
    trs, _ = KnifePose().solve()
    return trs
