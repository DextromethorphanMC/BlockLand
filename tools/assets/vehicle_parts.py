"""Shared builders of the vehicle models (tools/vehicles/VEHICLE_SPEC.md section 3, VIEWMODEL_SPEC section 57):
rods and pipes along paths, lathes about any axis, lofted body shells from superellipse stations, wheels (tyre, rim,
hub, nuts, tread lugs), and the node bookkeeping every vehicle uses (a part is modelled in vehicle space, its node
carries a pivot and an optional rotation, the GLB writer turns both into local transforms). Not part of the shared
modelling core (shapes / meshkit): only builders of finished parts the vehicle scripts merge into their meshes."""
import numpy as np

import shapes as sh
import rifle_forms as rf
from meshkit import Mesh

X, Y, Z = np.eye(3)


def unit(v):
    v = np.asarray(v, float)
    return v / (np.linalg.norm(v) + 1e-30)


def frame_of(t):
    """Two unit vectors perpendicular to the direction t (and each other)."""
    t = unit(t)
    a = Y if abs(t[1]) < 0.9 else X
    u = unit(np.cross(t, a))
    return u, np.cross(t, u)


def tube(path, r, segs, mat, cap=True, soft=True):
    """A round rod along a polyline (radius r scalar or one per point), ends capped; parallel-transported rings so
    a bent pipe does not twist."""
    path = np.asarray(path, float)
    rr = np.full(len(path), r, float) if np.isscalar(r) else np.asarray(r, float)
    th = np.linspace(0, 2 * np.pi, segs, endpoint=False)
    t0 = unit(path[1] - path[0])
    u, v = frame_of(t0)
    rings = []
    for i, p in enumerate(path):
        t = unit(path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)])
        u = unit(u - np.dot(u, t) * t)
        v = np.cross(t, u)
        rings.append(p + rr[i] * (np.outer(np.cos(th), u) + np.outer(np.sin(th), v)))
    m = sh.loft(np.array(rings), mat, cap_start=cap, cap_end=cap, rounding=False,
                soft_cols=range(segs) if soft else ())
    return m


def bend(points, radius, steps=6):
    """A polyline with its inner corners replaced by arcs of `radius` (pipes, frame tubes, handlebars)."""
    pts = [np.asarray(p, float) for p in points]
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        a, b, c = pts[i - 1], pts[i], pts[i + 1]
        d0, d1 = unit(b - a), unit(c - b)
        ang = np.arccos(np.clip(np.dot(d0, d1), -1, 1))
        if ang < 1e-3:
            out.append(b)
            continue
        cut = min(radius * np.tan(ang / 2), 0.45 * np.linalg.norm(b - a), 0.45 * np.linalg.norm(c - b))
        p0, p1 = b - d0 * cut, b + d1 * cut
        for k in range(steps + 1):
            s = k / steps
            q = (1 - s) ** 2 * p0 + 2 * (1 - s) * s * b + s * s * p1
            out.append(q)
    out.append(pts[-1])
    return np.array(out)


def lathe(profile, segs, mat, center=(0, 0, 0), axis="x", rounding=True):
    """A (r, a) profile turned about the axis through `center`; a runs along the axis."""
    m = sh.revolve(profile, segs, mat, rounding=rounding)
    if _closed(profile):
        sh.orient_outward(m)
    if axis == "x":
        return sh.place(m, origin=center, x_axis=Y, y_axis=Z, z_axis=X)
    if axis == "y":
        return sh.place(m, origin=center, x_axis=X, y_axis=-Z, z_axis=Y)
    return sh.place(m, origin=center)


def _closed(profile):
    p = np.asarray(profile, float)
    return np.allclose(p[0], p[-1]) or (abs(p[0][0]) < 1e-9 and abs(p[-1][0]) < 1e-9)


def cyl(a, b, r, segs, mat, soft=True):
    return tube([a, b], r, segs, mat, soft=soft)


def disc_x(center, r, half, segs, mat):
    """A short cylinder along X (a puck): caps and side."""
    c = np.asarray(center, float)
    return cyl(c - X * half, c + X * half, r, segs, mat)


def shell(stations, mat, count=40, round0=0.0, round1=0.0, axis=Z, up=Y, cap=True):
    """A lofted body along `axis`: stations [(s, cx, cy, hw, top, bottom, n, flat_top, flat_bottom)] — s along the
    axis, (cx, cy) the station's offset in the (x, y) plane, the rest as rifle_forms.section (top / bottom relative to
    cy). Ends domed by quarter rounds of round0 / round1."""
    axis, up = unit(axis), unit(up)
    side = np.cross(up, axis)
    out = []
    for s, cx, cy, hw, top, bottom, n, ft, fb in stations:
        loop = rf.section(hw, top, bottom, n=n, count=count, flat_top=ft, flat_bottom=fb)
        out.append((loop, axis * s + side * cx + up * cy, side, up))
    return rf.frame_loft(out, mat, round0=round0, round1=round1, cap=cap)


def box(c, half, mat, chamfer=0.0, rounding=True):
    c, h = np.asarray(c, float), np.asarray(half, float)
    return sh.box(*(c - h), *(c + h), chamfer, mat, rounding=rounding)


def oriented_box(c, half, x_axis, y_axis, mat, chamfer=0.0, rounding=True):
    """A box of half extents `half` in the frame (x_axis, y_axis, x × y) centred at c."""
    x, y = unit(x_axis), unit(y_axis)
    z = np.cross(x, y)
    m = sh.box(-half[0], -half[1], -half[2], half[0], half[1], half[2], chamfer, mat, rounding=rounding)
    return sh.place(m, origin=c, x_axis=x, y_axis=y, z_axis=z)


def plate(outline_zy, half_width, mat, x=0.0, bevel=0.0):
    """A flat plate: a (z, y) outline extruded across X (thickness 2 half_width) at x."""
    b = ((bevel, 0.0), (0.0, bevel)) if bevel > 0 else ((0.0, 0.0),)
    return sh.extrude_x(np.asarray(outline_zy, float), half_width=half_width, bevel=b, material=mat, x_center=x)


def plate_xz(outline_xz, y0, y1, mat, chamfer=0.0):
    """A flat plate lying in the ground plane: an (x, z) outline from y0 up to y1."""
    loop = np.asarray(outline_xz, float)
    m = sh.prism_z(np.stack([loop[:, 0], -loop[:, 1]], 1), y0, y1, chamfer, mat)
    # prism_z extrudes along +Z: turn it so +Z is up (+Y) and its y is -z
    return sh.place(m, x_axis=X, y_axis=-Z, z_axis=Y)


def mirror_x(m):
    """The mesh mirrored to the other side (x -> -x); Mesh.transformed turns the faces back outwards."""
    return m.transformed(np.diag([-1.0, 1.0, 1.0, 1.0]))


def both_sides(m):
    out = Mesh()
    out.merge(m)
    out.merge(mirror_x(m))
    return out


def merged(*meshes):
    out = Mesh()
    for m in meshes:
        if m is not None:
            out.merge(m)
    return out


# --------------------------------------------------------------------------------------------- wheels
def tyre(R, width, bead_r, mat, segs=96, shoulder=0.35, bulge=0.04):
    """A tyre about X through the origin: tread of radius R and `width`, round shoulders, sidewalls down to the rim
    at bead_r. shoulder: fraction of the half width taken by the round."""
    hw = width / 2
    sh_r = shoulder * hw
    side = R - bead_r
    prof = [(bead_r, -hw * 0.82), (bead_r + side * 0.35, -hw * (1 + bulge))]
    for phi in np.linspace(0, np.pi / 2, 6):                 # the near shoulder, from the sidewall up onto the tread
        prof.append((R - sh_r + sh_r * np.sin(phi), -hw + sh_r - sh_r * np.cos(phi)))
    for phi in np.linspace(np.pi / 2, np.pi, 6):             # the far shoulder
        prof.append((R - sh_r + sh_r * np.sin(phi), hw - sh_r - sh_r * np.cos(phi)))
    prof += [(bead_r + side * 0.35, hw * (1 + bulge)), (bead_r, hw * 0.82), (bead_r, -hw * 0.82)]
    m = sh.revolve(prof, segs, mat)
    sh.orient_outward(m)
    return sh.place(m, x_axis=Y, y_axis=Z, z_axis=X)


def lugs(R, width, count, mat, rows=2, depth=0.014, length=0.055, gap=0.012, stagger=True):
    """Tread blocks standing `depth` proud of a tyre of radius R (about X): `rows` rows across the tread, every
    other row shifted half a pitch; each block a chamfered box tilted to follow the circle."""
    m = Mesh()
    hw = width / 2
    row_w = (width - (rows + 1) * gap) / rows
    for k in range(rows):
        x0 = -hw + gap + k * (row_w + gap)
        xc = x0 + row_w / 2
        off = (0.5 if (stagger and k % 2) else 0.0) * 2 * np.pi / count
        for i in range(count):
            a = off + i * 2 * np.pi / count
            radial = np.array([0.0, np.cos(a), np.sin(a)])
            tang = np.array([0.0, -np.sin(a), np.cos(a)])
            c = radial * (R + depth / 2 - 0.002) + X * xc
            m.merge(oriented_box(c, (row_w / 2, depth / 2 + 0.002, length / 2), X, radial, mat, chamfer=0.003,
                                 rounding=False))
    return m


def rim_face(r, holes, hole_r, hole_ring, half, mat, x=0.0, centre_r=0.0, rounding=True, count=72, hole_count=20):
    """A wheel's face: a disc of radius r (thickness 2 half) about X at x, `holes` round holes on a ring (vents /
    lightening holes) and an optional centre bore."""
    outer = sh.circle(0, 0, r, count)
    hl = [sh.circle(hole_ring * np.cos(a), hole_ring * np.sin(a), hole_r, hole_count)
          for a in np.linspace(0, 2 * np.pi, holes, endpoint=False)] if holes else []
    if centre_r > 0:
        hl.append(sh.circle(0, 0, centre_r, 28))
    return sh.extrude_x(outer, hl, half_width=half, material=mat, x_center=x, rounding=rounding)


def spoked_face(r, hub_r, spokes, spoke_w, half, mat, x=0.0):
    """A cast wheel's face about X: hub disc, `spokes` tapered spokes out to a ring of width 8 % of r."""
    m = Mesh()
    ring_in = r * 0.88
    prof = [(ring_in, -half), (r, -half), (r, half), (ring_in, half), (ring_in, -half)]
    m.merge(lathe(prof, 72, mat, axis="x", center=(x, 0, 0)))
    m.merge(lathe([(0.0, -half * 1.4), (hub_r, -half * 1.4), (hub_r, half * 1.4), (0.0, half * 1.4)], 36, mat,
                  axis="x", center=(x, 0, 0)))
    for i in range(spokes):
        a = i * 2 * np.pi / spokes
        d = np.array([0.0, np.cos(a), np.sin(a)])
        t = np.array([0.0, -np.sin(a), np.cos(a)])
        p0, p1 = d * hub_r * 0.8, d * ring_in * 1.01
        w0, w1 = spoke_w, spoke_w * 0.62
        loop = np.array([p0 - t * w0 / 2, p1 - t * w1 / 2, p1 + t * w1 / 2, p0 + t * w0 / 2])
        # an (z, y) outline extruded across X
        m.merge(sh.extrude_x(loop[:, [2, 1]], half_width=half * 0.8, material=mat, x_center=x))
    return m


def nuts(count, ring, r, h, mat, x, outward=1.0):
    """Wheel nuts on a ring about X, standing out of the face at x towards outward * X."""
    m = Mesh()
    for i in range(count):
        a = i * 2 * np.pi / count
        c = np.array([x, ring * np.cos(a), ring * np.sin(a)])
        m.merge(cyl(c, c + X * h * outward, r, 6, mat, soft=False))
    return m


def resample_loop(points, step):
    """A closed polyline resampled every `step` metres along its length -> (points, arc length of each)."""
    p = np.asarray(points, float)
    q = np.vstack([p, p[:1]])
    seg = np.linalg.norm(np.diff(q, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    total = s[-1]
    n = max(8, int(round(total / step)))
    ts = np.linspace(0, total, n, endpoint=False)
    out = np.stack([np.interp(ts, s, q[:, k]) for k in range(3)], 1)
    return out, ts, total


def track_band(path, width, thick, pitch, mat, v_per_metre, horn_mat=None, horn=(0.06, 0.07, 0.05)):
    """A closed track (VEHICLE_SPEC section 3, material `track` scrolling its v): a band of links following the closed
    path (in a plane x = const, the track's middle), its inner face on the path, grousers standing out every `pitch`
    and the links' gaps between them; uvs u across the band, v along it (v_per_metre units a metre). Guide horns stand
    in from the inner face in the middle of each link (horn: half width, height, half length)."""
    sub = 6
    pts, ts, total = resample_loop(path, pitch / sub)
    n = len(pts)
    cx = pts[:, 0].mean()
    centre = pts.mean(0)
    m = Mesh()
    rings, vs = [], []
    hw = width / 2
    for i in range(n):
        t = unit(pts[(i + 1) % n] - pts[i - 1])
        nrm = unit(np.cross(X, t))
        if np.dot(nrm, pts[i] - centre) < 0:
            nrm = -nrm
        ph = (i % sub) / sub
        lift = thick * (1.0 if 0.15 < ph < 0.85 else 0.55) + (0.35 * thick if 0.35 < ph < 0.65 else 0.0)
        base = pts[i]
        prof = [(-hw, 0.0), (hw, 0.0), (hw, lift * 0.75), (hw * 0.92, lift), (-hw * 0.92, lift), (-hw, lift * 0.75)]
        rings.append([np.array([cx + x, 0, 0]) + (base - X * (base[0])) + nrm * h for x, h in prof])
        vs.append(ts[i] * v_per_metre)
    K = len(rings[0])
    base_i = m.add_vertices(np.concatenate([np.array(r) for r in rings]))
    us = [-hw, hw, hw, hw * 0.92, -hw * 0.92, -hw]
    for i in range(n):
        j = (i + 1) % n
        v0, v1 = vs[i], vs[i] + total / n * v_per_metre          # the last quad runs on past the seam (v keeps growing)
        for k in range(K):
            kk = (k + 1) % K
            uv = [(us[k] * v_per_metre, v0), (us[kk] * v_per_metre, v0), (us[kk] * v_per_metre, v1),
                  (us[k] * v_per_metre, v1)]
            m.add_face([base_i + i * K + k, base_i + i * K + kk, base_i + j * K + kk, base_i + j * K + k], mat, uvs=uv)
    sh.orient_outward(m)
    if horn_mat:
        for i in range(sub // 2, n, sub):
            t = unit(pts[(i + 1) % n] - pts[i - 1])
            nrm = unit(np.cross(X, t))
            if np.dot(nrm, pts[i] - centre) < 0:
                nrm = -nrm
            c = pts[i] - nrm * horn[1] / 2
            m.merge(oriented_box(c, (horn[0], horn[1] / 2, horn[2]), X, nrm, horn_mat, 0.008, rounding=False))
    return m


# ----------------------------------------------------------------------------------------- nodes
class Rig:
    """Nodes of a vehicle in vehicle space: name -> (parent, pivot (3,), rotation 3x3 or None); meshes are added in
    vehicle space and made local by the writer."""

    def __init__(self, root="body"):
        self.nodes = {root: (None, np.zeros(3), np.eye(3))}
        self.order = [root]
        self.meshes = {}
        self.root = root

    def node(self, name, parent, pivot, rot=None):
        self.nodes[name] = (parent, np.asarray(pivot, float), np.eye(3) if rot is None else np.asarray(rot, float))
        self.order.append(name)
        return name

    def add(self, name, mesh):
        if name not in self.meshes:
            self.meshes[name] = Mesh()
        self.meshes[name].merge(mesh)

    def world(self, name):
        _, p, r = self.nodes[name]
        return p, r


def rot_x(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
