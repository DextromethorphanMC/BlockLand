"""Texture sets of the revolver (pbr_textures.SET_MODULES; VIEWMODEL_SPEC sections 20 and 49): the deep, polished
royal-blue bluing of the frame, cylinder and barrel (worn bright at the edges where it is drawn and holstered, faint
swirls of the polish, a few handling scratches), brushed stainless for its skin, and the ivory grip of a skin. The
walnut grip, the speed loader's polymer, the small parts' dark steel and the brass are shared sets."""
import numpy as np


def sets(pt):
    def bluing(size, rng, pmm):
        swirl = pt.fbm(size, rng, 6.0 / pmm, 4, aniso=2.0)
        mott = pt.fbm(size, rng, size / 5, 4)
        hue = pt.smoothstep(-1.0, 1.4, pt.fbm(size, rng, size / 4, 3))
        rub = pt.smoothstep(0.35, 1.0, pt.smudges(size, rng, 1.6 / pmm, 0.015))
        sc = pt.scratch_field(size, rng, pmm, 70, (0.5, 10), strength=(0.15, 0.55))
        prints = pt.prints(size, rng, pmm, 2)
        base = pt.lerp(pt.srgb([0.115, 0.135, 0.205]), pt.srgb([0.090, 0.100, 0.150]), hue)     # royal blue, deepening
        alb = pt.tint(base, 0.04 * mott + 0.015 * swirl, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.50, 0.50, 0.52]), 0.55 * rub)                          # worn to the white
        alb = pt.lerp(alb, pt.srgb([0.40, 0.42, 0.46]), 0.45 * sc)
        rough = 0.13 + 0.03 * mott + 0.02 * swirl + 0.06 * rub + 0.06 * sc + 0.10 * prints
        return pt.Layers(alb, pt.groove(sc, 0.003), np.clip(rough, 0, 1), np.ones((size, size), bool),
                         relief=0.0006 * swirl)

    def stainless(size, rng, pmm):
        lay = pt.satin_blade(size, rng, pmm)
        lay.albedo = np.clip(lay.albedo * np.array([0.94, 0.95, 0.96]), 0, 1)
        lay.rough = np.clip(lay.rough + 0.06, 0, 1)
        return lay

    def ivory(size, rng, pmm):
        grain = pt.noise(size, rng, 0.8 / pmm, 12.0 / pmm)
        bands = pt.fbm(size, rng, size / 6, 3)
        craze = pt.scratch_field(size, rng, pmm, 40, (2, 14), strength=(0.1, 0.35))
        alb = pt.tint(pt.srgb([0.86, 0.81, 0.69]), 0.05 * grain + 0.04 * bands, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.70, 0.62, 0.48]), 0.5 * craze)
        rough = 0.32 + 0.04 * grain + 0.08 * craze
        return pt.Layers(alb, 0.004 * grain - pt.groove(craze, 0.002), np.clip(rough, 0, 1),
                         np.zeros((size, size), bool), f0=0.04)

    return {
        "revolver/blued": pt.SetDef(pt._r(bluing), 1024, 0.10, 1201),
        "revolver/stainless": pt.SetDef(pt._r(stainless), 512, 0.10, 1202),
        "revolver/ivory": pt.SetDef(pt._r(ivory), 512, 0.10, 1203),
    }
