"""Calico M950 clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> calico_rig.Pose; gen_calico bakes them (IK included) at
>= 60 samples/s (fire at 120).

Authored to section 31 with the AK's toolkit (anim.Curve bases, rings for every push, drift in holds). The magazine
change follows the real gun: the gun rolls its top towards the eye, the support hand's thumb presses the release
lever on the magazine's nose (`mag_release`) and the nose pops up off the front latch; the hand takes the magazine,
lifts the nose high about the rear sight bridge and draws it out forwards (`mag_out`) — 20 cm along its own axis, so
the 100-round magazine clears the bridge too — and tosses it away (it falls, tumbling). The new one comes up from
below, its tail is fed into the bridge, slid home and the nose pressed down into the latch (`mag_in`, the biggest
jolt). The empty reload then racks the cocking handle on the left: pinched, drawn back against the spring
(`bolt_back`), let go, the bolt slams home (`bolt_forward`)."""
import functools

import numpy as np

import ads_fire
import armrig
import calico_model as cm
import calico_rig as rig
import hands
from ak_clips import Layers, blend_curls, fade_end, foot, FOOT_WALK, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

TRAVEL = cm.BOLT_TRAVEL
MAG_REST = rig.NODES["mag"][1]
PIVOT = np.array([0.0, cm.MAG_R - 0.0010, 0.5 * (cm.BRIDGE_Z0 + cm.BRIDGE_Z1) - cm.MAG_Z0])   # mag space: under
GRAVITY = V(0.0, -9.81, 0.0)                                                                  # the bridge
PULL = -14.0                                      # trigger blade swung back (degrees about X)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def mag_pose(theta, slide=0.0, offset=(0, 0, 0), extra=(0, 0, 0)):
    """Magazine (gun space) with its nose lifted `theta` degrees about the bridge (pivot under it), drawn `slide`
    metres forwards along its own axis, then offset / turned (euler) for the free flight in the hand."""
    q = quat_mul(quat_axis_angle((1, 0, 0), theta), quat_euler(*extra))
    t = MAG_REST + PIVOT - quat_rotate(q, PIVOT) + quat_rotate(q, (0.0, 0.0, -slide)) + np.asarray(offset, float)
    return t, q


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0006 * np.sin(w + 0.6), 0.0009 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0004 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["calico_fore"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.08 s, whipped up with the muzzle leading (the turn overshoots at 0.24, the position at
# 0.29), the support hand slaps onto the forend at 0.33 and the gun takes the catch; settled by 0.55
T_CATCH = 0.33
DRAW_OFF = Layers(Curve((0.00, V(0.046, -0.250, 0.080)), (0.05, V(0.048, -0.258, 0.084)),
                        (0.29, V(-0.005, 0.008, -0.006), "flow"), (0.40, V(0.0012, -0.0020, 0.0008)), (0.55, V(0, 0, 0))),
                  rings=((T_CATCH, V(0.0, -0.0016, 0.0006), 8.0, 0.4),), end=0.55)
DRAW_ROT = Layers(Curve((0.00, V(-14, -44, -30)), (0.05, V(-15, -46, -31)), (0.24, V(2.4, 4.4, 3.0), "flow"),
                        (0.37, V(-0.6, -1.0, -0.7)), (0.55, V(0, 0, 0))),
                  rings=((T_CATCH, V(0.2, -0.9, 0.6), 8.0, 0.4),), end=0.55)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.06, V(0.25, 0, 0.15)), (0.25, V(-0.8, 0.1, -0.6)),
                        (0.38, V(0.3, 0, 0.2)), (0.55, V(0, 0, 0))),
                  rings=((T_CATCH, V(-0.35, 0.0, 0.25), 7.0, 0.4),), end=0.55)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.22, V(-0.010, -0.032, 0.022)), (T_CATCH, V(0, 0.003, 0), "hit"),
                (0.40, V(0, -0.0008, 0)), (0.47, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.24, {"spread": 1.0}), (0.34, {"calico_clamp": 1.0}),
                  (0.46, {"calico_fore": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.22, {"grip": 1.0}), (0.40, {"calico_grip": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.55, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
# roller-delayed blowback, closed bolt: the trigger breaks, the bolt (and its cocking handle, riding the slot on the
# left) flies back 32 mm, hits its buffer at 0.012 and is driven home by 0.046 with a 1.2 mm bounce; the hands' own
# reaction stays small (the runtime adds the recoil). fire_last is the same: the Calico has no bolt hold-open.
F_END = 0.140
F_BOLT = Curve((0.0, 0.0), (0.002, 0.0, ("snap", 2.6)), (0.012, TRAVEL, ("hit", 0.9)), (0.017, TRAVEL - 0.0006),
               (0.046, 0.0, ("hit", 2.2)), (0.052, 0.0012, "stop"), (0.060, 0.0, "stop"), (F_END, 0.0))
F_TRIG = Curve((0.0, 0.25 * PULL), (0.004, PULL, ("hit", 1.6)), (0.050, PULL), (0.100, 0.0), (F_END, 0.0))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.05)
    off = ring(t, 0.001, V(0.0004, 0.0015, 0.0050), 10.0, 0.5) + ring(t, 0.046, V(0.0, -0.0004, -0.0012), 13.0, 0.5)
    rot = ring(t, 0.001, V(0.2, 1.4, -0.45), 9.5, 0.5) + ring(t, 0.046, V(0.0, -0.4, 0.1), 13.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + [0.006, 0.026, 0.032]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.bolt_z = float(F_BOLT(t))
    p.trigger = float(F_TRIG(t))
    k = float(F_TRIG(t)) / PULL
    p.fingers_r = blend_curls(hands.POSES["calico_grip"], hands.POSES["calico_trigger"], min(1.0, 1.25 * k))
    clamp = -5.0 * float(ring(t, 0.008, 1.0, 9.0, 0.6)) * fade_end(t, F_END, 0.05)
    p.fingers_l = hands.adjust(hands.POSES["calico_fore"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, 0.002, V(0.30, 0.05, 0.10), 9.0, 0.5) * fade_end(t, F_END, 0.05)))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# beats (both reloads): let go of the forend 0.10 -> the gun dips (anticipation) and rolls its top towards the eye
# (overshoot 0.36) -> thumb on the release lever, press (mag_release 0.42): the nose pops off the latch -> the hand
# takes the magazine, lifts the nose about the bridge (mag_out 0.62) and draws it forwards out of the bridge (0.70 ..
# 0.90) -> tossed away at 0.92 (it falls, tumbling; hidden at 1.18) -> the hand dives off screen for the new one
# (shown at 1.22), brings it up nose high, feeds its tail into the bridge slowly (1.56 .. 1.66), slides it home
# (1.88) -> the palm presses the nose down: hit at 2.04 (mag_in), the gun and the camera jolt.
T_LETGO, T_THUMB, T_RELEASE = 0.10, 0.36, 0.42
T_GRAB, T_OUT, T_DROP, T_HIDE = 0.56, 0.62, 0.92, 1.18
T_SHOW, T_ALIGN, T_FEED, T_HOME, T_SEAT = 1.22, 1.56, 1.66, 1.88, 2.04
LIFT = 24.0                                       # nose above the front sight tower
SLIDE = 0.200                                     # out of the bridge: clears the 100-round magazine as well
AWAY = V(-0.110, -0.300, 0.060)                   # gun-space spot below the screen where the new magazine starts

MAG_THETA_OUT = Curve((T_RELEASE, 0.0), (0.455, 4.2, ("hit", 1.4)), (0.50, 3.4), (T_GRAB, 3.6), (T_OUT, 5.0),
                      (0.72, LIFT - 2.0, "flow"), (0.82, LIFT), (T_DROP, LIFT + 6.0))
MAG_SLIDE_OUT = Curve((0.0, 0.0), (0.68, 0.0, "stop"), (0.80, 0.090), (T_DROP, SLIDE + 0.020, ("hit", 0.8)))
MAG_LIFT_OUT = Curve((0.0, V(0, 0, 0)), (0.80, V(0, 0, 0), "stop"), (T_DROP, V(-0.012, 0.018, 0.0)))
MAG_THETA_IN = Curve((T_SHOW, LIFT + 10.0), (1.42, LIFT + 3.0), (T_ALIGN, LIFT), (T_FEED, LIFT - 0.5),
                     (T_HOME, LIFT - 1.5), (1.94, 7.0, "flow"), (2.00, 2.2), (T_SEAT, -0.5, ("hit", 2.0)),
                     (T_SEAT + 0.03, 0.25, "stop"), (T_SEAT + 0.07, 0.0, "stop"))
MAG_SLIDE_IN = Curve((T_SHOW, SLIDE + 0.030), (T_ALIGN, SLIDE + 0.004), (T_FEED, SLIDE - 0.010),
                     (1.80, 0.030, "flow"), (T_HOME, 0.0, ("hit", 1.4)))
MAG_OFF_IN = Curve((T_SHOW, AWAY), (1.40, V(-0.040, -0.090, 0.020), "flow"), (T_ALIGN, V(-0.003, -0.004, 0.0)),
                   (T_FEED, V(0, 0, 0)))
MAG_SPIN_IN = Curve((T_SHOW, V(12, -16, 30)), (1.40, V(5, -6, 10)), (T_ALIGN, V(0, 0, 0)))
TOSS = V(-0.25, -0.75, 0.05)                      # flicked down and left as the hand lets go
TUMBLE = V(2.6, -1.2, 3.0)                        # rad/s about the world axes


def mag_held_out(t):
    return mag_pose(float(MAG_THETA_OUT(t)), float(MAG_SLIDE_OUT(t)), MAG_LIFT_OUT(t))


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
    return p0, (p0 - p1) / 0.012 * 0.3 + TOSS, q0


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
        p.mag_t, p.mag_q = mag_pose(float(MAG_THETA_IN(t)), float(MAG_SLIDE_IN(t)), MAG_OFF_IN(t), MAG_SPIN_IN(t))


# support hand ---------------------------------------------------------------------------------------
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (T_THUMB, {"release": 1}),
                (0.47, {"release": 1}), (T_GRAB, {"mag": 1}))
L_OUT_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.22, V(-0.020, 0.030, 0.020), "flow"),
                  (T_THUMB, V(0, 0, 0)), (0.47, V(0, 0, 0)), (0.51, V(-0.004, 0.008, 0.004), "flow"), (T_GRAB, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.18, 1.0), (0.28, 0.7), (T_THUMB, 0.0), (0.47, 0.0), (0.51, 0.7),
               (T_GRAB, 0.0))
THUMB_PRESS = Curve((0.0, 0.0), (0.38, 0.0), (T_RELEASE, -16.0, ("hit", 1.6)), (0.46, -6.0), (0.50, 0.0))
L_IN = Weights((T_SHOW, {"mag": 1}), (T_HOME, {"mag": 1}), (1.98, {"release": 1}), (T_SEAT + 0.04, {"release": 1}))
L_IN_OFF = Curve((T_SHOW, V(0, 0, 0)), (T_HOME, V(0, 0, 0)), (1.94, V(0.0, 0.010, 0.0), "flow"),
                 (T_SEAT, V(0.0, -0.002, 0.0), "hit"), (T_SEAT + 0.04, V(0, 0, 0)))
FETCH_W = Curve((T_DROP, 0.0), (1.16, 1.0), (T_SHOW, 1.0))
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
    q.mag_t, q.mag_q = mag_pose(float(MAG_THETA_IN(T_SHOW)), float(MAG_SLIDE_IN(T_SHOW)), MAG_OFF_IN(T_SHOW),
                                MAG_SPIN_IN(T_SHOW))
    return q.anchor("mag")


def _left_fetch(p, t, clip):
    rel = _release_hand(clip)
    new = _new_mag_anchor(p)
    w = float(FETCH_W(t))
    pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - T_DROP) / (1.16 - T_DROP)), FETCH_ARC)
    open_w = smoothstep(T_DROP, T_DROP + 0.05, t) * (1 - smoothstep(1.12, 1.18, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, q, curls)


def mag_hands(p, t, clip):
    """Left hand from the start of the reload to just after the seat (t < T_SEAT + 0.04)."""
    if t < T_DROP:
        _left_out(p, t)
    elif t < T_SHOW:
        _left_fetch(p, t, clip)
    else:
        left_hand(p, L_IN, t, L_IN_OFF(t))
        pos, q, curls = p.hand_l
        curls = hands.adjust(curls, index=(-8.0 * float(Curve((T_HOME, 0.0), (T_SEAT, 1.0, "hit"), (2.20, 0.0))(t)), 0),
                             middle=(-6.0 * float(Curve((T_HOME, 0.0), (T_SEAT, 1.0, "hit"), (2.20, 0.0))(t)), 0))
        p.hand_l = (pos, q, curls)


SH_MAG = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(0.030, 0.020, -0.050)),
               (0.90, V(0.040, 0.030, -0.070)), (1.15, V(-0.020, -0.050, 0.040)), (1.40, V(0.030, 0.010, -0.040)),
               (T_SEAT + 0.06, V(0.030, 0.030, -0.040)), (2.40, V(0, 0, 0)))

# ---------------------------------------------------------------------------------------- reload
PRES_OFF = V(-0.034, 0.036, -0.010)               # presentation: the top of the gun turned to the eye
PRES_ROT = V(7.0, 6.0, 15.0)
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.34, PRES_OFF + V(-0.003, 0.003, 0.0), "flow"),
                     (0.50, PRES_OFF), (0.80, PRES_OFF + V(0.004, -0.006, 0.004)), (1.20, PRES_OFF + V(0.002, -0.002, 0.002)),
                     (T_FEED, PRES_OFF + V(0.0, 0.004, 0.0)), (T_SEAT, PRES_OFF + V(0.0, -0.002, -0.002)),
                     (2.36, V(0.003, -0.004, 0.0015), "flow"), (2.50, V(-0.0006, 0.0008, -0.0003)), (2.60, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.0, 0.0014, 0.0), 10.0, 0.4), (T_OUT, V(0.0, 0.0025, -0.0015), 7.0, 0.4),
                      (T_DROP, V(0.0, 0.0018, 0.0), 6.0, 0.45), (T_HOME, V(0.0, 0.0, 0.0030), 8.0, 0.4),
                      (T_SEAT, V(0.0, -0.0055, 0.0015), 7.5, 0.34), (2.34, V(0.0, -0.0012, 0.0), 8.0, 0.4)),
               drifts=((0.50, 0.80, V(0.0008, 0.0008, 0.0006), 11, 0.7), (1.0, 1.5, V(0.0010, 0.0010, 0.0006), 12, 0.6)),
               end=2.60)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.8, -1.2, -2.6)), (0.36, PRES_ROT + V(0.6, 0.6, 1.6), "flow"),
                     (0.50, PRES_ROT), (0.80, PRES_ROT + V(-1.0, -1.0, 1.0)), (1.20, PRES_ROT + V(0.5, -0.5, 0.5)),
                     (T_FEED, PRES_ROT + V(0.5, 0.5, -1.0)), (T_SEAT, PRES_ROT + V(0.0, -1.5, 0.5)),
                     (2.36, V(-0.6, -0.9, -2.2), "flow"), (2.50, V(0.2, 0.3, 0.5)), (2.60, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.0, 0.7, -0.5), 10.0, 0.4), (T_OUT, V(0.0, 1.4, -0.8), 7.0, 0.4),
                      (T_DROP, V(0.3, 0.8, 0.6), 6.0, 0.45), (T_HOME, V(0.0, -0.8, 0.5), 8.0, 0.4),
                      (T_SEAT, V(-0.4, -2.8, 1.4), 7.5, 0.34), (2.34, V(0.0, -0.5, 0.3), 8.0, 0.4)),
               drifts=((0.50, 0.80, V(0.4, 0.4, 0.6), 21, 0.7), (1.0, 1.5, V(0.5, 0.5, 0.7), 22, 0.6)), end=2.60)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, -0.3)), (0.40, V(-0.6, 0.2, 1.3)), (0.90, V(-0.5, 0.3, 1.0)),
                     (1.40, V(-0.3, 0.1, 0.8)), (T_SEAT, V(-0.5, 0.2, 1.1)), (2.34, V(0.2, -0.1, -0.4)), (2.60, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.2, 0.0, -0.2), 9.0, 0.4), (T_OUT, V(0.3, 0.1, -0.3), 7.0, 0.4),
                      (T_SEAT, V(-1.1, 0.1, 0.8), 6.5, 0.35)), end=2.60)
L_BACK = Weights((T_SEAT + 0.04, {"release": 1}), (2.34, {"support": 1}))
L_BACK_OFF = Curve((T_SEAT + 0.04, V(0, 0, 0)), (2.18, V(-0.016, -0.020, 0.012), "flow"), (2.34, V(0, 0, 0)))
L_REGRIP = Curve((2.30, 0.0), (2.38, 1.0), (2.50, 0.0))


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, R_OFF(t), R_ROT(t))
    p.shoulder_l = SH_MAG(t)
    mag_swap(p, t, "reload")
    if t < T_SEAT + 0.04:
        mag_hands(p, t, "reload")
    else:
        left_hand(p, L_BACK, t, L_BACK_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, 0.7 * ease("inout", t, T_SEAT + 0.04, 2.14) * (1 - ease("inout", t, 2.22, 2.34)))
        curls = blend_curls(curls, hands.POSES["calico_clamp"], 0.8 * float(L_REGRIP(t)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.2, 2.5)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD_EVENTS = [(0.06, "inspect_move"), (T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")]
RELOAD_KEYS = (T_RELEASE, T_OUT, T_DROP, T_HIDE, T_SHOW, T_HOME, T_SEAT)
RELOAD = Clip("reload", 2.60, reload_pose, events=RELOAD_EVENTS, extra_times=RELOAD_KEYS)

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change; then the hand leaves the seated nose for the cocking handle just behind it on the left,
# pinches the tab (2.22), draws it back slowly against the spring (2.38, bolt_back), lets go (2.42): the bolt slams
# home (2.455, bolt_forward) - the gun is shoved, the camera jolts - and the hand swings back to the forend.
T_REACH, T_PINCH, T_BACK, T_LET, T_FWD = 2.16, 2.22, 2.38, 2.42, 2.455
E_END = 3.10
E_BOLT = Curve((0.0, 0.0), (T_PINCH, 0.0, "stop"), (2.27, 0.006), (T_BACK, TRAVEL, ("hit", 1.3)),
               (T_LET, TRAVEL - 0.0008, "stop"), (T_FWD, 0.0, ("hit", 2.4)), (T_FWD + 0.010, 0.0012, "stop"),
               (T_FWD + 0.024, 0.0, "stop"), (E_END, 0.0))
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.34, PRES_OFF + V(-0.003, 0.003, 0.0), "flow"),
                     (0.50, PRES_OFF), (0.80, PRES_OFF + V(0.004, -0.006, 0.004)), (1.20, PRES_OFF + V(0.002, -0.002, 0.002)),
                     (T_FEED, PRES_OFF + V(0.0, 0.004, 0.0)), (T_SEAT, PRES_OFF + V(0.0, -0.002, -0.002)),
                     (T_REACH, PRES_OFF + V(-0.006, 0.004, -0.004)), (T_BACK, PRES_OFF + V(-0.004, 0.003, 0.004)),
                     (T_FWD + 0.03, PRES_OFF + V(-0.006, 0.002, -0.004)), (2.86, V(0.003, -0.004, 0.0015), "flow"),
                     (3.00, V(-0.0006, 0.0008, -0.0003)), (E_END, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.0, 0.0014, 0.0), 10.0, 0.4), (T_OUT, V(0.0, 0.0025, -0.0015), 7.0, 0.4),
                      (T_DROP, V(0.0, 0.0018, 0.0), 6.0, 0.45), (T_HOME, V(0.0, 0.0, 0.0030), 8.0, 0.4),
                      (T_SEAT, V(0.0, -0.0055, 0.0015), 7.5, 0.34), (T_BACK, V(0.0, 0.0008, 0.0024), 9.0, 0.4),
                      (T_FWD, V(0.0, -0.0028, -0.0045), 7.0, 0.32), (2.84, V(0.0, -0.0012, 0.0), 8.0, 0.4)),
               drifts=((0.50, 0.80, V(0.0008, 0.0008, 0.0006), 11, 0.7), (1.0, 1.5, V(0.0010, 0.0010, 0.0006), 12, 0.6)),
               end=E_END)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.8, -1.2, -2.6)), (0.36, PRES_ROT + V(0.6, 0.6, 1.6), "flow"),
                     (0.50, PRES_ROT), (0.80, PRES_ROT + V(-1.0, -1.0, 1.0)), (1.20, PRES_ROT + V(0.5, -0.5, 0.5)),
                     (T_FEED, PRES_ROT + V(0.5, 0.5, -1.0)), (T_SEAT, PRES_ROT + V(0.0, -1.5, 0.5)),
                     (T_REACH, PRES_ROT + V(2.0, 0.0, -26.0)), (T_BACK, PRES_ROT + V(2.5, 0.6, -28.0)),
                     (T_FWD + 0.03, PRES_ROT + V(2.0, -0.6, -27.0)), (2.86, V(-0.6, -0.9, -2.2), "flow"),
                     (3.00, V(0.2, 0.3, 0.5)), (E_END, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.0, 0.7, -0.5), 10.0, 0.4), (T_OUT, V(0.0, 1.4, -0.8), 7.0, 0.4),
                      (T_DROP, V(0.3, 0.8, 0.6), 6.0, 0.45), (T_HOME, V(0.0, -0.8, 0.5), 8.0, 0.4),
                      (T_SEAT, V(-0.4, -2.8, 1.4), 7.5, 0.34), (T_BACK, V(0.0, 0.6, 1.0), 9.0, 0.4),
                      (T_FWD, V(0.6, -2.4, -1.6), 7.0, 0.32), (2.84, V(0.0, -0.5, 0.3), 8.0, 0.4)),
               drifts=((0.50, 0.80, V(0.4, 0.4, 0.6), 21, 0.7), (1.0, 1.5, V(0.5, 0.5, 0.7), 22, 0.6)), end=E_END)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, -0.3)), (0.40, V(-0.6, 0.2, 1.3)), (0.90, V(-0.5, 0.3, 1.0)),
                     (1.40, V(-0.3, 0.1, 0.8)), (T_SEAT, V(-0.5, 0.2, 1.1)), (T_BACK, V(0.2, 0.1, 1.6)),
                     (2.60, V(-0.3, 0.1, 1.2)), (2.86, V(0.2, -0.1, -0.4)), (E_END, V(0, 0, 0))),
               rings=((T_RELEASE, V(0.2, 0.0, -0.2), 9.0, 0.4), (T_OUT, V(0.3, 0.1, -0.3), 7.0, 0.4),
                      (T_SEAT, V(-1.1, 0.1, 0.8), 6.5, 0.35), (T_FWD, V(1.3, -0.2, -1.1), 6.5, 0.32)), end=E_END)
E_LEFT = Weights((T_SEAT + 0.04, {"release": 1}), (T_REACH, {"charging": 1}), (T_LET, {"charging": 1}),
                 (2.58, {"world": 1}), (2.84, {"support": 1}))
E_LEFT_OFF = Curve((T_SEAT + 0.04, V(0, 0, 0)), (2.11, V(-0.012, 0.016, 0.010), "flow"), (T_REACH, V(0, 0, 0)),
                   (T_LET, V(0, 0, 0)), (2.52, V(-0.010, -0.010, 0.026), "flow"), (2.84, V(0, 0, 0)))
E_FINGERS = Curve((T_SEAT + 0.04, 0.0), (2.10, 1.0), (T_REACH, 0.6), (T_PINCH, 0.0), (T_LET - 0.005, 0.0),
                  (T_LET + 0.04, 1.0), (2.66, 1.0), (2.82, 0.0))
E_REGRIP = Curve((2.80, 0.0), (2.88, 1.0), (3.00, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.32, V(0.030, 0.020, -0.050)),
                 (0.90, V(0.040, 0.030, -0.070)), (1.15, V(-0.020, -0.050, 0.040)), (1.40, V(0.030, 0.010, -0.040)),
                 (T_SEAT + 0.06, V(0.030, 0.030, -0.040)), (T_LET, V(0.040, 0.020, -0.030)), (2.86, V(0, 0, 0)))


def let_go(p):
    """Spot just off the released tab where the fingers spring open."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.025, 0.010, 0.030)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    p.shoulder_l = SH_EMPTY(t)
    mag_swap(p, t, "reload_empty")
    p.bolt_z = float(E_BOLT(t))
    if t < T_SEAT + 0.04:
        mag_hands(p, t, "reload_empty")
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_FINGERS(t)))
        curls = blend_curls(curls, hands.POSES["calico_clamp"], 0.8 * float(E_REGRIP(t)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.7, 3.0)), 0))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", E_END, reload_empty_pose,
                    events=RELOAD_EVENTS + [(T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=RELOAD_KEYS + (T_PINCH, T_BACK, T_LET, T_FWD, T_FWD + 0.010))
RELOADS = {"reload": (R_OFF, R_ROT), "reload_empty": (E_OFF, E_ROT)}

# --------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a swing round to show the left flank, the long magazine across the view (overshoot 0.56,
# settle 0.74) -> moving hold -> the support hand goes up to the cocking handle and eases the bolt back for a look
# into the port (1.62 .. 1.86, bolt_back), lets it snap home (2.02, bolt_forward) -> the gun rolls over to show its
# right side and the ejection port (2.60) -> back to ready past the rest pose, settle.
I_BACK, I_LET, I_FWD = 1.86, 1.98, 2.02
I_END = 4.00
I_A_OFF, I_A_ROT = V(-0.050, 0.040, -0.050), V(38.0, 10.0, 18.0)
I_B_OFF, I_B_ROT = V(-0.070, 0.020, -0.050), V(22.0, 0.0, 65.0)
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.56, I_A_OFF + V(-0.003, 0.002, -0.003), "flow"),
                     (0.74, I_A_OFF), (1.30, I_A_OFF + V(0.002, -0.002, 0.001)), (1.62, I_A_OFF + V(-0.004, 0.004, 0.006)),
                     (I_BACK, I_A_OFF + V(-0.003, 0.004, 0.008)), (I_FWD, I_A_OFF + V(-0.004, 0.003, 0.006)),
                     (2.24, I_A_OFF + V(0.002, -0.004, 0.0)), (2.62, I_B_OFF + V(-0.003, -0.003, -0.002), "flow"),
                     (2.80, I_B_OFF), (3.22, I_B_OFF + V(0.003, 0.002, 0.002)),
                     (3.66, V(0.003, -0.004, 0.0015), "flow"), (3.84, V(-0.0006, 0.0008, -0.0003)), (I_END, V(0, 0, 0))),
               rings=((I_BACK, V(0.0, 0.0006, 0.0014), 9.0, 0.4), (I_FWD, V(0.0, -0.0020, -0.0032), 7.5, 0.35),
                      (3.62, V(0.0, -0.0012, 0.0), 8.0, 0.4)),
               drifts=((0.74, 1.40, V(0.0010, 0.0009, 0.0007), 31, 0.6), (2.80, 3.30, V(0.0010, 0.0008, 0.0006), 32, 0.6)),
               end=I_END)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, -2.2)), (0.56, I_A_ROT + V(3.0, 1.0, 2.0), "flow"),
                     (0.74, I_A_ROT), (1.30, I_A_ROT + V(-2.0, 1.0, -1.5)), (1.62, I_A_ROT + V(-6.0, 2.0, -28.0)),
                     (I_BACK, I_A_ROT + V(-7.0, 2.5, -30.0)), (I_FWD, I_A_ROT + V(-6.0, 1.5, -29.0)),
                     (2.24, I_A_ROT + V(-12.0, 4.0, -12.0)), (2.62, I_B_ROT + V(1.5, 2.0, 5.0), "flow"),
                     (2.80, I_B_ROT), (3.22, I_B_ROT + V(-1.0, -1.0, -2.0)),
                     (3.66, V(-0.8, -1.2, -2.6), "flow"), (3.84, V(0.2, 0.3, 0.6)), (I_END, V(0, 0, 0))),
               rings=((I_BACK, V(0.0, 0.6, 0.8), 9.0, 0.4), (I_FWD, V(0.5, -1.8, -1.5), 7.5, 0.35),
                      (3.62, V(0.0, -0.5, 0.3), 8.0, 0.4)),
               drifts=((0.74, 1.40, V(0.6, 0.7, 1.0), 41, 0.6), (2.80, 3.30, V(0.5, 0.6, 0.9), 42, 0.6)), end=I_END)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, -0.4)), (0.56, V(0.5, -0.4, 1.2)), (1.30, V(0.3, -0.2, 0.8)),
                     (I_BACK, V(0.1, -0.1, 1.0)), (2.24, V(0.0, 0.1, 0.2)), (2.62, V(-0.4, 0.5, -1.6)),
                     (3.22, V(-0.2, 0.3, -1.0)), (3.66, V(0.2, -0.1, 0.4)), (I_END, V(0, 0, 0))),
               rings=((I_FWD, V(0.9, -0.2, -0.8), 7.0, 0.35),), end=I_END)
I_BOLT = Curve((0.0, 0.0), (1.66, 0.0, "stop"), (1.74, 0.004), (I_BACK, 0.020, ("hit", 1.3)), (I_LET, 0.0196, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0010, "stop"), (I_FWD + 0.022, 0.0, "stop"), (I_END, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (1.44, {"support": 1}), (1.66, {"charging": 1}), (I_LET, {"charging": 1}),
                 (2.20, {"world": 1}), (2.50, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (1.44, V(0, 0, 0)), (1.55, V(-0.012, 0.022, 0.010), "flow"), (1.66, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.14, V(-0.010, -0.012, 0.024), "flow"), (2.50, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (1.44, 0.0), (1.52, 1.0), (1.60, 0.6), (1.66, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (2.30, 1.0), (2.48, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(0.0, 0.10, -0.10)), (2.24, V(0.0, 0.10, -0.10)),
               (2.62, V(0.013, -0.055, 0.014)), (3.26, V(0.013, -0.055, 0.014)), (3.70, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(-0.020, 0.020, -0.040)), (1.44, V(-0.020, 0.020, -0.040)),
               (1.66, V(-0.020, 0.100, 0.020)), (I_LET, V(-0.020, 0.100, 0.020)), (2.50, V(0.010, 0.0, -0.060)),
               (3.26, V(0.010, 0.0, -0.060)), (3.70, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.56, V(1.0, 0.0, 0.3)), (2.24, V(1.0, 0.0, 0.3)),
                 (2.62, V(0.39, 0.18, 0.90)), (3.26, V(0.39, 0.18, 0.90)), (3.70, rig.POLE_R))


def _inspect_let_go(p):
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.022, 0.012, 0.030)), q, hands.blend({"spread": 1.0})


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=_inspect_let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_FINGERS(t)))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.3, 1.44, t)
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    curls = blend_curls(curls, hands.POSES["calico_clamp"], 0.7 * float(Curve((2.46, 0.0), (2.54, 1.0), (2.66, 0.0))(t)))
    p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.7, 4.0, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", I_END, inspect_pose,
               events=[(0.10, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"), (2.26, "inspect_move"),
                       (3.30, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))


# ------------------------------------------------------------------------------------ locomotion
WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.07, FOOT_WALK)
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.11))
    apply_gun(p, V(0.0036 * side, 0.0006 + 0.0048 * b, -0.0015 * b - 0.0010 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.04)), 0.15 + 0.75 * bl, 1.40 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["calico_fore"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.150, -0.118, -0.290)                  # running carry: lowered, muzzle down-left, the magazine's top out
LOW_Q = gun_frame((-0.30, -0.60, -0.74), (-0.36, 0.70, -0.50))
LOW_SHOULDER = V(0.080, 0.150, -0.100)
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
    p.gun_t = LOW_T + V(0.006 * side, 0.010 * b + 0.004 * swing, 0.010 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.2 * bl + 3.0 * swing, 3.6 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"calico_grip": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.180, -0.170, -0.340)                  # tactical sprint: muzzle up in front of the right shoulder
TAC_Q = gun_frame((-0.20, 0.85, -0.50), (-0.60, 0.20, -0.78))   # top turned away: the port side shows
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
    p.fingers_r = hands.blend({"calico_grip": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
