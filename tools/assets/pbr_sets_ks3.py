"""KS-3 texture sets (VIEWMODEL_SPEC sections 20 / 25), plugged into pbr_textures.SETS through SET_MODULES: the
shotshell hulls. The rest is shared (pbr_models_ks3): the AWP's cerakote action for the receiver, the P9's moulded
polymer and stippling for the forend, stock and grip, the common steels and brass."""
import numpy as np


def shotshell_hull(pt, size, rng, pmm, colour=(0.62, 0.06, 0.05)):
    """Extruded polyethylene hull: fine extrusion lines along its length (u), a waxy sheen, faint printing bands
    across it and a few scuffs from the tube and the hand."""
    lines = pt.noise(size, rng, 0.2 / pmm, 9.0 / pmm)
    mott = pt.fbm(size, rng, size / 5, 4)
    sc = pt.scratch_field(size, rng, pmm, 30, (0.5, 6.0), strength=(0.2, 0.6))
    band = pt.smoothstep(0.86, 0.95, np.abs(np.sin(np.mgrid[0:size, 0:size][1] / size * np.pi * 3)))
    c = pt.srgb(colour)
    alb = pt.tint(c, 0.03 * mott + 0.02 * lines, 1.0)
    alb = pt.lerp(alb, c * 0.72, 0.25 * band[..., None] if np.ndim(band) == 2 else 0.25 * band)
    alb = pt.lerp(alb, c * 1.25 + 0.04, 0.5 * sc)
    rough = 0.34 + 0.05 * mott + 0.03 * lines + 0.18 * sc
    return pt.Layers(alb, pt.groove(sc, 0.004), rough, np.zeros((size, size), bool), f0=0.04, relief=0.003 * lines)


def sets(pt):
    hull = lambda d, rng: shotshell_hull(pt, d.size, rng, pt.px_mm(d))
    return {
        "ks3/shell": pt.SetDef(hull, 256, 0.05, 814),
    }
