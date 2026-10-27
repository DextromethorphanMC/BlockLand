"""Organic forms and small hardware for the rifles' detail pass (VIEWMODEL_SPEC section 53: what the shared primitives
do not round — tapers, organic furniture — is rounded here). Every builder returns a meshkit.Mesh.

* `frame_loft`: a loft of 2D sections placed in their own frames along a path, its ends domed by quarter rounds (no flat
  slab ends), the round's bands marked soft.
* `section`: a rounded cross-section by its half width, top, bottom and squareness (superellipse corners), optionally
  flattened on top / bottom, with a dent along each side (finger grooves, palm swells).
* hardware: slotted / hex / Torx-like socket screws, pins, rivets, dimples, sling swivels."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from model_quality import arc_segments, fillet_segments


def _unit(v):
    v = np.asarray(v, float)
    return v / (np.linalg.norm(v) + 1e-30)


def section(hw, top, bottom, n=2.6, count=28, flat_top=0.0, flat_bottom=0.0, dent=0.0, dent_y=None, dent_w=None,
            front=None):
    """A CCW cross-section (x, y): superellipse of half width `hw` between `bottom` and `top` (n: 2 = ellipse, higher =
    squarer), `flat_top` / `flat_bottom` (0..1) flattening those faces, `dent` (m) pressed into both sides around
    `dent_y` over `dent_w` (a groove along the part). `front` != None skews the left/right halves (asymmetric)."""
    cy, b = (top + bottom) / 2, (top - bottom) / 2
    t = np.linspace(0, 2 * np.pi, count, endpoint=False)
    c, s = np.cos(t), np.sin(t)
    x = hw * np.sign(c) * np.abs(c) ** (2 / n)
    y = b * np.sign(s) * np.abs(s) ** (2 / n)
    if flat_top:
        y = np.where(y > 0, np.minimum(y, b * (1 - 0.5 * flat_top) + (y - b * (1 - 0.5 * flat_top)) * (1 - flat_top)), y)
    if flat_bottom:
        y = np.where(y < 0, np.maximum(y, -b * (1 - 0.5 * flat_bottom) + (y + b * (1 - 0.5 * flat_bottom)) * (1 - flat_bottom)), y)
    y = y + cy
    if dent:
        dy = cy if dent_y is None else dent_y
        dw = 0.3 * (top - bottom) if dent_w is None else dent_w
        fall = np.exp(-((y - dy) / (0.5 * dw)) ** 2)
        x = x - np.sign(x) * dent * fall * (np.abs(x) > 0.6 * hw)
    return np.stack([x, y], 1)


def frame_loft(stations, material, round0=0.0, round1=0.0, cap=True):
    """stations: [(loop2d (N, 2) CCW, origin (3,), x_axis (3,), y_axis (3,))] along the part (same N). The first and
    last ends are domed by quarter rounds of `round0` / `round1` metres (stations closer to an end than its round are
    replaced by the dome); the dome's bands are soft (smooth shaded)."""
    loops = [np.asarray(s[0], float) for s in stations]
    org = [np.asarray(s[1], float) for s in stations]
    xs = [_unit(s[2]) for s in stations]
    ys = [_unit(s[3]) for s in stations]

    def ring(loop, o, x, y):
        return o + loop[:, :1] * x + loop[:, 1:2] * y

    def dist(a, b):
        return float(np.linalg.norm(org[a] - org[b]))

    first, last = 0, len(stations) - 1
    rings, soft = [], []
    if round0 > 0:
        d0 = _unit(org[1] - org[0])
        for k, (dx, inset) in enumerate(reversed(sh.quarter_round(round0))):
            rings.append(ring(sh.offset_loop(loops[0], inset), org[0] + d0 * dx, xs[0], ys[0]))
            soft.append(len(rings) - 1)
        while first + 1 < last and dist(first + 1, 0) <= round0 * 1.05:
            first += 1
        first += 1
    tail = []
    if round1 > 0:
        d1 = _unit(org[-2] - org[-1])
        for dx, inset in sh.quarter_round(round1):
            tail.append(ring(sh.offset_loop(loops[-1], inset), org[-1] + d1 * dx, xs[-1], ys[-1]))
        while last - 1 > first and dist(last - 1, len(stations) - 1) <= round1 * 1.05:
            last -= 1
        last -= 1
    for k in range(first, last + 1):
        rings.append(ring(loops[k], org[k], xs[k], ys[k]))
    start_tail = len(rings) - 1
    rings.extend(tail)
    soft += list(range(start_tail, len(rings) - 1))
    if round0 > 0:
        soft = [b for b in soft if b < len(rings) - 1]
    return sh.loft(np.array(rings), material, cap_start=cap, cap_end=cap, rounding=False, soft_bands=soft)


def straight_stations(loop_fn, z0, z1, count, x=(1, 0, 0), y=(0, 1, 0), origin_fn=None):
    """Stations along +Z from z0 to z1: loop_fn(u) -> loop, origin_fn(u) -> (x, y) offset of the station."""
    out = []
    for k in range(count + 1):
        u = k / count
        o = (0.0, 0.0) if origin_fn is None else origin_fn(u)
        out.append((loop_fn(u), np.array([o[0], o[1], z0 + (z1 - z0) * u]), np.array(x, float), np.array(y, float)))
    return out


# ------------------------------------------------------------------------------------------- hardware
def _placed(m, center, normal):
    """Mesh built along +Z (its face at z = 0, standing out to +z) placed at center facing `normal`."""
    n = _unit(normal)
    a = np.array([0.0, 1.0, 0.0]) if abs(n[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    x = _unit(np.cross(a, n))
    y = np.cross(n, x)
    return sh.place(m, origin=center, x_axis=x, y_axis=y, z_axis=n)


def screw(center, normal, r=0.0022, h=0.0008, kind="hex", material="steel_dark", recess=None, angle=0.0):
    """A screw head standing `h` out of a surface: `hex` (a sunk socket), `slot` or `cross` (`recess` material lines
    across the head; default the head's own). The socket is real geometry, so the baked AO darkens it."""
    seg = max(12, arc_segments(r, 12))
    if kind in ("hex", "torx"):
        ri, depth = r * 0.46, min(0.0009, h * 0.9)
        prof = [(0.0, h - depth), (ri, h - depth), (ri, h), (r * 0.80, h), (r, h * 0.45), (r, -0.0004), (0.0, -0.0004)]
        m = sh.revolve(prof, seg, material, precision=True)
    else:
        m = sh.revolve([(0.0, h), (r * 0.80, h), (r, h * 0.45), (r, -0.0004), (0.0, -0.0004)], seg, material,
                       precision=True)
        w, l = r * 0.20, r * 0.90
        for k in range(2 if kind == "cross" else 1):
            a = angle + k * np.pi / 2
            ca, sa = np.cos(a), np.sin(a)
            loop = np.array([(-l * ca + w * sa, -l * sa - w * ca), (l * ca + w * sa, l * sa - w * ca),
                             (l * ca - w * sa, l * sa + w * ca), (-l * ca - w * sa, -l * sa + w * ca)])
            m.merge(sh.prism_z(loop, h - 0.0002, h + 0.00004, 0.0, recess or material, rounding=False))
    return _placed(m, center, normal)


def pin(center, normal, r=0.0016, h=0.0003, material="steel"):
    """A flush cross pin's end: a shallow dome barely standing out."""
    seg = max(10, arc_segments(r, 10))
    m = sh.revolve([(0.0, h), (r * 0.6, h * 0.85), (r, 0.0), (r, -0.0004), (0.0, -0.0004)], seg, material, precision=True)
    return _placed(m, center, normal)


def rivet(center, normal, r=0.0019, h=0.0009, material="steel"):
    seg = max(10, arc_segments(r, 10))
    m = sh.revolve([(0.0, h), (r * 0.55, h * 0.9), (r, h * 0.2), (r, -0.0005), (0.0, -0.0005)], seg, material,
                   precision=True)
    return _placed(m, center, normal)


def dimple(center, normal, a, b, depth, material, up=(0.0, 1.0, 0.0), count=20):
    """A stamped oval dimple (the AKM's magazine-well dimple): a shallow dish of half axes a (along `up`'s cross) by b
    (along `up`) pressed `depth` into a surface facing `normal`, drawn as its rim and floor just proud of the surface."""
    n = _unit(normal)
    v = _unit(up)
    u = _unit(np.cross(v, n))
    rings = []
    for k, (s, z) in enumerate(((1.08, 0.00025), (1.0, 0.00012), (0.82, -depth * 0.55), (0.55, -depth * 0.9),
                                (0.25, -depth))):
        t = np.linspace(0, 2 * np.pi, count, endpoint=False)
        pts = [center + n * z + u * (a * s * np.cos(q)) + v * (b * s * np.sin(q)) for q in t]
        rings.append(np.array(pts))
    m = sh.loft(np.array(rings), material, cap_start=False, cap_end=True, rounding=False, auto_orient=False,
                soft_bands=range(4))
    # faces must look out along the normal
    _orient_to(m, n)
    return m


def _orient_to(m, n):
    v = np.asarray(m.v)
    flips = 0
    for i, f in enumerate(m.f):
        p = v[list(f)]
        fn = np.cross(p[1] - p[0], p[2] - p[0])
        if np.dot(fn, n) < 0:
            flips += 1
    if flips > len(m.f) / 2:
        m.f = [list(reversed(f)) for f in m.f]
        m.uv = [None if uv is None else list(reversed(uv)) for uv in m.uv]


def swivel(center, axis, r=0.0085, wire=0.0016, material="steel"):
    """A sling swivel loop: a torus of wire radius `wire` around `axis` (its plane perpendicular to it)."""
    a = _unit(axis)
    seg_r = 18
    seg_w = 8
    t = np.linspace(0, 2 * np.pi, seg_r, endpoint=False)
    rings = []
    ref = np.array([0.0, 1.0, 0.0]) if abs(a[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    e1 = _unit(np.cross(ref, a))
    e2 = np.cross(a, e1)
    for q in np.append(t, t[0]):
        c = center + r * (np.cos(q) * e1 + np.sin(q) * e2)
        radial = np.cos(q) * e1 + np.sin(q) * e2
        w = np.linspace(0, 2 * np.pi, seg_w, endpoint=False)
        rings.append(np.array([c + wire * (np.cos(p) * radial + np.sin(p) * a) for p in w]))
    m = sh.loft(np.array(rings), material, cap_start=False, cap_end=False, rounding=False, auto_orient=False,
                soft_bands=range(seg_r), soft_cols=range(seg_w))
    sh.orient_outward(m)                                    # a closed torus: faces out
    return m


def groove_ring(radius, z, width, depth, material, seg=None, axis_xy=(0.0, 0.0)):
    """A turned groove around a Z-axis part (a dark band slightly inside its surface, e.g. on a barrel or a nut)."""
    seg = seg or arc_segments(radius, 24)
    x, y = axis_xy
    return sh.revolve([(radius + 0.00008, z - width / 2), (radius - depth, z - width / 2 + depth * 0.6),
                       (radius - depth, z + width / 2 - depth * 0.6), (radius + 0.00008, z + width / 2)],
                      seg, material, precision=True).translated((x, y, 0.0))


def along(points, steps):
    """Arc-length resampling of a 3D polyline to `steps` + 1 points; returns (points, tangents)."""
    p = np.asarray(points, float)
    seg = np.linalg.norm(np.diff(p, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    t = np.linspace(0.0, s[-1], steps + 1)
    out = np.stack([np.interp(t, s, p[:, i]) for i in range(3)], 1)
    tan = np.gradient(out, axis=0)
    tan /= np.linalg.norm(tan, axis=1)[:, None] + 1e-30
    return out, tan
