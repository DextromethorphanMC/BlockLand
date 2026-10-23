"""AK-47 viewmodel rig (VIEWMODEL_SPEC sections 3, 16 and 18): node layout in gun space, hip pose, hand anchors
and pose -> node TRS (two-bone arm IK to palm frames, finger curls)."""
import numpy as np

import ak_model as am
import armrig
import hands
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = am.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.134, -0.126, -0.330])       # framed for the 54 deg viewmodel FOV: the muzzle stays out of the
GUN_EULER = (2.5, 3.0, 7.0)                     # crosshair area (coverage.py); yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])  # rifle stance: support shoulder forward (bladed stance)
POLE_R = np.array([0.5, 0.50, 0.70])               # elbow out and back (rifle hold)
POLE_L = np.array([-1.0, -0.75, 0.10])

SELECTOR_FIRE = -12.0                            # lever down to full auto
TRIGGER_PULL = 16.0

STATIC = ("receiver", "dust_cover", "handguard", "gas_tube", "barrel", "front_sight", "stock", "pistol_grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "selector": ("gun", G(am.SELECTOR_PIVOT)),
    "trigger": ("gun", G(am.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, am.SIGHT_Y, am.REAR_NOTCH_Z))),
    "sight_rear": ("gun", G((0.0, am.SIGHT_Y, am.REAR_NOTCH_Z))),
    "front_sight_ears": ("gun", G((0.0, am.SIGHT_Y, am.FRONT_POST_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, am.CROWN_Z))),
    "mag": ("gun", G(am.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, am.MUZZLE_Z - 0.0045))),
    "ejection": ("gun", G(am.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "selector": am.SELECTOR_PIVOT, "trigger": am.TRIGGER_PIVOT, "sight_iron": (0.0, am.SIGHT_Y, am.REAR_NOTCH_Z),
    "sight_rear": (0.0, am.SIGHT_Y, am.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, am.CROWN_Z),
    "front_sight_ears": (0.0, am.SIGHT_Y, am.FRONT_POST_Z),
}
# eye point (section 5): on the sight line, looking along -Z. The AK's rear sight sits at the front of the
# receiver, so the iron eye point is where a cheek weld on the stock comb puts the eye (0.25 m behind the rear
# notch; 0.11 m would put the eye right over the dust cover). Optics bring their own `aim` (section 19).
AIM_IRON_BACK = 0.250
AIM_IRON_T = G((0.0, am.SIGHT_Y, am.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.82, 0.38, -0.30))           # AK: shells go right, a little up and forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale); an attachment's origin goes here
MOUNTS = {
    "mount_optic": ("gun", G((0.0, am.COVER_TOP + am.RAIL_H, am.OPTIC_Z)), None, None),     # on the cover rail
    # laser on the right of the lower handguard: the module hangs along the attachment's -Y, so turn it onto +X
    "mount_laser": ("gun", G((am.HG_SIDE_X, -0.0060, -0.1500)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, am.CROWN_Z)), None, None),                      # brake unscrewed
    "mount_stock": ("gun", G((0.0, -0.0120, am.REC_Z1 - 0.0005)), None, None),            # replaces the wood stock
    # section 35: under the lower handguard near its front, ahead of the support hand (which stays on the handguard)
    "mount_underbarrel": ("gun", G((0.0, -0.0315, -0.1780)), None, None),
    # sling loop low on the left of the receiver behind the grip; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-am.REC_W - 0.0016, -0.0300, 0.2050)), quat_conj(GUN_Q), None),
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


_F, _A = am.GRIP_FRONT, am.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
HG_C = np.array([0.0, -0.0105, -0.1080])          # handguard section centre where the support hand sits
KNOB = np.array([am.REC_W + 0.0150, 0.0060, am.HANDLE_Z + 0.0010])   # charging handle knob (bolt space at rest)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the guard
    "grip": ("gun", _wrap(G(am.grip_center(0.018)), _FR, -_A, "R", "ak_indexed", 0.031, -0.027)),
    # support hand under the lower handguard: palm up, thumb along the left side, fingers up the right side
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.036, -0.016)),
    # support hand round the magazine (mag space: +Y up the mag, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, -0.045, 0.004), (0.0, -0.20, -1.0), (0.0, 1.0, 0.25), "L", "rifle_mag", 0.0300,
                         0.004)),
    # from below: palm on the magazine's left side, fingers up along it, thumb tip on the release paddle behind it
    "mag_release": ("mag", _tip_anchor((-0.004, -0.010, 0.036), "thumb", norm((0.0, 0.50, -0.85)),
                                       norm((0.0, 0.40, 1.0)), "L", "rifle_mag")),
    # fresh magazine held low: palm on its front face, fingers round the right side, thumb up the left side
    # (keeps the magazine well and the top of the magazine in view while it is inserted)
    "mag_low": ("mag", _wrap((0.0, -0.112, -0.009), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "rifle_mag", 0.0510,
                             -0.020)),
    # heel of the palm under the floor plate (seating tap), fingers forward
    "slap": ("mag", _anchor((0.0, -0.190, -0.050), (0.0, -0.35, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
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
        # the gun hand follows its grip exactly (authored within range); the support hand is range-limited
        tr, ar = armrig.solve_side("R", sr, POLE_R if self.pole_r is None else self.pole_r, hr)
        tl, al = armrig.solve_side("L", sl, POLE_L if self.pole_l is None else self.pole_l, hl, limit=True)
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
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
