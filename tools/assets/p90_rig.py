"""P90 viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19, 35 and 40): node layout in gun space, hip pose, hand anchors
and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like ak_rig.py: the shooting hand on the
thumbhole grip (fingers through the trigger guard opening, the thumb through the thumbhole), the support hand under
the forend behind the hand stop; the reload works the magazine lying on top of the receiver."""
import numpy as np

import armrig
import hands
import p90_model as pm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = pm.GRIP_TOP.copy()                          # gun pivot: top of the grip, between the thumbhole and the trigger


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.150, -0.148, -0.390])       # the short bullpup sits close: the housing right of the crosshair
GUN_EULER = (14.0, 3.0, -2.0)                    # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = -15.0                             # the blade swings back about its top pivot
SELECTOR_AUTO = 0.0

STATIC = ("butt", "receiver", "frame", "forend", "housing", "barrel")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(pm.TRIGGER_PIVOT)),
    "selector": ("gun", G(pm.SELECTOR_PIVOT)),
    "sight_rear": ("gun", G((0.0, pm.SIGHT_Y, pm.REAR_SIGHT_Z))),
    "sight_iron": ("gun", G((0.0, pm.SIGHT_Y, pm.FRONT_SIGHT_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, pm.FH_Z1))),
    "mag": ("gun", G(pm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, pm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(pm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": pm.TRIGGER_PIVOT,
    "sight_rear": (0.0, pm.SIGHT_Y, pm.REAR_SIGHT_Z), "sight_iron": (0.0, pm.SIGHT_Y, pm.FRONT_SIGHT_Z),
    "muzzle_brake": (0.0, 0.0, pm.FH_Z1),
}
# eye point (section 5): through the folding irons on the top rail, on the bore's centreline, the cheek on the butt
# behind the magazine
AIM_IRON_BACK = 0.175
AIM_IRON_T = G((0.0, pm.SIGHT_Y, pm.REAR_SIGHT_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
MAG_LIP = np.array([0.0, pm.MAG_H, 0.0])         # mag-local: the front of the top face, the hook under the latch
EJECT_DIR = norm(pm.EJECT_DIR)                   # down through the butt's floor

MOUNTS = {   # name -> (parent, translation, rotation, scale); an attachment's origin goes here
    "mount_optic": ("gun", G((0.0, pm.RAIL_TOP, pm.OPTIC_Z)), None, None),               # the housing's top rail
    # laser on the right side rail: the module hangs along the attachment's -Y, turned onto +X
    "mount_laser": ("gun", G((pm.SIDE_RAIL_X, pm.SIDE_RAIL_Y, pm.LASER_Z)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, pm.CROWN_Z)), None, None),                      # flash hider off
    # section 35: the short rail under the hand stop, ahead of the support hand
    "mount_underbarrel": ("gun", G((0.0, pm.LIP_BOTTOM - 0.0057, pm.UNDER_Z)), None, None),
    # the QD sling loop on the left of the receiver, behind the grip; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-pm.BAND_W - 0.0080, -0.0180, 0.2550)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross(x, (1.0, 0, 0)))
    y = y if y[2] < 0 else -y
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


hands.POSES.setdefault("p90_grip", {"thumb": (-52, -34), "index": (-22, -40), "middle": (-82, -74),
                                    "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("p90_trigger", {"thumb": (-52, -34), "index": (-34, -58), "middle": (-82, -74),
                                       "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("p90_fore", {"thumb": (-18, -10), "index": (-54, -50), "middle": (-60, -56),
                                    "ring": (-64, -60), "pinky": (-68, -62)})
hands.POSES.setdefault("p90_mag", {"thumb": (-30, -18), "index": (-36, -30), "middle": (-44, -36),
                                   "ring": (-50, -40), "pinky": (-56, -44)})

_F, _A = pm.GRIP_FRONT, pm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))
FORE_C = np.array([0.0, -0.0200, 0.0400])        # forend section where the support hand sits
KNOB_L = np.array([-0.5 * (pm.KNOB_X0 + pm.KNOB_X1), -0.0040, 0.0330])    # left cocking knob (bolt space at rest)
H = pm.MAG_H
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, fingers round its front through the guard opening, the thumb
    # through the thumbhole behind it
    "grip": ("gun", _wrap(G(pm.grip_center(0.032)), _FR, -_A, "R", "p90_grip", 0.036, -0.020)),
    # support hand under the forend behind the hand stop: palm up, thumb along the left side, fingers up the right
    "support": ("gun", _wrap(G(FORE_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "p90_fore",
                             0.036, -0.012)),
    # the rear half of the magazine taken from the left: palm on its left side, fingers up and over its top, the
    # thumb back on the left catch (mag space: origin at the front of the bottom face, +Z rearward)
    "mag_side": ("mag", _anchor((-pm.MAG_HW - 0.0150, 0.45 * H, 0.1800), norm((0.25, 1.0, -0.12)), (0.0, 0.10, 1.0),
                                "L", "p90_mag")),
    # reach-under: fingertip hooked behind the left cocking knob (bolt space)
    "charging": ("bolt", _tip_anchor(G(KNOB_L + np.array([-0.0020, -0.0010, 0.0080])), "middle",
                                     norm((0.30, 0.25, -1.0)), norm((0.0, 1.0, 0.1)), "L", "hook")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.trigger = 0.0
        self.selector = SELECTOR_AUTO
        self.mag_t = NODES["mag"][1].copy()
        self.mag_q = MAG_Q.copy()
        self.mag_s = 1.0
        self.hand_r = None
        self.hand_l = None
        self.fingers_r = None
        self.fingers_l = None
        self.cam_q = quat_identity()
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
        elif space == "bolt":
            pos = self.gun_point(np.asarray(w) + np.array([0.0, 0.0, self.bolt_z]))
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
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
            "trigger": {"rotation": quat_axis_angle((1, 0, 0), self.trigger)},
            "selector": {"rotation": quat_axis_angle((0, 1, 0), self.selector)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
