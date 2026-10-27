"""Brass knuckle-duster geometry (VIEWMODEL_SPEC section 42), built in the RIGHT hand's frame (hands.py: the palm
hangs from the wrist along -Y, the palm faces +Z, the thumb is on -X) for a closed fist whose four fingers pass
straight through the rings (knuckles_rig.DUSTER_FIST: first segments curled 90 degrees, so they run along +Z from the
knuckle line at y = -PALM_LEN).

An original design: a flat cast plate between the palm face and the curled middle segments - four oval finger rings
round the first finger segments, a scalloped striking edge in front of them with four raised knuckle bosses, an open
window below the rings and a palm bar under it (held between the palm and the finger tips), wrapped in paracord, with a
lanyard hole in its little-finger end. The left duster is this one mirrored (knuckles_rig)."""
import numpy as np

import hands
import shapes as sh
from meshkit import Mesh
from p9_model import extrude_z

PITCH = hands.palm_width("wide") / 4          # finger pitch (15.4 mm)
RING_X = np.array([hands.knuckle_x("R", f) for f in ("index", "middle", "ring", "pinky")])
RING_Y = -hands.PALM_LEN                       # the first finger segments' centre line
HOLE_A, HOLE_B = 0.0065, 0.0078               # hole half axes (x across the fingers, y through the finger's thickness)
BAND = 0.0037                                 # ring wall in front of the holes
Z0, Z1 = 0.0152, 0.0244                       # plate faces: on the palm face .. under the curled middle segments
ZC = 0.5 * (Z0 + Z1)
FRONT_Y = RING_Y - HOLE_B - BAND              # striking edge at the ring centres
NOTCH = 0.0026                                # the edge dips this much between two rings
BOSS = 0.0032                                 # knuckle bosses stand this far proud of the edge
END_X = RING_X[-1] + HOLE_A + BAND + 0.0004   # ring ends (pinky side; the index side mirrors it)
WINDOW = (-0.0262, 0.0262, -0.0488, -0.0430)  # open window under the rings (x0, x1, y0, y1)
BAR_Y = (-0.0430, -0.0322)                    # palm bar between the window and the plate's lower edge
BAR_END = 0.0395                              # palm bar half length (its rounded ends reach past the hand)
LANYARD = np.array([0.0335, -0.0370, ZC])     # lanyard hole in the bar's little-finger end (charm mount)
LANYARD_R = 0.0021
CENTRE = np.array([0.0, RING_Y, ZC])          # duster root: the middle of the ring row (knife / knife_L node)
TIP = np.array([0.0, FRONT_Y + NOTCH, ZC])    # middle of the striking face (between the middle and ring bosses)


def _catmull(points, samples):
    """Closed Catmull-Rom curve through the control points, `samples` points per span."""
    p = np.asarray(points, float)
    n = len(p)
    out = []
    for i in range(n):
        p0, p1, p2, p3 = p[i - 1], p[i], p[(i + 1) % n], p[(i + 2) % n]
        for s in np.linspace(0, 1, samples, endpoint=False):
            s2, s3 = s * s, s * s * s
            out.append(0.5 * (2 * p1 + (-p0 + p2) * s + (2 * p0 - 5 * p1 + 4 * p2 - p3) * s2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * s3))
    return np.array(out)


def front_edge(x):
    """y of the striking edge: furthest forward over each ring, dipping between them."""
    ph = (x - RING_X[0]) / PITCH
    return FRONT_Y + NOTCH * 0.5 * (1 - np.cos(2 * np.pi * ph))


def outline():
    """CCW (x, y) outline of the plate: the scalloped edge, the rounded ring ends, the palm bar's ends and its
    gently bowed lower edge."""
    top = [(x, front_edge(x)) for x in np.linspace(END_X - 0.0035, -(END_X - 0.0035), 21)]
    side = [(-END_X, RING_Y - 0.0040), (-END_X - 0.0004, RING_Y + 0.0040), (-END_X + 0.0006, -0.0505),
            (-BAR_END + 0.0010, -0.0458), (-BAR_END - 0.0004, -0.0400), (-BAR_END + 0.0022, -0.0338),
            (-0.0300, BAR_Y[1] + 0.0004), (-0.0140, BAR_Y[1] + 0.0014), (0.0, BAR_Y[1] + 0.0018)]
    other = [(-x, y) for x, y in reversed(side[:-1])]
    ctrl = top + side + other
    # the scalloped edge is already dense; the rounded corners are splined
    pts = _catmull(ctrl, 2)
    return sh.ccw(pts)


def holes():
    rings = [sh.superellipse(HOLE_A, HOLE_B, 2.25, 20, cx=x, cy=RING_Y) for x in RING_X]
    x0, x1, y0, y1 = WINDOW
    window = sh.rounded_rect(x0, y0, x1, y1, 0.0026, 3)
    lanyard = [sh.circle(LANYARD[0], LANYARD[1], LANYARD_R, 12)]
    return [sh.cw(h) for h in rings + [window] + lanyard]


def plate_mesh():
    edge = [(0.0008 - 0.0008 * np.sin(t), 0.0008 - 0.0008 * np.cos(t)) for t in (0.0, np.pi / 4, np.pi / 2)]
    m = extrude_z(outline(), holes(), Z0, Z1, edge, "frame")
    # the polished striking face: the front edge (and its rounding) over the rings
    m.set_material(lambda c, n: c[1] < FRONT_Y + NOTCH + 0.0012 and n[1] < -0.35 and abs(c[0]) < END_X - 0.002,
                   "face")
    return m


def boss_mesh(x):
    """A raised knuckle boss on the striking edge over one ring: a rounded, slightly pointed pad."""
    y0 = front_edge(x) + 0.0010
    rings = []
    for k, (dy, s) in enumerate(((0.0, 1.0), (-0.0012, 0.98), (-0.0022, 0.88), (-0.0029, 0.66), (-0.0033, 0.38))):
        loop = sh.superellipse(0.0049 * s, 0.0040 * s, 2.4, 32)          # (x, z) footprint
        rings.append(np.stack([x + loop[:, 0], np.full(len(loop), y0 + dy - (BOSS - 0.0032)),
                               ZC + loop[:, 1]], 1))
    return sh.loft(rings, "face")


def _sweep(path, radius, sides, material):
    """Round tube along a polyline (parallel-transported frames), capped."""
    path = np.asarray(path, float)
    t = np.gradient(path, axis=0)
    t /= np.linalg.norm(t, axis=1)[:, None]
    ref = np.array([1.0, 0.0, 0.0]) if abs(t[0][0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    u = np.cross(t[0], ref)
    u /= np.linalg.norm(u)
    rings = []
    ang = np.linspace(0, 2 * np.pi, sides, endpoint=False)
    for i, p in enumerate(path):
        if i:
            u = u - t[i] * np.dot(u, t[i])
            u /= np.linalg.norm(u)
        v = np.cross(t[i], u)
        rings.append(p + radius * (np.cos(ang)[:, None] * u + np.sin(ang)[:, None] * v))
    return sh.loft(rings, material)


CORD_R = 0.00165
TURNS = 13


def wrap_mesh():
    """Paracord wound round the palm bar between the window ends: a tight helix over the bar's rounded section."""
    y0, y1 = BAR_Y
    yc, hy, hz = 0.5 * (y0 + y1), 0.5 * (y1 - y0) + CORD_R * 0.85, 0.5 * (Z1 - Z0) + CORD_R * 0.85
    x0, x1 = WINDOW[0] + 0.0012, WINDOW[1] - 0.0012
    per = 20
    n = TURNS * per
    a = np.linspace(0, 2 * np.pi * TURNS, n + 1)
    xs = np.linspace(x0, x1, n + 1)
    c, s = np.cos(a), np.sin(a)
    k = 3.0                                                           # superellipse section round the flat bar
    ry = hy * np.sign(c) * np.abs(c) ** (2 / k)
    rz = hz * np.sign(s) * np.abs(s) ** (2 / k)
    path = np.stack([xs, yc + ry, ZC + rz], 1)
    m = _sweep(path, CORD_R, 10, "wrap")
    # the cord's two ends, melted and tucked: a small bead at each end of the wrap
    bead = sh.revolve([(0.0, -CORD_R * 1.5), (CORD_R * 1.35, -CORD_R * 0.6), (CORD_R * 1.45, 0.0),
                       (CORD_R * 1.35, CORD_R * 0.6), (0.0, CORD_R * 1.5)], 14, "wrap")
    for p_ in (path[0], path[-1]):
        m.merge(bead.translated(p_))
    return m


def duster_mesh():
    """The right duster in the right hand's frame."""
    m = plate_mesh()
    for x in RING_X:
        m.merge(boss_mesh(x))
    m.merge(wrap_mesh())
    return m


def mirrored(m):
    """The left duster: the right one mirrored across the hand's X (the left hand's frame is the right's mirror)."""
    mat = np.diag([-1.0, 1.0, 1.0, 1.0])
    return m.transformed(mat)
