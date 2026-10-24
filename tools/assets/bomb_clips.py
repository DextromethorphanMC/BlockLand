"""The bomb's clips (VIEWMODEL_SPEC sections 31 and 39), authored with the equipment's tools (equip_clips: key-pose
motions, camera curves, the shared idle / draw / inspect / walk / sprint).

`plant` (stretched by the runtime to the mode's plant time): the bundle is lifted a little, then lowered and pushed
out in front as the view dips (the player kneels), set down on the floor (`place`: it stops dead, a small bump), the
left hand steadies it by its side while the right hand lets go and keys the code in — the index finger taps seven
keys (`key` on every press), then both hands hover over it. The bomb stays where it was put (`fly`) until the end."""
import numpy as np

import bomb_rig as br
import equip_clips as ec
import equip_rig as er
import hands
from anim import ring, smoothstep, window
from equip_clips import V, apply, motion, squeeze, turned
from knife_clips import CamCurve, ease, rings
from p9_clips import Clip
from vmath import norm, quat_conj, quat_rotate

# as-built timing (seconds) -> bomb.json (drawTime) and the spec
DUR_DRAW = 0.60
DUR_PLANT = 3.2
DUR_INSPECT = 3.0
T_PLACE = 0.62
TAPS = (0.98, 1.24, 1.50, 1.76, 2.02, 2.30, 2.58)
KEYS = (1, 5, 7, 3, 10, 8, 11)                     # key_<n> pressed at each tap
HOVER = 0.013                                     # the fingertip's lift between presses (m)
PRESS = 0.0010                                    # how far a key goes down under the finger

PLACE_T = V(0.010, -0.176, -0.440)
PLACE_UP, PLACE_FWD = (0.0, 0.93, 0.37), (-0.06, 0.0, -1.0)
TAP_CURLS = {"thumb": (-44, -24), "index": (-14, -12), "middle": (-88, -80), "ring": (-90, -84), "pinky": (-88, -82)}
HOLD_L = hands.adjust(hands.POSES["support"], index=(10.0, 6.0), middle=(6.0, 4.0))
LEFT_START = V(-0.160, -0.440, -0.120)


def timing():
    """bomb.json fields this clip set fixes."""
    return {"drawTime": DUR_DRAW}


def _wrapped(clip, events=None):
    """A shared equipment clip, its poses solved with the `bomb` node (and its events renamed)."""
    pose_fn = clip._pose
    out = Clip(clip.name, clip.duration, lambda t: br.renamed(pose_fn(t)), events=clip.events if events is None else events,
               fps=clip.fps, loop=clip.loop, extra_times=clip.extra_times, force_nodes=clip.force_nodes)
    return out


def _world(frame, local):
    t, q = frame
    return t + quat_rotate(q, local)


def _axis(frame, v):
    return quat_rotate(frame[1], v)


def reach_tip(p, tip, rounds=3):
    """Moves the free right hand's target until the index fingertip of the hand the solver actually reaches (the wrist
    limits turn the palm) lands on `tip`."""
    held = (p.hold_t.copy(), p.hold_q.copy())
    for _ in range(rounds):
        _, info = p.solve()
        p.hold_t, p.hold_q = held[0].copy(), held[1].copy()
        reached = hands.fingertips("R", info["R"]["hand"], info["R"]["q_palm"], p.free_r[2])["index"]
        err = (np.asarray(tip, float) - reached) * p.free_w
        p.free_r = (p.free_r[0] + err, p.free_r[1], p.free_r[2])


def plant_clip(it):
    place_q = er.item_quat(PLACE_UP, PLACE_FWD)
    lift_t = it.rest_t + V(-0.020, 0.022, -0.020)
    m = motion(it,
               (0.00, it.rest_t, it.rest_q, "stop"),
               (0.20, lift_t, turned(it.rest_q, V(4.0, 6.0, 2.0)), ("flow", 0.8)),
               (0.48, PLACE_T + V(0.004, 0.030, 0.012), turned(place_q, V(-2.0, -4.0, 0.0)), "flow"),
               (T_PLACE, PLACE_T, place_q, {"pos": ("hit", 2.0), "rot": ("hit", 1.6)}),
               (DUR_PLANT, PLACE_T, place_q, "stop"))
    # where the bomb actually lies once down: the drawn frame at the moment it is set down
    p0 = er.EquipPose(it)
    apply(m, p0, T_PLACE)
    p0.solve()
    placed = (p0.hold_t.copy(), p0.hold_q.copy())
    up, fwd, right = _axis(placed, (0, 1, 0)), _axis(placed, (0, 0, -1)), _axis(placed, (1, 0, 0))

    # the right index finger's path over the keypad: hops from key to key, pressing each at its tap time
    keys = [_world(placed, it.points[f"key_{k}"]) + up * (0.8 * hands.FINGER_T / 2) for k in KEYS]
    q_tap = hands.palm_frame(norm(0.55 * fwd - 0.80 * up - 0.10 * right), -right, "R")
    tip_off = hands.fingertips("R", np.zeros(3), q_tap, TAP_CURLS)["index"]

    def tip(t):
        if t <= TAPS[0]:
            u = ease("inout", t, T_PLACE + 0.05, TAPS[0])
            above = keys[0] + up * (HOVER * 2.2)
            return above + (keys[0] - above) * (u ** 2.4)
        if t >= TAPS[-1]:
            u = ease("out2", t, TAPS[-1], TAPS[-1] + 0.30)
            return keys[-1] + up * (HOVER * 2.6 * u) + right * (0.010 * u) - fwd * (0.012 * u)
        i = int(np.searchsorted(TAPS, t, side="right") - 1)
        u = (t - TAPS[i]) / (TAPS[i + 1] - TAPS[i])
        along = smoothstep(0.15, 0.85, u)
        hop = np.sin(np.pi * np.clip(u, 0, 1)) ** 0.7
        return keys[i] + (keys[i + 1] - keys[i]) * along + up * (HOVER * hop)

    def press(t):
        return sum(np.exp(-((t - tt) / 0.025) ** 2) for tt in TAPS)

    # the left hand steadies the bundle by its left side, the palm on the tape, the fingers along it
    contact_l = _world(placed, np.array([-(0.0475 + 0.0016), -0.002, 0.032]))
    f_l = norm(fwd - 0.35 * up)
    q_l = ec.left_palm(f_l, right)
    wrist_l = contact_l - right * (hands.PALM_T / 2) - f_l * (hands.PALM_LEN / 2)
    lpath = ec.Spline((0.00, LEFT_START, "stop"), (0.22, LEFT_START, "stop"),
                      (0.50, wrist_l + V(-0.030, 0.030, 0.035), "flow"), (T_PLACE + 0.03, wrist_l, ("hit", 1.4)),
                      (2.86, wrist_l + up * 0.002, "stop"), (DUR_PLANT, wrist_l + up * 0.018 - right * 0.006, "stop"))

    cam = CamCurve(DUR_PLANT, (0.0, V(0, 0, 0)), (0.20, V(0.4, 0.2, 0.3)), (T_PLACE, V(-2.1, 0.3, -0.9), "hit"),
                   (1.00, V(-1.9, 0.2, -0.5)), (2.60, V(-1.8, -0.2, -0.3)), (2.95, V(-0.9, 0.0, -0.1)),
                   (DUR_PLANT, V(0, 0, 0)),
                   accents=[(T_PLACE, V(-0.25, 0.0, -0.4), 8.0, 0.45)] + [(tt, V(-0.08, 0.0, 0.05), 11.0, 0.5)
                                                                           for tt in TAPS])
    shoulder = ec.Spline((0.0, V(0, 0, 0), "stop"), (T_PLACE, V(-0.010, -0.025, -0.020), "hit"),
                         (2.86, V(-0.010, -0.025, -0.020), "stop"), (DUR_PLANT, V(-0.006, -0.012, -0.010), "stop"))

    def pose(t):
        p = er.EquipPose(it)
        apply(m, p, t, dpos=rings(t, [(T_PLACE, V(0.0, 0.004, 0.0), 9.0, 0.4)], DUR_PLANT))
        p.shoulder_r = shoulder(t)
        p.fingers_r = squeeze(it.curls, t, T_PLACE, -6.0, 0.06)
        if t >= T_PLACE:
            p.fly = placed
            tp = tip(t) - up * (PRESS * press(t))
            p.free_r = (tp - tip_off, q_tap, hands.adjust(TAP_CURLS, index=(-6.0 * press(t), -4.0 * press(t))))
            p.free_w = ease("inout", t, T_PLACE + 0.02, T_PLACE + 0.22)
        p.hand_l = (lpath(t), q_l, HOLD_L)
        p.pole_l = V(-0.60, -0.80, 0.30)
        p.left_scale = 1.0 if t >= 0.22 else 0.0
        if t >= T_PLACE:
            reach_tip(p, tp)
        cam(p, t)
        return br.renamed(p)

    ev = [(T_PLACE, "place")] + [(tt, "key") for tt in TAPS]
    return Clip("plant", DUR_PLANT, pose, events=ev, fps=60, extra_times=(T_PLACE,) + TAPS)


def inspect(it):
    showq = (turned(it.rest_q, V(25.0, 22.0, 10.0)), turned(it.rest_q, V(-30.0, -8.0, -50.0)),
             turned(it.rest_q, V(15.0, -20.0, 45.0)))
    return ec.inspect_clip(it, DUR_INSPECT, showq)


def clips():
    it = br.item()
    return [_wrapped(ec.idle_clip(it)), _wrapped(ec.draw_clip(it, DUR_DRAW), events=[(0.0, "draw")]),
            _wrapped(inspect(it)), _wrapped(ec.walk_clip(it)), _wrapped(ec.sprint_clip(it)), plant_clip(it)]
