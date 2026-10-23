"""KS-3 build steps for build_all.py (VIEWMODEL_SPEC section 25), plugged in through STEP_MODULES:
ks3_source (re-converts the source model when it is there: env BLOCKLAND_KS3_SOURCE or ~/Downloads/shotgun.glb;
otherwise the committed source/ks3_parts.npz is used), ks3 (GLB + texture sets / AO), ks3_items, ks3_sounds."""
import os


def steps(path, apply_pbr):
    def build_source():
        import ks3_source
        src = os.environ.get("BLOCKLAND_KS3_SOURCE", ks3_source.DEFAULT_SOURCE)
        if os.path.exists(src):
            ks3_source.build(src)
        else:
            print(f"ks3 source: {src} not found, keeping {os.path.relpath(ks3_source.OUT)}")

    def build_model():
        import gen_ks3
        import ks3_clips
        tris, chans = gen_ks3.build(path("models", "gun", "ks3.glb"), ks3_clips.all_clips())
        gun = sum(v for k, v in tris.items() if k != "fx_shell")
        print(f"ks3.glb: {gun} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
        print(f"        animation channels {chans}")
        apply_pbr("gun/ks3")

    def build_items():
        import gen_ks3_items
        gen_ks3_items.build(path)

    def build_sounds():
        import gen_ks3_sounds
        gen_ks3_sounds.build(path)

    return {"ks3_source": build_source, "ks3": build_model, "ks3_items": build_items, "ks3_sounds": build_sounds}
