"""build_all.py step of the wall graffiti (STEP_MODULES): `graffiti` writes the graffiti textures, previews and sounds."""


def steps(path, apply_pbr):
    def build_graffiti():
        import gen_graffiti
        gen_graffiti.build(path)

    return {"graffiti": build_graffiti}
