"""Texture sets of the Calico M950 (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES):
the black-anodised aluminium of the receiver, tower and sight bridge, and the glass-filled black polymer of the lower,
grip, forend and magazines with the grey marbling the reference photo shows (the resin's swirls), the magazine body a
little glossier and scuffed lengthwise where it slides into the receiver. Small steel parts, the barrel and the case
use the common sets (pbr_models_calico.py)."""
import numpy as np


def sets(pt):
    def receiver(size, rng, pmm):
        """Satin black hard anodising on machined aluminium: the anodised set's blast grain, oil and scratches, a
        little more sheen, and rub-throughs to bright aluminium where hands and holsters wear the edges."""
        lay = pt.anodized_black(size, rng, pmm)
        rub = pt.smoothstep(0.55, 1.0, pt.smudges(size, rng, 4.0 / pmm, 0.006))
        tool = pt.milling(size, rng, pmm, pitch_mm=0.25, sweep=0.12)
        alb = pt.lerp(lay.albedo, pt.srgb([0.58, 0.58, 0.60]), 0.6 * rub)
        metal = lay.metal | (rub > 0.6)
        rough = np.where(metal, np.minimum(lay.rough, 0.30), lay.rough - 0.06 + 0.015 * tool)
        return pt.Layers(alb, lay.height, np.clip(rough, 0.0, 1.0), metal, f0=0.05,
                         relief=lay.relief + 0.0006 * tool)

    def marbled(size, rng, pmm, colour=(0.135, 0.135, 0.14), gloss=0.0, aniso=1.0, slide=0):
        """Glass-filled polymer moulded from a black and grey resin that never fully mixed: polymer_moulded's mould
        texture and handling, swirled grey veins and clouds (domain-warped noise), the veins a hair glossier;
        `slide` adds scuffs along u (a magazine pushed in and out of its cradle)."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=colour)
        base = pt.fbm(size, rng, size / 5, 4, aniso=aniso)
        wx, wy = pt.fbm(size, rng, size / 7, 3), pt.fbm(size, rng, size / 7, 3)
        swirl = pt.warp(base, 0.045 * size * wx, 0.045 * size * wy)
        veins = pt.smoothstep(0.80, 0.99, 1 - np.abs(np.sin(1.4 * swirl)))
        veins = pt.blur(veins * pt.smoothstep(-0.7, 1.0, pt.fbm(size, rng, size / 4, 3)), 0.006 * size)
        smear = pt.warp(pt.fbm(size, rng, size / 9, 4, aniso=2.0), 0.03 * size * wy, 0.03 * size * wx)
        cloud = pt.smoothstep(-0.3, 1.4, swirl) * pt.smoothstep(-0.9, 1.0, smear)
        alb = pt.lerp(lay.albedo, lay.albedo * 1.9 + 0.045, np.clip(0.45 * veins + 0.55 * cloud, 0.0, 1.0))
        h = lay.height
        rough = lay.rough - gloss - 0.04 * veins
        if slide:
            sc = pt.scratch_field(size, rng, pmm, slide, (3.0, 30.0), angle=0.0, spread=0.06, curve=0.05,
                                  strength=(0.15, 0.5))
            alb = pt.lerp(alb, alb * 1.35 + 0.03, 0.55 * sc)
            h = h + pt.groove(sc, 0.004)
            rough = rough - 0.08 * sc
        return pt.Layers(alb, h, np.clip(rough, 0.0, 1.0), lay.metal, f0=0.04, relief=lay.relief)

    return {
        "calico/receiver": pt.SetDef(pt._r(receiver), 1024, 0.15, 931),
        "calico/polymer": pt.SetDef(pt._r(marbled, colour=(0.135, 0.135, 0.14)), 1024, 0.15, 932),
        "calico/mag": pt.SetDef(pt._r(marbled, colour=(0.150, 0.150, 0.155), gloss=0.06, aniso=2.5, slide=120),
                                1024, 0.15, 933),
    }
