"""Uzi viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like the Thompson's (tommy_rig.py): the
shooting hand round the near-vertical pistol grip, the support hand cupping the ribbed handguard from below; the
magazine rides `mag` straight down out of the grip, the cocking knob (`bolt`) slides back along the top slot."""
import numpy as np

import armrig
import hands
import uzi_model as um
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = um.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.122, -0.122, -0.318])       # framed for the 54 deg viewmodel FOV (coverage.py, framing.py)
GUN_EULER = (2.0, 3.0, 7.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.120, -0.330, -0.040])  # the support hand is close in on the short handguard
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.85, 0.10])

TRIGGER_PULL = 15.0

STATIC = ("receiver", "top_cover", "rail", "barrel", "barrel_nut", "front_ears", "rear_ears", "handguard", "frame",
          "grip", "stock")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(um.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, um.SIGHT_Y, um.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, um.SIGHT_Y, um.REAR_NOTCH_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, um.CROWN_Z))),
    "mag": ("gun", G(um.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, um.MUZZLE_Z - 0.0010))),
    "ejection": ("gun", G(um.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": um.TRIGGER_PIVOT, "sight_iron": (0.0, um.SIGHT_Y, um.FRONT_POST_Z),
    "sight_rear": (0.0, um.SIGHT_Y, um.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, um.CROWN_Z),
}
# eye point (section 5): on the sight line looking along -Z, behind the flip aperture (cheek on the stock arms)
AIM_IRON_BACK = 0.085
AIM_IRON_T = G((0.0, um.SIGHT_Y, um.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()                          # the well runs straight down the grip
EJECT_DIR = norm((0.85, 0.35, -0.12))           # cases go right, up and a little forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale); an attachment's origin goes here
MOUNTS = {
    "mount_optic": ("gun", G((0.0, um.RAIL_TOP, um.OPTIC_Z)), None, None),          # rail adapter on the cover
    # laser on the right of the handguard's front band: the module hangs along the attachment's -Y, turn it onto +X
    "mount_laser": ("gun", G((um.HG_W + 0.0008, -0.0060, 0.0150)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, um.CROWN_Z)), None, None),                  # thread protector off
    # section 35: under the front of the handguard, ahead of the support hand
    "mount_underbarrel": ("gun", G((0.0, um.HG_BOT, 0.0050)), None, None),
    "mount_stock": ("gun", G((0.0, 0.0040, um.REC_Z1 - 0.0005)), None, None),        # replaces the folding stock
    # low on the left of the receiver behind the grip frame; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-um.REC_W - 0.0016, -0.0060, 0.2950)), quat_conj(GUN_Q), None),
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


# support hand cupping the magazine below the grip: fingers round its front, thumb up its left side
hands.POSES.setdefault("uzi_mag", {"thumb": (-22, -10), "index": (-56, -46), "middle": (-62, -52),
                                   "ring": (-66, -56), "pinky": (-70, -60)})
# support hand cupping the ribbed handguard from below
hands.POSES.setdefault("uzi_cup", {"thumb": (-18, -10), "index": (-58, -50), "middle": (-62, -56),
                                   "ring": (-66, -60), "pinky": (-70, -62)})

_X = np.array([1.0, 0.0, 0.0])
_FR = norm(um.GRIP_FRONT * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm axis a little left
HG_C = np.array([0.0, -0.0110, 0.0860])          # handguard section where the support hand sits
KNOB = np.array([0.0, um.KNOB_TOP - 0.0040, um.KNOB_Z])   # on the knob (bolt space at rest)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the frame
    "grip": ("gun", _wrap(G(um.grip_center(0.030)), _FR, -um.GRIP_AXIS, "R", "ak_indexed", 0.0275, -0.020)),
    # support hand under the handguard: palm up, fingers round its right side, thumb along its left
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.25, 0.10)), norm((-0.25, 0.30, -1.0)), "L", "uzi_cup", 0.0330,
                             -0.014)),
    # the magazine below the grip (mag space: +Y up the magazine, -Z front): palm on its left face, fingers round its
    # front, thumb up its left rear towards the heel catch
    "mag": ("mag", _wrap((0.0, -0.1450, 0.0020), (0.10, -0.25, -1.0), (0.0, 1.0, 0.35), "L", "uzi_mag", 0.0125,
                         0.010)),
    # thumb on the heel catch's pad (left, at the grip's bottom behind the magazine), fingers on the magazine front
    "mag_release": ("mag", _tip_anchor(np.array([-0.0205, um.GRIP_BOTTOM_LOCAL - 0.0035, 0.0100]), "thumb",
                                       norm((0.20, -0.45, -1.0)), norm((0.0, 0.85, 0.50)), "L", "uzi_mag")),
    # over the top: fingertips hooked round the front of the cocking knob from the left (bolt space)
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([-0.0040, -0.0010, -0.0100])), "index",
                                     norm((0.55, 0.45, -0.70)), norm((0.0, 0.30, 1.0)), "L", "hook")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.trigger = 0.0
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
        self.shoulder_l = np.zeros(3)   # support shoulder offset (it drops back while the hand works low)
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
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
            "trigger": {"rotation": quat_axis_angle((1, 0, 0), self.trigger)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
