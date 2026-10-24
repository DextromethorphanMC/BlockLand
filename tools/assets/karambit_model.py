"""Karambit geometry (VIEWMODEL_SPEC section 34). Design frame (metres): the handle runs along Z, the blade grows from
z = 0 towards -Z and curves like a claw towards -X (the side the fingers wrap), its flats face +-Y; the finger ring is
at the other end of the handle (+Z), its hole along Y (in the blade's plane, like every karambit's). The cutting edge
is the concave (inner, -X) side, the spine the convex one. Original design: a hooked claw blade with a thick spine,
a hollow-ground inner edge and a needle point, a slim handle with three finger grooves on the finger side and a small
guard at the blade, the round steel ring with a lanyard tab on its outside.

The ring's centre is not fixed here: the rig places it on the index finger's path through the claw grip
(karambit_rig.RING_C) and hands it to karambit_parts()."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from model_quality import refine_samples

BLADE_LEN = 0.095              # along the curved centre line
BLADE_R0 = 0.085               # radius of curvature at the root (tighter towards the point: a hook)
SPINE_W = 0.0105               # half width spine side at the root
EDGE_W = 0.0095                # half width edge side at the root
SPINE_T = 0.0017               # half thickness at the spine (root)
EDGE_T = 0.00016               # half thickness at the edge
HANDLE_Z = (0.0, 0.050)        # handle from the blade root to where it runs into the ring
HANDLE_A, HANDLE_B = 0.0105, 0.0072    # handle half width (x, the blade's plane) / half thickness (y)
FINGER_Z = (0.0085, 0.0239, 0.0393)    # pinky, ring and middle finger centres along the handle (claw grip)
GRIP = np.array([0.0, 0.0, 0.0316])    # knife root: the middle of the palm along the handle
RING_R, RING_TUBE = 0.0120, 0.0032     # ring centre line radius and tube radius (hole 2 x 8.8 mm)


def _smooth(a, b, x):
    u = np.clip((x - a) / (b - a), 0, 1)
    return u * u * (3 - 2 * u)


# ----------------------------------------------------------------------------------------- blade
def centre_line(u):
    """(point, outer normal (spine side), tangent) of the blade's centre line at u (0 root .. 1 point), in the X-Z
    plane: starts along -Z, bends towards -X, tighter towards the point."""
    n = 96
    us = np.linspace(0.0, 1.0, n)
    ds = BLADE_LEN / (n - 1)
    p, ang = np.zeros(3), 0.0
    pts, angs = [p.copy()], [0.0]
    for i in range(1, n):
        k = (1.0 + 0.6 * us[i] ** 2) / BLADE_R0             # curvature grows towards the point
        ang += k * ds
        p = p + ds * np.array([-np.sin(ang), 0.0, -np.cos(ang)])
        pts.append(p.copy())
        angs.append(ang)
    pts, angs = np.array(pts), np.array(angs)
    x, z = np.interp(u, us, pts[:, 0]), np.interp(u, us, pts[:, 2])
    a = np.interp(u, us, angs)
    tangent = np.array([-np.sin(a), 0.0, -np.cos(a)])
    outer = np.array([np.cos(a), 0.0, -np.sin(a)])          # +X at the root: the convex spine side
    return np.array([x, 0.0, z]), outer, tangent


def widths(u):
    """Distance of the spine and of the edge from the centre line: the spine curls down into the point, the edge
    runs on into it (the point sits a little to the inner, edge side: a hooked claw)."""
    ws = SPINE_W * np.sqrt(max(0.0, 1 - u ** 2.6)) * (1 - 0.18 * u)
    we = EDGE_W * (1 - u) ** 0.8 * (1 + 0.25 * np.sin(np.pi * u))     # the belly of the inner edge
    return ws, we


def thickness(u, s):
    """Half thickness at u along the blade and s across it (+1 spine .. -1 edge): a full flat grind into the inner
    edge, the spine rounded."""
    T = SPINE_T * (1 - 0.55 * u) + 0.00015
    if s >= 0.85:
        return T * (0.55 + 0.45 * np.sqrt(max(0.0, (1 - s) / 0.15)))       # rounded spine
    a = (0.85 - s) / 1.85                                                  # 0 at the spine shoulder .. 1 at the edge
    if a < 0.78:
        t = T - (T - 0.32 * T) * (a / 0.78) ** 1.1
    else:
        t = 0.32 * T + (EDGE_T - 0.32 * T) * (a - 0.78) / 0.22
    return max(t, 0.00012)


def blade_mesh():
    us = np.array([0.0, 0.04, 0.10, 0.17, 0.25, 0.33, 0.41, 0.49, 0.57, 0.65, 0.72, 0.79, 0.85, 0.90, 0.945, 0.975,
                   1.0])
    ss = np.array([1.0, 0.95, 0.85, 0.60, 0.30, 0.0, -0.30, -0.55, -0.72, -0.85, -0.94, -1.0])
    us, ss = refine_samples(us), refine_samples(ss)
    nu, ns = len(us), len(ss)
    P = np.zeros((2, nu, ns, 3))
    for i, u in enumerate(us):
        c, outer, _ = centre_line(u)
        ws, we = widths(u)
        for j, s in enumerate(ss):
            d = ws * s if s >= 0 else we * s
            t = thickness(u, s) if ws + we > 1e-6 else 0.0
            pt = c + outer * d
            P[0, i, j] = pt + np.array([0.0, t, 0.0])
            P[1, i, j] = pt - np.array([0.0, t, 0.0])
    m = Mesh()
    base = m.add_vertices(P.reshape(-1, 3))
    idx = lambda side, i, j: base + (side * nu + i) * ns + j
    for i in range(nu - 1):
        for j in range(ns - 1):
            sm = 0.5 * (ss[j] + ss[j + 1])
            mat = "blade_edge" if sm < -0.80 and 0.5 * (us[i] + us[i + 1]) > 0.05 else "blade"
            m.add_face([idx(0, i, j), idx(0, i + 1, j), idx(0, i + 1, j + 1), idx(0, i, j + 1)], mat)
            m.add_face([idx(1, i, j), idx(1, i, j + 1), idx(1, i + 1, j + 1), idx(1, i + 1, j)], mat)
        m.add_face([idx(0, i, 0), idx(1, i, 0), idx(1, i + 1, 0), idx(0, i + 1, 0)], "blade")
        m.add_face([idx(0, i, ns - 1), idx(0, i + 1, ns - 1), idx(1, i + 1, ns - 1), idx(1, i, ns - 1)], "blade_edge")
    m.add_face([idx(0, 0, j) for j in range(ns)] + [idx(1, 0, j) for j in reversed(range(ns))], "blade")
    sh.orient_outward(m)
    return m


def tip_point():
    c, outer, _ = centre_line(1.0)
    return c


# ---------------------------------------------------------------------------------------- handle
def _section_scale_x(z, x_sign):
    """Half width factor of the handle at z on one side: finger grooves on the finger side (-X), the guard at the
    blade on both sides, a gentle swell under the palm on the spine side."""
    k = 1.0
    if x_sign < 0:
        for zf in FINGER_Z:
            k -= 0.11 * np.exp(-((z - zf) / 0.0050) ** 2)
        k += 0.55 * np.exp(-((z - 0.0010) / 0.0028) ** 2)          # the guard under the pinky
    else:
        k += 0.05 * np.sin(np.pi * np.clip((z - 0.004) / 0.040, 0, 1))
        k += 0.20 * np.exp(-((z - 0.0010) / 0.0030) ** 2)           # thumb-side shoulder of the guard
    return k


def handle_mesh():
    """Slim handle: superellipse sections lofted along Z, finger grooves on the finger side; it tapers where it runs
    into the ring."""
    loop = sh.superellipse(HANDLE_A, HANDLE_B, 2.4, 18)
    zs = list(np.linspace(HANDLE_Z[0], 0.044, 16)) + [0.047, HANDLE_Z[1]]
    rings = []
    for z in zs:
        end = 1 - 0.30 * _smooth(0.044, HANDLE_Z[1], z)
        pts = []
        for x, y in loop:
            pts.append((x * _section_scale_x(z, np.sign(x)) * end - 0.004 * _smooth(0.042, HANDLE_Z[1], z),
                        y * end * (0.92 if z < 0.002 else 1.0), z))
        rings.append(pts)
    return sh.loft(rings, "handle")


# ------------------------------------------------------------------------------------------ ring
def ring_mesh(centre):
    """The finger ring (a torus, hole along Y) with a lanyard tab on its outside, and the neck into the handle."""
    prof = [(RING_R + RING_TUBE * np.cos(a), RING_TUBE * 1.25 * np.sin(a))
            for a in np.linspace(0, 2 * np.pi, sh.arc_segments(RING_TUBE * 1.25, 10), endpoint=False)]
    rings = [[(r * np.cos(th), r * np.sin(th), z) for r, z in prof]
             for th in np.linspace(0, 2 * np.pi, sh.arc_segments(RING_R + RING_TUBE, 22), endpoint=False)]
    torus = sh.loft(rings + [rings[0]], "ring", cap_start=False, cap_end=False)
    sh.orient_outward(torus)                       # a closed surface: wind it outwards
    m = sh.place(torus, origin=centre, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0))
    # lanyard tab on the outer side, away from the handle (towards +Z / -X)
    out = np.array([-0.55, 0.0, 0.83])
    tab_c = np.asarray(centre, float) + out * (RING_R + RING_TUBE + 0.0015)
    tab = sh.revolve([(0.0018, -0.0018), (0.0042, -0.0018), (0.0042, 0.0018), (0.0018, 0.0018), (0.0018, -0.0018)],
                     12, "ring", precision=True)
    m.merge(sh.place(tab, origin=tab_c, x_axis=(1, 0, 0), y_axis=(0, 0, -1), z_axis=(0, 1, 0)))
    return m


def lanyard_point(centre):
    """Where a charm hangs: the inner bottom of the lanyard tab."""
    out = np.array([-0.55, 0.0, 0.83])
    return np.asarray(centre, float) + out * (RING_R + RING_TUBE + 0.0015 + 0.0018)


def karambit_parts(ring_centre):
    """Design-frame meshes per node: blade, handle, ring."""
    return {"blade": blade_mesh(), "handle": handle_mesh(), "ring": ring_mesh(ring_centre)}
