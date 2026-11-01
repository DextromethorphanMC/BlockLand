"""Fighter jet (战斗机, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original light single-seat, single-engine
fighter sized for the game (12.4 m long, 8.6 m span). Vehicle space: metres, +Y up, the nose towards -Z (its right is
+X), the origin on the ground under the middle of the wheelbase.

Fuselage: a lofted superellipse body from the pointed radome (its own darker shade, a pitot boom ahead of it) back to
the engine bay, the cockpit under a one-piece bubble canopy (ejection seat, coaming with three displays, the HUD,
stick, throttle and side consoles inside), a dorsal spine behind it, raised access panels, an anti-glare panel, blade
antennas, AoA vanes, the cannon in a blister on the left of the nose. Two raked cheek intakes under the leading-edge
root extensions; a cropped-delta wing with split flaps and ailerons (each its own surface, so the hinge gaps read),
wingtip launch rails carrying short-range missiles, nav lights (red port, green starboard), under-wing pylons with
rocket pods; all-moving tailplanes, one swept fin with its rudder, two canted ventral strakes. The engine's
convergent-divergent nozzle with its petals and flame holder; the afterburner's flame core (`afterburner`) burns
behind it while boosting.

Tricycle gear on their own nodes, folding by +95 degrees about each node's local X: the nose leg forward into the
nose (landing light, torque links, a leg door that closes the bay), the main legs inward into the fuselage sides."""
import numpy as np

import rifle_forms as rf
import shapes as sh
import vehicle_parts as vp
from meshkit import Mesh
from vehicle_parts import X, Y, Z

# ---------------------------------------------------------------------------------------- contract points
SEAT = (0.0, 2.0, -3.2)
MUZZLE_CANNON = (-0.55, 1.85, -5.0)
MUZZLE_ROCKETS = (-2.4, 1.12, -0.8)
EXHAUST = (0.0, 1.75, 6.0)
HEADLIGHT = (0.0, 0.95, -2.35)

AXIS_Y = 1.75                                 # the engine's axis
NOSE_HINGE = np.array([0.0, 1.45, -2.38])
NOSE_AXLE = np.array([0.0, 0.25, -2.20])
NOSE_R, NOSE_W = 0.25, 0.16
MAIN_HINGE = np.array([1.12, 1.32, 2.20])     # the right leg; the left is mirrored
MAIN_AXLE = np.array([1.30, 0.33, 2.20])
MAIN_R, MAIN_W = 0.33, 0.22

WING_Y = 1.55
WING_ROOT, WING_TIP = 0.55, 4.30


# ------------------------------------------------------------------------------------------- curves
def _pchip(xs, ys):
    """A monotone cubic through (xs, ys) (no overshoot between the stations): f(x)."""
    xs, ys = np.asarray(xs, float), np.asarray(ys, float)
    h = np.diff(xs)
    d = np.diff(ys) / h
    m = np.zeros_like(ys)
    for k in range(1, len(xs) - 1):
        if d[k - 1] * d[k] > 0:
            w1, w2 = 2 * h[k] + h[k - 1], h[k] + 2 * h[k - 1]
            m[k] = (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
    m[0], m[-1] = d[0], d[-1]

    def f(x):
        x = float(np.clip(x, xs[0], xs[-1]))
        k = int(np.clip(np.searchsorted(xs, x) - 1, 0, len(xs) - 2))
        t = (x - xs[k]) / h[k]
        t2, t3 = t * t, t * t * t
        return ((2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h[k] * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1]
                + (t3 - t2) * h[k] * m[k + 1])
    return f


#        z      half width  top    bottom  squareness
_FUS = [(-6.60, 0.020, 1.715, 1.685, 2.0),
        (-6.40, 0.105, 1.775, 1.615, 2.0),
        (-6.10, 0.200, 1.850, 1.535, 2.0),
        (-5.70, 0.300, 1.945, 1.430, 2.3),
        (-5.20, 0.395, 2.060, 1.320, 2.7),
        (-4.70, 0.460, 2.130, 1.245, 2.9),
        (-4.20, 0.505, 2.140, 1.200, 3.0),
        (-3.50, 0.545, 2.100, 1.170, 3.0),
        (-2.80, 0.585, 2.110, 1.155, 3.0),
        (-2.10, 0.635, 2.200, 1.150, 3.0),
        (-1.30, 0.700, 2.345, 1.150, 2.9),
        (-0.30, 0.735, 2.415, 1.152, 3.0),
        (0.90, 0.735, 2.435, 1.160, 3.0),
        (2.00, 0.705, 2.405, 1.180, 2.9),
        (3.00, 0.640, 2.345, 1.225, 2.7),
        (3.90, 0.560, 2.275, 1.280, 2.5),
        (4.60, 0.500, 2.225, 1.310, 2.3),
        (5.05, 0.470, 2.205, 1.320, 2.2)]
_HW = _pchip([s[0] for s in _FUS], [s[1] for s in _FUS])
_TOP = _pchip([s[0] for s in _FUS], [s[2] for s in _FUS])
_BOT = _pchip([s[0] for s in _FUS], [s[3] for s in _FUS])
_N = _pchip([s[0] for s in _FUS], [s[4] for s in _FUS])
RADOME_END = -5.55


def fus_point(z, t, grow=0.0):
    """The fuselage skin at station z, angle t round it (0 = right side, pi/2 = top), pushed out by `grow`."""
    hw, top, bot, n = _HW(z) + grow, _TOP(z) + grow, _BOT(z) - grow, _N(z)
    b, cy = (top - bot) / 2, (top + bot) / 2
    c, s = np.cos(t), np.sin(t)
    return np.array([hw * np.sign(c) * abs(c) ** (2 / n), cy + b * np.sign(s) * abs(s) ** (2 / n), z])


def _fus_stations(zs):
    return [(z, 0.0, 0.0, _HW(z), _TOP(z), _BOT(z), _N(z), 0.0, 0.0) for z in zs]


# ------------------------------------------------------------------------------------------- airfoils
def _thickness(u):
    """NACA four-digit half thickness (a fraction of the chord per unit thickness ratio), closed trailing edge."""
    return 5 * (0.2969 * np.sqrt(u) - 0.1260 * u - 0.3516 * u ** 2 + 0.2843 * u ** 3 - 0.1036 * u ** 4)


def airfoil(a=0.0, b=1.0, count=14):
    """The loop of a symmetric section between chord fractions a..b, (u, thickness) per point, unit thickness ratio:
    the upper side from b forward to a, the lower back to b (a cut end is flat)."""
    k = np.linspace(0, 1, count)
    us = a + (b - a) * (0.5 - 0.5 * np.cos(np.pi * k))
    upper = [(u, _thickness(u)) for u in us[::-1]]
    lower = [(u, -_thickness(u)) for u in us]
    if a <= 1e-9:
        lower = lower[1:]                       # the leading edge is one point
    if b >= 1 - 1e-9:
        lower = lower[:-1]                      # so is the trailing edge
    return np.array(upper + lower)


def surface_loft(stations, mat, a=0.0, b=1.0, count=14):
    """A lifting surface: stations [(origin of the leading edge (3,), chord axis (3,), thickness axis (3,), chord,
    thickness ratio)] along its span; the section between chord fractions a..b, every facet a round's."""
    loop = airfoil(a, b, count)
    rings = []
    for o, cax, tax, chord, ratio in stations:
        o, cax, tax = np.asarray(o, float), vp.unit(cax), vp.unit(tax)
        rings.append([o + cax * u * chord + tax * t * ratio * chord for u, t in loop])
    return sh.loft(np.array(rings), mat, rounding=False, soft_cols=range(len(loop)))


def _fus_inside(p):
    return np.array([0.0, AXIS_Y - 0.05, p[2]])


def _patch(fn, u0, u1, v0, v1, mat, lift=0.004, depth=0.006, nu=7, nv=5, inside=_fus_inside):
    """A thin panel standing `lift` proud of the surface fn(u, v) -> point over [u0, u1] x [v0, v1] (an access panel,
    a hatch): its top follows the surface, its walls drop into it. inside(p): a point inside the solid under p."""
    us, vs = np.linspace(u0, u1, nu), np.linspace(v0, v1, nv)
    P = np.array([[fn(u, v) for v in vs] for u in us])
    du, dv = np.gradient(P, axis=0), np.gradient(P, axis=1)
    N = np.cross(du, dv)
    N /= np.linalg.norm(N, axis=2, keepdims=True) + 1e-12
    out = np.array([[p - inside(p) for p in row] for row in P])
    if np.sum(N * out) < 0:
        N = -N
    top, bot = P + N * lift, P - N * depth
    m = Mesh()
    bt = m.add_vertices(top.reshape(-1, 3))
    bb = m.add_vertices(bot.reshape(-1, 3))
    T = lambda i, j: bt + i * nv + j
    B = lambda i, j: bb + i * nv + j
    for i in range(nu - 1):
        for j in range(nv - 1):
            m.add_face([T(i, j), T(i + 1, j), T(i + 1, j + 1), T(i, j + 1)], mat, soft=True)
            m.add_face([B(i, j + 1), B(i + 1, j + 1), B(i + 1, j), B(i, j)], mat, soft=True)
    for i in range(nu - 1):
        m.add_face([T(i + 1, 0), T(i, 0), B(i, 0), B(i + 1, 0)], mat)
        m.add_face([T(i, nv - 1), T(i + 1, nv - 1), B(i + 1, nv - 1), B(i, nv - 1)], mat)
    for j in range(nv - 1):
        m.add_face([T(nu - 1, j + 1), T(nu - 1, j), B(nu - 1, j), B(nu - 1, j + 1)], mat)
        m.add_face([T(0, j), T(0, j + 1), B(0, j + 1), B(0, j)], mat)
    return sh.orient_outward(m)


def annulus_z(outer, inner, z0, z1, mat, rounding=True):
    """A plate across Z from z0 to z1: the (x, y) outline `outer` with the hole `inner` (a ring, a lip)."""
    flip = lambda lp: np.asarray(lp, float)
    m = sh.extrude_x(flip(outer), [flip(inner)] if inner is not None else (), half_width=(z1 - z0) / 2, material=mat,
                     x_center=(z0 + z1) / 2, rounding=rounding)
    # extrude_x took the outline as (z, y) and extruded along X: swap X and Z (the writer turns mirrored faces back)
    S = np.eye(4)
    S[:3, :3] = [[0, 0, 1], [0, 1, 0], [1, 0, 0]]
    return m.transformed(S)


# ------------------------------------------------------------------------------------------- fuselage
def _fuselage():
    m = Mesh()
    zs = np.concatenate([np.linspace(-6.60, RADOME_END, 12)])
    m.merge(vp.shell(_fus_stations(zs), "paint_accent", count=56, round0=0.02))
    zs = np.concatenate([np.linspace(RADOME_END - 0.004, -4.0, 10), np.linspace(-3.8, 5.05, 34)])
    body = vp.shell(_fus_stations(zs), "paint", count=64)
    m.merge(body)
    # the radome's joint: a thin dark band, and the pitot boom with its base fairing and vanes
    ring = [fus_point(RADOME_END, t, 0.003)[:2] for t in np.linspace(0, 2 * np.pi, 56, endpoint=False)]
    inner = [fus_point(RADOME_END, t, -0.01)[:2] for t in np.linspace(0, 2 * np.pi, 56, endpoint=False)]
    m.merge(annulus_z(np.array(ring)[::-1], np.array(inner)[::-1], RADOME_END - 0.012, RADOME_END + 0.012, "frame",
                      rounding=False))
    tip = np.array([0.0, 1.70, -6.58])
    m.merge(vp.lathe([(0.0, 0.0), (0.028, 0.0), (0.020, 0.10), (0.012, 0.16)], 16, "metal", axis="z",
                     center=tip).transformed(_flip_z(tip)))
    m.merge(vp.cyl(tip + Z * -0.14, tip + Z * -0.48, 0.012, 10, "metal"))
    m.merge(vp.cyl(tip + Z * -0.48, tip + Z * -0.52, 0.007, 8, "metal"))
    # anti-glare panel ahead of the windscreen
    m.merge(_patch(lambda z, t: fus_point(z, t), -5.1, -4.32, np.pi / 2 - 0.55, np.pi / 2 + 0.55, "paint_accent",
                   lift=0.003, nu=8, nv=7))
    # raised access panels: the avionics bays on the nose, the spine, the engine bay underneath, beside the cockpit
    for sx in (1, -1):
        t0 = 0.0 if sx > 0 else np.pi
        m.merge(_patch(lambda z, t: fus_point(z, t), -5.05, -4.45, t0 - 0.30, t0 + 0.28, "paint"))
        m.merge(_patch(lambda z, t: fus_point(z, t), -1.55, -0.70, t0 + sx * 0.45 - 0.14, t0 + sx * 0.45 + 0.14, "paint"))
        m.merge(_patch(lambda z, t: fus_point(z, t), 2.55, 3.75, t0 - sx * 0.55 - 0.18, t0 - sx * 0.55 + 0.18, "paint"))
        # static ports and the AoA vanes
        p = fus_point(-5.35, t0 + sx * 0.12)
        m.merge(vp.cyl(p, p + np.array([sx * 0.006, 0, 0]), 0.022, 12, "frame", soft=False))
        q = fus_point(-5.75, t0 + sx * 0.05)
        m.merge(vp.cyl(q, q + np.array([sx * 0.03, 0, 0]), 0.012, 10, "metal"))
        m.merge(vp.oriented_box(q + np.array([sx * 0.035, 0.0, 0.03]), (0.004, 0.025, 0.05), X, Y, "metal", 0.002))
    for z0, z1 in ((0.15, 0.95), (1.25, 2.20), (2.50, 3.35)):
        m.merge(_patch(lambda z, t: fus_point(z, t), z0, z1, np.pi / 2 - 0.30, np.pi / 2 + 0.30, "paint"))
    m.merge(_patch(lambda z, t: fus_point(z, t), 2.40, 4.10, -np.pi / 2 - 0.42, -np.pi / 2 + 0.42, "paint_accent",
                   lift=0.003))
    m.merge(_patch(lambda z, t: fus_point(z, t), -1.80, -0.30, -np.pi / 2 - 0.35, -np.pi / 2 + 0.35, "paint"))
    # blade antennas (spine and belly), the anti-collision beacon
    m.merge(_blade((0.0, _TOP(1.05) - 0.01, 1.05), 0.17, 0.30, 1))
    m.merge(_blade((0.0, _BOT(-0.95) + 0.01, -0.95), 0.13, 0.26, -1))
    b = np.array([0.0, _TOP(-0.10), -0.10])
    m.merge(vp.lathe([(0.0, 0.0), (0.05, 0.0), (0.045, 0.04), (0.0, 0.06)], 16, "taillight", axis="y", center=b))
    # gear bays: dark recesses where the legs stow
    m.merge(_patch(lambda z, t: fus_point(z, t), -3.80, -2.42, -np.pi / 2 - 0.24, -np.pi / 2 + 0.24, "frame",
                   lift=0.002))
    # the main wheels stow side by side: the left a little aft, the right a little forward (their fold axes skewed)
    for sx, z0, z1 in ((1, 1.42, 2.22), (-1, 2.16, 2.96)):
        t0 = -np.pi / 2
        m.merge(_patch(lambda z, t: fus_point(z, t), z0, z1, t0 + sx * 0.06, t0 + sx * 0.70, "frame", lift=0.002))
    # ventral strakes, canted out
    for sx in (1, -1):
        root = np.array([sx * 0.42, _BOT(3.6) + 0.06, 3.1])
        st = [(root, Z, X * sx, 1.35, 0.06),
              (root + np.array([sx * 0.10, -0.36, 0.55]), Z, X * sx, 0.70, 0.05)]
        m.merge(surface_loft(st, "paint", count=10))
    return m


def _flip_z(origin):
    """A frame at origin whose +Z points along the vehicle's -Z (lathes built along +Z turned to face forward)."""
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2] = -X, Y, -Z
    M[:3, 3] = origin
    T = np.eye(4)
    T[:3, 3] = -np.asarray(origin, float)
    return M @ T


def _blade(base, height, length, up):
    """A swept blade antenna standing `up` (+1 / -1) from base, its root `length` long."""
    b = np.asarray(base, float)
    outline = np.array([(-length / 2, 0.0), (length / 2, 0.0), (length / 2 + 0.03, up * height * 0.15),
                        (length * 0.05, up * height), (-length * 0.15, up * height)])
    pts = [(b[2] + z, b[1] + y) for z, y in outline]
    return vp.plate(pts, 0.009, "paint_accent", x=b[0])


def _cockpit():
    m = Mesh()
    # the tub's dark floor and sills under the canopy
    st = [(z, 0.0, 0.0, _CHW(z) - 0.006, CANOPY_SILL + 0.012, 1.98, 5.0, 0.0, 0.0) for z in np.linspace(-4.38, -1.90, 12)]
    m.merge(vp.shell(st, "frame", count=40, round0=0.02, round1=0.02))
    # ejection seat: bucket, cushions, the back leaning aft, the headbox, the yellow-black firing handle
    m.merge(vp.box((0.0, 2.25, -3.12), (0.23, 0.08, 0.22), "frame", 0.02))
    m.merge(vp.box((0.0, 2.345, -3.17), (0.20, 0.03, 0.19), "seat", 0.015))
    back = vp.unit((0, np.cos(np.radians(18)), np.sin(np.radians(18))))
    fwd = np.cross(X, back)
    c = np.array([0.0, 2.52, -2.86])
    m.merge(vp.oriented_box(c, (0.22, 0.27, 0.05), X, back, "frame", 0.02))
    m.merge(vp.oriented_box(c - fwd * 0.06 + back * -0.02, (0.19, 0.22, 0.025), X, back, "seat", 0.012))
    m.merge(vp.oriented_box(c + back * 0.30 + fwd * 0.02, (0.15, 0.07, 0.08), X, back, "frame", 0.02))
    for sx in (-1, 1):
        m.merge(vp.oriented_box(c + back * 0.06 + X * sx * 0.235, (0.018, 0.27, 0.06), X, back, "metal", 0.006))
    m.merge(vp.tube(vp.bend([(-0.06, 2.30, -3.36), (-0.06, 2.26, -3.42), (0.06, 2.26, -3.42), (0.06, 2.30, -3.36)],
                            0.02), 0.009, 8, "indicator"))
    # coaming over the instrument panel, three displays facing the pilot, the HUD
    m.merge(vp.box((0.0, 2.27, -3.98), (0.36, 0.11, 0.16), "frame", 0.025))
    for x in (-0.20, 0.0, 0.20):
        m.merge(vp.box((x, 2.25, -3.817), (0.075, 0.065, 0.004), "gauge", 0.002))
        m.merge(vp.box((x, 2.25, -3.814), (0.085, 0.075, 0.003), "plastic", 0.002))
    tilt = vp.unit((0, np.cos(np.radians(28)), np.sin(np.radians(28))))
    hud = np.array([0.0, 2.48, -4.00])
    m.merge(vp.oriented_box(hud, (0.085, 0.07, 0.003), X, tilt, "glass", 0.0, rounding=False))
    for sx in (-1, 1):
        m.merge(vp.cyl((sx * 0.08, 2.38, -4.02), hud + (sx * 0.08, -0.05, 0.0), 0.008, 8, "metal"))
    # side consoles, the stick, the throttle
    for sx in (-1, 1):
        m.merge(vp.box((sx * 0.31, 2.20, -3.25), (0.07, 0.04, 0.38), "frame", 0.012))
        m.merge(vp.box((sx * 0.31, 2.243, -3.35), (0.05, 0.003, 0.12), "gauge", 0.001))
    m.merge(vp.tube(vp.bend([(0.0, 2.17, -3.48), (0.0, 2.33, -3.47), (0.0, 2.43, -3.42)], 0.05), 0.016, 10, "frame"))
    m.merge(vp.box((0.0, 2.46, -3.41), (0.022, 0.04, 0.03), "rubber", 0.01))
    m.merge(vp.box((-0.31, 2.28, -3.30), (0.025, 0.05, 0.035), "rubber", 0.012))
    return m


#        z      half width  top    (the canopy's rim lies on the sills at y 2.13)
_CAN = [(-4.45, 0.34, 2.20), (-4.20, 0.42, 2.50), (-3.85, 0.47, 2.76), (-3.40, 0.49, 2.90), (-2.90, 0.49, 2.94),
        (-2.40, 0.47, 2.90), (-1.95, 0.42, 2.76), (-1.55, 0.33, 2.58), (-1.25, 0.22, 2.40)]
CANOPY_SILL = 2.13
_CHW = _pchip([c[0] for c in _CAN], [c[1] for c in _CAN])
_CTOP = _pchip([c[0] for c in _CAN], [c[2] for c in _CAN])


def _canopy_bottom(top, floor):
    """The bottom to give rifle_forms.section (flat_bottom 1 flattens at half its depth) so the flat lies at `floor`."""
    return (4 * floor - top) / 3


def _canopy():
    zs = np.linspace(_CAN[0][0], _CAN[-1][0], 22)
    st = [(z, 0.0, 0.0, _CHW(z), _CTOP(z), _canopy_bottom(_CTOP(z), CANOPY_SILL - 0.02), 2.15, 0.0, 1.0) for z in zs]
    return vp.shell(st, "glass", count=48, round0=0.04, round1=0.10)


def _canopy_frame():
    m = Mesh()
    # the sill rails along both sides and the windscreen arch
    for sx in (-1, 1):
        rail = [(sx * (_CHW(z) + 0.012), CANOPY_SILL + 0.01, z) for z in np.linspace(-4.40, -1.35, 24)]
        m.merge(vp.tube(rail, 0.022, 10, "paint_accent"))
    for z in (-4.02,):
        hw, top = _CHW(z), _CTOP(z)
        loop = rf.section(hw + 0.012, top + 0.014, _canopy_bottom(top + 0.014, CANOPY_SILL - 0.02), n=2.15, count=96,
                          flat_bottom=1.0)
        kept = loop[:, 1] > CANOPY_SILL + 0.01
        start = next(i for i in range(len(loop)) if kept[i] and not kept[i - 1])
        arch = []
        i = start
        while kept[i % len(loop)] and len(arch) < len(loop):
            arch.append(np.array([loop[i % len(loop), 0], loop[i % len(loop), 1], z]))
            i += 1
        m.merge(vp.tube(arch[::2], 0.024, 10, "paint_accent"))
    # the spine fairing behind the canopy, blending into the dorsal spine
    st = [(z, 0.0, 0.0, hw, top, 2.06, 2.6, 0.0, 0.0) for z, hw, top in
          ((-1.95, 0.30, 2.66), (-1.40, 0.34, 2.62), (-0.70, 0.38, 2.56), (0.20, 0.40, 2.50), (1.20, 0.38, 2.46),
           (2.00, 0.30, 2.43))]
    m.merge(vp.shell(st, "paint", count=40, round0=0.06, round1=0.25))
    return m


def _cannon_blister():
    m = Mesh()
    x, y = MUZZLE_CANNON[0], MUZZLE_CANNON[1]
    st = [(z, x + 0.05, 0.0, hw, y + h, y - h, 2.3, 0.0, 0.0) for z, hw, h in
          ((-4.93, 0.07, 0.065), (-4.70, 0.085, 0.080), (-4.10, 0.080, 0.075), (-3.40, 0.05, 0.05))]
    m.merge(vp.shell(st, "paint", count=28, round0=0.03, round1=0.12))
    m.merge(vp.lathe([(0.0, 0.0), (0.045, 0.0), (0.045, 0.004), (0.0, 0.004)], 16, "frame", axis="z",
                     center=(x, y, -4.935)).transformed(_flip_z((x, y, -4.935))))
    m.merge(vp.cyl((x, y, -4.90), MUZZLE_CANNON, 0.024, 14, "gunmetal"))
    m.merge(vp.lathe([(0.012, 0.0), (0.026, 0.0), (0.026, 0.02), (0.012, 0.02), (0.012, 0.0)], 14, "gunmetal",
                     axis="z", center=MUZZLE_CANNON).transformed(_flip_z(MUZZLE_CANNON)))
    return m


def _intakes():
    m = Mesh()
    for sx in (1, -1):
        st = [(z, sx * cx, 0.0, hw, top, bot, 3.6, 0.0, 0.0) for z, cx, hw, top, bot in
              ((-2.32, 0.70, 0.22, 1.59, 1.15), (-1.90, 0.705, 0.225, 1.595, 1.15), (-1.00, 0.70, 0.22, 1.59, 1.155),
               (0.20, 0.64, 0.20, 1.55, 1.18), (1.20, 0.52, 0.15, 1.47, 1.22))]
        m.merge(vp.shell(st, "paint", count=40, round0=0.035, round1=0.0))
        # the inlet: its dark face a little inside the rounded lip
        z = -2.32
        loop = rf.section(0.22 - 0.04, 1.59 - 0.04, 1.15 + 0.04, n=3.6, count=40)
        face = Mesh()
        sh.cap_polygon(face, np.array([[sx * 0.70 + x, y, z - 0.002] for x, y in loop]), "frame")
        if np.dot(_face_normal(face), -Z) < 0:
            face.f = [tuple(reversed(f)) for f in face.f]
        m.merge(face)
        # a splitter rib along the intake's inner top edge
        m.merge(vp.box((sx * 0.50, 1.60, -1.55), (0.015, 0.012, 0.75), "paint", 0.006))
    return m


def _face_normal(mesh):
    v = np.array(mesh.v)
    f = mesh.f[0]
    n = np.cross(v[f[1]] - v[f[0]], v[f[2]] - v[f[0]])
    return n / (np.linalg.norm(n) + 1e-12)


# ------------------------------------------------------------------------------------------- wings and tails
def wing_le(x):
    return -1.40 + (x - 0.65) / 3.65 * 2.30


def wing_te(x):
    return 3.00 - (x - 0.65) / 3.65 * 0.40


def wing_ratio(x):
    return 0.050 - 0.012 * np.clip((x - 0.65) / 3.65, 0, 1)


def wing_point(x, u, upper=True):
    c = wing_te(x) - wing_le(x)
    t = _thickness(u) * wing_ratio(x) * c
    return np.array([x, WING_Y + (t if upper else -t), wing_le(x) + u * c])


def _wing_stations(x0, x1, n):
    out = []
    for x in np.linspace(x0, x1, n):
        le, te = wing_le(x), wing_te(x)
        out.append(((x, WING_Y, le), Z, Y, te - le, wing_ratio(x)))
    return out


def _wing():
    """The right wing (the left is mirrored): the main box, flap and aileron, the LERX, tip rail, pylon and pod."""
    m = Mesh()
    m.merge(surface_loft(_wing_stations(WING_ROOT, WING_TIP, 12), "paint", 0.0, 0.785, count=16))
    for x0, x1 in ((0.80, 2.55), (2.58, 4.12)):
        m.merge(surface_loft(_wing_stations(x0, x1, 6), "paint", 0.80, 1.0, count=8))
    # hinge fairings under the flap and aileron
    for x in (1.35, 2.25, 3.05, 3.75):
        u = 0.80
        p = wing_point(x, u, False)
        m.merge(vp.shell([(p[2] - 0.12, x, 0.0, 0.03, p[1] + 0.01, p[1] - 0.05, 2.4, 0.0, 0.0),
                          (p[2] + 0.30, x, 0.0, 0.025, p[1] + 0.01, p[1] - 0.04, 2.4, 0.0, 0.0)], "paint",
                         count=16, round0=0.03, round1=0.05))
    # leading-edge root extension: a thin rounded plate from the wing root forward along the fuselage
    lerx = np.array([(0.50, -3.35), (0.62, -3.05), (0.80, -2.55), (1.02, -1.95), (1.25, -1.35), (1.45, -0.86),
                     (1.45, -0.55), (0.50, -0.55)])
    m.merge(vp.plate_xz(lerx, WING_Y + 0.035, WING_Y + 0.075, "paint", 0.012))
    # access panels on the upper skin, one underneath
    for x0, x1, u0, u1 in ((1.20, 2.00, 0.18, 0.42), (2.55, 3.40, 0.14, 0.34), (1.40, 2.40, 0.50, 0.70)):
        m.merge(_patch(lambda x, u: wing_point(x, u, True), x0, x1, u0, u1, "paint", lift=0.003,
                       inside=lambda p: p - Y))
    m.merge(_patch(lambda x, u: wing_point(x, u, False), 1.30, 2.10, 0.25, 0.50, "paint", lift=0.003,
                   inside=lambda p: p + Y))
    # the tip: launch rail, missile, nav light (green on the right; the mirrored copy turns it red)
    m.merge(_tip_rail())
    m.merge(_missile(np.array([WING_TIP + 0.12, WING_Y - 0.035, -0.55])))
    light = wing_point(WING_TIP - 0.10, 0.0, True) + np.array([0.0, -0.01, -0.02])
    m.merge(vp.box(light, (0.05, 0.022, 0.03), "nav_green", 0.012))
    # under-wing pylon and rocket pod (the left pod's front is the rockets' muzzle; the runtime mirrors it)
    m.merge(_pylon_and_pod(-MUZZLE_ROCKETS[0]))
    return m


def _tip_rail():
    x = WING_TIP + 0.04
    m = Mesh()
    m.merge(vp.box((x, WING_Y, 1.05), (0.045, 0.045, 1.05), "paint", 0.015))
    m.merge(vp.box((x + 0.05, WING_Y - 0.035, 1.05), (0.02, 0.012, 1.0), "metal", 0.004))
    return m


def _missile(front):
    """A short-range missile along +Z from `front`: seeker dome, canards, body bands, tail fins, motor nozzle."""
    m = Mesh()
    L, R = 2.85, 0.064
    prof = [(0.0, 0.0), (0.030, 0.012), (0.052, 0.06), (R, 0.16), (R, L - 0.05), (0.055, L), (0.0, L)]
    body = sh.revolve(prof, 28, "paint")
    sh.orient_outward(body)
    m.merge(body.translated(front))
    m.merge(vp.lathe([(0.0, 0.0), (0.031, 0.0), (0.024, 0.018), (0.0, 0.026)], 16, "lens", axis="z",
                     center=front + Z * 0.012).transformed(_flip_z(front + Z * 0.012)))
    for z in (0.32, 1.1, 2.35):
        m.merge(vp.lathe([(R + 0.001, 0.0), (R + 0.003, 0.0), (R + 0.003, 0.05), (R + 0.001, 0.05)], 28,
                         "paint_accent", axis="z", center=front + Z * z))
    for k in range(4):
        a = np.pi / 4 + k * np.pi / 2
        d = np.array([np.cos(a), np.sin(a), 0.0])
        for z0, span, root, tip in ((0.42, 0.075, 0.16, 0.06), (2.30, 0.13, 0.42, 0.20)):
            st = [(front + d * R + Z * z0, Z, np.cross(Z, d), root, 0.06),
                  (front + d * (R + span) + Z * (z0 + root - tip), Z, np.cross(Z, d), tip, 0.05)]
            m.merge(surface_loft(st, "paint_accent", count=8))
    return m


def _pylon_and_pod(x):
    m = Mesh()
    top = wing_point(x, 0.25, False)[1]
    pod_c = np.array([x, MUZZLE_ROCKETS[1], MUZZLE_ROCKETS[2]])
    R = 0.20
    outline = [(-0.25, top + 0.02), (0.75, top + 0.02), (0.82, pod_c[1] + R - 0.02), (-0.10, pod_c[1] + R - 0.02)]
    m.merge(vp.plate(outline, 0.035, "paint", x=x, bevel=0.01))
    L = 1.75
    prof = [(0.0, 0.0), (R - 0.012, 0.0), (R, 0.015), (R, 1.30), (0.15, 1.58), (0.06, L), (0.0, L)]
    body = sh.revolve(prof, 40, "paint_accent")
    sh.orient_outward(body)
    m.merge(body.translated(pod_c))
    for z in (0.08, 1.24):
        m.merge(vp.lathe([(R - 0.004, 0.0), (R + 0.006, 0.0), (R + 0.006, 0.04), (R - 0.004, 0.04)], 40, "metal",
                         axis="z", center=pod_c + Z * z))
    # the front face: seven launch tubes, their dark mouths, a tie-down lug on top
    for k in range(7):
        if k == 0:
            c = pod_c.copy()
        else:
            a = np.pi / 2 + (k - 1) * np.pi / 3
            c = pod_c + np.array([np.cos(a), np.sin(a), 0.0]) * 0.115
        m.merge(vp.lathe([(0.040, 0.0), (0.050, 0.0), (0.050, 0.03), (0.040, 0.03), (0.040, 0.0)], 18, "metal",
                         axis="z", center=c + Z * -0.015))
        m.merge(vp.lathe([(0.0, 0.0), (0.041, 0.0), (0.041, 0.002), (0.0, 0.002)], 18, "frame", axis="z",
                         center=c + Z * -0.0035))
    return m


def _tailplane():
    """The right all-moving tailplane, its pivot fairing."""
    m = Mesh()
    st = []
    for x in np.linspace(0.40, 2.35, 7):
        f = (x - 0.40) / 1.95
        le, te = 3.85 + f * 1.25, 5.55 + f * 0.20
        st.append(((x, 1.62, le), Z, Y, te - le, 0.045 - 0.01 * f))
    m.merge(surface_loft(st, "paint", count=14))
    m.merge(vp.shell([(4.35, 0.62, 0.0, 0.07, 1.68, 1.56, 2.3, 0.0, 0.0),
                      (5.10, 0.60, 0.0, 0.06, 1.67, 1.57, 2.3, 0.0, 0.0)], "paint", count=16, round0=0.06,
                     round1=0.08))
    return m


def _fin():
    m = Mesh()

    def stations(y0, y1, n, a, b):
        out = []
        for y in np.linspace(y0, y1, n):
            f = (y - 2.05) / 2.05
            le, te = 3.30 + f * 1.45, 5.55 - f * 0.12
            out.append(((0.0, y, le), Z, X, te - le, 0.055 - 0.012 * f))
        return out

    m.merge(surface_loft(stations(2.05, 4.10, 10, 0, 0.78), "paint", 0.0, 0.78, count=16))
    m.merge(surface_loft(stations(2.40, 3.88, 6, 0.80, 1.0), "paint", 0.80, 1.0, count=8))
    # the fin cap fairing, the white tail light, the rudder's hinge fairing at the root
    m.merge(vp.shell([(4.62, 0.0, 0.0, 0.035, 4.14, 4.06, 2.3, 0.0, 0.0),
                      (5.42, 0.0, 0.0, 0.03, 4.13, 4.06, 2.3, 0.0, 0.0)], "paint_accent", count=16, round0=0.08,
                     round1=0.04))
    m.merge(vp.box((0.0, 4.02, 5.47), (0.018, 0.025, 0.02), "headlight", 0.008))
    # fin root fillet into the spine
    m.merge(vp.shell([(2.70, 0.0, 0.0, 0.05, 2.36, 2.20, 2.5, 0.0, 0.0),
                      (3.40, 0.0, 0.0, 0.10, 2.36, 2.20, 2.5, 0.0, 0.0),
                      (5.10, 0.0, 0.0, 0.10, 2.32, 2.18, 2.5, 0.0, 0.0)], "paint", count=20, round0=0.15,
                     round1=0.05))
    return m


# ------------------------------------------------------------------------------------------- engine
def _nozzle():
    m = Mesh()
    c = np.array([0.0, AXIS_Y, 4.95])
    prof = [(0.478, 0.0), (0.474, 0.22), (0.452, 0.55), (0.418, 0.83), (0.402, 0.85), (0.384, 0.845),
            (0.372, 0.80), (0.380, 0.50), (0.405, 0.22), (0.41, 0.0), (0.478, 0.0)]
    m.merge(vp.lathe(prof, 64, "exhaust", axis="z", center=c))
    # petals: overlapping strips over the divergent section
    for k in range(18):
        a = k * 2 * np.pi / 18
        d = np.array([np.cos(a), np.sin(a), 0.0])
        p0, p1 = c + d * 0.468 + Z * 0.30, c + d * 0.414 + Z * 0.82
        along = vp.unit(p1 - p0)
        m.merge(vp.oriented_box((p0 + p1) / 2 + d * 0.004, (0.055, 0.004, np.linalg.norm(p1 - p0) / 2),
                                np.cross(along, d), d, "metal", 0.002))
    # inside: the flame holder rings and the cone in the tail pipe
    for r in (0.30, 0.17):
        ring = [c + Z * 0.30 + np.array([r * np.cos(a), r * np.sin(a), 0.0])
                for a in np.linspace(0, 2 * np.pi, 33)]
        m.merge(vp.tube(ring, 0.014, 8, "exhaust", cap=False))
    for k in range(8):
        a = k * np.pi / 4
        d = np.array([np.cos(a), np.sin(a), 0.0])
        m.merge(vp.cyl(c + Z * 0.30 + d * 0.08, c + Z * 0.30 + d * 0.39, 0.010, 6, "exhaust"))
    m.merge(vp.lathe([(0.0, 0.0), (0.12, 0.0), (0.11, 0.08), (0.05, 0.22), (0.0, 0.26)], 24, "exhaust", axis="z",
                     center=c + Z * 0.05))
    m.merge(vp.lathe([(0.0, 0.0), (0.41, 0.0), (0.41, 0.01), (0.0, 0.01)], 40, "frame", axis="z", center=c + Z * 0.0))
    return m


def _afterburner():
    """The flame core from inside the nozzle out behind it, shock diamonds as bulges."""
    c = np.array([0.0, AXIS_Y, 5.35])
    L = 2.1
    prof = [(0.0, 0.0)]
    for a in np.linspace(0.0, L, 26):
        f = a / L
        r = (0.33 - 0.27 * f) * (1 + 0.16 * np.cos(2 * np.pi * a / 0.42) * (1 - f))
        prof.append((max(r, 0.02), a))
    prof.append((0.0, L + 0.05))
    return vp.lathe(prof, 28, "afterburner", axis="z", center=c)


# ------------------------------------------------------------------------------------------- landing gear
def _wheel(R, W, rim_mat="alu"):
    m = Mesh()
    bead = R * 0.62
    m.merge(vp.tyre(R, W, bead, "tire", segs=48, shoulder=0.45, bulge=0.05))
    for sx in (-1, 1):
        m.merge(vp.rim_face(bead - 0.004, 6, R * 0.07, bead * 0.55, 0.006, rim_mat, x=sx * W * 0.36, count=48,
                            hole_count=12))
        m.merge(vp.nuts(8, bead * 0.30, 0.008, 0.012, "metal", sx * W * 0.36 + sx * 0.006, sx))
    m.merge(vp.lathe([(0.0, -W * 0.40), (bead * 0.24, -W * 0.40), (bead * 0.24, W * 0.40), (0.0, W * 0.40)], 20,
                     "metal", axis="x"))
    return m


def _nose_gear():
    m = Mesh()
    H, A = NOSE_HINGE, NOSE_AXLE
    crown = A + Y * 0.24
    d = vp.unit(crown - H)
    m.merge(vp.cyl(H + Y * 0.04, H + d * 0.66, 0.050, 16, "metal"))
    m.merge(vp.cyl(H + d * 0.60, crown, 0.036, 16, "chrome"))
    m.merge(vp.cyl(H + Y * 0.02 + X * -0.09, H + Y * 0.02 + X * 0.09, 0.035, 12, "metal"))
    m.merge(vp.box(crown, (0.13, 0.028, 0.055), "metal", 0.01))
    for sx in (-1, 1):
        m.merge(vp.box(A + np.array([sx * 0.112, 0.12, 0.0]), (0.014, 0.13, 0.035), "metal", 0.006))
    m.merge(vp.cyl(A + X * -0.13, A + X * 0.13, 0.022, 10, "metal"))
    # torque links ahead of the strut
    up, low = H + d * 0.56 + Z * -0.06, crown + Y * 0.06 + Z * -0.06
    knee = (up + low) / 2 + Z * -0.09
    m.merge(vp.oriented_box((up + knee) / 2, (0.012, np.linalg.norm(knee - up) / 2, 0.016), X, vp.unit(knee - up),
                            "metal", 0.004))
    m.merge(vp.oriented_box((low + knee) / 2, (0.012, np.linalg.norm(knee - low) / 2, 0.016), X,
                            vp.unit(knee - low), "metal", 0.004))
    # drag brace aft to the bay, the landing light, the leg door ahead of it all
    m.merge(vp.cyl(H + d * 0.45, np.array([0.0, 1.38, -1.80]), 0.022, 10, "metal"))
    m.merge(vp.box((0.0, 0.95, -2.29), (0.055, 0.04, 0.045), "metal", 0.01))
    m.merge(vp.lathe([(0.0, 0.0), (0.034, 0.0), (0.034, 0.01), (0.0, 0.016)], 16, "headlight", axis="z",
                     center=np.array(HEADLIGHT) + Z * 0.012).transformed(_flip_z(np.array(HEADLIGHT) + Z * 0.012)))
    door_c = H + d * 0.36 + Z * -0.11
    m.merge(vp.oriented_box(door_c, (0.17, 0.33, 0.008), X, d, "paint", 0.006))
    return m


def _main_gear():
    """The right main leg (vehicle space), its wheel on an axle stub outboard."""
    m = Mesh()
    H, A = MAIN_HINGE, MAIN_AXLE
    knuckle = np.array([H[0], A[1] + 0.06, A[2]])
    m.merge(vp.cyl(H + Y * 0.02, H + Y * -0.50, 0.060, 16, "metal"))
    m.merge(vp.cyl(H + Y * -0.44, knuckle, 0.045, 16, "chrome"))
    m.merge(vp.cyl(H + Z * -0.10 + Y * 0.01, H + Z * 0.10 + Y * 0.01, 0.045, 12, "metal"))
    m.merge(vp.box(knuckle, (0.05, 0.06, 0.07), "metal", 0.012))
    m.merge(vp.cyl(knuckle + Y * -0.06, np.array([A[0] + MAIN_W * 0.45, A[1], A[2]]), 0.03, 12, "metal"))
    # side brace up inboard, torque links aft of the strut, the brake caliper
    m.merge(vp.cyl(H + Y * -0.30, np.array([H[0] - 0.20, H[1] - 0.06, H[2]]), 0.022, 10, "metal"))
    up, low = H + Y * -0.42 + Z * 0.06, knuckle + Y * 0.07 + Z * 0.06
    knee = (up + low) / 2 + Z * 0.09
    for a, b in ((up, knee), (low, knee)):
        m.merge(vp.oriented_box((a + b) / 2, (0.012, np.linalg.norm(b - a) / 2, 0.016), X, vp.unit(b - a), "metal",
                                0.004))
    m.merge(vp.box((A[0] - MAIN_W * 0.5 - 0.012, A[1] + 0.14, A[2] - 0.05), (0.012, 0.05, 0.06), "frame", 0.008))
    return m


# ------------------------------------------------------------------------------------------- the rig
def build(rig):
    body = Mesh()
    body.merge(_fuselage())
    body.merge(_cockpit())
    body.merge(_canopy_frame())
    body.merge(_canopy())
    body.merge(_cannon_blister())
    body.merge(_intakes())
    wing = _wing()
    tail = _tailplane()
    body.merge(wing)
    body.merge(vp.mirror_x(wing).transformed(np.eye(4)))
    body.merge(tail)
    body.merge(vp.mirror_x(tail))
    body.merge(_fin())
    body.merge(_nozzle())
    _nav_colours(body)
    rig.add("body", body)

    rig.node("afterburner", "body", (0.0, AXIS_Y, 5.35))
    rig.add("afterburner", _afterburner())

    # nose gear: identity frame, +95 degrees about X swings the leg forward into the nose
    rig.node("gear_0", "body", NOSE_HINGE)
    rig.add("gear_0", _nose_gear())
    rig.node("wheel_n", "gear_0", NOSE_AXLE)
    rig.add("wheel_n", _wheel(NOSE_R, NOSE_W).translated(NOSE_AXLE))
    rig.node("headlight_0", "gear_0", HEADLIGHT)
    # main gear: local X along the fuselage (the left's +Z, the right's -Z), skewed 22 degrees so +95 degrees folds
    # each leg inward and the left a little aft, the right a little forward (the stowed wheels lie side by side)
    right = _main_gear()
    for name, wheel, sx, rot in (("gear_1", "wheel_l", -1, vp.rot_y(-112)), ("gear_2", "wheel_r", 1, vp.rot_y(68))):
        hinge = MAIN_HINGE * np.array([sx, 1, 1])
        axle = MAIN_AXLE * np.array([sx, 1, 1])
        rig.node(name, "body", hinge, rot)
        rig.add(name, right if sx > 0 else vp.mirror_x(right))
        rig.node(wheel, name, axle, np.eye(3))
        rig.add(wheel, _wheel(MAIN_R, MAIN_W).translated(axle))

    rig.node("seat_0", "body", SEAT)
    rig.node("muzzle_cannon", "body", MUZZLE_CANNON)
    rig.node("muzzle_rockets", "body", MUZZLE_ROCKETS)
    rig.node("exhaust_0", "body", EXHAUST)


def _nav_colours(m):
    """The right wing's green nav light came over to the left by the mirror: red there."""
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        if m.m[i] == "nav_green" and v[list(f)][:, 0].mean() < 0:
            m.m[i] = "taillight"
