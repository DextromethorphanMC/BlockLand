"""Triangle winding: analysis and repair (VIEWMODEL_SPEC section 6 — the runtime culls back faces of every material
that is not `doubleSided`, so a face wound inwards is invisible from outside and the part looks hollow).

Models from outside the project often rely on double-sided materials and come with mixed or inside-out winding; their
converters run `orient()` on every converted part. `analyse()` is what check_orient.py tests every model with."""
import numpy as np

WELD = 1e-7          # metres: vertices this close are one point (split normals / UV seams share positions)
EPS = 1e-12          # twice the area below which a triangle is degenerate (ignored)


def _weld(V):
    key = np.round(np.asarray(V, float) / WELD).astype(np.int64)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    return inv.reshape(-1)


def _normals(V, F):
    a, b, c = V[F[:, 0]], V[F[:, 1]], V[F[:, 2]]
    return np.cross(b - a, c - a)


def _edges(W, F, live):
    """(min, max) welded edge -> [(triangle, directed edge (a, b))]."""
    edges = {}
    for t in np.flatnonzero(live):
        tri = W[F[t]]
        for k in range(3):
            a, b = int(tri[k]), int(tri[(k + 1) % 3])
            edges.setdefault((min(a, b), max(a, b)), []).append((t, (a, b)))
    return edges


def _patches(F, W, live, edges):
    """Groups triangles joined by manifold edges and the flips that make each group consistent.
    -> (patch id per triangle (-1 = degenerate), flip per triangle relative to the patch's first triangle)."""
    n = len(F)
    patch = -np.ones(n, np.int64)
    flip = np.zeros(n, bool)
    adjacent = [[] for _ in range(n)]
    for shared in edges.values():
        if len(shared) != 2:
            continue                                  # boundary or non-manifold: does not join
        (t0, e0), (t1, e1) = shared
        adjacent[t0].append((t1, e0 == e1))           # same direction = opposite orientation
        adjacent[t1].append((t0, e0 == e1))
    count = 0
    for seed in np.flatnonzero(live):
        if patch[seed] >= 0:
            continue
        patch[seed] = count
        stack = [seed]
        while stack:
            t = stack.pop()
            for u, opposite in adjacent[t]:
                if patch[u] < 0:
                    patch[u] = count
                    flip[u] = flip[t] ^ opposite
                    stack.append(u)
        count += 1
    return patch, flip


def _hits(origins, dirs, A, E1, E2):
    """Möller–Trumbore: for each ray, whether it meets any triangle in front of its origin."""
    hit = np.zeros(len(origins), bool)
    for k in range(0, len(origins), 64):
        o, d = origins[k:k + 64, None, :], dirs[k:k + 64, None, :]
        p = np.cross(d, E2[None])
        det = np.einsum("rtk,tk->rt", p, E1)
        ok = np.abs(det) > 1e-14
        inv = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
        s = o - A[None]
        u = np.einsum("rtk,rtk->rt", s, p) * inv
        q = np.cross(s, E1[None])
        v = np.einsum("rtk,rtk->rt", d, q) * inv
        dist = np.einsum("tk,rtk->rt", E2, q) * inv
        hit[k:k + 64] = (ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (dist > 1e-6)).any(1)
    return hit


def _cone(n, rng, count, spread=0.6):
    """Unit directions around n (a fixed jittered cone)."""
    out = n[None, :] + rng.normal(size=(count, 3)) * spread
    out[0] = n
    return out / np.linalg.norm(out, axis=1, keepdims=True)


def _outward_votes(V, F, members, normals, A, E1, E2, rng, samples=16, rays=5):
    """Area-weighted votes of a patch's triangles: > 0 when their front sides see out of the part more often than their
    backs (the front is outside)."""
    area = np.linalg.norm(normals[members], axis=1)
    order = members[np.argsort(-area)][:samples]
    score = 0.0
    for t in order:
        n = normals[t] / max(np.linalg.norm(normals[t]), 1e-30)
        c = V[F[t]].mean(0)
        scale = max(np.linalg.norm(V[F[t]] - c, axis=1).max(), 1e-5)
        front, back = _cone(n, rng, rays), _cone(-n, rng, rays)
        o_front = np.repeat((c + n * scale * 1e-3)[None], rays, 0)
        o_back = np.repeat((c - n * scale * 1e-3)[None], rays, 0)
        escaped_front = (~_hits(o_front, front, A, E1, E2)).sum()
        escaped_back = (~_hits(o_back, back, A, E1, E2)).sum()
        score += np.linalg.norm(normals[t]) * (escaped_front - escaped_back)
    return score


def _view_dirs():
    dirs = [(x, y, z) for x in (-1, 0, 1) for y in (-1, 0, 1) for z in (-1, 0, 1) if (x, y, z) != (0, 0, 0)]
    return [np.array(d, float) / np.linalg.norm(d) for d in dirs]


def visible_facing(V, F, groups, size=256):
    """Seen from 26 directions around the triangle set (orthographic, nothing culled): per group id (one per triangle,
    -1 = ignored), how many pixels show its front faces and how many its back faces."""
    import raster as rs
    V = np.asarray(V, float)
    centre = 0.5 * (V.min(0) + V.max(0))
    radius = max(np.linalg.norm(V - centre, axis=1).max(), 1e-5)
    count = int(groups.max()) + 1
    front = np.zeros(count)
    back = np.zeros(count)
    for d in _view_dirs():
        z = -d
        up = np.array([0.0, 1.0, 0.0]) if abs(z[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
        x = np.cross(up, -z)
        x /= np.linalg.norm(x)
        y = np.cross(-z, x)
        R = np.stack([x, y, -z])
        pos = (V - (centre + d * radius * 3)) @ R.T
        cam = rs.Camera(size, size, ortho_half_h=radius * 1.05)
        item = {"pos": pos, "nrm": np.zeros_like(pos), "idx": F,
                "mat": {"base": (1, 1, 1, 1), "tex": None, "alpha_mode": "OPAQUE", "double": True}}
        _, res = rs.rasterize([item], cam, size, size, flat=True)
        if res is None or "opaque" not in res[0]:
            continue
        frag = res[0]["opaque"]
        tv = frag["tv"]
        sx, sy, _, _ = cam.project(pos)
        area = (sx[tv[:, 1]] - sx[tv[:, 0]]) * (sy[tv[:, 2]] - sy[tv[:, 0]]) \
            - (sy[tv[:, 1]] - sy[tv[:, 0]]) * (sx[tv[:, 2]] - sx[tv[:, 0]])
        g = groups[frag["tri"]]
        ok = g >= 0
        np.add.at(front, g[ok & (area < 0)], 1)           # rasterize: front faces have negative area (y down)
        np.add.at(back, g[ok & (area >= 0)], 1)
    return front, back


def orient(V, F, seed=0, occluders=None):
    """Reorders the triangles F (T, 3) of the vertices V (N, 3) so that every surface is wound consistently and faces
    outwards: closed patches by their signed volume; open ones so that what can be seen of them from outside are front
    faces (a hole's walls face into the hole; `occluders` = (V, F) of the parts around this one, e.g. the rest of the
    weapon), hidden ones by which side sees out of the part (ties: away from the part's centre). Degenerate triangles
    are left as they are."""
    V = np.asarray(V, float)
    F = np.array(F, np.int64)
    W = _weld(V)
    normals = _normals(V, F)
    live = (np.linalg.norm(normals, axis=1) > EPS) & (W[F[:, 0]] != W[F[:, 1]]) & (W[F[:, 1]] != W[F[:, 2]]) \
        & (W[F[:, 0]] != W[F[:, 2]])
    edges = _edges(W, F, live)
    patch, flip = _patches(F, W, live, edges)
    F[flip] = F[flip][:, [0, 2, 1]]
    normals = _normals(V, F)
    A = V[F[live, 0]]
    E1, E2 = V[F[live, 1]] - A, V[F[live, 2]] - A
    centre = V[np.unique(F[live])].mean(0) if live.any() else np.zeros(3)
    rng = np.random.default_rng(seed)
    boundary = {}
    for key, shared in edges.items():
        if len(shared) != 2:
            for t, _ in shared:
                boundary[patch[t]] = True
    seen_front = seen_back = None
    if boundary:
        if occluders is not None and len(occluders[1]):
            Vo, Fo = np.asarray(occluders[0], float), np.asarray(occluders[1], np.int64)
            seen_front, seen_back = visible_facing(np.concatenate([V, Vo]), np.concatenate([F, Fo + len(V)]),
                                                   np.concatenate([patch, -np.ones(len(Fo), np.int64)]))
        else:
            seen_front, seen_back = visible_facing(V, F, patch)
    sheets = []
    for p in range(patch.max() + 1):
        members = np.flatnonzero(patch == p)
        if p not in boundary:
            a, b, c = V[F[members, 0]], V[F[members, 1]], V[F[members, 2]]
            pc = V[F[members]].reshape(-1, 3).mean(0)
            outward = np.einsum("ij,ij->i", a - pc, np.cross(b - pc, c - pc)).sum() >= 0
        elif seen_front[p] + seen_back[p] >= 8:
            outward = seen_front[p] >= seen_back[p]
            # a sheet that folds back is seen from behind whichever way it is wound: give it a back side
            if min(seen_front[p], seen_back[p]) > TWO_SIDED * (seen_front[p] + seen_back[p]):
                sheets.append(members)
        else:
            score = _outward_votes(V, F, members, normals, A, E1, E2, rng)
            if abs(score) < 1e-18:
                centres = V[F[members]].mean(1)
                score = np.einsum("ij,ij->", normals[members], centres - centre)
            outward = score >= 0
        if not outward:
            F[members] = F[members][:, [0, 2, 1]]
    if sheets:
        F = np.concatenate([F] + [F[m][:, [0, 2, 1]] for m in sheets])
    return F


#: an open patch still seen from behind on more than this share of its pixels gets a reversed copy (two-sided sheet)
TWO_SIDED = 0.08


def inward_open_patches(V, F, seed=0, margin=0.25):
    """Open patches whose front sides clearly see less out of the triangle set than their backs (inside-out sheets)."""
    V = np.asarray(V, float)
    F = np.asarray(F, np.int64)
    W = _weld(V)
    normals = _normals(V, F)
    live = (np.linalg.norm(normals, axis=1) > EPS) & (W[F[:, 0]] != W[F[:, 1]]) & (W[F[:, 1]] != W[F[:, 2]]) \
        & (W[F[:, 0]] != W[F[:, 2]])
    if not live.any():
        return 0
    edges = _edges(W, F, live)
    patch, _ = _patches(F, W, live, edges)
    boundary = {patch[t] for shared in edges.values() if len(shared) != 2 for t, _ in shared}
    A = V[F[live, 0]]
    E1, E2 = V[F[live, 1]] - A, V[F[live, 2]] - A
    rng = np.random.default_rng(seed)
    inward = 0
    for p in sorted(boundary):
        members = np.flatnonzero(patch == p)
        score = _outward_votes(V, F, members, normals, A, E1, E2, rng)
        weight = np.linalg.norm(normals[members], axis=1)[:16].sum() * 5
        if score < -margin * weight:
            inward += 1
    return inward


def analyse(V, F):
    """-> (inconsistent manifold edges, inside-out closed patches, closed patches, open patches) of one triangle set."""
    V = np.asarray(V, float)
    F = np.asarray(F, np.int64)
    W = _weld(V)
    normals = _normals(V, F)
    live = (np.linalg.norm(normals, axis=1) > EPS) & (W[F[:, 0]] != W[F[:, 1]]) & (W[F[:, 1]] != W[F[:, 2]]) \
        & (W[F[:, 0]] != W[F[:, 2]])
    edges = _edges(W, F, live)
    inconsistent = sum(1 for shared in edges.values() if len(shared) == 2 and shared[0][1] == shared[1][1])
    patch, _ = _patches(F, W, live, edges)
    boundary = {patch[t] for shared in edges.values() if len(shared) != 2 for t, _ in shared}
    inside_out = closed = 0
    for p in range(patch.max() + 1):
        if p in boundary:
            continue
        members = np.flatnonzero(patch == p)
        a, b, c = V[F[members, 0]], V[F[members, 1]], V[F[members, 2]]
        pc = V[F[members]].reshape(-1, 3).mean(0)
        closed += 1
        if np.einsum("ij,ij->i", a - pc, np.cross(b - pc, c - pc)).sum() < 0:
            inside_out += 1
    return inconsistent, inside_out, closed, patch.max() + 1 - closed
