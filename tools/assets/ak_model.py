"""AK-47 (AKM pattern) geometry, VIEWMODEL_SPEC section 18. Design frame (metres): +X right, +Y up, +Z rearward;
bore axis on x = y = 0, z = 0 at the front face of the receiver. Real-world scale.

Every builder returns a meshkit.Mesh in the design frame; gen_ak47 moves meshes into node-local space."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0155                   # receiver half width
REC_Y0, REC_Y1 = -0.0400, 0.0150  # receiver bottom / top edge (the dust cover sits on it)
REC_Z1 = 0.2380                  # rear end (stock tang)
COVER_Z0, COVER_TOP = 0.0470, 0.0315
SIGHT_Y = 0.0500                 # sight line: rear notch shoulders / front post top (~50 mm over the bore, as an AKM)
REAR_NOTCH_Z = 0.0440            # rear face of the tangent leaf's notch plate
FRONT_POST_Z = -0.3340
BARREL_R = 0.0088
CROWN_Z = -0.3930
MUZZLE_Z = -0.4290               # front face of the slant brake (top edge)
GAS_Y, GAS_R = 0.0255, 0.0080
HG_Z0, HG_Z1 = -0.1960, -0.0110  # lower handguard front / rear
GRIP_TOP = np.array([0.0, REC_Y0, 0.1500])
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(20.0)), np.sin(np.radians(20.0))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(20.0)), -np.cos(np.radians(20.0))])
GRIP_LEN = 0.0950
MAG_ORIGIN = np.array([0.0, REC_Y0 + 0.0040, 0.0310])     # seated magazine pivot (top centre, inside the well)
MAG_FRONT_LIP = np.array([0.0, -0.0020, -0.0310])            # mag-local: the front lug that hooks in first
TRIGGER_PIVOT = np.array([0.0, -0.0330, 0.1010])
SELECTOR_PIVOT = np.array([REC_W, 0.0020, 0.2000])
BOLT_TRAVEL = 0.0860             # carrier / charging handle stroke
HANDLE_Z = 0.0780                # charging handle at rest (bolt forward)
EJECTION = np.array([REC_W + 0.0005, 0.0080, 0.0740])
RAIL_Z0, RAIL_Z1 = 0.0880, 0.2050  # optic rail on the dust cover (section 19: optics mount on it)
RAIL_H, RAIL_W = 0.0040, 0.0105    # rail height over the cover top, half width
OPTIC_Z = 0.1460                 # where an optic's base centre sits on the rail (eye relief behind the receiver)
HG_SIDE_X = 0.0225               # right face of the lower handguard at the laser rail


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def _rot_about_x(mesh, deg, pivot):
    return sh.rot_x(mesh, deg, pivot)


def _ring_xy(loop2d, z):
    loop2d = np.asarray(loop2d, float)
    return np.stack([loop2d[:, 0], loop2d[:, 1], np.full(len(loop2d), z)], 1)


def _loft_z(pairs, material):
    """Loft of (x, y) loops at increasing z (same vertex count)."""
    return sh.loft([_ring_xy(s, z) for s, z in pairs], material)


def _cyl_z(r, z0, z1, material, seg=18, y=0.0, x=0.0, chamfer=0.0):
    c = min(chamfer, 0.45 * abs(z1 - z0))
    prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)]
    return sh.revolve(prof, seg, material).translated((x, y, 0.0))


def _cyl_x(r, x0, x1, center_yz, material, seg=12):
    m = sh.revolve([(0.0, x0), (r, x0), (r, x1), (0.0, x1)], seg, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated((0.0, center_yz[0], center_yz[1]))


def _rivet(x, y, z, side, r=0.0019, material="steel"):
    """Dome rivet head on a receiver side (side = +1 right / -1 left)."""
    m = sh.revolve([(0.0, 0.0010), (r * 0.55, 0.0009), (r, 0.0002), (r, -0.0006), (0.0, -0.0006)], 10, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(side, 0, 0))
    return m.translated((x, y, z))


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """Stamped receiver with trunnion rivets, magazine release, trigger guard and the ejection port."""
    prof = [(0.0000, REC_Y0 + 0.004), (0.0040, REC_Y0), (0.2000, REC_Y0), (0.2160, REC_Y0 + 0.0040),
            (REC_Z1, REC_Y0 + 0.0110), (REC_Z1, REC_Y1 - 0.0020), (REC_Z1 - 0.0020, REC_Y1), (0.0000, REC_Y1)]
    m = sh.extrude_x(prof, (), REC_W, chamfer_bevel(0.0012), "steel")
    # front trunnion block: slightly proud, rounded front
    m.merge(extrude_z(sh.rounded_rect(-REC_W - 0.0006, REC_Y0 + 0.0040, REC_W + 0.0006, REC_Y1 - 0.0010, 0.0030, 2),
                      (), 0.0005, 0.0360, chamfer_bevel(0.0008), "steel"))
    # magazine dimples / rails pressed into the sides
    for s in (-1, 1):
        x0, x1 = sorted((s * REC_W, s * (REC_W + 0.0008)))
        m.merge(sh.box(x0, -0.0215, 0.0420, x1, -0.0135, 0.1950, 0.0003, "steel"))
        for z, y in ((0.0090, -0.0270), (0.0280, -0.0270), (0.0090, -0.0050), (0.0280, -0.0050),
                     (0.2120, -0.0300), (0.2240, -0.0130), (0.1250, -0.0300)):
            m.merge(_rivet(s * (REC_W + 0.0006), y, z, s))
    # magazine release paddle (behind the magazine) and the stamped trigger guard
    m.merge(sh.box(-0.0055, REC_Y0 - 0.0120, 0.0625, 0.0055, REC_Y0 + 0.0005, 0.0670, 0.0008, "steel"))
    m.merge(sh.box(-0.0060, REC_Y0 - 0.0150, 0.0595, 0.0060, REC_Y0 - 0.0105, 0.0680, 0.0010, "steel"))
    guard_out = [(0.0640, REC_Y0 + 0.0005), (0.0640, REC_Y0 - 0.0060), (0.0720, REC_Y0 - 0.0215),
                 (0.0860, REC_Y0 - 0.0285), (0.1160, REC_Y0 - 0.0290), (0.1370, REC_Y0 - 0.0230),
                 (0.1440, REC_Y0 - 0.0140), (0.1440, REC_Y0 + 0.0005)]
    guard_in = [(0.0725, REC_Y0 - 0.0010), (0.0780, REC_Y0 - 0.0175), (0.0890, REC_Y0 - 0.0240),
                (0.1140, REC_Y0 - 0.0245), (0.1310, REC_Y0 - 0.0195), (0.1380, REC_Y0 - 0.0120),
                (0.1380, REC_Y0 - 0.0010)]
    m.merge(sh.extrude_x(guard_out, [guard_in], 0.0055, chamfer_bevel(0.0008), "steel"))
    # ejection port on the right wall: dark recess with the carrier face behind it
    port = Mesh()
    x = REC_W + 0.00035
    port.add_poly([(x, -0.0030, 0.1020), (x, -0.0030, 0.0420), (x, REC_Y1 - 0.0010, 0.0420),
                   (x, REC_Y1 - 0.0010, 0.1020)], "steel_dark")
    m.merge(port)
    # selector cut-out stops on the right side (small stamped detents)
    for k, y in enumerate((-0.0050, -0.0110)):
        m.merge(sh.box(REC_W, y, 0.1640 + 0.006 * k, REC_W + 0.0007, y + 0.0030, 0.1690 + 0.006 * k, 0.0002,
                       "steel"))
    return m


def dust_cover_mesh():
    """Ribbed top cover with the recoil spring guide button at the rear."""
    def section(inflate=0.0):
        w, y0, top, r = REC_W + 0.0006 + inflate, REC_Y1 - 0.0050, COVER_TOP + inflate, 0.0110
        pts = [(-w, y0), (w, y0)]
        for a in np.linspace(0.0, np.pi / 2, 5):
            pts.append((w - r + r * np.cos(a), top - r + r * np.sin(a)))
        for a in np.linspace(np.pi / 2, np.pi, 5):
            pts.append((-w + r + r * np.cos(a), top - r + r * np.sin(a)))
        return np.array(pts)
    s0 = section()
    m = _loft_z([(sh.offset_loop(s0, 0.0012), COVER_Z0), (s0, COVER_Z0 + 0.0014), (s0, REC_Z1 - 0.0030),
                 (sh.offset_loop(s0, 0.0010), REC_Z1)], "steel")
    for z in (0.0880, 0.1350, 0.1820):                          # stamped transverse ribs
        m.merge(_loft_z([(section(0.0004), z - 0.0045), (section(0.0011), z - 0.0020),
                         (section(0.0011), z + 0.0020), (section(0.0004), z + 0.0045)], "steel"))
    m.merge(_cyl_z(0.0042, REC_Z1 - 0.0010, REC_Z1 + 0.0055, "steel", 14, y=0.0150, chamfer=0.0012))
    # low picatinny section riveted on top (optics sit on it; below the iron sight line)
    m.merge(sh.box(-RAIL_W, COVER_TOP - 0.0030, RAIL_Z0, RAIL_W, COVER_TOP + RAIL_H, RAIL_Z1, 0.0008, "steel_dark"))
    for k in range(int((RAIL_Z1 - RAIL_Z0 - 0.006) / 0.010)):                     # cross slots
        z = RAIL_Z0 + 0.008 + k * 0.010
        m.merge(sh.box(-RAIL_W - 0.0003, COVER_TOP + RAIL_H - 0.0016, z, RAIL_W + 0.0003, COVER_TOP + RAIL_H + 0.0003,
                       z + 0.0042, 0.0003, "steel_dark"))
    return m


# ------------------------------------------------------------------------- barrel, gas tube, sights
def barrel_mesh():
    """Barrel with the rear sight block, gas block, handguard retainers and the slant muzzle brake."""
    m = sh.revolve([(0.0, CROWN_Z), (BARREL_R * 0.9, CROWN_Z), (BARREL_R, CROWN_Z + 0.001),
                    (BARREL_R * 1.04, -0.250), (BARREL_R * 1.10, 0.0), (0.0, 0.0)], 18, "steel")
    # rear sight block over the front trunnion (carries the tangent leaf)
    rs_low = sh.rounded_rect(-0.0120, -0.0100, 0.0120, SIGHT_Y - 0.0185, (0.004, 0.004, 0.003, 0.003), 2)
    rs_top = sh.rounded_rect(-0.0105, -0.0100, 0.0105, SIGHT_Y - 0.0090, (0.004, 0.004, 0.003, 0.003), 2)
    m.merge(_loft_z([(sh.offset_loop(rs_low, 0.0010), -0.0420), (rs_low, -0.0400), (rs_top, -0.0150),
                     (rs_top, 0.0420), (sh.offset_loop(rs_top, 0.0010), 0.0440)], "steel"))
    # rear handguard retainer (with its lever) and the front retainer band
    for z0, z1 in ((HG_Z1, HG_Z1 + 0.0080), (HG_Z0 - 0.0085, HG_Z0)):
        band = sh.rounded_rect(-0.0262, -0.0330, 0.0262, 0.0140, (0.012, 0.012, 0.004, 0.004), 3)
        m.merge(extrude_z(band, (), z0, z1, chamfer_bevel(0.0008), "steel"))
    m.merge(sh.box(0.0255, -0.0120, HG_Z1 - 0.0010, 0.0285, 0.0080, HG_Z1 + 0.0070, 0.0006, "steel"))
    # gas block: collar on the barrel, gas port tower up to the tube
    gb = [(-0.0110, -0.0110), (0.0110, -0.0110), (0.0120, 0.0000), (0.0100, GAS_Y + 0.0065),
          (-0.0100, GAS_Y + 0.0065), (-0.0120, 0.0000)]
    m.merge(extrude_z(np.array(gb), (), -0.2380, HG_Z0 - 0.0085, chamfer_bevel(0.0012), "steel"))
    m.merge(_cyl_z(0.0060, -0.2440, -0.2380, "steel", 14, y=GAS_Y, chamfer=0.0010))    # gas plug
    return m


def muzzle_brake_mesh():
    """Slant brake on the crown (own node `muzzle_brake`: hidden while a suppressor is fitted). The front face is
    cut so the lower left lip runs further forward."""
    z_back = CROWN_Z + 0.0030
    m = sh.revolve([(0.0, z_back), (0.0108, z_back), (0.0108, z_back - 0.0010)], 18, "steel")
    ring = np.array([(0.0108 * np.cos(a), 0.0108 * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 18, endpoint=False)])
    def slant_z(x, y):
        return MUZZLE_Z + 0.0090 * (y / 0.0108) * 0.5 + 0.0040 * (x / 0.0108) - 0.0045
    front = np.array([(x, y, slant_z(x, y)) for x, y in ring])
    back = np.array([(x, y, z_back - 0.0010) for x, y in ring])
    m.merge(sh.loft([back, front], "steel"))
    inner = sh.revolve([(0.0045, MUZZLE_Z - 0.0100), (0.0045, MUZZLE_Z + 0.0060)], 12, "steel_dark", outward=False)
    m.merge(inner)
    return m


def gas_tube_mesh():
    """Gas tube from the gas block to the rear sight block (the wood upper handguard covers its middle)."""
    m = _cyl_z(GAS_R, -0.2380, -0.0400, "steel", 16, y=GAS_Y, chamfer=0.0006)
    m.merge(_cyl_z(GAS_R + 0.0012, -0.0460, -0.0380, "steel", 16, y=GAS_Y, chamfer=0.0005))
    m.merge(sh.box(0.0085, GAS_Y - 0.0030, -0.0400, 0.0125, GAS_Y + 0.0050, -0.0300, 0.0008, "steel"))  # lever
    return m


def front_sight_mesh():
    """Front sight block: collar on the barrel and the tower (the ears are front_sight_ears, the post sight_iron)."""
    collar = sh.rounded_rect(-0.0120, -0.0130, 0.0120, 0.0120, 0.0090, 3)
    m = extrude_z(collar, (), FRONT_POST_Z - 0.0200, FRONT_POST_Z + 0.0170, chamfer_bevel(0.0010), "steel_dark")
    tower = [(-0.0085, 0.0100), (0.0085, 0.0100), (0.0060, SIGHT_Y - 0.0125), (-0.0060, SIGHT_Y - 0.0125)]
    m.merge(extrude_z(np.array(tower), (), FRONT_POST_Z - 0.0120, FRONT_POST_Z + 0.0090, chamfer_bevel(0.0008),
                      "steel_dark"))
    m.merge(sh.box(-0.0050, -0.0210, FRONT_POST_Z - 0.0160, 0.0050, -0.0120, FRONT_POST_Z + 0.0100, 0.0010,
                   "steel_dark"))                      # bayonet lug
    return m


def front_sight_ears_mesh():
    """The two protective ears around the front post (node `front_sight_ears`: hidden with the irons while an optic is
    fitted, so nothing but the scene shows around the reticle)."""
    m = Mesh()
    for s in (-1, 1):
        ear = [(s * 0.0040, SIGHT_Y - 0.0145), (s * 0.0090, SIGHT_Y - 0.0145), (s * 0.0082, SIGHT_Y + 0.0045),
               (s * 0.0060, SIGHT_Y + 0.0060), (s * 0.0040, SIGHT_Y + 0.0050)]
        ear = ear if s > 0 else ear[::-1]
        m.merge(extrude_z(np.array(ear), (), FRONT_POST_Z - 0.0080, FRONT_POST_Z + 0.0070, chamfer_bevel(0.0005),
                          "steel_dark"))
    return m


def iron_sights_mesh():
    """Rear tangent sight, front post and ears together (icons / v1 layout)."""
    return rear_sight_mesh().merge(front_post_mesh()).merge(front_sight_ears_mesh())


def rear_sight_mesh():
    """Rear tangent leaf with slider and notch plate (node `sight_rear`; hidden while an optic is fitted)."""
    y0, y1, y2, y3 = SIGHT_Y - 0.0095, SIGHT_Y - 0.0060, SIGHT_Y - 0.0073, SIGHT_Y - 0.0037
    leaf = [(-0.0075, y0), (0.0075, y0), (0.0075, y1), (-0.0075, y1)]
    leaf_hi = [(-0.0075, y2), (0.0075, y2), (0.0075, y3), (-0.0075, y3)]
    m = _loft_z([(np.array(leaf), -0.0380), (np.array(leaf), -0.0300), (np.array(leaf_hi), REAR_NOTCH_Z - 0.0040),
                 (np.array(leaf_hi), REAR_NOTCH_Z - 0.0025)], "steel_dark")
    m.merge(sh.box(-0.0090, SIGHT_Y - 0.0103, -0.0060, 0.0090, SIGHT_Y - 0.0045, 0.0060, 0.0008,
                   "steel_dark"))                                                                  # range slider
    m.merge(sh.box(0.0090, SIGHT_Y - 0.0090, -0.0030, 0.0115, SIGHT_Y - 0.0060, 0.0030, 0.0005,
                   "steel_dark"))                                                                  # slider catch
    # notch plate: the sight line runs over its shoulders, the 3 mm deep U notch frames the front post
    notch = np.array([(-0.0085, SIGHT_Y - 0.0095), (0.0085, SIGHT_Y - 0.0095), (0.0085, SIGHT_Y - 0.0008),
                      (0.0070, SIGHT_Y), (0.0012, SIGHT_Y), (0.0012, SIGHT_Y - 0.0030), (-0.0012, SIGHT_Y - 0.0030),
                      (-0.0012, SIGHT_Y), (-0.0070, SIGHT_Y), (-0.0085, SIGHT_Y - 0.0008)])
    m.merge(extrude_z(notch, (), REAR_NOTCH_Z - 0.0030, REAR_NOTCH_Z, chamfer_bevel(0.0003), "steel_dark"))
    return m


def front_post_mesh():
    """Front sight post (node `sight_iron`; hidden with the rear sight while an optic is fitted)."""
    post = np.array([(-0.0011, SIGHT_Y - 0.0135), (0.0011, SIGHT_Y - 0.0135), (0.0011, SIGHT_Y - 0.0006),
                     (0.0006, SIGHT_Y), (-0.0006, SIGHT_Y), (-0.0011, SIGHT_Y - 0.0006)])
    return extrude_z(post, (), FRONT_POST_Z - 0.0012, FRONT_POST_Z + 0.0012, chamfer_bevel(0.0002), "steel_dark")


# ------------------------------------------------------------------------------------ furniture
def _hg_section(width, y0, y1, n=14):
    """Rounded handguard cross-section (CCW), flat top."""
    pts = [(-width * 0.80, y1), ]
    for a in np.linspace(np.pi, 2 * np.pi, n):
        pts.append((width * np.cos(a), y0 + (y1 - y0) * 0.55 + (y1 - y0) * 0.55 * np.sin(a)))
    pts.append((width * 0.80, y1))
    return np.array(pts)


def handguard_mesh():
    """Wood: lower handguard with palm swells + upper handguard around the gas tube."""
    zs = np.linspace(HG_Z0, HG_Z1, 11)
    rings = []
    for k, z in enumerate(zs):
        u = k / (len(zs) - 1)
        swell = 1.0 + 0.10 * np.sin(np.pi * np.clip((u - 0.10) / 0.80, 0, 1))
        sec = _hg_section(0.0225 * swell, -0.0315, 0.0100)
        if k in (0, len(zs) - 1):
            sec = sh.offset_loop(sec, 0.0012)
        rings.append(_ring_xy(sec, z))
    m = sh.loft(rings, "wood")
    up = []
    for k, z in enumerate(np.linspace(-0.1920, -0.0470, 6)):
        w = 0.0145
        sec = sh.superellipse(w, 0.0130, 2.6, 20, 0.0, GAS_Y + 0.0010)
        if k in (0, 5):
            sec = sh.offset_loop(sec, 0.0012)
        up.append(_ring_xy(sec, z))
    m.merge(sh.loft(up, "wood"))
    return m


def stock_mesh():
    """AKM fixed wooden stock with the steel butt plate."""
    z0, z1 = REC_Z1 - 0.0015, REC_Z1 + 0.2520
    top = lambda u: 0.0105 - 0.0300 * u ** 1.15
    bot = lambda u: (REC_Y0 + 0.0070) - 0.0870 * u ** 0.85 - 0.010 * u ** 3
    half = lambda u: 0.0150 + 0.0050 * u
    rings = []
    n = 10
    for k in range(n + 1):
        u = k / n
        y0, y1, w = bot(u), top(u), half(u)
        r = min(0.0080, 0.45 * w)
        sec = sh.rounded_rect(-w, y0, w, y1, r, 2)
        if k == 0:
            sec = sh.offset_loop(sec, 0.0010)
        rings.append(_ring_xy(sec, z0 + u * (z1 - z0)))
    m = sh.loft(rings, "wood")
    y0, y1, w = bot(1.0), top(1.0), half(1.0)
    plate = sh.rounded_rect(-w - 0.0005, y0 - 0.0005, w + 0.0005, y1 + 0.0005, min(0.0080, 0.45 * w), 2)
    m.merge(extrude_z(plate, (), z1 - 0.0005, z1 + 0.0040, chamfer_bevel(0.0008), "steel"))
    m.merge(sh.box(-0.0050, y1 - 0.0005, z1 - 0.0400, 0.0050, y1 + 0.0010, z1 - 0.0050, 0.0004, "steel"))  # tang
    return m


def pistol_grip_mesh():
    """Wood pistol grip, raked 20 degrees, with finger ribs on the front strap."""
    rings = []
    n = 9
    for k in range(n + 1):
        d = -0.0040 + (GRIP_LEN + 0.0040) * k / n
        c = grip_center(d)
        u = k / n
        depth = 0.0170 + 0.0020 * np.sin(np.pi * u)
        w = 0.0125 + 0.0012 * np.sin(np.pi * u)
        sec = sh.superellipse(w, depth, 2.4, 20)
        if k == n:
            sec = sh.offset_loop(sec, 0.0020)
        pts = np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * (-GRIP_FRONT) for p in sec])
        rings.append(pts)
    m = sh.loft(rings, "wood")
    for k in range(4):                                  # front-strap finger ribs
        d = 0.020 + k * 0.018
        c = grip_center(d) + GRIP_FRONT * 0.0175
        rib = sh.box(-0.0095, -0.0012, -0.0012, 0.0095, 0.0012, 0.0012, 0.0004, "wood")
        m.merge(sh.place(rib, origin=c, x_axis=(1, 0, 0), y_axis=-GRIP_AXIS, z_axis=-GRIP_FRONT))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_mesh():
    """Bolt carrier top and charging handle (right side); node origin at the rest position."""
    m = sh.box(-0.0115, -0.0020, 0.0420, 0.0135, 0.0130, 0.1400, 0.0015, "steel_dark")   # stays inside at full travel
    stem = [(HANDLE_Z - 0.0070, 0.0010), (HANDLE_Z + 0.0080, 0.0010), (HANDLE_Z + 0.0080, 0.0110),
            (HANDLE_Z - 0.0040, 0.0110)]
    m.merge(sh.extrude_x(np.array(stem), (), 0.0060, chamfer_bevel(0.0006), "steel_dark", x_center=REC_W + 0.0040))
    knob = sh.revolve([(0.0, 0.0), (0.0048, 0.0), (0.0055, 0.0010), (0.0055, 0.0100), (0.0042, 0.0125),
                       (0.0, 0.0130)], 14, "steel_dark")
    knob = sh.place(knob, x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    m.merge(knob.translated((REC_W + 0.0085, 0.0060, HANDLE_Z + 0.0010)))
    return m


def selector_mesh():
    """Large safety / selector lever on the right side (rotates about X at its rear pivot)."""
    prof = [(SELECTOR_PIVOT[2] + 0.0080, 0.0080), (SELECTOR_PIVOT[2] - 0.0050, 0.0110), (0.1150, 0.0100),
            (0.1000, 0.0130), (0.0920, 0.0110), (0.0920, -0.0010), (0.1020, 0.0010), (0.1150, 0.0030),
            (SELECTOR_PIVOT[2] - 0.0050, -0.0030), (SELECTOR_PIVOT[2] + 0.0080, -0.0040)]
    m = sh.extrude_x(np.array(prof), (), 0.0008, chamfer_bevel(0.0003), "steel_dark", x_center=REC_W + 0.0012)
    m.merge(_cyl_x(0.0055, REC_W, REC_W + 0.0030, (SELECTOR_PIVOT[1], SELECTOR_PIVOT[2]), "steel_dark", 14))
    return m


def trigger_mesh():
    blade = [(0.0880, REC_Y0 + 0.0040), (0.0950, REC_Y0 + 0.0040), (0.0955, REC_Y0 - 0.0040),
             (0.0945, REC_Y0 - 0.0110), (0.0915, REC_Y0 - 0.0170), (0.0880, REC_Y0 - 0.0205),
             (0.0860, REC_Y0 - 0.0195), (0.0880, REC_Y0 - 0.0150), (0.0895, REC_Y0 - 0.0090),
             (0.0895, REC_Y0 - 0.0020)]
    return sh.extrude_x(np.array(blade), (), 0.0030, round_bevel(0.0010, 2), "steel_dark")


# ------------------------------------------------------------------------------------ magazines
def _mag_spine(extended):
    """Centre line of the curved magazine in (z, y) of mag space (top at the origin), tangent angles."""
    length = 0.2050 if extended else 0.1650
    n = 14 if extended else 11
    pts, angs = [], []
    curve = 0.32 if extended else 0.42        # radius of the forward sweep
    straight = 0.030
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
    """Curved steel magazine (mag-local: +Y up into the well, -Z front) with ribs and the floor plate."""
    spine, angs = _mag_spine(extended)
    rings = []
    ribs = []
    for k, ((z, y), a) in enumerate(zip(spine, angs)):
        depth = 0.0300 + 0.0035 * (k / (len(spine) - 1))         # front-back half depth grows down the mag
        hw = 0.0130
        sec = sh.rounded_rect(-hw, -depth, hw, depth, 0.0040, 2)  # (x, along-front)
        fwd = np.array([0.0, np.sin(a), -np.cos(a)])             # front direction of this section
        c = np.array([0.0, y, z])
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * fwd for p in sec]))
        if 2 <= k <= len(spine) - 2 and k % 2 == 0:
            ribs.append((c, fwd, depth))
    m = sh.loft(rings, "mag")
    for c, fwd, depth in ribs:                                     # pressed side ribs
        for s in (-1, 1):
            rb = sh.box(-0.0006, -0.0012, -depth * 0.70, 0.0006, 0.0012, depth * 0.70, 0.0003, "mag")
            up = np.cross(fwd, (1.0, 0, 0))
            m.merge(sh.place(rb, origin=c + np.array([s * 0.0132, 0, 0]), x_axis=(1, 0, 0), y_axis=-up,
                             z_axis=-fwd))
    # top: feed lips and the front locking lug
    m.merge(sh.box(-0.0090, 0.0000, -0.0240, 0.0090, 0.0050, 0.0260, 0.0010, "mag"))
    m.merge(sh.box(-0.0050, -0.0040, -0.0345, 0.0050, 0.0030, -0.0295, 0.0008, "mag"))
    # floor plate
    (z, y), a = spine[-1], angs[-1]
    fwd = np.array([0.0, np.sin(a), -np.cos(a)])
    up = np.cross(fwd, (1.0, 0, 0))
    depth = 0.0335
    plate = sh.box(-0.0150, -0.0050, -depth - 0.0030, 0.0150, 0.0010, depth + 0.0010, 0.0015, "mag_base")
    m.merge(sh.place(plate, origin=np.array([0.0, y, z]), x_axis=(1, 0, 0), y_axis=-up, z_axis=-fwd))
    # top round (lacquered steel case, copper-jacketed bullet forward)
    m.merge(sh.revolve([(0.0, 0.0), (0.0056, 0.0), (0.0055, -0.0270), (0.0045, -0.0320), (0.0040, -0.0390),
                        (0.0030, -0.0480), (0.0010, -0.0560), (0.0, -0.0565)], 12, "brass")
            .translated((0.0, 0.0060, 0.0260)))
    return m


def shell_mesh(scale=1.15):
    """Spent 7.62x39 lacquered steel case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0193), (0.0050, 0.0193), (0.0057, 0.0185), (0.0057, 0.0175), (0.0050, 0.0170),
            (0.0050, 0.0155), (0.0056, 0.0150), (0.0054, -0.0090), (0.0046, -0.0120), (0.0044, -0.0193),
            (0.0040, -0.0193), (0.0040, -0.0170), (0.0, -0.0170)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 18, "brass")
