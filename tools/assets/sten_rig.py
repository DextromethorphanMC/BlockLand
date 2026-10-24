"""STEN Mk II viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 19 and 40): node layout in gun space, hip pose, hand
anchors and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like ak_rig.py / rpd_rig.py.

The shooting hand holds the wooden pistol grip (Mk V pattern) behind the trigger guard, the index finger forward on
the trigger; the support hand holds the magazine housing from below (palm up,
fingers curled round its front face, never on the magazine). The magazine sticks out horizontally to the left and
leaves along -X. The bolt is an open bolt: at rest it is held back on the sear (cocked, the handle at the rear of its
slot); a shot drives it forward and blows it back; after the last shot it stays forward (bolt_z = -BOLT_TRAVEL, the
slide-lock offset); the empty reload racks the handle back. The bolt may also turn a little about the bore (the
handle up into the safety notch, inspect)."""
import functools

import numpy as np

import armrig
import hands
import sten_model as sm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = np.array([0.0, -0.0200, 0.3350])           # gun pivot: the small of the stock behind the trigger (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.132, -0.124, -0.335])
GUN_EULER = (3.0, 3.0, 5.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = -15.0                             # the blade's foot swings back

STATIC = ("receiver", "barrel", "trigger_housing")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "stock": ("gun", G((0, 0, 0))),
    "bolt": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(sm.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, sm.SIGHT_Y, sm.FRONT_Z))),
    "sight_rear": ("gun", G((0.0, sm.SIGHT_Y, sm.REAR_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, sm.CROWN_Z))),
    "mag": ("gun", G(sm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, sm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(sm.EJECTION)),
}
MESH_ORIGIN = {
    "trigger": sm.TRIGGER_PIVOT, "sight_iron": (0.0, sm.SIGHT_Y, sm.FRONT_Z), "sight_rear": (0.0, sm.SIGHT_Y, sm.REAR_Z),
    "muzzle_brake": (0.0, 0.0, sm.CROWN_Z),
}
# eye point (section 5): behind the peep with the cheek on the stock tube
AIM_IRON_BACK = 0.0780
AIM_IRON_T = G((0.0, sm.SIGHT_Y, sm.REAR_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.86, 0.34, 0.08))            # out of the right-hand port, a little up and back

MOUNTS = {   # section 19: name -> (parent, translation, rotation, scale)
    "mount_optic": ("gun", G((0.0, sm.RAIL_TOP, sm.OPTIC_Z)), None, None),               # clamp rail on the tube
    "mount_laser": ("gun", G((sm.SLEEVE_SIDE_X, 0.0, -0.0880)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, sm.CROWN_Z)), None, None),                       # collar off
    "mount_stock": ("gun", G((0.0, -0.0075, sm.CAP_Z1 - 0.0005)), None, None),           # the end cap's socket
    # sling loop on the left rear of the tube; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G(sm.SLING_L + np.array([-0.0085, -0.0015, 0.0])), quat_conj(GUN_Q), None),
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


SHOULDER_R_REACH = np.array([-0.020, 0.040, -0.100])   # the gun arm reaching forward to the cocking knob
POLE_R_REACH = np.array([0.6, 0.2, 0.8])

# the shooting hand: palm on the right of the pistol grip, knuckles round its front, the thumb over the left side
_X = np.array([1.0, 0.0, 0.0])
GRIP_C = sm.grip_center(0.0330)
GRIP_FWD = norm(sm.GRIP_FRONT * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm axis a little left
GRIP_THUMB = -sm.GRIP_AXIS
TRIGGER_FACE = np.array([0.0, -0.0640, sm.TRIGGER_PIVOT[2] - 0.0035])       # the pad of the index on the blade
HOUSING_C = np.array([-0.0540, 0.0, sm.MAG_ZC])                            # support hand: under the housing
KNOB = np.array([sm.R_OUT + 0.0150, 0.0, sm.HANDLE_Z])                     # cocking knob (bolt space at rest)
MAG_HOLD_X = -0.0950                                                       # mag-local x of the hand on the magazine


def _grip():
    return _wrap(G(GRIP_C), GRIP_FWD, GRIP_THUMB, "R", "sten_grip", 0.0275, -0.0200)


@functools.lru_cache(maxsize=None)
def trigger_curls():
    """Index curls that put its pad on the trigger blade (fire), straightened off it (indexed), and on the blade
    pulled back (TRIGGER_PULL)."""
    wrist, q, _ = _grip()
    base = hands.blend({"sten_grip": 1.0})
    _, on = hands.fit_finger("R", wrist, q, base, "index", G(TRIGGER_FACE), step=2)
    off = (on[0] - 12.0, on[1] + 26.0)                     # straightened along the housing, off the trigger
    rel = TRIGGER_FACE - sm.TRIGGER_PIVOT                  # the pad rides the blade back as it is pulled
    pulled = sm.TRIGGER_PIVOT + quat_rotate(quat_axis_angle((1, 0, 0), TRIGGER_PULL), rel)
    _, back = hands.fit_finger("R", wrist, q, base, "index", G(pulled), step=2)
    return tuple(on), tuple(off), tuple(back)


hands.POSES.setdefault("sten_grip", {"thumb": (-48, -28), "index": (-82, -70), "middle": (-86, -76),
                                     "ring": (-86, -78), "pinky": (-84, -76)})
hands.POSES.setdefault("sten_trigger", dict(hands.POSES["sten_grip"], index=trigger_curls()[0]))
hands.POSES.setdefault("sten_indexed", dict(hands.POSES["sten_grip"], index=trigger_curls()[1]))
hands.POSES.setdefault("sten_pulled", dict(hands.POSES["sten_grip"], index=trigger_curls()[2]))
hands.POSES.setdefault("sten_housing", {"thumb": (-10, -6), "index": (-74, -64), "middle": (-80, -70),
                                        "ring": (-82, -72), "pinky": (-82, -70)})
hands.POSES.setdefault("sten_mag", {"thumb": (-14, -10), "index": (-58, -52), "middle": (-64, -58),
                                    "ring": (-66, -60), "pinky": (-66, -58)})

ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    "grip": ("gun", (_grip()[0], _grip()[1], "sten_indexed")),
    # palm up under the magazine housing, fingers curled round its front face, thumb along it to the left
    "support": ("gun", _wrap(G(HOUSING_C), norm((0.10, 0.22, -1.0)), norm((-1.0, 0.15, 0.10)), "L", "sten_housing",
                             0.0330)),
    # palm on the magazine's rear face, fingers up and curled over its top, thumb along the top rear edge towards
    # the housing (it reaches the catch's tab)
    "mag": ("mag", _wrap((MAG_HOLD_X, 0.0, 0.0), norm((0.0, 1.0, -0.15)), norm((1.0, 0.10, 0.0)), "L", "sten_mag",
                         sm.MAG_HZ + 0.0185, -0.0120)),
    # the same hold further out on the magazine (fetch / insert: the inner end goes into the housing)
    "mag_low": ("mag", _wrap((MAG_HOLD_X - 0.0600, 0.0, 0.0), norm((0.0, 1.0, -0.15)), norm((1.0, 0.10, 0.0)), "L",
                             "sten_mag", sm.MAG_HZ + 0.0185, -0.0120)),
    # thumb tip on the catch's tab (on the housing: gun space), the fingers over the magazine
    "mag_release": ("gun", _tip_anchor(G(sm.MAG_CATCH + np.array([0.0, 0.0018, 0.0])), "thumb",
                                       norm((0.0, 1.0, -0.15)), norm((1.0, 0.10, 0.0)), "L", "sten_mag")),
    # heel of the open hand on the floor plate (seating slap), fingers forward
    "slap": ("mag", _anchor((-sm.MAG_LEN - 0.0170, 0.0, 0.0060), norm((0.0, 0.30, -1.0)), (0.0, 1.0, 0.0), "L",
                            "spread")),
    # the shooting hand leaves the stock and hooks the cocking knob from in front, fingers over it (bolt space: it rides
    # the handle back, and up into the safety notch)
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([0.0030, 0.0040, -0.0080])), "middle",
                                     norm((-0.30, -0.50, -0.80)), norm((-0.20, 0.60, -0.60)), "R", "hook")),
}


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0           # + rearward from rest (cocked); -BOLT_TRAVEL = forward (fired / empty)
        self.bolt_rot = 0.0         # degrees about the bore (+ lifts the handle on the right towards the notch)
        self.trigger = 0.0
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

    # space conversions -------------------------------------------------------------------------
    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def bolt_q(self):
        return quat_axis_angle((0, 0, 1), self.bolt_rot)

    def space_point(self, space, p):
        if space == "gun":
            return self.gun_point(p)
        if space == "bolt":       # the bolt turns about the bore (the design frame's Z axis) and slides along it
            return self.gun_point(self.bolt_trs()["translation"] + quat_rotate(self.bolt_q(), np.asarray(p) + P0))
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def space_quat(self, space):
        if space == "mag":
            return quat_mul(self.gun_q, self.mag_q)
        if space == "bolt":
            return quat_mul(self.gun_q, self.bolt_q())
        return self.gun_q

    def anchor(self, which):
        space, (w, q, pose) = ANCHORS[which]
        return self.space_point(space, w), quat_mul(self.space_quat(space), q), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        items = [(w, world if name == "world" else self.anchor(name)) for name, w in weights.items()]
        pos, q, curls = armrig.blend_targets(items)
        return pos + np.asarray(offset, float), q, curls

    def bolt_trs(self):
        """The bolt node (mesh in design coordinates): its local Z axis is the bore, so it turns about it in place
        and slides along it."""
        return {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z]), "rotation": self.bolt_q()}

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
            "bolt": self.bolt_trs(),
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
