"""build_all.py steps of the Jackhammer (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES:
jackhammer (model + texture sets / regions / AO) and jackhammer_sounds. The items step `jackhammer_items` comes from
build_new_guns.py (gen_gun_items.py renders the icons and the held model from this GLB)."""


def steps(path, apply_pbr):
    def build_model():
        import gen_jackhammer
        import jackhammer_clips
        tris, chans = gen_jackhammer.build(path("models", "gun", "jackhammer.glb"), jackhammer_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k != "fx_shell")
        print(f"jackhammer.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/jackhammer")

    def build_sounds():
        import gen_jackhammer_sounds
        gen_jackhammer_sounds.build(path)

    return {"jackhammer": build_model, "jackhammer_sounds": build_sounds}
