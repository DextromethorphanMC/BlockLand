"""Karambit clips (VIEWMODEL_SPEC sections 31 and 34). Authored with the balisong's tools (knife_clips: Motion splined
through key poses with flow / stop / whip / hit / snap keys, impact rings, moving holds, a camera that coils with the
wind-ups and leans along the cuts); contact keys sit on the contact times of knives.json: light 0.12 of 0.38 s, heavy
0.36 of 0.85 s. The karambit is held in a reverse claw grip, so the slashes are hooks — the claw's inner edge leads,
the fist punches through — and the tricks are the knife spinning round the index finger in its ring (`spin`, about
the ring's axis): flicked off by the wrist, the other fingers letting go, caught by the closing fist with a little
overshoot. A half turn takes it into a forward grip (blade up past the thumb), another back into the claw."""
import numpy as np

import armrig
import hands
import karambit_rig as kr
from anim import Curve, smoothstep
from knife_clips import CamCurve, Spline, ease, feasible_for, light_keys, mix, nudge, periodic_bounce, pump, rings, turned
from p9_clips import Clip
from vmath import norm, quat_euler, quat_mul, quat_rotate, frame_quat

V = lambda *a: np.array(a, float)
T_HIT = 0.12                 # light attacks: the blade crosses the screen centre (knives.json impact / duration)
T_EXT = 0.36                 # heavy: full extension
DUR_L, DUR_H = 0.38, 0.85
REST = (kr.REST_T, kr.REST_Q)


def claw_q(blade_dir, hook_dir):
    """Knife orientation from the blade's root direction (knife -Z) and the direction the claw hooks to (its inner
    edge faces it: knife -X)."""
    z = -norm(blade_dir)
    x = -norm(np.asarray(hook_dir, float) - z * np.dot(hook_dir, z))
    return frame_quat(x, np.cross(z, x), z)


def centre_root(q, depth):
    """Knife root such that the blade (root -> point chord's middle) lies on the screen-centre ray."""
    return V(0, 0, -depth) - quat_rotate(q, kr.BLADE_MID)


def feasible(*keys, contact=None, extra=None, iters=8):
    return feasible_for(kr, kr.KarambitPose, kr.BLADE_MID, *keys, contact=contact, extra=extra, iters=iters)


def at_rest(dt=(0, 0, 0), drot=(0, 0, 0)):
    return kr.REST_T + V(*dt), turned(kr.REST_Q, V(*drot))


def squeeze(t, t_hit, depth=-6.0, width=0.06, loosen=0.0, t_loose=None):
    """The claw fist tightening round the contact; the index finger (in the ring) barely moves."""
    k = depth * np.exp(-((t - t_hit) / width) ** 2)
    if loosen and t_loose is not None:
        k += loosen * np.exp(-((t - t_loose) / 0.05) ** 2)
    return hands.adjust(kr.CLAW, index=(0.25 * k, 0.15 * k), middle=(k, k * 0.5), ring=(k, k * 0.5), pinky=(k, 0),
                        thumb=(k * 0.6, 0))


def loose(w):
    """The fist opened by w for a spin: middle, ring, little finger and thumb let go, the index stays in the ring."""
    if w <= 1e-4:
        return kr.CLAW
    out = {f: (1 - w) * np.asarray(kr.CLAW[f], float) + w * np.asarray(hands.POSES["spread"][f], float)
           for f in hands.FINGERS}
    out["index"] = np.asarray(kr.CLAW["index"], float) + w * V(6.0, 4.0)
    return out


def blend_curls(a, b, w):
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = kr.KarambitPose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = kr.REST_T + V(0.0004 * np.sin(w + 0.5), 0.0006 * np.sin(w), 0.0003 * np.sin(w + 1.2))
    p.knife_q = quat_mul(kr.REST_Q, quat_euler(0.3 * np.sin(w + 0.4), 0.35 * np.sin(w + 1.0), 0.4 * np.sin(w)))
    p.fingers_r = hands.adjust(kr.CLAW, middle=(1.5 * np.sin(w + 2.0), 0.8 * np.sin(w + 2.4)),
                               thumb=(2.0 * np.sin(w + 0.8), 1.0 * np.sin(w + 1.3)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand hook: the fist cocks up and out to the right (the claw hanging, its edge facing left), the hook whips
# right-to-left through the centre (fastest at the contact, the claw raking across) and decelerates evenly low-left;
# back to the guard along an arc and settled
LIGHT_TIMES = (0.036, 0.080, T_HIT, 0.235, 0.285, 0.335, DUR_L)
L1_W = (V(0.182, -0.040, -0.292), mix(kr.REST_Q, claw_q((0.20, -0.80, -0.56), (-0.92, 0.05, -0.38)), 0.5))
L1_C = claw_q((-0.30, -0.72, -0.62), (-0.88, 0.20, -0.42))
L1_E = (V(-0.070, -0.170, -0.318), claw_q((-0.62, -0.62, -0.48), (-0.45, 0.42, -0.79)))
L1 = feasible(*light_keys(LIGHT_TIMES, REST, L1_W, L1_C, centre_root(L1_C, 0.36), (0.006, 0.007, 0.002), 0.08,
                          end=L1_E, back=(V(0.050, -0.150, -0.326), 0.62),
                          over=((0.004, 0.005, -0.002), (0.0, 2.5, 1.5))), contact=3)
L1_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.078, V(0.30, -0.28, 0.65)), (T_HIT, V(-0.05, 0.30, -0.50)),
                  (0.170, V(-0.50, 0.95, -2.10)), (0.235, V(-0.32, 0.72, -1.55)), (0.315, V(0.08, -0.08, 0.22)),
                  (DUR_L, V(0, 0, 0)))


def light1_pose(t):
    p = kr.KarambitPose()
    L1.apply(p, t)
    p.fingers_r = squeeze(t, T_HIT, -6.0, 0.06, loosen=4.0, t_loose=0.078)
    L1_CAM(p, t)
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand hook: the fist drops and coils across the body low-left (the claw facing right), whips up through the
# centre and on out high-right; the wrist keeps rolling through the wind-up into the strike
L2_W = (V(0.010, -0.168, -0.312), mix(kr.REST_Q, claw_q((-0.62, -0.55, -0.56), (0.20, 0.80, -0.55)), 0.7))
L2_C = claw_q((-0.70, -0.30, -0.65), (0.15, 0.90, -0.40))
L2_E = (V(0.214, -0.034, -0.302), claw_q((-0.50, -0.18, -0.85), (0.20, 0.95, -0.22)))
L2 = feasible(*light_keys(LIGHT_TIMES, REST, L2_W, L2_C, centre_root(L2_C, 0.36), (0.006, -0.030, 0.003), 0.05,
                          end=L2_E, back=(V(0.170, -0.060, -0.312), 0.60),
                          over=((-0.004, -0.004, 0.002), (0.0, -2.0, -1.5)),
                          wind_mode={"pos": "stop", "rot": "flow"}), contact=3)
L2_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.078, V(-0.22, 0.25, -0.60)), (T_HIT, V(0.10, -0.30, 0.50)),
                  (0.170, V(0.55, -0.95, 2.10)), (0.235, V(0.36, -0.72, 1.55)), (0.315, V(-0.08, 0.08, -0.22)),
                  (DUR_L, V(0, 0, 0)))


def light2_pose(t):
    p = kr.KarambitPose()
    L2.apply(p, t)
    p.fingers_r = squeeze(t, T_HIT, -6.0, 0.06, loosen=4.0, t_loose=0.078)
    L2_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ heavy
# a hammer-fist hook: a small dip, the fist coils high beside the head (the claw pointing forward, its hook up),
# creeps back while it loads, then drives forward and down, accelerating all the way to full extension at 0.36 where
# the point bites and stops dead; a shudder, then the rip - the fist pulls back and down, dragging the claw through -
# and home along an arc
HV_C1 = claw_q((-0.10, -0.05, -0.99), (-0.05, 0.95, -0.20))
HV_C2 = claw_q((-0.08, -0.12, -0.99), (-0.05, 0.95, -0.25))
HV_E = claw_q((-0.42, -0.50, -0.76), (0.05, 0.85, -0.52))
HV_RIP = claw_q((-0.45, -0.75, -0.48), (0.10, 0.45, 0.89))
HV = feasible((0.000, kr.REST_T, kr.REST_Q, "stop"),
              (0.050, *at_rest((-0.004, -0.008, -0.008), (0.0, -3.0, 0.0)), "stop"),
              (0.180, V(0.150, -0.010, -0.250), HV_C1, "flow"),
              (0.262, V(0.160, 0.000, -0.236), HV_C2, "stop"),
              (T_EXT, V(0.060, -0.060, -0.462), HV_E, {"pos": ("hit", 2.1), "rot": ("hit", 1.6)}),
              (0.420, V(0.066, -0.070, -0.448), turned(HV_E, V(0.0, -3.0, 0.0)), "stop"),
              (0.560, V(0.090, -0.150, -0.360), HV_RIP, "flow"),
              (0.720, *at_rest((0.003, -0.006, 0.004), (0.0, -1.5, 0.0)), ("flow", 0.6)),
              (DUR_H, kr.REST_T, kr.REST_Q, "stop"))
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.050, V(-0.002, 0.0, -0.004), "stop"),
                     (0.262, V(0.006, 0.004, 0.020), "stop"), (T_EXT, V(-0.026, -0.004, -0.036), "hit"),
                     (0.420, V(-0.020, -0.004, -0.028), "stop"), (0.560, V(-0.006, -0.010, -0.006), "flow"),
                     (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_EXT, V(0.0, 0.0015, 0.004), 9.0, 0.35)]
HV_RING_ROT = [(T_EXT, V(0.0, 1.6, 0.8), 9.0, 0.35)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.050, V(-0.15, 0.0, 0.0)), (0.262, V(0.65, 0.18, 0.55)),
                  (T_EXT, V(-0.60, -0.10, -0.25), "hit"), (0.560, V(-0.35, 0.05, 0.30)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_EXT, V(-1.1, -0.15, -0.45), 7.0, 0.40)])


def heavy_pose(t):
    p = kr.KarambitPose()
    HV.apply(p, t)
    nudge(p, rings(t, HV_RING, DUR_H), rings(t, HV_RING_ROT, DUR_H))
    p.shoulder_r = HV_SHOULDER(t)
    p.fingers_r = squeeze(t, T_EXT, -8.0, 0.08)
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# drawn from below (off screen for the first tenth of a second), the claw pointing back up the forearm: the wrist
# flicks and the fist opens, the karambit spins a full turn round the index finger in its ring, fastest mid-turn, and
# the closing fist catches it in the claw grip - it turns on a little past and is pulled back, the hand jolts, the
# camera answers - and it settles into the guard a hair past it
T_FLIP, T_CATCH, DUR_D = 0.22, 0.44, 0.70
DR = feasible((0.00, V(0.180, -0.330, -0.250), claw_q((-0.40, -0.30, -0.87), (-0.20, 0.95, -0.20)), "stop"),
              (0.10, V(0.168, -0.255, -0.272), claw_q((-0.45, -0.32, -0.83), (-0.20, 0.95, -0.20)), "flow"),
              (0.20, V(0.140, -0.130, -0.300), claw_q((-0.50, -0.36, -0.79), (-0.30, 0.90, -0.30)), "flow"),
              (T_CATCH, V(0.118, -0.086, -0.316), claw_q((-0.55, -0.42, -0.72), (-0.35, 0.90, -0.30)), "flow"),
              (0.54, V(0.114, -0.078, -0.316), turned(kr.REST_Q, V(0.0, 3.0, 2.0)), "flow"),
              (0.63, *at_rest((-0.003, 0.004, -0.003), (0.0, 2.0, 0.0)), ("flow", 0.6)),
              (DUR_D, kr.REST_T, kr.REST_Q, "stop"))
DR_FLICK = Curve((0.14, 0.0), (0.20, 9.0), (0.27, -10.0), (0.34, -2.0), (0.40, 0.0))   # knife-local pitch, degrees
DR_SPIN = Curve((0.0, -360.0), (T_FLIP, -360.0, ("snap", 0.5)), (T_CATCH, 0.0, ("hit", 1.6)), (DUR_D, 0.0))
DR_SPIN_OVER = [(T_CATCH, 8.0, 8.0, 0.45)]                     # the catch: it turns on a little past the fist
DR_JOLT = [(T_CATCH, V(-0.002, -0.004, 0.003), 8.0, 0.40)]
DR_JOLT_ROT = [(T_CATCH, V(0.6, -2.5, 1.0), 8.0, 0.40)]
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.20, V(0.3, 0.0, 0.4)), (0.34, V(-0.1, 0.0, -0.1)), (DUR_D, V(0, 0, 0)),
                  accents=[(T_CATCH, V(-0.5, 0.05, -0.35), 7.0, 0.40)])


def draw_pose(t):
    p = kr.KarambitPose()
    DR.apply(p, t)
    nudge(p, rings(t, DR_JOLT, DUR_D), rings(t, DR_JOLT_ROT, DUR_D) + V(0.0, float(DR_FLICK(t)), 0.0))
    p.spin = float(DR_SPIN(t) + rings(t, DR_SPIN_OVER, DUR_D))
    open_f = ease("inout", t, T_FLIP - 0.05, T_FLIP + 0.03) * (1 - ease("in2", t, T_CATCH - 0.05, T_CATCH))
    p.fingers_r = blend_curls(squeeze(t, T_CATCH + 0.025, -7.0, 0.05), loose(1.0), 0.85 * open_f)
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# raise the fist and turn the claw to the eye; two spins forward round the ring and one back, each flicked off by the
# wrist and caught; a half spin into the forward grip (blade up past the thumb), a slow menacing turn, a half spin
# back into the claw; the left hand presents the blade (a moving hold); back to the guard. Every catch turns on a
# little past and is pulled back, the hand and the camera answer it.
DUR_I = 3.60
P_T, Q_T = kr.reachable(V(0.096, -0.074, -0.318), claw_q((-0.70, -0.30, -0.65), (-0.20, 0.97, -0.10)))
P_F, Q_F = kr.reachable(V(0.104, -0.088, -0.312), claw_q((-0.62, -0.42, -0.66), (-0.30, 0.92, -0.25)))
P_SHOW, Q_SHOW = kr.reachable(V(0.090, -0.070, -0.318), claw_q((-0.85, -0.25, -0.46), (-0.15, 0.30, -0.94)))
SPINS = ((0.38, 0.84, 360.0), (0.98, 1.44, 360.0), (1.56, 1.92, -360.0), (2.06, 2.30, 180.0), (2.92, 3.14, 180.0))
INS = feasible((0.00, kr.REST_T, kr.REST_Q, "stop"),
               (0.22, P_T + V(-0.004, 0.006, 0.002), turned(Q_T, V(0.0, 3.0, 2.0)), ("flow", 0.7)),
               (0.32, P_T, Q_T, "flow"),
               (1.94, P_T + V(0.002, -0.002, 0.0), turned(Q_T, V(1.0, -1.0, 0.0)), "flow"),
               (2.30, P_F, Q_F, "flow"),
               (2.88, P_F + V(-0.002, 0.002, 0.0), turned(Q_F, V(-1.5, 0.0, 1.0)), "flow"),
               (3.14, P_SHOW, Q_SHOW, "flow"),
               (3.32, P_SHOW + V(-0.003, 0.002, 0.0), turned(Q_SHOW, V(3.0, 0.0, 2.0)), "flow"),
               (3.48, *at_rest((-0.002, 0.003, -0.002), (0.0, 1.8, 0.0)), ("flow", 0.6)),
               (DUR_I, kr.REST_T, kr.REST_Q, "stop"))


def _spin_curve():
    keys, total = [(0.0, 0.0)], 0.0
    for t0, t1, deg in SPINS:
        keys.append((t0, total, ("snap", 1.5)))         # flicked off fast, slowed by the finger's friction
        total += deg
        keys.append((t1, total, ("hit", 0.5)))          # and caught while it still turns
    keys.append((DUR_I, total))
    return Curve(*keys)


SPIN = _spin_curve()
SPIN_OVER = [(t1, 5.0 * np.sign(deg), 8.0, 0.45) for _, t1, deg in SPINS]
EV_INSPECT = [(t0, "flip") for t0, _, _ in SPINS] + [(t1, "catch") for _, t1, _ in SPINS]
EV_INSPECT.sort()
INS_JOLT = [(t1, V(0.0, -0.0025, 0.0015), 8.0, 0.40) for _, t1, _ in SPINS]
INS_JOLT_ROT = [(t1, V(0.0, -1.8, 0.8), 8.0, 0.40) for _, t1, _ in SPINS]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.32, V(0.4, 0.0, 1.0)), (1.40, V(0.3, 0.0, 0.8)),
                   (2.30, V(0.2, -0.4, -1.0)), (2.88, V(-0.2, 0.3, -1.2)), (3.20, V(-0.2, 0.4, 1.2)),
                   (DUR_I, V(0, 0, 0)), accents=[(t1, V(-0.3, 0.0, -0.2), 8.0, 0.40) for _, t1, _ in SPINS])


def spin_pump(t, spin):
    """The wrist driving the spins round the finger: one pump per turn, phase-locked to the spin angle."""
    w = 0.0
    for t0, t1, _ in SPINS[:3]:
        w = max(w, smoothstep(t0 - 0.03, t0 + 0.08, t) * (1 - smoothstep(t1 - 0.10, t1, t)))
    ph = np.radians(spin)
    return (V(0.0, 0.0035 * np.sin(ph + 1.0), 0.0012 * np.sin(ph + 2.6)) * w,
            V(2.5 * np.sin(ph + 0.2), 3.5 * np.sin(ph + 2.2), 6.0 * np.sin(ph + 0.6)) * w)


def inspect_pose(t):
    p = kr.KarambitPose()
    INS.apply(p, t)
    spin = float(SPIN(t))
    p.spin = spin + float(rings(t, SPIN_OVER, DUR_I))
    dpos, drot = spin_pump(t, spin)
    nudge(p, dpos + rings(t, INS_JOLT, DUR_I), drot + rings(t, INS_JOLT_ROT, DUR_I))
    # in the forward grip the wrist turns the blade out and back (a slow menacing turn)
    turn = np.sin(np.pi * np.clip((t - 2.36) / 0.50, 0, 1)) ** 2
    p.knife_q = quat_mul(p.knife_q, quat_euler(0.0, 0.0, 26.0 * turn))
    open_f = 0.0
    for t0, t1, _ in SPINS:
        open_f = max(open_f, ease("inout", t, t0 - 0.05, t0 + 0.04) * (1 - ease("in2", t, t1 - 0.06, t1)))
    # in the forward grip (between the half spins) the fist stays half open: the handle sits above the fingers
    fwd = ease("inout", t, 2.22, 2.32) * (1 - ease("inout", t, 2.90, 2.98))
    k = -6.0 * sum(np.exp(-((t - t1 - 0.02) / 0.05) ** 2) for _, t1, _ in SPINS)        # the fist closes on each catch
    fist = hands.adjust(kr.CLAW, index=(0.25 * k, 0), middle=(k, k * 0.5), ring=(k, 0), pinky=(k, 0), thumb=(k * 0.6, 0))
    p.fingers_r = blend_curls(fist, loose(1.0), max(0.55 * open_f, 0.45 * fwd))
    # left hand presents the blade
    w = ease("inout", t, 3.12, 3.30) * (1 - ease("inout", t, 3.36, 3.52))
    mid = p.knife_point(kr.BLADE_MID)
    present = (mid + V(0.020, -0.078, 0.030), armrig.palm_from_back((-0.2, -1.0, 0.25), (0.6, 0.2, -0.75), "L"),
               hands.blend({"relaxed": 0.6, "spread": 0.4}))
    rest_l = (kr.LEFT_REST[0], present[1], hands.blend({"relaxed": 1.0}))
    p.hand_l = armrig.blend_targets([(1 - w + 1e-6, rest_l), (w, present)])
    p.left_scale = 1.0 if 3.06 <= t < 3.56 else 0.0
    INS_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ sprint
# lowered running carry: the knife arm swings with the stride, the karambit trailing its swing, a hard drop on every
# foot strike; the left fist pumps big and lingers at the ends of its swing
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_C, Q_RUN = kr.reachable(V(0.130, -0.132, -0.290), claw_q((-0.30, -0.80, -0.52), (-0.30, 0.45, -0.84)))
RUN_A = V(-0.008, 0.038, -0.034)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def sprint_pose(t):
    p = kr.KarambitPose()
    b = periodic_bounce(t, DUR_S / 2, 0.12, 1.6)
    side = np.cos(W_S * (t - 0.04))
    lag = np.cos(W_S * t - 0.55)
    p.knife_t = RUN_C + RUN_A * pump(np.cos(W_S * t)) + V(0.003 * side, -0.010 * b, 0.002 * b)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.0 * np.sin(W_S * t), -7.0 * lag - 2.5 * b, 3.0 * side))
    p.fingers_r = hands.adjust(kr.CLAW, middle=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    p.hand_l = (L_RUN_C - L_RUN_A * pump(np.cos(W_S * t)) + V(0.0, -0.006 * b, 0.0), None, hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


# -------------------------------------------------------------------------------------- walk
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def walk_pose(t):
    """Guard held, the arm countering the steps a few hundredths behind the body; the fist tightens on impacts."""
    p = kr.KarambitPose()
    b = periodic_bounce(t, DUR_W / 2, 0.10, 1.4)
    b_late = periodic_bounce(t - 0.04, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.knife_t = kr.REST_T + V(0.004 * side, 0.003 - 0.006 * b, 0.002 * b - 0.001 * np.sin(W_W * t))
    p.knife_q = quat_mul(kr.REST_Q, quat_euler(1.2 * np.cos(W_W * (t - 0.09)), 0.4 - 1.2 * b_late,
                                               1.6 * np.sin(W_W * (t - 0.04))))
    p.fingers_r = hands.adjust(kr.CLAW, middle=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    return p


# -------------------------------------------------------------------------------- tac_sprint
# high guard: the fist raised beside the face, the claw hanging forward; the knife arm pumps short and hard (the claw
# whipping a little behind it), the left fist pumps big
DUR_T = 0.58
W_T = 2 * np.pi / DUR_T
TAC_C, Q_TAC = kr.reachable(V(0.108, -0.062, -0.272), claw_q((-0.35, -0.55, -0.76), (-0.25, 0.80, -0.55)))
TAC_A = V(-0.006, 0.020, -0.016)


def tac_sprint_pose(t):
    p = kr.KarambitPose()
    b = periodic_bounce(t, DUR_T / 2, 0.10, 1.8)
    side = np.cos(W_T * (t - 0.035))
    lag = np.cos(W_T * t - 0.6)
    p.knife_t = TAC_C + TAC_A * pump(np.cos(W_T * t)) + V(0.004 * side, 0.004 - 0.013 * b, 0.003 * b)
    p.knife_q = quat_mul(Q_TAC, quat_euler(2.0 * side, -3.5 * lag - 2.8 * b, 2.5 * np.sin(W_T * t)))
    p.fingers_r = hands.adjust(kr.CLAW, middle=(-3.0 * b, 0))
    p.hand_l = (L_RUN_C + V(0.0, 0.012 - 0.006 * b, 0.0) - L_RUN_A * 1.1 * pump(np.cos(W_T * t)), None,
                hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "equip"), (T_FLIP, "flip"), (T_CATCH, "catch")], fps=60,
         extra_times=(0.20, T_FLIP, T_CATCH, 0.54)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.065, "swing")], fps=60, extra_times=(0.078, T_HIT, 0.235)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.065, "swing")], fps=60, extra_times=(0.078, T_HIT, 0.235)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.27, "swing_heavy")], fps=60, extra_times=(0.262, T_EXT, 0.42)),
    Clip("inspect", DUR_I, inspect_pose, events=EV_INSPECT, fps=60,
         extra_times=tuple(t for t, _ in EV_INSPECT) + (3.06, 3.56)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
