"""build_all.py steps of the RPD (VIEWMODEL_SPEC section 33), plugged in through STEP_MODULES:
rpd (model + texture sets / regions / AO), rpd_items (icons, held models, item definitions), rpd_sounds."""


def steps(path, apply_pbr):
    def build_rpd():
        import gen_rpd
        import rpd_clips
        tris, chans = gen_rpd.build(path("models", "gun", "rpd.glb"), rpd_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"rpd.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/rpd")

    def build_rpd_items():
        import gen_rpd_items
        gen_rpd_items.build(path)

    def build_rpd_sounds():
        import gen_rpd_sounds
        gen_rpd_sounds.build(path)

    return {"rpd": build_rpd, "rpd_items": build_rpd_items, "rpd_sounds": build_rpd_sounds}
