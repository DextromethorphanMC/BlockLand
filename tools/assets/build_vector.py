"""build_all.py steps of the Vector (VIEWMODEL_SPEC section 44), plugged in through STEP_MODULES: vector (model + texture
set bindings / regions / AO) and vector_sounds. The item step `vector_items` comes from build_new_guns (gen_gun_items)."""


def steps(path, apply_pbr):
    def build_vector():
        import gen_vector
        import vector_clips
        tris, chans = gen_vector.build(path("models", "gun", "vector.glb"), vector_clips.all_clips())
        drawn = sum(v for k, v in tris.items() if k not in ("fx_shell",))
        print(f"vector.glb: {drawn} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/vector")

    def build_vector_sounds():
        import gen_vector_sounds
        gen_vector_sounds.build(path)

    return {"vector": build_vector, "vector_sounds": build_vector_sounds}
