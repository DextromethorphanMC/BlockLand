"""SSG 08 texture sets (VIEWMODEL_SPEC sections 20, 23 and 40), plugged into pbr_textures.SETS through SET_MODULES:
hard-anodised dark grey aluminium for the chassis, fore-end and stock (milled, with the edges and handled spots
rubbed through to bright aluminium), the black nitrided stainless barrel and brake (lathe turning, carbon fouling),
the stippled polymer grip, the moulded polymer cheek piece and magazine. The bolt and small parts use
common/steel_dark, the butt pad common/rubber, the cases common/brass."""
import numpy as np


def anodized_alu(pt, size, rng, pmm, colour, wear=1.0):
    """Hard-anodised, bead-blasted 6061 aluminium in a dark grey: blast grain over face-milling arcs, a grey that
    drifts a little cool or warm, oily prints and smudges (glossier), scuffs crazing the coating lighter, and wear -
    rubbed-through spots and scratch bottoms showing bright aluminium (sparse: a tiling set cannot know the edges)."""
    blast = 0.7 * pt.noise(size, rng, 0.65) + 0.3 * pt.noise(size, rng, 1.5)
    mill = pt.milling(size, rng, pmm, pitch_mm=0.55, sweep=0.12)
    mott = pt.fbm(size, rng, size / 5, 4)
    hue = pt.fbm(size, rng, size / 3, 3)
    oil = np.clip(0.8 * pt.prints(size, rng, pmm, 3) + 0.6 * pt.smudges(size, rng, size / 8, 0.25), 0, 1)
    zone = pt.smudges(size, rng, size / 8, 0.28)
    scuff = pt.blur(pt.scratch_field(size, rng, pmm, 60, (3, 25), width=3, curve=0.5, cluster=zone,
                                     strength=(0.15, 0.45)), 1.2)
    sc = pt.scratch_field(size, rng, pmm, int(70 * wear), (0.4, 12), cluster=0.2 + 0.8 * zone, strength=(0.3, 1.0))
    rub = pt.smoothstep(0.55, 1.0, pt.smudges(size, rng, 0.8 / pmm, 0.0025 * wear))
    bare = np.maximum(pt.smoothstep(0.78, 0.98, sc), rub)
    dust = pt.specks(size, rng, pmm, 0.5)
    c = pt.srgb(colour)
    base = pt.lerp(c * np.array([0.97, 0.99, 1.04]), c * np.array([1.03, 1.0, 0.97]), pt.smoothstep(-1, 1, hue))
    alb = pt.tint(base, 0.045 * mott + 0.03 * blast + 0.012 * mill, 1.0)
    alb = pt.lerp(alb, c * 1.45 + 0.03, 0.55 * scuff)
    alb = pt.lerp(alb, c * 1.9 + 0.05, 0.5 * sc)
    metal = bare > 0.5
    alb = np.where(metal[..., None], pt.srgb([0.64, 0.65, 0.66]), alb)
    alb = pt.lerp(alb, pt.srgb([0.42, 0.40, 0.37]), 0.7 * dust)
    h = pt.groove(sc, 0.006) - 0.002 * scuff + 0.004 * dust - 0.003 * rub
    rough = np.where(metal, 0.30, 0.52 + 0.04 * blast + 0.03 * mott + 0.025 * mill - 0.18 * oil - 0.12 * scuff
                     - 0.10 * sc + 0.25 * dust)
    return pt.Layers(alb, h, rough, metal, f0=0.05, relief=0.0014 * blast + 0.0012 * mill)


def nitrided_barrel(pt, size, rng, pmm):
    """Black nitrided stainless barrel and brake: satin black with lathe turning lines round the barrel (constant u
    along the bore), a fine grain, grey carbon fouling blooms, spots rubbed to a dull steel sheen, a few scratches
    along the bore from cleaning rods and bipod legs."""
    lathe = 0.7 * pt.noise(size, rng, 0.55, 10.0 / pmm) + 0.3 * pt.noise(size, rng, 0.9, 3.0 / pmm)
    grain = pt.noise(size, rng, 0.7)
    mott = pt.fbm(size, rng, size / 5, 4)
    foul = pt.smoothstep(0.4, 1.8, pt.fbm(size, rng, size / 12, 5))
    rub = pt.smoothstep(0.35, 1.0, pt.smudges(size, rng, 1.5 / pmm, 0.015))
    sc = pt.scratch_field(size, rng, pmm, 70, (0.6, 16), angle=0, spread=0.25)
    oil = np.clip(0.6 * pt.prints(size, rng, pmm, 1) + 0.5 * pt.smudges(size, rng, size / 9, 0.18), 0, 1)
    alb = pt.tint(pt.srgb([0.165, 0.168, 0.178]), 0.04 * mott + 0.02 * lathe + 0.02 * grain, 1.0)
    alb = pt.lerp(alb, pt.srgb([0.22, 0.21, 0.20]), 0.35 * foul)
    alb = pt.lerp(alb, pt.srgb([0.36, 0.36, 0.37]), 0.45 * rub)
    alb = pt.lerp(alb, pt.srgb([0.44, 0.44, 0.45]), 0.5 * sc)
    h = pt.groove(sc, 0.004)
    rough = 0.36 + 0.04 * mott + 0.03 * lathe + 0.12 * foul - 0.08 * rub - 0.10 * sc - 0.10 * oil
    return pt.Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0018 * lathe + 0.0005 * grain)


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
    alu = lambda colour, wear=1.0: (lambda d, rng: anodized_alu(pt, d.size, rng, pt.px_mm(d), colour, wear))
    return {
        "ssg08/chassis": pt.SetDef(alu((0.165, 0.168, 0.175)), 1024, 0.15, 1801),
        "ssg08/foreend": pt.SetDef(alu((0.160, 0.163, 0.170), 1.3), 1024, 0.15, 1802),
        "ssg08/stock": pt.SetDef(alu((0.150, 0.152, 0.158), 1.2), 1024, 0.15, 1803),
        "ssg08/barrel": pt.SetDef(lambda d, rng: nitrided_barrel(pt, d.size, rng, pt.px_mm(d)), 1024, 0.12, 1804,
                                  detail=0.7),
        "ssg08/grip": pt.SetDef(pt._r(pt.stipple, colour=(0.115, 0.115, 0.12)), 1024, 0.15, 1805),
        "ssg08/cheek": pt.SetDef(_deep(pt, pt._r(pt.polymer_moulded, colour=(0.125, 0.125, 0.13))), 512, 0.15, 1806),
        "ssg08/mag": pt.SetDef(_deep(pt, pt._r(pt.polymer_moulded, colour=(0.135, 0.135, 0.14))), 512, 0.12, 1807),
    }
