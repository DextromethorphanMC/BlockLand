"""Universal attachment geometry (VIEWMODEL_SPEC section 19).

Attachment frame (metres, real scale): origin = the mount point, +X right, +Y up, -Z towards the muzzle. The mount
node in the weapon file sits where that origin goes, so one model fits every weapon. Each builder returns a Part:
meshes and helper nodes, every node with its parent and translation (root node `attachment`)."""
import numpy as np

import p9_model as pm
import p9_parts as pp
import shapes as sh
import textures as tx
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z


class Part:
    """nodes: [(name, parent, translation)], meshes: {node: Mesh in that node's space}."""

    def __init__(self):
        self.nodes = [("attachment", None, np.zeros(3))]
        self.meshes = {}

    def node(self, name, parent="attachment", t=(0.0, 0.0, 0.0), mesh=None):
        self.nodes.append((name, parent, np.asarray(t, float)))
        if mesh is not None:
            self.meshes[name] = mesh
        return self


# ------------------------------------------------------------------------------------------------- optics
EYE_RELIEF = 0.140                 # eye point behind the window (section 5)
MINI_BASE = np.array([0.0, 0.0124, 0.0530])        # P9 design frame: bottom centre of the slide optic's base


def _inward(c, n, axis=(0.0, 0.0), share=0.5):
    """set_material test: the face's normal points towards the Z axis through `axis` (x, y) by at least `share`."""
    d = np.array([axis[0] - c[0], axis[1] - c[1]])
    ln = np.linalg.norm(d)
    return ln > 1e-9 and float(n[:2] @ d) / ln > share


def mini_reddot():
    """Pistol-style slide optic (the P9's mini red dot): low body with a hooded window. The hood's window walls
    (and their chamfers) are what the eye looks along when aiming: matte black `interior`."""
    p = Part()
    m = pp.reddot_mesh()
    hood_axis = (0.0, 0.0260)                                       # centre of the hood's window (design frame)
    m.set_material(lambda c, n: 0.0375 <= c[2] <= 0.0475 and abs(c[0]) <= 0.0099 and 0.0185 <= c[1] <= 0.0335
                   and abs(n[2]) < 0.9 and _inward(c, n, hood_axis, 0.3), "interior")
    p.meshes["attachment"] = m.translated(-MINI_BASE)
    reticle = np.array([0.0, pm.REDDOT_Y, pm.REDDOT_GLASS_Z + 0.0003]) - MINI_BASE
    p.node("reticle", "attachment", reticle, pp.reticle_mesh(0.0018))
    p.node("aim", "attachment", reticle + np.array([0.0, 0.0, EYE_RELIEF]))
    return p


TUBE_H = 0.0235                    # tube red dot: sight line above the base
TUBE_R = 0.0135
TUBE_GLASS_Z = -0.0260


def _cyl_axis(r, length, centre, axis, material, seg=14, chamfer=0.0006):
    """Cylinder of radius r along `axis` ('x' or 'y'), centred on `centre`."""
    h = length / 2
    c = min(chamfer, 0.45 * h)
    m = sh.revolve([(0.0, -h), (r - c, -h), (r, -h + c), (r, h - c), (r - c, h), (0.0, h)], seg, material)
    if axis == "x":
        m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    elif axis == "y":
        m = sh.place(m, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))
    return m.translated(centre)


def reddot():
    """Tube red dot (from the AK's optic) on a low picatinny mount: clamp base, riser, open tube with turrets."""
    p = Part()
    m = sh.box(-0.0105, 0.0, -0.0240, 0.0105, 0.0055, 0.0240, 0.0008, "attachment")              # rail clamp
    for z in (-0.0180, -0.0060, 0.0060, 0.0180):                                                   # cross-slot lugs
        m.merge(sh.box(-0.0108, 0.0004, z - 0.0018, 0.0108, 0.0018, z + 0.0018, 0.0003, "attachment"))
    m.merge(_cyl_axis(0.0042, 0.0060, (0.0135, 0.0030, 0.0040), "x", "steel_dark", 12))           # clamp knob
    riser = sh.rounded_rect(-0.0080, 0.0050, 0.0080, TUBE_H - 0.0100, (0.0, 0.0, 0.0025, 0.0025), 2)
    m.merge(extrude_z(riser, (), -0.0160, 0.0160, chamfer_bevel(0.0008), "attachment"))
    tube = sh.revolve([(0.0118, 0.0420), (0.0118, -0.0400), (0.0145, -0.0400), (0.0150, -0.0370),
                       (0.0136, -0.0330), (TUBE_R, 0.0360), (0.0145, 0.0380), (0.0145, 0.0420),
                       (0.0118, 0.0420)], 24, "attachment")
    tube.set_material(_inward, "interior")                         # the bore the eye looks down: matte black
    m.merge(tube.translated((0.0, TUBE_H, 0.0)))
    m.merge(_cyl_axis(0.0050, 0.0070, (0.0, TUBE_H + TUBE_R + 0.0028, 0.0040), "y", "attachment", 14))   # elevation
    m.merge(_cyl_axis(0.0050, 0.0070, (TUBE_R + 0.0028, TUBE_H, 0.0040), "x", "attachment", 14))         # windage
    m.merge(_cyl_axis(0.0030, 0.0040, (-TUBE_R - 0.0015, TUBE_H - 0.0040, 0.0120), "x", "steel_dark", 10))  # switch
    glass = Mesh()
    ring = [(0.0118 * np.cos(a), TUBE_H + 0.0118 * np.sin(a), TUBE_GLASS_Z)
            for a in np.linspace(0, 2 * np.pi, 20, endpoint=False)]
    glass.add_poly(ring, "glass")
    m.merge(glass)
    p.meshes["attachment"] = m
    reticle = np.array([0.0, TUBE_H, TUBE_GLASS_Z + 0.0003])
    p.node("reticle", "attachment", reticle, pp.reticle_mesh(0.0020))
    p.node("aim", "attachment", reticle + np.array([0.0, 0.0, EYE_RELIEF]))
    return p


# ---------------------------------------------------------------------------------------- holographic sight
HOLO_H = 0.0285                    # window centre over the mount point
HOLO_W, HOLO_WH = 0.0300, 0.0240   # window (glass) width / height
HOLO_GLASS_Z = -0.0040             # the window sits at the rear end of the hood
HOLO_HOOD = 0.0420                 # hood depth in front of the window
HOLO_RELIEF = 0.130                # eye point behind the window
HOLO_DRAFT = (0.13, 0.11)          # the hood's inner walls open forward (x, y) more steeply than the sight lines from
                                   # the eye to the window's edges (0.115, 0.092): none of them shows through the glass
HOLO_OUT_DRAFT = (0.065, 0.055)    # the outer skin flares half as much (the walls thin out towards the front)
HOLO_BEVEL = 0.0012                # the window's rear edge, chamfered towards the eye


def _flare(loop, t, cy, dx, dy, bottom=True):
    """(x, y) loop grown by t: sides out by dx * t, top up by dy * t, bottom down by dy * t (unless not `bottom`)."""
    p = np.array(loop, float)
    p[:, 0] += np.sign(p[:, 0]) * dx * t
    up = p[:, 1] > cy
    p[:, 1] += np.where(up, dy * t, -dy * t if bottom else 0.0)
    return p


def _at_z(loop, z):
    return np.column_stack([loop, np.full(len(loop), z)])


def _hood_outline():
    """CCW (x, y) outline of the hood at the window: side walls rising into two rounded ears either side of the top
    bar; the flat bottom sinks into the body."""
    xo, xe, yb, yt, ye, re = 0.0200, 0.0163, 0.0105, 0.0448, 0.0490, 0.0015
    right = [(xo - re + re * np.cos(a), ye - re + re * np.sin(a)) for a in np.linspace(0, np.pi / 2, 4)]
    left = [(-xo + re + re * np.cos(a), ye - re + re * np.sin(a)) for a in np.linspace(np.pi / 2, np.pi, 4)]
    return np.array([(-xo, yb), (xo, yb)] + right + [(xe, ye), (xe, yt), (-xe, yt), (-xe, ye)] + left)


def holo():
    """EOTech-style holographic sight: a boxy body on a rail clamp, a rectangular hood over the window with two ears
    protecting it, the brightness buttons on the back, a battery cap on the right. The window is a flat `glass`
    face (the hood's rounded opening) at the hood's rear end, UV 0..1 across it (u along +X, v down); the hood's
    inner walls open forward so the eye never sees them through it, and they are matte black `interior`. No `reticle`
    node: the runtime draws the holographic reticle in the glass. `aim` is the eye point on the window's centre line."""
    p = Part()
    cy, zg, D, b = HOLO_H, HOLO_GLASS_Z, HOLO_HOOD, HOLO_BEVEL
    dx, dy = HOLO_DRAFT
    ox, oy = HOLO_OUT_DRAFT
    # rail clamp with cross-slot lugs and the clamp's thumb nut (left)
    m = sh.box(-0.0105, 0.0, -0.0420, 0.0105, 0.0055, 0.0420, 0.0008, "attachment")
    for z in (-0.0300, -0.0100, 0.0100, 0.0300):
        m.merge(sh.box(-0.0108, 0.0004, z - 0.0018, 0.0108, 0.0018, z + 0.0018, 0.0003, "attachment"))
    m.merge(_cyl_axis(0.0040, 0.0050, (-0.0128, 0.0027, 0.0120), "x", "steel_dark", 14))
    # body: low under the hood, a ramp behind the window that stays under the sight lines to its bottom edge
    zr, zf = 0.0425, -0.0525
    body = [(zf + 0.0020, 0.0045), (zr - 0.0020, 0.0045), (zr, 0.0065), (zr, 0.0150), (zr - 0.0030, 0.0180),
            (0.0340, 0.0186), (zg + 0.0006, 0.0152), (zg + 0.0006, 0.0110), (zf + 0.0025, 0.0110), (zf, 0.0088),
            (zf, 0.0065)]
    m.merge(sh.extrude_x(body, (), 0.0150, chamfer_bevel(0.0010), "attachment"))
    for x0, x1 in ((-0.0105, -0.0030), (0.0030, 0.0105)):                                  # brightness buttons
        m.merge(sh.box(x0, 0.0080, zr - 0.0005, x1, 0.0128, zr + 0.0013, 0.0005, "steel_dark"))
    cap = _knurled(0.0050, 0.0, 0.0042, 4, 0.0004, "steel_dark", 20)                       # battery cap (right)
    m.merge(sh.place(cap, origin=(0.0143, 0.0104, 0.0200), x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0)))
    # hood: outer skin (ears, top bar, sides; chamfered ends), the window's inner walls, the end faces
    inner = sh.rounded_rect(-HOLO_W / 2, cy - HOLO_WH / 2, HOLO_W / 2, cy + HOLO_WH / 2, 0.0020, 3)
    in_rear, in_front = _flare(inner, b, cy, 1.0, 1.0), _flare(inner, D, cy, dx, dy)
    c = 0.0006
    out0 = _hood_outline()
    outD, outDc = _flare(out0, D, cy, ox, oy, bottom=False), _flare(out0, D - c, cy, ox, oy, bottom=False)
    hood = sh.loft([_at_z(sh.offset_loop(outD, c), zg - D), _at_z(outDc, zg - D + c),
                    _at_z(out0, zg + b - c), _at_z(sh.offset_loop(out0, c), zg + b)], "attachment",
                   cap_start=False, cap_end=False)
    hood.merge(sh.loft([_at_z(in_rear, zg + b), _at_z(inner, zg), _at_z(in_front, zg - D)], "interior",
                       cap_start=False, cap_end=False))
    sh.cap_polygon(hood, _at_z(sh.offset_loop(out0, c), zg + b), "attachment", holes3d=[_at_z(in_rear, zg + b)])
    sh.cap_polygon(hood, _at_z(sh.offset_loop(outD, c), zg - D), "attachment", holes3d=[_at_z(in_front, zg - D)],
                   flip=True)
    m.merge(hood)
    # the window: the hood's opening at the glass plane, facing the eye
    uv = [((x + HOLO_W / 2) / HOLO_W, (cy + HOLO_WH / 2 - y) / HOLO_WH) for x, y in inner]
    m.add_poly(_at_z(inner, zg), "glass", uvs=uv)
    p.meshes["attachment"] = m
    p.node("aim", "attachment", (0.0, cy, zg + HOLO_RELIEF))
    return p


# --------------------------------------------------------------------------------------------- scopes (section 22)
SCOPE_RELIEF = 0.085               # eye point behind the eyepiece glass (section 22: 0.07 .. 0.09)
SNIPER_H = 0.0400                  # sniper scope: optical axis above the mount point (clears a 56 mm objective)
SCOPE2_H = 0.0330                  # 2x prism scope


def lens_disc(r, facing, material, seg=32):
    """Flat glass disc at the origin facing +Z (facing=+1, towards the eye) or -Z (-1); UV (0.5, 0.5) at the centre,
    radius 0.5 at the rim, u along +X, v down (-Y), so a texture shows upright through the eyepiece."""
    m = Mesh()
    t = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    ring = [(r * np.cos(a), r * np.sin(a), 0.0) for a in t]
    uv = [(0.5 + 0.5 * np.cos(a), 0.5 - 0.5 * np.sin(a)) for a in t]
    c = m.add_vertices([(0.0, 0.0, 0.0)])
    base = m.add_vertices(ring)
    for i in range(seg):
        j = (i + 1) % seg
        if facing > 0:
            m.add_face([c, base + i, base + j], material, uvs=[(0.5, 0.5), uv[i], uv[j]])
        else:
            m.add_face([c, base + j, base + i], material, uvs=[(0.5, 0.5), uv[j], uv[i]])
    return m


def _knurled(r, z0, z1, ribs, depth, material, seg=36):
    """Axially fluted adjustment ring with chamfered shoulders and top indexing.
    Replaces stacked lathe rings: tactile silhouette, fewer triangles, same material batches.
    `ribs` sets the number of major graduations, not another stack of geometry rings.
    """
    n = max(24, 4 * ((seg + 3) // 4))
    a = np.linspace(0, 2 * np.pi, n, endpoint=False)
    flute = np.where(np.arange(n) % 2, depth, 0.0)
    bevel = min(0.0006, (z1 - z0) * 0.15)
    sections = []
    for z, radius in ((z0, np.full(n, r - bevel)), (z0 + bevel, r - flute),
                      (z1 - bevel, r - flute), (z1, np.full(n, r - bevel))):
        sections.append(np.stack([radius * np.cos(a), radius * np.sin(a), np.full(n, z)], 1))
    m = sh.loft(sections, material)
    # Small shallow index inlays stay within the existing steel_dark primitive; never lens/glass.
    for k in range(ribs * 3):
        angle = k * 2 * np.pi / (ribs * 3)
        u = np.array([np.cos(angle), np.sin(angle), 0.0])
        v = np.array([-np.sin(angle), np.cos(angle), 0.0])
        outer = r - bevel - 0.0004
        inner = outer - (0.0022 if k % 3 == 0 else 0.0011)
        pts = [u * inner - v * 0.00016, u * outer - v * 0.00016,
               u * outer + v * 0.00016, u * inner + v * 0.00016]
        m.add_poly(np.array(pts) + [0, 0, z1 + 0.000015], "steel_dark")
    return m



CUP_RATIO = 1.40                   # section 22: eyecup outer radius / eyepiece glass radius (1.35 .. 1.45)


def _eyecup(r_glass, z_glass, depth, bell, ratio=CUP_RATIO, taper=0.0, steps=8):
    """Rubber eyecup round an eyepiece glass (section 22), in the scope's axis frame (Z along the optical axis).
    Outer radius `ratio` x r_glass (narrowing by `taper` towards the rim), rear rim `depth` behind the glass with a
    soft elliptical lip; it tucks into the ocular bell's last ring `bell` (r, z). The inner wall the eye looks along
    (glass edge -> lip, opening towards the eye) is matte black `interior`, so at ADS the glass sits in a dark ring,
    then the rubber."""
    r_out = ratio * r_glass
    r_in = r_glass + 0.0018 * (r_glass / 0.0186) ** 0.5           # where the lip starts on the inside
    r_lip = r_out - taper
    e_r, e_z = (r_lip - r_in) / 2, 0.0022 * (r_glass / 0.0186) ** 0.5
    top = z_glass + depth
    br, bz = bell
    prof = [(br - 0.0004, bz), (br + 0.0004, bz + 0.0006), (r_out - 0.0008, bz + 0.3 * (top - e_z - bz)),
            (r_out, bz + 0.55 * (top - e_z - bz)), (r_lip, top - e_z)]
    for a in np.linspace(0.0, np.pi, steps + 1)[1:]:                 # the lip, outside -> top -> inside
        prof.append((r_in + e_r + e_r * np.cos(a), top - e_z + e_z * np.sin(a)))
    cup = sh.revolve(prof, 40, "scope_rubber")
    wall = [(r_in, top - e_z), (r_glass + 0.0006, z_glass + 0.4 * depth), (r_glass + 0.0001, z_glass - 0.0008)]
    cup.merge(sh.revolve(wall, 40, "interior", outward=False))
    return cup


def _ring_mount(z, axis_h, r_tube, material):
    """One scope ring: band round the tube, stanchion down to the base, cap screws."""
    m = sh.revolve([(r_tube, -0.0070), (r_tube + 0.0042, -0.0070), (r_tube + 0.0045, -0.0064),
                    (r_tube + 0.0045, 0.0064), (r_tube + 0.0042, 0.0070), (r_tube, 0.0070), (r_tube, -0.0070)],
                   24, material)
    m = m.translated((0.0, axis_h, z))
    m.merge(sh.box(-0.0095, 0.0060, z - 0.0068, 0.0095, axis_h - r_tube + 0.0010, z + 0.0068, 0.0010, material))
    for s in (-1, 1):                                                       # cap split screws
        m.merge(_cyl_axis(0.0019, 0.0030, (s * (r_tube + 0.0050), axis_h + 0.0010, z), "x", "steel_dark", 10))
    return m


def scope_sniper():
    """Long variable-power sniper scope (6x in the data) in a one-piece cantilever mount: 30 mm tube, 56 mm
    objective bell, elevation / windage turrets, side parallax knob, power ring with a throw lever, ocular bell.
    `lens_ocular` (towards the eye) and `lens_objective` are the glass discs; `aim` is the eye point."""
    p = Part()
    H, R = SNIPER_H, 0.0150
    z_ocu, z_obj = 0.1305, -0.2135
    m = sh.box(-0.0115, 0.0, -0.0700, 0.0115, 0.0065, 0.0600, 0.0010, "scope")                   # rail base
    for z in (-0.0560, -0.0160, 0.0240):                                                            # recoil lugs
        m.merge(sh.box(-0.0118, 0.0004, z - 0.0020, 0.0118, 0.0020, z + 0.0020, 0.0003, "scope"))
    for z in (-0.0450, 0.0350):                                                                     # clamp screws
        m.merge(_cyl_axis(0.0042, 0.0050, (0.0140, 0.0032, z), "x", "steel_dark", 12))
    for z in (-0.0500, 0.0400):
        m.merge(_ring_mount(z, H, R, "scope"))
    tube = sh.revolve([(R - 0.0012, -0.1060), (R, -0.1060), (R, 0.0620), (R - 0.0012, 0.0620)], 32, "scope")
    m.merge(tube.translated((0.0, H, 0.0)))
    obj = [(R - 0.0010, -0.1040), (R + 0.0008, -0.1120), (0.0250, -0.1450), (0.0300, -0.1640), (0.0310, -0.1720),
           (0.0310, -0.2180), (0.0298, -0.2220), (0.0276, -0.2220), (0.0276, z_obj - 0.0010)]
    m.merge(sh.revolve(obj, 32, "scope").translated((0.0, H, 0.0)))
    # turret saddle with the elevation (top), windage (right) and parallax (left) knobs
    saddle = sh.rounded_rect(-0.0190, H - 0.0190, 0.0190, H + 0.0190, 0.0080, 3)
    m.merge(extrude_z(saddle, (), -0.0280, 0.0200, chamfer_bevel(0.0015), "scope"))
    top = _knurled(0.0125, 0.0, 0.0175, 4, 0.0006, "scope", 22)
    m.merge(sh.place(top, origin=(0.0, H + 0.0185, -0.0040), x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0)))
    m.merge(sh.box(-0.0006, H + 0.0358, -0.0040 - 0.0110, 0.0006, H + 0.0362, -0.0040 - 0.0060, 0.0, "steel_dark"))
    side = _knurled(0.0120, 0.0, 0.0165, 4, 0.0006, "scope", 22)
    m.merge(sh.place(side, origin=(0.0185, H, -0.0040), x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0)))
    focus = _knurled(0.0165, 0.0, 0.0120, 3, 0.0006, "scope", 24)
    m.merge(sh.place(focus, origin=(-0.0185, H, -0.0040), x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(-1, 0, 0)))
    # power ring, throw lever and the ocular bell with its rubber eyecup ring
    m.merge(_knurled(0.0185, 0.0600, 0.0820, 5, 0.0007, "scope", 30).translated((0.0, H, 0.0)))
    m.merge(sh.box(0.0175, H - 0.0030, 0.0640, 0.0255, H + 0.0030, 0.0780, 0.0010, "scope"))
    # the bell flares into the eyecup, whose silhouette hides the whole scope from the eye point (section 22)
    ocu = [(0.0170, 0.0820), (0.0194, 0.0900), (0.0220, 0.1060), (0.0234, 0.1180), (0.0238, 0.1240)]
    m.merge(sh.revolve(ocu, 40, "scope").translated((0.0, H, 0.0)))
    # shallow, tapering towards the rim: the AWP's fire kick keeps it inside the 4:3 frame (check_sniper framing)
    m.merge(_eyecup(0.0186, z_ocu, 0.0075, ocu[-1], taper=0.0016).translated((0.0, H, 0.0)))
    p.meshes["attachment"] = m
    p.node("lens_ocular", "attachment", (0.0, H, z_ocu), lens_disc(0.0186, 1, "lens"))
    p.node("lens_objective", "attachment", (0.0, H, z_obj), lens_disc(0.0276, -1, "lens_front"))
    p.node("aim", "attachment", (0.0, H, z_ocu + SCOPE_RELIEF))
    return p


def scope_2x():
    """Compact 2x prism scope (ACOG pattern) on its own flat-top clamp: objective housing with a sunshade lip,
    prism body with adjuster caps and the illumination tube on top, short ocular with a rubber ring."""
    p = Part()
    H = SCOPE2_H
    z_ocu, z_obj = 0.0555, -0.0835
    m = sh.box(-0.0115, 0.0, -0.0360, 0.0115, 0.0085, 0.0360, 0.0010, "scope")                   # clamp base
    for z in (-0.0200, 0.0100):                                                                     # recoil lugs
        m.merge(sh.box(-0.0118, 0.0004, z - 0.0020, 0.0118, 0.0020, z + 0.0020, 0.0003, "scope"))
    lever = sh.box(0.0115, 0.0010, -0.0160, 0.0160, 0.0070, 0.0240, 0.0010, "steel_dark")          # throw lever
    m.merge(lever)
    m.merge(_cyl_axis(0.0040, 0.0040, (0.0135, 0.0040, -0.0190), "x", "steel_dark", 12))
    # prism body: wider at the bottom, sloped faces front and back
    body_lo = sh.rounded_rect(-0.0170, 0.0080, 0.0170, H + 0.0150, (0.0020, 0.0020, 0.0090, 0.0090), 3)
    body_hi = sh.rounded_rect(-0.0150, 0.0080, 0.0150, H + 0.0130, (0.0020, 0.0020, 0.0080, 0.0080), 3)
    m.merge(_scope_loft([(sh.offset_loop(body_hi, 0.0010), -0.0420), (body_hi, -0.0400), (body_lo, -0.0300),
                         (body_lo, 0.0200), (body_hi, 0.0300), (sh.offset_loop(body_hi, 0.0010), 0.0320)], "scope"))
    obj = [(0.0150, -0.0400), (0.0180, -0.0440), (0.0180, -0.0800), (0.0188, -0.0810), (0.0188, -0.0870),
           (0.0172, -0.0870), (0.0164, z_obj - 0.0006)]
    m.merge(sh.revolve(obj, 36, "scope").translated((0.0, H, 0.0)))
    ocu = [(0.0120, 0.0300), (0.0150, 0.0330), (0.0154, 0.0420), (0.0162, 0.0480), (0.0168, 0.0505)]
    m.merge(sh.revolve(ocu, 36, "scope").translated((0.0, H, 0.0)))
    m.merge(_eyecup(0.0130, z_ocu, 0.0085, ocu[-1], taper=0.0006).translated((0.0, H, 0.0)))
    # adjuster caps (top elevation, right windage) and the fibre-optic illumination tube on top
    cap = _knurled(0.0072, 0.0, 0.0060, 3, 0.0004, "scope")
    m.merge(sh.place(cap, origin=(0.0, H + 0.0140, -0.0060), x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0)))
    m.merge(sh.place(cap, origin=(0.0160, H - 0.0010, -0.0060), x_axis=(0, 0, -1), y_axis=(0, 1, 0),
                     z_axis=(1, 0, 0)))
    m.merge(sh.revolve([(0.0, -0.0300), (0.0034, -0.0300), (0.0034, 0.0220), (0.0, 0.0220)], 12, "steel_dark")
            .translated((0.0, H + 0.0212, 0.0)))
    for z in (-0.0280, 0.0200):                                                                     # tube clips
        m.merge(sh.box(-0.0045, H + 0.0130, z - 0.0025, 0.0045, H + 0.0230, z + 0.0025, 0.0006, "scope"))
    p.meshes["attachment"] = m
    p.node("lens_ocular", "attachment", (0.0, H, z_ocu), lens_disc(0.0130, 1, "lens"))
    p.node("lens_objective", "attachment", (0.0, H, z_obj), lens_disc(0.0164, -1, "lens_front"))
    p.node("aim", "attachment", (0.0, H, z_ocu + SCOPE_RELIEF))
    return p


def _scope_loft(pairs, material):
    """Loft of (x, y) loops at increasing z (same vertex count)."""
    return sh.loft([np.stack([np.asarray(s)[:, 0], np.asarray(s)[:, 1], np.full(len(s), z)], 1) for s, z in pairs],
                   material)


def reticle_image(kind, size=1024):
    """RGBA reticle for a scope's eyepiece (section 22): black = etched lines, red = illuminated. The image spans
    the whole eyepiece (reticleSize 1.0); its centre is the optical axis. Anti-aliased by 4x supersampling."""
    ss = 4
    n = size * ss
    c = (np.arange(n) + 0.5) / n - 0.5                     # -0.5 .. 0.5 across the eyepiece
    x, y = np.meshgrid(c, -c)                              # y up
    etched = np.zeros((n, n), bool)
    lit = np.zeros((n, n), bool)
    px = 1.0 / size
    if kind == "sniper":
        thin, thick, post_r = 1.6 * px, 9.0 * px, 0.30
        r = np.hypot(x, y)
        etched |= (np.abs(y) <= thin / 2) & (np.abs(x) <= post_r)
        etched |= (np.abs(x) <= thin / 2) & (np.abs(y) <= post_r)
        etched |= (np.abs(y) <= thick / 2) & (np.abs(x) > post_r) & (r < 0.5)
        etched |= (np.abs(x) <= thick / 2) & (np.abs(y) > post_r) & (r < 0.5)
        for k in range(1, 6):                              # mil dots along the thin lines
            d = k * 0.050
            for cx, cy in ((d, 0), (-d, 0), (0, d), (0, -d)):
                etched |= np.hypot(x - cx, y - cy) <= 3.2 * px
        for k in range(1, 10):                             # half-mil hash marks between the dots
            d = (k + 0.5) * 0.050 if k < 6 else None
            if d is None or d > post_r:
                continue
            etched |= (np.abs(x - d) <= thin / 2) & (np.abs(y) <= 6 * px)
            etched |= (np.abs(x + d) <= thin / 2) & (np.abs(y) <= 6 * px)
            etched |= (np.abs(y + d) <= thin / 2) & (np.abs(x) <= 6 * px)
        lit |= np.hypot(x, y) <= 3.6 * px                  # illuminated centre dot
        etched &= ~(np.hypot(x, y) <= 5.5 * px)
    else:                                                  # 2x: illuminated chevron over a bullet-drop ladder
        tip_y = 0.0
        h, half_w, w = 0.060, 0.036, 6.0 * px
        u = np.abs(x)
        on_arm = (y <= tip_y) & (y >= tip_y - h) & (np.abs((tip_y - y) - u * h / half_w) <= w * 1.25) & (u <= half_w)
        lit |= on_arm
        lit |= (np.hypot(x, y - tip_y) <= 2.5 * px)
        etched |= (np.abs(x) <= 1.4 * px) & (y < tip_y - h - 0.010) & (y > -0.36)
        for k, (yy, ww) in enumerate(((-0.105, 0.050), (-0.150, 0.040), (-0.195, 0.032), (-0.240, 0.025),
                                      (-0.285, 0.018))):
            etched |= (np.abs(y - yy) <= 1.6 * px) & (np.abs(x) <= ww)
        etched |= (np.abs(y) <= 1.4 * px) & (np.abs(x) >= 0.20) & (np.abs(x) <= 0.42)   # horizontal stadia
        etched |= (np.abs(x) <= 1.4 * px) & (y >= 0.20) & (y <= 0.42)
    img = np.zeros((n, n, 4))
    img[etched] = (0.02, 0.02, 0.02, 1.0)
    img[lit] = (1.0, 0.18, 0.12, 1.0)
    img = img.reshape(size, ss, size, ss, 4).mean(axis=(1, 3))
    rgb = np.where(img[:, :, 3:4] > 1e-6, img[:, :, :3] / np.maximum(img[:, :, 3:4], 1e-6), 0.0)
    return np.dstack([np.clip(rgb, 0, 1), img[:, :, 3]])


# --------------------------------------------------------------------------------------------------- laser
def laser():
    """Rail laser module (one model, the beam colour is data): hangs below its rail clamp, lens facing -Z."""
    p = Part()
    p.meshes["attachment"] = pp.laser_mesh().translated(-pm.LASER_ORIGIN)
    p.node("emitter", "attachment", pm.LASER_LENS - pm.LASER_ORIGIN)
    return p


# ---------------------------------------------------------------------------------------------- suppressor
SUP_LEN = 0.160
SUP_R = 0.0165


def suppressor():
    """Universal can: threads on at the origin (a stub reaches back into the barrel), muzzle at the front cap."""
    p = Part()
    L, R = SUP_LEN, SUP_R
    m = sh.revolve([(0.0, 0.0060), (0.0062, 0.0060), (0.0062, 0.0), (0.0125, 0.0), (0.0156, -0.0040),
                    (R, -0.0100), (R, -L + 0.0100), (0.0156, -L + 0.0040), (0.0092, -L), (0.0056, -L),
                    (0.0056, -L + 0.0120), (0.0, -L + 0.0120)], 28, "suppressor")
    for z in (-0.0170, -L + 0.0170):                                                   # end-cap rings
        m.merge(sh.revolve([(R - 0.0002, z - 0.0018), (R + 0.0004, z - 0.0012), (R + 0.0004, z + 0.0012),
                            (R - 0.0002, z + 0.0018)], 28, "steel_dark"))
    for k in range(10):                                                                  # wrench flats / grip rings
        z = -0.0300 - k * 0.0028
        m.merge(sh.revolve([(R - 0.0001, z - 0.0007), (R + 0.0003, z - 0.0004), (R + 0.0003, z + 0.0004),
                            (R - 0.0001, z + 0.0007)], 28, "steel_dark"))
    m.merge(sh.disc((0.0, 0.0, -L + 0.0119), (0, 0, -1), 0.0056, 16, "steel_dark"))     # baffle seen down the bore
    p.meshes["attachment"] = m
    p.node("muzzle", "attachment", (0.0, 0.0, -L))
    return p


# --------------------------------------------------------------------------------------------------- stock
def tactical_stock():
    """Skeletonised folding stock: hinge block at the origin, top strut and a lower strut to the butt plate, cheek
    rest on top; extends rearward (+Z) about 0.24 m."""
    p = Part()
    m = sh.box(-0.0130, -0.0300, -0.0010, 0.0130, 0.0110, 0.0300, 0.0020, "attachment")       # hinge / adapter
    m.merge(_cyl_axis(0.0045, 0.0300, (0.0, -0.0090, 0.0150), "x", "steel_dark", 12))         # hinge pin
    top = np.array([(0.0, 0.0090), (0.0, 0.0010), (0.2120, -0.0060), (0.2120, 0.0040)])         # (z, y) top strut
    m.merge(sh.extrude_x(top, (), 0.0060, chamfer_bevel(0.0008), "attachment"))
    low = np.array([(0.0200, -0.0300), (0.0200, -0.0210), (0.2120, -0.0800), (0.2120, -0.0890)])
    m.merge(sh.extrude_x(low, (), 0.0050, chamfer_bevel(0.0007), "attachment"))
    brace = np.array([(0.1050, -0.0020), (0.1150, -0.0030), (0.1410, -0.0520), (0.1310, -0.0510)])
    m.merge(sh.extrude_x(brace, (), 0.0045, chamfer_bevel(0.0006), "attachment"))
    cheek = sh.rounded_rect(-0.0140, -0.0010, 0.0140, 0.0110, (0.0, 0.0, 0.0050, 0.0050), 3)
    m.merge(extrude_z(cheek, (), 0.0900, 0.1850, chamfer_bevel(0.0012), "attachment").translated((0.0, -0.0030, 0.0)))
    butt = sh.rounded_rect(-0.0175, -0.1100, 0.0175, 0.0120, 0.0090, 3)
    m.merge(extrude_z(butt, (), 0.2080, 0.2250, chamfer_bevel(0.0015), "attachment"))
    pad = sh.rounded_rect(-0.0180, -0.1110, 0.0180, 0.0130, 0.0095, 3)
    m.merge(extrude_z(pad, (), 0.2250, 0.2420, chamfer_bevel(0.0030), "stock_pad"))
    for z in (0.0350, 0.0600):                                                             # sling loop
        m.merge(sh.box(0.0060, -0.0020, z - 0.0020, 0.0120, 0.0020, z + 0.0020, 0.0004, "steel_dark"))
    p.meshes["attachment"] = m
    return p


# --------------------------------------------------------------------------------------------------- charm
BLOCK = 0.0230                     # grass block edge
RING_Y = -0.0045                   # top ring centre below the mount point


def _ring(r_out, r_in, thick, centre, plane, material, seg=16):
    """Flat ring (washer) in the 'xy' or 'zy' plane."""
    m = sh.revolve([(r_in, -thick / 2), (r_out, -thick / 2), (r_out, thick / 2), (r_in, thick / 2),
                    (r_in, -thick / 2)], seg, material)
    if plane == "zy":
        m = sh.place(m, x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    return m.translated(centre)


def _block_mesh(edge, centre, material):
    """Cube with an atlas UV per face: top grass (u 0..1/3), sides (1/3..2/3), bottom dirt (2/3..1)."""
    h = edge / 2
    c = np.asarray(centre, float)
    m = Mesh()
    third = 1.0 / 3.0

    def face(pts, col):
        u0 = col * third
        m.add_poly([c + np.asarray(q, float) for q in pts], material,
                   uvs=[(u0, 1.0), (u0 + third, 1.0), (u0 + third, 0.0), (u0, 0.0)])

    # sides: corners bottom-left, bottom-right, top-right, top-left seen from outside (CCW)
    face([(-h, -h, h), (h, -h, h), (h, h, h), (-h, h, h)], 1)            # +Z
    face([(h, -h, -h), (-h, -h, -h), (-h, h, -h), (h, h, -h)], 1)        # -Z
    face([(h, -h, h), (h, -h, -h), (h, h, -h), (h, h, h)], 1)            # +X
    face([(-h, -h, -h), (-h, -h, h), (-h, h, h), (-h, h, -h)], 1)        # -X
    face([(-h, h, h), (h, h, h), (h, h, -h), (-h, h, -h)], 0)            # top
    face([(-h, -h, -h), (h, -h, -h), (h, -h, h), (-h, -h, h)], 2)        # bottom
    return m


def charm_grass():
    """A little grass block on a ring and two chain links. `pivot` (empty, at the mount point) is what the runtime
    swings; the mesh `charm` hangs under it along -Y."""
    p = Part()
    p.node("pivot", "attachment", (0.0, 0.0, 0.0))
    m = _ring(0.0042, 0.0029, 0.0011, (0.0, RING_Y, 0.0), "xy", "charm_metal")
    y = RING_Y - 0.0042
    for k, plane in enumerate(("zy", "xy")):                           # chain links, alternating planes
        link = _ring(0.0030, 0.0019, 0.0009, (0.0, 0.0, 0.0), plane, "charm_metal", 12)
        link = link.transformed(np.diag([1.0, 1.45, 1.0, 1.0]))
        m.merge(link.translated((0.0, y - 0.0030, 0.0)))
        y -= 0.0052
    m.merge(_cyl_axis(0.0011, 0.0030, (0.0, y - 0.0005, 0.0), "y", "charm_metal", 8))     # screw eye
    top = y - 0.0020
    m.merge(_block_mesh(BLOCK, (0.0, top - BLOCK / 2, 0.0), "charm_block"))
    p.node("charm", "pivot", (0.0, 0.0, 0.0), m)
    return p


def grass_texture(scale=8, seed=7):
    """64x... pixel art atlas: top grass | side (dirt with a grass fringe) | bottom dirt, each 16x16, upscaled
    `scale`x with nearest so the runtime's linear filter keeps it crisp. RGBA float."""
    rng = np.random.default_rng(seed)
    greens = np.array([(0.33, 0.58, 0.20), (0.40, 0.66, 0.25), (0.29, 0.51, 0.17), (0.46, 0.71, 0.30)])
    dirts = np.array([(0.47, 0.32, 0.20), (0.55, 0.38, 0.24), (0.40, 0.27, 0.16), (0.60, 0.43, 0.28)])
    top = greens[rng.choice(4, (16, 16), p=[0.42, 0.28, 0.2, 0.1])]
    side = dirts[rng.choice(4, (16, 16), p=[0.4, 0.3, 0.2, 0.1])]
    fringe = 3 + (rng.random(16) < 0.45).astype(int) + (rng.random(16) < 0.2).astype(int)
    for x in range(16):
        side[:fringe[x], x] = greens[rng.choice(4, fringe[x], p=[0.4, 0.3, 0.2, 0.1])]
    for _ in range(5):                                                   # pebbles
        side[rng.integers(6, 16), rng.integers(0, 16)] = (0.52, 0.50, 0.48)
    bottom = dirts[rng.choice(4, (16, 16), p=[0.4, 0.3, 0.2, 0.1])]
    atlas = np.concatenate([top, side, bottom], axis=1)
    atlas = np.repeat(np.repeat(atlas, scale, axis=0), scale, axis=1)
    out = np.ones((atlas.shape[0], atlas.shape[1], 4))
    out[:, :, :3] = atlas
    return out


# --------------------------------------------------------------------------------------------- materials
# name: (base rgba, metallic, roughness, extra) -- same names and looks as the weapon files where shared
MATERIALS = {
    "attachment": ((0.070, 0.070, 0.075, 1), 0.30, 0.60, {}),
    "steel_dark": ((0.040, 0.040, 0.044, 1), 0.70, 0.50, {}),
    # neutral, nearly clear: under a shader pack the model's own glass is drawn and a tint reads as a blue cast
    "glass": ((0.88, 0.90, 0.92, 0.10), 0.0, 0.05, {"alpha_mode": "BLEND", "double": True}),
    # optics' inner walls the eye looks along when aiming (tube bore, hood, eyecup): flat matte black, no reflections
    "interior": ((0.025, 0.025, 0.027, 1), 0.0, 0.95, {}),
    "reticle": ((1.0, 0.12, 0.08, 1), 0.0, 0.50, {"emissive": (1.0, 0.10, 0.06), "alpha_mode": "MASK",
                                                   "cutoff": 0.5, "double": True, "texture": "dot"}),
    "laser_lens": ((1.0, 0.25, 0.20, 1), 0.0, 0.20, {"emissive": (1.0, 0.12, 0.08)}),
    "suppressor": ((0.055, 0.056, 0.060, 1), 0.45, 0.55, {"texture": "brushed"}),
    "stock_pad": ((0.030, 0.030, 0.032, 1), 0.0, 0.92, {"texture": "stipple"}),
    "charm_metal": ((0.70, 0.70, 0.72, 1), 1.0, 0.30, {}),
    "charm_block": ((1.0, 1.0, 1.0, 1), 0.0, 0.85, {"texture": "grass"}),
    # the Thompson's wooden foregrip (section 35 `tommy_grip`): oiled walnut as on the gun (gen_tommy "wood")
    "wood_foregrip": ((0.30, 0.14, 0.065, 1), 0.0, 0.50, {"texture": "wood"}),
    # scopes (section 22): matte anodised body, rubber eyecups, the eyepiece (picture in picture) and objective glass
    "scope": ((0.034, 0.034, 0.037, 1), 0.45, 0.46, {"texture": "brushed"}),
    "scope_rubber": ((0.018, 0.018, 0.019, 1), 0.0, 0.90, {}),
    "lens": ((0.015, 0.017, 0.020, 0.92), 0.0, 0.04, {"alpha_mode": "BLEND"}),
    "lens_front": ((0.030, 0.035, 0.040, 0.85), 0.0, 0.03, {"alpha_mode": "BLEND"}),
}
# section 21: explicit regions of the scopes' materials (others: the slot's region by default)
MATERIAL_REGIONS = {"scope": "optic", "scope_rubber": "optic", "lens": "none", "lens_front": "none",
                    "interior": "none"}
TEXTURES = {"dot": lambda: tx.dot(32), "brushed": lambda: tx.brushed(256), "stipple": lambda: tx.stipple(256),
            "grass": grass_texture, "wood": lambda: tx.wood(256)}

# model -> builder (section 19 file names)
MODELS = {"mini_reddot": mini_reddot, "reddot": reddot, "laser": laser, "suppressor": suppressor,
          "tactical_stock": tactical_stock, "charm_grass": charm_grass, "scope_2x": scope_2x,
          "scope_sniper": scope_sniper, "holo": holo}
# section 28: more stocks, in a module of their own
from stock_models import MODELS as _STOCK_MODELS                       # noqa: E402
MODELS.update(_STOCK_MODELS)
# section 35: the rectangular red dot and the underbarrel slot's models
from underbarrel_models import MODELS as _UNDERBARREL_MODELS             # noqa: E402
MODELS.update(_UNDERBARREL_MODELS)
# scope -> reticle texture kind (textures/scope/reticle_<name>.png, section 22)
RETICLES = {"scope_2x": "2x", "scope_sniper": "sniper"}
