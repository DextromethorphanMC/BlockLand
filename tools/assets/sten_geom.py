"""Local geometry helpers of the STEN Mk II (sten_model.py): a rolled steel tube with windows really cut through its
wall (magazine opening, ejection port, the L-shaped cocking slot), a perforated barrel sleeve with drilled holes, a
straight tube between two points and polygons wound towards a wanted normal. Every builder returns a meshkit.Mesh
whose faces share vertices (one surface per part, consistent edges for check_orient)."""
import numpy as np

import shapes as sh
from meshkit import Mesh, newell_normal, triangulate
from model_quality import arc_segments


class Pool:
    """Vertex pool welding equal positions, so faces built cell by cell share their edges."""

    def __init__(self, mesh):
        self.m, self.keys = mesh, {}

    def __call__(self, p):
        k = tuple(np.round(np.asarray(p, float), 9))
        i = self.keys.get(k)
        if i is None:
            i = self.m.add_vertices([p])
            self.keys[k] = i
        return i


def face(m, idx, mat, want):
    """Adds a polygon (vertex indices) wound so its normal points along `want`."""
    pts = np.array([m.v[i] for i in idx])
    if np.dot(newell_normal(pts), want) < 0:
        idx = list(idx)[::-1]
    m.add_face(idx, mat)


def poly(m, pts, mat, want):
    pts = np.asarray(pts, float)
    if np.dot(newell_normal(pts), want) < 0:
        pts = pts[::-1]
    m.add_poly(pts, mat)


def _dedupe(vals, eps):
    out = []
    for v in sorted(vals):
        if not out or v - out[-1] > eps:
            out.append(v)
    return out


def windowed_tube(r_out, r_in, z0, z1, windows, material, theta0=-np.pi / 2, seg=None, z_extra=(),
                  wall_material=None):
    """Tube along Z (axis x = y = 0) from z0 to z1 with rectangular windows in (theta, z) cut through its wall:
    windows = [(theta_a, theta_b, z_a, z_b), ...], theta measured from +X towards +Y within [theta0, theta0 + 2 pi).
    A window is any union of grid cells, so an L-shaped slot is two rectangles. The cut walls face into the opening,
    the bore faces inwards, both ends are closed by annuli."""
    seg = seg or arc_segments(r_out, 16)
    ths = list(theta0 + 2 * np.pi * np.arange(seg) / seg)
    edges = [w[0] for w in windows] + [w[1] for w in windows]
    ths = [t for t in ths if all(abs(t - e) > 0.35 * np.pi / seg for e in edges)] + edges
    ths = _dedupe(ths, 1e-7)
    zs = _dedupe([z0, z1] + [w[2] for w in windows] + [w[3] for w in windows] + list(z_extra), 1e-7)
    nt, nz = len(ths), len(zs)
    tc = lambda i: ths[i] if i < nt else ths[i - nt] + 2 * np.pi

    def cut(i, j):
        if j < 0 or j >= nz - 1:
            return False
        a, b = tc(i % nt), tc(i % nt + 1)
        if b < a:
            b += 2 * np.pi
        t, z = 0.5 * (a + b), 0.5 * (zs[j] + zs[j + 1])
        for wa, wb, za, zb in windows:
            if wa <= t <= wb and za <= z <= zb:
                return True
        return False

    m = Mesh()
    P = Pool(m)
    out = lambda i, j: P((r_out * np.cos(ths[i % nt]), r_out * np.sin(ths[i % nt]), zs[j]))
    inn = lambda i, j: P((r_in * np.cos(ths[i % nt]), r_in * np.sin(ths[i % nt]), zs[j]))
    wm = wall_material or material
    for j in range(nz - 1):
        for i in range(nt):
            if cut(i, j):
                continue
            tm = 0.5 * (tc(i) + tc(i + 1))
            radial = np.array([np.cos(tm), np.sin(tm), 0.0])
            face(m, [out(i, j), out(i + 1, j), out(i + 1, j + 1), out(i, j + 1)], material, radial)
            face(m, [inn(i, j), inn(i + 1, j), inn(i + 1, j + 1), inn(i, j + 1)], material, -radial)
            # walls towards a cut neighbour
            if cut(i - 1, j):           # neighbour on the low-theta side: wall at ths[i]
                t = ths[i]
                face(m, [out(i, j), out(i, j + 1), inn(i, j + 1), inn(i, j)], wm,
                     np.array([np.sin(t), -np.cos(t), 0.0]))
            if cut(i + 1, j):
                t = tc(i + 1)
                face(m, [out(i + 1, j), out(i + 1, j + 1), inn(i + 1, j + 1), inn(i + 1, j)], wm,
                     np.array([-np.sin(t), np.cos(t), 0.0]))
            if cut(i, j - 1):
                face(m, [out(i, j), out(i + 1, j), inn(i + 1, j), inn(i, j)], wm, np.array([0, 0, -1.0]))
            if cut(i, j + 1):
                face(m, [out(i, j + 1), out(i + 1, j + 1), inn(i + 1, j + 1), inn(i, j + 1)], wm,
                     np.array([0, 0, 1.0]))
    for j, want in ((0, -1.0), (nz - 1, 1.0)):
        for i in range(nt):
            face(m, [out(i, j), out(i + 1, j), inn(i + 1, j), inn(i, j)], material, np.array([0, 0, want]))
    return m


def perforated_sleeve(r_out, t, z0, z1, rows, per_row, hole_r, material, end=0.006, m_side=8, k_side=2):
    """Barrel sleeve: a tube from z0 to z1 with `rows` staggered rows of `per_row` drilled radial holes between two
    plain bands of `end` length. Each hole cell (its boundary sampled m_side times along the circumference and k_side
    times along Z, shared with its neighbours) is triangulated flat round its round hole and wrapped onto the outer and
    the inner skin, the two joined by the bore of the hole (facing into it)."""
    r_in = r_out - t
    m = Mesh()
    P = Pool(m)
    dth = 2 * np.pi / per_row
    n_ring = per_row * m_side
    cyl = lambda r, th, z: (r * np.cos(th), r * np.sin(th), z)
    hz = (z1 - z0 - 2 * end) / rows
    zr = [z0 + end + k * hz for k in range(rows + 1)]
    for r in range(rows):
        za, zb = zr[r], zr[r + 1]
        phase = (r % 2) * dth / 2
        for c in range(per_row):
            ta = phase + c * dth
            bnd = []                                      # boundary (theta, z), counter-clockwise in (theta, z)
            for s in range(m_side):
                bnd.append((ta + dth * s / m_side, za))
            for s in range(k_side):
                bnd.append((ta + dth, za + hz * s / k_side))
            for s in range(m_side):
                bnd.append((ta + dth - dth * s / m_side, zb))
            for s in range(k_side):
                bnd.append((ta, zb - hz * s / k_side))
            tcen, zcen = ta + dth / 2, 0.5 * (za + zb)
            nh = arc_segments(hole_r, 16)
            angs = 2 * np.pi * np.arange(nh) / nh + np.pi / nh
            hole2 = [(hole_r * np.cos(a), zcen + hole_r * np.sin(a)) for a in angs]
            bnd2 = [((th - tcen) * r_out, z) for th, z in bnd]
            # the cell's skin: the square boundary round the hole, triangulated flat in (arc length, z) and wrapped
            # onto the skin (the hole drilled radially: the same width at both skins). The outer skin gets a ring
            # round the hole first (lofted to it), so no long flat triangle cuts across the curve.
            ru, rz = 0.86 * 0.5 * dth * r_out, 0.86 * 0.5 * hz          # an ellipse filling the cell
            mid2 = [(ru * np.cos(a), zcen + rz * np.sin(a)) for a in angs]
            hole_pt = lambda rr: [P(cyl(rr, tcen + np.arcsin(np.clip(u / rr, -1, 1)), z)) for u, z in hole2]
            # outer skin
            ids = [P(cyl(r_out, th, z)) for th, z in bnd] + [P(cyl(r_out, tcen + u / r_out, z)) for u, z in mid2]
            for tri in triangulate(np.array(bnd2), [np.array(mid2)]):
                cc = np.mean([m.v[ids[k]] for k in tri], axis=0)
                face(m, [ids[k] for k in tri], material, np.array([cc[0], cc[1], 0.0]))
            M = ids[len(bnd):]
            Ho = hole_pt(r_out)
            for i in range(nh):
                j = (i + 1) % nh
                cc = np.mean([m.v[k] for k in (M[i], M[j], Ho[j], Ho[i])], axis=0)
                face(m, [M[i], M[j], Ho[j], Ho[i]], material, np.array([cc[0], cc[1], 0.0]))
            # inner skin (seen only through the holes)
            ids = [P(cyl(r_in, th, z)) for th, z in bnd] + hole_pt(r_in)
            for tri in triangulate(np.array(bnd2), [np.array(hole2)]):
                cc = np.mean([m.v[ids[k]] for k in tri], axis=0)
                face(m, [ids[k] for k in tri], material, -np.array([cc[0], cc[1], 0.0]))
            Hi = ids[len(bnd):]
            for i in range(nh):
                j = (i + 1) % nh
                a = 0.5 * (angs[i] + angs[j]) + (np.pi if j == 0 else 0.0)
                th = tcen + hole_r * np.cos(a) / r_out
                tang = np.array([-np.sin(th), np.cos(th), 0.0])
                want = -(np.cos(a) * tang + np.sin(a) * np.array([0, 0, 1.0]))
                face(m, [Ho[i], Ho[j], Hi[j], Hi[i]], material, want)
    ring_th = [k * 2 * np.pi / n_ring for k in range(n_ring)]
    for za, zb in ((z0, zr[0]), (zr[-1], z1)):
        for rr, sgn in ((r_out, 1.0), (r_in, -1.0)):
            for k in range(n_ring):
                ta, tb = ring_th[k], ring_th[(k + 1) % n_ring] + (2 * np.pi if k == n_ring - 1 else 0.0)
                tm = 0.5 * (ta + tb)
                face(m, [P(cyl(rr, ta, za)), P(cyl(rr, tb, za)), P(cyl(rr, tb, zb)), P(cyl(rr, ta, zb))], material,
                     sgn * np.array([np.cos(tm), np.sin(tm), 0.0]))
    for z, want in ((z0, -1.0), (z1, 1.0)):
        for k in range(n_ring):
            ta, tb = ring_th[k], ring_th[(k + 1) % n_ring]
            face(m, [P(cyl(r_out, ta, z)), P(cyl(r_out, tb, z)), P(cyl(r_in, tb, z)), P(cyl(r_in, ta, z))], material,
                 np.array([0, 0, want]))
    return m


def tube_between(a, b, r, material, seg=None, chamfer=0.0):
    """Closed cylinder of radius r from point a to point b (round, within the curve error)."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    d = b - a
    L = float(np.linalg.norm(d))
    c = min(chamfer, 0.45 * L)
    prof = [(0.0, 0.0), (r - c, 0.0), (r, c), (r, L - c), (r - c, L), (0.0, L)]
    mesh = sh.revolve(prof, seg or 16, material, precision=True)
    z = d / L
    x = np.cross((0, 1.0, 0), z) if abs(z[1]) < 0.9 else np.cross((1.0, 0, 0), z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return sh.place(mesh, origin=a, x_axis=x, y_axis=y, z_axis=z)


def lathe(profile, material, seg=16, origin=(0, 0, 0), axis="z"):
    """Revolve an (r, along) profile round Z (or X / Y: `along` runs along that axis); a profile that does not start
    and end on the axis is closed (a ring)."""
    profile = [tuple(p) for p in profile]
    if profile[0][0] > 0 and profile[-1][0] > 0:
        profile = profile + [profile[0]]
    mesh = sh.revolve(profile, seg, material, precision=True)
    if axis == "x":
        mesh = sh.place(mesh, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    elif axis == "y":
        mesh = sh.place(mesh, x_axis=(0, 0, 1), y_axis=(1, 0, 0), z_axis=(0, 1, 0))
    return mesh.translated(origin)


def weld_bead(points, r, material, seg=6):
    """A weld bead along a polyline: a slim round sausage (closed) with flat caps."""
    pts = np.asarray(points, float)
    m = Mesh()
    rings = []
    for k, p in enumerate(pts):
        d = pts[min(k + 1, len(pts) - 1)] - pts[max(k - 1, 0)]
        d /= np.linalg.norm(d)
        x = np.cross(d, (0, 1.0, 0)) if abs(d[1]) < 0.9 else np.cross(d, (1.0, 0, 0))
        x /= np.linalg.norm(x)
        y = np.cross(d, x)
        ang = 2 * np.pi * np.arange(seg) / seg
        rings.append(p + r * (np.cos(ang)[:, None] * x + np.sin(ang)[:, None] * y))
    return sh.loft(rings, material)
