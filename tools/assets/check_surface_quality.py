"""Offline surface/geometry regression. No game launch, GPU or visual acceptance.

python -I tools/assets/check_surface_quality.py --baseline ORIGINAL_PROJECT_COPY
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np
from PIL import Image

import attach_models
import p9_parts
import pbr_textures as pt
from check_model_quality import contract, ray_distance
from glbread import GLTF
from meshkit import build_primitives, triangle_count

ROOT = Path(__file__).resolve().parents[2]
ASSETS = Path('mod/src/main/resources/assets/blockland')


class SurfaceTests(unittest.TestCase):
    def test_profiles_keep_resolution_and_scale(self):
        for name, detail in pt.SURFACE_QUALITY['detail'].items():
            d = pt.SETS[name]
            self.assertLessEqual(d.size, pt.SURFACE_QUALITY['max_texture_size'])
            self.assertEqual(d.detail, detail)
            self.assertAlmostEqual(pt.uv_scale(name) * d.tile, pt.UV_UNIT, places=6)

    def test_deterministic_packed_channels(self):
        for name in ('common/anodized_black', 'p9/mag', 'butterfly/blade', 'equipment/olive', 'equipment/zinc'):
            old = pt.SETS[name]
            d = pt.SetDef(old.recipe, 128, old.tile, old.seed, old.detail)
            a = pt.pack(d.recipe(d, np.random.default_rng(d.seed)), d)
            b = pt.pack(d.recipe(d, np.random.default_rng(d.seed)), d)
            for x, y in zip(a, b):
                np.testing.assert_array_equal(x, y)
                self.assertEqual(x.dtype, np.uint8)
            self.assertTrue(np.all(a[2][..., 3] == 255), name + ' must not emit')
            self.assertTrue(np.all((a[2][..., 1] <= 229) | (a[2][..., 1] == 255)))
            self.assertTrue(np.all(a[1][..., 3] >= 1))
            self.assertGreater(np.std(a[2][..., 0]), 1, name + ' needs specular breakup')

    def test_magazine_ports_have_depth_not_painted_dots(self):
        for extended in (False, True):
            p = build_primitives(p9_parts.mag_mesh(extended))
            direction = np.array([0, 0, -1])
            holes = (0.030, 0.045, 0.060, 0.075) + ((0.015, 0.0) if extended else ())
            for y in holes:
                depth = ray_distance(p, np.array([-0.004, y, 0.020]), direction)
                self.assertAlmostEqual(depth, 0.0065, places=7)
                wall = ray_distance(p, np.array([0.004, y, 0.020]), direction)
                self.assertAlmostEqual(wall, 0.00375, places=7)

    def test_scope_flutes_and_index_budget(self):
        m = attach_models._knurled(0.0125, 0, 0.0175, 4, 0.0006, 'scope', 22)
        p = build_primitives(m)
        self.assertLess(triangle_count(p), 300)
        ring = np.array(m.v)[24:48]
        radius = np.linalg.norm(ring[:, :2], axis=1)
        self.assertAlmostEqual(float(radius.max() - radius.min()), 0.0006, places=8)
        self.assertEqual(set(m.m), {'scope', 'steel_dark'})
        for pr in p:
            self.assertTrue(np.isfinite(pr['positions']).all())
            np.testing.assert_allclose(np.linalg.norm(pr['normals'], axis=1), 1, atol=1e-6)


def assets(baseline):
    before, after = baseline / ASSETS, ROOT / ASSETS
    settings = pt.SURFACE_QUALITY
    changed_sets = set()
    memory = [0, 0]
    for p in sorted((after / 'textures/pbr').rglob('*.png')):
        rel = p.relative_to(after)
        old = before / rel
        assert old.exists(), f'Unexpected texture allocation: {rel}'
        with Image.open(old) as a, Image.open(p) as b:
            assert a.size == b.size and a.mode == b.mode, rel
            for i, im in enumerate((a, b)):
                w, h = im.size
                while True:
                    memory[i] += w * h * 4
                    if w == h == 1:
                        break
                    w, h = max(1, w // 2), max(1, h // 2)
        if old.read_bytes() != p.read_bytes():
            changed_sets.add(str(rel.with_suffix('')).removesuffix('_n').removesuffix('_s'))
    assert memory[0] == memory[1]
    assert len(changed_sets) >= 40
    totals = [0, 0]
    batches = [0, 0]
    for category in ('gun', 'knife', 'equipment', 'attachment'):
        for p in sorted((after / 'models' / category).glob('*.glb')):
            if p.stem == 'arms':
                continue
            old, new = GLTF(before / p.relative_to(after)), GLTF(p)
            assert contract(old) == contract(new), f'Rig/animation/material binding changed: {p.name}'
            counts, primitives = [], []
            for i, g in enumerate((old, new)):
                ps = [p for m in g.json['meshes'] for p in m['primitives']]
                tris = sum(g.json['accessors'][p['indices']]['count'] // 3 for p in ps)
                counts.append(tris)
                primitives.append(len(ps))
                totals[i] += tris
                batches[i] += len(ps)
            assert primitives[1] <= primitives[0] + settings['max_primitive_growth'], p.name
            if p.stem == 'p9':
                assert counts[1] - counts[0] <= settings['max_p9_triangle_growth']
            elif category == 'attachment':
                assert counts[1] <= settings['max_attachment_triangles']
                assert counts[1] <= counts[0], p.name
            else:
                assert counts[1] == counts[0], p.name
            if old.json != new.json or old.bin != new.bin:
                print(f'{category}/{p.name}: triangles {counts}; primitives {primitives}; contract unchanged')
    assert totals[1] <= totals[0]
    print(f'Changed sets: {len(changed_sets)}; RGBA8 full-mip allocation: {memory}; triangles: {totals}; primitives: {batches}')
    print('PASS: no new textures, dimensions, material batches, rigs, clips, lens or aim bindings')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SurfaceTests))
    if not result.wasSuccessful():
        sys.exit(1)
    if args.baseline:
        assets(args.baseline)
