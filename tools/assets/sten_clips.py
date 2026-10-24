"""STEN Mk II clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> sten_rig.Pose; gen_sten bakes them (IK included) at >= 60
samples/s (fire at 120).

Authored to section 31 like the AK's (ak_clips.py: Curve bases, rings for every push, drift in holds). The STEN is a
light (3.2 kg) open-bolt gun: at rest the bolt is held back on the sear; a shot lets it fly forward (it fires as it
closes), blows it back into the buffer and onto the sear again; the last shot leaves it forward (the slide-lock
offset). The magazine lies horizontally in its housing on the left: the reload thumbs the catch on the housing's
rear, draws the magazine out sideways and tosses it, fetches a fresh one from below, lines it up with the housing
mouth and pushes it home along the housing (the gun answers to the right), then a slap of the palm on the floor plate.
The empty reload rolls the gun over to the left, the support hand reaches over the top to the cocking knob on the right
and racks the bolt back until it catches on the sear. The inspect shows the left flank and the magazine, then the
right side: the thumb lifts the cocking handle into the safety notch and drops it out again."""
import functools

import numpy as np

import ads_fire
import armrig
import hands
import sten_model as sm
import sten_rig as rig
from ak_clips import FOOT_WALK, Layers, blend_curls, fade_end, foot, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

TRAVEL = sm.BOLT_TRAVEL
FWD = -TRAVEL                                       # bolt forward (fired / empty)
OVER = sm.OVERTRAVEL
MAG_REST = rig.NODES["mag"][1]
GRAVITY = V(0.0, -9.81, 0.0)

hands.POSES.setdefault("sten_clamp", {"thumb": (-16, -10), "index": (-80, -70), "middle": (-86, -76),
                                      "ring": (-88, -78), "pinky": (-88, -76)})


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(index=(0.0, 0.0), pose="sten_indexed"):
    return hands.adjust(hands.POSES[pose], index=index)


def housing_fingers(t, amount=1.0):
    w = 2 * np.pi * t / 2.0
    return hands.adjust(hands.POSES["sten_housing"], index=(-3 * amount * np.sin(w + 0.7), 0),
                        middle=(-2 * amount * np.sin(w + 0.4), 0), thumb=(2 * amount * np.sin(w + 1.1), 0))


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.24 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = housing_fingers(t)
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.1 s, whipped up muzzle first (the turn overshoots at 0.28, the body at 0.36); the
# support hand slaps onto the magazine housing at 0.40 and the gun takes the catch; settles by 0.62
T_CATCH = 0.40
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.270, 0.090)), (0.07, V(0.053, -0.282, 0.094)),
                        (0.36, V(-0.006, 0.010, -0.007), "flow"), (0.50, V(0.0012, -0.0022, 0.0009)), (0.66, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.0, -0.0018, 0.0006), 8.5, 0.4)], end=0.66)
DRAW_ROT = Layers(Curve((0.00, V(-16, -46, -30)), (0.07, V(-17, -49, -32)), (0.28, V(2.4, 4.6, 3.4), "flow"),
                        (0.44, V(-0.6, -1.2, -0.8)), (0.56, V(0.15, 0.2, 0.1)), (0.66, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.3, -1.0, 0.7), 8.5, 0.4)], end=0.66)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.25, 0, 0.15)), (0.30, V(-0.8, 0.1, -0.6)),
                        (0.46, V(0.3, 0, 0.25)), (0.66, V(0, 0, 0))),
                  rings=[(T_CATCH, V(-0.35, 0.0, 0.25), 7.0, 0.4)], end=0.66)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.26, V(-0.010, -0.032, 0.022)), (T_CATCH, V(0, 0.003, 0), "hit"),
                (0.48, V(0, -0.0008, 0)), (0.56, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.30, {"spread": 1.0}), (0.41, {"sten_clamp": 1.0}),
                  (0.54, {"sten_housing": 1.0}))
DRAW_FR = Weights((0.0, {"sten_grip": 1.0}), (0.28, {"sten_grip": 1.0}), (0.48, {"sten_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.66, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
# open bolt, ~540 rpm: the sear lets the bolt go, the spring drives it forward (accelerating) to fire as it closes at
# 0.020; the blowback throws it back (fast, slowing against the spring) into the buffer at 0.058, the spring pushes it
# forward onto the sear (a hit at 0.071, a 0.8 mm rebound) -- back at rest. The gun is shoved forward by the bolt's
# slam just before the shot and recoils with it; the support fingers clamp a frame later.
F_END = 0.100
F_HIT, F_BUF, F_SEAR = 0.020, 0.058, 0.071
F_BOLT = Curve((0.0, 0.0), (0.002, 0.0, ("snap", 1.6)), (F_HIT, FWD, {"in": -7.0, "out": 4.6}),
               (F_BUF, OVER, ("hit", 1.0)), (F_SEAR, 0.0, ("hit", 1.6)), (F_SEAR + 0.006, 0.0008, "stop"),
               (F_SEAR + 0.014, 0.0, "stop"), (F_END, 0.0))
F_TRIG = Curve((0.0, 0.45 * rig.TRIGGER_PULL), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.055, rig.TRIGGER_PULL),
               (0.092, 0.45 * rig.TRIGGER_PULL), (F_END, 0.45 * rig.TRIGGER_PULL))


def _fire_kick(t, end):
    k = fade_end(t, end, 0.03)
    off = (ring(t, 0.003, V(0.0, -0.0003, -0.0010), 14.0, 0.5)              # the bolt's mass slams forward
           + ring(t, F_HIT, V(0.0003, 0.0013, 0.0046), 12.0, 0.5)          # the shot
           + ring(t, F_BUF, V(0.0, 0.0002, 0.0007), 15.0, 0.5))            # into the buffer
    rot = (ring(t, 0.003, V(0.0, -0.25, 0.05), 14.0, 0.5) + ring(t, F_HIT, V(0.25, 1.3, -0.40), 11.0, 0.5)
           + ring(t, F_BUF, V(0.0, 0.25, 0.0), 15.0, 0.5))
    return off * k, rot * k


def _fire_hands(p, t, end):
    k = float(np.clip(p.trigger / rig.TRIGGER_PULL, 0.0, 1.0))
    p.fingers_r = blend_curls(hands.POSES["sten_trigger"], hands.POSES["sten_pulled"], k)
    clamp = -5.0 * float(ring(t, F_HIT + 0.010, 1.0, 9.0, 0.6)) * fade_end(t, end, 0.03)
    p.fingers_l = hands.adjust(hands.POSES["sten_housing"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, F_HIT, V(0.30, 0.04, 0.08), 9.0, 0.5) * fade_end(t, end, 0.035)))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t, F_END))
    p.bolt_z = float(F_BOLT(t))
    p.trigger = float(F_TRIG(t))
    _fire_hands(p, t, F_END)
    return p


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + [0.010, 0.030, 0.045, 0.064]))
FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)

# the last round: the same shot, but the empty magazine leaves nothing to feed and the trigger is still held -- the
# bolt rides forward again and stays there, closed on the empty chamber (the slide-lock pose)
FL_END = 0.115
FL_BOLT = Curve((0.0, 0.0), (0.002, 0.0, ("snap", 1.6)), (F_HIT, FWD, {"in": -7.0, "out": 4.6}),
                (F_BUF, OVER, ("hit", 1.0)), (0.084, FWD, ("hit", 1.8)), (0.090, FWD + 0.0012, "stop"),
                (0.098, FWD, "stop"), (FL_END, FWD))
FL_TRIG = Curve((0.0, 0.45 * rig.TRIGGER_PULL), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.070, rig.TRIGGER_PULL),
                (0.106, 0.45 * rig.TRIGGER_PULL), (FL_END, 0.45 * rig.TRIGGER_PULL))


def fire_last_pose(t):
    p = rig.Pose()
    off, rot = _fire_kick(t, FL_END)
    off = off + ring(t, 0.084, V(0.0, -0.0004, -0.0011), 14.0, 0.5) * fade_end(t, FL_END, 0.03)  # it slams home
    rot = rot + ring(t, 0.084, V(0.0, -0.35, 0.08), 14.0, 0.5) * fade_end(t, FL_END, 0.03)
    apply_gun(p, off, rot)
    p.bolt_z = float(FL_BOLT(t))
    p.trigger = float(FL_TRIG(t))
    _fire_hands(p, t, FL_END)
    return p


FIRE_LAST = Clip("fire_last", FL_END, fire_last_pose, fps=120,
                 extra_times=sorted(set(FL_BOLT.times() + FL_TRIG.times() + [0.010, 0.030, 0.045, 0.076])))
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# beats (both reloads): the support hand lets go of the housing 0.06 -> the gun dips and rolls a hair (anticipation)
# then turns its left side up to the eye (overshoot 0.34, settle 0.48) -> thumb on the catch 0.34 (mag_release) -> the
# hand draws the magazine out sideways (mag_out 0.50) and flicks it away down and left, letting go at 0.56 -> dives
# off screen for the new one (in the hand at 0.92) -> brings it up on an arc, decelerating -> lines it up with the
# housing mouth, slowly (1.30 .. 1.42) -> pushes it home along the housing: hit at 1.56 (mag_in), the gun and the
# camera jolt to the right.
T_LETGO, T_RELEASE, T_PULL, T_OUT, T_DROP = 0.06, 0.34, 0.37, 0.50, 0.56
T_HIDE, T_SHOW, T_ALIGN, T_ENTER, T_SEAT = 0.80, 0.92, 1.30, 1.42, 1.56
AWAY = V(-0.090, -0.300, 0.090)                          # where the new magazine waits (gun space, its top)

PULL_OUT = Curve((T_PULL, 0.0), (0.40, 0.003), (T_OUT, 0.080, "flow"), (T_DROP, 0.112))
DROP_Y = Curve((T_PULL, 0.0), (T_OUT - 0.02, 0.0), (T_DROP, -0.022))
TILT_OUT = Curve((T_PULL, 0.0), (T_OUT - 0.03, 0.0), (T_DROP, 16.0))
MAG_OFF_IN = Curve((T_SHOW, AWAY), (1.12, V(-0.115, -0.090, 0.022), "flow"), (T_ALIGN, V(-0.084, -0.010, 0.003)),
                   (T_ENTER, V(-0.0715, -0.0008, 0.0002)), (1.47, V(-0.064, 0.0, 0.0)),
                   (T_SEAT, V(0.0014, 0.0, 0.0), ("hit", 2.2)), (T_SEAT + 0.03, V(-0.0006, 0.0, 0.0), "stop"),
                   (T_SEAT + 0.06, V(0.0, 0.0, 0.0), "stop"))
MAG_ROT_IN = Curve((T_SHOW, V(14.0, -10.0, 38.0)), (1.12, V(6.0, -4.0, 14.0)), (T_ALIGN, V(1.5, -0.8, 3.5)),
                   (T_ENTER, V(0.3, -0.2, 0.8)), (1.47, V(0, 0, 0)))
TOSS = V(-0.30, -0.25, 0.06)                             # the flick away (viewmodel space, m/s)
TUMBLE = V(2.4, 1.0, 3.2)                                # rad/s about the world axes


def mag_held_out(t):
    """Gun-space magazine (t, q) while the hand draws it out (T_PULL .. T_DROP)."""
    s = float(PULL_OUT(t))
    return MAG_REST + V(-s, float(DROP_Y(t)), 0.0), quat_euler(0.0, 0.0, float(TILT_OUT(t)))


def mag_world(gun_off, gun_rot, mag_t, mag_q):
    p = rig.Pose()
    apply_gun(p, gun_off, gun_rot)
    return p.gun_point(mag_t), quat_mul(p.gun_q, mag_q)


@functools.lru_cache(maxsize=None)
def _drop_state(clip):
    off, rot = RELOADS[clip]
    pos = lambda t: mag_world(off(t), rot(t), *mag_held_out(t))
    p0, q0 = pos(T_DROP)
    p1, _ = pos(T_DROP - 0.012)
    return p0, (p0 - p1) / 0.012 * 0.5 + TOSS, q0


def mag_swap(p, t, clip):
    if t < T_PULL:
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
        p.mag_t = MAG_REST + MAG_OFF_IN(t)
        p.mag_q = quat_euler(*MAG_ROT_IN(t))


# support hand ---------------------------------------------------------------------------------------
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (0.30, {"mag_release": 1}),
                (T_RELEASE + 0.02, {"mag_release": 1}), (0.44, {"mag": 1}))
L_OUT_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.17, V(-0.030, -0.030, 0.026), "flow"),
                  (0.30, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.13, 1.0), (0.22, 0.8), (0.30, 0.0))
THUMB_PRESS = Curve((0.0, 0.0), (0.31, 0.0), (T_RELEASE, -16.0, ("hit", 1.6)), (0.40, -6.0), (0.46, 0.0))
L_IN = Weights((T_SHOW, {"mag_low": 1}), (T_SEAT + 0.015, {"mag_low": 1}))
FETCH_W = Curve((T_DROP, 0.0), (0.86, 1.0), (T_SHOW, 1.0))
FETCH_ARC = V(-0.040, -0.080, 0.030)


def _left_out(p, t):
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


def _new_mag_anchor(p):
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    q.mag_t, q.mag_q = MAG_REST + MAG_OFF_IN(T_SHOW), quat_euler(*MAG_ROT_IN(T_SHOW))
    return q.anchor("mag_low")


def _left_fetch(p, t, clip):
    rel = _release_hand(clip)
    new = _new_mag_anchor(p)
    w = float(FETCH_W(t))
    pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - T_DROP) / (0.86 - T_DROP)), FETCH_ARC)
    open_w = smoothstep(T_DROP, T_DROP + 0.05, t) * (1 - smoothstep(0.82, 0.88, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, q, curls)


def mag_hands(p, t, clip):
    if t < T_DROP:
        _left_out(p, t)
    elif t < T_SHOW:
        _left_fetch(p, t, clip)
    else:
        left_hand(p, L_IN, t)


# ---------------------------------------------------------------------------------------- reload
# after the seat: the hand lets go, swings out to the floor plate and slaps it home (1.70: the gun shoves right), then
# back under the housing (2.02) with a squeeze; the gun swings back to ready past the rest pose and settles.
T_SLAP = 1.70
PRESENT_OFF = V(-0.006, 0.008, 0.002)
PRESENT_ROT = V(-3.0, -2.5, -16.0)
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0040, 0.0015)), (0.34, PRESENT_OFF * 1.06, "flow"),
                     (0.48, PRESENT_OFF), (0.92, PRESENT_OFF + V(0.0008, -0.0010, 0.0)),
                     (1.30, PRESENT_OFF + V(-0.0010, 0.0016, -0.0020)), (T_ENTER, PRESENT_OFF + V(-0.0006, 0.0010, 0.0)),
                     (T_SEAT, PRESENT_OFF + V(0.0030, 0.0004, 0.0)), (1.74, PRESENT_OFF + V(0.0040, 0.0006, 0.0)),
                     (2.02, V(0.0030, -0.0040, 0.0012), "flow"), (2.22, V(-0.0008, 0.0010, -0.0003)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0010, 0.0005), 10.0, 0.4), (T_OUT, V(-0.0020, 0.0, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0045, 0.0010, 0.0), 7.5, 0.35), (T_SLAP, V(0.0035, 0.0008, 0.0), 9.0, 0.35),
                      (2.00, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=2.40)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.0, -0.8, 1.6)), (0.34, PRESENT_ROT * 1.07, "flow"),
                     (0.48, PRESENT_ROT), (0.92, PRESENT_ROT + V(-0.6, 0.4, 0.5)), (1.30, PRESENT_ROT + V(0.8, 0.6, -0.4)),
                     (T_ENTER, PRESENT_ROT + V(0.4, 0.2, 0.0)), (T_SEAT, PRESENT_ROT + V(-1.2, -0.4, 1.0)),
                     (1.74, PRESENT_ROT + V(-1.6, -0.2, 1.4)), (2.04, V(-0.8, -0.8, 2.0), "flow"),
                     (2.24, V(0.2, 0.3, -0.5)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.4, 0.6, 0.0), 10.0, 0.4), (T_OUT, V(1.2, 0.0, -0.6), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.6, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-2.4, 0.4, 1.8), 7.5, 0.35), (T_SLAP, V(-1.6, 0.2, 1.2), 9.0, 0.35),
                      (2.00, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.40)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.6, 0.3, -1.4)),
                     (0.70, V(-0.4, 0.2, -0.9)), (1.30, V(-0.5, 0.3, -1.1)), (1.56, V(-0.4, 0.2, -1.0)),
                     (1.80, V(-0.4, 0.2, -1.2)), (2.08, V(0.3, -0.1, 0.6)), (2.40, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.2, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-0.6, -0.8, 1.0), 6.5, 0.35),
                      (T_SLAP, V(-0.4, -0.6, 0.6), 8.0, 0.35)], end=2.40)
L_SLAP = Weights((T_SEAT + 0.015, {"mag_low": 1}), (1.65, {"slap": 1}), (1.76, {"slap": 1}),
                 (2.02, {"support": 1}))
L_SLAP_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.65, V(-0.022, -0.004, 0.0)), (T_SLAP, V(0.002, 0.0, 0.0), "hit"),
                   (1.76, V(-0.004, 0.0, 0.0)), (1.88, V(-0.020, -0.034, 0.016), "flow"), (2.02, V(0, 0, 0)))
L_SLAP_OPEN = Curve((T_SEAT + 0.015, 0.0), (1.63, 1.0), (1.80, 1.0), (1.96, 0.0))
L_REGRIP = Curve((1.96, 0.0), (2.04, 1.0), (2.16, 0.0))
SH_MAG = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(-0.090, 0.010, 0.080)),
               (T_SEAT + 0.10, V(-0.090, 0.010, 0.080)), (2.10, V(0, 0, 0)))


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
        curls = blend_curls(curls, hands.POSES["sten_clamp"], float(L_REGRIP(t)) * 0.8)
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(index=(4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.9, 2.2)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", 2.40, reload_pose,
              events=[(0.04, "inspect_move"), (T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_SLAP))

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change (no slap): the support hand goes straight back under the housing (1.80) and holds the gun
# while it rolls a little to the left, the cocking knob on the right coming up; the shooting hand leaves the stock,
# reaches forward along the right side and hooks the knob (1.98), racks it back against the spring to the end of the
# slot (2.14, bolt_back), lets go: the bolt springs forward the few millimetres onto the sear (2.19, bolt_forward, a
# clack) -- the gun is cocked; the hand swings back onto the pistol grip, the gun rolls back to ready.
T_REACH, T_GRAB, T_BACK, T_LET, T_FWD = 1.92, 1.98, 2.14, 2.16, 2.19
T_HOLD = 1.80                                            # support hand back on the housing
COCK_OFF = V(-0.006, 0.004, 0.0)
COCK_ROT = V(0.0, 0.5, 20.0)
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0025, -0.0040, 0.0015)), (0.34, PRESENT_OFF * 1.06, "flow"),
                     (0.48, PRESENT_OFF), (0.92, PRESENT_OFF + V(0.0008, -0.0010, 0.0)),
                     (1.30, PRESENT_OFF + V(-0.0010, 0.0016, -0.0020)), (T_ENTER, PRESENT_OFF + V(-0.0006, 0.0010, 0.0)),
                     (T_SEAT, PRESENT_OFF + V(0.0030, 0.0004, 0.0)), (1.76, (PRESENT_OFF + COCK_OFF) / 2, "flow"),
                     (T_REACH, COCK_OFF * 1.05), (T_GRAB, COCK_OFF), (T_BACK, COCK_OFF + V(0.0, 0.0006, 0.0026)),
                     (T_FWD, COCK_OFF + V(0.0, -0.0004, -0.0008)), (2.32, COCK_OFF * 0.9),
                     (2.64, V(0.0030, -0.0040, 0.0012), "flow"), (2.80, V(-0.0006, 0.0008, -0.0002)), (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0010, 0.0005), 10.0, 0.4), (T_OUT, V(-0.0020, 0.0, 0.0), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.0, -0.0012, 0.0), 5.0, 0.5),
                      (T_SEAT, V(0.0045, 0.0010, 0.0), 7.5, 0.35), (T_HOLD, V(0.0, 0.0016, 0.0), 8.0, 0.4),
                      (T_BACK, V(0.0, 0.0008, 0.0030), 9.0, 0.4), (T_FWD, V(0.0, -0.0010, -0.0026), 9.0, 0.35),
                      (2.62, V(0.0, -0.0014, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=2.90)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.0, -0.8, 1.6)), (0.34, PRESENT_ROT * 1.07, "flow"),
                     (0.48, PRESENT_ROT), (0.92, PRESENT_ROT + V(-0.6, 0.4, 0.5)), (1.30, PRESENT_ROT + V(0.8, 0.6, -0.4)),
                     (T_ENTER, PRESENT_ROT + V(0.4, 0.2, 0.0)), (T_SEAT, PRESENT_ROT + V(-1.2, -0.4, 1.0)),
                     (1.76, (PRESENT_ROT + COCK_ROT) / 2, "flow"), (T_REACH, COCK_ROT * 1.06), (T_GRAB, COCK_ROT),
                     (T_BACK, COCK_ROT + V(0.5, 0.8, -1.5)), (T_FWD, COCK_ROT + V(-0.3, -0.5, 0.8)),
                     (2.32, COCK_ROT * 0.9), (2.66, V(-0.6, -0.9, -2.4), "flow"), (2.80, V(0.2, 0.3, 0.5)),
                     (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.4, 0.6, 0.0), 10.0, 0.4), (T_OUT, V(1.2, 0.0, -0.6), 7.0, 0.4),
                      (T_DROP + 0.02, V(0.2, -0.6, 0.4), 5.0, 0.5),
                      (T_SEAT, V(-2.4, 0.4, 1.8), 7.5, 0.35), (T_HOLD, V(0.0, 0.5, -0.4), 8.0, 0.4),
                      (T_BACK, V(0.0, 0.9, -1.2), 9.0, 0.4), (T_FWD, V(0.3, -1.4, 0.8), 9.0, 0.35),
                      (2.62, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.52, 1.36, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.90)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.40, V(-0.6, 0.3, -1.4)),
                     (0.70, V(-0.4, 0.2, -0.9)), (1.30, V(-0.5, 0.3, -1.1)), (1.56, V(-0.4, 0.2, -1.0)),
                     (1.86, V(-0.2, -0.2, 0.9)), (T_BACK, V(0.3, -0.1, 1.2)), (2.32, V(-0.2, 0.0, 0.8)),
                     (2.66, V(0.2, 0.1, -0.5)), (2.90, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.2, 0.0, 0.3), 9.0, 0.4), (T_SEAT, V(-0.6, -0.8, 1.0), 6.5, 0.35),
                      (T_FWD, V(0.9, -0.2, -0.6), 8.0, 0.35)], end=2.90)
E_BOLT = Curve((0.0, FWD), (T_GRAB, FWD), (2.02, FWD, "stop"), (2.07, FWD + 0.022), (T_BACK, OVER, ("hit", 1.3)),
               (T_LET, OVER - 0.0002, "stop"), (T_FWD, 0.0, ("hit", 2.0)), (T_FWD + 0.008, 0.0007, "stop"),
               (T_FWD + 0.018, 0.0, "stop"), (2.90, 0.0))
E_LEFT = Weights((T_SEAT + 0.015, {"mag_low": 1}), (T_HOLD, {"support": 1}))
E_LEFT_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.68, V(-0.016, -0.028, 0.014), "flow"), (T_HOLD, V(0, 0, 0)))
E_LEFT_OPEN = Curve((T_SEAT + 0.015, 0.0), (1.62, 1.0), (1.74, 0.9), (T_HOLD, 0.0))
E_RIGHT = Weights((1.68, {"grip": 1}), (T_REACH, {"charging": 1}), (T_LET, {"charging": 1}), (2.26, {"world": 1}),
                  (2.50, {"grip": 1}))
E_RIGHT_OFF = Curve((1.68, V(0, 0, 0)), (1.82, V(0.030, 0.022, 0.012), "flow"), (T_REACH, V(0, 0, 0)),
                    (T_GRAB, V(0.0, 0.0, -0.0015)), (T_LET, V(0, 0, 0)), (2.36, V(0.024, 0.018, 0.020), "flow"),
                    (2.50, V(0, 0, 0)))
E_R_OPEN = Curve((1.68, 0.0), (1.76, 1.0), (T_REACH - 0.03, 1.0), (T_GRAB, 0.0), (T_LET - 0.005, 0.0),
                 (T_LET + 0.04, 1.0), (2.42, 1.0), (2.52, 0.0))
R_REACH_W = Curve((1.62, 0.0), (T_REACH, 1.0), (T_LET, 1.0), (2.50, 0.0))


def right_hand(p, weights, t, offset=(0, 0, 0), world=None, lag=FINGER_LAG):
    """Shooting hand from anchor weights (it leaves the stock for the cocking knob); fingers lag the hand."""
    pos, q, _ = p.blend_hand(weights(t), p.gun_dir(np.asarray(offset, float)), world)
    _, _, curls = p.blend_hand(weights(max(t - lag, 0.0)), (0, 0, 0), world)
    p.hand_r = (pos, q, curls)


def let_go(p):
    """Where the shooting hand springs open off the released knob."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(0.024, 0.020, 0.026)), q, hands.blend({"spread": 1.0})


def reach_arm(p, w):
    """The gun arm reaching forward to the knob (shoulder forward, elbow out)."""
    if w > 0:
        p.shoulder_r = p.shoulder_r + w * rig.SHOULDER_R_REACH
        p.pole_r = (1 - w) * rig.POLE_R + w * rig.POLE_R_REACH


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    p.shoulder_l = SH_MAG(t)
    mag_swap(p, t, "reload_empty")
    p.bolt_z = float(E_BOLT(t))
    if t < T_SEAT + 0.015:
        mag_hands(p, t, "reload_empty")
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_LEFT_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["sten_clamp"], 0.8 * float(L_REGRIP(t - 0.16)))
        p.hand_l = (pos, q, curls)
    if t < 1.68:
        p.fingers_r = grip_fingers(index=(4.0 * ease("inout", t, 0.1, 0.3), 0))
    else:
        right_hand(p, E_RIGHT, t, E_RIGHT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_r
        curls = open_fingers(curls, float(E_R_OPEN(t)))
        if t > 2.50:
            curls = blend_curls(curls, hands.POSES["sten_grip"], float(L_REGRIP(t - 0.54)) * 0.6)
        p.hand_r = (pos, q, curls)
        reach_arm(p, float(R_REACH_W(t)))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.90, reload_empty_pose,
                    events=[(0.04, "inspect_move"), (T_RELEASE, "mag_release"), (T_OUT, "mag_out"),
                            (T_SEAT, "mag_in"), (T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=(T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_GRAB, T_BACK, T_LET, T_FWD,
                                 T_FWD + 0.008))
RELOADS = {"reload": (R_OFF, R_ROT), "reload_empty": (E_OFF, E_ROT)}

# --------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a swing round to show the left flank and the magazine (overshoot 0.56, settle 0.74) -> moving
# hold -> a counter-roll (1.52) and the gun rolls over to the left, its right side up (overshoot 1.92) -> the shooting
# hand leaves the stock, hooks the cocking knob, eases the bolt back a hair and lifts the handle into the safety notch
# (2.40), holds it there, drops it out and lets the bolt settle back on the sear (2.78) -> back round the stock, the gun
# rolls back to ready past the rest pose and settles.
I_LIFT, I_SAFE, I_DROP, I_SET = 2.26, 2.40, 2.68, 2.78
I_A_OFF, I_A_ROT = V(-0.030, 0.006, -0.030), V(18.0, 5.0, -18.0)
I_B_OFF, I_B_ROT = V(-0.022, 0.006, -0.020), V(-8.0, 6.0, 40.0)
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.56, I_A_OFF * 1.05, "flow"),
                     (0.74, I_A_OFF), (1.10, I_A_OFF + V(0.0012, -0.0010, 0.0010)), (1.42, I_A_OFF + V(-0.0020, 0.0020, -0.0030)),
                     (1.52, I_A_OFF + V(0.0010, 0.0, 0.0010)), (1.92, I_B_OFF * 1.04, "flow"), (2.06, I_B_OFF),
                     (I_SAFE, I_B_OFF + V(0.0, 0.0010, 0.0012)), (I_DROP, I_B_OFF + V(0.0004, 0.0, -0.0006)),
                     (3.05, I_B_OFF + V(0.0010, -0.0020, 0.0015)), (3.62, V(0.0030, -0.0042, 0.0015), "flow"),
                     (3.82, V(-0.0006, 0.0008, -0.0003)), (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.0, -0.0014, 0.0004), 6.0, 0.45), (I_SAFE, V(0.0, 0.0008, 0.0), 10.0, 0.4),
                      (I_SET, V(0.0, -0.0010, -0.0012), 9.0, 0.4), (3.24, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.0010, 0.0009, 0.0007), 21, 0.6), (2.04, 2.30, V(0.0005, 0.0005, 0.0004), 22, 0.9),
                       (2.42, 2.66, V(0.0004, 0.0004, 0.0003), 23, 0.9)], end=4.00)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, 2.2)), (0.56, I_A_ROT * 1.07, "flow"), (0.74, I_A_ROT),
                     (1.10, I_A_ROT + V(-2.0, 1.0, 3.0)), (1.42, I_A_ROT + V(-3.0, 2.0, 1.0)),
                     (1.52, I_A_ROT + V(-1.0, 1.0, -4.0)), (1.92, I_B_ROT * 1.06, "flow"), (2.06, I_B_ROT),
                     (I_SAFE, I_B_ROT + V(0.4, 0.6, -1.0)), (I_DROP, I_B_ROT + V(-0.4, -0.4, 1.2)),
                     (3.05, I_B_ROT + V(1.0, -1.0, -3.0)), (3.62, V(-0.8, -1.2, -3.2), "flow"), (3.82, V(0.2, 0.3, 0.6)),
                     (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.2, -0.9, 0.7), 6.0, 0.45), (I_SAFE, V(0.0, 0.5, -0.6), 10.0, 0.4),
                      (I_SET, V(0.3, -0.8, 0.6), 9.0, 0.4), (3.24, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.6, 0.7, 1.0), 31, 0.6), (2.04, 2.30, V(0.3, 0.3, 0.4), 32, 0.9),
                       (2.42, 2.66, V(0.3, 0.3, 0.3), 33, 0.9)], end=4.00)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.56, V(0.5, -0.4, -1.2)), (1.42, V(0.3, -0.2, -0.7)),
                     (1.92, V(-0.4, 0.2, 1.8)), (I_SAFE, V(-0.2, 0.2, 1.3)), (I_DROP, V(-0.3, 0.2, 1.5)),
                     (3.10, V(-0.2, 0.1, 1.0)), (3.66, V(0.2, -0.1, -0.5)), (4.00, V(0, 0, 0))),
               rings=[(I_SET, V(0.5, -0.1, -0.4), 8.0, 0.35)], end=4.00)
I_BOLT = Curve((0.0, 0.0), (I_LIFT, 0.0, "stop"), (2.33, OVER, "stop"), (I_DROP, OVER, "stop"),
               (I_SET, 0.0, ("hit", 1.8)), (I_SET + 0.010, 0.0006, "stop"), (I_SET + 0.020, 0.0, "stop"), (4.0, 0.0))
I_BOLT_ROT = Curve((0.0, 0.0), (2.33, 0.0, "stop"), (I_SAFE, sm.SAFETY_ROT, ("hit", 1.5)),
                   (I_SAFE + 0.03, sm.SAFETY_ROT - 0.8, "stop"), (I_SAFE + 0.06, sm.SAFETY_ROT, "stop"),
                   (2.60, sm.SAFETY_ROT, "stop"), (I_DROP - 0.01, 0.0, ("hit", 1.4)), (4.0, 0.0))
I_RIGHT = Weights((0.00, {"grip": 1}), (2.04, {"grip": 1}), (2.20, {"charging": 1}), (I_SET + 0.02, {"charging": 1}),
                  (2.96, {"world": 1}), (3.20, {"grip": 1}))
I_RIGHT_OFF = Curve((0.00, V(0, 0, 0)), (2.04, V(0, 0, 0)), (2.12, V(0.026, 0.020, 0.012), "flow"),
                    (2.20, V(0, 0, 0)), (I_SET + 0.02, V(0, 0, 0)), (3.04, V(0.022, 0.016, 0.020), "flow"),
                    (3.20, V(0, 0, 0)))
I_R_OPEN = Curve((0.0, 0.0), (2.04, 0.0), (2.10, 1.0), (2.16, 1.0), (2.22, 0.0), (I_SET + 0.02, 0.0),
                 (I_SET + 0.07, 1.0), (3.12, 1.0), (3.22, 0.0))
I_REACH_W = Curve((1.98, 0.0), (2.20, 1.0), (I_SET + 0.02, 1.0), (3.20, 0.0))


def inspect_let_go(p):
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(0.022, 0.018, 0.024)), q, hands.blend({"spread": 1.0})


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.bolt_z = float(I_BOLT(t))
    p.bolt_rot = float(I_BOLT_ROT(t))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.8, 2.0, t)
    fl = hands.adjust(hands.POSES["sten_housing"], index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    hold = float(np.clip((t - 2.0) / 0.12, 0, 1)) * (1 - smoothstep(3.0, 3.3, t))     # it holds the gun alone
    p.fingers_l = blend_curls(fl, hands.POSES["sten_clamp"], 0.6 * hold)
    if 2.04 < t < 3.20:
        right_hand(p, I_RIGHT, t, I_RIGHT_OFF(t), world=inspect_let_go(p))
        pos, q, curls = p.hand_r
        p.hand_r = (pos, q, open_fingers(curls, float(I_R_OPEN(t))))
    else:
        p.fingers_r = grip_fingers(index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.7, 4.0, t)), 0.0))
    reach_arm(p, float(I_REACH_W(t)))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.10, "inspect_move"), (1.52, "inspect_move"), (I_SAFE, "inspect_move"),
                       (3.05, "inspect_move")],
               extra_times=(I_SAFE, I_DROP, I_SET, I_SET + 0.010))


# ------------------------------------------------------------------------------------ locomotion
WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.06, FOOT_WALK)
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.10))
    apply_gun(p, V(0.0040 * side, 0.0006 + 0.0052 * b, -0.0016 * b - 0.0010 * np.sin(w * t)),
              V(1.0 * np.sin(w * (t - 0.04)), 0.15 + 0.80 * bl, 1.55 * side_l))
    p.fingers_r = grip_fingers(index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["sten_housing"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.160, -0.140, -0.300)                  # running carry: gun lowered across the body, muzzle down-left
LOW_Q = gun_frame((-0.40, -0.50, -0.77), (0.25, 0.85, -0.40))
LOW_SHOULDER = V(0.040, 0.060, -0.040)
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
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.6 * np.sin(w * (t - 0.03)), 3.4 * bl + 3.2 * swing, 3.8 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"sten_grip": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.220, -0.300, -0.300)                  # tactical sprint: muzzle up in front of the right shoulder, the
TAC_Q = gun_frame((-0.20, 0.85, -0.48), (0.85, 0.25, 0.45))      # magazine pointing up and away (clear of the centre)
TAC_SHOULDER = V(-0.040, -0.120, -0.100)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.045)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.012 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.0 * np.sin(w * (t - 0.03)), 3.0 * bl, 3.8 * np.cos(w * (t - 0.075))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"sten_grip": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
