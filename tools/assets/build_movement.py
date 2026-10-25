"""build_all.py step of section 47 (STEP_MODULES): `movement_sounds` synthesises the movement actions' sounds (prone,
getting up, the dive and its landing)."""


def steps(path, apply_pbr):
    def build_movement_sounds():
        import gen_movement_sounds
        gen_movement_sounds.build(path)

    return {"movement_sounds": build_movement_sounds}
