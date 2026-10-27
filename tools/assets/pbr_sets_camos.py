"""Texture sets of shared camos (VIEWMODEL_SPEC section 21), plugged into pbr_textures.SETS.

`skin/camo/gold_beads` (黄金链珠) and `skin/camo/silver_beads` (钢银链珠): rows of polished ball chain laid side by
side over a black satin ground — round beads on thin links, every other row shifted half a bead and the rows packed as
close as round beads go (row pitch √3/2 of the bead pitch), so the metal reads as one beaded surface with only narrow dark
seams between the beads. Beads are metal (gold or steel-silver F0, smooth, a little wear dulling some), the links metal,
the ground dielectric; the height carries each bead's dome and the links' rods so the normal map catches light on every
bead. The two sets share the pattern and differ only in their metal."""
import numpy as np

TILE = 0.12                     # metres per repeat
BEADS = 12                      # beads per chain per repeat
ROWS = 14                       # chains per repeat: even (the half-bead shift tiles), pitch 12/14 ≈ √3/2 of a bead
R_BEAD = 0.48                   # bead radius, in bead pitches (0.5 would touch along the chain)
ROD_R = 0.12                    # link radius, in bead pitches

GOLD = {"base": [1.0, 0.78, 0.34], "alt": [0.98, 0.70, 0.42], "pale": [1.0, 0.86, 0.52], "link": [0.55, 0.44, 0.22]}
SILVER = {"base": [0.84, 0.85, 0.88], "alt": [0.76, 0.78, 0.82], "pale": [0.93, 0.93, 0.95], "link": [0.50, 0.51, 0.53]}


def beads(size, rng, pmm, pt, metal):
    y, x = np.mgrid[0:size, 0:size] + 0.5
    pitch = size / BEADS                                           # bead pitch, px (along the chain)
    row_pitch = size / ROWS                                        # px between chains
    ry = y / row_pitch
    own = np.floor(ry).astype(int)
    # the nearest bead may sit in this chain or a neighbouring one (round beads overlap the row boundaries): take the
    # highest of the three
    height_u = np.full(y.shape, -1.0)
    dome_best = np.zeros(y.shape)
    rod_best = np.zeros(y.shape)
    row_of = own.copy()
    bead_of = np.zeros(y.shape, dtype=int)
    for dr in (-1, 0, 1):
        row = own + dr
        fy = (ry - row - 0.5) * row_pitch / pitch                  # across the chain, in bead pitches
        shift = np.where(row % 2 == 1, 0.5, 0.0)
        u = x / pitch - shift
        bead = np.floor(u).astype(int) % BEADS
        fx = u - np.floor(u) - 0.5                                 # along the chain, in bead pitches
        dome = np.sqrt(np.clip(R_BEAD * R_BEAD - fx * fx - fy * fy, 0, None))
        rod = np.sqrt(np.clip(ROD_R * ROD_R - fy * fy, 0, None)) * (np.abs(fx) > 0.2) * 0.8
        h = np.maximum(dome, rod)
        better = h > height_u
        height_u = np.where(better, h, height_u)
        dome_best = np.where(better, dome, dome_best)
        rod_best = np.where(better, rod, rod_best)
        row_of = np.where(better, row, row_of)
        bead_of = np.where(better, bead, bead_of)
    height_u = np.clip(height_u, 0, None)
    pitch_mm = pitch * pmm
    height = height_u * pitch_mm                                   # mm
    on_bead = dome_best > 0
    on_rod = (rod_best > 0) & ~on_bead
    # each bead its own shade of the metal and its own wear
    seed = rng.random((ROWS, BEADS))
    s = seed[row_of % ROWS, bead_of]
    base, alt, pale = pt.srgb(metal["base"]), pt.srgb(metal["alt"]), pt.srgb(metal["pale"])
    # mostly the same metal, a few beads a touch warmer / darker or paler (alloy and polish differ a little)
    tone = np.where(s[..., None] < 0.15, pt.lerp(base, alt, 0.5), np.where(s[..., None] > 0.88, pt.lerp(base, pale, 0.5), base))
    tone = tone * (0.94 + 0.12 * seed[row_of % ROWS, (bead_of + 3) % BEADS])[..., None]
    wear = pt.noise(size, rng, size / 40)
    ground = pt.srgb([0.035, 0.032, 0.03])
    link = pt.srgb(metal["link"])
    albedo = np.where(on_bead[..., None], tone * (0.96 + 0.04 * wear[..., None]), np.where(on_rod[..., None], link, ground))
    # a bead is polished on top, a little duller at its foot; the ground is satin
    top = np.clip(dome_best / R_BEAD, 0, 1)
    rough = np.where(on_bead, 0.16 - 0.08 * top + 0.12 * (s > 0.9) + 0.04 * wear,
                     np.where(on_rod, 0.32, 0.55 + 0.1 * wear))
    is_metal = on_bead | on_rod
    # the seams between beads hold shadow
    ao = np.clip(1.0 - 0.6 * (1.0 - np.clip(height / (0.3 * pitch_mm), 0, 1)) * ~is_metal, 0, 1)
    return pt.Layers(albedo, height, rough, is_metal, f0=0.04, ao=ao)


def gold_beads(size, rng, pmm, pt):
    return beads(size, rng, pmm, pt, GOLD)


def silver_beads(size, rng, pmm, pt):
    return beads(size, rng, pmm, pt, SILVER)


def sets(pt):
    def recipe(fn):
        return lambda d, rng: fn(d.size, rng, pt.px_mm(d), pt)
    # polished metal: little of pack()'s detail lift (it would mottle the metal)
    return {"skin/camo/gold_beads": pt.SetDef(recipe(gold_beads), 512, TILE, 701, detail=0.25),
            "skin/camo/silver_beads": pt.SetDef(recipe(silver_beads), 512, TILE, 702, detail=0.25)}
