"""Skinned arms (VIEWMODEL_SPEC sections 7 and 16): places an arms.glb mesh on a weapon's rig the way the runtime does
(client/viewmodel/ArmSkin). A mesh node's extras name its anchor `joint` (its vertices are in that joint's space in the
bind pose: the straight arm), and a skinned mesh adds `skin` (joint names, indexed by JOINTS_0) and `bind` (each skin
joint's translation in the anchor's space in the bind pose). Vertices blend their joints with dual quaternions
(WEIGHTS_0), so the elbow and wrist bend and twist round without losing volume. A mesh without `skin` is rigid.
A skin joint named `<joint>:untwisted` is that joint with its twist about its own Y axis (relative to its parent)
taken out: the elbow blends into the untwisted forearm and the twist spreads down the forearm to the wrist, as the
radius turns about the ulna, instead of wringing the elbow."""
import numpy as np

UNTWISTED = ":untwisted"


def _quat(R):
    """Unit quaternion (x, y, z, w) of a rotation matrix (orthonormalised first)."""
    u, _, vt = np.linalg.svd(R)
    R = u @ vt
    t = np.trace(R)
    if t > 0:
        s = np.sqrt(t + 1.0) * 2
        q = [(R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s, 0.25 * s]
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        q = [0.25 * s, (R[0, 1] + R[1, 0]) / s, (R[0, 2] + R[2, 0]) / s, (R[2, 1] - R[1, 2]) / s]
    elif R[1, 1] > R[2, 2]:
        s = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        q = [(R[0, 1] + R[1, 0]) / s, 0.25 * s, (R[1, 2] + R[2, 1]) / s, (R[0, 2] - R[2, 0]) / s]
    else:
        s = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        q = [(R[0, 2] + R[2, 0]) / s, (R[1, 2] + R[2, 1]) / s, 0.25 * s, (R[1, 0] - R[0, 1]) / s]
    q = np.array(q)
    return q / np.linalg.norm(q)


def _qmul(a, b):
    """Hamilton product of (..., 4) quaternions (x, y, z, w)."""
    av, aw, bv, bw = a[..., :3], a[..., 3:], b[..., :3], b[..., 3:]
    return np.concatenate([aw * bv + bw * av + np.cross(av, bv), aw * bw - np.sum(av * bv, -1, keepdims=True)], -1)


def dual_quat(M):
    """(real, dual) quaternions of a rigid 4x4 transform."""
    r = _quat(M[:3, :3])
    d = 0.5 * _qmul(np.array([*M[:3, 3], 0.0]), r)
    return r, d


def blend(points, normals, joints, weights, real, dual):
    """Dual-quaternion blend skinning: points / normals (N, 3), joints / weights (N, 4), per-joint real / dual (J, 4)."""
    j = joints.astype(np.int64)
    w = weights.astype(np.float64)
    r = real[j]                                          # (N, 4, 4)
    d = dual[j]
    sign = np.where(np.sum(r * r[:, :1], -1) < 0, -1.0, 1.0)      # same hemisphere as the first influence
    br = np.sum(r * (w * sign)[..., None], 1)
    bd = np.sum(d * (w * sign)[..., None], 1)
    n = np.linalg.norm(br, axis=1, keepdims=True)
    br, bd = br / n, bd / n
    rv, rw, dv, dw = br[:, :3], br[:, 3:], bd[:, :3], bd[:, 3:]
    t = 2 * (rw * dv - dw * rv + np.cross(rv, dv))

    def rotate(v):
        u = 2 * np.cross(rv, v)
        return v + rw * u + np.cross(rv, u)

    pos = rotate(points) + t
    nrm = rotate(normals) if normals is not None else None
    return pos, nrm


def _rigid(M):
    return abs(np.linalg.det(M[:3, :3])) > 1e-12


def untwist(W_parent, W_joint):
    """World matrix of a joint without its twist about its local Y relative to its parent (swing only)."""
    L = np.linalg.inv(W_parent) @ W_joint
    q = _quat(L[:3, :3])
    tw = np.array([0.0, q[1], 0.0, q[3]])
    n = np.linalg.norm(tw)
    tw = tw / n if n > 1e-9 else np.array([0.0, 0.0, 0.0, 1.0])
    swing = _qmul(q, tw * np.array([-1.0, -1.0, -1.0, 1.0]))
    x, y, z, w = swing
    R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    out = L.copy()
    out[:3, :3] = R * np.linalg.norm(L[:3, :3], axis=0)
    return W_parent @ out


def joint_matrix(g, W, name, fallback):
    """World matrix of skin joint `name` on weapon g (the `:untwisted` form included); `fallback` when missing."""
    base = name[:-len(UNTWISTED)] if name.endswith(UNTWISTED) else name
    j = g.names.get(base)
    if j is None or not _rigid(W[j]):
        return fallback
    if base != name:
        p = g.parent.get(j)
        if p is not None and _rigid(W[p]):
            return untwist(W[p], W[j])
    return W[j]


def mesh_world(arms, node, g, W):
    """[(primitive, world positions, world normals)] of an arms.glb mesh node drawn on weapon `g` under its world
    matrices W (node index -> 4x4); [] when the anchor joint is missing or collapsed. Skin joints the weapon lacks (or
    that are collapsed) follow the anchor."""
    ex = node.get("extras", {})
    anchor = g.names.get(ex.get("joint"))
    if anchor is None or not _rigid(W[anchor]):
        return []
    A = W[anchor]
    prims = arms.mesh_primitives(node["mesh"])
    skin = ex.get("skin")
    real = dual = None
    if skin:
        Ainv = np.linalg.inv(A)
        rs, ds = [], []
        for name, b in zip(skin, ex.get("bind", [[0.0, 0.0, 0.0]] * len(skin))):
            M = joint_matrix(g, W, name, A)
            B = np.eye(4)
            B[:3, 3] = -np.asarray(b, float)
            r_, d_ = dual_quat(Ainv @ M @ B)
            rs.append(r_)
            ds.append(d_)
        real, dual = np.array(rs), np.array(ds)
    out = []
    for p in prims:
        pos, nrm = p["positions"], p["normals"]
        if real is not None and p.get("joints") is not None:
            pos, nrm = blend(pos, nrm, p["joints"], p["weights"], real, dual)
        R = A[:3, :3]
        wn = None
        if nrm is not None:
            wn = nrm @ np.linalg.inv(R)
            wn /= np.linalg.norm(wn, axis=1)[:, None] + 1e-30
        out.append((p, pos @ R.T + A[:3, 3], wn))
    return out
