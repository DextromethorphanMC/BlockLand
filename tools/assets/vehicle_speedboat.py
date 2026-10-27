"""Speedboat (发动机小游艇, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original 5.6 m bowrider with one
outboard. Vehicle space: metres, +Y up, the bow towards -Z (starboard = +X), the origin on the keel under the middle
of the hull (the boat's "ground").

A deep-V hull (20 degrees of deadrise aft, finer forward, a forefoot sweeping up to the stem) with hard chines and a
spray rail, flared topsides under a rising sheer, white gelcoat above the chine and antifouling below; a crowned bow
deck with a chrome bow rail, cleats and the bow light; the cockpit liner and its non-skid floor, a wrap-around
windshield over the helm console (wheel, throttle, gauges) on the starboard side and the passenger console to port,
two pedestal helm seats and a bench across the stern; the swim platform with a boarding ladder, the stern light pole,
rub rails along the sheer. The outboard on the transom turns about its steering axis; its propeller spins about Z."""
import numpy as np

import shapes as sh
import vehicle_parts as vp
from meshkit import Mesh
from vehicle_parts import X, Y, Z

BOW, STERN = -2.90, 2.70
FLOOR = 0.46
CONSOLE_Z = -0.62
SEATS = [(0.46, FLOOR + 0.46, 0.30), (-0.46, FLOOR + 0.46, 0.30), (-0.58, FLOOR + 0.40, 2.12), (0.0, FLOOR + 0.40, 2.12),
         (0.58, FLOOR + 0.40, 2.12)]                     # helm (driver), passenger, the stern bench
OUTBOARD = np.array([0.0, 0.98, STERN + 0.10])            # steering axis (vertical) through the clamp
PROP = np.array([0.0, -0.08, STERN + 0.52])
N_ST = 34


def beam(z):
    """Half beam at the sheer."""
    t = np.clip((z - BOW) / 2.9, 0, 1)
    return 1.05 * (1 - (1 - t) ** 2.2) ** 0.55


def sheer(z):
    return 1.00 + 0.17 * np.clip(-z / 2.9, 0, 1) ** 1.6


def keel(z):
    return 0.0 if z > -1.0 else 0.86 * ((-1.0 - z) / 1.9) ** 2.1


def deadrise(z):
    return np.radians(20 + 26 * np.clip((-z + 0.5) / 3.4, 0, 1) ** 1.5)


def section(z):
    """Starboard half from the sheer down to the keel: (points, index of the chine)."""
    b, s, k = beam(z), sheer(z), keel(z)
    cx = 0.86 * b
    cy = min(k + cx * np.tan(deadrise(z)), s - 0.08)
    pts = [(b, s), (b * 0.985, s - 0.12 * (s - cy)), (b * 0.95, s - 0.45 * (s - cy)), (cx + 0.035 * b, cy + 0.035),
           (cx, cy)]
    chine = len(pts) - 1
    # spray rail half way down the bottom
    mx, my = cx * 0.55, k + cx * 0.55 * np.tan(deadrise(z))
    pts += [(mx + 0.02, my + 0.004), (mx, my - 0.012), (mx - 0.01, my - 0.006)]
    pts += [(0.05, k + 0.05 * np.tan(deadrise(z))), (0.0, k)]
    return np.array(pts), chine


def _flip_out(m, axis_point_fn):
    """Turns a surface's faces to face away from the hull's centre line."""
    v = np.array(m.v)
    score = 0.0
    for f in m.f:
        p = v[list(f)]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        c = p.mean(0)
        score += np.dot(n, c - axis_point_fn(c))
    if score < 0:
        m.f = [tuple(reversed(f)) for f in m.f]
        m.uv = [list(reversed(u)) if u is not None else None for u in m.uv]
    return m


def _hull():
    m = Mesh()
    # stations dense at the bow, where the sections change fast
    zs = np.concatenate([BOW + 0.02 + (np.linspace(0, 1, 10) ** 1.6) * 1.4, np.linspace(BOW + 1.6, STERN, N_ST - 10)])
    top_s, bot_s, top_p, bot_p = [], [], [], []
    for z in zs:
        pts, ch = section(z)
        st = [np.array([x, y, z]) for x, y in pts]
        top_s.append(st[:ch + 1])
        bot_s.append(st[ch:])
        top_p.append([p * (-1, 1, 1) for p in st[:ch + 1]])
        bot_p.append([p * (-1, 1, 1) for p in st[ch:]])
    centre = lambda c: np.array([0.0, max(c[1], 0.45), c[2]])
    for rings, mat in ((top_s, "gelcoat"), (top_p, "gelcoat"), (bot_s, "paint"), (bot_p, "paint")):
        surf = sh.loft(np.array(rings), mat, cap_start=False, cap_end=False, closed=False, rounding=False)
        m.merge(_flip_out(surf, centre))
    # transom: the stern section closed off, with a motor notch at the top
    pts, ch = section(STERN)
    loop = [(x, y) for x, y in pts] + [(-x, y) for x, y in pts[::-1][1:]]
    loop = np.array(loop)
    tr = Mesh()
    sh.cap_polygon(tr, np.stack([loop[:, 0], loop[:, 1], np.full(len(loop), STERN)], 1), "gelcoat")
    m.merge(_flip_out(tr, lambda c: np.array([c[0], c[1], 0.0])))
    # rub rails along the sheer
    for sx in (-1, 1):
        rail = [(sx * (beam(z) + 0.012), sheer(z) - 0.03, z) for z in np.linspace(BOW + 0.06, STERN, 40)]
        m.merge(vp.tube(rail, 0.026, 12, "rubber"))
        m.merge(vp.tube([(sx * (beam(z) + 0.035), sheer(z) - 0.03, z) for z in np.linspace(BOW + 0.3, STERN - 0.05, 40)],
                        0.007, 8, "chrome"))
    # a sheer stripe under the rub rail and a boot stripe over the waterline, a hair proud of the topsides
    for frac0, frac1, mat in ((0.06, 0.16, "paint_accent"), (0.84, 0.90, "paint_accent")):
        for sx in (-1, 1):
            band = []
            for z in zs[3:]:
                pts, ch = section(z)
                top = pts[:ch + 1]
                ys = np.interp(np.linspace(0, 1, len(top)), np.linspace(0, 1, len(top)), top[:, 1])
                s, cy = ys[0], ys[-1]
                ring = []
                for f in (frac0, frac1):
                    y = s - f * (s - cy)
                    x = np.interp(-y, -top[:, 1], top[:, 0]) + 0.003
                    ring.append(np.array([sx * x, y, z]))
                band.append(ring)
            m.merge(_flip_out(sh.loft(np.array(band), mat, cap_start=False, cap_end=False, closed=False,
                                      rounding=False), centre))
    # the stem's bow eye
    eye = np.array([0.0, keel(BOW + 0.25) + 0.30, BOW + 0.20])
    m.merge(vp.tube([eye + (0, 0, -0.02), eye + (0, -0.05, -0.08), eye + (0, -0.10, -0.02)], 0.009, 8, "chrome"))
    return m


def _deck_strip(z0, z1, inner, mat, n=24, crown=0.0):
    """A deck surface between the sheers (inner = None) or a side deck strip of width `inner`, over z0..z1."""
    rings = []
    for z in np.linspace(z0, z1, n):
        b, s = beam(z), sheer(z)
        if inner is None:
            xs = np.linspace(-b, b, 17)
            rings.append([(x, s + crown * (1 - (x / max(b, 1e-3)) ** 2), z) for x in xs])
        else:
            rings.append([(b, s, z), (b - inner, s + 0.01, z)])
    return np.array(rings)


def _deck():
    m = Mesh()
    bow = _deck_strip(BOW + 0.03, CONSOLE_Z - 0.10, None, "gelcoat", 26, crown=0.07)
    m.merge(_flip_out(sh.loft(bow, "gelcoat", cap_start=False, cap_end=False, closed=False, rounding=False),
                      lambda c: np.array([c[0], c[1] - 1.0, c[2]])))
    for sx in (-1, 1):
        strip = _deck_strip(CONSOLE_Z - 0.10, STERN, 0.20, "gelcoat", 26)
        strip = strip * np.array([sx, 1, 1])
        m.merge(_flip_out(sh.loft(strip, "gelcoat", cap_start=False, cap_end=False, closed=False, rounding=False),
                          lambda c: np.array([c[0], c[1] - 1.0, c[2]])))
        # cockpit liner wall from the side deck down to the floor
        wall = []
        for z in np.linspace(CONSOLE_Z - 0.10, STERN - 0.14, 20):
            x = sx * (beam(z) - 0.20)
            wall.append([(x, sheer(z) + 0.01, z), (x - sx * 0.02, FLOOR + 0.05, z), (x - sx * 0.06, FLOOR, z)])
        m.merge(_flip_in(sh.loft(np.array(wall), "gelcoat", cap_start=False, cap_end=False, closed=False,
                                 rounding=False)))
        # side storage pocket and the cup holders along the liner
        m.merge(vp.box((sx * (beam(1.0) - 0.215), FLOOR + 0.30, 1.0), (0.012, 0.11, 0.40), "plastic", 0.01))
    # the cockpit's forward bulkhead: from the floor up to the bow deck's crowned aft edge, between the liner walls (the
    # hull is a one-sided skin seen from outside: without it the cockpit looked forward into the open hull — water)
    zb = CONSOLE_Z - 0.10
    bb, sb = beam(zb), sheer(zb)
    xs = np.linspace(-(bb - 0.20), bb - 0.20, 17)
    edge = [(x, sb + 0.07 * (1 - (x / max(bb, 1e-3)) ** 2), zb) for x in xs[::-1]]
    bulk = np.array([(-(bb - 0.26), FLOOR, zb), (bb - 0.26, FLOOR, zb)] + edge)
    bh = Mesh()
    sh.cap_polygon(bh, bulk, "gelcoat")
    m.merge(_flip_out(bh, lambda c: np.array([c[0], c[1], c[2] - 1.0])))
    # non-skid cockpit floor and the inner transom wall
    floor = [(-(beam(z) - 0.26), z) for z in np.linspace(CONSOLE_Z - 0.10, STERN - 0.14, 14)]
    floor += [((beam(z) - 0.26), z) for z in np.linspace(STERN - 0.14, CONSOLE_Z - 0.10, 14)]
    m.merge(vp.plate_xz(np.array(floor), FLOOR - 0.03, FLOOR, "deck", 0.004))
    b = beam(STERN - 0.14) - 0.20
    tw = Mesh()
    sh.cap_polygon(tw, np.array([(-b, FLOOR, STERN - 0.14), (b, FLOOR, STERN - 0.14), (b, sheer(STERN), STERN - 0.14),
                                 (-b, sheer(STERN), STERN - 0.14)]), "gelcoat", flip=True)
    m.merge(tw)
    # bow rail on stanchions, cleats, the bow light
    for sx in (-1, 1):
        rail = [(sx * (beam(z) - 0.07), sheer(z) + 0.26, z) for z in np.linspace(BOW + 0.32, -1.20, 18)]
        m.merge(vp.tube(rail, 0.0125, 12, "chrome"))
        for z in (BOW + 0.40, -2.05, -1.25):
            x = sx * (beam(z) - 0.07)
            m.merge(vp.cyl((x, sheer(z), z), (x, sheer(z) + 0.26, z), 0.010, 10, "chrome"))
            m.merge(vp.lathe([(0.0, 0.0), (0.028, 0.0), (0.028, 0.006), (0.0, 0.006)], 16, "chrome", axis="y",
                             center=(x, sheer(z), z)))
        for z in (-2.20, STERN - 0.25):
            m.merge(_cleat((sx * (beam(z) - 0.10), sheer(z) + 0.01, z)))
    m.merge(vp.tube([(-0.0, sheer(BOW + 0.32) + 0.26, BOW + 0.32 - 0.06), (0.0, sheer(BOW + 0.32) + 0.26, BOW + 0.32)],
                    0.0125, 12, "chrome"))
    light = np.array([0.0, sheer(BOW + 0.18) + 0.06, BOW + 0.18])
    m.merge(vp.box(light, (0.05, 0.03, 0.04), "chrome", 0.01))
    m.merge(vp.box(light + (0.03, 0.0, -0.02), (0.022, 0.022, 0.02), "indicator", 0.006))
    m.merge(vp.box(light + (-0.03, 0.0, -0.02), (0.022, 0.022, 0.02), "taillight", 0.006))
    # anchor locker hatch on the bow deck
    hatch = vp.shell([(-1.95, 0, sheer(-1.9) + 0.045, 0.24, 0.03, -0.02, 6.0, 0.8, 0.8),
                      (-1.55, 0, sheer(-1.6) + 0.045, 0.24, 0.03, -0.02, 6.0, 0.8, 0.8)], "gelcoat", count=24,
                     round0=0.02, round1=0.02)
    m.merge(hatch)
    return m, light


def _flip_in(m):
    """Faces of a cockpit liner face the cockpit (towards the centre line)."""
    return _flip_out(m, lambda c: np.array([c[0] * 2.0, c[1], c[2]]))


def _cleat(c):
    c = np.asarray(c, float)
    m = vp.merged(vp.box(c + (0, 0.012, 0), (0.016, 0.012, 0.03), "chrome", 0.006))
    m.merge(vp.tube(vp.bend([c + (0, 0.02, -0.09), c + (0, 0.04, -0.06), c + (0, 0.04, 0.06), c + (0, 0.02, 0.09)], 0.03),
                    0.011, 10, "chrome"))
    return m


def _helm():
    m = Mesh()
    # windshield: a wrap-around glass band leaning back, its chrome top frame, the walk-through split in the middle
    lean = np.radians(28)
    top_rings, rings = [], []
    for x in np.linspace(-0.96, 0.96, 25):
        if abs(x) < 0.24:
            continue
        z = CONSOLE_Z - 0.28 * (1 - (x / 0.96) ** 2)
        y0 = sheer(z) + 0.02
        base = np.array([x, y0, z])
        top = base + np.array([0.0, np.cos(lean), np.sin(lean)]) * 0.42
        rings.append((x, base, top))
    for side in (-1, 1):
        part = [r for r in rings if np.sign(r[0]) == side]
        glass = np.array([[b, t] for _, b, t in part])
        g = sh.loft(glass, "glass", cap_start=False, cap_end=False, closed=False, rounding=False)
        m.merge(g)
        m.merge(_flip_out(sh.loft(glass - np.array([0, 0, 0.004]), "glass", cap_start=False, cap_end=False,
                                  closed=False, rounding=False), lambda c: np.array([c[0], c[1], c[2] + 1.0])))
        m.merge(vp.tube([t for _, _, t in part], 0.012, 10, "chrome"))
        m.merge(vp.tube([b for _, b, _ in part], 0.010, 10, "rubber"))
    # helm console (starboard) and passenger console (port)
    for sx in (1, -1):
        cx = sx * 0.46
        con = vp.shell([(CONSOLE_Z - 0.05, cx, 0, 0.30, FLOOR + 0.70, FLOOR, 5.0, 0.8, 0.9),
                        (CONSOLE_Z + 0.20, cx, 0, 0.30, FLOOR + 0.62, FLOOR, 5.0, 0.8, 0.9),
                        (CONSOLE_Z + 0.32, cx, 0, 0.29, FLOOR + 0.50, FLOOR, 5.0, 0.8, 0.9)], "gelcoat", count=28,
                       round0=0.03, round1=0.04)
        m.merge(con)
    dash = np.array([0.46, FLOOR + 0.66, CONSOLE_Z + 0.20])
    m.merge(vp.oriented_box(dash, (0.25, 0.10, 0.012), X, vp.unit((0, 0.55, 1.0)), "plastic", 0.008))
    for dx in (-0.13, 0.0, 0.13):
        c = dash + (dx, 0.01, 0.01)
        n = vp.unit((0, 0.55, 1.0))
        g = vp.lathe([(0.0, 0.0), (0.036, 0.0), (0.036, 0.004), (0.0, 0.004)], 24, "gauge", axis="z")
        r = vp.lathe([(0.034, 0.0), (0.042, 0.0), (0.042, 0.010), (0.034, 0.008), (0.034, 0.0)], 24, "chrome", axis="z")
        u, v = vp.frame_of(n)
        for part in (g, r):
            m.merge(sh.place(part, origin=c, x_axis=u, y_axis=v, z_axis=n))
    # steering wheel on its tilt hub, throttle lever
    hub = np.array([0.46, FLOOR + 0.60, CONSOLE_Z + 0.30])
    ax = vp.unit((0.0, 0.45, 1.0))
    m.merge(vp.cyl(hub - ax * 0.10, hub, 0.03, 16, "chrome"))
    u, v = vp.frame_of(ax)
    ring = [hub + ax * 0.04 + 0.17 * (np.cos(a) * u + np.sin(a) * v) for a in np.linspace(0, 2 * np.pi, 41)]
    m.merge(vp.tube(ring, 0.014, 10, "chrome", cap=False))
    for a in np.linspace(0, 2 * np.pi, 3, endpoint=False):
        d = np.cos(a) * u + np.sin(a) * v
        m.merge(vp.tube([hub + ax * 0.03 + d * 0.03, hub + ax * 0.04 + d * 0.16], 0.008, 8, "chrome"))
    thr = np.array([0.70, FLOOR + 0.62, CONSOLE_Z + 0.18])
    m.merge(vp.box(thr, (0.035, 0.03, 0.05), "plastic", 0.01))
    m.merge(vp.tube([thr + (0, 0.02, 0), thr + (0.0, 0.16, 0.06)], 0.009, 8, "chrome"))
    m.merge(vp.lathe([(0.0, -0.025), (0.018, -0.025), (0.018, 0.025), (0.0, 0.025)], 12, "plastic", axis="x",
                     center=thr + (0.0, 0.16, 0.06)))
    return m


def _seats():
    m = Mesh()
    for x, hip, z in SEATS[:2]:
        m.merge(vp.cyl((x, FLOOR, z), (x, hip - 0.12, z), 0.04, 16, "chrome"))
        m.merge(vp.lathe([(0.0, 0.0), (0.16, 0.0), (0.16, 0.012), (0.0, 0.012)], 24, "chrome", axis="y",
                         center=(x, FLOOR, z)))
        m.merge(vp.shell([(z - 0.22, x, hip - 0.08, 0.24, 0.07, -0.05, 4.0, 0.6, 0.8),
                          (z + 0.20, x, hip - 0.08, 0.25, 0.07, -0.05, 4.0, 0.6, 0.8)], "seat", count=28, round0=0.04,
                         round1=0.03))
        ax = vp.unit((0.0, 1.0, 0.18))
        back = vp.shell([(0.0, 0, 0, 0.24, 0.06, -0.06, 4.0, 0.7, 0.7), (0.48, 0, 0, 0.21, 0.06, -0.05, 4.0, 0.7, 0.7)],
                        "seat", count=28, axis=ax, up=np.cross(ax, X), round0=0.03, round1=0.06)
        m.merge(back.translated((x, hip - 0.06, z + 0.22)))
    # stern bench: cushion and backrest against the inner transom
    b = beam(2.1) - 0.26
    m.merge(vp.box((0.0, FLOOR + 0.17, 2.18), (b, 0.17, 0.34), "gelcoat", 0.03))
    m.merge(vp.shell([(-b + 0.02, 0, FLOOR + 0.38, 0.30, 0.05, -0.05, 5.0, 0.7, 0.8),
                      (b - 0.02, 0, FLOOR + 0.38, 0.30, 0.05, -0.05, 5.0, 0.7, 0.8)], "seat", count=28, axis=X, up=Y,
                     round0=0.04, round1=0.04).translated((0, 0, 2.16)))
    m.merge(vp.shell([(-b + 0.02, 0, FLOOR + 0.66, 0.06, 0.18, -0.18, 5.0, 0.7, 0.7),
                      (b - 0.02, 0, FLOOR + 0.66, 0.06, 0.18, -0.18, 5.0, 0.7, 0.7)], "seat", count=28, axis=X, up=Y,
                     round0=0.04, round1=0.04).translated((0, 0, STERN - 0.22)))
    return m


def _stern():
    m = Mesh()
    # swim platform either side of the motor, the boarding ladder (starboard), the stern light pole
    for sx in (-1, 1):
        plat = np.array([(sx * 0.22, STERN), (sx * 0.95, STERN), (sx * 0.90, STERN + 0.36), (sx * 0.26, STERN + 0.36)])
        m.merge(vp.plate_xz(plat if sx > 0 else plat[::-1], 0.30, 0.34, "deck", 0.006))
    for x in (0.55, 0.75):
        m.merge(vp.tube(vp.bend([(x, 0.34, STERN + 0.30), (x, 0.36, STERN + 0.40), (x, 0.0, STERN + 0.42)], 0.04), 0.011,
                        10, "chrome"))
    for y in (0.22, 0.10, -0.02):
        m.merge(vp.box((0.65, y, STERN + 0.42), (0.11, 0.008, 0.02), "chrome", 0.004))
    pole = np.array([-0.80, sheer(STERN) + 0.01, STERN - 0.10])
    m.merge(vp.cyl(pole, pole + Y * 0.70, 0.012, 10, "chrome"))
    m.merge(vp.lathe([(0.0, 0.0), (0.03, 0.0), (0.03, 0.06), (0.0, 0.07)], 16, "headlight", axis="y",
                     center=pole + Y * 0.70))
    # the outboard's clamp bracket on the transom top
    m.merge(vp.box((0.0, sheer(STERN) - 0.06, STERN + 0.03), (0.16, 0.10, 0.05), "metal", 0.01))
    return m, pole + Y * 0.73


def _outboard():
    """In vehicle space; the node pivots about the vertical through OUTBOARD."""
    m = Mesh()
    o = OUTBOARD
    # cowling: a rounded box above the transom
    cowl = vp.shell([(0.08, 0, 0, 0.20, 0.10, -0.30, 3.6, 0.3, 0.2), (0.35, 0, 0, 0.23, 0.12, -0.32, 3.8, 0.3, 0.2),
                     (0.56, 0, 0, 0.21, 0.09, -0.28, 3.4, 0.4, 0.2)], "paint_accent", count=36, axis=Y, up=-Z,
                    round0=0.03, round1=0.08)
    m.merge(cowl.translated((o[0], o[1], o[2] + 0.18)))
    m.merge(vp.shell([(0.0, 0, 0, 0.19, 0.08, -0.28, 3.4, 0.3, 0.2), (0.10, 0, 0, 0.20, 0.09, -0.29, 3.4, 0.3, 0.2)],
                     "plastic", count=36, axis=Y, up=-Z).translated((o[0], o[1], o[2] + 0.18)))
    # midsection leg: a streamlined foil down to the cavitation plate
    leg = vp.shell([(-0.88, 0, 0, 0.055, 0.10, -0.16, 2.4, 0, 0), (-0.40, 0, 0, 0.06, 0.12, -0.18, 2.4, 0, 0),
                    (0.0, 0, 0, 0.075, 0.13, -0.22, 2.4, 0, 0)], "paint_accent", count=24, axis=Y, up=-Z)
    m.merge(leg.translated((o[0], o[1], o[2] + 0.26)))
    plate = np.array([(-0.17, STERN + 0.20), (0.17, STERN + 0.20), (0.15, STERN + 0.62), (-0.15, STERN + 0.62)])
    m.merge(vp.plate_xz(plate, 0.075, 0.09, "paint_accent", 0.004))
    # gearcase torpedo, skeg, water intake slots
    gear = vp.shell([(STERN + 0.22, 0, PROP[1], 0.05, 0.05, -0.05, 2.0, 0, 0), (STERN + 0.36, 0, PROP[1], 0.068, 0.068,
                                                                                 -0.068, 2.0, 0, 0),
                     (STERN + 0.50, 0, PROP[1], 0.055, 0.055, -0.055, 2.0, 0, 0)], "paint_accent", count=24,
                    round0=0.045, round1=0.01)
    m.merge(gear)
    skeg = np.array([(STERN + 0.28, PROP[1] - 0.03), (STERN + 0.50, PROP[1] - 0.04), (STERN + 0.48, PROP[1] - 0.24),
                     (STERN + 0.38, PROP[1] - 0.22)])
    m.merge(vp.plate(skeg, 0.008, "paint_accent"))
    for k in range(4):
        m.merge(vp.box((0.058, PROP[1] + 0.03, STERN + 0.30 + k * 0.03), (0.004, 0.02, 0.006), "plastic", rounding=False))
    # tiller-side steering arm and the trim cylinder, the exhaust relief outlet
    m.merge(vp.box((0.0, o[1] + 0.02, o[2] + 0.03), (0.05, 0.03, 0.08), "metal", 0.01))
    outlet = np.array([0.0, o[1] - 0.05, o[2] + 0.42])
    return m, outlet


def _propeller():
    """About Z through PROP (in vehicle space): hub and three cupped, twisted blades."""
    m = Mesh()
    hub = vp.lathe([(0.0, -0.06), (0.040, -0.06), (0.046, -0.02), (0.042, 0.06), (0.022, 0.09), (0.0, 0.095)], 28,
                   "propeller", axis="z", center=PROP)
    m.merge(hub)
    for b in range(3):
        a0 = b * 2 * np.pi / 3
        rings = []
        for k, r in enumerate(np.linspace(0.042, 0.17, 9)):
            chord = 0.07 + 0.09 * np.sin(np.pi * (k / 8) ** 0.8) - 0.03 * (k / 8) ** 3
            pitch = np.radians(62 - 30 * k / 8)
            thick = 0.010 * (1 - 0.8 * k / 8)
            sweep = 0.25 * (k / 8) ** 2
            pts = []
            for t in np.linspace(0, 2 * np.pi, 10, endpoint=False):
                u = 0.5 * chord * np.cos(t)
                w = thick * np.sin(t) + 0.004 * np.cos(t) ** 2
                ang = a0 + sweep + u / r
                d = np.array([np.cos(ang), np.sin(ang), 0.0])
                pts.append(PROP + d * r + Z * (u * np.cos(pitch) * -0.6 + w * np.sin(pitch)) + 0.0 * d)
            rings.append(pts)
        m.merge(sh.loft(np.array(rings), "propeller", cap_end=True, rounding=False))
    return m


def build(rig):
    rig.add("body", _hull())
    deck, bow_light = _deck()
    rig.add("body", deck)
    rig.add("body", _helm())
    rig.add("body", _seats())
    stern, stern_light = _stern()
    rig.add("body", stern)
    rig.node("outboard", "body", OUTBOARD)
    ob, outlet = _outboard()
    rig.add("outboard", ob)
    rig.node("propeller", "outboard", PROP)
    rig.add("propeller", _propeller())
    for i, (x, hip, z) in enumerate(SEATS):
        rig.node(f"seat_{i}", "body", (x, hip, z))
    rig.node("headlight_0", "body", bow_light)
    rig.node("taillight_0", "body", stern_light)
    rig.node("exhaust_0", "outboard", outlet)
    rig.node("wake", "body", (0.0, 0.10, STERN))
