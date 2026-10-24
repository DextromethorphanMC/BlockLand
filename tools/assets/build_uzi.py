"""build_all.py steps of the Uzi (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: uzi (model + texture
sets / regions / AO) and uzi_sounds. Its icons, held models and item definitions are the shared `uzi_items` step
(build_new_guns.py -> gen_gun_items.py, rendered from this GLB)."""


def steps(path, apply_pbr):
    def build_uzi():
        import gen_uzi
        import uzi_clips
        tris, chans = gen_uzi.build(path("models", "gun", "uzi.glb"), uzi_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k != "fx_shell")
        print(f"uzi.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/uzi")

    def build_uzi_sounds():
        import gen_uzi_sounds
        gen_uzi_sounds.build(path)

    return {"uzi": build_uzi, "uzi_sounds": build_uzi_sounds}
