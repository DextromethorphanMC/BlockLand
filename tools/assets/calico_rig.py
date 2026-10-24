"""Calico M950 viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand
anchors and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like ak_rig.py: a two-handed pistol
hold, the shooting hand on the raked grip with the index finger along the frame (in the finger hole when firing), the
support hand under the ribbed forend. The magazine (`mag`, origin on its front face's centre) lies on top of the
receiver: the reloads tilt it about the rear sight bridge and slide it out forward (magazine space anchors below)."""
import numpy as np

import armrig
import calico_model as cm
import hands
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = cm.GRIP_TOP.copy()                          # gun pivot: in the grip, under the web of the hand (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.160, -0.160, -0.380])       # framed for the 54 deg viewmodel FOV (framing.py): turned in so the
GUN_EULER = (22.0, 9.0, 16.0)                    # left flank and the magazine show; yaw (muzzle in), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.285, -0.120])  # support shoulder forward: the hand is out on the forend
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])

TRIGGER_PULL = 15.0
SELECTOR_FIRE = 0.0

STATIC = ("receiver", "forend", "barrel", "lower", "grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "selector": ("gun", G(cm.SELECTOR_PIVOT)),
    "trigger": ("gun", G(cm.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, cm.SIGHT_Y, cm.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, cm.SIGHT_Y, cm.REAR_NOTCH_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, cm.SHOULDER_Z))),
    "mag": ("gun", G(cm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, cm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(cm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "selector": cm.SELECTOR_PIVOT, "trigger": cm.TRIGGER_PIVOT, "sight_iron": (0.0, cm.SIGHT_Y, cm.FRONT_POST_Z),
    "sight_rear": (0.0, cm.SIGHT_Y, cm.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, cm.SHOULDER_Z),
}
# eye point (section 5): on the sight line behind the rear notch on the bridge, far enough back that the 100-round
# magazine's rear cap (13 cm behind the notch, 1.7 cm under the line) stays clear of the eye
AIM_IRON_BACK = 0.165
AIM_IRON_T = G((0.0, cm.SIGHT_Y, cm.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.88, 0.30, 0.10))            # cases leave the port to the right, a little up and back

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, cm.RAIL_TOP, cm.OPTIC_Z)), None, None),       # the bridge rail
    # the laser sits on the forend's top rail: the module hangs along its -Y, so turn it upright
    "mount_laser": ("gun", G((0.0, cm.FORE_TOP + 0.0050, 0.5 * (cm.TOP_RAIL[0] + cm.TOP_RAIL[1]))),
                    quat_axis_angle((0, 0, 1), 180.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, cm.SHOULDER_Z)), None, None),          # the protector unscrewed
    # pistol stocks (section 40, as the P9's): at the bottom of the back strap, raked up to the shoulder, scaled
    "mount_stock": ("gun", G((0.0, -0.0840, 0.1600)), quat_axis_angle((1, 0, 0), -20.0), np.full(3, 0.70)),
    # section 35: the rail under the forend's nose, ahead of the support hand
    "mount_underbarrel": ("gun", G((0.0, cm.UNDER_Y, 0.5 * (cm.LOW_RAIL[0] + cm.LOW_RAIL[1]))), None, None),
    # the sling swivel on the left of the nose; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G(cm.SWIVEL + np.array([-0.0012, 0.0, 0.0])), quat_conj(GUN_Q), None),
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
    """Anchor placing one fingertip (of `pose`) on `tip`: for pressing and pinching small parts."""
    q = hands.palm_frame(fwd, thumb, side)
    off = hands.fingertips(side, np.zeros(3), q, hands.blend({pose: 1.0}))[finger]
    return np.asarray(tip, float) - off, q, pose


hands.POSES.setdefault("calico_grip", {"thumb": (-44, -26), "index": (6, -24), "middle": (-84, -74),
                                       "ring": (-86, -78), "pinky": (-86, -78)})
hands.POSES.setdefault("calico_trigger", {"thumb": (-44, -26), "index": (2, -58), "middle": (-84, -74),
                                          "ring": (-86, -78), "pinky": (-86, -78)})
hands.POSES.setdefault("calico_fore", {"thumb": (-10, -6), "index": (-52, -48), "middle": (-58, -54),
                                       "ring": (-62, -58), "pinky": (-66, -60)})
hands.POSES.setdefault("calico_clamp", {"thumb": (-18, -12), "index": (-60, -56), "middle": (-66, -62),
                                        "ring": (-70, -66), "pinky": (-74, -68)})
hands.POSES.setdefault("calico_mag", {"thumb": (-20, -10), "index": (-40, -34), "middle": (-46, -40),
                                      "ring": (-50, -44), "pinky": (-54, -46)})

_F, _A = cm.GRIP_FRONT, cm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
FORE_C = np.array([0.0, -0.0050, -0.0600])        # forend section centre where the support hand sits
TAB = np.array([-(cm.PLATE_X + 0.0030), 0.5 * (cm.HANDLE_Y0 + cm.HANDLE_Y1), cm.HANDLE_Z0 + 0.0040])
LEVER = np.array([0.0, cm.MAG_R + 0.0026, 0.0090])            # mag space: the release lever on the nose
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index along the frame
    "grip": ("gun", _wrap(G(cm.grip_center(0.004)), _FR, -_A, "R", "calico_grip", 0.033, -0.024)),
    # support hand under the forend: palm up, thumb along the left flank, fingers up the right flank
    "support": ("gun", _wrap(G(FORE_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "calico_fore",
                             0.034, -0.014)),
    # the index fingertip on the release lever on the nose, the hand up the magazine's left flank (mag space)
    "release": ("mag", _tip_anchor(LEVER + np.array([0.0, 0.0008, 0.0010]), "index", norm((0.20, 0.90, -0.40)),
                                   norm((0.0, -0.25, -1.0)), "L", "calico_mag")),
    # palm on the magazine's left flank near its nose, fingers up over the top, thumb forward under it: lifts and
    # carries it (the forearm comes from below, not across the view)
    "mag": ("mag", _wrap(np.array([0.0, 0.0, 0.0560]), norm((0.15, 0.95, -0.20)), norm((0.0, -0.20, -1.0)), "L",
                         "calico_mag", cm.MAG_R + 0.0010, 0.006)),
    # thumb and index pinching the cocking handle's tab, the hand coming up from below (palm towards the gun)
    "charging": ("bolt", _tip_anchor(G(TAB), "index", norm((0.25, 0.55, -0.80)), norm((-0.10, 1.0, 0.25)), "L",
                                     "bolt_pinch")),
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
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
