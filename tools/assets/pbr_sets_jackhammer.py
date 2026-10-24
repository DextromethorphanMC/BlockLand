"""Jackhammer texture sets (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS through SET_MODULES:
the hard-anodised black aluminium of the receiver, lower and handle, the moulded black polymer of the butt, bay
housing and operating sleeve, the rubber grip panels, the dark (brown-black, oiled) steel of the barrel and flash hider
and the much-handled anodised cassette. The steel details, butt pad, hulls and brass use the common / KS-3 sets.

python -I tools/assets/pbr_textures.py jackhammer/"""
import numpy as np


def sets(pt):
    def receiver(size, rng, pmm):
        """Hard-anodised, bead-blasted aluminium (the house recipe), its black a little cooler, a touch more wear."""
        lay = pt.anodized_black(size, rng, pmm)
        zone = pt.smudges(size, rng, size / 7, 0.3)
        sc = pt.scratch_field(size, rng, pmm, 40, (0.5, 8.0), cluster=0.2 + 0.8 * zone, strength=(0.2, 0.7))
        alb = pt.lerp(lay.albedo, pt.srgb([0.30, 0.30, 0.31]), 0.45 * sc)
        rough = lay.rough - 0.08 * sc
        return pt.Layers(alb, lay.height + pt.groove(sc, 0.004), rough, lay.metal, f0=lay.f0, relief=lay.relief)

    def polymer(size, rng, pmm):
        """Glass-filled black polymer (butt, bay housing, operating sleeve): the house mould texture, a deep black."""
        return pt.polymer_moulded(size, rng, pmm, colour=(0.118, 0.118, 0.124))

    def grip(size, rng, pmm):
        """Moulded rubber grip panels: a fine pebble grain, satin where the palm rubs."""
        return pt.rubber(size, rng, pmm)

    def barrel(size, rng, pmm):
        """Oiled dark steel with a warm brown-black oxide (the photo's barrel): the dark steel's grinding and handling
        marks recoloured, a little heat colour in streaks along the bore, glossier where oil stays."""
        lay = pt.steel_dark(size, rng, pmm)
        hue = pt.fbm(size, rng, size / 4, 3)
        streak = pt.smoothstep(0.2, 1.4, pt.fbm(size, rng, size / 6, 4, aniso=5.0))
        deep = pt.lerp(pt.srgb([0.150, 0.128, 0.112]), pt.srgb([0.128, 0.120, 0.118]), pt.smoothstep(-1.2, 1.2, hue))
        deep = pt.lerp(deep, pt.srgb([0.205, 0.150, 0.110]), 0.35 * streak[..., None])
        bright = lay.albedo.mean(-1, keepdims=True) > 0.36
        alb = np.where(bright, lay.albedo, deep * (lay.albedo / np.maximum(lay.albedo.mean(), 1e-3)) ** 0.5)
        rough = np.clip(lay.rough - 0.04 - 0.06 * streak, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, relief=lay.relief)

    def cassette(size, rng, pmm):
        """The Ammo-Cassette's anodised shell: dropped and slapped home every reload, so scuffed and scratched more
        than the receiver, the deepest marks bright aluminium."""
        lay = pt.anodized_black(size, rng, pmm)
        handled = pt.smudges(size, rng, size / 6, 0.4)
        sc = pt.scratch_field(size, rng, pmm, 90, (0.8, 22.0), curve=0.4, cluster=0.25 + 0.75 * handled,
                              strength=(0.25, 0.9))
        scuff = pt.blur(pt.scratch_field(size, rng, pmm, 40, (4, 30), width=3, curve=0.5, cluster=handled,
                                         strength=(0.15, 0.45)), 1.2)
        bare = pt.smoothstep(0.75, 0.98, sc)
        metal = lay.metal | (bare > 0.5)
        alb = pt.lerp(lay.albedo, pt.srgb([0.24, 0.24, 0.25]), np.clip(0.5 * sc + 0.4 * scuff, 0, 1))
        alb = np.where((bare > 0.5)[..., None], pt.srgb([0.78, 0.79, 0.80]), alb)
        rough = np.where(metal, 0.30, lay.rough - 0.10 * scuff - 0.06 * sc)
        return pt.Layers(alb, lay.height + pt.groove(sc, 0.006), rough, metal, f0=lay.f0, relief=lay.relief)

    return {
        "jackhammer/receiver": pt.SetDef(pt._r(receiver), 1024, 0.12, 4101, detail=0.45),
        "jackhammer/polymer": pt.SetDef(pt._r(polymer), 1024, 0.15, 4102, detail=0.6),
        "jackhammer/grip": pt.SetDef(pt._r(grip), 512, 0.08, 4103, detail=0.55),
        "jackhammer/barrel": pt.SetDef(pt._r(barrel), 1024, 0.12, 4104, detail=0.45),
        "jackhammer/cassette": pt.SetDef(pt._r(cassette), 1024, 0.12, 4105, detail=0.5),
    }
