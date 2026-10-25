"""build_all.py steps of section 45 (STEP_MODULES): `baton` builds models/knife/baton.glb and applies its texture sets,
regions and baked AO; `baton_items` its icon, item models and item definition; `baton_sounds` its sounds."""


def steps(path, apply_pbr):
    def build_baton():
        import gen_baton
        tris, chans = gen_baton.build(path("models", "knife", "baton.glb"))
        print(f"baton.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("knife/baton")

    def build_baton_items():
        import gen_baton_items
        gen_baton_items.build(path)

    def build_baton_sounds():
        import gen_baton_sounds
        gen_baton_sounds.build(path)

    return {"baton": build_baton, "baton_items": build_baton_items, "baton_sounds": build_baton_sounds}
