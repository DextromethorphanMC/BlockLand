"""Butterfly knife (balisong) geometry. Design frame (metres): blade points along -Z, spine +Y, edge -Y,
flats face +-X. The two pivot pins sit on the tang at z = 0: the safe-handle pin on the spine side (y = +PIN_Y),
the bite-handle pin on the edge side (y = -PIN_Y). Open: both handles run back (+Z) side by side, safe handle on
top. Each handle swings 180 deg about its own pin to close over its half of the blade."""
import numpy as np

import shapes as sh
from meshkit import Mesh
from model_quality import refine_samples
from p9_model import chamfer_bevel, round_bevel

PIN_Y = 0.0062                 # pivot pins at (0, +-PIN_Y, 0)
HANDLE_LEN = 0.130             # pin to butt
HANDLE_H = 0.0125              # each handle's height (y)
HANDLE_GAP = 0.0003            # half gap between the handles along the grip seam
LINER_X = (0.0024, 0.0058)     # liner plate spans |x| in this range; the channel is |x| < 0.0024
BLADE_T = 0.0017               # blade half thickness at the spine
TIP_Z, TIP_Y = -0.103, 0.0025
BLADE_Z0 = -0.010              # blade grind starts here (tang in front of the pins)
LATCH_PIVOT = np.array([0.0, -0.0060, 0.1255])
GRIP = np.array([0.0, 0.0030, 0.0720])     # right-hand grip point = knife root (rear half of the handles)
TIP = np.array([0.0, TIP_Y, TIP_Z])


# ----------------------------------------------------------------------------------------- blade
def spine_y(z):
    zr = np.clip((-0.068 - z) / (-0.068 - TIP_Z), 0, 1)       # clip point starts at z = -0.068
    return 0.012 - (0.012 - TIP_Y) * zr ** 1.5


def edge_y(z):
    zr = np.clip((-0.064 - z) / (-0.064 - TIP_Z), 0, 1)       # belly sweeps up from z = -0.064
    base = np.where(z > -0.016, -0.0098, -0.0115)
    return base + (TIP_Y - (-0.0115)) * zr ** 2.1


def _smooth(a, b, x):
    u = np.clip((x - a) / (b - a), 0, 1)
    return u * u * (3 - 2 * u)


def blade_mesh():
    """Ground blade: flats with a fuller, a crisp primary bevel (blade_edge) running out to the tip."""
    us = np.array([0.0, 0.04, 0.09, 0.15, 0.22, 0.30, 0.38, 0.46, 0.54, 0.62, 0.70, 0.77, 0.84, 0.90, 0.95, 0.985, 1.0])
    vs = np.array([0.0, 0.10, 0.17, 0.205, 0.27, 0.335, 0.37, 0.46, 0.55, 0.70, 0.85, 1.0])
    us, vs = refine_samples(us), refine_samples(vs)
    zs = BLADE_Z0 + (TIP_Z - BLADE_Z0) * us
    nu, nv = len(us), len(vs)
    P = np.zeros((2, nu, nv, 3))                 # side (+x, -x), u, v
    for i, (u, z) in enumerate(zip(us, zs)):
        ys, ye = spine_y(z), edge_y(z)
        T = BLADE_T * (1 - 0.55 * u ** 2)
        vb = 1 - 0.45 * _smooth(0.0, 0.07, u)          # bevel line (ricasso is unsharpened)
        for j, v in enumerate(vs):
            y = ys + (ye - ys) * v
            if v <= vb:
                t = T
            else:
                t = T + (0.00018 - T) * (v - vb) / max(1 - vb, 1e-6)
            fuller = 0.00055 * np.sin(np.pi * np.clip((v - 0.17) / 0.20, 0, 1)) * _smooth(0.05, 0.12, u) \
                * (1 - _smooth(0.52, 0.62, u))
            t = max(t - fuller, 0.00015)
            P[0, i, j] = (t, y, z)
            P[1, i, j] = (-t, y, z)
    m = Mesh()
    base = m.add_vertices(P.reshape(-1, 3))
    idx = lambda s, i, j: base + (s * nu + i) * nv + j
    for i in range(nu - 1):
        u_mid = 0.5 * (us[i] + us[i + 1])
        vb = 1 - 0.45 * _smooth(0.0, 0.07, u_mid)
        for j in range(nv - 1):
            mat = "blade_edge" if vs[j] >= vb - 1e-6 else "blade"
            m.add_face([idx(0, i, j), idx(0, i + 1, j), idx(0, i + 1, j + 1), idx(0, i, j + 1)], mat)   # +x side
            m.add_face([idx(1, i, j), idx(1, i, j + 1), idx(1, i + 1, j + 1), idx(1, i + 1, j)], mat)   # -x side
        m.add_face([idx(0, i, 0), idx(1, i, 0), idx(1, i + 1, 0), idx(0, i + 1, 0)], "blade")            # spine
        e = nv - 1
        m.add_face([idx(0, i, e), idx(0, i + 1, e), idx(1, i + 1, e), idx(1, i, e)], "blade_edge")       # edge
    m.add_face([idx(0, 0, j) for j in range(nv)] + [idx(1, 0, j) for j in reversed(range(nv))], "blade")
    sh.orient_outward(m)
    # tang with kicker (sits between the handle liners when open)
    tang = [(-0.012, 0.0120), (0.0020, 0.0120), (0.0065, 0.0104), (0.0088, 0.0060), (0.0091, 0.0),
            (0.0086, -0.0060), (0.0062, -0.0104), (0.0020, -0.0123), (-0.0030, -0.0131), (-0.0080, -0.0106),
            (-0.012, -0.0096)]
    m.merge(sh.extrude_x(tang, (), BLADE_T, chamfer_bevel(0.0004), "blade"))
    return m


# --------------------------------------------------------------------------------------- handles
def _zy_rounded(z0, z1, y0, y1, r_front=0.0045, r_back=0.0036):
    """Rounded rectangle in (z, y): front end (z0) around the pin, butt at z1."""
    pts = sh.rounded_rect(z0, y0, z1, y1, (r_front, r_back, r_back, r_front), 4)
    return pts


def _slot(z0, z1, yc, hh):
    """Stadium-shaped skeleton slot in (z, y)."""
    r = hh
    pts = []
    for a in np.linspace(np.pi / 2, 3 * np.pi / 2, 7):
        pts.append((z0 + r + r * np.cos(a), yc + r * np.sin(a)))
    for a in np.linspace(-np.pi / 2, np.pi / 2, 7):
        pts.append((z1 - r + r * np.cos(a), yc + r * np.sin(a)))
    return np.array(pts)


SLOTS = ((0.017, 0.043), (0.051, 0.077), (0.085, 0.111))


def handle_mesh(side):
    """side +1 = safe handle (spine side, y > 0), -1 = bite handle (edge side)."""
    s = side
    y_in, y_out = s * HANDLE_GAP, s * (HANDLE_GAP + HANDLE_H)
    y0, y1 = min(y_in, y_out), max(y_in, y_out)
    yc = 0.5 * (y0 + y1)
    prof = _zy_rounded(-0.0075, HANDLE_LEN, y0, y1)
    slots = [_slot(a, b, yc, 0.0021) for a, b in SLOTS]
    m = Mesh()
    hw = 0.5 * (LINER_X[1] - LINER_X[0])
    xc = 0.5 * (LINER_X[0] + LINER_X[1])
    for sx in (-1, 1):
        m.merge(sh.extrude_x(prof, slots, hw, round_bevel(0.0007, 1), "handle", x_center=sx * xc))
        inlay = _zy_rounded(0.011, 0.118, y0 + 0.0016, y1 - 0.0016, 0.0022, 0.0022)
        holes = [sh.offset_loop(sh.ccw(sl), -0.0008) for sl in slots]
        m.merge(sh.extrude_x(inlay, holes, 0.00028, chamfer_bevel(0.00012), "handle_inlay",
                             x_center=sx * (LINER_X[1] + 0.00026)))
    # spacer closing the channel along the outer edge, and the butt bridge
    ys0, ys1 = (y1 - 0.0021, y1 - 0.0002) if s > 0 else (y0 + 0.0002, y0 + 0.0021)
    m.merge(sh.box(-LINER_X[0], ys0, 0.0115, LINER_X[0], ys1, 0.1265, 0.0003, "handle"))
    # pivot pin (through the tang) with domed heads, butt screw
    py = s * PIN_Y
    m.merge(sh.revolve([(0, -0.0064), (0.0022, -0.0064), (0.0028, -0.0060), (0.0028, 0.0060),
                        (0.0022, 0.0064), (0, 0.0064)], 16, "pins", precision=True).transformed(_x_axis((0, py, 0))))
    m.merge(sh.revolve([(0, -0.0062), (0.0015, -0.0062), (0.0019, -0.0059), (0.0019, 0.0059),
                        (0.0015, 0.0062), (0, 0.0062)], 12, "pins", precision=True).transformed(_x_axis((0, yc, 0.1215))))
    # liner screws through the inlays between the skeleton slots (two a side), and a tang stop pin in each handle
    import detail_parts as dp
    head = dp.socket_screw(0.0016, "pins")
    for z in (0.5 * (SLOTS[0][1] + SLOTS[1][0]), 0.5 * (SLOTS[1][1] + SLOTS[2][0])):
        for sx in (-1, 1):
            m.merge(dp.on_surface(head, (sx * (LINER_X[1] + 0.00054), yc, z), (sx, 0, 0)))
    for sx in (-1, 1):
        m.merge(dp.on_surface(dp.rivet(0.0011, "pins"), (sx * (LINER_X[1] + 0.00054), yc, 0.0060), (sx, 0, 0)))
    return m


def _x_axis(center):
    """Matrix mapping a Z-axis lathe onto the X axis at center."""
    M = np.eye(4)
    M[:3, :3] = np.array([[0, 0, 1.0], [1.0, 0, 0], [0, 1.0, 0]])
    M[:3, 3] = center
    return M


def latch_mesh():
    """Latch bar in the bite handle's channel at the butt; latched it reaches up into the safe handle."""
    c = LATCH_PIVOT
    prof = [(c[2] - 0.0024, c[1] - 0.0020), (c[2] + 0.0024, c[1] - 0.0020), (c[2] + 0.0026, c[1] + 0.0150),
            (c[2] + 0.0072, c[1] + 0.0158), (c[2] + 0.0074, c[1] + 0.0172), (c[2] - 0.0016, c[1] + 0.0172),
            (c[2] - 0.0024, c[1] + 0.0160)]
    m = sh.extrude_x(prof, (), 0.0017, chamfer_bevel(0.0003), "latch")
    m.merge(sh.revolve([(0, -0.0060), (0.0012, -0.0060), (0.0012, 0.0060), (0, 0.0060)], 10, "pins", precision=True)
            .transformed(_x_axis(c)))
    return m


def knife_parts():
    """Design-frame meshes: blade (incl. tang), handle_safe, handle_bite, latch."""
    return {"blade": blade_mesh(), "handle_safe": handle_mesh(+1), "handle_bite": handle_mesh(-1),
            "latch": latch_mesh()}
