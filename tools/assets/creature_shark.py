"""Shark (鲨鱼, tools/creatures/SHARK_SPEC.md section 4): an original 3.5 m requiem-type shark. Creature space: metres,
+Y up, the nose towards -Z (starboard = +X), the origin on the body's axis half way along (the nose at z = -1.75, the
tail fin's tips at about z = +1.75) — the runtime bends every vertex sideways by its z (SharkBend), so nothing that
should stay rigid may live in `body` far from the axis it rides on.

A fusiform body lofted from superelliptic sections (a conical snout, the deepest girth just behind the gills, a slender
caudal peduncle with lateral keels), countershaded (slate blue-grey back, off-white belly along a wavy line that dips to
the mouth corners); five gill slits a side, small black eyes, nostrils under the snout; tapered airfoil fins — a tall
falcate first dorsal, broad pectorals swept back and down, pelvics, a small second dorsal and anal fin, a crescent tail
with the upper lobe the longer —; serrated teeth along the upper jaw's U and on the lower jaw's rim. The lower jaw is
its own node (`jaw`, hinged at the mouth's corners, opens about its local X)."""
import numpy as np

from meshkit import Mesh

NOSE, TAIL = -1.75, 1.75
UV_UNIT = 0.05                       # metres per UV unit (meshkit's box projection, pbr_textures.UV_UNIT)
RING = 72
N_EXP = 2.25                         # section squareness (2 = ellipse)
HINGE_Z = -1.03
MOUTH_FRONT_Z, MOUTH_CORNER_Z = -1.43, -1.06

# body profile: z, half width, top above the axis, bottom below it
PROFILE = np.array([
    (-1.75, 0.000, 0.000, 0.000),
    (-1.72, 0.045, 0.034, 0.024),
    (-1.66, 0.095, 0.070, 0.052),
    (-1.56, 0.150, 0.112, 0.090),
    (-1.42, 0.208, 0.160, 0.136),
    (-1.25, 0.268, 0.226, 0.192),
    (-1.00, 0.336, 0.308, 0.268),
    (-0.70, 0.384, 0.378, 0.338),
    (-0.40, 0.400, 0.418, 0.378),
    (-0.10, 0.380, 0.406, 0.366),
    (0.20, 0.330, 0.350, 0.310),
    (0.50, 0.250, 0.262, 0.232),
    (0.80, 0.162, 0.172, 0.150),
    (1.00, 0.112, 0.122, 0.106),
    (1.15, 0.082, 0.096, 0.086),
    (1.27, 0.070, 0.086, 0.080),
])


def _pchip(x, y, xq):
    """Monotone cubic (Fritsch-Carlson) through (x, y) at xq: a smooth profile without overshoot."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    h = np.diff(x)
    d = np.diff(y) / h
    m = np.zeros_like(y)
    m[0], m[-1] = d[0], d[-1]
    for i in range(1, len(x) - 1):
        if d[i - 1] * d[i] > 0:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
    xq = np.asarray(xq, float)
    k = np.clip(np.searchsorted(x, xq) - 1, 0, len(x) - 2)
    t = (xq - x[k]) / h[k]
    h00, h10, h01, h11 = 2 * t ** 3 - 3 * t ** 2 + 1, t ** 3 - 2 * t ** 2 + t, -2 * t ** 3 + 3 * t ** 2, t ** 3 - t ** 2
    return h00 * y[k] + h10 * h[k] * m[k] + h01 * y[k + 1] + h11 * h[k] * m[k + 1]


def hw(z):
    return _pchip(PROFILE[:, 0], PROFILE[:, 1], z)


def top(z):
    return _pchip(PROFILE[:, 0], PROFILE[:, 2], z)


def bottom(z):
    return _pchip(PROFILE[:, 0], PROFILE[:, 3], z)


def keel(z):
    """The peduncle's lateral keels: a little extra width along the sides near the tail."""
    return 0.018 * np.exp(-((z - 1.12) / 0.12) ** 2)


def section_point(z, theta):
    """The body's surface at z and angle theta (0 = starboard side, pi/2 = top)."""
    c, s = np.cos(theta), np.sin(theta)
    w = hw(z) + keel(z) * np.exp(-(s / 0.18) ** 2)
    x = w * np.sign(c) * np.abs(c) ** (2 / N_EXP)
    y = np.where(s >= 0, top(z), bottom(z)) * np.sign(s) * np.abs(s) ** (2 / N_EXP)
    return x, y


def underside_y(x, z):
    """The body's lower surface at (x, z) (|x| inside the section)."""
    w, b = hw(z), bottom(z)
    c = np.clip(np.abs(x) / max(w, 1e-6), 0, 1) ** (N_EXP / 2)
    return -b * np.sqrt(np.maximum(0.0, 1 - c * c)) ** (2 / N_EXP)


def mouth_half_width():
    return 0.62 * float(hw(MOUTH_CORNER_Z))


def mouth_arc_z(x):
    """The mouth's U seen from below: its front at the middle, its corners near the hinge."""
    xc = mouth_half_width()
    return MOUTH_FRONT_Z + (MOUTH_CORNER_Z - MOUTH_FRONT_Z) * np.clip(np.abs(x) / xc, 0, 1) ** 2


def countershade_y(z):
    """The countershading line: low at the head (the white reaches the mouth's corners), higher along the flank."""
    s = (z - NOSE) / (TAIL - NOSE)
    return -(0.34 - 0.20 * s) * bottom(z) + 0.012 * np.sin(z * 11.0) + 0.006 * np.sin(z * 27.0)


def _flip(mesh, i):
    mesh.f[i] = tuple(reversed(mesh.f[i]))
    if mesh.uv[i] is not None:
        mesh.uv[i] = list(reversed(mesh.uv[i]))


def _orient(mesh):
    """Makes every closed part of the mesh face outwards: neighbouring faces wound alike (each shared edge run in
    opposite directions, by a walk over the faces), then each part turned so its signed volume is positive."""
    from collections import defaultdict, deque
    edges = defaultdict(list)
    for fi, f in enumerate(mesh.f):
        for k in range(len(f)):
            a, b = f[k], f[(k + 1) % len(f)]
            edges[(min(a, b), max(a, b))].append((fi, a < b))
    seen, flip = [False] * len(mesh.f), [False] * len(mesh.f)
    parts = []
    for start in range(len(mesh.f)):
        if seen[start]:
            continue
        seen[start] = True
        part, queue = [start], deque([start])
        while queue:
            fi = queue.popleft()
            f = mesh.f[fi]
            for k in range(len(f)):
                a, b = f[k], f[(k + 1) % len(f)]
                forward = (a < b) != flip[fi]
                for fj, fwd in edges[(min(a, b), max(a, b))]:
                    if seen[fj]:
                        continue
                    flip[fj] = fwd == forward
                    seen[fj] = True
                    part.append(fj)
                    queue.append(fj)
        parts.append(part)
    for i in range(len(mesh.f)):
        if flip[i]:
            _flip(mesh, i)
    v = np.array(mesh.v)
    for part in parts:
        vol = 0.0
        for fi in part:
            f = mesh.f[fi]
            p0 = v[f[0]]
            for k in range(1, len(f) - 1):
                vol += np.dot(p0, np.cross(v[f[k]], v[f[k + 1]]))
        if vol < 0:
            for fi in part:
                _flip(mesh, fi)


# ------------------------------------------------------------------------------------------------ the body
def _stations():
    """Loft stations: dense at the snout and the head, even along the body."""
    head = NOSE + (np.linspace(0, 1, 30) ** 1.7) * 0.9
    rest = np.linspace(NOSE + 0.93, 1.27, 64)
    return np.concatenate([head[1:], rest])


BELLY_SEGS = 22                      # ring segments along each half of the belly (the back gets the rest)


def _ring_angles(z):
    """The ring's angles at z, from under the belly round: a row of vertices lies exactly on the countershading line
    on each side, so the white meets the grey along a smooth curve instead of stepping from face to face."""
    b = max(float(bottom(z)), 1e-6)
    share = np.clip(-float(countershade_y(z)) / b, 0.02, 0.98)
    tb = -np.arcsin(share ** (N_EXP / 2))                          # the line's angle on the starboard side
    back = RING - 2 * BELLY_SEGS
    right = np.linspace(-np.pi / 2, tb, BELLY_SEGS, endpoint=False)
    over = np.linspace(tb, np.pi - tb, back, endpoint=False)
    left = np.linspace(np.pi - tb, 1.5 * np.pi, BELLY_SEGS, endpoint=False)
    return np.concatenate([right, over, left])


def _body():
    m = Mesh()
    zs = _stations()
    rings, arcs = [], []
    for z in zs:
        theta = _ring_angles(z)                                    # from the belly round, the seam under the belly
        x, y = section_point(z, theta)
        pts = np.stack([x, y, np.full(RING, z)], 1)
        rings.append(m.add_vertices(pts))
        seg = np.linalg.norm(np.roll(pts, -1, 0) - pts, axis=1)
        arcs.append(np.concatenate([[0.0], np.cumsum(seg)]))
    tip = m.add_vertices([[0.0, 0.0, NOSE]])
    end = m.add_vertices([[0.0, 0.0, 1.31]])
    xc = mouth_half_width()

    def material(c, j):
        x, y, z = c
        if z < HINGE_Z and abs(x) < xc and z > mouth_arc_z(x) and y < -0.5 * bottom(z):
            return "mouth"
        return "skin_belly" if j < BELLY_SEGS or j >= RING - BELLY_SEGS else "skin_back"

    v = np.array(m.v)
    for i in range(len(zs) - 1):
        for j in range(RING):
            a, b = rings[i] + j, rings[i] + (j + 1) % RING
            c, d = rings[i + 1] + (j + 1) % RING, rings[i + 1] + j
            uv = [(zs[i] / UV_UNIT, arcs[i][j] / UV_UNIT), (zs[i] / UV_UNIT, arcs[i][j + 1] / UV_UNIT),
                  (zs[i + 1] / UV_UNIT, arcs[i + 1][j + 1] / UV_UNIT), (zs[i + 1] / UV_UNIT, arcs[i + 1][j] / UV_UNIT)]
            centre = v[[a, b, c, d]].mean(0)
            m.add_face((a, b, c, d), material(centre, j), uv, soft=True)
    for j in range(RING):
        a, b = rings[0] + j, rings[0] + (j + 1) % RING
        m.add_face((tip, b, a), material(v[[a, b]].mean(0), j), None, soft=True)
        a, b = rings[-1] + j, rings[-1] + (j + 1) % RING
        m.add_face((a, b, end), "skin_back", None, soft=True)
    _orient(m)
    return m


# ------------------------------------------------------------------------------------------------ fins
def _thickness(c):
    """A symmetric airfoil's half thickness at chord share c (NACA 00xx, closed trailing edge), 1 at its thickest."""
    f = 0.2969 * np.sqrt(c) - 0.126 * c - 0.3516 * c ** 2 + 0.2843 * c ** 3 - 0.1036 * c ** 4
    return f / 0.06002


def _fin(sections, normal, mat, chord=16):
    """A tapered airfoil fin: sections [(leading edge, trailing edge, thickness)] from root to tip, its thickness along
    `normal` (or a per-section normal). Both ends closed."""
    m = Mesh()
    cs = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, chord))           # dense at both edges
    loops = []
    for k, (le, te, th) in enumerate(sections):
        le, te = np.asarray(le, float), np.asarray(te, float)
        n = np.asarray(normal[k] if np.ndim(normal) == 2 else normal, float)
        n = n / np.linalg.norm(n)
        half = 0.5 * th * _thickness(cs)
        line = le[None, :] + cs[:, None] * (te - le)[None, :]
        upper = line + half[:, None] * n
        lower = line - half[:, None] * n
        loop = np.concatenate([upper, lower[-2:0:-1]])
        loops.append(m.add_vertices(loop))
    count = 2 * chord - 2
    for k in range(len(loops) - 1):
        for j in range(count):
            m.add_face((loops[k] + j, loops[k] + (j + 1) % count, loops[k + 1] + (j + 1) % count, loops[k + 1] + j), mat,
                       None, soft=True)
    for which in (0, len(loops) - 1):
        pts = np.array(m.v[loops[which]:loops[which] + count])
        centre = m.add_vertices([pts.mean(0)])
        for j in range(count):
            m.add_face((loops[which] + j, loops[which] + (j + 1) % count, centre), mat, None, soft=True)
    _orient(m)
    return m


def _shade_fin(mesh):
    """Countershading on a fin: faces turned downwards white, the rest the back's grey."""
    v = np.array(mesh.v)
    for i, f in enumerate(mesh.f):
        p = v[list(f)]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        if np.linalg.norm(n) > 0 and n[1] / np.linalg.norm(n) < -0.35:
            mesh.m[i] = "skin_belly"
    return mesh


def _mirror(mesh):
    return mesh.transformed(np.diag([-1.0, 1.0, 1.0, 1.0]))


def _fins():
    out = Mesh()
    X = (1.0, 0.0, 0.0)
    dorsal = [((0, 0.30, -0.66), (0, 0.30, 0.06), 0.062), ((0, 0.52, -0.46), (0, 0.52, 0.00), 0.050),
              ((0, 0.68, -0.29), (0, 0.66, -0.02), 0.038), ((0, 0.82, -0.13), (0, 0.80, -0.01), 0.024),
              ((0, 0.92, -0.01), (0, 0.91, 0.04), 0.012), ((0, 0.975, 0.06), (0, 0.968, 0.085), 0.004)]
    out.merge(_fin(dorsal, X, "skin_back"))
    second = [((0, 0.13, 0.60), (0, 0.13, 0.82), 0.030), ((0, 0.24, 0.72), (0, 0.23, 0.84), 0.018),
              ((0, 0.31, 0.82), (0, 0.30, 0.87), 0.004)]
    out.merge(_fin(second, X, "skin_back"))
    anal = [((0, -0.10, 0.64), (0, -0.10, 0.88), 0.030), ((0, -0.20, 0.78), (0, -0.19, 0.89), 0.016),
            ((0, -0.26, 0.87), (0, -0.25, 0.92), 0.004)]
    out.merge(_shade_fin(_fin(anal, X, "skin_back")))
    upper = [((0, 0.06, 1.05), (0, 0.02, 1.38), 0.075), ((0, 0.30, 1.24), (0, 0.19, 1.43), 0.048),
             ((0, 0.55, 1.44), (0, 0.45, 1.56), 0.030), ((0, 0.76, 1.62), (0, 0.70, 1.69), 0.016),
             ((0, 0.865, 1.735), (0, 0.845, 1.755), 0.004)]
    out.merge(_fin(upper, X, "skin_back"))
    lower = [((0, -0.04, 1.08), (0, 0.00, 1.37), 0.070), ((0, -0.21, 1.25), (0, -0.13, 1.41), 0.045),
             ((0, -0.36, 1.40), (0, -0.30, 1.49), 0.028), ((0, -0.47, 1.52), (0, -0.44, 1.57), 0.014),
             ((0, -0.53, 1.585), (0, -0.52, 1.60), 0.004)]
    out.merge(_fin(lower, X, "skin_back"))

    def paired(sections):
        normals = []
        for k, (le, te, _) in enumerate(sections):
            nxt = sections[min(k + 1, len(sections) - 1)][0] if k + 1 < len(sections) else le
            prv = sections[k - 1][0] if k > 0 else le
            span = np.asarray(nxt, float) - np.asarray(prv, float)
            n = np.cross(np.asarray(te, float) - np.asarray(le, float), span)
            normals.append(n if n[1] > 0 else -n)
        return normals

    pectoral = [((0.30, -0.17, -0.84), (0.29, -0.20, -0.42), 0.075), ((0.50, -0.28, -0.70), (0.49, -0.30, -0.42), 0.055),
                ((0.66, -0.37, -0.53), (0.65, -0.38, -0.38), 0.034), ((0.78, -0.43, -0.38), (0.77, -0.44, -0.31), 0.016),
                ((0.84, -0.465, -0.27), (0.84, -0.465, -0.25), 0.004)]
    right = _shade_fin(_fin(pectoral, paired(pectoral), "skin_back"))
    out.merge(right)
    out.merge(_mirror(right))
    pelvic = [((0.10, -0.24, 0.34), (0.08, -0.22, 0.58), 0.040), ((0.18, -0.33, 0.50), (0.17, -0.32, 0.64), 0.022),
              ((0.24, -0.39, 0.62), (0.24, -0.39, 0.67), 0.004)]
    right = _shade_fin(_fin(pelvic, paired(pelvic), "skin_back"))
    out.merge(right)
    out.merge(_mirror(right))
    return out


# ------------------------------------------------------------------------------------------------ details
def _sphere(c, r, mat, rings=10, segs=16):
    m = Mesh()
    c = np.asarray(c, float)
    rows = []
    for i in range(1, rings):
        phi = np.pi * i / rings
        t = 2 * np.pi * np.arange(segs) / segs
        rows.append(m.add_vertices(np.stack([c[0] + r * np.sin(phi) * np.cos(t), c[1] + r * np.cos(phi) * np.ones(segs),
                                             c[2] + r * np.sin(phi) * np.sin(t)], 1)))
    top_i = m.add_vertices([c + (0, r, 0)])
    bot_i = m.add_vertices([c - (0, r, 0)])
    for i in range(len(rows) - 1):
        for j in range(segs):
            m.add_face((rows[i] + j, rows[i] + (j + 1) % segs, rows[i + 1] + (j + 1) % segs, rows[i + 1] + j), mat, None, True)
    for j in range(segs):
        m.add_face((top_i, rows[0] + (j + 1) % segs, rows[0] + j), mat, None, True)
        m.add_face((bot_i, rows[-1] + j, rows[-1] + (j + 1) % segs), mat, None, True)
    _orient(m)
    return m


def _eyes():
    z = -1.30
    out = Mesh()
    for side in (1, -1):
        theta = np.radians(24) if side > 0 else np.pi - np.radians(24)
        x, y = section_point(z, theta)
        out.merge(_sphere((float(x) - side * 0.012, float(y), z), 0.030, "eye"))
    return out


def _strip(points, normal_out, width_dir, width, mat):
    """A thin band along `points` (on a surface, lifted along `normal_out`), `width` across `width_dir`."""
    m = Mesh()
    p = np.asarray(points, float)
    a = m.add_vertices(p - 0.5 * width * np.asarray(width_dir))
    b = m.add_vertices(p + 0.5 * width * np.asarray(width_dir))
    for k in range(len(p) - 1):
        m.add_face((a + k, a + k + 1, b + k + 1, b + k), mat, None, False)
    # face it outwards
    v = np.array(m.v)
    f = m.f[0]
    n = np.cross(v[f[1]] - v[f[0]], v[f[2]] - v[f[0]])
    if np.dot(n, normal_out(p[0])) < 0:
        m.f = [tuple(reversed(x)) for x in m.f]
    return m


def _gills():
    out = Mesh()
    for side in (1, -1):
        for k in range(5):
            z0 = -0.99 + k * 0.072
            pts = []
            for t in np.linspace(np.radians(-24), np.radians(20), 9):
                theta = t if side > 0 else np.pi - t
                z = z0 + 0.03 * (t / np.radians(30))           # slanted back towards the top
                x, y = section_point(z, theta)
                pts.append((float(x) + side * 0.004, float(y), z))
            out.merge(_strip(pts, lambda q, s=side: np.array([s, 0.0, 0.0]), (0, 0, 1), 0.007, "gill"))
    return out


def _nostrils():
    out = Mesh()
    z = -1.58
    for side in (1, -1):
        x = side * 0.07
        y = float(underside_y(x, z)) - 0.003
        t = np.linspace(0, 2 * np.pi, 12, endpoint=False)
        pts = np.stack([x + 0.018 * np.cos(t), np.full(12, y), z + 0.008 * np.sin(t)], 1)
        m = Mesh()
        m.add_poly(pts, "gill")
        n = np.cross(pts[1] - pts[0], pts[2] - pts[0])
        if n[1] > 0:
            m.f = [tuple(reversed(f)) for f in m.f]
        out.merge(m)
    return out


def _tooth(base, along, down, inward, width, height, mat="tooth", thick=0.006):
    """A flat serrated-looking blade: a triangle `width` wide along `along`, `height` long down `down`, `thick` deep."""
    base, along, down, inward = (np.asarray(v, float) for v in (base, along, down, inward))
    along, down, inward = along / np.linalg.norm(along), down / np.linalg.norm(down), inward / np.linalg.norm(inward)
    a, b = base - along * width / 2, base + along * width / 2
    tip = base + down * height
    m = Mesh()
    front = m.add_vertices([a, b, tip])
    back = m.add_vertices([a + inward * thick, b + inward * thick, tip + inward * thick * 0.4])
    m.add_face((front, front + 1, front + 2), mat)
    m.add_face((back, back + 2, back + 1), mat)
    for i, j in ((0, 1), (1, 2), (2, 0)):
        m.add_face((front + i, back + i, back + j, front + j), mat)
    _orient(m)
    return m


def _upper_teeth():
    out = Mesh()
    xc = mouth_half_width()
    for x in np.linspace(-xc * 0.94, xc * 0.94, 25):
        z = float(mouth_arc_z(x)) + 0.012
        y = float(underside_y(x, z))
        # along the U, pointing down and a little back, leaning in
        dz = 2 * (MOUTH_CORNER_Z - MOUTH_FRONT_Z) * np.sign(x) * abs(x) / xc ** 2
        along = np.array([1.0, 0.0, dz])
        inward = np.array([-x, 0.0, HINGE_Z - z])
        if np.linalg.norm(inward) < 1e-6:
            inward = np.array([0.0, 0.0, 1.0])
        inward = inward / np.linalg.norm(inward)
        out.merge(_tooth((x, y + 0.004, z), along, (0.0, -1.0, 0.25), inward, 0.026, 0.021))
    return out


# ------------------------------------------------------------------------------------------------ the jaw
def jaw_pivot():
    return np.array([0.0, float(underside_y(0.0, HINGE_Z)) + 0.03, HINGE_Z])


def _jaw():
    """The lower jaw: a thick shell under the head's mouth, skin outside, mouth flesh inside, teeth on its rim."""
    m = Mesh()
    xc = mouth_half_width()
    zs = np.linspace(MOUTH_FRONT_Z - 0.035, HINGE_Z, 16)
    cols = 17
    outer_rows, inner_rows = [], []
    for z in zs:
        frac = np.clip((z - (MOUTH_FRONT_Z - 0.035)) / (MOUTH_CORNER_Z - MOUTH_FRONT_Z + 0.035), 0, 1)
        wj = max(0.035, (xc + 0.03) * np.sqrt(frac))
        xs = np.linspace(-wj, wj, cols)
        ys = np.array([float(underside_y(x, z)) for x in xs])
        # thickest under the chin, thinning to a lip along the mouth's U and towards the hinge, so the closed jaw
        # blends into the head as a crease (the teeth just show along it)
        edge = 1 - (np.abs(xs) / wj) ** 6
        back = np.clip((HINGE_Z - z) / 0.10, 0, 1)
        proud = 0.004 + 0.020 * edge * back
        outer_rows.append(m.add_vertices(np.stack([xs * 1.01, ys - proud, np.full(cols, z)], 1)))
        inner_rows.append(m.add_vertices(np.stack([xs * 0.97, ys + 0.010, np.full(cols, z)], 1)))
    n = len(zs)
    for i in range(n - 1):
        for j in range(cols - 1):
            o = (outer_rows[i] + j, outer_rows[i] + j + 1, outer_rows[i + 1] + j + 1, outer_rows[i + 1] + j)
            m.add_face(o, "skin_belly", None, True)
            k = (inner_rows[i] + j, inner_rows[i + 1] + j, inner_rows[i + 1] + j + 1, inner_rows[i] + j + 1)
            m.add_face(k, "mouth", None, True)
    # the rim round the shell: both sides, the front lip, the back at the hinge
    for i in range(n - 1):
        for j in (0, cols - 1):
            m.add_face((outer_rows[i] + j, outer_rows[i + 1] + j, inner_rows[i + 1] + j, inner_rows[i] + j), "skin_belly")
    for i, row in ((0, 0), (n - 1, n - 1)):
        for j in range(cols - 1):
            m.add_face((outer_rows[row] + j, inner_rows[row] + j, inner_rows[row] + j + 1, outer_rows[row] + j + 1),
                       "skin_belly")
    # the shell is one closed part: face it outwards by its volume
    _orient(m)
    # lower teeth along the inner rim, pointing up
    v = np.array(m.v)
    for i in range(1, n - 3, 1):
        for j in (1, cols - 2):
            p = v[inner_rows[i] + j]
            nxt = v[inner_rows[i + 1] + j]
            along = nxt - p
            inward = np.array([-np.sign(p[0]), 0.0, 0.0])
            m.merge(_tooth(p + (0, 0.004, 0), along, (0.0, 1.0, 0.15), inward, 0.022, 0.018))
    for j in range(2, cols - 2, 2):
        p = v[inner_rows[0] + j]
        m.merge(_tooth(p + (0, 0.004, 0.006), (1.0, 0.0, 0.0), (0.0, 1.0, 0.2), (0.0, 0.0, 1.0), 0.022, 0.018))
    return m


def build(rig):
    rig.add("body", _body())
    rig.add("body", _fins())
    rig.add("body", _eyes())
    rig.add("body", _gills())
    rig.add("body", _nostrils())
    rig.add("body", _upper_teeth())
    rig.node("jaw", "body", jaw_pivot())
    rig.add("jaw", _jaw())
