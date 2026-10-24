"""KS-3 viewmodel rig (VIEWMODEL_SPEC sections 3, 16 and 25): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like the AK / AWP rigs. The support hand rides
the pump (anchors in pump space follow its stroke); during shell reloads it carries `shell_load`, a shell under
`arm_L_hand` whose local transform is solved every frame so the round travels exactly along its path into the port."""
import numpy as np

import armrig
import hands
import ks3_model as km
from ak_rig import _anchor, _tip_anchor, _wrap
from ik import UPPER_LEN
from vmath import (frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate,
                   quat_to_mat, mat_to_quat)

P0 = km.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.142, -0.161, -0.269])       # framed for the 54 deg viewmodel FOV: the receiver's front where
GUN_EULER = (2.5, 3.0, 7.0)                     # the AK's is, a little lower (the grip top is 6.6 cm under the bore)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.130, -0.285, -0.170])  # support shoulder forward: the pump is far out
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])

TRIGGER_PULL = 15.0
GATE_IN = 24.0                                   # loading gate pushed up into the receiver by a shell (about +X)

STATIC = ("receiver", "grip", "barrel", "stock", "safety", "tube_standard", "tube_extended")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "pump": ("gun", G(km.pivot("pump"))),
    "loading_gate": ("gun", G(km.pivot("loading_gate"))),
    "trigger": ("gun", G(km.pivot("trigger"))),
    "sight_iron": ("gun", G((0.0, km.SIGHT_Y, km.FRONT_SIGHT_Z))),
    "sight_rear": ("gun", G((0.0, km.SIGHT_Y, km.REAR_SIGHT_Z))),
    "muzzle": ("gun", G((0.0, 0.0, km.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(km.EJECTION)),
}
# eye point (section 5): on the sight line, a cheek weld on the comb puts it 0.24 behind the rear notch
AIM_IRON_BACK = 0.240
AIM_IRON_T = G((0.0, km.SIGHT_Y, km.REAR_SIGHT_Z + AIM_IRON_BACK))
EJECT_DIR = norm((0.80, 0.48, -0.20))           # hulls go right, up and a little forward

# attachment mounts (section 19): name -> (parent, translation, rotation, scale)
MOUNTS = {
    "mount_optic": ("gun", G((0.0, km.REC_TOP + km.RAIL_H, km.OPTIC_Z)), None, None),     # on the receiver rail
    # laser on the clamp's side rail, right of the barrel in front of the forend: the module hangs along -Y -> +X
    "mount_laser": ("gun", G((km.BARREL_R + 0.0062, 0.0, km.LASER_Z)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, km.MUZZLE_Z)), None, None),
    # section 35: under the front of the forend, riding the pump's stroke, ahead of the support hand (pump space)
    "mount_underbarrel": ("pump", np.array([0.0, -0.0390, -0.1350]), None, None),
    "mount_stock": ("gun", G((0.0, -0.0220, km.REC_Z1 - 0.0005)), None, None),            # replaces the stock
    # sling loop low on the left of the receiver's rear; -Y hangs straight down in the hip pose
    "mount_charm": ("gun", G(km.CHARM_POINT), quat_conj(GUN_Q), None),
}


def ejection_quat():
    x = EJECT_DIR
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


# ---- hand anchors (section 16) ---------------------------------------------------------------------
_F, _A = km.GRIP_FRONT, km.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))   # palm long axis a little to the left
PUMP_C = np.array([0.0, -0.0080, -0.0400])      # pump space: where the support hand's palm sits under the forend
PORT_C = np.array([0.0, km.REC_BOT - 0.0040, 0.0600])                         # under the loading port (design frame)
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    # shooting hand: palm on the right of the pistol grip, knuckles round the front strap, index along the guard
    "grip": ("gun", _wrap(G(km.grip_center(0.034)), _FR, -_A, "R", "ak_indexed", 0.0330, -0.027)),
    # support hand under the forend (pump space): palm up, thumb along the left side, fingers up the right side
    "support": ("pump", _wrap(PUMP_C, norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                              0.0380, -0.016)),
    # empty hand under the receiver, thumb at the loading port (between shells)
    "port": ("gun", _wrap(G(PORT_C), norm((1.0, 0.45, 0.20)), norm((-0.20, 0.30, -1.0)), "L", "pinch", 0.0260,
                          -0.012)),
}
# a shell in the left hand (hand frame): lying over the palm in front of the thumb, nose along the thumb (the thumb
# pushes its head), across the fingers that hold it from below; shell_load's rest transform
HOLD_T = np.array([0.0350, -0.0400, 0.0220])
HOLD_Q = frame_quat((0.0, -1.0, 0.0), (0.0, 0.0, 1.0), (-1.0, 0.0, 0.0))


def _mat(t, q):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(q)
    m[:3, 3] = t
    return m


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.pump_z = 0.0
        self.gate = 0.0
        self.trigger = 0.0
        self.shell = None           # (position, quat) of the carried shell in gun space; None = none in this clip
        self.shell_shown = False    # the carried shell is drawn (else collapsed, its transform kept continuous)
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

    def pump_point(self, p):
        """Pump space (design frame relative to the pump's pivot at rest) -> viewmodel space."""
        return self.gun_point(NODES["pump"][1] + np.asarray(p, float) + np.array([0.0, 0.0, self.pump_z]))

    def anchor(self, which):
        space, (w, q, pose) = ANCHORS[which]
        pos = self.pump_point(w) if space == "pump" else self.gun_point(w)
        return pos, quat_mul(self.gun_q, q), hands.blend({pose: 1.0})

    def shell_anchor(self, pose="pinch"):
        """The left hand holding the carried shell (palm frame from the shell's frame and HOLD)."""
        t, q = self.shell
        M = _mat(self.gun_point(G(t)), quat_mul(self.gun_q, q)) @ np.linalg.inv(_mat(HOLD_T, HOLD_Q))
        return M[:3, 3], mat_to_quat(M[:3, :3]), hands.blend({pose: 1.0})

    def blend_hand(self, weights, offset=(0, 0, 0), world=None):
        items = []
        for name, w in weights.items():
            if name == "world":
                items.append((w, world))
            elif name == "shell":
                items.append((w, self.shell_anchor()))
            else:
                items.append((w, self.anchor(name)))
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
            "pump": {"translation": NODES["pump"][1] + np.array([0.0, 0.0, self.pump_z])},
            "loading_gate": {"rotation": rx(self.gate)},
            "trigger": {"rotation": rx(self.trigger)},
            "camera": {"rotation": self.cam_q},
            "shell_load": {"translation": HOLD_T.copy(), "rotation": HOLD_Q.copy(), "scale": np.zeros(3)},
        }
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        if self.shell is not None:
            # the hand as solved (IK may fall short of the target): the shell's local transform keeps it on its path
            hand = _mat(al["hand"], al["q_palm"])
            t, q = self.shell
            local = np.linalg.inv(hand) @ _mat(self.gun_point(G(t)), quat_mul(self.gun_q, q))
            trs["shell_load"] = {"translation": local[:3, 3], "rotation": mat_to_quat(local[:3, :3]),
                                 "scale": np.full(3, 1.0 if self.shell_shown else 0.0)}
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
