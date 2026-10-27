"""Ammo crate geometry (VIEWMODEL_SPEC section 46). Block frame (metres): the origin at the middle of the block's
floor, +Y up, the crate's front (latches, markings) towards -Z, its long side along X; everything inside the block
(|x|, |z| < 0.5, 0 <= y < 1). Original design after a military wooden ammunition crate: board walls of three planks
on two skids, corner and end cleats, rope handles through the end cleats, a framed lid on two strap hinges at the back
held shut by two over-centre latches at the front, olive paint with a yellow band; inside, two steel ammo cans and a row
of loaded magazines."""
import numpy as np

import shapes as sh
from meshkit import Mesh

W, D = 0.84, 0.50                       # outer width (x) and depth (z) of the body
SKID = 0.025                            # skid height
T = 0.022                               # wall board thickness
BODY_TOP = 0.385                        # top edge of the walls (the lid sits on it)
LID_T = 0.026                           # lid board thickness
LID_TOP = BODY_TOP + LID_T
HINGE = np.array([0.0, BODY_TOP, D / 2 + 0.006])          # the lid's pivot (its axis along X)
LATCH_X = (-0.25, 0.25)
LATCH_PIVOT_Y = BODY_TOP - 0.052                           # over-centre latch: the lever turns about its foot
LATCH_Z = -D / 2 - 0.004
FLOOR = SKID + T                                           # the inside floor
OPEN_DEG = 105.0                                           # the lid's open angle
LATCH_OPEN_DEG = -112.0                                    # the latch levers swung down and out


def _box(x0, y0, z0, x1, y1, z1, mat, c=0.0015):
    return sh.box(min(x0, x1), min(y0, y1), min(z0, z1), max(x0, x1), max(y0, y1), max(z0, z1), c, mat)


# ---------------------------------------------------------------------------------------------- body
def _wall_x(z_face, outward):
    """A long wall (front or back) at z_face: a core board and three planks standing 1.5 mm proud, a groove between."""
    m = Mesh()
    zi = z_face - outward * T
    m.merge(_box(-W / 2 + T, FLOOR - T, zi, W / 2 - T, BODY_TOP, z_face - outward * 0.0015, "wood"))
    h = (BODY_TOP - SKID) / 3
    for k in range(3):
        y0, y1 = SKID + k * h + 0.0013, SKID + (k + 1) * h - 0.0013
        m.merge(_box(-W / 2, y0, z_face - outward * 0.006, W / 2, y1, z_face, "wood", 0.002))
    return m


def _wall_z(x_face, outward):
    m = Mesh()
    m.merge(_box(x_face - outward * T, FLOOR - T, -D / 2 + 0.003, x_face - outward * 0.0015, BODY_TOP, D / 2 - 0.003, "wood"))
    h = (BODY_TOP - SKID) / 3
    for k in range(3):
        y0, y1 = SKID + k * h + 0.0013, SKID + (k + 1) * h - 0.0013
        m.merge(_box(x_face - outward * 0.006, y0, -D / 2 + 0.002, x_face, y1, D / 2 - 0.002, "wood", 0.002))
    return m


def _tube(path, r, segs, mat):
    """A round rope / rod along a path (open ends capped)."""
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


def _rope_handle(sx):
    """Rope loop through two holes in the end cleat, hanging a little below it; a knot block over each hole."""
    x = sx * (W / 2 + 0.012)
    xo = sx * (W / 2 + 0.034)
    path = [(x, 0.262, -0.075), (xo, 0.258, -0.074)]
    for a in np.linspace(0, np.pi, 11)[1:-1]:
        path.append((xo + sx * 0.006 * np.sin(a), 0.255 - 0.055 * np.sin(a), -0.074 * np.cos(a)))
    path += [(xo, 0.258, 0.074), (x, 0.262, 0.075)]
    m = _tube(path, 0.0075, 10, "rope")
    for z in (-0.075, 0.075):                                   # whipped ends over the holes
        m.merge(_tube([(x - sx * 0.002, 0.262, z), (x + sx * 0.010, 0.262, z)], 0.011, 10, "rope"))
    return m


def body_mesh():
    m = Mesh()
    # skids along X, the bottom board
    for z in (-0.17, 0.17):
        m.merge(_box(-W / 2 + 0.01, 0.0, z - 0.035, W / 2 - 0.01, SKID, z + 0.035, "wood", 0.003))
    m.merge(_box(-W / 2 + T, SKID, -D / 2 + T, W / 2 - T, FLOOR, D / 2 - T, "wood"))
    m.merge(_wall_x(-D / 2, -1))
    m.merge(_wall_x(D / 2, 1))
    m.merge(_wall_z(-W / 2, -1))
    m.merge(_wall_z(W / 2, 1))
    # corner cleats on the long walls and the end cleats (the rope handles go through them)
    for sz in (-1, 1):
        z_face = sz * (D / 2 + 0.006)
        for sx in (-1, 1):
            m.merge(_box(sx * W / 2, SKID, z_face, sx * (W / 2 - 0.065), BODY_TOP - 0.004, z_face + sz * 0.013, "wood", 0.003))
        # the yellow band across the front and back
        m.merge(_box(-0.09, SKID + 0.02, z_face + sz * 0.0004, 0.09, BODY_TOP - 0.02, z_face + sz * 0.0009, "marking", 0.0))
    for sx in (-1, 1):
        x_face = sx * (W / 2 + 0.006)
        m.merge(_box(x_face, 0.215, -D / 2 + 0.01, x_face + sx * 0.016, 0.300, D / 2 - 0.01, "wood", 0.003))
        m.merge(_rope_handle(sx))
    # corner brackets: bent steel straps round the four upper and four lower corners
    for sx in (-1, 1):
        for sz in (-1, 1):
            for y0, y1 in ((SKID, SKID + 0.055), (BODY_TOP - 0.055, BODY_TOP)):
                xa, za = sx * (W / 2 + 0.0195), sz * (D / 2 + 0.0195)
                m.merge(_box(xa, y0, za, xa - sx * 0.075, y1, za - sz * 0.0025, "metal", 0.0008))
                m.merge(_box(xa, y0, za, xa - sx * 0.0025, y1, za - sz * 0.075, "metal", 0.0008))
    # hinge knuckles and their leaves on the back wall
    for x in LATCH_X:
        m.merge(_tube([(x - 0.032, HINGE[1], HINGE[2]), (x + 0.032, HINGE[1], HINGE[2])], 0.0075, 12, "metal"))
        m.merge(_box(x - 0.030, BODY_TOP - 0.065, D / 2 + 0.0195, x + 0.030, BODY_TOP - 0.004, D / 2 + 0.023, "metal", 0.0008))
    # latch base plates on the front wall
    for x in LATCH_X:
        m.merge(_box(x - 0.024, LATCH_PIVOT_Y - 0.030, -D / 2 - 0.006, x + 0.024, LATCH_PIVOT_Y + 0.012, -D / 2 - 0.0095,
                     "metal", 0.0008))
        m.merge(_tube([(x - 0.018, LATCH_PIVOT_Y, LATCH_Z - 0.006), (x + 0.018, LATCH_PIVOT_Y, LATCH_Z - 0.006)], 0.0045, 10,
                      "metal"))
    return m


# ---------------------------------------------------------------------------------------------- lid
def lid_mesh():
    """The framed lid: boards with a rim frame and two cross battens on top, the hinge leaves at the back, the latches'
    catches at the front, the yellow band across it."""
    m = Mesh()
    m.merge(_box(-W / 2 - 0.004, BODY_TOP, -D / 2 - 0.006, W / 2 + 0.004, BODY_TOP + LID_T * 0.6, D / 2 + 0.006, "wood", 0.002))
    top = BODY_TOP + LID_T * 0.6
    for sz in (-1, 1):                                            # rim frame
        m.merge(_box(-W / 2 - 0.004, top, sz * (D / 2 + 0.006), W / 2 + 0.004, LID_TOP, sz * (D / 2 - 0.040), "wood", 0.003))
    for sx in (-1, 1):
        m.merge(_box(sx * (W / 2 + 0.004), top, -D / 2 + 0.03, sx * (W / 2 - 0.045), LID_TOP, D / 2 - 0.03, "wood", 0.003))
        m.merge(_box(sx * 0.215, top, -D / 2 + 0.03, sx * 0.285, LID_TOP - 0.002, D / 2 - 0.03, "wood", 0.003))
    m.merge(_box(-0.09, top, -D / 2 + 0.035, 0.09, top + 0.0006, D / 2 - 0.035, "marking", 0.0))
    for x in LATCH_X:
        m.merge(_box(x - 0.030, BODY_TOP + 0.004, D / 2 + 0.006, x + 0.030, BODY_TOP + 0.0095, D / 2 - 0.090, "metal", 0.0008))
        # the catch the latch hooks over
        m.merge(_box(x - 0.016, BODY_TOP + 0.004, -D / 2 - 0.006, x + 0.016, BODY_TOP + 0.016, -D / 2 - 0.013, "metal", 0.0008))
    return m


def latch_mesh(x):
    """An over-centre latch lever (in block space, closed): from its foot on the base plate up over the lid's catch."""
    m = Mesh()
    z = LATCH_Z - 0.012
    m.merge(_box(x - 0.013, LATCH_PIVOT_Y - 0.004, z - 0.003, x + 0.013, BODY_TOP + 0.014, z + 0.003, "metal", 0.0008))
    # the lip hooking over the catch, and a finger tab
    m.merge(_box(x - 0.013, BODY_TOP + 0.010, z + 0.003, x + 0.013, BODY_TOP + 0.016, -D / 2 - 0.006, "metal", 0.0008))
    m.merge(_box(x - 0.010, BODY_TOP - 0.004, z - 0.009, x + 0.010, BODY_TOP + 0.008, z - 0.003, "metal", 0.0008))
    return m


# ------------------------------------------------------------------------------------------- contents
def _ammo_can(x0, x1, z0, z1, h):
    """A steel ammo can: body, the lid with its seal lip, the folded carry handle and the cam latch at one end."""
    m = Mesh()
    m.merge(_box(x0, FLOOR, z0, x1, FLOOR + h - 0.03, z1, "can", 0.006))
    m.merge(_box(x0 - 0.003, FLOOR + h - 0.032, z0 - 0.003, x1 + 0.003, FLOOR + h, z1 + 0.003, "can", 0.005))
    m.merge(_box(x0 + 0.004, FLOOR + h - 0.036, z0 - 0.001, x1 - 0.004, FLOOR + h - 0.030, z1 + 0.001, "can", 0.001))
    xm = 0.5 * (x0 + x1)
    m.merge(_tube([(xm - 0.05, FLOOR + h + 0.004, 0.5 * (z0 + z1)), (xm + 0.05, FLOOR + h + 0.004, 0.5 * (z0 + z1))],
                  0.006, 10, "metal"))
    for sx in (-1, 1):
        m.merge(_box(xm + sx * 0.05, FLOOR + h, 0.5 * (z0 + z1) - 0.004, xm + sx * 0.058, FLOOR + h + 0.006,
                     0.5 * (z0 + z1) + 0.004, "metal", 0.0008))
    m.merge(_box(x1 + 0.003, FLOOR + h - 0.07, 0.5 * (z0 + z1) - 0.02, x1 + 0.011, FLOOR + h - 0.004,
                 0.5 * (z0 + z1) + 0.02, "metal", 0.001))
    return m


def _magazine(x, z, h=0.175):
    """A loaded box magazine standing in the crate: the body, its base plate, the feed lips and the top round."""
    m = Mesh()
    m.merge(_box(x - 0.012, FLOOR + 0.008, z - 0.033, x + 0.012, FLOOR + h, z + 0.033, "mag", 0.003))
    m.merge(_box(x - 0.014, FLOOR, z - 0.037, x + 0.014, FLOOR + 0.009, z + 0.037, "mag", 0.002))
    m.merge(_box(x - 0.010, FLOOR + h, z - 0.028, x + 0.010, FLOOR + h + 0.006, z + 0.022, "mag", 0.001))
    m.merge(_tube([(x, FLOOR + h + 0.009, z - 0.026), (x, FLOOR + h + 0.009, z + 0.016)], 0.0048, 10, "brass"))
    m.merge(_tube([(x, FLOOR + h + 0.009, z + 0.016), (x, FLOOR + h + 0.009, z + 0.026)], 0.0030, 10, "brass"))
    return m


def contents_mesh():
    m = Mesh()
    with sh.authored():                       # inside the crate, seen from a step away: no rounded edges (spec 53)
        m.merge(_ammo_can(-0.385, -0.095, -0.205, -0.045, 0.19))
        m.merge(_ammo_can(-0.385, -0.095, 0.035, 0.195, 0.19))
        m.merge(_ammo_can(-0.070, 0.180, -0.205, -0.045, 0.19))
        for k, z in enumerate(np.linspace(-0.17, 0.17, 6)):
            m.merge(_magazine(0.235 + 0.06 * (k % 2), z + 0.012 * (k % 3 - 1)))
        m.merge(_magazine(0.040, 0.10))
        m.merge(_magazine(0.075, 0.12, 0.170))
    return m


def parts():
    """Block-frame meshes per node."""
    return {"body": body_mesh(), "contents": contents_mesh(), "lid": lid_mesh(),
            "latch_L": latch_mesh(LATCH_X[0]), "latch_R": latch_mesh(LATCH_X[1])}


PIVOTS = {"body": np.zeros(3), "contents": np.zeros(3), "lid": HINGE.copy(),
          "latch_L": np.array([LATCH_X[0], LATCH_PIVOT_Y, LATCH_Z - 0.006]),
          "latch_R": np.array([LATCH_X[1], LATCH_PIVOT_Y, LATCH_Z - 0.006])}
