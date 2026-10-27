"""Off-road 4x4 (越野车, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original open-top utility 4x4, left-hand
drive. Vehicle space: metres, +Y up, the front towards -Z (its right is +X), the origin on the ground under the middle
of the wheelbase. Modelled at a real 4x4's proportions (2.50 m wheelbase) and built SCALE times larger: a seated
Minecraft player reaches about 1.2 m over the hips (its head is large), so at real size heads stood through the roll
cage; the cage also stands a little taller over the seats than a real one.

A boxy steel tub: flat side panels with the wheel arches cut out and plastic flares over them, a crowned bonnet with a
slotted grille and round headlamps, a tube bull bar with a winch, a folding windshield, a roll cage over all four
seats; inside, four bucket seats, a dashboard with gauges, the steering wheel, a floor of ribbed rubber mats and the
gear and transfer levers; behind, a cargo bed, the spare wheel on the tailgate and two jerrycans. Underneath, ladder
frame rails, live axles with their differentials, shocks, the fuel tank and the exhaust. Mud tyres on steel wheels."""
import numpy as np

import shapes as sh
import vehicle_parts as vp
from meshkit import Mesh
from vehicle_parts import X, Y, Z

WB, TRACK, R, TW, RIM = 2.50, 1.56, 0.40, 0.28, 0.235
AXLE_Y = R
WHEELS = {"fl": (-TRACK / 2, -WB / 2), "fr": (TRACK / 2, -WB / 2), "rl": (-TRACK / 2, WB / 2), "rr": (TRACK / 2, WB / 2)}
BODY_W = 0.88                       # half width of the tub's sides
SILL, BELT = 0.56, 1.06             # bottom of the sides, the beltline (top of the sides)
FRONT, REAR = -2.00, 1.98           # the grille face, the tailgate
SCREEN_Z = -0.56                    # windshield base
FLOOR = 0.64
SEATS = [(-0.40, 0.02), (0.40, 0.02), (-0.40, 0.88), (0.40, 0.88)]      # driver, passenger, rear left, rear right
HIP = 0.98
SEGS = 18
#: the whole vehicle is built this much larger than the constants above (see the module comment)
SCALE = 1.25
#: the roll cage's top over the ground (before SCALE): 1.35 m over the hips once scaled, a seated player's head ~0.15 m
#: under the top rail
CAGE_TOP = 2.06


def plate_z(outline_xy, half, z, mat, holes=(), rounding=True):
    """A plate in the (x, y) plane at z, thickness 2 half: an (x, y) outline (and holes) extruded along Z."""
    flip = lambda p: np.stack([-np.asarray(p, float)[:, 0], np.asarray(p, float)[:, 1]], 1)
    m = sh.extrude_x(flip(outline_xy), [flip(h) for h in holes], half_width=half, material=mat, x_center=0.0,
                     rounding=rounding)
    return sh.place(m, origin=(0, 0, z), x_axis=Z, y_axis=Y, z_axis=-X)


def arch(zc, r, n=16):
    """Points of a wheel arch's cut-out (z, y), from its rear foot over the top to its front foot."""
    return [(zc + r * np.cos(a), AXLE_Y + r * np.sin(a)) for a in np.linspace(np.radians(-8), np.radians(188), n)]


# -------------------------------------------------------------------------------------------- wheels
def wheel(side):
    """A wheel about X through the origin; its outer face towards side * X."""
    m = vp.merged(
        vp.tyre(R, TW, RIM + 0.012, "tire", segs=64, shoulder=0.42, bulge=0.06),
        vp.lugs(R - 0.004, TW * 0.94, 24, "tire", rows=2, depth=0.016, length=0.075, gap=0.016),
        vp.lathe([(RIM - 0.03, -TW * 0.40), (RIM + 0.012, -TW * 0.40), (RIM + 0.012, -TW * 0.34), (RIM - 0.004, -TW * 0.30),
                  (RIM - 0.004, TW * 0.30), (RIM + 0.012, TW * 0.34), (RIM + 0.012, TW * 0.40), (RIM - 0.03, TW * 0.40),
                  (RIM - 0.03, -TW * 0.40)], 48, "paint_accent"),
    )
    face_x = side * TW * 0.26
    m.merge(vp.rim_face(RIM - 0.006, 8, 0.026, 0.165, 0.007, "paint_accent", x=face_x, centre_r=0.0))
    # hub, the beadlock ring and its bolts, wheel nuts, centre cap
    m.merge(vp.lathe([(0.0, 0.0), (0.095, 0.0), (0.10, 0.02), (0.085, 0.045), (0.0, 0.05)], 32, "paint_accent",
                     center=(face_x, 0, 0)) if side > 0 else
            vp.mirror_x(vp.lathe([(0.0, 0.0), (0.095, 0.0), (0.10, 0.02), (0.085, 0.045), (0.0, 0.05)], 32,
                                 "paint_accent", center=(-face_x, 0, 0))))
    ring_x = side * TW * 0.40
    m.merge(vp.lathe([(RIM - 0.005, -0.006), (RIM + 0.030, -0.006), (RIM + 0.030, 0.006), (RIM - 0.005, 0.006),
                      (RIM - 0.005, -0.006)], 48, "metal", center=(ring_x, 0, 0)))
    m.merge(vp.nuts(16, RIM + 0.014, 0.006, 0.008, "metal", ring_x + side * 0.006, side))
    m.merge(vp.nuts(6, 0.075, 0.011, 0.016, "metal", face_x + side * 0.046, side))
    return m


def _wheel_at(side, x, z):
    return wheel(side).translated((x, AXLE_Y, z))


# ---------------------------------------------------------------------------------------------- body
def _sides():
    m = Mesh()
    for sx in (-1, 1):
        out = [(FRONT + 0.06, BELT + 0.05), (FRONT + 0.02, SILL + 0.08), (FRONT + 0.10, SILL)]
        out += [(z, max(y, SILL)) for z, y in reversed(arch(-WB / 2, R + 0.08))]
        out += [(-WB / 2 + R + 0.12, SILL), (WB / 2 - R - 0.12, SILL)]
        out += [(z, max(y, SILL)) for z, y in reversed(arch(WB / 2, R + 0.08))]
        out += [(REAR, SILL), (REAR, BELT), (SCREEN_Z + 0.70, BELT), (SCREEN_Z + 0.55, BELT - 0.14),
                (SCREEN_Z - 0.10, BELT - 0.14), (SCREEN_Z - 0.20, BELT + 0.05)]
        m.merge(vp.plate(np.array(out), 0.012, "paint", x=sx * BODY_W))
        # panel seams of the door openings and the rocker strip
        for z0, z1 in ((SCREEN_Z + 0.02, SCREEN_Z + 0.03), (0.52, 0.53)):
            m.merge(vp.box((sx * (BODY_W + 0.012), (SILL + BELT) / 2 - 0.02, (z0 + z1) / 2), (0.002, 0.20, 0.004),
                           "paint_accent", 0.001))
        m.merge(vp.box((sx * (BODY_W + 0.02), SILL + 0.03, 0.0), (0.02, 0.035, WB / 2 - R - 0.14), "plastic", 0.01))
        # fender flares over the arches
        for zc in (-WB / 2, WB / 2):
            pts = arch(zc, R + 0.085, 20)
            ring = []
            for z, y in pts:
                d = vp.unit((0.0, y - AXLE_Y, z - zc))
                base = np.array([sx * (BODY_W + 0.01), y, z])
                ring.append([base, base + d * 0.06 + X * sx * 0.0, base + d * 0.06 + X * sx * 0.11,
                             base + X * sx * 0.11 - d * 0.0, base + X * sx * 0.12 - d * 0.015])
            ring = np.array(ring)                                           # (n, 5, 3)
            m.merge(sh.loft(ring.transpose(0, 1, 2), "plastic", rounding=False, closed=True))
            for k in range(0, 20, 4):                                       # flare bolts
                z, y = pts[k + 1]
                d = vp.unit((0.0, y - AXLE_Y, z - zc))
                c = np.array([sx * (BODY_W + 0.11), y, z]) + d * 0.03
                m.merge(vp.cyl(c, c + X * sx * 0.012, 0.0065, 6, "metal", soft=False))
    return m


def _bonnet_front():
    m = Mesh()
    # crowned bonnet from the windshield to the grille, a raised centre bulge
    st = []
    for z, top in ((SCREEN_Z, 1.14), (-1.10, 1.135), (-1.70, 1.12), (FRONT + 0.02, 1.10)):
        st.append((z, 0.0, 0.0, BODY_W + 0.005, top, BELT - 0.02, 6.0, 0.9, 0.95))
    m.merge(vp.shell(st, "paint", count=36, axis=Z, round0=0.02, round1=0.03))
    m.merge(vp.shell([(SCREEN_Z - 0.05, 0, 0, 0.36, 1.165, 1.10, 4.0, 0.8, 0.9),
                      (FRONT + 0.20, 0, 0, 0.30, 1.150, 1.10, 4.0, 0.8, 0.9)], "paint", count=28, round0=0.03, round1=0.05))
    # bonnet latches and the hinge line at the screen
    for sx in (-1, 1):
        m.merge(vp.box((sx * 0.70, 1.12, FRONT + 0.12), (0.03, 0.012, 0.035), "rubber", 0.006))
    m.merge(vp.cyl((-0.80, 1.145, SCREEN_Z + 0.02), (0.80, 1.145, SCREEN_Z + 0.02), 0.010, 12, "paint_accent"))
    # grille: a plate with seven tall slots, round headlamps either side
    grille = [(-BODY_W, SILL + 0.10), (BODY_W, SILL + 0.10), (BODY_W, BELT + 0.04), (-BODY_W, BELT + 0.04)]
    slots = [sh.rounded_rect(x - 0.035, 0.70, x + 0.035, 1.04, 0.03, 4) for x in np.linspace(-0.30, 0.30, 7)]
    lamps = [sh.circle(sx * 0.62, 0.93, 0.105, 32) for sx in (-1, 1)]
    m.merge(plate_z(grille, 0.012, FRONT, "paint", holes=slots + lamps))
    m.merge(plate_z([(-0.40, 0.68), (0.40, 0.68), (0.40, 1.06), (-0.40, 1.06)], 0.004, FRONT + 0.05, "paint_accent",
                    rounding=False))
    for sx in (-1, 1):
        c = np.array([sx * 0.62, 0.93, FRONT + 0.02])
        m.merge(vp.lathe([(0.0, 0.0), (0.105, 0.0), (0.112, -0.012), (0.106, -0.022), (0.0, -0.022)], 36, "chrome",
                         axis="z", center=c))
        m.merge(vp.lathe([(0.0, -0.030), (0.092, -0.022), (0.094, -0.016), (0.0, -0.016)], 36, "headlight", axis="z",
                         center=c))
        m.merge(vp.lathe([(0.0, 0.0), (0.10, 0.0), (0.10, 0.12), (0.0, 0.14)], 28, "paint_accent", axis="z",
                         center=c + (0, 0, 0.0)))
        # turn signal under each headlamp
        m.merge(vp.box((sx * 0.62, 0.76, FRONT - 0.012), (0.045, 0.022, 0.01), "indicator", 0.006))
    return m


def _bull_bar():
    m = Mesh()
    z = FRONT - 0.16
    # bumper beam with the winch, the hoop of the bull bar, tow hooks
    m.merge(vp.box((0.0, 0.66, z), (0.95, 0.075, 0.07), "frame", 0.02))
    m.merge(vp.box((0.0, 0.70, z - 0.05), (0.26, 0.10, 0.07), "frame", 0.02))
    m.merge(vp.cyl((-0.20, 0.71, z - 0.12), (0.20, 0.71, z - 0.12), 0.065, 24, "metal"))
    m.merge(vp.cyl((-0.20, 0.71, z - 0.12), (-0.24, 0.71, z - 0.12), 0.085, 24, "frame"))
    m.merge(vp.cyl((0.20, 0.71, z - 0.12), (0.24, 0.71, z - 0.12), 0.085, 24, "frame"))
    hoop = vp.bend([(-0.62, 0.74, z), (-0.58, 1.06, z - 0.08), (0.58, 1.06, z - 0.08), (0.62, 0.74, z)], 0.12)
    m.merge(vp.tube(hoop, 0.030, SEGS, "frame"))
    for sx in (-1, 1):
        m.merge(vp.tube([(sx * 0.30, 0.74, z), (sx * 0.30, 1.04, z - 0.075)], 0.026, SEGS, "frame"))
        hook = vp.bend([(sx * 0.55, 0.62, z - 0.06), (sx * 0.55, 0.58, z - 0.16), (sx * 0.55, 0.66, z - 0.20)], 0.03)
        m.merge(vp.tube(hook, 0.016, 12, "paint"))
    # skid plate under the front
    m.merge(vp.plate_xz(np.array([(-0.55, FRONT - 0.10), (0.55, FRONT - 0.10), (0.60, FRONT + 0.60),
                                  (-0.60, FRONT + 0.60)]), 0.36, 0.372, "metal", 0.004))
    return m


def _windshield():
    m = Mesh()
    lean = np.radians(16)
    up = np.array([0.0, np.cos(lean), np.sin(lean)])
    base = np.array([0.0, 1.15, SCREEN_Z])
    h = 0.52
    top = base + up * h
    # frame: two posts, the top and bottom rails, the glass
    for sx in (-1, 1):
        m.merge(vp.oriented_box((base + top) / 2 + X * sx * 0.82, (0.028, h / 2 + 0.02, 0.022), X, up, "paint", 0.008))
    m.merge(vp.oriented_box(top + X * 0.0, (0.85, 0.028, 0.024), X, up, "paint", 0.008))
    m.merge(vp.oriented_box(base + up * 0.02, (0.85, 0.03, 0.03), X, up, "paint", 0.008))
    glass = vp.oriented_box((base + top) / 2 + up * 0.01, (0.79, h / 2 - 0.035, 0.004), X, up, "glass", rounding=False)
    m.merge(glass)
    # wipers parked at the base, the rear-view mirror, the side mirrors on the posts
    for x in (-0.45, 0.10):
        p = base + up * 0.07 + Z * -0.03 + X * x
        m.merge(vp.oriented_box(p + X * 0.18, (0.20, 0.006, 0.004), X, up, "rubber", rounding=False))
    m.merge(vp.oriented_box(top - up * 0.07 + Z * 0.05, (0.11, 0.035, 0.012), X, up, "plastic", 0.006))
    for sx in (-1, 1):
        p = base + up * 0.18 + X * sx * 0.86
        arm = [p, p + X * sx * 0.16 + up * 0.04]
        m.merge(vp.tube(arm, 0.009, 10, "frame"))
        m.merge(vp.oriented_box(arm[1] + X * sx * 0.06, (0.075, 0.055, 0.015), X, Y, "plastic", 0.012))
        m.merge(vp.oriented_box(arm[1] + X * sx * 0.06 + Z * 0.016, (0.068, 0.048, 0.002), X, Y, "chrome",
                                rounding=False))
    return m, top


def _cage(screen_top):
    m = Mesh()
    yt = CAGE_TOP
    for zc in (0.20, 1.50):                                         # main hoops behind each seat row
        hoop = vp.bend([(-BODY_W + 0.05, BELT, zc), (-BODY_W + 0.10, yt, zc), (BODY_W - 0.10, yt, zc),
                        (BODY_W - 0.05, BELT, zc)], 0.16)
        m.merge(vp.tube(hoop, 0.030, SEGS, "frame"))
    for sx in (-1, 1):
        rail = vp.bend([screen_top + X * sx * 0.80, (sx * (BODY_W - 0.10), yt, 0.20), (sx * (BODY_W - 0.10), yt, 1.50),
                        (sx * (BODY_W - 0.06), BELT + 0.10, REAR - 0.06)], 0.14)
        m.merge(vp.tube(rail, 0.026, SEGS, "frame"))
        m.merge(vp.tube([(sx * (BODY_W - 0.08), yt - 0.02, 0.22), (sx * (BODY_W - 0.06), BELT + 0.02, 0.90)], 0.022,
                        SEGS, "frame"))
        # grab handles on the hoops (shallow: they hang over the front seats' heads)
        m.merge(vp.tube(vp.bend([(sx * 0.72, yt - 0.03, 0.20), (sx * 0.72, yt - 0.08, 0.17), (sx * 0.56, yt - 0.08, 0.17),
                                 (sx * 0.56, yt - 0.03, 0.20)], 0.03), 0.012, 10, "rubber"))
    m.merge(vp.tube([(-0.75, yt - 0.02, 0.20), (0.75, yt - 0.02, 1.50)], 0.022, SEGS, "frame"))
    return m


def _seat(x, z):
    """A bucket seat facing -Z with its frame on the floor."""
    m = Mesh()
    cushion = vp.shell([(z - 0.22, x, FLOOR + 0.24, 0.23, 0.10, -0.06, 4.0, 0.6, 0.8),
                        (z + 0.02, x, FLOOR + 0.25, 0.25, 0.10, -0.06, 4.0, 0.6, 0.8),
                        (z + 0.22, x, FLOOR + 0.24, 0.24, 0.09, -0.06, 4.0, 0.6, 0.8)], "seat", count=32,
                       round0=0.04, round1=0.03)
    m.merge(cushion)
    back_axis = vp.unit((0.0, 1.0, 0.22))
    back = vp.shell([(0.00, 0, 0, 0.25, 0.07, -0.07, 4.0, 0.7, 0.7), (0.35, 0, 0, 0.24, 0.08, -0.06, 4.0, 0.7, 0.7),
                     (0.62, 0, 0, 0.19, 0.07, -0.05, 4.0, 0.7, 0.7)], "seat", count=32, axis=back_axis,
                    up=np.cross(back_axis, X), round0=0.03, round1=0.06)
    m.merge(back.translated((x, FLOOR + 0.30, z + 0.24)))
    for sx in (-1, 1):                                               # side bolsters
        m.merge(vp.shell([(z - 0.20, x + sx * 0.21, FLOOR + 0.33, 0.04, 0.04, -0.04, 2.6, 0, 0),
                          (z + 0.20, x + sx * 0.22, FLOOR + 0.34, 0.045, 0.045, -0.04, 2.6, 0, 0)], "seat", count=20,
                         round0=0.03, round1=0.03))
    m.merge(vp.box((x, FLOOR + 0.08, z), (0.18, 0.08, 0.20), "frame", 0.01))
    for sx in (-1, 1):
        m.merge(vp.box((x + sx * 0.16, FLOOR + 0.01, z), (0.015, 0.012, 0.26), "metal", 0.004))
    return m


def _interior():
    m = Mesh()
    # floor with ribbed mats, the transmission tunnel
    m.merge(vp.plate_xz(np.array([(-BODY_W, SCREEN_Z - 0.05), (BODY_W, SCREEN_Z - 0.05), (BODY_W, REAR - 0.02),
                                  (-BODY_W, REAR - 0.02)]), FLOOR - 0.03, FLOOR, "metal", 0.003))
    for x0 in (-0.62, 0.18):
        for z0, z1 in ((SCREEN_Z, 0.40), (0.55, 1.20)):
            m.merge(vp.plate_xz(np.array([(x0, z0), (x0 + 0.44, z0), (x0 + 0.44, z1), (x0, z1)]), FLOOR, FLOOR + 0.008,
                                "rubber", 0.003))
            for z in np.arange(z0 + 0.04, z1, 0.05):
                m.merge(vp.box((x0 + 0.22, FLOOR + 0.010, z), (0.20, 0.003, 0.006), "rubber", rounding=False))
    m.merge(vp.shell([(SCREEN_Z, 0, FLOOR, 0.14, 0.18, 0.0, 3.0, 0.6, 1.0), (0.60, 0, FLOOR, 0.13, 0.14, 0.0, 3.0, 0.6, 1.0)],
                     "plastic", count=24, round0=0.05, round1=0.05))
    # dashboard: a padded beam with the instrument binnacle and the passenger grab bar
    m.merge(vp.shell([(-BODY_W + 0.02, 0, 0, 0.11, 0.08, -0.12, 3.5, 0.6, 0.5), (BODY_W - 0.02, 0, 0, 0.11, 0.08, -0.12, 3.5, 0.6, 0.5)],
                     "plastic", count=28, axis=X, up=Y, round0=0.02, round1=0.02).translated((0, BELT - 0.02, SCREEN_Z + 0.06)))
    bin_c = np.array([-0.40, BELT + 0.05, SCREEN_Z + 0.06])
    m.merge(vp.box(bin_c, (0.17, 0.06, 0.07), "plastic", 0.02))
    for dx in (-0.08, 0.0, 0.08):
        c = bin_c + (dx, 0.0, 0.072)
        m.merge(vp.lathe([(0.0, 0.0), (0.034, 0.0), (0.034, 0.004), (0.0, 0.004)], 24, "gauge", axis="z", center=c))
        m.merge(vp.lathe([(0.032, 0.0), (0.040, 0.0), (0.040, 0.010), (0.032, 0.008), (0.032, 0.0)], 24, "chrome",
                         axis="z", center=c))
    m.merge(vp.tube(vp.bend([(0.15, BELT + 0.05, SCREEN_Z + 0.12), (0.18, BELT + 0.14, SCREEN_Z + 0.16),
                             (0.62, BELT + 0.14, SCREEN_Z + 0.16), (0.65, BELT + 0.05, SCREEN_Z + 0.12)], 0.04), 0.014,
                    12, "rubber"))
    # steering column and wheel (rim, three spokes, hub)
    sw_c = np.array([-0.40, 1.16, -0.22])
    col = vp.unit((0.0, -0.45, -1.0))
    m.merge(vp.cyl(sw_c + col * 0.05, sw_c + col * 0.40, 0.030, 16, "plastic"))
    u, v = vp.frame_of(-col)
    ring = [sw_c + 0.19 * (np.cos(a) * u + np.sin(a) * v) for a in np.linspace(0, 2 * np.pi, 49)]
    m.merge(vp.tube(ring, 0.017, 12, "rubber", cap=False))
    for a in (np.pi / 2, np.pi / 2 + 2.2, np.pi / 2 - 2.2):
        d = np.cos(a) * u + np.sin(a) * v
        m.merge(vp.tube([sw_c + d * 0.04 + col * 0.02, sw_c + d * 0.18], 0.011, 10, "metal"))
    m.merge(vp.lathe([(0.0, 0.0), (0.05, 0.0), (0.048, 0.03), (0.0, 0.035)], 24, "plastic", axis="z").transformed(
        _frame_matrix(sw_c, u, v, col)))
    # gear and transfer levers on the tunnel
    for dx, h in ((-0.04, 0.30), (0.05, 0.24)):
        p = np.array([dx, FLOOR + 0.18, -0.18])
        m.merge(vp.tube([p, p + (0.0, h, 0.05)], 0.010, 10, "metal"))
        m.merge(vp.lathe([(0.0, -0.02), (0.022, -0.02), (0.026, 0.0), (0.020, 0.02), (0.0, 0.024)], 16, "rubber",
                         axis="y", center=p + (0.0, h + 0.01, 0.05)))
        m.merge(vp.box(p, (0.04, 0.012, 0.05), "rubber", 0.008))
    for x, z in SEATS:
        m.merge(_seat(x, z))
    return m


def _frame_matrix(origin, u, v, w):
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = u, v, w, origin
    return M


def _rear():
    m = Mesh()
    # cargo bed and the tailgate with its hinges and latches
    m.merge(plate_z([(-BODY_W, SILL), (BODY_W, SILL), (BODY_W, BELT), (-BODY_W, BELT)], 0.014, REAR, "paint"))
    for sx in (-1, 1):
        m.merge(vp.cyl((sx * 0.62, SILL + 0.04, REAR + 0.015), (sx * 0.48, SILL + 0.04, REAR + 0.015), 0.014, 12, "metal"))
        m.merge(vp.box((sx * 0.70, BELT - 0.06, REAR + 0.02), (0.04, 0.02, 0.012), "metal", 0.004))
        # tail lamps and reflectors on the rear corners
        c = np.array([sx * 0.76, 0.86, REAR + 0.02])
        m.merge(vp.box(c, (0.06, 0.09, 0.02), "plastic", 0.01))
        m.merge(vp.box(c + (0.0, 0.035, 0.016), (0.048, 0.035, 0.006), "taillight", 0.004))
        m.merge(vp.box(c + (0.0, -0.045, 0.016), (0.048, 0.030, 0.006), "indicator", 0.004))
        m.merge(vp.box((sx * 0.76, 0.64, REAR + 0.022), (0.05, 0.015, 0.004), "reflector", 0.003))
    # rear bumper
    m.merge(vp.box((0.0, 0.58, REAR + 0.10), (0.92, 0.07, 0.06), "frame", 0.02))
    # spare wheel on the tailgate (static, its face outwards: +Z)
    spare = sh.place(wheel(1), origin=(0.0, BELT - 0.02, REAR + 0.20), x_axis=-Z, y_axis=Y, z_axis=X)
    m.merge(spare)
    m.merge(vp.box((0.0, BELT - 0.02, REAR + 0.04), (0.06, 0.06, 0.03), "frame", 0.01))
    # two jerrycans in the bed
    for x in (-0.55, -0.20):
        jc = np.array([x, FLOOR + 0.22, REAR - 0.20])
        m.merge(vp.box(jc, (0.085, 0.22, 0.17), "armor", 0.02))
        for dy in (-0.10, 0.0, 0.10):
            m.merge(vp.box(jc + (0.087, dy, 0.0), (0.004, 0.035, 0.13), "armor", 0.01))
        m.merge(vp.tube([jc + (-0.06, 0.22, -0.08), jc + (-0.06, 0.25, -0.04), jc + (-0.06, 0.25, 0.04),
                         jc + (-0.06, 0.22, 0.08)], 0.010, 10, "armor"))
        m.merge(vp.cyl(jc + (0.04, 0.22, 0.10), jc + (0.04, 0.26, 0.13), 0.022, 12, "metal"))
    return m


def _under():
    m = Mesh()
    for sx in (-1, 1):                                          # ladder frame rails
        m.merge(vp.box((sx * 0.45, 0.47, 0.0), (0.045, 0.07, 1.95), "frame", 0.01))
    for z in (-1.70, -0.60, 0.40, 1.60):
        m.merge(vp.box((0.0, 0.47, z), (0.42, 0.05, 0.04), "frame", 0.01))
    for zc in (-WB / 2, WB / 2):                                 # live axles, differentials, shocks
        m.merge(vp.cyl((-TRACK / 2 + 0.12, AXLE_Y, zc), (TRACK / 2 - 0.12, AXLE_Y, zc), 0.045, 20, "frame"))
        diff = vp.lathe([(0.0, -0.10), (0.10, -0.08), (0.14, -0.02), (0.13, 0.04), (0.09, 0.10), (0.0, 0.11)], 28,
                        "frame", axis="z", center=(0.10, AXLE_Y, zc))
        m.merge(diff)
        for sx in (-1, 1):
            m.merge(vp.cyl((sx * 0.55, AXLE_Y + 0.05, zc + 0.10), (sx * 0.52, 0.70, zc + 0.22), 0.032, 16, "paint"))
            m.merge(vp.cyl((sx * 0.55, AXLE_Y + 0.05, zc + 0.10), (sx * 0.535, 0.58, zc + 0.17), 0.020, 12, "chrome"))
            leaf = vp.bend([(sx * 0.45, 0.44, zc - 0.55), (sx * 0.45, AXLE_Y + 0.02, zc), (sx * 0.45, 0.44, zc + 0.55)], 0.5)
            for k in range(4):
                m.merge(vp.tube(leaf - Y * 0.012 * k, 0.012 - 0.001 * k, 8, "frame"))
    m.merge(vp.cyl((0.0, AXLE_Y + 0.05, -WB / 2 + 0.12), (0.0, AXLE_Y + 0.08, WB / 2 - 0.12), 0.030, 14, "metal"))
    m.merge(vp.box((-0.25, 0.50, 0.90), (0.20, 0.10, 0.30), "plastic", 0.02))                   # fuel tank
    pipe = vp.bend([(0.25, 0.42, -1.20), (0.30, 0.38, -0.30), (0.62, 0.36, 0.60), (0.66, 0.38, 1.80),
                    (0.66, 0.40, REAR + 0.06)], 0.25)
    m.merge(vp.tube(pipe, 0.030, 16, "exhaust"))
    m.merge(vp.cyl((0.60, 0.38, 0.40), (0.62, 0.38, 1.05), 0.075, 24, "exhaust"))
    return m, np.array([0.66, 0.40, REAR + 0.08])


def build(rig):
    rig.add("body", _sides())
    rig.add("body", _bonnet_front())
    rig.add("body", _bull_bar())
    ws, screen_top = _windshield()
    rig.add("body", ws)
    rig.add("body", _cage(screen_top))
    rig.add("body", _interior())
    rig.add("body", _rear())
    under, tailpipe = _under()
    rig.add("body", under)
    for key, (x, z) in WHEELS.items():
        side = 1 if x > 0 else -1
        pivot = np.array([x, AXLE_Y, z])
        parent = "body"
        if key[0] == "f":
            rig.node(f"steer_{key}", "body", pivot)
            # the steering knuckle and the hub carrier turn with the wheel's steering, not with its spin
            rig.add(f"steer_{key}", vp.merged(
                vp.box(pivot + X * -side * 0.10, (0.05, 0.10, 0.06), "frame", 0.012),
                vp.cyl(pivot + X * -side * 0.18, pivot + X * -side * 0.08, 0.05, 18, "frame")))
            parent = f"steer_{key}"
        rig.node(f"wheel_{key}", parent, pivot)
        rig.add(f"wheel_{key}", _wheel_at(side, x, z))
    for i, (x, z) in enumerate(SEATS):
        rig.node(f"seat_{i}", "body", (x, HIP, z))
    for i, sx in enumerate((-1, 1)):
        rig.node(f"headlight_{i}", "body", (sx * 0.62, 0.93, FRONT - 0.04))
        rig.node(f"taillight_{i}", "body", (sx * 0.76, 0.895, REAR + 0.045))
    rig.node("exhaust_0", "body", tailpipe)
    _scale(rig, SCALE)


def _scale(rig, k):
    """Scales the built vehicle about its origin (on the ground): every mesh and every node's pivot."""
    for name, mesh in rig.meshes.items():
        rig.meshes[name] = mesh.transformed(np.diag([k, k, k, 1.0]))
    for name, (parent, pivot, rot) in rig.nodes.items():
        rig.nodes[name] = (parent, pivot * k, rot)
