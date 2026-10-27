"""Texture sets of the PP-19 Bizon (VIEWMODEL_SPEC sections 20 and 54), plugged into pbr_textures.SETS (SET_MODULES):
the satin black enamel Izhmash bakes over the stamped AK steel (orange peel, the sheet's waviness, rubbed through to
the grey phosphate and bright steel at the edges and where hands and the sling rub), the black-phosphated barrel, the
AK-74 grip's moulded black polymer and the helical magazine's glass-filled polymer: the moulded texture with the
helix of the feed spiral showing through as a faint diagonal ridge every few centimetres, and long glossy lanes where
the support hand holds it."""
import numpy as np


def sets(pt):
    def satin(lay, rough_base, gain=0.8):
        """Same layers, the enamel's sheen eased to a satin finish (bare steel keeps its own)."""
        painted = ~lay.metal
        rough = np.where(painted, np.clip(rough_base + gain * (lay.rough - 0.40), 0.0, 1.0), lay.rough)
        return pt.Layers(lay.albedo, lay.height, rough, lay.metal, f0=lay.f0, porosity=lay.porosity,
                         relief=lay.relief)

    def receiver(size, rng, pmm):
        """Stamped steel under satin black enamel: orange peel, dust in the paint, rubbed-through spots and edges."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.074, 0.074, 0.080), coverage=0.0016)
        hue = pt.fbm(size, rng, size / 4, 3)
        tone = pt.lerp(pt.srgb([0.97, 0.98, 1.03]), pt.srgb([1.02, 1.0, 0.98]), pt.smoothstep(-1.2, 1.2, hue))
        alb = np.where(lay.metal[..., None], lay.albedo, np.clip(lay.albedo * tone, 0, 1))
        lay = pt.Layers(alb, lay.height, lay.rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=lay.relief)
        return satin(lay, 0.52)

    def barrel(size, rng, pmm):
        """Black-phosphated barrel: the parkerised crystal texture darkened to near black, oily patches."""
        lay = pt.parkerized(size, rng, pmm)
        alb = np.where(lay.metal[..., None], lay.albedo, lay.albedo * 0.52)
        return pt.Layers(alb, lay.height, np.clip(lay.rough + 0.02, 0, 1), lay.metal, f0=lay.f0, relief=lay.relief)

    def polymer(size, rng, pmm):
        """AK-74 grip polymer: the mould texture, faint flow lines, polished peaks where the hand rubs."""
        return pt.polymer_moulded(size, rng, pmm, colour=(0.094, 0.094, 0.100))

    def mag(size, rng, pmm):
        """Helical magazine polymer: the moulded texture, the feed spiral's faint diagonal ridges (a 28 mm pitch),
        glossy lanes along it where the hand holds it, dust settled in the grain."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.084, 0.084, 0.090))
        y, x = np.mgrid[0:size, 0:size] + 0.5
        pitch = 28.0 * pmm
        phase = ((x + 0.42 * y) / pitch) % 1.0
        ridge = pt.smoothstep(0.0, 0.10, phase) * (1.0 - pt.smoothstep(0.16, 0.30, phase))
        lane = pt.smoothstep(0.3, 1.3, pt.noise(size, rng, 2.5 / pmm, 34.0 / pmm))
        dust = pt.smoothstep(0.5, 1.6, pt.fbm(size, rng, size / 10, 3))
        h = lay.height + 0.010 * ridge
        rough = np.clip(lay.rough - 0.08 * lane + 0.05 * dust, 0, 1)
        alb = pt.lerp(lay.albedo, pt.srgb([0.15, 0.15, 0.15]), 0.18 * dust)
        relief = (lay.relief if lay.relief is not None else 0.0) + 0.004 * ridge
        return pt.Layers(alb, h, rough, lay.metal, f0=lay.f0, porosity=lay.porosity, relief=relief)

    return {
        "bizon/receiver": pt.SetDef(pt._r(receiver), 1024, 0.15, 1901, detail=0.5),
        "bizon/barrel": pt.SetDef(pt._r(barrel), 1024, 0.12, 1902, detail=0.55),
        "bizon/polymer": pt.SetDef(pt._r(polymer), 1024, 0.15, 1903, detail=0.6),
        "bizon/mag": pt.SetDef(pt._r(mag), 1024, 0.15, 1904, detail=0.55),
    }
