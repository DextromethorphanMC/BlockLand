"""Thompson viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 18 and 24): node layout in gun space, hip pose, hand anchors
and pose -> node TRS (two-bone arm IK to palm frames, finger curls). Laid out like the AK rig (ak_rig.py): the
shooting hand on the pistol grip, the support hand round the vertical foregrip."""
import numpy as np

import armrig
import hands
import tommy_model as tm
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

P0 = tm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.128, -0.118, -0.300])       # framed for the 54 deg viewmodel FOV (coverage.py, framing.py)
GUN_EULER = (2.5, 3.0, 7.0)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.290, -0.120])  # support shoulder forward: the hand is far out on the foregrip
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.80, 0.05])

TRIGGER_PULL = 14.0

STATIC = ("receiver", "barrel", "grip", "stock")   # the wooden foregrip is an attachment (tommy_grip, section 35)
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "bolt": ("gun", G((0, 0, 0))),
    "selector": ("gun", G(tm.SELECTOR_PIVOT)),
    "trigger": ("gun", G(tm.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, tm.SIGHT_Y, tm.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, tm.SIGHT_Y, tm.REAR_NOTCH_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, tm.CROWN_Z))),
    "mag": ("gun", G(tm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, tm.MUZZLE_Z - 0.0030))),
    "ejection": ("gun", G(tm.EJECTION)),
}
MESH_ORIGIN = {   # node -> design-frame point its mesh is built around
    "selector": tm.SELECTOR_PIVOT, "trigger": tm.TRIGGER_PIVOT, "sight_iron": (0.0, tm.SIGHT_Y, tm.FRONT_POST_Z),
    "sight_rear": (0.0, tm.SIGHT_Y, tm.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, tm.CROWN_Z),
}
# eye point (section 5): on the sight line, looking along -Z, behind the rear notch (cheek on the stock comb)
AIM_IRON_BACK = 0.105
AIM_IRON_T = G((0.0, tm.SIGHT_Y, tm.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()                          # the well is straight: magazines go straight up and down
EJECT_DIR = norm((0.85, 0.40, -0.20))           # cases go right, a little up and forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale); an attachment's origin goes here
MOUNTS = {
    "mount_optic": ("gun", G((0.0, tm.RAIL_TOP, tm.OPTIC_Z)), None, None),         # on the riser, ahead of the knob
    # laser on the right of the receiver's front block: the module hangs along the attachment's -Y, turn it onto +X
    "mount_laser": ("gun", G((tm.REC_W + 0.0004, -0.0080, 0.0060)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, tm.CROWN_Z)), None, None),                  # compensator unscrewed
    # section 35: under the barrel clamp's plate, where the wooden foregrip sat (it is the slot's default attachment,
    # `tommy_grip`, built from the same source part); the clips' support hand holds it there
    "mount_underbarrel": ("gun", G(tm.FORE_MOUNT), None, None),
    "mount_stock": ("gun", G((0.0, -0.0100, tm.REC_Z1 - 0.0005)), None, None),      # replaces the wooden stock
    # low on the left of the frame behind the grip; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G((-tm.REC_W - 0.0016, -0.0320, 0.2300)), quat_conj(GUN_Q), None),
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


# support hand holding a magazine by its front face, fingers up: nearly flat fingers (they lie on the stick's and the
# drum's front faces alike), the thumb curled round the magazine's left edge
hands.POSES.setdefault("tommy_side", {"thumb": (-34, -22), "index": (-16, -12), "middle": (-14, -10),
                                      "ring": (-14, -10), "pinky": (-16, -12)})

_X = np.array([1.0, 0.0, 0.0])
_FR = norm(tm.GRIP_FRONT * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))  # palm axis a little left
_FL = norm(tm.FORE_FRONT * np.cos(np.radians(18.0)) + _X * np.sin(np.radians(18.0)))  # left palm: a little right
KNOB = np.array([0.0, 0.0175, tm.KNOB_Z])        # cocking knob top centre (bolt space at rest)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the grip, knuckles round the front strap, index resting on the frame
    "grip": ("gun", _wrap(G(tm.grip_center(0.022)), _FR, -tm.GRIP_AXIS, "R", "ak_indexed", 0.030, -0.024)),
    # support hand round the vertical foregrip: palm on its left, fingers round the grooved front
    "support": ("gun", _wrap(G(tm.fore_center(0.055)), _FL, -tm.FORE_AXIS, "L", "grip", 0.029, -0.022)),
    # support hand round the magazine just under the well (mag space: +Y up the mag, -Z front): palm on its left
    "mag": ("mag", _wrap((0.0, -0.040, 0.002), (0.0, -0.15, -1.0), (0.0, 1.0, 0.20), "L", "rifle_mag", 0.0200,
                         0.006)),
    # thumb on the magazine catch above the left of the well, fingers on the magazine's front
    "mag_release": ("mag", _tip_anchor((-0.0150, 0.0080, 0.0230), "thumb", norm((0.0, 0.45, -0.89)),
                                       norm((0.0, 0.40, 1.0)), "L", "rifle_mag")),
    # fresh magazine held low by its upper end: palm on its front face, fingers round the right side
    "mag_low": ("mag", _wrap((0.0, -0.030, -0.006), (1.0, 0.0, 0.0), (0.0, 1.0, 0.15), "L", "rifle_mag", 0.0300,
                             -0.018)),
    # heel of the palm pushing the magazine home from its front face (works for the stick and the drum's tower)
    "slap": ("mag", _anchor((0.0, -0.052, -0.040), (0.0, -0.25, -1.0), (-1.0, 0.0, 0.0), "L", "spread")),
    # palm flat on the magazine's front face low down, fingers up, thumb round its left edge: one hold for the stick
    # and the drum (the drum's front face is flush with the stick's, tommy_model.DRUM_C), used to pull the old
    # magazine, carry the new one and push it home; the forearm hangs in front of and below the magazine, so the
    # magazine stays in view
    "mag_side": ("mag", _wrap((0.0, -0.104, 0.0), (0.0, 1.0, 0.06), (-1.0, 0.10, 0.0), "L", "tommy_side", 0.0190)),
    # over the top: fingers hooked round the front of the cocking knob from the left (bolt space)
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([-0.004, -0.003, -0.007])), "index",
                                     norm((0.55, 0.55, -0.62)), norm((0.0, 0.25, 1.0)), "L", "hook")),
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
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
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
