"""Tumbleweed models (风滚草, RENDERING_SPEC section 29): `models/ambient/tumbleweed_<i>.glb`, a few seeded variants of
the same plant. A dried Russian-thistle ball: stiff main stems shoot out of the root crown and curl round the shell,
crossing each other into an airy, slightly flattened tangle; short side twigs bristle off them. Every stem is a tapered
tube with smooth facets (thin, so few sides), in three straw / weathered grey-brown tones. Unit-ish size: radius 0.5 m
around the origin (the runtime scales each one); one node `tumbleweed`.

python -I tools/assets/gen_tumbleweed.py"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import meshkit as mk  # noqa: E402
from glb import GLB  # noqa: E402
from meshkit import Mesh  # noqa: E402

OUT = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "models", "ambient")
RADIUS = 0.5
VARIANTS = 3
# tube cross-sections: a stem is 2–11 mm across, a twig under 6 mm — at the distances they are seen from, more sides only
# cost triangles (RENDERING_SPEC section 29: 15–20k per ball before, 9–12k now)
STEM_SIDES, TWIG_SIDES = 4, 3
# name: (base rgba (linear), roughness)
MATERIALS = {
    "stem": ((0.42, 0.30, 0.16, 1), 0.88),
    "stem_grey": ((0.30, 0.26, 0.21, 1), 0.92),
    "twig": ((0.55, 0.42, 0.25, 1), 0.85),
}


def _frame(t):
    """Two unit vectors across the direction t."""
    t = t / np.linalg.norm(t)
    a = np.array([0.0, 1.0, 0.0]) if abs(t[1]) < 0.9 else np.array([1.0, 0.0, 0.0])
    n = np.cross(t, a)
    n /= np.linalg.norm(n)
    return n, np.cross(t, n)


def tapered_tube(points, radii, sides, material, base_cap=True):
    """A tube along `points` with a radius per point, rings turned with parallel transport (no twisting); the tip is
    closed by a small cone so no hole shows, the base too unless it sits inside something (`base_cap`)."""
    pts = np.asarray(points, float)
    m = Mesh()
    rings = []
    n_prev = None
    for i, p in enumerate(pts):
        t = pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]
        t /= np.linalg.norm(t) + 1e-12
        if n_prev is None:
            n, b = _frame(t)
        else:
            n = n_prev - t * np.dot(n_prev, t)
            n /= np.linalg.norm(n) + 1e-12
            b = np.cross(t, n)
        n_prev = n
        ring = [p + radii[i] * (np.cos(a) * n + np.sin(a) * b) for a in np.linspace(0, 2 * np.pi, sides, endpoint=False)]
        rings.append(m.add_vertices(ring))
    for i in range(len(rings) - 1):
        a, b = rings[i], rings[i + 1]
        for k in range(sides):
            k1 = (k + 1) % sides
            m.add_face((a + k, a + k1, b + k1, b + k), material, soft=True)
    # tips: a point a little past each end
    ends = ((pts[0], rings[0], pts[0] - pts[1]), (pts[-1], rings[-1], pts[-1] - pts[-2]))
    for end, ring, d in ends if base_cap else ends[1:]:
        d = d / (np.linalg.norm(d) + 1e-12)
        tip = m.add_vertices([end + d * radii[0 if ring == rings[0] else -1] * 1.5])
        for k in range(sides):
            k1 = (k + 1) % sides
            face = (ring + k1, ring + k, tip) if ring == rings[0] else (ring + k, ring + k1, tip)
            m.add_face(face, material, soft=True)
    return m


def _rotate(v, axis, angle):
    axis = axis / np.linalg.norm(axis)
    return v * np.cos(angle) + np.cross(axis, v) * np.sin(angle) + axis * np.dot(axis, v) * (1 - np.cos(angle))


def _unit(rng):
    v = rng.normal(size=3)
    return v / np.linalg.norm(v)


def build(seed):
    rng = np.random.default_rng(1700 + seed)
    m = Mesh()
    squash = np.array([1.0, 0.82 + 0.06 * rng.random(), 1.0])
    crown = np.array([0.0, -RADIUS * 0.55, 0.0]) * squash
    stems = 44 + 6 * seed
    for s in range(stems):
        # a main stem: out of the crown, then round the shell along a tilted great circle with a little wander
        axis = _unit(rng)
        start = _unit(rng)
        start -= axis * np.dot(start, axis)
        start /= np.linalg.norm(start)
        span = rng.uniform(1.6, 3.4)
        reach = RADIUS * rng.uniform(0.82, 1.0)
        wobble = rng.uniform(0.02, 0.06) * RADIUS
        phase = rng.uniform(0, 6.3, size=3)
        count = 14
        pts, radii = [], []
        for i in range(count):
            t = i / (count - 1)
            d = _rotate(start, axis, span * t)
            grow = 0.12 + 0.88 * (1 - (1 - min(1.0, t / 0.35)) ** 2)
            p = crown * (1 - grow) + d * reach * grow
            p = p + wobble * np.sin(np.array([7.0, 9.0, 11.0]) * t + phase)
            pts.append(p * squash)
            radii.append(RADIUS * (0.022 - 0.014 * t))
        material = "stem" if rng.random() < 0.62 else "stem_grey"
        # its base is in the crown, among the other stems (every point of the path stays: fewer show corners in the arc)
        m.merge(tapered_tube(pts, radii, STEM_SIDES, material, base_cap=False))
        # side twigs bristling off the outer part of the stem
        for _ in range(rng.integers(5, 9)):
            i = rng.integers(5, count - 1)
            base = np.asarray(pts[i])
            out = base / (np.linalg.norm(base) + 1e-9)
            tangent = np.asarray(pts[i + 1]) - np.asarray(pts[i - 1])
            tangent /= np.linalg.norm(tangent) + 1e-9
            d = tangent * rng.uniform(-0.6, 0.9) + out * rng.uniform(0.2, 0.8) + _unit(rng) * 0.45
            d /= np.linalg.norm(d)
            length = RADIUS * rng.uniform(0.08, 0.22)
            bend = _unit(rng) * 0.25
            twig = [base + (d + bend * k * k) * length * k for k in (0.0, 0.34, 0.67, 1.0)]
            # kept just inside a shell a little past the ball, so none pokes into the ground it rolls on
            twig = [q * min(1.0, RADIUS * 1.06 / (np.linalg.norm(q) + 1e-9)) for q in twig]
            r0 = radii[i] * 0.55
            # thin enough for three sides and two lengths; its base sits in its stem
            m.merge(tapered_tube([twig[0], (twig[1] + twig[2]) / 2, twig[3]], [r0, r0 * 0.62, r0 * 0.3], TWIG_SIDES, "twig",
                                 base_cap=False))
    return m


def write(mesh, path):
    g = GLB()
    prims = mk.build_primitives(mesh, split_materials=True)
    for name in sorted({p["material"] for p in prims}):
        base, rough = MATERIALS[name]
        g.add_material(name, base, 0.0, rough, extras={"region": "none"})
    mesh_index = g.add_mesh("tumbleweed", prims)
    g.add_node("tumbleweed", None, np.zeros(3), np.array([0.0, 0.0, 0.0, 1.0]), mesh=mesh_index)
    g.save(path)
    return mk.triangle_count(prims)


def main():
    os.makedirs(OUT, exist_ok=True)
    for i in range(VARIANTS):
        path = os.path.join(OUT, f"tumbleweed_{i}.glb")
        tris = write(build(i), path)
        print(f"tumbleweed_{i}.glb: {tris} triangles")


if __name__ == "__main__":
    main()
