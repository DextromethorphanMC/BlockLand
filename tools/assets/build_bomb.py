"""build_all.py steps of section 39 (STEP_MODULES): `bomb` builds models/bomb/c4.glb (its texture sets first when they
are missing) and applies its sets, regions and baked AO; `bomb_items` the icon, item models and item definition;
`bomb_sounds` the synthesised sounds of section 39."""
import os


def steps(path, apply_pbr):
    def build_bomb():
        import gen_bomb
        import pbr_textures
        sets = [n for n in pbr_textures.SETS if n.startswith("bomb/")]
        if any(not os.path.exists(pbr_textures.set_path(n)) for n in sets):
            pbr_textures.build(sets)
        tris, chans = gen_bomb.build(path("models", "bomb", "c4.glb"))
        print(f"c4.glb: {sum(tris.values())} triangles; per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("bomb/c4")

    def build_bomb_items():
        import gen_bomb_items
        gen_bomb_items.build(path)

    def build_bomb_sounds():
        import gen_bomb_sounds
        gen_bomb_sounds.build(path)

    return {"bomb": build_bomb, "bomb_items": build_bomb_items, "bomb_sounds": build_bomb_sounds}
