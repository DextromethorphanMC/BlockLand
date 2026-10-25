"""build_all.py steps of section 46 (STEP_MODULES): `ammo_crate` builds models/block/ammo_crate.glb and applies its
texture sets and baked AO; `ammo_crate_items` its icon, block / item models, blockstate and item definition;
`ammo_crate_sounds` the crate's and the resupply's sounds."""


def steps(path, apply_pbr):
    def build_ammo_crate():
        import gen_ammo_crate
        tris, chans = gen_ammo_crate.build(path("models", "block", "ammo_crate.glb"))
        print(f"ammo_crate.glb: {sum(tris.values())} triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("block/ammo_crate")

    def build_ammo_crate_items():
        import gen_ammo_crate_items
        gen_ammo_crate_items.build(path)

    def build_ammo_crate_sounds():
        import gen_ammo_crate_sounds
        gen_ammo_crate_sounds.build(path)

    return {"ammo_crate": build_ammo_crate, "ammo_crate_items": build_ammo_crate_items,
            "ammo_crate_sounds": build_ammo_crate_sounds}
