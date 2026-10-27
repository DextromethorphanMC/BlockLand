"""build_all.py step of the blind one (瞎子, infection monster, STEP_MODULES): `blind` paints its skin
(textures/entity/blind.png)."""


def steps(path, apply_pbr):
    def build_skin():
        import blind_skin
        blind_skin.build(path)

    return {"blind": build_skin}
