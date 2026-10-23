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
