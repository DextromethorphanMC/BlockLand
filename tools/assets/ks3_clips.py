"""KS-3 clips (VIEWMODEL_SPEC sections 4, 15-17, 25 and 31): idle, draw, fire (shot + pump cycle), fire_last (shot
only), the shell-mode reload (reload_start, reload_shell - one round, repeatable -, reload_end, reload_end_empty with a
pump cycle), inspect (with a press-check), walk, sprint, tac_sprint. Each clip maps time -> ks3_rig.Pose (palm frames +
finger curls); gen_ks3 bakes them (IK included) at >= 60 samples/s. The support hand rides the pump (its anchor is in
pump space), so working the pump moves the hand with it.

Animation v3 (section 31): motion runs on anim.Curve (C1 through its keys; "stop" at extremes and holds, "hit" where a
part slams into a stop), the weapon answers every push of the hands with anim.ring (the pump's rear stop and battery,
each shell thumbed home, the hand slapping onto the forend), holds drift, the fingers trail their hand. A 12-gauge
moves like a heavy gun: the shot shoves it back and up, the pump is yanked back and driven home and the gun rocks with
both strokes.

The reload clips share one pose at their joins (the gun rolled to show its loading port, the empty hand under the
port, the camera at rest): reload_start ends in it, reload_shell starts and ends in it, reload_end / reload_end_empty
start from it, so the runtime can chain them without a jump. Inside reload_shell the gun is alive (it gives as the arm
reaches down, rolls to meet the shell, is pushed by the thumb) and comes back to the pose for the next round."""
import numpy as np

import hands
import ks3_model as km
import ks3_rig as rig
from ak_clips import gun_frame, open_fingers, pump_left, step_bounce
from anim import Curve, Follow, drift, ring, smoothstep, window
from p9_clips import FINGER_LAG, V, Clip, Weights, left_hand, poses
from vmath import quat_axis_angle, quat_euler, quat_mul

TRAVEL = km.PUMP_TRAVEL
ZERO = V(0, 0, 0)


def apply_gun(p, offset=(0, 0, 0), euler=(0, 0, 0)):
    """Offset the gun from the hip pose: world translation + local yaw/pitch/roll (degrees)."""
    p.gun_t = rig.GUN_T + np.asarray(offset, float)
    p.gun_q = quat_mul(rig.GUN_Q, quat_euler(*euler))


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def grip_fingers(p, index=(0.0, 0.0), thumb=(0.0, 0.0)):
    return hands.adjust(p.anchor("grip")[2], index=index, thumb=thumb)


def support_fingers(squeeze=0.0, thumb=0.0):
    return hands.adjust(hands.POSES["handguard"], index=(squeeze, 0), middle=(squeeze, 0), ring=(squeeze * 0.8, 0),
                        thumb=(thumb, 0))


def fade_out(t, end, length=0.08):
    """1 until `length` before `end`, eased to 0 at `end` (secondary motion must be gone when a clip ends)."""
    return 1.0 - smoothstep(end - length, end, t)


def clench(t, t0, amount=-8.0, width=0.05):
    """A squeeze of the fingers peaking at t0 (absorbing a push)."""
    return amount * np.exp(-((t - t0) / width) ** 2)


# --------------------------------------------------------------------------------------- pump cycle
class PumpCycle:
    """The support hand racks the pump from t0: a brace (the hand tenses, the gun dips a hair), a hard pull back to
    the stop (`pump_back` on the hit: the bolt opens, the hull flies out), a beat at the stop, the drive forward into
    battery (`pump_forward` on the hit); each stop rebounds a little and kicks the gun. soft=True is a press-check:
    eased against the spring to a short `travel`, held, then let the spring run it home."""

    def __init__(self, t0, travel=TRAVEL, hold=0.025, brace=0.035, pull=0.085, drive=0.07, soft=False):
        k = 0.5 + 0.5 * travel / TRAVEL                  # shorter strokes are quicker
        self.t0, self.travel, self.soft = t0, travel, soft
        self.t_pull = t0 + brace
        self.t_back = self.t_pull + pull * (1.0 if soft else k)
        self.t_fwd0 = self.t_back + hold
        self.t_fwd = self.t_fwd0 + drive * k
        self.t_end = self.t_fwd + 0.16
        z, bounce = travel, 0.0016 * travel / TRAVEL
        back = (self.t_back, z, "stop") if soft else (self.t_back, z, ("hit", 2.2))
        self.z = Curve((t0, 0.0), (self.t_pull, 0.0), back,
                       (self.t_back + 0.014, z - (0.0 if soft else bounce), "stop"),
                       (self.t_fwd0, z - (0.0 if soft else 0.4 * bounce), "stop"),
                       (self.t_fwd, 0.0, ("hit", 2.4)), (self.t_fwd + 0.012, 0.6 * bounce, "stop"),
                       (self.t_fwd + 0.026, 0.0))
        self.k = travel / TRAVEL

    def events(self):
        return [(self.t_back - 0.003, "pump_back"), (self.t_fwd - 0.003, "pump_forward")]

    def times(self):
        return (self.t0, self.t_pull, self.t_back, self.t_back + 0.014, self.t_fwd0, self.t_fwd, self.t_fwd + 0.012,
                self.t_fwd + 0.026)

    def active(self, t):
        return self.t0 <= t <= self.t_end

    def apply(self, p, t):
        """The pump and the support hand's fingers (they clamp on as it pulls, clench at each stop)."""
        p.pump_z = float(self.z(t))
        pull = min(1.0, p.pump_z / max(self.travel, 1e-6) * 2.0) * window(t, self.t0, self.t_end, 0.05)
        squeeze = -9.0 * pull + clench(t, self.t_back + 0.01, -5.0 * self.k) + clench(t, self.t_fwd + 0.01, -6.0 * self.k)
        p.fingers_l = support_fingers(squeeze=squeeze, thumb=-6.0 * pull)

    def gun_motion(self, t):
        """The gun answers the rack: it dips as the hand braces, is dragged back and down by the pull (muzzle dips and
        turns in), jolts back at the rear stop, is thrust forward by the drive and jolts at battery."""
        u = float(self.z(t)) / TRAVEL
        k = self.k
        brace = window(t, self.t0, self.t_fwd + 0.03, 0.045)
        drive = window(t, self.t_fwd0, self.t_fwd + 0.012, 0.035)
        off = V(-0.0015, -0.0025, 0.0015) * brace + V(-0.0030, -0.0040, 0.0100) * u + V(0.0, 0.0018, -0.0055) * drive * k
        rot = V(0.3, -0.7, -1.2) * brace + V(1.4, -2.2, -3.0) * u + V(-0.4, 1.1, 0.8) * drive * k
        if not self.soft:
            off = off + ring(t, self.t_back, V(0.0, -0.0014, 0.0038) * k, 9.0, 0.38)
            rot = rot + ring(t, self.t_back, V(0.5, -1.5, -1.0) * k, 9.0, 0.38)
        off = off + ring(t, self.t_fwd, V(0.0, 0.0010, -0.0034) * k, 10.0, 0.38)
        rot = rot + ring(t, self.t_fwd, V(-0.3, 1.3, 0.9) * k, 10.0, 0.38)
        f = fade_out(t, self.t_end, 0.06)
        return off * f, rot * f

    def camera(self, t):
        """(pitch, yaw, roll) jolts as the pump hits its stops."""
        c = ring(t, self.t_fwd, V(0.45, 0.05, 0.35) * self.k, 8.0, 0.35)
        if not self.soft:
            c = c + ring(t, self.t_back, V(-0.40, 0.08, -0.45) * self.k, 7.5, 0.35)
        return c * fade_out(t, self.t_end, 0.06)


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
# whipped up from below the bottom right: off screen for the first ~0.1 s, the muzzle leads (its overshoot at 0.36 s,
# the body's at 0.43 s), the support hand slaps onto the forend at 0.50 s (the gun bucks up, the fingers close and
# squeeze), settle
D_DUR = 0.75
T_CATCH = 0.50
DRAW_OFF = Curve((0.00, V(0.055, -0.280, 0.100)), (0.09, V(0.057, -0.262, 0.094)), (0.24, V(0.018, -0.070, 0.028)),
                 (0.43, V(-0.0045, 0.0065, -0.0055), "stop"), (0.58, V(0.0012, -0.0016, 0.0010), "stop"),
                 (D_DUR, ZERO))
DRAW_ROT = Curve((0.00, V(-14, -46, -30)), (0.06, V(-15, -48, -31), "stop"), (0.20, V(-6, -13, -14)),
                 (0.36, V(1.8, 4.6, 3.0), "stop"), (0.53, V(-0.5, -1.1, -0.8), "stop"), (D_DUR, ZERO))
DRAW_CAM = Curve((0.00, ZERO), (0.22, V(-0.9, 0.05, -0.7), "stop"), (0.42, V(0.45, -0.05, 0.30), "stop"),
                 (0.62, V(-0.08, 0, -0.05)), (D_DUR, ZERO))
DRAW_LW = Curve((0.0, V(-0.010, -0.050, 0.040)), (0.30, V(-0.008, -0.034, 0.024)),
                (0.47, V(0.0, 0.0035, -0.001), "stop"), (0.56, ZERO))
DRAW_FL = Weights((0.0, {"spread": 1.0}), (0.36, {"spread": 1.0}), (0.50, {"handguard": 1.0}))
DRAW_FR = Weights((0.0, {"grip": 1.0}), (0.34, {"grip": 1.0}), (0.52, {"ak_indexed": 1.0}))


def draw_pose(t):
    p = rig.Pose()
    f = fade_out(t, D_DUR, 0.12)
    off = DRAW_OFF(t) + ring(t, T_CATCH, V(0.0, 0.0016, -0.0008), 8.0, 0.4) * f
    rot = DRAW_ROT(t) + ring(t, T_CATCH, V(0.2, 1.0, 0.6), 8.0, 0.4) * f
    apply_gun(p, off, rot)
    left_hand(p, Weights((0.0, {"support": 1.0})), t, DRAW_LW(t))
    p.fingers_l = hands.adjust(poses(max(t - FINGER_LAG, 0.0), DRAW_FL),
                               **{n: (clench(t, T_CATCH + 0.04, -9.0, 0.045), 0) for n in ("index", "middle", "ring")})
    p.fingers_r = poses(t, DRAW_FR)
    c = DRAW_CAM(t) + ring(t, T_CATCH, V(0.25, 0, 0.15), 8.0, 0.4) * f
    cam(p, *c)
    return p


DRAW = Clip("draw", D_DUR, draw_pose, events=[(0.0, "equip")], extra_times=(0.06, 0.20, 0.36, 0.43, T_CATCH))

# ------------------------------------------------------------------------------------------- fire
# 12 gauge: the shot shoves the gun back and flips the muzzle (the wrists take it, the support hand clenches), it drops
# back a hair past rest, then the support hand racks the pump while the gun is still coming down
SHOT_TRIG = Curve((0.0, 0.0), (0.008, rig.TRIGGER_PULL, ("hit", 1.6)), (0.065, rig.TRIGGER_PULL, "stop"),
                  (0.140, 0.0))
SHOT_T = Curve((0.0, ZERO), (0.004, ZERO, ("snap", 3.0)), (0.016, V(-0.0016, 0.0024, 0.0068), "stop"),
               (0.060, V(-0.0010, 0.0020, 0.0024)), (0.112, V(0.0002, -0.0006, -0.0013), "stop"),
               (0.175, V(0.0, 0.0002, 0.0003), "stop"), (0.245, ZERO))
SHOT_R = Curve((0.0, ZERO), (0.005, ZERO, ("snap", 2.6)), (0.022, V(0.5, 3.3, -1.3), "stop"),
               (0.072, V(0.3, 1.6, -0.6)), (0.128, V(-0.1, -0.55, 0.2), "stop"), (0.190, V(0.0, 0.10, 0.0), "stop"),
               (0.250, ZERO))
SHOT_CAM = Curve((0.0, ZERO), (0.004, ZERO, ("snap", 2.0)), (0.022, V(1.15, 0.10, 0.55), "stop"),
                 (0.090, V(0.20, 0.0, 0.10)), (0.230, ZERO))
SHOT_KEYS = (0.004, 0.005, 0.008, 0.016, 0.022, 0.060, 0.065, 0.112, 0.128)
FIRE_PUMP = PumpCycle(0.19)                         # racked well inside 60 / 70 rpm = 0.86 s
FIRE_DUR = round(FIRE_PUMP.t_end + 0.12, 3)


def shot(p, t):
    """Trigger, kick and camera of the shot itself (t from the shot)."""
    trig = float(SHOT_TRIG(t))
    k = trig / rig.TRIGGER_PULL
    p.trigger = trig
    # the finger pad rides the trigger back, then comes off it onto the guard again (the hip pose's indexed finger)
    off = float(smoothstep(0.10, 0.20, t))
    p.fingers_r = hands.adjust(hands.blend({"ak_trigger": 1.0 - off + 1e-6, "ak_indexed": off + 1e-6}),
                               index=(-6.0 * k, -9.0 * k))
    p.fingers_l = support_fingers(squeeze=clench(t, 0.022, -8.0, 0.04))
    return SHOT_T(t), SHOT_R(t), SHOT_CAM(t)


def fire_pose(t):
    p = rig.Pose()
    off, rot, c = shot(p, t)
    p_off, p_rot = FIRE_PUMP.gun_motion(t)
    apply_gun(p, off + p_off, rot + p_rot)
    if FIRE_PUMP.active(t):
        FIRE_PUMP.apply(p, t)
    cam(p, *(c + FIRE_PUMP.camera(t)))
    return p


def fire_last_pose(t):
    p = rig.Pose()
    off, rot, c = shot(p, t)
    apply_gun(p, off, rot)
    cam(p, *c)
    return p


FIRE = Clip("fire", FIRE_DUR, fire_pose, events=FIRE_PUMP.events(), fps=120,
            extra_times=SHOT_KEYS + FIRE_PUMP.times())
FIRE_LAST = Clip("fire_last", 0.25, fire_last_pose, fps=120, extra_times=SHOT_KEYS)

# --------------------------------------------------------------------------------- shell reloads
# the loading pose: rolled to the right so the loading port faces the eye and the left hand, muzzle up a little
LOAD_OFF = V(-0.050, 0.024, -0.040)
LOAD_ROT = V(4.0, 6.0, -40.0)
LOAD_SH_L = V(-0.050, -0.040, 0.140)               # the support shoulder drops back while the hand works low


def load_gun(p, u, off=ZERO, rot=ZERO):
    """The gun `u` of the way into the loading pose (u may overshoot), plus extra offsets."""
    apply_gun(p, LOAD_OFF * u + off, LOAD_ROT * u + rot)
    p.shoulder_l = LOAD_SH_L * float(np.clip(u, 0.0, 1.1))
    p.fingers_r = grip_fingers(p, index=(-4.0 * float(np.clip(u, 0.0, 1.0)), 0))


# reload_start: a small counter-roll as the hand lets go of the pump, then the gun rolls over to show the port, past
# the pose and back into it; the hand swings down and under to the port
S_DUR = 0.45
S_U = Curve((0.00, 0.0), (0.07, -0.06, "stop"), (0.31, 1.07, "stop"), (S_DUR, 1.0))
S_LIFT = Curve((0.00, ZERO), (0.07, V(0.0, -0.0015, 0.0010), "stop"), (0.20, V(0.004, 0.006, 0.004)),
               (0.34, V(-0.0010, -0.0012, 0.0), "stop"), (S_DUR, ZERO))
S_HAND = Weights((0.00, {"support": 1}), (0.07, {"support": 1}), (0.36, {"port": 1}), (S_DUR, {"port": 1}))
S_HAND_OFF = Curve((0.00, ZERO), (0.07, ZERO), (0.20, V(-0.022, -0.034, 0.022), "stop"), (0.36, ZERO))
S_OPEN = Curve((0.0, 0.0), (0.05, 0.0), (0.13, 0.75, "stop"), (0.30, 0.25), (0.40, 0.0))
S_CAM = Curve((0.00, ZERO), (0.07, V(0.12, 0, 0.25), "stop"), (0.26, V(-0.45, 0.05, -1.25), "stop"), (S_DUR, ZERO))


def reload_start_pose(t):
    p = rig.Pose()
    load_gun(p, float(S_U(t)), S_LIFT(t))
    left_hand(p, S_HAND, t, S_HAND_OFF(t))
    pos, q, curls = p.hand_l
    p.hand_l = (pos, q, open_fingers(curls, float(S_OPEN(t))))
    cam(p, *S_CAM(t))
    return p


RELOAD_START = Clip("reload_start", S_DUR, reload_start_pose, events=[(0.05, "inspect_move")],
                    extra_times=(0.07, 0.31))

# reload_shell: the hand drops out of view to fetch a shell (the gun gives a little as the arm reaches), brings it up
# under the port decelerating, aligns it, thumbs it up into the port and forward into the tube (the gun is pushed and
# rings, the gate swings in and snaps back), and comes back to the port; the gun rolls a touch to meet the shell and
# returns to the loading pose for the next round
L_DUR = 0.52
T_SHOW, T_ALIGN, T_PUSH, T_IN, T_HIDE = 0.14, 0.30, 0.335, 0.40, 0.42   # shown / aligned / push / in the tube / hidden
SHELL_LOW = V(-0.020, -0.230, 0.140)               # off screen below (design frame)
SHELL_PORT = V(0.0, km.REC_BOT - 0.0200, 0.0620)   # the shell's centre just under the port, nose up a little
SHELL_UP = SHELL_PORT + V(0.0, 0.0120, -0.0080)    # up into the port
SHELL_IN = V(0.0, km.TUBE_Y, -0.0060)              # pushed forward into the tube
SHELL_POS = Curve((0.00, SHELL_LOW), (T_SHOW, SHELL_LOW, "stop"), (0.25, SHELL_PORT + V(0.0, -0.0110, 0.0100)),
                  (T_ALIGN, SHELL_PORT + V(0.0, -0.0015, 0.0012)), (T_PUSH, SHELL_PORT, "stop"),
                  (T_PUSH + 0.030, SHELL_UP), (T_IN, SHELL_IN, ("hit", 1.8)), (L_DUR, SHELL_IN))
SHELL_TILT = Curve((0.00, V(-25, 10, 0)), (T_SHOW, V(-25, 10, 0), "stop"), (0.25, V(14, 2, 0)),
                   (T_ALIGN, V(10, 0, 0), "stop"), (T_PUSH + 0.030, V(6, 0, 0)), (T_IN, ZERO, "hit"),
                   (L_DUR, ZERO))
L_HAND = Weights((0.00, {"port": 1}), (0.03, {"port": 1}), (T_SHOW, {"shell": 1}), (T_PUSH + 0.02, {"shell": 1}),
                 (T_IN + 0.02, {"port": 1}), (L_DUR, {"port": 1}))
L_HAND_OFF = Curve((0.00, ZERO), (0.03, ZERO), (0.095, V(-0.032, -0.064, 0.034), "stop"), (T_SHOW, ZERO),
                   (T_IN + 0.02, ZERO), (0.47, V(0.0, -0.0040, 0.0020), "stop"), (L_DUR, ZERO))
L_THUMB = Curve((0.0, 0.0), (T_PUSH - 0.01, 0.0), (T_PUSH + 0.035, -26.0, "stop"), (T_IN + 0.025, -12.0),
                (L_DUR, 0.0))
L_GRAB = Curve((0.0, 0.0), (0.04, 0.0), (0.10, 0.7, "stop"), (T_SHOW + 0.02, -0.15, "stop"), (0.24, 0.0))
L_GATE = Curve((0.0, 0.0), (T_PUSH + 0.012, 0.0), (T_PUSH + 0.040, rig.GATE_IN, "stop"),
               (T_IN + 0.008, rig.GATE_IN, "stop"), (T_IN + 0.040, 0.0, ("hit", 2.0)), (L_DUR, 0.0))
# the gun: gives as the arm reaches down, rolls to meet the shell, is pushed by the thumb, back to the pose
L_GUN_T = Curve((0.00, ZERO), (0.12, V(0.0010, -0.0016, 0.0006), "stop"), (0.27, V(-0.0012, -0.0010, -0.0008)),
                (T_PUSH, V(-0.0016, -0.0006, -0.0010), "stop"), (0.45, V(0.0004, 0.0004, 0.0002), "stop"),
                (L_DUR, ZERO))
L_GUN_R = Curve((0.00, ZERO), (0.12, V(-0.3, -0.9, 1.6), "stop"), (0.27, V(0.2, 0.3, -1.4)),
                (T_PUSH, V(0.3, 0.6, -2.2), "stop"), (0.45, V(0.0, 0.2, 0.3), "stop"), (L_DUR, ZERO))
L_CAM = Curve((0.00, ZERO), (0.16, V(-0.30, 0.0, -0.35), "stop"), (T_PUSH, V(-0.12, 0.0, -0.25)),
              (0.46, V(0.05, 0.0, 0.0), "stop"), (L_DUR, ZERO))


def shell_quat(t):
    e = SHELL_TILT(t)
    return quat_mul(quat_axis_angle((1, 0, 0), float(e[0])), quat_axis_angle((0, 1, 0), float(e[1])))


def reload_shell_pose(t):
    p = rig.Pose()
    f = fade_out(t, L_DUR, 0.10)
    push_t = ring(t, T_IN - 0.012, V(0.0, 0.0022, -0.0012), 8.5, 0.40) * f
    push_r = ring(t, T_IN - 0.012, V(-0.2, 1.1, -0.9), 8.5, 0.40) * f
    load_gun(p, 1.0, L_GUN_T(t) + push_t, L_GUN_R(t) + push_r)
    p.gate = float(np.clip(L_GATE(t), 0.0, rig.GATE_IN))
    # the shell follows its path all through the clip (hidden before it is fetched and once it is in the tube), so
    # its baked transform never jumps while it is drawn
    p.shell = (SHELL_POS(t), shell_quat(t))
    p.shell_shown = T_SHOW <= t < T_HIDE
    left_hand(p, L_HAND, t, L_HAND_OFF(t))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(np.clip(L_GRAB(t), 0.0, 1.0)))
    curls = hands.adjust(curls, thumb=(float(L_THUMB(t)), 0.0),
                         index=(min(0.0, float(L_GRAB(t))) * 20.0, 0), middle=(min(0.0, float(L_GRAB(t))) * 20.0, 0))
    p.hand_l = (pos, q, curls)
    cam(p, *(L_CAM(t) + ring(t, T_IN - 0.010, V(0.32, 0.0, -0.22), 8.0, 0.38) * f))
    return p


RELOAD_SHELL = Clip("reload_shell", L_DUR, reload_shell_pose, events=[(T_IN - 0.012, "shell_in")],
                    extra_times=(T_SHOW, T_ALIGN, T_PUSH, T_IN - 0.012, T_IN, T_HIDE))

# reload_end: the gun rolls a touch further first, the hand sweeps from the port onto the forend and slaps it (the gun
# bucks), the gun rolls back upright past the hip pose and settles
E_DUR = 0.42
T_SLAP = 0.235
E_U = Curve((0.00, 1.0), (0.05, 1.035, "stop"), (0.30, -0.055, "stop"), (E_DUR, 0.0))
E_LIFT = Curve((0.00, ZERO), (0.16, V(0.003, 0.005, 0.002)), (0.30, V(-0.0010, -0.0012, 0.0), "stop"), (E_DUR, ZERO))
E_HAND = Weights((0.00, {"port": 1}), (0.04, {"port": 1}), (T_SLAP, {"support": 1}), (E_DUR, {"support": 1}))
E_HAND_OFF = Curve((0.00, ZERO), (0.04, ZERO), (0.13, V(-0.018, -0.030, 0.018), "stop"),
                   (T_SLAP - 0.02, V(0.0, 0.0030, -0.0010), "stop"), (T_SLAP + 0.03, ZERO))
E_OPEN = Curve((0.0, 0.0), (0.03, 0.0), (0.10, 0.8, "stop"), (T_SLAP - 0.01, 0.4), (T_SLAP + 0.03, 0.0))
E_CAM = Curve((0.00, ZERO), (0.05, V(-0.10, 0, -0.20), "stop"), (0.28, V(0.45, -0.05, 0.75), "stop"), (E_DUR, ZERO))


def end_hand(p, t, weights, offset, opening, slap):
    left_hand(p, weights, t, offset(t))
    pos, q, curls = p.hand_l
    curls = open_fingers(curls, float(np.clip(opening(t), 0.0, 1.0)))
    p.hand_l = (pos, q, hands.adjust(curls, **{n: (clench(t, slap + 0.03, -8.0, 0.045), 0)
                                               for n in ("index", "middle", "ring")}))


def reload_end_pose(t):
    p = rig.Pose()
    f = fade_out(t, E_DUR, 0.08)
    off = E_LIFT(t) + ring(t, T_SLAP, V(0.0, 0.0018, -0.0006), 8.5, 0.4) * f
    rot = ring(t, T_SLAP, V(0.2, 1.0, 0.8), 8.5, 0.4) * f
    load_gun(p, float(E_U(t)), off, rot)
    end_hand(p, t, E_HAND, E_HAND_OFF, E_OPEN, T_SLAP)
    cam(p, *(E_CAM(t) + ring(t, T_SLAP, V(0.20, 0, 0.15), 8.0, 0.4) * f))
    return p


RELOAD_END = Clip("reload_end", E_DUR, reload_end_pose, events=[(T_SLAP - 0.005, "inspect_move")],
                  extra_times=(0.05, T_SLAP, 0.30))

# reload_end_empty: as reload_end (the gun only comes most of the way back), then the pump is racked to chamber a round,
# then back to the hip pose
EE_DUR = 0.80
T_SLAP_E = 0.225
EE_PUMP = PumpCycle(0.285)
EE_U = Curve((0.00, 1.0), (0.05, 1.035, "stop"), (0.28, 0.10), (EE_PUMP.t_fwd, 0.035), (0.70, -0.02, "stop"),
             (EE_DUR, 0.0))
EE_LIFT = Curve((0.00, ZERO), (0.15, V(0.003, 0.005, 0.002)), (0.28, ZERO))
EE_HAND = Weights((0.00, {"port": 1}), (0.04, {"port": 1}), (T_SLAP_E, {"support": 1}), (EE_DUR, {"support": 1}))
EE_HAND_OFF = Curve((0.00, ZERO), (0.04, ZERO), (0.12, V(-0.018, -0.030, 0.018), "stop"),
                    (T_SLAP_E - 0.02, V(0.0, 0.0030, -0.0010), "stop"), (T_SLAP_E + 0.03, ZERO))
EE_OPEN = Curve((0.0, 0.0), (0.03, 0.0), (0.10, 0.8, "stop"), (T_SLAP_E - 0.01, 0.4), (T_SLAP_E + 0.03, 0.0))
EE_CAM = Curve((0.00, ZERO), (0.05, V(-0.10, 0, -0.20), "stop"), (0.25, V(0.35, -0.04, 0.55), "stop"),
               (0.45, V(0.10, 0, 0.15)), (EE_DUR, ZERO))


def reload_end_empty_pose(t):
    p = rig.Pose()
    f = fade_out(t, EE_DUR, 0.08)
    b_off, b_rot = EE_PUMP.gun_motion(t)
    off = EE_LIFT(t) + b_off + ring(t, T_SLAP_E, V(0.0, 0.0016, -0.0006), 8.5, 0.4) * f
    rot = b_rot + ring(t, T_SLAP_E, V(0.2, 0.9, 0.7), 8.5, 0.4) * f
    load_gun(p, float(EE_U(t)), off, rot)
    end_hand(p, t, EE_HAND, EE_HAND_OFF, EE_OPEN, T_SLAP_E)
    if EE_PUMP.active(t):
        EE_PUMP.apply(p, t)
        p.fingers_l = hands.adjust(p.fingers_l, **{n: (clench(t, T_SLAP_E + 0.03, -8.0, 0.045), 0)
                                                   for n in ("index", "middle", "ring")})
    c = EE_CAM(t) + EE_PUMP.camera(t) + ring(t, T_SLAP_E, V(0.18, 0, 0.12), 8.0, 0.4) * f
    cam(p, *c)
    return p


RELOAD_END_EMPTY = Clip("reload_end_empty", EE_DUR, reload_end_empty_pose, events=EE_PUMP.events(), fps=120,
                        extra_times=(0.05, T_SLAP_E) + EE_PUMP.times())

# --------------------------------------------------------------------------------------- inspect
# a small dip as the weight shifts, the gun swings up to show its left flank (past it, settles, drifts while the
# fingers re-grip), a wind-up, the heavy turn over to the port side (past it, settles), a press-check (the pump eased
# back against its spring, the gun tipped towards the eye to look into the chamber, the spring runs it home with a
# clack), a beat, and the swing back to the hip pose past it and settle
INSPECT_DUR = 3.70
CHECK = PumpCycle(2.12, travel=0.026, hold=0.36, brace=0.06, pull=0.16, soft=True)
P1_T, P1_R = V(-0.048, 0.034, -0.030), V(32, 6, -6)
P2_T, P2_R = V(-0.110, 0.050, -0.090), V(-48, 6, 14)
I_OFF = Curve((0.00, ZERO), (0.10, V(0.003, -0.005, 0.003), "stop"), (0.36, P1_T * 0.6 + V(0.0, 0.012, 0.0)),
              (0.56, P1_T * 1.07, "stop"), (0.78, P1_T * 0.98, "stop"), (1.36, P1_T * 1.01),
              (1.48, P1_T + V(0.008, -0.004, 0.004), "stop"), (1.71, (P1_T + P2_T) * 0.5 + V(0.0, -0.030, 0.006)),
              (1.92, P2_T * 1.04 + V(0.0, 0.002, 0.0), "stop"), (2.10, P2_T * 0.99, "stop"),
              (CHECK.t_back, P2_T + V(0.004, 0.006, 0.012), "stop"), (CHECK.t_fwd, P2_T + V(0.003, 0.004, 0.008)),
              (2.92, P2_T * 1.0, "stop"), (3.04, P2_T * 1.02 + V(0.004, 0.003, 0.0), "stop"),
              (3.23, P2_T * 0.42 + V(0.0, -0.016, 0.004)), (3.42, V(-0.0035, 0.0040, -0.0030), "stop"), (3.56, V(0.0008, -0.0010, 0.0006), "stop"),
              (INSPECT_DUR, ZERO))
I_ROT = Curve((0.00, ZERO), (0.10, V(-1.0, -1.6, 2.2), "stop"), (0.36, P1_R * 0.6 + V(0.0, 4.0, 0.0)),
              (0.56, P1_R * 1.09, "stop"), (0.78, P1_R * 0.97, "stop"), (1.36, P1_R * 1.02),
              (1.48, P1_R + V(4.0, 1.5, -2.0), "stop"), (1.71, (P1_R + P2_R) * 0.5 + V(0.0, -10.0, 10.0)),
              (1.92, P2_R * 1.06, "stop"), (2.10, P2_R * 0.98, "stop"),
              (CHECK.t_back, P2_R + V(4.0, 3.0, 9.0), "stop"), (CHECK.t_fwd, P2_R + V(3.0, 2.0, 7.0)),
              (2.92, P2_R * 1.0, "stop"), (3.04, P2_R * 1.03 + V(0.0, 1.5, -2.0), "stop"),
              (3.23, P2_R * 0.42 + V(0.0, -6.0, 4.0)), (3.42, V(1.2, 1.6, -1.8), "stop"), (3.56, V(-0.3, -0.4, 0.4), "stop"), (INSPECT_DUR, ZERO))
I_CAM = Curve((0.00, ZERO), (0.10, V(-0.15, 0, 0.2), "stop"), (0.56, V(0.45, -0.35, 0.9), "stop"),
              (1.36, V(0.30, -0.20, 0.6)), (1.50, V(0.40, -0.25, 0.8), "stop"), (1.92, V(-0.40, 0.45, -1.3), "stop"),
              (CHECK.t_back, V(-0.55, 0.40, -1.6), "stop"), (2.92, V(-0.25, 0.30, -0.9)),
              (3.42, V(0.25, -0.05, 0.4), "stop"), (INSPECT_DUR, ZERO))
I_SH_R = Curve((0.00, ZERO), (0.10, ZERO), (0.56, V(0.0, 0.07, -0.07)), (1.48, V(0.0, 0.07, -0.07)),
               (1.92, V(0.010, -0.030, 0.010)), (3.04, V(0.010, -0.030, 0.010)), (3.48, ZERO))
I_SH_L = Curve((0.00, ZERO), (0.10, ZERO), (0.56, V(-0.020, 0.010, -0.040)), (1.48, V(-0.020, 0.010, -0.040)),
               (1.92, V(0.010, 0.000, -0.050)), (3.04, V(0.010, 0.000, -0.050)), (3.48, ZERO))


def inspect_pose(t):
    p = rig.Pose()
    # moving holds: the gun keeps drifting in the two showcase poses (faded out by the transitions' own motion)
    hold = window(t, 0.62, 1.40, 0.15) + window(t, 1.98, 3.00, 0.15)
    off = I_OFF(t) + drift(t, V(0.0010, 0.0012, 0.0008), seed=31, freq=0.55) * hold
    rot = I_ROT(t) + drift(t, V(0.7, 0.9, 1.0), seed=32, freq=0.5) * hold
    c_off, c_rot = CHECK.gun_motion(t)
    apply_gun(p, off + c_off, rot + c_rot)
    p.shoulder_r, p.shoulder_l = I_SH_R(t), I_SH_L(t)
    # the hands keep re-gripping during the holds (a tap of the trigger finger, the support fingers rolling)
    w = 2 * np.pi * t / 1.6
    p.fingers_l = hands.adjust(hands.POSES["handguard"], index=(-3 * np.sin(w) * window(t, 0.5, 1.5, 0.2), 0),
                               middle=(-2 * np.sin(w + 0.5) * window(t, 0.5, 1.5, 0.2), 0),
                               ring=(clench(t, 1.62, -6.0, 0.06), 0))
    p.fingers_r = grip_fingers(p, index=(2.5 * np.sin(2 * np.pi * t / 1.3) * window(t, 0.3, 3.3, 0.3)
                                         + clench(t, 2.9, 10.0, 0.08), 0.0))
    if CHECK.active(t):
        CHECK.apply(p, t)
    cam(p, *(I_CAM(t) + CHECK.camera(t)))
    return p


INSPECT = Clip("inspect", INSPECT_DUR, inspect_pose,
               events=[(0.06, "inspect_move"), (1.46, "inspect_move")] + CHECK.events() + [(3.02, "inspect_move")],
               extra_times=CHECK.times())

# ------------------------------------------------------------------------------------ locomotion
# one loop = two steps (left foot down at t = 0, right at half the loop); the body bobs with a quick dip after each
# contact, the gun's mass lags it (Follow, periodic) so the muzzle keeps dipping a beat after the foot lands
def strikes(t, dur, amp_t, amp_r, freq=9.0, zeta=0.42):
    """Foot strikes at t = 0 (left) and dur / 2 (right), periodic: the weapon jolts down and rings out; x and roll
    swap sides with the foot. -> (offset, rotation)"""
    off, rot = ZERO.copy(), ZERO.copy()
    flip = V(-1.0, 1.0, 1.0)
    for k, tc in enumerate((0.0, dur / 2)):
        x = (t - tc - 0.008) % dur
        s = flip if k else V(1.0, 1.0, 1.0)
        off = off + ring(x, 0.0, amp_t * s, freq, zeta)
        rot = rot + ring(x, 0.0, amp_r * V(s[0], 1.0, s[0]), freq, zeta)
    return off, rot


WALK_DUR = 1.00
WALK_B = lambda t: step_bounce(t, WALK_DUR / 2, 0.10, 1.5)
WALK_LAG = Follow(lambda t: V(WALK_B(t)), WALK_DUR, freq=3.2, zeta=0.45, loop=True)


def walk_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / WALK_DUR
    b = WALK_B(t)
    lag = float(WALK_LAG.lag(t)[0])
    side = np.cos(w * (t - 0.05))
    s_off, s_rot = strikes(t, WALK_DUR, V(0.0004, -0.0011, 0.0004), V(0.15, -0.55, 0.35), 8.0, 0.45)
    apply_gun(p, V(0.004 * side, 0.003 - 0.0068 * b - 0.0030 * lag, 0.0019 * b - 0.0010 * np.sin(w * t)) + s_off,
              V(0.85 * np.sin(w * t), 0.3 - 0.75 * b - 1.6 * lag, 1.35 * side + 0.4 * np.sin(2 * w * (t - 0.08)))
              + s_rot)
    p.fingers_r = grip_fingers(p, index=(1.2 * np.sin(w * t), 0))
    p.fingers_l = hands.adjust(hands.POSES["handguard"], middle=(-2 * b, 0), ring=(-2 * b, 0))
    return p


WALK = Clip("walk", WALK_DUR, walk_pose, loop=True)

SPRINT_DUR = 0.66
LOW_T = V(0.160, -0.120, -0.310)                  # running carry: shotgun lowered, muzzle down-left (absolute)
LOW_Q = gun_frame((-0.34, -0.50, -0.80), (-0.30, 0.78, -0.48))
LOW_SHOULDER = V(0.080, 0.160, -0.100)
LOW_POLE = V(0.8, 0.5, 0.3)
L_RUN_C = V(-0.140, -0.300, -0.170)
L_RUN_A = V(0.025, 0.150, -0.130)
SPRINT_B = lambda t: step_bounce(t, SPRINT_DUR / 2, 0.10, 2.2)
SPRINT_LAG = Follow(lambda t: V(SPRINT_B(t)), SPRINT_DUR, freq=4.5, zeta=0.35, loop=True)


def sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / SPRINT_DUR
    b = SPRINT_B(t)
    lag = float(SPRINT_LAG.lag(t)[0])
    side = np.cos(w * (t - 0.04))
    swing = np.cos(w * t)                           # the gun arm swings against the pumping left arm
    s_off, s_rot = strikes(t, SPRINT_DUR, V(0.0012, -0.0030, 0.0010), V(0.6, -1.7, 1.3))
    p.gun_t = LOW_T + V(0.006 * side, -0.013 * b - 0.005 * lag + 0.004 * swing, 0.011 * swing) + s_off
    p.gun_q = quat_mul(LOW_Q, quat_euler(*(V(2.4 * np.sin(w * t), -3.0 * b - 3.0 * lag + 3.2 * swing,
                                             3.6 * side + 1.2 * np.sin(2 * w * (t - 0.05))) + s_rot)))
    p.shoulder_r, p.pole_r = LOW_SHOULDER, LOW_POLE
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_left(t, SPRINT_DUR, L_RUN_C, L_RUN_A * 1.05)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


SPRINT = Clip("sprint", SPRINT_DUR, sprint_pose, loop=True)

TAC_DUR = 0.58
TAC_T = V(0.150, -0.150, -0.290)                  # tactical sprint: muzzle up in front of the right shoulder
TAC_Q = gun_frame((-0.14, 0.88, -0.46), (-0.30, 0.42, 0.86))
TAC_SHOULDER = V(-0.040, -0.120, -0.100)
TAC_B = lambda t: step_bounce(t, TAC_DUR / 2, 0.10, 2.4)
TAC_LAG = Follow(lambda t: V(TAC_B(t)), TAC_DUR, freq=4.8, zeta=0.35, loop=True)


def tac_sprint_pose(t):
    p = rig.Pose()
    w = 2 * np.pi / TAC_DUR
    b = TAC_B(t)
    lag = float(TAC_LAG.lag(t)[0])
    side = np.cos(w * (t - 0.035))
    s_off, s_rot = strikes(t, TAC_DUR, V(0.0012, -0.0034, 0.0008), V(0.6, -1.9, 1.4))
    p.gun_t = TAC_T + V(0.007 * side, 0.005 - 0.016 * b - 0.006 * lag, 0.004 * b) + s_off
    p.gun_q = quat_mul(TAC_Q, quat_euler(*(V(2.0 * np.sin(w * t), -3.0 * b - 3.4 * lag,
                                             3.8 * side + 1.0 * np.sin(2 * w * (t - 0.04))) + s_rot)))
    p.shoulder_r = TAC_SHOULDER
    p.fingers_r = hands.blend({"ak_indexed": 1.0})
    p.hand_l = pump_left(t, TAC_DUR, L_RUN_C + V(0.0, 0.010, 0.0), L_RUN_A * 1.14)
    p.pole_l = V(-0.60, -0.70, 0.50)
    return p


TAC_SPRINT = Clip("tac_sprint", TAC_DUR, tac_sprint_pose, loop=True)


def all_clips():
    return [IDLE, DRAW, FIRE, FIRE_LAST, RELOAD_START, RELOAD_SHELL, RELOAD_END, RELOAD_END_EMPTY, INSPECT, WALK,
            SPRINT, TAC_SPRINT]
