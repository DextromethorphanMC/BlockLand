"""Pancor Jackhammer geometry, VIEWMODEL_SPEC section 40. Design frame (metres): +X right, +Y up, +Z rearward; bore
axis on x = y = 0, z = 0 at the front face of the cassette bay (the breech). Real-world scale: overall 0.787 m,
barrel 0.525 m (cassette face to the flash hider's tip), measured off the reference photo (left side, 0.426 mm/px).

An original procedural model: a 12-gauge full-auto bullpup revolver. From the muzzle back: the duckbill flash hider
on a clamp collar with the front sling loop, the dark steel barrel, the operating sleeve (forend) that slides back on
the cocking stroke over the receiver's nose, the vented receiver (two rows of round vents in its side plates, the
barrel showing through them) on a lower receiver whose fixed rail runs on under the forend, the long carrying handle
with its oval and three round cut-outs, the sights and a short picatinny section on its top, the trigger housing with a
round guard opening, the rotary selector and the safety, the open-frame pistol grip with its rubber panels, the front
cup of the cassette bay, the 10-round rotary Ammo-Cassette (its outer surface carries the zig-zag cam groove that
indexes it), the top shroud over the bay, the rear cup and the square butt with its window, rubber pad and sling studs.
Every builder returns a meshkit.Mesh in the design frame; gen_jackhammer moves meshes into node-local space."""
import numpy as np

import shapes as sh
from ak_model import _cyl_x, _cyl_z
from meshkit import Mesh
from model_quality import arc_segments
from p9_model import chamfer_bevel, extrude_z

S = 0.000426                       # metres per pixel of the reference photo

# ---- key dimensions ---------------------------------------------------------------------------
TOP = 0.0340                       # receiver top (the top line of the gun)
REC_W = 0.0250                     # receiver half width (upper: side plates' outer faces)
REC_Z0, REC_Z1 = -0.2170, -0.0060  # receiver front face / rear end (inside the front cup)
REC_BOT = -0.0510                  # lower receiver bottom
SEAM_Y = -0.0095                   # seam between the vented upper and the lower receiver
CAP_Y = 0.0255                     # underside of the receiver's top cap
PLATE_T = 0.0025                   # vented side plates
LOW_W = 0.0245                     # lower receiver half width
VENT_ROWS = ((0.0200, 0.0030), (-0.0020, 0.0034))     # (y, radius) of the two vent rows
VENT_Z = tuple(-0.0680 - k * 0.01625 for k in range(9))
STRIP_Z1 = -0.3080                 # front end of the fixed rail under the forend
STRIP_Y0, STRIP_Y1, STRIP_W = -0.0565, -0.0449, 0.0200

CAS_X, CAS_Y = 0.0, -0.0425        # cassette axis (the top chamber lines up with the bore)
CAS_Z0, CAS_Z1 = 0.0120, 0.1020    # cassette front / rear face
CAS_C = np.array([CAS_X, CAS_Y, 0.5 * (CAS_Z0 + CAS_Z1)])        # the `mag` pivot: the cassette's centre
CAS_R, CAS_RG = 0.0545, 0.0520     # land radius / bottom of the zig-zag groove
CHAMBERS, CHAMBER_RC, CHAMBER_R = 10, 0.0425, 0.0110
GROOVE_W = 0.0050                  # cam groove width
CAM_Z0, CAM_Z1 = 0.0300, 0.0860    # the groove's front and rear turning points
INDEX = 360.0 / CHAMBERS           # one chamber per shot

RING_R0, RING_R1 = 0.0480, 0.0600  # front cup of the bay (receiver)
RING_Z0, RING_Z1 = -0.0120, 0.0100
SHROUD_R, SHROUD_RI = 0.0605, 0.0565
SHROUD_Z0, SHROUD_Z1 = -0.0080, 0.1100
SHROUD_TOP = 0.0345
WINDOW_Y = -0.0094                 # the bay's side windows start under the shroud
STOCK_W = 0.0300
CUP_R, CUP_Z0, CUP_Z1 = 0.0612, 0.1040, 0.1160
BUTT_Z, PAD_Z = 0.2300, 0.2394    # stock end / rear face of the rubber pad
BUTT_BOT = -0.0916

BARREL_R, BARREL_IN_R = 0.0195, 0.0165
COLLAR_Z0, CROWN_Z = -0.4300, -0.4520
COLLAR_R = 0.0280
MUZZLE_Z = -0.5474                 # tip of the flash hider
HIDER_R, HIDER_RI = 0.0265, 0.0105
HIDER_STEP = -0.4900               # where the duckbill cut starts

FE_Z0, FE_Z1 = -0.2150, -0.3855    # forend (operating sleeve) rear / front at the top
FE_TOP, FE_BOT, FE_W = 0.0107, -0.0447, 0.0285
FE_WALL = 0.0030
FE_WEB_Z = -0.2620                 # the forend is a pair of side walls behind this, solid in front of it
BOLT_TRAVEL = 0.0320               # cocking stroke (+Z) of the operating sleeve

HANDLE_W = 0.0115
HANDLE_TOP = 0.0810
RAIL_Z0, RAIL_Z1 = -0.0625, -0.2000
RAIL_TOP, RAIL_W = 0.0845, 0.0106
OPTIC_Z = -0.0820                  # optic base centre on the rail
SIGHT_Y = 0.0955                   # sight line: rear notch bottom / front post top
REAR_SIGHT_Z = -0.0575             # rear face of the notch plate
FRONT_SIGHT_Z = -0.3050

GRIP_ANGLE = 30.0                  # the grip's axis leans back this much from vertical
GRIP_TOP = np.array([0.0, -0.0810, -0.1060])
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])
GRIP_LEN = 0.0640
FRAME_W = 0.0120                   # lower frame (trigger housing, grip, strut, bottom bar) half width
TRIGGER_PIVOT = np.array([0.0, -0.0505, -0.1585])
SELECTOR_C = np.array([-FRAME_W, -0.0640, -0.1415])
LATCH_PIVOT = np.array([-FRAME_W, -0.1060, -0.0090])   # cassette latch on the strut's left side
EJECTION = np.array([REC_W + 0.0005, 0.0040, -0.0300])   # (the revolver keeps its hulls; effects origin only)
CHARM_POINT = np.array([-LOW_W - 0.0010, -0.0440, -0.0340])
LASER_POINT = np.array([FE_W + 0.0015, -0.0200, -0.3620])     # forend space == design frame at rest
UNDER_POINT = np.array([0.0, FE_BOT, -0.3640])


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def soft_bevel(r):
    """Two-step rounded edge for extrusions (a cheap fillet: the outline, 45 degrees, the face)."""
    return [(r, 0.0), (0.293 * r, 0.293 * r), (0.0, r)]


def fillet(pts, radii, seg=3):
    """Closed 2D polygon with its corners rounded (radii per corner, 0 = sharp)."""
    p = np.asarray(pts, float)
    n = len(p)
    radii = [radii] * n if np.isscalar(radii) else list(radii)
    out = []
    for i in range(n):
        a, b, c = p[i - 1], p[i], p[(i + 1) % n]
        r = radii[i]
        if r <= 0:
            out.append(b)
            continue
        u, v = a - b, c - b
        lu, lv = np.linalg.norm(u), np.linalg.norm(v)
        u, v = u / lu, v / lv
        half = 0.5 * np.arccos(np.clip(np.dot(u, v), -1.0, 1.0))
        d = min(r / np.tan(half), 0.45 * lu, 0.45 * lv)
        r = d * np.tan(half)
        p0, p1 = b + u * d, b + v * d
        bis = u + v
        centre = b + bis / np.linalg.norm(bis) * (r / np.sin(half))
        a0 = np.arctan2(*(p0 - centre)[::-1])
        a1 = np.arctan2(*(p1 - centre)[::-1])
        da = (a1 - a0 + np.pi) % (2 * np.pi) - np.pi
        for k in range(seg + 1):
            t = a0 + da * k / seg
            out.append(centre + r * np.array([np.cos(t), np.sin(t)]))
    return np.array(out)


def circle_loop(cx, cy, r, n=None):
    n = n or arc_segments(r, 12)
    return sh.circle(cx, cy, r, n)


def stadium(c0, c1, r, n=8):
    """2D slot outline between two centres (CCW)."""
    c0, c1 = np.asarray(c0, float), np.asarray(c1, float)
    d = (c1 - c0) / np.linalg.norm(c1 - c0)
    a = np.arctan2(d[1], d[0])
    pts = [c1 + r * np.array([np.cos(t), np.sin(t)]) for t in np.linspace(a - np.pi / 2, a + np.pi / 2, n + 1)]
    pts += [c0 + r * np.array([np.cos(t), np.sin(t)]) for t in np.linspace(a + np.pi / 2, a + 1.5 * np.pi, n + 1)]
    return np.array(pts)


def lathe(profile, segments, material, centre=(0.0, 0.0), closed=False):
    """Revolve an (r, z) profile about the Z axis through `centre` (x, y); closed=True joins the last profile point
    back to the first (a ring / tube with an inner wall)."""
    prof = list(profile) + ([profile[0]] if closed else [])
    m = sh.revolve(prof, segments, material)
    return m.translated((centre[0], centre[1], 0.0))


def _xside(m, s):
    """Mirror a mesh built on the right (+X) to side s (+1 right, -1 left), keeping the winding outward."""
    if s > 0:
        return m
    out = m.transformed(np.diag([-1.0, 1.0, 1.0, 1.0]))
    out.f = [tuple(reversed(f)) for f in out.f]
    out.uv = [list(reversed(u)) if u is not None else None for u in out.uv]
    return out


def screw(c, normal_x, r=0.0022, h=0.0010, material="steel_dark", seg=8, slot=True):
    """Socket / slotted screw head on a side face (normal_x = +1 right, -1 left)."""
    m = sh.revolve([(0.0, h), (r * 0.7, h), (r, h * 0.45), (r, -0.0006), (0.0, -0.0006)], seg, material)
    if slot:
        m.merge(sh.box(-r * 0.75, -0.00035, h - 0.0002, r * 0.75, 0.00035, h + 0.0003, 0.0, material))
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(normal_x, 0, 0))
    return m.translated(c)


def torus_x(c, R, r, seg=14, tube=6, material="steel_dark"):
    """Ring in the YZ plane (axis along X) round centre c: sling loops and swivels."""
    m = Mesh()
    u = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    v = np.linspace(0, 2 * np.pi, tube, endpoint=False)
    rings = []
    for a in u:
        d = np.array([0.0, np.cos(a), np.sin(a)])
        rings.append([np.asarray(c) + d * (R + r * np.cos(b)) + np.array([r * np.sin(b), 0, 0]) for b in v])
    base = m.add_vertices(np.array(rings).reshape(-1, 3))
    for i in range(seg):
        for j in range(tube):
            a0, a1 = i, (i + 1) % seg
            b0, b1 = j, (j + 1) % tube
            m.add_face([base + a0 * tube + b0, base + a1 * tube + b0, base + a1 * tube + b1, base + a0 * tube + b1],
                       material)
    return sh.orient_outward(m)


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """Vented upper (top cap, two side plates with 2 x 9 round vents, front and rear walls), the lower receiver
    block with the fixed rail under the forend, the front cup of the cassette bay, pins and screws."""
    m = Mesh()
    # top cap: a slab with rounded top edges over the side plates
    cap = sh.rounded_rect(-REC_W, CAP_Y, REC_W, TOP, (0.0, 0.0, 0.0050, 0.0050), 4)
    m.merge(extrude_z(cap, (), REC_Z0, REC_Z1, soft_bevel(0.0020), "steel"))
    # side plates with the vents (through: the barrel shows behind them)
    plate = np.array([(REC_Z0 + 0.0040, SEAM_Y - 0.0006), (REC_Z1 - 0.0040, SEAM_Y - 0.0006),
                      (REC_Z1 - 0.0040, CAP_Y), (REC_Z0 + 0.0040, CAP_Y)])
    holes = [circle_loop(z, y, r, 12) for y, r in VENT_ROWS for z in VENT_Z]
    for s in (-1, 1):
        m.merge(sh.extrude_x(plate, holes, PLATE_T / 2, soft_bevel(0.0006), "steel", x_center=s * (REC_W - PLATE_T / 2)))
    # front and rear walls of the vented section
    for z0, z1 in ((REC_Z0, REC_Z0 + 0.0040), (REC_Z1 - 0.0040, REC_Z1)):
        m.merge(sh.box(-REC_W, SEAM_Y - 0.0006, z0, REC_W, CAP_Y, z1, 0.0009, "steel"))
    # lower receiver block (soft edges), its seam under the plates
    low = np.array([(REC_Z0, REC_BOT), (REC_Z1, REC_BOT), (REC_Z1, SEAM_Y + 0.0010), (REC_Z0, SEAM_Y + 0.0010)])
    m.merge(sh.extrude_x(fillet(low, (0.0060, 0.0, 0.0, 0.0010), 3), (), LOW_W, soft_bevel(0.0034), "steel"))
    # fixed rail under the forend, with its slot
    m.merge(sh.box(-STRIP_W, STRIP_Y0, STRIP_Z1, STRIP_W, STRIP_Y1, REC_Z0 + 0.0020, 0.0012, "steel"))
    for s in (-1, 1):
        x0, x1 = sorted((s * STRIP_W, s * (STRIP_W + 0.0003)))
        m.merge(sh.box(x0, STRIP_Y0 + 0.0030, STRIP_Z1 + 0.0080, x1, STRIP_Y1 - 0.0030, STRIP_Z1 + 0.0120, 0.0,
                       "steel_dark"))
    # front cup of the cassette bay (the receiver's rear wall): a thick ring round the cassette axis
    c = 0.0012
    ring = [(RING_R0, RING_Z0), (RING_R1 - c, RING_Z0), (RING_R1, RING_Z0 + c), (RING_R1, RING_Z1 - c),
            (RING_R1 - c, RING_Z1), (RING_R0, RING_Z1)]
    m.merge(lathe(ring, 48, "steel", (CAS_X, CAS_Y), closed=True))
    # the barrel's chamber end inside the vented section (seen through the vents), from the bay's front cup
    m.merge(sh.revolve([(0.0, RING_Z0 + 0.0010), (BARREL_IN_R, RING_Z0 + 0.0010), (BARREL_IN_R, REC_Z0 + 0.0040),
                        (0.0, REC_Z0 + 0.0040)], 40, "barrel"))
    # pins and screws (left and right)
    for s in (-1, 1):
        x = s * LOW_W
        m.merge(screw((x, -0.0320, -0.1170), s, 0.0021, slot=False))                  # takedown pin
        m.merge(screw((x, -0.0430, -0.0300), s, 0.0018, slot=False))
        for z in (-0.0300, -0.2000):
            m.merge(screw((s * REC_W, 0.0290, z), s, 0.0017))
    return m


def handle_mesh():
    """Carrying handle / sight rail: one plate with rounded edges, the oval and three round cut-outs, a picatinny
    section for optics on its top."""
    prof = [(REC_Z1 - 0.0100, TOP - 0.0010), (-0.0430, HANDLE_TOP), (-0.3220, HANDLE_TOP), (-0.3770, 0.0310),
            (REC_Z0 - 0.0010, 0.0310), (REC_Z0 - 0.0010, TOP - 0.0010)]
    outline = fillet(prof, (0.0, 0.0060, 0.0120, 0.0030, 0.0020, 0.0), 4)
    holes = [stadium((-0.0682, 0.04895), (-0.1703, 0.04895), 0.01065, 8)]
    holes += [circle_loop(z, 0.0498, 0.0140, 24) for z in (-0.2110, -0.2660, -0.3190)]
    m = sh.extrude_x(outline, holes, HANDLE_W, soft_bevel(0.0032), "steel")
    # picatinny section: base and crossbars at the standard 10 mm pitch
    m.merge(sh.box(-RAIL_W, HANDLE_TOP - 0.0010, RAIL_Z1, RAIL_W, RAIL_TOP - 0.0030, RAIL_Z0, 0.0008, "steel_dark"))
    n = int((RAIL_Z0 - RAIL_Z1 - 0.004) / 0.010)
    for k in range(n):
        z = RAIL_Z1 + 0.0050 + k * 0.010
        m.merge(sh.box(-RAIL_W - 0.0002, RAIL_TOP - 0.0032, z, RAIL_W + 0.0002, RAIL_TOP, z + 0.0052, 0.0,
                       "steel_dark"))
    for s in (-1, 1):
        m.merge(screw((s * HANDLE_W, 0.0740, -0.2420), s, 0.0016))
    return m


# ---------------------------------------------------------------------------------- stock and bay
def _shroud_section(inflate=0.0):
    """Section of the shroud over the cassette bay: a flat-topped hood that becomes the bay's round housing at the
    sides, down to the side windows; the inner arc clears the cassette."""
    cy, R, Ri = CAS_Y, SHROUD_R + inflate, SHROUD_RI
    yw = WINDOW_Y
    a_out = np.arcsin((yw - cy) / R)
    y_side = cy + np.sqrt(R * R - STOCK_W ** 2)
    a_top = np.arcsin((y_side - cy) / R)
    pts = []
    for a in np.linspace(a_out, a_top, 8):                       # right: round housing up to the hood's side
        pts.append((R * np.cos(a), cy + R * np.sin(a)))
    top = SHROUD_TOP + inflate
    for p in fillet([(STOCK_W + inflate, y_side), (STOCK_W + inflate, top), (-STOCK_W - inflate, top),
                     (-STOCK_W - inflate, y_side)], (0.0, 0.0055, 0.0055, 0.0), 4)[1:-1]:
        pts.append(tuple(p))
    for a in np.linspace(np.pi - a_top, np.pi - a_out, 8):         # left: down to the window
        pts.append((R * np.cos(a), cy + R * np.sin(a)))
    ai = np.arcsin((yw - cy) / Ri)
    for a in np.linspace(np.pi - ai, ai, 26):                        # inner arc back over the cassette
        pts.append((Ri * np.cos(a), cy + Ri * np.sin(a)))
    return np.array(pts)


def stock_mesh():
    """Butt (frame with a recessed panel and a through window), rear cup of the bay, the shroud, sling studs."""
    m = Mesh()
    # shroud over the bay, chamfered ends
    s0 = _shroud_section()
    secs = [(sh.offset_loop(s0, 0.0010), SHROUD_Z0), (s0, SHROUD_Z0 + 0.0010), (s0, SHROUD_Z1)]
    m.merge(sh.loft([np.stack([s[:, 0], s[:, 1], np.full(len(s), z)], 1) for s, z in secs], "stock"))
    # rear cup round the cassette's back
    c = 0.0015
    cup = [(0.0, CUP_Z0), (CUP_R - c, CUP_Z0), (CUP_R, CUP_Z0 + c), (CUP_R, CUP_Z1 - c), (CUP_R - c, CUP_Z1),
           (0.0, CUP_Z1)]
    m.merge(lathe(cup, 48, "stock", (CAS_X, CAS_Y)))
    # butt: outer frame round a recess
    outer = fillet([(SHROUD_Z1, BUTT_BOT), (BUTT_Z, BUTT_BOT), (BUTT_Z, SHROUD_TOP), (SHROUD_Z1, SHROUD_TOP)],
                   (0.0, 0.0080, 0.0040, 0.0), 4)
    recess = fillet([(0.1230, -0.0580), (0.2080, -0.0580), (0.2080, -0.0085), (0.1230, -0.0085)], 0.0060, 3)
    m.merge(sh.extrude_x(outer, [recess], STOCK_W, soft_bevel(0.0050), "stock"))
    panel = fillet([(0.1220, -0.0590), (0.2090, -0.0590), (0.2090, -0.0075), (0.1220, -0.0075)], 0.0060, 3)
    window = fillet([(0.1360, -0.0426), (0.1790, -0.0426), (0.1790, -0.0190), (0.1360, -0.0190)], 0.0060, 3)
    m.merge(sh.extrude_x(panel, [window], STOCK_W - 0.0040, soft_bevel(0.0012), "stock"))
    # sling studs: a post and a swivel loop, top and bottom
    for y, z, up in ((SHROUD_TOP, 0.1947, 1.0), (BUTT_BOT, 0.2185, -1.0)):
        post = _cyl_x(0.0028, -0.0030, 0.0030, (y + up * 0.0030, z), "steel_dark", 12)
        m.merge(post)
        m.merge(sh.box(-0.0020, min(y, y + up * 0.0045), z - 0.0030, 0.0020, max(y, y + up * 0.0045), z + 0.0030,
                       0.0006, "steel_dark"))
        m.merge(torus_x((0.0, y + up * 0.0075, z), 0.0042, 0.0011, 10, 5))
    return m


def pad_mesh():
    """Rubber butt pad (separate material: never skinned)."""
    outline = fillet([(BUTT_Z - 0.0010, BUTT_BOT - 0.0004), (PAD_Z, BUTT_BOT + 0.0040), (PAD_Z, SHROUD_TOP - 0.0030),
                      (BUTT_Z - 0.0010, SHROUD_TOP + 0.0004)], (0.0, 0.0040, 0.0030, 0.0), 3)
    return sh.extrude_x(outline, (), STOCK_W + 0.0004, soft_bevel(0.0050), "rubber")


# ---------------------------------------------------------------------------------- lower frame
OPENING = [(-0.0165, -0.0555), (-0.0780, -0.0555), (-0.0852, -0.0700), (-0.0480, -0.1330), (-0.0440, -0.1365),
           (-0.0195, -0.1365), (-0.0165, -0.1335)]
GUARD = [(-0.1540, -0.0530), (-0.1820, -0.0530), (-0.1858, -0.0600), (-0.1800, -0.0772), (-0.1600, -0.0776),
         (-0.1535, -0.0700)]


def lower_mesh():
    """Trigger housing (round guard opening), the open-frame pistol grip with the strut and the bottom bar back to
    the bay, its rubber grip panels, the rotary selector, the safety and screws."""
    out = [(-0.0040, -0.0490), (-0.0040, -0.1430), (-0.0090, -0.1480), (-0.1030, -0.1480), (-0.1085, -0.1430),
           (-0.1085, -0.1360), (-0.1300, -0.0885), (-0.1940, -0.0885), (-0.2150, -0.0520), (-0.2150, -0.0490)]
    outline = fillet(out, (0.0, 0.0030, 0.0030, 0.0030, 0.0020, 0.0040, 0.0050, 0.0060, 0.0020, 0.0), 3)
    holes = [fillet(OPENING, (0.0040, 0.0100, 0.0040, 0.0060, 0.0, 0.0040, 0.0030), 3),
             fillet(GUARD, (0.0040, 0.0040, 0.0040, 0.0060, 0.0050, 0.0040), 3)]
    m = sh.extrude_x(outline, holes, FRAME_W, soft_bevel(0.0036), "steel")
    # rubber grip panels: a slot-shaped pad along the grip axis with a raised centre, both sides
    a, b = grip_center(0.010), grip_center(0.058)
    for s in (-1, 1):
        pad = stadium((a[2], a[1]), (b[2], b[1]), 0.0150, 8)
        m.merge(sh.extrude_x(pad, (), 0.0016, soft_bevel(0.0011), "grip", x_center=s * (FRAME_W + 0.0012)))
        core = stadium((a[2], a[1]), (b[2], b[1]), 0.0095, 8)
        m.merge(sh.extrude_x(core, (), 0.0008, soft_bevel(0.0006), "grip", x_center=s * (FRAME_W + 0.0030)))
        for z, y in ((-0.2020, -0.0600), (-0.1180, -0.0560), (-0.0600, -0.1423)):
            m.merge(screw((s * FRAME_W, y, z), s, 0.0014))
    # rotary selector (left): a boss with a cross-shaped lever in a round recess
    c = SELECTOR_C
    boss = _cyl_x(0.0092, c[0] - 0.0010, c[0] + 0.0004, (c[1], c[2]), "steel_dark", 32)
    m.merge(boss)
    for ang in (0.0, 90.0):
        bar = sh.box(-0.0016, -0.0078, -0.0012, 0.0016, 0.0078, 0.0012, 0.0005, "steel_dark")
        bar = sh.rot_x(bar, ang).translated((c[0] - 0.0020, c[1], c[2]))
        m.merge(bar)
    # safety lever (left, behind the guard)
    lever = sh.box(-0.0012, -0.0115, -0.0016, 0.0012, 0.0010, 0.0016, 0.0005, "steel_dark")
    m.merge(sh.rot_x(lever, -8.0).translated((-FRAME_W - 0.0010, -0.0560, -0.1890)))
    m.merge(screw((-FRAME_W, -0.0560, -0.1890), -1, 0.0018, slot=False))
    return m


def trigger_mesh():
    """Trigger blade hanging in the guard opening (node origin on its pivot)."""
    z, y = TRIGGER_PIVOT[2], TRIGGER_PIVOT[1]
    blade = [(z + 0.0035, y + 0.0020), (z - 0.0040, y + 0.0020), (z - 0.0045, y - 0.0080), (z - 0.0055, y - 0.0150),
             (z - 0.0045, y - 0.0215), (z - 0.0010, y - 0.0250), (z + 0.0012, y - 0.0240), (z + 0.0002, y - 0.0200),
             (z + 0.0006, y - 0.0130), (z + 0.0035, y - 0.0060)]
    return sh.extrude_x(np.array(blade), (), 0.0028, soft_bevel(0.0009), "steel_dark")


def latch_mesh():
    """Cassette latch on the strut's left (node origin on its pivot): a short lever the support thumb presses down."""
    p = LATCH_PIVOT
    m = sh.box(-0.0016, -0.0040, -0.0060, 0.0, 0.0030, 0.0040, 0.0006, "steel_dark")
    m.merge(sh.box(-0.0024, -0.0150, -0.0050, -0.0002, -0.0030, 0.0030, 0.0007, "steel_dark"))
    for k in range(3):
        m.merge(sh.box(-0.0030, -0.0145 + k * 0.0035, -0.0048, -0.0020, -0.0128 + k * 0.0035, 0.0028, 0.0003,
                       "steel_dark"))
    m.merge(_cyl_x(0.0028, -0.0022, 0.0002, (0.0, 0.0), "steel_dark", 12))
    return m.translated(p)


# ---------------------------------------------------------------------------------- barrel, muzzle
def barrel_mesh():
    """Barrel in front of the receiver (its chamber end inside the receiver is part of receiver_mesh): the barrel nut
    at the receiver face, the 39 mm dark steel tube, the clamp collar with the front sling loop, the bore at the crown."""
    c = 0.0010
    prof = [(0.0, REC_Z0 + 0.0030), (0.0212, REC_Z0 + 0.0030), (0.0215, REC_Z0), (0.0215, REC_Z0 - 0.0120),
            (0.0205, REC_Z0 - 0.0135), (BARREL_R, REC_Z0 - 0.0140), (BARREL_R, COLLAR_Z0 + 0.0005),
            (0.0, COLLAR_Z0 + 0.0005)]
    m = sh.revolve(prof, 52, "barrel")
    col = [(HIDER_RI, COLLAR_Z0), (COLLAR_R - c, COLLAR_Z0), (COLLAR_R, COLLAR_Z0 - c), (COLLAR_R, CROWN_Z + c),
           (COLLAR_R - c, CROWN_Z), (0.0125, CROWN_Z), (HIDER_RI, CROWN_Z + 0.0020)]
    m.merge(sh.revolve(col + [(HIDER_RI, COLLAR_Z0)], 48, "barrel"))
    # clamp screw and the sling loop under the collar
    zc = 0.5 * (COLLAR_Z0 + CROWN_Z)
    m.merge(sh.box(-0.0050, -COLLAR_R - 0.0060, zc - 0.0045, 0.0050, -COLLAR_R + 0.0020, zc + 0.0045, 0.0012,
                   "steel_dark"))
    m.merge(torus_x((0.0, -COLLAR_R - 0.0120, zc), 0.0058, 0.0014, 12, 6))
    m.merge(screw((0.0050, -COLLAR_R - 0.0020, zc), 1, 0.0016))
    return m


def muzzle_brake_mesh():
    """Duckbill flash hider: a ring threaded on the crown, then the lower half cut away in a rising curve so only the
    top tongue reaches forward (the photo's profile), a side slot (node `muzzle_brake`, hidden by the barrel slot)."""
    z0 = CROWN_Z + 0.0005
    c = 0.0008
    ring = [(HIDER_RI, z0), (HIDER_R - c, z0), (HIDER_R, z0 - c), (HIDER_R, HIDER_STEP), (HIDER_RI, HIDER_STEP)]
    m = sh.revolve(ring + [(HIDER_RI, z0)], 48, "barrel")
    # the tongue: C-shaped sections, the cut rising from the bottom of the ring to above the bore
    ro, ri = HIDER_R - 0.00005, HIDER_RI
    secs = []
    us = (0.0, 0.12, 0.25, 0.38, 0.50, 0.62, 0.72, 0.81, 0.88, 0.94, 0.98, 1.0)
    z1 = MUZZLE_Z
    for k, u in enumerate(us):
        z = HIDER_STEP + 0.0005 + (z1 - HIDER_STEP - 0.0005) * u
        y_cut = -ro * 0.998 + (0.0040 + ro * 0.998) * u * u
        inset = 0.0016 if k == len(us) - 1 else (0.0006 if k == len(us) - 2 else 0.0)
        R = ro - inset
        th = np.arcsin(np.clip(y_cut / R, -0.9995, 0.9995))
        ph = np.arcsin(np.clip(y_cut / ri, -0.9990, 0.9990))
        outer = [(R * np.cos(a), R * np.sin(a)) for a in np.linspace(th, np.pi - th, 25)]
        inner = [((ri + inset) * np.cos(a), (ri + inset) * np.sin(a)) for a in np.linspace(np.pi - ph, ph, 13)]
        sec = np.array(outer + inner)
        secs.append(np.stack([sec[:, 0], sec[:, 1], np.full(len(sec), z)], 1))
    m.merge(sh.loft(secs, "barrel"))
    for s in (-1, 1):                                                      # side slot
        x0, x1 = sorted((s * (HIDER_R - 0.0010), s * (HIDER_R + 0.0002)))
        m.merge(sh.box(x0, -0.0020, -0.4985, x1, 0.0128, -0.4945, 0.0002, "steel_dark"))
    return m


# ---------------------------------------------------------------------------------- forend
def _forend_outline(inset=0.0, z_rear=FE_Z0):
    pts = [(z_rear, FE_TOP - inset), (-0.3720, FE_TOP - inset), (FE_Z1 + inset, -0.0040), (-0.3660 + inset, FE_BOT + inset),
           (z_rear, FE_BOT + inset)]
    return fillet(pts, (0.0, 0.0080, 0.0140, 0.0060, 0.0), 4)


def forend_mesh():
    """Operating sleeve (node `bolt`): two side walls that slide back over the receiver's nose on the cocking stroke,
    solid in front of the nose, raised oval panels on both sides."""
    m = Mesh()
    for s in (-1, 1):
        m.merge(sh.extrude_x(_forend_outline(), (), FE_WALL / 2, soft_bevel(0.0012), "forend",
                             x_center=s * (FE_W - FE_WALL / 2)))
        panel = stadium((-0.2500, -0.0232), (-0.3560, -0.0232), 0.0140, 8)
        m.merge(sh.extrude_x(panel, (), 0.0008, soft_bevel(0.0006), "forend", x_center=s * (FE_W + 0.0006)))
    web = _forend_outline(0.0003, FE_WEB_Z)
    m.merge(sh.extrude_x(web, (), FE_W - FE_WALL + 0.0003, soft_bevel(0.0010), "forend"))
    return m


# ---------------------------------------------------------------------------------- sights
def rear_sight_mesh():
    """Rear sight block on the handle's rear end with a square notch (node `sight_rear`)."""
    y0 = HANDLE_TOP - 0.0010
    m = sh.box(-0.0070, y0, REAR_SIGHT_Z - 0.0010, 0.0070, SIGHT_Y - 0.0050, REAR_SIGHT_Z + 0.0100, 0.0012, "steel_dark")
    notch = np.array([(-0.0075, SIGHT_Y - 0.0060), (0.0075, SIGHT_Y - 0.0060), (0.0075, SIGHT_Y + 0.0040),
                      (0.0060, SIGHT_Y + 0.0048), (0.0013, SIGHT_Y + 0.0048), (0.0013, SIGHT_Y),
                      (-0.0013, SIGHT_Y), (-0.0013, SIGHT_Y + 0.0048), (-0.0060, SIGHT_Y + 0.0048),
                      (-0.0075, SIGHT_Y + 0.0040)])
    m.merge(extrude_z(notch, (), REAR_SIGHT_Z - 0.0030, REAR_SIGHT_Z, chamfer_bevel(0.0003), "steel_dark"))
    return m


def front_sight_mesh():
    """Front post with its protective ears on a base at the handle's front (node `sight_iron`)."""
    y0 = HANDLE_TOP - 0.0010
    z = FRONT_SIGHT_Z
    m = sh.box(-0.0080, y0, z - 0.0100, 0.0080, SIGHT_Y - 0.0110, z + 0.0080, 0.0012, "steel_dark")
    post = np.array([(-0.0011, SIGHT_Y - 0.0120), (0.0011, SIGHT_Y - 0.0120), (0.0011, SIGHT_Y - 0.0006),
                     (0.0006, SIGHT_Y), (-0.0006, SIGHT_Y), (-0.0011, SIGHT_Y - 0.0006)])
    m.merge(extrude_z(post, (), z - 0.0012, z + 0.0012, chamfer_bevel(0.0002), "steel_dark"))
    for s in (-1, 1):
        ear = [(s * 0.0042, SIGHT_Y - 0.0120), (s * 0.0080, SIGHT_Y - 0.0120), (s * 0.0074, SIGHT_Y - 0.0015),
               (s * 0.0058, SIGHT_Y - 0.0005), (s * 0.0042, SIGHT_Y - 0.0015)]
        ear = ear if s > 0 else ear[::-1]
        m.merge(extrude_z(np.array(ear), (), z - 0.0060, z + 0.0050, chamfer_bevel(0.0005), "steel_dark"))
    return m


# ---------------------------------------------------------------------------------- the Ammo-Cassette
def _cam_columns():
    """Column angles (radians) and per column the groove's (lo, hi) z interval, the axial legs' side walls given a
    hair of width so no face is degenerate."""
    R = CAS_R
    period = 2 * np.pi / CHAMBERS
    half_axial = 0.5 * GROOVE_W / R                    # half the axial leg's angular width
    eps = 0.00012 / R
    slope = (CAM_Z1 - CAM_Z0) / ((period - 2 * half_axial) * R)   # dz per metre of arc along the helical leg
    h = 0.5 * GROOVE_W * np.sqrt(1.0 + slope * slope)            # vertical half height of the helical leg
    cols = []
    n_helix = 6
    for k in range(CHAMBERS):
        a = k * period
        # axial leg (the groove spans front..rear turning points)
        cols.append((a - half_axial, CAM_Z0 - h, CAM_Z1 + h))
        cols.append((a + half_axial, CAM_Z0 - h, CAM_Z1 + h))
        # helical leg from the rear end of this axial leg to the front end of the next
        for j in range(n_helix + 1):
            u = j / n_helix
            ang = a + half_axial + eps + u * (period - 2 * half_axial - 2 * eps)
            zc = CAM_Z1 + (CAM_Z0 - CAM_Z1) * u
            cols.append((ang, zc - h, zc + h))
    return cols


def cassette_mesh(local=True):
    """The 10-round Ammo-Cassette: lands and the zig-zag cam groove on its outer surface (a sawtooth: an axial leg
    every 36 degrees, where the indexing stud rides while the cassette is locked, joined by a helical leg that turns
    it on), chamfered ends, ten chamber mouths on the front face with the loaded hulls' crimps inside, an axle hub on
    both faces. Built round its own axis; local=True returns it in `mag` space (the cassette's centre at the origin),
    else in the design frame."""
    R, Rg = CAS_R, CAS_RG
    z0, z1 = CAS_Z0, CAS_Z1
    c = 0.0020
    cols = []
    for ang, lo, hi in _cam_columns():
        prof = [(R - c, z0), (R, z0 + c), (R, lo), (Rg, lo + 0.0004), (Rg, hi - 0.0004), (R, hi), (R, z1 - c),
                (R - 0.6 * c, z1), (0.0, z1)]
        cols.append([(r * np.cos(ang), r * np.sin(ang), z) for r, z in prof])
    cols.append(cols[0])
    rings = np.array(cols)                                         # (columns, profile points, 3)
    body = Mesh()
    base = body.add_vertices(rings.reshape(-1, 3))
    K, N = rings.shape[:2]
    for k in range(K - 1):
        for i in range(N - 1):
            body.add_face([base + k * N + i, base + (k + 1) * N + i, base + (k + 1) * N + i + 1,
                           base + k * N + i + 1], "cassette")
    # front face: a disc (its rim is the columns' first ring) with ten chamber mouths and the axle hole
    rim = rings[:-1, 0, :]
    seg = 24
    t = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    holes, centres = [], []
    for k in range(CHAMBERS):
        a = np.pi / 2 + k * 2 * np.pi / CHAMBERS                    # chamber 0 at the top (in line with the bore)
        cx, cy = CHAMBER_RC * np.cos(a), CHAMBER_RC * np.sin(a)
        centres.append((cx, cy))
        holes.append(np.stack([cx + CHAMBER_R * np.cos(t), cy + CHAMBER_R * np.sin(t), np.full(seg, z0)], 1))
    th = np.linspace(0, 2 * np.pi, 24, endpoint=False)
    hub = np.stack([0.0078 * np.cos(th), 0.0078 * np.sin(th), np.full(24, z0)], 1)
    sh.cap_polygon(body, rim[::-1], "cassette", holes3d=holes + [hub])
    sh.orient_outward(body)
    m = Mesh()
    m.merge(body)
    for cx, cy in centres:
        mouth = sh.revolve([(CHAMBER_R, z0), (CHAMBER_R, z0 + 0.0070)], seg, "cassette", outward=False)
        m.merge(mouth.translated((cx, cy, 0.0)))
        m.merge(sh.disc((cx, cy, z0 + 0.0070), (0.0, 0.0, -1.0), CHAMBER_R, seg, "shell"))
    axle = sh.revolve([(0.0078, z0), (0.0078, z0 + 0.0030)], 24, "cassette", outward=False)
    m.merge(axle)
    m.merge(sh.disc((0.0, 0.0, z0 + 0.0030), (0.0, 0.0, -1.0), 0.0078, 24, "steel_dark"))
    m.merge(sh.revolve([(0.0, z0 + 0.0020), (0.0042, z0 + 0.0020), (0.0042, z0 - 0.0012), (0.0030, z0 - 0.0024),
                        (0.0, z0 - 0.0024)], 16, "steel_dark"))                     # axle end
    # rear: the axle's ratchet hub
    m.merge(sh.revolve([(0.0, z1 + 0.0018), (0.0090, z1 + 0.0018), (0.0110, z1), (0.0110, z1 - 0.0010),
                        (0.0, z1 - 0.0010)], 24, "steel_dark"))
    if local:
        return m.translated((0.0, 0.0, -CAS_C[2]))
    return m.translated((CAS_X, CAS_Y, 0.0))


def shell_mesh():
    """Spent 12-gauge hull (the KS-3's: red hull, brass head; fx_shell)."""
    import ks3_model
    return ks3_model.shell_mesh(spent=True, seg=10)
