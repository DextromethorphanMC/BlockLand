"""H&K MP5A3 geometry (VIEWMODEL_SPEC sections 3-6, 19-21 and 40). Design frame (metres): +X right, +Y up, +Z rearward;
bore axis on x = y = 0, z = 0 at the breech face (barrel 225 mm: the crown at z = -0.225). Real-world scale: 680 mm with
the A3 stock extended (550 collapsed), sight radius 340 mm.

An original procedural model built like the AK's (ak_model.py helpers, meshkit.Mesh in the design frame; gen_mp5 moves
meshes into node-local space): the stamped receiver with its rounded top, the A3 stock channels pressed into both
flanks and the ejection port cut into the right one (a real notch: the bolt carrier and its roller show through it),
the end cap, the cocking tube over the barrel with the slot and locking notch on the left, the folded cocking handle,
the welded front sight housing with its hood, the rotary drum diopter between its wings, the low claw mount with a
picatinny top (kept under the iron sight line), the slim handguard with its hand stop, the 3-lug barrel and the slim
lug cap, the polymer trigger group (paddle release, SEF selector, the wide guard and the grip), the curved 30- and
40-round steel magazines and the A3 stock on its two rails with the butt cap."""
import numpy as np

import shapes as sh
import smg_detail as sd
from ak_model import _ring_xy, _rivet
from meshkit import Mesh
from model_quality import arc_segments
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0155                    # receiver half width
REC_Y0, REC_TOP = -0.0200, 0.0310  # receiver bottom (the trigger group's joint) / top
REC_R = 0.0140                    # radius of the receiver's rounded top corners
REC_Z0, REC_Z1 = -0.0120, 0.2540  # receiver front face / rear edge (under the end cap)
CAP_Z0, CAP_Z1 = 0.2400, 0.2700   # end cap
TUBE_Y, TUBE_R = 0.0205, 0.0100   # cocking tube axis height / radius
TUBE_Z0 = -0.1975                 # front of the tube's end plug
SLOT_A, SLOT_HW, NOTCH_A = 160.0, 12.0, 32.0   # cocking slot angle (deg from +X, left, a little up), half width, notch
SLOT_IN = 0.0040                  # radius of the slot's floor (the cocking lever shows in it)
HANDLE_Z = -0.1280                # cocking handle root at rest (bolt forward)
BOLT_TRAVEL = 0.0800              # cocking stroke (handle and carrier)
CARRIER_TRAVEL = 0.0640           # carrier stroke when firing
SLOT_Z0, SLOT_Z1 = HANDLE_Z - 0.0070, HANDLE_Z + BOLT_TRAVEL + 0.0070
NOTCH_Z0, NOTCH_Z1 = HANDLE_Z + BOLT_TRAVEL - 0.0060, SLOT_Z1
SIGHT_Y = 0.0465                  # sight line over the bore: drum aperture / front post tip
FRONT_POST_Z = -0.1760
REAR_Z = FRONT_POST_Z + 0.3400    # rear drum axis (sight radius 340 mm)
APERTURE_R = 0.0024
DRUM_R = 0.0078
FS_Z0, FS_Z1 = -0.1935, -0.1615  # front sight housing
BARREL_R = 0.0079
CROWN_Z = -0.2250
LUG_Z1 = -0.2060                  # rear end of the 3-lug section
LUG_R = 0.0090
MUZZLE_Z = -0.2337                # front face of the lug cap
HG_Z0, HG_Z1 = -0.1500, REC_Z0 - 0.0004  # handguard front (hand stop) / rear
HG_Y0, HG_YT = -0.0225, TUBE_Y - 0.0035   # handguard bottom / top (meets the cocking tube's sides)
HG_W = 0.0195                     # handguard half width
WELL_Z0, WELL_Z1 = 0.0008, 0.0372  # magazine well (outside)
WELL_Y1 = -0.0445                 # bottom of the well
MAG_HW, MAG_HD = 0.0113, 0.0166   # magazine half width / half depth (front-back) at the top
MAG_ORIGIN = np.array([0.0, -0.0090, 0.5 * (WELL_Z0 + WELL_Z1)])   # seated magazine pivot: top centre of the body
MAG_LEN, MAG_LEN_EXT = 0.1780, 0.2300
MAG_FRONT = np.array([0.0, 0.0, -MAG_HD])          # mag-local: the front edge at the top
HOUSING_W = 0.0148                # trigger group half width
HOUSING_Z0, HOUSING_Z1 = 0.0384, 0.2360
GRIP_TOP = np.array([0.0, -0.0360, 0.1250])
GRIP_RAKE = 21.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_RAKE)), np.sin(np.radians(GRIP_RAKE))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_RAKE)), -np.cos(np.radians(GRIP_RAKE))])
GRIP_LEN = 0.0960
TRIGGER_PIVOT = np.array([0.0, -0.0270, 0.0790])
SELECTOR_PIVOT = np.array([-HOUSING_W, -0.0285, 0.1170])
PADDLE_PIVOT = np.array([0.0, -0.0300, 0.0420])
EJECTION = np.array([REC_W + 0.0005, 0.0075, 0.0250])
PORT_Z0, PORT_Z1, PORT_Y0, PORT_Y1 = 0.0040, 0.0460, -0.0040, 0.0180
CHANNEL_Y0, CHANNEL_Y1, CHANNEL_D = 0.0060, 0.0150, 0.0022   # the A3 stock's rail channels in both flanks
RAIL_Z0, RAIL_Z1 = 0.0100, 0.1200  # claw mount
RAIL_BASE, RAIL_TOP, RAIL_W = 0.0340, 0.0392, 0.0106
OPTIC_Z = 0.0820                  # where an optic's base centre sits on the rail
HG_SIDE_X = HG_W + 0.0006         # right face of the handguard at the laser mount
STOCK_Z1 = 0.4465                 # butt face, stock extended (680 mm overall from the cap's front)
RAIL_X = REC_W - CHANNEL_D + 0.0014   # stock rails' centre lines (in the channels)
RAIL_YC = 0.5 * (CHANNEL_Y0 + CHANNEL_Y1)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# -------------------------------------------------------------------------------------- helpers
def _dedupe(loop, eps=1e-7):
    """Drop consecutive duplicate points of a closed loop (degenerate edges spoil offsets)."""
    out = []
    for p in np.asarray(loop, float):
        if not out or np.linalg.norm(p - out[-1]) > eps:
            out.append(p)
    if len(out) > 1 and np.linalg.norm(out[0] - out[-1]) <= eps:
        out.pop()
    return np.array(out)


def _arc(cx, cy, r, a0, a1, seg):
    return [(cx + r * np.cos(a), cy + r * np.sin(a)) for a in np.linspace(a0, a1, seg + 1)]


def _trap(z, z0, z1, rf, rb):
    """0..1 trapezoid in z: ramps rf / rb long at the front / rear (0 = a step)."""
    if z < z0 - 1e-9 or z > z1 + 1e-9:
        return 0.0
    a = 1.0 if rf <= 0 else min(1.0, (z - z0) / rf)
    b = 1.0 if rb <= 0 else min(1.0, (z1 - z) / rb)
    return float(np.clip(min(a, b), 0.0, 1.0))


def _side(bands, z, x_out, y_lo, y_hi):
    """Polyline (x, y) up a receiver flank from y_lo to y_hi, pressed in by every band (y0, y1, depth, z0, z1, rf,
    rb) active at z; every band edge carries two points (a crisp step, zero length where the band is not active)."""
    levels = sorted({b[0] for b in bands} | {b[1] for b in bands})
    bounds = [y_lo] + levels + [y_hi]

    def depth(y0, y1):
        d = 0.0
        for by0, by1, dep, bz0, bz1, rf, rb in bands:
            if by0 <= y0 + 1e-9 and y1 <= by1 + 1e-9:
                d = max(d, dep * _trap(z, bz0, bz1, rf, rb))
        return d

    ds = [depth(a, b) for a, b in zip(bounds, bounds[1:])]
    pts = [(x_out - ds[0], y_lo)]
    for k, y in enumerate(levels):
        pts += [(x_out - ds[k], y), (x_out - ds[k + 1], y)]
    pts.append((x_out - ds[-1], y_hi))
    return pts


def _band_zs(bands, z0, z1):
    zs = {z0, z1}
    for _, _, _, bz0, bz1, rf, rb in bands:
        for z in (bz0, bz0 + max(rf, 0.0003), bz1 - max(rb, 0.0003), bz1):
            if z0 <= z <= z1:
                zs.add(round(z, 7))
    return sorted(zs)


def _hull(points):
    """Convex hull (CCW) of 2D points (monotone chain)."""
    p = sorted(map(tuple, np.asarray(points, float)))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for q in p:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], q) <= 0:
            lower.pop()
        lower.append(q)
    for q in reversed(p):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], q) <= 0:
            upper.pop()
        upper.append(q)
    return np.array(lower[:-1] + upper[:-1])


def _chaikin(pts, iterations):
    """Corner-cutting smoothing of an open polyline (its two ends kept: they run into other parts)."""
    p = np.asarray(pts, float)
    for _ in range(iterations):
        q = [p[0]]
        for a, b in zip(p[:-1], p[1:]):
            q += [0.75 * a + 0.25 * b, 0.25 * a + 0.75 * b]
        q.append(p[-1])
        p = np.array(q)
    return p


def _ring_x(r_major, r_minor, centre, material, seg=16, seg_minor=6):
    """Torus (a sling loop or a weld bead) round an X axis through `centre`."""
    prof = [(r_major + r_minor * np.cos(a), r_minor * np.sin(a)) for a in np.linspace(0, 2 * np.pi, seg_minor + 1)]
    m = sh.revolve(prof, seg, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated(centre)


def _pin_x(y, z, x0, x1, r, material="steel_dark", seg=12):
    """Cross pin (or a screw head) along X from x0 to x1, chamfered ends."""
    c = min(0.0004, 0.3 * abs(x1 - x0))
    m = sh.revolve([(0.0, x0), (r - c, x0), (r, x0 + c), (r, x1 - c), (r - c, x1), (0.0, x1)], seg, material,
                   precision=True)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated((0.0, y, z))


def _stud(y, z, x_face, side, r, height, material="steel_dark", seg=None, sink=0.0006):
    """Pin end, screw head or button standing `height` out of a flank at x_face (side +1 right / -1 left): a chamfered
    cylinder, open at its buried base (inside the part)."""
    c = min(0.00035, 0.4 * height, 0.3 * r)
    seg = seg or max(10, min(16, int(round(r / 0.00025))))
    m = sh.revolve([(r, -sink), (r, height - c), (r - c, height), (0.0, height)], seg, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    if side < 0:
        m = sh.place(m, x_axis=(-1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, -1))
    return m.translated((x_face, y, z))


def _cyl(r, z0, z1, material, seg, y=0.0, x=0.0, chamfer=0.0):
    """Cylinder along Z with a fixed segment count (parts mostly hidden inside others)."""
    c = min(chamfer, 0.45 * abs(z1 - z0))
    prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)]
    return sh.revolve(prof, seg, material).translated((x, y, 0.0))


def _bead(r, z, material, seg=24):
    """Half-round weld bead round a tube of radius r where it meets a face at z (its flat side on the face, its round
    towards -Z)."""
    prof = [(r - 0.0004, z + 0.0004), (r + 0.0009, z + 0.0002), (r + 0.0011, z - 0.0004), (r + 0.0006, z - 0.0011),
            (r - 0.0004, z - 0.0013)]
    return sh.revolve(prof, seg, material)


# -------------------------------------------------------------------------------------- receiver
def _rec_bands():
    """Pressings in the receiver flanks: (y0, y1, depth, z0, z1, ramp front, ramp rear) per side."""
    right = [(PORT_Y0, PORT_Y1, REC_W - 0.0010, PORT_Z0, PORT_Z1, 0.0, 0.0),
             (CHANNEL_Y0, CHANNEL_Y1, CHANNEL_D, 0.0560, REC_Z1 + 0.02, 0.0040, 0.0)]
    left = [(CHANNEL_Y0, CHANNEL_Y1, CHANNEL_D, 0.0100, REC_Z1 + 0.02, 0.0040, 0.0)]
    return right, left


def rec_section(z, inflate=0.0):
    """Receiver cross-section (x, y), CCW: flat bottom, the flanks with their pressings, the rounded top."""
    right, left = _rec_bands()
    w, top, y0 = REC_W + inflate, REC_TOP + inflate, REC_Y0 - inflate
    rb, rt = 0.0025, REC_R + inflate
    seg = arc_segments(rt, 6, np.pi / 2)
    pts = _arc(-w + rb, y0 + rb, rb, np.pi, 1.5 * np.pi, 2)
    pts += _arc(w - rb, y0 + rb, rb, 1.5 * np.pi, 2 * np.pi, 2)
    pts += _side(right, z, w, y0 + rb, top - rt)[1:-1]
    pts += _arc(w - rt, top - rt, rt, 0.0, 0.5 * np.pi, seg)
    pts += _arc(-w + rt, top - rt, rt, 0.5 * np.pi, np.pi, seg)
    pts += [(-x, y) for x, y in _side(left, z, w, y0 + rb, top - rt)[::-1][1:-1]]
    return np.array(pts)


def receiver_mesh():
    """Stamped receiver: rounded top, the stock channels, the ejection port (open down to the carrier), the case
    deflector bump, the trunnion collar and the weld bead where the cocking tube meets the front, the rear sight base,
    the push-button release, pins and rivets."""
    right, left = _rec_bands()
    zs = _band_zs(right + left, REC_Z0, REC_Z1)
    m = sh.loft([_ring_xy(rec_section(z), z) for z in zs], "steel")
    m.set_material(lambda c, n: PORT_Z0 - 1e-4 < c[2] < PORT_Z1 + 1e-4 and PORT_Y0 - 1e-4 < c[1] < PORT_Y1 + 1e-4
                   and 0.0 < c[0] < REC_W - 0.0003, "steel_dark")
    # front: the cocking tube's weld bead, the barrel trunnion collar
    m.merge(_bead(TUBE_R, REC_Z0, "steel", 28).translated((0.0, TUBE_Y, 0.0)))
    m.merge(sh.revolve([(0.0102, REC_Z0 + 0.002), (0.0102, REC_Z0 - 0.0020), (0.0094, REC_Z0 - 0.0032),
                        (BARREL_R - 0.0002, REC_Z0 - 0.0032)], 16, "steel", precision=True))
    # case deflector bump behind the port
    m.merge(sh.box(REC_W - 0.0010, 0.0010, PORT_Z1 + 0.0050, REC_W + 0.0024, 0.0150, PORT_Z1 + 0.0175, 0.0014,
                   "steel"))
    # rear sight base (the drum and its wings are sight_rear) with its two screws
    m.merge(sh.box(-0.0100, REC_TOP - 0.0022, REAR_Z - 0.0200, 0.0100, 0.0352, REAR_Z + 0.0160, 0.0012, "steel"))
    for s in (-1, 1):
        m.merge(_stud(0.0322, REAR_Z - 0.0140, s * 0.0100, s, 0.0016, 0.0009))
        m.merge(_stud(0.0322, REAR_Z + 0.0100, s * 0.0100, s, 0.0016, 0.0009))
    # push-button magazine release on the right, behind the well, in its collar
    m.merge(_stud(-0.0150, WELL_Z1 + 0.0060, REC_W, 1, 0.0046, 0.0007, "steel", 20))
    m.merge(_stud(-0.0150, WELL_Z1 + 0.0060, REC_W, 1, 0.0032, 0.0021, "steel_dark", 16))
    # trunnion rivets and the push pins of the trigger group (both flanks)
    for s in (-1, 1):
        for z, y in ((-0.0060, -0.0120), (-0.0060, 0.0020)):
            m.merge(_rivet(s * (REC_W + 0.0003), y, z, s, 0.0017))
        m.merge(_stud(-0.0195, HOUSING_Z0 + 0.0075, s * REC_W, s, 0.0026, 0.0010))
        m.merge(_stud(-0.0195, HOUSING_Z1 - 0.0080, s * REC_W, s, 0.0026, 0.0010))
    return m


def end_cap_mesh():
    """A3 end cap: the receiver's section a hair proud, notched for the stock rails, the stock latch underneath and
    the rear sling loop on the left."""
    sec = _dedupe(rec_section(REC_Z1, inflate=0.0008))
    m = extrude_z(sec, (), CAP_Z0, CAP_Z1, chamfer_bevel(0.0014), "steel")
    m.merge(sh.box(-0.0070, REC_Y0 - 0.0050, CAP_Z1 - 0.0150, 0.0070, REC_Y0 + 0.0010, CAP_Z1 - 0.0010, 0.0012,
                   "steel_dark"))                                                       # stock latch
    m.merge(sh.box(-0.0050, REC_Y0 - 0.0068, CAP_Z1 - 0.0120, 0.0050, REC_Y0 - 0.0040, CAP_Z1 - 0.0030, 0.0008,
                   "steel_dark"))
    m.merge(_ring_x(0.0052, 0.0012, (-REC_W - 0.0040, -0.0030, CAP_Z0 + 0.0130), "steel_dark", 14, 5))
    m.merge(sh.box(-REC_W - 0.0030, -0.0070, CAP_Z0 + 0.0090, -REC_W, 0.0010, CAP_Z0 + 0.0170, 0.0008, "steel_dark"))
    return m


# --------------------------------------------------------------------------------- cocking tube
def _tube_section(z):
    """Cocking tube section (x, y), CCW: a circle with the slot on the left (its floor at SLOT_IN) and, at the rear end
    of the slot, the locking notch reaching up. Fixed point count: the points of the slot region slide with it."""
    r, ri = TUBE_R, SLOT_IN
    slot = _trap(z, SLOT_Z0, SLOT_Z1, 0.0, 0.0) > 0
    notch = NOTCH_A if _trap(z, NOTCH_Z0, NOTCH_Z1, 0.0, 0.0) > 0 else 0.0
    a0, a3 = np.radians(SLOT_A - SLOT_HW - NOTCH_A - 5.0), np.radians(SLOT_A + SLOT_HW + 5.0)
    e1 = np.radians(SLOT_A - SLOT_HW - notch)
    e2 = np.radians(SLOT_A + SLOT_HW)
    n_out = arc_segments(r, 36)
    base = np.linspace(a3, a0 + 2 * np.pi, int(np.ceil(n_out * (2 * np.pi - (a3 - a0)) / (2 * np.pi))) + 1)
    pts = [(r * np.cos(a), r * np.sin(a)) for a in base[:-1]]              # the closed part, from a3 round to a0
    rin = ri if slot else r
    pts += [(r * np.cos(a), r * np.sin(a)) for a in np.linspace(a0, e1, 5)]
    pts += [(rin * np.cos(a), rin * np.sin(a)) for a in np.linspace(e1, e2, 6)]
    pts += [(r * np.cos(a), r * np.sin(a)) for a in np.linspace(e2, a3, 3)[:-1]]
    return np.array([(x, y + TUBE_Y) for x, y in pts])


def cocking_tube_mesh():
    """Cocking tube from the front sight to the receiver: slot and notch on the left (their walls in steel_dark), the
    domed front plug."""
    zs = sorted({TUBE_Z0 + 0.0015, SLOT_Z0 - 0.0002, SLOT_Z0, NOTCH_Z0 - 0.0002, NOTCH_Z0, SLOT_Z1, SLOT_Z1 + 0.0002,
                 REC_Z0 + 0.004})
    m = sh.loft([_ring_xy(_tube_section(z), z) for z in zs], "steel")
    m.set_material(lambda c, n: np.hypot(c[0], c[1] - TUBE_Y) < TUBE_R - 0.0004, "steel_dark")
    plug = sh.revolve([(0.0, TUBE_Z0), (0.0055, TUBE_Z0 + 0.0002), (0.0085, TUBE_Z0 + 0.0009),
                       (TUBE_R - 0.0003, TUBE_Z0 + 0.0020), (TUBE_R - 0.0003, TUBE_Z0 + 0.0040), (0.0, TUBE_Z0 + 0.0040)],
                      24, "steel", precision=True)
    m.merge(plug.translated((0.0, TUBE_Y, 0.0)))
    return m


def _tube_dirs(angle=SLOT_A):
    a = np.radians(angle)
    radial = np.array([np.cos(a), np.sin(a), 0.0])
    tangent = np.array([-np.sin(a), np.cos(a), 0.0])
    return radial, tangent


HINGE = np.array([0.0, TUBE_Y, 0.0]) + (TUBE_R + 0.0024) * _tube_dirs()[0] + np.array([0.0, 0.0, HANDLE_Z + 0.0040])
TUBE_AXIS_PT = np.array([0.0, TUBE_Y, HANDLE_Z])


def bolt_mesh():
    """Node `bolt` (the slide lock): the cocking lever's rod, showing in the slot floor; rides the stroke (+Z)."""
    return _cyl(SLOT_IN + 0.0012, HANDLE_Z - 0.0080, HANDLE_Z + 0.0500, "steel_dark", 14, y=TUBE_Y, chamfer=0.0006)


def handle_root_mesh():
    """Node `charging_handle` (child of bolt; turns about the tube's axis into the locking notch): the root block riding
    in the slot from the lever inside up to the hinge boss. Design frame (gen_mp5 moves it to the tube's axis)."""
    radial, tangent = _tube_dirs()
    root = sh.box(SLOT_IN - 0.0005, -0.0019, -0.0060, TUBE_R + 0.0030, 0.0019, 0.0060, 0.0006, "steel_dark")
    m = sh.place(root, origin=(0.0, TUBE_Y, HANDLE_Z), x_axis=radial, y_axis=tangent, z_axis=(0, 0, 1))
    hinge = sh.revolve([(0.0, -0.0036), (0.0028, -0.0036), (0.0028, 0.0036), (0.0, 0.0036)], 12, "steel_dark")
    m.merge(sh.place(hinge, origin=HINGE, x_axis=(0, 0, 1), y_axis=radial, z_axis=tangent))
    return m


def handle_lever_mesh():
    """Node `handle_lever` (child of charging_handle; swings out about the hinge pin): the flat lever folded forward
    along the tube, a little proud of it, and its round knob. Design frame, folded (gen_mp5 moves it to the hinge)."""
    radial, tangent = _tube_dirs()
    axial = np.array([0.0, 0.0, 1.0])
    m = Mesh()
    lever = [(0.0040, -0.0023), (-0.0260, -0.0023), (-0.0300, -0.0012), (-0.0300, 0.0012), (-0.0260, 0.0023),
             (0.0040, 0.0023)]
    bar = sh.extrude_x(np.array([(zz, yy) for zz, yy in lever]), (), 0.0034, round_bevel(0.0008, 1), "steel_dark")
    # extrude_x: profile in (z, y) extruded along x -> remap: x -> tangent, y -> radial, z -> axial
    bar = sh.place(bar, origin=HINGE + 0.0009 * radial, x_axis=-tangent, y_axis=radial, z_axis=axial)
    m.merge(bar)
    # knob at the lever's tip, pointing out from the tube
    knob = sh.revolve([(0.0034, 0.0), (0.0040, 0.0008), (0.0040, 0.0062), (0.0030, 0.0080), (0.0, 0.0082)],
                      16, "steel_dark")
    tip = HINGE + np.array([0.0, 0.0, -0.0270]) - 0.0012 * radial
    m.merge(sh.place(knob, origin=tip, x_axis=tangent, y_axis=axial, z_axis=radial))
    return m


# ------------------------------------------------------------------------- barrel, sights, cap
def barrel_mesh():
    """Barrel from the trunnion to the crown with the 3-lug section and the bore."""
    m = sh.revolve([(0.0, REC_Z0 + 0.004), (BARREL_R, REC_Z0 + 0.004), (BARREL_R, LUG_Z1 - 0.0010),
                    (LUG_R, LUG_Z1 - 0.0006), (LUG_R, CROWN_Z + 0.0008), (LUG_R - 0.0008, CROWN_Z),
                    (0.0046, CROWN_Z), (0.0045, CROWN_Z + 0.0120), (0.0, CROWN_Z + 0.0120)], 24, "barrel",
                   precision=True)
    for k in range(3):                                          # three lugs: top, lower left, lower right
        a = np.radians(90.0 + 120.0 * k)
        radial = np.array([np.cos(a), np.sin(a), 0.0])
        tangent = np.array([-np.sin(a), np.cos(a), 0.0])
        lug = sh.box(LUG_R - 0.0006, -0.0029, CROWN_Z + 0.0030, LUG_R + 0.0023, 0.0029, LUG_Z1 - 0.0030, 0.0007,
                     "barrel")
        m.merge(sh.place(lug, x_axis=radial, y_axis=tangent, z_axis=(0, 0, 1)))
    return m


def front_sight_mesh():
    """Front sight housing welded round the barrel and the cocking tube: rounded block, a cross pin, the weld bead and
    the front sling loop on the left (the hood and post are sight_iron / front_sight_hood)."""
    circles = np.concatenate([sh.circle(0.0, 0.0, BARREL_R + 0.0034, 24), sh.circle(0.0, TUBE_Y, TUBE_R + 0.0026, 24),
                              np.array([(-0.0064, TUBE_Y + TUBE_R + 0.0030), (0.0064, TUBE_Y + TUBE_R + 0.0030)])])
    outline = _hull(circles)
    m = extrude_z(outline, (), FS_Z0, FS_Z1, round_bevel(0.0014, 2), "steel")
    for s in (-1, 1):
        m.merge(_stud(0.0120, FS_Z0 + 0.0090, s * 0.0112, s, 0.0019, 0.0006))
    bead = sh.place(_bead(TUBE_R, 0.0, "steel", 28), z_axis=(0, 0, -1), y_axis=(0, -1, 0))   # its round to the rear
    m.merge(bead.translated((0.0, TUBE_Y, FS_Z1)))
    # front sling loop: a lug on the left with a ring through it
    m.merge(sh.box(-0.0145, -0.0040, FS_Z0 + 0.0090, -0.0100, 0.0040, FS_Z1 - 0.0090, 0.0010, "steel"))
    m.merge(_ring_x(0.0060, 0.0011, (-0.0160, -0.0060, 0.5 * (FS_Z0 + FS_Z1)), "steel_dark", 14, 5))
    return m


def front_hood_mesh():
    """Hooded front sight head (node `front_sight_hood`, under sight_iron: hidden with it while an optic is fitted): the
    tower on the housing and the ring hood round the post."""
    zc0, zc1 = FRONT_POST_Z - 0.0075, FRONT_POST_Z + 0.0075
    ring = extrude_z(sh.circle(0.0, SIGHT_Y, 0.0088, arc_segments(0.0088, 28)), [sh.circle(0.0, SIGHT_Y, 0.0068,
                     arc_segments(0.0068, 28))[::-1]], zc0, zc1, chamfer_bevel(0.0005), "steel")
    tower = np.array([(-0.0058, TUBE_Y + TUBE_R + 0.0010), (0.0058, TUBE_Y + TUBE_R + 0.0010),
                      (0.0042, SIGHT_Y - 0.0074), (-0.0042, SIGHT_Y - 0.0074)])
    ring.merge(extrude_z(tower, (), zc0 + 0.0005, zc1 - 0.0005, chamfer_bevel(0.0006), "steel"))
    return ring


def front_post_mesh():
    """Front post inside the hood (node `sight_iron`)."""
    post = np.array([(-0.0011, SIGHT_Y - 0.0075), (0.0011, SIGHT_Y - 0.0075), (0.0009, SIGHT_Y - 0.0005),
                     (0.0005, SIGHT_Y), (-0.0005, SIGHT_Y), (-0.0009, SIGHT_Y - 0.0005)])
    return extrude_z(post, (), FRONT_POST_Z - 0.0013, FRONT_POST_Z + 0.0013, chamfer_bevel(0.0002), "steel_dark")


def _drum_half(sign, y0, y1, n_arc=12):
    """One half (sign -1 left / +1 right) of the drum: a vertical cylinder cut by the aperture along Z; the cut face is
    x = sign * w(y) with w the aperture's half width at that height (0 outside it)."""
    ys = [y0] + list(SIGHT_Y + APERTURE_R * np.sin(np.linspace(-np.pi / 2, np.pi / 2, 9))) + [y1]
    rings = []
    for y in ys:
        dy = y - SIGHT_Y
        w = np.sqrt(max(APERTURE_R ** 2 - dy * dy, 0.0))
        phi = np.arcsin(min(w / DRUM_R, 1.0))
        if sign > 0:      # right half: from the cut's rear end round the right to its front end
            ang = np.linspace(phi, np.pi - phi, n_arc + 1)
        else:
            ang = np.linspace(np.pi + phi, 2 * np.pi - phi, n_arc + 1)
        # (x, z) = DRUM_R (sin, cos): angle measured from +Z towards +X
        pts = [(DRUM_R * np.sin(a), y, REAR_Z + DRUM_R * np.cos(a)) for a in ang]
        pts = [(sign * max(abs(x), w), yy, zz) for x, yy, zz in pts]
        rings.append(np.array(pts))
    return sh.loft(rings, "steel_dark")


def rear_sight_mesh():
    """Rotary drum diopter between its protective wings (node `sight_rear`; hidden while an optic is fitted): the drum
    (vertical axis) with the aperture through it on the sight line, its top hub, the wings and the windage screw."""
    y0, y1 = 0.0350, SIGHT_Y + 0.0090
    m = _drum_half(1, y0, y1)
    m.merge(_drum_half(-1, y0, y1))
    m.merge(sh.revolve([(0.0, 0.0), (0.0040, 0.0), (0.0044, 0.0006), (0.0044, 0.0016), (0.0, 0.0016)], 16,
                       "steel_dark", precision=True).transformed(_y_up(REAR_Z, y1 - 0.0002)))     # hub on the top
    for s in (-1, 1):                                      # wings: rise above the drum, rounded tops
        prof = [(REAR_Z - 0.0125, 0.0345), (REAR_Z + 0.0125, 0.0345), (REAR_Z + 0.0120, SIGHT_Y + 0.0080),
                (REAR_Z + 0.0080, SIGHT_Y + 0.0125), (REAR_Z - 0.0040, SIGHT_Y + 0.0125),
                (REAR_Z - 0.0110, SIGHT_Y + 0.0060)]
        wing = sh.extrude_x(np.array(prof), (), 0.0014, round_bevel(0.0006, 1), "steel_dark",
                            x_center=s * (DRUM_R + 0.0034))
        m.merge(wing)
    m.merge(_stud(0.0380, REAR_Z + 0.0020, DRUM_R + 0.0048, 1, 0.0022, 0.0012))              # windage screw
    return m


def _y_up(z, y):
    """Matrix turning a +Z lathe onto +Y at (0, y, z)."""
    mat = np.eye(4)
    mat[:3, :3] = np.column_stack([(1, 0, 0), (0, 0, -1), (0, 1, 0)])
    mat[:3, 3] = (0.0, y, z)
    return mat


def muzzle_cap_mesh():
    """Slim lug cap over the 3-lug section (node `muzzle_brake`; hidden while the barrel slot holds something): a
    sleeve with two grip grooves and a rounded front face round the bore."""
    prof = [(0.0, LUG_Z1 - 0.0002), (0.0121, LUG_Z1 - 0.0002), (0.0128, LUG_Z1 - 0.0010), (0.0128, -0.2160),
            (0.0122, -0.2167), (0.0122, -0.2183), (0.0128, -0.2190), (0.0128, MUZZLE_Z + 0.0025),
            (0.0119, MUZZLE_Z + 0.0003), (0.0106, MUZZLE_Z), (0.0058, MUZZLE_Z), (0.0058, CROWN_Z - 0.0050),
            (0.0, CROWN_Z - 0.0050)]
    return sh.revolve(prof, 24, "barrel", precision=True)


def claw_mount_mesh():
    """Low claw mount: the base on the receiver top, the front and rear claws hooked into the receiver's recesses, the
    picatinny top (lands and slots at 10 mm pitch) kept under the iron sight line, the clamp lever on the right and the
    claw screws on the left."""
    m = sh.box(-0.0100, REC_TOP - 0.0012, RAIL_Z0, 0.0100, RAIL_BASE + 0.0004, RAIL_Z1, 0.0012, "steel_dark")
    for z0, z1 in ((RAIL_Z0, RAIL_Z0 + 0.0150), (RAIL_Z1 - 0.0150, RAIL_Z1)):
        for s in (-1, 1):
            x0, x1 = sorted((s * 0.0098, s * (REC_W + 0.0019)))
            m.merge(sh.box(x0, 0.0170, z0, x1, RAIL_BASE, z1, 0.0010, "steel_dark"))
            x0, x1 = sorted((s * (REC_W + 0.0019), s * (REC_W - 0.0008)))
            m.merge(sh.box(x0, 0.0140, z0 + 0.0020, x1, 0.0185, z1 - 0.0020, 0.0006, "steel_dark"))    # claw tip
    low = np.array([(-0.0078, RAIL_BASE), (0.0078, RAIL_BASE), (0.0078, RAIL_BASE + 0.0012), (0.0100, 0.0368),
                    (-0.0100, 0.0368), (-0.0078, RAIL_BASE + 0.0012)])
    m.merge(extrude_z(low, (), RAIL_Z0 + 0.0010, RAIL_Z1 - 0.0010, chamfer_bevel(0.0005), "steel_dark"))
    land = np.array([(-0.0078, 0.0362), (0.0078, 0.0362), (RAIL_W, 0.0369), (RAIL_W, 0.0380), (RAIL_W - 0.0012, RAIL_TOP),
                     (-RAIL_W + 0.0012, RAIL_TOP), (-RAIL_W, 0.0380), (-RAIL_W, 0.0369)])
    n = int((RAIL_Z1 - RAIL_Z0 - 0.004) / 0.0100)
    z = RAIL_Z0 + 0.5 * (RAIL_Z1 - RAIL_Z0 - (n - 1) * 0.0100 - 0.0048)
    for k in range(n):
        m.merge(extrude_z(land, (), z + k * 0.0100, z + k * 0.0100 + 0.0048, material="steel_dark"))
    # clamp lever on the right, screws on the left
    m.merge(sh.box(0.0098, 0.0230, RAIL_Z1 - 0.0420, 0.0128, 0.0320, RAIL_Z1 - 0.0160, 0.0010, "steel_dark"))
    m.merge(_stud(0.0275, RAIL_Z1 - 0.0300, 0.0128, 1, 0.0042, 0.0030, seg=16))
    for z0 in (RAIL_Z0 + 0.0075, RAIL_Z1 - 0.0075):
        m.merge(_stud(0.0255, z0, -REC_W - 0.0019, -1, 0.0024, 0.0010))
    return m


# ------------------------------------------------------------------------------------ furniture
def _hg_section(z):
    """Slim handguard section (x, y), CCW: a deep rounded U round the barrel whose flat top meets the cocking tube's
    sides; a palm swell along it and the flared hand stop at the front."""
    u = (z - HG_Z0) / (HG_Z1 - HG_Z0)
    swell = 1.0 + 0.05 * np.sin(np.pi * np.clip((u - 0.10) / 0.75, 0.0, 1.0))
    flare = 0.0022 * (1.0 - smooth(0.0, 0.055, u)) + 0.0006 * smooth(0.93, 1.0, u)
    w = HG_W * swell + flare
    yb = HG_Y0 - flare
    yc = -0.0020
    rc = 0.0042
    pts = []
    n = 8
    for a in np.linspace(-np.pi / 2, 0.0, n + 1):                       # lower right quarter (superellipse)
        c, s = np.cos(a), np.sin(a)
        pts.append((w * np.sign(c) * abs(c) ** (2 / 2.6), yc + (yc - yb) * np.sign(s) * abs(s) ** (2 / 2.6)))
    top_w = w - 0.0010
    pts.append((top_w, HG_YT - rc))
    pts += _arc(top_w - rc, HG_YT - rc, rc, 0.0, 0.5 * np.pi, 3)[1:]
    pts += _arc(-top_w + rc, HG_YT - rc, rc, 0.5 * np.pi, np.pi, 3)[:-1]
    pts.append((-top_w, HG_YT - rc))
    for a in np.linspace(np.pi, 1.5 * np.pi, n + 1)[:-1]:               # lower left quarter
        c, s = np.cos(a), np.sin(a)
        pts.append((w * np.sign(c) * abs(c) ** (2 / 2.6), yc + (yc - yb) * np.sign(s) * abs(s) ** (2 / 2.6)))
    return _dedupe(pts)


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def handguard_mesh():
    """Slim polymer handguard with its front hand stop, palm swell, a rear lip and the retaining pin."""
    zs = [HG_Z0, HG_Z0 + 0.0040, HG_Z0 + 0.0085, -0.1200, -0.0900, -0.0600, -0.0350,
          HG_Z1 - 0.0050, HG_Z1 - 0.0010, HG_Z1]
    rings = []
    for k, z in enumerate(zs):
        sec = _hg_section(z)
        if k == len(zs) - 1:
            sec = sh.offset_loop(sec, 0.0010)
        rings.append(_ring_xy(sec, z))
    # the hand stop's front face rounded over (the rear end butts against the receiver)
    rings, soft = sd.round_end(rings, 0.0028, at_end=False)
    m = sh.loft(rings, "handguard", soft_bands=soft)
    for s in (-1, 1):
        m.merge(_stud(0.0060, HG_Z1 - 0.0100, s * HG_W, s, 0.0022, 0.0008, sink=0.0012))
    return m


def stock_mesh():
    """A3 retractable stock, extended: the two rails in the receiver channels, the yoke and the butt cap."""
    z_rail0, z_yoke = REC_Z1 - 0.0200, STOCK_Z1 - 0.0350
    m = Mesh()
    for s in (-1, 1):
        x0, x1 = sorted((s * (RAIL_X - 0.0014), s * (RAIL_X + 0.0014)))
        m.merge(sh.box(x0, RAIL_YC - 0.0042, z_rail0, x1, RAIL_YC + 0.0042, z_yoke + 0.0060, 0.0008, "steel"))
        m.merge(_stud(RAIL_YC, z_yoke + 0.0020, s * (RAIL_X + 0.0014), s, 0.0018, 0.0008, "steel"))
    m.merge(sh.box(-0.0175, RAIL_YC - 0.0080, z_yoke - 0.0040, 0.0175, RAIL_YC + 0.0070, z_yoke + 0.0110, 0.0018,
                   "steel"))                                                           # yoke across the rails
    # butt cap: tall and narrow, its rear face a gentle curve, hung mostly under the rails
    front = [(z_yoke + 0.0050, 0.0250), (z_yoke + 0.0040, -0.0300), (z_yoke + 0.0080, -0.0720),
             (z_yoke + 0.0110, -0.0860)]
    rear = [(STOCK_Z1 - 0.0010, -0.0880), (STOCK_Z1 + 0.0004, -0.0600), (STOCK_Z1, -0.0200), (STOCK_Z1 - 0.0008, 0.0180),
            (STOCK_Z1 - 0.0040, 0.0300)]
    top = [(STOCK_Z1 - 0.0120, 0.0335), (z_yoke + 0.0120, 0.0320)]
    prof = np.array(front + rear + top)
    m.merge(sh.extrude_x(prof, (), 0.0180, round_bevel(0.0045, 3), "stock"))
    # rubber pad face: shallow ribs across the butt
    for k in range(4):
        y = -0.0680 + k * 0.0240
        m.merge(sd.rib((-0.0120, y, STOCK_Z1 - 0.0004), (0.0120, y, STOCK_Z1 - 0.0004), 0.0080, 0.0014, (0, 0, 1),
                       "stock", seg=9))
    return m


def housing_mesh():
    """Polymer trigger group: the housing under the receiver (paddle release well, the pins' bosses), the wide trigger
    guard and the selector's markings boss."""
    prof = [(HOUSING_Z0, -0.0195), (HOUSING_Z0, -0.0330), (HOUSING_Z0 + 0.0060, -0.0370), (0.0700, -0.0378),
            (0.1100, -0.0400), (0.1500, -0.0405), (0.1900, -0.0370), (0.2200, -0.0335), (HOUSING_Z1, -0.0310),
            (HOUSING_Z1, -0.0195)]
    m = sh.extrude_x(np.array(prof), (), HOUSING_W, round_bevel(0.0020, 2), "polymer")
    guard_out = [(0.0480, -0.0350), (0.0500, -0.0560), (0.0580, -0.0650), (0.0720, -0.0690), (0.0900, -0.0690),
                 (0.1040, -0.0640), (0.1120, -0.0520), (0.1140, -0.0380)]
    guard_in = [(0.0565, -0.0372), (0.0575, -0.0540), (0.0630, -0.0605), (0.0740, -0.0635), (0.0890, -0.0635),
                (0.0990, -0.0595), (0.1045, -0.0505), (0.1060, -0.0380)]
    m.merge(sh.extrude_x(_chaikin(guard_out, 1), [_chaikin(guard_in, 1)], 0.0080,
                         round_bevel(0.0018, 2), "polymer"))
    # selector boss on the left with the S / E / F detent bumps, axle boss on the right
    m.merge(_stud(SELECTOR_PIVOT[1], SELECTOR_PIVOT[2], -HOUSING_W, -1, 0.0075, 0.0010, "polymer", 24))
    for a in (60.0, 15.0, -30.0):
        r = 0.0098
        y, z = SELECTOR_PIVOT[1] + r * np.sin(np.radians(a)), SELECTOR_PIVOT[2] - r * np.cos(np.radians(a))
        m.merge(_stud(y, z, -HOUSING_W, -1, 0.0011, 0.0006, "polymer", 8))
    m.merge(_stud(SELECTOR_PIVOT[1], SELECTOR_PIVOT[2], HOUSING_W, 1, 0.0045, 0.0012, "steel_dark", 16))
    return m


def pistol_grip_mesh():
    """Polymer grip raked 21 degrees: full back strap, a slight swell, flared base."""
    rings = []
    n = 10
    for k in range(n + 1):
        u = k / n
        d = -0.0050 + (GRIP_LEN + 0.0050) * u
        c = grip_center(d)
        depth = 0.0200 + 0.0016 * np.sin(np.pi * u) + 0.0020 * smooth(0.85, 1.0, u)
        w = 0.0128 + 0.0010 * np.sin(np.pi * u) + 0.0012 * smooth(0.85, 1.0, u)
        sec = sh.superellipse(w, depth, 2.5, 24, n_front=2.2)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * GRIP_FRONT for p in sec]))
    c = grip_center(GRIP_LEN + 0.0052)                                 # the base, rounded over
    rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * GRIP_FRONT
                           for p in sh.superellipse(w, depth, 2.5, 24, n_front=2.2)]))
    rings, soft = sd.round_end(rings, 0.0050)
    return sh.loft(rings, "polymer", soft_bands=soft)


# ------------------------------------------------------------------------------------ moving parts
def carrier_mesh():
    """Bolt carrier and bolt head (node `carrier`): shows through the ejection port, its right roller and the extractor
    at the front; rides back when firing."""
    m = _cyl(0.0104, 0.0130, 0.1150, "steel_dark", 24, y=0.0080, chamfer=0.0010)
    m.merge(_cyl(0.0086, -0.0004, 0.0150, "steel_dark", 24, y=0.0020, chamfer=0.0008))        # bolt head
    m.merge(_stud(0.0020, 0.0100, 0.0070, 1, 0.0032, 0.0028, seg=14))                        # right roller
    m.merge(sh.box(0.0060, 0.0040, -0.0006, 0.0094, 0.0080, 0.0120, 0.0005, "steel_dark"))         # extractor
    return m


def trigger_mesh():
    z = TRIGGER_PIVOT[2]
    y = TRIGGER_PIVOT[1]
    blade = [(z - 0.0040, y + 0.0040), (z + 0.0030, y + 0.0040), (z + 0.0030, y - 0.0080), (z + 0.0005, y - 0.0170),
             (z - 0.0030, y - 0.0235), (z - 0.0060, y - 0.0260), (z - 0.0078, y - 0.0250), (z - 0.0055, y - 0.0210),
             (z - 0.0035, y - 0.0150), (z - 0.0028, y - 0.0080)]
    return sh.extrude_x(np.array(blade), (), 0.0032, round_bevel(0.0010, 2), "steel_dark")


def selector_mesh():
    """SEF selector lever on the left (rotates about X at its axle): hub and the lever pointing forward (full auto)."""
    p = SELECTOR_PIVOT
    m = _stud(p[1], p[2], p[0] - 0.0004, -1, 0.0050, 0.0026, seg=18)
    lever = [(p[2] + 0.0040, p[1] + 0.0035), (p[2] - 0.0150, p[1] + 0.0020), (p[2] - 0.0205, p[1] + 0.0030),
             (p[2] - 0.0225, p[1] + 0.0010), (p[2] - 0.0220, p[1] - 0.0030), (p[2] - 0.0150, p[1] - 0.0028),
             (p[2] + 0.0040, p[1] - 0.0035)]
    m.merge(sh.extrude_x(np.array(lever), (), 0.0011, round_bevel(0.0004, 1), "steel_dark", x_center=p[0] - 0.0036))
    m.merge(sh.box(p[0] - 0.0062, p[1] - 0.0028, p[2] - 0.0222, p[0] - 0.0036, p[1] + 0.0030, p[2] - 0.0172, 0.0007,
                   "steel_dark"))                                                     # thumb tab
    return m


def paddle_mesh():
    """Paddle magazine release behind the well (node `paddle`, rotates about X at its pivot: pushed forward)."""
    p = PADDLE_PIVOT
    prof = [(p[2] + 0.0030, p[1] + 0.0030), (p[2] - 0.0025, p[1] + 0.0030), (p[2] - 0.0040, p[1] - 0.0080),
            (p[2] - 0.0005, p[1] - 0.0150), (p[2] + 0.0040, p[1] - 0.0172), (p[2] + 0.0062, p[1] - 0.0160),
            (p[2] + 0.0030, p[1] - 0.0120), (p[2] + 0.0018, p[1] - 0.0060)]
    m = sh.extrude_x(np.array(prof), (), 0.0080, round_bevel(0.0010, 2), "steel_dark")
    for k in range(3):                                             # finger ribs on its rear face
        y = p[1] - 0.0085 - 0.0024 * k
        z = p[2] + 0.0030 + 0.0011 * k
        m.merge(sd.rib((-0.0068, y, z), (0.0068, y, z), 0.0012, 0.0007, (0, -0.35, 1), "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ magazines
def _mag_spine(length):
    """Centre line of the curved magazine in (z, y) of mag space (top at the origin) and the tangent angles: straight
    for the top 35 mm, then the forward sweep."""
    n = max(8, int(round(length / 0.0170)))
    curve, straight = 0.32, 0.035
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


def _mag_section(depth, rib):
    """(x, f) loop of the magazine body (f towards the front), CCW seen from above: rounded rectangle with a pressed
    rib along each side (rib = its height)."""
    hw, r = MAG_HW, 0.0035
    f0, f1 = -0.0070, 0.0050                        # rib band (behind the middle: the cartridges' bodies)
    pts = []
    pts += _arc(hw - r, -depth + r, r, -0.5 * np.pi, 0.0, 2)                 # back right corner
    pts += [(hw, f0), (hw + rib, f0 + 0.0012), (hw + rib, f1 - 0.0012), (hw, f1)]
    pts += _arc(hw - r, depth - r, r, 0.0, 0.5 * np.pi, 2)                   # front right
    pts += _arc(-hw + r, depth - r, r, 0.5 * np.pi, np.pi, 2)                # front left
    pts += [(-hw, f1), (-hw - rib, f1 - 0.0012), (-hw - rib, f0 + 0.0012), (-hw, f0)]
    pts += _arc(-hw + r, -depth + r, r, np.pi, 1.5 * np.pi, 2)               # back left
    # (x, f) with f to the front is mirrored relative to (x, y): reverse to keep it CCW for the lofts' frames
    return np.array(pts)


def _round_mesh(seg=12):
    """One 9x19 round along -Z, base at z = 0: the brass case and the round-nosed FMJ bullet (brass too)."""
    prof = [(0.0, 0.0), (0.00480, 0.0), (0.00495, -0.0006), (0.00495, -0.0010), (0.00400, -0.0013),
            (0.00400, -0.0022), (0.00493, -0.0026), (0.00482, -0.0191), (0.00450, -0.0193), (0.00440, -0.0230),
            (0.00380, -0.0262), (0.00260, -0.0286), (0.0, -0.0297)]
    return sh.revolve(prof, seg, "brass")


def mag_mesh(extended=False):
    """Curved steel magazine (mag-local: +Y up into the well, -Z front): ribbed body, feed lips with the top round and
    the floor plate with its locking tab."""
    length = MAG_LEN_EXT if extended else MAG_LEN
    spine, angs = _mag_spine(length)
    rings = []
    nk = len(spine) - 1
    for k, ((z, y), a) in enumerate(zip(spine, angs)):
        u = k / nk
        depth = MAG_HD + 0.0006 * u
        rib = 0.0007 * smooth(0.037, 0.050, u * length) * (1 - smooth(0.90, 1.0, u))
        fwd = np.array([0.0, np.sin(a), -np.cos(a)])
        c = np.array([0.0, y, z])
        sec = _mag_section(depth, rib)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * fwd for p in sec]))
    m = sh.loft(rings, "mag")
    # feed lips at the top (over the rear two thirds) and the top round, staggered to the right
    for s in (-1, 1):
        x0, x1 = sorted((s * 0.0032, s * (MAG_HW - 0.0002)))
        m.merge(sh.box(x0, -0.0010, -0.0060, x1, 0.0042, MAG_HD - 0.0008, 0.0008, "mag"))
    m.merge(_round_mesh(16).translated((0.0018, 0.0034, MAG_HD - 0.0016)))
    # catch lug on the back face, inside the well when seated
    m.merge(sh.box(-0.0045, -0.0260, MAG_HD - 0.0004, 0.0045, -0.0215, MAG_HD + 0.0018, 0.0006, "mag"))
    # floor plate and its tab
    (z, y), a = spine[-1], angs[-1]
    fwd = np.array([0.0, np.sin(a), -np.cos(a)])
    up = np.cross(fwd, (1.0, 0, 0))
    depth = MAG_HD + 0.0006
    plate = sh.box(-MAG_HW - 0.0016, -0.0048, -depth - 0.0020, MAG_HW + 0.0016, 0.0008, depth + 0.0016, 0.0014,
                   "mag_base")
    plate.merge(sh.box(-0.0060, -0.0040, -depth - 0.0045, 0.0060, -0.0008, -depth - 0.0010, 0.0008, "mag_base"))
    m.merge(sh.place(plate, origin=np.array([0.0, y, z]), x_axis=(1, 0, 0), y_axis=-up, z_axis=-fwd))
    return m


def mag_well_mesh():
    """Magazine well under the receiver: an open sleeve round the magazine with a flared lip at the bottom."""
    outline = sh.rounded_rect(-MAG_HW - 0.0014, WELL_Z0, MAG_HW + 0.0014, WELL_Z1, 0.0030, 2)
    hole = sh.rounded_rect(-MAG_HW - 0.0004, WELL_Z0 + 0.0010, MAG_HW + 0.0004, WELL_Z1 - 0.0010, 0.0022, 2)
    sleeve = extrude_z(outline, [hole[::-1]], -(REC_Y0 + 0.0006), -WELL_Y1, chamfer_bevel(0.0005), "steel")
    # extrude_z runs along +Z: turn it so the sleeve hangs along -Y (its outline in the x-z plane)
    flip = np.eye(4)
    flip[:3, :3] = np.column_stack([(1, 0, 0), (0, 0, 1), (0, -1, 0)])
    m = sleeve.transformed(flip)
    lip_o = sh.rounded_rect(-MAG_HW - 0.0024, WELL_Z0 - 0.0010, MAG_HW + 0.0024, WELL_Z1 + 0.0010, 0.0036, 2)
    lip = extrude_z(lip_o, [hole[::-1]], -WELL_Y1 - 0.0040, -WELL_Y1, chamfer_bevel(0.0009), "steel")
    m.merge(lip.transformed(flip))
    return m


def shell_mesh(scale=1.15):
    """Spent 9x19 brass case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0096), (0.0045, 0.0096), (0.00495, 0.0090), (0.00495, 0.0086), (0.0040, 0.0083),
            (0.0040, 0.0074), (0.00493, 0.0070), (0.00482, -0.0096), (0.0044, -0.0096), (0.0044, -0.0080),
            (0.0, -0.0080)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 16, "brass")
