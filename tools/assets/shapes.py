"""Shape builders on top of meshkit.Mesh: lofts, bevelled profile extrusions, lathes, boxes."""
import numpy as np

from meshkit import Mesh, newell_normal, plane_basis, triangulate


# ------------------------------------------------------------------ 2D outlines
def offset_loop(pts, d):
    """Offset a closed 2D loop to its LEFT side by d (miter joins). CCW outer -> shrinks."""
    p = np.asarray(pts, dtype=float)
    prev = np.roll(p, 1, axis=0)
    nxt = np.roll(p, -1, axis=0)
    e1 = p - prev
    e2 = nxt - p
    e1 /= np.linalg.norm(e1, axis=1)[:, None] + 1e-30
    e2 /= np.linalg.norm(e2, axis=1)[:, None] + 1e-30
    n1 = np.stack([-e1[:, 1], e1[:, 0]], 1)
    n2 = np.stack([-e2[:, 1], e2[:, 0]], 1)
    m = n1 + n2
    denom = 1 + np.sum(n1 * n2, axis=1)
    denom = np.maximum(denom, 0.25)          # clamp very sharp miters
    return p + d * m / denom[:, None]


def signed_area(p):
    p = np.asarray(p, dtype=float)
    return 0.5 * float(np.sum(p[:, 0] * np.roll(p[:, 1], -1) - np.roll(p[:, 0], -1) * p[:, 1]))


def ccw(p):
    p = np.asarray(p, dtype=float)
    return p if signed_area(p) > 0 else p[::-1].copy()


def cw(p):
    p = np.asarray(p, dtype=float)
    return p if signed_area(p) < 0 else p[::-1].copy()


def circle(cx, cy, r, n, start=0.0):
    t = start + np.linspace(0, 2 * np.pi, n, endpoint=False)
    return np.stack([cx + r * np.cos(t), cy + r * np.sin(t)], 1)


def rounded_rect(x0, y0, x1, y1, r, seg=3):
    """CCW rounded rectangle; r scalar or (r_bl, r_br, r_tr, r_tl)."""
    rs = [r] * 4 if np.isscalar(r) else list(r)
    corners = [(x0 + rs[0], y0 + rs[0], np.pi, 1.5 * np.pi, rs[0]),
               (x1 - rs[1], y0 + rs[1], 1.5 * np.pi, 2 * np.pi, rs[1]),
               (x1 - rs[2], y1 - rs[2], 0, 0.5 * np.pi, rs[2]),
               (x0 + rs[3], y1 - rs[3], 0.5 * np.pi, np.pi, rs[3])]
    pts = []
    for cx, cy, a0, a1, rr in corners:
        if rr <= 0:
            pts.append((cx, cy))
            continue
        for t in np.linspace(a0, a1, seg + 1):
            pts.append((cx + rr * np.cos(t), cy + rr * np.sin(t)))
    return np.array(pts)


def superellipse(a, b, n, count, cx=0.0, cy=0.0, n_front=None):
    """CCW superellipse |x/a|^n + |y/b|^n = 1 (optionally different exponent for y > 0)."""
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    ex = np.where(s > 0, n_front or n, n)
    x = a * np.sign(c) * np.abs(c) ** (2 / ex)
    y = b * np.sign(s) * np.abs(s) ** (2 / ex)
    return np.stack([cx + x, cy + y], 1)


# ----------------------------------------------------------------------- solids
def signed_volume(mesh):
    v = np.array(mesh.v)
    vol = 0.0
    for f in mesh.f:
        p = v[list(f)]
        for k in range(1, len(f) - 1):
            vol += np.dot(p[0], np.cross(p[k], p[k + 1]))
    return vol / 6.0


def orient_outward(mesh):
    if signed_volume(mesh) < 0:
        mesh.f = [tuple(reversed(f)) for f in mesh.f]
        mesh.uv = [list(reversed(u)) if u is not None else None for u in mesh.uv]
    return mesh


def cap_polygon(mesh, loop3d, material, holes3d=(), flip=False):
    """Planar cap with optional holes; vertices are added. Orientation: loop order (CCW) unless flip."""
    loop3d = np.asarray(loop3d, dtype=float)
    n = newell_normal(loop3d)
    u, w, _ = plane_basis(n)
    allp = [loop3d] + [np.asarray(h, dtype=float) for h in holes3d]
    base = mesh.add_vertices(np.concatenate(allp))
    o2 = [np.stack([p @ u, p @ w], 1) for p in allp]
    for t in triangulate(o2[0], o2[1:]):
        mesh.add_face([base + i for i in (t[::-1] if flip else t)], material)


def loft(sections, material, cap_start=True, cap_end=True, closed=True, mesh=None, auto_orient=True):
    """Skin consecutive rings (K, N, 3). Caps: start reversed, end as-is (consistent with sides)."""
    s = np.asarray(sections, dtype=float)
    K, N = s.shape[0], s.shape[1]
    m = Mesh()
    base = m.add_vertices(s.reshape(-1, 3))
    idx = lambda k, i: base + k * N + (i % N)
    for k in range(K - 1):
        for i in range(N if closed else N - 1):
            m.add_face([idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)], material)
    if cap_start:
        m.add_face([idx(0, i) for i in reversed(range(N))], material)
    if cap_end:
        m.add_face([idx(K - 1, i) for i in range(N)], material)
    if auto_orient and cap_start and cap_end:
        orient_outward(m)
    return mesh.merge(m) if mesh is not None else m


def extrude_x(outer, holes=(), half_width=0.01, bevel=((0.0, 0.0),), material="default", x_center=0.0):
    """Extrude a (z, y) side profile along X, symmetric about x_center.
    bevel: list of (dx_from_face, inset) from the middle outwards, e.g. chamfer c:
    ((c, 0), (0, c)) = full outline at |x| = hw - c, inset c at the face."""
    outer = ccw(outer)
    holes = [cw(h) for h in holes]
    rings = sorted(bevel, key=lambda r: -r[0])        # from inner (largest dx) to face (dx=0)
    xs, insets = [], []
    for dx, ins in rings[::-1]:                       # -X face first
        xs.append(-(half_width - dx))
        insets.append(ins)
    for dx, ins in rings:
        if dx == 0 and len(xs) and np.isclose(xs[-1], -(half_width - dx)) and half_width == 0:
            continue
        xs.append(half_width - dx)
        insets.append(ins)
    xs = np.array(xs) + x_center
    mesh = Mesh()
    loops = [outer] + list(holes)
    secs_per_loop = []
    for loop in loops:
        secs = []
        for x, ins in zip(xs, insets):
            o = offset_loop(loop, ins) if ins else loop
            secs.append(np.stack([np.full(len(o), x), o[:, 1], o[:, 0]], 1))
        secs_per_loop.append(secs)
        sm = np.asarray(secs)
        K, N = sm.shape[:2]
        base = mesh.add_vertices(sm.reshape(-1, 3))
        for k in range(K - 1):
            for i in range(N):
                a, b = base + k * N + i, base + k * N + (i + 1) % N
                c, d = base + (k + 1) * N + (i + 1) % N, base + (k + 1) * N + i
                mesh.add_face([a, d, c, b], material)
    for end, flip in ((0, False), (len(xs) - 1, True)):
        o2 = [np.stack([s[end][:, 2], s[end][:, 1]], 1) for s in secs_per_loop]
        base = mesh.add_vertices(np.concatenate([s[end] for s in secs_per_loop]))
        for t in triangulate(o2[0], o2[1:]):
            mesh.add_face([base + i for i in (t[::-1] if flip else t)], material)
    return orient_outward(mesh)


def section_rings(loop2d, z_list, inset_list=None, plane="xy"):
    """Rings of a 2D (x, y) loop placed at several z, optionally inset (for chamfered ends)."""
    out = []
    for k, z in enumerate(z_list):
        ins = inset_list[k] if inset_list is not None else 0.0
        o = offset_loop(loop2d, ins) if ins else np.asarray(loop2d, float)
        out.append(np.stack([o[:, 0], o[:, 1], np.full(len(o), z)], 1))
    return out


def prism_z(loop2d, z0, z1, chamfer=0.0, material="default"):
    """Extrude a CCW (x, y) loop from z0 to z1 with optional 45 degree chamfer on both ends."""
    loop2d = ccw(loop2d)
    if chamfer > 0:
        rings = section_rings(loop2d, [z0, z0 + chamfer, z1 - chamfer, z1], [chamfer, 0, 0, chamfer])
    else:
        rings = section_rings(loop2d, [z0, z1])
    return loft(rings, material)


def box(x0, y0, z0, x1, y1, z1, chamfer=0.0, material="default"):
    c = min(chamfer, 0.45 * min(x1 - x0, y1 - y0, z1 - z0))
    if c > 0:
        loop = np.array([(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c),
                         (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)])
    else:
        loop = np.array([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])
    return prism_z(loop, z0, z1, c, material)


def revolve(profile, segments, material, outward=True, start_angle=0.0):
    """Lathe a (r, z) profile around the Z axis. Profile points with r == 0 collapse to the axis."""
    prof = np.asarray(profile, dtype=float)
    t = start_angle + np.linspace(0, 2 * np.pi, segments, endpoint=False)
    rings = [np.stack([r * np.cos(t), r * np.sin(t), np.full(segments, z)], 1) for r, z in prof]
    m = Mesh()
    base = m.add_vertices(np.concatenate(rings))
    idx = lambda k, i: base + k * segments + (i % segments)
    for k in range(len(prof) - 1):
        for i in range(segments):
            f = [idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)]
            m.add_face(f, material)
    # orientation: check the face with the largest radius against the radial direction
    v = np.array(m.v)
    score = 0.0
    for f in m.f:
        p = v[list(f)]
        n = np.cross(p[1] - p[0], p[2] - p[0]) + np.cross(p[2] - p[0], p[3] - p[0])
        c = p.mean(0)
        score += np.dot(n, np.array([c[0], c[1], 0.0]))
    if (score < 0) == outward:
        m.f = [tuple(reversed(f)) for f in m.f]
    return m


def disc(center, normal, r, segments, material):
    """Flat circle facing `normal`."""
    u, w, n = plane_basis(normal)
    t = np.linspace(0, 2 * np.pi, segments, endpoint=False)
    pts = np.asarray(center, float) + r * (np.cos(t)[:, None] * u + np.sin(t)[:, None] * w)
    m = Mesh()
    m.add_poly(pts, material)
    return m


def place(mesh, origin=(0, 0, 0), x_axis=(1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, 1)):
    mat = np.eye(4)
    mat[:3, 0], mat[:3, 1], mat[:3, 2] = x_axis, y_axis, z_axis
    mat[:3, 3] = origin
    return mesh.transformed(mat)


def rot_x(mesh, deg, pivot=(0, 0, 0)):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    r = np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    m = np.eye(4)
    m[:3, :3] = r
    p = np.asarray(pivot, float)
    m[:3, 3] = p - r @ p
    return mesh.transformed(m)
