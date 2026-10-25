"""build_all.py steps of section 50 (STEP_MODULES): `armor` builds models/armor/vest.glb and models/armor/plate.glb and
applies their texture sets, regions and baked AO; `armor_items` their icons, item models and item definitions;
`armor_sounds` their sounds."""


def steps(path, apply_pbr):
    def build_armor():
        import gen_armor
        tris = gen_armor.build_vest(path("models", "armor", "vest.glb"))
        print(f"vest.glb: {sum(tris.values())} triangles; per mesh {tris}")
        tris, chans = gen_armor.build_plate(path("models", "armor", "plate.glb"))
        print(f"plate.glb: {sum(tris.values())} plate triangles; animation channels {chans}")
        apply_pbr("armor/vest", "armor/plate")

    def build_armor_items():
        import gen_armor_items
        gen_armor_items.build(path)

    def build_armor_sounds():
        import gen_armor_sounds
        gen_armor_sounds.build(path)

    return {"armor": build_armor, "armor_items": build_armor_items, "armor_sounds": build_armor_sounds}
