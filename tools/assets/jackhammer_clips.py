"""Jackhammer clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload,
reload_empty, inspect, walk, sprint, tac_sprint. Each clip maps time -> jackhammer_rig.Pose (palm frames + finger
curls); gen_jackhammer bakes them (IK included) at >= 60 samples/s (the shots at 240).

Authored to section 31 like the RPD / KS-3 (anim.Curve bases, a ring for every push, drift in holds, fingers trailing
their hand). A 4.6 kg 12-gauge bullpup: big moves start a little late and settle long. The shot is the trigger break,
the hands taking the 12-gauge kick, and the cassette indexing one chamber (36 degrees, driven by the gas-operated rod
through the zig-zag cam, a hit and a hair of rebound); the turn snaps back by a chamber at the clip's end on a STEP
channel (the cassette is ten-fold symmetric, so nothing jumps). The reload is the cassette swap behind the grip: the
gun is rolled and lifted to bring the bay into view, the support thumb presses the latch, the hand wraps the cassette
and pulls it down out of the bay, lets it fall, fetches a full one from below, aligns it under the bay and drives it
home; the empty reload then racks the operating sleeve (the forend) to cock the action."""
import functools

import numpy as np

import ads_fire
import hands
import jackhammer_model as jm
import jackhammer_rig as rig
import armrig
from ak_clips import FOOT_WALK, Layers, blend_curls, fade_end, foot, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep, window
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

TRAVEL = jm.BOLT_TRAVEL
MAG_REST = rig.MAG_REST
ZERO = V(0, 0, 0)
GRAVITY = V(0.0, -9.81, 0.0)

hands.POSES.setdefault("jh_clamp", {"thumb": (-20, -12), "index": (-56, -52), "middle": (-62, -58),
                                    "ring": (-66, -62), "pinky": (-70, -64)})


class Shot(Clip):
    """A fire clip: the cassette's index turn is baked as a STEP channel (gen_jackhammer.bake_clip)."""
    step_nodes = (("mag", "rotation"),)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def support_fingers(squeeze=0.0, thumb=0.0):
    return hands.adjust(hands.POSES["handguard"], index=(squeeze, 0), middle=(squeeze, 0), ring=(squeeze * 0.8, 0),
                        pinky=(squeeze * 0.6, 0), thumb=(thumb, 0))


def clench(t, t0, amount=-8.0, width=0.05):
    """A squeeze of the fingers peaking at t0 (absorbing a push)."""
    return amount * np.exp(-((t - t0) / width) ** 2)


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0011 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.14 * np.sin(w + 0.3), 0.18 * np.sin(w + 0.9), 0.20 * np.sin(w) + 0.07 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.09 s (the outgoing weapon is still dropping), heaved up with the muzzle leading: the
# turn overshoots at 0.30, the body at 0.40, a long settle; the support hand slaps onto the forend at 0.48
T_CATCH = 0.48
DRAW_END = 0.80
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.290, 0.100)), (0.09, V(0.054, -0.300, 0.105)),
                        (0.40, V(-0.005, 0.008, -0.006), "flow"), (0.56, V(0.0012, -0.0024, 0.001)),
                        (DRAW_END, V(0, 0, 0))),
                  rings=((T_CATCH, V(0.0, 0.0032, -0.0014), 7.0, 0.36),), end=DRAW_END)
DRAW_ROT = Layers(Curve((0.00, V(-16, -48, -32)), (0.09, V(-17, -51, -34)), (0.30, V(2.6, 4.6, 3.2), "flow"),
                        (0.50, V(-0.7, -1.0, -0.6)), (DRAW_END, V(0, 0, 0))),
                  rings=((T_CATCH, V(1.5, 0.2, -0.8), 6.5, 0.34),), end=DRAW_END)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.09, V(0.3, 0, 0.2)), (0.32, V(-0.9, 0.1, -0.7)),
                        (0.56, V(0.25, 0, 0.2)), (DRAW_END, V(0, 0, 0))),
                  rings=((T_CATCH, V(-0.45, 0.05, 0.35), 6.0, 0.38),), end=DRAW_END)
DRAW_LW = Curve((0.0, V(-0.014, -0.055, 0.040)), (0.32, V(-0.012, -0.040, 0.028)), (0.46, V(0, 0.003, 0), "hit"),
                (0.56, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.38, {"spread": 1.0}), (0.50, {"jh_clamp": 1.0}),
                  (0.68, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.30, {"grip": 1.0}), (0.54, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", DRAW_END, draw_pose, events=[(0.0, "equip")], extra_times=(T_CATCH,))

# ------------------------------------------------------------------------------------------- fire
# 240 rpm: the trigger breaks, the 12-gauge shoves the hands (the runtime adds the root recoil), the gas piston
# drives the operating rod back and its stud, riding the cam groove, turns the cassette one chamber: the turn starts
# at 0.012 s, slams onto its stop at 0.040 s with a hair of rebound and is settled by 0.06 s; the trigger resets by
# 0.14 s. At the end the turn snaps back by one chamber on the STEP channel (the cassette looks the same).
F_END = 0.220
F_RESET = F_END - 0.0005
F_TRIG = Curve((0.0, 0.0), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.060, rig.TRIGGER_PULL, "stop"),
               (0.130, 1.5), (0.180, 0.0))
F_INDEX = Curve((0.0, 0.0), (0.012, 0.0, ("snap", 2.0)), (0.040, -jm.INDEX, ("hit", 1.5)),
                (0.047, -jm.INDEX + 0.6, "stop"), (0.056, -jm.INDEX, "stop"), (F_END, -jm.INDEX))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.06)
    off = ring(t, 0.001, V(0.0006, 0.0026, 0.0080), 10.0, 0.45) + ring(t, 0.040, V(0.0002, -0.0004, -0.0010), 14.0, 0.5)
    rot = ring(t, 0.001, V(0.4, 2.4, -0.8), 9.0, 0.45) + ring(t, 0.040, V(-0.1, -0.3, 0.25), 14.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_TRIG.times() + F_INDEX.times() + [0.002, F_RESET - 0.0004, F_RESET]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.trigger = float(F_TRIG(t))
    p.index = 0.0 if t >= F_RESET else float(F_INDEX(t))
    k = float(F_TRIG(t)) / rig.TRIGGER_PULL
    p.fingers_r = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
    sq = -7.0 * float(ring(t, 0.004, 1.0, 8.0, 0.55)) * fade_end(t, F_END, 0.06)
    p.fingers_l = support_fingers(squeeze=sq, thumb=0.5 * sq)
    cam(p, *(ring(t, 0.002, V(0.40, 0.06, 0.14), 8.0, 0.5) * fade_end(t, F_END, 0.06)))
    return p


FIRE = Shot("fire", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)
FIRE_LAST = Shot("fire_last", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)    # no hold-open: the same
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
# the 12-gauge kick rolls hard: less of it reaches a scope
ADS_SHAPE = {"roll": 0.05, "lateral": 0.02, "max_turn": 0.012}
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE, **ADS_SHAPE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE, **ADS_SHAPE)

# ------------------------------------------------------------------------------------------- reload
T_LETGO, T_LATCH, T_REL = 0.08, 0.40, 0.47     # hand leaves the forend / thumb on the latch / latch pressed
T_GRAB, T_OUT, T_DROP = 0.58, 0.74, 0.84        # hand round the cassette / clear of the bay (mag_out) / let go
T_HIDE, T_SHOW = 1.10, 1.40                     # the old one far below the screen / the new one in the hand
T_ALIGN, T_SEAT = 1.94, 2.10                    # under the bay, lined up / driven home (mag_in)
T_HOME = 2.46                                   # support hand back on the forend
AWAY = V(-0.070, -0.300, 0.080)                 # off-screen spot (gun space offset) where the new cassette starts

OUT_OFF = Curve((T_REL, ZERO), (T_REL + 0.04, V(0.0, -0.0035, 0.0), "stop"), (T_GRAB, V(0.0, -0.0050, 0.0)),
                (T_OUT, V(-0.004, -0.060, 0.010), "flow"), (T_DROP, V(-0.012, -0.100, 0.016)))
OUT_ROT = Curve((T_REL, ZERO), (T_GRAB, V(0.0, 0.0, 0.0)), (T_OUT, V(2.0, -3.0, 5.0)), (T_DROP, V(5.0, -6.0, 12.0)))
TOSS = V(-0.25, -0.30, 0.05)
TUMBLE = V(2.0, 0.8, -3.2)
IN_OFF = Curve((T_SHOW, AWAY), (1.66, V(-0.026, -0.110, 0.030), "flow"), (1.86, V(-0.002, -0.020, 0.002)),
               (T_ALIGN, V(0.0, -0.0070, 0.0)), (2.02, V(0.0, -0.0055, 0.0), "stop"),
               (T_SEAT, V(0.0, 0.0010, 0.0), ("hit", 1.8)), (T_SEAT + 0.035, V(0.0, -0.0004, 0.0), "stop"),
               (T_SEAT + 0.07, ZERO))
IN_ROT = Curve((T_SHOW, V(14, -10, 30)), (1.66, V(5, -4, 10)), (1.90, V(0.5, 0.0, 0.8)), (T_ALIGN + 0.04, ZERO))
LATCH = Curve((0.0, 0.0), (T_LATCH + 0.02, 0.0), (T_REL, rig.LATCH_DOWN, ("hit", 1.6)), (T_GRAB, rig.LATCH_DOWN, "stop"),
              (T_GRAB + 0.06, 0.0), (T_SEAT - 0.02, 0.0), (T_SEAT, 6.0, "stop"), (T_SEAT + 0.05, 0.0))


def mag_held_out(t):
    """Gun-space cassette (t, q) while the hand pulls it out (T_REL .. T_DROP)."""
    return MAG_REST + OUT_OFF(t), quat_euler(*OUT_ROT(t))


def mag_world(gun_off, gun_rot, mag_t, mag_q):
    p = rig.Pose()
    apply_gun(p, gun_off, gun_rot)
    return p.gun_point(mag_t), quat_mul(p.gun_q, mag_q)


@functools.lru_cache(maxsize=None)
def _drop_state(clip):
    """World position, velocity and orientation of the cassette as the hand lets go (per reload clip)."""
    off, rot = RELOADS[clip]
    pos = lambda t: mag_world(off(t), rot(t), *mag_held_out(t))
    p0, q0 = pos(T_DROP)
    p1, _ = pos(T_DROP - 0.012)
    return p0, (p0 - p1) / 0.012 * 0.6 + TOSS, q0


def mag_swap(p, t, clip):
    """The cassette through the swap: released and pulled down out of the bay, dropped (gravity, tumbling), hidden,
    the new one carried up, lined up under the bay and driven home."""
    p.latch = float(LATCH(t))
    if t < T_REL:
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
        p.mag_t, p.mag_q = MAG_REST + IN_OFF(t), quat_euler(*IN_ROT(t))


# support hand ---------------------------------------------------------------------------------------
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (T_LATCH, {"latch": 1}), (T_REL + 0.02, {"latch": 1}),
                (T_GRAB, {"cassette": 1}))
L_OUT_OFF = Curve((0.00, ZERO), (T_LETGO, ZERO), (0.24, V(-0.030, -0.045, 0.030), "flow"), (T_LATCH, ZERO))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.16, 1.0), (0.30, 0.6), (T_LATCH, 0.0), (T_REL + 0.02, 0.0),
               (0.53, 0.5), (T_GRAB, 0.0))
THUMB_PRESS = Curve((0.0, 0.0), (T_LATCH + 0.02, 0.0), (T_REL, -14.0, ("hit", 1.6)), (T_REL + 0.04, -6.0), (T_GRAB, 0.0))
FETCH_W = Curve((T_DROP, 0.0), (1.30, 1.0), (T_SHOW, 1.0))
FETCH_ARC = V(-0.040, -0.080, 0.040)            # the hand dives down and out of view for the new cassette
L_IN_OPEN = Curve((T_SEAT, 0.0), (T_SEAT + 0.05, 0.0), (2.20, 1.0), (2.36, 0.6), (T_HOME, 0.0))
L_RET = Weights((T_SEAT + 0.04, {"cassette": 1}), (T_HOME, {"support": 1}))
L_RET_OFF = Curve((T_SEAT + 0.04, ZERO), (2.28, V(-0.030, -0.050, 0.020), "flow"), (T_HOME, ZERO))
L_REGRIP = Curve((T_HOME, 0.0), (T_HOME + 0.08, 1.0), (T_HOME + 0.22, 0.0))


def _left_out(p, t):
    """Support hand leaving the forend, thumbing the latch, wrapping the cassette and pulling it out (t < T_DROP)."""
    left_hand(p, L_OUT, t, L_OUT_OFF(t))
    pos, q, curls = p.hand_l
    curls = hands.adjust(open_fingers(curls, float(L_OPEN(t))), thumb=(float(THUMB_PRESS(t)), 0.0))
    p.hand_l = (pos, q, curls)


@functools.lru_cache(maxsize=None)
def _release_hand(clip):
    """The support hand as it lets go of the old cassette (world target), per reload clip."""
    off, rot = RELOADS[clip]
    p = rig.Pose()
    apply_gun(p, off(T_DROP), rot(T_DROP))
    p.mag_t, p.mag_q = mag_held_out(T_DROP)
    _left_out(p, T_DROP)
    return p.hand_l


def _new_mag_anchor(p):
    """The hold on the new cassette where it waits off screen (gun pose of this frame)."""
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    q.mag_t, q.mag_q = MAG_REST + IN_OFF(T_SHOW), quat_euler(*IN_ROT(T_SHOW))
    return q.anchor("cassette")


def _left_fetch(p, t, clip):
    """Lets the old cassette go, dives off screen and takes the new one (T_DROP .. T_SHOW)."""
    rel = _release_hand(clip)
    new = _new_mag_anchor(p)
    w = float(FETCH_W(t))
    pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - T_DROP) / (1.30 - T_DROP)), FETCH_ARC)
    open_w = smoothstep(T_DROP, T_DROP + 0.05, t) * (1 - smoothstep(1.24, 1.32, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, q, curls)


def mag_hands(p, t, clip):
    """Left hand from the start of the reload until it lets go of the seated cassette (t < T_SEAT + 0.04)."""
    if t < T_DROP:
        _left_out(p, t)
    elif t < T_SHOW:
        _left_fetch(p, t, clip)
    else:
        left_hand(p, Weights((T_SHOW, {"cassette": 1})), t)
        pos, q, curls = p.hand_l
        curls = hands.adjust(curls, index=(clench(t, T_SEAT + 0.01, -8.0), 0), middle=(clench(t, T_SEAT + 0.01, -8.0), 0))
        p.hand_l = (pos, q, curls)


def return_hand(p, t):
    """From the seated cassette back to the forend (T_SEAT + 0.04 .. T_HOME), then the re-grip squeeze."""
    left_hand(p, L_RET, t, L_RET_OFF(t))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(L_IN_OPEN(t)))
    if t > T_HOME:
        curls = blend_curls(curls, hands.POSES["jh_clamp"], float(L_REGRIP(t)))
    p.hand_l = (pos, q, curls)


RING_EVENTS = (     # time, offset ring (m), turn ring (deg), camera ring (deg), freq, zeta
    (T_REL, V(0.0, 0.0020, 0.0010), V(0.8, 0.1, -1.0), V(0.20, 0.05, -0.25), 8.0, 0.4),
    (T_OUT, V(0.0, 0.0040, 0.0), V(1.0, 0.0, 1.2), V(0.25, 0.0, 0.35), 6.0, 0.38),
    (T_SEAT, V(0.0, 0.0060, -0.0020), V(2.6, 0.2, 1.4), V(0.80, 0.1, 0.60), 7.5, 0.34),
)


def _rings(index, extra=()):
    return tuple((e[0], e[index], e[4], e[5]) for e in RING_EVENTS) + tuple(extra)


def lighter(t):
    """0..1 while the cassette is out: the gun rides a little higher in the hands without its 1.4 kg of shells."""
    return smoothstep(T_OUT, T_OUT + 0.25, t) * (1 - smoothstep(T_SEAT, T_SEAT + 0.2, t))


def weight_shift(t):
    w = lighter(t)
    return V(0.0, 0.0030 * w, 0.0), V(0.0, 0.7 * w, 0.0)


# the presentation: the gun lifted, pushed out and rolled so the bay behind the grip faces the camera
P_OFF = V(-0.150, 0.010, -0.150)
P_ROT = V(35.0, -6.0, -55.0)
R_END = 3.20
R_OFF = Layers(Curve((0.00, ZERO), (0.10, V(0.003, -0.006, 0.002)), (0.38, P_OFF, "flow"),
                     (0.80, P_OFF + V(0.002, -0.006, 0.0)), (1.40, P_OFF + V(-0.002, 0.004, 0.0)),
                     (2.00, P_OFF + V(0.001, -0.003, 0.0)), (2.20, P_OFF + V(0.0, 0.002, 0.0)),
                     (2.62, V(0.004, -0.005, 0.002), "flow"), (2.90, V(-0.001, 0.001, 0.0)), (R_END, ZERO)),
               rings=_rings(1), drifts=((0.85, 1.85, V(0.0012, 0.0010, 0.0008), 11, 0.5),), end=R_END)
R_ROT = Layers(Curve((0.00, ZERO), (0.10, V(-1.0, -1.5, 3.0)), (0.40, P_ROT, "flow"), (0.80, P_ROT + V(1, 0, 2)),
                     (1.40, P_ROT + V(-1, 1, -2)), (2.00, P_ROT + V(0.5, 0, 1)), (2.20, P_ROT + V(0, 0, 0)),
                     (2.64, V(-1.6, -1.2, 2.4), "flow"), (2.92, V(0.3, 0.2, -0.3)), (R_END, ZERO)),
               rings=_rings(2), drifts=((0.85, 1.85, V(0.6, 0.5, 0.9), 21, 0.5),), end=R_END)
R_CAM = Layers(Curve((0.00, ZERO), (0.10, V(0.2, 0, 0.4)), (0.42, V(-0.8, 0.3, -1.6)), (1.40, V(-0.9, 0.2, -1.2)),
                     (2.10, V(-0.7, 0.2, -1.4)), (2.64, V(0.3, -0.1, 0.4)), (R_END, ZERO)),
               rings=_rings(3), end=R_END)
SH_L = Curve((0.00, ZERO), (0.10, ZERO), (0.40, V(0.010, 0.040, -0.020)), (2.20, V(0.010, 0.040, -0.020)),
             (2.55, ZERO))
# the gun arm follows the presentation out: the shoulder comes forward and up so the grip stays in reach
SH_R = Curve((0.00, ZERO), (0.10, ZERO), (0.40, V(-0.040, 0.060, -0.170)), (2.20, V(-0.040, 0.060, -0.170)),
             (2.62, ZERO))


def reload_pose(t):
    p = rig.Pose()
    dt, dr = weight_shift(t)
    apply_gun(p, R_OFF(t) + dt, R_ROT(t) + dr)
    mag_swap(p, t, "reload")
    if t < T_SEAT + 0.04:
        mag_hands(p, t, "reload")
    else:
        return_hand(p, t)
    p.shoulder_l = SH_L(t)
    p.shoulder_r = SH_R(t)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.6, 2.9)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD_EVENTS = [(0.03, "inspect_move"), (T_REL, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")]
RELOAD_KEYS = (T_LATCH, T_REL, T_GRAB, T_OUT, T_DROP, T_HIDE, T_SHOW, T_ALIGN, T_SEAT, T_HOME)
RELOAD = Clip("reload", R_END, reload_pose, events=RELOAD_EVENTS, extra_times=RELOAD_KEYS)


# ---------------------------------------------------------------------------------- reload_empty
class Rack:
    """The support hand racks the operating sleeve from t0 (the hand rides the sleeve: its anchor is in bolt space):
    a brace, a hard pull to the rear stop (`bolt_back` on the hit: the action cocks), a beat, the drive forward into
    battery (`bolt_forward` on the hit); each stop rebounds a little and kicks the gun. soft=True is a part stroke
    eased against the spring and let run home (inspect)."""

    def __init__(self, t0, travel=TRAVEL, hold=0.030, brace=0.040, pull=0.090, drive=0.075, soft=False):
        k = 0.5 + 0.5 * travel / TRAVEL
        self.t0, self.travel, self.soft = t0, travel, soft
        self.t_pull = t0 + brace
        self.t_back = self.t_pull + pull * (1.0 if soft else k)
        self.t_fwd0 = self.t_back + hold
        self.t_fwd = self.t_fwd0 + drive * k
        self.t_end = self.t_fwd + 0.16
        z, bounce = travel, 0.0014 * travel / TRAVEL
        back = (self.t_back, z, "stop") if soft else (self.t_back, z, ("hit", 2.2))
        self.z = Curve((t0, 0.0), (self.t_pull, 0.0), back,
                       (self.t_back + 0.014, z - (0.0 if soft else bounce), "stop"),
                       (self.t_fwd0, z - (0.0 if soft else 0.4 * bounce), "stop"),
                       (self.t_fwd, 0.0, ("hit", 2.4)), (self.t_fwd + 0.012, 0.6 * bounce, "stop"),
                       (self.t_fwd + 0.026, 0.0))
        self.k = travel / TRAVEL

    def events(self):
        return [(self.t_back - 0.003, "bolt_back"), (self.t_fwd - 0.003, "bolt_forward")]

    def active(self, t):
        return self.t0 <= t <= self.t_end

    def times(self):
        return (self.t0, self.t_pull, self.t_back, self.t_back + 0.014, self.t_fwd0, self.t_fwd, self.t_fwd + 0.012,
                self.t_fwd + 0.026)

    def apply(self, p, t):
        p.bolt_z = float(self.z(t))
        pull = min(1.0, p.bolt_z / max(self.travel, 1e-6) * 2.0) * window(t, self.t0, self.t_end, 0.05)
        squeeze = -9.0 * pull + clench(t, self.t_back + 0.01, -5.0 * self.k) + clench(t, self.t_fwd + 0.01, -6.0 * self.k)
        p.fingers_l = support_fingers(squeeze=squeeze, thumb=-6.0 * pull)

    def gun_motion(self, t):
        u = float(self.z(t)) / TRAVEL
        k = self.k
        brace = window(t, self.t0, self.t_fwd + 0.03, 0.045)
        drive = window(t, self.t_fwd0, self.t_fwd + 0.012, 0.035)
        off = V(-0.0015, -0.0025, 0.0015) * brace + V(-0.0030, -0.0040, 0.0090) * u + V(0.0, 0.0018, -0.0050) * drive * k
        rot = V(0.3, -0.7, -1.2) * brace + V(1.4, -2.0, -3.0) * u + V(-0.4, 1.0, 0.8) * drive * k
        if not self.soft:
            off = off + ring(t, self.t_back, V(0.0, -0.0014, 0.0036) * k, 9.0, 0.38)
            rot = rot + ring(t, self.t_back, V(0.5, -1.4, -1.0) * k, 9.0, 0.38)
        off = off + ring(t, self.t_fwd, V(0.0, 0.0010, -0.0032) * k, 10.0, 0.38)
        rot = rot + ring(t, self.t_fwd, V(-0.3, 1.2, 0.9) * k, 10.0, 0.38)
        f = 1.0 - smoothstep(self.t_end - 0.06, self.t_end, t)
        return off * f, rot * f

    def camera(self, t):
        c = ring(t, self.t_fwd, V(0.45, 0.05, 0.35) * self.k, 8.0, 0.35)
        if not self.soft:
            c = c + ring(t, self.t_back, V(-0.40, 0.08, -0.45) * self.k, 7.5, 0.35)
        return c * (1.0 - smoothstep(self.t_end - 0.06, self.t_end, t))


E_END = 3.80
E_HOME = 2.42
RACK = Rack(2.50)
E_OFF = Layers(Curve((0.00, ZERO), (0.10, V(0.003, -0.006, 0.002)), (0.38, P_OFF, "flow"),
                     (0.80, P_OFF + V(0.002, -0.006, 0.0)), (1.40, P_OFF + V(-0.002, 0.004, 0.0)),
                     (2.00, P_OFF + V(0.001, -0.003, 0.0)), (2.20, P_OFF + V(0.0, 0.002, 0.0)),
                     (2.48, V(-0.040, 0.000, 0.010), "flow"), (2.80, V(-0.042, -0.002, 0.012)),
                     (3.20, V(0.004, -0.004, 0.002), "flow"), (3.50, V(-0.001, 0.001, 0.0)), (E_END, ZERO)),
               rings=_rings(1), drifts=((0.85, 1.85, V(0.0012, 0.0010, 0.0008), 11, 0.5),), end=E_END)
E_ROT = Layers(Curve((0.00, ZERO), (0.10, V(-1.0, -1.5, 3.0)), (0.40, P_ROT, "flow"), (0.80, P_ROT + V(1, 0, 2)),
                     (1.40, P_ROT + V(-1, 1, -2)), (2.00, P_ROT + V(0.5, 0, 1)), (2.20, P_ROT + V(0, 0, 0)),
                     (2.48, V(6.0, 6.0, -16.0), "flow"), (2.80, V(6.5, 5.5, -17.0)),
                     (3.22, V(-1.4, -1.0, 2.0), "flow"), (3.50, V(0.3, 0.2, -0.3)), (E_END, ZERO)),
               rings=_rings(2), drifts=((0.85, 1.85, V(0.6, 0.5, 0.9), 21, 0.5),), end=E_END)
E_CAM = Layers(Curve((0.00, ZERO), (0.10, V(0.2, 0, 0.4)), (0.42, V(-0.8, 0.3, -1.6)), (1.40, V(-0.9, 0.2, -1.2)),
                     (2.10, V(-0.7, 0.2, -1.4)), (2.60, V(-0.4, 0.2, -1.0)), (3.22, V(0.3, -0.1, 0.4)),
                     (E_END, ZERO)), rings=_rings(3), end=E_END)
E_RET = Weights((T_SEAT + 0.04, {"cassette": 1}), (E_HOME, {"support": 1}))
E_RET_OFF = Curve((T_SEAT + 0.04, ZERO), (2.26, V(-0.030, -0.050, 0.020), "flow"), (E_HOME, ZERO))
E_RET_OPEN = Curve((T_SEAT, 0.0), (T_SEAT + 0.05, 0.0), (2.20, 1.0), (2.34, 0.6), (E_HOME, 0.0))
E_SH_L = Curve((0.00, ZERO), (0.10, ZERO), (0.40, V(0.010, 0.040, -0.020)), (2.20, V(0.010, 0.040, -0.020)),
               (2.50, ZERO))
E_SH_R = Curve((0.00, ZERO), (0.10, ZERO), (0.40, V(-0.040, 0.060, -0.170)), (2.20, V(-0.040, 0.060, -0.170)),
               (2.50, V(-0.010, 0.020, -0.040)), (2.80, V(-0.010, 0.020, -0.040)), (3.30, ZERO))


def reload_empty_pose(t):
    p = rig.Pose()
    dt, dr = weight_shift(t)
    o, r = RACK.gun_motion(t)
    apply_gun(p, E_OFF(t) + dt + o, E_ROT(t) + dr + r)
    mag_swap(p, t, "reload_empty")
    if t < T_SEAT + 0.04:
        mag_hands(p, t, "reload_empty")
    else:
        left_hand(p, E_RET, t, E_RET_OFF(t))
        pos, q, curls = p.hand_l
        p.hand_l = (pos, q, open_fingers(curls, float(E_RET_OPEN(t))))
        if t >= E_HOME:
            RACK.apply(p, t)
            if t > RACK.t_end:
                p.fingers_l = blend_curls(p.fingers_l, hands.POSES["jh_clamp"],
                                          float(Curve((RACK.t_end, 0.0), (RACK.t_end + 0.08, 1.0),
                                                      (RACK.t_end + 0.22, 0.0))(t)))
    p.shoulder_l = E_SH_L(t)
    p.shoulder_r = E_SH_R(t)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 3.2, 3.5)), 0))
    cam(p, *(E_CAM(t) + RACK.camera(t)))
    return p


RELOAD_EMPTY = Clip("reload_empty", E_END, reload_empty_pose, events=RELOAD_EVENTS + RACK.events(),
                    extra_times=RELOAD_KEYS + RACK.times())
RELOADS = {"reload": (R_OFF, R_ROT), "reload_empty": (E_OFF, E_ROT)}

# --------------------------------------------------------------------------------------- inspect
# a heavy swing to show the left flank (vents, cassette, the cam groove), a half stroke of the operating sleeve eased
# back against its spring and let run home, a swing to show the top, back with a long settle
I_END = 4.20
I_RACK = Rack(1.55, travel=0.016, hold=0.12, pull=0.16, drive=0.07, soft=True)
I_OFF = Layers(Curve((0.00, ZERO), (0.12, V(0.003, -0.005, 0.002)), (0.62, V(-0.130, 0.010, -0.120), "flow"),
                     (1.40, V(-0.132, 0.012, -0.122)), (2.10, V(-0.128, 0.010, -0.118)),
                     (2.75, V(-0.070, 0.000, -0.060), "flow"), (3.35, V(-0.072, -0.002, -0.062)),
                     (3.85, V(0.003, -0.004, 0.002), "flow"), (I_END, ZERO)),
               rings=((2.75, V(0.0, 0.0010, 0.0010), 6.0, 0.4),),
               drifts=((0.70, 1.50, V(0.0012, 0.0010, 0.0008), 31, 0.45), (2.80, 3.40, V(0.0010, 0.0008, 0.0006), 32, 0.5)),
               end=I_END)
# first the left flank with the bay and the cassette's cam groove (the reload's presentation, less rolled), then the
# gun swung muzzle-left and rolled to show its top: the handle, the rail and the sights
I_ROT = Layers(Curve((0.00, ZERO), (0.12, V(-1.4, -1.6, 2.6)), (0.62, V(32.0, -4.0, -32.0), "flow"),
                     (1.40, V(30.0, -3.0, -30.0)), (2.10, V(31.0, -4.0, -33.0)),
                     (2.75, V(40.0, 5.0, 30.0), "flow"), (3.35, V(41.0, 6.0, 32.0)),
                     (3.85, V(-0.8, -1.0, -1.4), "flow"), (I_END, ZERO)),
               drifts=((0.70, 1.50, V(0.8, 0.6, 1.0), 41, 0.45), (2.80, 3.40, V(0.6, 0.5, 0.8), 42, 0.5)), end=I_END)
I_CAM = Layers(Curve((0.00, ZERO), (0.14, V(0.2, 0, 0.5)), (0.62, V(-0.5, 0.3, -1.3)), (1.40, V(-0.3, 0.2, -0.8)),
                     (2.10, V(-0.2, 0.1, -0.6)), (2.75, V(-0.3, 0.4, 1.4)), (3.35, V(-0.2, 0.3, 1.1)),
                     (3.85, V(0.1, 0, -0.2)), (I_END, ZERO)), end=I_END)
I_SH_R = Curve((0.0, ZERO), (0.12, ZERO), (0.62, V(-0.040, 0.060, -0.150)), (2.10, V(-0.040, 0.060, -0.150)),
               (2.75, V(-0.020, 0.030, -0.080)), (3.35, V(-0.020, 0.030, -0.080)), (3.85, ZERO))


def inspect_pose(t):
    p = rig.Pose()
    o, r = I_RACK.gun_motion(t)
    apply_gun(p, I_OFF(t) + o, I_ROT(t) + r)
    w = 2 * np.pi * t / 1.7
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w), 0), middle=(-2 * np.sin(w + 0.5), 0))
    if I_RACK.active(t):
        I_RACK.apply(p, t)
    p.shoulder_r = I_SH_R(t)
    wr = ease("inout", t, 2.10, 2.75) * (1 - ease("inout", t, 3.35, 3.85))
    p.pole_r = rig.POLE_R * (1 - wr) + V(0.80, 0.30, 0.50) * wr
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3), 0.0))
    cam(p, *(I_CAM(t) + I_RACK.camera(t)))
    return p


INSPECT = Clip("inspect", I_END, inspect_pose,
               events=[(0.02, "inspect_move")] + I_RACK.events() + [(2.12, "inspect_move")],
               extra_times=I_RACK.times())


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
    apply_gun(p, V(0.0038 * side, 0.0006 + 0.0052 * b, -0.0016 * b - 0.0010 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.05)), 0.15 + 0.80 * bl, 1.50 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.120, -0.220, -0.350)                 # running carry: the bullpup swung across low, muzzle to the left,
LOW_Q = gun_frame((-0.85, -0.25, -0.45), (0.10, 0.80, -0.50))       # the long rear out of the right edge
LOW_SHOULDER = V(0.080, 0.160, -0.100)
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    half = SPRINT_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.06)
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))
    p.gun_t = LOW_T + V(0.006 * side, 0.011 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.4 * bl + 3.2 * swing, 3.8 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.220, -0.150, -0.400)                 # tactical sprint: muzzle up in front of the right shoulder, the
TAC_Q = gun_frame((0.12, 0.90, -0.40), (-0.85, 0.25, 0.45))        # left flank to the camera, the rear down
TAC_SHOULDER = V(0.000, -0.060, -0.160)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.05)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.013 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.0 * np.sin(w * (t - 0.03)), 3.2 * bl, 3.8 * np.cos(w * (t - 0.08))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
