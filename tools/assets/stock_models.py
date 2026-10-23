"""More stock attachments (VIEWMODEL_SPEC section 28): `heavy_stock` and `wire_stock`.

Same frame as every attachment (attach_models.py): origin = the mount point at the rear of the receiver, +Y up,
+Z rearward (the stock runs back towards the shoulder). Materials as the tactical stock: `attachment` (region stock),
`steel_dark` (stock), `stock_pad` (none). attach_models.MODELS picks the builders up from MODELS below."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from p9_model import chamfer_bevel, extrude_z, round_bevel


def _attach():
    import attach_models as am          # imported late: attach_models registers these builders when it loads
    return am


def _smooth_path(points, per_segment=4):
    """Catmull-Rom through `points` (N, 3): a rod's centre line with rounded bends."""
    p = np.asarray(points, float)
    ext = np.vstack([2 * p[0] - p[1], p, 2 * p[-1] - p[-2]])
    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for s in np.linspace(0, 1, per_segment, endpoint=False):
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * s + (2 * p0 - 5 * p1 + 4 * p2 - p3) * s * s
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * s ** 3))
    out.append(p[-1])
    return np.array(out)


def rod(points, r, material, seg=8, smooth=True):
    """A round bar swept along a polyline (bends rounded), capped at both ends; rings follow a parallel frame."""
    path = _smooth_path(points) if smooth else np.asarray(points, float)
    tangents = np.gradient(path, axis=0)
    tangents /= np.linalg.norm(tangents, axis=1, keepdims=True)
    ref = np.array([1.0, 0.0, 0.0]) if abs(tangents[0][0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    n = np.cross(tangents[0], ref)
    n /= np.linalg.norm(n)
    rings = []
    t = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    for k, (c, tan) in enumerate(zip(path, tangents)):
        n = n - tan * np.dot(n, tan)                 # parallel transport of the ring frame
        n /= np.linalg.norm(n)
        b = np.cross(tan, n)
        rings.append(c + r * (np.cos(t)[:, None] * n + np.sin(t)[:, None] * b))
    return sh.loft(rings, material)


def heavy_stock():
    """Solid adjustable stock: receiver end plate and castle nut, a buffer tube, the stock body sliding on it with a
    raised cheek riser, the adjustment lever underneath, a QD sling socket and a thick 25 mm rubber pad; extends
    rearward about 0.27 m."""
    am = _attach()
    p = am.Part()
    m = sh.box(-0.0140, -0.0260, -0.0010, 0.0140, 0.0100, 0.0170, 0.0020, "attachment")       # end plate / adapter
    m.merge(am._cyl_axis(0.0170, 0.0080, (0.0, -0.0070, 0.0210), "z", "steel_dark", 16, 0.0010))   # castle nut
    m.merge(am._cyl_axis(0.0145, 0.1900, (0.0, -0.0070, 0.1150), "z", "attachment", 18, 0.0008))   # buffer tube
    # stock body (side profile in z, y), wide and rounded
    body = np.array([(0.0850, 0.0110), (0.1050, 0.0170), (0.2300, 0.0170), (0.2450, 0.0140), (0.2450, -0.1100),
                     (0.2380, -0.1180), (0.2150, -0.1130), (0.1850, -0.0900), (0.1500, -0.0560), (0.1200, -0.0380),
                     (0.0850, -0.0280)])
    m.merge(sh.extrude_x(body, (), 0.0200, round_bevel(0.0030, 2), "attachment"))
    # side recess panels (a moulded lightening pocket each side)
    pocket = np.array([(0.1450, -0.0200), (0.2250, -0.0200), (0.2250, -0.0900), (0.2050, -0.0940), (0.1750, -0.0700)])
    for sx in (-1, 1):
        m.merge(sh.extrude_x(pocket, (), 0.0010, chamfer_bevel(0.0005), "steel_dark", x_center=sx * 0.0192))
    # cheek riser on top (narrower than the body, rounded)
    cheek = sh.rounded_rect(-0.0155, 0.0150, 0.0155, 0.0280, (0.0, 0.0, 0.0060, 0.0060), 3)
    m.merge(extrude_z(cheek, (), 0.1200, 0.2280, round_bevel(0.0025, 2), "attachment"))
    # riser adjustment screws and the lever under the front of the body
    for z in (0.1450, 0.2050):
        m.merge(am._cyl_axis(0.0026, 0.0030, (0.0, 0.0290, z), "y", "steel_dark", 10, 0.0004))
    m.merge(sh.box(-0.0045, -0.0360, 0.0880, 0.0045, -0.0270, 0.1250, 0.0015, "steel_dark"))
    # QD sling socket (left side) and butt screws
    m.merge(am._cyl_axis(0.0055, 0.0060, (-0.0215, -0.0850, 0.2150), "x", "steel_dark", 12, 0.0006))
    # 25 mm rubber recoil pad
    pad = sh.rounded_rect(-0.0215, -0.1200, 0.0215, 0.0180, 0.0100, 3)
    m.merge(extrude_z(pad, (), 0.2450, 0.2700, round_bevel(0.0045, 2), "stock_pad"))
    p.meshes["attachment"] = m
    return p


def wire_stock():
    """Minimalist folding wire stock: a small hinge block, two bent steel rods (top and bottom) running back to a
    curved butt plate with a thin rubber pad; extends rearward about 0.22 m."""
    am = _attach()
    p = am.Part()
    m = sh.box(-0.0110, -0.0280, -0.0010, 0.0110, 0.0080, 0.0200, 0.0018, "attachment")       # hinge block
    m.merge(am._cyl_axis(0.0040, 0.0240, (0.0, -0.0100, 0.0140), "x", "steel_dark", 12))      # hinge pin
    for sx in (-1, 1):
        x = sx * 0.0068
        # top rod: level from the hinge, then slopes a little down to the plate's top
        m.merge(rod([(x, 0.0020, 0.0150), (x, 0.0010, 0.0700), (x * 1.6, -0.0040, 0.1500), (x * 2.0, -0.0080, 0.2050)],
                    0.0030, "steel_dark"))
        # bottom rod: drops from the hinge to the plate's toe
        m.merge(rod([(x, -0.0220, 0.0150), (x, -0.0300, 0.0600), (x * 1.6, -0.0620, 0.1500), (x * 2.0, -0.0860, 0.2050)],
                    0.0030, "steel_dark"))
    # cross brace joining the two bottom rods (at their middle control point)
    m.merge(am._cyl_axis(0.0024, 0.0250, (0.0, -0.0620, 0.1500), "x", "steel_dark", 10))
    # butt plate: a curved steel plate (shallow arc in side view), and its pad
    plate = np.array([(0.2030, 0.0050), (0.2130, 0.0040), (0.2160, -0.0400), (0.2130, -0.0960), (0.2030, -0.0950),
                      (0.2060, -0.0400)])
    m.merge(sh.extrude_x(plate, (), 0.0175, round_bevel(0.0020, 2), "attachment"))
    pad = np.array([(0.2160, 0.0030), (0.2230, 0.0010), (0.2260, -0.0400), (0.2230, -0.0930), (0.2160, -0.0950),
                    (0.2185, -0.0400)])
    m.merge(sh.extrude_x(pad, (), 0.0170, round_bevel(0.0020, 2), "stock_pad"))
    p.meshes["attachment"] = m
    return p


# model -> builder (section 28); attach_models.MODELS includes these
MODELS = {"heavy_stock": heavy_stock, "wire_stock": wire_stock}
