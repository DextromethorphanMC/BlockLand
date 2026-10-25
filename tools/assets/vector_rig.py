"""Vector viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 44): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like the MP5 rig (mp5_rig.py): the
non-reciprocating charging handle is `bolt` (the slide-lock node: the slider riding the left slot, +Z) with its child
`handle_lever` (the knob that folds out about its hinge, about Y); the bolt seen through the ejection port is `carrier`,
cycling on its own when the gun fires; `paddle` is the magazine release lever at the front of the guard."""
import numpy as np

import armrig
import hands
import vector_model as vm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = vm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.122, -0.115, -0.260])       # framed for the 54 deg viewmodel FOV (coverage.py, framing.py)
GUN_EULER = (2.5, 3.0, 7.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.285, -0.090])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = 14.0
SELECTOR_AUTO = 0.0
PADDLE_PUSH = 13.0                               # degrees the release lever swings forward when pressed
UNFOLD = 80.0                                    # the knob swung out of its folded rest (about +Y: out to the left)

STATIC = ("receiver", "lower", "pistol_grip", "barrel")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "stock": ("gun", G((0, 0, 0))),
    "bolt": ("gun", G((0, 0, 0))),
    "handle_lever": ("bolt", vm.HINGE.copy()),       # bolt space = the design frame shifted by the stroke
    "carrier": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(vm.TRIGGER_PIVOT)),
    "selector": ("gun", G(vm.SELECTOR_PIVOT)),
    "paddle": ("gun", G(vm.PADDLE_PIVOT)),
    "sight_iron": ("gun", G((0.0, vm.SIGHT_Y, vm.FRONT_POST_Z))),
    "front_sight_hood": ("sight_iron", np.zeros(3)),
    "sight_rear": ("gun", G((0.0, vm.SIGHT_Y, vm.REAR_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, vm.CROWN_Z))),
    "mag": ("gun", G(vm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, vm.MUZZLE_Z - 0.0030))),
    "ejection": ("gun", G(vm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": vm.TRIGGER_PIVOT, "selector": vm.SELECTOR_PIVOT, "paddle": vm.PADDLE_PIVOT,
    "sight_iron": (0.0, vm.SIGHT_Y, vm.FRONT_POST_Z), "front_sight_hood": (0.0, vm.SIGHT_Y, vm.FRONT_POST_Z),
    "sight_rear": (0.0, vm.SIGHT_Y, vm.REAR_Z), "muzzle_brake": (0.0, 0.0, vm.CROWN_Z),
    "handle_lever": vm.HINGE,
}
# eye point (section 5): on the sight line, looking along -Z, behind the rear aperture
AIM_IRON_BACK = 0.0850                          # behind the rail's end: nothing within 3 cm in front of the eye
AIM_IRON_T = G((0.0, vm.SIGHT_Y, vm.REAR_Z + AIM_IRON_BACK))
MAG_Q = quat_axis_angle((1, 0, 0), -vm.MAG_RAKE)   # the magazine raked back in its well (its foot rearward)
EJECT_DIR = norm((0.85, 0.35, -0.30))           # cases go right, a little up and forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, vm.RAIL_TOP, vm.OPTIC_Z)), None, None),
    # laser on the right side rail stub: the module hangs along the attachment's -Y, turn it onto +X
    "mount_laser": ("gun", G((vm.REC_W + 0.0050, 0.0000, -0.0900)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, vm.HIDER_Z0 - 0.0010)), None, None),        # on the thread (hider off)
    "mount_stock": ("gun", G(vm.STOCK_MOUNT), None, None),                           # on the hinge block
    # section 35: the accessory rail under the nose
    "mount_underbarrel": ("gun", G((0.0, vm.CHIN_Y - 0.0062, 0.5 * (vm.CRAIL_Z0 + vm.CRAIL_Z1))), None, None),
    # the sling point under the left of the stock adapter; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-0.0170, 0.0110, vm.REC_Z1 + 0.0400)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


def unfold_q(deg):
    return quat_axis_angle((0, 1, 0), deg)


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


# finger poses fitted to the Vector's grip and its parts
hands.POSES.setdefault("vec_indexed", {"thumb": (-48, -28), "index": (4, -22), "middle": (-86, -76),
                                       "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("vec_trigger", {"thumb": (-48, -28), "index": (-14, -58), "middle": (-86, -76),
                                       "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("vec_chin", {"thumb": (-16, -10), "index": (-58, -50), "middle": (-64, -56),
                                    "ring": (-68, -60), "pinky": (-72, -64)})
hands.POSES.setdefault("vec_clamp", {"thumb": (-24, -16), "index": (-66, -60), "middle": (-72, -66),
                                     "ring": (-76, -70), "pinky": (-80, -72)})
hands.POSES.setdefault("vec_pinch", {"thumb": (-34, -20), "index": (-56, -46), "middle": (-74, -66),
                                     "ring": (-82, -74), "pinky": (-84, -78)})
hands.POSES.setdefault("vec_mag", {"thumb": (-20, -10), "index": (-52, -44), "middle": (-62, -54),
                                   "ring": (-68, -58), "pinky": (-72, -62)})

_F, _A = vm.GRIP_FRONT, vm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
CHIN_C = np.array([0.0, -0.0560, -0.0040])        # section centre of the lower's slanted front where the support hand wraps it
KNOB = vm.HINGE + np.array([-0.0022, 0.0, -vm.KNOB_LEN + 0.0060])   # the knob's grip end (folded, design frame)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the frame
    "grip": ("gun", _wrap(G(vm.grip_center(0.022)), _FR, -_A, "R", "vec_indexed", 0.030, -0.026)),
    # support hand round the chin: palm on its left flank and under it, thumb up the left, fingers round the front
    "support": ("gun", _wrap(G(CHIN_C), norm((0.85, -0.45, -0.05)), norm((-0.15, 0.30, -1.0)), "L", "vec_chin",
                             0.028, -0.010)),
    # round the magazine under the well (mag space: +Y up the mag, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, -0.068, 0.002), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L", "vec_mag", 0.0230,
                         0.004)),
    # from below: palm on the magazine's left, the thumb tip on the release lever behind it
    "mag_release": ("mag", _tip_anchor((0.0, -0.0300, 0.0230), "thumb", norm((0.0, 0.55, -0.83)),
                                       norm((0.0, 0.40, 1.0)), "L", "vec_mag")),
    # fresh magazine held low by its body: palm on its front face, fingers round the right side, thumb up the left
    "mag_low": ("mag", _wrap((0.0, -0.095, -0.006), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "vec_mag", 0.0380,
                             -0.018)),
    # heel of the palm under the floor plate (the seating push), fingers forward
    "slap": ("mag", _anchor((0.0, -0.200, -0.045), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # fingers hooked round the unfolded knob from below, palm towards the gun, thumb back (knob space)
    "handle": ("knob", _tip_anchor((-0.0030, -0.0020, 0.0030), "middle", norm((0.35, 0.80, -0.50)),
                                   norm((0.0, -0.30, 1.0)), "L", "vec_pinch")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0           # charging handle stroke (+Z rearward)
        self.fold = 0.0             # knob unfolded (0 = folded forward against the flank)
        self.carrier_z = 0.0        # bolt stroke (firing, or dragged by the handle)
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

    def knob_local(self):
        """Gun-local point of the knob's grip end (follows the stroke and the unfolding)."""
        hinge = G(vm.HINGE) + np.array([0.0, 0.0, self.bolt_z])
        return hinge + quat_rotate(unfold_q(self.fold), KNOB - vm.HINGE)

    def space_point(self, space, p):
        if space == "gun":
            return self.gun_point(p)
        if space == "knob":
            return self.gun_point(self.knob_local() + quat_rotate(unfold_q(self.fold), p))
        return self.mag_point(p)

    def space_quat(self, space):
        if space == "mag":
            return quat_mul(self.gun_q, self.mag_q)
        if space == "knob":
            return quat_mul(self.gun_q, unfold_q(self.fold))
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
