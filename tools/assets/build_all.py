"""Regenerates every BlockLand gun asset.  Usage (from the repo root):  python -I tools/assets/build_all.py

Optional arguments select parts: glb, knife, fx, items, knife_items, sounds, knife_sounds, decals
(default: all, in that order; knife_items needs the knife GLB built first)."""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "blockland")


def path(*parts):
    p = os.path.join(ASSETS, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def build_glb():
    import gen_arms
    import gen_p9
    import p9_clips
    tris, chans = gen_p9.build(path("models", "gun", "p9.glb"), p9_clips.all_clips())
    gun_tris = sum(v for k, v in tris.items() if k != "fx_shell")
    print(f"p9.glb: {gun_tris} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
    print(f"        animation channels {chans}")
    print(f"arms.glb: {gen_arms.build(path('models', 'gun', 'arms.glb'))} triangles")


def build_knife():
    import gen_knife
    tris, chans = gen_knife.build(path("models", "knife", "butterfly.glb"))
    print(f"butterfly.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
    print(f"        animation channels {chans}")


def build_knife_items():
    import gen_knife_items
    gen_knife_items.build(path)


def build_knife_sounds():
    import gen_knife_sounds
    gen_knife_sounds.build(path)


def build_decals():
    import gen_decals
    gen_decals.build(path)


def build_items():
    import gen_items
    gen_items.build(path)


def build_fx():
    import gen_fx
    gen_fx.build(path)


def build_sounds():
    import gen_sounds
    gen_sounds.build(path)


STEPS = {"glb": build_glb, "knife": build_knife, "fx": build_fx, "items": build_items,
         "knife_items": build_knife_items, "sounds": build_sounds, "knife_sounds": build_knife_sounds,
         "decals": build_decals}


def main(argv):
    want = [a for a in argv if a in STEPS] or list(STEPS)
    for name in want:
        t = time.time()
        STEPS[name]()
        print(f"[{name}] done in {time.time() - t:.1f}s")


if __name__ == "__main__":
    main(sys.argv[1:])
