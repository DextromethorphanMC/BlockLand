"""Shape builders on top of meshkit.Mesh: lofts, bevelled profile extrusions, lathes, boxes.

Edges are rounded by default (VIEWMODEL_SPEC section 53): the outlines' corners get small fillets, extrusions and
prisms get a quarter-round edge where they would end in a square edge, lathe profiles' corners are filleted and lathes
of 12 or more segments are sampled to the chord error. Radii follow the part's size within a machined or
moulded edge's range (model_quality.json "edges"); `rounding=False` (or a sharp material: blades) keeps an edge as
authored."""
import numpy as np

from meshkit import Mesh, newell_normal, plane_basis, triangulate
from model_quality import EDGES, arc_segments, edge_radius, fillet_segments, is_sharp

#: False inside authored() (see there).
_rounding = [True]


class authored:
    """`with shapes.authored(): ...` builds everything in the block as authored (no rounding, VIEWMODEL_SPEC section
    53): for parts nobody sees up close (a closed crate's contents) or meshes that must keep their exact counts."""

    def __enter__(self):
        self.was = _rounding[0]
        _rounding[0] = False

    def __exit__(self, *exc):
        _rounding[0] = self.was


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
    """Circle of exactly n points (callers rely on the count: sample with arc_segments(r, n) for the chord error)."""
    t = start + np.linspace(0, 2 * np.pi, n, endpoint=False)
    return np.stack([cx + r * np.cos(t), cy + r * np.sin(t)], 1)


def rounded_rect(x0, y0, x1, y1, r, seg=3):
    """CCW rounded rectangle; r scalar or (r_bl, r_br, r_tr, r_tl). Always 4 x (seg + 1) points (lofts skin rings of
    different radii point by point)."""
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


# ------------------------------------------------------------------ rounded edges
def fillet_loop(pts, radius, closed=True, arcs=False):
    """Round the corners of a 2D loop (or an open polyline: its ends stay) with arcs of `radius`: every vertex turning
    by at least EDGES min_turn_deg (denser curves are already round) and at most max_turn_deg (sharper corners are
    points on purpose: sight ramps, teeth, tips — a fillet would pull them back), the arc shrunk to fit 45 % of each
    adjacent edge, left sharp when that leaves less than EDGES skip_radius_m (under a pixel on screen). Concave
    corners get the same (an inside fillet).
    arcs=True -> (points, mask): mask[i] says the edge from point i to the next is a chord of a round."""
    p = np.asarray(pts, dtype=float)
    n = len(p)
    if radius <= 0 or n < 3:
        return (p, np.zeros(len(p), bool)) if arcs else p
    chords = []
    min_turn = np.radians(EDGES["min_turn_deg"])
    max_turn = np.radians(EDGES["max_turn_deg"])
    out = []
    for i in range(n):
        c = p[i]
        if not closed and (i == 0 or i == n - 1):
            out.append(c)
            continue
        a, b = p[i - 1], p[(i + 1) % n]
        e1, e2 = c - a, b - c
        l1, l2 = float(np.hypot(*e1)), float(np.hypot(*e2))
        if l1 < 1e-9 or l2 < 1e-9:
            out.append(c)
            continue
        u1, u2 = e1 / l1, e2 / l2
        turn = float(np.arccos(np.clip(np.dot(u1, u2), -1.0, 1.0)))
        if turn < min_turn or turn > max_turn:
            out.append(c)
            continue
        half = (np.pi - turn) / 2                       # half the interior angle
        t = min(radius / np.tan(half), 0.45 * l1, 0.45 * l2)
        r = t * np.tan(half)
        if r < EDGES["skip_radius_m"]:
            out.append(c)
            continue
        side = 1.0 if u1[0] * u2[1] - u1[1] * u2[0] > 0 else -1.0
        start = c - u1 * t
        centre = start + np.array([-u1[1], u1[0]]) * side * r
        a0 = np.arctan2(start[1] - centre[1], start[0] - centre[0])
        k = fillet_segments(r, turn)
        chords.extend(range(len(out), len(out) + k))
        for j in range(k + 1):
            ang = a0 + side * turn * j / k
            out.append(centre + r * np.array([np.cos(ang), np.sin(ang)]))
    out = np.array(out)
    if not arcs:
        return out
    mask = np.zeros(len(out), bool)
    mask[chords] = True
    return out, mask


def quarter_round(r):
    """A quarter-round edge as extrude rings (dx from the face, inset), from the side wall to the face."""
    k = fillet_segments(r, np.pi / 2)
    th = np.linspace(0, np.pi / 2, k + 1)
    return [(r - r * np.sin(t), r - r * np.cos(t)) for t in th]


def _segments_cross(loop):
    """Whether a closed 2D loop crosses itself (non-adjacent edges intersecting)."""
    p = np.asarray(loop, dtype=float)
    n = len(p)
    if n < 4 or n > 2500:                         # too big to test exhaustively: trust it
        return False
    a, b = p, np.roll(p, -1, axis=0)
    d = b - a
    i, j = np.triu_indices(n, 2)                  # every pair of edges, skipping neighbours
    keep = ~((i == 0) & (j == n - 1))
    i, j = i[keep], j[keep]
    cross = lambda u, v: u[:, 0] * v[:, 1] - u[:, 1] * v[:, 0]
    den = cross(d[i], d[j])
    ok = np.abs(den) > 1e-18
    safe = np.where(ok, den, 1.0)
    w = a[j] - a[i]
    t = np.where(ok, cross(w, d[j]) / safe, -1.0)
    u = np.where(ok, cross(w, d[i]) / safe, -1.0)
    return bool(np.any(ok & (t > 1e-9) & (t < 1 - 1e-9) & (u > 1e-9) & (u < 1 - 1e-9)))


def _inset_ok(loops, inset):
    """The loops (outer CCW, holes CW) offset by `inset` stay simple and keep their orientation and some area."""
    for k, loop in enumerate(loops):
        o = offset_loop(loop, inset)
        a0, a1 = signed_area(loop), signed_area(o)
        if np.sign(a0) != np.sign(a1) or (k == 0 and abs(a1) < 0.2 * abs(a0)):
            return False
        if _segments_cross(o):
            return False
    return True


def _is_default_bevel(bevel):
    return all(abs(dx) < 1e-12 and abs(ins) < 1e-12 for dx, ins in bevel)


def _as_round(bevel):
    """A plain 45 degree chamfer [(c, 0), (0, c)] as a quarter round of radius c; anything else as authored."""
    rings = sorted(bevel, key=lambda r: -r[0])
    if len(rings) == 2:
        (dx0, in0), (dx1, in1) = rings
        if dx0 > 0 and abs(in0) < 1e-12 and abs(dx1) < 1e-12 and abs(in1 - dx0) < 1e-9:
            return quarter_round(dx0)
    return list(bevel)


def _round_profile(loops, bevel, thickness):
    """-> (bevel, outline fillet radius) for an extrusion of these loops (outer CCW, holes CW) `thickness` thick."""
    lo = np.min(loops[0], axis=0)
    hi = np.max(loops[0], axis=0)
    thickness = min(thickness, float(np.min(hi - lo)))
    if _is_default_bevel(bevel):
        rb = min(edge_radius(thickness), 0.45 * 0.5 * thickness)
        out = []
        while rb >= EDGES["min_radius_m"] * 0.5 and thickness >= 0.0008:
            if _inset_ok(loops, rb):
                out = quarter_round(rb)
                break
            rb *= 0.5
        bevel = out or [(0.0, 0.0)]
    else:
        bevel = _as_round(bevel)
    deepest = max(ins for _, ins in bevel)
    return bevel, max(edge_radius(float(np.min(hi - lo))), 1.25 * deepest)


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


def _prism_of(s):
    """A capped loft whose rings are all one 2D outline offset (offset_loop) in parallel planes -> (base 2D outline
    CCW, origin, u, w, normal, plane offsets, insets); None for any other loft (tapers, twists, lathes, organic)."""
    K, N = s.shape[0], s.shape[1]
    if K < 2 or N < 3:
        return None
    n = newell_normal(s[0])
    size = float(np.max(np.ptp(s.reshape(-1, 3), axis=0)))
    tol = 1e-6 + 1e-5 * size
    origin = s[0, 0]
    u, w, _ = plane_basis(n)
    rings2, zs = [], []
    for ring in s:
        d = (ring - origin) @ n
        if np.ptp(d) > tol:
            return None
        zs.append(float(d.mean()))
        rings2.append(np.stack([(ring - origin) @ u, (ring - origin) @ w], 1))
    if np.ptp(zs) < tol:
        return None
    areas = [signed_area(r) for r in rings2]
    k0 = int(np.argmax(np.abs(areas)))
    base = rings2[k0]
    flip = areas[k0] < 0
    if flip:                                              # offset_loop insets a CCW loop: keep the base CCW
        base, rings2 = base[::-1], [r[::-1] for r in rings2]
    unit = offset_loop(base, 1.0) - base                   # offset_loop moves every vertex linearly with the inset
    insets = []
    for r in rings2:
        ins = float(np.sum((r - base) * unit) / max(float(np.sum(unit * unit)), 1e-30))
        if ins < -tol or np.max(np.abs((offset_loop(base, ins) if ins > tol else base) - r)) > tol * 4:
            return None
        insets.append(max(ins, 0.0) if ins > tol else 0.0)
    return base, origin, u, w, n, zs, insets, flip


def _round_prism_loft(s, material):
    """The rounded form of a prism-shaped loft (VIEWMODEL_SPEC section 53): its outline's corners filleted and a plain
    45 degree chamfer at an end (one inset ring, then the full outline as far again along the axis) made a quarter
    round. Square ends stay square (helpers use them for flush joins); other authored end rings stay as they are."""
    found = _prism_of(s)
    if found is None:
        return None
    base, origin, u, w, n, zs, insets, flip = found
    zs, insets = list(zs), list(insets)
    if zs[-1] < zs[0]:
        zs, insets, reverse = zs[::-1], insets[::-1], True
    else:
        reverse = False
    if any(b <= a for a, b in zip(zs, zs[1:])):
        return None

    def end(zs, insets):
        """-> replacement (z offsets from the end face, insets) for the end at index 0, or None to keep it."""
        if len(zs) >= 3 and insets[0] > 0 and insets[1] == 0 and abs((zs[1] - zs[0]) - insets[0]) < 1e-7:
            return [(dx, ins) for dx, ins in reversed(quarter_round(insets[0]))], 2
        return None, 0

    rings = list(zip(zs, insets))
    head, _ = end(zs, insets)
    tail, _ = end([-z for z in zs[::-1]], insets[::-1])
    if tail and len(rings) - (2 if head else 0) < 2:
        tail = None
    first = [(zs[0] + dx, ins) for dx, ins in head] if head else []
    last = [(zs[-1] - dx, ins) for dx, ins in reversed(tail)] if tail else []
    rings = first + rings[(2 if head else 0):len(rings) - (2 if tail else 0)] + last
    lo, hi = base.min(0), base.max(0)
    deepest = max(ins for _, ins in rings)
    loop, chords = fillet_loop(base, max(edge_radius(float(np.min(hi - lo))), 1.25 * deepest), arcs=True)
    if deepest > 0 and not _inset_ok([loop], deepest):
        return None
    if flip:                                              # back to the authored direction: edge i is chord N-2-i
        loop, chords = loop[::-1], np.roll(chords[::-1], -1)
    ordered = rings[::-1] if reverse else rings
    out = []
    for z, ins in ordered:
        o = loop if ins <= 0 else (offset_loop(loop[::-1], ins)[::-1] if flip else offset_loop(loop, ins))
        out.append(origin + o[:, 0:1] * u + o[:, 1:2] * w + z * n)
    bands = [k for k in range(len(ordered) - 1) if ordered[k][1] > 0 or ordered[k + 1][1] > 0]
    return np.asarray(out), bands, np.nonzero(chords)[0]


def loft(sections, material, cap_start=True, cap_end=True, closed=True, mesh=None, auto_orient=True, rounding=True,
         soft_bands=(), soft_cols=()):
    """Skin consecutive rings (K, N, 3). Caps: start reversed, end as-is (consistent with sides).
    A capped loft whose rings are one outline offset in parallel planes (a prism built by hand) is rounded like
    prism_z unless rounding=False: corners filleted, plain chamfers made quarter rounds, square ends kept.
    soft_bands / soft_cols: band k (rings k..k+1) / column i (points i..i+1) are facets of a round (smooth shaded)."""
    s = np.asarray(sections, dtype=float)
    if rounding and _rounding[0] and cap_start and cap_end and closed and not is_sharp(material):
        rounded = _round_prism_loft(s, material)
        if rounded is not None:
            s, soft_bands, soft_cols = rounded
    K, N = s.shape[0], s.shape[1]
    m = Mesh()
    base = m.add_vertices(s.reshape(-1, 3))
    idx = lambda k, i: base + k * N + (i % N)
    soft_bands, soft_cols = set(soft_bands), set(soft_cols)
    for k in range(K - 1):
        for i in range(N if closed else N - 1):
            m.add_face([idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)], material,
                       soft=k in soft_bands or i in soft_cols)
    if cap_start:
        m.add_face([idx(0, i) for i in reversed(range(N))], material)
    if cap_end:
        m.add_face([idx(K - 1, i) for i in range(N)], material)
    if auto_orient and cap_start and cap_end:
        orient_outward(m)
    return mesh.merge(m) if mesh is not None else m


def extrude_x(outer, holes=(), half_width=0.01, bevel=((0.0, 0.0),), material="default", x_center=0.0, rounding=True):
    """Extrude a (z, y) side profile along X, symmetric about x_center.
    bevel: list of (dx_from_face, inset) from the middle outwards, e.g. chamfer c:
    ((c, 0), (0, c)) = full outline at |x| = hw - c, inset c at the face.
    Rounded by default: no bevel becomes a small quarter round, a plain chamfer a quarter round of its size, and the
    outline's corners are filleted; rounding=False (or a sharp material) keeps everything as given."""
    outer = ccw(outer)
    holes = [cw(h) for h in holes]
    chords = [np.zeros(len(outer), bool)] + [np.zeros(len(h), bool) for h in holes]
    if rounding and _rounding[0] and not is_sharp(material):
        bevel, r = _round_profile([outer] + holes, bevel, 2 * half_width)
        rounded = [fillet_loop(lp, r, arcs=True) for lp in [outer] + holes]
        outer, holes = rounded[0][0], [h for h, _ in rounded[1:]]
        chords = [c for _, c in rounded]
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
    for loop, chord in zip(loops, chords):
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
                mesh.add_face([a, d, c, b], material, soft=insets[k] > 0 or insets[k + 1] > 0 or chord[i])
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


def prism_z(loop2d, z0, z1, chamfer=0.0, material="default", rounding=True):
    """Extrude a CCW (x, y) loop from z0 to z1 with optional 45 degree chamfer on both ends. Rounded by default: the
    chamfer (or, without one, a small edge by the part's size) becomes a quarter round on both ends and the loop's
    corners are filleted; rounding=False (or a sharp material) keeps the 45 degree chamfer and the sharp corners."""
    loop2d = ccw(loop2d)
    if rounding and _rounding[0] and not is_sharp(material):
        bevel, r = _round_profile([loop2d], [(chamfer, 0.0), (0.0, chamfer)] if chamfer > 0 else [(0.0, 0.0)],
                                  abs(z1 - z0))
        loop2d, chords = fillet_loop(loop2d, r, arcs=True)
        cols = np.nonzero(chords)[0]
        if len(bevel) > 1:
            side = sorted(bevel, key=lambda ring: -ring[0])       # side wall first, the face last
            zs = [z0 + dx for dx, _ in reversed(side)] + [z1 - dx for dx, _ in side]
            insets = [ins for _, ins in reversed(side)] + [ins for _, ins in side]
            bands = [k for k in range(len(zs) - 1) if insets[k] > 0 or insets[k + 1] > 0]
            return loft(section_rings(loop2d, zs, insets), material, rounding=False, soft_bands=bands, soft_cols=cols)
        return loft(section_rings(loop2d, [z0, z1]), material, rounding=False, soft_cols=cols)
    if chamfer > 0:
        rings = section_rings(loop2d, [z0, z0 + chamfer, z1 - chamfer, z1], [chamfer, 0, 0, chamfer])
    else:
        rings = section_rings(loop2d, [z0, z1])
    return loft(rings, material, rounding=False)


def box(x0, y0, z0, x1, y1, z1, chamfer=0.0, material="default", rounding=True):
    """Axis-aligned box; chamfer = the edges' 45 degree chamfer, or with rounding (the default) their rounding radius
    (0 = a small edge by the box's size; boxes under 0.8 mm stay square)."""
    c = min(chamfer, 0.45 * min(x1 - x0, y1 - y0, z1 - z0))
    if rounding and _rounding[0] and not is_sharp(material):
        return prism_z(np.array([(x0, y0), (x1, y0), (x1, y1), (x0, y1)]), z0, z1, c, material)
    if c > 0:
        loop = np.array([(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c),
                         (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)])
    else:
        loop = np.array([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])
    return prism_z(loop, z0, z1, c, material, rounding=False)


def revolve(profile, segments, material, outward=True, start_angle=0.0, precision=False, rounding=True):
    """Lathe a (r, z) profile around the Z axis. Profile points with r == 0 collapse to the axis.
    Rounded by default: the profile's corners are filleted (points on the axis stay: a cap's centre) and `segments`
    of auto_curve_min_segments or more are a round, sampled to the chord error; fewer stay an intentional polygon."""
    prof = np.asarray(profile, dtype=float)
    if rounding and _rounding[0] and not is_sharp(material) and len(prof) >= 3:
        span = np.ptp(prof, axis=0)
        r = edge_radius(min(2 * np.max(np.abs(prof[:, 0])), max(span[1], 1e-6)))
        on_axis = np.abs(prof[:, 0]) < 1e-9
        pieces, chords, start = [], [], 0
        for i in range(1, len(prof)):
            if on_axis[i] or i == len(prof) - 1:
                piece, chord = fillet_loop(prof[start:i + 1], r, closed=False, arcs=True)
                pieces.append(piece if not pieces else piece[1:])
                chords.append(chord[:-1])                 # an open polyline: its last point has no edge
                start = i
        prof = np.concatenate(pieces)
        soft_rows = set(np.nonzero(np.concatenate(chords))[0])
        if segments >= EDGES["auto_curve_min_segments"]:
            precision = True
    else:
        soft_rows = set()
    if precision:
        segments = arc_segments(np.max(np.abs(prof[:, 0])), segments)
    t = start_angle + np.linspace(0, 2 * np.pi, segments, endpoint=False)
    rings = [np.stack([r * np.cos(t), r * np.sin(t), np.full(segments, z)], 1) for r, z in prof]
    m = Mesh()
    base = m.add_vertices(np.concatenate(rings))
    idx = lambda k, i: base + k * segments + (i % segments)
    for k in range(len(prof) - 1):
        for i in range(segments):
            f = [idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)]
            m.add_face(f, material, soft=k in soft_rows)
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


def perforated_band(radius, y0, y1, hole_radius, thickness, material, count=6, phase=0.0):
    """Closed curved sheet cells with real bevelled through-holes, axis +Y.
    Build in unwrapped (arc length, height) coordinates then evaluate on the cylinder.
    Each cell is closed independently; adjacent cells meet at identical boundaries."""
    half_w, half_h = radius * np.pi / count, (y1 - y0) / 2
    if hole_radius + thickness / 4 >= min(half_w, half_h):
        raise ValueError("hole does not fit the cylinder cell")
    n = max(32, 16 * int(np.ceil(arc_segments(hole_radius, 16) / 16)))
    angles = np.linspace(0, 2 * np.pi, n, endpoint=False)
    direction = np.stack([np.cos(angles), np.sin(angles)], 1)
    # Uniform boundary samples make staggered rows meet on the SAME cylinder chords.
    # Ray-projecting the hole's angles onto the rectangle would leave mismatched T-junctions.
    nx, ny = 3 * n // 8, n // 8
    boundary = np.array([(x, y) for y in (-half_h, half_h)
                         for x in np.linspace(-half_w, half_w, nx + 1)]
                        + [(x, y) for x in (-half_w, half_w)
                           for y in np.linspace(-half_h, half_h, ny + 1)[1:-1]])
    order = np.argsort(np.mod(np.arctan2(boundary[:, 1], boundary[:, 0]), 2 * np.pi))
    boundary = boundary[order]
    mouth = direction * hole_radius
    lip = direction * (hole_radius + thickness / 4)
    m = Mesh()
    for cell in range(count):
        theta = phase + 2 * np.pi * cell / count
        rings = []
        # Walk across the outer skin, down the cell boundary, back along the inner skin, up the bore.
        for uv, r in [(mouth, radius - thickness / 4), (lip, radius), (boundary, radius),
                      (boundary, radius - thickness), (mouth, radius - thickness)]:
            a = theta + uv[:, 0] / radius
            rings.append(np.stack([r * np.cos(a), (y0 + y1) / 2 + uv[:, 1], r * np.sin(a)], 1))
        piece = loft(rings + [rings[0]], material, cap_start=False, cap_end=False)
        orient_outward(piece)
        m.merge(piece)
    return m
