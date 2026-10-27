"""Detail helpers the SMG models share (VIEWMODEL_SPEC section 53 carried into moulded and pressed parts the shared
primitives do not round): moulded or pressed ribs with a round crown and ends that run down into the face instead of
a trapezoid with knife edges, rounded ends for organic lofts (grip bottoms, handguard ends) instead of a flat cap with
a hairline chamfer, and bent straps smoothed by corner cutting. Built on shapes.py / meshkit.py without changing them;
every face of a round is marked soft (meshkit.Mesh.soft) so a few facets shade as one curve."""
import numpy as np

import shapes as sh
from meshkit import newell_normal, plane_basis
from model_quality import fillet_segments


def _norm(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def rib(a, b, width, height, up, material, seg=7, sink=0.0003, end=None):
    """A rib from a to b on a face whose outward normal is `up`: a half-ellipse section `width` wide at its foot (sunk
    `sink` under the face) and `height` proud, its ends rounded down into the face over `end` (default: half the
    width). Soft shaded."""
    a, b, up = (np.asarray(v, float) for v in (a, b, up))
    d = b - a
    length = float(np.linalg.norm(d))
    d = d / max(length, 1e-12)
    up = _norm(up - np.dot(up, d) * d)
    side = _norm(np.cross(d, up))
    hw = 0.5 * width
    t = np.linspace(0.0, np.pi, seg)
    sec = [(hw * np.cos(tt), height * np.sin(tt)) for tt in t] + [(-hw, -sink), (hw, -sink)]
    sec = np.array(sec)
    r_end = min(end if end is not None else hw, 0.45 * length)
    # rings along the rib: the crown eases down over r_end at both ends (a quarter ellipse), the foot stays put
    stations = [(0.0, 0.18), (0.30 * r_end, 0.62), (r_end, 1.0), (length - r_end, 1.0), (length - 0.30 * r_end, 0.62),
                (length, 0.18)]
    rings = []
    for s, k in stations:
        o = a + s * d
        pts = [o + x * k * side + (y * k if y > 0 else y) * up for x, y in sec]
        rings.append(np.array(pts))
    m = sh.loft(rings, material, rounding=False, soft_bands=range(len(rings) - 1))
    return m


def rib_x(x_face, side, a_zy, b_zy, width, height, material, **kw):
    """`rib` on a face normal to X (side +1 right / -1 left), from (z, y) a to (z, y) b."""
    pa = (side * x_face, a_zy[1], a_zy[0])
    pb = (side * x_face, b_zy[1], b_zy[0])
    return rib(pa, pb, width, height, (side, 0.0, 0.0), material, **kw)


def smooth_open(pts, iterations=2):
    """Corner-cutting (Chaikin) smoothing of an open 2D polyline, its two ends kept (they join other parts): a bent
    strap or a moulded curve drawn with a few points comes out as a smooth bend instead of facets."""
    p = np.asarray(pts, float)
    for _ in range(iterations):
        q = [p[0]]
        for a, b in zip(p[:-1], p[1:]):
            q += [0.75 * a + 0.25 * b, 0.25 * a + 0.75 * b]
        q.append(p[-1])
        p = np.array(q)
    return p


def round_end(rings, r, at_end=True):
    """Organic loft rings (K, N, 3) with their last (or first) end rounded over radius `r`: the end ring's outline
    turned in along a quarter round that finishes on the end's plane, inset r there (the flat cap is that much smaller).
    -> (rings, soft band indices). The round fits within the end ring's distance from its neighbour."""
    rs = [np.asarray(x, float) for x in rings]
    if not at_end:
        rs = rs[::-1]
    last, prev = rs[-1], rs[-2]
    c = last.mean(0)
    # the round runs along the end ring's normal (its sections may slide sideways from ring to ring)
    axis = newell_normal(last)
    if np.dot(axis, c - prev.mean(0)) < 0:
        axis = -axis
    gap = float(np.min(np.abs((last - prev) @ axis)))
    r = min(r, 0.8 * gap)
    u, w, _ = plane_basis(axis)
    p2 = np.stack([(last - c) @ u, (last - c) @ w], 1)
    flip = sh.signed_area(p2) < 0
    base = p2[::-1] if flip else p2
    # a thin wall (a C-section round a well) cannot take the full inset: halve it until the cap stays simple
    while r > 0.0002 and not sh._inset_ok([base], r):
        r *= 0.5
    k = max(2, fillet_segments(r, np.pi / 2))
    new = []
    for j in range(k + 1):
        th = 0.5 * np.pi * j / k
        ins, dz = r * (1.0 - np.cos(th)), -r + r * np.sin(th)
        q = sh.offset_loop(base, ins) if ins > 0 else base
        q = q[::-1] if flip else q
        new.append(c + q[:, 0:1] * u + q[:, 1:2] * w + dz * axis)
    out = rs[:-1] + new
    n0 = len(rs) - 1
    soft = list(range(n0, n0 + k))
    if not at_end:
        out = out[::-1]
        soft = [len(out) - 2 - i for i in soft]
    return out, soft

