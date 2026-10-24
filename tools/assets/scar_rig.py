"""SCAR-H viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand anchors
and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like ak_rig.py; the SCAR adds the bolt
catch (the carrier locks open on the last round), the charging handle on the LEFT riding on the carrier (`bolt`), and
the straight-insertion magazine (no rocking on a front lug: it goes straight up the flared well)."""
import numpy as np

import armrig
import hands
import scar_model as sm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = sm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ----------------------------------------------------------------------------------------------
GUN_T = np.array([0.140, -0.136, -0.322])        # the long upper ends right of the crosshair area (coverage.py)
GUN_EULER = (1.6, 3.0, 7.0)                      # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])

SELECTOR_FIRE = -95.0                            # lever turned down from safe (forward) to auto
TRIGGER_PULL = 15.0
CATCH_UP = 6.0                                   # bolt catch raised into the carrier's path (hold-open), about X

STATIC = ("receiver", "lower", "barrel", "pistol_grip", "stock")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "bolt_catch": ("gun", G(sm.BOLT_CATCH_PIVOT)),
    "selector": ("gun", G(sm.SELECTOR_PIVOT)),
    "trigger": ("gun", G(sm.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, sm.SIGHT_Y, sm.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, sm.SIGHT_Y, sm.REAR_SIGHT_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, sm.CROWN_Z))),
    "mag": ("gun", G(sm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, sm.MUZZLE_Z - 0.0030))),
    "ejection": ("gun", G(sm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "bolt_catch": sm.BOLT_CATCH_PIVOT, "selector": sm.SELECTOR_PIVOT, "trigger": sm.TRIGGER_PIVOT,
    "sight_iron": (0.0, sm.SIGHT_Y, sm.FRONT_POST_Z), "sight_rear": (0.0, sm.SIGHT_Y, sm.REAR_SIGHT_Z),
    "muzzle_brake": (0.0, 0.0, sm.CROWN_Z),
}
# eye point (section 5): an aperture rear sight is used close to the eye (cheek on the stock's riser)
AIM_IRON_BACK = 0.100
AIM_IRON_T = G((0.0, sm.SIGHT_Y, sm.REAR_SIGHT_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.80, 0.22, -0.42))            # the deflector throws the cases right and well forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, sm.RAIL_TOP, sm.OPTIC_Z)), None, None),               # top rail, receiver section
    # laser on the right side rail: the module hangs along the attachment's -Y, turned onto +X
    "mount_laser": ("gun", G((sm.WALL_X + sm.RAIL_H - 0.0003, 0.0005, -0.2500)), quat_axis_angle((0, 0, 1), 90.0),
                    None),
    "mount_barrel": ("gun", G((0.0, 0.0, sm.CROWN_Z)), None, None),                     # flash hider off
    "mount_stock": ("gun", G((0.0, -0.0040, sm.UP_Z1 + 0.0080)), None, None),          # on the hinge block
    # section 35: on the bottom rail near its front, ahead of the support hand
    "mount_underbarrel": ("gun", G((0.0, sm.HG_Y0 - sm.RAIL_H + 0.0003, -0.2520)), None, None),
    # sling point on the left of the lower's tail; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-sm.LOW_X - 0.0036, -0.0330, 0.2120)), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


# ---- hand anchors (section 16) -------------------------------------------------------------------------------------
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
    """Anchor placing one fingertip (of `pose`) on `tip`: for pressing and hooking small parts."""
    q = hands.palm_frame(fwd, thumb, side)
    off = hands.fingertips(side, np.zeros(3), q, hands.blend({pose: 1.0}))[finger]
    return np.asarray(tip, float) - off, q, pose


hands.POSES.setdefault("scar_clamp", {"thumb": (-20, -12), "index": (-58, -54), "middle": (-64, -60),
                                      "ring": (-68, -64), "pinky": (-72, -66)})
# left thumb pressing a button on the left flank while the fingers wrap the magazine or the well
hands.POSES.setdefault("scar_thumb", {"thumb": (-4, 10), "index": (-52, -46), "middle": (-60, -52),
                                      "ring": (-66, -58), "pinky": (-72, -62)})
# fingertips pinching the charging handle's paddle from the front
hands.POSES.setdefault("scar_pinch", {"thumb": (-30, -16), "index": (-62, -58), "middle": (-70, -66),
                                      "ring": (-82, -80), "pinky": (-86, -82)})

_F, _A = sm.GRIP_FRONT, sm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
HG_C = np.array([0.0, -0.0080, -0.1620])          # handguard section centre where the support hand sits
KNOB = np.array([-sm.WALL_X - 0.0150, sm.HANDLE_Y + 0.0010, sm.HANDLE_Z])   # charging paddle (bolt space at rest)
REL_L = sm.MAG_RELEASE * np.array([-1.0, 1.0, 1.0]) - sm.MAG_ORIGIN         # left release button (mag space)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting along the lower
    "grip": ("gun", _wrap(G(sm.grip_center(0.020)), _FR, -_A, "R", "ak_indexed", 0.031, -0.027)),
    # support hand under the handguard (on the bottom rail): palm up, thumb along the left, fingers up the right
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.038, -0.014)),
    # round the magazine under the well: palm on its left side, fingers forward round the front, thumb up the back
    "mag": ("mag", _wrap((0.0, -0.088, -0.004), (0.0, -0.25, -1.0), (0.0, 1.0, 0.30), "L", "rifle_mag", 0.0300,
                         0.004)),
    # the same grasp higher, the thumb tip on the left magazine release button behind the well
    "mag_release": ("mag", _tip_anchor(REL_L + np.array([-0.0030, -0.0005, 0.0010]), "thumb", norm((0.0, 0.40, -1.0)),
                                       norm((0.0, 0.55, 1.0)), "L", "scar_thumb")),
    # fresh magazine held low by its lower half: palm on the front, fingers round the right side, thumb up the left
    "mag_low": ("mag", _wrap((0.0, -0.118, -0.006), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "rifle_mag",
                             sm.MAG_HD + 0.0080, -0.020)),
    # heel of the palm under the floor plate (the tug / tap that checks the seat)
    "slap": ("mag", _anchor((0.0, -0.182, -0.040), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # palm on the left of the magazine well, the thumb tip on the top of the bolt catch paddle
    "catch": ("gun", _tip_anchor(G(sm.BOLT_CATCH_PIVOT + np.array([-0.0030, 0.0068, -0.0060])), "thumb",
                                 norm((0.15, 0.55, -1.0)), norm((0.0, 0.55, 1.0)), "L", "scar_thumb")),
    # index and middle tips hooked round the front of the charging paddle (bolt space), knuckles to the left
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([-0.0020, -0.0010, -0.0100])), "middle",
                                     norm((0.55, 0.15, 0.30)), norm((0.10, 1.0, 0.10)), "L", "scar_pinch")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.catch = 0.0
        self.trigger = 0.0
        self.selector = SELECTOR_FIRE
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

    # space conversions --------------------------------------------------------------------------------------------
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

    # node TRS -----------------------------------------------------------------------------------------------------
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
            "bolt_catch": {"rotation": rx(self.catch)},
            "trigger": {"rotation": rx(self.trigger)},
            "selector": {"rotation": rx(self.selector)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
