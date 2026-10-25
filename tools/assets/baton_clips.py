"""Expandable baton clips (VIEWMODEL_SPEC sections 31 and 45). Authored like the crowbar's (crowbar_clips: knife_clips
Motion splined through key poses, each key with its elbow pole so the hammer grip can hold the baton where the swing
wants it, impact rings, a camera that coils with the wind-ups and leans along the swings); contact keys sit on the
contact times of knives.json: light 0.14 of 0.42 s, heavy 0.32 of 0.80 s. The baton is light (≈ 0.5 kg) and its
weight is near the hand, so the strikes are snapped by the wrist and the forearm - short cocks, fast whips, a short
follow-through - and the signature is the flick that throws the shafts out of the handle until they lock with a
crack (`ext1`, `ext2` on the rig: shaft_2 rides out with shaft_1 and seats a hair later)."""
import numpy as np

import baton_rig as br
import hands
from anim import Curve
from knife_clips import (CamCurve, Motion, Spline, ease, grip_squeeze, light_keys, nudge, periodic_bounce, pump, rings,
                         turned)
from p9_clips import Clip
from p9_rig import POLE_R
from vmath import norm, quat_euler, quat_mul, quat_rotate

V = lambda *a: np.array(a, float)
Q = br.knife_quat
T_HIT, DUR_L = 0.14, 0.42        # light attacks: the tip's line crosses the screen centre (knives.json)
T_EXT, DUR_H = 0.32, 0.80        # heavy: the overhead strike lands
REST = (br.REST_T, br.REST_Q)
POLE = norm(POLE_R)


def centre_root(q, depth):
    """Knife root such that the baton's hit point lies on the screen-centre ray at `depth`."""
    return V(0, 0, -depth) - quat_rotate(q, br.HIT)


def reach(pos, q, pole=None):
    """Knife root / orientation the hand reaches for an authored pose with this elbow pole (the wrist limits)."""
    p = br.BatonPose()
    p.knife_t, p.knife_q = np.asarray(pos, float).copy(), np.asarray(q, float).copy()
    p.pole_r = POLE if pole is None else norm(pole)
    p.solve()
    return p.knife_t.copy(), p.knife_q.copy()


class Swing:
    """Root motion (knife_clips.Motion) plus the elbow's pole splined through the same keys (as crowbar_clips)."""

    def __init__(self, motion, poles):
        self.motion, self.poles = motion, poles

    def apply(self, p, t):
        self.motion.apply(p, t)
        p.pole_r = norm(self.poles(t))


def feasible(*keys, contact=None, extra=None, iters=8):
    """Keys (t, pos, orientation, mode[, elbow pole]): every key takes the orientation the wrist allows with its pole;
    the contact key is then re-centred so the solved hit point crosses the screen centre exactly at its time."""
    keys = [list(k) + [None] * (5 - len(k)) for k in keys]
    for k in keys:
        q = Q(*k[2]) if isinstance(k[2], tuple) else k[2]
        if np.allclose(k[1], br.REST_T) and abs(float(np.dot(q, br.REST_Q))) > 1 - 1e-9:
            k[2] = q
            continue
        k[1], k[2] = reach(k[1], q, k[4])
    poles = Spline(*[(k[0], POLE if k[4] is None else norm(k[4]), "flow") for k in keys])

    def build():
        return Swing(Motion(*[tuple(k[:4]) for k in keys]), poles)
    if contact is not None:
        tc = keys[contact][0]
        for _ in range(iters):
            p = br.BatonPose()
            build().apply(p, tc)
            if extra is not None:
                extra(p, tc)
            p.solve()
            mid = p.knife_point(br.HIT)
            keys[contact][1] = keys[contact][1] - V(mid[0], mid[1], 0.0)
    return build()


def at_rest(dt=(0, 0, 0), drot=(0, 0, 0)):
    return br.REST_T + V(*dt), turned(br.REST_Q, V(*drot))


def with_poles(keys, poles):
    return [tuple(k) + (pole,) for k, pole in zip(keys, poles)]


def bounce(t, period, lag=0.12, sharp=1.6):
    return periodic_bounce(t, period, lag, sharp)


def snap_open(t, t0, t_lock, lag=0.012):
    """(ext1, ext2) of a flick that throws the shafts out at t0 and seats them at t_lock: they accelerate out (the tip
    shaft a hair behind the middle one) and stop dead on their friction tapers."""
    def one(a, b):
        u = np.clip((t - a) / max(b - a, 1e-6), 0.0, 1.0)
        return u * u * (3 - 2 * u) if u < 0.5 else 1 - 0.5 * (2 - 2 * u) ** 2.4
    return one(t0, t_lock), one(t0 + lag, t_lock + lag)


def snap_shut(t, t0, dur=0.035):
    """(ext1, ext2) of the shafts driven back into the handle by a blow on the tip (both together, very fast)."""
    u = np.clip((t - t0) / dur, 0.0, 1.0)
    return 1 - u ** 1.6, 1 - u ** 1.3


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = br.BatonPose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = br.REST_T + V(0.0004 * np.sin(w + 0.5), 0.0007 * np.sin(w), 0.0003 * np.sin(w + 1.2))
    p.knife_q = quat_mul(br.REST_Q, quat_euler(0.30 * np.sin(w + 0.2), 0.45 * np.sin(w - 0.6), 0.35 * np.sin(w + 0.9)))
    p.fingers_r = hands.adjust(br.FIST, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)),
                               thumb=(1.5 * np.sin(w + 0.8), 1.0 * np.sin(w + 1.3)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand strike: a short cock back over the right shoulder, the forearm drives down and across and the wrist snaps
# the baton through the centre at 0.14, a short follow-through low left, straight back to the guard
LIGHT_TIMES = (0.035, 0.092, T_HIT, 0.215, 0.285, 0.355, DUR_L)
L1_W = (V(0.200, -0.085, -0.255), Q((-0.05, 0.75, 0.66), (0.50, 0.40, 0.75)))
L1_C = Q((-0.52, 0.34, -0.78), (0.55, 0.83, 0.05))
L1_E = (V(0.040, -0.250, -0.330), Q((-0.72, -0.02, -0.69), (-0.10, 0.95, -0.20)))
L1_POLES = (None, None, (0.85, -0.51, -0.09), (0.39, 0.87, -0.29), (0.40, 0.90, 0.20), (0.90, 0.20, 0.10), None, None)
L1 = feasible(*with_poles(light_keys(LIGHT_TIMES, REST, L1_W, L1_C, centre_root(L1_C, 0.62), (0.008, 0.015, 0.008),
                                     0.22, end=L1_E, back=(V(0.100, -0.215, -0.315), 0.62),
                                     over=((0.003, 0.004, -0.002), (0.0, 2.0, 1.0))), L1_POLES), contact=3)
L1_SHOULDER = Spline((0.0, V(0, 0, 0), "stop"), (0.092, V(0.008, 0.008, 0.015), "stop"),
                     (T_HIT, V(-0.010, -0.004, -0.015), "flow"), (0.215, V(-0.020, -0.008, -0.015), "stop"),
                     (DUR_L, V(0, 0, 0), "stop"))
L1_RING = [(T_HIT, V(0.0, -0.0015, 0.0015), 8.0, 0.40)]
L1_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.092, V(0.35, -0.35, 0.80)), (T_HIT, V(-0.25, 0.25, -0.60)),
                  (0.20, V(-0.60, 0.90, -1.90)), (0.27, V(-0.35, 0.55, -1.20)), (0.35, V(0.08, -0.08, 0.20)),
                  (DUR_L, V(0, 0, 0)))


def light1_pose(t):
    p = br.BatonPose()
    L1.apply(p, t)
    nudge(p, rings(t, L1_RING, DUR_L))
    p.shoulder_r = L1_SHOULDER(t)
    p.fingers_r = grip_squeeze(br.FIST, t, T_HIT, -7.0, 0.05, loosen=3.0, t_loose=0.092)
    L1_CAM(p, t)
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand: cocked across to the left shoulder, whipped out to the right through the centre, the forearm rolling over
L2_W = (V(-0.010, -0.150, -0.225), Q((-0.62, 0.70, 0.36), (0.70, 0.0, -0.70)))
L2_C = Q((-0.14, 0.40, -0.91), (1.0, 0.0, 0.0))
L2_E = (V(0.245, -0.135, -0.290), Q((0.78, 0.44, -0.45), (0.40, 0.0, 0.90)))
L2_POLES = (None, None, (0.72, 0.45, 0.54), (0.85, -0.51, -0.09), (0.49, -0.78, -0.39), None, None, None)
L2 = feasible(*with_poles(light_keys(LIGHT_TIMES, REST, L2_W, L2_C, centre_root(L2_C, 0.62), (-0.010, 0.010, 0.006),
                                     0.18, end=L2_E, back=(V(0.200, -0.190, -0.300), 0.62),
                                     over=((-0.003, 0.003, 0.002), (0.0, -2.0, -1.0)),
                                     wind_mode={"pos": "stop", "rot": "flow"}), L2_POLES), contact=3)
L2_SHOULDER = Spline((0.0, V(0, 0, 0), "stop"), (0.092, V(-0.015, 0.005, -0.008), "stop"),
                     (T_HIT, V(0.008, 0.0, -0.018), "flow"), (0.215, V(0.015, 0.003, -0.008), "stop"),
                     (DUR_L, V(0, 0, 0), "stop"))
L2_RING = [(T_HIT, V(-0.0015, 0.0, 0.0015), 8.0, 0.40)]
L2_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.092, V(0.15, 0.35, -0.70)), (T_HIT, V(-0.08, -0.25, 0.55)),
                  (0.20, V(0.30, -0.90, 1.80)), (0.27, V(0.20, -0.55, 1.10)), (0.35, V(-0.05, 0.08, -0.20)),
                  (DUR_L, V(0, 0, 0)))


def light2_pose(t):
    p = br.BatonPose()
    L2.apply(p, t)
    nudge(p, rings(t, L2_RING, DUR_L))
    p.shoulder_r = L2_SHOULDER(t)
    p.fingers_r = grip_squeeze(br.FIST, t, T_HIT, -7.0, 0.05, loosen=3.0, t_loose=0.092)
    L2_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ heavy
# one-handed overhead strike: the arm lifts the baton high, the tip hanging back over the shoulder, a beat, then the
# shoulder and the arm drive it down over the top through the centre at 0.32 where it stops dead on the target, a
# rebound, and back down into the guard
HV_C = Q((-0.04, 0.45, -0.89), (0.0, 0.89, 0.45))
HV = feasible((0.000, br.REST_T, br.REST_Q, "stop"),
              (0.080, V(0.130, -0.150, -0.285), Q((-0.10, 0.80, -0.59), (0.30, 0.59, 0.81)), "flow",
               (0.94, -0.19, 0.28)),
              (0.200, V(0.100, 0.110, -0.160), Q((0.02, 0.62, 0.78), (0.05, -0.78, 0.62)), "flow"),
              (0.255, V(0.100, 0.130, -0.140), Q((0.03, 0.45, 0.89), (0.05, -0.89, 0.45)), "stop"),
              (T_EXT, centre_root(HV_C, 0.64), HV_C, {"pos": ("hit", 2.2), "rot": ("hit", 2.0)}, (0.92, 0.28, 0.28)),
              (0.380, V(0.025, -0.165, -0.300), Q((-0.04, 0.54, -0.84), (0.0, 0.84, 0.54)), "stop", (0.92, 0.28, 0.28)),
              (0.540, V(0.100, -0.175, -0.300), Q((-0.16, 0.52, -0.84), (0.6, 0.6, 0.5)), "flow", (0.94, -0.19, 0.28)),
              (0.680, *at_rest((-0.004, 0.007, -0.003), (0.0, 2.5, 0.0)), ("flow", 0.6)),
              (DUR_H, br.REST_T, br.REST_Q, "stop"), contact=4)
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.255, V(0.0, 0.030, 0.030), "stop"),
                     (T_EXT, V(-0.010, -0.020, -0.040), "hit"), (0.40, V(-0.006, -0.012, -0.025), "stop"),
                     (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_EXT, V(0.0, 0.003, 0.002), 10.0, 0.30)]
HV_RING_ROT = [(T_EXT, V(0.0, 2.5, 0.6), 12.0, 0.28)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.08, V(0.15, 0.0, 0.0)), (0.255, V(1.20, 0.15, 0.35)),
                  (T_EXT, V(-1.10, -0.10, -0.25), "hit"), (0.50, V(-0.30, 0.0, -0.08)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_EXT, V(-0.8, 0.12, 0.5), 7.5, 0.35)])


def heavy_pose(t):
    p = br.BatonPose()
    HV.apply(p, t)
    nudge(p, rings(t, HV_RING, DUR_H), rings(t, HV_RING_ROT, DUR_H))
    p.shoulder_r = HV_SHOULDER(t)
    p.fingers_r = grip_squeeze(br.FIST, t, T_EXT, -9.0, 0.07)
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# drawn collapsed from below on the right; the wrist cocks it back up beside the shoulder and flicks it forward and
# down: the shafts fly out of the handle and lock with a crack at 0.31 (the hand jolts, the camera answers), the arm
# rides the recoil back up and settles into the guard
T_FLICK, T_LOCK, DUR_D = 0.255, 0.310, 0.65
DR = feasible((0.00, V(0.200, -0.360, -0.230), Q((0.15, -0.20, -0.97), (0.60, 0.75, -0.10)), "stop"),
              (0.14, V(0.190, -0.230, -0.255), Q((0.0, 0.40, -0.92), (0.85, 0.45, 0.20)), "flow"),
              (0.215, V(0.180, -0.170, -0.250), Q((-0.05, 0.85, 0.52), (0.9, 0.1, 0.2)), "stop"),
              (T_LOCK, V(0.170, -0.175, -0.310), Q((-0.10, 0.40, -0.91), (0.9, 0.4, 0.0)), {"pos": "flow", "rot": ("hit", 1.6)}),
              (0.42, *at_rest((0.002, 0.006, 0.002), (0.0, 4.0, 0.0)), "stop"),
              (0.54, *at_rest((-0.001, -0.002, -0.001), (0.0, -1.5, 0.0)), ("flow", 0.6)),
              (DUR_D, br.REST_T, br.REST_Q, "stop"))
DR_JOLT = [(T_LOCK, V(0.0, -0.003, -0.002), 10.0, 0.35)]
DR_JOLT_ROT = [(T_LOCK, V(0.0, -3.5, 0.6), 11.0, 0.32)]
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.215, V(0.5, 0.1, 0.4)), (T_LOCK, V(-0.4, 0.0, -0.3)), (DUR_D, V(0, 0, 0)),
                  accents=[(T_LOCK, V(-0.6, 0.0, 0.3), 9.0, 0.35)])


def draw_pose(t):
    p = br.BatonPose()
    DR.apply(p, t)
    nudge(p, rings(t, DR_JOLT, DUR_D), rings(t, DR_JOLT_ROT, DUR_D))
    p.ext1, p.ext2 = snap_open(t, T_FLICK, T_LOCK)
    p.fingers_r = grip_squeeze(br.FIST, t, T_LOCK, -7.0, 0.05)
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# the open baton held up and looked along, turned in the fingers; lifted and driven tip-first down at the floor - the
# blow on the tip drives the shafts back into the handle (a thud and a clack, the hand jolts) - the collapsed baton
# brought up to look at, cocked back and flicked open again, back to the guard
DUR_I = 3.40
T_SLAM, T_FLICK2, T_LOCK2 = 1.36, 2.30, 2.355
P_LOOK, Q_LOOK = reach(V(0.115, -0.134, -0.239), Q((-0.30, 0.35, -0.89), (0.0, 1.0, 0.0)), (0.72, 0.45, 0.54))
P_LIFT, Q_LIFT = reach(V(0.160, -0.150, -0.300), Q((0.0, -0.70, -0.71), (0.9, 0.0, 0.4)), (0.92, 0.28, 0.28))
P_SLAM, Q_SLAM = reach(V(0.160, -0.300, -0.320), Q((0.05, -0.90, -0.43), (0.9, 0.0, 0.4)), (0.92, 0.28, 0.28))
P_SHUT, Q_SHUT = reach(V(0.095, -0.120, -0.235), Q((-0.25, 0.55, -0.80), (0.0, 1.0, 0.0)), (0.72, 0.45, 0.54))
INS = feasible((0.00, br.REST_T, br.REST_Q, "stop"),
               (0.40, P_LOOK + V(-0.004, 0.006, 0.0), turned(Q_LOOK, V(0.0, 2.0, 3.0)), ("flow", 0.7), (0.72, 0.45, 0.54)),
               (0.85, P_LOOK, turned(Q_LOOK, V(0.0, -1.5, 0.0)), "flow", (0.72, 0.45, 0.54)),
               (1.18, P_LIFT, Q_LIFT, "stop", (0.92, 0.28, 0.28)),
               (T_SLAM, P_SLAM, Q_SLAM, "hit", (0.92, 0.28, 0.28)),
               (1.50, P_SLAM + V(0.0, 0.025, 0.004), turned(Q_SLAM, V(0.0, 3.0, 0.0)), "stop", (0.92, 0.28, 0.28)),
               (1.85, P_SHUT, Q_SHUT, "flow", (0.72, 0.45, 0.54)),
               (2.10, P_SHUT + V(0.003, -0.002, 0.0), turned(Q_SHUT, V(0.0, -2.0, 1.5)), "flow", (0.72, 0.45, 0.54)),
               (2.25, V(0.180, -0.170, -0.250), Q((-0.05, 0.85, 0.52), (0.9, 0.1, 0.2)), "stop"),
               (T_LOCK2, V(0.170, -0.175, -0.310), Q((-0.10, 0.40, -0.91), (0.9, 0.4, 0.0)),
                {"pos": "flow", "rot": ("hit", 1.6)}),
               (2.70, *at_rest((0.002, 0.006, 0.002), (0.0, 4.0, 0.0)), "stop"),
               (3.05, *at_rest((-0.001, -0.002, -0.001), (0.0, -1.2, 0.0)), ("flow", 0.6)),
               (DUR_I, br.REST_T, br.REST_Q, "stop"))
INS_ROLL = Curve((0.0, 0.0), (0.42, 0.0), (0.85, 70.0), (1.10, 0.0), (DUR_I, 0.0))      # turned in the fingers
INS_JOLT = [(T_SLAM, V(0.0, 0.006, 0.002), 9.0, 0.35), (T_LOCK2, V(0.0, -0.003, -0.002), 10.0, 0.35)]
INS_JOLT_ROT = [(T_SLAM, V(0.0, 3.5, 0.0), 9.0, 0.35), (T_LOCK2, V(0.0, -3.5, 0.6), 11.0, 0.32)]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.45, V(0.6, 0.3, 1.0)), (1.10, V(0.2, -0.2, 0.4)),
                   (T_SLAM, V(-1.0, 0.0, -0.3), "hit"), (1.85, V(0.5, 0.2, 0.6)), (2.25, V(0.4, 0.0, 0.3)),
                   (T_LOCK2, V(-0.3, 0.0, -0.2)), (DUR_I, V(0, 0, 0)),
                   accents=[(T_SLAM, V(-0.6, 0.0, 0.3), 8.0, 0.40), (T_LOCK2, V(-0.5, 0.0, 0.3), 9.0, 0.35)])
EV_INSPECT = [(T_SLAM, "baton_close"), (T_LOCK2, "baton_open")]


def inspect_pose(t):
    p = br.BatonPose()
    INS.apply(p, t)
    nudge(p, rings(t, INS_JOLT, DUR_I), rings(t, INS_JOLT_ROT, DUR_I))
    p.roll = float(INS_ROLL(t))
    if t < T_FLICK2:
        p.ext1, p.ext2 = snap_shut(t, T_SLAM)
    else:
        p.ext1, p.ext2 = snap_open(t, T_FLICK2, T_LOCK2)
    open_f = ease("inout", t, 0.40, 0.55) * (1 - ease("inout", t, 0.95, 1.10))       # the fingers loosen to turn it
    loose = hands.blend({"fist": 0.6, "relaxed": 0.4})
    base = grip_squeeze(br.FIST, t, T_SLAM, -8.0, 0.06)
    base = grip_squeeze(base, t, T_LOCK2, -6.0, 0.05)
    p.fingers_r = {f: (1 - 0.6 * open_f) * np.asarray(base[f], float) + 0.6 * open_f * np.asarray(loose[f], float)
                   for f in hands.FINGERS}
    INS_CAM(p, t)
    return p


# -------------------------------------------------------------------------------------- walk
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def walk_pose(t):
    """The baton at the guard, the arm countering the steps a little behind the body."""
    p = br.BatonPose()
    b = bounce(t, DUR_W / 2, 0.10, 1.4)
    b_late = bounce(t - 0.05, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.knife_t = br.REST_T + V(0.005 * side, 0.004 - 0.007 * b, 0.002 * b - 0.0012 * np.sin(W_W * t))
    p.knife_q = quat_mul(br.REST_Q, quat_euler(1.3 * np.cos(W_W * (t - 0.10)), 0.5 - 1.5 * b_late,
                                               1.6 * np.sin(W_W * (t - 0.05))))
    p.fingers_r = hands.adjust(br.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    return p


# ------------------------------------------------------------------------------------ sprint
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_C, Q_RUN = br.reachable(V(0.170, -0.235, -0.270), Q((-0.12, 0.42, -0.90), (0.90, 0.40, 0.15)))
RUN_A = V(-0.012, 0.050, -0.050)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def sprint_pose(t):
    """Lowered running carry: the baton swinging with the arm, a drop on every foot strike, the left fist pumping."""
    p = br.BatonPose()
    b = periodic_bounce(t, DUR_S / 2, 0.12, 1.6)
    side = np.cos(W_S * (t - 0.04))
    lag = np.cos(W_S * t - 0.6)
    p.knife_t = RUN_C + RUN_A * pump(np.cos(W_S * t)) + V(0.003 * side, -0.011 * b, 0.002 * b)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.0 * np.sin(W_S * t), -7.0 * lag - 2.5 * b, 3.0 * side))
    p.fingers_r = hands.adjust(br.FIST, index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    p.hand_l = (L_RUN_C - L_RUN_A * pump(np.cos(W_S * t)) + V(0.0, -0.006 * b, 0.0), None, hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


# -------------------------------------------------------------------------------- tac_sprint
DUR_T = 0.58
W_T = 2 * np.pi / DUR_T
TAC_C, Q_TAC = br.reachable(V(0.135, -0.150, -0.255), Q((-0.10, 0.96, -0.25), (0.92, 0.10, 0.37)))
TAC_A = V(-0.006, 0.020, -0.016)


def tac_sprint_pose(t):
    """High carry: the baton upright beside the face, pumped short and hard, the left fist pumping big."""
    p = br.BatonPose()
    b = bounce(t, DUR_T / 2, 0.10, 1.8)
    side = np.cos(W_T * (t - 0.035))
    lag = np.cos(W_T * t - 0.6)
    p.knife_t = TAC_C + TAC_A * pump(np.cos(W_T * t)) + V(0.004 * side, 0.004 - 0.013 * b, 0.003 * b)
    p.knife_q = quat_mul(Q_TAC, quat_euler(2.0 * side, -4.0 * lag - 2.8 * b, 2.5 * np.sin(W_T * t)))
    p.fingers_r = hands.adjust(br.FIST, index=(-3.0 * b, 0))
    p.hand_l = (L_RUN_C + V(0.0, 0.012 - 0.006 * b, 0.0) - L_RUN_A * 1.1 * pump(np.cos(W_T * t)), None,
                hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "baton_equip"), (T_LOCK, "baton_open")], fps=60,
         extra_times=(0.215, T_FLICK, T_LOCK)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.06, "baton_swing")], fps=60, extra_times=(0.092, T_HIT, 0.215)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.06, "baton_swing")], fps=60, extra_times=(0.092, T_HIT, 0.215)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.22, "baton_swing_heavy")], fps=60, extra_times=(0.255, T_EXT, 0.38)),
    Clip("inspect", DUR_I, inspect_pose, events=EV_INSPECT, fps=60,
         extra_times=(1.18, T_SLAM, T_SLAM + 0.035, 2.25, T_FLICK2, T_LOCK2)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
