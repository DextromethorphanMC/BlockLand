"""Texture sets of the MP5 (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES): the matte
black paint HK puts over the stamped, phosphated steel (receiver, tube, front sight; worn through to the grey phosphate
and bright steel where hands and gear rub), the black-phosphated barrel, the moulded polymer of the trigger group, the
slim handguard's pebbled polymer and the painted magazine steel (insertion wear along the body)."""
import numpy as np


def sets(pt):
    def matte(lay, rough_base, gain=0.8):
        """Same layers, the paint's sheen knocked down to a matte finish (bare steel and primer keep their own)."""
        painted = ~lay.metal
        rough = np.where(painted, np.clip(rough_base + gain * (lay.rough - 0.40), 0.0, 1.0), lay.rough)
        return pt.Layers(lay.albedo, lay.height, rough, lay.metal, f0=lay.f0, porosity=lay.porosity,
                         relief=lay.relief)

    def receiver(size, rng, pmm):
        """Stamped receiver steel under HK's matte black paint: orange peel, the sheet's waviness, dust in the paint,
        sparse rubbed-through spots (phosphate grey, the deepest bright steel), a faintly blue-black cast."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.082, 0.083, 0.090), coverage=0.0012)
        hue = pt.fbm(size, rng, size / 4, 3)
        tone = pt.lerp(pt.srgb([0.98, 0.99, 1.04]), pt.srgb([1.02, 1.0, 0.98]), pt.smoothstep(-1.2, 1.2, hue))
        alb = np.where(lay.metal[..., None], lay.albedo, np.clip(lay.albedo * tone, 0, 1))
        lay = pt.Layers(alb, lay.height, lay.rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=lay.relief)
        return matte(lay, 0.64)

    def barrel(size, rng, pmm):
        """Black-phosphated barrel steel: the parkerised crystal texture darkened to near black, oil-dark patches,
        scratches crushed lighter, a few rubbed bright spots near the lugs."""
        lay = pt.parkerized(size, rng, pmm)
        alb = np.where(lay.metal[..., None], lay.albedo, lay.albedo * 0.50)
        return pt.Layers(alb, lay.height, np.clip(lay.rough + 0.02, 0, 1), lay.metal, f0=lay.f0, relief=lay.relief)

    def polymer(size, rng, pmm):
        """Glass-filled black polymer of the trigger group and grip: the mould texture, faint flow lines, polished
        peaks where the hand rubs, dust in the grain."""
        return pt.polymer_moulded(size, rng, pmm, colour=(0.098, 0.098, 0.104))

    def handguard(size, rng, pmm):
        """Slim handguard polymer: the moulded texture with a pebble grain over it (~0.8 mm), a little rougher."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.104, 0.104, 0.110))
        cells = max(8, int(round(size * pmm / 0.8)))
        f1, f2, _ = pt.worley(size, rng, cells)
        pebble = pt.smoothstep(0.0, 0.25, f2 - f1) ** 0.6
        h = lay.height + 0.012 * pebble
        rough = np.clip(lay.rough + 0.05 - 0.05 * pebble, 0, 1)
        alb = pt.tint(lay.albedo, 0.025 * pt.std(pebble), 1.0)
        relief = (lay.relief if lay.relief is not None else 0.0) + 0.004 * pebble
        return pt.Layers(alb, h, rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=relief)

    def mag(size, rng, pmm):
        """Magazine steel: black paint, satin-matte, worn in long lanes by the well (insertion wear)."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.078, 0.078, 0.084), coverage=0.0010, vertical=True)
        return matte(lay, 0.52, 0.7)

    return {
        "mp5/receiver": pt.SetDef(pt._r(receiver), 1024, 0.15, 1501, detail=0.5),
        "mp5/barrel": pt.SetDef(pt._r(barrel), 1024, 0.12, 1502, detail=0.55),
        "mp5/polymer": pt.SetDef(pt._r(polymer), 1024, 0.15, 1503, detail=0.6),
        "mp5/handguard": pt.SetDef(pt._r(handguard), 1024, 0.12, 1504, detail=0.6),
        "mp5/mag": pt.SetDef(pt._r(mag), 1024, 0.15, 1505, detail=0.5),
    }
