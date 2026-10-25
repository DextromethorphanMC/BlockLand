"""build_all steps of the hand cannon (VIEWMODEL_SPEC section 43), plugged into build_all.STEPS (see STEP_MODULES
there): handcannon (the GLB, then its texture sets, regions and AO), handcannon_items (icons, item models, the
grenade_ammo item), handcannon_sounds."""


def steps(path, apply_pbr):
    def build_gun():
        import gen_handcannon
        tris, chans = gen_handcannon.build(path("models", "gun", "handcannon.glb"))
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell", "mag_extended"))
        print(f"handcannon.glb: {drawn} triangles drawn; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/handcannon")

    def build_items():
        import gen_handcannon_items
        gen_handcannon_items.build(path)

    def build_sounds():
        import gen_handcannon_sounds
        gen_handcannon_sounds.build(path)

    return {"handcannon": build_gun, "handcannon_items": build_items, "handcannon_sounds": build_sounds}
