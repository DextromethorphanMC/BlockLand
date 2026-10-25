"""Texture sets of the expandable baton (pbr_textures.SET_MODULES): the shafts' black-oxide steel (polished along the
shaft by the friction locks, scuffed where it strikes, the oxide rubbed through to grey steel at the worst knocks),
the grip's ribbed foam rubber (a fine closed-cell grain, polished where the palm sits) and the end cap's anodised
aluminium (the shared recipe)."""
import numpy as np


def sets(pt):
    def black_oxide(size, rng, pmm):
        lines = 0.7 * pt.noise(size, rng, 10.0 / pmm, max(0.65, 0.12 / pmm)) \
            + 0.3 * pt.noise(size, rng, 3.0 / pmm, max(0.65, 0.25 / pmm))
        mott = pt.fbm(size, rng, size / 5, 4)
        zone = pt.smudges(size, rng, size / 6, 0.3)
        sc = pt.scratch_field(size, rng, pmm, 90, (0.5, 12), angle=0, spread=0.4, cluster=0.3 + 0.7 * zone,
                              strength=(0.2, 0.8))
        knock = pt.smoothstep(0.5, 0.9, pt.blur(pt._wear_mask(size, rng, 0.004, size / 70), 1.2))
        alb = pt.tint(pt.srgb([0.105, 0.105, 0.115]), 0.04 * mott + 0.02 * lines, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.30, 0.30, 0.31]), 0.55 * sc)
        alb = pt.lerp(alb, pt.srgb([0.48, 0.48, 0.49]), knock)
        h = pt.groove(sc, 0.003) - 0.006 * knock
        rough = 0.22 + 0.04 * mott + 0.03 * lines - 0.05 * zone + 0.06 * sc + 0.06 * knock
        return pt.Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0012 * lines)

    def foam(size, rng, pmm):
        cells = max(8, int(round(size * pmm / 0.35)))
        f1, f2, _ = pt.worley(size, rng, cells)
        pore = pt.smoothstep(0.0, 0.18, f2 - f1) ** 0.5
        grain = pt.noise(size, rng, 0.7)
        mott = pt.fbm(size, rng, size / 5, 4)
        shine = pt.smudges(size, rng, size / 7, 0.3)
        dust = (1 - pore) * pt.smoothstep(-0.2, 1.2, pt.fbm(size, rng, size / 10, 3))
        alb = pt.tint(pt.srgb([0.075, 0.075, 0.078]), 0.05 * mott + 0.03 * grain, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.24, 0.23, 0.21]), 0.25 * dust)
        h = 0.020 * pore - 0.010 * (1 - pore) + 0.003 * grain
        rough = 0.88 - 0.14 * shine * pore + 0.04 * dust
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.035, relief=0.002 * grain)

    return {
        "baton/shaft": pt.SetDef(pt._r(black_oxide), 512, 0.10, 841),
        "baton/grip": pt.SetDef(pt._r(foam), 512, 0.06, 842),
        "baton/hardware": pt.SetDef(pt._r(pt.anodized_black), 512, 0.06, 843),
    }
