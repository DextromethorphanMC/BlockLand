"""Texture sets of the USP, the dual USP and its Cerberus skin (VIEWMODEL_SPEC sections 20 / 26), plugged into
pbr_textures.SETS (see SET_MODULES there)."""
import numpy as np


def engraved_gunmetal(pt, size, rng, pmm):
    """Cerberus slide and frame: dark polished gunmetal carrying a hand-cut scroll engraving — fine curling grooves
    (level lines of a slow noise field, broken into flourishes), darkened by oxide in the cuts and rubbed bright on
    their edges where hands go; polish lines along the slide, light handling."""
    field = pt.fbm(size, rng, size / 7, 4)
    w = 0.07 + 0.03 * pt.norm01(pt.fbm(size, rng, size / 6, 3))
    lines = np.abs(np.sin(field * 15.0))
    cut = pt.smoothstep(w, 0.0, lines)                                   # the groove itself
    keep = pt.smoothstep(0.0, 0.45, pt.fbm(size, rng, size / 4, 3))      # engraved panels between plain polish
    cut = cut * keep
    edge = pt.smoothstep(w * 2.2, w, lines) * keep - cut                  # the burnished shoulder of each cut
    polish = 0.6 * pt.noise(size, rng, 8.0 / pmm, 0.5) + 0.4 * pt.noise(size, rng, 2.0 / pmm, 0.6)
    handled = pt.smudges(size, rng, size / 7, 0.3)
    sc = pt.scratch_field(size, rng, pmm, 90, (0.5, 10), angle=0, spread=0.6, cluster=0.3 + 0.7 * handled,
                          strength=(0.2, 0.6))
    base = pt.srgb([0.20, 0.20, 0.225])
    alb = pt.tint(base, 0.04 * pt.fbm(size, rng, size / 5, 4) + 0.015 * polish, 1.0)
    alb = pt.lerp(alb, pt.srgb([0.08, 0.075, 0.075]), 0.85 * cut)         # oxide in the cuts
    alb = pt.lerp(alb, pt.srgb([0.55, 0.55, 0.58]), 0.35 * np.clip(edge, 0, 1))
    alb = pt.lerp(alb, pt.srgb([0.56, 0.56, 0.58]), 0.5 * sc)
    h = -0.045 * cut + pt.groove(sc, 0.004)
    rough = 0.24 + 0.03 * polish + 0.35 * cut - 0.06 * handled - 0.06 * sc
    return pt.Layers(alb, h, rough, cut < 0.5, relief=0.0012 * polish)


def red_lacquer(pt, size, rng, pmm):
    """Cerberus inlays and magazine base: deep red lacquer over metal, glossy, with fine orange peel, a darker
    pooling at the edges of each brush stroke and a few chips showing the steel below."""
    peel = 0.6 * pt.noise(size, rng, 0.8 / pmm) + 0.4 * pt.noise(size, rng, 2.2 / pmm)
    pool = pt.fbm(size, rng, size / 4, 4)
    chips = pt.smoothstep(0.7, 0.95, pt.smudges(size, rng, 0.8 / pmm, 0.004))
    alb = pt.tint(pt.srgb([0.60, 0.035, 0.04]), 0.06 * pool + 0.015 * peel, 1.0)
    alb = pt.lerp(alb, pt.srgb([0.40, 0.40, 0.42]), chips)
    h = 0.004 * peel - 0.012 * chips
    rough = 0.16 + 0.03 * peel + 0.02 * pool + 0.2 * chips
    return pt.Layers(alb, h, rough, chips > 0.5, f0=0.05, relief=0.003 * peel)


def sets(pt):
    _r, S = pt._r, pt.SetDef

    def engraved(size, rng, pmm):
        return engraved_gunmetal(pt, size, rng, pmm)

    def lacquer(size, rng, pmm):
        return red_lacquer(pt, size, rng, pmm)

    return {
        # USP (section 26): hard-coated slide, nitrided barrel; its polymer frame and stippled grip share the P9's sets
        "usp/slide": S(_r(pt.cerakote, colour=(0.115, 0.115, 0.12)), 1024, 0.15, 803),
        "usp/barrel": S(_r(pt.nitride_slide), 512, 0.12, 804),
        "usp/mag": S(_r(pt.painted_steel, paint=(0.12, 0.12, 0.125), coverage=0.002, vertical=True), 1024, 0.12, 805),
        # the Cerberus skin's model
        "usp/cerberus_body": S(_r(engraved), 1024, 0.15, 811),
        "usp/cerberus_trim": S(_r(lacquer), 512, 0.10, 812),
        "usp/cerberus_grip": S(_r(pt.stipple, colour=(0.085, 0.08, 0.08)), 512, 0.15, 813),
    }
