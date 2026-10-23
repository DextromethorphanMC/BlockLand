"""Thompson clips (VIEWMODEL_SPEC sections 4, 15-17, 24 and 31): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> tommy_rig.Pose (palm frames + finger curls); gen_tommy bakes
them (IK included) at >= 60 samples/s (fire at 240).

Authored to section 31 with anim.Curve (C1 through its keys: the motion carries through a key unless it is an extreme,
a stop or a hit): a heavy, old-school SMG. Every big move has its anticipation, the hands' pushes and the cocking
knob's slams ring through the gun (anim.ring) and the camera, holds drift (anim.drift), fingers and the support arm
follow the gun. Both magazines ride the `mag` pivot straight out of the well; the support hand holds either by its left
face (tommy_rig "mag_side": the same hold suits the stick and the drum), pulls the old one and lets it fall (gravity
and tumble in viewmodel space, off the gun's motion), fetches the new one from below the screen, lines it up under the
well and drives it home."""
import numpy as np

import hands
import tommy_model as tm
import tommy_rig as rig
from anim import Curve, Follow, drift, qexp, ring, smoothstep, window
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import frame_quat, norm, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
TRAVEL = tm.BOLT_TRAVEL
ZERO = V(0, 0, 0)
GRAVITY = V(0.0, -9.8, 0.0)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def gun_at(off, rot):
    """(gun translation, rotation) of an offset / euler pair, without a Pose."""
    return rig.GUN_T + np.asarray(off, float), quat_mul(rig.GUN_Q, quat_euler(*rot))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def until(t, t1, fade=0.05):
    """1, easing to 0 by t1: rings that must be gone at the end of a clip."""
    return 1.0 - smoothstep(t1 - fade, t1, t)


def squeeze(curls, amount, fingers=("index", "middle", "ring", "pinky")):
    """The wrapping fingers a little tighter (negative closes): a grip taking up a force."""
    return hands.adjust(curls, **{f: (amount, 0.5 * amount) for f in fingers})


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0), grip=0.0):
    c = hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)
    return squeeze(c, grip, ("middle", "ring", "pinky")) if grip else c


def fore_fingers(t, period=2.0, amount=1.0, grip=0.0):
    w = 2 * np.pi * t / period
    c = hands.adjust(hands.POSES["grip"], index=(-2 * amount * np.sin(w + 0.7), 0),
                     middle=(-1.5 * amount * np.sin(w + 0.4), 0), thumb=(2 * amount * np.sin(w + 1.1), 0))
    return squeeze(c, grip) if grip else c


def open_fingers(curls, o, pose="spread"):
    """Blend finger curls towards an open hand (o = 0..1)."""
    if o <= 0:
        return curls
    return {f: (1 - o) * np.asarray(curls[f], float) + o * np.asarray(hands.POSES[pose][f], float)
            for f in hands.FINGERS}


def hand_on_mag(p, mag_t, mag_q, which="mag_side"):
    """The support hand's target on a magazine pose that need not be the `mag` node's (the hand keeps its grip shape
    while the old magazine falls on its own)."""
    keep = p.mag_t, p.mag_q
    p.mag_t, p.mag_q = mag_t, mag_q
    target = p.anchor(which)
    p.mag_t, p.mag_q = keep
    return target


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0010 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.16 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = fore_fingers(t)
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen low right for the first frames (sinking a little: the wind-up), then whipped up muzzle first - the turn
# peaks past the ready pose at 0.29 s, the lift at 0.34 s - and settled; the support hand catches the foregrip at 0.39 s
# (a hit, a squeeze) and the camera dips with the lift and bobs back
T_CATCH = 0.39
DRAW_OFF = Curve((0.00, V(0.050, -0.270, 0.090)), (0.07, V(0.054, -0.284, 0.095)),
                 (0.20, V(0.021, -0.090, 0.032), "flow"), (0.34, V(-0.0055, 0.0090, -0.0065)),
                 (0.47, V(0.0013, -0.0023, 0.0010)), (0.60, ZERO))
DRAW_ROT = Curve((0.00, V(-16, -48, -30)), (0.07, V(-17, -52, -33)), (0.17, V(-6.5, -15, -12.5), "flow"),
                 (0.29, V(1.9, 4.0, 2.8)), (0.44, V(-0.5, -1.0, -0.6)), (0.60, ZERO))
DRAW_CAM = Curve((0.00, ZERO), (0.24, V(-0.80, 0.05, -0.55)), (0.41, V(0.40, 0.0, 0.25)), (0.60, ZERO))
DRAW_LW = Curve((0.00, V(-0.012, -0.046, 0.032)), (0.24, V(-0.008, -0.026, 0.018), "flow"),
                (T_CATCH, V(0.0, 0.0012, 0.0), "hit"), (0.46, V(0.0, -0.0010, 0.0)), (0.56, ZERO))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.27, {"spread": 1.0}), (T_CATCH + 0.02, {"grip": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.30, {"grip": 1.0}), (0.46, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    grab = -7.0 * window(t, T_CATCH, T_CATCH + 0.16, 0.06)            # the catch: fingers clamp, then relax
    p.fingers_l = squeeze(poses(max(t - FINGER_LAG, 0.0), DRAW_FL), grab)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.60, draw_pose, events=[(0.0, "equip")], extra_times=(0.07, T_CATCH))

# ------------------------------------------------------------------------------------------- fire
# 720 rpm, open bolt: the trigger breaks, the knob (riding on the bolt) is blown back hard into the buffer (a hit with
# a small bounce), the spring drives it forward to slam home (a hit, a 1.5 mm rebound). The hands only answer the
# shot - the wrists give 4.5 mm and 1.7 deg and come back a hair past rest - and the slam rings through the gun; the
# runtime adds the recoil springs at the root. Everything is back at rest at 0.083 s (the next shot's start).
F_END = 0.083
T_BUFFER, T_HOME = 0.018, 0.058
F_BOLT = Curve((0.0, 0.0), (0.002, 0.0, ("snap", 2.5)), (T_BUFFER, TRAVEL, ("hit", 1.5)),
               (0.023, TRAVEL - 0.0040, "stop"), (T_HOME, 0.0, ("hit", 2.2)), (0.064, 0.0015, "stop"),
               (0.073, 0.0), (F_END, 0.0))
F_TRIG = Curve((0.0, 0.0), (0.006, rig.TRIGGER_PULL, "hit"), (0.052, rig.TRIGGER_PULL, "stop"), (F_END, 0.0))
F_KICK_T = Curve((0.0, ZERO), (0.002, ZERO, ("snap", 2.0)), (0.012, V(0.0004, 0.0016, 0.0045)),
                 (0.042, V(-0.0001, -0.0003, -0.0006)), (F_END, ZERO))
F_KICK_R = Curve((0.0, ZERO), (0.002, ZERO, ("snap", 2.0)), (0.014, V(0.25, 1.7, -0.55)),
                 (0.044, V(-0.05, -0.25, 0.08)), (F_END, ZERO))
F_CAM = Curve((0.0, ZERO), (0.016, V(0.30, 0.04, 0.10)), (F_END, ZERO))
FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + F_KICK_T.times() + F_KICK_R.times()))


def fire_pose(t):
    p = rig.Pose()
    fade = until(t, F_END, 0.018)
    slam_t = ring(t, T_HOME, V(0.0, -0.0004, -0.0007), 11.0, 0.45) * fade
    slam_r = ring(t, T_HOME, V(0.05, -0.35, 0.12), 11.0, 0.45) * fade
    apply_gun(p, F_KICK_T(t) + slam_t, F_KICK_R(t) + slam_r)
    p.bolt_z = float(F_BOLT(t))
    p.trigger = float(F_TRIG(t))
    k = p.trigger / rig.TRIGGER_PULL                          # the finger pad rides the trigger back
    p.fingers_r = squeeze(hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k)),
                          -3.0 * window(t, 0.004, 0.060, 0.02), ("middle", "ring", "pinky"))
    clamp = -4.5 * np.exp(-((t - 0.020) / 0.022) ** 2)       # the support hand clamps on the foregrip
    p.fingers_l = squeeze(hands.POSES["grip"], clamp, ("index", "middle", "ring"))
    cam(p, *F_CAM(t))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)    # open bolt: it closes on empty

# ------------------------------------------------------------------------------- magazine change
# the support hand leaves the foregrip (fingers open), thumbs the catch (the press jolts the gun), slides down onto
# the magazine's left face, pulls it out against the catch (the gun dips with the pull and springs up as it lets go),
# flicks it away and lets it fall; it fetches the new one below the screen, brings it up on an arc, lines it up under
# the well (slow), gathers and drives it home (a hit: the gun and camera jolt up), tugs once to check, and swings back
# to the foregrip
T_RELEASE, T_OUT, T_SEAT = 0.34, 0.50, 1.52          # events mag_release / mag_out / mag_in
T_LET = 0.55                                         # the hand lets the old magazine go: it falls from here
T_FREE = 0.485                                       # the magazine comes free of the catch
T_TUG = 1.585
T_HIDE, T_SHOW = 0.80, 0.98                          # magazine scale 0 / 1 (off screen, in the left hand)
AWAY = V(-0.100, -0.360, 0.100)                     # off screen (gun space): where the hand swaps magazines
# the magazine in the support hand, gun space (offset from its seat) and its turn (degrees)
CARRY_OFF = Curve((0.00, ZERO), (0.36, ZERO), (0.41, V(0.0, -0.006, 0.0005)), (T_OUT, V(-0.002, -0.050, 0.002)),
                  (T_LET, V(-0.012, -0.092, 0.008), "flow"), (0.86, AWAY), (T_SHOW, AWAY),
                  (1.20, V(-0.016, -0.080, -0.006), "flow"), (1.31, V(-0.002, -0.044, -0.004)),
                  (1.40, V(0.0, -0.028, -0.001)), (1.44, V(0.0, -0.025, 0.0)), (T_SEAT, ZERO, "hit"))
CARRY_SPIN = Curve((0.00, ZERO), (0.43, ZERO), (T_OUT, V(1.5, -2.0, 4.0)), (T_LET, V(5, -6, 12), "flow"),
                   (0.86, V(14, -20, 40)), (T_SHOW, V(12, -18, 34)), (1.20, V(3, -5, 10), "flow"),
                   (1.31, V(0.8, -1.5, 3.5)), (1.40, V(0.2, -0.4, 1.0)), (1.44, V(0.1, -0.2, 0.5)), (T_SEAT, ZERO))
TUMBLE = V(-6.0, 3.0, 9.0)                          # rad/s, viewmodel space: the dropped magazine turns as it falls


def carrier(t):
    """The magazine the support hand holds (or would hold): (mag translation, rotation) in gun space."""
    return MAG_REST + CARRY_OFF(t), quat_euler(*CARRY_SPIN(t))


class Drop:
    """The old magazine after the hand lets go at T_LET: it leaves with the hand's velocity and falls under gravity,
    tumbling, in viewmodel space (the gun moves on without it)."""

    def __init__(self, gun_fn):
        def world(t):
            gt, gq = gun_fn(t)
            mt, mq = carrier(t)
            return gt + quat_rotate(gq, mt), quat_mul(gq, mq)

        self.w0, self.q0 = world(T_LET)
        self.v0 = (self.w0 - world(T_LET - 0.004)[0]) / 0.004

    def __call__(self, t, gun_t, gun_q):
        tau = t - T_LET
        w = self.w0 + self.v0 * tau + 0.5 * GRAVITY * tau * tau
        q = quat_mul(qexp(TUMBLE * tau), self.q0)
        inv = quat_conj(gun_q)
        return quat_rotate(inv, w - gun_t), quat_mul(inv, q)


def place_mag(p, t, drop):
    if t < T_LET or t >= T_SHOW:
        p.mag_t, p.mag_q = carrier(t)
    else:
        p.mag_t, p.mag_q = drop(t, p.gun_t, p.gun_q)
    if T_HIDE <= t < T_SHOW:
        p.mag_s = 0.0


L_MAG = Weights((0.00, {"support": 1}), (0.08, {"support": 1}), (0.27, {"mag_release": 1}),
                (0.33, {"mag_release": 1}), (0.40, {"world": 1}), (1.56, {"world": 1}), (1.90, {"support": 1}))
L_MAG_OFF = Curve((0.00, ZERO), (0.08, ZERO), (0.175, V(-0.022, -0.032, 0.018), "flow"), (0.27, ZERO), (1.56, ZERO),
                  (1.72, V(-0.022, -0.032, 0.014), "flow"), (1.90, V(0.0, 0.0013, 0.0), "hit"),
                  (1.98, V(0.0, -0.0009, 0.0)), (2.08, ZERO))
L_OPEN = Curve((0.00, 0.0), (0.06, 0.0), (0.13, 1.0), (0.21, 0.8), (0.29, 0.0), (0.53, 0.0), (0.58, 1.0, "stop"),
               (0.88, 1.0), (0.95, 0.0), (1.55, 0.0), (1.61, 1.0), (1.80, 0.75), (1.90, 0.0, "hit"))
THUMB_PRESS = Curve((0.0, 0.0), (0.29, 0.0), (T_RELEASE, -16.0, "hit"), (0.40, -3.0), (0.46, 0.0))
L_CLAMP = lambda t: -8.0 * window(t, T_SEAT - 0.02, T_TUG + 0.03, 0.03) - 6.0 * window(t, 1.90, 2.10, 0.05)
# the support shoulder drops back and out while the hand works under the gun (it reaches the magazine)
SH_MAG = Curve((0.00, ZERO), (0.08, ZERO), (0.30, V(0.020, -0.040, 0.150)), (1.56, V(0.020, -0.040, 0.150)),
               (1.90, ZERO))


def mag_hands(p, t):
    """Support hand for the magazine change (0 .. 1.90 s and on)."""
    left_hand(p, L_MAG, t, L_MAG_OFF(t), world=hand_on_mag(p, *carrier(t)))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(L_OPEN(t)))
    curls = squeeze(hands.adjust(curls, thumb=(float(THUMB_PRESS(t)), 0.0)), L_CLAMP(t))
    p.hand_l = (pos, q, curls)


def mag_reactions(t):
    """The gun answering the support hand: the catch press, the pull and the magazine coming free, the seat, the tug;
    and the moving hold while the hand is away. -> (offset, euler, camera)."""
    pull = window(t, 0.38, T_FREE, 0.04)
    off = (ring(t, T_RELEASE, V(0.0010, 0.0002, 0.0), 7.0, 0.40) + ring(t, T_FREE, V(0.0, 0.0020, 0.0), 6.5, 0.40)
           + ring(t, T_SEAT, V(0.0004, 0.0035, -0.0006), 7.0, 0.32) + ring(t, T_TUG, V(0.0, -0.0014, 0.0), 8.0, 0.40)
           + V(0.0, -0.0022, 0.0) * pull
           + drift(t, V(0.0010, 0.0008, 0.0006), seed=3, freq=0.55, start=0.40, end=1.46, fade=0.20))
    rot = (ring(t, T_RELEASE, V(-0.4, 0.2, 0.9), 7.0, 0.40) + ring(t, T_FREE, V(0.0, 0.8, 0.6), 6.5, 0.40)
           + ring(t, T_SEAT, V(-0.5, 2.2, -1.6), 7.0, 0.32) + ring(t, T_TUG, V(0.0, -0.6, 0.3), 8.0, 0.40)
           + V(0.0, -0.6, -1.0) * pull
           + drift(t, V(0.6, 0.5, 0.9), seed=4, freq=0.50, start=0.40, end=1.46, fade=0.20))
    camera = (ring(t, T_RELEASE, V(0.15, 0.0, 0.30), 6.0, 0.45) + ring(t, T_FREE, V(0.35, 0.0, 0.25), 6.0, 0.40)
              + ring(t, T_SEAT, V(1.00, -0.15, 0.80), 6.0, 0.35) + ring(t, T_TUG, V(-0.30, 0.0, -0.20), 7.0, 0.45))
    return off, rot, camera


# ---------------------------------------------------------------------------------------- reload
# anticipation (the gun dips and leans the other way), then a swing up and in that rolls the well into view (past it,
# then settled), the hold while the hand works (drifting, the well turned towards the incoming magazine), and after
# the seat a swing back down to ready that passes it and settles
PRESENT_OFF = [(0.00, ZERO), (0.10, V(0.0025, -0.0035, 0.0020)), (0.32, V(-0.040, 0.033, -0.007)),
               (0.42, V(-0.037, 0.030, -0.006)), (1.25, V(-0.037, 0.027, -0.006)), (1.44, V(-0.038, 0.029, -0.007))]
PRESENT_ROT = [(0.00, ZERO), (0.10, V(-0.7, -1.2, 2.5)), (0.32, V(4.4, 8.8, -28.0)), (0.42, V(4.0, 8.0, -25.5)),
               (1.25, V(4.0, 8.4, -27.0)), (1.44, V(3.8, 8.6, -27.5))]
R_OFF = Curve(*PRESENT_OFF, (1.62, V(-0.036, 0.030, -0.006)), (1.80, V(-0.016, 0.012, -0.002)),
              (2.00, V(0.003, -0.004, 0.002)), (2.16, V(-0.0007, 0.0009, -0.0003)), (2.30, ZERO))
R_ROT = Curve(*PRESENT_ROT, (1.62, V(3.6, 7.0, -24.0)), (1.80, V(1.4, 3.0, -9.0)), (2.00, V(-0.8, -1.4, 3.2)),
              (2.16, V(0.2, 0.35, -0.7)), (2.30, ZERO))
R_CAM = Curve((0.00, ZERO), (0.10, V(0.15, 0.0, 0.40)), (0.36, V(-0.60, 0.30, -1.80)), (1.20, V(-0.50, 0.25, -1.50)),
              (1.46, V(-0.65, 0.30, -1.90)), (1.70, V(-0.40, 0.20, -1.10)), (2.02, V(0.20, -0.10, 0.50)), (2.30, ZERO))


def reload_gun(t):
    off, rot, _ = mag_reactions(t)
    return gun_at(R_OFF(t) + off, R_ROT(t) + rot)


R_DROP = Drop(reload_gun)


def reload_pose(t):
    p = rig.Pose()
    off, rot, camera = mag_reactions(t)
    fade = until(t, 2.30, 0.15)
    apply_gun(p, R_OFF(t) + off * fade, R_ROT(t) + rot * fade)
    p.shoulder_l = SH_MAG(t)
    place_mag(p, t, R_DROP)
    mag_hands(p, t)
    seat = -4.0 * window(t, T_SEAT - 0.02, T_SEAT + 0.12, 0.03)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.8, 2.1)), 0),
                               grip=seat)
    cam(p, *(R_CAM(t) + camera * fade))
    return p


MAG_TIMES = (0.10, T_RELEASE, T_FREE, T_OUT, T_LET, T_HIDE, T_SHOW, 1.44, T_SEAT, T_TUG, 1.90)
RELOAD = Clip("reload", 2.30, reload_pose,
              events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=MAG_TIMES)

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change; then the gun rolls up towards the support hand, which goes up over the receiver, hooks the
# cocking knob, hauls it back against the spring (slow, then fast into the rear stop: the gun is dragged back towards
# the hand), lets go: the bolt slams home (the gun jolts forward, the muzzle dips and rings, the camera kicks) and the
# hand flicks off and swings back to the foregrip
T_GRAB, T_PULL, T_BACK, T_LET2, T_FWD = 1.93, 1.99, 2.10, 2.135, 2.17    # events bolt_back @ T_BACK, bolt_forward @ T_FWD
E_OFF = Curve(*PRESENT_OFF, (1.62, V(-0.034, 0.028, -0.010)), (1.92, V(-0.012, 0.006, -0.040)),
              (T_BACK, V(-0.014, 0.007, -0.033)), (2.32, V(-0.008, 0.003, -0.028)), (2.56, V(0.003, -0.004, 0.002)),
              (2.68, V(-0.0006, 0.0008, -0.0003)), (2.80, ZERO))
E_ROT = Curve(*PRESENT_ROT, (1.62, V(3.2, 6.5, -22.0)), (1.92, V(2.0, 5.0, -26.0)), (T_BACK, V(1.6, 5.6, -27.5)),
              (2.32, V(1.2, 3.4, -17.0)), (2.56, V(-0.8, -1.4, 3.0)), (2.68, V(0.2, 0.3, -0.6)), (2.80, ZERO))
E_CAM = Curve((0.00, ZERO), (0.10, V(0.15, 0.0, 0.40)), (0.36, V(-0.60, 0.30, -1.80)), (1.20, V(-0.50, 0.25, -1.50)),
              (1.46, V(-0.65, 0.30, -1.90)), (1.62, V(-0.40, 0.20, -1.30)), (1.92, V(-0.30, 0.30, -1.60)),
              (T_BACK, V(-0.10, 0.40, -1.40)), (2.32, V(-0.20, 0.10, -0.80)), (2.56, V(0.20, -0.10, 0.40)),
              (2.80, ZERO))
E_BOLT = Curve((0.0, 0.0), (T_PULL, 0.0, "stop"), (2.05, 0.030), (T_BACK, TRAVEL, ("hit", 1.6)),
               (2.115, TRAVEL - 0.0025), (T_LET2, TRAVEL - 0.0015, "stop"), (T_FWD, 0.0, ("hit", 2.2)),
               (T_FWD + 0.007, 0.0014, "stop"), (T_FWD + 0.018, 0.0))
E_LEFT = Weights((1.60, {"mag_side": 1}), (1.90, {"charging": 1}), (T_LET2, {"charging": 1}), (2.26, {"world": 1}),
                 (2.56, {"support": 1}))
E_LEFT_OFF = Curve((1.60, ZERO), (1.76, V(-0.030, 0.012, 0.004), "flow"), (1.90, ZERO), (T_LET2, ZERO),
                   (2.24, V(-0.008, -0.026, -0.004), "flow"), (2.42, V(-0.006, -0.012, 0.002)),
                   (2.56, V(0.0, 0.0012, 0.0), "hit"), (2.64, V(0.0, -0.0009, 0.0)), (2.74, ZERO))
E_OPEN = Curve((1.60, 0.0), (1.66, 1.0), (1.86, 0.9), (T_GRAB, 0.0, "hit"), (T_LET2, 0.0, "stop"),
               (T_LET2 + 0.03, 1.0, "stop"), (2.46, 1.0), (2.56, 0.0, "hit"))
E_CLAMP = lambda t: -6.0 * window(t, T_GRAB, T_LET2, 0.03) - 6.0 * window(t, 2.56, 2.76, 0.05)
SH_EMPTY = Curve((0.00, ZERO), (0.08, ZERO), (0.30, V(0.020, -0.040, 0.150)), (1.56, V(0.020, -0.040, 0.150)),
                 (1.92, V(0.120, -0.040, 0.020)), (T_LET2 + 0.05, V(0.120, -0.040, 0.020)), (2.58, ZERO))


def bolt_reactions(t):
    """The gun answering the cocking: the hook, the haul back (dragged towards the hand), the rear stop, the slam."""
    haul = window(t, T_PULL, T_BACK + 0.02, 0.04)
    off = (ring(t, T_GRAB, V(0.0, 0.0006, 0.0), 8.0, 0.45) + V(-0.0015, 0.0, 0.0030) * haul
           + ring(t, T_BACK, V(0.0, 0.0, 0.0012), 9.0, 0.40) + ring(t, T_FWD, V(0.0003, -0.0012, -0.0030), 8.0, 0.33))
    rot = (ring(t, T_GRAB, V(0.0, 0.0, 0.4), 8.0, 0.45) + V(-1.0, 0.3, -0.8) * haul
           + ring(t, T_BACK, V(-0.3, 0.4, 0.0), 9.0, 0.40) + ring(t, T_FWD, V(0.4, -2.0, 1.1), 8.0, 0.33))
    camera = ring(t, T_BACK, V(0.30, 0.0, 0.20), 7.0, 0.45) + ring(t, T_FWD, V(-1.10, 0.20, -0.90), 6.0, 0.35)
    return off, rot, camera


def empty_gun(t):
    off, rot, _ = mag_reactions(t)
    return gun_at(E_OFF(t) + off, E_ROT(t) + rot)


E_DROP = Drop(empty_gun)


def let_go(p):
    """Off-hand spot left of and below the released knob: the hand springs open and drops away (it does not fly up
    at the camera)."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.022, -0.012, 0.012)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    off, rot, camera = mag_reactions(t)
    b_off, b_rot, b_cam = bolt_reactions(t)
    fade = until(t, 2.80, 0.15)
    apply_gun(p, E_OFF(t) + (off + b_off) * fade, E_ROT(t) + (rot + b_rot) * fade)
    p.shoulder_l = SH_EMPTY(t)
    w = ease("inout", t, 1.70, 1.92) * (1 - ease("inout", t, 2.26, 2.56))    # elbow down: the hand comes up over the top
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * V(-0.30, -0.95, 0.10)
    place_mag(p, t, E_DROP)
    p.bolt_z = float(E_BOLT(t))
    if t < 1.60:
        mag_hands(p, t)
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        p.hand_l = (pos, q, squeeze(open_fingers(curls, float(E_OPEN(t))), E_CLAMP(t)))
    seat = -4.0 * window(t, T_SEAT - 0.02, T_SEAT + 0.12, 0.03) - 4.0 * window(t, T_FWD - 0.01, T_FWD + 0.10, 0.03)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.3, 2.6)), 0),
                               grip=seat)
    cam(p, *(E_CAM(t) + (camera + b_cam) * fade))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.80, reload_empty_pose,
                    events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in"),
                            (T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=MAG_TIMES + (T_GRAB, T_PULL, T_BACK, T_LET2, T_FWD, T_FWD + 0.007))

# --------------------------------------------------------------------------------------- inspect
# a wind-up dip, the swing up to show the left side (the drum's key, the levers) - past it and settled, then held,
# drifting, the fingers drumming on the foregrip; a gather and the heavy roll over to the right side (port up), dipping
# under on the way and overshooting the roll; the support hand hooks the knob and draws it back to glance into the port,
# holds it there (it jiggles in the hand), lets it slam home (ring, camera kick); then the gun swings back down to
# ready, past it, and settles
I_BACK, I_FWD = 2.36, 2.75
I_OFF = Curve((0.00, ZERO), (0.09, V(0.003, -0.005, 0.002)), (0.50, V(-0.0585, 0.0245, -0.0625)),
              (0.66, V(-0.0560, 0.0228, -0.0598)), (1.32, V(-0.0572, 0.0255, -0.0620)),
              (1.42, V(-0.0590, 0.0240, -0.0640)), (1.70, V(-0.0730, -0.0300, -0.0680), "flow"),
              (1.92, V(-0.0845, -0.0525, -0.0700)), (2.06, V(-0.0828, -0.0495, -0.0685)),
              (I_BACK, V(-0.0848, -0.0490, -0.0670)), (I_FWD, V(-0.0833, -0.0510, -0.0690)),
              (2.95, V(-0.0820, -0.0520, -0.0660)), (3.20, V(-0.0300, -0.0180, -0.0220), "flow"),
              (3.42, V(0.0035, -0.0045, 0.0020)), (3.62, V(-0.0008, 0.0010, -0.0004)), (4.00, ZERO))
I_ROT = Curve((0.00, ZERO), (0.09, V(-1.5, -1.5, 2.5)), (0.50, V(53, 9, -13)), (0.66, V(50, 8, -12)),
              (1.32, V(47, 10, -10)), (1.42, V(49, 9, -11)), (1.92, V(10, 31, 74)),
              (2.06, V(11, 30, 70)), (I_BACK, V(10, 31, 72)), (I_FWD, V(11, 30, 71)), (2.95, V(11, 29, 69)),
              (3.42, V(-1.2, -1.6, -3.5)), (3.62, V(0.3, 0.4, 0.8)), (4.00, ZERO))
I_CAM = Curve((0.00, ZERO), (0.09, V(-0.10, 0.05, 0.30)), (0.50, V(0.45, -0.35, 0.90)), (1.32, V(0.30, -0.20, 0.60)),
              (1.92, V(-0.35, 0.45, -1.40)), (2.40, V(-0.45, 0.35, -1.20)), (2.95, V(-0.20, 0.30, -1.00)),
              (3.42, V(0.15, -0.05, 0.35)), (4.00, ZERO))
I_BOLT = Curve((0.0, 0.0), (2.24, 0.0, "stop"), (I_BACK, 0.034), (2.48, 0.031), (2.60, 0.035), (2.68, 0.033, "stop"),
               (I_FWD, 0.0, ("hit", 2.2)), (I_FWD + 0.007, 0.0010, "stop"), (I_FWD + 0.016, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (2.02, {"support": 1}), (2.22, {"charging": 1}), (2.69, {"charging": 1}),
                 (2.86, {"world": 1}), (3.20, {"support": 1}))
I_LEFT_OFF = Curve((0.00, ZERO), (2.02, ZERO), (2.12, V(-0.010, 0.030, 0.030), "flow"), (2.22, ZERO), (2.69, ZERO),
                   (2.92, V(-0.010, -0.020, 0.0), "flow"), (3.20, V(0.0, 0.0010, 0.0), "hit"),
                   (3.28, V(0.0, -0.0008, 0.0)), (3.38, ZERO))
I_OPEN = Curve((0.0, 0.0), (2.03, 0.0), (2.11, 1.0), (2.16, 0.9), (2.24, 0.0, "hit"), (2.68, 0.0, "stop"),
               (2.72, 1.0, "stop"), (3.06, 1.0), (3.20, 0.0, "hit"))
I_SH_R = Curve((0.00, ZERO), (0.09, ZERO), (0.50, V(0.0, 0.12, -0.12)), (1.42, V(0.0, 0.12, -0.12)),
               (1.95, V(0.013, -0.065, 0.014)), (2.95, V(0.013, -0.065, 0.014)), (3.42, ZERO))
I_SH_L = Curve((0.00, ZERO), (0.09, ZERO), (0.50, V(-0.020, 0.010, -0.050)), (1.42, V(-0.020, 0.010, -0.050)),
               (1.95, V(0.030, 0.040, 0.060)), (2.95, V(0.030, 0.040, 0.060)), (3.42, ZERO))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.09, rig.POLE_R), (0.50, V(1.0, 0.0, 0.3)), (1.42, V(1.0, 0.0, 0.3)),
                 (1.95, V(0.39, 0.18, 0.90)), (2.95, V(0.39, 0.18, 0.90)), (3.42, rig.POLE_R))


def inspect_pose(t):
    p = rig.Pose()
    hold = (drift(t, V(0.0015, 0.0012, 0.0010), seed=7, freq=0.45, start=0.55, end=1.45, fade=0.2)
            + drift(t, V(0.0012, 0.0010, 0.0010), seed=8, freq=0.50, start=1.95, end=2.95, fade=0.2))
    hold_r = (drift(t, V(1.5, 1.1, 1.6), seed=9, freq=0.45, start=0.55, end=1.45, fade=0.2)
              + drift(t, V(1.1, 0.8, 1.3), seed=10, freq=0.50, start=1.95, end=2.95, fade=0.2))
    slam = ring(t, I_FWD, V(0.0, -0.0010, -0.0025), 8.0, 0.33)
    slam_r = ring(t, I_FWD, V(0.3, -1.6, 0.9), 8.0, 0.33) + ring(t, 1.92, V(0.0, 0.0, 2.0), 3.5, 0.45)
    fade = until(t, 4.0, 0.3)
    apply_gun(p, I_OFF(t) + (hold + slam) * fade, I_ROT(t) + (hold_r + slam_r) * fade)
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    w = ease("inout", t, 1.95, 2.22) * (1 - ease("inout", t, 2.86, 3.20))
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * V(-0.55, -0.85, -0.15)
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_OPEN(t)))
    wf = 2 * np.pi * t / 0.9                                  # fingers drumming on the foregrip in the first hold
    tap = window(t, 0.70, 1.40, 0.10)
    curls = hands.adjust(curls, index=(-6 * max(0.0, np.sin(wf)) * tap, 0), middle=(-5 * max(0.0, np.sin(wf - 0.9)) * tap, 0),
                         ring=(-4 * max(0.0, np.sin(wf - 1.8)) * tap, 0))
    p.hand_l = (pos, q, squeeze(curls, -6.0 * window(t, 3.20, 3.42, 0.05)))
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3), 0.0),
                               grip=-3.0 * window(t, I_FWD - 0.01, I_FWD + 0.12, 0.03))
    cam(p, *(I_CAM(t) + (ring(t, I_FWD, V(-0.90, 0.15, -0.60), 6.0, 0.35) + ring(t, 1.92, V(0.0, 0.0, -0.4), 3.5, 0.45))
             * fade))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.02, "inspect_move"), (1.40, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (2.95, "inspect_move")],
               extra_times=(I_BACK, I_FWD, I_FWD + 0.007, 2.24, 2.68))


# ------------------------------------------------------------------------------------ locomotion
def step_bounce(t, period, lag=0.12, sharp=1.6):
    ph = (t % period) / period
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - lag))) ** sharp


def pump_left(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0)):
    w = 2 * np.pi / dur
    pos = centre - swing * np.cos(w * t) + bulge * np.sin(w * t)
    clench = -6.0 * abs(np.sin(w * t)) ** 3                  # the fist tightens at each end of the swing
    curls = squeeze(hands.adjust(hands.POSES["fist"], index=(6 * np.sin(w * t), 0), pinky=(-4 * np.sin(w * t), 0)),
                    clench)
    return pos, None, curls


def footfall(period, dip=1.0, rise=0.32, low=0.20, high=0.62):
    """One step (period = a step, the foot strikes at 0): the body sinks quickly after the strike (lowest at `low` of
    the step, -dip), rises through mid-stance (+rise at `high`) and comes down to the next strike. A seamless loop."""
    return Curve((0.0, 0.0), (low * period, -dip), (high * period, rise), (period, 0.0), loop=True)


WALK_DUR = 1.00
W_STEP = footfall(WALK_DUR / 2, 1.0, 0.32, 0.20, 0.62)


def walk_body(t):
    w = 2 * np.pi / WALK_DUR
    b = float(W_STEP(t % (WALK_DUR / 2)))
    side = np.cos(w * (t - 0.05))                             # +1 left step, -1 right step
    return V(0.0040 * side, 0.0060 * b, -0.0016 * b - 0.0010 * np.sin(w * t))


W_LAG = Follow(walk_body, WALK_DUR, freq=4.2, zeta=0.45, loop=True)   # the gun trails the body a few hundredths


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    b = float(W_STEP(t % (WALK_DUR / 2)))
    side = np.cos(w * (t - 0.05))
    lag = W_LAG.lag(t)
    off = walk_body(t) + 0.8 * lag
    apply_gun(p, off, V(0.9 * np.sin(w * t) - 40 * lag[0], 0.25 + 0.75 * b + 90 * lag[1], 1.4 * side))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["grip"], middle=(2 * b, 0), ring=(2 * b, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)


def gun_frame(muzzle, top):
    """Gun orientation from the muzzle direction and the direction its top faces (viewmodel space)."""
    z = -norm(muzzle)
    y = norm(np.asarray(top, float) - z * np.dot(top, z))
    return frame_quat(np.cross(y, z), y, z)


# running carries: each foot strike drops the gun hard (the footfall curve), the gun bounces on the arms behind the
# body (a spring follow: it lands late and overshoots), swings across against the free arm, which pumps as a fist
SPRINT_DUR = 0.66
LOW_T = V(0.150, -0.110, -0.300)                  # running carry: gun lowered, muzzle down-left (absolute)
LOW_Q = gun_frame((-0.30, -0.60, -0.74), (-0.30, 0.70, -0.55))
LOW_SHOULDER = V(0.080, 0.160, -0.100)            # gun arm: elbow up and out so the forearm follows the grip
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)
S_STEP = footfall(SPRINT_DUR / 2, 1.0, 0.45, 0.17, 0.60)


def sprint_body(t):
    w = 2 * np.pi / SPRINT_DUR
    b = float(S_STEP(t % (SPRINT_DUR / 2)))
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * t)                         # the gun arm swings against the left arm
    return V(0.0055 * side, 0.0110 * b + 0.004 * swing, 0.010 * swing)


S_LAG = Follow(sprint_body, SPRINT_DUR, freq=5.0, zeta=0.35, loop=True)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    b = float(S_STEP(t % (SPRINT_DUR / 2)))
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * t)
    lag = S_LAG.lag(t)
    p.gun_t = LOW_T + sprint_body(t) + 0.9 * lag
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.2 * np.sin(w * t) - 60 * lag[0], 2.8 * b + 3.0 * swing + 110 * lag[1],
                                         3.2 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.150, -0.300)                  # tactical sprint: muzzle up in front of the right shoulder
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)
T_STEP = footfall(TAC_DUR / 2, 1.0, 0.50, 0.16, 0.58)


def tac_body(t):
    w = 2 * np.pi / TAC_DUR
    b = float(T_STEP(t % (TAC_DUR / 2)))
    side = np.cos(w * (t - 0.035))
    return V(0.0065 * side, 0.0130 * b, -0.0040 * b)


T_LAG = Follow(tac_body, TAC_DUR, freq=5.5, zeta=0.35, loop=True)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    b = float(T_STEP(t % (TAC_DUR / 2)))
    side = np.cos(w * (t - 0.035))
    lag = T_LAG.lag(t)
    p.gun_t = TAC_T + tac_body(t) + 0.9 * lag
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.0 * np.sin(w * t) - 50 * lag[0], 3.0 * b + 120 * lag[1], 3.4 * side))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.12)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT, TAC_SPRINT]
