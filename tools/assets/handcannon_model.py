"""Hand cannon geometry (VIEWMODEL_SPEC section 43): an original procedural single-shot 40 mm grenade pistol (手炮), a
cut-down break-open launcher with a pistol grip, built to the P9 contract (as the Desert Eagle, section 48) so the P9's
hand anchors and the ready / draw / walk clips fit it; its own clips (handcannon_clips) break it open.

Design frame as the P9's (metres): +X right, +Y up, +Z rearward; bore axis on x = y = 0. The names the P9 rig and clips
read (grip_center, AXIS, FRONT, SW, SY1, SZ0, CROWN_Z, SIGHT_Y, REAR_SIGHT_Z, the pivots, MAG_ORIGIN, LASER_ORIGIN) are
defined here. The shapes: a 248 mm aluminium barrel (olive paint) with a counterbored chamber, two grip rings and a
tall front blade at the muzzle, hinged under its breech on a forward knuckle of the frame (it tips muzzle-down to
load); a short steel receiver (the standing breech with the firing pin) under a top rail with the rear sight; the
side latch that frees the barrel; an external hammer; a squared trigger guard; a raked rubber grip. The round: a 40 x
46 mm high-explosive grenade (aluminium case, olive projectile, gilded band and fuze). Every builder returns a
meshkit.Mesh."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, orient_radial, pin, round_bevel

GRIP_ANGLE = 18.0
TAN18 = np.tan(np.radians(GRIP_ANGLE))
AXIS = np.array([0.0, -np.cos(np.radians(GRIP_ANGLE)), np.sin(np.radians(GRIP_ANGLE))])     # grip axis, downwards
FRONT = np.array([0.0, -np.sin(np.radians(GRIP_ANGLE)), -np.cos(np.radians(GRIP_ANGLE))])  # grip "front"

# ---- key dimensions -------------------------------------------------------------------------------
BORE_R = 0.0205                 # 40 mm bore (with clearance)
BARREL_R = 0.0262               # outside of the tube
CHAMBER_R = 0.0219              # the rim's counterbore at the breech
BREECH_Z = -0.0040              # barrel's rear face = the chamber's mouth (the standing breech just behind it)
CROWN_Z = -0.2520               # muzzle face
SZ0 = CROWN_Z                   # P9 contract: the barrel mount sits just in front of this
HINGE = np.array([0.0, -0.0350, -0.0300])        # hinge pin: under the barrel, forward of the breech
OPEN = -44.0                    # barrel rotation about the hinge when broken open (muzzle down)
SW = 0.0275                     # receiver half width (its shoulders cover the barrel's rear face)
REC_Z1 = 0.0680                 # receiver's rear
REC_Y0, REC_Y1 = -0.0400, 0.0280                 # receiver bottom / top (the rail sits on it)
SY1 = 0.0358                    # top of the rail (P9: the slide's top)
SIGHT_Y = 0.0425                # top of the rear notch / front blade: the iron sight line (under a mini dot)
REAR_SIGHT_Z = 0.0640           # rear face of the rear sight
HAMMER_PIVOT = np.array([0.0, 0.0120, 0.0640])
TRIGGER_PIVOT = np.array([0.0, -0.0420, 0.0150])
STOP_PIVOT = np.array([-SW - 0.0006, -0.0060, 0.0120])   # the side latch's pivot (left of the receiver)
LASER_ORIGIN = np.array([0.0, -0.0300, -0.1300])         # (no laser slot: the contract's mount)
MAG_ORIGIN = np.array([0.0, 0.0, BREECH_Z])              # the round's case head when chambered
GRIP_C0 = np.array([0.0, -0.0380, 0.0470])               # grip centre line at the top
GRIP_LEN = 0.0880
OPTIC_Y = SY1 - 0.0004          # optic base on the rail
OPTIC_Z = 0.0300
# the round (mag-local = gun-local while chambered: nose towards -Z, case head at z = 0)
CASE_R, RIM_R, CASE_LEN = 0.0200, 0.0214, 0.0420
ROUND_LEN = 0.1030


def grip_center(d):
    return GRIP_C0 + d * AXIS


def _loft_z(pairs, material, **kw):
    return sh.loft([np.stack([*np.asarray(s).T, np.full(len(s), z)], 1) for s, z in pairs], material, **kw)


# ------------------------------------------------------------------------------------ barrel
def barrel_mesh():
    """The tube (open bore, counterbored chamber, two raised grip rings), the sight rib and front blade, the hinge lug
    under the breech with the pin's bore. Design frame (gen moves it to the hinge)."""
    R, r, bz, cz = BARREL_R, BORE_R, BREECH_Z, CROWN_Z
    ring = lambda z0, z1, h: [(R, z0), (R + h * 0.5, z0 - 0.0008), (R + h, z0 - 0.0016), (R + h, z1 + 0.0016),
                              (R + h * 0.5, z1 + 0.0008), (R, z1)]
    prof = [(CHAMBER_R, bz), (R - 0.0012, bz), (R, bz - 0.0012)]
    prof += ring(-0.0360, -0.0480, 0.0016)
    prof += ring(-0.2150, -0.2270, 0.0016)
    prof += [(R, cz + 0.0020), (R - 0.0020, cz), (r + 0.0015, cz), (r, cz + 0.0015), (r, bz - 0.0040),
             (CHAMBER_R, bz - 0.0030), (CHAMBER_R, bz)]
    m = sh.revolve(prof, 36, "barrel")
    # the sight rib along the top, and the tall front blade at the muzzle with its dot towards the eye
    rib = np.array([(-0.0034, R - 0.0020), (0.0034, R - 0.0020), (0.0034, R + 0.0022), (0.0024, R + 0.0032),
                    (-0.0024, R + 0.0032), (-0.0034, R + 0.0022)])
    m.merge(_loft_z([(sh.offset_loop(rib, 0.0006), cz + 0.0060), (rib, cz + 0.0068), (rib, bz - 0.0120),
                     (sh.offset_loop(rib, 0.0006), bz - 0.0112)], "steel_dark"))
    fz0, fz1 = cz + 0.0040, cz + 0.0200
    blade = [(fz0, R + 0.0026), (fz1, R + 0.0026), (fz1, SIGHT_Y), (fz1 - 0.0034, SIGHT_Y), (fz0 + 0.0060, R + 0.0080)]
    m.merge(sh.extrude_x(blade, (), 0.0017, chamfer_bevel(0.0003), "steel_dark"))
    for sx in (-1, 1):                                   # protective ears either side of the blade
        ear = [(fz0 + 0.0020, R + 0.0020), (fz1 + 0.0010, R + 0.0020), (fz1 + 0.0010, SIGHT_Y - 0.0030),
               (fz1 - 0.0040, SIGHT_Y - 0.0010), (fz0 + 0.0060, R + 0.0060)]
        m.merge(sh.extrude_x(ear, (), 0.0011, chamfer_bevel(0.0003), "steel_dark", x_center=sx * 0.0072))
    m.merge(sh.disc((0, SIGHT_Y - 0.0022, fz1 + 0.00006), (0, 0, 1), 0.0011, 12, "sight_dots"))
    # the hinge lug: two cheeks either side of the frame's knuckle, from the tube down round the pin
    hz, hy = HINGE[2], HINGE[1]
    lug = [(hz - 0.0130, -R + 0.0030), (bz - 0.0020, -R + 0.0030), (bz - 0.0020, hy + 0.0020),
           (hz + 0.0110, hy - 0.0080), (hz - 0.0010, hy - 0.0115), (hz - 0.0110, hy - 0.0080), (hz - 0.0140, hy + 0.0040)]
    hole = sh.circle(hz, hy, 0.0032, 10)[::-1]
    for sx in (-1, 1):
        m.merge(sh.extrude_x(lug, [hole], 0.0034, round_bevel(0.0010, 1), "barrel", x_center=sx * 0.0128))
    # the extractor's tab at the breech face, bottom of the chamber
    m.merge(sh.box(-0.0060, -CHAMBER_R - 0.0010, bz - 0.0040, 0.0060, -CHAMBER_R + 0.0026, bz + 0.0004, 0.0004,
                   "steel_dark"))
    return m


# ------------------------------------------------------------------------------------- frame
# side outline (z, y) of the receiver: the standing breech at the front, the hammer's slot at the top rear, the
# frame's knuckle reaching forward under the barrel to the hinge
RECEIVER = [(BREECH_Z + 0.0004, REC_Y1 - 0.0010), (BREECH_Z + 0.0014, REC_Y1), (REC_Z1 - 0.0060, REC_Y1),
            (REC_Z1, REC_Y1 - 0.0080), (REC_Z1 + 0.0030, -0.0080), (REC_Z1 - 0.0010, -0.0240), (0.0560, REC_Y0),
            (0.0060, REC_Y0), (BREECH_Z + 0.0004, REC_Y0)]
# the frame's knuckle: forward from the receiver's foot, under the barrel, round the hinge pin (between the barrel's
# two hinge cheeks)
KNUCKLE = [(0.0080, REC_Y0), (-0.0060, REC_Y0 - 0.0040), (HINGE[2] - 0.0060, HINGE[1] - 0.0080),
           (HINGE[2] - 0.0100, HINGE[1]), (HINGE[2] - 0.0030, HINGE[1] + 0.0060), (BREECH_Z - 0.0040, -0.0285),
           (0.0080, -0.0285)]
KNUCKLE_W = 0.0088
GUARD = [(-0.0060, REC_Y0 - 0.0030), (-0.0080, -0.0560), (-0.0090, -0.0640), (-0.0030, -0.0690),
         (0.0420, -0.0690), (0.0450, -0.0640), (0.0450, -0.0560), (0.0400, -0.0560), (0.0400, -0.0630),
         (0.0000, -0.0630), (-0.0030, -0.0600), (-0.0030, REC_Y0 - 0.0030)]
GRIP_WIDTH = [(-0.0060, 0.0300), (0.0, 0.0310), (0.0080, 0.0330), (0.0200, 0.0346), (0.0450, 0.0350),
              (0.0700, 0.0348), (0.0820, 0.0340), (GRIP_LEN, 0.0320)]


def _grip_ring(d, half_w, front, back, count=22):
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    n = np.where(s > 0, 2.4, 3.0)
    x = half_w * np.sign(c) * np.abs(c) ** (2 / n)
    sv = np.where(s > 0, front, back) * np.sign(s) * np.abs(s) ** (2 / n)
    centre = grip_center(d)
    return centre[None, :] + x[:, None] * np.array([1.0, 0, 0]) + sv[:, None] * FRONT[None, :]


def grip_mesh():
    """The raked grip: rubber with three finger grooves on its front, a flat steel heel cap."""
    rings = []
    for d, w in GRIP_WIDTH:
        u = np.clip((d - 0.012) / 0.062, 0, 1)
        groove = 0.0016 * (0.5 - 0.5 * np.cos(2 * np.pi * 3 * u)) * (0.012 < d < 0.074)
        rings.append(_grip_ring(d, 0.5 * w, 0.0158 - groove, 0.0175))
    m = sh.loft(rings, "grip", cap_start=True, cap_end=True, auto_orient=False)
    orient_radial(m, GRIP_C0, AXIS, +1)
    caps = [len(m.f) - 2, len(m.f) - 1]
    pv = np.array(m.v)
    for i in caps:
        f = m.f[i]
        c = pv[list(f)].mean(0)
        nrm = np.cross(pv[f[1]] - pv[f[0]], pv[f[2]] - pv[f[0]])
        want = AXIS if np.dot(c - GRIP_C0, AXIS) > 0.04 else -AXIS
        if np.dot(nrm, want) < 0:
            m.f[i] = tuple(reversed(f))
    heel = _grip_ring(GRIP_LEN, 0.5 * GRIP_WIDTH[-1][1] - 0.0004, 0.0150, 0.0167)
    cap = sh.loft([heel, heel + 0.0030 * AXIS[None, :]], "polymer", auto_orient=True)
    return m.merge(cap)


def frame_mesh():
    m = sh.extrude_x(RECEIVER, (), SW, round_bevel(0.0024, 2), "polymer")
    m.merge(sh.extrude_x(KNUCKLE, (), KNUCKLE_W, round_bevel(0.0014, 1), "polymer"))
    m.merge(sh.extrude_x(GUARD, (), 0.0060, round_bevel(0.0022, 1), "polymer"))
    m.merge(grip_mesh())
    # the standing breech: a recessed face plate with the firing-pin bush in line with the bore
    m.merge(sh.box(-0.0230, -0.0230, BREECH_Z - 0.0006, 0.0230, 0.0230, BREECH_Z + 0.0010, 0.0005, "steel_dark"))
    m.merge(pin((0, 0, BREECH_Z - 0.0004), 0.0032, 0.0012, "steel_dark", axis="z", seg=12))
    # the hinge pin through the knuckle and the barrel's cheeks
    hp = sh.revolve([(0.0, -0.0175), (0.0034, -0.0175), (0.0034, 0.0175), (0.0, 0.0175)], 12, "steel_dark")
    m.merge(sh.place(hp, origin=HINGE, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0)))
    # the latch's housing on the left and a cross pin behind the trigger
    m.merge(sh.box(-SW - 0.0012, -0.0120, 0.0040, -SW + 0.0004, 0.0000, 0.0220, 0.0005, "steel_dark"))
    m.merge(pin((0, -0.0300, 0.0500), 0.0014, SW + 0.0002, "steel_dark", seg=8))
    return m


# ---------------------------------------------------------------------------- moving parts
def trigger_mesh():
    """Curved trigger blade hinged at TRIGGER_PIVOT."""
    pz, py = TRIGGER_PIVOT[2], TRIGGER_PIVOT[1]
    blade = [(pz - 0.0020, py + 0.0010), (pz + 0.0030, py + 0.0010), (pz + 0.0040, py - 0.0060),
             (pz + 0.0070, py - 0.0130), (pz + 0.0110, py - 0.0180), (pz + 0.0128, py - 0.0168),
             (pz + 0.0090, py - 0.0120), (pz + 0.0068, py - 0.0060), (pz + 0.0050, py + 0.0010)]
    blade = [(z - 0.0035, y) for z, y in blade]
    return sh.extrude_x(blade, (), 0.0036, round_bevel(0.0007, 1), "steel_dark")


def hammer_mesh():
    """External spur hammer, upright at rotation 0 (cocked the P9 way), pivot at HAMMER_PIVOT."""
    pz, py = HAMMER_PIVOT[2], HAMMER_PIVOT[1]
    prof = [(pz - 0.0050, py - 0.0040), (pz + 0.0050, py - 0.0040), (pz + 0.0080, py + 0.0120),
            (pz + 0.0160, py + 0.0190), (pz + 0.0170, py + 0.0240), (pz + 0.0100, py + 0.0250),
            (pz + 0.0020, py + 0.0200), (pz - 0.0040, py + 0.0120)]
    m = sh.extrude_x(prof, (), 0.0036, round_bevel(0.0012, 1), "steel_dark")
    for k in range(4):                                   # checkered spur
        z, y = pz + 0.0105 + 0.0018 * k, py + 0.0222 + 0.0008 * k
        m.merge(sh.box(-0.0030, y, z - 0.0004, 0.0030, y + 0.0008, z + 0.0004, 0.0002, "steel_dark"))
    return m


def slide_stop_mesh():
    """The side latch on the left of the receiver: an arm reaching back to a serrated thumb tab (pressed down, it
    frees the barrel); pivot at STOP_PIVOT."""
    sx, sy, sz = STOP_PIVOT
    prof = [(sz - 0.0050, sy - 0.0030), (sz + 0.0040, sy - 0.0040), (sz + 0.0260, sy - 0.0060),
            (sz + 0.0330, sy - 0.0040), (sz + 0.0340, sy + 0.0030), (sz + 0.0270, sy + 0.0040),
            (sz + 0.0050, sy + 0.0040), (sz - 0.0050, sy + 0.0030)]
    m = sh.extrude_x(prof, (), 0.0012, chamfer_bevel(0.0004), "steel_dark", x_center=sx - 0.0006)
    for k in range(3):
        z = sz + 0.0270 + 0.0022 * k
        m.merge(sh.box(sx - 0.0024, sy - 0.0040, z - 0.0004, sx - 0.0014, sy + 0.0036, z + 0.0004, 0.0002, "steel_dark"))
    m.merge(pin((sx - 0.0010, sy, sz), 0.0026, 0.0014, "steel_dark", seg=10))
    return m


# ------------------------------------------------------------------------------------ the rail and the sights
def rail_mesh():
    """The receiver's top rail (P9 contract: the `slide`, which never moves here) with cross slots, and its rear plate
    under the hammer."""
    z0, z1 = BREECH_Z + 0.0030, REC_Z1 - 0.0080
    rail = np.array([(-0.0105, REC_Y1 - 0.0010), (0.0105, REC_Y1 - 0.0010), (0.0105, SY1 - 0.0032), (0.0080, SY1 - 0.0020),
                     (0.0080, SY1 - 0.0008), (0.0072, SY1), (-0.0072, SY1), (-0.0080, SY1 - 0.0008),
                     (-0.0080, SY1 - 0.0020), (-0.0105, SY1 - 0.0032)])
    slot = rail.copy()
    slot[:, 1] = np.minimum(slot[:, 1], SY1 - 0.0024)
    pairs = [(sh.offset_loop(rail, 0.0006), z0), (rail, z0 + 0.0008)]
    z = z0 + 0.0060
    while z < z1 - 0.0080:
        pairs += [(rail, z - 0.0020), (slot, z - 0.0019), (slot, z + 0.0019), (rail, z + 0.0020)]
        z += 0.0100
    pairs += [(rail, z1 - 0.0008), (sh.offset_loop(rail, 0.0006), z1)]
    return _loft_z(pairs, "slide")


def rear_sight_mesh():
    """Square-notch rear sight on the rail's rear, two dots (slide space = design frame)."""
    base = SY1 - 0.0010
    rear = np.array([(-0.0090, base), (0.0090, base), (0.0090, SIGHT_Y - 0.0016), (0.0078, SIGHT_Y),
                     (0.0022, SIGHT_Y), (0.0022, SIGHT_Y - 0.0050), (-0.0022, SIGHT_Y - 0.0050), (-0.0022, SIGHT_Y),
                     (-0.0078, SIGHT_Y), (-0.0090, SIGHT_Y - 0.0016)])
    low = rear.copy()
    low[:, 1] = np.minimum(low[:, 1], SIGHT_Y - 0.0050)
    m = _loft_z([(low, REAR_SIGHT_Z - 0.0120), (rear, REAR_SIGHT_Z - 0.0090), (rear, REAR_SIGHT_Z - 0.0005),
                 (sh.offset_loop(rear, 0.0005), REAR_SIGHT_Z)], "steel_dark")
    for x in (-0.0052, 0.0052):
        m.merge(sh.disc((x, SIGHT_Y - 0.0024, REAR_SIGHT_Z + 0.00006), (0, 0, 1), 0.0011, 12, "sight_dots"))
    return m


# ----------------------------------------------------------------------------------- the round
def round_mesh(seg=24):
    """The 40 x 46 mm grenade along -Z, case head at z = 0: an aluminium case with its rim and the primer, the
    projectile's gilded driving band, the olive ogive and the gilded point-detonating fuze."""
    case = [(0.0, 0.0), (0.0040, 0.0), (0.0040, -0.0004), (RIM_R - 0.0004, -0.0004), (RIM_R, -0.0008),
            (RIM_R, -0.0026), (CASE_R - 0.0004, -0.0030), (CASE_R, -0.0040), (CASE_R, -CASE_LEN),
            (CASE_R - 0.0010, -CASE_LEN), (0.0, -CASE_LEN)]
    m = sh.revolve(case, seg, "mag")
    m.merge(sh.revolve([(0.0, 0.0002), (0.0040, 0.0002), (0.0040, -0.0006), (0.0, -0.0006)], max(8, seg // 2), "brass"))
    z = -CASE_LEN
    band = [(0.0, z + 0.0004), (0.0194, z + 0.0004), (0.0203, z - 0.0004), (0.0203, z - 0.0050),
            (0.0196, z - 0.0056), (0.0, z - 0.0056)]
    m.merge(sh.revolve(band, seg, "brass"))
    ogive = [(0.0, z - 0.0050)]
    for a in np.linspace(0, 1, 9):
        rr = 0.0197 * np.sqrt(max(0.0, 1 - a ** 1.7)) * (1 - 0.25 * a) + 0.0060 * a
        ogive.append((rr, z - 0.0056 - a * 0.0480))
    ogive.append((0.0, z - 0.0536))
    m.merge(sh.revolve(ogive, seg, "mag_base"))
    fz = z - 0.0536
    fuze = [(0.0, fz + 0.0010), (0.0062, fz + 0.0010), (0.0058, fz - 0.0030), (0.0040, fz - 0.0062), (0.0018, fz - 0.0072),
            (0.0, ROUND_LEN * -1.0)]
    m.merge(sh.revolve(fuze, max(8, seg * 2 // 3), "brass"))
    return m


def shell_mesh():
    """The spent case thrown out when the barrel is broken open (the runtime's shell, section 4): the round's case."""
    case = [(0.0, 0.0), (RIM_R - 0.0004, 0.0), (RIM_R, -0.0004), (RIM_R, -0.0022), (CASE_R, -0.0030),
            (CASE_R, -CASE_LEN), (CASE_R - 0.0016, -CASE_LEN), (CASE_R - 0.0016, -0.0050), (0.0, -0.0050)]
    return sh.revolve(case, 20, "mag")
