"""Texture sets of the Thompson (VIEWMODEL_SPEC sections 20 and 24), plugged into pbr_textures.SETS
(SET_MODULES): oiled walnut furniture and the blued steel of the receiver and magazines."""
import numpy as np


def sets(pt):
    def walnut(size, rng, pmm):
        """Oil-finished American walnut: the AK furniture's wood structure (rings, fibres, pores and rays along u)
        recoloured to a deep brown with dark streaks, under a satin oil finish (less gloss, more open pores)."""
        lay = pt.lacquered_wood(size, rng, pmm)
        lum = lay.albedo.mean(-1)
        t = pt.norm01(lum)
        streak = pt.fbm(size, rng, size / 6, 3, aniso=4.0)
        tone = pt.palette(t, [(0.0, pt.srgb([0.105, 0.060, 0.035])), (0.45, pt.srgb([0.225, 0.130, 0.070])),
                              (1.0, pt.srgb([0.36, 0.215, 0.115]))])
        alb = pt.tint(tone, -0.10 * streak, 1.0)
        rough = np.clip(lay.rough + 0.12, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=0.04, porosity=0.25, relief=lay.relief)

    def blued(size, rng, pmm):
        """Hot-blued machined steel (receiver, magazines): the dark steel's grinding, rubbing and handling marks with a
        deep blue-black cast and a smoother polish; edges rubbed back to grey steel."""
        lay = pt.steel_dark(size, rng, pmm)
        hue = pt.fbm(size, rng, size / 4, 3)
        deep = pt.lerp(pt.srgb([0.150, 0.158, 0.185]), pt.srgb([0.172, 0.165, 0.180]), pt.smoothstep(-1.2, 1.2, hue))
        bright = lay.albedo.mean(-1, keepdims=True) > 0.36        # rubbed spots and scratches stay bright steel
        alb = np.where(bright, lay.albedo, deep * (lay.albedo / np.maximum(lay.albedo.mean(), 1e-3)) ** 0.5)
        rough = np.clip(lay.rough - 0.06, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, relief=lay.relief)

    return {
        "tommy/walnut": pt.SetDef(pt._r(walnut), 1024, 0.25, 801),
        "tommy/blued": pt.SetDef(pt._r(blued), 1024, 0.15, 802),
    }
