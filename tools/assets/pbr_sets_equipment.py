"""Texture sets of the equipment (VIEWMODEL_SPEC sections 20, 21 and 36), plugged into pbr_textures.SETS
(SET_MODULES): the grenades' olive enamel and zinc-plated levers, stencilled bands, the molotov's rag, the throwing
knife's paracord wrap, the injector's moulded polymers, and the camo skins of the equipment (woodland, desert, arctic,
tiger stripe: enamel over sheet steel, the same paint used on the knife blades and injector barrels)."""
import numpy as np


def sets(pt):
    def olive(size, rng, pmm):
        """Olive drab enamel on a pressed steel body (the M67 sphere, the M84 tube, the M18 can)."""
        lay = pt.painted_steel(size, rng, pmm, paint=(0.205, 0.235, 0.150), coverage=0.004)
        # Cast shell under enamel: broad casting grain, deliberately unlike stamped bands.
        cast = pt.noise(size, rng, 0.75 / pmm)
        lay.relief += 0.009 * cast
        lay.rough = np.clip(lay.rough + 0.13 + 0.025 * cast, 0.08, 0.95)
        return lay

    def zinc(size, rng, pmm):
        """Zinc-plated spring steel (the levers): a blasted matt steel with the yellowish cast of the plating."""
        lay = pt.stainless_blasted(size, rng, pmm)
        alb = lay.albedo * np.array([1.0, 0.975, 0.89])
        return pt.Layers(alb, lay.height, np.clip(lay.rough + 0.05, 0, 1), lay.metal, relief=lay.relief)

    def band(colour):
        def recipe(size, rng, pmm):
            """A painted band on the can, its edges and the stencil letters rubbed (sparse wear)."""
            return pt.painted_steel(size, rng, pmm, paint=colour, coverage=0.006)
        return recipe

    def rag(size, rng, pmm):
        """Cotton rag: a plain weave (about 1.2 threads per mm), frayed fibres, fuel soaking dark stains into it and a
        sooty scorch towards its end."""
        k = max(8, int(round(size * pmm / 0.85)))
        y, x = np.mgrid[0:size, 0:size] / size * 2 * np.pi * k
        warp = 0.5 + 0.5 * np.sin(x)
        weft = 0.5 + 0.5 * np.sin(y)
        over = np.where(np.sin(x / 2 + np.pi / 4) * np.sin(y / 2 + np.pi / 4) > 0, warp, weft)
        fibres = pt.noise(size, rng, 0.7, 3.0)
        stain = pt.smoothstep(-0.2, 1.4, pt.fbm(size, rng, size / 5, 4))
        soot = pt.smoothstep(0.6, 1.8, pt.fbm(size, rng, size / 7, 4))
        base = pt.srgb([0.66, 0.60, 0.48])
        alb = pt.tint(base, 0.10 * (over - 0.5) + 0.05 * fibres, 1.0)
        alb = pt.lerp(alb, alb * np.array([0.62, 0.52, 0.38]), 0.55 * stain)
        alb = pt.lerp(alb, pt.srgb([0.09, 0.08, 0.07]), 0.7 * soot)
        h = 0.06 * over + 0.01 * fibres
        rough = 0.92 - 0.12 * stain
        return pt.Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.035, porosity=0.6)

    def paracord(size, rng, pmm):
        """Black 550 cord in tight turns (one turn about 4.7 mm, as the grip's ridges) with a diagonal braid sheath."""
        y, x = np.mgrid[0:size, 0:size] / size
        turns = max(4, int(round(size * pmm / 4.7)))
        ridge = 0.5 + 0.5 * np.cos(2 * np.pi * turns * y)
        k = max(8, int(round(size * pmm / 0.9)))
        braid = np.sin(2 * np.pi * k * (x + y)) * np.sin(2 * np.pi * k * (x - y))
        fuzz = pt.noise(size, rng, 0.8)
        alb = pt.tint(pt.srgb([0.055, 0.055, 0.058]), 0.25 * braid + 0.1 * fuzz, 1.0)
        h = 0.25 * ridge + 0.04 * braid
        rough = 0.86 + 0.04 * braid
        return pt.Layers(alb, h, rough, np.zeros((size, size), bool), f0=0.04, porosity=0.3)

    def camo(cols, scale_mm, stripes=False):
        def recipe(size, rng, pmm):
            """Camo enamel: blotches (or brushed stripes) in the scheme's colours over the painted steel's sheen,
            orange peel and handling wear; the pattern is warped so it never reads as noise."""
            lay = pt.painted_steel(size, rng, pmm, paint=cols[0], coverage=0.003)
            px = scale_mm / pmm
            wx, wy = pt.fbm(size, rng, px * 0.8, 3), pt.fbm(size, rng, px * 0.8, 3)
            if stripes:
                f = pt.fbm(size, rng, px, 4, aniso=5.0)
            else:
                f = pt.fbm(size, rng, px, 4)
            f = pt.warp(f, 0.25 * px * wx, 0.25 * px * wy)
            qs = np.quantile(f, np.linspace(0, 1, len(cols) + 1)[1:-1])
            idx = np.digitize(f, qs)
            alb = np.asarray(cols, float)[idx]
            edge = 1 - pt.smoothstep(0.0, 0.02, np.abs(f[..., None] - qs).min(-1))   # a soft overspray edge
            alb = alb * (1 + 0.04 * pt.fbm(size, rng, size / 8, 3))[..., None]
            soft = np.stack([pt.blur(alb[..., c], 1.2) for c in range(3)], -1)
            alb = pt.lerp(alb, soft, 0.6 * edge)
            # keep the paint's wear: primer and bare steel where the plain paint had them
            worn = (lay.albedo.mean(-1) - np.asarray(cols[0]).mean()) > 0.08
            alb = np.where(worn[..., None], lay.albedo, alb)
            return pt.Layers(alb, lay.height, lay.rough, lay.metal, f0=0.045, porosity=lay.porosity,
                             relief=lay.relief)
        return recipe

    def polymer(colour):
        def recipe(size, rng, pmm):
            return pt.polymer_moulded(size, rng, pmm, colour=colour)
        return recipe

    def label_print(size, rng, pmm):
        """The injector's printed label: glossy film over the moulded barrel, faint print lines and scuffs."""
        lay = pt.polymer_textured(size, rng, colour=(0.80, 0.62, 0.12))
        y = np.mgrid[0:size, 0:size][0] / size
        lines = (np.sin(2 * np.pi * 9 * y) > 0.86).astype(float)
        alb = pt.lerp(lay.albedo, pt.srgb([0.12, 0.10, 0.06]), 0.55 * lines)
        return pt.Layers(alb, lay.height * 0.3, np.clip(lay.rough - 0.30, 0, 1), lay.metal, f0=0.045)

    WOODLAND = [(0.20, 0.235, 0.150), (0.31, 0.33, 0.20), (0.24, 0.18, 0.11), (0.07, 0.07, 0.06)]
    DESERT = [(0.56, 0.48, 0.34), (0.66, 0.58, 0.42), (0.44, 0.34, 0.23)]
    ARCTIC = [(0.80, 0.81, 0.82), (0.58, 0.60, 0.62), (0.30, 0.31, 0.33)]
    TIGER = [(0.30, 0.32, 0.18), (0.08, 0.08, 0.07), (0.45, 0.42, 0.26)]
    return {
        "equipment/olive": pt.SetDef(pt._r(olive), 512, 0.12, 901),
        "equipment/zinc": pt.SetDef(pt._r(zinc), 512, 0.06, 902),
        "equipment/band_flash": pt.SetDef(pt._r(band((0.60, 0.53, 0.24))), 512, 0.10, 903),
        "equipment/band_smoke": pt.SetDef(pt._r(band((0.52, 0.60, 0.28))), 512, 0.10, 904),
        "equipment/rag": pt.SetDef(pt._r(rag), 512, 0.06, 905),
        "equipment/paracord": pt.SetDef(pt._r(paracord), 512, 0.0564, 906),
        "equipment/pen_barrel": pt.SetDef(pt._r(polymer((0.74, 0.73, 0.68))), 512, 0.10, 907),
        "equipment/pen_label": pt.SetDef(pt._r(label_print), 512, 0.10, 908),
        "equipment/pen_orange": pt.SetDef(pt._r(polymer((0.86, 0.36, 0.06))), 512, 0.075, 909),
        "equipment/pen_blue": pt.SetDef(pt._r(polymer((0.10, 0.25, 0.70))), 512, 0.075, 910),
        "skin/camo/woodland": pt.SetDef(pt._r(camo(WOODLAND, 9.0)), 512, 0.15, 921),
        "skin/camo/desert": pt.SetDef(pt._r(camo(DESERT, 11.0)), 512, 0.15, 922),
        "skin/camo/arctic": pt.SetDef(pt._r(camo(ARCTIC, 10.0)), 512, 0.15, 923),
        "skin/camo/tiger": pt.SetDef(pt._r(camo(TIGER, 7.0, stripes=True)), 512, 0.15, 924),
    }
