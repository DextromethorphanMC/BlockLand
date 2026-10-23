"""Quadric edge-collapse decimation (Garland & Heckbert) for converted source models (VIEWMODEL_SPEC section 24):
open borders and creases are kept by boundary quadrics, collapses that flip a triangle are refused.

decimate(V, F, target) -> (V2, F2) with at most `target` triangles (fewer only when nothing more can go)."""
import heapq

import numpy as np


def _plane_quadric(n, p):
    d = -float(np.dot(n, p))
    q = np.array([n[0], n[1], n[2], d])
    return np.outer(q, q)


def weld(V, F, eps=1e-6):
    """Merge vertices closer than eps (source meshes split them along UV seams)."""
    key = np.round(np.asarray(V, float) / eps).astype(np.int64)
    _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
    inv = inv.reshape(-1)
    F2 = inv[np.asarray(F, np.int64)]
    keep = (F2[:, 0] != F2[:, 1]) & (F2[:, 1] != F2[:, 2]) & (F2[:, 0] != F2[:, 2])
    return np.asarray(V, float)[first], F2[keep]


def decimate(V, F, target, border_weight=50.0, max_cost=None):
    V, F = weld(V, F)
    V = V.copy()
    F = [list(f) for f in F]
    if len(F) <= target:
        return V, np.array(F, np.int64).reshape(-1, 3)
    n = len(V)
    alive_f = [True] * len(F)
    vf = [set() for _ in range(n)]
    for fi, f in enumerate(F):
        for v in f:
            vf[v].add(fi)
    Q = np.zeros((n, 4, 4))

    def face_normal(f):
        a, b, c = V[f[0]], V[f[1]], V[f[2]]
        nn = np.cross(b - a, c - a)
        ln = np.linalg.norm(nn)
        return (nn / ln if ln > 1e-18 else None), ln * 0.5

    for f in F:
        nn, area = face_normal(f)
        if nn is None:
            continue
        K = _plane_quadric(nn, V[f[0]]) * max(area, 1e-12)
        for v in f:
            Q[v] += K
    # open borders: a plane through the edge perpendicular to its face keeps the outline in place
    edge_faces = {}
    for fi, f in enumerate(F):
        for k in range(3):
            a, b = f[k], f[(k + 1) % 3]
            edge_faces.setdefault((min(a, b), max(a, b)), []).append(fi)
    for (a, b), fs in edge_faces.items():
        if len(fs) != 1:
            continue
        nn, _ = face_normal(F[fs[0]])
        if nn is None:
            continue
        e = V[b] - V[a]
        ln = np.linalg.norm(e)
        if ln < 1e-12:
            continue
        pn = np.cross(e / ln, nn)
        K = _plane_quadric(pn / np.linalg.norm(pn), V[a]) * border_weight * ln * ln
        Q[a] += K
        Q[b] += K

    version = [0] * n
    alive_v = [True] * n
    heap = []

    def best(a, b):
        q = Q[a] + Q[b]
        cands = [V[a], V[b], 0.5 * (V[a] + V[b])]
        A = q.copy()
        A[3] = [0, 0, 0, 1]
        if abs(np.linalg.det(A)) > 1e-14:
            p = np.linalg.solve(A, [0, 0, 0, 1])[:3]
            if np.linalg.norm(p - 0.5 * (V[a] + V[b])) < 2.0 * np.linalg.norm(V[a] - V[b]) + 1e-9:
                cands.append(p)
        best_c, best_p = None, None
        for p in cands:
            h = np.append(p, 1.0)
            c = float(h @ q @ h)
            if best_c is None or c < best_c:
                best_c, best_p = c, p
        return best_c, best_p

    def push(a, b):
        c, p = best(a, b)
        heapq.heappush(heap, (c, a, b, version[a], version[b], tuple(p)))

    for (a, b) in edge_faces:
        push(a, b)
    faces = sum(alive_f)
    while faces > target and heap:
        c, a, b, va, vb, p = heapq.heappop(heap)
        if not (alive_v[a] and alive_v[b]) or version[a] != va or version[b] != vb:
            continue
        if max_cost is not None and c > max_cost:
            break
        p = np.array(p)
        shared = vf[a] & vf[b]
        # refuse collapses that flip or squash a surviving neighbour
        ok = True
        for fi in (vf[a] | vf[b]) - shared:
            f = F[fi]
            before, _ = face_normal(f)
            pts = [p if v in (a, b) else V[v] for v in f]
            nn = np.cross(pts[1] - pts[0], pts[2] - pts[0])
            ln = np.linalg.norm(nn)
            if before is None or ln < 1e-16 or np.dot(nn / ln, before) < 0.2:
                ok = False
                break
        # keep the mesh manifold-ish: the two ends may share at most the faces of the edge's own wings
        if ok:
            na = {v for fi in vf[a] for v in F[fi]}
            nb = {v for fi in vf[b] for v in F[fi]}
            if len(na & nb) - 2 > len(shared):
                ok = False
        if not ok:
            continue
        for fi in shared:
            alive_f[fi] = False
            faces -= 1
            for v in F[fi]:
                vf[v].discard(fi)
        for fi in list(vf[b]):
            F[fi] = [a if v == b else v for v in F[fi]]
            vf[a].add(fi)
        vf[b] = set()
        alive_v[b] = False
        V[a] = p
        Q[a] = Q[a] + Q[b]
        version[a] += 1
        nbrs = {v for fi in vf[a] for v in F[fi] if v != a}
        for v in nbrs:
            push(min(a, v), max(a, v))
    keep = [F[i] for i in range(len(F)) if alive_f[i]]
    F2 = np.array(keep, np.int64).reshape(-1, 3)
    used = np.unique(F2)
    remap = -np.ones(n, np.int64)
    remap[used] = np.arange(len(used))
    return V[used], remap[F2]
