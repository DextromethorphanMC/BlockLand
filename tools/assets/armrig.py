"""Shared viewmodel arm rig (sections 3 and 16): two-bone IK to the wrist, wrist rotation from a palm frame,
finger curls. A hand target is (wrist position, palm quaternion, finger curls) in viewmodel space."""
import numpy as np

import hands
from ik import FORE_LEN, solve_arm
from vmath import norm, quat_axis_angle, quat_conj, quat_mul, quat_normalize, quat_rotate


def palm_from_back(back, fwd_hint=(0.0, -1.0, 0.0), side="R"):
    """Palm frame whose back-of-hand (-Z) faces `back` and whose fingers point roughly along `fwd_hint`."""
    z = -norm(back)
    y = -norm(np.asarray(fwd_hint, float) - z * np.dot(fwd_hint, z))
    x = np.cross(y, z)
    from vmath import frame_quat
    return frame_quat(x, y, z)


def blend_targets(items):
    """items: [(weight, (pos, quat, curls))] -> blended target."""
    items = [(w, t) for w, t in items if w > 1e-6]
    tot = sum(w for w, _ in items)
    pos = sum(w * t[0] for w, t in items) / tot
    quat = hands.nlerp_quats([t[1] for _, t in items], [w / tot for w, _ in items])
    curls = {f: sum(w * np.asarray(t[2][f], float) for w, t in items) / tot for f in hands.FINGERS}
    return pos, quat, curls


def aligned_pole(shoulder, wrist, q_palm, pole, align):
    """Elbow pole that lines the forearm up with the palm: aim the elbow at the point a forearm length behind
    the wrist (along the palm), blended with the default down/out pole so elbows never flip up."""
    shoulder, wrist = np.asarray(shoulder, float), np.asarray(wrist, float)
    u = norm(wrist - shoulder)
    ideal = wrist - FORE_LEN * quat_rotate(q_palm, (0.0, -1.0, 0.0))
    v = ideal - shoulder
    v = v - u * np.dot(v, u)                       # metres: fades out smoothly when the ideal elbow is on-axis
    v[1] = min(v[1], 0.0)                          # alignment may pull the elbow sideways / down, never up
    p = norm(np.asarray(pole, float) - u * np.dot(pole, u))
    return norm(p + align * v / 0.12)


def quat_between(a, b):
    """Minimal rotation taking unit vector a onto unit vector b."""
    a, b = norm(a), norm(b)
    c = float(np.dot(a, b))
    if c < -0.999999:
        axis = norm(np.cross(a, (1.0, 0.0, 0.0) if abs(a[0]) < 0.9 else (0.0, 1.0, 0.0)))
        return np.array([axis[0], axis[1], axis[2], 0.0])
    v = np.cross(a, b)
    return quat_normalize(np.array([v[0], v[1], v[2], 1.0 + c]))


FREE_BEND = quat_axis_angle((1, 0, 0), -12.0)     # relaxed wrist of a free (pumping) hand, relative to the forearm


def solve_side(side, shoulder, pole, target, align=0.45, limit=False):
    """-> (node TRS dict for arm_X, arm_X_fore, arm_X_hand and the fingers, ik info).
    A target with palm quaternion None is a free hand: the palm follows the forearm with a relaxed bend.
    limit=True keeps the wrist inside WRIST_LIMITS (ik["q_palm"] is the palm actually used)."""
    wrist, q_palm, curls = target
    if q_palm is None:
        out = np.array([1.0 if side == "R" else -1.0, 0.25, -0.35])
        ik = solve_arm(shoulder, wrist, pole, out)
        q_palm = quat_mul(ik["q_fore_world"], FREE_BEND)
    else:
        back = quat_rotate(q_palm, (0.0, 0.0, -1.0))
        if align > 0:
            pole = aligned_pole(shoulder, wrist, q_palm, pole, align)
        ik = solve_arm(shoulder, wrist, pole, back)
        # swing-twist: the forearm takes the palm's twist, swung onto the forearm axis by the minimal rotation,
        # so the wrist only bends (continuous whenever the palm frame is continuous)
        fore_dir = norm(ik["hand"] - ik["elbow"])
        if limit:
            q_palm = limit_palm(q_palm, fore_dir, side)
        q_fore_world = quat_mul(quat_between(quat_rotate(q_palm, (0.0, -1.0, 0.0)), fore_dir), q_palm)
        ik["q_fore_world"] = q_fore_world
        ik["q_fore"] = quat_mul(quat_conj(ik["q_upper"]), q_fore_world)
    ik["q_palm"] = q_palm
    p = f"arm_{side}"
    trs = {p: {"rotation": ik["q_upper"]}, f"{p}_fore": {"rotation": ik["q_fore"]},
           f"{p}_hand": {"rotation": hands.hand_local(ik["q_fore_world"], q_palm)}}
    trs.update(hands.finger_trs(side, curls))
    return trs, ik


# anatomical wrist range in degrees: extension / flexion (hand about its X axis) and ulnar / radial deviation
# (about the palm normal; ulnar = towards the little finger)
WRIST_LIMITS = {"ext": 60.0, "flex": 70.0, "ulnar": 38.0, "radial": 20.0}


def _angles_from_fore(q_palm, fore_dir, side):
    """Wrist angles from the forearm direction seen from the palm (the forearm takes the palm's twist, so the
    hand is a pure swing of the forearm frame and the palm direction in that frame is f mirrored about Y)."""
    f = quat_rotate(quat_conj(q_palm), fore_dir)
    h = np.array([-f[0], f[1], -f[2]])
    ext = float(np.degrees(np.arctan2(-h[2], -h[1])))
    dev = float(np.degrees(np.arctan2(h[0], -h[1]))) * hands.mirror(side)
    return ext, dev, f


def wrist_angles(q_fore_world, q_palm, side):
    """(extension, ulnar deviation) of the hand relative to the forearm in degrees (negative = flexion / radial)."""
    return _angles_from_fore(q_palm, quat_rotate(q_fore_world, (0.0, -1.0, 0.0)), side)[:2]


def bend_limit(ux, uz, side, limits=WRIST_LIMITS):
    """Largest wrist bend (deg) towards the direction (ux, uz) of the hand's XZ plane: an ellipse through the
    four anatomical limits (continuous across the quadrants)."""
    a = limits["ulnar"] if ux * hands.mirror(side) > 0 else limits["radial"]
    b = limits["ext"] if uz < 0 else limits["flex"]
    return 1.0 / np.sqrt((ux / a) ** 2 + (uz / b) ** 2)


def limit_palm(q_palm, fore_dir, side, limits=WRIST_LIMITS, knee=12.0):
    """Smallest turn of the palm that brings the wrist back into its range for this forearm direction: the bend
    keeps its direction and saturates smoothly at the range, so the hand cannot fold past what a real wrist does,
    whatever the target asked for."""
    f = quat_rotate(quat_conj(q_palm), fore_dir)
    h = np.array([-f[0], f[1], -f[2]])            # palm direction in the forearm frame
    r = float(np.hypot(h[0], h[2]))
    if r < 1e-9:
        return q_palm
    theta = float(np.degrees(np.arctan2(r, -h[1])))
    ux, uz = h[0] / r, h[2] / r
    a = bend_limit(ux, uz, side, limits) - knee
    if theta <= a:
        return q_palm
    t2 = np.radians(a + knee * (1.0 - np.exp(-(theta - a) / knee)))
    h2 = np.array([ux * np.sin(t2), -np.cos(t2), uz * np.sin(t2)])
    return quat_mul(q_palm, quat_between(np.array([-h2[0], h2[1], -h2[2]]), f))


def wrist_bend(ik, q_palm):
    """Angle (deg) between the forearm direction and the palm direction: how hard the wrist is bent."""
    fore = norm(ik["hand"] - ik["elbow"])
    palm = quat_rotate(q_palm, (0.0, -1.0, 0.0))
    return float(np.degrees(np.arccos(np.clip(np.dot(fore, palm), -1, 1))))


def add_hand_nodes(g, side, rest):
    """Add arm_X_hand and the finger joints under arm_X_fore (rest rotations from `rest`)."""
    for name, (parent, t, _) in hands.joint_layout(side).items():
        g.add_node(name, parent, t, rest[name]["rotation"])


def rest_values(rest, side):
    """Rest TRS entries for bake_clip for one side's hand joints."""
    return {name: {"rotation": rest[name]["rotation"]} for name in hands.joint_layout(side)}
