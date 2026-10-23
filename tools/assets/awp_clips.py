"""AWP clips (VIEWMODEL_SPEC sections 4, 15-17, 23 and 31): idle, draw, fire (shot + bolt cycle), fire_last (shot
only), reload, reload_empty (magazine change + bolt cycle), inspect, walk, sprint, tac_sprint. Each clip maps time ->
awp_rig.Pose (palm frames + finger curls); gen_awp bakes them (IK included) at >= 60 samples/s.

Animation v3 (section 31): the heaviest weapon in the game, so every move is slow to start, long to settle and lands
with follow-through. Motion is authored as C1 curves (anim.Curve) through key poses; the rifle answers every push of
the hands (anim.ring through Hits: the shot, the bolt hitting its stops, the magazine seating), holds drift
(anim.drift) and the camera leans with the body and jolts at the impacts. The shooting hand leaves the grip for the
bolt: reach, lift (the cam breaks, then the handle pops up), pull back (slow extraction, fast stroke, hits the stop,
the case flies), push forward (chambers with a hit, the muzzle dips), press down (locks with a hit), back on the grip."""
import numpy as np

import awp_model as am
import awp_rig as rig
import hands
from anim import Curve, Follow, arc, drift, ring, smoothstep, window
from p9_clips import FINGER_LAG, V, Clip, Weights, ease, left_hand
from vmath import frame_quat, norm, quat_euler, quat_mul

MAG_REST = rig.NODES["mag"][1]
TRAVEL = am.BOLT_TRAVEL
LIFT = am.BOLT_LIFT
Z3 = V(0, 0, 0)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def open_fingers(curls, o, pose="spread"):
    """Blend finger curls towards an open hand (o = 0..1)."""
    if o <= 0:
        return curls
    return {f: (1 - o) * np.asarray(curls[f], float) + o * np.asarray(hands.POSES[pose][f], float)
            for f in hands.FINGERS}


def squeeze(curls, k):
    """Fingers clench (k > 0) on what they hold: a few degrees on every finger."""
    if abs(k) < 1e-6:
        return curls
    return hands.adjust(curls, index=(-k, -0.6 * k), middle=(-k, -0.6 * k), ring=(-k, -0.6 * k),
                        pinky=(-0.8 * k, -0.5 * k), thumb=(-0.4 * k, 0))


def end_fade(t, dur, fade=0.14):
    """1 until `fade` before the end, 0 at the end exactly (camera tracks must close on identity)."""
    return 1.0 - smoothstep(dur - fade, dur, t)


def gun_frame(muzzle, top):
    """Gun orientation from the muzzle direction and the direction its top faces (viewmodel space)."""
    z = -norm(muzzle)
    y = norm(np.asarray(top, float) - z * np.dot(top, z))
    return frame_quat(np.cross(y, z), y, z)


class Hits:
    """Impacts the rifle answers (section 31.1, force transfer): (time, offset amp, turn amp (yaw, pitch, roll),
    camera amp (pitch, yaw, roll), frequency, damping, squeeze of the support fingers)."""

    def __init__(self, *hits):
        self.hits = hits

    def __call__(self, t):
        off, rot, cm, sq = Z3.copy(), Z3.copy(), Z3.copy(), 0.0
        for t0, o, r, c, f, z, s in self.hits:
            if t < t0:
                continue
            k = float(ring(t, t0, 1.0, f, z))
            off = off + np.asarray(o, float) * k
            rot = rot + np.asarray(r, float) * k
            cm = cm + np.asarray(c, float) * k
            lag = t - t0 - 0.03                          # the support fingers clench a few frames later
            if s and lag > 0:
                sq += s * np.exp(-((lag - 0.03) / 0.05) ** 2)
        return off, rot, cm, sq


def H(t0, off=Z3, rot=Z3, cm=Z3, freq=8.0, zeta=0.40, sq=0.0):
    return (t0, V(*off), V(*rot), V(*cm), freq, zeta, sq)


def right_hand(p, w_bolt, t, offset=(0, 0, 0), lag=FINGER_LAG):
    """Shooting hand between the grip (w = 0) and the bolt knob (w = 1); its fingers follow the hand with a lag."""
    w = float(np.clip(w_bolt(t), 0.0, 1.0))
    pos, q, _ = p.blend_hand({"grip": 1.0 - w, "bolt": w}, p.gun_dir(np.asarray(offset, float)))
    wl = float(np.clip(w_bolt(max(t - lag, 0.0)), 0.0, 1.0))
    _, _, curls = p.blend_hand({"grip": 1.0 - wl, "bolt": wl}, (0, 0, 0))
    p.hand_r = (pos, q, curls)


# --------------------------------------------------------------------------------------- bolt cycle
class BoltCycle:
    """The shooting hand works the bolt from t0: loosen and reach for the knob (an arc up off the grip), lift (the cam
    breaks slowly, then the handle pops up against its stop), pull back (slow primary extraction, then a fast stroke
    into the stop - the case flies), push forward (the round feeds, the bolt hits home), press down (the cam pulls it
    into lock), back to the grip (an arc, fingers re-wrap). `travel` < TRAVEL = a press-check (`hold` s at the rear).
    The rifle cants towards the hand while the bolt is worked, is dragged by the strokes and rings at every stop."""

    REACH, UP, BACK, FWD, DOWN, RETURN = 0.13, 0.075, 0.115, 0.12, 0.065, 0.20

    def __init__(self, t0, travel=TRAVEL, hold=0.0, force=1.0):
        self.t0, self.travel, self.force = t0, travel, force
        frac = travel / TRAVEL
        self.t_up0 = t0 + self.REACH
        self.t_up1 = self.t_up0 + self.UP
        self.t_back0 = self.t_up1 + 0.012
        self.t_back1 = self.t_back0 + self.BACK * (0.5 + 0.5 * frac)
        self.t_fwd0 = self.t_back1 + 0.035 + hold
        self.t_fwd1 = self.t_fwd0 + self.FWD * (0.5 + 0.5 * frac)
        self.t_down0 = self.t_fwd1 + 0.012
        self.t_down1 = self.t_down0 + self.DOWN
        self.t_end = self.t_down1 + self.RETURN
        tu0, tu1, tb0, tb1 = self.t_up0, self.t_up1, self.t_back0, self.t_back1
        tf0, tf1, td0, td1 = self.t_fwd0, self.t_fwd1, self.t_down0, self.t_down1
        # handle: the cam breaks (slow), pops up into its stop, rebounds a hair; pressed down, pulled into lock
        self.rot = Curve((t0, 0.0), (tu0, 0.0, "stop"), (tu0 + 0.032, 9.0), (tu1, LIFT, "hit"),
                         (tu1 + 0.018, LIFT - 2.5, "stop"), (tb0 + 0.02, LIFT, "stop"), (td0, LIFT, "stop"),
                         (td0 + 0.022, LIFT - 10.0), (td1, 0.0, "hit"), (td1 + 0.05, 0.0, "stop"))
        z = travel
        rb = 0.0018 * (0.5 + 0.5 * frac)
        self.z = Curve((t0, 0.0), (tb0, 0.0, "stop"), (tb0 + 0.028 * (0.6 + 0.4 * frac), 0.07 * z),
                       (tb1, z, ("hit", 2.4)), (tb1 + 0.014, z - rb, "stop"), (tf0, z - rb * 0.6, "stop"),
                       (tf0 + (tf1 - tf0) * 0.62, 0.42 * z), (tf1, 0.0, ("hit", 2.2)), (tf1 + 0.012, 0.0011, "stop"),
                       (tf1 + 0.026, 0.0, "stop"))
        # the hand: off the grip in an arc, onto the knob, rides it, back down onto the grip
        self.w = Curve((t0, 0.0), (t0 + 0.035, 0.06), (tu0 - 0.008, 1.0, "stop"), (td1 + 0.012, 1.0, "stop"),
                       (self.t_end, 0.0, "stop"))
        self.fingers_open = Curve((t0, 0.0), (t0 + 0.05, 0.55), (tu0 - 0.035, 0.45), (tu0 + 0.01, 0.0, "stop"),
                                  (td1 + 0.010, 0.0, "stop"), (td1 + 0.07, 0.65), (self.t_end - 0.03, 0.15),
                                  (self.t_end + 0.02, 0.0, "stop"))
        # the rifle rolls towards the hand while the bolt is worked and swings back past rest when it lets go
        self.cant = Curve((t0, 0.0), (tu0 + 0.03, 1.0), (td1 + 0.02, 0.92), (self.t_end + 0.06, -0.10),
                          (self.t_end + 0.20, 0.0, "stop"))
        # the hand's pull and push reach the rifle through the shoulder a little softened (and the stop's jolt is a
        # hit of its own): the strokes' drag follows the bolt's speed through a spring
        self.drag = Follow(lambda t: np.array([self.z.vel(t)]), self.t_end + 0.6, freq=7.0, zeta=0.8)
        f = force
        self.hits = Hits(
            H(tu0 + 0.03, off=(0, 0.0012, 0), rot=(0, 0.3, 1.0), cm=(0.10, 0, 0.25), freq=7.0, sq=3),   # cam breaks
            H(tu1, off=(0, 0.0012, 0), rot=(0, 0.2, 0.8), cm=(0.10, 0, 0.20), freq=9.0),                 # handle up
            H(tb1, off=(0.0008, 0.0010, 0.0045 * f), rot=(-0.6, 0.9, -1.6), cm=(0.35, 0.05, 0.6), freq=8.0,
              sq=4),                                                                                      # rear stop
            H(tf1, off=(0, -0.0012, -0.0045 * f), rot=(0.3, -1.5 * f, 0.9), cm=(-0.45, 0, -0.3), freq=7.5,
              sq=5),                                                                                      # chambered
            H(td1, off=(0, -0.0016, 0), rot=(0, -0.4, -1.4), cm=(-0.15, 0, 0.35), freq=8.5, sq=3),       # locked
            H(self.t_end - 0.01, rot=(0, 0.5, 0), cm=(0.08, 0, 0), freq=6.0),                           # back on grip
        )

    def events(self):
        return [(self.t_up0 + 0.035, "awp.bolt_up"), (self.t_back1 - 0.006, "awp.bolt_back"),
                (self.t_fwd1 - 0.004, "awp.bolt_forward"), (self.t_down1 - 0.004, "awp.bolt_down")]

    def times(self):
        return (self.t_up0, self.t_up1, self.t_back0, self.t_back1, self.t_fwd0, self.t_fwd1, self.t_down0,
                self.t_down1, self.t_end)

    def active(self, t):
        return self.t0 <= t <= self.t_end + 0.03

    def apply(self, p, t):
        p.bolt_rot = float(np.clip(self.rot(t), 0.0, LIFT + 1.0))
        p.bolt_z = float(max(self.z(t), 0.0))
        if not self.active(t):
            return
        # the reach and the return are arcs up and out (the hand clears the stock, does not slide through it)
        u_in = float(np.clip((t - self.t0) / (self.t_up0 - self.t0), 0, 1))
        u_out = float(np.clip((t - self.t_down1) / (self.t_end - self.t_down1), 0, 1))
        lift = arc(u_in, V(0.016, 0.024, 0.008)) if t < self.t_up0 else arc(u_out, V(0.014, 0.026, 0.004))
        right_hand(p, self.w, t, lift)
        pos, q, curls = p.hand_r
        p.hand_r = (pos, q, open_fingers(curls, float(np.clip(self.fingers_open(t), 0, 1))))

    def gun_motion(self, t):
        """Rifle offset / turn / camera / support squeeze while the bolt is worked."""
        c = float(self.cant(t))
        off = V(-0.011, -0.006, 0.004) * c
        rot = V(1.2, -1.4, -4.6) * c
        # the strokes drag the rifle with them (the shoulder takes the pull, the push shoves it forward)
        v = float(self.drag(t)[0]) / 1.6
        off = off + V(0.0006, 0.0008, 0.0050) * v
        rot = rot + V(-0.3, -0.4, -0.8) * v
        h_off, h_rot, h_cam, sq = self.hits(t)
        cm = V(-0.25, 0.0, -0.55) * c + h_cam
        return off + h_off, rot + h_rot, cm, sq


# ------------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = rig.Pose()
    w = 2 * np.pi * t / 2.0
    apply_gun(p, (0.0007 * np.sin(w + 0.6), 0.0011 * np.sin(w) + 0.0003 * np.sin(2 * w), 0.0005 * np.sin(w + 1.3)),
              (0.14 * np.sin(w + 0.3), 0.18 * np.sin(w + 0.9), 0.20 * np.sin(w) + 0.07 * np.sin(2 * w + 0.5)))
    p.fingers_r = grip_fingers(p, index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w + 0.7), 0),
                               middle=(-2 * np.sin(w + 0.4), 0), thumb=(2 * np.sin(w + 1.1), 0))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ------------------------------------------------------------------------------------------- draw
# the heavy rifle is hauled up from the bottom right: it sags a little first (still off screen), the muzzle leads the
# swing up (the turn arrives before the position), it overshoots the ready pose and settles slowly; the support hand
# catches the forend at 0.62 s - the rifle and the camera jolt with the catch, the fingers clench and relax
DRAW_DUR = 0.95
T_CATCH = 0.62
DRAW_OFF = Curve((0.00, V(0.060, -0.300, 0.110)), (0.08, V(0.063, -0.311, 0.114)),
                 (0.52, V(-0.006, 0.0085, -0.0065)), (0.70, V(0.0014, -0.0026, 0.0012)),
                 (0.84, V(-0.0003, 0.0005, -0.0002)), (DRAW_DUR, Z3))
DRAW_ROT = Curve((0.00, V(-14, -46, -28)), (0.08, V(-14.6, -48.5, -29.5)), (0.43, V(2.0, 4.6, 3.0)),
                 (0.62, V(-0.6, -1.3, -0.8)), (0.80, V(0.15, 0.3, 0.2)), (DRAW_DUR, Z3))
DRAW_CAM = Curve((0.00, Z3), (0.28, V(-0.35, 0.0, -0.3)), (0.50, V(-0.85, 0.05, -0.55)), (0.72, V(0.25, 0, 0.2)),
                 (DRAW_DUR, Z3))
DRAW_HITS = Hits(H(T_CATCH, off=(0, -0.0016, 0.0006), rot=(0, -0.7, 0.5), cm=(-0.25, 0, 0.2), freq=7.0, sq=7))
DRAW_LW = Curve((0.0, V(-0.012, -0.055, 0.045)), (0.36, V(-0.010, -0.040, 0.028)), (T_CATCH - 0.01, V(0, 0.003, 0)),
                (T_CATCH + 0.08, Z3, "stop"))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.44, {"spread": 1.0}), (T_CATCH + 0.02, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.46, {"grip": 1.0}), (0.68, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    h_off, h_rot, h_cam, sq = DRAW_HITS(t)
    apply_gun(p, DRAW_OFF(t) + h_off * end_fade(t, DRAW_DUR), DRAW_ROT(t) + h_rot * end_fade(t, DRAW_DUR))
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = squeeze(hands.blend(DRAW_FL(max(t - FINGER_LAG, 0.0))), sq)
    p.fingers_r = hands.blend(DRAW_FR(t))
    cam(p, *((DRAW_CAM(t) + h_cam) * end_fade(t, DRAW_DUR)))
    return p


DRAW = Clip("draw", DRAW_DUR, draw_pose, events=[(0.0, "equip")], extra_times=(T_CATCH,))

# ------------------------------------------------------------------------------------------- fire
# .338 Lapua: the runtime drives the whole rig back into the shoulder (section 31.4: recoil springs); the clip adds
# what the hands do - the wrists give (back 6.5 mm at 16 ms, the muzzle flips 3 deg a few ms later), spring back past
# rest and settle, the support hand clenches on the forend. Then the bolt is cycled - back on the
# grip and steady by ~1.06 s, when the sight starts rising again for the next round (60 / 41 rpm - 0.40 s ADS with
# the sniper scope, section 23)
F_TRIG = Curve((0.0, 6.0), (0.006, rig.TRIGGER_PULL, "hit"), (0.085, rig.TRIGGER_PULL, "stop"), (0.17, 0.0, "stop"))
F_KICK_T = Curve((0.0, Z3, {"out": V(-0.10, 0.12, 0.70)}), (0.016, V(-0.0012, 0.0014, 0.0065), "stop"),
                 (0.060, V(-0.0008, 0.0016, 0.0030)), (0.130, V(0.0002, 0.0002, -0.0012), "stop"),
                 (0.205, V(0.0, -0.0002, 0.0003), "stop"), (0.300, Z3, "stop"))
F_KICK_R = Curve((0.0, Z3, {"out": V(8.0, 130.0, -25.0)}), (0.024, V(0.3, 3.1, -0.6), "stop"),
                 (0.075, V(0.2, 1.7, -0.3)), (0.155, V(-0.08, -0.35, 0.14), "stop"),
                 (0.230, V(0.0, 0.10, -0.03), "stop"), (0.300, Z3, "stop"))
F_CAM = Curve((0.0, Z3, {"out": V(36.0, 3.0, 14.0)}), (0.020, V(0.50, 0.04, 0.22), "stop"), (0.110, V(0.11, 0.0, 0.04)),
              (0.260, Z3, "stop"))
SHOT_KEYS = (0.006, 0.016, 0.024, 0.060, 0.075, 0.135, 0.160)
FIRE_BOLT = BoltCycle(0.20)                          # back on the grip by 60 / rpm - ADS time (section 23)
FIRE_DUR = round(FIRE_BOLT.t_end + 0.25, 3)


def shot(p, t, off_trigger=(0.10, 0.20)):
    """Trigger, kick and camera of the shot itself (t from the shot); the finger leaves the trigger and lies along
    the guard again over `off_trigger` (the grip's own finger pose, as in the idle)."""
    trig = float(F_TRIG(t))
    k = trig / rig.TRIGGER_PULL                                   # the finger pad rides the trigger back
    p.trigger = trig
    on = hands.adjust(hands.POSES["ak_trigger"], index=(-6.0 * k, -9.0 * k))
    u = ease("sine", t, *off_trigger)
    p.fingers_r = {f: (1 - u) * np.asarray(on[f], float) + u * np.asarray(p.anchor("grip")[2][f], float)
                   for f in hands.FINGERS}
    sq = 6.0 * np.exp(-((t - 0.035) / 0.045) ** 2)              # the support hand clamps on the forend
    p.fingers_l = squeeze(hands.POSES["handguard"], sq)
    return F_KICK_T(t), F_KICK_R(t), F_CAM(t)


def fire_pose(t):
    p = rig.Pose()
    off, rot, c = shot(p, t)
    b_off, b_rot, b_cam, sq = FIRE_BOLT.gun_motion(t)
    fade = end_fade(t, FIRE_DUR, 0.10)
    hold = drift(t, V(0.0007, 0.0007, 0.0005), seed=11, freq=0.7, start=0.25, end=FIRE_DUR, fade=0.2)
    hold_r = drift(t, V(0.25, 0.30, 0.45), seed=12, freq=0.6, start=0.25, end=FIRE_DUR, fade=0.2)
    apply_gun(p, off + (b_off + hold) * fade, rot + (b_rot + hold_r) * fade)
    FIRE_BOLT.apply(p, t)
    if t > FIRE_BOLT.t0:
        p.fingers_r = None                          # the hand's own curls: on the knob, then the grip's again
    if sq > 0:
        p.fingers_l = squeeze(p.fingers_l, sq)
    cam(p, *((c + b_cam) * end_fade(t, FIRE_DUR)))
    return p


def fire_last_pose(t):
    p = rig.Pose()
    off, rot, c = shot(p, t, (0.14, 0.28))
    apply_gun(p, off, rot)
    cam(p, *(c * end_fade(t, 0.30, 0.04)))
    return p


FIRE = Clip("fire", FIRE_DUR, fire_pose, events=FIRE_BOLT.events(), fps=120,
            extra_times=SHOT_KEYS + FIRE_BOLT.times())
FIRE_LAST = Clip("fire_last", 0.30, fire_last_pose, fps=120, extra_times=SHOT_KEYS)

# ------------------------------------------------------------------------------- magazine change
T_RELEASE, T_OUT, T_SEAT = 0.42, 0.56, 1.86          # events mag_release / mag_out / mag_in
T_HIDE, T_SHOW = 0.98, 1.18                          # magazine scale 0 / 1 (off screen, in the left hand)
T_TAP = T_SEAT + 0.10                                # heel of the hand taps the floor plate
AWAY = V(-0.090, -0.330, 0.120)                     # off-screen spot (gun space) where the hand swaps magazines
DROP = am.MAG_H + 0.012                              # the magazine clears the well when it has come down this far

# out: the paddle lets go (a few mm of drop under its weight), pulled down clear of the well, swung away (gravity
# and the arm: accelerating); in: brought back on an arc, slowed under the well, aligned and fed up slowly (a little
# hunting), then shoved home with a hit (1 mm overtravel, settles back onto the catch)
MAG_OFF = Curve((0.00, Z3), (T_RELEASE, Z3, "stop"), (T_RELEASE + 0.05, V(0, -0.006, 0.0005)),
                (T_OUT, V(0, -0.014, 0.001)), (0.74, V(-0.010, -DROP, 0.004)), (T_HIDE, AWAY, ("hit", 1.6)),
                (T_SHOW, AWAY, "stop"), (1.50, V(-0.006, -DROP - 0.006, -0.005)),
                (1.60, V(-0.0015, -DROP - 0.001, -0.0015), "stop"), (1.70, V(0.0005, -DROP * 0.62, 0.0)),
                (1.76, V(0.0, -DROP * 0.50, 0.0), "stop"), (T_SEAT, V(0, 0.0011, 0), ("hit", 2.6)),
                (T_SEAT + 0.045, Z3, "stop"))
MAG_SPIN = Curve((0.00, Z3), (T_OUT, Z3, "stop"), (0.74, V(1.5, -3.0, 4.0)), (T_HIDE, V(12, -24, 40), "hit"),
                 (T_SHOW, V(10, -20, 34), "stop"), (1.50, V(1.5, -2.5, 3.5)), (1.60, V(0.6, -0.6, 1.0)),
                 (1.70, V(-0.3, 0.2, -0.2)), (1.76, Z3, "stop"))


def mag_swap(p, t):
    if t < T_RELEASE:
        return
    p.mag_t = MAG_REST + MAG_OFF(t)
    p.mag_q = quat_euler(*MAG_SPIN(t))
    if T_HIDE <= t < T_SHOW:
        p.mag_s = 0.0


# support hand: leaves the forend (fingers open, an arc down), thumb on the release paddle, pulls the magazine down
# and away, comes back with the new one, feeds it and shoves it home, taps the floor plate with the heel of the hand
L_MAG = Weights((0.00, {"support": 1}), (0.11, {"support": 1}), (0.34, {"mag_release": 1}),
                (0.60, {"mag_release": 1}), (0.72, {"mag": 1}), (T_HIDE, {"mag": 1}), (T_SHOW - 0.04, {"mag_low": 1}),
                (T_SEAT + 0.02, {"mag_low": 1}), (T_TAP - 0.012, {"slap": 1}), (T_TAP + 0.06, {"slap": 1}))
L_MAG_OFF = Curve((0.00, Z3), (0.11, Z3, "stop"), (0.22, V(-0.022, -0.034, 0.020)), (0.34, Z3, "stop"),
                  (T_SEAT + 0.02, Z3, "stop"), (T_TAP - 0.03, V(0, -0.018, 0.004)), (T_TAP, V(0, 0.003, 0), "hit"),
                  (T_TAP + 0.05, Z3, "stop"))
L_OPEN = Curve((0.0, 0.0), (0.09, 0.0, "stop"), (0.17, 1.0), (0.27, 1.0, "stop"), (0.36, 0.0, "stop"))
THUMB_PRESS = Curve((0.0, 0.0), (0.36, 0.0, "stop"), (T_RELEASE, -16.0, "hit"), (0.48, -4.0), (T_OUT, 0.0, "stop"))
L_BACK = Weights((T_TAP + 0.06, {"slap": 1}), (T_SEAT + 0.50, {"support": 1}))
L_BACK_OFF = Curve((T_TAP + 0.06, Z3), (T_SEAT + 0.30, V(-0.018, -0.030, 0.014)), (T_SEAT + 0.47, V(0, 0.002, 0)),
                   (T_SEAT + 0.56, Z3, "stop"))
# the support shoulder drops back and out while the hand works low on the magazine
SH_MAG = Curve((0.00, Z3), (0.10, Z3, "stop"), (0.36, V(-0.060, -0.030, 0.150), "stop"),
               (T_SEAT + 0.10, V(-0.060, -0.030, 0.150), "stop"), (T_SEAT + 0.52, Z3, "stop"))
# the rifle answers the magazine: the press, the pull out, the shove home, the tap
MAG_HITS = Hits(H(T_RELEASE, off=(0, -0.0010, 0), rot=(0, -0.3, 0.6), cm=(0, 0, 0.15), freq=8.0),
                H(T_OUT, off=(0, 0.0030, 0), rot=(0, 0.9, -0.5), cm=(0.20, 0, -0.25), freq=6.5),
                H(T_SEAT, off=(0, 0.0042, -0.0008), rot=(0.3, 1.6, -1.4), cm=(0.55, 0.05, 0.6), freq=7.5, zeta=0.35),
                H(T_TAP, off=(0, 0.0022, 0), rot=(0, 0.8, -0.6), cm=(0.30, 0, 0.3), freq=8.5))


def magazine_part(p, t):
    p.shoulder_l = SH_MAG(t)
    mag_swap(p, t)
    if t < T_TAP + 0.06:
        left_hand(p, L_MAG, t, L_MAG_OFF(t))
        pos, q, curls = p.hand_l
        curls = hands.adjust(open_fingers(curls, float(np.clip(L_OPEN(t), 0, 1))), thumb=(float(THUMB_PRESS(t)), 0.0))
        p.hand_l = (pos, q, curls)
    else:
        left_hand(p, L_BACK, t, L_BACK_OFF(t))


# ---------------------------------------------------------------------------------------- reload
# anticipation (the rifle dips and rolls the other way), the roll into the presentation (left side and well to the
# eye) with overshoot, a moving hold that leans down to meet the new magazine, the seat and tap rock it, a counter-roll
# before it swings back to ready past rest and settles; the camera leans with it and jolts at the seat
R_OFF = Curve((0.00, Z3), (0.09, V(0.004, -0.005, 0.003)), (0.36, V(-0.055, 0.070, 0.012)),
              (0.56, V(-0.052, 0.064, 0.013)), (1.00, V(-0.055, 0.067, 0.012)), (1.48, V(-0.050, 0.059, 0.016)),
              (T_SEAT - 0.02, V(-0.051, 0.062, 0.014)), (2.04, V(-0.054, 0.067, 0.011)),
              (2.50, V(0.0035, -0.0045, 0.0015)), (2.78, V(-0.0006, 0.0007, -0.0002)), (3.00, Z3))
R_ROT = Curve((0.00, Z3), (0.09, V(-0.8, -1.6, 2.6)), (0.38, V(6.6, 13.2, -29.5)), (0.58, V(6.0, 11.6, -26.0)),
              (1.00, V(6.4, 11.8, -28.4)), (1.48, V(5.2, 13.6, -24.0)), (T_SEAT - 0.02, V(5.8, 11.0, -25.5)),
              (2.04, V(5.0, 10.2, -30.0)), (2.52, V(-0.8, -1.3, 3.2)), (2.80, V(0.15, 0.25, -0.4)), (3.00, Z3))
R_CAM = Curve((0.00, Z3), (0.11, V(0.15, 0.0, 0.40)), (0.42, V(-0.70, 0.20, -1.40)), (0.95, V(-0.40, 0.10, -1.00)),
              (1.50, V(-0.65, 0.10, -1.25)), (T_SEAT - 0.02, V(-0.50, 0.05, -1.05)), (2.10, V(-0.30, 0.0, -0.60)),
              (2.52, V(0.30, -0.05, 0.60)), (3.00, Z3))
R_HOLD = (V(0.0012, 0.0010, 0.0008), V(0.6, 0.5, 0.9))         # drift in the presentation hold


def reload_gun(p, t, dur, off_curve, rot_curve, cam_curve, extra=(Z3, Z3, Z3)):
    """Rifle pose, magazine hits and camera of the magazine change (shared by both reloads)."""
    h_off, h_rot, h_cam, sq = MAG_HITS(t)
    fade = end_fade(t, dur, 0.25)
    hold_t = drift(t, R_HOLD[0], seed=21, freq=0.55, start=0.40, end=2.10, fade=0.25)
    hold_r = drift(t, R_HOLD[1], seed=22, freq=0.50, start=0.40, end=2.10, fade=0.25)
    apply_gun(p, off_curve(t) + (h_off + hold_t) * fade + extra[0], rot_curve(t) + (h_rot + hold_r) * fade + extra[1])
    cam(p, *((cam_curve(t) + h_cam + extra[2]) * end_fade(t, dur)))
    return sq


def reload_pose(t):
    p = rig.Pose()
    reload_gun(p, t, 3.00, R_OFF, R_ROT, R_CAM)
    magazine_part(p, t)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("sine", t, 0.1, 0.3) * (1 - ease("sine", t, 2.3, 2.6)), 0))
    return p


RELOAD = Clip("reload", 3.00, reload_pose,
              events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")],
              extra_times=(T_RELEASE, T_OUT, T_HIDE, T_SHOW, T_SEAT, T_TAP))

# ---------------------------------------------------------------------------------- reload_empty
# the same magazine change; the rifle then comes back towards the bolt-working cant and the shooting hand chambers
# the first round with a harder push home than in the fire cycle
EMPTY_BOLT = BoltCycle(2.30, force=1.25)
EMPTY_DUR = 3.70
E_OFF = Curve((0.00, Z3), (0.09, V(0.004, -0.005, 0.003)), (0.36, V(-0.055, 0.070, 0.012)),
              (0.56, V(-0.052, 0.064, 0.013)), (1.00, V(-0.055, 0.067, 0.012)), (1.48, V(-0.050, 0.059, 0.016)),
              (T_SEAT - 0.02, V(-0.051, 0.062, 0.014)), (2.04, V(-0.054, 0.067, 0.011)),
              (2.38, V(-0.008, 0.010, 0.004)), (EMPTY_BOLT.t_end, V(-0.003, 0.003, 0.0015)),
              (3.42, V(0.0012, -0.0015, 0.0005)), (EMPTY_DUR, Z3))
E_ROT = Curve((0.00, Z3), (0.09, V(-0.8, -1.6, 2.6)), (0.38, V(6.6, 13.2, -29.5)), (0.58, V(6.0, 11.6, -26.0)),
              (1.00, V(6.4, 11.8, -28.4)), (1.48, V(5.2, 13.6, -24.0)), (T_SEAT - 0.02, V(5.8, 11.0, -25.5)),
              (2.04, V(5.0, 10.2, -30.0)), (2.38, V(0.8, 1.6, -5.0)), (EMPTY_BOLT.t_end, V(0.3, 0.5, -1.2)),
              (3.42, V(-0.2, -0.3, 0.6)), (EMPTY_DUR, Z3))
E_CAM = Curve((0.00, Z3), (0.11, V(0.15, 0.0, 0.40)), (0.42, V(-0.70, 0.20, -1.40)), (0.95, V(-0.40, 0.10, -1.00)),
              (1.50, V(-0.65, 0.10, -1.25)), (T_SEAT - 0.02, V(-0.50, 0.05, -1.05)), (2.10, V(-0.30, 0.0, -0.60)),
              (2.40, V(0.0, 0.0, 0.0)), (EMPTY_DUR, Z3))


def reload_empty_pose(t):
    p = rig.Pose()
    b_off, b_rot, b_cam, sq_b = EMPTY_BOLT.gun_motion(t)
    sq = reload_gun(p, t, EMPTY_DUR, E_OFF, E_ROT, E_CAM, (b_off, b_rot, b_cam))
    magazine_part(p, t)
    p.fingers_r = grip_fingers(p, index=(-4.0 * ease("sine", t, 0.1, 0.3) * (1 - ease("sine", t, 2.0, 2.3)), 0))
    EMPTY_BOLT.apply(p, t)
    if EMPTY_BOLT.active(t) and t > EMPTY_BOLT.t0 + 0.02:
        p.fingers_r = None
    if sq_b > 0 and t > T_SEAT + 0.56:
        pos, q, curls = p.hand_l
        p.hand_l = (pos, q, squeeze(curls, sq_b))
    return p


RELOAD_EMPTY = Clip("reload_empty", EMPTY_DUR, reload_empty_pose,
                    events=[(T_RELEASE, "mag_release"), (T_OUT, "mag_out"), (T_SEAT, "mag_in")] + EMPTY_BOLT.events(),
                    extra_times=(T_RELEASE, T_OUT, T_HIDE, T_SHOW, T_SEAT, T_TAP) + EMPTY_BOLT.times())

# --------------------------------------------------------------------------------------- inspect
# a small dip, the rifle is turned to show its left flank (scope up; the barrel passes below the crosshair area, the
# right-side pose keeps the muzzle right of it) - overshoot, moving hold, the support
# fingers drum on the forend; a heavier roll over to the right side (it swings past and settles), the shooting hand
# press-checks the chamber (handle up, the bolt drawn 34 mm and held while the eye checks, pushed home, locked), then
# the rifle swings back to ready past rest and settles
CHECK = BoltCycle(1.98, travel=0.034, hold=0.24, force=0.6)
INSPECT_DUR = 4.00
I_OFF = Curve((0.00, Z3), (0.10, V(0.003, -0.005, 0.002)), (0.62, V(-0.043, 0.013, -0.033)),
              (0.86, V(-0.039, 0.009, -0.029)), (1.30, V(-0.041, 0.012, -0.033)), (1.40, V(-0.043, 0.013, -0.031)),
              (1.86, V(0.002, -0.034, -0.008)), (2.06, V(0.0, -0.030, -0.010)),
              (CHECK.t_end, V(-0.002, -0.029, -0.012)), (3.48, V(0.003, -0.004, 0.0015)),
              (3.78, V(-0.0005, 0.0006, -0.0002)), (INSPECT_DUR, Z3))
I_ROT = Curve((0.00, Z3), (0.10, V(-0.8, -1.2, 2.2)), (0.62, V(33.5, -1.0, -8.0)), (0.86, V(30.0, -2.0, -6.0)),
              (1.30, V(28.5, -0.5, -4.5)), (1.40, V(30.0, -1.5, -7.0)), (1.86, V(-7.4, 3.6, 29.5)),
              (2.06, V(-6.0, 3.0, 26.0)), (CHECK.t_end, V(-5.2, 3.8, 28.0)), (3.48, V(-0.7, -1.0, -1.4)),
              (3.78, V(0.15, 0.2, 0.25)), (INSPECT_DUR, Z3))
I_CAM = Curve((0.00, Z3), (0.12, V(0.12, 0.0, -0.25)), (0.62, V(0.45, -0.35, 0.95)), (1.30, V(0.30, -0.20, 0.60)),
              (1.86, V(-0.35, 0.45, -1.20)), (2.90, V(-0.20, 0.30, -0.80)), (3.48, V(0.10, 0.0, 0.25)),
              (INSPECT_DUR, Z3))
I_SH_R = Curve((0.00, Z3), (0.10, Z3, "stop"), (0.62, V(0.0, 0.08, -0.08)), (1.38, V(0.0, 0.08, -0.08), "stop"),
               (1.86, V(0.010, -0.040, 0.010)), (CHECK.t_end, V(0.010, -0.040, 0.010), "stop"), (3.48, Z3, "stop"))
I_SH_L = Curve((0.00, Z3), (0.10, Z3, "stop"), (0.62, V(-0.020, 0.010, -0.040)), (1.38, V(-0.020, 0.010, -0.040), "stop"),
               (1.86, V(0.010, 0.000, -0.050)), (CHECK.t_end, V(0.010, 0.000, -0.050), "stop"), (3.48, Z3, "stop"))
I_HITS = Hits(H(0.64, rot=(0.6, 0.5, -0.9), cm=(0.10, 0, -0.2), freq=5.0, zeta=0.45),
              H(1.88, off=(0, -0.0015, 0), rot=(-0.6, -0.4, 1.4), cm=(-0.15, 0, 0.3), freq=5.0, zeta=0.45),
              H(3.50, rot=(0.0, 0.4, 0.3), freq=5.0))


def inspect_pose(t):
    p = rig.Pose()
    b_off, b_rot, b_cam, sq = CHECK.gun_motion(t)
    h_off, h_rot, h_cam, _ = I_HITS(t)
    fade = end_fade(t, INSPECT_DUR, 0.3)
    hold_t = drift(t, V(0.0012, 0.0010, 0.0008), seed=31, freq=0.5, start=0.6, end=3.5, fade=0.3)
    hold_r = drift(t, V(0.8, 0.6, 1.0), seed=32, freq=0.45, start=0.6, end=3.5, fade=0.3)
    apply_gun(p, I_OFF(t) + (b_off + h_off + hold_t) * fade, I_ROT(t) + (b_rot + h_rot + hold_r) * fade)
    p.shoulder_r, p.shoulder_l = I_SH_R(t), I_SH_L(t)
    # the support fingers drum on the forend in the first hold, clench with the bolt's stops later
    drum = window(t, 0.75, 1.35, 0.1)
    w = 2 * np.pi * t / 0.55
    p.fingers_l = squeeze(hands.adjust(hands.POSES["handguard"], index=(-8 * max(0, np.sin(w)) * drum, 0),
                                       middle=(-6 * max(0, np.sin(w - 0.9)) * drum, 0),
                                       ring=(-4 * max(0, np.sin(w - 1.8)) * drum, 0)), sq)
    p.fingers_r = grip_fingers(p, index=(2.0 * np.sin(2 * np.pi * t / 1.3), 0.0))
    CHECK.apply(p, t)
    if CHECK.active(t) and t > CHECK.t0 + 0.02:
        p.fingers_r = None
    cam(p, *((I_CAM(t) + b_cam + h_cam) * end_fade(t, INSPECT_DUR)))
    return p


INSPECT = Clip("inspect", INSPECT_DUR, inspect_pose,
               events=[(0.04, "inspect_move"), (1.40, "inspect_move")] + CHECK.events() + [(3.05, "inspect_move")],
               extra_times=CHECK.times())


# ------------------------------------------------------------------------------------ locomotion
def step_bounce(t, period, lag=0.12, sharp=1.6):
    """0..1 impact curve per footstep (period = one step), lowest point just after the foot contact."""
    ph = (t % period) / period
    return (0.5 + 0.5 * np.cos(2 * np.pi * (ph - lag))) ** sharp


def periodic_lag(fn, dur, freq, zeta, n=240):
    """Steady-state response of a damped spring driven by the periodic fn over one loop (sampled table): the heavy
    rifle's own bounce trailing the body's."""
    f = Follow(fn, dur, freq=freq, zeta=zeta, rate=n / dur * 4, loop=True)
    return f.lag


WALK_DUR = 1.00
_WALK_B = lambda t: np.array([step_bounce(t, WALK_DUR / 2, 0.12, 1.8)])
WALK_LAG = periodic_lag(_WALK_B, WALK_DUR, 3.2, 0.35)


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    b = float(_WALK_B(t)[0])
    lag = float(WALK_LAG(t)[0])                     # the long barrel dips after each contact and swings back
    side = np.cos(w * (t - 0.07))
    sway = np.sin(w * (t - 0.07))
    apply_gun(p, V(0.0045 * side, 0.0032 - 0.0075 * b, 0.0022 * b - 0.0011 * np.sin(w * t)),
              V(0.9 * sway, 0.35 - 0.9 * b - 1.2 * lag, 1.4 * side))
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(-2 * b, 0), ring=(-2 * b, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)


def pump_fist(t, dur, centre, swing, bulge=V(-0.018, 0.020, 0.0), punch=0.0):
    """The free hand pumping as a fist on an ellipse (back / down at t = 0, forward / up at half the loop), with a
    jolt at each foot strike."""
    w = 2 * np.pi / dur
    pos = centre - swing * np.cos(w * t) + bulge * np.sin(w * t) + V(0, -punch, 0)
    curls = hands.adjust(hands.POSES["fist"], index=(6 * np.sin(w * t), 0), pinky=(-4 * np.sin(w * t), 0))
    return pos, None, curls


SPRINT_DUR = 0.66
LOW_T = V(0.170, -0.150, -0.320)                  # running carry: rifle lowered, muzzle down-left (absolute); the
LOW_Q = gun_frame((-0.36, -0.42, -0.83), (-0.30, 0.82, -0.40))   # long stock stays low, out of the upper right
LOW_SHOULDER = V(0.080, 0.160, -0.100)
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.028, 0.160, -0.140)
_RUN_B = lambda t: np.array([step_bounce(t, SPRINT_DUR / 2, 0.10, 2.2)])
RUN_LAG = periodic_lag(_RUN_B, SPRINT_DUR, 4.0, 0.30)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    b = float(_RUN_B(t)[0])
    lag = float(RUN_LAG(t)[0])
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * t)                         # the gun arm swings against the left arm
    p.gun_t = LOW_T + V(0.006 * side, -0.013 * b + 0.005 * swing - 0.006 * lag, 0.012 * swing)
    p.gun_q = quat_mul(LOW_Q, quat_euler(2.4 * np.sin(w * t), -3.0 * b + 3.4 * swing - 3.5 * lag, 3.6 * side))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_fist(t, SPRINT_DUR, L_RUN_C, L_RUN_A, punch=0.006 * b)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.160, -0.170, -0.300)                  # tactical sprint: muzzle up in front of the right shoulder
TAC_Q = gun_frame((-0.14, 0.88, -0.46), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)
_TAC_B = lambda t: np.array([step_bounce(t, TAC_DUR / 2, 0.10, 2.2)])
TAC_LAG = periodic_lag(_TAC_B, TAC_DUR, 4.2, 0.30)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    b = float(_TAC_B(t)[0])
    lag = float(TAC_LAG(t)[0])
    side = np.cos(w * (t - 0.035))
    p.gun_t = TAC_T + V(0.007 * side, 0.006 - 0.016 * b - 0.005 * lag, 0.005 * b)
    p.gun_q = quat_mul(TAC_Q, quat_euler(2.2 * np.sin(w * t), -3.0 * b - 3.0 * lag, 3.8 * side))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_fist(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.12, punch=0.007 * b)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, RELOAD, RELOAD_EMPTY, INSPECT, WALK, SPRINT, TAC_SPRINT]
