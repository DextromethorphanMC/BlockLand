"""Polygon mesh container + export to glTF primitives (smoothing by angle with face-weighted normals, box-projected
UVs)."""
import numpy as np


class Mesh:
    """Polygon soup with shared vertex indices. Faces are CCW seen from outside."""

    def __init__(self):
        self.v = []          # [x, y, z]
        self.f = []          # tuple of vertex indices
        self.m = []          # material name per face
        self.uv = []         # None or list of (u, v) per face corner (explicit UVs)
        self.soft = []       # per face: part of a round (smoothed up to EDGES soft_deg, VIEWMODEL_SPEC section 53)

    def add_vertices(self, pts):
        base = len(self.v)
        self.v.extend([list(map(float, p)) for p in np.asarray(pts, dtype=float).reshape(-1, 3)])
        return base

    def add_face(self, idx, material, uvs=None, soft=False):
        self.f.append(tuple(int(i) for i in idx))
        self.m.append(material)
        self.uv.append(uvs)
        self.soft.append(bool(soft))

    def add_poly(self, pts, material, uvs=None, soft=False):
        base = self.add_vertices(pts)
        self.add_face(range(base, base + len(pts)), material, uvs, soft)

    def merge(self, other):
        base = len(self.v)
        self.v.extend([list(p) for p in other.v])
        soft = getattr(other, "soft", None) or []
        soft = list(soft) + [False] * (len(other.f) - len(soft))
        for f, m, uv, sf in zip(other.f, other.m, other.uv, soft):
            self.f.append(tuple(i + base for i in f))
            self.m.append(m)
            self.uv.append(uv)
            self.soft.append(sf)
        return self

    def copy(self):
        return Mesh().merge(self)

    def transformed(self, mat4):
        out = self.copy()
        if out.v:
            v = np.array(out.v)
            v = v @ mat4[:3, :3].T + mat4[:3, 3]
            out.v = v.tolist()
            if np.linalg.det(mat4[:3, :3]) < 0:   # mirrored: flip winding
                out.f = [tuple(reversed(f)) for f in out.f]
                out.uv = [list(reversed(u)) if u is not None else None for u in out.uv]
        return out

    def translated(self, d):
        m = np.eye(4)
        m[:3, 3] = d
        return self.transformed(m)

    def set_material(self, fn, material):
        """Re-assign faces where fn(center, normal) is true."""
        v = np.array(self.v)
        for i, f in enumerate(self.f):
            p = v[list(f)]
            c = p.mean(axis=0)
            n = np.zeros(3)
            for k in range(1, len(f) - 1):
                n += np.cross(p[k] - p[0], p[k + 1] - p[0])
            ln = np.linalg.norm(n)
            if ln > 0 and fn(c, n / ln):
                self.m[i] = material


# ------------------------------------------------------------------ triangulation
def _signed_area(p):
    x, y = p[:, 0], p[:, 1]
    return 0.5 * float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y))


def _point_in_tri(p, a, b, c, eps=1e-18):
    d1 = (p[0] - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (p[1] - b[1])
    d2 = (p[0] - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (p[1] - c[1])
    d3 = (p[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (p[1] - a[1])
    neg = d1 < -eps or d2 < -eps or d3 < -eps
    pos = d1 > eps or d2 > eps or d3 > eps
    return not (neg and pos)


def _same(p, q):
    return abs(p[0] - q[0]) < 1e-12 and abs(p[1] - q[1]) < 1e-12


def _bridge(poly, hole):
    """Splice a hole (CW list of (idx, (x, y))) into poly (CCW) through a visible bridge vertex."""
    m = max(range(len(hole)), key=lambda i: hole[i][1][0])
    mp = hole[m][1]
    best, best_x = None, float("inf")
    n = len(poly)
    for i in range(n):
        a, b = poly[i][1], poly[(i + 1) % n][1]
        if (a[1] - mp[1]) * (b[1] - mp[1]) > 0 or a[1] == b[1]:
            continue
        t = (mp[1] - a[1]) / (b[1] - a[1])
        x = a[0] + t * (b[0] - a[0])
        if mp[0] <= x < best_x:
            best_x = x
            best = i if a[0] > b[0] else (i + 1) % n
    if best is None:
        best = min(range(n), key=lambda i: (poly[i][1][0] - mp[0]) ** 2 + (poly[i][1][1] - mp[1]) ** 2)
    else:
        cand = poly[best][1]
        hit = (best_x, mp[1])
        dbest = (cand[0] - mp[0]) ** 2 + (cand[1] - mp[1]) ** 2
        for i in range(n):
            q = poly[i][1]
            if i != best and q[0] >= mp[0] and not _same(q, cand) and _point_in_tri(q, mp, hit, cand):
                d = (q[0] - mp[0]) ** 2 + (q[1] - mp[1]) ** 2
                if d < dbest:
                    best, dbest = i, d
    # the chosen vertex may occur several times (earlier bridges): use the copy whose wedge holds mp
    same = [j for j in range(n) if poly[j][0] == poly[best][0]]
    if len(same) > 1:
        for j in same:
            p, v, q = poly[j - 1][1], poly[j][1], poly[(j + 1) % n][1]
            l1 = (v[0] - p[0]) * (mp[1] - p[1]) - (v[1] - p[1]) * (mp[0] - p[0]) > 0
            l2 = (q[0] - v[0]) * (mp[1] - v[1]) - (q[1] - v[1]) * (mp[0] - v[0]) > 0
            convex = (v[0] - p[0]) * (q[1] - v[1]) - (v[1] - p[1]) * (q[0] - v[0]) > 0
            if (l1 and l2) if convex else (l1 or l2):
                best = j
                break
    hole_seq = hole[m:] + hole[:m + 1]
    return poly[:best + 1] + hole_seq + [poly[best]] + poly[best + 1:]


def triangulate(outer, holes=()):
    """Ear clipping of a 2D polygon with holes. Returns index triples into concat(outer, *holes)."""
    pts = [np.asarray(outer, dtype=float)] + [np.asarray(h, dtype=float) for h in holes]
    base = np.cumsum([0] + [len(p) for p in pts])
    rings = []
    for k, p in enumerate(pts):
        idx = list(range(len(p)))
        area = _signed_area(p)
        if (k == 0 and area < 0) or (k > 0 and area > 0):
            idx.reverse()
        rings.append([(int(base[k] + i), (float(p[i, 0]), float(p[i, 1]))) for i in idx])
    poly = rings[0]
    for h in sorted(rings[1:], key=lambda h: -max(q[1][0] for q in h)):
        poly = _bridge(poly, h)
    tris = []
    ring = poly[:]
    while len(ring) > 3:
        n = len(ring)
        found = False
        for i in range(n):
            ia, ib, ic = ring[i - 1], ring[i], ring[(i + 1) % n]
            a, b, c = ia[1], ib[1], ic[1]
            cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if cross <= 1e-20:
                continue
            ok = True
            for q in ring:
                if q[0] == ia[0] or q[0] == ib[0] or q[0] == ic[0]:
                    continue
                qp = q[1]
                if _same(qp, a) or _same(qp, b) or _same(qp, c):
                    continue
                if _point_in_tri(qp, a, b, c):
                    ok = False
                    break
            if ok:
                tris.append((ia[0], ib[0], ic[0]))
                del ring[i]
                found = True
                break
        if not found:
            areas = []
            for i in range(n):
                a, b, c = ring[i - 1][1], ring[i][1], ring[(i + 1) % n][1]
                areas.append(abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])))
            i = int(np.argmin(areas))
            if areas[i] < 1e-16:
                del ring[i]
            else:
                for k in range(1, len(ring) - 1):
                    tris.append((ring[0][0], ring[k][0], ring[k + 1][0]))
                ring = []
    if len(ring) == 3:
        tris.append((ring[0][0], ring[1][0], ring[2][0]))
    return tris


def plane_basis(normal):
    n = np.asarray(normal, dtype=float)
    n = n / np.linalg.norm(n)
    a = np.array([1.0, 0, 0]) if abs(n[0]) < 0.9 else np.array([0, 1.0, 0])
    u = np.cross(a, n)
    u /= np.linalg.norm(u)
    return u, np.cross(n, u), n


def newell_normal(p):
    p = np.asarray(p, dtype=float)
    q = np.roll(p, -1, axis=0)
    n = np.array([np.sum((p[:, 1] - q[:, 1]) * (p[:, 2] + q[:, 2])),
                  np.sum((p[:, 2] - q[:, 2]) * (p[:, 0] + q[:, 0])),
                  np.sum((p[:, 0] - q[:, 0]) * (p[:, 1] + q[:, 1]))])
    ln = np.linalg.norm(n)
    return n / ln if ln > 0 else np.array([0, 0, 1.0])


def _tri_face(v, f):
    """Triangulate one polygon face (local corner indices)."""
    n = len(f)
    if n == 3:
        return [(0, 1, 2)]
    p = v[list(f)]
    if n == 4:
        if np.sum((p[0] - p[2]) ** 2) <= np.sum((p[1] - p[3]) ** 2) * 1.0001:
            return [(0, 1, 2), (0, 2, 3)]
        return [(0, 1, 3), (1, 2, 3)]
    u, w, nrm = plane_basis(newell_normal(p))
    p2 = np.stack([p @ u, p @ w], axis=1)
    tris = triangulate(p2)
    return tris


# ------------------------------------------------------------------------ export
def box_uv(p, n, scale):
    ax = int(np.argmax(np.abs(n)))
    if ax == 0:
        u, vup = -np.sign(n[0]) * p[2], p[1]
    elif ax == 1:
        u, vup = p[0], -np.sign(n[1]) * p[2]
    else:
        u, vup = np.sign(n[2]) * p[0], p[1]
    return u / scale, -vup / scale


def _soft_flags(mesh):
    """Per face: part of a round (meshes built before smoothing flags existed have none, or a short list)."""
    soft = list(getattr(mesh, "soft", None) or [])
    return soft[:len(mesh.f)] + [False] * (len(mesh.f) - len(soft))


def build_primitives(mesh, hard_deg=35.0, uv_scale=0.05, split_materials=False):
    """-> list of dicts {material, positions, normals, uvs, indices}; one per material.
    split_materials: also treat material boundaries as hard edges (crisp bevels on thin parts).
    Normals: faces meeting at less than hard_deg are shaded smooth, each face's share weighted by its corner angle and
    its polygon's area ("weighted normals", VIEWMODEL_SPEC section 53): a big flat face keeps its own normal up to its
    rounded edge, and the narrow facets of the round carry the turn, so highlights roll over the edge. Faces marked
    soft (the facets of a round) are smoothed with their neighbours up to EDGES soft_deg instead of hard_deg."""
    from model_quality import EDGES
    if not mesh.f:
        return []
    V = np.array(mesh.v, dtype=float)
    tri_v, tri_m, tri_uv, tri_face = [], [], [], []
    for k, (f, mat, uvs) in enumerate(zip(mesh.f, mesh.m, mesh.uv)):
        for t in _tri_face(V, f):
            tri_v.append([f[t[0]], f[t[1]], f[t[2]]])
            tri_m.append(mat)
            tri_uv.append([uvs[t[0]], uvs[t[1]], uvs[t[2]]] if uvs is not None else None)
            tri_face.append(k)
    T = np.array(tri_v, dtype=np.int64)
    p0, p1, p2 = V[T[:, 0]], V[T[:, 1]], V[T[:, 2]]
    fn = np.cross(p1 - p0, p2 - p0)
    ln = np.linalg.norm(fn, axis=1)
    keep = ln > 1e-13
    T, fn, ln = T[keep], fn[keep], ln[keep]
    tri_face = np.asarray(tri_face, dtype=np.int64)[keep]
    tri_soft = np.asarray(_soft_flags(mesh), dtype=bool)[tri_face] if len(mesh.f) else np.zeros(0, bool)
    face_area = np.bincount(tri_face, weights=0.5 * ln, minlength=len(mesh.f))
    tri_area = face_area[tri_face]
    tri_m = [m for m, k in zip(tri_m, keep) if k]
    tri_uv = [u for u, k in zip(tri_uv, keep) if k]
    fnu = fn / ln[:, None]
    # weld by position for smoothing decisions
    _, canon = np.unique(np.round(V / 1e-7).astype(np.int64), axis=0, return_inverse=True)
    canon = canon.reshape(-1)
    cv = canon[T].reshape(-1)                       # corner -> welded vertex
    ntri = len(T)
    P = V[T]                                        # (ntri, 3, 3)
    ang = np.zeros((ntri, 3))
    for c in range(3):
        a = P[:, (c + 1) % 3] - P[:, c]
        b = P[:, (c + 2) % 3] - P[:, c]
        cosv = np.sum(a * b, 1) / (np.linalg.norm(a, axis=1) * np.linalg.norm(b, axis=1) + 1e-30)
        ang[:, c] = np.arccos(np.clip(cosv, -1, 1))
    ang = ang.reshape(-1)
    order = np.argsort(cv, kind="stable")
    cvs = cv[order]
    uniq, start, count = np.unique(cvs, return_index=True, return_counts=True)
    grp = np.repeat(np.arange(len(uniq)), count)
    k = count[grp]
    a_pos = np.repeat(np.arange(len(order)), k)
    offs = np.arange(int(k.sum())) - np.repeat(np.cumsum(k) - k, k)
    b_pos = np.repeat(start[grp], k) + offs
    A, B = order[a_pos], order[b_pos]
    ta, tb = A // 3, B // 3
    dots = np.sum(fnu[ta] * fnu[tb], axis=1)
    sel = (dots >= np.cos(np.radians(hard_deg))) | ((tri_soft[ta] | tri_soft[tb])
                                                    & (dots >= np.cos(np.radians(max(hard_deg, EDGES["soft_deg"])))))
    if split_materials:
        mat_id = {m: i for i, m in enumerate(dict.fromkeys(tri_m))}
        tm = np.array([mat_id[m] for m in tri_m])
        sel &= tm[ta] == tm[tb]
    normals = np.zeros((ntri * 3, 3))
    np.add.at(normals, A[sel], fnu[tb[sel]] * (ang[B[sel]] * tri_area[tb[sel]])[:, None])
    nl = np.linalg.norm(normals, axis=1)
    bad = nl < 1e-12
    normals[bad] = fnu[np.arange(ntri * 3)[bad] // 3]
    normals /= np.linalg.norm(normals, axis=1)[:, None]
    pos = P.reshape(-1, 3)
    uvs = np.zeros((ntri * 3, 2))
    for t in range(ntri):
        for c in range(3):
            if tri_uv[t] is not None:
                uvs[t * 3 + c] = tri_uv[t][c]
            else:
                uvs[t * 3 + c] = box_uv(pos[t * 3 + c], fnu[t], uv_scale)
    out = []
    mats = np.array(tri_m)
    for mat in dict.fromkeys(tri_m):
        tsel = np.nonzero(mats == mat)[0]
        corners = (tsel[:, None] * 3 + np.arange(3)[None, :]).reshape(-1)
        key = np.concatenate([cv[corners][:, None].astype(float), np.round(normals[corners] * 1e4),
                              np.round(uvs[corners] * 1e5)], axis=1)
        _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
        out.append({"material": mat, "positions": pos[corners][first], "normals": normals[corners][first],
                    "uvs": uvs[corners][first], "indices": inv.reshape(-1, 3).astype(np.int64)})
    return out


def triangle_count(prims):
    return int(sum(len(p["indices"]) for p in prims))
