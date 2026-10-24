"""FN P90 (TR pattern) geometry, VIEWMODEL_SPEC section 40. Design frame (metres): +X right, +Y up, +Z rearward;
bore axis on x = y = 0, z = 0 at the front face of the body. Real-world scale: overall 500 mm (butt plate to the flash
hider), barrel 263 mm (crown at z = -0.045, chamber at z = +0.218: a bullpup, the action sits in the butt).

Outlines are authored in the pixels of the reference photo (source/references/p90.jpg, right side; p90_shapes.P),
so the silhouette follows the real gun: the long butt, the thumbhole pistol grip, the trigger guard opening with the
selector disc under the trigger, the forward hand stop, the sight housing straddling the magazine on two straps with
its top and side picatinny rails (the TR housing), the integral irons on its top edges and folding backup irons on the
top rail (the sight line), the 50-round magazine
lying on the receiver from the housing back to the butt (a smoky translucent shell over two rows of 5.7x28 rounds
that turn through 90 deg at the front to feed down the bore), the ambidextrous cocking handles in their channels under
the housing front, the barrel and flash hider at the front, and the ejection chute in the bottom of the butt.

The moulded body is built from extrusions whose half width varies over the side profile (p90_shapes.extrude_var):
full at the receiver, slimmer down the grip, with large rounded edges. Every builder returns a meshkit.Mesh in the
design frame; gen_p90 moves meshes into node-local space."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p90_shapes import (P, PX, bevel, cyl_x, cyl_z, extrude_var, fillet, picatinny, py, pz, screw, smooth)
from p9_model import chamfer_bevel, extrude_z

# ---- key dimensions ---------------------------------------------------------------------------
BAND_W = 0.0265                  # receiver half width (the widest part with the mag: 53 mm)
BAND_TOP = py(345)               # receiver top under the magazine (+14.5 mm)
SEAM_Y = py(430)                 # receiver / lower frame parting line
HOUSING_W = 0.0240               # sight housing half width
HOUSING_TOP = py(128)            # +80.3 mm over the bore
RAIL_Z0, RAIL_Z1 = pz(1500), pz(1125)
RAIL_TOP = HOUSING_TOP + 0.0034 + 0.0049
OPTIC_Z = 0.0780                 # an optic's base centre on the top rail
SIDE_RAIL_Y, SIDE_RAIL_Z0, SIDE_RAIL_Z1 = py(187), pz(1420), pz(1165)
SIDE_RAIL_X = HOUSING_W + 0.0034 + 0.0049
LASER_Z = 0.5 * (SIDE_RAIL_Z0 + SIDE_RAIL_Z1)
STRAP_IN, STRAP_OUT = 0.0234, 0.0272     # the housing's rear straps either side of the magazine

# integral irons on the housing's top edges (static detail, too far off the bore to aim with)
SIDE_IRON_X = 0.0262
SIDE_IRON_Y = HOUSING_TOP + 0.0035
SIDE_NOTCH_Z = pz(1080)
SIDE_POST_Z = pz(1505)
# folding backup irons on the top rail (sight_rear / sight_iron; the eye aims through them, on the bore's centreline)
SIGHT_Y = RAIL_TOP + 0.0140      # aperture centre / post tip
REAR_SIGHT_Z = RAIL_Z1 - 0.0120  # the rear leaf at the rail's rear end
FRONT_SIGHT_Z = RAIL_Z0 + 0.0110

BARREL_R = 0.0082
CROWN_Z = pz(1680)               # -0.0448
MUZZLE_Z = pz(1690)              # front face of the flash hider (-0.0479)
FH_Z1 = pz(1602)                 # flash hider rear end

# magazine (node `mag`: pivot at the front of its bottom face, where it hinges in the front latch)
MAG_HW = 0.0232
MAG_Y0, MAG_Y1 = py(342), py(265)
MAG_Z0, MAG_Z1 = pz(1422), pz(556)
MAG_H, MAG_L = MAG_Y1 - MAG_Y0, MAG_Z1 - MAG_Z0
MAG_ORIGIN = np.array([0.0, MAG_Y0, MAG_Z0])
ROUND_PITCH = 0.0079             # 5.7x28 rim diameter: the rounds lie across the magazine side by side
ROW_Y = (0.0087, 0.0157)         # mag-local heights of the two staggered rows (photo: rows at y px 313 / 290)

# grip, trigger, selector, cocking handles, ejection
GRIP_TOP = np.array([0.0, py(452), pz(1004)])
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(8.0)), np.sin(np.radians(8.0))])      # down and a little back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(8.0)), -np.cos(np.radians(8.0))])
TRIGGER_PIVOT = np.array([0.0, py(459), pz(1104)])
SELECTOR_PIVOT = np.array([0.0, py(578), pz(1140)])
BOLT_TRAVEL = 0.0680             # cocking handle stroke (non-reciprocating: only the hand moves it)
KNOB_X0, KNOB_X1 = 0.0216, 0.0294
EJECTION = np.array([0.0, -0.0905, 0.2735])              # chute in the bottom of the butt, behind the grip
EJECT_DIR = np.array([0.08, -0.95, 0.31])                # cases drop out downwards (and a little back)
LIP_BOTTOM = py(592)
UNDER_Z = pz(1512)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# ------------------------------------------------------------------------------------- body widths
# Affine half widths keep every extrusion's side face flat (a drafted moulding), so the big caps shade cleanly.
def hw_butt(z, y):
    return 0.0255


def hw_frame(z, y):
    """Lower frame: 43 mm across under the receiver, drafted in to 34 mm at the bottom of the trigger guard."""
    return 0.0200 + 0.050 * (y + 0.040)


def hw_forend(z, y):
    return 0.0216 + 0.040 * (y + 0.020)


# ----------------------------------------------------------------------------------- outlines (px)
FRAME = [(470, 418), (1330, 418), (1330, 518), (1404, 518), (1401, 530), (1391, 548), (1384, 590), (1372, 628),
         (1350, 660), (1310, 696), (1270, 713), (1235, 718), (1150, 718), (1131, 712), (1130, 690), (1138, 660),
         (1146, 628), (1140, 610), (1122, 603), (1068, 603), (1040, 619), (1005, 650), (965, 677), (920, 698),
         (870, 712), (830, 716), (720, 716), (690, 702), (620, 678), (560, 659), (505, 641), (472, 638)]
FRAME_R = [0, 0, 0, 0, 6, 10, 20, 30, 30, 30, 20, 16, 14, 10, 12, 20, 10, 6, 14, 20, 30, 30, 30, 30, 20, 20, 14, 18,
           30, 20, 10, 0]
REAR_HOLE = [(735, 452), (895, 452), (926, 478), (929, 512), (912, 545), (790, 600), (742, 594), (708, 566),
             (702, 520), (712, 474)]
REAR_HOLE_R = [20, 24, 18, 18, 26, 18, 20, 26, 30, 22]
TRIGGER_HOLE = [(1094, 456), (1245, 456), (1286, 488), (1291, 530), (1265, 576), (1230, 585), (1104, 566),
                (1091, 545)]
TRIGGER_HOLE_R = [10, 30, 22, 26, 22, 22, 14, 10]
FOREND = [(1295, 418), (1545, 418), (1547, 450), (1533, 592), (1492, 592), (1486, 535), (1474, 521), (1295, 521)]
FOREND_R = [0, 0, 10, 14, 14, 12, 10, 0]
BAND = [(468, 345), (1545, 345), (1545, 430), (1446, 430), (1446, 382), (1081, 382), (1081, 430), (468, 430)]
BAND_R = [0, 0, 0, 0, 12, 12, 0, 0]
HOUSING = [(985, 262), (998, 228), (1016, 192), (1040, 150), (1066, 128), (1522, 128), (1533, 142), (1539, 190),
           (1545, 250), (1545, 343), (1432, 343), (1432, 262), (1415, 255), (1125, 216), (1108, 236), (1104, 262)]
HOUSING_R = [0, 30, 30, 16, 14, 14, 16, 30, 8, 0, 0, 8, 6, 12, 10, 0]
HOUSING_WINDOW = [(1048, 168), (1150, 168), (1150, 204), (1062, 204)]
STRAP = [(976, 380), (979, 300), (986, 262), (998, 236), (1110, 236), (1104, 262), (1093, 320), (1087, 380)]
STRAP_R = [6, 30, 20, 6, 6, 20, 30, 6]
CATCH = [(462, 263), (548, 265), (557, 290), (552, 345), (462, 345)]
CATCH_R = [0, 12, 10, 0, 0]
TAB = [(562, 338), (640, 338), (646, 360), (636, 384), (568, 384)]


# ------------------------------------------------------------------------------------------ body
def butt_mesh():
    """The long butt: a moulded block with a full round on every edge, its butt plate (a separate moulding, the
    seam just in front of it), the sling slot at the bottom rear and the screws on both sides."""
    m = extrude_var(fillet([(52, 263), (470, 263), (472, 640), (52, 651)], [26, 0, 0, 24], min_convex=26), (),
                    hw_butt, bevel(0.0068, 4), "polymer")
    plate = fillet([(39, 264), (58, 264), (58, 650), (42, 650)], [12, 0, 0, 12], min_convex=9)
    m.merge(extrude_var(plate, (), 0.0250, bevel(0.0026, 3), "polymer"))
    for s in (-1, 1):
        slot = fillet([(112, 619), (200, 619), (200, 628), (112, 628)], 4)
        m.merge(extrude_var(slot, (), 0.0004, ((0.0, 0.0),), "steel_dark", x_center=s * 0.0255))
        for x, y in ((105, 358), (305, 342), (215, 610), (440, 590)):
            z, yy = P(x, y)
            m.merge(screw((s * 0.0256, yy, z), (s, 0, 0)))
    return m


def receiver_mesh():
    """The receiver under the magazine: a full-width band from the butt to the front face, notched under the housing
    front for the cocking handles' channel (the channel floor is the lower frame / forend, its back wall the core),
    the guide rods the handles run on, the magazine catch block behind the magazine with the release tabs on both
    sides and the QD sling stud on the left."""
    m = extrude_var(fillet(BAND, BAND_R), (), BAND_W, bevel(0.0028, 3), "polymer")
    m.merge(sh.box(-0.0215, py(424), pz(1451), 0.0215, py(377), pz(1076), 0.0010, "polymer"))         # channel core
    tab = fillet(TAB, [10, 8, 10, 12, 10])
    for s in (-1, 1):
        m.merge(cyl_z(0.0030, pz(1445), pz(1086), "steel", 10, x=s * 0.0233, y=py(400), chamfer=0.0008))
        m.merge(extrude_var(tab, (), 0.0016, bevel(0.0010, 2), "polymer", x_center=s * (BAND_W + 0.0012)))
    m.merge(extrude_var(fillet(CATCH, CATCH_R, min_convex=10), (), MAG_HW + 0.0010, bevel(0.0028, 3), "polymer"))
    m.merge(sh.box(-0.0150, py(300), pz(560), 0.0150, py(276), pz(554), 0.0006, "steel"))           # catch latch
    # QD sling stud and its loop on the left (mount_charm hangs the charm here)
    m.merge(cyl_x(0.0046, -BAND_W - 0.0062, -BAND_W + 0.0010, (py(420), 0.2550), "steel", 10, 0.0006))
    loop = sh.revolve([(0.0040, -0.0009), (0.0050, -0.0009), (0.0050, 0.0009), (0.0040, 0.0009), (0.0040, -0.0009)],
                      12, "steel")
    m.merge(sh.place(loop, origin=(-BAND_W - 0.0080, py(420) - 0.0045, 0.2550), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                     z_axis=(1, 0, 0)))
    for s in (-1, 1):
        for x, y in ((663, 400), (1000, 410)):
            z, yy = P(x, y)
            m.merge(screw((s * (BAND_W + 0.0001), yy, z), (s, 0, 0), r=0.0021))
    return m


def frame_mesh():
    """Lower frame (grip module): the thumbhole pistol grip, the trigger guard opening (the shooting hand's fingers go
    through it, the trigger at its top rear), the trigger guard's curved bottom that hooks back under the grip, the
    ejection chute in the sloping bottom behind the grip; screws on both sides."""
    m = extrude_var(fillet(FRAME, FRAME_R, min_convex=14),
                    (fillet(REAR_HOLE, REAR_HOLE_R), fillet(TRIGGER_HOLE, TRIGGER_HOLE_R)),
                    hw_frame, bevel(0.0040, 3), "polymer")
    n = np.array([0.0, -0.951, 0.307])                     # outward normal of the sloping bottom
    port = sh.box(-0.0068, -0.0008, -0.0095, 0.0068, 0.00035, 0.0095, 0.0006, "steel_dark")
    m.merge(sh.place(port, origin=EJECTION + 0.0016 * n, x_axis=(1, 0, 0), y_axis=n, z_axis=np.cross((1, 0, 0), n)))
    for s in (-1, 1):
        for x, y in ((663, 481), (992, 483), (923, 635), (727, 650), (1235, 652)):
            z, yy = P(x, y)
            m.merge(screw((s * (hw_frame(z, yy) + 0.0001), yy, z), (s, 0, 0)))
    return m


def forend_mesh():
    """Forend under the barrel: the support hand's place, closed at the front by the hand stop (the lip that keeps the
    hand behind the muzzle) with a short accessory rail under it; screws on both sides."""
    m = extrude_var(fillet(FOREND, FOREND_R, min_convex=14), (), hw_forend, bevel(0.0040, 3), "polymer")
    m.merge(sh.place(picatinny(-0.0085, 0.0085, "steel", base_w=0.0080, base_h=0.0020),
                     origin=(0.0, LIP_BOTTOM + 0.0012, UNDER_Z), x_axis=(-1, 0, 0), y_axis=(0, -1, 0),
                     z_axis=(0, 0, 1)))
    for s in (-1, 1):
        for x, y in ((1367, 480), (1527, 483)):
            z, yy = P(x, y)
            m.merge(screw((s * (hw_forend(z, yy) + 0.0001), yy, z), (s, 0, 0)))
    return m


def housing_mesh():
    """Sight housing (TR pattern): a deep block over the front of the magazine, standing on the receiver in front of
    the magazine and on two straps either side of it at the rear (the magazine slides in under it); the rear window,
    the top rail (optics), side rails left and right (the laser on the right one) with their screws, the raised
    bosses on the front block and the sight adjustment cap on the right."""
    m = extrude_var(fillet(HOUSING, HOUSING_R, min_convex=12), (fillet(HOUSING_WINDOW, 12),), HOUSING_W,
                    bevel(0.0034, 3), "polymer")
    strap = fillet(STRAP, STRAP_R, min_convex=6)
    for s in (-1, 1):
        m.merge(extrude_var(strap, (), 0.5 * (STRAP_OUT - STRAP_IN), bevel(0.0014, 2), "polymer",
                            x_center=s * 0.5 * (STRAP_OUT + STRAP_IN)))
    m.merge(picatinny(RAIL_Z0, RAIL_Z1).translated((0.0, HOUSING_TOP - 0.0002, 0.0)))
    for s in (-1, 1):
        rail = picatinny(SIDE_RAIL_Z0, SIDE_RAIL_Z1, base_w=0.0085,
                         teeth=(SIDE_RAIL_Z0 + 0.0095, SIDE_RAIL_Z1 - 0.0095))
        m.merge(sh.place(rail, origin=(s * (HOUSING_W - 0.0002), SIDE_RAIL_Y, 0.0), x_axis=(0, -s, 0),
                         y_axis=(s, 0, 0), z_axis=(0, 0, 1)))
        for z in (SIDE_RAIL_Z0 + 0.0048, SIDE_RAIL_Z1 - 0.0048):
            m.merge(screw((s * (HOUSING_W + 0.0032), SIDE_RAIL_Y, z), (s, 0, 0), r=0.0021))
        boss = fillet([(1447, 276), (1528, 276), (1528, 338), (1447, 338)], 10)
        m.merge(extrude_var(boss, (), 0.0010, bevel(0.0007, 2), "polymer", x_center=s * (HOUSING_W + 0.0006)))
    cap = sh.revolve([(0.0, 0.0024), (0.0030, 0.0024), (0.0030, 0.0030), (0.0042, 0.0030), (0.0046, 0.0026),
                      (0.0046, 0.0018), (0.0086, 0.0018), (0.0092, 0.0010), (0.0092, -0.0010)], 20, "steel_dark")
    zc, yc = P(1461, 165)
    m.merge(sh.place(cap, origin=(HOUSING_W + 0.0005, yc, zc), x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0)))
    m.merge(side_irons_mesh())
    return m


# ------------------------------------------------------------------------------- barrel, muzzle
def barrel_mesh():
    """The barrel stub out of the front face: the locking collar, the barrel, the thread under the flash hider, the
    crown and its bore."""
    m = sh.revolve([(0.0029, CROWN_Z - 0.0010), (0.0029, 0.0100)], 12, "barrel", outward=False)
    m.merge(sh.revolve([(0.0029, CROWN_Z), (0.0060, CROWN_Z), (0.0070, CROWN_Z + 0.0010), (0.0070, FH_Z1 - 0.0005),
                        (BARREL_R, FH_Z1 + 0.0010), (BARREL_R, pz(1578)), (0.0102, pz(1575)), (0.0104, pz(1572)),
                        (0.0104, pz(1543)), (0.0100, 0.0010), (0.0, 0.0010)], 20, "barrel"))
    return m


def muzzle_brake_mesh():
    """P90 flash hider: a sleeve with two rings, the slotted cage at the front, chamfered crown face."""
    r = 0.0098
    z1, z0 = FH_Z1, MUZZLE_Z
    prof = [(0.0068, z1), (0.0090, z1), (r, z1 - 0.0012), (r, pz(1614)), (0.0088, pz(1616)), (0.0088, pz(1624)),
            (r, pz(1626)), (r, pz(1633)), (0.0088, pz(1635)), (0.0088, pz(1643)), (r, pz(1645)),
            (r, z0 + 0.0012), (0.0090, z0), (0.0056, z0), (0.0056, z0 + 0.0060), (0.0068, z0 + 0.0060),
            (0.0068, z1)]
    m = sh.revolve(prof, 20, "barrel")
    for k in range(4):                                  # cage slots
        a = np.pi / 4 + k * np.pi / 2
        c, s = np.cos(a), np.sin(a)
        slot = sh.box(-0.0018, 0.0084, z0 - 0.0006, 0.0018, r + 0.0004, pz(1652), 0.0003, "steel_dark")
        m.merge(sh.place(slot, x_axis=(c, s, 0), y_axis=(-s, c, 0), z_axis=(0, 0, 1)))
    return m


# ------------------------------------------------------------------------------------ sights
def side_irons_mesh():
    """The housing's integral irons on its top edges: a notch at the rear and a post at the front on each side."""
    m = Mesh()
    for s in (-1, 1):
        xc = s * SIDE_IRON_X
        y0, y1 = HOUSING_TOP - 0.0050, SIDE_IRON_Y + 0.0022
        notch = np.array([(-0.0034, y0), (0.0034, y0), (0.0034, y1 - 0.0006), (0.0028, y1), (0.0008, y1),
                          (0.0008, SIDE_IRON_Y), (-0.0008, SIDE_IRON_Y), (-0.0008, y1), (-0.0028, y1),
                          (-0.0034, y1 - 0.0006)]) + np.array([xc, 0.0])
        m.merge(extrude_z(notch, (), SIDE_NOTCH_Z - 0.0030, SIDE_NOTCH_Z + 0.0040, chamfer_bevel(0.0004), "polymer"))
        base = np.array([(-0.0032, y0), (0.0032, y0), (0.0032, SIDE_IRON_Y - 0.0035), (0.0020, SIDE_IRON_Y - 0.0030),
                         (-0.0020, SIDE_IRON_Y - 0.0030), (-0.0032, SIDE_IRON_Y - 0.0035)]) + np.array([xc, 0.0])
        m.merge(extrude_z(base, (), SIDE_POST_Z - 0.0035, SIDE_POST_Z + 0.0035, chamfer_bevel(0.0004), "polymer"))
        blade = np.array([(-0.0008, SIDE_IRON_Y - 0.0034), (0.0008, SIDE_IRON_Y - 0.0034), (0.0008, SIDE_IRON_Y - 0.0003),
                          (0.0005, SIDE_IRON_Y), (-0.0005, SIDE_IRON_Y), (-0.0008, SIDE_IRON_Y - 0.0003)]) + np.array([xc, 0.0])
        m.merge(extrude_z(blade, (), SIDE_POST_Z - 0.0011, SIDE_POST_Z + 0.0011, chamfer_bevel(0.0002), "steel_dark"))
    return m


def _arc(cx, cy, r, a0, a1, n):
    t = np.radians(np.linspace(a0, a1, n))
    return [(cx + r * np.cos(a), cy + r * np.sin(a)) for a in t]


def _rail_clamp(z0, z1):
    """A folding sight's clamp on the top rail: the body over the rail with its jaws down the rail's sides and the
    cross bolt's nut on the left."""
    body = np.array([(-0.0126, RAIL_TOP - 0.0012), (0.0126, RAIL_TOP - 0.0012), (0.0126, RAIL_TOP + 0.0032),
                     (0.0100, RAIL_TOP + 0.0058), (-0.0100, RAIL_TOP + 0.0058), (-0.0126, RAIL_TOP + 0.0032)])
    m = extrude_z(body, (), z0, z1, chamfer_bevel(0.0007), "polymer")
    for s in (-1, 1):
        jaw = np.array([(-0.0010, HOUSING_TOP + 0.0006), (0.0010, HOUSING_TOP + 0.0006), (0.0010, RAIL_TOP - 0.0008),
                        (-0.0010, RAIL_TOP - 0.0008)]) + np.array([s * 0.0117, 0.0])
        m.merge(extrude_z(jaw, (), z0 + 0.0015, z1 - 0.0015, ((0.0, 0.0),), "polymer"))
    zc = 0.5 * (z0 + z1)
    m.merge(cyl_x(0.0032, -0.0150, -0.0125, (RAIL_TOP - 0.0004, zc), "steel_dark", 6, chamfer=0.0004))
    return m


def rear_sight_mesh():
    """Folding rear sight on the top rail (node `sight_rear`, hidden with an optic): the clamp, the leaf standing up
    with its ghost-ring aperture on the sight line, the protective wings and the hinge."""
    zr = REAR_SIGHT_Z
    m = _rail_clamp(zr - 0.0090, RAIL_Z1 - 0.0005)
    leaf = np.array([(-0.0070, RAIL_TOP + 0.0050), (0.0070, RAIL_TOP + 0.0050), (0.0058, SIGHT_Y - 0.0060)] +
                    _arc(0.0, SIGHT_Y, 0.0062, -40.0, 220.0, 11) + [(-0.0058, SIGHT_Y - 0.0060)])
    hole = np.array(_arc(0.0, SIGHT_Y, 0.0029, 360.0, 0.0, 13)[:-1])
    m.merge(extrude_z(leaf, [hole], zr - 0.0016, zr + 0.0016, chamfer_bevel(0.0004), "polymer"))
    for s in (-1, 1):
        wing = np.array([(0.0074, RAIL_TOP + 0.0050), (0.0098, RAIL_TOP + 0.0050), (0.0094, SIGHT_Y + 0.0028),
                         (0.0084, SIGHT_Y + 0.0040), (0.0074, SIGHT_Y + 0.0032)]) * np.array([s, 1.0])
        m.merge(extrude_z(sh.ccw(wing), (), zr - 0.0030, zr + 0.0045, chamfer_bevel(0.0005), "polymer"))
    m.merge(cyl_x(0.0016, -0.0074, 0.0074, (RAIL_TOP + 0.0056, zr + 0.0030), "steel_dark", 6))
    return m


def front_post_mesh():
    """Folding front sight on the top rail (node `sight_iron`): the clamp, the tower, the post with its tip on the
    sight line between two ears (which stop 1 mm under it)."""
    zf = FRONT_SIGHT_Z
    m = _rail_clamp(zf - 0.0070, zf + 0.0085)
    tower = np.array([(-0.0060, RAIL_TOP + 0.0040), (0.0060, RAIL_TOP + 0.0040), (0.0060, RAIL_TOP + 0.0060),
                      (0.0042, SIGHT_Y - 0.0085), (-0.0042, SIGHT_Y - 0.0085), (-0.0060, RAIL_TOP + 0.0060)])
    m.merge(extrude_z(tower, (), zf - 0.0030, zf + 0.0050, chamfer_bevel(0.0005), "polymer"))
    for s in (-1, 1):
        ear = np.array([(0.0034, SIGHT_Y - 0.0090), (0.0068, SIGHT_Y - 0.0090), (0.0064, SIGHT_Y - 0.0020),
                        (0.0052, SIGHT_Y - 0.0010), (0.0034, SIGHT_Y - 0.0024)]) * np.array([s, 1.0])
        m.merge(extrude_z(sh.ccw(ear), (), zf - 0.0026, zf + 0.0040, chamfer_bevel(0.0004), "polymer"))
    post = np.array([(-0.0009, SIGHT_Y - 0.0090), (0.0009, SIGHT_Y - 0.0090), (0.0009, SIGHT_Y - 0.0004),
                     (0.0005, SIGHT_Y), (-0.0005, SIGHT_Y), (-0.0009, SIGHT_Y - 0.0004)])
    m.merge(extrude_z(post, (), zf - 0.0009, zf + 0.0009, chamfer_bevel(0.0002), "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_mesh():
    """The ambidextrous cocking handles (node `bolt`, rest = forward): a hooked knob on each side riding its guide
    rod in the channel under the housing front, joined through the receiver; the round pin on each knob."""
    knob = fillet([(1446, 384), (1446, 425), (1414, 427), (1384, 421), (1361, 405), (1366, 391), (1392, 383)],
                  [6, 8, 14, 12, 8, 10, 12])
    m = Mesh()
    for s in (-1, 1):
        m.merge(extrude_var(knob, (), 0.5 * (KNOB_X1 - KNOB_X0), bevel(0.0016, 2), "steel_dark",
                            x_center=s * 0.5 * (KNOB_X0 + KNOB_X1)))
        zc, yc = P(1420, 404)
        m.merge(cyl_x(0.0024, *sorted((s * (KNOB_X1 - 0.0004), s * (KNOB_X1 + 0.0006))), (yc, zc), "steel", 12,
                      0.0003))
    return m


def trigger_mesh():
    """Wide curved trigger hanging from the top of the guard opening (pivot at the top)."""
    blade = fillet([(1096, 459), (1121, 459), (1124, 482), (1130, 506), (1140, 528), (1160, 546), (1152, 553),
                    (1132, 547), (1114, 530), (1104, 506), (1098, 482)], [3, 3, 20, 20, 16, 4, 4, 14, 20, 20, 10])
    return extrude_var(blade, (), 0.0058, bevel(0.0014, 2), "steel_dark")


def selector_mesh():
    """Rotary fire selector disc under the trigger (axis vertical, ribbed rim, the shooter's thumb or finger rolls
    it); node origin at its centre."""
    r = 0.0125
    m = sh.revolve([(0.0, -0.0022), (r - 0.0010, -0.0022), (r, -0.0014), (r, 0.0014), (r - 0.0010, 0.0022),
                    (0.0, 0.0022)], 20, "steel_dark")
    m = sh.place(m, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))
    for k in range(14):
        a = 2 * np.pi * k / 14
        rib = sh.box(-0.0006, -0.0018, r - 0.0006, 0.0006, 0.0018, r + 0.0009, 0.0, "steel_dark")
        m.merge(sh.place(rib, x_axis=(np.cos(a), 0, -np.sin(a)), y_axis=(0, 1, 0), z_axis=(np.sin(a), 0, np.cos(a))))
    return m


# ------------------------------------------------------------------------------------ magazine
def _round(seg=6):
    """One 5.7x28 round along +X (head at x = 0, bullet tip at x = -0.0405): bottlenecked brass case, pointed
    bullet."""
    case = sh.revolve([(0.0, 0.0), (0.00395, 0.0), (0.00390, -0.0215), (0.00322, -0.0245), (0.00300, -0.0285)],
                      seg, "brass", start_angle=np.pi / seg)
    case.merge(sh.revolve([(0.00300, -0.0285), (0.00215, -0.0345), (0.0, -0.0405)], seg, "steel_dark",
                          start_angle=np.pi / seg))
    return sh.place(case, x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))


def mag_rounds():
    """Positions (mag-local centre of the round's length, yaw in degrees about Y; 0 = across, bullet left) of the 50
    rounds: two staggered rows lying across the magazine, then three turning on the feed ramp at the front until the
    top one points down the bore under the feed lips."""
    out = []
    z_first = 0.0545
    for k in range(24):
        out.append((np.array([0.0, ROW_Y[1], z_first + k * ROUND_PITCH]), 0.0))
    for k in range(23):
        out.append((np.array([0.0, ROW_Y[0], z_first + 0.5 * ROUND_PITCH + k * ROUND_PITCH]), 0.0))
    out += [(np.array([0.0, 0.0150, 0.0440]), 28.0), (np.array([0.0005, 0.0162, 0.0335]), 58.0),
            (np.array([0.0, 0.0172, 0.0245]), 90.0)]
    return out


def mag_mesh():
    """The 50-round magazine (mag-local: origin at the front of its bottom face): the translucent smoky shell (outer
    skin and the inner skin of its walls, BLEND `mag_window`), the opaque front latch block with its hook, the rear end
    with the catch notches, the follower behind the last round, the feed insert and the 50 rounds."""
    L, H, W = MAG_L, MAG_H, MAG_HW
    sec = sh.rounded_rect(-W, 0.0, W, H, 0.0032, 3)
    m = extrude_z(sec, (), 0.0060, L - 0.0060, bevel(0.0018), "mag_window")
    inner = extrude_z(sh.offset_loop(sec, 0.0015), (), 0.0075, L - 0.0075, ((0.0, 0.0),), "mag_window")
    inner.f = [tuple(reversed(f)) for f in inner.f]
    m.merge(inner)
    front = sh.rounded_rect(-W - 0.0004, -0.0003, W + 0.0004, H + 0.0003, 0.0034, 3)
    m.merge(extrude_z(front, (), 0.0, 0.0078, bevel(0.0012), "mag"))
    hook = np.array([(-0.0110, H - 0.0010), (0.0110, H - 0.0010), (0.0110, H + 0.0024), (0.0080, H + 0.0030),
                     (-0.0080, H + 0.0030), (-0.0110, H + 0.0024)])
    m.merge(extrude_z(hook, (), -0.0055, 0.0060, chamfer_bevel(0.0006), "mag"))
    rear = sh.rounded_rect(-W - 0.0003, -0.0002, W + 0.0003, H + 0.0002, 0.0034, 3)
    m.merge(extrude_z(rear, (), L - 0.0080, L, bevel(0.0012), "mag"))
    for s in (-1, 1):                                    # catch notches on the rear end
        x0, x1 = sorted((s * (W + 0.0002), s * (W + 0.0010)))
        m.merge(sh.box(x0, 0.0060, L - 0.0068, x1, 0.0150, L - 0.0018, 0.0002, "steel_dark"))
    m.merge(sh.box(-W + 0.0018, 0.0019, L - 0.0145, W - 0.0018, H - 0.0019, L - 0.0080, 0.0012, "mag"))   # follower
    m.merge(sh.box(-W + 0.0018, 0.0016, 0.0070, W - 0.0018, 0.0070, 0.0300, 0.0012, "mag"))           # feed ramp
    rnd = _round()
    for c, yaw in mag_rounds():
        q = sh.place(rnd.translated((0.0200, 0.0, 0.0)), x_axis=(np.cos(np.radians(yaw)), 0, -np.sin(np.radians(yaw))),
                     y_axis=(0, 1, 0), z_axis=(np.sin(np.radians(yaw)), 0, np.cos(np.radians(yaw))))
        m.merge(q.translated(c))
    return m


def shell_mesh(scale=1.15):
    """Spent 5.7x28 case (bottlenecked brass), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0144), (0.0034, 0.0144), (0.00395, 0.0138), (0.00395, 0.0130), (0.0033, 0.0126),
            (0.0033, 0.0118), (0.0039, 0.0112), (0.0038, -0.0070), (0.0032, -0.0098), (0.0032, -0.0144),
            (0.0028, -0.0144), (0.0028, -0.0120), (0.0, -0.0120)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 16, "brass", precision=True)
