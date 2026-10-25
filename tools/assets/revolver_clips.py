"""Revolver clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 49).

idle, draw, walk, sprint and tac_sprint are the P9's authored clips run on the revolver's rig (variants: the rig keeps
the P9 rig's interface). Authored here: the double-action shot (the trigger pull cocks the hammer and turns the cylinder
one chamber in the first 22 ms, the hammer falls, the heavy .357 kick, the trigger resets; the cylinder's turn snaps
back one chamber on a STEP channel at the end — invisible, the cylinder is six-fold symmetric), the speed-loader reload
(the thumb latch, the cylinder swung out by the left hand, muzzle up, the ejector rod punched: six cases out; muzzle
down, a loader from the belt, the rounds dropped in, the knob turned, the empty loader tossed, the cylinder closed by
hand) and an inspect (the cylinder opened and spun, a look into the chambers, closed, the gun turned to show its flanks).
Motion: anim.Curve keys (flow / stop / hit / snap), anim.ring for every slam and catch, finger lag (section 31)."""
import numpy as np

import ads_fire
import hands
import revolver_model as rm
import revolver_rig as rig
import variants
from anim import Curve, drift, ring, smoothstep
from vmath import quat_axis_angle, quat_mul, quat_rotate

_P9 = variants.load("revolver__", {"p9_rig": rig}, ["p9_clips", "p9_loco"])
pc, loco = _P9["p9_clips"], _P9["p9_loco"]
V, Clip, Weights, apply_gun, cam, until, squeeze, poses = (pc.V, pc.Clip, pc.Weights, pc.apply_gun, pc.cam, pc.until,
                                                          pc.squeeze, pc.poses)
FINGER_LAG = pc.FINGER_LAG
IDLE, DRAW = pc.IDLE, pc.DRAW
WALK, SPRINT, TAC_SPRINT = loco.WALK, loco.SPRINT, loco.TAC_SPRINT


class StepClip(Clip):
    """A clip whose listed (node, path) channels are baked STEP (gen_jackhammer.bake_clip: clip.step_nodes)."""
    step_nodes = (("cylinder", "rotation"),)


def left_from(p, weights, t, offset=(0, 0, 0), world=None):
    """The support hand from anchor weights (with `world` as a blendable target); the fingers follow a beat late."""
    pos, q, _ = p.blend_hand(weights(t), np.asarray(offset, float), world)
    _, _, curls = p.blend_hand(weights(max(t - FINGER_LAG, 0.0)), (0, 0, 0), world)
    p.hand_l = (pos, q, curls)


# ------------------------------------------------------------------------------------------- fire
# double action: the long pull cocks the hammer and indexes the cylinder (compressed into the first 22 ms: the shot is
# the click), the hammer falls; the kick of a .357 in a 1.2 kg revolver drives the wrists back 8 mm and flips the muzzle
# 5.5 degrees, slower than the P9's (the arms take it); the trigger resets on the way back; the support hand clamps
DUR_F = 0.30
T_FALL = 0.022
F_RESET = DUR_F - 0.004
F_TRIG = Curve((0.0, 0.0), (0.016, -19.0, "hit"), (0.070, -19.0, "stop"), (0.190, -3.0), (DUR_F, 0.0))
F_HAMMER = Curve((0.0, 0.0), (0.017, rig.HAMMER_COCKED, "stop"), (T_FALL, 0.0, "hit"), (DUR_F, 0.0))
F_INDEX = Curve((0.0, 0.0), (0.003, 0.0, ("snap", 1.6)), (0.018, 60.0, ("hit", 1.5)), (0.024, 59.4, "stop"),
                (0.032, 60.0, "stop"), (DUR_F, 60.0))
F_PULL = Curve((0.0, -4.0), (0.016, -12.0, "hit"), (0.070, -12.0, "stop"), (0.190, -3.0), (DUR_F, -1.0))
FIRE_KEYS = sorted(set(F_TRIG.times() + F_HAMMER.times() + F_INDEX.times() + [F_RESET - 0.0004, F_RESET]))


def fire_kick(t):
    """(offset, euler, camera) of the hands' reaction to the shot, faded to exactly zero by the clip's end."""
    fade = until(t, DUR_F, 0.06)
    back = ring(t, T_FALL, 0.0080, 7.5, 0.48)
    rise = ring(t, T_FALL + 0.004, 0.0034, 5.8, 0.45)
    flip = ring(t, T_FALL + 0.002, V(0.7, 5.5, -1.5), 5.2, 0.42)
    off = V(0.0007 * float(rise) / 0.0034, float(rise), float(back))
    view = ring(t, T_FALL, V(0.85, 0.08, 0.16), 5.5, 0.50)
    return off * fade, flip * fade, view * fade


def fire_pose(t):
    p = rig.Pose()
    off, rot, view = fire_kick(t)
    apply_gun(p, off, rot)
    p.trigger = float(F_TRIG(t))
    p.hammer = float(F_HAMMER(t))
    p.cylinder = 0.0 if t >= F_RESET else float(F_INDEX(t))
    pull = float(F_PULL(t))
    p.fingers_r = hands.adjust(hands.POSES["trigger"], index=(pull, pull * 0.6))
    clamp = -5.0 * float(ring(t, T_FALL + 0.010, 1.0, 5.0, 0.7)) * until(t, DUR_F, 0.08)
    p.fingers_l = squeeze(hands.POSES["support"], clamp)
    cam(p, *view)
    return p


FIRE = StepClip("fire", DUR_F, fire_pose, fps=120, extra_times=tuple(FIRE_KEYS))
# aimed (section 31.5): the same shot with the sight picture held, a shove back along the sight line
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE, roll=0.02, lateral=0.006, max_turn=0.005)

# ------------------------------------------------------------------------------------------ reload
# 2.70 s. The right thumb pushes the latch forward and the left hand, coming under the frame, pushes the cylinder out
# (0.22-0.36, it swings open and bounces on its stop); the gun goes muzzle up and the left thumb punches the ejector
# rod (0.60): six cases fall out; muzzle down, the left hand fetches a loader from the belt (off screen), lines it up
# behind the cylinder (1.30), the rounds drop in (1.42), the knob turns (1.50) and the empty loader is pulled away and
# tossed; the left hand closes the cylinder (2.16, it clicks shut) and goes back to the grip.
DUR_R = 2.70
T_OPEN, T_OPENED = 0.22, 0.36
T_PUNCH = 0.60
T_CASES = (0.615, 0.625, 0.635, 0.645, 0.655, 0.665)
T_GONE = 0.69
T_LOADER, T_ALIGN, T_IN, T_TWIST = 1.00, 1.30, 1.42, 1.50
T_AWAY = 1.80
T_CLOSE = 2.16
R_OFF = Curve((0.00, V(0, 0, 0)), (0.20, V(-0.020, 0.012, 0.004)), (0.40, V(-0.034, 0.030, 0.012), "flow"),
              (0.60, V(-0.040, 0.036, 0.020), "stop"), (0.85, V(-0.032, 0.032, 0.012), "flow"),
              (1.10, V(-0.022, 0.040, 0.018), "stop"), (1.50, V(-0.024, 0.042, 0.020), "stop"),
              (1.85, V(-0.024, 0.032, 0.012)), (2.15, V(-0.020, 0.020, 0.006), "stop"),
              (2.45, V(-0.004, 0.002, 0.000)), (DUR_R, V(0, 0, 0), "stop"))
# (yaw, pitch, roll): rolled to the right (top over to the right) so the cylinder swings out into view, muzzle up for the
# punch, then on over onto its right side with the muzzle down: the open cylinder on top, its rear face to the eye
R_ROT = Curve((0.00, V(0, 0, 0)), (0.20, V(-6, 6, -28)), (0.40, V(-10, 30, -34), "flow"),
              (0.60, V(-12, 58, -30), "stop"), (0.86, V(-8, 16, -52), "flow"), (1.10, V(-6, -6, -62), "stop"),
              (1.50, V(-6, -8, -64), "stop"), (1.85, V(-6, -4, -50)), (2.15, V(-6, -2, -34), "stop"),
              (2.45, V(1.0, 0.8, -2.0)), (DUR_R, V(0, 0, 0), "stop"))
R_CRANE = Curve((0.0, 0.0), (T_OPEN, 0.0, ("snap", 1.4)), (T_OPENED, rig.CRANE_OPEN, ("hit", 2.0)),
                (2.02, rig.CRANE_OPEN, "stop"), (T_CLOSE, 0.0, ("hit", 2.2)), (DUR_R, 0.0))
R_CRANE_BOUNCE = [(T_OPENED, -5.0, 9.0, 0.40)]
R_LATCH = Curve((0.0, 0.0), (0.10, 0.0), (0.17, rig.LATCH_TRAVEL, "hit"), (0.40, rig.LATCH_TRAVEL, "stop"),
                (0.52, 0.0), (DUR_R, 0.0))
R_EJECT = Curve((0.0, 0.0), (T_PUNCH - 0.02, 0.0, ("snap", 1.5)), (T_PUNCH + 0.05, rm.EJECTOR_TRAVEL, ("hit", 2.0)),
                (T_PUNCH + 0.10, rm.EJECTOR_TRAVEL, "stop"), (T_PUNCH + 0.16, 0.0, ("hit", 1.5)), (DUR_R, 0.0))
R_CAM = Curve((0.0, V(0, 0, 0)), (0.30, V(0.5, -0.4, -1.4)), (0.60, V(1.4, -0.6, -1.6), "stop"),
              (1.10, V(-1.4, -0.4, -2.0), "stop"), (1.50, V(-1.2, -0.4, -1.8)), (2.15, V(0.2, -0.3, -1.2)),
              (DUR_R, V(0, 0, 0)))
R_KNOCKS = [(T_OPENED, V(0.3, 0.6, 0.8), 8.0, 0.4), (T_PUNCH + 0.05, V(-1.2, 0.0, 0.5), 9.0, 0.4),
            (T_IN, V(0.6, 0.0, 0.2), 9.0, 0.45), (T_CLOSE, V(0.5, -0.4, 1.0), 10.0, 0.4)]
POUCH = V(-0.120, -0.420, -0.120)                 # the belt (off screen): loaders are fetched and tossed here
# left hand: grip -> under the frame pushing the cylinder out -> the cylinder -> the ejector rod -> the belt -> the
# loader at the cylinder -> tossing it -> the cylinder (closing it) -> the grip
R_LEFT = Weights((0.00, {"support": 1.0}), (0.08, {"support": 1.0}), (0.24, {"cradle": 1.0}), (0.40, {"cylinder": 1.0}),
                 (0.52, {"ejector": 1.0}), (T_PUNCH + 0.12, {"ejector": 1.0}), (0.92, {"world": 1.0}),
                 (T_LOADER, {"world": 1.0}), (1.20, {"loader": 1.0}), (T_TWIST + 0.08, {"loader": 1.0}),
                 (1.86, {"world": 1.0}), (1.98, {"cylinder": 1.0}), (T_CLOSE + 0.04, {"cylinder": 1.0}),
                 (2.42, {"support": 1.0}), (DUR_R, {"support": 1.0}))


def _cylinder_in_gun(p):
    """(origin, quat) of cylinder space in gun space."""
    cq = p.crane_q()
    origin = rig.NODES["crane"][1] + quat_rotate(cq, rig.CYL_LOCAL)
    return origin, quat_mul(cq, quat_axis_angle((0, 0, 1), p.cylinder))


def _loader_pose(p, t):
    """The speed loader (gun space): in the hand from the belt, lined up behind the open cylinder, pushed in, pulled
    away and tossed."""
    o, q = _cylinder_in_gun(p)
    seat = o + quat_rotate(q, (0.0, 0.0, rm.CYL_REAR - rm.CYL_FRONT + 0.0006))
    back = quat_rotate(q, (0.0, 0.0, 1.0))
    if t < T_ALIGN:
        u = smoothstep(T_LOADER, T_ALIGN, t)
        far = seat + back * 0.050 + quat_rotate(q, (-0.020, -0.040, 0.0))
        pos = far + (seat + back * 0.045 - far) * u
    elif t < T_IN:
        u = smoothstep(T_ALIGN, T_IN, t)
        pos = seat + back * 0.045 * (1 - u) ** 1.6     # the rounds slide down into the chambers
    elif t < T_TWIST + 0.06:
        pos = seat
    else:
        u = smoothstep(T_TWIST + 0.06, T_AWAY, t)
        pos = seat + back * (0.045 * u) + quat_rotate(q, (-0.060 * u * u, -0.090 * u * u, 0.0))
    twist = 25.0 * smoothstep(T_TWIST - 0.04, T_TWIST + 0.03, t)
    return pos, quat_mul(q, quat_axis_angle((0, 0, 1), twist))


def reload_pose(t):
    p = rig.Pose()
    knock = sum((ring(t, t0, a, f, z) for t0, a, f, z in R_KNOCKS), V(0, 0, 0)) * until(t, DUR_R, 0.10)
    apply_gun(p, R_OFF(t) + 0.0006 * knock, R_ROT(t) + knock)
    p.latch = float(R_LATCH(t))
    p.crane = float(R_CRANE(t)) + float(sum(ring(t, t0, a, f, z) for t0, a, f, z in R_CRANE_BOUNCE)) * (t < 1.9)
    p.ejector = float(R_EJECT(t))
    p.rounds_s = 0.0 if T_GONE <= t < T_TWIST + 0.02 else 1.0
    if T_LOADER <= t < T_AWAY:
        p.mag_s = 1.0
        p.mag_t, p.mag_q = _loader_pose(p, t)
        p.loader_rounds_s = 1.0 if t < T_TWIST + 0.02 else 0.0
    # right thumb on the latch, then back round the grip
    press = smoothstep(0.06, 0.16, t) * (1 - smoothstep(0.40, 0.56, t))
    p.fingers_r = hands.adjust(hands.POSES["indexed"], thumb=(22.0 * press, 10.0 * press))
    world = p.world_target(POUCH + V(0.0, 0.02 * np.sin(6 * t), 0.0), back=(-0.2, -1.0, 0.3), fwd=(0.0, 0.3, -1.0),
                           side="L", pose="pinch")
    left_from(p, R_LEFT, t, world=world)
    # the thumb's punch on the rod
    if T_PUNCH - 0.08 < t < T_PUNCH + 0.18:
        k = smoothstep(T_PUNCH - 0.05, T_PUNCH + 0.04, t) * (1 - smoothstep(T_PUNCH + 0.08, T_PUNCH + 0.18, t))
        p.fingers_l = hands.adjust(p.hand_l[2], thumb=(-14.0 * k, -10.0 * k))
    cam(p, *(R_CAM(t) + 0.35 * knock * V(1, 0.5, 0.5)))
    return p


RELOAD_EVENTS = [(0.08, "inspect_move"), (0.17, "revolver_latch"), (T_OPEN + 0.02, "cylinder_open"),
                 (T_PUNCH, "ejector")] + [(tc, "case_out") for tc in T_CASES] + \
    [(0.95, "inspect_move"), (T_IN, "speedloader"), (T_TWIST, "speedloader_twist"), (T_CLOSE, "cylinder_close")]
RELOAD = Clip("reload", DUR_R, reload_pose, events=RELOAD_EVENTS,
              extra_times=(T_OPENED, T_PUNCH, T_GONE, T_IN, T_TWIST, T_TWIST + 0.02, T_CLOSE))

# ----------------------------------------------------------------------------------------- inspect
# 3.60 s: the gun raised and rolled to show the cylinder's side, the latch, the cylinder swung out; a flick of the left
# fingers spins it (three turns, slowing on its own friction), a look down at the chambers; closed by the palm with a
# click; the gun turned to show the right flank; back to the ready.
DUR_I = 3.60
T_I_OPEN, T_SPIN, T_SPIN_END, T_I_CLOSE = 0.46, 0.70, 1.75, 2.24
I_OFF = Curve((0.00, V(0, 0, 0)), (0.36, V(-0.030, 0.034, 0.020), "stop"), (0.70, V(-0.032, 0.036, 0.018)),
              (1.40, V(-0.030, 0.034, 0.016), "stop"), (1.95, V(-0.026, 0.030, 0.012)), (2.30, V(-0.022, 0.028, 0.012), "stop"),
              (2.75, V(0.010, 0.030, 0.010), "stop"), (3.10, V(0.008, 0.026, 0.008), "stop"), (DUR_I, V(0, 0, 0), "stop"))
I_ROT = Curve((0.00, V(0, 0, 0)), (0.36, V(-16, 10, -40), "stop"), (0.70, V(-18, 12, -44)),
              (1.40, V(-20, 14, -42), "stop"), (1.95, V(-24, 26, -30)), (2.30, V(-14, 12, -32), "stop"),
              (2.75, V(26, 8, 34), "stop"), (3.10, V(24, 6, 30), "stop"), (DUR_I, V(0, 0, 0), "stop"))
I_CRANE = Curve((0.0, 0.0), (T_I_OPEN - 0.10, 0.0, ("snap", 1.4)), (T_I_OPEN, rig.CRANE_OPEN, ("hit", 2.0)),
                (2.08, rig.CRANE_OPEN, "stop"), (T_I_CLOSE, 0.0, ("hit", 2.2)), (DUR_I, 0.0))
I_LATCH = Curve((0.0, 0.0), (0.24, 0.0), (0.32, rig.LATCH_TRAVEL, "hit"), (0.52, rig.LATCH_TRAVEL, "stop"),
                (0.64, 0.0), (DUR_I, 0.0))
I_SPIN = Curve((0.0, 0.0), (T_SPIN, 0.0, ("snap", 2.4)), (T_SPIN_END, 1080.0, "stop"), (DUR_I, 1080.0))
I_CAM = Curve((0.0, V(0, 0, 0)), (0.36, V(0.6, -0.6, -1.6), "stop"), (1.40, V(0.9, -0.5, -1.4)),
              (1.95, V(1.2, -0.4, -1.0)), (2.75, V(0.4, 0.6, 1.4), "stop"), (3.10, V(0.3, 0.5, 1.2)),
              (DUR_I, V(0, 0, 0)))
I_KNOCKS = [(T_I_OPEN, V(0.4, 0.8, 1.0), 8.0, 0.4), (T_SPIN, V(0.0, -0.6, -0.4), 9.0, 0.5),
            (T_I_CLOSE, V(0.5, -0.5, 1.2), 10.0, 0.4)]
I_LEFT = Weights((0.00, {"support": 1.0}), (0.10, {"support": 1.0}), (0.36, {"cradle": 1.0}), (0.56, {"cylinder": 1.0}),
                 (0.64, {"cylinder": 1.0}), (0.86, {"support": 1.0}), (1.90, {"support": 1.0}), (2.12, {"cylinder": 1.0}),
                 (T_I_CLOSE + 0.04, {"cylinder": 1.0}), (2.52, {"support": 1.0}), (DUR_I, {"support": 1.0}))


def inspect_pose(t):
    p = rig.Pose()
    knock = sum((ring(t, t0, a, f, z) for t0, a, f, z in I_KNOCKS), V(0, 0, 0)) * until(t, DUR_I, 0.10)
    hold = drift(t, V(0.0012, 0.0010, 0.0008), seed=49, start=0.4, end=3.3)
    apply_gun(p, I_OFF(t) + hold + 0.0006 * knock, I_ROT(t) + knock + 400 * hold)
    p.latch = float(I_LATCH(t))
    p.crane = float(I_CRANE(t)) + float(ring(t, T_I_OPEN, -5.0, 9.0, 0.4)) * (t < 2.0)
    p.cylinder = float(I_SPIN(t))
    press = smoothstep(0.20, 0.30, t) * (1 - smoothstep(0.52, 0.68, t))
    p.fingers_r = hands.adjust(hands.POSES["indexed"], thumb=(22.0 * press, 10.0 * press))
    left_from(p, I_LEFT, t)
    # the flick: the fingers brush the cylinder's flank
    flick = np.exp(-((t - T_SPIN + 0.02) / 0.05) ** 2)
    p.fingers_l = hands.adjust(p.hand_l[2], index=(-30.0 * flick, -20.0 * flick), middle=(-30.0 * flick, -20.0 * flick))
    cam(p, *(I_CAM(t) + 0.35 * knock * V(1, 0.5, 0.5)))
    return p


INSPECT_EVENTS = [(0.10, "inspect_move"), (0.32, "revolver_latch"), (T_I_OPEN - 0.08, "cylinder_open"),
                  (T_SPIN, "cylinder_spin"), (T_I_CLOSE, "cylinder_close"), (2.50, "inspect_move")]
INSPECT = Clip("inspect", DUR_I, inspect_pose, events=INSPECT_EVENTS, extra_times=(T_I_OPEN, T_SPIN, T_I_CLOSE))


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_ADS, RELOAD, INSPECT, WALK, SPRINT, TAC_SPRINT]
