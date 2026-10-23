"""Karambit viewmodel rig (VIEWMODEL_SPEC section 34): node layout, the claw grip, ready stance and pose -> node TRS.

A rigid `knife` node at the middle of the handle carries the blade, the handle and the ring. The hand holds it in a
reverse "claw" grip: the palm on the spine side of the handle (knife +Y), the middle, ring and little finger wrapped
round the finger side (-X), the thumb along the handle towards the ring and the index finger through the ring. The ring
is placed where the index finger runs (forward kinematics of the grip below), so the finger passes through its hole in
every pose that keeps the grip. Besides its position and orientation the knife can `spin` about the ring's axis (knife
Y through the ring's centre): it turns round the index finger while the other fingers let go — the karambit's spins,
and the change to a forward grip and back. Like the dagger (dagger_rig.py) the knife follows the IK hand, the wrist
limits turn it with the hand, and a free hand target (`free_r`) lets the hand leave the handle."""
import numpy as np

import armrig
import hands
import karambit_model as km
from p9_rig import POLE_L, POLE_R
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_rotate, quat_slerp, quat_to_mat

SHOULDER_R = np.array([0.200, -0.330, 0.060])      # knife stance, as the dagger
SHOULDER_L = np.array([-0.200, -0.330, 0.060])
ALIGN_R = 0.30
LEFT_REST = (np.array([-0.10, -0.42, -0.10]), None, hands.blend({"relaxed": 1.0}))   # off screen, hidden


def knife_quat(blade_dir, palm_side):
    """Knife orientation from the blade direction at its root (knife -Z) and the direction of the palm side of the
    handle (knife +Y: the flats face it, the claw curves towards the knife's -X)."""
    z = -norm(blade_dir)
    y = norm(np.asarray(palm_side, float) - z * np.dot(palm_side, z))
    return frame_quat(np.cross(y, z), y, z)


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


# ---- the claw grip (knife space: the root at km.GRIP) --------------------------------------------------------------
# palm on the spine (+Y) side of the handle facing it, fingers pointing to the finger side (-X) and curling round it,
# the thumb along the handle towards the ring (+Z); the knuckle line sits just past the finger side of the handle
_FWD, _THUMB = np.array([-1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0])
_Q_GRIP = hands.palm_frame(_FWD, _THUMB, "R")
_KNUCKLE_X = -(km.HANDLE_A + hands.FINGER_T / 2)
_PALM_C = np.array([_KNUCKLE_X + hands.PALM_LEN / 2, km.HANDLE_B + hands.PALM_T / 2, 0.0])
GRIP_PALM = (_PALM_C - quat_rotate(_Q_GRIP, (0.0, -hands.PALM_LEN / 2, 0.0)), _Q_GRIP)     # (wrist, palm quat)
# the claw fist: the three fingers wrapped round the slim handle, the index curled through the ring, the thumb laid
# along the handle's back towards the ring
CLAW = hands.adjust(hands.POSES["fist"], index=(14.0, 34.0), middle=(4.0, 2.0), ring=(4.0, 2.0), pinky=(6.0, 3.0),
                    thumb=(14.0, 18.0))


def _index_through_ring():
    """Design-frame point where the index finger's first segment crosses the blade's plane (knife Y = 0) in the claw
    grip, and the direction it runs there: the ring is centred on it, its hole along the finger."""
    wrist, q = GRIP_PALM
    lay = hands.joint_layout("R")
    tr = hands.finger_trs("R", CLAW)
    _, t1, _ = lay["arm_R_index"]
    p1 = wrist + quat_rotate(q, t1)
    q1 = quat_mul(q, tr["arm_R_index"]["rotation"])
    d = quat_rotate(q1, (0.0, -1.0, 0.0))
    s = -p1[1] / d[1] if abs(d[1]) > 1e-6 else 0.0
    s = float(np.clip(s, 0.0, hands.LENGTHS["index"][0]))
    return p1 + d * s + km.GRIP, d


RING_C, INDEX_DIR = _index_through_ring()
RING_C = RING_C * np.array([1.0, 0.0, 1.0])          # in the blade's plane
TIP = km.tip_point()

ORIGINS = {"blade": np.zeros(3), "handle": km.GRIP.copy(), "ring": RING_C.copy()}
NODES = {   # name: (parent, translation)
    "blade": ("knife", ORIGINS["blade"] - km.GRIP),
    "tip": ("blade", TIP - ORIGINS["blade"]),
    "handle": ("knife", ORIGINS["handle"] - km.GRIP),
    "ring": ("knife", ORIGINS["ring"] - km.GRIP),
}
MESH_NODES = ("blade", "handle", "ring")
BLADE_MID = 0.5 * (ORIGINS["blade"] + TIP) - km.GRIP           # knife space: middle of the root -> point chord
RING_K = RING_C - km.GRIP                                      # knife space: the ring's centre (the spin pivot)
CHARM_MOUNT = km.lanyard_point(RING_C) - km.GRIP               # section 19: the charm hangs from the lanyard tab

# authored ready stance (made reachable below): the fist low on the right in a guard, palm facing in, thumb up, the
# claw hanging forward-down out of the bottom of the fist and hooking forward; the point ends right of and below the
# crosshair area
REST_T = np.array([0.116, -0.080, -0.312])
REST_BLADE = norm((-0.55, -0.40, -0.73))
REST_PALM = norm((0.95, -0.05, 0.10))
REST_Q = knife_quat(REST_BLADE, REST_PALM)


class KarambitPose:
    def __init__(self):
        self.knife_t = REST_T.copy()
        self.knife_q = REST_Q.copy()
        self.spin = 0.0              # about the ring's axis (knife Y) through its centre, relative to the hand
        self.grip = GRIP_PALM        # right palm frame in knife space
        self.fingers_r = None        # finger curls of the right hand (default: CLAW)
        self.free_r = None           # (wrist, palm quat, curls) world target for the right hand while the knife flies
        self.free_w = 0.0
        self.hand_l = None           # (wrist, palm quat, curls) world target for the left hand
        self.left_scale = 0.0
        self.shoulder_r = np.zeros(3)  # lunge offset of the right shoulder
        self.pole_r = None
        self.pole_l = None
        self.cam_q = quat_identity()

    # ---------------------------------------------------------------------------------- hierarchy
    def spin_q(self):
        return quat_axis_angle((0, 1, 0), self.spin) if self.spin else quat_identity()

    def knife_world_q(self):
        return quat_mul(self.knife_q, self.spin_q()) if self.spin else self.knife_q

    def knife_world_t(self):
        """The knife root turned round the ring by the spin (the hand's frame keeps the ring where it is)."""
        if not self.spin:
            return self.knife_t
        return self.knife_t + quat_rotate(self.knife_q, RING_K - quat_rotate(self.spin_q(), RING_K))

    def matrices(self):
        def T(t, q=None):
            m = np.eye(4)
            if q is not None:
                m[:3, :3] = quat_to_mat(q)
            m[:3, 3] = t
            return m
        mk = T(self.knife_world_t(), self.knife_world_q())
        out = {"knife": mk}
        for name, (parent, t) in NODES.items():
            out[name] = out[parent] @ T(t)
        return out

    def point(self, node, local):
        m = self.matrices()[node]
        return m[:3, :3] @ np.asarray(local, float) + m[:3, 3]

    def knife_point(self, local):
        return self.knife_world_t() + quat_rotate(self.knife_world_q(), local)

    def grip_target(self):
        w, q = self.grip
        curls = self.fingers_r if self.fingers_r is not None else CLAW
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
            "knife": {"translation": self.knife_world_t(), "rotation": self.knife_world_q()},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = shoulder_r
        trs["arm_L"]["scale"] = np.full(3, self.left_scale)
        return trs, {"R": ar, "L": al, "target_R": target, "target_L": hl}


def reachable(t, q):
    """Knife root / orientation after the wrist limit for an authored pose (authoring pass for key poses)."""
    p = KarambitPose()
    p.knife_t, p.knife_q = np.asarray(t, float).copy(), np.asarray(q, float).copy()
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


REST_T, REST_Q = reachable(REST_T, REST_Q)


def rest_values():
    trs, _ = KarambitPose().solve()
    return trs


def index_in_ring(trs_world, ring_world, ring_axis_world):
    """How far (m) the index finger's first segment passes from the ring's centre where it crosses the ring's plane
    (nan when it does not cross it) — for the checks: < the hole's radius means the finger is through the ring."""
    a, b = trs_world
    n = norm(ring_axis_world)
    da, db = np.dot(a - ring_world, n), np.dot(b - ring_world, n)
    if da * db > 0:
        return float("nan")
    s = da / (da - db)
    p = a + (b - a) * s
    return float(np.linalg.norm(p - ring_world))
