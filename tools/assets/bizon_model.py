"""PP-19 Bizon geometry (VIEWMODEL_SPEC sections 3-6, 19-21 and 54). Design frame (metres): +X right, +Y up, +Z
rearward; bore axis on x = y = 0, z = 0 at the front face of the receiver (as the AK's, ak_model.py). Real-world scale:
660 mm with the stock unfolded, 230 mm barrel, sight radius 228 mm.

An original procedural model built like the MP5's (mp5_model.py helpers, meshkit.Mesh in the design frame; gen_bizon
moves meshes into node-local space): the AKS-74U-pattern stamped receiver, its front cut short over the magazine (the
magazine runs under the barrel and doubles as the foregrip), the front and rear trunnions with their rivets, the
ribbed dust cover with a low picatinny section, the open rear sight on the front trunnion, the bare barrel with a
clamp-on side rail, the front sight block carrying the magazine's front catch, the slotted muzzle nut, the AK charging
handle on the carrier, the big safety lever, the stamped trigger guard, the AK-74 polymer grip, the 64-round helical
magazine (a fluted polymer cylinder with its feed tower, nose lug and rear latch lug) and the AKS-74 side-folding
triangle stock (two struts and a stamped butt plate with a rubber pad)."""
import numpy as np

import shapes as sh
import smg_detail as sd
from ak_model import _ring_xy, _rivet
from meshkit import Mesh
from model_quality import arc_segments
from mp5_model import _chaikin, _pin_x, _stud, smooth
from p9_model import chamfer_bevel, extrude_z, round_bevel

# ---- key dimensions ---------------------------------------------------------------------------
REC_W = 0.0155                    # receiver half width
REC_Y1 = 0.0150                   # top edge of the receiver walls (the dust cover sits on it)
REC_Y0 = -0.0400                  # receiver bottom behind the magazine
REC_YF = -0.0110                  # receiver bottom over the magazine (front part)
REC_Z1 = 0.2380                   # rear end (rear trunnion)
WELL_Z1 = 0.0600                  # end of the magazine seat: the receiver steps down behind it
COVER_Z0, COVER_TOP = 0.0470, 0.0315
SIGHT_Y = 0.0500                  # sight line: rear notch shoulders / front post tip
REAR_NOTCH_Z = 0.0400             # rear face of the rear sight's notch plate
FRONT_POST_Z = -0.1880            # sight radius 228 mm
BARREL_R = 0.0086
CROWN_Z = -0.2150
MUZZLE_Z = -0.2470                # front face of the muzzle nut
FS_Z0, FS_Z1 = -0.2030, -0.1700   # front sight block
MAG_R = 0.0265                    # helical magazine radius (53 mm cylinder)
MAG_Y = REC_YF - MAG_R - 0.0012   # magazine axis height
MAG_Z0, MAG_Z1 = -0.1900, 0.0585  # front end of the cylinder / its rear face
MAG_ORIGIN = np.array([0.0, REC_YF, 0.0500])          # seated magazine pivot: the top of the feed tower's latch
MAG_AXIS_Y = MAG_Y - MAG_ORIGIN[1]                    # mag-local axis height
LUG_Z0 = FS_Z0 + 0.0045          # front end of the nose lug: it butts against the front catch
MAG_FRONT_HOOK = np.array([0.0, MAG_AXIS_Y + MAG_R + 0.0004, LUG_Z0 - MAG_ORIGIN[2]])  # the nose lug's tip
GRIP_TOP = np.array([0.0, REC_Y0, 0.1500])
GRIP_RAKE = 20.0
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_RAKE)), np.sin(np.radians(GRIP_RAKE))])     # down and back
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_RAKE)), -np.cos(np.radians(GRIP_RAKE))])
GRIP_LEN = 0.0950
TRIGGER_PIVOT = np.array([0.0, -0.0330, 0.1030])
SELECTOR_PIVOT = np.array([REC_W, 0.0020, 0.2000])
PADDLE_PIVOT = np.array([0.0, REC_Y0 + 0.0010, 0.0655])
BOLT_TRAVEL = 0.0800              # carrier / charging handle stroke
HANDLE_Z = 0.0800                 # charging handle at rest (bolt forward)
EJECTION = np.array([REC_W + 0.0005, 0.0080, 0.0740])
PORT_Z0, PORT_Z1 = 0.0440, 0.1040
RAIL_Z0, RAIL_Z1 = 0.0920, 0.2060  # optic rail on the dust cover
RAIL_H, RAIL_W = 0.0040, 0.0105
OPTIC_Z = 0.1460
LASER_Z = -0.1360                 # side rail clamped on the barrel (laser mount on its right face)
LASER_X = 0.0182
BUTT_Z = 0.4000                   # butt face, stock unfolded
HINGE = np.array([-REC_W - 0.0030, -0.0100, REC_Z1 - 0.0080])     # stock hinge (left rear)


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


# -------------------------------------------------------------------------------------- helpers
def _tube(p0, p1, r, material, seg=16, chamfer=0.0006):
    """Round tube from p0 to p1 (chamfered ends)."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    d = p1 - p0
    length = float(np.linalg.norm(d))
    z = d / length
    x = np.cross((0.0, 1.0, 0.0), z)
    x = x / np.linalg.norm(x) if np.linalg.norm(x) > 1e-6 else np.array([1.0, 0.0, 0.0])
    y = np.cross(z, x)
    c = min(chamfer, 0.3 * r)
    prof = [(0.0, 0.0), (r - c, 0.0), (r, c), (r, length - c), (r - c, length), (0.0, length)]
    m = sh.revolve(prof, seg, material, precision=True)
    return sh.place(m, origin=p0, x_axis=x, y_axis=y, z_axis=z)


def _cyl_z(r, z0, z1, material, seg, y=0.0, x=0.0, chamfer=0.0):
    c = min(chamfer, 0.45 * abs(z1 - z0))
    prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)]
    return sh.revolve(prof, seg, material, precision=True).translated((x, y, 0.0))


def _y_up(z, y, x=0.0):
    """Matrix turning a +Z lathe onto +Y at (x, y, z)."""
    mat = np.eye(4)
    mat[:3, :3] = np.column_stack([(1, 0, 0), (0, 0, -1), (0, 1, 0)])
    mat[:3, 3] = (x, y, z)
    return mat


# -------------------------------------------------------------------------------------- receiver
def receiver_mesh():
    """Stamped receiver: cut short over the magazine in front, stepped down behind it; the front and rear trunnions
    with their rivets, the long pressed rail along both flanks, the ejection port (a real recess with the carrier face
    behind it), the stamped trigger guard, the selector's detent stops, the rear sight base on the front trunnion, the
    stock hinge block and the rear sling loop on the left."""
    prof = [(0.0000, REC_YF + 0.0030), (0.0030, REC_YF), (WELL_Z1 - 0.0010, REC_YF), (WELL_Z1, REC_YF - 0.0012),
            (WELL_Z1, REC_Y0 + 0.0030), (WELL_Z1 + 0.0030, REC_Y0), (0.2000, REC_Y0), (0.2160, REC_Y0 + 0.0040),
            (REC_Z1, REC_Y0 + 0.0110), (REC_Z1, REC_Y1 - 0.0020), (REC_Z1 - 0.0020, REC_Y1), (0.0000, REC_Y1)]
    m = sh.extrude_x(np.array(prof), (), REC_W, round_bevel(0.0012, 2), "steel")
    # front trunnion block, a little proud, rounded front
    m.merge(extrude_z(sh.rounded_rect(-REC_W - 0.0006, REC_YF + 0.0010, REC_W + 0.0006, REC_Y1 - 0.0010, 0.0030, 2),
                      (), 0.0005, 0.0360, round_bevel(0.0008, 2), "steel"))
    # rear trunnion plate
    m.merge(extrude_z(sh.rounded_rect(-REC_W - 0.0004, REC_Y0 + 0.0120, REC_W + 0.0004, REC_Y1 - 0.0020, 0.0025, 2),
                      (), REC_Z1 - 0.0260, REC_Z1 + 0.0010, round_bevel(0.0008, 2), "steel"))
    for s in (-1, 1):
        x0, x1 = sorted((s * REC_W, s * (REC_W + 0.0008)))
        m.merge(sh.box(x0, -0.0215, 0.0700, x1, -0.0135, 0.1950, 0.0004, "steel"))     # pressed rail
        for z, y in ((0.0090, -0.0050), (0.0280, -0.0050), (0.0090, 0.0070), (0.0280, 0.0070),
                     (0.2120, -0.0300), (0.2240, -0.0130), (0.1250, -0.0300), (0.0720, -0.0300)):
            m.merge(_rivet(s * (REC_W + 0.0006), y, z, s))
    # ejection port: a recess in the right wall, the carrier's dark face at its bottom
    m.merge(sh.box(REC_W - 0.0010, -0.0030, PORT_Z0, REC_W + 0.0003, REC_Y1 - 0.0010, PORT_Z1, 0.0003, "steel_dark"))
    # stamped trigger guard (its front leg is the magazine release's housing)
    guard_out = [(0.0750, REC_Y0 + 0.0005), (0.0750, REC_Y0 - 0.0060), (0.0815, REC_Y0 - 0.0215),
                 (0.0945, REC_Y0 - 0.0285), (0.1180, REC_Y0 - 0.0290), (0.1380, REC_Y0 - 0.0230),
                 (0.1450, REC_Y0 - 0.0140), (0.1450, REC_Y0 + 0.0005)]
    guard_in = [(0.0830, REC_Y0 - 0.0010), (0.0868, REC_Y0 - 0.0175), (0.0968, REC_Y0 - 0.0240),
                (0.1160, REC_Y0 - 0.0245), (0.1320, REC_Y0 - 0.0195), (0.1390, REC_Y0 - 0.0120),
                (0.1390, REC_Y0 - 0.0010)]
    m.merge(sh.extrude_x(_chaikin(guard_out, 1), [_chaikin(guard_in, 1)], 0.0055, round_bevel(0.0009, 2), "steel"))
    # selector cut-out stops on the right (stamped detents)
    for k, y in enumerate((-0.0050, -0.0110)):
        m.merge(sh.box(REC_W, y, 0.1640 + 0.006 * k, REC_W + 0.0007, y + 0.0030, 0.1690 + 0.006 * k, 0.0002, "steel"))
    # rear sight base on the front trunnion (the notch plate and its drum are sight_rear)
    low = sh.rounded_rect(-0.0120, REC_Y1 - 0.0020, 0.0120, SIGHT_Y - 0.0175, (0.004, 0.004, 0.003, 0.003), 2)
    top = sh.rounded_rect(-0.0108, REC_Y1 - 0.0020, 0.0108, SIGHT_Y - 0.0115, (0.004, 0.004, 0.003, 0.003), 2)
    m.merge(sh.loft([_ring_xy(sh.offset_loop(low, 0.0010), -0.0030), _ring_xy(low, -0.0012),
                     _ring_xy(top, 0.0150), _ring_xy(top, REAR_NOTCH_Z - 0.0040),
                     _ring_xy(sh.offset_loop(top, 0.0010), REAR_NOTCH_Z - 0.0025)], "steel"))
    for s in (-1, 1):
        m.merge(_stud(SIGHT_Y - 0.0200, 0.0080, s * 0.0118, s, 0.0017, 0.0007))
    # stock hinge block on the left rear with its pin and the folding latch button
    m.merge(sh.box(-REC_W - 0.0055, -0.0300, REC_Z1 - 0.0300, -REC_W + 0.0004, 0.0080, REC_Z1 + 0.0020, 0.0012,
                   "steel"))
    pin = sh.revolve([(0.0, -0.0200), (0.0034, -0.0200), (0.0038, -0.0195), (0.0038, 0.0195), (0.0034, 0.0200),
                      (0.0, 0.0200)], 16, "steel_dark", precision=True)
    m.merge(pin.transformed(_y_up(HINGE[2], -0.0110, HINGE[0])))
    m.merge(_stud(-0.0150, REC_Z1 - 0.0160, -REC_W - 0.0055, -1, 0.0040, 0.0022, "steel_dark", 18))
    # rear sling loop (left, behind the grip)
    m.merge(sh.box(-REC_W - 0.0030, -0.0330, REC_Z1 - 0.0450, -REC_W + 0.0002, -0.0230, REC_Z1 - 0.0350, 0.0008,
                   "steel_dark"))
    loop = sh.revolve([(0.0052 + 0.0011 * np.cos(a), 0.0011 * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 7)], 14,
                      "steel_dark")
    m.merge(sh.place(loop, origin=(-REC_W - 0.0035, -0.0350, REC_Z1 - 0.0400), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                     z_axis=(1, 0, 0)))
    return m


def dust_cover_mesh():
    """Ribbed top cover with the recoil spring guide button at the rear and a low picatinny section on top."""
    def section(inflate=0.0):
        w, y0, top, r = REC_W + 0.0006 + inflate, REC_Y1 - 0.0050, COVER_TOP + inflate, 0.0110
        n = arc_segments(r, 6, np.pi / 2)
        pts = [(-w, y0), (w, y0)]
        for a in np.linspace(0.0, np.pi / 2, n + 1):
            pts.append((w - r + r * np.cos(a), top - r + r * np.sin(a)))
        for a in np.linspace(np.pi / 2, np.pi, n + 1):
            pts.append((-w + r + r * np.cos(a), top - r + r * np.sin(a)))
        return np.array(pts)
    s0 = section()
    m = sh.loft([_ring_xy(sh.offset_loop(s0, 0.0012), COVER_Z0), _ring_xy(s0, COVER_Z0 + 0.0014),
                 _ring_xy(s0, REC_Z1 - 0.0030), _ring_xy(sh.offset_loop(s0, 0.0010), REC_Z1)], "steel")
    for z in (0.0700, 0.2150):                          # stamped transverse ribs (clear of the rail)
        m.merge(sh.loft([_ring_xy(section(0.0004), z - 0.0045), _ring_xy(section(0.0011), z - 0.0020),
                         _ring_xy(section(0.0011), z + 0.0020), _ring_xy(section(0.0004), z + 0.0045)], "steel"))
    m.merge(_cyl_z(0.0042, REC_Z1 - 0.0010, REC_Z1 + 0.0055, "steel", 16, y=0.0150, chamfer=0.0012))
    # low picatinny section riveted on top: base, lands with the cross slots, two rivets each side
    base = np.array([(-0.0090, COVER_TOP - 0.0030), (0.0090, COVER_TOP - 0.0030), (0.0090, COVER_TOP + 0.0006),
                     (RAIL_W, COVER_TOP + 0.0012), (RAIL_W, COVER_TOP + RAIL_H - 0.0012),
                     (RAIL_W - 0.0012, COVER_TOP + RAIL_H), (-RAIL_W + 0.0012, COVER_TOP + RAIL_H),
                     (-RAIL_W, COVER_TOP + RAIL_H - 0.0012), (-RAIL_W, COVER_TOP + 0.0012),
                     (-0.0090, COVER_TOP + 0.0006)])
    n = int((RAIL_Z1 - RAIL_Z0 - 0.004) / 0.0100)
    z = RAIL_Z0 + 0.5 * (RAIL_Z1 - RAIL_Z0 - (n - 1) * 0.0100 - 0.0052)
    low = np.array([(-0.0090, COVER_TOP - 0.0030), (0.0090, COVER_TOP - 0.0030), (0.0090, COVER_TOP + 0.0016),
                    (-0.0090, COVER_TOP + 0.0016)])
    m.merge(extrude_z(low, (), RAIL_Z0, RAIL_Z1, chamfer_bevel(0.0006), "steel_dark"))
    for k in range(n):
        m.merge(extrude_z(base, (), z + k * 0.0100, z + k * 0.0100 + 0.0052, chamfer_bevel(0.0003), "steel_dark"))
    for zz in (RAIL_Z0 + 0.0080, RAIL_Z1 - 0.0080):
        for s in (-1, 1):
            m.merge(_stud(COVER_TOP - 0.0012, zz, s * (REC_W + 0.0006), s, 0.0016, 0.0006, "steel_dark", 12))
    return m


# ------------------------------------------------------------------------- barrel, sights, muzzle
def barrel_mesh():
    """Barrel from the trunnion to the crown (a slight step at the chamber end, the thread), the barrel nut and the
    clamp-on side rail (laser mount on its right face)."""
    m = sh.revolve([(0.0, CROWN_Z + 0.0120), (0.0046, CROWN_Z + 0.0120), (0.0046, CROWN_Z), (BARREL_R - 0.0010, CROWN_Z),
                    (BARREL_R - 0.0004, CROWN_Z - 0.0002 + 0.0006), (BARREL_R - 0.0004, CROWN_Z + 0.0140),
                    (BARREL_R, CROWN_Z + 0.0150), (BARREL_R, -0.0600), (BARREL_R + 0.0008, -0.0560),
                    (BARREL_R + 0.0008, 0.0020), (0.0, 0.0020)], arc_segments(BARREL_R, 24), "barrel", precision=True)
    # barrel nut against the front trunnion
    nut = [(0.0, -0.0006), (0.0112, -0.0006), (0.0118, -0.0012), (0.0118, -0.0105), (0.0110, -0.0112),
           (BARREL_R + 0.0006, -0.0112)]
    m.merge(sh.revolve(nut, 12, "steel_dark", precision=True))
    # side rail clamp: a split ring round the barrel, the short rail block on its right, two screws underneath
    z0, z1 = LASER_Z - 0.0140, LASER_Z + 0.0140
    m.merge(_cyl_z(BARREL_R + 0.0024, z0, z1, "steel_dark", arc_segments(BARREL_R + 0.0024, 24), chamfer=0.0007))
    rail = np.array([(BARREL_R + 0.0010, -0.0060), (LASER_X - 0.0020, -0.0060), (LASER_X - 0.0020, -0.0052),
                     (LASER_X, -0.0052), (LASER_X, 0.0052), (LASER_X - 0.0020, 0.0052), (LASER_X - 0.0020, 0.0060),
                     (BARREL_R + 0.0010, 0.0060)])
    m.merge(extrude_z(rail, (), z0 + 0.0010, z1 - 0.0010, chamfer_bevel(0.0005), "steel_dark"))
    for k in range(2):
        zz = z0 + 0.0070 + k * 0.0140
        m.merge(sh.box(LASER_X - 0.0006, -0.0058, zz - 0.0018, LASER_X + 0.0004, 0.0058, zz + 0.0018, 0.0002,
                       "steel_dark"))                                                   # rail slots' lands
    m.merge(_pin_x(-0.0085, LASER_Z - 0.0060, -0.0048, 0.0048, 0.0014))
    m.merge(_pin_x(-0.0085, LASER_Z + 0.0060, -0.0048, 0.0048, 0.0014))
    return m


def front_sight_mesh():
    """Front sight block: the collar round the barrel, the tower to the post, the cross pin, the front sling swivel
    and the magazine's front catch hanging under it (the ears are front_sight_hood, the post sight_iron)."""
    collar = sh.rounded_rect(-0.0122, -0.0117, 0.0122, 0.0122, 0.0090, 3)
    m = extrude_z(collar, (), FS_Z0, FS_Z1, round_bevel(0.0012, 2), "steel")
    tower = [(-0.0088, 0.0100), (0.0088, 0.0100), (0.0062, SIGHT_Y - 0.0130), (-0.0062, SIGHT_Y - 0.0130)]
    m.merge(extrude_z(np.array(tower), (), FRONT_POST_Z - 0.0120, FRONT_POST_Z + 0.0095, round_bevel(0.0008, 1),
                      "steel"))
    for s in (-1, 1):
        m.merge(_stud(0.0040, FS_Z1 - 0.0080, s * 0.0122, s, 0.0018, 0.0006))
    # the magazine's front catch: a stamped tab hanging from the block's front, ahead of the magazine's nose; the
    # nose lug butts against its rear face and its lip curls back under the lug
    catch = [(FS_Z0 + 0.0010, -0.0100), (FS_Z0 - 0.0008, -0.0130), (FS_Z0 - 0.0008, -0.0215), (FS_Z0 + 0.0030, -0.0232),
             (LUG_Z0 + 0.0060, -0.0232), (LUG_Z0 + 0.0060, -0.0196), (LUG_Z0, -0.0190), (LUG_Z0, -0.0100)]
    m.merge(sh.extrude_x(_chaikin(catch, 1), (), 0.0072, round_bevel(0.0007, 1), "steel_dark"))
    # front sling swivel on the left
    m.merge(sh.box(-0.0150, -0.0050, FS_Z0 + 0.0060, -0.0118, 0.0040, FS_Z1 - 0.0060, 0.0009, "steel"))
    loop = sh.revolve([(0.0055 + 0.0010 * np.cos(a), 0.0010 * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 7)], 14,
                      "steel_dark")
    m.merge(sh.place(loop, origin=(-0.0165, -0.0090, 0.5 * (FS_Z0 + FS_Z1)), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                     z_axis=(1, 0, 0)))
    return m


def front_hood_mesh():
    """The two protective ears round the front post (node `front_sight_hood`, under sight_iron: hidden with it while
    an optic is fitted)."""
    m = Mesh()
    for s in (-1, 1):
        ear = [(s * 0.0040, SIGHT_Y - 0.0150), (s * 0.0092, SIGHT_Y - 0.0150), (s * 0.0086, SIGHT_Y + 0.0035),
               (s * 0.0072, SIGHT_Y + 0.0060), (s * 0.0052, SIGHT_Y + 0.0062), (s * 0.0040, SIGHT_Y + 0.0050)]
        ear = ear if s > 0 else ear[::-1]
        m.merge(extrude_z(np.array(ear), (), FRONT_POST_Z - 0.0075, FRONT_POST_Z + 0.0065, round_bevel(0.0005, 1),
                          "steel"))
    return m


def front_post_mesh():
    """Front sight post (node `sight_iron`)."""
    post = np.array([(-0.0011, SIGHT_Y - 0.0140), (0.0011, SIGHT_Y - 0.0140), (0.0011, SIGHT_Y - 0.0006),
                     (0.0006, SIGHT_Y), (-0.0006, SIGHT_Y), (-0.0011, SIGHT_Y - 0.0006)])
    m = extrude_z(post, (), FRONT_POST_Z - 0.0012, FRONT_POST_Z + 0.0012, chamfer_bevel(0.0002), "steel_dark")
    m.merge(_cyl_z(0.0024, FRONT_POST_Z - 0.0016, FRONT_POST_Z + 0.0016, "steel_dark", 12, y=SIGHT_Y - 0.0140))
    return m


def rear_sight_mesh():
    """Open rear sight (node `sight_rear`; hidden while an optic is fitted): the notch plate on the front trunnion's
    sight base, its top no higher than the sight line, and the range drum on its right. No hood: nothing rises above
    the sight line between the eye and the front post (check_bizon tests the sight picture)."""
    m = Mesh()
    notch = np.array([(-0.0085, SIGHT_Y - 0.0110), (0.0085, SIGHT_Y - 0.0110), (0.0085, SIGHT_Y - 0.0008),
                      (0.0070, SIGHT_Y), (0.0013, SIGHT_Y), (0.0013, SIGHT_Y - 0.0032), (-0.0013, SIGHT_Y - 0.0032),
                      (-0.0013, SIGHT_Y), (-0.0070, SIGHT_Y), (-0.0085, SIGHT_Y - 0.0008)])
    m.merge(extrude_z(notch, (), REAR_NOTCH_Z - 0.0030, REAR_NOTCH_Z, chamfer_bevel(0.0003), "steel_dark"))
    # range drum on the right side of the notch plate, below the sight line
    drum = sh.revolve([(0.0, 0.0), (0.0042, 0.0), (0.0046, 0.0004), (0.0046, 0.0030), (0.0040, 0.0034), (0.0, 0.0034)],
                      18, "steel_dark", precision=True)
    m.merge(sh.place(drum, origin=(0.0085, SIGHT_Y - 0.0070, REAR_NOTCH_Z - 0.0060), x_axis=(0, 1, 0),
                     y_axis=(0, 0, 1), z_axis=(1, 0, 0)))
    for k in range(6):                                   # knurl ribs round the drum
        a = 2 * np.pi * k / 6
        rib = sh.box(0.0087, -0.0005, -0.0005, 0.0117, 0.0005, 0.0005, 0.0001, "steel_dark")
        m.merge(sh.place(rib, origin=(0.0, SIGHT_Y - 0.0070 + 0.0046 * np.sin(a), REAR_NOTCH_Z - 0.0060 + 0.0046 * np.cos(a))))
    return m


def muzzle_nut_mesh():
    """Slotted muzzle nut (node `muzzle_brake`; hidden while the barrel slot holds something): a sleeve on the
    thread, a bevelled front face round the bore, two vent slots each side and the detent tooth."""
    prof = [(0.0, CROWN_Z + 0.0125), (0.0108, CROWN_Z + 0.0125), (0.0116, CROWN_Z + 0.0118), (0.0116, MUZZLE_Z + 0.0035),
            (0.0108, MUZZLE_Z + 0.0004), (0.0094, MUZZLE_Z), (0.0058, MUZZLE_Z), (0.0058, CROWN_Z - 0.0060),
            (0.0, CROWN_Z - 0.0060)]
    m = sh.revolve(prof, arc_segments(0.0116, 28), "barrel", precision=True)
    for s in (-1, 1):
        for k in range(2):
            z = CROWN_Z - 0.0060 - k * 0.0110
            x0, x1 = sorted((s * 0.0098, s * 0.0119))
            m.merge(sh.box(x0, -0.0016, z - 0.0080, x1, 0.0016, z, 0.0003, "steel_dark"))
    m.merge(sh.box(-0.0016, -0.0128, CROWN_Z + 0.0020, 0.0016, -0.0108, CROWN_Z + 0.0100, 0.0004, "barrel"))
    return m


# ------------------------------------------------------------------------------------ furniture
def pistol_grip_mesh():
    """AK-74 polymer grip, raked 20 degrees: full back strap, a palm swell, the finger ribs down its front, a flared
    base with the cap screw."""
    rings = []
    n = 10
    w = depth = 0.0
    for k in range(n + 1):
        u = k / n
        d = -0.0040 + (GRIP_LEN + 0.0088) * u
        c = grip_center(d)
        depth = 0.0172 + 0.0018 * np.sin(np.pi * u) + 0.0016 * smooth(0.85, 1.0, u)
        w = 0.0124 + 0.0012 * np.sin(np.pi * u) + 0.0010 * smooth(0.85, 1.0, u)
        sec = sh.superellipse(w, depth, 2.5, 32, n_front=2.2)
        rings.append(np.array([c + p[0] * np.array([1.0, 0, 0]) + p[1] * GRIP_FRONT for p in sec]))
    rings, soft = sd.round_end(rings, 0.0045)                          # the flared base rounded over
    m = sh.loft(rings, "polymer", soft_bands=soft)
    for k in range(4):                                                  # finger ribs down the front strap
        d = 0.0220 + k * 0.0175
        c = grip_center(d) + GRIP_FRONT * 0.0170
        m.merge(sd.rib(c - (0.0090, 0, 0), c + (0.0090, 0, 0), 0.0026, 0.0011, GRIP_FRONT, "polymer"))
    screw = sh.revolve([(0.0, 0.0), (0.0030, 0.0), (0.0030, 0.0010), (0.0022, 0.0014), (0.0, 0.0014)], 12, "steel_dark")
    m.merge(sh.place(screw, origin=grip_center(GRIP_LEN + 0.0050), x_axis=(1, 0, 0), y_axis=-GRIP_FRONT,
                     z_axis=GRIP_AXIS))
    return m


def stock_mesh():
    """AKS-74 side-folding stock, unfolded: the hinge knuckle on the receiver's left rear, the upper and lower
    struts converging on it, the stamped butt plate with its rib and the rubber pad."""
    m = Mesh()
    knuckle = np.array([HINGE[0] + 0.0040, -0.0110, REC_Z1 + 0.0080])
    m.merge(sh.box(-0.0085, -0.0300, REC_Z1 + 0.0010, 0.0060, 0.0060, REC_Z1 + 0.0170, 0.0018, "steel"))   # yoke
    top0, bot0 = np.array([0.0, 0.0010, REC_Z1 + 0.0150]), np.array([0.0, -0.0250, REC_Z1 + 0.0150])
    top1, bot1 = np.array([0.0, 0.0050, BUTT_Z - 0.0120]), np.array([0.0, -0.0880, BUTT_Z - 0.0120])
    m.merge(_tube(top0, top1, 0.0068, "steel", 20))
    m.merge(_tube(bot0, bot1, 0.0060, "steel", 20))
    for p, r in ((top1, 0.0068), (bot1, 0.0060)):                       # weld collars at the plate
        m.merge(_tube(p - np.array([0.0, 0.0, 0.0050]), p + np.array([0.0, 0.0, 0.0030]), r + 0.0012, "steel", 20))
    m.merge(sh.box(knuckle[0] - 0.0030, knuckle[1] - 0.0120, knuckle[2] - 0.0060, knuckle[0] + 0.0030,
                   knuckle[1] + 0.0120, knuckle[2] + 0.0060, 0.0015, "steel"))
    # butt plate: a stamped plate, tall and narrow, a pressed rib down its middle
    plate = [(BUTT_Z - 0.0140, 0.0140), (BUTT_Z - 0.0140, -0.1000), (BUTT_Z - 0.0060, -0.1060),
             (BUTT_Z - 0.0010, -0.1040), (BUTT_Z - 0.0010, 0.0160), (BUTT_Z - 0.0060, 0.0200)]
    m.merge(sh.extrude_x(_chaikin(plate, 1), (), 0.0150, round_bevel(0.0025, 2), "steel"))
    m.merge(sh.box(-0.0030, -0.0920, BUTT_Z - 0.0160, 0.0030, 0.0080, BUTT_Z - 0.0130, 0.0010, "steel"))
    pad = [(BUTT_Z - 0.0012, 0.0170), (BUTT_Z - 0.0012, -0.1030), (BUTT_Z + 0.0090, -0.0990), (BUTT_Z + 0.0105, -0.0450),
           (BUTT_Z + 0.0100, 0.0080), (BUTT_Z + 0.0070, 0.0150)]
    m.merge(sh.extrude_x(_chaikin(pad, 2), (), 0.0158, round_bevel(0.0035, 3), "stock"))
    for k in range(5):                                                  # pad ribs
        y = -0.0850 + k * 0.0200
        m.merge(sd.rib((-0.0125, y, BUTT_Z + 0.0098), (0.0125, y, BUTT_Z + 0.0098), 0.0060, 0.0014, (0, 0, 1), "stock",
                       seg=9))
    return m


# ------------------------------------------------------------------------------------ moving parts
def bolt_mesh():
    """Node `bolt`: the bolt carrier's top (seen through the port) and the AK charging handle on the right: the
    stem riding out of the receiver slot and the knurled round knob; rides the stroke (+Z)."""
    m = sh.box(-0.0115, -0.0020, 0.0420, 0.0135, 0.0130, 0.1100, 0.0015, "steel_dark")   # fills the port at rest
    stem = [(HANDLE_Z - 0.0075, 0.0012), (HANDLE_Z + 0.0080, 0.0012), (HANDLE_Z + 0.0080, 0.0108),
            (HANDLE_Z - 0.0045, 0.0108)]
    m.merge(sh.extrude_x(np.array(stem), (), 0.0062, round_bevel(0.0007, 1), "steel_dark", x_center=REC_W + 0.0040))
    knob = sh.revolve([(0.0, 0.0), (0.0046, 0.0), (0.0055, 0.0009), (0.0057, 0.0030), (0.0057, 0.0095),
                       (0.0050, 0.0122), (0.0034, 0.0134), (0.0, 0.0137)], 20, "steel_dark", precision=True)
    knob = sh.place(knob, x_axis=(0, 0, -1), y_axis=(0, 1, 0), z_axis=(1, 0, 0))
    m.merge(knob.translated((REC_W + 0.0080, 0.0060, HANDLE_Z + 0.0010)))
    for k in range(3):                                                  # knurl rings round the knob
        x = REC_W + 0.0118 + k * 0.0026
        ring = sh.revolve([(0.0058, -0.0004), (0.0062, 0.0), (0.0058, 0.0004)], 20, "steel_dark")
        m.merge(sh.place(ring, origin=(x, 0.0060, HANDLE_Z + 0.0010), x_axis=(0, 1, 0), y_axis=(0, 0, 1),
                         z_axis=(1, 0, 0)))
    return m


def selector_mesh():
    """The big AK safety / selector lever on the right (rotates about X at its rear pivot), its finger tab."""
    p = SELECTOR_PIVOT
    prof = [(p[2] + 0.0080, 0.0080), (p[2] - 0.0050, 0.0110), (0.1150, 0.0100), (0.1000, 0.0132), (0.0915, 0.0110),
            (0.0915, -0.0012), (0.1020, 0.0010), (0.1150, 0.0030), (p[2] - 0.0050, -0.0030), (p[2] + 0.0080, -0.0040)]
    m = sh.extrude_x(_chaikin(prof, 1), (), 0.0008, chamfer_bevel(0.0003), "steel_dark", x_center=REC_W + 0.0012)
    hub = sh.revolve([(0.0, 0.0), (0.0055, 0.0), (0.0058, 0.0004), (0.0058, 0.0026), (0.0052, 0.0030), (0.0, 0.0030)],
                     18, "steel_dark", precision=True)
    m.merge(sh.place(hub, origin=(REC_W, p[1], p[2]), x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0)))
    m.merge(sh.box(REC_W + 0.0012, 0.0000, 0.0915, REC_W + 0.0050, 0.0110, 0.0960, 0.0006, "steel_dark"))
    return m


def trigger_mesh():
    z = TRIGGER_PIVOT[2]
    y = TRIGGER_PIVOT[1]
    blade = [(z - 0.0040, y + 0.0070), (z + 0.0030, y + 0.0070), (z + 0.0035, y - 0.0010), (z + 0.0025, y - 0.0080),
             (z - 0.0005, y - 0.0140), (z - 0.0040, y - 0.0175), (z - 0.0060, y - 0.0165), (z - 0.0040, y - 0.0120),
             (z - 0.0025, y - 0.0060), (z - 0.0025, y + 0.0010)]
    return sh.extrude_x(_chaikin(blade, 1), (), 0.0030, round_bevel(0.0010, 2), "steel_dark")


def paddle_mesh():
    """AK magazine release behind the magazine (node `paddle`, rotates about X at its pivot: pushed forward)."""
    p = PADDLE_PIVOT
    prof = [(p[2] - 0.0030, p[1] + 0.0010), (p[2] + 0.0030, p[1] + 0.0010), (p[2] + 0.0030, p[1] - 0.0080),
            (p[2] + 0.0050, p[1] - 0.0130), (p[2] + 0.0025, p[1] - 0.0145), (p[2] - 0.0020, p[1] - 0.0120),
            (p[2] - 0.0030, p[1] - 0.0060)]
    m = sh.extrude_x(_chaikin(prof, 1), (), 0.0058, round_bevel(0.0008, 1), "steel_dark")
    for k in range(3):                                                  # grip ridges on its rear face
        y = p[1] - 0.0070 - 0.0022 * k
        m.merge(sh.box(-0.0050, y, p[2] + 0.0026, 0.0050, y + 0.0009, p[2] + 0.0038 + 0.0005 * k, 0.0003, "steel_dark"))
    return m


# ------------------------------------------------------------------------------------ magazine
FLUTES = (-128.0, -104.0, -76.0, -52.0)        # finger flutes under the magazine (deg about its axis, 0 = right)


def _mag_section(r, flute=1.0, seg=72):
    """Magazine cross-section (x, y) about its axis, CCW: a circle with shallow finger flutes along the bottom."""
    pts = []
    for a in np.linspace(0.0, 2 * np.pi, seg, endpoint=False):
        d = 0.0
        for c in FLUTES:
            u = (np.degrees(a) - 360.0 - c) if np.degrees(a) > 180 else (np.degrees(a) - c)
            d = max(d, np.cos(np.clip(u / 9.0, -1.0, 1.0) * np.pi / 2) ** 2)
        rr = r - 0.0011 * flute * d
        pts.append((rr * np.cos(a), rr * np.sin(a)))
    return np.array(pts)


def mag_mesh():
    """The 64-round helical magazine (mag-local: origin at the top of its latch, +Z rear, -Z front): a fluted polymer
    cylinder with rounded ends, the moulded bands, the feed tower rising into the receiver at its rear, the nose lug
    that hooks into the front catch, the latch lug on its rear face and the round-count window down its left side."""
    yc = MAG_AXIS_Y
    z0, z1 = MAG_Z0 - MAG_ORIGIN[2], MAG_Z1 - MAG_ORIGIN[2]
    zs = [z0 + 0.0040, z0 + 0.0120, z0 + 0.0130, -0.1600, -0.1100, -0.0600, -0.0300, z1 - 0.0160, z1 - 0.0150,
          z1 - 0.0040]
    rings = []
    for z in zs:
        fl = smooth(z0 + 0.0130, z0 + 0.0250, z) * (1 - smooth(z1 - 0.0300, z1 - 0.0170, z))
        sec = _mag_section(MAG_R, fl)
        rings.append(_ring_xy(sec + np.array([0.0, yc]), z))
    m = sh.loft(rings, "mag")
    # rounded nose and rear (lathe profiles round the axis)
    seg = 72
    nose = [(0.0, z0 - 0.0060), (0.0090, z0 - 0.0052), (0.0170, z0 - 0.0030), (0.0230, z0 - 0.0005),
            (0.0256, z0 + 0.0020), (MAG_R, z0 + 0.0040)]
    m.merge(sh.revolve(nose, seg, "mag_base", precision=True).translated((0.0, yc, 0.0)))
    rear = [(MAG_R, z1 - 0.0040), (MAG_R - 0.0006, z1 - 0.0012), (MAG_R - 0.0026, z1), (0.0, z1)]
    m.merge(sh.revolve(rear, seg, "mag_base", precision=True).translated((0.0, yc, 0.0)))
    # moulded bands: a raised ring behind the nose and one in front of the rear end
    for zz in (z0 + 0.0125, z1 - 0.0155):
        band = sh.revolve([(MAG_R - 0.0004, zz - 0.0030), (MAG_R + 0.0008, zz - 0.0024), (MAG_R + 0.0008, zz + 0.0024),
                           (MAG_R - 0.0004, zz + 0.0030)], seg, "mag_base", precision=True)
        m.merge(band.translated((0.0, yc, 0.0)))
    # feed tower: the rear top rising into the receiver (its lips hold the top round), the latch block behind it
    tower = sh.rounded_rect(-0.0108, -0.0030, 0.0108, 0.0090, 0.0020, 2)
    m.merge(extrude_z(tower, (), z1 - 0.0480, z1 - 0.0010, round_bevel(0.0008, 1), "mag_base"))
    m.merge(sh.box(-0.0040, -0.0160, z1 - 0.0010, 0.0040, -0.0060, z1 + 0.0024, 0.0006, "mag_base"))   # latch lug
    # nose lug along the top of the front end, reaching forward to butt against the front catch
    lz0 = LUG_Z0 - MAG_ORIGIN[2]
    lug = [(lz0, yc + MAG_R - 0.0050), (lz0, yc + MAG_R + 0.0004), (lz0 + 0.0060, yc + MAG_R + 0.0010),
           (z0 + 0.0140, yc + MAG_R + 0.0004), (z0 + 0.0160, yc + MAG_R - 0.0040), (z0 + 0.0020, yc + MAG_R - 0.0060)]
    m.merge(sh.extrude_x(np.array(lug), (), 0.0042, round_bevel(0.0006, 1), "mag_base"))
    # round-count window down the left side: a recessed strip with the cartridge noses showing through
    a = np.radians(180.0 - 18.0)
    win = sh.box(-0.0016, -0.0024, -0.0700, 0.0010, 0.0024, -0.0700 + 0.1200, 0.0004, "mag_base")
    m.merge(sh.place(win, origin=(MAG_R * np.cos(a), yc + MAG_R * np.sin(a), 0.0), x_axis=(np.cos(a), np.sin(a), 0),
                     y_axis=(-np.sin(a), np.cos(a), 0), z_axis=(0, 0, 1)))
    for k in range(9):
        z = -0.0640 + k * 0.0130
        tip = sh.revolve([(0.0, 0.0), (0.0016, 0.0002), (0.0018, 0.0016), (0.0, 0.0018)], 8, "brass")
        m.merge(sh.place(tip, origin=((MAG_R - 0.0005) * np.cos(a), yc + (MAG_R - 0.0005) * np.sin(a), z),
                         x_axis=(0, 0, 1), y_axis=(-np.sin(a), np.cos(a), 0), z_axis=(np.cos(a), np.sin(a), 0)))
    return m


def shell_mesh(scale=1.15):
    """Spent 9x18 Makarov brass case (material `brass`), along Z, mouth towards -Z."""
    prof = [(0.0, 0.0090), (0.0046, 0.0090), (0.00495, 0.0084), (0.00495, 0.0080), (0.0041, 0.0077),
            (0.0041, 0.0069), (0.00493, 0.0065), (0.00485, -0.0090), (0.0045, -0.0090), (0.0045, -0.0076),
            (0.0, -0.0076)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 16, "brass", precision=True)
