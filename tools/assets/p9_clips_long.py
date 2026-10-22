"""P9 reload, reload_empty and inspect clips."""
import numpy as np

import p9_model as pm
import p9_rig as rig
from anim import Track
from p9_clips import V, Clip, Weights, apply_gun, cam
from vmath import norm, quat_euler, quat_mul

AX = pm.AXIS                              # down the mag well, gun space
MAG_REST = rig.NODES["mag"][1]

# ------------------------------------------------------------------------------ shared magazine swap
T_RELEASE = 0.27                          # thumb hits the release
T_DROP = 0.29                             # magazine starts to fall
T_HIDE = 0.60                             # dropped magazine is far below the screen
T_SEAT = 1.21                             # new magazine clicks home (mag_in)

NEW_OFF = Track((T_HIDE, AX * 0.32 + V(-0.070, 0.0, 0.050)),
                (0.95, AX * 0.075 + V(-0.012, 0.0, 0.004), "out"),
                (1.04, AX * 0.040, "inout"),
                (1.12, AX * 0.006, "in2"),
                (1.165, AX * 0.007),
                (T_SEAT, AX * -0.0025, "in2"),
                (1.27, V(0, 0, 0), "out2"))
NEW_ROT = Track((T_HIDE, V(10, -25, 30)), (0.95, V(2, -8, 10), "out"), (1.04, V(0, -1, 0), "inout"),
                (1.12, V(0, 0, 0)))


def mag_swap(p, t):
    """Old magazine drops out, new one arrives from below in the left hand and is slapped home."""
    if t < T_DROP:
        return
    if t < T_HIDE:
        dt = t - T_DROP
        d = 0.05 * dt + 0.5 * 8.5 * dt * dt
        late = max(0.0, t - 0.40)
        p.mag_t = MAG_REST + AX * d + V(-0.06 * late, -0.2 * late * late, 0.02 * late)
        p.mag_q = quat_mul(rig.MAG_Q, quat_euler(15 * late, -70 * late, 35 * late))
        return
    if t < 0.66:
        p.mag_s = 0.0                     # hidden while the hand fetches the new one (off screen)
    p.mag_t = MAG_REST + NEW_OFF(t)
    p.mag_q = quat_mul(rig.MAG_Q, quat_euler(*NEW_ROT(t)))


POUCH = V(-0.065, -0.460, -0.150)                               # off-screen spot where the new mag is grabbed

L_RELOAD = Weights((0.00, {"support": 1}), (0.06, {"support": 1}), (0.24, {"magrel": 1}), (0.31, {"magrel": 1}),
                   (0.52, {"world": 1}), (0.56, {"world": 1}), (0.70, {"mag": 1}), (1.125, {"mag": 1}),
                   (1.17, {"slap": 1}), (1.25, {"slap": 1}))
L_OFF_RELOAD = Track((0.00, V(0, 0, 0)), (0.22, V(0, 0, 0)), (0.27, V(0.005, 0, 0), "out2"), (0.31, V(0, 0, 0)),
                     (1.125, V(0, 0, 0)), (1.165, V(-0.004, -0.026, 0.006), "out2"), (T_SEAT, V(0, 0.002, 0), "in2"),
                     (1.26, V(0, 0, 0)))


def left_hand(p, weights, gun_offset=(0, 0, 0), world=None):
    if world is None:                                 # off-screen pouch, hand oriented like on the magazine
        world = (POUCH, p.gun_dir(rig.MAG_HAND_BACK))
    p.hand_l = p.blend_hand(weights, p.gun_dir(np.asarray(gun_offset, float)), world)


# --------------------------------------------------------------------------------------- reload
R_OFF = Track((0.00, V(0, 0, 0)), (0.26, V(-0.010, 0.012, 0.004), "out"), (0.70, V(-0.012, 0.014, 0.004), "sine"),
              (1.14, V(-0.012, 0.014, 0.004), "sine"), (T_SEAT + 0.01, V(-0.010, 0.002, 0.006), "out2"),
              (1.34, V(-0.011, 0.012, 0.004), "inout"), (1.80, V(0.0008, -0.0012, 0.0005), "inout"),
              (1.90, V(0, 0, 0), "inout"))
R_ROT = Track((0.00, V(0, 0, 0)), (0.26, V(8, 12, -26), "out"), (0.60, V(9, 13, -27), "sine"),
              (1.10, V(8, 11, -25), "sine"), (T_SEAT + 0.01, V(8, 7.5, -24), "out2"),
              (1.34, V(8, 12, -25), "inout"), (1.80, V(-0.4, -0.6, 0.6), "inout"), (1.90, V(0, 0, 0), "inout"))
R_CAM = Track((0.00, V(0, 0, 0)), (0.30, V(0, 0, 0)), (0.42, V(-0.6, 0, -1.0)), (0.80, V(0, 0, 0)),
              (T_SEAT - 0.01, V(0, 0, 0)), (T_SEAT + 0.025, V(-0.9, 0.1, 0.5), "out2"), (1.50, V(0, 0, 0)),
              (1.90, V(0, 0, 0)))
L_RELOAD_END = Weights((1.25, {"slap": 1}), (1.55, {"support": 1}))


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, R_OFF(t), R_ROT(t))
    mag_swap(p, t)
    w = L_RELOAD(t) if t < 1.25 else L_RELOAD_END(t)
    left_hand(p, w, L_OFF_RELOAD(t))
    cam(p, *R_CAM(t))
    return p


MAG_EVENTS = [(T_RELEASE, "mag_release"), (0.33, "mag_out"), (T_SEAT, "mag_in")]
RELOAD = Clip("reload", 1.90, reload_pose, events=MAG_EVENTS, extra_times=(T_SEAT, 1.165, 0.27, T_HIDE, 0.66),
              force_nodes=("mag",))

# --------------------------------------------------------------------------------- reload_empty
# Slide starts locked back; after the magazine is slapped home the support thumb drops the slide stop.
T_REACH, T_PRESS, T_HOME = 1.47, 1.55, 1.575
E_OFF = Track((0.00, V(0, 0, 0)), (0.26, V(-0.010, 0.012, 0.004), "out"), (0.70, V(-0.012, 0.014, 0.004), "sine"),
              (1.14, V(-0.012, 0.014, 0.004), "sine"), (T_SEAT + 0.01, V(-0.010, 0.002, 0.006), "out2"),
              (1.30, V(-0.014, 0.012, 0.004), "inout"), (T_REACH, V(-0.020, 0.016, 0.002), "inout"),
              (T_PRESS, V(-0.020, 0.015, 0.002)), (T_HOME + 0.012, V(-0.019, 0.019, 0.007), "out2"),
              (1.72, V(-0.018, 0.014, 0.003), "inout"), (2.20, V(0.0008, -0.001, 0.0005), "inout"),
              (2.30, V(0, 0, 0), "inout"))
E_ROT = Track((0.00, V(0, 0, 0)), (0.26, V(8, 12, -26), "out"), (0.60, V(9, 13, -27), "sine"),
              (1.10, V(8, 11, -25), "sine"), (T_SEAT + 0.01, V(8, 7.5, -24), "out2"),
              (1.30, V(9, 9, -10), "inout"), (T_REACH, V(12, 7, 14), "inout"), (T_PRESS, V(12, 6.5, 15)),
              (T_HOME + 0.012, V(12.5, 9.5, 14), "out2"), (1.72, V(11, 6, 13), "inout"),
              (2.20, V(-0.4, -0.6, 0.5), "inout"), (2.30, V(0, 0, 0), "inout"))
E_SLIDE = Track((0.0, rig.SLIDE_LOCK), (T_PRESS + 0.004, rig.SLIDE_LOCK), (T_HOME, 0.0, "in2"),
                (T_HOME + 0.010, 0.0010, "out2"), (T_HOME + 0.025, 0.0))
E_STOP = Track((0.0, rig.STOP_UP), (T_PRESS - 0.03, rig.STOP_UP), (T_PRESS, -1.0, "in2"), (T_PRESS + 0.06, 0.0))
E_HAMMER = Track((0.0, 46.0), (T_PRESS + 0.012, 46.0), (T_HOME + 0.004, rig.HAMMER_COCKED, "in2"))
E_CAM = Track((0.00, V(0, 0, 0)), (0.30, V(0, 0, 0)), (0.42, V(-0.6, 0, -1.0)), (0.80, V(0, 0, 0)),
              (T_SEAT - 0.01, V(0, 0, 0)), (T_SEAT + 0.025, V(-0.9, 0.1, 0.5), "out2"), (1.45, V(0, 0, 0)),
              (T_HOME - 0.005, V(0, 0, 0)), (T_HOME + 0.02, V(0.7, 0, -0.6), "out2"), (1.90, V(0, 0, 0)),
              (2.30, V(0, 0, 0)))
L_EMPTY_END = Weights((1.25, {"slap": 1}), (T_REACH, {"stop": 1}), (T_PRESS + 0.05, {"stop": 1}),
                      (2.05, {"support": 1}))
L_OFF_EMPTY = Track((1.25, V(0, 0, 0)), (1.36, V(-0.020, 0.004, 0.006)), (T_REACH, V(0, 0, 0)),
                    (T_PRESS - 0.03, V(-0.002, 0.004, 0.0)), (T_PRESS, V(0.002, -0.005, 0.0), "in2"),
                    (T_PRESS + 0.10, V(-0.006, -0.004, 0.004), "out2"), (2.05, V(0, 0, 0)))


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    p.slide_z = float(E_SLIDE(t))
    p.stop = float(E_STOP(t))
    p.hammer = float(E_HAMMER(t))
    mag_swap(p, t)
    if t < 1.25:
        left_hand(p, L_RELOAD(t), L_OFF_RELOAD(t))
    else:
        left_hand(p, L_EMPTY_END(t), L_OFF_EMPTY(t))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.30, reload_empty_pose,
                    events=MAG_EVENTS + [(T_PRESS, "slide_release"), (T_HOME, "slide_forward")],
                    extra_times=(T_SEAT, 1.165, T_HIDE, 0.66, T_PRESS, T_PRESS + 0.004, T_HOME, T_HOME + 0.01),
                    force_nodes=("mag", "slide", "slide_stop", "hammer"))

# -------------------------------------------------------------------------------------- inspect
I_OFF = Track((0.00, V(0, 0, 0)), (0.62, V(-0.050, 0.035, 0.020), "inout"), (1.30, V(-0.054, 0.037, 0.018), "sine"),
              (1.85, V(-0.030, 0.030, 0.010), "inout"), (2.40, V(-0.028, 0.028, 0.012), "sine"),
              (2.75, V(-0.036, -0.004, 0.058), "inout"), (3.10, V(-0.036, -0.003, 0.058), "sine"),
              (3.52, V(0.0006, -0.0008, 0.0004), "inout"), (3.60, V(0, 0, 0), "inout"))
I_ROT = Track((0.00, V(0, 0, 0)), (0.62, V(55, 8, 12), "inout"), (1.30, V(60, 10, 16), "sine"),
              (1.85, V(10, 33, 84), "inout"), (2.40, V(8, 36, 88), "sine"),
              (2.75, V(18, 6, 26), "inout"), (3.10, V(19, 7, 27), "sine"),
              (3.52, V(0.5, -0.5, -0.6), "inout"), (3.60, V(0, 0, 0), "inout"))
I_SLIDE = Track((0.0, 0.0), (2.76, 0.0), (2.92, 0.012, "out"), (3.07, 0.012), (3.115, 0.0, "in2"),
                (3.125, 0.0006, "out2"), (3.14, 0.0))
I_CAM = Track((0.00, V(0, 0, 0)), (0.62, V(0.4, 0, 1.4)), (1.30, V(0.3, 0, 1.2), "sine"),
              (1.85, V(-0.3, -0.2, 1.6)), (2.40, V(-0.2, -0.1, 1.5), "sine"), (2.75, V(0, 0, 0.6)),
              (3.10, V(0, 0, 0.5), "sine"), (3.60, V(0, 0, 0)))
L_INSPECT = Weights((0.00, {"support": 1}), (0.08, {"support": 1}), (0.45, {"world": 1}), (2.40, {"world": 1}),
                    (2.72, {"press": 1}), (3.12, {"press": 1}), (3.50, {"support": 1}))
L_OFF_INSPECT = Track((0.0, V(0, 0, 0)), (2.40, V(0, 0, 0)), (2.58, V(-0.030, -0.015, 0.0)), (2.72, V(0, 0, 0)),
                      (3.12, V(0, 0, 0)), (3.30, V(-0.015, -0.020, 0.0)), (3.50, V(0, 0, 0)))
INSPECT_REST = (V(-0.070, -0.450, -0.140), norm((-1.0, 0.1, -0.2)))


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.slide_z = float(I_SLIDE(t))
    left_hand(p, L_INSPECT(t), L_OFF_INSPECT(t), world=INSPECT_REST)
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 3.60, inspect_pose,
               events=[(0.02, "inspect_move"), (1.30, "inspect_move"), (2.40, "inspect_move"),
                       (2.78, "slide_back"), (3.10, "slide_forward")],
               extra_times=(2.92, 3.07, 3.115, 3.125))
