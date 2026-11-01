"""Equipment clips (VIEWMODEL_SPEC sections 31 and 36), authored with the knives' tools: the held frame splined
through key poses (knife_clips.Motion: flow / stop / hit / snap / whip keys), impact rings, moving holds, a camera
that coils with the wind-ups and leans into the throws, and springs (anim.Follow) for what trails (the molotov's
flame, the left hand pumping).

Grenades (frag, flashbang, smoke): `pull` — the grenade comes up to the chest, turned so the ring faces the left
hand; the left index finger hooks the ring and pulls it straight out (the right hand braces against the pull), the
pin leaves with the left hand; frag and flashbang let the spoon go at the end of the pull (cooking: their fuze runs
from here), the smoke grenade keeps it pressed until it leaves the hand. `hold` — cocked by the right shoulder, the
left arm pointing the throw. `throw` — a last wind-back, the whip over the shoulder (speed peak at the release, the
body turning into it), the follow-through down across the body. `lob` — the arm drops and swings through underhand.
Molotov: `pull` lights the rag with a petrol lighter in the left hand. Throwing knife: a short cocked flick. Injector:
`use` — cap off, raised, stabbed into the thigh, pressed and held, pulled out and tossed away. Flashlight: `use` — the
thumb presses the tail switch (`click`: it switches) with a small tick of the wrist.

One clip set per item (clips(key)); event times are the gameplay timing of equipment.json (`releaseAt`, `injectAt`,
`pullTime`, `useTime`, `drawTime`: TIMES below, copied into the data by gen_equipment's caller)."""
import numpy as np

import equip_model as em
import equip_rig as rig
import hands
from anim import Follow, drift, ring, smoothstep, window
from knife_clips import CamCurve, Motion, Spline, ease, periodic_bounce, pump, rings
from p9_clips import Clip
from vmath import norm, quat_axis_angle, quat_conj, quat_euler, quat_mul, quat_rotate, quat_slerp

V = lambda *a: np.array(a, float)
GRENADES = ("frag_grenade", "flashbang", "smoke_grenade")
COOKS = ("frag_grenade", "flashbang")           # their spoon flies at the end of the pull (the fuze runs from there)

# as-built gameplay timing (seconds) -> equipment.json
T_RELEASE = 0.20             # throw and lob: the item leaves the hand
DUR_THROW = 0.62
T_PIN, T_SPOON, DUR_PULL = 0.24, 0.40, 0.50
T_LIGHT, T_CATCH_FIRE, DUR_LIGHT = 0.30, 0.40, 0.70
T_KNIFE_RELEASE, DUR_KNIFE_THROW = 0.16, 0.48
T_CAP, T_STAB, T_INJECT, T_DISCARD, DUR_USE = 0.28, 0.62, 0.82, 1.20, 1.45
T_CLICK, DUR_CLICK = 0.12, 0.36
DUR_DRAW = {"throwing_knife": 0.36, "adrenaline": 0.45, "molotov": 0.52, "flashlight": 0.40}
DUR_DRAW_DEFAULT = 0.48
FLY_TIME = 0.07              # the thrown copy stays drawn this long after the release (the entity takes over)


def timing(key):
    """equipment.json fields this clip set fixes."""
    out = {"drawTime": DUR_DRAW.get(key, DUR_DRAW_DEFAULT)}
    if key in GRENADES or key == "molotov":
        out["pullTime"] = DUR_LIGHT if key == "molotov" else DUR_PULL
        out["throw.releaseAt"] = T_RELEASE
        out["throw.recover"] = round(DUR_THROW - T_RELEASE, 3)
    elif key == "throwing_knife":
        out["throw.releaseAt"] = T_KNIFE_RELEASE
        out["throw.recover"] = round(DUR_KNIFE_THROW - T_KNIFE_RELEASE, 3)
    elif key == "flashlight":
        out["useTime"] = DUR_CLICK
        out["injectAt"] = T_CLICK
    else:
        out["useTime"] = DUR_USE
        out["injectAt"] = T_INJECT
    return out


# =========================================================================================== helpers
def turned(q, deg):
    return quat_mul(q, quat_euler(*deg)) if np.any(np.abs(deg) > 1e-9) else q


def motion(it, *keys):
    """Held-frame motion through key poses (t, pos, quat, mode); every key made reachable for the wrist (so the
    splines run between poses the hand can hold), the rest pose kept as the idle has it."""
    out = []
    for t, pos, q, mode in keys:
        if np.allclose(pos, it.rest_t) and abs(float(np.dot(q, it.rest_q))) > 1 - 1e-9:
            out.append((t, it.rest_t.copy(), it.rest_q.copy(), mode))
            continue
        p2, q2 = rig.reachable(it, pos, q)
        out.append((t, p2, q2, mode))
    return Motion(*out)


def apply(m, p, t, dpos=None, drot=None):
    p.hold_t = m.pos(t)
    p.hold_q = m.quat(t)
    if dpos is not None:
        p.hold_t = p.hold_t + np.asarray(dpos, float)
    if drot is not None:
        p.hold_q = turned(p.hold_q, np.asarray(drot, float))


def velocity(m, t, dt=1e-3):
    """Linear velocity (m/s) and angular velocity (rad/s, world) of a Motion's frame at t."""
    v = (m.pos(t + dt) - m.pos(t - dt)) / (2 * dt)
    q0, q1 = m.quat(t - dt), m.quat(t + dt)
    from anim import qlog
    w = qlog(quat_mul(q1, quat_conj(q0))) / (2 * dt)
    return v, w


def flight(m, t0, t, extra_v=(0, 0, 0), spin=None, gravity=4.0):
    """Frame of an item released from Motion m at t0, flying on with the hand's speed (plus extra_v) under a little
    gravity (viewmodel space is not to scale with the world; this only carries it out of the picture)."""
    p0, q0 = m.pos(t0), m.quat(t0)
    v, w = velocity(m, t0)
    v = v + np.asarray(extra_v, float)
    if spin is not None:
        w = np.asarray(spin, float)
    dt = max(0.0, t - t0)
    pos = p0 + v * dt + V(0, -0.5 * gravity * dt * dt, 0)
    from anim import qexp
    return pos, quat_mul(qexp(w * dt), q0)


def lever_pop(p, t, t0, it):
    """The spoon springs off its hinge at t0: it flips out on the striker spring, then tumbles away (item space)."""
    if t < t0:
        return
    hinge = it.part_rest["lever"]["translation"]
    dt = t - t0
    flip = 80.0 * smoothstep(0.0, 0.028, dt) + 1400.0 * max(0.0, dt - 0.02)
    q = quat_mul(quat_axis_angle((0, 0, 1), flip), quat_axis_angle((1, 0, 0), 300.0 * max(0.0, dt - 0.02)))
    pos = hinge + V(0.9, 0.7, 0.15) * max(0.0, dt - 0.012) + V(0, -2.0, 0) * max(0.0, dt - 0.012) ** 2
    p.parts["lever"] = {"translation": pos, "rotation": q, "scale": np.ones(3) if dt < 0.085 else np.zeros(3)}


def hide(p, *names):
    for n in names:
        p.parts[n] = dict(p.parts.get(n, {}), scale=np.zeros(3))


def show(p, *names):
    for n in names:
        p.parts[n] = dict(p.parts.get(n, {}), scale=np.ones(3))


def blend_curls(a, b, w):
    return {f: (1 - w) * np.asarray(a[f], float) + w * np.asarray(b[f], float) for f in hands.FINGERS}


def squeeze(curls, t, t0, depth=-6.0, width=0.06):
    k = depth * np.exp(-((t - t0) / width) ** 2)
    return hands.adjust(curls, index=(k, 0.5 * k), middle=(k, 0.5 * k), ring=(k, 0), pinky=(k, 0), thumb=(0.6 * k, 0))


def opened(it, w):
    """The fist opened by w (0 = the grip, 1 = spread): the release."""
    return blend_curls(it.curls, hands.blend({"relaxed": 0.6, "spread": 0.4}), w)


def left_palm(fwd, normal):
    """Left palm quat with the fingers along fwd and the palm facing `normal`."""
    f = norm(fwd)
    n = norm(np.asarray(normal, float) - f * np.dot(normal, f))
    return hands.palm_frame(f, np.cross(n, f), "L")


HOOK = hands.adjust(hands.POSES["fist"], index=(52.0, 22.0), thumb=(20.0, 10.0))     # index crooked, the rest a fist


def hook_target(ring_c, q_l):
    """Left hand target with the index finger's middle joint in the ring's centre."""
    off = rig.finger_points("L", np.zeros(3), q_l, HOOK, "index")[1]
    return ring_c - off, q_l, HOOK


def left_off(p):
    p.hand_l = (rig.LEFT_REST[0], None, hands.blend({"relaxed": 1.0}))


def stride(it, p, t, dur, amp=1.0, fast=False):
    """Shared locomotion carry: the arm swinging with the stride, the item trailing it, a drop on every foot strike;
    sprint (fast) lowers it and pumps the left fist."""
    w = 2 * np.pi / dur
    b = periodic_bounce(t, dur / 2, 0.12, 1.6 if fast else 1.4)
    side = np.cos(w * (t - 0.04))
    if fast:
        c = it.rest_t + V(0.022, -0.040, 0.020)
        p.hold_t = c + V(-0.008, 0.034, -0.030) * pump(np.cos(w * t)) * amp + V(0.003 * side, -0.010 * b, 0.002 * b)
        p.hold_q = quat_mul(turned(it.rest_q, V(6.0, -14.0, -8.0)),
                            quat_euler(2.0 * np.sin(w * t), -6.0 * np.cos(w * t - 0.55) - 2.5 * b, 3.0 * side))
        p.hand_l = (V(-0.135, -0.260, -0.165) - V(0.025, 0.160, -0.135) * pump(np.cos(w * t)) + V(0, -0.006 * b, 0),
                    None, hands.blend({"fist": 1.0}))
        p.pole_l = V(-0.60, -0.70, 0.50)
        p.left_scale = 1.0
    else:
        b_late = periodic_bounce(t - 0.04, dur / 2, 0.10, 1.4)
        p.hold_t = it.rest_t + V(0.004 * side, 0.003 - 0.006 * b, 0.002 * b - 0.001 * np.sin(w * t))
        p.hold_q = quat_mul(it.rest_q, quat_euler(1.2 * np.cos(w * (t - 0.09)), 0.4 - 1.2 * b_late,
                                                  1.6 * np.sin(w * (t - 0.04))))
    p.fingers_r = hands.adjust(it.curls, middle=(-2.0 * b, 0), thumb=(-1.5 * b, 0))


# =========================================================================================== shared clips
def idle_clip(it, extra=None):
    def pose(t):
        p = rig.EquipPose(it)
        w = 2 * np.pi * t / 2.0
        p.hold_t = it.rest_t + V(0.0004 * np.sin(w + 0.5), 0.0006 * np.sin(w), 0.0003 * np.sin(w + 1.2))
        p.hold_q = quat_mul(it.rest_q, quat_euler(0.3 * np.sin(w + 0.4), 0.35 * np.sin(w + 1.0), 0.4 * np.sin(w)))
        p.fingers_r = hands.adjust(it.curls, index=(1.2 * np.sin(w + 2.0), 0.6 * np.sin(w + 2.4)),
                                   thumb=(1.5 * np.sin(w + 0.8), 0.8 * np.sin(w + 1.3)))
        if extra:
            extra(p, t)
        return p
    return Clip("idle", 2.0, pose, loop=True)


def walk_clip(it, extra=None):
    def pose(t):
        p = rig.EquipPose(it)
        stride(it, p, t, 1.0)
        if extra:
            extra(p, t)
        return p
    return Clip("walk", 1.0, pose, fps=60, loop=True)


def sprint_clip(it, extra=None):
    def pose(t):
        p = rig.EquipPose(it)
        stride(it, p, t, 0.66, fast=True)
        if extra:
            extra(p, t)
        return p
    return Clip("sprint", 0.66, pose, fps=60, loop=True)


def draw_clip(it, dur, extra=None, spin=0.0):
    """Up from the bottom right on an arc: it arrives a hair past the guard (the hand still moving), is caught by the
    wrist and settles; the fist re-grips on the arrival; `spin` (deg) turns it in the hand on the way (a flourish)."""
    t_up = 0.62 * dur
    m = motion(it,
               (0.00, it.rest_t + V(0.090, -0.250, 0.070), turned(it.rest_q, V(-25.0, -35.0, 20.0)), "stop"),
               (0.30 * dur, it.rest_t + V(0.045, -0.090, 0.030), turned(it.rest_q, V(-10.0, -18.0, 8.0)), "flow"),
               (t_up, it.rest_t + V(-0.004, 0.006, -0.004), turned(it.rest_q, V(2.0, 3.0, -2.0)), ("flow", 0.7)),
               (0.84 * dur, it.rest_t + V(0.001, -0.002, 0.001), turned(it.rest_q, V(-0.5, -0.8, 0.5)), "flow"),
               (dur, it.rest_t, it.rest_q, "stop"))
    cam = CamCurve(dur, (0.0, V(0, 0, 0)), (0.30 * dur, V(-0.35, -0.15, 0.6)), (t_up, V(0.25, 0.1, -0.3)),
                   (dur, V(0, 0, 0)), accents=[(t_up, V(-0.3, 0.0, 0.2), 7.0, 0.45)])
    roll = Spline((0.0, V(spin), "stop"), (0.06, V(spin), "stop"), (t_up, V(0.0), ("hit", 1.2)),
                  (dur, V(0.0), "stop")) if spin else None

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        p.fingers_r = squeeze(it.curls, t, t_up + 0.02, -7.0, 0.05)
        if roll:                                      # it turns end over end in the loosened fist, caught on arrival
            p.spin = float(roll(t)[0]) + float(rings(t, [(t_up, 7.0, 8.0, 0.45)], dur))
            loose = ease("inout", t, 0.04, 0.10) * (1 - ease("in2", t, t_up - 0.05, t_up))
            p.fingers_r = blend_curls(p.fingers_r, opened(it, 0.7), loose)
        cam(p, t)
        if extra:
            extra(p, t)
        return p
    return Clip("draw", dur, pose, events=[(0.0, "equip")], fps=120 if spin else 60, extra_times=(t_up,))


def inspect_clip(it, dur, show_q, extra=None, left=None, pos3=None):
    """Raised to the eye and turned: one side, the other, the top (the left hand checks what it checks: `left(p, t)`),
    back down with a settle."""
    up = it.rest_t + V(-0.052, 0.035, 0.028)
    p3 = up if pos3 is None else pos3
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.30, up + V(0.004, -0.004, 0.0), turned(show_q[0], V(-3.0, 2.0, 0.0)), ("flow", 0.8)),
               (0.42, up, show_q[0], "flow"),
               (0.95, up + V(0.002, 0.002, -0.002), turned(show_q[0], V(2.0, -2.0, 1.0)), "flow"),
               (1.30, up + V(-0.004, 0.000, 0.000), show_q[1], "flow"),
               (1.85, up + V(-0.002, 0.003, 0.002), turned(show_q[1], V(-2.0, 1.5, 0.0)), "flow"),
               (2.20, p3 + V(0.002, 0.008, 0.004), show_q[2], "flow"),
               (dur - 0.42, p3 + V(0.004, 0.006, 0.004), turned(show_q[2], V(2.0, 0.0, -2.0)), "flow"),
               (dur - 0.14, it.rest_t + V(-0.002, 0.004, -0.002), turned(it.rest_q, V(0.0, 1.5, 0.0)), ("flow", 0.6)),
               (dur, it.rest_t, it.rest_q, "stop"))
    cam = CamCurve(dur, (0.0, V(0, 0, 0)), (0.42, V(0.5, 0.0, 0.9)), (1.30, V(0.3, 0.2, -0.8)),
                   (2.20, V(0.6, -0.2, 0.4)), (dur - 0.14, V(-0.1, 0.0, 0.1)), (dur, V(0, 0, 0)))

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t, dpos=drift(t, V(0.0012, 0.0010, 0.0008), seed=3, start=0.3, end=dur - 0.2),
              drot=drift(t, V(0.8, 0.8, 0.6), seed=4, start=0.3, end=dur - 0.2))
        p.fingers_r = squeeze(it.curls, t, 0.42, -3.0, 0.08)
        if left:
            left(p, t)
        cam(p, t)
        if extra:
            extra(p, t)
        return p
    return Clip("inspect", dur, pose, events=[], fps=60)


# ==================================================================================== the cocked hold
HOLD_T = V(0.132, 0.022, -0.255)


def hold_q(it):
    """Cocked: the top turned back over the wrist and out to the right, the fist by the shoulder (the long bottle
    less, and held a little lower and further out: its neck must stay in the picture)."""
    if it.key == "molotov":
        return turned(it.rest_q, V(-14.0, 26.0, -10.0))
    return turned(it.rest_q, V(-30.0, 38.0, -18.0))


def hold_t(it):
    """The longer the item, the further out it is held (its top must stay in the picture)."""
    return {"molotov": V(0.150, -0.030, -0.330), "flashbang": V(0.138, 0.012, -0.290),
            "smoke_grenade": V(0.140, 0.008, -0.295)}.get(it.key, HOLD_T)


LEFT_POINT = V(-0.125, -0.135, -0.400)
Q_LEFT_POINT = None


def left_point_q():
    return left_palm((0.30, 0.10, -1.0), (0.55, -0.80, 0.0))


def hold_left(p, t, period=1.2, amp=1.0):
    """The left arm points the throw: the open hand forward, breathing with the body."""
    w = 2 * np.pi * t / period
    pos = LEFT_POINT + amp * V(0.002 * np.sin(w + 0.7), 0.003 * np.sin(w), 0.0015 * np.sin(w + 2.0))
    p.hand_l = (pos, left_point_q(), hands.blend({"relaxed": 0.55, "spread": 0.45}))
    p.left_scale = 1.0


def hold_clip(it, extra=None):
    hq = rig.reachable(it, hold_t(it), hold_q(it))

    def pose(t):
        p = rig.EquipPose(it)
        w = 2 * np.pi * t / 1.2
        p.hold_t = hq[0] + V(0.0012 * np.sin(w + 0.4), 0.0018 * np.sin(w), 0.0010 * np.sin(w + 1.6))
        tremble = V(0.25 * np.sin(2 * np.pi * 5 * t / 1.2 * 1.0 + 0.3), 0.2 * np.sin(2 * np.pi * 7 * t / 1.2), 0.0)
        p.hold_q = quat_mul(hq[1], quat_euler(0.6 * np.sin(w + 1.0) + tremble[0], 0.8 * np.sin(w) + tremble[1],
                                              0.5 * np.sin(w + 2.0)))
        p.fingers_r = hands.adjust(it.curls, index=(-4.0, -2.0), middle=(-4.0, -2.0), ring=(-4.0, 0.0),
                                   pinky=(-4.0, 0.0))            # a tight fist: the spoon is held down
        hold_left(p, t)
        hide(p, "pin")
        if it.key in COOKS:
            hide(p, "lever")
        if extra:
            extra(p, t)
        return p
    return Clip("hold", 1.2, pose, fps=60, loop=True)


# ==================================================================================== the throws
def throw_clip(it, extra=None, keep_lever=False):
    """Overhand: the last wind-back (0.08), the whip over the shoulder to the release (speed peak there, the body
    turning in), the follow-through down across the body, the arm out of the picture."""
    hq = rig.reachable(it, hold_t(it), hold_q(it))
    m = motion(it,
               (0.00, hq[0], hq[1], "stop"),
               (0.08, hq[0] + V(0.020, 0.012, 0.032), turned(hq[1], V(-6.0, -25.0, -6.0)), "stop"),
               (T_RELEASE, V(0.092, 0.050, -0.360), turned(it.rest_q, V(10.0, 30.0, 12.0)), ("whip", 1.0)),
               (0.32, V(-0.030, -0.170, -0.400), turned(it.rest_q, V(30.0, 75.0, 30.0)), "flow"),
               (0.46, V(0.040, -0.400, -0.300), turned(it.rest_q, V(20.0, 80.0, 20.0)), "flow"),
               (DUR_THROW, V(0.110, -0.470, -0.200), turned(it.rest_q, V(-10.0, -30.0, 15.0)), "stop"))
    shoulder = Spline((0.00, V(0, 0, 0), "stop"), (0.08, V(0.006, -0.004, 0.030), "stop"),
                      (T_RELEASE, V(-0.030, 0.012, -0.060), "flow"), (0.36, V(-0.045, -0.010, -0.050), "stop"),
                      (DUR_THROW, V(0, 0, 0), "stop"))
    lhand = Spline((0.00, LEFT_POINT, "stop"), (0.08, LEFT_POINT + V(0.010, 0.010, -0.010), "stop"),
                   (T_RELEASE, LEFT_POINT + V(-0.040, -0.090, 0.150), "flow"),
                   (0.40, LEFT_POINT + V(-0.040, -0.300, 0.260), "stop"))
    cam = CamCurve(DUR_THROW, (0.0, V(0, 0, 0)), (0.08, V(1.0, -0.6, 1.4)), (T_RELEASE + 0.02, V(-1.8, 0.9, -2.6)),
                   (0.36, V(-1.0, 1.0, -1.6)), (DUR_THROW, V(0, 0, 0)),
                   accents=[(T_RELEASE, V(-0.6, 0.2, -0.8), 6.0, 0.45)])

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        p.shoulder_r = shoulder(t)
        if t >= T_RELEASE:
            p.fly = flight(m, T_RELEASE, t, extra_v=V(0.0, 0.6, -2.0), spin=V(-14.0, 0.0, 2.0))
            p.visible = t < T_RELEASE + FLY_TIME
        p.fingers_r = blend_curls(squeeze(it.curls, t, 0.10, -5.0, 0.05), opened(it, 1.0),
                                  ease("out2", t, T_RELEASE - 0.01, T_RELEASE + 0.06))
        lw = smoothstep(0.30, 0.42, t)
        p.hand_l = (lhand(t), left_point_q(), hands.blend({"relaxed": 0.55 + 0.45 * lw, "spread": 0.45 * (1 - lw)}))
        p.left_scale = 1.0 if t < 0.42 else 0.0
        hide(p, "pin")
        if it.key in COOKS:
            hide(p, "lever")
        elif "lever" in it.moving and not keep_lever:
            lever_pop(p, t, T_RELEASE, it)
        cam(p, t)
        if extra:
            extra(p, t, m)
        return p
    return Clip("throw", DUR_THROW, pose, events=[(T_RELEASE, "release")] +
                ([(T_RELEASE, "spoon")] if it.key in GRENADES and it.key not in COOKS else []),
                fps=120, extra_times=(0.08, T_RELEASE, T_RELEASE + FLY_TIME))


def lob_clip(it, extra=None):
    """Underhand: the arm drops back past the hip, swings through low and lets go in front at chest height, palm up,
    following through upwards; then down out of the picture."""
    hq = rig.reachable(it, hold_t(it), hold_q(it))
    low_q = turned(it.rest_q, V(10.0, -50.0, 15.0))
    m = motion(it,
               (0.00, hq[0], hq[1], "stop"),
               (0.10, V(0.175, -0.250, -0.170), low_q, "flow"),
               (T_RELEASE, V(0.075, -0.090, -0.360), turned(it.rest_q, V(-15.0, -15.0, 25.0)), ("whip", 0.9)),
               (0.32, V(0.060, -0.050, -0.400), turned(it.rest_q, V(-20.0, 10.0, 35.0)), "stop"),
               (0.48, V(0.090, -0.300, -0.330), turned(it.rest_q, V(-10.0, 0.0, 10.0)), "flow"),
               (DUR_THROW, V(0.110, -0.470, -0.200), turned(it.rest_q, V(-10.0, -30.0, 15.0)), "stop"))
    shoulder = Spline((0.00, V(0, 0, 0), "stop"), (0.10, V(0.004, -0.010, 0.020), "flow"),
                      (T_RELEASE, V(-0.012, 0.000, -0.040), "flow"), (0.34, V(-0.016, 0.010, -0.030), "stop"),
                      (DUR_THROW, V(0, 0, 0), "stop"))
    lhand = Spline((0.00, LEFT_POINT, "stop"), (0.12, LEFT_POINT + V(-0.020, -0.120, 0.080), "flow"),
                   (0.30, LEFT_POINT + V(-0.040, -0.300, 0.200), "stop"))
    cam = CamCurve(DUR_THROW, (0.0, V(0, 0, 0)), (0.10, V(-0.6, -0.3, 0.6)), (T_RELEASE + 0.03, V(0.8, 0.4, -0.9)),
                   (0.36, V(0.4, 0.3, -0.5)), (DUR_THROW, V(0, 0, 0)),
                   accents=[(T_RELEASE, V(0.3, 0.1, -0.4), 6.0, 0.5)])

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        p.shoulder_r = shoulder(t)
        if t >= T_RELEASE:
            p.fly = flight(m, T_RELEASE, t, extra_v=V(0.0, 0.8, -1.0), spin=V(6.0, 0.0, 0.0))
            p.visible = t < T_RELEASE + FLY_TIME
        p.fingers_r = blend_curls(it.curls, opened(it, 1.0), ease("out2", t, T_RELEASE - 0.01, T_RELEASE + 0.07))
        p.hand_l = (lhand(t), left_point_q(), hands.blend({"relaxed": 1.0}))
        p.left_scale = 1.0 if t < 0.30 else 0.0
        hide(p, "pin")
        if it.key in COOKS:
            hide(p, "lever")
        elif "lever" in it.moving:
            lever_pop(p, t, T_RELEASE, it)
        cam(p, t)
        if extra:
            extra(p, t, m)
        return p
    return Clip("lob", DUR_THROW, pose, events=[(T_RELEASE, "release")] +
                ([(T_RELEASE, "spoon")] if it.key in GRENADES and it.key not in COOKS else []),
                fps=120, extra_times=(0.10, T_RELEASE, T_RELEASE + FLY_TIME))


# ==================================================================================== grenade: the pin
def pull_clip(it):
    """The grenade comes up to the chest, turned so the ring faces the left hand; the left index hooks the ring
    (0.20), pulls it straight out along the pin (free at T_PIN) and away; the right hand braces against the pull,
    then cocks back to the hold; frag and flashbang let the spoon fly at T_SPOON."""
    hq = rig.reachable(it, hold_t(it), hold_q(it))
    chest_q = turned(it.rest_q, V(-25.0, 5.0, 22.0))
    chest = V(0.055, -0.100, -0.300)
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.14, chest + V(0.004, 0.004, 0.0), turned(chest_q, V(2.0, 0.0, -2.0)), ("flow", 0.7)),
               (0.20, chest, chest_q, "stop"),
               (0.27, chest + V(0.010, -0.002, 0.006), turned(chest_q, V(-3.0, -4.0, 4.0)), "flow"),
               (0.36, V(0.120, -0.020, -0.240), turned(hq[1], V(10.0, -15.0, 6.0)), "flow"),
               (DUR_PULL, hq[0], hq[1], "stop"))
    # the ring's centre and the pull direction (the pin's axis, item -Z) at the hook time, from the solved hand
    p0 = rig.EquipPose(it)
    apply(m, p0, 0.20)
    p0.solve()
    ring_local = it.part_rest["pin"]["translation"] + em.RING_HOLE[0]
    ring_w = p0.hold_t + quat_rotate(p0.hold_q, ring_local)
    pull_dir = quat_rotate(p0.hold_q, (0.0, 0.0, -1.0))
    x_item = quat_rotate(p0.hold_q, (1.0, 0.0, 0.0))
    q_l = left_palm(x_item, -quat_rotate(p0.hold_q, (0.0, 1.0, 0.0)) + 0.5 * pull_dir)
    hook = hook_target(ring_w, q_l)
    pin_w = p0.hold_t + quat_rotate(p0.hold_q, it.part_rest["pin"]["translation"])
    carry_pos = quat_rotate(quat_conj(q_l), pin_w - hook[0])
    carry_q = quat_mul(quat_conj(q_l), p0.hold_q)
    start = V(-0.150, -0.400, -0.180)
    lpath = Spline((0.00, start, "stop"), (0.12, hook[0] + V(-0.040, -0.050, 0.020), "flow"),
                   (0.20, hook[0], "stop"), (T_PIN + 0.02, hook[0] + pull_dir * 0.040, ("flow", 1.2)),
                   (0.36, hook[0] + pull_dir * 0.080 + V(-0.080, -0.110, 0.030), "flow"),
                   (0.46, start + V(0.0, -0.05, 0.0), "stop"))
    brace = [(T_PIN, V(-0.006, 0.0, -0.008), 9.0, 0.45)]          # the pin lets go: the grenade jumps back
    cam = CamCurve(DUR_PULL, (0.0, V(0, 0, 0)), (0.20, V(-0.4, 0.3, -0.6)), (T_PIN, V(0.2, 0.0, 0.5)),
                   (0.40, V(0.6, -0.4, 1.0)), (DUR_PULL, V(0, 0, 0)),
                   accents=[(T_PIN, V(0.4, -0.2, 0.6), 8.0, 0.45)] +
                   ([(T_SPOON, V(0.2, 0.0, -0.3), 9.0, 0.45)] if it.key in COOKS else []))

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t, dpos=rings(t, brace, DUR_PULL))
        p.fingers_r = squeeze(it.curls, t, 0.24, -6.0, 0.06)
        q_now = q_l
        cl = HOOK if t < 0.36 else blend_curls(HOOK, hands.blend({"relaxed": 1.0}), ease("inout", t, 0.36, 0.44))
        # open fingers on the approach, the index crooks round the ring on arrival
        cl = blend_curls(hands.blend({"relaxed": 0.6, "spread": 0.4}), cl, ease("inout", t, 0.10, 0.19)) \
            if t < 0.20 else cl
        p.hand_l = (lpath(t), q_now, cl)
        p.pole_l = V(-0.55, -0.80, 0.30)
        p.left_scale = 1.0 if t < 0.47 else 0.0
        w = smoothstep(0.185, 0.20, t)
        p.carry["pin"] = (w, carry_pos, carry_q)
        if t >= 0.47:
            hide(p, "pin")
        if it.key in COOKS:
            lever_pop(p, t, T_SPOON, it)
        cam(p, t)
        return p
    ev = [(T_PIN, "pin")] + ([(T_SPOON, "spoon")] if it.key in COOKS else [])
    return Clip("pull", DUR_PULL, pose, events=ev, fps=120, extra_times=(0.20, T_PIN, T_SPOON))


def grenade_inspect(it):
    showq = (turned(it.rest_q, V(55.0, -10.0, 10.0)), turned(it.rest_q, V(-50.0, -5.0, -8.0)),
             turned(it.rest_q, V(10.0, 40.0, 5.0)))

    def left(p, t):
        """At the top view the left index taps the ring and gives the pin a wiggle (still home)."""
        w = ease("inout", t, 2.05, 2.25) * (1 - ease("inout", t, 2.60, 2.78))
        if w <= 1e-4:
            p.left_scale = 0.0
            return
        ring_local = it.part_rest["pin"]["translation"] + em.RING_HOLE[0]
        ring_w = p.hold_t + quat_rotate(p.hold_q, ring_local)
        q_l = left_palm(quat_rotate(p.hold_q, (1.0, 0.0, 0.0)), -quat_rotate(p.hold_q, (0.0, 1.0, 0.0)))
        hook = hook_target(ring_w + quat_rotate(p.hold_q, V(-0.004, 0.006, -0.004)), q_l)
        rest = (rig.LEFT_REST[0], q_l, hands.blend({"relaxed": 1.0}))
        p.hand_l = (hook[0] * w + rest[0] * (1 - w), q_l, blend_curls(hands.blend({"relaxed": 1.0}), HOOK, w))
        p.left_scale = 1.0
        wig = np.sin(2 * np.pi * 6 * max(0.0, t - 2.3)) * ease("inout", t, 2.3, 2.38) * (1 - ease("inout", t, 2.5, 2.6))
        p.parts["pin"] = {"translation": it.part_rest["pin"]["translation"] + V(0, 0, -0.0012 * abs(wig)),
                          "rotation": quat_axis_angle((0, 0, 1), 6.0 * wig)}
    return inspect_clip(it, 3.0, showq, left=left, pos3=V(0.040, -0.085, -0.285))


# ======================================================================================== molotov
def flame_lean(p, m, t):
    """The flame trails the bottle's motion (a spring on its velocity) and flickers."""
    v, _ = velocity(m, t)
    lean = np.clip(-1.6 * v, -1.2, 1.2)
    p.flame_lag = V(lean[2] * 0.9, 0.0, -lean[0] * 0.9) + V(0.08 * np.sin(31 * t), 0.0, 0.10 * np.sin(23 * t + 1.0))


def lit(p, t):
    show(p, "flame")
    p.flame_lag = V(0.08 * np.sin(31 * t), 0.0, 0.10 * np.sin(23 * t + 1.0))


LIGHTER_HELD = None


def lighter_carry():
    """The lighter in the left fist: standing in the curled fingers, its top up past the index (the thumb's side), the
    thumb on the lid and the wheel; its flat faces against the palm (palm-local)."""
    return V(0.006, -0.058, 0.026), quat_axis_angle((0, 0, 1), -90.0)


def light_clip(it):
    """`pull` of the molotov: the left hand brings the lighter up under the rag, the thumb flips the lid open (0.14) and
    rolls the wheel (T_LIGHT: the wick lights), the flame licks the rag's hanging end and it catches (T_CATCH_FIRE);
    the lid is snapped shut and the hand drops away; the bottle comes up to meet it and goes back to the hold."""
    hq = rig.reachable(it, hold_t(it), hold_q(it))
    meet_q = turned(it.rest_q, V(-10.0, -10.0, 15.0))
    meet = V(0.030, -0.120, -0.290)
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.22, meet + V(0.004, -0.004, 0.0), turned(meet_q, V(2.0, 0.0, 0.0)), ("flow", 0.7)),
               (0.30, meet, meet_q, "stop"),
               (0.42, meet + V(0.002, 0.004, 0.002), turned(meet_q, V(-2.0, 2.0, 0.0)), "stop"),
               (0.54, V(0.110, -0.030, -0.250), turned(hq[1], V(10.0, -15.0, 6.0)), "flow"),
               (DUR_LIGHT, hq[0], hq[1], "stop"))
    p0 = rig.EquipPose(it)
    apply(m, p0, 0.34)
    p0.solve()
    rag_end = p0.hold_t + quat_rotate(p0.hold_q, V(0.0006, 0.104, 0.0220))    # the rag's hanging end
    q_l = left_palm((0.80, 0.0, -0.60), (0.30, 0.0, 0.95))
    lpos, lq = lighter_carry()
    # where the wick flame's tip must be: just under the rag's end
    lighter_top = quat_rotate(q_l, lpos + quat_rotate(lq, V(0.0, em.LIGHTER["h"] + 0.030, 0.0)))
    wrist_at = rag_end - V(0.0, 0.004, 0.0) - lighter_top
    start = V(-0.150, -0.420, -0.200)
    lpath = Spline((0.00, start, "stop"), (0.16, wrist_at + V(-0.030, -0.050, 0.010), "flow"),
                   (0.30, wrist_at + V(0.0, -0.012, 0.0), "flow"), (0.38, wrist_at, "stop"),
                   (0.48, wrist_at + V(-0.030, -0.040, 0.010), "flow"), (0.64, start, "stop"))
    cam = CamCurve(DUR_LIGHT, (0.0, V(0, 0, 0)), (0.30, V(-0.6, 0.4, -0.4)), (T_CATCH_FIRE, V(-0.3, 0.2, 0.3)),
                   (0.56, V(0.6, -0.4, 1.0)), (DUR_LIGHT, V(0, 0, 0)),
                   accents=[(T_CATCH_FIRE, V(0.3, 0.0, 0.0), 7.0, 0.5)])

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        flick = ease("inout", t, 0.08, 0.13) * (1 - ease("inout", t, 0.15, 0.20)) +             ease("inout", t, 0.25, 0.29) * (1 - ease("inout", t, 0.31, 0.36))
        p.hand_l = (lpath(t), q_l, hands.adjust(hands.POSES["mag"], index=(-30.0, -20.0), middle=(-12.0, -10.0),
                                                ring=(-10.0, -10.0), pinky=(-8.0, -8.0), thumb=(-25.0 * flick, 10.0)))
        p.pole_l = V(-0.55, -0.80, 0.30)
        on = 0.0 < t < 0.65
        p.left_scale = 1.0 if on else 0.0
        p.carry["lighter"] = (1.0, lpos, lq)
        p.parts["lighter"] = {"scale": np.ones(3) if on else np.zeros(3)}
        lid = -118.0 * ease("out2", t, 0.10, 0.16) * (1 - ease("in2", t, 0.48, 0.52))
        h = rig.item("molotov").part_rest["lighter_lid"]["translation"]
        p.parts["lighter_lid"] = {"translation": h, "rotation": quat_axis_angle((0, 0, 1), lid)}
        p.parts["lighter_flame"] = {"scale": np.ones(3) if T_LIGHT <= t < 0.50 else np.zeros(3)}
        if t >= T_CATCH_FIRE:
            show(p, "flame")
            flame_lean(p, m, t)
            p.flame_lag = p.flame_lag * smoothstep(T_CATCH_FIRE, T_CATCH_FIRE + 0.1, t)
        cam(p, t)
        return p
    return Clip("pull", DUR_LIGHT, pose, events=[(0.14, "light")], fps=120,
                extra_times=(T_LIGHT, T_CATCH_FIRE, 0.50))


# ================================================================================== throwing knife
def knife_throw_clip(it):
    """A short cocked flick: the knife comes back beside the ear point-up (0.09), the arm whips forward and the
    fingers open at the release (point first, almost no spin), the hand follows through down-left."""
    t_r = T_KNIFE_RELEASE
    cock_q = turned(it.rest_q, V(10.0, -60.0, -10.0))
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.09, V(0.160, 0.010, -0.210), cock_q, "stop"),
               (t_r, V(0.085, 0.000, -0.380), rig.along_quat((-0.08, 0.12, -1.0), (0.1, 1.0, 0.15)), ("whip", 1.0)),
               (0.26, V(-0.010, -0.150, -0.410), turned(it.rest_q, V(10.0, 50.0, 10.0)), "flow"),
               (0.38, V(0.050, -0.380, -0.300), turned(it.rest_q, V(0.0, 60.0, 0.0)), "flow"),
               (DUR_KNIFE_THROW, V(0.110, -0.470, -0.200), turned(it.rest_q, V(-10.0, -30.0, 15.0)), "stop"))
    shoulder = Spline((0.0, V(0, 0, 0), "stop"), (0.09, V(0.004, 0.0, 0.020), "stop"),
                      (t_r, V(-0.020, 0.006, -0.040), "flow"), (0.30, V(-0.025, -0.004, -0.030), "stop"),
                      (DUR_KNIFE_THROW, V(0, 0, 0), "stop"))
    cam = CamCurve(DUR_KNIFE_THROW, (0.0, V(0, 0, 0)), (0.09, V(0.6, -0.3, 0.9)), (t_r + 0.02, V(-1.2, 0.5, -1.6)),
                   (0.30, V(-0.5, 0.4, -0.8)), (DUR_KNIFE_THROW, V(0, 0, 0)),
                   accents=[(t_r, V(-0.4, 0.1, -0.5), 7.0, 0.45)])
    # the knife leaves point first along the view
    p_r, q_r = m.pos(t_r), m.quat(t_r)
    fly_q = rig.along_quat((0.0, 0.02, -1.0), (0.0, 1.0, 0.0))

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        p.shoulder_r = shoulder(t)
        if t >= t_r:
            dt = t - t_r
            u = smoothstep(0.0, 0.014, dt)               # it tips over the fingers into its line
            p.fly = (p_r + V(-0.25, 0.10, -7.0) * dt, quat_slerp(q_r, fly_q, u))
            p.visible = dt < 0.04
        p.fingers_r = blend_curls(squeeze(it.curls, t, 0.09, -5.0, 0.04), opened(it, 1.0),
                                  ease("out2", t, t_r - 0.01, t_r + 0.05))
        cam(p, t)
        return p
    return Clip("throw", DUR_KNIFE_THROW, pose, events=[(t_r, "release")], fps=120, extra_times=(0.09, t_r))


def knife_inspect(it):
    """Tossed up from the fist end over end, caught by the grip (the hand gives with the catch), then the wrist turns
    the blade to the eye — one flat, the other — and back to the guard."""
    dur = 2.8
    up = it.rest_t + V(-0.040, 0.020, 0.020)
    t0, t1 = 0.45, 0.95                          # toss .. catch
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.30, up + V(0.0, -0.020, 0.0), turned(it.rest_q, V(0.0, 10.0, 0.0)), "stop"),
               (t0, up + V(0.0, 0.012, 0.0), turned(it.rest_q, V(0.0, 18.0, 0.0)), "flow"),
               (0.70, up + V(0.0, -0.010, 0.0), turned(it.rest_q, V(0.0, 8.0, 0.0)), "stop"),
               (t1, up, it.rest_q, ("hit", 1.4)),
               (1.50, up + V(-0.010, 0.010, 0.0), turned(it.rest_q, V(0.0, 0.0, -70.0)), "flow"),
               (2.10, up + V(0.000, 0.008, 0.0), turned(it.rest_q, V(0.0, 10.0, 75.0)), "flow"),
               (dur - 0.15, it.rest_t + V(-0.002, 0.003, 0.0), turned(it.rest_q, V(0.0, 1.5, 0.0)), ("flow", 0.6)),
               (dur, it.rest_t, it.rest_q, "stop"))
    cam = CamCurve(dur, (0.0, V(0, 0, 0)), (0.30, V(0.3, 0.0, 0.3)), (0.70, V(0.6, 0.0, 0.4)), (1.5, V(0.2, 0.3, -0.8)),
                   (2.1, V(0.2, -0.3, 0.8)), (dur, V(0, 0, 0)), accents=[(t1, V(-0.4, 0.0, -0.2), 8.0, 0.4)])
    catch = [(t1, V(0.0, -0.006, 0.003), 8.0, 0.40)]

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t, dpos=rings(t, catch, dur))
        if t0 <= t < t1:
            u = (t - t0) / (t1 - t0)
            hand0, q0 = m.pos(t0), m.quat(t0)
            pos = hand0 + V(0.0, 0.11 * 4 * u * (1 - u), 0.0) + (m.pos(t1) - hand0) * u
            q = quat_mul(q0, quat_axis_angle((1, 0, 0), -360.0 * u))
            p.fly = (pos, q)
        op = ease("out2", t, t0 - 0.03, t0 + 0.03) * (1 - ease("in2", t, t1 - 0.06, t1))
        p.fingers_r = blend_curls(squeeze(it.curls, t, t1 + 0.02, -8.0, 0.05), opened(it, 1.0), 0.8 * op)
        cam(p, t)
        return p
    return Clip("inspect", dur, pose, events=[(t0, "flip"), (t1, "catch")], fps=120, extra_times=(t0, t1))


# ===================================================================================== adrenaline
def cap_hold():
    """The cap pinched between the left thumb and index (palm-local)."""
    return V(0.0, -0.075, 0.012), quat_euler(0.0, 90.0, 0.0)


def use_clip(it):
    """Cap off (the left hand pulls it straight back off the thumb end, T_CAP), the pen raised high point-down
    (anticipation), stabbed into the thigh (T_STAB: it stops dead, the camera ducks to it), the thumb presses and
    holds (T_INJECT: the shroud springs out over the needle, the hand trembles under the drug), pulled out, tossed
    away to the right (T_DISCARD), the hand out of the picture."""
    show_q = turned(it.rest_q, V(-15.0, -35.0, 25.0))
    up_q = rig.along_quat((-0.10, -0.95, -0.30), (0.0, 0.2, -1.0))
    stab_q = rig.along_quat((-0.05, -0.95, 0.25), (0.0, 0.3, -1.0))
    show_t = V(0.060, -0.070, -0.270)
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.16, show_t + V(0.004, 0.004, 0.0), turned(show_q, V(2.0, 0.0, 0.0)), ("flow", 0.7)),
               (0.22, show_t, show_q, "stop"),
               (T_CAP + 0.02, show_t + V(0.006, -0.004, 0.004), turned(show_q, V(-3.0, 3.0, 0.0)), "stop"),
               (0.50, V(0.115, 0.000, -0.275), up_q, "stop"),
               (T_STAB, V(0.130, -0.165, -0.290), stab_q, {"pos": ("hit", 2.2), "rot": ("hit", 1.8)}),
               (T_INJECT, V(0.130, -0.169, -0.289), stab_q, "stop"),
               (1.02, V(0.130, -0.165, -0.290), stab_q, "stop"),
               (1.14, V(0.128, -0.100, -0.290), turned(stab_q, V(0.0, 20.0, 0.0)), "flow"),
               (T_DISCARD, V(0.180, -0.120, -0.290), turned(stab_q, V(-20.0, 30.0, -20.0)), ("whip", 1.0)),
               (1.32, V(0.200, -0.300, -0.250), turned(it.rest_q, V(0.0, 0.0, 0.0)), "flow"),
               (DUR_USE, V(0.110, -0.470, -0.200), turned(it.rest_q, V(-10.0, -30.0, 15.0)), "stop"))
    shoulder = Spline((0.0, V(0, 0, 0), "stop"), (0.50, V(0.0, 0.010, 0.010), "stop"),
                      (T_STAB, V(0.0, -0.040, -0.020), "hit"), (1.02, V(0.0, -0.040, -0.020), "stop"),
                      (DUR_USE, V(0, 0, 0), "stop"))
    stab_ring = [(T_STAB, V(0.0, 0.006, 0.0), 9.0, 0.4), (T_INJECT, V(0.0, -0.002, 0.0), 10.0, 0.45)]
    cam = CamCurve(DUR_USE, (0.0, V(0, 0, 0)), (0.22, V(0.3, 0.0, 0.4)), (0.50, V(0.6, -0.3, 0.8)),
                   (T_STAB, V(-2.2, -0.3, -1.4), "hit"), (1.02, V(-2.0, -0.2, -1.0)), (1.18, V(-0.6, -0.6, 0.6)),
                   (DUR_USE, V(0, 0, 0)), accents=[(T_STAB, V(-0.25, 0.0, -0.6), 8.0, 0.45),
                                                    (T_INJECT, V(0.0, 0.0, 0.5), 6.0, 0.5)])
    # the cap's frame at the moment the left hand takes it
    p0 = rig.EquipPose(it)
    apply(m, p0, 0.20)
    p0.solve()
    cap_w = p0.hold_t + quat_rotate(p0.hold_q, it.part_rest["cap"]["translation"])
    back = quat_rotate(p0.hold_q, (0.0, 0.0, 1.0))
    q_l = left_palm(V(0.75, 0.10, -0.65), -V(0.75, 0.0, 0.40))
    cpos, cq = cap_hold()
    wrist = cap_w - quat_rotate(q_l, cpos)
    carry_pos = quat_rotate(quat_conj(q_l), cap_w - wrist)
    carry_q = quat_mul(quat_conj(q_l), p0.hold_q)
    start = V(-0.150, -0.400, -0.180)
    lpath = Spline((0.00, start, "stop"), (0.12, wrist + V(-0.030, -0.040, 0.020), "flow"), (0.20, wrist, "stop"),
                   (T_CAP, wrist + back * 0.035, ("flow", 1.2)), (0.38, wrist + back * 0.06 + V(-0.08, -0.12, 0.0),
                                                                  "flow"), (0.48, start, "stop"))
    t_fly = T_DISCARD

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t, dpos=rings(t, stab_ring, DUR_USE) + window(t, T_INJECT, 1.02, 0.04) *
              drift(t, V(0.0012, 0.0012, 0.0006), seed=9, freq=7.0))
        p.shoulder_r = shoulder(t)
        p.fingers_r = squeeze(it.curls, t, T_STAB, -7.0, 0.06)
        press = ease("out2", t, T_INJECT - 0.05, T_INJECT) * (1 - ease("inout", t, 1.02, 1.10))
        p.fingers_r = hands.adjust(p.fingers_r, thumb=(-14.0 * press, -8.0 * press))
        p.hand_l = (lpath(t), q_l, hands.adjust(hands.POSES["pinch"], index=(-6.0, 0.0)))
        p.pole_l = V(-0.55, -0.80, 0.30)
        p.left_scale = 1.0 if t < 0.48 else 0.0
        p.carry["cap"] = (smoothstep(0.185, 0.20, t), carry_pos, carry_q)
        if t >= 0.48:
            hide(p, "cap")
        if t >= T_INJECT:
            sh = ring(t, T_INJECT, 1.0, 14.0, 0.5)
            p.parts["plunger"] = {"translation": it.part_rest["plunger"]["translation"] +
                                  V(0, 0, -0.012 * min(1.0, (t - T_INJECT) / 0.015) - 0.0015 * sh)}
        if t >= t_fly:
            p.fly = flight(m, t_fly, t, extra_v=V(1.5, 0.4, 0.3), spin=V(0.0, 0.0, -18.0))
            p.visible = t < t_fly + 0.10
            p.fingers_r = blend_curls(p.fingers_r, opened(it, 1.0), ease("out2", t, t_fly - 0.01, t_fly + 0.05))
        cam(p, t)
        return p
    ev = [(T_CAP, "cap"), (T_STAB, "stab"), (T_INJECT, "inject"), (T_DISCARD, "discard")]
    return Clip("use", DUR_USE, pose, events=ev, fps=120, extra_times=(T_CAP, T_STAB, T_INJECT, T_DISCARD))


# ===================================================================================== flashlight
def click_clip(it):
    """The thumb pushes the tail switch home (T_CLICK: it clicks, the boot squashes 1 mm) and lets go; the wrist ticks
    up a hair against the press and settles."""
    press_t = T_CLICK - 0.05
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (press_t, it.rest_t + V(0.0, 0.002, 0.003), turned(it.rest_q, V(1.5, 0.0, -1.0)), "flow"),
               (T_CLICK, it.rest_t + V(0.0, 0.003, 0.005), turned(it.rest_q, V(2.5, 0.0, -1.5)), ("hit", 1.4)),
               (T_CLICK + 0.10, it.rest_t + V(0.0, 0.001, 0.001), turned(it.rest_q, V(0.6, 0.0, -0.3)), "flow"),
               (DUR_CLICK, it.rest_t, it.rest_q, "stop"))
    cam = CamCurve(DUR_CLICK, (0.0, V(0, 0, 0)), (T_CLICK, V(-0.15, 0.0, 0.1), "hit"), (DUR_CLICK, V(0, 0, 0)))

    def pose(t):
        p = rig.EquipPose(it)
        apply(m, p, t)
        press = ease("out2", t, press_t, T_CLICK) * (1 - ease("inout", t, T_CLICK + 0.04, T_CLICK + 0.14))
        p.fingers_r = hands.adjust(it.curls, thumb=(-12.0 * press, -9.0 * press))
        p.parts["button"] = {"translation": it.part_rest["button"]["translation"] + V(0.0, 0.0, -0.0010 * press)}
        cam(p, t)
        return p
    return Clip("use", DUR_CLICK, pose, events=[(T_CLICK, "click")], fps=120, extra_times=(T_CLICK,))


def light_inspect(it):
    showq = (turned(it.rest_q, V(35.0, -10.0, 0.0)), turned(it.rest_q, V(10.0, 30.0, -70.0)),
             turned(it.rest_q, V(-20.0, -40.0, 10.0)))
    return inspect_clip(it, 2.6, showq)


def pen_inspect(it):
    showq = (turned(it.rest_q, V(40.0, 0.0, 0.0)), turned(it.rest_q, V(25.0, 15.0, -60.0)),
             turned(it.rest_q, V(30.0, -15.0, 0.0)))
    return inspect_clip(it, 2.8, showq)


def molotov_inspect(it):
    showq = (turned(it.rest_q, V(40.0, -8.0, 8.0)), turned(it.rest_q, V(-45.0, -5.0, -6.0)),
             turned(it.rest_q, V(8.0, 25.0, 25.0)))
    return inspect_clip(it, 3.0, showq)


# ======================================================================================== the sets
def clips(key):
    it = rig.item(key)
    if key in GRENADES:
        return [idle_clip(it), draw_clip(it, DUR_DRAW_DEFAULT), grenade_inspect(it), walk_clip(it), sprint_clip(it),
                pull_clip(it), hold_clip(it), throw_clip(it), lob_clip(it)]
    if key == "molotov":
        def burning(p, t, m=None):
            if m is not None:
                show(p, "flame")
                flame_lean(p, m, t)
            else:
                lit(p, t)
        return [idle_clip(it), draw_clip(it, DUR_DRAW["molotov"]), molotov_inspect(it), walk_clip(it),
                sprint_clip(it), light_clip(it), hold_clip(it, extra=lit), throw_clip(it, extra=burning),
                lob_clip(it, extra=burning)]
    if key == "throwing_knife":
        return [idle_clip(it), draw_clip(it, DUR_DRAW["throwing_knife"], spin=-360.0), knife_inspect(it),
                walk_clip(it), sprint_clip(it), knife_throw_clip(it)]
    if key == "flashlight":
        return [idle_clip(it), draw_clip(it, DUR_DRAW["flashlight"]), light_inspect(it), walk_clip(it), sprint_clip(it),
                click_clip(it)]
    return [idle_clip(it), draw_clip(it, DUR_DRAW["adrenaline"]), pen_inspect(it), walk_clip(it), sprint_clip(it),
            use_clip(it)]
