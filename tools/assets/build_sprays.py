"""build_all.py step for the sprays (VIEWMODEL_SPEC section 29): the textures, then their check."""


def steps(path, apply_pbr):
    def sprays():
        import check_sprays
        import gen_sprays
        gen_sprays.build(path)
        errors, _ = check_sprays.check()
        if errors:
            raise SystemExit("sprays: " + "; ".join(errors))

    return {"sprays": sprays}
