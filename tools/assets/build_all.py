"""Regenerates every BlockLand gun asset.  Usage (from the repo root):  python -I tools/assets/build_all.py

Optional arguments select parts: pbr_textures, glb, ak47, knife, attachments (scopes and their reticles included),
awp, pbr, fx, items, ak_items, awp_items, knife_items, sounds, ak_sounds, awp_sounds, suppressor_sounds, knife_sounds,
decals, monster_textures, knife_patterns, slide_sounds (default:
all, in that order; the *_items steps need their GLB built first). Every model step re-applies its texture sets,
regions and baked AO (pbr_apply.py); "pbr" re-applies them to every model, "pbr_textures" regenerates the sets."""
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


def apply_pbr(*models):
    """Section 20 / 21: binds texture sets and regions, bakes vertex AO (run after a model is (re)generated)."""
    import pbr_apply
    for model in models:
        if model in pbr_apply.MODELS:
            pbr_apply.apply(model)


def build_pbr_textures():
    import pbr_textures
    pbr_textures.build()
    import pbr_apply
    pbr_apply.refresh_factors()


def build_pbr():
    import pbr_apply
    apply_pbr(*pbr_apply.MODELS)


def build_glb():
    import gen_arms
    import gen_p9
    import p9_clips
    tris, chans = gen_p9.build(path("models", "gun", "p9.glb"), p9_clips.all_clips())
    gun_tris = sum(v for k, v in tris.items() if k != "fx_shell")
    print(f"p9.glb: {gun_tris} gun triangles (+{tris['fx_shell']} shell); per mesh {tris}")
    print(f"        animation channels {chans}")
    print(f"arms.glb: {gen_arms.build(path('models', 'gun', 'arms.glb'))} triangles")
    apply_pbr("gun/p9")


def build_ak47():
    import ak_clips
    import gen_ak47
    tris, chans = gen_ak47.build(path("models", "gun", "ak47.glb"), ak_clips.all_clips())
    print(f"ak47.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} gun triangles (+{tris['fx_shell']} shell); "
          f"per mesh {tris}")
    print(f"        animation channels {chans}")
    apply_pbr("gun/ak47")


def build_awp():
    import awp_clips
    import gen_awp
    tris, chans = gen_awp.build(path("models", "gun", "awp.glb"), awp_clips.all_clips())
    print(f"awp.glb: {sum(v for k, v in tris.items() if k != 'fx_shell')} gun triangles (+{tris['fx_shell']} shell); "
          f"per mesh {tris}")
    print(f"        animation channels {chans}")
    apply_pbr("gun/awp")


def build_awp_items():
    import gen_awp_items
    gen_awp_items.build(path)


def build_awp_sounds():
    import gen_awp_sounds
    gen_awp_sounds.build(path)


def build_attachments():
    import gen_attachments
    built = gen_attachments.build(path)
    for name, tris in built.items():
        print(f"attachment/{name}.glb: {tris} triangles")
    apply_pbr(*(f"attachment/{name}" for name in built))


def build_ak_items():
    import gen_ak_items
    gen_ak_items.build(path)


def build_ak_sounds():
    import gen_ak_sounds
    gen_ak_sounds.build(path)


def build_suppressor_sounds():
    import gen_suppressor_sounds
    gen_suppressor_sounds.build(path)


def build_knife():
    import gen_knife
    tris, chans = gen_knife.build(path("models", "knife", "butterfly.glb"))
    print(f"butterfly.glb: {sum(tris.values())} knife triangles; per mesh {tris}")
    print(f"        animation channels {chans}")
    apply_pbr("knife/butterfly")


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


def build_monster_textures():
    import gen_monster_textures
    gen_monster_textures.build(path)


def build_knife_patterns():
    import gen_knife_patterns
    gen_knife_patterns.build(path)


def build_slide_sounds():
    import gen_slide_sounds
    gen_slide_sounds.build(path)


STEPS = {"pbr_textures": build_pbr_textures,
         "glb": build_glb, "ak47": build_ak47, "knife": build_knife, "attachments": build_attachments,
         "awp": build_awp,
         "pbr": build_pbr,
         "fx": build_fx, "items": build_items,
         "ak_items": build_ak_items, "awp_items": build_awp_items, "knife_items": build_knife_items,
         "sounds": build_sounds, "ak_sounds": build_ak_sounds, "awp_sounds": build_awp_sounds,
         "suppressor_sounds": build_suppressor_sounds, "knife_sounds": build_knife_sounds,
         "decals": build_decals,
         "monster_textures": build_monster_textures, "knife_patterns": build_knife_patterns,
         "slide_sounds": build_slide_sounds}

# Steps of later weapons live in modules of their own (parallel work): each module has `steps(path, apply_pbr)` ->
# {step name: function()}, appended after the steps above in this order. Missing modules are skipped.
STEP_MODULES = ("build_tommy", "build_ks3", "build_usp", "build_dagger", "build_stocks", "build_sprays",
                "build_fx_special", "build_rpd", "build_karambit", "build_equipment",
                "build_bomb", "build_battleroyale", "build_parachute", "build_graffiti", "build_new_guns",
                "gen_kill_icons", "build_uzi", "build_p90", "build_mp5", "build_sten", "build_ssg08", "build_calico", "build_scar", "build_m4a1", "build_jackhammer",
                "build_crowbar", "build_knuckles",
                "build_handcannon", "build_vector", "build_baton", "build_ammo_crate", "build_creeper_zombie", "build_movement",
                "build_deagle", "build_revolver", "build_armor", "build_revive", "build_binoculars")


def _load_step_modules():
    import importlib
    for module in STEP_MODULES:
        try:
            mod = importlib.import_module(module)
        except ModuleNotFoundError as e:
            if e.name == module:
                continue
            raise
        STEPS.update(mod.steps(path, apply_pbr))


_load_step_modules()


def main(argv):
    want = [a for a in argv if a in STEPS] or list(STEPS)
    for name in want:
        t = time.time()
        STEPS[name]()
        print(f"[{name}] done in {time.time() - t:.1f}s")


if __name__ == "__main__":
    main(sys.argv[1:])
