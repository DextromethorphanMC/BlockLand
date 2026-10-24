"""Uzi clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 40): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> uzi_rig.Pose (palm frames + finger curls); gen_uzi bakes them
(IK included) at >= 60 samples/s (fire at 240).

Authored to section 31 with anim.Curve like the Thompson's (tommy_clips.py): a compact 3.5 kg open-bolt SMG, quicker
than the Thompson. The cocking knob on top does not reciprocate (the real Uzi's knob stays forward while the bolt
cycles inside), so a shot is the trigger break, the hands taking the kick and the slam ringing through the gun. The
magazine lives in the pistol grip: the support hand leaves the handguard, takes the magazine below the grip with its
thumb on the heel catch, presses it (the gun jolts), pulls the magazine straight down and lets it fall (gravity and
tumble in viewmodel space), fetches the new one from below the screen, lines it up under the grip, pushes it most of
the way in, gathers and drives it home with a hit. The empty reload then reaches over
the top, hooks the knob, hauls it back against its spring onto the sear and lets it snap forward."""
import numpy as np

import ads_fire
import armrig
import hands
import uzi_model as um
import uzi_rig as rig
from anim import Curve, Follow, drift, qexp, ring, smoothstep, window
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import frame_quat, norm, quat_conj, quat_euler, quat_mul, quat_rotate

MAG_REST = rig.NODES["mag"][1]
TRAVEL = um.KNOB_TRAVEL
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


def cup_fingers(t, period=2.0, amount=1.0, grip=0.0):
    """The support hand's fingers round the handguard, alive."""
    w = 2 * np.pi * t / period
    c = hands.adjust(hands.POSES["uzi_cup"], index=(-2 * amount * np.sin(w + 0.7), 0),
                     middle=(-1.5 * amount * np.sin(w + 0.4), 0), thumb=(2 * amount * np.sin(w + 1.1), 0))
    return squeeze(c, grip) if grip else c


def open_fingers(curls, o, pose="spread"):
    """Blend finger curls towards an open hand (o = 0..1)."""
    if o <= 0:
        return curls
    return {f: (1 - o) * np.asarray(curls[f], float) + o * np.asarray(hands.POSES[pose][f], float)
            for f in hands.FINGERS}


def hand_on_mag(p, mag_t, mag_q, which="mag"):
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
    p.fingers_l = cup_fingers(t)
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen low right for the first frames (sinking a little: the wind-up), then whipped up muzzle first - the turn
# peaks past the ready pose at 0.27 s, the lift at 0.31 s - and settled; the support hand slaps onto the handguard at
# 0.36 s (a hit, a squeeze) and the camera dips with the lift and bobs back
T_CATCH = 0.36
DRAW_OFF = Curve((0.00, V(0.050, -0.265, 0.085)), (0.07, V(0.054, -0.279, 0.090)),
                 (0.19, V(0.020, -0.086, 0.030), "flow"), (0.31, V(-0.0050, 0.0085, -0.0060)),
                 (0.43, V(0.0012, -0.0022, 0.0010)), (0.56, ZERO))
DRAW_ROT = Curve((0.00, V(-16, -48, -30)), (0.07, V(-17, -52, -33)), (0.16, V(-6.0, -14, -12), "flow"),
                 (0.27, V(1.8, 3.8, 2.7)), (0.41, V(-0.5, -1.0, -0.6)), (0.56, ZERO))
DRAW_CAM = Curve((0.00, ZERO), (0.22, V(-0.75, 0.05, -0.50)), (0.38, V(0.38, 0.0, 0.24)), (0.56, ZERO))
DRAW_LW = Curve((0.00, V(-0.012, -0.046, 0.030)), (0.22, V(-0.008, -0.026, 0.016), "flow"),
                (T_CATCH, V(0.0, 0.0012, 0.0), "hit"), (0.43, V(0.0, -0.0010, 0.0)), (0.52, ZERO))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.25, {"spread": 1.0}), (T_CATCH + 0.02, {"uzi_cup": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.28, {"grip": 1.0}), (0.43, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    grab = -7.0 * window(t, T_CATCH, T_CATCH + 0.15, 0.06)            # the catch: fingers clamp, then relax
    p.fingers_l = squeeze(poses(max(t - FINGER_LAG, 0.0), DRAW_FL), grab)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.56, draw_pose, events=[(0.0, "equip")], extra_times=(0.07, T_CATCH))

# ------------------------------------------------------------------------------------------- fire
# 760 rpm, open bolt: the trigger breaks, the bolt flies home and fires, blows back onto the buffer inside the
# receiver; the knob on top stays put. The hands only answer the shot - the wrists give 4 mm and 1.6 deg and come back
# a hair past rest - and the bolt's slam into the buffer rings through the gun; the runtime adds the recoil springs at
# the root. Everything is back at rest at 0.079 s (the next shot's start).
F_END = 0.079
T_SHOT, T_BUFFER = 0.006, 0.040
F_TRIG = Curve((0.0, 0.0), (0.005, rig.TRIGGER_PULL, "hit"), (0.048, rig.TRIGGER_PULL, "stop"), (F_END, 0.0))
F_KICK_T = Curve((0.0, ZERO), (0.004, ZERO, ("snap", 2.0)), (0.013, V(0.0004, 0.0015, 0.0042)),
                 (0.040, V(-0.0001, -0.0003, -0.0006)), (F_END, ZERO))
F_KICK_R = Curve((0.0, ZERO), (0.004, ZERO, ("snap", 2.0)), (0.015, V(0.25, 1.6, -0.55)),
                 (0.042, V(-0.05, -0.25, 0.08)), (F_END, ZERO))
F_CAM = Curve((0.0, ZERO), (0.016, V(0.28, 0.04, 0.10)), (F_END, ZERO))
FIRE_KEYS = sorted(set(F_TRIG.times() + F_KICK_T.times() + F_KICK_R.times() + [T_BUFFER]))


def fire_pose(t):
    p = rig.Pose()
    fade = until(t, F_END, 0.018)
    slam_t = ring(t, T_BUFFER, V(0.0, 0.0003, 0.0007), 12.0, 0.45) * fade
    slam_r = ring(t, T_BUFFER, V(-0.05, 0.30, -0.10), 12.0, 0.45) * fade
    apply_gun(p, F_KICK_T(t) + slam_t, F_KICK_R(t) + slam_r)
    p.trigger = float(F_TRIG(t))
    k = p.trigger / rig.TRIGGER_PULL                          # the finger pad rides the trigger back
    p.fingers_r = squeeze(hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k)),
                          -3.0 * window(t, 0.004, 0.058, 0.02), ("middle", "ring", "pinky"))
    clamp = -4.5 * np.exp(-((t - 0.020) / 0.022) ** 2)       # the support hand clamps on the handguard
    p.fingers_l = squeeze(hands.POSES["uzi_cup"], clamp, ("index", "middle", "ring"))
    cam(p, *F_CAM(t))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=240, extra_times=FIRE_KEYS)    # open bolt: nothing holds open
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
# the short receiver puts a red dot close to the eye: a shorter shove
ADS_SHAPE = {"max_along": 0.0008}
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE, **ADS_SHAPE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE, **ADS_SHAPE)

# ------------------------------------------------------------------------------- magazine change
# the support hand leaves the handguard (fingers open), takes the magazine below the grip with the thumb on the heel
# catch, presses it (the press jolts the gun), pulls the magazine straight down out of the grip (the gun dips with the
# pull and springs up as it comes free), lets it fall; fetches the new one below the screen, brings it up on an arc,
# lines it up under the grip (slow), pushes it most of the way in, gathers (a small dip) and drives it home (a hit:
# the gun and camera jolt up), and swings back to the handguard
T_GRAB, T_RELEASE, T_FREE, T_OUT = 0.24, 0.30, 0.37, 0.41   # events mag_release / mag_out
T_LET = 0.45                                                # the hand lets the old magazine go: it falls from here
T_HIDE, T_SHOW = 0.72, 0.84                                 # magazine scale 0 / 1 (off screen, in the left hand)
T_PUSH, T_GATHER, T_SEAT = 1.10, 1.17, 1.23                 # pushed most of the way / gathered / seated (mag_in)
AWAY = V(-0.090, -0.340, 0.100)                             # off screen (gun space): where the hand swaps magazines
# straight down the grip until it is clear of it (the well guides it), then away; the new one comes up from below,
# lined up a hand's width under the grip, and goes in straight
CARRY_OFF = Curve((0.00, ZERO), (T_RELEASE, ZERO), (0.33, V(0.0, -0.004, 0.0)), (T_FREE, V(0.0, -0.030, 0.0)),
                  (T_OUT, V(0.0, -0.085, 0.0), "flow"), (T_LET, V(-0.008, -0.125, 0.006), "flow"), (0.70, AWAY),
                  (T_SHOW, AWAY), (0.97, V(-0.014, -0.165, -0.008), "flow"), (1.02, V(0.0, -0.118, 0.0)),
                  (T_PUSH, V(0.0, -0.022, 0.0)), (T_GATHER, V(0.0, -0.027, 0.0), "stop"), (T_SEAT, ZERO, "hit"))
CARRY_SPIN = Curve((0.00, ZERO), (T_OUT, ZERO), (T_LET, V(3, -4, 8), "flow"),
                   (0.70, V(14, -18, 38)), (T_SHOW, V(12, -16, 32)), (0.97, V(2.5, -3.5, 7), "flow"),
                   (1.02, ZERO))
TUMBLE = V(-5.0, 3.0, 8.0)                          # rad/s, viewmodel space: the dropped magazine turns as it falls


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


# hand target on the magazine: the thumb on the heel catch, then the grip on its body
ON_MAG = Weights((0.00, {"mag_release": 1}), (T_RELEASE - 0.03, {"mag_release": 1}), (T_FREE, {"mag": 1}))
L_MAG = Weights((0.00, {"support": 1}), (0.07, {"support": 1}), (T_GRAB, {"world": 1}), (T_SEAT + 0.08, {"world": 1}),
                (1.50, {"support": 1}))
L_MAG_OFF = Curve((0.00, ZERO), (0.07, ZERO), (0.15, V(-0.020, -0.030, 0.016), "flow"), (T_GRAB, ZERO),
                  (T_SEAT + 0.08, ZERO),
                  (1.37, V(-0.020, -0.026, 0.012), "flow"), (1.50, V(0.0, 0.0012, 0.0), "hit"),
                  (1.57, V(0.0, -0.0009, 0.0)), (1.66, ZERO))
L_OPEN = Curve((0.00, 0.0), (0.05, 0.0), (0.11, 1.0), (0.18, 0.8), (T_GRAB, 0.0), (T_LET - 0.01, 0.0),
               (T_LET + 0.04, 1.0, "stop"), (0.74, 1.0), (0.82, 0.0), (T_SEAT + 0.02, 0.0), (1.30, 0.8),
               (1.34, 0.9), (1.44, 0.6), (1.50, 0.0, "hit"))
THUMB_PRESS = Curve((0.0, 0.0), (T_RELEASE - 0.04, 0.0), (T_RELEASE, -18.0, "hit"), (0.36, -4.0), (0.42, 0.0))
L_CLAMP = lambda t: (-6.0 * window(t, T_FREE - 0.04, T_LET - 0.01, 0.02) - 7.0 * window(t, T_GATHER, T_SEAT + 0.05, 0.02)
                     - 6.0 * window(t, 1.50, 1.66, 0.05))
# the support shoulder drops back while the hand works under the grip
SH_MAG = Curve((0.00, ZERO), (0.07, ZERO), (0.24, V(0.040, -0.050, 0.120)), (T_SEAT + 0.06, V(0.040, -0.050, 0.120)),
               (1.50, ZERO))


def mag_hands(p, t):
    """Support hand for the magazine change (0 .. 1.66 s)."""
    mt, mq = carrier(t)
    items = [(w, hand_on_mag(p, mt, mq, name)) for name, w in ON_MAG(t).items()]
    pos, q, curls = armrig.blend_targets(items)
    left_hand(p, L_MAG, t, L_MAG_OFF(t), world=(pos, q, curls))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(L_OPEN(t)))
    curls = squeeze(hands.adjust(curls, thumb=(float(THUMB_PRESS(t)), 0.0)), L_CLAMP(t))
    if t > 1.50:
        curls = cup_fingers(t, grip=-5.0 * window(t, 1.50, 1.66, 0.05))
    p.hand_l = (pos, q, curls)


def mag_reactions(t):
    """The gun answering the support hand: the catch press, the pull and the magazine coming free, the seat; and the
    moving hold while the hand is away. -> (offset, euler, camera)."""
    pull = window(t, 0.31, T_FREE, 0.03)
    off = (ring(t, T_RELEASE, V(0.0010, 0.0003, 0.0004), 7.5, 0.40) + ring(t, T_FREE, V(0.0, 0.0022, 0.0), 7.0, 0.40)
           + ring(t, T_SEAT, V(0.0003, 0.0040, -0.0006), 7.5, 0.32) + V(0.0, -0.0024, 0.0) * pull
           + drift(t, V(0.0010, 0.0008, 0.0006), seed=3, freq=0.55, start=0.40, end=1.05, fade=0.18))
    rot = (ring(t, T_RELEASE, V(-0.4, 0.2, 0.9), 7.5, 0.40) + ring(t, T_FREE, V(0.0, 0.9, 0.6), 7.0, 0.40)
           + ring(t, T_SEAT, V(-0.6, 2.4, -1.6), 7.5, 0.32) + V(0.0, -0.7, -1.0) * pull
           + drift(t, V(0.6, 0.5, 0.9), seed=4, freq=0.50, start=0.40, end=1.05, fade=0.18))
    camera = (ring(t, T_RELEASE, V(0.15, 0.0, 0.30), 6.0, 0.45) + ring(t, T_FREE, V(0.35, 0.0, 0.25), 6.0, 0.40)
              + ring(t, T_SEAT, V(1.05, -0.15, 0.80), 6.0, 0.35))
    return off, rot, camera


# ---------------------------------------------------------------------------------------- reload
# anticipation (the gun dips and leans the other way), then a swing up and in that rolls the grip's bottom into view
# (past it, then settled), the hold while the hand works (drifting, the grip turned towards the incoming magazine),
# and after the seat a swing back down to ready that passes it and settles
PRESENT_OFF = [(0.00, ZERO), (0.09, V(0.0025, -0.0035, 0.0020)), (0.28, V(-0.002, 0.124, -0.022)),
               (0.38, V(0.000, 0.118, -0.025)), (1.00, V(0.000, 0.115, -0.025)), (1.18, V(-0.001, 0.119, -0.026))]
PRESENT_ROT = [(0.00, ZERO), (0.09, V(-0.7, 1.2, 2.5)), (0.28, V(9.0, -15.5, -39.0)), (0.38, V(8.0, -14.0, -36.0)),
               (1.00, V(8.0, -14.4, -36.5)), (1.18, V(8.2, -14.0, -37.0))]
R_OFF = Curve(*PRESENT_OFF, (1.36, V(-0.002, 0.104, -0.022)), (1.56, V(-0.002, 0.034, -0.008)),
              (1.76, V(0.003, -0.004, 0.002)), (1.92, V(-0.0007, 0.0009, -0.0003)), (2.10, ZERO))
R_ROT = Curve(*PRESENT_ROT, (1.36, V(7.0, -12.0, -32.0)), (1.56, V(2.5, -4.0, -11.0)), (1.76, V(-0.8, 0.6, 3.0)),
              (1.92, V(0.2, -0.15, -0.7)), (2.10, ZERO))
R_CAM = Curve((0.00, ZERO), (0.09, V(0.15, 0.0, 0.40)), (0.32, V(-0.60, 0.30, -1.70)), (1.00, V(-0.50, 0.25, -1.45)),
              (1.20, V(-0.65, 0.30, -1.80)), (1.44, V(-0.40, 0.20, -1.00)), (1.78, V(0.20, -0.10, 0.45)), (2.10, ZERO))


def reload_gun(t):
    off, rot, _ = mag_reactions(t)
    return gun_at(R_OFF(t) + off, R_ROT(t) + rot)


R_DROP = Drop(reload_gun)


def reload_pose(t):
    p = rig.Pose()
    off, rot, camera = mag_reactions(t)
    fade = until(t, 2.10, 0.15)
    apply_gun(p, R_OFF(t) + off * fade, R_ROT(t) + rot * fade)
    p.shoulder_l = SH_MAG(t)
    place_mag(p, t, R_DROP)
    mag_hands(p, t)
    seat = -4.0 * window(t, T_SEAT - 0.02, T_SEAT + 0.12, 0.03)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 1.6, 1.9)), 0),
                               grip=seat)
    cam(p, *(R_CAM(t) + camera * fade))
    return p


MAG_TIMES = (0.09, T_GRAB, T_RELEASE, T_FREE, T_OUT, T_LET, T_HIDE, T_SHOW, 1.02, T_PUSH, T_GATHER, T_SEAT, 1.50)
RELOAD = Clip("reload", 2.10, reload_pose,
              events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=MAG_TIMES)

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change; then the gun rolls up towards the support hand, which goes up over the receiver, hooks
# the cocking knob, hauls it back against its spring (slow, then fast into the rear stop: the gun is dragged back
# towards the hand) - the bolt goes back with it onto the sear - and lets go: the knob snaps forward alone (the gun
# jolts, the camera kicks) and the hand flicks off and swings back to the handguard
T_REACH, T_HOOK, T_PULL, T_BACK, T_LET2, T_FWD = 1.30, 1.50, 1.55, 1.65, 1.685, 1.715   # bolt_back / bolt_forward
E_OFF = Curve(*PRESENT_OFF, (1.30, V(0.000, 0.060, -0.012), "flow"), (1.48, V(0.010, -0.020, -0.015)),
              (T_BACK, V(0.009, -0.019, -0.010)), (1.86, V(0.006, -0.012, -0.012)), (2.10, V(0.003, -0.004, 0.002)),
              (2.26, V(-0.0006, 0.0008, -0.0003)), (2.60, ZERO))
E_ROT = Curve(*PRESENT_ROT, (1.30, V(6.5, -8.0, -20.0), "flow"), (1.48, V(4.0, 2.0, 12.0)), (T_BACK, V(3.6, 2.6, 13.5)),
              (1.86, V(2.5, 1.5, 8.0)), (2.10, V(-0.8, -1.0, -2.0)), (2.26, V(0.2, 0.3, 0.6)), (2.60, ZERO))
E_CAM = Curve((0.00, ZERO), (0.09, V(0.15, 0.0, 0.40)), (0.32, V(-0.60, 0.30, -1.70)), (1.00, V(-0.50, 0.25, -1.45)),
              (1.20, V(-0.65, 0.30, -1.80)), (1.36, V(-0.40, 0.25, -1.40)), (1.50, V(-0.30, 0.30, -1.50)),
              (T_BACK, V(-0.10, 0.40, -1.30)), (1.86, V(-0.20, 0.10, -0.75)), (2.12, V(0.20, -0.10, 0.40)),
              (2.60, ZERO))
E_BOLT = Curve((0.0, 0.0), (T_PULL, 0.0, "stop"), (1.60, 0.028), (T_BACK, TRAVEL, ("hit", 1.6)),
               (1.665, TRAVEL - 0.0022), (T_LET2, TRAVEL - 0.0014, "stop"), (T_FWD, 0.0, ("hit", 2.2)),
               (T_FWD + 0.007, 0.0013, "stop"), (T_FWD + 0.018, 0.0))
E_LEFT = Weights((T_REACH, {"world": 1}), (1.46, {"charging": 1}), (T_LET2, {"charging": 1}), (1.80, {"world": 1}),
                 (2.06, {"support": 1}))
E_LEFT_OFF = Curve((T_REACH, ZERO), (1.38, V(-0.030, 0.012, 0.006), "flow"), (1.46, ZERO), (T_LET2, ZERO),
                   (1.78, V(-0.008, -0.026, -0.004), "flow"), (1.94, V(-0.006, -0.012, 0.002)),
                   (2.06, V(0.0, 0.0012, 0.0), "hit"), (2.13, V(0.0, -0.0009, 0.0)), (2.22, ZERO))
E_OPEN = Curve((T_REACH, 0.78), (1.38, 1.0), (1.44, 0.9), (T_HOOK, 0.0, "hit"), (T_LET2, 0.0, "stop"),
               (T_LET2 + 0.03, 1.0, "stop"), (1.96, 1.0), (2.06, 0.0, "hit"))
E_CLAMP = lambda t: -6.0 * window(t, T_HOOK, T_LET2, 0.03) - 6.0 * window(t, 2.06, 2.24, 0.05)
SH_EMPTY = Curve((0.00, ZERO), (0.07, ZERO), (0.24, V(0.040, -0.050, 0.120)), (T_SEAT + 0.06, V(0.040, -0.050, 0.120)),
                 (1.46, V(0.110, -0.030, 0.030)), (T_LET2 + 0.05, V(0.110, -0.030, 0.030)), (2.08, ZERO))


def bolt_reactions(t):
    """The gun answering the cocking: the hook, the haul back (dragged towards the hand), the rear stop, the snap."""
    haul = window(t, T_PULL, T_BACK + 0.02, 0.035)
    off = (ring(t, T_HOOK, V(0.0, 0.0006, 0.0), 8.0, 0.45) + V(-0.0015, 0.0, 0.0028) * haul
           + ring(t, T_BACK, V(0.0, 0.0, 0.0012), 9.0, 0.40) + ring(t, T_FWD, V(0.0003, -0.0010, -0.0024), 8.5, 0.34))
    rot = (ring(t, T_HOOK, V(0.0, 0.0, 0.4), 8.0, 0.45) + V(-1.0, 0.3, -0.8) * haul
           + ring(t, T_BACK, V(-0.3, 0.4, 0.0), 9.0, 0.40) + ring(t, T_FWD, V(0.4, -1.6, 0.9), 8.5, 0.34))
    camera = ring(t, T_BACK, V(0.30, 0.0, 0.20), 7.0, 0.45) + ring(t, T_FWD, V(-0.90, 0.18, -0.75), 6.5, 0.35)
    return off, rot, camera


def empty_gun(t):
    off, rot, _ = mag_reactions(t)
    return gun_at(E_OFF(t) + off, E_ROT(t) + rot)


E_DROP = Drop(empty_gun)


def let_go(p):
    """Off-hand spot left of and below the released knob: the hand springs open and drops away (it does not fly up
    at the camera)."""
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.022, -0.014, 0.012)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    off, rot, camera = mag_reactions(t)
    b_off, b_rot, b_cam = bolt_reactions(t)
    fade = until(t, 2.60, 0.15)
    apply_gun(p, E_OFF(t) + (off + b_off) * fade, E_ROT(t) + (rot + b_rot) * fade)
    p.shoulder_l = SH_EMPTY(t)
    w = ease("inout", t, 1.30, 1.48) * (1 - ease("inout", t, 1.80, 2.06))    # elbow down: the hand comes up over the top
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * V(-0.30, -0.95, 0.10)
    place_mag(p, t, E_DROP)
    p.bolt_z = float(E_BOLT(t))
    if t < T_REACH:
        mag_hands(p, t)
    else:
        mag_hands(p, T_REACH)
        start = p.hand_l
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=start if t < 1.46 else let_go(p))
        pos, q, curls = p.hand_l
        p.hand_l = (pos, q, squeeze(open_fingers(curls, float(E_OPEN(t))), E_CLAMP(t)))
    seat = -4.0 * window(t, T_SEAT - 0.02, T_SEAT + 0.12, 0.03) - 4.0 * window(t, T_FWD - 0.01, T_FWD + 0.10, 0.03)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, 2.1, 2.4)), 0),
                               grip=seat)
    cam(p, *(E_CAM(t) + (camera + b_cam) * fade))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.60, reload_empty_pose,
                    events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in"),
                            (T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=MAG_TIMES + (T_HOOK, T_PULL, T_BACK, T_LET2, T_FWD, T_FWD + 0.007))

# --------------------------------------------------------------------------------------- inspect
# a wind-up dip, the swing up to show the left side (the selector plate, the swivel, the heel catch) - past it and
# settled, then held, drifting, the fingers drumming on the handguard; a gather and the roll over to the right side
# (the ejection port up), dipping under on the way and overshooting the roll; the support hand hooks the knob and
# draws it back a few centimetres, holds it (it jiggles in the hand), lets it snap home (ring, camera kick); then the
# gun swings back down to ready, past it, and settles
I_BACK, I_FWD = 2.30, 2.66
I_OFF = Curve((0.00, ZERO), (0.09, V(0.003, -0.005, 0.002)), (0.48, V(-0.030, 0.004, -0.058)),
              (0.64, V(-0.028, 0.002, -0.056)), (1.28, V(-0.029, 0.004, -0.058)),
              (1.38, V(-0.031, 0.003, -0.060)), (1.64, V(-0.016, -0.040, -0.064), "flow"),
              (1.86, V(-0.012, -0.062, -0.066)), (2.00, V(-0.011, -0.059, -0.064)),
              (I_BACK, V(-0.013, -0.059, -0.063)), (I_FWD, V(-0.012, -0.061, -0.065)),
              (2.86, V(-0.010, -0.062, -0.062)), (3.12, V(-0.008, -0.022, -0.020), "flow"),
              (3.34, V(0.0035, -0.0045, 0.0020)), (3.56, V(-0.0008, 0.0010, -0.0004)), (4.00, ZERO))
I_ROT = Curve((0.00, ZERO), (0.09, V(-1.5, -1.5, 2.5)), (0.48, V(50, 10, -14)), (0.64, V(47, 9, -12)),
              (1.28, V(45, 10, -10)), (1.38, V(47, 9, -11)), (1.86, V(10, 30, 72)),
              (2.00, V(11, 29, 68)), (I_BACK, V(10, 30, 70)), (I_FWD, V(11, 29, 69)), (2.86, V(11, 28, 67)),
              (3.34, V(-1.2, -1.6, -3.5)), (3.56, V(0.3, 0.4, 0.8)), (4.00, ZERO))
I_CAM = Curve((0.00, ZERO), (0.09, V(-0.10, 0.05, 0.30)), (0.48, V(0.45, -0.35, 0.90)), (1.28, V(0.30, -0.20, 0.60)),
              (1.86, V(-0.35, 0.45, -1.40)), (2.34, V(-0.45, 0.35, -1.20)), (2.86, V(-0.20, 0.30, -1.00)),
              (3.34, V(0.15, -0.05, 0.35)), (4.00, ZERO))
I_BOLT = Curve((0.0, 0.0), (2.18, 0.0, "stop"), (I_BACK, 0.036), (2.42, 0.033), (2.52, 0.037), (2.60, 0.035, "stop"),
               (I_FWD, 0.0, ("hit", 2.2)), (I_FWD + 0.007, 0.0010, "stop"), (I_FWD + 0.016, 0.0))
I_LEFT = Weights((0.00, {"support": 1}), (1.96, {"support": 1}), (2.16, {"charging": 1}), (2.61, {"charging": 1}),
                 (2.78, {"world": 1}), (3.12, {"support": 1}))
I_LEFT_OFF = Curve((0.00, ZERO), (1.96, ZERO), (2.06, V(-0.010, 0.030, 0.030), "flow"), (2.16, ZERO), (2.61, ZERO),
                   (2.84, V(-0.010, -0.020, 0.0), "flow"), (3.12, V(0.0, 0.0010, 0.0), "hit"),
                   (3.20, V(0.0, -0.0008, 0.0)), (3.30, ZERO))
I_OPEN = Curve((0.0, 0.0), (1.97, 0.0), (2.05, 1.0), (2.10, 0.9), (2.18, 0.0, "hit"), (2.60, 0.0, "stop"),
               (2.64, 1.0, "stop"), (2.98, 1.0), (3.12, 0.0, "hit"))
I_SH_R = Curve((0.00, ZERO), (0.09, ZERO), (0.48, V(0.0, 0.12, -0.12)), (1.38, V(0.0, 0.12, -0.12)),
               (1.89, V(0.013, -0.065, 0.014)), (2.86, V(0.013, -0.065, 0.014)), (3.34, ZERO))
I_SH_L = Curve((0.00, ZERO), (0.09, ZERO), (0.48, V(-0.020, 0.010, -0.050)), (1.38, V(-0.020, 0.010, -0.050)),
               (1.89, V(0.030, 0.040, 0.060)), (2.86, V(0.030, 0.040, 0.060)), (3.34, ZERO))
I_POLE_R = Curve((0.00, rig.POLE_R), (0.09, rig.POLE_R), (0.48, V(1.0, 0.0, 0.3)), (1.38, V(1.0, 0.0, 0.3)),
                 (1.89, V(0.39, 0.18, 0.90)), (2.86, V(0.39, 0.18, 0.90)), (3.34, rig.POLE_R))


def inspect_pose(t):
    p = rig.Pose()
    hold = (drift(t, V(0.0015, 0.0012, 0.0010), seed=7, freq=0.45, start=0.52, end=1.40, fade=0.2)
            + drift(t, V(0.0012, 0.0010, 0.0010), seed=8, freq=0.50, start=1.90, end=2.86, fade=0.2))
    hold_r = (drift(t, V(1.5, 1.1, 1.6), seed=9, freq=0.45, start=0.52, end=1.40, fade=0.2)
              + drift(t, V(1.1, 0.8, 1.3), seed=10, freq=0.50, start=1.90, end=2.86, fade=0.2))
    slam = ring(t, I_FWD, V(0.0, -0.0009, -0.0022), 8.5, 0.33)
    slam_r = ring(t, I_FWD, V(0.3, -1.4, 0.8), 8.5, 0.33) + ring(t, 1.86, V(0.0, 0.0, 2.0), 3.5, 0.45)
    fade = until(t, 4.0, 0.3)
    apply_gun(p, I_OFF(t) + (hold + slam) * fade, I_ROT(t) + (hold_r + slam_r) * fade)
    p.shoulder_r, p.pole_r = I_SH_R(t), I_POLE_R(t)
    p.shoulder_l = I_SH_L(t)
    w = ease("inout", t, 1.89, 2.16) * (1 - ease("inout", t, 2.78, 3.12))
    if w > 0:
        p.pole_l = (1 - w) * rig.POLE_L + w * V(-0.55, -0.85, -0.15)
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t), world=let_go(p))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_OPEN(t)))
    wf = 2 * np.pi * t / 0.9                                  # fingers drumming on the handguard in the first hold
    tap = window(t, 0.66, 1.34, 0.10)
    curls = hands.adjust(curls, index=(-6 * max(0.0, np.sin(wf)) * tap, 0), middle=(-5 * max(0.0, np.sin(wf - 0.9)) * tap, 0),
                         ring=(-4 * max(0.0, np.sin(wf - 1.8)) * tap, 0))
    p.hand_l = (pos, q, squeeze(curls, -6.0 * window(t, 3.12, 3.34, 0.05)))
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3), 0.0),
                               grip=-3.0 * window(t, I_FWD - 0.01, I_FWD + 0.12, 0.03))
    cam(p, *(I_CAM(t) + (ring(t, I_FWD, V(-0.85, 0.15, -0.55), 6.0, 0.35) + ring(t, 1.86, V(0.0, 0.0, -0.4), 3.5, 0.45))
             * fade))
    return p


INSPECT = Clip("inspect", 4.00, inspect_pose,
               events=[(0.02, "inspect_move"), (1.36, "inspect_move"), (I_BACK, "bolt_back"), (I_FWD, "bolt_forward"),
                       (2.88, "inspect_move")],
               extra_times=(I_BACK, I_FWD, I_FWD + 0.007, 2.18, 2.60))


# ------------------------------------------------------------------------------------ locomotion
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
    return V(0.0040 * side, 0.0058 * b, -0.0015 * b - 0.0010 * np.sin(w * t))


W_LAG = Follow(walk_body, WALK_DUR, freq=4.4, zeta=0.45, loop=True)   # the gun trails the body a few hundredths


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    b = float(W_STEP(t % (WALK_DUR / 2)))
    side = np.cos(w * (t - 0.05))
    lag = W_LAG.lag(t)
    off = walk_body(t) + 0.8 * lag
    apply_gun(p, off, V(0.9 * np.sin(w * t) - 40 * lag[0], 0.25 + 0.75 * b + 90 * lag[1], 1.4 * side))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["uzi_cup"], middle=(2 * b, 0), ring=(2 * b, 0))
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


S_LAG = Follow(sprint_body, SPRINT_DUR, freq=5.2, zeta=0.35, loop=True)


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


T_LAG = Follow(tac_body, TAC_DUR, freq=5.6, zeta=0.35, loop=True)


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
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
