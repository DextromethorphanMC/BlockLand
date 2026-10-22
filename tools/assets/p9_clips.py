"""P9 animation clips (VIEWMODEL_SPEC section 4): idle, draw, fire, fire_last. Reloads / inspect live in
p9_clips_long. Each clip maps time -> rig.Pose; gen_p9 bakes the poses (IK included) into glTF keys."""
import numpy as np

import p9_rig as rig
from anim import EASES, Track, damped
from vmath import quat_euler, quat_mul


class Clip:
    def __init__(self, name, duration, pose_fn, events=(), fps=30, loop=False, extra_times=(), force_nodes=()):
        self.name, self.duration, self._pose = name, duration, pose_fn
        self.events = sorted(events)
        self.fps, self.loop = fps, loop
        self.extra_times = extra_times
        self.force_nodes = set(force_nodes)

    def pose(self, t):
        if self.loop and t >= self.duration - 1e-9:
            t = 0.0                                   # identical first/last key
        return self._pose(min(max(t, 0.0), self.duration))


class Weights:
    """Smoothly cross-faded anchor weights: Weights((t, {"support": 1}), (t2, {"mag": 1}), ...)."""

    def __init__(self, *keys, ease="sine"):
        self.keys = sorted(keys, key=lambda k: k[0])
        self.ease = EASES[ease]

    def __call__(self, t):
        ks = self.keys
        if t <= ks[0][0]:
            return dict(ks[0][1])
        if t >= ks[-1][0]:
            return dict(ks[-1][1])
        for (t0, w0), (t1, w1) in zip(ks, ks[1:]):
            if t0 <= t < t1:
                u = self.ease((t - t0) / (t1 - t0))
                names = set(w0) | set(w1)
                return {n: w0.get(n, 0.0) * (1 - u) + w1.get(n, 0.0) * u for n in names}
        return dict(ks[-1][1])


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def V(*a):
    return np.array(a, float)


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0004 * np.sin(w + 0.6), 0.0007 * np.sin(w), 0.0003 * np.sin(w + 1.3)),
              (0.12 * np.sin(w + 0.3), 0.18 * np.sin(w + 0.9), 0.15 * np.sin(w)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
DRAW_OFF = Track((0.00, V(0.045, -0.250, 0.070)), (0.36, V(-0.003, 0.005, -0.004), "out"),
                 (0.48, V(0.001, -0.0015, 0.001)), (0.60, V(0, 0, 0)))
DRAW_ROT = Track((0.00, V(-14, -52, -34)), (0.36, V(1.2, 2.6, 1.8), "out"),
                 (0.48, V(-0.3, -0.7, -0.4)), (0.60, V(0, 0, 0)))
DRAW_CAM = Track((0.00, V(0, 0, 0)), (0.30, V(-0.6, 0, -0.4)), (0.46, V(0.25, 0, 0.15)), (0.60, V(0, 0, 0)))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.60, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
F_TRIG = Track((0.0, -6.0), (0.010, -14.0, "out2"), (0.050, -14.0), (0.120, 0.0))
F_HAMMER = Track((0.0, rig.HAMMER_COCKED), (0.006, 0.0, "in2"), (0.022, 46.0, "out2"), (0.050, 46.0),
                 (0.085, rig.HAMMER_COCKED))
F_SLIDE = Track((0.0, 0.0), (0.025, rig.SLIDE_LOCK, "out2"), (0.034, rig.SLIDE_LOCK), (0.088, 0.0, "in2"),
                (0.097, 0.0012, "out2"), (0.108, 0.0))
F_SLIDE_LAST = Track((0.0, 0.0), (0.025, rig.SLIDE_LOCK + 0.0015, "out2"), (0.040, rig.SLIDE_LOCK))
F_KICK_T = Track((0.0, V(0, 0, 0)), (0.018, V(0.0005, 0.0022, 0.0095), "out2"), (0.060, V(0.0003, 0.0030, 0.0035)),
                 (0.130, V(0, -0.0004, -0.0006)), (0.200, V(0, 0, 0)))
F_KICK_R = Track((0.0, V(0, 0, 0)), (0.020, V(0.5, 3.4, -0.7), "out2"), (0.070, V(0.3, 2.0, -0.4)),
                 (0.140, V(-0.1, -0.35, 0.1)), (0.200, V(0, 0, 0)))
F_CAM = Track((0.0, V(0, 0, 0)), (0.030, V(0.4, 0.05, 0.0), "out2"), (0.200, V(0, 0, 0)))
F_STOP = Track((0.0, 0.0), (0.030, 0.0), (0.050, rig.STOP_UP, "out2"))
FIRE_KEYS = sorted(set(F_TRIG.times() + F_HAMMER.times() + F_SLIDE.times() + F_KICK_T.times()
                       + F_SLIDE_LAST.times() + F_STOP.times()))


def fire_pose(last):
    def pose(t):
        p = rig.Pose()
        apply_gun(p, F_KICK_T(t), F_KICK_R(t))
        p.trigger = float(F_TRIG(t))
        p.hammer = float(F_HAMMER(t)) if not last else float(Track((0.0, rig.HAMMER_COCKED), (0.006, 0.0, "in2"),
                                                                   (0.022, 46.0, "out2"))(t))
        p.slide_z = float(F_SLIDE_LAST(t) if last else F_SLIDE(t))
        if last:
            p.stop = float(F_STOP(t))
        cam(p, *F_CAM(t))
        return p
    return pose


FIRE = Clip("fire", 0.20, fire_pose(False), fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", 0.20, fire_pose(True), fps=120, extra_times=FIRE_KEYS)


def all_clips():
    import p9_clips_long as long
    import p9_sprint
    return [IDLE, DRAW, FIRE, FIRE_LAST, long.RELOAD, long.RELOAD_EMPTY, long.INSPECT, p9_sprint.SPRINT]


__all__ = ["Clip", "Weights", "apply_gun", "cam", "V", "damped", "all_clips"]
