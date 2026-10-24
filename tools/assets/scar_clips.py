"""SCAR-H clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> scar_rig.Pose (palm frames + finger curls); gen_scar bakes
them (IK included) at >= 60 samples/s (fire at 120).

Authored to section 31 with the motion toolkit of the AK (anim.Curve, ring, drift, arc). The SCAR's mechanism:
600 rpm, the carrier (and the charging handle riding on it, left side) runs 108 mm back and slams home; on the last
round it stops on the bolt catch (held open 99 mm back; the runtime keeps it there while the gun is empty, guns.json
slideLock). The magazine goes straight in and out of the flared well (no rocking on a lug): the support hand's thumb
presses the left release, the hand strips the magazine down and lets it fall (gravity, tumbling), fetches the new one
off screen, aligns it slowly under the well and drives it home (hit). The tactical reload ends with a tap on the floor
plate; the empty reload instead brings the hand up to the bolt catch: the thumb presses it and the carrier slams home.
The inspect shows the left flank (charging handle, bolt catch, selector), rolls the port up and press-checks with the
left charging handle (support hand from underneath)."""
import functools

import numpy as np

import ads_fire
import armrig
import hands
import scar_model as sm
import scar_rig as rig
from anim import Curve, arc, drift, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
LIP = np.array([0.0, 0.0, -sm.MAG_HD])            # mag-local front top edge: the magazine tilts about it
TRAVEL = sm.BOLT_TRAVEL
HOLD = sm.BOLT_HOLD
GRAVITY = V(0.0, -9.81, 0.0)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def mag_tilt(theta, offset=(0, 0, 0), extra=(0, 0, 0)):
    """Magazine tilted `theta` deg about its front top edge (+ = bottom forward), then offset (gun space)."""
    q = quat_mul(quat_axis_angle((1, 0, 0), theta), quat_euler(*extra))
    return MAG_REST + LIP - quat_rotate(q, LIP) + np.asarray(offset, float), q


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def fade_end(t, end, width=0.18):
    """1 until `width` before `end`, then down to 0: secondary layers are exactly gone when a clip ends."""
    return 1.0 - smoothstep(end - width, end, t)


class Layers:
    """Base curve + impulse rings + moving-hold drift for one vector channel (gun offset, gun turn, camera)."""

    def __init__(self, base, rings=(), drifts=(), end=None):
        self.base, self.rings, self.drifts, self.end = base, rings, drifts, end

    def __call__(self, t):
        v = np.asarray(self.base(t), float).copy()
        extra = np.zeros_like(v)
        for t0, amp, freq, zeta in self.rings:
            extra = extra + ring(t, t0, amp, freq, zeta)
        for t0, t1, amp, seed, freq in self.drifts:
            extra = extra + drift(t, amp, seed, freq, t0, t1, fade=min(0.2, (t1 - t0) / 3))
        if self.end is not None:
            extra = extra * fade_end(t, self.end)
        return v + extra


def open_fingers(curls, o, pose="spread"):
    if o <= 0:
        return curls
    return {f: (1 - o) * np.asarray(curls[f], float) + o * np.asarray(hands.POSES[pose][f], float)
            for f in hands.FINGERS}


def blend_curls(a, b, w):
    w = float(np.clip(w, 0.0, 1.0))
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


# ------------------------------------------------------------------------------------------------------------ idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.15 * np.sin(w + 0.3), 0.19 * np.sin(w + 0.9), 0.21 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------------------------ draw
# Off screen for the first ~0.12 s (a further dip at 0.06: the hand gathers the rifle), then heaved up muzzle first:
# the turn overshoots at 0.31, the position at 0.39, the support hand slaps onto the bottom rail at 0.43 and the rifle
# takes the catch; settles by 0.62.
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.275, 0.095)), (0.06, V(0.054, -0.288, 0.100)),
                        (0.39, V(-0.006, 0.010, -0.007)), (0.53, V(0.0013, -0.0024, 0.0010)), (0.70, V(0, 0, 0))),
                  rings=[(0.43, V(0.0, -0.0018, 0.0006), 8.0, 0.4)], end=0.70)
DRAW_ROT = Layers(Curve((0.00, V(-16, -48, -30)), (0.06, V(-17, -51, -32)), (0.31, V(2.2, 4.4, 3.2)),
                        (0.47, V(-0.6, -1.1, -0.8)), (0.61, V(0.15, 0.2, 0.1)), (0.70, V(0, 0, 0))),
                  rings=[(0.43, V(0.2, -1.0, 0.6), 8.0, 0.4)], end=0.70)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.25, 0, 0.15)), (0.33, V(-0.85, 0.1, -0.6)),
                        (0.49, V(0.35, 0, 0.25)), (0.70, V(0, 0, 0))),
                  rings=[(0.43, V(-0.35, 0.0, 0.25), 7.0, 0.4)], end=0.70)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.28, V(-0.010, -0.034, 0.024)), (0.43, V(0, 0.003, 0)),
                (0.51, V(0, -0.0008, 0)), (0.59, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.33, {"spread": 1.0}), (0.44, {"scar_clamp": 1.0}),
                  (0.55, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.30, {"grip": 1.0}), (0.50, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.70, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------------------------ fire
# 600 rpm: the piston kicks the carrier back (snap start, a soft bump on the buffer at 0.026), the recoil spring
# drives it home (accelerating, a hit at 0.074 with a 1.6 mm bounce). The charging handle rides along on the left. The
# clip's own kick is the hands' reaction (the runtime adds the root recoil); the 7.62x51 shoves harder than the AK.
# fire_last: the carrier comes forward only onto the bolt catch (hit at 0.050, held 99 mm back) and the catch rises.
F_BOLT = Curve((0.0, 0.0), (0.004, 0.0, ("snap", 2.4)), (0.026, TRAVEL, ("hit", 0.9)), (0.032, TRAVEL - 0.0006),
               (0.074, 0.0, ("hit", 2.2)), (0.081, 0.0016, "stop"), (0.089, 0.0, "stop"), (0.100, 0.0))
F_BOLT_LAST = Curve((0.0, 0.0), (0.004, 0.0, ("snap", 2.4)), (0.026, TRAVEL, ("hit", 0.9)), (0.032, TRAVEL - 0.0006),
                    (0.050, HOLD, ("hit", 1.6)), (0.057, HOLD + 0.0012, "stop"), (0.066, HOLD, "stop"), (0.100, HOLD))
F_CATCH_LAST = Curve((0.0, 0.0), (0.030, 0.0), (0.042, rig.CATCH_UP, ("hit", 1.5)), (0.100, rig.CATCH_UP))
F_TRIG = Curve((0.0, 7.0), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.055, rig.TRIGGER_PULL), (0.092, 7.0),
               (0.100, 7.0))
_FIRE_END = 0.100


def _fire_kick(t, last):
    """Hands' reaction: offset (m) and turn (deg) of the rifle in the hands."""
    k = fade_end(t, _FIRE_END, 0.03)
    t_home = 0.050 if last else 0.074
    s = 0.5 if last else 1.0
    off = ring(t, 0.001, V(0.0004, 0.0018, 0.0058), 12.0, 0.5) + \
        ring(t, t_home, V(0.0, -0.0004, -0.0011) * s, 14.0, 0.5)
    rot = ring(t, 0.001, V(0.25, 1.7, -0.50), 11.0, 0.5) + ring(t, t_home, V(0.0, -0.35, 0.1) * s, 14.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + [0.012, 0.02, 0.078]))
FIRE_LAST_KEYS = sorted(set(F_BOLT_LAST.times() + F_TRIG.times() + F_CATCH_LAST.times() + [0.012, 0.02]))


def fire_pose(last):
    def pose(t):
        p = rig.Pose()
        apply_gun(p, *_fire_kick(t, last))
        p.bolt_z = float((F_BOLT_LAST if last else F_BOLT)(t))
        if last:
            p.catch = float(F_CATCH_LAST(t))
        p.trigger = float(F_TRIG(t))
        k = float(F_TRIG(t)) / rig.TRIGGER_PULL                   # finger pad rides the trigger back
        p.fingers_r = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
        clamp = -5.0 * float(ring(t, 0.012, 1.0, 9.0, 0.6)) * fade_end(t, _FIRE_END, 0.03)
        p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                                   thumb=(0.5 * clamp, 0))
        c = ring(t, 0.002, V(0.34, 0.04, 0.09), 9.0, 0.5) * fade_end(t, _FIRE_END, 0.035)
        cam(p, *c)
        return p
    return pose


FIRE = Clip("fire", _FIRE_END, fire_pose(False), fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", _FIRE_END, fire_pose(True), fps=120, extra_times=FIRE_LAST_KEYS)
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------------------------- magazine change
# beats (both reloads): let go 0.06 -> the rifle dips and rolls left (anticipation) -> rolls right into the
# presentation (overshoot 0.36, settle 0.52) -> the hand closes round the magazine, the thumb presses the left release
# 0.38 (mag_release) -> the hand strips the magazine straight down out of the well (mag_out 0.50, tilting a little as
# it clears) and lets it fall at 0.56 -> dives off screen for the new one (shown at 0.94) -> brings it up on an arc,
# decelerating -> aligns it slowly under the flared well (1.30 .. 1.46, a hair of wind-up) -> drives it straight
# home: hit at 1.56 (mag_in), the rifle and the camera jolt.
T_LETGO, T_RELEASE, T_OUT, T_DROP = 0.06, 0.38, 0.50, 0.56
T_SHOW, T_ALIGN, T_HOOK, T_SEAT = 0.94, 1.32, 1.46, 1.56
T_HIDE = 0.82
AWAY = V(-0.080, -0.330, 0.110)                       # off-screen spot (gun space) where the new magazine starts
CLEAR = -0.078                                         # the magazine's top just under the well's lip

MAG_THETA_OUT = Curve((T_RELEASE, 0.0), (0.44, 0.6), (T_OUT, 3.5), (T_DROP, 9.0, ("hit", 1.0)))
MAG_OFF_OUT = Curve((T_RELEASE, V(0, 0, 0)), (0.41, V(0, -0.0025, 0.0)), (T_OUT, V(0.0, -0.052, 0.001)),
                    (T_DROP, V(-0.004, CLEAR - 0.012, 0.004), ("hit", 1.0)))
MAG_THETA_IN = Curve((T_SHOW, 30.0), (1.14, 18.0), (T_ALIGN, 5.0), (1.42, 1.6), (T_HOOK, 1.2), (1.50, 0.4),
                     (T_SEAT, 0.0, ("hit", 2.0)))
MAG_OFF_IN = Curve((T_SHOW, AWAY), (1.14, V(-0.030, -0.170, 0.034)), (T_ALIGN, V(-0.003, CLEAR - 0.016, 0.002)),
                   (1.42, V(-0.0006, CLEAR - 0.004, 0.0004)), (T_HOOK, V(0, CLEAR + 0.004, 0)),
                   (T_SEAT, V(0, 0, 0), ("hit", 2.4)), (T_SEAT + 0.030, V(0, -0.0011, 0), "stop"),
                   (T_SEAT + 0.065, V(0, 0, 0), "stop"))
MAG_SPIN_IN = Curve((T_SHOW, V(10, -20, 35)), (1.14, V(6, -12, 20)), (1.36, V(0, 0, 0)))
TOSS = V(-0.16, -0.30, 0.06)
TUMBLE = V(3.0, 1.0, -2.2)                            # rad/s about the world axes


def mag_held_out(t):
    return mag_tilt(float(MAG_THETA_OUT(t)), MAG_OFF_OUT(t))


def mag_world(gun_off, gun_rot, mag_t, mag_q):
    p = rig.Pose()
    apply_gun(p, gun_off, gun_rot)
    return p.gun_point(mag_t), quat_mul(p.gun_q, mag_q)


@functools.lru_cache(maxsize=None)
def _drop_state(clip):
    """World position, velocity and orientation of the magazine as the hand lets go (per reload clip)."""
    off, rot = RELOADS[clip]
    pos = lambda t: mag_world(off(t), rot(t), *mag_held_out(t))
    p0, q0 = pos(T_DROP)
    p1, _ = pos(T_DROP - 0.012)
    return p0, (p0 - p1) / 0.012 * 0.6 + TOSS, q0


def mag_swap(p, t, clip):
    if t < T_RELEASE:
        return
    if t < T_DROP:
        p.mag_t, p.mag_q = mag_held_out(t)
    elif t < T_SHOW:
        p0, v0, q0 = _drop_state(clip)
        tau = t - T_DROP
        pw = p0 + v0 * tau + 0.5 * GRAVITY * tau * tau
        ang = np.linalg.norm(TUMBLE) * tau
        qw = quat_mul(quat_axis_angle(TUMBLE, np.degrees(ang)), q0) if ang > 1e-9 else q0
        inv = quat_conj(p.gun_q)
        p.mag_t = quat_rotate(inv, pw - p.gun_t)
        p.mag_q = quat_mul(inv, qw)
        if t >= T_HIDE:
            p.mag_s = 0.0
    else:
        p.mag_t, p.mag_q = mag_tilt(float(MAG_THETA_IN(t)), MAG_OFF_IN(t), MAG_SPIN_IN(t))


# support hand ---------------------------------------------------------------------------------------------------
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (0.31, {"mag_release": 1}))
L_OUT_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.18, V(-0.024, -0.030, 0.020), "flow"),
                  (0.31, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.13, 1.0), (0.23, 0.85), (0.31, 0.0))
THUMB_PRESS = Curve((0.0, 0.0), (0.33, 0.0), (T_RELEASE, -16.0, ("hit", 1.6)), (0.43, -6.0), (T_OUT, 0.0))
L_IN = Weights((T_SHOW, {"mag_low": 1}), (T_SEAT + 0.015, {"mag_low": 1}))
FETCH_W = Curve((T_DROP, 0.0), (0.88, 1.0), (T_SHOW, 1.0))
FETCH_ARC = V(-0.030, -0.070, 0.030)


def _left_out(p, t):
    """Support hand leaving the handguard, closing round the magazine, thumbing the release and stripping it out."""
    left_hand(p, L_OUT, t, L_OUT_OFF(t))
    pos, q, curls = p.hand_l
    curls = hands.adjust(open_fingers(curls, float(L_OPEN(t))), thumb=(float(THUMB_PRESS(t)), 0.0))
    p.hand_l = (pos, q, curls)


@functools.lru_cache(maxsize=None)
def _release_hand(clip):
    off, rot = RELOADS[clip]
    p = rig.Pose()
    apply_gun(p, off(T_DROP), rot(T_DROP))
    p.mag_t, p.mag_q = mag_held_out(T_DROP)
    _left_out(p, T_DROP)
    return p.hand_l


def _new_mag_anchor(p, t):
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    q.mag_t, q.mag_q = mag_tilt(float(MAG_THETA_IN(T_SHOW)), MAG_OFF_IN(T_SHOW), MAG_SPIN_IN(T_SHOW))
    return q.anchor("mag_low")


def _left_fetch(p, t, clip):
    rel = _release_hand(clip)
    new = _new_mag_anchor(p, t)
    w = float(FETCH_W(t))
    pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - T_DROP) / (0.88 - T_DROP)), FETCH_ARC)
    open_w = smoothstep(T_DROP, T_DROP + 0.05, t) * (1 - smoothstep(0.84, 0.90, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, q, curls)


def mag_hands(p, t, clip):
    if t < T_DROP:
        _left_out(p, t)
    elif t < T_SHOW:
        _left_fetch(p, t, clip)
    else:
        left_hand(p, L_IN, t)


# ---------------------------------------------------------------------------------------------------------- reload
# after the seat: the hand slides down the magazine, drops 2 cm (wind-up) and taps the floor plate (1.72), then swings
# back to the bottom rail (2.06) with a squeeze; the rifle swings back to ready past the rest pose and settles (2.5).
T_SLAP = 1.72
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0042, 0.0016)), (0.34, V(-0.0412, 0.0312, -0.0050)),
                     (0.52, V(-0.0395, 0.0290, -0.0030)), (0.94, V(-0.0405, 0.0303, -0.0040)),
                     (1.30, V(-0.0400, 0.0316, -0.0060)), (T_HOOK, V(-0.0394, 0.0302, -0.0050)),
                     (T_SEAT, V(-0.0386, 0.0286, -0.0022)), (1.76, V(-0.0400, 0.0322, -0.0060)),
                     (2.08, V(0.0030, -0.0042, 0.0012)), (2.30, V(-0.0008, 0.0010, -0.0003)), (2.50, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0012, 0.0), 10.0, 0.4), (0.46, V(0.0, -0.0022, -0.0012), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0, 0.0046, 0.0012), 7.5, 0.35), (T_SLAP, V(0.0, 0.0030, 0.0), 9.0, 0.35),
                      (2.06, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=2.50)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.1)), (0.36, V(4.4, 8.0, -24.0)),
                     (0.52, V(4.0, 7.2, -21.8)), (0.94, V(4.2, 7.6, -22.6)), (1.30, V(3.6, 8.8, -21.0)),
                     (T_HOOK, V(3.7, 8.4, -20.6)), (T_SEAT, V(3.6, 6.8, -19.4)), (1.76, V(2.8, 7.0, -23.0)),
                     (2.10, V(-0.6, -1.0, 2.8)), (2.32, V(0.2, 0.3, -0.6)), (2.50, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.8, 0.5), 10.0, 0.4), (0.46, V(0.0, -1.2, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.8, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-0.4, 2.8, 1.4), 7.5, 0.35), (T_SLAP, V(0.0, 1.6, -0.8), 9.0, 0.35),
                      (2.06, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.50)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (1.30, V(-0.5, 0.2, -1.2)), (1.56, V(-0.4, 0.1, -1.0)),
                     (1.82, V(-0.5, 0.1, -1.3)), (2.14, V(0.3, -0.1, 0.7)), (2.50, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.25, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-1.3, 0.15, 0.9), 6.5, 0.35),
                      (T_SLAP, V(-0.8, 0.0, -0.5), 8.0, 0.35)], end=2.50)
L_SLAP = Weights((T_SEAT + 0.015, {"mag_low": 1}), (1.67, {"slap": 1}), (1.78, {"slap": 1}),
                 (2.06, {"support": 1}))
L_SLAP_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.67, V(0.0, -0.022, 0.004)), (T_SLAP, V(0.0, 0.002, 0.0), "hit"),
                   (1.78, V(0.0, -0.004, 0.0)), (1.90, V(-0.020, -0.030, 0.014), "flow"), (2.06, V(0, 0, 0)))
L_SLAP_OPEN = Curve((T_SEAT + 0.015, 0.0), (1.65, 1.0), (1.82, 1.0), (1.98, 0.0))
L_REGRIP = Curve((2.00, 0.0), (2.08, 1.0), (2.20, 0.0))                    # squeeze as it takes the rail
SH_MAG = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(-0.110, -0.020, 0.100)),
               (T_SEAT + 0.10, V(-0.110, -0.020, 0.100)), (2.14, V(0, 0, 0)))


# the SCAR's well sits low under the long receiver: the presentation lifts the rifle higher and turns the muzzle a
# little further left than the AK's, so the magazine work happens in the lower middle of the screen
LIFT_R = Curve((0.00, V(0, 0, 0, 0)), (0.10, V(0, 0, 0, 0)), (0.38, V(-0.012, 0.024, 0.0, 4.0)),
               (1.80, V(-0.012, 0.024, 0.0, 4.0)), (2.12, V(0, 0, 0, 0)))
LIFT_E = Curve((0.00, V(0, 0, 0, 0)), (0.10, V(0, 0, 0, 0)), (0.38, V(-0.012, 0.024, 0.0, 4.0)),
               (2.06, V(-0.012, 0.024, 0.0, 4.0)), (2.52, V(0, 0, 0, 0)))


def lifted(off, rot, lift, t):
    v = lift(t)
    return off(t) + v[:3], rot(t) + V(v[3], 0.0, 0.0)


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, *lifted(R_OFF, R_ROT, LIFT_R, t))
    p.shoulder_l = SH_MAG(t)
    mag_swap(p, t, "reload")
    if t < T_SEAT + 0.015:
        mag_hands(p, t, "reload")
    else:
        left_hand(p, L_SLAP, t, L_SLAP_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(L_SLAP_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["scar_clamp"], float(L_REGRIP(t)) * 0.8)
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.0, 2.3)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", 2.50, reload_pose,
              events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_SLAP))

# ---------------------------------------------------------------------------------------------------- reload_empty
# the carrier starts locked open on the catch. Same magazine change; then the hand leaves the seated magazine and rises
# along the well, the rifle rolls a little further so the left flank faces the eye; the thumb finds the bolt catch
# (1.80), presses it (1.90, the paddle snaps down): the carrier slams home (1.93, bolt_forward) - the rifle is shoved
# and nods, the camera jolts - and the hand swings back to the rail while the rifle returns to ready.
T_REACH, T_PRESS, T_FWD = 1.80, 1.90, 1.93
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0042, 0.0016)), (0.34, V(-0.0412, 0.0312, -0.0050)),
                     (0.52, V(-0.0395, 0.0290, -0.0030)), (0.94, V(-0.0405, 0.0303, -0.0040)),
                     (1.30, V(-0.0400, 0.0316, -0.0060)), (T_HOOK, V(-0.0394, 0.0302, -0.0050)),
                     (T_SEAT, V(-0.0386, 0.0286, -0.0022)), (T_REACH, V(-0.0420, 0.0330, -0.0120)),
                     (T_PRESS, V(-0.0414, 0.0322, -0.0112)), (T_FWD, V(-0.0408, 0.0316, -0.0100)),
                     (2.02, V(-0.0418, 0.0330, -0.0130)), (2.52, V(0.0030, -0.0042, 0.0012)),
                     (2.76, V(-0.0006, 0.0008, -0.0002)), (3.00, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0012, 0.0), 10.0, 0.4), (0.46, V(0.0, -0.0022, -0.0012), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0, 0.0046, 0.0012), 7.5, 0.35), (T_PRESS, V(0.0, 0.0006, 0.0010), 10.0, 0.4),
                      (T_FWD, V(0.0, -0.0032, -0.0055), 7.0, 0.32), (2.50, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=3.00)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.1)), (0.36, V(4.4, 8.0, -24.0)),
                     (0.52, V(4.0, 7.2, -21.8)), (0.94, V(4.2, 7.6, -22.6)), (1.30, V(3.6, 8.8, -21.0)),
                     (T_HOOK, V(3.7, 8.4, -20.6)), (T_SEAT, V(3.6, 6.8, -19.4)), (T_REACH, V(5.0, 7.8, -27.0)),
                     (T_PRESS, V(4.8, 7.6, -26.2)), (T_FWD, V(4.6, 7.2, -25.6)), (2.04, V(5.0, 7.6, -27.0)),
                     (2.54, V(-0.6, -1.0, 3.0)), (2.78, V(0.2, 0.3, -0.5)), (3.00, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.8, 0.5), 10.0, 0.4), (0.46, V(0.0, -1.2, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.8, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-0.4, 2.8, 1.4), 7.5, 0.35), (T_PRESS, V(0.0, 0.5, 0.8), 10.0, 0.4),
                      (T_FWD, V(0.8, -3.0, -2.0), 7.0, 0.32), (2.50, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=3.00)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (1.30, V(-0.5, 0.2, -1.2)), (1.56, V(-0.4, 0.1, -1.0)),
                     (T_REACH, V(-0.4, 0.2, -1.7)), (T_FWD, V(-0.3, 0.2, -1.5)), (2.14, V(-0.4, 0.1, -1.4)),
                     (2.56, V(0.3, -0.1, 0.8)), (3.00, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.25, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-1.3, 0.15, 0.9), 6.5, 0.35),
                      (T_FWD, V(1.6, -0.3, -1.4), 6.5, 0.32)], end=3.00)
E_BOLT = Curve((0.0, HOLD), (T_PRESS, HOLD), (T_PRESS + 0.012, HOLD - 0.0015), (T_FWD, 0.0, ("hit", 2.4)),
               (T_FWD + 0.010, 0.0016, "stop"), (T_FWD + 0.024, 0.0, "stop"), (3.00, 0.0))
E_CATCH = Curve((0.0, rig.CATCH_UP), (T_PRESS - 0.03, rig.CATCH_UP), (T_PRESS, -1.5, ("hit", 1.6)),
                (T_PRESS + 0.06, -1.2, "stop"), (T_PRESS + 0.14, 0.0), (3.00, 0.0))
E_LEFT = Weights((T_SEAT + 0.015, {"mag_low": 1}), (T_REACH, {"catch": 1}), (T_PRESS + 0.04, {"catch": 1}),
                 (2.22, {"world": 1}), (2.50, {"support": 1}))
E_LEFT_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.68, V(-0.016, -0.014, 0.010), "flow"), (T_REACH, V(0, 0, 0)),
                   (T_PRESS - 0.03, V(0.0, 0.0, 0.0)), (T_PRESS, V(0.0020, -0.0010, 0.0)),
                   (T_PRESS + 0.04, V(0.0015, -0.0008, 0.0)), (2.16, V(0.004, -0.022, 0.026), "flow"),
                   (2.50, V(0, 0, 0)))
E_THUMB = Curve((0.0, 0.0), (T_PRESS - 0.04, 0.0), (T_PRESS, -10.0, ("hit", 1.6)), (T_PRESS + 0.08, -6.0),
                (2.10, 0.0))
E_FINGERS = Curve((T_SEAT + 0.015, 0.0), (1.66, 0.6), (T_REACH - 0.03, 0.0), (T_PRESS + 0.05, 0.0),
                  (T_PRESS + 0.12, 0.9), (2.32, 0.9), (2.46, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(-0.110, -0.020, 0.100)),
                 (T_SEAT + 0.06, V(-0.110, -0.020, 0.100)), (T_REACH, V(-0.090, 0.000, 0.080)),
                 (T_PRESS + 0.08, V(-0.090, 0.000, 0.080)), (2.52, V(0, 0, 0)))


def let_go(p):
    """Off-hand spot just below and behind the bolt catch (the hand springs off it)."""
    pos, q, _ = p.anchor("catch")
    return pos + p.gun_dir(V(-0.012, -0.040, 0.030)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, *lifted(E_OFF, E_ROT, LIFT_E, t))
    p.shoulder_l = SH_EMPTY(t)
    mag_swap(p, t, "reload_empty")
    p.bolt_z = float(E_BOLT(t))
    p.catch = float(E_CATCH(t))
    if t < T_SEAT + 0.015:
        mag_hands(p, t, "reload_empty")
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        curls = hands.adjust(open_fingers(curls, float(E_FINGERS(t))), thumb=(float(E_THUMB(t)), 0.0))
        curls = blend_curls(curls, hands.POSES["scar_clamp"], 0.8 * float(L_REGRIP(t - 0.44)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.4, 2.7)), 0))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 3.00, reload_empty_pose,
                    events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in"),
                            (T_FWD, "bolt_forward")],
                    extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_PRESS, T_FWD, T_FWD + 0.010))
RELOADS = {"reload": (lambda t: lifted(R_OFF, R_ROT, LIFT_R, t)[0], lambda t: lifted(R_OFF, R_ROT, LIFT_R, t)[1]),
           "reload_empty": (lambda t: lifted(E_OFF, E_ROT, LIFT_E, t)[0],
                            lambda t: lifted(E_OFF, E_ROT, LIFT_E, t)[1])}

# --------------------------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a heavy swing round to show the left flank: charging handle, bolt catch, selector (overshoot
# 0.56, settle 0.74) -> moving hold -> a counter-roll (1.52) and the rifle rolls over, port up (overshoot 1.92) -> the
# support hand reaches under to the charging handle, pulls the carrier back a little (2.42, bolt_back), holds while the
# rifle tips to look into the port, lets go: the carrier slams home (2.685, bolt_forward), the rifle jolts -> the hand
# swings back to the rail, the rifle rolls back to ready past the rest pose and settles.
I_BACK, I_LET, I_FWD = 2.42, 2.64, 2.685
I_A_OFF, I_A_ROT = V(-0.0558, 0.0228, -0.0596), V(51.5, 7.8, -11.6)
I_B_OFF, I_B_ROT = V(-0.0833, -0.0770, -0.0690), V(11.0, 33.0, 80.0)
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.56, V(-0.0582, 0.0250, -0.0620)),
                     (0.74, I_A_OFF), (1.10, V(-0.0566, 0.0240, -0.0606)), (1.42, V(-0.0590, 0.0262, -0.0635)),
                     (1.52, V(-0.0575, 0.0250, -0.0620)), (1.92, V(-0.0842, -0.0792, -0.0702)), (2.06, I_B_OFF),
                     (I_BACK, V(-0.0846, -0.0762, -0.0672)), (I_LET, V(-0.0850, -0.0758, -0.0668)),
                     (I_FWD, V(-0.0835, -0.0780, -0.0690)), (3.05, V(-0.0828, -0.0800, -0.0660)),
                     (3.62, V(0.0030, -0.0042, 0.0015)), (3.82, V(-0.0006, 0.0008, -0.0003)), (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.0, -0.0014, 0.0004), 6.0, 0.45), (I_BACK, V(0.0, 0.0008, 0.0016), 9.0, 0.4),
                      (I_FWD, V(0.0, -0.0022, -0.0034), 7.5, 0.35), (3.24, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.0010, 0.0009, 0.0007), 21, 0.6), (2.04, 2.30, V(0.0005, 0.0005, 0.0004), 22, 0.9),
                       (2.42, 2.66, V(0.0004, 0.0004, 0.0003), 23, 0.9)], end=4.00)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, 2.2)), (0.56, V(55.5, 8.8, -13.5)), (0.74, I_A_ROT),
                     (1.10, V(49.5, 9.0, -8.0)), (1.42, V(48.0, 10.0, -9.5)), (1.52, V(49.0, 9.0, -14.0)),
                     (1.92, V(11.5, 34.0, 85.0)), (2.06, I_B_ROT), (I_BACK, V(10.0, 34.0, 82.0)),
                     (I_LET, V(10.5, 33.5, 83.5)), (I_FWD, V(11.0, 33.0, 81.0)), (3.05, V(11.5, 32.0, 79.0)),
                     (3.62, V(-0.8, -1.2, -3.2)), (3.82, V(0.2, 0.3, 0.6)), (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.2, -0.9, 0.7), 6.0, 0.45), (I_BACK, V(0.0, 0.8, 0.8), 9.0, 0.4),
                      (I_FWD, V(0.5, -1.8, -1.5), 7.5, 0.35), (3.24, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.6, 0.7, 1.0), 31, 0.6), (2.04, 2.30, V(0.3, 0.3, 0.4), 32, 0.9),
                       (2.42, 2.66, V(0.3, 0.3, 0.3), 33, 0.9)], end=4.00)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.56, V(0.5, -0.4, 1.2)), (1.42, V(0.3, -0.2, 0.7)),
                     (1.92, V(-0.4, 0.5, -1.8)), (I_BACK, V(-0.2, 0.3, -1.3)), (I_LET, V(-0.3, 0.3, -1.5)),
                     (3.10, V(-0.2, 0.2, -1.0)), (3.66, V(0.2, -0.1, 0.5)), (4.00, V(0, 0, 0))),
               rings=[(I_FWD, V(1.0, -0.2, -0.9), 7.0, 0.35)], end=4.00)
I_BOLT = Curve((0.0, 0.0), (2.24, 0.0, "stop"), (2.32, 0.012), (I_BACK, 0.036, ("hit", 1.3)), (I_LET, 0.0352, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0009, "stop"), (I_FWD + 0.020, 0.0, "stop"), (4.0, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (2.06, {"support": 1}), (2.24, {"charging": 1}), (I_LET, {"charging": 1}),
                 (2.86, {"world": 1}), (3.22, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (2.06, V(0, 0, 0)), (2.15, V(-0.012, -0.016, 0.020), "flow"), (2.24, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.90, V(0.004, -0.018, 0.026), "flow"), (3.22, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (2.06, 0.0), (2.13, 1.0), (2.17, 1.0), (2.25, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (3.02, 1.0), (3.20, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(0.0, 0.12, -0.12)), (1.48, V(0.0, 0.12, -0.12)),
               (1.92, V(0.013, -0.065, 0.014)), (3.08, V(0.013, -0.065, 0.014)), (3.62, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(-0.020, 0.010, -0.050)), (1.48, V(-0.020, 0.010, -0.050)),
               (1.92, V(0.010, -0.020, -0.050)), (3.08, V(0.010, -0.020, -0.050)), (3.62, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.56, V(1.0, 0.0, 0.3)), (1.48, V(1.0, 0.0, 0.3)),
                 (1.92, V(0.39, 0.18, 0.90)), (3.08, V(0.39, 0.18, 0.90)), (3.62, rig.POLE_R))


def charge_let_go(p):
    """Off-hand spot just under the released charging handle (it springs open there)."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.030, -0.020, 0.030)), q, hands.blend({"spread": 1.0})


# the SCAR is 0.1 m longer than the AK: both showcase poses sit lower and further right, the port-up pose with less
# muzzle rise, so the long upper never parks over the crosshair (added on the AK-pattern curves with smooth windows
# that open and close during the swings)
DA_OFF, DA_ROT = V(0.012, -0.030, 0.0), V(-8.0, -2.0, 0.0)
DB_OFF, DB_ROT = V(0.016, -0.010, 0.0), V(0.0, -15.0, 0.0)


def _showcase(t):
    wa = smoothstep(0.10, 0.56, t) * (1 - smoothstep(1.52, 1.92, t))
    wb = smoothstep(1.52, 1.92, t) * (1 - smoothstep(3.05, 3.62, t))
    return wa * DA_OFF + wb * DB_OFF, wa * DA_ROT + wb * DB_ROT


def inspect_pose(t):
    p = rig.Pose()
    do, dr = _showcase(t)
    apply_gun(p, I_OFF(t) + do, I_ROT(t) + dr)
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=charge_let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_FINGERS(t)))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.8, 2.0, t)
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    curls = blend_curls(curls, hands.POSES["scar_clamp"], 0.7 * float(L_REGRIP(t - 1.24)))
    p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.7, 4.0, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.10, "inspect_move"), (1.52, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (3.05, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))


# ------------------------------------------------------------------------------------------------------ locomotion
def punch_left(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0)):
    """The free fist driven forward and back like a runner's arm, wrist and fingers lagging the stroke."""
    w = 2 * np.pi / dur
    c = np.cos(w * t)
    drive = np.sign(c) * np.abs(c) ** 0.7
    pos = centre - swing * drive + bulge * np.sin(w * t)
    lag = np.sin(w * (t - 0.04))
    curls = hands.adjust(hands.POSES["fist"], index=(5 * lag, 0), middle=(3 * lag, 0), pinky=(-4 * lag, 0))
    return pos, None, curls


FOOT = Curve((0.0, 0.25), (0.14, -1.0), (0.56, 0.45), (1.0, 0.25), loop=True)
FOOT_WALK = Curve((0.0, 0.20), (0.20, -1.0), (0.62, 0.40), (1.0, 0.20), loop=True)


def foot(t, period, lag=0.0, curve=FOOT):
    return float(curve(((t - lag) % period) / period))


WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.07, FOOT_WALK)
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.11))
    apply_gun(p, V(0.0038 * side, 0.0006 + 0.0052 * b, -0.0016 * b - 0.0010 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.04)), 0.15 + 0.78 * bl, 1.45 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)


def gun_frame(muzzle, top):
    """Gun orientation from the muzzle direction and the direction its top faces (viewmodel space)."""
    z = -norm(muzzle)
    y = norm(np.asarray(top, float) - z * np.dot(top, z))
    return frame_quat(np.cross(y, z), y, z)


SPRINT_DUR = 0.66
LOW_T = V(0.150, -0.110, -0.300)
LOW_Q = gun_frame((-0.30, -0.60, -0.74), (-0.30, 0.70, -0.55))
LOW_SHOULDER = V(0.080, 0.160, -0.100)
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    half = SPRINT_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.05)
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))
    p.gun_t = LOW_T + V(0.006 * side, 0.010 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.2 * bl + 3.2 * swing, 3.6 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.155, -0.300)
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.045)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.012 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.0 * np.sin(w * (t - 0.03)), 3.0 * bl, 3.6 * np.cos(w * (t - 0.075))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
