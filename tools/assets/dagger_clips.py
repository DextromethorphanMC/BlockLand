"""Combat dagger clips (VIEWMODEL_SPEC sections 27 and 31). Authored with the balisong's tools (knife_clips: Motion
splined through key poses with flow / stop / whip / hit / snap keys, impact rings, moving holds, a camera that coils
with the wind-ups and leans along the cuts); contact keys sit on the contact times of knives.json: light 0.11 of
0.36 s, heavy 0.33 of 0.80 s. The dagger is faster and its blade is longer; there is no mechanism, so the tricks are
the knife spinning in the hand (`spin`, about its flat normal through the grip), driven by the wrist and caught with
a little overshoot."""
import numpy as np

import armrig
import dagger_rig as dr
import hands
from anim import Curve, smoothstep
from knife_clips import (CamCurve, Spline, ease, feasible_for, grip_squeeze, knife_q_flat, light_keys, mix, nudge,
                         periodic_bounce, pump, rings, turned)
from p9_clips import Clip
from vmath import quat_axis_angle, quat_euler, quat_mul, quat_rotate

V = lambda *a: np.array(a, float)
Q = dr.knife_quat
T_HIT = 0.11                 # light attacks: the blade crosses the screen centre (knives.json impact / duration)
T_EXT = 0.33                 # heavy: full extension
DUR_L, DUR_H = 0.36, 0.80
REST = (dr.REST_T, dr.REST_Q)


def centre_root(q, depth):
    """Knife root such that the blade (guard -> point midpoint) lies on the screen-centre ray."""
    return V(0, 0, -depth) - quat_rotate(q, dr.BLADE_MID)


def feasible(*keys, contact=None, extra=None, iters=8):
    """Every key pose takes the orientation the wrist allows; the contact key is then re-centred so the solved blade
    crosses the screen centre exactly at its time (knife_clips.feasible_for on the dagger rig)."""
    return feasible_for(dr, dr.DaggerPose, dr.BLADE_MID, *keys, contact=contact, extra=extra, iters=iters)


def at_rest(dt=(0, 0, 0), drot=(0, 0, 0)):
    return dr.REST_T + V(*dt), turned(dr.REST_Q, V(*drot))


def loose(w):
    """The fist opened by w (0 = gripping, 1 = fingers spread to let the handle turn through them)."""
    return hands.blend({"fist": 1.0 - w, "spread": w}) if w > 1e-4 else dr.FIST


def blend_curls(a, b, w):
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = dr.DaggerPose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = dr.REST_T + V(0.0004 * np.sin(w + 0.5), 0.0006 * np.sin(w), 0.0003 * np.sin(w + 1.2))
    p.knife_q = quat_mul(dr.REST_Q, quat_euler(0.25 * np.sin(w + 0.4), 0.35 * np.sin(w + 1.0), 0.4 * np.sin(w)))
    p.fingers_r = hands.adjust(dr.FIST, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)),
                               thumb=(2.0 * np.sin(w + 0.8), 1.0 * np.sin(w + 1.3)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand: a short cock up-right, the whip down through the centre (fastest at the contact) and an even deceleration
# down-left, back to the guard along an arc and settled; quicker and shorter than the balisong's
LIGHT_TIMES = (0.025, 0.066, T_HIT, 0.185, 0.255, 0.315, DUR_L)
L1_W = (V(0.166, -0.008, -0.298), Q((0.36, 0.82, -0.45), (0.74, 0.47, 0.45)))
L1_C = Q((-0.72, 0.30, -0.62), (0.60, 0.75, -0.25))
L1_E = (V(-0.064, -0.162, -0.312), Q((-0.26, -0.85, -0.46), (0.62, 0.20, 0.76)))
L1 = feasible(*light_keys(LIGHT_TIMES, REST, L1_W, L1_C, centre_root(L1_C, 0.40), (0.010, 0.014, 0.004), 0.16,
                          end=L1_E, back=(V(0.052, -0.154, -0.330), 0.62),
                          over=((0.004, 0.005, -0.002), (0.0, 2.5, 1.5))), contact=3)
L1_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.066, V(0.30, -0.25, 0.60)), (T_HIT, V(-0.05, 0.30, -0.50)),
                  (0.160, V(-0.50, 0.95, -2.10)), (0.220, V(-0.32, 0.72, -1.55)), (0.300, V(0.08, -0.08, 0.22)),
                  (DUR_L, V(0, 0, 0)))


def light1_pose(t):
    p = dr.DaggerPose()
    L1.apply(p, t)
    p.fingers_r = grip_squeeze(dr.FIST, t, T_HIT, -6.0, 0.06, loosen=4.0, t_loose=0.066)
    L1_CAM(p, t)
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand: the hand drops first (the point passes just over the crosshair, the blade under it), coils low-left across
# the body, the whip up through the centre and on out high-right
L2_W = (V(0.030, -0.140, -0.312), Q((-0.66, -0.28, -0.70), (-0.15, -0.35, 0.92)))
L2_C = Q((-0.55, 0.45, -0.70), (-0.65, -0.70, 0.10))
L2 = feasible(*light_keys(LIGHT_TIMES, REST, L2_W, L2_C, centre_root(L2_C, 0.40), (0.012, -0.060, 0.006), 0.08,
                          end=(V(0.0, 0.010, 0.010), V(0.0, 0.0, 0.0)), back=(V(0.176, -0.044, -0.316), 0.60),
                          over=((-0.004, -0.004, 0.002), (0.0, -2.0, -1.5)),
                          wind_mode={"pos": "stop", "rot": "flow"}), contact=3)
L2_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.066, V(-0.22, 0.25, -0.60)), (T_HIT, V(0.10, -0.30, 0.50)),
                  (0.160, V(0.55, -0.95, 2.10)), (0.220, V(0.36, -0.72, 1.55)), (0.300, V(-0.08, 0.08, -0.22)),
                  (DUR_L, V(0, 0, 0)))


def light2_pose(t):
    p = dr.DaggerPose()
    L2.apply(p, t)
    p.fingers_r = grip_squeeze(dr.FIST, t, T_HIT, -6.0, 0.06, loosen=4.0, t_loose=0.066)
    L2_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ heavy
# a quick straight thrust: a small forward dip, the coil to the shoulder (creeping back while it loads), the thrust
# snapping out of it and accelerating all the way to full extension at 0.33 where it stops dead, a shudder and a
# recoil, then home along an arc
HV_P = ((-0.28, 0.42, -0.86), (0.10, 0.90, 0.40))
HV_P2 = ((-0.25, 0.47, -0.85), (0.10, 0.90, 0.40))
HV_E = ((-0.36, 0.38, -0.85), (0.10, 0.90, 0.40))
HV = feasible((0.000, dr.REST_T, dr.REST_Q, "stop"),
              (0.045, *at_rest((-0.004, -0.006, -0.010), (0.0, -3.0, 0.0)), "stop"),
              (0.165, V(0.158, -0.080, -0.252), HV_P, "flow"),
              (0.245, V(0.170, -0.076, -0.236), HV_P2, "stop"),
              (T_EXT, V(0.078, -0.072, -0.452), HV_E, {"pos": ("hit", 2.1), "rot": ("hit", 1.6)}),
              (0.390, V(0.090, -0.078, -0.432), turned(Q(*HV_E), V(0.0, -3.0, 0.0)), "stop"),
              (0.550, V(0.112, -0.098, -0.350), mix(Q(*HV_E), dr.REST_Q, 0.78), "flow"),
              (0.700, *at_rest((0.003, -0.004, 0.004), (0.0, -1.5, 0.0)), ("flow", 0.6)),
              (DUR_H, dr.REST_T, dr.REST_Q, "stop"))
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.045, V(-0.002, 0.0, -0.004), "stop"),
                     (0.245, V(0.006, -0.005, 0.018), "stop"), (T_EXT, V(-0.024, 0.006, -0.034), "hit"),
                     (0.390, V(-0.018, 0.004, -0.026), "stop"), (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_EXT, V(0.0, 0.0015, 0.004), 9.0, 0.35)]
HV_RING_ROT = [(T_EXT, V(0.0, 1.6, 0.8), 9.0, 0.35)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.045, V(-0.15, 0.0, 0.0)), (0.245, V(0.60, 0.15, 0.50)),
                  (T_EXT, V(-0.55, -0.10, -0.25), "hit"), (0.520, V(-0.15, 0.0, -0.05)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_EXT, V(-1.1, -0.15, -0.45), 7.0, 0.40)])


def heavy_pose(t):
    p = dr.DaggerPose()
    HV.apply(p, t)
    nudge(p, rings(t, HV_RING, DUR_H), rings(t, HV_RING_ROT, DUR_H))
    p.shoulder_r = HV_SHOULDER(t)
    brace = ease("inout", t, 0.12, 0.23) * (1 - ease("inout", t, 0.45, 0.66))     # thumb onto the guard
    p.fingers_r = hands.adjust(grip_squeeze(dr.FIST, t, T_EXT, -8.0, 0.08), thumb=(26.0 * brace, 20.0 * brace))
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# drawn from below in a reverse grip (blade pointing back and down; off screen for the first tenth of a second), the
# wrist flicks and the hand loosens: the dagger spins half a turn through the fingers, fastest mid-turn, into the
# forward grip, where the fist catches it - it turns on a little past and is pulled back, the hand jolts, the camera
# answers - and it settles into the ready stance a hair past it
T_FLIP, T_CATCH, DUR_D = 0.20, 0.38, 0.60
DR = feasible((0.00, V(0.185, -0.335, -0.255), ((-0.15, 0.40, -0.90), (0.85, 0.40, 0.20)), "stop"),
              (0.09, V(0.172, -0.262, -0.276), ((-0.18, 0.44, -0.88), (0.78, 0.50, 0.24)), "flow"),
              (0.18, V(0.140, -0.120, -0.315), ((-0.25, 0.52, -0.82), (0.60, 0.70, 0.30)), "flow"),
              (T_CATCH, V(0.122, -0.098, -0.333), ((-0.30, 0.58, -0.76), (0.20, 0.72, 0.66)), "flow"),
              (0.47, V(0.116, -0.090, -0.336), ((-0.28, 0.57, -0.77), (0.08, 0.72, 0.69)), "flow"),
              (0.54, *at_rest((-0.003, 0.004, -0.003), (0.0, 2.0, 0.0)), ("flow", 0.6)),
              (DUR_D, dr.REST_T, dr.REST_Q, "stop"))
DR_FLICK = Curve((0.12, 0.0), (0.18, 8.0), (0.25, -9.0), (0.31, -2.0), (0.36, 0.0))     # knife-local pitch, degrees
DR_SPIN = Curve((0.0, 180.0), (T_FLIP, 180.0, ("snap", 0.5)), (T_CATCH, 0.0, ("hit", 1.6)), (DUR_D, 0.0))
DR_SPIN_OVER = [(T_CATCH, -7.0, 8.0, 0.45)]                    # the catch: it turns on a little past the fist
DR_JOLT = [(T_CATCH, V(-0.002, -0.004, 0.003), 8.0, 0.40)]
DR_JOLT_ROT = [(T_CATCH, V(0.6, -2.5, 1.0), 8.0, 0.40)]
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.18, V(0.3, 0.0, 0.4)), (0.30, V(-0.1, 0.0, -0.1)), (DUR_D, V(0, 0, 0)),
                  accents=[(T_CATCH, V(-0.5, 0.05, -0.35), 7.0, 0.40)])


def draw_pose(t):
    p = dr.DaggerPose()
    DR.apply(p, t)
    nudge(p, rings(t, DR_JOLT, DUR_D), rings(t, DR_JOLT_ROT, DUR_D) + V(0.0, float(DR_FLICK(t)), 0.0))
    p.spin = float(DR_SPIN(t) + rings(t, DR_SPIN_OVER, DUR_D))
    open_f = ease("inout", t, T_FLIP - 0.04, T_FLIP + 0.03) * (1 - ease("in2", t, T_CATCH - 0.05, T_CATCH))
    p.fingers_r = blend_curls(grip_squeeze(dr.FIST, t, T_CATCH + 0.025, -7.0, 0.05), loose(1.0), 0.8 * open_f)
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# raise and turn the palm up (the left flat to the eye); two spins on the finger, driven by the wrist and caught; flip
# to a reverse grip, a slow menacing turn, flip back; the left hand presents the blade (a moving hold); back to the
# stance. Every catch turns on a little past and is pulled back, the hand and the camera answer it.
DUR_I = 3.40
P_T, Q_T = dr.reachable(V(0.100, -0.096, -0.330), knife_q_flat((-0.40, 0.80, -0.44), (0.30, -0.20, -0.93)))
P_R, Q_R = dr.reachable(V(0.106, -0.104, -0.322), knife_q_flat((-0.30, 0.78, -0.55), (0.45, -0.10, -0.89)))
P_SHOW, Q_SHOW = dr.reachable(V(0.094, -0.082, -0.322), knife_q_flat((-0.85, 0.30, -0.43), (-0.10, 0.30, 0.95)))
SPINS = ((0.36, 1.12, 720.0), (1.30, 1.60, 180.0), (2.24, 2.54, 180.0))      # (start, end, degrees): 3 turns in all
INS = feasible((0.00, dr.REST_T, dr.REST_Q, "stop"),
               (0.22, P_T + V(-0.004, 0.006, 0.002), turned(Q_T, V(0.0, 3.0, 2.0)), ("flow", 0.7)),
               (0.32, P_T, Q_T, "flow"),
               (1.12, P_T + V(0.002, -0.002, 0.0), turned(Q_T, V(1.0, -1.0, 0.0)), "flow"),
               (1.62, P_R, Q_R, "flow"),
               (2.18, P_R + V(-0.002, 0.002, 0.0), turned(Q_R, V(-1.5, 0.0, 1.0)), "flow"),
               (2.58, P_T, Q_T, "flow"),
               (2.80, P_SHOW, Q_SHOW, "flow"),
               (3.00, P_SHOW + V(-0.003, 0.002, 0.0), turned(Q_SHOW, V(3.0, 0.0, 2.0)), "flow"),
               (3.14, P_SHOW + V(-0.004, 0.003, 0.0), turned(Q_SHOW, V(-1.0, 0.0, 0.5)), "flow"),
               (3.31, *at_rest((-0.002, 0.003, -0.002), (0.0, 1.8, 0.0)), ("flow", 0.6)),
               (DUR_I, dr.REST_T, dr.REST_Q, "stop"))
# spin angle: each spin flicked off (snap), slowing evenly as it turns on the finger, caught (hit) while still turning
# and carried on a little past the catch
def _spin_curve():
    keys, total = [(0.0, 0.0)], 0.0
    for t0, t1, deg in SPINS:
        keys.append((t0, total, ("snap", 1.5)))         # flicked off fast, slowed by the finger's friction
        total += deg
        keys.append((t1, total, ("hit", 0.5)))          # and caught while it still turns
    keys.append((DUR_I, total))
    return Curve(*keys)


SPIN = _spin_curve()
SPIN_OVER = [(t1, 4.0 * np.sign(deg), 8.0, 0.45) for _, t1, deg in SPINS]
EV_INSPECT = [(0.36, "flip"), (0.74, "flip"), (1.12, "catch"), (1.30, "flip"), (1.60, "catch"), (2.24, "flip"),
              (2.54, "catch")]
INS_JOLT = [(t1, V(0.0, -0.0025, 0.0015), 8.0, 0.40) for _, t1, _ in SPINS]
INS_JOLT_ROT = [(t1, V(0.0, -1.8, 0.8), 8.0, 0.40) for _, t1, _ in SPINS]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.32, V(0.4, 0.0, 1.0)), (1.10, V(0.3, 0.0, 0.8)),
                   (1.62, V(0.2, -0.4, -1.0)), (2.18, V(-0.2, 0.3, -1.2)), (2.60, V(0.3, 0.0, 0.8)),
                   (3.00, V(-0.2, 0.4, 1.2)), (DUR_I, V(0, 0, 0)),
                   accents=[(t1, V(-0.3, 0.0, -0.2), 8.0, 0.40) for _, t1, _ in SPINS])


def spin_pump(t, spin):
    """The wrist driving the long spin on the finger: one pump per turn, phase-locked to the spin angle."""
    t0, t1, _ = SPINS[0]
    w = smoothstep(t0 - 0.03, t0 + 0.08, t) * (1 - smoothstep(t1 - 0.10, t1, t))
    ph = np.radians(spin)
    return (V(0.0, 0.0035 * np.sin(ph + 1.0), 0.0012 * np.sin(ph + 2.6)) * w,
            V(2.5 * np.sin(ph + 0.2), 3.5 * np.sin(ph + 2.2), 6.0 * np.sin(ph + 0.6)) * w)


def inspect_pose(t):
    p = dr.DaggerPose()
    INS.apply(p, t)
    spin = float(SPIN(t))
    p.spin = spin + float(rings(t, SPIN_OVER, DUR_I))
    dpos, drot = spin_pump(t, spin)
    nudge(p, dpos + rings(t, INS_JOLT, DUR_I), drot + rings(t, INS_JOLT_ROT, DUR_I))
    # in the reverse grip the wrist turns the blade out and back (a slow menacing turn)
    turn = np.sin(np.pi * np.clip((t - 1.66) / 0.50, 0, 1)) ** 2
    p.knife_q = quat_mul(p.knife_q, quat_axis_angle((0, 0, 1), 24.0 * turn))
    open_f = 0.0
    for t0, t1, _ in SPINS:
        open_f = max(open_f, ease("inout", t, t0 - 0.05, t0 + 0.04) * (1 - ease("in2", t, t1 - 0.06, t1)))
    k = -6.0 * sum(np.exp(-((t - t1 - 0.02) / 0.05) ** 2) for _, t1, _ in SPINS)        # the fist closes on each catch
    fist = hands.adjust(dr.FIST, index=(k, k * 0.5), middle=(k, k * 0.5), ring=(k, 0), pinky=(k, 0), thumb=(k * 0.6, 0))
    p.fingers_r = blend_curls(fist, loose(1.0), 0.5 * open_f)
    # left hand presents the blade
    w = ease("inout", t, 2.62, 2.82) * (1 - ease("inout", t, 3.06, 3.28))
    mid = p.knife_point(dr.BLADE_MID)
    present = (mid + V(0.020, -0.078, 0.030), armrig.palm_from_back((-0.2, -1.0, 0.25), (0.6, 0.2, -0.75), "L"),
               hands.blend({"relaxed": 0.6, "spread": 0.4}))
    rest_l = (dr.LEFT_REST[0], present[1], hands.blend({"relaxed": 1.0}))
    p.hand_l = armrig.blend_targets([(1 - w + 1e-6, rest_l), (w, present)])
    p.left_scale = 1.0 if 2.58 <= t < 3.32 else 0.0
    INS_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ sprint
# lowered running carry: the knife arm swings with the stride, the dagger trailing its swing, a hard drop on every
# foot strike; the left fist pumps big and lingers at the ends of its swing
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_C, Q_RUN = dr.reachable(V(0.135, -0.155, -0.288), Q((-0.22, 0.25, -0.94), (0.05, 1.0, 0.25)))
RUN_A = V(-0.012, 0.055, -0.050)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def step_bounce(t):
    return periodic_bounce(t, DUR_S / 2, 0.12, 1.6)


def sprint_pose(t):
    p = dr.DaggerPose()
    b = step_bounce(t)
    side = np.cos(W_S * (t - 0.04))
    lag = np.cos(W_S * t - 0.55)
    p.knife_t = RUN_C + RUN_A * pump(np.cos(W_S * t)) + V(0.003 * side, -0.010 * b, 0.002 * b)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.0 * np.sin(W_S * t), -7.0 * lag - 2.5 * b, 3.0 * side))
    p.fingers_r = hands.adjust(dr.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    p.hand_l = (L_RUN_C - L_RUN_A * pump(np.cos(W_S * t)) + V(0.0, -0.006 * b, 0.0), None, hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


# -------------------------------------------------------------------------------------- walk
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def bounce(t, period, lag=0.12, sharp=1.6):
    return periodic_bounce(t, period, lag, sharp)


def walk_pose(t):
    """Knife at the ready, the arm countering the steps a few hundredths behind the body; the grip tightens a little
    on every impact."""
    p = dr.DaggerPose()
    b = bounce(t, DUR_W / 2, 0.10, 1.4)
    b_late = bounce(t - 0.04, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.knife_t = dr.REST_T + V(0.004 * side, 0.003 - 0.006 * b, 0.002 * b - 0.001 * np.sin(W_W * t))
    p.knife_q = quat_mul(dr.REST_Q, quat_euler(1.2 * np.cos(W_W * (t - 0.09)), 0.4 - 1.2 * b_late,
                                               1.6 * np.sin(W_W * (t - 0.04))))
    p.fingers_r = hands.adjust(dr.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    return p


# -------------------------------------------------------------------------------- tac_sprint
# high guard: the dagger raised beside the face, point up; the knife arm pumps short and hard (the blade whipping a
# little behind it), the left fist pumps big
DUR_T = 0.58
W_T = 2 * np.pi / DUR_T
TAC_C, Q_TAC = dr.reachable(V(0.104, -0.070, -0.270), Q((-0.20, 0.84, -0.50), (0.30, 0.45, 0.84)))
TAC_A = V(-0.006, 0.020, -0.016)


def tac_sprint_pose(t):
    p = dr.DaggerPose()
    b = bounce(t, DUR_T / 2, 0.10, 1.8)
    side = np.cos(W_T * (t - 0.035))
    lag = np.cos(W_T * t - 0.6)
    p.knife_t = TAC_C + TAC_A * pump(np.cos(W_T * t)) + V(0.004 * side, 0.004 - 0.013 * b, 0.003 * b)
    p.knife_q = quat_mul(Q_TAC, quat_euler(2.0 * side, -3.5 * lag - 2.8 * b, 2.5 * np.sin(W_T * t)))
    p.fingers_r = hands.adjust(dr.FIST, index=(-3.0 * b, 0))
    p.hand_l = (L_RUN_C + V(0.0, 0.012 - 0.006 * b, 0.0) - L_RUN_A * 1.1 * pump(np.cos(W_T * t)), None,
                hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "equip"), (T_FLIP, "flip"), (T_CATCH, "catch")], fps=60,
         extra_times=(0.18, T_FLIP, T_CATCH, 0.47)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.06, "swing")], fps=60, extra_times=(0.066, T_HIT, 0.185)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.06, "swing")], fps=60, extra_times=(0.066, T_HIT, 0.185)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.24, "swing_heavy")], fps=60, extra_times=(0.245, T_EXT, 0.39)),
    Clip("inspect", DUR_I, inspect_pose, events=EV_INSPECT, fps=60,
         extra_times=tuple(t for t, _ in EV_INSPECT) + (2.58, 3.32)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
