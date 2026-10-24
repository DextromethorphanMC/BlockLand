"""build_all.py steps of section 42 (STEP_MODULES): `knuckles` builds models/knife/knuckles.glb and applies its texture
sets, regions and baked AO; `knuckles_items` its icon, item models and item definition; `knuckles_sounds` its sounds."""


def steps(path, apply_pbr):
    def build_knuckles():
        import gen_knuckles
        tris, chans = gen_knuckles.build(path("models", "knife", "knuckles.glb"))
        print(f"knuckles.glb: {sum(tris.values())} triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("knife/knuckles")

    def build_knuckles_items():
        import gen_knuckles_items
        gen_knuckles_items.build(path)

    def build_knuckles_sounds():
        import gen_knuckles_sounds
        gen_knuckles_sounds.build(path)

    return {"knuckles": build_knuckles, "knuckles_items": build_knuckles_items,
            "knuckles_sounds": build_knuckles_sounds}
