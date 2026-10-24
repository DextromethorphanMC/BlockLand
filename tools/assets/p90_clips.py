"""P90 clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> p90_rig.Pose; gen_p90 bakes them (IK included) at >= 60
samples/s (fire at 120).

Authored to section 31 like the AK's (ak_clips.py: Curve bases, rings for every push, drift in holds). The P90 is a
light, compact 2.6 kg bullpup: moves are quicker and smaller than the AK's, the 5.7 mm shot barely stirs the hands, and
the closed bolt runs inside the butt with non-reciprocating cocking handles, so the fire clip is the trigger break and
the hands' reaction only. The reload is the P90's own: the gun rolls to show its top, the support hand squeezes the
catches at the rear of the magazine, lifts the magazine's rear and draws it back out from under the sight housing,
carries it away, brings the new one in front first under the housing, slides it home into the front latch and presses
the rear down until it clicks. The empty reload adds a pull of the left cocking handle."""
import numpy as np

import ads_fire
import hands
import p90_model as pm
import p90_rig as rig
from ak_clips import Layers, blend_curls, fade_end, foot, FOOT_WALK, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_euler, quat_mul, quat_rotate

TRAVEL = pm.BOLT_TRAVEL
MAG_REST = rig.NODES["mag"][1]
LIP = rig.MAG_LIP
UNDER_SLOPE = 0.13          # the housing's underside rises 13 mm per 100 mm towards the rear: the magazine follows it


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def mag_rock(theta, offset=(0, 0, 0), extra=(0, 0, 0)):
    """Magazine turned `theta` deg about its front hook (negative lifts the rear), then offset (gun space)."""
    q = quat_mul(quat_axis_angle((1, 0, 0), theta), quat_euler(*extra))
    return MAG_REST + LIP - quat_rotate(q, LIP) + np.asarray(offset, float), q


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.22 * np.sin(w + 0.9), 0.24 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["p90_fore"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.1 s, whipped up muzzle first (light gun: quick), overshoot at 0.30 / 0.36, the support
# hand slaps the forend at 0.40, settled by 0.62
T_CATCH = 0.40
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.260, 0.085)), (0.06, V(0.053, -0.272, 0.090)),
                        (0.36, V(-0.006, 0.009, -0.006)), (0.48, V(0.0012, -0.0022, 0.0010)), (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.0, -0.0015, 0.0006), 8.5, 0.4)], end=0.62)
DRAW_ROT = Layers(Curve((0.00, V(-15, -46, -30)), (0.06, V(-16, -49, -32)), (0.28, V(2.4, 4.6, 3.4)),
                        (0.42, V(-0.6, -1.1, -0.8)), (0.54, V(0.15, 0.2, 0.1)), (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.2, -0.9, 0.6), 8.5, 0.4)], end=0.62)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.25, 0, 0.15)), (0.30, V(-0.8, 0.1, -0.6)),
                        (0.44, V(0.3, 0, 0.25)), (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(-0.3, 0.0, 0.25), 7.5, 0.4)], end=0.62)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.26, V(-0.010, -0.034, 0.024)), (T_CATCH, V(0, 0.003, 0)),
                (0.47, V(0, -0.0008, 0)), (0.54, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.30, {"spread": 1.0}), (0.41, {"rpd_clamp": 1.0}),
                  (0.52, {"p90_fore": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.26, {"grip": 1.0}), (0.44, {"p90_grip": 1.0}))
hands.POSES.setdefault("rpd_clamp", {"thumb": (-20, -12), "index": (-60, -56), "middle": (-66, -62),
                                     "ring": (-70, -66), "pinky": (-74, -68)})


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.62, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
# 900 rpm, closed bolt inside the butt: the trigger breaks (hit at 0.006), the hands give a few millimetres (the
# 5.7 mm round is mild), the support fingers clench a frame later; the trigger resets before the next shot. The
# cocking handles do not reciprocate. Ends exactly at rest so automatic fire chains.
F_END = 0.066
F_TRIG = Curve((0.0, -6.0), (0.006, rig.TRIGGER_PULL, ("hit", 1.6)), (0.036, rig.TRIGGER_PULL), (0.060, -6.0),
               (F_END, -6.0))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.022)
    off = ring(t, 0.001, V(0.0003, 0.0011, 0.0038), 13.0, 0.5)
    rot = ring(t, 0.001, V(0.2, 1.1, -0.35), 12.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_TRIG.times() + [0.010, 0.018, 0.028]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.trigger = float(F_TRIG(t))
    k = float(F_TRIG(t)) / rig.TRIGGER_PULL
    p.fingers_r = blend_curls(hands.POSES["p90_grip"], hands.POSES["p90_trigger"], k)
    clamp = -4.0 * float(ring(t, 0.010, 1.0, 9.0, 0.6)) * fade_end(t, F_END, 0.022)
    p.fingers_l = hands.adjust(hands.POSES["p90_fore"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, 0.002, V(0.22, 0.03, 0.06), 10.0, 0.5) * fade_end(t, F_END, 0.026)))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)   # closed bolt, no hold-open
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# beats (both reloads): let go 0.06 -> the gun dips and rolls right (anticipation) -> rolls left, top towards the eye
# (overshoot 0.34) -> the hand over the magazine's rear 0.34, squeezes the catches (mag_release 0.40) -> lifts the
# rear (0.40 .. 0.52) and draws it back out of the front latch (mag_out 0.66), following the housing's underside ->
# carries it up off the receiver and away down-left, off screen by 1.02 -> the new one comes up (1.16), its front
# lined up under the housing (slow, 1.42 .. 1.56) -> slid home into the front latch (1.72) -> the rear pressed down
# with the heel of the hand: hit at 1.92 (mag_in), the gun and the camera jolt.
T_LETGO, T_GRAB, T_RELEASE, T_LIFT, T_OUT = 0.06, 0.34, 0.40, 0.52, 0.66
T_CLEAR, T_HIDE, T_SHOW = 0.82, 1.02, 1.16
T_ALIGN, T_HOOK, T_LATCH, T_PRESS, T_SEAT = 1.42, 1.56, 1.72, 1.80, 1.92
AWAY = V(-0.170, -0.200, 0.020)                  # off-screen spot (gun space) the hand takes the magazine to / from

MAG_THETA = Curve((T_RELEASE, 0.0), (0.44, -0.6, "stop"), (T_LIFT, -6.0), (T_OUT, -7.5), (T_CLEAR, -12.0),
                  (T_HIDE, -18.0), (T_SHOW, -18.0), (1.30, -15.0), (T_ALIGN, -12.5), (T_HOOK, -11.5),
                  (T_LATCH, -9.0, "stop"), (T_PRESS, -8.6, "stop"), (T_SEAT, 0.6, ("hit", 2.2)),
                  (T_SEAT + 0.035, -0.25, "stop"), (T_SEAT + 0.07, 0.0, "stop"))
MAG_BACK = Curve((T_LIFT, 0.0), (T_OUT, 0.030), (T_CLEAR, 0.080), (T_HIDE, 0.100), (T_SHOW, 0.100), (1.30, 0.085),
                 (T_ALIGN, 0.052), (T_HOOK, 0.040, "stop"), (T_LATCH, 0.0, ("hit", 1.2)), (T_SEAT, 0.0))
MAG_AWAY = Curve((T_OUT, 0.0), (T_CLEAR, 0.12), (T_HIDE, 1.0), (T_SHOW, 1.0), (1.30, 0.22), (T_ALIGN, 0.0))


def mag_path(t):
    """Gun-space magazine (t, q) from the release to the seat: rocked about its hook, drawn back along the housing's
    underside, swung away to AWAY and back."""
    back = float(MAG_BACK(t)) if t > T_LIFT else 0.0
    up = UNDER_SLOPE * min(back, 0.10) + 0.0015 * (back > 0.0005)
    off = V(0.0, up, back)
    a = float(MAG_AWAY(t)) if T_OUT < t < T_ALIGN else 0.0
    off = off * (1 - a) + a * (AWAY + V(0.0, 0.0, 0.0)) + arc(a, V(-0.04, 0.05, 0.0)) * (a < 1.0)
    roll = 25.0 * a
    return mag_rock(float(MAG_THETA(t)), off, (0.0, 0.0, roll))


def mag_swap(p, t):
    if t < T_RELEASE:
        return
    p.mag_t, p.mag_q = mag_path(t)
    if T_HIDE <= t < T_SHOW:
        p.mag_s = 0.0


L_REACH = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (T_GRAB, {"mag_side": 1}),
                  (T_LATCH + 0.02, {"mag_side": 1}), (T_PRESS + 0.04, {"mag_side": 1}),
                  (T_SEAT + 0.03, {"mag_side": 1}))
L_REACH_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.20, V(-0.026, 0.006, 0.018), "flow"),
                    (T_GRAB, V(0, 0, 0)), (T_LATCH + 0.02, V(0, 0, 0)), (1.77, V(0.0, 0.012, 0.004)),
                    (T_PRESS + 0.04, V(0.0, 0.004, 0.0)), (T_SEAT, V(0.0, -0.0015, 0.0), "hit"),
                    (T_SEAT + 0.03, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.14, 1.0), (0.26, 0.8), (T_GRAB, 0.0), (T_LATCH, 0.0), (1.77, 0.6),
               (T_PRESS + 0.04, 0.0))
SQUEEZE = Curve((0.0, 0.0), (T_GRAB, 0.0), (T_RELEASE, -16.0, ("hit", 1.6)), (0.46, -8.0), (T_OUT, -4.0),
                (T_CLEAR, 0.0))


def mag_hands(p, t):
    """Support hand from the start of the reload to just after the seat."""
    left_hand(p, L_REACH, t, L_REACH_OFF(t))
    pos, q, curls = p.hand_l
    sq = float(SQUEEZE(t))
    curls = hands.adjust(open_fingers(curls, float(L_OPEN(t))), thumb=(sq, 0.0), index=(0.6 * sq, 0.0),
                         middle=(0.8 * sq, 0.0))
    p.hand_l = (pos, q, curls)
    w = smoothstep(T_LETGO, T_GRAB, t)
    a = float(MAG_AWAY(t)) if T_OUT < t < T_ALIGN else 0.0
    p.shoulder_l = w * V(0.030, 0.040, 0.000) + a * V(-0.040, -0.080, 0.040)


# ---------------------------------------------------------------------------------------- reload
# after the seat the hand comes off the magazine (2.00), swings down to the forend (2.22) and clamps; the gun rolls
# back to ready past the rest pose and settles by 2.7
T_BACK = 2.22
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0020, -0.0040, 0.0015)), (0.34, V(-0.0120, 0.0180, -0.0400)),
                     (0.52, V(-0.0100, 0.0160, -0.0420)), (0.82, V(-0.0130, 0.0140, -0.0400)), (1.16, V(-0.0120, 0.0100, -0.0380)),
                     (T_ALIGN, V(-0.0110, 0.0150, -0.0400)), (T_LATCH, V(-0.0100, 0.0170, -0.0410)),
                     (T_SEAT, V(-0.0100, 0.0120, -0.0400)), (2.05, V(-0.0060, 0.0140, -0.0420)),
                     (2.36, V(0.0026, -0.0036, 0.0010)), (2.54, V(-0.0006, 0.0008, -0.0002)), (2.70, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0010, 0.0), 10.0, 0.4), (T_OUT, V(0.0, 0.0016, 0.0022), 7.0, 0.4),
                      (T_LATCH, V(0.0, 0.0, -0.0016), 9.0, 0.4), (T_SEAT, V(0.0, -0.0042, 0.0006), 7.5, 0.35),
                      (T_BACK, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 0.80, V(0.0008, 0.0007, 0.0006), 51, 0.7),
                       (1.10, 1.70, V(0.0009, 0.0008, 0.0006), 52, 0.6)], end=2.70)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.6, -1.0, -2.4)), (0.34, V(6.4, 12.0, 10.5)),
                     (0.52, V(6.0, 11.4, 9.8)), (0.82, V(5.3, 11.8, 9.0)), (1.16, V(5.8, 12.2, 9.5)),
                     (T_ALIGN, V(6.3, 11.6, 10.2)), (T_LATCH, V(6.0, 11.2, 9.9)), (T_SEAT, V(4.8, 11.0, 9.3)),
                     (2.05, V(5.4, 11.4, 9.8)), (2.38, V(-0.6, -0.9, -2.6)), (2.56, V(0.2, 0.3, 0.6)),
                     (2.70, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.6, -0.5), 10.0, 0.4), (T_OUT, V(0.8, -0.6, 0.4), 7.0, 0.4),
                      (T_LATCH, V(-0.4, 0.0, 0.0), 9.0, 0.4), (T_SEAT, V(-2.2, 0.5, -1.6), 7.5, 0.35),
                      (T_BACK, V(0.0, -0.5, 0.4), 8.0, 0.4)],
               drifts=[(0.52, 0.80, V(0.4, 0.4, 0.6), 53, 0.7), (1.10, 1.70, V(0.5, 0.5, 0.7), 54, 0.6)], end=2.70)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, -0.4)), (0.40, V(-0.7, 0.2, 1.5)),
                     (0.80, V(-0.4, 0.1, 1.0)), (1.30, V(-0.5, 0.2, 1.2)), (1.84, V(-0.4, 0.1, 1.1)),
                     (2.10, V(-0.5, 0.1, 1.3)), (2.42, V(0.3, -0.1, -0.6)), (2.70, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.2, 0.0, -0.3), 9.0, 0.4), (T_SEAT, V(-1.1, 0.15, -0.8), 6.5, 0.35)], end=2.70)
L_HOME = Weights((T_SEAT + 0.03, {"mag_side": 1}), (T_BACK, {"support": 1}))
L_HOME_OFF = Curve((T_SEAT + 0.03, V(0, 0, 0)), (2.02, V(0.0, 0.016, 0.006)), (2.12, V(-0.018, -0.026, 0.016), "flow"),
                   (T_BACK, V(0, 0, 0)))
L_HOME_OPEN = Curve((T_SEAT + 0.03, 0.4), (2.02, 1.0), (2.16, 0.8), (T_BACK, 0.0))
L_REGRIP = Curve((2.18, 0.0), (2.26, 1.0), (2.40, 0.0))


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, R_OFF(t), R_ROT(t))
    mag_swap(p, t)
    if t < T_SEAT + 0.03:
        mag_hands(p, t)
    else:
        left_hand(p, L_HOME, t, L_HOME_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(L_HOME_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["rpd_clamp"], 0.8 * float(L_REGRIP(t)))
        p.hand_l = (pos, q, curls)
        p.shoulder_l = V(0.030, 0.040, 0.000) * (1 - smoothstep(T_SEAT, T_BACK, t))
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.2, 2.5)), 0))
    cam(p, *R_CAM(t))
    return p


R_EVENTS = [(0.03, "inspect_move"), (T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")]
R_KEYS = (T_RELEASE, T_OUT, T_HIDE, T_SHOW, T_LATCH, T_SEAT, T_SEAT + 0.035)
RELOAD = Clip("reload", 2.70, reload_pose, events=R_EVENTS, extra_times=R_KEYS)

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change; then the hand drops from the magazine to the left cocking handle under the housing front
# (2.14), hooks it, draws it back against the spring (2.20 .. 2.34, bolt_back), lets go (2.37): the bolt slams home
# (2.41, bolt_forward) - the gun jolts, the camera jumps - and the hand swings back to the forend (2.72)
T_REACH, T_GRAB2, T_BBACK, T_LET, T_BFWD = 2.14, 2.20, 2.34, 2.37, 2.41
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0020, -0.0040, 0.0015)), (0.34, V(-0.0120, 0.0180, -0.0400)),
                     (0.52, V(-0.0100, 0.0160, -0.0420)), (0.82, V(-0.0130, 0.0140, -0.0400)), (1.16, V(-0.0120, 0.0100, -0.0380)),
                     (T_ALIGN, V(-0.0110, 0.0150, -0.0400)), (T_LATCH, V(-0.0100, 0.0170, -0.0410)),
                     (T_SEAT, V(-0.0100, 0.0120, -0.0400)), (2.04, V(-0.0120, 0.0100, -0.0480)),
                     (T_REACH, V(-0.0160, 0.0020, -0.0620)), (T_BBACK, V(-0.0170, 0.0030, -0.0580)),
                     (T_BFWD, V(-0.0150, 0.0000, -0.0640)), (2.52, V(-0.0120, 0.0000, -0.0640)),
                     (2.86, V(0.0026, -0.0036, 0.0010)), (3.04, V(-0.0006, 0.0008, -0.0002)), (3.20, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.0010, 0.0), 10.0, 0.4), (T_OUT, V(0.0, 0.0016, 0.0022), 7.0, 0.4),
                      (T_LATCH, V(0.0, 0.0, -0.0016), 9.0, 0.4), (T_SEAT, V(0.0, -0.0042, 0.0006), 7.5, 0.35),
                      (T_BBACK, V(0.0, 0.0008, 0.0022), 9.0, 0.4), (T_BFWD, V(0.0, -0.0026, -0.0040), 7.5, 0.32),
                      (2.74, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.52, 0.80, V(0.0008, 0.0007, 0.0006), 51, 0.7),
                       (1.10, 1.70, V(0.0009, 0.0008, 0.0006), 52, 0.6)], end=3.20)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.6, -1.0, -2.4)), (0.34, V(6.4, 12.0, 10.5)),
                     (0.52, V(6.0, 11.4, 9.8)), (0.82, V(5.3, 11.8, 9.0)), (1.16, V(5.8, 12.2, 9.5)),
                     (T_ALIGN, V(6.3, 11.6, 10.2)), (T_LATCH, V(6.0, 11.2, 9.9)), (T_SEAT, V(4.8, 11.0, 9.3)),
                     (2.04, V(5.1, 12.0, 11.0)), (T_REACH, V(4.2, 14.0, 14.0)), (T_BBACK, V(4.4, 14.6, 14.5)),
                     (T_BFWD, V(3.9, 13.8, 13.5)), (2.52, V(4.1, 13.0, 13.0)), (2.88, V(-0.6, -0.9, -2.6)),
                     (3.06, V(0.2, 0.3, 0.6)), (3.20, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.0, 0.6, -0.5), 10.0, 0.4), (T_OUT, V(0.8, -0.6, 0.4), 7.0, 0.4),
                      (T_LATCH, V(-0.4, 0.0, 0.0), 9.0, 0.4), (T_SEAT, V(-2.2, 0.5, -1.6), 7.5, 0.35),
                      (T_BBACK, V(0.0, 0.8, -1.0), 9.0, 0.4), (T_BFWD, V(0.8, -2.4, 1.8), 7.5, 0.32),
                      (2.74, V(0.0, -0.5, 0.4), 8.0, 0.4)],
               drifts=[(0.52, 0.80, V(0.4, 0.4, 0.6), 53, 0.7), (1.10, 1.70, V(0.5, 0.5, 0.7), 54, 0.6)], end=3.20)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, -0.4)), (0.40, V(-0.7, 0.2, 1.5)),
                     (0.80, V(-0.4, 0.1, 1.0)), (1.30, V(-0.5, 0.2, 1.2)), (1.84, V(-0.4, 0.1, 1.1)),
                     (2.10, V(-0.3, 0.2, 1.8)), (T_BBACK, V(0.2, 0.0, 1.5)), (2.56, V(-0.3, 0.1, 1.2)),
                     (2.92, V(0.3, -0.1, -0.6)), (3.20, V(0, 0, 0))),
               rings=[(T_RELEASE, V(0.2, 0.0, -0.3), 9.0, 0.4), (T_SEAT, V(-1.1, 0.15, -0.8), 6.5, 0.35),
                      (T_BFWD, V(1.3, -0.3, 1.1), 6.5, 0.32)], end=3.20)
E_BOLT = Curve((0.0, 0.0), (T_GRAB2, 0.0), (2.25, 0.0, "stop"), (2.29, 0.018), (T_BBACK, TRAVEL, ("hit", 1.4)),
               (T_LET, TRAVEL - 0.0012, "stop"), (T_BFWD, 0.0, ("hit", 2.4)), (T_BFWD + 0.010, 0.0013, "stop"),
               (T_BFWD + 0.022, 0.0, "stop"), (3.20, 0.0))
E_LEFT = Weights((T_SEAT + 0.03, {"mag_side": 1}), (T_REACH, {"charging": 1}), (T_LET, {"charging": 1}),
                 (2.52, {"world": 1}), (2.74, {"support": 1}))
E_LEFT_OFF = Curve((T_SEAT + 0.03, V(0, 0, 0)), (2.02, V(0.0, 0.016, 0.006)), (2.08, V(-0.024, 0.004, 0.004), "flow"),
                   (T_REACH, V(0, 0, 0)), (T_GRAB2, V(0.0, 0.0, -0.002)), (T_LET, V(0, 0, 0)),
                   (2.50, V(-0.006, -0.020, 0.026), "flow"), (2.74, V(0, 0, 0)))
E_FINGERS = Curve((T_SEAT + 0.03, 0.4), (2.02, 1.0), (T_REACH - 0.03, 1.0), (T_GRAB2, 0.0), (T_LET - 0.005, 0.0),
                  (T_LET + 0.05, 1.0), (2.58, 1.0), (2.72, 0.0))
E_REGRIP = Curve((2.70, 0.0), (2.78, 1.0), (2.92, 0.0))


def let_go(p):
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.012, -0.030, 0.030)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    mag_swap(p, t)
    p.bolt_z = float(E_BOLT(t))
    if t < T_SEAT + 0.03:
        mag_hands(p, t)
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_FINGERS(t)))
        curls = blend_curls(curls, hands.POSES["rpd_clamp"], 0.8 * float(E_REGRIP(t)))
        p.hand_l = (pos, q, curls)
        w = 1 - smoothstep(T_SEAT, T_REACH, t)
        p.shoulder_l = V(0.030, 0.040, 0.000) * w + V(-0.010, -0.040, 0.020) * smoothstep(T_SEAT, T_REACH, t) \
            * (1 - smoothstep(2.45, 2.74, t))
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.8, 3.0)), 0))
    cam(p, *E_CAM(t))
    return p


E_EVENTS = R_EVENTS + [(T_BBACK, "bolt_back"), (T_BFWD, "bolt_forward")]
RELOAD_EMPTY = Clip("reload_empty", 3.20, reload_empty_pose, events=E_EVENTS,
                    extra_times=R_KEYS + (T_GRAB2, T_BBACK, T_LET, T_BFWD, T_BFWD + 0.010))

# --------------------------------------------------------------------------------------- inspect
# anticipation -> a swing round to show the left flank and the magazine full of rounds, tipped towards the eye
# (overshoot 0.52) -> a moving hold; the support hand flicks the left cocking handle back a little and lets it snap
# home (1.02 / 1.12) -> a counter-roll and the gun rolls right over to show its right side and the rails (overshoot
# 1.96) -> a moving hold -> back to ready past the rest pose, settle.
I_BACK, I_LET, I_FWD = 1.02, 1.08, 1.12
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.52, V(-0.0240, 0.0060, -0.0360)),
                     (0.70, V(-0.0220, 0.0040, -0.0340)), (1.20, V(-0.0230, 0.0060, -0.0360)), (1.46, V(-0.0200, 0.0040, -0.0320)),
                     (1.96, V(-0.0480, -0.0220, -0.0440)), (2.12, V(-0.0460, -0.0200, -0.0420)),
                     (2.80, V(-0.0470, -0.0220, -0.0440)), (3.34, V(0.0030, -0.0040, 0.0012)),
                     (3.56, V(-0.0006, 0.0008, -0.0002)), (3.80, V(0, 0, 0))),
               rings=[(I_FWD, V(0.0, -0.0018, -0.0026), 8.0, 0.35), (2.12, V(0.0, -0.0012, 0.0004), 6.0, 0.45)],
               drifts=[(0.70, 1.40, V(0.0010, 0.0009, 0.0007), 61, 0.6), (2.10, 2.80, V(0.0010, 0.0009, 0.0007), 62, 0.6)],
               end=3.80)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, -2.2)), (0.52, V(38.0, 12.0, 26.0)), (0.70, V(35.5, 11.0, 24.0)),
                     (1.20, V(36.5, 11.5, 25.5)), (1.46, V(35.0, 10.0, 28.0)), (1.96, V(12.0, 26.0, -62.4)),
                     (2.12, V(11.0, 27.0, -59.2)), (2.80, V(12.0, 25.5, -60.8)), (3.36, V(-0.8, -1.2, 2.8)),
                     (3.58, V(0.2, 0.3, -0.5)), (3.80, V(0, 0, 0))),
               rings=[(I_FWD, V(0.6, -1.6, 1.2), 8.0, 0.35), (2.12, V(0.2, -0.8, -0.7), 6.0, 0.45)],
               drifts=[(0.70, 1.40, V(0.6, 0.7, 1.0), 63, 0.6), (2.10, 2.80, V(0.6, 0.7, 1.0), 64, 0.6)], end=3.80)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, -0.4)), (0.52, V(0.5, -0.4, 1.4)), (1.40, V(0.3, -0.2, 0.9)),
                     (1.96, V(-0.4, 0.5, -1.8)), (2.80, V(-0.2, 0.3, -1.2)), (3.40, V(0.2, -0.1, 0.5)),
                     (3.80, V(0, 0, 0))),
               rings=[(I_FWD, V(0.8, -0.2, 0.7), 7.0, 0.35)], end=3.80)
I_BOLT = Curve((0.0, 0.0), (0.92, 0.0, "stop"), (0.97, 0.008), (I_BACK, 0.026, ("hit", 1.3)), (I_LET, 0.0255, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0008, "stop"), (I_FWD + 0.020, 0.0, "stop"), (3.8, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (0.72, {"support": 1}), (0.90, {"charging": 1}), (I_LET, {"charging": 1}),
                 (1.24, {"world": 1}), (1.46, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (0.72, V(0, 0, 0)), (0.80, V(-0.012, -0.010, 0.012), "flow"), (0.90, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (1.26, V(0.004, -0.016, 0.022), "flow"), (1.46, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (0.72, 0.0), (0.78, 1.0), (0.84, 1.0), (0.91, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (1.30, 1.0), (1.44, 0.0))
I_REGRIP = Curve((1.42, 0.0), (1.50, 1.0), (1.64, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.52, V(0.0, 0.10, -0.10)), (1.46, V(0.0, 0.10, -0.10)),
               (1.96, V(0.013, -0.060, 0.014)), (2.80, V(0.013, -0.060, 0.014)), (3.34, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.52, V(-0.020, 0.010, -0.040)), (1.46, V(-0.020, 0.010, -0.040)),
               (1.96, V(0.010, 0.000, -0.060)), (2.80, V(0.010, 0.000, -0.060)), (3.34, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.52, V(1.0, 0.0, 0.3)), (1.46, V(1.0, 0.0, 0.3)),
                 (1.96, V(0.39, 0.18, 0.90)), (2.80, V(0.39, 0.18, 0.90)), (3.34, rig.POLE_R))


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
    tap = smoothstep(1.6, 1.9, t) * (1 - smoothstep(2.9, 3.2, t))
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    curls = blend_curls(curls, hands.POSES["rpd_clamp"], 0.7 * float(I_REGRIP(t)))
    p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.5, 3.8, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 3.80, inspect_pose,
               events=[(0.10, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"), (1.46, "inspect_move"),
                       (2.86, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))


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
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["p90_fore"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.150, -0.118, -0.290)
LOW_Q = gun_frame((-0.30, -0.60, -0.74), (-0.30, 0.70, -0.55))
LOW_SHOULDER = V(0.080, 0.160, -0.100)
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    half = SPRINT_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.04)
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))
    p.gun_t = LOW_T + V(0.006 * side, 0.011 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.6 * np.sin(w * (t - 0.03)), 3.4 * bl + 3.4 * swing, 3.8 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"p90_grip": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.150, -0.290)
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.04)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.013 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.2 * np.sin(w * (t - 0.03)), 3.2 * bl, 3.8 * np.cos(w * (t - 0.075))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"p90_grip": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
