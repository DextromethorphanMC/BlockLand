"""Steyr SSG 08 geometry (.308 Winchester bolt-action sniper rifle), VIEWMODEL_SPEC sections 23 and 40. Design frame
(metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0, z = 0 at the front face of the receiver. Real-world
scale: overall 1.14 m with the stock extended, a 508 mm fluted heavy barrel (bolt face to crown) and a muzzle brake.

The rifle as modelled: a flat-sided aluminium upper (the action, bolt bore, ejection port on the right) on a chassis
lower with the magazine well, the trigger guard and the pistol grip, one picatinny rail along the whole top from the
rear of the receiver to the front of the fore-end, an octagonal aluminium fore-end with side vent plates (through
slots: the fluted barrel shows in them), short side rails and a bottom rail at its front, the skeletal folding stock
(hinge on the right, the adjustable cheek piece on two posts with its wheel, the adjustable butt plate and the rubber
pad), the rotating bolt with three lugs and the large round knob on the right, the non-rotating shroud, the 10-round
polymer magazine with the top round in its lips, the three-port muzzle brake.

Every builder returns a meshkit.Mesh in the design frame; gen_ssg08 moves meshes into node-local space.

Materials (stable names: the texture sets of section 20 and the gold skin's tints key on them) and their regions
(section 21): steel -> body (upper, lower, top rail), handguard -> handguard (fore-end), stock -> stock (aluminium
stock), cheek -> stock (polymer cheek piece), grip -> grip, pad -> none (rubber butt pad), barrel -> barrel (barrel,
brake), steel_dark -> detail (bolt, trigger, screws, pins, small parts), mag / mag_base -> magazine, brass -> none."""
import numpy as np

import shapes as sh
from ak_model import _cyl_x, _cyl_z, _loft_z, _ring_xy
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

MATERIAL_REGIONS = {"steel": "body", "handguard": "handguard", "stock": "stock", "cheek": "stock", "grip": "grip",
                    "pad": "none", "barrel": "barrel", "steel_dark": "detail", "mag": "magazine",
                    "mag_base": "magazine", "brass": "none"}

# ---- key dimensions ---------------------------------------------------------------------------
UP_W = 0.0185                     # upper receiver half width (flat sides)
UP_Y0, UP_Y1 = -0.0150, 0.0160    # upper bottom (meets the lower) / top (the rail sits on it)
UP_Z1 = 0.1900                    # rear face of the upper (the bolt handle root sits just behind it)
BORE_R = 0.0100                   # bolt bore through the upper
PORT_Z0, PORT_Z1 = 0.0480, 0.1180  # ejection port on the right
PORT_Y0, PORT_Y1 = -0.0065, 0.0085
LOW_W = 0.0245                    # lower chassis half width
LOW_Y0 = -0.0460                  # bottom of the lower (magazine well, trigger guard root)
LOW_Z1 = 0.2620                   # rear end of the lower: the stock hinge line
TANG_Y1 = -0.0175                 # top of the lower behind the upper (the bolt shroud travels back over it)
RAIL_B = UP_Y1                    # picatinny base
RAIL_TOP = RAIL_B + 0.0095        # picatinny top (mount_optic)
RAIL_W = 0.0106                   # half width over the teeth
RAIL_Z0, RAIL_Z1 = -0.3550, 0.1960
PITCH, TOOTH = 0.01001, 0.00478   # MIL-STD-1913 slot pitch and tooth length
OPTIC_Z = 0.1300                  # optic base centre on the rail (a red dot's eye point clears the bolt shroud)
FE_Z0 = -0.3550                   # fore-end nose
FE_W = 0.0255                     # fore-end half width (flat sides)
FE_Y0 = -0.0330                   # fore-end bottom
FE_SIDE0, FE_SIDE1 = -0.0235, 0.0050    # the flat side band
FE_TOP_W = 0.0170                 # top chamfers end under the rail
CHANNEL_R = 0.0140                # barrel channel through the fore-end
VENT_Z0, VENT_Z1 = -0.2560, -0.0300     # vent plates on both sides
VENT_Y0, VENT_Y1 = -0.0215, 0.0035
VENT_T = 0.0035                   # vent plate thickness
SIDE_RAIL_Z = (-0.3480, -0.2700)  # short side rails at 3 and 9 o'clock (front of the fore-end)
SIDE_RAIL_Y = (FE_SIDE0 + FE_SIDE1) / 2
BOT_RAIL_Z = (-0.3460, -0.2650)   # bottom rail (bipod)
BARREL_R0, BARREL_R1 = 0.0120, 0.0099   # barrel radius at the receiver / at the crown
CROWN_Z = -0.4700
BRAKE_R, BRAKE_LEN = 0.0128, 0.0720
MUZZLE_Z = CROWN_Z - BRAKE_LEN    # front face of the muzzle brake
FLUTE_Z0, FLUTE_Z1 = -0.4400, -0.0350
BOLT_R = 0.0094                   # bolt body radius
BOLT_FACE = 0.0350
BOLT_TRAVEL = 0.0880              # bolt stroke (short action, .308)
BOLT_LIFT = 60.0                  # degrees the handle turns up to unlock (about +Z: right side up)
HANDLE_Z = 0.2000                 # bolt handle root (bolt closed): just behind the upper
HANDLE_DROP = 24.0                # the stem points right and this many degrees down at rest
HANDLE_SWEEP = 0.16               # rearward sweep of the stem (dz per unit of radial reach)
HANDLE_LEN = 0.0590               # bolt axis -> knob centre (radial)
KNOB_R = 0.0118                   # the oversized round knob (23.6 mm)
SHROUD_Z0, SHROUD_Z1 = 0.2085, 0.2460
EJECTION = np.array([UP_W + 0.0005, 0.0010, 0.0840])
MAG_ORIGIN = np.array([0.0, -0.0120, 0.0800])   # seated magazine pivot (top centre, inside the well)
MAG_H, MAG_HL, MAG_HW = 0.0880, 0.0405, 0.0128  # magazine height, half length, half width
WELL_Z0, WELL_Z1 = MAG_ORIGIN[2] - MAG_HL - 0.0040, MAG_ORIGIN[2] + MAG_HL + 0.0040
RELEASE_Z = 0.1255                # magazine release paddle at the front of the trigger guard
GUARD_Z0, GUARD_Z1 = 0.1290, 0.1990
GUARD_Y = -0.0740                 # bottom of the trigger guard
TRIGGER_PIVOT = np.array([0.0, LOW_Y0 + 0.0050, 0.1630])
GRIP_TOP = np.array([0.0, LOW_Y0 + 0.0040, 0.2120])   # front strap meets the lower behind the trigger guard
GRIP_RAKE = 14.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_RAKE)), np.sin(np.radians(GRIP_RAKE))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_RAKE)), -np.cos(np.radians(GRIP_RAKE))])
GRIP_LEN = 0.1050
STOCK_Z0 = LOW_Z1 + 0.0015        # stock front (behind the hinge line)
STOCK_HW = 0.0110                 # stock skeleton half width
BUTT_Z = 0.5640                   # rear face of the butt plate carrier
PAD_Z1 = 0.5920                   # rear of the rubber pad (overall 1.134 m from the brake)
COMB_Y = 0.0380                   # top of the cheek piece
CHEEK_Z0, CHEEK_Z1 = 0.3860, 0.5280
HINGE = np.array([LOW_W - 0.0030, -0.0350, LOW_Z1 + 0.0008])   # folding hinge axis (vertical, right side)
CHARM_POINT = np.array([-LOW_W + 0.0015 - 0.0016, -0.0300, 0.1500])   # QD sling cup (left, in the rear pocket)
POCKETS = ((0.0300, 0.1120), (0.1240, 0.1780))   # milled pockets in the sides of the lower (z ranges)
POCKET_Y = (-0.0390, -0.0210)
POCKET_D = 0.0015
SAFETY = np.array([LOW_W + 0.0004, TANG_Y1 - 0.0050, 0.2420])  # three-position safety lever (right, behind the bolt)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# ------------------------------------------------------------------------------------- helpers
def chaikin(poly, iters=1, keep=()):
    """Corner-cut a closed 2D polygon `iters` times (rounded corners); vertices in `keep` stay sharp."""
    p = [np.asarray(q, float) for q in poly]
    sharp = set(keep)
    for _ in range(iters):
        # every edge (a, b) gives its points at 1/4 and 3/4; an end at a sharp vertex keeps that vertex instead
        out, new_sharp = [], set()
        n = len(p)
        for i in range(n):
            a, b = p[i], p[(i + 1) % n]
            if i in sharp:
                new_sharp.add(len(out))
                out.append(a)
            else:
                out.append(0.75 * a + 0.25 * b)
            if (i + 1) % n not in sharp:
                out.append(0.25 * a + 0.75 * b)
        p, sharp = out, new_sharp
    return np.array(p)


def bevel_round(r, steps=2):
    """Quarter-round extrusion bevel with a fixed number of steps (round_bevel refines to the chord error)."""
    th = np.linspace(0, np.pi / 2, steps + 1)
    return [(r - r * np.sin(t), r - r * np.cos(t)) for t in th]


def prism(loop, holes, z0, z1, c0, c1, material):
    """(x, y) outline with holes from z0 to z1, its outer edges (and the holes' edges) chamfered by c0 at the front
    end and c1 at the rear end (0 = a square end that meets the next part flush)."""
    loop = sh.ccw(loop)
    holes = [sh.cw(h) for h in holes]
    m = Mesh()
    for lp in [loop] + holes:
        rings = []
        if c0 > 0:
            rings.append(_ring_xy(sh.offset_loop(lp, c0), z0))
            rings.append(_ring_xy(lp, z0 + c0))
        else:
            rings.append(_ring_xy(lp, z0))
        if c1 > 0:
            rings.append(_ring_xy(lp, z1 - c1))
            rings.append(_ring_xy(sh.offset_loop(lp, c1), z1))
        else:
            rings.append(_ring_xy(lp, z1))
        m.merge(sh.loft(rings, material, cap_start=False, cap_end=False, auto_orient=False))
    for z, c, flip in ((z0, c0, True), (z1, c1, False)):
        outer = _ring_xy(sh.offset_loop(loop, c) if c > 0 else loop, z)
        hs = [_ring_xy(sh.offset_loop(h, c) if c > 0 else h, z) for h in holes]
        sh.cap_polygon(m, outer, material, holes3d=hs, flip=flip)
    return m


def plate_x(profile_zy, holes, x0, x1, bevel, material):
    """Plate between x0 and x1 from a (z, y) outline with (z, y) holes (extrude_x)."""
    hw = 0.5 * (x1 - x0)
    return sh.extrude_x(np.asarray(profile_zy, float), [np.asarray(h, float) for h in holes], hw, bevel, material,
                        x_center=0.5 * (x0 + x1))


def slot_zy(z0, z1, yc, h, n=6):
    """Stadium (z, y) outline: a slot from z0 to z1, h tall, round ends (n points per end)."""
    r = 0.5 * h
    pts = []
    for a in np.linspace(-np.pi / 2, np.pi / 2, n):          # rear end (+z)
        pts.append((z1 - r + r * np.cos(a), yc + r * np.sin(a)))
    for a in np.linspace(np.pi / 2, 1.5 * np.pi, n):         # front end
        pts.append((z0 + r + r * np.cos(a), yc + r * np.sin(a)))
    return np.array(pts)


def rail_teeth(z0, z1, base=0.0, width=RAIL_W, material="steel"):
    """Picatinny teeth (local frame: rail up +Y from `base`, along Z from z0 to z1): one crenellated (z, y) outline
    extruded across the rail with chamfered faces - 4.78 mm teeth at the 10.01 mm pitch over 3.2 mm deep slots."""
    yb, yf, yt = base + 0.0055, base + 0.0062, base + 0.0095
    n = int((z1 - z0 - TOOTH) / PITCH) + 1
    start = z0 + 0.5 * ((z1 - z0) - ((n - 1) * PITCH + TOOTH))
    top = []
    for k in range(n):
        a = start + k * PITCH
        b = a + TOOTH
        top += [(a, yf), (a, yt), (b, yt), (b, yf)]
    outline = [(z1, yb), (z1, yf)] + top[::-1] + [(z0, yf), (z0, yb)]
    # outline must be CCW in (z, y): walk the top from +z to -z
    return sh.extrude_x(np.array(outline), (), width, ((0.0007, 0.0), (0.0, 0.0007)), material)


def rail_spine(z0, z1, base=0.0, material="steel"):
    """The continuous picatinny body under the teeth: the neck and the 45 degree flanks the clamps grip."""
    sec = np.array([(-0.0084, base), (0.0084, base), (0.0084, base + 0.0028), (RAIL_W, base + 0.0050),
                    (RAIL_W, base + 0.0062), (-RAIL_W, base + 0.0062), (-RAIL_W, base + 0.0050),
                    (-0.0084, base + 0.0028)])
    return extrude_z(sec, (), z0, z1, chamfer_bevel(0.0006), material)


def rail(z0, z1, base=0.0, material="steel"):
    return rail_spine(z0, z1, base, material).merge(rail_teeth(z0, z1, base, RAIL_W, material))


def hex_screw(center, normal, r=0.0026, h=0.0011, material="steel_dark", n=12):
    """Socket-head cap screw head standing `h` proud on a face: a domed rim round a real hexagonal socket."""
    normal = np.asarray(normal, float) / np.linalg.norm(normal)
    u = np.cross(normal, (0.0, 1.0, 0.0) if abs(normal[1]) < 0.9 else (1.0, 0.0, 0.0))
    u /= np.linalg.norm(u)
    w = np.cross(normal, u)
    t = np.linspace(0, 2 * np.pi, n, endpoint=False)
    s = 0.42 * r                                                     # socket across corners / 2
    hexagon = []
    for a in t:                                                      # points on the hexagon at the same angles
        k = (a % (np.pi / 3)) - np.pi / 6
        rr = s * np.cos(np.pi / 6) / np.cos(k)
        hexagon.append((rr * np.cos(a), rr * np.sin(a)))
    hexagon = np.array(hexagon)
    circ = np.stack([np.cos(t), np.sin(t)], 1)
    rings = [(hexagon, h - 0.0010), (hexagon, h), (circ * 0.76 * r, h), (circ * r, -0.0004)]
    c = np.asarray(center, float)
    secs = [np.array([c + x * u + y * w + d * normal for x, y in loop]) for loop, d in rings]
    return sh.loft(secs, material)


def qd_cup(center, normal, material="steel_dark"):
    """Flush QD sling cup: a raised bezel ring with the socket bore and its button."""
    normal = np.asarray(normal, float) / np.linalg.norm(normal)
    m = sh.revolve([(0.0033, -0.0022), (0.0033, 0.0005), (0.0060, 0.0005), (0.0066, 0.0001), (0.0066, -0.0022),
                    (0.0033, -0.0022)], 18, material)
    m.merge(sh.disc((0.0, 0.0, -0.0016), (0.0, 0.0, 1.0), 0.0034, 12, material))          # socket floor
    m.merge(sh.revolve([(0.0, -0.0017), (0.0016, -0.0017), (0.0016, -0.0009), (0.0012, -0.0006), (0.0, -0.0006)],
                       8, material))                                                     # its latch button
    z = normal
    x = np.cross((0.0, 1.0, 0.0), z) if abs(z[1]) < 0.9 else np.array([1.0, 0, 0])
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return sh.place(m, origin=center, x_axis=x, y_axis=y, z_axis=z)


# -------------------------------------------------------------------------------------- receiver
def _upper_section():
    """Rounded-top, flat-sided upper with the bolt bore (outer loop, bore hole)."""
    return sh.rounded_rect(-UP_W, UP_Y0, UP_W, UP_Y1, (0.0015, 0.0015, 0.0050, 0.0050), 3)


def _port_section():
    """Section through the ejection port: the right wall cut away from PORT_Y0 to PORT_Y1 into the bolt bore (one
    C-shaped loop: the bore opens to the right)."""
    o = _upper_section()
    xb = np.sqrt(BORE_R ** 2 - PORT_Y0 ** 2)
    xt = np.sqrt(BORE_R ** 2 - PORT_Y1 ** 2)
    a0 = np.arctan2(PORT_Y0, xb)
    a1 = np.arctan2(PORT_Y1, xt) - 2 * np.pi
    arc = [(BORE_R * np.cos(a), BORE_R * np.sin(a)) for a in np.linspace(a0, a1, 22)]
    # outer loop from the port's top edge on the right wall, CCW round the outside, back to its bottom edge
    pts = [p for p in o if not (p[0] > UP_W - 1e-9 and PORT_Y0 < p[1] < PORT_Y1)]
    # rotate the list so it starts at the first point above the port on the right side
    k = next(i for i, p in enumerate(pts) if p[0] > UP_W - 0.002 and p[1] >= PORT_Y1)
    pts = pts[k:] + pts[:k]
    return np.array([(UP_W, PORT_Y1)] + pts + [(UP_W, PORT_Y0), (xb, PORT_Y0)] + arc + [(xt, PORT_Y1)])


def upper_mesh():
    """Aluminium upper: the action housing with the bolt bore, the ejection port (the bolt shows in it), the bolt
    release on the left, the receiver ring screws."""
    bore = sh.circle(0.0, 0.0, BORE_R, 24)[::-1]
    sec = _upper_section()
    m = prism(sec, [bore], 0.0000, PORT_Z0, 0.0010, 0.0, "steel")
    m.merge(prism(_port_section(), (), PORT_Z0, PORT_Z1, 0.0, 0.0, "steel"))
    m.merge(prism(sec, [bore], PORT_Z1, UP_Z1, 0.0, 0.0010, "steel"))
    # bolt release catch on the left rear, with its finger ridges
    m.merge(sh.box(-UP_W - 0.0016, -0.0020, 0.1520, -UP_W + 0.0002, 0.0060, 0.1780, 0.0005, "steel_dark"))
    for k in range(3):
        z = 0.1560 + k * 0.0070
        m.merge(sh.box(-UP_W - 0.0021, -0.0010, z, -UP_W - 0.0010, 0.0050, z + 0.0025, 0.0003, "steel_dark"))
    # the receiver ring screws (both sides, front of the action) and the port's dark bolt-face recess
    for s in (-1, 1):
        m.merge(hex_screw((s * UP_W, -0.0060, 0.0180), (s, 0, 0)))
    return m


def lower_mesh():
    """Chassis lower: magazine well (flared mouth), the tang behind the action (low: the bolt shroud passes over
    it), the trigger guard, the magazine release paddle, the screws and the QD sling cup (left)."""
    m = Mesh()
    sec = sh.rounded_rect(-LOW_W, LOW_Y0, LOW_W, UP_Y0 + 0.0004, (0.0040, 0.0040, 0.0, 0.0), 3)
    core = sh.rounded_rect(-LOW_W + POCKET_D, LOW_Y0, LOW_W - POCKET_D, UP_Y0 + 0.0004, (0.0040, 0.0040, 0.0, 0.0), 3)
    za, zb = POCKETS[0][0] - 0.0200, POCKETS[1][1] + 0.0060
    m.merge(prism(sec, (), 0.0000, za, 0.0012, 0.0, "steel"))
    m.merge(prism(core, (), za, zb, 0.0, 0.0, "steel"))
    m.merge(prism(sec, (), zb, UP_Z1 + 0.0005, 0.0, 0.0012, "steel"))
    # the side skins over the core with the milled pockets (real 1.5 mm deep recesses)
    skin = [(zb, LOW_Y0 + 0.0040), (zb, UP_Y0 + 0.0004), (za, UP_Y0 + 0.0004), (za, LOW_Y0 + 0.0040)]
    holes = [sh.rounded_rect(z0, POCKET_Y[0], z1, POCKET_Y[1], 0.0030, 2) for z0, z1 in POCKETS]
    for s in (-1, 1):
        x0, x1 = sorted((s * (LOW_W - POCKET_D), s * LOW_W))
        m.merge(plate_x(skin, holes, x0, x1, ((0.0005, 0.0), (0.0, 0.0005)), "steel"))
    tang = sh.rounded_rect(-LOW_W, LOW_Y0, LOW_W, TANG_Y1, (0.0040, 0.0040, 0.0022, 0.0022), 3)
    m.merge(extrude_z(tang, (), UP_Z1 - 0.0010, LOW_Z1, chamfer_bevel(0.0012), "steel"))
    # the upper's shoulder steps down to the lower: a chamfered ledge each side of the upper
    for s in (-1, 1):
        x0, x1 = sorted((s * UP_W, s * (LOW_W - 0.0010)))
        m.merge(sh.box(x0, UP_Y0 - 0.0005, 0.0020, x1, UP_Y0 + 0.0016, UP_Z1 - 0.0020, 0.0009, "steel"))
    # magazine well mouth: a flared skirt round the magazine below the lower
    skirt_o = sh.rounded_rect(-MAG_HW - 0.0045, WELL_Z0 - 0.0030, MAG_HW + 0.0045, WELL_Z1 + 0.0010, 0.0045, 3)
    skirt_i = sh.rounded_rect(-MAG_HW - 0.0010, WELL_Z0 + 0.0005, MAG_HW + 0.0010, WELL_Z1 - 0.0005, 0.0025, 3)
    m.merge(_xz_prism(skirt_o, [skirt_i[::-1]], LOW_Y0 - 0.0075, LOW_Y0 + 0.0010, "steel"))
    m.merge(_xz_prism(skirt_i, (), LOW_Y0 - 0.0010, LOW_Y0 + 0.0011, "steel_dark"))          # dark well floor
    # trigger guard: a loop from behind the magazine well to the grip
    gz0, gz1, y0 = GUARD_Z0, GUARD_Z1, LOW_Y0
    out = [(gz0 - 0.0040, y0 + 0.0005), (gz0 - 0.0040, y0 - 0.0060), (gz0 + 0.0030, y0 - 0.0200),
           (gz0 + 0.0180, GUARD_Y + 0.0030), (gz0 + 0.0400, GUARD_Y), (gz1 - 0.0150, GUARD_Y + 0.0020),
           (gz1 - 0.0030, y0 - 0.0170), (gz1 + 0.0040, y0 + 0.0005)]
    inn = [(gz0 + 0.0030, y0 - 0.0010), (gz0 + 0.0030, y0 - 0.0060), (gz0 + 0.0080, y0 - 0.0175),
           (gz0 + 0.0200, GUARD_Y + 0.0085), (gz0 + 0.0390, GUARD_Y + 0.0062), (gz1 - 0.0170, GUARD_Y + 0.0075),
           (gz1 - 0.0080, y0 - 0.0140), (gz1 - 0.0040, y0 - 0.0010)]
    m.merge(sh.extrude_x(chaikin(out, 1, keep=(0, 7)), [chaikin(inn, 1, keep=(0, 7))], 0.0072, bevel_round(0.0018, 1),
                         "steel"))
    # magazine release paddle in front of the guard (pressed forward by the support thumb)
    pad = [(RELEASE_Z - 0.0022, y0 + 0.0005), (RELEASE_Z - 0.0030, y0 - 0.0080), (RELEASE_Z - 0.0010, y0 - 0.0125),
           (RELEASE_Z + 0.0022, y0 - 0.0120), (RELEASE_Z + 0.0022, y0 + 0.0005)]
    m.merge(sh.extrude_x(np.array(pad), (), 0.0085, bevel_round(0.0010, 1), "steel_dark"))
    for k in range(3):                                                             # grip ridges on its front
        y = y0 - 0.0060 - k * 0.0022
        m.merge(sh.box(-0.0080, y - 0.0005, RELEASE_Z - 0.0034, 0.0080, y + 0.0005, RELEASE_Z - 0.0024, 0.0002,
                       "steel_dark"))
    # chassis screws (both sides) and the QD cup on the left
    for s in (-1, 1):
        for z, y in ((0.0170, -0.0300), (0.2420 if s < 0 else 0.2250, -0.0330)):
            m.merge(hex_screw((s * LOW_W, y, z), (s, 0, 0)))
    m.merge(qd_cup(CHARM_POINT + np.array([0.0012, 0.0, 0.0]), (-1, 0, 0)))
    # three-position safety (right side of the tang, behind the bolt handle): base, lever and its knurled tab
    m.merge(sh.revolve([(0.0, 0.0), (0.0042, 0.0), (0.0042, 0.0018), (0.0030, 0.0026), (0.0, 0.0026)], 14,
                       "steel_dark", precision=True).transformed(_frame(SAFETY, (1, 0, 0))))
    x0 = SAFETY[0] + 0.0014
    m.merge(sh.box(x0, SAFETY[1] - 0.0120, SAFETY[2] - 0.0026, x0 + 0.0026, SAFETY[1] + 0.0010, SAFETY[2] + 0.0026,
                   0.0008, "steel_dark"))                                          # the lever, pointing down
    m.merge(sh.box(x0 + 0.0010, SAFETY[1] - 0.0128, SAFETY[2] - 0.0040, x0 + 0.0040, SAFETY[1] - 0.0090,
                   SAFETY[2] + 0.0040, 0.0008, "steel_dark"))                      # its thumb tab
    return m


def _frame(origin, z_axis, up=(0.0, 1.0, 0.0)):
    """4x4 placing a local frame at origin with +Z along z_axis and +Y as close to `up` as possible."""
    z = np.asarray(z_axis, float) / np.linalg.norm(z_axis)
    y = np.asarray(up, float) - z * np.dot(up, z)
    y /= np.linalg.norm(y)
    x = np.cross(y, z)
    M = np.eye(4)
    M[:3, 0], M[:3, 1], M[:3, 2], M[:3, 3] = x, y, z, origin
    return M


def _xz_prism(loop_xz, holes, y0, y1, material):
    """Prism of an (x, z) outline (with holes) from y0 to y1 (extrude_z of (x, -z) turned up)."""
    loop = np.asarray(loop_xz, float)
    m = extrude_z(np.stack([loop[:, 0], -loop[:, 1]], 1),
                  [np.stack([np.asarray(h)[:, 0], -np.asarray(h)[:, 1]], 1) for h in holes], y0, y1,
                  ((0.0, 0.0),), material)
    R = np.eye(4)
    R[:3, :3] = np.array([[1.0, 0, 0], [0, 0, 1.0], [0, -1.0, 0]])   # (x, y, z) -> (x, z, -y)
    return m.transformed(R)


def top_rail_mesh():
    """The one-piece picatinny rail along the whole top: rear of the receiver to the front of the fore-end."""
    m = rail(RAIL_Z0, RAIL_Z1, RAIL_B - 0.0006)
    # rail screws in the slots over the receiver (the rail is screwed to the action)
    for z in (0.0300, 0.1520):
        zc = _slot_centre(z)
        m.merge(sh.revolve([(0.0, 0.0), (0.0030, 0.0), (0.0030, 0.0009), (0.0024, 0.0013), (0.0, 0.0013)], 14,
                           "steel_dark").transformed(_frame((0.0, RAIL_B + 0.0056, zc), (0, 1, 0),
                                                                            up=(0, 0, 1))))
    return m


def _slot_centre(z):
    """Centre of the rail slot nearest z (the top rail's slot grid)."""
    n = int((RAIL_Z1 - RAIL_Z0 - TOOTH) / PITCH) + 1
    start = RAIL_Z0 + 0.5 * ((RAIL_Z1 - RAIL_Z0) - ((n - 1) * PITCH + TOOTH))
    k = round((z - start - TOOTH - 0.5 * (PITCH - TOOTH)) / PITCH)
    return start + k * PITCH + TOOTH + 0.5 * (PITCH - TOOTH)


# ---------------------------------------------------------------------------------------- fore-end
def _fe_section(inflate=0.0):
    """CCW (x, y) outer octagon of the fore-end (corners broken)."""
    w, y0, s0, s1, tw, top = FE_W + inflate, FE_Y0 - inflate, FE_SIDE0, FE_SIDE1, FE_TOP_W, RAIL_B
    pts = [(-0.0150, y0), (0.0150, y0), (w, s0), (w, s1), (tw, top), (-tw, top), (-w, s1), (-w, s0)]
    return chaikin(pts, 1)


def _fe_band_pieces():
    """In the vent zone the side walls are the vent plates: the fore-end shell splits into a top piece (above the
    plates, over the channel) and a bottom piece (below them, under the channel)."""
    o = _fe_section()
    xt = np.sqrt(CHANNEL_R ** 2 - VENT_Y1 ** 2)
    top = [p for p in o if p[1] > VENT_Y1 + 1e-9]
    top = sorted(top, key=lambda p: -np.arctan2(p[1] - 0.0, p[0]))       # right to left over the top
    arc_t = [(CHANNEL_R * np.cos(a), CHANNEL_R * np.sin(a))
             for a in np.linspace(np.pi - np.arcsin(VENT_Y1 / CHANNEL_R), np.arcsin(VENT_Y1 / CHANNEL_R), 16)]
    top_loop = [(FE_W, VENT_Y1)] + top + [(-FE_W, VENT_Y1), (-xt, VENT_Y1)] + arc_t + [(xt, VENT_Y1)]
    bot = [p for p in o if p[1] < VENT_Y0 - 1e-9]
    bot = sorted(bot, key=lambda p: np.arctan2(p[1], p[0]))              # left to right under the bottom
    # the channel's lowest point is above VENT_Y0: the bottom piece is solid under it
    bot_loop = [(-FE_W, VENT_Y0)] + bot + [(FE_W, VENT_Y0)]
    return np.array(top_loop), np.array(bot_loop)


def foreend_mesh():
    """Octagonal aluminium fore-end round the free-floated barrel: nose ring, vent plates with through slots, the
    short side rails and the bottom rail at the front, a sling QD cup and the screws to the chassis."""
    channel = sh.circle(0.0, 0.0, CHANNEL_R, 28)[::-1]
    o = _fe_section()
    m = Mesh()
    # nose segment (chamfered front ring round the channel)
    m.merge(prism(o, [channel], FE_Z0, VENT_Z0, 0.0016, 0.0, "handguard"))
    # vent zone: top and bottom pieces, the plates with their through slots
    top, bot = _fe_band_pieces()
    m.merge(extrude_z(top, (), VENT_Z0, VENT_Z1, ((0.0, 0.0),), "handguard"))
    m.merge(extrude_z(bot, (), VENT_Z0, VENT_Z1, ((0.0, 0.0),), "handguard"))
    plate = [(VENT_Z1, VENT_Y0), (VENT_Z1, VENT_Y1), (VENT_Z0, VENT_Y1), (VENT_Z0, VENT_Y0)]
    slots = [slot_zy(VENT_Z0 + 0.0130 + k * 0.0450, VENT_Z0 + 0.0130 + k * 0.0450 + 0.0330, -0.0090, 0.0105, 5)
             for k in range(5)]
    for s in (-1, 1):
        x0, x1 = sorted((s * FE_W, s * (FE_W - VENT_T)))
        m.merge(plate_x(plate, slots, x0, x1, ((0.0006, 0.0), (0.0, 0.0006)), "handguard"))
    # rear segment to the chassis
    m.merge(prism(o, [channel], VENT_Z1, 0.0000, 0.0, 0.0008, "handguard"))
    # side rails (3 and 9 o'clock) and the bottom rail at the front
    for s in (-1, 1):
        R = np.eye(4)
        R[:3, :3] = np.array([[0, s * 1.0, 0], [-s * 1.0, 0, 0], [0, 0, 1.0]])   # local +Y -> world s*X
        R[:3, 3] = (0.0, SIDE_RAIL_Y, 0.0)
        r = rail(SIDE_RAIL_Z[0], SIDE_RAIL_Z[1], FE_W - 0.0006, "handguard")
        m.merge(r.transformed(R))
    Rb = np.eye(4)
    Rb[:3, :3] = np.array([[-1.0, 0, 0], [0, -1.0, 0], [0, 0, 1.0]])            # local +Y -> world -Y
    m.merge(rail(BOT_RAIL_Z[0], BOT_RAIL_Z[1], -FE_Y0 - 0.0006, "handguard").transformed(Rb))
    # screws: fore-end to chassis (both sides, rear), the side rail screws
    for s in (-1, 1):
        m.merge(hex_screw((s * FE_W, (FE_SIDE0 + FE_SIDE1) / 2, -0.0140), (s, 0, 0), r=0.0022))
    m.merge(qd_cup((-FE_W - 0.0004, -0.0090, -0.0130 - 0.0100), (-1, 0, 0)))
    return m


def _ring_face(outer, hole, z, material):
    """Flat face at z (facing -Z) between an outer loop and a hole."""
    mm = Mesh()
    sh.cap_polygon(mm, _ring_xy(sh.cw(outer), z), material, holes3d=[_ring_xy(sh.ccw(hole), z)])
    # make sure it faces -Z
    v = np.array(mm.v)
    f = mm.f[0]
    nrm = np.cross(v[f[1]] - v[f[0]], v[f[2]] - v[f[0]])
    if nrm[2] > 0:
        mm.f = [tuple(reversed(ff)) for ff in mm.f]
    return mm


# ---------------------------------------------------------------------------------- barrel, brake
def _flute_section(r, depth, n=6, count=48, width=0.42):
    """CCW (x, y) barrel section of radius r with n rounded flutes `depth` deep (width = share of the pitch)."""
    a = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / n
    ph = (a * n / (2 * np.pi)) % 1.0
    u = np.clip((np.abs(ph - 0.5) - (0.5 - width / 2)) / (width / 2), -1, 0)
    rr = r - depth * np.sqrt(np.clip(1 - (1 + u) ** 2, 0, 1)) * (u > -1.0001) * (u < 0)
    return np.stack([rr * np.cos(a), rr * np.sin(a)], 1)


def barrel_mesh():
    """Fluted heavy barrel from inside the receiver ring to the threaded crown (the brake is its own node)."""
    def r_at(z):
        return BARREL_R0 + (BARREL_R1 - BARREL_R0) * (z / CROWN_Z)
    zs = [-0.0020, -0.0200, FLUTE_Z1 + 0.010, FLUTE_Z1, FLUTE_Z1 - 0.008]
    zs += list(np.linspace(FLUTE_Z1 - 0.030, FLUTE_Z0 + 0.030, 4))
    zs += [FLUTE_Z0 + 0.008, FLUTE_Z0, FLUTE_Z0 - 0.010, CROWN_Z + 0.0120]
    rings = []
    for z in zs:
        inside = FLUTE_Z0 + 0.006 <= z <= FLUTE_Z1 - 0.006
        edge = (FLUTE_Z0 <= z <= FLUTE_Z1) and not inside
        depth = 0.0024 if inside else (0.0010 if edge else 0.0)
        rings.append(_ring_xy(_flute_section(r_at(z), depth), z))
    m = sh.loft(rings[::-1], "barrel", cap_start=False, auto_orient=False)    # crown end open: the tenon joins
    # shoulder and the threaded tenon under the brake (the suppressor threads on here)
    r1 = r_at(CROWN_Z + 0.012)
    m.merge(sh.revolve([(r1, CROWN_Z + 0.0120), (r1 - 0.0010, CROWN_Z + 0.0110), (0.0080, CROWN_Z + 0.0110),
                        (0.0080, CROWN_Z + 0.0004), (0.0074, CROWN_Z), (0.0040, CROWN_Z), (0.0040, CROWN_Z + 0.004),
                        (0.0, CROWN_Z + 0.004)], 24, "barrel"))
    return m


def _annulus_halves(r_out, r_in, half_gap, seg=32):
    """Upper and lower crescents of an annulus cut by two side ports (|y| < half_gap opened on both sides)."""
    out = []
    for s in (1, -1):
        ao = np.arcsin(half_gap / r_out)
        a_out = np.linspace(ao, np.pi - ao, seg // 2) if s > 0 else np.linspace(np.pi + ao, 2 * np.pi - ao, seg // 2)
        loop = [(r_out * np.cos(a), r_out * np.sin(a)) for a in a_out]
        if half_gap < r_in:                          # the bridge keeps a piece of the bore wall
            ai = np.arcsin(half_gap / r_in)
            a_in = np.linspace(np.pi - ai, ai, seg // 4) if s > 0 else np.linspace(2 * np.pi - ai, np.pi + ai, seg // 4)
            loop += [(r_in * np.cos(a), r_in * np.sin(a)) for a in a_in]
        out.append(np.array(loop))
    return out


def muzzle_brake_mesh():
    """Three-chamber muzzle brake (node `muzzle_brake`: hidden while the barrel slot holds something): a jam-nut
    collar at the rear, three through side ports per side (the bore and the baffles show in them), the flat-faced
    front with the bore and a chamfered crown edge."""
    z1, z0 = CROWN_Z + 0.0115, MUZZLE_Z
    bore = sh.circle(0.0, 0.0, 0.0058, 12)[::-1]
    disc = sh.circle(0.0, 0.0, BRAKE_R, 24)
    m = Mesh()
    # rear collar (jam nut with wrench flats)
    m.merge(sh.revolve([(0.0080, z1), (BRAKE_R - 0.0008, z1), (BRAKE_R, z1 - 0.0008), (BRAKE_R, z1 - 0.0090),
                        (BRAKE_R - 0.0008, z1 - 0.0098), (0.0080, z1 - 0.0098)], 24, "barrel"))
    for s in (-1, 1):
        m.merge(sh.box(-0.0060, s * BRAKE_R - 0.0007 if s > 0 else -BRAKE_R - 0.0003, z1 - 0.0085, 0.0060,
                       s * BRAKE_R + 0.0003 if s > 0 else -BRAKE_R + 0.0007, z1 - 0.0013, 0.0003, "barrel"))
    zz = z1 - 0.0098
    port_len, wall = 0.0115, 0.0055
    for k in range(3):
        za = zz - wall
        m.merge(extrude_z(disc, [bore], za, zz, ((0.0, 0.0),), "barrel"))             # baffle wall
        zb = za - port_len
        for half in _annulus_halves(BRAKE_R, 0.0058, 0.0068, seg=24):                          # port: top / bottom bridges
            m.merge(extrude_z(half, (), zb, za, ((0.0, 0.0),), "barrel"))
        zz = zb
    # front cap: down to the muzzle face with a chamfer
    m.merge(extrude_z(disc, [bore], z0 + 0.0012, zz, ((0.0, 0.0),), "barrel"))
    m.merge(sh.revolve([(0.0058, z0 + 0.0012), (BRAKE_R, z0 + 0.0012), (BRAKE_R - 0.0012, z0),
                        (0.0068, z0), (0.0058, z0 + 0.0010)], 24, "barrel"))
    # the bore inside (dark), seen through the ports and the muzzle
    m.merge(sh.revolve([(0.0040, z1), (0.0040, z0 + 0.0015)], 16, "steel_dark", outward=False, precision=True))
    return m


# ------------------------------------------------------------------------------------------- stock
def stock_mesh():
    """Skeletal folding stock (aluminium): the frame with its big opening, the hinge block and knuckles (it folds to
    the right), the cheek piece riser rail, the butt plate carrier with its adjustment slots, the QD cup and the
    folding button."""
    z0 = STOCK_Z0
    outer = [(z0, TANG_Y1 + 0.0002), (z0, LOW_Y0 - 0.0100), (z0 + 0.0250, -0.0700), (0.5050, -0.1260),
             (0.5420, -0.1380), (BUTT_Z, -0.1380), (BUTT_Z, 0.0110), (BUTT_Z - 0.0080, 0.0170), (0.3900, 0.0170),
             (0.3700, 0.0090), (0.3470, -0.0080), (0.3380, TANG_Y1 + 0.0002)]
    hole = [(z0 + 0.0350, -0.0420), (z0 + 0.0560, -0.0580), (0.4950, -0.1080), (0.5300, -0.1170),
            (BUTT_Z - 0.0200, -0.1120), (BUTT_Z - 0.0200, -0.0040), (BUTT_Z - 0.0280, 0.0010), (0.3980, 0.0010),
            (0.3700, -0.0110), (0.3450, -0.0280), (z0 + 0.0480, -0.0310)]
    m = sh.extrude_x(chaikin(outer, 2, keep=(0, 1, 5, 6)), [chaikin(hole, 2)], STOCK_HW, bevel_round(0.0040, 3),
                     "stock")
    # hinge block on the front (wider than the frame) with the knuckles on the right
    blk = sh.rounded_rect(-LOW_W + 0.0010, LOW_Y0 + 0.0010, LOW_W - 0.0010, TANG_Y1 - 0.0010, 0.0035, 2)
    m.merge(extrude_z(blk, (), z0, z0 + 0.0180, chamfer_bevel(0.0012), "stock"))
    for k, (y0, y1) in enumerate(((LOW_Y0 + 0.0020, -0.0380), (-0.0360, -0.0280), (-0.0260, TANG_Y1 - 0.0020))):
        mat = "steel_dark" if k == 1 else "stock"
        kn = sh.revolve([(0.0, y0), (0.0052, y0), (0.0058, y0 + 0.0006), (0.0058, y1 - 0.0006), (0.0052, y1),
                         (0.0, y1)], 12, mat)
        kn = sh.place(kn, x_axis=(0, 0, 1), y_axis=(1, 0, 0), z_axis=(0, 1, 0))
        m.merge(kn.translated((HINGE[0], 0.0, HINGE[2])))
    m.merge(sh.revolve([(0.0, 0.0), (0.0040, 0.0), (0.0045, 0.0012), (0.0, 0.0016)], 14, "steel_dark")
            .transformed(_frame((-LOW_W + 0.0010, -0.0330, z0 + 0.0090), (-1, 0, 0))))
    # cheek riser: a flat-topped bar on the top beam the posts stand in
    m.merge(sh.box(-0.0085, 0.0150, CHEEK_Z0 - 0.0030, 0.0085, 0.0185, CHEEK_Z1 - 0.0080, 0.0010, "stock"))
    # butt plate carrier: the vertical plate with the two adjustment slots and their clamp screws
    plate = [(BUTT_Z + 0.0080, -0.1390), (BUTT_Z + 0.0080, 0.0200), (BUTT_Z, 0.0200), (BUTT_Z, -0.1390)]
    m.merge(plate_x(plate, (), -0.0160, 0.0160, bevel_round(0.0020, 2), "stock"))
    for s in (-1, 1):
        m.merge(hex_screw((s * 0.0160, -0.0600, BUTT_Z + 0.0040), (s, 0, 0), r=0.0020, h=0.0009))
    for s in (-1, 1):                                                       # the side slots of the adjustment
        x0, x1 = sorted((s * 0.0160, s * 0.0166))
        sl = slot_zy(-0.0900, -0.0300, 0.0, 0.0040, 4)
        sl = np.stack([np.full(len(sl), BUTT_Z + 0.0040) + sl[:, 1], sl[:, 0]], 1)
        m.merge(plate_x(sl, (), x0, x1, ((0.0, 0.0),), "steel_dark"))
    # QD cup under the rear of the stock
    m.merge(qd_cup((0.0, -0.1380 - 0.0004, 0.5300), (0, -1, 0)))
    return m


def cheek_mesh():
    """Adjustable cheek piece: a polymer comb on two steel posts, its height wheel on the left."""
    c0, c1 = CHEEK_Z0, CHEEK_Z1
    prof = [(c0, COMB_Y - 0.0180), (c1, COMB_Y - 0.0180), (c1, COMB_Y - 0.0030), (c1 - 0.0100, COMB_Y),
            (c0 + 0.0250, COMB_Y), (c0 + 0.0040, COMB_Y - 0.0060), (c0, COMB_Y - 0.0110)]
    m = sh.extrude_x(chaikin(prof, 1, keep=(0, 1)), (), 0.0165, bevel_round(0.0045, 3), "cheek")
    for z in (c0 + 0.0280, c1 - 0.0300):                                           # the posts
        post = sh.revolve([(0.0, 0.0175), (0.0032, 0.0175), (0.0032, COMB_Y - 0.0140), (0.0, COMB_Y - 0.0140)],
                          12, "steel_dark")
        post = sh.place(post, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))
        m.merge(post.translated((0.0, 0.0, z)))
    # height wheel (knurled) on the left of the riser
    wheel = sh.revolve([(0.0, 0.0), (0.0085, 0.0), (0.0090, 0.0010), (0.0090, 0.0060), (0.0085, 0.0070),
                        (0.0, 0.0070)], 20, "steel_dark")
    m.merge(wheel.transformed(_frame((-0.0110, 0.0110, 0.5 * (c0 + c1)), (-1, 0, 0))))
    for k in range(10):                                                            # knurl ridges
        a = 2 * np.pi * k / 10
        rib = sh.box(-0.0007, -0.0005, 0.0010, 0.0007, 0.0005, 0.0060, 0.0, "steel_dark")
        rib = rib.translated((0.0, 0.0090, 0.0))
        c, s = np.cos(a), np.sin(a)
        R = np.eye(4)
        R[:3, :3] = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])
        m.merge(rib.transformed(_frame((-0.0110, 0.0110, 0.5 * (c0 + c1)), (-1, 0, 0)) @ R))
    return m


def pad_mesh():
    """Rubber butt pad: concave at the shoulder, ribbed face, rounded toe and heel."""
    z0 = BUTT_Z + 0.0080
    prof = [(z0, -0.1420), (z0 + 0.0170, -0.1400), (PAD_Z1, -0.1250), (PAD_Z1 - 0.0045, -0.0600),
            (PAD_Z1 - 0.0040, -0.0100), (PAD_Z1, 0.0130), (z0 + 0.0160, 0.0240), (z0, 0.0240)]
    m = sh.extrude_x(chaikin(prof, 1, keep=(0, 7)), (), 0.0195, bevel_round(0.0055, 3), "pad")
    for k in range(6):
        y = -0.1080 + k * 0.0210
        zf = PAD_Z1 - 0.0045 + 0.0004 * abs(k - 2.5)
        m.merge(sh.box(-0.0140, y, zf - 0.0010, 0.0140, y + 0.0035, zf + 0.0008, 0.0, "pad"))
    return m


def grip_mesh():
    """Ergonomic polymer pistol grip, near vertical: a palm swell on the right, three finger grooves on the front
    strap, a thumb rest on the left, a flared base; the side panels are stippled (texture set)."""
    rings = []
    n = 22
    for k in range(n + 1):
        u = k / n
        d = -0.0060 + (GRIP_LEN + 0.0080) * u
        c = grip_center(d)
        flare = np.clip((u - 0.80) / 0.20, 0, 1) ** 2
        depth = 0.0215 + 0.0020 * np.sin(np.pi * u) + 0.0030 * flare
        w = 0.0142 + 0.0016 * np.sin(np.pi * min(1.0, u * 1.2)) + 0.0020 * flare
        groove = 0.0018 * max(0.0, np.sin(np.pi * 3 * (u - 0.22) / 0.62)) if 0.22 < u < 0.84 else 0.0
        sec = sh.superellipse(w, depth, 2.6, 40, n_front=2.6)
        pts = []
        for x, y in sec:
            yy = y - (groove if y > 0.4 * depth else 0.0)                      # finger grooves on the front strap
            xx = x * (1.06 if x > 0 and abs(y) < 0.6 * depth else 1.0)          # palm swell (right)
            pts.append(c + xx * np.array([1.0, 0, 0]) + yy * GRIP_FRONT)
        rings.append(np.array(pts))
    m = sh.loft(rings, "grip")
    # base plug
    b = grip_center(GRIP_LEN + 0.0020)
    cap = sh.superellipse(0.0150, 0.0230, 2.6, 22)
    ring = [np.array([b + x * np.array([1.0, 0, 0]) + y * GRIP_FRONT for x, y in cap]),
            np.array([b + GRIP_AXIS * 0.0035 + 0.92 * x * np.array([1.0, 0, 0]) + 0.92 * y * GRIP_FRONT
                      for x, y in cap])]
    m.merge(sh.loft(ring, "grip"))
    # thumb rest ledge on the left at the top
    t0 = grip_center(0.0080)
    ledge = sh.box(-0.0050, -0.0025, -0.0170, 0.0050, 0.0025, 0.0150, 0.0015, "grip")
    m.merge(ledge.transformed(_frame(t0 + np.array([-0.0175, 0.0, 0.0]), -GRIP_FRONT, up=-GRIP_AXIS)))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_shroud_mesh():
    """Non-rotating bolt shroud behind the handle with the cocking indicator (node `bolt`, bolt space at rest)."""
    z0, z1 = SHROUD_Z0, SHROUD_Z1
    m = sh.revolve([(0.0, z0), (0.0118, z0), (0.0128, z0 + 0.0010), (0.0128, z0 + 0.0220), (0.0112, z1 - 0.0040),
                    (0.0090, z1), (0.0, z1)], 28, "steel_dark")
    m.merge(_cyl_z(0.0028, z1 - 0.0010, z1 + 0.0055, "steel_dark", 12, chamfer=0.0008))   # cocking indicator
    m.merge(sh.box(-0.0035, 0.0100, z0 + 0.0030, 0.0035, 0.0132, z0 + 0.0220, 0.0008, "steel_dark"))   # top fin
    return m


def bolt_body_mesh():
    """Rotating bolt (node `bolt_handle`, bolt space at rest: the handle points right and HANDLE_DROP degrees down):
    the body with three locking lugs and the extractor, the handle collar, the stem swept back a little and the
    large round knob with its grip rings."""
    m = sh.revolve([(0.0, BOLT_FACE), (BOLT_R - 0.0010, BOLT_FACE), (BOLT_R, BOLT_FACE + 0.0010),
                    (BOLT_R, SHROUD_Z0 + 0.0005), (0.0, SHROUD_Z0 + 0.0005)], 24, "steel_dark")
    for k in range(3):                                                             # locking lugs at the front
        a = np.radians(90.0 + 120.0 * k)
        lug = sh.box(-0.0034, BOLT_R - 0.0020, BOLT_FACE + 0.0005, 0.0034, BOLT_R + 0.0024, BOLT_FACE + 0.0160,
                     0.0006, "steel_dark")
        R = np.eye(4)
        R[:3, :3] = np.array([[np.cos(a - np.pi / 2), -np.sin(a - np.pi / 2), 0],
                              [np.sin(a - np.pi / 2), np.cos(a - np.pi / 2), 0], [0, 0, 1.0]])
        m.merge(lug.transformed(R))
    m.merge(sh.box(BOLT_R - 0.0010, -0.0018, BOLT_FACE + 0.0005, BOLT_R + 0.0006, 0.0018, BOLT_FACE + 0.0280, 0.0004,
                   "steel_dark"))                                                  # extractor
    # handle collar round the rear of the body
    m.merge(sh.revolve([(0.0, HANDLE_Z - 0.0075), (0.0104, HANDLE_Z - 0.0075), (0.0116, HANDLE_Z - 0.0063),
                        (0.0116, HANDLE_Z + 0.0063), (0.0104, HANDLE_Z + 0.0075), (0.0, HANDLE_Z + 0.0075)], 28,
                       "steel_dark"))
    a = np.radians(-HANDLE_DROP)
    root = np.array([0.0110 * np.cos(a), 0.0110 * np.sin(a), HANDLE_Z])
    knob = knob_rest()
    stem_len = float(np.linalg.norm(knob - root))
    stem = sh.revolve([(0.0, -0.0010), (0.0056, -0.0010), (0.0050, 0.0080), (0.0040, stem_len - 0.0090),
                       (0.0042, stem_len - 0.0040), (0.0, stem_len - 0.0040)], 16, "steel_dark")
    z_ax = (knob - root) / stem_len
    x_ax = np.cross((0.0, 0.0, 1.0), z_ax)
    x_ax /= np.linalg.norm(x_ax)
    y_ax = np.cross(z_ax, x_ax)
    m.merge(sh.place(stem, origin=root, x_axis=x_ax, y_axis=y_ax, z_axis=z_ax))
    # the knob: a sphere a little flattened on its end, two grip rings round its equator
    prof = [(0.0, -KNOB_R * 0.88)]
    for t in np.linspace(0.42, np.pi - 0.30, 8):
        prof.append((KNOB_R * np.sin(t), -KNOB_R * np.cos(t)))
    prof.append((KNOB_R * 0.36, KNOB_R * 0.97))
    prof.append((0.0, KNOB_R * 0.97))
    ball = sh.revolve(prof, 30, "steel_dark")
    m.merge(sh.place(ball, origin=knob, x_axis=x_ax, y_axis=y_ax, z_axis=z_ax))
    for zz in (-0.0020, 0.0024):
        r = np.sqrt(KNOB_R ** 2 - zz ** 2)
        groove = sh.revolve([(r + 0.0001, zz - 0.0007), (r - 0.0006, zz), (r + 0.0001, zz + 0.0007)], 30,
                            "steel_dark")
        m.merge(sh.place(groove, origin=knob, x_axis=x_ax, y_axis=y_ax, z_axis=z_ax))
    return m


def knob_rest():
    """Centre of the bolt knob in bolt space at rest (handle down)."""
    a = np.radians(-HANDLE_DROP)
    return np.array([HANDLE_LEN * np.cos(a), HANDLE_LEN * np.sin(a), HANDLE_Z + HANDLE_SWEEP * HANDLE_LEN])


def trigger_mesh():
    """Wide, flat-faced adjustable trigger shoe (rotates about X at its pivot)."""
    p = TRIGGER_PIVOT
    blade = [(p[2] - 0.0040, p[1] + 0.0030), (p[2] + 0.0030, p[1] + 0.0030), (p[2] + 0.0034, p[1] - 0.0050),
             (p[2] + 0.0018, p[1] - 0.0130), (p[2] - 0.0010, p[1] - 0.0190), (p[2] - 0.0042, p[1] - 0.0215),
             (p[2] - 0.0062, p[1] - 0.0205), (p[2] - 0.0048, p[1] - 0.0160), (p[2] - 0.0032, p[1] - 0.0095),
             (p[2] - 0.0030, p[1] - 0.0020)]
    m = sh.extrude_x(chaikin(blade, 1, keep=(0, 1)), (), 0.0045, bevel_round(0.0012, 2), "steel_dark")
    return m


# --------------------------------------------------------------------------------------- magazine
def _mag_prism(sec_xz, y0, y1, material):
    rings = [np.stack([sec_xz[:, 0], np.full(len(sec_xz), y), sec_xz[:, 1]], 1) for y in (y0, y1)]
    return sh.loft(rings, material)


def mag_mesh():
    """Detachable 10-round polymer box magazine (mag-local: top centre at the origin, +Y up into the well, -Z front):
    ribbed sides, the witness window strip, the latch notch at the rear, a floor plate with its grip ledge and the
    top .308 round in the feed lips."""
    def sec(inflate=0.0, r=0.0035):
        return sh.rounded_rect(-MAG_HW - inflate, -MAG_HL - inflate, MAG_HW + inflate, MAG_HL + inflate, r, 3)
    body = sec()
    rings = []
    for y, inf in ((-MAG_H + 0.0090, 0.0), (-0.0080, 0.0), (-0.0020, -0.0006), (0.0, -0.0012)):
        s = sh.rounded_rect(-MAG_HW - inf, -MAG_HL - inf, MAG_HW + inf, MAG_HL + inf, 0.0035, 3)
        rings.append(np.stack([s[:, 0], np.full(len(s), y), s[:, 1]], 1))
    m = sh.loft(rings, "mag")
    del body
    for s in (-1, 1):                                                              # moulded side ribs
        x0, x1 = sorted((s * MAG_HW, s * (MAG_HW + 0.0008)))
        for z in (-0.0260, -0.0090, 0.0080, 0.0250):
            m.merge(sh.box(x0, -MAG_H + 0.0180, z - 0.0018, x1, -0.0300, z + 0.0018, 0.0, "mag"))
    m.merge(sh.box(-0.0040, -0.0400, MAG_HL - 0.0004, 0.0040, -0.0300, MAG_HL + 0.0010, 0.0004, "mag"))  # latch
    # floor plate, a little proud all round, the grip ledge at its front
    plate = sh.rounded_rect(-MAG_HW - 0.0016, -MAG_HL - 0.0022, MAG_HW + 0.0016, MAG_HL + 0.0016, 0.0045, 3)
    rings = [np.stack([plate[:, 0], np.full(len(plate), y), plate[:, 1]], 1) for y in (-MAG_H, -MAG_H + 0.0090)]
    m.merge(sh.loft(rings, "mag_base"))
    m.merge(sh.box(-MAG_HW - 0.0010, -MAG_H - 0.0010, -MAG_HL - 0.0052, MAG_HW + 0.0010, -MAG_H + 0.0060,
                   -MAG_HL - 0.0010, 0.0010, "mag_base"))
    # feed lips and the top round (.308 Win: rim to the rear, bullet forward)
    for s in (-1, 1):
        x0, x1 = sorted((s * (MAG_HW - 0.0016), s * 0.0046))
        m.merge(sh.box(x0, -0.0020, -MAG_HL + 0.0060, x1, 0.0012, MAG_HL - 0.0090, 0.0005, "mag"))
    rnd = sh.revolve([(0.0, 0.0), (0.0059, 0.0), (0.0060, -0.0012), (0.0051, -0.0016), (0.0051, -0.0026),
                      (0.0059, -0.0034), (0.0058, -0.0390), (0.0046, -0.0435), (0.0043, -0.0512),
                      (0.0039, -0.0540), (0.0030, -0.0600), (0.0015, -0.0670), (0.0, -0.0695)], 14, "brass")
    m.merge(rnd.translated((0.0, -0.0046, MAG_HL - 0.0030)))
    return m


def shell_mesh(scale=1.0):
    """Spent .308 Winchester case (material `brass`) along Z, mouth towards -Z (51 mm long)."""
    prof = [(0.0, 0.0255), (0.0055, 0.0255), (0.0060, 0.0248), (0.0060, 0.0236), (0.0052, 0.0230),
            (0.0052, 0.0218), (0.0059, 0.0210), (0.0058, -0.0136), (0.0046, -0.0181), (0.0043, -0.0255),
            (0.0039, -0.0255), (0.0039, -0.0230), (0.0, -0.0230)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 14, "brass")
