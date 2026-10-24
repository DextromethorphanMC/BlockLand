"""MP5A3 viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like the AK rig (ak_rig.py), plus the MP5's
non-reciprocating cocking handle: `bolt` (the slide-lock node) is the cocking lever riding the slot (+Z), its child
`charging_handle` the root block that turns up into the locking notch (about the cocking tube's axis) and its child
`handle_lever` the folding lever (about its hinge pin); the bolt carrier seen through the ejection port is `carrier`,
cycling on its own when the gun fires."""
import numpy as np

import armrig
import hands
import mp5_model as mm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = mm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.122, -0.124, -0.262])       # framed for the 54 deg viewmodel FOV (coverage.py, framing.py)
GUN_EULER = (2.5, 3.0, 7.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.285, -0.090])  # the support hand sits close in on the short handguard
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = 15.0
SELECTOR_AUTO = 0.0                              # the lever as modelled points forward: full auto (F)
PADDLE_PUSH = 14.0                               # degrees the paddle swings forward when pressed
UNFOLD = 84.0                                    # the cocking lever swung out of its folded rest
LOCK = -mm.NOTCH_A                               # the handle turned up into the locking notch (about the tube axis)

STATIC = ("receiver", "cocking_tube", "front_sight", "claw_mount", "handguard", "housing", "pistol_grip", "barrel")
TUBE_PT = mm.TUBE_AXIS_PT                        # charging_handle origin (bolt space = design frame at rest)
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "stock": ("gun", G((0, 0, 0))),
    "bolt": ("gun", G((0, 0, 0))),
    "charging_handle": ("bolt", TUBE_PT),
    "handle_lever": ("charging_handle", mm.HINGE - TUBE_PT),
    "carrier": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(mm.TRIGGER_PIVOT)),
    "selector": ("gun", G(mm.SELECTOR_PIVOT)),
    "paddle": ("gun", G(mm.PADDLE_PIVOT)),
    "sight_iron": ("gun", G((0.0, mm.SIGHT_Y, mm.FRONT_POST_Z))),
    "front_sight_hood": ("sight_iron", np.zeros(3)),
    "sight_rear": ("gun", G((0.0, mm.SIGHT_Y, mm.REAR_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, mm.CROWN_Z))),
    "mag": ("gun", G(mm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, mm.MUZZLE_Z - 0.0030))),
    "ejection": ("gun", G(mm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": mm.TRIGGER_PIVOT, "selector": mm.SELECTOR_PIVOT, "paddle": mm.PADDLE_PIVOT,
    "sight_iron": (0.0, mm.SIGHT_Y, mm.FRONT_POST_Z), "front_sight_hood": (0.0, mm.SIGHT_Y, mm.FRONT_POST_Z),
    "sight_rear": (0.0, mm.SIGHT_Y, mm.REAR_Z), "muzzle_brake": (0.0, 0.0, mm.CROWN_Z),
    "charging_handle": TUBE_PT, "handle_lever": mm.HINGE,
}
# eye point (section 5): on the sight line, looking along -Z, behind the drum's aperture (cheek on the stock rails)
AIM_IRON_BACK = 0.0500
AIM_IRON_T = G((0.0, mm.SIGHT_Y, mm.REAR_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.85, 0.35, -0.30))           # cases go right, a little up and forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, mm.RAIL_TOP, mm.OPTIC_Z)), None, None),           # claw mount's picatinny top
    # laser on the right of the handguard: the module hangs along the attachment's -Y, turn it onto +X
    "mount_laser": ("gun", G((mm.HG_SIDE_X, -0.0020, -0.1120)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, mm.LUG_Z1 - 0.0020)), None, None),          # over the lugs (cap off)
    "mount_stock": ("gun", G((0.0, -0.0040, mm.CAP_Z1 + 0.0005)), None, None),       # replaces the A3 stock
    # section 35: under the front of the handguard, ahead of the support hand
    "mount_underbarrel": ("gun", G((0.0, mm.HG_Y0 - 0.0004, -0.1390)), None, None),
    # the rear sling loop on the left of the end cap; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-mm.REC_W - 0.0040, -0.0085, mm.CAP_Z0 + 0.0130)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


_RADIAL, _TANGENT = mm._tube_dirs()
UNFOLD_AXIS = norm(np.cross((0.0, 0.0, -1.0), _RADIAL))     # turns the folded lever's -Z towards the radial


def lock_q(deg):
    return quat_axis_angle((0, 0, 1), deg)


def unfold_q(deg):
    return quat_axis_angle(UNFOLD_AXIS, deg)


# ---- hand anchors (section 16) ---------------------------------------------------------------------
def _anchor(centre, fwd, thumb, side, pose):
    """Anchor from the palm centre (middle of the palm box) instead of the wrist."""
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q, pose


def _wrap(centre, fwd, thumb, side, pose, r, push=0.0):
    """Palm face `r` from `centre` (towards the part), slid `push` along the fingers."""
    q = hands.palm_frame(fwd, thumb, side)
    z = quat_rotate(q, (0.0, 0.0, 1.0))
    return _anchor(np.asarray(centre, float) - hands.wrap_distance(r) * z + push * norm(fwd), fwd, thumb, side, pose)


def _tip_anchor(tip, finger, fwd, thumb, side, pose):
    """Anchor placing one fingertip (of `pose`) on `tip`: for hooking small parts."""
    q = hands.palm_frame(fwd, thumb, side)
    off = hands.fingertips(side, np.zeros(3), q, hands.blend({pose: 1.0}))[finger]
    return np.asarray(tip, float) - off, q, pose


# the MP5's grip: the trigger sits closer to the grip than the AK's (finger poses fitted to that reach)
hands.POSES.setdefault("mp5_indexed", {"thumb": (-48, -28), "index": (4, -22), "middle": (-86, -76),
                                       "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("mp5_trigger", {"thumb": (-48, -28), "index": (-12, -56), "middle": (-86, -76),
                                       "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("mp5_clamp", {"thumb": (-22, -14), "index": (-60, -56), "middle": (-66, -62),
                                     "ring": (-70, -66), "pinky": (-74, -68)})
hands.POSES.setdefault("mp5_pinch", {"thumb": (-30, -24), "index": (-62, -58), "middle": (-70, -66),
                                     "ring": (-80, -74), "pinky": (-84, -78)})
hands.POSES.setdefault("mp5_mag", {"thumb": (-20, -10), "index": (-52, -44), "middle": (-62, -54),
                                   "ring": (-68, -58), "pinky": (-72, -62)})

_F, _A = mm.GRIP_FRONT, mm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
HG_C = np.array([0.0, -0.0040, -0.0740])          # handguard section centre where the support hand sits
KNOB = mm.HINGE + np.array([0.0, 0.0, -0.0270])   # the lever's knob (folded, design frame)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the frame
    "grip": ("gun", _wrap(G(mm.grip_center(0.020)), _FR, -_A, "R", "mp5_indexed", 0.030, -0.026)),
    # support hand under the handguard: palm up, thumb along the left side, fingers up the right side
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.034, -0.016)),
    # round the magazine under the well (mag space: +Y up the mag, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, -0.060, 0.002), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L", "mp5_mag", 0.0225,
                         0.004)),
    # from below: palm on the magazine's left, fingers up and round its front, thumb tip on the paddle behind it
    "mag_release": ("mag", _tip_anchor((0.0, -0.0225, 0.0285), "thumb", norm((0.0, 0.55, -0.83)),
                                       norm((0.0, 0.40, 1.0)), "L", "mp5_mag")),
    # fresh magazine held low by its body: palm on its front face, fingers round the right side, thumb up the left
    "mag_low": ("mag", _wrap((0.0, -0.090, -0.006), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "mp5_mag", 0.0380,
                             -0.018)),
    # heel of the palm under the floor plate (the seating push), fingers forward
    "slap": ("mag", _anchor((0.0, -0.205, -0.045), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # fingers hooked round the front of the handle's end, palm towards the gun, thumb back (knob space: at the knob,
    # turned with the handle into the notch but not with the lever's unfolding)
    "handle": ("knob", _tip_anchor((0.0010, 0.0020, -0.0070), "middle", norm((0.35, 0.80, -0.50)),
                                   norm((0.0, -0.30, 1.0)), "L", "mp5_pinch")),
}


def _knob_locked():
    """Gun-local centre of the knob with the handle hauled back, unfolded and locked in the notch."""
    q = Pose()
    q.bolt_z, q.lock, q.fold = mm.BOLT_TRAVEL, LOCK, UNFOLD
    return q.knob_local()


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0           # cocking handle stroke (+Z rearward)
        self.lock = 0.0             # handle turned about the tube axis (LOCK = in the notch)
        self.fold = 0.0             # lever unfolded (0 = folded forward against the tube)
        self.carrier_z = 0.0        # bolt carrier stroke (firing, or dragged by the handle)
        self.trigger = 0.0
        self.selector = SELECTOR_AUTO
        self.paddle = 0.0
        self.mag_t = NODES["mag"][1].copy()
        self.mag_q = MAG_Q.copy()
        self.mag_s = 1.0
        self.hand_r = None          # (wrist, palm quat, curls) in viewmodel space; default = grip anchor
        self.hand_l = None          # default = support anchor
        self.fingers_r = None
        self.fingers_l = None
        self.cam_q = quat_identity()
        self.pole_r = None
        self.pole_l = None
        self.shoulder_l = np.zeros(3)
        self.shoulder_r = np.zeros(3)

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def mag_point(self, p):
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def lever_frame(self):
        """Gun-local transform (t, q) of the lever space: the design frame of the folded lever at rest."""
        ql = lock_q(self.lock)
        qu = unfold_q(self.fold)
        bolt_t = NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])
        hinge = bolt_t + TUBE_PT + quat_rotate(ql, mm.HINGE - TUBE_PT)
        q = quat_mul(ql, qu)
        return hinge - quat_rotate(q, mm.HINGE), q

    def knob_local(self):
        """Gun-local centre of the knob at the lever's end (follows the stroke, the lock and the unfolding)."""
        t, q = self.lever_frame()
        return t + quat_rotate(q, KNOB + 0.0040 * _RADIAL)

    def space_point(self, space, p):
        if space == "gun":
            return self.gun_point(p)
        if space == "knob":
            return self.gun_point(self.knob_local() + quat_rotate(lock_q(self.lock), p))
        return self.mag_point(p)

    def space_quat(self, space):
        if space == "mag":
            return quat_mul(self.gun_q, self.mag_q)
        if space == "knob":
            return quat_mul(self.gun_q, lock_q(self.lock))
        return self.gun_q

    def anchor(self, which):
        space, (w, q, pose) = ANCHORS[which]
        return self.space_point(space, w), quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

    # node TRS -----------------------------------------------------------------------------------
    def solve(self):
        hr = self.hand_r if self.hand_r is not None else self.anchor("grip")
        hl = self.hand_l if self.hand_l is not None else self.anchor("support")
        if self.fingers_r is not None:
            hr = (hr[0], hr[1], self.fingers_r)
        if self.fingers_l is not None:
            hl = (hl[0], hl[1], self.fingers_l)
        sr, sl = SHOULDER_R + self.shoulder_r, SHOULDER_L + self.shoulder_l
        tr, ar = armrig.solve_side("R", sr, POLE_R if self.pole_r is None else self.pole_r, hr)
        tl, al = armrig.solve_side("L", sl, POLE_L if self.pole_l is None else self.pole_l, hl, limit=True)
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
            "charging_handle": {"rotation": lock_q(self.lock)},
            "handle_lever": {"rotation": unfold_q(self.fold)},
            "carrier": {"translation": NODES["carrier"][1] + np.array([0.0, 0.0, self.carrier_z])},
            "trigger": {"rotation": rx(self.trigger)},
            "selector": {"rotation": rx(self.selector)},
            "paddle": {"rotation": rx(self.paddle)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
# the HK slap: the flat of the hand coming down on the locked handle from the left, fingers to the right, thumb forward
# (gun space, the heel of the palm on the knob)
KNOB_LOCKED = _knob_locked()
ANCHORS["hk_slap"] = ("gun", _anchor(KNOB_LOCKED + np.array([0.0080, 0.0150, -0.0040]), norm((0.80, -0.25, -0.55)),
                                     norm((0.0, 0.20, -1.0)), "L", "spread"))
