"""Armour plate clips (VIEWMODEL_SPEC section 50), authored with the knife tools (knife_clips: Motion splined through
key poses with flow / stop / hit keys, impact rings, the camera curve). The insert is stretched by the runtime to the
data's insertSeconds: the plate is drawn in to the chest, turned upright like the plate bag at the front and pushed
down into it — out of sight at the bottom of the view, the push stopping dead (the plate seated) with a jolt the hands
and the camera answer; it stays down until the server's word brings the next plate up (the draw)."""
import numpy as np

import armor_rig as ar
import hands
from knife_clips import CamCurve, Motion, nudge, periodic_bounce, pump, rings
from p9_clips import Clip
from vmath import quat_euler, quat_mul

V = lambda *a: np.array(a, float)
Q = ar.plate_quat


def motion(*keys):
    return Motion(*[(t, np.asarray(p, float), q, mode) for t, p, q, mode in keys])


def apply(m, p, t):
    p.plate_t = m.pos(t)
    p.plate_q = m.quat(t)


def squeeze(t, t0, depth=-6.0, width=0.06):
    k = depth * np.exp(-((t - t0) / width) ** 2)
    return hands.adjust(ar.HOLD, index=(k, k * 0.5), middle=(k, k * 0.5), ring=(k, 0), pinky=(k, 0), thumb=(k * 0.6, 0))


# ---------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = ar.PlatePose()
    w = 2 * np.pi * t / 2.0
    p.plate_t = ar.REST_T + V(0.0006 * np.sin(w + 0.5), 0.0010 * np.sin(w), 0.0005 * np.sin(w + 1.2))
    p.plate_q = quat_mul(ar.REST_Q, quat_euler(0.35 * np.sin(w + 0.4), 0.5 * np.sin(w - 0.7), 0.3 * np.sin(w + 1.1)))
    return p


# ---------------------------------------------------------------------------------------- draw
# up from the pouch at the hip: the plate comes up face-down, turned up into the hold, a little past and back
DUR_D = 0.50
DR = motion((0.00, V(0.080, -0.520, -0.300), Q((0.10, -0.90, -0.40), (0.2, 0.4, 0.9)), "stop"),
            (0.18, V(0.050, -0.330, -0.360), Q((0.12, -0.60, -0.80), (0.1, 0.8, 0.5)), "flow"),
            (0.34, ar.REST_T + V(-0.004, 0.010, -0.006), Q((0.10, -0.24, -0.96), (0.0, 1.0, -0.25)), ("flow", 0.7)),
            (0.42, ar.REST_T + V(0.001, -0.003, 0.002), ar.REST_Q, "flow"),
            (DUR_D, ar.REST_T, ar.REST_Q, "stop"))
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.18, V(0.3, 0.0, 0.3)), (0.34, V(-0.2, 0.0, -0.1)), (DUR_D, V(0, 0, 0)))


def draw_pose(t):
    p = ar.PlatePose()
    apply(DR, p, t)
    p.fingers = squeeze(t, 0.34, -4.0)
    DR_CAM(p, t)
    return p


# -------------------------------------------------------------------------------------- insert
DUR_I = 1.00
T_SEAT = 0.72
SEATED = Q((0.0, -0.04, -1.0), (0.0, 1.0, 0.04))
I = motion((0.00, ar.REST_T, ar.REST_Q, "stop"),
           (0.18, V(0.030, -0.160, -0.370), Q((0.05, -0.16, -0.99), (0.0, 1.0, 0.12)), "flow"),
           (0.45, V(0.010, -0.200, -0.320), Q((0.0, -0.06, -1.0), (0.0, 1.0, 0.05)), "flow"),
           (0.60, V(0.000, -0.300, -0.280), SEATED, "flow"),
           (T_SEAT, V(0.000, -0.420, -0.250), SEATED, {"pos": ("hit", 1.6), "rot": "flow"}),
           (0.82, V(0.000, -0.440, -0.250), SEATED, "stop"),
           (DUR_I, V(0.000, -0.460, -0.250), SEATED, "stop"))
I_JOLT = [(T_SEAT, V(0.0, 0.006, 0.002), 9.0, 0.35)]
I_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.30, V(0.4, 0.0, 0.2)), (T_SEAT, V(-0.4, 0.0, -0.2), "hit"), (0.85, V(0.1, 0, 0)),
                 (DUR_I, V(0, 0, 0)), accents=[(T_SEAT, V(-0.9, 0.0, 0.3), 8.0, 0.4)])


def insert_pose(t):
    p = ar.PlatePose()
    apply(I, p, t)
    p.plate_t = p.plate_t + rings(t, I_JOLT, DUR_I + 1.0)
    p.fingers = squeeze(t, T_SEAT, -9.0, 0.08)
    p.shoulder_r = V(0.0, -0.01, -0.02) * np.sin(np.pi * np.clip(t / DUR_I, 0, 1))
    p.shoulder_l = p.shoulder_r.copy()
    I_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ walk / sprint
DUR_W = 1.0
W_W = 2 * np.pi / DUR_W


def walk_pose(t):
    p = ar.PlatePose()
    b = periodic_bounce(t, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.plate_t = ar.REST_T + V(0.004 * side, 0.003 - 0.008 * b, 0.002 * b)
    p.plate_q = quat_mul(ar.REST_Q, quat_euler(1.2 * np.cos(W_W * (t - 0.1)), 0.5 - 1.4 * b, 1.4 * np.sin(W_W * (t - 0.05))))
    return p


DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_T = V(0.050, -0.300, -0.330)
RUN_Q = Q((0.15, -0.55, -0.82), (0.0, 1.0, -0.4))


def sprint_pose(t):
    p = ar.PlatePose()
    b = periodic_bounce(t, DUR_S / 2, 0.12, 1.6)
    side = np.cos(W_S * (t - 0.04))
    p.plate_t = RUN_T + V(0.010 * side, -0.012 * b + 0.010 * pump(np.cos(W_S * t)), 0.004 * b)
    p.plate_q = quat_mul(RUN_Q, quat_euler(3.0 * side, -3.0 * b, 3.0 * np.sin(W_S * t)))
    return p


CLIPS = [
    Clip("idle", 2.0, idle_pose, loop=True),
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "plate_draw")], fps=60, extra_times=(0.18, 0.34)),
    Clip("insert", DUR_I, insert_pose, events=[], fps=60, extra_times=(0.45, T_SEAT, 0.80)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
]
