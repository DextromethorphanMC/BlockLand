"""Texture sets of the bomb (VIEWMODEL_SPEC sections 20 and 39), plugged into pbr_textures.SETS (SET_MODULES): the
plastic explosive in its clear wrapping film, the grey duct tape and the timer unit's moulded case and its keys."""
import numpy as np


def sets(pt):
    def explosive(size, rng, pmm):
        """Plastic explosive packed by hand into a clear film: a putty-coloured body with soft lumps and finger
        dents, the film's long creases and wrinkles catching the light (glossier along them), dust in the folds."""
        lumps = pt.fbm(size, rng, size / 5, 4)
        dents = pt.smoothstep(0.25, 0.0, pt.worley(size, rng, max(6, int(size * pmm / 9.0)))[0])
        creases = pt.noise(size, rng, 30.0 / pmm, 2.0 / pmm)
        wrinkle = pt.noise(size, rng, 6.0 / pmm, 0.9 / pmm)
        fold = pt.smoothstep(0.55, 1.6, np.abs(creases) * 2.0)
        dust = pt.smoothstep(0.2, 1.2, pt.fbm(size, rng, size / 8, 3)) * fold
        base = pt.srgb([0.56, 0.48, 0.31])
        alb = pt.tint(base, 0.05 * lumps - 0.04 * dents, 1.0)
        alb = pt.lerp(alb, alb * 1.08 + 0.02, 0.35 * fold)
        alb = pt.lerp(alb, pt.srgb([0.42, 0.39, 0.34]), 0.25 * dust)
        h = 0.06 * lumps - 0.05 * dents + 0.012 * fold
        rough = 0.62 - 0.22 * fold + 0.05 * lumps + 0.10 * dust
        return pt.Layers(alb, h, np.clip(rough, 0.2, 0.95), np.zeros((size, size), bool), f0=0.045,
                         relief=0.006 * wrinkle + 0.004 * creases)

    def duct_tape(size, rng, pmm):
        """Grey duct tape: the cloth scrim pressed through the coating (about 1.1 threads per mm, the weft finer),
        wrinkles where it was wound tight, grime at the edges of the creases, a satin sheen."""
        y, x = np.mgrid[0:size, 0:size] / size
        k = max(8, int(round(size * pmm / 0.9)))
        scrim = 0.5 * np.cos(2 * np.pi * k * x) + 0.5 * np.cos(2 * np.pi * int(k * 0.8) * y)
        wr = pt.noise(size, rng, 14.0 / pmm, 3.0 / pmm)
        wrinkle = pt.smoothstep(0.4, 1.4, np.abs(wr) * 2.0)
        grime = pt.smoothstep(0.0, 1.3, pt.fbm(size, rng, size / 7, 4)) * (0.4 + 0.6 * wrinkle)
        base = pt.srgb([0.43, 0.44, 0.45])
        alb = pt.tint(base, 0.03 * scrim + 0.04 * wrinkle, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.20, 0.19, 0.18]), 0.35 * grime)
        h = 0.010 * scrim + 0.05 * wr
        rough = 0.48 + 0.05 * scrim + 0.18 * grime - 0.08 * wrinkle
        return pt.Layers(alb, h, np.clip(rough, 0.2, 0.95), np.zeros((size, size), bool), f0=0.04)

    return {
        "bomb/explosive": pt.SetDef(pt._r(explosive), 512, 0.12, 951),
        "bomb/tape": pt.SetDef(pt._r(duct_tape), 512, 0.06, 952),
        "bomb/housing": pt.SetDef(pt._r(pt.polymer_moulded, colour=(0.075, 0.078, 0.082)), 512, 0.08, 953),
        "bomb/keys": pt.SetDef(pt._r(pt.polymer_moulded, colour=(0.36, 0.37, 0.39)), 256, 0.04, 954),
    }
