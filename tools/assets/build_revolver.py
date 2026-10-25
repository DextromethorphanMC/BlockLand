"""build_all steps of the revolver (VIEWMODEL_SPEC section 49), plugged into build_all.STEPS (see STEP_MODULES there):
revolver (the GLB, then its texture sets, regions and AO), revolver_items (icons, item models), revolver_sounds."""


def steps(path, apply_pbr):
    def build_gun():
        import gen_revolver
        import revolver_clips
        tris, chans = gen_revolver.build(path("models", "gun", "revolver.glb"), revolver_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k != "fx_shell")
        print(f"revolver.glb: {drawn} triangles drawn; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/revolver")

    def build_items():
        import gen_revolver_items
        gen_revolver_items.build(path)

    def build_sounds():
        import gen_revolver_sounds
        gen_revolver_sounds.build(path)

    return {"revolver": build_gun, "revolver_items": build_items, "revolver_sounds": build_sounds}
