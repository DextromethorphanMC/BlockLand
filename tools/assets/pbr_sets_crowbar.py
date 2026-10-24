"""Texture sets of the crowbar (pbr_textures.SET_MODULES): the bar's enamel (red by default, black and rusted for the
skins) chipped and scuffed through to primer and steel - a bar that has been used: more and larger wear than a gun's
finish -, the ground bare steel of the claw and chisel (coarse grinder marks across, a blue-brown heat tint, nicks),
and the friction tape (a black cotton weave in rubbery adhesive, rubbed shiny where the hand sits)."""
import numpy as np


def sets(pt):
    def enamel(colour):
        def run(size, rng, pmm):
            lay = pt.painted_steel(size, rng, pmm, paint=colour, coverage=0.012)
            # knocks: larger chipped patches with the primer showing round bare steel
            knock = pt._wear_mask(size, rng, 0.010, size / 40)
            dent = pt.blur(knock, 2.0)
            lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.30, 0.30, 0.29]), pt.smoothstep(0.3, 0.6, dent))
            bare = pt.smoothstep(0.6, 0.8, dent)
            lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.50, 0.50, 0.51]), bare)
            lay.metal = lay.metal | (bare > 0.5)
            lay.rough = np.where(bare > 0.5, 0.36, lay.rough)
            lay.height = lay.height - 0.010 * pt.smoothstep(0.3, 0.6, dent)
            return lay
        return run

    def rusted(size, rng, pmm):
        """Bare steel gone to rust: an orange-brown scale over dark oxide, flaking (raised, rough), pitted, the odd
        flake of red paint left in the deepest pits."""
        scale = pt.fbm(size, rng, 2.5 / pmm, 5)
        blotch = pt.smoothstep(-0.4, 1.0, pt.fbm(size, rng, size / 5, 4))
        flakes = pt.smoothstep(0.55, 0.75, pt.norm01(pt.worley(size, rng, max(8, int(size * pmm / 2.5)))[0]))
        pit = pt.pits(size, rng, pmm, 6.0, (0.08, 0.35))
        paint = pt.smoothstep(0.85, 0.95, pt.norm01(pt.fbm(size, rng, size / 9, 3))) * (1 - blotch)
        dark = pt.srgb([0.17, 0.11, 0.08])
        orange = pt.srgb([0.45, 0.22, 0.10])
        alb = pt.lerp(dark, orange, np.clip(0.55 * blotch + 0.25 * scale + 0.2 * flakes, 0, 1))
        alb = pt.lerp(alb, alb * 0.55, 0.7 * pit)
        alb = pt.lerp(alb, pt.srgb([0.42, 0.07, 0.05]), 0.8 * paint)
        h = 0.012 * scale + 0.02 * flakes - 0.02 * pit
        rough = 0.80 + 0.06 * scale + 0.05 * flakes - 0.20 * paint
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.05, porosity=0.35,
                         relief=0.004 * scale)

    def ground(size, rng, pmm):
        """Ground bare steel: coarse grinder marks across the bar (along v), a faint heat tint, nicks and dings."""
        lines = 0.7 * pt.noise(size, rng, max(0.65, 0.25 / pmm), 9.0 / pmm) + 0.3 * pt.noise(size, rng, 0.65, 3.0 / pmm)
        heat = pt.smoothstep(0.4, 1.8, pt.fbm(size, rng, size / 4, 3))
        nick = pt.pits(size, rng, pmm, 2.0, (0.1, 0.4))
        sc = pt.scratch_field(size, rng, pmm, 60, (0.5, 8.0), strength=(0.2, 0.7))
        alb = pt.tint(pt.srgb([0.56, 0.56, 0.57]), 0.03 * lines, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.46, 0.42, 0.40]), 0.35 * heat)
        alb = pt.lerp(alb, alb * 0.7, 0.6 * nick)
        h = 0.003 * lines - 0.012 * nick + pt.groove(sc, 0.003)
        rough = 0.30 + 0.06 * lines + 0.06 * heat + 0.10 * nick - 0.05 * sc
        return pt.Layers(alb, h, rough, np.ones((size, size), bool), relief=0.0015 * lines)

    def tape(size, rng, pmm):
        """Cloth friction tape: a plain cotton weave filled with black adhesive, fuzz, polish where it is gripped."""
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(4, int(round(size * pmm / 0.45)))                      # ~0.45 mm threads
        warp = 0.5 + 0.5 * np.sin(2 * np.pi * n * x)
        weft = 0.5 + 0.5 * np.sin(2 * np.pi * n * y)
        cross = np.where(((np.floor(n * x * 2) + np.floor(n * y * 2)) % 2) > 0, warp, weft)
        fuzz = pt.noise(size, rng, 0.7)
        rub = pt.smudges(size, rng, size / 6, 0.35)
        grime = pt.smoothstep(0.4, 1.6, pt.fbm(size, rng, size / 8, 3))
        alb = pt.tint(pt.srgb([0.045, 0.045, 0.047]), 0.15 * cross + 0.05 * fuzz, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.16, 0.15, 0.13]), 0.25 * grime)
        h = 0.020 * cross + 0.004 * fuzz
        rough = 0.86 - 0.20 * rub + 0.04 * fuzz + 0.03 * grime
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04, relief=0.002 * fuzz)

    return {
        "crowbar/paint_red": pt.SetDef(pt._r(enamel((0.58, 0.075, 0.055))), 1024, 0.15, 831),
        "crowbar/paint_black": pt.SetDef(pt._r(enamel((0.075, 0.075, 0.08))), 1024, 0.15, 832),
        "crowbar/rust": pt.SetDef(pt._r(rusted), 512, 0.15, 833),
        "crowbar/ends": pt.SetDef(pt._r(ground), 512, 0.05, 834),
        "crowbar/tape": pt.SetDef(pt._r(tape), 512, 0.05, 835),
    }
