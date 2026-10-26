"""Revive antenna geometry (VIEWMODEL_SPEC section 51). Block frame (metres): the origin at the middle of the lower
block's floor, +Y up, the front (latches, the medic panel, the dish) towards -Z; everything inside the two blocks it
occupies (|x|, |z| < 0.5, 0 <= y < 2). Original design after a military field relay: a rugged polymer case holding the
radio and its battery (ribbed shell, a lid with two draw latches, folding side handles, a medic panel on the front, a
control box with a dim screen and two knobs on the lid), a three-stage telescopic mast clamped to the lid, a coiled
lead from the control box to the mast, and on top the radio head with a small dish towards the front, a whip antenna on
a spring base and a red beacon in a wire guard (lit and dark as two nodes the renderer alternates)."""
import numpy as np

import shapes as sh
from meshkit import Mesh

# case
CW, CD = 0.56, 0.38                     # outer width (x) and depth (z) of the shell
CZ = 0.00                               # its middle along z
FEET = 0.018                            # rubber feet height
SHELL_TOP = 0.205                       # top edge of the lower shell (the lid seam)
LID_TOP = 0.275                         # the lid's top face
# mast (on the case's middle, a little to the back)
MAST_X, MAST_Z = 0.0, 0.055
MAST = ((0.030, LID_TOP, 0.90), (0.024, 0.90, 1.14), (0.018, 1.14, 1.32))   # (radius, bottom, top) of each stage
# radio head on the mast
HEAD = (-0.075, 1.32, MAST_Z - 0.065, 0.075, 1.465, MAST_Z + 0.065)
DISH_C = np.array([0.0, 1.39, MAST_Z - 0.165])                 # the dish's rim centre (it looks towards -Z)
DISH_R, DISH_DEPTH = 0.115, 0.034
WHIP_X = 0.045
WHIP_TOP = 1.975
BEACON = np.array([-0.032, HEAD[4], MAST_Z])                    # the beacon's foot (on the head's top)
BEACON_R, BEACON_H = 0.021, 0.036
BEACON_CENTRE = BEACON + np.array([0.0, 0.012 + 0.5 * BEACON_H, 0.0])


def _box(x0, y0, z0, x1, y1, z1, mat, c=0.0015):
    return sh.box(min(x0, x1), min(y0, y1), min(z0, z1), max(x0, x1), max(y0, y1), max(z0, z1), c, mat)


def tube(path, r, segs, mat):
    """A round rod along a path, ends capped."""
    path = np.asarray(path, float)
    rings = []
    for i, p in enumerate(path):
        t = path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)]
        t /= np.linalg.norm(t)
        a = np.array([0.0, 1.0, 0.0]) if abs(t[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
        u = np.cross(t, a)
        u /= np.linalg.norm(u)
        v = np.cross(t, u)
        th = np.linspace(0, 2 * np.pi, segs, endpoint=False)
        rings.append(p + r * (np.outer(np.cos(th), u) + np.outer(np.sin(th), v)))
    return sh.loft(rings, mat)


def _upright(mesh, x, z):
    """A mesh revolved round local Z, stood up along +Y at (x, z)."""
    return sh.place(mesh, origin=(x, 0, z), x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))


def lathe(profile, segs, mat, x, z):
    """A closed (r, y) profile turned round the vertical through (x, z), wound outwards."""
    m = sh.revolve(profile, segs, mat)
    sh.orient_outward(m)
    return _upright(m, x, z)


def cylinder(x, z, y0, y1, r, segs, mat):
    return tube([(x, y0, z), (x, y1, z)], r, segs, mat)


# ---------------------------------------------------------------------------------------------- base
def _shell():
    """The lower shell: a chamfered box with three raised ribs round it and recessed side pockets for the handles."""
    m = Mesh()
    x0, x1, z0, z1 = -CW / 2, CW / 2, CZ - CD / 2, CZ + CD / 2
    m.merge(_box(x0 + 0.006, FEET, z0 + 0.006, x1 - 0.006, SHELL_TOP, z1 - 0.006, "case", 0.012))
    for y in (0.055, 0.115, 0.172):                          # moulded ribs (stiffeners) round the shell
        m.merge(_box(x0, y - 0.008, z0, x1, y + 0.008, z1, "case", 0.004))
    # lid: a slightly larger chamfered slab with a rim lip over the seam
    m.merge(_box(x0 - 0.002, SHELL_TOP, z0 - 0.002, x1 + 0.002, SHELL_TOP + 0.014, z1 + 0.002, "case", 0.004))
    m.merge(_box(x0 + 0.004, SHELL_TOP + 0.014, z0 + 0.004, x1 - 0.004, LID_TOP, z1 - 0.004, "case", 0.014))
    # rubber feet
    for sx in (-1, 1):
        for sz in (-1, 1):
            m.merge(cylinder(sx * (CW / 2 - 0.05), CZ + sz * (CD / 2 - 0.05), 0.0, FEET + 0.002, 0.022, 12, "rubber"))
    return m


def _latches():
    """Two draw latches on the front: a base on the shell, the lever up over a keeper on the lid."""
    m = Mesh()
    zf = CZ - CD / 2
    for x in (-0.17, 0.17):
        m.merge(_box(x - 0.028, SHELL_TOP - 0.060, zf - 0.004, x + 0.028, SHELL_TOP - 0.012, zf + 0.002, "hardware", 0.002))
        m.merge(_box(x - 0.024, SHELL_TOP - 0.050, zf - 0.016, x + 0.024, SHELL_TOP + 0.030, zf - 0.004, "hardware", 0.003))
        m.merge(_box(x - 0.020, SHELL_TOP + 0.020, zf - 0.010, x + 0.020, SHELL_TOP + 0.040, zf + 0.002, "hardware", 0.002))
        m.merge(_box(x - 0.010, SHELL_TOP - 0.046, zf - 0.020, x + 0.010, SHELL_TOP - 0.030, zf - 0.016, "hardware", 0.001))
    # hinges along the back
    zb = CZ + CD / 2
    for x in (-0.17, 0.17):
        m.merge(tube([(x - 0.045, SHELL_TOP + 0.004, zb + 0.008), (x + 0.045, SHELL_TOP + 0.004, zb + 0.008)], 0.009, 10,
                     "hardware"))
    return m


def _handles():
    """Folding carry handles on both ends: two pivot bosses and a rubber-gripped bar hanging down."""
    m = Mesh()
    for sx in (-1, 1):
        xf = sx * CW / 2
        for z in (-0.075, 0.075):
            m.merge(tube([(xf - sx * 0.004, 0.170, CZ + z), (xf + sx * 0.016, 0.170, CZ + z)], 0.013, 12, "hardware"))
        xb = xf + sx * 0.024
        path = [(xb, 0.166, CZ - 0.075), (xb, 0.118, CZ - 0.065), (xb, 0.108, CZ - 0.04), (xb, 0.108, CZ + 0.04),
                (xb, 0.118, CZ + 0.065), (xb, 0.166, CZ + 0.075)]
        m.merge(tube(path, 0.007, 10, "hardware"))
        m.merge(tube([(xb, 0.108, CZ - 0.042), (xb, 0.108, CZ + 0.042)], 0.012, 12, "rubber"))
    return m


def _medic_panel():
    """A white panel on the front of the shell with a red cross."""
    m = Mesh()
    zf = CZ - CD / 2 - 0.0005
    m.merge(_box(-0.065, 0.040, zf + 0.004, 0.065, 0.150, zf - 0.002, "panel_white", 0.002))
    m.merge(_box(-0.042, 0.083, zf - 0.002, 0.042, 0.107, zf - 0.0035, "panel_red", 0.0))
    m.merge(_box(-0.012, 0.053, zf - 0.002, 0.012, 0.137, zf - 0.0035, "panel_red", 0.0))
    return m


def _control_box():
    """The control box on the lid's front left: housing, a recessed dim screen, two knobs and a cable gland."""
    m = Mesh()
    x0, x1, z0, z1 = -0.235, -0.065, CZ - 0.165, CZ - 0.030
    m.merge(_box(x0, LID_TOP - 0.002, z0, x1, LID_TOP + 0.052, z1, "case_dark", 0.006))
    # the screen on its sloped-looking front: a recessed window on the top
    m.merge(_box(x0 + 0.015, LID_TOP + 0.050, z0 + 0.018, x1 - 0.060, LID_TOP + 0.0535, z1 - 0.020, "screen", 0.0))
    for k, x in enumerate((x1 - 0.040, x1 - 0.018)):
        m.merge(lathe([(0.0001, 0.0), (0.011, 0.0), (0.011, 0.016), (0.008, 0.020), (0.0001, 0.020), (0.0001, 0.0)],
                      14, "hardware", 0, 0).translated((x, LID_TOP + 0.050, z0 + 0.035 + 0.040 * k)))
    m.merge(cylinder(x1 - 0.020, z1 - 0.012, LID_TOP + 0.050, LID_TOP + 0.064, 0.010, 12, "hardware"))
    return m


def _mast_mount():
    """The mast's base on the lid: a plate, a split clamp ring with its lever."""
    m = Mesh()
    m.merge(_box(MAST_X - 0.060, LID_TOP - 0.002, MAST_Z - 0.060, MAST_X + 0.060, LID_TOP + 0.012, MAST_Z + 0.060,
                 "hardware", 0.004))
    m.merge(cylinder(MAST_X, MAST_Z, LID_TOP + 0.010, LID_TOP + 0.075, 0.042, 20, "hardware"))
    m.merge(_box(MAST_X + 0.038, LID_TOP + 0.030, MAST_Z - 0.010, MAST_X + 0.090, LID_TOP + 0.050, MAST_Z + 0.010,
                 "hardware", 0.003))
    for sx in (-1, 1):                                       # bolts in the plate's corners
        for sz in (-1, 1):
            m.merge(cylinder(MAST_X + sx * 0.045, MAST_Z + sz * 0.045, LID_TOP + 0.010, LID_TOP + 0.018, 0.007, 8,
                             "hardware"))
    return m


def _lead():
    """The coiled lead from the control box's gland up to the mast."""
    start = np.array([-0.085, LID_TOP + 0.064, CZ - 0.042])
    end = np.array([MAST_X - 0.030, 0.62, MAST_Z])
    pts = []
    for t in np.linspace(0, 1, 46):
        p = start + (end - start) * t
        p[1] += 0.10 * np.sin(np.pi * t) * (1 - t)             # rises out of the gland, then droops to the mast
        if 0.25 < t < 0.85:                                    # the coiled middle
            a = (t - 0.25) / 0.6 * 2 * np.pi * 7
            p += 0.012 * np.array([np.cos(a), 0.0, np.sin(a)])
        pts.append(p)
    m = tube(pts, 0.0055, 8, "rubber")
    m.merge(cylinder(MAST_X - 0.024, MAST_Z, 0.60, 0.645, 0.012, 10, "hardware"))   # the plug on the mast
    return m


def base_mesh():
    m = Mesh()
    m.merge(_shell())
    m.merge(_latches())
    m.merge(_handles())
    m.merge(_medic_panel())
    m.merge(_control_box())
    m.merge(_mast_mount())
    m.merge(_lead())
    return m


# ---------------------------------------------------------------------------------------------- mast
def _mast():
    """Three telescopic stages, each with a locking collar at the top of the one below."""
    m = Mesh()
    for k, (r, y0, y1) in enumerate(MAST):
        m.merge(cylinder(MAST_X, MAST_Z, y0, y1, r, 20, "mast"))
        if k < len(MAST) - 1:
            m.merge(cylinder(MAST_X, MAST_Z, y1 - 0.035, y1 + 0.004, r + 0.007, 20, "hardware"))
            m.merge(_box(MAST_X + r + 0.004, y1 - 0.028, MAST_Z - 0.006, MAST_X + r + 0.022, y1 - 0.010, MAST_Z + 0.006,
                         "hardware", 0.002))
    return m


def _head():
    """The radio head: a ribbed box (cooling fins at the back), its dish arm, the dish with a feed, the whip on a spring."""
    m = Mesh()
    x0, y0, z0, x1, y1, z1 = HEAD
    m.merge(_box(x0, y0, z0, x1, y1, z1, "head", 0.010))
    for k in range(5):                                       # fins on the back
        x = x0 + 0.020 + k * (x1 - x0 - 0.040) / 4
        m.merge(_box(x - 0.003, y0 + 0.015, z1 - 0.002, x + 0.003, y1 - 0.015, z1 + 0.014, "head", 0.001))
    # the dish's arm out of the front and its back plate
    m.merge(_box(-0.014, DISH_C[1] - 0.014, z0 - 0.002, 0.014, DISH_C[1] + 0.014, DISH_C[2] + DISH_DEPTH + 0.010,
                 "hardware", 0.003))
    # dish: a thin paraboloid shell round a hub, its rim at DISH_C and its hub DISH_DEPTH behind it (towards the head),
    # so the concave side looks towards -Z; a rolled rim. Profile (r, z) relative to DISH_C, revolved round Z.
    prof = []
    rs_ = np.linspace(0.016, DISH_R, 9)
    for r in rs_:                                             # front (concave) surface, hub -> rim
        prof.append((r, DISH_DEPTH * (1 - (r / DISH_R) ** 2)))
    prof.append((DISH_R + 0.004, -0.002))
    prof.append((DISH_R + 0.004, 0.006))
    for r in rs_[::-1]:                                       # back surface, rim -> hub
        prof.append((r, DISH_DEPTH * (1 - (r / DISH_R) ** 2) + 0.008))
    prof.append(prof[0])
    dish = sh.revolve(prof, 28, "dish")
    sh.orient_outward(dish)
    m.merge(dish.translated(DISH_C))
    m.merge(sh.box(-0.018, -0.018, 0, 0.018, 0.018, 0.012, 0.003, "hardware")
            .translated(DISH_C + np.array([0, 0, DISH_DEPTH + 0.006])))
    # the feed: three struts from the rim to a small horn at the focus
    focus = DISH_C + np.array([0, 0, -0.060])
    for a in (np.pi / 2, np.pi / 2 + 2 * np.pi / 3, np.pi / 2 + 4 * np.pi / 3):
        rim = DISH_C + np.array([DISH_R * np.cos(a), DISH_R * np.sin(a), 0.002])
        m.merge(tube([rim, focus + np.array([0, 0, 0.010])], 0.0028, 6, "hardware"))
    m.merge(tube([focus + np.array([0, 0, 0.014]), focus - np.array([0, 0, 0.016])], 0.012, 12, "head"))
    # whip: a spring base on the head's top, the thin rod, a ball tip
    wy = y1
    m.merge(cylinder(WHIP_X, MAST_Z, wy - 0.002, wy + 0.016, 0.012, 14, "hardware"))
    coil = []
    for t in np.linspace(0, 1, 60):
        a = t * 2 * np.pi * 6
        coil.append((WHIP_X + 0.007 * np.cos(a), wy + 0.016 + 0.040 * t, MAST_Z + 0.007 * np.sin(a)))
    m.merge(tube(coil, 0.0022, 6, "whip"))
    m.merge(cylinder(WHIP_X, MAST_Z, wy + 0.016, wy + 0.200, 0.0060, 8, "whip"))          # the thicker lower section
    m.merge(cylinder(WHIP_X, MAST_Z, wy + 0.195, WHIP_TOP - 0.008, 0.0042, 8, "whip"))
    m.merge(lathe([(0.0001, -0.008), (0.006, -0.006), (0.008, 0.0), (0.006, 0.006), (0.0001, 0.008), (0.0001, -0.008)],
                  10, "whip", WHIP_X, MAST_Z).translated((0, WHIP_TOP - 0.008, 0)))
    # beacon base and its wire guard
    bx, by, bz = BEACON
    m.merge(cylinder(bx, bz, by - 0.002, by + 0.012, BEACON_R + 0.006, 16, "hardware"))
    top = by + 0.012 + BEACON_H + 0.010
    for a in np.linspace(0, 2 * np.pi, 4, endpoint=False) + np.pi / 4:
        x, z = bx + (BEACON_R + 0.007) * np.cos(a), bz + (BEACON_R + 0.007) * np.sin(a)
        m.merge(tube([(x, by + 0.010, z), (x, top - 0.004, z), (bx + 0.6 * (x - bx), top, bz + 0.6 * (z - bz))], 0.0018, 6,
                     "hardware"))
    m.merge(cylinder(bx, bz, top - 0.002, top + 0.002, 0.008, 10, "hardware"))
    return m


def mast_mesh():
    m = Mesh()
    m.merge(_mast())
    m.merge(_head())
    return m


def beacon_mesh(mat):
    """The beacon's dome (block frame): a short cylinder rounding into a cap."""
    bx, by, bz = BEACON
    y0 = by + 0.012
    prof = [(0.0001, y0), (BEACON_R, y0)]
    for t in np.linspace(0, 1, 7)[1:]:
        a = t * np.pi / 2
        prof.append((BEACON_R * np.cos(a) if t < 1 else 0.0001, y0 + 0.45 * BEACON_H + 0.55 * BEACON_H * np.sin(a)))
    prof.insert(2, (BEACON_R, y0 + 0.45 * BEACON_H))
    prof.append(prof[0])
    return lathe(prof, 18, mat, bx, bz)


def parts():
    """Block-frame meshes per node."""
    return {"base": base_mesh(), "mast": mast_mesh(), "beacon": beacon_mesh("beacon"),
            "beacon_off": beacon_mesh("beacon_off")}


PIVOTS = {"base": np.zeros(3), "mast": np.zeros(3), "beacon": BEACON_CENTRE.copy(), "beacon_off": BEACON_CENTRE.copy()}

# collision / outline boxes per half in block pixels with the front north (ReviveAntennaBlock; checked against these)
LOWER_BOX = (8 + 16 * (-CW / 2), 0.0, 8 + 16 * (CZ - CD / 2), 8 + 16 * (CW / 2), 16 * LID_TOP, 8 + 16 * (CZ + CD / 2))
