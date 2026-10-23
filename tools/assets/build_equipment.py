"""build_all.py steps of section 36 (STEP_MODULES): `equipment` builds models/equipment/<id>.glb for the six items and
applies their texture sets, regions and baked AO; `equipment_items` their icons, item models and item definitions;
`equipment_sounds` the synthesised sounds of section 36."""


def steps(path, apply_pbr):
    def build_equipment():
        import equip_data
        import gen_equipment
        equip_data.write_timing()                    # the clips' as-built timing -> equipment.json
        for key in gen_equipment.ITEM_IDS:
            tris, chans = gen_equipment.build(path("models", "equipment", f"{key}.glb"), key)
            print(f"{key}.glb: {sum(tris.values())} triangles; per mesh {tris}")
            print(f"        animation channels {chans}")
            apply_pbr(f"equipment/{key}")

    def build_equipment_items():
        import gen_equipment_items
        gen_equipment_items.build(path)

    def build_equipment_sounds():
        import gen_equipment_sounds
        gen_equipment_sounds.build(path)

    return {"equipment": build_equipment, "equipment_items": build_equipment_items,
            "equipment_sounds": build_equipment_sounds}
