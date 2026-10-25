"""build_all steps of the Desert Eagle (VIEWMODEL_SPEC section 48), plugged into build_all.STEPS (see STEP_MODULES
there): deagle (the GLB, then its texture sets, regions and AO), deagle_items (icons, item models),
deagle_sounds."""


def steps(path, apply_pbr):
    def build_gun():
        import gen_deagle
        tris, chans = gen_deagle.build(path("models", "gun", "deagle.glb"))
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
        print(f"deagle.glb: {drawn} triangles drawn; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/deagle")

    def build_items():
        import gen_deagle_items
        gen_deagle_items.build(path)

    def build_sounds():
        import gen_deagle_sounds
        gen_deagle_sounds.build(path)

    return {"deagle": build_gun, "deagle_items": build_items, "deagle_sounds": build_sounds}
