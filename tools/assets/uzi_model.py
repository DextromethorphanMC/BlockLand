"""IMI Uzi geometry (VIEWMODEL_SPEC section 40). Design frame (metres): +X right, +Y up, +Z rearward; bore axis on
x = y = 0, z = 0 at the front face of the receiver. Real-world scale, measured off the reference photograph
(source/references/uzi.jpg, right side, stock extended: 3.0 px per mm): overall 0.65 m (0.47 m folded), barrel
0.26 m, the thread protector 75 mm ahead of the receiver.

An original procedural model: a stamped 44 mm steel box receiver with two long pressed ribs along each side, the top
cover with its cocking slot and the knurled, notched cocking knob (its notch keeps the sight line clear), the
protected front post and the flip aperture between the rear ears, a knurled barrel nut and a short protruding barrel
with a thread protector, the ribbed polymer handguard, the grip frame with the large strap trigger guard, the
selector slide on the left, a near-vertical pistol grip that takes the magazine (32 / 50 rounds) with the grip safety
at its back and the heel catch at its bottom, sling swivels, the metal folding stock (two pressed arms on a hinge at
the receiver's rear, the curved brace and the butt plate) and a short rail adapter clamped on the rear of the top
cover for optics. Every builder returns a meshkit.Mesh in the design frame; gen_uzi moves meshes into node space."""
import numpy as np

import shapes as sh
import smg_detail as sd
from ak_model import _cyl_x, _cyl_z, _ring_xy
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0222                   # receiver half width (a 44 mm stamped box)
REC_TOP = 0.0250                 # top edge of the side walls (the top cover sits on it)
REC_Y0 = -0.0120                 # receiver bottom behind the grip frame
REC_Y0_FRONT = -0.0300           # the lower front of the receiver (inside the handguard)
REC_Z1 = 0.3570                  # rear end (stock hinge)
COVER_Z0, COVER_Z1 = 0.0265, 0.3140   # the cover's nose tucks in behind the front sight
COVER_TOP = 0.0328
COVER_W = REC_W + 0.0009          # the cover is a channel: its skirts come down outside the receiver walls
SKIRT_Y = 0.0168
SLOT_Z0, SLOT_Z1 = 0.0905, 0.2050   # cocking slot along the top of the cover
SLOT_W = 0.0031                  # slot half width
SLOT_DEPTH = 0.0036
KNOB_Z = 0.1015                  # cocking knob centre at rest (forward)
KNOB_TRAVEL = 0.0900             # its stroke back along the slot (the bolt is drawn back onto the sear)
KNOB_R, KNOB_TOP = 0.0110, 0.0513
NOTCH_W, NOTCH_Y = 0.0026, 0.0418  # the sighting notch through the knob's top (half width / floor)
SIGHT_Y = 0.0460                 # sight line: front post tip / rear aperture centre
FRONT_POST_Z = 0.0205
REAR_NOTCH_Z = 0.3360            # rear face of the aperture leaf
REAR_EAR_C = np.array([0.3348, 0.0425])   # (z, y) of the rear ears' round tops
REAR_EAR_R = 0.0142
APERTURE_R = 0.0025              # the flip leaf's aperture (a 5 mm peep)
BARREL_R = 0.0083
CROWN_Z = -0.0700                # barrel crown (threaded end; a suppressor threads on here)
NUT_Z0, NUT_R = -0.0295, 0.0155  # knurled barrel nut
CAP_Z1, CAP_R = -0.0748, 0.0092  # thread protector (the `muzzle_brake` node)
MUZZLE_Z = CAP_Z1
HG_Z0, HG_Z1 = -0.0008, 0.1335  # handguard front / rear
HG_W, HG_TOP, HG_BOT = 0.0258, 0.0150, -0.0345
FRAME_Z0, FRAME_Z1 = 0.1350, 0.2560   # grip frame (trigger housing) under the receiver
FRAME_Y0, FRAME_Y1 = -0.0300, -0.0050
FRAME_W = REC_W + 0.0013
GRIP_Z0 = 0.1850                 # pistol grip front strap (steel, in front of the magazine)
GRIP_PANEL_Z = 0.1880            # front edges of the polymer grip panels
GRIP_CHEEK_Z = 0.2045            # behind the steel cheeks at the top of the front strap
GRIP_Y0, GRIP_Y1 = -0.0280, -0.1290   # grip top (inside the frame) / bottom
GRIP_TOP = np.array([0.0, -0.0300, 0.2140])
GRIP_TILT = 4.0                  # the grip is nearly vertical (the magazine runs straight through it)
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_TILT)), np.sin(np.radians(GRIP_TILT))])
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_TILT)), -np.cos(np.radians(GRIP_TILT))])
MAG_HW, MAG_HD = 0.01175, 0.0165  # magazine half width (x) / half depth (z): 23.5 x 33 mm
MAG_ORIGIN = np.array([0.0, -0.0120, 0.2050])   # seated magazine pivot (top centre of the body, in the receiver)
MAG_LEN = {False: 0.1945, True: 0.2665}          # body below the pivot: 32 / 50 rounds (83 / 155 mm below the grip)
GRIP_BOTTOM_LOCAL = GRIP_Y1 - MAG_ORIGIN[1]      # the grip's bottom face in magazine space
TRIGGER_PIVOT = np.array([0.0, -0.0270, 0.1690])
EJECTION = np.array([COVER_W + 0.0005, 0.0227, 0.1730])
PORT_Z0, PORT_Z1, PORT_Y0, PORT_Y1 = 0.1480, 0.1980, 0.0182, 0.0272   # in the cover's right skirt
RAIL_Z0, RAIL_Z1 = 0.2150, 0.3020  # optic rail adapter on the rear of the top cover (behind the knob's stroke)
RAIL_TOP, RAIL_W = COVER_TOP + 0.0085, 0.0106   # a riser: optics look over the knob and the ears
OPTIC_Z = 0.2585
HINGE = np.array([0.0, 0.0045, 0.3645])           # stock hinge pin (y, z used)
STOCK_X = 0.0236                 # stock arms' centre planes (x = +-)
BUTT_Z = 0.5755                  # rear face of the butt plate (overall length from the thread protector: 0.654)
RIBS = ((0.1380, 0.2600, 0.0115), (0.1380, 0.2600, 0.0018), (0.2680, 0.3180, 0.0115), (0.2680, 0.3180, 0.0018))
RIB_H = 0.0024                   # pressed rib half height
SWIVEL = np.array([-REC_W, 0.0130, 0.1130])        # front sling swivel boss on the left of the receiver
HG_RIB_Z1 = 0.0900               # the handguard's ribs end here; behind them it is smooth and scooped under the
                                 # swivel


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# -------------------------------------------------------------------------------------- helpers
def _stadium(z0, z1, yc, h, seg=5):
    """CCW (z, y) loop of a pill along z (rounded ends of radius h)."""
    pts = []
    for a in np.linspace(-np.pi / 2, np.pi / 2, seg + 1):
        pts.append((z1 - h + h * np.cos(a), yc + h * np.sin(a)))
    for a in np.linspace(np.pi / 2, 3 * np.pi / 2, seg + 1):
        pts.append((z0 + h + h * np.cos(a), yc + h * np.sin(a)))
    return np.array(pts)


def _pad_x(loop_zy, x_face, side, proud, material, embed=0.0004, chamfer=None):
    """Raised pad on a face normal to X (side +1 right / -1 left): the (z, y) loop lofted from `embed` inside the
    face out to `proud`, its outer edge chamfered."""
    loop = sh.ccw(np.asarray(loop_zy, float))
    c = min(0.5 * proud, 0.0005) if chamfer is None else chamfer
    rings = []
    for dx, ins in ((-embed, 0.0), (proud - c, 0.0), (proud, c)):
        o = sh.offset_loop(loop, ins) if ins else loop
        rings.append(np.stack([np.full(len(o), side * (x_face + dx)), o[:, 1], o[:, 0]], 1))
    return sh.loft(rings, material)


def _rib_y(x_face, side, z, w, y0, y1, proud, material):
    """Moulded rib standing on a face normal to X, running along Y from y0 to y1 (`w` wide from z): a round crown,
    its ends running down into the face (smg_detail.rib)."""
    return sd.rib_x(x_face, side, (z + 0.5 * w, y0), (z + 0.5 * w, y1), w, proud, material)


def _rib_z(x_face, side, y, h, z0, z1, proud, material):
    """Moulded rib on a face normal to X running along Z (height h from y), round crowned like _rib_y."""
    return sd.rib_x(x_face, side, (z0, y + 0.5 * h), (z1, y + 0.5 * h), h, proud, material)


def _rot_y(mesh):
    """Revolved mesh (axis Z) turned so its axis runs along +Y."""
    return sh.place(mesh, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))


def _rot_x(mesh, side=1):
    """Revolved mesh (axis Z) turned so its axis runs along side * X."""
    return sh.place(mesh, x_axis=(0, 1, 0), y_axis=(0, 0, side), z_axis=(side, 0, 0))


def _screw_x(x, y, z, side, r=0.0022, material="steel_dark", slot=True):
    """Slotted dome screw head on a face normal to X."""
    m = sh.revolve([(0.0, 0.0011), (r * 0.6, 0.0010), (r, 0.0004), (r, -0.0004), (0.0, -0.0004)], 12, material)
    m = _rot_x(m, side).translated((x, y, z))
    if slot:
        x0, x1 = sorted((x + side * 0.0005, x + side * 0.00118))
        m.merge(sh.box(x0, y - 0.00028, z - r * 0.8, x1, y + 0.00028, z + r * 0.8, 0.0, "steel_dark"))
    return m


def _pin_x(x0, x1, y, z, r, material="steel_dark", seg=10):
    """Cross pin along X with chamfered ends."""
    c = 0.25 * r
    m = sh.revolve([(0.0, x0), (r - c, x0), (r, x0 + c), (r, x1 - c), (r - c, x1), (0.0, x1)], seg, material)
    return sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0)).translated((0.0, y, z))


def _fluted_ring(r_out, r_in, n, y, half=None):
    """3D ring (x, y, z) in the XZ plane at height y: a straight knurl of n flutes (2n points, alternating radii).
    half = +1 / -1: only the flutes beyond the sighting notch on that side (the same angles for every radius, so
    rings loft), closed along the notch wall."""
    a = np.linspace(0, 2 * np.pi, 2 * n, endpoint=False)
    r = np.where(np.arange(2 * n) % 2 == 0, r_out, r_in)
    if half is None:
        return np.stack([r * np.cos(a), np.full(2 * n, y), -r * np.sin(a)], 1)
    a = np.where(a > np.pi, a - 2 * np.pi, a) if half > 0 else a
    keep = np.cos(a) * half * 0.0095 > NOTCH_W + 0.0006
    order = np.argsort(a[keep])
    a, r = a[keep][order], r[keep][order]
    zw = np.sqrt(max(r_in * r_in - NOTCH_W * NOTCH_W, 0.0))
    pts = [(half * NOTCH_W, y, half * zw)] + [(rr * np.cos(aa), y, -rr * np.sin(aa)) for aa, rr in zip(a, r)]         + [(half * NOTCH_W, y, -half * zw)]
    return np.array(pts)


def _knurl_tube(r_out, r_in, n, z0, z1, material, chamfer=0.0008, y=0.0):
    """Straight-knurled cylinder along Z (n flutes), chamfered ends."""
    def ring(z, k):
        a = np.linspace(0, 2 * np.pi, 2 * n, endpoint=False)
        r = np.where(np.arange(2 * n) % 2 == 0, r_out, r_in) * k
        return np.stack([r * np.cos(a), y + r * np.sin(a), np.full(2 * n, z)], 1)
    kc = 1.0 - chamfer / r_out
    return sh.loft([ring(z0, kc), ring(z0 + chamfer, 1.0), ring(z1 - chamfer, 1.0), ring(z1, kc)], material)


# -------------------------------------------------------------------------------------- receiver
def _receiver_profile():
    """(z, y) side profile of the stamped box: tall at the front (inside the handguard), shallower behind the grip
    frame, the rear end square with rounded corners."""
    return np.array([(0.0000, REC_Y0_FRONT + 0.0015), (0.0015, REC_Y0_FRONT), (FRAME_Z0 + 0.0060, REC_Y0_FRONT),
                     (FRAME_Z0 + 0.0060, REC_Y0), (REC_Z1 - 0.0030, REC_Y0), (REC_Z1, REC_Y0 + 0.0030),
                     (REC_Z1, REC_TOP - 0.0040), (REC_Z1 - 0.0012, REC_TOP - 0.0012), (REC_Z1 - 0.0040, REC_TOP),
                     (0.0015, REC_TOP), (0.0000, REC_TOP - 0.0015)])


def receiver_mesh():
    """Stamped box receiver: the long pressed ribs on both sides, the ejection port on the right, the front sling
    swivel, the stock hinge lug at the rear and the stock latch under it."""
    m = sh.extrude_x(_receiver_profile(), (), REC_W, round_bevel(0.0018, 2), "steel")
    for z0, z1, yc in RIBS:                                 # pressed ribs (raised, rounded ends)
        for s in (-1, 1):
            m.merge(_pad_x(_stadium(z0, z1, yc, RIB_H, 5), REC_W, s, 0.0010, "steel", chamfer=0.0006))
    # stock hinge lug at the rear (between the stock's knuckles) and its pin; the latch under the rear end
    lug = np.array([(REC_Z1 - 0.0040, -0.0060), (HINGE[2], -0.0060), (HINGE[2] + 0.0060, HINGE[1] - 0.0040),
                    (HINGE[2] + 0.0060, HINGE[1] + 0.0040), (HINGE[2], 0.0150), (REC_Z1 - 0.0040, 0.0150)])
    m.merge(sh.extrude_x(lug, (), 0.0140, chamfer_bevel(0.0008), "steel"))
    m.merge(_pin_x(-0.0250, 0.0250, HINGE[1], HINGE[2], 0.0024))
    m.merge(sh.box(-0.0080, REC_Y0 - 0.0050, REC_Z1 - 0.0200, 0.0080, REC_Y0 + 0.0010, REC_Z1 - 0.0010, 0.0010,
                   "steel"))
    m.merge(sh.box(-0.0060, REC_Y0 - 0.0068, REC_Z1 - 0.0185, 0.0060, REC_Y0 - 0.0040, REC_Z1 - 0.0110, 0.0008,
                   "steel_dark"))                                            # latch button
    # front sling swivel on the left (boss, rivet and the D loop) and the rear swivel tab on the right
    m.merge(sling_loop())
    m.merge(sh.box(REC_W - 0.0010, REC_Y0 - 0.0010, REC_Z1 - 0.0300, REC_W + 0.0025, REC_Y0 + 0.0080,
                   REC_Z1 - 0.0120, 0.0008, "steel"))
    m.merge(_pin_x(REC_W, REC_W + 0.0035, REC_Y0 + 0.0035, REC_Z1 - 0.0210, 0.0018))
    # spot welds of the barrel trunnion near the front
    for s in (-1, 1):
        for z in (0.0060, 0.0260):
            w = sh.revolve([(0.0, 0.0003), (0.0020, 0.0001), (0.0024, -0.0004), (0.0, -0.0004)], 10, "steel")
            m.merge(_rot_x(w, s).translated((s * REC_W, 0.0145, z)))
    return m


def sling_loop():
    """Front sling swivel on the left of the receiver: a round boss with its rivet and the D loop round it, lying
    against the side and hanging down and forward over the handguard's scooped rear."""
    x, y, z = SWIVEL
    m = sh.revolve([(0.0, 0.0052), (0.0040, 0.0052), (0.0048, 0.0044), (0.0048, -0.0006), (0.0, -0.0006)], 16,
                   "steel")
    m = _rot_x(m, -1).translated((x, y, z))
    m.merge(_screw_x(x - 0.0052, y, z, -1, 0.0024, slot=False))
    loop = []
    for a in np.linspace(0.0, 2 * np.pi, 14, endpoint=False):     # a D loop: round at the front, flat at the boss
        p = np.array([0.0145 * np.cos(a), 0.0110 * np.sin(a)])
        if p[0] > 0.0060:
            p[0] = 0.0060 + 0.35 * (p[0] - 0.0060)
        loop.append(p)
    loop = np.array(loop)
    rot = np.radians(-28.0)                                        # tilted: hanging down and forward
    c, sn = np.cos(rot), np.sin(rot)
    loop = loop @ np.array([[c, sn], [-sn, c]])
    centre = np.array([z - 0.0040, y - 0.0040])
    ring = []
    for k in range(len(loop)):                                     # a round rod swept round the loop
        p0, p1 = loop[k - 1], loop[(k + 1) % len(loop)]
        t = (p1 - p0) / np.linalg.norm(p1 - p0)
        nrm = np.array([-t[1], t[0]])
        sec = []
        for b in np.linspace(0.0, 2 * np.pi, 6, endpoint=False):
            off = 0.0013 * np.cos(b) * nrm
            sec.append((x - 0.0058 + 0.0013 * np.sin(b), centre[1] + loop[k][1] + off[1], centre[0] + loop[k][0]
                        + off[0]))
        ring.append(sec)
    ring.append(ring[0])
    m.merge(sh.orient_outward(sh.loft(np.array(ring), "steel_dark", cap_start=False, cap_end=False)))
    return m


def _cover_section(notch, nose=0.0):
    """(x, y) CCW section of the top cover channel: skirts down outside the receiver walls, rounded top corners, the
    cocking slot cut in the top (notch) or not; `nose` 0..1 lowers and narrows it towards the front end."""
    w = COVER_W - 0.0030 * nose
    top = COVER_TOP - 0.0058 * nose
    y0 = SKIRT_Y + 0.0040 * nose
    r = 0.0050
    pts = [(-w, y0), (w, y0)]
    for a in np.linspace(0.0, np.pi / 2, 5):
        pts.append((w - r + r * np.cos(a), top - r + r * np.sin(a)))
    if notch:
        d = top - SLOT_DEPTH
        pts += [(SLOT_W, top), (SLOT_W, d), (-SLOT_W, d), (-SLOT_W, top)]
    else:
        pts += [(0.0040, top), (0.0013, top), (-0.0013, top), (-0.0040, top)]
    for a in np.linspace(np.pi / 2, np.pi, 5):
        pts.append((-w + r + r * np.cos(a), top - r + r * np.sin(a)))
    return np.array(pts)


def top_cover_mesh():
    """Top cover: a pressed channel over the receiver's top with the cocking slot cut along its middle (front,
    slotted and rear parts built from one section so the outside stays seamless), its nose tapering down in behind
    the front sight, the ejection port cut in its right skirt, two stiffening beads and the rear latch buttons."""
    solid, slot = _cover_section(False), _cover_section(True)
    nose = [(COVER_Z0, 1.0), (COVER_Z0 + 0.0045, 0.82), (COVER_Z0 + 0.0110, 0.45), (COVER_Z0 + 0.0190, 0.12),
            (COVER_Z0 + 0.0260, 0.0)]
    rings = [_ring_xy(sh.offset_loop(sh.ccw(_cover_section(False, 1.0)), 0.0010), COVER_Z0 - 0.0006)]
    rings += [_ring_xy(_cover_section(False, k), z) for z, k in nose]
    rings.append(_ring_xy(solid, SLOT_Z0))
    m = sh.loft(rings, "steel")
    m.merge(sh.loft([_ring_xy(slot, SLOT_Z0 - 0.0001), _ring_xy(slot, SLOT_Z1 + 0.0001)], "steel"))
    m.merge(sh.loft([_ring_xy(solid, SLOT_Z1), _ring_xy(solid, COVER_Z1 - 0.0012),
                     _ring_xy(sh.offset_loop(sh.ccw(solid), 0.0010), COVER_Z1)], "steel"))
    floor = Mesh()                                                   # dark floor of the slot
    y = COVER_TOP - SLOT_DEPTH + 0.0001
    floor.add_poly([(-SLOT_W, y, SLOT_Z1), (SLOT_W, y, SLOT_Z1), (SLOT_W, y, SLOT_Z0), (-SLOT_W, y, SLOT_Z0)],
                   "steel_dark")
    m.merge(floor)
    # ejection port in the right skirt: a dark opening framed by a pressed lip
    x = COVER_W + 0.0003
    port = Mesh()
    port.add_poly([(x, PORT_Y0, PORT_Z1), (x, PORT_Y0, PORT_Z0), (x, PORT_Y1, PORT_Z0), (x, PORT_Y1, PORT_Z1)],
                  "steel_dark")
    m.merge(port)
    lip_o = np.array([(PORT_Z0 - 0.0015, PORT_Y0 - 0.0013), (PORT_Z1 + 0.0015, PORT_Y0 - 0.0013),
                      (PORT_Z1 + 0.0015, PORT_Y1 + 0.0013), (PORT_Z0 - 0.0015, PORT_Y1 + 0.0013)])
    lip_i = np.array([(PORT_Z0, PORT_Y0), (PORT_Z0, PORT_Y1), (PORT_Z1, PORT_Y1), (PORT_Z1, PORT_Y0)])
    m.merge(sh.extrude_x(lip_o, [lip_i], 0.0005, chamfer_bevel(0.0003), "steel", x_center=COVER_W + 0.0002))
    # pressed stiffening beads either side of the slot
    for s_ in (-1, 1):
        bead = sh.box(-0.0010, -0.0006, COVER_Z0 + 0.0300, 0.0010, 0.0005, RAIL_Z0 - 0.0040, 0.0004, "steel")
        m.merge(bead.translated((s_ * 0.0105, COVER_TOP - 0.0002, 0.0)))
    # rear latch: two buttons either side of the cover's rear end
    for s_ in (-1, 1):
        b = sh.revolve([(0.0, 0.0016), (0.0024, 0.0016), (0.0030, 0.0008), (0.0030, -0.0005), (0.0, -0.0005)], 12,
                       "steel_dark")
        m.merge(_rot_x(b, s_).translated((s_ * COVER_W, 0.0245, COVER_Z1 - 0.0110)))
    return m


def knob_mesh():
    """The cocking knob on its stem in the cover slot (node `bolt`, at rest forward): a straight-knurled cylinder
    with a chamfered top cut by the sighting notch (the front post shows through it), the smooth neck, the stem and
    the slide plate riding in the slot."""
    n = 18
    y_neck0, y_neck1 = COVER_TOP - 0.0006, 0.0390
    prof = [(0.0, y_neck0), (0.0094, y_neck0), (0.0100, y_neck0 + 0.0006)]
    for k in range(3):                                      # the neck's three turned grooves
        y = y_neck0 + 0.0014 + k * 0.0016
        prof += [(0.0100, y), (0.0092, y + 0.0004), (0.0092, y + 0.0008), (0.0100, y + 0.0012)]
    prof += [(0.0100, y_neck1 - 0.0004), (0.0104, y_neck1), (0.0, y_neck1)]
    m = _rot_y(sh.revolve(prof, 28, "steel_dark"))
    # the knurled body: a full ring section up to the notch floor, two halves above it
    kc = 1.0 - 0.0010 / KNOB_R
    rings = [_fluted_ring(KNOB_R * 0.94, KNOB_R * 0.90, n, y_neck1 - 0.0004),
             _fluted_ring(KNOB_R, KNOB_R - 0.0006, n, y_neck1 + 0.0006),
             _fluted_ring(KNOB_R, KNOB_R - 0.0006, n, NOTCH_Y)]
    m.merge(sh.loft(rings, "steel_dark"))
    for half in (-1, 1):
        rings = [_fluted_ring(KNOB_R, KNOB_R - 0.0006, n, NOTCH_Y - 0.0006, half=half),
                 _fluted_ring(KNOB_R, KNOB_R - 0.0006, n, KNOB_TOP - 0.0012, half=half),
                 _fluted_ring(KNOB_R * kc, (KNOB_R - 0.0006) * kc, n, KNOB_TOP, half=half)]
        m.merge(sh.loft(rings, "steel_dark"))
    m = m.translated((0.0, 0.0, KNOB_Z))
    # stem down the slot and the slide plate under it
    m.merge(sh.box(-SLOT_W + 0.0006, COVER_TOP - SLOT_DEPTH + 0.0003, KNOB_Z - 0.0040, SLOT_W - 0.0006,
                   y_neck0 + 0.0004, KNOB_Z + 0.0040, 0.0004, "steel_dark"))
    m.merge(sh.box(-SLOT_W + 0.0003, COVER_TOP - SLOT_DEPTH + 0.0002, KNOB_Z - 0.0110, SLOT_W - 0.0003,
                   COVER_TOP - SLOT_DEPTH + 0.0010, KNOB_Z + 0.0110, 0.0003, "steel_dark"))
    return m


def rail_mesh():
    """Short picatinny rail adapter clamped on the rear of the top cover (behind the knob's stroke): a saddle hugging
    the cover with two clamp screws each side, the rail on it with real 10 mm slot spacing."""
    w, top = COVER_W + 0.0012, COVER_TOP + 0.0012
    sad = [(-w, REC_TOP + 0.0030), (w, REC_TOP + 0.0030), (w, top - 0.0040), (w - 0.0040, top),
           (-w + 0.0040, top), (-w, top - 0.0040)]
    m = extrude_z(np.array(sad), (), RAIL_Z0, RAIL_Z1, chamfer_bevel(0.0009), "steel_dark")
    floor = RAIL_TOP - 0.0026                                     # the cross slots' floor
    riser = np.array([(-0.0098, top - 0.0004), (0.0098, top - 0.0004), (0.0098, top + 0.0010), (0.0078, top + 0.0024),
                      (0.0078, floor), (-0.0078, floor), (-0.0078, top + 0.0024), (-0.0098, top + 0.0010)])
    m.merge(extrude_z(riser, (), RAIL_Z0 + 0.0012, RAIL_Z1 - 0.0012, chamfer_bevel(0.0005), "steel_dark"))
    tooth = np.array([(-0.0078, floor - 0.0004), (0.0078, floor - 0.0004), (0.0078, RAIL_TOP - 0.0030),
                      (RAIL_W, RAIL_TOP - 0.0020), (RAIL_W, RAIL_TOP - 0.0012), (RAIL_W - 0.0010, RAIL_TOP),
                      (-RAIL_W + 0.0010, RAIL_TOP), (-RAIL_W, RAIL_TOP - 0.0012), (-RAIL_W, RAIL_TOP - 0.0020),
                      (-0.0078, RAIL_TOP - 0.0030)])
    edges = [RAIL_Z0 + 0.0020]
    k = 0
    while RAIL_Z0 + 0.0070 + k * 0.0100 + 0.0052 < RAIL_Z1 - 0.0040:      # cross slots: 5.2 mm every 10 mm
        z = RAIL_Z0 + 0.0070 + k * 0.0100
        edges += [z, z + 0.0052]
        k += 1
    edges.append(RAIL_Z1 - 0.0020)
    for z0, z1 in zip(edges[0::2], edges[1::2]):                       # the teeth between the slots
        m.merge(extrude_z(tooth, (), z0, z1, chamfer_bevel(0.0004), "steel_dark"))
    for z in (RAIL_Z0 + 0.0140, RAIL_Z1 - 0.0140):                    # clamp screws
        for s in (-1, 1):
            m.merge(_screw_x(s * w, REC_TOP + 0.0060, z, s, 0.0021))
    return m


# ------------------------------------------------------------------------------- sights
def front_ears_mesh():
    """Front sight block welded on the receiver's front top: a saddle down over the side walls carrying the two
    protective ears with rounded tops (they stay when an optic is fitted; the post between them is `sight_iron`)."""
    sad = [(-REC_W - 0.0012, SKIRT_Y - 0.0004), (REC_W + 0.0012, SKIRT_Y - 0.0004), (REC_W + 0.0012, 0.0255),
           (REC_W - 0.0018, 0.0285), (-REC_W + 0.0018, 0.0285), (-REC_W - 0.0012, 0.0255)]
    m = extrude_z(np.array(sad), (), 0.0015, 0.0405, round_bevel(0.0010, 1), "steel")
    ear = [(0.0040, 0.0270), (0.0400, 0.0270)]
    for a in np.linspace(0.0, np.pi / 2, 6):                  # a big round over the rear top
        ear.append((0.0260 + 0.0140 * np.cos(a), 0.0373 + 0.0140 * np.sin(a)))
    for a in np.linspace(np.pi / 2, np.pi, 5)[1:]:            # a tighter round at the front
        ear.append((0.0130 + 0.0090 * np.cos(a), 0.0423 + 0.0090 * np.sin(a)))
    ear = np.array(ear)
    for s_ in (-1, 1):
        m.merge(sh.extrude_x(ear, (), 0.0017, chamfer_bevel(0.0005), "steel", x_center=s_ * 0.0092))
    return m


def front_post_mesh():
    """Front post on its elevation drum between the ears (node `sight_iron`: hidden while an optic is fitted)."""
    z = FRONT_POST_Z
    m = _cyl_x(0.0046, -0.0074, 0.0074, (0.0335, z), "steel_dark", 16)
    for k in range(12):                                     # the drum's grip notches
        a = 2 * np.pi * k / 12
        c = np.array([0.0, 0.0335 + 0.0046 * np.sin(a), z + 0.0046 * np.cos(a)])
        m.merge(sh.box(-0.0060, c[1] - 0.0004, c[2] - 0.0004, 0.0060, c[1] + 0.0004, c[2] + 0.0004, 0.0,
                       "steel_dark"))
    post = np.array([(-0.0012, 0.0350), (0.0012, 0.0350), (0.0008, SIGHT_Y - 0.0004), (0.0005, SIGHT_Y),
                     (-0.0005, SIGHT_Y), (-0.0008, SIGHT_Y - 0.0004)])
    m.merge(extrude_z(post, (), z - 0.0012, z + 0.0012, chamfer_bevel(0.0002), "steel_dark"))
    return m


def rear_ears_mesh():
    """Rear sight base on the receiver's rear top with the two round protective ears (screw heads at their
    centres); the flip aperture between them is `sight_rear`."""
    sad = [(-REC_W - 0.0012, SKIRT_Y + 0.0020), (REC_W + 0.0012, SKIRT_Y + 0.0020), (REC_W + 0.0012, 0.0262),
           (REC_W - 0.0018, 0.0292), (-REC_W + 0.0018, 0.0292), (-REC_W - 0.0012, 0.0262)]
    m = extrude_z(np.array(sad), (), 0.3165, 0.3545, round_bevel(0.0010, 1), "steel")
    zc, yc = REAR_EAR_C
    ear = [(0.3205, 0.0270), (0.3490, 0.0270)]
    for a in np.linspace(-0.12, np.pi + 0.12, 15):
        ear.append((zc + REAR_EAR_R * np.cos(a), yc + REAR_EAR_R * np.sin(a)))
    ear = np.array(ear)
    for s in (-1, 1):
        m.merge(sh.extrude_x(ear, (), 0.0016, chamfer_bevel(0.0005), "steel", x_center=s * 0.0099))
        m.merge(_screw_x(s * 0.0115, yc, zc, s, 0.0030))
    return m


def rear_sight_mesh():
    """The flip aperture (node `sight_rear`, hidden while an optic is fitted): the raised leaf with its 3.8 mm
    aperture on the sight line, the second leaf folded back and the pivot pin through the ears."""
    leaf = [(-0.0060, 0.0285), (0.0060, 0.0285)]
    for a in np.linspace(0.0, np.pi, 9):
        leaf.append((0.0060 * np.cos(a), SIGHT_Y + 0.0018 + 0.0045 * np.sin(a)))
    hole = sh.circle(0.0, SIGHT_Y, APERTURE_R, 20)[::-1]
    m = extrude_z(np.array(leaf), [hole], REAR_NOTCH_Z - 0.0020, REAR_NOTCH_Z, chamfer_bevel(0.0003), "steel_dark")
    flat = np.array([(-0.0060, 0.0292), (0.0060, 0.0292), (0.0060, 0.0312), (-0.0060, 0.0312)])
    m.merge(extrude_z(flat, (), REAR_NOTCH_Z, REAR_NOTCH_Z + 0.0135, chamfer_bevel(0.0003), "steel_dark"))
    m.merge(_pin_x(-0.0112, 0.0112, 0.0300, REAR_NOTCH_Z - 0.0006, 0.0017))
    return m


# ------------------------------------------------------------------------------- barrel
def barrel_mesh():
    """The short barrel protruding from the nut (threaded at the crown) and the knurled barrel nut."""
    m = sh.revolve([(0.0045, CROWN_Z), (BARREL_R - 0.0008, CROWN_Z), (BARREL_R, CROWN_Z + 0.0008),
                    (BARREL_R, NUT_Z0 + 0.0010), (0.0, NUT_Z0 + 0.0010)], 16, "barrel", precision=True)
    for k in range(4):                                      # thread crests under the protector
        z = CROWN_Z + 0.0020 + k * 0.0022
        m.merge(sh.revolve([(BARREL_R - 0.0002, z), (BARREL_R + 0.0003, z + 0.0006), (BARREL_R - 0.0002, z + 0.0012)],
                           16, "barrel"))
    m.merge(sh.revolve([(0.0045, CROWN_Z), (0.0045, CROWN_Z + 0.0120)], 16, "steel_dark", outward=False))   # bore
    m.merge(sh.disc((0.0, 0.0, CROWN_Z + 0.0120), (0.0, 0.0, -1.0), 0.0045, 16, "steel_dark"))
    return m


def nut_mesh():
    """Barrel nut: knurled front section, a smooth collar against the receiver, the bore hole for the barrel."""
    m = _knurl_tube(NUT_R, NUT_R - 0.0007, 30, NUT_Z0, -0.0068, "barrel", chamfer=0.0014)
    m.merge(sh.revolve([(0.0, -0.0070), (NUT_R - 0.0010, -0.0070), (NUT_R - 0.0010, 0.0015), (0.0, 0.0015)], 16,
                       "barrel", precision=True))
    return m


def muzzle_brake_mesh():
    """Thread protector on the crown (node `muzzle_brake`: hidden while the barrel slot holds a suppressor)."""
    z0, z1 = CROWN_Z + 0.0125, CAP_Z1
    m = sh.revolve([(BARREL_R - 0.0002, z0), (CAP_R - 0.0004, z0), (CAP_R, z0 - 0.0004), (CAP_R, z1 + 0.0024),
                    (CAP_R - 0.0004, z1 + 0.0016), (CAP_R - 0.0012, z1), (0.0050, z1)], 16, "barrel", precision=True)
    m.merge(sh.revolve([(0.0050, z1), (0.0050, z1 + 0.0050)], 12, "steel_dark", outward=False, precision=True))
    return m


# ------------------------------------------------------------------------------- furniture
def _hg_top(z):
    """Height of the handguard's top edge: level along the ribbed front, scooped down behind it under the swivel."""
    u = np.clip((z - HG_RIB_Z1 + 0.0030) / 0.0360, 0.0, 1.0)
    return HG_TOP - 0.0190 * (u * u * (3 - 2 * u))


def _hg_section(chamfer=0.0, top=HG_TOP, flange=0.0):
    """CCW (x, y) U-section of the handguard round the receiver's lower front (top edge at `top`)."""
    e, r = 0.0005, 0.0085
    w, top, bot = HG_W - chamfer + flange, top - chamfer, HG_BOT + chamfer - flange
    pts = [(REC_W - e, top), (REC_W - e, REC_Y0_FRONT + e), (-REC_W + e, REC_Y0_FRONT + e), (-REC_W + e, top),
           (-w + 0.0012, top), (-w, top - 0.0012)]
    for a in np.linspace(np.pi, 1.5 * np.pi, 5):
        pts.append((-w + r + r * np.cos(a), bot + r + r * np.sin(a)))
    for a in np.linspace(1.5 * np.pi, 2 * np.pi, 5):
        pts.append((w - r + r * np.cos(a), bot + r + r * np.sin(a)))
    pts += [(w, top - 0.0012), (w - 0.0012, top)]
    return sh.ccw(np.array(pts))


def handguard_mesh():
    """Black polymer handguard round the lower front of the receiver: a slightly proud front flange, vertical
    moulded ribs along the front two thirds of both sides, a smooth rear scooped down under the sling swivel, two
    screw holes each side."""
    zs = [HG_Z0, HG_Z0 + 0.0012, 0.0050, 0.0062, HG_RIB_Z1 - 0.0040, 0.0960, 0.1020, 0.1080, 0.1140, 0.1200,
          HG_Z1 - 0.0012, HG_Z1]
    rings = []
    for k, z in enumerate(zs):
        chamfer = 0.0010 if k in (0, len(zs) - 1) else 0.0
        flange = 0.0007 if z <= 0.0050 else 0.0
        rings.append(_ring_xy(_hg_section(chamfer, _hg_top(z), flange), z))
    m = sh.loft(rings, "polymer")
    holes = (0.0265, 0.1075)
    for k in range(25):                                     # ribs: 1.6 mm wide, 3.2 mm pitch
        z = 0.0090 + k * 0.0032
        y0 = -0.0250
        if abs(z + 0.0008 - holes[0]) < 0.0055:
            y0 = -0.0150
        for s_ in (-1, 1):
            m.merge(_rib_y(HG_W, s_, z, 0.0016, y0, HG_TOP - 0.0030, 0.0008, "polymer"))
    for z in holes:                                         # screw holes: a counterbore ring round a dark hole
        for s_ in (-1, 1):
            ring = sh.revolve([(0.0026, 0.0006), (0.0036, 0.0006), (0.0042, 0.0), (0.0042, -0.0006),
                               (0.0026, -0.0006)], 14, "polymer")
            m.merge(_rot_x(ring, s_).translated((s_ * HG_W, -0.0197, z)))
            m.merge(sh.disc((s_ * (HG_W + 0.0002), -0.0197, z), (s_, 0.0, 0.0), 0.0027, 12, "steel_dark"))
    return m


def frame_mesh():
    """Grip frame (trigger housing) under the receiver: the side plates (the right one with the recessed marking
    panel), pins, the large strap trigger guard; the selector slide on the left (static, on `A`)."""
    prof = np.array([(FRAME_Z0, FRAME_Y1), (FRAME_Z0, FRAME_Y0 + 0.0060), (FRAME_Z0 + 0.0060, FRAME_Y0),
                     (FRAME_Z1 - 0.0030, FRAME_Y0), (FRAME_Z1, FRAME_Y0 + 0.0030), (FRAME_Z1, FRAME_Y1)])
    m = sh.extrude_x(prof, (), FRAME_W, round_bevel(0.0012, 2), "steel")
    # selector plate on the left (A - R - S): a raised rounded plate with the slider's slot and the slider at A
    plate = _stadium(0.1650, 0.2250, -0.0180, 0.0058, 4)
    m.merge(_pad_x(plate, FRAME_W, -1, 0.0009, "steel", chamfer=0.0004))
    slot = _stadium(0.1700, 0.2050, -0.0172, 0.0018, 3)
    m.merge(_pad_x(slot, FRAME_W + 0.0009, -1, 0.0001, "steel_dark", embed=0.0002, chamfer=0.00005))
    knob = sh.revolve([(0.0, 0.0024), (0.0016, 0.0024), (0.0024, 0.0016), (0.0024, -0.0004), (0.0, -0.0004)], 12,
                      "steel_dark")
    m.merge(_rot_x(knob, -1).translated((-FRAME_W - 0.0009, -0.0172, 0.1745)))
    for z, y, r in ((0.1433, -0.0160, 0.0021), (0.2433, -0.0187, 0.0030)):      # pins through the frame
        m.merge(_pin_x(-FRAME_W - 0.0006, FRAME_W + 0.0006, y, z, r))
    ring = sh.revolve([(0.0018, 0.0010), (0.0034, 0.0010), (0.0034, -0.0004), (0.0018, -0.0004)], 14, "steel_dark")
    m.merge(_rot_x(ring, -1).translated((-FRAME_W - 0.0006, -0.0187, 0.2433)))          # the takedown pin's ring
    # trigger guard: a bent steel strap from the frame down and back into the grip's front strap
    outer = [(0.1302, -0.0275), (0.1302, -0.0632)]
    for a in np.linspace(np.pi, 1.5 * np.pi, 4)[1:]:
        outer.append((0.1382 + 0.0080 * np.cos(a), -0.0632 + 0.0080 * np.sin(a)))
    outer += [(0.1808, -0.0712)]
    for a in np.linspace(1.5 * np.pi, 2 * np.pi, 4)[1:]:
        outer.append((0.1808 + 0.0070 * np.cos(a), -0.0642 + 0.0070 * np.sin(a)))
    outer += [(0.1878, -0.0275)]
    inner = [(0.1330, -0.0295), (0.1330, -0.0632)]
    for a in np.linspace(np.pi, 1.5 * np.pi, 4)[1:]:
        inner.append((0.1382 + 0.0052 * np.cos(a), -0.0632 + 0.0052 * np.sin(a)))
    inner += [(0.1808, -0.0684)]
    for a in np.linspace(1.5 * np.pi, 2 * np.pi, 4)[1:]:
        inner.append((0.1808 + 0.0042 * np.cos(a), -0.0642 + 0.0042 * np.sin(a)))
    inner += [(0.1850, -0.0295)]
    m.merge(sh.extrude_x(np.array(outer), [np.array(inner)], 0.0040, round_bevel(0.0009, 1), "steel"))
    return m


def _grip_back(y):
    """z of the grip panels' back: cut in under the grip safety, a palm swell low down."""
    u = np.clip((GRIP_Y0 - y) / (GRIP_Y0 - GRIP_Y1), 0.0, 1.0)
    swell = np.exp(-((u - 0.66) / 0.22) ** 2)
    k = np.clip((-0.0700 - y) / 0.0120, 0.0, 1.0)
    k = k * k * (3 - 2 * k)
    return 0.2368 + k * (0.0010 + 0.0015 * u + 0.0060 * swell)


def _grip_hw(y):
    u = np.clip((GRIP_Y0 - y) / (GRIP_Y0 - GRIP_Y1), 0.0, 1.0)
    return 0.0165 + 0.0009 * np.exp(-((u - 0.66) / 0.22) ** 2)


def _grip_section(y, toe=0.0):
    """(x, z) CCW C-section of the grip panels at height y: sides and a rounded back round the magazine well, open
    at the front where the steel front strap closes it (`toe` pushes the front edges forward: the bottom lip)."""
    hw, zb = _grip_hw(y), _grip_back(y)
    k = np.clip((y + 0.0470) / 0.0008, 0.0, 1.0)              # the panels step back under the steel cheeks
    zf = GRIP_PANEL_Z - toe + k * (GRIP_CHEEK_Z - GRIP_PANEL_Z)
    xi = MAG_HW + 0.0009
    zi = MAG_ORIGIN[2] + MAG_HD + 0.0009
    rb, ri = 0.0080, 0.0028
    pts = [(-xi, zf), (-hw + 0.0012, zf), (-hw, zf + 0.0012)]
    for a in np.linspace(np.pi, 0.5 * np.pi, 5):                # outer back corners (left, then right)
        pts.append((-hw + rb + rb * np.cos(a), zb - rb + rb * np.sin(a)))
    for a in np.linspace(0.5 * np.pi, 0.0, 5):
        pts.append((hw - rb + rb * np.cos(a), zb - rb + rb * np.sin(a)))
    pts += [(hw, zf + 0.0012), (hw - 0.0012, zf), (xi, zf)]
    for a in np.linspace(0.0, 0.5 * np.pi, 3):                  # inner (well) back corners
        pts.append((xi - ri + ri * np.cos(a), zi - ri + ri * np.sin(a)))
    for a in np.linspace(0.5 * np.pi, np.pi, 3):
        pts.append((-xi + ri + ri * np.cos(a), zi - ri + ri * np.sin(a)))
    return sh.ccw(np.array(pts))


def grip_mesh():
    """The pistol grip round the magazine well: moulded polymer panels (a C-section open at the front, closed by the
    steel front strap the magazine sits against), their L-shaped field of horizontal ribs and two screws, the ribbed
    block at the top of the front strap, the grip safety at the back and the heel catch (magazine release) on the left
    at the bottom, its tab hooked under the magazine's back."""
    ys = [GRIP_Y0, -0.0470, -0.0478, -0.0560, -0.0680, -0.0740, -0.0800, -0.0880, -0.0960, -0.1040, -0.1120, -0.1200,
          GRIP_Y1 + 0.0030, GRIP_Y1 + 0.0008, GRIP_Y1]
    rings = []
    for y in ys:
        toe = 0.0030 * np.clip((y - (GRIP_Y1 + 0.0120)) / -0.0120, 0.0, 1.0)
        sec = _grip_section(y, toe)
        rings.append(np.stack([sec[:, 0], np.full(len(sec), y), sec[:, 1]], 1))
    rings, soft = sd.round_end(rings, 0.0013)                   # the base rounded over (thin C-wall)
    m = sh.loft(rings, "polymer", soft_bands=soft)
    # the steel front strap closing the well's front
    strap = np.array([(-0.0136, GRIP_PANEL_Z + 0.0002), (-0.0136, GRIP_Z0 + 0.0012), (-0.0124, GRIP_Z0),
                      (0.0124, GRIP_Z0), (0.0136, GRIP_Z0 + 0.0012), (0.0136, GRIP_PANEL_Z + 0.0002)])
    m.merge(sh.loft([np.stack([strap[:, 0], np.full(len(strap), y), strap[:, 1]], 1) for y in (GRIP_Y0, -0.1255)],
                    "steel"))
    # ribbed steel cheeks either side of the well at the top of the front strap (where the middle finger sits)
    blk = np.array([(GRIP_Z0 + 0.0005, -0.0285), (GRIP_CHEEK_Z + 0.0004, -0.0285), (GRIP_CHEEK_Z + 0.0010, -0.0330),
                    (GRIP_CHEEK_Z + 0.0006, -0.0445), (GRIP_CHEEK_Z - 0.0020, -0.0478), (0.1900, -0.0478),
                    (GRIP_Z0 - 0.0002, -0.0445), (GRIP_Z0 - 0.0004, -0.0330)])
    for s_ in (-1, 1):
        m.merge(sh.extrude_x(blk, (), 0.0019, round_bevel(0.0010, 1), "steel_dark", x_center=s_ * 0.01455))
        for k in range(4):
            m.merge(_rib_y(0.0164, s_, 0.1915 + k * 0.0030, 0.0014, -0.0450, -0.0310, 0.0005, "steel_dark"))
    # rib field: an L of horizontal ribs (upper ones in front, lower ones right round to the swell)
    for k in range(20):
        y = -0.0575 - k * 0.0032
        z1 = 0.2270 if y > -0.0860 else _grip_back(y) - 0.0072
        hw = _grip_hw(y)
        for s_ in (-1, 1):
            m.merge(_rib_z(hw, s_, y - 0.0008, 0.0016, 0.2010, z1, 0.0006, "polymer"))
    for z, y in ((0.2300, -0.0715), (0.2295, -0.0995)):
        for s_ in (-1, 1):
            m.merge(_screw_x(s_ * _grip_hw(y), y, z, s_, 0.0026))
    # grip safety
    gs = np.array([(0.2340, -0.0350), (0.2455, -0.0350), (0.2478, -0.0400), (0.2484, -0.0500), (0.2476, -0.0640),
                   (0.2452, -0.0720), (0.2420, -0.0750), (0.2340, -0.0745)])
    m.merge(sh.extrude_x(gs, (), 0.0062, round_bevel(0.0018, 2), "steel"))
    for k in range(4):
        y = -0.0450 - k * 0.0055
        m.merge(sh.box(-0.0050, y, 0.2470, 0.0050, y + 0.0016, 0.2490, 0.0004, "steel"))
    # heel catch on the left: a lever plate with a rounded top and a ribbed lower end, bent in under the grip
    hw = _grip_hw(-0.1200) + 0.0004
    lever = [(0.2060, -0.1335), (0.2225, -0.1335), (0.2225, -0.1080)]
    for a in np.linspace(0.0, np.pi, 7)[1:-1]:
        lever.append((0.21425 + 0.00825 * np.cos(a), -0.1080 + 0.0050 * np.sin(a)))
    lever.append((0.2060, -0.1080))
    m.merge(_pad_x(np.array(lever), hw, -1, 0.0013, "steel_dark", embed=0.0010, chamfer=0.0005))
    for k in range(5):
        y = -0.1310 + k * 0.0032
        m.merge(_rib_z(hw + 0.0013, -1, y, 0.0014, 0.2075, 0.2210, 0.0005, "steel_dark"))
    m.merge(sh.box(-hw - 0.0013, GRIP_Y1 - 0.0040, 0.2060, -MAG_HW - 0.0012, GRIP_Y1 + 0.0004, 0.2225, 0.0006,
                   "steel_dark"))
    m.merge(sh.box(-0.0070, GRIP_Y1 - 0.0034, MAG_ORIGIN[2] + MAG_HD - 0.0008, 0.0070, GRIP_Y1 + 0.0004,
                   MAG_ORIGIN[2] + MAG_HD + 0.0050, 0.0005, "steel_dark"))                   # the hook behind the mag
    return m


def trigger_mesh():
    """Curved trigger blade (origin on its pivot)."""
    z, y0 = TRIGGER_PIVOT[2], FRAME_Y0
    blade = [(z - 0.0040, y0 + 0.0030), (z + 0.0030, y0 + 0.0030), (z + 0.0034, y0 - 0.0040),
             (z + 0.0020, y0 - 0.0110), (z - 0.0010, y0 - 0.0170), (z - 0.0050, y0 - 0.0210),
             (z - 0.0074, y0 - 0.0216), (z - 0.0068, y0 - 0.0196), (z - 0.0040, y0 - 0.0160),
             (z - 0.0018, y0 - 0.0100), (z - 0.0012, y0 - 0.0040)]
    return sh.extrude_x(np.array(blade), (), 0.0032, round_bevel(0.0011, 2), "steel_dark")


# ------------------------------------------------------------------------------- stock
def _arm_profile():
    """(z, y) side profile of one stock arm with the curved brace down to the butt plate, and its cutout."""
    a, b = 0.0600, 0.0860
    cz, cy = 0.4950, -0.1120
    hz, hy = HINGE[2], HINGE[1]
    outer = [(hz - 0.0020, 0.0150), (0.5600, -0.0170), (0.5600, -0.1120)]
    for t in np.linspace(np.pi / 2, 0.0, 9)[1:]:
        outer.append((cz + a * np.sin(t), cy + b * np.cos(t)))
    outer += [(0.4600, -0.0205), (0.3880, -0.0080), (hz + 0.0060, -0.0050)]
    for t in np.linspace(1.42 * np.pi, 0.80 * np.pi, 5):          # round the knuckle, bottom to top
        outer.append((hz + 0.0092 * np.cos(t), hy + 0.0092 * np.sin(t)))
    a2, b2 = a + 0.0085, b + 0.0085
    z_r = 0.5520
    t_end = np.arcsin(min(1.0, (z_r - cz) / a2))
    t_start = np.arccos(min(1.0, (-0.0186 - cy) / b2))
    p0 = (cz + a2 * np.sin(t_start), cy + b2 * np.cos(t_start))
    hole = [p0, (z_r, -0.0197)]
    for t in np.linspace(t_end, t_start, 8)[:-1]:
        hole.append((cz + a2 * np.sin(t), cy + b2 * np.cos(t)))
    return np.array(outer), sh.cw(np.array(hole))


def stock_mesh():
    """Metal folding stock, extended: two pressed arms on knuckles round the hinge pin (each with its long embossed
    groove and a rivet), the curved braces with their cutouts, the butt plate across the ends."""
    outer, hole = _arm_profile()
    m = Mesh()
    for s in (-1, 1):
        m.merge(sh.extrude_x(outer, [hole], 0.0012, chamfer_bevel(0.0005), "steel", x_center=s * STOCK_X))
        knuckle = sh.revolve([(0.0, -0.0050), (0.0094, -0.0050), (0.0100, -0.0044), (0.0100, 0.0044),
                              (0.0094, 0.0050), (0.0, 0.0050)], 20, "steel")
        m.merge(_rot_x(knuckle).translated((s * 0.0192, HINGE[1], HINGE[2])))
        z0, z1 = 0.3920, 0.4660
        top = lambda z: 0.0150 + (z - HINGE[2] + 0.002) * (-0.0305 / (0.5500 - HINGE[2] + 0.002))
        slot = np.array([(z, top(z) - 0.0080 + dy) for z, dy in ((z0, -0.0022), (z1, -0.0022), (z1 + 0.0022, 0.0),
                                                                  (z1, 0.0022), (z0, 0.0022), (z0 - 0.0022, 0.0))])
        m.merge(_pad_x(slot, STOCK_X + 0.0012, s, 0.0008, "steel"))
        r = sh.revolve([(0.0, 0.0012), (0.0024, 0.0008), (0.0030, 0.0), (0.0030, -0.0006), (0.0, -0.0006)], 10,
                       "steel")
        m.merge(_rot_x(r, s).translated((s * (STOCK_X + 0.0012), top(0.4700) - 0.0110, 0.4700)))
    # butt plate across the arms' ends
    # butt plate: a 12 mm curved steel plate (concave towards the shoulder at its middle), rounded heel and toe
    plate = np.array([(0.5580, -0.0110), (0.5690, -0.0120), (0.5740, -0.0190), (BUTT_Z, -0.0600), (0.5735, -0.1050),
                      (0.5690, -0.1240), (0.5620, -0.1300), (0.5560, -0.1280), (0.5600, -0.1100), (0.5625, -0.0640),
                      (0.5610, -0.0250)])
    m.merge(sh.extrude_x(plate, (), 0.0252, round_bevel(0.0022, 2), "steel"))
    for y in (-0.0350, -0.0750):                                   # cross rivets in the plate
        m.merge(_pin_x(-0.0262, 0.0262, y, 0.5680, 0.0020))
    return m


# ------------------------------------------------------------------------------- magazines
def _mag_ring(y, grow=0.0, r=0.0030):
    loop = sh.rounded_rect(-MAG_HW - grow, -MAG_HD - grow, MAG_HW + grow, MAG_HD + grow, r + grow, 2)
    return np.stack([loop[:, 0], np.full(len(loop), y), loop[:, 1]], 1)


def _round_9mm(material_case="brass"):
    """9x19 round along -Z (case base at z = 0): the brass case and the copper-jacketed bullet."""
    m = sh.revolve([(0.0, 0.0), (0.0049, 0.0), (0.0049, -0.0010), (0.0043, -0.0012), (0.0048, -0.0016),
                    (0.0048, -0.0192), (0.0045, -0.0192)], 12, material_case)
    m.merge(sh.revolve([(0.0045, -0.0192), (0.0045, -0.0222), (0.0036, -0.0262), (0.0016, -0.0292), (0.0, -0.0297)],
                       12, "steel_dark"))
    return m


def mag_mesh(extended=False):
    """Uzi stick magazine (mag-local: origin at the top of the body, +Y up, -Z front): a straight stamped body with
    two pressed ribs down each side, the folded feed lips with the top round, the catch notch on the back where the
    heel catch hooks it, the floor plate; the 50-round one is 72 mm longer."""
    bot = -MAG_LEN[extended]
    m = sh.loft([_mag_ring(0.0000, -0.0010, 0.0024), _mag_ring(-0.0030), _mag_ring(bot + 0.0010),
                 _mag_ring(bot, -0.0004)], "mag")
    lips = sh.box(-0.0080, -0.0010, -0.0150, 0.0080, 0.0028, 0.0150, 0.0010, "mag")
    m.merge(lips)
    m.merge(_round_9mm().translated((0.0028, 0.0060, 0.0160)))
    for s in (-1, 1):                                       # pressed ribs along both sides
        for zc in (-0.0072, 0.0072):
            m.merge(sd.rib_x(MAG_HW, s, (zc, bot + 0.0180), (zc, -0.0250), 0.0032, 0.0006, "mag", end=0.0030))
    y_catch = GRIP_BOTTOM_LOCAL - 0.0005                     # notch the heel catch hooks into
    m.merge(sh.box(-0.0060, y_catch - 0.0030, MAG_HD - 0.0006, 0.0060, y_catch + 0.0005, MAG_HD + 0.0002, 0.0002,
                   "mag_base"))
    plate = [_mag_ring(bot + 0.0004, 0.0009, 0.0030), _mag_ring(bot - 0.0040, 0.0011, 0.0030),
             _mag_ring(bot - 0.0055, 0.0003, 0.0030)]
    m.merge(sh.loft(plate, "mag_base"))
    m.merge(sh.box(-0.0050, bot - 0.0062, 0.0030, 0.0050, bot - 0.0050, MAG_HD - 0.0020, 0.0004, "mag_base"))
    return m


def shell_mesh():
    """Spent 9x19 brass case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0096), (0.0049, 0.0096), (0.0049, 0.0088), (0.0043, 0.0086), (0.0047, 0.0082),
            (0.0048, -0.0096), (0.0044, -0.0096), (0.0044, -0.0080), (0.0, -0.0080)]
    return sh.revolve(prof, 14, "brass", precision=True)
