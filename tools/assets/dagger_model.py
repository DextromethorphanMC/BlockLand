"""Combat dagger geometry (VIEWMODEL_SPEC section 27). Design frame (metres): the blade points along -Z, flats face
+-X, the two edges are +-Y (double-edged, symmetric). z = 0 is the front face of the crossguard's centre; the blade
runs forward to the point at TIP_Z, the grip and pommel back along +Z. Original design: a spear-point blade with a
central ridge, a short fuller and an unsharpened ricasso, a straight steel crossguard with ball ends, a ribbed rubber
grip that swells in the middle, and a steel pommel ending in a lanyard loop."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from model_quality import refine_samples
from p9_model import round_bevel

TIP_Z = -0.170                 # point of the blade
BLADE_Z0 = 0.0020              # blade root, inside the guard
BLADE_W = 0.0145               # half width at the root
RIDGE_T = 0.0028               # half thickness at the central ridge (root)
EDGE_T = 0.00018               # half thickness at the edges
GUARD = (-0.0025, 0.0080)      # crossguard front / back face (z)
GUARD_Y = 0.0330               # crossguard half length (to the ball centres)
GRIP_Z = (0.0080, 0.0980)      # grip from the guard to the pommel
GRIP_A, GRIP_B = 0.0102, 0.0132   # grip half width (x) / half height (y) at its ends
POMMEL_Z = (0.0980, 0.1160)
LOOP_C = np.array([0.0, 0.0, 0.1210])    # lanyard loop centre (charm mount)
GRIP = np.array([0.0, 0.0, 0.0520])      # right-hand grip point = knife root (middle of the grip)
TIP = np.array([0.0, 0.0, TIP_Z])


def _smooth(a, b, x):
    u = np.clip((x - a) / (b - a), 0, 1)
    return u * u * (3 - 2 * u)


# ----------------------------------------------------------------------------------------- blade
def half_width(u):
    """Blade half width along the blade (u = 0 root .. 1 point): a gentle taper into an ogival spear point."""
    w = BLADE_W * (1 - 0.30 * u)
    if u > 0.60:
        w *= np.sqrt(max(0.0, 1 - ((u - 0.60) / 0.40) ** 2))
    return w


def thickness(u, s):
    """Half thickness at u along the blade and s across it (-1 bottom edge .. 0 ridge .. 1 top edge)."""
    T = RIDGE_T * (1 - 0.6 * u ** 1.5) + 0.0002
    a = abs(s)
    tb = 0.38 * T                              # at the line where the secondary (edge) bevel starts
    if a <= 0.80:
        t = T - (T - tb) * (a / 0.80) ** 1.15  # primary grind, very slightly hollow
    else:
        t = tb + (EDGE_T - tb) * (a - 0.80) / 0.20
    flat = 1 - _smooth(0.02, 0.09, u)          # ricasso: flat and blunt next to the guard
    t_flat = T * 0.82 if a <= 0.90 else T * 0.82 + (0.0009 - T * 0.82) * (a - 0.90) / 0.10
    t = t + (t_flat - t) * flat
    fuller = 0.0007 * np.exp(-(s / 0.13) ** 2) * _smooth(0.07, 0.12, u) * (1 - _smooth(0.36, 0.44, u))
    return max(t - fuller, 0.00012)


def blade_mesh():
    """Ground blade on a (u, s) grid, both flats; the outer fifth of each side is the edge bevel (blade_edge)."""
    us = np.array([0.0, 0.05, 0.10, 0.17, 0.24, 0.31, 0.38, 0.44, 0.52, 0.60, 0.68, 0.76, 0.83, 0.89, 0.945, 1.0])
    ss = np.array([1.0, 0.92, 0.80, 0.62, 0.40, 0.20, 0.08, 0.0, -0.08, -0.20, -0.40, -0.62, -0.80, -0.92, -1.0])
    us, ss = refine_samples(us), refine_samples(ss)
    zs = BLADE_Z0 + (TIP_Z - BLADE_Z0) * us
    nu, ns = len(us), len(ss)
    P = np.zeros((2, nu, ns, 3))
    for i, (u, z) in enumerate(zip(us, zs)):
        w = half_width(u)
        for j, s in enumerate(ss):
            t = thickness(u, s) if w > 1e-6 else 0.0
            P[0, i, j] = (t, w * s, z)
            P[1, i, j] = (-t, w * s, z)
    m = Mesh()
    base = m.add_vertices(P.reshape(-1, 3))
    idx = lambda side, i, j: base + (side * nu + i) * ns + j
    for i in range(nu - 1):
        for j in range(ns - 1):
            a = 0.5 * (abs(ss[j]) + abs(ss[j + 1]))
            mat = "blade_edge" if a > 0.80 and 0.5 * (us[i] + us[i + 1]) > 0.06 else "blade"
            m.add_face([idx(0, i, j), idx(0, i + 1, j), idx(0, i + 1, j + 1), idx(0, i, j + 1)], mat)
            m.add_face([idx(1, i, j), idx(1, i, j + 1), idx(1, i + 1, j + 1), idx(1, i + 1, j)], mat)
        # the two thin edges, each wound outwards (the top one faces +y, the bottom one -y)
        m.add_face([idx(0, i, 0), idx(1, i, 0), idx(1, i + 1, 0), idx(0, i + 1, 0)], "blade_edge")
        m.add_face([idx(0, i, ns - 1), idx(0, i + 1, ns - 1), idx(1, i + 1, ns - 1), idx(1, i, ns - 1)], "blade_edge")
    m.add_face([idx(0, 0, j) for j in range(ns)] + [idx(1, 0, j) for j in reversed(range(ns))], "blade")
    sh.orient_outward(m)
    return m


# ----------------------------------------------------------------------------------------- guard
def guard_mesh():
    """Straight crossguard (oval bar seen from the side) with a ball at each end."""
    prof = sh.rounded_rect(GUARD[0], -GUARD_Y, GUARD[1], GUARD_Y, 0.0040, 2)       # (z, y)
    m = sh.extrude_x(prof, (), 0.0062, round_bevel(0.0018, 1), "guard")
    for sy in (-1, 1):
        ball = sh.revolve([(0.0, -0.0052), (0.0030, -0.0043), (0.0045, -0.0026), (0.0052, 0.0), (0.0045, 0.0026),
                           (0.0030, 0.0043), (0.0, 0.0052)], 12, "guard", precision=True)
        m.merge(sh.place(ball, origin=(0.0, sy * (GUARD_Y + 0.0012), 0.5 * (GUARD[0] + GUARD[1])),
                         x_axis=(1, 0, 0), y_axis=(0, 0, 1), z_axis=(0, 1, 0)))
    # the blade's shoulder plate on the guard's front (a raised oval boss around the ricasso)
    boss = sh.rounded_rect(-0.0044, -0.0175, 0.0044, 0.0175, 0.0035, 3)              # (x, y)
    m.merge(sh.prism_z(boss, GUARD[0] - 0.0018, GUARD[0] + 0.0004, 0.0008, "guard"))
    return m


# ------------------------------------------------------------------------------------------ grip
RIBS = 8


def _grip_scale(z):
    """Section scale along the grip: swells in the middle, ribs over the middle part, a lip at each end."""
    u = (z - GRIP_Z[0]) / (GRIP_Z[1] - GRIP_Z[0])
    s = 1.0 + 0.07 * np.sin(np.pi * u) ** 1.5
    zone = _smooth(0.06, 0.12, u) * (1 - _smooth(0.88, 0.94, u))
    phase = (u - 0.09) * RIBS / 0.82
    s += 0.045 * zone * (0.5 + 0.5 * np.cos(2 * np.pi * phase)) ** 2
    return s


def grip_mesh():
    """Ribbed rubber grip: superellipse sections lofted along Z (three rings per rib)."""
    loop = sh.superellipse(GRIP_A, GRIP_B, 2.6, 14)
    zs = [GRIP_Z[0], GRIP_Z[0] + 0.0012, GRIP_Z[0] + 0.0040]
    u0, u1 = 0.09, 0.91
    for k in range(RIBS * 3 + 1):
        zs.append(GRIP_Z[0] + (u0 + (u1 - u0) * k / (RIBS * 3)) * (GRIP_Z[1] - GRIP_Z[0]))
    zs += [GRIP_Z[1] - 0.0040, GRIP_Z[1] - 0.0012, GRIP_Z[1]]
    rings = []
    for z in sorted(set(np.round(zs, 6))):
        k = _grip_scale(z)
        if z <= GRIP_Z[0] + 0.0013 or z >= GRIP_Z[1] - 0.0013:
            k *= 0.94                                # rounded lip where it meets guard / pommel
        rings.append(np.stack([loop[:, 0] * k, loop[:, 1] * k, np.full(len(loop), z)], 1))
    return sh.loft(rings, "handle")


# ----------------------------------------------------------------------------------------- pommel
def pommel_mesh():
    """Steel pommel cap (swells, then domes) and the lanyard loop behind it."""
    loop = sh.superellipse(GRIP_A, GRIP_B, 2.6, 16)
    z0, z1 = POMMEL_Z
    keys = [(z0, 0.98), (z0 + 0.0015, 1.06), (z0 + 0.0060, 1.10), (z0 + 0.0110, 1.02), (z0 + 0.0150, 0.80),
            (z1 - 0.0008, 0.48), (z1, 0.30)]
    rings = [np.stack([loop[:, 0] * k, loop[:, 1] * k, np.full(len(loop), z)], 1) for z, k in keys]
    m = sh.loft(rings, "pommel")
    # lanyard loop: a flat ring standing in the y-z plane (its hole runs along X), a short neck into the cap
    ring = sh.revolve([(0.0029, -0.0017), (0.0056, -0.0017), (0.0056, 0.0017), (0.0029, 0.0017), (0.0029, -0.0017)],
                      16, "pommel", precision=True)
    m.merge(sh.place(ring, origin=LOOP_C, x_axis=(0, 0, 1), y_axis=(0, 1, 0), z_axis=(1, 0, 0)))
    m.merge(sh.box(-0.0016, -0.0030, z1 - 0.0012, 0.0016, 0.0030, LOOP_C[2] - 0.0048, 0.0006, "pommel"))
    return m


def dagger_parts():
    """Design-frame meshes per node: blade, guard, handle (grip), pommel."""
    return {"blade": blade_mesh(), "guard": guard_mesh(), "handle": grip_mesh(), "pommel": pommel_mesh()}
