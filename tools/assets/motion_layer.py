"""Hand-keyed / mocap-style detail layered over a code-authored one-shot clip (VIEWMODEL_SPEC section 31.6).

Our clips are C1 curves through sparse keys: they read smooth and clean, where an animator's keys or a mocap take carry
a dense layer of small, slightly irregular motion. `layered(clip, rig, seed)` returns the same clip (name, length,
events, frame rate) with that layer on top:

* overlapping action - the weapon follows its authored path through a damped spring a few frames late, with the
  follow-through and settle a real mass has; the gun hand's wrist keeps leading (its palm turns on the authored path,
  only its position stays on the grip);
* settle micro-motion - after every big move comes to rest, a small damped wobble along the way it came;
* anticipation - before a magazine pull or a rack (clip events), a small counter-move against the motion that follows;
* small asymmetric hand adjustments - the support wrist and every finger wander on their own seeded curves, more in
  holds than in fast moves, never mirrored;
* finger re-grips - in calm stretches the gun hand's lower fingers (and the support hand's, while it holds the
  weapon) loosen and clamp back, staggered index to pinky.

Deterministic: every random choice comes from `seed`. The layer fades in over the first frames and is exactly gone at
the clip's end, so a clip still starts and ends on its authored pose (section 31.3). Amplitudes stay in millimetres and
a few degrees, inside the eye-distance and framing checks; `gain` scales all of it."""
import numpy as np

import hands
from anim import Follow, drift, qexp, qlog, ring, smoothstep
from vmath import quat_conj, quat_mul, quat_rotate

RATE = 240.0                         # spring bake rate
ANTICIPATE = {"mag_release": 1.0, "mag_out": 0.7, "slide_back": 1.0, "bolt_back": 1.0, "slide_release": 0.6}
WRAP = ("middle", "ring", "pinky")    # the fingers that re-grip (the index stays on its guard / trigger)


def _soft(v, cap):
    n = float(np.linalg.norm(v))
    return v if n < 1e-12 else v * (cap * np.tanh(n / cap) / n)


class _Base:
    """The clip's weapon motion sampled once: offset (m) and turn (rad, gun-local rotation vector) from the rest."""

    def __init__(self, clip, rig):
        self.t0, self.q0 = np.asarray(rig.GUN_T, float), np.asarray(rig.GUN_Q, float)
        self.ts = np.arange(0.0, clip.duration + 1e-9, 1.0 / RATE)
        poses = [clip.pose(float(t)) for t in self.ts]
        self.off = np.array([p.gun_t - self.t0 for p in poses])
        rv = [qlog(quat_mul(quat_conj(self.q0), p.gun_q)) for p in poses]
        self.rot = np.array(rv)
        self.speed = np.linalg.norm(np.gradient(self.rot, axis=0), axis=1) * RATE           # rad/s
        self.lin = np.linalg.norm(np.gradient(self.off, axis=0), axis=1) * RATE             # m/s

    def at(self, arr, t):
        return np.array([np.interp(t, self.ts, arr[:, k]) for k in range(arr.shape[1])])

    def calm(self, t):
        """1 in a hold, 0 in a fast move (turn above ~40 deg/s or travel above ~0.25 m/s)."""
        s = max(np.interp(t, self.ts, self.speed) / np.radians(40.0), np.interp(t, self.ts, self.lin) / 0.25)
        return float(np.clip(1.0 - s, 0.0, 1.0))


def _settles(base, dur):
    """(time, direction of travel (off, rot), size) where a big move comes to rest."""
    out = []
    s = base.speed
    peak, peak_i = 0.0, 0
    for i in range(1, len(s)):
        if s[i] > peak:
            peak, peak_i = s[i], i
        if peak > np.radians(60.0) and s[i] < 0.25 * peak:
            j = max(peak_i - 3, 0)
            d_off = base.off[i] - base.off[j]
            d_rot = base.rot[i] - base.rot[j]
            t = float(base.ts[i])
            if 0.15 < t < dur - 0.3:
                out.append((t, d_off, d_rot, min(1.0, peak / np.radians(180.0))))
            peak = 0.0
    return out


def _regrips(base, clip, rng, dur):
    """Seeded re-grip times in calm stretches away from events and the clip's ends."""
    events = [t for t, _ in clip.events]
    spots = [t for t in np.arange(0.35, dur - 0.45, 0.05)
             if base.calm(t) > 0.8 and base.calm(t + 0.2) > 0.6 and all(abs(t - e) > 0.25 for e in events)]
    out = []
    for t in rng.permutation(spots):
        if all(abs(t - o) > 0.7 for o in out):
            out.append(float(t))
        if len(out) >= max(1, int(dur / 1.4)):
            break
    return sorted(out)


def _regrip_curve(t, t0, stagger):
    """Fingers loosen (+) and clamp back with a little overshoot (-): 0 outside [t0, t0 + 0.32]."""
    u = t - t0 - stagger
    if u <= 0.0 or u >= 0.30:
        return 0.0
    if u < 0.08:
        return smoothstep(0.0, 0.08, u)
    return 1.0 - 1.25 * smoothstep(0.08, 0.20, u) + 0.25 * smoothstep(0.20, 0.30, u)


def layered(clip, rig, seed, gain=1.0, lag=(7.5, 0.5), turn_lag=(6.0, 0.45), regrip_left=True):
    """`clip` with the motion layer on top (see the module doc). lag / turn_lag = (Hz, damping) of the springs the
    weapon's position / turn follow its authored path with."""
    dur = clip.duration
    base = _Base(clip, rig)
    rng = np.random.default_rng(seed)
    f_off = Follow(lambda t: base.at(base.off, t), dur, lag[0], lag[1], rate=RATE)
    f_rot = Follow(lambda t: base.at(base.rot, t), dur, turn_lag[0], turn_lag[1], rate=RATE)
    settles = _settles(base, dur)
    regrips = _regrips(base, clip, rng, dur)
    antic = []
    for t_ev, name in clip.events:
        k = ANTICIPATE.get(name)
        if k and 0.12 < t_ev < dur - 0.15:
            after_off = base.at(base.off, t_ev + 0.14) - base.at(base.off, t_ev)
            after_rot = base.at(base.rot, t_ev + 0.14) - base.at(base.rot, t_ev)
            antic.append((t_ev, -0.18 * k * after_off, -0.18 * k * after_rot))
    seeds = rng.integers(0, 1 << 30, size=16)
    stagger = {f: 0.025 * i + rng.uniform(0.0, 0.015) for i, f in enumerate(("index",) + WRAP)}
    finger_amp = {f: rng.uniform(1.5, 3.5) for f in hands.FINGERS}

    def envelope(t):
        return smoothstep(0.0, 0.10, t) * (1.0 - smoothstep(dur - 0.22, dur, t))

    def pose(t):
        p = clip.pose(t)
        env = envelope(t) * gain
        calm = base.calm(t)
        # overlapping action: the spring's lag (capped), anticipation bumps and settle wobbles
        d_off = _soft(f_off.lag(t), 0.006)
        d_rot = _soft(f_rot.lag(t), np.radians(4.0))
        for t_ev, a_off, a_rot in antic:
            w = np.sin(np.pi * np.clip((t - (t_ev - 0.13)) / 0.13, 0.0, 1.0)) ** 2
            d_off = d_off + w * _soft(a_off, 0.003)
            d_rot = d_rot + w * _soft(a_rot, np.radians(2.0))
        for t_s, s_off, s_rot, size in settles:
            d_off = d_off + ring(t, t_s, _soft(0.08 * size * s_off, 0.0015), 5.5, 0.35)
            d_rot = d_rot + ring(t, t_s, _soft(0.08 * size * s_rot, np.radians(1.2)), 5.0, 0.35)
        # handheld tremor, stronger in holds
        d_off = d_off + drift(t, np.full(3, 0.00035) * (0.4 + 0.6 * calm), int(seeds[0]), 2.2)
        d_rot = d_rot + drift(t, np.radians(np.full(3, 0.18)) * (0.4 + 0.6 * calm), int(seeds[1]), 1.9)
        old_t, old_q = p.gun_t.copy(), p.gun_q.copy()
        p.gun_t = old_t + env * d_off
        p.gun_q = quat_mul(old_q, qexp(env * d_rot))
        # the gun hand: on the grip of the lagging weapon, its palm still turning on the authored path (the wrist leads)
        if p.hand_r is None:
            q_auth = rig.Pose()
            q_auth.gun_t, q_auth.gun_q = old_t, old_q
            _, palm_lead, curls_r = q_auth.anchor("grip")
            pos, palm, _ = p.anchor("grip")
            p.hand_r = (pos, _nlerp(palm, palm_lead, 0.6 * env), curls_r)
        else:
            d = quat_mul(p.gun_q, quat_conj(old_q))
            pos, q, c = p.hand_r
            p.hand_r = (p.gun_t + quat_rotate(d, np.asarray(pos) - old_t), None if q is None else quat_mul(d, q), c)
        # a support hand placed in viewmodel space rides with the weapon's extra motion (it holds or works it)
        if p.hand_l is not None:
            d = quat_mul(p.gun_q, quat_conj(old_q))
            pos, q, c = p.hand_l
            off = drift(t, np.full(3, 0.0012) * calm, int(seeds[2]), 0.9) * env
            p.hand_l = (p.gun_t + quat_rotate(d, np.asarray(pos) - old_t) + off, None if q is None else quat_mul(d, q), c)
        # fingers: asymmetric wander and re-grips
        curls_r = p.fingers_r if p.fingers_r is not None else p.hand_r[2]
        adj_r = {}
        for i, f in enumerate(WRAP):
            g = sum(_regrip_curve(t, t0, stagger[f]) for t0 in regrips)
            wander = drift(t, finger_amp[f] * calm, int(seeds[3 + i]), 0.7)
            adj_r[f] = (env * (14.0 * g + wander), env * (10.0 * g + 0.5 * wander))
        p.fingers_r = hands.adjust(curls_r, **adj_r)
        left_on_gun = p.hand_l is None
        if regrip_left and left_on_gun:
            curls_l = p.fingers_l if p.fingers_l is not None else p.anchor("support")[2]
            adj_l = {}
            for i, f in enumerate(("thumb", "index") + WRAP):
                g = sum(_regrip_curve(t, t0 + 0.11, stagger.get(f, 0.0)) for t0 in regrips)
                wander = drift(t, finger_amp[f] * calm, int(seeds[8 + i]), 0.6)
                adj_l[f] = (env * (18.0 * g + wander), env * (12.0 * g + 0.5 * wander))
            p.fingers_l = hands.adjust(curls_l, **adj_l)
        return p

    out = type(clip)(clip.name, dur, pose, events=clip.events, fps=clip.fps, extra_times=clip.extra_times,
                     force_nodes=clip.force_nodes)
    for key, value in vars(clip).items():
        out.__dict__.setdefault(key, value)
    return out


def _nlerp(a, b, w):
    b = b if np.dot(a, b) >= 0 else -b
    q = (1 - w) * np.asarray(a, float) + w * np.asarray(b, float)
    return q / np.linalg.norm(q)
