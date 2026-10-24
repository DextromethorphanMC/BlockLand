"""Texture sets of the knuckles (pbr_textures.SET_MODULES): the dusters' cast brass (satin, tarnished in the recesses,
handled bright), the polished striking face (dinged and scratched from use) and the paracord on the palm bar (a
twill-woven sheath, matte black)."""
import numpy as np


def sets(pt):
    def cast_brass(size, rng, pmm):
        lay = pt.brass(size, rng, pmm)
        grain = pt.fbm(size, rng, 0.5 / pmm, 3)                       # the cast skin under the satin finish
        tarn = pt.smoothstep(0.2, 1.6, pt.fbm(size, rng, size / 6, 4))
        lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.55, 0.40, 0.22]), 0.35 * tarn[..., None])
        lay.rough = np.clip(lay.rough + 0.14 + 0.03 * grain + 0.10 * tarn, 0, 1)
        lay.relief = (lay.relief if lay.relief is not None else 0) + 0.0015 * grain
        return lay

    def struck_face(size, rng, pmm):
        lay = pt.brass(size, rng, pmm)
        dings = pt.pits(size, rng, pmm, 3.0, (0.08, 0.30))
        sc = pt.scratch_field(size, rng, pmm, 90, (0.5, 6), strength=(0.3, 0.9))
        lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.97, 0.86, 0.60]), 0.25)
        lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.62, 0.46, 0.26]), 0.5 * dings[..., None])
        lay.height = lay.height - 0.012 * dings + pt.groove(sc, 0.003)
        lay.rough = np.clip(0.7 * lay.rough + 0.10 * dings + 0.08 * sc, 0, 1)
        return lay

    def paracord(size, rng, pmm):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(4, int(round(size * pmm / 1.1)))                       # ~1.1 mm strands, a 2/2 twill
        a = np.sin(2 * np.pi * n * (x + y))
        b = np.sin(2 * np.pi * n * (x - y))
        over = (np.sin(np.pi * n * (x - y) * 2 + np.pi * np.floor(n * (x + y) * 2) / 2) > 0)
        weave = np.where(over, 0.5 + 0.5 * a, 0.5 + 0.5 * b) ** 0.7
        fuzz = pt.noise(size, rng, 0.6)
        mott = pt.fbm(size, rng, size / 5, 4)
        dirt = pt.smoothstep(0.3, 1.5, pt.fbm(size, rng, size / 8, 3))
        alb = pt.tint(pt.srgb([0.055, 0.055, 0.052]), 0.03 * mott + 0.02 * fuzz, 1.0)
        alb = pt.lerp(alb, alb * 1.6 + 0.01, 0.4 * weave[..., None])
        alb = pt.lerp(alb, pt.srgb([0.20, 0.18, 0.15]), 0.25 * dirt[..., None])
        h = 0.10 * weave + 0.01 * fuzz
        rough = 0.82 + 0.06 * fuzz - 0.10 * weave * (1 - dirt) + 0.05 * dirt
        return pt.Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.035, relief=0.004 * fuzz)

    return {
        "knuckles/brass": pt.SetDef(pt._r(cast_brass), 512, 0.06, 821),
        "knuckles/face": pt.SetDef(pt._r(struck_face), 512, 0.04, 822),
        "knuckles/wrap": pt.SetDef(pt._r(paracord), 256, 0.03, 823),
    }
