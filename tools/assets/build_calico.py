"""build_all.py steps of the Calico M950 (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: calico (model
+ texture sets / regions / AO) and calico_sounds. The items step calico_items comes from build_new_guns.py."""


def steps(path, apply_pbr):
    def build_calico():
        import calico_clips
        import gen_calico
        tris, chans = gen_calico.build(path("models", "gun", "calico.glb"), calico_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"calico.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/calico")

    def build_calico_sounds():
        import gen_calico_sounds
        gen_calico_sounds.build(path)

    return {"calico": build_calico, "calico_sounds": build_calico_sounds}
