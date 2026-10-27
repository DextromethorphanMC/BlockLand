"""Texture sets of the vehicles (pbr_textures.SET_MODULES, VIEWMODEL_SPEC section 57). Vehicle-sized repeats (tens of
centimetres, not the weapons' few): automotive paints (gloss red with clear-coat orange peel for the motorcycle, a
matte sand for the 4x4, a worn olive drab for the tank: cast and rolled armour texture, chips to primer and steel,
dust settled in the low spots), the boat's gelcoat and antifouling, powder-coated frame black, sand-cast aluminium,
tyre rubber, the track's steel links with their rubber pads (two links a repeat: the runtime scrolls v), seat vinyl,
canvas, textured plastic, non-skid deck and heat-tinted exhaust steel."""
import numpy as np


def sets(pt):
    def paint(size, rng, pmm, colour, gloss, wear, dust, peel_mm=1.2, cast=0.0):
        peel = 0.6 * pt.noise(size, rng, peel_mm / pmm) + 0.4 * pt.noise(size, rng, 2.5 * peel_mm / pmm)
        sheet = pt.fbm(size, rng, 60.0 / pmm, 3)
        mott = pt.fbm(size, rng, size / 4, 4)
        sc = pt.scratch_field(size, rng, pmm, int(40 + 160 * wear), (2, 60), spread=np.pi, curve=0.4,
                              strength=(0.15, 0.6))
        chips = pt._wear_mask(size, rng, 0.002 + 0.02 * wear, size / 70)
        w = np.maximum(sc * wear * 1.5, pt.blur(chips, 1.2))
        primer = pt.smoothstep(0.45, 0.55, w)
        bare = pt.smoothstep(0.8, 0.9, w)
        metal = bare > 0.5
        grime = pt.smoothstep(0.2, 1.4, pt.fbm(size, rng, size / 6, 4)) * dust
        specks = pt.specks(size, rng, pmm, 0.5 + 3 * dust)
        alb = pt.tint(pt.srgb(colour), 0.05 * mott + 0.01 * peel, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.30, 0.30, 0.29]), primer)
        alb = np.where(metal[..., None], pt.tint(pt.srgb([0.48, 0.48, 0.49]), pt.noise(size, rng, 2), 0.05), alb)
        alb = pt.lerp(alb, pt.srgb([0.42, 0.38, 0.30]), 0.45 * grime + 0.4 * specks)
        relief = 0.02 * sheet + 0.003 * peel
        h = -0.03 * primer - 0.01 * bare + 0.004 * specks
        if cast:
            pits = pt.pits(size, rng, pmm, 2.0, (0.3, 1.2))
            rough_cast = pt.fbm(size, rng, 6.0 / pmm, 4)
            h = h + cast * (0.10 * rough_cast - 0.06 * pits)
            alb = pt.lerp(alb, alb * 0.8, 0.4 * pits)
        rough = gloss + 0.04 * mott - 0.04 * pt.smudges(size, rng, size / 6, 0.3) + 0.35 * grime + 0.25 * specks
        rough = np.where(metal, 0.35, rough * (1 - primer) + 0.65 * primer)
        return pt.Layers(alb, h, np.clip(rough, 0.03, 1), metal, f0=0.05, porosity=0.1 * primer + 0.2 * grime,
                         relief=relief)

    def gelcoat(size, rng, pmm, colour, gloss):
        peel = pt.noise(size, rng, 1.6 / pmm)
        wave = pt.fbm(size, rng, 120.0 / pmm, 3)
        craze = pt.scratch_field(size, rng, pmm, 30, (3, 25), spread=np.pi, curve=0.8, strength=(0.1, 0.4))
        algae = pt.smoothstep(0.4, 1.5, pt.fbm(size, rng, size / 5, 4))
        alb = pt.tint(pt.srgb(colour), 0.02 * pt.fbm(size, rng, size / 4, 4), 1.0)
        alb = pt.lerp(alb, pt.srgb([0.55, 0.53, 0.45]), 0.12 * algae + 0.3 * craze)
        rough = gloss + 0.03 * peel + 0.10 * algae + 0.15 * craze
        return pt.Layers(alb, pt.groove(craze, 0.004), np.clip(rough, 0.03, 1), np.zeros((size, size), bool), f0=0.045,
                         relief=0.03 * wave + 0.002 * peel)

    def cast_aluminium(size, rng, pmm):
        sand = pt.noise(size, rng, 0.35 / pmm) * 0.6 + pt.noise(size, rng, 1.0 / pmm) * 0.4
        mott = pt.fbm(size, rng, size / 5, 4)
        ox = pt.smoothstep(0.3, 1.4, pt.fbm(size, rng, size / 7, 4))
        sc = pt.scratch_field(size, rng, pmm, 50, (1, 20), strength=(0.2, 0.7))
        alb = pt.tint(pt.srgb([0.60, 0.61, 0.62]), 0.05 * mott + 0.04 * sand, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.42, 0.41, 0.38]), 0.35 * ox)
        alb = pt.lerp(alb, pt.srgb([0.80, 0.80, 0.81]), 0.5 * sc)
        rough = 0.50 + 0.08 * sand + 0.12 * ox - 0.25 * sc
        return pt.Layers(alb, 0.03 * sand + pt.groove(sc, 0.006), np.clip(rough, 0, 1), np.ones((size, size), bool),
                         relief=0.006 * sand)

    def tyre(size, rng, pmm):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(4, int(round(size * pmm / 28)))                    # ~28 mm tread blocks
        bx, by = (x * n) % 1, (y * n + 0.5 * (np.floor(x * n) % 2)) % 1
        block = (np.minimum(bx, 1 - bx) > 0.12) & (np.minimum(by, 1 - by) > 0.10)
        siping = (np.abs(((bx * 3) % 1) - 0.5) < 0.04) & block
        grain = pt.noise(size, rng, 0.4 / pmm)
        dirt = pt.smoothstep(0.3, 1.4, pt.fbm(size, rng, size / 6, 4))
        h = 1.2 * pt.blur(block.astype(float), 1.0) - 0.4 * siping + 0.02 * grain
        alb = pt.tint(pt.srgb([0.035, 0.035, 0.036]), 0.03 * grain, 1.0)
        alb = pt.lerp(alb, pt.srgb([0.30, 0.27, 0.22]), 0.25 * dirt * (1 - block))
        rough = 0.82 + 0.06 * grain - 0.10 * block * (1 - dirt) + 0.08 * dirt
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04, relief=0.004 * grain)

    def track(size, rng, pmm):
        """Two links a repeat along v (image rows): a steel shoe with a raised grouser bar across it, the pin bosses
        at its ends, a rubber pad in the middle; worn bright on the grouser edges, mud packed in the recesses."""
        y, x = np.mgrid[0:size, 0:size] / size
        link = (y * 2) % 1
        grouser = np.abs(link - 0.5) < 0.07
        joint = (link < 0.05) | (link > 0.95)
        pad = (np.abs(x - 0.5) < 0.18) & (np.abs(link - 0.5) > 0.12) & ~joint
        boss = ((np.abs(x - 0.08) < 0.06) | (np.abs(x - 0.92) < 0.06)) & ((link < 0.12) | (link > 0.88))
        grain = pt.noise(size, rng, 0.5 / pmm)
        mud = pt.smoothstep(0.2, 1.3, pt.fbm(size, rng, size / 6, 4))
        h = 2.0 * pt.blur(grouser.astype(float), 1.5) + 0.8 * boss - 1.2 * joint - 0.3 * pad + 0.05 * grain
        edge = pt.blur(grouser.astype(float), 3.0) * (1 - grouser)
        metal = ~pad
        alb = pt.tint(pt.srgb([0.20, 0.19, 0.18]), 0.06 * grain, 1.0)
        alb = np.where(pad[..., None], pt.srgb([0.04, 0.04, 0.04]), alb)
        alb = pt.lerp(alb, pt.srgb([0.55, 0.55, 0.56]), 0.6 * grouser * (1 - mud) + 0.3 * edge)
        alb = pt.lerp(alb, pt.srgb([0.33, 0.28, 0.22]), 0.6 * mud * (1 - grouser) + 0.5 * joint)
        rough = np.where(pad, 0.85, 0.55 - 0.25 * grouser * (1 - mud) + 0.3 * mud)
        return pt.Layers(alb, h, np.clip(rough, 0, 1), metal, f0=0.04, porosity=0.4 * mud, relief=0.01 * grain)

    def vinyl(size, rng, pmm, colour):
        f1, f2, _ = pt.worley(size, rng, max(8, int(size * pmm / 1.6)))
        grain = 1.0 - np.clip((f2 - f1) * 4.0, 0, 1)               # creases between the grain's cells
        g2 = pt.noise(size, rng, 0.5 / pmm)
        wear = pt.smudges(size, rng, size / 6, 0.3)
        alb = pt.tint(pt.srgb(colour), 0.04 * g2 + 0.03 * grain, 1.0)
        alb = pt.lerp(alb, alb * 1.15 + 0.01, 0.3 * wear)
        rough = 0.55 + 0.05 * grain - 0.12 * wear
        return pt.Layers(alb, 0.02 * grain + 0.005 * g2, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04,
                         relief=0.004 * g2)

    def canvas(size, rng, pmm, colour):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(8, int(round(size * pmm / 1.0)))
        weave = np.where(((np.floor(n * x * 2) + np.floor(n * y * 2)) % 2) > 0, 0.5 + 0.5 * np.sin(2 * np.pi * n * x),
                         0.5 + 0.5 * np.sin(2 * np.pi * n * y))
        stain = pt.smoothstep(0.2, 1.4, pt.fbm(size, rng, size / 5, 4))
        alb = pt.tint(pt.srgb(colour), 0.06 * weave + 0.05 * pt.fbm(size, rng, size / 4, 4), 1.0)
        alb = pt.lerp(alb, pt.srgb([0.30, 0.27, 0.20]), 0.3 * stain)
        return pt.Layers(alb, 0.03 * weave, np.clip(0.88 + 0.04 * weave, 0, 1), np.zeros((size, size), bool), f0=0.04,
                         relief=0.004 * weave)

    def nonskid(size, rng, pmm):
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(6, int(round(size * pmm / 6.0)))                    # 6 mm diamond pattern
        d = np.abs(((x + y) * n) % 1 - 0.5) + np.abs(((x - y) * n) % 1 - 0.5)
        bump = pt.smoothstep(0.55, 0.35, d)
        dirt = pt.smoothstep(0.3, 1.4, pt.fbm(size, rng, size / 5, 4))
        alb = pt.tint(pt.srgb([0.86, 0.86, 0.84]), 0.02 * pt.noise(size, rng, 1.0), 1.0)
        alb = pt.lerp(alb, pt.srgb([0.55, 0.53, 0.48]), 0.25 * dirt * (1 - bump))
        return pt.Layers(alb, 0.25 * bump, np.clip(0.55 + 0.25 * bump + 0.1 * dirt, 0, 1), np.zeros((size, size), bool),
                         f0=0.045)

    def exhaust(size, rng, pmm):
        heat = pt.fbm(size, rng, size / 3, 4)
        t = np.clip(0.5 + 0.8 * heat, 0, 1)
        alb = pt.palette(t, [(0.0, [0.55, 0.53, 0.50]), (0.35, [0.62, 0.48, 0.28]), (0.6, [0.42, 0.30, 0.45]),
                             (0.85, [0.30, 0.36, 0.55]), (1.0, [0.35, 0.33, 0.32])])
        alb = pt.lerp(pt.srgb([0.46, 0.45, 0.44]), alb, 0.45)               # a faint temper tint, not a rainbow
        soot = pt.smoothstep(0.4, 1.4, pt.fbm(size, rng, size / 6, 4))
        alb = pt.lerp(alb, pt.srgb([0.08, 0.08, 0.08]), 0.5 * soot)
        brush = pt.noise(size, rng, 0.3 / pmm, 6.0 / pmm)
        rough = 0.35 + 0.30 * soot + 0.05 * brush
        return pt.Layers(alb, 0.004 * brush, np.clip(rough, 0, 1), soot < 0.6, relief=0.003 * brush)

    def plastic(size, rng, pmm):
        return pt.polymer_textured(size, rng, colour=(0.05, 0.05, 0.052))

    return {
        "vehicle/paint_red": pt.SetDef(pt._r(paint, colour=[0.62, 0.05, 0.04], gloss=0.14, wear=0.15, dust=0.15), 1024,
                                       0.60, 1101, detail=0.3),
        "vehicle/paint_sand": pt.SetDef(pt._r(paint, colour=[0.62, 0.53, 0.38], gloss=0.48, wear=0.5, dust=0.6), 1024,
                                        0.60, 1102, detail=0.5),
        "vehicle/paint_olive": pt.SetDef(pt._r(paint, colour=[0.27, 0.29, 0.17], gloss=0.62, wear=0.9, dust=0.8,
                                               cast=1.0), 1024, 0.80, 1103),
        "vehicle/paint_black": pt.SetDef(pt._r(paint, colour=[0.025, 0.025, 0.028], gloss=0.22, wear=0.25, dust=0.2),
                                         512, 0.30, 1104, detail=0.3),
        "vehicle/paint_navy": pt.SetDef(pt._r(paint, colour=[0.03, 0.07, 0.20], gloss=0.12, wear=0.05, dust=0.05),
                                        512, 0.50, 1105, detail=0.3),
        "vehicle/antifouling": pt.SetDef(pt._r(paint, colour=[0.36, 0.06, 0.05], gloss=0.70, wear=0.2, dust=0.5,
                                               peel_mm=2.5), 512, 0.60, 1106),
        "vehicle/gelcoat": pt.SetDef(pt._r(gelcoat, colour=[0.92, 0.92, 0.90], gloss=0.08), 1024, 1.00, 1107,
                                     detail=0.3),
        "vehicle/cast_aluminium": pt.SetDef(pt._r(cast_aluminium), 512, 0.20, 1108),
        "vehicle/tyre": pt.SetDef(pt._r(tyre), 1024, 0.25, 1109),
        "vehicle/track": pt.SetDef(pt._r(track), 1024, 0.34, 1110),
        "vehicle/vinyl_black": pt.SetDef(pt._r(vinyl, colour=[0.045, 0.040, 0.036]), 512, 0.20, 1111),
        "vehicle/vinyl_white": pt.SetDef(pt._r(vinyl, colour=[0.80, 0.79, 0.76]), 512, 0.20, 1112),
        "vehicle/canvas": pt.SetDef(pt._r(canvas, colour=[0.36, 0.34, 0.24]), 512, 0.12, 1113),
        "vehicle/nonskid": pt.SetDef(pt._r(nonskid), 512, 0.15, 1114),
        "vehicle/exhaust": pt.SetDef(pt._r(exhaust), 512, 0.25, 1115),
        "vehicle/plastic": pt.SetDef(pt._r(plastic), 512, 0.12, 1116),
    }
