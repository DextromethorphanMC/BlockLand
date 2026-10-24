"""Aimed fire clips (VIEWMODEL_SPEC sections 4 and 31.5): `fire_ads` / `fire_last_ads`, the shot as the hands give it
with the sights up. Derived from a gun's own hip `fire` / `fire_last` pose function, so the mechanism (trigger, slide /
bolt / carrier cycle, hammer, fingers) and the events stay exactly the hip clip's, and only the weapon's motion in the
hands is re-shaped to keep the sight picture:

* the push back is kept along the sight line (the eye point slides back along it, the picture does not move), at
  most `max_along` metres so nothing comes too close to the eye;
* sideways / vertical drift of the eye point is cut to a trace (`lateral`);
* pitch and yaw of the weapon are cut to a trace (`turn`, never more than `max_turn` degrees) and every turn is taken
  about the eye point, not the grip, so what is left only tilts the sight line, never swings the eye point off it;
* the roll about the sight line is kept small (`roll`): an optic sits a few centimetres above the iron sight line, so a
  roll about the irons slides its eye point sideways;
* the clip's camera is calmed (`camera`: pitch, yaw, roll factors) - the runtime adds the aimed recoil and camera punch
  (section 31.4 / 32), which already follow the sight line.

Hands that the hip pose places in viewmodel space (not on an anchor) are carried along with the re-shaped weapon, so a
hand that holds the weapon keeps holding it. Use: FIRE_ADS = aimed(FIRE, rig, eye_point_in_gun_space)."""
import numpy as np

from anim import qexp, qlog
from vmath import quat_conj, quat_mul, quat_rotate

SUFFIX = "_ads"
SIGHT = np.array([0.0, 0.0, -1.0])        # sight line in gun space (aim nodes are not rotated against the gun)


def _carry(target, old_t, old_q, new_t, new_q):
    """A viewmodel-space hand target (pos, quat, curls) moved with the weapon from (old_t, old_q) to (new_t, new_q)."""
    if target is None:
        return None
    pos, q, curls = target
    d = quat_mul(new_q, quat_conj(old_q))
    return new_t + quat_rotate(d, np.asarray(pos, float) - old_t), (None if q is None else quat_mul(d, q)), curls


def _soft_cap(v, cap):
    """v (a rotation vector) shortened smoothly so its length never passes `cap` (radians)."""
    n = float(np.linalg.norm(v))
    return v if n < 1e-12 else v * (cap * np.tanh(n / cap) / n)


def reshape(p, rig, aim, ref=None, along=0.85, max_along=0.003, lateral=0.04, roll=0.12, turn=0.03, max_turn=0.03,
            camera=(0.45, 0.4, 0.7)):
    """Re-shapes a hip fire pose `p` (in place) into its aimed variant; `aim` = the eye point in gun space. The hip
    clip moves the weapon from the rig's rest pose; the aimed one moves it from `ref` (gun_t, gun_q): the idle's first
    frame, where the runtime puts the eye point on the camera when it aims (section 5)."""
    aim = np.asarray(aim, float)
    t0, q0 = np.asarray(rig.GUN_T, float), np.asarray(rig.GUN_Q, float)
    rt, rq = (t0, q0) if ref is None else (np.asarray(ref[0], float), np.asarray(ref[1], float))
    old_t, old_q = p.gun_t.copy(), p.gun_q.copy()
    s = quat_rotate(rq, SIGHT)
    e = (old_t + quat_rotate(old_q, aim)) - (t0 + quat_rotate(q0, aim))        # the eye point's displacement
    d = float(np.dot(e, s)) * along
    e_along = max_along * np.tanh(d / max_along) * s                          # the shove back, at most max_along
    e_side = e - float(np.dot(e, s)) * s
    rv = qlog(quat_mul(quat_conj(q0), old_q))                                   # gun-local turn: pitch, yaw, roll
    tilt = _soft_cap(rv * np.array([turn, turn, 0.0]), np.radians(max_turn))   # pistols flip hard: cap the trace
    new_q = quat_mul(rq, qexp(tilt + rv * np.array([0.0, 0.0, roll])))
    eye = rt + quat_rotate(rq, aim) + e_along + lateral * e_side
    p.gun_t = eye - quat_rotate(new_q, aim)
    p.gun_q = new_q
    p.hand_r = _carry(p.hand_r, old_t, old_q, p.gun_t, new_q)
    p.hand_l = _carry(p.hand_l, old_t, old_q, p.gun_t, new_q)
    cv = qlog(p.cam_q)                                                          # small: ~ (pitch, yaw, roll)
    p.cam_q = qexp(cv * np.asarray(camera, float))
    return p


def aimed(clip, rig, aim, idle=None, name=None, **shape):
    """The aimed variant of a hip fire clip: same length, frame rate, key times and events, named `<clip>_ads`;
    `idle` = the gun's idle clip (its first frame is where the sights are when aiming)."""
    ref = None
    if idle is not None:
        first = idle.pose(0.0)
        ref = (first.gun_t.copy(), first.gun_q.copy())

    def pose(t):
        return reshape(clip.pose(t), rig, aim, ref, **shape)
    out = type(clip)(name or clip.name + SUFFIX, clip.duration, pose, events=clip.events, fps=clip.fps,
                     extra_times=clip.extra_times, force_nodes=clip.force_nodes)
    for key, value in vars(clip).items():          # bake options a clip carries (e.g. linear_scale)
        out.__dict__.setdefault(key, value)
    return out
