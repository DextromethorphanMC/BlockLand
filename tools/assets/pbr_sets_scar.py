"""Texture sets of the SCAR-H (VIEWMODEL_SPEC sections 20, 21 and 40), plugged into pbr_textures.SETS (SET_MODULES):
the classic two-tone - flat dark earth hard-anodised aluminium (upper, rails), FDE glass-filled polymer (lower, stock)
with its pebbled grip, black phosphated steel (barrel, gas block, flash hider, small steel parts) and the black coated
steel magazines. Built from the house recipes and helpers in pbr_textures.py; wear shows where hands and gear rub."""
import numpy as np

FDE_ALU = (0.480, 0.395, 0.290)        # sRGB albedo of the anodised upper (a little warmer and darker)
FDE_POLY = (0.500, 0.432, 0.322)       # the polymer lower / stock (paler, a touch greener)


def _deep(pt, recipe, k=4.0):
    """The shared moulded-polymer recipe with its EDM grain k times deeper (0.01 -> 0.04 mm): deep enough for the
    parallax and the micro-shadows to show the mould texture at viewmodel distance (VIEWMODEL_SPEC section 53).
    recipe None -> a function deepening Layers directly."""
    import dataclasses

    def deepen(lay):
        return dataclasses.replace(lay, height=lay.height * k)
    if recipe is None:
        return deepen
    return lambda d, rng: deepen(recipe(d, rng))


def sets(pt):
    def anodized_fde(size, rng, pmm):
        """Type III hard anodise, dyed flat dark earth, on bead-blasted extruded aluminium: fine blast grain and faint
        extrusion lines, a tone that drifts between the dye batches, oily fingerprints and smudges (glossier),
        rubbed-through spots and scratches crazing the coat paler, the deepest ones bright aluminium, dust."""
        blast = 0.7 * pt.noise(size, rng, 0.65) + 0.3 * pt.noise(size, rng, 1.5)
        extrude = pt.noise(size, rng, 40.0 / pmm, 0.35 / pmm)                 # die lines along u
        mott = pt.fbm(size, rng, size / 5, 4)
        hue = pt.fbm(size, rng, size / 3, 3)
        oil = np.clip(0.8 * pt.prints(size, rng, pmm, 3) + 0.6 * pt.smudges(size, rng, size / 8, 0.25), 0, 1)
        zone = pt.smudges(size, rng, size / 7, 0.30)
        scuff = pt.blur(pt.scratch_field(size, rng, pmm, 60, (3, 25), width=3, curve=0.5, cluster=zone,
                                         strength=(0.15, 0.5)), 1.2)
        sc = pt.scratch_field(size, rng, pmm, 70, (0.4, 12), cluster=0.2 + 0.8 * zone, strength=(0.3, 1.0))
        rub = pt._wear_mask(size, rng, 0.004, size / 80)                        # rubbed-through spots
        wear = np.maximum(sc, pt.blur(rub, 1.0))
        bare = pt.smoothstep(0.72, 0.96, wear)
        dust = pt.specks(size, rng, pmm, 0.8)
        base = pt.lerp(pt.srgb(FDE_ALU), pt.srgb([0.462, 0.388, 0.292]), pt.smoothstep(-1, 1, hue))
        alb = pt.tint(base, 0.045 * mott + 0.030 * blast + 0.010 * extrude, 1.0)
        alb = pt.lerp(alb, alb * 1.10 + 0.03, 0.55 * scuff)
        alb = pt.lerp(alb, pt.srgb([0.62, 0.56, 0.47]), 0.45 * sc)
        metal = bare > 0.5
        alb = np.where(metal[..., None], pt.srgb([0.72, 0.72, 0.73]), alb)
        alb = pt.lerp(alb, alb * 0.78, 0.35 * oil)
        alb = pt.lerp(alb, pt.srgb([0.66, 0.60, 0.50]), 0.6 * dust)
        h = pt.groove(sc, 0.006) - 0.003 * scuff - 0.008 * pt.blur(rub, 1.0) + 0.004 * dust
        rough = np.where(metal, 0.30, 0.56 + 0.04 * blast + 0.03 * mott - 0.20 * oil - 0.12 * scuff - 0.08 * sc
                         + 0.22 * dust)
        tool = pt.milling(size, rng, pmm, pitch_mm=0.6, sweep=0.06)
        rough = rough + 0.02 * tool
        return pt.Layers(alb, h, rough, metal, f0=0.05,
                         relief=0.0014 * blast + 0.0008 * tool + 0.0010 * extrude)

    def polymer_fde(size, rng, pmm):
        """The FDE glass-filled polymer of the lower and the stock (the house moulded polymer recipe)."""
        return _deep(pt, None)(pt.polymer_moulded(size, rng, pmm, colour=FDE_POLY))

    def grip_fde(size, rng, pmm):
        """The grip's moulded pebble texture in the polymer's colour."""
        return pt.stipple(size, rng, pmm, colour=(FDE_POLY[0] * 0.97, FDE_POLY[1] * 0.97, FDE_POLY[2] * 0.97))

    def phosphate_black(size, rng, pmm):
        """Black manganese phosphate (barrel, gas block, flash hider, steel parts): the parkerising recipe's crystal
        structure, oil and burnish, taken down to a near-black with a faint warm cast; scratches stay grey steel."""
        lay = pt.parkerized(size, rng, pmm)
        lum = lay.albedo.mean(-1, keepdims=True)
        bright = lum > 0.40                                               # bare steel in the deep scratches
        alb = np.where(bright, lay.albedo, lay.albedo * (pt.srgb([0.080, 0.078, 0.076]) / 0.205))
        rough = np.where(lay.metal, lay.rough, np.clip(lay.rough - 0.04, 0.0, 1.0))
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=0.045, relief=lay.relief)

    def mag_black(size, rng, pmm):
        """Black coated steel magazine body: the painted sheet recipe with insertion wear along the body."""
        return pt.painted_steel(size, rng, pmm, paint=(0.085, 0.085, 0.088), coverage=0.003, vertical=True)

    return {
        "scar/anodized": pt.SetDef(pt._r(anodized_fde), 1024, 0.15, 1401, 0.5),
        "scar/polymer": pt.SetDef(pt._r(polymer_fde), 1024, 0.20, 1402, 0.6),
        "scar/grip": pt.SetDef(pt._r(grip_fde), 1024, 0.15, 1403, 0.35),
        "scar/barrel": pt.SetDef(pt._r(phosphate_black), 1024, 0.12, 1404, 0.55),
        "scar/mag": pt.SetDef(pt._r(mag_black), 1024, 0.15, 1405, 0.5),
    }
