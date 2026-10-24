"""Knuckles clips (VIEWMODEL_SPEC sections 31 and 42). Authored with the knife tools (knife_clips: Motion splined
through key poses with flow / stop / hit / snap keys, impact rings, a camera that follows the body's turn) on each fist
(knuckles_rig: the middle of the duster and the hand's palm frame); the arms solve to the wrists behind them and the
dusters ride on the solved hands. Contact keys sit on the contact times of knives.json: light 0.10 of 0.30 s (the
striking face at full extension in front of the screen centre), heavy 0.25 of 0.62 s.

Boxing mechanics: every punch starts in the legs and hips - the punching shoulder drives forward (the body turns,
`shoulder` offsets and the camera's yaw / roll) while the other fist tucks to the chin; the fist turns over late
(a corkscrew, palm down at the contact), arrives at speed and stops dead, the arm recoils off the target and comes back
faster than it went out, along a slightly lower path, into the guard with a little settle."""
import numpy as np

import knuckles_rig as kr
from knife_clips import CamCurve, Motion, Spline, ease, mix, periodic_bounce, pump, rings
from p9_clips import Clip
from vmath import quat_euler, quat_mul

V = lambda *a: np.array(a, float)
Q = kr.fist_q
T_HIT, DUR_L = 0.10, 0.30          # knives.json light: impact / duration
T_UP, DUR_H = 0.25, 0.62           # heavy
GR, GL = kr.GUARD_R, kr.GUARD_L


def turned(q, deg):
    """q turned by fist-local (yaw, pitch, roll) degrees."""
    return quat_mul(q, quat_euler(*deg)) if np.any(np.abs(np.asarray(deg)) > 1e-9) else q


def near(guard, dpos=(0, 0, 0), drot=(0, 0, 0)):
    return guard[0] + V(*dpos), turned(guard[1], V(*drot))


def fists(p, t, motions):
    """Puts each side's fist where its Motion is at t."""
    for side, m in motions.items():
        p.fist[side] = (m.pos(t), m.quat(t))


def squeeze_at(t, t_hit, depth=-5.0, width=0.05):
    """The fist clenching hard at the contact."""
    return kr.squeeze(depth * np.exp(-((t - t_hit) / width) ** 2))


def offset(p, side, dpos):
    c, q = p.fist[side]
    p.fist[side] = (c + np.asarray(dpos, float), q)


def still(guard, dur):
    return Motion((0.0, guard[0], guard[1], "stop"), (dur, guard[0], guard[1], "stop"))


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    """The guard breathing: the fists rise and fall a few millimetres, the lead hand pawing a hair."""
    p = kr.KnucklesPose()
    w = 2 * np.pi * t / 2.0
    p.fist["R"] = near(GR, (0.0006 * np.sin(w + 0.6), 0.0012 * np.sin(w), 0.0005 * np.sin(w + 1.3)),
                       (0.4 * np.sin(w + 0.3), 0.5 * np.sin(w + 1.1), 0.5 * np.sin(w)))
    p.fist["L"] = near(GL, (0.0008 * np.sin(w + 2.0), 0.0012 * np.sin(w + 0.2), 0.0012 * np.sin(w + 2.4)),
                       (0.5 * np.sin(w + 2.2), 0.4 * np.sin(w + 0.7), 0.5 * np.sin(w + 1.6)))
    p.curls["R"] = kr.squeeze(1.5 * np.sin(w + 1.7))
    p.curls["L"] = kr.squeeze(1.5 * np.sin(w + 0.4))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)


# ------------------------------------------------------------------------------ light: jab, cross
def punch(lead, c_hit, q_hit, load, back_mid, cam, shoulder_hit, other_tuck, shoulder_other):
    """A straight punch of the `lead` side ("L" jab / "R" cross): a small load, the drive to full extension at
    T_HIT (fastest there), the recoil off the target, the faster return a little lower, the settle."""
    g = GL if lead == "L" else GR
    o = GR if lead == "L" else GL
    q_load = mix(g[1], q_hit, 0.12)
    m = Motion((0.000, g[0], g[1], "stop"),
               (0.028, g[0] + V(*load), q_load, "flow"),
               (T_HIT, c_hit, q_hit, {"pos": ("hit", 2.2), "rot": ("hit", 1.6)}),
               (0.130, c_hit + V(0.0, -0.002, 0.016), turned(q_hit, V(0.0, -2.0, 0.0)), "flow"),
               (0.195, back_mid, mix(q_hit, g[1], 0.82), "flow"),
               (0.252, g[0] + V(0.002, 0.003, -0.004), turned(g[1], V(0.0, 1.5, 0.0)), ("flow", 0.5)),
               (DUR_L, g[0], g[1], "stop"))
    tuck = Motion((0.000, o[0], o[1], "stop"),
                  (0.070, o[0] + V(*other_tuck), turned(o[1], V(0.0, 4.0, 0.0)), "flow"),
                  (0.170, o[0] + V(*other_tuck) * 0.9, turned(o[1], V(0.0, 3.0, 0.0)), "flow"),
                  (DUR_L, o[0], o[1], "stop"))
    sh = Spline((0.000, V(0, 0, 0), "stop"), (0.028, V(*load) * 0.4, "flow"), (T_HIT, V(*shoulder_hit), "hit"),
                (0.150, V(*shoulder_hit) * 0.7, "flow"), (0.240, V(*shoulder_hit) * 0.05, "flow"),
                (DUR_L, V(0, 0, 0), "stop"))
    sh_o = Spline((0.000, V(0, 0, 0), "stop"), (T_HIT, V(*shoulder_other), "flow"), (DUR_L, V(0, 0, 0), "stop"))
    ring_pos = [(T_HIT, V(0.0, 0.0012, 0.004), 11.0, 0.40)]          # the arm shudders off the target
    ring_rot = [(T_HIT, V(0.0, 2.0, 0.0), 11.0, 0.40)]

    def pose(t):
        p = kr.KnucklesPose()
        fists(p, t, {lead: m, ("R" if lead == "L" else "L"): tuck})
        offset(p, lead, rings(t, ring_pos, DUR_L))
        c, q = p.fist[lead]
        p.fist[lead] = (c, turned(q, rings(t, ring_rot, DUR_L)))
        p.shoulder[lead] = sh(t)
        p.shoulder["R" if lead == "L" else "L"] = sh_o(t)
        p.curls[lead] = squeeze_at(t, T_HIT)
        p.curls["R" if lead == "L" else "L"] = kr.squeeze(-3.0 * ease("inout", t, 0.02, 0.08)
                                                            * (1 - ease("inout", t, 0.18, 0.28)))
        cam(p, t)
        return p
    return pose


# the jab: the left fist turns palm-down on its way and lands just left of and below the crosshair; the left shoulder
# drives forward, the right fist tucks to the chin, the view turns a little right with the body
JAB_C = V(-0.018, -0.036, -0.495)
JAB_Q = Q((0.06, 0.07, -1.0), (0.12, -1.0, 0.05))
JAB_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.028, V(0.10, 0.20, 0.30)), (T_HIT, V(-0.35, -0.65, -1.10), "hit"),
                   (0.170, V(-0.15, -0.35, -0.55)), (DUR_L, V(0, 0, 0)),
                   accents=[(T_HIT, V(-0.45, 0.0, -0.25), 9.0, 0.40)])
light1_pose = punch("L", JAB_C, JAB_Q, (0.004, -0.004, 0.008), V(-0.098, -0.078, -0.372), JAB_CAM,
                    (0.032, 0.012, -0.078), (-0.014, 0.010, 0.012), (0.006, 0.0, 0.026))
# the cross: the rear (right) fist, the whole body turning behind it - more shoulder, more yaw and roll
CROSS_C = V(0.014, -0.032, -0.488)
CROSS_Q = Q((-0.07, 0.07, -1.0), (-0.12, -1.0, 0.05))
CROSS_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (0.028, V(0.12, -0.30, -0.40)), (T_HIT, V(-0.40, 0.85, 1.60), "hit"),
                     (0.170, V(-0.18, 0.45, 0.80)), (DUR_L, V(0, 0, 0)),
                     accents=[(T_HIT, V(-0.50, 0.0, 0.30), 9.0, 0.40)])
light2_pose = punch("R", CROSS_C, CROSS_Q, (0.006, -0.006, 0.012), V(0.092, -0.084, -0.352), CROSS_CAM,
                    (-0.042, 0.012, -0.098), (0.016, 0.010, 0.014), (-0.006, 0.0, 0.030))


# ------------------------------------------------------------------------------------ heavy
# the uppercut: the right knee and shoulder dip (the fist drops beside the ribs, palm turning up), a beat of load, then
# the legs drive it up and in: the fist rises through the front of the centre, knuckles up, palm to the face, and stops
# dead at the chin height of the target at 0.25; the shudder, the fist dropping back out, the guard
UP_LOAD = V(0.120, -0.170, -0.278)
UP_C = V(0.026, -0.030, -0.432)
UP_Q = Q((-0.05, 0.84, -0.54), (0.05, 0.54, 0.84))
HV = Motion((0.000, GR[0], GR[1], "stop"),
            (0.070, V(0.124, -0.138, -0.288), Q((-0.10, 0.55, -0.83), (-0.30, 0.80, 0.52)), "flow"),
            (0.150, UP_LOAD, Q((-0.08, 0.62, -0.78), (-0.15, 0.78, 0.61)), ("flow", 0.4)),
            (T_UP, UP_C, UP_Q, {"pos": ("hit", 2.0), "rot": ("hit", 1.5)}),
            (0.300, UP_C + V(0.002, -0.006, 0.010), turned(UP_Q, V(0.0, -3.0, 0.0)), "flow"),
            (0.420, V(0.090, -0.080, -0.335), mix(UP_Q, GR[1], 0.75), "flow"),
            (0.530, GR[0] + V(0.002, 0.004, -0.004), turned(GR[1], V(0.0, 1.5, 0.0)), ("flow", 0.5)),
            (DUR_H, GR[0], GR[1], "stop"))
HV_L = Motion((0.000, GL[0], GL[1], "stop"),
              (0.120, GL[0] + V(0.020, 0.006, 0.020), turned(GL[1], V(0.0, 5.0, 0.0)), "flow"),
              (0.330, GL[0] + V(0.022, 0.008, 0.022), turned(GL[1], V(0.0, 5.0, 0.0)), "flow"),
              (DUR_H, GL[0], GL[1], "stop"))
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.150, V(0.006, -0.034, 0.014), "stop"),
                     (T_UP, V(-0.032, 0.022, -0.062), "hit"), (0.330, V(-0.026, 0.016, -0.050), "flow"),
                     (0.500, V(-0.004, 0.002, -0.006), "flow"), (DUR_H, V(0, 0, 0), "stop"))
HV_SHOULDER_L = Spline((0.000, V(0, 0, 0), "stop"), (0.150, V(0.0, -0.020, -0.010), "flow"),
                       (T_UP, V(0.010, 0.010, 0.030), "flow"), (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_UP, V(0.0, -0.002, 0.003), 10.0, 0.38)]
HV_RING_ROT = [(T_UP, V(0.0, -2.5, 0.0), 10.0, 0.38)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.150, V(-0.80, 0.25, 0.90)), (T_UP, V(0.55, -0.45, -1.40), "hit"),
                  (0.360, V(0.25, -0.25, -0.70)), (0.520, V(-0.05, 0.05, 0.10)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_UP, V(0.70, 0.0, -0.40), 7.5, 0.40)])


def heavy_pose(t):
    p = kr.KnucklesPose()
    fists(p, t, {"R": HV, "L": HV_L})
    offset(p, "R", rings(t, HV_RING, DUR_H))
    c, q = p.fist["R"]
    p.fist["R"] = (c, turned(q, rings(t, HV_RING_ROT, DUR_H)))
    p.shoulder["R"] = HV_SHOULDER(t)
    p.shoulder["L"] = HV_SHOULDER_L(t)
    p.curls["R"] = squeeze_at(t, T_UP, -7.0, 0.07)
    p.curls["L"] = kr.squeeze(-3.0 * ease("inout", t, 0.05, 0.15) * (1 - ease("inout", t, 0.40, 0.55)))
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# the fists come up from below (off screen at first), the knuckles turn in and knock together in front of the chest
# (the brass clinks: both arms jolt back off it), part and settle into the guard
DUR_D, T_CLINK = 0.55, 0.34
CLINK_R = (V(0.0158, -0.082, -0.336), Q((-1.0, 0.12, -0.20), (0.0, -0.25, -1.0)))
CLINK_L = (V(-0.0158, -0.082, -0.336), Q((1.0, 0.12, -0.20), (0.0, -0.25, -1.0)))


def _draw_motion(guard, clink, sx):
    return Motion((0.00, guard[0] + V(0.040 * sx, -0.235, 0.090), turned(guard[1], V(0.0, -35.0, 0.0)), "stop"),
                  (0.12, guard[0] + V(0.010 * sx, -0.120, 0.030), turned(guard[1], V(0.0, -18.0, 0.0)), "flow"),
                  (0.24, clink[0] + V(0.030 * sx, -0.010, 0.004), mix(guard[1], clink[1], 0.8), "flow"),
                  (T_CLINK, clink[0], clink[1], {"pos": ("hit", 1.8), "rot": ("hit", 1.4)}),
                  (0.40, clink[0] + V(0.012 * sx, 0.003, 0.004), clink[1], "flow"),
                  (0.48, guard[0] + V(0.004 * sx, 0.004, -0.004), turned(guard[1], V(0.0, 1.5, 0.0)), ("flow", 0.5)),
                  (DUR_D, guard[0], guard[1], "stop"))


DR = {"R": _draw_motion(GR, CLINK_R, 1.0), "L": _draw_motion(GL, CLINK_L, -1.0)}
DR_JOLT = {"R": [(T_CLINK, V(0.004, 0.001, 0.002), 10.0, 0.40)], "L": [(T_CLINK, V(-0.004, 0.001, 0.002), 10.0, 0.40)]}
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.20, V(0.40, 0.0, 0.0)), (T_CLINK, V(-0.15, 0.0, 0.0)),
                  (DUR_D, V(0, 0, 0)), accents=[(T_CLINK, V(-0.45, 0.0, 0.0), 9.0, 0.40)])


def draw_pose(t):
    p = kr.KnucklesPose()
    fists(p, t, DR)
    for side in "RL":
        offset(p, side, rings(t, DR_JOLT[side], DUR_D))
        # the fingers settle into the rings as the hands come up, then clench for the knock
        p.curls[side] = kr.squeeze(8.0 * (1 - ease("inout", t, 0.0, 0.22)) - 5.0 * np.exp(-((t - T_CLINK) / 0.05) ** 2))
        p.shoulder[side] = V(0.0, -0.020, 0.010) * (1 - ease("inout", t, 0.0, 0.30))
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# the right fist comes up and turns its back to the eye (a look along the knuckle bar and the bosses), turns over to
# show the palm side (the palm bar, its paracord and the curled fingers), the left fist comes in and the two knock
# knuckles twice, then both drop back into the guard
DUR_I = 3.0
T_K1, T_K2 = 2.12, 2.34
SHOW_BACK = (V(0.058, -0.042, -0.272), Q((-0.86, 0.36, -0.36), (0.12, -0.42, -0.90)))
SHOW_UP = (V(0.064, -0.047, -0.270), Q((-0.82, 0.40, -0.40), (-0.05, 0.75, -0.66)))
SHOW_PALM = (V(0.068, -0.051, -0.266), Q((-0.80, 0.42, -0.42), (-0.10, 0.62, 0.78)))
KNOCK_R = (V(0.0158, -0.098, -0.324), Q((-1.0, 0.10, -0.22), (0.0, -1.0, 0.0)))
KNOCK_L = (V(-0.0158, -0.098, -0.324), Q((1.0, 0.10, -0.22), (0.0, -1.0, 0.0)))
INS_R = Motion((0.00, GR[0], GR[1], "stop"),
               (0.40, SHOW_BACK[0], SHOW_BACK[1], ("flow", 0.6)),
               (0.95, SHOW_BACK[0] + V(-0.004, 0.004, 0.002), turned(SHOW_BACK[1], V(4.0, 3.0, 0.0)), "flow"),
               (1.18, SHOW_UP[0], SHOW_UP[1], "flow"),
               (1.42, SHOW_PALM[0], SHOW_PALM[1], "flow"),
               (1.78, SHOW_PALM[0] + V(-0.003, 0.003, 0.0), turned(SHOW_PALM[1], V(-4.0, 3.0, 0.0)), "flow"),
               (2.02, KNOCK_R[0] + V(0.014, 0.0, 0.0), KNOCK_R[1], "flow"),
               (T_K1, KNOCK_R[0], KNOCK_R[1], {"pos": ("hit", 1.8), "rot": "flow"}),
               (2.22, KNOCK_R[0] + V(0.012, 0.002, 0.0), KNOCK_R[1], "stop"),
               (T_K2, KNOCK_R[0], KNOCK_R[1], {"pos": ("hit", 1.8), "rot": "flow"}),
               (2.46, KNOCK_R[0] + V(0.016, 0.002, 0.002), KNOCK_R[1], "flow"),
               (2.80, GR[0] + V(0.003, 0.004, -0.004), turned(GR[1], V(0.0, 1.5, 0.0)), ("flow", 0.5)),
               (DUR_I, GR[0], GR[1], "stop"))
INS_L = Motion((0.00, GL[0], GL[1], "stop"),
               (0.45, GL[0] + V(-0.012, -0.030, 0.020), turned(GL[1], V(0.0, -8.0, 0.0)), "flow"),
               (1.55, GL[0] + V(-0.010, -0.034, 0.022), turned(GL[1], V(0.0, -8.0, 0.0)), "flow"),
               (2.02, KNOCK_L[0] + V(-0.014, 0.0, 0.0), KNOCK_L[1], "flow"),
               (T_K1, KNOCK_L[0], KNOCK_L[1], {"pos": ("hit", 1.8), "rot": "flow"}),
               (2.22, KNOCK_L[0] + V(-0.012, 0.002, 0.0), KNOCK_L[1], "stop"),
               (T_K2, KNOCK_L[0], KNOCK_L[1], {"pos": ("hit", 1.8), "rot": "flow"}),
               (2.46, KNOCK_L[0] + V(-0.016, 0.002, 0.002), KNOCK_L[1], "flow"),
               (2.80, GL[0] + V(-0.003, 0.004, -0.004), turned(GL[1], V(0.0, 1.5, 0.0)), ("flow", 0.5)),
               (DUR_I, GL[0], GL[1], "stop"))
INS_JOLT = [(T_K1, V(0.003, 0.001, 0.0), 10.0, 0.40), (T_K2, V(0.003, 0.001, 0.0), 10.0, 0.40)]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.40, V(-0.40, 0.30, 0.80)), (1.40, V(-0.30, 0.40, 1.00)),
                   (2.00, V(-0.40, 0.0, 0.0)), (2.50, V(-0.30, 0.0, 0.0)), (DUR_I, V(0, 0, 0)),
                   accents=[(T_K1, V(-0.30, 0.0, 0.0), 9.0, 0.40), (T_K2, V(-0.30, 0.0, 0.0), 9.0, 0.40)])


def inspect_pose(t):
    p = kr.KnucklesPose()
    fists(p, t, {"R": INS_R, "L": INS_L})
    j = rings(t, INS_JOLT, DUR_I)
    offset(p, "R", j)
    offset(p, "L", j * V(-1.0, 1.0, 1.0))
    k = -5.0 * (np.exp(-((t - T_K1) / 0.05) ** 2) + np.exp(-((t - T_K2) / 0.05) ** 2))
    p.curls["R"] = kr.squeeze(k - 2.5 * ease("inout", t, 0.3, 0.6) * (1 - ease("inout", t, 1.8, 2.0)))
    p.curls["L"] = kr.squeeze(k)
    p.shoulder["R"] = V(-0.020, 0.010, -0.010) * ease("inout", t, 0.1, 0.45) * (1 - ease("inout", t, 1.8, 2.1))
    INS_CAM(p, t)
    return p


# -------------------------------------------------------------------------------------- walk
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def walk_pose(t):
    """The guard carried on the steps: the fists drop on each foot strike a few hundredths behind the body and sway
    with the hips, the two a little out of phase."""
    p = kr.KnucklesPose()
    for side, g, ph in (("R", GR, 0.0), ("L", GL, 0.5)):
        b = periodic_bounce(t - 0.03 - 0.01 * ph, DUR_W / 2, 0.10, 1.4)
        sway = np.cos(W_W * (t - 0.05) + np.pi * ph)
        p.fist[side] = near(g, (0.004 * sway, 0.004 - 0.008 * b, 0.003 * b),
                            (1.2 * sway, -1.5 * b, 1.5 * np.sin(W_W * (t - 0.04))))
        p.curls[side] = kr.squeeze(-1.5 * b)
    return p


# ------------------------------------------------------------------------------------ sprint
# running with the fists low: both arms pump in opposition, the fists lingering at the ends of their swing, a hard
# drop on every foot strike
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN = {"R": (V(0.140, -0.215, -0.255), Q((-0.10, 0.20, -0.97), (-0.97, 0.0, -0.10)), V(-0.014, 0.060, -0.070)),
       "L": (V(-0.140, -0.215, -0.255), Q((0.10, 0.20, -0.97), (0.97, 0.0, -0.10)), V(0.014, 0.060, -0.070))}


def _run(t, dur, run, lift, swing):
    p = kr.KnucklesPose()
    w = 2 * np.pi / dur
    b = periodic_bounce(t, dur / 2, 0.12, 1.6)
    for side, sgn in (("R", 1.0), ("L", -1.0)):
        c, q, a = run[side]
        s = pump(sgn * np.cos(w * t))
        lag = sgn * np.cos(w * t - 0.5)
        p.fist[side] = (c + V(0, lift, 0) + a * swing * s + V(0.0, -0.010 * b, 0.003 * b),
                        turned(q, V(2.0 * lag, -10.0 * lag * swing - 2.0 * b, 2.0 * sgn * np.sin(w * t))))
        p.pole[side] = V(0.60 * sgn, -0.70, 0.50)
        p.curls[side] = kr.squeeze(-2.0 * b)
    return p


def sprint_pose(t):
    return _run(t, DUR_S, RUN, 0.0, 1.0)


DUR_T = 0.58


def tac_sprint_pose(t):
    """Tactical sprint: the fists high and driving hard, short and fast."""
    return _run(t, DUR_T, RUN, 0.100, 0.8)


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "knuckles_equip"), (T_CLINK, "knuckles_clink")], fps=60,
         extra_times=(0.24, T_CLINK, 0.40)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.02, "knuckles_swing")], fps=60, extra_times=(0.028, T_HIT, 0.13)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.02, "knuckles_swing")], fps=60, extra_times=(0.028, T_HIT, 0.13)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.15, "knuckles_swing_heavy")], fps=60,
         extra_times=(0.15, T_UP, 0.30)),
    Clip("inspect", DUR_I, inspect_pose, events=[(T_K1, "knuckles_clink"), (T_K2, "knuckles_clink")], fps=60,
         extra_times=(T_K1, T_K2)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
