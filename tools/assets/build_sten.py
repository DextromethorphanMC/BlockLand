"""build_all.py steps of the STEN Mk II (VIEWMODEL_SPEC section 40), plugged in through STEP_MODULES:
sten (model + texture sets / regions / AO) and sten_sounds. The item assets are the shared step sten_items
(build_new_guns.py -> gen_gun_items.py, from this GLB)."""


def steps(path, apply_pbr):
    def build_sten():
        import gen_sten
        import sten_clips
        tris, chans = gen_sten.build(path("models", "gun", "sten.glb"), sten_clips.all_clips())
        print(f"sten.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} gun triangles "
              f"(+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/sten")

    def build_sten_sounds():
        import gen_sten_sounds
        gen_sten_sounds.build(path)

    return {"sten": build_sten, "sten_sounds": build_sten_sounds}
