"""Local shape helpers of the P90 (VIEWMODEL_SPEC section 40): outlines authored in the pixels of the reference photo
(`source/references/p90.jpg`, right side, 3.3 px per mm), filleted corners, extrusions whose half width varies over
the side profile (the moulded polymer body is fuller at the receiver and slimmer at the grip), picatinny rails and
socket-head screws. Built on shapes.py / meshkit.py without changing them."""
import numpy as np

import shapes as sh
from meshkit import Mesh, triangulate
from model_quality import arc_segments

PX = 0.5 / 1650.0                 # metres per photo pixel: butt plate (x 40) to flash hider tip (x 1690) = 500 mm
X0, Y0 = 1532.0, 393.0            # photo x of the body's front face (z = 0), photo y of the bore axis


def P(x, y):
    """Photo pixel -> design (z, y) in metres (+Z rearward, +Y up)."""
    return np.array([(X0 - x) * PX, (Y0 - y) * PX])


def pz(x):
    return (X0 - x) * PX


def py(y):
    return (Y0 - y) * PX


OUTLINE_ERR = 0.00010             # chord error of the body outlines' fillets (0.1 mm: under a pixel at 4K, section 20)


def arc_count(r, sweep, seg_min=2, err=None):
    if err is None:
        return arc_segments(r, seg_min, sweep)
    step = 2 * np.arccos(max(-1.0, 1 - min(err / max(r, 1e-9), 2.0)))
    return max(int(seg_min), int(np.ceil(abs(sweep) / step)))


def fillet(points, radii, seg_min=2, min_convex=0.0, err=OUTLINE_ERR):
    """Closed photo-pixel polygon with a fillet of radius radii[k] (pixels, 0 = sharp) at each corner -> (N, 2)
    design-frame (z, y) loop. Arcs are split within model_quality's chord error. A rounded convex corner gets at least
    `min_convex` pixels of radius: an edge bevel deeper than a corner's radius would turn its arc inside out."""
    p = np.array([P(*q) for q in points], float)
    r = np.asarray(radii, float) * PX if not np.isscalar(radii) else np.full(len(p), radii * PX)
    orient = np.sign(sh.signed_area(p))
    out = []
    n = len(p)
    for k in range(n):
        a, b, c = p[k - 1], p[k], p[(k + 1) % n]
        rk = r[k]
        turn = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        if rk > 0 and turn * orient > 0:
            rk = max(rk, min_convex * PX)
        u, v = a - b, c - b
        lu, lv = np.linalg.norm(u), np.linalg.norm(v)
        if rk <= 0 or lu < 1e-9 or lv < 1e-9:
            out.append(b)
            continue
        u, v = u / lu, v / lv
        cosang = float(np.clip(np.dot(u, v), -1.0, 1.0))
        ang = np.arccos(cosang)
        if ang > np.pi - 1e-3 or ang < 1e-3:
            out.append(b)
            continue
        t = rk / np.tan(ang / 2)
        t = min(t, 0.48 * lu, 0.48 * lv)
        rr = t * np.tan(ang / 2)
        p0, p1 = b + u * t, b + v * t
        bis = (u + v) / np.linalg.norm(u + v)
        centre = b + bis * rr / np.sin(ang / 2)
        a0 = np.arctan2(*(p0 - centre)[::-1])
        a1 = np.arctan2(*(p1 - centre)[::-1])
        sweep = (a1 - a0 + np.pi) % (2 * np.pi) - np.pi
        seg = arc_count(rr, abs(sweep), seg_min, err)
        for s in np.linspace(0.0, 1.0, seg + 1):
            ang_s = a0 + s * sweep
            out.append(centre + rr * np.array([np.cos(ang_s), np.sin(ang_s)]))
    out = np.array(out)
    keep = [0] + [i for i in range(1, len(out)) if np.linalg.norm(out[i] - out[i - 1]) > 2e-5]
    out = out[keep]
    if np.linalg.norm(out[0] - out[-1]) <= 2e-5:
        out = out[:-1]
    return out


def bevel(r, steps=None, err=OUTLINE_ERR):
    """Quarter-round edge as extrude rings (dx from the face, inset), from the middle outwards."""
    steps = steps or arc_count(r, np.pi / 2, 2, err)
    th = np.linspace(0, np.pi / 2, steps + 1)
    return [(r - r * np.sin(t), r - r * np.cos(t)) for t in th]


def extrude_var(outer, holes=(), hw=0.01, edge=((0.0, 0.0),), material="default", x_center=0.0):
    """Like shapes.extrude_x, but the half width may be a function hw(z, y) evaluated at every ring vertex: a side
    profile (z, y) with rounded edges whose sides swell or slim across the outline. Caps are triangulated in (z, y)."""
    f = hw if callable(hw) else (lambda z, y: hw)
    outer = sh.ccw(outer)
    holes = [sh.cw(h) for h in holes]
    rings = sorted(edge, key=lambda q: -q[0])
    seq = [(-1.0, dx, ins) for dx, ins in rings[::-1]] + [(1.0, dx, ins) for dx, ins in rings]
    mesh = Mesh()
    secs_per_loop = []
    for loop in [outer] + list(holes):
        secs = []
        for s, dx, ins in seq:
            o = sh.offset_loop(loop, ins) if ins else np.asarray(loop, float)
            w = np.array([f(z, y) for z, y in o])
            secs.append(np.stack([x_center + s * (w - dx), o[:, 1], o[:, 0]], 1))
        secs_per_loop.append(secs)
        sm = np.asarray(secs)
        K, N = sm.shape[:2]
        base = mesh.add_vertices(sm.reshape(-1, 3))
        for k in range(K - 1):
            for i in range(N):
                a, b = base + k * N + i, base + k * N + (i + 1) % N
                c, d = base + (k + 1) * N + (i + 1) % N, base + (k + 1) * N + i
                mesh.add_face([a, d, c, b], material)
    for end, flip in ((0, False), (len(seq) - 1, True)):
        o2 = [np.stack([s[end][:, 2], s[end][:, 1]], 1) for s in secs_per_loop]
        base = mesh.add_vertices(np.concatenate([s[end] for s in secs_per_loop]))
        for t in triangulate(o2[0], o2[1:]):
            mesh.add_face([base + i for i in (t[::-1] if flip else t)], material)
    return sh.orient_outward(mesh)


def smooth(x, e0, e1):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# ------------------------------------------------------------------------------------------- hardware
def picatinny(z0, z1, material="steel", base_w=0.0088, base_h=0.0034, pitch=0.010, slot=0.0052, teeth=None):
    """MIL-STD-1913 rail along -Z on y = 0 (teeth up): a base strip and dovetail teeth (21.2 mm over the flats, 45 deg
    undercut) a pitch apart with recoil slots between them (within `teeth` = (za, zb) when given: plain end blocks for
    the screws); the rail top at y = base_h + 0.0049."""
    m = sh.box(-base_w, -0.0010, z0, base_w, base_h, z1, 0.0006, material)
    if teeth is not None:
        z0, z1 = teeth
    top = base_h + 0.0049
    tooth = np.array([(-0.0080, base_h - 0.0002), (0.0080, base_h - 0.0002), (0.0106, base_h + 0.0024),
                      (0.0106, top - 0.0010), (0.0096, top), (-0.0096, top), (-0.0106, top - 0.0010),
                      (-0.0106, base_h + 0.0024)])
    from p9_model import extrude_z
    count = max(1, int((z1 - z0 - 0.0020) // pitch))
    start = 0.5 * (z0 + z1) - 0.5 * (count * pitch - slot)
    for k in range(count):
        a = start + k * pitch
        m.merge(extrude_z(tooth, (), a, a + pitch - slot, ((0.0, 0.0),), material))
    return m


def screw(centre, normal, r=0.0024, material="steel_dark", seg=8, socket=True):
    """Socket-head screw: a low domed head with a hex socket sunk in it, axis along `normal` (the head stands 0.6 mm
    proud of `centre`, its shank 1.2 mm into the part)."""
    prof = [(r * 0.72, 0.0006), (r, 0.0001), (r, -0.0008)]
    prof = ([(0.0, 0.0002), (r * 0.42, 0.0002)] if socket else [(0.0, 0.0006)]) + prof
    m = sh.revolve(prof, seg, material, start_angle=np.pi / seg)
    n = np.asarray(normal, float) / np.linalg.norm(normal)
    u, w, _ = __import__("meshkit").plane_basis(n)
    return sh.place(m, origin=centre, x_axis=u, y_axis=w, z_axis=n)


def cyl_z(r, z0, z1, material, seg=16, x=0.0, y=0.0, chamfer=0.0, precision=False):
    c = min(chamfer, 0.45 * abs(z1 - z0))
    if c > 0:
        prof = [(0.0, z0), (r - c, z0), (r, z0 + c), (r, z1 - c), (r - c, z1), (0.0, z1)]
    else:
        prof = [(0.0, z0), (r, z0), (r, z1), (0.0, z1)]
    return sh.revolve(prof, seg, material, precision=precision).translated((x, y, 0.0))


def cyl_x(r, x0, x1, centre_yz, material, seg=12, chamfer=0.0):
    """Cylinder along X from x0 to x1 through (y, z) = centre_yz."""
    c = min(chamfer, 0.45 * abs(x1 - x0))
    if c > 0:
        prof = [(0.0, x0), (r - c, x0), (r, x0 + c), (r, x1 - c), (r - c, x1), (0.0, x1)]
    else:
        prof = [(0.0, x0), (r, x0), (r, x1), (0.0, x1)]
    m = sh.revolve(prof, seg, material)
    m = sh.place(m, x_axis=(0, 1, 0), y_axis=(0, 0, 1), z_axis=(1, 0, 0))
    return m.translated((0.0, centre_yz[0], centre_yz[1]))
