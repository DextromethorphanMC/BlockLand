"""Dual-pistol clips (VIEWMODEL_SPEC sections 4, 15-17, 26, 31), built for any DualRig: idle, draw, fire (right gun),
fire_L (left gun), fire_last, fire_last_L, fire_both, reload, reload_empty, inspect, walk, sprint, tac_sprint.

Each side is authored as the right gun in the right hand (dual_rig mirrors the left), usually the same motion with a
few hundredths of a second of lag on the left so the pair does not move like one rigid block. A clip that leaves a
side at its rest pose does not animate it (the runtime keeps that gun in its idle). Events use the right gun's
timing; a second `slide_forward` / `hammer` marks the left gun's.

Motion is authored to section 31: anim.Curve keys (C1 through the keys; "stop" extremes, "hit" impacts, "snap"
launches), every push answered by a ring() on the gun and an accent on the camera, moving holds (drift), the fingers
and the dropped magazines following physics rather than keys (the old magazine falls in viewmodel space under gravity
from the moment it leaves the well, whatever the gun does)."""
import numpy as np

import hands
from anim import Curve, Follow, drift, ring, smoothstep, window
from armrig import blend_targets
from dual_rig import GUN_Q, GUN_T, Side
from p9_clips import Clip, V
from vmath import quat_conj, quat_euler, quat_mul, quat_rotate

LAG_L = 0.06                 # the left hand follows the right by this much in two-handed moves
GRAVITY = V(0.0, -9.8, 0.0)  # viewmodel space (the camera is roughly level while reloading)
SQUEEZE = ("middle", "ring", "pinky")

# extra finger poses (section 16): fingers loosened round the guard for a twirl, a hard clench for the volley
hands.POSES.setdefault("dual_twirl", {"thumb": (-20, -10), "index": (-40, -34), "middle": (-30, -20), "ring": (-34, -24),
                                      "pinky": (-38, -28)})


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def lagged(t, lag, duration, settle=0.40):
    """The left side's clock: `lag` behind the right (a number or a function of t), catching up over the last `settle`
    seconds so both sides end the clip at rest together."""
    lag = lag(t) if callable(lag) else lag
    return max(0.0, t - lag * (1.0 - smoothstep(duration - settle, duration, t)))


def mix(a, b, u):
    """Finger curls between two poses (dicts of curls)."""
    return {f: np.asarray(a[f], float) * (1 - u) + np.asarray(b[f], float) * u for f in hands.FINGERS}


def squeeze(curls, deg, index=0.0):
    """The gripping fingers clench (negative deg closes) - the hand absorbing a push or a shot."""
    out = {f: np.array(v, float) for f, v in curls.items()}
    for f in SQUEEZE:
        out[f] = out[f] + np.array([deg, 0.6 * deg])
    out["index"] = out["index"] + np.array([index, 0.6 * index])
    return out


def gun_frame(off, rot):
    """The gun's (translation, quaternion) for an offset / euler from the hip pose (Side.offset)."""
    return GUN_T + np.asarray(off, float), quat_mul(GUN_Q, quat_euler(*rot))


# ------------------------------------------------------------------------------------------- idle
def _idle_side(s: Side, t, phase):
    w = 2 * np.pi * t / 2.0 + phase
    s.offset((0.0006 * np.sin(w + 0.6), 0.0009 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0004 * np.sin(w + 1.3)),
             (0.18 * np.sin(w + 0.3), 0.22 * np.sin(w + 0.9), 0.20 * np.sin(w) + 0.08 * np.sin(2 * w + 0.5)))
    grip = s.anchor("grip")
    s.fingers = hands.adjust(grip[2], index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)),
                             middle=(-1.2 * np.sin(w + 0.4), 0), thumb=(1.0 * np.sin(w + 1.1), 0))


def make_clips(rig):
    """-> every clip of a dual-pistol file for this rig."""
    lay = rig.lay
    LOCK = lay.slide_lock
    COCKED = lay.hammer_cocked
    ax = lay.axis
    mag_rest = rig.nodes["mag"][1]

    def pose():
        return rig.pose()

    # ---------------------------------------------------------------------------------------- idle
    def idle_pose(t):
        p = pose()
        _idle_side(p.R, t, 0.0)
        _idle_side(p.L, t, 1.9)
        return p

    idle = Clip("idle", 2.0, idle_pose, loop=True)

    # ---------------------------------------------------------------------------------------- draw
    # both pistols ripped up from the hips: a short dip (anticipation, still off screen), a launch with the muzzles
    # leading (the turn overshoots at 0.30 s, the position at 0.36 s), a settle; the fingers slide off the frame onto
    # the trigger guard and the hands squeeze as the weight arrives; the left gun 45 ms behind
    D_DUR = 0.65
    d_off = Curve((0.00, V(0.050, -0.268, 0.072)), (0.07, V(0.054, -0.279, 0.078), ("snap", 1.4)),
                  (0.20, V(0.018, -0.066, 0.024)), (0.36, V(-0.004, 0.006, -0.005)),
                  (0.50, V(0.0012, -0.0016, 0.0010)), (D_DUR, V(0, 0, 0)))
    d_rot = Curve((0.00, V(-14, -52, -34)), (0.07, V(-15, -55, -36), ("snap", 1.4)), (0.18, V(-6, -15, -12)),
                  (0.30, V(1.6, 4.6, 2.6)), (0.44, V(-0.5, -1.3, -0.8)), (0.56, V(0.15, 0.3, 0.2)), (D_DUR, V(0, 0, 0)))
    d_cam = Curve((0.00, V(0, 0, 0)), (0.22, V(-0.15, 0, 0)), (0.38, V(-0.75, 0, 0)), (0.51, V(0.25, 0, 0)),
                  (D_DUR, V(0, 0, 0)))
    d_fing = Curve((0.0, 0.0), (0.27, 0.0), (0.44, 1.0))

    def draw_side(s, t):
        s.offset(d_off(t), d_rot(t))
        u = float(d_fing(t))
        clench = float(ring(t, 0.33, -7.0, 6.0, 0.5)) * (1 - smoothstep(0.52, D_DUR, t))
        s.fingers = squeeze(hands.blend({"grip": 1 - u + 1e-6, "indexed": u + 1e-6}), clench)

    def draw_pose(t):
        p = pose()
        draw_side(p.R, t)
        draw_side(p.L, lagged(t, 0.045, D_DUR, 0.25))
        cam(p, *d_cam(t))
        return p

    draw = Clip("draw", D_DUR, draw_pose, events=[(0.0, "equip")])

    # ---------------------------------------------------------------------------------------- fire
    # the shot launches everything at once (hammer falls, slide snaps back to full travel by 12 % of the clip, the
    # wrist flips); the slide runs home on its spring, hits battery at 0.086 s with a rebound and dips the muzzle
    # (ring); the gripping fingers clench at the shot, the trigger finger rides the trigger and resets
    F_HOME = 0.086
    f_trig = Curve((0.0, -6.0), (0.009, -14.0, "hit"), (0.050, -14.0), (0.120, 0.0))
    f_hammer = Curve((0.0, COCKED), (0.006, 0.0, "hit"), (0.022, 46.0, "stop"), (0.050, 46.0), (F_HOME, COCKED, "stop"))
    f_hammer_last = Curve((0.0, COCKED), (0.006, 0.0, "hit"), (0.022, 46.0, "stop"))
    f_slide = Curve((0.0, 0.0, ("snap", 2.2)), (0.024, LOCK, "stop"), (F_HOME, 0.0, "hit"), (F_HOME + 0.009, 0.0012, "stop"),
                    (F_HOME + 0.020, 0.0, "stop"))
    f_slide_last = Curve((0.0, 0.0, ("snap", 2.2)), (0.024, LOCK + 0.0015, "hit"), (0.033, LOCK - 0.0006, "stop"),
                         (0.045, LOCK, "stop"))
    f_kick_t = Curve((0.0, V(0, 0, 0), ("snap", 2.5)), (0.016, V(0.0005, 0.0019, 0.0060), "stop"),
                     (0.052, V(0.0003, 0.0028, 0.0024)), (0.100, V(0, -0.0006, -0.0008)), (0.150, V(0, 0.0002, 0.0002)),
                     (0.200, V(0, 0, 0)))
    f_kick_r = Curve((0.0, V(0, 0, 0), ("snap", 2.5)), (0.022, V(0.5, 3.4, -0.8), "stop"), (0.060, V(0.3, 2.0, -0.4)),
                     (0.108, V(-0.1, -0.55, 0.15)), (0.155, V(0.0, 0.15, -0.05)), (0.200, V(0, 0, 0)))
    f_cam = Curve((0.0, 0.0, ("snap", 2.0)), (0.030, 1.0, "stop"), (0.090, 0.35), (0.200, 0.0))
    f_stop = Curve((0.0, 0.0), (0.030, 0.0), (0.048, lay.stop_up, "hit"))
    f_pull = Curve((0.0, -4.0), (0.009, -9.0, "hit"), (0.050, -9.0), (0.120, -1.0), (0.200, 0.0))
    fire_keys = sorted(set(f_trig.times() + f_hammer.times() + f_slide.times() + f_kick_t.times()
                           + f_slide_last.times() + f_stop.times()))

    def fire_side(s, t, last, kick=1.0, spread=0.0):
        end = 1 - smoothstep(0.17, 0.20, t)
        slam = 0.0 if last else float(ring(t, F_HOME, -0.8, 15.0, 0.4)) * end     # the slide home dips the muzzle
        rot = f_kick_r(t) * kick + V(-spread * float(f_kick_r(t)[1]) / 3.4, slam, 0.0)
        s.offset(f_kick_t(t) * kick, rot)
        s.trigger = float(f_trig(t))
        s.hammer = float((f_hammer_last if last else f_hammer)(t))
        s.slide_z = float((f_slide_last if last else f_slide)(t))
        if last:
            s.stop = float(f_stop(t))
        pull = float(f_pull(t))
        clench = float(ring(t, 0.004, -5.0 * kick, 9.0, 0.5)) * end
        s.fingers = squeeze(hands.adjust(hands.POSES["trigger"], index=(pull, pull * 0.6)), clench)

    def fire_pose(right, left, last, kick=1.0, cam_k=1.0, spread=0.0):
        def pose_fn(t):
            p = pose()
            if right:
                fire_side(p.R, t, last, kick, spread)
            if left:
                fire_side(p.L, t, last, kick, spread)
            # the view kicks up; a shot from one hand turns it a hair towards that side
            k = float(f_cam(t))
            yaw = 0.0 if right and left else (-0.12 if right else 0.12) * k
            cam(p, 0.35 * cam_k * k, yaw, 0.0)
            return p
        return pose_fn

    fire = Clip("fire", 0.20, fire_pose(True, False, False), fps=120, extra_times=fire_keys)
    fire_l = Clip("fire_L", 0.20, fire_pose(False, True, False), fps=120, extra_times=fire_keys)
    fire_last = Clip("fire_last", 0.20, fire_pose(True, False, True), fps=120, extra_times=fire_keys)
    fire_last_l = Clip("fire_last_L", 0.20, fire_pose(False, True, True), fps=120, extra_times=fire_keys)
    # the volley: both wrists flip harder and the muzzles kick apart a little
    fire_both = Clip("fire_both", 0.24, fire_pose(True, True, False, kick=1.22, cam_k=1.7, spread=1.3), fps=120,
                     extra_times=fire_keys)

    # -------------------------------------------------------------------------------------- reload
    # anticipation (a dip, the tops rolling out) -> the guns roll in to show their wells -> both thumbs press the
    # releases (the guns jolt) -> a flick of the wrists throws the magazines out (they fall under gravity) -> a quick dive
    # out of view -> slammed down onto fresh magazines at the belt (mag_in, the camera nods with the impact) -> back up
    # with the muzzles leading and an overshoot -> a look at the fresh magazines (rolled over, a moving hold) -> the
    # guns are "set": a nod up and forward, then down into ready with a settle.
    # The empty reload goes from the rise into the slide release instead: the guns come in a little to show the locked
    # slides, the thumbs ride up onto the slide stops and load them (the guns tip a hair), the slides slam home - the
    # right gun first, the left 0.12 s later - each slam dipping its muzzle and jolting the camera; then the set.
    T_RELEASE, T_DROP, T_OUT, T_HIDE, T_SHOW, T_SEAT = 0.25, 0.262, 0.30, 0.46, 0.55, 0.72

    shared_off = [(0.00, V(0, 0, 0)), (0.06, V(0.001, -0.003, 0.001)), (0.19, V(-0.006, 0.021, 0.008)),
                  (0.26, V(-0.006, 0.020, 0.008)), (0.33, V(-0.004, 0.029, 0.006)),
                  (0.42, V(0.010, -0.080, 0.030)), (0.52, V(0.020, -0.215, 0.048)), (0.66, V(0.018, -0.232, 0.052)),
                  (T_SEAT, V(0.016, -0.244, 0.050), "hit"), (0.77, V(0.016, -0.236, 0.050)),
                  (0.89, V(0.008, -0.110, 0.024)), (1.02, V(-0.003, 0.009, -0.004))]
    shared_rot = [(0.00, V(0, 0, 0)), (0.06, V(-1.0, -1.5, 3.0)), (0.19, V(6.8, 17.5, -27.0)), (0.26, V(6.0, 16.0, -24.0)),
                  (0.33, V(5.0, 22.0, -20.0)), (0.42, V(9.0, -18.0, -17.0)), (0.52, V(10.0, -46.0, -12.0)),
                  (0.66, V(10.0, -49.0, -10.0)), (T_SEAT, V(9.5, -47.0, -10.0)), (0.77, V(9.5, -47.5, -10.0)),
                  (0.90, V(4.0, -12.0, -6.0)), (0.98, V(0.8, 4.2, 1.2))]
    shared_cam = [(0.00, V(0, 0, 0)), (0.06, V(0.10, 0, 0)), (0.25, V(0.25, 0, 0)), (0.33, V(0.35, 0, 0)),
                  (0.52, V(-1.30, 0, 0)), (0.70, V(-1.00, 0, 0)), (1.00, V(0.35, 0, 0))]
    r_off = Curve(*shared_off, (1.20, V(-0.006, 0.006, -0.004)), (1.42, V(-0.006, 0.007, -0.004)),
                  (1.60, V(0.000, 0.004, -0.010)), (1.80, V(0.0008, -0.0015, 0.0006)), (2.05, V(-0.0002, 0.0003, 0.0)),
                  (2.40, V(0, 0, 0)))
    r_rot = Curve(*shared_rot, (1.20, V(4.0, 3.0, -14.0)), (1.42, V(4.4, 3.4, -14.6)), (1.60, V(-0.5, 5.0, 1.5)),
                  (1.82, V(0.2, -1.2, -0.6)), (2.05, V(-0.05, 0.25, 0.15)), (2.40, V(0, 0, 0)))
    r_cam = Curve(*shared_cam, (1.25, V(0.10, 0, 0)), (1.60, V(0.30, 0, 0)), (1.85, V(-0.15, 0, 0)), (2.40, V(0, 0, 0)))

    # empty reload: slides locked back from the start until the thumbs drop the slide stops after the rise
    T_THUMB, T_LET_GO, T_HOME = 1.30, 1.53, 1.555
    E_DUR = 2.80
    e_off = Curve(*shared_off, (1.22, V(-0.010, 0.012, 0.000)), (1.48, V(-0.010, 0.010, 0.000)),
                  (1.62, V(-0.008, 0.012, 0.003)), (1.90, V(-0.004, 0.016, -0.008)), (2.15, V(0.0008, -0.0015, 0.0006)),
                  (2.45, V(-0.0002, 0.0003, 0.0)), (E_DUR, V(0, 0, 0)))
    e_rot = Curve(*shared_rot, (1.22, V(-3.0, 6.0, 8.0)), (1.48, V(-3.2, 5.0, 7.5)), (1.62, V(-2.6, 7.0, 7.0)),
                  (1.90, V(-0.6, 8.0, 2.0)), (2.14, V(0.3, -1.2, -0.8)), (2.45, V(-0.08, 0.25, 0.15)), (E_DUR, V(0, 0, 0)))
    e_cam = Curve(*shared_cam, (1.25, V(0.15, 0, 0)), (1.50, V(0.05, 0, 0)), (1.92, V(0.30, 0, 0)),
                  (2.30, V(-0.10, 0, 0)), (E_DUR, V(0, 0, 0)))
    e_slide = Curve((0.0, LOCK), (T_LET_GO, LOCK), (T_HOME, 0.0, "hit"), (T_HOME + 0.009, 0.0011, "stop"),
                    (T_HOME + 0.022, 0.0, "stop"))
    e_stop = Curve((0.0, lay.stop_up), (T_LET_GO - 0.03, lay.stop_up), (T_LET_GO, 0.0, "hit"))
    e_hammer = Curve((0.0, 46.0), (T_LET_GO + 0.012, 46.0), (T_HOME + 0.004, COCKED, "hit"))
    e_press = Curve((0.0, 0.0), (1.05, 0.0), (T_THUMB, 1.0), (T_LET_GO + 0.06, 1.0), (1.85, 0.0))
    e_push = Curve((0.0, 0.0), (1.42, 0.0), (T_LET_GO, 1.0, "hit"), (T_LET_GO + 0.07, 0.25), (1.82, 0.0))
    # the left side trails 0.06 s, and a little more through the slide release so the two slams read separately
    E_LAG = Curve((0.0, LAG_L), (1.05, LAG_L), (1.35, 0.12), (1.90, 0.12), (2.25, LAG_L))
    SLAM_L = T_HOME + 0.12

    w_press = Curve((0.0, 0.0), (0.12, 0.0), (0.20, 1.0), (0.30, 1.0), (0.42, 0.0))
    thumb_push = Curve((0.0, 0.0), (0.21, 0.0), (T_RELEASE, -10.0, "hit"), (0.30, 0.0))
    new_mag = Curve((T_SHOW, 0.045), (0.66, 0.012), (T_SEAT, 0.0, "hit"))

    def frame_at(off_c, rot_c, t):
        o, r = jolts(t, False)
        return gun_frame(off_c(t) + o, rot_c(t) + r)

    def jolts(t, empty):
        """The guns' answers to the hands: the thumb press, the magazine seat and (empty) the slide slam."""
        o = ring(t, T_RELEASE, V(0.0, -0.0012, 0.0), 9.0, 0.4) + ring(t, T_SEAT, V(0.0, 0.004, 0.0), 8.0, 0.35)
        r = ring(t, T_RELEASE, V(0.0, -0.6, 1.4), 9.0, 0.4) + ring(t, T_SEAT, V(0.0, 2.5, 0.0), 8.0, 0.35)
        if empty:
            o = o + ring(t, T_HOME, V(0.0, -0.0015, -0.0022), 10.0, 0.35)
            r = r + ring(t, T_HOME, V(0.0, -2.0, -0.6), 10.0, 0.35)
        return o, r

    def mag_drop(s, t, off_c, rot_c):
        """The old magazine leaves the well with a push and falls under gravity in viewmodel space, tumbling; hidden
        once far below; the fresh one is pushed up into the well off screen and seated at T_SEAT."""
        if t < T_DROP:
            return
        if t < T_HIDE:
            g0, q0 = frame_at(off_c, rot_c, T_DROP)
            g1, q1 = frame_at(off_c, rot_c, T_DROP + 0.004)
            p0 = g0 + quat_rotate(q0, mag_rest)
            v0 = (g1 + quat_rotate(q1, mag_rest) - p0) / 0.004 + quat_rotate(q0, ax * 0.55)
            dt = t - T_DROP
            pw = p0 + v0 * dt + 0.5 * GRAVITY * dt * dt
            spin = quat_euler(-70 * dt, 40 * dt * dt * 30, 90 * dt)
            qw = quat_mul(quat_mul(q0, spin), rig.mag_q)
            qi = quat_conj(s.gun_q)
            s.mag_t = quat_rotate(qi, pw - s.gun_t)
            s.mag_q = quat_mul(qi, qw)
            return
        if t < T_SHOW:
            s.mag_s = 0.0
            s.mag_t = mag_rest + ax * 0.2
            return
        s.mag_t = mag_rest + ax * float(new_mag(t))

    def thumb_on(s, weight, press, push=0.0):
        """The grip shifted towards a thumb-press anchor (weight 0..1), the thumb pushing in by `push` degrees."""
        grip = s.anchor("grip")
        hand = blend_targets([(1 - weight, grip), (weight, s.anchor(press))]) if weight > 1e-4 else grip
        s.hand = (hand[0], hand[1], dict(hand[2], thumb=hand[2]["thumb"] + np.array([push, 0.0])))

    def reload_side(s, t, empty, dur):
        off_c, rot_c = (e_off, e_rot) if empty else (r_off, r_rot)
        o, r = jolts(t, empty)
        hold = (drift(t, V(0.0008, 0.0008, 0.0006), seed=3, freq=0.55, start=1.04, end=dur - 0.04, fade=0.25),
                drift(t, V(0.5, 0.6, 0.8), seed=4, freq=0.55, start=1.04, end=dur - 0.04, fade=0.25))
        s.offset(off_c(t) + o + hold[0], rot_c(t) + r + hold[1])
        mag_drop(s, t, off_c, rot_c)
        thumb_on(s, float(w_press(t)), "release", float(thumb_push(t)))
        if empty:
            s.slide_z = float(e_slide(t))
            s.stop = float(e_stop(t))
            s.hammer = float(e_hammer(t))
            w = float(e_press(t))
            if w > 1e-4:
                thumb_on(s, w, "stop", -6.0 * float(e_push(t)))
        # the grip tightens for the dive and the slam, clenches at every impact
        tight = -4.0 * float(window(t, 0.30, 0.95, 0.12))
        hit = float(ring(t, T_SEAT, -6.0, 7.0, 0.45)) + (float(ring(t, T_HOME, -4.0, 8.0, 0.45)) if empty else 0.0)
        s.hand = (s.hand[0], s.hand[1], squeeze(s.hand[2], tight + hit))

    def reload_pose(empty):
        dur = E_DUR if empty else 2.40

        def pose_fn(t):
            p = pose()
            reload_side(p.R, t, empty, dur)
            reload_side(p.L, lagged(t, E_LAG if empty else LAG_L, dur), empty, dur)
            c = (e_cam if empty else r_cam)(t) + ring(t, T_SEAT, V(-0.9, 0, 0), 6.0, 0.4)
            if empty:
                c = c + ring(t, T_HOME, V(-0.8, 0, 0.5), 9.0, 0.4) + ring(t, SLAM_L, V(-0.6, 0, -0.5), 9.0, 0.4)
            cam(p, *(c * (1 - smoothstep(dur - 0.25, dur, t))))
            return p
        return pose_fn

    mag_events = [(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")]
    reload_keys = (T_RELEASE, T_DROP, T_HIDE, T_SHOW, T_SEAT, T_HIDE + LAG_L, T_SHOW + LAG_L, T_SEAT + LAG_L)
    reload = Clip("reload", 2.40, reload_pose(False), events=mag_events, extra_times=reload_keys,
                  force_nodes=("mag", "mag_L"))
    reload_empty = Clip("reload_empty", E_DUR, reload_pose(True),
                        events=mag_events + [(T_LET_GO, "slide_release"), (T_HOME, "slide_forward"),
                                             (SLAM_L, "slide_forward")],
                        extra_times=reload_keys + (T_LET_GO, T_HOME, T_HOME + 0.009, SLAM_L - 0.025, SLAM_L,
                                                   SLAM_L + 0.009),
                        force_nodes=("mag", "mag_L", "slide", "slide_L", "slide_stop", "slide_stop_L", "hammer",
                                     "hammer_L"))

    # ------------------------------------------------------------------------------------- inspect
    # a dip, then the guns roll over to show their flanks (tops towards each other) with an overshoot and a moving
    # hold; back to neutral, the hand opens, a cock-back, and each pistol is flicked round the trigger finger and
    # caught (the right first, the left 0.16 s later): the catch stops it dead, it springs back past the grip and
    # settles, the hand bounces with the weight; then a last look with the muzzles up, back to the hip with a settle
    S_LAG = 0.16
    T_CATCH = 1.86
    i_off = Curve((0.00, V(0, 0, 0)), (0.08, V(0.002, -0.004, 0.002)), (0.50, V(-0.012, 0.009, -0.012)),
                  (1.12, V(-0.013, 0.009, -0.014)), (1.32, V(-0.010, 0.012, -0.010)), (2.15, V(-0.010, 0.010, -0.010)),
                  (2.50, V(-0.022, 0.032, -0.020)), (3.12, V(-0.024, 0.033, -0.022)), (3.55, V(0.001, -0.0015, 0.0008)),
                  (3.78, V(-0.0002, 0.0003, -0.0001)), (4.00, V(0, 0, 0)))
    i_rot = Curve((0.00, V(0, 0, 0)), (0.08, V(-1.0, -1.0, -3.0)), (0.50, V(-9.0, 7.0, 79.0)), (0.70, V(-8.0, 6.0, 73.0)),
                  (1.12, V(-8.5, 6.5, 75.0)), (1.32, V(0.0, 2.0, 6.0)), (2.15, V(0.0, 2.0, 6.0)),
                  (2.50, V(-7.0, 37.0, 11.0)), (2.68, V(-6.0, 34.0, 10.0)), (3.12, V(-6.5, 35.0, 10.5)),
                  (3.55, V(0.5, -2.0, -1.0)), (3.78, V(-0.1, 0.4, 0.2)), (4.00, V(0, 0, 0)))
    i_spin = Curve((0.0, 0.0), (1.30, 0.0), (1.42, 22.0, ("snap", 1.7)), (T_CATCH, -368.0, "hit"), (1.93, -356.0),
                   (2.02, -361.5), (2.10, -360.0))
    i_open = Curve((0.0, 0.0), (1.22, 0.0), (1.36, 1.0), (1.80, 1.0), (T_CATCH + 0.02, 0.0, "hit"))
    i_cam = Curve((0.00, V(0, 0, 0)), (0.50, V(0.35, 0, 1.1)), (1.10, V(0.25, 0, 0.9)), (1.32, V(0.0, 0, 0.1)),
                  (1.45, V(0.20, 0, 0)), (2.15, V(0.0, 0, 0)), (2.50, V(0.45, 0, 0)), (3.10, V(0.30, 0, 0)),
                  (3.55, V(-0.15, 0, 0)), (4.00, V(0, 0, 0)))

    def inspect_side(s, t, lag):
        """The turn and the last look on the shared clock, the spin (and the hand opening for it) `lag` later."""
        ts = max(0.0, t - lag)
        hold = (drift(t, V(0.0008, 0.0008, 0.0006), seed=7, freq=0.6, start=0.55, end=1.2, fade=0.2)
                + drift(t, V(0.0008, 0.0008, 0.0006), seed=8, freq=0.6, start=2.55, end=3.2, fade=0.2),
                drift(t, V(0.6, 0.7, 0.9), seed=9, freq=0.6, start=0.55, end=1.2, fade=0.2)
                + drift(t, V(0.6, 0.7, 0.9), seed=10, freq=0.6, start=2.55, end=3.2, fade=0.2))
        catch = ring(ts, T_CATCH, V(0.0, -0.004, 0.0015), 7.0, 0.4) + ring(ts, 1.42, V(0.0, 0.002, 0.0), 6.0, 0.5)
        s.offset(i_off(t) + hold[0] + catch, i_rot(t) + hold[1] + ring(ts, T_CATCH, V(0.0, -2.0, 0.0), 7.0, 0.4))
        u = float(np.clip(i_open(ts), 0.0, 1.0))
        if u > 1e-4:
            hand = blend_targets([(1 - u, s.anchor("grip")), (u, s.anchor("open"))])
            s.hand = (hand[0], hand[1], mix(hands.POSES["indexed"], hands.POSES["dual_twirl"], u))
        else:
            s.hand = s.anchor("grip")
        s.hand = (s.hand[0], s.hand[1], squeeze(s.hand[2], float(ring(ts, T_CATCH + 0.02, -7.0, 7.0, 0.45))))
        a = float(i_spin(ts))
        if a:
            s.spin(a)

    def inspect_pose(t):
        p = pose()
        inspect_side(p.R, t, 0.0)
        inspect_side(p.L, t, S_LAG)
        c = i_cam(t) + ring(t, T_CATCH, V(-0.5, 0, 0), 7.0, 0.4) + ring(t, T_CATCH + S_LAG, V(-0.4, 0, 0), 7.0, 0.4)
        cam(p, *(c * (1 - smoothstep(3.75, 4.0, t))))
        return p

    inspect = Clip("inspect", 4.00, inspect_pose,
                   events=[(0.04, "inspect_move"), (1.24, "inspect_move"), (T_CATCH + 0.01, "hammer"),
                           (T_CATCH + S_LAG + 0.01, "hammer"), (2.22, "inspect_move"), (3.20, "inspect_move")],
                   extra_times=(1.42, T_CATCH, 1.42 + S_LAG, T_CATCH + S_LAG))

    # ----------------------------------------------------------------------------------- locomotion
    def footfall(period, drop):
        """0 at foot contact, down to -1 `drop` seconds later (a quick sink into the step), a slow rise back."""
        return Curve((0.0, 0.0), (drop, -1.0), (period, 0.0), loop=True)

    walk_dur = 1.00
    w_step = footfall(walk_dur / 2, 0.10)
    w_lag = Follow(w_step, walk_dur / 2, freq=2.6, zeta=0.45, loop=True)      # the muzzles dip after the body

    def walk_side(s, t, sign):
        w = 2 * np.pi / walk_dur
        b = float(w_step(t))
        lagb = float(w_lag(t))
        side = np.cos(w * (t - 0.05)) * sign          # world sway: both guns drift the same way (sign mirrors)
        s.offset(V(0.0032 * side, 0.0030 + 0.0060 * b, -0.0016 * b - 0.0008 * np.sin(w * t)),
                 V(0.7 * np.sin(w * t) * sign, 0.25 + 0.9 * lagb, 1.1 * side))
        s.fingers = hands.adjust(s.anchor("grip")[2], index=(1.2 * np.sin(w * t), 0), middle=(1.5 * b, 0), ring=(1.5 * b, 0))

    def walk_pose(t):
        p = pose()
        walk_side(p.R, t, 1.0)
        walk_side(p.L, t, -1.0)
        return p

    walk = Clip("walk", walk_dur, walk_pose, loop=True)

    sprint_dur = 0.66
    low_t = V(0.128, -0.112, -0.318)
    low_euler = V(16.0, -30.0, 24.0)
    s_step = footfall(sprint_dur / 2, 0.06)
    s_lag = Follow(s_step, sprint_dur / 2, freq=4.0, zeta=0.35, loop=True)   # the guns bounce after each strike

    def sprint_side(s, t, phase):
        w = 2 * np.pi / sprint_dur
        b = float(s_step(t))
        lagb = float(s_lag(t))
        swing = np.cos(w * t + phase)                  # the arms pump in opposition: forward-up, back-down
        lift = np.sin(w * t + phase)
        side = np.cos(w * (t - 0.04) + phase)
        s.offset(low_t - GUN_T + V(0.004 * side, 0.014 * b + 0.007 * swing + 0.003 * lift, 0.024 * swing),
                 low_euler + V(2.0 * lift, 4.5 * lagb + 6.0 * swing, 3.5 * side))
        s.fingers = hands.adjust(hands.blend({"indexed": 1.0}), middle=(2.0 * b, 0), ring=(2.0 * b, 0))

    def sprint_pose(t):
        p = pose()
        sprint_side(p.R, t, 0.0)
        sprint_side(p.L, t, np.pi)
        return p

    sprint = Clip("sprint", sprint_dur, sprint_pose, loop=True)

    tac_dur = 0.58
    tac_t = V(0.132, -0.052, -0.285)
    tac_euler = V(4.0, 58.0, -8.0)                  # muzzles up beside the head, tops leaning out a little
    t_step = footfall(tac_dur / 2, 0.055)
    t_lag = Follow(t_step, tac_dur / 2, freq=4.2, zeta=0.35, loop=True)

    def tac_side(s, t, phase):
        w = 2 * np.pi / tac_dur
        b = float(t_step(t))
        lagb = float(t_lag(t))
        side = np.cos(w * (t - 0.035) + phase)
        swing = np.cos(w * t + phase)
        s.offset(tac_t - GUN_T + V(0.004 * side, 0.004 + 0.012 * b, -0.004 * b + 0.007 * swing),
                 tac_euler + V(1.8 * np.sin(w * t + phase), 3.0 * lagb + 1.5 * swing, 3.0 * side))
        s.fingers = hands.adjust(hands.blend({"indexed": 1.0}), middle=(2.0 * b, 0), ring=(2.0 * b, 0))

    def tac_pose(t):
        p = pose()
        tac_side(p.R, t, 0.0)
        tac_side(p.L, t, np.pi)
        return p

    tac_sprint = Clip("tac_sprint", tac_dur, tac_pose, loop=True)

    return [idle, draw, fire, fire_l, fire_last, fire_last_l, fire_both, reload, reload_empty, inspect, walk, sprint,
            tac_sprint]
