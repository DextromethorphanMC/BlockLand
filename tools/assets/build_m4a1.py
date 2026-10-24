"""build_all.py steps of the M4A1 (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: m4a1 (model + texture
sets / regions / AO) and m4a1_sounds. The items step m4a1_items comes from build_new_guns.py (gen_gun_items)."""


def steps(path, apply_pbr):
    def build_m4a1():
        import gen_m4a1
        import m4a1_clips
        tris, chans = gen_m4a1.build(path("models", "gun", "m4a1.glb"), m4a1_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k != "fx_shell")
        print(f"m4a1.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/m4a1")

    def build_m4a1_sounds():
        import gen_m4a1_sounds
        gen_m4a1_sounds.build(path)

    return {"m4a1": build_m4a1, "m4a1_sounds": build_m4a1_sounds}
