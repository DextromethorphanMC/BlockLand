"""Python mirror of the runtime support-hand hold (VIEWMODEL_SPEC section 35, client/viewmodel/SupportGrip): the left
hand moved onto an underbarrel attachment's hold (underbarrel_models.grip_holds, craft/attachments.json `grip`) in
whatever pose a clip gives, for checks (check_attach) and previews. Same maths as the Java, step by step."""
import numpy as np

import hands
from glbread import trs
from vmath import mat_to_quat, quat_conj, quat_mul, quat_rotate, quat_slerp

NEAR, FAR = 0.025, 0.07          # the clip's hand within NEAR of its idle hold (mount space) holds, beyond FAR it does not
REACH, MAX_SHIFT = 0.985, 0.15   # out of reach: the shoulder comes forward, at most MAX_SHIFT


def _rot(M):
    R = M[:3, :3].copy()
    for k in range(3):
        R[:, k] /= np.linalg.norm(R[:, k])
    return R


def _between(a, b):
    a, b = a / np.linalg.norm(a), b / np.linalg.norm(b)
    c = float(np.dot(a, b))
    if c < -0.999999:
        axis = np.cross(a, (1, 0, 0) if abs(a[0]) < 0.9 else (0, 1, 0))
        return np.array([*(axis / np.linalg.norm(axis)), 0.0])
    v = np.cross(a, b)
    q = np.array([v[0], v[1], v[2], 1 + c])
    return q / np.linalg.norm(q)


def weight(d):
    t = np.clip((d - NEAR) / (FAR - NEAR), 0, 1)
    return 1 - t * t * (3 - 2 * t)


def apply(g, sample, idle0, hold, mount="mount_underbarrel"):
    """-> (overrides for g.world_matrices, weight, the clip's hand distance from its idle hold, shoulder shift).
    sample / idle0: g.sample(...) of the clip and of the idle's first frame; hold: (wrist, palm quat, curls)."""
    W = g.world_matrices(sample)
    I = g.world_matrices(idle0)
    n = g.names
    mt, up, fore, hand = n[mount], n["arm_L"], n["arm_L_fore"], n["arm_L_hand"]
    H0 = np.linalg.inv(I[mt]) @ I[hand]
    Hc = np.linalg.inv(W[mt]) @ W[hand]
    d = float(np.linalg.norm(Hc[:3, 3] - H0[:3, 3]))
    w = weight(d)
    ov = {k: dict(v) for k, v in sample.items()}
    if w <= 0.001:
        return ov, 0.0, d, 0.0
    wrist, q, curls = hold
    D = W[mt] @ trs(wrist, q, (1, 1, 1)) @ np.linalg.inv(H0) @ Hc
    P, qh = D[:3, 3], mat_to_quat(_rot(D))
    S, E, Hp = W[up][:3, 3], W[fore][:3, 3], W[hand][:3, 3]
    l1, l2 = np.linalg.norm(E - S), np.linalg.norm(Hp - E)
    dist = np.linalg.norm(P - S)
    u = (P - S) / dist
    shift = float(np.clip(dist - REACH * (l1 + l2), 0.0, MAX_SHIFT))
    S, E = S + u * shift, E + u * shift
    dist = np.clip(dist - shift, abs(l1 - l2) + 1e-4, (l1 + l2) * 0.999)
    a = (l1 * l1 - l2 * l2 + dist * dist) / (2 * dist)
    h = np.sqrt(max(l1 * l1 - a * a, 0.0))
    side = (E - S) - u * np.dot(E - S, u)
    if np.dot(side, side) < 1e-10:
        side = np.array([0.0, -1.0, 0.0]) + u * u[1]
    side /= np.linalg.norm(side)
    elbow = S + u * a + side * h
    qu = quat_mul(_between(E - S, elbow - S), mat_to_quat(_rot(W[up])))
    qf = quat_mul(_between(quat_rotate(qh, (0, -1, 0)), P - elbow), qh)
    parent = g.parent[up]
    qp = mat_to_quat(_rot(W[parent]))
    for j, ql in ((up, quat_mul(quat_conj(qp), qu)), (fore, quat_mul(quat_conj(qu), qf)), (hand, quat_mul(quat_conj(qf), qh))):
        cur = np.asarray(sample.get(j, {}).get("rotation", g.local_trs(j)[1]), float)
        ov.setdefault(j, {})["rotation"] = quat_slerp(cur, ql, w)
    if shift > 0:
        cur = np.asarray(sample.get(up, {}).get("translation", g.local_trs(up)[0]), float)
        new = (np.linalg.inv(W[parent]) @ np.append(S, 1.0))[:3]
        ov[up]["translation"] = cur + (new - cur) * w
    lay = hands.joint_layout("L")
    for f in hands.FINGERS:
        for seg, sfx in ((0, ""), (1, "2")):
            name = f"arm_L_{f}{sfx}"
            j = n.get(name)
            if j is None:
                continue
            cur = np.asarray(sample.get(j, {}).get("rotation", g.local_trs(j)[1]), float)
            idl = np.asarray(idle0.get(j, {}).get("rotation", g.local_trs(j)[1]), float)
            held = quat_mul(lay[name][2] if seg == 0 else np.array([0.0, 0.0, 0.0, 1.0]), hands.rx(curls[f][seg]))
            # the clip's deviation from its idle curl, opening only: the attachment stops a clenching finger
            dq = quat_mul(quat_conj(idl), cur)
            dq = dq if dq[3] >= 0 else -dq
            opened = max(2 * np.degrees(np.arctan2(dq[0], dq[3])), 0.0)
            ov.setdefault(j, {})["rotation"] = quat_slerp(cur, quat_mul(held, hands.rx(opened)), w)
    return ov, w, d, shift


def hand_points(g, arms, W):
    """The left hand's skin (palm and fingers, arms.glb wide meshes) under the world matrices W."""
    out = []
    for node in arms.nodes:
        ex = node.get("extras", {})
        if "mesh" in node and ex.get("variant") == "wide" and ex.get("layer") == "base" \
                and str(ex.get("joint", "")).startswith("arm_L_") and ex.get("joint") != "arm_L_fore":
            import armskin                 # the palm is skinned (its wrist end bends with the forearm)
            out.extend(pos for _, pos, _ in armskin.mesh_world(arms, node, g, W))
    return np.concatenate(out)


def surface(a, M):
    """The triangles (T, 3, 3) of an attachment model under matrix M."""
    out = []
    Aw = a.world_matrices({})
    for i, nd in enumerate(a.nodes):
        if "mesh" not in nd:
            continue
        T = M @ Aw[i]
        for p in a.mesh_primitives(nd["mesh"]):
            pos = p["positions"] @ T[:3, :3].T + T[:3, 3]
            idx = np.asarray(p["indices"]).reshape(-1, 3) if p.get("indices") is not None else                 np.arange(len(pos)).reshape(-1, 3)
            out.append(pos[idx])
    return np.concatenate(out)


def _winding(P, tri):
    """Generalised winding number of points P (N, 3) w.r.t. triangles (T, 3, 3): about 1 inside a closed part."""
    a, b, c = (tri[None, :, k, :] - P[:, None, :] for k in range(3))
    la, lb, lc = (np.linalg.norm(v, axis=-1) for v in (a, b, c))
    det = np.einsum("ntk,ntk->nt", a, np.cross(b, c))
    den = la * lb * lc + np.einsum("ntk,ntk->nt", a, b) * lc + np.einsum("ntk,ntk->nt", b, c) * la +         np.einsum("ntk,ntk->nt", c, a) * lb
    return np.arctan2(det, den).sum(1) / (2 * np.pi)


def _distance(P, tri):
    """Distance from each point to the nearest triangle (sampled densely: vertices, edges, a 6x6 barycentric grid)."""
    u, v = np.meshgrid(np.linspace(0, 1, 7), np.linspace(0, 1, 7))
    keep = u + v <= 1 + 1e-9
    u, v = u[keep], v[keep]
    S = tri[:, 0, None, :] + u[None, :, None] * (tri[:, 1, None, :] - tri[:, 0, None, :]) +         v[None, :, None] * (tri[:, 2, None, :] - tri[:, 0, None, :])
    S = S.reshape(-1, 3)
    return np.sqrt(((P[:, None, :] - S[None, :, :]) ** 2).sum(-1)).min(1)


def gap(hand, tri):
    """(closest distance from the hand's skin to the attachment, deepest hand point inside it: 0 = none inside)."""
    lo, hi = tri.reshape(-1, 3).min(0) - 0.03, tri.reshape(-1, 3).max(0) + 0.03
    H = hand[np.all((hand >= lo) & (hand <= hi), axis=1)]
    if not len(H):
        return np.inf, 0.0
    dist = _distance(H, tri)
    inside = np.abs(_winding(H, tri)) > 0.5
    return float(dist[~inside].min()) if (~inside).any() else 0.0, float(dist[inside].max()) if inside.any() else 0.0
