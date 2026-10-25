"""Texture sets of the Vector (VIEWMODEL_SPEC sections 20 and 44), plugged into pbr_textures.SETS (SET_MODULES): the
flat dark earth two-tone of the reference - the aluminium upper in a sprayed FDE ceramic coating (orange peel, handling
sheen, scratches showing the lighter undercoat, wear through to the black anodising on the edges), the glass-filled
polymer of the lower and the grip moulded in FDE (the mould texture, faint flow lines, rubbed peaks, dust in the grain) -
and the black parts: the folding stock's polymer, the polymer magazines (worn in lanes where they ride the well) and
the black-nitrided barrel."""
import numpy as np


def sets(pt):
    def upper(size, rng, pmm):
        """FDE ceramic coating over the aluminium upper: worn through to the black anodising in sparse spots."""
        lay = pt.cerakote(size, rng, pmm, colour=(0.60, 0.52, 0.38))
        wear = pt._wear_mask(size, rng, 0.0012, size / 90)
        bare = pt.smoothstep(0.55, 0.75, wear)
        alb = pt.lerp(lay.albedo, pt.srgb([0.13, 0.13, 0.14]), bare)
        return pt.Layers(alb, lay.height - 0.006 * bare, np.clip(lay.rough - 0.15 * bare, 0, 1), lay.metal | (bare > 0.5),
                         f0=lay.f0, porosity=lay.porosity, relief=lay.relief)

    def lower(size, rng, pmm):
        """Glass-filled polymer moulded in FDE: the mould texture, faint flow lines, polished peaks, dust in the grain."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.62, 0.54, 0.40))
        dust = pt.smoothstep(0.4, 1.6, pt.fbm(size, rng, size / 9, 3))
        alb = pt.lerp(lay.albedo, pt.srgb([0.55, 0.49, 0.40]), 0.25 * dust)
        return pt.Layers(alb, lay.height, np.clip(lay.rough + 0.04 * dust, 0, 1), lay.metal, f0=lay.f0,
                         porosity=lay.porosity, relief=lay.relief)

    def stock(size, rng, pmm):
        """The folding stock's polymer: the same mould, rougher and a touch lighter (it rubs on gear)."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.104, 0.104, 0.110))
        return pt.Layers(lay.albedo, lay.height, np.clip(lay.rough + 0.05, 0, 1), lay.metal, f0=lay.f0,
                         porosity=lay.porosity, relief=lay.relief)

    def mag(size, rng, pmm):
        """Polymer magazine body: moulded texture, glossier lanes where it slides through the well."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.088, 0.088, 0.094))
        lane = pt.smoothstep(0.3, 1.3, pt.noise(size, rng, 2.5 / pmm, 32.0 / pmm))
        rough = np.clip(lay.rough - 0.09 * lane, 0, 1)
        return pt.Layers(lay.albedo, lay.height, rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=lay.relief)

    def barrel(size, rng, pmm):
        """Black-nitrided barrel steel: the parkerised crystal texture darkened to near black, oily patches."""
        lay = pt.parkerized(size, rng, pmm)
        alb = np.where(lay.metal[..., None], lay.albedo, lay.albedo * 0.50)
        return pt.Layers(alb, lay.height, np.clip(lay.rough + 0.02, 0, 1), lay.metal, f0=lay.f0, relief=lay.relief)

    return {
        "vector/upper": pt.SetDef(pt._r(upper), 1024, 0.15, 1701, detail=0.45),
        "vector/lower": pt.SetDef(pt._r(lower), 1024, 0.15, 1702, detail=0.6),
        "vector/stock": pt.SetDef(pt._r(stock), 1024, 0.15, 1703, detail=0.6),
        "vector/mag": pt.SetDef(pt._r(mag), 1024, 0.15, 1704, detail=0.5),
        "vector/barrel": pt.SetDef(pt._r(barrel), 1024, 0.12, 1705, detail=0.55),
    }
