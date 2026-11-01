"""Helicopter (直升机, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original armed utility helicopter, 11 m
long over the fuselage with an 11 m main rotor. Vehicle space: metres, +Y up, the nose towards -Z (its right is +X),
the origin on the ground under the middle of the skids. Six seats: pilot (right) and gunner (left) side by side in the
glazed cockpit, four troops at the open cabin doors facing forward.

Fuselage: one skin lofted nose to tail through rounded-rectangle stations (a radome, the sloping windscreen, the cabin's
constant section, the upswept tail into the boom), its glass and openings cut along the skin's own grid lines — the
framed windscreen with its centre post, overhead and chin windows, the cockpit doors' windows, the cabin doors open on
both sides — with an inner skin and door jambs where the cabin can be seen into. The cabin doors: the rear one slid
back on its rails, the front one folded forward on its hinges, each with its window. Inside: two armoured crew seats,
the instrument panel with glowing displays under its glare shield, the centre console, cyclics, collectives and
pedals; the cabin's non-skid floor, quilted walls and four canvas troop seats on tube frames.

On the roof: the two engine nacelles with their intakes and exhaust stacks either side of the transmission cowling,
the mast and swashplate; the main rotor (node `rotor_main`, spins about its local Y): the hub with elastomeric grips,
pitch horns and links to the rotating swashplate ring, four twisted, drooping blades with erosion strips on their
leading edges (leading edges on the side they advance to when the rotor turns positively about +Y, counter-clockwise
seen from above). Stub wings off the roof line carrying a tall pylon and a rocket pod each (seven tubes); the chin
turret (`chin_turret` yaw, `chin_gun` pitch) with a six-barrel rotary gun; skids on two arched cross tubes; the boom
with its drive-shaft cover, the horizontal stabiliser with end plates, the swept fin carrying the tail-rotor gearbox and
the tail rotor (node `rotor_tail`, four blades, spins about its local X), a tail stinger; wire cutter, pitot tubes,
antennas, a search light, navigation lights and beacons."""
import numpy as np

import shapes as sh
import vehicle_parts as vp
from meshkit import Mesh
from vehicle_parts import X, Y, Z

# ------------------------------------------------------------------------------------------- the contract
SEATS = [(0.45, 1.55, -2.3), (-0.45, 1.55, -2.3), (-0.9, 1.45, -0.6), (0.9, 1.45, -0.6), (-0.9, 1.45, 0.5),
         (0.9, 1.45, 0.5)]
HUB = np.array([0.0, 3.6, -0.2])
TAIL_HUB = np.array([0.38, 2.75, 6.55])
TURRET = np.array([0.0, 0.85, -3.6])
GUN = np.array([0.0, 0.70, -3.6])
GUN_MUZZLE = np.array([0.0, 0.70, -4.6])
POD = np.array([1.65, 1.35, -0.4])                 # the right pod's centre (the left one is its mirror)
POD_FRONT, POD_BACK = -1.2, 0.4
EXHAUST = [(-0.55, 3.0, 0.9), (0.55, 3.0, 0.9)]
HEADLIGHT = (0.0, 0.94, -3.9)

ROTOR_R, TAIL_R = 5.5, 1.0
SKID_X = 1.12

# --------------------------------------------------------------------------------------- fuselage sections
# A station is a rounded rectangle (half width, bottom, top, bottom and top corner radii), sampled as its right half
# from the bottom centre to the top centre and mirrored: every station has the same points in the same places, so
# windows and openings follow grid lines. Right half indices: 0 bottom centre, 1 bottom, 2..6 the lower corner, 7..15
# the side (SIDE_F of its straight part), 16..22 the upper corner (TR_A degrees), 23..25 the top to its centre.
BOT_F = (0.0, 0.5)
BR_A = (270, 288, 306, 324, 342)
SIDE_F = (0.0, 0.05, 0.18, 0.32, 0.46, 0.56, 0.66, 0.78, 0.89)
TR_A = (0, 12, 24, 36, 50, 64, 78)
TOP_F = (0.0, 0.5, 1.0)
HALF = len(BOT_F) + len(BR_A) + len(SIDE_F) + len(TR_A) + len(TOP_F)          # 26
N = 2 * HALF - 2                                                               # 50 points a station
SIDE0, TR0 = 7, 16

# cabin: the constant section through the doors
CAB_HW, CAB_Y0, CAB_Y1, CAB_RB, CAB_RT = 1.10, 0.70, 2.72, 0.34, 0.40

KEYS = [  # z, half width, bottom, top
    (-3.95, 0.50, 1.00, 1.66), (-3.80, 0.64, 0.97, 1.84), (-3.60, 0.80, 0.93, 2.05), (-3.35, 0.93, 0.86, 2.30),
    (-3.05, 1.03, 0.78, 2.52), (-2.75, 1.08, 0.72, 2.66), (-2.40, CAB_HW, CAB_Y0, CAB_Y1),
    (2.00, CAB_HW, CAB_Y0, CAB_Y1), (2.35, 1.03, 0.85, 2.72), (2.75, 0.80, 1.24, 2.74), (3.20, 0.54, 1.66, 2.77),
    (3.70, 0.39, 1.96, 2.79), (4.20, 0.355, 2.02, 2.79), (6.60, 0.235, 2.17, 2.64), (6.80, 0.17, 2.23, 2.60),
    (6.86, 0.11, 2.27, 2.56), (6.88, 0.05, 2.31, 2.52),
]
NOSE_TIP, NOSE_DOME = -4.25, 0.30                 # the nose cone: an ellipsoidal dome ahead of the first key
TAIL_END = 6.88

# windows and openings (right side quad index ranges; the left side mirrors them)
DOOR = (-1.05, 0.90, 8, 18)                       # z from, z to, first and last quad (points 8..19: y 1.10..2.56)
GLASS = [(-3.90, -3.02, 13, 23),                  # windscreen (quad 24, next to the top centre, is its centre post)
         (-2.92, -1.95, 12, 18),                  # cockpit door windows
         (-2.85, -2.30, 21, 23),                  # overhead windows
         (-3.75, -3.30, 8, 10)]                   # chin windows
RADOME = -4.13                                    # the dark radome at the nose cone's tip
INNER = (-3.85, 2.00)                             # where the skin has an inside (the cockpit and the cabin)

STATIONS = sorted(set(np.round(np.concatenate([
    KEYS[0][0] - NOSE_DOME * np.cos(np.radians(np.array([3, 10, 20, 32, 45, 58, 72, 84]))),
    [-3.90, -3.85, -3.80, -3.75, -3.70, -3.62, -3.55, -3.47, -3.39, -3.30, -3.22, -3.14, -3.06, -3.02, -2.97, -2.92,
     -2.85, -2.75, -2.65, -2.55, -2.45, -2.35, -2.30, -2.20, -2.08, -1.95, -1.85, -1.70, -1.55, -1.40, -1.25, -1.12,
     -1.05],
    np.linspace(-0.90, 0.90, 13),
    [1.05, 1.25, 1.45, 1.65, 1.85, 2.00, 2.12, 2.24, 2.36, 2.48, 2.60, 2.72, 2.84, 2.96, 3.08, 3.20, 3.35, 3.50, 3.70,
     3.95, 4.25, 4.60, 5.00, 5.40, 5.80, 6.20, 6.45, 6.60, 6.70, 6.78, 6.83, 6.86, TAIL_END]]), 4)))


def _pchip(xs, ys, x):
    """Monotone cubic interpolation (Fritsch-Carlson): the shape never overshoots between its keys."""
    xs, ys = np.asarray(xs, float), np.asarray(ys, float)
    h, d = np.diff(xs), np.diff(ys) / np.diff(xs)
    m = np.zeros_like(ys)
    m[0], m[-1] = d[0], d[-1]
    for k in range(1, len(xs) - 1):
        if d[k - 1] * d[k] > 0:
            w1, w2 = 2 * h[k] + h[k - 1], h[k] + 2 * h[k - 1]
            m[k] = (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
    x = np.clip(x, xs[0], xs[-1])
    k = int(np.clip(np.searchsorted(xs, x) - 1, 0, len(xs) - 2))
    t = (x - xs[k]) / h[k]
    return ((2 * t ** 3 - 3 * t ** 2 + 1) * ys[k] + (t ** 3 - 2 * t ** 2 + t) * h[k] * m[k]
            + (-2 * t ** 3 + 3 * t ** 2) * ys[k + 1] + (t ** 3 - t ** 2) * h[k] * m[k + 1])


def station(z):
    """(half width, bottom, top) of the skin at z: the radome's dome, then the keys."""
    z0 = KEYS[0][0]
    if z < z0:
        c = np.clip((z0 - z) / NOSE_DOME, 0, 1)
        s = np.sqrt(max(1 - c * c, 0.0))
        hw0, b0, t0 = KEYS[0][1:]
        mid0, half0 = (b0 + t0) / 2, (t0 - b0) / 2
        mid = mid0 - 0.06 * c                          # the nose droops a little to its tip
        return max(hw0 * s, 0.004), mid - max(half0 * s, 0.003), mid + max(half0 * s, 0.003)
    zs = [k[0] for k in KEYS]
    return tuple(float(_pchip(zs, [k[i] for k in KEYS], z)) for i in (1, 2, 3))


def half_loop(hw, y0, y1):
    rb = min(CAB_RB, hw, 0.5 * (y1 - y0))
    rt = min(CAB_RT, hw, 0.5 * (y1 - y0))
    xb, xt = hw - rb, hw - rt
    ya, yb = y0 + rb, y1 - rt
    pts = [(xb * f, y0) for f in BOT_F]
    pts += [(xb + rb * np.cos(np.radians(a)), ya + rb * np.sin(np.radians(a))) for a in BR_A]
    # the side bellies out a few millimetres between the corners (it would read as a flat plate otherwise)
    pts += [(hw + 0.012 * np.sin(np.pi * f) * min(1.0, hw / CAB_HW), ya + (yb - ya) * f) for f in SIDE_F]
    pts += [(xt + rt * np.cos(np.radians(a)), yb + rt * np.sin(np.radians(a))) for a in TR_A]
    pts += [(xt * (1 - f), y1) for f in TOP_F]
    return np.array(pts)


def loop2d(z):
    r = half_loop(*station(z))
    left = r[1:-1][::-1] * np.array([-1.0, 1.0])
    return np.concatenate([r, left])


def right_quad(i):
    """A quad's index on the right side (quads 25..49 mirror 24..0)."""
    return i if i < HALF - 1 else N - 1 - i


def _outer_mat(zc, i):
    q = right_quad(i)
    z0, z1, a, b = DOOR
    if z0 < zc < z1 and a <= q <= b:
        return None
    if zc < RADOME:
        return "paint_accent"
    for g0, g1, a, b in GLASS:
        if g0 < zc < g1 and a <= q <= b:
            return "glass"
    return "paint"


def _inner_mat(zc, i):
    m = _outer_mat(zc, i)
    if m is None or m == "glass":
        return m
    # the cockpit trimmed in dark plastic, the cabin's walls lined with quilted blankets
    return "plastic" if zc < -1.85 or right_quad(i) <= 6 else "fabric"


def _grid(rings, mat, soft=True, inward=False):
    """Quads between consecutive closed rings (K, N, 3); mat(zc, i) gives each one's material (None: no face)."""
    m = Mesh()
    K, n = rings.shape[:2]
    base = m.add_vertices(rings.reshape(-1, 3))
    idx = lambda k, i: base + k * n + (i % n)
    for k in range(K - 1):
        zc = 0.5 * (rings[k, 0, 2] + rings[k + 1, 0, 2])
        for i in range(n):
            mt = mat(zc, i)
            if mt is None:
                continue
            f = [idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)]
            m.add_face(f[::-1] if inward else f, mt, soft=soft)
    return m


def _oriented(m, pts, material, want):
    """A quad facing (roughly) direction `want`."""
    p = np.asarray(pts, float)
    n = np.cross(p[1] - p[0], p[3] - p[0])
    base = m.add_vertices(p)
    f = [base, base + 1, base + 2, base + 3]
    m.add_face(f if np.dot(n, want) >= 0 else f[::-1], material)


def _fuselage():
    """The outer skin (nose to tail), the inner skin of the cockpit and cabin, the door jambs, the bulkheads."""
    zs = np.array(STATIONS)
    rings = np.array([np.column_stack([loop2d(z), np.full(N, z)]) for z in zs])
    m = Mesh()
    m.merge(_grid(rings, _outer_mat))
    # the radome's tip and the boom's end
    tip = Mesh()
    b = tip.add_vertices(rings[0])
    tip.add_face([b + i for i in reversed(range(N))], "paint_accent")
    b = tip.add_vertices(rings[-1])
    tip.add_face([b + i for i in range(N)], "paint")
    m.merge(tip)
    # inside: the skin 35 mm in, faces turned in; bulkheads at its ends
    k0, k1 = int(np.argmin(abs(zs - INNER[0]))), int(np.argmin(abs(zs - INNER[1])))
    inner = np.array([np.column_stack([sh.offset_loop(loop2d(z), 0.035), np.full(N, z)]) for z in zs[k0:k1 + 1]])
    m.merge(_grid(inner, _inner_mat, inward=True))
    caps = Mesh()
    b = caps.add_vertices(inner[0])
    caps.add_face([b + i for i in range(N)], "plastic")                 # faces aft into the cockpit
    b = caps.add_vertices(inner[-1])
    caps.add_face([b + i for i in reversed(range(N))], "fabric")        # faces forward into the cabin
    m.merge(caps)
    # door jambs joining the skins round each opening
    z0, z1, a, b_ = DOOR
    ka, kb = int(np.argmin(abs(zs - z0))), int(np.argmin(abs(zs - z1)))
    jamb = Mesh()
    for side in (1, -1):
        pts = range(a, b_ + 2) if side > 0 else range(N - b_ - 1, N - a + 1)
        lo, hi = (a, b_ + 1) if side > 0 else (N - a, N - b_ - 1)
        pts = list(pts)
        for k, want in ((ka, Z), (kb, -Z)):
            for p0, p1 in zip(pts[:-1], pts[1:]):
                _oriented(jamb, [rings[k, p0], rings[k, p1], inner[k - k0, p1], inner[k - k0, p0]], "paint", want)
        for k in range(ka, kb):
            for p, want in ((lo, Y), (hi, -Y)):
                _oriented(jamb, [rings[k, p], rings[k + 1, p], inner[k + 1 - k0, p], inner[k - k0, p]], "paint", want)
    m.merge(jamb)
    return m, rings


def _cabin_point(i, z=0.0, out=0.0):
    """Point i of the cabin's section at z, `out` metres outside the skin (along its outward normal)."""
    loop = loop2d(-1.0)
    p = loop[i % N]
    t = loop[(i + 1) % N] - loop[(i - 1) % N]
    n = vp.unit(np.array([t[1], -t[0], 0.0]))
    return np.array([p[0], p[1], z]) + n * out


def _strip(i0, i1, z0, z1, out0, out1, mat, side=1):
    """A curved panel over the cabin's section points i0..i1 from z0 to z1, between out0 and out1 off the skin."""
    a = [_cabin_point(i, 0, out1)[:2] for i in range(i0, i1 + 1)]
    b = [_cabin_point(i, 0, out0)[:2] for i in range(i1, i0 - 1, -1)]
    loop = np.array(a + b)
    if side < 0:
        loop = loop * np.array([-1.0, 1.0])
    loop = sh.ccw(loop)
    rings = np.array([np.column_stack([loop, np.full(len(loop), z)]) for z in (z0, z1)])
    return sh.loft(rings, mat)


def _door_panel(z0, z1, side):
    """A cabin door off its opening: lower and upper panels, posts and its window, handle and rollers."""
    m = Mesh()
    m.merge(_strip(8, 13, z0, z1, 0.030, 0.062, "paint", side))
    m.merge(_strip(17, 19, z0, z1, 0.030, 0.062, "paint", side))
    m.merge(_strip(13, 17, z0, z0 + 0.09, 0.030, 0.062, "paint", side))
    m.merge(_strip(13, 17, z1 - 0.09, z1, 0.030, 0.062, "paint", side))
    m.merge(_strip(13, 17, z0 + 0.09, z1 - 0.09, 0.044, 0.050, "glass", side))
    h = _cabin_point(11, (z0 + z1) / 2 + 0.25 * (1 if z0 > 0 else -1), 0.075)
    handle = vp.box(h, (0.012, 0.02, 0.09), "metal", 0.006)
    m.merge(handle if side > 0 else vp.mirror_x(handle))
    return m


def _doors():
    m = Mesh()
    for side in (1, -1):
        m.merge(_door_panel(0.93, 2.03, side))       # slid back over the aft cabin
        m.merge(_door_panel(-1.92, -1.13, side))     # folded forward against the cockpit door
        # rails: the upper one over the opening's top, the lower one under the sill aft of it
        for i, z0, z1 in ((20, -1.12, 2.06), (7, 0.88, 2.06)):
            a, b = _cabin_point(i, z0, 0.03), _cabin_point(i, z1, 0.03)
            rail = vp.cyl(a, b, 0.016, 10, "metal")
            m.merge(rail if side > 0 else vp.mirror_x(rail))
        # hinges of the folded panel at the opening's front post, the cockpit door's handle and hinges
        for y in (1.35, 2.15):
            hp = np.array([CAB_HW + 0.045, y, -1.10])
            hinge = vp.cyl(hp - Y * 0.05, hp + Y * 0.05, 0.016, 10, "metal")
            m.merge(hinge if side > 0 else vp.mirror_x(hinge))
        handle = vp.box((CAB_HW + 0.02, 1.62, -2.08), (0.012, 0.018, 0.08), "metal", 0.006)
        m.merge(handle if side > 0 else vp.mirror_x(handle))
    return m


# --------------------------------------------------------------------------------------------- interior
def _seat_crew(x):
    m = Mesh()
    z = -2.30
    m.merge(vp.box((x, 1.43, z - 0.02), (0.24, 0.05, 0.25), "seat", 0.03))
    back = vp.oriented_box((x, 1.86, z + 0.27), (0.24, 0.40, 0.05), X, vp.unit((0, 1, 0.2)), "seat", 0.03)
    m.merge(back)
    m.merge(vp.oriented_box((x, 2.34, z + 0.37), (0.13, 0.09, 0.05), X, vp.unit((0, 1, 0.2)), "seat", 0.03))
    for sx in (-1, 1):                            # armour wings
        m.merge(vp.oriented_box((x + sx * 0.27, 1.75, z + 0.20), (0.015, 0.38, 0.20), X, vp.unit((0, 1, 0.2)),
                                "frame", 0.008))
    m.merge(vp.box((x, 1.16, z + 0.05), (0.20, 0.20, 0.20), "frame", 0.02))         # the seat bucket's base
    return m


def _seat_troop(x, z):
    """A canvas seat on a tube frame, facing forward."""
    m = Mesh()
    m.merge(vp.box((x, 1.37, z), (0.21, 0.018, 0.21), "fabric", 0.012))
    m.merge(vp.oriented_box((x, 1.76, z + 0.27), (0.21, 0.32, 0.015), X, vp.unit((0, 1, 0.12)), "fabric", 0.01))
    frame = [(x - 0.22, 0.98, z - 0.20), (x - 0.22, 1.37, z - 0.22), (x - 0.22, 1.38, z + 0.24),
             (x - 0.22, 2.10, z + 0.33)]
    for sx in (0, 0.44):
        path = vp.bend([np.array(p) + (sx, 0, 0) for p in frame], 0.05)
        m.merge(vp.tube(path, 0.014, 8, "frame"))
    m.merge(vp.cyl((x - 0.22, 2.10, z + 0.33), (x + 0.22, 2.10, z + 0.33), 0.014, 8, "frame"))
    m.merge(vp.cyl((x - 0.22, 1.37, z - 0.22), (x + 0.22, 1.37, z - 0.22), 0.014, 8, "frame"))
    for sx in (-0.18, 0.18):                       # legs to the floor at the back
        m.merge(vp.cyl((x + sx, 0.98, z + 0.20), (x + sx, 1.37, z + 0.22), 0.012, 8, "frame"))
    return m


def _interior():
    m = Mesh()
    # floor: the cockpit's narrows into the nose
    floor = np.array([(-0.70, -3.30), (0.70, -3.30), (0.95, -2.95), (0.98, 1.97), (-0.98, 1.97), (-0.95, -2.95)])
    m.merge(vp.plate_xz(floor, 0.86, 0.98, "metal", 0.01))
    for x in (0.45, -0.45):
        m.merge(_seat_crew(x))
    # instrument panel under its glare shield, displays and gauges on its face
    m.merge(vp.box((0.0, 1.64, -3.21), (0.78, 0.20, 0.07), "plastic", 0.02))
    m.merge(vp.box((0.0, 1.87, -3.20), (0.80, 0.025, 0.14), "plastic", 0.012))
    for sx in (-1, 1):
        for k, dx in enumerate((0.20, 0.52)):
            m.merge(vp.box((sx * dx, 1.68, -3.135), (0.10, 0.085, 0.004), "gauge", rounding=False))
        for dx in (0.10, 0.30, 0.42, 0.64):
            m.merge(vp.lathe([(0.0, 0.0), (0.026, 0.0), (0.026, 0.006), (0.0, 0.006)], 16, "gauge", axis="z",
                             center=(sx * dx, 1.51, -3.142), rounding=False))
    m.merge(vp.box((0.0, 1.20, -2.62), (0.13, 0.22, 0.50), "plastic", 0.02))     # centre console
    for k in range(5):
        m.merge(vp.box((0.0, 1.425, -2.95 + k * 0.17), (0.09, 0.004, 0.05), "gauge", rounding=False))
    for x in (0.45, -0.45):
        # cyclic, collective, pedals
        m.merge(vp.tube(vp.bend([(x, 0.98, -2.70), (x, 1.30, -2.76), (x, 1.46, -2.82)], 0.08), 0.016, 10, "frame"))
        m.merge(vp.box((x, 1.50, -2.83), (0.025, 0.05, 0.03), "rubber", 0.01))
        cx = x + (0.30 if x > 0 else -0.30)
        m.merge(vp.tube([(cx, 1.08, -2.12), (cx, 1.28, -2.48)], 0.018, 10, "frame"))
        m.merge(vp.box((cx, 1.29, -2.52), (0.03, 0.03, 0.07), "rubber", 0.01))
        for dx in (-0.12, 0.12):
            m.merge(vp.oriented_box((x + dx, 1.08, -3.05), (0.04, 0.10, 0.012), X, vp.unit((0, 1, 0.6)), "metal", 0.006))
    for x, z in ((-0.9, -0.6), (0.9, -0.6), (-0.9, 0.5), (0.9, 0.5)):
        m.merge(_seat_troop(x, z))
    # a fire extinguisher and a first aid box on the rear bulkhead
    m.merge(vp.cyl((-0.45, 1.25, 1.90), (-0.45, 1.70, 1.90), 0.06, 16, "reflector"))
    m.merge(vp.box((0.40, 1.85, 1.93), (0.18, 0.12, 0.05), "plastic", 0.015))
    return m


# --------------------------------------------------------------------------------------------- roof
def _engines():
    m = Mesh()
    for sx in (-1, 1):
        cx = sx * 0.55
        st = []
        for z, hw, top, bot in ((-1.27, 0.22, 0.22, -0.24), (-1.15, 0.26, 0.26, -0.27), (-0.6, 0.27, 0.27, -0.27),
                                (0.2, 0.27, 0.26, -0.27), (0.55, 0.22, 0.22, -0.24), (0.74, 0.16, 0.16, -0.18)):
            st.append((z, sx * 0.55, 3.0, hw, top, bot - 0.03, 2.6, 0.0, 0.0))
        m.merge(vp.shell(st, "paint", count=40, round0=0.05, round1=0.04))
        # intake: a lip and a screen
        m.merge(vp.lathe([(0.15, 0.0), (0.19, 0.0), (0.20, 0.03), (0.17, 0.05), (0.15, 0.05), (0.15, 0.0)], 32,
                         "alu", axis="z", center=(cx, 3.0, -1.32)))
        m.merge(vp.lathe([(0.0, 0.0), (0.155, 0.0), (0.155, 0.01), (0.0, 0.01)], 32, "frame", axis="z",
                         center=(cx, 3.0, -1.29), rounding=False))
        for k in range(-3, 4):
            m.merge(vp.box((cx + k * 0.04, 3.0, -1.31), (0.004, 0.15 * np.sqrt(1 - (k / 3.8) ** 2), 0.006), "metal",
                           rounding=False))
        # exhaust stack ending at the anchor
        ex = np.array(EXHAUST[0 if sx < 0 else 1])
        m.merge(vp.lathe([(0.13, -0.24), (0.13, -0.02), (0.125, 0.0), (0.105, 0.0), (0.105, -0.10), (0.0, -0.10)], 32,
                         "exhaust", axis="z", center=ex))
        # cowling latches
        for z in (-0.8, -0.3, 0.2):
            m.merge(vp.box((cx + sx * 0.26, 3.02, z), (0.012, 0.03, 0.05), "metal", 0.006))
    # the transmission cowling between them, the mast through it, the oil cooler grille at its back
    st = [(-1.62, 0.0, 2.88, 0.24, 0.12, -0.18, 2.6, 0.0, 0.5), (-1.30, 0.0, 2.92, 0.34, 0.26, -0.22, 2.8, 0.2, 0.6),
          (0.60, 0.0, 2.92, 0.36, 0.30, -0.22, 2.8, 0.3, 0.6), (1.25, 0.0, 2.88, 0.26, 0.14, -0.18, 2.6, 0.2, 0.5)]
    m.merge(vp.shell(st, "paint", count=40, round0=0.10, round1=0.08))
    for k in range(6):
        m.merge(vp.box((0.0, 2.94 + k * 0.035, 1.28), (0.20, 0.006, 0.01), "frame", rounding=False))
    m.merge(vp.cyl((0, 3.15, HUB[2]), (0, 3.52, HUB[2]), 0.085, 24, "metal"))
    m.merge(vp.lathe([(0.0, 0.0), (0.20, 0.0), (0.22, 0.02), (0.22, 0.05), (0.0, 0.05)], 36, "metal", axis="y",
                     center=(0, 3.28, HUB[2])))
    for a in (0, 120, 240):                        # the swashplate's actuators down to the cowling
        d = np.array([np.sin(np.radians(a)), 0, np.cos(np.radians(a))])
        p = np.array([0, 3.28, HUB[2]]) + d * 0.18
        m.merge(vp.cyl(p, p - Y * 0.10 + d * 0.04, 0.02, 10, "metal"))
    # wire cutter on the roof, beacons, antennas
    m.merge(vp.oriented_box((0, 2.84, -2.62), (0.006, 0.13, 0.05), X, vp.unit((0, 1, 0.9)), "metal", 0.003))
    m.merge(vp.lathe([(0.0, 0.0), (0.05, 0.0), (0.045, 0.05), (0.0, 0.07)], 16, "taillight", axis="y",
                     center=(0, 2.715, 1.55)))
    m.merge(vp.oriented_box((0, 2.86, 1.75), (0.006, 0.12, 0.10), X, vp.unit((0, 1, 0.5)), "plastic", 0.004))
    return m


# --------------------------------------------------------------------------------------------- main rotor
def _airfoil(chord, t, count=12):
    """A symmetric section (NACA 00xx), leading edge at u = 0: CCW (u, v) points."""
    xs = (1 - np.cos(np.linspace(0, np.pi, count))) / 2
    yt = 5 * t * (0.2969 * np.sqrt(xs) - 0.1260 * xs - 0.3516 * xs ** 2 + 0.2843 * xs ** 3 - 0.1036 * xs ** 4)
    upper = np.stack([xs, yt], 1)[::-1]                     # trailing edge -> leading edge over the top
    lower = np.stack([xs, -yt], 1)[1:-1]                    # and back underneath
    return np.concatenate([upper, lower]) * np.array([chord, chord])


def _blade(centre, d, up, length, r0, chord, thick, twist, droop, mats, tip_sweep=0.0, count=12):
    """A blade from r0 to `length` along the unit direction d, about the axis `up`; it advances towards up x d. Its
    chord pitches by twist(r) degrees (leading edge up); mats(face column) picks the erosion strip."""
    m_dir = vp.unit(np.cross(up, d))              # where the blade moves
    c_dir = -m_dir                                # leading edge -> trailing edge
    tip = min(0.30, 0.25 * (length - r0))
    rs = np.concatenate([r0 + (length - tip - r0) * np.array([0.0, 0.04, 0.10, 0.2, 0.32, 0.45, 0.58, 0.72, 0.86, 1.0]),
                         length - tip * np.array([0.6, 0.32, 0.14, 0.04, 0.0])])
    rings = []
    for r in rs:
        th = np.radians(twist(r))
        c_ = c_dir * np.cos(th) - up * np.sin(th)
        u_ = up * np.cos(th) + c_dir * np.sin(th)
        tipness = np.clip((r - (length - tip)) / tip, 0, 1)
        ch = chord * (1 - 0.45 * tipness ** 1.6)
        tk = thick * (1 - 0.5 * tipness)
        sweep = tip_sweep * tipness ** 2
        sec = _airfoil(ch, tk / chord, count)
        lead = -0.25 * chord + sweep + 0.25 * (chord - ch)
        rings.append([centre + d * r + up * droop(r) + c_ * (lead + p[0]) + u_ * p[1] for p in sec])
    rings = np.array(rings)
    n = rings.shape[1]
    m = Mesh()
    base = m.add_vertices(rings.reshape(-1, 3))
    idx = lambda k, i: base + k * n + (i % n)
    for k in range(len(rs) - 1):
        for i in range(n):
            m.add_face([idx(k, i), idx(k, i + 1), idx(k + 1, i + 1), idx(k + 1, i)], mats(i, n, rs[k]), soft=True)
    m.add_face([idx(0, i) for i in reversed(range(n))], mats(0, n, rs[0]))
    m.add_face([idx(len(rs) - 1, i) for i in range(n)], mats(0, n, rs[-1]))
    sh.orient_outward(m)
    return m


def _rotor_main():
    m = Mesh()
    c = HUB
    m.merge(vp.lathe([(0.0, -0.07), (0.27, -0.07), (0.31, -0.03), (0.31, 0.03), (0.25, 0.07), (0.0, 0.08)], 48,
                     "metal", axis="y", center=c))
    m.merge(vp.lathe([(0.0, 0.0), (0.11, 0.0), (0.10, 0.08), (0.05, 0.14), (0.0, 0.15)], 32, "metal", axis="y",
                     center=c + Y * 0.07))
    # the rotating swashplate ring, its links to the pitch horns
    m.merge(vp.lathe([(0.17, 0.0), (0.25, 0.0), (0.25, 0.04), (0.17, 0.04), (0.17, 0.0)], 40, "metal", axis="y",
                     center=c - Y * 0.27))

    def mats(i, n, r):
        lead = abs(i + 0.5 - n / 2) < 2.0          # the leading edge is the middle of the loop
        return "alu" if lead and r > 1.1 else "paint_accent"

    for k in range(4):
        a = np.radians(90 * k)
        d = np.array([np.cos(a), 0.0, np.sin(a)])
        mv = vp.unit(np.cross(Y, d))
        # grip: an elastomeric bearing and a forged arm out to the cuff
        m.merge(vp.cyl(c + d * 0.22, c + d * 0.44, 0.075, 20, "rubber"))
        m.merge(vp.oriented_box(c + d * 0.62, (0.20, 0.045, 0.085), d, Y, "metal", 0.02))
        m.merge(vp.cyl(c + d * 0.80, c + d * 0.98, 0.055, 16, "paint_accent"))
        horn = c + d * 0.40 + mv * 0.16 - Y * 0.02
        m.merge(vp.box(horn, (0.03, 0.025, 0.03), "metal", 0.008))
        m.merge(vp.cyl(horn - Y * 0.02, c - Y * 0.25 + d * 0.21 + mv * 0.08, 0.012, 8, "metal"))
        m.merge(_blade(c, d, Y, ROTOR_R, 0.95, 0.42, 0.046, lambda r: 9.0 - 7.0 * (r - 0.95) / (ROTOR_R - 0.95),
                       lambda r: -0.012 * max(r - 0.9, 0) ** 1.4, mats, tip_sweep=0.10))
    return m


def _rotor_tail():
    m = Mesh()
    c = TAIL_HUB
    m.merge(vp.lathe([(0.0, -0.06), (0.07, -0.06), (0.08, -0.03), (0.08, 0.04), (0.05, 0.08), (0.0, 0.09)], 28,
                     "metal", axis="x", center=c))
    m.merge(vp.lathe([(0.0, 0.0), (0.035, 0.0), (0.035, 0.05), (0.0, 0.06)], 16, "metal", axis="x",
                     center=c + X * 0.08))

    def mats(i, n, r):
        return "alu" if r > TAIL_R - 0.14 else "paint_accent"

    for k in range(4):
        a = np.radians(90 * k + 30)
        d = np.array([0.0, np.cos(a), np.sin(a)])
        m.merge(vp.cyl(c + d * 0.06, c + d * 0.17, 0.028, 12, "metal"))
        m.merge(_blade(c, d, X, TAIL_R, 0.15, 0.16, 0.018, lambda r: 6.0 - 3.0 * r, lambda r: 0.0, mats, count=9))
    return m


# --------------------------------------------------------------------------------------------- tail
def _tail():
    m = Mesh()
    # drive shaft cover along the boom top, with its hanger bearing humps
    st = [(2.15, 0.0, 2.80, 0.05, 0.02, -0.06, 2.4, 0.4, 0.0), (2.5, 0.0, 2.81, 0.08, 0.05, -0.06, 2.4, 0.4, 0.0),
          (5.8, 0.0, 2.69, 0.07, 0.05, -0.06, 2.4, 0.4, 0.0), (6.1, 0.0, 2.66, 0.05, 0.03, -0.06, 2.4, 0.4, 0.0)]
    m.merge(vp.shell(st, "paint", count=24, round0=0.06, round1=0.06))
    for z in (3.4, 4.5, 5.5):
        y = float(np.interp(z, [2.5, 5.8], [2.81, 2.69]))
        m.merge(vp.box((0, y + 0.05, z), (0.09, 0.03, 0.08), "paint", 0.02))
    # fin: swept, its root in the boom; the tail rotor's gearbox on its right
    fin = np.array([(5.62, 2.42), (6.50, 3.52), (6.86, 3.55), (6.95, 2.62), (6.88, 2.42)])
    m.merge(sh.extrude_x(fin, half_width=0.055, bevel=((0.02, 0.0), (0.0, 0.02)), material="paint"))
    gb = np.array([0.0, TAIL_HUB[1], TAIL_HUB[2]])
    m.merge(vp.lathe([(0.0, 0.05), (0.10, 0.05), (0.11, 0.10), (0.09, 0.25), (0.05, 0.30), (0.0, 0.31)], 28, "paint",
                     axis="x", center=gb))
    m.merge(vp.cyl(gb + X * 0.30, TAIL_HUB - X * 0.05, 0.03, 12, "metal"))
    # the intermediate gearbox where the shaft turns up into the fin
    m.merge(vp.box((0, 2.72, 5.95), (0.10, 0.08, 0.14), "paint", 0.04))
    # horizontal stabiliser and its end plates
    stab = np.array([(-1.15, 5.12), (-0.30, 4.90), (0.30, 4.90), (1.15, 5.12), (1.15, 5.38), (-1.15, 5.38)])
    m.merge(vp.plate_xz(stab, 2.28, 2.35, "paint", 0.02))
    for sx in (-1, 1):
        m.merge(vp.box((sx * 1.16, 2.33, 5.25), (0.015, 0.14, 0.15), "paint", 0.012))
    # beacon on the fin, the tail light at the boom's end, a stinger under the tail rotor
    m.merge(vp.lathe([(0.0, 0.0), (0.04, 0.0), (0.035, 0.04), (0.0, 0.055)], 16, "taillight", axis="y",
                     center=(0, 3.545, 6.70)))
    m.merge(vp.lathe([(0.0, 0.0), (0.04, 0.0), (0.035, 0.03), (0.0, 0.04)], 16, "headlight", axis="z",
                     center=(0, 2.42, TAIL_END - 0.01)))
    m.merge(vp.tube(vp.bend([(0, 2.20, 6.05), (0, 1.80, 6.45), (0, 1.86, 6.75), (0, 2.25, 6.82)], 0.12), 0.025, 10,
                    "metal"))
    m.merge(vp.oriented_box((0, 2.87, 4.0), (0.006, 0.10, 0.09), X, vp.unit((0, 1, 0.4)), "plastic", 0.004))
    return m


# --------------------------------------------------------------------------------------------- wings and pods
def _pod():
    """The right rocket pod: seven tubes in a faired drum, its pylon up to the wing."""
    m = Mesh()
    c = np.array([POD[0], POD[1], POD_FRONT])
    length = POD_BACK - POD_FRONT
    m.merge(vp.lathe([(0.17, 0.0), (0.195, 0.0), (0.20, 0.02), (0.20, length - 0.30), (0.17, length - 0.15),
                      (0.10, length - 0.03), (0.0, length)], 40, "paint_accent", axis="z", center=c))
    m.merge(vp.lathe([(0.0, 0.035), (0.172, 0.035), (0.172, 0.04), (0.0, 0.04)], 40, "frame", axis="z", center=c,
                     rounding=False))
    for k in range(7):
        off = np.zeros(3) if k == 6 else 0.105 * np.array([np.cos(k * np.pi / 3), np.sin(k * np.pi / 3), 0.0])
        m.merge(vp.lathe([(0.034, 0.035), (0.045, 0.035), (0.045, 0.0), (0.034, 0.0), (0.034, 0.035)], 16, "metal",
                         axis="z", center=c + off, rounding=False))
    for z in (-0.95, 0.10):                         # bands round the drum
        m.merge(vp.lathe([(0.20, 0.0), (0.206, 0.005), (0.206, 0.035), (0.20, 0.04)], 40, "metal", axis="z",
                         center=(POD[0], POD[1], z)))
    pylon = np.array([(-0.66, 1.50), (-0.16, 1.50), (-0.22, 2.44), (-0.60, 2.44)])          # (z, y)
    m.merge(sh.extrude_x(pylon, half_width=0.045, bevel=((0.015, 0.0), (0.0, 0.015)), material="paint",
                         x_center=POD[0]))
    for z in (-0.70, -0.12):                         # sway braces
        for sx in (-1, 1):
            p = np.array([POD[0] + sx * 0.10, 1.57, z])
            m.merge(vp.cyl(p + (0, 0.08, 0), p + (sx * 0.02, -0.02, 0), 0.012, 8, "metal"))
    return m


def _wing():
    """The right stub wing: off the roof line, a little anhedral, a navigation light at its tip."""
    st = [(0.96, 0.53, 2.62, 0.43, 0.062, -0.05, 2.3, 0.0, 0.0), (1.50, 0.50, 2.46, 0.40, 0.058, -0.048, 2.3, 0.0, 0.0),
          (2.05, 0.46, 2.30, 0.35, 0.05, -0.04, 2.3, 0.0, 0.0)]
    m = vp.shell(st, "paint", axis=X, up=Y, count=28, round1=0.05)
    return m


def _skids():
    m = Mesh()
    for sx in (-1, 1):
        x = sx * SKID_X
        path = vp.bend([(x, 0.055, 1.40), (x, 0.055, -2.55), (x, 0.42, -3.10)], 0.30, steps=10)
        m.merge(vp.tube(path, 0.055, 16, "paint"))
        m.merge(vp.lathe([(0.0, 0.0), (0.055, 0.0), (0.04, 0.03), (0.0, 0.035)], 16, "rubber", axis="z",
                         center=(x, 0.055, 1.40)))
        m.merge(vp.box((x, 0.012, -0.5), (0.03, 0.012, 1.4), "metal", 0.006, rounding=False))   # the wear shoe
        # steps by the cockpit door
        m.merge(vp.box((sx * (SKID_X - 0.02), 0.40, -2.10), (0.10, 0.012, 0.14), "metal", 0.006))
        m.merge(vp.cyl((x, 0.06, -2.10), (sx * (SKID_X - 0.02), 0.39, -2.10), 0.02, 8, "metal"))
    for z in (-1.95, 0.85):                        # arched cross tubes up to the belly, faired where they meet it
        path = vp.bend([(-SKID_X, 0.06, z), (-SKID_X, 0.40, z), (-0.84, 0.68, z), (0.84, 0.68, z), (SKID_X, 0.40, z),
                        (SKID_X, 0.06, z)], 0.20, steps=8)
        m.merge(vp.tube(path, 0.05, 16, "paint"))
        for sx in (-1, 1):
            m.merge(vp.box((sx * 0.72, 0.72, z), (0.10, 0.04, 0.09), "paint", 0.03))
    return m


def _nose_bits():
    m = Mesh()
    # the chin turret's mount, pitot tubes, the search light, antennas, the belly beacon
    m.merge(vp.lathe([(0.0, 0.0), (0.26, 0.0), (0.27, 0.02), (0.24, 0.05), (0.0, 0.05)], 40, "paint_accent",
                     axis="y", center=(0, 0.92, TURRET[2])))
    for sx in (-1, 1):
        p = np.array([sx * 0.16, 1.62, -3.98])
        m.merge(vp.tube([p, p + (0, 0.02, -0.12), p + (0, 0.02, -0.38)], [0.014, 0.010, 0.008], 10, "metal"))
    lamp = np.array([0.0, 1.01, -3.90])
    m.merge(vp.lathe([(0.0, 0.0), (0.075, 0.0), (0.08, 0.04), (0.07, 0.06), (0.0, 0.06)], 24, "metal", axis="y",
                     center=lamp - Y * 0.06))
    m.merge(vp.lathe([(0.0, -0.004), (0.065, -0.004), (0.065, 0.0), (0.0, 0.0)], 24, "headlight", axis="y",
                     center=lamp - Y * 0.06, rounding=False))
    m.merge(vp.lathe([(0.0, 0.0), (0.05, 0.0), (0.045, -0.04), (0.0, -0.05)], 16, "taillight", axis="y",
                     center=(0, 0.70, 1.10)))
    m.merge(vp.oriented_box((0.30, 0.62, -0.4), (0.006, 0.10, 0.08), X, vp.unit((0, -1, 0.4)), "plastic", 0.004))
    return m


def _wingtip_lights():
    """Red to port, the teal-green display glass to starboard."""
    def light(mat):
        return vp.lathe([(0.0, 0.0), (0.035, 0.0), (0.03, 0.03), (0.0, 0.04)], 16, mat, axis="x",
                        center=(2.04, 2.30, -0.45))
    return vp.merged(light("gauge"), vp.mirror_x(light("taillight")))


# --------------------------------------------------------------------------------------------- the gun
def _turret():
    """In vehicle space (the turret node yaws about Y through TURRET): its drum and the yoke down to the gun."""
    m = Mesh()
    m.merge(vp.lathe([(0.0, -0.05), (0.20, -0.05), (0.22, -0.01), (0.22, 0.05), (0.18, 0.07), (0.0, 0.07)], 40,
                     "paint_accent", axis="y", center=TURRET))
    for sx in (-1, 1):
        m.merge(vp.oriented_box(TURRET + (sx * 0.19, -0.10, 0.02), (0.025, 0.10, 0.10), X, Y, "paint_accent", 0.015))
    return m


def _gun():
    """In vehicle space (pitches about X through GUN): a six-barrel rotary gun firing along -Z."""
    m = Mesh()
    g = GUN
    m.merge(vp.cyl(g - X * 0.19, g + X * 0.19, 0.03, 12, "metal"))
    m.merge(vp.box(g + (0, 0.0, 0.02), (0.11, 0.09, 0.15), "gunmetal", 0.02))
    m.merge(vp.cyl(g + (0, 0.07, 0.06), g + (0, 0.07, 0.22), 0.05, 16, "gunmetal"))             # drive motor
    m.merge(vp.box(g + (0, 0.10, 0.0), (0.05, 0.03, 0.06), "frame", 0.01))                     # feed chute
    m.merge(vp.lathe([(0.0, 0.0), (0.085, 0.0), (0.09, 0.03), (0.09, 0.13), (0.07, 0.16), (0.0, 0.16)], 32,
                     "gunmetal", axis="z", center=g + Z * -0.30))
    for k in range(6):
        a = k * np.pi / 3
        off = 0.05 * np.array([np.cos(a), np.sin(a), 0.0])
        m.merge(vp.cyl(g + off + Z * -0.15, GUN_MUZZLE + off, 0.0135, 10, "gunmetal"))
    for z, r in ((-0.55, 0.075), (-0.93, 0.072)):          # barrel clamps
        m.merge(vp.lathe([(0.0, 0.0), (r, 0.0), (r, 0.03), (0.0, 0.03)], 28, "gunmetal", axis="z",
                         center=g + Z * (z - 0.03)))
    return m


# --------------------------------------------------------------------------------------------- build
def build(rig):
    skin, _ = _fuselage()
    rig.add("body", skin)
    rig.add("body", _doors())
    rig.add("body", _interior())
    rig.add("body", _engines())
    rig.add("body", _tail())
    rig.add("body", _skids())
    rig.add("body", _nose_bits())
    rig.add("body", vp.both_sides(_wing()))
    rig.add("body", vp.both_sides(_pod()))
    rig.add("body", _wingtip_lights())
    rig.node("rotor_main", "body", HUB)
    rig.add("rotor_main", _rotor_main())
    rig.node("rotor_tail", "body", TAIL_HUB)
    rig.add("rotor_tail", _rotor_tail())
    rig.node("chin_turret", "body", TURRET)
    rig.add("chin_turret", _turret())
    rig.node("chin_gun", "chin_turret", GUN)
    rig.add("chin_gun", _gun())
    rig.node("muzzle_chingun", "chin_gun", GUN_MUZZLE)
    rig.node("muzzle_rockets", "body", (-POD[0], POD[1], POD_FRONT))
    for i, p in enumerate(SEATS):
        rig.node(f"seat_{i}", "body", p)
    for i, p in enumerate(EXHAUST):
        rig.node(f"exhaust_{i}", "body", p)
    rig.node("headlight_0", "body", HEADLIGHT)
