"""Dual-pistol viewmodel rig (VIEWMODEL_SPEC section 26): two pistols, one per hand, from one pistol layout.

The right gun is the ordinary contract (`gun` and the nodes under it); the left gun repeats every node with the suffix
`_L` under `gun_L`. The left pair (gun + left hand) is the MIRROR image of a right-hand pose: clips author both sides
the same way ("as if it were the right gun in the right hand"), and solve() mirrors the left side across the view's
YZ plane. So the left gun is a left-handed twin (its ejection port, slide stop and case throw on its outer side) and
every hand anchor, finger pose and clip works for both hands. Both files of the dual USP (usp_dual.glb and the
Cerberus skin's usp_dual_cerberus.glb) are built by this rig and dual_clips, each from its own PistolLayout."""
from dataclasses import dataclass, field

import numpy as np

import armrig
import hands
from ik import UPPER_LEN
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_identity, quat_mul, quat_rotate

MIRROR = np.array([-1.0, 1.0, 1.0])
SUFFIX = "_L"


def mirror_t(t):
    return np.asarray(t, float) * MIRROR


def mirror_q(q):
    """The rotation conjugated by the X mirror (rotations about X stay, about Y and Z turn the other way)."""
    q = np.asarray(q, float)
    return np.array([q[0], -q[1], -q[2], q[3]])


@dataclass
class PistolLayout:
    """Where the rig finds one pistol's parts, in its design frame (+X right, +Y up, +Z rearward, bore on x = y = 0)."""
    name: str
    p0: np.ndarray                     # gun pivot (top of the grip), the `gun` node's origin
    grip_angle: float                  # degrees between the grip axis and the vertical
    grip_c0: np.ndarray                # grip centre line at its top
    grip_len: float
    mag_origin: np.ndarray             # seated magazine pivot
    hammer_pivot: np.ndarray
    trigger_pivot: np.ndarray
    stop_pivot: np.ndarray
    crown_z: float                     # muzzle
    slide_front: float                 # front face of the slide (the barrel mount sits just in front)
    laser_origin: np.ndarray
    eject_pos: np.ndarray
    release: np.ndarray                # thumb point of the magazine release
    stop_tab: np.ndarray               # thumb point of the slide-stop tab
    charm_pos: np.ndarray              # sling loop on the left of the frame
    grip_d: float = 0.031              # where the shooting hand's palm centres on the grip
    grip_r: float = 0.033              # palm face distance from the grip centre line
    grip_push: float = -0.024
    slide_lock: float = 0.032
    hammer_cocked: float = 32.0
    stop_up: float = 5.0
    spin_pivot: np.ndarray = field(default_factory=lambda: np.array([0.0, -0.0465, -0.0080]))   # inside the guard

    @property
    def axis(self):
        a = np.radians(self.grip_angle)
        return np.array([0.0, -np.cos(a), np.sin(a)])

    @property
    def front(self):
        a = np.radians(self.grip_angle)
        return np.array([0.0, -np.sin(a), -np.cos(a)])

    def grip_center(self, d):
        return self.grip_c0 + d * self.axis

    def G(self, p):
        """Design frame -> gun-local."""
        return np.asarray(p, float) - self.p0


EJECT_DIR = norm((0.80, 0.50, 0.33))


def eject_quat(direction):
    """Frame whose +X is the case throw direction (the runtime throws cases along the ejection node's +X)."""
    x = norm(direction)
    y = norm(np.cross((0, 0, 1.0), x))
    y = y if y[1] > 0 else -y
    return frame_quat(x, y, np.cross(x, y))


# ---- hip pose (right side; the left gun is its mirror image) --------------------------------------------------
GUN_T = np.array([0.140, -0.082, -0.340])
GUN_EULER = (6.0, 1.5, 9.0)                    # yaw inwards, pitch, roll (top leans in)
GUN_Q = quat_euler(*GUN_EULER)
SHOULDER_R = np.array([0.200, -0.320, 0.050])
SHOULDER_L = mirror_t(SHOULDER_R)
POLE_R = np.array([1.0, -0.60, -0.10])
POLE_L = mirror_t(POLE_R)
ARM_FORE_T = np.array([0.0, -UPPER_LEN, 0.0])


def _anchor(centre, fwd, thumb, pose):
    """Right-hand anchor from the palm centre: (wrist, palm quat, finger pose name)."""
    q = hands.palm_frame(fwd, thumb, "R")
    wrist = np.asarray(centre, float) - quat_rotate(q, (0.0, -hands.PALM_LEN / 2, 0.0))
    return wrist, q, pose


def _wrap(centre, fwd, thumb, pose, r, push=0.0):
    q = hands.palm_frame(fwd, thumb, "R")
    z = quat_rotate(q, (0.0, 0.0, 1.0))
    return _anchor(np.asarray(centre, float) - hands.wrap_distance(r) * z + push * norm(fwd), fwd, thumb, pose)


class DualRig:
    """Node layout, hand anchors and pose solving for one PistolLayout (see the module docstring)."""

    def __init__(self, layout: PistolLayout):
        self.lay = L = layout
        G = L.G
        self.nodes = {   # name: (parent, translation in parent space), right gun (the left adds SUFFIX, mirrored)
            "frame": ("gun", G((0, 0, 0))),
            "slide": ("gun", G((0, 0, 0))),
            "barrel": ("gun", G((0, 0, 0))),
            "hammer": ("gun", G(L.hammer_pivot)),
            "trigger": ("gun", G(L.trigger_pivot)),
            "slide_stop": ("gun", G(L.stop_pivot)),
            "mag": ("gun", G(L.mag_origin)),
            "muzzle": ("gun", G((0, 0, L.crown_z))),
            "ejection": ("gun", G(L.eject_pos)),
        }
        self.mag_q = quat_axis_angle((1, 0, 0), -L.grip_angle)        # mag local +Y runs up the grip axis
        # attachment mounts (section 19): name -> (parent, translation, rotation); no optic or stock on dual guns
        self.mounts = {
            "mount_laser": ("gun", G(L.laser_origin), None),
            "mount_underbarrel": ("gun", G(L.laser_origin), None),               # section 35: the laser's rail
            "mount_barrel": ("gun", G((0.0, 0.0, L.slide_front - 0.0010)), None),
            "mount_charm": ("gun", G(L.charm_pos), quat_conj(GUN_Q)),             # right gun only
        }
        x = np.array([1.0, 0.0, 0.0])
        f, a = L.front, L.axis
        fr = norm(f * np.cos(np.radians(5.0)) - x * np.sin(np.radians(5.0)))
        self.anchors = {   # right-hand anchors in gun space (the left hand uses them mirrored)
            "grip": _wrap(G(L.grip_center(L.grip_d)), fr, -a, "indexed", L.grip_r, L.grip_push),
            # fingers opened round the trigger guard for a twirl: the palm backs off the grip
            "open": _wrap(G(L.grip_center(L.grip_d)), fr, -a, "relaxed", L.grip_r + 0.018, L.grip_push - 0.004),
        }
        # the gripping hand shifted so its thumb lands on the magazine release / the slide-stop tab (both on the
        # gun's left side): (wrist, palm quat, curls) in gun space, and how far the fitted thumb tip misses
        self.anchors["release"], self.release_miss = self._fit_thumb(L.release)
        self.anchors["stop"], self.stop_miss = self._fit_thumb(L.stop_tab, lift=0.0015)

    def _fit_thumb(self, point, lift=0.0):
        """Grip rolled about the grip axis and slid along it, thumb curls brute-forced, so the thumb tip reaches a
        point (fitted once per layout)."""
        w0, q0, pose = self.anchors["grip"]
        base = hands.blend({pose: 1.0})
        target = self.lay.G(point) + np.array([0.0, lift, 0.0])
        pivot = self.lay.G(self.lay.grip_center(0.035))
        trials = []
        for roll in range(-32, 1, 4):                   # coarse: every hand shift with coarse thumb curls
            for up in (-0.010, -0.006, -0.003, 0.0):
                for fwd in (0.0, 0.004, 0.008, 0.012):
                    qr = quat_axis_angle(self.lay.axis, roll)
                    w = pivot + quat_rotate(qr, w0 - pivot) + up * self.lay.axis + fwd * self.lay.front
                    q = quat_mul(qr, q0)
                    d, _ = hands.fit_finger("R", w, q, base, "thumb", target, ranges=((-120, 60), (-90, 40)), step=15)
                    trials.append((d + 0.00010 * abs(roll) + 0.04 * (abs(up) + fwd), roll, up, fwd, w, q))
        best = None
        for score, roll, up, fwd, w, q in sorted(trials, key=lambda x: x[0])[:4]:   # fine: the best few
            d, cur = hands.fit_finger("R", w, q, base, "thumb", target, ranges=((-120, 60), (-90, 40)), step=4)
            s = d + 0.00010 * abs(roll) + 0.04 * (abs(up) + fwd)
            if best is None or s < best[0]:
                best = (s, w, q, cur, d)
        _, w, q, cur, d = best
        curls = dict(base, thumb=np.asarray(cur, float))
        return (w, q, curls), d

    def ejection_quat(self, left=False):
        return eject_quat(mirror_t(EJECT_DIR) if left else EJECT_DIR)

    def pose(self):
        return Pose(self)

    def rest_trs(self):
        return self.pose().solve()[0]


class Side:
    """One gun and the hand on it, authored as the right gun in the right hand (viewmodel space)."""

    def __init__(self, rig):
        self.rig = rig
        L = rig.lay
        self.gun_t = GUN_T.copy()
        self.gun_q = GUN_Q.copy()
        self.slide_z = 0.0
        self.trigger = 0.0
        self.hammer = L.hammer_cocked
        self.stop = 0.0
        self.mag_t = rig.nodes["mag"][1].copy()
        self.mag_q = rig.mag_q.copy()
        self.mag_s = 1.0
        self.hand = None             # (wrist, palm quat, curls) in viewmodel space; default = the grip anchor
        self.fingers = None          # optional finger curls overriding the target's
        self.pole = None

    def gun_point(self, p):
        return self.gun_t + quat_rotate(self.gun_q, p)

    def gun_dir(self, d):
        return quat_rotate(self.gun_q, d)

    def anchor(self, name):
        w, q, pose = self.rig.anchors[name]
        curls = hands.blend({pose: 1.0}) if isinstance(pose, str) else pose
        return self.gun_point(w), quat_mul(self.gun_q, q), curls

    def offset(self, t=(0, 0, 0), euler=(0, 0, 0)):
        """Gun moved from the hip pose: world translation + local yaw / pitch / roll (degrees)."""
        self.gun_t = GUN_T + np.asarray(t, float)
        self.gun_q = quat_mul(GUN_Q, quat_euler(*euler))

    def spin(self, degrees, pivot=None):
        """Turns the gun about its local X axis through a point of the gun (default: inside the trigger guard)."""
        p = self.rig.lay.G(self.rig.lay.spin_pivot if pivot is None else pivot)
        pw = self.gun_point(p)
        self.gun_q = quat_mul(self.gun_q, quat_axis_angle((1, 0, 0), degrees))
        self.gun_t = pw - quat_rotate(self.gun_q, p)


class Pose:
    """Both sides plus the camera at one instant; solve() -> node TRS (IK included), the left side mirrored."""

    def __init__(self, rig):
        self.rig = rig
        self.R = Side(rig)
        self.L = Side(rig)
        self.cam_q = quat_identity()

    def solve(self):
        rig = self.rig
        rx = lambda deg: quat_axis_angle((1, 0, 0), deg)
        trs = {"camera": {"rotation": self.cam_q}}
        info = {}
        for key, s in (("R", self.R), ("L", self.L)):
            left = key == "L"
            sfx = SUFFIX if left else ""
            mt = mirror_t if left else (lambda t: np.asarray(t, float))
            mq = mirror_q if left else (lambda q: np.asarray(q, float))
            trs["gun" + sfx] = {"translation": mt(s.gun_t), "rotation": mq(s.gun_q)}
            trs["slide" + sfx] = {"translation": rig.nodes["slide"][1] + np.array([0, 0, s.slide_z])}
            trs["trigger" + sfx] = {"rotation": rx(s.trigger)}
            trs["hammer" + sfx] = {"rotation": rx(s.hammer)}
            trs["slide_stop" + sfx] = {"rotation": rx(s.stop)}
            trs["mag" + sfx] = {"translation": mt(s.mag_t), "rotation": mq(s.mag_q), "scale": np.full(3, s.mag_s)}
            target = s.hand if s.hand is not None else s.anchor("grip")
            if s.fingers is not None:
                target = (target[0], target[1], s.fingers)
            pole = POLE_R if s.pole is None else np.asarray(s.pole, float)
            if left:
                target = (mirror_t(target[0]), mirror_q(target[1]) if target[1] is not None else None, target[2])
                pole = mirror_t(pole)
            tr, ik = armrig.solve_side(key, SHOULDER_L if left else SHOULDER_R, pole, target)
            trs.update(tr)
            info[key] = ik
            info["target_" + key] = target
        return trs, info
