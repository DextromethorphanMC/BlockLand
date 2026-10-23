"""build_all.py steps of the special skins' effect sprites (VIEWMODEL_SPEC section 30)."""


def steps(path, apply_pbr):
    def build_fx_special():
        import gen_fx_special
        gen_fx_special.build(path)

    return {"fx_special": build_fx_special}
