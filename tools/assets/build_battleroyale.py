"""build_all.py step of 大逃杀 (STEP_MODULES): `battleroyale` writes the drop plane's texture and the mode's sounds."""


def steps(path, apply_pbr):
    def build_battleroyale():
        import gen_battleroyale
        gen_battleroyale.build(path)

    return {"battleroyale": build_battleroyale}
