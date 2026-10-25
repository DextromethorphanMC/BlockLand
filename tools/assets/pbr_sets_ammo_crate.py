"""Texture sets of the ammo crate (pbr_textures.SET_MODULES): olive-drab paint brushed over sawn pine boards (the grain
showing through the thin paint, scuffed and chipped to bare wood on the edges a tiling set can only suggest, dust in
the grain), the yellow stencil paint of the band, and the manila rope of the handles."""
import numpy as np


def sets(pt):
    def painted_pine(size, rng, pmm):
        """Thin olive paint over sawn pine: the rings and saw marks show through, chips and rubs to bare wood."""
        y, x = np.mgrid[0:size, 0:size] / size
        tile_mm = size * pmm
        rings = max(6, int(round(tile_mm / 4.5)))
        sway = pt.std(pt.blur(rng.standard_normal((size, size)), size / 3, size / 12))
        phase = rings * y + 2.0 * sway
        t = phase - np.floor(phase)
        late = pt.smoothstep(0.5, 0.92, t) ** 1.5 * (1 - pt.smoothstep(0.95, 1.0, t))
        saw = pt.noise(size, rng, 30.0 / pmm, 1.2 / pmm)              # band-saw marks across the board
        fibre = pt.noise(size, rng, 5.0 / pmm, 0.6)
        mott = pt.fbm(size, rng, size / 5, 4)
        brush = pt.noise(size, rng, 6.0 / pmm, 0.9 / pmm)
        wear = np.maximum(pt._wear_mask(size, rng, 0.006, size / 50),
                          pt.scratch_field(size, rng, pmm, int(18 * (tile_mm / 100) ** 2), (3, 25), strength=(0.3, 0.8)))
        bare = pt.smoothstep(0.45, 0.65, wear)
        dust = pt.smoothstep(0.2, 1.4, pt.fbm(size, rng, size / 8, 3)) * (0.4 + 0.6 * late)
        paint = pt.tint(pt.srgb([0.25, 0.27, 0.155]), 0.06 * mott + 0.03 * brush, 1.0)
        paint = pt.lerp(paint, paint * 0.82, 0.35 * late)              # the grain shows through the thin coat
        wood = pt.lerp(pt.srgb([0.66, 0.55, 0.38]), pt.srgb([0.48, 0.36, 0.22]), 0.7 * late)
        wood = pt.tint(wood, 0.06 * fibre, 1.0)
        alb = pt.lerp(paint, wood, bare)
        alb = pt.lerp(alb, pt.srgb([0.42, 0.40, 0.33]), 0.30 * dust)
        h = 0.010 * late + 0.006 * saw - 0.012 * bare
        rough = 0.72 + 0.05 * brush + 0.04 * mott + 0.10 * bare + 0.06 * dust
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.04, porosity=0.2 + 0.4 * bare,
                         relief=0.003 * fibre + 0.002 * saw)

    def stencil_yellow(size, rng, pmm):
        lay = pt.painted_steel(size, rng, pmm, paint=(0.70, 0.54, 0.09), coverage=0.010)
        lay.metal = np.zeros_like(lay.metal)                            # paint on wood: no bare steel under it
        lay.albedo = pt.lerp(lay.albedo, pt.srgb([0.45, 0.42, 0.30]), pt.smoothstep(0.5, 0.9, lay.rough - 0.2))
        lay.rough = np.clip(lay.rough + 0.25, 0, 1)
        return lay

    def manila(size, rng, pmm):
        """Three-strand manila rope: twisted strands (diagonal), the fibres along them, fuzz and grime."""
        y, x = np.mgrid[0:size, 0:size] / size
        n = max(2, int(round(size * pmm / 6.0)))
        strand = 0.5 + 0.5 * np.cos(2 * np.pi * n * (x + y))
        fibre = pt.noise(size, rng, 0.4 / pmm, 4.0 / pmm)
        fuzz = pt.noise(size, rng, 0.7)
        grime = pt.smoothstep(0.3, 1.5, pt.fbm(size, rng, size / 6, 3))
        alb = pt.tint(pt.srgb([0.62, 0.50, 0.32]), 0.10 * fibre + 0.05 * fuzz, 1.0)
        alb = pt.lerp(alb * 0.7, alb, strand ** 0.6)
        alb = pt.lerp(alb, pt.srgb([0.30, 0.25, 0.18]), 0.35 * grime)
        h = 0.20 * strand ** 0.7 + 0.02 * fibre
        rough = 0.88 + 0.05 * fuzz - 0.05 * grime
        return pt.Layers(alb, h, np.clip(rough, 0, 1), np.zeros((size, size), bool), f0=0.035, relief=0.004 * fuzz)

    return {
        "ammo_crate/wood": pt.SetDef(pt._r(painted_pine), 1024, 0.30, 841),
        "ammo_crate/marking": pt.SetDef(pt._r(stencil_yellow), 256, 0.10, 842),
        "ammo_crate/rope": pt.SetDef(pt._r(manila), 256, 0.03, 843),
    }
