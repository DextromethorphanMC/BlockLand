"""P9 `sprint` clip: CoD-style tactical sprint, pistol at high ready in the right hand, left arm pumping.

0.66 s loop = two footsteps (left foot contact at t=0, right foot at t=0.33). Everything is a periodic function
of t, so the first and last keys are identical. The camera node is not animated (the runtime adds its own bob)."""
import numpy as np

import p9_rig as rig
from p9_clips import V, Clip, apply_gun
from vmath import norm

DUR = 0.66
STEP = DUR / 2
W = 2 * np.pi / DUR

# high-ready pose (absolute, viewmodel space): grip beside/just in front of the right cheek, muzzle ~64 deg up
SPRINT_T = V(0.124, -0.044, -0.246)
# yaw (muzzle leans in towards the screen centre), pitch (muzzle ~64 deg up), roll (left flank towards the eye);
# applied on top of the hip rotation
SPRINT_EULER = V(6.0, 64.0, -6.0)

# left fist pumping on an ellipse: back/down (off screen) at t=0, forward/up in the lower-left at t=STEP
ARM_C = V(-0.140, -0.283, -0.155)            # forward extreme (-0.115, -0.135, -0.29): lower-left of the view
ARM_A = V(0.025, 0.148, -0.135)              # half swing: forward and up; back extreme is below the screen
ARM_B = V(-0.018, 0.020, 0.000)              # lateral bulge so the path is an ellipse, not a line
L_BACK = norm((-1.0, 0.10, -0.15))           # back of the fist faces outward
L_POLE = V(-0.60, -0.70, 0.50)               # elbow out, down and back while running


def step_bounce(t):
    """0..1 impact curve per footstep, lowest point just after foot contact."""
    ph = (t % STEP) / STEP
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - 0.12))) ** 1.6


def sprint_pose(t):
    p = rig.Pose()
    b = step_bounce(t)
    side = np.cos(W * (t - 0.04))            # +1 on the left step, -1 on the right step
    offset = SPRINT_T - rig.GUN_T + V(0.004 * side, 0.004 - 0.010 * b, 0.003 * b)
    euler = SPRINT_EULER + V(1.5 * np.sin(W * t), -1.8 * b, 2.6 * side)
    apply_gun(p, offset, euler)
    pos = ARM_C - ARM_A * np.cos(W * t) + ARM_B * np.sin(W * t)
    p.hand_l = (pos, L_BACK)
    p.pole_l = L_POLE
    return p


SPRINT = Clip("sprint", DUR, sprint_pose, fps=60, loop=True)
