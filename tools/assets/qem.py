"""Mesh decimation by quadric error metrics (Garland & Heckbert): edge collapses in order of the error they add,
to the optimal point of the summed plane quadrics. Open boundaries are kept (penalty planes along them), collapses that
would flip a neighbouring triangle or create a sliver are refused. For converted source models that are heavier than
the triangle budget of VIEWMODEL_SPEC Round G (section 24 preamble).

simplify(positions (V, 3), triangles (T, 3), target_triangles) -> (positions, triangles)"""
import heapq

import numpy as np


def _plane_quadric(p0, p1, p2):
    n = np.cross(p1 - p0, p2 - p0)
    area = np.linalg.norm(n)
    if area < 1e-20:
        return np.zeros((4, 4)), 0.0
    n = n / area
    d = -np.dot(n, p0)
    v = np.array([n[0], n[1], n[2], d])
    return np.outer(v, v) * (0.5 * area), area


def _error(Q, p):
    v = np.array([p[0], p[1], p[2], 1.0])
    return float(v @ Q @ v)


def simplify(positions, triangles, target, boundary_weight=1000.0, min_quality=0.08):
    P = np.array(positions, float)
    T = [list(map(int, t)) for t in triangles]
    alive = [True] * len(T)
    count = len(T)
    if count <= target:
        return P, np.array(T, np.int64)
    Q = np.zeros((len(P), 4, 4))
    vfaces = [set() for _ in range(len(P))]
    for f, (a, b, c) in enumerate(T):
        q, _ = _plane_quadric(P[a], P[b], P[c])
        for v in (a, b, c):
            Q[v] += q
            vfaces[v].add(f)
    # boundary edges (used by one triangle): a plane through the edge, perpendicular to its face, keeps it in place
    edge_use = {}
    for f, (a, b, c) in enumerate(T):
        for u, w in ((a, b), (b, c), (c, a)):
            edge_use.setdefault((min(u, w), max(u, w)), []).append(f)
    for (u, w), fs in edge_use.items():
        if len(fs) == 1:
            a, b, c = T[fs[0]]
            n = np.cross(P[b] - P[a], P[c] - P[a])
            e = P[w] - P[u]
            m = np.cross(e, n)
            ln = np.linalg.norm(m)
            if ln < 1e-20:
                continue
            m /= ln
            v = np.array([m[0], m[1], m[2], -np.dot(m, P[u])])
            q = np.outer(v, v) * boundary_weight * float(np.dot(e, e))
            Q[u] += q
            Q[w] += q
    version = [0] * len(P)
    removed = [False] * len(P)

    def best(u, w):
        Qs = Q[u] + Q[w]
        A = Qs.copy()
        A[3] = [0, 0, 0, 1]
        cands = [P[u], P[w], 0.5 * (P[u] + P[w])]
        try:
            if abs(np.linalg.det(A)) > 1e-18:
                x = np.linalg.solve(A, np.array([0, 0, 0, 1.0]))[:3]
                if np.linalg.norm(x - 0.5 * (P[u] + P[w])) < 2.0 * np.linalg.norm(P[u] - P[w]) + 1e-9:
                    cands.insert(0, x)
        except np.linalg.LinAlgError:
            pass
        errs = [_error(Qs, c) for c in cands]
        k = int(np.argmin(errs))
        return errs[k], cands[k]

    heap = []

    def push(u, w):
        if u == w or removed[u] or removed[w]:
            return
        e, p = best(u, w)
        heapq.heappush(heap, (e, u, w, version[u], version[w], tuple(p)))

    for (u, w) in edge_use:
        push(u, w)

    def quality(a, b, c):
        e = np.cross(b - a, c - a)
        area = np.linalg.norm(e)
        s = np.sum((b - a) ** 2) + np.sum((c - b) ** 2) + np.sum((a - c) ** 2)
        return 4 * np.sqrt(3) * 0.5 * area / max(s, 1e-30)

    def valid(u, w, p):
        for v, other in ((u, w), (w, u)):
            for f in vfaces[v]:
                if not alive[f] or other in T[f]:
                    continue
                tri = [P[x] for x in T[f]]
                old = np.cross(tri[1] - tri[0], tri[2] - tri[0])
                new_tri = [p if x == v else P[x] for x in T[f]]
                new = np.cross(new_tri[1] - new_tri[0], new_tri[2] - new_tri[0])
                if np.dot(old, new) <= 0.2 * np.linalg.norm(old) * np.linalg.norm(new):
                    return False
                if quality(*new_tri) < min_quality and quality(*tri) >= min_quality:
                    return False
        # no non-manifold collapse: the two vertices share exactly the neighbours of their common faces
        nu = {x for f in vfaces[u] if alive[f] for x in T[f]}
        nw = {x for f in vfaces[w] if alive[f] for x in T[f]}
        shared = (nu & nw) - {u, w}
        common = [f for f in vfaces[u] if alive[f] and w in T[f]]
        return len(shared) <= len(common)

    while count > target and heap:
        e, u, w, vu, vw, p = heapq.heappop(heap)
        if removed[u] or removed[w] or version[u] != vu or version[w] != vw:
            continue
        p = np.array(p)
        if not valid(u, w, p):
            continue
        # collapse w into u
        P[u] = p
        Q[u] = Q[u] + Q[w]
        removed[w] = True
        for f in list(vfaces[w]):
            if not alive[f]:
                continue
            if u in T[f]:
                alive[f] = False
                count -= 1
                for x in T[f]:
                    vfaces[x].discard(f)
            else:
                T[f] = [u if x == w else x for x in T[f]]
                vfaces[u].add(f)
        vfaces[w] = set()
        version[u] += 1
        for x in {x for f in vfaces[u] if alive[f] for x in T[f]} - {u}:
            push(u, x)
    tris = np.array([t for t, a in zip(T, alive) if a], np.int64)
    used, remap = np.unique(tris, return_inverse=True)
    return P[used], remap.reshape(-1, 3)
