"""PP-19 Bizon viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 54): node layout in gun space, hip pose, hand
anchors and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like the AK rig (ak_rig.py): the
AK action (`bolt` = the carrier with its charging handle on the right, reciprocating), the AK safety lever, plus the
MP5's `paddle` (the magazine release behind the magazine). The helical magazine lies under the barrel and is the
foregrip: the support hand holds it from below, and it rocks out and in about the lug at its nose (MAG_FRONT_HOOK)."""
import numpy as np

import armrig
import bizon_model as bm
import hands
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = bm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.126, -0.120, -0.270])       # framed for the 54 deg viewmodel FOV (coverage.py, framing.py)
GUN_EULER = (2.5, 3.0, 7.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.300, -0.080])  # the support hand under the magazine, close in on the short gun
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
SELECTOR_FIRE = -12.0                            # lever down to full auto
TRIGGER_PULL = 16.0
PADDLE_PUSH = 14.0                               # degrees the release swings forward when pressed

STATIC = ("receiver", "dust_cover", "front_sight", "barrel", "pistol_grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "stock": ("gun", G((0, 0, 0))),
    "bolt": ("gun", G((0, 0, 0))),
    "selector": ("gun", G(bm.SELECTOR_PIVOT)),
    "trigger": ("gun", G(bm.TRIGGER_PIVOT)),
    "paddle": ("gun", G(bm.PADDLE_PIVOT)),
    "sight_iron": ("gun", G((0.0, bm.SIGHT_Y, bm.FRONT_POST_Z))),
    "front_sight_hood": ("sight_iron", np.zeros(3)),
    "sight_rear": ("gun", G((0.0, bm.SIGHT_Y, bm.REAR_NOTCH_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, bm.CROWN_Z))),
    "mag": ("gun", G(bm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, bm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(bm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "selector": bm.SELECTOR_PIVOT, "trigger": bm.TRIGGER_PIVOT, "paddle": bm.PADDLE_PIVOT,
    "sight_iron": (0.0, bm.SIGHT_Y, bm.FRONT_POST_Z), "front_sight_hood": (0.0, bm.SIGHT_Y, bm.FRONT_POST_Z),
    "sight_rear": (0.0, bm.SIGHT_Y, bm.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, bm.CROWN_Z),
}
# eye point (section 5): on the sight line, looking along -Z, a cheek weld on the stock's upper strut behind the
# receiver (the rear sight sits on the front trunnion, as the AK's)
AIM_IRON_BACK = 0.230
AIM_IRON_T = G((0.0, bm.SIGHT_Y, bm.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.82, 0.38, -0.30))           # shells go right, a little up and forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, bm.COVER_TOP + bm.RAIL_H, bm.OPTIC_Z)), None, None),     # on the cover rail
    # laser on the clamp-on side rail of the barrel: the module hangs along the attachment's -Y, turn it onto +X
    "mount_laser": ("gun", G((bm.LASER_X, 0.0, bm.LASER_Z)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, bm.CROWN_Z)), None, None),                        # muzzle nut off
    "mount_stock": ("gun", G((0.0, -0.0120, bm.REC_Z1 - 0.0005)), None, None),             # replaces the AKS stock
    # the rear sling loop on the left of the receiver behind the grip; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-bm.REC_W - 0.0035, -0.0350, bm.REC_Z1 - 0.0400)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


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


# the helical magazine is a fat cylinder: the fingers wrap further round it than round a handguard
hands.POSES.setdefault("bizon_wrap", {"thumb": (-18, -10), "index": (-58, -52), "middle": (-64, -58),
                                      "ring": (-68, -62), "pinky": (-70, -64)})
hands.POSES.setdefault("bizon_clamp", {"thumb": (-24, -14), "index": (-66, -60), "middle": (-72, -66),
                                       "ring": (-76, -70), "pinky": (-78, -72)})

_F, _A = bm.GRIP_FRONT, bm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
MAG_C = np.array([0.0, bm.MAG_Y, -0.0880])       # magazine section centre where the support hand sits (design)
MAG_AX = bm.MAG_AXIS_Y                           # mag-local axis height
KNOB = np.array([bm.REC_W + 0.0150, 0.0060, bm.HANDLE_Z + 0.0010])   # charging handle knob (bolt space at rest)
_UNDER = (norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)))       # palm up under the magazine, thumb forward
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the guard
    "grip": ("gun", _wrap(G(bm.grip_center(0.018)), _FR, -_A, "R", "ak_indexed", 0.031, -0.027)),
    # support hand under the magazine: palm up, thumb along its left, fingers round its right side
    "support": ("gun", _wrap(G(MAG_C), *_UNDER, "L", "bizon_wrap", bm.MAG_R + 0.0010, -0.010)),
    # the same hold on the magazine in its own space (it rocks out with the hand)
    "mag": ("mag", _wrap((0.0, MAG_AX, -0.1150), *_UNDER, "L", "bizon_wrap", bm.MAG_R + 0.0010, -0.010)),
    # under the magazine's rear, the thumb reaching back up its left side onto the release behind it
    "mag_release": ("mag", _tip_anchor((-0.0052, -0.0170, 0.0150), "thumb", norm((1.0, 0.25, 0.05)),
                                       norm((-0.30, 0.45, 1.0)), "L", "bizon_wrap")),
    # fresh magazine carried by its middle from below
    "mag_low": ("mag", _wrap((0.0, MAG_AX, -0.1000), *_UNDER, "L", "bizon_wrap", bm.MAG_R + 0.0010, -0.010)),
    # heel of the palm under the magazine's rear (the seating push up), fingers forward
    "slap": ("mag", _anchor((0.0, MAG_AX - bm.MAG_R - 0.0120, -0.0250), (0.0, -0.20, -1.0), (-1.0, 0.0, 0.0), "L",
                            "spread")),
    # reach-under: hand under the receiver, fingers hooked round the charging handle from the front (bolt space)
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([0.004, -0.002, -0.008])), "middle",
                                     norm((0.85, 0.45, -0.25)), norm((0.10, 0.10, -1.0)), "L", "hook")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.trigger = 0.0
        self.selector = SELECTOR_FIRE
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

    def space_quat(self, space):
        return quat_mul(self.gun_q, self.mag_q) if space == "mag" else self.gun_q

    def anchor(self, which):
        space, (w, q, pose) = ANCHORS[which]
        if space == "gun":
            pos = self.gun_point(w)
        elif space == "bolt":
            pos = self.gun_point(np.asarray(w) + np.array([0.0, 0.0, self.bolt_z]))
        else:
            pos = self.mag_point(w)
        return pos, quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

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
