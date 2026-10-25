"""Revolver geometry (VIEWMODEL_SPEC section 49): an original six-shot double-action .357 revolver in the classic mould
of the full-underlug target revolvers. Design frame (metres) as the P9's: the bore along -Z (the muzzle forwards), y
up, x to the right; the bore axis is y = x = 0.

The frame (side profile extruded, the cylinder window and the trigger guard cut through it), a 4-inch barrel with a
full-length underlug and a ventilated rib, the ramped front sight with its red insert and the adjustable rear sight on
the top strap; the six-chamber fluted cylinder on its crane (swinging out to the left about an axis under the
cylinder), the ejector rod and star; the spur hammer, the trigger, the thumb latch, walnut target grips; the cartridges
in the chambers, the speed loader with six rounds and the spent case."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import extrude_z, round_bevel

# ---- the frame ---------------------------------------------------------------------------------------------------
FRAME_HW = 0.0085                  # frame half width (the cylinder is wider: it stands out of its window both sides)
STRAP_TOP = 0.0165                 # top of the top strap and the rib
CYL_FRONT, CYL_REAR = -0.0285, 0.0125      # cylinder faces (41 mm long)
CYL_Y = -0.0135                    # cylinder axis: the top chamber lines up with the bore
CYL_R = 0.0205                     # cylinder radius
CHAMBER_R = 0.0135                 # radius of the chamber circle
BORE_R = 0.0046                    # chamber / bore radius (.357)
FRAME_FRONT = -0.0340
MUZZLE_Z = -0.1360                 # the 4-inch barrel's crown
BARREL_R = 0.0090
LUG_BOTTOM = -0.0250               # bottom of the full underlug
RIB_HW = 0.0045
SIGHT_Y = 0.0215                   # top of the front blade = bottom of the rear notch: the sight line
FRONT_SIGHT_Z = (-0.1300, -0.1140)
REAR_SIGHT_Z = (0.0080, 0.0220)    # the rear sight's body on the top strap (its rear face at the larger z)
HAMMER_PIVOT = np.array([0.0, -0.0050, 0.0330])
TRIGGER_PIVOT = np.array([0.0, -0.0300, 0.0040])
LATCH_C = np.array([-FRAME_HW, -0.0080, 0.0215])     # thumb latch on the frame's left behind the cylinder
CRANE_AXIS = np.array([-0.0040, -0.0385, 0.0])       # the crane turns about the line through here along Z (in the
#                                                       frame's bottom bar, left of the middle)
EJECTOR_TRAVEL = 0.0220
GRIP_C0 = np.array([0.0, -0.0500, 0.0400])           # grip centre line at the top (gun pivot)
GRIP_ANGLE = 22.0
AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])   # down the grip
FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])  # towards the front strap
GRIP_LEN = 0.0920
CHARM_AT = np.array([0.0, -0.0500, 0.0400]) + (GRIP_LEN + 0.004) * AXIS     # lanyard ring under the butt
OPTIC_BASE = np.array([0.0, STRAP_TOP, -0.0150])     # optic over the cylinder, on the top strap / rib
EJECTION = np.array([0.0, 0.0, CYL_REAR - CYL_FRONT + 0.004])   # cylinder space: behind its rear face
CASE_LEN = 0.0330
BULLET_LEN = 0.0070


def grip_center(d):
    return GRIP_C0 + d * AXIS


def chamber_angles():
    """Chamber angles about the cylinder axis (0 = straight up: the chamber in line with the bore)."""
    return np.radians(np.arange(6) * 60.0)


def chamber_offset(a):
    """(x, y) of a chamber's centre from the cylinder axis."""
    return np.array([CHAMBER_R * np.sin(a), CHAMBER_R * np.cos(a)])


# ------------------------------------------------------------------------------------------------- frame
FRAME_PROFILE = [   # (z, y), the frame seen from the right: front of the frame, under the cylinder, the trigger guard,
    # the grip tang, the back strap, the hammer slot, the top strap
    (FRAME_FRONT, 0.0080), (FRAME_FRONT, -0.0360), (-0.0300, -0.0420), (-0.0120, -0.0440),
    (-0.0080, -0.0470), (-0.0085, -0.0560), (-0.0060, -0.0660), (0.0010, -0.0730), (0.0120, -0.0760),
    (0.0230, -0.0740), (0.0290, -0.0640), (0.0300, -0.0560), (0.0330, -0.0560), (0.0380, -0.0620),
    (0.0560, -0.0560), (0.0570, -0.0420), (0.0560, -0.0260), (0.0520, -0.0120), (0.0450, 0.0000),
    (0.0400, 0.0080), (0.0300, 0.0120), (0.0250, STRAP_TOP - 0.0015), (0.0220, STRAP_TOP),
    (-0.0300, STRAP_TOP), (-0.0330, 0.0140)]
WINDOW = sh.rounded_rect(CYL_FRONT - 0.0015, -0.0365, CYL_REAR + 0.0012, 0.0095, 0.0030, 3)          # (z, y)
GUARD_HOLE = [(-0.0035, -0.0470), (-0.0035, -0.0600), (0.0010, -0.0675), (0.0110, -0.0695), (0.0200, -0.0670),
              (0.0240, -0.0600), (0.0245, -0.0520), (0.0200, -0.0470)]
HAMMER_SLOT = sh.rounded_rect(0.0255, -0.0060, 0.0440, 0.0105, 0.0015, 2)


def frame_mesh():
    m = sh.extrude_x(FRAME_PROFILE, [WINDOW, GUARD_HOLE], FRAME_HW, round_bevel(0.0018, 1), "steel")
    # the hammer's slot down the middle of the frame's rear (a thin cut: the frame's walls either side of it)
    for sx in (-1, 1):
        wall = sh.extrude_x(sh.rounded_rect(0.0250, -0.0080, 0.0460, 0.0110, 0.0015, 2), (), 0.0028,
                            round_bevel(0.0008, 1), "steel", x_center=sx * 0.0058)
        m.merge(wall)
    # the recoil shield: a round boss behind the cylinder, wider than the frame
    shield = sh.revolve([(0.0, 0.0), (0.0185, 0.0), (0.0205, 0.0010), (0.0205, 0.0035), (0.0185, 0.0045), (0.0, 0.0045)],
                        40, "steel")
    m.merge(shield.translated((0.0, CYL_Y, CYL_REAR + 0.0010)))
    # frame screws (the side plate's) and the hammer / trigger pins either side
    for c in ((0.0360, -0.0300), (HAMMER_PIVOT[2], HAMMER_PIVOT[1])):
        for sx in (-1, 1):
            m.merge(sh.place(sh.revolve([(0.0, 0.0), (0.0024, 0.0), (0.0027, 0.0004), (0.0, 0.0006)], 8, "steel_dark"),
                             origin=(sx * FRAME_HW, c[1], c[0]), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                             z_axis=(sx * 1.0, 0, 0)))
    return m


# ------------------------------------------------------------------------------------------------ barrel
SHROUD_PROFILE = [   # (z, y): the barrel with its full underlug, from the frame's front to the crown
    (FRAME_FRONT + 0.0010, BARREL_R - 0.0005), (MUZZLE_Z + 0.0015, BARREL_R - 0.0005), (MUZZLE_Z, BARREL_R - 0.0020),
    (MUZZLE_Z, LUG_BOTTOM + 0.0060), (MUZZLE_Z + 0.0030, LUG_BOTTOM + 0.0010), (MUZZLE_Z + 0.0080, LUG_BOTTOM),
    (FRAME_FRONT - 0.0020, LUG_BOTTOM), (FRAME_FRONT + 0.0010, LUG_BOTTOM - 0.0020)]


def barrel_mesh():
    """The barrel and its underlug (one forging), rounded over the top; the ventilated rib; the bore at the crown."""
    m = sh.extrude_x(SHROUD_PROFILE, (), BARREL_R - 0.0005, round_bevel(0.0060, 3), "barrel")
    # ventilated rib: a top rib on posts, the slots between them open to the sides
    rib = [(FRAME_FRONT + 0.0010, 0.0060), (FRAME_FRONT + 0.0010, STRAP_TOP), (MUZZLE_Z + 0.0020, STRAP_TOP),
           (MUZZLE_Z + 0.0020, 0.0060)]
    slots = []
    for k in range(5):
        z0 = FRAME_FRONT - 0.0080 - k * 0.0182
        slots.append(sh.rounded_rect(z0 - 0.0140, BARREL_R - 0.0004, z0, STRAP_TOP - 0.0032, 0.0014, 1))
    m.merge(sh.extrude_x(rib, slots, RIB_HW, round_bevel(0.0010, 1), "barrel"))
    # the bore: a dark disc a hair inside the crown, and the crown's ring
    m.merge(sh.place(sh.revolve([(0.0, 0.0), (BORE_R, 0.0), (BORE_R, -0.0030), (0.0, -0.0030)], 20, "bore"),
                     origin=(0.0, 0.0, MUZZLE_Z + 0.0002), x_axis=(1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, -1)))
    # the ejector rod's channel opening at the underlug's front (the rod's tip shows when it is closed)
    m.merge(sh.place(sh.revolve([(0.0, 0.0), (0.0030, 0.0), (0.0030, -0.0015), (0.0, -0.0015)], 12, "bore"),
                     origin=(0.0, CYL_Y, MUZZLE_Z + 0.0082), x_axis=(1, 0, 0), y_axis=(0, 1, 0), z_axis=(0, 0, -1)))
    return m


def front_sight_mesh():
    """Ramped blade pinned into the rib with a red insert (in its own node's frame: origin at the blade's foot)."""
    z0, z1 = FRONT_SIGHT_Z
    blade = [(z0, 0.0), (z0, SIGHT_Y - STRAP_TOP), (z0 + 0.0030, SIGHT_Y - STRAP_TOP), (z1, 0.0)]
    m = sh.extrude_x(blade, (), 0.0016, round_bevel(0.0004, 1), "steel_dark")
    insert = [(z0 + 0.0008, SIGHT_Y - STRAP_TOP - 0.0045), (z0 + 0.0008, SIGHT_Y - STRAP_TOP - 0.0012),
              (z0 + 0.0040, SIGHT_Y - STRAP_TOP - 0.0012), (z0 + 0.0060, SIGHT_Y - STRAP_TOP - 0.0045)]
    m.merge(sh.extrude_x(insert, (), 0.0017, ((0.0, 0.0),), "insert"))
    return m.translated((0.0, STRAP_TOP, 0.0))


def rear_sight_mesh():
    """Adjustable rear sight: a body on the top strap, the blade with its square notch, the windage screw."""
    z0, z1 = REAR_SIGHT_Z
    body = sh.extrude_x([(z0, STRAP_TOP), (z0, STRAP_TOP + 0.0035), (z1, STRAP_TOP + 0.0045), (z1, STRAP_TOP)], (),
                        0.0045, round_bevel(0.0008, 1), "steel_dark")
    top = SIGHT_Y + 0.0035
    for sx in (-1, 1):       # the blade either side of the notch
        body.merge(sh.box(sx * 0.0016 if sx > 0 else -0.0060, SIGHT_Y - 0.0030, z1 - 0.0028,
                          0.0060 if sx > 0 else -0.0016, top, z1, 0.0003, "steel_dark"))
    body.merge(sh.box(-0.0060, SIGHT_Y - 0.0040, z1 - 0.0028, 0.0060, SIGHT_Y, z1, 0.0003, "steel_dark"))
    screw = sh.revolve([(0.0, 0.0), (0.0013, 0.0), (0.0013, 0.0016), (0.0, 0.0016)], 10, "steel_dark")
    body.merge(sh.place(screw, origin=(0.0045, STRAP_TOP + 0.0030, z1 - 0.0014), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                        z_axis=(1, 0, 0)))
    return body


# --------------------------------------------------------------------------------------------- cylinder
def _cyl_radius(theta, z):
    """The cylinder's outline at angle theta (0 = up) and z: round, with six flutes between the chambers from the front
    face back over two thirds of its length (each flute's ends rounded off)."""
    u = (z - CYL_FRONT) / (CYL_REAR - CYL_FRONT)
    depth = 0.0026 * np.clip((u - 0.06) / 0.08, 0, 1) * np.clip((0.70 - u) / 0.10, 0, 1)
    d = np.abs(((theta - np.radians(30.0)) + np.pi / 6) % (np.pi / 3) - np.pi / 6)      # from the nearest flute centre
    flute = np.clip(1 - (d / np.radians(11.0)) ** 2, 0, 1)
    return CYL_R - depth * np.sqrt(flute)


def cylinder_mesh():
    """In cylinder space (origin on its axis, at the front face's z = 0 ... the rear at +0.041): the fluted body, the
    chamber mouths in front (dark), the rear face round the chambers and the ratchet's well (the cartridges and the star
    fill them: rounds_mesh, ejector_mesh)."""
    n = 48
    th = np.linspace(0, 2 * np.pi, n, endpoint=False)
    zs = np.array([0.0, 0.0008, 0.0035, 0.006, 0.010, 0.016, 0.022, 0.026, 0.029, 0.032, 0.0402, 0.041])
    length = CYL_REAR - CYL_FRONT
    rings = []
    for z in zs:
        r = _cyl_radius(th, CYL_FRONT + z)
        if z < 0.0009 or z > length - 0.0009:
            r = r - 0.0007                              # the faces' chamfers
        rings.append(np.stack([r * np.sin(th), r * np.cos(th), np.full(n, z)], 1))
    m = sh.loft(rings, "steel", cap_start=False, cap_end=False, auto_orient=False)
    _orient_out(m)
    # the faces: front with the six chamber mouths, rear with the six chambers and the ratchet's recess
    holes = [np.stack([chamber_offset(a)[0] + BORE_R * 1.02 * np.sin(t), chamber_offset(a)[1] + BORE_R * 1.02 * np.cos(t)], 1)
             for a in chamber_angles() for t in [np.linspace(0, 2 * np.pi, 12, endpoint=False)]]
    for z, face_dir in ((0.0, -1.0), (length, 1.0)):
        loop = rings[0 if face_dir < 0 else -1]
        face = Mesh()
        sh.cap_polygon(face, loop, "steel", [np.stack([h[::-1, 0], h[::-1, 1], np.full(len(h), z)], 1) for h in holes])
        _face_towards(face, (0.0, 0.0, face_dir))
        m.merge(face)
        # the chambers: short dark tubes into the cylinder (the cartridges' heads fill the rear ones)
        for h in holes:
            tube = sh.loft([np.stack([h[:, 0], h[:, 1], np.full(len(h), z)], 1),
                            np.stack([h[:, 0], h[:, 1], np.full(len(h), z - face_dir * 0.006)], 1)],
                           "bore", cap_start=False, cap_end=True, auto_orient=False)
            _orient_in(tube, h.mean(0), face_dir)
            m.merge(tube)
    return m


def _orient_out(m):
    """Flip faces so they point away from the Z axis (a lofted shell round it)."""
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        p = v[list(f)]
        nrm = np.cross(p[1] - p[0], p[2] - p[0])
        c = p.mean(0)
        if np.dot(nrm, [c[0], c[1], 0.0]) < 0:
            m.f[i] = tuple(reversed(f))


def _orient_in(m, centre2d, face_dir):
    """A drilled chamber's faces point out of the steel: its walls towards its axis, its bottom out of the face it was
    drilled into (face_dir: that face's normal along Z)."""
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        p = v[list(f)]
        nrm = np.cross(p[1] - p[0], p[2] - p[0])
        if len(f) > 4:                                   # the bottom
            if nrm[2] * face_dir < 0:
                m.f[i] = tuple(reversed(f))
            continue
        c = p.mean(0)
        radial = np.array([c[0] - centre2d[0], c[1] - centre2d[1], 0.0])
        if np.dot(nrm, radial) > 0:
            m.f[i] = tuple(reversed(f))


def _face_towards(m, d):
    v = np.array(m.v)
    for i, f in enumerate(m.f):
        p = v[list(f)]
        if np.dot(np.cross(p[1] - p[0], p[2] - p[0]), d) < 0:
            m.f[i] = tuple(reversed(f))


def crane_mesh():
    """The crane (yoke), in crane space (origin on its turning axis, z at the cylinder's front face): its arm up to the
    cylinder's axis in front of the cylinder and its barrel along the axis (inside the frame's bottom bar when shut)."""
    to = np.array([-CRANE_AXIS[0], CYL_Y - CRANE_AXIS[1]])
    side = np.array([-to[1], to[0]]) / np.linalg.norm(to) * 0.0042
    arm = [(-side[0], -side[1]), (side[0], side[1]), (to[0] + side[0], to[1] + side[1]),
           (to[0] - side[0], to[1] - side[1])]
    m = extrude_z(sh.ccw(arm), (), -0.0042, -0.0006, round_bevel(0.0010, 1), "steel")
    m.merge(sh.revolve([(0.0, 0.0), (0.0030, 0.0), (0.0030, 0.0380), (0.0, 0.0380)], 12, "steel"))
    return m


def ejector_mesh():
    """Ejector rod (forwards under the barrel) with its knurled tip and the star at the cylinder's rear, in cylinder space."""
    length = CYL_REAR - CYL_FRONT
    rod = sh.revolve([(0.0, -0.0560), (0.0034, -0.0560), (0.0036, -0.0550), (0.0036, -0.0470), (0.0026, -0.0460),
                      (0.0026, 0.0), (0.0, 0.0)], 12, "steel")
    m = rod
    # the star: six arms between the chambers, fitting into the rear face's recess
    ang = np.linspace(0, 2 * np.pi, 49)[:-1]
    r = 0.0055 + 0.0040 * np.clip(np.cos(6 * (ang - np.radians(30.0))), 0, 1) ** 0.6
    star = np.stack([r * np.sin(ang), r * np.cos(ang)], 1)
    rings = [np.stack([star[:, 0], star[:, 1], np.full(len(star), z)], 1) for z in (length - 0.0016, length + 0.0004)]
    m.merge(sh.loft(rings, "steel"))
    return m


def rounds_mesh(spent=False):
    """The six cartridges in their chambers (cylinder space): the brass cases (rims and primers at the rear face) and,
    in front, the bullets inside (hidden in the chambers: they show only as the star pushes the rounds out)."""
    m = Mesh()
    length = CYL_REAR - CYL_FRONT
    for a in chamber_angles():
        cx, cy = chamber_offset(a)
        m.merge(_cartridge(spent).translated((cx, cy, length + 0.0006)))
    return m


def _cartridge(spent=False, segments=8):
    """One .357 cartridge, its head at z = 0 and its case running forwards (-Z)."""
    prof = [(0.0, 0.0), (0.0056, 0.0), (0.0056, -0.0013), (0.0047, -0.0016), (0.0047, -CASE_LEN)]
    prof += [(0.0042, -CASE_LEN)] if spent else [(0.0044, -CASE_LEN - 0.0010), (0.0034, -CASE_LEN - 0.0052),
                                                 (0.0, -CASE_LEN - BULLET_LEN)]
    if spent:
        prof += [(0.0, -CASE_LEN)]
    case = sh.revolve(prof, segments, "brass")
    if not spent:
        v = np.array(case.v)
        for i, f in enumerate(case.f):                  # the bullet's faces: its copper-lead jacket
            if v[list(f)][:, 2].mean() < -CASE_LEN:
                case.m[i] = "bullet"
    return case


def speedloader_mesh():
    """The speed loader's body and knob (mag space: origin at the rounds' heads' plane, the rounds point -Z)."""
    body = sh.revolve([(0.0, 0.0010), (0.0185, 0.0010), (0.0195, 0.0025), (0.0195, 0.0120), (0.0170, 0.0140),
                       (0.0060, 0.0140), (0.0060, 0.0230), (0.0085, 0.0240), (0.0085, 0.0300), (0.0060, 0.0310),
                       (0.0, 0.0310)], 20, "loader")
    return body


def loader_rounds_mesh():
    m = Mesh()
    for a in chamber_angles():
        cx, cy = chamber_offset(a)
        m.merge(_cartridge(False).translated((cx, cy, 0.0)))
    return m


def hammer_mesh():
    """Spur hammer, in its pivot's frame: its body down in the frame's slot, the spur up and back with its checkering."""
    prof = [(-0.0055, -0.0060), (-0.0070, 0.0060), (-0.0040, 0.0120), (0.0010, 0.0175), (0.0080, 0.0215),
            (0.0150, 0.0225), (0.0160, 0.0195), (0.0100, 0.0170), (0.0050, 0.0120), (0.0060, 0.0020),
            (0.0045, -0.0070), (-0.0010, -0.0095)]
    m = sh.extrude_x(prof, (), 0.0026, round_bevel(0.0008, 1), "steel_dark")
    # the spur's top widens into a checkered thumb pad
    pad = [(0.0035, 0.0185), (0.0085, 0.0222), (0.0152, 0.0230), (0.0156, 0.0212), (0.0095, 0.0198), (0.0045, 0.0165)]
    m.merge(sh.extrude_x(pad, (), 0.0042, round_bevel(0.0008, 1), "steel_dark"))
    return m


def trigger_mesh():
    """The trigger, in its pivot's frame: a smooth, wide double-action blade curving down and forwards."""
    prof = [(-0.0020, 0.0030), (0.0030, 0.0030), (0.0035, -0.0100), (0.0010, -0.0200), (-0.0040, -0.0270),
            (-0.0070, -0.0275), (-0.0060, -0.0245), (-0.0035, -0.0200), (-0.0028, -0.0110)]
    return sh.extrude_x(prof, (), 0.0032, round_bevel(0.0010, 1), "steel_dark")


def latch_mesh():
    """The cylinder release's thumb piece on the frame's left (its own frame: origin at its middle)."""
    prof = [(-0.0060, -0.0028), (0.0060, -0.0028), (0.0072, 0.0), (0.0060, 0.0028), (-0.0060, 0.0028),
            (-0.0072, 0.0)]
    m = sh.extrude_x(prof, (), 0.0010, round_bevel(0.0005, 1), "steel_dark")
    return m.translated((-0.0010, 0.0, 0.0))


# ------------------------------------------------------------------------------------------------- grip
GRIP_HALF_W = [(-0.0100, 0.0090), (0.0, 0.0140), (0.010, 0.0155), (0.030, 0.0165), (0.055, 0.0170), (0.075, 0.0165),
               (0.086, 0.0158), (GRIP_LEN, 0.0145)]
GRIP_DEPTH = [(-0.0100, 0.0180, 0.0200), (0.0, 0.0175, 0.0200), (0.020, 0.0165, 0.0195), (0.045, 0.0170, 0.0200),
              (0.070, 0.0180, 0.0205), (0.086, 0.0175, 0.0200), (GRIP_LEN, 0.0160, 0.0185)]    # (d, front, back)


def _interp(table, d, col=1):
    xs = [r[0] for r in table]
    return float(np.interp(d, xs, [r[col] for r in table]))


def grip_mesh():
    """Walnut target grips: superellipse sections lofted down the grip axis, fuller at the bottom, a finger swell in
    front; closed at the butt."""
    ds = np.linspace(-0.0100, GRIP_LEN, 14)
    t = np.linspace(0, 2 * np.pi, 28, endpoint=False) + np.pi / 28
    c, s = np.cos(t), np.sin(t)
    rings = []
    for k, d in enumerate(ds):
        hw = _interp(GRIP_HALF_W, d)
        sf, sb = _interp(GRIP_DEPTH, d, 1), _interp(GRIP_DEPTH, d, 2)
        n = np.where(s > 0, 2.4, 2.9)
        x = hw * np.sign(c) * np.abs(c) ** (2 / n)
        sv = np.where(s > 0, sf, sb) * np.sign(s) * np.abs(s) ** (2 / n)
        if k == len(ds) - 1:
            x, sv = x * 0.93, sv * 0.96                 # the butt's rounded edge
        centre = grip_center(d)
        rings.append(centre[None, :] + x[:, None] * np.array([1.0, 0, 0]) + sv[:, None] * FRONT[None, :])
    return sh.loft(rings, "grip")


def charm_ring_mesh():
    """Lanyard ring under the butt (where the charm hangs)."""
    ring = sh.revolve([(0.0030, -0.0008), (0.0048, -0.0008), (0.0048, 0.0008), (0.0030, 0.0008), (0.0030, -0.0008)],
                      14, "steel_dark")
    return sh.place(ring, origin=CHARM_AT + 0.0040 * AXIS, x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))


def shell_mesh():
    """fx_shell: an empty .357 case, its head at the origin and its mouth along -Z."""
    return _cartridge(True, 10)
