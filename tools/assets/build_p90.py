"""build_all.py steps of the P90 (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: p90 (model + texture
sets / regions / AO) and p90_sounds. The items step p90_items is build_new_guns' (gen_gun_items renders the icons and
the held model from the GLB)."""


def steps(path, apply_pbr):
    def build_p90():
        import gen_p90
        import p90_clips
        tris, chans = gen_p90.build(path("models", "gun", "p90.glb"), p90_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"p90.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/p90")

    def build_p90_sounds():
        import gen_p90_sounds
        gen_p90_sounds.build(path)

    return {"p90": build_p90, "p90_sounds": build_p90_sounds}
