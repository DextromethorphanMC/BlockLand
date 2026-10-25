"""Vector .45 SMG geometry (VIEWMODEL_SPEC sections 3-6, 19-21 and 44). Design frame (metres): +X right, +Y up, +Z
rearward; bore axis on x = y = 0, z = 0 at the breech face (barrel 140 mm: the crown at z = -0.140). Real-world scale:
617 mm with the stock unfolded.

An original procedural model after the KRISS Vector Gen II, its proportions traced off an orthographic reference sheet
(0.64 mm per reference pixel, the bore and the breech face as origin): the long slab-sided receiver (355 mm) with the
full-length top rail standing 52 mm over a low bore axis, which runs level with the top of the shooter's hand; the
short accessory rail under its square nose; the tall polymer lower whose front slants down and back into the magazine
well (the support hand's finger ribs along it), the straight Glock-style .45 magazine raked 17 degrees in it, the deep
lower running back under the trigger to the big trigger-guard opening, the slim guard bar across it and the bottom
strap into the base of the raked grip (stippled panels, finger ribs); recessed panels and the raised chevron on the
lower's flanks, the screw heads; the charging handle's slot and the exposed recoil mechanism on the left, the ejection
port on the right; flip-up sights at both ends of the rail; the side-folding skeleton stock behind its adapter (the
hinge knob on the left), its strut down to the vertical butt frame and the rubber pad. Two-tone: the receiver, lower and
grip in flat dark earth, the rails, sights, barrel, stock and magazines black (pbr_models_vector)."""
import numpy as np

import shapes as sh
from calico_model import picatinny
from meshkit import Mesh
from model_quality import arc_segments
from mp5_model import _cyl, _pin_x, _stud, smooth
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions (traced off the reference; see the module docstring) -----------------------
REC_W = 0.0225                    # upper receiver half width
SKIN = 0.0030                     # depth of the flanks over the core (the port, slot and windows are cut through it)
REC_TOP = 0.0452                  # top of the upper (the rail stands on it)
REC_Z0, REC_Z1 = -0.1264, 0.2214  # upper front face / rear face (over the back of the grip)
UPPER_BOT = 0.0106                # the upper's underside behind the lower: the top of the trigger-guard opening
FRONT_BOT = -0.0277               # the upper's underside in front of it (the nose and the mechanism)
STEP_Z = 0.0980                   # where the underside steps up (inside the lower)
RAIL_Z0, RAIL_Z1 = -0.1200, 0.2190
RAIL_FOOT, RAIL_TOP, RAIL_HW = REC_TOP - 0.0010, REC_TOP + 0.0071, 0.0106
SIGHT_Y = RAIL_TOP + 0.0200       # flip-up sights: the front post tip / the rear aperture
FRONT_POST_Z = -0.1050
REAR_Z = 0.1650                   # rear aperture (sight radius 270 mm)
APERTURE_R = 0.0021
BARREL_R = 0.0086
CROWN_Z = -0.1400
MUZZLE_Z = -0.1640                # front face of the flash hider
HIDER_Z0 = -0.1360                # flash hider's rear face (over the barrel's thread)
OPTIC_Z = 0.1300                  # an optic's base centre: far enough back that every optic's eye sits behind the rail
LOWER_W = 0.0215                  # lower receiver half width
LOWER_SKIN = 0.0012               # depth of the lower's recessed panels
LOWER_FRONT = -0.0431             # the lower's front top corner (behind the accessory rail)
CHIN_Y = -0.0298                  # under the nose: the accessory rail's foot (its teeth face down to CHIN_Y - 0.0062)
CRAIL_Z0, CRAIL_Z1 = -0.1035, -0.0480  # the accessory rail under the nose
MAG_RAKE = 17.0                   # the magazine leans back this much from the vertical (its foot rearward)
MAG_DOWN = np.array([0.0, -np.cos(np.radians(MAG_RAKE)), np.sin(np.radians(MAG_RAKE))])   # down the magazine
MAG_HW, MAG_HD = 0.0118, 0.0168   # magazine half width / half depth (front-back)
MAG_ORIGIN = np.array([0.0, -0.0120, -0.0040])   # seated magazine pivot: top centre of the body, under the chamber
WELL_DEPTH = 0.1035               # from the pivot down the magazine to the well's mouth
WELL_Y1 = float(MAG_ORIGIN[1] + WELL_DEPTH * MAG_DOWN[1])   # the mouth's height (on the magazine's axis)
WELL_Z0, WELL_Z1 = 0.0157, 0.0385                 # the mouth's front / rear on the lower's underside
MAG_LEN, MAG_LEN_EXT = 0.2050, 0.2450              # 25 and 30 rounds
GUARD_Y = -0.1060                 # bottom of the trigger-guard opening
GRIP_TOP = np.array([0.0, 0.0110, 0.1890])
GRIP_RAKE = 16.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_RAKE)), np.sin(np.radians(GRIP_RAKE))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_RAKE)), -np.cos(np.radians(GRIP_RAKE))])
GRIP_LEN = 0.1040
TRIGGER_PIVOT = np.array([0.0, 0.0090, 0.1490])
SELECTOR_PIVOT = np.array([-REC_W, 0.0300, 0.1720])
PADDLE_PIVOT = np.array([0.0, -0.0340, 0.0270])     # magazine release tabs behind the well, on both flanks
EJECTION = np.array([REC_W + 0.0005, 0.0240, 0.0000])
PORT_Z0, PORT_Z1, PORT_Y0, PORT_Y1 = -0.0270, 0.0240, 0.0090, 0.0390
SLOT_Y = 0.0170                   # charging handle slot (left flank, at the front)
HANDLE_Z = -0.1080                # charging handle's slider front at rest
BOLT_TRAVEL = 0.0560              # its stroke
CARRIER_TRAVEL = 0.0460           # the bolt's stroke when firing
SLOT_Z0, SLOT_Z1 = HANDLE_Z - 0.0040, HANDLE_Z + 0.0160 + BOLT_TRAVEL
WINDOW = (-0.0280, -0.0190, 0.0287, 0.0060)          # the exposed recoil mechanism on the left (z0, y0, z1, y1)
HINGE = np.array([-REC_W - 0.0006, SLOT_Y, HANDLE_Z + 0.0140])    # the folding knob's hinge (rear of the slider)
KNOB_LEN = 0.0230
ADAPTER_Z1 = 0.2834               # the stock adapter behind the receiver (the stock hinges on its rear)
ADAPTER_TOP = 0.0360              # kept low: the eye looks over it at ADS
STOCK_Z0 = ADAPTER_Z1
STOCK_Z1 = 0.4777                 # the butt pad's face (617 mm overall from the crown)
HINGE_BLOCK = (REC_Z1, ADAPTER_Z1)
STOCK_MOUNT = np.array([0.0, 0.0240, ADAPTER_Z1 + 0.0004])


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def _flip_y():
    """Matrix turning an extrude_z sleeve (outline in x-y, along +Z) to hang along -Y (outline in the x-z plane)."""
    flip = np.eye(4)
    flip[:3, :3] = np.column_stack([(1, 0, 0), (0, 0, 1), (0, -1, 0)])
    return flip


def _mag_frame(origin=MAG_ORIGIN):
    """Matrix from magazine-local (+Y up the magazine, -Z front) to the design frame: raked about X at `origin`."""
    a = np.radians(-MAG_RAKE)
    mat = np.eye(4)
    mat[:3, :3] = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
    mat[:3, 3] = origin
    return mat


def _slanted(centre, axis, half_len, half_w, r, seg=3):
    """(z, y) loop of a rounded slot of `half_len` along the unit (z, y) `axis`, `half_w` across it."""
    loop = sh.rounded_rect(-half_len, -half_w, half_len, half_w, r, seg)
    a = np.asarray(axis, float) / np.linalg.norm(axis)
    n = np.array([-a[1], a[0]])
    return np.array([np.asarray(centre) + p[0] * a + p[1] * n for p in loop])


# ------------------------------------------------------------------------------------- receiver
def _upper_profile(top=REC_TOP):
    """(z, y) side outline of the upper: square nose, flat top, the rear face over the grip; its underside low in front
    (the nose, the mechanism) and stepping up behind the lower to the top of the trigger-guard opening."""
    return np.array([(REC_Z0, top - 0.0040), (REC_Z0 + 0.0040, top), (REC_Z1 - 0.0060, top), (REC_Z1, top - 0.0060),
                     (REC_Z1, UPPER_BOT), (STEP_Z, UPPER_BOT), (STEP_Z, FRONT_BOT), (REC_Z0 + 0.0030, FRONT_BOT),
                     (REC_Z0, FRONT_BOT + 0.0030)])


def _port_hole():
    return sh.rounded_rect(PORT_Z0, PORT_Y0, PORT_Z1, PORT_Y1, 0.0020, 2)


def _slot_hole():
    return sh.rounded_rect(SLOT_Z0, SLOT_Y - 0.0034, SLOT_Z1, SLOT_Y + 0.0034, 0.0030, 3)


def _window_hole():
    z0, y0, z1, y1 = WINDOW
    return np.array([(z0 + 0.0030, y0), (z1, y0), (z1, y1 - 0.0080), (z1 - 0.0090, y1), (z0, y1), (z0, y0 + 0.0040)])


def mechanism_mesh():
    """What shows through the window on the left: the guide rods and the springs of the recoil mechanism, the block."""
    z0, y0, z1, y1 = WINDOW
    x = -(REC_W - SKIN)
    m = Mesh()
    for y in (y0 + 0.0065, y1 - 0.0075):
        m.merge(_cyl(0.0024, z0 + 0.0010, z1 - 0.0010, "rail", 10, y=y, x=x - 0.0006))
        for k in range(9):                                   # the spring's coils as rings
            z = z0 + 0.0040 + k * (z1 - z0 - 0.0080) / 8
            m.merge(_cyl(0.0031, z - 0.0006, z + 0.0006, "rail", 10, y=y, x=x - 0.0006))
    m.merge(sh.box(x - 0.0030, y0 + 0.0020, z1 - 0.0170, x + 0.0004, y1 - 0.0020, z1 - 0.0040, 0.0006, "rail"))
    return m


def receiver_mesh():
    """Upper receiver: a core block with the two flanks over it (the ejection port through the right one, the charging
    slot and the mechanism window through the left), the top rail, the accessory rail under the nose, a side rail stub
    for the laser, the stock adapter with its hinge knob, the screw heads."""
    m = sh.extrude_x(_upper_profile(REC_TOP + 0.0006), (), REC_W - SKIN, ((0.0, 0.0),), "upper")
    m.merge(sh.extrude_x(_upper_profile(), [_port_hole()], SKIN / 2, chamfer_bevel(0.0011), "upper",
                         x_center=REC_W - SKIN / 2))
    m.merge(sh.extrude_x(_upper_profile(), [_slot_hole(), _window_hole()], SKIN / 2, chamfer_bevel(0.0011), "upper",
                         x_center=-REC_W + SKIN / 2))
    m.merge(mechanism_mesh())
    # the barrel's exit in the nose: a raised collar
    m.merge(sh.revolve([(BARREL_R + 0.0005, REC_Z0 + 0.0010), (0.0128, REC_Z0 + 0.0010), (0.0128, REC_Z0 - 0.0018),
                        (0.0118, REC_Z0 - 0.0030), (BARREL_R + 0.0005, REC_Z0 - 0.0030)], 24, "upper", precision=True))
    m.merge(picatinny(RAIL_Z0, RAIL_Z1, RAIL_FOOT, RAIL_TOP, RAIL_HW, 33, "rail"))
    # the accessory rail under the nose (vertical grip, light)
    m.merge(picatinny(CRAIL_Z0, CRAIL_Z1, FRONT_BOT + 0.0010, CHIN_Y - 0.0062, 0.0088, 5, "rail", down=True))
    m.merge(sh.box(-0.0100, CHIN_Y - 0.0010, CRAIL_Z0 - 0.0020, 0.0100, FRONT_BOT + 0.0012, CRAIL_Z1 + 0.0020, 0.0012,
                   "rail"))
    # side rail stub on the right of the nose for the laser
    side = picatinny(-0.1150, -0.0650, 0.0, 0.0060, 0.0075, 4, "rail")
    m.merge(sh.place(side, origin=(REC_W - 0.0010, 0.0000, 0.0), x_axis=(0, 1, 0), y_axis=(1, 0, 0), z_axis=(0, 0, 1)))
    # the stock adapter behind the receiver: low on top (the cheek rests over it), the hinge knob on its left
    adapter = np.array([(REC_Z1 - 0.0020, 0.0125), (ADAPTER_Z1, 0.0125), (ADAPTER_Z1, ADAPTER_TOP - 0.0030),
                        (ADAPTER_Z1 - 0.0050, ADAPTER_TOP), (REC_Z1 - 0.0020, ADAPTER_TOP)])
    m.merge(sh.extrude_x(adapter, (), 0.0150, round_bevel(0.0020, 2), "stock"))
    knob = sh.revolve([(0.0, 0.0), (0.0090, 0.0), (0.0094, -0.0010), (0.0094, -0.0050), (0.0085, -0.0060),
                       (0.0, -0.0060)], 20, "stock", precision=True)
    m.merge(sh.place(knob, origin=(-0.0150, 0.0240, REC_Z1 + 0.0200), x_axis=(0, 0, 1), y_axis=(0, 1, 0),
                     z_axis=(1, 0, 0)))
    for k in range(10):                                   # its ribs
        a = 2 * np.pi * k / 10
        rib = sh.box(-0.0216, -0.0010, -0.0007, -0.0150, 0.0010, 0.0007, 0.0002, "stock")
        rot = np.eye(4)
        rot[:3, :3] = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
        rib = rib.transformed(rot).translated((0.0, 0.0240 + 0.0094 * np.cos(a), REC_Z1 + 0.0200 + 0.0094 * np.sin(a)))
        m.merge(rib)
    # screw heads on both flanks (the reference's pattern)
    for y, z in ((0.0300, -0.0950), (0.0050, -0.1170), (0.0050, -0.0950), (0.0300, 0.0480), (0.0310, 0.1200),
                 (0.0300, 0.2000), (-0.0180, -0.0700)):
        for s in (-1, 1):
            m.merge(_stud(y, z, s * REC_W, s, 0.0026, 0.0007, "rail"))
    # the bolt catch over the magazine well (both sides)
    for s in (-1, 1):
        lev = np.array([(0.0420, 0.0330), (0.0560, 0.0350), (0.0570, 0.0390), (0.0420, 0.0390)])
        m.merge(sh.extrude_x(lev, (), 0.0009, round_bevel(0.0004, 1), "rail", x_center=s * (REC_W + 0.0009)))
    return m


def _slab_outline():
    """(z, y) side outline of the lower: the front slanting down and back to the well's mouth, the deep underside, the
    strap rising into the grip's base, the trigger-guard opening's edges, the top inside the upper."""
    return np.array([(LOWER_FRONT, FRONT_BOT + 0.0035), (LOWER_FRONT, -0.0293), (-0.0202, -0.0374),
                     (WELL_Z0, -0.1109), (WELL_Z1, -0.1240), (0.1267, -0.1256), (0.1659, -0.1109), (0.1985, -0.0979),
                     (0.2030, -0.0890), (0.1910, -0.0915), (0.1675, -0.1040), (0.1332, -0.1040), (0.1218, -0.0619),
                     (0.1087, -0.0130), (0.1038, UPPER_BOT + 0.0010), (STEP_Z - 0.0060, UPPER_BOT + 0.0010),
                     (STEP_Z - 0.0060, FRONT_BOT + 0.0035)])


def _slab_recesses():
    """The recessed panels in the lower's flanks: a long slot along its slanted front, a shorter one by the guard."""
    front = _slanted((0.0108, -0.0603), (0.0359, -0.0735), 0.0300, 0.0056, 0.0050)
    rear = _slanted((0.1020, -0.0800), (0.0245, -0.0910), 0.0220, 0.0050, 0.0045)
    return [front, rear]


def lower_mesh():
    """Polymer lower: a core with recessed panels in its flanks, the magazine well's mouth collar raked with the
    magazine, the guard bar, the finger ribs along the front, the raised chevron, the release tabs' bosses, pins."""
    out = _slab_outline()
    m = sh.extrude_x(out, (), LOWER_W - LOWER_SKIN, round_bevel(0.0010, 1), "lower")
    for s in (-1, 1):
        m.merge(sh.extrude_x(out, _slab_recesses(), LOWER_SKIN / 2, round_bevel(0.0005, 1), "lower",
                             x_center=s * (LOWER_W - LOWER_SKIN / 2)))
        # the raised chevron on each flank
        tri = np.array([(0.0451, -0.0261), (0.0940, -0.0261), (0.0712, -0.0603)])
        inner = np.array([(0.0560, -0.0300), (0.0830, -0.0300), (0.0712, -0.0480)])
        m.merge(sh.extrude_x(tri, [inner[::-1]], 0.0004, ((0.0, 0.0),), "lower", x_center=s * (LOWER_W + 0.0003)))
        # finger ribs along the slanted front
        a = np.array([0.0359, -0.0735]) / np.linalg.norm([0.0359, -0.0735])
        for k in range(6):
            c = np.array([-0.0202, -0.0374]) + a * (0.010 + 0.0105 * k) + np.array([0.0050, 0.0])
            rib = sh.box(-0.0006, -0.0012, -0.0040, 0.0006, 0.0012, 0.0040, 0.0003, "lower")
            m.merge(rib.translated((s * (LOWER_W + 0.0003), c[1], c[0])))
        # the release tabs' bosses
        m.merge(_stud(PADDLE_PIVOT[1] - 0.0060, PADDLE_PIVOT[2] + 0.0040, s * LOWER_W, s, 0.0040, 0.0006, "lower"))
    # the slim guard bar across the opening, under the trigger
    bar = np.array([(0.1100, -0.0205), (0.1440, -0.0225), (0.1730, -0.0185), (0.1740, -0.0225), (0.1440, -0.0265),
                    (0.1115, -0.0250)])
    m.merge(sh.extrude_x(bar, (), 0.0045, round_bevel(0.0012, 1), "lower"))
    # the magazine well's mouth: a collar round the magazine, raked with it, standing out of the underside
    outline = sh.rounded_rect(-MAG_HW - 0.0032, -MAG_HD - 0.0034, MAG_HW + 0.0032, MAG_HD + 0.0030, 0.0040, 2)
    hole = sh.rounded_rect(-MAG_HW - 0.0006, -MAG_HD - 0.0007, MAG_HW + 0.0006, MAG_HD + 0.0007, 0.0034, 2)
    collar = extrude_z(outline, [hole[::-1]], WELL_DEPTH - 0.0150, WELL_DEPTH + 0.0080, round_bevel(0.0014, 1), "lower")
    m.merge(collar.transformed(_flip_y()).transformed(_mag_frame()))
    # the dark well behind the mouth (seen when the magazine is out)
    cap = sh.box(-MAG_HW - 0.0004, -WELL_DEPTH + 0.0100, -MAG_HD - 0.0004, MAG_HW + 0.0004, -WELL_DEPTH + 0.0110,
                 MAG_HD + 0.0004, 0.0, "rail")
    m.merge(cap.transformed(_mag_frame()))
    # pins through the lower
    for y, z in ((-0.1110, 0.0700), (-0.1110, 0.1150), (-0.0700, 0.0350), (-0.0350, 0.0950)):
        m.merge(_pin_x(y, z, -LOWER_W - 0.0004, LOWER_W + 0.0004, 0.0026, "rail"))
    return m


def pistol_grip_mesh():
    """The raked grip (16 degrees) growing out of the receiver's rear: deep at the top, slimmer at the flared base;
    stippled panels on its flanks, finger ribs down its front."""
    rings = []
    n = 12
    for k in range(n + 1):
        u = k / n
        d = -0.0080 + (GRIP_LEN + 0.0080) * u
        c = grip_center(d)
        depth = 0.0300 - 0.0100 * u + 0.0016 * np.sin(np.pi * u) + 0.0014 * smooth(0.88, 1.0, u)
        w = 0.0142 + 0.0008 * np.sin(np.pi * u) + 0.0012 * smooth(0.88, 1.0, u)
        sec = sh.superellipse(w, depth, 2.8, 24, n_front=2.3)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * GRIP_FRONT for p in sec]))
    for inset, dd in ((0.0018, 0.0030), (0.0055, 0.0052)):
        c = grip_center(GRIP_LEN + dd)
        sec = sh.offset_loop(sh.superellipse(w, depth, 2.8, 24, n_front=2.3), inset)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * GRIP_FRONT for p in sec]))
    m = sh.loft(rings, "lower")
    # stippled panels: raised pads on both flanks
    for s in (-1, 1):
        for d0, h in ((0.034, 0.022), (0.072, 0.018)):
            c = grip_center(d0)
            pad = sh.box(-0.0007, -h / 2, -0.0130, 0.0007, h / 2, 0.0130, 0.0005, "lower")
            m.merge(sh.place(pad, origin=c + np.array([s * 0.0148, 0.0, 0.0]) + 0.0030 * (-GRIP_FRONT),
                             x_axis=(1, 0, 0), y_axis=-GRIP_AXIS, z_axis=-GRIP_FRONT))
    # finger ribs down the front
    for k in range(5):
        d = 0.034 + 0.012 * k
        depth = 0.0300 - 0.0100 * (d + 0.008) / (GRIP_LEN + 0.008)
        c = grip_center(d) + (depth - 0.0008) * GRIP_FRONT
        rib = sh.box(-0.0090, -0.0012, -0.0012, 0.0090, 0.0012, 0.0012, 0.0004, "lower")
        m.merge(sh.place(rib, origin=c, x_axis=(1, 0, 0), y_axis=-GRIP_AXIS, z_axis=-GRIP_FRONT))
    return m


def barrel_mesh():
    """Barrel from the chamber to the crown, the thread under the flash hider."""
    return sh.revolve([(0.0, -0.0060), (BARREL_R, -0.0060), (BARREL_R, REC_Z0 - 0.0010), (0.0080, REC_Z0 - 0.0040),
                       (0.0074, HIDER_Z0 + 0.0010), (0.0068, HIDER_Z0), (0.0068, CROWN_Z + 0.0006),
                       (0.0060, CROWN_Z), (0.0060, CROWN_Z), (0.0058, CROWN_Z + 0.0100), (0.0, CROWN_Z + 0.0100)],
                      24, "barrel", precision=True)


def muzzle_cap_mesh():
    """Three-prong flash hider (node `muzzle_brake`; hidden while the barrel slot holds something): the body over the
    thread, then three tines with open slots between them, chamfered tips."""
    m = sh.revolve([(0.0062, HIDER_Z0), (0.0100, HIDER_Z0), (0.0106, HIDER_Z0 - 0.0006), (0.0106, -0.1480),
                    (0.0062, -0.1480)], 24, "barrel", precision=True)
    for k in range(3):
        a0 = np.radians(90.0 + 120.0 * k - 46.0)
        a1 = np.radians(90.0 + 120.0 * k + 46.0)
        ang = np.linspace(a0, a1, 7)
        outer = [(0.0106 * np.cos(a), 0.0106 * np.sin(a)) for a in ang]
        inner = [(0.0070 * np.cos(a), 0.0070 * np.sin(a)) for a in ang[::-1]]
        tine = extrude_z(np.array(outer + inner), (), MUZZLE_Z, -0.1478, chamfer_bevel(0.0004), "barrel")
        m.merge(tine)
    return m


def front_post_mesh():
    """Front sight post (node `sight_iron`): the post on its tower, the tip on the sight line."""
    post = np.array([(-0.0011, SIGHT_Y - 0.0085), (0.0011, SIGHT_Y - 0.0085), (0.0009, SIGHT_Y - 0.0005),
                     (0.0005, SIGHT_Y), (-0.0005, SIGHT_Y), (-0.0009, SIGHT_Y - 0.0005)])
    return extrude_z(post, (), FRONT_POST_Z - 0.0012, FRONT_POST_Z + 0.0012, chamfer_bevel(0.0002), "rail")


def front_hood_mesh():
    """Flip-up front sight body (node `front_sight_hood`, under sight_iron): the rail clamp, the raised leaf with the
    protective ears either side of the post (their tips below the post's)."""
    z0, z1 = FRONT_POST_Z - 0.0080, FRONT_POST_Z + 0.0080
    base = np.array([(-0.0118, RAIL_TOP - 0.0030), (0.0118, RAIL_TOP - 0.0030), (0.0118, RAIL_TOP + 0.0040),
                     (-0.0118, RAIL_TOP + 0.0040)])
    m = extrude_z(base, (), z0, z1, round_bevel(0.0010, 1), "rail")
    leaf = np.array([(-0.0080, RAIL_TOP + 0.0035), (0.0080, RAIL_TOP + 0.0035), (0.0080, SIGHT_Y - 0.0040),
                     (0.0055, SIGHT_Y - 0.0010), (0.0034, SIGHT_Y - 0.0010), (0.0034, SIGHT_Y - 0.0090),
                     (-0.0034, SIGHT_Y - 0.0090), (-0.0034, SIGHT_Y - 0.0010), (-0.0055, SIGHT_Y - 0.0010),
                     (-0.0080, SIGHT_Y - 0.0040)])
    m.merge(extrude_z(leaf, (), FRONT_POST_Z - 0.0030, FRONT_POST_Z + 0.0030, chamfer_bevel(0.0004), "rail"))
    m.merge(_pin_x(RAIL_TOP + 0.0010, FRONT_POST_Z + 0.0055, -0.0122, 0.0122, 0.0016, "rail"))
    return m


def rear_sight_mesh():
    """Flip-up rear sight (node `sight_rear`; hidden while an optic is fitted): the clamp, the leaf with the aperture
    ring on the sight line and the ears round it."""
    z0, z1 = REAR_Z - 0.0090, REAR_Z + 0.0090
    base = np.array([(-0.0118, RAIL_TOP - 0.0030), (0.0118, RAIL_TOP - 0.0030), (0.0118, RAIL_TOP + 0.0040),
                     (-0.0118, RAIL_TOP + 0.0040)])
    m = extrude_z(base, (), z0, z1, round_bevel(0.0010, 1), "rail")
    seg = arc_segments(0.0062, 24)
    ring = extrude_z(sh.circle(0.0, SIGHT_Y, 0.0062, seg), [sh.circle(0.0, SIGHT_Y, APERTURE_R, seg)[::-1]],
                     REAR_Z - 0.0015, REAR_Z + 0.0015, chamfer_bevel(0.0003), "rail")
    m.merge(ring)
    stem = np.array([(-0.0045, RAIL_TOP + 0.0035), (0.0045, RAIL_TOP + 0.0035), (0.0034, SIGHT_Y - 0.0055),
                     (-0.0034, SIGHT_Y - 0.0055)])
    m.merge(extrude_z(stem, (), REAR_Z - 0.0015, REAR_Z + 0.0015, chamfer_bevel(0.0003), "rail"))
    for s in (-1, 1):
        ear = np.array([(s * 0.0072, RAIL_TOP + 0.0035), (s * 0.0102, RAIL_TOP + 0.0035), (s * 0.0102, SIGHT_Y + 0.0030),
                        (s * 0.0080, SIGHT_Y + 0.0060), (s * 0.0072, SIGHT_Y + 0.0060)])
        ear = ear if s > 0 else ear[::-1]
        m.merge(extrude_z(ear, (), REAR_Z - 0.0035, REAR_Z + 0.0035, chamfer_bevel(0.0004), "rail"))
    return m


def stock_mesh():
    """Side-folding skeleton stock, unfolded: the bar from the adapter's hinge (the cheek rest), bending down into the
    vertical butt frame, the strut from under the bar to the frame's foot (the open triangle between them), the rubber
    pad, the hinge knuckle."""
    outer = np.array([(STOCK_Z0, 0.0354), (0.3847, 0.0354), (0.3960, 0.0230), (0.4020, 0.0090), (0.4467, 0.0066),
                      (0.4467, -0.1240), (0.4206, -0.1191), (0.3683, 0.0125), (STOCK_Z0, 0.0125)])
    hole = np.array([(0.3930, -0.0060), (0.4340, -0.0060), (0.4340, -0.1060), (0.4255, -0.1060)])
    m = sh.extrude_x(outer, [hole[::-1]], 0.0100, round_bevel(0.0022, 2), "stock")
    # the rubber pad: rounded, a little wider than the frame, its grip lines
    pad = np.array([(0.4460, 0.0073), (0.4720, 0.0073), (STOCK_Z1, -0.0020), (STOCK_Z1, -0.1210), (0.4720, -0.1305),
                    (0.4460, -0.1305)])
    m.merge(sh.extrude_x(pad, (), 0.0135, round_bevel(0.0035, 2), "pad"))
    for k in range(8):
        y = -0.0140 - 0.0135 * k
        m.merge(sh.box(-0.0120, y - 0.0012, STOCK_Z1 - 0.0004, 0.0120, y + 0.0012, STOCK_Z1 + 0.0008, 0.0003, "pad"))
    for y, z in ((0.0000, 0.4370), (-0.1100, 0.4370)):
        m.merge(_pin_x(y, z, -0.0105, 0.0105, 0.0032, "rail"))
    # the hinge knuckle at the front
    m.merge(sh.box(-0.0090, 0.0115, STOCK_Z0 - 0.0005, 0.0090, 0.0364, STOCK_Z0 + 0.0130, 0.0020, "stock"))
    return m


# ------------------------------------------------------------------------------------ moving parts
def carrier_mesh():
    """The bolt seen through the ejection port (node `carrier`): its side plate just inside the port, the extractor,
    the bolt face's rim; rides back when firing."""
    x0, x1 = REC_W - SKIN - 0.0030, REC_W - SKIN + 0.0008
    m = sh.box(x0, PORT_Y0 + 0.0008, PORT_Z0 - 0.0040, x1, PORT_Y1 - 0.0008, PORT_Z1 + CARRIER_TRAVEL + 0.0040,
               0.0006, "rail")
    m.merge(sh.box(x1 - 0.0010, PORT_Y0 + 0.0080, PORT_Z0 - 0.0030, x1 + 0.0008, PORT_Y0 + 0.0130, PORT_Z0 + 0.0140,
                   0.0004, "rail"))
    for z in (PORT_Z0 + 0.0260, PORT_Z0 + 0.0360):
        m.merge(sh.box(x1 - 0.0004, PORT_Y0 + 0.0030, z, x1 + 0.0002, PORT_Y1 - 0.0030, z + 0.0040, 0.0001, "rail"))
    return m


def slider_mesh():
    """Charging handle slider (node `bolt`, the slide-lock node): the block riding the slot just outside the core, the
    stem out through the slot to the knob's hinge."""
    core = -(REC_W - SKIN)
    m = sh.box(core - 0.0010, SLOT_Y - 0.0028, HANDLE_Z, core + 0.0020, SLOT_Y + 0.0028, HANDLE_Z + 0.0160, 0.0006,
               "rail")
    m.merge(sh.box(-REC_W - 0.0008, SLOT_Y - 0.0022, HANDLE_Z + 0.0090, core - 0.0005, SLOT_Y + 0.0022,
                   HANDLE_Z + 0.0160, 0.0004, "rail"))
    return m


def knob_mesh():
    """The folding knob (node `handle_lever`, hinged at the slider's rear about Y): folded forward flat on the left
    flank; ribbed grip face."""
    hz = HINGE[2]
    x_in, x_out = HINGE[0] + 0.0004, HINGE[0] - 0.0040
    prof = np.array([(hz + 0.0020, SLOT_Y - 0.0040), (hz + 0.0020, SLOT_Y + 0.0040), (hz - KNOB_LEN + 0.0030, SLOT_Y + 0.0052),
                     (hz - KNOB_LEN, SLOT_Y + 0.0040), (hz - KNOB_LEN, SLOT_Y - 0.0040),
                     (hz - KNOB_LEN + 0.0030, SLOT_Y - 0.0052)])
    m = sh.extrude_x(prof, (), 0.5 * (x_in - x_out), round_bevel(0.0008, 1), "rail", x_center=0.5 * (x_in + x_out))
    for k in range(4):
        z = hz - KNOB_LEN + 0.0045 + 0.0035 * k
        m.merge(sh.box(x_out - 0.0005, SLOT_Y - 0.0042, z, x_out + 0.0004, SLOT_Y + 0.0042, z + 0.0012, 0.0002, "rail"))
    return m


def trigger_mesh():
    z = TRIGGER_PIVOT[2]
    y = TRIGGER_PIVOT[1]
    blade = [(z - 0.0040, y + 0.0030), (z + 0.0030, y + 0.0030), (z + 0.0030, y - 0.0080), (z + 0.0008, y - 0.0160),
             (z - 0.0025, y - 0.0215), (z - 0.0055, y - 0.0235), (z - 0.0070, y - 0.0225), (z - 0.0050, y - 0.0190),
             (z - 0.0032, y - 0.0140), (z - 0.0026, y - 0.0070)]
    return sh.extrude_x(np.array(blade), (), 0.0032, round_bevel(0.0010, 2), "rail")


def selector_mesh():
    """Selector on the left over the grip (rotates about X at its axle): hub and a short lever pointing forward."""
    p = SELECTOR_PIVOT
    m = _stud(p[1], p[2], p[0] - 0.0004, -1, 0.0048, 0.0024, "rail", seg=18)
    lever = [(p[2] + 0.0035, p[1] + 0.0030), (p[2] - 0.0130, p[1] + 0.0020), (p[2] - 0.0175, p[1] + 0.0010),
             (p[2] - 0.0175, p[1] - 0.0020), (p[2] - 0.0130, p[1] - 0.0026), (p[2] + 0.0035, p[1] - 0.0030)]
    m.merge(sh.extrude_x(np.array(lever), (), 0.0011, round_bevel(0.0004, 1), "rail", x_center=p[0] - 0.0034))
    return m


def paddle_mesh():
    """Ambidextrous magazine release (node `paddle`, rotates about X at its pivot: pushed forward): a ribbed tab on each
    flank of the lower behind the magazine well, joined through it."""
    p = PADDLE_PIVOT
    m = Mesh()
    for s in (-1, 1):
        prof = np.array([(p[2] - 0.0040, p[1] + 0.0030), (p[2] + 0.0060, p[1] + 0.0030), (p[2] + 0.0070, p[1] - 0.0100),
                         (p[2] + 0.0020, p[1] - 0.0140), (p[2] - 0.0030, p[1] - 0.0110)])
        m.merge(sh.extrude_x(prof, (), 0.0010, round_bevel(0.0004, 1), "rail", x_center=s * (LOWER_W + 0.0010)))
        for k in range(3):
            y = p[1] - 0.0040 - 0.0028 * k
            m.merge(sh.box(s * (LOWER_W + 0.0018) - 0.0004, y, p[2] - 0.0010, s * (LOWER_W + 0.0018) + 0.0004,
                           y + 0.0010, p[2] + 0.0050, 0.0002, "rail"))
    m.merge(_pin_x(p[1], p[2], -LOWER_W, LOWER_W, 0.0016, "rail"))
    return m


# ------------------------------------------------------------------------------------ magazines
def _mag_section(hw, hd, r=0.0040):
    """(x, f) loop of the polymer magazine body (f towards the front): a rounded rectangle, the back a little narrower
    (the Glock-style taper)."""
    pts = []
    for cx, cf, a0 in ((hw - r, -hd + r, -0.5 * np.pi), (hw - r, hd - r, 0.0), (-hw + r, hd - r, 0.5 * np.pi),
                       (-hw + r, -hd + r, np.pi)):
        for a in np.linspace(a0, a0 + 0.5 * np.pi, 4):
            x = cx + r * np.cos(a)
            f = cf + r * np.sin(a)
            if f < 0:
                x *= 1.0 - 0.08 * (-f / hd)
            pts.append((x, f))
    return np.array(pts)


def _round_mesh(seg=14):
    """One .45 ACP round along -Z, base at z = 0: the brass case and the round-nosed bullet (brass too)."""
    prof = [(0.0, 0.0), (0.00598, 0.0), (0.00605, -0.0006), (0.00605, -0.0012), (0.00510, -0.0016),
            (0.00510, -0.0028), (0.00600, -0.0032), (0.00592, -0.0228), (0.00570, -0.0232), (0.00560, -0.0262),
            (0.00460, -0.0296), (0.00280, -0.0318), (0.0, -0.0324)]
    return sh.revolve(prof, seg, "brass")


def mag_mesh(extended=False):
    """Straight double-stack magazine (mag-local: +Y up into the well, -Z front): polymer body, the base pad, feed lips
    with the top round, witness holes down its back."""
    length = MAG_LEN_EXT if extended else MAG_LEN
    ys = [0.0, -0.0300, -0.0600, -length + 0.0200, -length + 0.0060, -length]
    rings = []
    for y in ys:
        grow = 0.0006 * smooth(-0.020, -0.060, y)
        sec = _mag_section(MAG_HW + grow, MAG_HD + grow)
        rings.append(np.array([(p[0], y, -p[1]) for p in sec]))
    m = sh.loft(rings, "mag")
    for s in (-1, 1):                                 # feed lips
        x0, x1 = sorted((s * 0.0036, s * (MAG_HW - 0.0004)))
        m.merge(sh.box(x0, -0.0010, -MAG_HD + 0.0050, x1, 0.0036, MAG_HD - 0.0010, 0.0008, "mag"))
    m.merge(_round_mesh().translated((0.0020, 0.0040, MAG_HD - 0.0020)))
    for k in range(6):
        m.merge(_cyl(0.0012, -0.0004, 0.0004, "mag_base", 8).transformed(_z_back(np.array([0.0, -0.1150 - 0.014 * k,
                                                                                            MAG_HD - 0.0002]))))
    plate = sh.box(-MAG_HW - 0.0018, -length - 0.0060, -MAG_HD - 0.0030, MAG_HW + 0.0018, -length + 0.0010,
                   MAG_HD + 0.0018, 0.0018, "mag_base")
    m.merge(plate)
    m.merge(sh.box(-MAG_HW - 0.0006, -length + 0.0010, -MAG_HD - 0.0010, MAG_HW + 0.0006, -length + 0.0140,
                   MAG_HD + 0.0008, 0.0010, "mag_base"))
    return m


def _z_back(p):
    mat = np.eye(4)
    mat[:3, 3] = p
    return mat


def shell_mesh(scale=1.1):
    """Spent .45 ACP case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0114), (0.0056, 0.0114), (0.00605, 0.0108), (0.00605, 0.0102), (0.0051, 0.0098),
            (0.0051, 0.0086), (0.0060, 0.0082), (0.00592, -0.0114), (0.0055, -0.0114), (0.0055, -0.0098),
            (0.0, -0.0098)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 16, "brass")


def parts():
    """Design-frame meshes per mesh node (gen_vector moves them to their node origins)."""
    return {
        "receiver": receiver_mesh(),
        "lower": lower_mesh(),
        "pistol_grip": pistol_grip_mesh(),
        "barrel": barrel_mesh(),
        "stock": stock_mesh(),
        "bolt": slider_mesh(),
        "handle_lever": knob_mesh(),
        "carrier": carrier_mesh(),
        "trigger": trigger_mesh(),
        "selector": selector_mesh(),
        "paddle": paddle_mesh(),
        "sight_iron": front_post_mesh(),
        "front_sight_hood": front_hood_mesh(),
        "sight_rear": rear_sight_mesh(),
        "muzzle_brake": muzzle_cap_mesh(),
        "mag_standard": mag_mesh(False),
        "mag_extended": mag_mesh(True),
        "fx_shell": shell_mesh(),
    }
