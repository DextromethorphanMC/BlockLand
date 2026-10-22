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
