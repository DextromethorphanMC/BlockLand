"""Non-visual precision geometry tests and optional GLB contract regression comparison.

python -I tools/assets/check_model_quality.py [--baseline path/to/original/models]
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np

import equip_model
from glbread import GLTF
from meshkit import build_primitives, newell_normal
from model_quality import BUDGETS, SETTINGS, arc_segments, refine_samples

#: weapon files in the pistol budget
PISTOLS = ("p9", "p9a", "usp", "usp_dual", "usp_dual_cerberus", "calico", "handcannon", "deagle", "revolver")
import shapes as sh

MODELS = Path(__file__).resolve().parents[2] / "mod/src/main/resources/assets/blockland/models"


def ray_distance(primitives, origin, direction):
    """Independent two-sided Moller-Trumbore ray test; nearest surface, in metres."""
    triangles = np.concatenate([p["positions"][p["indices"]] for p in primitives])
    a, b, c = triangles[:, 0], triangles[:, 1], triangles[:, 2]
    e1, e2 = b - a, c - a
    h = np.cross(direction, e2)
    det = np.einsum("ij,ij->i", e1, h)
    inv = np.divide(1.0, det, out=np.zeros_like(det), where=abs(det) > 1e-15)
    s = origin - a
    u = inv * np.einsum("ij,ij->i", s, h)
    q = np.cross(s, e1)
    v = inv * (q @ direction)
    t = inv * np.einsum("ij,ij->i", e2, q)
    valid = (abs(det) > 1e-15) & (u >= -1e-9) & (v >= -1e-9) & (u + v <= 1 + 1e-9) & (t > 0)
    return float(np.min(t[valid])) if np.any(valid) else float("inf")


class PrecisionTests(unittest.TestCase):
    def test_chord_error(self):
        for radius in (0.0004, 0.001, 0.0032, 0.0062, 0.012, 0.0318):
            n = arc_segments(radius, 8)
            self.assertLessEqual(radius * (1 - np.cos(np.pi / n)), SETTINGS["curve_error_m"])
            self.assertLessEqual(n, SETTINGS["max_radial_segments"])
            self.assertEqual(n % 4, 0)

    def test_authored_minimum_and_cap(self):
        self.assertEqual(arc_segments(0, 6), 6)
        self.assertEqual(arc_segments(100, 8), SETTINGS["max_radial_segments"])
        self.assertEqual(arc_segments(0.001, 128), 128)

    def test_refinement_retains_knots(self):
        for knots in ([0, 0.17, 0.8, 1], [1, 0.8, 0, -0.8, -1]):
            refined = refine_samples(knots)
            self.assertTrue(all(k in refined for k in knots))
            np.testing.assert_array_equal(refined[::SETTINGS["blade_subdivisions"]], knots)

    def test_opt_in_does_not_round_polygonal_parts(self):
        profile = [(0, 0), (0.02, 0), (0.02, 0.1), (0, 0.1)]
        coarse = sh.revolve(profile, 6, "steel")
        fine = sh.revolve(profile, 6, "steel", precision=True)
        self.assertEqual(len(coarse.v), 24)
        self.assertGreater(len(fine.v), len(coarse.v))
        self.assertGreater(sh.signed_volume(fine), 0)
        for primitive in build_primitives(fine):
            self.assertTrue(np.isfinite(primitive["normals"]).all())
            np.testing.assert_allclose(np.linalg.norm(primitive["normals"], axis=1), 1, atol=1e-6)

    def test_perforated_shell_is_closed_and_outward(self):
        m = sh.perforated_band(0.0215, -0.009, 0.009, 0.0062, 0.0012, "body")
        self.assertGreater(sh.signed_volume(m), 0)
        vertices = np.array(m.v)
        outer, inner = 0, 0
        for face in m.f:
            pts = vertices[list(face)]
            radii = np.linalg.norm(pts[:, [0, 2]], axis=1)
            centre = pts.mean(0) * [1, 0, 1]
            if np.allclose(radii, 0.0215, atol=1e-9):
                self.assertGreater(np.dot(newell_normal(pts), centre), 0)
                outer += 1
            if np.allclose(radii, 0.0203, atol=1e-9):
                self.assertLess(np.dot(newell_normal(pts), centre), 0)
                inner += 1
        self.assertGreater(min(outer, inner), 0)
        # Index-independent welding closes the duplicate first/last loft rings in each cell.
        _, canon = np.unique(np.round(np.array(m.v), 9), axis=0, return_inverse=True)
        edges = {}
        for face in m.f:
            for a, b in zip(face, face[1:] + face[:1]):
                a, b = int(canon[a]), int(canon[b])
                key = tuple(sorted((a, b)))
                edges.setdefault(key, []).append((a, b))
        # Shared cell walls may appear twice; each directed edge must have its opposite.
        for pairs in edges.values():
            self.assertEqual(sum(1 if a < b else -1 for a, b in pairs), 0)

    def test_flashbang_holes_have_real_depth(self):
        parts, _ = equip_model.flashbang()
        primitives = build_primitives(parts["body"][0])
        for row, y in enumerate((-0.026, -0.008, 0.010)):
            for k in range(6):
                angle = np.radians(k * 60 + 15 + (30 if row % 2 else 0))
                n = np.array([np.cos(angle), 0, np.sin(angle)])
                origin = n * (equip_model.FLASH_R + 0.01) + [0, y, 0]
                # The lever/hand are separate nodes and intentionally excluded here.
                self.assertAlmostEqual(ray_distance(primitives, origin, -n), 0.0135, delta=0.00008)
                a = angle + np.radians(30)
                n = np.array([np.cos(a), 0, np.sin(a)])
                origin = n * (equip_model.FLASH_R + 0.01) + [0, y, 0]
                self.assertAlmostEqual(ray_distance(primitives, origin, -n), 0.01, delta=0.00008)

    def test_staggered_rows_share_boundary_vertices(self):
        rows = [sh.perforated_band(0.0215, a, b, 0.0062, 0.0012, "body", phase=np.radians(phase))
                for a, b, phase in [(-0.009, 0.009, 15), (0.009, 0.027, 45)]]
        boundaries = []
        for mesh in rows:
            vertices = np.array(mesh.v)
            select = np.isclose(vertices[:, 1], 0.009, atol=1e-10)
            select &= np.isclose(np.linalg.norm(vertices[:, [0, 2]], axis=1), 0.0215, atol=1e-10)
            boundaries.append(set(map(tuple, np.round(vertices[select], 9))))
        self.assertEqual(boundaries[0], boundaries[1])
        self.assertEqual(len(boundaries[0]), 72)

    def test_karambit_point_is_not_the_radial_extremum(self):
        import karambit_model
        primitives = build_primitives(karambit_model.blade_mesh())
        vertices = np.concatenate([p["positions"] for p in primitives])
        points = np.unique(np.round(vertices[abs(vertices[:, 1]) < 1e-7], 7), axis=0)
        self.assertEqual(len(points), 1)
        np.testing.assert_allclose(points[0], karambit_model.tip_point(), atol=1e-7)
        far = vertices[np.argmax(np.linalg.norm(vertices, axis=1))]
        self.assertGreater(np.linalg.norm(far - points[0]), 0.002)


def contract(g):
    """Compare values, not relocated accessor IDs or BIN offsets after rebuilding geometry."""
    def acc(i):
        return hashlib.sha256(g.accessor(i).tobytes()).hexdigest()

    nodes = []
    for n in g.nodes:
        node = {k: v for k, v in n.items() if k != "mesh"}
        if "mesh" in n:
            node["mesh_name"] = g.json["meshes"][n["mesh"]]["name"]
        nodes.append(node)
    animations = []
    for a in g.json.get("animations", []):
        anim = {k: v for k, v in a.items() if k != "samplers"}
        anim["samplers"] = [{**s, "input": acc(s["input"]), "output": acc(s["output"])} for s in a["samplers"]]
        animations.append(anim)
    materials = [{k: m[k] for k in ("name", "extras", "alphaMode", "doubleSided") if k in m}
                 for m in g.json.get("materials", [])]
    return {"nodes": nodes, "animations": animations, "materials": materials}


def check_assets(baseline=None):
    errors = []
    for category in ("gun", "knife", "equipment"):
        paths = sorted((MODELS / category).glob("*.glb"))
        if not paths:
            errors.append(f"Missing model category: {category}")
        for path in paths:
            if path.stem == "arms":
                continue
            g = GLTF(path)
            tris = sum(g.json["accessors"][p["indices"]]["count"] // 3
                       for mesh in g.json["meshes"] for p in mesh["primitives"])
            # pistols by name (not by prefix: the P90 is a long gun)
            key = category if category != "gun" else ("pistol" if path.stem in PISTOLS else "long_gun")
            # Conservative whole-file ceiling also includes spare magazines and shells.
            limit = BUDGETS[key] * (2 if "dual" in path.stem else 1)
            if tris > limit:
                errors.append(f"{path.name}: {tris} exceeds whole-file ceiling {limit}")
            for mesh in g.json["meshes"]:
                for pr in mesh["primitives"]:
                    for semantic, idx in pr["attributes"].items():
                        if not np.isfinite(g.accessor(idx)).all():
                            errors.append(f"{path.name}: non-finite {semantic}")
                    norm = g.accessor(pr["attributes"]["NORMAL"])
                    if not np.allclose(np.linalg.norm(norm, axis=1), 1, atol=0.002):
                        errors.append(f"{path.name}: non-unit normals")
            if baseline:
                old = GLTF(baseline / category / path.name)
                previous_contract = contract(old)
                for field, value in contract(g).items():
                    if previous_contract[field] != value:
                        errors.append(f"{path.name}: changed {field}")
                # Additional sampling may reach the analytic silhouette, but must not rescale the item.
                def bounds(model):
                    result = {}
                    for mesh in model.json["meshes"]:
                        pos = np.concatenate([model.accessor(p["attributes"]["POSITION"])
                                              for p in mesh["primitives"]])
                        result[mesh["name"]] = np.stack([pos.min(0), pos.max(0)])
                    return result
                old_bounds = bounds(old)
                for name, box in bounds(g).items():
                    if name not in old_bounds or not np.allclose(box, old_bounds[name], atol=0.001, rtol=0):
                        errors.append(f"{path.name}/{name}: local bounds changed by more than 1 mm")
            print(f"{category}/{path.name}: {tris} triangles / {limit}; {path.stat().st_size} bytes")
    for error in errors:
        print("FAIL:", error)
    print(f"Geometry asset checks: {len(errors)} errors")
    return not errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PrecisionTests))
    sys.exit(0 if check_assets(args.baseline) and result.wasSuccessful() else 1)
