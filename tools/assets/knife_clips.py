"""Butterfly knife clips (VIEWMODEL_SPEC section 12). Motion is authored as Hermite splines through key poses:
'flow' keys keep the velocity (slashes sweep through them), 'stop' keys are extremes (anticipation apex, end of
follow-through, stab extension). Contact keys sit exactly on the contact times."""
import numpy as np

import knife_rig as kr
from anim import EASES, Track
from p9_clips import Clip
from vmath import norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

V = lambda *a: np.array(a, float)
DEG = np.degrees


# ------------------------------------------------------------------------------------- splines
def qlog(q):
    q = q if q[3] >= 0 else -q
    s = np.linalg.norm(q[:3])
    return q[:3] * 2 if s < 1e-9 else q[:3] / s * 2 * np.arctan2(s, q[3])


def qexp(rv):
    ang = np.linalg.norm(rv)
    return np.array([0, 0, 0, 1.0]) if ang < 1e-9 else quat_axis_angle(rv / ang, np.degrees(ang))


def to_rv(q):
    return qlog(quat_mul(quat_conj(kr.REST_Q), q))


def from_rv(rv):
    return quat_mul(kr.REST_Q, qexp(rv))


class Spline:
    """Hermite spline through (t, value, mode) keys; mode 'flow' = Catmull-Rom tangent, 'stop' = zero."""

    def __init__(self, *keys):
        self.t = np.array([k[0] for k in keys], float)
        self.v = [np.asarray(k[1], float) for k in keys]
        self.m = []
        for i, k in enumerate(keys):
            mode = k[2] if len(k) > 2 else "flow"
            if mode == "stop" or len(keys) == 1:
                self.m.append(np.zeros_like(self.v[i]))
            else:
                i0, i1 = max(i - 1, 0), min(i + 1, len(keys) - 1)
                self.m.append((self.v[i1] - self.v[i0]) / (self.t[i1] - self.t[i0]))

    def __call__(self, t):
        if t <= self.t[0]:
            return self.v[0].copy()
        if t >= self.t[-1]:
            return self.v[-1].copy()
        i = int(np.searchsorted(self.t, t, side="right") - 1)
        h = self.t[i + 1] - self.t[i]
        s = (t - self.t[i]) / h
        h00, h10, h01, h11 = 2 * s ** 3 - 3 * s ** 2 + 1, s ** 3 - 2 * s ** 2 + s, -2 * s ** 3 + 3 * s ** 2, s ** 3 - s ** 2
        return h00 * self.v[i] + h10 * h * self.m[i] + h01 * self.v[i + 1] + h11 * h * self.m[i + 1]


class Motion:
    """Root position + orientation, splined through key poses. Orientation keys are (blade_dir, spine_dir)
    pairs (or quaternions); the two direction splines are re-orthonormalised every frame."""

    def __init__(self, *keys):
        dirs = []
        for t, p, o, m in keys:
            if isinstance(o, tuple):
                o = kr.knife_quat(*o)
            dirs.append((quat_rotate(o, (0, 0, -1.0)), quat_rotate(o, (0, 1.0, 0))))
        self.pos = Spline(*[(k[0], k[1], k[3]) for k in keys])
        self.blade = Spline(*[(k[0], d[0], k[3]) for k, d in zip(keys, dirs)])
        self.spine = Spline(*[(k[0], d[1], k[3]) for k, d in zip(keys, dirs)])

    def quat(self, t):
        return kr.knife_quat(norm(self.blade(t)), norm(self.spine(t)))

    def apply(self, p, t):
        p.knife_t = self.pos(t)
        p.knife_q = self.quat(t)


def knife_q_flat(blade_dir, x_hint):
    """Orientation from the blade direction and the direction the right flat (knife +X) should face."""
    z = -norm(blade_dir)
    x = norm(np.asarray(x_hint, float) - z * np.dot(x_hint, z))
    return kr.frame_quat(x, np.cross(z, x), z)


def centre_root(q, depth):
    """Knife root such that the blade (pin->tip midpoint) lies on the screen-centre ray."""
    return V(0, 0, -depth) - quat_rotate(q, kr.BLADE_MID)


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def ease(name, t, t0, t1):
    return EASES[name](float(np.clip((t - t0) / (t1 - t0), 0, 1)))


REST = (kr.REST_T, kr.REST_Q)
Q = kr.knife_quat

# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = kr.KnifePose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = kr.REST_T + V(0.0004 * np.sin(w + 0.5), 0.0006 * np.sin(w), 0.0003 * np.sin(w + 1.2))
    p.knife_q = quat_mul(kr.REST_Q, quat_euler(0.25 * np.sin(w + 0.4), 0.35 * np.sin(w + 1.0), 0.4 * np.sin(w)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand: wind-up up-right (blade cocked up), sweep through the centre, finish low-left; edge leads (spine trails)
T_HIT = 0.13
L1_C = ((-0.72, 0.30, -0.62), (0.60, 0.75, -0.25))
L1 = Motion((0.00, kr.REST_T, kr.REST_Q, "stop"),
            (0.07, V(0.170, -0.005, -0.310), ((0.35, 0.75, -0.55), (0.70, 0.55, 0.30)), "stop"),
            (T_HIT, centre_root(Q(*L1_C), 0.38), L1_C, "flow"),
            (0.20, V(-0.040, -0.120, -0.335), ((-0.55, -0.50, -0.67), (0.50, 0.80, 0.00)), "flow"),
            (0.28, V(-0.065, -0.155, -0.315), ((-0.25, -0.85, -0.46), (0.60, 0.20, 0.75)), "stop"),
            (0.42, kr.REST_T, kr.REST_Q, "stop"))
L1_CAM = Track((0.0, V(0, 0, 0)), (0.07, V(0.2, -0.2, 0.3)), (0.16, V(-0.3, 0.9, -1.5), "out2"), (0.42, V(0, 0, 0)))


def light1_pose(t):
    p = kr.KnifePose()
    L1.apply(p, t)
    cam(p, *L1_CAM(t))
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand: wind-up low-left across the body, sweep up through the centre, finish high-right; edge leads
L2_C = ((-0.55, 0.45, -0.70), (-0.65, -0.70, 0.10))
L2 = Motion((0.00, kr.REST_T, kr.REST_Q, "stop"),
            (0.07, V(-0.020, -0.130, -0.320), ((-0.60, -0.45, -0.66), (-0.70, -0.70, 0.00)), "stop"),
            (T_HIT, centre_root(Q(*L2_C), 0.38), L2_C, "flow"),
            (0.20, V(0.170, 0.005, -0.310), ((0.30, 0.78, -0.55), (-0.60, -0.20, -0.50)), "flow"),
            (0.28, V(0.195, 0.025, -0.292), ((0.42, 0.72, -0.55), (-0.70, 0.10, -0.50)), "stop"),
            (0.42, kr.REST_T, kr.REST_Q, "stop"))
L2_CAM = Track((0.0, V(0, 0, 0)), (0.07, V(-0.2, 0.2, -0.3)), (0.16, V(0.4, -0.9, 1.5), "out2"), (0.42, V(0, 0, 0)))


def light2_pose(t):
    p = kr.KnifePose()
    L2.apply(p, t)
    cam(p, *L2_CAM(t))
    return p


# ------------------------------------------------------------------------------------ heavy
# wind-up (pull back to the shoulder), committed thrust to full extension at 0.40, small recoil, recover
T_EXT = 0.40
HV_P = ((-0.30, 0.45, -0.84), (0.10, 0.90, 0.40))
HV_E = ((-0.40, 0.42, -0.81), (0.10, 0.90, 0.40))
HV = Motion((0.00, kr.REST_T, kr.REST_Q, "stop"),
            (0.18, V(0.165, -0.075, -0.245), HV_P, "flow"),
            (0.26, V(0.175, -0.072, -0.232), HV_P, "stop"),
            (T_EXT, V(0.075, -0.070, -0.455), HV_E, "stop"),
            (0.48, V(0.083, -0.074, -0.442), HV_E, "flow"),
            (0.70, V(0.110, -0.095, -0.355), (kr.REST_BLADE, kr.REST_SPINE), "flow"),
            (0.95, kr.REST_T, kr.REST_Q, "stop"))
HV_SHOULDER = Spline((0.00, V(0, 0, 0), "stop"), (0.26, V(0.005, -0.005, 0.015), "stop"),
                     (T_EXT, V(-0.020, 0.005, -0.030), "stop"), (0.95, V(0, 0, 0), "stop"))
HV_CAM = Track((0.0, V(0, 0, 0)), (0.26, V(0.45, 0.1, 0.3)), (0.43, V(-1.2, -0.2, -0.6), "out2"),
               (0.95, V(0, 0, 0)))


def heavy_pose(t):
    p = kr.KnifePose()
    HV.apply(p, t)
    p.shoulder_r = HV_SHOULDER(t)
    cam(p, *HV_CAM(t))
    return p


# ------------------------------------------------------------------------------------- draw
# closed knife rises from the bottom right, left hand flicks the bite handle out, blade swings over the top
DR = Motion((0.00, V(0.190, -0.330, -0.270), ((-0.10, -0.30, -0.95), (0.90, 0.30, 0.00)), "stop"),
            (0.20, V(0.135, -0.100, -0.320), ((-0.25, 0.50, -0.83), (0.75, 0.60, 0.20)), "flow"),
            (0.34, V(0.130, -0.092, -0.325), ((-0.27, 0.55, -0.79), (0.55, 0.75, 0.35)), "flow"),
            (0.50, V(0.117, -0.082, -0.338), ((-0.32, 0.64, -0.70), (0.00, 0.70, 0.70)), "flow"),
            (0.62, V(0.121, -0.090, -0.341), ((-0.295, 0.585, -0.755), (0.13, 0.70, 0.70)), "flow"),
            (0.80, kr.REST_T, kr.REST_Q, "stop"))
DR_A = Track((0.0, 180.0), (0.32, 180.0), (0.50, 0.0, "in2"), (0.535, -7.0, "out2"), (0.58, 0.0))
DR_B = Track((0.0, 180.0), (0.17, 180.0), (0.32, -8.0, "inout"), (0.36, 0.0))
DR_C = Track((0.0, kr.LATCH_OPEN), (0.54, kr.LATCH_OPEN), (0.62, 0.0, "in2"))
DR_LW = Track((0.0, 0.0), (0.06, 0.0), (0.17, 1.0), (0.24, 1.0), (0.40, 0.0))
DR_CAM = Track((0.0, V(0, 0, 0)), (0.20, V(0.3, 0.0, 0.4)), (0.50, V(-0.5, 0.0, -0.3)), (0.80, V(0, 0, 0)))


def draw_pose(t):
    p = kr.KnifePose()
    DR.apply(p, t)
    p.a, p.b, p.c = float(DR_A(t)), float(DR_B(t)), float(DR_C(t))
    w = float(DR_LW(t))
    butt = p.point("handle_bite", (0.0, 0.0, 0.118))
    rest_l = kr.LEFT_REST
    p.hand_l = ((1 - w) * rest_l[0] + w * butt, norm((1 - w) * rest_l[1] + w * norm((-1.0, 0.3, -0.3))))
    p.left_scale = 1.0 if t < 0.44 else 0.0
    cam(p, *DR_CAM(t))
    return p


# ----------------------------------------------------------------------------------- inspect
P_T = V(0.085, -0.070, -0.310)
Q_T = knife_q_flat((-0.40, 0.82, -0.40), (-0.25, 0.25, 0.93))
Q_SHOW = knife_q_flat((-0.85, 0.30, -0.43), (-0.10, 0.30, 0.95))
P_SHOW = V(0.080, -0.060, -0.300)
INS = Motion((0.00, kr.REST_T, kr.REST_Q, "stop"),
             (0.25, P_T, Q_T, "stop"),
             (1.62, P_T, Q_T, "stop"),
             (2.44, P_T, Q_T, "stop"),
             (2.62, P_SHOW, Q_SHOW, "stop"),
             (3.10, P_SHOW + V(-0.004, 0.003, 0.0), Q_SHOW, "stop"),
             (3.55, kr.REST_T, kr.REST_Q, "stop"))
T_TOSS, T_CATCH = 1.66, 2.40
INS_CAM = Track((0.0, V(0, 0, 0)), (0.30, V(0.4, 0.0, 1.2)), (1.00, V(0.3, 0.0, 1.0)), (1.30, V(0.0, -0.3, -1.0)),
                (2.00, V(0.8, 0.0, 0.8)), (2.80, V(-0.2, 0.4, 1.4)), (3.60, V(0, 0, 0)))


def inspect_pose(t):
    p = kr.KnifePose()
    INS.apply(p, t)
    # unlatch / relatch
    p.c = kr.LATCH_OPEN * (ease("inout", t, 0.08, 0.20) - ease("in2", t, 3.22, 3.36))
    # two fan flips around the safe pin, then a rollover (barrel roll) with one more fan
    fan = 720.0 * ease("inout", t, 0.28, 0.98)
    roll = 360.0 * ease("inout", t, 1.08, 1.55)
    p.a = fan + 360.0 * ease("inout", t, 1.08, 1.55)
    if roll > 0:
        p.knife_q = quat_mul(p.knife_q, quat_axis_angle((0, 0, 1), roll))
    # aerial: toss, one end-over-end turn about the flat normal, catch
    if T_TOSS - 0.02 < t < T_CATCH + 0.04:
        s = float(np.clip((t - T_TOSS) / (T_CATCH - T_TOSS), 0, 1))
        p.knife_t = P_T + V(-0.03, 0.0, -0.02) * np.sin(np.pi * s) + V(0, 0.145, 0) * 4 * s * (1 - s)
        p.knife_q = quat_mul(p.knife_q, quat_axis_angle((1, 0, 0), 360.0 * ease("sine", t, T_TOSS, T_CATCH)))
        p.a = 18.0 * np.sin(2 * np.pi * s)
        p.free_w = min(ease("out2", t, T_TOSS - 0.02, T_TOSS + 0.02), 1 - ease("in2", t, T_CATCH - 0.04, T_CATCH))
        dip = V(0.0, -0.035, 0.010) * np.sin(np.pi * s)
        p.free_r = (P_T + dip - 0.03 * norm(kr.SHOULDER_R - P_T), quat_rotate(Q_T, (1, 0, 0)))
    # left hand presents the blade
    w = ease("inout", t, 2.46, 2.66) * (1 - ease("inout", t, 3.00, 3.24))
    mid = p.knife_point(kr.BLADE_MID)
    p.hand_l = ((1 - w) * kr.LEFT_REST[0] + w * (mid + V(0.0, -0.040, 0.012)),
                norm((1 - w) * kr.LEFT_REST[1] + w * norm((-0.8, -0.2, 0.5))))
    p.left_scale = 1.0 if 2.42 <= t < 3.30 else 0.0
    cam(p, *INS_CAM(t))
    return p


# ------------------------------------------------------------------------------------ sprint
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
Q_RUN = Q((-0.22, 0.25, -0.94), (0.05, 1.0, 0.25))
RUN_C = V(0.135, -0.150, -0.290)
RUN_A = V(-0.012, 0.055, -0.050)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def step_bounce(t):
    ph = (t % (DUR_S / 2)) / (DUR_S / 2)
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - 0.12))) ** 1.6


def sprint_pose(t):
    p = kr.KnifePose()
    b = step_bounce(t)
    side = np.cos(W_S * (t - 0.04))
    p.knife_t = RUN_C + RUN_A * np.cos(W_S * t) + V(0.003 * side, -0.008 * b, 0.0)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.0 * np.sin(W_S * t), -6.0 * np.cos(W_S * t) - 2.0 * b, 3.0 * side))
    p.hand_l = (L_RUN_C - L_RUN_A * np.cos(W_S * t), norm((-1.0, 0.1, -0.15)))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", 0.80, draw_pose, events=[(0.0, "equip"), (0.17, "flip"), (0.50, "open")], fps=60,
         extra_times=(0.17, 0.32, 0.44, 0.50)),
    Clip("light_1", 0.42, light1_pose, events=[(0.08, "swing")], fps=60, extra_times=(0.07, T_HIT, 0.20, 0.28)),
    Clip("light_2", 0.42, light2_pose, events=[(0.08, "swing")], fps=60, extra_times=(0.07, T_HIT, 0.20, 0.28)),
    Clip("heavy", 0.95, heavy_pose, events=[(0.30, "swing_heavy")], fps=60, extra_times=(0.26, T_EXT, 0.48)),
    Clip("inspect", 3.60, inspect_pose,
         events=[(0.28, "flip"), (0.62, "flip"), (0.98, "open"), (1.08, "flip"), (1.55, "open"),
                 (T_TOSS, "flip"), (T_CATCH, "catch")], fps=60,
         extra_times=(T_TOSS, T_CATCH, 2.42, 3.30)),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
]
