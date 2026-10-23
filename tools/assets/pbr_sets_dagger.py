"""Texture sets of the dagger (pbr_textures.SET_MODULES): a blackened, ceramic-coated steel for the guard and pommel,
and the grip's moulded rubber (the ribs are geometry; the set carries the pebble grain, a faint olive cast and the
polish where the hand rubs)."""
import numpy as np


def sets(pt):
    def grip_rubber(size, rng, pmm):
        lay = pt.rubber(size, rng, pmm)
        lay.albedo = np.clip(lay.albedo * np.array([0.92, 1.0, 0.90]), 0, 1)     # very dark olive-grey
        return lay

    return {
        "dagger/blackened": pt.SetDef(pt._r(pt.cerakote, colour=(0.085, 0.085, 0.09)), 512, 0.06, 801),
        "dagger/grip": pt.SetDef(pt._r(grip_rubber), 512, 0.06, 802),
    }
