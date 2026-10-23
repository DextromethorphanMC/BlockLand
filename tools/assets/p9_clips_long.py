"""P9 reload, reload_empty and inspect clips with hand / finger work (sections 15-16, 31).
Event names are fixed (the runtime stretches clips to guns.json).

Beats of the reloads: a small counter-move, the roll into the presentation (arriving with overshoot, then a moving
hold), the thumb on the release (the press jolts the gun, the gun lifts as the magazine leaves), the old magazine
slides out and falls under gravity tumbling, the support hand fetches the new one off screen and brings it up on an
arc, aligns it at the well (slow), pushes it most of the way, drops under it and slaps it home (mag_in: the gun
jolts up and rings, the camera nods), then the gun swings back to ready through a small overshoot and the support
hand re-grips with a squeeze. The empty reload rolls upright while the support thumb climbs to the slide stop, loads
it (the gun dips a hair), drops it: the slide slams home (the muzzle dips and rings, the camera kicks)."""
import numpy as np

import armrig
import hands
import p9_model as pm
import p9_rig as rig
from anim import Curve, drift, ring
from p9_clips import V, Clip, Weights, apply_gun, cam, left_hand, squeeze, until
from vmath import quat_axis_angle, quat_euler, quat_mul, quat_rotate

AX = pm.AXIS                              # down the mag well, gun space
MAG_REST = rig.NODES["mag"][1]


# ------------------------------------------------------------------- right thumb on the magazine release
def _release_target():
    """Grip shifted so the thumb lands on the release button: roll about the grip axis + thumb curls (fitted)."""
    _, (w0, q0, pose) = rig.ANCHORS["grip"]
    base = hands.blend({pose: 1.0})
    button = rig.G((-0.0168, -0.0337, 0.0372))
    pivot = rig.G(pm.grip_center(0.035))
    best = None
    for roll in range(-24, 1, 4):
        for slide in (-0.006, -0.003, 0.0, 0.003):
            qr = quat_axis_angle(AX, roll)
            w = pivot + quat_rotate(qr, w0 - pivot) + slide * pm.FRONT
            q = quat_mul(qr, q0)
            d, cur = hands.fit_finger("R", w, q, base, "thumb", button, ranges=((-120, 60), (-90, 40)), step=6)
            score = d + 0.00015 * abs(roll)
            if best is None or score < best[0]:
                best = (score, w, q, cur, d)
    return best[1], best[2], best[3], best[4]


REL_W, REL_Q, REL_THUMB, REL_MISS = _release_target()


def right_press(p, w):
    """Right hand blended from the grip towards the release press (w = 0..1); thumb pushes in at full weight."""
    grip = p.anchor("grip")
    press = (p.gun_point(REL_W), quat_mul(p.gun_q, REL_Q), hands.adjust(grip[2], thumb=np.subtract(REL_THUMB, grip[2]["thumb"])))
    p.hand_r = armrig.blend_targets([(1 - w, grip), (w, press)]) if w > 1e-4 else grip


# ------------------------------------------------------------------------------ shared magazine swap
T_RELEASE = 0.27                          # thumb hits the release (event)
T_DROP = 0.29                             # magazine starts to slide out
T_HIDE = 0.60                             # dropped magazine is far below the screen
T_SHOW = 0.66                             # the new one appears in the hand (off screen)
T_ALIGN = 1.04                            # its tip is in the well: alignment beat
T_PUSHED = 1.11                           # pushed most of the way by the fingers
T_LETGO = 1.165                           # fingers off (it springs back a hair), the palm drops under it
T_SEAT = 1.21                             # the slap seats it (mag_in)

# the new magazine, gun space: up from below on an arc, slow alignment at the well, the push, the slap
NEW_OFF = Curve((T_HIDE, AX * 0.30 + V(-0.070, 0.0, 0.050)), (0.84, AX * 0.120 + V(-0.030, 0.0, 0.018), "flow"),
                (0.97, AX * 0.050 + V(-0.004, 0.0, 0.002)), (T_ALIGN, AX * 0.034, "stop"),
                (T_PUSHED, AX * 0.006, ("hit", 1.3)), (T_LETGO, AX * 0.0072, "stop"),
                (T_SEAT, AX * -0.0025, ("hit", 2.2)), (1.25, V(0, 0, 0), "stop"))
NEW_ROT = Curve((T_HIDE, V(12, -28, 32)), (0.84, V(5, -12, 14), "flow"), (0.97, V(1.0, -2.0, 2.0)),
                (T_ALIGN, V(0, 0, 0), "stop"))
G = 9.0                                   # m/s^2 along the well while the magazine slides / falls out


def mag_swap(p, t):
    """Old magazine slides out of the well and falls tumbling; the new one rides up in the left hand and is seated."""
    if t < T_DROP:
        return
    if t < T_HIDE:
        dt = t - T_DROP
        d = 0.22 * dt + 0.5 * G * dt * dt              # spring kick out of the well, then gravity
        free = max(0.0, dt - 0.045)                     # clear of the well: it drifts and tumbles
        p.mag_t = MAG_REST + AX * d + V(-0.10 * free, -0.6 * free * free, 0.03 * free)
        p.mag_q = quat_mul(rig.MAG_Q, quat_euler(400 * free ** 2, -480 * free ** 2, 360 * free ** 2))   # spin-up
        return
    if t < T_SHOW:
        p.mag_s = 0.0                                   # hidden while the hand fetches the new one (off screen)
    p.mag_t = MAG_REST + NEW_OFF(t)
    p.mag_q = quat_mul(rig.MAG_Q, quat_euler(*NEW_ROT(t)))


POUCH = V(-0.065, -0.460, -0.150)                       # off-screen spot where the new mag is grabbed


def pouch(p):
    q = quat_mul(p.gun_q, rig.ANCHORS["mag"][1][1])
    return POUCH, q, hands.blend({"mag": 1.0})


# support hand: lets go of the grip (fingers open), drops out of view to the pouch, returns holding the new magazine
# (its anchor rides on the magazine), pushes it in, swaps to an open palm under the base plate and slaps it home
L_RELOAD = Weights((0.00, {"support": 1}), (0.07, {"support": 1}), (0.30, {"world": 1}), (0.56, {"world": 1}),
                   (T_SHOW, {"mag": 1}), (1.105, {"mag": 1}), (T_LETGO + 0.01, {"slap": 1}), (1.25, {"slap": 1}))
# offsets (gun space): an arc down-left on the way out; under the magazine the palm winds up (drops 2.8 cm) and
# drives up into the slap
L_OFF_RELOAD = Curve((0.00, V(0, 0, 0)), (0.07, V(0.002, 0.002, 0.0)), (0.18, V(-0.022, -0.012, 0.012), "flow"),
                     (0.30, V(0, 0, 0)), (1.105, V(0, 0, 0)), (1.15, V(-0.004, -0.028, 0.006), "stop"),
                     (T_SEAT, V(0.0, 0.002, 0.0), ("hit", 2.2)), (1.25, V(0, 0, 0), "stop"))
L_FINGERS_OPEN = Curve((0.0, 0.0), (0.05, 0.0), (0.13, 1.0), (0.30, 1.0), (0.40, 0.0))     # let go of the grip
W_PRESS = Curve((0.0, 0.0), (0.12, 0.0), (0.22, 1.0, "stop"), (0.31, 1.0, "stop"), (0.44, 0.0))
THUMB_PUSH = Curve((0.0, 0.0), (0.23, 0.0), (T_RELEASE, -11.0, "hit"), (0.32, 0.0, "stop"))


def _reload_hands(p, t, end_weights, end_offset=None, world_end=None):
    right_press(p, float(W_PRESS(t)))
    thumb = p.hand_r[2]["thumb"] + np.array([float(THUMB_PUSH(t)), 0.0])
    p.hand_r = (p.hand_r[0], p.hand_r[1], dict(p.hand_r[2], thumb=thumb))
    if t < 1.25:
        left_hand(p, L_RELOAD, t, L_OFF_RELOAD(t), world=pouch(p))
        o = float(np.clip(L_FINGERS_OPEN(t), 0, 1))
        if o > 0:
            p.hand_l = (p.hand_l[0], p.hand_l[1], armrig.blend_targets(
                [(1 - o, (0, p.hand_l[1], p.hand_l[2])), (o, (0, p.hand_l[1], hands.blend({"relaxed": 1})))])[2])
    else:
        left_hand(p, end_weights, t, end_offset(t) if end_offset else (0, 0, 0), world=world_end)


def reactions(t, seat=True):
    """The gun answering the hands: the thumb's press on the release, the magazine leaving (lighter: it lifts),
    the slap seating the new one. -> (offset, euler, camera)."""
    off = ring(t, T_RELEASE, V(0.0008, -0.0004, 0.0), 9.0, 0.4) + ring(t, T_DROP + 0.01, V(0.0, 0.0012, 0.0), 8.0, 0.4)
    rot = ring(t, T_RELEASE, V(0.3, -0.2, 0.9), 9.0, 0.4) + ring(t, T_DROP + 0.01, V(0.0, 0.6, 0.0), 8.0, 0.4)
    view = ring(t, T_RELEASE, V(0.0, 0.0, 0.25), 8.0, 0.5)
    if seat:
        off = off + ring(t, T_SEAT, V(0.0006, 0.0036, -0.0010), 8.5, 0.38)
        rot = rot + ring(t, T_SEAT, V(-0.6, 1.3, 1.6), 8.5, 0.38)
        view = view + ring(t, T_SEAT, V(-1.15, 0.12, 0.55), 7.5, 0.45)
    return off, rot, view


# ---------------------------------------------------------------------------------------- reload
# presentation: rolled 26 deg (top in), muzzle up 12, turned 8 so the magazine well faces the support hand
PRESENT_T, PRESENT_R = V(-0.010, 0.012, 0.004), V(8.0, 12.0, -26.0)
# the hold is a moving hold with intent: the gun tips after the falling magazine, then turns its well towards the
# incoming one and comes down a little to meet the hand (braced for the slap)
HOLD_T = ((0.72, PRESENT_T + V(-0.0010, 0.0016, 0.0)), (1.02, V(-0.0104, 0.0104, 0.0046)),
          (1.19, V(-0.0098, 0.0084, 0.0053), "stop"))
HOLD_ROT = ((0.72, V(8.8, 12.8, -27.4)), (1.02, V(7.3, 10.6, -24.0)), (1.19, V(6.9, 9.7, -22.8), "stop"))
R_OFF = Curve((0.00, V(0, 0, 0)), (0.09, V(0.002, -0.003, 0.002), "stop"),
              (0.30, PRESENT_T * 1.12 + V(0, 0, 0.0002), "stop"), (0.46, PRESENT_T), *HOLD_T,
              (1.29, V(-0.0110, 0.0118, 0.0044), "stop"), (1.62, V(0.0016, -0.0021, 0.0008), "stop"),
              (1.78, V(-0.0003, 0.0004, -0.0002)), (1.90, V(0, 0, 0), "stop"))
R_ROT = Curve((0.00, V(0, 0, 0)), (0.09, V(-1.0, -2.0, 3.5), "stop"),
              (0.30, V(9.0, 13.6, -28.6), "stop"), (0.46, PRESENT_R), *HOLD_ROT,
              (1.29, V(7.6, 11.4, -24.4), "stop"), (1.62, V(-0.8, -1.4, 2.1), "stop"),
              (1.78, V(0.2, 0.35, -0.45)), (1.90, V(0, 0, 0), "stop"))
R_CAM = Curve((0.00, V(0, 0, 0)), (0.11, V(0.15, 0.0, 0.40), "stop"), (0.38, V(-0.70, 0.10, -1.40), "stop"),
              (0.90, V(-0.45, 0.08, -1.00)), (1.18, V(-0.30, 0.0, -0.80)), (1.46, V(0.22, 0.0, 0.45), "stop"),
              (1.90, V(0, 0, 0)))
L_RELOAD_END = Weights((1.25, {"slap": 1}), (1.52, {"support": 1}))
HOLD_R = (0.46, 1.10)                     # the presentation hold, kept alive with a little drift


def reload_pose(t):
    p = rig.Pose()
    off, rot, view = reactions(t)
    hold_t = drift(t, V(0.0010, 0.0009, 0.0006), seed=3, freq=0.7, start=HOLD_R[0] - 0.1, end=HOLD_R[1], fade=0.2)
    hold_r = drift(t, V(0.8, 0.6, 1.0), seed=4, freq=0.6, start=HOLD_R[0] - 0.1, end=HOLD_R[1], fade=0.2)
    fade = until(t, 1.90, 0.12)
    apply_gun(p, R_OFF(t) + (off + hold_t) * fade, R_ROT(t) + (rot + hold_r) * fade)
    mag_swap(p, t)
    _reload_hands(p, t, L_RELOAD_END)
    regrip = -8.0 * float(ring(t, 1.50, 1.0, 5.0, 0.6)) * until(t, 1.90, 0.15)
    p.hand_l = (p.hand_l[0], p.hand_l[1], squeeze(p.hand_l[2], regrip))
    cam(p, *(R_CAM(t) + view * fade))
    return p


MAG_EVENTS = [(T_RELEASE, "mag_release"), (0.33, "mag_out"), (T_SEAT, "mag_in")]
RELOAD_KEYS = (T_RELEASE, T_DROP, T_HIDE, T_SHOW, T_ALIGN, T_PUSHED, T_LETGO, T_SEAT)
RELOAD = Clip("reload", 1.90, reload_pose, events=MAG_EVENTS, extra_times=RELOAD_KEYS, force_nodes=("mag",))

# --------------------------------------------------------------------------------- reload_empty
# slide starts locked back; after the slap the gun rolls upright (from 1.21 over 0.2 s, not a snap) while the support
# hand slides up the frame, its thumb loads onto the slide stop (the gun dips a hair against the press) and drops it
# (slide_release): the slide slams home (slide_forward), the muzzle dips and rings, the camera kicks. The support
# arm stays low and never crosses the crosshair (an overhand rack would drag the forearm across the screen).
T_THUMB, T_LET_GO, T_HOME = 1.40, 1.46, 1.485
STOP_SHIFT = V(-0.006, 0.036, 0.018)              # support anchor moved up the left side of the frame (gun space)
STOP_THUMB = (36.0, -42.0)                        # thumb curls putting its tip on the slide stop tab (fitted)
UPRIGHT_T, UPRIGHT_R = V(-0.016, 0.006, 0.003), V(6.0, 4.5, 8.0)     # flank (slide stop) turned to the view
E_OFF = Curve((0.00, V(0, 0, 0)), (0.09, V(0.002, -0.003, 0.002), "stop"),
              (0.30, PRESENT_T * 1.12 + V(0, 0, 0.0002), "stop"), (0.46, PRESENT_T), *HOLD_T,
              (1.30, V(-0.0140, 0.0082, 0.0038), "flow"), (T_THUMB, UPRIGHT_T + V(0.0, 0.0006, 0.0), "stop"),
              (T_LET_GO, UPRIGHT_T + V(0.0, -0.0006, 0.0), "stop"),
              (1.66, V(-0.0100, 0.0050, 0.0030)), (2.02, V(0.0012, -0.0016, 0.0006), "stop"),
              (2.17, V(-0.0002, 0.0003, -0.0001)), (2.30, V(0, 0, 0), "stop"))
E_ROT = Curve((0.00, V(0, 0, 0)), (0.09, V(-1.0, -2.0, 3.5), "stop"),
              (0.30, V(9.0, 13.6, -28.6), "stop"), (0.46, PRESENT_R), *HOLD_ROT,
              (1.30, V(7.2, 6.8, -7.0), "flow"), (T_THUMB, UPRIGHT_R + V(0.2, 0.3, 1.4), "stop"),
              (T_LET_GO, UPRIGHT_R + V(0.0, -0.4, 0.8), "stop"),
              (1.66, V(4.0, 3.0, 5.0)), (2.02, V(-0.6, -0.9, 1.0), "stop"),
              (2.17, V(0.15, 0.25, -0.3)), (2.30, V(0, 0, 0), "stop"))
E_SLIDE = Curve((0.0, rig.SLIDE_LOCK), (T_LET_GO, rig.SLIDE_LOCK, "stop"), (T_HOME, 0.0, ("hit", 2.2)),
                (T_HOME + 0.010, 0.0011, "stop"), (T_HOME + 0.024, 0.0, "stop"), (2.30, 0.0))
E_STOP = Curve((0.0, rig.STOP_UP), (T_LET_GO - 0.03, rig.STOP_UP, "stop"), (T_LET_GO, 0.0, "hit"), (2.30, 0.0))
E_HAMMER = Curve((0.0, 46.0), (T_LET_GO + 0.012, 46.0, "stop"), (T_HOME + 0.004, rig.HAMMER_COCKED, "hit"),
                 (2.30, rig.HAMMER_COCKED))
E_CAM = Curve((0.00, V(0, 0, 0)), (0.11, V(0.15, 0.0, 0.40), "stop"), (0.38, V(-0.70, 0.10, -1.40), "stop"),
              (0.90, V(-0.45, 0.08, -1.00)), (1.18, V(-0.30, 0.0, -0.80)), (1.36, V(-0.10, 0.05, 0.30)),
              (T_LET_GO, V(-0.05, 0.05, 0.40), "stop"), (1.80, V(0.12, 0.0, 0.20)), (2.30, V(0, 0, 0)))
L_EMPTY_END = Weights((1.25, {"slap": 1}), (1.31, {"support": 1}), (T_THUMB - 0.04, {"world": 1}),
                      (T_HOME + 0.05, {"world": 1}), (1.76, {"support": 1}))
L_OFF_EMPTY = Curve((1.25, V(0, 0, 0)), (1.28, V(-0.008, -0.010, 0.006), "stop"), (1.32, V(0, 0, 0)),
                    (2.08, V(0, 0, 0)))
STOP_PRESS = Curve((0.0, 0.0), (T_THUMB, 0.0, "stop"), (T_LET_GO - 0.006, 1.0, "hit"), (T_LET_GO + 0.07, 0.25, "stop"),
                   (1.62, 0.0))
HOLD_E = (0.46, 1.10)


def stop_hand(p, t):
    """Support hand up the frame with the thumb on the slide stop; the press pushes the lever down."""
    _, (w, q, pose) = rig.ANCHORS["support"]
    curls = hands.blend({pose: 1.0})
    curls = hands.adjust(curls, thumb=np.subtract(STOP_THUMB, curls["thumb"]))
    press = float(STOP_PRESS(t))
    pos = p.gun_point(np.asarray(w) + STOP_SHIFT + V(0.0, -0.0032 * press, 0.0))
    return pos, quat_mul(p.gun_q, q), hands.adjust(curls, thumb=(-6.0 * press, -4.0 * press))


def slam(t):
    """The slide driving home: the muzzle dips and rings, the gun is nudged forward, the camera kicks."""
    off = ring(t, T_HOME, V(0.0004, -0.0012, -0.0022), 9.0, 0.36)
    rot = ring(t, T_HOME, V(0.4, -1.8, -0.9), 9.0, 0.36)
    view = ring(t, T_HOME, V(0.95, -0.15, -0.55), 7.0, 0.42)
    return off, rot, view


def reload_empty_pose(t):
    p = rig.Pose()
    off, rot, view = reactions(t)
    s_off, s_rot, s_view = slam(t)
    hold_t = drift(t, V(0.0010, 0.0009, 0.0006), seed=3, freq=0.7, start=HOLD_E[0] - 0.1, end=HOLD_E[1], fade=0.2)
    hold_r = drift(t, V(0.8, 0.6, 1.0), seed=4, freq=0.6, start=HOLD_E[0] - 0.1, end=HOLD_E[1], fade=0.2)
    fade = until(t, 2.30, 0.14)
    apply_gun(p, E_OFF(t) + (off + s_off + hold_t) * fade, E_ROT(t) + (rot + s_rot + hold_r) * fade)
    p.slide_z = float(E_SLIDE(t))
    p.stop = float(E_STOP(t))
    p.hammer = float(E_HAMMER(t))
    mag_swap(p, t)
    _reload_hands(p, t, L_EMPTY_END, L_OFF_EMPTY, world_end=stop_hand(p, t))
    regrip = -8.0 * float(ring(t, 1.74, 1.0, 5.0, 0.6)) * until(t, 2.30, 0.15)
    p.hand_l = (p.hand_l[0], p.hand_l[1], squeeze(p.hand_l[2], regrip))
    cam(p, *(E_CAM(t) + (view + s_view) * fade))
    return p


RELOAD_EMPTY = Clip("reload_empty", 2.30, reload_empty_pose,
                    events=MAG_EVENTS + [(T_LET_GO, "slide_release"), (T_HOME, "slide_forward")],
                    extra_times=RELOAD_KEYS + (T_THUMB, T_LET_GO, T_HOME, T_HOME + 0.01),
                    force_nodes=("mag", "slide", "slide_stop", "hammer"))

# -------------------------------------------------------------------------------------- inspect
# show the left side, flip it over (muzzle leads, the gun lifted on an arc mid-flip) to the right side (port up),
# press-check with a pinch on the front serrations (the gun turns to look into the port; the slide snaps home and
# the gun rings), then a small lift and the swing back to ready through an overshoot. Every pose arrives with
# follow-through and is held as a moving hold; the trigger finger taps the frame while the gun is admired.
T_PC_BACK, T_PC_HOME = 2.92, 3.115
I_OFF = Curve((0.00, V(0, 0, 0)), (0.10, V(0.004, -0.005, 0.002), "stop"),
              (0.62, V(-0.0196, -0.0064, -0.0126), "stop"), (0.82, V(-0.0180, -0.0080, -0.0120)),
              (1.25, V(-0.0208, -0.0060, -0.0138), "stop"), (1.37, V(-0.0192, -0.0110, -0.0118), "stop"),
              (1.62, V(-0.0285, 0.0300, -0.0185), "flow"), (1.88, V(-0.0204, 0.0136, -0.0226), "stop"),
              (2.06, V(-0.0210, 0.0140, -0.0220)), (2.40, V(-0.0196, 0.0120, -0.0200), "stop"),
              (2.75, V(-0.0252, -0.0200, 0.0260), "stop"), (T_PC_BACK, V(-0.0256, -0.0190, 0.0262)),
              (3.07, V(-0.0250, -0.0192, 0.0258), "stop"), (3.20, V(-0.0222, -0.0158, 0.0224), "stop"),
              (3.47, V(0.0013, -0.0017, 0.0008), "stop"), (3.60, V(0, 0, 0), "stop"))
I_ROT = Curve((0.00, V(0, 0, 0)), (0.10, V(-2.0, -1.5, -2.5), "stop"),
              (0.62, V(57.0, 8.6, 13.2), "stop"), (0.82, V(55.0, 8.0, 12.0)),
              (1.25, V(59.5, 10.5, 15.5), "stop"), (1.37, V(61.0, 11.0, 10.5), "stop"),
              (1.62, V(35.0, 22.5, 49.5), {"in": V(-150, 68, 228), "out": V(-150, 68, 228)}),
              (1.88, V(10.0, 34.5, 88.5), "stop"),
              (2.06, V(10.2, 33.0, 84.5)), (2.40, V(8.0, 36.0, 87.0), "stop"),
              (2.75, V(18.0, 6.0, 26.0), "stop"), (T_PC_BACK, V(19.0, 7.2, 30.0)),
              (3.07, V(18.6, 6.8, 29.0), "stop"), (3.20, V(16.0, 6.0, 22.0), "stop"),
              (3.47, V(-0.8, -1.2, 1.4), "stop"), (3.60, V(0, 0, 0), "stop"))
I_SLIDE = Curve((0.0, 0.0), (2.78, 0.0, "stop"), (T_PC_BACK, 0.012, "stop"), (3.07, 0.0118, "stop"),
                (T_PC_HOME, 0.0, ("hit", 2.2)), (T_PC_HOME + 0.010, 0.0007, "stop"), (T_PC_HOME + 0.024, 0.0, "stop"),
                (3.60, 0.0))
I_CAM = Curve((0.00, V(0, 0, 0)), (0.12, V(-0.15, 0.0, -0.30), "stop"), (0.62, V(0.50, 0.0, 1.60), "stop"),
              (1.25, V(0.35, 0.0, 1.30)), (1.62, V(0.60, -0.20, 2.30), "flow"), (1.88, V(-0.40, -0.20, 2.50), "stop"),
              (2.40, V(-0.20, -0.10, 1.80)), (2.75, V(0.0, 0.0, 0.70), "stop"), (3.20, V(0.10, 0.0, 0.40)),
              (3.60, V(0, 0, 0)))
L_INSPECT = Weights((0.00, {"support": 1}), (0.10, {"support": 1}), (0.45, {"world": 1}), (2.40, {"world": 1}),
                    (2.70, {"pinch_front": 1}), (3.12, {"pinch_front": 1}), (3.48, {"support": 1}))
L_OFF_INSPECT = Curve((0.0, V(0, 0, 0)), (2.40, V(0, 0, 0)), (2.58, V(-0.030, -0.020, 0.010), "flow"),
                      (2.70, V(0.0015, 0.0010, -0.0008), "hit"), (2.76, V(0, 0, 0), "stop"), (3.12, V(0, 0, 0)),
                      (3.30, V(-0.015, -0.020, 0.0), "flow"), (3.48, V(0, 0, 0), "stop"), (3.60, V(0, 0, 0)))
INSPECT_REST = V(-0.070, -0.450, -0.140)
HOLDS_I = ((0.62, 1.30), (1.88, 2.45))


def inspect_pose(t):
    p = rig.Pose()
    fade = until(t, 3.60, 0.12)
    off = sum((drift(t, V(0.0008, 0.0007, 0.0005), seed=10 + k, freq=0.55, start=a, end=b, fade=0.18)
               for k, (a, b) in enumerate(HOLDS_I)), V(0, 0, 0))
    rot = sum((drift(t, V(0.7, 0.6, 0.9), seed=20 + k, freq=0.5, start=a, end=b, fade=0.18)
               for k, (a, b) in enumerate(HOLDS_I)), V(0, 0, 0))
    # the slide snapping home rings the gun; the pinch landing on the serrations nudges it
    off = off + ring(t, T_PC_HOME, V(0.0003, -0.0008, -0.0014), 9.0, 0.38) + ring(t, 2.70, V(0.0008, 0.0, 0.0), 8, 0.45)
    rot = rot + ring(t, T_PC_HOME, V(0.3, -1.2, -0.6), 9.0, 0.38) + ring(t, 2.70, V(0.0, 0.0, -0.8), 8, 0.45)
    apply_gun(p, I_OFF(t) + off * fade, I_ROT(t) + rot * fade)
    p.slide_z = float(I_SLIDE(t))
    rest = (INSPECT_REST, quat_mul(p.gun_q, rig.ANCHORS["support"][1][1]), hands.blend({"relaxed": 1}))
    left_hand(p, L_INSPECT, t, L_OFF_INSPECT(t), world=rest)
    # the trigger finger taps the frame twice during each hold; the support pinch tightens as it pulls
    tap = sum(float(ring(t, t0, 1.0, 7.0, 0.5)) for t0 in (0.95, 1.12, 2.10, 2.24)) * fade
    p.fingers_r = hands.adjust(p.anchor("grip")[2], index=(-9.0 * tap, -5.0 * tap))
    pull = float(np.clip((I_SLIDE(t)) / 0.012, 0, 1))
    p.hand_l = (p.hand_l[0], p.hand_l[1], hands.adjust(p.hand_l[2], thumb=(-6 * pull, -4 * pull),
                                                      index=(-6 * pull, -3 * pull)))
    view = ring(t, T_PC_HOME, V(0.7, -0.1, -0.4), 7.0, 0.45)
    cam(p, *(I_CAM(t) + view * fade))
    return p


INSPECT = Clip("inspect", 3.60, inspect_pose,
               events=[(0.02, "inspect_move"), (1.32, "inspect_move"), (2.42, "inspect_move"),
                       (2.80, "slide_back"), (T_PC_HOME - 0.005, "slide_forward")],
               extra_times=(2.70, T_PC_BACK, 3.07, T_PC_HOME, T_PC_HOME + 0.010))
