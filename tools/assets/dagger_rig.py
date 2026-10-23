"""Combat dagger viewmodel rig (VIEWMODEL_SPEC section 27): node layout, ready stance and pose -> node TRS.

A fixed blade: one rigid `knife` node at the right-hand grip carries the blade, guard, grip and pommel. Besides its
position and orientation the knife can `spin` about its flat normal (local X) and `roll` about its long axis
(local Z) through the grip point while the hand keeps its grip frame: flips in the hand, spins on the finger and
the change to a reverse grip. Like the balisong (knife_rig.py) the knife follows the IK hand, the wrist limits turn
the knife with the hand, and a free hand target (`free_r`) lets the hand leave the handle while the knife flies."""
import numpy as np

import armrig
import dagger_model as dm
import hands
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate, quat_slerp, quat_to_mat

SHOULDER_R = np.array([0.200, -0.330, 0.060])      # knife stance, as the balisong
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ORIGINS = {   # node -> its origin in the design frame (meshes are built in design space and moved to their node)
    "blade": np.zeros(3), "guard": np.zeros(3), "handle": dm.GRIP.copy(), "pommel": np.array([0.0, 0.0, dm.POMMEL_Z[0]]),
}
NODES = {   # name: (parent, translation)
    "blade": ("knife", ORIGINS["blade"] - dm.GRIP),
    "tip": ("blade", dm.TIP - ORIGINS["blade"]),
    "guard": ("knife", ORIGINS["guard"] - dm.GRIP),
    "handle": ("knife", ORIGINS["handle"] - dm.GRIP),
    "pommel": ("knife", ORIGINS["pommel"] - dm.GRIP),
}
MESH_NODES = ("blade", "guard", "handle", "pommel")
BLADE_MID = 0.5 * (ORIGINS["blade"] + dm.TIP) - dm.GRIP        # knife space: middle of the guard -> point segment
# section 19 / 27: the charm hangs through the lanyard loop (its ring passes the loop's lower inner edge)
CHARM_MOUNT = dm.LOOP_C + np.array([0.0, -0.0040, 0.0]) - dm.GRIP
ALIGN_R = 0.30
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))   # off screen, hidden


def knife_quat(blade_dir, spine_hint):
    """Knife orientation from the blade direction (knife -Z) and a top-edge direction hint (knife +Y)."""
    z = -norm(blade_dir)
    y = norm(np.asarray(spine_hint, float) - z * np.dot(spine_hint, z))
    return frame_quat(np.cross(y, z), y, z)


# authored ready stance (made reachable below): more upright than the balisong's, so the longer blade ends just
# right of and above the crosshair area
REST_T = np.array([0.138, -0.104, -0.325])
REST_BLADE = norm((-0.12, 0.46, -0.88))
REST_SPINE = norm((0.12, 0.72, 0.68))
REST_Q = knife_quat(REST_BLADE, REST_SPINE)


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


# ---- hands (section 16): the grip held like a pistol grip, diagonal across the palm (saber grip) ----------------
def _palm(centre, fwd, thumb, side):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q


GRIP_ANGLE = 55.0
# the grip's right side is GRIP_A x 1.07 (its swell) from the centre line; the palm lies on it, set back so the
# fingers (tilted towards the blade by the diagonal) wrap the grip from the guard back, the index against the guard
_GRIP_CENTRE = np.array([dm.GRIP_A * 1.07 + hands.PALM_T / 2, -0.0100 + hands.PALM_LEN / 2, 0.016])


def _diagonal(fwd, thumb, angle):
    q = quat_axis_angle((1, 0, 0), angle)
    return quat_rotate(q, fwd), quat_rotate(q, thumb)


GRIP_PALM = _palm(_GRIP_CENTRE, *_diagonal((0.0, -1.0, 0.0), (0.0, 0.0, -1.0), GRIP_ANGLE), "R")   # knife space
FIST = hands.adjust(hands.POSES["fist"], index=(9.0, 4.0), middle=(9.0, 4.0), ring=(9.0, 4.0), pinky=(8.0, 3.0))


class DaggerPose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.spin = 0.0              # about the flat normal (knife X) through the grip, relative to the hand
        self.roll = 0.0              # about the long axis (knife Z) through the grip, relative to the hand
        self.grip = GRIP_PALM        # right palm frame in knife space
        self.fingers_r = None        # finger curls of the right hand (default: FIST, opened for the thicker grip)
        self.free_r = None           # (wrist, palm quat, curls) world target for the right hand while the knife flies
        self.free_w = 0.0
        self.hand_l = None           # (wrist, palm quat, curls) world target for the left hand
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)  # lunge offset of the right shoulder
        self.pole_r = None
        self.pole_l = None
        self.cam_q = quat_identity()

    # ---------------------------------------------------------------------------------- hierarchy
    def knife_world_q(self):
        q = self.knife_q
        if self.roll:
            q = quat_mul(q, quat_axis_angle((0, 0, 1), self.roll))
        if self.spin:
            q = quat_mul(q, rx(self.spin))
        return q

    def matrices(self):
        def T(t, q=None):
            m = np.eye(4)
            if q is not None:
                m[:3, :3] = quat_to_mat(q)
            m[:3, 3] = t
            return m
        mk = T(self.knife_t, self.knife_world_q())
        out = {"knife": mk}
        for name, (parent, t) in NODES.items():
            out[name] = out[parent] @ T(t)
        return out

    def point(self, node, local):
        m = self.matrices()[node]
        return m[:3, :3] @ np.asarray(local, float) + m[:3, 3]

    def knife_point(self, local):
        return self.knife_t + quat_rotate(self.knife_world_q(), local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else FIST
        return self.knife_t + quat_rotate(self.knife_q, w), quat_mul(self.knife_q, q), curls   # un-spun frame

    # -------------------------------------------------------------------------------------- solve
    def _hand_target(self):
        target = self.grip_target()
        if self.free_r is not None and self.free_w > 0:
            target = armrig.blend_targets([(1 - self.free_w, target), (self.free_w, self.free_r)])
        return target

    def solve(self):
        shoulder_r = SHOULDER_R + self.shoulder_r
        pole_r = POLE_R if self.pole_r is None else self.pole_r
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
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(t, q):
    """Knife root / orientation after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = DaggerPose()
    p.knife_t, p.knife_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


REST_T, REST_Q = reachable(REST_T, REST_Q)


def rest_values():
    trs, _ = DaggerPose().solve()
    return trs
