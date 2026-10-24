"""Texture sets of the P90 (VIEWMODEL_SPEC sections 20 and 40), plugged into pbr_textures.SETS (SET_MODULES): the
black glass-filled polymer of the body (a fine, even mould texture with a satin sheen), the grip module's polymer
(the same moulding, polished glossier and scuffed where the hands work), the smoky-brown polymer of the magazine's
end pieces and the dark phosphated barrel and flash hider. Rails, small steel and the cartridges use the common sets."""
import numpy as np


def sets(pt):
    def body(size, rng, pmm):
        """FN's black polymer: the moulded EDM grain, finer and more even than the P9's, a cool near-black, a satin
        sheen; hands and slings polish the high spots, dust settles in the grain."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.098, 0.099, 0.104))
        rough = np.clip(lay.rough - 0.05, 0.0, 1.0)
        return pt.Layers(lay.albedo, lay.height * 0.8, rough, lay.metal, f0=0.042, relief=lay.relief)

    def grip(size, rng, pmm):
        """The grip module: the same polymer, worn smoother and glossier where the palm and fingers rest, more scuffs
        and fingerprints."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.092, 0.092, 0.097))
        handled = pt.smudges(size, rng, size / 5, 0.45)
        oil = np.clip(0.7 * pt.prints(size, rng, pmm, 4), 0, 1)
        rough = np.clip(lay.rough - 0.10 * handled - 0.12 * oil, 0.0, 1.0)
        alb = pt.lerp(lay.albedo, lay.albedo * 1.10 + 0.006, 0.4 * handled)
        return pt.Layers(alb, lay.height * (1.0 - 0.35 * handled), rough, lay.metal, f0=0.042, relief=lay.relief)

    def mag_end(size, rng, pmm):
        """The magazine's front latch block, rear cap and follower: dark smoked polymer with a slight warm cast,
        glossier than the body, insertion scuffs along the length."""
        lay = pt.polymer_moulded(size, rng, pmm, colour=(0.115, 0.088, 0.066))
        sc = pt.scratch_field(size, rng, pmm, 60, (2, 14), angle=np.pi / 2, spread=0.15, strength=(0.2, 0.5))
        alb = pt.lerp(lay.albedo, lay.albedo * 1.4 + 0.02, 0.5 * sc)
        rough = np.clip(lay.rough - 0.18 - 0.08 * sc, 0.0, 1.0)
        return pt.Layers(alb, lay.height * 0.6 + pt.groove(sc, 0.004), rough, lay.metal, f0=0.045,
                         relief=lay.relief)

    def barrel(size, rng, pmm):
        """Phosphated barrel and flash hider: the parkerized crystal over a darker, oilier base; carbon at the
        muzzle end is left to the mesh's AO."""
        lay = pt.parkerized(size, rng, pmm)
        alb = lay.albedo * np.array([0.72, 0.72, 0.74])
        return pt.Layers(alb, lay.height, np.clip(lay.rough - 0.04, 0.0, 1.0), lay.metal, f0=0.045,
                         relief=lay.relief)

    return {
        "p90/polymer": pt.SetDef(pt._r(body), 1024, 0.15, 901),
        "p90/grip": pt.SetDef(pt._r(grip), 1024, 0.15, 902),
        "p90/mag": pt.SetDef(pt._r(mag_end), 512, 0.10, 903),
        "p90/barrel": pt.SetDef(pt._r(barrel), 512, 0.10, 904),
    }
