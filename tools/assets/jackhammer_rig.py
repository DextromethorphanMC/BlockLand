"""Jackhammer viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand
anchors and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like the KS-3 / RPD rigs. The
support hand rides the operating sleeve (`bolt`, anchors in bolt space follow its cocking stroke); the cassette
(`mag`) turns about its own axis — one chamber (36 degrees) per shot — and is pulled down out of its bay behind the
grip in the reloads."""
import numpy as np

import armrig
import hands
import jackhammer_model as jm
from ak_rig import _anchor, _tip_anchor, _wrap
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = jm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.180, -0.150, -0.400])       # the bullpup's grip sits further out: the receiver and handle run
GUN_EULER = (-4.0, 4.0, 5.0)                    # off the right edge, the cassette shows at the bottom (yaw, pitch, roll)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.175, -0.350, 0.140])        # forward: the bullpup grip is held out further
SHOULDER_L = np.array([-0.130, -0.290, -0.190])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = 16.0
LATCH_DOWN = 22.0                                # the latch lever pressed (about +X: its tail swings back)

STATIC = ("receiver", "handle", "stock", "lower", "barrel")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(jm.TRIGGER_PIVOT)),
    "latch": ("gun", G(jm.LATCH_PIVOT)),
    "sight_iron": ("gun", G((0.0, jm.SIGHT_Y, jm.FRONT_SIGHT_Z))),
    "sight_rear": ("gun", G((0.0, jm.SIGHT_Y, jm.REAR_SIGHT_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, jm.CROWN_Z))),
    "mag": ("gun", G(jm.CAS_C)),
    "muzzle": ("gun", G((0.0, 0.0, jm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(jm.EJECTION)),
}
MESH_ORIGIN = {
    "trigger": jm.TRIGGER_PIVOT, "latch": jm.LATCH_PIVOT, "sight_iron": (0.0, jm.SIGHT_Y, jm.FRONT_SIGHT_Z),
    "sight_rear": (0.0, jm.SIGHT_Y, jm.REAR_SIGHT_Z), "muzzle_brake": (0.0, 0.0, jm.CROWN_Z),
}
# eye point (section 5): on the sight line, the cheek on the hood over the cassette (a bullpup: the eye sits far back)
AIM_IRON_BACK = 0.150
AIM_IRON_T = G((0.0, jm.SIGHT_Y, jm.REAR_SIGHT_Z + AIM_IRON_BACK))
MAG_REST = NODES["mag"][1]
EJECT_DIR = norm((0.80, 0.40, 0.10))

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, jm.RAIL_TOP, jm.OPTIC_Z)), None, None),             # handle top rail
    # laser on the right of the forend's front (bolt space = the design frame), the module hangs -Y -> +X
    "mount_laser": ("bolt", jm.LASER_POINT, quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, jm.CROWN_Z)), None, None),
    # section 35: under the forend's front, riding the sleeve's stroke, ahead of the support hand
    "mount_underbarrel": ("bolt", jm.UNDER_POINT, None, None),
    # low on the left of the lower receiver just ahead of the bay; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G(jm.CHARM_POINT), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


# ---- hand anchors (section 16) ---------------------------------------------------------------------
_F, _A = jm.GRIP_FRONT, jm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
SUPPORT_C = np.array([0.0, -0.0190, -0.2680])    # bolt space (design frame): the support hand round the forend
_MAG_R = jm.CAS_R
_D = norm((1.0, 1.0, 0.0))
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the pistol grip, knuckles round the front, index along the guard
    "grip": ("gun", _wrap(G(jm.grip_center(0.030)), _FR, -_A, "R", "ak_indexed", 0.0350, -0.030)),
    # support hand round the operating sleeve: palm under it, thumb along the left side, fingers up the right side
    "support": ("bolt", _wrap(SUPPORT_C, norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                              0.0420, -0.016)),
    # palm under the cassette, fingers curled up its far (right) side, thumb on its near face towards the front
    # (mag space): the camera keeps seeing the cassette's left face and its cam groove
    "cassette": ("mag", _wrap(np.array([0.0, 0.0, -0.0060]), norm((1.0, 0.15, 0.0)), (0.0, 0.0, -1.0), "L",
                              "rifle_mag", _MAG_R + 0.0050, -0.0180)),
    # thumb tip on the latch's tail, the fingers spread on the cassette below it (gun space)
    "latch": ("gun", _tip_anchor(G(jm.LATCH_PIVOT + np.array([-0.0040, -0.0130, 0.0010])), "thumb",
                                 norm((0.55, -0.70, 0.45)), norm((0.10, 0.45, -1.0)), "L", "spread")),
    # palm flat under the cassette's bottom, fingers forward (mag space): the seating push
    "seat": ("mag", _anchor(np.array([-0.0040, -_MAG_R - 0.0170, 0.0060]), norm((0.15, 0.0, -1.0)),
                            (-1.0, 0.0, 0.0), "L", "relaxed")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.trigger = 0.0
        self.latch = 0.0
        self.mag_t = MAG_REST.copy()
        self.mag_q = quat_identity()
        self.mag_s = 1.0
        self.index = 0.0            # cassette turn about its axis (degrees, negative = one chamber on)
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

    def mag_quat(self):
        return quat_mul(self.mag_q, quat_axis_angle((0, 0, 1), self.index))

    def space_point(self, space, p):
        if space == "gun":
            return self.gun_point(p)
        if space == "bolt":
            return self.gun_point(NODES["bolt"][1] + np.asarray(p, float) + np.array([0.0, 0.0, self.bolt_z]))
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def space_quat(self, space):
        return quat_mul(self.gun_q, self.mag_q) if space == "mag" else self.gun_q

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
            "trigger": {"rotation": rx(self.trigger)},
            "latch": {"rotation": rx(self.latch)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_quat(), "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
