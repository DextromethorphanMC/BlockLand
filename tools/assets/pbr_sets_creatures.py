"""Texture sets of the creatures (pbr_textures.SET_MODULES; tools/creatures/SHARK_SPEC.md section 4). The shark's skin:
fine dermal denticles (overlapping placoid scales of a fraction of a millimetre, raked back along the body — the "grain"
the normal map carries), soft mottling, a few healed scars and bite rakes, wet and only slightly glossy; the slate
blue-grey of the back and the off-white of the belly are two sets of the same recipe."""
import numpy as np


def sets(pt):
    def shark_skin(size, rng, pmm, colour, scars, mottle):
        # denticles: tiny ridged scales raked towards +u (back along the body): stretched noise plus fine ridges
        grain = pt.fbm(size, rng, 0.9 / pmm, 3, aniso=2.4)
        ridges = 0.5 + 0.5 * np.sin(np.linspace(0, size * pmm / 0.45 * 2 * np.pi, size)[None, :]
                                    + 2.5 * pt.noise(size, rng, 3.0 / pmm))
        mott = pt.fbm(size, rng, size / 5, 4)
        blotch = pt.smoothstep(0.35, 1.4, pt.fbm(size, rng, size / 9, 4))
        sc = pt.scratch_field(size, rng, pmm, int(6 + 40 * scars), (20, 180), spread=0.6, curve=0.5, strength=(0.2, 0.6))
        alb = pt.tint(pt.srgb(colour), mottle * (0.10 * mott - 0.08 * blotch) + 0.02 * grain, 1.0)
        alb = pt.lerp(alb, alb * 1.25 + 0.03, 0.55 * sc * scars)
        h = 0.02 * grain + 0.012 * ridges * pt.smoothstep(0.2, 0.8, pt.noise(size, rng, 6.0 / pmm)) - 0.03 * sc * scars
        rough = 0.46 + 0.06 * grain - 0.05 * mott + 0.10 * sc * scars
        return pt.Layers(alb, h, np.clip(rough, 0.2, 0.9), np.zeros((size, size), bool), f0=0.028, porosity=0.05,
                         relief=0.006 * grain)

    return {
        "creature/shark_back": pt.SetDef(pt._r(shark_skin, colour=[0.20, 0.25, 0.30], scars=0.6, mottle=1.0), 1024, 0.60,
                                         1201, detail=0.6),
        "creature/shark_belly": pt.SetDef(pt._r(shark_skin, colour=[0.76, 0.76, 0.72], scars=0.2, mottle=0.4), 1024, 0.60,
                                          1202, detail=0.4),
    }
