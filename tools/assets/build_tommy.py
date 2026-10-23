"""build_all.py steps of the Thompson (VIEWMODEL_SPEC section 24), plugged in through STEP_MODULES:
tommy (model + texture sets / regions / AO), tommy_items (icons, held models, item definitions), tommy_sounds.
The source model is read from TOMMY_SOURCE when set, else ~/Downloads/gangster_tommy_gun.glb (CREDITS.md); without
it the converted parts committed in source/tommy_parts.npz are used."""
import os


def steps(path, apply_pbr):
    def build_tommy():
        import gen_tommy
        import tommy_clips
        src = os.environ.get("TOMMY_SOURCE") or None
        tris, chans = gen_tommy.build(path("models", "gun", "tommy.glb"), tommy_clips.all_clips(), src)
        print(f"tommy.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} gun triangles "
              f"(+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/tommy")

    def build_tommy_items():
        import gen_tommy_items
        gen_tommy_items.build(path)

    def build_tommy_sounds():
        import gen_tommy_sounds
        gen_tommy_sounds.build(path)

    return {"tommy": build_tommy, "tommy_items": build_tommy_items, "tommy_sounds": build_tommy_sounds}
