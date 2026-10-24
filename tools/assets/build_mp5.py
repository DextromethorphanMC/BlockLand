"""build_all.py steps of the MP5 (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: mp5 (model + texture
set bindings / regions / AO) and mp5_sounds. The item step `mp5_items` comes from build_new_guns (gen_gun_items)."""


def steps(path, apply_pbr):
    def build_mp5():
        import gen_mp5
        import mp5_clips
        tris, chans = gen_mp5.build(path("models", "gun", "mp5.glb"), mp5_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"mp5.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/mp5")

    def build_mp5_sounds():
        import gen_mp5_sounds
        gen_mp5_sounds.build(path)

    return {"mp5": build_mp5, "mp5_sounds": build_mp5_sounds}
