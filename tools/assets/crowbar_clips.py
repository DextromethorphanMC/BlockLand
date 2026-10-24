"""Crowbar clips (VIEWMODEL_SPEC sections 31 and 41). Authored with the balisong's tools (knife_clips: Motion splined
through key poses with flow / stop / whip / hit / snap keys, impact rings, a camera that coils with the wind-ups and
leans along the swings); contact keys sit on the contact times of knives.json: light 0.24 of 0.62 s, heavy 0.50 of
1.15 s. The bar is heavy (≈ 1.5 kg, its weight 0.45 m out at the head), so the motion is the body's and the arm's,
not the wrist's: long wind-ups that load, the head lagging behind the hand and overtaking it into the contact, a
follow-through the weight carries on, and slow recoveries that settle with an overshoot."""
import numpy as np

import armrig
import crowbar_rig as cr
import hands
from anim import Curve, smoothstep
from knife_clips import (CamCurve, Motion, Spline, absorb, ease, grip_squeeze, light_keys, mix, nudge, periodic_bounce,
                         pump, rings, turned)
from p9_clips import Clip
from p9_rig import POLE_R
from vmath import norm, quat_euler, quat_mul, quat_rotate

V = lambda *a: np.array(a, float)
Q = cr.knife_quat
T_HIT, DUR_L = 0.24, 0.62        # light attacks: the head crosses the screen centre (knives.json impact / duration)
T_EXT, DUR_H = 0.50, 1.15        # heavy: the smash lands
REST = (cr.REST_T, cr.REST_Q)


def centre_root(q, depth):
    """Knife root such that the head's hit point lies on the screen-centre ray at `depth`."""
    return V(0, 0, -depth) - quat_rotate(q, cr.HIT)


POLE = norm(POLE_R)


def reach(pos, q, pole=None):
    """Knife root / orientation the hand reaches for an authored pose with this elbow pole (the wrist limits)."""
    p = cr.CrowbarPose()
    p.knife_t, p.knife_q = np.asarray(pos, float).copy(), np.asarray(q, float).copy()
    p.pole_r = POLE if pole is None else norm(pole)
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


class Swing:
    """Root motion (knife_clips.Motion) plus the elbow's pole, splined through the same keys: a chop raises the elbow,
    a backhand takes it across, so the hammer grip can hold the bar where the swing wants it."""

    def __init__(self, motion, poles):
        self.motion, self.poles = motion, poles

    def apply(self, p, t):
        self.motion.apply(p, t)
        p.pole_r = norm(self.poles(t))

    def quat(self, t):
        return self.motion.quat(t)


def feasible(*keys, contact=None, extra=None, iters=8):
    """Keys (t, pos, orientation, mode[, elbow pole]). Every key pose takes the orientation the wrist allows with its
    pole; the contact key is then re-centred so the solved head crosses the screen centre exactly at its time."""
    keys = [list(k) + [None] * (5 - len(k)) for k in keys]
    for k in keys:
        q = Q(*k[2]) if isinstance(k[2], tuple) else k[2]
        if np.allclose(k[1], cr.REST_T) and abs(float(np.dot(q, cr.REST_Q))) > 1 - 1e-9:
            k[2] = q                       # the ready stance as the idle has it, so clips start and end on the idle
            continue
        k[1], k[2] = reach(k[1], q, k[4])
    poles = Spline(*[(k[0], POLE if k[4] is None else norm(k[4]), "flow") for k in keys])

    def build():
        return Swing(Motion(*[tuple(k[:4]) for k in keys]), poles)
    if contact is not None:
        tc = keys[contact][0]
        for _ in range(iters):
            p = cr.CrowbarPose()
            build().apply(p, tc)
            if extra is not None:
                extra(p, tc)
            p.solve()
            mid = p.knife_point(cr.HIT)
            keys[contact][1] = keys[contact][1] - V(mid[0], mid[1], 0.0)
    return build()


def at_rest(dt=(0, 0, 0), drot=(0, 0, 0)):
    return cr.REST_T + V(*dt), turned(cr.REST_Q, V(*drot))


def blend_curls(a, b, w):
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


def bounce(t, period, lag=0.12, sharp=1.6):
    return periodic_bounce(t, period, lag, sharp)


def with_poles(keys, poles):
    return [tuple(k) + (pole,) for k, pole in zip(keys, poles)]


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    """Breathing with the weight in the hand: a slow rise and sink, the head drifting a little behind it."""
    p = cr.CrowbarPose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = cr.REST_T + V(0.0005 * np.sin(w + 0.5), 0.0009 * np.sin(w), 0.0004 * np.sin(w + 1.2))
    p.knife_q = quat_mul(cr.REST_Q, quat_euler(0.30 * np.sin(w + 0.2), 0.55 * np.sin(w - 0.6), 0.35 * np.sin(w + 0.9)))
    p.fingers_r = hands.adjust(cr.FIST, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)),
                               thumb=(1.5 * np.sin(w + 0.8), 1.0 * np.sin(w + 1.3)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand chop: the bar cocked back over the right shoulder (the head drops behind it as it loads), the arm pulls
# down and across, the head overtakes the hand and whips down-left through the centre at the contact, its weight
# carries the follow-through low across the body; heaved back up to the guard, settling a hair past it
LIGHT_TIMES = (0.06, 0.155, T_HIT, 0.37, 0.48, 0.565, DUR_L)
L1_W = (V(0.205, -0.075, -0.245), Q((-0.05, 0.70, 0.71), (0.50, 0.40, 0.75)))
L1_C = Q((-0.52, 0.34, -0.78), (0.55, 0.83, 0.05))
L1_E = (V(0.020, -0.270, -0.330), Q((-0.75, -0.05, -0.66), (-0.10, 0.95, -0.20)))
# elbow: down at the guard, out and up as the bar is driven down across, high across the body at the end
L1_POLES = (None, None, (0.85, -0.51, -0.09), (0.39, 0.87, -0.29), (0.40, 0.90, 0.20), (0.90, 0.20, 0.10), None, None)
L1 = feasible(*with_poles(light_keys(LIGHT_TIMES, REST, L1_W, L1_C, centre_root(L1_C, 0.66), (0.010, 0.020, 0.010),
                                     0.22, end=L1_E, back=(V(0.090, -0.235, -0.315), 0.60),
                                     over=((0.004, 0.006, -0.002), (0.0, 2.0, 1.0))), L1_POLES), contact=3)
L1_SHOULDER = Spline((0.0, V(0, 0, 0), "stop"), (0.155, V(0.010, 0.010, 0.020), "stop"),
                     (T_HIT, V(-0.015, -0.006, -0.020), "flow"), (0.37, V(-0.030, -0.012, -0.020), "stop"),
                     (DUR_L, V(0, 0, 0), "stop"))
L1_RING = [(T_HIT, V(0.0, -0.002, 0.002), 7.0, 0.40)]
L1_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.155, V(0.45, -0.45, 1.00)), (T_HIT, V(-0.30, 0.30, -0.80)),
                  (0.33, V(-0.85, 1.20, -2.60)), (0.44, V(-0.50, 0.80, -1.70)), (0.56, V(0.10, -0.10, 0.25)),
                  (DUR_L, V(0, 0, 0)))


def light1_pose(t):
    p = cr.CrowbarPose()
    L1.apply(p, t)
    nudge(p, rings(t, L1_RING, DUR_L))
    p.shoulder_r = L1_SHOULDER(t)
    p.fingers_r = grip_squeeze(cr.FIST, t, T_HIT, -7.0, 0.07, loosen=3.0, t_loose=0.155)
    L1_CAM(p, t)
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand: the bar swung across to the left shoulder (the claw side leading back), the shoulders turn, and the bar
# is driven out to the right through the centre, the claw's back leading; the weight carries it out high right
L2_W = (V(-0.010, -0.150, -0.215), Q((-0.62, 0.70, 0.36), (0.70, 0.0, -0.70)))
L2_C = Q((-0.14, 0.40, -0.91), (1.0, 0.0, 0.0))
L2_E = (V(0.255, -0.135, -0.285), Q((0.80, 0.42, -0.43), (0.40, 0.0, 0.90)))
# elbow: lifted and drawn across for the backhand's coil, back out and down as the arm extends to the right
L2_POLES = (None, None, (0.72, 0.45, 0.54), (0.85, -0.51, -0.09), (0.49, -0.78, -0.39), None, None, None)
L2 = feasible(*with_poles(light_keys(LIGHT_TIMES, REST, L2_W, L2_C, centre_root(L2_C, 0.66), (-0.012, 0.012, 0.008),
                                     0.18, end=L2_E, back=(V(0.205, -0.200, -0.300), 0.62),
                                     over=((-0.004, 0.004, 0.002), (0.0, -2.0, -1.0)),
                                     wind_mode={"pos": "stop", "rot": "flow"}), L2_POLES), contact=3)
L2_SHOULDER = Spline((0.0, V(0, 0, 0), "stop"), (0.155, V(-0.020, 0.006, -0.010), "stop"),
                     (T_HIT, V(0.010, 0.0, -0.025), "flow"), (0.37, V(0.020, 0.004, -0.010), "stop"),
                     (DUR_L, V(0, 0, 0), "stop"))
L2_RING = [(T_HIT, V(-0.002, 0.0, 0.002), 7.0, 0.40)]
L2_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.155, V(0.20, 0.45, -0.90)), (T_HIT, V(-0.10, -0.30, 0.70)),
                  (0.33, V(0.40, -1.20, 2.40)), (0.44, V(0.25, -0.80, 1.60)), (0.56, V(-0.05, 0.10, -0.25)),
                  (DUR_L, V(0, 0, 0)))


def light2_pose(t):
    p = cr.CrowbarPose()
    L2.apply(p, t)
    nudge(p, rings(t, L2_RING, DUR_L))
    p.shoulder_r = L2_SHOULDER(t)
    p.fingers_r = grip_squeeze(cr.FIST, t, T_HIT, -7.0, 0.07, loosen=3.0, t_loose=0.155)
    L2_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ heavy
# two-handed overhead smash: the left hand comes up and takes the shaft above the right, both lift the bar high over
# the head (the head hangs back, out of sight, the body leaning back as it loads), a beat at the top, then the whole
# body drives it down: the head comes over and slams through the centre at 0.50, stops dead against the target, a
# shudder runs back up the bar into the hands, it rebounds a little; the bar is lowered, the left hand lets go and
# the right brings it back up into the guard with a heavy settle
HV_C = Q((-0.04, 0.45, -0.89), (0.0, 0.89, 0.45))
HV = feasible((0.000, cr.REST_T, cr.REST_Q, "stop"),
              (0.120, V(0.115, -0.175, -0.290), Q((-0.10, 0.80, -0.59), (0.30, 0.59, 0.81)), "flow",
               (0.94, -0.19, 0.28)),
              (0.300, V(0.060, 0.130, -0.150), Q((0.02, 0.62, 0.78), (0.05, -0.78, 0.62)), "flow"),
              (0.395, V(0.060, 0.160, -0.120), Q((0.03, 0.45, 0.89), (0.05, -0.89, 0.45)), "stop"),
              (T_EXT, centre_root(HV_C, 0.70), HV_C, {"pos": ("hit", 2.2), "rot": ("hit", 2.0)}, (0.92, 0.28, 0.28)),
              (0.560, V(0.020, -0.200, -0.330), Q((-0.04, 0.53, -0.85), (0.0, 0.85, 0.53)), "stop", (0.92, 0.28, 0.28)),
              (0.760, V(0.080, -0.190, -0.300), Q((-0.20, 0.45, -0.87), (0.6, 0.6, 0.5)), "flow", (0.94, -0.19, 0.28)),
              (0.960, *at_rest((-0.006, 0.010, -0.004), (0.0, 3.0, 0.0)), ("flow", 0.6)),
              (1.060, *at_rest((0.002, -0.004, 0.002), (0.0, -1.2, 0.0)), "flow"),
              (DUR_H, cr.REST_T, cr.REST_Q, "stop"), contact=4)
HV_SUPPORT = Curve((0.0, 0.0), (0.05, 0.0), (0.22, 1.0), (0.72, 1.0), (0.86, 0.0), (DUR_H, 0.0))
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.395, V(0.0, 0.035, 0.035), "stop"),
                     (T_EXT, V(-0.010, -0.025, -0.050), "hit"), (0.62, V(-0.006, -0.018, -0.035), "stop"),
                     (DUR_H, V(0, 0, 0), "stop"))
HV_SHOULDER_L = Spline((0.000, V(0, 0, 0), "stop"), (0.395, V(0.030, 0.040, 0.020), "stop"),
                       (T_EXT, V(0.045, -0.010, -0.050), "hit"), (0.62, V(0.035, -0.006, -0.035), "stop"),
                       (0.90, V(0, 0, 0), "stop"), (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_EXT, V(0.0, 0.004, 0.003), 9.0, 0.30)]
HV_RING_ROT = [(T_EXT, V(0.0, 3.0, 0.8), 11.0, 0.28)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.12, V(0.20, 0.0, 0.0)), (0.395, V(1.40, 0.15, 0.40)),
                  (T_EXT, V(-1.30, -0.10, -0.30), "hit"), (0.70, V(-0.40, 0.0, -0.10)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_EXT, V(-1.0, 0.15, 0.6), 7.0, 0.35)])


def heavy_pose(t):
    p = cr.CrowbarPose()
    HV.apply(p, t)
    nudge(p, rings(t, HV_RING, DUR_H), rings(t, HV_RING_ROT, DUR_H))
    p.shoulder_r = HV_SHOULDER(t)
    p.shoulder_l = HV_SHOULDER_L(t)
    p.support = float(HV_SUPPORT(t))
    # the free left hand comes up from below and goes back down there
    p.hand_l = (cr.LEFT_REST[0] + V(0.06, 0.10, -0.08), None, hands.blend({"relaxed": 1.0}))
    p.left_scale = 1.0 if 0.04 <= t < 0.98 else 0.0
    sq = grip_squeeze(cr.FIST, t, T_EXT, -9.0, 0.08)
    p.fingers_r = sq
    p.fingers_l = grip_squeeze(cr.FIST_L, t, T_EXT, -9.0, 0.08)
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# pulled up from below on the right: the hand rises first and the head lags, hanging forward and low; the wrist
# lifts it and its weight swings it up past the guard, the wrist checks it (a jolt, the camera answers), it sways back
# forward and settles into the stance
T_CHECK, DUR_D = 0.38, 0.80
DR = feasible((0.00, V(0.205, -0.380, -0.215), Q((0.18, -0.30, -0.94), (0.60, 0.70, -0.30)), "stop"),
              (0.16, V(0.190, -0.300, -0.255), Q((0.08, 0.10, -0.99), (0.75, 0.55, 0.05)), "flow"),
              (T_CHECK, V(0.165, -0.205, -0.290), Q((-0.36, 0.86, -0.36), (0.88, 0.30, 0.37)), "stop"),
              (0.54, *at_rest((0.002, -0.004, -0.004), (0.0, -4.0, 0.0)), "flow"),
              (0.68, *at_rest((-0.001, 0.002, 0.001), (0.0, 1.5, 0.0)), ("flow", 0.6)),
              (DUR_D, cr.REST_T, cr.REST_Q, "stop"))
DR_JOLT = [(T_CHECK, V(-0.002, 0.004, 0.002), 7.0, 0.40)]
DR_JOLT_ROT = [(T_CHECK, V(0.0, 2.5, 0.8), 7.0, 0.40)]
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.16, V(-0.4, 0.2, 0.5)), (T_CHECK, V(0.5, 0.0, -0.3)), (DUR_D, V(0, 0, 0)),
                  accents=[(T_CHECK, V(0.4, 0.0, 0.3), 6.0, 0.40)])


def draw_pose(t):
    p = cr.CrowbarPose()
    DR.apply(p, t)
    nudge(p, rings(t, DR_JOLT, DUR_D), rings(t, DR_JOLT_ROT, DUR_D))
    p.fingers_r = grip_squeeze(cr.FIST, t, T_CHECK, -6.0, 0.06)
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# raise the bar and look at the head (the goose-neck in profile), turn it to see the claw's slot; bring it down
# across into the open left palm, two weighty taps (the hand gives under each and the bar bounces off it); the left
# hand drops away, the bar rolls once in the loosened fingers (the claw sweeping round), caught, back to the guard
DUR_I = 3.80
T_TAP = (1.42, 1.82)
POLE_LOOK, POLE_TAP = (0.49, -0.78, -0.39), (0.40, 0.90, 0.20)
Q_LOOK = Q((-0.30, 0.35, -0.89), (-1.0, 0.0, 0.0))
P_LOOK, Q_LOOK = reach(V(0.159, -0.144, -0.255), Q_LOOK, POLE_LOOK)
P_SLOT, Q_SLOT = reach(V(0.105, -0.220, -0.254), Q((-0.25, 0.45, -0.86), (0.2, 0.2, 1.0)), (0.92, 0.28, 0.28))
P_TAP, Q_TAP = reach(V(0.150, -0.170, -0.300), Q((-0.85, 0.15, -0.50), (0.0, 1.0, 0.2)), POLE_TAP)
P_UP, Q_UP = P_TAP + V(0.004, 0.010, 0.0), turned(Q_TAP, V(0.0, -10.0, 0.0))
P_SHOW, Q_SHOW = reach(V(0.060, -0.176, -0.260), Q((-0.22, 0.40, -0.89), (1.0, 0.1, 0.1)))
INS = feasible((0.00, cr.REST_T, cr.REST_Q, "stop"),
               (0.40, P_LOOK + V(-0.004, 0.006, 0.0), turned(Q_LOOK, V(0.0, 2.0, 3.0)), ("flow", 0.7), POLE_LOOK),
               (0.55, P_LOOK, Q_LOOK, "flow", POLE_LOOK),
               (0.95, P_SLOT, Q_SLOT, "flow", (0.92, 0.28, 0.28)),
               (1.10, P_SLOT + V(0.002, -0.002, 0.0), turned(Q_SLOT, V(0.0, -1.0, 2.0)), "flow", (0.92, 0.28, 0.28)),
               (1.30, P_UP, Q_UP, "stop", POLE_TAP),
               (T_TAP[0], P_TAP, Q_TAP, "hit", POLE_TAP),
               (1.60, P_UP + V(0.0, 0.002, 0.0), turned(Q_UP, V(0.0, -2.0, 0.0)), "stop", POLE_TAP),
               (T_TAP[1], P_TAP, Q_TAP, "hit", POLE_TAP),
               (2.05, P_SHOW + V(0.0, -0.004, 0.0), Q_SHOW, "flow"),
               (2.85, P_SHOW, turned(Q_SHOW, V(0.0, 1.5, 0.0)), "flow"),
               (3.30, *at_rest((-0.004, 0.008, -0.004), (0.0, 3.0, 0.0)), ("flow", 0.6)),
               (3.58, *at_rest((0.001, -0.002, 0.001), (0.0, -1.0, 0.0)), "flow"),
               (DUR_I, cr.REST_T, cr.REST_Q, "stop"))
T_ROLL = (2.15, 2.75)
ROLL = Curve((0.0, 0.0), (T_ROLL[0], 0.0, ("snap", 1.2)), (T_ROLL[1], 360.0, ("hit", 0.6)), (DUR_I, 360.0))
ROLL_OVER = [(T_ROLL[1], 6.0, 7.0, 0.45)]
INS_JOLT = [(t, V(0.0, 0.004, 0.0), 9.0, 0.35) for t in T_TAP]
INS_JOLT_ROT = [(t, V(0.0, 3.0, 0.0), 9.0, 0.35) for t in T_TAP] + [(T_ROLL[1], V(0.0, -1.5, 0.8), 8.0, 0.40)]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.55, V(0.6, 0.3, 1.0)), (1.10, V(0.4, -0.2, 0.6)),
                   (1.70, V(-0.3, 0.2, -0.4)), (2.40, V(0.4, 0.0, 0.6)), (3.10, V(-0.2, 0.0, -0.2)), (DUR_I, V(0, 0, 0)),
                   accents=[(t, V(-0.3, 0.0, 0.2), 8.0, 0.40) for t in T_TAP])
EV_INSPECT = [(T_TAP[0], "crowbar_tap"), (T_TAP[1], "crowbar_tap")]


def inspect_pose(t):
    p = cr.CrowbarPose()
    INS.apply(p, t)
    nudge(p, rings(t, INS_JOLT, DUR_I), rings(t, INS_JOLT_ROT, DUR_I))
    p.roll = float(ROLL(t) + rings(t, ROLL_OVER, DUR_I))
    if p.roll >= 359.999 and t > T_ROLL[1] + 0.3:
        p.roll = 0.0                                    # a full turn: the same frame
    open_f = ease("inout", t, T_ROLL[0] - 0.08, T_ROLL[0] + 0.02) * (1 - ease("in2", t, T_ROLL[1] - 0.06, T_ROLL[1]))
    loose = hands.blend({"fist": 0.55, "relaxed": 0.45})
    p.fingers_r = blend_curls(grip_squeeze(cr.FIST, t, T_ROLL[1] + 0.02, -6.0, 0.05), loose, 0.7 * open_f)
    # the left palm held open under the bar's head end for the taps, giving under each
    w = ease("inout", t, 1.05, 1.28) * (1 - ease("inout", t, 1.92, 2.12))
    on_bar = P_TAP + quat_rotate(Q_TAP, V(0.0, 0.0, -0.28))      # where the shaft lands, near the neck
    give = sum(absorb(t, tt, V(0.0, -0.6, 0.0), 22.0) for tt in T_TAP)
    q_palm = armrig.palm_from_back((0.05, -1.0, 0.10), (-0.3, 0.0, -1.0), "L")
    centre = on_bar - V(0.0, cr.R_TAPE + hands.PALM_T / 2, 0.0)
    palm = (centre - quat_rotate(q_palm, (0.0, -hands.PALM_LEN / 2, 0.0)) + give, q_palm,
            hands.blend({"relaxed": 0.7, "spread": 0.3}))
    rest_l = (cr.LEFT_REST[0], palm[1], hands.blend({"relaxed": 1.0}))
    p.hand_l = armrig.blend_targets([(1 - w + 1e-6, rest_l), (w, palm)])
    p.left_scale = 1.0 if 1.02 <= t < 2.14 else 0.0
    INS_CAM(p, t)
    return p


# -------------------------------------------------------------------------------------- walk
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def walk_pose(t):
    """The bar at the guard, the arm countering the steps a little behind the body, the head lagging further (its
    weight); the grip firms on every foot strike."""
    p = cr.CrowbarPose()
    b = bounce(t, DUR_W / 2, 0.10, 1.4)
    b_late = bounce(t - 0.06, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.knife_t = cr.REST_T + V(0.005 * side, 0.004 - 0.008 * b, 0.002 * b - 0.0015 * np.sin(W_W * t))
    p.knife_q = quat_mul(cr.REST_Q, quat_euler(1.5 * np.cos(W_W * (t - 0.12)), 0.6 - 1.8 * b_late,
                                               1.8 * np.sin(W_W * (t - 0.06))))
    p.fingers_r = hands.adjust(cr.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    return p


# ------------------------------------------------------------------------------------ sprint
# lowered running carry: the bar held low, the head forward, swinging with the arm and trailing its swing; a hard drop
# on every foot strike; the left fist pumps big
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_C, Q_RUN = cr.reachable(V(0.170, -0.235, -0.270), Q((-0.12, 0.42, -0.90), (0.90, 0.40, 0.15)))
RUN_A = V(-0.012, 0.050, -0.050)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def sprint_pose(t):
    p = cr.CrowbarPose()
    b = periodic_bounce(t, DUR_S / 2, 0.12, 1.6)
    side = np.cos(W_S * (t - 0.04))
    lag = np.cos(W_S * t - 0.75)
    p.knife_t = RUN_C + RUN_A * pump(np.cos(W_S * t)) + V(0.003 * side, -0.012 * b, 0.002 * b)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.5 * np.sin(W_S * t), -9.0 * lag - 3.0 * b, 3.0 * side))
    p.fingers_r = hands.adjust(cr.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    p.hand_l = (L_RUN_C - L_RUN_A * pump(np.cos(W_S * t)) + V(0.0, -0.006 * b, 0.0), None, hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


# -------------------------------------------------------------------------------- tac_sprint
# high carry: the bar raised upright beside the face, the head above the top of the screen; the arm pumps short and
# hard with the head whipping behind it, the left fist pumps big
DUR_T = 0.58
W_T = 2 * np.pi / DUR_T
TAC_C, Q_TAC = cr.reachable(V(0.135, -0.150, -0.255), Q((-0.10, 0.96, -0.25), (0.92, 0.10, 0.37)))
TAC_A = V(-0.006, 0.020, -0.016)


def tac_sprint_pose(t):
    p = cr.CrowbarPose()
    b = bounce(t, DUR_T / 2, 0.10, 1.8)
    side = np.cos(W_T * (t - 0.035))
    lag = np.cos(W_T * t - 0.7)
    p.knife_t = TAC_C + TAC_A * pump(np.cos(W_T * t)) + V(0.004 * side, 0.004 - 0.014 * b, 0.003 * b)
    p.knife_q = quat_mul(Q_TAC, quat_euler(2.0 * side, -4.5 * lag - 3.0 * b, 2.5 * np.sin(W_T * t)))
    p.fingers_r = hands.adjust(cr.FIST, index=(-3.0 * b, 0))
    p.hand_l = (L_RUN_C + V(0.0, 0.012 - 0.006 * b, 0.0) - L_RUN_A * 1.1 * pump(np.cos(W_T * t)), None,
                hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "crowbar_equip")], fps=60, extra_times=(0.16, T_CHECK)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.12, "crowbar_swing")], fps=60, extra_times=(0.155, T_HIT, 0.37)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.12, "crowbar_swing")], fps=60, extra_times=(0.155, T_HIT, 0.37)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.40, "crowbar_swing_heavy")], fps=60,
         extra_times=(0.395, T_EXT, 0.56)),
    Clip("inspect", DUR_I, inspect_pose, events=EV_INSPECT, fps=60,
         extra_times=T_TAP + T_ROLL + (1.02, 2.14)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
