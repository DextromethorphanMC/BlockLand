"""P9 animation clips (VIEWMODEL_SPEC sections 4, 15-17, 31): idle, draw, fire, fire_last. Reloads / inspect live in
p9_clips_long, walk / sprint / tac_sprint in p9_loco. Each clip maps time -> rig.Pose (hands are palm frames +
finger curls); gen_p9 bakes the poses (IK included) at >= 60 samples/s.

Motion is authored with anim.Curve (C1 through its keys: velocity carries through a key unless it is a stop, an
extreme or a hit) plus secondary motion from anim.ring (the weapon answering every push and slam), anim.drift (moving
holds) and finger lag, so nothing starts or stops dead except where a part really hits something (section 31)."""
import numpy as np

import ads_fire
import hands
import motion_layer
import p9_rig as rig
from anim import EASES, Curve, damped, ring, smoothstep
from vmath import quat_euler, quat_mul

FINGER_LAG = 0.05          # small parts follow the hand a few frames later (section 15)


class Clip:
    def __init__(self, name, duration, pose_fn, events=(), fps=60, loop=False, extra_times=(), force_nodes=()):
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


def ease(name, t, t0, t1):
    return EASES[name](float(np.clip((t - t0) / (t1 - t0), 0.0, 1.0)))


def poses(t, track):
    """Finger pose blend from a Weights-like track of {pose_name: w}."""
    return hands.blend(track(t))


def left_hand(p, weights, t, offset=(0, 0, 0), world=None, lag=FINGER_LAG):
    """Support hand from anchor weights; its fingers follow the hand with a lag."""
    pos, q, _ = p.blend_hand(weights(t), p.gun_dir(np.asarray(offset, float)), world)
    _, _, curls = p.blend_hand(weights(max(t - lag, 0.0)), (0, 0, 0), world)
    p.hand_l = (pos, q, curls)


# ---------------------------------------------------------------------------------- v3 helpers
def until(t, t1, fade=0.04):
    """1 before t1 - fade, easing to 0 at t1: rings and drift that must be gone by the end of a clip."""
    return 1.0 - smoothstep(t1 - fade, t1, t)


def support_world(p, t, offset, curls=None):
    """Support hand on its anchor plus a viewmodel-space offset (it reaches the weapon from somewhere else)."""
    pos, q, base = p.anchor("support")
    p.hand_l = (pos + np.asarray(offset, float), q, base if curls is None else curls)


def squeeze(curls, amount, fingers=("index", "middle", "ring", "pinky")):
    """Curl the wrapping fingers a little tighter (negative closes): a grip that takes up a force."""
    return hands.adjust(curls, **{f: (amount, 0.4 * amount) for f in fingers})


# ------------------------------------------------------------------------------------------- idle
I_FING_L = lambda t: hands.adjust(hands.POSES["support"], index=(-3 * np.sin(2 * np.pi * t / 2.0 + 0.7), 0),
                                  middle=(-2 * np.sin(2 * np.pi * t / 2.0 + 0.4), 0))


def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    # weight shifts: slow figure-of-eight drift with the hands settling a hair after the gun
    apply_gun(p, (0.0006 * np.sin(w + 0.6), 0.0009 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0004 * np.sin(w + 1.3)),
              (0.18 * np.sin(w + 0.3), 0.22 * np.sin(w + 0.9), 0.20 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    tr = p.anchor("grip")
    p.fingers_r = hands.adjust(tr[2], index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.hand_l = None
    p.fingers_l = I_FING_L(t)
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# Ripped up from the bottom right: off screen for the first ~0.1 s (the outgoing weapon is still dropping), the muzzle
# leads the raise (rotation overshoots at 0.27, translation at 0.32 - 3-5 deg / 6 mm past the ready pose), a small
# undershoot, settle. The support hand comes up from below-left a beat later and catches the gun at 0.37 (fingers
# open -> clamp with a squeeze); the catch knocks the gun a little. Camera: looks down at the draw, nods with the
# arrival.
T_CATCH = 0.37
DRAW_OFF = Curve((0.00, V(0.052, -0.262, 0.078)), (0.08, V(0.046, -0.205, 0.066)),
                 (0.19, V(0.014, -0.060, 0.016), "flow"), (0.32, V(-0.0035, 0.0062, -0.0050), "stop"),
                 (0.45, V(0.0010, -0.0016, 0.0009), "stop"), (0.60, V(0, 0, 0), "stop"))
DRAW_ROT = Curve((0.00, V(-15, -56, -37)), (0.08, V(-13, -47, -32)), (0.18, V(-3.5, -11, -10), "flow"),
                 (0.27, V(1.8, 4.6, 2.8), "stop"), (0.42, V(-0.5, -1.0, -0.6), "stop"), (0.60, V(0, 0, 0), "stop"))
DRAW_CAM = Curve((0.00, V(0, 0, 0)), (0.16, V(-0.55, 0.0, -0.45)), (0.31, V(0.40, 0.05, 0.40), "stop"),
                 (0.47, V(-0.08, 0, -0.06)), (0.60, V(0, 0, 0)))
# support hand: offset from its anchor (viewmodel space), arrives with a slight overshoot into the grip
DRAW_LW = Curve((0.00, V(-0.060, -0.190, 0.070)), (0.14, V(-0.050, -0.120, 0.050)),
                (0.27, V(-0.016, -0.030, 0.014), "flow"), (T_CATCH, V(0.0015, 0.0025, -0.001), "hit"),
                (0.44, V(0, 0, 0), "stop"), (0.60, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.28, {"spread": 1.0}), (T_CATCH + 0.02, {"support": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.30, {"grip": 1.0}), (0.48, {"indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    knock = ring(t, T_CATCH, 1.0, 9.0, 0.4) * until(t, 0.60, 0.08)       # the catch jolts the gun
    apply_gun(p, DRAW_OFF(t) + knock * V(0.0006, -0.0016, 0.0004), DRAW_ROT(t) + knock * V(-0.4, -0.7, -1.1))
    support_world(p, t, DRAW_LW(t), poses(max(t - FINGER_LAG, 0), DRAW_FL))
    grip = 7.0 * ring(t, T_CATCH + 0.02, 1.0, 6.0, 0.6) * until(t, 0.60, 0.1)
    p.fingers_l = squeeze(p.hand_l[2], -grip)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *(DRAW_CAM(t) + knock * V(-0.25, 0.0, -0.2)))
    return p


DRAW = Clip("draw", 0.60, draw_pose, events=[(0.0, "equip")], extra_times=(T_CATCH,))
DRAW = motion_layer.layered(DRAW, rig, 401)            # section 31.6: the hand-keyed detail layer

# ------------------------------------------------------------------------------------------- fire
# The shot at t = 0: trigger breaks, hammer falls, the slide is blown back to full travel by 0.022 (11 %: the P9A's
# 1050 rpm plays this clip in 0.057 s), dwells on the frame stop, is driven home by its spring (accelerating, a hit at
# 0.078 = 39 %), rebounds 1 mm and settles. The hammer is pushed past cock by the slide and rides down onto the
# sear. The clip's own kick is the hands' reaction (the runtime adds the root recoil): the wrists give back 5 mm,
# the muzzle flips 2.6 deg a beat after (rotation lags the shove), recovers through a small undershoot; the slide
# slamming home dips the muzzle and nudges the gun forward. The support fingers clamp at the shot.
LOCK = rig.SLIDE_LOCK
T_HOME_F = 0.078
F_TRIG = Curve((0.0, 0.0), (0.008, -14.0, "hit"), (0.050, -14.0, "stop"), (0.115, -1.5), (0.200, 0.0))
F_HAMMER = Curve((0.0, rig.HAMMER_COCKED), (0.005, 0.0, "hit"), (0.019, 46.0, "stop"), (0.034, 46.0, "stop"),
                 (T_HOME_F - 0.004, rig.HAMMER_COCKED, "hit"), (0.200, rig.HAMMER_COCKED))
F_HAMMER_LAST = Curve((0.0, rig.HAMMER_COCKED), (0.005, 0.0, "hit"), (0.019, 46.0, "stop"), (0.200, 46.0))
F_SLIDE = Curve((0.0, 0.0, {"out": 2.4 * LOCK / 0.022}), (0.022, LOCK, {"in": 0.5 * LOCK / 0.022}),
                (0.030, LOCK, "stop"), (T_HOME_F, 0.0, ("hit", 2.1)), (0.086, 0.0011, "stop"), (0.097, 0.0, "stop"),
                (0.200, 0.0))
F_SLIDE_LAST = Curve((0.0, 0.0, {"out": 2.4 * LOCK / 0.022}), (0.022, LOCK + 0.0016, {"in": 0.5 * LOCK / 0.022}),
                     (0.031, LOCK - 0.0004, "stop"), (0.040, LOCK, "stop"), (0.200, LOCK))
F_STOP = Curve((0.0, 0.0), (0.026, 0.0), (0.036, rig.STOP_UP + 0.8, "hit"), (0.046, rig.STOP_UP, "stop"),
               (0.200, rig.STOP_UP))
F_PULL = Curve((0.0, -4.0), (0.008, -9.0, "hit"), (0.050, -9.0, "stop"), (0.115, -2.0), (0.200, -1.0))
FIRE_KEYS = sorted(set(F_TRIG.times() + F_HAMMER.times() + F_SLIDE.times() + F_SLIDE_LAST.times() + F_STOP.times()))


def fire_kick(t, last):
    """(offset, euler, camera) of the hands' reaction to the shot, faded to exactly zero by the clip's end."""
    fade = until(t, 0.200, 0.05)
    back = ring(t, 0.0, 0.0052, 10.0, 0.5)
    rise = ring(t, 0.004, 0.0021, 7.0, 0.45)
    flip = ring(t, 0.002, V(0.40, 2.6, -0.70), 6.5, 0.42)
    off = V(0.0004 * float(rise) / 0.0021, float(rise), float(back))
    if not last:                                   # the slide slamming home: muzzle dips, gun nudged forward
        off = off + ring(t, T_HOME_F, V(0.0, -0.0004, -0.0009), 14.0, 0.4)
        flip = flip + ring(t, T_HOME_F, V(0.05, -0.45, 0.10), 13.0, 0.4)
    view = ring(t, 0.002, V(0.38, 0.04, 0.05), 7.0, 0.55)
    return off * fade, flip * fade, view * fade


def fire_pose(last):
    def pose(t):
        p = rig.Pose()
        off, rot, view = fire_kick(t, last)
        apply_gun(p, off, rot)
        p.trigger = float(F_TRIG(t))
        p.hammer = float((F_HAMMER_LAST if last else F_HAMMER)(t))
        p.slide_z = float((F_SLIDE_LAST if last else F_SLIDE)(t))
        if last:
            p.stop = float(F_STOP(t))
        # finger on the trigger, curling with the pull; the support hand clamps at the shot, a beat late
        pull = float(F_PULL(t))
        p.fingers_r = hands.adjust(hands.POSES["trigger"], index=(pull, pull * 0.6))
        clamp = -5.0 * float(ring(t, 0.012, 1.0, 5.0, 0.7)) * until(t, 0.200, 0.06)
        p.fingers_l = squeeze(hands.POSES["support"], clamp)
        cam(p, *view)
        return p
    return pose


FIRE = Clip("fire", 0.20, fire_pose(False), fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", 0.20, fire_pose(True), fps=120, extra_times=FIRE_KEYS)
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line (ads_fire).
# Optics ride the slide, which brings them 34 mm closer to the eye: almost no roll or tilt is left
PISTOL_ADS = {"roll": 0.02, "lateral": 0.01, "max_turn": 0.006}
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.NODES["slide"][1] + rig.AIM_IRON_T, IDLE, **PISTOL_ADS)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.NODES["slide"][1] + rig.AIM_IRON_T, IDLE, **PISTOL_ADS)


def all_clips():
    import p9_clips_long as long
    import p9_loco as loco
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, long.RELOAD, long.RELOAD_EMPTY, long.INSPECT,
            loco.WALK, loco.SPRINT, loco.TAC_SPRINT]


__all__ = ["Clip", "Weights", "apply_gun", "cam", "V", "damped", "all_clips", "ease", "left_hand", "poses"]
