"""Body armour geometry (VIEWMODEL_SPEC section 50).

The plate carrier is built in the player model's torso frame, as the runtime draws it (client/armor/VestLayer): metres =
blocks, +Y up, -Z forward, the origin at the neck (the body part's pivot); the torso fills x -0.25..0.25, y -0.75..0,
z -0.125..0.125. Original design after a modern low-profile plate carrier: front and back plate bags of 500D nylon
over the torso, an elastic cummerbund with side-plate pockets round the waist, padded shoulder straps over the top, a
row of three magazine pouches and an admin flap on the front, a drag handle on the back. The plates are the bulges of
the bags: `plate_1` the front plate, `plate_2` the back plate, `plate_3` the two side plates; without them the bags lie
flat.

The armour plate (the first-person item) is a single-curve ceramic plate in the ESAPI "shooter's cut": 0.25 x 0.32 m,
the top corners cut away, 22 mm thick, bent round a 0.45 m radius, in a black polyurea coat with a label."""
import numpy as np

import shapes as sh
from meshkit import Mesh

TORSO_X, TORSO_Y, TORSO_Z = 0.25, 0.75, 0.125
GAP = 0.004                         # the carrier's inside off the torso (it never touches the skin's faces)
BAG_T = 0.016                       # a plate bag's thickness when empty
BAG_X = 0.205                       # half width of the front / back bags
BAG_Y = (-0.565, -0.115)            # their bottom / top
BULGE = 0.014                       # how far a plate pushes a bag out
CUMMER_Y = (-0.575, -0.425)         # the cummerbund's band
CUMMER_T = 0.009


# ------------------------------------------------------------------------------------------- helpers
def pad(x0, x1, y0, y1, z0, z1, r, bevel, material, seg=3):
    """A padded slab facing -Z or +Z: a rounded rectangle (x, y) lofted from z0 to z1 with soft, bevelled faces."""
    loop = sh.rounded_rect(x0, y0, x1, y1, r, seg)
    rings = sh.section_rings(loop, [z0, z0 + bevel * 0.5, z0 + bevel, z1 - bevel, z1 - bevel * 0.5, z1],
                             [bevel * 0.8, bevel * 0.25, 0, 0, bevel * 0.25, bevel * 0.8])
    return sh.loft(rings, material)


def pillow(cx, cy, hx, hy, z_base, rise, sign, material, n=8):
    """A domed panel over a rectangle (a plate in its bag): flat rim, rising `rise` towards z_base + sign * rise at the
    middle; closed underneath (hidden inside the bag)."""
    xs = np.linspace(-1, 1, n + 1)
    ys = np.linspace(-1, 1, n + 1)
    m = Mesh()
    top = np.zeros((n + 1, n + 1, 3))
    for i, u in enumerate(xs):
        for j, v in enumerate(ys):
            # a superellipse dome: flat in the middle, falling off near the rim
            d = max(0.0, 1 - (abs(u) ** 4 + abs(v) ** 4) ** 0.5)
            top[i, j] = (cx + u * hx, cy + v * hy, z_base + sign * rise * d ** 0.35)
    base = m.add_vertices(top.reshape(-1, 3))
    idx = lambda i, j: base + i * (n + 1) + j
    for i in range(n):
        for j in range(n):
            m.add_face([idx(i, j), idx(i + 1, j), idx(i + 1, j + 1), idx(i, j + 1)], material)
    rim = [idx(i, 0) for i in range(n + 1)] + [idx(n, j) for j in range(1, n + 1)] \
        + [idx(i, n) for i in range(n - 1, -1, -1)] + [idx(0, j) for j in range(n - 1, 0, -1)]
    m.add_face(list(reversed(rim)), material)
    # face away from the torso
    want = np.array([0.0, 0.0, sign])
    v = np.array(m.v)
    f = m.f[0]
    nrm = np.cross(v[f[1]] - v[f[0]], v[f[2]] - v[f[0]])
    if np.dot(nrm, want) < 0:
        m.f = [tuple(reversed(face)) for face in m.f]
    return m


def strap(points, width, thick, material):
    """A flat strap along a polyline in the y-z plane (x fixed per point), its flat side facing outwards."""
    pts = np.asarray(points, float)
    rings = []
    for k in range(len(pts)):
        a = pts[max(0, k - 1)]
        b = pts[min(len(pts) - 1, k + 1)]
        t = b - a
        t = t / max(np.linalg.norm(t), 1e-9)
        side = np.array([1.0, 0.0, 0.0])
        out = np.cross(t, side)
        out = out / max(np.linalg.norm(out), 1e-9)
        c = pts[k]
        rings.append(np.array([c - side * width / 2, c + side * width / 2, c + side * width / 2 + out * thick,
                               c - side * width / 2 + out * thick]))
    return sh.loft(rings, material)


# --------------------------------------------------------------------------------------------- carrier
def carrier_mesh():
    """The carrier without plates: both bags, the cummerbund, straps, pouches, admin flap and drag handle."""
    m = Mesh()
    zf = -TORSO_Z - GAP
    zb = TORSO_Z + GAP
    # plate bags (the pocket fabric over an empty bag)
    m.merge(pad(-BAG_X, BAG_X, BAG_Y[0], BAG_Y[1], zf - BAG_T, zf, 0.035, 0.006, "carrier"))
    m.merge(pad(-BAG_X, BAG_X, BAG_Y[0] + 0.02, BAG_Y[1], zb, zb + BAG_T, 0.035, 0.006, "carrier"))
    # cummerbund: round the sides between the bags (a thin band over x = +-0.25), its side-plate pockets
    for s in (-1, 1):
        x0 = s * (TORSO_X + GAP)
        band = sh.box(min(x0, x0 + s * CUMMER_T), CUMMER_Y[0], -TORSO_Z - GAP - 0.004,
                      max(x0, x0 + s * CUMMER_T), CUMMER_Y[1], TORSO_Z + GAP + 0.004, 0.003, "webbing")
        m.merge(band)
        # the band wraps round the torso's corners onto the bags
        for zs in (-1, 1):
            m.merge(sh.box(min(s * (BAG_X - 0.01), s * (TORSO_X + GAP + CUMMER_T)), CUMMER_Y[0],
                           zs * (TORSO_Z + GAP) - (0.006 if zs < 0 else 0), max(s * (BAG_X - 0.01), s * (TORSO_X + GAP + CUMMER_T)),
                           CUMMER_Y[1], zs * (TORSO_Z + GAP) + (0.006 if zs > 0 else 0), 0.002, "webbing"))
    # shoulder straps: from the front bag's top over the shoulder to the back bag's top
    for s in (-1, 1):
        x = s * 0.155
        path = [(x, BAG_Y[1] - 0.01, zf - BAG_T * 0.6), (x, -0.04, zf - 0.006), (x, 0.004, -0.07), (x, 0.010, 0.0),
                (x, 0.004, 0.07), (x, -0.04, zb + 0.006), (x, BAG_Y[1] - 0.01, zb + BAG_T * 0.6)]
        m.merge(strap(path, 0.075, 0.011, "carrier"))
        # padding of the strap on the shoulder (lying on the torso's top, under the head)
        m.merge(sh.box(x - 0.04, 0.001, -0.09, x + 0.04, 0.014, 0.09, 0.004, "carrier"))
        # side-release buckle where the cummerbund meets the front bag
        m.merge(sh.box(s * 0.205 - 0.016, -0.53, zf - BAG_T - 0.008, s * 0.205 + 0.016, -0.49, zf - BAG_T + 0.002, 0.002,
                       "hardware"))
    # MOLLE webbing rows across the front bag's lower half and the back bag
    for k in range(3):
        y = -0.52 + k * 0.032
        m.merge(sh.box(-BAG_X + 0.012, y, zf - BAG_T - 0.003, BAG_X - 0.012, y + 0.02, zf - BAG_T + 0.001, 0.001, "webbing"))
        m.merge(sh.box(-BAG_X + 0.03, y + 0.02, zb + BAG_T - 0.001, BAG_X - 0.03, y + 0.04, zb + BAG_T + 0.003, 0.001,
                       "webbing"))
    # three magazine pouches across the front, flaps on top
    for k in (-1, 0, 1):
        cx = k * 0.105
        z0 = zf - BAG_T - 0.004
        m.merge(pad(cx - 0.042, cx + 0.042, -0.545, -0.415, z0 - 0.042, z0, 0.01, 0.004, "carrier"))
        m.merge(pad(cx - 0.044, cx + 0.044, -0.432, -0.40, z0 - 0.046, z0 - 0.001, 0.008, 0.003, "carrier"))
        m.merge(sh.box(cx - 0.012, -0.44, z0 - 0.048, cx + 0.012, -0.425, z0 - 0.044, 0.001, "hardware"))
    # admin flap above them
    m.merge(pad(-0.115, 0.115, -0.395, -0.27, zf - BAG_T - 0.016, zf - BAG_T + 0.001, 0.02, 0.004, "carrier"))
    # drag handle on the back's top
    m.merge(strap([(0.0, BAG_Y[1] - 0.03, zb + BAG_T), (0.0, BAG_Y[1] - 0.008, zb + BAG_T + 0.026),
                   (0.0, BAG_Y[1] + 0.012, zb + BAG_T + 0.03), (0.0, BAG_Y[1] + 0.02, zb + BAG_T + 0.004)],
                  0.06, 0.008, "webbing"))
    m.merge(_carrier_details(zf, zb))
    return m


def _carrier_details(zf, zb):
    """The carrier's sewn and small parts: bar tacks splitting each MOLLE row into 1-inch channels (front and back),
    a shock-cord retention loop over each pouch flap, a patch on the admin flap."""
    from stock_models import rod
    m = Mesh()
    for k in range(3):
        y = -0.52 + k * 0.032
        for x in np.arange(-BAG_X + 0.030, BAG_X - 0.020, 0.038):                       # front rows
            m.merge(sh.box(x - 0.0025, y + 0.001, zf - BAG_T - 0.0042, x + 0.0025, y + 0.019, zf - BAG_T - 0.002, 0.0006,
                           "webbing"))
        for x in np.arange(-BAG_X + 0.045, BAG_X - 0.035, 0.038):                       # back rows
            m.merge(sh.box(x - 0.0025, y + 0.021, zb + BAG_T + 0.002, x + 0.0025, y + 0.039, zb + BAG_T + 0.0042, 0.0006,
                           "webbing"))
    for k in (-1, 0, 1):                                                                 # shock-cord loops
        cx = k * 0.105
        z0 = zf - BAG_T - 0.004 - 0.047
        m.merge(rod([(cx - 0.020, -0.426, z0 + 0.003), (cx - 0.010, -0.421, z0 - 0.001), (cx + 0.010, -0.421, z0 - 0.001),
                     (cx + 0.020, -0.426, z0 + 0.003)], 0.0016, "hardware", seg=8))
    m.merge(pad(-0.045, 0.045, -0.365, -0.305, zf - BAG_T - 0.019, zf - BAG_T - 0.015, 0.006, 0.0015, "webbing"))
    return m


def plate_bulges():
    """{node: mesh}: each plate's bulge of its bag (front, back, both sides)."""
    zf = -TORSO_Z - GAP - BAG_T
    zb = TORSO_Z + GAP + BAG_T
    cy = 0.5 * (BAG_Y[0] + BAG_Y[1])
    front = pillow(0.0, cy + 0.03, BAG_X - 0.03, (BAG_Y[1] - BAG_Y[0]) / 2 - 0.05, zf + 0.002, BULGE, -1, "carrier")
    back = pillow(0.0, cy + 0.03, BAG_X - 0.03, (BAG_Y[1] - BAG_Y[0]) / 2 - 0.06, zb - 0.002, BULGE, 1, "carrier")
    sides = Mesh()
    for s in (-1, 1):
        x = s * (TORSO_X + GAP + CUMMER_T)
        side = pillow(0.0, 0.5 * (CUMMER_Y[0] + CUMMER_Y[1]), 0.07, (CUMMER_Y[1] - CUMMER_Y[0]) / 2 - 0.012, -0.001,
                      0.010, 1, "carrier")
        # turn the pillow (built facing +Z) to face +-X at the side
        R = np.array([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], float) if s > 0 else np.array([[0, 0, -1], [0, 1, 0], [1, 0, 0]], float)
        mat = np.eye(4)
        mat[:3, :3] = R
        mat[:3, 3] = (x, 0, 0)
        sides.merge(side.transformed(mat))
    return {"plate_1": front, "plate_2": back, "plate_3": sides}


def vest_parts():
    parts = {"carrier": carrier_mesh()}
    parts.update(plate_bulges())
    return parts


# -------------------------------------------------------------------------------------------- the plate
PLATE_W, PLATE_H, PLATE_T, PLATE_R = 0.25, 0.32, 0.022, 0.45
CUT = 0.055                      # the shooter's cut at the top corners


def plate_outline():
    """The plate seen from the front (x across, y up, centred): rounded corners, the top two cut away."""
    w, h = PLATE_W / 2, PLATE_H / 2
    pts = []
    def arc(cx, cy, r, a0, a1, n=4):
        for t in np.linspace(a0, a1, n):
            pts.append((cx + r * np.cos(t), cy + r * np.sin(t)))
    arc(-w + 0.02, -h + 0.02, 0.02, np.pi, 1.5 * np.pi)
    arc(w - 0.02, -h + 0.02, 0.02, 1.5 * np.pi, 2 * np.pi)
    arc(w - 0.012, h - CUT - 0.004, 0.012, 0.0, np.pi / 4, 3)
    arc(w - CUT - 0.004, h - 0.012, 0.012, np.pi / 4, np.pi / 2, 3)
    arc(-w + CUT + 0.004, h - 0.012, 0.012, np.pi / 2, 3 * np.pi / 4, 3)
    arc(-w + 0.012, h - CUT - 0.004, 0.012, 3 * np.pi / 4, np.pi, 3)
    return np.array(pts)


def bend(m, radius=PLATE_R):
    """Bends a flat plate (its face in the x-y plane) round a vertical axis behind it: the edges come back (+Z)."""
    v = np.array(m.v)
    a = v[:, 0] / radius
    x = np.sin(a) * (radius - v[:, 2])
    z = radius - np.cos(a) * (radius - v[:, 2])
    out = m.copy()
    out.v = np.stack([x, v[:, 1], z], 1).tolist()
    return out


def plate_mesh():
    """The plate in its own frame: the strike face towards -Z, the body side (+Z) towards the wearer; a label patch on
    the body side."""
    outline = plate_outline()
    m = Mesh()
    # an extrusion through z, with a rounded rim (inset rings), finely sampled across so the bend is smooth
    rings = []
    for z, ins in ((-PLATE_T / 2, 0.004), (-PLATE_T / 2 + 0.003, 0.0006), (PLATE_T / 2 - 0.003, 0.0006), (PLATE_T / 2, 0.004)):
        o = sh.offset_loop(sh.ccw(outline), ins)
        rings.append(np.stack([o[:, 0], o[:, 1], np.full(len(o), z)], 1))
    m.merge(sh.loft(rings, "plate", cap_start=False, cap_end=False))
    # the two faces as grids (so they bend), clipped to the outline by keeping inner cells
    for z, sign, ins in ((-PLATE_T / 2, -1, 0.004), (PLATE_T / 2, 1, 0.004)):
        face = Mesh()
        from meshkit import triangulate
        o = sh.offset_loop(sh.ccw(outline), ins)
        # subdivide the outline polygon with vertical slices for the bend
        xs = np.linspace(o[:, 0].min(), o[:, 0].max(), 11)
        tris = triangulate(o)
        base = face.add_vertices(np.stack([o[:, 0], o[:, 1], np.full(len(o), z)], 1))
        for t in tris:
            face.add_face([base + i for i in (t if sign > 0 else t[::-1])], "plate")
        m.merge(face)
        del xs
    m = sh.orient_outward(m)
    # a label patch on the body side
    label = sh.box(-0.07, -0.03, PLATE_T / 2 - 0.0005, 0.07, 0.06, PLATE_T / 2 + 0.0008, 0.001, "label")
    m.merge(label)
    m.merge(_seam(outline))
    return bend(_refine(m))


def _seam(outline, inset=0.009, r=0.0007, step=0.006):
    """The coat's moulded edge bead round the strike face, a finger's width in from the rim (it catches the light
    where the plate curves away); sampled finely so it follows the bend."""
    from stock_models import rod
    o = sh.offset_loop(sh.ccw(outline), inset)
    loop = np.vstack([o, o[:1]])
    pts = []
    for a, b in zip(loop[:-1], loop[1:]):
        n = max(1, int(np.ceil(np.linalg.norm(b - a) / step)))
        for t in np.arange(n) / n:
            pts.append((*(a + (b - a) * t), -PLATE_T / 2 - r * 0.35))
    pts.append(pts[0])
    return rod(pts, r, "plate", seg=6, smooth=False)


def _refine(m, step=0.025):
    """Splits long faces across x so the bend stays smooth (a fan of the outline caps has long triangles)."""
    from meshkit import Mesh as M
    out = M()
    v = np.array(m.v)
    for f, mat in zip(m.f, m.m):
        pts = v[list(f)]
        if len(f) == 3 and np.ptp(pts[:, 0]) > step:
            # subdivide the triangle into 4 recursively until short enough
            stack = [pts]
            while stack:
                p = stack.pop()
                if np.ptp(p[:, 0]) <= step:
                    out.add_poly(p, mat)
                    continue
                a, b, c = p
                ab, bc, ca = (a + b) / 2, (b + c) / 2, (c + a) / 2
                stack += [np.array([a, ab, ca]), np.array([ab, b, bc]), np.array([ca, bc, c]), np.array([ab, bc, ca])]
        else:
            out.add_poly(pts, mat)
    return out
