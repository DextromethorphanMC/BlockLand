# Third-party models

Some first-person models start from models published under CC-BY-4.0
(<https://creativecommons.org/licenses/by/4.0/>). The scripts in this folder convert them (scale, parts, decimation,
UVs, materials) and rework them into designs of our own (VIEWMODEL_SPEC.md §24–§26); none of their textures are
shipped. Changes were made to every one of them.

| Our model | Based on | Author | Source |
|---|---|---|---|
| `models/gun/tommy.glb` (Thompson SMG) | *Gangster Tommy Gun* | Pieter Ferreira | <https://sketchfab.com/3d-models/gangster-tommy-gun-fcf362cddad7452f84ba6c04e307b52e> |
| `models/gun/ks3.glb` (KS-3 shotgun) | *Shotgun* | DJMaesen | <https://sketchfab.com/3d-models/shotgun-2f5b48d9904145a39c1c2fcc6e5579b7> |
| `models/gun/usp_dual_cerberus.glb` (dual USP skin 地狱犬) | *GunGrave Dual Pistols* | sandyyusuf0 | <https://sketchfab.com/3d-models/gungrave-dual-pistols-a34f45d0245f4c4b8cc42159bac252c6> |

The source files are not part of the repository: the converters read them (as plain geometry, never executing
anything) from a path given on the command line or an environment variable (`TOMMY_SOURCE`, `BLOCKLAND_CERBERUS_SRC`;
default `%USERPROFILE%/Downloads/<file>.glb`) and keep the converted parts in `source/tommy_parts.npz`,
`source/ks3_parts.npz` and `data/cerberus_parts.npz`, so the models rebuild without the downloads.

# Third-party shader code

BlockLand's volumetric clouds (RENDERING_SPEC.md §14) and water surface (§15) are shader code ported from *Bliss*, with
the noise texture they sample (`assets/blockland/textures/bliss/noises.png`). They keep their authors' credit in every
ported file and are not BlockLand's own work. Bliss's licence (Chocapic13's rules: credit required, no claiming the code
as one's own, the same rules for anything shared on) is shipped next to them in
`assets/blockland/shaders/include/bliss/LICENSE.md`. The water on the lens (§27) re-implements Bliss's idea in
BlockLand's own code.

| Our feature | From | Authors | Source |
|---|---|---|---|
| Volumetric cloud layers and their shadows (§14), water waves, Snell's window, water volume and caustics (§15) | *Bliss* v2.1.2 (Chocapic13' Shaders edit) | Xonk, based on Chocapic13's shaders | <https://github.com/X0nk/Bliss-Shader> · Chocapic13: <https://www.curseforge.com/minecraft/customization/chocapic13-shaders> |
