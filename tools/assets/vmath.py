"""Small vector / quaternion helpers. Quaternions are numpy arrays (x, y, z, w) like glTF."""
import numpy as np


def norm(v):
    v = np.asarray(v, dtype=float)
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


def quat_identity():
    return np.array([0.0, 0.0, 0.0, 1.0])


def quat_axis_angle(axis, angle_deg):
    axis = norm(axis)
    h = np.radians(angle_deg) * 0.5
    s = np.sin(h)
    return np.array([axis[0] * s, axis[1] * s, axis[2] * s, np.cos(h)])


def quat_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ])


def quat_conj(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])


def quat_normalize(q):
    q = np.asarray(q, dtype=float)
    return q / np.linalg.norm(q)


def quat_to_mat(q):
    x, y, z, w = quat_normalize(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def mat_to_quat(m):
    m = np.asarray(m, dtype=float)
    tr = m[0, 0] + m[1, 1] + m[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        q = [(m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    q = quat_normalize(q)
    return q if q[3] >= 0 else -q


def quat_rotate(q, v):
    return quat_to_mat(q) @ np.asarray(v, dtype=float)


def quat_slerp(a, b, t):
    a = quat_normalize(a)
    b = quat_normalize(b)
    d = float(np.dot(a, b))
    if d < 0:
        b, d = -b, -d
    if d > 0.9995:
        return quat_normalize(a + (b - a) * t)
    th = np.arccos(d)
    return (np.sin((1 - t) * th) * a + np.sin(t * th) * b) / np.sin(th)


def quat_euler(yaw=0.0, pitch=0.0, roll=0.0):
    """Ry(yaw) * Rx(pitch) * Rz(roll), degrees. Yaw + turns -Z towards -X, pitch + lifts -Z up,
    roll + leans +Y towards -X."""
    return quat_mul(quat_mul(quat_axis_angle((0, 1, 0), yaw), quat_axis_angle((1, 0, 0), pitch)),
                    quat_axis_angle((0, 0, 1), roll))


def frame_quat(x_axis, y_axis, z_axis):
    """Quaternion of the rotation whose columns are the given (orthonormal) local axes."""
    return mat_to_quat(np.column_stack([x_axis, y_axis, z_axis]))


def trs_matrix(t=None, q=None, s=None):
    m = np.eye(4)
    r = quat_to_mat(q) if q is not None else np.eye(3)
    sc = np.asarray(s, dtype=float) if s is not None else np.ones(3)
    m[:3, :3] = r * sc[None, :]
    if t is not None:
        m[:3, 3] = t
    return m


def transform_points(m, pts):
    pts = np.asarray(pts, dtype=float)
    return pts @ m[:3, :3].T + m[:3, 3]


def make_continuous(quats):
    """Flip signs so consecutive quaternions are in the same hemisphere (shortest slerp)."""
    out = [np.asarray(quats[0], dtype=float)]
    for q in quats[1:]:
        q = np.asarray(q, dtype=float)
        out.append(-q if np.dot(out[-1], q) < 0 else q)
    return out
