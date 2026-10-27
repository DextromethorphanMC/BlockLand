"""Organic handgun parts (VIEWMODEL_SPEC section 55): the pieces of a pistol the hand closes round — grips lofted from
rounded superellipse rings (palm swell, finger grooves, a flared magazine well), recessed texture panels with a step
round them, and every facet of them shaded as one smooth surface. Used by the pistols' own model modules (p9, usp,
deagle, handcannon, revolver); the shared core (shapes, meshkit) is not changed by it.

Ring coordinates are (x, s): x to the gun's right, s towards the grip's front strap (the "front" direction of the
ring's plane). A grip is a stack of rings at distances d down its axis."""
import numpy as np

import shapes as sh


def smoothstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ring(half_w, front, back, count=48, n_front=2.15, n_back=2.45, flat_sides=0.0):
    """A grip section (x, s), CCW, `count` points: superellipse halves towards the front strap (s > 0) and the back
    strap, exponents near 2 (round, as a hand wants it, not a box with rounded corners). flat_sides 0..1 flattens the
    sides a little (grip panels lie flatter than the straps)."""
    t = np.linspace(0, 2 * np.pi, count, endpoint=False) + np.pi / count
    c, s = np.cos(t), np.sin(t)
    n = np.where(s > 0, n_front, n_back)
    x = half_w * np.sign(c) * np.abs(c) ** (2 / n)
    sv = np.where(s > 0, front, back) * np.sign(s) * np.abs(s) ** (2 / n)
    if flat_sides:
        # pull the sides' bulge towards their tangent plane: |x| close to half_w over a wider band
        x = np.sign(x) * (np.abs(x) + flat_sides * (half_w - np.abs(x)) * np.abs(c) ** 6)
    return np.stack([x, sv], 1)


def finger_grooves(d, d0, d1, count, depth):
    """How deep the front strap's finger grooves cut at distance d: `count` grooves between d0 and d1, rounded ridges
    between them, nothing outside."""
    u = np.clip((d - d0) / (d1 - d0), 0, 1)
    inside = (d > d0) & (d < d1)
    return depth * (0.5 - 0.5 * np.cos(2 * np.pi * count * u)) * inside


def section_normals(sec):
    """Outward unit normals (2D) of a CCW section's points."""
    prev, nxt = np.roll(sec, 1, 0), np.roll(sec, -1, 0)
    t = nxt - prev
    n = np.stack([t[:, 1], -t[:, 0]], 1)
    return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)


def panel_mask(sections, ds, d0, d1, side=0.62, edge=0.0014, x_edge=0.10, front=False):
    """Per ring point 0..1: inside the two side panels (|x| beyond `side` of the ring's half width, d between d0 and
    d1), with `edge` metres of transition along d and `x_edge` (share of the half width) across. front: the panel
    wraps round the front strap too (one panel from side to side, the back strap left smooth)."""
    out = []
    for d, sec in zip(ds, sections):
        hw = max(np.abs(sec[:, 0]).max(), 1e-9)
        across = smoothstep(side - x_edge, side + x_edge, np.abs(sec[:, 0]) / hw)
        if front:
            sf = max(sec[:, 1].max(), 1e-9)
            across = np.maximum(across, smoothstep(0.25, 0.45, sec[:, 1] / sf))
        along = smoothstep(d0, d0 + edge, d) * (1 - smoothstep(d1 - edge, d1, d))
        out.append(across * along)
    return np.array(out)


def recess(sections, mask, depth):
    """Sections pushed in along their normals by depth × mask (a recessed panel with a sloped step round it)."""
    return [sec - depth * m[:, None] * section_normals(sec) for sec, m in zip(sections, mask)]


def refine(ds, breaks, step):
    """Ring distances: `ds` plus extra rings every `step` and a ring just inside / outside every break (panel edges,
    groove ends) so steps stay sharp."""
    out = set(np.round(np.asarray(ds, float), 6))
    lo, hi = min(ds), max(ds)
    out.update(np.round(np.arange(lo, hi, step), 6))
    for b in breaks:
        for e in (-0.0007, 0.0, 0.0007):
            if lo < b + e < hi:
                out.add(round(b + e, 6))
    return sorted(out)


def skin(rings, material, mask=None, panel_material="grip", threshold=0.5, cap_start=True, cap_end=False):
    """Loft the 3D rings (K, N, 3) as one smooth surface: every band soft-shaded (no facets on a grip), faces whose
    corners average over `threshold` of the mask take panel_material. Faces k*N + i are band k, column i (shapes.loft)."""
    rings = np.asarray(rings, float)
    K, N = rings.shape[0], rings.shape[1]
    m = sh.loft(rings, material, cap_start=cap_start, cap_end=cap_end, auto_orient=False, rounding=False,
                soft_bands=range(K - 1))
    if mask is not None:
        for k in range(K - 1):
            for i in range(N):
                avg = (mask[k, i] + mask[k, (i + 1) % N] + mask[k + 1, i] + mask[k + 1, (i + 1) % N]) / 4
                if avg > threshold:
                    m.m[k * N + i] = panel_material
    return m


def chaikin(points, iterations=3, closed=False):
    """A polyline (or closed loop) turned into a smooth curve by corner cutting (Chaikin): each pass replaces every
    corner by two points a quarter of the way along its edges. An open line keeps its end points, so a stretch of an
    outline can be smoothed and spliced back."""
    p = np.asarray(points, float)
    for _ in range(iterations):
        a = p if closed else p[:-1]
        b = np.roll(p, -1, 0) if closed else p[1:]
        q = np.empty((2 * len(a), p.shape[1]))
        q[0::2] = 0.75 * a + 0.25 * b
        q[1::2] = 0.25 * a + 0.75 * b
        p = q if closed else np.vstack([p[:1], q, p[-1:]])
    return p


def smooth_span(outline, i0, i1, iterations=3):
    """The outline with its points i0..i1 (inclusive) replaced by a Chaikin curve through them (the ends kept)."""
    o = [tuple(map(float, q)) for q in outline]
    span = chaikin(o[i0:i1 + 1], iterations)
    return o[:i0] + [tuple(q) for q in span] + o[i1 + 1:]
