# Section 40 rebuild: one gun per agent (shared brief)

Repository: `C:\Users\Anonymouse\Projects\BlockLand` (Minecraft 1.21.8 Forge mod "BlockLand"). Read `AGENTS.md` first.
You rebuild ONE first-person gun from scratch as a procedural, real-scale, COD-quality model with its own rig,
animations, LabPBR texture sets, sounds, checker and build step — exactly the way the existing generated guns are made
(the RPD is the closest template; the AK, AWP, Thompson and KS-3 are the others). The previous version of the nine
section-40 guns came from a crude shared parametric generator ("arsenal", now removed and backed up under
`tools/assets/source/section40_artist_v1/` — do not reuse it); the user wants every gun remade "更真实，COD 级别精度".

## Hard rules

* Do NOT run Gradle, `mod/build.ps1` or any Java build, and do NOT launch the game: dev clients and a dev server are
  running and load classes from `mod/build`. Your work is Python asset tooling and asset files only. Visual acceptance
  in game is the user's; you judge your work with the repo's offline renders and checks.
* The working tree has a lot of uncommitted work from other sessions. Never revert, reformat or delete anything you do
  not own. Do not commit.
* Files you OWN (create freely): `tools/assets/<gun>_model.py`, `<gun>_rig.py`, `<gun>_clips.py`, `gen_<gun>.py`,
  `pbr_sets_<gun>.py`, `pbr_models_<gun>.py`, `check_<gun>.py`, `build_<gun>.py`, `gen_<gun>_sounds.py` (plus any
  `<gun>_*.py` helper), the output `mod/src/main/resources/assets/blockland/models/gun/<gun>.glb`, everything under
  `mod/src/main/resources/assets/blockland/textures/pbr/<gun>/` (delete the old arsenal sets there that you no longer
  use), `sounds/gun/<gun>_*.ogg`, and the item files `gen_gun_items.py` writes for your gun. Scratch renders go into
  your scratchpad / a temp dir, never into the repo.
* Already registered for you (do not edit these shared files): `build_all.py` STEP_MODULES has `build_<gun>`,
  `pbr_textures.py` SET_MODULES has `pbr_sets_<gun>`, `pbr_apply.py` MODEL_MODULES has `pbr_models_<gun>`,
  `check_glb.py` CHECK_MODULES has `check_<gun>`, `loadouts.py` derives `MOUNTS_OF` / `HIDES` for your gun from
  guns.json (`SECTION_40`), `check_orient.py` allows a translucent BLEND material named `mag_window` (P90 magazine).
  The items step `<gun>_items` already exists (`build_new_guns.py` → `gen_gun_items.py`, renders icons and the held
  model from your GLB): do not define another step of that name.
* Shared files you must NOT edit: `data/blockland/guns/guns.json`, `VIEWMODEL_SPEC.md`, `RENDERING_SPEC.md`,
  any Java, any other weapon's files, shared helpers (`shapes.py`, `meshkit.py`, `glb.py`, `hands.py`, `armrig.py`,
  `ik.py`, `pbr_textures.py` recipes, `check_glb.py`, `loadouts.py`, `preview.py` …). If you need a shared helper
  changed, write your own local helper instead. If the gun's data must change (reload times to match your clips, the
  slide-lock node / offset, magazine node names, ejectEvent, a slot's hides), DO NOT edit guns.json: put the exact
  JSON changes in your final report and the coordinator applies them. Language keys only via
  `python -I tools/lang_add.py <keys.json>` (locked); sounds.json only via `gen_sounds.merge_sounds_json` (locked).

## The data contract (read your gun's entry in `mod/src/main/resources/data/blockland/guns/guns.json`)

* Node names the runtime and data use: `viewmodel` (never animated) → `gun` (pivot near the grip/trigger) → meshes;
  `mag` pivot with `mag_standard` (and `mag_extended` when the gun has two magazines — the magazine `node` fields in
  guns.json), `muzzle`, `ejection`, `aim_iron` (not for the SSG 08: its default optic is the sniper scope, like the
  AWP), the `slideLock` node (`bolt`), iron sights `sight_rear` / `sight_iron` (hidden while an optic is fitted),
  `muzzle_brake` (the muzzle device, hidden while the barrel slot holds something), `stock` where the stock slot
  hides one, mounts `mount_optic`, `mount_barrel`, `mount_laser`, `mount_stock`, `mount_underbarrel`,
  `mount_charm` exactly for the slots your gun has, arm joints, `camera`, root `fx_shell` (the right case).
  VIEWMODEL_SPEC §1–6, §16 (hands), §19 (mounts), §20–21 (PBR, regions), §31 (motion authoring), §35 (underbarrel:
  mount ahead of the support hand), §40 (these guns) are the contract; read them.
* Clips: `idle` (2.0 s loop), `draw`, `fire`, `fire_last`, `reload`, `reload_empty`, `inspect`, `walk`, `sprint`,
  `tac_sprint` (as the RPD has them). Reload lengths should equal guns.json `reload.tactical` / `reload.empty`; if your
  clip honestly needs another length, report the new numbers. Events (`extras.events`): `mag_release`, `mag_out`,
  `mag_in`, `bolt_back` / `bolt_forward` (or `slide_back` / `slide_forward`), `inspect_move`, `equip` — they play
  `gun.<event>` sounds that exist. Camera animation within §4/§31 limits, back at rest at clip ends.
* Materials and regions: every material carries `extras.region` (`body`, `barrel`, `magazine`, `stock`, `grip`,
  `handguard`, `detail`, `none` for brass/interior) and a LabPBR set (`extras.pbr`) applied by your `pbr_models_<gun>.py`
  table through `pbr_apply` (see `pbr_models_rpd.py`, incl. baked AO). Name the metal parts `steel`, `steel_dark`,
  `barrel` (the 鎏金 skin tints those); the camo skins re-texture regions `body`, `stock`, `handguard`, `grip`.

## Quality bar ("COD 级别精度")

* Real-world dimensions and proportions from references (your gun's brief gives the key numbers; the user attached
  photos for some guns — Read the image files listed in your prompt and match them closely). Every recognisable part
  modelled as its own shape: receiver/upper/lower split, rails with real slot spacing, sights, cocking handle, ejection
  port, selector, mag release, trigger + guard, pins and screws, vents/perforations, texture panels, stock mechanics.
  Bevel/chamfer edges (no knife-sharp box edges), smooth curves within `model_quality.arc_segments` error, no
  z-fighting overlaps, correct winding (`check_orient`).
* Triangle budget `model_quality.BUDGETS` (`long_gun` 18000, `pistol` 10000 — Calico is a pistol), and pass
  `check_model_quality.py` / `check_surface_quality.py` style rules as the other guns do.
* PBR: real-scale texture sets in `pbr_sets_<gun>.py` built from the recipes and helpers in `pbr_textures.py`
  (anodized aluminium, parkerized / blued / painted steel, cerakote, moulded polymer with texture, stippling, rubber,
  wood — whatever the real gun uses), with wear on edges where hands and holsters rub. Build only your sets:
  `python -I tools/assets/pbr_textures.py <gun>/`.
* Rig and animation at the level of the RPD / AK: IK arms with real hand anchors (palm frames, finger poses from
  `hands.py`), weighty, physically plausible motion (anticipation, follow-through, settle), a believable reload for
  the gun's real mechanism (e.g. an open-bolt SMG's bolt handle, a bullpup's rear magazine, a top magazine, a bolt
  action), nothing within 0.06 of the eye in any frame, framing per §2 (`coverage.py`, `framing.py`).
* Sounds: `gen_<gun>_sounds.py` synthesises the gun's own fire sounds (3 variants + `_far`) with the repo's sound
  synthesis (see `gen_rpd_sounds.py`, `gen_ak_sounds.py`, `gen_sounds.py`), replacing the placeholder files
  `sounds/gun/<gun>_fire_1..3.ogg` and `<gun>_fire_far.ogg` (the sounds.json entries already exist; keep the names).

## How to build and check (all offline, from the repo root)

* Your step module `build_<gun>.py` (like `build_rpd.py`) returns `{"<gun>": …, "<gun>_sounds": …}`; the model step
  writes the GLB and calls `apply_pbr("gun/<gun>")`.
* `python -I tools/assets/build_all.py <gun> <gun>_sounds <gun>_items`
* `python -I tools/assets/check_glb.py mod/src/main/resources/assets/blockland/models/gun/<gun>.glb` must end with
  "OK: <gun>.glb follows the contract" for your file (your `check_<gun>.py` claims `Path(path).stem == "<gun>"` and
  runs the same depth of checks as `check_rpd.py`: nodes/parents, materials/regions, clip lengths/events, mechanism
  motion, sights at ADS, eye distance, framing, budget, attachments on every mount via check_attach).
* Look at your work: `tools/assets/preview.py` (turntables, frame sheets, ADS views — read its `main`), and
  `pbr_preview.py` for texture sets. Read the PNGs you render, compare with the references, fix what looks wrong,
  iterate. Do this several times; do not stop at "the checks pass".

## Final report (your last message)

Files created/changed; triangle count; check_glb result lines for your gun; a short description of the model,
materials and animations; renders you looked at (paths); any guns.json changes needed (exact JSON); a §40
subsection text (English, the style of VIEWMODEL_SPEC §33) for the coordinator to add; known issues.
