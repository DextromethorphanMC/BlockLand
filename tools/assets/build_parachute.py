"""build_all.py steps of the parachute (STEP_MODULES): `parachute_items` the canopy texture, icon, item models and item
definition; `parachute_sounds` the synthesised parachute sounds. The canopy and pack themselves are built in code
(`client/parachute/CanopyRenderer`), so there is no GLB."""


def steps(path, apply_pbr):
    def build_parachute_items():
        import gen_parachute_items
        gen_parachute_items.build(path)

    def build_parachute_sounds():
        import gen_parachute_sounds
        gen_parachute_sounds.build(path)

    return {"parachute_items": build_parachute_items, "parachute_sounds": build_parachute_sounds}
