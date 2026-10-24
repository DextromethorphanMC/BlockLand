"""Hands and fingers (VIEWMODEL_SPEC section 16): joint layout, finger poses, palm frames and skin-UV meshes.

Hand joint frame (both sides): the palm hangs from the wrist along local -Y, the palm faces local +Z, the back of
the hand is local -Z (as for the arm segments). The thumb sits on the medial side: local -X on the right hand,
+X on the left hand. Every finger joint curls about its local X; NEGATIVE angles close the hand (the thumb joint
has a fixed opposed rest frame so the same rule holds for it). Joint positions are the same for wide and slim
skins (slim meshes are narrower and shifted inside the same joints)."""
import numpy as np

from meshkit import Mesh
from vmath import frame_quat, norm, quat_axis_angle, quat_conj, quat_identity, quat_mul, quat_normalize

PX = 0.0175                     # one skin pixel across the arm in v1 (4 px = 0.07)
HAND_SCALE = 0.88               # v2: hands at real-hand proportions, a little smaller than v1 skin pixels
HPX = PX * HAND_SCALE           # one skin pixel across the hand
PALM_LEN = 4 * HPX              # wrist -> knuckles
PALM_T = 2 * HPX                # palm thickness (z)
PALM_T_V1 = 2 * PX              # the palm thickness the hand anchors were authored with (see wrap_distance)
FINGERS = ("thumb", "index", "middle", "ring", "pinky")
LENGTHS = {f: (a * HAND_SCALE, b * HAND_SCALE) for f, (a, b) in
           {"thumb": (0.030, 0.026), "index": (0.034, 0.026), "middle": (0.036, 0.028), "ring": (0.034, 0.026),
            "pinky": (0.028, 0.022)}.items()}
FINGER_T = 0.0165 * HAND_SCALE  # finger thickness (z)
SKIN_OFFSETS = {"R": (40, 16), "L": (32, 48)}
HAND_JOINTS = [f"{f}{s}" for f in FINGERS for s in ("", "2")]


def palm_width(variant):
    return 4 * HPX if variant == "wide" else 3 * HPX


def wrap_distance(r):
    """Palm-centre distance for an anchor authored with the v1 palm (PALM_T_V1): keeps the palm face where it was,
    so hands still touch the parts they hold after the hands got thinner."""
    return r - (PALM_T_V1 - PALM_T) / 2


def mirror(side):
    return 1.0 if side == "R" else -1.0          # right hand: thumb towards -X


def knuckle_x(side, finger, variant="wide"):
    """x of a finger's centre line in the hand frame (index next to the thumb)."""
    k = ("index", "middle", "ring", "pinky").index(finger)
    w = palm_width(variant)
    return mirror(side) * (-w / 2 + w / 8 + k * w / 4)


THUMB_BASE = np.array([-0.0335, -0.020, 0.006]) * HAND_SCALE     # right hand; mirrored in x for the left


def thumb_rest(side):
    """Opposed rest frame of the thumb base joint: local -Y along the relaxed thumb, negative X rotation closes it
    across the palm."""
    s = mirror(side)
    d = norm((-0.55 * s, -0.80, 0.22))            # relaxed thumb direction
    m = np.array([1.0 * s, 0.0, 0.65])             # closing motion: across the palm towards the palm side
    m = norm(m - d * np.dot(m, d))
    x = norm(np.cross(m, d))
    y = -d
    return frame_quat(x, y, np.cross(x, y))


def joint_layout(side):
    """name -> (parent, translation, rest base rotation). Translations use the wide hand."""
    p = f"arm_{side}"
    s = mirror(side)
    out = {f"{p}_hand": (f"{p}_fore", np.array([0.0, -0.30, 0.0]), quat_identity())}
    tb = THUMB_BASE * np.array([s, 1, 1])
    out[f"{p}_thumb"] = (f"{p}_hand", tb, thumb_rest(side))
    out[f"{p}_thumb2"] = (f"{p}_thumb", np.array([0.0, -LENGTHS["thumb"][0], 0.0]), quat_identity())
    for f in ("index", "middle", "ring", "pinky"):
        out[f"{p}_{f}"] = (f"{p}_hand", np.array([knuckle_x(side, f), -PALM_LEN, 0.0]), quat_identity())
        out[f"{p}_{f}2"] = (f"{p}_{f}", np.array([0.0, -LENGTHS[f][0], 0.0]), quat_identity())
    return out


# --------------------------------------------------------------------------------------------- poses
# (curl of the first segment, curl of the second) in degrees; negative closes towards the palm
POSES = {
    "relaxed": {"thumb": (-8, -6), "index": (-18, -14), "middle": (-22, -18), "ring": (-26, -20), "pinky": (-30, -22)},
    "grip": {"thumb": (-48, -28), "index": (-82, -70), "middle": (-86, -76), "ring": (-86, -78), "pinky": (-84, -76)},
    "trigger": {"thumb": (-48, -28), "index": (-42, -38), "middle": (-86, -76), "ring": (-86, -78), "pinky": (-84, -76)},
    "indexed": {"thumb": (-48, -28), "index": (-6, -4), "middle": (-86, -76), "ring": (-86, -78), "pinky": (-84, -76)},
    "pinch": {"thumb": (-46, -12), "index": (-58, -48), "middle": (-74, -66), "ring": (-78, -70), "pinky": (-80, -72)},
    "spread": {"thumb": (10, 4), "index": (8, 2), "middle": (8, 2), "ring": (8, 2), "pinky": (10, 3)},
    "press": {"thumb": (-18, 6), "index": (-82, -70), "middle": (-86, -76), "ring": (-86, -78), "pinky": (-84, -76)},
    "mag": {"thumb": (-55, -30), "index": (-38, -30), "middle": (-72, -62), "ring": (-76, -66), "pinky": (-78, -68)},
    "fist": {"thumb": (-58, -42), "index": (-92, -88), "middle": (-94, -90), "ring": (-94, -90), "pinky": (-92, -88)},
    "support": {"thumb": (-6, -4), "index": (-70, -62), "middle": (-74, -66), "ring": (-76, -68), "pinky": (-76, -70)},
    # rifle hands
    "handguard": {"thumb": (-14, -8), "index": (-50, -46), "middle": (-56, -52), "ring": (-60, -56),
                  "pinky": (-64, -58)},
    "hook": {"thumb": (-36, -14), "index": (-74, -78), "middle": (-76, -80), "ring": (-84, -82), "pinky": (-86, -84)},
    "rifle_mag": {"thumb": (-10, -6), "index": (-46, -40), "middle": (-58, -50), "ring": (-64, -56),
                  "pinky": (-70, -60)},
    # AK pistol grip (fitted to the rifle's long trigger reach): finger resting on the guard / pad on the trigger
    "ak_indexed": {"thumb": (-48, -28), "index": (8, -30), "middle": (-86, -76), "ring": (-86, -78),
                   "pinky": (-84, -76)},
    "ak_trigger": {"thumb": (-48, -28), "index": (8, -61), "middle": (-86, -76), "ring": (-86, -78),
                   "pinky": (-84, -76)},
    # bolt-action knob: index and middle hooked round the front of the ball, thumb pressing from behind
    "bolt_pinch": {"thumb": (-36, -18), "index": (-58, -62), "middle": (-66, -70), "ring": (-82, -80),
                   "pinky": (-86, -82)},
}


def blend(weights):
    """weights: {pose_name or dict: w} -> curls dict (weighted average)."""
    tot = sum(w for w in weights.values() if w > 0)
    out = {f: np.zeros(2) for f in FINGERS}
    for pose, w in weights.items():
        if w <= 0:
            continue
        p = POSES[pose] if isinstance(pose, str) else pose
        for f in FINGERS:
            out[f] += np.asarray(p[f], float) * (w / tot)
    return out


def adjust(curls, **delta):
    """Add per-finger offsets, e.g. adjust(c, thumb=(10, 0))."""
    out = {f: np.array(v, float) for f, v in curls.items()}
    for f, d in delta.items():
        out[f] = out[f] + np.asarray(d, float)
    return out


def rx(deg):
    return quat_axis_angle((1, 0, 0), deg)


def finger_trs(side, curls):
    """Local rotations of the ten finger joints."""
    lay = joint_layout(side)
    out = {}
    for f in FINGERS:
        c1, c2 = curls[f]
        base = lay[f"arm_{side}_{f}"][2]
        out[f"arm_{side}_{f}"] = {"rotation": quat_mul(base, rx(c1))}
        out[f"arm_{side}_{f}2"] = {"rotation": rx(c2)}
    return out


# ---------------------------------------------------------------------------------------- palm frames
def palm_frame(fwd, thumb, side):
    """Palm orientation from the wrist->knuckles direction and the direction the thumb side points to."""
    y = -norm(fwd)
    t = np.asarray(thumb, float)
    t = norm(t - np.dot(t, y) * y)
    x = -t if side == "R" else t
    return frame_quat(x, y, np.cross(x, y))


def nlerp_quats(qs, ws):
    ref = qs[0]
    acc = np.zeros(4)
    for q, w in zip(qs, ws):
        acc += w * (q if np.dot(q, ref) >= 0 else -q)
    return quat_normalize(acc)


def hand_local(q_fore_world, q_palm_world):
    return quat_mul(quat_conj(q_fore_world), q_palm_world)


# -------------------------------------------------------------------------------------------- meshes
def _box_uv(m, x0, x1, y0, y1, z0, z1, mat, uv):
    """Box with per-face UV rectangles: uv[face] = (u_a, v_a, u_b, v_b) with face corner conventions:
    +x/-x faces: u_a at the +z/-z edge rule below, v_a at the joint (+y) end."""
    s = 1.0 / 64.0

    def quad(p, r):
        m.add_poly(p, mat, uvs=[(a * s, b * s) for a, b in r])

    if "fz" in uv:      # -Z (back of hand): +X at u_a ... -X at u_b, joint end at v_a
        ua, va, ub, vb = uv["fz"]
        quad([(x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0)], [(ub, va), (ua, va), (ua, vb), (ub, vb)])
    if "bz" in uv:      # +Z (palm side): -X at u_a ... +X at u_b
        ua, va, ub, vb = uv["bz"]
        quad([(x1, y1, z1), (x0, y1, z1), (x0, y0, z1), (x1, y0, z1)], [(ub, va), (ua, va), (ua, vb), (ub, vb)])
    if "px" in uv:      # +X face: +Z (palm) edge at u_a, -Z at u_b
        ua, va, ub, vb = uv["px"]
        quad([(x1, y1, z0), (x1, y1, z1), (x1, y0, z1), (x1, y0, z0)], [(ub, va), (ua, va), (ua, vb), (ub, vb)])
    if "nx" in uv:      # -X face: -Z edge at u_a, +Z at u_b
        ua, va, ub, vb = uv["nx"]
        quad([(x0, y1, z1), (x0, y1, z0), (x0, y0, z0), (x0, y0, z1)], [(ub, va), (ua, va), (ua, vb), (ub, vb)])
    if "ny" in uv:      # far end (-Y)
        ua, va, ub, vb = uv["ny"]
        quad([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], [(ub, vb), (ua, vb), (ua, va), (ub, va)])
    if "py" in uv:      # joint end (+Y)
        ua, va, ub, vb = uv["py"]
        quad([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], [(ub, vb), (ua, vb), (ua, va), (ub, va)])


def _panels(side, variant):
    u0, v0 = SKIN_OFFSETS[side]
    w = 4 if variant == "wide" else 3
    d = 4
    return u0, v0, w, d


# the palm's start blends half into the forearm (VIEWMODEL_SPEC section 7: the forearm's end blends half into the hand,
# so both meet at the half-angle); rigid on the hand beyond PALM_BLEND
PALM_TOP = 0.004
PALM_BLEND = 0.013
PALM_RINGS = (PALM_TOP, -0.0045, -PALM_BLEND, -PALM_LEN)


def palm_fore_weight(y):
    """Forearm weight of a palm vertex at bind height y (hand joint space)."""
    u = min(max(y / -PALM_BLEND, 0.0), 1.0)
    return 0.5 * (1.0 - u * u * (3 - 2 * u))


def hand_mesh(side, variant):
    """Palm box (4 px wide, 2 px thick, 4 px long) mapped to the lowest four rows of the arm skin; split in rings
    (PALM_RINGS) so its wrist end can bend with the forearm."""
    u0, v0, w, d = _panels(side, variant)
    W = palm_width(variant)
    r0, r1 = v0 + d + 8, v0 + d + 12
    row = lambda y: r0 + (r1 - r0) * (PALM_TOP - y) / (PALM_TOP + PALM_LEN)
    m = Mesh()
    last = len(PALM_RINGS) - 2
    for k, (ya, yb) in enumerate(zip(PALM_RINGS, PALM_RINGS[1:])):
        ra, rb = row(ya), row(yb)
        uv = {
            "fz": (u0 + d, ra, u0 + d + w, rb),                       # front panel
            "bz": (u0 + 2 * d + w, ra, u0 + 2 * d + 2 * w, rb),       # back panel
            "px": (u0 + 1, ra, u0 + 3, rb),                           # panel 1, middle columns
            "nx": (u0 + d + w + 1, ra, u0 + d + w + 3, rb),           # panel 3, middle columns
        }
        if k == last:
            uv["ny"] = (u0 + d + w, v0 + 1, u0 + d + 2 * w, v0 + 3)  # bottom face
        if k == 0:
            uv["py"] = (u0 + d + w, v0 + 1, u0 + d + 2 * w, v0 + 3)
        _box_uv(m, -W / 2, W / 2, yb, ya, -PALM_T / 2, PALM_T / 2, "skin", uv)
    return m


def finger_mesh(side, finger, seg, variant):
    """One finger segment in its joint's space (y from 0 to -length), 1 px wide, lowest skin rows."""
    u0, v0, w, d = _panels(side, variant)
    W = palm_width(variant)
    fw = (W / 4 if finger != "thumb" else 1.15 * HPX) - 0.0018 * HAND_SCALE
    L = LENGTHS[finger][0 if seg == 1 else 1]
    if finger == "thumb":
        dx = 0.0 if variant == "wide" else mirror(side) * (palm_width("wide") - W) / 2 * 0.8
        cf = (u0 + d + w - 1, u0 + d + w) if side == "R" else (u0 + d, u0 + d + 1)
        cb = (u0 + 2 * d + w, u0 + 2 * d + w + 1) if side == "R" else (u0 + 2 * d + 2 * w - 1, u0 + 2 * d + 2 * w)
    else:
        k = ("index", "middle", "ring", "pinky").index(finger)
        dx = knuckle_x(side, finger, variant) - knuckle_x(side, finger, "wide")
        # column on the front panel counted from its +X edge, on the back panel from its -X edge
        x_c = knuckle_x(side, finger, variant)
        cf_i = (W / 2 - x_c) / W * w
        cb_i = (x_c + W / 2) / W * w
        cf = (u0 + d + cf_i - 0.5 * w / 4, u0 + d + cf_i + 0.5 * w / 4)
        cb = (u0 + 2 * d + w + cb_i - 0.5 * w / 4, u0 + 2 * d + w + cb_i + 0.5 * w / 4)
    rows = (v0 + d + 10, v0 + d + 12) if seg == 1 else (v0 + d + 11, v0 + d + 12)
    m = Mesh()
    side_col = (u0 + 1.5, u0 + 2.5)
    _box_uv(m, dx - fw / 2, dx + fw / 2, -L, 0.0015, -FINGER_T / 2, FINGER_T / 2, "skin", {
        "fz": (cf[0], rows[0], cf[1], rows[1]),
        "bz": (cb[0], rows[0], cb[1], rows[1]),
        "px": (side_col[0], rows[0], side_col[1], rows[1]),
        "nx": (u0 + d + w + 1.5, rows[0], u0 + d + w + 2.5, rows[1]),
        "ny": (u0 + d + w + (cf[0] - u0 - d), v0 + 1.5, u0 + d + w + (cf[1] - u0 - d), v0 + 2.5),
        "py": (u0 + d + w + (cf[0] - u0 - d), v0 + 1.5, u0 + d + w + (cf[1] - u0 - d), v0 + 2.5),
    })
    if finger == "thumb" and dx:
        lay = joint_layout(side)
        q = lay[f"arm_{side}_thumb"][2]
        from vmath import quat_rotate
        off = quat_rotate(quat_conj(q), (dx, 0.0, 0.0))     # hand-frame shift seen from the thumb joints
        m = m.translated(off - np.array([dx, 0, 0]))
    return m


def mesh_joints(side):
    """(mesh suffix, joint name, builder) for the hand meshes of one side."""
    out = [("hand", f"arm_{side}_hand", lambda v: hand_mesh(side, v))]
    for f in FINGERS:
        for seg, suffix in ((1, ""), (2, "2")):
            out.append((f"{f}{suffix}", f"arm_{side}_{f}{suffix}",
                        (lambda f_, s_: (lambda v: finger_mesh(side, f_, s_, v)))(f, seg)))
    return out


def fingertips(side, wrist, q_palm, curls):
    """World positions of the fingertips (end of the second segment) for a hand target: forward kinematics."""
    from vmath import quat_rotate
    lay = joint_layout(side)
    tr = finger_trs(side, curls)
    out = {}
    for f in FINGERS:
        _, t1, _ = lay[f"arm_{side}_{f}"]
        q1 = quat_mul(q_palm, tr[f"arm_{side}_{f}"]["rotation"])
        p1 = np.asarray(wrist, float) + quat_rotate(q_palm, t1)
        q2 = quat_mul(q1, tr[f"arm_{side}_{f}2"]["rotation"])
        p2 = p1 + quat_rotate(q1, (0.0, -LENGTHS[f][0], 0.0))
        out[f] = p2 + quat_rotate(q2, (0.0, -LENGTHS[f][1], 0.0))
    return out


def fit_finger(side, wrist, q_palm, curls, finger, target, ranges=((-100, 40), (-90, 20)), step=4):
    """Brute-force the two curls of one finger so its tip lands as close as possible to `target`."""
    best = (1e9, curls[finger])
    for c1 in np.arange(ranges[0][0], ranges[0][1] + 1e-6, step):
        for c2 in np.arange(ranges[1][0], ranges[1][1] + 1e-6, step):
            trial = dict(curls)
            trial[finger] = (c1, c2)
            d = np.linalg.norm(fingertips(side, wrist, q_palm, trial)[finger] - target)
            if d < best[0]:
                best = (d, (c1, c2))
    return best
