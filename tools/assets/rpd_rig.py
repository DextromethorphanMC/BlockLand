"""RPD viewmodel rig (VIEWMODEL_SPEC sections 3, 16, 18 and 33): node layout in gun space, hip pose, hand anchors and
pose -> node TRS (two-bone arm IK to palm frames, finger curls). Built like ak_rig.py, plus the hinged feed cover
and the belt: its end piece is a node of its own (`belt`, carried by the hand while the belt is changed, or hanging
from the drum's mouth), the pieces behind it (belt_1 ..) are laid along a curve from that end to the drum's mouth
every frame (belt_chain), so the belt always runs out of the drum and feeds by a link per shot."""
import numpy as np

import armrig
import hands
import rpd_model as rm
from ik import UPPER_LEN
from vmath import (frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate,
                   quat_slerp)

P0 = rm.GRIP_TOP.copy()                          # gun pivot: top of the pistol grip (design frame)


def G(p):
    """Design frame -> gun-local."""
    return np.asarray(p, float) - P0


# ---- rest pose (hip) ------------------------------------------------------------------------------
GUN_T = np.array([0.142, -0.134, -0.322])       # the long barrel ends right of the crosshair area
GUN_EULER = (1.0, 3.0, 7.5)                     # yaw (muzzle inward), pitch, roll
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.170, -0.360, 0.200])
SHOULDER_L = np.array([-0.150, -0.280, -0.110])
POLE_R = np.array([0.5, 0.50, 0.70])
POLE_L = np.array([-1.0, -0.75, 0.10])
TRIGGER_PULL = 16.0

STATIC = ("receiver", "handguard", "barrel", "front_sight", "stock", "pistol_grip")
NODES = {   # name: (parent, translation in parent space)
    **{n: ("gun", G((0, 0, 0))) for n in STATIC},
    "feed_cover": ("gun", G(rm.COVER_HINGE)),
    "bolt": ("gun", G((0, 0, 0))),
    "trigger": ("gun", G(rm.TRIGGER_PIVOT)),
    "sight_iron": ("gun", G((0.0, rm.SIGHT_Y, rm.FRONT_POST_Z))),
    "sight_rear": ("gun", G((0.0, rm.SIGHT_Y, rm.REAR_NOTCH_Z))),
    "muzzle_brake": ("gun", G((0.0, 0.0, rm.CROWN_Z))),
    "belt": ("gun", G(rm.FEED)),
    "mag": ("gun", G(rm.MAG_ORIGIN)),
    "muzzle": ("gun", G((0.0, 0.0, rm.MUZZLE_Z - 0.0040))),
    "ejection": ("gun", G(rm.EJECTION)),
}
MESH_ORIGIN = {
    "feed_cover": rm.COVER_HINGE, "trigger": rm.TRIGGER_PIVOT, "sight_iron": (0.0, rm.SIGHT_Y, rm.FRONT_POST_Z),
    "sight_rear": (0.0, rm.SIGHT_Y, rm.REAR_NOTCH_Z), "muzzle_brake": (0.0, 0.0, rm.CROWN_Z),
}
AIM_IRON_BACK = 0.300                            # cheek on the stock comb, clear of the rear rail
AIM_IRON_T = G((0.0, rm.SIGHT_Y, rm.REAR_NOTCH_Z + AIM_IRON_BACK))
MAG_Q = quat_identity()
EJECT_DIR = norm((0.85, 0.20, -0.20))

MOUNTS = {
    "mount_optic": ("gun", G((0.0, rm.RAIL_TOP, rm.OPTIC_Z)), None, None),             # rear receiver rail
    "mount_laser": ("gun", G((rm.HG_SIDE_X, -0.0100, -0.1350)), quat_axis_angle((0, 0, 1), 90.0), None),
    "mount_barrel": ("gun", G((0.0, 0.0, rm.CROWN_Z)), None, None),
    "mount_stock": ("gun", G((0.0, -0.0100, rm.REC_Z1 - 0.0005)), None, None),
    "mount_charm": ("gun", G((-rm.REC_W - 0.0016, -0.0260, 0.2250)), quat_conj(GUN_Q), None),
    # section 35: under the gas block, ahead of the support hand (the bipod folds forward along the barrel)
    "mount_underbarrel": ("gun", G((0.0, rm.GAS_Y - 0.0100, -0.2650)), None, None),
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


_F, _A = rm.GRIP_FRONT, rm.GRIP_AXIS
_X = np.array([1.0, 0.0, 0.0])
_FR = norm(_F * np.cos(np.radians(15.0)) - _X * np.sin(np.radians(15.0)))
HG_C = np.array([0.0, -0.0140, -0.1100])          # handguard section where the support hand sits
KNOB = np.array([rm.REC_W + 0.0160, 0.0015, rm.HANDLE_Z + 0.0010])
_CH = rm.COVER_HINGE
ANCHORS = {   # name: (space, (wrist, palm quat, finger pose))
    "grip": ("gun", _wrap(G(rm.grip_center(0.018)), _FR, -_A, "R", "ak_indexed", 0.031, -0.027)),
    "support": ("gun", _wrap(G(HG_C), norm((1.0, 0.30, 0.10)), norm((-0.25, 0.25, -1.0)), "L", "handguard",
                             0.038, -0.016)),
    # palm down on the rear of the feed cover, fingers across it to the right, thumb forward (cover space: the hinge)
    "cover": ("cover", _anchor(np.array([-0.0040, rm.COVER_TOP + 0.0150, rm.COVER_Z1 - 0.0150]) - _CH,
                               (1.0, -0.25, 0.10), (0.0, 0.0, -1.0), "L", "spread")),
    # fingertips under the latch tab, ready to flip the cover up
    "cover_lift": ("cover", _tip_anchor(np.array([0.0, rm.COVER_TOP - 0.0030, rm.COVER_Z1 + 0.0060]) - _CH, "middle",
                                        (0.85, -0.30, 0.30), (0.0, 0.0, -1.0), "L", "hook")),
    # pinching the belt's end between its first two rounds from the outside (belt space)
    "belt": ("belt", _anchor((-0.0160, -0.0100, 0.0060), (0.25, -1.0, 0.0), (0.0, 0.0, -1.0), "L", "pinch")),
    # palm on the drum's left face, fingers up along it, thumb towards the front (mag space)
    "drum": ("mag", _wrap(rm.DRUM_C + np.array([-rm.DRUM_W, 0.0040, 0.0060]), (0.0, 1.0, -0.20), (0.0, 0.0, -1.0),
                          "L", "rifle_mag", 0.0060)),
    "charging": ("bolt", _tip_anchor(G(KNOB + np.array([0.004, -0.002, -0.008])), "middle",
                                     norm((0.85, 0.45, -0.25)), norm((0.10, 0.10, -1.0)), "L", "hook")),
}


BELT_NODES = tuple(f"belt_{k}" for k in range(1, rm.BELT_ROUNDS))
HANG_END = np.array([-0.0840, -0.0560, 0.0])      # mag-local centre of the end round of a belt hanging off the drum
HANG_Q = quat_axis_angle((0, 0, 1), -96.0)        # (outside either drum's rim), running up to the mouth (-X -> +Y)


def _bezier(p0, p1, p2, p3, n=48):
    t = np.linspace(0.0, 1.0, n)[:, None]
    pts = (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t * t * p2 + t ** 3 * p3
    seg = np.linalg.norm(np.diff(pts, axis=0), axis=1)
    return pts, np.concatenate([[0.0], np.cumsum(seg)])


def belt_chain(end_t, end_q, mouth_t, mouth_q, feed=0.0, n=rm.BELT_ROUNDS):
    """Centres and rotations (gun space) of belt pieces 1 .. n-1 behind the end piece: a cubic curve leaving the end
    along its -X (out of the tray, or up from a hanging end) and entering the drum's mouth downwards; pieces sit a link
    apart (`feed` 0..LINK slides them all one link on, towards the end — a shot feeding the belt), the ones past the
    mouth carry on into the drum (hidden inside it). Each piece's frame: -X along the belt, Z the round's base side
    (blended from the end's to the drum's)."""
    p0 = end_t + quat_rotate(end_q, rm.BELT_END)
    d0 = quat_rotate(end_q, (-1.0, 0.0, 0.0))
    pm = mouth_t + quat_rotate(mouth_q, rm.MOUTH)
    dm = quat_rotate(mouth_q, (0.0, -1.0, 0.0))
    h = 0.42 * np.linalg.norm(pm - p0) + 0.004
    pts, arc = _bezier(p0, p0 + h * d0, pm - h * dm, pm)
    length = arc[-1]
    step = max(rm.LINK, length / (n - 1.5))                # a stretched belt (end pulled far) never leaves a gap
    out = []
    for k in range(1, n):
        s = k * step - feed * step / rm.LINK
        if s <= length:
            i = int(np.clip(np.searchsorted(arc, s) - 1, 0, len(pts) - 2))
            f = (s - arc[i]) / max(arc[i + 1] - arc[i], 1e-9)
            pos, tan = pts[i] + f * (pts[i + 1] - pts[i]), norm(pts[i + 1] - pts[i])
            w = s / max(length, 1e-9)
        else:
            pos, tan, w = pm + (s - length) * dm, dm, 1.0
        axis = quat_rotate(quat_slerp(end_q, mouth_q, w), (0.0, 0.0, 1.0))
        x = -tan
        z = norm(axis - np.dot(axis, x) * x)
        out.append((pos, frame_quat(x, np.cross(z, x), z)))
    return out


class Pose:
    """Everything a clip controls at one instant (see solve())."""

    def __init__(self):
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.bolt_z = 0.0
        self.trigger = 0.0
        self.cover = 0.0            # degrees about X at the hinge (negative opens: the rear lifts)
        self.belt_t = NODES["belt"][1].copy()
        self.belt_q = quat_identity()
        self.belt_s = 1.0
        self.belt_hang = 0.0        # 0 = the end where belt_t / belt_q put it, 1 = hanging off the drum's mouth
        self.belt_feed = 0.0        # 0..LINK: the belt sliding one link into the gun (a shot)
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

    def cover_q(self):
        return quat_axis_angle((1, 0, 0), self.cover)

    def space_point(self, space, p):
        if space == "gun":
            return self.gun_point(p)
        if space == "bolt":
            return self.gun_point(np.asarray(p) + np.array([0.0, 0.0, self.bolt_z]))
        if space == "cover":
            return self.gun_point(NODES["feed_cover"][1] + quat_rotate(self.cover_q(), p))
        if space == "belt":
            bt, bq = self.belt_end()
            return self.gun_point(bt + quat_rotate(bq, p))
        return self.gun_point(self.mag_t + quat_rotate(self.mag_q, p))

    def space_quat(self, space):
        local = {"mag": self.mag_q, "belt": self.belt_end()[1], "cover": self.cover_q()}.get(space)
        return self.gun_q if local is None else quat_mul(self.gun_q, local)

    def belt_end(self):
        """The end piece's node translation / rotation (gun space): blended from the hand-driven belt_t / belt_q to
        hanging off the drum's mouth by belt_hang (the position may overshoot: the belt swinging as it drops)."""
        h = self.belt_hang
        if h == 0.0:
            return self.belt_t, self.belt_q
        hq = quat_mul(self.mag_q, HANG_Q)
        ht = self.mag_t + quat_rotate(self.mag_q, HANG_END) - quat_rotate(hq, rm.BELT_END)
        return self.belt_t + h * (ht - self.belt_t), quat_slerp(self.belt_q, hq, float(np.clip(h, 0.0, 1.0)))

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
        bt, bq = self.belt_end()
        bs = self.belt_s if self.mag_s > 0 else 0.0        # the belt goes wherever its drum goes
        tr, ar = armrig.solve_side("R", sr, POLE_R if self.pole_r is None else self.pole_r, hr)
        tl, al = armrig.solve_side("L", sl, POLE_L if self.pole_l is None else self.pole_l, hl, limit=True)
        trs = {
            "gun": {"translation": self.gun_t, "rotation": self.gun_q},
            "bolt": {"translation": NODES["bolt"][1] + np.array([0.0, 0.0, self.bolt_z])},
            "trigger": {"rotation": quat_axis_angle((1, 0, 0), self.trigger)},
            "feed_cover": {"rotation": self.cover_q()},
            "belt": {"translation": bt, "rotation": bq, "scale": np.full(3, bs)},
            "mag": {"translation": self.mag_t, "rotation": self.mag_q, "scale": np.full(3, self.mag_s)},
            "camera": {"rotation": self.cam_q},
        }
        chain_t = bt - quat_rotate(bq, (self.belt_feed, 0.0, 0.0))      # the chain counts from the unfed end
        for name, (pos, q) in zip(BELT_NODES, belt_chain(chain_t, bq, self.mag_t, self.mag_q, self.belt_feed)):
            trs[name] = {"translation": pos, "rotation": q, "scale": np.full(3, bs)}
        trs.update(tr)
        trs.update(tl)
        trs["arm_R"]["translation"] = sr
        trs["arm_L"]["translation"] = sl
        return trs, {"R": ar, "L": al, "target_R": hr, "target_L": hl}


ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])
