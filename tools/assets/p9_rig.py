"""P9 viewmodel rig: node layout in gun space, hip pose, hand anchors and pose -> node TRS (with arm IK)."""
import numpy as np

import armrig
import hands
import p9_model as pm
from ik import UPPER_LEN, solve_arm
from vmath import norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = np.array([0.0, -0.0450, 0.0600])          # gun pivot (top of the grip) in the design frame


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.122, -0.060, -0.352])       # framed for the 54 deg viewmodel FOV
GUN_EULER = (5.0, 1.0, 5.0)                     # yaw (inward), pitch, roll (top leans in)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.200, -0.320, 0.050])
SHOULDER_L = np.array([-0.200, -0.320, 0.050])
POLE_R = np.array([1.0, -0.60, -0.10])
POLE_L = np.array([-1.0, -0.60, -0.10])

HAMMER_COCKED = 32.0
STOP_UP = 5.0
SLIDE_LOCK = 0.034

NODES = {   # name: (parent, translation in parent space)
    "frame": ("gun", G((0, 0, 0))),
    "slide": ("gun", G((0, 0, 0))),
    "sight_iron": ("slide", np.array([0.0, pm.SY1, 0.0])),
    "barrel": ("gun", G((0, 0, 0))),
    "hammer": ("gun", G(pm.HAMMER_PIVOT)),
    "trigger": ("gun", G(pm.TRIGGER_PIVOT)),
    "slide_stop": ("gun", G(pm.STOP_PIVOT)),
    "mag": ("gun", G(pm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0, 0, pm.CROWN_Z))),
    "ejection": ("gun", G((pm.SW, 0.0060, 0.0080))),
}
AIM_IRON_T = np.array([0.0, pm.SIGHT_Y, pm.REAR_SIGHT_Z + 0.110])          # slide space
MAG_Q = quat_axis_angle((1, 0, 0), -18.0)       # mag local +Y runs up the grip axis

# attachment mounts (section 19): name -> (parent, translation, rotation, scale); an attachment's origin goes here
OPTIC_BASE = np.array([0.0, 0.0124, 0.0530])     # slide space: the optic cut in front of the rear sight
MOUNTS = {
    "mount_optic": ("slide", OPTIC_BASE, None, None),
    "mount_laser": ("gun", G(pm.LASER_ORIGIN), None, None),                    # under the frame rail
    # section 35: the same short rail holds a grip instead (guns.json: the underbarrel slot excludes the laser)
    "mount_underbarrel": ("gun", G(pm.LASER_ORIGIN), None, None),
    "mount_barrel": ("gun", G((0.0, 0.0, pm.SZ0 - 0.0010)), None, None),      # just in front of the slide
    # stock kit at the bottom of the back strap: rises towards the shoulder (butt near the bore line), pistol-sized
    "mount_stock": ("gun", G((0.0, -0.0985, 0.1010)), quat_axis_angle((1, 0, 0), -28.0), np.full(3, 0.70)),
    # sling loop on the left of the frame's front rail section; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-0.0124, -0.0300, -0.0700)), quat_conj(GUN_Q), None),
}
EJECT_DIR = norm((0.80, 0.50, 0.33))


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    z = np.cross(x, y)
    from vmath import frame_quat
    return frame_quat(x, y, z)


# ---- hand anchors (section 16): wrist position + palm frame in a named space + finger pose ----------
# palm frame: fingers along -Y, palm face +Z, thumb side -X (right) / +X (left); see hands.palm_frame
_C = pm.grip_center(0.035)                         # middle of the grip
_F, _A = pm.FRONT, pm.AXIS                        # grip "forward" (towards the front strap) and grip axis (down)


def _anchor(centre, fwd, thumb, side, pose):
    """Anchor from the palm centre (middle of the palm box) instead of the wrist."""
    q = hands.palm_frame(fwd, thumb, side)
    wrist = np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0))
    return wrist, q, pose


def _wrap(centre, fwd, thumb, side, pose, r, push=0.0):
    """Anchor wrapping a part: palm face `r` from `centre` (towards the part), slid `push` along the fingers."""
    q = hands.palm_frame(fwd, thumb, side)
    z = quat_rotate(q, (0.0, 0.0, 1.0))
    return _anchor(np.asarray(centre, float) - hands.wrap_distance(r) * z + push * norm(fwd), fwd, thumb, side, pose)


_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(5.0)) - _X * np.sin(np.radians(5.0)))         # right palm along the right panel
_FL = norm(_F * np.cos(np.radians(45.0)) + _X * np.sin(np.radians(45.0)) + 0.45 * _A)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right grip panel, knuckles at the front strap, thumb side up the grip axis
    "grip": ("gun", _wrap(G(pm.grip_center(0.031)), _FR, -_A, "R", "indexed", 0.033, -0.024)),
    # support hand: thumbs-forward, palm on the left panel over the right fingers, fingers wrap the front
    "support": ("gun", _wrap(G(pm.grip_center(0.048)), _FL, norm(-_A + 0.5 * _F), "L", "support", 0.040)),
    # support hand holding a fresh magazine (mag space: +Y up the mag, -Z front): palm on its left side
    "mag": ("mag", _wrap((0.0, 0.020, 0.0), (0.0, -0.15, -1.0), (0.0, 1.0, 0.0), "L", "mag", 0.0285, 0.004)),
    # heel of the support palm under the base plate (slap), fingers forward and open
    "slap": ("mag", _anchor((0.0, -0.0245, 0.010), (0.0, 0.0, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # slingshot pinch on the rear serrations (slide space), palm down over the slide
    "pinch_rear": ("slide", _anchor((-0.002, 0.034, 0.066), (1.0, -0.35, 0.0), (0.0, 0.0, 1.0), "L", "pinch")),
    # press-check pinch on the front serrations from the left (slide space), palm facing the slide flank
    "pinch_front": ("slide", _anchor((-0.033, 0.006, -0.066), (0.30, 1.0, 0.0), (0.0, 0.0, 1.0), "L", "pinch")),
}
GRIP_POSE_R = "indexed"


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.slide_z = 0.0
        self.trigger = 0.0
        self.hammer = HAMMER_COCKED
        self.stop = 0.0
        self.mag_t = NODES["mag"][1].copy()
        self.mag_q = MAG_Q.copy()
        self.mag_s = 1.0
        self.hand_r = None          # (wrist, palm quat, curls) in viewmodel space; default = grip anchor
        self.hand_l = None          # default = support anchor
        self.fingers_r = None       # optional finger curls overriding the target's
        self.fingers_l = None
        self.cam_q = quat_identity()
        self.pole_r = None          # optional elbow pole overrides (default POLE_R / POLE_L)
        self.pole_l = None

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def slide_point(self, p):
        return self.gun_point(NODES["slide"][1] + np.array([0, 0, self.slide_z]) + p)

    def mag_point(self, p):
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def space_quat(self, space):
        return quat_mul(self.gun_q, self.mag_q) if space == "mag" else self.gun_q

    def anchor(self, which):
        """World (wrist, palm quat, curls) of a named hand anchor."""
        space, (w, q, pose) = ANCHORS[which]
        if space == "gun":
            pos = self.gun_point(w)
        elif space == "slide":
            pos = self.slide_point(w)
        else:
            pos = self.mag_point(w)
        return pos, quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        """weights: {anchor_name or 'world': w}; world: (wrist, palm quat, curls) in viewmodel space."""
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

    def world_target(self, pos, back, fwd=(0.0, -1.0, 0.0), side="L", pose="relaxed"):
        return np.asarray(pos, float), armrig.palm_from_back(back, fwd, side), hands.blend({pose: 1.0})

    # node TRS -----------------------------------------------------------------------------------
    def solve(self):
        hr = self.hand_r if self.hand_r is not None else self.anchor("grip")
        hl = self.hand_l if self.hand_l is not None else self.anchor("support")
        if self.fingers_r is not None:
            hr = (hr[0], hr[1], self.fingers_r)
        if self.fingers_l is not None:
            hl = (hl[0], hl[1], self.fingers_l)
        tr, ar = armrig.solve_side("R", SHOULDER_R, POLE_R if self.pole_r is None else self.pole_r, hr)
        tl, al = armrig.solve_side("L", SHOULDER_L, POLE_L if self.pole_l is None else self.pole_l, hl)
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "slide": {"translation": NODES["slide"][1] + np.array([0, 0, self.slide_z])},
            "trigger": {"rotation": rx(self.trigger)},
            "hammer": {"rotation": rx(self.hammer)},
            "slide_stop": {"rotation": rx(self.stop)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


def rest_trs():
    trs, _ = Pose().solve()
    return trs


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
