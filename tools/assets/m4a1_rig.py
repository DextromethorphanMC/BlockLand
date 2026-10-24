"""M4A1 viewmodel rig (VIEWMODEL_SPEC sections 3, 16 and 40): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like ak_rig.py, plus the AR controls the hands
work: the magazine catch button (right index), the bolt catch (left thumb), the charging handle (left fingers) and
the bolt carrier held open by the catch after the last round."""
import numpy as np

import armrig
import hands
import m4a1_model as mm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

P0 = mm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.152, -0.153, -0.335])       # the carbine low in the lower right (its in-line stock would
GUN_EULER = (3.5, 4.5, 8.0)                     # fill the corner otherwise), the muzzle right of the crosshair area
                                                # (coverage.py); yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])  # bladed rifle stance: support shoulder forward
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = 15.0
CATCH_PRESS = 7.0                                # bolt catch pad pushed in (deg about Z)
BUTTON_PRESS = 0.0022                            # magazine catch button travel (m, along -X)

STATIC = ("upper", "lower", "handguard", "barrel", "stock", "pistol_grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "charging_handle": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(mm.TRIGGER_PIVOT)),
    "mag_catch": ("gun", G(mm.MAG_CATCH)),
    "bolt_catch": ("gun", G(mm.CATCH_PIN)),
    "sight_iron": ("gun", G((0.0, mm.SIGHT_Y, mm.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, mm.SIGHT_Y, mm.REAR_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, mm.HIDER_Z0))),
    "mag": ("gun", G(mm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, mm.HIDER_Z1 - 0.0030))),
    "ejection": ("gun", G(mm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": mm.TRIGGER_PIVOT, "mag_catch": mm.MAG_CATCH, "bolt_catch": mm.CATCH_PIN,
    "sight_iron": (0.0, mm.SIGHT_Y, mm.FRONT_POST_Z), "sight_rear": (0.0, mm.SIGHT_Y, mm.REAR_Z),
    "muzzle_brake": (0.0, 0.0, mm.HIDER_Z0),
}
# eye point (section 5): on the sight line 75 mm behind the rear aperture (cheek on the stock, nose by the charging
# handle), looking along -Z through the aperture and over the front post
AIM_IRON_BACK = 0.075
AIM_IRON_T = G((0.0, mm.SIGHT_Y, mm.REAR_Z + AIM_IRON_BACK))
EJECT_DIR = norm((0.86, 0.26, -0.16))           # right, a little up and forward off the brass deflector

MOUNTS = {   # name -> (parent, translation, rotation, scale); an attachment's origin goes here (section 19)
    "mount_optic": ("gun", G((0.0, mm.RAIL_TOP, mm.OPTIC_Z)), None, None),               # flat-top rail
    # laser on the clamp-on rail on the right of the handguard: the module hangs along its -Y, turned onto +X
    "mount_laser": ("gun", G(mm.rail_top("right")), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, mm.HIDER_Z0)), None, None),                   # flash hider off
    # receiver extension: a stock attachment's adapter and tube line up with the bore
    "mount_stock": ("gun", G((0.0, 0.0070, 0.1918)), None, None),
    # section 35: the clamp-on rail under the front of the handguard, ahead of the support hand
    "mount_underbarrel": ("gun", G(mm.rail_top("bottom")), None, None),
    # the front sling swivel under the sight base; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((0.0, -0.0270, mm.FSB_Z0 - 0.0095)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


# ---- hand anchors (section 16) ---------------------------------------------------------------------
def _anchor(centre, fwd, thumb, side, pose):
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0)), q, pose


def _wrap(centre, fwd, thumb, side, pose, r, push=0.0):
    q = hands.palm_frame(fwd, thumb, side)
    z = quat_rotate(q, (0.0, 0.0, 1.0))
    return _anchor(np.asarray(centre, float) - hands.wrap_distance(r) * z + push * norm(fwd), fwd, thumb, side, pose)


def _tip_anchor(tip, finger, fwd, thumb, side, pose):
    q = hands.palm_frame(fwd, thumb, side)
    off = hands.fingertips(side, np.zeros(3), q, hands.blend({pose: 1.0}))[finger]
    return np.asarray(tip, float) - off, q, pose


_F, _A = mm.GRIP_FRONT, mm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
HG_C = np.array([0.0, mm.HG_YC - 0.0030, -0.0600])   # handguard section where the support hand sits
GRIP = _wrap(G(mm.grip_center(0.020)), _FR, -_A, "R", "ak_indexed", 0.0345, -0.028)


HEEL = 0.0120                     # palm heel: this far from the wrist along the fingers, on the palm face


def _heel(point, fwd, thumb, side, pose):
    """Anchor putting the heel of the palm (its face, HEEL from the wrist) on `point`."""
    q = hands.palm_frame(fwd, thumb, side)
    return np.asarray(point, float) - quat_rotate(q, (0.0, -HEEL, hands.PALM_T / 2)), q, pose


def _button():
    """The grip hand pitched 10 deg forward (about the gun's X) with its index straight on the magazine catch button:
    of all palm turns and index curls this moves the wrist least from the grip (16 mm, fitted offline)."""
    wrist, q, pose = GRIP
    curls = dict(hands.blend({pose: 1.0}))
    curls["index"] = (5.0, 0.0)
    q = quat_mul(quat_axis_angle((1, 0, 0), 10.0), q)
    tip = hands.fingertips("R", np.zeros(3), q, curls)["index"]
    return G(mm.MAG_CATCH + np.array([0.0028, 0.0, 0.0])) - tip, q, curls


BUTTON = _button()
hands.POSES["m4_button"] = BUTTON[2]
BUTTON = (BUTTON[0], BUTTON[1], "m4_button")
hands.POSES.setdefault("m4_clamp", {"thumb": (-20, -12), "index": (-58, -54), "middle": (-64, -60),
                                    "ring": (-68, -64), "pinky": (-72, -66)})
# the support hand round the magazine well, thumb up on the bolt catch pad
hands.POSES.setdefault("m4_well", {"thumb": (-6, 4), "index": (-52, -46), "middle": (-62, -54), "ring": (-66, -58),
                                   "pinky": (-70, -62)})
CH_GRAB = np.array([-0.0110, mm.CH_Y + 0.0028, mm.UP_Z1 + 0.0004])   # front of the T's left arm (handle space)

ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting along the lower
    "grip": ("gun", GRIP),
    # the same hand, index stretched onto the magazine catch button (the hand rides up a few mm)
    "button": ("gun", BUTTON),
    # support hand under the handguard: palm up, thumb along the left side, fingers up the right side
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.0400, -0.014)),
    # round the magazine below the well (mag space: +Y up the magazine, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, -0.0880, 0.0040), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L", "rifle_mag", 0.0270,
                         0.004)),
    # a fresh magazine held low: palm on its front face, fingers round the right side, thumb up the left side
    "mag_low": ("mag", _wrap((0.0, -0.1050, -0.0100), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "rifle_mag", 0.0480,
                             -0.018)),
    # heel of the palm under the floor plate (the tug / seating tap), fingers forward
    "slap": ("mag", _anchor((0.0, -0.1950, -0.0300), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # the heel of the palm on the bolt catch pad (left side), fingers forward along the receiver
    "catch": ("gun", _heel(G(mm.CATCH_PAD), norm((0.10, 0.35, -1.0)), norm((0.0, 1.0, 0.35)), "L", "relaxed")),
    # fingers hooked round the front of the charging handle's left arm, palm towards the rear (handle space)
    "charging": ("handle", _tip_anchor(G(CH_GRAB), "middle", norm((0.90, -0.20, 0.0)), norm((0.0, 1.0, 0.0)), "L",
                                       "hook")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.ch_z = 0.0
        self.trigger = 0.0
        self.button = 0.0           # magazine catch pressed (0..1)
        self.catch = 0.0            # bolt catch pad pushed (0..1)
        self.mag_t = NODES["mag"][1].copy()
        self.mag_q = np.array([0.0, 0.0, 0.0, 1.0])
        self.mag_s = 1.0
        self.hand_r = None
        self.hand_l = None
        self.fingers_r = None
        self.fingers_l = None
        self.cam_q = np.array([0.0, 0.0, 0.0, 1.0])
        self.pole_r = None
        self.pole_l = None
        self.shoulder_l = np.zeros(3)
        self.shoulder_r = np.zeros(3)

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
        elif space == "handle":
            pos = self.gun_point(np.asarray(w) + np.array([0.0, 0.0, self.ch_z]))
        else:
            pos = self.mag_point(w)
        return pos, quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

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
            "charging_handle": {"translation": NODES["charging_handle"][1] + np.array([0.0, 0.0, self.ch_z])},
            "trigger": {"rotation": rx(self.trigger)},
            "mag_catch": {"translation": NODES["mag_catch"][1] + np.array([-BUTTON_PRESS * self.button, 0.0, 0.0])},
            "bolt_catch": {"rotation": quat_axis_angle((0, 0, 1), -CATCH_PRESS * self.catch)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
