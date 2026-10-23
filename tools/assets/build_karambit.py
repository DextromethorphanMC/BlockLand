"""build_all.py steps of section 34 (STEP_MODULES): `karambit` builds models/knife/karambit.glb and applies its texture
sets, regions and baked AO; `karambit_items` its icon, item models and item definition."""


def steps(path, apply_pbr):
    def build_karambit():
        import gen_karambit
        tris, chans = gen_karambit.build(path("models", "knife", "karambit.glb"))
        print(f"karambit.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("knife/karambit")

    def build_karambit_items():
        import gen_karambit_items
        gen_karambit_items.build(path)

    return {"karambit": build_karambit, "karambit_items": build_karambit_items}
