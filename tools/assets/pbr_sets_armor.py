"""Texture sets of the body armour (pbr_textures.SET_MODULES): the carrier's 500D nylon (a fine plain weave in ranger
green, matte, scuffed paler where it rubs, dust in the weave), its webbing (a coarse ribbed weave a shade darker) and
the plate's polyurea coat (black, faintly rubbery, a sprayed orange-peel skin with scuffs)."""
import numpy as np


def sets(pt):
    def nylon(size, rng, pmm):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(8, int(round(size * pmm / 0.55)))                       # ~0.55 mm yarns
        warp = 0.5 + 0.5 * np.sin(2 * np.pi * n * x)
        weft = 0.5 + 0.5 * np.sin(2 * np.pi * n * y)
        cell = ((np.floor(n * x * 2) + np.floor(n * y * 2)) % 2) > 0
        weave = np.where(cell, warp, weft)
        fuzz = pt.noise(size, rng, 0.7)
        mott = pt.fbm(size, rng, size / 5, 4)
        rub = pt.smudges(size, rng, size / 6, 0.30)
        dust = pt.smoothstep(0.3, 1.5, pt.fbm(size, rng, size / 8, 3))
        alb = pt.tint(pt.srgb([0.25, 0.28, 0.20]), 0.08 * weave + 0.05 * mott + 0.03 * fuzz, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.36, 0.38, 0.31]), 0.30 * rub)
        alb = pt.lerp(alb, pt.srgb([0.44, 0.41, 0.34]), 0.25 * dust)
        h = 0.016 * weave + 0.004 * fuzz
        rough = 0.86 + 0.04 * fuzz - 0.08 * rub + 0.04 * dust
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04, relief=0.002 * fuzz)

    def webbing(size, rng, pmm):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(8, int(round(size * pmm / 1.1)))                        # coarse ribs ~1.1 mm along the strap
        rib = (0.5 + 0.5 * np.sin(2 * np.pi * n * y)) ** 1.5
        twill = 0.5 + 0.5 * np.sin(2 * np.pi * n * (x + y) * 0.5)
        fuzz = pt.noise(size, rng, 0.7)
        mott = pt.fbm(size, rng, size / 5, 4)
        alb = pt.tint(pt.srgb([0.20, 0.23, 0.16]), 0.10 * rib + 0.04 * twill + 0.05 * mott, 1.0)
        h = 0.030 * rib + 0.008 * twill + 0.004 * fuzz
        rough = 0.82 + 0.05 * fuzz - 0.06 * rib
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04, relief=0.002 * fuzz)

    def polyurea(size, rng, pmm):
        peel = 0.6 * pt.noise(size, rng, 0.9 / pmm) + 0.4 * pt.noise(size, rng, 2.4 / pmm)
        mott = pt.fbm(size, rng, size / 5, 4)
        sc = pt.scratch_field(size, rng, pmm, 50, (1, 18), strength=(0.2, 0.7))
        rub = pt.smudges(size, rng, size / 7, 0.25)
        alb = pt.tint(pt.srgb([0.065, 0.065, 0.068]), 0.05 * mott + 0.03 * peel, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.20, 0.20, 0.20]), 0.5 * sc)
        h = 0.010 * peel + pt.groove(sc, 0.004)
        rough = 0.66 + 0.04 * peel + 0.03 * mott - 0.10 * rub + 0.06 * sc
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.045, relief=0.0015 * peel)

    return {
        "armor/nylon": pt.SetDef(pt._r(nylon), 512, 0.06, 951),
        "armor/webbing": pt.SetDef(pt._r(webbing), 256, 0.04, 952),
        "armor/polyurea": pt.SetDef(pt._r(polyurea), 512, 0.12, 953),
    }
