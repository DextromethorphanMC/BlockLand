"""Texture sets of the Uzi (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES):
the charcoal phosphate of the stamped receiver, cover, frame and stock, the black-oxide barrel, the diamond knurl of
the barrel nut and the cocking knob, the black moulded polymer of the handguard and grips, the magazines' painted
steel. Built at real scale from pbr_textures' recipes and helpers.

python -I tools/assets/pbr_textures.py uzi/"""
import numpy as np


def sets(pt):
    def parkerized(size, rng, pmm):
        """Manganese phosphate over stamped sheet steel: the common parkerised crystals, oil and handling marks
        recoloured to the Uzi's cool charcoal grey; the stamping leaves a gentle waviness in the reflections and the
        phosphate holds a little more oil (a soft sheen where hands rub)."""
        lay = pt.parkerized(size, rng, pmm)
        coat = ~lay.metal
        cool = np.array([0.94, 0.98, 1.06])
        alb = np.where(coat[..., None], lay.albedo * cool, lay.albedo)
        sheet = pt.fbm(size, rng, 14.0 / pmm, 3)
        sheen = pt.smudges(size, rng, size / 6, 0.30)
        rough = np.where(coat, np.clip(lay.rough - 0.05 - 0.06 * sheen, 0.0, 1.0), lay.rough)
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=0.045, relief=lay.relief + 0.018 * sheet)

    def oxide(size, rng, pmm):
        """Black-oxide barrel steel: the dark steel's grinding along u, turned a deeper blue-black and a little
        smoother; rubbed spots stay bright."""
        lay = pt.steel_dark(size, rng, pmm)
        bright = lay.albedo.mean(-1, keepdims=True) > 0.40
        alb = np.where(bright, lay.albedo, lay.albedo * np.array([0.80, 0.82, 0.88]))
        rough = np.clip(lay.rough - 0.03, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, relief=lay.relief)

    def knurl(size, rng, pmm):
        """Diamond knurl (0.8 mm pitch, crossed at 90 degrees) cut in blued steel: pyramids with flattened tops,
        the tops polished bright where fingers pull, dark oily grooves, the odd crushed point."""
        y, x = np.mgrid[0:size, 0:size] / float(size)
        n = max(4, int(round(size * pmm / 0.8)))                       # diamonds per tile: whole, so it tiles
        tri = lambda t: np.abs((t % 1.0) - 0.5) * 2.0                 # 1 on a groove line, 0 between
        h = np.minimum(1.0 - tri((x + y) * n), 1.0 - tri((x - y) * n))
        flat = np.minimum(h, 0.78) / 0.78
        worn = pt.smudges(size, rng, size / 6, 0.45)
        crush = pt.smoothstep(0.75, 1.0, pt.smudges(size, rng, 1.2 / pmm, 0.03))
        lay = pt.steel_dark(size, rng, pmm)
        top = pt.smoothstep(0.85, 1.0, flat)
        alb = pt.lerp(lay.albedo * 0.82, pt.srgb([0.34, 0.34, 0.355]), (0.25 + 0.40 * worn)[..., None] * top[..., None])
        alb = pt.lerp(alb, alb * 0.55, (1.0 - pt.smoothstep(0.0, 0.35, flat))[..., None])
        height = 0.22 * flat - 0.05 * crush + lay.height
        rough = np.clip(0.42 - 0.16 * top * (0.4 + 0.6 * worn) + 0.10 * (1 - flat) + 0.05 * crush, 0.08, 1.0)
        return pt.Layers(alb, height, rough, np.ones((size, size), bool), relief=lay.relief)

    def polymer(size, rng, pmm):
        """Black glass-filled polymer (handguard, grip panels): the moulded EDM texture, matte, polished a little
        on the peaks where hands rub, dust in the grain."""
        return pt.polymer_moulded(size, rng, pmm, colour=(0.098, 0.098, 0.104))

    def mag(size, rng, pmm):
        """Magazine bodies: dark grey enamel over phosphated sheet steel with lanes of insertion wear along their
        length (vertical) and sparse chips."""
        return pt.painted_steel(size, rng, pmm, paint=(0.150, 0.152, 0.158), coverage=0.0022, vertical=True)

    return {
        "uzi/parkerized": pt.SetDef(pt._r(parkerized), 1024, 0.12, 4101, 0.55),
        "uzi/barrel": pt.SetDef(pt._r(oxide), 1024, 0.10, 4102, 0.45),
        "uzi/knurl": pt.SetDef(pt._r(knurl), 512, 0.02, 4103, 0.35),
        "uzi/polymer": pt.SetDef(pt._r(polymer), 1024, 0.15, 4104, 0.6),
        "uzi/mag": pt.SetDef(pt._r(mag), 1024, 0.12, 4105, 0.5),
    }
