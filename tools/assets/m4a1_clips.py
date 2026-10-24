"""M4A1 clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> m4a1_rig.Pose; gen_m4a1 bakes them (IK included) at
>= 60 samples/s (fire at 120). Authored with the section 31 toolkit like the AK (anim.Curve, ring, drift, Follow-like
finger lag), on the AR's real manual of arms:

* fire (800 rpm, 0.075 s): the carrier cycles (gas snap back to the buffer, spring home with a hit and a rebound);
  fire_last: the carrier comes back onto the bolt catch and stays there, held open (the runtime keeps it there
  while the gun is empty: guns.json slideLock offset).
* reload (tactical, 2.2 s): the support hand takes the magazine, the shooting hand's index presses the magazine
  catch, the old magazine is drawn straight down out of the well and tossed, a fresh one fetched from off screen,
  aligned nose first and pushed straight up with a hit, the palm tugs / slaps the floor plate.
* reload_empty (2.7 s): the bolt starts held open; the index presses the catch and the empty magazine drops free
  under its own weight while the support hand is already on its way to the pouch; the new one is seated, the hand
  slides up the well and the thumb slaps the bolt catch: the carrier slams home and the carbine jolts.
* inspect (4.0 s): the left flank, a roll port-up, a press check with the charging handle (carrier and handle pulled
  30 mm, a look into the chamber, let go: both slam home), back to ready."""
import functools

import numpy as np

import ads_fire
import armrig
import hands
import m4a1_model as mm
import m4a1_rig as rig
import motion_layer
from ak_clips import (FOOT, FOOT_WALK, Layers, blend_curls, fade_end, foot, gun_frame, open_fingers, punch_left)
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
LIP = np.array([0.0, -0.0010, -mm.MAG_HD])          # mag-local front top edge: the magazine noses in on it
TRAVEL, HOLD = mm.BOLT_TRAVEL, mm.BOLT_HOLD
GRAVITY = V(0.0, -9.81, 0.0)
CLEAR = 0.0640                                       # drop at which the magazine's top leaves the well


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


# The low hip pose hides the magazine well below the screen edge: both reloads lift the carbine into a presentation
# (on top of their own offsets) while the hands work at the well.
LIFT_OFF = V(-0.012, 0.040, 0.010)
LIFT_ROT = V(3.0, 6.0, -6.0)


class Lifted:
    """A clip's gun offset / turn plus an extra pose (`kind` "off" / "rot": the presentation lift, or a vector)
    scaled by a 0..1 weight curve."""

    def __init__(self, base, weight, kind):
        self.base, self.weight, self.kind = base, weight, kind

    def __call__(self, t):
        extra = self.kind if not isinstance(self.kind, str) else LIFT_OFF if self.kind == "off" else LIFT_ROT
        return self.base(t) + float(self.weight(t)) * extra


def mag_at(theta, offset=(0, 0, 0), extra=(0, 0, 0)):
    """Magazine turned `theta` deg about its front top edge (+ = bottom forward), then offset (gun space)."""
    q = quat_mul(quat_axis_angle((1, 0, 0), theta), quat_euler(*extra))
    return MAG_REST + LIP - quat_rotate(q, LIP) + np.asarray(offset, float), q


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def right_hand(p, t, press):
    """The shooting hand: on the grip, the index stretching to the magazine catch while `press` (Curve 0..1) rises;
    the button goes in with the finger."""
    w = float(np.clip(press(t), 0.0, 1.0))
    if w <= 0.0:
        return
    pos, q, _ = p.blend_hand({"grip": 1 - w, "button": w})
    wl = float(np.clip(press(max(t - 0.03, 0.0)), 0.0, 1.0))         # the finger trails the hand a little
    curls = blend_curls(p.fingers_r if p.fingers_r is not None else hands.POSES["ak_indexed"],
                        hands.POSES["m4_button"], wl)
    p.hand_r = (pos, q, curls)
    p.fingers_r = curls


# ------------------------------------------------------------------------------------------------ idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.15 * np.sin(w + 0.3), 0.18 * np.sin(w + 0.9), 0.20 * np.sin(w) + 0.07 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------------ draw
# off screen for ~0.12 s (the hand gathers the carbine), whipped up muzzle first (the turn overshoots at 0.28, the
# position at 0.35), the support hand slaps onto the handguard at 0.38, settles by 0.65.
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.265, 0.090)), (0.06, V(0.053, -0.276, 0.094)),
                        (0.35, V(-0.005, 0.009, -0.006)), (0.48, V(0.0012, -0.0022, 0.0009)), (0.65, V(0, 0, 0))),
                  rings=[(0.38, V(0.0, -0.0014, 0.0005), 8.5, 0.4)], end=0.65)
DRAW_ROT = Layers(Curve((0.00, V(-16, -46, -30)), (0.06, V(-17, -49, -32)), (0.28, V(2.0, 4.0, 3.0)),
                        (0.43, V(-0.6, -1.0, -0.8)), (0.56, V(0.15, 0.2, 0.1)), (0.65, V(0, 0, 0))),
                  rings=[(0.38, V(0.2, -0.8, 0.6), 8.5, 0.4)], end=0.65)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.25, 0, 0.15)), (0.30, V(-0.80, 0.1, -0.55)),
                        (0.45, V(0.30, 0, 0.22)), (0.65, V(0, 0, 0))),
                  rings=[(0.38, V(-0.32, 0.0, 0.22), 7.0, 0.4)], end=0.65)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.26, V(-0.010, -0.034, 0.024)), (0.38, V(0, 0.003, 0)),
                (0.46, V(0, -0.0008, 0)), (0.54, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.30, {"spread": 1.0}), (0.39, {"m4_clamp": 1.0}),
                  (0.50, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.28, {"grip": 1.0}), (0.46, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.65, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------------ fire
# 800 rpm: gas snaps the carrier back (a soft bump on the buffer at 0.019), the buffer spring drives it home
# (accelerating, a hit at 0.055 with a 1.4 mm bounce). The clip's kick is the hands' reaction only (the runtime adds
# the root recoil). The last round: the carrier comes forward onto the bolt catch and stops there.
F_BOLT = Curve((0.0, 0.0), (0.003, 0.0, ("snap", 2.4)), (0.019, TRAVEL, ("hit", 0.9)), (0.024, TRAVEL - 0.0006),
               (0.055, 0.0, ("hit", 2.2)), (0.060, 0.0014, "stop"), (0.066, 0.0, "stop"), (0.075, 0.0))
F_BOLT_LAST = Curve((0.0, 0.0), (0.003, 0.0, ("snap", 2.4)), (0.019, TRAVEL, ("hit", 0.9)),
                    (0.024, TRAVEL - 0.0006), (0.032, HOLD, ("hit", 1.6)), (0.037, HOLD + 0.0009, "stop"),
                    (0.043, HOLD, "stop"), (0.075, HOLD))
F_TRIG = Curve((0.0, 6.0), (0.005, rig.TRIGGER_PULL, ("hit", 1.6)), (0.042, rig.TRIGGER_PULL), (0.068, 6.0),
               (0.075, 6.0))
_FIRE_END = 0.075


def _fire_kick(t, last):
    k = fade_end(t, _FIRE_END, 0.025)
    home = 0.032 if last else 0.055
    off = ring(t, 0.001, V(0.0003, 0.0013, 0.0045), 13.0, 0.5) + ring(t, home, V(0.0, -0.0003, -0.0009), 15.0, 0.5)
    rot = ring(t, 0.001, V(0.2, 1.2, -0.35), 12.0, 0.5) + ring(t, home, V(0.0, -0.28, 0.08), 15.0, 0.5)
    return off * k, rot * k


def fire_pose(last):
    bolt = F_BOLT_LAST if last else F_BOLT

    def pose(t):
        p = rig.Pose()
        apply_gun(p, *_fire_kick(t, last))
        p.bolt_z = float(bolt(t))
        p.trigger = float(F_TRIG(t))
        k = float(F_TRIG(t)) / rig.TRIGGER_PULL
        p.fingers_r = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
        clamp = -5.0 * float(ring(t, 0.010, 1.0, 10.0, 0.6)) * fade_end(t, _FIRE_END, 0.025)
        p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                                   thumb=(0.5 * clamp, 0))
        cam(p, *(ring(t, 0.002, V(0.28, 0.03, 0.07), 10.0, 0.5) * fade_end(t, _FIRE_END, 0.03)))
        return p
    return pose


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + [0.010, 0.016, 0.058]))
FIRE = Clip("fire", _FIRE_END, fire_pose(False), fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", _FIRE_END, fire_pose(True), fps=120,
                 extra_times=sorted(set(FIRE_KEYS + F_BOLT_LAST.times())))
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)


# --------------------------------------------------------------------------------------- magazine physics
def mag_world(gun_off, gun_rot, mag_t, mag_q):
    p = rig.Pose()
    apply_gun(p, gun_off, gun_rot)
    return p.gun_point(mag_t), quat_mul(p.gun_q, mag_q)


def to_gun(p, pw, qw):
    inv = quat_conj(p.gun_q)
    return quat_rotate(inv, pw - p.gun_t), quat_mul(inv, qw)


def ballistic(p, t, t0, p0, v0, q0, tumble):
    tau = t - t0
    pw = p0 + v0 * tau + 0.5 * GRAVITY * tau * tau
    ang = np.linalg.norm(tumble) * tau
    qw = quat_mul(quat_axis_angle(tumble, np.degrees(ang)), q0) if ang > 1e-9 else q0
    return to_gun(p, pw, qw)


# ------------------------------------------------------------------------------- the new magazine (both reloads)
def mag_in_curves(t_show, t_hook, t_seat):
    """Theta (nose-in tilt), offset and spin of the fresh magazine from where the hand shows it to the seat: carried
    up on an arc, aligned nose first in the mouth of the well (slow), pushed straight up and seated with a hit (a
    1 mm overtravel as it latches)."""
    away = V(-0.075, -0.300, 0.105)
    mid = t_show + 0.45 * (t_hook - t_show)
    th = Curve((t_show, 26.0), (mid, 20.0), (t_hook - 0.10, 11.0), (t_hook, 7.0),
               (t_seat - 0.05, 1.2), (t_seat, 0.0, ("hit", 2.0)), (t_seat + 0.07, 0.0))
    off = Curve((t_show, away), (mid, V(-0.028, -0.120, 0.032)), (t_hook - 0.10, V(-0.003, -0.050, -0.002)),
                (t_hook, V(0.0, -0.0400, 0.0)), (t_seat - 0.05, V(0.0, -0.0120, 0.0)),
                (t_seat, V(0.0, 0.0012, 0.0), ("hit", 2.4)), (t_seat + 0.035, V(0.0, -0.0004, 0.0), "stop"),
                (t_seat + 0.07, V(0, 0, 0), "stop"))
    spin = Curve((t_show, V(10, -20, 35)), (mid, V(6, -12, 20)), (t_hook - 0.05, V(0, 0, 0)))
    return th, off, spin


def new_mag(p, t, curves):
    th, off, spin = curves
    p.mag_t, p.mag_q = mag_at(float(th(t)), off(t), spin(t))


def fetch_hand(p, t, rel, t_from, t_show, curves, bulge=V(-0.030, -0.070, 0.030)):
    """The support hand from its release state `rel` (world target) diving off screen to the new magazine."""
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    new_mag(q, t_show, curves)
    new = q.anchor("mag_low")
    w = float(Curve((t_from, 0.0), (t_show - 0.06, 1.0), (t_show, 1.0))(t))
    pos, qq, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
    pos = pos + arc(min(1.0, (t - t_from) / (t_show - 0.06 - t_from)), bulge)
    open_w = smoothstep(t_from, t_from + 0.05, t) * (1 - smoothstep(t_show - 0.12, t_show - 0.04, t))
    curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
    p.hand_l = (pos, qq, curls)


# ------------------------------------------------------------------------------------- reload (tactical)
# let go 0.05 -> the carbine dips and rolls (anticipation) -> rolls into the presentation (overshoot 0.30) ->
# the hand closes on the magazine (0.24) -> the index presses the catch (0.30, mag_release) -> the magazine is drawn
# straight down out of the well (clears at 0.42, mag_out) and tossed (0.47) -> the hand dives off screen and shows a
# fresh one (0.78) -> nose in at the mouth (1.08) -> pushed up and seated (1.20, mag_in) -> the palm slaps the floor
# plate (1.34) -> back to the handguard (1.66) -> the carbine swings back to ready past the rest pose, settles.
T_LETGO, T_GRAB, T_REL, T_OUT, T_TOSS = 0.05, 0.24, 0.30, 0.42, 0.47
T_SHOW, T_HOOK, T_SEAT, T_SLAP = 0.78, 1.08, 1.20, 1.34
T_HIDE = 0.74
R_END = 2.20
R_IN = mag_in_curves(T_SHOW, T_HOOK, T_SEAT)
R_PULL = Curve((T_REL, 0.0), (T_REL + 0.02, -0.0020), (T_REL + 0.06, -0.0080), (T_OUT, -CLEAR - 0.004),
               (T_TOSS, -CLEAR - 0.022))
R_PULL_TH = Curve((T_REL, 0.0), (T_OUT, 2.0), (T_TOSS, 9.0))
TOSS = V(-0.30, -0.25, 0.10)
TUMBLE = V(3.4, 1.0, -2.6)
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.0022, -0.0040, 0.0015)), (0.30, V(-0.0400, 0.0300, -0.0050)),
                     (0.44, V(-0.0386, 0.0282, -0.0035)), (0.78, V(-0.0396, 0.0296, -0.0042)),
                     (T_HOOK, V(-0.0392, 0.0306, -0.0055)), (T_SEAT, V(-0.0378, 0.0280, -0.0024)),
                     (T_SLAP + 0.04, V(-0.0392, 0.0316, -0.0058)), (1.70, V(0.0028, -0.0040, 0.0012)),
                     (1.92, V(-0.0007, 0.0009, -0.0003)), (R_END, V(0, 0, 0))),
               rings=[(T_REL, V(0.0, 0.0010, 0.0), 10.0, 0.4), (T_OUT, V(0.0, -0.0018, -0.0015), 7.0, 0.4),
                      (T_SEAT, V(0.0, 0.0040, 0.0018), 7.5, 0.35), (T_SLAP, V(0.0, 0.0028, 0.0), 9.0, 0.35),
                      (1.66, V(0.0, -0.0013, 0.0), 8.0, 0.4)],
               drifts=[(0.44, 1.06, V(0.0009, 0.0008, 0.0006), 11, 0.7)], end=R_END)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(-0.5, -1.0, 2.0)), (0.30, V(4.2, 7.6, -23.0)),
                     (0.44, V(3.8, 7.0, -21.0)), (0.78, V(4.0, 7.4, -22.0)), (T_HOOK, V(3.5, 8.4, -20.4)),
                     (T_SEAT, V(3.4, 6.8, -19.2)), (T_SLAP + 0.04, V(2.8, 7.0, -22.6)), (1.72, V(-0.6, -1.0, 2.6)),
                     (1.94, V(0.2, 0.3, -0.5)), (R_END, V(0, 0, 0))),
               rings=[(T_REL, V(0.0, 0.7, 0.4), 10.0, 0.4), (T_OUT, V(0.0, -1.0, 0.2), 7.0, 0.4),
                      (T_SEAT, V(-0.5, 2.4, 1.6), 7.5, 0.35), (T_SLAP, V(0.0, 1.5, -0.8), 9.0, 0.35),
                      (1.66, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.44, 1.06, V(0.4, 0.5, 0.6), 12, 0.7)], end=R_END)
R_LIFT = Curve((0.0, 0.0), (0.08, 0.0), (0.32, 1.0), (1.40, 1.0), (1.74, 0.0), (R_END, 0.0))
R_OFF, R_ROT = Lifted(R_OFF, R_LIFT, "off"), Lifted(R_ROT, R_LIFT, "rot")
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.2, 0, 0.4)), (0.34, V(-0.7, 0.2, -1.6)),
                     (0.62, V(-0.4, 0.1, -1.0)), (1.10, V(-0.5, 0.2, -1.2)), (T_SEAT, V(-0.4, 0.1, -1.0)),
                     (1.42, V(-0.5, 0.1, -1.3)), (1.76, V(0.3, -0.1, 0.7)), (R_END, V(0, 0, 0))),
               rings=[(T_REL, V(0.22, 0.0, 0.25), 9.0, 0.4), (T_SEAT, V(-1.2, 0.15, 0.9), 6.5, 0.35),
                      (T_SLAP, V(-0.8, 0.0, -0.5), 8.0, 0.35)], end=R_END)
R_PRESS = Curve((0.0, 0.0), (0.16, 0.0), (0.27, 1.0), (T_REL + 0.04, 1.0), (0.46, 0.0))
R_BUTTON = Curve((0.0, 0.0), (0.27, 0.0), (T_REL, 1.0, ("hit", 1.6)), (T_REL + 0.05, 1.0), (T_REL + 0.10, 0.0))
L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (T_GRAB, {"mag": 1}))
L_OUT_OFF = Curve((0.00, V(0, 0, 0)), (T_LETGO, V(0, 0, 0)), (0.14, V(-0.020, -0.030, 0.016), "flow"),
                  (T_GRAB, V(0, 0, 0)))
L_OPEN = Curve((0.0, 0.0), (T_LETGO, 0.0), (0.12, 1.0), (0.19, 0.8), (T_GRAB, 0.0))
L_SLAP = Weights((T_SEAT + 0.015, {"mag_low": 1}), (1.30, {"slap": 1}), (1.40, {"slap": 1}), (1.66, {"support": 1}))
L_SLAP_OFF = Curve((T_SEAT + 0.015, V(0, 0, 0)), (1.29, V(0.0, -0.020, 0.004)), (T_SLAP, V(0.0, 0.002, 0.0), "hit"),
                   (1.40, V(0.0, -0.004, 0.0)), (1.52, V(-0.020, -0.030, 0.014), "flow"), (1.66, V(0, 0, 0)))
L_SLAP_OPEN = Curve((T_SEAT + 0.015, 0.0), (1.28, 1.0), (1.44, 1.0), (1.60, 0.0))
L_REGRIP = Curve((1.60, 0.0), (1.68, 1.0), (1.80, 0.0))
SH_MAG = Curve((0.00, V(0, 0, 0)), (0.06, V(0, 0, 0)), (0.28, V(-0.110, -0.020, 0.100)),
               (T_SEAT + 0.10, V(-0.110, -0.020, 0.100)), (1.74, V(0, 0, 0)))


def _tac_mag_held(t):
    return mag_at(float(R_PULL_TH(t)), V(0.0, float(R_PULL(t)), 0.0))


def _tac_left_out(p, t):
    left_hand(p, L_OUT, t, L_OUT_OFF(t))
    pos, q, curls = p.hand_l
    p.hand_l = (pos, q, open_fingers(curls, float(L_OPEN(t))))


@functools.lru_cache(maxsize=None)
def _tac_release():
    """World state of the old magazine and of the hand as it tosses it (t = T_TOSS)."""
    def state(t):
        p = rig.Pose()
        apply_gun(p, R_OFF(t), R_ROT(t))
        p.mag_t, p.mag_q = _tac_mag_held(t)
        _tac_left_out(p, t)
        return p
    p1, p0 = state(T_TOSS), state(T_TOSS - 0.012)
    w1, q1 = mag_world(R_OFF(T_TOSS), R_ROT(T_TOSS), p1.mag_t, p1.mag_q)
    w0, _ = mag_world(R_OFF(T_TOSS - 0.012), R_ROT(T_TOSS - 0.012), p0.mag_t, p0.mag_q)
    return w1, (w1 - w0) / 0.012 * 0.6 + TOSS, q1, p1.hand_l


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, R_OFF(t), R_ROT(t))
    p.shoulder_l = SH_MAG(t)
    p.button = float(np.clip(R_BUTTON(t), 0.0, 1.0))
    if t < T_REL:
        pass
    elif t < T_TOSS:
        p.mag_t, p.mag_q = _tac_mag_held(t)
    elif t < T_SHOW:
        w1, v1, q1, _ = _tac_release()
        p.mag_t, p.mag_q = ballistic(p, t, T_TOSS, w1, v1, q1, TUMBLE)
        if t >= T_HIDE:
            p.mag_s = 0.0
    else:
        new_mag(p, t, R_IN)
    if t < T_TOSS:
        _tac_left_out(p, t)
    elif t < T_SHOW:
        fetch_hand(p, t, _tac_release()[3], T_TOSS, T_SHOW, R_IN)
    elif t < T_SEAT + 0.015:
        left_hand(p, Weights((0.0, {"mag_low": 1})), t)
    else:
        left_hand(p, L_SLAP, t, L_SLAP_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(L_SLAP_OPEN(t)))
        p.hand_l = (pos, q, blend_curls(curls, hands.POSES["m4_clamp"], float(L_REGRIP(t)) * 0.8))
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.06, 0.2) * (1 - ease("inout", t, 1.7, 2.0)), 0))
    right_hand(p, t, R_PRESS)
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", R_END, reload_pose,
              events=[(T_REL, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=(T_REL, T_OUT, T_TOSS, T_HIDE, T_SHOW, T_SEAT, T_SLAP))

# ---------------------------------------------------------------------------------------- reload_empty
# the bolt is held open; the index presses the catch (0.32) and the empty magazine drops free under its own weight
# (clears the well at 0.44) while the support hand already dives for the pouch; a fresh magazine is shown (0.82),
# nosed in (1.12) and seated (1.25, mag_in); the hand slides up the well, the thumb lifts (wind-up) and slaps the bolt
# catch (1.60): the carrier slams home (1.635, bolt_forward), the carbine jolts and nods; the hand goes back to the
# handguard, the carbine swings back to ready and settles.
E_REL, E_SHOW, E_HOOK, E_SEAT = 0.32, 0.82, 1.12, 1.25
E_ONCATCH, E_PRESS, E_FWD = 1.46, 1.60, 1.635
E_HIDE = 0.78
E_END = 2.70
E_IN = mag_in_curves(E_SHOW, E_HOOK, E_SEAT)
E_SLIDE = 0.7 * 9.81                                 # the magazine slides out of the well (friction of the walls)
E_CLEAR_T = E_REL + 0.012 + np.sqrt(2 * CLEAR / E_SLIDE)
E_OUT = round(float(E_CLEAR_T), 3)
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.0022, -0.0040, 0.0015)), (0.32, V(-0.0400, 0.0300, -0.0050)),
                     (0.46, V(-0.0388, 0.0286, -0.0036)), (0.82, V(-0.0398, 0.0298, -0.0044)),
                     (E_HOOK, V(-0.0392, 0.0306, -0.0055)), (E_SEAT, V(-0.0378, 0.0280, -0.0024)),
                     (E_ONCATCH, V(-0.0420, 0.0330, -0.0120)), (E_PRESS - 0.03, V(-0.0426, 0.0336, -0.0128)),
                     (E_FWD, V(-0.0418, 0.0322, -0.0105)), (1.80, V(-0.0380, 0.0300, -0.0090)),
                     (2.16, V(0.0028, -0.0040, 0.0012)), (2.40, V(-0.0007, 0.0009, -0.0003)), (E_END, V(0, 0, 0))),
               rings=[(E_REL, V(0.0, 0.0010, 0.0), 10.0, 0.4), (E_OUT, V(0.0, 0.0012, 0.0), 6.0, 0.45),
                      (E_SEAT, V(0.0, 0.0040, 0.0018), 7.5, 0.35), (E_PRESS, V(-0.0010, 0.0, 0.0), 10.0, 0.45),
                      (E_FWD, V(0.0, -0.0030, -0.0048), 7.0, 0.32), (2.12, V(0.0, -0.0013, 0.0), 8.0, 0.4)],
               drifts=[(0.46, 1.10, V(0.0009, 0.0008, 0.0006), 13, 0.7)], end=E_END)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(-0.5, -1.0, 2.0)), (0.32, V(4.2, 7.6, -23.0)),
                     (0.46, V(3.8, 7.0, -21.0)), (0.82, V(4.0, 7.4, -22.0)), (E_HOOK, V(3.5, 8.4, -20.4)),
                     (E_SEAT, V(3.4, 6.8, -19.2)), (E_ONCATCH, V(5.5, 9.0, -27.0)),
                     (E_PRESS - 0.03, V(5.8, 9.3, -27.8)), (E_FWD, V(5.2, 8.6, -26.4)), (1.82, V(4.6, 8.0, -25.0)),
                     (2.18, V(-0.6, -1.0, 2.8)), (2.42, V(0.2, 0.3, -0.5)), (E_END, V(0, 0, 0))),
               rings=[(E_REL, V(0.0, 0.7, 0.4), 10.0, 0.4), (E_OUT, V(0.0, 0.5, -0.3), 6.0, 0.45),
                      (E_SEAT, V(-0.5, 2.4, 1.6), 7.5, 0.35), (E_PRESS, V(0.0, 0.4, 0.8), 10.0, 0.45),
                      (E_FWD, V(0.9, -2.8, -2.0), 7.0, 0.32), (2.12, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.46, 1.10, V(0.4, 0.5, 0.6), 14, 0.7)], end=E_END)
E_LIFT = Curve((0.0, 0.0), (0.08, 0.0), (0.34, 1.0), (1.80, 1.0), (2.20, 0.0), (E_END, 0.0))
E_OFF, E_ROT = Lifted(E_OFF, E_LIFT, "off"), Lifted(E_ROT, E_LIFT, "rot")
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.2, 0, 0.4)), (0.36, V(-0.7, 0.2, -1.6)),
                     (0.66, V(-0.4, 0.1, -1.0)), (1.14, V(-0.5, 0.2, -1.2)), (E_SEAT, V(-0.4, 0.1, -1.0)),
                     (1.50, V(-0.3, 0.2, -1.8)), (1.80, V(-0.4, 0.1, -1.4)), (2.22, V(0.3, -0.1, 0.8)),
                     (E_END, V(0, 0, 0))),
               rings=[(E_REL, V(0.22, 0.0, 0.25), 9.0, 0.4), (E_SEAT, V(-1.2, 0.15, 0.9), 6.5, 0.35),
                      (E_FWD, V(1.5, -0.3, -1.3), 6.5, 0.32)], end=E_END)
E_PRESS_R = Curve((0.0, 0.0), (0.18, 0.0), (0.29, 1.0), (E_REL + 0.04, 1.0), (0.48, 0.0))
E_BUTTON = Curve((0.0, 0.0), (0.29, 0.0), (E_REL, 1.0, ("hit", 1.6)), (E_REL + 0.05, 1.0), (E_REL + 0.10, 0.0))
E_BOLT = Curve((0.0, HOLD), (E_PRESS, HOLD), (E_PRESS + 0.006, HOLD - 0.0004), (E_FWD, 0.0, ("hit", 2.4)),
               (E_FWD + 0.010, 0.0016, "stop"), (E_FWD + 0.024, 0.0, "stop"), (E_END, 0.0))
E_CATCH = Curve((0.0, 0.0), (E_PRESS - 0.03, 0.0), (E_PRESS, 1.0, ("hit", 1.8)), (E_PRESS + 0.05, 1.0),
                (E_PRESS + 0.11, 0.0))
E_THUMB = Curve((0.0, 0.0), (E_ONCATCH, 0.0), (E_PRESS - 0.05, 6.0), (E_PRESS, -4.0, ("hit", 1.6)),
                (E_PRESS + 0.06, -3.0), (E_PRESS + 0.14, 0.0))
E_L_OUT = Weights((0.00, {"support": 1}), (T_LETGO, {"support": 1}), (0.22, {"world": 1}))
E_LEFT = Weights((E_SEAT + 0.015, {"mag_low": 1}), (E_ONCATCH, {"catch": 1}), (E_FWD + 0.06, {"catch": 1}),
                 (2.06, {"support": 1}))
E_LEFT_OFF = Curve((E_SEAT + 0.015, V(0, 0, 0)), (1.36, V(-0.030, -0.020, 0.006), "flow"),
                   (E_ONCATCH, V(-0.028, -0.006, 0.0)), (E_PRESS - 0.05, V(-0.034, -0.008, 0.002)),
                   (E_PRESS, V(0.0, 0.0, 0.0), ("hit", 2.0)), (E_PRESS + 0.05, V(-0.0015, 0.0, 0.0), "stop"),
                   (E_FWD + 0.06, V(-0.008, -0.004, 0.0)), (1.86, V(-0.024, -0.034, 0.016), "flow"),
                   (2.06, V(0, 0, 0)))
E_FINGERS = Curve((E_SEAT + 0.015, 0.0), (1.34, 0.5), (E_ONCATCH, 0.0), (E_FWD + 0.04, 0.0), (1.74, 0.9),
                  (1.98, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.06, V(0, 0, 0)), (0.30, V(-0.110, -0.020, 0.100)),
                 (E_SEAT + 0.06, V(-0.110, -0.020, 0.100)), (E_ONCATCH, V(-0.060, -0.050, 0.060)),
                 (E_FWD + 0.05, V(-0.060, -0.050, 0.060)), (2.10, V(0, 0, 0)))


def _away_hand(p):
    """Where the support hand heads as it leaves (below and left of the well, already reaching for the pouch)."""
    pos, q, _ = p.anchor("support")
    return pos + p.gun_dir(V(-0.035, -0.090, 0.090)), q, hands.blend({"spread": 1.0})


@functools.lru_cache(maxsize=None)
def _empty_exit():
    """World position, velocity and orientation of the empty magazine as its top clears the well."""
    def at(t):
        p = rig.Pose()
        apply_gun(p, E_OFF(t), E_ROT(t))
        tau = max(t - E_REL - 0.012, 0.0)
        return mag_world(E_OFF(t), E_ROT(t), MAG_REST + V(0.0, -0.5 * E_SLIDE * tau * tau, 0.0),
                         np.array([0.0, 0.0, 0.0, 1.0]))
    w1, q1 = at(E_CLEAR_T)
    w0, _ = at(E_CLEAR_T - 0.008)
    return w1, (w1 - w0) / 0.008, q1


@functools.lru_cache(maxsize=None)
def _empty_leave():
    p = rig.Pose()
    apply_gun(p, E_OFF(0.22), E_ROT(0.22))
    left_hand(p, E_L_OUT, 0.22, world=_away_hand(p))
    return p.hand_l


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, E_OFF(t), E_ROT(t))
    p.shoulder_l = SH_EMPTY(t)
    p.button = float(np.clip(E_BUTTON(t), 0.0, 1.0))
    p.bolt_z = float(E_BOLT(t))
    p.catch = float(np.clip(E_CATCH(t), 0.0, 1.0))
    if E_REL <= t < E_SHOW:
        if t < E_CLEAR_T:
            tau = max(t - E_REL - 0.012, 0.0)
            p.mag_t = MAG_REST + V(0.0, -0.5 * E_SLIDE * tau * tau, 0.0)
        else:
            w1, v1, q1 = _empty_exit()
            p.mag_t, p.mag_q = ballistic(p, t, E_CLEAR_T, w1, v1, q1, V(1.8, 0.4, -1.2))
        if t >= E_HIDE:
            p.mag_s = 0.0
    elif t >= E_SHOW:
        new_mag(p, t, E_IN)
    if t < 0.22:
        left_hand(p, E_L_OUT, t, world=_away_hand(p))
        pos, q, curls = p.hand_l
        p.hand_l = (pos, q, open_fingers(curls, float(L_OPEN(t))))
    elif t < E_SHOW:
        fetch_hand(p, t, _empty_leave(), 0.22, E_SHOW, E_IN, bulge=V(-0.020, -0.050, 0.020))
    elif t < E_SEAT + 0.015:
        left_hand(p, Weights((0.0, {"mag_low": 1})), t)
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_FINGERS(t)))
        curls = hands.adjust(curls, thumb=(float(E_THUMB(t)), 0.4 * float(E_THUMB(t))))
        curls = blend_curls(curls, hands.POSES["m4_clamp"], 0.8 * float(L_REGRIP(t - 0.42)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.06, 0.2) * (1 - ease("inout", t, 2.2, 2.5)), 0))
    right_hand(p, t, E_PRESS_R)
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", E_END, reload_empty_pose,
                    events=[(E_REL, "mag_release"), (E_OUT, "mag_out"), (E_SEAT, "mag_in"), (E_FWD, "bolt_forward")],
                    extra_times=(E_REL, E_REL + 0.012, E_OUT, E_HIDE, E_SHOW, E_SEAT, E_PRESS, E_FWD, E_FWD + 0.010))

# ------------------------------------------------------------------------------------------------ inspect
# anticipation (0.10) -> a heavy swing round to show the left flank (overshoot 0.56, settle 0.74), a moving hold ->
# counter-roll (1.52) and the carbine rolls over port-up (overshoot 1.92) -> the support hand reaches over to the
# charging handle (2.22), pulls carrier and handle 30 mm back (2.40, bolt_back), holds while the carbine tips to look
# into the chamber, lets go: both slam home (2.665, bolt_forward), the carbine jolts -> back to the handguard, the
# carbine rolls back to ready past the rest pose and settles.
I_GRAB, I_BACK, I_LET, I_FWD = 2.22, 2.40, 2.62, 2.665
I_A_OFF, I_A_ROT = V(-0.0540, 0.0236, -0.0560), V(50.0, 8.0, -11.0)
I_B_OFF, I_B_ROT = V(-0.0800, -0.0700, -0.0640), V(10.0, 30.0, 78.0)
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.56, V(-0.0566, 0.0256, -0.0584)),
                     (0.74, I_A_OFF), (1.10, V(-0.0550, 0.0246, -0.0570)), (1.42, V(-0.0572, 0.0268, -0.0598)),
                     (1.52, V(-0.0558, 0.0256, -0.0584)), (1.92, V(-0.0810, -0.0722, -0.0652)), (2.06, I_B_OFF),
                     (I_BACK, V(-0.0812, -0.0692, -0.0622)), (I_LET, V(-0.0816, -0.0688, -0.0618)),
                     (I_FWD, V(-0.0802, -0.0710, -0.0640)), (3.05, V(-0.0795, -0.0730, -0.0612)),
                     (3.62, V(0.0030, -0.0042, 0.0015)), (3.82, V(-0.0006, 0.0008, -0.0003)), (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.0, -0.0014, 0.0004), 6.0, 0.45), (I_BACK, V(0.0, 0.0008, 0.0016), 9.0, 0.4),
                      (I_FWD, V(0.0, -0.0020, -0.0032), 7.5, 0.35), (3.24, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.0010, 0.0009, 0.0007), 21, 0.6), (2.04, 2.28, V(0.0005, 0.0005, 0.0004), 22, 0.9),
                       (2.40, 2.64, V(0.0004, 0.0004, 0.0003), 23, 0.9)], end=4.00)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, 2.2)), (0.56, V(54.0, 9.0, -13.0)), (0.74, I_A_ROT),
                     (1.10, V(48.0, 9.2, -7.5)), (1.42, V(46.5, 10.0, -9.0)), (1.52, V(47.5, 9.0, -13.5)),
                     (1.92, V(10.5, 31.0, 83.0)), (2.06, I_B_ROT), (I_BACK, V(9.0, 31.0, 80.0)),
                     (I_LET, V(9.5, 30.5, 81.5)), (I_FWD, V(10.0, 30.0, 79.0)), (3.05, V(10.5, 29.0, 77.0)),
                     (3.62, V(-0.8, -1.2, -3.0)), (3.82, V(0.2, 0.3, 0.6)), (4.00, V(0, 0, 0))),
               rings=[(2.08, V(0.2, -0.9, 0.7), 6.0, 0.45), (I_BACK, V(0.0, 0.8, 0.8), 9.0, 0.4),
                      (I_FWD, V(0.5, -1.6, -1.4), 7.5, 0.35), (3.24, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.74, 1.46, V(0.6, 0.7, 1.0), 31, 0.6), (2.04, 2.28, V(0.3, 0.3, 0.4), 32, 0.9),
                       (2.40, 2.64, V(0.3, 0.3, 0.3), 33, 0.9)], end=4.00)
# the port-up pose comes lower and flatter than the AK's (the carbine's in-line stock and the charging handle at the
# rear must stay in view while the hand works it)
I_BW = Curve((0.0, 0.0), (1.52, 0.0), (1.92, 1.0), (3.05, 1.0), (3.62, 0.0), (4.0, 0.0))
I_OFF, I_ROT = Lifted(I_OFF, I_BW, V(-0.020, 0.085, 0.045)), Lifted(I_ROT, I_BW, V(18.0, -32.0, -8.0))
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.56, V(0.5, -0.4, 1.2)), (1.42, V(0.3, -0.2, 0.7)),
                     (1.92, V(-0.4, 0.5, -1.8)), (I_BACK, V(-0.2, 0.3, -1.3)), (I_LET, V(-0.3, 0.3, -1.5)),
                     (3.10, V(-0.2, 0.2, -1.0)), (3.66, V(0.2, -0.1, 0.5)), (4.00, V(0, 0, 0))),
               rings=[(I_FWD, V(0.9, -0.2, -0.8), 7.0, 0.35)], end=4.00)
I_CH = Curve((0.0, 0.0), (I_GRAB + 0.02, 0.0, "stop"), (2.30, 0.010), (I_BACK, mm.CH_TRAVEL, ("hit", 1.3)),
             (I_LET, mm.CH_TRAVEL - 0.0008, "stop"), (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0008, "stop"),
             (I_FWD + 0.020, 0.0, "stop"), (4.0, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (2.04, {"support": 1}), (I_GRAB, {"charging": 1}), (I_LET, {"charging": 1}),
                 (2.86, {"world": 1}), (3.22, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (2.04, V(0, 0, 0)), (2.13, V(-0.016, 0.026, 0.020), "flow"), (I_GRAB, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.90, V(0.004, -0.018, 0.026), "flow"), (3.22, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (2.04, 0.0), (2.11, 1.0), (2.15, 1.0), (2.24, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (3.02, 1.0), (3.20, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(0.0, 0.12, -0.12)), (1.48, V(0.0, 0.12, -0.12)),
               (1.92, V(0.013, -0.065, 0.014)), (3.08, V(0.013, -0.065, 0.014)), (3.62, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.56, V(-0.020, 0.010, -0.050)), (1.48, V(-0.020, 0.010, -0.050)),
               (1.92, V(0.010, 0.000, -0.070)), (3.08, V(0.010, 0.000, -0.070)), (3.62, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.56, V(1.0, 0.0, 0.3)), (1.48, V(1.0, 0.0, 0.3)),
                 (1.92, V(0.39, 0.18, 0.90)), (3.08, V(0.39, 0.18, 0.90)), (3.62, rig.POLE_R))
I_REGRIP = Curve((3.16, 0.0), (3.24, 1.0), (3.36, 0.0))


def let_go(p):
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.012, 0.030, 0.030)), q, hands.blend({"spread": 1.0})


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    p.ch_z = float(I_CH(t))
    p.bolt_z = p.ch_z
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_FINGERS(t)))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.8, 2.0, t)
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    p.hand_l = (pos, q, blend_curls(curls, hands.POSES["m4_clamp"], 0.7 * float(I_REGRIP(t))))
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.7, 4.0, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.10, "inspect_move"), (1.52, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (3.05, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))

# --------------------------------------------------------------------------------------------- locomotion
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
              V(0.85 * np.sin(w * (t - 0.04)), 0.15 + 0.70 * bl, 1.40 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.185, -0.150, -0.310)                  # running carry: lowered, muzzle down-left (absolute); flatter
LOW_Q = gun_frame((-0.35, -0.42, -0.84), (-0.25, 0.86, -0.43))   # than the AK's so the in-line stock stays low
LOW_SHOULDER = V(0.080, 0.160, -0.100)
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
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.2 * bl + 3.2 * swing, 3.6 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.180, -0.145, -0.290)                  # tactical sprint: muzzle up before the right shoulder
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
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
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)

# section 31.6: the hand-keyed detail layer (overlap, settles, anticipation, hand adjustments, re-grips)
DRAW, RELOAD, RELOAD_EMPTY, INSPECT = (motion_layer.layered(c, rig, seed) for c, seed in
                                       ((DRAW, 201), (RELOAD, 202), (RELOAD_EMPTY, 203), (INSPECT, 204)))

def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
