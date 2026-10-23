"""build_all steps of the USP, the dual USP and its Cerberus skin (VIEWMODEL_SPEC section 26), plugged into
build_all.STEPS (see STEP_MODULES there): usp_textures, usp, usp_dual, usp_dual_cerberus, usp_items, usp_sounds."""


def steps(path, apply_pbr):
    def build_textures():
        import pbr_textures
        pbr_textures.build(["usp/"])

    def build_usp():
        import gen_usp
        tris, chans = gen_usp.build(path("models", "gun", "usp.glb"))
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
        print(f"usp.glb: {drawn} triangles drawn (standard magazine); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/usp")

    def build_dual():
        import gen_usp_dual
        tris, chans = gen_usp_dual.build(path("models", "gun", "usp_dual.glb"))
        print(f"usp_dual.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} triangles (two guns, every "
              f"magazine); animation channels {chans}")
        apply_pbr("gun/usp_dual")

    def build_cerberus():
        import cerberus_model
        import gen_usp_dual
        tris, chans = gen_usp_dual.build(path("models", "gun", "usp_dual_cerberus.glb"), cerberus_model.gunset())
        print(f"usp_dual_cerberus.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} triangles (two guns, "
              f"every magazine); animation channels {chans}")
        apply_pbr("gun/usp_dual_cerberus")

    def build_items():
        import gen_usp_items
        gen_usp_items.build(path)

    def build_sounds():
        import gen_usp_sounds
        gen_usp_sounds.build(path)

    return {"usp_textures": build_textures, "usp": build_usp, "usp_dual": build_dual,
            "usp_dual_cerberus": build_cerberus, "usp_items": build_items, "usp_sounds": build_sounds}
