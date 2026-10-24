"""build_all.py steps of the SSG 08 (VIEWMODEL_SPEC sections 23 and 40), plugged in through STEP_MODULES:
ssg08 (model + texture sets / regions / AO), ssg08_sounds. The item step ssg08_items lives in build_new_guns."""


def steps(path, apply_pbr):
    def build_ssg08():
        import gen_ssg08
        import ssg08_clips
        tris, chans = gen_ssg08.build(path("models", "gun", "ssg08.glb"), ssg08_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"ssg08.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"          animation channels {chans}")
        apply_pbr("gun/ssg08")

    def build_ssg08_sounds():
        import gen_ssg08_sounds
        gen_ssg08_sounds.build(path)

    return {"ssg08": build_ssg08, "ssg08_sounds": build_ssg08_sounds}
