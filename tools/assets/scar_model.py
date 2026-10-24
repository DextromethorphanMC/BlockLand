"""FN SCAR-H (Mk 17, 7.62x51 mm) geometry, VIEWMODEL_SPEC sections 3-6, 19-21 and 40. Design frame (metres): +X right,
+Y up, +Z rearward; bore axis on x = y = 0, z = 0 at the breech face. Real-world scale: overall 0.965 m with the stock
extended, a 406 mm (16 in) barrel with the SCAR flash hider, the long extruded aluminium upper with its continuous top
rail (MIL-STD-1913: 10 mm slot pitch, 5.2 mm slots), bolt-on side rails at the front and a bottom rail under the
handguard, the polymer lower with the flared magazine well, the integral trigger guard, the ambidextrous selector and
magazine releases, the bolt catch on the left, the reciprocating charging handle in its slot on the LEFT (it rides on
the bolt carrier), the ejection port with its brass deflector on the right, the flip-up front (gas block) and rear
(aperture) sights, the gas regulator, and the side-folding, telescoping polymer stock with its cheek riser.

Built from bevelled extrusions, lofts and lathes (shapes.py); holes are real (the port shows the carrier and the top
round, the slot shows the carrier, the rear sight is a through aperture, the flash hider slots are open). Every builder
returns a meshkit.Mesh in the design frame; gen_scar moves meshes into node-local space."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions -----------------------------------------------------------------------------------------------
UP_Z0, UP_Z1 = -0.3000, 0.2360          # upper receiver front (handguard end) / rear face (stock hinge)
HG_Z1 = -0.0620                         # handguard section ends here (the receiver walls carry on behind)
WALL_X, WALL_IN = 0.0184, 0.0157        # receiver wall outer face / inner face
TOP_X = 0.0185                          # top block side face (0.1 mm proud of the walls: no coplanar faces)
TOP_Y0 = 0.0135                         # top block bottom (the walls tuck into it)
WALL_Y0 = -0.0200                       # receiver walls bottom (into the lower)
HG_Y0 = -0.0255                         # handguard floor (the bottom rail sits on it)
RAIL_TOP = 0.0360                       # top of the picatinny teeth
RAIL_BASE = 0.0330                      # slot bottoms
RAIL_Z0, RAIL_Z1 = -0.2990, 0.2290
RAIL_PITCH, SLOT_W = 0.0100, 0.0052
SIGHT_Y = 0.0700                        # sight line: front post tip / rear aperture centre
FRONT_POST_Z = -0.3120
REAR_SIGHT_Z = 0.2040                   # rear face of the aperture leaf
BARREL_R = 0.0098
CROWN_Z = -0.4060
MUZZLE_Z = -0.4570                      # front face of the flash hider
HIDER_Z1 = -0.3985                      # rear end of the flash hider (jam nut)
GAS_Z0, GAS_Z1 = -0.3270, -0.3000       # gas block front / rear
PISTON_Y = 0.0175
LOW_Z0, LOW_Z1 = -0.0740, 0.2300        # lower receiver front / rear
LOW_X = 0.0195
LOW_TOP, LOW_Y0 = -0.0190, -0.0425      # lower top (parting line) / bottom line behind the magazine well
WELL_Y1 = -0.0800                       # magazine well bottom lip
MAG_ORIGIN = np.array([0.0, -0.0140, 0.0380])   # seated magazine pivot: top centre of the feed lips
MAG_HW, MAG_HD = 0.0130, 0.0435         # magazine body half width / half depth (front -Z, rear +Z)
WELL_HW, WELL_Z0, WELL_Z1 = 0.0136, -0.0066, 0.0826   # magazine well opening (inner)
GRIP_TOP = np.array([0.0, LOW_Y0, 0.1630])
GRIP_ANGLE = 20.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])     # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])
GRIP_LEN = 0.1000
TRIGGER_PIVOT = np.array([0.0, -0.0330, 0.1100])
SELECTOR_PIVOT = np.array([0.0, -0.0290, 0.1690])
BOLT_CATCH_PIVOT = np.array([-LOW_X, -0.0255, 0.0990])
MAG_RELEASE = np.array([LOW_X, -0.0345, 0.0955])   # mirrored for the left button
HANDLE_Z, HANDLE_Y = -0.0400, 0.0060    # charging handle (left) at rest
BOLT_TRAVEL = 0.1080                    # carrier / charging handle stroke
BOLT_HOLD = 0.0990                      # where the bolt catch holds the carrier open (empty)
PORT_Z0, PORT_Z1, PORT_Y0, PORT_Y1 = 0.0300, 0.1000, -0.0100, 0.0115
SLOT_Z0, SLOT_Z1 = HANDLE_Z - 0.0085, HANDLE_Z + BOLT_TRAVEL + 0.0075
SLOT_Y0, SLOT_Y1 = HANDLE_Y - 0.0036, HANDLE_Y + 0.0036
EJECTION = np.array([WALL_X + 0.0010, 0.0010, 0.0700])
OPTIC_Z = 0.1350                        # an optic's base centre on the top rail (eye relief behind the receiver)
SIDE_RAIL = (-0.2920, -0.1960)          # bolt-on side rails (3 and 9 o'clock) at the front
BOTTOM_RAIL = (-0.2920, -0.1220)        # bolt-on bottom rail under the handguard
RAIL_H = 0.0110                         # height of a bolt-on rail over its mounting face
STOCK_Z0, BUTT_Z = 0.2500, 0.5090       # stock body front (behind the hinge) / butt pad rear face
HINGE = np.array([0.0140, 0.0, 0.2445])  # folding hinge knuckle axis (vertical, on the right)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# ---------------------------------------------------------------------------------------------------- helpers
def extrude_y(loop_xz, holes=(), y0=0.0, y1=0.01, bevel=((0.0, 0.0),), material="default"):
    """Extrude an (x, z) footprint along Y from y0 to y1 (extrude_x with the axes swapped)."""
    hw = 0.5 * (y1 - y0)
    zx = lambda p: np.asarray(p, float)[:, ::-1]
    m = sh.extrude_x(zx(loop_xz), [zx(h) for h in holes], hw, bevel, material, x_center=y0 + hw)
    swap = np.eye(4)
    swap[:3, :3] = np.array([[0, 1, 0], [1, 0, 0], [0, 0, 1]], float)      # (X, x, z) -> (x, X, z)
    return m.transformed(swap)


def prism_x(profile_zy, holes=(), x0=0.0, x1=0.01, bevel=((0.0, 0.0),), material="default"):
    """Extrude a (z, y) side profile across X from x0 to x1."""
    return sh.extrude_x(np.asarray(profile_zy, float), [np.asarray(h, float) for h in holes], 0.5 * (x1 - x0),
                        bevel, material, x_center=0.5 * (x0 + x1))


def cyl(r, a0, a1, center, axis, material, seg=12, chamfer=0.0, precision=True):
    """Cylinder of radius r along `axis` ("x", "y", "z") from a0 to a1 (coordinates on that axis), centred at the
    other two coordinates of `center`; optional 45 degree chamfer on both ends."""
    c = min(chamfer, 0.45 * abs(a1 - a0), 0.9 * r)
    prof = [(0.0, a0), (r - c, a0), (r, a0 + c), (r, a1 - c), (r - c, a1), (0.0, a1)] if c > 0 else \
        [(0.0, a0), (r, a0), (r, a1), (0.0, a1)]
    m = sh.revolve(prof, seg, material, precision=precision)
    cx, cy, cz = (float(v) for v in center)
    if axis == "x":
        m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
        return m.translated((0.0, cy, cz))
    if axis == "y":
        m = sh.place(m, x_axis=(0, 0, 1), y_axis=(1, 0, 0), z_axis=(0, 1, 0))
        return m.translated((cx, 0.0, cz))
    return m.translated((cx, cy, 0.0))


def pin_head(center, side, r=0.0028, h=0.0007, material="steel"):
    """Domed pin / rivet head on a side face (side = +1 right, -1 left), sunk 0.4 mm into the face."""
    m = sh.revolve([(0.0, h), (r * 0.72, h * 0.78), (r, 0.0), (r, -0.0004), (0.0, -0.0004)], 10, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(side, 0, 0))
    return m.translated(center)


def socket_screw(center, normal, r=0.0026, h=0.0016, recess=0.0011, material="steel_dark"):
    """Socket head cap screw: a chamfered head with a round socket recess, along `normal` (out of the face)."""
    prof = [(0.0, h - 0.0010), (recess, h - 0.0010), (recess, h), (r, h - 0.0004), (r, -0.0004), (0.0, -0.0004)]
    m = sh.revolve(prof, 8, material, start_angle=np.pi / 8)
    n = np.asarray(normal, float)
    n = n / np.linalg.norm(n)
    a = np.array([0.0, 1.0, 0.0]) if abs(n[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    x = np.cross(a, n)
    x /= np.linalg.norm(x)
    m = sh.place(m, x_axis=x, y_axis=np.cross(n, x), z_axis=n)
    return m.translated(center)


def tooth_profile(top, half=0.0106, bevel=0.0023, depth=0.0034):
    """Cross-section (u across, v up) of one picatinny tooth: the 45 degree top bevels and the flat sides."""
    return np.array([(-half, top - depth), (half, top - depth), (half, top - bevel), (half - bevel, top),
                     (-half + bevel, top), (-half, top - bevel)])


def teeth(z0, z1, top, material, place=None):
    """Picatinny teeth between z0 and z1 (slot centres on the 10 mm pitch), each a chamfered prism standing on the
    rail base (slot bottoms at top - 3 mm). `place(mesh)` maps the (u, v, z) rail frame into the design frame."""
    m = Mesh()
    sec = tooth_profile(top)
    tooth = RAIL_PITCH - SLOT_W
    n = int(np.floor((z1 - z0 - tooth) / RAIL_PITCH)) + 1
    start = 0.5 * (z0 + z1) - 0.5 * ((n - 1) * RAIL_PITCH + tooth)
    for k in range(n):
        a = start + k * RAIL_PITCH
        m.merge(sh.prism_z(sec, a, a + tooth, 0.0, material))
    return place(m) if place else m


def rail_strip(z0, z1, material, place):
    """A bolt-on picatinny rail (u across, v out of the mounting face, along z): the mounting plate, the dovetail
    neck and undercut, the slot bottoms and the teeth; `place` maps it onto its face."""
    base = np.array([(-0.0118, 0.0000), (0.0118, 0.0000), (0.0118, 0.0022), (0.0108, 0.0032), (0.0080, 0.0036),
                     (0.0080, 0.0050), (0.0104, 0.0074), (0.0104, RAIL_H - 0.0030), (-0.0104, RAIL_H - 0.0030),
                     (-0.0104, 0.0074), (-0.0080, 0.0050), (-0.0080, 0.0036), (-0.0108, 0.0032), (-0.0118, 0.0022)])
    m = extrude_z(base, (), z0, z1, chamfer_bevel(0.0006), material)
    m.merge(teeth(z0 + 0.0010, z1 - 0.0010, RAIL_H, material))
    return place(m)


def shell_loft(outer, inner, material):
    """Closed tubular shell: outer rings and inner rings (same count and vertex count each, top to bottom), joined at
    both ends by flat lips. Consistently wound, outward."""
    m = Mesh()
    K, N = len(outer), len(outer[0])
    ob = m.add_vertices(np.concatenate(outer))
    ib = m.add_vertices(np.concatenate(inner))
    o = lambda k, i: ob + k * N + (i % N)
    q = lambda k, i: ib + k * N + (i % N)
    for k in range(K - 1):
        for i in range(N):
            m.add_face([o(k, i), o(k, i + 1), o(k + 1, i + 1), o(k + 1, i)], material)
            m.add_face([q(k, i), q(k + 1, i), q(k + 1, i + 1), q(k, i + 1)], material)
    for i in range(N):
        m.add_face([o(K - 1, i), o(K - 1, i + 1), q(K - 1, i + 1), q(K - 1, i)], material)
        m.add_face([o(0, i), q(0, i), q(0, i + 1), o(0, i + 1)], material)
    return sh.orient_outward(m)


def ring_y(loop_xz, y):
    loop_xz = np.asarray(loop_xz, float)
    return np.stack([loop_xz[:, 0], np.full(len(loop_xz), y), loop_xz[:, 1]], 1)


def ring_z(loop_xy, z):
    loop_xy = np.asarray(loop_xy, float)
    return np.stack([loop_xy[:, 0], loop_xy[:, 1], np.full(len(loop_xy), z)], 1)


# --------------------------------------------------------------------------------------------- upper receiver
def top_block_section():
    """(x, y) section of the top of the extrusion: the side faces' upper ends, the bevelled shoulders, the receiver
    top and the rail's neck, undercut and slot bottoms (the teeth are separate prisms)."""
    right = [(TOP_X, TOP_Y0), (TOP_X, 0.0212), (0.0178, 0.0229), (0.0162, 0.0240), (0.0112, 0.0261),
             (0.0080, 0.0265), (0.0080, 0.0296), (0.0104, 0.0320), (0.0104, RAIL_BASE)]
    left = [(-x, y) for x, y in reversed(right)]
    return np.array(right + left)


def wall_profile(side):
    """(z, y) outline of one receiver wall: tall over the handguard (down to its floor), stepping up over the lower;
    the hole: the ejection port on the right, the charging handle slot on the left."""
    outer = [(UP_Z0, HG_Y0 + 0.0015), (UP_Z0 + 0.0015, HG_Y0), (HG_Z1 - 0.0080, HG_Y0), (HG_Z1, WALL_Y0),
             (UP_Z1, WALL_Y0), (UP_Z1, TOP_Y0 + 0.0005), (UP_Z0, TOP_Y0 + 0.0005)]
    if side > 0:
        hole = sh.rounded_rect(PORT_Z0, PORT_Y0, PORT_Z1, PORT_Y1, (0.0030, 0.0030, 0.0050, 0.0050), 3)
    else:
        hole = sh.rounded_rect(SLOT_Z0, SLOT_Y0, SLOT_Z1, SLOT_Y1, 0.0034, 3)
    return outer, hole


def receiver_mesh():
    """The extruded upper: top block with the continuous top rail, both receiver walls (port right, slot left), the
    handguard floor and front cap, the end plate, the bolt-on side and bottom rails, the brass deflector, the barrel
    clamp bolts, the stock hinge and its latch. Material `upper` behind the handguard section, `handguard` in front."""
    m = extrude_z(top_block_section(), (), UP_Z0, UP_Z1, chamfer_bevel(0.0009), "upper")
    m.merge(teeth(RAIL_Z0, RAIL_Z1, RAIL_TOP, "upper"))
    for side in (1, -1):
        outer, hole = wall_profile(side)
        x0, x1 = sorted((side * WALL_IN, side * WALL_X))
        m.merge(prism_x(outer, [hole], x0, x1, chamfer_bevel(0.0007), "upper"))
    # handguard floor under the barrel (between the walls) and the front cap round the barrel
    floor = sh.rounded_rect(-WALL_IN - 0.0003, HG_Y0 + 0.0004, WALL_IN + 0.0003, HG_Y0 + 0.0062, 0.0010, 2)
    m.merge(extrude_z(floor, (), UP_Z0 + 0.0006, HG_Z1, chamfer_bevel(0.0005), "upper"))
    cap = sh.rounded_rect(-WALL_IN - 0.0002, HG_Y0 + 0.0040, WALL_IN + 0.0002, TOP_Y0 + 0.0004, 0.0020, 2)
    bore = sh.circle(0.0, 0.0, BARREL_R + 0.0022, 24)
    m.merge(extrude_z(cap, [bore], UP_Z0 + 0.0004, UP_Z0 + 0.0090, chamfer_bevel(0.0004), "upper"))
    # end plate closing the rear of the receiver (the hinge block bolts onto it)
    plate = sh.rounded_rect(-WALL_IN - 0.0002, WALL_Y0 + 0.0003, WALL_IN + 0.0002, TOP_Y0 + 0.0004, 0.0015, 2)
    m.merge(extrude_z(plate, (), UP_Z1 - 0.0080, UP_Z1, chamfer_bevel(0.0003), "upper"))
    # bolt-on rails: right / left at 3 and 9 o'clock, the bottom rail under the handguard (u across, v out)
    for side in (1, -1):
        m.merge(rail_strip(*SIDE_RAIL, "handguard", lambda r, s=side: sh.place(
            r, origin=(s * (WALL_X - 0.0003), 0.0005, 0.0), x_axis=(0, s, 0), y_axis=(s, 0, 0), z_axis=(0, 0, 1))))
    m.merge(rail_strip(*BOTTOM_RAIL, "handguard", lambda r: sh.place(
        r, origin=(0.0, HG_Y0 + 0.0003, 0.0), x_axis=(-1, 0, 0), y_axis=(0, -1, 0), z_axis=(0, 0, 1))))
    # rail mounting screws and the barrel clamp bolts on the right (the barrel extension is clamped in the upper)
    for side in (1, -1):
        for z in (-0.1810, -0.1390):
            m.merge(socket_screw((side * WALL_X, 0.0010, z), (side, 0, 0), 0.0024, 0.0006, 0.0011))
    for z in (-0.0480, -0.0300):
        m.merge(socket_screw((WALL_X, -0.0085, z), (1, 0, 0), 0.0030, 0.0012, 0.0013))
    # brass deflector behind the ejection port: a wedge rising out of the wall whose front face turns the cases
    # forward, tapering back into the wall
    def dsec(out, inset):
        return np.array([(WALL_X - 0.0002, PORT_Y0 + inset), (WALL_X - 0.0002, PORT_Y1 - inset),
                         (WALL_X + out, PORT_Y1 - inset - 0.0010), (WALL_X + out, PORT_Y0 + inset + 0.0010)])
    rings = [ring_z(dsec(0.0006, 0.0026), PORT_Z1 + 0.0026), ring_z(dsec(0.0046, 0.0016), PORT_Z1 + 0.0058),
             ring_z(dsec(0.0046, 0.0016), PORT_Z1 + 0.0100), ring_z(dsec(0.0010, 0.0020), PORT_Z1 + 0.0230)]
    m.merge(sh.loft(rings, "upper"))
    # stock hinge: the block on the end plate and the knuckle on the right with its pin; the latch button left
    blk = sh.rounded_rect(-0.0150, -0.0300, 0.0150, 0.0225, (0.004, 0.004, 0.003, 0.003), 2)
    m.merge(extrude_z(blk, (), UP_Z1 - 0.0005, UP_Z1 + 0.0080, chamfer_bevel(0.0008), "steel"))
    m.merge(cyl(0.0042, -0.0270, 0.0200, HINGE, "y", "steel", 16, 0.0008))
    m.merge(cyl(0.0022, -0.0290, 0.0222, HINGE, "y", "steel_dark", 12, 0.0005))
    m.merge(cyl(0.0034, -0.0168, -0.0148, (0.0, 0.0040, UP_Z1 + 0.0050), "x", "steel_dark", 14, 0.0005))
    # material by section: the extrusion ahead of the receiver section is the handguard (region `handguard`)
    return _split_handguard(m)


def _split_handguard(m):
    """Faces of the `upper` material whose centre lies ahead of the receiver section become `handguard`."""
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        if m.m[i] == "upper" and v[list(f)].mean(0)[2] < HG_Z1 - 0.0040:
            m.m[i] = "handguard"
    return m


# ------------------------------------------------------------------------------------------ barrel and gas block
def barrel_mesh():
    """Barrel (heavier towards the gas block), the threaded muzzle under the flash hider, the gas block clamped
    round it at the front of the upper with the piston housing, the regulator knob with its lever, the clamp bolt."""
    m = sh.revolve([(0.0, CROWN_Z + 0.0008), (0.0062, CROWN_Z + 0.0008), (0.0084, CROWN_Z + 0.0020),
                    (0.0084, HIDER_Z1 + 0.0015), (BARREL_R * 0.96, HIDER_Z1 - 0.0005),
                    (BARREL_R, -0.3600), (BARREL_R * 1.06, UP_Z0 - 0.0010), (BARREL_R * 1.06, UP_Z0 + 0.0400),
                    (0.0, UP_Z0 + 0.0400)], 24, "barrel", precision=True)
    # gas block: collar round the barrel, the housing up to the piston, flats on top for the front sight
    pts = [(0.0130 * np.cos(a), 0.0130 * np.sin(a)) for a in np.radians(np.linspace(-30, -150, 9))]
    sec = np.array(pts + [(-0.0118, 0.0020), (-0.0118, 0.0262), (-0.0094, 0.0300), (0.0094, 0.0300),
                          (0.0118, 0.0262), (0.0118, 0.0020)])[::-1]
    m.merge(extrude_z(sec, (), GAS_Z0, GAS_Z1 + 0.0010, chamfer_bevel(0.0010), "barrel"))
    # regulator: knob on the piston axis at the front of the block, two flats and the selector lever
    m.merge(cyl(0.0080, GAS_Z0 - 0.0070, GAS_Z0 + 0.0005, (0.0, PISTON_Y, 0.0), "z", "barrel", 20, 0.0010))
    m.merge(cyl(0.0045, GAS_Z0 - 0.0095, GAS_Z0 - 0.0065, (0.0, PISTON_Y, 0.0), "z", "steel_dark", 16, 0.0006))
    lever = [(-0.0030, PISTON_Y + 0.0040), (0.0030, PISTON_Y + 0.0040), (0.0024, PISTON_Y + 0.0150),
             (-0.0024, PISTON_Y + 0.0150)]
    m.merge(extrude_z(np.array(lever), (), GAS_Z0 - 0.0062, GAS_Z0 - 0.0035, chamfer_bevel(0.0005), "steel_dark"))
    # cross bolt clamping the block (head on the right, nut on the left)
    m.merge(cyl(0.0024, -0.0145, 0.0145, (0.0, -0.0105, 0.5 * (GAS_Z0 + GAS_Z1)), "x", "steel_dark", 12, 0.0004))
    m.merge(socket_screw((0.0118, -0.0105, 0.5 * (GAS_Z0 + GAS_Z1)), (1, 0, 0), 0.0030, 0.0010, 0.0012))
    return m


def muzzle_brake_mesh():
    """The SCAR flash hider: a cylinder with four open slots round the front (real gaps between the prongs), the
    bore, the chamfered crown, the jam nut with its wrench flats at the rear (own node: hidden by a suppressor)."""
    r_out, r_in = 0.0112, 0.0072
    z_front, z_slots = MUZZLE_Z, MUZZLE_Z + 0.0300
    m = sh.revolve([(r_in, z_slots), (r_out, z_slots), (r_out, HIDER_Z1 - 0.0110), (r_out - 0.0008,
                    HIDER_Z1 - 0.0102), (0.0084, HIDER_Z1 - 0.0102), (0.0084, z_slots + 0.0010), (r_in, z_slots + 0.0010),
                    (r_in, z_slots)],
                   24, "barrel", precision=True)
    # four prongs between the slots (each a curved block, slots 3.4 mm wide)
    for k in range(4):
        a0 = np.radians(45 + 90 * k) + 0.0034 / r_out / 2 * 2
        a1 = np.radians(45 + 90 * (k + 1)) - 0.0034 / r_out / 2 * 2
        arc = np.linspace(a0, a1, 7)
        outer = [(r_out * np.cos(a), r_out * np.sin(a)) for a in arc]
        inner = [(r_in * np.cos(a), r_in * np.sin(a)) for a in arc[::-1]]
        sec = np.array(outer + inner)
        prong = sh.loft([ring_z(sh.offset_loop(sh.ccw(sec), 0.0006), z_front),
                         ring_z(sh.ccw(sec), z_front + 0.0008), ring_z(sh.ccw(sec), z_slots + 0.0006)], "barrel")
        m.merge(prong)
    # jam nut with flats at the rear
    nut = sh.rounded_rect(-0.0118, -0.0118, 0.0118, 0.0118, 0.0060, 3)
    m.merge(extrude_z(nut, [sh.circle(0.0, 0.0, 0.0086, 20)], HIDER_Z1 - 0.0085, HIDER_Z1, chamfer_bevel(0.0008),
                      "barrel"))
    return m


# ---------------------------------------------------------------------------------------------- iron sights
def front_sight_mesh():
    """Flip-up front sight on the gas block (node `sight_iron`): the clamp on the block, the leaf with two
    protective wings and the post between them (the post tip is the aiming point; the wings stop 1 mm under it)."""
    z = FRONT_POST_Z
    base = sh.rounded_rect(-0.0108, 0.0290, 0.0108, 0.0395, (0.0, 0.0, 0.003, 0.003), 2)
    m = extrude_z(base, (), z - 0.0085, z + 0.0080, chamfer_bevel(0.0007), "steel_dark")
    m.merge(cyl(0.0020, -0.0118, 0.0118, (0.0, 0.0360, z + 0.0050), "x", "steel_dark", 10, 0.0004))   # hinge pin
    leaf = [(-0.0095, 0.0390), (0.0095, 0.0390), (0.0095, 0.0450), (0.0092, SIGHT_Y - 0.0020),
            (0.0080, SIGHT_Y - 0.0006), (0.0056, SIGHT_Y - 0.0006), (0.0050, SIGHT_Y - 0.0020),
            (0.0050, 0.0490), (-0.0050, 0.0490), (-0.0050, SIGHT_Y - 0.0020), (-0.0056, SIGHT_Y - 0.0006),
            (-0.0080, SIGHT_Y - 0.0006), (-0.0092, SIGHT_Y - 0.0020), (-0.0095, 0.0450)]
    m.merge(extrude_z(np.array(leaf), (), z - 0.0020, z + 0.0025, chamfer_bevel(0.0004), "steel_dark"))
    post = np.array([(-0.0009, 0.0480), (0.0009, 0.0480), (0.0009, SIGHT_Y - 0.0005), (0.0005, SIGHT_Y),
                     (-0.0005, SIGHT_Y), (-0.0009, SIGHT_Y - 0.0005)])
    m.merge(extrude_z(post, (), z - 0.0012, z + 0.0012, chamfer_bevel(0.0002), "steel_dark"))
    return m


def rear_sight_mesh():
    """Flip-up rear sight on the rear of the top rail (node `sight_rear`): the clamp straddling the rail with its
    cross bolt, the elevation drum on the right, the leaf - a ghost-ring aperture on a narrow stem - and the
    protective wings."""
    z = REAR_SIGHT_Z
    zf, zr = z - 0.0130, z + 0.0170
    top = sh.rounded_rect(-0.0118, RAIL_TOP - 0.0002, 0.0118, RAIL_TOP + 0.0068, (0.0, 0.0, 0.003, 0.003), 2)
    m = extrude_z(top, (), zf, zr, chamfer_bevel(0.0008), "steel_dark")
    for s in (-1, 1):                                       # jaws down the rail's sides
        jaw = np.array([(0.0106, 0.0300), (0.0124, 0.0300), (0.0124, RAIL_TOP + 0.0010), (0.0106, RAIL_TOP + 0.0010)])
        jaw = jaw * [s, 1] if s > 0 else (jaw * [s, 1])[::-1]
        m.merge(extrude_z(jaw, (), zf + 0.0010, zr - 0.0010, chamfer_bevel(0.0004), "steel_dark"))
    m.merge(cyl(0.0018, -0.0140, 0.0140, (0.0, 0.0315, zr - 0.0060), "x", "steel_dark", 10, 0.0004))     # cross bolt
    # elevation drum on the right: twelve knurled flats
    m.merge(cyl(0.0050, 0.0118, 0.0158, (0.0, RAIL_TOP + 0.0040, z + 0.0030), "x", "steel_dark", 12, 0.0007, False))
    # the leaf: a ghost-ring aperture (a real 8.4 mm hole) on a narrow stem, open either side of it
    y0 = RAIL_TOP + 0.0062
    stem = np.array([(-0.0030, y0), (0.0030, y0), (0.0024, SIGHT_Y - 0.0050), (-0.0024, SIGHT_Y - 0.0050)])
    m.merge(extrude_z(stem, (), z, z + 0.0030, chamfer_bevel(0.0004), "steel_dark"))
    ring = sh.circle(0.0, SIGHT_Y, 0.0064, 20)
    m.merge(extrude_z(ring, [sh.circle(0.0, SIGHT_Y, 0.0042, 20)], z - 0.0008, z + 0.0038, chamfer_bevel(0.0006),
                      "steel_dark"))
    for s in (-1, 1):                                       # wings
        wing = np.array([(0.0084, y0), (0.0106, y0), (0.0106, SIGHT_Y + 0.0060), (0.0094, SIGHT_Y + 0.0082),
                         (0.0084, SIGHT_Y + 0.0078)])
        wing = wing * [s, 1] if s > 0 else (wing * [s, 1])[::-1]
        m.merge(extrude_z(wing, (), z - 0.0040, z + 0.0090, chamfer_bevel(0.0005), "steel_dark"))
    return m


# ------------------------------------------------------------------------------------------- lower receiver
def lower_mesh():
    """Polymer lower (trigger module): the body round the magazine well opening, the flared well, the tail under the
    stock hinge, the integral trigger guard, the takedown / trigger / hammer pins, the ambidextrous magazine
    release buttons and the textured front of the well."""
    foot = sh.rounded_rect(-LOW_X, LOW_Z0, LOW_X, 0.1500, (0.0040, 0.0040, 0.0025, 0.0025), 3)
    well = sh.rounded_rect(-WELL_HW, WELL_Z0, WELL_HW, WELL_Z1, 0.0022, 3)
    m = extrude_y(foot, [well], LOW_Y0, LOW_TOP, round_bevel(0.0012, 2), "lower")
    # tail: rises from behind the grip to the rear of the receiver
    tail = [(0.1380, LOW_TOP), (0.1380, LOW_Y0 + 0.0002), (0.1780, LOW_Y0 - 0.0018), (0.2000, LOW_Y0 + 0.0002),
            (0.2180, -0.0360), (LOW_Z1, -0.0300), (LOW_Z1 + 0.0010, -0.0240), (LOW_Z1, LOW_TOP)]
    m.merge(prism_x(tail[::-1], (), -LOW_X + 0.0004, LOW_X - 0.0004, round_bevel(0.0012, 2), "lower"))
    # the flared magazine well (shell round the opening)
    n_seg = 3
    def outer(hw, z0, z1, r):
        return sh.rounded_rect(-hw, z0, hw, z1, r, n_seg)
    inner_loop = sh.rounded_rect(-WELL_HW, WELL_Z0, WELL_HW, WELL_Z1, 0.0022, n_seg)
    outers = [ring_y(outer(0.0180, -0.0150, 0.0915, 0.0040), LOW_Y0),
              ring_y(outer(0.0182, -0.0158, 0.0922, 0.0042), -0.0640),
              ring_y(outer(0.0204, -0.0205, 0.0965, 0.0050), -0.0755),
              ring_y(outer(0.0216, -0.0226, 0.0985, 0.0055), WELL_Y1 + 0.0010),
              ring_y(outer(0.0208, -0.0216, 0.0977, 0.0050), WELL_Y1)]
    inners = [ring_y(inner_loop, y) for y in (LOW_Y0, -0.0640, -0.0755, WELL_Y1 + 0.0010)] + \
             [ring_y(sh.offset_loop(sh.ccw(inner_loop), -0.0012), WELL_Y1)]
    m.merge(shell_loft(outers, inners, "lower"))
    # grip ribs moulded into the front of the well (the support hand's thumb rests here)
    for k in range(5):
        y = -0.0500 - 0.0045 * k
        rib = sh.box(-0.0110, y - 0.0010, -0.0175 - 0.0008 * k, 0.0110, y + 0.0010, -0.0150, 0.0, "lower")
        m.merge(rib)
    # integral trigger guard
    g_out = [(0.0880, LOW_Y0 + 0.0010), (0.0880, -0.0600), (0.0935, -0.0675), (0.1040, -0.0705),
             (0.1460, -0.0705), (0.1560, -0.0675), (0.1600, -0.0590), (0.1580, LOW_Y0 + 0.0010)]
    g_in = [(0.0960, LOW_Y0 - 0.0015), (0.0960, -0.0585), (0.1005, -0.0640), (0.1080, -0.0660),
            (0.1440, -0.0660), (0.1505, -0.0630), (0.1515, -0.0555), (0.1470, LOW_Y0 - 0.0015)]
    m.merge(prism_x(g_out, [g_in], -0.0058, 0.0058, round_bevel(0.0014, 2), "lower"))
    # pins: front pivot, rear takedown, trigger and hammer pins (heads on both sides)
    for z, y, r in ((-0.0600, -0.0300, 0.0032), (0.2150, -0.0255, 0.0030), (TRIGGER_PIVOT[2], TRIGGER_PIVOT[1], 0.0022),
                    (0.1280, -0.0255, 0.0022)):
        for s in (-1, 1):
            m.merge(pin_head((s * LOW_X, y, z), s, r, 0.0006, "steel"))
    # ambidextrous magazine release buttons behind the well
    for s in (-1, 1):
        c = MAG_RELEASE * np.array([s, 1, 1])
        btn = sh.rounded_rect(c[2] - 0.0050, c[1] - 0.0040, c[2] + 0.0050, c[1] + 0.0040, 0.0025, 2)
        x0, x1 = sorted((s * (LOW_X - 0.0006), s * (LOW_X + 0.0022)))
        m.merge(prism_x(btn, (), x0, x1, round_bevel(0.0008, 2), "steel"))
        for k in range(3):                                     # grip grooves on the button face
            zz = c[2] - 0.0025 + 0.0025 * k
            x0, x1 = sorted((s * (LOW_X + 0.0019), s * (LOW_X + 0.0025)))
            m.merge(sh.box(x0, c[1] - 0.0030, zz - 0.0004, x1, c[1] + 0.0030, zz + 0.0004, 0.0, "steel"))
    # sling point on the left of the tail (the charm hangs here)
    m.merge(cyl(0.0034, -LOW_X - 0.0035, -LOW_X + 0.0005, (0.0, -0.0330, 0.2120), "x", "steel", 12, 0.0006, False))
    return m


def bolt_catch_mesh():
    """Bolt catch paddle on the left of the lower (node origin on its pivot pin, rotates about X: the top pressed
    in releases the carrier)."""
    p = BOLT_CATCH_PIVOT
    prof = [(p[2] - 0.0030, p[1] - 0.0030), (p[2] + 0.0040, p[1] - 0.0034), (p[2] + 0.0060, p[1] + 0.0010),
            (p[2] + 0.0040, p[1] + 0.0080), (p[2] - 0.0090, p[1] + 0.0080), (p[2] - 0.0120, p[1] + 0.0050),
            (p[2] - 0.0110, p[1] + 0.0010)]
    m = prism_x(prof[::-1], (), -LOW_X - 0.0024, -LOW_X + 0.0004, round_bevel(0.0007, 2), "steel")
    for k in range(3):
        z = p[2] - 0.0085 + 0.0030 * k
        m.merge(sh.box(-LOW_X - 0.0029, p[1] + 0.0028, z - 0.0005, -LOW_X - 0.0022, p[1] + 0.0074, z + 0.0005, 0.0,
                       "steel"))
    m.merge(pin_head((-LOW_X - 0.0024, p[1], p[2]), -1, 0.0016, 0.0005, "steel_dark"))
    return m


def selector_mesh():
    """Ambidextrous selector: the hub on both sides of the lower and a lever on each (node origin on the pivot,
    rotates about X)."""
    p = SELECTOR_PIVOT
    m = Mesh()
    for s in (-1, 1):
        x0, x1 = sorted((s * (LOW_X - 0.0004), s * (LOW_X + 0.0026)))
        m.merge(cyl(0.0048, x0, x1, p, "x", "steel", 14, 0.0006, False))
        lever = [(p[2] + 0.0035, p[1] - 0.0030), (p[2] + 0.0035, p[1] + 0.0030), (p[2] - 0.0130, p[1] + 0.0026),
                 (p[2] - 0.0175, p[1] + 0.0018), (p[2] - 0.0180, p[1] - 0.0016), (p[2] - 0.0130, p[1] - 0.0026)]
        x0, x1 = sorted((s * (LOW_X + 0.0016), s * (LOW_X + 0.0034)))
        m.merge(prism_x(lever[::-1], (), x0, x1, round_bevel(0.0006, 2), "steel"))
    return m


def trigger_mesh():
    """Curved trigger blade (node origin on its pin)."""
    z = TRIGGER_PIVOT[2]
    y = TRIGGER_PIVOT[1]
    blade = [(z - 0.0030, y + 0.0040), (z + 0.0050, y + 0.0040), (z + 0.0045, y - 0.0080), (z + 0.0010, y - 0.0160),
             (z - 0.0010, y - 0.0230), (z - 0.0002, y - 0.0262), (z - 0.0030, y - 0.0270), (z - 0.0055, y - 0.0230),
             (z - 0.0055, y - 0.0160), (z - 0.0045, y - 0.0080)]
    return prism_x(np.array(blade)[::-1], (), -0.0034, 0.0034, round_bevel(0.0011, 2), "steel_dark")


# --------------------------------------------------------------------------------------------- pistol grip
def pistol_grip_mesh():
    """Polymer pistol grip: a swelling oval section along the 20 degree axis, a finger ridge under the guard, the
    bottom cap and the moulded texture panels on both sides (raised pebbled pads)."""
    rings = []
    n = 12
    for k in range(n + 1):
        u = k / n
        d = -0.0050 + (GRIP_LEN + 0.0050) * u
        c = grip_center(d)
        depth = 0.0172 + 0.0026 * np.sin(np.pi * min(u * 1.2, 1.0)) - 0.0008 * u
        w = 0.0125 + 0.0018 * np.sin(np.pi * u)
        sec = sh.superellipse(w, depth, 2.5, 24, n_front=2.2)
        if k == n:
            sec = sh.offset_loop(sec, 0.0030)
        elif k == n - 1:
            sec = sh.offset_loop(sec, 0.0007)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * (-GRIP_FRONT) for p in sec]))
    m = sh.loft(rings, "grip")
    for s in (-1, 1):                                       # texture pads on the flanks
        pad = sh.rounded_rect(-0.0130, -0.0300, 0.0110, 0.0280, 0.0080, 3)    # (along -front, along axis)
        mid = grip_center(0.052) + s * np.array([0.0128, 0.0, 0.0])
        x_ax = np.array([s, 0.0, 0.0])
        p = extrude_z(pad, (), -0.0004, 0.0008, chamfer_bevel(0.0004), "grip")
        p = sh.place(p, origin=mid, x_axis=-GRIP_FRONT * s, y_axis=-GRIP_AXIS, z_axis=x_ax)
        m.merge(p)
    return m


# ------------------------------------------------------------------------------------------------ stock
def stock_mesh():
    """Side-folding, telescoping polymer stock (node `stock`, hidden while the stock slot holds an attachment): the
    front body on the hinge, the outer (butt) section telescoped over it with the step at the joint, the cheek riser
    on the comb with its release button, the length adjustment lever underneath, the sling loop and the rubber pad."""
    z0, z1 = STOCK_Z0, BUTT_Z - 0.0120
    zj = 0.3900                                             # where the butt section slides over the front body
    def top(z):
        u = (z - z0) / (z1 - z0)
        return 0.0225 + 0.0090 * min(u * 3.0, 1.0) ** 0.6
    def bot(z):
        u = (z - z0) / (z1 - z0)
        return -0.0270 - 0.0620 * u ** 1.05
    def half(z):
        u = (z - z0) / (z1 - z0)
        return 0.0140 + 0.0030 * u
    zs = list(np.linspace(z0, zj, 6)) + [zj + 0.0002] + list(np.linspace(zj + 0.006, z1, 6))
    rings = []
    for k, z in enumerate(zs):
        grow = 0.0 if z <= zj else 0.0018
        y0, y1, w = bot(z) - grow, top(z) + grow, half(z) + grow
        sec = sh.rounded_rect(-w, y0, w, y1, (0.0045, 0.0045, 0.0075, 0.0075), 3)
        if k == 0:
            sec = sh.offset_loop(sh.ccw(sec), 0.0010)
        rings.append(ring_z(sec, z))
    m = sh.loft(rings, "stock")
    # the step face where the butt section starts (a ring round the front body)
    # cheek riser pad on the comb, its release button at the front
    rz0, rz1 = 0.3300, 0.4750
    ry = top(rz1) + 0.0018
    riser = sh.rounded_rect(rz0, ry - 0.0040, rz1, ry + 0.0045, (0.002, 0.002, 0.006, 0.010), 3)
    m.merge(prism_x(riser, (), -0.0150, 0.0150, round_bevel(0.0018, 2), "stock"))
    m.merge(cyl(0.0032, -0.0040, 0.0040, (0.0, ry + 0.0002, rz0 - 0.0010), "x", "steel", 14, 0.0006))
    # length adjustment lever under the butt section, the sling loop on the left
    lev = sh.rounded_rect(zj + 0.010, bot(zj + 0.03) - 0.0060, zj + 0.046, bot(zj + 0.03) + 0.0020, 0.0025, 2)
    m.merge(prism_x(lev, (), -0.0060, 0.0060, round_bevel(0.0008, 2), "stock"))
    m.merge(cyl(0.0036, -half(0.47) - 0.0038, -half(0.47) + 0.0004, (0.0, bot(0.47) + 0.012, 0.47), "x", "steel", 14,
                0.0006))
    # hinge leaf on the front of the stock (joins the knuckle on the right)
    leaf = sh.rounded_rect(-0.0130, -0.0265, 0.0130, 0.0215, 0.0030, 2)
    m.merge(extrude_z(leaf, (), UP_Z1 + 0.0080, z0 + 0.0040, chamfer_bevel(0.0006), "steel"))
    # rubber butt pad: follows the butt outline, slightly larger, a sloping heel
    y0, y1, w = bot(z1) - 0.0024, top(z1) + 0.0024, half(z1) + 0.0024
    pad = sh.rounded_rect(-w, y0, w, y1, (0.0050, 0.0050, 0.0085, 0.0085), 3)
    m.merge(extrude_z(pad, (), z1 - 0.0004, BUTT_Z, round_bevel(0.0025, 2), "stock_pad"))
    return m


# ------------------------------------------------------------------------------------------- moving parts
def bolt_mesh():
    """Bolt carrier group (inside; seen through the ejection port and the slot) and the reciprocating charging
    handle through the slot on the LEFT: the stem, the arm and the angled paddle knob. Node origin at rest."""
    m = sh.box(-0.0128, -0.0108, 0.0000, 0.0128, 0.0118, 0.1180, 0.0018, "steel")          # carrier body
    m.merge(sh.box(-0.0090, 0.0050, -0.0560, 0.0090, 0.0118, 0.0020, 0.0012, "steel"))      # op-rod nose over the barrel
    m.merge(cyl(0.0078, -0.0100, 0.0030, (0.0, 0.0, 0.0), "z", "steel_dark", 14, 0.0010, False))   # bolt head
    # right face details seen in the port: the cam pin window and the extractor
    m.merge(sh.box(0.0122, -0.0060, 0.0180, 0.0132, 0.0040, 0.0420, 0.0003, "steel_dark"))
    m.merge(sh.box(0.0122, 0.0060, 0.0480, 0.0134, 0.0100, 0.0980, 0.0003, "steel_dark"))
    m.merge(sh.box(0.0070, -0.0030, -0.0105, 0.0090, 0.0040, 0.0020, 0.0003, "steel_dark"))   # extractor
    # charging handle: the stem through the slot, the arm and the paddle
    z, y = HANDLE_Z, HANDLE_Y
    m.merge(sh.box(-WALL_IN + 0.0008, y - 0.0030, z - 0.0070, -0.0090, y + 0.0030, z + 0.0070, 0.0006, "steel"))
    stem = [(z - 0.0060, y - 0.0029), (z + 0.0062, y - 0.0029), (z + 0.0062, y + 0.0029), (z - 0.0060, y + 0.0029)]
    m.merge(prism_x(stem, (), -WALL_X - 0.0035, -WALL_IN + 0.0010, chamfer_bevel(0.0005), "steel"))
    arm = [(z - 0.0058, y - 0.0040), (z + 0.0060, y - 0.0040), (z + 0.0045, y + 0.0045), (z - 0.0050, y + 0.0060)]
    m.merge(prism_x(arm, (), -WALL_X - 0.0105, -WALL_X - 0.0030, round_bevel(0.0010, 2), "steel"))
    knob = sh.rounded_rect(-0.0085, -0.0065, 0.0085, 0.0090, (0.0040, 0.0040, 0.0060, 0.0060), 3)   # (z, y) paddle
    k = prism_x(np.array([(z + p[0], y + p[1]) for p in knob]), (), -WALL_X - 0.0195, -WALL_X - 0.0095,
                round_bevel(0.0018, 2), "steel")
    m.merge(sh.rot_x(k, -18.0, (0.0, y, z)))
    for i in range(4):                                     # grip grooves on the paddle's outer face
        zz = z - 0.0045 + 0.0030 * i
        g = sh.box(-WALL_X - 0.0200, y - 0.0040, zz - 0.0005, -WALL_X - 0.0192, y + 0.0070, zz + 0.0005, 0.0,
                   "steel_dark")
        m.merge(sh.rot_x(g, -18.0, (0.0, y, z)))
    return m


# ------------------------------------------------------------------------------------------- magazines
def _mag_spine(extended):
    """Centre line (z, y) of the magazine in mag space (top at the origin) and its tangent angles: the 20-round one
    straight, the 30-round one straight through the well and then curving forward."""
    length = 0.2280 if extended else 0.1620
    straight = 0.0800 if extended else length
    radius = 0.55
    n = 10 if extended else 6
    pts, angs = [], []
    for k in range(n + 1):
        s = 0.0020 + (length - 0.0020) * k / n
        if s <= straight:
            pts.append((0.0, -s))
            angs.append(0.0)
        else:
            a = (s - straight) / radius
            pts.append((-(radius - radius * np.cos(a)), -straight - radius * np.sin(a)))
            angs.append(a)
    return np.array(pts), np.array(angs)


def _mag_section(hd, hw=MAG_HW):
    """(x, along-front) section: the front (cartridge tip end) rounder, a rear spine."""
    return sh.rounded_rect(-hw, -hd, hw, hd, (0.0030, 0.0030, 0.0055, 0.0055), 3)


def cartridge(seg=10):
    """7.62x51 mm NATO round along -Z, base at z = 0: brass case (rim, extraction groove, shoulder, neck) and the
    jacketed bullet (material brass as well: gilding metal)."""
    prof = [(0.0, 0.0), (0.0059, 0.0), (0.0060, -0.0012), (0.0050, -0.0016), (0.0050, -0.0030), (0.0060, -0.0034),
            (0.0058, -0.0394), (0.0044, -0.0430), (0.0044, -0.0512), (0.0039, -0.0512), (0.0039, -0.0560),
            (0.0034, -0.0620), (0.0022, -0.0680), (0.0007, -0.0710), (0.0, -0.0712)]
    return sh.revolve(prof, seg, "brass")


def mag_mesh(extended=False):
    """SCAR-H steel magazine (mag-local: +Y up into the well, -Z front): body with two pressed ribs per side and the
    rear spine, the feed lips with the top round, the floor plate with its front lip and the retaining tab."""
    spine, angs = _mag_spine(extended)
    rings, ribs = [], []
    for k, ((z, y), a) in enumerate(zip(spine, angs)):
        u = k / (len(spine) - 1)
        hd = MAG_HD + (0.0012 * u if extended else 0.0)
        fwd = np.array([0.0, np.sin(a), -np.cos(a)])
        c = np.array([0.0, y, z])
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * fwd for p in _mag_section(hd)]))
        ribs.append((c, fwd, hd))
    m = sh.loft(rings, "mag")
    # pressed ribs on both sides (raised, along the body) and the spine at the back
    for i in range(len(ribs) - 1):
        (c0, f0, hd0), (c1, f1, hd1) = ribs[i], ribs[i + 1]
        if extended and i == 0 or i == len(ribs) - 2:
            continue
        for s in (-1, 1):
            for off in (-0.0175, 0.0145):
                pa = c0 + np.array([s * (MAG_HW - 0.0003), 0, 0]) + off * f0
                pb = c1 + np.array([s * (MAG_HW - 0.0003), 0, 0]) + off * f1
                rib = _strip(pa, pb, np.array([s, 0.0, 0.0]), 0.0034 if off < 0 else 0.0026, 0.0008)
                m.merge(rib)
    # feed lips and the top round
    m.merge(sh.box(-0.0112, -0.0040, -0.0300, 0.0112, 0.0002, 0.0400, 0.0008, "mag"))
    m.merge(cartridge().translated((0.0030, 0.0040, 0.0405)))
    m.merge(cartridge(8).translated((-0.0040, -0.0035, 0.0405)))
    # floor plate
    (z, y), a = spine[-1], angs[-1]
    fwd = np.array([0.0, np.sin(a), -np.cos(a)])
    up = np.cross(fwd, (1.0, 0, 0))
    hd = ribs[-1][2]
    plate = sh.rounded_rect(-MAG_HW - 0.0018, -hd - 0.0030, MAG_HW + 0.0018, hd + 0.0016, (0.0030, 0.0030, 0.006, 0.006), 3)
    p = extrude_z(plate, (), -0.0005, 0.0068, round_bevel(0.0014, 2), "mag_base")
    m.merge(sh.place(p, origin=np.array([0.0, y, z]) - 0.0060 * up, x_axis=(1, 0, 0), y_axis=fwd, z_axis=up))
    tab = sh.box(-0.0045, -0.0020, -0.0010, 0.0045, 0.0020, 0.0016, 0.0005, "mag_base")
    m.merge(sh.place(tab, origin=np.array([0.0, y, z]) - 0.0068 * up + (hd - 0.0100) * fwd * -1, x_axis=(1, 0, 0),
                     y_axis=fwd, z_axis=up))
    return m


def _strip(pa, pb, normal, width, height):
    """A raised strip from pa to pb lying on a face with `normal` (a pressed rib): bevelled sides."""
    d = np.asarray(pb, float) - np.asarray(pa, float)
    length = np.linalg.norm(d)
    d = d / length
    n = np.asarray(normal, float)
    w = np.cross(n, d)
    sec = np.array([(-width / 2, 0.0), (width / 2, 0.0), (width / 2 - height * 0.8, height),
                    (-width / 2 + height * 0.8, height)])
    m = sh.prism_z(sec, 0.0, length, 0.0, "mag")
    return sh.place(m, origin=pa, x_axis=w, y_axis=n, z_axis=d)


def shell_mesh(scale=1.12):
    """Spent 7.62x51 mm brass case (material `brass`), along Z, mouth towards -Z, centred."""
    prof = [(0.0, 0.0256), (0.0059, 0.0256), (0.0060, 0.0244), (0.0050, 0.0240), (0.0050, 0.0226), (0.0060, 0.0222),
            (0.0058, -0.0138), (0.0044, -0.0174), (0.0044, -0.0256), (0.0039, -0.0256), (0.0039, -0.0210),
            (0.0, -0.0210)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 14, "brass")
