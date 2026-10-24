"""AK-47 clips (VIEWMODEL_SPEC sections 4, 15-18 and 31): idle, draw, fire, fire_last, reload, reload_empty, inspect,
walk, sprint, tac_sprint. Each clip maps time -> ak_rig.Pose (palm frames + finger curls); gen_ak47 bakes them
(IK included) at >= 60 samples/s (fire at 120).

Authored to section 31 with the v3 motion toolkit: the weapon moves on C1 curves (anim.Curve) instead of eased
segments that stop at every key, every push of a hand rings through the rifle (anim.ring) and the camera, holds
drift (anim.drift), big moves anticipate, overshoot and settle, and the rifle is heavier than the pistol (slower big
moves, longer follow-through). The old magazine is rocked out and dropped (it falls under gravity, tumbling); the new
one is fetched off screen, aligned slowly on its front lug and rocked home with a hit. The empty reload adds a
reach-under pull of the charging handle: slowly back against the spring, let go, the carrier slams home."""
import functools

import numpy as np

import ads_fire
import ak_model as am
import ak_rig as rig
import armrig
import hands
import motion_layer
from anim import Curve, arc, drift, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
LIP = am.MAG_FRONT_LIP
TRAVEL = am.BOLT_TRAVEL
GRAVITY = V(0.0, -9.81, 0.0)                        # viewmodel space, level view

# a fist that squeezes harder than "fist" (sprint pumping) and a support hand clamped on the handguard
hands.POSES.setdefault("ak_clamp", {"thumb": (-20, -12), "index": (-58, -54), "middle": (-64, -60),
                                    "ring": (-68, -64), "pinky": (-72, -66)})


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def mag_rock(theta, offset=(0, 0, 0), extra=(0, 0, 0)):
    """Magazine rotated `theta` deg about its front lug (+ = bottom forward), then offset (gun space)."""
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
    """Blend finger curls towards an open hand (o = 0..1)."""
    if o <= 0:
        return curls
    return {f: (1 - o) * np.asarray(curls[f], float) + o * np.asarray(hands.POSES[pose][f], float)
            for f in hands.FINGERS}


def blend_curls(a, b, w):
    w = float(np.clip(w, 0.0, 1.0))
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# Off screen for the first ~0.15 s (a small further dip at 0.06: the hand gathers the rifle), then whipped up: the
# muzzle leads (the turn overshoots at 0.30, the position at 0.38), the support hand slaps onto the handguard at 0.42
# and the rifle takes the catch; settles by 0.60.
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.270, 0.090)), (0.06, V(0.053, -0.283, 0.095)),
                        (0.38, V(-0.006, 0.010, -0.007)), (0.52, V(0.0013, -0.0024, 0.0010)), (0.70, V(0, 0, 0))),
                  rings=[(0.42, V(0.0, -0.0016, 0.0006), 8.0, 0.4)], end=0.70)
DRAW_ROT = Layers(Curve((0.00, V(-16, -48, -30)), (0.06, V(-17, -51, -32)), (0.30, V(2.2, 4.2, 3.2)),
                        (0.46, V(-0.6, -1.1, -0.8)), (0.60, V(0.15, 0.2, 0.1)), (0.70, V(0, 0, 0))),
                  rings=[(0.42, V(0.2, -0.9, 0.6), 8.0, 0.4)], end=0.70)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.25, 0, 0.15)), (0.32, V(-0.85, 0.1, -0.6)),
                        (0.48, V(0.35, 0, 0.25)), (0.70, V(0, 0, 0))),
                  rings=[(0.42, V(-0.35, 0.0, 0.25), 7.0, 0.4)], end=0.70)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.28, V(-0.010, -0.034, 0.024)), (0.42, V(0, 0.003, 0)),
                (0.50, V(0, -0.0008, 0)), (0.58, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.32, {"spread": 1.0}), (0.43, {"ak_clamp": 1.0}),
                  (0.54, {"handguard": 1.0}))
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

# ------------------------------------------------------------------------------------------- fire
# 600 rpm: the gas kicks the carrier back (snap start, a soft bump at the rear), the recoil spring drives it home
# (accelerating, a hit at 0.073 with a 1.6 mm bounce). The clip's own kick is the hands' reaction (the runtime adds the
# root recoil): the wrists give a few mm and ~1.5 deg and ring back, the carrier slamming home nods the muzzle; the
# support fingers clamp a frame after the shot. Starts and ends on the same trigger / finger state so automatic fire
# chains without a jump.
F_BOLT = Curve((0.0, 0.0), (0.004, 0.0, ("snap", 2.4)), (0.025, TRAVEL, ("hit", 0.9)), (0.031, TRAVEL - 0.0006),
               (0.073, 0.0, ("hit", 2.2)), (0.080, 0.0016, "stop"), (0.088, 0.0, "stop"), (0.100, 0.0))
F_TRIG = Curve((0.0, 7.0), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.055, rig.TRIGGER_PULL), (0.092, 7.0),
               (0.100, 7.0))
_FIRE_END = 0.100


def _fire_kick(t):
    """Hands' reaction: offset (m) and turn (deg) of the rifle in the hands."""
    k = fade_end(t, _FIRE_END, 0.03)
    off = ring(t, 0.001, V(0.0004, 0.0016, 0.0052), 12.0, 0.5) + ring(t, 0.073, V(0.0, -0.0004, -0.0011), 14.0, 0.5)
    rot = ring(t, 0.001, V(0.25, 1.5, -0.45), 11.0, 0.5) + ring(t, 0.073, V(0.0, -0.35, 0.1), 14.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + [0.012, 0.02, 0.078]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.bolt_z = float(F_BOLT(t))
    p.trigger = float(F_TRIG(t))
    k = float(F_TRIG(t)) / rig.TRIGGER_PULL                   # finger pad rides the trigger back
    p.fingers_r = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
    clamp = -5.0 * float(ring(t, 0.012, 1.0, 9.0, 0.6)) * fade_end(t, _FIRE_END, 0.03)
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    c = ring(t, 0.002, V(0.32, 0.04, 0.08), 9.0, 0.5) * fade_end(t, _FIRE_END, 0.035)
    cam(p, *c)
    return p


FIRE = Clip("fire", _FIRE_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", _FIRE_END, fire_pose, fps=120, extra_times=FIRE_KEYS)     # AK: no bolt hold-open
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace of roll
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# beats (both reloads): let go 0.06 -> the rifle dips and rolls left (anticipation) -> rolls right into the
# presentation (overshoot 0.36, settle 0.52) -> thumb on the paddle 0.36 (mag_release) -> the hand rocks the
# magazine forward off its lug (mag_out 0.52) and lets it fall at 0.56 -> the hand dives off screen for the new one
# (shown at 0.92) -> brings it up on an arc, decelerating -> aligns the front lug slowly (1.32 .. 1.47, a hair of
# wind-up) -> rocks it home: hit at 1.58 (mag_in), the rifle and the camera jolt.
T_LETGO, T_RELEASE, T_OUT, T_DROP = 0.06, 0.36, 0.52, 0.56
T_SHOW, T_HOOK, T_SEAT = 0.92, 1.47, 1.58
T_HIDE = 0.80                                         # the dropped magazine is far below the screen by then
AWAY = V(-0.080, -0.330, 0.110)                       # off-screen spot (gun space) where the new magazine starts

MAG_THETA_OUT = Curve((T_RELEASE, 0.0), (0.40, 3.2), (T_OUT, 24.0), (T_DROP, 28.0, ("hit", 1.0)))
MAG_OFF_OUT = Curve((T_RELEASE, V(0, 0, 0)), (0.47, V(0, -0.002, 0.0)), (T_OUT, V(0.0, -0.010, 0.002)),
                    (T_DROP, V(-0.006, -0.026, 0.008), ("hit", 1.0)))
MAG_THETA_IN = Curve((T_SHOW, 34.0), (1.12, 30.0), (1.32, 24.0), (1.42, 22.0), (T_HOOK, 22.6),
                     (T_SEAT, -1.8, ("hit", 2.4)), (T_SEAT + 0.035, 0.35, "stop"), (T_SEAT + 0.07, 0.0, "stop"))
MAG_OFF_IN = Curve((T_SHOW, AWAY), (1.12, V(-0.030, -0.110, 0.030)), (1.32, V(-0.004, -0.028, -0.004)),
                   (1.42, V(-0.0008, -0.006, -0.0016)), (T_HOOK, V(0, -0.0012, 0)), (1.52, V(0, 0, 0)),
                   (T_SEAT, V(0, 0, 0)))
MAG_SPIN_IN = Curve((T_SHOW, V(10, -20, 35)), (1.12, V(6, -12, 20)), (1.34, V(0, 0, 0)))
# the old magazine leaves the hand with its velocity plus a small flick down and left, tumbling
TOSS = V(-0.18, -0.35, 0.06)
TUMBLE = V(3.2, 1.1, -2.4)                            # rad/s about the world axes


def mag_held_out(t):
    """Gun-space magazine (t, q) while the hand rocks it out (T_RELEASE .. T_DROP)."""
    return mag_rock(float(MAG_THETA_OUT(t)), MAG_OFF_OUT(t))


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
        p.mag_t, p.mag_q = mag_rock(float(MAG_THETA_IN(t)), MAG_OFF_IN(t), MAG_SPIN_IN(t))


# support hand ---------------------------------------------------------------------------------------
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (0.30, {"mag_release": 1}))
L_OUT_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.18, V(-0.022, -0.034, 0.018), "flow"),
                  (0.30, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.13, 1.0), (0.22, 0.85), (0.30, 0.0))
THUMB_PRESS = Curve((0.0, 0.0), (0.31, 0.0), (T_RELEASE, -17.0, ("hit", 1.6)), (0.41, -5.0), (T_OUT, 0.0))
L_IN = Weights((T_SHOW, {"mag_low": 1}), (T_SEAT + 0.015, {"mag_low": 1}))
FETCH_W = Curve((T_DROP, 0.0), (0.86, 1.0), (T_SHOW, 1.0))
FETCH_ARC = V(-0.030, -0.070, 0.030)                  # the hand dives down and out of view on its way to the pouch


def _left_out(p, t):
    """Support hand leaving the handguard, thumbing the paddle and rocking the magazine out (t < T_DROP)."""
    left_hand(p, L_OUT, t, L_OUT_OFF(t))
    pos, q, curls = p.hand_l
    curls = hands.adjust(open_fingers(curls, float(L_OPEN(t))), thumb=(float(THUMB_PRESS(t)), 0.0))
    p.hand_l = (pos, q, curls)


@functools.lru_cache(maxsize=None)
def _release_hand(clip):
    """The support hand as it lets go of the old magazine (world target), per reload clip."""
    off, rot = RELOADS[clip]
    p = rig.Pose()
    apply_gun(p, off(T_DROP), rot(T_DROP))
    p.mag_t, p.mag_q = mag_held_out(T_DROP)
    _left_out(p, T_DROP)
    return p.hand_l


def _new_mag_anchor(p, t):
    """The mag_low hold on the new magazine where it waits off screen (gun pose of this frame)."""
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    q.mag_t, q.mag_q = mag_rock(float(MAG_THETA_IN(T_SHOW)), MAG_OFF_IN(T_SHOW), MAG_SPIN_IN(T_SHOW))
    return q.anchor("mag_low")


def _left_fetch(p, t, clip):
    """Lets the old magazine go, dives off screen and takes the new one (T_DROP .. T_SHOW)."""
    rel = _release_hand(clip)
    new = _new_mag_anchor(p, t)
    w = float(FETCH_W(t))
    pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - T_DROP) / (0.86 - T_DROP)), FETCH_ARC)
    open_w = smoothstep(T_DROP, T_DROP + 0.05, t) * (1 - smoothstep(0.82, 0.88, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, q, curls)


def mag_hands(p, t, clip):
    """Left hand from the start of the reload to the seat (t < T_SEAT + 0.015)."""
    if t < T_DROP:
        _left_out(p, t)
    elif t < T_SHOW:
        _left_fetch(p, t, clip)
    else:
        left_hand(p, L_IN, t)


# ---------------------------------------------------------------------------------------- reload
# after the seat: the hand lets go, drops 2 cm (wind-up) and slaps the floor plate home (1.70), then swings back
# to the handguard (2.02) with a squeeze; the rifle swings back to ready past the rest pose and settles.
T_SLAP = 1.70
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0042, 0.0016)), (0.34, V(-0.0412, 0.0312, -0.0050)),
                     (0.52, V(-0.0395, 0.0290, -0.0030)), (0.92, V(-0.0405, 0.0303, -0.0040)),
                     (1.30, V(-0.0400, 0.0316, -0.0060)), (T_HOOK, V(-0.0394, 0.0302, -0.0050)),
                     (T_SEAT, V(-0.0386, 0.0286, -0.0022)), (1.74, V(-0.0400, 0.0322, -0.0060)),
                     (2.02, V(0.0030, -0.0042, 0.0012)), (2.22, V(-0.0008, 0.0010, -0.0003)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0012, 0.0), 10.0, 0.4), (0.46, V(0.0, -0.0020, -0.0020), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0, 0.0042, 0.0020), 7.5, 0.35), (T_SLAP, V(0.0, 0.0030, 0.0), 9.0, 0.35),
                      (2.00, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=2.40)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.1)), (0.36, V(4.4, 8.0, -24.0)),
                     (0.52, V(4.0, 7.2, -21.8)), (0.92, V(4.2, 7.6, -22.6)), (1.30, V(3.6, 8.8, -21.0)),
                     (T_HOOK, V(3.7, 8.4, -20.6)), (T_SEAT, V(3.6, 6.8, -19.4)), (1.74, V(2.8, 7.0, -23.0)),
                     (2.04, V(-0.6, -1.0, 2.8)), (2.24, V(0.2, 0.3, -0.6)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.8, 0.5), 10.0, 0.4), (0.46, V(0.0, -1.2, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.8, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-0.5, 2.6, 1.8), 7.5, 0.35), (T_SLAP, V(0.0, 1.6, -0.8), 9.0, 0.35),
                      (2.00, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.40)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (1.30, V(-0.5, 0.2, -1.2)), (1.56, V(-0.4, 0.1, -1.0)),
                     (1.80, V(-0.5, 0.1, -1.3)), (2.08, V(0.3, -0.1, 0.7)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.25, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-1.2, 0.15, 0.9), 6.5, 0.35),
                      (T_SLAP, V(-0.8, 0.0, -0.5), 8.0, 0.35)], end=2.40)
L_SLAP = Weights((T_SEAT + 0.015, {"mag_low": 1}), (1.65, {"slap": 1}), (1.76, {"slap": 1}),
                 (2.02, {"support": 1}))
L_SLAP_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.65, V(0.0, -0.022, 0.004)), (T_SLAP, V(0.0, 0.002, 0.0), "hit"),
                   (1.76, V(0.0, -0.004, 0.0)), (1.88, V(-0.020, -0.030, 0.014), "flow"), (2.02, V(0, 0, 0)))
L_SLAP_OPEN = Curve((T_SEAT + 0.015, 0.0), (1.63, 1.0), (1.80, 1.0), (1.96, 0.0))
L_REGRIP = Curve((1.96, 0.0), (2.04, 1.0), (2.16, 0.0))                    # squeeze as it takes the handguard
SH_MAG = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(-0.110, -0.020, 0.100)),
               (T_SEAT + 0.10, V(-0.110, -0.020, 0.100)), (2.10, V(0, 0, 0)))


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, R_OFF(t), R_ROT(t))
    p.shoulder_l = SH_MAG(t)
    mag_swap(p, t, "reload")
    if t < T_SEAT + 0.015:
        mag_hands(p, t, "reload")
    else:
        left_hand(p, L_SLAP, t, L_SLAP_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(L_SLAP_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["ak_clamp"], float(L_REGRIP(t)) * 0.8)
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.9, 2.2)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", 2.40, reload_pose,
              events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_SLAP))

# ---------------------------------------------------------------------------------- reload_empty
# same magazine change; then the hand leaves the seated magazine, the rifle rolls further right and comes in so the
# hand reaches under it to the charging handle (1.92), hooks it, pulls it slowly back against the spring (1.98 ..
# 2.12, bolt_back), lets go (2.15): the carrier slams home (2.19, bolt_forward) - the rifle is shoved and nods, the
# camera jolts - and the hand flies off, swinging back to the handguard while the rifle returns to ready.
T_REACH, T_GRAB, T_BACK, T_LET, T_FWD = 1.92, 1.98, 2.12, 2.15, 2.19
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0042, 0.0016)), (0.34, V(-0.0412, 0.0312, -0.0050)),
                     (0.52, V(-0.0395, 0.0290, -0.0030)), (0.92, V(-0.0405, 0.0303, -0.0040)),
                     (1.30, V(-0.0400, 0.0316, -0.0060)), (T_HOOK, V(-0.0394, 0.0302, -0.0050)),
                     (T_SEAT, V(-0.0386, 0.0286, -0.0022)), (1.72, V(-0.0392, 0.0332, -0.0200)),
                     (T_REACH, V(-0.0316, 0.0398, -0.0606)), (T_GRAB, V(-0.0310, 0.0386, -0.0590)),
                     (T_BACK, V(-0.0322, 0.0400, -0.0532)), (T_FWD, V(-0.0310, 0.0372, -0.0580)),
                     (2.27, V(-0.0300, 0.0350, -0.0610)), (2.62, V(0.0030, -0.0042, 0.0012)),
                     (2.78, V(-0.0006, 0.0008, -0.0002)), (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0012, 0.0), 10.0, 0.4), (0.46, V(0.0, -0.0020, -0.0020), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0, 0.0042, 0.0020), 7.5, 0.35), (T_BACK, V(0.0, 0.0010, 0.0024), 9.0, 0.4),
                      (T_FWD, V(0.0, -0.0030, -0.0050), 7.0, 0.32), (2.60, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=2.90)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.1)), (0.36, V(4.4, 8.0, -24.0)),
                     (0.52, V(4.0, 7.2, -21.8)), (0.92, V(4.2, 7.6, -22.6)), (1.30, V(3.6, 8.8, -21.0)),
                     (T_HOOK, V(3.7, 8.4, -20.6)), (T_SEAT, V(3.6, 6.8, -19.4)), (1.72, V(3.8, 8.0, -26.0)),
                     (T_REACH, V(4.6, 9.2, -33.0)), (T_GRAB, V(4.5, 9.0, -32.0)), (T_BACK, V(4.1, 9.6, -34.0)),
                     (T_FWD, V(4.3, 8.6, -32.5)), (2.28, V(4.5, 7.5, -31.0)), (2.64, V(-0.6, -1.0, 3.0)),
                     (2.80, V(0.2, 0.3, -0.5)), (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.8, 0.5), 10.0, 0.4), (0.46, V(0.0, -1.2, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.8, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-0.5, 2.6, 1.8), 7.5, 0.35), (T_BACK, V(0.0, 0.8, 1.2), 9.0, 0.4),
                      (T_FWD, V(0.8, -2.8, -2.0), 7.0, 0.32), (2.60, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.90)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (1.30, V(-0.5, 0.2, -1.2)), (1.56, V(-0.4, 0.1, -1.0)),
                     (1.86, V(-0.3, 0.2, -1.8)), (T_BACK, V(0.3, 0.0, -1.4)), (2.30, V(-0.4, 0.1, -1.2)),
                     (2.66, V(0.3, -0.1, 0.8)), (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.25, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-1.2, 0.15, 0.9), 6.5, 0.35),
                      (T_FWD, V(1.6, -0.3, -1.4), 6.5, 0.32)], end=2.90)
E_BOLT = Curve((0.0, 0.0), (T_GRAB, 0.0), (2.03, 0.0, "stop"), (2.08, 0.026), (T_BACK, TRAVEL, ("hit", 1.4)),
               (T_LET, TRAVEL - 0.0016, "stop"), (T_FWD, 0.0, ("hit", 2.4)), (T_FWD + 0.010, 0.0016, "stop"),
               (T_FWD + 0.024, 0.0, "stop"), (2.90, 0.0))
E_LEFT = Weights((T_SEAT + 0.015, {"mag_low": 1}), (T_REACH, {"charging": 1}), (T_LET, {"charging": 1}),
                 (2.36, {"world": 1}), (2.62, {"support": 1}))
E_LEFT_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.76, V(-0.014, -0.044, 0.022), "flow"), (T_REACH, V(0, 0, 0)),
                   (T_GRAB, V(0.0, 0.0, -0.0025)), (T_LET, V(0, 0, 0)), (2.26, V(0.006, -0.022, 0.032), "flow"),
                   (2.62, V(0, 0, 0)))
E_FINGERS = Curve((T_SEAT + 0.015, 0.0), (1.66, 1.0), (T_REACH - 0.04, 1.0), (T_GRAB, 0.0), (T_LET - 0.005, 0.0),
                  (T_LET + 0.05, 1.0), (2.40, 1.0), (2.58, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(-0.110, -0.020, 0.100)),
                 (T_SEAT + 0.06, V(-0.110, -0.020, 0.100)), (T_REACH, V(-0.030, -0.170, 0.030)),
                 (T_LET + 0.05, V(-0.030, -0.170, 0.030)), (2.62, V(0, 0, 0)))
SH_EMPTY_R = Curve((0.00, V(0, 0, 0)), (1.66, V(0, 0, 0)), (T_REACH, V(-0.010, 0.010, -0.050)),
                   (T_LET + 0.05, V(-0.010, 0.010, -0.050)), (2.62, V(0, 0, 0)))   # gun arm follows the push
POLE_REACH = V(-0.55, -0.80, -0.75)


def let_go(p):
    """Off-hand spot just under / behind the released handle (it springs open there)."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.010, -0.035, 0.040)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    p.shoulder_l = SH_EMPTY(t)
    p.shoulder_r = SH_EMPTY_R(t)
    w = ease("sine", t, 1.66, T_REACH) * (1 - ease("sine", t, 2.24, 2.56))    # elbow forward for the reach-under
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * POLE_REACH
    mag_swap(p, t, "reload_empty")
    p.bolt_z = float(E_BOLT(t))
    if t < T_SEAT + 0.015:
        mag_hands(p, t, "reload_empty")
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_FINGERS(t)))
        curls = blend_curls(curls, hands.POSES["ak_clamp"], 0.8 * float(L_REGRIP(t - 0.58)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.4, 2.7)), 0))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.90, reload_empty_pose,
                    events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in"),
                            (T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_GRAB, T_BACK, T_LET, T_FWD,
                                 T_FWD + 0.010))
RELOADS = {"reload": (R_OFF, R_ROT), "reload_empty": (E_OFF, E_ROT)}

# --------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a heavy swing round to show the right side (overshoot 0.56, settle 0.74) -> moving hold,
# turned a little towards the eye -> a counter-roll (1.52) and the rifle rolls over, port up (overshoot 1.92) ->
# the support hand goes to the charging handle, pulls the carrier back (2.42, bolt_back), holds while the rifle tips
# to look into the chamber, lets go: the carrier slams home (2.685, bolt_forward), the rifle jolts -> the hand swings
# back to the handguard, the rifle rolls back to ready past the rest pose and settles.
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
I_BOLT = Curve((0.0, 0.0), (2.24, 0.0, "stop"), (2.32, 0.010), (I_BACK, 0.032, ("hit", 1.3)), (I_LET, 0.0312, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0009, "stop"), (I_FWD + 0.020, 0.0, "stop"), (4.0, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (2.06, {"support": 1}), (2.24, {"charging": 1}), (I_LET, {"charging": 1}),
                 (2.86, {"world": 1}), (3.22, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (2.06, V(0, 0, 0)), (2.15, V(-0.010, 0.020, 0.030), "flow"), (2.24, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.90, V(0.004, -0.018, 0.026), "flow"), (3.22, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (2.06, 0.0), (2.13, 1.0), (2.17, 1.0), (2.25, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (3.02, 1.0), (3.20, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(0.0, 0.12, -0.12)), (1.48, V(0.0, 0.12, -0.12)),
               (1.92, V(0.013, -0.065, 0.014)), (3.08, V(0.013, -0.065, 0.014)), (3.62, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(-0.020, 0.010, -0.050)), (1.48, V(-0.020, 0.010, -0.050)),
               (1.92, V(0.010, 0.000, -0.070)), (3.08, V(0.010, 0.000, -0.070)), (3.62, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.56, V(1.0, 0.0, 0.3)), (1.48, V(1.0, 0.0, 0.3)),
                 (1.92, V(0.39, 0.18, 0.90)), (3.08, V(0.39, 0.18, 0.90)), (3.62, rig.POLE_R))


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_FINGERS(t)))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.8, 2.0, t)
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    curls = blend_curls(curls, hands.POSES["ak_clamp"], 0.7 * float(L_REGRIP(t - 1.24)))
    p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.7, 4.0, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.10, "inspect_move"), (1.52, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (3.05, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))

# section 31.6: the hand-keyed detail layer (overlap, settles, anticipation, hand adjustments, re-grips)
DRAW, RELOAD, RELOAD_EMPTY, INSPECT = (motion_layer.layered(c, rig, seed) for c, seed in
                                       ((DRAW, 101), (RELOAD, 102), (RELOAD_EMPTY, 103), (INSPECT, 104)))

# ------------------------------------------------------------------------------------ locomotion
def step_bounce(t, period, lag=0.12, sharp=1.6):
    ph = (t % period) / period
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - lag))) ** sharp


def pump_left(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0)):
    w = 2 * np.pi / dur
    pos = centre - swing * np.cos(w * t) + bulge * np.sin(w * t)
    curls = hands.adjust(hands.POSES["fist"], index=(6 * np.sin(w * t), 0), pinky=(-4 * np.sin(w * t), 0))
    return pos, None, curls


# one footstep (-1 = lowest): the body compresses fast just after the foot strikes and rises slowly
FOOT = Curve((0.0, 0.25), (0.14, -1.0), (0.56, 0.45), (1.0, 0.25), loop=True)
FOOT_WALK = Curve((0.0, 0.20), (0.20, -1.0), (0.62, 0.40), (1.0, 0.20), loop=True)     # softer: a walking step


def foot(t, period, lag=0.0, curve=FOOT):
    return float(curve(((t - lag) % period) / period))


def punch_left(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0)):
    """The free fist driven forward and back like a runner's arm: quick through the middle of each stroke, a short
    pause at both ends (a sine pushed towards a square), wrist and fingers lagging the stroke."""
    w = 2 * np.pi / dur
    c = np.cos(w * t)
    drive = np.sign(c) * np.abs(c) ** 0.7
    pos = centre - swing * drive + bulge * np.sin(w * t)
    lag = np.sin(w * (t - 0.04))
    curls = hands.adjust(hands.POSES["fist"], index=(5 * lag, 0), middle=(3 * lag, 0), pinky=(-4 * lag, 0))
    return pos, None, curls


WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.07, FOOT_WALK)            # the rifle's turn follows the body a few hundredths late
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.11))
    apply_gun(p, V(0.0038 * side, 0.0006 + 0.0050 * b, -0.0016 * b - 0.0010 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.04)), 0.15 + 0.75 * bl, 1.45 * side_l))
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
LOW_T = V(0.150, -0.110, -0.300)                  # running carry: rifle lowered, muzzle down-left (absolute)
LOW_Q = gun_frame((-0.30, -0.60, -0.74), (-0.30, 0.70, -0.55))
LOW_SHOULDER = V(0.080, 0.160, -0.100)            # gun arm: elbow up and out so the forearm follows the grip
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    half = SPRINT_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.05)                      # the heavy rifle bounces a little after the body
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))                # the gun arm swings against the left arm
    p.gun_t = LOW_T + V(0.006 * side, 0.010 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.2 * bl + 3.2 * swing, 3.6 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.150, -0.300)                  # tactical sprint: muzzle up in front of the right shoulder,
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))      # kept right of the crosshair (absolute)
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
