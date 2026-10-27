"""build_all.py steps of the PP-19 Bizon (VIEWMODEL_SPEC section 54), plugged in through STEP_MODULES: bizon (model +
texture set bindings / regions / AO) and bizon_sounds. The item step `bizon_items` comes from build_new_guns
(gen_gun_items)."""


def steps(path, apply_pbr):
    def build_bizon():
        import bizon_clips
        import gen_bizon
        tris, chans = gen_bizon.build(path("models", "gun", "bizon.glb"), bizon_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"bizon.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/bizon")

    def build_bizon_sounds():
        import gen_bizon_sounds
        gen_bizon_sounds.build(path)

    return {"bizon": build_bizon, "bizon_sounds": build_bizon_sounds}
