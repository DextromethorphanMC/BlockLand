"""build_all.py steps of section 27 (STEP_MODULES): `dagger` builds models/knife/dagger.glb and applies its texture
sets, regions and baked AO; `dagger_items` its icon, item models and item definition."""


def steps(path, apply_pbr):
    def build_dagger():
        import gen_dagger
        tris, chans = gen_dagger.build(path("models", "knife", "dagger.glb"))
        print(f"dagger.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("knife/dagger")

    def build_dagger_items():
        import gen_dagger_items
        gen_dagger_items.build(path)

    return {"dagger": build_dagger, "dagger_items": build_dagger_items}
