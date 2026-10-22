# BlockLand gun asset contract (glTF 2.0 / GLB)

This is the contract between gun art and the mod runtime (`com.blockland.client.gun.*`). Any GLB that follows it
works in game: the procedural generator in this folder, or a model exported from Blender by an artist.
The runtime only relies on what is written here.

## 1. Files

| Path (under `mod/src/main/resources/assets/blockland/`) | What |
|---|---|
| `models/gun/<gun>.glb` | First-person viewmodel of one gun: gun meshes, attachments, arm rig joints, animations |
| `models/gun/arms.glb` | Shared arm meshes (player-skin UVs), attached to the rig joints by name |
| `textures/item/<gun>.png`, `textures/item/<gun>_<skin>.png` | 32x32 inventory icons (one per skin) |
| `models/item/<gun>.json`, `models/item/<gun>_<skin>.json` | Icon item models (`item/generated`) |
| `models/item/<gun>_held.json`, `models/item/<gun>_held_<skin>.json` | Low-poly cuboid model used in third person / dropped (LOD) |
| `textures/item/<gun>_held.png`, `textures/item/<gun>_held_<skin>.png` | 16x16 palette texture for the held model |
| `items/<gun>.json` | Item model definition (see §8) |
| `textures/item/<ammo>.png` | 16x16 ammo icons: `pistol_ammo`, `smg_ammo`, `shotgun_ammo`, `sniper_ammo` |
| `models/item/<ammo>.json`, `items/<ammo>.json` | Their item models / definitions |
| `textures/gun/<pattern>.png` | Tileable skin pattern textures (e.g. `camo_digital.png`) |
| `textures/effect/*.png` | Effect sprites (§9) |
| `sounds/gun/*.ogg`, `sounds.json` | Gun sounds (§10) |

The first gun is `p9` (P9 手枪, a modern polymer-frame 9 mm pistol).

## 2. Space and units

* Viewmodel space = OpenGL camera space: origin at the eye, **+X right, +Y up, −Z forward** (into the screen).
* Units: blocks = metres. Model guns at real-world scale (P9: ~0.19 long, ~0.135 tall with magazine).
* The runtime draws this space with its own viewmodel projection (54° vertical FOV, near plane 0.02), so nothing
  may come closer than 0.06 to the eye in any frame of any animation.
* Rest pose (node TRS in the file) = the hip-fire hold pose, both hands on the gun.

## 3. Node hierarchy (exact names)

```
viewmodel                 root; NEVER animated (the runtime applies ADS, sway, bob, recoil, sprint here)
├─ gun                    weapon root (pivot near the grip/trigger area); animated
│  ├─ frame               mesh: receiver, dust cover rail, trigger guard, grip
│  ├─ slide               mesh: animate translation (+Z = rearward) for blowback / lock / rack
│  │  ├─ sight_iron       mesh: rear notch sight + front post (always shown)
│  │  ├─ sight_reddot     mesh: slide-mounted mini red dot (attachment)
│  │  │  └─ reddot_reticle  mesh: tiny quad on the window, facing +Z (towards the eye), material `reticle`
│  │  ├─ aim_iron         empty: eye point for iron sights (see §5)
│  │  └─ aim_reddot       empty: eye point for the red dot
│  ├─ barrel              mesh
│  ├─ hammer              mesh, origin on its pivot axis (rotation about X)
│  ├─ trigger             mesh, origin on its pivot axis (rotation about X)
│  ├─ slide_stop          mesh (small lever, optional)
│  ├─ mag                 empty pivot at the seated magazine position; animated during reloads
│  │  ├─ mag_standard     mesh (attachment: standard magazine)
│  │  └─ mag_extended     mesh (attachment: extended magazine, longer, with base extension)
│  ├─ laser               mesh: under-rail laser module (attachment)
│  │  └─ laser_emitter    empty at the lens; the beam leaves along its −Z
│  ├─ muzzle              empty at the barrel crown; −Z forward
│  └─ ejection            empty at the ejection port (right side of slide); its +X points where shells fly
├─ camera                 optional, no mesh: animated rotation drives the player's view (see §4)
├─ arm_R                  joint: right shoulder
│  └─ arm_R_fore          joint: right elbow
├─ arm_L                  joint: left shoulder
│  └─ arm_L_fore          joint: left elbow
fx_shell                  separate ROOT node (not under viewmodel), mesh: spent brass casing; never drawn in
                          the viewmodel, the runtime instances it for ejected shells
```

* Attachments (`sight_reddot`, `mag_standard`, `mag_extended`, `laser`) are all present in the file; the runtime
  hides the ones that are not equipped (and their children).
* Joints: a segment hangs along the joint's **local −Y** axis. `arm_X` local origin = shoulder, `arm_X_fore` local
  origin = elbow (its translation = `(0, -UPPER_LEN, 0)`), the hand end of the forearm is at `(0, -FORE_LEN, 0)`
  of `arm_X_fore`. Local −Z of a segment is the "front" of the arm (back of the hand side), local +X the outer
  side for the right arm. UPPER_LEN = FORE_LEN = 0.30.
* Hidden helper nodes may be added freely; unknown names are ignored by the runtime.

## 4. Animations

glTF animations on node TRS (`translation` / `rotation` / `scale`), interpolation `LINEAR` (rotations are slerped)
or `STEP`. No CUBICSPLINE, no morph targets, no skins. Times start at 0. `viewmodel` must not be animated.
A node not targeted by a clip keeps its rest pose during that clip.

| Name | Length | Content |
|---|---|---|
| `idle` | 2.0 s, loops | almost static (the runtime adds breathing) – first and last key identical |
| `draw` | ~0.6 s | gun rises from bottom-right into the rest pose, hands already on it |
| `fire` | ~0.2 s | slide blowback (+Z ≈ 0.034 in ~0.025 s, back by ~0.09 s), hammer cocks, trigger pull/reset, small kick |
| `fire_last` | ~0.2 s | like `fire` but the slide stays locked back at the end |
| `reload` | ~1.9 s | tactical: magazine out, new magazine in, slide stays forward |
| `reload_empty` | ~2.3 s | slide starts locked back; magazine swap; slide released (slide stop or rack) |
| `inspect` | ~3.6 s | COD-style inspect: show left side, right side, press-check (pull slide ~0.012 back), return |
| `sprint` | ~0.66 s, loops (optional) | tactical-sprint carry: gun raised muzzle-up, off hand pumping; one loop = two footsteps. The runtime locks it to the player's steps and blends it in with the sprint; without it the runtime poses a raised carry procedurally |

The runtime adds recoil, sway, bob, breathing, ADS and sprint on top, so animations stay in the hip pose frame and
keep their own kick modest.

**Camera:** the optional `camera` node's rotation relative to its rest pose is added to the player's view (pitch /
yaw / roll) while a clip plays — the "camera animation" of a first-person weapon. Only rotation is used. Keep it
subtle (about ±1.5° pitch/yaw, ±3° roll) and back at rest at the end of every clip.

**Events** (for sounds and runtime cues) are stored in the animation's `extras`:
```json
"extras": { "events": [ { "time": 0.25, "name": "mag_release" }, { "time": 0.35, "name": "mag_out" } ] }
```
Event names used by the runtime: `mag_release`, `mag_out`, `mag_in`, `slide_back`, `slide_forward`,
`slide_release`, `inspect_move`, `equip`, `hammer`. The runtime also treats `mag_in` as "the new rounds count from
here" for cancel logic.

Magazine during reloads: the `mag` pivot is animated; hide the dropped magazine and show the new one by animating
the `mag` node's `scale` (STEP to `0,0,0` while it is off screen, back to `1,1,1`).

## 5. Sights and ADS

`aim_iron` / `aim_reddot` are eye points: the position where the eye must be, with the node's −Z looking along the
sight line (through the rear notch and the top of the front post, or through the reticle). When aiming, the runtime
moves the whole viewmodel so that this node lands exactly on the camera (origin, looking down −Z). Put the eye point
~0.11 behind the rear sight for irons and ~0.14 behind the red dot window. The `reddot_reticle` quad must lie on
the line from `aim_reddot` along its −Z.

## 6. Materials (names are used by skins)

`pbrMetallicRoughness.baseColorFactor`, optional `baseColorTexture` (embedded PNG, REPEAT wrapping),
`metallicFactor`, `roughnessFactor`, `emissiveFactor` (non-zero = fullbright), `alphaMode` OPAQUE / MASK
(`alphaCutoff`) / BLEND, `doubleSided`.

| Material | Used on |
|---|---|
| `polymer` | frame |
| `grip` | stippled grip panels (textured) |
| `slide` | slide (textured: fine brushed noise) |
| `barrel` | barrel |
| `steel_dark` | trigger, hammer, slide stop, iron sights |
| `sight_dots` | sight inserts (emissive, faint green) |
| `mag` | magazine bodies |
| `mag_base` | magazine base plates |
| `attachment` | red dot body, laser module body |
| `glass` | red dot window (BLEND, alpha ~0.2) |
| `reticle` | red dot reticle (emissive red, alpha-cut dot texture) |
| `laser_lens` | laser lens (emissive) |
| `brass` | shell casing |

UVs on gun meshes are box-projected in world units (1 UV = 0.05 m) so tileable skin patterns work on any part.

## 7. Arms (`arms.glb`)

Root nodes (identity transforms) each carrying one mesh, named `<side>_<segment>_<variant>[_sleeve]`:
side `R`/`L`, segment `upper`/`fore`, variant `wide` (4 px arms, 0.07 × 0.07) / `slim` (3 px, 0.0525 × 0.07).
Meshes are in joint-local space (segment from y = 0 down to y = −0.30). UVs map to the 64×64 player skin: right
arm base (40,16) overlay (40,32), left arm base (32,48) overlay (48,48); the upper segment uses the top 6 px rows of
the side faces, the forearm the bottom 6 px rows; the hand end is the bottom face. `_sleeve` meshes are the overlay
layer, inflated by 0.004 per side, material `skin_overlay` (MASK, cutoff 0.1). Base meshes use material `skin`.
The runtime binds the local player's skin texture to both.

## 8. Item model definition (`items/<gun>.json`)

Icons in GUI / on the ground / in frames, the low-poly held model everywhere else, per skin via the first
`custom_model_data` string (`""`/missing = default skin):

```json
{ "model": { "type": "minecraft:select", "property": "minecraft:display_context",
  "cases": [ { "when": ["gui", "ground", "fixed"], "model": SKIN_SELECT(icon models) } ],
  "fallback": SKIN_SELECT(held models) } }
SKIN_SELECT(x) = { "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
  "cases": [ { "when": "<skin>", "model": { "type": "minecraft:model", "model": "blockland:item/<x>_<skin>" } } ],
  "fallback": { "type": "minecraft:model", "model": "blockland:item/<x>" } }
```

## 9. Effect textures (`textures/effect/`)

`muzzle_flash.png` (64×64 front-on star burst, white-hot core, RGBA with soft alpha), `muzzle_flash_side.png`
(64×32 side-on flame cone, origin at the left edge), `glow.png` (32×32 radial falloff), `bullet_hole.png` (16×16),
`reticle_dot.png` (16×16 crisp dot), `smoke.png` (32×32 soft puff).

## 10. Sounds (`sounds/gun/*.ogg`, mono 44.1 kHz Vorbis) and events (`sounds.json`)

`blockland:gun.p9.fire` (3 variants), `blockland:gun.p9.fire_far`, `blockland:gun.dry_fire`, `blockland:gun.mag_release`,
`blockland:gun.mag_out`, `blockland:gun.mag_in`, `blockland:gun.slide_back`, `blockland:gun.slide_forward`,
`blockland:gun.slide_release`, `blockland:gun.inspect_move`, `blockland:gun.equip`, `blockland:gun.hammer`,
`blockland:gun.shell` (3 variants), `blockland:gun.hitmarker`, `blockland:gun.headshot`, `blockland:gun.kill`,
`blockland:gun.attach`.
Animation event `<name>` plays `blockland:gun.<name>`.

---

# Melee weapons (knives)

Knives use the same runtime stack as guns (GLB viewmodel, shared arm rig, retained GPU renderer, procedural motion
layer). Everything in §2 (space, units), §4 (clip rules, `camera` node, events), §6 (material format) and §7 (arms)
applies; the differences are listed here. Gameplay numbers live in `data/blockland/melee/knives.json`.

## 11. Knife files

| Path (under `mod/src/main/resources/assets/blockland/`) | What |
|---|---|
| `models/knife/<knife>.glb` | First-person viewmodel: knife meshes, arm rig joints, animations |
| `textures/item/<knife>.png` + `models/item/<knife>.json` | 32x32 inventory icon (`item/generated`) |
| `models/item/<knife>_held.json` + `textures/item/<knife>_held.png` | Low-poly cuboid model (third person, dropped), knife open |
| `items/<knife>.json` | Icon in `gui` / `ground` / `fixed`, held model otherwise (like §8, without the skin select) |
| `sounds/knife/*.ogg`, entries in `sounds.json` | Knife sounds (§13) |

The first knife is `butterfly` (蝴蝶刀, a balisong: blade with two handles on pivot pins at the tang).

## 12. Knife nodes and clips

```
viewmodel                 root; NEVER animated (runtime: sway, bob, sprint, attack camera punch)
├─ knife                  weapon root, at the right-hand grip; animated
│  └─ …                   free hierarchy for the mechanism (balisong: handle_safe, blade, handle_bite, latch …)
│     └─ tip              empty at the point of the blade (optional, effects)
├─ camera                 optional, rotation drives the view (§4)
├─ arm_R / arm_R_fore     joints (§3, §7)
└─ arm_L / arm_L_fore     joints; scale the left arm to 0 (§4 STEP) while it is off screen
```

The knife is held in the right hand; the left hand joins for flips (draw, inspect). Rest pose = ready stance,
blade open, point forward-up, held low on the right.

| Clip | Length | Content |
|---|---|---|
| `idle` | 2.0 s loop | ready stance, almost static |
| `draw` | 0.80 s | knife comes up closed, flips open (balisong opening) and settles into the rest pose |
| `light_1` | 0.42 s | light attack: fast forehand slash, upper right → lower left; blade crosses screen centre at **0.13 s** |
| `light_2` | 0.42 s | light attack: backhand slash, lower left → upper right; crosses centre at **0.13 s** |
| `heavy` | 0.95 s | heavy attack: wind-up (pull back) then a committed forward stab; full extension at **0.40 s**, recover |
| `inspect` | ~3.6 s | balisong tricks: fan flips, a rollover, an aerial with catch, show the blade, back to rest |
| `sprint` | 0.66 s loop (optional) | running carry, arm pumping, one loop = two footsteps |

The runtime stretches attack clips to the attack's `duration` in knives.json, so the contact time must sit at the
same fraction of the clip as `impact / duration` in the data (light 0.13 / 0.42, heavy 0.40 / 0.95).

Events (§4 format): event `<name>` plays `blockland:knife.<name>`. Names used: `swing`, `swing_heavy`, `flip`,
`open`, `catch`, `equip`.

Materials: `blade` (polished steel), `blade_edge` (bright bevel), `handle` (skeletonised handle), `handle_inlay`,
`pins` (steel), `latch`.

## 13. Knife sounds (`sounds/knife/*.ogg`)

`blockland:knife.equip`, `blockland:knife.open`, `blockland:knife.flip` (3 variants), `blockland:knife.catch`,
`blockland:knife.swing` (3), `blockland:knife.swing_heavy` (2), `blockland:knife.hit_flesh` (3),
`blockland:knife.hit_wall` (3).

---

# Damage decals

## 14. Decal textures (`textures/decal/<type>.png`)

Each file is a strip of `variants` frames laid out **horizontally**; one frame is picked at random per decal.
RGBA, straight alpha; the shader multiplies by the block light and the face shade, so draw colour as seen in full
daylight. Decal look and lifetime are defined in `assets/blockland/decals.json`.

| Type | Frame | Variants | Look |
|---|---|---|---|
| `bullet_hole` | 32×32 | 4 | dark punched centre, chipped rim, faint dust/scorch ring fading to transparent |
| `knife_slash` | 128×32 | 2 | long thin gouge across the frame with tapered ends and a bright scraped edge |
| `knife_stab` | 32×32 | 2 | deep puncture with a short tear along the frame's X |
| `knife_cut` | 128×32 | 2 | cut on a body: dark red wound line with a few droplets, tapered ends |

The frame's X axis follows the cut direction (the runtime aligns it with the swing).
