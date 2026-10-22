"""Two-bone arm IK for the viewmodel rig (VIEWMODEL_SPEC section 3).

A segment hangs along its joint's local -Y. Local -Z is the front of the arm (back of the hand on the forearm),
local +X the outer side of the right arm. The upper arm's front faces the elbow bend (biceps side), the forearm
is twisted about its own axis so that its -Z points as close as possible to the requested back-of-hand direction."""
import numpy as np

from vmath import frame_quat, norm, quat_conj, quat_mul

UPPER_LEN = 0.30
FORE_LEN = 0.30


def _perp(v, axis, fallback):
    p = v - axis * np.dot(v, axis)
    if np.linalg.norm(p) < 1e-6:
        p = fallback - axis * np.dot(fallback, axis)
    return norm(p)


def solve_arm(shoulder, target, pole, back_dir, l1=UPPER_LEN, l2=FORE_LEN):
    """-> dict(q_upper (joint rotation in parent space), q_fore (local to upper), elbow, hand, reach_error)."""
    shoulder = np.asarray(shoulder, float)
    target = np.asarray(target, float)
    d = target - shoulder
    dist = np.linalg.norm(d)
    u = d / max(dist, 1e-9)
    reach = l1 + l2
    soft = 0.96 * reach                 # soft IK: approach full extension asymptotically (no elbow snap)
    if dist > soft:
        dist = soft + (reach - soft) * (1 - np.exp(-(dist - soft) / (reach - soft)))
    dist_c = np.clip(dist, abs(l1 - l2) + 1e-4, reach - 1e-5)
    a = (l1 * l1 - l2 * l2 + dist_c * dist_c) / (2 * dist_c)
    h = np.sqrt(max(l1 * l1 - a * a, 0.0))
    side = _perp(np.asarray(pole, float), u, np.array([0.0, -1.0, 0.0]))
    elbow = shoulder + u * a + side * h
    u1 = norm(elbow - shoulder)
    u2 = norm(target - elbow)
    hand = elbow + u2 * l2
    bend = _perp(u2, u1, side)                      # direction the forearm folds towards
    y1, z1 = -u1, -bend
    x1 = np.cross(y1, z1)
    b = _perp(np.asarray(back_dir, float), u2, -bend)
    y2, z2 = -u2, -b
    x2 = np.cross(y2, z2)
    q1 = frame_quat(x1, y1, z1)
    q2 = frame_quat(x2, y2, z2)
    return {"q_upper": q1, "q_fore": quat_mul(quat_conj(q1), q2), "q_fore_world": q2, "elbow": elbow,
            "hand": hand, "reach_error": float(np.linalg.norm(hand - target))}
