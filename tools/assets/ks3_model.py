"""KS-3 pump shotgun geometry, VIEWMODEL_SPEC section 25. Design frame (metres): +X right, +Y up, +Z rearward; bore
axis on x = y = 0, z = 0 at the receiver's front face. Real scale (12 gauge, 0.49 m barrel, 0.99 m overall).

The receiver, pistol grip, pump forend, trigger and loading gate come from *Shotgun* by DJMaesen (CC-BY-4.0) through
ks3_source (source/ks3_parts.npz); everything else is ours: the barrel with its ventilated rib and choke, the
picatinny rail, the iron sights, the skeleton buttstock, both magazine tubes (standard and extended to the muzzle with a
barrel clamp), the laser clamp, the action bars and sleeves of the pump, the crossbolt safety and the shotshells.

Every builder returns a meshkit.Mesh in the design frame; gen_ks3 moves meshes into node-local space.

Materials (stable names, texture sets of section 20 key on them) and their regions (section 21):
receiver -> body, barrel -> barrel, pump -> handguard, stock -> stock, grip -> grip, steel_dark -> detail,
shell -> none, brass -> none."""
import numpy as np

import ks3_source
import shapes as sh
from ak_model import _cyl_z
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z

MATERIAL_REGIONS = {"receiver": "body", "barrel": "barrel", "pump": "handguard", "stock": "stock", "grip": "grip",
                    "steel_dark": "detail", "shell": "none", "brass": "none"}

# ---- key dimensions (the source's are measured from source/ks3_parts.npz) -------------------------------------
REC_W = 0.0167                    # receiver half width
REC_TOP = 0.0151                  # receiver top
REC_BOT = -0.0449                 # receiver bottom at its front face
REC_Z1 = 0.2460                   # receiver rear face (above the grip)
RAIL_Z0, RAIL_Z1 = 0.0200, 0.2050  # picatinny rail on the receiver top
RAIL_H, RAIL_W = 0.0060, 0.0105   # rail height over the receiver, half width
OPTIC_Z = 0.1150                  # optic base centre on the rail
BARREL_R = 0.01034                # barrel outside radius (source)
BORE_R = 0.0095                   # 12 gauge
CHOKE_Z = -0.4420                 # extended choke tube from here to the muzzle
MUZZLE_Z = -0.4896                # crown (source)
RIB_Z0, RIB_Z1 = -0.0080, -0.4360  # ventilated rib on the barrel
RIB_TOP, RIB_W = BARREL_R + 0.0042, 0.0038
SIGHT_Y = 0.0240                  # iron sight line: just clears the rail top (REC_TOP + RAIL_H = 0.0211)
FRONT_SIGHT_Z = -0.4250
REAR_SIGHT_Z = RAIL_Z1 - 0.0040   # rear notch on the end of the rail
TUBE_Y, TUBE_R = -0.0272, 0.0138  # magazine tube axis and radius (source)
TUBE_END = -0.3420                # standard (6-round) tube end, before its cap
TUBE_EXT_END = -0.4560            # extended tube runs out to the barrel clamp
PUMP_TRAVEL = 0.0620              # forend stroke (+Z): stops short of the receiver face
STOCK_W = 0.0135                  # buttstock half width
BUTT_Z = 0.4880                   # rear face of the butt pad
COMB_Y = -0.0090                  # top of the comb (3.3 cm under the sight line: the cheek sits there)
LASER_Z = -0.3700                 # laser clamp on the barrel (in front of the forend's forward stop)
EJECTION = np.array([REC_W + 0.0005, 0.0030, 0.0520])
CHARM_POINT = np.array([-REC_W - 0.0016, -0.0300, 0.2150])   # sling loop low on the left of the receiver rear
# pistol grip (source shape): top of the front strap and the grip's axis down and back
GRIP_TOP = np.array([0.0, -0.0660, 0.2050])
GRIP_ANGLE = 25.0                 # the grip's axis leans back this much from vertical (principal axis of its vertices)
GRIP_AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])
GRIP_FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])
SHELL_LEN, SHELL_R, HEAD_LEN, RIM_R = 0.0700, 0.0102, 0.0160, 0.0111

_SOURCE = None


def source():
    global _SOURCE
    if _SOURCE is None:
        _SOURCE = ks3_source.load()
    return _SOURCE


def pivot(name):
    return source()[1][name].copy()


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def from_source(part, material):
    pos, tris = source()[0][part]
    m = Mesh()
    m.add_vertices(pos)
    for t in tris:
        m.add_face(t, material)
    return m


# -------------------------------------------------------------------------------------- receiver
def rail_mesh():
    """Picatinny rail on the receiver top (optics mount on it); the cross slots read as the teeth."""
    top = REC_TOP + RAIL_H
    m = sh.box(-RAIL_W, REC_TOP - 0.0020, RAIL_Z0, RAIL_W, top, RAIL_Z1, 0.0008, "receiver")
    for k in range(int((RAIL_Z1 - RAIL_Z0 - 0.006) / 0.010)):
        z = RAIL_Z0 + 0.006 + k * 0.010
        m.merge(sh.box(-RAIL_W - 0.0003, top - 0.0020, z, RAIL_W + 0.0003, top + 0.0003, z + 0.0050, 0.0,
                       "receiver"))
    for z in (RAIL_Z0 + 0.012, RAIL_Z1 - 0.012):                                    # screws into the receiver
        m.merge(_cyl_z(0.0020, -0.0004, 0.0006, "steel_dark", 10).transformed(_rx_to_y()).translated((0, top, z)))
    return m


def _rx_to_y():
    """Rotation taking +Z to +Y (heads of screws that face up)."""
    m = np.eye(4)
    m[:3, :3] = np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]], float)
    return m


def receiver_mesh():
    """The source receiver (trigger guard, ejection port, rear block) with its hardware, our rail on top."""
    m = from_source("receiver", "receiver")
    m.merge(from_source("hardware", "steel_dark"))
    m.merge(rail_mesh())
    return m


def grip_mesh():
    return from_source("grip", "grip")


def safety_mesh():
    """Crossbolt safety through the rear of the trigger guard (pushed to the right = safe off)."""
    m = _cyl_z(0.0032, -REC_W + 0.0060, REC_W + 0.0035, "steel_dark", 12, chamfer=0.0006)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated((0.0, -0.0560, 0.1870))


# ------------------------------------------------------------------------------- barrel and rib
def barrel_mesh():
    """Barrel from inside the receiver to the crown, a knurled extended choke, the ventilated rib on posts and the
    laser clamp with its side rail (the laser mount sits on it)."""
    r, c = BARREL_R, 0.0006
    m0, c0 = MUZZLE_Z, CHOKE_Z                       # the choke runs from the crown (m0) back to c0
    prof = [(0.0, m0 + 0.0100), (BORE_R, m0 + 0.0100), (BORE_R, m0), (0.0122, m0), (0.0128, m0 + 0.0010),
            (0.0128, m0 + 0.0100), (0.0124, m0 + 0.0110), (0.0124, c0 - 0.0130), (0.0128, c0 - 0.0120),
            (0.0128, c0 - 0.0010), (r + 0.0006, c0), (r, c0 + c), (r, -0.0120), (0.0122, -0.0110),
            (0.0122, 0.0040), (0.0, 0.0040)]
    m = sh.revolve(prof, 24, "barrel", precision=True)
    # knurling on the choke: ridges between the two bands
    for k in range(12):
        a = 2 * np.pi * k / 12
        x, y = 0.01245 * np.cos(a), 0.01245 * np.sin(a)
        m.merge(sh.box(-0.0011, -0.0004, m0 + 0.0110, 0.0011, 0.0004, c0 - 0.0130, 0.0,
                       "barrel").transformed(_rot_z(np.degrees(a) - 90)).translated((x, y, 0.0)))
    m.merge(rib_mesh())
    m.merge(laser_clamp_mesh())
    return m


def _rot_z(deg):
    a = np.radians(deg)
    M = np.eye(4)
    M[:2, :2] = [[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]]
    return M


def rib_mesh():
    """Flat ventilated rib: a strip on posts every 4 cm, cut square at both ends."""
    m = sh.box(-RIB_W, RIB_TOP - 0.0015, RIB_Z1, RIB_W, RIB_TOP, RIB_Z0, 0.0004, "barrel")
    n = int(round((RIB_Z0 - RIB_Z1) / 0.040))
    for k in range(n + 1):
        z = RIB_Z1 + 0.004 + k * (RIB_Z0 - RIB_Z1 - 0.008) / n
        m.merge(sh.box(-0.0016, BARREL_R - 0.0010, z - 0.0030, 0.0016, RIB_TOP - 0.0014, z + 0.0030, 0.0, "barrel"))
    return m


def laser_clamp_mesh():
    """Steel clamp ring round the barrel with a short side rail on the right (mount_laser)."""
    seg = [(BARREL_R + 0.0001, LASER_Z - 0.0090), (BARREL_R + 0.0026, LASER_Z - 0.0090),
           (BARREL_R + 0.0030, LASER_Z - 0.0085), (BARREL_R + 0.0030, LASER_Z + 0.0085),
           (BARREL_R + 0.0026, LASER_Z + 0.0090), (BARREL_R + 0.0001, LASER_Z + 0.0090)]
    m = sh.revolve(seg, 20, "steel_dark", precision=True)
    m.merge(sh.box(BARREL_R + 0.0012, -0.0060, LASER_Z - 0.0110, BARREL_R + 0.0062, 0.0060, LASER_Z + 0.0110, 0.0006,
                   "steel_dark"))
    return m


def front_sight_mesh():
    """Front post on a ramp at the end of the rib, a brass bead on top (node-local: origin at the bead)."""
    z = FRONT_SIGHT_Z
    side = [(z - 0.0080, RIB_TOP - 0.0002), (z + 0.0070, RIB_TOP - 0.0002), (z + 0.0040, SIGHT_Y - 0.0050),
            (z + 0.0010, SIGHT_Y - 0.0020), (z - 0.0010, SIGHT_Y - 0.0020), (z - 0.0050, SIGHT_Y - 0.0080)]
    m = sh.extrude_x(side, (), 0.0019, chamfer_bevel(0.0004), "steel_dark")
    bead = sh.revolve([(0.0, 0.0016), (0.0010, 0.0014), (0.0016, 0.0), (0.0010, -0.0014), (0.0, -0.0016)], 10, "brass", precision=True)
    m.merge(bead.translated((0.0, SIGHT_Y - 0.0016, z)))                  # bead top on the sight line
    return m.translated((0.0, -SIGHT_Y, -z))


def rear_sight_mesh():
    """Low notch block clamped to the rear of the rail (node-local: origin at the notch bottom)."""
    z = REAR_SIGHT_Z
    base = sh.box(-RAIL_W - 0.0008, REC_TOP + RAIL_H - 0.0010, z - 0.0055, RAIL_W + 0.0008, SIGHT_Y, z + 0.0035,
                  0.0006, "steel_dark")                                       # the notch bottom is the sight line
    for s in (-1, 1):                                             # the notch: two ears either side of the sight line
        base.merge(sh.box(0.0012 if s > 0 else -0.0062, SIGHT_Y - 0.0002, z - 0.0035,
                          0.0062 if s > 0 else -0.0012, SIGHT_Y + 0.0030, z + 0.0015, 0.0, "steel_dark"))
    return base.translated((0.0, -SIGHT_Y, -z))


# ------------------------------------------------------------------------------ magazine tubes
def tube_mesh(end, extended):
    """Magazine tube from inside the receiver to `end`, with a knurled end cap; the extended tube also carries a
    barrel clamp holding it to the barrel near the muzzle."""
    r = TUBE_R
    prof = [(0.0, 0.0050), (r, 0.0050), (r, end + 0.0030), (r + 0.0006, end + 0.0020), (r + 0.0006, end - 0.0120),
            (r - 0.0004, end - 0.0145), (0.0045, end - 0.0150), (0.0045, end - 0.0170), (0.0, end - 0.0170)]
    m = sh.revolve(prof, 20, "barrel", precision=True)
    for k in range(10):                                                        # cap knurl ridges
        a = 2 * np.pi * (k + 0.5) / 10
        m.merge(sh.box(-0.0010, -0.0003, end - 0.0115, 0.0010, 0.0006, end + 0.0010, 0.0, "barrel")
                .transformed(_rot_z(np.degrees(a) - 90)).translated(((r + 0.0005) * np.cos(a), (r + 0.0005) * np.sin(a), 0)))
    m = m.translated((0.0, TUBE_Y, 0.0))
    if extended:
        z = end + 0.0030
        clamp = Mesh()
        for y, rr in ((0.0, BARREL_R), (TUBE_Y, TUBE_R)):
            clamp.merge(sh.revolve([(rr + 0.0001, z - 0.0060), (rr + 0.0024, z - 0.0060), (rr + 0.0028, z - 0.0055),
                                    (rr + 0.0028, z + 0.0055), (rr + 0.0024, z + 0.0060), (rr + 0.0001, z + 0.0060)],
                                   18, "steel_dark", precision=True).translated((0.0, y, 0.0)))
        clamp.merge(sh.box(-0.0060, TUBE_Y + TUBE_R - 0.0010, z - 0.0058, 0.0060, -BARREL_R + 0.0010, z + 0.0058,
                           0.0010, "steel_dark"))
        m.merge(clamp)
    return m


# -------------------------------------------------------------------------------------- pump
def pump_mesh():
    """The source forend (with its bolt and action-bar stubs), our steel action bars along the tube into the receiver
    and sleeves at both ends of the forend's channel round the tube (node-local: origin at the pump's pivot)."""
    m = from_source("pump", "pump")
    o = pivot("pump")
    z0, z1 = -0.0760, 0.0060                         # forend rear end .. inside the receiver at rest
    for s in (-1, 1):
        m.merge(sh.box(s * 0.0142 - 0.0012, TUBE_Y + 0.0070, z0, s * 0.0142 + 0.0012, TUBE_Y + 0.0120, z1, 0.0004,
                       "steel_dark"))
    for z_a, z_b in ((-0.3050, -0.2960), (-0.0800, -0.0710)):
        ring = [(TUBE_R + 0.0004, z_a), (0.0192, z_a), (0.0192, z_b), (TUBE_R + 0.0004, z_b)]
        m.merge(sh.revolve(ring, 18, "steel_dark", precision=True).translated((0.0, TUBE_Y, 0.0)))
    return m.translated(-o)


def loading_gate_mesh():
    return from_source("loading_gate", "steel_dark").translated(-pivot("loading_gate"))


def trigger_mesh():
    return from_source("trigger", "steel_dark").translated(-pivot("trigger"))


# -------------------------------------------------------------------------------------- stock
def stock_mesh():
    """Skeleton buttstock: comb bar from the top of the receiver's rear block, a lower strut from above the grip,
    an open window between them, a butt with a ribbed rubber pad (all polymer)."""
    outer = [(REC_Z1 - 0.0010, 0.0110), (REC_Z1 + 0.0250, 0.0080), (0.3300, COMB_Y), (BUTT_Z - 0.0260, COMB_Y),
             (BUTT_Z - 0.0140, COMB_Y - 0.0030), (BUTT_Z - 0.0120, -0.1120), (BUTT_Z - 0.0300, -0.1200),
             (0.4050, -0.1060), (0.3000, -0.0640), (REC_Z1 + 0.0120, -0.0420), (REC_Z1 - 0.0010, -0.0380)]
    window = [(0.3150, -0.0300), (0.4300, -0.0230), (0.4400, -0.0300), (0.4400, -0.0860), (0.4200, -0.0900),
              (0.3500, -0.0640)]
    m = sh.extrude_x(outer, (window,), STOCK_W, chamfer_bevel(0.0022), "stock")
    pad = [(BUTT_Z - 0.0140, COMB_Y - 0.0010), (BUTT_Z, COMB_Y - 0.0040), (BUTT_Z, -0.1140), (BUTT_Z - 0.0120, -0.1190)]
    m.merge(sh.extrude_x(pad, (), STOCK_W + 0.0008, chamfer_bevel(0.0025), "steel_dark"))
    for k in range(6):                                                  # pad ribs
        y = -0.020 - k * 0.016
        m.merge(sh.box(-STOCK_W - 0.0003, y - 0.0015, BUTT_Z - 0.0010, STOCK_W + 0.0003, y + 0.0015, BUTT_Z + 0.0012,
                       0.0, "steel_dark"))
    # sling swivel cup on the underside of the butt and the bolts into the receiver's rear block
    m.merge(_cyl_z(0.0050, -0.0016, 0.0016, "steel_dark", 12).transformed(_rx_to_y()).translated((0.0, -0.1130, 0.4500)))
    for s in (-1, 1):
        cap = _cyl_z(0.0030, -0.0006, 0.0008, "steel_dark", 10)
        cap = sh.place(cap, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(s, 0, 0))
        m.merge(cap.translated((s * STOCK_W, -0.0140, REC_Z1 + 0.0140)))
    return m


# -------------------------------------------------------------------------------------- shells
def shell_profile(spent=False):
    """12-gauge 2 3/4 in shell along Z, head (rim) at +Z: red hull, low brass head; a spent one has its crimp open."""
    L, r, h, rim = SHELL_LEN, SHELL_R, HEAD_LEN, RIM_R
    z0, z1 = -L / 2, L / 2
    if spent:
        hull = [(0.0088, z0), (r + 0.0003, z0 + 0.0030), (r, z0 + 0.0080)]
    else:
        hull = [(0.0, z0 + 0.0020), (0.0050, z0 + 0.0012), (0.0088, z0 + 0.0004), (r - 0.0004, z0), (r, z0 + 0.0012)]
    hull += [(r, z1 - h - 0.0005)]
    head = [(r + 0.0002, z1 - h), (r + 0.0002, z1 - 0.0018), (rim, z1 - 0.0016), (rim, z1 - 0.0002),
            (rim - 0.0004, z1), (0.0030, z1), (0.0030, z1 - 0.0004), (0.0, z1 - 0.0004)]
    return hull, head


def shell_mesh(spent=False, seg=14):
    hull, head = shell_profile(spent)
    m = Mesh()
    if spent:                                       # the open mouth shows the hull's inside down to the wad
        inner = [(0.0, hull[0][1] + 0.0060), (0.0080, hull[0][1] + 0.0060), (0.0084, hull[0][1])]
        m.merge(sh.revolve(inner + hull, seg, "shell", precision=True))
    else:
        m.merge(sh.revolve(hull, seg, "shell", precision=True))
    m.merge(sh.revolve(head, seg, "brass", precision=True))
    return m
