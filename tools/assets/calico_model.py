"""Calico M950 pistol geometry, VIEWMODEL_SPEC section 40. Design frame (metres): +X right, +Y up, +Z rearward; bore
axis on x = y = 0, z = 0 just in front of the magazine's front face. Real-world scale, measured off the reference photo
(tools/assets/source/references/calico_m950.jpg, left side view, 0.346 mm per pixel: the pistol is 0.355 m from the
thread protector to the back of the grip).

An original procedural model: a black-anodised aluminium receiver whose upper walls cradle the magazine, the black
polymer lower (a solid trigger guard web with a round finger hole, the selector above its front, the raked pistol grip
with its slanted ribs and the slot along the front of the rib panel), the ribbed polymer forend with the vent holes
along its bottom and short accessory rails on top (laser) and underneath (front grips), the barrel standing out of the
forend with the knurled thread protector on its muzzle, the cocking handle riding a slotted plate on the left of the
receiver, and the long cylindrical helical magazine lying on top: it hooks into the front sight tower (front latch
under its nose) and runs back through the rear sight bridge — a steel hoop over the rear of the receiver carrying the
rear sight and a short optic rail — far past the shooter's hand, its winding key on the rear cap. The 50-round
magazine ends 2 cm behind the bridge, the 100-round one 13 cm further back.

Every builder returns a meshkit.Mesh in the design frame; gen_calico moves meshes into node-local space."""
import numpy as np

import shapes as sh
import smg_detail as sd
from meshkit import Mesh
from model_quality import arc_segments, edge_radius, is_sharp
from p9_model import chamfer_bevel, round_bevel
from vmath import norm

# ---- key dimensions ---------------------------------------------------------------------------
# barrel and muzzle
BARREL_R = 0.0068
THREAD_R = 0.0059
CROWN_Z = -0.1880                # the barrel's crown (end of the 1/2-28 thread)
SHOULDER_Z = -0.1735             # thread shoulder: the protector / a suppressor seats here
MUZZLE_Z = -0.1905               # front face of the thread protector
PROT_R = 0.0074
BORE_R = 0.0046
# forend
FORE_Z0, FORE_Z1 = -0.1530, -0.0240
FORE_TOP = 0.0183
FORE_W = 0.0165
FORE_BEVEL = 0.0050
# receiver
REC_Z0, REC_Z1 = -0.0235, 0.1655  # front block / rear end
REC_W = 0.0160                   # lower receiver half width (round the bolt)
REC_BOTTOM = -0.0145
CRADLE_W = 0.0318                # upper walls round the magazine
CRADLE_TOP = 0.0298
# magazine (lies along +Z on top: axis at MAG_Y)
MAG_Y = 0.0330
MAG_R = 0.0285
MAG_FLAT = 0.0262                # flat bottom of the body (it slides on the cradle)
MAG_Z0 = 0.0030                  # front face
MAG_BODY = {False: 0.1680, True: 0.2910}      # rear end of the body: 50 / 100 rounds
CAP_LEN = 0.0135
CAP_R = 0.0264
KEY_Y = 0.0180                   # winding key above the magazine's axis, on the rear cap
MAG_ORIGIN = np.array([0.0, MAG_Y, MAG_Z0])    # `mag` pivot: centre of the magazine's front face
# tower (front sight / magazine stop) and the rear sight bridge
TOWER_W = 0.0080
TOWER_TOP = 0.0675
FRONT_POST_Z = -0.0115
BRIDGE_Z0, BRIDGE_Z1 = 0.1400, 0.1630
HOOP_R = 0.0294                  # inside the bridge (clear of the magazine)
BRIDGE_TOP = 0.0650
RAIL_Z0, RAIL_Z1 = 0.1150, 0.1850
RAIL_TOP = 0.0705
RAIL_W = 0.0106
OPTIC_Z = 0.1500
SIGHT_Y = 0.0780                 # sight line: top of the front post / the rear notch's shoulders
REAR_NOTCH_Z = 0.1800
# bolt and cocking handle (left side, in the slot of the cover plate)
HANDLE_Z0, HANDLE_Z1 = 0.1000, 0.1260
HANDLE_Y0, HANDLE_Y1 = 0.0210, 0.0258
SLOT_Z0, SLOT_Z1 = 0.0975, 0.1610
BOLT_TRAVEL = 0.0320
PLATE_X = CRADLE_W + 0.0012      # outer face of the cover plate (left side: -PLATE_X)
EJECTION = np.array([REC_W + 0.0006, -0.0052, 0.0400])
# lower: finger hole, trigger, selector
HOLE_C = np.array([0.0561, -0.0353])   # (z, y)
HOLE_R = 0.0131
LOWER_W = 0.0128                 # narrower than the grip's front strap: the web runs into the grip inside it
TRIGGER_PIVOT = np.array([0.0, -0.0196, 0.0505])
SELECTOR_PIVOT = np.array([-LOWER_W, -0.0262, 0.0360])
# grip: horizontal sections (y, front z, back z, half width)
GRIP_SECTIONS = [(0.0045, 0.0965, 0.1360, 0.0148), (-0.0300, 0.0880, 0.1440, 0.0154),
                 (-0.0520, 0.0905, 0.1527, 0.0158), (-0.0700, 0.1025, 0.1572, 0.0157),
                 (-0.0880, 0.1130, 0.1622, 0.0151), (-0.0955, 0.1150, 0.1645, 0.0157),
                 (-0.0985, 0.1158, 0.1650, 0.0153)]
GRIP_TOP = np.array([0.0, -0.0480, 0.1205])
GRIP_AXIS = norm((0.0, -0.904, 0.428))         # down the grip (raked back 25 degrees)
GRIP_FRONT = norm((0.0, -0.428, -0.904))       # towards the front strap
# rails for the laser (top of the forend) and the front grips (under it)
TOP_RAIL = (-0.1480, -0.1100)
LOW_RAIL = (-0.1520, -0.1120)
UNDER_Y = -0.0258                # bottom of the lower rail's teeth
SWIVEL = np.array([-0.0258, -0.0118, -0.1440])


def grip_center(d):
    return GRIP_TOP + d * GRIP_AXIS


def fore_bottom(z):
    """Bottom line of the forend (it deepens towards the receiver)."""
    return -0.0180 - 0.110 * (z + 0.150)


# -------------------------------------------------------------------------------------- helpers
def fillet(poly, radii, minimum=2):
    """Round the corners of a 2D polygon (radius per corner, 0 = sharp) with arcs within the curve error."""
    p = np.asarray(poly, float)
    n = len(p)
    radii = [radii] * n if np.isscalar(radii) else list(radii)
    out = []
    for i in range(n):
        a, b, c = p[i - 1], p[i], p[(i + 1) % n]
        r = radii[i]
        if r <= 0:
            out.append(b)
            continue
        u, v = norm(np.append(a - b, 0))[:2], norm(np.append(c - b, 0))[:2]
        cosang = float(np.clip(np.dot(u, v), -1, 1))
        half = np.arccos(cosang) / 2
        if half < 1e-3 or abs(half - np.pi / 2) < 1e-4:
            out.append(b)
            continue
        d = min(r / np.tan(half), 0.45 * np.linalg.norm(a - b), 0.45 * np.linalg.norm(c - b))
        r = d * np.tan(half)
        p0, p1 = b + u * d, b + v * d
        centre = b + norm(np.append(u + v, 0))[:2] * (r / np.sin(half))
        a0 = np.arctan2(*(p0 - centre)[::-1])
        a1 = np.arctan2(*(p1 - centre)[::-1])
        da = (a1 - a0 + np.pi) % (2 * np.pi) - np.pi
        k = arc_segments(r, minimum, abs(da))
        for t in np.linspace(0.0, 1.0, k + 1):
            out.append(centre + r * np.array([np.cos(a0 + da * t), np.sin(a0 + da * t)]))
    return np.array(out)


def extrude_x2(outer, holes=(), half_width=0.01, bevel=((0.0, 0.0),), material="default", x_center=0.0,
               hole_bevel=((0.0, 0.0),)):
    """shapes.extrude_x with its own bevel for the holes (default: straight tunnels): a rounded outline does not pay
    for rounding every vent and slot as well. Rounded like shapes.extrude_x (VIEWMODEL_SPEC section 53): no bevel
    becomes a small quarter round, a plain chamfer a quarter round of its size, the outline's corners (and the holes')
    are filleted, and the faces of every round are soft shaded."""
    outer = sh.ccw(outer)
    holes = [sh.cw(h) for h in holes]
    chords = [np.zeros(len(outer), bool)] + [np.zeros(len(h), bool) for h in holes]
    if sh._rounding[0] and not is_sharp(material):
        bevel, r = sh._round_profile([outer], bevel, 2 * half_width)
        hole_bevel = sh._as_round(hole_bevel)
        outer, c0 = sh.fillet_loop(outer, r, arcs=True)
        rounded = [sh.fillet_loop(h, min(r, edge_radius(float(np.min(np.ptp(h, axis=0))))), arcs=True) for h in holes]
        holes = [h for h, _ in rounded]
        chords = [c0] + [c for _, c in rounded]

    def rings(bv):
        rs = sorted(bv, key=lambda r: -r[0])
        xs, ins = [], []
        for dx, i in rs[::-1]:
            xs.append(-(half_width - dx))
            ins.append(i)
        for dx, i in rs:
            xs.append(half_width - dx)
            ins.append(i)
        return np.array(xs) + x_center, ins

    mesh = Mesh()
    faces = []
    for k, loop in enumerate([outer] + list(holes)):
        xs, insets = rings(bevel if k == 0 else hole_bevel)
        chord = chords[k]
        secs = []
        for x, ins in zip(xs, insets):
            o = sh.offset_loop(loop, ins) if ins else loop
            secs.append(np.stack([np.full(len(o), x), o[:, 1], o[:, 0]], 1))
        sm = np.asarray(secs)
        K, N = sm.shape[:2]
        base = mesh.add_vertices(sm.reshape(-1, 3))
        for j in range(K - 1):
            for i in range(N):
                a, b = base + j * N + i, base + j * N + (i + 1) % N
                c, d = base + (j + 1) * N + (i + 1) % N, base + (j + 1) * N + i
                mesh.add_face([a, d, c, b], material, soft=insets[j] > 0 or insets[j + 1] > 0 or bool(chord[i]))
        faces.append((secs[0], secs[-1]))
    from meshkit import triangulate
    for end, flip in ((0, False), (1, True)):
        loops = [f[end] for f in faces]
        o2 = [np.stack([s[:, 2], s[:, 1]], 1) for s in loops]
        base = mesh.add_vertices(np.concatenate(loops))
        for t in triangulate(o2[0], o2[1:]):
            mesh.add_face([base + i for i in (t[::-1] if flip else t)], material)
    return sh.orient_outward(mesh)


def _turn_z(m):
    """Mesh built along X (extrude_x2: profile in (z, y)) -> along Z (profile in (x, y))."""
    rot = np.eye(4)
    rot[:3, :3] = np.array([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], float)
    return m.transformed(rot)


def extrude_z2(loop_xy, holes=(), z0=0.0, z1=0.01, bevel=((0.0, 0.0),), material="default",
               hole_bevel=((0.0, 0.0),)):
    hw = 0.5 * (z1 - z0)
    return _turn_z(extrude_x2(loop_xy, holes, hw, bevel, material, x_center=-(z0 + hw), hole_bevel=hole_bevel))


def smooth_bevel(r, steps=3):
    """Quarter-round bevel in `steps` facets (30 degrees each for 3: shaded smooth, cheaper than round_bevel)."""
    th = np.linspace(0, np.pi / 2, steps + 1)
    return [(r - r * np.sin(t), r - r * np.cos(t)) for t in th]


def cyl_z(r, z0, z1, material, x=0.0, y=0.0, chamfer=0.0, minimum=12):
    c = min(chamfer, 0.45 * abs(z1 - z0))
    prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)] if c > 0 else \
        [(0.0, z0), (r, z0), (r, z1), (0.0, z1)]
    return sh.revolve(prof, minimum, material, precision=True).translated((x, y, 0.0))


_X_NEG = np.array([[0, 0, -1, 0], [1, 0, 0, 0], [0, -1, 0, 0], [0, 0, 0, 1]], float)   # lathe axis Z -> -X


def cyl_x(r, x0, x1, y, z, material, chamfer=0.0, minimum=12):
    x0, x1 = min(x0, x1), max(x0, x1)
    m = cyl_z(r, x0, x1, material, chamfer=chamfer, minimum=minimum)
    return sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0)).translated((0.0, y, z))


def screw(x, y, z, side, r=0.0021, material="steel_dark"):
    """Low domed screw head on a side face (side = +1 right / -1 left), with a hex socket ring."""
    prof = [(0.0, 0.0010), (0.62 * r, 0.0010), (r, 0.0002), (r * 0.9, -0.0006)]
    m = sh.revolve(prof + [(0.0, -0.0006)], 12, material, precision=True)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(side, 0, 0))
    return m.translated((x, y, z))


def bar(a, b, width, height, up, material, taper=0.6):
    """A rib from a to b: trapezoid section (`width` at its foot, taper x width on top) standing `height` along `up`."""
    a, b, up = (np.asarray(v, float) for v in (a, b, up))
    d = norm(b - a)
    side = norm(np.cross(d, up))
    w0, w1 = width / 2, width * taper / 2
    sec = [(-w0, 0.0), (w0, 0.0), (w1, height), (-w1, height)]
    ring = lambda p: np.array([p + s * side + h * up for s, h in sec])
    return sh.loft([ring(a), ring(b)], material)


def rail_profile(z0, z1, y_foot, y_top, teeth, tooth=0.0053, depth=0.0026, down=False, foot_slope=None):
    """Side outline (z, y) of a short picatinny rail: base from its foot to the slot floor, cross bars every 10 mm
    (`teeth` of them, centred). down=True hangs the teeth below the base (a rail under the forend); foot_slope (z ->
    y) gives the foot edge (embedded in a sloped surface)."""
    s = -1.0 if down else 1.0
    floor = y_top - s * depth
    mid = 0.5 * (z0 + z1)
    centres = mid + (np.arange(teeth) - (teeth - 1) / 2) * 0.0100
    top = [(z0, floor)]
    for c in centres:
        top += [(c - tooth / 2 - 0.0006, floor), (c - tooth / 2, y_top), (c + tooth / 2, y_top),
                (c + tooth / 2 + 0.0006, floor)]
    top.append((z1, floor))
    foot = [(z1, foot_slope(z1) if foot_slope else y_foot), (z0, foot_slope(z0) if foot_slope else y_foot)]
    return np.array(top + foot)


def picatinny(z0, z1, y_foot, y_top, half_w, teeth, material="steel_dark", down=False, foot_slope=None):
    prof = rail_profile(z0, z1, y_foot, y_top, teeth, down=down, foot_slope=foot_slope)
    return extrude_x2(prof, (), half_w, chamfer_bevel(0.0010), material)


# -------------------------------------------------------------------------------------- receiver
def _cradle_loop():
    """Section (x, y) of the upper receiver: two walls hugging the magazine's lower half, tapering into the lower
    receiver; the inside follows the magazine round its bottom."""
    w, top = CRADLE_W, CRADLE_TOP
    outer = [(-w, top), (-w, 0.0120), (-0.0200, 0.0005), (0.0200, 0.0005), (w, 0.0120), (w, top)]
    outer = fillet(outer, [0.0, 0.0030, 0.0020, 0.0020, 0.0030, 0.0])
    r = MAG_R + 0.0008
    a0 = np.arcsin((top - MAG_Y) / r)
    n = 16                                                    # the magazine covers it: only seen while it is off
    ang = np.linspace(a0, -np.pi - a0, n + 1)                 # right wall top, under the magazine, left wall top
    inner = np.stack([r * np.cos(ang), MAG_Y + r * np.sin(ang)], 1)
    return np.concatenate([outer, inner])


def _bridge_loop():
    """Section (x, y) of the rear sight bridge: a flat-topped hoop from wall to wall over the magazine."""
    w, top = CRADLE_W, BRIDGE_TOP
    outer = fillet([(-w, CRADLE_TOP - 0.0004), (-w, top - 0.004), (-0.0200, top), (0.0200, top), (w, top - 0.004),
                    (w, CRADLE_TOP - 0.0004)], [0.0, 0.0060, 0.0080, 0.0080, 0.0060, 0.0])
    r = HOOP_R
    a0 = np.arcsin((CRADLE_TOP - 0.0004 - MAG_Y) / r)
    n = 16
    ang = np.linspace(np.pi - a0, a0, n + 1)
    inner = np.stack([r * np.cos(ang), MAG_Y + r * np.sin(ang)], 1)
    return np.concatenate([outer, inner[::-1]])


def receiver_mesh():
    """Black-anodised aluminium receiver: the lower receiver (bolt way, ejection port on the right, the front block
    under the tower), the cradle walls, the cover plate with the cocking handle's slot on the left, the front sight
    tower with its adjustment drum, the rear sight bridge and its optic rail, screws and the takedown lever."""
    prof = [(REC_Z0, 0.0170), (REC_Z0, REC_BOTTOM), (0.0420, REC_BOTTOM), (0.1330, 0.0030), (REC_Z1, 0.0030),
            (REC_Z1, 0.0050), (0.0010, 0.0050), (0.0010, FORE_TOP), (REC_Z0, FORE_TOP)]
    prof = fillet(prof, [0.0010, 0.0015, 0.0060, 0.0040, 0.0010, 0.0, 0.0, 0.0010, 0.0010])
    m = extrude_x2(prof, (), REC_W, chamfer_bevel(0.0012), "steel")
    m.merge(extrude_z2(_cradle_loop(), (), 0.0006, REC_Z1, chamfer_bevel(0.0008), "steel"))
    # cover plate on the left wall: the cocking handle runs in its slot
    plate = [(0.0840, 0.0168), (0.1636, 0.0168), (0.1636, 0.0294), (0.0840, 0.0294)]
    slot = [(SLOT_Z0, HANDLE_Y0 - 0.0006), (SLOT_Z1, HANDLE_Y0 - 0.0006), (SLOT_Z1, HANDLE_Y1 + 0.0006),
            (SLOT_Z0, HANDLE_Y1 + 0.0006)]
    m.merge(extrude_x2(plate, [slot], 0.0008, chamfer_bevel(0.0005), "steel", x_center=-(CRADLE_W + 0.0004)))
    for z, y in ((0.0905, 0.0236), (0.1585, 0.0236)):
        m.merge(screw(-PLATE_X, y, z, -1, r=0.0019))
    # ejection port (right): a dark window on the lower receiver's side with a raised deflector lip behind it
    port = Mesh()
    x = REC_W + 0.00035
    port.add_poly([(x, -0.0102, 0.0560), (x, -0.0102, 0.0240), (x, -0.0004, 0.0240), (x, -0.0004, 0.0560)],
                  "steel_dark")
    m.merge(port)
    m.merge(sh.box(REC_W - 0.0004, -0.0110, 0.0560, REC_W + 0.0012, 0.0004, 0.0600, 0.0004, "steel"))
    # front sight tower: a post in front of the magazine with a cap overhanging forward (the front sight sits on it)
    tower = [(-0.0165, 0.0175), (-0.0002, 0.0175), (-0.0002, 0.0645), (-0.0020, TOWER_TOP), (-0.0205, TOWER_TOP),
             (-0.0215, 0.0655), (-0.0215, 0.0600), (-0.0190, 0.0582), (-0.0092, 0.0582), (-0.0092, 0.0230),
             (-0.0112, 0.0212), (-0.0165, 0.0212)]
    tower = fillet(tower, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0030, 0.0, 0.0])
    m.merge(extrude_x2(tower, (), TOWER_W, chamfer_bevel(0.0010), "steel"))
    # the drum on its left (front sight adjustment)
    m.merge(cyl_x(0.0070, -TOWER_W - 0.0050, -TOWER_W + 0.0004, 0.0540, -0.0118, "steel_dark", chamfer=0.0012))
    # rear sight bridge over the magazine and its rail
    m.merge(extrude_z2(_bridge_loop(), (), BRIDGE_Z0, BRIDGE_Z1, chamfer_bevel(0.0010), "steel"))
    m.merge(picatinny(RAIL_Z0, RAIL_Z1, BRIDGE_TOP - 0.0008, RAIL_TOP, RAIL_W, 7))
    m.merge(screw(-CRADLE_W, 0.0225, 0.1515, -1, r=0.0022))                         # bridge screw
    # screw and the takedown lever at the front
    m.merge(screw(-REC_W, 0.0095, -0.0130, -1))
    lever = [(-0.0350, -0.0100), (-0.0160, -0.0100), (-0.0150, -0.0130), (-0.0160, -0.0168), (-0.0350, -0.0168)]
    m.merge(extrude_x2(lever, (), 0.0009, chamfer_bevel(0.0004), "steel_dark", x_center=-(FORE_W + 0.0006)))
    return m


def bridge_info():
    return {"top": BRIDGE_TOP, "rail": (RAIL_Z0, RAIL_Z1, RAIL_TOP)}


# ------------------------------------------------------------------------------------- forend
def forend_mesh():
    """Ribbed polymer forend: rounded box deepening towards the receiver, vertical ribs on both flanks, a row of vent
    holes along the bottom, the laser rail on top, the grip rail underneath and the sling swivel stud."""
    z0, z1 = FORE_Z0, FORE_Z1
    prof = [(z0, FORE_TOP), (z0, fore_bottom(z0)), (z1, fore_bottom(z1)), (z1, FORE_TOP)]
    prof = fillet(prof, [0.0060, 0.0080, 0.0, 0.0])
    vents = []
    for k in range(5):
        zc = -0.1060 + k * 0.0145
        yc = fore_bottom(zc) + 0.0072
        n = arc_segments(0.0030, 12)
        t = np.linspace(0, 2 * np.pi, n, endpoint=False)
        vents.append(np.stack([zc + 0.0042 * np.cos(t), yc + 0.0017 * np.sin(t)], 1))
    m = extrude_x2(prof, vents, FORE_W, smooth_bevel(FORE_BEVEL, 2), "polymer")
    # the heat shield inside: what the vents look onto (dark, not daylight through the forend)
    m.merge(sh.loft([np.array([(-0.0060, fore_bottom(z) + 0.0040, z), (0.0060, fore_bottom(z) + 0.0040, z),
                               (0.0060, fore_bottom(z) + 0.0110, z), (-0.0060, fore_bottom(z) + 0.0110, z)])
                     for z in (-0.1130, -0.0420)], "steel_dark"))
    # ribs on both flanks
    for side in (-1, 1):
        x = side * (FORE_W - 0.0003)
        for k in range(17):
            z = -0.1240 + k * 0.0050
            yb = fore_bottom(z) + 0.0150
            m.merge(bar((x, 0.0090, z), (x, yb, z), 0.0019, 0.0011, (side, 0, 0), "polymer"))
    # rails: laser on top, front grips under the nose (its foot follows the sloped bottom)
    m.merge(picatinny(TOP_RAIL[0], TOP_RAIL[1], FORE_TOP - 0.0008, FORE_TOP + 0.0050, RAIL_W, 4))
    m.merge(picatinny(LOW_RAIL[0], LOW_RAIL[1], 0.0, UNDER_Y, RAIL_W, 4, down=True,
                      foot_slope=lambda z: fore_bottom(z) + 0.0010))
    # sling swivel stud on the left of the nose (the charm hangs here) and the forend screw at the back
    stud = [(0.0, FORE_W - 0.0008), (0.0034, FORE_W - 0.0008), (0.0034, FORE_W + 0.0030), (0.0022, FORE_W + 0.0036),
            (0.0022, -SWIVEL[0] + 0.0004), (0.0, -SWIVEL[0] + 0.0004)]
    m.merge(sh.revolve(stud, 12, "steel_dark", precision=True).transformed(_X_NEG).translated((0.0, SWIVEL[1],
                                                                                                SWIVEL[2])))
    m.merge(screw(-FORE_W + 0.0004, -0.0195, -0.0310, -1, r=0.0024))
    return m


# --------------------------------------------------------------------------------- barrel, muzzle
def barrel_mesh():
    """Barrel standing out of the forend (its thread is always covered: by the protector, or by a suppressor)."""
    return cyl_z(BARREL_R, CROWN_Z + 0.0020, FORE_Z0 + 0.0030, "barrel", minimum=16)


def muzzle_brake_mesh():
    """Thread protector (node `muzzle_brake`, hidden while the barrel slot holds a suppressor): a knurled collar
    with a chamfered face and the bore."""
    z0, z1 = MUZZLE_Z, SHOULDER_Z - 0.0002
    prof = [(0.0, z0 + 0.0040), (BORE_R + 0.0002, z0 + 0.0040), (BORE_R + 0.0002, z0), (PROT_R - 0.0012, z0),
            (PROT_R, z0 + 0.0012), (PROT_R, z1 - 0.0008), (PROT_R - 0.0008, z1), (0.0, z1)]
    m = sh.revolve(prof, 16, "barrel", precision=True)
    for k in range(6):                                     # knurl ribs round the waist
        a = 2 * np.pi * (k + 0.5) / 6
        n = np.array([np.cos(a), np.sin(a), 0.0])
        p = n * (PROT_R - 0.0007)
        m.merge(bar(p + (0, 0, z0 + 0.0050), p + (0, 0, z1 - 0.0050), 0.0011, 0.0008, n, "barrel"))
    return m


# ------------------------------------------------------------------------------------- the lower
def lower_mesh():
    """Polymer lower (trigger housing): the solid guard web round the finger hole, the front under the receiver, the
    swoop up the receiver's flank to the grip, the web's bottom running back into the grip's front strap (buried in
    it); pins of the trigger and the hammer."""
    prof = [(REC_Z0 - 0.0002, -0.0080), (0.0400, -0.0080), (0.1280, 0.0085), (0.1330, -0.0060), (0.1060, -0.0520),
            (0.0960, -0.0572), (0.0790, -0.0594), (0.0640, -0.0598), (0.0480, -0.0590), (0.0360, -0.0540),
            (0.0300, -0.0440), (0.0060, -0.0372), (REC_Z0 - 0.0002, -0.0322)]
    prof = fillet(prof, [0.0, 0.0, 0.0, 0.0, 0.0040, 0.0060, 0.0080, 0.0080, 0.0080, 0.0060, 0.0080, 0.0040,
                         0.0030])
    n = arc_segments(HOLE_R, 16)
    t = np.linspace(0, 2 * np.pi, n, endpoint=False)
    hole = np.stack([HOLE_C[0] + HOLE_R * np.cos(t), HOLE_C[1] + HOLE_R * np.sin(t)], 1)
    m = extrude_x2(prof, [hole], LOWER_W, smooth_bevel(0.0040, 2), "polymer", hole_bevel=chamfer_bevel(0.0015))
    for p in ((TRIGGER_PIVOT[2], TRIGGER_PIVOT[1]), (0.0700, -0.0150)):
        m.merge(cyl_x(0.0019, -LOWER_W - 0.0003, LOWER_W + 0.0003, p[1], p[0], "steel_dark"))
    return m


GRIP_N = 3.6                     # superellipse exponent of the grip sections (flat-ish flanks for the rib panels)


def _grip_ring(y, zf, zb, w, n=None, inset=0.0, tilt=0.0):
    depth = 0.5 * (zb - zf) - inset
    zc = 0.5 * (zb + zf)
    n = n or arc_segments(0.012, 28)
    sec = sh.superellipse(w - inset, depth, GRIP_N, n)
    yy = y + tilt * (sec[:, 1] / max(depth, 1e-6))
    return np.stack([sec[:, 0], yy, zc + sec[:, 1]], 1)


def grip_mesh():
    """Pistol grip: horizontal superellipse sections raked back, a flared butt (heel higher than the toe), slanted
    ribs on the rear of both side panels and the long slot along the panel's front edge."""
    rings = []
    for k, (y, zf, zb, w) in enumerate(GRIP_SECTIONS):
        tilt = 0.0022 if k >= len(GRIP_SECTIONS) - 2 else 0.0
        rings.append(_grip_ring(y, zf, zb, w, tilt=tilt))
    y, zf, zb, w = GRIP_SECTIONS[-1]
    rings.append(_grip_ring(y - 0.0012, zf, zb, w, tilt=0.0022))
    # the butt rounded over (a moulded base, not a flat cap)
    rings, soft = sd.round_end(rings, 0.0035)
    m = sh.loft(rings, "polymer", soft_bands=soft)
    # side panels: ribs slanting down to the rear, and the slot along their front
    for side in (-1, 1):
        for k in range(13):
            y0 = -0.0545 - k * 0.0031
            yb = y0 - 0.0016
            a, b = _flank(y0, 0.40), _flank(yb, 0.82)
            m.merge(bar((side * a[0], y0, a[1]), (side * b[0], yb, b[1]), 0.0014, 0.0012, (side, 0, 0), "polymer",
                        taper=0.55))
        a, b = _flank(-0.0520, 0.31), _flank(-0.0920, 0.31)
        m.merge(bar((side * a[0], -0.0520, a[1]), (side * b[0], -0.0920, b[1]), 0.0012, 0.0011, (side, 0, 0),
                    "polymer", taper=0.5))
    return m


def _flank(y, frac, sink=0.0008):
    """(x, z) on the grip's flank at height y, `frac` of the way from the front strap to the back strap, sunk
    `sink` into the surface (where a rib's foot goes)."""
    zf, zb, w = _grip_at(y)
    u = 2 * frac - 1
    x = w * (1 - abs(u) ** GRIP_N) ** (1 / GRIP_N)
    return x - sink, zf + frac * (zb - zf)


def _grip_at(y):
    ys = [s[0] for s in GRIP_SECTIONS][::-1]
    cols = np.array(GRIP_SECTIONS)[::-1]
    return tuple(float(np.interp(y, ys, cols[:, i])) for i in (1, 2, 3))


# --------------------------------------------------------------------------------- moving parts
def trigger_mesh():
    """Curved trigger blade hanging into the finger hole (pivot above the hole)."""
    z, y = TRIGGER_PIVOT[2], TRIGGER_PIVOT[1]
    blade = [(z - 0.0035, y + 0.0020), (z + 0.0035, y + 0.0020), (z + 0.0030, y - 0.0080), (z + 0.0012, y - 0.0150),
             (z - 0.0018, y - 0.0205), (z - 0.0052, y - 0.0232), (z - 0.0066, y - 0.0222), (z - 0.0045, y - 0.0190),
             (z - 0.0022, y - 0.0140), (z - 0.0018, y - 0.0080)]
    return extrude_x2(blade, (), 0.0028, chamfer_bevel(0.0007), "steel_dark")


def selector_mesh():
    """Safety / selector lever on the left above the guard's front (the red F behind it is the fire setting)."""
    p = SELECTOR_PIVOT
    m = cyl_x(0.0042, p[0] - 0.0014, p[0] + 0.0004, p[1], p[2], "steel_dark")
    lever = [(p[2] - 0.0030, p[1] + 0.0010), (p[2] + 0.0030, p[1] + 0.0010), (p[2] + 0.0024, p[1] - 0.0110),
             (p[2] - 0.0024, p[1] - 0.0110)]
    m.merge(extrude_x2(lever, (), 0.0008, chamfer_bevel(0.0003), "steel_dark", x_center=p[0] - 0.0018))
    return m


def bolt_mesh():
    """The cocking handle (left side): a ribbed tab on a stem through the slot; node origin at its rest."""
    tab = [(HANDLE_Z0, HANDLE_Y0), (HANDLE_Z1 - 0.0020, HANDLE_Y0), (HANDLE_Z1, HANDLE_Y0 + 0.0016),
           (HANDLE_Z1, HANDLE_Y1 - 0.0016), (HANDLE_Z1 - 0.0020, HANDLE_Y1), (HANDLE_Z0, HANDLE_Y1)]
    m = extrude_x2(tab, (), 0.0016, chamfer_bevel(0.0006), "steel_dark", x_center=-(PLATE_X + 0.0014))
    stem = [(HANDLE_Z0 + 0.0040, HANDLE_Y0 + 0.0006), (HANDLE_Z1 - 0.0040, HANDLE_Y0 + 0.0006),
            (HANDLE_Z1 - 0.0040, HANDLE_Y1 - 0.0006), (HANDLE_Z0 + 0.0040, HANDLE_Y1 - 0.0006)]
    m.merge(extrude_x2(stem, (), 0.0012, ((0.0, 0.0),), "steel_dark", x_center=-(CRADLE_W + 0.0006)))
    for k in range(5):
        z = HANDLE_Z0 + 0.0060 + k * 0.0032
        m.merge(bar((-PLATE_X - 0.0029, HANDLE_Y0 + 0.0006, z), (-PLATE_X - 0.0029, HANDLE_Y1 - 0.0006, z), 0.0014,
                    0.0007, (-1, 0, 0), "steel_dark"))
    return m


# ------------------------------------------------------------------------------------------- sights
def front_post_mesh():
    """Front sight (node `sight_iron`, hidden by an optic): a square post between two protective wings on the tower
    cap."""
    z = FRONT_POST_Z
    post = np.array([(-0.0010, TOWER_TOP - 0.0006), (0.0010, TOWER_TOP - 0.0006), (0.0010, SIGHT_Y - 0.0005),
                     (0.0006, SIGHT_Y), (-0.0006, SIGHT_Y), (-0.0010, SIGHT_Y - 0.0005)])
    from p9_model import extrude_z
    m = extrude_z(post, (), z - 0.0012, z + 0.0012, chamfer_bevel(0.0002), "steel_dark")
    m.merge(sh.box(-0.0034, TOWER_TOP - 0.0006, z - 0.0040, 0.0034, TOWER_TOP + 0.0016, z + 0.0040, 0.0005,
                   "steel_dark"))
    for s in (-1, 1):
        wing = [(s * 0.0040, TOWER_TOP - 0.0006), (s * 0.0072, TOWER_TOP - 0.0006), (s * 0.0068, SIGHT_Y - 0.0010),
                (s * 0.0052, SIGHT_Y - 0.0002), (s * 0.0040, SIGHT_Y - 0.0016)]
        wing = wing if s > 0 else wing[::-1]
        m.merge(extrude_z(np.array(wing), (), z - 0.0050, z + 0.0045, chamfer_bevel(0.0004), "steel_dark"))
    return m


def rear_sight_mesh():
    """Rear sight (node `sight_rear`, hidden by an optic): a notch blade on a clamp at the rear of the bridge rail."""
    from p9_model import extrude_z
    zr = REAR_NOTCH_Z
    m = sh.box(-0.0080, RAIL_TOP - 0.0010, zr - 0.0120, 0.0080, RAIL_TOP + 0.0030, zr - 0.0005, 0.0007, "steel_dark")
    y0 = RAIL_TOP + 0.0020
    notch = np.array([(-0.0080, y0), (0.0080, y0), (0.0080, SIGHT_Y - 0.0008), (0.0068, SIGHT_Y),
                      (0.0013, SIGHT_Y), (0.0013, SIGHT_Y - 0.0030), (-0.0013, SIGHT_Y - 0.0030),
                      (-0.0013, SIGHT_Y), (-0.0068, SIGHT_Y), (-0.0080, SIGHT_Y - 0.0008)])
    m.merge(extrude_z(notch, (), zr - 0.0032, zr, chamfer_bevel(0.0003), "steel_dark"))
    m.merge(screw(0.0080, RAIL_TOP + 0.0010, zr - 0.0065, 1, r=0.0016))
    return m


# ------------------------------------------------------------------------------------------- magazine
def _mag_section(r=MAG_R, flat=MAG_FLAT):
    """Magazine body section (x, y about its axis): round, two moulded seams high on each flank, the flat it slides
    on underneath."""
    n = arc_segments(r, 24)
    d = np.radians(0.8)
    seams = np.radians([22.0, 158.0, 212.0, 328.0])
    base = np.linspace(-np.pi / 2, 1.5 * np.pi, n, endpoint=False)
    base = [a for a in base if min(abs((a - s + np.pi) % (2 * np.pi) - np.pi) for s in seams) > 1.5 * d]
    pts = [(a, r) for a in base]
    for s in seams:                                            # shallow V seams of the two mould halves
        pts += [(s - d, r), (s, r - 0.0006), (s + d, r)]
    pts.sort(key=lambda q: (q[0] + np.pi / 2) % (2 * np.pi))
    p = np.array([(rr * np.cos(a), rr * np.sin(a)) for a, rr in pts])
    p[:, 1] = np.maximum(p[:, 1], -flat)
    keep = [0] + [i for i in range(1, len(p)) if np.linalg.norm(p[i] - p[i - 1]) > 1e-6]
    return p[keep]


def mag_mesh(extended=False):
    """Helical magazine (mag-local: origin on its front face's centre, axis along +Z): the polymer body with its
    seams, the front latch under its nose (hooks into the tower), the latch lever on top, the rear cap and the winding
    key; the 100-round magazine is 12.3 cm longer."""
    zr = MAG_BODY[extended] - MAG_Z0
    sec = _mag_section()
    # the moulded nose rounded over (it lies in full view on top of the gun)
    rings = [np.stack([sec[:, 0], sec[:, 1], np.full(len(sec), z)], 1) for z in (0.0, 0.0300, zr)]
    rings, soft = sd.round_end(rings, 0.0040, at_end=False)
    m = sh.loft(rings, "mag", soft_bands=soft)
    # rear cap and the winding key on it
    c = 0.0016                                   # open at the front: that end is buried in the body
    m.merge(sh.revolve([(CAP_R, zr - 0.0010), (CAP_R, zr + CAP_LEN - c), (CAP_R - c, zr + CAP_LEN),
                        (0.0, zr + CAP_LEN)], 24, "mag_base", precision=True))
    e = zr + CAP_LEN
    key = [(0.0, e - 0.0005), (0.0046, e - 0.0005), (0.0046, e + 0.0016), (0.0030, e + 0.0026), (0.0030, e + 0.0074),
           (0.0022, e + 0.0084), (0.0, e + 0.0084)]
    m.merge(sh.revolve(key, 12, "mag_base", precision=True).translated((0.0, KEY_Y, 0.0)))
    m.merge(sh.box(-0.0045, KEY_Y + 0.0024, e + 0.0040, 0.0045, KEY_Y + 0.0046, e + 0.0074, 0.0004, "mag_base"))
    # front: the latch hook under the nose and the release lever on top
    m.merge(sh.box(-0.0060, -MAG_FLAT - 0.0022, -0.0030, 0.0060, -MAG_FLAT + 0.0030, 0.0090, 0.0006, "mag_base"))
    m.merge(sh.box(-0.0048, MAG_R - 0.0010, 0.0010, 0.0048, MAG_R + 0.0024, 0.0150, 0.0007, "mag_base"))
    # moulded grip bands near both ends (the hand lifts it there)
    for z in (0.0200, zr - 0.0500):
        for s in (-1, 1):
            a = np.radians(90 - s * 38)
            p = np.array([MAG_R * np.cos(a), MAG_R * np.sin(a), 0.0])
            nrm = np.array([np.cos(a), np.sin(a), 0.0])
            m.merge(bar(p + (0, 0, z - 0.0060), p + (0, 0, z + 0.0060), 0.0020, 0.0006, nrm, "mag_base"))
    return m


def mag_meshes():
    return {False: mag_mesh(False), True: mag_mesh(True)}


# ------------------------------------------------------------------------------------------- shell
def shell_mesh(scale=1.25):
    """Spent 9x19 mm brass case (material `brass`), along Z, mouth towards -Z (the P9's)."""
    prof = [(0.0, 0.0040), (0.0041, 0.0040), (0.0041, -0.00955), (0.00482, -0.00955), (0.00496, 0.0064),
            (0.00432, 0.0074), (0.00498, 0.0085), (0.00498, 0.00955), (0.0, 0.00955)]
    return sh.revolve([(r * scale, z * scale) for r, z in prof], 16, "brass", precision=True)
