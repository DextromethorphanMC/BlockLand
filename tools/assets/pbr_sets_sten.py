"""Texture sets of the STEN Mk II (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES):
the wartime finish -- black enamel over phosphated sheet steel, thin and worn through to grey phosphate and bright
steel where hands and webbing rub -- the hot-blued barrel group, the magazine's painted sheet with its long
insertion scuffs, and the stained beech of the pistol grip."""
import numpy as np


def sets(pt):
    def body(size, rng, pmm):
        """Thin, rushed black enamel over phosphate on stamped and rolled sheet: more orange peel and sheen breakup
        than the AK's, wear spots and scuffs twice as frequent (the paint was never good), a rusty bloom here and
        there in the pits."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.100, 0.101, 0.105), coverage=0.018)
        bloom = pt.smoothstep(0.55, 1.0, pt.fbm(size, rng, size / 9, 4)) * pt.pits(size, rng, pmm, 1.6, (0.08, 0.3))
        # the house recipe's dust specks and primer read as bright freckles on a black gun: keep the paint dark
        # (worn-through bare steel stays bright metal)
        paint = np.minimum(lay.albedo, pt.srgb([0.19, 0.19, 0.195]))
        alb = np.where(lay.metal[..., None], lay.albedo, paint)
        alb = pt.lerp(alb, pt.srgb([0.17, 0.11, 0.08]), 0.35 * np.clip(bloom, 0, 1)[..., None])
        rough = np.clip(lay.rough + 0.05 + 0.15 * bloom, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=lay.relief)

    def blued(size, rng, pmm):
        """Hot-blued machined steel (barrel, nut, sleeve): the dark steel's grinding lines and handling marks with a
        blue-black cast, rubbed back to grey steel on the high spots."""
        lay = pt.steel_dark(size, rng, pmm)
        hue = pt.fbm(size, rng, size / 4, 3)
        deep = pt.lerp(pt.srgb([0.135, 0.142, 0.170]), pt.srgb([0.155, 0.150, 0.165]), pt.smoothstep(-1.2, 1.2, hue))
        bright = lay.albedo.mean(-1, keepdims=True) > 0.36
        alb = np.where(bright, lay.albedo, deep * (lay.albedo / np.maximum(lay.albedo.mean(), 1e-3)) ** 0.5)
        rough = np.clip(lay.rough + 0.02, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, relief=lay.relief)

    def mag(size, rng, pmm):
        """Painted magazine sheet: the vertical (insertion) wear lanes of the drawn sheet, a little browner black."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.105, 0.102, 0.098), coverage=0.008, vertical=True)
        paint = np.minimum(lay.albedo, pt.srgb([0.20, 0.20, 0.20]))
        alb = np.where(lay.metal[..., None], lay.albedo, paint)
        return pt.Layers(alb, lay.height, lay.rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=lay.relief)

    def wood(size, rng, pmm):
        """The pistol grip: wartime beech stained a reddish brown under a thin oil finish, the stain rubbed thin to the
        pale wood on the edges the hand wears."""
        lay = pt.lacquered_wood(size, rng, pmm)
        t = pt.norm01(lay.albedo.mean(-1))
        streak = pt.fbm(size, rng, size / 6, 3, aniso=4.0)
        tone = pt.palette(t, [(0.0, pt.srgb([0.120, 0.058, 0.032])), (0.5, pt.srgb([0.250, 0.125, 0.065])),
                              (1.0, pt.srgb([0.400, 0.225, 0.120]))])
        alb = pt.tint(tone, -0.08 * streak, 1.0)
        rough = np.clip(lay.rough + 0.15, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=0.04, porosity=0.3, relief=lay.relief)

    return {
        "sten/wood": pt.SetDef(pt._r(wood), 1024, 0.25, 844),
        "sten/body": pt.SetDef(pt._r(body), 1024, 0.15, 841, 0.5),
        "sten/blued": pt.SetDef(pt._r(blued), 1024, 0.12, 842, 0.45),
        "sten/mag": pt.SetDef(pt._r(mag), 1024, 0.15, 843, 0.5),
    }
