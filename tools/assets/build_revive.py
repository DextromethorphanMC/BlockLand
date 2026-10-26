"""build_all.py steps of section 51 (STEP_MODULES): `revive_antenna` builds models/block/revive_antenna.glb and applies
its texture sets and baked AO; `revive_antenna_items` its icon, block / item models, blockstate, item definition and
loot table."""


def steps(path, apply_pbr):
    def build_revive_antenna():
        import gen_revive_antenna
        tris = gen_revive_antenna.build(path("models", "block", "revive_antenna.glb"))
        print(f"revive_antenna.glb: {sum(tris.values())} triangles; per mesh {tris}")
        apply_pbr("block/revive_antenna")

    def build_revive_antenna_items():
        import gen_revive_antenna_items
        gen_revive_antenna_items.build(path)

    return {"revive_antenna": build_revive_antenna, "revive_antenna_items": build_revive_antenna_items}
