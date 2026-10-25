"""Revolver viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 49): node layout in gun space, hip pose, hand anchors
and pose -> node TRS (two-bone arm IK to palm frames, finger curls).

It keeps the P9 rig's interface (Pose with gun_t / gun_q, the hands, trigger, hammer, the `grip` and `support`
anchors, GUN_T / GUN_Q, NODES with a `slide`, AIM_IRON_T ...), so the P9's idle, draw and locomotion clips run on it
unchanged (revolver_clips loads them through variants with this module standing in for p9_rig); the P9-only
attributes (slide, slide stop, magazine well) are accepted and ignored. Its own mechanism: the crane swings the
cylinder out to the left about an axis under it, the cylinder indexes about its own axis, the ejector rod pushes the
star (and the cartridges with it) out of the cylinder's rear, the thumb latch slides forwards, and the speed loader
(`mag`) is carried by the left hand."""
import numpy as np

import armrig
import hands
import revolver_model as rm
from ik import UPPER_LEN
from vmath import norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = rm.GRIP_C0.copy()                            # gun pivot: top of the grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.124, -0.082, -0.350])       # framed for the 54 deg viewmodel FOV (the bore sits high over the hand)
GUN_EULER = (5.0, 1.0, 5.0)                     # yaw (inward), pitch, roll (top leans in)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.200, -0.320, 0.050])
SHOULDER_L = np.array([-0.200, -0.320, 0.050])
POLE_R = np.array([1.0, -0.60, -0.10])
POLE_L = np.array([-1.0, -0.60, -0.10])

HAMMER_COCKED = 38.0                            # degrees back (single-action notch); double action passes through it
SLIDE_LOCK = 0.0                                # P9 interface (no slide)
STOP_UP = 0.0
CRANE_OPEN = 86.0                               # the cylinder swung out to the left
LATCH_TRAVEL = 0.0035

CYL_LOCAL = np.array([0.0, rm.CYL_Y, rm.CYL_FRONT]) - (rm.CRANE_AXIS + np.array([0.0, 0.0, rm.CYL_FRONT]))
NODES = {   # name: (parent, translation in parent space)
    "frame": ("gun", G((0, 0, 0))),
    "slide": ("gun", G((0, 0, 0))),             # P9 interface only: the sight line's reference (not in the file)
    "barrel": ("gun", G((0, 0, 0))),
    "grip": ("gun", G((0, 0, 0))),
    "sight_iron": ("gun", G((0, 0, 0))),
    "sight_rear": ("gun", G((0, 0, 0))),
    "hammer": ("gun", G(rm.HAMMER_PIVOT)),
    "trigger": ("gun", G(rm.TRIGGER_PIVOT)),
    "latch": ("gun", G(rm.LATCH_C)),
    "crane": ("gun", G(rm.CRANE_AXIS + np.array([0.0, 0.0, rm.CYL_FRONT]))),
    "cylinder": ("crane", CYL_LOCAL),
    "ejector": ("cylinder", np.zeros(3)),
    "rounds": ("ejector", np.zeros(3)),
    "ejection": ("cylinder", rm.EJECTION),
    "muzzle": ("gun", G((0.0, 0.0, rm.MUZZLE_Z - 0.0030))),
    "mag": ("gun", G((-0.090, -0.250, 0.060))),   # the speed loader's rest: off screen, below left (hidden)
}
AIM_IRON_T = G((0.0, rm.SIGHT_Y, rm.REAR_SIGHT_Z[1] + 0.110))         # eye point: on the sight line behind the notch
MAG_REST = NODES["mag"][1].copy()

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G(rm.OPTIC_BASE), None, None),               # on the top strap over the cylinder
    # the charm hangs from the lanyard ring under the butt; -Y straight down in the hip pose
    "mount_charm": ("gun", G(rm.CHARM_AT), quat_conj(GUN_Q), None),
}


def ejection_quat():
    """Cases leave the cylinder backwards and a little to the right (the node's +X; the gun's right at rest)."""
    from vmath import frame_quat
    x = norm((0.60, 0.0, 0.80))
    y = np.array([0.0, 1.0, 0.0])
    z = np.cross(x, y)
    return frame_quat(x, y, z)


# ---- hand anchors (section 16): wrist position + palm frame in a named space + finger pose ----------
def _anchor(centre, fwd, thumb, side, pose):
    """Anchor from the palm centre (middle of the palm box) instead of the wrist."""
    q = hands.palm_frame(fwd, thumb, side)
    wrist = np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0))
    return wrist, q, pose


def _wrap(centre, fwd, thumb, side, pose, r, push=0.0):
    """Anchor wrapping a part: palm face `r` from `centre` (towards the part), slid `push` along the fingers."""
    q = hands.palm_frame(fwd, thumb, side)
    z = quat_rotate(q, (0.0, 0.0, 1.0))
    return _anchor(np.asarray(centre, float) - hands.wrap_distance(r) * z + push * norm(fwd), fwd, thumb, side, pose)


_X = np.array([1.0, 0.0, 0.0])
_F, _A = rm.FRONT, rm.AXIS
_FR = norm(_F * np.cos(np.radians(5.0)) - _X * np.sin(np.radians(5.0)))         # right palm along the right panel
_FL = norm(_F * np.cos(np.radians(45.0)) + _X * np.sin(np.radians(45.0)) + 0.45 * _A)
CYL_SPACE_C = np.array([0.0, 0.0, 0.5 * (rm.CYL_REAR - rm.CYL_FRONT)])       # cylinder space: its middle
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right grip panel, knuckles round the front strap, thumb up the grip
    "grip": ("gun", _wrap(G(rm.grip_center(0.032)), _FR, -_A, "R", "indexed", 0.035, -0.024)),
    # support hand: thumbs forward, palm on the left panel over the right fingers, fingers round the front
    "support": ("gun", _wrap(G(rm.grip_center(0.050)), _FL, norm(-_A + 0.5 * _F), "L", "support", 0.041)),
    # the left hand cradling the frame from below, its fingers through the window pushing the cylinder out (gun space)
    "cradle": ("gun", _wrap(G((0.004, rm.CYL_Y - 0.004, rm.CYL_FRONT + 0.010)), (1.0, 0.25, 0.0), (0.0, 0.0, -1.0), "L",
                            "handguard", 0.034)),
    # the left hand round the swung-out cylinder from below, the thumb along it (cylinder space)
    "cylinder": ("cylinder", _wrap(CYL_SPACE_C, (1.0, 0.3, 0.0), (0.0, 0.0, -1.0), "L", "handguard", 0.036)),
    # the left thumb on the ejector rod's tip (cylinder space: the rod runs -Z from the front face), palm under it
    "ejector": ("cylinder", _anchor((-0.010, -0.032, -0.060), (0.25, 1.0, 0.25), (0.0, 0.0, 1.0), "L", "press")),
    # the speed loader's knob between the left thumb and fingers (mag space: +Z behind the loader)
    # (from its left side: the palm beside the knob, the fingers up over it, the thumb on its back; the forearm stays
    # out of the line of sight to the cylinder)
    "loader": ("mag", _anchor((-0.024, -0.012, 0.022), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), "L", "pinch")),
}
GRIP_POSE_R = "indexed"


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.trigger = 0.0
        self.hammer = 0.0                    # double action: down at rest
        self.cylinder = 0.0                  # index turn about the cylinder's axis (degrees)
        self.crane = 0.0                     # the cylinder swung out (degrees)
        self.ejector = 0.0                   # the star pushed out (metres)
        self.latch = 0.0                     # the thumb latch pushed forwards (metres)
        self.rounds_s = 1.0                  # the cartridges in the cylinder shown
        self.mag_t = MAG_REST.copy()         # the speed loader (gun space)
        self.mag_q = quat_identity()
        self.mag_s = 0.0
        self.loader_rounds_s = 1.0
        self.slide_z = 0.0                   # P9 interface (ignored)
        self.stop = 0.0
        self.hand_r = None
        self.hand_l = None
        self.fingers_r = None
        self.fingers_l = None
        self.cam_q = quat_identity()
        self.pole_r = None
        self.pole_l = None

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def crane_q(self):
        return quat_axis_angle((0, 0, 1), self.crane)

    def cylinder_frame(self):
        """(origin, quat) of cylinder space in viewmodel space."""
        cq = quat_mul(self.gun_q, self.crane_q())
        origin = self.gun_point(NODES["crane"][1]) + quat_rotate(cq, CYL_LOCAL)
        return origin, quat_mul(cq, quat_axis_angle((0, 0, 1), self.cylinder))

    def mag_point(self, p):
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def space_quat(self, space):
        if space == "mag":
            return quat_mul(self.gun_q, self.mag_q)
        if space == "cylinder":
            return self.cylinder_frame()[1]
        return self.gun_q

    def anchor(self, which):
        """World (wrist, palm quat, curls) of a named hand anchor."""
        space, (w, q, pose) = ANCHORS[which]
        if space == "gun":
            pos = self.gun_point(w)
        elif space == "cylinder":
            o, cq = self.cylinder_frame()
            pos = o + quat_rotate(cq, w)
        else:
            pos = self.mag_point(w)
        return pos, quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        """weights: {anchor_name or 'world': w}; world: (wrist, palm quat, curls) in viewmodel space."""
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

    def world_target(self, pos, back, fwd=(0.0, -1.0, 0.0), side="L", pose="relaxed"):
        return np.asarray(pos, float), armrig.palm_from_back(back, fwd, side), hands.blend({pose: 1.0})

    # node TRS -----------------------------------------------------------------------------------
    def solve(self):
        hr = self.hand_r if self.hand_r is not None else self.anchor("grip")
        hl = self.hand_l if self.hand_l is not None else self.anchor("support")
        if self.fingers_r is not None:
            hr = (hr[0], hr[1], self.fingers_r)
        if self.fingers_l is not None:
            hl = (hl[0], hl[1], self.fingers_l)
        tr, ar = armrig.solve_side("R", SHOULDER_R, POLE_R if self.pole_r is None else self.pole_r, hr)
        tl, al = armrig.solve_side("L", SHOULDER_L, POLE_L if self.pole_l is None else self.pole_l, hl)
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "trigger": {"rotation": rx(self.trigger)},
            "hammer": {"rotation": rx(self.hammer)},
            "latch": {"translation": NODES["latch"][1] + np.array([0.0, 0.0, -self.latch])},
            "crane": {"rotation": self.crane_q()},
            "cylinder": {"rotation": quat_axis_angle((0, 0, 1), self.cylinder)},
            "ejector": {"translation": np.array([0.0, 0.0, self.ejector])},
            "rounds": {"scale": np.full(3, self.rounds_s)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "loader_rounds": {"scale": np.full(3, self.loader_rounds_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = SHOULDER_R
        trs["arm_L"]["translation"] = SHOULDER_L
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


def rest_trs():
    trs, _ = Pose().solve()
    return trs


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
