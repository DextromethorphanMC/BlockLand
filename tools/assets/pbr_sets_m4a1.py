"""Texture sets of the M4A1 (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES): the
hard-anodised (type III) forged aluminium of the receivers and rail, the glass-filled nylon of the handguard and stock,
the A2 grip's diamond chequering and the anodised aluminium magazine. The barrel, small steel parts, brass and the
butt pad use the common sets."""
import numpy as np


def sets(pt):
    def hardcoat(size, rng, pmm):
        """Mil-spec hard anodising on forged 7075: the house anodised-black blast and handling (oily prints, rubbed
        scuffs, scratches crazing the coat lighter, the deepest bright aluminium) recoloured to the hardcoat's dark
        grey with a faint green-brown cast, the forging's broad mottle and visible end-mill passes under the coat."""
        lay = pt.anodized_black(size, rng, pmm)
        hue = pt.fbm(size, rng, size / 3, 3)
        forge = pt.fbm(size, rng, size / 6, 4)
        mill = pt.milling(size, rng, pmm, pitch_mm=0.9, sweep=0.12)
        base = pt.lerp(pt.srgb([0.118, 0.120, 0.116]), pt.srgb([0.124, 0.120, 0.113]), pt.smoothstep(-1, 1, hue))
        lum = lay.albedo.mean(-1, keepdims=True)
        ref = float(np.median(lum))
        coat = base * np.clip(lum / max(ref, 1e-3), 0.7, 1.8) * (1 + 0.04 * forge[..., None])
        alb = np.where(lay.metal[..., None], lay.albedo, coat)
        rough = np.clip(lay.rough + 0.04 + 0.03 * mill, 0.0, 1.0)
        return pt.Layers(alb, lay.height, rough, lay.metal, f0=0.05, relief=lay.relief + 0.0010 * mill)

    def chequer(size, rng, pmm):
        """A2 grip: moulded diamond chequering (1.6 mm pyramids on a 45 degree grid; the 51.2 mm tile holds a whole
        number of them) in black glass-filled nylon, the peaks polished glossier and paler by hands, dust in the
        valleys, the mould grain over it all."""
        base = pt.polymer_moulded(size, rng, pmm, colour=(0.115, 0.115, 0.12))
        y, x = np.mgrid[0:size, 0:size] * pmm
        u, v = (x + y) / 1.6, (x - y) / 1.6
        fu, fv = np.abs(u % 1.0 - 0.5), np.abs(v % 1.0 - 0.5)
        pyr = 1.0 - 2.0 * np.maximum(fu, fv)                          # 1 at a peak, 0 along the grooves
        h = 0.28 * pyr
        peak = pt.smoothstep(0.65, 1.0, pyr)
        low = pt.smoothstep(0.25, 0.0, pyr)
        c = pt.srgb([0.115, 0.115, 0.12])
        alb = pt.lerp(base.albedo, c * 1.30 + 0.015, 0.45 * peak[..., None])
        alb = pt.lerp(alb, pt.srgb([0.30, 0.29, 0.27]), 0.18 * low[..., None])
        rough = np.clip(base.rough + 0.06 - 0.20 * peak + 0.06 * low, 0.0, 1.0)
        return pt.Layers(alb, h + 0.3 * base.height, rough, base.metal, f0=0.04, relief=base.relief)

    def phosphate(size, rng, pmm):
        """Black manganese phosphate of the barrel, sight base, hider and receiver extension: the house parkerising
        (crystal clusters, oil, burnished handling, scratches to bright steel) in its darker military black-grey."""
        lay = pt.parkerized(size, rng, pmm)
        alb = np.where(lay.metal[..., None], lay.albedo, lay.albedo * 0.62)
        return pt.Layers(alb, lay.height, lay.rough, lay.metal, f0=0.045, relief=lay.relief)

    def small_parts(size, rng, pmm):
        """Black-oxide / nitrided small parts and the anodised sight and charging handle: the house dark steel with
        its rubbed-to-bright spots held back (they read as paint blotches on parts this small), the scratches kept."""
        lay = pt.steel_dark(size, rng, pmm)
        lum = lay.albedo.mean(-1, keepdims=True)
        base = float(np.median(lum))
        alb = np.where(lum > base * 1.35, lay.albedo * (0.45 + 0.55 * base * 1.35 / np.maximum(lum, 1e-3)), lay.albedo)
        return pt.Layers(alb * 0.85, lay.height, np.clip(lay.rough + 0.05, 0, 1), lay.metal, relief=lay.relief)

    return {
        "m4a1/small_parts": pt.SetDef(pt._r(small_parts), 512, 0.08, 916),
        "m4a1/phosphate": pt.SetDef(pt._r(phosphate), 1024, 0.12, 915),
        "m4a1/receiver": pt.SetDef(pt._r(hardcoat), 1024, 0.15, 911),
        "m4a1/polymer": pt.SetDef(pt._r(pt.polymer_moulded, colour=(0.120, 0.120, 0.125)), 1024, 0.15, 912),
        "m4a1/grip": pt.SetDef(pt._r(chequer), 512, 0.0512, 913),
        "m4a1/mag": pt.SetDef(pt._r(pt.painted_steel, paint=(0.155, 0.160, 0.150), coverage=0.0025, vertical=True),
                              1024, 0.15, 914),
    }
