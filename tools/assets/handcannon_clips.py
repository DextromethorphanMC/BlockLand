"""Hand cannon clips (VIEWMODEL_SPEC sections 15-17, 31 and 43): the 40 mm launcher's own shot, break-open reloads and
inspect, on the P9 rig re-run against handcannon_model (gen_handcannon). The idle, draw and locomotion are the P9's.

The shot: the trigger breaks, the hammer falls, and a 40 mm round leaves a pistol: the wrists are driven back 20 mm and
the muzzle flips 12 degrees, slowly (the arms absorb it), the support hand clamping; the camera answers.
The reload (empty: the runtime plays it after every shot): the gun rolls to the left and comes in; the support hand
leaves the grip for the barrel; the thumb presses the side latch (mag_release); the hand pulls the barrel down onto its
stop (it bounces), the hammer cocking with it; the gun is flicked up and the spent case springs out of the chamber
(mag_out: the runtime throws the gun's shell, the chamber's round hides); the hand goes down to the pouch and comes
back with a grenade on an arc, lines it up behind the chamber, pushes it in (mag_in) with the thumb on its head, drops
under the barrel and swings it shut with a snap (slide_forward: the gun jolts, the camera nods); back to the ready
through a small overshoot, the support hand re-gripping. The tactical reload (a live round still in) is the same,
shorter. The inspect turns the gun over, then breaks it open a little to look at the round and snaps it shut."""
import numpy as np

import armrig
import hands
import handcannon_model as hm
from anim import Curve, ring
from vmath import norm, quat_axis_angle, quat_mul, quat_rotate

V = lambda *a: np.array(a, float)


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


class Kit:
    """The rig and clip modules this hand cannon runs on (gen_handcannon.modules), and its geometry in gun space."""

    def __init__(self, rig, clips):
        self.rig, self.c = rig, clips
        self.hinge = rig.G(hm.HINGE)

    def barrel_point(self, p, design, deg):
        """Gun-local position of a design-frame point on the barrel opened by deg about the hinge."""
        return self.hinge + quat_rotate(rx(deg), self.rig.G(design) - self.hinge)

    def chamber(self, deg):
        """(case-head position, orientation) of a seated round in gun-local space, the barrel opened by deg."""
        return self.barrel_point(None, hm.MAG_ORIGIN, deg), rx(deg)

    # hands ------------------------------------------------------------------------------------------------------------
    def barrel_hand(self, p, deg, z=-0.105, curls=None):
        """The support hand under the barrel (palm up, fingers wrapping its right side), at design z along it."""
        centre = np.array([-0.004, -(hm.BARREL_R + hands.PALM_T / 2 + 0.0010), z])
        q_local = hands.palm_frame((1.0, 0.25, 0.0), (0.0, 0.0, -1.0), "L")
        wrist = centre - quat_rotate(q_local, (0.0, -hands.PALM_LEN / 2, 0.0))
        pos = p.gun_point(self.barrel_point(p, wrist, deg))
        q = quat_mul(p.gun_q, quat_mul(rx(deg), q_local))
        return pos, q, curls if curls is not None else hands.blend({"handguard": 1.0})

    def round_hand(self, p, pushing=0.0, open_=0.0):
        """The support hand holding the grenade by its case from the left, fingers along it towards the nose; `pushing`
        slides it back onto the case head (the thumb pushing it home), `open_` opens the fingers."""
        centre = np.array([-(hm.CASE_R + hands.PALM_T / 2 + 0.0012), 0.0010, -0.0060 + 0.040 * pushing])
        q_local = hands.palm_frame((0.0, -0.10, -1.0), (0.0, 1.0, 0.0), "L")
        wrist = centre - quat_rotate(q_local, (0.0, -hands.PALM_LEN / 2, 0.0))
        pos = p.gun_point(p.mag_t + quat_rotate(p.mag_q, wrist))
        q = quat_mul(p.gun_q, quat_mul(p.mag_q, q_local))
        curls = hands.blend({"mag": 1.0 - open_, "spread": open_})
        return pos, q, curls


# ---------------------------------------------------------------------------------------------------------- fire
def fire_clips(kit, name):
    rig, c = kit.rig, kit.c
    trig = Curve((0.0, 0.0), (0.010, -16.0, "hit"), (0.080, -16.0, "stop"), (0.180, -3.0), (0.300, 0.0))
    hammer = Curve((0.0, rig.HAMMER_COCKED), (0.006, -2.0, "hit"), (0.020, 0.0, "stop"), (0.300, 0.0))
    pull = Curve((0.0, -4.0), (0.010, -10.0, "hit"), (0.080, -10.0, "stop"), (0.180, -2.0), (0.300, -1.0))

    def pose(t):
        p = rig.Pose()
        fade = c.until(t, 0.300, 0.07)
        back = ring(t, 0.0, 0.0200, 5.5, 0.55)
        rise = ring(t, 0.006, 0.0080, 4.5, 0.50)
        flip = ring(t, 0.004, V(1.4, 12.0, -2.6), 4.2, 0.48)
        off = V(0.0012 * float(rise) / 0.0080, float(rise), float(back))
        c.apply_gun(p, off * fade, flip * fade)
        p.trigger = float(trig(t))
        p.hammer = float(hammer(t))
        pl = float(pull(t))
        p.fingers_r = hands.adjust(hands.POSES["trigger"], index=(pl, pl * 0.6))
        clamp = -7.0 * float(ring(t, 0.014, 1.0, 4.5, 0.65)) * c.until(t, 0.300, 0.08)
        p.fingers_l = c.squeeze(hands.POSES["support"], clamp)
        c.cam(p, *(ring(t, 0.004, V(1.6, 0.15, 0.35), 4.5, 0.55) * fade))
        return p

    keys = tuple(sorted(set(trig.times() + hammer.times())))
    return c.Clip(name, 0.30, pose, fps=120, extra_times=keys)


# ---------------------------------------------------------------------------------------------------- reloads
def reload_clip(kit, name, dur):
    """The break-open reload, authored for 2.45 s and scaled to `dur`."""
    rig, c = kit.rig, kit.c
    k = dur / 2.45
    T = lambda t: t * k
    T_LATCH, T_OPEN, T_OUT, T_FETCH, T_BACK, T_LINE, T_IN, T_UNDER, T_SHUT = (
        T(0.26), T(0.50), T(0.58), T(1.05), T(1.40), T(1.50), T(1.72), T(1.86), T(2.00))
    O = hm.OPEN
    gun_off = Curve((0.0, V(0, 0, 0)), (T(0.22), V(-0.030, 0.018, 0.016)), (T_OPEN, V(-0.034, 0.024, 0.012), "flow"),
                    (T_OUT + T(0.06), V(-0.036, 0.032, 0.010), "stop"), (T_FETCH, V(-0.030, 0.020, 0.014)),
                    (T_LINE, V(-0.032, 0.024, 0.012)), (T_IN, V(-0.030, 0.022, 0.012), "stop"),
                    (T_SHUT, V(-0.026, 0.020, 0.012), "hit"), (T(2.28), V(0.002, -0.002, 0.001), "stop"),
                    (dur, V(0, 0, 0), "stop"))
    gun_rot = Curve((0.0, V(0, 0, 0)), (T(0.22), V(-10.0, 6.0, -24.0)), (T_OPEN, V(-11.0, 8.0, -26.0), "flow"),
                    (T_OUT + T(0.06), V(-12.0, 15.0, -24.0), "stop"), (T_FETCH, V(-10.0, 7.0, -27.0)),
                    (T_LINE, V(-12.0, 9.0, -28.0)), (T_IN, V(-11.0, 8.0, -27.0), "stop"),
                    (T_SHUT, V(-8.0, 3.0, -22.0), "hit"), (T(2.28), V(0.8, -0.6, 1.2), "stop"), (dur, V(0, 0, 0), "stop"))
    barrel = Curve((0.0, 0.0), (T_LATCH + T(0.04), 0.0, "stop"), (T_OPEN, O, ("hit", 1.6)), (T_UNDER, O, "stop"),
                   (T_SHUT, 0.0, ("hit", 1.8)), (dur, 0.0))
    latch = Curve((0.0, 0.0), (T(0.20), 0.0, "stop"), (T_LATCH, -16.0, "hit"), (T_OPEN, -16.0, "stop"),
                  (T_OPEN + T(0.12), 0.0), (T_SHUT - T(0.03), 0.0, "stop"), (T_SHUT, -10.0, "hit"),
                  (T_SHUT + T(0.08), 0.0), (dur, 0.0))
    hammer = Curve((0.0, 0.0), (T_LATCH, 0.0, "stop"), (T_OPEN, rig.HAMMER_COCKED, "hit"), (dur, rig.HAMMER_COCKED))
    thumb = Curve((0.0, 0.0), (T(0.18), 0.0), (T_LATCH, 24.0, "hit"), (T_OPEN, 12.0), (T_OPEN + T(0.10), 0.0), (dur, 0.0))
    # the support hand: grip -> barrel -> (pouch) -> round -> barrel -> grip
    hold = [(0.0, "grip"), (T(0.12), "grip"), (T(0.28), "barrel"), (T_OUT + T(0.04), "barrel"), (T_OUT + T(0.20), "pouch"),
            (T_FETCH, "pouch"), (T_BACK, "round"), (T_IN + T(0.02), "round"), (T_UNDER, "barrel"), (T_SHUT + T(0.04), "barrel"),
            (T(2.30), "grip"), (dur, "grip")]
    pouch = V(-0.150, -0.420, -0.140)                 # viewmodel space, below the screen on the left

    def round_pose(p, t):
        """Where the chamber's round / the new grenade is (gun-local), and whether it shows."""
        if t < T_OUT:
            p.mag_t, p.mag_q = kit.chamber(float(barrel(t)))
            return
        if t < T_FETCH:
            p.mag_s = 0.0                              # the spent case is the runtime's shell now; the hand is empty
            p.mag_t, p.mag_q = kit.chamber(O)
            return
        head, q = kit.chamber(O)
        axis = quat_rotate(q, (0.0, 0.0, -1.0))       # into the chamber
        ready = head - axis * (hm.ROUND_LEN + 0.012)
        if t < T_LINE:
            u = float(np.clip((t - T_FETCH) / (T_LINE - T_FETCH), 0, 1))
            u = u * u * (3 - 2 * u)
            low = ready + V(-0.060, -0.160, 0.080)
            arc = V(-0.020, 0.030, 0.0) * np.sin(np.pi * u)
            p.mag_t = low + (ready - low) * u + arc
            p.mag_q = quat_mul(q, quat_mul(quat_axis_angle((0, 1, 0), 35.0 * (1 - u)), rx(-25.0 * (1 - u))))
        elif t < T_IN:
            u = float(np.clip((t - T_LINE) / (T_IN - T_LINE), 0, 1))
            u = u * u * (3 - 2 * u)
            p.mag_t, p.mag_q = ready + (head - ready) * u, q
        else:
            p.mag_t, p.mag_q = kit.chamber(float(barrel(t)))

    def pose(t):
        p = rig.Pose()
        jolt = ring(t, T_OUT, 1.0, 7.0, 0.45) * c.until(t, dur, 0.1)
        snap = ring(t, T_SHUT, 1.0, 8.0, 0.40) * c.until(t, dur, 0.1)
        bump = ring(t, T_OPEN, 1.0, 9.0, 0.45) * c.until(t, dur, 0.1)
        c.apply_gun(p, gun_off(t) + jolt * V(0.0, 0.004, 0.003) + snap * V(0.0, -0.002, -0.003),
                    gun_rot(t) + jolt * V(0.6, 3.0, 1.0) + snap * V(-0.4, -2.0, 0.8) + bump * V(0.0, -0.8, 0.0))
        deg = float(barrel(t)) + 2.0 * float(bump) * (T_OPEN <= t < T_UNDER)
        p.barrel = deg
        p.stop = float(latch(t))
        p.hammer = float(hammer(t))
        round_pose(p, t)
        th = float(thumb(t))
        grip = p.anchor("grip")
        p.hand_r = grip
        p.fingers_r = hands.adjust(grip[2], thumb=(-th, -0.5 * th))
        # the support hand between its holds
        targets = {"grip": p.anchor("support"), "barrel": kit.barrel_hand(p, deg),
                   "pouch": (pouch, armrig.palm_from_back((-0.3, -1.0, 0.2), (0.2, 0.0, -1.0), "L"),
                             hands.blend({"mag": 1.0})),
                   "round": kit.round_hand(p, pushing=float(np.clip((t - T_LINE - T(0.12)) / T(0.12), 0, 1)),
                                           open_=float(np.clip((t - T_IN) / T(0.08), 0, 1)))}
        for (t0, a), (t1, b) in zip(hold, hold[1:]):
            if t0 <= t <= t1:
                u = 0.0 if t1 <= t0 else (t - t0) / (t1 - t0)
                u = u * u * (3 - 2 * u)
                p.hand_l = armrig.blend_targets([(1 - u + 1e-6, targets[a]), (u, targets[b])])
                break
        else:
            p.hand_l = targets["grip"]
        c.cam(p, *(jolt * V(0.6, 0.0, 0.3) + snap * V(-0.7, 0.1, -0.5) + bump * V(-0.3, 0.0, 0.0)))
        return p

    events = [(T(0.04), "inspect_move"), (T_LATCH, "mag_release"), (T_OUT, "mag_out"), (T_IN, "mag_in"),
              (T_SHUT, "slide_forward")]
    keys = (T_LATCH, T_OPEN, T_OUT, T_FETCH, T_LINE, T_IN, T_UNDER, T_SHUT)
    return c.Clip(name, dur, pose, events=events, extra_times=keys, force_nodes=("mag", "barrel"))


# ---------------------------------------------------------------------------------------------------- inspect
def inspect_clip(kit):
    rig, c = kit.rig, kit.c
    dur = 3.30
    O = -24.0
    gun_off = Curve((0.0, V(0, 0, 0)), (0.30, V(-0.040, 0.030, 0.000)), (0.95, V(-0.044, 0.034, -0.004)),
                    (1.40, V(-0.040, 0.032, 0.002)), (1.55, V(-0.034, 0.026, 0.008), "stop"), (2.25, V(-0.032, 0.024, 0.008), "stop"),
                    (2.40, V(-0.030, 0.022, 0.008), "hit"), (3.05, V(0.002, -0.002, 0.001), "stop"), (dur, V(0, 0, 0), "stop"))
    gun_rot = Curve((0.0, V(0, 0, 0)), (0.30, V(-30.0, 6.0, -38.0)), (0.95, V(-34.0, 9.0, -42.0)),
                    (1.40, V(28.0, 8.0, 34.0)), (1.55, V(-10.0, 10.0, -24.0), "stop"), (2.25, V(-12.0, 11.0, -25.0), "stop"),
                    (2.40, V(-8.0, 4.0, -20.0), "hit"), (3.05, V(0.8, -0.5, 1.0), "stop"), (dur, V(0, 0, 0), "stop"))
    barrel = Curve((0.0, 0.0), (1.55, 0.0, "stop"), (1.72, O, ("hit", 1.5)), (2.20, O, "stop"), (2.40, 0.0, ("hit", 1.8)),
                   (dur, 0.0))
    latch = Curve((0.0, 0.0), (1.48, 0.0, "stop"), (1.56, -16.0, "hit"), (1.72, -16.0, "stop"), (1.85, 0.0), (dur, 0.0))
    hold = [(0.0, "grip"), (1.40, "grip"), (1.62, "barrel"), (2.45, "barrel"), (2.95, "grip"), (dur, "grip")]

    def pose(t):
        p = rig.Pose()
        snap = ring(t, 2.40, 1.0, 8.0, 0.40) * c.until(t, dur, 0.1)
        c.apply_gun(p, gun_off(t) + snap * V(0.0, -0.002, -0.003), gun_rot(t) + snap * V(-0.4, -2.0, 0.8))
        deg = float(barrel(t))
        p.barrel = deg
        p.stop = float(latch(t))
        p.mag_t, p.mag_q = kit.chamber(deg)           # the round rides in the chamber
        grip = p.anchor("grip")
        th = 22.0 * float(np.exp(-((t - 1.56) / 0.08) ** 2))
        p.fingers_r = hands.adjust(grip[2], thumb=(-th, -0.5 * th))
        targets = {"grip": p.anchor("support"), "barrel": kit.barrel_hand(p, deg)}
        for (t0, a), (t1, b) in zip(hold, hold[1:]):
            if t0 <= t <= t1:
                u = (t - t0) / max(t1 - t0, 1e-6)
                u = u * u * (3 - 2 * u)
                p.hand_l = armrig.blend_targets([(1 - u + 1e-6, targets[a]), (u, targets[b])])
                break
        c.cam(p, *(snap * V(-0.6, 0.1, -0.4)))
        return p

    events = [(0.10, "inspect_move"), (0.95, "inspect_move"), (1.56, "slide_back"), (2.40, "slide_forward"),
              (2.95, "inspect_move")]
    return c.Clip("inspect", dur, pose, events=events, extra_times=(1.56, 1.72, 2.40), force_nodes=("mag", "barrel"))
