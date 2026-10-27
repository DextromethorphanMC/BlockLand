"""Desert Eagle geometry (VIEWMODEL_SPEC section 48): an original procedural .50 gas-operated pistol of the heavy
"Desert Eagle" family, built to the P9 contract (as the USP, section 26).

Design frame as the P9's (metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0. The names the P9 rig and clips
read (grip_center, AXIS, FRONT, SW, SY1, SZ0, CROWN_Z, SIGHT_Y, REAR_SIGHT_Z, the pivots, MAG_ORIGIN, LASER_ORIGIN) are
defined here, so the P9's hand anchors and clips fit this pistol (gen_deagle). The shapes are its own, after the
Mark XIX: a fixed 6-inch barrel of triangular section (short upright flanks on the frame, long flats sloping in to the
narrow top), a rail along its top and the front sight at the muzzle; a short heavy
slide behind it (the bolt carrier: rear serrations, the ejection port, the rear sight); a long steel frame with a
dust cover under the barrel, a big squared trigger guard and a deep grip for the long .50 cartridge (a single-stack
7-round magazine) with wrap-around rubber panels; a rounded spur hammer. 267 mm long, 32 mm across the slide.
Every builder returns a meshkit.Mesh."""
import numpy as np

import organic as og
import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, fit_well, orient_radial, pin, round_bevel

GRIP_ANGLE = 18.0
TAN18 = np.tan(np.radians(GRIP_ANGLE))
AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])     # grip axis, downwards
FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])  # grip "front"

# ---- key dimensions (names shared with p9_model: the P9 rig and clips read them) ------------------
SW = 0.0158                     # slide half width
SY0, SY1 = -0.0200, 0.0150      # slide bottom / top (the barrel's flat top is level with it)
SLIDE_Z0, SZ1 = -0.0230, 0.0980  # slide front / rear
CROWN_Z = -0.1720               # muzzle face (6-inch barrel)
SZ0 = CROWN_Z                   # P9 contract: the barrel mount sits just in front of this
BORE_R = 0.0064                 # .50 bore
BARREL_YB = -0.0140             # barrel's bottom (on the frame's dust cover)
RAIL_Y = 0.0186                 # top of the rail along the barrel
PORT = (-0.0140, 0.0250)        # ejection port z range (right side of the slide)
SIGHT_Y = 0.0232                # top of the rear sight / front blade
REAR_SIGHT_Z = 0.0950           # rear face of the rear sight
HAMMER_PIVOT = np.array([0.0, -0.0215, 0.1035])
TRIGGER_PIVOT = np.array([0.0, -0.0300, 0.0020])
STOP_PIVOT = np.array([-0.0142, -0.0262, 0.0330])
LASER_ORIGIN = np.array([0.0, -0.0336, -0.1150])       # under the dust cover's front
GRIP_C0 = np.array([0.0, -0.0330, 0.0590])             # grip centre line at the top
GRIP_LEN = 0.0900
MAG_S = 0.0045                                         # magazine centre offset (towards FRONT) at the well
MAG_LEN = 0.0960
MAG_HW, MAG_HD = 0.0082, 0.0222                        # single-stack .50 magazine half width / half depth
OPTIC_Y = SY1 - 0.0004                                 # optic plate on the slide in front of the rear sight
OPTIC_Z = 0.0560


def grip_center(d):
    return GRIP_C0 + d * AXIS


MAG_ORIGIN = grip_center(GRIP_LEN) + MAG_S * FRONT


def _loft_z(pairs, material):
    return sh.loft([np.stack([*np.asarray(s).T, np.full(len(s), z)], 1) for s, z in pairs], material)


# ------------------------------------------------------------------------------------- slide
def slide_section(kind="normal", depth=0.0):
    """CCW (x, y) slide cross-section, 16 vertices: tall flat flanks, a step and broad bevels up to a narrow flat top.
    kind: normal | groove (rear serration cut) | port (ejection port: the right flank open above the extractor)."""
    W, yb, yt = SW, SY0, SY1
    p = [(-W + 0.0007, yb), (W - 0.0007, yb), (W, yb + 0.0007), (W, 0.0020), (W - 0.0006, 0.0034),
         (W - 0.0012, 0.0050), (0.0090, yt - 0.0004), (0.0084, yt), (-0.0084, yt), (-0.0090, yt - 0.0004),
         (-W + 0.0012, 0.0050), (-W + 0.0006, 0.0034), (-W, 0.0020), (-W, yb + 0.0007)]
    p = [list(q) for q in p]
    if kind == "groove":
        for i in (2, 3):
            p[i][0] -= depth
        for i in (12, 13):
            p[i][0] += depth
        p[4][0] -= depth * 0.6
        p[11][0] += depth * 0.6
    elif kind == "port":
        for i, (x, y) in zip((3, 4, 5, 6), ((W, -0.0035), (0.0060, -0.0035), (0.0040, 0.0060), (0.0040, yt - 0.0004))):
            p[i] = [x, y]
        p[7] = [0.0036, yt]
    return np.array(p)


def slide_mesh():
    """The short heavy slide (bolt carrier housing) behind the barrel: a bevelled block, the ejection port on the right,
    nine rear serrations, the extractor, a rear plate over the firing pin and the two safety levers at the rear."""
    secs = []

    def add(z, kind="normal", inset=0.0, depth=0.0):
        s = slide_section(kind, depth)
        if inset:
            s = sh.offset_loop(s, inset)
        secs.append(np.stack([s[:, 0], s[:, 1], np.full(len(s), z)], 1))

    add(SLIDE_Z0, inset=0.0012)
    add(SLIDE_Z0 + 0.0010)
    add(PORT[0]), add(PORT[0] + 0.0001, "port"), add(PORT[1] - 0.0001, "port"), add(PORT[1])
    for k in range(9):                                     # rear serrations: narrow, deep, slanted forward a little
        z0 = 0.0520 + k * 0.0046
        add(z0), add(z0 + 0.0004, "groove", depth=0.0011), add(z0 + 0.0018, "groove", depth=0.0011), add(z0 + 0.0022)
    add(SZ1 - 0.0012)
    add(SZ1, inset=0.0012)
    m = sh.loft(secs, "slide")
    # extractor behind the port and the hammer cut-out's surround at the rear
    m.merge(sh.box(SW - 0.0003, -0.0030, 0.0240, SW + 0.0006, 0.0020, 0.0380, 0.0003, "slide"))
    m.merge(sh.box(-0.0070, -0.0170, SZ1 - 0.0008, 0.0070, 0.0080, SZ1 + 0.0008, 0.0005, "steel_dark"))
    # ambidextrous safety levers on the slide's rear flanks: a pivot drum and a paddle sweeping back and up
    for s in (-1, 1):
        x0, x1 = sorted((s * (SW - 0.0002), s * (SW + 0.0030)))
        lever = [(0.0790, -0.0060), (0.0890, -0.0070), (0.0960, -0.0020), (0.0960, 0.0030), (0.0905, 0.0030),
                 (0.0840, -0.0015), (0.0790, -0.0010)]
        m.merge(sh.extrude_x(lever, (), 0.5 * (x1 - x0), chamfer_bevel(0.0005), "steel_dark",
                             x_center=0.5 * (x0 + x1)))
        m.merge(pin((s * (SW + 0.0012), -0.0040, 0.0820), 0.0034, 0.0016, "steel_dark", seg=12))
    return m


# ------------------------------------------------------------------------------------ barrel
def barrel_section(z):
    """CCW (x, y) section of the fixed barrel: a wide foot on the dust cover, flanks tapering up to a narrow flat top
    (the polygonal "Desert Eagle" barrel), slimmer towards the muzzle."""
    u = np.clip((SLIDE_Z0 - z) / (SLIDE_Z0 - CROWN_Z), 0, 1)
    wb = 0.0146 - 0.0008 * u                     # half width at the foot (as wide as the slide)
    wt = 0.0064                                  # the narrow top under the rail
    yb, yt = BARREL_YB, SY1
    # the Mark XIX's triangular barrel: short upright flanks on the frame, then long flats sloping in to the top
    return np.array([(-wb + 0.0006, yb), (wb - 0.0006, yb), (wb, yb + 0.0006), (wb, -0.0080),
                     (wb - 0.0010, -0.0062), (wt + 0.0008, yt - 0.0008), (wt, yt), (-wt, yt),
                     (-wt - 0.0008, yt - 0.0008), (-wb + 0.0010, -0.0062), (-wb, -0.0080), (-wb, yb + 0.0006)])


def barrel_mesh():
    """The fixed barrel: the polygonal body from the slide's front to the muzzle (with the bore at its face), the rail
    with cross slots along its top, the front sight blade at the muzzle, and the chamber hood inside the slide (seen
    through the ejection port)."""
    zs = [SLIDE_Z0 - 0.0002, SLIDE_Z0 - 0.0400, SLIDE_Z0 - 0.0800, CROWN_Z + 0.0060, CROWN_Z + 0.0010, CROWN_Z]
    secs = []
    for i, z in enumerate(zs):
        s = barrel_section(z)
        if i == len(zs) - 1:
            s = sh.offset_loop(s, 0.0010)
        secs.append((s, z))
    secs = secs[::-1]                              # muzzle first
    body = _loft_z(secs, "barrel")
    m = Mesh()
    # open the muzzle: replace the front cap with the face around the bore and a short bore tube
    face_i = None
    for i, f in enumerate(body.f):
        pv = np.array(body.v)[list(f)]
        if len(f) > 4 and np.allclose(pv[:, 2], CROWN_Z):
            face_i = i
    loop = np.array(body.v)[list(body.f[face_i])]
    body.f.pop(face_i), body.m.pop(face_i), body.uv.pop(face_i), body.soft.pop(face_i)
    hole = np.stack([*sh.circle(0, 0, BORE_R, 20).T, np.full(20, CROWN_Z)], 1)
    sh.cap_polygon(body, loop, "barrel", [hole])
    m.merge(body)
    bore = sh.revolve([(BORE_R, CROWN_Z), (BORE_R, CROWN_Z + 0.0300), (0.0, CROWN_Z + 0.0300)], 20, "steel_dark",
                      outward=False, precision=True)
    m.merge(bore)
    # rail along the top: a raised strip with cross slots (a low Picatinny section)
    rail = np.array([(-0.0062, SY1 - 0.0002), (0.0062, SY1 - 0.0002), (0.0062, RAIL_Y - 0.0008), (0.0054, RAIL_Y),
                     (-0.0054, RAIL_Y), (-0.0062, RAIL_Y - 0.0008)])
    slot = rail.copy()
    slot[:, 1] = np.minimum(slot[:, 1], RAIL_Y - 0.0018)
    pairs = [(sh.offset_loop(rail, 0.0005), CROWN_Z + 0.0160), (rail, CROWN_Z + 0.0166)]
    z = CROWN_Z + 0.0230
    while z < SLIDE_Z0 - 0.0090:
        pairs += [(rail, z - 0.0016), (slot, z - 0.0015), (slot, z + 0.0015), (rail, z + 0.0016)]
        z += 0.0100
    pairs += [(rail, SLIDE_Z0 - 0.0020), (sh.offset_loop(rail, 0.0005), SLIDE_Z0 - 0.0012)]
    m.merge(_loft_z(pairs, "barrel"))
    # front sight: a ramped blade on the rail at the muzzle with a dot facing the eye
    fz0, fz1 = CROWN_Z + 0.0040, CROWN_Z + 0.0150
    blade = [(fz0, RAIL_Y - 0.0004), (fz1, RAIL_Y - 0.0004), (fz1, SIGHT_Y), (fz1 - 0.0030, SIGHT_Y),
             (fz0 + 0.0020, RAIL_Y + 0.0012)]
    m.merge(sh.extrude_x(blade, (), 0.0016, chamfer_bevel(0.0003), "steel_dark"))
    m.merge(sh.disc((0, SIGHT_Y - 0.0018, fz1 + 0.00006), (0, 0, 1), 0.0010, 12, "sight_dots"))
    # chamber hood inside the slide (the top shows through the port)
    m.merge(sh.box(-0.0105, -0.0110, SLIDE_Z0 + 0.0010, 0.0105, 0.0105, 0.0290, 0.0010, "barrel"))
    m.merge(sh.disc((0, 0, 0.02905), (0, 0, 1), 0.0070, 14, "steel_dark"))
    return m


# ------------------------------------------------------------------------------------- frame
# side outline (z, y): the dust cover under the barrel, the step under the slide, the tang behind it, the grip's top
FRAME_PROFILE = [(-0.1530, BARREL_YB - 0.0002), (SLIDE_Z0 + 0.0010, BARREL_YB - 0.0002), (SLIDE_Z0 + 0.0030, SY0),
                 (0.0920, SY0), (0.0975, -0.0222), (0.1010, -0.0262), (0.0995, -0.0312), (0.0940, -0.0350),
                 (0.0880, -0.0392), (0.0450, -0.0392), (0.0430, -0.0340), (-0.0250, -0.0340), (-0.1460, -0.0340),
                 (-0.1515, -0.0320), (-0.1540, -0.0250)]
# big squared trigger guard with a hooked front
GUARD_PROFILE = [(-0.0320, -0.0335), (-0.0330, -0.0500), (-0.0365, -0.0560), (-0.0352, -0.0605),
                 (-0.0310, -0.0632), (-0.0230, -0.0645), (0.0440, -0.0645), (0.0440, -0.0585),
                 (-0.0180, -0.0585), (-0.0228, -0.0568), (-0.0256, -0.0530), (-0.0262, -0.0470),
                 (-0.0262, -0.0335)]


def strap_front_z(y):
    return 0.0315 + (-0.034 - y) * TAN18


def strap_back_z(y):
    return 0.0870 + (-0.040 - y) * TAN18


GRIP_WIDTH = [(-0.0035, 0.0250), (0.0, 0.0268), (0.005, 0.0310), (0.012, 0.0334), (0.026, 0.0344),
              (0.046, 0.0344), (0.066, 0.0342), (0.0800, 0.0350), (0.0860, 0.0362), (GRIP_LEN, 0.0366)]


def _ring_frame(d):
    u = np.clip(d / GRIP_LEN, 0, 1)
    sm = np.clip(u / 0.45, 0, 1)
    phi = np.radians(GRIP_ANGLE) * sm * sm * (3 - 2 * sm)
    c = grip_center(d)
    s_dir = np.array([0.0, -np.sin(phi), -np.cos(phi)])
    s_f = (c[2] - strap_front_z(c[1])) / (np.cos(phi) + TAN18 * np.sin(phi))
    s_b = (strap_back_z(c[1]) - c[2]) / np.cos(phi)
    for _ in range(4):
        s_b = (strap_back_z(c[1] + s_b * np.sin(phi)) - c[2]) / np.cos(phi)
    return c, s_dir, s_f, s_b


def _shape(half_w, s_f, s_b, count=22, n_front=2.4, n_back=3.0):
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    n = np.where(s > 0, n_front, n_back)
    x = half_w * np.sign(c) * np.abs(c) ** (2 / n)
    sv = np.where(s > 0, s_f, s_b) * np.sign(s) * np.abs(s) ** (2 / n)
    return x, sv


def mag_outline(clear=0.0, count_corner=4):
    """Magazine cross-section in mag-local (x, front) coordinates, CCW: a narrow, deep single stack."""
    return sh.rounded_rect(-MAG_HW - clear, -MAG_HD - clear, MAG_HW + clear, MAG_HD + clear,
                           (0.0030 + clear, 0.0030 + clear, 0.0060 + clear, 0.0060 + clear), count_corner)


# the rubber wrap (sides and front strap, distance down the grip) and its finger grooves (section 55)
PANEL_D = (0.0140, 0.0840)
GROOVES = (0.0160, 0.0760, 3, 0.0018)


def grip_mesh():
    """The deep grip: a steel frame ring with a wrap-around rubber sleeve standing proud of it (finger grooves on the
    front strap), the well."""
    ds = og.refine([d for d, _ in GRIP_WIDTH], PANEL_D + GROOVES[:2], 0.0035)
    gd, gw = zip(*GRIP_WIDTH)
    frames, sections = [], []
    for d in ds:
        c, s_dir, s_f, s_b = _ring_frame(d)
        frames.append((c, s_dir))
        sec = og.ring(0.5 * np.interp(d, gd, gw), s_f, s_b, 48, 2.2, 2.7, flat_sides=0.3)
        # three finger grooves across the front strap (the rubber wrap), deepest on the strap's middle
        groove = og.finger_grooves(d, *GROOVES)
        sec[:, 1] = np.where(sec[:, 1] > 0, np.maximum(sec[:, 1] - groove * (sec[:, 1] / max(s_f, 1e-6)) ** 4, 0.0),
                             sec[:, 1])
        sections.append(sec)
    # the wrap is a separate sleeve: its edge stands 0.5 mm proud of the steel back strap (a negative recess)
    mask = og.panel_mask(sections, ds, *PANEL_D, side=0.45, front=True)
    sections = og.recess(sections, mask, -0.0005)
    rings = [c[None, :] + sec[:, 0:1] * np.array([1.0, 0, 0]) + sec[:, 1:2] * s_dir[None, :]
             for (c, s_dir), sec in zip(frames, sections)]
    outer = og.skin(rings, "polymer", mask)
    orient_radial(outer, GRIP_C0, AXIS, +1)
    top = outer.f[-1]
    pv = np.array(outer.v)[list(top)]
    if np.cross(pv[1] - pv[0], pv[2] - pv[0])[1] < 0:
        outer.f[-1] = tuple(reversed(top))
    well = fit_well(mag_outline(0.0008), ds, sections, GRIP_LEN, MAG_S, 0.036)
    to3 = lambda pts, d: (grip_center(d) + MAG_S * FRONT)[None, :] + pts[:, 0:1] * np.array([1.0, 0, 0]) \
        + pts[:, 1:2] * FRONT[None, :]
    well_bottom = to3(well, GRIP_LEN)
    bottom = Mesh()
    sh.cap_polygon(bottom, rings[-1], "polymer", [well_bottom])
    pv = np.array(bottom.v)
    f0 = bottom.f[0]
    if np.dot(np.cross(pv[f0[1]] - pv[f0[0]], pv[f0[2]] - pv[f0[0]]), AXIS) < 0:
        bottom.f = [tuple(reversed(f)) for f in bottom.f]
    walls = sh.loft([well_bottom, to3(well, GRIP_LEN - 0.036)], "polymer", cap_start=False, cap_end=True,
                    auto_orient=False)
    orient_radial(walls, MAG_ORIGIN, AXIS, -1)
    cap = walls.f[-1]
    pv = np.array(walls.v)[list(cap)]
    if np.dot(np.cross(pv[1] - pv[0], pv[2] - pv[0]), AXIS) < 0:
        walls.f[-1] = tuple(reversed(cap))
    return outer.merge(bottom).merge(walls)


def mag_release_mesh():
    """Round magazine release button on the left behind the trigger guard."""
    m = sh.revolve([(0.0, 0.0), (0.0042, 0.0), (0.0046, 0.0008), (0.0046, 0.0026), (0.0040, 0.0032), (0.0, 0.0032)],
                   14, "steel_dark", precision=True)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(-1, 0, 0))
    return m.translated((-0.0134, -0.0380, 0.0400))


def frame_mesh():
    m = sh.extrude_x(FRAME_PROFILE, (), 0.0136, round_bevel(0.0016, 1), "polymer")
    m.merge(sh.extrude_x(GUARD_PROFILE, (), 0.0068, round_bevel(0.0026, 1), "polymer"))
    for k in range(5):                                   # serrations on the front of the guard
        y = -0.0520 - k * 0.0018
        m.merge(sh.box(-0.0044, y, -0.0372 - 0.0003 * k, 0.0044, y + 0.0008, -0.0352 - 0.0003 * k, 0.0002,
                       "polymer"))
    m.merge(grip_mesh())
    m.merge(mag_release_mesh())
    # the barrel's locking block / takedown lever on the left above the trigger, and the frame pins
    m.merge(sh.box(-0.0146, -0.0300, -0.0200, -0.0132, -0.0230, 0.0060, 0.0004, "steel_dark"))
    m.merge(sh.box(-0.0156, -0.0285, -0.0215, -0.0140, -0.0244, -0.0120, 0.0004, "steel_dark"))
    for y, z in ((-0.0300, 0.0020), (-0.0262, 0.0330), (-0.0270, 0.0800), (-0.0230, -0.1250)):
        m.merge(pin((0, y, z), 0.0013, 0.0139, "steel_dark", seg=8))
    return m


# ---------------------------------------------------------------------------- moving parts
def trigger_mesh():
    """Wide curved trigger blade, hinged at TRIGGER_PIVOT."""
    d = np.array([TRIGGER_PIVOT[2] - 0.0040, TRIGGER_PIVOT[1] + 0.0250])        # USP profile -> this pivot
    blade = [(0.0024, -0.0255), (0.0030, -0.0310), (0.0046, -0.0360), (0.0072, -0.0408), (0.0104, -0.0446),
             (0.0132, -0.0468), (0.0150, -0.0466), (0.0154, -0.0450), (0.0134, -0.0420), (0.0108, -0.0382),
             (0.0092, -0.0340), (0.0086, -0.0295), (0.0090, -0.0255)]
    blade = [(z + d[0], y + d[1]) for z, y in blade]
    m = sh.extrude_x(blade, (), 0.0040, round_bevel(0.0014, 1), "steel_dark")
    m.merge(sh.box(-0.0022, TRIGGER_PIVOT[1] - 0.0012, TRIGGER_PIVOT[2] - 0.0050, 0.0022, TRIGGER_PIVOT[1] + 0.0014,
                   TRIGGER_PIVOT[2] + 0.0070, 0.0004, "steel_dark"))
    return m


def hammer_mesh():
    """Rounded spur hammer (checkered top), upright at rotation 0, pivot at HAMMER_PIVOT."""
    pz, py = HAMMER_PIVOT[2], HAMMER_PIVOT[1]
    arc = [(pz + 0.0045 + 0.0070 * np.cos(a), py + 0.0225 + 0.0070 * np.sin(a))
           for a in np.radians(np.linspace(-30, 200, 12))]
    prof = [(pz - 0.0060, py - 0.0010), (pz + 0.0060, py - 0.0010), (pz + 0.0110, py + 0.0170)] + arc + \
           [(pz - 0.0040, py + 0.0150)]
    hole = sh.circle(pz + 0.0042, py + 0.0218, 0.0025, 8)[::-1]
    m = sh.extrude_x(prof, [hole], 0.0040, round_bevel(0.0012, 1), "steel_dark")
    for k in range(4):                                   # checkering across the spur
        a = np.radians(55 + 18 * k)
        z, y = pz + 0.0045 + 0.0070 * np.cos(a), py + 0.0225 + 0.0070 * np.sin(a)
        m.merge(sh.box(-0.0034, y - 0.0003, z - 0.0004, 0.0034, y + 0.0005, z + 0.0004, 0.0002, "steel_dark"))
    return m


def slide_stop_mesh():
    """Slide stop on the left of the frame: a long arm with a serrated thumb tab (pivot at STOP_PIVOT)."""
    sz, sy = STOP_PIVOT[2], STOP_PIVOT[1]
    prof = [(sz + 0.0050, sy - 0.0022), (sz + 0.0050, sy + 0.0026), (sz - 0.0150, sy + 0.0044),
            (sz - 0.0230, sy + 0.0046), (sz - 0.0246, sy + 0.0026), (sz - 0.0236, sy - 0.0006),
            (sz - 0.0150, sy - 0.0010), (sz - 0.0010, sy - 0.0026)]
    m = sh.extrude_x(prof, (), 0.0008, chamfer_bevel(0.0003), "steel_dark", x_center=STOP_PIVOT[0])
    m.merge(sh.box(STOP_PIVOT[0] - 0.0022, sy + 0.0004, sz - 0.0236, STOP_PIVOT[0] - 0.0004, sy + 0.0040,
                   sz - 0.0150, 0.0004, "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ sights
def rear_sight_mesh():
    """Square-notch rear sight block on the slide's rear with two dots (slide space = design frame)."""
    base = SY1 - 0.0010
    rear = np.array([(-0.0098, base), (0.0098, base), (0.0098, SIGHT_Y - 0.0016), (0.0086, SIGHT_Y),
                     (0.0020, SIGHT_Y), (0.0020, SIGHT_Y - 0.0042), (-0.0020, SIGHT_Y - 0.0042), (-0.0020, SIGHT_Y),
                     (-0.0086, SIGHT_Y), (-0.0098, SIGHT_Y - 0.0016)])
    low = rear.copy()
    low[:, 1] = np.minimum(low[:, 1], SIGHT_Y - 0.0042)
    m = _loft_z([(low, REAR_SIGHT_Z - 0.0110), (rear, REAR_SIGHT_Z - 0.0080), (rear, REAR_SIGHT_Z - 0.0005),
                 (sh.offset_loop(rear, 0.0005), REAR_SIGHT_Z)], "steel_dark")
    for x in (-0.0056, 0.0056):
        m.merge(sh.disc((x, SIGHT_Y - 0.0021, REAR_SIGHT_Z + 0.00006), (0, 0, 1), 0.0011, 12, "sight_dots"))
    return m


# ----------------------------------------------------------------------------- magazine
def _mag_place(mesh):
    """Builder space (x, y=front, z=up the mag) -> mag-local space (+Y up the mag, -Z front)."""
    return sh.place(mesh, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))


def cartridge(material="brass"):
    """.50 rimless round along -Z (bullet forward), case head at z = 0: a long straight case, a flat-nosed bullet."""
    r = 0.0070
    prof = [(0.0, 0.0), (r, 0.0), (r, -0.0010), (r * 0.92, -0.0016), (r * 0.94, -0.0326), (r * 0.90, -0.0336),
            (r * 0.84, -0.0360), (r * 0.62, -0.0386), (r * 0.42, -0.0398), (0.0, -0.0400)]
    return sh.revolve(prof, 12, material, precision=True)


def mag_mesh():
    """Single-stack steel magazine, a base plate with a lip, the top round showing (bullet forward)."""
    m = _mag_place(sh.prism_z(mag_outline(), 0.0, MAG_LEN, 0.0006, "mag"))
    plate = sh.offset_loop(mag_outline(), -0.0018)
    plate[:, 1] += np.where(plate[:, 1] > 0.0, 0.0018, 0.0)          # finger lip at the front
    m.merge(_mag_place(sh.prism_z(plate, -0.0070, 0.0002, 0.0014, "mag_base")))
    m.merge(cartridge().translated((0.0, MAG_LEN + 0.0060, 0.0202)))
    for y in (0.030, 0.046, 0.062, 0.078):               # witness holes on the left of the body
        m.merge(sh.disc((-MAG_HW - 0.00008, y, 0.004), (-1, 0, 0), 0.0012, 8, "brass"))
    return m


def shell_mesh(scale=1.15):
    """Spent .50 case (long, straight, rebated rim), enlarged a little like the P9's so it reads on screen."""
    prof = [(0.0, 0.0060), (0.0060, 0.0060), (0.0060, -0.0250), (0.0066, -0.0250), (0.0069, 0.0080),
            (0.0062, 0.0088), (0.0062, 0.0100), (0.0068, 0.0104), (0.0068, 0.0114), (0.0, 0.0114)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 20, "brass", precision=True)
