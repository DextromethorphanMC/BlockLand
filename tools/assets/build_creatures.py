"""build_all.py steps of the creatures (STEP_MODULES; tools/creatures/SHARK_SPEC.md): `creature_shark` builds
models/creature/shark.glb (gen_creatures from creature_shark) and applies its texture sets and baked AO;
`creature_sounds` the shark's sound files and sounds.json entries (gen_creature_sounds); `creature_items` its spawn
egg's icon (gen_creature_items)."""

CREATURES = ("shark",)


def steps(path, apply_pbr):
    def one(cid):
        def build():
            import os
            import gen_creatures
            out = path("models", "creature", f"{cid}.glb")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            tris = gen_creatures.build(cid, out)
            print(f"{cid}.glb: {sum(tris.values())} triangles; per node {tris}")
            apply_pbr(f"creature/{cid}")
        return build

    def sounds():
        import gen_creature_sounds
        gen_creature_sounds.build(path)

    def items():
        import gen_creature_items
        gen_creature_items.build(path)

    out = {f"creature_{cid}": one(cid) for cid in CREATURES}
    out["creature_sounds"] = sounds
    out["creature_items"] = items
    return out
