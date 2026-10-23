"""Butterfly knife clips (VIEWMODEL_SPEC sections 12 and 31).

Root motion is a Hermite spline through key poses (Motion). Per key, and separately for position and orientation
({"pos": mode, "rot": mode}): 'flow' carries the velocity through the key (("flow", g) scales it - g > 1 puts the
speed peak on the key, a slash at its contact), 'stop' is an extreme (wind-up apex, end of the follow-through), 'hit'
arrives at speed and stops dead (full extension of a stab), 'snap' leaves a held pose at speed. Secondary layers ride
on the keyed motion: impact rings (a blade slamming open, the latch snapping shut, a catch absorbed by the wrist),
wrist pumps that drive the flips, moving holds, and a camera that coils with the wind-ups and leans along the cuts.
Contact keys sit exactly on the contact times of knives.json. dagger_clips builds its clips from the same helpers."""
import numpy as np

import armrig
import hands
import knife_rig as kr
from anim import EASES, Curve, Track, drift, ring, smoothstep
from p9_clips import Clip
from vmath import norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate, quat_slerp

V = lambda *a: np.array(a, float)
DEG = np.degrees


# ------------------------------------------------------------------------------------- splines
def qlog(q):
    q = q if q[3] >= 0 else -q
    s = np.linalg.norm(q[:3])
    return q[:3] * 2 if s < 1e-9 else q[:3] / s * 2 * np.arctan2(s, q[3])


def qexp(rv):
    ang = np.linalg.norm(rv)
    return np.array([0, 0, 0, 1.0]) if ang < 1e-9 else quat_axis_angle(rv / ang, np.degrees(ang))


def to_rv(q):
    return qlog(quat_mul(quat_conj(kr.REST_Q), q))


def from_rv(rv):
    return quat_mul(kr.REST_Q, qexp(rv))


def _part(mode, part):
    """A key's mode for "pos" or "rot": {"pos": ..., "rot": ...} splits them, anything else applies to both."""
    if isinstance(mode, dict) and ("pos" in mode or "rot" in mode):
        return mode.get(part, "flow")
    return mode


def _kind(mode):
    if isinstance(mode, dict):
        return "explicit", mode                       # {"in": tangent, "out": tangent} (units per second)
    return (mode[0], mode[1]) if isinstance(mode, tuple) else (mode, None)


def _tangents(ts, seg, modes, zero):
    """In / out tangents per key from the segment deltas `seg` (key i -> i + 1) and the key modes."""
    n = len(ts)
    m_in, m_out = [zero.copy() for _ in range(n)], [zero.copy() for _ in range(n)]
    for i in range(n):
        kind, k = _kind(modes[i])
        if n == 1 or kind == "stop":
            continue
        if kind == "explicit":
            m_in[i] = zero + np.asarray(k.get("in", zero), float)
            m_out[i] = zero + np.asarray(k.get("out", zero), float)
            continue
        if kind == "whip":                            # the speed peak sits on this key: sum of the two secants
            if 0 < i < n - 1:
                m = (1.0 if k is None else k) * (seg[i - 1] / (ts[i] - ts[i - 1]) + seg[i] / (ts[i + 1] - ts[i]))
                m_in[i], m_out[i] = m.copy(), m.copy()
            continue
        if kind == "hit":
            if i > 0:
                m_in[i] = (2.0 if k is None else k) * seg[i - 1] / (ts[i] - ts[i - 1])
            continue
        if kind == "snap":
            if i < n - 1:
                m_out[i] = (2.0 if k is None else k) * seg[i] / (ts[i + 1] - ts[i])
            continue
        i0, i1 = max(i - 1, 0), min(i + 1, n - 1)
        span = sum((seg[j] for j in range(i0, i1)), zero.copy())
        m = (1.0 if k is None else k) * span / (ts[i1] - ts[i0])
        m_in[i], m_out[i] = m.copy(), m.copy()
    return m_in, m_out


def _basis(s):
    s2, s3 = s * s, s * s * s
    return 2 * s3 - 3 * s2 + 1, s3 - 2 * s2 + s, -2 * s3 + 3 * s2, s3 - s2


class Spline:
    """Hermite spline through (t, value, mode) keys; modes as Motion's ('flow' = Catmull-Rom, 'stop' = zero, ...)."""

    def __init__(self, *keys):
        self.t = np.array([k[0] for k in keys], float)
        self.v = [np.asarray(k[1], float) for k in keys]
        modes = [k[2] if len(k) > 2 else "flow" for k in keys]
        seg = [self.v[i + 1] - self.v[i] for i in range(len(keys) - 1)]
        self.m_in, self.m_out = _tangents(self.t, seg, modes, np.zeros_like(self.v[0]))

    def __call__(self, t):
        if t <= self.t[0]:
            return self.v[0].copy()
        if t >= self.t[-1]:
            return self.v[-1].copy()
        i = int(np.searchsorted(self.t, t, side="right") - 1)
        h = self.t[i + 1] - self.t[i]
        h00, h10, h01, h11 = _basis((t - self.t[i]) / h)
        return h00 * self.v[i] + h10 * h * self.m_out[i] + h01 * self.v[i + 1] + h11 * h * self.m_in[i + 1]


class Motion:
    """Root position + orientation, splined through key poses (t, pos, orientation, mode); orientation keys are
    (blade_dir, spine_dir) pairs or quaternions. Orientation is a Hermite spline in the rotation-vector space of each
    segment's start key, so it never passes through the singular frames a per-axis direction spline can hit."""

    def __init__(self, *keys):
        qs = [kr.knife_quat(*o) if isinstance(o, tuple) else np.asarray(o, float) for _, _, o, _ in keys]
        for i in range(1, len(qs)):
            if np.dot(qs[i], qs[i - 1]) < 0:
                qs[i] = -qs[i]
        self.pos = Spline(*[(k[0], k[1], _part(k[3], "pos")) for k in keys])
        self.t = np.array([k[0] for k in keys], float)
        self.q = qs
        self.d = [qlog(quat_mul(quat_conj(qs[i]), qs[i + 1])) for i in range(len(qs) - 1)]   # segment rotations
        # angular velocity at each key, in that key's frame (a segment's rotation vector is the same in both of its
        # keys' frames)
        self.m_in, self.m_out = _tangents(self.t, self.d, [_part(k[3], "rot") for k in keys], np.zeros(3))

    def quat(self, t):
        if t <= self.t[0]:
            return self.q[0].copy()
        if t >= self.t[-1]:
            return self.q[-1].copy()
        i = int(np.searchsorted(self.t, t, side="right") - 1)
        h = self.t[i + 1] - self.t[i]
        _, h10, h01, h11 = _basis((t - self.t[i]) / h)
        d = self.d[i]
        m1 = quat_rotate(qexp(d), self.m_in[i + 1])                # end tangent seen from the start key's frame
        return quat_mul(self.q[i], qexp(h10 * h * self.m_out[i] + h01 * d + h11 * h * m1))

    def apply(self, p, t):
        p.knife_t = self.pos(t)
        p.knife_q = self.quat(t)


def knife_q_flat(blade_dir, x_hint):
    """Orientation from the blade direction and the direction the right flat (knife +X) should face."""
    z = -norm(blade_dir)
    x = norm(np.asarray(x_hint, float) - z * np.dot(x_hint, z))
    return kr.frame_quat(x, np.cross(z, x), z)


def centre_root(q, depth, blade_mid=None):
    """Knife root such that the blade (pin->tip midpoint) lies on the screen-centre ray."""
    return V(0, 0, -depth) - quat_rotate(q, kr.BLADE_MID if blade_mid is None else blade_mid)


def feasible_for(rig, pose_cls, blade_mid, *keys, contact=None, extra=None, iters=8):
    """Authoring pass: every key pose takes the orientation the wrist allows (the knife turns with the hand about
    the wrist), so the splines run between reachable poses; the contact key is then re-centred so the solved blade
    (with the clip's secondary layers `extra(p, t)`) crosses the screen centre exactly at its time."""
    keys = [list(k) for k in keys]
    for k in keys:
        q = rig.knife_quat(*k[2]) if isinstance(k[2], tuple) else k[2]
        if np.allclose(k[1], rig.REST_T) and abs(float(np.dot(q, rig.REST_Q))) > 1 - 1e-9:
            k[2] = q                       # the ready stance as the idle has it, so clips start and end on the idle
            continue
        k[1], k[2] = rig.reachable(k[1], q)
    if contact is not None:
        tc = keys[contact][0]
        for _ in range(iters):
            p = pose_cls()
            Motion(*[tuple(k) for k in keys]).apply(p, tc)
            if extra is not None:
                extra(p, tc)
            p.solve()
            mid = p.knife_point(blade_mid)
            keys[contact][1] = keys[contact][1] - V(mid[0], mid[1], 0.0)
    return Motion(*[tuple(k) for k in keys])


def feasible(*keys, contact=None, extra=None, iters=8):
    return feasible_for(kr, kr.KnifePose, kr.BLADE_MID, *keys, contact=contact, extra=extra, iters=iters)


def cam(p, pitch=0.0, yaw=0.0, roll=0.0):
    p.cam_q = quat_euler(yaw, pitch, roll)


def ease(name, t, t0, t1):
    return EASES[name](float(np.clip((t - t0) / (t1 - t0), 0, 1)))


# ------------------------------------------------------------------------------- secondary layers
def turned(q, deg):
    """q turned by knife-local (yaw, pitch, roll) degrees."""
    return quat_mul(q, quat_euler(*deg)) if np.any(np.abs(deg) > 1e-9) else q


def mix(qa, qb, u):
    return quat_slerp(qa, qb, u)


def fade_out(t, dur, fade=0.08):
    """1 until `fade` before the end, 0 at the end: secondary layers die out so one-shots end exactly at rest."""
    return 1.0 - smoothstep(dur - fade, dur, t)


def rings(t, hits, dur):
    """Sum of damped impact responses [(t0, amplitude (vector), freq Hz, zeta)], faded out at the clip's end."""
    out = None
    for t0, amp, f, z in hits:
        r = ring(t, t0, amp, f, z)
        out = r if out is None else out + r
    return out * fade_out(t, dur)


def nudge(p, dpos=None, drot=None):
    """Adds a viewmodel-space offset and a knife-local turn (yaw, pitch, roll degrees) to the knife root."""
    if dpos is not None:
        p.knife_t = p.knife_t + np.asarray(dpos, float)
    if drot is not None:
        p.knife_q = turned(p.knife_q, np.asarray(drot, float))


def whip_end(w_pos, w_q, c_pos, c_q, k, bend=(0.0, 0.0, 0.0), turn=(0.0, 0.0, 0.0)):
    """End of a slash's follow-through: the strike (wind-up -> contact) carried on k times as far along the same
    line and about the same axis (bent by `bend` metres and `turn` knife-local degrees). With k = follow-through
    time / strike time and 'whip' at the contact, the slash accelerates out of the wind-up, peaks exactly at the
    contact and decelerates evenly to a stop."""
    pos = np.asarray(c_pos, float) + k * (np.asarray(c_pos, float) - np.asarray(w_pos, float)) + np.asarray(bend)
    q = quat_mul(c_q, qexp(k * qlog(quat_mul(quat_conj(w_q), c_q))))
    return pos, turned(q, np.asarray(turn, float))


def absorb(t, t0, v0, omega=24.0):
    """A hand catching something moving at v0 (m/s or deg/s): the critically damped give - it keeps moving with the
    catch, decelerates and comes back (0 before t0, peak v0 / (omega e) at 1 / omega after t0)."""
    if t < t0:
        return np.zeros_like(np.asarray(v0, float))
    x = t - t0
    return np.asarray(v0, float) * x * np.exp(-omega * x)


class CamCurve:
    """Camera (pitch, yaw, roll) degrees: a C1 curve through keys plus impact accents [(t0, (p, y, r), f, zeta)];
    accents fade out so the camera is at rest at the end."""

    def __init__(self, dur, *keys, accents=()):
        self.dur, self.curve, self.accents = dur, Curve(*keys), accents

    def __call__(self, p, t):
        v = self.curve(t)
        if self.accents:
            v = v + rings(t, self.accents, self.dur)
        cam(p, *v)


def grip_squeeze(base, t, t_hit, depth=-6.0, width=0.07, loosen=0.0, t_loose=None):
    """The fist tightening around the contact (fingers squeeze, thumb presses); optionally loosened a little before
    (the wrist cocked in the wind-up)."""
    k = depth * np.exp(-((t - t_hit) / width) ** 2)
    if loosen and t_loose is not None:
        k += loosen * np.exp(-((t - t_loose) / 0.05) ** 2)
    return hands.adjust(base, index=(k, k * 0.5), middle=(k, k * 0.5), ring=(k, 0), pinky=(k, 0), thumb=(k * 0.6, 0))


def clamp(t, t_hit, depth=-6.0, width=0.07):
    """Fist tightening around the contact (fingers squeeze, thumb presses)."""
    return grip_squeeze(hands.POSES["fist"], t, t_hit, depth, width)


def periodic_bounce(t, period, lag=0.12, sharp=1.6):
    """0..1 impact curve per footstep (one step = `period`), lowest just after the foot contact: a fast drop into
    the contact and a slower rise (the weight lands, then the legs push it back up)."""
    ph = ((t % period) / period - lag) % 1.0
    drop = 0.5 + 0.5 * np.cos(2 * np.pi * ph)
    return drop ** sharp


REST = (kr.REST_T, kr.REST_Q)
Q = kr.knife_quat


def at_rest(dt=(0, 0, 0), drot=(0, 0, 0)):
    """A key near the ready stance: offset (m) and knife-local turn (deg)."""
    return kr.REST_T + V(*dt), turned(kr.REST_Q, V(*drot))


# --------------------------------------------------------------------------------------- idle
def idle_pose(t):
    p = kr.KnifePose()
    w = 2 * np.pi * t / 2.0
    p.knife_t = kr.REST_T + V(0.0004 * np.sin(w + 0.5), 0.0006 * np.sin(w), 0.0003 * np.sin(w + 1.2))
    p.knife_q = quat_mul(kr.REST_Q, quat_euler(0.25 * np.sin(w + 0.4), 0.35 * np.sin(w + 1.0), 0.4 * np.sin(w)))
    # the fingers stay alive: a slow re-grip, the thumb rubbing the handle
    p.fingers_r = hands.adjust(hands.POSES["fist"], index=(1.5 * np.sin(w + 2.0), 1.0 * np.sin(w + 2.4)),
                               thumb=(2.0 * np.sin(w + 0.8), 1.0 * np.sin(w + 1.3)))
    return p


IDLE = Clip("idle", 2.0, idle_pose, loop=True)

# ---------------------------------------------------------------------------------- light_1
# forehand: a short cock up and right (the wrist bent back, the knife pulled in), the whip down through the centre -
# accelerating out of the wind-up, fastest exactly at the contact - and an even deceleration down-left along the same
# line (whip_end), then back to the guard along an arc, a hair past it and settled. The camera coils right with the
# wind-up and leans left / down along the cut.
T_HIT, DUR_L = 0.13, 0.42
T_WIND = 0.075
# light slash beats: pre-move, wind-up apex, contact, end of follow-through, recovery, overshoot, rest
LIGHT_TIMES = (0.03, T_WIND, T_HIT, 0.22, 0.30, 0.37, DUR_L)


def light_keys(times, rest, wind, c_ori, c_root, pre_off, pre_mix, end, back, over, wind_mode="stop"):
    """Key list of a light slash (rest -> pre -> wind-up -> contact (whip) -> end of follow-through -> recovery ->
    overshoot -> rest) at `times`; the contact key is index 3 (re-centred by feasible). `end`: (pos, quat), or
    (bend, turn) of whip_end (the strike carried on along its own line and axis, follow-through / strike time times
    as far, so the speed peaks at the contact and falls off evenly). `wind_mode`: the wind-up apex is a turning point
    ("stop"), or {"pos": "stop", "rot": "flow"} when the wrist keeps rolling through it into the strike (backhand)."""
    t_pre, t_wind, t_hit, t_end, t_back, t_over, dur = times
    rest_t, rest_q = rest
    if len(end[1]) == 3:
        end = whip_end(wind[0], wind[1], c_root, c_ori, (t_end - t_hit) / (t_hit - t_wind), end[0], end[1])
    return [(0.000, rest_t, rest_q, "stop"),
            (t_pre, rest_t + np.asarray(pre_off, float),
             mix(rest_q, wind[1], pre_mix) if np.isscalar(pre_mix) else pre_mix, "flow"),
            (t_wind, wind[0], wind[1], wind_mode),
            (t_hit, c_root, c_ori, "whip"),
            (t_end, end[0], end[1], "stop"),
            (t_back, back[0], mix(end[1], rest_q, back[1]), "flow"),
            (t_over, rest_t + np.asarray(over[0], float), turned(rest_q, np.asarray(over[1], float)), ("flow", 0.5)),
            (dur, rest_t, rest_q, "stop")]


L1_W = (V(0.168, -0.004, -0.302), Q((0.38, 0.82, -0.43), (0.74, 0.47, 0.45)))
L1_C = Q((-0.72, 0.30, -0.62), (0.60, 0.75, -0.25))
L1_E = (V(-0.072, -0.162, -0.314), Q((-0.26, -0.85, -0.46), (0.62, 0.20, 0.76)))
L1 = feasible(*light_keys(LIGHT_TIMES, REST, L1_W, L1_C, centre_root(L1_C, 0.38), (0.012, 0.016, 0.004), 0.16,
                          end=L1_E, back=(V(0.050, -0.152, -0.332), 0.62),
                          over=((0.004, 0.005, -0.002), (0.0, 2.5, 1.5))), contact=3)
L1_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (T_WIND, V(0.35, -0.30, 0.70)), (T_HIT, V(-0.05, 0.35, -0.60)),
                  (0.190, V(-0.55, 1.05, -2.30)), (0.260, V(-0.35, 0.80, -1.70)), (0.350, V(0.08, -0.08, 0.25)),
                  (DUR_L, V(0, 0, 0)))


def light1_pose(t):
    p = kr.KnifePose()
    L1.apply(p, t)
    p.fingers_r = grip_squeeze(hands.POSES["fist"], t, T_HIT, loosen=4.0, t_loose=T_WIND)
    L1_CAM(p, t)
    return p


# ---------------------------------------------------------------------------------- light_2
# backhand: the hand drops first (so the point passes under the crosshair, not across it) and coils low-left across
# the body (wrist cocked, blade down and back), the whip up through the centre and on out high-right, back to the
# guard; the camera mirrors light_1
L2_W = (V(-0.020, -0.122, -0.316), Q((-0.66, -0.28, -0.70), (-0.15, -0.35, 0.92)))
L2_C = Q((-0.55, 0.45, -0.70), (-0.65, -0.70, 0.10))
L2 = feasible(*light_keys(LIGHT_TIMES, REST, L2_W, L2_C, centre_root(L2_C, 0.38), (0.012, -0.050, 0.004), 0.10,
                          end=(V(0.0, 0.010, 0.010), V(0.0, 0.0, 0.0)), back=(V(0.176, -0.040, -0.318), 0.60),
                          over=((-0.004, -0.004, 0.002), (0.0, -2.0, -1.5)),
                          wind_mode={"pos": "stop", "rot": "flow"}), contact=3)
L2_CAM = CamCurve(DUR_L, (0.0, V(0, 0, 0)), (T_WIND, V(-0.25, 0.30, -0.70)), (T_HIT, V(0.10, -0.35, 0.60)),
                  (0.190, V(0.60, -1.05, 2.30)), (0.260, V(0.40, -0.80, 1.70)), (0.350, V(-0.08, 0.08, -0.25)),
                  (DUR_L, V(0, 0, 0)))


def light2_pose(t):
    p = kr.KnifePose()
    L2.apply(p, t)
    p.fingers_r = grip_squeeze(hands.POSES["fist"], t, T_HIT, loosen=4.0, t_loose=T_WIND)
    L2_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ heavy
# a small forward dip, then the weight goes back: the knife coils to the shoulder (the shoulder too) and creeps a
# little further back while it loads; the thrust snaps out of the coil and accelerates all the way to full extension
# at 0.40 where it stops dead (a hit), the wrist shudders and the arm recoils, then it comes home along an arc
T_EXT, DUR_H = 0.40, 0.95
HV_P = ((-0.30, 0.45, -0.84), (0.10, 0.90, 0.40))
HV_P2 = ((-0.27, 0.50, -0.82), (0.10, 0.90, 0.40))
HV_E = ((-0.40, 0.42, -0.81), (0.10, 0.90, 0.40))
HV = feasible((0.000, kr.REST_T, kr.REST_Q, "stop"),
              (0.055, *at_rest((-0.004, -0.006, -0.010), (0.0, -3.0, 0.0)), "stop"),
              (0.200, V(0.160, -0.072, -0.252), HV_P, "flow"),
              (0.295, V(0.177, -0.067, -0.231), HV_P2, "stop"),
              (T_EXT, V(0.072, -0.068, -0.458), HV_E, {"pos": ("hit", 2.1), "rot": ("hit", 1.6)}),
              (0.470, V(0.086, -0.075, -0.437), turned(Q(*HV_E), V(0.0, -3.0, 0.0)), "stop"),
              (0.660, V(0.114, -0.097, -0.352), mix(Q(*HV_E), kr.REST_Q, 0.78), "flow"),
              (0.840, *at_rest((0.003, -0.004, 0.004), (0.0, -1.5, 0.0)), ("flow", 0.6)),
              (DUR_H, kr.REST_T, kr.REST_Q, "stop"))
HV_SHOULDER = Spline((0.000, V(0, 0, 0), "stop"), (0.055, V(-0.002, 0.0, -0.004), "stop"),
                     (0.295, V(0.007, -0.004, 0.020), "stop"), (T_EXT, V(-0.024, 0.006, -0.036), "hit"),
                     (0.470, V(-0.018, 0.004, -0.028), "stop"), (DUR_H, V(0, 0, 0), "stop"))
HV_RING = [(T_EXT, V(0.0, 0.0015, 0.004), 9.0, 0.35)]
HV_RING_ROT = [(T_EXT, V(0.0, 1.6, 0.8), 9.0, 0.35)]
HV_CAM = CamCurve(DUR_H, (0.0, V(0, 0, 0)), (0.055, V(-0.15, 0.0, 0.0)), (0.295, V(0.65, 0.15, 0.55)),
                  (T_EXT, V(-0.55, -0.10, -0.25), "hit"), (0.620, V(-0.15, 0.0, -0.05)), (DUR_H, V(0, 0, 0)),
                  accents=[(T_EXT, V(-1.1, -0.15, -0.45), 7.0, 0.40)])


def heavy_pose(t):
    p = kr.KnifePose()
    HV.apply(p, t)
    nudge(p, rings(t, HV_RING, DUR_H), rings(t, HV_RING_ROT, DUR_H))
    p.shoulder_r = HV_SHOULDER(t)
    brace = ease("inout", t, 0.16, 0.28) * (1 - ease("inout", t, 0.55, 0.80))     # thumb onto the spine
    p.fingers_r = hands.adjust(grip_squeeze(hands.POSES["fist"], t, T_EXT, -8.0, 0.09), thumb=(30.0 * brace,
                                                                                               25.0 * brace))
    HV_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------- draw
# the closed knife rises from below (off screen for the first tenth of a second), the left hand flicks the bite
# handle out (it swings past and rebounds), the right wrist cocks and flicks: the blade is thrown round over the top,
# accelerating, and slams open at 0.50 - the handles bounce off the stop, the hand jolts, the camera answers - the
# thumb snaps the latch shut (a smaller jolt) and the knife settles into the guard a little past it
DUR_D = 0.80
T_FLICK, T_OPEN, T_LATCH = 0.17, 0.50, 0.61
DR = feasible((0.00, V(0.190, -0.330, -0.270), ((-0.10, -0.30, -0.95), (0.90, 0.30, 0.00)), "stop"),
              (0.10, V(0.180, -0.255, -0.287), ((-0.15, 0.02, -0.99), (0.86, 0.45, 0.10)), "flow"),
              (0.20, V(0.135, -0.100, -0.320), ((-0.25, 0.50, -0.83), (0.75, 0.60, 0.20)), "flow"),
              (0.34, V(0.130, -0.092, -0.325), ((-0.27, 0.55, -0.79), (0.55, 0.75, 0.35)), "flow"),
              (T_OPEN, V(0.117, -0.082, -0.338), ((-0.32, 0.64, -0.70), (0.00, 0.70, 0.70)), "flow"),
              (0.62, V(0.121, -0.090, -0.341), ((-0.295, 0.585, -0.755), (0.13, 0.70, 0.70)), "flow"),
              (0.71, *at_rest((-0.003, 0.004, -0.003), (0.0, 2.0, 0.0)), ("flow", 0.6)),
              (DUR_D, kr.REST_T, kr.REST_Q, "stop"))
# the wrist flick that throws the blade (knife-local pitch, degrees): cocked, then snapped through
DR_FLICK = Curve((0.24, 0.0), (0.31, 9.0), (0.40, -11.0), (0.47, -2.5), (0.53, 0.0))
DR_A = Curve((0.0, 180.0), (0.32, 180.0), (T_OPEN, 0.0, ("hit", 2.2)), (DUR_D, 0.0))
DR_A_BOUNCE = [(T_OPEN, 6.0, 9.0, 0.35)]                       # the handles bounce off the open stop
DR_B = Curve((0.0, 180.0), (T_FLICK, 180.0, ("snap", 0.6)), (0.29, -9.0, "stop"), (0.33, 2.5), (0.37, 0.0),
             (DUR_D, 0.0))
DR_C = Curve((0.0, kr.LATCH_OPEN), (0.55, kr.LATCH_OPEN), (T_LATCH, 0.0, ("hit", 2.0)), (DUR_D, 0.0))
DR_C_BOUNCE = [(T_LATCH, 10.0, 14.0, 0.40)]
DR_LW = Track((0.0, 0.0), (0.06, 0.0), (T_FLICK, 1.0), (0.24, 1.0), (0.40, 0.0))
DR_JOLT = [(T_OPEN, V(-0.003, -0.004, 0.003), 8.0, 0.40), (T_LATCH, V(0.0, -0.0015, 0.0), 11.0, 0.45)]
DR_JOLT_ROT = [(T_OPEN, V(0.8, -3.0, 1.5), 8.0, 0.40), (T_LATCH, V(0.0, -1.0, 0.0), 11.0, 0.45)]
DR_CAM = CamCurve(DUR_D, (0.0, V(0, 0, 0)), (0.20, V(0.3, 0.0, 0.4)), (0.40, V(-0.15, 0.0, -0.2)), (DUR_D, V(0, 0, 0)),
                  accents=[(T_OPEN, V(-0.6, 0.05, -0.4), 7.0, 0.40), (T_LATCH, V(-0.25, 0.0, 0.0), 10.0, 0.45)])


def draw_pose(t):
    p = kr.KnifePose()
    DR.apply(p, t)
    nudge(p, rings(t, DR_JOLT, DUR_D), rings(t, DR_JOLT_ROT, DUR_D) + V(0.0, float(DR_FLICK(t)), 0.0))
    p.a = float(DR_A(t) + rings(t, DR_A_BOUNCE, 0.72))
    p.b = float(DR_B(t))
    p.c = float(DR_C(t) + rings(t, DR_C_BOUNCE, 0.74))
    w = float(DR_LW(t))
    butt = p.point("handle_bite", (0.0, 0.0, 0.118))
    flick = (butt - quat_rotate(p.knife_q, (0.0, 0.0, -0.045)) + V(-0.030, -0.020, 0.020),
             armrig.palm_from_back((-0.9, -0.2, -0.35), (0.35, 0.35, -0.85), "L"),
             hands.blend({"pinch": 1.0}))
    rest_l = (kr.LEFT_REST[0], flick[1], hands.blend({"relaxed": 1.0}))
    p.hand_l = armrig.blend_targets([(1 - w + 1e-6, rest_l), (w, flick)])
    # right hand: the closed knife is held loosely, fingers open as the blade swings round, then lock on hard
    open_r = ease("inout", t, 0.30, 0.38) * (1 - ease("in2", t, 0.46, T_OPEN))
    fist = grip_squeeze(hands.POSES["fist"], t, T_OPEN + 0.03, -7.0, 0.05)
    p.fingers_r = {f: (1 - 0.7 * open_r) * np.asarray(fist[f]) + 0.7 * open_r * np.asarray(hands.POSES["relaxed"][f])
                   for f in hands.FINGERS}
    p.left_scale = 1.0 if t < 0.44 else 0.0
    DR_CAM(p, t)
    return p


# ----------------------------------------------------------------------------------- inspect
# unlatch with the thumb (the latch swings out and bounces), up to the trick pose; two fan flips driven by the wrist
# (it pumps once per turn, the handles speed up as they fall and slow over the top) ending with a slap into the palm;
# a rollover over the back of the hand; a dip and an upward throw tosses the knife: one end-over-end turn at a
# constant spin under real gravity (0.40 s flight), the open hand waits below and rises to meet it, the catch carries
# on down with the knife's momentum and springs back; then the blade is shown to the light with the left hand under
# it (a moving hold, the knife turned slowly), the latch snaps shut and the knife swings back to the guard
DUR_I = 3.60
P_T, Q_T = kr.reachable(V(0.104, -0.088, -0.330), knife_q_flat((-0.40, 0.82, -0.40), (-0.25, 0.25, 0.93)))
P_SHOW, Q_SHOW = kr.reachable(V(0.096, -0.078, -0.322), knife_q_flat((-0.85, 0.30, -0.43), (-0.10, 0.30, 0.95)))
T_FAN0, T_FAN1, T_FAN2 = 0.30, 0.63, 0.98
T_ROLL0, T_ROLL1 = 1.08, 1.55
T_DIP, T_TOSS, T_CATCH = 1.66, 1.76, 2.16
FLIGHT = T_CATCH - T_TOSS
GRAVITY = V(0.0, -9.8, 0.0)
P_REL = P_T + V(0.0, 0.040, -0.006)                            # where the throw lets go (the catch is at P_T)
F_DRIFT = V(-0.020, 0.0, -0.015)                               # the knife drifts out a little on its way up
F_V0 = (P_T - P_REL - 0.5 * GRAVITY * FLIGHT ** 2) / FLIGHT     # launch velocity that lands it in the hand
V_RELEASE = F_V0 + F_DRIFT * np.pi / FLIGHT
V_CATCH = F_V0 + GRAVITY * FLIGHT - F_DRIFT * np.pi / FLIGHT
# the end-over-end spin: the flick winds it up over the last 60 ms in the hand (in2, ending at the flight's rate)
SPIN0 = 0.03 * 360.0 / (FLIGHT + 0.03)
SPIN_RATE = (360.0 - SPIN0) / FLIGHT
T_SHOW, T_RELATCH = 2.56, 3.22
INS = feasible((0.00, kr.REST_T, kr.REST_Q, "stop"),
               (0.21, P_T + V(-0.004, 0.006, 0.002), turned(Q_T, V(0, 3.0, 2.0)), ("flow", 0.7)),
               (0.29, P_T, Q_T, "flow"),
               (T_FAN2, P_T + V(0.002, -0.002, 0.0), turned(Q_T, V(1.0, -1.0, 0.0)), "flow"),
               (T_ROLL0, P_T + V(0.0, 0.001, 0.0), Q_T, "flow"),
               (T_ROLL1, P_T + V(-0.002, 0.002, 0.001), turned(Q_T, V(-1.0, 1.0, 0.0)), "flow"),
               (T_DIP, P_T + V(0.0, -0.024, 0.006), turned(Q_T, V(0.0, -6.0, 0.0)), "stop"),
               (T_TOSS, P_REL, turned(Q_T, V(0.0, 6.0, 0.0)), {"pos": {"in": V_RELEASE, "out": V_RELEASE},
                                                               "rot": "flow"}),
               (T_CATCH, P_T, Q_T, {"pos": "stop", "rot": "flow"}),
               (2.36, P_T + V(-0.002, -0.004, 0.002), turned(Q_T, V(0.0, -1.5, 0.0)), "flow"),
               (T_SHOW, P_SHOW, Q_SHOW, "flow"),
               (2.90, P_SHOW + V(-0.003, 0.002, 0.0), turned(Q_SHOW, V(4.0, 0.0, 3.0)), "flow"),
               (T_RELATCH - 0.06, P_SHOW + V(-0.004, 0.003, 0.0), turned(Q_SHOW, V(-1.5, 0.0, 0.5)), "flow"),
               (3.36, V(0.110, -0.084, -0.334), mix(Q_SHOW, kr.REST_Q, 0.55), "flow"),
               (3.50, *at_rest((-0.002, 0.003, -0.002), (0.0, 1.8, 0.0)), ("flow", 0.6)),
               (DUR_I, kr.REST_T, kr.REST_Q, "stop"))
Q_RELEASE = INS.quat(T_TOSS)
# blade angle: two fan flips (a little faster as the handles fall, slower over the top), a slap into the palm, then
# the rollover carries one more turn
FAN = Curve((0.0, 0.0), (T_FAN0, 0.0), (T_FAN1, 360.0, ("flow", 1.05)), (T_FAN2, 720.0, ("hit", 1.4)),
            (T_ROLL0, 720.0), (T_ROLL1, 1080.0, ("hit", 1.5)), (DUR_I, 1080.0))
ROLL = Curve((0.0, 0.0), (T_ROLL0, 0.0), (T_ROLL1, 360.0, ("hit", 1.2)), (DUR_I, 360.0))
FAN_SLAPS = [(T_FAN2, -5.0, 10.0, 0.40), (T_ROLL1, -4.0, 10.0, 0.40)]       # the handles bounce off the palm
INS_C = Curve((0.0, 0.0), (0.08, 0.0), (0.20, kr.LATCH_OPEN, ("hit", 1.8)), (T_RELATCH, kr.LATCH_OPEN),
              (3.32, 0.0, ("hit", 2.0)), (DUR_I, 0.0))
INS_C_BOUNCE = [(0.20, -9.0, 12.0, 0.40), (3.32, 9.0, 14.0, 0.40)]
INS_JOLT = [(0.20, V(0.0, -0.001, 0.0), 11.0, 0.45), (T_FAN2, V(0.0, -0.003, 0.002), 8.0, 0.40),
            (T_ROLL1, V(0.0, -0.0025, 0.0015), 8.0, 0.40), (3.32, V(0.0, -0.0015, 0.0), 11.0, 0.45)]
INS_JOLT_ROT = [(T_FAN2, V(0.0, -2.0, 1.0), 8.0, 0.40), (T_ROLL1, V(1.5, -1.5, 0.0), 8.0, 0.40),
                (3.32, V(0.0, -1.0, 0.0), 11.0, 0.45)]
INS_CAM = CamCurve(DUR_I, (0.0, V(0, 0, 0)), (0.28, V(0.4, 0.0, 1.2)), (T_FAN2, V(0.3, 0.0, 1.0)),
                   (1.10, V(0.1, -0.2, -0.4)), (T_ROLL1, V(0.2, -0.3, -1.0)), (T_DIP + 0.02, V(-0.30, 0.0, -0.4)),
                   (T_TOSS + 0.19, V(0.9, 0.0, 0.3)), (T_CATCH, V(0.2, 0.0, 0.2)), (2.60, V(-0.2, 0.4, 1.4)),
                   (3.10, V(-0.1, 0.3, 1.2)), (DUR_I, V(0, 0, 0)),
                   accents=[(T_FAN2, V(-0.3, 0.0, -0.2), 8.0, 0.40), (T_ROLL1, V(-0.25, 0.1, 0.0), 8.0, 0.40),
                            (T_CATCH, V(-0.7, 0.0, -0.3), 7.0, 0.40), (3.32, V(-0.2, 0.0, 0.0), 10.0, 0.45)])
# the throwing hand follows through up, drops to wait open below and rises to meet the knife (offsets along the
# release -> catch line, by flight fraction)
TOSS_HAND = Spline((0.0, V(0, 0, 0), "stop"), (0.18, V(0.004, 0.030, -0.004), "flow"),
                   (0.62, V(0.0, -0.030, 0.008), "stop"), (1.0, V(0, 0, 0), ("flow", 0.8)))


def fan_pump(t, a):
    """The wrist pumping the flips: phase-locked to the blade angle (one pump per turn), faded at the ends."""
    w = smoothstep(T_FAN0 - 0.04, T_FAN0 + 0.06, t) * (1 - smoothstep(T_ROLL1 - 0.06, T_ROLL1 + 0.04, t))
    w *= 1.0 - 0.55 * (smoothstep(T_FAN2 - 0.02, T_FAN2 + 0.04, t) * (1 - smoothstep(T_ROLL0 - 0.04, T_ROLL0, t)))
    ph = np.radians(a)
    return (V(0.0, 0.0040 * np.sin(ph + 1.0), 0.0015 * np.sin(ph + 2.6)) * w,
            V(3.0 * np.sin(ph + 0.2), 4.0 * np.sin(ph + 2.2), 7.0 * np.sin(ph + 0.6)) * w)


def toss(p, t):
    """The aerial: the flick winds the spin up in the hand, the knife flies a ballistic arc from the release to the
    hand (constant spin, its base orientation turning from the release's to the catch's), and the catch gives with
    its momentum (it carries on down and turns on a little, then the wrist brings it back)."""
    def rx_(deg):
        return quat_axis_angle((1, 0, 0), deg)
    if T_TOSS - 0.06 < t < T_TOSS:
        p.knife_q = quat_mul(p.knife_q, rx_(SPIN0 * ease("in2", t, T_TOSS - 0.06, T_TOSS)))
    elif T_TOSS <= t < T_CATCH:
        x = t - T_TOSS
        s = x / FLIGHT
        p.knife_t = P_REL + F_V0 * x + 0.5 * GRAVITY * x * x + F_DRIFT * np.sin(np.pi * s)
        p.knife_q = quat_mul(quat_slerp(Q_RELEASE, Q_T, s), rx_(SPIN0 + SPIN_RATE * x))
        p.a = 18.0 * np.sin(2 * np.pi * s)                        # the handles flutter apart in the air
    elif t >= T_CATCH:
        k = fade_out(t, DUR_I)
        nudge(p, absorb(t, T_CATCH, V_CATCH, 22.0) * k)
        p.knife_q = quat_mul(p.knife_q, rx_(float(absorb(t, T_CATCH, SPIN_RATE, 20.0)) * k))
    if T_TOSS - 0.02 < t < T_CATCH + 0.02:
        g_w, g_q = kr.GRIP_PALM
        s = float(np.clip((t - T_TOSS) / FLIGHT, 0, 1))
        p.free_w = min(ease("out2", t, T_TOSS - 0.01, T_TOSS + 0.03), 1 - ease("in2", t, T_CATCH - 0.05, T_CATCH))
        base = P_REL + (P_T - P_REL) * s
        catch = hands.blend({"spread": 1.0 - ease("in2", t, T_CATCH - 0.07, T_CATCH),
                             "fist": ease("in2", t, T_CATCH - 0.07, T_CATCH)})
        p.free_r = (base + quat_rotate(Q_T, g_w) + TOSS_HAND(s), quat_mul(Q_T, g_q), catch)


def inspect_pose(t):
    p = kr.KnifePose()
    INS.apply(p, t)
    # unlatch / relatch with the thumb
    p.c = float(INS_C(t) + rings(t, INS_C_BOUNCE, DUR_I))
    a = float(FAN(t))
    a_vis = a + 14.0 * np.sin(np.radians(a)) * (T_FAN0 < t < T_ROLL1)          # gravity: faster as the handles fall
    p.a = a_vis + float(rings(t, FAN_SLAPS, DUR_I))
    p.roll = float(ROLL(t))                         # the knife rolls over the hand; the hand keeps its grip frame
    dpos, drot = fan_pump(t, a)
    nudge(p, dpos + rings(t, INS_JOLT, DUR_I), drot + rings(t, INS_JOLT_ROT, DUR_I))
    # fingers open each time the bite handle sweeps past the palm (fan / rollover), the thumb flicks it on
    sweep = np.cos(np.radians(a_vis)) if (T_FAN0 < t < T_FAN2 or T_ROLL0 < t < T_ROLL1) else 1.0
    open_f = float(np.clip(-sweep, 0.0, 1.0))
    p.fingers_r = hands.adjust(hands.blend({"fist": 1.0 - 0.75 * open_f, "spread": 0.75 * open_f}),
                               thumb=(-18.0 * float(np.clip(sweep, 0, 1)) * (T_FAN0 < t < T_ROLL1), 0.0))
    toss(p, t)
    # left hand presents the blade
    w = ease("inout", t, 2.40, 2.62) * (1 - ease("inout", t, 3.00, 3.24))
    mid = p.knife_point(kr.BLADE_MID)
    present = (mid + V(0.020, -0.075, 0.030), armrig.palm_from_back((-0.2, -1.0, 0.25), (0.6, 0.2, -0.75), "L"),
               hands.blend({"relaxed": 0.6, "spread": 0.4}))
    rest_l = (kr.LEFT_REST[0], present[1], hands.blend({"relaxed": 1.0}))
    p.hand_l = armrig.blend_targets([(1 - w + 1e-6, rest_l), (w, present)])
    p.left_scale = 1.0 if 2.36 <= t < 3.30 else 0.0
    INS_CAM(p, t)
    return p


# ------------------------------------------------------------------------------------ sprint
# lowered running carry: the knife arm swings with the stride, the knife trailing the swing (its wrist lags), a hard
# drop on every foot strike; the left fist pumps big and lingers at the ends of its swing
DUR_S = 0.66
W_S = 2 * np.pi / DUR_S
RUN_C, Q_RUN = kr.reachable(V(0.135, -0.150, -0.290), Q((-0.22, 0.25, -0.94), (0.05, 1.0, 0.25)))
RUN_A = V(-0.012, 0.055, -0.050)
L_RUN_C = V(-0.135, -0.260, -0.165)
L_RUN_A = V(0.025, 0.160, -0.135)


def pump(x, k=1.6):
    """A swing that lingers a little at its ends (arms pumping): cos shaped towards a square wave, still smooth."""
    return np.tanh(k * x) / np.tanh(k)


def step_bounce(t):
    return periodic_bounce(t, DUR_S / 2, 0.12, 1.6)


def sprint_pose(t):
    p = kr.KnifePose()
    b = step_bounce(t)
    side = np.cos(W_S * (t - 0.04))
    swing = pump(np.cos(W_S * t))
    lag = np.cos(W_S * t - 0.55)                                  # the knife trails its arm's swing
    p.knife_t = RUN_C + RUN_A * swing + V(0.003 * side, -0.010 * b, 0.002 * b)
    p.knife_q = quat_mul(Q_RUN, quat_euler(2.0 * np.sin(W_S * t), -7.0 * lag - 2.5 * b, 3.0 * side))
    p.fingers_r = hands.adjust(hands.POSES["fist"], index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    p.hand_l = (L_RUN_C - L_RUN_A * pump(np.cos(W_S * t)) + V(0.0, -0.006 * b, 0.0), None, hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


# -------------------------------------------------------------------------------------- walk
# one loop = two steps (left foot contact at t = 0, right foot at half the loop), as the P9 loops
DUR_W = 1.00
W_W = 2 * np.pi / DUR_W


def bounce(t, period, lag=0.12, sharp=1.6):
    """0..1 impact curve per footstep, lowest just after foot contact."""
    return periodic_bounce(t, period, lag, sharp)


def walk_pose(t):
    """Knife at the ready, the arm countering the steps: a dip after each foot contact, sway and roll towards the
    stepping side with the knife a few hundredths behind the body; the grip tightens a little on every impact."""
    p = kr.KnifePose()
    b = bounce(t, DUR_W / 2, 0.10, 1.4)
    b_late = bounce(t - 0.04, DUR_W / 2, 0.10, 1.4)
    side = np.cos(W_W * (t - 0.05))
    p.knife_t = kr.REST_T + V(0.004 * side, 0.003 - 0.006 * b, 0.002 * b - 0.001 * np.sin(W_W * t))
    p.knife_q = quat_mul(kr.REST_Q, quat_euler(1.2 * np.cos(W_W * (t - 0.09)), 0.4 - 1.2 * b_late,
                                               1.6 * np.sin(W_W * (t - 0.04))))
    p.fingers_r = hands.adjust(hands.POSES["fist"], index=(-2.0 * b, 0), thumb=(-1.5 * b, 0))
    return p


# -------------------------------------------------------------------------------- tac_sprint
# high guard: knife raised beside the face, blade up, edge forward; the knife arm pumps short and hard (the blade
# whipping a little behind it), the left fist pumps big
DUR_T = 0.58
W_T = 2 * np.pi / DUR_T
TAC_C, Q_TAC = kr.reachable(V(0.100, -0.064, -0.272), Q((-0.22, 0.82, -0.53), (0.30, 0.45, 0.84)))
TAC_A = V(-0.006, 0.020, -0.016)


def tac_sprint_pose(t):
    p = kr.KnifePose()
    b = bounce(t, DUR_T / 2, 0.10, 1.8)
    side = np.cos(W_T * (t - 0.035))
    lag = np.cos(W_T * t - 0.6)
    p.knife_t = TAC_C + TAC_A * pump(np.cos(W_T * t)) + V(0.004 * side, 0.004 - 0.013 * b, 0.003 * b)
    p.knife_q = quat_mul(Q_TAC, quat_euler(2.0 * side, -3.5 * lag - 2.8 * b, 2.5 * np.sin(W_T * t)))
    p.fingers_r = hands.adjust(hands.POSES["fist"], index=(-3.0 * b, 0))
    p.hand_l = (L_RUN_C + V(0.0, 0.012 - 0.006 * b, 0.0) - L_RUN_A * 1.1 * pump(np.cos(W_T * t)), None,
                hands.blend({"fist": 1.0}))
    p.pole_l = V(-0.60, -0.70, 0.50)
    p.left_scale = 1.0
    return p


CLIPS = [
    IDLE,
    Clip("draw", DUR_D, draw_pose, events=[(0.0, "equip"), (T_FLICK, "flip"), (0.33, "flip"), (T_OPEN, "open")],
         fps=60, extra_times=(T_FLICK, 0.32, 0.44, T_OPEN, T_LATCH)),
    Clip("light_1", DUR_L, light1_pose, events=[(0.08, "swing")], fps=60,
         extra_times=(0.085, T_HIT, 0.185, 0.26)),
    Clip("light_2", DUR_L, light2_pose, events=[(0.08, "swing")], fps=60,
         extra_times=(0.085, T_HIT, 0.185, 0.26)),
    Clip("heavy", DUR_H, heavy_pose, events=[(0.30, "swing_heavy")], fps=60, extra_times=(0.295, T_EXT, 0.47)),
    Clip("inspect", DUR_I, inspect_pose,
         events=[(T_FAN0, "flip"), (T_FAN1, "flip"), (T_FAN2, "open"), (T_ROLL0, "flip"), (T_ROLL1, "open"),
                 (T_TOSS, "flip"), (T_CATCH, "catch")], fps=60,
         extra_times=(T_TOSS, T_CATCH, 2.36, 3.30, T_FAN2, T_ROLL1)),
    Clip("walk", DUR_W, walk_pose, fps=60, loop=True),
    Clip("sprint", DUR_S, sprint_pose, fps=60, loop=True),
    Clip("tac_sprint", DUR_T, tac_sprint_pose, fps=60, loop=True),
]
