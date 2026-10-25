"""Vector clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 44): idle, draw, fire, fire_last, fire_ads, fire_last_ads,
reload, reload_empty, inspect, walk, sprint, tac_sprint. Each clip maps time -> vector_rig.Pose; gen_vector bakes them
(IK included) at >= 60 samples/s (fire at 120).

Authored to section 31 like the MP5's (mp5_clips.py: Curve bases, rings for every push, drift in holds). The Vector is a
closed-bolt SMG whose bolt drives a block down an inclined track ("Super V"): the recoil goes down and back into the
hand instead of up, so a shot is a short shove straight back with almost no muzzle rise, at 1150 rpm. Firing cycles only
the bolt (seen through the ejection port); the charging handle does not reciprocate. The magazine change: thumb on the
release lever at the front of the guard, the magazine drops free and the hand tosses it, the new one is lined up under
the well and pushed home (a pull to check it). The empty reload changes the magazine first, then the support hand
reaches over the left flank, flips the folding knob out, hauls the handle back and lets it fly (slingshot): the bolt
slams home and the knob folds back on its spring."""
import functools

import numpy as np

import ads_fire
import armrig
import hands
import motion_layer
import vector_model as vm
import vector_rig as rig
from ak_clips import FOOT_WALK, Layers, blend_curls, fade_end, foot, gun_frame, open_fingers, punch_left
from anim import Curve, arc, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
TRAVEL = vm.BOLT_TRAVEL
CTRAVEL = vm.CARRIER_TRAVEL
GRAVITY = V(0.0, -9.81, 0.0)                        # viewmodel space, level view
CLEAR = vm.WELL_DEPTH + 0.0100                      # magazine drop (down its raked axis) that clears the deep well


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def mag_at(offset=(0, 0, 0), euler=(0, 0, 0)):
    """Magazine pivot offset and turn (yaw, pitch, roll) about its top centre, both in the magazine's own frame (raked
    in its well: -Y runs down the magazine), so the clips' drops and seats follow the well."""
    return MAG_REST + quat_rotate(rig.MAG_Q, np.asarray(offset, float)), quat_mul(rig.MAG_Q, quat_euler(*euler))


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["vec_chin"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.1 s, whipped up with the muzzle leading (the turn overshoots at 0.27, the position at
# 0.33), the support hand slaps onto the chin at 0.37 and the gun takes the catch; settled by 0.60
T_CATCH = 0.37
DRAW_OFF = Layers(Curve((0.00, V(0.050, -0.262, 0.085)), (0.06, V(0.053, -0.274, 0.090)),
                        (0.33, V(-0.006, 0.009, -0.006), "flow"), (0.46, V(0.0012, -0.0022, 0.0010)),
                        (0.60, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.0, -0.0016, 0.0006), 8.5, 0.4)], end=0.60)
DRAW_ROT = Layers(Curve((0.00, V(-15, -46, -30)), (0.06, V(-16, -49, -32)), (0.27, V(2.4, 4.4, 3.4), "flow"),
                        (0.41, V(-0.6, -1.0, -0.8)), (0.52, V(0.15, 0.2, 0.1)), (0.60, V(0, 0, 0))),
                  rings=[(T_CATCH, V(0.2, -0.9, 0.6), 8.5, 0.4)], end=0.60)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.07, V(0.25, 0, 0.15)), (0.28, V(-0.8, 0.1, -0.6)),
                        (0.43, V(0.3, 0, 0.25)), (0.60, V(0, 0, 0))),
                  rings=[(T_CATCH, V(-0.35, 0.0, 0.25), 7.5, 0.4)], end=0.60)
DRAW_LW = Curve((0.0, V(-0.012, -0.050, 0.034)), (0.25, V(-0.010, -0.034, 0.022)), (T_CATCH, V(0, 0.003, 0)),
                (0.44, V(0, -0.0008, 0)), (0.51, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.28, {"spread": 1.0}), (0.38, {"vec_clamp": 1.0}),
                  (0.49, {"vec_chin": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.25, {"grip": 1.0}), (0.43, {"vec_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.60, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
# 1150 rpm (0.052 s): the trigger breaks, the bolt is thrown back (its block runs down the inclined track), hits the
# buffer at 0.015, the spring drives it home: a hit at 0.036 with a 1 mm bounce. The charging handle does not move.
# The Super V turns the recoil down into the hand: the clip's kick is a short shove straight back along the bore with a
# trace of muzzle dip and almost no rise (the runtime adds the recoil and camera punch from guns.json). The support
# fingers clamp a frame after the shot. Starts and ends on the same trigger / finger state so automatic fire chains.
F_END = 0.052
F_CARRIER = Curve((0.0, 0.0), (0.0020, 0.0, ("snap", 1.6)), (0.0035, 0.0025), (0.015, CTRAVEL, ("hit", 1.0)),
                  (0.0190, CTRAVEL - 0.0007), (0.036, 0.0, ("hit", 2.2)), (0.0400, 0.0010, "stop"), (0.0440, 0.0, "stop"),
                  (F_END, 0.0))
F_TRIG = Curve((0.0, -5.0), (0.003, -rig.TRIGGER_PULL, ("hit", 1.6)), (0.032, -rig.TRIGGER_PULL), (0.047, -5.0),
               (F_END, -5.0))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.020)
    off = ring(t, 0.001, V(0.0001, 0.0002, 0.0042), 14.0, 0.55) + ring(t, 0.036, V(0.0, -0.0002, -0.0007), 16.0, 0.5)
    rot = ring(t, 0.001, V(0.15, -0.25, -0.25), 13.0, 0.55) + ring(t, 0.036, V(0.0, 0.15, 0.06), 16.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_CARRIER.times() + F_TRIG.times() + [0.008, 0.012, 0.025]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.carrier_z = float(F_CARRIER(t))
    p.trigger = float(F_TRIG(t))
    k = -float(F_TRIG(t)) / rig.TRIGGER_PULL                     # the finger pad rides the trigger back
    p.fingers_r = hands.blend({"vec_indexed": 1.0 - k, "vec_trigger": k})
    clamp = -5.0 * float(ring(t, 0.008, 1.0, 10.0, 0.6)) * fade_end(t, F_END, 0.020)
    p.fingers_l = hands.adjust(hands.POSES["vec_chin"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, 0.002, V(0.16, 0.03, 0.05), 10.0, 0.5) * fade_end(t, F_END, 0.024)))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)   # no hold-open here
# aimed (section 31.5): the same shot with the sight picture held (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------- magazine change
# One magazine change, laid out from its own start time `s`. Beats from s: the thumb finds the release lever (+0.24)
# and presses it (mag_release +0.30), the magazine drops free out of the deep well (mag_out +0.42) and the hand flicks
# it away (+0.50: it falls, tumbling), dives off screen for the new one (shown +0.86), brings it up on an arc, lines it
# up under the well (+1.18 .. +1.30: slow), and pushes it home (mag_in +1.40: the gun and the camera jolt), then a
# short pull to check it (+1.50).
SWAP = {"press": 0.30, "out": 0.42, "drop": 0.50, "hide": 0.74, "show": 0.86, "align": 1.18, "enter": 1.30,
        "seat": 1.40, "tug": 1.50}
AWAY = V(-0.080, -0.330, 0.100)                       # off-screen spot (gun space) where the new magazine starts
TOSS = V(-0.25, -0.30, 0.10)                          # the hand's flick on top of the magazine's own velocity
TUMBLE = V(2.6, 1.4, -3.0)                            # rad/s about the world axes


class Swap:
    """Magazine and support hand through one magazine change starting at `s`, inside the clip `clip`; `tug`: a short
    pull to check the magazine after the seat -- `end` is when the hand is free again."""

    def __init__(self, s, clip, tug=True):
        self.s, self.clip = s, clip
        self.k = {name: s + dt for name, dt in SWAP.items()}
        k = self.k
        self.end = k["tug"] + 0.06 if tug else k["seat"] + 0.07
        tug_keys = ((k["tug"], V(0, -0.0010, 0), "stop"), (k["tug"] + 0.06, V(0, 0, 0))) if tug else ()
        self.out_off = Curve((k["press"], V(0, 0, 0)), (k["press"] + 0.03, V(0, 0.0006, 0), "stop"),
                             (k["press"] + 0.07, V(0.0, -0.008, 0.0)), (k["out"], V(-0.001, -CLEAR, 0.002)),
                             (k["drop"], V(-0.006, -0.090, 0.006), ("hit", 1.2)))
        self.out_rot = Curve((k["press"], V(0, 0, 0)), (k["press"] + 0.07, V(0.5, -0.8, 1.0)), (k["out"], V(2, -3, 5)),
                             (k["drop"], V(4, -6, 12), ("hit", 1.2)))
        self.in_off = Curve((k["show"], AWAY), (k["show"] + 0.18, V(-0.030, -0.150, 0.035), "flow"),
                            (k["align"], V(-0.0020, -CLEAR - 0.0060, -0.0010)),
                            (k["enter"], V(0.0, -CLEAR + 0.0060, 0.0), "flow"), (k["seat"] - 0.03, V(0, -0.0060, 0)),
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
# settle 0.48) -> the magazine change (above) -> after the tug the hand swings back to the chin (1.86) with a squeeze;
# the gun swings back to ready past the rest pose and settles.
RS = Swap(0.0, "reload")
RK = RS.k
R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0022, -0.0040, 0.0014)), (0.32, V(-0.0340, 0.0300, -0.0040)),
                     (0.48, V(-0.0325, 0.0282, -0.0026)), (0.86, V(-0.0332, 0.0292, -0.0034)),
                     (RK["align"], V(-0.0328, 0.0306, -0.0050)), (RK["seat"], V(-0.0318, 0.0280, -0.0020)),
                     (RK["tug"] + 0.06, V(-0.0326, 0.0300, -0.0040)), (1.86, V(0.0028, -0.0040, 0.0012), "flow"),
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
LIFT_OFF, LIFT_ROT = V(-0.012, 0.040, 0.014), V(1.0, 5.0, -7.0)
R_LIFT = Curve((0.06, 0.0), (0.36, 1.0), (RK["tug"] + 0.06, 1.0), (1.84, 0.0))


# the long receiver and the deep well (0.11 m under the bore): while a reload presents the well, the gun is held a little
# further out and higher, so the well, the magazines and the hands come into view instead of the receiver filling it
PRESENT = V(-0.010, 0.012, -0.035)


def _present(off):
    return off + float(np.clip(off[1] / 0.030, 0.0, 1.0)) * PRESENT


def r_off(t):
    return _present(R_OFF(t)) + float(R_LIFT(t)) * LIFT_OFF


def r_rot(t):
    return R_ROT(t) + float(R_LIFT(t)) * LIFT_ROT


R_BACK = Weights((RK["tug"] + 0.06, {"mag_low": 1}), (1.86, {"support": 1}))
R_BACK_OFF = Curve((RK["tug"] + 0.06, V(0, 0, 0)), (1.70, V(-0.018, -0.030, 0.014), "flow"), (1.86, V(0, 0, 0)))
R_BACK_OPEN = Curve((RK["tug"] + 0.06, 0.0), (1.64, 1.0), (1.76, 0.8), (1.86, 0.0))
L_REGRIP = Curve((1.82, 0.0), (1.90, 1.0), (2.02, 0.0))                    # squeeze as it takes the chin
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
        curls = blend_curls(curls, hands.POSES["vec_clamp"], float(L_REGRIP(t)) * 0.8)
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.8, 2.1)), 0))
    cam(p, *R_CAM(t))
    return p


RELOAD = Clip("reload", 2.20, reload_pose,
              events=[(RK["press"], "mag_release"), (RK["out"], "mag_out"), (RK["seat"], "mag_in")],
              extra_times=tuple(RK.values()))

# ---------------------------------------------------------------------------------- reload_empty
# the magazine change first (from 0.0, no tug: mag_in 1.40); then the gun rolls its left flank up and the hand flies
# over to the folded knob (1.62), flips it out (1.68), hauls the handle back against the spring (bolt_back 1.80: the
# bolt with it), lets it fly (1.86): the bolt slams home (bolt_forward 1.88), the gun is shoved and nods, the knob
# folds back on its spring; the hand drops back to the chin (2.18) while the gun returns to ready.
ES = Swap(0.0, "reload_empty", tug=False)
EK = ES.k
T_REACH, T_UNFOLD, T_BACK, T_LET = 1.62, 1.68, 1.80, 1.86
T_HOME = T_LET + 0.022
E_DUR = 2.60
E_BOLT = Curve((0.0, 0.0), (T_UNFOLD, 0.0, "stop"), (T_UNFOLD + 0.04, 0.010), (T_BACK, TRAVEL, ("hit", 1.4)),
               (T_BACK + 0.03, TRAVEL - 0.0010, "stop"), (T_LET, TRAVEL - 0.0008, "stop"), (T_HOME, 0.0, ("hit", 2.6)),
               (T_HOME + 0.010, 0.0014, "stop"), (T_HOME + 0.022, 0.0, "stop"), (E_DUR, 0.0))
E_FOLD = Curve((0.0, 0.0), (T_REACH + 0.01, 0.0, "stop"), (T_UNFOLD, rig.UNFOLD, ("hit", 1.4)),
               (T_LET + 0.010, rig.UNFOLD, "stop"), (T_LET + 0.080, -2.0, ("hit", 1.8)), (T_LET + 0.110, 3.0, "stop"),
               (T_LET + 0.150, 0.0))
E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.0022, -0.0040, 0.0014)), (0.32, V(-0.0340, 0.0300, -0.0040)),
                     (0.48, V(-0.0325, 0.0282, -0.0026)), (0.86, V(-0.0332, 0.0292, -0.0034)),
                     (EK["align"], V(-0.0328, 0.0306, -0.0050)), (EK["seat"], V(-0.0318, 0.0280, -0.0020)),
                     (T_REACH, V(-0.0300, 0.0240, -0.0080)), (T_BACK, V(-0.0290, 0.0250, -0.0060)),
                     (T_LET, V(-0.0300, 0.0244, -0.0074)), (2.04, V(-0.0280, 0.0220, -0.0060)),
                     (2.32, V(0.0026, -0.0038, 0.0012), "flow"),
                     (2.46, V(-0.0006, 0.0008, -0.0002)), (E_DUR, V(0, 0, 0))),
               rings=[(EK["press"], V(0.0, 0.0012, 0.0), 10.0, 0.4), (EK["out"], V(0.0, -0.0022, -0.0012), 7.5, 0.4),
                      (EK["drop"] + 0.02, V(0.0, 0.0018, 0.0), 6.0, 0.5), (EK["seat"], V(0.0, 0.0040, 0.0018), 8.0, 0.35),
                      (T_BACK, V(0.0, 0.0010, 0.0030), 9.0, 0.4),
                      (T_HOME, V(0.0, -0.0040, -0.0040), 7.5, 0.32), (2.26, V(0.0, -0.0012, 0.0), 8.0, 0.4)],
               drifts=[(EK["drop"], EK["align"], V(0.0008, 0.0008, 0.0006), 13, 0.7)], end=E_DUR)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-0.5, -1.0, 2.2)), (0.34, V(4.0, 8.4, -25.0)),
                     (0.48, V(3.6, 7.6, -22.4)), (0.86, V(3.9, 8.0, -23.2)), (EK["align"], V(3.4, 9.0, -21.6)),
                     (EK["seat"], V(3.4, 7.0, -20.0)), (T_REACH, V(1.0, 10.0, -26.0)), (T_BACK, V(1.6, 9.0, -25.0)),
                     (T_LET, V(1.2, 9.6, -25.6)), (2.04, V(0.8, 8.0, -22.0)), (2.34, V(-0.6, -1.0, 2.6), "flow"),
                     (2.48, V(0.2, 0.3, -0.5)),
                     (E_DUR, V(0, 0, 0))),
               rings=[(EK["press"], V(0.0, 0.8, 0.5), 10.0, 0.4), (EK["out"], V(0.0, -1.4, -0.8), 7.5, 0.4),
                      (EK["drop"] + 0.02, V(0.2, 0.9, 0.4), 6.0, 0.5), (EK["seat"], V(-0.4, 2.8, 1.6), 8.0, 0.35),
                      (T_BACK, V(0.0, 0.8, 1.4), 9.0, 0.4),
                      (T_HOME, V(0.6, -3.0, -2.2), 7.5, 0.32), (2.26, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(EK["drop"], EK["align"], V(0.4, 0.5, 0.6), 14, 0.7)], end=E_DUR)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.2, 0, 0.4)), (0.38, V(-0.7, 0.2, -1.6)),
                     (0.70, V(-0.4, 0.1, -1.0)), (EK["align"], V(-0.5, 0.2, -1.2)), (EK["seat"], V(-0.4, 0.1, -1.0)),
                     (T_REACH, V(-0.4, 0.2, -1.2)), (T_BACK, V(-0.3, 0.2, -1.1)), (2.10, V(-0.4, 0.2, -1.1)),
                     (2.40, V(0.3, -0.1, 0.6)), (E_DUR, V(0, 0, 0))),
               rings=[(EK["press"], V(0.25, 0.0, 0.3), 9.0, 0.4), (EK["out"], V(-0.35, 0.0, -0.3), 8.0, 0.4),
                      (EK["seat"], V(-1.2, 0.15, 0.9), 7.0, 0.35), (T_BACK, V(0.35, 0.05, 0.4), 9.0, 0.4),
                      (T_HOME, V(-1.4, 0.3, -1.2), 7.0, 0.32)], end=E_DUR)
E_LIFT = Curve((0.06, 0.0), (0.36, 1.0), (2.04, 1.0), (2.30, 0.0))


def e_off(t):
    return _present(E_OFF(t)) + float(E_LIFT(t)) * LIFT_OFF


def e_rot(t):
    return E_ROT(t) + float(E_LIFT(t)) * LIFT_ROT


E_TO_KNOB = Weights((ES.end, {"mag_low": 1}), (T_REACH, {"handle": 1}))
E_TO_KNOB_OFF = Curve((ES.end, V(0, 0, 0)), (1.55, V(-0.024, -0.010, 0.014), "flow"), (T_REACH, V(0, 0, 0)))
E_KNOB_OPEN = Curve((ES.end, 0.0), (1.53, 1.0), (T_REACH - 0.03, 0.6), (T_REACH, 0.0))
E_BACK = Weights((T_LET, {"world": 1}), (2.06, {"world": 1}), (2.18, {"support": 1}))
E_BACK_OFF = Curve((T_LET, V(0, 0, 0)), (2.00, V(-0.010, -0.020, 0.020), "flow"), (2.18, V(0, 0, 0)))
E_BACK_OPEN = Curve((T_LET, 0.0), (T_LET + 0.04, 1.0), (2.10, 0.8), (2.18, 0.0))
SH_EMPTY = Curve((0.00, V(0, 0, 0)), (0.08, V(0, 0, 0)), (0.30, V(-0.100, -0.020, 0.095)),
                 (ES.end, V(-0.100, -0.020, 0.095)), (T_REACH, V(-0.090, -0.020, 0.080)), (T_LET, V(-0.090, -0.020, 0.080)),
                 (2.30, V(0, 0, 0)))


def let_go(p):
    """Off-hand spot just off the released knob (the fingers spring open there)."""
    pos, q, _ = p.anchor("handle")
    return pos + p.gun_dir(V(-0.020, 0.010, 0.010)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    apply_gun(p, e_off(t), e_rot(t))
    p.shoulder_l = SH_EMPTY(t)
    p.bolt_z = float(E_BOLT(t))
    p.carrier_z = min(p.bolt_z, CTRAVEL)
    p.fold = float(E_FOLD(t))
    ES.mag(p, t)
    p.paddle = ES.paddle(t)
    if t <= ES.end:
        ES.hand(p, t, "support", 0.06)
    elif t < T_LET:
        left_hand(p, E_TO_KNOB, t, E_TO_KNOB_OFF(t))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_KNOB_OPEN(t)))
        pull = float(Curve((T_UNFOLD, 0.0), (T_BACK, 1.0), (T_LET - 0.02, 0.8), (T_LET, 0.0))(t))
        p.hand_l = (pos, q, hands.adjust(curls, index=(-8 * pull, 0), thumb=(-8 * pull, 0)))
    else:
        # the let-go spot is where the knob was, fully back, as the fingers opened
        q0 = rig.Pose()
        q0.gun_t, q0.gun_q = p.gun_t, p.gun_q
        q0.bolt_z, q0.fold = TRAVEL, rig.UNFOLD
        left_hand(p, E_BACK, t, E_BACK_OFF(t), world=let_go(q0))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_BACK_OPEN(t)))
        curls = blend_curls(curls, hands.POSES["vec_clamp"], 0.8 * float(L_REGRIP(t - 0.32)))
        p.hand_l = (pos, q, curls)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.2, 2.5)), 0))
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", E_DUR, reload_empty_pose,
                    events=[(EK["press"], "mag_release"), (EK["out"], "mag_out"), (EK["seat"], "mag_in"),
                            (T_BACK, "bolt_back"), (T_HOME, "bolt_forward")],
                    extra_times=(T_UNFOLD, T_BACK, T_LET, T_HOME, T_HOME + 0.010) + tuple(EK.values()))
RELOADS = {"reload": (r_off, r_rot, RS), "reload_empty": (e_off, e_rot, ES)}

# --------------------------------------------------------------------------------------- inspect
# anticipation (0.10) -> a swing round to show the right side, the port and the angular lower (overshoot 0.54, settle
# 0.70) -> moving hold -> a counter-roll (1.50) and the gun rolls over, its left side up (overshoot 1.88) -> the support
# hand flips the knob out and draws the handle back a little (a press check: bolt_back 2.36), lets it go: it snaps home
# (bolt_forward 2.60), the knob folds -> the hand returns to the chin, the gun rolls back to ready and settles.
I_BACK, I_LET, I_FWD = 2.36, 2.56, 2.58
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.003, -0.005, 0.002)), (0.54, V(-0.0540, 0.0240, -0.0560)),
                     (0.70, V(-0.0520, 0.0222, -0.0540)), (1.10, V(-0.0510, 0.0232, -0.0548)),
                     (1.40, V(-0.0532, 0.0250, -0.0570)), (1.50, V(-0.0520, 0.0240, -0.0560)),
                     (1.88, V(-0.0460, 0.0560, -0.0120)), (2.02, V(-0.0448, 0.0550, -0.0110)),
                     (I_BACK, V(-0.0452, 0.0556, -0.0090)), (I_FWD, V(-0.0445, 0.0544, -0.0110)),
                     (2.96, V(-0.0440, 0.0540, -0.0090)), (3.50, V(0.0028, -0.0040, 0.0014), "flow"),
                     (3.72, V(-0.0006, 0.0008, -0.0003)), (3.90, V(0, 0, 0))),
               rings=[(2.04, V(0.0, -0.0012, 0.0004), 6.5, 0.45), (I_BACK, V(0.0, 0.0008, 0.0014), 9.0, 0.4),
                      (I_FWD, V(0.0, -0.0020, -0.0030), 8.0, 0.35), (3.16, V(0.0, -0.0010, 0.0), 8.0, 0.4)],
               drifts=[(0.70, 1.40, V(0.0010, 0.0009, 0.0007), 21, 0.6), (2.02, 2.28, V(0.0005, 0.0005, 0.0004), 22, 0.9),
                       (2.62, 2.96, V(0.0005, 0.0004, 0.0003), 23, 0.8)], end=3.90)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(-1.2, -1.4, 2.2)), (0.54, V(54.0, 8.6, -13.0)),
                     (0.70, V(50.5, 7.8, -11.4)), (1.10, V(48.5, 8.8, -8.0)), (1.40, V(47.5, 9.6, -9.6)),
                     (1.50, V(48.5, 9.0, -13.5)), (1.88, V(-8.0, 10.0, -48.0)), (2.02, V(-7.0, 9.0, -45.0)),
                     (I_BACK, V(-6.4, 9.6, -46.0)), (I_FWD, V(-7.0, 8.8, -45.5)), (2.96, V(-7.5, 8.0, -43.0)),
                     (3.50, V(-0.8, -1.2, -3.0), "flow"), (3.72, V(0.2, 0.3, 0.6)), (3.90, V(0, 0, 0))),
               rings=[(2.04, V(0.2, -0.9, 0.7), 6.5, 0.45), (I_BACK, V(0.0, 0.7, 0.8), 9.0, 0.4),
                      (I_FWD, V(0.5, -1.6, -1.3), 8.0, 0.35), (3.16, V(0.0, -0.5, 0.3), 8.0, 0.4)],
               drifts=[(0.70, 1.40, V(0.6, 0.7, 1.0), 31, 0.6), (2.02, 2.28, V(0.3, 0.3, 0.4), 32, 0.9),
                       (2.62, 2.96, V(0.3, 0.3, 0.3), 33, 0.8)], end=3.90)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.54, V(0.5, -0.4, 1.2)), (1.40, V(0.3, -0.2, 0.7)),
                     (1.88, V(-0.4, -0.4, 1.6)), (I_BACK, V(-0.2, -0.3, 1.2)), (I_LET, V(-0.3, -0.3, 1.4)),
                     (3.00, V(-0.2, -0.2, 1.0)), (3.54, V(0.2, 0.1, -0.5)), (3.90, V(0, 0, 0))),
               rings=[(I_FWD, V(0.9, -0.2, 0.8), 7.5, 0.35)], end=3.90)
I_BOLT = Curve((0.0, 0.0), (2.20, 0.0, "stop"), (2.28, 0.006), (I_BACK, 0.020, ("hit", 1.3)), (I_LET, 0.0192, "stop"),
               (I_FWD, 0.0, ("hit", 2.4)), (I_FWD + 0.010, 0.0010, "stop"), (I_FWD + 0.020, 0.0, "stop"), (3.9, 0.0))
I_FOLD = Curve((0.0, 0.0), (2.06, 0.0, "stop"), (2.18, rig.UNFOLD * 0.85, ("hit", 1.3)), (I_LET + 0.01, rig.UNFOLD * 0.85),
               (I_FWD + 0.06, -2.0, ("hit", 1.6)), (I_FWD + 0.09, 2.0, "stop"), (I_FWD + 0.13, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (1.98, {"support": 1}), (2.16, {"handle": 1}), (I_LET, {"handle": 1}),
                 (2.80, {"world": 1}), (3.14, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (1.98, V(0, 0, 0)), (2.07, V(-0.020, 0.024, 0.016), "flow"), (2.16, V(0, 0, 0)),
                   (I_LET, V(0, 0, 0)), (2.84, V(0.004, -0.018, 0.024), "flow"), (3.14, V(0, 0, 0)))
I_FINGERS = Curve((0.0, 0.0), (1.98, 0.0), (2.05, 1.0), (2.10, 1.0), (2.17, 0.0), (I_LET - 0.005, 0.0),
                  (I_LET + 0.04, 1.0), (2.96, 1.0), (3.12, 0.0))
I_SH_R = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.54, V(0.0, 0.11, -0.11)), (1.46, V(0.0, 0.11, -0.11)),
               (1.88, V(0.000, 0.060, -0.060)), (3.00, V(0.000, 0.060, -0.060)), (3.52, V(0, 0, 0)))
I_SH_L = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.54, V(-0.020, 0.010, -0.050)), (1.46, V(-0.020, 0.010, -0.050)),
               (1.88, V(-0.080, -0.010, 0.070)), (3.00, V(-0.080, -0.010, 0.070)), (3.52, V(0, 0, 0)))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.10, rig.POLE_R), (0.54, V(1.0, 0.0, 0.3)), (1.46, V(1.0, 0.0, 0.3)),
                 (1.88, V(0.90, 0.10, 0.45)), (3.00, V(0.90, 0.10, 0.45)), (3.52, rig.POLE_R))


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
    curls = blend_curls(curls, hands.POSES["vec_clamp"], 0.7 * float(L_REGRIP(t - 1.30)))
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
    p.fingers_l = hands.adjust(hands.POSES["vec_chin"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
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
    p.fingers_r = hands.blend({"vec_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.185, -0.182, -0.300)                  # tactical sprint: muzzle up beside the right shoulder
TAC_Q = gun_frame((0.30, 0.80, -0.52), (-0.92, 0.25, -0.10))   # the top turned in to the face: the long
                                                                 # receiver shows its flank, clear of the view
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
    p.fingers_r = hands.blend({"vec_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)

# section 31.6: the hand-keyed detail layer (overlap, settles, anticipation, hand adjustments, re-grips)
DRAW, RELOAD, RELOAD_EMPTY, INSPECT = (motion_layer.layered(c, rig, seed) for c, seed in
                                       ((DRAW, 441), (RELOAD, 442), (RELOAD_EMPTY, 443), (INSPECT, 444)))


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
