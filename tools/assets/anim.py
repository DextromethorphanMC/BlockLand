"""Keyframe tracks with easing, used to author clips before baking them to glTF LINEAR/STEP keys."""
import numpy as np


def _inout(u):
    return 4 * u ** 3 if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2


EASES = {
    "linear": lambda u: u,
    "in": lambda u: u ** 3,
    "out": lambda u: 1 - (1 - u) ** 3,
    "inout": _inout,
    "in2": lambda u: u * u,
    "out2": lambda u: 1 - (1 - u) ** 2,
    "in4": lambda u: u ** 4,
    "out4": lambda u: 1 - (1 - u) ** 4,
    "sine": lambda u: 0.5 - 0.5 * np.cos(np.pi * u),
    "outback": lambda u: 1 + 2.4 * (u - 1) ** 3 + 1.4 * (u - 1) ** 2,          # overshoot ~10%
    "inback": lambda u: 2.4 * u ** 3 - 1.4 * u ** 2,                            # anticipation
    "hold": lambda u: 0.0,
}


class Track:
    """Track((t, value), (t, value, ease), ...): ease names the curve INTO that key."""

    def __init__(self, *keys):
        ks = []
        for k in keys:
            t, v = k[0], np.asarray(k[1], dtype=float)
            ease = k[2] if len(k) > 2 else "inout"
            ks.append((float(t), v, ease))
        ks.sort(key=lambda k: k[0])
        self.keys = ks

    @staticmethod
    def const(v):
        return Track((0.0, v))

    def __call__(self, t):
        ks = self.keys
        if t <= ks[0][0]:
            return ks[0][1].copy()
        if t >= ks[-1][0]:
            return ks[-1][1].copy()
        for i in range(len(ks) - 1):
            t0, v0, _ = ks[i]
            t1, v1, ease = ks[i + 1]
            if t0 <= t < t1:
                u = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
                return v0 + (v1 - v0) * EASES[ease](u)
        return ks[-1][1].copy()

    def times(self):
        return [k[0] for k in self.keys]


def smoothstep(e0, e1, x):
    u = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return u * u * (3 - 2 * u)


def window(t, t0, t1, fade=0.08):
    """Smooth 0..1..0 weight: rises over [t0, t0+fade], falls over [t1-fade, t1]."""
    return smoothstep(t0, t0 + fade, t) * (1 - smoothstep(t1 - fade, t1, t))


def damped(t, t0, amp, freq=9.0, decay=7.0):
    """Follow-through wobble starting at t0 (0 before)."""
    if t < t0:
        return 0.0
    x = t - t0
    return amp * np.sin(2 * np.pi * freq * x) * np.exp(-decay * x)


def sample_times(duration, fps, extra=()):
    ts = list(np.arange(0.0, duration + 1e-9, 1.0 / fps))
    if abs(ts[-1] - duration) > 1e-6:
        ts.append(duration)
    ts += [t for t in extra if 0 <= t <= duration]
    ts = sorted(set(round(t, 6) for t in ts))
    out = [ts[0]]
    for t in ts[1:]:
        if t - out[-1] > 1e-4:
            out.append(t)
    return np.array(out)


# =============================================================================================================
# Motion toolkit v3 (VIEWMODEL_SPEC section 31). Track above eases every segment on its own, so with "inout" /
# "sine" keys the motion comes to a dead stop at every key - the mechanical look. Curve is C1 through its keys
# (velocity carries through a key unless the key says otherwise); Follow / ring / drift / arc add the secondary
# motion (lag, overshoot, recoil through the wrists, handheld drift, arcs) that sells weight.
# =============================================================================================================
def _steffen(d0, d1, h0, h1):
    """Monotone tangent (Steffen 1990) per component: no overshoot between keys, zero at extremes and holds."""
    p = (d0 * h1 + d1 * h0) / (h0 + h1)
    m = (np.sign(d0) + np.sign(d1)) * np.minimum(np.minimum(np.abs(d0), np.abs(d1)), 0.5 * np.abs(p))
    return np.where(d0 * d1 > 0, m, 0.0)


class Curve:
    """C1 Hermite spline through keys: Curve((t, value), (t, value, mode), ..., loop=False).

    mode = how the motion passes through that key:
      "auto"  (default) monotone tangent: carries the velocity through a key that lies between its neighbours,
              stops at extremes and holds (two equal keys), never overshoots between keys
      "flow"  Catmull-Rom tangent: sweeps through the key and may overshoot it a little (arcs, swings)
      "stop"  zero velocity: a turning point, a settle, a held pose
      "hit"   arrives at speed and stops dead: an impact (slide home, magazine seated, stab extension);
              ("hit", k) arrives at k x the incoming secant (default 2, like an "in2" ease)
      "snap"  leaves a standstill at speed: an explosive start after a held anticipation; ("snap", k)
      {"in": m, "out": m}   explicit tangents (units per second, scalars or vectors)
      an EASES name         the segment INTO this key uses that classic ease (Track semantics); the key then leaves
                            with the ease's own end slope, so a following curve segment joins it C1
    The first and last keys are stops unless loop=True (periodic: the last key must equal the first)."""

    def __init__(self, *keys, loop=False):
        ks = sorted(((float(k[0]), np.asarray(k[1], float), k[2] if len(k) > 2 else "auto") for k in keys),
                    key=lambda k: k[0])
        self.t = np.array([k[0] for k in ks])
        self.v = [k[1] for k in ks]
        self.mode = [k[2] for k in ks]
        self.loop = loop
        n = len(ks)
        zero = np.zeros_like(self.v[0])
        self.m_in, self.m_out = [zero.copy() for _ in range(n)], [zero.copy() for _ in range(n)]
        if n < 2:
            return
        h = np.diff(self.t)
        d = [(self.v[i + 1] - self.v[i]) / h[i] for i in range(n - 1)]
        for i in range(n):
            mode = self.mode[i]
            kind = mode[0] if isinstance(mode, tuple) else mode
            k = mode[1] if isinstance(mode, tuple) else None
            if 0 < i < n - 1:
                d0, d1, h0, h1 = d[i - 1], d[i], h[i - 1], h[i]
            elif loop:
                d0, d1, h0, h1 = d[-1], d[0], h[-1], h[0]
            else:
                d0 = d1 = None
            if isinstance(mode, dict):
                self.m_in[i] = np.asarray(mode.get("in", zero), float) * np.ones_like(zero)
                self.m_out[i] = np.asarray(mode.get("out", zero), float) * np.ones_like(zero)
            elif kind == "stop" or (isinstance(kind, str) and kind in EASES):
                pass
            elif kind == "hit":
                if i > 0:
                    self.m_in[i] = (2.0 if k is None else k) * d[i - 1]
            elif kind == "snap":
                if i < n - 1:
                    self.m_out[i] = (2.0 if k is None else k) * d[i]
            elif d0 is not None:
                m = (d0 * h1 + d1 * h0) / (h0 + h1) if kind == "flow" else _steffen(d0, d1, h0, h1)
                self.m_in[i] = self.m_out[i] = m
        # a classic-eased segment hands its end slope on to the key's outgoing side (C1 into a curve segment)
        for i in range(1, n):
            mode = self.mode[i]
            if isinstance(mode, str) and mode in EASES and i < n - 1:
                slope = (EASES[mode](1.0) - EASES[mode](1.0 - 1e-4)) / 1e-4
                self.m_out[i] = slope * d[i - 1]
        if loop:                                         # the closing key shares the first key's tangents
            self.m_in[-1], self.m_out[-1] = self.m_in[0], self.m_out[0]

    def __call__(self, t):
        ts = self.t
        if self.loop and len(ts) > 1:
            t = ts[0] + (t - ts[0]) % (ts[-1] - ts[0])
        if t <= ts[0]:
            return self.v[0].copy()
        if t >= ts[-1]:
            return self.v[-1].copy()
        i = int(np.searchsorted(ts, t, side="right") - 1)
        h = ts[i + 1] - ts[i]
        s = (t - ts[i]) / h
        mode = self.mode[i + 1]
        if isinstance(mode, str) and mode in EASES:
            return self.v[i] + (self.v[i + 1] - self.v[i]) * EASES[mode](s)
        s2, s3 = s * s, s * s * s
        return ((2 * s3 - 3 * s2 + 1) * self.v[i] + (s3 - 2 * s2 + s) * h * self.m_out[i]
                + (-2 * s3 + 3 * s2) * self.v[i + 1] + (s3 - s2) * h * self.m_in[i + 1])

    def vel(self, t, dt=1e-4):
        return (self(t + dt) - self(t - dt)) / (2 * dt)

    def times(self):
        return list(self.t)

    def shifted(self, dt):
        """The same curve dt seconds later (overlapping action: small parts follow the big masses)."""
        return lambda t: self(t - dt)


def qlog(q):
    """Rotation vector (radians) of a quaternion."""
    q = np.asarray(q, float)
    q = q if q[3] >= 0 else -q
    s = np.linalg.norm(q[:3])
    return q[:3] * 2 if s < 1e-9 else q[:3] / s * 2 * np.arctan2(s, q[3])


def qexp(rv):
    """Quaternion of a rotation vector (radians)."""
    rv = np.asarray(rv, float)
    ang = np.linalg.norm(rv)
    if ang < 1e-12:
        return np.array([0.0, 0.0, 0.0, 1.0])
    a = rv / ang * np.sin(ang / 2)
    return np.array([a[0], a[1], a[2], np.cos(ang / 2)])


class QuatCurve:
    """Orientation through key quaternions: (t, quat[, "auto" | "flow" | "stop"]). Hermite in the rotation-vector
    space of each segment's start key (like knife_clips.Motion), so large turns (inspect flips) stay smooth and never
    pass through a singular frame; "auto" stops where the turn reverses, the first and last keys are stops."""

    def __init__(self, *keys):
        from vmath import quat_conj, quat_mul
        qs = [np.asarray(k[1], float) / np.linalg.norm(k[1]) for k in keys]
        for i in range(1, len(qs)):
            if np.dot(qs[i], qs[i - 1]) < 0:
                qs[i] = -qs[i]
        self.t = np.array([float(k[0]) for k in keys])
        self.q = qs
        self.d = [qlog(quat_mul(quat_conj(qs[i]), qs[i + 1])) for i in range(len(qs) - 1)]
        self.m = []
        n = len(keys)
        for i, k in enumerate(keys):
            mode = k[2] if len(k) > 2 else "auto"
            if mode == "stop" or i in (0, n - 1):
                self.m.append(np.zeros(3))
                continue
            a, b = self.d[i - 1] / (self.t[i] - self.t[i - 1]), self.d[i] / (self.t[i + 1] - self.t[i])
            self.m.append(np.zeros(3) if mode == "auto" and np.dot(a, b) <= 0 else 0.5 * (a + b))

    def __call__(self, t):
        from vmath import quat_mul, quat_rotate
        ts = self.t
        if t <= ts[0]:
            return self.q[0].copy()
        if t >= ts[-1]:
            return self.q[-1].copy()
        i = int(np.searchsorted(ts, t, side="right") - 1)
        h = ts[i + 1] - ts[i]
        s = (t - ts[i]) / h
        h10, h01, h11 = s ** 3 - 2 * s ** 2 + s, -2 * s ** 3 + 3 * s ** 2, s ** 3 - s ** 2
        d = self.d[i]
        m1 = quat_rotate(qexp(d), self.m[i + 1])
        return quat_mul(self.q[i], qexp(h10 * h * self.m[i] + h01 * d + h11 * h * m1))


class Follow:
    """A damped spring (natural frequency `freq` Hz, damping ratio `zeta`) following driver(t), baked from t = 0
    (starting at rest on the driver) at `rate` steps/s; loop=True runs several periods and keeps the periodic steady
    state (locomotion loops). Call it for the follower's value, .lag(t) for follower - driver: add gain x lag to a
    part to make it trail, overshoot and settle (fingers, a magazine in the hand, the weapon's rotation dragging
    behind its translation, a camera reacting to the hands). One-shot clips: the lag is not zero at the very end
    unless the driver has been still for a while - fade it with window() when the clip must end exactly at rest."""

    def __init__(self, driver, duration, freq=6.0, zeta=0.5, rate=960, loop=False):
        self.duration, self.loop = float(duration), loop
        n = max(2, int(np.ceil(self.duration * rate)) + 1)
        self.ts = np.linspace(0.0, self.duration, n)
        w = 2 * np.pi * freq
        target = np.array([np.asarray(driver(float(t)), float) for t in self.ts])
        x, v = target[0].copy(), np.zeros_like(target[0])
        h = self.ts[1] - self.ts[0]
        out = np.empty_like(target)
        for _ in range(4 if loop else 1):              # loops: converge onto the periodic response
            for i in range(n):
                v = v + (w * w * (target[i] - x) - 2 * zeta * w * v) * h     # semi-implicit Euler
                x = x + v * h
                out[i] = x
        self.xs, self.target = out, target

    def _at(self, arr, t):
        t = float(t) % self.duration if self.loop else min(max(float(t), 0.0), self.duration)
        f = t / self.duration * (len(self.ts) - 1)
        i = min(int(f), len(self.ts) - 2)
        u = f - i
        return arr[i] * (1 - u) + arr[i + 1] * u

    def __call__(self, t):
        return self._at(self.xs, t)

    def lag(self, t):
        return self._at(self.xs - self.target, t)


def ring(t, t0, amp, freq=8.0, zeta=0.35):
    """Impulse response of a damped spring struck at t0 (0 before): reaches `amp` (scalar or vector) at its first
    peak, a quarter period later, then rings out. Recoil through the wrists, a magazine seating, a slide slamming
    home, a catch."""
    amp = np.asarray(amp, float)
    if t < t0:
        return np.zeros_like(amp)
    w = 2 * np.pi * freq
    wd = w * np.sqrt(max(1e-6, 1 - zeta * zeta))
    tp = np.arctan2(wd, zeta * w) / wd
    peak = np.exp(-zeta * w * tp) * np.sin(wd * tp)
    x = t - t0
    return amp * (np.exp(-zeta * w * x) * np.sin(wd * x) / peak)


def drift(t, amp, seed=0, freq=0.6, start=None, end=None, fade=0.25):
    """Handheld drift: smooth low-frequency wander (three incommensurate sines per component, phases from `seed`),
    so a held pose is a moving hold instead of a freeze. With start / end it fades in and out over `fade` seconds
    (one-shot clips must start and end on the rest pose)."""
    amp = np.asarray(amp, float)
    rng = np.random.default_rng(seed)
    out = np.zeros_like(amp)
    for f, a in ((1.0, 0.6), (1.71, 0.3), (2.93, 0.1)):
        out = out + a * np.sin(2 * np.pi * freq * f * t + rng.uniform(0, 2 * np.pi, size=amp.shape))
    if start is not None and end is not None:
        out = out * window(t, start, end, fade)
    return amp * out


def arc(u, bulge):
    """0 at u = 0 and u = 1, `bulge` at u = 0.5 (vector or scalar): bows a straight move into an arc."""
    u = float(np.clip(u, 0.0, 1.0))
    return np.asarray(bulge, float) * 4 * u * (1 - u)
