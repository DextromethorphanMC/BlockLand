"""build_all.py steps of the guns whose GLB the artist makes (VIEWMODEL_SPEC section 40), plugged in through
STEP_MODULES: gun_items (icons, held models, item definitions of every such gun; placeholders until its GLB is there)
and <id>_items for one gun."""


def steps(path, apply_pbr):
    import gen_gun_items

    def one(gun):
        return lambda: gen_gun_items.build(path, (gun,))

    out = {"gun_items": lambda: gen_gun_items.build(path)}
    for gun in gen_gun_items.GUNS:
        out[f"{gun}_items"] = one(gun)
    return out
