"""AWP (Accuracy International Arctic Warfare Magnum pattern) geometry, VIEWMODEL_SPEC section 23. Design frame
(metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0, z = 0 at the front face of the receiver.
Real-world scale (.338 Lapua Magnum, 26" fluted barrel, thumbhole stock).

Every builder returns a meshkit.Mesh in the design frame; gen_awp moves meshes into node-local space.

Materials (stable names, the texture sets of section 20 are keyed on them) and their regions (section 21):
receiver -> body, barrel -> barrel, bolt -> detail, steel_dark -> detail, stock -> stock, grip -> grip, pad -> stock,
mag / mag_base -> magazine, brass -> none."""
import numpy as np

import shapes as sh
from ak_model import _cyl_x, _cyl_z, _loft_z, _ring_xy
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

MATERIAL_REGIONS = {"receiver": "body", "barrel": "barrel", "bolt": "detail", "steel_dark": "detail",
                    "stock": "stock", "grip": "grip", "pad": "stock", "mag": "magazine", "mag_base": "magazine",
                    "brass": "none"}

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0170                    # receiver half width
REC_Y0, REC_Y1 = -0.0240, 0.0190  # receiver bottom / top (the scope rail sits on top)
REC_Z1 = 0.2450                   # rear end of the receiver (the bolt shroud sticks out behind it)
RAIL_H, RAIL_W = 0.0085, 0.0105   # picatinny height over the receiver top, half width
RAIL_Z0, RAIL_Z1 = -0.0300, 0.2350
OPTIC_Z = 0.1550                  # optic base centre on the rail: far enough back that a red dot's eye point clears the action
BARREL_R0, BARREL_R1 = 0.0128, 0.0108   # barrel radius at the receiver / at the crown
CROWN_Z = -0.6600
BRAKE_R, BRAKE_LEN = 0.0140, 0.0760
MUZZLE_Z = CROWN_Z - BRAKE_LEN    # front face of the muzzle brake
FLUTE_Z0, FLUTE_Z1 = -0.5300, -0.1000   # fluted section of the barrel
BOLT_R = 0.0095                   # bolt body radius
BOLT_TRAVEL = 0.1050              # bolt stroke (long action)
BOLT_LIFT = 68.0                  # degrees the handle turns up to unlock (about +Z: right side up)
HANDLE_Z = 0.2080                 # bolt handle root (bolt closed)
HANDLE_DROP = 24.0                # the stem points right and this many degrees down at rest
HANDLE_LEN = 0.0560               # stem length from the bolt axis to the knob centre
KNOB_R = 0.0098
EJECTION = np.array([REC_W + 0.0005, 0.0060, 0.0820])
PORT_Z0, PORT_Z1 = 0.0420, 0.1320
STOCK_W = 0.0240                  # chassis / forend half width
FE_Z0 = -0.3300                   # forend nose
FE_Y0, FE_Y1 = -0.0490, 0.0040    # forend bottom / top of its side walls
CH_Y0 = -0.0660                   # chassis bottom line under the action
CH_TOP = -0.0130                  # top of the chassis side walls (they wrap the lower receiver)
GRIP_TOP = np.array([0.0, CH_Y0 + 0.0080, 0.2300])    # front strap meets the chassis behind the trigger guard
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(16.0)), np.sin(np.radians(16.0))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(16.0)), -np.cos(np.radians(16.0))])
GRIP_LEN = 0.1000
MAG_ORIGIN = np.array([0.0, REC_Y0 + 0.0050, 0.0700])  # seated magazine pivot (top centre, inside the well)
MAG_H, MAG_HL, MAG_HW = 0.1040, 0.0520, 0.0150       # magazine height, half length, half width
TRIGGER_PIVOT = np.array([0.0, CH_Y0 + 0.0060, 0.1780])
RELEASE_Z = 0.1300                # magazine release paddle in front of the trigger guard
BUTT_Z = 0.6500                   # rear face of the stock (the pad goes on behind it)
COMB_Y = 0.0300                   # top of the comb
CHEEK_Y = 0.0420                  # top of the raised cheek piece
CHEEK_Z0 = 0.3950                 # the comb rises behind the bolt's stroke (shroud reaches z 0.38 when open)
CHARM_POINT = np.array([-STOCK_W - 0.0012, CH_Y0 + 0.0150, 0.1320])  # sling loop on the left of the chassis


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """Round-topped action body with the ejection port, bolt release, recoil lug and the scope rail."""
    sec = sh.rounded_rect(-REC_W, REC_Y0, REC_W, REC_Y1, (0.0030, 0.0030, 0.0110, 0.0110), 3)
    m = extrude_z(sec, (), 0.0000, REC_Z1, chamfer_bevel(0.0012), "receiver")
    # recoil lug / barrel nut ring at the front face
    m.merge(_cyl_z(0.0172, -0.0160, 0.0010, "receiver", 24, chamfer=0.0012))
    # ejection port on the right wall: dark recess (the bolt body shows in it when open)
    port = Mesh()
    x = REC_W + 0.00035
    port.add_poly([(x, -0.0060, PORT_Z1), (x, -0.0060, PORT_Z0), (x, REC_Y1 - 0.0040, PORT_Z0),
                   (x, REC_Y1 - 0.0040, PORT_Z1)], "steel_dark")
    m.merge(port)
    # bolt release catch on the left rear, bolt stop slot
    m.merge(sh.box(-REC_W - 0.0012, 0.0010, 0.1980, -REC_W + 0.0002, 0.0070, 0.2280, 0.0004, "steel_dark"))
    # handle slot at the rear right (the stem drops into it when the bolt closes)
    slot = Mesh()
    slot.add_poly([(x, -0.0095, 0.2300), (x, -0.0095, 0.1880), (x, -0.0030, 0.1880), (x, -0.0030, 0.2300)],
                  "steel_dark")
    m.merge(slot)
    m.merge(rail_mesh())
    # screws holding the action in the chassis (both sides)
    for s in (-1, 1):
        for z in (0.0240, 0.2150):
            hd = sh.revolve([(0.0, 0.0008), (0.0024, 0.0008), (0.0027, 0.0), (0.0, 0.0)], 12, "steel_dark", precision=True)
            hd = sh.place(hd, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(s, 0, 0))
            m.merge(hd.translated((s * (STOCK_W + 0.0001), -0.0350, z)))
    return m


def rail_mesh():
    """Picatinny rail on the receiver (optics mount on it), running a little forward over the barrel."""
    top = REC_Y1 + RAIL_H
    m = sh.box(-RAIL_W, REC_Y1 - 0.0030, RAIL_Z0, RAIL_W, top, RAIL_Z1, 0.0008, "receiver")
    for k in range(int((RAIL_Z1 - RAIL_Z0 - 0.008) / 0.010)):                    # cross slots
        z = RAIL_Z0 + 0.008 + k * 0.010
        m.merge(sh.box(-RAIL_W - 0.0003, top - 0.0022, z, RAIL_W + 0.0003, top + 0.0003, z + 0.0050, 0.0003,
                       "receiver"))
    # riser block under the front of the rail where it leaves the receiver
    m.merge(sh.box(-0.0090, 0.0040, RAIL_Z0 + 0.002, 0.0090, REC_Y1, 0.0020, 0.0008, "receiver"))
    return m


# ---------------------------------------------------------------------------------- barrel, brake
def _flute_section(r, depth, n=6, count=72, width=0.42):
    """CCW (x, y) barrel section of radius r with n rounded flutes `depth` deep (width = share of the pitch)."""
    a = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / n
    ph = (a * n / (2 * np.pi)) % 1.0                       # 0..1 inside each flute pitch
    u = np.clip((np.abs(ph - 0.5) - (0.5 - width / 2)) / (width / 2), -1, 0)   # -1 at the flute centre
    rr = r - depth * np.sqrt(np.clip(1 - (1 + u) ** 2, 0, 1)) * (u > -1.0001) * (u < 0)
    return np.stack([rr * np.cos(a), rr * np.sin(a)], 1)


def barrel_mesh():
    """Fluted heavy barrel from the receiver to the crown (the brake is its own node)."""
    def r_at(z):
        return BARREL_R0 + (BARREL_R1 - BARREL_R0) * (z / CROWN_Z)
    rings = []
    zs = [0.0, -0.0300, FLUTE_Z1 + 0.012, FLUTE_Z1, FLUTE_Z1 - 0.008]
    zs += list(np.linspace(FLUTE_Z1 - 0.030, FLUTE_Z0 + 0.030, 6))
    zs += [FLUTE_Z0 + 0.008, FLUTE_Z0, FLUTE_Z0 - 0.012, CROWN_Z + 0.004, CROWN_Z]
    for z in zs:
        inside = FLUTE_Z0 + 0.006 <= z <= FLUTE_Z1 - 0.006
        edge = (FLUTE_Z0 <= z <= FLUTE_Z1) and not inside
        depth = 0.0026 if inside else (0.0011 if edge else 0.0)
        r = r_at(z) * (0.93 if z == CROWN_Z else 1.0)
        rings.append(_ring_xy(_flute_section(r, depth), z))
    m = sh.loft(rings, "barrel")
    m.merge(sh.revolve([(0.0040, CROWN_Z + 0.0005), (0.0040, CROWN_Z - 0.0001)], 12, "steel_dark", outward=False, precision=True))
    return m


def muzzle_brake_mesh():
    """Two-port muzzle brake screwed onto the crown (node `muzzle_brake`: hidden while a suppressor is fitted)."""
    z1, z0 = CROWN_Z + 0.0020, MUZZLE_Z
    m = sh.revolve([(0.0, z1), (BRAKE_R - 0.0012, z1), (BRAKE_R, z1 - 0.0012), (BRAKE_R, z0 + 0.0020),
                    (BRAKE_R - 0.0020, z0), (0.0060, z0), (0.0060, z0 + 0.010), (0.0, z0 + 0.010)], 24, "barrel", precision=True)
    # side ports: dark recesses with the baffle walls between them
    for s in (-1, 1):
        for k in range(3):
            zc = z0 + 0.016 + k * 0.019
            port = Mesh()
            x = s * (BRAKE_R + 0.0002)
            pts = [(x, -0.0072, zc + 0.0060), (x, -0.0072, zc - 0.0060), (x, 0.0072, zc - 0.0060),
                   (x, 0.0072, zc + 0.0060)]
            port.add_poly(pts if s > 0 else pts[::-1], "steel_dark")
            m.merge(port)
    # top / bottom flats with the clamp screw
    m.merge(sh.box(-0.0050, BRAKE_R - 0.0010, z1 - 0.022, 0.0050, BRAKE_R + 0.0004, z1 - 0.006, 0.0004, "barrel"))
    return m


# ------------------------------------------------------------------------------------------- stock
def _forend_section(w, y0, y1, ch_w=0.0165, ch_y=-0.0110, r=0.0100, seg=4):
    """CCW (x, y) forend section: rounded bottom, flat top walls with the free-float barrel channel."""
    pts = []
    for a in np.linspace(np.pi, 1.5 * np.pi, seg + 1):                        # bottom left
        pts.append((-w + r + r * np.cos(a), y0 + r + r * np.sin(a)))
    for a in np.linspace(1.5 * np.pi, 2 * np.pi, seg + 1):                    # bottom right
        pts.append((w - r + r * np.cos(a), y0 + r + r * np.sin(a)))
    pts += [(w, y1 - 0.0025), (w - 0.0025, y1), (ch_w + 0.0012, y1)]
    for a in np.linspace(0.0, -np.pi, 9):                                      # channel (opens upwards)
        pts.append((ch_w * np.cos(a), ch_y + 0.6 * ch_w * np.sin(a) + 0.006))
    pts += [(-ch_w - 0.0012, y1), (-w + 0.0025, y1), (-w, y1 - 0.0025)]
    return np.array(pts)


def forend_mesh():
    """Forend of the chassis stock around the free-floated barrel, with the accessory rail and sling stud."""
    sec = _forend_section(STOCK_W, FE_Y0, FE_Y1)
    nose = _forend_section(STOCK_W - 0.0040, FE_Y0 + 0.0040, FE_Y1 - 0.0030)
    pairs = [(sh.offset_loop(nose, 0.0012), FE_Z0), (nose, FE_Z0 + 0.0020), (sec, FE_Z0 + 0.0180),
             (sec, -0.0200), (sec, 0.0000)]
    m = _loft_z(pairs, "stock")
    # accessory rail under the front of the forend (bipod / sling)
    m.merge(sh.box(-0.0090, FE_Y0 - 0.0060, FE_Z0 + 0.0250, 0.0090, FE_Y0 + 0.0010, FE_Z0 + 0.1250, 0.0006, "steel_dark"))
    for k in range(9):
        z = FE_Z0 + 0.0300 + k * 0.0105
        m.merge(sh.box(-0.0093, FE_Y0 - 0.0063, z, 0.0093, FE_Y0 - 0.0040, z + 0.0050, 0.0003, "steel_dark"))
    # grip panels on the sides of the forend (shallow raised pads)
    for s in (-1, 1):
        x0, x1 = sorted((s * STOCK_W, s * (STOCK_W + 0.0008)))
        m.merge(sh.box(x0, -0.0400, -0.2700, x1, -0.0080, -0.0400, 0.0003, "stock"))
    return m


def chassis_mesh():
    """Middle of the stock under the action: magazine well, trigger guard and the magazine release paddle."""
    # the side walls stop under the bolt handle (the stem passes over them at rest)
    sec = sh.rounded_rect(-STOCK_W, CH_Y0, STOCK_W, CH_TOP, (0.0060, 0.0060, 0.0020, 0.0020), 2)
    m = extrude_z(sec, (), 0.0000, GRIP_TOP[2] + 0.0060, chamfer_bevel(0.0010), "stock")
    # magazine well lips (the magazine slides up through them)
    for s in (-1, 1):
        x0, x1 = sorted((s * (MAG_HW + 0.0010), s * (MAG_HW + 0.0045)))
        m.merge(sh.box(x0, CH_Y0 - 0.0060, MAG_ORIGIN[2] - MAG_HL - 0.004, x1, CH_Y0 + 0.0005,
                       MAG_ORIGIN[2] + MAG_HL + 0.004, 0.0008, "stock"))
    # trigger guard loop
    gz0, gz1 = 0.1420, GRIP_TOP[2] + 0.0030
    guard_out = [(gz0, CH_Y0 + 0.0005), (gz0, CH_Y0 - 0.0100), (gz0 + 0.0120, CH_Y0 - 0.0300),
                 (gz0 + 0.0400, CH_Y0 - 0.0340), (gz1 - 0.0100, CH_Y0 - 0.0300), (gz1, CH_Y0 - 0.0180),
                 (gz1, CH_Y0 + 0.0005)]
    guard_in = [(gz0 + 0.0060, CH_Y0 - 0.0010), (gz0 + 0.0060, CH_Y0 - 0.0090), (gz0 + 0.0160, CH_Y0 - 0.0250),
                (gz0 + 0.0390, CH_Y0 - 0.0280), (gz1 - 0.0120, CH_Y0 - 0.0250), (gz1 - 0.0060, CH_Y0 - 0.0160),
                (gz1 - 0.0060, CH_Y0 - 0.0010)]
    m.merge(sh.extrude_x(np.array(guard_out), [np.array(guard_in)], 0.0070, chamfer_bevel(0.0010), "stock"))
    # magazine release paddle in front of the guard
    m.merge(sh.box(-0.0060, CH_Y0 - 0.0110, RELEASE_Z - 0.0030, 0.0060, CH_Y0 + 0.0005, RELEASE_Z + 0.0030, 0.0008,
                   "steel_dark"))
    # sling loop on the left side (the charm hangs from it)
    loop = sh.revolve([(0.0018, -0.0010), (0.0034, -0.0010), (0.0034, 0.0010), (0.0018, 0.0010), (0.0018, -0.0010)],
                      12, "steel_dark", precision=True)
    loop = sh.place(loop, x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    m.merge(loop.translated(CHARM_POINT + np.array([-0.0012, 0.0030, 0.0])))
    return m


def butt_mesh():
    """Thumbhole buttstock: upper body with the comb sloping down to the toe, the lower bar joining the bottom of
    the grip to the toe (closing the thumbhole), the raised cheek piece and the rubber butt pad."""
    z0 = GRIP_TOP[2] - 0.0040
    # the wrist stays low behind the receiver: the bolt shroud travels back over it (comb rises behind the stroke)
    upper = np.array([(z0, CH_TOP), (z0, -0.0420), (0.3200, -0.0480), (0.3900, -0.0620), (0.4600, -0.0930),
                      (0.5300, -0.1330), (0.5600, -0.1440), (BUTT_Z, -0.1440), (BUTT_Z, 0.0200),
                      (BUTT_Z - 0.0080, COMB_Y), (CHEEK_Z0, COMB_Y), (CHEEK_Z0 - 0.0150, 0.0200),
                      (CHEEK_Z0 - 0.0350, -0.0100), (CHEEK_Z0 - 0.0700, -0.0160), (0.2700, -0.0160)])
    m = sh.extrude_x(upper, (), 0.0210, round_bevel(0.0040, 2), "stock")
    # lower bar: bottom of the grip to the toe
    bot = grip_center(GRIP_LEN)
    bar = np.array([(bot[2] - 0.0140, bot[1] + 0.0180), (bot[2] - 0.0090, bot[1] - 0.0100),
                    (bot[2] + 0.0250, bot[1] - 0.0170), (0.5600, -0.1570), (0.5700, -0.1280),
                    (bot[2] + 0.0380, bot[1] + 0.0110)])
    m.merge(sh.extrude_x(bar, (), 0.0160, round_bevel(0.0045, 2), "stock"))
    # raised cheek piece on two posts
    c0, c1 = CHEEK_Z0 + 0.0020, CHEEK_Z0 + 0.1300
    cheek = np.array([(c0, COMB_Y - 0.0040), (c1, COMB_Y - 0.0040), (c1, CHEEK_Y - 0.0030),
                      (c1 - 0.0080, CHEEK_Y), (c0 + 0.0130, CHEEK_Y), (c0, CHEEK_Y - 0.0040)])
    m.merge(sh.extrude_x(cheek, (), 0.0190, round_bevel(0.0030, 2), "stock"))
    for z in (c0 + 0.0250, c1 - 0.0250):                                           # cheek piece posts
        post = sh.revolve([(0.0, -0.0040), (0.0030, -0.0040), (0.0030, 0.0040), (0.0, 0.0040)], 10, "steel_dark", precision=True)
        post = sh.place(post, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))
        m.merge(post.translated((-0.0150, COMB_Y - 0.0010, z)))
        m.merge(post.translated((0.0150, COMB_Y - 0.0010, z)))
    # adjuster wheel on the left of the cheek piece
    m.merge(_cyl_x(0.0070, -0.0260, -0.0190, (COMB_Y + 0.0020, (c0 + c1) / 2), "steel_dark", 14))
    return m


def pad_mesh():
    """Rubber butt pad behind the stock."""
    prof = np.array([(BUTT_Z - 0.0005, -0.1460), (BUTT_Z + 0.0200, -0.1450), (BUTT_Z + 0.0230, -0.1380),
                     (BUTT_Z + 0.0230, 0.0170), (BUTT_Z + 0.0200, 0.0250), (BUTT_Z - 0.0005, 0.0270)])
    m = sh.extrude_x(prof, (), 0.0225, round_bevel(0.0050, 2), "pad")
    for k in range(5):                                                             # horizontal ribs on the face
        y = -0.1200 + k * 0.0300
        m.merge(sh.box(-0.0180, y, BUTT_Z + 0.0228, 0.0180, y + 0.0040, BUTT_Z + 0.0242, 0.0005, "pad"))
    return m


def grip_mesh():
    """Thumbhole pistol grip (stippled), raked 16 degrees, finger grooves on the front strap; its bottom runs into
    the lower bar of the stock."""
    rings = []
    n = 10
    for k in range(n + 1):
        d = -0.0060 + (GRIP_LEN + 0.0120) * k / n
        c = grip_center(d)
        u = k / n
        depth = 0.0195 + 0.0020 * np.sin(np.pi * u)
        w = 0.0145 + 0.0010 * np.sin(np.pi * u)
        sec = sh.superellipse(w, depth, 2.5, 20)
        pts = np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * (-GRIP_FRONT) for p in sec])
        rings.append(pts)
    m = sh.loft(rings, "grip")
    for k in range(3):                                                             # finger grooves (ridges)
        d = 0.0300 + k * 0.0230
        c = grip_center(d) + GRIP_FRONT * 0.0195
        rib = sh.box(-0.0115, -0.0016, -0.0014, 0.0115, 0.0016, 0.0014, 0.0005, "grip")
        m.merge(sh.place(rib, origin=c, x_axis=(1, 0, 0), y_axis=-GRIP_AXIS, z_axis=-GRIP_FRONT))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_shroud_mesh():
    """Non-rotating bolt shroud behind the receiver with the cocking indicator (node `bolt`, bolt space at rest)."""
    m = sh.revolve([(0.0, REC_Z1 - 0.0020), (0.0120, REC_Z1 - 0.0020), (0.0128, REC_Z1 + 0.0020),
                    (0.0128, REC_Z1 + 0.0240), (0.0100, REC_Z1 + 0.0300), (0.0, REC_Z1 + 0.0300)], 20, "bolt", precision=True)
    m.merge(_cyl_z(0.0026, REC_Z1 + 0.0290, REC_Z1 + 0.0360, "steel_dark", 10, y=0.0, chamfer=0.0008))   # indicator
    m.merge(sh.box(-0.0030, 0.0110, REC_Z1 + 0.0040, 0.0030, 0.0150, REC_Z1 + 0.0220, 0.0006, "bolt"))   # top fin
    return m


def bolt_body_mesh():
    """Rotating bolt body with its locking lugs, the handle stem and the ball knob (node `bolt_handle`, bolt space at
    rest: the handle points right and HANDLE_DROP degrees down)."""
    m = _cyl_z(BOLT_R, 0.0080, REC_Z1 - 0.0010, "bolt", 18, chamfer=0.0010)
    for s in (-1, 1):                                                              # locking lugs at the front
        m.merge(sh.box(-0.0035, s * BOLT_R - 0.0025, 0.0080, 0.0035, s * BOLT_R + 0.0025, 0.0300, 0.0006, "bolt"))
    for k in range(4):                                                             # spiral flutes on the body
        z = 0.0600 + k * 0.0300
        m.merge(sh.revolve([(BOLT_R - 0.0004, z), (BOLT_R + 0.0001, z + 0.0020), (BOLT_R + 0.0001, z + 0.0100),
                            (BOLT_R - 0.0004, z + 0.0120)], 18, "steel_dark", precision=True))
    # handle: stem from the body out to the right, swept back a little, then the knob
    a = np.radians(-HANDLE_DROP)
    d = np.array([np.cos(a), np.sin(a), 0.10])
    d /= np.linalg.norm(d)
    root = np.array([BOLT_R * np.cos(a), BOLT_R * np.sin(a), HANDLE_Z])
    knob = knob_rest()
    stem_len = float(np.linalg.norm(knob - root))
    stem = sh.revolve([(0.0, 0.0), (0.0048, 0.0), (0.0040, 0.0080), (0.0034, stem_len - 0.0060),
                       (0.0040, stem_len), (0.0, stem_len)], 12, "bolt", precision=True)
    z_ax = (knob - root) / stem_len
    x_ax = np.cross((0.0, 0.0, 1.0), z_ax)
    x_ax /= np.linalg.norm(x_ax)
    y_ax = np.cross(z_ax, x_ax)
    m.merge(sh.place(stem, origin=root, x_axis=x_ax, y_axis=y_ax, z_axis=z_ax))
    ball = sh.revolve([(0.0, -KNOB_R)] + [(KNOB_R * np.sin(t), -KNOB_R * np.cos(t)) for t in np.linspace(0.3, 2.84, 8)]
                      + [(0.0, KNOB_R)], 14, "bolt", precision=True)
    m.merge(sh.place(ball, origin=knob, x_axis=x_ax, y_axis=y_ax, z_axis=z_ax))
    return m


def knob_rest():
    """Centre of the bolt knob in bolt space at rest (handle down)."""
    a = np.radians(-HANDLE_DROP)
    d = np.array([np.cos(a), np.sin(a), 0.10])
    d /= np.linalg.norm(d)
    root = np.array([BOLT_R * np.cos(a), BOLT_R * np.sin(a), HANDLE_Z])
    return root + d * (HANDLE_LEN - BOLT_R)


def trigger_mesh():
    """Trigger blade (rotates about X at its pivot)."""
    p = TRIGGER_PIVOT
    blade = [(p[2] - 0.0040, p[1] + 0.0040), (p[2] + 0.0030, p[1] + 0.0040), (p[2] + 0.0035, p[1] - 0.0040),
             (p[2] + 0.0025, p[1] - 0.0110), (p[2] - 0.0005, p[1] - 0.0170), (p[2] - 0.0040, p[1] - 0.0200),
             (p[2] - 0.0060, p[1] - 0.0190), (p[2] - 0.0040, p[1] - 0.0150), (p[2] - 0.0025, p[1] - 0.0090),
             (p[2] - 0.0025, p[1] - 0.0020)]
    return sh.extrude_x(np.array(blade), (), 0.0030, round_bevel(0.0010, 2), "steel_dark")


# --------------------------------------------------------------------------------------- magazine
def mag_mesh():
    """Detachable 10-round box magazine (mag-local: top centre at the origin, +Y up into the well, -Z front) with the
    top round showing in the feed lips."""
    sec = sh.rounded_rect(-MAG_HW, -MAG_HL, MAG_HW, MAG_HL, 0.0030, 2)        # (x, z) section
    m = _mag_prism(sec, -MAG_H + 0.0080, 0.0, "mag")
    for s in (-1, 1):                                                              # pressed side ribs
        x0, x1 = sorted((s * MAG_HW, s * (MAG_HW + 0.0007)))
        for z in (-0.0300, 0.0000, 0.0300):
            m.merge(sh.box(x0, -MAG_H + 0.0200, z - 0.0040, x1, -0.0250, z + 0.0040, 0.0002, "mag"))
    # floor plate, a little proud all round
    plate = sh.rounded_rect(-MAG_HW - 0.0015, -MAG_HL - 0.0020, MAG_HW + 0.0015, MAG_HL + 0.0020, 0.0040, 2)
    m.merge(_mag_prism(plate, -MAG_H, -MAG_H + 0.0080, "mag_base"))
    m.merge(sh.box(-0.0060, -MAG_H - 0.0015, -0.0300, 0.0060, -MAG_H + 0.0005, 0.0300, 0.0005, "mag_base"))  # pull tab
    # feed lips and the top round (.338 Lapua: case forward, bullet towards the front)
    m.merge(sh.box(-MAG_HW + 0.0010, -0.0010, -MAG_HL + 0.0040, -0.0040, 0.0030, MAG_HL - 0.0060, 0.0005, "mag"))
    m.merge(sh.box(0.0040, -0.0010, -MAG_HL + 0.0040, MAG_HW - 0.0010, 0.0030, MAG_HL - 0.0060, 0.0005, "mag"))
    rnd = sh.revolve([(0.0, 0.0), (0.0074, 0.0), (0.0074, -0.0050), (0.0072, -0.0560), (0.0058, -0.0640),
                      (0.0044, -0.0700), (0.0044, -0.0740), (0.0035, -0.0820), (0.0018, -0.0905), (0.0, -0.0930)],
                     14, "brass", precision=True)
    m.merge(rnd.translated((0.0, 0.0030, MAG_HL - 0.0050)))
    return m


def _mag_prism(sec_xz, y0, y1, material):
    """Prism of an (x, z) outline from y0 to y1 (mag-local)."""
    rings = [np.stack([sec_xz[:, 0], np.full(len(sec_xz), y), sec_xz[:, 1]], 1) for y in (y0, y1)]
    return sh.loft(rings, material)


def shell_mesh(scale=1.0):
    """Spent .338 Lapua Magnum case (material `brass`) along Z, mouth towards -Z."""
    prof = [(0.0, 0.0345), (0.0068, 0.0345), (0.0075, 0.0338), (0.0075, 0.0325), (0.0066, 0.0318),
            (0.0066, 0.0300), (0.0074, 0.0294), (0.0072, -0.0170), (0.0058, -0.0250), (0.0046, -0.0285),
            (0.0046, -0.0345), (0.0040, -0.0345), (0.0040, -0.0320), (0.0, -0.0320)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 18, "brass", precision=True)
