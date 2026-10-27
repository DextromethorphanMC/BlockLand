"""Motorcycle (摩托车, VEHICLE_SPEC section 3, VIEWMODEL_SPEC section 57): an original dual-sport scrambler. Vehicle
space: metres, +Y up, the front towards -Z, the origin on the ground under the middle of the wheelbase (1.42 m).

A steel tube cradle frame round an air-cooled single (finned barrel tilted 15 degrees forward, sand-cast crankcase
with its clutch and stator covers, carburettor and air box behind), a teardrop tank with knee recesses and a chrome
filler cap, a flat ribbed seat over number-plate side panels, a high pipe along the right with a heat shield over the
silencer, a box-section swingarm on a mono-shock, chain drive on the left; telescopic fork at 26 degrees of rake
(stanchions, lower legs, triple clamps) carrying the round headlight, the front fender and the front wheel; cast
five-spoke wheels with drilled discs and two-piston calipers, dual-sport tyres."""
import numpy as np

import shapes as sh
import vehicle_parts as vp
from vehicle_parts import X, Y, Z

RAKE = 26.0
FRONT_AXLE = np.array([0.0, 0.340, -0.710])
REAR_AXLE = np.array([0.0, 0.330, 0.710])
FORK_LEN = 0.70                     # axle to steering head centre along the axis
FORK_OFFSET = 0.040                 # axle ahead of the axis
R_FORK = vp.rot_x(RAKE)             # the fork's local Y is the steering axis (its top leans back)
HEAD = FRONT_AXLE - R_FORK @ np.array([0.0, -FORK_LEN, -FORK_OFFSET])
SWING_PIVOT = np.array([0.0, 0.445, 0.115])
SEAT_HIPS = np.array([0.0, 0.935, 0.235])
SEGS = 20


def _fork_place(m):
    return sh.place(m, origin=HEAD, x_axis=R_FORK[:, 0], y_axis=R_FORK[:, 1], z_axis=R_FORK[:, 2])


def _gear(r_root, r_tip, teeth, pts_per=4):
    """(z, y) outline of a sprocket: round-topped teeth on a root circle."""
    out = []
    n = teeth * pts_per
    for i in range(n):
        a = 2 * np.pi * i / n
        ph = (i % pts_per) / pts_per
        r = r_root + (r_tip - r_root) * (0.5 - 0.5 * np.cos(2 * np.pi * ph)) ** 0.7
        out.append((r * np.cos(a), r * np.sin(a)))
    return np.array(out)


# ------------------------------------------------------------------------------------------------ wheels
def _wheel(R, width, rim_r, disc_side, sprocket):
    """A wheel about X through the origin: tyre, cast rim, hub, the brake disc on `disc_side` (+1 right, -1 left),
    optionally the chain sprocket on the left."""
    m = vp.merged(
        vp.tyre(R, width, rim_r, "tire", segs=64, shoulder=0.55),
        vp.lugs(R - 0.004, width * 0.92, 26, "tire", rows=2, depth=0.008, length=0.036, gap=0.010),
        # rim barrel (the bead seat inside the tyre) and its flanges
        vp.lathe([(rim_r - 0.022, -width * 0.42), (rim_r + 0.004, -width * 0.42), (rim_r + 0.004, -width * 0.36),
                  (rim_r - 0.006, -width * 0.32), (rim_r - 0.006, width * 0.32), (rim_r + 0.004, width * 0.36),
                  (rim_r + 0.004, width * 0.42), (rim_r - 0.022, width * 0.42), (rim_r - 0.022, -width * 0.42)],
                 48, "alu"),
        vp.spoked_face(rim_r - 0.018, 0.055, 5, 0.034, 0.010, "alu"),
        # hub and axle ends
        vp.lathe([(0.0, -0.075), (0.026, -0.075), (0.032, -0.06), (0.040, -0.05), (0.040, 0.05), (0.032, 0.06),
                  (0.026, 0.075), (0.0, 0.075)], 32, "alu"),
        vp.lathe([(0.0, -0.092), (0.012, -0.092), (0.012, 0.092), (0.0, 0.092)], 16, "metal"),
    )
    # drilled brake disc
    xd = disc_side * 0.058
    holes = [sh.circle(0.105 * np.cos(a), 0.105 * np.sin(a), 0.006, 8) for a in np.linspace(0, 2 * np.pi, 18, endpoint=False)]
    holes += [sh.circle(0.088 * np.cos(a), 0.088 * np.sin(a), 0.005, 8)
              for a in np.linspace(np.pi / 18, 2 * np.pi + np.pi / 18, 18, endpoint=False)]
    ring_out = sh.circle(0, 0, 0.130 if sprocket else 0.135, 72)
    ring_in = sh.circle(0, 0, 0.062, 36)
    m.merge(sh.extrude_x(ring_out, holes + [ring_in], half_width=0.0025, material="metal", x_center=xd, rounding=False))
    for a in np.linspace(0, 2 * np.pi, 5, endpoint=False):         # the disc's carrier bolts
        c = np.array([xd, 0.055 * np.cos(a), 0.055 * np.sin(a)])
        m.merge(vp.cyl(c - X * 0.004 * disc_side, c + X * 0.006 * disc_side, 0.006, 6, "metal", soft=False))
    m.merge(vp.lathe([(0.04, xd - 0.004), (0.064, xd - 0.004), (0.064, xd + 0.004), (0.04, xd + 0.004),
                      (0.04, xd - 0.004)], 36, "alu"))
    if sprocket:
        m.merge(sh.extrude_x(_gear(0.098, 0.110, 42), [sh.circle(0, 0, 0.05, 32)] +
                             [sh.circle(0.075 * np.cos(a), 0.075 * np.sin(a), 0.012, 14)
                              for a in np.linspace(0, 2 * np.pi, 5, endpoint=False)],
                             half_width=0.003, material="metal", x_center=-0.072, rounding=False))
    return m


# ------------------------------------------------------------------------------------------------- fork
def _fork_parts():
    """In the fork's frame (origin at the steering head, +Y up the steering axis, -Z forward)."""
    axle = np.array([0.0, -FORK_LEN, -FORK_OFFSET])
    m = vp.merged()
    for sx in (-1, 1):
        x = sx * 0.095
        # chrome stanchion from the lower clamp into the lower leg
        m.merge(vp.cyl((x, -0.30, -FORK_OFFSET * 0.4), (x, 0.13, 0.0), 0.022, SEGS, "chrome"))
        # lower leg: anodised slider with a dust seal, down to the axle clamp
        leg = [(0.0, 0.0), (0.030, 0.0), (0.033, 0.02), (0.033, 0.36), (0.030, 0.40), (0.024, 0.41), (0.0, 0.41)]
        top = np.array([x, -0.28, -FORK_OFFSET * 0.45])
        bottom = np.array([x, axle[1] - 0.03, axle[2]])
        d = vp.unit(top - bottom)
        lm = sh.revolve(leg, 28, "alu")
        sh.orient_outward(lm)
        u, v = vp.frame_of(d)
        m.merge(sh.place(lm, origin=bottom, x_axis=u, y_axis=v, z_axis=d))
        m.merge(vp.cyl(top + d * 0.0, top + d * 0.018, 0.0265, SEGS, "rubber"))            # dust seal
        # axle clamp lug with its pinch bolts
        m.merge(vp.box((x, axle[1], axle[2]), (0.022, 0.034, 0.030), "alu", 0.006))
        for dy in (-0.012, 0.012):
            c = np.array([x + sx * 0.022, axle[1] + dy, axle[2] + 0.018])
            m.merge(vp.cyl(c, c + X * sx * 0.005, 0.0045, 6, "metal", soft=False))
    # two-piston caliper on the right leg, behind the disc
    cal = np.array([0.058, axle[1] + 0.085, axle[2] + 0.075])
    m.merge(vp.oriented_box(cal, (0.020, 0.045, 0.026), X, vp.unit((0, 0.6, 0.8)), "alu", 0.008))
    # brake line up the right leg to the clamp
    m.merge(vp.tube(vp.bend([cal + (0.012, 0.04, -0.01), (0.125, axle[1] + 0.30, axle[2] - 0.01),
                             (0.125, -0.26, -0.03), (0.11, -0.06, -0.02)], 0.04), 0.0035, 8, "rubber"))
    # triple clamps (lower and upper) and the steering stem
    for y, t in ((-0.02, 0.022), (0.115, 0.018)):
        outline = np.array([(-0.135, -0.03), (0.135, -0.03), (0.150, 0.0), (0.135, 0.035), (0.04, 0.05),
                            (-0.04, 0.05), (-0.135, 0.035), (-0.150, 0.0)])
        loop = np.stack([outline[:, 0], outline[:, 1]], 1)
        clamp = sh.prism_z(loop, y - t / 2, y + t / 2, 0.004, "alu")
        m.merge(sh.place(clamp, x_axis=X, y_axis=-Z, z_axis=Y))
    m.merge(vp.cyl((0, -0.06, 0.02), (0, 0.15, 0.02), 0.016, SEGS, "metal"))
    m.merge(vp.cyl((0, 0.125, 0.02), (0, 0.15, 0.02), 0.020, SEGS, "chrome"))                 # stem nut
    # headlight bucket ahead of the stanchions on two ears, its chrome ring and lens
    hc = np.array([0.0, -0.055, -0.175])
    look = vp.unit(R_FORK.T @ np.array([0.0, -0.05, -1.0]))
    bucket = vp.lathe([(0.0, -0.10), (0.050, -0.098), (0.080, -0.075), (0.092, -0.04), (0.094, 0.0), (0.0, 0.0)],
                      40, "paint_accent", axis="z")
    u, v = vp.frame_of(look)
    m.merge(sh.place(bucket, origin=hc, x_axis=u, y_axis=v, z_axis=look))
    ring = vp.lathe([(0.082, -0.004), (0.098, -0.004), (0.100, 0.008), (0.084, 0.012), (0.082, -0.004)], 48, "chrome",
                    axis="z")
    m.merge(sh.place(ring, origin=hc, x_axis=u, y_axis=v, z_axis=look))
    lens = vp.lathe([(0.0, 0.004), (0.060, 0.000), (0.084, -0.004), (0.084, -0.010), (0.0, -0.010)], 40, "headlight",
                    axis="z")
    m.merge(sh.place(lens, origin=hc, x_axis=u, y_axis=v, z_axis=look))
    for sx in (-1, 1):
        m.merge(vp.tube([(sx * 0.095, -0.03, -0.02), (sx * 0.085, -0.04, -0.10), (sx * 0.075, -0.05, -0.135)], 0.007,
                        8, "frame"))
    # front fender: a ribbed plastic arc over the tyre on two stays
    fc = axle
    rr, hw = 0.385, 0.062
    arc = []
    for a in np.linspace(np.radians(30), np.radians(150), 22):
        d = np.array([0.0, np.sin(a), -np.cos(a)])
        arc.append(fc + d * rr)
    sec = []
    for p in arc:
        rad = vp.unit(p - fc)
        loop = [p + X * hw * np.cos(t) + rad * 0.020 * np.sin(t) for t in np.linspace(0, np.pi, 9)]
        inner = [p + X * hw * np.cos(t) + rad * (-0.004) for t in np.linspace(np.pi, 0, 9)]
        sec.append(np.array(loop + inner))
    m.merge(sh.loft(np.array(sec), "paint", rounding=False))
    for sx in (-1, 1):
        m.merge(vp.tube([(sx * 0.07, axle[1] + 0.05, axle[2] - 0.02), (sx * 0.064, axle[1] + 0.33, axle[2] - 0.06)],
                        0.005, 8, "metal"))
    return m, axle


def _handlebar_parts():
    """In the fork's frame, around the upper clamp (the handlebar node's pivot)."""
    m = vp.merged()
    bar = vp.bend([(-0.40, 0.235, 0.105), (-0.30, 0.225, 0.090), (-0.16, 0.19, 0.030), (0.0, 0.19, 0.010),
                   (0.16, 0.19, 0.030), (0.30, 0.225, 0.090), (0.40, 0.235, 0.105)], 0.06)
    m.merge(vp.tube(bar, 0.011, 16, "chrome"))
    for sx in (-1, 1):
        # risers clamping the bar
        m.merge(vp.cyl((sx * 0.045, 0.125, 0.02), (sx * 0.045, 0.19, 0.012), 0.014, SEGS, "alu"))
        # grip: ribbed rubber over the bar end, a bar-end weight
        g0 = np.array([sx * 0.27, 0.218, 0.074])
        g1 = np.array([sx * 0.40, 0.235, 0.105])
        d = vp.unit(g1 - g0)
        prof = [(0.0, 0.0), (0.017, 0.0)] + sum([[(0.017, k * 0.0105 + 0.002), (0.0155, k * 0.0105 + 0.006)]
                                                for k in range(12)], []) + [(0.018, 0.128), (0.018, 0.135), (0.0, 0.135)]
        grip = sh.revolve(prof, 20, "rubber")
        sh.orient_outward(grip)
        u, v = vp.frame_of(d)
        m.merge(sh.place(grip, origin=g0, x_axis=u, y_axis=v, z_axis=d))
        m.merge(vp.cyl(g1 + d * 0.004, g1 + d * 0.022, 0.014, 16, "chrome"))
        # lever perch and lever (clutch left, brake right)
        perch = np.array([sx * 0.235, 0.212, 0.064])
        m.merge(vp.box(perch, (0.020, 0.018, 0.018), "alu", 0.004))
        m.merge(vp.tube(vp.bend([perch + (sx * 0.01, 0.0, -0.02), perch + (sx * 0.06, 0.0, -0.06),
                                 perch + (sx * 0.17, 0.02, -0.04)], 0.03), 0.006, 8, "alu"))
        # mirror on a stalk
        mb = perch + (sx * -0.01, 0.02, 0.0)
        mh = mb + (sx * 0.10, 0.22, 0.04)
        m.merge(vp.tube(vp.bend([mb, mb + (sx * 0.03, 0.12, 0.02), mh], 0.05), 0.0055, 8, "chrome"))
        head = vp.shell([(-0.012, 0, 0, 0.055, 0.035, -0.035, 2.2, 0, 0), (0.012, 0, 0, 0.055, 0.035, -0.035, 2.2, 0, 0)],
                        "plastic", count=28, round1=0.006)
        m.merge(head.translated(mh + (0, 0.0, 0.006)))
        glass = vp.shell([(-0.013, 0, 0, 0.050, 0.030, -0.030, 2.2, 0, 0), (-0.011, 0, 0, 0.050, 0.030, -0.030, 2.2, 0, 0)],
                         "chrome", count=28)
        m.merge(glass.translated(mh + (0, 0.0, 0.006)))
    # round speedometer on a bracket: cup, glass ring, face
    gc = np.array([0.0, 0.215, -0.035])
    m.merge(vp.lathe([(0.0, -0.035), (0.040, -0.034), (0.045, -0.010), (0.046, 0.004), (0.0, 0.004)], 32, "plastic",
                     axis="y", center=gc))
    m.merge(vp.lathe([(0.0, 0.002), (0.038, 0.002), (0.038, 0.004), (0.0, 0.004)], 32, "gauge", axis="y", center=gc))
    m.merge(vp.lathe([(0.036, 0.003), (0.047, 0.003), (0.047, 0.010), (0.036, 0.008), (0.036, 0.003)], 32, "chrome",
                     axis="y", center=gc))
    return m


# ------------------------------------------------------------------------------------------------- body
def _frame():
    m = vp.merged()
    # steering head tube along the axis
    m.merge(vp.cyl(HEAD + R_FORK @ np.array([0, -0.085, 0]), HEAD + R_FORK @ np.array([0, 0.095, 0]), 0.030, 24, "frame"))
    head_low = HEAD + R_FORK @ np.array([0, -0.07, 0.01])
    head_high = HEAD + R_FORK @ np.array([0, 0.07, 0.02])
    # backbone (twin top tubes) from the head over the engine to the swingarm pivot plates
    for sx in (-1, 1):
        top = vp.bend([head_high, (sx * 0.055, 0.86, -0.20), (sx * 0.085, 0.80, 0.02), (sx * 0.095, 0.72, 0.08),
                       (sx * 0.10, SWING_PIVOT[1] + 0.06, SWING_PIVOT[2] - 0.02)], 0.10)
        m.merge(vp.tube(top, 0.0145, 16, "frame"))
        # seat rail and the strut from the pivot plate up to it
        rail = vp.bend([(sx * 0.085, 0.80, 0.02), (sx * 0.090, 0.835, 0.30), (sx * 0.080, 0.86, 0.62)], 0.08)
        m.merge(vp.tube(rail, 0.0115, 14, "frame"))
        m.merge(vp.tube([(sx * 0.10, 0.52, 0.10), (sx * 0.092, 0.70, 0.36), (sx * 0.085, 0.84, 0.55)], 0.010, 12, "frame"))
        # cradle: down tubes split under the engine and rise to the pivot plates
        down = vp.bend([head_low + (sx * 0.0, 0, 0), (sx * 0.035, 0.62, -0.36), (sx * 0.075, 0.30, -0.28),
                        (sx * 0.080, 0.18, -0.12), (sx * 0.085, 0.18, 0.06), (sx * 0.10, SWING_PIVOT[1] - 0.05, SWING_PIVOT[2])],
                       0.07)
        m.merge(vp.tube(down, 0.0145, 16, "frame"))
        # swingarm pivot plate (gusset) with the pivot bolt
        plate = np.array([(0.18, 0.62), (0.03, 0.55), (0.02, 0.38), (0.08, 0.34), (0.20, 0.40), (0.22, 0.54)])
        m.merge(vp.plate(plate, 0.004, "frame", x=sx * 0.107))
        m.merge(vp.cyl((sx * 0.10, SWING_PIVOT[1], SWING_PIVOT[2]), (sx * 0.125, SWING_PIVOT[1], SWING_PIVOT[2]),
                       0.013, 12, "metal"))
    # cross tube under the tank and the skid plate under the engine
    m.merge(vp.cyl((-0.06, 0.84, -0.12), (0.06, 0.84, -0.12), 0.010, 12, "frame"))
    skid = np.array([(-0.10, -0.34), (0.10, -0.34), (0.12, -0.24), (0.12, 0.10), (-0.12, 0.10), (-0.12, -0.24)])
    m.merge(vp.plate_xz(skid, 0.155, 0.162, "alu", 0.002))
    return m


def _engine():
    m = vp.merged()
    # crankcase: a rounded cast block, the clutch cover bulging on the right, the stator cover on the left
    m.merge(vp.shell([(-0.20, 0, 0.36, 0.115, 0.10, -0.13, 3.2, 0.3, 0.4),
                      (-0.05, 0, 0.36, 0.125, 0.12, -0.15, 3.4, 0.4, 0.5),
                      (0.10, 0, 0.35, 0.120, 0.11, -0.14, 3.4, 0.4, 0.5),
                      (0.16, 0, 0.33, 0.100, 0.07, -0.10, 3.0, 0.3, 0.4)], "alu", count=36, round0=0.03, round1=0.03))
    for sx, rr, c in ((1, 0.098, (0.0, 0.33, -0.02)), (-1, 0.085, (0.0, 0.32, 0.03))):
        x0 = sx * 0.118
        cov = vp.lathe([(0.0, 0.0), (rr, 0.0), (rr + 0.004, 0.012), (rr - 0.006, 0.030), (rr * 0.55, 0.040),
                        (0.0, 0.042)], 48, "alu", axis="x", center=(x0, c[1], c[2]))
        if sx < 0:
            cov = vp.mirror_x(vp.lathe([(0.0, 0.0), (rr, 0.0), (rr + 0.004, 0.012), (rr - 0.006, 0.030),
                                        (rr * 0.55, 0.040), (0.0, 0.042)], 48, "alu", axis="x",
                                       center=(-x0, c[1], c[2])))
        m.merge(cov)
        for a in np.linspace(0, 2 * np.pi, 8, endpoint=False):     # cover screws
            p = np.array([sx * (0.118 + 0.014), c[1] + (rr - 0.008) * np.cos(a), c[2] + (rr - 0.008) * np.sin(a)])
            m.merge(vp.cyl(p, p + X * sx * 0.006, 0.0042, 6, "metal", soft=False))
    # finned barrel and head, tilted 15 degrees forward
    axis = vp.unit((0.0, np.cos(np.radians(15)), -np.sin(np.radians(15))))
    base = np.array([0.0, 0.47, -0.06])
    prof = [(0.0, 0.0), (0.055, 0.0)]
    for k in range(9):                                              # cooling fins of the barrel
        y = 0.012 + k * 0.016
        prof += [(0.058, y), (0.098 - 0.002 * k, y + 0.002), (0.098 - 0.002 * k, y + 0.007), (0.058, y + 0.009)]
    for k in range(5):                                              # bigger fins of the head
        y = 0.170 + k * 0.016
        prof += [(0.062, y), (0.112, y + 0.002), (0.112, y + 0.008), (0.062, y + 0.010)]
    prof += [(0.060, 0.255), (0.040, 0.268), (0.0, 0.270)]
    barrel = sh.revolve(prof, 36, "alu", rounding=False)
    sh.orient_outward(barrel)
    u, v = vp.frame_of(axis)
    m.merge(sh.place(barrel, origin=base, x_axis=u, y_axis=v, z_axis=axis))
    # spark plug and its cap, the rocker caps
    plug = base + axis * 0.235 + np.array([0.0, 0.0, -0.06])
    m.merge(vp.cyl(plug, plug + np.array([0.0, 0.02, -0.04]), 0.010, 12, "rubber"))
    m.merge(vp.tube(vp.bend([plug + (0, 0.02, -0.04), (0.06, 0.80, -0.30), (0.10, 0.82, -0.22)], 0.04), 0.004, 8,
                    "rubber"))
    for dz in (-0.04, 0.04):
        p = base + axis * 0.262 + np.array([0.0, 0.0, dz])
        m.merge(vp.cyl(p, p + axis * 0.02, 0.022, 20, "alu"))
    # carburettor behind the barrel and the air box under the seat
    carb = base + axis * 0.15 + np.array([0.0, 0.0, 0.10])
    m.merge(vp.cyl(carb - Z * 0.03, carb + Z * 0.05, 0.026, 20, "alu"))
    m.merge(vp.box(carb + (0.0, 0.035, 0.01), (0.024, 0.022, 0.026), "alu", 0.006))
    m.merge(vp.tube([carb + Z * 0.05, carb + (0.0, 0.03, 0.12), (0.0, 0.70, 0.30)], 0.030, 18, "rubber"))
    m.merge(vp.box((0.0, 0.70, 0.36), (0.085, 0.075, 0.09), "plastic", 0.02))
    # front sprocket cover on the left
    m.merge(vp.mirror_x(vp.box((0.13, 0.40, 0.09), (0.012, 0.045, 0.06), "alu", 0.01)))
    return m


def _exhaust():
    m = vp.merged()
    port = np.array([0.0, 0.66, -0.17])
    path = vp.bend([port, (0.0, 0.62, -0.30), (0.08, 0.50, -0.34), (0.15, 0.44, -0.22), (0.17, 0.50, -0.02),
                    (0.18, 0.62, 0.20), (0.18, 0.68, 0.36)], 0.08, steps=8)
    m.merge(vp.tube(path, 0.019, 18, "exhaust"))
    # silencer: tapered can with end caps, its outlet the exhaust node
    s0, s1 = np.array([0.18, 0.68, 0.36]), np.array([0.19, 0.74, 0.74])
    d = vp.unit(s1 - s0)
    prof = [(0.0, -0.01), (0.022, -0.01), (0.044, 0.04), (0.046, 0.06), (0.046, 0.34), (0.042, 0.37), (0.030, 0.39),
            (0.016, 0.395), (0.016, 0.42), (0.0, 0.42)]
    can = sh.revolve(prof, 36, "exhaust")
    sh.orient_outward(can)
    u, v = vp.frame_of(d)
    m.merge(sh.place(can, origin=s0, x_axis=u, y_axis=v, z_axis=d))
    # perforated heat shield on the can's outer side
    shield = []
    for t in np.linspace(0.08, 0.30, 10):
        c = s0 + d * t
        ring = [c + 0.052 * (np.cos(a) * u + np.sin(a) * v) for a in np.linspace(-1.1, 1.1, 9)]
        shield.append(ring)
    sh_m = sh.loft(np.array(shield), "chrome", cap_start=False, cap_end=False, closed=False, rounding=False)
    back = sh.loft(np.array(shield)[:, ::-1] - 0.0015 * np.array([u]), "chrome", cap_start=False, cap_end=False,
                   closed=False, rounding=False)
    m.merge(sh_m)
    m.merge(back)
    outlet = s0 + d * 0.42
    return m, outlet


def _tank_seat():
    m = vp.merged()
    # tank: teardrop over the backbone, recessed for the knees at the back
    st = []
    for s, hw, top, bot in ((-0.40, 0.07, 0.94, 0.86), (-0.33, 0.135, 1.00, 0.80), (-0.22, 0.165, 1.03, 0.78),
                            (-0.10, 0.160, 1.03, 0.79), (-0.01, 0.120, 1.00, 0.82), (0.05, 0.085, 0.965, 0.84)):
        st.append((s, 0.0, 0.0, hw, top, bot, 2.6, 0.25, 0.6))
    m.merge(vp.shell(st, "paint", count=44, round0=0.03, round1=0.03))
    # stripe band and the chrome filler cap
    cap = np.array([0.0, 1.03, -0.20])
    m.merge(vp.lathe([(0.0, 0.0), (0.038, 0.0), (0.040, 0.008), (0.034, 0.016), (0.0, 0.018)], 32, "chrome", axis="y",
                     center=cap - (0, 0.004, 0)))
    m.merge(vp.lathe([(0.0, 0.016), (0.012, 0.016), (0.012, 0.026), (0.0, 0.026)], 16, "chrome", axis="y", center=cap))
    for sx in (-1, 1):                                                  # tank badges
        m.merge(vp.oriented_box((sx * 0.162, 0.93, -0.18), (0.003, 0.022, 0.06), X, Y, "chrome", 0.002))
    # seat: flat, ribbed top, from the tank back to the tail
    st = []
    for s, hw, top in ((0.00, 0.075, 0.935), (0.08, 0.125, 0.940), (0.30, 0.135, 0.945), (0.52, 0.115, 0.950),
                       (0.66, 0.080, 0.955)):
        st.append((s, 0.0, 0.0, hw, top, 0.855, 5.0, 0.85, 0.9))
    m.merge(vp.shell(st, "seat", count=44, round0=0.02, round1=0.03))
    for k in range(7):                                                  # stitched rib seams across the seat top
        z = 0.12 + k * 0.05
        y = 0.938 + 0.01 * (z / 0.66)
        m.merge(vp.tube([(-0.095, y, z), (0.0, y + 0.002, z), (0.095, y, z)], 0.0022, 6, "seat"))
    # side panels (number boards) under the seat
    for sx in (-1, 1):
        outline = np.array([(0.02, 0.86), (0.36, 0.86), (0.40, 0.78), (0.30, 0.66), (0.08, 0.66), (0.00, 0.74)])
        m.merge(vp.plate(outline, 0.006, "paint_accent", x=sx * 0.125))
        oval = np.array([(0.21 + 0.09 * np.cos(a), 0.76 + 0.055 * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 28,
                                                                                                  endpoint=False)])
        m.merge(vp.plate(oval, 0.0015, "deck", x=sx * 0.1325))
    return m


def _tail():
    m = vp.merged()
    # rear fender rising from under the seat to the taillight
    sec = []
    for z, y, hw in ((0.60, 0.86, 0.085), (0.75, 0.865, 0.075), (0.90, 0.875, 0.062), (1.02, 0.885, 0.050)):
        loop = [(hw * np.cos(t), y + 0.035 * np.sin(t) * 0.6, z) for t in np.linspace(0, np.pi, 11)]
        loop += [(hw * np.cos(t) * 0.94, y - 0.006, z) for t in np.linspace(np.pi, 0, 11)]
        sec.append(np.array(loop))
    m.merge(sh.loft(np.array(sec), "paint_accent", rounding=False))
    tl = np.array([0.0, 0.875, 1.03])
    m.merge(vp.box(tl, (0.042, 0.022, 0.018), "plastic", 0.006))
    m.merge(vp.box(tl + (0.0, 0.0, 0.016), (0.036, 0.016, 0.004), "taillight", 0.003))
    # licence plate on its bracket, the plate light, turn signals on stalks
    m.merge(vp.oriented_box((0.0, 0.78, 1.02), (0.090, 0.060, 0.002), X, vp.unit((0, 1, 0.25)), "deck", 0.004))
    m.merge(vp.tube([(0.0, 0.86, 0.99), (0.0, 0.82, 1.01)], 0.006, 8, "metal"))
    base, tip = np.array([0.05, 0.87, 0.98]), np.array([0.15, 0.87, 1.00])
    sig = vp.merged(vp.cyl(base, tip, 0.006, 8, "rubber"),
                    vp.lathe([(0.0, 0.0), (0.014, 0.0), (0.016, 0.018), (0.012, 0.034), (0.0, 0.036)], 16, "indicator",
                             axis="x", center=tip),
                    vp.box((0.06, 0.70, 1.04), (0.012, 0.012, 0.003), "reflector", 0.002))
    m.merge(vp.both_sides(sig))
    return m, tl + (0.0, 0.0, 0.02)


def _swingarm():
    m = vp.merged()
    for sx in (-1, 1):
        x = sx * 0.095
        st = []
        p0 = SWING_PIVOT + (x, 0, 0)
        p1 = REAR_AXLE + (x * 1.05, 0, 0)
        d = vp.unit(p1 - p0)
        for k, (hh, hw) in enumerate(((0.032, 0.020), (0.036, 0.021), (0.030, 0.018), (0.022, 0.015))):
            t = k / 3
            o = p0 + (p1 - p0) * t
            loop = sh.rounded_rect(-hw, -hh, hw, hh, 0.006, 3)
            st.append((loop, o, X, vp.unit(np.cross(d, X) * -1)))
        import rifle_forms as rf
        m.merge(rf.frame_loft(st, "alu", round0=0.008, round1=0.008))
        # chain adjuster block at the axle
        m.merge(vp.box(REAR_AXLE + (x * 1.18, 0, 0.0), (0.006, 0.016, 0.03), "alu", 0.004))
    # cross brace in front of the tyre, the shock link
    m.merge(vp.cyl(SWING_PIVOT + (-0.09, -0.01, 0.22), SWING_PIVOT + (0.09, -0.01, 0.22), 0.016, 16, "alu"))
    m.merge(vp.cyl((-0.11, SWING_PIVOT[1], SWING_PIVOT[2]), (0.11, SWING_PIVOT[1], SWING_PIVOT[2]), 0.018, 18, "alu"))
    # mono-shock: body, the spring coil round it
    s0, s1 = np.array([0.0, 0.47, 0.38]), np.array([0.0, 0.79, 0.16])
    d = vp.unit(s1 - s0)
    m.merge(vp.cyl(s0, s0 + d * 0.30, 0.020, 18, "alu"))
    m.merge(vp.cyl(s0 + d * 0.30, s1, 0.009, 12, "chrome"))
    u, v = vp.frame_of(d)
    coil = [s0 + d * (0.04 + 0.20 * t) + 0.034 * (np.cos(t * 2 * np.pi * 7) * u + np.sin(t * 2 * np.pi * 7) * v)
            for t in np.linspace(0, 1, 7 * 16 + 1)]
    m.merge(vp.tube(coil, 0.0055, 8, "paint"))
    # chain run on the left between the sprockets
    top0, top1 = np.array([-0.072, 0.43 + 0.05, 0.09]), REAR_AXLE + (-0.072, 0.105, 0.0)
    bot0, bot1 = np.array([-0.072, 0.43 - 0.05, 0.09]), REAR_AXLE + (-0.072, -0.105, 0.0)
    for a, b in ((top0, top1), (bot0, bot1)):
        n = int(np.linalg.norm(b - a) / 0.0127)
        for k in range(n):
            c = a + (b - a) * (k + 0.5) / n
            m.merge(vp.oriented_box(c, (0.005, 0.004, 0.0062), X, Y, "metal", rounding=False))
    # chain guard over the top run
    m.merge(vp.oriented_box((top0 + top1) / 2 + (0.0, 0.03, 0.0), (0.004, 0.006, np.linalg.norm(top1 - top0) * 0.42),
                            X, vp.unit(np.cross(X, vp.unit(top1 - top0)) * -1), "plastic", 0.003))
    return m


def _pegs_stand():
    m = vp.merged()
    for sx in (-1, 1):
        p = np.array([sx * 0.16, 0.36, 0.20])
        m.merge(vp.cyl(p - X * sx * 0.05, p + X * sx * 0.035, 0.012, 12, "metal"))
        for k in range(5):                                              # serrated peg teeth
            c = p + X * sx * (-0.02 + k * 0.012) + Y * 0.012
            m.merge(vp.oriented_box(c, (0.002, 0.003, 0.02), X, Y, "metal", rounding=False))
    # shift lever (left), brake pedal (right), kickstand (left, up)
    m.merge(vp.tube(vp.bend([(-0.14, 0.42, 0.05), (-0.15, 0.40, -0.06), (-0.17, 0.38, -0.12)], 0.03), 0.006, 8, "metal"))
    m.merge(vp.tube(vp.bend([(0.13, 0.38, 0.14), (0.15, 0.34, 0.02), (0.17, 0.33, -0.06)], 0.03), 0.006, 8, "metal"))
    m.merge(vp.tube([(-0.11, 0.30, 0.08), (-0.14, 0.25, 0.30)], 0.009, 10, "frame"))
    m.merge(vp.box((-0.14, 0.248, 0.31), (0.018, 0.004, 0.026), "frame", 0.003))
    return m


def build(rig):
    rig.add("body", vp.merged(_frame(), _engine(), _tank_seat(), _swingarm(), _pegs_stand()))
    ex, outlet = _exhaust()
    rig.add("body", ex)
    tail, tail_light = _tail()
    rig.add("body", tail)
    # fork and its children (steering about the fork's local Y)
    rig.node("fork", "body", HEAD, R_FORK)
    fork_m, axle_local = _fork_parts()
    rig.add("fork", _fork_place(fork_m))
    bar_pivot = HEAD + R_FORK @ np.array([0.0, 0.115, 0.02])
    rig.node("handlebar", "fork", bar_pivot, R_FORK)
    rig.add("handlebar", _fork_place(_handlebar_parts()))
    rig.node("wheel_f", "fork", FRONT_AXLE, R_FORK)
    wf = _wheel(0.340, 0.090, 0.275, 1, False)
    rig.add("wheel_f", sh.place(wf, origin=FRONT_AXLE, x_axis=R_FORK[:, 0], y_axis=R_FORK[:, 1], z_axis=R_FORK[:, 2]))
    hl = HEAD + R_FORK @ np.array([0.0, -0.055, -0.20])
    rig.node("headlight_0", "fork", hl, R_FORK)
    # rear wheel, its caliper on the swingarm (body)
    rig.node("wheel_r", "body", REAR_AXLE)
    rig.add("wheel_r", _wheel(0.330, 0.125, 0.245, 1, True).translated(REAR_AXLE))
    cal = REAR_AXLE + (0.058, -0.06, -0.09)
    rig.add("body", vp.oriented_box(cal, (0.018, 0.04, 0.024), X, vp.unit((0, 0.8, -0.6)), "alu", 0.007))
    rig.node("seat_0", "body", SEAT_HIPS)
    rig.node("exhaust_0", "body", outlet)
    rig.node("taillight_0", "body", tail_light)
