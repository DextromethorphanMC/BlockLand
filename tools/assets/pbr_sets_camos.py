"""Texture sets of shared camos (VIEWMODEL_SPEC section 21), plugged into pbr_textures.SETS.

`skin/camo/gold_beads` (黄金链珠): rows of polished gold ball chain laid side by side over a black satin ground — round
beads on thin links, every other row shifted half a bead, the rows touching so the gold reads as one beaded surface with
dark seams. Beads are metal (gold F0, smooth, a little wear dulling some), the links and the ground dielectric; the
height carries each bead's dome and the links' rods so the normal map catches light on every bead."""
import numpy as np

TILE = 0.12                     # metres per repeat
ROWS = 12                       # chains per repeat
BEADS = 12                      # beads per chain per repeat (square cells: round beads)


def gold_beads(size, rng, pmm, pt):
    y, x = np.mgrid[0:size, 0:size] + 0.5
    pitch_x, pitch_y = size / BEADS, size / ROWS
    row = np.floor(y / pitch_y).astype(int)
    fy = y / pitch_y - row - 0.5                                   # -0.5..0.5 across the chain
    shift = np.where(row % 2 == 1, 0.5, 0.0)
    u = x / pitch_x - shift
    bead = np.floor(u).astype(int) % BEADS
    fx = u - np.floor(u) - 0.5                                     # -0.5..0.5 along the chain
    # a bead: a dome of radius 0.42 pitch; the link between beads: a rod of radius 0.12 pitch along the chain
    r_bead = 0.42
    d = np.hypot(fx * pitch_x / pitch_y, fy)
    dome = np.sqrt(np.clip(r_bead * r_bead - d * d, 0, None))
    rod_r = 0.12
    rod = np.sqrt(np.clip(rod_r * rod_r - fy * fy, 0, None)) * (np.abs(fx) > 0.2)
    pitch_mm = pitch_y * pmm
    height = np.maximum(dome, rod * 0.8) * pitch_mm               # mm
    on_bead = dome > 0
    on_rod = (rod > 0) & ~on_bead
    # each bead its own shade of gold and its own wear
    seed = rng.random((ROWS, BEADS))
    s = seed[row % ROWS, bead]
    gold = pt.srgb([1.0, 0.78, 0.34])
    rose = pt.srgb([0.98, 0.70, 0.42])
    pale = pt.srgb([1.0, 0.86, 0.52])
    # mostly the same gold, a few beads a touch rosier or paler (alloy and polish differ a little)
    tone = np.where(s[..., None] < 0.15, pt.lerp(gold, rose, 0.5), np.where(s[..., None] > 0.88, pt.lerp(gold, pale, 0.5), gold))
    tone = tone * (0.94 + 0.12 * seed[row % ROWS, (bead + 3) % BEADS])[..., None]
    wear = pt.noise(size, rng, size / 40)
    ground = pt.srgb([0.035, 0.032, 0.03])
    link = pt.srgb([0.55, 0.44, 0.22])
    albedo = np.where(on_bead[..., None], tone * (0.96 + 0.04 * wear[..., None]), np.where(on_rod[..., None], link, ground))
    # a bead is polished on top, a little duller at its foot; the ground is satin
    top = np.clip(dome / r_bead, 0, 1)
    rough = np.where(on_bead, 0.16 - 0.08 * top + 0.12 * (s > 0.9) + 0.04 * wear,
                     np.where(on_rod, 0.32, 0.55 + 0.1 * wear))
    metal = on_bead | on_rod
    # the seams between beads hold shadow
    ao = np.clip(1.0 - 0.6 * (1.0 - np.clip(height / (0.3 * pitch_mm), 0, 1)) * ~metal, 0, 1)
    return pt.Layers(albedo, height, rough, metal, f0=0.04, ao=ao)


def sets(pt):
    def recipe(d, rng):
        return gold_beads(d.size, rng, pt.px_mm(d), pt)
    # polished metal: little of pack()'s detail lift (it would mottle the gold)
    return {"skin/camo/gold_beads": pt.SetDef(recipe, 512, TILE, 701, detail=0.25)}
