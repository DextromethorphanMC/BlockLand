"""RPD clips (VIEWMODEL_SPEC sections 4, 15-17, 31 and 33): idle, draw, fire, fire_last, reload, reload_empty,
inspect, walk, sprint, tac_sprint. Each clip maps time -> rpd_rig.Pose; gen_rpd bakes them (IK included) at >= 60
samples/s (fire at 120).

Authored to section 31 like the AK's (ak_clips.py: Curve bases, rings for every push, drift in holds), heavier: the
RPD is a 7.4 kg belt-fed gun, so big moves start later, travel further and settle longer. The belt change is the
machine-gunner's routine: the latch flicked and the feed cover popping up, the old belt lifted out and thrown aside,
the drum unhooked and dropped with the belt hanging off it, a full drum hooked on (hit), its belt pulled out of the
drum's mouth and laid across the feed tray, the cover slammed shut — the biggest jolt of the reload. The belt is always
one piece from the drum's mouth to its end (rpd_rig.belt_chain); every shot feeds it a link. The empty reload adds a
reach-under pull of the charging handle."""
import numpy as np

import ads_fire
import hands
import rpd_model as rm
import rpd_rig as rig
from ak_clips import Layers, blend_curls, fade_end, foot, FOOT_WALK, gun_frame, open_fingers, punch_left
from anim import Curve, drift, ring, smoothstep
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand, poses
from vmath import quat_euler, quat_mul

TRAVEL = rm.BOLT_TRAVEL
MAG_REST = rig.NODES["mag"][1]
BELT_REST = rig.NODES["belt"][1]
OPEN = rm.COVER_OPEN

hands.POSES.setdefault("rpd_clamp", {"thumb": (-20, -12), "index": (-60, -56), "middle": (-66, -62),
                                     "ring": (-70, -66), "pinky": (-74, -68)})


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0008 * np.sin(w + 0.6), 0.0011 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.15 * np.sin(w + 0.3), 0.20 * np.sin(w + 0.9), 0.22 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    p.belt_t = BELT_REST + V(0.0, 0.0004 * np.sin(w + 1.7), 0.0)        # the loose belt end sways a hair
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# off screen for the first ~0.12 s (the outgoing weapon is still dropping), a heavy heave up with the muzzle leading:
# the turn overshoots at 0.34, the body at 0.44, then a long settle; the support hand slaps the handguard at 0.56
T_CATCH = 0.56
DRAW_OFF = Layers(Curve((0.00, V(0.052, -0.300, 0.105)), (0.10, V(0.056, -0.312, 0.110)),
                        (0.44, V(-0.005, 0.009, -0.006), "flow"), (0.62, V(0.0012, -0.0026, 0.001)), (0.95, V(0, 0, 0))),
                  rings=((T_CATCH, V(0.0, 0.0035, -0.0015), 7.0, 0.36),), end=0.95)
DRAW_ROT = Layers(Curve((0.00, V(-17, -50, -32)), (0.10, V(-18, -53, -34)), (0.34, V(2.8, 4.8, 3.4), "flow"),
                        (0.56, V(-0.7, -1.1, -0.6)), (0.95, V(0, 0, 0))),
                  rings=((T_CATCH, V(1.6, 0.2, -0.8), 6.5, 0.34),), end=0.95)
DRAW_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.10, V(0.3, 0, 0.2)), (0.36, V(-0.9, 0.1, -0.7)), (0.62, V(0.25, 0, 0.2)),
                        (0.95, V(0, 0, 0))), rings=((T_CATCH, V(-0.45, 0.05, 0.35), 6.0, 0.38),), end=0.95)
DRAW_LW = Curve((0.0, V(-0.012, -0.050, 0.036)), (0.36, V(-0.010, -0.036, 0.024)), (0.52, V(0, 0.003, 0), "hit"),
                (0.62, V(0, 0, 0)))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.44, {"spread": 1.0}), (0.57, {"rpd_clamp": 1.0}),
                  (0.75, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.36, {"grip": 1.0}), (0.60, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    apply_gun(p, DRAW_OFF(t), DRAW_ROT(t))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = poses(max(t - FINGER_LAG, 0.0), DRAW_FL)
    p.fingers_r = poses(t, DRAW_FR)
    cam(p, *DRAW_CAM(t))
    return p


DRAW = Clip("draw", 0.95, draw_pose, events=[(0.0, "equip")])

# ------------------------------------------------------------------------------------------- fire
# open bolt, 700 rpm: the carrier slams back into its buffer and is driven home inside 0.086 s; the feed pawl drags
# the belt a link into the tray as the carrier comes back (every round slides on one place; at the end the pieces sit
# where their neighbours were, so the clip loops seamlessly); the hands' reaction stays small (the runtime adds the
# recoil)
F_END = 0.086
F_BOLT = Curve((0.0, 0.0), (0.003, 0.0, ("snap", 2.4)), (0.021, TRAVEL, ("hit", 0.9)), (0.027, TRAVEL - 0.0007),
               (0.063, 0.0, ("hit", 2.2)), (0.069, 0.0016, "stop"), (0.076, 0.0, "stop"), (F_END, 0.0))
F_TRIG = Curve((0.0, 7.0), (0.005, rig.TRIGGER_PULL, ("hit", 1.6)), (0.048, rig.TRIGGER_PULL), (0.080, 7.0),
               (F_END, 7.0))
F_FEED = Curve((0.0, 0.0), (0.016, 0.0, ("snap", 2.0)), (0.032, 1.0, ("hit", 1.4)), (0.040, 0.97), (0.050, 1.0))
F_RESET = F_END - 0.0005                     # the belt one link on == the belt at rest: back to 0 in one sample
F_BELT = Curve((0.0, 0.0), (0.016, 0.0), (0.030, 0.0012, "hit"), (0.046, 0.0004), (0.066, 0.0), (F_END, 0.0))


def _fire_kick(t):
    k = fade_end(t, F_END, 0.026)
    off = ring(t, 0.001, V(0.0005, 0.0017, 0.0058), 11.0, 0.5) + ring(t, 0.063, V(0.0, -0.0005, -0.0013), 14.0, 0.5)
    rot = ring(t, 0.001, V(0.3, 1.6, -0.55), 10.0, 0.5) + ring(t, 0.063, V(0.0, -0.4, 0.12), 14.0, 0.5)
    return off * k, rot * k


FIRE_KEYS = sorted(set(F_BOLT.times() + F_TRIG.times() + F_FEED.times() + F_BELT.times()
                       + [0.010, F_RESET - 0.0004, F_RESET]))


def fire_pose(t):
    p = rig.Pose()
    apply_gun(p, *_fire_kick(t))
    p.bolt_z = float(F_BOLT(t))
    p.trigger = float(F_TRIG(t))
    p.belt_feed = 0.0 if t >= F_RESET else rm.LINK * float(F_FEED(t))
    p.belt_t = BELT_REST + V(p.belt_feed, float(F_BELT(t)), 0.0)          # the end slides into the tray, lifting
    k = float(F_TRIG(t)) / rig.TRIGGER_PULL
    p.fingers_r = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
    clamp = -5.0 * float(ring(t, 0.010, 1.0, 9.0, 0.6)) * fade_end(t, F_END, 0.026)
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(clamp, 0), middle=(clamp, 0), ring=(clamp, 0),
                               thumb=(0.5 * clamp, 0))
    cam(p, *(ring(t, 0.002, V(0.34, 0.05, 0.10), 9.0, 0.5) * fade_end(t, F_END, 0.03)))
    return p


FIRE = Clip("fire", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)
FIRE_LAST = Clip("fire_last", F_END, fire_pose, fps=120, extra_times=FIRE_KEYS)      # open bolt: no hold-open
# aimed (section 31.5): the same shot with the sight picture held - a shove back along the sight line, a trace
# of roll (ads_fire)
FIRE_ADS = ads_fire.aimed(FIRE, rig, rig.AIM_IRON_T, IDLE)
FIRE_LAST_ADS = ads_fire.aimed(FIRE_LAST, rig, rig.AIM_IRON_T, IDLE)

# ------------------------------------------------------------------------------------------- reload (belt change)
T_LIFT, T_OPEN = 0.40, 0.64          # fingertips under the latch / cover snaps up (cover_open)
T_BELT_OUT = 1.05                    # old belt lifted out of the tray (belt)
T_BELT_DROP = 1.27                   # let go: the belt end falls and hangs off the drum's mouth
T_DRUM, T_OUT = 1.40, 1.55           # hand on the drum / unhooked (mag_out)
T_HIDE, T_SHOW = 1.98, 2.42          # drum off screen / the new one in the hand
T_HOOK = 3.05                        # new drum hooked on (mag_in)
T_BELT_SHOW, T_LAY = 3.28, 3.76      # new belt picked up from the drum / laid on the tray (belt)
T_CLOSE = 4.35                       # cover slammed (cover_close)

COVER = Curve((0.00, 0.0), (0.52, 0.0), (0.56, 1.6, "stop"), (T_OPEN, OPEN - 6.0, ("hit", 1.4)), (0.71, OPEN + 2.0),
              (0.82, OPEN), (4.05, OPEN, "stop"), (4.16, OPEN - 4.0, "stop"), (T_CLOSE, 0.0, ("hit", 2.4)),
              (T_CLOSE + 0.035, 1.3, "stop"), (T_CLOSE + 0.09, 0.0))
BELT_OUT = Curve((0.00, V(0, 0, 0)), (0.92, V(0, 0, 0), "stop"), (T_BELT_OUT, V(-0.010, 0.030, -0.004)),
                 (T_BELT_DROP, V(-0.032, 0.034, -0.002)))
BELT_OUT_TILT = Curve((0.92, V(0, 0, 0)), (T_BELT_OUT, V(0, 0, -12.0)), (T_BELT_DROP, V(0, 0, -30.0)))
# 0 = in the hand, 1 = hanging off the drum's mouth: the drop overshoots (the belt swings) and settles
BELT_HANG = Curve((T_BELT_DROP, 0.0), (1.37, 1.07, "hit"), (1.45, 0.97), (1.54, 1.01), (1.62, 1.0))
BELT_PICK = Curve((T_BELT_SHOW, 1.0), (3.30, 1.0, "stop"), (3.50, 0.0))                 # out of the drum, into the hand
BELT_IN = Curve((T_BELT_SHOW, V(-0.068, -0.110, -0.006)), (3.52, V(-0.020, 0.026, -0.002), "flow"),
                (3.68, V(-0.004, 0.009, 0.000)), (T_LAY, V(0.0, 0.0, 0.0), ("hit", 1.6)))
BELT_TILT = Curve((T_BELT_SHOW, V(0, 0, -95.0)), (3.52, V(0, 0, -14.0)), (T_LAY, V(0, 0, 0)))
DRUM_OUT = Curve((T_OUT - 0.02, V(0, 0, 0)), (1.63, V(-0.004, -0.012, 0.004), "flow"), (1.80, V(-0.040, -0.120, 0.050)),
                 (T_HIDE, V(-0.070, -0.320, 0.100)))
DRUM_OUT_ROT = Curve((T_OUT - 0.02, V(0, 0, 0)), (1.63, V(4, -2, 3)), (T_HIDE, V(22, -10, 26)))
DRUM_IN = Curve((T_SHOW, V(-0.070, -0.300, 0.100)), (2.80, V(-0.012, -0.040, 0.010), "flow"),
                (2.94, V(0.0, -0.008, 0.001)), (3.00, V(0.0, -0.006, 0.000), "stop"),
                (T_HOOK, V(0.0, 0.0015, 0.0), ("hit", 1.8)), (T_HOOK + 0.06, V(0, 0, 0)))
DRUM_IN_ROT = Curve((T_SHOW, V(16, -10, 22)), (2.80, V(4, -2, 5)), (2.96, V(0, 0, 0)))


def mechanism(p, t):
    """Cover, belt and drum through the belt change (shared by both reloads)."""
    p.cover = float(COVER(t))
    if t < T_BELT_SHOW:                       # lifted out of the tray, let go: it hangs off the old drum's mouth
        p.belt_t = BELT_REST + BELT_OUT(t)
        p.belt_q = quat_euler(*BELT_OUT_TILT(t))
        p.belt_hang = float(BELT_HANG(t)) if t > T_BELT_DROP else 0.0
    else:                                     # the new drum's belt end pulled out and laid on the tray
        p.belt_t = BELT_REST + BELT_IN(t)
        p.belt_q = quat_euler(*BELT_TILT(t))
        p.belt_hang = float(BELT_PICK(t))
    if t < T_OUT - 0.02:
        return
    if t < T_HIDE:
        p.mag_t = MAG_REST + DRUM_OUT(t)
        p.mag_q = quat_euler(*DRUM_OUT_ROT(t))
        return
    if t < T_SHOW:
        p.mag_t = MAG_REST + DRUM_OUT(T_HIDE)
        p.mag_q = quat_euler(*DRUM_OUT_ROT(T_HIDE))
        p.mag_s = 0.0
        return
    p.mag_t = MAG_REST + DRUM_IN(t)
    p.mag_q = quat_euler(*DRUM_IN_ROT(t))


# the support hand: off the handguard, fingertips under the latch, flick (the cover carries them up), pinch the belt
# and throw it, palm on the drum (it rides the drum off screen and back), the new belt pinched up into the tray, palm on
# the cover's rear, slam, back on the handguard
L_BELT = Weights((0.00, {"support": 1}), (0.10, {"support": 1}), (T_LIFT, {"cover_lift": 1}),
                 (T_OPEN + 0.04, {"cover_lift": 1}), (0.90, {"belt": 1}), (1.30, {"belt": 1}), (T_DRUM, {"drum": 1}),
                 (T_HOOK + 0.06, {"drum": 1}), (T_BELT_SHOW, {"belt": 1}), (T_LAY + 0.02, {"belt": 1}),
                 (4.02, {"cover": 1}), (T_CLOSE + 0.04, {"cover": 1}), (4.85, {"support": 1}))
L_OFF = Curve((0.00, V(0, 0, 0)), (0.10, V(0, 0, 0)), (0.24, V(-0.020, 0.010, 0.020), "flow"), (T_LIFT, V(0, 0, 0)),
              (T_OPEN + 0.04, V(0, 0, 0)), (0.78, V(-0.012, 0.024, 0.010), "flow"), (0.90, V(0, 0, 0)),
              (1.30, V(0, 0, 0)), (1.36, V(-0.010, -0.020, 0.010), "flow"), (T_DRUM, V(0, 0, 0)),
              (T_HOOK + 0.06, V(0, 0, 0)), (3.17, V(-0.016, -0.006, 0.012), "flow"), (T_BELT_SHOW, V(0, 0, 0)),
              (T_LAY + 0.02, V(0, 0, 0)), (3.90, V(-0.010, 0.026, 0.016), "flow"), (4.02, V(0, 0, 0)),
              (T_CLOSE + 0.04, V(0, 0, 0)), (4.55, V(-0.014, -0.022, 0.012), "flow"), (4.85, V(0, 0, 0)))
L_OPEN_HAND = Curve((0.0, 0.0), (0.10, 0.0), (0.20, 1.0), (0.34, 0.6), (T_LIFT, 0.0), (T_OPEN + 0.02, 0.0),
                    (0.74, 1.0), (0.88, 0.2), (0.92, 0.0), (1.28, 0.0), (1.34, 1.0), (T_DRUM, 0.0),
                    (T_HOOK + 0.05, 0.0), (3.14, 0.8), (T_BELT_SHOW, 0.0), (T_LAY + 0.01, 0.0), (3.86, 1.0),
                    (4.02, 0.9), (T_CLOSE + 0.06, 0.9), (4.70, 0.6), (4.85, 0.0))
L_REGRIP = Curve((4.85, 0.0), (4.93, 1.0), (5.06, 0.0))


def belt_hands(p, t, end=5.5):
    left_hand(p, L_BELT, t, L_OFF(t))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(L_OPEN_HAND(t)))
    if t > 4.85:
        curls = blend_curls(curls, hands.POSES["rpd_clamp"], float(L_REGRIP(t)))
    p.hand_l = (pos, q, curls)
    w = ease("inout", t, 0.10, 0.40) * (1 - ease("inout", t, 4.45, 4.85))
    p.shoulder_l = w * V(-0.060, 0.040, 0.050)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("inout", t, 0.1, 0.3) * (1 - ease("inout", t, end - 0.6,
                                                                                         end - 0.3)), 0))


RING_EVENTS = (     # time, offset ring (m), turn ring (deg), camera ring (deg), freq, zeta
    (T_OPEN, V(0.0, 0.0040, 0.0020), V(1.6, 0.2, -2.0), V(0.45, 0.05, -0.55), 7.0, 0.36),
    (T_BELT_OUT, V(0.0, 0.0020, 0.0), V(0.6, 0.0, -0.8), V(0.15, 0.0, -0.2), 6.0, 0.4),
    (T_OUT, V(0.0, 0.0050, 0.0), V(1.2, 0.0, 1.0), V(0.3, 0.0, 0.4), 6.0, 0.38),
    (T_HOOK, V(0.0, 0.0060, -0.0020), V(2.6, 0.2, 1.3), V(0.75, 0.1, 0.6), 7.5, 0.34),
    (T_LAY, V(0.0, -0.0020, 0.0), V(-0.6, 0.0, 0.5), V(-0.2, 0.0, 0.2), 7.0, 0.4),
    (T_CLOSE, V(0.0, -0.0075, -0.0030), V(-3.6, 0.5, 2.6), V(-1.0, 0.15, 0.9), 8.0, 0.32),
)


def _rings(index, extra=()):
    return tuple((e[0], e[index], e[4], e[5]) for e in RING_EVENTS) + tuple(extra)


R_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.003, -0.006, 0.002)), (0.44, V(-0.046, 0.040, -0.012), "flow"),
                     (0.64, V(-0.047, 0.044, -0.012)), (1.00, V(-0.049, 0.042, -0.012)), (1.50, V(-0.053, 0.034, -0.010)),
                     (2.30, V(-0.051, 0.033, -0.010)), (3.05, V(-0.049, 0.036, -0.011)), (3.60, V(-0.046, 0.044, -0.012)),
                     (4.30, V(-0.044, 0.040, -0.010)), (4.95, V(0.004, -0.004, 0.002), "flow"),
                     (5.25, V(-0.001, 0.001, 0.0)), (5.50, V(0, 0, 0))),
               rings=_rings(1), drifts=((0.70, 1.40, V(0.0012, 0.0010, 0.0008), 11, 0.5),
                                        (1.80, 2.90, V(0.0014, 0.0012, 0.0008), 12, 0.45),
                                        (3.80, 4.20, V(0.0008, 0.0008, 0.0005), 13, 0.6)), end=5.5)
R_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(-1.2, -1.5, 3.0)), (0.46, V(10.0, 9.0, -30.0), "flow"),
                     (1.00, V(11.0, 8.0, -31.0)), (1.50, V(9.0, 7.0, -24.0)), (2.30, V(9.0, 7.0, -23.0)),
                     (3.10, V(10.0, 8.0, -26.0)), (3.70, V(12.0, 9.0, -32.0)), (4.30, V(11.0, 9.0, -30.0)),
                     (4.95, V(-1.5, -1.0, 2.2), "flow"), (5.25, V(0.3, 0.2, -0.3)), (5.50, V(0, 0, 0))),
               rings=_rings(2), drifts=((0.70, 1.40, V(0.6, 0.5, 0.9), 21, 0.5),
                                        (1.80, 2.90, V(0.7, 0.6, 1.0), 22, 0.45),
                                        (3.80, 4.20, V(0.4, 0.3, 0.5), 23, 0.6)), end=5.5)
R_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.48, V(-0.8, 0.3, -1.8)), (1.50, V(-1.0, 0.2, -1.2)),
                     (3.05, V(-0.8, 0.2, -1.4)), (4.30, V(-0.7, 0.3, -1.7)), (4.95, V(0.3, -0.1, 0.4)),
                     (5.50, V(0, 0, 0))), rings=_rings(3), end=5.5)


def lighter(t):
    """0..1 while the drum is off: the gun rides a few millimetres higher in the hands without its 3 kg drum."""
    return smoothstep(T_OUT, T_OUT + 0.25, t) * (1 - smoothstep(T_HOOK, T_HOOK + 0.25, t))


def weight_shift(t):
    w = lighter(t)
    return V(0.0, 0.0035 * w, 0.0), V(0.0, 0.8 * w, 0.0)


def reload_pose(t):
    p = rig.Pose()
    dt, dr = weight_shift(t)
    apply_gun(p, R_OFF(t) + dt, R_ROT(t) + dr)
    mechanism(p, t)
    belt_hands(p, t)
    cam(p, *R_CAM(t))
    return p


RELOAD_EVENTS = [(0.03, "inspect_move"), (T_OPEN - 0.01, "cover_open"), (T_BELT_OUT, "belt"), (T_OUT, "mag_out"),
                 (T_HOOK, "mag_in"), (T_LAY, "belt"), (T_CLOSE, "cover_close")]
RELOAD_KEYS = (T_OPEN, T_OUT, T_HIDE, T_SHOW, T_HOOK, T_BELT_DROP, T_BELT_SHOW, T_LAY, T_CLOSE)
RELOAD = Clip("reload", 5.50, reload_pose, events=RELOAD_EVENTS, extra_times=RELOAD_KEYS)

# ---------------------------------------------------------------------------------- reload_empty
# the same belt change, then a reach-under pull of the charging handle: slow back against the spring, let go, slam
T_REACH, T_GRAB, T_BACK, T_LET, T_FWD = 4.62, 4.74, 4.98, 5.04, 5.11
E_BOLT = Curve((0.0, 0.0), (T_GRAB, 0.0, "stop"), (4.80, 0.018), (T_BACK, TRAVEL, ("hit", 1.4)),
               (T_LET, TRAVEL - 0.0008, "stop"), (T_FWD, 0.0, ("hit", 2.2)), (T_FWD + 0.012, 0.0013, "stop"),
               (T_FWD + 0.03, 0.0))
E_EVENTS_EXTRA = (
    (T_BACK, V(0.0, 0.0, 0.0030), V(0.6, 0.6, -1.2), V(0.3, 0.1, -0.4), 7.0, 0.42),
    (T_FWD, V(0.0, -0.0040, -0.0035), V(-2.6, -0.4, 1.8), V(-0.8, 0.15, 0.8), 8.0, 0.32),
)


def _erings(index):
    return _rings(index, tuple((e[0], e[index], e[4], e[5]) for e in E_EVENTS_EXTRA))


E_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.003, -0.006, 0.002)), (0.44, V(-0.046, 0.040, -0.012), "flow"),
                     (0.64, V(-0.047, 0.044, -0.012)), (1.00, V(-0.049, 0.042, -0.012)), (1.50, V(-0.053, 0.034, -0.010)),
                     (2.30, V(-0.051, 0.033, -0.010)), (3.05, V(-0.049, 0.036, -0.011)), (3.60, V(-0.046, 0.044, -0.012)),
                     (4.30, V(-0.044, 0.040, -0.010)), (4.62, V(-0.034, 0.040, -0.055)), (T_BACK, V(-0.035, 0.041, -0.050)),
                     (T_FWD + 0.02, V(-0.033, 0.037, -0.057)), (5.55, V(0.003, -0.003, 0.002), "flow"),
                     (5.80, V(-0.001, 0.001, 0.0)), (6.00, V(0, 0, 0))),
               rings=_erings(1), drifts=((0.70, 1.40, V(0.0012, 0.0010, 0.0008), 11, 0.5),
                                         (1.80, 2.90, V(0.0014, 0.0012, 0.0008), 12, 0.45)), end=6.0)
E_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(-1.2, -1.5, 3.0)), (0.46, V(10.0, 9.0, -30.0), "flow"),
                     (1.00, V(11.0, 8.0, -31.0)), (1.50, V(9.0, 7.0, -24.0)), (2.30, V(9.0, 7.0, -23.0)),
                     (3.10, V(10.0, 8.0, -26.0)), (3.70, V(12.0, 9.0, -32.0)), (4.30, V(11.0, 9.0, -30.0)),
                     (4.62, V(5.0, 9.0, -33.0)), (T_BACK, V(4.5, 9.6, -35.0)), (T_FWD + 0.02, V(5.0, 7.6, -32.0)),
                     (5.55, V(-1.2, -0.9, 1.8), "flow"), (5.80, V(0.3, 0.2, -0.3)), (6.00, V(0, 0, 0))),
               rings=_erings(2), drifts=((0.70, 1.40, V(0.6, 0.5, 0.9), 21, 0.5),
                                         (1.80, 2.90, V(0.7, 0.6, 1.0), 22, 0.45)), end=6.0)
E_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.2, 0, 0.5)), (0.48, V(-0.8, 0.3, -1.8)), (1.50, V(-1.0, 0.2, -1.2)),
                     (3.05, V(-0.8, 0.2, -1.4)), (4.30, V(-0.7, 0.3, -1.7)), (4.70, V(-0.4, 0.3, -2.0)),
                     (5.55, V(0.3, -0.1, 0.4)), (6.00, V(0, 0, 0))), rings=_erings(3), end=6.0)
E_LEFT = Weights((T_CLOSE + 0.04, {"cover": 1}), (T_REACH, {"charging": 1}), (T_LET, {"charging": 1}),
                 (5.28, {"world": 1}), (5.55, {"support": 1}))
E_LEFT_OFF = Curve((T_CLOSE + 0.04, V(0, 0, 0)), (4.50, V(-0.014, -0.040, 0.022), "flow"), (T_REACH, V(0, 0, 0)),
                   (T_LET, V(0, 0, 0)), (5.18, V(0.006, -0.020, 0.030), "flow"), (5.55, V(0, 0, 0)))
E_FINGERS = Curve((T_CLOSE, 0.9), (4.50, 1.0), (T_REACH - 0.04, 1.0), (T_GRAB, 0.0), (T_LET - 0.005, 0.0),
                  (T_LET + 0.04, 1.0), (5.38, 1.0), (5.55, 0.0))
E_REGRIP = Curve((5.55, 0.0), (5.63, 1.0), (5.76, 0.0))
POLE_REACH = V(-0.55, -0.80, -0.75)


def let_go(p):
    pos, q, _ = p.anchor("charging")
    return pos + p.gun_dir(V(-0.010, -0.035, 0.040)), q, hands.blend({"spread": 1.0})


def reload_empty_pose(t):
    p = rig.Pose()
    dt, dr = weight_shift(t)
    apply_gun(p, E_OFF(t) + dt, E_ROT(t) + dr)
    mechanism(p, t)
    p.bolt_z = float(E_BOLT(t))
    if t <= T_CLOSE + 0.04:
        belt_hands(p, t, end=6.0)
    else:
        left_hand(p, E_LEFT, t, E_LEFT_OFF(t), world=let_go(p))
        pos, q, curls = p.hand_l
        curls = open_fingers(curls, float(E_FINGERS(t)))
        if t > 5.55:
            curls = blend_curls(curls, hands.POSES["rpd_clamp"], float(E_REGRIP(t)))
        p.hand_l = (pos, q, curls)
        w = 1 - ease("inout", t, 5.2, 5.55)
        p.shoulder_l = w * V(-0.030, -0.150, 0.030)
        p.shoulder_r = w * V(-0.010, 0.010, -0.050) * ease("inout", t, T_CLOSE, T_REACH)
        p.fingers_r = grip_fingers(p, index=(-4.0 * (1 - ease("inout", t, 5.4, 5.7)), 0))
    wp = ease("inout", t, 4.45, T_REACH) * (1 - ease("inout", t, 5.15, 5.45))
    if wp > 0:
        p.pole_l = (1 - wp) * rig.POLE_L + wp * POLE_REACH
    cam(p, *E_CAM(t))
    return p


RELOAD_EMPTY = Clip("reload_empty", 6.00, reload_empty_pose,
                    events=RELOAD_EVENTS + [(T_BACK, "bolt_back"), (T_FWD, "bolt_forward")],
                    extra_times=RELOAD_KEYS + (T_GRAB, T_BACK, T_LET, T_FWD))

# --------------------------------------------------------------------------------------- inspect
# a heavy swing to show the left flank and the belt, the cover lifted a little for a look and slammed, a roll to the
# right side (the bolt rattles in its rails as the gun rolls), back with a long settle
I_PEEK, I_SHUT = 1.95, 2.62
I_COVER = Curve((0.0, 0.0), (1.62, 0.0), (1.68, 1.2, "stop"), (I_PEEK, -30.0), (2.40, -31.0, "stop"),
                (2.48, -34.0, "stop"), (I_SHUT, 0.0, ("hit", 2.4)), (I_SHUT + 0.03, 1.0, "stop"), (I_SHUT + 0.08, 0.0))
I_BOLT = Curve((0.0, 0.0), (3.08, 0.0, "stop"), (3.16, 0.0045, "stop"), (3.30, 0.0, ("hit", 2.0)), (3.36, 0.0))
I_OFF = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(0.003, -0.005, 0.002)), (0.62, V(-0.058, 0.030, -0.060), "flow"),
                     (1.40, V(-0.060, 0.032, -0.062)), (1.95, V(-0.052, 0.040, -0.050)), (2.62, V(-0.054, 0.038, -0.052)),
                     (3.20, V(-0.082, -0.070, -0.066), "flow"), (3.70, V(-0.084, -0.068, -0.068)),
                     (4.20, V(0.003, -0.004, 0.002), "flow"), (4.50, V(0, 0, 0))),
               rings=((I_SHUT, V(0.0, -0.0060, -0.0025), 8.0, 0.32), (3.30, V(0.0, 0.0010, 0.0010), 9.0, 0.4)),
               drifts=((0.70, 1.55, V(0.0012, 0.0010, 0.0008), 31, 0.45), (3.25, 3.80, V(0.0010, 0.0008, 0.0006), 32, 0.5)),
               end=4.5)
I_ROT = Layers(Curve((0.00, V(0, 0, 0)), (0.12, V(-1.4, -1.6, 2.6)), (0.62, V(50.0, 9.0, -14.0), "flow"),
                     (1.40, V(48.0, 10.0, -12.0)), (1.95, V(30.0, 12.0, -30.0)), (2.62, V(31.0, 11.0, -29.0)),
                     (3.20, V(11.0, 33.0, 78.0), "flow"), (3.70, V(10.0, 34.0, 80.0)),
                     (4.20, V(-0.8, -1.0, -1.4), "flow"), (4.50, V(0, 0, 0))),
               rings=((I_SHUT, V(-3.0, 0.4, 2.2), 8.0, 0.32), (3.30, V(0.4, 0.0, 0.6), 9.0, 0.4)),
               drifts=((0.70, 1.55, V(0.8, 0.6, 1.0), 41, 0.45), (3.25, 3.80, V(0.6, 0.5, 0.8), 42, 0.5)), end=4.5)
I_CAM = Layers(Curve((0.00, V(0, 0, 0)), (0.14, V(0.2, 0, 0.5)), (0.62, V(0.5, -0.4, 1.3)), (1.40, V(0.3, -0.2, 0.8)),
                     (1.95, V(-0.4, 0.2, -1.0)), (2.62, V(-0.3, 0.2, -0.9)), (3.20, V(-0.3, 0.4, -1.4)),
                     (3.70, V(-0.2, 0.3, -1.1)), (4.20, V(0.1, 0, 0.2)), (4.50, V(0, 0, 0))),
               rings=((I_SHUT, V(-0.9, 0.1, 0.8), 8.0, 0.34),), end=4.5)
I_LEFT = Weights((0.00, {"support": 1}), (1.40, {"support": 1}), (1.62, {"cover_lift": 1}), (2.40, {"cover_lift": 1}),
                 (2.48, {"cover": 1}), (I_SHUT + 0.03, {"cover": 1}), (2.95, {"support": 1}))
I_LEFT_OFF = Curve((0.00, V(0, 0, 0)), (1.40, V(0, 0, 0)), (1.52, V(-0.016, 0.018, 0.016), "flow"), (1.62, V(0, 0, 0)),
                   (I_SHUT + 0.03, V(0, 0, 0)), (2.78, V(-0.012, -0.018, 0.010), "flow"), (2.95, V(0, 0, 0)))
I_OPEN = Curve((0.0, 0.0), (1.40, 0.0), (1.48, 1.0), (1.60, 0.0), (2.40, 0.0), (2.46, 0.9), (I_SHUT + 0.05, 0.9),
               (2.85, 0.4), (2.95, 0.0))


def inspect_pose(t):
    p = rig.Pose()
    apply_gun(p, I_OFF(t), I_ROT(t))
    p.cover = float(I_COVER(t))
    p.bolt_z = float(I_BOLT(t))
    left_hand(p, I_LEFT, t, I_LEFT_OFF(t))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(I_OPEN(t)))
    w = 2 * np.pi * t / 1.7
    curls = hands.adjust(curls, index=(-3 * np.sin(w) * (t < 1.4), 0), middle=(-2 * np.sin(w + 0.5) * (t < 1.4), 0))
    p.hand_l = (pos, q, curls)
    wl = ease("inout", t, 1.40, 1.62) * (1 - ease("inout", t, 2.70, 2.95))
    p.shoulder_l = wl * V(-0.050, 0.050, 0.040)
    ws = ease("inout", t, 0.12, 0.62) * (1 - ease("inout", t, 2.62, 3.20))
    wr = ease("inout", t, 2.62, 3.20) * (1 - ease("inout", t, 3.70, 4.20))
    p.shoulder_r = ws * V(0.0, 0.11, -0.11) + wr * V(0.013, -0.065, 0.014)
    p.pole_r = rig.POLE_R * (1 - ws - wr) + V(1.0, 0.0, 0.3) * ws + V(0.39, 0.18, 0.90) * wr
    p.belt_t = BELT_REST + V(0.0, 0.0015 * np.sin(2 * np.pi * t / 1.3) * smoothstep(0.3, 0.8, t)
                             * (1 - smoothstep(3.9, 4.4, t)), 0.0)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3), 0.0))
    cam(p, *I_CAM(t))
    return p


INSPECT = Clip("inspect", 4.50, inspect_pose,
               events=[(0.02, "inspect_move"), (1.66, "cover_open"), (I_SHUT, "cover_close"), (2.85, "inspect_move"),
                       (3.30, "bolt_forward")],
               extra_times=(I_PEEK, I_SHUT, 3.30))


# ------------------------------------------------------------------------------------ locomotion
WALK_DUR = 1.00


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    half = WALK_DUR / 2
    b = foot(t, half, 0.02, FOOT_WALK)
    bl = foot(t, half, 0.08, FOOT_WALK)
    side = np.cos(w * (t - 0.05))
    side_l = np.cos(w * (t - 0.12))
    apply_gun(p, V(0.0040 * side, 0.0006 + 0.0056 * b, -0.0016 * b - 0.0010 * np.sin(w * t)),
              V(0.9 * np.sin(w * (t - 0.05)), 0.15 + 0.85 * bl, 1.55 * side_l))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(2.2 * bl, 0), ring=(2.2 * bl, 0))
    p.belt_t = BELT_REST + V(0.0, 0.0010 * foot(t, half, 0.12, FOOT_WALK), 0.0)
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.150, -0.112, -0.300)
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
    bl = foot(t, half, 0.06)
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * (t - 0.02))
    p.gun_t = LOW_T + V(0.006 * side, 0.011 * b + 0.004 * swing, 0.011 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * (t - 0.03)), 3.6 * bl + 3.2 * swing, 3.8 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.belt_t = BELT_REST + V(0.0, 0.0022 * foot(t, half, 0.09), 0.0)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.150, -0.300)
TAC_Q = gun_frame((-0.16, 0.88, -0.45), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    half = TAC_DUR / 2
    b = foot(t, half)
    bl = foot(t, half, 0.05)
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.004 + 0.013 * b, -0.005 * bl)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.0 * np.sin(w * (t - 0.03)), 3.2 * bl, 3.8 * np.cos(w * (t - 0.08))))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = punch_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.15)
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.belt_t = BELT_REST + V(0.0, 0.0020 * foot(t, half, 0.08), 0.0)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, FIRE_ADS, FIRE_LAST_ADS, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT,
            TAC_SPRINT]
