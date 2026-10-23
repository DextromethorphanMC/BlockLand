"""USP pistol geometry (VIEWMODEL_SPEC section 26): an original procedural .45 polymer pistol built to the P9 contract.

Design frame as the P9's (metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0. The grip, magazine well,
trigger, hammer and slide-stop pivots sit where the P9 rig expects them (same names as p9_model), so the P9's hand
anchors and clips fit this pistol too (see gen_usp); the shapes are the USP's own: a long squared slide with broad top
bevels and a rounded nose, a deep dust cover with a slotted accessory rail, a big square trigger guard with a hook,
an exposed ring hammer and a control lever at the rear of the frame. Every builder returns a meshkit.Mesh."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, fit_well, orient_radial, pin, round_bevel

GRIP_ANGLE = 18.0
TAN18 = np.tan(np.radians(GRIP_ANGLE))
AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])     # grip axis, downwards
FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])  # grip "front"

# ---- key dimensions (names shared with p9_model: the P9 rig and clips read them) ------------------
SW = 0.0146                     # slide half width
SY0, SY1 = -0.0175, 0.0142      # slide bottom / top
SZ0, SZ1 = -0.1000, 0.0895      # slide front / rear
NOSE = 0.0130                   # length of the slide's rounded lower nose
NOSE_Y = -0.0150                # slide bottom at its front face
PORT = (-0.0120, 0.0290)        # ejection port z range
PORT_X, PORT_Y = -0.0062, -0.0030
CROWN_Z = -0.0985
SIGHT_Y = 0.0200                # top of the rear sight blades / front post
REAR_SIGHT_Z = 0.0870           # rear face of the rear sight
HAMMER_PIVOT = np.array([0.0, -0.0185, 0.0915])
TRIGGER_PIVOT = np.array([0.0, -0.0250, 0.0040])
STOP_PIVOT = np.array([-0.0127, -0.0222, 0.0370])
LASER_ORIGIN = np.array([0.0, -0.0386, -0.0640])       # bottom face of the accessory rail
GRIP_C0 = np.array([0.0, -0.0270, 0.0545])             # grip centre line at the top
GRIP_LEN = 0.0800
MAG_S = 0.00625                                        # magazine centre offset (towards FRONT) at the well
MAG_LEN = 0.0860
MAG_HW, MAG_HD = 0.0120, 0.0170                        # .45 double-stack magazine half width / half depth
OPTIC_Y = SY1 - 0.0004                                 # optic cut in front of the rear sight (slide space)
OPTIC_Z = 0.0540
RELEASE = np.array([-0.0166, -0.0337, 0.0372])         # thumb point of the magazine release (left side)


def grip_center(d):
    return GRIP_C0 + d * AXIS


MAG_ORIGIN = grip_center(GRIP_LEN) + MAG_S * FRONT


# ------------------------------------------------------------------------------------- slide
def slide_section(kind="normal", depth=0.0, yb=SY0):
    """CCW (x, y) slide cross-section, 18 vertices: flat flanks, broad bevels to a narrow flat top.
    kind: normal | groove (rear serration cut) | port (ejection port: the top right corner open)."""
    W, yt = SW, SY1
    p = [(-W + 0.0006, yb), (W - 0.0006, yb), (W, yb + 0.0006), (W, yb + 0.0026), (W, PORT_Y), (W, 0.0066),
         (W - 0.0010, 0.0086), (W - 0.0046, yt - 0.0004), (W - 0.0056, yt), (PORT_X, yt), (PORT_X, yt),
         (-W + 0.0056, yt), (-W + 0.0046, yt - 0.0004), (-W + 0.0010, 0.0086), (-W, 0.0066), (-W, PORT_Y),
         (-W, yb + 0.0026), (-W, yb + 0.0006)]
    p = [list(q) for q in p]
    if kind == "groove":
        for i in (3, 4, 5):
            p[i][0] -= depth
        for i in (14, 15, 16):
            p[i][0] += depth
        p[6][0] -= depth * 0.6
        p[13][0] += depth * 0.6
    elif kind == "port":
        for i, x in zip((5, 6, 7, 8, 9), (0.0110, 0.0068, 0.0026, -0.0018, PORT_X)):
            p[i] = [x, PORT_Y]
    return np.array(p)


def _nose_y(z):
    """Slide bottom along the rounded nose (a quarter ellipse up to NOSE_Y at the front face)."""
    u = np.clip((SZ0 + NOSE - z) / NOSE, 0.0, 1.0)
    return SY0 + (NOSE_Y - SY0) * (1.0 - np.sqrt(max(0.0, 1.0 - u * u)))


def slide_mesh():
    secs = []

    def add(z, kind="normal", inset=0.0, depth=0.0):
        s = slide_section(kind, depth, _nose_y(z))
        if inset:
            s = sh.offset_loop(s, inset)
        secs.append(np.stack([s[:, 0], s[:, 1], np.full(len(s), z)], 1))

    add(SZ0, inset=0.0016)
    add(SZ0 + 0.0008, inset=0.0004)
    for k in range(1, 4):                                  # the rounded nose
        add(SZ0 + NOSE * k / 3)
    add(PORT[0]), add(PORT[0] + 0.0001, "port"), add(PORT[1] - 0.0001, "port"), add(PORT[1])
    for k in range(5):                                     # rear serrations: wide, flat-bottomed cuts
        z0 = 0.0580 + k * 0.0054
        add(z0), add(z0 + 0.0004, "groove", depth=0.0009), add(z0 + 0.0020, "groove", depth=0.0009), add(z0 + 0.0024)
    add(SZ1 - 0.0012)
    add(SZ1, inset=0.0012)
    m = sh.loft(secs, "slide", cap_start=False, cap_end=True, auto_orient=False)
    ring = secs[0]
    hole_b = np.stack(list(sh.circle(0, 0, 0.0078, 18).T) + [np.full(18, SZ0)], 1)
    hole_g = np.stack(list(sh.circle(0, -0.0116, 0.0026, 8).T) + [np.full(8, SZ0)], 1)
    sh.cap_polygon(m, ring[::-1], "slide", [hole_b, hole_g])
    for r_out, r_in, cy, seg in ((0.0078, 0.0064, 0.0, 18), (0.0026, 0.0019, -0.0116, 8)):
        tube = sh.revolve([(r_out, SZ0), (r_out, SZ0 + 0.0040), (r_in, SZ0 + 0.0040)], seg, "slide", outward=False)
        m.merge(tube.translated((0, cy, 0)))
    # extractor (right, behind the port), loaded-chamber flat and the rear plate over the striker channel
    m.merge(sh.box(SW - 0.0003, 0.0006, 0.0300, SW + 0.0007, 0.0046, 0.0440, 0.0003, "slide"))
    m.merge(sh.box(-0.0064, -0.0140, SZ1 - 0.0010, 0.0064, 0.0090, SZ1 + 0.0006, 0.0004, "slide"))
    return m


# ------------------------------------------------------------------------------------ barrel
def barrel_mesh():
    prof = [(0.0, -0.0600), (0.0058, -0.0600), (0.0058, CROWN_Z + 0.0008), (0.0064, CROWN_Z), (0.0072, CROWN_Z),
            (0.0076, CROWN_Z + 0.0005), (0.0076, -0.0175), (0.0058, -0.0165)]
    m = sh.revolve(prof, 18, "barrel", outward=True)
    m.set_material(lambda c, n: np.hypot(c[0], c[1]) < 0.0060, "steel_dark")        # dark bore
    m.merge(sh.box(-0.0094, -0.0086, -0.0165, 0.0094, 0.0110, 0.0290, 0.0009, "barrel"))  # hood
    m.merge(sh.disc((0, 0, 0.02905), (0, 0, 1), 0.0061, 12, "steel_dark"))          # chamber mouth
    rod = sh.revolve([(0.0, CROWN_Z + 0.0012), (0.0019, CROWN_Z + 0.0012), (0.0023, CROWN_Z + 0.0016),
                      (0.0023, -0.0320), (0.0, -0.0320)], 8, "steel_dark")
    m.merge(rod.translated((0, -0.0116, 0)))
    for k in range(3):                                   # a few spring coils behind the guide hole
        z = CROWN_Z + 0.0055 + k * 0.0034
        m.merge(sh.revolve([(0.0024, z), (0.0031, z + 0.0008), (0.0024, z + 0.0016)], 8, "steel_dark")
                .translated((0, -0.0116, 0)))
    return m


# ------------------------------------------------------------------------------------- frame
FRAME_PROFILE = [(-0.0952, -0.0178), (0.0905, -0.0175), (0.0935, -0.0190), (0.0946, -0.0216), (0.0936, -0.0246),
                 (0.0896, -0.0273), (0.0851, -0.0301), (0.0818, -0.0336), (0.0800, -0.0372), (0.0420, -0.0372),
                 (0.0400, -0.0315), (-0.0200, -0.0315), (-0.0905, -0.0315), (-0.0950, -0.0300), (-0.0958, -0.0200)]
# big square trigger guard with a finger hook at the front
GUARD_PROFILE = [(-0.0268, -0.0300), (-0.0272, -0.0438), (-0.0302, -0.0498), (-0.0294, -0.0538),
                 (-0.0258, -0.0566), (-0.0185, -0.0580), (0.0400, -0.0580), (0.0400, -0.0522),
                 (-0.0148, -0.0522), (-0.0188, -0.0506), (-0.0210, -0.0472), (-0.0214, -0.0420),
                 (-0.0214, -0.0300)]


def strap_front_z(y):
    return 0.0295 + (-0.030 - y) * TAN18


def strap_back_z(y):
    return 0.0802 + (-0.036 - y) * TAN18


GRIP_WIDTH = [(-0.0035, 0.0228), (0.0, 0.0246), (0.005, 0.0288), (0.012, 0.0310), (0.026, 0.0316),
              (0.046, 0.0316), (0.064, 0.0314), (0.0740, 0.0322), (0.0785, 0.0334), (GRIP_LEN, 0.0340)]


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


def _shape(half_w, s_f, s_b, count=20, n_front=2.6, n_back=3.6):
    """Grip ring: a squarer back strap than the P9's (USP: flat sides, square back)."""
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    n = np.where(s > 0, n_front, n_back)
    x = half_w * np.sign(c) * np.abs(c) ** (2 / n)
    sv = np.where(s > 0, s_f, s_b) * np.sign(s) * np.abs(s) ** (2 / n)
    return x, sv


def mag_outline(clear=0.0, count_corner=4):
    """Magazine cross-section in mag-local (x, front) coordinates, CCW."""
    return sh.rounded_rect(-MAG_HW - clear, -MAG_HD - clear, MAG_HW + clear, MAG_HD + clear,
                           (0.0036 + clear, 0.0036 + clear, 0.0066 + clear, 0.0066 + clear), count_corner)


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
    top = outer.f[-1]
    pv = np.array(outer.v)[list(top)]
    if np.cross(pv[1] - pv[0], pv[2] - pv[0])[1] < 0:
        outer.f[-1] = tuple(reversed(top))
    # textured panels on the sides and the front strap (USP: fine checkering all round below the frame)
    outer.set_material(lambda c, n: (abs(n[0]) > 0.35 or n[2] < -0.6) and -0.0975 < c[1] < -0.0400, "grip")
    # the magazine's corners nearly touch the rounded front strap: the well follows the grip there
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
    # lanyard loop boss at the bottom of the back strap
    c_end, s_dir, _, s_b = _ring_frame(GRIP_LEN)
    boss = c_end - s_dir * (s_b - 0.0015) - 0.0040 * AXIS
    outer.merge(sh.box(-0.0055, -0.0030, -0.0035, 0.0055, 0.0030, 0.0035, 0.0012, "polymer").translated(boss))
    return outer.merge(bottom).merge(walls)


def rail_mesh():
    """Accessory rail under the dust cover: a flat block with two side grooves and three cross slots."""
    base = np.array([(-0.0098, -0.0386), (0.0098, -0.0386), (0.0112, -0.0372), (0.0112, -0.0358),
                     (0.0098, -0.0350), (0.0098, -0.0334), (0.0108, -0.0326), (0.0108, -0.0312),
                     (-0.0108, -0.0312), (-0.0108, -0.0326), (-0.0098, -0.0334), (-0.0098, -0.0350),
                     (-0.0112, -0.0358), (-0.0112, -0.0372)])
    slot = base.copy()
    slot[:, 1] = np.maximum(slot[:, 1], -0.0360)
    secs = []
    add = lambda z, s, ins=0.0: secs.append(
        np.stack([*(sh.offset_loop(s, ins) if ins else s).T, np.full(len(s), z)], 1))
    add(-0.0900, base, 0.0007)
    add(-0.0893, base)
    for zc in (-0.0700,):
        add(zc - 0.0022, base), add(zc - 0.0021, slot), add(zc + 0.0021, slot), add(zc + 0.0022, base)
    add(-0.0368, base)
    add(-0.0360, base, 0.0007)
    return sh.loft(secs, "polymer")


def control_lever_mesh():
    """Safety / decocking lever on the left rear of the frame: a paddle pivoting under the slide's rear."""
    prof = [(0.0800, -0.0250), (0.0830, -0.0205), (0.0790, -0.0195), (0.0680, -0.0230), (0.0610, -0.0262),
            (0.0596, -0.0290), (0.0612, -0.0306), (0.0660, -0.0296), (0.0750, -0.0270)]
    m = sh.extrude_x(prof, (), 0.0008, chamfer_bevel(0.0003), "steel_dark", x_center=-0.0127)
    m.merge(pin((0, -0.0228, 0.0800), 0.0022, 0.0127, "steel_dark", seg=8))
    return m


def mag_release_mesh():
    """Ambidextrous magazine release paddle behind the trigger guard (the thumb presses its left end)."""
    m = Mesh()
    for s in (-1, 1):
        x0, x1 = sorted((s * 0.0112, s * 0.0160))
        m.merge(sh.box(x0, -0.0376, 0.0326, x1, -0.0300, 0.0414, 0.0010, "polymer"))
    return m


def frame_mesh():
    m = sh.extrude_x(FRAME_PROFILE, (), 0.0118, round_bevel(0.0018, 1), "polymer")
    m.merge(sh.extrude_x(GUARD_PROFILE, (), 0.0060, round_bevel(0.0024, 1), "polymer"))
    for k in range(4):                                   # serrations on the front of the guard
        y = -0.0452 - k * 0.0018
        m.merge(sh.box(-0.0040, y, -0.0288 - 0.0004 * k, 0.0040, y + 0.0008, -0.0278 - 0.0004 * k, 0.0002,
                       "polymer"))
    m.merge(rail_mesh())
    m.merge(grip_mesh())
    m.merge(mag_release_mesh())
    m.merge(control_lever_mesh())
    m.merge(sh.box(-0.0127, -0.0214, -0.0150, 0.0127, -0.0188, -0.0052, 0.0003, "steel_dark"))  # takedown
    for y, z in ((-0.0250, 0.0040), (-0.0255, -0.0105), (-0.0222, 0.0370)):
        m.merge(pin((0, y, z), 0.0011, 0.0121, "steel_dark", seg=8))
    return m
