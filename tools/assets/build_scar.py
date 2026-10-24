"""build_all.py steps of the SCAR-H (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES: scar (model + texture
sets / regions / AO) and scar_sounds. The items step `scar_items` comes from build_new_guns.py (gen_gun_items)."""


def steps(path, apply_pbr):
    def build_scar():
        import gen_scar
        import scar_clips
        tris, chans = gen_scar.build(path("models", "gun", "scar.glb"), scar_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"scar.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/scar")

    def build_scar_sounds():
        import gen_scar_sounds
        gen_scar_sounds.build(path)

    return {"scar": build_scar, "scar_sounds": build_scar_sounds}
