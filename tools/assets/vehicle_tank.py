"""Tank (坦克, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original main battle tank sized for the game
(hull 5.9 m, 3.2 m over the tracks). Vehicle space: metres, +Y up, the front towards -Z (its right is +X), the origin on
the ground under the middle of the running gear. Three seats: the driver in the hull front, the gunner in the turret,
the commander at the roof machine gun (exposed in his hatch).

Hull: a welded box with a two-plane glacis, the deck over the engine at the back with its grilles, fender shelves over
the tracks carrying stowage bins, bolted side skirts over the upper run, headlamps in guards, tow hooks and the rear
plate's exhaust grilles. Running gear per side: six dual road wheels with rubber tyres, three return rollers, the
drive sprocket at the rear and the idler at the front, a link track with grousers and guide horns (material `track`,
scrolling). Turret: faceted welded armour on its ring, a bustle with a stowage basket, the mantlet under a canvas
cover, the gun with its thermal sleeve, fume extractor and muzzle reference sensor; the gunner's sight box, smoke
dischargers, the commander's cupola carrying the machine gun behind its shield, antennas."""
import numpy as np

import gen_vehicles as gv
import shapes as sh
import vehicle_parts as vp
from meshkit import Mesh
from vehicle_parts import X, Y, Z

TRACK_X, TRACK_W, TRACK_T = 1.36, 0.50, 0.055
WHEEL_Y, WHEEL_R = 0.40, 0.33
WHEEL_Z = [-1.95, -1.17, -0.39, 0.39, 1.17, 1.95]
SPROCKET = (2.68, 0.84, 0.31)                 # z, y, radius (drive, at the rear)
IDLER = (-2.72, 0.80, 0.29)
ROLLERS = [(-1.25, 1.13), (0.0, 1.14), (1.25, 1.13)]
ROLLER_R = 0.085
HULL_W = 1.06
DECK = 1.42
TURRET = np.array([0.0, DECK, 0.25])          # ring centre (yaw about Y)
TRUNNION = np.array([0.0, 1.88, -0.95])       # the gun's pitch axis (X)
BARREL = 4.05                                 # from the trunnions to the crown
CUPOLA = np.array([0.52, 2.16, 0.42])
MG_PIVOT = CUPOLA + np.array([0.0, 0.16, -0.05])
SEATS = [((-0.42, 1.02, -1.95), "body"), ((-0.38, 1.34, 0.10), "turret"), ((0.52, 1.95, 0.42), "turret")]


# ------------------------------------------------------------------------------------------- running gear
def road_wheel():
    """About X through the origin: two steel discs with lightening holes and rubber tyres, the gap between them
    for the guide horns. Kept light (twelve of them, half behind the skirts)."""
    m = Mesh()
    for side in (-1, 1):
        xc = side * 0.11
        m.merge(vp.lathe([(WHEEL_R - 0.055, -0.08), (WHEEL_R - 0.01, -0.08), (WHEEL_R, -0.065), (WHEEL_R, 0.065),
                          (WHEEL_R - 0.01, 0.08), (WHEEL_R - 0.055, 0.08), (WHEEL_R - 0.055, -0.08)], 32, "rubber",
                         center=(xc, 0, 0), rounding=False))
        m.merge(vp.rim_face(WHEEL_R - 0.05, 6, 0.045, 0.17, 0.010, "armor", x=xc + side * 0.06, rounding=False,
                            count=32, hole_count=12))
        hub = vp.lathe([(0.0, 0.0), (0.09, 0.0), (0.10, 0.03), (0.07, 0.06), (0.0, 0.065)], 20, "metal",
                       center=(abs(xc) + 0.065, 0, 0), rounding=False)
        m.merge(hub if side > 0 else vp.mirror_x(hub))
        m.merge(vp.nuts(8, 0.06, 0.009, 0.012, "metal", xc + side * 0.12, side))
    m.merge(vp.cyl((-0.17, 0, 0), (0.17, 0, 0), 0.06, 16, "metal"))
    return m


def _gear(r_root, r_tip, teeth, per=4):
    out = []
    for i in range(teeth * per):
        a = 2 * np.pi * i / (teeth * per)
        ph = (i % per) / per
        r = r_root + (r_tip - r_root) * (0.5 - 0.5 * np.cos(2 * np.pi * ph)) ** 0.8
        out.append((r * np.cos(a), r * np.sin(a)))
    return np.array(out)


def sprocket(r):
    m = Mesh()
    for side in (-1, 1):
        m.merge(sh.extrude_x(_gear(r - 0.035, r + 0.035, 13), [sh.circle(0, 0, 0.12, 32)], half_width=0.022,
                             material="metal", x_center=side * 0.09, rounding=False))
    m.merge(vp.lathe([(0.0, -0.2), (0.15, -0.2), (0.17, -0.12), (0.17, 0.12), (0.15, 0.2), (0.0, 0.2)], 32, "armor"))
    m.merge(vp.nuts(10, 0.12, 0.012, 0.015, "metal", 0.20, 1))
    return m


def idler(r):
    m = Mesh()
    for side in (-1, 1):
        m.merge(vp.lathe([(r - 0.06, -0.05), (r, -0.05), (r, 0.05), (r - 0.06, 0.05), (r - 0.06, -0.05)], 36, "metal",
                         center=(side * 0.11, 0, 0), rounding=False))
        m.merge(vp.rim_face(r - 0.05, 5, 0.04, 0.15, 0.010, "armor", x=side * 0.15, rounding=False, count=36,
                            hole_count=12))
    m.merge(vp.cyl((-0.20, 0, 0), (0.20, 0, 0), 0.07, 20, "metal"))
    return m


def roller():
    return vp.merged(vp.lathe([(0.0, -0.13), (ROLLER_R, -0.13), (ROLLER_R + 0.005, -0.10), (ROLLER_R + 0.005, 0.10),
                               (ROLLER_R, 0.13), (0.0, 0.13)], 24, "rubber"),
                     vp.lathe([(0.0, 0.13), (0.04, 0.13), (0.04, 0.16), (0.0, 0.17)], 16, "metal"))


def track_path():
    """The track's inner face in the (y, z) plane: under the road wheels, round the sprocket, over the rollers,
    round the idler (sampled densely; track_band resamples it)."""
    pts = []
    zf, zr = WHEEL_Z[0], WHEEL_Z[-1]
    yb = WHEEL_Y - WHEEL_R
    for z in np.linspace(zr, zf, 30):                                   # lower run, rear to front
        pts.append((0.0, yb, z))
    # up the front to the idler and round its front
    zi, yi, ri = IDLER
    a0 = np.arctan2(yb - yi, zf - zi)
    for a in np.linspace(a0, a0 - np.radians(200), 26):
        pts.append((0.0, yi + ri * np.sin(a), zi + ri * np.cos(a)))
    # upper run over the return rollers, sagging a little between them
    ytop = [ry + ROLLER_R for _, ry in ROLLERS]
    zs = [IDLER[0] + 0.15] + [rz for rz, _ in ROLLERS] + [SPROCKET[0] - 0.12]
    ys = [IDLER[1] + IDLER[2] - 0.02] + ytop + [SPROCKET[1] + SPROCKET[2] - 0.02]
    for z in np.linspace(zs[0], zs[-1], 40):
        y = np.interp(z, zs, ys)
        k = np.searchsorted(zs, z)
        if 0 < k < len(zs):
            t = (z - zs[k - 1]) / (zs[k] - zs[k - 1])
            y -= 0.035 * np.sin(np.pi * t)
        pts.append((0.0, y, z))
    zs_, ys_, rs_ = SPROCKET
    a0 = np.arctan2((ys_ + rs_ - 0.02) - ys_, (zs[-1]) - zs_)
    for a in np.linspace(np.pi / 2, -np.pi / 2 - np.radians(40), 26):
        pts.append((0.0, ys_ + rs_ * np.sin(a), zs_ + rs_ * np.cos(a)))
    return np.array(pts)


# ------------------------------------------------------------------------------------------------ hull
def _hull():
    m = Mesh()
    side = np.array([(-2.45, 0.46), (-2.92, 0.92), (-2.98, 1.00), (-2.10, DECK), (2.70, DECK), (2.92, 1.32),
                     (2.95, 0.82), (2.60, 0.46)])
    m.merge(sh.extrude_x(side, half_width=HULL_W, bevel=((0.03, 0.0), (0.0, 0.03)), material="armor"))
    # fender shelves over the tracks, the front mudguards turned down
    for sx in (-1, 1):
        x0, x1 = sx * HULL_W, sx * (TRACK_X + TRACK_W / 2 + 0.04)
        shelf = np.array([(min(x0, x1), -2.95), (max(x0, x1), -2.95), (max(x0, x1), 2.92), (min(x0, x1), 2.92)])
        m.merge(vp.plate_xz(shelf, DECK - 0.24, DECK - 0.22, "armor", 0.004))
        m.merge(vp.oriented_box((sx * (TRACK_X + 0.0), DECK - 0.36, -3.02), (TRACK_W / 2 + 0.04, 0.14, 0.006), X,
                                vp.unit((0, 1, -0.4)), "armor", 0.004))
        # side skirts: six bolted panels over the upper run
        for k in range(6):
            z0, z1 = -2.62 + k * 0.89, -2.62 + (k + 1) * 0.89 - 0.025
            c = np.array([sx * (TRACK_X + TRACK_W / 2 + 0.05), 0.92, (z0 + z1) / 2])
            m.merge(vp.box(c, (0.018, 0.30, (z1 - z0) / 2), "armor", 0.008))
            for dz in (-0.33, 0.0, 0.33):
                for dy in (0.22, -0.22):
                    p = c + (sx * 0.02, dy, dz)
                    m.merge(vp.cyl(p, p + X * sx * 0.012, 0.014, 6, "armor", soft=False))
        # stowage bins on the shelves
        for z0, z1 in ((0.55, 1.45), (1.60, 2.55)):
            c = np.array([sx * (HULL_W + 0.27), DECK - 0.04, (z0 + z1) / 2])
            m.merge(vp.box(c, (0.22, 0.17, (z1 - z0) / 2), "armor", 0.02))
            m.merge(vp.box(c + (0, 0.172, 0), (0.20, 0.006, (z1 - z0) / 2 - 0.03), "armor", 0.004))
            for dz in (-0.25, 0.25):
                m.merge(vp.box(c + (sx * 0.222, 0.06, dz), (0.006, 0.03, 0.04), "metal", 0.004))
    # glacis details: the driver's hatch with periscopes, tow hooks, headlamps in guards, the front towing eyes
    hatch = np.array([-0.42, DECK + 0.01, -2.05])
    m.merge(vp.lathe([(0.0, 0.0), (0.30, 0.0), (0.31, 0.03), (0.28, 0.06), (0.0, 0.065)], 36, "armor", axis="y",
                     center=hatch))
    for dx in (-0.16, 0.0, 0.16):
        p = hatch + (dx, 0.02, -0.30)
        m.merge(vp.box(p, (0.06, 0.04, 0.03), "armor", 0.008))
        m.merge(vp.box(p + (0, 0.0, -0.032), (0.05, 0.025, 0.004), "lens", 0.002))
    for sx in (-1, 1):
        hl = np.array([sx * (HULL_W - 0.12), DECK - 0.12, -2.72])
        m.merge(vp.lathe([(0.0, 0.0), (0.08, 0.0), (0.085, 0.06), (0.0, 0.08)], 24, "armor", axis="z", center=hl))
        m.merge(vp.lathe([(0.0, -0.005), (0.07, -0.003), (0.07, 0.002), (0.0, 0.002)], 24, "headlight", axis="z",
                         center=hl))
        guard = vp.bend([hl + (-0.12, -0.08, 0.02), hl + (-0.12, 0.11, -0.10), hl + (0.12, 0.11, -0.10),
                         hl + (0.12, -0.08, 0.02)], 0.05)
        m.merge(vp.tube(guard, 0.012, 10, "armor"))
        hook = np.array([sx * 0.62, 0.62, -2.62])
        m.merge(vp.tube(vp.bend([hook + (0, 0.08, 0.05), hook + (0, 0.0, -0.10), hook + (0, -0.12, -0.02)], 0.06),
                        0.035, 12, "metal"))
        # rear: tail lamps, exhaust grilles, towing pintle
        tl = np.array([sx * (HULL_W - 0.10), 1.20, 2.94])
        m.merge(vp.box(tl, (0.07, 0.05, 0.02), "armor", 0.006))
        m.merge(vp.box(tl + (0, 0, 0.018), (0.05, 0.03, 0.006), "taillight", 0.003))
        ex = np.array([sx * 0.55, 1.02, 2.94])
        m.merge(vp.box(ex, (0.26, 0.16, 0.02), "frame", 0.01))
        for k in range(7):
            m.merge(vp.box(ex + (0, -0.12 + k * 0.04, 0.022), (0.24, 0.008, 0.008), "frame", rounding=False))
    # engine deck: grilles and access panels behind the turret
    for z0 in (1.65, 2.15):
        c = np.array([0.0, DECK + 0.012, z0])
        m.merge(vp.box(c, (0.85, 0.012, 0.22), "frame", 0.006))
        for k in range(11):
            m.merge(vp.box(c + (-0.75 + k * 0.15, 0.014, 0), (0.05, 0.006, 0.20), "frame", rounding=False))
    for x in (-0.65, 0.65):
        m.merge(vp.box((x, DECK + 0.01, 0.90), (0.32, 0.01, 0.25), "armor", 0.005))
    # jerrycans and the unditching beam on the rear
    for x in (-0.80, -0.55):
        m.merge(vp.box((x, DECK + 0.20, 2.75), (0.085, 0.20, 0.16), "armor", 0.02))
    m.merge(vp.cyl((-0.95, DECK + 0.10, 2.88), (0.95, DECK + 0.10, 2.88), 0.07, 16, "frame"))
    return m


# ---------------------------------------------------------------------------------------------- turret
def _turret():
    """In vehicle space (the turret node pivots about the vertical through TURRET)."""
    m = Mesh()
    t = TURRET
    # turret ring collar
    m.merge(vp.lathe([(0.0, 0.0), (1.05, 0.0), (1.08, 0.05), (1.02, 0.09), (0.0, 0.09)], 48, "armor", axis="y",
                     center=t))
    # faceted armour: a wedge front, flat sloped sides, the bustle at the back
    plan = np.array([(-0.30, -1.25), (0.30, -1.25), (1.02, -0.70), (1.12, 0.25), (1.05, 0.95), (0.80, 1.55),
                     (-0.80, 1.55), (-1.05, 0.95), (-1.12, 0.25), (-1.02, -0.70)])
    # welded armour: flat faces with bevelled edges (a 30 mm bevel on every corner of the plan, so the big faces keep
    # their own normals and the bevels carry the turn)
    plan = _bevel(sh.ccw(plan), 0.03)

    def ring(y, s):
        return [(x * s, y, t[2] + z * s + (1 - s) * 0.10) for x, z in plan]
    rings = [ring(DECK + 0.08, 1.0), ring(DECK + 0.38, 1.0), ring(DECK + 0.72, 0.90)]
    body = sh.loft(np.array(rings), "armor", rounding=False)
    sh.orient_outward(body)
    m.merge(body)
    # add-on armour plates on the front faces (bolted, a hair proud)
    for sx in (-1, 1):
        a = np.array([sx * 0.32, DECK + 0.16, t[2] - 1.27])
        b = np.array([sx * 1.00, DECK + 0.16, t[2] - 0.74])
        d = vp.unit(b - a)
        nrm = vp.unit(np.cross(Y, d)) * (-sx)
        c = (a + b) / 2 + Y * 0.20 + nrm * 0.03
        m.merge(vp.oriented_box(c, (np.linalg.norm(b - a) / 2 - 0.04, 0.20, 0.03), d, Y, "armor", 0.01))
        for u in (-0.3, 0.0, 0.3):
            for dy in (-0.12, 0.12):
                p = c + d * u + Y * dy + nrm * 0.03
                m.merge(vp.cyl(p, p + nrm * 0.012, 0.016, 6, "armor", soft=False))
    # mantlet with its canvas cover, between the cheeks
    mc = TRUNNION + np.array([0.0, -0.02, -0.20])
    m.merge(vp.box(mc + (0, 0, 0.12), (0.30, 0.24, 0.16), "armor", 0.03))
    # gunner's sight box on the roof (left front), its window
    sb = np.array([-0.62, DECK + 0.80, t[2] - 0.55])
    m.merge(vp.box(sb, (0.15, 0.13, 0.22), "armor", 0.02))
    m.merge(vp.box(sb + (0, 0.02, -0.222), (0.11, 0.08, 0.004), "lens", 0.004))
    m.merge(vp.box(sb + (0, 0.14, -0.02), (0.12, 0.012, 0.18), "armor", 0.004))
    # gunner's hatch (left), the commander's cupola (right) with periscopes round it
    m.merge(vp.lathe([(0.0, 0.0), (0.30, 0.0), (0.31, 0.03), (0.27, 0.07), (0.0, 0.075)], 36, "armor", axis="y",
                     center=(-0.45, DECK + 0.72, t[2] + 0.35)))
    m.merge(vp.lathe([(0.32, 0.0), (0.42, 0.0), (0.43, 0.10), (0.40, 0.18), (0.32, 0.18), (0.32, 0.0)], 40, "armor",
                     axis="y", center=CUPOLA - Y * 0.15))
    for a in np.linspace(0, 2 * np.pi, 7, endpoint=False):
        d = np.array([np.sin(a), 0.0, -np.cos(a)])
        p = CUPOLA - Y * 0.05 + d * 0.42
        m.merge(vp.oriented_box(p, (0.06, 0.035, 0.012), np.cross(Y, d), Y, "armor", 0.006))
        m.merge(vp.oriented_box(p + d * 0.013, (0.045, 0.022, 0.002), np.cross(Y, d), Y, "lens", rounding=False))
    # open hatch lid standing up behind the cupola
    m.merge(vp.oriented_box(CUPOLA + (0, 0.20, 0.42), (0.30, 0.30, 0.03), X, vp.unit((0, 1, 0.25)), "armor", 0.02))
    # smoke dischargers on the cheeks: four tubes a side
    for sx in (-1, 1):
        base = np.array([sx * 0.95, DECK + 0.48, t[2] - 0.55])
        for k in range(4):
            p = base + (0, 0.0, -0.08 * k) + (sx * 0.02 * k, 0.0, 0.0)
            d = vp.unit((sx * 0.35, 0.55, -0.75))
            m.merge(vp.cyl(p, p + d * 0.22, 0.042, 14, "armor"))
            m.merge(vp.cyl(p + d * 0.22, p + d * 0.23, 0.036, 14, "rubber"))
    # bustle stowage basket and two antenna bases with whips
    bz = t[2] + 1.58
    for y in (DECK + 0.20, DECK + 0.55):
        m.merge(vp.tube(vp.bend([(-0.85, y, bz - 0.2), (-0.88, y, bz + 0.30), (0.88, y, bz + 0.30), (0.85, y, bz - 0.2)],
                                0.08), 0.014, 8, "armor"))
    for x in np.linspace(-0.8, 0.8, 7):
        m.merge(vp.cyl((x, DECK + 0.10, bz + 0.30), (x, DECK + 0.56, bz + 0.30), 0.011, 8, "armor"))
    m.merge(vp.box((0.0, DECK + 0.32, bz + 0.12), (0.70, 0.18, 0.14), "fabric", 0.05))
    for x in (-0.85, 0.85):
        base = np.array([x, DECK + 0.72, t[2] + 1.25])
        m.merge(vp.cyl(base, base + Y * 0.08, 0.05, 14, "frame"))
        m.merge(vp.tube([base + Y * 0.08, base + Y * 1.4 + Z * 0.15, base + Y * 2.3 + Z * 0.45], [0.010, 0.006, 0.003],
                        6, "frame"))
    return m


def _bevel(loop, d):
    """Every corner of a 2D loop cut back by d along both of its edges."""
    out = []
    n = len(loop)
    for i in range(n):
        p, a, b = loop[i], loop[i - 1], loop[(i + 1) % n]
        out.append(p + vp.unit(np.append(a - p, 0))[:2] * d)
        out.append(p + vp.unit(np.append(b - p, 0))[:2] * d)
    return np.array(out)


def _cannon():
    """In vehicle space; pitches about X through TRUNNION. The barrel along -Z."""
    m = Mesh()
    z0 = TRUNNION[2]
    # canvas mantlet cover: a bellows of rings round the barrel root
    m.merge(vp.lathe([(0.0, 0.0), (0.24, 0.0), (0.26, 0.06), (0.22, 0.12), (0.25, 0.18), (0.21, 0.24), (0.18, 0.30),
                      (0.12, 0.34), (0.0, 0.34)], 32, "fabric", axis="z").transformed(_at(TRUNNION + Z * -0.40, flip=True)))
    m.merge(vp.box(TRUNNION + (0, 0, -0.18), (0.24, 0.22, 0.12), "armor", 0.03))
    # barrel: thermal sleeve sections with clamps, the fume extractor, the muzzle and its reference sensor
    prof = [(0.0, 0.0), (0.105, 0.0), (0.105, 0.55)]
    z = 0.55
    for k in range(3):
        prof += [(0.110, z), (0.110, z + 0.05), (0.098, z + 0.06), (0.098, z + 0.70), (0.104, z + 0.71)]
        z += 0.71
    prof += [(0.104, z), (0.150, z + 0.10), (0.150, z + 0.48), (0.098, z + 0.58)]
    z += 0.58
    prof += [(0.090, z + 0.0), (0.090, BARREL - 0.18), (0.098, BARREL - 0.16), (0.098, BARREL - 0.02),
             (0.084, BARREL), (0.0, BARREL)]
    barrel = sh.revolve(prof, 32, "armor")
    sh.orient_outward(barrel)
    m.merge(barrel.transformed(_at(TRUNNION + Z * -0.30, flip=True)))
    m.merge(vp.lathe([(0.0, 0.0), (0.040, 0.0), (0.040, 0.012), (0.0, 0.012)], 16, "lens", axis="z").transformed(
        _at(TRUNNION + Z * -(0.30 + BARREL - 0.04) + Y * 0.115, flip=True)))
    m.merge(vp.box(TRUNNION + Z * -(0.30 + BARREL - 0.04) + Y * 0.105, (0.03, 0.02, 0.04), "armor", 0.006))
    muzzle = TRUNNION + Z * -(0.30 + BARREL)
    return m, muzzle


def _at(origin, flip=False):
    """A frame at origin whose +Z points along -Z of the vehicle (flip) or +Z."""
    M = np.eye(4)
    if flip:
        M[:3, 0], M[:3, 1], M[:3, 2] = -X, Y, -Z
    M[:3, 3] = origin
    return M


def _mg():
    """The roof machine gun with its shield; pivots (yaw and pitch) at MG_PIVOT, firing along -Z."""
    m = Mesh()
    p = MG_PIVOT
    m.merge(vp.cyl(p - Y * 0.16, p, 0.035, 14, "gunmetal"))                    # pintle
    m.merge(vp.box(p + (0, 0.05, 0.06), (0.05, 0.06, 0.24), "gunmetal", 0.01))   # receiver
    m.merge(vp.box(p + (0, 0.115, 0.04), (0.045, 0.008, 0.16), "gunmetal", 0.004))  # feed cover
    m.merge(vp.box(p + (-0.10, 0.02, 0.04), (0.05, 0.07, 0.09), "armor", 0.01))  # ammo can
    barrel = vp.lathe([(0.0, 0.0), (0.030, 0.0), (0.030, 0.30), (0.020, 0.32), (0.016, 0.85), (0.024, 0.86),
                       (0.024, 0.95), (0.0, 0.95)], 16, "gunmetal", axis="z").transformed(_at(p + (0, 0.05, -0.18),
                                                                                               flip=True))
    m.merge(barrel)
    for k in range(8):                                                           # cooling holes on the jacket
        q = p + (0, 0.05 + 0.031, -0.22 - k * 0.032)
        m.merge(vp.cyl(q, q + Y * 0.002, 0.007, 8, "frame", soft=False))
    for sx in (-1, 1):                                                           # spade grips
        m.merge(vp.tube(vp.bend([p + (sx * 0.04, 0.05, 0.30), p + (sx * 0.08, 0.06, 0.36), p + (sx * 0.08, -0.06, 0.36)],
                                0.02), 0.012, 8, "rubber"))
    # gun shield: a bent armour plate (a closed solid 12 mm thick)
    shield = []
    for a in np.linspace(-0.9, 0.9, 9):
        d = np.array([np.sin(a), 0.0, -np.cos(a)])
        shield.append([p + d * 0.36 + Y * -0.12, p + d * 0.36 + Y * 0.30, p + d * 0.372 + Y * 0.30,
                       p + d * 0.372 + Y * -0.12])
    m.merge(sh.loft(np.array(shield), "armor", rounding=False))
    muzzle = p + (0, 0.05, -1.13)
    return m, muzzle


def build(rig):
    rig.add("body", _hull())
    v_per_m = gv.TRACK_V_PER_METRE
    for side, sx in (("l", -1), ("r", 1)):
        x = sx * TRACK_X
        path = track_path() + np.array([x, 0, 0])
        rig.node(f"track_{side}", "body", (x, WHEEL_Y, 0.0))
        rig.add(f"track_{side}", vp.track_band(path, TRACK_W, TRACK_T, 0.17, "track", v_per_m, horn_mat="track",
                                               horn=(0.035, 0.085, 0.05)))
        for i, z in enumerate(WHEEL_Z):
            name = f"roadwheel_{side}{i}"
            rig.node(name, "body", (x, WHEEL_Y, z))
            rig.add(name, road_wheel().translated((x, WHEEL_Y, z)))
        zs, ys, rs = SPROCKET
        rig.node(f"sprocket_{side}", "body", (x, ys, zs))
        rig.add(f"sprocket_{side}", sprocket(rs).translated((x, ys, zs)))
        zi, yi, ri = IDLER
        rig.node(f"idler_{side}", "body", (x, yi, zi))
        rig.add(f"idler_{side}", idler(ri).translated((x, yi, zi)))
        for rz, ry in ROLLERS:                                             # return rollers turn too, but small: body
            rig.add("body", roller().translated((x, ry, rz)))
        # suspension arms from the hull to each road wheel hub
        for z in WHEEL_Z:
            rig.add("body", vp.oriented_box((sx * (HULL_W + 0.08), WHEEL_Y + 0.12, z + 0.16), (0.07, 0.05, 0.20), X,
                                            vp.unit((0, 0.5, 1)), "armor", 0.015))
    rig.node("turret", "body", TURRET)
    rig.add("turret", _turret())
    rig.node("cannon", "turret", TRUNNION)
    cannon, muzzle = _cannon()
    rig.add("cannon", cannon)
    rig.node("muzzle_cannon", "cannon", muzzle)
    rig.node("mg", "turret", MG_PIVOT)
    mg, mg_muzzle = _mg()
    rig.add("mg", mg)
    rig.node("muzzle_mg", "mg", mg_muzzle)
    for i, (p, parent) in enumerate(SEATS):
        rig.node(f"seat_{i}", parent, p)
    for i, sx in enumerate((-1, 1)):
        rig.node(f"headlight_{i}", "body", (sx * (HULL_W - 0.12), DECK - 0.12, -2.80))
        rig.node(f"taillight_{i}", "body", (sx * (HULL_W - 0.10), 1.20, 2.97))
        rig.node(f"exhaust_{i}", "body", (sx * 0.55, 1.02, 2.98))
