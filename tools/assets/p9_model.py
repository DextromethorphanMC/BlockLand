"""P9 pistol geometry. Design frame (metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0.

Every builder returns a meshkit.Mesh in the design frame; gen_p9 moves meshes into node-local space."""
import numpy as np

import shapes as sh
from meshkit import Mesh

TAN18 = np.tan(np.radians(18.0))
AXIS = np.array([0.0, -np.cos(np.radians(18.0)), np.sin(np.radians(18.0))])     # grip axis, downwards
FRONT = np.array([0.0, -np.sin(np.radians(18.0)), -np.cos(np.radians(18.0))])  # grip "front" direction

# ---- key dimensions ---------------------------------------------------------------------------
SW = 0.0127                     # slide half width
SY0, SY1 = -0.0165, 0.0128      # slide bottom / top
SZ0, SZ1 = -0.0895, 0.0885      # slide front / rear
PORT = (-0.0100, 0.0260)        # ejection port z range
PORT_X, PORT_Y = -0.0060, -0.0035
CROWN_Z = -0.0880
SIGHT_Y = 0.0183                # top of rear blades / front post
REAR_SIGHT_Z = 0.0862           # rear face of the rear sight
REDDOT_GLASS_Z = 0.0425
REDDOT_Y = 0.0260
HAMMER_PIVOT = np.array([0.0, -0.0175, 0.0905])
TRIGGER_PIVOT = np.array([0.0, -0.0240, 0.0060])
STOP_PIVOT = np.array([-0.0122, -0.0212, 0.0380])
LASER_ORIGIN = np.array([0.0, -0.0362, -0.0570])
LASER_LENS = np.array([0.0045, -0.0455, -0.0802])
GRIP_C0 = np.array([0.0, -0.0270, 0.0545])       # grip centre line at the top
GRIP_LEN = 0.0800
MAG_S = 0.00625                                  # magazine centre offset (towards FRONT) at the well
MAG_LEN = 0.0840


def grip_center(d):
    return GRIP_C0 + d * AXIS


MAG_ORIGIN = grip_center(GRIP_LEN) + MAG_S * FRONT


def round_bevel(r, steps=2):
    steps = sh.arc_segments(r, steps, np.pi / 2)
    th = np.linspace(0, np.pi / 2, steps + 1)
    return [(r - r * np.sin(t), r - r * np.cos(t)) for t in th]


def chamfer_bevel(c):
    return [(c, 0.0), (0.0, c)]


def extrude_z(loop_xy, holes=(), z0=0.0, z1=0.01, bevel=((0.0, 0.0),), material="default"):
    """Extrude an (x, y) profile along Z (built with extrude_x and rotated)."""
    hw = 0.5 * (z1 - z0)
    m = sh.extrude_x(loop_xy, holes, hw, bevel, material, x_center=-(z0 + hw))
    rot = np.eye(4)
    rot[:3, :3] = np.array([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], float)
    return m.transformed(rot)


def pin(center, r, half_len, material, axis="x", seg=12):
    m = sh.revolve([(0, -half_len), (r * 0.8, -half_len), (r, -half_len + r * 0.2), (r, half_len - r * 0.2),
                    (r * 0.8, half_len), (0, half_len)], seg, material, precision=True)
    if axis == "x":
        m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated(center)


# ------------------------------------------------------------------------------------- slide
def slide_section(kind="normal", depth=0.0):
    """CCW (x, y) slide cross-section, 17 vertices. kind: normal | groove | port."""
    W, yb, yt = SW, SY0, SY1
    p = [(-W + 0.0007, yb), (W - 0.0007, yb), (W, yb + 0.0007), (W, yb + 0.0022), (W, PORT_Y),
         (W, 0.0048), (W - 0.0019, 0.0104), (W - 0.0048, yt), (0.0040, yt), (PORT_X, yt), (PORT_X, yt),
         (-W + 0.0048, yt), (-W + 0.0019, 0.0104), (-W, 0.0048), (-W, PORT_Y), (-W, yb + 0.0022),
         (-W, yb + 0.0007)]
    p = [list(q) for q in p]
    if kind == "groove":
        for i in (3, 4, 5):
            p[i][0] -= depth
        for i in (13, 14, 15):
            p[i][0] += depth
        p[6][0] -= depth * 0.5
        p[12][0] += depth * 0.5
    elif kind == "port":
        for i, x in zip((5, 6, 7, 8, 9), (0.0095, 0.0055, 0.0015, -0.0025, PORT_X)):
            p[i] = [x, PORT_Y]
    return np.array(p)


def slide_mesh():
    secs = []

    def add(z, kind="normal", inset=0.0, depth=0.0):
        s = slide_section(kind, depth)
        if inset:
            s = sh.offset_loop(s, inset)
        secs.append(np.stack([s[:, 0], s[:, 1], np.full(len(s), z)], 1))

    add(SZ0, inset=0.0012)
    add(SZ0 + 0.0012)
    for k in range(6):                                    # front serrations
        z0 = -0.0800 + k * 0.0036
        add(z0), add(z0 + 0.0003, "groove", depth=0.0007), add(z0 + 0.0015, "groove", depth=0.0007), add(z0 + 0.0018)
    add(PORT[0]), add(PORT[0] + 0.0001, "port"), add(PORT[1] - 0.0001, "port"), add(PORT[1])
    for k in range(9):                                    # rear serrations
        z0 = 0.0500 + k * 0.0036
        add(z0), add(z0 + 0.0003, "groove", depth=0.0008), add(z0 + 0.0015, "groove", depth=0.0008), add(z0 + 0.0018)
    add(SZ1 - 0.0010)
    add(SZ1, inset=0.0010)
    m = sh.loft(secs, "slide", cap_start=False, cap_end=True, auto_orient=False)
    # front face with muzzle + guide rod holes
    ring = secs[0]
    hole_b = np.stack(list(sh.circle(0, 0, 0.0071, 24).T) + [np.full(24, SZ0)], 1)
    hole_g = np.stack(list(sh.circle(0, -0.0121, 0.0033, 12).T) + [np.full(12, SZ0)], 1)
    sh.cap_polygon(m, ring[::-1], "slide", [hole_b, hole_g])
    for r_out, r_in, cy, seg in ((0.0071, 0.0058, 0.0, 24), (0.0033, 0.0024, -0.0121, 12)):
        tube = sh.revolve([(r_out, SZ0), (r_out, SZ0 + 0.0035), (r_in, SZ0 + 0.0035)], seg, "slide", outward=False)
        m.merge(tube.translated((0, cy, 0)))
    # extractor (right side behind the port) and rear cover plate
    m.merge(sh.box(SW - 0.0003, 0.0004, 0.0268, SW + 0.0006, 0.0040, 0.0400, 0.0003, "slide"))
    m.merge(sh.box(-0.0060, -0.0135, SZ1 - 0.0010, 0.0060, 0.0075, SZ1 + 0.0006, 0.0004, "slide"))
    return m


# ------------------------------------------------------------------------------------ barrel
def barrel_mesh():
    prof = [(0.0, -0.0600), (0.0045, -0.0600), (0.0045, -0.0872), (0.0051, -0.0880), (0.0062, -0.0880),
            (0.00675, -0.0875), (0.00675, -0.0160), (0.0050, -0.0150)]
    m = sh.revolve(prof, 24, "barrel", outward=True, precision=True)
    m.set_material(lambda c, n: np.hypot(c[0], c[1]) < 0.0047, "steel_dark")       # dark bore
    m.merge(sh.box(-0.0085, -0.0080, -0.0150, 0.0085, 0.0098, 0.0270, 0.0008, "barrel"))  # hood
    m.merge(sh.disc((0, 0, 0.02705), (0, 0, 1), 0.0049, 16, "steel_dark"))          # chamber mouth
    # recoil spring guide rod (front visible through the slide)
    rod = sh.revolve([(0.0, CROWN_Z), (0.0024, CROWN_Z), (0.0028, CROWN_Z + 0.0004), (0.0028, -0.0300),
                      (0.0, -0.0300)], 12, "steel_dark", precision=True)
    m.merge(rod.translated((0, -0.0121, 0)))
    coil = []
    for k in range(4):                                   # a few spring coils behind the hole lip
        z = CROWN_Z + 0.0050 + k * 0.0032
        coil.append(sh.revolve([(0.0029, z), (0.0036, z + 0.0008), (0.0029, z + 0.0016)], 12, "steel_dark", precision=True)
                    .translated((0, -0.0121, 0)))
    for c in coil:
        m.merge(c)
    return m


# ------------------------------------------------------------------------------------- frame
FRAME_PROFILE = [(-0.0845, -0.0165), (0.0930, -0.0165), (0.0962, -0.0178), (0.0966, -0.0205),
                 (0.0950, -0.0232), (0.0905, -0.0258), (0.0855, -0.0285), (0.0820, -0.0320),
                 (0.0800, -0.0360), (0.0420, -0.0360), (0.0400, -0.0300), (-0.0200, -0.0300),
                 (-0.0845, -0.0300), (-0.0850, -0.0288), (-0.0850, -0.0177)]
GUARD_PROFILE = [(-0.0255, -0.0290), (-0.0262, -0.0360), (-0.0255, -0.0420), (-0.0235, -0.0475),
                 (-0.0200, -0.0522), (-0.0150, -0.0550), (-0.0090, -0.0562), (0.0400, -0.0562),
                 (0.0400, -0.0505), (-0.0100, -0.0505), (-0.0145, -0.0493), (-0.0178, -0.0465),
                 (-0.0195, -0.0420), (-0.0200, -0.0360), (-0.0200, -0.0290)]


def strap_front_z(y):
    return 0.0295 + (-0.030 - y) * TAN18


def strap_back_z(y):
    return 0.0800 + (-0.036 - y) * TAN18 + 0.0017 * np.exp(-((y + 0.062) / 0.020) ** 2)


GRIP_WIDTH = [(-0.0035, 0.0222), (0.0, 0.0240), (0.004, 0.0275), (0.009, 0.0298), (0.016, 0.0306),
              (0.026, 0.0310), (0.036, 0.0310), (0.046, 0.0306), (0.056, 0.0302), (0.064, 0.0300),
              (0.071, 0.0303), (0.0755, 0.0311), (0.0785, 0.0320), (GRIP_LEN, 0.0326)]


def _ring_frame(d):
    u = np.clip(d / GRIP_LEN, 0, 1)
    sm = np.clip(u / 0.45, 0, 1)
    phi = np.radians(18.0) * sm * sm * (3 - 2 * sm)
    c = grip_center(d)
    s_dir = np.array([0.0, -np.sin(phi), -np.cos(phi)])
    s_f = (c[2] - strap_front_z(c[1])) / (np.cos(phi) + TAN18 * np.sin(phi))
    s_b = (strap_back_z(c[1]) - c[2]) / np.cos(phi)
    for _ in range(4):
        s_b = (strap_back_z(c[1] + s_b * np.sin(phi)) - c[2]) / np.cos(phi)
    return c, s_dir, s_f, s_b


def _shape(half_w, s_f, s_b, count=28, n_front=2.4, n_back=3.1):
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    n = np.where(s > 0, n_front, n_back)
    x = half_w * np.sign(c) * np.abs(c) ** (2 / n)
    sv = np.where(s > 0, s_f, s_b) * np.sign(s) * np.abs(s) ** (2 / n)
    return x, sv


def orient_radial(mesh, origin, axis, sign=1.0):
    """Flip faces so their normal points away from (sign=+1) or towards (-1) an axis line."""
    v = np.array(mesh.v)
    axis = np.asarray(axis, float) / np.linalg.norm(axis)
    for i, f in enumerate(mesh.f):
        p = v[list(f)]
        n = np.zeros(3)
        for k in range(1, len(f) - 1):
            n += np.cross(p[k] - p[0], p[k + 1] - p[0])
        r = p.mean(0) - origin
        r -= axis * np.dot(r, axis)
        if np.dot(n, r) * sign < 0:
            mesh.f[i] = tuple(reversed(f))
    return mesh


def mag_outline(clear=0.0, count_corner=4):
    """Magazine cross-section in mag-local (x, front) coordinates, CCW."""
    return sh.rounded_rect(-0.01125 - clear, -0.01625 - clear, 0.01125 + clear, 0.01625 + clear,
                           (0.0035 + clear, 0.0035 + clear, 0.0062 + clear, 0.0062 + clear), count_corner)


def _signed_distance(p, poly):
    """Distance of the 2D point p to the polygon's outline, negative inside."""
    a, b = poly, np.roll(poly, -1, 0)
    ab = b - a
    t = np.clip(np.einsum("ij,ij->i", p - a, ab) / np.maximum(np.einsum("ij,ij->i", ab, ab), 1e-18), 0, 1)
    dist = np.linalg.norm(a + ab * t[:, None] - p, axis=1).min()
    crossing = ((a[:, 1] > p[1]) != (b[:, 1] > p[1])) & \
        (p[0] < a[:, 0] + (p[1] - a[:, 1]) * ab[:, 0] / np.where(ab[:, 1] == 0, 1e-18, ab[:, 1]))
    return -dist if crossing.sum() % 2 else dist


def fit_well(well, ds, sections, grip_len, mag_s, depth, margin=0.0004):
    """The magazine well's outline (mag-local x, front) pulled in where the grip leaves less than `margin` around it, at
    every millimetre up to `depth` from the bottom: the well's walls run straight up the grip axis and must stay inside
    the grip's surface (poking through, they would show their back faces from outside: spec section 6).

    ds, sections: the grip's rings (distance along the grip, (M, 2) polygon in ring coordinates x / front); the rings over
    the well's depth must be perpendicular to the grip axis (the lower part of the grip), with the ring centre on the
    grip's centre line, so the well's centre sits at front = mag_s in them."""
    ds = np.asarray(ds, float)
    pts = np.asarray(well, float) + np.array([0.0, mag_s])
    for h in np.arange(0.0, depth + 1e-9, 0.001):
        d = grip_len - h
        k = int(np.clip(np.searchsorted(ds, d) - 1, 0, len(ds) - 2))
        t = float(np.clip((d - ds[k]) / (ds[k + 1] - ds[k]), 0, 1))
        poly = (1 - t) * sections[k] + t * sections[k + 1]          # the loft between two rings is linear
        for i, p in enumerate(pts):
            if _signed_distance(p, poly) <= -margin:
                continue
            lo, hi = 0.0, 1.0                                        # pull towards the ring centre
            for _ in range(32):
                mid = 0.5 * (lo + hi)
                lo, hi = (mid, hi) if _signed_distance(p * mid, poly) <= -margin else (lo, mid)
            pts[i] = p * lo
    return pts - np.array([0.0, mag_s])


def grip_mesh():
    ds = [d for d, _ in GRIP_WIDTH]
    widths = dict(GRIP_WIDTH)
    rings, sections = [], []
    for d in ds:
        c, s_dir, s_f, s_b = _ring_frame(d)
        x, sv = _shape(0.5 * widths[d], s_f, s_b)
        rings.append(c[None, :] + x[:, None] * np.array([1.0, 0, 0]) + sv[:, None] * s_dir[None, :])
        sections.append(np.stack([x, sv], 1))
    outer = sh.loft(rings, "polymer", cap_start=True, cap_end=False, auto_orient=False)
    orient_radial(outer, GRIP_C0, AXIS, +1)
    top = outer.f[-1]                                    # the top cap: must face up
    pv = np.array(outer.v)[list(top)]
    if np.cross(pv[1] - pv[0], pv[2] - pv[0])[1] < 0:
        outer.f[-1] = tuple(reversed(top))
    # stippled side panels
    outer.set_material(lambda c, n: abs(n[0]) > 0.35 and -0.0950 < c[1] < -0.0385, "grip")
    # bottom face around the magazine well + well walls
    c_end, _, _, _ = _ring_frame(GRIP_LEN)
    # the magazine's corners nearly touch the rounded front strap: the well follows the grip there
    well = fit_well(mag_outline(0.0008), ds, sections, GRIP_LEN, MAG_S, 0.034)
    to3 = lambda pts, d: (grip_center(d) + MAG_S * FRONT)[None, :] + pts[:, 0:1] * np.array([1.0, 0, 0]) \
        + pts[:, 1:2] * FRONT[None, :]
    well_bottom = to3(well, GRIP_LEN)
    bottom = Mesh()
    sh.cap_polygon(bottom, rings[-1], "polymer", [well_bottom])
    pv = np.array(bottom.v)
    f0 = bottom.f[0]
    if np.dot(np.cross(pv[f0[1]] - pv[f0[0]], pv[f0[2]] - pv[f0[0]]), AXIS) < 0:
        bottom.f = [tuple(reversed(f)) for f in bottom.f]
    walls = sh.loft([well_bottom, to3(well, GRIP_LEN - 0.034)], "polymer", cap_start=False, cap_end=True,
                    auto_orient=False)
    orient_radial(walls, MAG_ORIGIN, AXIS, -1)
    cap = walls.f[-1]
    pv = np.array(walls.v)[list(cap)]
    if np.dot(np.cross(pv[1] - pv[0], pv[2] - pv[0]), AXIS) < 0:
        walls.f[-1] = tuple(reversed(cap))
    return outer.merge(bottom).merge(walls)


def rail_mesh():
    base = np.array([(-0.0085, -0.0362), (0.0085, -0.0362), (0.0103, -0.0344), (0.0103, -0.0322),
                     (0.0083, -0.0312), (0.0083, -0.0290), (-0.0083, -0.0290), (-0.0083, -0.0312),
                     (-0.0103, -0.0322), (-0.0103, -0.0344)])
    slot = base.copy()
    slot[:, 1] = np.maximum(slot[:, 1], -0.0322)
    secs = []
    add = lambda z, s, ins=0.0: secs.append(
        np.stack([*(sh.offset_loop(s, ins) if ins else s).T, np.full(len(s), z)], 1))
    add(-0.0800, base, 0.0006)
    add(-0.0794, base)
    for zc in (-0.0720, -0.0600, -0.0480):
        add(zc - 0.0020, base), add(zc - 0.0019, slot), add(zc + 0.0019, slot), add(zc + 0.0020, base)
    add(-0.0346, base)
    add(-0.0340, base, 0.0006)
    return sh.loft(secs, "polymer")


def frame_mesh():
    m = sh.extrude_x(FRAME_PROFILE, (), 0.0115, round_bevel(0.0016, 2), "polymer")
    m.merge(sh.extrude_x(GUARD_PROFILE, (), 0.0058, round_bevel(0.0022, 2), "polymer"))
    m.merge(rail_mesh())
    m.merge(grip_mesh())
    m.merge(sh.box(-0.0163, -0.0372, 0.0330, -0.0115, -0.0302, 0.0410, 0.0009, "polymer"))   # mag release
    m.merge(sh.box(-0.0124, -0.0205, -0.0140, 0.0124, -0.0180, -0.0045, 0.0003, "steel_dark"))  # takedown
    for y, z in ((-0.0240, 0.0060), (-0.0245, -0.0095), (-0.0212, 0.0380)):
        m.merge(pin((0, y, z), 0.0011, 0.0118, "steel_dark"))
    return m
