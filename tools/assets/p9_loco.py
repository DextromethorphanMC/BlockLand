"""P9 locomotion clips (sections 17, 31): walk, sprint (lowered running carry), tac_sprint (raised high-ready carry).
One loop = two footsteps (left foot contact at t = 0, right foot at half the loop); everything is periodic, so
first and last keys are identical. No camera animation (the runtime adds its own bob).

Each footstep is an impact: the body drops quickly just after the contact and rises softly through the stride
(an asymmetric per-step curve, not a sine). The weapon rides the body through a spring (anim.Follow, periodic), so
it lags each impact and bounces a little after it; it counter-rolls against the steps. In the carries the free hand
pumps as a fist on an ellipse and takes the impacts too."""
import numpy as np

import hands
import p9_rig as rig
from anim import Curve, Follow
from p9_clips import V, Clip, apply_gun


def step_bounce(t, period, lag=0.12, sharp=1.6):
    """0..1 impact curve per footstep (period = one step), lowest point just after foot contact."""
    ph = (t % period) / period
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - lag))) ** sharp


def step_curve(period, low=0.20, high=0.70):
    """Per-footstep body height, -1 (bottom of the stance at `low` of the step) .. +1 (top of the flight at `high`):
    falling fastest at the foot contact (t = 0) and caught sharply just after it - the impact - then a long push up.
    Periodic over one step."""
    return Curve((0.0, 0.0), (low * period, -1.0, "stop"), (high * period, 1.0, "stop"), (period, 0.0), loop=True)


def pump_left(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0)):
    """Left fist pumping on an ellipse: back/down at t = 0, forward/up at half the loop."""
    w = 2 * np.pi / dur
    pos = centre - swing * np.cos(w * t) + bulge * np.sin(w * t)
    curls = hands.adjust(hands.POSES["fist"], index=(6 * np.sin(w * t), 0), pinky=(-4 * np.sin(w * t), 0))
    return pos, None, curls                       # free hand: the fist follows the forearm


class Gait:
    """Body motion of one loop (two steps) and the weapon's spring response to it (viewmodel offsets)."""

    def __init__(self, dur, drop, sway, low=0.20, high=0.70, freq=4.2, zeta=0.32):
        self.dur = dur
        self.step = step_curve(dur / 2, low, high)
        w = 2 * np.pi / dur
        self.body = lambda t: V(sway * np.cos(w * (t - 0.04 * dur)), drop * float(self.step(t)), 0.0)
        self.follow = Follow(self.body, dur, freq=freq, zeta=zeta, loop=True)

    def __call__(self, t):
        """(body offset, weapon lag behind it, step value -1..1, side +1 left .. -1 right)."""
        w = 2 * np.pi / self.dur
        return self.body(t), self.follow.lag(t), float(self.step(t)), float(np.cos(w * (t - 0.04 * self.dur)))


# ------------------------------------------------------------------------------------------- walk
WALK_DUR = 1.00
WALK_GAIT = Gait(WALK_DUR, drop=0.0028, sway=0.0030, freq=4.5, zeta=0.35)


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    body, lag, b, side = WALK_GAIT(t)
    off = body + 0.8 * lag + V(0.0, 0.0, -0.0006 * b - 0.0008 * np.sin(w * t))
    apply_gun(p, off, V(0.7 * np.sin(w * t) + 40 * lag[0], -0.55 * b + 0.12 + 180 * lag[1], 1.1 * side - 30 * lag[0]))
    grip = p.anchor("grip")
    p.fingers_r = hands.adjust(grip[2], index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["support"], middle=(1.6 * b - 0.4, 0), ring=(1.6 * b - 0.4, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

# ----------------------------------------------------------------------------------- sprint (lowered)
SPRINT_DUR = 0.66
LOW_T = V(0.108, -0.098, -0.330)                  # gun lowered towards the hip (absolute, viewmodel space)
LOW_EULER = V(14.0, -24.0, 26.0)                  # muzzle down-in, canted inward, on top of the hip rotation
L_RUN_C = V(-0.140, -0.283, -0.155)
L_RUN_A = V(0.025, 0.148, -0.135)
RUN = Gait(SPRINT_DUR, drop=0.0070, sway=0.0045, low=0.18, high=0.68, freq=4.6, zeta=0.34)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    body, lag, b, side = RUN(t)
    swing = np.cos(w * t)                         # the gun arm swings opposite to the left arm
    offset = LOW_T - rig.GUN_T + body + 0.9 * lag + V(0.0, 0.004 * swing, 0.010 * swing)
    apply_gun(p, offset, LOW_EULER + V(2.0 * np.sin(w * t) + 50 * lag[0], 2.0 * b + 3.0 * swing + 220 * lag[1],
                                       3.2 * side - 40 * lag[0]))
    p.fingers_r = hands.blend({"indexed": 1.0})
    pos, q, curls = pump_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A)
    p.hand_l = (pos + V(0.0, 0.006 * b, 0.0), q, curls)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

# --------------------------------------------------------------------------------- tac_sprint (raised)
TAC_DUR = 0.58
TAC_T = V(0.082, -0.042, -0.270)                  # grip beside / just in front of the right cheek
TAC_EULER = V(6.0, 64.0, -6.0)                    # muzzle ~64 deg up, leaning in, left flank to the eye
TAC = Gait(TAC_DUR, drop=0.0080, sway=0.0050, low=0.17, high=0.68, freq=4.8, zeta=0.34)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    body, lag, b, side = TAC(t)
    offset = TAC_T - rig.GUN_T + body + 0.9 * lag + V(0.0, 0.002, 0.0025 * b)
    apply_gun(p, offset, TAC_EULER + V(1.8 * np.sin(w * t) + 40 * lag[0], 1.8 * b + 200 * lag[1],
                                       3.0 * side - 40 * lag[0]))
    p.fingers_r = hands.blend({"indexed": 1.0})
    pos, q, curls = pump_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.10)
    p.hand_l = (pos + V(0.0, 0.007 * b, 0.0), q, curls)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)
