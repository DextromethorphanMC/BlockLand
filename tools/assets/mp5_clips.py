"""MP5A3 clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> mp5_rig.Pose; gen_mp5 bakes them (IK included) at >= 60
samples/s (fire at 120).

Authored to section 31 like the AK's (ak_clips.py: Curve bases, rings for every push, drift in holds), lighter: the
MP5 is a 2.5 kg SMG, so moves are quicker and settle sooner. The gun is closed-bolt and roller-delayed: firing cycles
only the bolt carrier (seen through the ejection port), the cocking handle stays put, and nothing holds the bolt open
on empty. The magazine change is the MP5's: thumb on the paddle behind the well, the magazine stripped straight down
and tossed, the new one lined up under the well and pushed home (a pull to check it). The empty reload is the "HK
slap": the support hand unfolds the cocking handle, hauls it back and flicks it up into the locking notch (the bolt
stays back), changes the magazine, then slaps the handle down out of the notch -- the bolt slams home, the gun jolts."""
import functools

import numpy as np

import ads_fire
import armrig
import hands
import motion_layer
import mp5_model as mm
import mp5_rig as rig
from ak_clips import FOOT_WALK, Layers, blend_curls, fade_end, foot, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
TRAVEL = mm.BOLT_TRAVEL
CTRAVEL = mm.CARRIER_TRAVEL
GRAVITY = V(0.0, -9.81, 0.0)                        # viewmodel space, level view
CLEAR = 0.0400                                      # magazine drop that clears the well (its top out of the mouth)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def mag_at(offset=(0, 0, 0), euler=(0, 0, 0)):
    """Magazine pivot offset (gun space) and turned (yaw, pitch, roll) about its top centre."""
    return MAG_REST + np.asarray(offset, float), quat_euler(*euler)


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.1 s, whipped up with the muzzle leading (the turn overshoots at 0.28, the position at
# 0.34), the support hand slaps onto the handguard at 0.38 and the gun takes the catch; settled by 0.62
T_CATCH = 0.38
DRAW_OFF = Layers(Curve((0.00, V(0.048, -0.262, 0.085)), (0.06, V(0.051, -0.274, 0.090)),
                        (0.34, V(-0.006, 0.009, -0.006), "flow"), (0.47, V(0.0012, -0.0022, 0.0010)),
                        (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.0, -0.0015, 0.0006), 8.5, 0.4)], end=0.62)
DRAW_ROT = Layers(Curve((0.00, V(-15, -46, -30)), (0.06, V(-16, -49, -32)), (0.28, V(2.4, 4.4, 3.4), "flow"),
                        (0.42, V(-0.6, -1.0, -0.8)), (0.54, V(0.15, 0.2, 0.1)), (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.2, -0.9, 0.6), 8.5, 0.4)], end=0.62)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.07, V(0.25, 0, 0.15)), (0.29, V(-0.8, 0.1, -0.6)),
                        (0.44, V(0.3, 0, 0.25)), (0.62, V(0, 0, 0))),
                  rings=[(T_CATCH, V(-0.35, 0.0, 0.25), 7.5, 0.4)], end=0.62)
DRAW_LW = Curve((0.0, V(-0.012, -0.046, 0.034)), (0.26, V(-0.010, -0.032, 0.022)), (T_CATCH, V(0, 0.003, 0)),
                (0.45, V(0, -0.0008, 0)), (0.52, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.29, {"spread": 1.0}), (0.39, {"mp5_clamp": 1.0}),
                  (0.50, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.26, {"grip": 1.0}), (0.44, {"mp5_indexed": 1.0}))


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
# 800 rpm (0.075 s): the trigger breaks, the roller-delayed carrier is thrown back (a short delay while the rollers
# unlock, then fast), hits the buffer at 0.020, the spring drives it home: a hit at 0.051 with a 1.2 mm bounce. The
# cocking handle does not move (non-reciprocating). The clip's own kick is the hands' reaction; the support fingers
# clamp a frame after the shot. Starts and ends on the same trigger / finger state so automatic fire chains.
F_END = 0.075
F_CARRIER = Curve((0.0, 0.0), (0.0025, 0.0, ("snap", 1.6)), (0.0045, 0.0030), (0.020, CTRAVEL, ("hit", 1.0)),
                  (0.0255, CTRAVEL - 0.0008), (0.051, 0.0, ("hit", 2.2)), (0.0565, 0.0012, "stop"), (0.062, 0.0, "stop"),
                  (F_END, 0.0))
F_TRIG = Curve((0.0, -5.0), (0.004, -rig.TRIGGER_PULL, ("hit", 1.6)), (0.046, -rig.TRIGGER_PULL), (0.069, -5.0),
               (F_END, -5.0))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.026)
    off = ring(t, 0.001, V(0.0003, 0.0013, 0.0046), 12.0, 0.5) + ring(t, 0.051, V(0.0, -0.0003, -0.0009), 15.0, 0.5)
    rot = ring(t, 0.001, V(0.2, 1.3, -0.40), 11.0, 0.5) + ring(t, 0.051, V(0.0, -0.3, 0.08), 15.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_CARRIER.times() + F_TRIG.times() + [0.010, 0.015, 0.035]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.carrier_z = float(F_CARRIER(t))
    p.trigger = float(F_TRIG(t))
    k = -float(F_TRIG(t)) / rig.TRIGGER_PULL                     # the finger pad rides the trigger back
    p.fingers_r = hands.blend({"mp5_indexed": 1.0 - k, "mp5_trigger": k})
    clamp = -5.0 * float(ring(t, 0.010, 1.0, 9.5, 0.6)) * fade_end(t, F_END, 0.026)
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, 0.002, V(0.28, 0.04, 0.08), 9.5, 0.5) * fade_end(t, F_END, 0.03)))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)   # no hold-open on the MP5
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# One magazine change, laid out from its own start time `s` (the tactical reload: 0; the empty one: after the handle is
# locked back). Beats from s: the thumb finds the paddle (+0.24) and presses it (mag_release +0.30), the hand strips the
# magazine straight down (mag_out +0.42, it clears the well) and tosses it (+0.50: it falls, tumbling), dives off
# screen for the new one (shown +0.86), brings it up on an arc, lines it up under the well (+1.18 .. +1.30: slow), and
# pushes it home (mag_in +1.40: the gun and the camera jolt), then a short pull to check it (+1.50).
SWAP = {"press": 0.30, "out": 0.42, "drop": 0.50, "hide": 0.74, "show": 0.86, "align": 1.18, "enter": 1.30,
        "seat": 1.40, "tug": 1.50}
AWAY = V(-0.080, -0.320, 0.100)                       # off-screen spot (gun space) where the new magazine starts
TOSS = V(-0.25, -0.30, 0.10)                          # the hand's flick on top of the magazine's own velocity
TUMBLE = V(2.6, 1.4, -3.0)                            # rad/s about the world axes


class Swap:
    """Magazine and support hand through one magazine change starting at `s`, inside the clip `clip`; `tug`: a short
    pull to check the magazine after the seat (the tactical reload) -- `end` is when the hand is free again."""

    def __init__(self, s, clip, tug=True):
        self.s, self.clip = s, clip
        self.k = {name: s + dt for name, dt in SWAP.items()}
        k = self.k
        self.end = k["tug"] + 0.06 if tug else k["seat"] + 0.07
        tug_keys = ((k["tug"], V(0, -0.0010, 0), "stop"), (k["tug"] + 0.06, V(0, 0, 0))) if tug else ()
        self.out_off = Curve((k["press"], V(0, 0, 0)), (k["press"] + 0.03, V(0, 0.0006, 0), "stop"),
                             (k["press"] + 0.07, V(0.0, -0.006, 0.0)), (k["out"], V(-0.001, -CLEAR, 0.002)),
                             (k["drop"], V(-0.006, -0.068, 0.006), ("hit", 1.2)))
        self.out_rot = Curve((k["press"], V(0, 0, 0)), (k["press"] + 0.07, V(0.5, -0.8, 1.0)), (k["out"], V(2, -3, 5)),
                             (k["drop"], V(4, -6, 12), ("hit", 1.2)))
        self.in_off = Curve((k["show"], AWAY), (k["show"] + 0.18, V(-0.030, -0.130, 0.035), "flow"),
                            (k["align"], V(-0.0020, -CLEAR - 0.0060, -0.0010)),
                            (k["enter"], V(0.0, -CLEAR + 0.0040, 0.0), "flow"), (k["seat"] - 0.03, V(0, -0.0060, 0)),
                            (k["seat"], V(0.0, 0.0012, 0.0), ("hit", 2.2)), (k["seat"] + 0.035, V(0, -0.0004, 0), "stop"),
                            (k["seat"] + 0.07, V(0, 0, 0)), *tug_keys)
        self.in_rot = Curve((k["show"], V(12, -22, 30)), (k["show"] + 0.18, V(6, -10, 14)), (k["align"], V(0.6, -1.2, 2.5)),
                            (k["enter"], V(0, 0, 0.4)), (k["seat"] - 0.03, V(0, 0, 0)))

    def held_out(self, t):
        return mag_at(self.out_off(t), self.out_rot(t))

    def mag(self, p, t):
        k = self.k
        if t < k["press"]:
            return
        if t < k["drop"]:
            p.mag_t, p.mag_q = self.held_out(t)
        elif t < k["show"]:
            p0, v0, q0 = _drop_state(self.clip)
            tau = t - k["drop"]
            pw = p0 + v0 * tau + 0.5 * GRAVITY * tau * tau
            ang = np.linalg.norm(TUMBLE) * tau
            qw = quat_mul(quat_axis_angle(TUMBLE, np.degrees(ang)), q0) if ang > 1e-9 else q0
            inv = quat_conj(p.gun_q)
            p.mag_t = quat_rotate(inv, pw - p.gun_t)
            p.mag_q = quat_mul(inv, qw)
            if t >= k["hide"]:
                p.mag_s = 0.0
        elif t < self.end:
            p.mag_t, p.mag_q = mag_at(self.in_off(t), self.in_rot(t))

    def paddle(self, t):
        k = self.k
        return float(Curve((k["press"] - 0.03, 0.0), (k["press"], rig.PADDLE_PUSH, ("hit", 1.6)),
                           (k["out"] - 0.04, rig.PADDLE_PUSH), (k["out"] + 0.03, 0.0, ("hit", 1.4)),
                           (k["out"] + 0.05, 0.0))(t))

    # the support hand ------------------------------------------------------------------------------
    def hand(self, p, t, start_from, t_leave):
        """Support hand from `start_from` (an anchor name, left at t_leave) to the seat; returns False once it is done
        (self.end)."""
        k = self.k
        if t < k["drop"]:
            w = Weights((self.s, {start_from: 1}), (t_leave, {start_from: 1}), (k["press"] - 0.07, {"mag_release": 1}),
                        (k["press"] + 0.02, {"mag_release": 1}), (k["press"] + 0.09, {"mag": 1}))
            off = Curve((self.s, V(0, 0, 0)), (t_leave, V(0, 0, 0)),
                        (0.5 * (t_leave + k["press"] - 0.07), V(-0.020, -0.030, 0.016), "flow"),
                        (k["press"] - 0.07, V(0, 0, 0)))
            left_hand(p, w, t, off(t))
            pos, q, curls = p.hand_l
            opened = float(Curve((self.s, 0.0), (t_leave, 0.0), (t_leave + 0.07, 1.0), (k["press"] - 0.10, 0.7),
                                 (k["press"] - 0.06, 0.0))(t))
            press = float(Curve((self.s, 0.0), (k["press"] - 0.04, 0.0), (k["press"], -18.0, ("hit", 1.6)),
                                (k["press"] + 0.06, -6.0), (k["out"], 0.0))(t))
            curls = hands.adjust(open_fingers(curls, opened), thumb=(press, 0.0))
            p.hand_l = (pos, q, curls)
        elif t < k["show"]:
            rel = _release_hand(self.clip)
            new = _new_mag_anchor(p, self)
            w = float(Curve((k["drop"], 0.0), (k["show"] - 0.04, 1.0), (k["show"], 1.0))(t))
            pos, q, _ = armrig.blend_targets([(1 - w, rel), (w, new)])
            pos = pos + arc(min(1.0, (t - k["drop"]) / (k["show"] - 0.04 - k["drop"])), V(-0.030, -0.060, 0.030))
            open_w = smoothstep(k["drop"], k["drop"] + 0.05, t) * (1 - smoothstep(k["show"] - 0.08, k["show"] - 0.02, t))
            curls = blend_curls(blend_curls(rel[2], new[2], w), hands.POSES["spread"], open_w)
            p.hand_l = (pos, q, curls)
        elif t <= self.end:
            left_hand(p, Weights((k["show"], {"mag_low": 1})), t)
            pos, q, curls = p.hand_l
            push = float(Curve((k["enter"], 0.0), (k["seat"], 1.0, ("hit", 1.6)), (k["seat"] + 0.08, 0.2))(t))
            p.hand_l = (pos, q, hands.adjust(curls, index=(-6 * push, 0), middle=(-6 * push, 0), ring=(-5 * push, 0)))
        else:
            return False
        return True


@functools.lru_cache(maxsize=None)
def _drop_state(clip):
    """World position, velocity and orientation of the magazine as the hand lets go (per reload clip)."""
    off, rot, swap = RELOADS[clip]
    k = swap.k

    def at(t):
        p = rig.Pose()
        apply_gun(p, off(t), rot(t))
        mt, mq = swap.held_out(t)
        return p.gun_point(mt), quat_mul(p.gun_q, mq)
    p0, q0 = at(k["drop"])
    p1, _ = at(k["drop"] - 0.012)
    return p0, (p0 - p1) / 0.012 * 0.6 + TOSS, q0


@functools.lru_cache(maxsize=None)
def _release_hand(clip):
    """The support hand as it lets go of the old magazine (world target), per reload clip."""
    off, rot, swap = RELOADS[clip]
    t = swap.k["drop"]
    p = rig.Pose()
    apply_gun(p, off(t), rot(t))
    p.mag_t, p.mag_q = swap.held_out(t)
    left_hand(p, Weights((t - 1.0, {"mag": 1})), t)
    return p.hand_l


def _new_mag_anchor(p, swap):
    """The mag_low hold on the new magazine where it waits off screen (gun pose of this frame)."""
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    t = swap.k["show"]
    q.mag_t, q.mag_q = mag_at(swap.in_off(t), swap.in_rot(t))
    return q.anchor("mag_low")


# ---------------------------------------------------------------------------------------- reload
# let go 0.06 -> the gun dips and rolls left (anticipation) -> rolls right into the presentation (overshoot 0.34,
# settle 0.48) -> the magazine change (above) -> after the tug the hand swings back to the handguard (1.86) with a
# squeeze; the gun swings back to ready past the rest pose and settles.
RS = Swap(0.0, "reload")
RK = RS.k
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0022, -0.0040, 0.0014)), (0.32, V(-0.0360, 0.0300, -0.0040)),
                     (0.48, V(-0.0345, 0.0282, -0.0026)), (0.86, V(-0.0352, 0.0292, -0.0034)),
                     (RK["align"], V(-0.0348, 0.0306, -0.0050)), (RK["seat"], V(-0.0338, 0.0280, -0.0020)),
                     (RK["tug"] + 0.06, V(-0.0346, 0.0300, -0.0040)), (1.86, V(0.0028, -0.0040, 0.0012), "flow"),
                     (2.04, V(-0.0007, 0.0009, -0.0003)), (2.20, V(0, 0, 0))),
               rings=[(RK["press"], V(0.0, 0.0012, 0.0), 10.0, 0.4), (RK["out"], V(0.0, -0.0022, -0.0012), 7.5, 0.4),
                      (RK["drop"] + 0.02, V(0.0, 0.0018, 0.0), 6.0, 0.5),
                      (RK["seat"], V(0.0, 0.0040, 0.0018), 8.0, 0.35), (RK["tug"], V(0.0, -0.0012, 0.0), 9.0, 0.4),
                      (1.84, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(0.48, 1.16, V(0.0008, 0.0008, 0.0006), 11, 0.7)], end=2.20)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.2)), (0.34, V(4.0, 8.4, -25.0)),
                     (0.48, V(3.6, 7.6, -22.4)), (0.86, V(3.9, 8.0, -23.2)), (RK["align"], V(3.4, 9.0, -21.6)),
                     (RK["seat"], V(3.4, 7.0, -20.0)), (RK["tug"] + 0.06, V(3.2, 7.6, -22.0)),
                     (1.88, V(-0.6, -1.0, 2.8), "flow"), (2.06, V(0.2, 0.3, -0.6)), (2.20, V(0, 0, 0))),
               rings=[(RK["press"], V(0.0, 0.8, 0.5), 10.0, 0.4), (RK["out"], V(0.0, -1.4, -0.8), 7.5, 0.4),
                      (RK["drop"] + 0.02, V(0.2, 0.9, 0.4), 6.0, 0.5),
                      (RK["seat"], V(-0.4, 2.8, 1.6), 8.0, 0.35), (RK["tug"], V(0.0, -0.8, 0.0), 9.0, 0.4),
                      (1.84, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.48, 1.16, V(0.4, 0.5, 0.6), 12, 0.7)], end=2.20)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.38, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (RK["align"], V(-0.5, 0.2, -1.2)), (RK["seat"], V(-0.4, 0.1, -1.0)),
                     (1.62, V(-0.5, 0.1, -1.3)), (1.92, V(0.3, -0.1, 0.7)), (2.20, V(0, 0, 0))),
               rings=[(RK["press"], V(0.25, 0.0, 0.3), 9.0, 0.4), (RK["out"], V(-0.35, 0.0, -0.3), 8.0, 0.4),
                      (RK["seat"], V(-1.2, 0.15, 0.9), 7.0, 0.35)], end=2.20)
# the magazine change is presented higher and turned further than the base curves: the well and both magazines in view
LIFT_OFF, LIFT_ROT = V(-0.012, 0.034, 0.014), V(1.0, 5.0, -7.0)
R_LIFT = Curve((0.06, 0.0), (0.36, 1.0), (RK["tug"] + 0.06, 1.0), (1.84, 0.0))


def r_off(t):
    return R_OFF(t) + float(R_LIFT(t)) * LIFT_OFF


def r_rot(t):
    return R_ROT(t) + float(R_LIFT(t)) * LIFT_ROT


R_BACK = Weights((RK["tug"] + 0.06, {"mag_low": 1}), (1.86, {"support": 1}))
R_BACK_OFF = Curve((RK["tug"] + 0.06, V(0, 0, 0)), (1.70, V(-0.018, -0.030, 0.014), "flow"), (1.86, V(0, 0, 0)))
R_BACK_OPEN = Curve((RK["tug"] + 0.06, 0.0), (1.64, 1.0), (1.76, 0.8), (1.86, 0.0))
L_REGRIP = Curve((1.82, 0.0), (1.90, 1.0), (2.02, 0.0))                    # squeeze as it takes the handguard
SH_MAG = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.30, V(-0.100, -0.020, 0.095)),
               (RK["tug"] + 0.06, V(-0.100, -0.020, 0.095)), (1.94, V(0, 0, 0)))


def reload_pose(t):
    p = rig.Pose()
    apply_gun(p, r_off(t), r_rot(t))
    p.shoulder_l = SH_MAG(t)
    RS.mag(p, t)
    p.paddle = RS.paddle(t)
    if not RS.hand(p, t, "support", 0.06):
        left_hand(p, R_BACK, t, R_BACK_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(R_BACK_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["mp5_clamp"], float(L_REGRIP(t)) * 0.8)
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.8, 2.1)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", 2.20, reload_pose,
              events=[(RK["press"], "mag_release"), (RK["out"], "mag_out"), (RK["seat"], "mag_in")],
              extra_times=tuple(RK.values()))

# ---------------------------------------------------------------------------------- reload_empty
# the HK slap. 0.05: the hand leaves the handguard, the gun tips its left side up; 0.24 fingers on the folded handle,
# unfold it (0.30), haul it back against the spring (bolt_back 0.44: the carrier with it), flick it up into the notch
# (0.50: it stays back); the gun rolls right for the magazine change (from 0.52, mag_in 1.82, no tug); the hand flies
# up over the handle, winds up (2.00) and slaps it down out of the notch (bolt_forward 2.08): the bolt slams home, the
# lever folds back on its spring, the gun is shoved and nods, the camera jolts; the hand bounces off and swings back to
# the handguard (2.38) while the gun returns to ready.
T_REACH, T_UNFOLD, T_BACK, T_LOCK, T_LEAVE = 0.24, 0.30, 0.44, 0.50, 0.52
ES = Swap(0.42, "reload_empty", tug=False)
EK = ES.k
T_UP, T_SLAP = 2.00, 2.08
E_BOLT = Curve((0.0, 0.0), (T_UNFOLD, 0.0, "stop"), (T_UNFOLD + 0.04, 0.010), (T_BACK, TRAVEL, ("hit", 1.4)),
               (T_BACK + 0.03, TRAVEL - 0.0010, "stop"), (T_LOCK, TRAVEL - 0.0006),
               (T_SLAP + 0.008, TRAVEL - 0.0010, "stop"), (T_SLAP + 0.040, 0.0, ("hit", 2.4)),
               (T_SLAP + 0.050, 0.0014, "stop"), (T_SLAP + 0.062, 0.0, "stop"), (2.70, 0.0))
E_LOCK = Curve((0.0, 0.0), (T_BACK + 0.02, 0.0, "stop"), (T_LOCK, rig.LOCK, ("hit", 1.6)), (T_LOCK + 0.03, rig.LOCK + 1.5),
               (T_LOCK + 0.06, rig.LOCK), (T_SLAP - 0.002, rig.LOCK, "stop"), (T_SLAP + 0.010, 0.0, ("hit", 2.0)),
               (T_SLAP + 0.025, -1.0, "stop"), (T_SLAP + 0.045, 0.0))
E_FOLD = Curve((0.0, 0.0), (T_REACH + 0.01, 0.0, "stop"), (T_UNFOLD, rig.UNFOLD, ("hit", 1.4)),
               (T_SLAP + 0.010, rig.UNFOLD, "stop"), (T_SLAP + 0.090, -2.0, ("hit", 1.8)), (T_SLAP + 0.120, 3.0, "stop"),
               (T_SLAP + 0.160, 0.0))
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.07, V(0.0018, -0.0034, 0.0012)), (0.26, V(-0.0160, -0.0380, 0.0600)),
                     (T_BACK, V(-0.0154, -0.0390, 0.0630)), (T_LOCK, V(-0.0166, -0.0376, 0.0612)),
                     (0.78, V(-0.0352, 0.0292, -0.0040), "flow"), (EK["show"], V(-0.0350, 0.0292, -0.0036)),
                     (EK["align"], V(-0.0348, 0.0306, -0.0050)), (EK["seat"], V(-0.0338, 0.0280, -0.0020)),
                     (ES.end, V(-0.0260, 0.0000, 0.0300)), (T_UP, V(-0.0160, -0.0440, 0.0650)),
                     (T_SLAP, V(-0.0156, -0.0470, 0.0660)), (2.20, V(-0.0150, -0.0450, 0.0630)),
                     (2.44, V(0.0026, -0.0038, 0.0012), "flow"), (2.58, V(-0.0006, 0.0008, -0.0002)), (2.70, V(0, 0, 0))),
               rings=[(T_BACK, V(0.0, 0.0010, 0.0030), 9.0, 0.4), (T_LOCK, V(0.0, 0.0012, 0.0), 10.0, 0.4),
                      (EK["press"], V(0.0, 0.0012, 0.0), 10.0, 0.4), (EK["out"], V(0.0, -0.0022, -0.0012), 7.5, 0.4),
                      (EK["drop"] + 0.02, V(0.0, 0.0018, 0.0), 6.0, 0.5), (EK["seat"], V(0.0, 0.0040, 0.0018), 8.0, 0.35),
                      (T_SLAP, V(0.0, -0.0050, -0.0040), 7.5, 0.32), (T_SLAP + 0.040, V(0.0, -0.0020, -0.0030), 9.0, 0.35),
                      (2.40, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(EK["drop"], EK["align"], V(0.0008, 0.0008, 0.0006), 13, 0.7)], end=2.70)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.07, V(0.6, -0.8, 2.0)), (0.26, V(-4.0, 3.0, -13.0)),
                     (T_BACK, V(-3.4, 2.4, -12.0)), (T_LOCK, V(-4.2, 3.2, -14.0)), (0.80, V(4.0, 8.4, -25.0), "flow"),
                     (EK["show"], V(3.8, 7.8, -23.0)), (EK["align"], V(3.4, 9.0, -21.6)), (EK["seat"], V(3.4, 7.0, -20.0)),
                     (ES.end, V(-1.0, 6.0, -22.0)), (T_UP, V(-4.0, 3.0, -13.0)), (T_SLAP, V(-3.6, 2.0, -12.0)),
                     (2.20, V(-3.0, 2.4, -11.5)), (2.46, V(-0.6, -1.0, 2.6), "flow"), (2.60, V(0.2, 0.3, -0.5)),
                     (2.70, V(0, 0, 0))),
               rings=[(T_BACK, V(0.0, 0.8, 1.4), 9.0, 0.4), (T_LOCK, V(0.0, -0.6, -1.0), 10.0, 0.4),
                      (EK["press"], V(0.0, 0.8, 0.5), 10.0, 0.4), (EK["out"], V(0.0, -1.4, -0.8), 7.5, 0.4),
                      (EK["drop"] + 0.02, V(0.2, 0.9, 0.4), 6.0, 0.5), (EK["seat"], V(-0.4, 2.8, 1.6), 8.0, 0.35),
                      (T_SLAP, V(0.6, -3.4, -2.6), 7.5, 0.32), (T_SLAP + 0.040, V(0.2, -1.6, -1.0), 9.0, 0.35),
                      (2.40, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(EK["drop"], EK["align"], V(0.4, 0.5, 0.6), 14, 0.7)], end=2.70)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.08, V(0.2, 0, 0.3)), (0.30, V(-0.4, 0.3, -0.9)), (T_LOCK, V(-0.3, 0.3, -1.0)),
                     (0.84, V(-0.7, 0.2, -1.6)), (EK["align"], V(-0.5, 0.2, -1.2)), (EK["seat"], V(-0.4, 0.1, -1.0)),
                     (T_UP, V(-0.3, 0.3, -1.1)), (2.24, V(-0.4, 0.2, -0.9)), (2.48, V(0.3, -0.1, 0.6)),
                     (2.70, V(0, 0, 0))),
               rings=[(T_BACK, V(0.35, 0.05, -0.4), 9.0, 0.4), (EK["press"], V(0.25, 0.0, 0.3), 9.0, 0.4),
                      (EK["out"], V(-0.35, 0.0, -0.3), 8.0, 0.4), (EK["seat"], V(-1.2, 0.15, 0.9), 7.0, 0.35),
                      (T_SLAP, V(-1.6, -0.3, 1.5), 7.0, 0.32)], end=2.70)
E_LIFT = Curve((T_LOCK + 0.04, 0.0), (0.80, 1.0), (ES.end, 1.0), (T_UP - 0.02, 0.0))


def e_off(t):
    return E_OFF(t) + float(E_LIFT(t)) * LIFT_OFF


def e_rot(t):
    return E_ROT(t) + float(E_LIFT(t)) * LIFT_ROT


E_LEFT_A = Weights((0.00, {"support": 1}), (0.05, {"support": 1}), (T_REACH, {"handle": 1}))
E_LEFT_A_OFF = Curve((0.00, V(0, 0, 0)), (0.05, V(0, 0, 0)), (0.15, V(-0.016, 0.026, 0.020), "flow"),
                     (T_REACH, V(0, 0, 0)))
E_OPEN_A = Curve((0.0, 0.0), (0.05, 0.0), (0.12, 1.0), (T_REACH - 0.04, 0.7), (T_REACH, 0.0))
E_SLAP_OFF = Curve((ES.end, V(0, 0, 0)), (1.93, V(-0.020, 0.016, 0.020), "flow"),
                   (T_UP, V(-0.010, 0.050, 0.010)), (T_SLAP, V(0.0, -0.002, 0.0), ("hit", 2.4)),
                   (T_SLAP + 0.05, V(-0.004, 0.022, 0.006), "stop"), (2.22, V(-0.010, 0.010, 0.020), "flow"),
                   (2.38, V(0, 0, 0)))
E_SLAP_W = Weights((ES.end, {"mag_low": 1}), (1.96, {"world": 1}), (T_SLAP + 0.05, {"world": 1}),
                   (2.38, {"support": 1}))
E_SLAP_OPEN = Curve((ES.end, 0.0), (1.94, 1.0), (T_SLAP + 0.10, 1.0), (2.30, 0.6), (2.38, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.06, V(0, 0, 0)), (T_REACH, V(0.010, 0.000, -0.050)),
                 (T_LOCK, V(0.010, 0.000, -0.030)), (EK["press"] - 0.04, V(-0.100, -0.020, 0.095)),
                 (ES.end, V(-0.100, -0.020, 0.095)), (T_UP, V(0.010, 0.010, -0.030)),
                 (T_SLAP + 0.05, V(0.010, 0.000, -0.030)), (2.46, V(0, 0, 0)))
POLE_UP = V(-1.00, -0.55, -0.10)


def slap_target(p):
    """Where the flat hand meets the locked handle (world target from this frame's gun pose, fixed to the locked state:
    it does not follow the handle once it is knocked out of the notch)."""
    q = rig.Pose()
    q.gun_t, q.gun_q = p.gun_t, p.gun_q
    q.bolt_z, q.lock, q.fold = TRAVEL, rig.LOCK, rig.UNFOLD
    return q.anchor("hk_slap")


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, e_off(t), e_rot(t))
    p.shoulder_l = SH_EMPTY(t)
    p.bolt_z = float(E_BOLT(t))
    p.carrier_z = min(p.bolt_z, CTRAVEL)
    p.lock = float(E_LOCK(t))
    p.fold = float(E_FOLD(t))
    ES.mag(p, t)
    p.paddle = ES.paddle(t)
    w = ease("sine", t, 0.06, T_REACH) * (1 - ease("sine", t, T_LOCK, EK["press"] - 0.04)) \
        + ease("sine", t, ES.end - 0.04, T_UP) * (1 - ease("sine", t, T_SLAP + 0.06, 2.38))
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * POLE_UP
    if t < T_LEAVE:
        left_hand(p, E_LEFT_A, t, E_LEFT_A_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_OPEN_A(t)))
        pull = float(Curve((T_UNFOLD, 0.0), (T_BACK, 1.0), (T_LOCK, 0.6), (T_LEAVE, 0.0))(t))
        p.hand_l = (pos, q, hands.adjust(curls, index=(-8 * pull, 0), middle=(-8 * pull, 0)))
    elif t > ES.end or not ES.hand(p, t, "handle", T_LEAVE):
        left_hand(p, E_SLAP_W, t, E_SLAP_OFF(t), world=slap_target(p))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_SLAP_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["mp5_clamp"], 0.8 * float(L_REGRIP(t - 0.52)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.3, 2.6)), 0))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.70, reload_empty_pose,
                    events=[(T_BACK, "bolt_back"), (EK["press"], "mag_release"), (EK["out"], "mag_out"),
                            (EK["seat"], "mag_in"), (T_SLAP, "bolt_forward")],
                    extra_times=(T_UNFOLD, T_BACK, T_LOCK, T_SLAP, T_SLAP + 0.010, T_SLAP + 0.040) + tuple(EK.values()))
RELOADS = {"reload": (r_off, r_rot, RS), "reload_empty": (e_off, e_rot, ES)}

# --------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a swing round to show the right side, the port and the case deflector (overshoot 0.54,
# settle 0.70) -> moving hold -> a counter-roll (1.50) and the gun rolls over, its left side up (overshoot 1.88) -> the
# support thumb unfolds the cocking handle and draws it back a little (a press check: bolt_back 2.36), lets it go: it
# snaps home (bolt_forward 2.60) -> the hand returns to the handguard, the gun rolls back to ready and settles.
I_BACK, I_LET, I_FWD = 2.36, 2.56, 2.60
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.54, V(-0.0540, 0.0240, -0.0560)),
                     (0.70, V(-0.0520, 0.0222, -0.0540)), (1.10, V(-0.0510, 0.0232, -0.0548)),
                     (1.40, V(-0.0532, 0.0250, -0.0570)), (1.50, V(-0.0520, 0.0240, -0.0560)),
                     (1.88, V(-0.0100, -0.0300, 0.0300)), (2.02, V(-0.0088, -0.0310, 0.0310)),
                     (I_BACK, V(-0.0092, -0.0304, 0.0330)), (I_FWD, V(-0.0085, -0.0316, 0.0310)),
                     (2.96, V(-0.0080, -0.0320, 0.0330)), (3.50, V(0.0028, -0.0040, 0.0014), "flow"),
                     (3.72, V(-0.0006, 0.0008, -0.0003)), (3.90, V(0, 0, 0))),
               rings=[(2.04, V(0.0, -0.0012, 0.0004), 6.5, 0.45), (I_BACK, V(0.0, 0.0008, 0.0014), 9.0, 0.4),
                      (I_FWD, V(0.0, -0.0020, -0.0030), 8.0, 0.35), (3.16, V(0.0, -0.0010, 0.0), 8.0, 0.4)],
               drifts=[(0.70, 1.40, V(0.0010, 0.0009, 0.0007), 21, 0.6), (2.02, 2.28, V(0.0005, 0.0005, 0.0004), 22, 0.9),
                       (2.62, 2.96, V(0.0005, 0.0004, 0.0003), 23, 0.8)], end=3.90)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, 2.2)), (0.54, V(54.0, 8.6, -13.0)),
                     (0.70, V(50.5, 7.8, -11.4)), (1.10, V(48.5, 8.8, -8.0)), (1.40, V(47.5, 9.6, -9.6)),
                     (1.50, V(48.5, 9.0, -13.5)), (1.88, V(-14.0, 5.0, -60.0)), (2.02, V(-13.0, 4.0, -56.0)),
                     (I_BACK, V(-12.4, 4.6, -57.0)), (I_FWD, V(-13.0, 3.8, -56.5)), (2.96, V(-13.5, 3.0, -54.0)),
                     (3.50, V(-0.8, -1.2, -3.0), "flow"), (3.72, V(0.2, 0.3, 0.6)), (3.90, V(0, 0, 0))),
               rings=[(2.04, V(0.2, -0.9, 0.7), 6.5, 0.45), (I_BACK, V(0.0, 0.7, 0.8), 9.0, 0.4),
                      (I_FWD, V(0.5, -1.6, -1.3), 8.0, 0.35), (3.16, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.70, 1.40, V(0.6, 0.7, 1.0), 31, 0.6), (2.02, 2.28, V(0.3, 0.3, 0.4), 32, 0.9),
                       (2.62, 2.96, V(0.3, 0.3, 0.3), 33, 0.8)], end=3.90)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.54, V(0.5, -0.4, 1.2)), (1.40, V(0.3, -0.2, 0.7)),
                     (1.88, V(-0.4, -0.4, 1.6)), (I_BACK, V(-0.2, -0.3, 1.2)), (I_LET, V(-0.3, -0.3, 1.4)),
                     (3.00, V(-0.2, -0.2, 1.0)), (3.54, V(0.2, 0.1, -0.5)), (3.90, V(0, 0, 0))),
               rings=[(I_FWD, V(0.9, -0.2, 0.8), 7.5, 0.35)], end=3.90)
I_BOLT = Curve((0.0, 0.0), (2.20, 0.0, "stop"), (2.28, 0.006), (I_BACK, 0.024, ("hit", 1.3)), (I_LET, 0.0232, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0010, "stop"), (I_FWD + 0.020, 0.0, "stop"), (3.9, 0.0))
I_FOLD = Curve((0.0, 0.0), (2.06, 0.0, "stop"), (2.18, rig.UNFOLD * 0.85, ("hit", 1.3)), (I_LET + 0.01, rig.UNFOLD * 0.85),
               (I_FWD + 0.06, -2.0, ("hit", 1.6)), (I_FWD + 0.09, 2.0, "stop"), (I_FWD + 0.13, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (1.98, {"support": 1}), (2.16, {"handle": 1}), (I_LET, {"handle": 1}),
                 (2.80, {"world": 1}), (3.14, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (1.98, V(0, 0, 0)), (2.07, V(-0.010, 0.020, 0.026), "flow"), (2.16, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.84, V(0.004, -0.018, 0.024), "flow"), (3.14, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (1.98, 0.0), (2.05, 1.0), (2.10, 1.0), (2.17, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (2.96, 1.0), (3.12, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.54, V(0.0, 0.11, -0.11)), (1.46, V(0.0, 0.11, -0.11)),
               (1.88, V(0.000, 0.030, -0.030)), (3.00, V(0.000, 0.030, -0.030)), (3.52, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.54, V(-0.020, 0.010, -0.050)), (1.46, V(-0.020, 0.010, -0.050)),
               (1.88, V(0.010, 0.000, -0.070)), (3.00, V(0.010, 0.000, -0.070)), (3.52, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.54, V(1.0, 0.0, 0.3)), (1.46, V(1.0, 0.0, 0.3)),
                 (1.88, V(0.90, 0.10, 0.45)), (3.00, V(0.90, 0.10, 0.45)), (3.52, rig.POLE_R))


def let_go(p):
    """Off-hand spot just off the released handle (it springs open there)."""
    pos, q, _ = p.anchor("handle")
    return pos + p.gun_dir(V(-0.012, 0.030, 0.030)), q, hands.blend({"spread": 1.0})


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    p.bolt_z = float(I_BOLT(t))
    p.carrier_z = p.bolt_z
    p.fold = float(I_FOLD(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_FINGERS(t)))
    w = 2 * np.pi * t / 1.7
    tap = 1 - smoothstep(1.7, 1.95, t)
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * tap, 0), middle=(-2 * np.sin(w + 0.5) * tap, 0))
    curls = blend_curls(curls, hands.POSES["mp5_clamp"], 0.7 * float(L_REGRIP(t - 1.30)))
    p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3) * (1 - smoothstep(3.6, 3.9, t)), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 3.90, inspect_pose,
               events=[(0.10, "inspect_move"), (1.50, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (2.96, "inspect_move")],
               extra_times=(I_BACK, I_LET, I_FWD, I_FWD + 0.010))


# ------------------------------------------------------------------------------------ locomotion
WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.06, FOOT_WALK)            # the gun's turn follows the body a few hundredths late
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.10))
    apply_gun(p, V(0.0036 * side, 0.0006 + 0.0048 * b, -0.0015 * b - 0.0009 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.04)), 0.15 + 0.72 * bl, 1.40 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.146, -0.108, -0.285)                  # running carry: lowered, muzzle down-left (absolute)
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
    bl = foot(t, half, 0.045)
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))
    p.gun_t = LOW_T + V(0.006 * side, 0.010 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.6 * np.sin(w * (t - 0.03)), 3.4 * bl + 3.4 * swing, 3.8 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"mp5_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.146, -0.146, -0.290)                  # tactical sprint: muzzle up in front of the right shoulder
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.04)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.012 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.2 * np.sin(w * (t - 0.03)), 3.2 * bl, 3.8 * np.cos(w * (t - 0.075))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"mp5_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)

# section 31.6: the hand-keyed detail layer (overlap, settles, anticipation, hand adjustments, re-grips)
DRAW, RELOAD, RELOAD_EMPTY, INSPECT = (motion_layer.layered(c, rig, seed) for c, seed in
                                       ((DRAW, 301), (RELOAD, 302), (RELOAD_EMPTY, 303), (INSPECT, 304)))

def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
