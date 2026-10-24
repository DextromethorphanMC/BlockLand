"""build_all.py steps of section 41 (STEP_MODULES): `crowbar` builds models/knife/crowbar.glb and applies its texture
sets, regions and baked AO; `crowbar_items` its icon, item models and item definition; `crowbar_sounds` its sounds."""


def steps(path, apply_pbr):
    def build_crowbar():
        import gen_crowbar
        tris, chans = gen_crowbar.build(path("models", "knife", "crowbar.glb"))
        print(f"crowbar.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("knife/crowbar")

    def build_crowbar_items():
        import gen_crowbar_items
        gen_crowbar_items.build(path)

    def build_crowbar_sounds():
        import gen_crowbar_sounds
        gen_crowbar_sounds.build(path)

    return {"crowbar": build_crowbar, "crowbar_items": build_crowbar_items, "crowbar_sounds": build_crowbar_sounds}
