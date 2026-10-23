"""Texture sets of the karambit (pbr_textures.SET_MODULES): the handle's G10 glass-fibre laminate, a very dark olive
with a fine stippled grip texture (the finger grooves are geometry)."""


def sets(pt):
    return {
        "karambit/g10": pt.SetDef(pt._r(pt.stipple, colour=(0.085, 0.095, 0.080)), 512, 0.05, 811),
    }
