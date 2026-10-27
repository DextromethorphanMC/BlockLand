"""STEN Mk II submachine gun geometry (VIEWMODEL_SPEC section 40). Design frame (metres): +X right, +Y up, +Z rearward;
bore axis on x = y = 0 (the barrel and the bolt are concentric with the receiver tube), z = 0 at the front end of the
receiver tube. Real-world scale: overall 0.760 m (crown to butt plate), barrel 0.196 m, receiver tube 45 mm.

An original procedural model of the wartime pattern: a plain rolled-steel receiver tube with its openings really cut
through the wall (the magazine opening on the left, the ejection port on the right opposite it, the L-shaped cocking
slot behind the port with the safety notch at its rear end), the barrel nut and the short perforated barrel sleeve
(drilled holes, the barrel seen through them) with the front sight blade on the front of the tube, the rotating
magazine housing collar with the side-mounted housing sticking out to the LEFT, the 32-round double-stack magazine
lying horizontally in it, the stamped trigger housing under the tube with the plain strap trigger guard, the trigger
and the cross-bolt selector button, the wooden pistol grip of the late pattern (Mk V) on its bracket behind the guard, the fixed peep sight on the tube's top rear, the end cap and the skeleton "T"
stock: a single steel tube to a flat butt strap. A short clamp rail on top of the tube carries the optics (our
addition, like the other guns' rails). Every builder returns a meshkit.Mesh in the design frame; gen_sten moves
meshes into node-local space."""
import numpy as np

import shapes as sh
import smg_detail as sd
import sten_geom as sg
from ak_model import _cyl_x, _cyl_z, _rivet
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
R_OUT, R_IN = 0.0225, 0.0207       # receiver tube (45 mm, 1.8 mm wall)
REC_Z0, REC_Z1 = 0.0000, 0.3180     # tube front end (barrel nut) / rear end (inside the end cap)
CAP_Z0, CAP_Z1 = 0.3140, 0.3340     # end cap (return spring cap) over the rear of the tube
BREECH_Z = 0.0460                  # barrel breech face (the bolt face closes here)
BARREL_R = 0.0090
CROWN_Z = BREECH_Z - 0.1960        # 196 mm barrel
MUZZLE_Z = CROWN_Z
NUT_Z0, NUT_Z1, NUT_R = -0.0300, 0.0040, 0.0238
SLEEVE_R, SLEEVE_T = 0.0160, 0.0012
SLEEVE_Z0, SLEEVE_Z1 = -0.1360, -0.0340
HOLE_R = 0.0032                    # drilled cooling holes: 3 staggered rows of 6
CAP_FRONT_Z0, CAP_FRONT_Z1 = -0.1420, -0.1310   # sleeve front cap (the barrel passes through)
COLLAR_Z0, COLLAR_Z1 = -0.1492, -0.1416         # muzzle collar (node muzzle_brake)
SIGHT_Y = 0.0365                   # sight line: front blade top / peep aperture centre
FRONT_Z = 0.0140                   # front blade (middle)
REAR_Z = 0.2975                    # peep plate (middle)
PEEP_R = 0.0038
# magazine (32-round double stack, lying horizontally in the housing on the left; mag-local: origin at the top of the
# feed lips, the magazine along -X)
MAG_ZC = 0.0680
MAG_HZ, MAG_HY = 0.0172, 0.0108    # body half depth (along the bore) / half thickness
MAG_TOP_X = -0.0120                # feed lips (design x)
MAG_LEN = 0.2480
MAG_TAPER = 0.0300                 # the double column narrows to the single feed over this length
MAG_TOP_HY = 0.0062
MAG_ORIGIN = np.array([MAG_TOP_X, 0.0, MAG_ZC])
HOUSING_X0, HOUSING_X1 = -0.0195, -0.0800     # housing from the collar out to its mouth
HOUSING_IN = (0.0181, 0.0117)      # inner half depth / half height (the magazine channel)
HOUSING_OUT = (0.0207, 0.0143)
COLLAR = (0.0226, 0.0242, 0.0400, 0.1020)     # housing collar: inner / outer radius, z range
MAG_CATCH = np.array([-0.0735, HOUSING_OUT[1] + 0.0050, MAG_ZC + 0.0150])   # thumb tab of the catch (top rear)
# windows through the tube (theta from +X towards +Y)
PORT = (-0.44, 0.44, MAG_ZC - 0.0182, MAG_ZC + 0.0182)     # ejection port (right, opposite the magazine)
MAG_WIN = (np.pi - 0.60, np.pi + 0.60, MAG_ZC - 0.0182, MAG_ZC + 0.0182)
# bolt and cocking handle (open bolt: at rest the bolt is held back on the sear, cocked)
BOLT_TRAVEL = 0.0900               # handle stroke from forward (fired / empty) to cocked
OVERTRAVEL = 0.0040                # pulled past the sear when cocking (the safety notch sits there)
HANDLE_Z = 0.2080                  # handle at rest (cocked)
HANDLE_FWD = HANDLE_Z - BOLT_TRAVEL
SLOT = (-0.175, 0.175, HANDLE_FWD - 0.0050, HANDLE_Z + OVERTRAVEL + 0.0042)
NOTCH = (0.175, 0.74, HANDLE_Z + OVERTRAVEL - 0.0042, HANDLE_Z + OVERTRAVEL + 0.0042)
SAFETY_ROT = 22.0                  # degrees the bolt turns (about the bore) to put the handle in the notch
BOLT_R = 0.0200
BOLT_Z0 = BREECH_Z + BOLT_TRAVEL   # bolt face at rest
BOLT_LEN = 0.1100
# trigger group
TH_Z0, TH_Z1 = 0.1800, 0.3220      # trigger housing
TH_W = 0.0130
TH_Y0 = -0.0605
TRIGGER_PIVOT = np.array([0.0, -0.0460, 0.2930])
SELECTOR = np.array([0.0, -0.0340, 0.2340])
# wooden pistol grip (Mk V pattern) on a steel bracket welded under the end of the tube, behind the trigger guard
GRIP_TOP = np.array([0.0, -0.0560, 0.3390])   # top of the wood, on the grip's axis
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(14.0)), np.sin(np.radians(14.0))])    # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(14.0)), -np.cos(np.radians(14.0))])
GRIP_LEN = 0.1000
# stock
STOCK_A = np.array([0.0, -0.0075, 0.3370])    # tube start (in the end cap's socket)
STOCK_B = np.array([0.0, -0.0340, 0.6000])    # tube end (welded to the butt strap)
STOCK_R = 0.0095
BUTT_Z0, BUTT_Z1 = 0.6000, 0.6060
BUTT_Y0, BUTT_Y1 = -0.1020, 0.0180
# optic rail (top of the tube, ahead of the peep; our addition)
RAIL_Z0, RAIL_Z1 = 0.1060, 0.1770
RAIL_TOP = 0.0315
OPTIC_Z = 0.1415
EJECTION = np.array([R_OUT + 0.0005, 0.0040, 0.0710])
SLING_L = np.array([-R_OUT, -0.0080, 0.2820])  # sling loop on the left rear of the tube (the charm hangs there)
SLEEVE_SIDE_X = SLEEVE_R + 0.0006


def _arc_band(r0, r1, a0, a1, seg):
    """CCW (x, y) loop of a curved band between radii r0 < r1 from angle a0 to a1."""
    a = np.linspace(a0, a1, seg + 1)
    outer = np.stack([r1 * np.cos(a), r1 * np.sin(a)], 1)
    inner = np.stack([r0 * np.cos(a[::-1]), r0 * np.sin(a[::-1])], 1)
    return np.concatenate([outer, inner])


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """The receiver tube with its cut openings, the end cap, the magazine housing collar and housing, the sight
    bases, the optic rail, the sling loop and the welds."""
    m = sg.windowed_tube(R_OUT, R_IN, REC_Z0, REC_Z1, [PORT, MAG_WIN, SLOT, NOTCH], "steel")
    # end cap (return spring cap): a cup over the tube's rear end, the stock socket boss on its rear face
    cap = [(0.0, CAP_Z1), (0.0214, CAP_Z1), (0.0234, CAP_Z1 - 0.0020), (0.0234, CAP_Z0 + 0.0008),
           (0.0226, CAP_Z0), (0.0, CAP_Z0)]
    m.merge(sg.lathe(cap, "steel"))
    m.merge(sh.box(-0.0035, -0.0270, CAP_Z0 + 0.0040, 0.0035, -0.0225, CAP_Z1 - 0.0030, 0.0008, "steel_dark"))  # catch
    # magazine housing collar (it can turn on the tube) with the magazine opening and the port cut through it
    c0, c1, cz0, cz1 = COLLAR
    win = (np.pi - 0.515, np.pi + 0.515, MAG_ZC - 0.0182, MAG_ZC + 0.0182)
    m.merge(sg.windowed_tube(c1, c0, cz0, cz1, [win, (PORT[0], PORT[1], PORT[2], PORT[3])], "steel"))
    # housing: a rectangular sleeve out to the left with a rolled mouth
    hz, hy = HOUSING_OUT
    iz, iy = HOUSING_IN
    outer = sh.rounded_rect(MAG_ZC - hz, -hy, MAG_ZC + hz, hy, 0.0032, 3)
    hole = sh.rounded_rect(MAG_ZC - iz, -iy, MAG_ZC + iz, iy, 0.0016, 3)
    hw = 0.5 * (HOUSING_X0 - HOUSING_X1 - 0.0030)
    m.merge(sh.extrude_x(outer, [hole], hw, chamfer_bevel(0.0006), "steel", x_center=HOUSING_X0 - hw))
    lip = sh.rounded_rect(MAG_ZC - hz - 0.0016, -hy - 0.0016, MAG_ZC + hz + 0.0016, hy + 0.0016, 0.0040, 3)
    m.merge(sh.extrude_x(lip, [hole], 0.0018, chamfer_bevel(0.0007), "steel", x_center=HOUSING_X1 + 0.0018))
    # stamped stiffening ribs along the housing's top and bottom, spot welds on the collar
    for s in (-1, 1):
        m.merge(sh.box(HOUSING_X1 + 0.0050, s * hy - 0.0007, MAG_ZC - 0.0060, HOUSING_X0 - 0.0060, s * hy + 0.0007,
                       MAG_ZC + 0.0060, 0.0005, "steel"))
    for th, z in zip(np.radians((140, 220, 140, 220)), (cz0 + 0.0045, cz0 + 0.0045, cz1 - 0.0045, cz1 - 0.0045)):
        m.merge(_spot(c1, th, z))
    # magazine catch: a spring lever along the housing's top rear edge, its thumb tab raised at the outer end, the
    # tooth reaching into the housing (the magazine's catch slot)
    zc = MAG_CATCH[2]
    m.merge(sh.box(-0.0700, hy - 0.0004, zc - 0.0045, -0.0330, hy + 0.0026, zc + 0.0045, 0.0008, "steel_dark"))
    m.merge(sh.box(-0.0790, hy + 0.0016, zc - 0.0055, -0.0690, MAG_CATCH[1] + 0.0016, zc + 0.0055, 0.0010,
                   "steel_dark"))
    m.merge(_cyl_z(0.0018, zc - 0.0062, zc + 0.0062, "steel_dark", 10, y=hy + 0.0016, x=-0.0370))   # pivot pin
    # front sight base on the front of the tube, rear sight saddle at the rear
    m.merge(sh.box(-0.0040, R_OUT - 0.0025, FRONT_Z - 0.0090, 0.0040, 0.0262, FRONT_Z + 0.0090, 0.0010, "steel"))
    m.merge(extrude_z(_arc_band(R_OUT - 0.0010, R_OUT + 0.0024, np.radians(55), np.radians(125), 8), (),
                      REAR_Z - 0.0130, REAR_Z + 0.0120, chamfer_bevel(0.0006), "steel"))
    m.merge(sh.box(-0.0092, 0.0225, REAR_Z - 0.0060, 0.0092, 0.0262, REAR_Z + 0.0070, 0.0008, "steel"))
    # optic rail: a curved saddle on the tube top (clear of the cocking slot), the rail with its cross slots
    m.merge(extrude_z(_arc_band(R_OUT - 0.0010, R_OUT + 0.0026, np.radians(42), np.radians(138), 10), (),
                      RAIL_Z0 - 0.0040, RAIL_Z1 + 0.0040, chamfer_bevel(0.0008), "steel_dark"))
    m.merge(rail_mesh())
    for z in (RAIL_Z0 + 0.0040, RAIL_Z1 - 0.0040):
        for s in (-1, 1):
            m.merge(_screw(s * 0.0178, 0.0178, z, s))
    # sling loop on the left rear (the charm hangs from it)
    p = SLING_L
    m.merge(sg.weld_bead([(p[0] + 0.0012, p[1], p[2] - 0.0100), (p[0] - 0.0060, p[1] - 0.0010, p[2] - 0.0085),
                          (p[0] - 0.0085, p[1] - 0.0015, p[2]), (p[0] - 0.0060, p[1] - 0.0010, p[2] + 0.0085),
                          (p[0] + 0.0012, p[1], p[2] + 0.0100)], 0.0017, "steel_dark", 8))
    # weld seam along the bottom of the tube, ahead of the trigger housing
    m.merge(sh.box(-0.0016, -R_OUT - 0.0007, 0.0120, 0.0016, -R_OUT + 0.0010, TH_Z0 - 0.0010, 0.0005, "steel"))
    return m


def _spot(r, th, z, rad=0.0020):
    """A spot-weld dimple raised a hair on a tube of radius r."""
    dome = sh.revolve([(0.0, 0.0006), (rad * 0.65, 0.0004), (rad, -0.0001), (0.0, -0.0006)], 10, "steel",
                      precision=True)
    n = np.array([np.cos(th), np.sin(th), 0.0])
    t = np.array([-np.sin(th), np.cos(th), 0.0])
    return sh.place(dome, origin=n * r + np.array([0, 0, z]), x_axis=t, y_axis=(0, 0, 1), z_axis=n)


def _screw(x, y, z, side):
    n = np.array([side * 0.7071, 0.7071, 0.0])
    head = sh.revolve([(0.0, 0.0010), (0.0013, 0.0008), (0.0018, 0.0002), (0.0, -0.0008)], 10, "steel_dark",
                      precision=True)
    t = np.cross(n, (0, 0, 1.0))
    return sh.place(head, origin=(x, y, z), x_axis=t, y_axis=(0, 0, 1), z_axis=n)


def rail_mesh():
    """Picatinny rail on the saddle: the dovetail bar, cross slots at the standard 10.01 mm pitch."""
    low = RAIL_TOP - 0.0026                                  # slot floor
    prof = np.array([(-0.0080, 0.0222), (0.0080, 0.0222), (0.0080, 0.0262), (0.0105, 0.0280), (0.0105, 0.0292),
                     (0.0085, low), (-0.0085, low), (-0.0105, 0.0292), (-0.0105, 0.0280), (-0.0080, 0.0262)])
    m = extrude_z(prof, (), RAIL_Z0, RAIL_Z1, chamfer_bevel(0.0005), "steel_dark")
    pitch, slot = 0.01001, 0.00523
    n = int((RAIL_Z1 - RAIL_Z0) / pitch)
    z0 = 0.5 * (RAIL_Z0 + RAIL_Z1) - 0.5 * (n - 1) * pitch
    for k in range(n):                                       # the teeth between the cross slots
        zc = z0 + k * pitch
        m.merge(sh.box(-0.0085, low - 0.0006, zc - 0.5 * (pitch - slot), 0.0085, RAIL_TOP, zc + 0.5 * (pitch - slot),
                       0.0004, "steel_dark"))
    return m


# ------------------------------------------------------------------------ barrel group
def barrel_mesh():
    """Barrel (bore at the crown, chamber mouth at the breech), the barrel nut with its spanner ribs, the perforated
    sleeve with its rear collar and front cap."""
    prof = [(0.0, CROWN_Z + 0.0110), (0.0042, CROWN_Z + 0.0110), (0.0042, CROWN_Z + 0.0004),
            (0.0050, CROWN_Z), (0.0080, CROWN_Z), (BARREL_R, CROWN_Z + 0.0010), (BARREL_R, BREECH_Z - 0.0010),
            (BARREL_R - 0.0008, BREECH_Z), (0.0052, BREECH_Z), (0.0046, BREECH_Z - 0.0060), (0.0, BREECH_Z - 0.0060)]
    m = sg.lathe(prof, "barrel")
    nut = [(0.0094, NUT_Z0), (0.0226, NUT_Z0), (NUT_R, NUT_Z0 + 0.0014), (NUT_R, NUT_Z1 - 0.0014),
           (0.0226, NUT_Z1), (0.0094, NUT_Z1)]
    m.merge(sg.lathe(nut, "barrel"))
    for k in range(4):                                        # spanner lugs round the nut
        a = np.pi / 4 + k * np.pi / 2
        rib = sh.box(-0.0017, NUT_R - 0.0010, NUT_Z0 + 0.0050, 0.0017, NUT_R + 0.0012, NUT_Z1 - 0.0050, 0.0005,
                     "barrel")
        m.merge(sh.place(rib, x_axis=(np.cos(a - np.pi / 2), np.sin(a - np.pi / 2), 0),
                         y_axis=(np.cos(a), np.sin(a), 0), z_axis=(0, 0, 1)))
    m.merge(sg.perforated_sleeve(SLEEVE_R, SLEEVE_T, SLEEVE_Z0, SLEEVE_Z1, 3, 6, HOLE_R, "barrel", end=0.0060,
                                 m_side=8, k_side=2))
    rear = [(SLEEVE_R - SLEEVE_T - 0.0002, -0.0400), (0.0172, -0.0400), (0.0180, -0.0392), (0.0180, -0.0293),
            (SLEEVE_R - SLEEVE_T - 0.0002, -0.0293)]
    m.merge(sg.lathe(rear, "barrel"))
    front = [(BARREL_R + 0.0003, CAP_FRONT_Z0), (0.0150, CAP_FRONT_Z0), (0.0166, CAP_FRONT_Z0 + 0.0016),
             (0.0166, CAP_FRONT_Z1), (SLEEVE_R - SLEEVE_T - 0.0002, CAP_FRONT_Z1)]
    m.merge(sg.lathe(front, "barrel"))
    return m


def muzzle_brake_mesh():
    """Muzzle collar on the protruding barrel ahead of the sleeve (node `muzzle_brake`: hidden by the barrel slot)."""
    prof = [(BARREL_R + 0.0001, COLLAR_Z0), (0.0102, COLLAR_Z0), (0.0110, COLLAR_Z0 + 0.0008),
            (0.0110, COLLAR_Z1), (BARREL_R + 0.0001, COLLAR_Z1)]
    return sg.lathe(prof, "barrel")


# ------------------------------------------------------------------------------------ sights
def front_blade_mesh():
    """Front sight blade (node `sight_iron`, hidden while an optic is fitted): a sloped blade, flat top on the sight
    line."""
    z = FRONT_Z
    prof = np.array([(z - 0.0065, 0.0258), (z + 0.0065, 0.0258), (z + 0.0045, SIGHT_Y - 0.0012),
                     (z + 0.0025, SIGHT_Y), (z - 0.0025, SIGHT_Y), (z - 0.0040, SIGHT_Y - 0.0015)])
    m = sh.extrude_x(prof, (), 0.0011, chamfer_bevel(0.0002), "steel_dark")
    return m


def rear_peep_mesh():
    """Fixed peep (aperture) sight on the saddle (node `sight_rear`): a plate with a round aperture on the sight line
    and two protective ears."""
    y0, top = 0.0250, SIGHT_Y + 0.0072
    outer = np.array([(-0.0092, y0), (0.0092, y0), (0.0092, top + 0.0028), (0.0080, top + 0.0040),
                      (0.0058, top + 0.0040), (0.0052, top), (-0.0052, top), (-0.0058, top + 0.0040),
                      (-0.0080, top + 0.0040), (-0.0092, top + 0.0028)])
    hole = sh.circle(0.0, SIGHT_Y, PEEP_R, sh.arc_segments(PEEP_R, 16))[::-1]
    m = extrude_z(outer, [hole], REAR_Z - 0.0016, REAR_Z + 0.0016, chamfer_bevel(0.0003), "steel_dark")
    for s in (-1, 1):                                                  # gussets behind the plate
        g = np.array([(REAR_Z + 0.0016, y0), (REAR_Z + 0.0070, y0), (REAR_Z + 0.0016, top - 0.0020)])
        m.merge(sh.extrude_x(g, (), 0.0010, ((0.0, 0.0),), "steel_dark", x_center=s * 0.0070))
    return m


# ------------------------------------------------------------------------------------ trigger group
def trigger_housing_mesh():
    """Stamped trigger housing under the tube, the strap trigger guard, the selector cross-bolt and the pins."""
    yt = -R_OUT + 0.0040
    prof = [(TH_Z0, yt), (TH_Z0 + 0.0030, -0.0420), (TH_Z0 + 0.0110, TH_Y0 + 0.0030), (TH_Z0 + 0.0170, TH_Y0),
            (TH_Z1 - 0.0150, TH_Y0), (TH_Z1 - 0.0070, TH_Y0 + 0.0040), (TH_Z1 - 0.0010, -0.0440), (TH_Z1, yt)]
    m = sh.extrude_x(prof, (), TH_W, chamfer_bevel(0.0016), "steel")
    # trigger slot in the bottom (dark), the guard
    slot = Mesh()
    y = TH_Y0 - 0.00035
    sg.poly(slot, [(-0.0032, y, 0.2860), (0.0032, y, 0.2860), (0.0032, y, 0.3040), (-0.0032, y, 0.3040)],
            "steel_dark", (0, -1, 0))
    m.merge(slot)
    guard_out = [(0.2640, TH_Y0 + 0.0010), (0.2640, TH_Y0 - 0.0070), (0.2700, TH_Y0 - 0.0185),
                 (0.2830, TH_Y0 - 0.0245), (0.3020, TH_Y0 - 0.0245), (0.3140, TH_Y0 - 0.0190),
                 (0.3195, TH_Y0 - 0.0090), (0.3195, TH_Y0 + 0.0010)]
    guard_in = [(0.2675, TH_Y0 + 0.0005), (0.2675, TH_Y0 - 0.0065), (0.2725, TH_Y0 - 0.0160),
                (0.2840, TH_Y0 - 0.0212), (0.3010, TH_Y0 - 0.0212), (0.3112, TH_Y0 - 0.0170),
                (0.3160, TH_Y0 - 0.0085), (0.3160, TH_Y0 + 0.0005)]
    # a bent strap: its bends smoothed (corner cutting) rather than eight facets
    m.merge(sh.extrude_x(sd.smooth_open(guard_out, 2), [sd.smooth_open(guard_in, 2)], 0.0042, chamfer_bevel(0.0007),
                         "steel"))
    # pins through the housing, the selector button (left: semi, right: auto)
    for y, z in ((-0.0300, 0.1960), (TRIGGER_PIVOT[1], TRIGGER_PIVOT[2]), (-0.0290, 0.3090)):
        for s in (-1, 1):
            m.merge(_rivet(s * (TH_W + 0.0006), y, z, s, 0.0017, "steel_dark"))
    m.merge(_cyl_x(0.0034, -TH_W - 0.0032, TH_W + 0.0024, (SELECTOR[1], SELECTOR[2]), "steel_dark", 16))
    m.merge(_cyl_x(0.0041, -TH_W - 0.0008, -TH_W + 0.0004, (SELECTOR[1], SELECTOR[2]), "steel", 16))
    m.merge(_cyl_x(0.0041, TH_W - 0.0004, TH_W + 0.0008, (SELECTOR[1], SELECTOR[2]), "steel", 16))
    # the welds joining the housing to the tube
    for s in (-1, 1):
        m.merge(sg.weld_bead([(s * TH_W * 0.98, -R_OUT + 0.0050, TH_Z0 + 0.0050),
                              (s * TH_W * 0.98, -R_OUT + 0.0050, TH_Z1 - 0.0050)], 0.0011, "steel", 6))
    return m


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def _grip_section(a, b, n=24, e=0.55):
    """Rounded section of the grip: half depth a (along GRIP_FRONT), half width b (x), a superellipse."""
    t = np.linspace(0.0, 2 * np.pi, n, endpoint=False)
    c, s = np.cos(t), np.sin(t)
    return np.stack([b * np.sign(s) * np.abs(s) ** e, a * np.sign(c) * np.abs(c) ** e], 1)


def grip_mesh():
    """Wooden pistol grip: the bracket under the end of the tube (welded, two pins), the grip lofted down its axis
    (a palm swell in the middle, a little flare at the heel, the bottom rounded over), the grip screw's escutcheons."""
    bracket = [(0.3160, -0.0190), (0.3520, -0.0190), (0.3600, -0.0260), (0.3600, -0.0575), (0.3170, -0.0575)]
    m = sh.extrude_x(np.array(bracket)[::-1], (), 0.0115, chamfer_bevel(0.0012), "steel")
    for y, z in ((-0.0310, 0.3290), (-0.0310, 0.3500)):
        for s in (-1, 1):
            m.merge(_rivet(s * 0.0121, y, z, s, 0.0016, "steel_dark"))
    rings = []
    for d, a, b in ((-0.0030, 0.0168, 0.0118), (0.0000, 0.0176, 0.0128), (0.0060, 0.0178, 0.0132),
                    (0.0350, 0.0188, 0.0142), (0.0650, 0.0190, 0.0140), (0.0880, 0.0196, 0.0138),
                    (0.0950, 0.0186, 0.0130), (0.0985, 0.0160, 0.0110), (GRIP_LEN, 0.0120, 0.0080)):
        c = grip_center(d)
        sec = _grip_section(a, b)
        rings.append(np.array([c + p[0] * np.array([1.0, 0.0, 0.0]) + p[1] * GRIP_FRONT for p in sec]))
    rings, soft = sd.round_end(rings, 0.0030)                   # the heel rounded over, no flat-sawn end
    m.merge(sh.loft(rings, "wood", soft_bands=soft))
    c = grip_center(0.0420)
    for s in (-1, 1):
        m.merge(_rivet(s * 0.0144, c[1], c[2], s, 0.0030, "steel_dark"))
    return m


def trigger_mesh():
    z, y = TRIGGER_PIVOT[2], TRIGGER_PIVOT[1]
    blade = [(z - 0.0040, y + 0.0035), (z + 0.0040, y + 0.0035), (z + 0.0044, y - 0.0060), (z + 0.0028, y - 0.0150),
             (z - 0.0006, y - 0.0230), (z - 0.0042, y - 0.0262), (z - 0.0064, y - 0.0252), (z - 0.0040, y - 0.0210),
             (z - 0.0020, y - 0.0140), (z - 0.0030, y - 0.0040)]
    return sh.extrude_x(np.array(blade), (), 0.0028, round_bevel(0.0010, 2), "steel_dark")


# ------------------------------------------------------------------------------------ stock
def butt_curve(y):
    """Rearward offset of the butt strap at height y: concave at the shoulder (the ends further back)."""
    return 0.0040 * ((np.asarray(y) - 0.5 * (BUTT_Y0 + BUTT_Y1)) / (0.5 * (BUTT_Y1 - BUTT_Y0))) ** 2


def stock_mesh():
    """Skeleton "T" stock: the tube from its socket fitting on the end cap to the flat butt strap (welded across its
    end), with a sling slot in the strap's lower half."""
    d = STOCK_B - STOCK_A
    u = d / np.linalg.norm(d)
    m = sg.tube_between(STOCK_A + 0.0040 * u, STOCK_B + 0.0020 * u, STOCK_R, "steel", chamfer=0.0006)
    m.merge(sg.tube_between(STOCK_A - 0.0010 * u, STOCK_A + 0.0230 * u, 0.0124, "steel", chamfer=0.0012))  # socket
    # catch plunger on the socket's top
    m.merge(sg.tube_between(STOCK_A + 0.0090 * u + (0, 0.0105, 0), STOCK_A + 0.0090 * u + (0, 0.0150, 0), 0.0028,
                            "steel_dark", chamfer=0.0006))
    # butt strap: a slightly curved flat plate (concave at the shoulder) with rounded ends
    w = 0.0145
    outline = sh.rounded_rect(BUTT_Y0, -w, BUTT_Y1, w, 0.0110, 4)          # (y, x) outline
    rings = []
    for z, ins in ((BUTT_Z0, 0.0010), (BUTT_Z0 + 0.0010, 0.0), (BUTT_Z1 - 0.0010, 0.0), (BUTT_Z1, 0.0010)):
        o = sh.offset_loop(outline, ins) if ins else outline
        rings.append(np.stack([o[:, 1], o[:, 0], z + butt_curve(o[:, 0])], 1))
    m.merge(sh.loft(rings, "steel"))
    slot = Mesh()                                            # sling slot on the strap's front face
    pts = [(x, y, BUTT_Z0 - 0.00035 + butt_curve(y)) for x, y in ((-0.0070, -0.0890), (0.0070, -0.0890),
                                                                   (0.0070, -0.0720), (-0.0070, -0.0720))]
    sg.poly(slot, pts, "steel_dark", (0, 0, -1))
    m.merge(slot)
    # weld where the tube meets the strap
    a = np.linspace(0, 2 * np.pi, 13)[:-1]
    ring = [STOCK_B + (0.0, 0.0, 0.0018) + (STOCK_R + 0.0008) * np.array([np.cos(t), np.sin(t), 0.0]) for t in a]
    m.merge(sg.weld_bead(ring + ring[:1], 0.0012, "steel", 6))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_mesh():
    """Bolt (at rest: cocked, held back on the sear) with the fixed firing pin and the extractor, the cocking handle
    out of the right side: a stem through the slot and a round knob."""
    z0, z1 = BOLT_Z0, BOLT_Z0 + BOLT_LEN
    prof = [(0.0, z0 - 0.0015), (0.0012, z0 - 0.0015), (0.0012, z0), (BOLT_R - 0.0012, z0), (BOLT_R, z0 + 0.0012),
            (BOLT_R, z1), (0.0, z1)]
    m = sg.lathe(prof, "steel_dark")
    m.merge(sh.box(-0.0090, 0.0040, z0 - 0.0004, -0.0060, 0.0090, z0 + 0.0140, 0.0004, "steel_dark"))   # extractor
    zh = HANDLE_Z
    m.merge(_cyl_x(0.0034, BOLT_R - 0.0020, R_OUT + 0.0045, (0.0, zh), "steel_dark", 16))              # stem
    knob = [(0.0, 0.0), (0.0040, 0.0), (0.0052, 0.0010), (0.0052, 0.0185), (0.0040, 0.0215), (0.0018, 0.0228),
            (0.0, 0.0230)]
    m.merge(sg.lathe(knob, "steel_dark", 16, origin=(R_OUT + 0.0035, 0.0, zh), axis="x"))
    return m


# ------------------------------------------------------------------------------------ magazine, rounds
def round_mesh(seg=12):
    """One 9x19 mm round along -Z (bullet forward), base at z = 0."""
    m = sh.revolve([(0.0, 0.0), (0.0047, 0.0), (0.0049, -0.0012), (0.0048, -0.0190)], seg, "brass", precision=True)
    m.merge(sh.revolve([(0.0048, -0.0190), (0.0034, -0.0268), (0.0, -0.0294)], seg, "brass", precision=True))
    return m


def mag_mesh():
    """32-round STEN magazine (mag-local: top of the lips at the origin, the magazine along -X): a straight double
    stack that narrows to the single feed at the top, the welded seam on the rear face, the catch slot, the folded
    floor plate, the top round between the lips."""
    hz, hy = MAG_HZ, MAG_HY
    sec = lambda y: sh.rounded_rect(-hz, -y, hz, y, min(0.0026, 0.45 * y), 3)      # (z, y)
    xs = [(0.0, MAG_TOP_HY, 0.0006), (-0.0012, MAG_TOP_HY, 0.0), (-MAG_TAPER, hy, 0.0), (-(MAG_LEN - 0.0085), hy, 0.0)]
    rings = []
    for x, y, ins in xs:
        o = sec(y)
        o = sh.offset_loop(o, ins) if ins else o
        rings.append(np.stack([np.full(len(o), x), o[:, 1], o[:, 0]], 1))
    m = sh.loft(rings, "mag")
    for s in (-1, 1):                                          # the lips rolled over the top round
        m.merge(sh.box(-0.0050, s * MAG_TOP_HY - 0.0007, -hz + 0.0030, 0.0006, s * MAG_TOP_HY + 0.0007,
                       hz - 0.0060, 0.0004, "mag"))
    m.merge(sh.box(-(MAG_LEN - 0.0100), -0.0024, hz - 0.0004, -MAG_TAPER - 0.0040, 0.0024, hz + 0.0006, 0.0004,
                   "mag"))                                                                       # rear seam
    slot = Mesh()
    sg.poly(slot, [(-0.0640, -0.0035, hz + 0.00065), (-0.0560, -0.0035, hz + 0.00065), (-0.0560, 0.0035, hz + 0.00065),
                   (-0.0640, 0.0035, hz + 0.00065)], "steel_dark", (0, 0, 1))                  # catch slot
    m.merge(slot)
    for s in (-1, 1):                                          # two pressed ribs along the flat faces
        for zc in (-0.0080, 0.0080):
            m.merge(sh.box(-(MAG_LEN - 0.0200), s * hy - 0.0005, zc - 0.0016, -MAG_TAPER - 0.0120, s * hy + 0.0005,
                           zc + 0.0016, 0.0004, "mag"))
    base = sh.rounded_rect(-hz - 0.0011, -hy - 0.0011, hz + 0.0011, hy + 0.0011, 0.0030, 3)
    m.merge(sh.extrude_x(base, (), 0.0047, chamfer_bevel(0.0010), "mag_base", x_center=-(MAG_LEN - 0.0047)))
    m.merge(sh.box(-MAG_LEN - 0.0006, -0.0040, -hz + 0.0030, -MAG_LEN + 0.0020, 0.0040, hz - 0.0030, 0.0005,
                   "mag_base"))                                                                  # floor plate stop
    m.merge(round_mesh().translated((-0.0040, 0.0, 0.0150)))
    return m


def shell_mesh(scale=1.1):
    """Spent 9x19 mm brass case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0096), (0.0049, 0.0092), (0.0049, 0.0082), (0.0040, 0.0076), (0.0049, 0.0066),
            (0.0048, -0.0096), (0.0043, -0.0096), (0.0, -0.0084)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 14, "brass", precision=True)
