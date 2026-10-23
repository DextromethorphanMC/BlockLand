"""RPD light machine gun geometry, VIEWMODEL_SPEC section 33. Design frame (metres): +X right, +Y up, +Z rearward;
bore axis on x = y = 0, z = 0 at the front face of the receiver. Real-world scale (overall ~1.04 m, barrel ~0.52 m).

An original procedural model built like the AK's (ak_model.py helpers): a long heavy barrel with the gas cylinder
under it, a wooden handguard, a stamped receiver with a hinged feed cover (front hinge, lifts at the rear), a right-side
charging handle, a wooden pistol grip and stock, a round belt drum hung under the receiver (100 / 150 rounds; its
axis along the bore, so the coiled belt's rounds already point forward) and the belt itself: linked cartridges
leaving the drum's mouth at its upper left and arcing up into the feed tray (separate pieces, rpd_rig.belt_chain). Every builder returns a
meshkit.Mesh in the design frame; gen_rpd moves meshes into node-local space."""
import numpy as np

import shapes as sh
from ak_model import _cyl_x, _cyl_z, _loft_z, _rivet, _ring_xy, _hg_section
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0165                   # receiver half width
REC_Y0, REC_Y1 = -0.0360, 0.0180  # receiver bottom / top edge
REC_Z1 = 0.2620                  # rear end (stock tang)
COVER_Z0, COVER_Z1 = 0.0120, 0.1500     # feed cover front (hinge) / rear (latch)
COVER_TOP = 0.0340
COVER_HINGE = np.array([0.0, 0.0250, COVER_Z0])
COVER_OPEN = -60.0               # degrees about X at the hinge: the rear lifts
SIGHT_Y = 0.0500                 # sight line over the bore (rear notch shoulders / front post top)
REAR_NOTCH_Z = -0.0040
FRONT_POST_Z = -0.4520
BARREL_R = 0.0098
CROWN_Z = -0.5020
MUZZLE_Z = -0.5320
GAS_Y, GAS_R = -0.0215, 0.0085
GAS_Z0, GAS_Z1 = -0.3420, -0.0500  # gas block front / tube rear
HG_Z0, HG_Z1 = -0.2050, -0.0300  # handguard front / rear
GRIP_TOP = np.array([0.0, REC_Y0, 0.1880])
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(18.0)), np.sin(np.radians(18.0))])
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(18.0)), -np.cos(np.radians(18.0))])
GRIP_LEN = 0.0950
TRIGGER_PIVOT = np.array([0.0, -0.0300, 0.1420])
MAG_ORIGIN = np.array([0.0, REC_Y0, 0.0600])     # drum hook point under the receiver
DRUM_C = np.array([-0.0040, -0.0720, 0.0])       # mag-local drum centre (axis along Z, the bore)
DRUM_R, DRUM_R_EXT = 0.0640, 0.0760
DRUM_W, DRUM_W_EXT = 0.0310, 0.0310               # half depths along Z: a coiled 56 mm round fits inside
MOUTH = np.array([-0.0300, -0.0020, 0.0])        # mag-local top of the drum's mouth (the belt leaves upwards)
BOLT_TRAVEL = 0.0950             # charging handle stroke
HANDLE_Z = 0.1000                # charging handle at rest
EJECTION = np.array([REC_W + 0.0005, 0.0020, 0.0880])
RAIL_Z0, RAIL_Z1 = 0.1640, 0.2480  # optic rail on the rear of the receiver (behind the feed cover)
RAIL_TOP, RAIL_W = 0.0360, 0.0105
OPTIC_Z = 0.2050
FEED = np.array([-REC_W, 0.0190, 0.0650])        # where the belt enters the feed tray (left side)
LINK = 0.0125                    # belt pitch
BELT_ROUNDS = 12                 # belt pieces drawn: the end in the tray (node `belt`) and belt_1 .. belt_11
BELT_END = np.array([0.0, 0.0, -0.0030])          # belt-local centre of the end round (the tray entry)
HG_SIDE_X = 0.0235


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """Stamped receiver: trunnion, the feed tray window on the left, ejection port on the right, the optic rail
    behind the cover, the trigger guard and the drum hook."""
    prof = [(0.0000, REC_Y0 + 0.0040), (0.0040, REC_Y0), (0.2300, REC_Y0), (0.2420, REC_Y0 + 0.0040),
            (REC_Z1, REC_Y0 + 0.0100), (REC_Z1, REC_Y1 - 0.0020), (REC_Z1 - 0.0020, REC_Y1), (0.0000, REC_Y1)]
    m = sh.extrude_x(prof, (), REC_W, chamfer_bevel(0.0012), "steel")
    m.merge(extrude_z(sh.rounded_rect(-REC_W - 0.0007, REC_Y0 + 0.0030, REC_W + 0.0007, REC_Y1 - 0.0010, 0.0030, 2),
                      (), 0.0005, 0.0300, chamfer_bevel(0.0008), "steel"))            # front trunnion
    for s in (-1, 1):
        for z, y in ((0.0090, -0.0250), (0.0230, -0.0250), (0.2350, -0.0280), (0.2480, -0.0120),
                     (0.1700, -0.0270), (0.1960, -0.0270)):
            m.merge(_rivet(s * (REC_W + 0.0006), y, z, s))
        x0, x1 = sorted((s * REC_W, s * (REC_W + 0.0008)))
        m.merge(sh.box(x0, -0.0200, 0.1560, x1, -0.0120, 0.2300, 0.0003, "steel"))      # side stiffener
    # feed tray window on the left (dark), the belt enters here
    win = Mesh()
    x = -REC_W - 0.00035
    win.add_poly([(x, 0.0040, 0.0400), (x, 0.0040, 0.0900), (x, REC_Y1 - 0.0005, 0.0900),
                  (x, REC_Y1 - 0.0005, 0.0400)], "steel_dark")
    m.merge(win)
    m.merge(sh.box(-REC_W - 0.0060, 0.0020, 0.0400, -REC_W, 0.0045, 0.0900, 0.0006, "steel"))   # tray lip
    # ejection port on the right
    port = Mesh()
    x = REC_W + 0.00035
    port.add_poly([(x, -0.0080, 0.1150), (x, -0.0080, 0.0640), (x, 0.0110, 0.0640), (x, 0.0110, 0.1150)],
                  "steel_dark")
    m.merge(port)
    # charging handle slot (right side, along the stroke)
    m.merge(sh.box(REC_W, -0.0020, HANDLE_Z - 0.0060, REC_W + 0.0006, 0.0030, HANDLE_Z + BOLT_TRAVEL + 0.008, 0.0002,
                   "steel_dark"))
    # optic rail riser on the rear top (behind the cover so it never meets the opened cover)
    m.merge(sh.box(-RAIL_W, REC_Y1 - 0.0010, RAIL_Z0, RAIL_W, RAIL_TOP, RAIL_Z1, 0.0008, "steel_dark"))
    for k in range(int((RAIL_Z1 - RAIL_Z0 - 0.006) / 0.010)):
        z = RAIL_Z0 + 0.007 + k * 0.010
        m.merge(sh.box(-RAIL_W - 0.0003, RAIL_TOP - 0.0016, z, RAIL_W + 0.0003, RAIL_TOP + 0.0003, z + 0.0042, 0.0003,
                       "steel_dark"))
    # cover latch block at the rear of the cover
    m.merge(sh.box(-0.0070, REC_Y1 - 0.0010, COVER_Z1 + 0.0010, 0.0070, 0.0300, COVER_Z1 + 0.0110, 0.0010, "steel"))
    # drum hook under the receiver and the trigger guard
    m.merge(sh.box(-0.0080, REC_Y0 - 0.0060, MAG_ORIGIN[2] - 0.0200, 0.0080, REC_Y0 + 0.0005, MAG_ORIGIN[2] + 0.0200,
                   0.0010, "steel"))
    guard_out = [(0.1040, REC_Y0 + 0.0005), (0.1040, REC_Y0 - 0.0060), (0.1120, REC_Y0 - 0.0215),
                 (0.1260, REC_Y0 - 0.0285), (0.1560, REC_Y0 - 0.0290), (0.1760, REC_Y0 - 0.0230),
                 (0.1820, REC_Y0 - 0.0140), (0.1820, REC_Y0 + 0.0005)]
    guard_in = [(0.1120, REC_Y0 - 0.0010), (0.1170, REC_Y0 - 0.0175), (0.1280, REC_Y0 - 0.0240),
                (0.1540, REC_Y0 - 0.0245), (0.1700, REC_Y0 - 0.0195), (0.1760, REC_Y0 - 0.0120),
                (0.1760, REC_Y0 - 0.0010)]
    m.merge(sh.extrude_x(guard_out, [guard_in], 0.0055, chamfer_bevel(0.0008), "steel"))
    # safety lever on the left above the grip
    m.merge(sh.box(-REC_W - 0.0012, -0.0150, 0.1700, -REC_W, -0.0080, 0.1900, 0.0004, "steel_dark"))
    return m


def feed_cover_mesh():
    """Hinged feed cover (node origin on the hinge pin at its front): rounded top, ribs, the rear latch tab."""
    def section(inflate=0.0):
        w, y0, top, r = REC_W + 0.0006 + inflate, REC_Y1 - 0.0040, COVER_TOP + inflate, 0.0100
        pts = [(-w, y0), (w, y0)]
        for a in np.linspace(0.0, np.pi / 2, 5):
            pts.append((w - r + r * np.cos(a), top - r + r * np.sin(a)))
        for a in np.linspace(np.pi / 2, np.pi, 5):
            pts.append((-w + r + r * np.cos(a), top - r + r * np.sin(a)))
        return np.array(pts)
    s0 = section()
    m = _loft_z([(sh.offset_loop(s0, 0.0012), COVER_Z0), (s0, COVER_Z0 + 0.0016), (s0, COVER_Z1 - 0.0020),
                 (sh.offset_loop(s0, 0.0010), COVER_Z1)], "steel")
    for z in (0.0500, 0.0950):
        m.merge(_loft_z([(section(0.0004), z - 0.0040), (section(0.0010), z - 0.0018),
                         (section(0.0010), z + 0.0018), (section(0.0004), z + 0.0040)], "steel"))
    m.merge(_cyl_x(0.0040, -REC_W - 0.0010, REC_W + 0.0010, (COVER_HINGE[1], COVER_HINGE[2]), "steel_dark", 12))
    m.merge(sh.box(-0.0055, COVER_TOP - 0.0050, COVER_Z1 - 0.0060, 0.0055, COVER_TOP + 0.0030, COVER_Z1 + 0.0050,
                   0.0010, "steel_dark"))                                                    # latch tab
    return m


# ------------------------------------------------------------------------- barrel, gas, sights
def barrel_mesh():
    """Heavy barrel with the rear sight block on the trunnion, handguard bands, gas block and the cylinder."""
    m = sh.revolve([(0.0, CROWN_Z), (BARREL_R * 0.9, CROWN_Z), (BARREL_R, CROWN_Z + 0.001),
                    (BARREL_R * 1.04, -0.300), (BARREL_R * 1.12, 0.0), (0.0, 0.0)], 18, "steel")
    rs = sh.rounded_rect(-0.0120, -0.0100, 0.0120, SIGHT_Y - 0.0095, (0.004, 0.004, 0.003, 0.003), 2)
    m.merge(_loft_z([(sh.offset_loop(rs, 0.0010), -0.0400), (rs, -0.0380), (rs, 0.0060),
                     (sh.offset_loop(rs, 0.0010), 0.0080)], "steel"))
    for z0, z1 in ((HG_Z1, HG_Z1 + 0.0080), (HG_Z0 - 0.0085, HG_Z0)):
        band = sh.rounded_rect(-0.0262, GAS_Y - 0.0150, 0.0262, 0.0130, (0.004, 0.004, 0.012, 0.012), 3)
        m.merge(extrude_z(band, (), z0, z1, chamfer_bevel(0.0008), "steel"))
    gb = [(-0.0120, GAS_Y - 0.0100), (0.0120, GAS_Y - 0.0100), (0.0120, 0.0110), (-0.0120, 0.0110)]
    m.merge(extrude_z(np.array(gb), (), GAS_Z0, GAS_Z0 + 0.0200, chamfer_bevel(0.0012), "steel"))
    m.merge(_cyl_z(GAS_R, GAS_Z0 + 0.0150, GAS_Z1, "steel", 16, y=GAS_Y, chamfer=0.0008))
    m.merge(_cyl_z(GAS_R + 0.0015, GAS_Z0 - 0.0060, GAS_Z0, "steel", 16, y=GAS_Y, chamfer=0.0010))   # gas plug
    return m


def muzzle_brake_mesh():
    """Short slotted flash hider on the crown (own node: hidden while a suppressor is fitted)."""
    m = _cyl_z(0.0115, MUZZLE_Z, CROWN_Z + 0.0030, "steel", 16, chamfer=0.0012)
    for k in range(4):
        a = np.pi / 4 + k * np.pi / 2
        c, s = np.cos(a), np.sin(a)
        slot = sh.box(-0.0016, 0.0098, MUZZLE_Z - 0.0005, 0.0016, 0.0120, MUZZLE_Z + 0.0180, 0.0002, "steel_dark")
        m.merge(sh.place(slot, x_axis=(c, s, 0), y_axis=(-s, c, 0), z_axis=(0, 0, 1)))
    m.merge(sh.revolve([(0.0050, MUZZLE_Z - 0.0010), (0.0050, MUZZLE_Z + 0.0100)], 12, "steel_dark", outward=False))
    return m


def front_sight_mesh():
    """Front sight collar and tower near the muzzle (the post and its ears are sight_iron, hidden with an optic)."""
    collar = sh.rounded_rect(-0.0120, -0.0130, 0.0120, 0.0130, 0.0095, 3)
    m = extrude_z(collar, (), FRONT_POST_Z - 0.0180, FRONT_POST_Z + 0.0160, chamfer_bevel(0.0010), "steel_dark")
    tower = [(-0.0085, 0.0110), (0.0085, 0.0110), (0.0065, SIGHT_Y - 0.0175), (-0.0065, SIGHT_Y - 0.0175)]
    m.merge(extrude_z(np.array(tower), (), FRONT_POST_Z - 0.0110, FRONT_POST_Z + 0.0090, chamfer_bevel(0.0008),
                      "steel_dark"))
    return m


def rear_sight_mesh():
    """Rear leaf sight on the trunnion block (node `sight_rear`; hidden while an optic is fitted)."""
    y0 = SIGHT_Y - 0.0098
    m = sh.box(-0.0080, y0, REAR_NOTCH_Z - 0.0300, 0.0080, SIGHT_Y - 0.0065, REAR_NOTCH_Z - 0.0030, 0.0008,
               "steel_dark")
    notch = np.array([(-0.0085, y0), (0.0085, y0), (0.0085, SIGHT_Y - 0.0008), (0.0070, SIGHT_Y),
                      (0.0012, SIGHT_Y), (0.0012, SIGHT_Y - 0.0030), (-0.0012, SIGHT_Y - 0.0030),
                      (-0.0012, SIGHT_Y), (-0.0070, SIGHT_Y), (-0.0085, SIGHT_Y - 0.0008)])
    m.merge(extrude_z(notch, (), REAR_NOTCH_Z - 0.0030, REAR_NOTCH_Z, chamfer_bevel(0.0003), "steel_dark"))
    return m


def front_post_mesh():
    """Front post on its base, with the two protective ears (node `sight_iron`: all hidden while an optic is fitted)."""
    post = np.array([(-0.0011, SIGHT_Y - 0.0180), (0.0011, SIGHT_Y - 0.0180), (0.0011, SIGHT_Y - 0.0006),
                     (0.0006, SIGHT_Y), (-0.0006, SIGHT_Y), (-0.0011, SIGHT_Y - 0.0006)])
    m = extrude_z(post, (), FRONT_POST_Z - 0.0012, FRONT_POST_Z + 0.0012, chamfer_bevel(0.0002), "steel_dark")
    for s in (-1, 1):
        ear = [(s * 0.0042, SIGHT_Y - 0.0180), (s * 0.0090, SIGHT_Y - 0.0180), (s * 0.0084, SIGHT_Y - 0.0020),
               (s * 0.0060, SIGHT_Y - 0.0010), (s * 0.0042, SIGHT_Y - 0.0020)]
        ear = ear if s > 0 else ear[::-1]
        m.merge(extrude_z(np.array(ear), (), FRONT_POST_Z - 0.0070, FRONT_POST_Z + 0.0060, chamfer_bevel(0.0005),
                          "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ furniture
def handguard_mesh():
    """Wooden handguard round the barrel and the gas cylinder, with palm swells."""
    zs = np.linspace(HG_Z0, HG_Z1, 11)
    rings = []
    for k, z in enumerate(zs):
        u = k / (len(zs) - 1)
        swell = 1.0 + 0.08 * np.sin(np.pi * np.clip((u - 0.10) / 0.80, 0, 1))
        sec = _hg_section(0.0232 * swell, GAS_Y - 0.0175, 0.0120)
        if k in (0, len(zs) - 1):
            sec = sh.offset_loop(sec, 0.0012)
        rings.append(_ring_xy(sec, z))
    m = sh.loft(rings, "wood")
    for k in range(5):                                       # cooling grooves on the sides
        z = HG_Z0 + 0.030 + k * 0.026
        for s in (-1, 1):
            x0, x1 = sorted((s * 0.0228, s * 0.0242))
            m.merge(sh.box(x0, -0.0120, z, x1, -0.0060, z + 0.0140, 0.0003, "wood"))
    return m


def stock_mesh():
    """Wooden stock: straight comb, deep belly and a steel butt plate."""
    z0, z1 = REC_Z1 - 0.0015, REC_Z1 + 0.2700
    top = lambda u: 0.0120 - 0.0220 * u ** 1.2
    bot = lambda u: (REC_Y0 + 0.0060) - 0.0900 * u ** 0.80 - 0.012 * u ** 3
    half = lambda u: 0.0160 + 0.0045 * u
    rings = []
    n = 10
    for k in range(n + 1):
        u = k / n
        y0, y1, w = bot(u), top(u), half(u)
        sec = sh.rounded_rect(-w, y0, w, y1, min(0.0085, 0.45 * w), 2)
        if k == 0:
            sec = sh.offset_loop(sec, 0.0010)
        rings.append(_ring_xy(sec, z0 + u * (z1 - z0)))
    m = sh.loft(rings, "wood")
    y0, y1, w = bot(1.0), top(1.0), half(1.0)
    plate = sh.rounded_rect(-w - 0.0005, y0 - 0.0005, w + 0.0005, y1 + 0.0005, min(0.0085, 0.45 * w), 2)
    m.merge(extrude_z(plate, (), z1 - 0.0005, z1 + 0.0045, chamfer_bevel(0.0008), "steel"))
    m.merge(sh.box(-0.0050, y1 - 0.0005, z1 - 0.0420, 0.0050, y1 + 0.0010, z1 - 0.0050, 0.0004, "steel"))
    m.merge(_cyl_x(0.0045, -w - 0.0010, w + 0.0010, (0.5 * (y0 + y1) - 0.010, z0 + 0.12), "steel_dark", 10))  # sling
    return m


def pistol_grip_mesh():
    rings = []
    n = 9
    for k in range(n + 1):
        d = -0.0040 + (GRIP_LEN + 0.0040) * k / n
        c = grip_center(d)
        u = k / n
        depth = 0.0175 + 0.0020 * np.sin(np.pi * u)
        w = 0.0128 + 0.0012 * np.sin(np.pi * u)
        sec = sh.superellipse(w, depth, 2.4, 20)
        if k == n:
            sec = sh.offset_loop(sec, 0.0020)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * (-GRIP_FRONT) for p in sec]))
    m = sh.loft(rings, "wood")
    for k in range(4):
        d = 0.020 + k * 0.018
        c = grip_center(d) + GRIP_FRONT * 0.0180
        rib = sh.box(-0.0098, -0.0012, -0.0012, 0.0098, 0.0012, 0.0012, 0.0004, "wood")
        m.merge(sh.place(rib, origin=c, x_axis=(1, 0, 0), y_axis=-GRIP_AXIS, z_axis=-GRIP_FRONT))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_mesh():
    """Carrier (inside) and the right-side charging handle; node origin at the rest position."""
    m = sh.box(-0.0110, -0.0060, 0.0600, 0.0130, 0.0100, 0.1500, 0.0015, "steel_dark")
    stem = [(HANDLE_Z - 0.0080, -0.0015), (HANDLE_Z + 0.0080, -0.0015), (HANDLE_Z + 0.0080, 0.0045),
            (HANDLE_Z - 0.0060, 0.0045)]
    m.merge(sh.extrude_x(np.array(stem), (), 0.0060, chamfer_bevel(0.0006), "steel_dark", x_center=REC_W + 0.0040))
    knob = sh.revolve([(0.0, 0.0), (0.0050, 0.0), (0.0058, 0.0010), (0.0058, 0.0110), (0.0045, 0.0135),
                       (0.0, 0.0140)], 14, "steel_dark")
    knob = sh.place(knob, x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    m.merge(knob.translated((REC_W + 0.0090, 0.0015, HANDLE_Z + 0.0010)))
    return m


def trigger_mesh():
    z = TRIGGER_PIVOT[2]
    blade = [(z - 0.0060, REC_Y0 + 0.0040), (z + 0.0010, REC_Y0 + 0.0040), (z + 0.0015, REC_Y0 - 0.0040),
             (z + 0.0005, REC_Y0 - 0.0110), (z - 0.0025, REC_Y0 - 0.0170), (z - 0.0060, REC_Y0 - 0.0205),
             (z - 0.0080, REC_Y0 - 0.0195), (z - 0.0060, REC_Y0 - 0.0150), (z - 0.0045, REC_Y0 - 0.0090),
             (z - 0.0045, REC_Y0 - 0.0020)]
    return sh.extrude_x(np.array(blade), (), 0.0030, round_bevel(0.0010, 2), "steel_dark")


# ------------------------------------------------------------------------------------ drum, belt, rounds
def _round(seg=6):
    """One 7.62x39 round along -Z, base at z = 0 (case `brass` = lacquered steel, the bullet in steel_dark); light: a
    belt draws a dozen of them."""
    case = sh.revolve([(0.0, 0.0), (0.0056, 0.0), (0.0055, -0.0270), (0.0040, -0.0390)], seg, "brass")
    case.merge(sh.revolve([(0.0040, -0.0390), (0.0028, -0.0510), (0.0, -0.0565)], seg, "steel_dark"))
    return case


def belt_piece():
    """One belt piece around its round's centre: the round (bullet towards -Z), its steel link — a band round the case
    and the strap to the next link (towards -X, the way the belt runs out of the tray)."""
    m = _round().translated((0.0, 0.0, 0.0283))
    m.merge(sh.revolve([(0.0062, 0.0180), (0.0062, 0.0100)], 6, "steel_dark", start_angle=np.pi / 6))
    m.merge(sh.box(-LINK + 0.0030, -0.0068, 0.0110, -0.0030, -0.0050, 0.0170, 0.0003, "steel_dark"))     # strap
    return m


def belt_mesh():
    """The belt's end piece (node `belt`: the round lying at the tray entry, the one the hand pinches)."""
    return belt_piece().translated(BELT_END)


def drum_mesh(extended=False):
    """Belt drum (mag-local: hook at the origin, drum below, axis along Z), the mouth the belt leaves from at its
    upper left (the same for both drums), the latch and stamped rings on both faces."""
    r, w = (DRUM_R_EXT, DRUM_W_EXT) if extended else (DRUM_R, DRUM_W)
    c = DRUM_C + (np.array([0.0, -(DRUM_R_EXT - DRUM_R), 0.0]) if extended else 0.0)
    m = _cyl_z(r, c[2] - w, c[2] + w, "mag", 24, y=c[1], x=c[0], chamfer=0.0025)
    for s in (-1, 1):                                          # stamped ring and hub on each face
        z0, z1 = sorted((c[2] + s * (w - 0.0005), c[2] + s * (w + 0.0010)))
        m.merge(_cyl_z(r * 0.72, z0, z1, "mag_base", 18, y=c[1], x=c[0]))
        z0, z1 = sorted((c[2] + s * (w + 0.0010), c[2] + s * (w + 0.0030)))
        m.merge(_cyl_z(0.0100, z0, z1, "mag_base", 12, y=c[1], x=c[0]))
    top = c[1] + r
    m.merge(sh.box(-0.0140, top - 0.0100, -0.0200, 0.0100, 0.0005, 0.0200, 0.0012, "mag"))       # neck up to the hook
    x0, x1 = MOUTH[0] - 0.0095, MOUTH[0] + 0.0095            # mouth: a chute from the rim up to the belt's exit
    rim = c[1] + np.sqrt(max(r * r - (MOUTH[0] - c[0]) ** 2, 0.0))
    m.merge(sh.box(x0, rim - 0.0120, -0.0300, x1, MOUTH[1], 0.0300, 0.0010, "mag"))
    m.merge(sh.box(x0 - 0.0008, MOUTH[1] - 0.0030, -0.0310, x1 + 0.0008, MOUTH[1] + 0.0004, 0.0310, 0.0004,
                   "mag_base"))                                # rolled lip of the mouth
    m.merge(sh.box(c[0] + 0.020, c[1] - 0.012, c[2] + w - 0.002, c[0] + 0.032, c[1] + 0.012, c[2] + w + 0.006,
                   0.0008, "mag_base"))                        # lid latch on the rear face
    return m


def shell_mesh(scale=1.15):
    """Spent 7.62x39 steel case (the AK's, material `brass`)."""
    from ak_model import shell_mesh as ak_shell
    return ak_shell(scale)
