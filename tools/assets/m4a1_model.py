"""Colt M4A1 carbine geometry (5.56x45 mm), VIEWMODEL_SPEC section 40. Design frame (metres): +X right, +Y up,
+Z rearward; bore axis on x = y = 0, z = 0 at the front face of the upper receiver. Real-world scale: overall 840 mm
with the stock extended (757 collapsed), 368 mm barrel (bolt face to muzzle) with the A2 birdcage flash hider.

An original procedural model built part by part like the RPD / AK (shapes / meshkit helpers): the forged flat-top upper
receiver (a C-channel round the bolt carrier bore with a real ejection port cut through its right wall, the
integral MIL-STD-1913 rail with 10 mm slot pitch, the forward assist, the brass deflector, the dust cover hanging
open on its hinge), the lower receiver (hollow magazine well with its flared mouth, front pivot lug, trigger guard
ears, magazine catch fence, bolt catch bosses, buffer tower, pins, selector), the A2 pistol grip, the M4 ribbed oval
handguard with vent holes and two clamp-on rail sections (the laser's on the right, the underbarrel's at the bottom),
the delta ring, the A2 front sight base (the triangular tower, ears and post are `sight_iron`, hidden with an optic:
what stays is the gas block collar, bayonet lug and sling swivel), the barrel with its M203 groove, the A2 birdcage
(five slots, closed bottom), the flip-up rear BUIS (`sight_rear`), the T-shaped charging handle, the bolt carrier
seen through the port, and the six-position stock on its buffer tube. Every builder returns a meshkit.Mesh in the
design frame; gen_m4a1 moves meshes into node-local space."""
import numpy as np

import rifle_forms as rf
import shapes as sh
from ak_model import _cyl_x, _cyl_z, _ring_xy
from meshkit import Mesh
from model_quality import arc_segments
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions -----------------------------------------------------------------------------------------------
UP_W = 0.0150                     # upper receiver half width
UP_Y0 = -0.0115                   # upper / lower parting line
UP_Z1 = 0.1780                    # rear face of the upper
BORE_R = 0.0127                   # bolt carrier bore
NECK_W = 0.0072                   # rail neck half width
RAIL_FLOOR, RAIL_TOP = 0.0282, 0.0320   # slot floor / top of the rail lugs (32 mm over the bore)
RAIL_W = 0.0106                   # half of the 21.2 mm rail width
RAIL_Z0, RAIL_Z1 = 0.0040, 0.1760
SLOT_PITCH, LUG_LEN = 0.0100, 0.0048
PORT_Z0, PORT_Z1 = 0.0340, 0.1000  # ejection port (right wall)
PORT_Y0, PORT_Y1 = -0.0080, 0.0100
HINGE = np.array([UP_W + 0.0012, PORT_Y0 - 0.0030])     # dust cover hinge rod (x, y)
COVER_OPEN = -150.0               # degrees about +Z at the hinge: the dust cover hangs down and out
LOW_W = 0.0142                    # lower receiver half width behind the magazine well
LOW_Y0 = -0.0275                  # lower receiver bottom (the grip and the guard hang below it)
WELL_Z0, WELL_Z1 = 0.0060, 0.0880  # magazine well outside, front / rear
WELL_W = 0.0152
WELL_Y0 = -0.0720                 # bottom of the magazine well (flared mouth)
MAG_ORIGIN = np.array([0.0, -0.0105, 0.0470])   # seated magazine: top centre (mag-local origin)
MAG_HW, MAG_HD = 0.01125, 0.0318  # magazine half width / half depth (front-back)
TRIGGER_PIVOT = np.array([0.0, -0.0195, 0.1180])
GRIP_TOP = np.array([0.0, LOW_Y0, 0.1690])
GRIP_RAKE = 25.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_RAKE)), np.sin(np.radians(GRIP_RAKE))])     # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_RAKE)), -np.cos(np.radians(GRIP_RAKE))])
GRIP_LEN = 0.0940
MAG_CATCH = np.array([LOW_W + 0.0028, -0.0340, 0.0915])  # magazine catch button face centre (right side)
CATCH_PIN = np.array([-WELL_W - 0.0012, -0.0215, 0.0790])  # bolt catch pivot (left side, pin along Z)
CATCH_PAD = np.array([-WELL_W - 0.0030, -0.0150, 0.0790])  # the serrated upper pad the thumb presses
SELECTOR = np.array([-LOW_W, -0.0200, 0.1520])
BOLT_FACE = 0.0230                # bolt face at rest (just behind the barrel extension)
BOLT_TRAVEL = 0.0820              # carrier stroke
BOLT_HOLD = 0.0760                # carrier held open by the bolt catch
CH_TRAVEL = 0.0300                # charging handle pulled for a chamber check (inspect)
CH_Y = 0.0185                     # charging handle T, height of its centre line
EJECTION = np.array([UP_W + 0.0010, 0.0010, 0.0700])
SIGHT_Y = 0.0660                  # sight line over the bore (A2 height)
REAR_Z = 0.1580                   # rear aperture plane (BUIS leaf)
FRONT_POST_Z = -0.1820
FSB_Z0, FSB_Z1 = -0.1625, -0.1920  # front sight base rear / front
BARREL_R = 0.0095
HG_Z0, HG_Z1 = -0.0180, -0.1565   # handguard rear (in the delta ring) / front (in the cap)
HG_YC = -0.0010
HIDER_Z0, HIDER_Z1 = -0.3345, -0.3915   # flash hider rear (crush washer) / front: overall 840 mm
BUTT_Z = 0.8400 + HIDER_Z1        # butt pad rear face, stock extended
STOCK_LEN = 0.1720
TUBE_R = 0.0146                   # receiver extension (buffer tube)
TUBE_Z0, TUBE_Z1 = 0.1970, 0.3720
OPTIC_Z = 0.0850                  # optic base centre on the upper rail: every optic's eye behind the rail
SIDE_RAIL_Z = (-0.1240, -0.1520)  # clamp-on rail sections on the handguard (right side and bottom)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def hg_axes(z):
    """Half width / half height of the oval handguard at z (it tapers towards the front)."""
    u = (z - HG_Z0) / (HG_Z1 - HG_Z0)
    return 0.0232 + (0.0204 - 0.0232) * u, 0.0247 + (0.0216 - 0.0247) * u


# ----------------------------------------------------------------------------------------------- local helpers
def arc(cx, cy, r, a0, a1, n):
    t = np.linspace(np.radians(a0), np.radians(a1), n)
    return [(cx + r * np.cos(a), cy + r * np.sin(a)) for a in t]


def prism(loop, z0, z1, c0=0.0, c1=0.0, material="steel"):
    """(x, y) loop extruded from z0 to z1 (z0 < z1) with its own 45 degree chamfer at each end."""
    loop = sh.ccw(loop)
    zs, ins = [z0], [c0]
    if c0 > 0:
        zs.append(z0 + c0)
        ins.append(0.0)
    if c1 > 0:
        zs.append(z1 - c1)
        ins.append(0.0)
    zs.append(z1)
    ins.append(c1)
    rings = [_ring_xy(sh.offset_loop(loop, i) if i else loop, z) for z, i in zip(zs, ins)]
    return sh.loft(rings, material)


def extrude_y(profile_zx, y0, y1, bevel=((0.0, 0.0),), material="steel", holes=()):
    """A (z, x) top-view profile (with optional holes) extruded along Y from y0 to y1."""
    hw = 0.5 * (y1 - y0)
    m = sh.extrude_x(np.asarray(profile_zx, float), [np.asarray(h, float) for h in holes], hw, bevel, material,
                     x_center=0.5 * (y0 + y1))
    swap = np.eye(4)
    swap[:3, :3] = np.array([[0, 1, 0], [1, 0, 0], [0, 0, 1]], float)       # x <-> y (winding fixed by transformed)
    return m.transformed(swap)


def cylz(r, z0, z1, material, seg, x=0.0, y=0.0, chamfer=0.0):
    """Cylinder along Z with a fixed segment count (small parts, parts mostly hidden): z0 < z1."""
    c = min(chamfer, 0.45 * abs(z1 - z0))
    prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)] if c else \
        [(0.0, z0), (r, z0), (r, z1), (0.0, z1)]
    return sh.revolve(prof, seg, material).translated((x, y, 0.0))


def cylx(r, x0, x1, y, z, material, seg=12):
    """Cylinder along X with a fixed segment count (pin heads, knobs)."""
    m = sh.revolve([(0.0, x0), (r, x0), (r, x1), (0.0, x1)], seg, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated((0.0, y, z))


def cyl_axis(r, p0, p1, material, seg=None, chamfer=0.0, r1=None):
    """Closed cylinder (or cone, r1) from p0 to p1 along any axis."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    d = p1 - p0
    ln = float(np.linalg.norm(d))
    r1 = r if r1 is None else r1
    c = min(chamfer, 0.4 * ln)
    prof = [(0.0, 0.0), (r - c, 0.0), (r, c), (r1, ln - c), (r1 - c, ln), (0.0, ln)] if c > 0 else \
        [(0.0, 0.0), (r, 0.0), (r1, ln), (0.0, ln)]
    m = sh.revolve(prof, seg or 12, material, precision=seg is None)
    z = d / ln
    x = np.cross(z, (0.0, 1.0, 0.0) if abs(z[1]) < 0.9 else (1.0, 0.0, 0.0))
    x /= np.linalg.norm(x)
    return sh.place(m, origin=p0, x_axis=x, y_axis=np.cross(z, x), z_axis=z)


def ring_y(outer, inner, y0, y1, material, c=0.0):
    """Hollow prism along Y between two (x, z) loops (outer CCW, inner inside it): walls, the bore and annular ends."""
    outer, inner = sh.ccw(outer), sh.ccw(inner)
    m = Mesh()

    def ring3(loop, y, inset=0.0):
        lp = sh.offset_loop(loop, inset) if inset else loop
        return np.stack([lp[:, 0], np.full(len(lp), y), lp[:, 1]], 1)
    rings_o = [ring3(outer, y0, c), ring3(outer, y0 + c), ring3(outer, y1 - c), ring3(outer, y1, c)] if c else \
        [ring3(outer, y0), ring3(outer, y1)]
    side = sh.loft(rings_o, material, cap_start=False, cap_end=False, auto_orient=False)
    inner_w = sh.loft([ring3(inner, y0), ring3(inner, y1)], material, cap_start=False, cap_end=False, auto_orient=False)
    # orient: outer wall normals point away from the loop centre, the bore's towards it
    cen = np.r_[outer.mean(0)]
    for mesh, sign in ((side, 1.0), (inner_w, -1.0)):
        v = np.array(mesh.v)
        f = mesh.f[0]
        p = v[list(f)]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        cpt = p.mean(0)
        radial = np.array([cpt[0] - cen[0], 0.0, cpt[2] - cen[1]])
        if np.dot(n, radial) * sign < 0:
            mesh.f = [tuple(reversed(ff)) for ff in mesh.f]
        m.merge(mesh)
    lo_o, hi_o = rings_o[0], rings_o[-1]
    sh.cap_polygon(m, lo_o, material, holes3d=[ring3(inner, y0)[::-1]], flip=True)
    sh.cap_polygon(m, hi_o, material, holes3d=[ring3(inner, y1)[::-1]])
    _fix_cap(m, lo_o, -1.0)
    _fix_cap(m, hi_o, 1.0)
    return m


def _fix_cap(m, ring, sign):
    """Make the faces of the cap just added at ring's height face sign * Y."""
    y = ring[0][1]
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        p = v[list(f)]
        if np.allclose(p[:, 1], y, atol=1e-9) and len(f) == 3:
            n = np.cross(p[1] - p[0], p[2] - p[0])
            if n[1] * sign < 0:
                m.f[i] = tuple(reversed(f))


def grid_shell(P, N, recess, depth, material, floor_material, wall_material=None):
    """Surface over a grid of outer points P (nu, nv, 3), u periodic, v open, with recessed cells: the cells flagged in
    `recess` (nu, nv - 1) sit `depth` along the inward normals N, walled round. Cell (i, j) spans u i..i+1, v j..j+1.
    Faces are wound so the outer surface faces away from N. Open at the first and last v ring (cap them)."""
    P = np.asarray(P, float)
    N = np.asarray(N, float)
    nu, nv = P.shape[:2]
    m = Mesh()
    Ob = m.add_vertices(P.reshape(-1, 3))
    Ib = m.add_vertices((P + depth * N).reshape(-1, 3))
    O = lambda i, j: Ob + (i % nu) * nv + j
    I = lambda i, j: Ib + (i % nu) * nv + j
    V = lambda k: np.array(m.v[k])
    probe = [O(0, 0), O(1, 0), O(1, 1), O(0, 1)]
    pp = np.array([V(k) for k in probe])
    flip = np.dot(np.cross(pp[1] - pp[0], pp[2] - pp[0]), -N[0, 0]) < 0
    for i in range(nu):
        for j in range(nv - 1):
            L = I if recess[i, j] else O
            f = [L(i, j), L(i + 1, j), L(i + 1, j + 1), L(i, j + 1)]
            m.add_face(f[::-1] if flip else f, floor_material if recess[i, j] else material)

    def wall(a, b, cell_centre):
        f = [O(*a), O(*b), I(*b), I(*a)]
        p = np.array([V(k) for k in f])
        n = np.cross(p[1] - p[0], p[2] - p[0]) + np.cross(p[2] - p[0], p[3] - p[0])
        if np.dot(n, cell_centre - p.mean(0)) < 0:
            f = f[::-1]
        m.add_face(f, wall_material or floor_material)

    def centre(i, j):
        return 0.25 * (P[i % nu, j] + P[(i + 1) % nu, j] + P[i % nu, j + 1] + P[(i + 1) % nu, j + 1]) + depth * N[i % nu, j]
    for i in range(nu):
        for j in range(nv - 1):
            a, b = recess[i, j], recess[(i - 1) % nu, j]
            if a != b:                                    # edge u = i between cells i - 1 and i
                rec = (i, j) if a else ((i - 1) % nu, j)
                wall((i, j), (i, j + 1), centre(*rec))
            if j > 0 and recess[i, j] != recess[i, j - 1]:     # edge v = j between cells j - 1 and j
                rec = (i, j) if recess[i, j] else (i, j - 1)
                wall((i, j), (i + 1, j), centre(*rec))
    return m


def cap_ring(m, ring, material, facing, hole=None):
    """Planar cap over a closed 3D ring (optionally with an inner ring as hole), wound to face `facing`."""
    ring = np.asarray(ring, float)
    holes = [np.asarray(hole, float)[::-1]] if hole is not None else []
    tmp = Mesh()
    sh.cap_polygon(tmp, ring, material, holes3d=holes)
    v = np.array(tmp.v)
    out = []
    for f in tmp.f:
        p = v[list(f)]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        out.append(tuple(reversed(f)) if np.dot(n, facing) < 0 else f)
    tmp.f = out
    m.merge(tmp)
    return m


def rail_lugs(z0, z1, material="steel", floor=RAIL_FLOOR, top=RAIL_TOP, w=RAIL_W, pitch=SLOT_PITCH, lug=LUG_LEN,
              chamfer=0.0004):
    """Picatinny lugs on a rail (rail-local: top up, along Z): each lug the 45 degree dovetail profile, a slot pitch
    apart, chamfered."""
    m = Mesh()
    prof = np.array([(-0.0090, floor), (0.0090, floor), (w, floor + 0.0012), (w, floor + 0.0018),
                     (w - 0.0021, top), (-w + 0.0021, top), (-w, floor + 0.0018), (-w, floor + 0.0012)])
    n = int(np.floor((z1 - z0 - lug) / pitch + 1e-6)) + 1
    start = 0.5 * (z0 + z1) - 0.5 * ((n - 1) * pitch + lug)
    for k in range(n):
        za = start + k * pitch
        m.merge(prism(prof, za, za + lug, chamfer, chamfer, material))
    return m


def placed(mesh, rot_z_deg, offset):
    """Rotate a rail-local mesh about Z (90 = its top to -X, -90 = to +X, 180 = down) and move it."""
    a = np.radians(rot_z_deg)
    c, s = np.cos(a), np.sin(a)
    return sh.place(mesh, origin=offset, x_axis=(c, s, 0.0), y_axis=(-s, c, 0.0), z_axis=(0.0, 0.0, 1.0))


# --------------------------------------------------------------------------------------------- upper receiver
def _upper_outer_right():
    """Right half of the upper's outer contour from the parting line up to the rail's slot floor (x, y): the flat side
    up past the port, the rounded shoulder into the rail neck, the rail's lower dovetail."""
    pts = [(UP_W - 0.0008, UP_Y0), (UP_W, UP_Y0 + 0.0008)]
    pts += arc(NECK_W, 0.0100, UP_W - NECK_W, 0.0, 90.0, 8)
    pts += [(NECK_W, 0.0190), (0.0098, 0.0214), (0.0098, RAIL_FLOOR - 0.0004), (0.0094, RAIL_FLOOR)]
    return pts


def _bore(y_from, y_to, side, n):
    """Points on the carrier bore wall (radius BORE_R) between two heights on one side (+1 right / -1 left)."""
    return [(side * np.sqrt(max(BORE_R ** 2 - y * y, 0.0)), y) for y in np.linspace(y_from, y_to, n)]


BORE_TOP = 0.0105                 # the bore's top is flattened: the charging handle channel runs above the carrier


def upper_mesh():
    """Forged upper: a C-channel round the carrier bore with the ejection port cut through the right wall (the wall
    band beside the port is extruded only in front of and behind it), the integral rail with its lugs, the forward
    assist, the brass deflector, the port's dust cover hanging open, the front ring and the rear charging handle
    channel."""
    right = _upper_outer_right()
    left = [(-x, y) for x, y in right[::-1]]
    xi = np.sqrt(BORE_R ** 2 - UP_Y0 ** 2)
    loop = [(UP_W, PORT_Y1)] + [p for p in right if p[1] > PORT_Y1 + 1e-6] + left + [(-xi, UP_Y0)]
    loop += _bore(UP_Y0, BORE_TOP, -1, 9)[1:] + _bore(BORE_TOP, PORT_Y1, +1, 2)
    m = prism(np.array(loop), 0.0, UP_Z1, 0.0008, 0.0010, "steel")
    # lower right strip under the port
    strip = [(UP_W - 0.0008, UP_Y0), (UP_W, UP_Y0 + 0.0008), (UP_W, PORT_Y0)] + _bore(PORT_Y0, UP_Y0, +1, 4)
    m.merge(prism(np.array(strip), 0.0, UP_Z1, 0.0008, 0.0010, "steel"))
    # the wall band beside the port, in front of and behind it (the port's edges are chamfered)
    band = [(UP_W, PORT_Y0), (UP_W, PORT_Y1)] + _bore(PORT_Y1, PORT_Y0, +1, 4)
    m.merge(prism(np.array(band), 0.0, PORT_Z0, 0.0008, 0.0007, "steel"))
    m.merge(prism(np.array(band), PORT_Z1, UP_Z1, 0.0007, 0.0010, "steel"))
    # rail lugs on the slot floor
    m.merge(rail_lugs(RAIL_Z0, RAIL_Z1))
    m.merge(forward_assist_mesh())
    # brass deflector behind the port: a wedge standing out of the right wall
    defl = np.array([(PORT_Z1 + 0.0010, UP_W - 0.0020), (PORT_Z1 + 0.0070, UP_W + 0.0068),
                     (PORT_Z1 + 0.0190, UP_W + 0.0068), (PORT_Z1 + 0.0260, UP_W - 0.0020)])
    m.merge(extrude_y(defl, PORT_Y0 + 0.0010, 0.0135, round_bevel(0.0018, 2), "steel"))
    # dust cover hinge lugs (front / rear of the port, under it) and the hinge rod
    for z0, z1 in ((PORT_Z0 - 0.0075, PORT_Z0 - 0.0005), (PORT_Z1 + 0.0005, PORT_Z1 + 0.0060)):
        lug = sh.rounded_rect(UP_W - 0.0020, HINGE[1] - 0.0030, HINGE[0] + 0.0022, HINGE[1] + 0.0030, 0.0012, 2)
        m.merge(prism(lug, z0, z1, 0.0, 0.0, "steel"))
    m.merge(_cyl_z(0.0011, PORT_Z0 - 0.0080, PORT_Z1 + 0.0065, "steel_dark", 10, y=HINGE[1], x=HINGE[0]))
    m.merge(dust_cover_mesh())
    # charging handle channel lips at the rear top (the T sits behind them)
    for s in (-1, 1):
        x0, x1 = sorted((s * 0.0062, s * 0.0098))
        m.merge(sh.box(x0, 0.0150, UP_Z1 - 0.0060, x1, 0.0214, UP_Z1 + 0.0004, 0.0, "steel"))
    # inner faces of the bore read as the dark machined inside
    m.set_material(lambda c, n: np.hypot(c[0], c[1]) < BORE_R + 2e-4 and np.dot(n[:2], c[:2]) < 0 and
                   0.0005 < c[2] < UP_Z1 - 0.0005, "bore")
    return m


def forward_assist_mesh():
    """Forward assist housing angled back and out at the right rear (flared root into the wall), its plunger and the
    round button."""
    d = np.array([0.30, 0.10, 0.95])
    d /= np.linalg.norm(d)
    a0 = np.array([0.0080, 0.0068, 0.1080])
    seg = 28
    house = sh.revolve([(0.0, 0.0), (0.0098, 0.0), (0.0082, 0.0220), (0.0082, 0.0428), (0.0070, 0.0440),
                        (0.0060, 0.0440), (0.0060, 0.0480), (0.0068, 0.0482), (0.0072, 0.0490), (0.0072, 0.0500),
                        (0.0064, 0.0508), (0.0, 0.0510)], seg, "steel")
    house.set_material(lambda c, n: np.dot(c, (0, 0, 1)) > 0.0435, "steel_dark")
    x = np.cross(d, (0.0, 1.0, 0.0))
    x /= np.linalg.norm(x)
    return sh.place(house, origin=a0, x_axis=x, y_axis=np.cross(d, x), z_axis=d)


def dust_cover_mesh():
    """Sheet steel dust cover, open: modelled shut over the port (hinge at its bottom edge, a gentle outward bow),
    then swung COVER_OPEN about the hinge rod. A stiffening rib along its length, the latch boss on its inside and
    the curl round the hinge rod."""
    y0, y1 = PORT_Y0 - 0.0018, PORT_Y1 + 0.0025
    R = 0.0600
    cx = UP_W + 0.0012 - R
    cy = 0.5 * (y0 + y1)
    a0, a1 = np.degrees(np.arcsin((y0 - cy) / R)), np.degrees(np.arcsin((y1 - cy) / R))
    outer = arc(cx, cy, R, a0, a1, 6)
    inner = arc(cx, cy, R - 0.0009, a1, a0, 6)
    z0, z1 = PORT_Z0 - 0.0002, PORT_Z1 + 0.0002
    m = prism(np.array(outer + inner), z0, z1, 0.0004, 0.0004, "steel")
    rib = arc(cx, cy, R + 0.0007, -1.0, 2.0, 3) + arc(cx, cy, R - 0.0003, 2.0, -1.0, 3)
    m.merge(prism(np.array(rib), z0 + 0.0040, z1 - 0.0040, 0.0005, 0.0005, "steel"))
    m.merge(sh.box(UP_W - 0.0018, 0.0010, z0 + 0.0060, UP_W + 0.0004, 0.0042, z0 + 0.0140, 0.0, "steel"))
    curl = arc(HINGE[0], HINGE[1], 0.0019, 100.0, 300.0, 5) + arc(HINGE[0], HINGE[1], 0.0013, 300.0, 100.0, 5)
    m.merge(prism(np.array(curl), z0 + 0.0010, z1 - 0.0010, 0.0, 0.0, "steel"))
    a = np.radians(COVER_OPEN)
    rot = np.eye(4)
    rot[:2, :2] = [[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]]
    pivot = np.array([HINGE[0], HINGE[1], 0.0])
    rot[:3, 3] = pivot - rot[:3, :3] @ pivot
    return m.transformed(rot)


def charging_handle_mesh():
    """T-shaped charging handle at the rear top: the shaft in the channel under the rail and the T behind the upper
    with the latch on its left arm (node `charging_handle`, +Z = pulled)."""
    m = sh.box(-0.0058, CH_Y - 0.0040, 0.1150, 0.0058, CH_Y + 0.0026, UP_Z1 + 0.0010, 0.0006, "steel_dark")
    t = np.array([(-0.0195, -0.0026), (0.0170, -0.0026), (0.0185, -0.0010), (0.0185, 0.0018), (0.0170, 0.0030),
                  (-0.0195, 0.0030)])
    tee = [(x, y + CH_Y) for x, y in t]
    m.merge(prism(np.array(tee), UP_Z1 + 0.0008, UP_Z1 + 0.0128, 0.0008, 0.0012, "steel_dark"))
    # the grip ribs on the back of the T and the latch on the left arm (pivoting hook, its thumb tab sticking back)
    for x in (-0.0150, -0.0105, 0.0105, 0.0150):
        m.merge(sh.box(x - 0.0012, CH_Y - 0.0018, UP_Z1 + 0.0126, x + 0.0012, CH_Y + 0.0022, UP_Z1 + 0.0140, 0.0004,
                       "steel_dark"))
    latch = np.array([(UP_Z1 + 0.0010, -0.0205), (UP_Z1 - 0.0045, -0.0140), (UP_Z1 - 0.0040, -0.0115),
                      (UP_Z1 + 0.0130, -0.0120), (UP_Z1 + 0.0160, -0.0225), (UP_Z1 + 0.0120, -0.0232)])   # (z, x)
    m.merge(extrude_y(latch, CH_Y - 0.0022, CH_Y + 0.0024, chamfer_bevel(0.0006), "steel_dark"))
    return m


def carrier_mesh():
    """Bolt carrier group as seen through the port (node `bolt`, +Z = rearward): the bolt head with its seven lugs
    and the extractor, the carrier body with the forward assist serrations on its right flank, the cam pin and the
    gas key on top."""
    m = cylz(0.0085, BOLT_FACE, BOLT_FACE + 0.0070, "steel_dark", 20, chamfer=0.0006)       # bolt head
    for k in range(7):                                                                      # locking lugs
        a = 2 * np.pi * (k + 0.5) / 7
        lug = sh.box(-0.0018, 0.0070, BOLT_FACE, 0.0018, 0.0098, BOLT_FACE + 0.0050, 0.0, "steel_dark")
        c, s = np.cos(a), np.sin(a)
        m.merge(sh.place(lug, x_axis=(c, s, 0), y_axis=(-s, c, 0), z_axis=(0, 0, 1)))
    m.merge(cylz(0.0066, BOLT_FACE + 0.0060, BOLT_FACE + 0.0190, "steel_dark", 16))
    m.merge(sh.box(0.0050, -0.0025, BOLT_FACE + 0.0005, 0.0086, 0.0025, BOLT_FACE + 0.0170, 0.0, "steel_dark"))
    m.merge(cylz(0.0122, BOLT_FACE + 0.0170, BOLT_FACE + 0.0950, "steel_dark", 28, chamfer=0.0012))
    for k in range(6):                                                                      # FA serrations
        z = BOLT_FACE + 0.0580 + k * 0.0034
        m.merge(sh.box(0.0110, -0.0045, z, 0.0126, 0.0040, z + 0.0016, 0.0, "steel_dark"))
    m.merge(cylx(0.0028, -0.0040, 0.0040, 0.0118, BOLT_FACE + 0.0300, "steel_dark", 10))   # cam pin head
    m.merge(sh.box(-0.0035, 0.0105, BOLT_FACE + 0.0390, 0.0035, 0.0124, BOLT_FACE + 0.0900, 0.0, "steel_dark"))
    m.set_material(lambda c, n: abs(c[2] - BOLT_FACE) < 1e-5 and n[2] < -0.9 and np.hypot(c[0], c[1]) < 0.0030, "bore")
    return m


# --------------------------------------------------------------------------------------------- lower receiver
def lower_mesh():
    """Lower receiver: hollow magazine well with the flared mouth, front pivot lug, the fire control body with the
    trigger guard ears, buffer tower, the magazine catch fence (right), bolt catch bosses (left), pin heads, the
    selector lever (on AUTO) and the trigger guard."""
    outer = sh.rounded_rect(-WELL_W, WELL_Z0, WELL_W, WELL_Z1, (0.0050, 0.0050, 0.0035, 0.0035), 3)
    inner = sh.rounded_rect(-0.0119, WELL_Z0 + 0.0070, 0.0119, WELL_Z1 - 0.0075, 0.0025, 2)
    m = ring_y(outer, inner, WELL_Y0 + 0.0050, UP_Y0, "steel", c=0.0008)
    # flared mouth: a thicker band at the bottom with a bevelled lead-in
    flare_o = sh.rounded_rect(-WELL_W - 0.0012, WELL_Z0 - 0.0014, WELL_W + 0.0012, WELL_Z1 + 0.0012,
                              (0.0060, 0.0060, 0.0045, 0.0045), 3)
    flare_i = sh.rounded_rect(-0.0124, WELL_Z0 + 0.0063, 0.0124, WELL_Z1 - 0.0068, 0.0028, 2)
    m.merge(ring_y(flare_o, flare_i, WELL_Y0, WELL_Y0 + 0.0065, "steel", c=0.0010))
    # front pivot lug and the takedown pin
    lug = np.array([(WELL_Z0 + 0.0030, UP_Y0), (WELL_Z0 - 0.0010, UP_Y0), (WELL_Z0 - 0.0068, UP_Y0 - 0.0040),
                    (WELL_Z0 - 0.0072, UP_Y0 - 0.0120), (WELL_Z0 - 0.0030, UP_Y0 - 0.0170),
                    (WELL_Z0 + 0.0030, UP_Y0 - 0.0170)])
    m.merge(sh.extrude_x(lug, (), 0.0095, round_bevel(0.0012, 2), "steel"))
    for s in (-1, 1):
        x0, x1 = sorted((s * 0.0094, s * 0.0110))
        m.merge(cylx(0.0034, x0, x1, UP_Y0 - 0.0075, WELL_Z0 - 0.0028, "steel_dark", 14))
    # fire control body behind the well, the trigger guard ears and the tail under the buffer tower
    body = np.array([(WELL_Z1 - 0.0040, UP_Y0), (0.1900, UP_Y0), (0.1915, UP_Y0 - 0.0015), (0.1915, LOW_Y0 + 0.0030),
                     (0.1890, LOW_Y0), (0.0995, LOW_Y0), (0.0975, LOW_Y0 - 0.0035), (0.0975, -0.0465),
                     (0.0950, -0.0500), (0.0890, -0.0505), (WELL_Z1 - 0.0005, -0.0480), (WELL_Z1 - 0.0040, -0.0440)])
    m.merge(sh.extrude_x(body, (), LOW_W, round_bevel(0.0010, 2), "steel"))
    # buffer tower: the ring the receiver extension threads into, rising behind the upper
    m.merge(cylz(0.0178, UP_Z1 + 0.0006, 0.1915, "steel", 40))
    m.merge(sh.box(-0.0130, UP_Y0 - 0.0030, UP_Z1 + 0.0006, 0.0130, -0.0040, 0.1915, 0.0008, "steel"))
    # magazine catch fence round the button (right) and the bolt catch bosses (left)
    c = MAG_CATCH
    fence = np.array([(c[2] - 0.0065, c[1] + 0.0085), (c[2] + 0.0050, c[1] + 0.0085), (c[2] + 0.0065, c[1] + 0.0060),
                      (c[2] + 0.0065, c[1] - 0.0060), (c[2] + 0.0050, c[1] - 0.0085), (c[2] - 0.0065, c[1] - 0.0085),
                      (c[2] - 0.0065, c[1] - 0.0060), (c[2] - 0.0030, c[1] - 0.0055), (c[2] + 0.0035, c[1] - 0.0055),
                      (c[2] + 0.0035, c[1] + 0.0055), (c[2] - 0.0030, c[1] + 0.0055), (c[2] - 0.0065, c[1] + 0.0060)])
    m.merge(sh.extrude_x(fence, (), 0.0012, chamfer_bevel(0.0005), "steel", x_center=LOW_W + 0.0006))
    m.merge(cylx(0.0060, LOW_W - 0.0003, LOW_W + 0.0004, c[1], c[2], "steel_dark", 20))       # button bore ring
    for dz in (-0.0105, 0.0105):
        boss = np.array([(CATCH_PIN[2] + dz - 0.0018, -0.0125), (CATCH_PIN[2] + dz + 0.0018, -0.0125),
                         (CATCH_PIN[2] + dz + 0.0022, -0.0330), (CATCH_PIN[2] + dz - 0.0022, -0.0330)])
        m.merge(sh.extrude_x(boss, (), 0.0016, chamfer_bevel(0.0005), "steel", x_center=-WELL_W - 0.0008))
    # pin heads: front pivot / rear takedown (left heads, right ends), trigger and hammer pins
    for z, y, r in ((0.1700, -0.0180, 0.0030), (TRIGGER_PIVOT[2], TRIGGER_PIVOT[1], 0.0024), (0.1395, -0.0170, 0.0024)):
        for s in (-1, 1):
            x0, x1 = sorted((s * (LOW_W - 0.0002), s * (LOW_W + (0.0008 if r > 0.0026 and s < 0 else 0.0004))))
            m.merge(cylx(r, x0, x1, y, z, "steel_dark", 10))
    # selector lever on the left, on AUTO (pointing back), with its hub; the detent end on the right
    s = SELECTOR
    m.merge(cylx(0.0058, -LOW_W - 0.0012, -LOW_W + 0.0002, s[1], s[2], "steel_dark", 16))
    lever = np.array([(s[2] - 0.0030, s[1] + 0.0030), (s[2] + 0.0165, s[1] + 0.0022), (s[2] + 0.0185, s[1]),
                      (s[2] + 0.0165, s[1] - 0.0022), (s[2] - 0.0030, s[1] - 0.0030)])
    m.merge(sh.extrude_x(lever, (), 0.0010, chamfer_bevel(0.0004), "steel_dark", x_center=-LOW_W - 0.0020))
    m.merge(cylx(0.0030, LOW_W - 0.0002, LOW_W + 0.0006, s[1], s[2], "steel_dark", 10))
    m.merge(trigger_guard_mesh())
    m.set_material(lambda c, n: abs(c[0]) < 0.0122 and WELL_Z0 + 0.0060 < c[2] < WELL_Z1 - 0.0065 and
                   c[1] > WELL_Y0 + 0.0060 and abs(n[1]) < 0.5, "bore")
    return m


def trigger_guard_mesh():
    """Aluminium trigger guard: from the ears behind the well, under the trigger to the front of the grip."""
    out = [(0.0890, -0.0440), (0.0890, -0.0500), (0.0930, -0.0548), (0.1030, -0.0568), (0.1440, -0.0565),
           (0.1515, -0.0540), (0.1555, -0.0470), (0.1560, -0.0330), (0.1530, LOW_Y0 + 0.0003), (0.1480, LOW_Y0 + 0.0003),
           (0.1500, -0.0335), (0.1495, -0.0470), (0.1460, -0.0520), (0.1035, -0.0525), (0.0960, -0.0505),
           (0.0945, -0.0440)]
    return sh.extrude_x(np.array(out), (), 0.0062, round_bevel(0.0012, 2), "steel")


def mag_catch_mesh():
    """Magazine catch button (node `mag_catch`, origin on its face centre; pressed along -X): a domed, chequered face."""
    c = MAG_CATCH
    m = sh.revolve([(0.0, 0.0), (0.0042, 0.0), (0.0050, -0.0006), (0.0050, -0.0045), (0.0, -0.0045)], 16,
                   "steel_dark", precision=True)
    m = sh.place(m, x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    return m.translated(c)


def bolt_catch_mesh():
    """Bolt catch on the left (node `bolt_catch`, pivot on its pin, rotation about Z): the serrated upper pad and the
    lower paddle."""
    p = CATCH_PIN
    x = p[0]
    prof = np.array([(p[2] - 0.0042, -0.0118), (p[2] + 0.0042, -0.0118), (p[2] + 0.0046, -0.0185),
                     (p[2] + 0.0030, -0.0240), (p[2] + 0.0040, -0.0300), (p[2] + 0.0030, -0.0318),
                     (p[2] - 0.0030, -0.0318), (p[2] - 0.0040, -0.0300), (p[2] - 0.0030, -0.0240),
                     (p[2] - 0.0046, -0.0185)])
    m = sh.extrude_x(prof, (), 0.0010, chamfer_bevel(0.0004), "steel_dark", x_center=x - 0.0010)
    for k in range(4):                                                     # serrations on the upper pad
        y = -0.0132 - 0.0013 * k
        m.merge(sh.box(x - 0.0026, y - 0.0003, p[2] - 0.0036, x - 0.0018, y + 0.0003, p[2] + 0.0036, 0.0001,
                       "steel_dark"))
    return m


def trigger_mesh():
    z = TRIGGER_PIVOT[2]
    y = TRIGGER_PIVOT[1]
    blade = [(z - 0.0055, y + 0.0020), (z + 0.0030, y + 0.0020), (z + 0.0025, y - 0.0080), (z - 0.0002, y - 0.0150),
             (z - 0.0040, y - 0.0215), (z - 0.0072, y - 0.0258), (z - 0.0090, y - 0.0250), (z - 0.0068, y - 0.0205),
             (z - 0.0045, y - 0.0140), (z - 0.0040, y - 0.0060)]
    return sh.extrude_x(np.array(blade), (), 0.0028, round_bevel(0.0010, 2), "steel_dark")


def pistol_grip_mesh():
    """A2 pistol grip, raked 25 degrees: the finger swell on the front strap, a beavertail at the top rear, chequered
    side panels (texture set)."""
    stations = []
    n = 18
    for k in range(n + 1):
        d = -0.0120 + (GRIP_LEN + 0.0120) * k / n
        u = k / n
        depth = 0.0192 + 0.0026 * np.sin(np.pi * u)
        w = 0.0134 + 0.0016 * np.sin(np.pi * min(1.0, u * 1.2))
        c = grip_center(d)
        swell = 0.0032 * np.exp(-((d - 0.0430) / 0.0075) ** 2)          # the A2 finger ridge
        tail = 0.0045 * np.exp(-((d + 0.0040) / 0.0090) ** 2)           # beavertail under the buffer tower
        sec = sh.superellipse(w, depth, 2.5, 34)
        pts = []
        for x, y in sec:
            yy = y + (swell if y > 0 else -tail) * (abs(x) < w * 0.8) * (1 - (abs(x) / w) ** 2)
            pts.append((x, yy))
        stations.append((np.array(pts), c, np.array([1.0, 0.0, 0.0]), GRIP_FRONT))
    return rf.frame_loft(stations, "polymer", 0.0, 0.0050)


# --------------------------------------------------------------------------------------- barrel, front sight
def barrel_mesh():
    """Barrel ahead of the handguard (the M203 groove in front of the sight base), the front sight base collar with
    the gas block top, bayonet lug and sling swivel, the handguard cap, the delta ring, the crush washer and, inside
    the upper, the barrel extension with the chamber (seen through the port while the bolt is held open)."""
    prof = [(0.0, FSB_Z0 + 0.0080), (BARREL_R, FSB_Z0 + 0.0080), (BARREL_R, FSB_Z1 - 0.0085), (0.0081, FSB_Z1 - 0.0095), (0.0081, FSB_Z1 - 0.0190),
            (BARREL_R, FSB_Z1 - 0.0200), (BARREL_R, HIDER_Z0 + 0.0020), (0.0082, HIDER_Z0 + 0.0010),
            (0.0082, HIDER_Z0 - 0.0100), (0.0, HIDER_Z0 - 0.0100)]
    m = sh.revolve(prof, 24, "barrel", precision=True)
    m.merge(cylz(0.0106, HIDER_Z0, HIDER_Z0 + 0.0020, "barrel", 20))                          # crush washer
    # front sight base: the collar round the barrel, the gas block top and the tower's foot
    collar = sh.rounded_rect(-0.0128, -0.0128, 0.0128, 0.0125, (0.0110, 0.0110, 0.0040, 0.0040), 4)
    m.merge(prism(collar, FSB_Z1, FSB_Z0, 0.0009, 0.0009, "barrel"))
    m.merge(prism(sh.rounded_rect(-0.0055, -0.0215, 0.0055, -0.0100, 0.0015, 2), FSB_Z1 - 0.0010, FSB_Z1 + 0.0130,
                  0.0006, 0.0006, "barrel"))                                                  # bayonet lug
    sw = np.array([(FSB_Z0 - 0.0035, -0.0120), (FSB_Z0 - 0.0035, -0.0265), (FSB_Z0 - 0.0055, -0.0285),
                   (FSB_Z0 - 0.0135, -0.0285), (FSB_Z0 - 0.0155, -0.0265), (FSB_Z0 - 0.0155, -0.0120)])
    hole = np.array([(FSB_Z0 - 0.0070, -0.0245), (FSB_Z0 - 0.0120, -0.0245), (FSB_Z0 - 0.0120, -0.0200),
                     (FSB_Z0 - 0.0070, -0.0200)])
    m.merge(sh.extrude_x(sw, [hole], 0.0018, chamfer_bevel(0.0004), "barrel"))               # sling swivel ring
    m.merge(_cyl_x(0.0016, -0.0128, 0.0128, (-0.0060, FSB_Z0 - 0.0095), "barrel", 10))       # taper pins
    m.merge(_cyl_x(0.0016, -0.0128, 0.0128, (0.0060, FSB_Z1 + 0.0075), "barrel", 10))
    # handguard cap and the delta ring (its front lip holds the handguard's rear end)
    m.merge(sh.revolve([(0.0090, FSB_Z0 + 0.0005), (0.0196, FSB_Z0 + 0.0005), (0.0212, FSB_Z0 + 0.0020),
                        (0.0212, HG_Z1 + 0.0010), (0.0090, HG_Z1 + 0.0010)], 44, "barrel"))
    m.merge(sh.revolve([(0.0150, -0.0005), (0.0255, -0.0040), (0.0272, -0.0065), (0.0272, -0.0175),
                        (0.0262, -0.0215), (0.0150, -0.0215)], 48, "barrel"))
    # barrel extension inside the upper: its rear face with the chamber mouth (dark), the feed ramps below
    m.merge(sh.revolve([(0.0058, BOLT_FACE - 0.0005), (0.0105, BOLT_FACE - 0.0005), (0.0115, BOLT_FACE - 0.0020),
                        (0.0115, 0.0010), (0.0058, 0.0010)], 20, "steel_dark", precision=False))
    m.merge(sh.revolve([(0.0058, 0.0010), (0.0058, BOLT_FACE - 0.0005)], 12, "bore", outward=False))
    m.merge(sh.box(-0.0050, -0.0115, 0.0060, 0.0050, -0.0070, BOLT_FACE - 0.0010, 0.0, "steel_dark"))
    return m


def front_sight_mesh():
    """A2 front sight: the triangular tower on the base, its ear platform, the two protective ears and the post
    (node `sight_iron`, all hidden while an optic is fitted)."""
    tower = np.array([(FSB_Z1 + 0.0006, 0.0115), (FSB_Z0 - 0.0006, 0.0115), (FSB_Z0 - 0.0006, 0.0140),
                      (FRONT_POST_Z + 0.0068, SIGHT_Y - 0.0140), (FRONT_POST_Z + 0.0055, SIGHT_Y - 0.0110),
                      (FRONT_POST_Z - 0.0042, SIGHT_Y - 0.0110), (FRONT_POST_Z - 0.0052, SIGHT_Y - 0.0135),
                      (FSB_Z1 + 0.0006, 0.0160)])
    m = sh.extrude_x(tower, (), 0.0058, round_bevel(0.0012, 2), "barrel")
    plat = np.array([(-0.0098, SIGHT_Y - 0.0150), (0.0098, SIGHT_Y - 0.0150), (0.0098, SIGHT_Y - 0.0118),
                     (-0.0098, SIGHT_Y - 0.0118)])
    m.merge(prism(plat, FRONT_POST_Z - 0.0050, FRONT_POST_Z + 0.0055, 0.0006, 0.0006, "barrel"))
    for s in (-1, 1):
        ear = [(s * 0.0048, SIGHT_Y - 0.0120), (s * 0.0098, SIGHT_Y - 0.0120), (s * 0.0094, SIGHT_Y - 0.0035),
               (s * 0.0080, SIGHT_Y - 0.0016), (s * 0.0062, SIGHT_Y - 0.0016), (s * 0.0048, SIGHT_Y - 0.0040)]
        ear = ear if s > 0 else ear[::-1]
        m.merge(prism(np.array(ear), FRONT_POST_Z - 0.0046, FRONT_POST_Z + 0.0050, 0.0005, 0.0005, "barrel"))
    post = np.array([(-0.0009, SIGHT_Y - 0.0130), (0.0009, SIGHT_Y - 0.0130), (0.0009, SIGHT_Y - 0.0004),
                     (0.0005, SIGHT_Y), (-0.0005, SIGHT_Y), (-0.0009, SIGHT_Y - 0.0004)])
    m.merge(prism(post, FRONT_POST_Z - 0.0009, FRONT_POST_Z + 0.0009, 0.0002, 0.0002, "steel_dark"))
    m.merge(_cyl_z(0.0030, FRONT_POST_Z - 0.0002, FRONT_POST_Z + 0.0002, "steel_dark", 12,
                   y=SIGHT_Y - 0.0122))                                                 # the post's detent wheel
    return m


def muzzle_brake_mesh():
    """A2 birdcage flash hider (node `muzzle_brake`): wrench flats at the rear, five slots round the top and sides,
    the closed bottom (no dust kicked up), the closed front ring and the bore."""
    R, BR = 0.0110, 0.0063
    nu = arc_segments(R, 48)
    th = np.linspace(0.0, 2 * np.pi, nu, endpoint=False)
    zs = [HIDER_Z0, HIDER_Z0 - 0.0012, HIDER_Z0 - 0.0128, HIDER_Z0 - 0.0140, HIDER_Z0 - 0.0170, HIDER_Z1 + 0.0055,
          HIDER_Z1 + 0.0012, HIDER_Z1]
    P = np.zeros((nu, len(zs), 3))
    N = np.zeros_like(P)
    for j, z in enumerate(zs):
        flats = 1 <= j <= 2
        for i, a in enumerate(th):
            r = R
            if j in (0, len(zs) - 1):
                r = R - 0.0008
            if flats and abs(np.cos(a)) > 1e-6:
                r = min(r, 0.0095 / abs(np.cos(a)))
            P[i, j] = (r * np.cos(a), r * np.sin(a), z)
            N[i, j] = (-np.cos(a), -np.sin(a), 0.0)
    rec = np.zeros((nu, len(zs) - 1), bool)
    for centre in (90.0, 30.0, 150.0, -30.0, 210.0):
        i0 = int(round(np.radians(centre) % (2 * np.pi) / (2 * np.pi) * nu))
        for di in (-1, 0):
            rec[(i0 + di) % nu, 4] = True
    m = grid_shell(P, N, rec, R - BR, "barrel", "bore", "barrel")
    cap_ring(m, P[:, 0], "barrel", (0, 0, 1))
    front = np.stack([BR * np.cos(th), BR * np.sin(th), np.full(nu, HIDER_Z1)], 1)
    cap_ring(m, P[:, -1], "barrel", (0, 0, -1), hole=front)
    m.merge(sh.revolve([(BR, HIDER_Z1), (BR, HIDER_Z0 - 0.0100)], nu, "bore", outward=False))
    m.merge(sh.disc((0, 0, HIDER_Z0 - 0.0100), (0, 0, -1), BR, nu, "bore"))
    return m


# --------------------------------------------------------------------------------------------- handguard
def _hg_point(theta, z, rib):
    a, b = hg_axes(z)
    return np.array([(a + rib) * np.cos(theta), HG_YC + (b + rib) * np.sin(theta), z])


def handguard_mesh():
    """M4 oval handguard: twelve lengthwise ribs, the seam groove of its two halves on each side, six vent holes in
    each of four rows (their floors the dark heat shields), the ends tucked into the delta ring and the cap; and
    two clamp-on rail sections (right side: the laser, bottom: the underbarrel)."""
    ribs = 12
    nu = ribs * 4
    th = np.linspace(0.0, 2 * np.pi, nu, endpoint=False) + np.pi / nu
    vents_z = [HG_Z1 + 0.0220 + k * 0.0190 for k in range(5)]
    zs = [HG_Z0, HG_Z0 - 0.0025]
    for zv in vents_z[::-1]:
        zs += [zv + 0.0045, zv - 0.0045]
    zs += [HG_Z1 + 0.0025, HG_Z1]
    zs = sorted(set(round(z, 6) for z in zs), reverse=True)
    P = np.zeros((nu, len(zs), 3))
    N = np.zeros_like(P)
    for i, a in enumerate(th):
        phase = i % 4
        seam = min(abs(np.cos(a)), 1.0) > 0.985
        h = 0.0 if phase in (2, 3) or seam else 0.0013
        for j, z in enumerate(zs):
            end = j in (0, len(zs) - 1)
            p = _hg_point(a, z, (h if not end else 0.0) - (0.0010 if end else 0.0))
            P[i, j] = p
            nrm = np.array([np.cos(a) / hg_axes(z)[0], np.sin(a) / hg_axes(z)[1], 0.0])
            N[i, j] = -nrm / np.linalg.norm(nrm)
    rec = np.zeros((nu, len(zs) - 1), bool)
    for zv in vents_z:
        j = int(np.argmin([abs(z - (zv + 0.0045)) for z in zs]))
        for centre in (55.0, 125.0, 235.0, 305.0):
            i0 = int(np.argmin([abs(((np.degrees(a) - centre + 180) % 360) - 180) for a in th]))
            for di in (-1, 0, 1):
                rec[(i0 + di) % nu, j] = True
    m = grid_shell(P, N, rec, 0.0035, "polymer", "steel_dark", "polymer")
    cap_ring(m, P[:, 0], "polymer", (0, 0, 1))
    cap_ring(m, P[:, -1], "polymer", (0, 0, -1))
    # clamp-on rails: a shoe hugging the handguard and four lugs (rail-local: top = +Y), on the right and the bottom
    z0, z1 = SIDE_RAIL_Z[1], SIDE_RAIL_Z[0]
    zc = 0.5 * (z0 + z1)
    a, b = hg_axes(zc)
    for rot, surf in ((-90.0, a), (180.0, b)):
        base = 0.0050
        rail = prism(np.array([(-0.0090, -0.0030), (0.0090, -0.0030), (0.0090, base - 0.0003), (0.0086, base),
                               (-0.0086, base), (-0.0090, base - 0.0003)]), z0 - 0.0040, z1 + 0.0040, 0.0008, 0.0008,
                     "steel")
        rail.merge(rail_lugs(z0, z1, "steel", floor=base, top=base + 0.0038, w=RAIL_W, chamfer=0.0))
        for zz in (z0 - 0.0020, z1 + 0.0020):                               # clamp screws
            rail.merge(cylx(0.0021, -0.0098, -0.0088, base - 0.0018, zz, "steel_dark", 10))
        off = (surf - 0.0006, HG_YC, 0.0) if rot < 0 else (0.0, HG_YC - surf + 0.0006, 0.0)
        m.merge(placed(rail, rot, off))
    return m


def rail_top(side):
    """Outer surface point of a clamp-on rail's lugs (design frame): 'right' for the laser, 'bottom' for the
    underbarrel slot."""
    zc = 0.5 * (SIDE_RAIL_Z[0] + SIDE_RAIL_Z[1])
    a, b = hg_axes(zc)
    if side == "right":
        return np.array([a - 0.0006 + 0.0088, HG_YC, zc])
    return np.array([0.0, HG_YC - b + 0.0006 - 0.0088, zc])


# --------------------------------------------------------------------------------------------- rear sight
def rear_sight_mesh():
    """Flip-up rear BUIS on the rail (node `sight_rear`): the clamp base with its jaws and thumb screw, the leaf with
    the 6.2 mm aperture, the protective wings, the windage drum and the hinge pin."""
    z0, z1 = REAR_Z - 0.0145, REAR_Z + 0.0160
    base = np.array([(-0.0118, RAIL_TOP - 0.0012), (0.0118, RAIL_TOP - 0.0012), (0.0118, RAIL_TOP + 0.0040),
                     (0.0098, RAIL_TOP + 0.0068), (-0.0098, RAIL_TOP + 0.0068), (-0.0118, RAIL_TOP + 0.0040)])
    m = prism(base, z0, z1, 0.0008, 0.0010, "steel_dark")
    for s in (-1, 1):                                                       # jaws hooking under the dovetail
        jaw = [(s * 0.0106, RAIL_FLOOR + 0.0003), (s * 0.0122, RAIL_FLOOR + 0.0003), (s * 0.0122, RAIL_TOP - 0.0010),
               (s * 0.0104, RAIL_TOP - 0.0010)]
        m.merge(prism(np.array(jaw if s > 0 else jaw[::-1]), z0 + 0.0020, z1 - 0.0020, 0.0004, 0.0004, "steel_dark"))
    m.merge(cylx(0.0046, -0.0160, -0.0118, RAIL_TOP + 0.0010, REAR_Z + 0.0060, "steel_dark", 16))       # thumb nut
    leaf_o = np.array([(-0.0088, RAIL_TOP + 0.0060), (0.0088, RAIL_TOP + 0.0060), (0.0070, SIGHT_Y - 0.0080)] +
                      arc(0.0, SIGHT_Y, 0.0068, -40.0, 220.0, 13) + [(-0.0070, SIGHT_Y - 0.0080)])
    hole = np.array(arc(0.0, SIGHT_Y, 0.0031, 360.0, 0.0, 17)[:-1])
    m.merge(extrude_z(leaf_o, [hole], REAR_Z - 0.0016, REAR_Z + 0.0016, chamfer_bevel(0.0004), "steel_dark"))
    for s in (-1, 1):
        wing = [(s * 0.0076, RAIL_TOP + 0.0060), (s * 0.0108, RAIL_TOP + 0.0060), (s * 0.0104, SIGHT_Y + 0.0030),
                (s * 0.0090, SIGHT_Y + 0.0046), (s * 0.0080, SIGHT_Y + 0.0040)]
        m.merge(prism(np.array(wing if s > 0 else wing[::-1]), REAR_Z - 0.0030, REAR_Z + 0.0050, 0.0005, 0.0005,
                      "steel_dark"))
    m.merge(cylx(0.0042, 0.0108, 0.0136, RAIL_TOP + 0.0130, REAR_Z + 0.0010, "steel_dark", 16))        # windage
    m.merge(_cyl_x(0.0017, -0.0100, 0.0100, (RAIL_TOP + 0.0062, REAR_Z + 0.0030), "steel_dark", 10))      # hinge
    return m


# --------------------------------------------------------------------------------------------- stock
def stock_mesh():
    """Receiver end plate with its sling loop, the castle nut, the buffer tube with its position rib, and the M4
    six-position stock (extended): the round slider over the tube, the web down to the toe, the latch lever and the
    butt pad (node `stock`, hidden while the stock slot holds an attachment)."""
    plate = sh.rounded_rect(-0.0168, -0.0262, 0.0168, 0.0140, (0.0050, 0.0050, 0.0115, 0.0115), 3)
    m = prism(plate, 0.1915, 0.1955, 0.0005, 0.0006, "barrel")
    loop = np.array([(0.1900, -0.0164), (0.2020, -0.0164), (0.2032, -0.0242), (0.2005, -0.0266), (0.1915, -0.0266),
                     (0.1888, -0.0242)])                                    # sling loop (top view: z, x)
    hole = np.array([(0.1925, -0.0176), (0.1995, -0.0176), (0.1995, -0.0244), (0.1925, -0.0244)])
    m.merge(extrude_y(loop, 0.0030, 0.0054, chamfer_bevel(0.0004), "barrel", holes=[hole]))
    # castle nut: a plain front ring and the notched rear ring
    m.merge(cylz(0.0160, 0.1955, 0.2000, "steel_dark", 44))
    nu = arc_segments(0.0160, 40)
    notch = []
    for k in range(nu):
        a = 2 * np.pi * k / nu
        r = 0.0160
        if any(abs(((a - c + np.pi) % (2 * np.pi)) - np.pi) < 0.12 for c in (np.pi / 2, np.pi / 2 + 2.094, np.pi / 2 - 2.094)):
            r = 0.0138
        notch.append((r * np.cos(a), r * np.sin(a)))
    m.merge(prism(np.array(notch), 0.2000, 0.2040, 0.0, 0.0005, "steel_dark"))
    # buffer tube with its rounded rear end and the position rib along its underside
    m.merge(sh.revolve([(0.0, TUBE_Z0), (TUBE_R, TUBE_Z0), (TUBE_R, TUBE_Z1 - 0.0030), (TUBE_R - 0.0012, TUBE_Z1),
                        (0.0, TUBE_Z1)], 24, "barrel", precision=True))
    m.merge(prism(np.array([(-0.0048, -TUBE_R + 0.0020), (0.0048, -TUBE_R + 0.0020), (0.0048, -0.0168),
                            (0.0036, -0.0180), (-0.0036, -0.0180), (-0.0048, -0.0168)]), 0.2045, TUBE_Z1 - 0.0010,
                  0.0006, 0.0010, "barrel"))
    for k in range(6):                                                      # position holes (dark dimples)
        z = 0.2110 + k * 0.0165
        if z > BUTT_Z - STOCK_LEN - 0.004:
            continue                                                        # under the extended stock
        m.merge(cylz(0.0019, -0.0182, -0.0176, "bore", 8).transformed(_rot_x_to_y()).translated((0, 0, z)))
    m.merge(stock_body_mesh())
    return m


def _rot_x_to_y():
    """Matrix turning a mesh built along Z into one along -Y (a dimple facing down)."""
    r = np.eye(4)
    r[:3, :3] = np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]], float)
    return r


def stock_body_mesh():
    """The M4 stock's slider and web: round over the tube, the web dropping to the toe with a pocket moulded into each
    side, every edge rounded (4-6 mm at the toe), the front end domed; the latch lever and the rubber butt pad."""
    z0, z1 = BUTT_Z - STOCK_LEN, BUTT_Z - 0.0080
    R = 0.0190
    side_n = 9

    def sec(u):
        yb = -0.0240 - 0.0080 * u - 0.0630 * max(0.0, (u - 0.30) / 0.70) ** 1.15   # slider, then the web drops
        w = 0.0110 + 0.0062 * u ** 1.4
        if u < 0.16:
            yb = -0.0300                                     # latch housing under the front
        jy = -np.sqrt(max(R * R - w * w, 0.0))
        a0 = np.degrees(np.arctan2(jy, w))
        rb = min(0.0055, 0.45 * w, 0.30 * (jy - yb))         # the toe's rounded corners
        depth = 0.0016 * np.clip((u - 0.25) / 0.15, 0, 1) * np.clip((0.97 - u) / 0.07, 0, 1)

        def side(x_sign):
            pts = []
            for t in np.linspace(0.0, 1.0, side_n):
                y = yb + rb + t * (jy - yb - rb)
                k = np.sin(np.pi * np.clip((y - (yb + rb + 0.0025)) / max(jy - yb - rb - 0.0060, 1e-4), 0, 1))
                pts.append((x_sign * (w - depth * k ** 0.6), y))
            return pts
        right = side(1)
        left = side(-1)[::-1]
        corner_r = [(w - rb + rb * np.cos(a), yb + rb + rb * np.sin(a)) for a in np.linspace(-np.pi / 2, 0, 4)[:-1]]
        corner_l = [(-w + rb + rb * np.cos(a), yb + rb + rb * np.sin(a)) for a in np.linspace(np.pi, 1.5 * np.pi, 4)[1:]]
        pts = corner_r + right + arc(0.0, 0.0, R, a0 + 8.0, 180.0 - a0 - 8.0, 13) + left + corner_l
        return sh.ccw(np.array(pts))
    m = rf.frame_loft(rf.straight_stations(sec, z0, z1, 18), "polymer", 0.0030, 0.0)
    # latch lever under the front and its pivot, the sling slot boss at the toe
    lever = np.array([(z0 + 0.0040, -0.0300), (z0 + 0.0420, -0.0275), (z0 + 0.0440, -0.0295), (z0 + 0.0400, -0.0330),
                      (z0 + 0.0080, -0.0340)])
    m.merge(sh.extrude_x(lever, (), 0.0050, chamfer_bevel(0.0006), "steel_dark"))
    m.merge(_cyl_x(0.0020, -0.0120, 0.0120, (-0.0262, z0 + 0.0380), "steel_dark", 10))
    # butt pad: a rubber pad with its edges well rounded
    yb = -0.0240 - 0.0080 - 0.0630
    w = 0.0172
    pad = sh.rounded_rect(-w - 0.0004, yb - 0.0006, w + 0.0004, R + 0.0010, (0.0060, 0.0060, 0.0150, 0.0150), 4)
    m.merge(rf.frame_loft(rf.straight_stations(lambda u: pad, z1 - 0.0004, BUTT_Z, 2), "pad", 0.0, 0.0028))
    return m


# --------------------------------------------------------------------------------------------- magazines
def _mag_spine(extended):
    """Centre line of the curved STANAG magazine in (z, y) of mag space (top at the origin) and its tangent angles."""
    length = 0.2380 if extended else 0.1800
    straight = 0.0700 if extended else 0.0640
    curve = 0.36 if extended else 0.30
    n = 24 if extended else 19
    pts, angs = [], []
    for k in range(n + 1):
        s = length * k / n
        if s <= straight:
            pts.append((0.0, -s))
            angs.append(0.0)
        else:
            a = (s - straight) / curve
            pts.append((-(curve - curve * np.cos(a)), -straight - curve * np.sin(a)))
            angs.append(a)
    return np.array(pts), np.array(angs)


def mag_mesh(extended=False):
    """Aluminium STANAG magazine (mag-local: +Y up into the well, -Z front): the curved body, two pressed ribs on
    each side, the feed lips with the top round, the steel floor plate with its tabs."""
    spine, angs = _mag_spine(extended)
    rings, frames = [], []
    for k, ((z, y), a) in enumerate(zip(spine, angs)):
        fwd = np.array([0.0, np.sin(a), -np.cos(a)])
        c = np.array([0.0, y, z])
        sec = sh.rounded_rect(-MAG_HW, -MAG_HD, MAG_HW, MAG_HD, (0.0030, 0.0030, 0.0055, 0.0055), 2)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * fwd for p in sec]))
        frames.append((c, fwd))
    m = sh.loft(rings, "mag")
    for s in (-1, 1):                                    # pressed ribs (they follow the spine)
        for off in (-0.0125, 0.0105):
            rib = []
            for k, (c, fwd) in enumerate(frames):
                if k == 0 or k == len(frames) - 1:
                    continue
                x0 = s * (MAG_HW - 0.0002)
                x1 = s * (MAG_HW + 0.0007)
                pts = [(x0, off - 0.0016), (x1, off - 0.0011), (x1, off + 0.0011), (x0, off + 0.0016)]
                rib.append(np.array([c + px * np.array([1.0, 0, 0]) + pf * fwd for px, pf in pts]))
            rib = [r if s > 0 else r[::-1] for r in rib]
            m.merge(sh.loft(rib, "mag"))
    # feed lips and the top round (brass case, copper bullet forward)
    for s in (-1, 1):
        lip = [(s * 0.0042, 0.0), (s * 0.0102, 0.0), (s * 0.0102, 0.0030), (s * 0.0060, 0.0052), (s * 0.0042, 0.0040)]
        m.merge(prism(np.array(lip if s > 0 else lip[::-1]), -0.0250, 0.0240, 0.0006, 0.0006, "mag"))
    m.merge(sh.revolve([(0.0, 0.0), (0.0048, 0.0), (0.0048, -0.0010), (0.0045, -0.0360), (0.0032, -0.0395),
                        (0.0029, -0.0450)], 14, "brass", precision=False).translated((0.0, 0.0010, 0.0280)))
    m.merge(sh.revolve([(0.0029, -0.0450), (0.0028, -0.0520), (0.0016, -0.0600), (0.0, -0.0625)], 14, "brass",
                       precision=False).translated((0.0, 0.0010, 0.0280)))
    # floor plate
    c, fwd = frames[-1]
    up = np.cross(fwd, (1.0, 0, 0))
    plate = sh.box(-MAG_HW - 0.0010, -0.0045, -MAG_HD - 0.0035, MAG_HW + 0.0010, 0.0008, MAG_HD + 0.0012, 0.0014,
                   "mag_base")
    m.merge(sh.place(plate, origin=c, x_axis=(1, 0, 0), y_axis=-up, z_axis=-fwd))
    flange = sh.box(-MAG_HW - 0.0005, -0.0010, -MAG_HD - 0.0005, MAG_HW + 0.0005, 0.0040, MAG_HD + 0.0005, 0.0008,
                    "mag")
    m.merge(sh.place(flange, origin=c, x_axis=(1, 0, 0), y_axis=-up, z_axis=-fwd))
    return m


# --------------------------------------------------------------------------------------------- shell
def shell_mesh(scale=1.10):
    """Spent 5.56x45 mm brass case along Z, mouth towards -Z (44.7 mm, rim 9.6 mm, rebated extractor groove)."""
    prof = [(0.0, 0.0224), (0.0046, 0.0224), (0.0048, 0.0212), (0.0040, 0.0200), (0.0040, 0.0186), (0.0048, 0.0178),
            (0.0045, -0.0128), (0.0032, -0.0160), (0.0031, -0.0223), (0.0026, -0.0223), (0.0, -0.0200)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 14, "brass")
