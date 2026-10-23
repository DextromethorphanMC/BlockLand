"""AWP viewmodel rig (VIEWMODEL_SPEC sections 3, 16 and 23): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like the AK rig (ak_rig.py); the bolt is
split into `bolt` (slides along the bore, carries the non-rotating shroud) and `bolt_handle` under it (turns about
the bore to unlock: body, lugs, handle and knob)."""
import numpy as np

import armrig
import awp_model as am
import hands
from ak_rig import _anchor, _tip_anchor, _wrap
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = am.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.150, -0.165, -0.345])       # framed for the 54 deg viewmodel FOV: lower and further right than
GUN_EULER = (-3.5, 1.0, 4.0)                    # the AK, the long barrel parallel-ish to the view so the muzzle ends
GUN_Q = quat_euler(*GUN_EULER)                  # right of the crosshair zone; the scope rides just below eye level
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.120, -0.275, -0.200])  # support shoulder well forward: the forend is far out
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])

TRIGGER_PULL = 14.0

STATIC = ("receiver", "barrel", "stock", "grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "bolt_handle": ("bolt", np.zeros(3)),
    "trigger": ("gun", G(am.TRIGGER_PIVOT)),
    "muzzle_brake": ("gun", G((0.0, 0.0, am.CROWN_Z))),
    "mag": ("gun", G(am.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, am.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(am.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "trigger": am.TRIGGER_PIVOT, "muzzle_brake": (0.0, 0.0, am.CROWN_Z),
}
MAG_Q = quat_identity()
EJECT_DIR = norm((0.88, 0.42, 0.10))            # .338 cases fly right, up and a little back

# attachment mounts (section 19): name -> (parent, translation, rotation, scale); no laser / stock slot on the AWP
MOUNTS = {
    "mount_optic": ("gun", G((0.0, am.REC_Y1 + am.RAIL_H, am.OPTIC_Z)), None, None),     # on the receiver rail
    "mount_barrel": ("gun", G((0.0, 0.0, am.CROWN_Z)), None, None),                      # brake unscrewed
    # section 35: the accessory rail under the front of the forend (the bipod)
    "mount_underbarrel": ("gun", G((0.0, am.FE_Y0 - 0.0063, am.FE_Z0 + 0.0750)), None, None),
    # sling loop on the left of the chassis behind the magazine; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G(am.CHARM_POINT), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


def bolt_quat(deg):
    """Bolt handle turned up by `deg` (about +Z: the right side rises)."""
    return quat_axis_angle((0, 0, 1), deg)


# ---- hand anchors (section 16) ---------------------------------------------------------------------
_F, _A = am.GRIP_FRONT, am.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
FE_C = np.array([0.0, (am.FE_Y0 + am.FE_Y1) / 2, -0.0700])   # forend section centre where the support hand sits
KNOB = am.knob_rest()
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the thumbhole grip, knuckles round the front strap, index on the guard
    "grip": ("gun", _wrap(G(am.grip_center(0.020)), _FR, -_A, "R", "ak_indexed", 0.0330, -0.027)),
    # support hand under the forend: palm up, thumb along the left side, fingers up the right side
    "support": ("gun", _wrap(G(FE_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.0430, -0.018)),
    # support hand round the magazine (mag space: +Y up the mag, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, -0.060, 0.000), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L", "rifle_mag", 0.0320,
                         0.004)),
    # thumb on the release paddle in front of the guard, fingers along the magazine's left side (mag space)
    "mag_release": ("mag", _tip_anchor((0.0, -0.0560, am.RELEASE_Z - am.MAG_ORIGIN[2] - 0.0010), "thumb",
                                       norm((0.0, 0.45, -0.89)), norm((0.0, 0.40, 1.0)), "L", "rifle_mag")),
    # fresh magazine held low: palm on its front face, fingers round the right side, thumb up the left side
    "mag_low": ("mag", _wrap((0.0, -0.070, -0.028), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "rifle_mag", 0.0480,
                             -0.020)),
    # heel of the palm under the floor plate (seating tap), fingers forward
    "slap": ("mag", _anchor((0.0, -am.MAG_H - 0.050, -0.030), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # shooting hand on the bolt knob (bolt-handle space, turns and slides with it): the hand right of the ball,
    # index and middle hooked round its front, thumb on top behind it
    "bolt": ("bolt", _tip_anchor(G(KNOB + np.array([-0.0020, -0.0010, -0.0090])), "index",
                                 norm((-0.62, -0.30, -0.72)), norm((-0.05, 0.75, 0.66)), "R", "bolt_pinch")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.bolt_rot = 0.0
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
        self.shoulder_l = np.zeros(3)
        self.shoulder_r = np.zeros(3)

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def mag_point(self, p):
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def bolt_point(self, p):
        """Bolt-handle space (gun space of the closed bolt) -> viewmodel space."""
        q = bolt_quat(self.bolt_rot)
        bolt_origin = NODES["bolt"][1]
        return self.gun_point(bolt_origin + quat_rotate(q, np.asarray(p, float) - bolt_origin)
                              + np.array([0.0, 0.0, self.bolt_z]))

    def space_quat(self, space):
        if space == "mag":
            return quat_mul(self.gun_q, self.mag_q)
        if space == "bolt":
            return quat_mul(self.gun_q, bolt_quat(self.bolt_rot))
        return self.gun_q

    def anchor(self, which):
        space, (w, q, pose) = ANCHORS[which]
        if space == "gun":
            pos = self.gun_point(w)
        elif space == "bolt":
            pos = self.bolt_point(w)
        else:
            pos = self.mag_point(w)
        return pos, quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

    def knob(self):
        """Viewmodel-space centre of the bolt knob."""
        return self.bolt_point(G(KNOB))

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
            "bolt_handle": {"rotation": bolt_quat(self.bolt_rot)},
            "trigger": {"rotation": rx(self.trigger)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
