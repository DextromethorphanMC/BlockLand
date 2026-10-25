# BlockLand gun asset contract (glTF 2.0 / GLB)

This is the contract between gun art and the mod runtime (`com.blockland.client.gun.*`). Any GLB that follows it
works in game: the procedural generator in this folder, or a model exported from Blender by an artist.
The runtime only relies on what is written here.

## 1. Files

| Path (under `mod/src/main/resources/assets/blockland/`) | What |
|---|---|
| `models/gun/<gun>.glb` | First-person viewmodel of one gun: gun meshes, magazines, attachment mounts, arm rig joints, animations |
| `models/attachment/<model>.glb` | Universal attachments (optics, laser, suppressor, stock, charm), drawn at a weapon's mount (§19) |
| `models/gun/arms.glb` | Shared arm meshes (player-skin UVs), attached to the rig joints by name |
| `textures/item/<gun>.png`, `textures/item/<gun>_<skin>.png` | 32x32 inventory icons (one per skin) |
| `models/item/<gun>.json`, `models/item/<gun>_<skin>.json` | Icon item models (`item/generated`) |
| `models/item/<gun>_held.json`, `models/item/<gun>_held_<skin>.json` | Low-poly cuboid model: its display transforms and box place the 3D weapon in third person / dropped / in frames (§37); drawn itself only in first person (under the viewmodel) |
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
  may come closer than 0.06 to the eye in any frame of any animation (attachments included).
* Rest pose (node TRS in the file) = the hip-fire hold pose, both hands on the gun.
* Framing (`coverage.py`, `framing.py`): at hip (idle / walk / fire) the weapon and arms sit in the lower right and
  leave the crosshair area (a square of 0.3 screen heights around the centre) clear except the muzzle tip; they cover
  roughly 10–15 % of the screen. Sprint carries stay out of that area as well. Reloads and inspects may pass it but
  must not park the weapon or an arm over the screen centre.

## 3. Node hierarchy (exact names)

```
viewmodel                 root; NEVER animated (the runtime applies ADS, sway, bob, recoil, sprint here)
├─ gun                    weapon root (pivot near the grip/trigger area); animated
│  ├─ frame               mesh: receiver, dust cover rail, trigger guard, grip
│  ├─ slide               mesh: animate translation (+Z = rearward) for blowback / lock / rack
│  │  ├─ sight_iron       mesh: rear notch sight + front post (always shown)
│  │  ├─ aim_iron         empty: eye point for iron sights (see §5)
│  │  └─ mount_optic      empty: optic cut in front of the rear sight (§19), rides with the slide
│  ├─ barrel              mesh
│  ├─ hammer              mesh, origin on its pivot axis (rotation about X)
│  ├─ trigger             mesh, origin on its pivot axis (rotation about X)
│  ├─ slide_stop          mesh (small lever, optional)
│  ├─ mag                 empty pivot at the seated magazine position; animated during reloads
│  │  ├─ mag_standard     mesh (standard magazine)
│  │  └─ mag_extended     mesh (extended magazine, longer, with base extension)
│  ├─ muzzle              empty at the barrel crown; −Z forward
│  ├─ ejection            empty at the ejection port (right side of slide); its +X points where shells fly
│  └─ mount_laser, mount_barrel, mount_stock, mount_charm   empties: attachment mounts (§19)
├─ camera                 optional, no mesh: animated rotation drives the player's view (see §4)
├─ arm_R                  joint: right shoulder
│  └─ arm_R_fore          joint: right elbow
├─ arm_L                  joint: left shoulder
│  └─ arm_L_fore          joint: left elbow
fx_shell                  separate ROOT node (not under viewmodel), mesh: spent brass casing; never drawn in
                          the viewmodel, the runtime instances it for ejected shells
```

* Magazines (`mag_standard`, `mag_extended`) are both in the file; the runtime hides the one that is not fitted.
  Optics, lasers, suppressors, stocks and charms are separate, universal files drawn at the mount nodes (§19); a
  weapon file must not contain `sight_reddot`, `reddot_reticle`, `aim_reddot`, `laser` or `laser_emitter`.
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
| `fire` | ~0.2 s | slide blowback (+Z ≈ 0.034 in ~0.022 s, home by ~0.08 s with a ~1 mm rebound), hammer cocks, trigger pull/reset, small kick |
| `fire_last` | ~0.2 s | like `fire` but the slide stays locked back at the end |
| `fire_ads`, `fire_last_ads` | as `fire` / `fire_last` (optional) | the same shot with the sights up: the sight picture holds (§31.5) |
| `reload` | ~1.9 s | tactical: magazine out, new magazine in, slide stays forward |
| `reload_empty` | ~2.3 s | slide starts locked back; magazine swap; slide released (slide stop or rack) |
| `inspect` | ~3.6 s | COD-style inspect: show left side, right side, press-check (pull slide ~0.012 back), return |
| `sprint` | ~0.66 s, loops (optional) | tactical-sprint carry: gun raised muzzle-up, off hand pumping; one loop = two footsteps. The runtime locks it to the player's steps and blends it in with the sprint; without it the runtime poses a raised carry procedurally |

The runtime adds recoil, sway, bob, breathing, ADS and sprint on top, so animations stay in the hip pose frame and
keep their own kick modest.

**Camera:** the optional `camera` node's rotation relative to its rest pose is added to the player's view (pitch /
yaw / roll) while a clip plays — the "camera animation" of a first-person weapon. Only rotation is used. Keep it
within ±2.5° pitch/yaw and ±4.5° roll (§31; v1/v2 files used ±1.5° / ±3°) and back at rest at the end of every clip.

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

`aim_iron` (weapon file) and an optic's `aim` (attachment file, §19) are eye points: the position where the eye
must be, with the node's −Z looking along the sight line (through the rear notch and the top of the front post, or
through the reticle). When aiming, the runtime moves the whole viewmodel so that this point lands exactly on the
camera (origin, looking down −Z); for an optic the point is `mount_optic` × `aim` in the rest pose. Put the eye point
~0.11 behind the rear sight for pistol irons, ~0.14 behind a red dot's window and ~0.13 behind a holographic
sight's. An optic's `reticle` quad must lie on the line from its `aim` along −Z (a holographic sight has no reticle
node: the centre of its `glass` window lies on that line). At ADS no part may come closer than 0.03 to the eye, and
nothing of the weapon may cover the window within a quarter of its radius around the reticle (`check_glb.py` checks
every optic on every gun).

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
| `brass` | shell casing |

Attachment files (§19) bring their own: `attachment` (optic / laser / stock bodies), `glass` (optic window, BLEND,
alpha ~0.2), `reticle` (emissive red, alpha-cut dot texture), `laser_lens` (emissive), `suppressor`, `stock_pad`,
`charm_block` (pixel-art atlas), `charm_metal`, `steel_dark`, and on every optic `interior`: the inward-facing walls
the eye looks along when aiming (a tube's bore, a hood's window walls, a scope's eyecup inside) — flat matte
near-black (base ≈ 0.025 linear, roughness 0.95, metallic 0, no texture set, region `none`), so they never catch
bright grazing reflections.

UVs on gun meshes are box-projected in world units (1 UV = 0.05 m) so tileable skin patterns work on any part.

**Winding:** the runtime culls back faces of every material that is not `doubleSided`, and only `glass`, `lens`,
`lens_front`, `reticle` and the arms' `skin_overlay` may be double sided, blend or cut out. So every triangle is
wound counter-clockwise seen from outside: closed parts enclose a positive volume, neighbouring triangles run their
shared edge in opposite directions, the walls of holes and bores face into the hole, and a thin open sheet that can be
seen from both sides carries a reversed copy. Models converted from outside sources (often authored double sided)
are re-wound by `mesh_orient.orient`; `check_orient.py` (via `check_glb.py`) renders every model without culling from
14 directions and fails a part that shows more than 15 % back faces (an inside-out part shows nearly all), a model
above 3 %, and any inconsistent edge or inside-out closed surface.

## 7. Arms (`arms.glb`)

Root nodes (identity transforms) each carrying one mesh. **v3 (skinned arms):** each arm is ONE continuous tube from
the shoulder to the wrist, named `<side>_arm_<variant>[_sleeve]`: side `R`/`L`, variant `wide` (4 px skins) / `slim`
(3 px skins, 3/4 as wide). Real-arm proportions: a box section that tapers 0.056 × 0.058 at the shoulder → 0.050 ×
0.049 at the elbow → 0.043 × 0.038 at the wrist (width × depth, wide skin; v1 used 0.07 × 0.07 boxes, v2 two separate
boxes per arm that folded rigidly at the elbow). The tube is in the shoulder joint's space in the **bind pose**, the
straight arm: y = 0 at the shoulder, the elbow at y = −0.30, the wrist at −0.60; 20 rings, densest around the elbow.
UVs map to the 64×64 player skin: right arm base (40,16) overlay (40,32), left arm base (32,48) overlay (48,48); the
12-px side strip runs down the whole tube (upper arm = the top 6 rows, forearm = the bottom 6, as the two v2 boxes
did); the shoulder cap is the top face, the wrist cap the bottom face. `_sleeve` meshes are the overlay layer, inflated
by 0.003 per side, material `skin_overlay` (MASK, cutoff 0.1). Base meshes use material `skin`. The runtime binds the
local player's skin texture to both.

**Skinning (v3).** A mesh node's `extras.joint` is its *anchor* (it is drawn at that joint; its vertices are in the
anchor's space in the bind pose). A skinned mesh adds `extras.skin` (the joints its vertices follow, indexed by the
`JOINTS_0` attribute, VEC4 unsigned) and `extras.bind` (per skin joint its translation in the anchor's space in the
bind pose: rotations are identity, the arm is straight); `WEIGHTS_0` (VEC4 float) sum to 1. No glTF `skins`: the
joints are the weapon file's rig joints, found by name. A skin joint named `<joint>:untwisted` is that joint with its
twist about its own Y (relative to its parent) taken out. Vertices blend their joints with **dual quaternions**, so
bends and twists keep their volume (no collapsing elbow, no candy-wrapper wrist).

| Mesh | Anchor | `skin` | Weights |
|---|---|---|---|
| `<s>_arm_<v>[_sleeve]` | `arm_<s>` | `arm_<s>`, `arm_<s>_fore:untwisted`, `arm_<s>_fore`, `arm_<s>_hand` (bind y 0, −0.30, −0.30, −0.60) | upper → forearm smoothly over ±0.045 around the elbow (into the *untwisted* forearm, so the elbow is a clean hinge); the forearm's twist (pronation, which the rig gives the forearm) comes in below the hinge over the forearm's upper half (0.01–0.15 below the elbow, as the radius turns about the ulna; mostly out of view, so the forearm on screen stays a straight box); the last 0.03 blends half into the hand |
| `<s>_hand_<v>` (palm) | `arm_<s>_hand` | `arm_<s>_fore`, `arm_<s>_hand` (bind y +0.30, 0) | the palm's wrist end is half forearm, fully hand 0.013 down: palm and forearm meet at the same half-angle, so the wrist bends round |

Fingers stay rigid on their joints. The runtime (`client/viewmodel/ArmSkin`) skins on the CPU every frame (a few
hundred vertices per arm) into a per-rig vertex buffer laid out like the arms model's own and draws it with the
anchor's model-view and the static index ranges (rendering spec §4: the hand-motion pass measures the anchor's motion;
only the change of the bend between two frames is not in the motion vectors). A skin joint the weapon file lacks (or
that is collapsed) follows the anchor; a mesh without `skin` is drawn rigidly at its joint as before. The offline tools
skin the same way (`armskin.py`, used by `loadouts.scene`, previews, coverage, `check_glb`'s eye distance and the
support-grip clearance); `check_glb` checks the tubes' sections, the skin joints and binds, and that weights sum to 1.

## 8. Item model definition (`items/<gun>.json`)

The icon in the GUI, the low-poly held model in first person (the viewmodel draws over the hand pass there), the full
3D weapon everywhere else (§37); icons and held models per skin via the first `custom_model_data` string
(`""`/missing = default skin). Written by `tools/assets/third_person.py` (`weapon_definition`), which every item
generator uses:

```json
{ "model": { "type": "minecraft:select", "property": "minecraft:display_context",
  "cases": [ { "when": ["gui"], "model": SKIN_SELECT(icon models) },
             { "when": ["firstperson_righthand", "firstperson_lefthand"], "model": SKIN_SELECT(held models) } ],
  "fallback": { "type": "minecraft:special", "base": "blockland:item/<gun>_held",
                "model": { "type": "blockland:weapon", "fit": "blockland:item/<gun>_held" } } } }
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
| `models/item/<knife>_held.json` + `textures/item/<knife>_held.png` | Low-poly cuboid model, knife open: places the 3D knife (§37), drawn itself only in first person |
| `items/<knife>.json` | Icon in `gui`, held model in first person, the 3D knife otherwise (like §8, without the skin select) |
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

**On bodies** (`Decals.onEntity`: `bullet_wound`, `knife_cut`, …) the decal lies on the entity's model as drawn, not on
its hitbox. `client/render/EntityPoses` records, after each living entity's draw within 96 blocks
(`RenderLivingEvent.Post`; not the shadow map's capture, not GUI previews), every drawn cube of its model (visible
parts only) as cube pixels → world, with the renderer's own placement replayed (sleeping offset, scale,
`setupRotations`, the flip, the renderer's `scale`, −1.501) on the event's pose stack, so walking, the head's turn,
crouching, swimming, a slide's lean and baby or scaled mobs are all in it. At the hit the attack's line is cast
against the last frame's cubes, placed where the entity stands now (a grazing melee line is re-aimed at the cube
passing closest to it); an overlay shell (a player's hat, jacket, sleeves, a villager's robe: a cube of the same part
or a child of it, ≤ 0.045 blocks in front) gives way to the cube it covers. The decal is laid out in that cube's space
(model pixels; its size by the cube's scale to blocks), 0.012 blocks off the face and clipped to the face's rectangle,
and keyed by the part's path and cube index; each frame it is drawn by the matrix that cube was drawn with, and not at
all in a frame the model was not drawn in. An entity not drawn lately (out of sight or range) falls back to rest-pose
humanoid parts or the bounding box (`BodySurface`).

---

# Animation system v2 (COD-standard first person)

These rules extend §3/§4/§7 and apply to every weapon (guns and knives). Files built to v1 still load.

## 15. Curves

* Interpolation may be `LINEAR`, `STEP` or **`CUBICSPLINE`** (glTF Hermite: per key in-tangent, value, out-tangent;
  tangents in units per second). Consecutive rotation keys must stay in the same quaternion hemisphere.
* Motion must read with weight: eased starts/stops, anticipation, follow-through, overshoot and settle. No
  constant-velocity segments between sparse keys. Either use CUBICSPLINE keys with authored tangents, or bake the
  curve at **≥ 60 samples/s** as LINEAR. The runtime reads a LINEAR channel whose keys are all ≤ 1/45 s apart (and at
  least four) as a monotone cubic through its keys (`client/gltf/ClipSampler`), so a 60 Hz bake stays smooth at any
  refresh rate without overshooting a hold or an impact; offline tools sample such channels linearly (sub-millimetre
  difference).
* Big masses (gun, forearms) lead, small parts (fingers, mag, bolt, tongue) follow with a few frames of lag.

## 16. Hands and fingers

Viewmodel rig joints (in every weapon GLB), in addition to `arm_R` / `arm_R_fore` / `arm_L` / `arm_L_fore`:

```
arm_X_fore
└─ arm_X_hand          wrist (palm), origin at the end of the forearm, segment along local −Y
   ├─ arm_X_thumb      ─ arm_X_thumb2
   ├─ arm_X_index      ─ arm_X_index2
   ├─ arm_X_middle     ─ arm_X_middle2
   ├─ arm_X_ring       ─ arm_X_ring2
   └─ arm_X_pinky      ─ arm_X_pinky2        (X = R or L)
```

* Fingers curl about their local X axis. Every clip keeps both hands on the weapon in a believable grip (trigger
  finger indexed along the frame / on the trigger when firing). Hands and fingers must work with the parts they touch:
  thumb on the mag release, fingers wrapping the magazine, fingers pinching the charging handle / slide serrations,
  the thumb rolling the balisong handles.
* `arms.glb`: every mesh node carries `extras`:
  `{"joint": "<viewmodel joint name>", "variant": "wide" | "slim", "layer": "base" | "sleeve"}`, skinned meshes
  also `skin` and `bind` (§7).
  The runtime draws each mesh at that joint's matrix (a skinned mesh bent over its skin joints), with the variant that
  matches the player's skin model, and both layers. Joints can be added without code changes. A mesh whose joint is
  missing in a weapon file is not drawn.
* Hand and finger UVs map to the 64×64 player skin (the lower arm pixels and the arm's bottom face), so every player
  sees their own hands. Sleeve overlays only cover the arm segments.
* Proportions (v2): one skin pixel of the hand is 0.0154 (0.88 × the arm's 0.0175): palm 0.062 long, 0.062 wide
  (wide skin) and 0.031 thick, finger segments 0.88 × the v1 lengths. The joint layout in every weapon file uses
  these lengths; grip anchors keep the palm face on the part (the hand centre moves in by the thinner half palm).

## 17. Locomotion clips

| Clip | Loop | Content |
|---|---|---|
| `idle` | 2–4 s | ready pose, tiny weight shifts (breathing is added at runtime) |
| `walk` | optional, one loop = two steps | arms counter the steps, weapon rolls slightly. The runtime phase-locks it to the feet and then lowers its own procedural bob |
| `sprint` | one loop = two steps | running carry: weapon lowered and canted, off hand pumping |
| `tac_sprint` | one loop = two steps | tactical sprint: weapon raised high (pistol up beside the face, rifle muzzle up across the chest), faster, more energetic |
| `fire` / `fire_last` / `reload` / `reload_empty` / `inspect` / `draw` | one-shot | as §4. Reloads and inspects must stay readable while the player walks (the runtime layers movement on top) |

The runtime blends idle → walk → sprint → tactical sprint by the player's speed and state, layers ADS, recoil,
sway, breathing and inertia procedurally, and crossfades one-shot clips with eased weights.
Keep authored camera motion subtle (§4).

## 18. AK-47 (`models/gun/ak47.glb`)

Same contract as the P9 (§3–§6, §15–§17) with rifle parts:

```
viewmodel
├─ gun                       pivot at the pistol grip
│  ├─ receiver, dust_cover, handguard, gas_tube, barrel, front_sight, stock, pistol_grip   meshes
│  │                         (dust_cover carries a low picatinny rail for optics; front_sight = collar + tower)
│  ├─ muzzle_brake           mesh: slant brake (hidden while a suppressor is fitted)
│  ├─ bolt                   charging handle + carrier (right side), +Z = rearward; cycles in `fire`, pulled in `reload_empty`
│  ├─ selector, trigger      meshes (selector lever on the right)
│  ├─ sight_iron             front post; sight_rear: tangent leaf + notch; front_sight_ears: the post's ears
│  │                         (all three hidden while an optic is fitted)
│  ├─ aim_iron               eye point (§5)
│  ├─ mag ─ mag_standard (30-round curved), mag_extended (45-round longer curve)
│  ├─ muzzle (at the brake), ejection
│  └─ mount_optic (on the rail), mount_laser (right of the handguard), mount_barrel (crown), mount_stock (receiver
│                            rear), mount_charm (left of the receiver behind the grip)   empties (§19)
├─ camera, arm joints (§16)
fx_shell                     separate root: 7.62×39 lacquered steel case
```

Materials: `wood` (stock, handguard, grip), `steel` (receiver, barrel), `steel_dark` (bolt, sights, selector, rail),
`mag`, `mag_base`, `brass` (shell). Gameplay timings live in
guns.json and the runtime stretches clips to them. Author clips at about: draw 0.7 s, fire 0.1 s (carrier back and
home within the 600 rpm interval), reload 2.4 s (rock the magazine out and drop it, fetch the new one off screen, rock
it in on its front lug, slap), reload_empty 2.9 s (no slap: a reach-under pull of the charging handle, the carrier slams
home), inspect ~4 s (right side, roll over port-up, chamber check).
Events: `mag_release`, `mag_out`, `mag_in`, `bolt_back`, `bolt_forward`, `inspect_move`, `equip`
(event `<name>` plays `blockland:gun.<name>`). The AK has no bolt hold-open, so `fire_last` = `fire`.

Item assets as §1/§8 for `ak47` (skins `default`, `black`, `desert`, `camo`, `gold`), and the ammo item
`rifle_ammo` (icon, item model, items json).
Sounds: `gun.ak47.fire` (3 variants), `gun.ak47.fire_far`, `gun.bolt_back`, `gun.bolt_forward`.
The magazine sounds reuse `gun.mag_*` or add rifle variants with new names.

---

# Attachments (craft system)

## 19. Attachments and mounts

Attachments are **universal**: one model fits every weapon that has the slot's mount. A weapon file only carries
empty mount nodes; each attachment is its own file, drawn at the mount's matrix (rest pose and every animation
frame, the mount's scale included). Which weapon offers which attachment, their stats and the nodes a slot hides
are data (`data/blockland/craft/attachments.json`, the weapons' `slots` in guns.json / knives.json).

| Slot | Mount node (weapon file) | Attachment files (`models/attachment/`) | Helper nodes |
|---|---|---|---|
| optic | `mount_optic` | `mini_reddot.glb` (low slide optic), `reddot.glb` (tube red dot on a low picatinny mount), `holo.glb` (holographic sight), scopes (§22) | `aim`, `reticle` (not on the holo and the scopes) |
| laser | `mount_laser` | `laser.glb` (one module; the beam colour is data) | `emitter` |
| barrel | `mount_barrel` | `suppressor.glb` (Ø 0.033, 0.16 long; threads on at the origin) | `muzzle` |
| stock | `mount_stock` | `tactical_stock.glb` (skeletonised folding stock, extends +Z ~0.24) | — |
| charm | `mount_charm` | `charm_grass.glb` (a 2.3 cm grass block on a ring and two chain links) | `pivot` |
| underbarrel | `mount_underbarrel` (only on weapons that offer it) | `vertical_grip.glb`, `angled_grip.glb`, `bipod.glb`, `tommy_grip.glb` (§35) | — |

**Attachment file:** root node `attachment` (identity transform, origin = the mount point), modelled at real scale in
the weapon convention (+X right, +Y up, −Z towards the muzzle). Optic: `aim` eye point (§5, 0.14 behind the
window) and a `reticle` quad on its line (the holographic sight: none, the centre of its `glass` window is on the
line), the walls seen when aiming in `interior` (§6); laser: `emitter` at the lens, beam along −Z; suppressor:
`muzzle` at the front cap (the new flash / tracer origin); charm: empty `pivot` at the ring with the mesh `charm`
hanging under it along −Y — the runtime swings `pivot` with a damped pendulum.

**`holo.glb` as built** (EOTech pattern, 0.095 long, 0.045 wide over the hood): rail clamp with a thumb nut (left), a
low body with the brightness buttons on its back and a battery cap (right), a rectangular hood over the front half
with two ears either side of its top bar. The window is one flat `glass` face (BLEND, alpha 0.2, double sided)
30 × 24 mm with 2 mm corner radii, centred 0.0285 over the mount point at z −0.004 (the hood's rear end), facing +Z,
UV 0..1 across it (u along +X, v down); `aim` at (0, 0.0285, 0.126), 0.13 behind it. The hood's inner walls open
forward (13 % sideways, 11 % up and down) more steeply than the sight lines from `aim` to the window's edges, so from
the eye only the window and its thin bevelled frame show; the body behind the window ramps up under those sight
lines. Materials `attachment`, `steel_dark` (region `optic`), `glass`, `interior` (`none`).

**Mount nodes:** empty, parented so they follow the part the attachment sits on (P9 `mount_optic` is under `slide`,
the knife's `mount_charm` under `handle_safe`). Optic / laser / barrel mounts look down the bore (−Z parallel to it);
a laser mount may be turned about Z (the AK's points the module at the handguard's right side). A mount may carry a
scale (the P9 sizes the shared stock to 0.70). Charm mounts are oriented so their −Y hangs straight down in the hip
pose.

| Weapon | Mounts (translation in the parent's space, metres) | Hidden while fitted |
|---|---|---|
| p9 / p9a | optic: slide (0, 0.0124, 0.0530) · laser: under the frame rail · barrel: in front of the slide · stock: bottom of the back strap, turned −28° about X (rises to the shoulder), scale 0.70 · charm: left of the frame's front rail section | — |
| ak47 | optic: on the dust-cover rail (eye 0.26 behind the receiver front, behind the receiver) · laser: right of the lower handguard, turned 90° about Z · barrel: crown (the brake comes off) · stock: rear of the receiver · charm: low on the left of the receiver behind the grip · underbarrel: under the lower handguard ahead of the support hand (§35) | optic: `sight_rear`, `sight_iron`, `front_sight_ears` · barrel: `muzzle_brake` · stock: `stock` |
| butterfly | charm: past the butt of `handle_safe` (lanyard hole) | — |

Checks (`check_glb.py` → `check_attach.py`): attachment files (root, helpers, reticle — or the holo's window centre
— on the aim line, a matte `interior` on every optic, a reflex sight's own body clear of the middle of its window
seen from `aim`, charm under `pivot`), every weapon's mounts, every build's attachments ≥ 0.06 from the eye in every
clip (`holo_full` included), and ADS through every optic on every gun (clearance ≥ 0.03, reticle / window centre
within 2 px of the centre at 1080p, window clear around it).
Previews: `preview.py attachments` (turntables, every model) and `preview.py ads` (every optic on every gun).

Sounds: `gun.suppressed` (3 variants, `subtitles.blockland.gun.suppressed`) is the suppressor's fire sound.
Knife skin patterns (tileable, box-projected like the guns, 1 UV = 0.05 m): `textures/knife/damascus.png`,
`fade.png`, `tiger.png`.

## 20. PBR materials (texture sets, LabPBR)

A material may name a **texture set** in its glTF `extras` (artists: a custom property on the Blender material):

```json
"extras": {"pbr": "textures/pbr/ak47/wood", "region": "stock", "uvScale": 1.0}
```

`pbr` is a path under `assets/blockland/` without `.png`; the runtime loads up to three PNGs (resource packs may
replace any of them, at any resolution; the house sets are 1024 px for the main weapon materials, 2048 px for the
AK wood, 512 px for small parts and skins):

| File | Content |
|---|---|
| `<set>.png` | albedo, sRGB RGBA (alpha = cut-out / blend like `alphaMode`); multiplied by `baseColorFactor` |
| `<set>_n.png` | LabPBR normal: **R** = X (0 left … 255 right), **G** = Y in the **DirectX convention** (0 up … 255 down), **B** = material AO (linear, 255 = none), **A** = height (255 = top, ≥ 1); Z is rebuilt as √(1 − x² − y²) |
| `<set>_s.png` | LabPBR specular: **R** = perceptual smoothness (roughness = (1 − R)²), **G** = F0 0–229 linear (dielectrics ≈ 10 = 4 %), 230–237 predefined metals (iron, gold, aluminium, chrome, copper, lead, platinum, silver), 255 = metal with the albedo as F0, **B** = porosity / SSS (ignored by our shader), **A** = emission 0–254 (255 = none) |

A missing `_n` means a flat normal and no texture AO, a missing `_s` falls back to `metallicFactor` /
`roughnessFactor`. Texture sets tile (REPEAT, mipmapped) over the box-projected UVs (§6:
1 UV = 0.05 m); `uvScale` multiplies a material's UVs (0.25 = one texture repeat every 0.2 m). Materials without
`pbr` keep the glTF look (factors and `baseColorTexture`).

**Runtime (`client/render`):** `Textures` loads a set once (CPU mip chain: albedo averaged in linear light and
weighted by alpha, `_s` keeping metal ids and "no emission" intact) and remembers the albedo's mean colour.
`blockland:core/gun` lights it with GGX / Smith / Schlick under the studio light rig and the lightmap: the tangent
frame comes from screen-space derivatives (no tangents in the vertex), so `_n` G is the +v component (down the
image) and the normal is T·x + B·y + N·z; AO = `COLOR_0` × `_n` blue. The vertex packs the normal and the baked AO
into four bytes. BlockLand does not support OptiFine shader packs or other rendering mods (Minecraft is the engine);
vanilla entity render types only carry third-person weapons into the sun shadow map (`ShadowPass`, §37).

**Baked ambient occlusion:** `COLOR_0` (R channel, 1 = open) on a mesh carries per-vertex AO (cavities, contact
shadows between parts); it multiplies the texture set's AO. Glass, reticles and emissive parts carry none.

**House sets and tools:** `pbr_textures.py` generates every set procedurally at real scale (heights in mm → normals
by texel size; the `_n` alpha is the depth below the set's top in 0.002 mm steps, AO comes from crevices deeper than
0.12 mm; micro texture - grain, blast, brush lines, sheet waviness - goes into the normal only; tile size per set
gives its `uvScale`; the weapon materials layer real-scale detail: machining / grinding lines, phosphate crystals,
orange peel, mould texture and stippling, wood rings, fibres, pores and ray flecks, plus handling - clustered
scratches, oil smudges and fingerprints in the roughness, rubbed and chipped spots, dust): `common/` (steel_dark,
brass, polished_steel, parkerized,
anodized_black, rubber), `p9/`, `ak47/`, `awp/`, `butterfly/`, `attachment/suppressor` and the skin sets under
`skin/`. `pbr_apply.py` binds them: a table per model maps material → (set, region), splits a material whose parts
belong to different regions (the AK's `wood` → `wood_handguard`, `wood_grip`; `steel` → `steel_barrel`,
`steel_stock`; `steel_dark` → `steel_dark_barrel`), sets the base
colour white, drops the embedded textures and bakes `COLOR_0` by ray casting in the rest pose (parts that leave their
place in clips — magazines, bolts, the P9 barrel — and the knife's swinging handles only occlude themselves). Every
model step of `build_all.py` re-applies it (`pbr` re-applies all, `pbr_textures` regenerates the sets);
`pbr_preview.py` renders sets and turntables with the maps, `pbr_check.py` checks this section and the next.

**Visible detail:** the first-person weapon gets about 0.2 mm per screen pixel at 4K (0.4 mm at DLSS Performance), so
true-to-scale marks finer than that average away in the mip chain. The packer lifts the band from 0.25 mm to 4 mm —
grain, blast, orange peel, grinding marks, scratches, wear — in the normal map (relief ×6, slopes eased into ~50°), the
albedo (contrast ×2.2 as a ratio in linear light, softly capped so camo, Damascus and other two-tone patterns keep their
look) and the roughness (×2.4, softly capped); the height channel and AO stay true, glowing sets keep their colours.
`SetDef.detail` scales the lift per set (0 = none). Finer relief is not lost at runtime: each `_s` mip level is
roughened by the normal detail it averages (Toksvig, `client/render/Mipmaps.roughen`).

## 21. Regions and skins

Every material belongs to a **region** (`extras.region`); skins re-texture regions, not single materials, so one
skin fits every gun and every attachment.

Naming in the 工匠 UI: ordinary skins (colours, texture sets) are **迷彩** (Camo), skins with a shader effect
**特效皮肤** (shader skins) and skins with a model or shot effects of their own (§30) **特殊皮肤** (special skins); each
kind has its own tab, and all of them set the one skin of a build. The data keeps one list for all three (`skins` in
guns.json / knives.json, `craft/skins.json`, a build's `skin`), so saved builds are not affected by the naming.

| Region | Parts |
|---|---|
| `body` | receiver, frame, slide, upper |
| `barrel` | barrel, gas block, front sight base; the barrel slot's attachments (suppressor) |
| `magazine` | magazines |
| `stock` | stocks (built-in ones and the stock slot's attachments) |
| `grip` | pistol grip, grip panels |
| `handguard` | handguard / forend |
| `optic` | the optic slot's attachments (bodies, rings, turrets) |
| `laser` | the laser slot's attachments |
| `underbarrel` | the underbarrel slot's attachments (grips, bipod; §35) |
| `detail` | small steel parts (trigger, pins, bolt, springs, iron sights); skins rarely touch it |
| `blade`, `handle`, `hardware` | knives (blade, scales, pins / latch) |
| `lever`, `pin`, `label`, `glass`, `liquid`, `rag`, `plunger`, `cap` | equipment (§36): grenade spoons, pins and printed bands, the molotov's glass, fuel and rag, the injector's shroud and cap (their bodies use `body`, `barrel`, `blade`, `grip`) |
| `none` | never skinned: glass, lenses, reticles, emissive dots, brass, charms, optics' `interior` |

An attachment's materials without `extras.region` belong to their slot's region (`optic`, `laser`, `barrel`,
`stock`; charms `none`).

The house models: **P9** slide `body`, polymer frame and grip panels `grip` (the frame is the grip module), barrel
`barrel`, small steel `detail`, magazines `magazine`; **AK-47** receiver and dust cover `body`, barrel, gas tube,
front sight and brake `barrel`, wooden stock and butt plate `stock`, handguard `handguard`, pistol grip `grip`, bolt,
selector, trigger and rear sight `detail`; **AWP** action `body`, barrel and brake `barrel`, chassis stock `stock`,
grip `grip`, bolt `detail`, butt pad `none`; **butterfly** blade and edge `blade`, handles and scales `handle`, pins
and latch `hardware`; attachments: optic bodies `optic`, the laser `laser`, the suppressor `barrel`, the tactical
stock `stock` (its rubber pad `none`), scope eyepiece rubber and every optic's `interior` `none`. Shared shader skins (`skins.json`): 极光 aurora
and 熔岩 magma for every gun, 星河 galaxy and 多普勒 doppler for knives.

**Skin data** (a weapon's `skins` in guns.json / knives.json, and the shared library
`data/blockland/craft/skins.json`, whose skins join every weapon kind or id listed in `for`): the old per-material
`tints` / `textures` still work; `regions` maps a region id (or `*` = every skinnable region not listed) to a layer:

```json
"aurora": {
  "name": "极光", "rarity": "legendary", "for": ["gun"],
  "regions": {
    "body":     {"pbr": "textures/pbr/skin/aurora/body", "effect": "iridescent", "params": [0.8, 1.0, 0.05, 6.0]},
    "magazine": {"pbr": "textures/pbr/skin/aurora/accent", "effect": "flow", "effectColor": "#5CF2FF", "params": [0.35, 0.06, 0.25, 1.0]},
    "*":        {"pbr": "textures/pbr/skin/aurora/metal", "tint": [0.9, 0.95, 1.0]}
  }
}
```

A layer replaces the region's texture set (`pbr`, `uvScale`) and may add a `tint` (RGB multiplier on the albedo),
an `effect`, an `effectColor` (`#RRGGBB`) and `params` (four numbers; missing ones take the defaults below). The
older per-material `tints` are display colours: on a material with a texture set they recolour its albedo around
that colour (albedo × tint / mean albedo) and keep its detail; per-material `textures` replace the albedo (tiled at
1 UV = 0.05 m, without the set's maps). A material's own entries win over a region layer. Skins with an effect are
listed in the 工匠's 特效皮肤 tab, and the craft preview keeps animating them. **Shader effects** run in our pipeline:

| `effect` | Look | `params` |
|---|---|---|
| `iridescent` | thin-film coating: the hue shifts with the viewing angle and across the surface | strength 0–1, film thickness scale (≈ 1), animation speed (cycles/s, 0 = still), pattern scale (repeats per metre); defaults 0.8, 1, 0.05, 6 |
| `flow` | the `_s` alpha mask glows in `effectColor`, a pulse runs along the weapon (−Z, towards the muzzle) | speed (m/s), pulse length (m), base glow 0–1, pulse strength; defaults 0.35, 0.06, 0.25, 1 |
| `galaxy` | a starfield and nebula deep "inside" the surface (parallax); albedo = nebula colours, `effectColor` = stars | depth (m), star density, twinkle speed, nebula strength; defaults 0.02, 1, 1, 0.6 |
| `inferno` | molten veins in a cellular crack network with energy running through them, glyphs burnt into the flanks; a smoulder at rest, ablaze with the skin's surge (§38) | vein density (cells per metre), flow speed (m/s), resting smoulder 0–1, char 0–1 (how dark the steel goes at full surge); defaults 70, 0.12, 0.15, 0.7 |

Effects run in each model's rest-pose space (a mesh's vertices plus its node's rest origin). `flow`: pulses repeat
every 6 pulse lengths (at least 5 cm) and glow = mask × (base + strength × pulse). `galaxy`: four star layers between
the surface and `depth`, the nebula (the albedo) seen at 0.6 × `depth`; a star density of 1 puts 5–10 star cells
across one texture repeat per layer, about a third of them lit.

## 22. Scopes (magnified optics)

A magnified optic is an optic attachment (§19) with a `scope` object in `attachments.json`:

```json
"scope_sniper": {
  "slot": "optic", "name": "狙击镜", "model": "models/attachment/scope_sniper.glb", "aimNode": "aim",
  "zoom": 1.15, "adsTimeScale": 1.25,
  "scope": {"magnification": 6.0, "zoomLevels": [4.0, 6.0, 9.0], "defaultZoom": 1, "focalPlane": "second",
            "lensNode": "lens_ocular", "reticle": "textures/scope/reticle_sniper.png", "reticleSize": 1.0,
            "reticleColor": "#FF2A1E", "illumination": 0.9, "eyeBox": 0.008, "parallax": 0.6,
            "coating": "#8FE8B8", "vignette": 0.45, "apparentSize": 0.64,
            "sway": 0.16, "holdBreath": 5.0, "blurOutside": 0.85, "flare": 1.0, "dirt": 0.3, "glint": 1.0}
}
```

`zoom` stays the main view's FOV divisor while aiming (a small focus zoom for scopes); the picture **in** the scope
is `magnification` times the unzoomed view. A variable scope lists its powers in `zoomLevels` (low to high, starting at
index `defaultZoom`; the mouse wheel steps through them while aiming, the picture eases between them, the choice is
kept per scope for the session, the eye box shrinks as √(start power / power) and the sensitivity follows the power).
`focalPlane` `second` keeps the reticle's size at every power, `first` grows it with the power (authored at the start
power). `reticle` is an RGBA texture centred in the eyepiece (`reticleSize` =
its width as a share of the eyepiece diameter): grey/black pixels are etched lines, coloured pixels are illuminated
and tinted with `reticleColor`. `eyeBox` (m) is how far the eye may sit off the optical axis before the image shrinks
into a dark ring, `parallax` how far the reticle drifts against the image when it does (0–1), `coating` the tint of
lens ghosts and (faintly, 25 %) of the glass's reflections — keep it pale: the glass reflects the player's real sky and
light, never a fixed colour — `vignette` the darkening towards the rim, `illumination` the brightness of the lit
reticle. Optional, all client-side feel: `sway` (degrees, the aimed rifle's breathing sway about the support hand),
`holdBreath` (seconds the sprint key steadies it; 0 = cannot), `blurOutside` (0–1, the world around the eyepiece
out of focus while aimed; default by magnification), `flare` (sun glare and ghosts), `dirt` (smudges lit by the sun)
and `glint` (0–1, how visible the raised scope is to the players it points at; default by magnification).

**Scope file nodes:** `attachment` root; `aim` eye point (§5) on the optical axis at the eye relief behind the
eyepiece (0.07–0.09 m); `lens_ocular` = a flat disc mesh in the eyepiece, facing +Z (the eye), centred on the aim
line, UV (0.5, 0.5) at its centre and radius 0.5 at its rim, material `lens` (BLEND, dark glass); `lens_objective`
= the front glass disc, material `lens_front`. No `reticle` quad: the runtime draws the reticle in the eyepiece.

**Each kind of sight is aimed its own way:**

| Sight | Aimed view |
|---|---|
| Iron sights (`aim_iron`) | the gun's notch and post are aligned on the camera; the world zooms by the optic's `zoom` |
| Reflex sights (`reflex` object: red dot, holographic) | the model's `glass` is drawn by the reflex pass (shader `reflex_glass`): the reticle is light at infinity — a dot, or a ring around a dot for `"reticle": "holo"` — on the sight line wherever the eye is behind the window, only seen through the glass, plus the coating's tint; the model's own `reticle` node and glass are left out (sizes in MOA, game scale) |
| Scopes (`scope` object) | an aimed pose of their own: the eye point moves along the sight line to a shorter eye relief so the eyepiece covers `scope.apparentSize` of the screen height by true perspective (default 0.6; the eyecup becomes the frame and the rest of the scope hides behind the eyepiece); the magnified picture, reticle and scope shadow (field stop, exit pupil) live inside the eyepiece glass, the model around it is drawn normally |

Every aimed sight also focuses (client-side feel, the setting 瞄准时聚焦): a sharp circle around the aim point — the
eyepiece for a scope, `focusRadius` half screen heights around the sight line's point for other optics (default 0.28
iron, 0.34 reflex) — and the world beyond it blurs from none at the circle's edge to `focusBlur` at the screen's corners
(0–1; default 0.36 iron, 0.42 reflex, a scope's `blurOutside`), drawn before the weapon so the weapon stays sharp.

Every aimed sight is the point of impact: once raised (aim ≥ 0.85, fully at 1) shots leave along the sight line as it was
drawn — the `aim` node's −Z (iron: `aim_iron`), a scope's optical axis, the screen centre under classic zoom — so sway,
kick and camera motion move the reticle and the shot together. The server keeps checking the direction against the
player's look (as before) and times aiming itself (`C2SAim`): a shot only gets the aimed spread once the sights could
be up after the weapon's ADS time.

Optic models must therefore hide their tube from the eye point: the eyepiece + eyecup (scopes) or the window + a thin
frame (reflex sights) cover everything behind them on the sight line, and surfaces seen through the optic use the
matte near-black `interior` material, never the shiny body material.

**Eyecup rule (scopes):** the rear rubber eyecup (`scope_rubber`) has an outer radius of 1.35–1.45 × the
`lens_ocular` glass radius and its rear rim at most 12 mm behind the glass, with a soft rounded lip; its inner wall
(glass edge → lip) is `interior`. Seen from `aim` along −Z nothing of the scope may show outside the eyecup's
silhouette except straight below it, where the mount comes up — so from the shorter aimed eye relief the eyepiece sits
in a dark ring (inner wall), then the rubber, and the rest of the scope is hidden.

While the sight is up, the runtime renders the world a second time through the scope (picture in picture, at about
the eyepiece's pixels × the quality setting, sharpened back, only while aimed) and shows it in `lens_ocular` with the
optics (eye box shadow, parallax, lateral colour, barrel distortion, rim vignette, reticle glow, sun glare and ghosts
with the sun traced for cover, smudges, fresnel reflections of the player's surroundings); the view around the scope
stays the normal one, blurred (and with the setting darkened) around the eyepiece before the weapon is drawn, so the
weapon stays sharp. The second render must stay isolated from the main view: its culling frustum has no corners
(vanilla draws a captured frustum as coloured debug planes — that was the teal veil and red flashes), it uses fog
uniforms of its own (GPU buffers are written unsynchronised), and name tags are left out of it. Other players see a
raised scope as a glint (`S2CAim` from the server, the optic from their held item), hidden by blocks. With picture in
picture switched off in the settings, the whole view zooms instead and the scope's outline masks the screen.

What the runtime reads from the model (`client/scope/ScopeLens`, measured once in the rest pose): the glass disc
must lie in `lens_ocular`'s own XY plane (its normal is the node's +Z, towards the eye) — the centre is the mean of
its vertices, the radius the furthest vertex from it in that plane; the optical axis is the node's −Z; the eye relief
is how far `aim` sits in front of the glass along +Z (it must be > 0). The eyepiece's apparent field is
radius / eye relief, so a 36 mm eyepiece with 80 mm relief shows a ±12.7° picture; the field stop is anchored to the
axis, so when the rifle sways the picture slides against the rim (a crescent) instead of moving with the glass. The
runtime draws its own disc over the glass (a hair in front of it), so the `lens` material is only what the eyepiece
looks like at the hip. A scope model without `lens_ocular` (or `aim` behind it) still works, with the zoom fallback.
Keep `zoom` small for scopes (1.0–1.15: the view around the scope); `eyeBox` 0.008–0.015 m (smaller = fussier).

**The scope files as built** (`attach_models.py`, `gen_attachments.py`; region `optic` for `scope` / `scope_rubber`,
`none` for the glass): `scope_sniper.glb` — 0.36 m variable-power scope in a one-piece cantilever mount (optical axis
0.040 m over the mount point, 30 mm tube, 56 mm objective bell, elevation / windage turrets, side parallax knob, power
ring with a throw lever, eyepiece glass Ø 37 mm, eye relief 0.085 m; the ocular bell flares into an eyecup of
radius 26.0 mm = 1.40 × the glass, tapering to 24.4 mm at its rim 7.5 mm behind the glass — shallow so the AWP's
fire kick keeps it inside the 4:3 frame); `scope_2x.glb` — compact prism scope on a flat-top clamp with a throw lever
(axis 0.033 m, eyepiece glass Ø 26 mm, eye relief 0.085 m, illumination tube on top; eyecup radius 18.2 mm = 1.40 ×
the glass, rim 8.5 mm behind it). Reticles `textures/scope/reticle_sniper.png` (duplex with mil dots and half-mil hashes, illuminated centre
dot) and `reticle_2x.png` (illuminated chevron over a bullet-drop ladder and stadia), 1024 px RGBA: etched lines
black, illuminated parts red (the runtime tints them with `reticleColor`). Both scopes fit every gun's
`mount_optic`; `check_attach.py` checks the glass discs (UV centre, rim, facing, upright UVs), the eye relief, the
eyecup rule (radius ratio, rim depth, the scope hidden behind its silhouette from `aim`), the eyepiece centred at
ADS on every gun, nothing between the eye and the eyepiece and nothing in front of the objective.

## 23. AWP (`models/gun/awp.glb`) and bolt action

A bolt-action sniper rifle, built like §3 / §18: roots `gun` and `viewmodel`, the bolt as `bolt` (body, handle and
knob under it), `mag_standard`, `muzzle`, `ejection`, `camera`; mounts `mount_optic` (scope rail above the
receiver), `mount_barrel` (crown) and `mount_charm`. The thumbhole stock is part of the rifle (region `stock`, no
stock slot). No iron sights and no `aim_iron`: the optic slot defaults to the sniper scope and does not offer `iron`.
Clips (§4 / §17): `idle`, `draw`, `fire` (shot, then the bolt cycle: up, back, forward, down; ≈ 1.3 s), `fire_last`
(shot only), `reload`, `reload_empty` (also cycles the bolt), `inspect`, `walk`, `tac_sprint`. Clip events play
`gun.<event>` sounds: `awp.bolt_up`, `awp.bolt_back`, `awp.bolt_forward`, `awp.bolt_down`.

**The file as built** (`gen_awp.py`, `awp_model.py`, `awp_rig.py`, `awp_clips.py`): Accuracy International AWM
pattern at real scale (.338 Lapua, 0.66 m fluted barrel, overall 1.40 m). Nodes under `gun`: `receiver` (action and
picatinny rail), `barrel`, `muzzle_brake` (own node: the barrel slot hides it), `stock` (forend, chassis with the
magazine well and trigger guard, thumbhole buttstock with the raised cheek piece, butt pad), `grip` (thumbhole pistol
grip), `bolt` (slides along the bore: the non-rotating shroud) with `bolt_handle` under it (turns about the bore,
whose axis its origin sits on: body, lugs, handle and knob; +68° about Z = handle up), `trigger`, `mag` →
`mag_standard` (10-round box magazine with the top round), `muzzle`, `ejection`. `mount_optic` sits on the rail
0.155 m behind the receiver face, far enough back that a red dot's eye point clears the action (§5: ≥ 0.03).
Hip pose: the rifle low on the right, nearly parallel to the view, so the long barrel ends right of the crosshair.
Clips as listed above plus `sprint` (lowered running carry); `fire` is 1.21 s: the bolt is unlocked at 0.33–0.41 s,
back at 0.53 s (case ejected), home at 0.69 s, locked at 0.76 s, and the hand is back on the grip at 0.96 s
(60 / 41 rpm − 0.40 s ADS with the sniper scope = 1.06 s); the rifle cants towards the hand and rings at each stop of
the bolt.

| Material | Region | Used on |
|---|---|---|
| `receiver` | body | action, rail, recoil lug |
| `barrel` | barrel | fluted barrel, muzzle brake |
| `bolt` | detail | bolt body, handle, knob, shroud |
| `steel_dark` | detail | trigger, screws, magazine release, sling loop, rail under the forend, cheek posts |
| `stock` | stock | forend, chassis, buttstock, cheek piece |
| `grip` | grip | thumbhole pistol grip |
| `pad` | none | rubber butt pad (never skinned) |
| `mag`, `mag_base` | magazine | magazine body, floor plate |
| `brass` | none | the top round, `fx_shell` |

Checks: `check_glb.py` → `check_sniper.py` (nodes, regions, clips and events, the bolt only slides while its handle
is up and ends closed, `fire_last` leaves it alone, eye distance, framing with the sniper scope fitted, the hip
muzzle out of the crosshair area) and `check_attach.py` (every optic and both scopes on it). Previews:
`preview.py awp` (clip sheets, rest views with the scopes, turntable, ADS through every optic).

In guns.json the rifle has `"boltAction": true`: after every shot the client takes the sight down while the fire
clip cycles the bolt and raises it again when aim is still held; the server's fire-rate check uses `rpm`. Timing
(`client/gun/BoltCycle`): the sight stays up 0.12 s after the shot (the recoil is seen through the scope), then goes
down and starts rising one ADS time before the next round can be fired (60 / `rpm` after the shot), so it is fully
up when the rifle is ready — make the `fire` clip's bolt cycle end by then (≈ 60 / rpm − adsTime).
`"ejectEvent": "awp.bolt_back"` makes the empty case fly out when the bolt comes back in `fire` and `reload_empty`
(not at the shot, and not in the inspect's press-check); guns without it eject at the shot.

---

# Round G: new weapons, sprays, special skins

Sections 24–30 extend the contract for the second batch of weapons. Everything above still applies (space and
units §2, node rules §3, clips and events §4 / §15–§17, materials §6 / §20–§21, mounts §19). Source models from
outside the project (§24–§26) are converted by scripts in `tools/assets` (never edited by hand): they are scaled to
real size, split into the parts the contract needs, decimated where they are heavier than the budget, re-UV'd with
the box projection of §6, given our material names, texture sets and regions, and changed into a design of our own
(shapes, details and proportions); their own textures are not shipped. Their licences (all CC-BY-4.0) are credited in
`tools/assets/CREDITS.md` and in the README.

Precision authoring budgets (`model_quality.json`; attachments and fx_shell not counted): rifles / SMGs /
shotguns ≤ 18k triangles, pistols ≤ 10k each (one magazine fitted), knives ≤ 12k, equipment ≤ 12k.
These replace the original low-poly 9k / 5k / 3k / 4k limits; historical counts below describe the original assets.
The third-person renderer currently shares these meshes: there is no separate distance LOD, so large crowds still
need in-game performance testing. Do not silently increase these limits to make a failed model pass.

Curved parts opt into `shapes.revolve(..., precision=True)`; sampling is based on a 0.04 mm circular chord error,
with at most 96 radial segments (authored minima are never reduced). This is an offline geometric target, NOT a
screen-space error or an industry-quality certification. Intentional polygonal parts and open meshes joining
fixed authored rings stay unchanged. Round bevels evaluate more points on their original arc; blade grids retain
all authored breakpoints and re-evaluate the analytic edge/fullers between them. Flat faces are not subdivided.
Flashbang vents are actual bevelled holes in a 1.2 mm curved shell with a core recessed by 3.5 mm, not dark discs.
Sizes, rig/node transforms, clips/events, skin material names/regions and mount positions remain the contract.

Run `python -I tools/assets/check_model_quality.py` for geometry tests and serialized model budgets; use
`--baseline <original-models-directory>` to check node/animation/material contracts against an earlier build.
Run `check_glb.py` and `pbr_check.py` as well. Visual judgement (inspect highlights, silhouette, skin seams,
finger contacts, third-person crowds) remains a user-side in-game check; these tests do not certify COD parity.

## 24. Thompson SMG (`models/gun/tommy.glb`, weapon id `tommy`)

Based on *Gangster Tommy Gun* by Pieter Ferreira (CC-BY-4.0). A 1928-pattern .45 SMG at real scale (overall ≈ 0.85 m,
barrel ≈ 0.27 m): finned barrel with a compensator, receiver with the cocking knob on top, rear leaf sight, front
post, wooden buttstock, wooden pistol grip, stick magazine and drum; its wooden vertical foregrip is the underbarrel
slot's default attachment `tommy_grip` (§35). Built like §18:

```
viewmodel
├─ gun                       pivot at the pistol grip
│  ├─ receiver, barrel, grip, stock                   meshes (stock: wooden butt; hidden by the stock slot)
│  ├─ muzzle_brake           compensator (hidden by the barrel slot)
│  ├─ bolt                   cocking knob on top of the receiver, +Z = rearward; cycles in `fire`, pulled in `reload_empty`
│  ├─ trigger, selector      meshes
│  ├─ sight_iron             front post; sight_rear: the rear leaf sight (hidden while an optic is fitted)
│  ├─ aim_iron               eye point (§5)
│  ├─ mag ─ mag_stick (30-round stick, default), mag_drum (50-round drum)
│  ├─ muzzle (at the compensator), ejection (right side of the receiver)
│  └─ mount_optic (on a riser rail ahead of the cocking slot), mount_laser (right of the receiver's front block),
│                            mount_barrel (crown), mount_stock (receiver rear), mount_charm (left of the frame),
│                            mount_underbarrel (under the barrel clamp's plate: the wooden foregrip, §35)
├─ camera, arm joints (§16)
fx_shell                     separate root: .45 ACP brass case
```

Clips (§4 / §17): `idle`, `draw`, `fire` (bolt back and forward within the 720 rpm interval, ≈ 0.08 s; open bolt:
`fire_last` = `fire`), `reload` (≈ 2.3 s: magazine out, new in), `reload_empty` (≈ 2.8 s: plus pulling the cocking
knob), `inspect` (≈ 4 s), `walk`, `sprint`, `tac_sprint`. Both magazines ride the `mag` pivot, so every reload works with
either: the left hand holds either one by its front face low down, fingers up and thumb round the left edge
(`mag_side`; the drum's front face is flush with the stick's), so the stick and the drum both stay in view; the old magazine is let go and falls, and the new one is fetched from below
the screen, lined up and driven home (`mag_in` at 1.52 s). The empty reload hauls the cocking knob back (`bolt_back` at
2.10 s) and lets it slam home (`bolt_forward` at 2.17 s). Events: `mag_release`, `mag_out`, `mag_in`, `bolt_back`,
`bolt_forward`, `inspect_move`, `equip`.

| Material | Region | Used on |
|---|---|---|
| `steel` | body | receiver, cocking knob slot, trigger guard |
| `steel_barrel` | barrel | barrel, cooling fins, compensator |
| `steel_dark` | detail | bolt knob, trigger, selector, sights, screws |
| `wood` | stock | buttstock (split: `wood_grip` → grip; the foregrip's `wood_foregrip` is `tommy_grip`'s, §35) |
| `mag`, `mag_base` | magazine | stick magazine, drum |
| `brass` | none | `fx_shell` |

Item assets as §1 / §8 for `tommy` (skins `default`, `black`, `chicago`). Sounds: `gun.tommy.fire` (3 variants),
`gun.tommy.fire_far`; the magazine and bolt sounds reuse `gun.mag_*`, `gun.bolt_*`. Checks: `check_tommy.py`
(claims files with `mag_drum`), via `check_glb.py`.

**The file as built** (`gen_tommy.py`, `tommy_source.py`, `tommy_model.py`, `tommy_rig.py`, `tommy_clips.py`; build step
`build_all.py tommy`, the source path from `TOMMY_SOURCE` or `~/Downloads/gangster_tommy_gun.glb`): the source's single
mesh is split into its connected pieces and sorted into our parts by position (receiver and frame, levers and pins,
foregrip, pistol grip, stock, cocking knob, trigger, safety lever); its drum, barrel, fins, sights and the
cylinders hidden inside the receiver are dropped, the receiver keeps 60 % of its triangles (quadric decimation,
`mesh_decimate.py`: its flat sides lose nothing) and the pistol grip is slimmed front to back. Rebuilt as ours: the
barrel with 12 thick cooling fins and two collars, the Cutts-pattern compensator with five vent slots, the front
blade on its band and a fixed rear notch sight (sight line 30 mm over the bore), a short riser rail ahead of the
cocking slot that carries every optic high enough to look over the knob (17.5 mm), the 30-round stick magazine and
the 50-round drum hung from a feed tower with its axis along the bore — round faces to the front and the back (the
winding key on the rear face, towards the shooter), as on the real L drum and the source — its front face flush with
the stick's front face (Round I: it had been turned onto X, faces to the sides). Fitting an optic
hides `sight_rear` and `sight_iron`. 8.2k triangles. Materials as the table (the pbr step splits `wood` and `steel`):
oiled walnut `tommy/walnut`, blued steel `tommy/blued` (receiver, magazines), parkerised barrel.

## 25. KS-3 pump shotgun (`models/gun/ks3.glb`, weapon id `ks3`) and shell reloads

Based on *Shotgun* by DJMaesen (CC-BY-4.0; its parts `base`, `loader`, `trigger`, `pump`, `slug`). A 12-gauge
pump-action shotgun at real scale (overall ≈ 1.0 m, barrel ≈ 0.47 m), tube magazine under the barrel.

```
viewmodel
├─ gun                       pivot at the pistol grip / wrist of the stock
│  ├─ receiver, grip, barrel, stock (hidden by the stock slot)   meshes
│  ├─ tube_standard, tube_extended   magazine tubes under the barrel (the fitted magazine's node shows, §3 magazines)
│  ├─ pump                   forend (+Z = rearward), slides along the tube in `fire` and `reload_end_empty`
│  ├─ loading_gate           the loading port's gate under the receiver (pushed in by each shell)
│  ├─ trigger, safety        meshes
│  ├─ sight_iron             front post with a brass bead; sight_rear: notch on the end of the rail (both hidden
│  │                         while an optic is fitted); aim_iron: eye point on their line, 0.24 behind the notch
│  ├─ muzzle, ejection (right of the receiver)
│  └─ mount_optic (receiver top rail), mount_laser (clamp on the barrel, turned 90° about Z), mount_barrel (crown), mount_stock
│                            (receiver rear), mount_charm (left of the receiver)
├─ camera, arm joints (§16)
│  └─ arm_L_hand ─ shell_load   a shotshell mesh in the left hand, shown (scale 1) only while a round is carried to
│                               the loading port in `reload_shell`, collapsed (scale 0, STEP) otherwise
fx_shell                     separate root: spent shotshell (red hull, brass head)
```

Clips: `idle`, `draw`, `fire` (shot, then the pump: back with event `pump_back` — the case flies out, `ejectEvent` —
and forward with `pump_forward`; the whole cycle inside 60 / 70 rpm ≈ 0.86 s), `fire_last` (shot only), `inspect`,
`walk`, `sprint`, `tac_sprint`, and the **shell-mode reload** clips:

| Clip | Length | Content |
|---|---|---|
| `reload_start` | ≈ 0.45 s | the shotgun rolls a little to show its loading port, the left hand leaves the pump |
| `reload_shell` | ≈ 0.52 s, played once per round | the left hand brings a shell (`shell_load` shown) to the port and thumbs it in (event `shell_in` when it goes in); ends where it starts, so it can repeat |
| `reload_end` | ≈ 0.42 s | the left hand back on the pump, the gun back to the rest pose |
| `reload_end_empty` | ≈ 0.80 s | as `reload_end`, plus a pump cycle (`pump_back`, `pump_forward`) to chamber a round |

A shell-mode gun (`guns.json` `"reload": {"mode": "shell", ...}`) needs no `reload` / `reload_empty`. The runtime plays
`reload_start`, then `reload_shell` once per missing round (each stretched to the data's `shell` time; a round counts
when its clip ends, on the client and on the server), then `reload_end` (or `reload_end_empty` when the reload started
on an empty gun). Firing once a round is in interrupts the reload (the server cancels its own timer the same way);
switching weapons cancels it. Events: `equip`, `pump_back`, `pump_forward`, `shell_in`, `inspect_move`.

| Material | Region | Used on |
|---|---|---|
| `receiver` | body | receiver, trigger guard, picatinny rail |
| `barrel` | barrel | barrel, choke, ventilated rib, magazine tubes and their caps |
| `pump` | handguard | forend |
| `stock` | stock | skeleton buttstock |
| `grip` | grip | pistol grip |
| `steel_dark` | detail | trigger, safety, loading gate, sights, pins and screws, action bars, laser and tube clamps, butt pad |
| `shell` | none | shotshell hull (`shell_load`, `fx_shell`) |
| `brass` | none | shotshell heads, the front bead |

Item assets for `ks3` (skins `default`, `olive`, `dragon`). Sounds: `gun.ks3.fire` (3), `gun.ks3.fire_far`,
`gun.pump_back`, `gun.pump_forward`, `gun.shell_in` (3). Checks: `check_shotgun.py` (claims files with `pump`; the
shell clips, `shell_load` only shown during `reload_shell`, the pump returns in every clip).

**The file as built** (`ks3_source.py` → `source/ks3_parts.npz`, `ks3_model.py`, `ks3_rig.py`, `ks3_clips.py`,
`gen_ks3.py`, `build_ks3.py`; texture sets `pbr_models_ks3.py`, `pbr_sets_ks3.py`). The source is read as data only
(scale 0.0054 m per unit: its bore becomes 19 mm); kept are the receiver (with its trigger guard, ejection port and
hardware), the pistol grip, the forend (with the bolt that shows in the port and rides with the pump), the trigger and
the loading gate, decimated by quadric edge collapse (`qem.py`) to 3.3k triangles; its perforated heat shield,
barrel, tube, sights and round are not used. Ours: a 0.49 m barrel with a knurled extended choke and a ventilated rib,
a picatinny rail on the receiver (`mount_optic` 0.115 behind the receiver face), the iron sights, a skeleton
buttstock with a ribbed pad (comb 3.3 cm under the sight line), the standard tube (ends 0.34 in front of the receiver,
knurled cap) and the extended one (out to the barrel clamp near the muzzle), steel action bars and channel sleeves on
the pump, the laser clamp, a crossbolt safety, and both shotshells. Overall 0.98 m; 7.4k triangles (fx_shell not
counted). Pump stroke 0.062 (the forend stops 1.4 cm short of the receiver). Hip pose: the receiver's front where the
AK's is, a little lower. `fire` is 0.685 s (the shot, then the support hand braces at 0.19 s, the pump hits its rear stop at 0.31 s —
`pump_back` at 0.307 s throws the hull — and battery at 0.405 s, `pump_forward` at 0.402 s).
The four reload clips meet in one loading pose (rolled 40° to the right, the empty left hand under the port; checked to
< 1 mm / 1°): `reload_shell` fetches the shell from below the view, carries it up under the port, thumbs it up and
forward into the tube (the gate swings in) and leaves it there, collapsed, at 0.42 s. The shell's local transform
under `arm_L_hand` is solved for every baked frame, so it follows its path exactly whatever the arm reaches. Texture
sets: the AWP's cerakote for the receiver, the P9's moulded polymer (forend, stock) and stippling (grip), common
parkerised steel, dark steel and brass, and `ks3/shell` for the hulls.

## 26. USP, dual USP and dual guns

**USP** (`models/gun/usp.glb`, weapon id `usp`): an original procedural .45 polymer pistol (generator like the P9's,
its own shapes: the long squared slide with the rounded nose, the exposed rounded hammer, the frame's accessory
rail, the slide-stop / decocker levers), built exactly to the P9 contract (§3–§6, §15–§17, §19): same node names,
clips, events and materials (`polymer`, `grip`, `slide`, `barrel`, `steel_dark`, `sight_dots`, `mag`, `mag_base`,
`brass`; regions as the P9's). Mounts as the P9 (`mount_stock` scaled 0.70). Item assets for `usp` (skins
`default`, `tan`, `two_tone`); sound `gun.usp.fire` (3 variants, a deeper .45 report). Checks: `check_usp.py`.

**Dual guns** (`guns.json` `"dual": {...}`, weapon id `usp_dual`, `models/gun/usp_dual.glb`): one item is a pair of
guns. The right gun is the ordinary contract (`gun` and every node under it); the left gun repeats **every node of
the right gun with the suffix `_L`** under its own root `gun_L` (a child of `viewmodel`): `frame_L`, `slide_L`,
`sight_iron_L`, `barrel_L`, `hammer_L`, `trigger_L`, `slide_stop_L`, `mag_L` → `mag_standard_L`, `mag_extended_L`,
`muzzle_L`, `ejection_L`, `mount_laser_L`, `mount_barrel_L` (no `mount_charm_L`: the charm hangs on the right gun).
There is no `aim_iron` and no `mount_optic` / `mount_stock` (dual guns do not aim). Each hand holds its own gun: the
right hand `gun`, the left hand `gun_L` (left-hand grip, finger on its trigger). Hip pose: the two pistols low left and
right of the screen, angled slightly inwards; the crosshair area (§2) stays clear.

The runtime applies, by that convention: the fitted magazine's node and its `_L` twin; the slide lock to
`slideLock.node` and its twin; an attachment fitted at a mount also at the mount's `_L` twin when the file has one (a
suppressor or laser on each gun); shots, flashes and cases from `muzzle` / `ejection` for the right gun's shots and
from `muzzle_L` / `ejection_L` for the left gun's.

Clips: `idle`, `draw` (both guns), `fire` (right gun), `fire_L` (left gun), `fire_last`, `fire_last_L` (that gun's slide
stays back), `fire_both` (both at once, a stronger kick), `reload` (≈ 2.4 s: both magazines drop, both new ones in —
the guns may dip out of view to swap), `reload_empty` (≈ 2.8 s: plus both slides released), `inspect` (≈ 4 s: the
pistols turned over and twirled), `walk`, `sprint`, `tac_sprint`. Events as the P9 (`hammer` may fire twice).

Gameplay (`guns.json`, `GunData.Dual`): the attack key fires the guns alternately (right first), the use key fires a
**volley** (both guns, two rounds, spread × `volleySpread`, costs `volleyCost` fire-rate credits); rounds are counted
for the pair (the magazine's capacity is both magazines); the server stays the authority (`C2SFire` carries `shots`
and `hand`, `S2CShot` the `hand` so other players see the flash at the right gun). Third person: a 3D gun in each
hand, the right `gun`, the left `gun_L` (§37).

**Special skin model `models/gun/usp_dual_cerberus.glb`** (skin `cerberus` 地狱犬 of `usp_dual`, §30): the same rig,
nodes and clips as `usp_dual.glb` (generated by the same rig and clip code) with different gun meshes: two large
ornate pistols based on *GunGrave Dual Pistols* by sandyyusuf0 (CC-BY-4.0), scaled to ≈ 0.26 m each, split into the
contract's parts (frame, slide, barrel, hammer, trigger, magazines, sights) and reworked into our own design (cross
emblems become our own engraved crest, red inlays, the slide with a ribbed top). Materials `cerberus_body` (body),
`cerberus_trim` (detail: the red inlays, emissive-free), `cerberus_grip` (grip), `barrel`, `steel_dark`, `mag`,
`mag_base`, `brass`. Item icons / held models `usp_dual_cerberus` as §1 / §8 skin variants. Sound
`gun.usp.fire_cerberus` (3). Effects `textures/effect/muzzle_flash_cerberus.png` (64×64) and
`muzzle_flash_side_cerberus.png` (64×32) in the §9 layout (a crimson, three-pronged flame). Checks: `check_dual.py`
(claims files with `gun_L`; every `_L` twin, both hands on their guns, clip set, framing of both guns, the slide
lock on both).

**The files as built** (`usp_model.py`, `usp_parts.py`, `gen_usp.py`; `dual_rig.py`, `dual_clips.py`,
`gen_usp_dual.py`, `cerberus_model.py`; steps `usp`, `usp_dual`, `usp_dual_cerberus`, `usp_items`, `usp_sounds`):
`usp.glb` keeps the P9's grip, magazine-well and pivot layout and re-runs the P9's rig and clips against its own
dimensions (`variants.py`), ≈ 4.7k triangles drawn. In the dual files the left gun is the right one's **mirror image**
(a left-handed twin: meshes mirrored in X, rest pose and every clip mirrored across the view's YZ plane), so its port,
slide stop and case throw face outwards and the left hand holds it exactly as the right hand holds its gun; clips
author both sides as a right-hand pose (the left a few hundredths of a second behind in two-handed moves, catching up
by the end). Hip pose: each gun low beside the crosshair square (gun pivot at ±0.14, −0.082, −0.34), slightly
canted in; the reload rolls both guns in to show their wells, flicks the magazines out (they fall under gravity), dips
the guns out of view for ≈ 0.35 s and slams them onto fresh magazines there (`mag_in` at 0.72 s), brings them back up
muzzles first and ends on a look at the magazines; the empty reload releases the slides one after the other (right at
1.555 s, left at 1.675 s); the inspect turns both flanks up, flicks each pistol once round the trigger finger and catches
it (the right at 1.86 s, the left 0.16 s later) and ends on a muzzles-up look. The Cerberus file adds the skin's own inspect `inspect_cerberus` and its effect nodes (§38). The Cerberus pistol keeps the source's raked grip (25.4°, its own `PistolLayout`: the rig fits the
hands, thumb presses and magazine pivot to it), is 0.26 m long and ≈ 4.5k triangles drawn; the converted source parts
are cached in `tools/assets/data/cerberus_parts.npz`, so it rebuilds without the source file.

## 27. Dagger (`models/knife/dagger.glb`, weapon id `dagger`)

An original procedural fixed-blade combat dagger (§11–§13): double-edged spear-point blade ≈ 0.17 m with a central
ridge and a fuller, a short steel crossguard, a ribbed rubber grip ≈ 0.11 m and a steel pommel with a lanyard hole.
Held point-forward in the right hand (rest pose like the balisong's).

```
viewmodel
├─ knife                  root at the right-hand grip
│  ├─ blade (mesh), guard, handle, pommel   meshes
│  ├─ tip                 empty at the point
│  └─ mount_charm         at the pommel's lanyard hole (§19)
├─ camera, arm joints (§16)
```

Clips: `idle`, `draw` (≈ 0.6 s: drawn from below, flipped once in the hand), `light_1` (forehand slash, contact at
0.11 s of 0.36 s), `light_2` (backhand slash, 0.11 / 0.36), `heavy` (a quick straight thrust, full extension at
0.33 s of 0.80 s), `inspect` (≈ 3.4 s: spun on the finger, flipped to reverse grip and back, the blade shown), `walk`,
`sprint`, `tac_sprint`. Events `equip`, `swing`, `swing_heavy`, `flip`, `catch` (sounds of §13). Materials: `blade`,
`blade_edge` (blade), `guard`, `pommel` (hardware), `handle` (handle). Item assets for `dagger` as §11. Checks:
`check_dagger.py` (claims files with `knife` + `pommel`; contact times = the data's fractions).

## 28. More stocks

Two more stock attachments (§19, slot `stock`), drawn at every `mount_stock`:

| File | Look | Data |
|---|---|---|
| `heavy_stock.glb` | solid adjustable stock: buffer tube, a thick butt with a cheek riser and a 25 mm rubber pad, extends +Z ≈ 0.27 | recoil × 0.75, ADS × 1.12, hip spread × 1.05 |
| `wire_stock.glb` | minimalist folding wire stock: two bent steel rods and a small butt plate, extends +Z ≈ 0.22 | recoil × 0.92, ADS × 1.01 |

Materials as `tactical_stock` (`attachment` stock, `steel_dark` stock, `stock_pad` none). Every weapon with a
`mount_stock` (P9, P9A, AK-47, Thompson, KS-3, USP) must keep them ≥ 0.06 from the eye in every clip (`check_attach.py`
builds `heavy_full`, `wire_full`).

## 29. Sprays (喷漆)

Sprays are square RGBA decals a player places on a weapon in the 工匠 screen. Data: `data/blockland/craft/sprays.json`
(`limits`: `perWeapon`, `minSize`, `maxSize` in metres; `sprays`: id → `name`, `texture`, `rarity`, `for`). Textures:
`textures/spray/<id>.png`, 256 × 256, straight alpha, the design filling the square with a soft 2–4 px edge and a
transparent border of at least 4 px; original designs only (`gen_sprays.py`, checked by `check_sprays.py`).

**Placement** (`craft/Spray`, kept in the player's build on the server, checked there without the model): `side`
(`left` = seen from the weapon's left, i.e. from −X looking +X; `right` = from +X looking −X), `u`, `v` (0..1 across
that side's view as the viewer sees it: `u` left → right, `v` top → bottom), `size` (edge in metres) and `rotation`
(degrees, counter-clockwise as the viewer sees it). The view's frame is the weapon's **rest-pose bounds**: the box
around every mesh under the weapon root (`gun` / `knife`, magazines of every option included, attachments not) in the
idle's first frame, **in the root's own axes** (X, Y, Z above are the root node's: +X right, +Y up, −Z the muzzle /
point — not viewmodel space, so the hip-pose cant, a knife held point-up or a dual gun angled inwards do not tilt the
side views), seen from that side (left side: `u` = 0 at the box's min Z = the muzzle end, 1 at max Z; right side:
`u` = 0 at max Z, 1 at min Z; `v` = 0 at max Y). So a placement does not move when attachments change. The 工匠
preview turns to that side square on while the spray page is open.

**Projection** (client, `client/spray`): the spray's square stands on the side plane at (u, v); it is projected along
the view direction onto everything drawn of the weapon and its fitted attachments, except materials of region `none`
(glass, lenses, brass, emissive parts, charms). Depth: the nearest surface hit by rays through the square (centre
first, else a 5 × 5 grid) fixes `d`; only triangles inside the slab `d − 4 mm … d + 30 mm` that face the projector
(normal · −direction > 0.25, fading in to 0.5) receive it; nothing is drawn when no ray hits. The decal is built once
per build in rest-pose space and split per weapon node, so it rides with its part in every animation (a spray on a
magazine leaves with it). Drawn after the opaque meshes, alpha-blended, lit like paint (dielectric, rough), a hair
above the surface; later sprays on top. The same geometry draws in the 工匠 preview. Third-person held models do not show sprays.

## 30. Special skins and the neon shader skin

A skin is **special** when it has a `model`, an `fx` or an `inspect` object (`craft/CraftData.Skin`): the 工匠 lists it in
the 特殊皮肤 tab (rarity `special`).

* `model` — a GLB with the weapon's node contract (the same nodes, mounts, clips and events; magazines and helper
  nodes by the same names), drawn instead of the weapon's own in first person and in the preview; the item icon and
  held model are the skin's (§8 skin variants).
* `fx` — the shots: `flashColor` (tints the muzzle flash), `flashTexture` / `flashSideTexture` (replace the §9 flash
  sprites), `flashScale`, `tracerColor`, `tracerWidth`, `fireSound` (a suppressor still wins). The shooter's viewmodel
  and everyone else's view of the shots follow it (other clients read the skin from the shooter's held item); the
  server only uses `fireSound`. Also `hitColor` and `kill` (§38: the kill marks' colour and the weapon's answer to a kill).
* `inspect` — an inspect of the skin's own (§38).

Examples: Thompson `chicago` (gold metal, golden flash and tracer), KS-3 `dragon` (magma skin, big orange flame and
burning tracers), AWP `thunder` (pale blue flash and tracer), dual USP `cerberus` (its own model, crimson flame,
red tracers, its own report).

**Effect `circuit`** (shader effect id 4, §21's table): neon circuit traces laid out in the weapon's rest-pose space,
glowing in `effectColor`, with light pulses running along them and the hue drifting. Params: pulse speed (m/s),
trace density (cells per metre), glow 0–1, hue drift (cycles/s); defaults 0.25, 40, 0.8, 0.05. The shared skin `neon`
(霓虹回路, `for` gun and knife) puts it on every region through `*` — body, barrel, magazine, stock, grip, handguard,
detail and every fitted attachment (optic, laser, barrel, stock) — over the texture set `skin/neon/base` (a dark
glossy lacquer; its `_s` emission carries a static trace pattern). So the pattern runs on across the
weapon and its attachments without a seam, attachments are drawn in the weapon's effect space: their effect origin
is the mount's rest position plus their own node origin.

---

# Animation direction v3 (COD-grade first person)

## 31. How weapon motion is authored and layered

Everything above still holds (clip names, events, lengths stretched to the data's times, camera node, 60 Hz bakes).
This section is the quality bar every clip is authored to, the tools for it, and what the runtime layers on top, so
clips do not double it.

### 31.1 Principles

* **Weight is time and force.** Big masses (the weapon, the forearms) accelerate and decelerate over several
  frames; nothing starts or stops instantly except at a *hit* (an impact) or a spring-driven *release*. A one-shot
  never leaves its first key at full speed (`anim_qa` launch ≤ 20 %; shots and draws excepted).
* **Anticipation.** Before every big move a small counter-move of 5–15 % of it, 0.04–0.10 s: the weapon dips / rolls
  the other way before rolling into a reload, winds up before a slam (magazine seat, slap, slide or bolt release), the
  knife cocks back before a slash.
* **Force transfer.** The hand acts, the weapon reacts: every push (seat, slap, rack, pump, bolt throw) gives the
  weapon a damped response along the push (`anim.ring`: 2–5 mm and 1.5–5°, 6–10 Hz, ζ 0.3–0.45) and the camera an
  accent; the other hand absorbs it (wrist flex, fingers clench, a few frames late).
* **Overshoot and settle.** Arrivals pass their target by 5–12 % (1–4 mm, 1–4°) and settle over 0.1–0.25 s; big
  rotations land with follow-through.
* **Moving holds.** In a hold longer than ~0.15 s the weapon keeps drifting (`anim.drift`: 0.5–1.5 mm, 0.3–1°,
  0.4–0.8 Hz) and the hands make micro-adjustments; one-shots do not freeze (`anim_qa` freeze ≤ 0.15 s away from the
  clip's ends).
* **Arcs.** Moves follow arcs (`anim.arc`, `"flow"` keys), not straight lines; the muzzle leads a raise, the stock
  trails a swing; rotation and translation peak at different times.
* **Overlap.** Big masses lead, small parts follow: fingers 0.03–0.06 s behind their hand, a magazine in the hand on a
  6–9 Hz `anim.Follow`, the support arm 0.02–0.04 s behind the weapon; magazines, slides and bolts move after the hand
  that drives them starts to move.
* **Mechanical truth.** Slides, bolts, pumps and hammers are springs: pulled slowly against the spring (slow in, faster
  out), released fast (accelerating into battery, a `"hit"`), stopped dead with a 0.5–1.5 mm rebound, and the weapon
  answers their mass (a slide slamming home dips the muzzle 1–2° and rings). A dropped magazine falls under gravity
  (≈ 9.8 m/s², tumbling); a new one is aligned slowly at the well (0.06–0.12 s) and then seated with a hit.
* **The camera follows the body.** It leans into a reload, jolts at every impact (seat, slam, slap, catch), rolls a
  little with an inspect flip and is back at zero at the end; within ±2.5° pitch / yaw and ±4.5° roll. Fire clips keep
  their camera small (the runtime adds the kick).
* **Rhythm.** Actions are fast, recoveries breathe: do the work quickly, then let the weapon swing back to ready with
  follow-through. A reload reads as: anticipation (0.05–0.1 s) → roll into the presentation (0.2–0.3 s) → release
  (thumb press, the weapon jolts) → the old magazine falls → the off hand fetches (quick, mostly off screen) → align
  (slow) → seat (hit + ring + camera) → [empty: slide stop / bolt / charging handle: wind-up, slam, ring, camera] →
  back to ready (0.3–0.45 s with overshoot) → re-grip (fingers settle).

### 31.2 What each clip shows

| Clip | Targets |
|---|---|
| `draw` | Starts off screen (bottom right) and stays off it for the first ~0.08 s of the stretched clip (the outgoing weapon is still dropping away then, §31.4). Whipped up with the muzzle leading, passes the ready pose by 3–6° / 3–6 mm at 55–65 % of the clip, settles; the support hand catches the weapon (open → close, a squeeze) around 70 %. |
| `fire`, `fire_last`, `fire_L`, `fire_both` | Trigger break, then the mechanism (slide / bolt / carrier cycles with the hit and rebound, hammer, the pump or bolt cycle of a manual action). The clip's own kick is the hands' reaction — the wrists flex, the support fingers clench — at most ~3° and ~6 mm; the runtime adds the root recoil (§31.4). Ends exactly at rest. |
| `reload`, `reload_empty`, shell clips | The beats of §31.1; `mag_in` on the seat hit; the empty reload adds the slide stop / bolt / charging handle with its slam and the weapon's reaction. Shell clips (§25) still meet in their one loading pose; each shell push gives the weapon a small ring. |
| `inspect` | 3–4 showcase poses, each reached with follow-through and held as a moving hold; one mechanical flourish (press-check, bolt or pump pulled, a spin); heavy transitions; return with settle. |
| `idle` | Almost still: tiny weight shifts and finger life (the runtime breathes). |
| `walk` | Weight on each foot contact (a quick dip ≈ 0.1 s after contact, a soft rise), counter-roll, the weapon a few hundredths behind the body. |
| `sprint`, `tac_sprint` | High energy: strong arcs, the free hand pumping as a fist, an impact on each foot strike, secondary bounce of the weapon; seamless loop. |
| knife attacks | Anticipation (wind-up 0.06–0.1 s, wrist cocked), the whip peaks in speed at the contact time, follow-through past the target with a long deceleration, recovery with a small overshoot; the camera leans along the cut. |

### 31.3 Tools

* `anim.py`: `Curve` (C1 Hermite through keys — `"auto"` monotone default, `"flow"` sweeps through, `"stop"`, `"hit"`
  arrives at speed and stops, `"snap"` leaves a hold at speed, explicit tangents, classic eases per segment),
  `QuatCurve` (orientation keys), `Follow` (a damped spring following a driver: lag, overshoot), `ring` (impulse
  response), `drift` (moving holds), `arc`. `Track` (classic per-segment eases) stops at every `"inout"` / `"sine"` key —
  use it only where a stop is meant.
* `anim_qa.py <weapon> [clip …]`: per clip the peak speeds, hitches (a stop between two moves in the same direction),
  the longest freeze, launch, start / end distance from the idle's first frame and the camera range, plus a curve plot
  `out/qa_<file>_<clip>.png` (root offset and turn, speeds, part travel, camera, events). Targets for one-shots: no
  hitches unless a `"hit"` stop is meant there, freeze ≤ 0.15 s, start / end within ≈ 1 mm / 0.5° of the idle's first
  frame (the draw: end only), launch ≤ 20 % (shots and draws excepted).
* `preview.py` contact sheets and `preview.py frames <weapon> <clip> t1,t2,…` for the poses.

### 31.4 What the runtime adds (do not bake it into clips)

* **Inertial transitions.** When a clip starts, ends or is interrupted the displayed pose leaves the previous motion
  with its velocity and blends into the new clip (dead blending; ≈ 0.05 s for shots, 0.1–0.25 s otherwise), so chained
  and interrupted clips never pop. Clips still start and end on the rest pose.
* **Recoil.** Spring recoil at the root (`client/gun/GunRecoil`), per weapon from its `handling` in guns.json:
  `recoilBack`, `recoilRise`, `recoilTwist`, `recoilSnap` (spring stiffness), `recoilShake` (multipliers, default 1)
  and `recoilPivot` (metres the kick pivots behind the grip, default 0: a shouldered stock ≈ 0.12–0.18). Stronger and
  more jittery as sustained fire heats up (the weapon rides back and climbs), calmer when aimed — aimed it is a shove
  back and a roll with only a trace of rise and yaw, because aimed shots follow the drawn sight line; a small camera
  punch and a trauma² shake per shot; a flinch on a dry click. Fire clips keep their own kick modest.
* **Fast automatic fire.** A fire clip longer than the fire interval is played faster so its mechanism finishes
  before the next shot.
* **Aiming.** The weapon arcs in and out of the sights (a cant, a small dip and pull towards the cheek mid-way) and
  settles on arrival.
* **Sprint.** Entering and leaving the carries arcs the weapon (a dip and roll mid-way) and leaving settles with a
  small bounce; locomotion clips stay seamless loops.
* **Sway and inertia.** Look sway (translation and rotation lag), movement inertia, jump and landing, breathing.
* **Locomotion camera, per direction.** The walk cycle advances with the distance travelled, with shorter steps
  backwards (0.72 of the forward stride) and sideways (0.84). The view's rhythm takes shares of forwards / backwards /
  sideways from the velocity relative to the view: forwards a deep nod twice per cycle (0.4°), a roll (0.66°) and a
  sway over each foot and a lean into the walk (0.5° down); backwards shorter, choppier steps (a sharper wave), less
  nod (0.24°) and the body held back (0.4° up), each footfall knocking the head back; sideways little nod, a strong
  lean into the strafe (1.2° roll at walking pace) and the head carried across over the leading foot (≈ 3 mm, 2.8 mm
  per lead step). Sprints deepen it (nod 0.62–0.78°, roll 1.4–1.75°, lean 0.85–1.05°). The head rises and falls
  (lowest on each footfall, 2.4 mm walking forwards, 4–5 mm sprinting) as continuous camera movement. All of it is
  calmed while aiming and scaled by the locomotion comfort setting.
* **Weapon switch.** The outgoing weapon drops away for ≈ 0.2 s (it keeps its motion) while the new one's `draw` rises.
* **The hands' force.** Every clip event also shoves the whole rig — weapon, both arms, camera — by
  `assets/blockland/viewmodel/impulses.json` (event name → first-peak `back` / `up` / `side` mm, `pitch` / `yaw` /
  `roll` degrees about the weapon's pivot, `camera` [pitch up, yaw, roll] degrees; resource packs may override it), so
  a slap or a slide slam jolts the hand that only holds the weapon too. The shove shrinks with the weapon's weight, its
  turn with the rotational inertia. Clip rings stay as the local detail; the event shove is the body's share.
* **Weight and rotational inertia.** Mass (`handling.weight`) sets how far shoves, landings and accelerations move the
  weapon; mass × (reach / 0.42 m)^1.2 (0.4–10) — reach = eye to `muzzle` (knives: `tip`) in the rest pose, measured from the
  model — sets the look sway's springs, so a long rifle trails turns wider and settles later than a pistol.
* **Surroundings.** Rays along the view and from where the weapon sits, out to its reach: a wall in front pushes the
  weapon back and tips it up and in (the pose yields to aiming — the sights still come up — and the weapon fires from
  either pose); something close on
  the right tucks it in; looking down raises and extends it a little, looking up lowers it; a jump leaves it behind and
  it floats in the air, a landing compresses the arms (deeper for heavy weapons) with a muzzle dip and a camera jolt;
  crouching and pushing off into a sprint jolt it.
* **Swimming** (`client/viewmodel/SwimPose`, a layer of the motion). Vanilla's swimming pose is a sprint in water, so
  weapons stop only a *run* when the hands need them (`SwimPose.stopRunning`; the movement input's sprint-key drop
  skips a swimmer) and the run's carries and its "fire waits for the carry" rule never apply to a swim
  (`SwimPose.running`). While swimming the weapon rests in a carry (guns low and close, canted in, muzzle down; knives
  held forward, `SwimPose.Style`), rides the strokes (a cycle per 2.4 blocks swum, a slow paddle when treading water:
  roll, bob and a surge on each pull) and the body's slow rise and fall; the support arm (`arm_L`, not on dual guns) is
  out of view pulling the water. Any one-shot clip (a shot, a reload, a cut) or aiming brings it up forward within
  ≈ 0.1 s and the free arm back on the weapon; 0.45 s after the hands last worked it, it sinks back into the carry.
  Aimed in the water the sights come up 1.6× slower and the weapon wanders more (look sway calmed less, a slow drift).
* **Frame-rate independence.** Every runtime layer runs on springs (fixed 240 Hz substeps) or elapsed time, input
  rates are clamped per second, not per frame, and clip curves are read smoothly (§15).

### 31.5 Aimed clips (`<clip>_ads`)

A clip named `<clip>` + `_ads` (guns.json `anims.aimedSuffix`, default `_ads`) is the aimed variant of `<clip>`; today
`fire_ads` and `fire_last_ads`. When the runtime plays a fire clip it blends it with its aimed variant by the aim amount
(0 at the hip, 1 fully aimed; `ViewmodelRig.playVariant`), on the hip clip's clock; a model without the variant plays
the hip clip alone. Contract (`check_ads_clips.py`, run by `check_glb.py` on every weapon file):

* the hip clip exists; same length (±1 ms) and the same events at the same times;
* the camera inside the §4 limits and at rest at the end;
* at EVERY frame, with the viewmodel moved as the runtime aims (the idle's first frame's eye point on the camera): the
  front post tip and, through every optic (`loadouts.OPTICS`), its reticle / window centre / eyepiece within 2 px of
  the screen centre at 1920×1080, and nothing in front of the eye closer than 0.03.

`ads_fire.aimed(FIRE, rig, eye_point, IDLE, **shape)` derives them from the hip pose function, so the mechanism, fingers
and events are the hip clip's: the hands' shove back is kept along the sight line (≤ `max_along`, 3 mm), sideways drift,
pitch and yaw are cut to a trace and taken about the eye point (≤ `max_turn`), the roll about the sight line is kept
small (an optic sits 3–5 cm above the iron line), the camera is calmed; the runtime's aimed recoil does the rest
(§31.4). Pistols ride their optics on the slide (34 mm closer to the eye at the peak), so they keep almost no roll or
tilt; tight-clearance guns (UZI rear ears, Jackhammer, KS-3 stock) take smaller shapes. Bolt actions (AWP, SSG 08) have
none: their sight comes down for the bolt (`BoltCycle`). Built for: AK-47, M4A1, MP5, P9, USP, UZI, P90, STEN, Calico,
SCAR-H, Jackhammer, RPD.

### 31.6 The hand-keyed detail layer (`motion_layer.py`)

Code-authored clips are clean C1 curves through sparse keys; an animator's keys or a mocap take carry a dense layer of
small irregular motion. `motion_layer.layered(clip, rig, seed)` returns the same clip (name, length, events) with it:

| Part | What |
|---|---|
| overlapping action | the weapon follows its authored path through damped springs (position 7.5 Hz ζ 0.5, turn 6 Hz ζ 0.45; the lag capped at 6 mm / 4°): it trails fast moves and overshoots / settles; the gun hand stays on the grip but its palm turns on the authored path, so the wrist leads |
| settle micro-motion | where a big turn (> 60°/s) comes to rest, a small damped wobble (5–5.5 Hz, ζ 0.35, ≤ 1.5 mm / 1.2°) along the way it came; a handheld tremor (0.35 mm, 0.18°, ~2 Hz) stronger in holds |
| anticipation | before `mag_release`, `mag_out`, `slide_back`, `bolt_back`, `slide_release`: a counter-move of 18 % of the next 0.14 s of motion (≤ 3 mm / 2°) over 0.13 s |
| asymmetric hand adjustments | a support hand placed in viewmodel space wanders on its own seeded curve (1.2 mm, in holds); every wrapping finger wanders with its own amplitude and phase |
| finger re-grips | in calm stretches away from events (about one per 1.4 s) the gun hand's middle / ring / pinky loosen 14° and clamp back with an overshoot, staggered 25 ms per finger; the support hand's follow 0.11 s later while it holds the weapon |

Deterministic (`seed`), faded in over 0.1 s and exactly gone at the end (clips still start and end on their authored
pose), inside the eye-distance and framing checks. Applied to `draw`, `reload`, `reload_empty`, `inspect` of the AK-47,
M4A1, MP5 and P9 (the USP inherits the P9's). Other guns: wrap their clips the same way (one line per file).

## 32. Camera feedback

Every weapon viewmodel owns a `client/camera/CameraRig`; everything that moves the view goes through it, on top of the
clip's `camera` track (§4) and the motion layer's locomotion sway. Layers:

| Layer | What | Used by |
|---|---|---|
| kick | pitch / yaw / roll in two stages: a fast *snap* spring (ω 48–58, ζ ≈ 0.45: the jolt, peaking in ~25 ms) and a slow *body* spring (ω 9–10, ζ ≈ 0.7: head and shoulders following through); a kick splits between them (`snap` share) | shots, clip events, landings, footfalls, crouch, pushing off, knife hits |
| lean | continuous impulses on the body layer from the body's acceleration | starting and stopping (pitches against it), strafe changes (roll) |
| shake | *rattle*: barely damped oscillators (14–19 Hz per axis, ζ ≈ 0.16, plus a hair of head movement) struck in a fresh direction by every shot or slam — the frame rings like struck metal and dies out in ~0.2 s, building up in a burst; *rumble*: a trauma channel (5.2 Hz, decay 1.8/s: weight) through smooth fractal gradient noise, amplitude trauma² (≤ 1.2) | shots, heavy slams (`shake` in impulses.json), landings, sprint footfalls, hits |
| FOV punch | spring, percent of the FOV | shots (wider), knife hits (narrower) |
| head movement | spring, mm in camera space, ≤ 30 mm, moves the rendered camera (the hand pass stays with the head); plus the continuous locomotion bob (`bob`, under the locomotion comfort setting) | shots (pushed back), landings and crouching (compressed), footfalls (per direction: down forwards, back backwards, across sideways), pushing off, the walk's rise and fall |

The weapon's own recoil (§31.4) carries a *crack* layer under its travel springs: 42 % of the shove and flip on stiff
springs (ω 110–130) that peak ~12 ms after the shot and are gone in ~60 ms, so a shot lands as an instant crack and
then the heavier travel and recovery. Fire clips cut straight in (no blend: their first frames are the crack).

A shot (`GunRecoil`) per unit of the gun's kick: 0.34° up (heavier rounds lean on the body layer: a shove, not a
crack), a yaw drift with the twist, a 0.55° roll swinging to the other side than last time three shots in four (a
rhythm, not jitter), a 1.1 % FOV punch, 3 mm back, a rattle strike of 0.26 (growing with heat) and rumble 0.32 trauma — scaled by the
weapon's `handling` camera profile: `cameraKick`, `cameraRoll`, `cameraFov`, `cameraRumble` and `recoilShake` (buzz).
Clip events add `camera` and `shake` from impulses.json (§31.4).

Aim accuracy: aimed shots follow the view, so with the sights up the rig scales pitch and yaw (kicks and shake) by
0.45 / 0.4, the head movement by 0.5 and the FOV punch by 0.7; roll (it turns about the aim point) stays whole. The
real recoil (guns.json `recoil`) is applied to the player's look elsewhere and is not part of this layer.

Comfort: the player's settings scale impacts (kicks, shake, FOV, head movement) and the locomotion sway separately,
0–150 % (设置 → 武器 → 镜头).

---

# Round H: RPD, karambit, new attachments

## 33. RPD light machine gun (`models/gun/rpd.glb`, weapon id `rpd`)

An original procedural 7.62×39 belt-fed light machine gun at real scale (overall ≈ 1.04 m, barrel ≈ 0.52 m), built like
§18 (rifle contract: `bolt` +Z rearward) with a hinged feed cover and a belt:

```
viewmodel
├─ gun                       pivot at the top of the pistol grip
│  ├─ receiver, handguard, barrel, front_sight, stock, pistol_grip   meshes (stock: wooden; hidden by the stock slot)
│  ├─ feed_cover             hinged at its front (node origin on the hinge pin): rotation about X, negative opens
│  │                         (the rear lifts, ≈ −60°); closed at rest and at the end of every clip
│  ├─ bolt                   carrier and the right-side charging handle (stroke 0.095)
│  ├─ trigger, muzzle_brake (slotted flash hider, hidden by the barrel slot)
│  ├─ sight_iron             front post with its ears; sight_rear: the leaf on the trunnion (both hidden by an optic)
│  ├─ aim_iron               eye point 0.30 behind the rear notch (cheek on the comb, clear of the rear rail)
│  ├─ belt                   the belt's end piece: one round in its link lying at the feed tray entry (left side);
│  │                         carried by the hand in the reloads, or hanging off the drum's mouth; STEP scale 0 while
│  │                         its drum is hidden; back on the tray at the end of every clip
│  ├─ belt_1 … belt_11       the rest of the belt, one round + link each: laid every frame (rpd_rig.belt_chain) along
│  │                         a curve leaving the end piece along its −X and entering the drum's mouth downwards, a link
│  │                         (12.5 mm) apart; pieces past the mouth run on inside the drum (hidden). The belt is always
│  │                         one piece from the drum to its end; it goes wherever its drum goes
│  ├─ mag ─ mag_standard (100-round drum), mag_extended (150)   round drums hung under the receiver, axis along Z
│  │                         (the bore: the coiled belt's rounds already point forward), the mouth the belt leaves from
│  │                         at the upper left (the same for both drums)
│  ├─ muzzle, ejection (right of the receiver)
│  └─ mount_optic (rail on the rear of the receiver, behind the cover), mount_laser (right of the handguard, turned
│                            90° about Z), mount_barrel (crown), mount_stock (receiver rear), mount_charm (left of the
│                            receiver), mount_underbarrel (under the gas block, ahead of the support hand, §35)
├─ camera, arm joints (§16)
fx_shell                     separate root: the AK's 7.62×39 steel case
```

Clips (§4 / §17 / §31): `idle`, `draw` (0.95 s, heavy: off screen 0.1 s, the muzzle leads the heave up, the support hand
slaps the handguard at 0.56 s), `fire` / `fire_last` (open bolt, 700 rpm: 0.086 s; the carrier hits its buffer at
0.021 s and is home at 0.063 s with a rebound; the feed pawl drags the whole belt a link into the tray between 0.016
and 0.032 s — at the end every piece sits where its neighbour was, so the clip loops seamlessly), `reload` (5.5 s, the
belt change: fingertips under the latch, the cover snaps up `cover_open` 0.63 s, the old belt pinched out `belt`
1.05 s and let go at 1.27 s — it drops and swings, hanging off the drum's mouth — the drum unhooked with it `mag_out`
1.55 s — the gun rides higher without its weight — a full drum hooked on `mag_in` 3.05 s, its belt end pulled out of
the mouth and laid on the tray `belt` 3.76 s, the cover slammed `cover_close` 4.35 s, the biggest jolt of the
reload), `reload_empty` (6.0 s: the same, then a reach-under pull of the charging handle `bolt_back` 4.98 s,
`bolt_forward` 5.11 s), `inspect` (4.5 s: the left flank and the belt, the cover lifted for a look and slammed, a roll to
the right side, the bolt rattling in its rails), `walk`, `sprint`, `tac_sprint` (the loose belt bounces on each step).
Events play `gun.<event>`: `cover_open`, `cover_close`, `belt` are new (impulses.json shoves them too).

| Material | Region | Used on |
|---|---|---|
| `steel` | body | receiver, feed cover (split: `steel_barrel` → barrel, `steel_stock` → stock butt plate) |
| `steel_dark` | detail | bolt, trigger, sights, rail, links, bullets (split: `steel_dark_barrel` → front sight, flash hider) |
| `wood` | stock | stock (split: `wood_handguard` → handguard, `wood_grip` → grip) |
| `mag`, `mag_base` | magazine | drums |
| `brass` | none | cartridge cases, `fx_shell` |

Texture sets: the AK's (`ak47/wood`, `ak47/steel`, `ak47/mag`) and the common ones (`pbr_models_rpd.py`). Item assets
for `rpd` (skins `default`, `black`, `desert` + the shared skins; icons rendered with the 100-round drum); sounds
`gun.rpd.fire` (3), `gun.rpd.fire_far`, `gun.cover_open`, `gun.cover_close`, `gun.belt` (`gen_rpd_sounds.py`). Data:
guns.json `rpd` (category `lmg`, ammo `rifle`, magazines `rpd_box` / `rpd_box_ext`, auto only, wide hip spread, heavy
handling, slots incl. `underbarrel`: vertical_grip / angled_grip / bipod, stock options incl. `stock_none`).
Files: `rpd_model.py`, `rpd_rig.py`, `rpd_clips.py`, `gen_rpd.py`, `gen_rpd_items.py`, `gen_rpd_sounds.py`,
`build_rpd.py` (steps `rpd`, `rpd_items`, `rpd_sounds`), `check_rpd.py` (claims files with `feed_cover`; the cover's range
and closing, the belt's STEP scale and return, the drum leaving in both reloads, plus check_rifle's clip rules).
9.0k triangles (both drums and every belt piece counted).

## 34. Karambit (`models/knife/karambit.glb`, weapon id `karambit`, 爪子刀)

An original procedural karambit (§11–§13): a hooked claw blade (≈ 0.095 m along its curve, bending ≈ 77° towards the
finger side, the concave inner edge sharpened, a thick rounded spine, a needle point), a slim G10 handle with three
finger grooves and a small guard at the blade, and a steel finger ring with a lanyard tab. Held in a reverse "claw"
grip: palm on the spine side of the handle, middle, ring and little finger wrapped round it, the thumb along it, the
index finger through the ring; the claw comes out under the little finger and hooks forward.

```
viewmodel
├─ knife                  root at the middle of the handle
│  ├─ blade (mesh) ─ tip  the point
│  ├─ handle, ring        meshes; the ring's hole runs along the knife's Y (the flats' normal)
│  └─ mount_charm         at the ring's lanyard tab (§19)
├─ camera, arm joints (§16)
```

The ring sits where the claw grip's index finger runs (`karambit_rig.RING_C`, forward kinematics of the grip), so the
finger passes through its hole in every frame (checked: ≤ the 8.8 mm hole radius). The knife can spin about the
ring's axis through its centre (`KarambitPose.spin`): it turns round the index finger while the other fingers let go.
Clips: `idle`, `draw` (0.70 s: up from below, a full spin round the ring, caught in the claw at 0.44 s), `light_1`
(forehand hook right to left, the claw raking across the centre at 0.12 s of 0.38 s), `light_2` (backhand hook from
low left, up through the centre at 0.12 s), `heavy` (hammer-fist hook: coil beside the head, the drive to full
extension at 0.36 s of 0.85 s, the rip back and down), `inspect` (3.6 s: two spins forward and one back round the
ring, a half spin into a forward grip and back, the blade presented with the left hand), `walk`, `sprint`,
`tac_sprint`. Events `equip`, `swing`, `swing_heavy`, `flip`, `catch`. Materials: `blade`, `blade_edge` (blade),
`handle` (handle, set `karambit/g10`), `ring` (hardware, `dagger/blackened`). Skins `default`, `black`, `tiger`.
Files: `karambit_model.py`, `karambit_rig.py`, `karambit_clips.py`, `gen_karambit.py`, `gen_karambit_items.py`,
`build_karambit.py` (steps `karambit`, `karambit_items`), `pbr_models_karambit.py`, `pbr_sets_karambit.py`; checks
`check_karambit.py` (claims files with `knife` + `ring`: nodes, materials, clips, contact times at the data's
fractions, the index finger through the ring, crosshair, eye distance, budget). 1.9k triangles.

---

## 35. Rectangular red dot, underbarrel slot, no stock

**`reddot_rect`** (长方形红点镜, slot `optic`, `models/attachment/reddot_rect.glb`, `underbarrel_models.py`): an open
reflex sight with a rectangular window, no magnification (`zoom` 1.12 like the iron sights, `adsTimeScale` 1.03,
`reflex` dot 10 MOA). A low picatinny clamp with a clamp screw, a flat base housing (brightness buttons left, battery tray
right, the LED emitter at the rear) and a thin frame round the window. The window is one flat `glass` face 42 × 22 mm
(a wide, low window) with 2 mm corner radii, centred 0.0242 over the mount point at z −0.012 (the frame's rear face), facing +Z, UV 0..1
across it (u along +X, v down); the frame's inner walls (`interior`) open forward (17 % sideways, 11 % up and down) more
steeply than the sight lines from `aim` to the window's edges, and the base housing stays under the sight lines to its
bottom edge, so from the eye only the window shows. A `reticle` quad (the dot when picture in picture is off) sits on the sight
line just behind the glass, `aim` 0.14 behind it. Region `optic`; materials `attachment`, `steel_dark`, `glass`,
`interior`, `reticle`.

**Underbarrel slot** (`underbarrel`, 下挂, skin region `underbarrel`): attachments clamped under the handguard or rail.
The weapon file carries an empty `mount_underbarrel` (§19 mount rules: −Z down the bore, +Y up; the attachment hangs
along −Y) on the underside of the handguard / forend rail, ahead of the support hand: the clips keep the hand on the
handguard, no clip changes with the attachment, and the runtime moves the hand onto the attachment (*The support
hand's hold*, below). Models (`underbarrel_models.py`, frame as every attachment, root `attachment`):

| File | Look | Data |
|---|---|---|
| `vertical_grip.glb` | rail clamp with a thumb screw, a tapered finger-grooved grip ≈ 0.10 m straight down, end cap | recoil × 0.88, ADS × 1.05 |
| `angled_grip.glb` | a long low clamp and a wedge whose rear face slopes down and forward (a palm stop), ribs on the ramp | recoil × 0.95, ADS × 0.93 |
| `bipod.glb` | clamp, hinge yoke, two legs folded forward along −Z (≈ 0.18 m), rubber feet | recoil × 0.82, ADS × 1.12, hip spread × 1.1 |
| `tommy_grip.glb` | the Thompson's wooden vertical foregrip (§24's source part, slimmed front to back: its long top along the barrel clamp and the finger-grooved grip), material `wood_foregrip` (walnut, `tommy/walnut`) | none (the gun as issued) |

Materials `attachment`, `steel_dark` (region `underbarrel`), `stock_pad` (the bipod's feet, `none`), `wood_foregrip`
(region `underbarrel`; the Thompson skins' tints name it). Mounts as built: AK-47 under the lower handguard near its
front (gun-local design point (0, −0.0315, −0.178), the grip's front edge at the handguard's front), AWP on the
accessory rail under the front of the forend, RPD under the gas tube; the Thompson under the barrel clamp's plate where
its foregrip sat (`tommy_model.FORE_MOUNT`, design (0, −0.0258, −0.105)); the KS-3 under the front of the forend,
parented to `pump` (pump space (0, −0.039, −0.135)) so it rides the pump's stroke ahead of the support hand. Pistols
(p9, p9a, usp, usp_dual, calico) have no underbarrel slot (their files may keep the mount; nothing is drawn there), and
the bipod is for machine guns and sniper rifles only. guns.json options: ak47, ks3 and the section 40 rifles, SMGs and
shotguns [vertical_grip, angled_grip], awp and ssg08 [bipod], rpd [vertical_grip, angled_grip, bipod], tommy default
`tommy_grip` [vertical_grip, angled_grip]; a weapon file without the mount simply does not draw the attachment
(`loadouts.OPTIONAL_SLOTS`).

**The support hand's hold** (`client/viewmodel/SupportGrip`, mirrored for checks and previews by `support_grip.py`):
an underbarrel attachment's `grip` in `craft/attachments.json` is where the left hand holds it, in the attachment's
frame: `wrist` (m), `palm` (quaternion x y z w, the hand joint frame of §16) and `fingers` (per finger the two curls in
degrees, §16 sign; a missing finger keeps the clip's). Authored with the rigs' hand helpers in
`underbarrel_models.grip_holds()`; `python -I tools/assets/underbarrel_models.py grips` prints the JSON. The holds: vertical
grip — a fist round the grip below the clamp, palm on its left, fingers round its front, the thumb over them (curls
fitted so no finger sinks into the 16 mm grip); angled grip — the hand under the handguard behind the ramp, palm up
against it, thumb forward along the left side, fingers round the right (a C-clamp); bipod — the hand cupping the forend
from below just behind the clamp, palm up (the legs folded ahead of it); `tommy_grip` — the Thompson clips' own hold
(tommy_rig's rest solve), so the default Thompson looks exactly as authored. Every frame, after the clips, locomotion
and the dead blend (§31.4): the clip's left hand in mount space keeps its deviation from the idle's first frame (a
clench on a shot, a drift) on top of the hold; the arm reaches it by two-bone IK in the plane of the clip's elbow (the
forearm takes the palm's twist, as `armrig.solve_side`), the shoulder coming forward along the reach when the hold is
past 0.985 × the arm's length (at most 0.15 m: the body leans in); the fingers take the hold's curls plus the clip's
deviation from its idle curls where it opens them (a clench meets the part). The weight is the clip's own: 1 while the
clip's hand is within 25 mm of its idle hold relative to the mount (idle, walk, shots, aiming, inspect presentations),
0 beyond 70 mm (smoothstep between), so reloads, draws, bolt and pump work and a sprint's pumping fist play as authored
and the hand slides onto the hold as a clip brings it back. Local rotations (and the shoulder's translation) are
slerped from the clip's by the weight, so nothing pops. First person only.

**A rail for one** (`excludes`, a slot field): a slot may name slots it takes over while it holds anything but its
default — they fall back to theirs (`CraftCatalog.complete`, so the server's stats and every renderer agree), and in
the 工匠 the newest choice wins (choosing either clears the other). (The pistols' underbarrel slot used it to exclude
`laser`; with that slot gone no weapon uses it now.)
Checks (`check_attach.py`): the mount where a weapon offers the slot, the builds `rect_full`, `angled_full`,
`bipod_full` ≥ 0.06 from the eye in every clip, and for every attachment a weapon's slot offers: its `grip` equals
`grip_holds()`, and in the holding clips (idle, walk; shots reported) with the hold applied the hand reaches it within
2 mm and sinks no more than 2.5 mm into the attachment (generalised winding number of the hand's skin points against
its triangles); `tommy_grip`'s hold is the clips' own and is reported only.

**`stock_none`** (无枪托, slot `stock`, no model): removes a built-in stock — the stock slot's `hides` take the stock
node away whenever the slot holds anything but its default. recoil × 1.25, ADS × 0.88, hip spread × 1.08. A stock slot
without an `options` list offers every stock attachment, so it shows on the AK-47, Thompson and KS-3 (and the RPD); the
pistols (p9, p9a, usp) list their stock options explicitly so it never shows there (their "none" already is no stock).

---

# Round I: equipment

## 36. Equipment (`models/equipment/<id>.glb`: grenades, molotov, throwing knife, adrenaline)

Six items, gameplay in `data/blockland/equipment/equipment.json` (ids `frag_grenade` 破片手雷 (M67 pattern),
`flashbang` 闪光弹 (M84), `smoke_grenade` 烟雾弹 (M18), `molotov` 燃烧瓶, `throwing_knife` 飞刀, `adrenaline`
肾上腺素注射剂 (auto-injector)). One file per item, like the knives: arm rig, the item, its clips. §2–§4, §6, §7, §15–§17
and §31 apply.

```
viewmodel
├─ equipment              the item's root = the middle of the right hand's grip (design frame of equip_model.py);
│  │                      animated. The thrown entity draws this subtree (its rest look; see below)
│  ├─ body | blade, grip | barrel, plunger, cap | glass, liquid, rag, flame      meshes (per item, below)
│  ├─ lever, pin          grenades: the spoon (origin at its hinge on the striker housing) and the safety pin
│  │                      (origin at its head where it leaves the fuze; the ring stands in front, hole along X)
│  ├─ tip                 knife / injector: empty at the point / needle end (effects)
│  └─ mount_charm         §19 charm mount (−Y straight down in the rest pose)
├─ lighter ─ lighter_lid, lighter_flame     molotov only: a petrol lighter carried by the left hand (scale 0 at rest)
├─ camera, arm joints (§16)
```

Design frames: grenades and the bottle stand along +Y (fuze / mouth up); the lever runs down the +X side (the palm
squeezes it), the ring sticks out towards −Z beside the fuze. Knife and injector lie along Z: point / needle towards
−Z, flats ±X; the injector's cap (thumb end) towards +Z. Rest pose = held low on the right (`equip_rig.ItemDef`).
The right hand's fingers are wrapped round each item by a fit against its signed distance (`equip_rig.fit_wrap`).

**Nodes the runtime needs.** Draw the `equipment` subtree in the hand. The **thrown entity** draws the same subtree
with the item's build (skin, charm) but **without `pin` and `lever`** (they stay behind: the pin in the thrower's
hand, the spoon flown off; the smoke grenade keeps its spoon until it leaves the hand, then drops it too); for the
molotov draw `flame` at scale 1 (lit; it stands up in world space: turn it against the bottle) and never the
`lighter` nodes (not under `equipment`). Nodes with scale 0 at rest: `flame`, `lighter`, `lighter_flame`. After the
release the viewmodel copy flies on for 0.07 s (0.04 s for the knife, 0.10 s for the tossed injector) and its
`equipment` node then goes to scale 0 (STEP) for the rest of the clip: the entity takes over; the arms finish the
follow-through off screen. A clip after `throw` / `lob` / `use` (the next item's `draw`, or a weapon switch) starts
from there.

| Item | Meshes (material → region) | Triangles |
|---|---|---|
| frag_grenade | `body` (body → body, fuze → body), `lever` (lever → lever), `pin` (pin → pin) | 2432 |
| flashbang | as frag + `label` (printed band → label), `vent` (dark vent recesses → body, no set) | 2052 |
| smoke_grenade | as flashbang (coloured top band and stencil band → label, emission holes → vent) | 2248 |
| molotov | `glass` (BLEND, double sided, no set → glass), `liquid` (fuel, no set → liquid), `rag` (→ rag), `flame` (emissive → none), `lighter*` (→ none) | 1876 |
| throwing_knife | `blade` (blade, blade_edge → blade), `grip` (paracord → grip) | 1640 |
| adrenaline | `barrel` (barrel → barrel, label → label), `plunger` (orange needle shroud → plunger), `cap` (blue safety cap → cap) | 1572 |

New skin regions (§21): `lever`, `pin`, `label`, `glass`, `liquid`, `rag`, `plunger`, `cap` (plus the existing
`body`, `blade`, `grip`, `barrel`). Texture sets (`pbr_sets_equipment.py`): `equipment/olive`, `zinc`, `band_flash`,
`band_smoke`, `rag`, `paracord`, `pen_barrel`, `pen_label`, `pen_orange`, `pen_blue`; camo enamels
`skin/camo/woodland`, `desert`, `arctic`, `tiger`. Skins (written by `equip_data.py` into equipment.json; craft Kind
EQUIPMENT): grenades `default`, `woodland` 林地迷彩, `desert` 沙漠迷彩, `arctic` 雪地迷彩, `aurora` 极光 (legendary:
iridescent body, flowing band); molotov `default`, `camo_rag` 林地布条, `desert_rag` 沙漠布条, `hellfire` 地狱火
(legendary: a glowing `flow` rag, red glass); throwing knife `default`, `woodland`, `tiger` 虎纹迷彩, `galaxy` 星河
(legendary); injector `default`, `woodland`, `arctic`, `neon` 霓虹回路 (legendary, `circuit`). The legendary ones are
shader skins (特效皮肤): equipment has no shot effects, so it has no 特殊皮肤 of its own (pbr_check allows rarity
`special` only with a skin model).

**Clips** (lengths as built; ✱ = the event time / length equals the equipment.json field; `equip_data.write_timing`
keeps them in sync on every build and `check_equipment` checks them):

| Clip | Grenades | Molotov | Knife | Injector |
|---|---|---|---|---|
| `idle` (loop) | 2.0 | 2.0 | 2.0 | 2.0 |
| `draw` | 0.48 ✱drawTime | 0.52 ✱ | 0.36 ✱ (flipped end over end in the loose fist, caught at 0.22) | 0.45 ✱ |
| `inspect` | 3.0 (both sides, the top; the left index checks the ring at 2.05–2.78) | 3.0 | 2.8 (tossed end over end at 0.45, caught at 0.95, both flats) | 2.8 |
| `walk` / `sprint` (loops) | 1.0 / 0.66 | 1.0 / 0.66 | 1.0 / 0.66 | 1.0 / 0.66 |
| `pull` | 0.50 ✱pullTime: up to the chest, left index hooks the ring (0.20), `pin` 0.24 (it comes free), frag / flashbang `spoon` 0.40, cocked into `hold` by 0.50 | 0.70 ✱pullTime: lighter up, `light` 0.14 (lid; wheel and wick at 0.30, the rag catches at 0.40), lid shut 0.50, into `hold` | — | — |
| `hold` (loop) | 1.2: cocked by the right shoulder, left arm pointing the throw; pin gone, frag / flashbang spoon gone | 1.2, flame burning | — | — |
| `throw` | 0.62: wind-back 0.08, whip, `release` ✱0.20 (releaseAt), follow-through, arm away; smoke `spoon` at 0.20 | 0.62, `release` ✱0.20 | 0.48: cocked by the ear 0.09, `release` ✱0.16, follow-through | — |
| `lob` | 0.62: arm drops past the hip, underhand swing, `release` ✱0.20 (the same releaseAt) | 0.62, `release` ✱0.20 | — | — |
| `use` | — | — | — | 1.45 ✱useTime: `cap` 0.28 (left hand pulls it off), raised point-down, `stab` 0.62 (thigh, bottom right of the screen, the camera ducks 2.2°), `inject` ✱0.82 (injectAt: the shroud springs out, the thumb presses and holds, tremble), pulled out, `discard` 1.20 (tossed right) |

`throw.recover` in the data = clip length − release (0.42 grenades / molotov, 0.32 knife). Events: `equip`, `pin`,
`spoon`, `light`, `release`, `cap`, `stab`, `inject`, `discard`, `flip` / `catch` (knife inspect). Suggested sounds
(the runtime maps events; `blockland:` ids): `equip` → `equipment.equip`, `pin` → `equipment.pin`, `spoon` →
`equipment.spoon`, `light` → `equipment.molotov_light` (one sound: lid, wheel, wick, the rag catching), `release` →
`equipment.throw` / `equipment.lob` (by clip) / `equipment.knife_throw`, `cap` → `equipment.adrenaline_cap`,
`inject` → `equipment.adrenaline_inject`, `flip` / `catch` → `knife.flip` / `knife.catch`. World sounds:
`equipment.bounce` (3), `frag_explode` (2), `frag_explode_far`, `flash_bang`, `flash_ring` (tinnitus, 3.4 s, for the
blinded player), `smoke_pop`, `smoke_hiss` (loops seamlessly, 4.0 s), `molotov_shatter`, `fire_loop` (loops, 4.0 s),
`knife_hit_flesh` (2), `knife_hit_block` (2), `heartbeat` (one lub-dub, 0.75 s: repeat it while the drug runs).
Subtitles `subtitles.blockland.equipment.*`. Item assets: `textures/item/<id>.png` icons, `models/item/<id>.json`,
`<id>_held.json` (cuboids + palette), `items/<id>.json` (icon in gui, held model in first person, the 3D item otherwise, §37).

Camera within the §4 limits in every clip (throw ±2.4° pitch / 3.4° roll at most, injector stab −2.4° pitch). Files:
`equip_model.py` (geometry, SDFs), `equip_rig.py` (grip fit, `EquipPose`: held frame, `fly`, `spin`, `parts`,
`carry` — parts handed to the left palm), `equip_clips.py`, `gen_equipment.py`, `gen_equipment_items.py`,
`gen_equipment_sounds.py`, `equip_data.py` (skins + timing → equipment.json), `build_equipment.py` (steps
`equipment`, `equipment_items`, `equipment_sounds`), `pbr_sets_equipment.py`, `pbr_models_equipment.py`; checks
`check_equipment.py` (claims files with an `equipment` node: nodes, materials / regions, clips, STEP scales, events,
the data timing, the item in the hand until its release and gone after it, the pin on the left index finger once
pulled, pin and cooked spoon gone in the hold, the lighter flame at the rag, crosshair, eye distance, equipment budget in `model_quality.json`).

## 37. Third person, dropped and framed weapons (the `blockland:weapon` item model)

Outside the GUI and first person every weapon item — guns, knives, equipment — is drawn as its full 3D model by the
special item model `blockland:weapon` (`client/thirdperson`): the stack's build (`blockland:gun` / `knife` /
`equipment` component) decides the model (a special skin's own, §30), the hidden nodes, the attachments at their
mounts (§19), the skin (§21) and the sprays (§29). The pose is the idle clip's first frame of the weapon root's
subtree (`gun` / `knife` / `equipment`, never the arms), taken in the root's **own space**: square to its axes, pivot at
the origin — the viewmodel's hip placement and cant do not carry over.

**Placement.** The weapon is fitted into the box of its held model (`fit`, default `blockland:item/<id>_held`; the
elements' `from` / `to`, rotations ignored, a skin variant's parent followed): the default build is scaled uniformly so
its extent along the box's longest axis matches the box, and centred in it. The held model is the special model's
`base`, so its `display` transforms (`thirdperson_*`, `ground`, `fixed`, `head`) place the weapon exactly where they
placed the cuboids, and its texture gives the break particles; the item's ground and GUI extents are the box. Both
spaces share their axes (§2 and the held models: muzzle / point / plunger towards −Z, up +Y, right +X), so no further
transform is needed unless a weapon's root frame is turned against its held model. Optional tuning on the `model`
object: `rotation` (degrees XYZ, applied to the weapon first: the karambit's root has the claw curling sideways,
`[0, 0, 90]` turns it down like its held model), `offset` (held-model pixels) and `scale` (a multiplier on the fit). A
special skin's model uses its weapon's fit (it keeps its own size against the original).

**Dual guns** (§26): the left hand (the off-hand copy `DualHold` puts there) draws the `gun_L` subtree with the
attachments at the mounts' `_L` twins, the right hand `gun`.

**Drawing.** The weapon is drawn right away with the weapon pipeline into the target the item is
drawn into (the world's frame, or a GUI picture such as the inventory's player), with the item's light, lit by the
world like the viewmodel (rendering spec §8) and storing emission in alpha. While BlockLand's sun shadow map is being captured (`ShadowPass`) its triangles go through vanilla entity render
types into the given buffers instead, so it casts a shadow. Detail: attachments within 48 blocks, sprays within 16. Assemblies are cached per
weapon, build and root (48), fits per weapon, root and tuning; both are dropped on a resource reload.

**Item definition** (§8; written by `tools/assets/third_person.py`, which also rewrites the existing files):
`"fallback": {"type": "minecraft:special", "base": "<held model>", "model": {"type": "blockland:weapon", "fit":
"<held model>"}}`, the icon for `gui`, the held model for `firstperson_righthand` / `firstperson_lefthand`.

---

## 38. Special skin inspects, dynamic skins and kill feedback

A special skin (§30) can bring an **inspect of its own** — its clip, events, sounds, particles, lights, camera feedback
and a change of look over the inspect — and an answer to a **kill**, all as data. Nothing about a skin is written into
the code: `client/viewmodel/SkinEffects` plays any skin's data, every weapon viewmodel can use it (the guns do).

**Data** (a skin in guns.json / knives.json / skins.json, read into `craft/CraftData.SkinInspect`, `SkinCue`,
`SkinEmitter`, `SkinLight`):

```json
"inspect": {
  "clip": "inspect_cerberus",
  "surge": [[0, 0], [1.28, 0], [1.34, 0.22], [1.9, 0.7], [3.6, 1.0], [4.9, 0.25], [5.45, 0]],
  "cues": {
    "cerberus.touch": {"particles": ["cerberus.touch_sparks"], "node": "fx_touch", "twin": false,
                       "light": {"color": "#FF5A24", "intensity": 3, "radius": 5, "seconds": 0.6}, "fov": -0.8}
  },
  "emitters": [{"particles": "cerberus.embers", "node": "slide", "from": 1.4, "to": 4.9, "fade": 0.4}],
  "glow": {"node": "gun", "color": "#FF3A14", "intensity": 1.6, "radius": 4.5}
},
"fx": {"hitColor": "#FF2A12", "kill": {"particles": ["cerberus.kill_flare"], "node": "muzzle", "sound": "gun.cerberus.kill",
                                       "surge": 0.85, "surgeFade": 1.6, "impulse": "cerberus.kill", "fov": 0.8}}
```

* `clip` — a clip of the drawn model (the skin's own model, or the weapon's) played instead of the weapon's inspect;
  missing or empty = the weapon's inspect clip (the rest of the block still plays over it). Times below are seconds of
  this clip as authored (inspects are not stretched).
* `surge` — the **dynamic material**: keys `[seconds, 0..1]`, linear between them, 0 outside them. The surge drives every
  shader effect of the skin's layers (§21): 0 is the skin at rest (pixel for pixel as without a surge), 1 its effects at
  full blaze — `flow`, `galaxy`, `circuit` glow brighter, `iridescent` goes to full strength and shines, `inferno` sets
  the weapon ablaze. Cut off (a shot, aiming), the inspect's surge cools down over 0.35 s instead of snapping back.
* `cues` — clip event name → what it sets off besides its sound (`gun.<event>`, sounds.json) and the rig's shove
  (`impulses.json` by the event's name, §31.4): `particles` (presets of `assets/blockland/viewmodel/particles.json`,
  burst at `node`), `node` (default `gun`; `muzzle` is a fitted suppressor's end), `twin` (default true: a dual weapon
  also at the node's `_L` twin), `light` (a flash: `color`, `intensity`, `radius`, `seconds`), `sound` (an extra sound
  event), `surge` / `surgeFade` (a pulse added to the surge, fading over that many seconds), `impulse` (an impulses.json
  entry to shove the rig with), `fov` (a field-of-view punch, percent; negative narrows).
* `emitters` — particle streams (looping presets) at `node` between `from` and `to`, fading in and out over `fade`.
* `glow` — a light at `node` whose intensity is `intensity × surge` (it lights the weapon, the hands and the world).
* `fx.kill` — a cue set off on the shooter's weapon when the server confirms a kill; `fx.hitColor` — the colour of the
  hit marker's kill marks while the skin is held (the player's own colours otherwise).
* `fx.killIcon` — the skin's own kill icon on the shooter's HUD at a confirmed kill (`client/hud/KillIcon`, above the
  kill medals): `texture` (base under `assets/blockland/`), `size` (GUI px, default 64), `seconds` (on screen, default
  2.2), `color` (glow and embers), `embers` (thrown off as it burns in and away; half as many per second rise off it
  while it glows). It burns in from its heart outwards in 0.45 s while it spins into place with an overshoot behind a
  flash, glows (a flickering, breathing halo) and sheds embers, then burns away the way it came in its last 0.5 s. The
  three textures come from an artwork with transparency in `tools/assets/source/kill_icons/<id>.(webp|png)`:
  `python -I tools/assets/build_all.py kill_icons` (`gen_kill_icons.py`, list `ICONS`) writes `<base>.png` (256 px),
  `<base>_glow.png` (128 px, white with the blurred bright parts as alpha, tinted by `color`) and `<base>_burn.png` (4 × 4
  frames of 256 px, the burn-in; played backwards to burn away), each with a `.mcmeta` for linear filtering. 地狱犬
  (`usp_dual` skin `cerberus`): `textures/gui/kill_icon/cerberus`, size 68, 2.3 s.

**Particles** (`assets/blockland/viewmodel/particles.json`, preset id → emitter; resource packs may override it, F3+T
reloads): `count` (≤ 128), `life` / `lifeJitter`, `spawn` (births spread over these seconds; a looping stream spreads
them over one life and keeps them coming), `speed` / `speedJitter`, `drag` (1/s), `gravity` (m/s² downwards in the
view; negative rises), `radius` (spawn sphere, or with `ring` a ring about the node's Z axis), `direction` (0 = every
direction, 1 = along the node's −Z), `orbit` (rad/s about Z), `gather` (0–1 pull into the node over the life),
`turbulence` (m), `size` ([half-size at birth, at death] m), `stretch` (streaks: seconds of screen speed), `color`
(particles start hotter, towards white, and cool to it), `intensity`, `loop`, `texture` (a sprite instead of the round
glow). They are GPU particles (rendering spec §8): nothing is simulated on the CPU, they ride with their node.

**Effect nodes** in a skin's model: extra nodes its clips animate (glTF TRS, `LINEAR` scale allowed for them) with
additive (`alphaMode` `ADD`, emissive, region `none`) quads — sigils, shockwave rings. They are collapsed (scale 0) at
rest, so the ordinary clips, third person and the 工匠 never show them; empty helper nodes give the cues a place.

**The Cerberus inspect** (`inspect_cerberus` in `usp_dual_cerberus.glb`, 5.6 s; `cerberus_clips.py`): a dip, the right
pistol turned to show its inner flank and crest; `cerberus.release` (0.62 s) — the left hand opens and the left pistol
floats up out of it, rolling slowly about its bore, embers orbiting it; `cerberus.touch` (1.30 s) — the free left hand
reaches across and its index fingertip presses the crest (within 12 mm of `fx_touch`, checked): a sigil flares open
round the fingertip (`fx_sigil`, overshoot, spinning, a heartbeat), sparks, a flash, the gun jolts, and the surge
spreads from there over both guns (the inferno skin: veins open white-hot behind a front, the steel chars, glyphs
ignite, the silhouette glows, embers rise); the right pistol turns muzzle-up and the fire racks its slide by itself
(`cerberus.rack` at the slam, 2.12 s, embers from the port); the sigil bursts and is gone by 2.45 s; `cerberus.catch`
(2.55 s) — the floating pistol sinks into the waiting hand; the pistols are brought together, muzzles crossing, trembling
as energy gathers into the muzzles; `cerberus.roar` (3.55 s) — both thrust forward and kick apart: flame and sparks from
both muzzles, shockwave rings (`fx_ring`, `fx_ring_L`), a big flash, a strong camera kick, shake and FOV punch; back to
the hip with a settle as the fire dies down. Each event has its own sound (`gun.cerberus.*`, `gen_usp_sounds.py`) and
shove (impulses.json). Kill: both muzzles flare and spit sparks, the guns surge, a howl, crimson kill marks.

Checks: `check_dual.py` (the skin clip's length, events and camera; the effect nodes collapsed at rest, additive, only
shown by the skin's clips; the fingertip on `fx_touch` at the touch; both hands back on their guns after the catch),
`pbr_check.py` (the skin's inspect and kill data: clip and events in the model, presets, nodes, sounds, impulses, surge
keys), `tools/shaders/check_shaders.py` (`gun`, `gun_particle`).

---

## 39. Bomb (C4, `models/bomb/c4.glb`)

The 枪战爆破 bomb (炸药包, item `blockland:c4`; data `data/blockland/bomb/bomb.json`, which carries the clip names,
`drawTime`, handling, the blast and the beep; the plant / defuse / fuse times are the mode's). One first-person file
like the equipment's: arm rig, the bomb, its clips, built with the equipment tools (`equip_rig` grip fit, held
frame, `fly`, free right hand). §2–§4, §6, §7, §15–§17, §20, §21 and §31 apply.

```
viewmodel
├─ bomb              the bomb's root (the middle of the bundle; animated). Design frame (bomb_model.py): the sticks
│  │                 along Z (the far end, with the LCD, towards −Z), the keypad facing +Y, the width along X
│  ├─ body           three sticks of plastic explosive in film (explosive → body), two bands of duct tape (tape → tape)
│  ├─ keypad         the timer unit: case and battery pack (housing → keypad), a 3 × 4 keypad (keys → keypad),
│  │                 the LCD (screen → none, emissive green)
│  ├─ wires          red, yellow and blue leads from the unit into the charge (wire_* → wires, plain colours, no set)
│  └─ led            the red status LED (led → none, emissive), origin at its centre, scale 1 (the runtime may blink it)
├─ camera, arm joints (§16)
```

Regions `body`, `tape`, `keypad`, `wires` (no skins yet). Texture sets (`pbr_sets_bomb.py`): `bomb/explosive`,
`bomb/tape`, `bomb/housing`, `bomb/keys`. 1440 triangles (budget 4000). At rest the right palm carries the bundle from
below (the fingers along the sticks, the thumb up its right side), low on the right with the keypad tilted to the eye;
the left arm is hidden (scale 0) outside `plant`.

| Clip | Length | Content / events |
|---|---|---|
| `idle` (loop) | 2.0 | breathing only |
| `draw` | 0.60 ✱`drawTime` | up from the bottom right; `draw` at 0.0 |
| `inspect` | 3.0 | raised and turned: the keypad, the wired side, the other side |
| `walk` / `sprint` (loops) | 1.0 / 0.66 | the shared equipment carry |
| `plant` | 3.2 (stretched by the runtime to the mode's plant time) | lifted, lowered and pushed out in front as the view dips (the player kneels; camera −2.1° pitch), set down: `place` 0.62 (it stops dead and stays there — `bomb` keeps its frame to the end); the left hand steadies it by its left side; the right hand lets go and the index finger keys the code in: `key` at 0.98, 1.24, 1.50, 1.76, 2.02, 2.30, 2.58 (the fingertip on a key top at each); both hands hover over it for the last ~0.3 s, the camera back at rest at 3.2 |

The bomb stays drawn on the floor to the end of `plant`: the runtime drops the viewmodel once the server confirms the
plant (a broken-off plant blends back to `idle`, the bomb back in the hand). Suggested sounds (`blockland:` ids):
`draw` → `bomb.draw`, `key` → `bomb.key`, `place` → none (the server's `bomb.planted` follows the plant). World
sounds: `bomb.planted` (armed chirp), `bomb.beep` (one tick), `bomb.beep_final` (1.25 s: the beeps rushing into a
solid tone), `bomb.defuse_start`, `bomb.defuse` (1.0 s, loops seamlessly), `bomb.defused`, `bomb.explode` (2
variants, 5.5 s with the tail), `bomb.explode_far` (distant rumble), `bomb.pickup`, `bomb.drop`. Subtitles
`subtitles.blockland.bomb.*`.

**Item assets.** `textures/item/c4.png` (32 px icon rendered from the GLB), `models/item/c4.json`,
`models/item/c4_held.json` (cuboids + the `c4_held.png` palette): the bundle lying flat in its own frame — keypad up
(+Y), the long side along X, 7 × 2.2 × 4.65 units, its **bottom face at y = 8** (the item's centre) — with an identity
`fixed` transform, so the planted bomb (drawn in the FIXED context at its position) rests on the floor; `items/c4.json`
shows the icon in the GUI and the held model everywhere else (no `blockland:weapon` special model: the bomb is not a
Craftable).

Files: `bomb_model.py`, `bomb_rig.py`, `bomb_clips.py`, `gen_bomb.py`, `gen_bomb_items.py`, `gen_bomb_sounds.py`,
`build_bomb.py` (steps `bomb`, `bomb_items`, `bomb_sounds`), `pbr_sets_bomb.py`, `pbr_models_bomb.py`; checks
`check_bomb.py` (nodes and parents, materials / regions / sets, clips, lengths, loops, camera limits, events, the
data's `drawTime`, the bomb in the hand until `place` and still after it, the fingertip on the keys at every press,
the left hand by the bomb, crosshair, eye distance, budget).


### 表面精度与预算（离线资产）

- `surface_quality.json` 按纹理组校准可见细节增益；它只供 `pbr_textures.py` 烘焙使用，不增加运行时分支、贴图采样或材质批次。不得为追求“清晰”把抛光刃口、喷砂铝、粗橡胶统一放大到同一法线强度。
- 刀身拉丝、刀刃抛光、弹匣纵向插拔磨痕、瞄具硬质阳极氧化加工纹和手雷铸造漆面必须区分。金属裸露、底漆、覆盖漆保持原 LabPBR 通道约定；磨痕主要通过粗糙度表现，不把底色整体刷白。
- 保留共享纹理的尺寸、真实重复尺度和现有 mip/Toksvig 路径。不要默认升级到 4K；RGBA8 全 mip 分配应与基线一致（该值是所有资源同时驻留的上限估算，不是实测显存）。
- 倍镜旋钮使用轴向凹槽、倒角和刻度，替代多层完整环带；不移动 `aim`、`lens_ocular`、`lens_objective`，不修改内壁黑色材质、透镜或准星。刻度在已有 `steel_dark` 批次内。
- P9 弹匣观察孔为穿过 1 mm 背板的倒角孔，背后黄铜退后 2.75 mm；不得用贴在实体表面上的黄铜圆片替代。保持弹匣轮廓、弹药位置、标准/扩容动画绑定。
- 非视觉回归：`python -I tools/assets/check_surface_quality.py --baseline ORIGINAL_PROJECT_COPY`，以及 `check_model_quality.py`、`pbr_check.py`、`check_glb.py`。表面检查约束尺寸、批次、总三角形不增长、绑定一致，并对观察孔做射线深度测试。COD 接近程度、镜面稳定性和帧时间仍需用户实机验收。

## 40. Nine more guns (UZI, P90, MP5, STEN Mk II, SSG 08, Calico M950, SCAR-H, M4A1, Jackhammer)

Original procedural models at real scale, each built like the RPD (§33): its own `<gun>_model.py`, `<gun>_rig.py`,
`<gun>_clips.py`, `gen_<gun>.py`, texture sets (`pbr_sets_<gun>.py`), material table (`pbr_models_<gun>.py`), fire
sounds (`gen_<gun>_sounds.py`), checker (`check_<gun>.py`, claims `<gun>.glb`) and build step module (`build_<gun>.py`:
steps `<gun>`, `<gun>_sounds`); items through `<gun>_items` / `gun_items` (`gen_gun_items.py`: icons rendered from the
GLB, lifted when a gun is dark overall and rimmed along its top so a black gun reads at 32 px; the held model from the
bounding boxes of its parts). The UZI, P90, Calico and Jackhammer are matched to reference photographs
(`tools/assets/source/references/`). The first, crude versions of these nine (a shared parametric generator) are kept in
`tools/assets/source/section40_artist_v1/` for reference only. Each gun's gold skin tints all of its metal materials
(`steel*`, `barrel*`).

| id | Category · ammo | Magazine nodes (`mag` pivot) | Slots (mount nodes needed) | Slot hides | Notes |
|---|---|---|---|---|---|
| `uzi` | smg · smg | `mag_standard` 32, `mag_extended` 50 (in the grip) | optic, barrel, laser, stock, underbarrel, charm | optic: `sight_rear`, `sight_iron` · barrel: `muzzle_brake` · stock: `stock` | open bolt: `bolt` node (slide lock) |
| `p90` | smg · smg | `mag_standard` 50 (top, along the receiver) | optic, barrel, laser, underbarrel, charm (no stock: bullpup) | optic: `sight_rear`, `sight_iron` · barrel: `muzzle_brake` | `bolt` node |
| `mp5` | smg · smg | `mag_standard` 30, `mag_extended` 40 | optic, barrel, laser, stock, underbarrel, charm | as uzi | `bolt` (cocking handle) node |
| `sten` | smg · smg | `mag_standard` 32 (side well, left) | optic, barrel, laser, stock, charm (no underbarrel) | as uzi | `bolt` node |
| `ssg08` | sniper · sniper | `mag_standard` 10 | optic (default `scope_sniper`), barrel, underbarrel (bipod), charm — no laser | barrel: `muzzle_brake` | bolt action (`boltAction`, like §23): `bolt` node, fire clip with the clip event `bolt_back` (ejects the case; sounds `gun.bolt_back` / `gun.bolt_forward` on the events of those names) |
| `calico` | pistol · pistol | `mag_standard` 50, `mag_extended` 100 (helical, on top) | optic, barrel, laser, stock (pistol stocks: tactical / heavy / wire, like the P9 — `mount_stock` turned and scaled as the P9's), underbarrel, charm | optic: `sight_rear`, `sight_iron` · barrel: `muzzle_brake` | `bolt` node |
| `scar` | rifle · rifle | `mag_standard` 20, `mag_extended` 30 | optic, barrel, laser, stock, underbarrel, charm | as uzi | `bolt` (charging handle) node |
| `m4a1` | rifle · rifle | `mag_standard` 30, `mag_extended` 45 | optic, barrel, laser, stock, underbarrel, charm | as uzi | `bolt` node |
| `jackhammer` | shotgun · shotgun (8 pellets) | `mag_standard` 10 (the rotary drum, swapped whole) | optic, barrel, laser, underbarrel, charm (no stock: bullpup) | optic: `sight_rear`, `sight_iron` · barrel: `muzzle_brake` | full auto, magazine reloads (not shell by shell); `bolt` node (the forend's cocking stroke may drive it) |

* Nodes: `viewmodel` → `gun` with the meshes, `mag` → the magazine meshes named as above, `muzzle`, `ejection`,
  `aim_iron` (§5; not needed on the SSG 08, whose default optic is a scope), the `slideLock` node (`bolt`), the mounts
  `mount_optic`, `mount_barrel`, `mount_laser` (not the SSG 08), `mount_stock` (where the table lists the stock),
  `mount_underbarrel` (ahead of the support hand, §35), `mount_charm`; arm joints; root `fx_shell` (the case:
  9 mm for uzi / mp5 / sten / calico, 5.7 mm for the P90, 5.56 / 7.62 for M4A1 / SCAR, .308 for the SSG 08, a 12-gauge
  hull for the Jackhammer). Parts a slot hides need those exact names (`sight_rear`, `sight_iron`, `muzzle_brake`,
  `stock`); a gun without such a part simply has nothing to hide.
* Clips (§4): `idle`, `draw`, `fire`, `fire_last`, `reload`, `reload_empty`, `inspect`, `walk`, `sprint` (optional),
  `tac_sprint` (optional); events `mag_release`, `mag_out`, `mag_in`, `slide_back` / `slide_forward` (or `bolt_back` /
  `bolt_forward`), `inspect_move`, `equip`; the reload's length should match the data (`reload.tactical` /
  `reload.empty` in guns.json) or the data be set to the clip's.
* Materials and regions (§20, §21): every material carries `extras.region` (`body`, `barrel`, `magazine`, `stock`,
  `grip`, `handguard`, `detail`) and preferably `extras.pbr` (a LabPBR set `<set>.png`, `_n`, `_s` under
  `textures/pbr/<gun>/`). The camo skins re-texture the regions `body`, `stock`, `handguard`, `grip` (as the gun has
  them); the 鎏金 skin tints the materials named `steel`, `steel_dark` and `barrel` — name the gun's metal parts so.
* Sounds: `sounds/gun/<id>_fire_1.ogg` … `_3.ogg` and `<id>_fire_far.ogg` (events `gun.<id>.fire`, `gun.<id>.fire_far`),
  synthesised per gun by `gen_<id>_sounds.py`.
* Build: `python -I tools/assets/build_all.py <id> <id>_sounds <id>_items`; check: `python -I tools/assets/check_glb.py`.


### Section 40 source assets / first integrated pass

The nine GLBs are generated from `arsenal_data.json` (art parameters, not gameplay statistics),
`arsenal_models.py` (distinct silhouettes/feed systems), `arsenal_rig.py` (IK and feed-axis-aware clips),
and `gen_arsenal.py`. Timings and slots are read from guns.json; this pipeline does not rewrite that file.
Models are original procedural game-art assets, not scanned/artist-approved COD assets; in-game visual approval
and further silhouette/material polish remain with the user. Existing copied gunshot sounds are intentionally retained.

Rebuild: generate the nine prefixes with `pbr_textures.py uzi/ p90/ mp5/ sten/ ssg08/ calico/ scar/ m4a1/ jackhammer/`,
then `build_all.py arsenal gun_items`. The source-dependent PBR registration is in `pbr_sets_arsenal.py` and
`pbr_models_arsenal.py`; each weapon has three 512px sets and one 256px set, plus shared cartridge brass.
Each GLB includes both magazine variants only where configured; its whole-file budget is 12,000 triangles and
44 material primitives (including spare magazine and shell, not an actual frame draw-call measurement).

Checks: `check_arsenal_rig.py` samples wrist reach at 60 Hz; `check_glb.py` dispatches to `check_arsenal.py`,
checking the exact nodes, slot hides, region/tint contracts, PBR paths, reload duration, SSG bolt events,
magazine withdrawal, loop seams, winding, eye distance, optic obstruction and underbarrel/hand clearance.
Do not relax these limits to accommodate a bad mount: move the mount or correct the actual mesh/rig.

**As built.**

* **UZI** (15.9k triangles; matched to a left-side photo): stamped receiver with the pressed ribs, top cover with the
  knurled, notched cocking knob (`bolt`, 90 mm stroke; still in the shots — the Uzi's knob does not reciprocate —
  hauled back in `reload_empty`), front post on its drum between ears, flip aperture (`aim_iron` 0.085 behind), knurled
  barrel nut and thread protector (`muzzle_brake`), ribbed handguard, grip with the grip safety and heel catch, magazine
  through the grip (`mag_extended` 72 mm longer), folding stock (`stock`), a riser rail on the cover for optics.
  Reload 2.10 s (heel catch, straight down, driven home), empty 2.60 s (+ knob pull). Sets `uzi/parkerized`, `barrel`,
  `knurl`, `polymer`, `mag`.
* **P90** (17.4k; matched to a photo): organic body traced off the photo, TR housing over the magazine with its rails
  and the integral irons on its top edges (static), folding backup irons on the top rail on the bore's centreline
  (`sight_rear`: ghost ring at the rail's rear end, `sight_iron`: post between ears at its front; sight line 14 mm over
  the rail, `aim_iron` 0.175 behind the ring, the housing below the sight picture), ambidextrous cocking handles
  (`bolt`, 68 mm, still when firing), selector disc, downward ejection out of the butt (`ejection` +X down), the 50-round
  magazine: a translucent `mag_window` shell (BLEND, region `magazine`) over two staggered rows of rounds, three turning
  on the feed ramp. Reload 2.7 s (rear lifted and drawn back from under the housing, new one front-first into the latch,
  rear pressed down), empty 3.2 s (+ left handle). No stock mount. Sets `p90/polymer`, `grip`, `mag`, `barrel`.
* **MP5A3** (16.6k): stamped receiver with the A3 rail channels, a real ejection port down to the carrier (`carrier`
  cycles 64 mm when firing), cocking tube with slot and notch (`bolt` → `charging_handle` → `handle_lever`), hooded front
  sight (`sight_iron` → `front_sight_hood`, hidden with the post), rotary drum with a real aperture (`sight_rear`), claw
  mount under the iron sight line, slim handguard, polymer trigger group with the paddle, curved 30 / 40-round
  magazines, A3 stock. Reload 2.2 s; empty 2.7 s is the HK slap (handle locked in the notch, magazine changed, slapped
  down). Sets `mp5/receiver`, `barrel`, `polymer`, `handguard`, `mag`.
* **STEN Mk II** (17.6k): tube receiver with its openings cut through the wall (magazine opening left, ejection port
  right, L-shaped cocking slot), drilled barrel sleeve, rotating housing collar and the left side magazine housing
  (32 rounds horizontal, leaving along −X), peep rear sight with ears, front blade, T tube stock, a short top rail, the
  wooden pistol grip of the late pattern (Mk V; material `wood`, region `grip`, set `sten/wood`) on a steel bracket
  behind the guard — the shooting hand holds it, index on the trigger. No underbarrel slot. Open
  bolt: at rest `bolt` is held back on the sear; `fire` runs it forward and back onto the sear, `fire_last` leaves it
  forward (slide-lock offset (0, 0, −0.09)). Support hand on the housing, never the magazine. Reload 2.4 s, empty 2.9 s
  (the right hand racks the right-side handle). Sets `sten/body`, `blued`, `mag`.
* **SSG 08** (17.2k): flat-sided upper with a through port, milled chassis lower, full-length top rail, octagonal
  fore-end with through vents and short side / bottom rails, fluted 508 mm barrel, three-port brake, skeletal folding
  stock with cheek piece, near-vertical grip, `bolt` (shroud) → `bolt_handle` (lifts 60°, 88 mm stroke). `fire` 1.02 s
  cycles the bolt (`bolt_back` 0.43 ejects the case, `bolt_forward` 0.56). No irons, no `aim_iron`, no laser or stock
  mount. Reload 2.7 s, empty 3.3 s. Sets `ssg08/chassis`, `foreend`, `stock`, `barrel`, `grip`, `cheek`, `mag`.
* **Calico M950** (9.7k, pistol budget; matched to a photo): aluminium receiver cradling the helical magazine, front
  sight tower, a rear sight bridge with a 7-slot rail over the rear of the receiver (our addition: the real rear sight
  sits on the magazine), ribbed forend with vent holes and top / bottom rails, raked grip, left cocking handle (`bolt`,
  32 mm, cycles in `fire`), the polymer lower's guard web (26 mm across, narrower than the grip's front strap) running
  back into the grip. `mag_standard` 50 (0.185 m) / `mag_extended` 100 (0.308 m) along the top, hooked at the
  tower. Reload 2.6 s (nose lever, nose lifted about the bridge, drawn forwards out, the new one fed tail-first),
  empty 3.1 s (+ handle). Stock mount at the grip bottom turned −20°, scale 0.70. Sets `calico/receiver`, `polymer`,
  `mag`.
* **SCAR-H** (17.8k): extruded upper with the continuous top rail, side and bottom rails, a real port with the
  deflector, polymer lower with the flared well, side-folding telescoping stock, ghost-ring rear and flip-up front sight
  on the gas block, `bolt` (carrier + left charging handle, 108 mm, held open at 0.099 = slide-lock offset), `bolt_catch`.
  20-round straight / 30-round curved magazines. Reload 2.5 s, empty 3.0 s (thumb on the bolt catch). FDE / black
  two-tone: sets `scar/anodized`, `polymer`, `grip`, `barrel`, `mag`.
* **M4A1** (17.0k): flat-top upper with a real port (carrier, lugs, forward-assist notches), forward assist, deflector,
  open dust cover; lower with the flared well, bolt catch, magazine catch, selector; A2 grip; oval handguard with heat
  shields and two clamp-on rail sections (laser right, underbarrel below); A2 front sight base; birdcage; six-position
  stock on its buffer tube; `bolt` (carrier, 82 mm, held open at 0.076 = slide-lock offset), `charging_handle`,
  `mag_catch`, `bolt_catch`. 30 / 45-round magazines. Reload 2.2 s, empty 2.7 s (palm heel on the bolt catch). Sets
  `m4a1/receiver`, `polymer`, `grip`, `mag`, `phosphate`, `small_parts`.
* **Jackhammer** (16.6k; matched to a photo): vented receiver (2 × 9 holes through to the barrel), carrying handle with
  the oval and three round cut-outs and a rail section, the forend operating sleeve (`bolt`, 32 mm cocking stroke; it
  carries the laser and underbarrel mounts), open-frame grip, duckbill flash hider, square butt, the 10-round
  Ammo-Cassette (`mag_standard`) with its zig-zag cam groove and loaded chamber mouths, `latch`. `fire` 0.22 s indexes
  the cassette 36° on a STEP channel (ten-fold symmetric, so the return at the clip's end is invisible); the gun keeps
  its hulls (`ejectEvent` "none": no cases thrown). Reload 3.2 s (latch, cassette down and out, a full one seated),
  empty 3.8 s (+ forend rack). Sets `jackhammer/receiver`, `polymer`, `grip`, `barrel`, `cassette`.

---

## 41. Crowbar (`models/knife/crowbar.glb`, weapon id `crowbar`, 撬棍)

An original procedural steel wrecking bar on the knife contract (§11–§13), after a common 600 mm gooseneck bar: a hex
bar 19 mm across the flats (two flats facing the claw side), swept along its centre line (`crowbar_model.py`) — the
goose-neck bends it round a 40 mm centre-line radius through 172° so the claw points back above the shaft; the claw is
flattened to a 25 mm wide, 6.4 mm slab, bevelled on its inner face to a 0.9 mm edge and split by a 30 mm V nail slot;
the other end is bent 12° towards the claw side and flattened into a 23 mm chisel ground to a 1.4 mm edge. 0.60 m from
the chisel to the goose-neck's nose, ≈ 1.5 kg of steel. Red enamel worn and chipped through to primer and steel (a used
bar), the claw and chisel ground bare, a friction-tape wrap (with the raised helical seam of its overlapping turns)
from just above the chisel bend, where the right hand holds it.

```
viewmodel
├─ knife                  root at the right-hand grip (the middle of the tape wrap)
│  ├─ bar (mesh)          shaft, goose-neck, claw, chisel: materials bar, ends
│  ├─ grip (mesh)         tape wrap: material grip
│  ├─ tip                 empty at the goose-neck's nose (the bar's furthest point, the striking point)
│  └─ mount_charm         a lanyard below the tape's lower end (§19)
├─ camera, arm joints (§16)
```

Rig (`crowbar_rig.py`): a hammer grip round the tape, the thumb wrapped towards the head, the shaft a little diagonal
across the palm (30°); the knife follows the IK hand and the wrist limits turn the bar with it (as §27), the bar can
`roll` in the fingers about its own axis, and the left hand can take the shaft 0.10 m above the right (`support`,
mirrored grip) for the two-handed smash. A hammer grip only lets the bar point 52°–110° from the forearm, so the
swings key the elbow too: every key carries an elbow pole (`crowbar_clips.feasible`), raised for the chops, drawn across
for the backhand. Ready stance: held like a bat low on the right, the fist and tape in the bottom right corner, the bar
leaning up and forward, the neck curling right with its outer curve (the striking face) towards the left.

Clips (knives.json `crowbar`; the weight leads every motion — long loading wind-ups, the head lagging the hand and
overtaking it into the contact, a follow-through the weight carries on, slow recoveries settling with an overshoot):
`idle` (2.0 s, breathing with the weight), `draw` (0.80 s: pulled up from below, the head lagging, swung up past the
guard and checked by the wrist at 0.38 s), `light_1` (0.62 s forehand chop: cocked back over the right shoulder, driven
down and across, the head over the screen centre at 0.24 s, follow-through low to the left), `light_2` (0.62 s
backhand: coiled to the left shoulder, driven out to the right with the claw's back leading, contact 0.24 s), `heavy`
(1.15 s: the left hand joins, both lift the bar high over the head, a beat at the top, the smash lands on the screen
centre at 0.50 s and stops dead, a shudder and rebound, lowered, the left hand lets go), `inspect` (3.8 s: the goose-
neck held up in profile, turned to show the claw's slot, laid across into the open left palm for two weighty taps at
1.42 / 1.82 s, a full roll in the loosened fingers, back to the guard), `walk`, `sprint` (the bar low, swinging with the
arm), `tac_sprint` (raised upright beside the face). Events `crowbar_equip`, `crowbar_swing`, `crowbar_swing_heavy`,
`crowbar_tap` (sounds `blockland:knife.crowbar_*`, the event-name rule of §12, `gen_crowbar_sounds.py`: the ring of a
held free steel bar — modes 1 : 2.76 : 5.40 : 8.93 over ≈ 240 Hz). Materials `bar` (region `blade`, set
`crowbar/paint_red`), `ends` (region `hardware`, `crowbar/ends`), `grip` (region `handle`, `crowbar/tape`): the shared
knife skins of `craft/skins.json` re-texture them by region. Skins `default`, `black` (`crowbar/paint_black`), `rust`
(`crowbar/rust`), `gold` (legendary: polished steel tinted gold, the tape kept). Impacts: profiles `blunt` /
`blunt_heavy` (`impacts.json`: chips, dust and sparks thrown back off the struck face, no cut), decals `blunt_dent`
(blocks: a crushed hollow with cracks) and `bruise` (bodies). Item assets as §11; the held model is built at 25 units
per metre (the bar would not fit the model box at 50) with the balisong's display scales × 1.8.
Files: `crowbar_model.py`, `crowbar_rig.py`, `crowbar_clips.py`, `gen_crowbar.py`, `gen_crowbar_items.py`,
`gen_crowbar_sounds.py`, `build_crowbar.py` (steps `crowbar`, `crowbar_items`, `crowbar_sounds`), `pbr_models_crowbar.py`,
`pbr_sets_crowbar.py`; checks `check_crowbar.py` (claims `crowbar.glb`: nodes, the tip at the nose and the bar's
length, materials, clips and their events' sound events, contact times at the data's fractions — the head's line over
the screen centre for the swings, the head landing on it for the smash —, the right hand fixed on the tape and the left
on the shaft while it holds it, crosshair clear but for the head, eye distance, budget). 7.1k triangles.

## 42. Knuckles (`models/knife/knuckles.glb`, weapon id `knuckles`, 拳套)

A pair of original procedural brass knuckle-dusters on the knife contract (§11–§13), one on each hand: a flat cast plate
(9.2 mm) lying between the palm face and the curled middle finger segments, four oval finger rings (13 × 15.6 mm, at
the hand's 15.4 mm finger pitch, 2.4 mm webs) round the first segments of a closed fist, a scalloped striking edge in
front of them with four raised knuckle bosses (polished), an open window under the rings and a palm bar below it, held
between the palm and the finger tips and wound with paracord (13 turns), its ends reaching past the hand, a lanyard
hole in its little-finger end. 80 × 46 mm. Built in the right hand's frame (`knuckles_model.py`); the left duster is
its mirror image (the left hand's frame mirrors the right's).

```
viewmodel
├─ knife                  the right duster, at the middle of its ring row, rigid to the right hand
│  ├─ duster (mesh)       materials frame, face, wrap
│  ├─ tip                 empty at the middle of the striking face
│  └─ mount_charm         in the lanyard hole of the palm bar's little-finger end (§19)
├─ knife_L                the left duster (the `_L` twin of §26 / §37), rigid to the left hand
│  ├─ duster_L (mesh)     the mirrored duster, same materials
│  └─ tip_L
├─ camera, arm joints (§16); both arms always shown (no arm scale channels)
```

Rig (`knuckles_rig.py`): the clips author each FIST — the duster's centre and the hand's palm frame (`fist_q(fwd,
palm)`: -Y along the punch, +Z the palm) — the arms solve to the wrists behind them (two-bone IK, the wrist kept in its
anatomical range) and each duster node is the hand as solved times the fixed offset, so the fingers sit in the rings
in every frame whatever the IK did (checked: every finger's first segment through its ring's centre within 1.5 mm and
10°; the fist curl `DUSTER_FIST` runs the four first segments straight through the plate). Shoulder offsets carry the
body's turn into each punch. Guard: orthodox, the left fist a little ahead, both fists low on screen, palms in.

Clips (knives.json `knuckles`): `idle` (2.0 s, the guard breathing), `draw` (0.55 s: the fists come up from below and
knock knuckles at 0.34 s), `light_1` (0.30 s left jab: a small load, the fist turning palm-down on the way, the
striking face at full extension 3° off the screen centre at 0.10 s, the right fist tucked to the chin, the recoil and
a faster return), `light_2` (0.30 s right cross, more shoulder and body turn, contact at 0.10 s), `heavy` (0.62 s
right uppercut: the shoulder and fist dip, then drive up through the front of the centre, knuckles up and palm to the
face, stopping dead at 0.25 s), `inspect` (3.0 s: the back of the right fist turned to the eye, turned over to show the
palm side and the wrap, then the fists knock twice at 2.12 / 2.34 s), `walk`, `sprint` (the fists low, pumping in
opposition), `tac_sprint` (higher, harder). Events `knuckles_equip`, `knuckles_swing`, `knuckles_swing_heavy`,
`knuckles_clink` (sounds `blockland:knife.knuckles_*`, `gen_knuckles_sounds.py`). Materials `frame` (region `blade`,
set `knuckles/brass`), `face` (region `blade`, `knuckles/face`), `wrap` (region `handle`, `knuckles/wrap`); AO per mesh.
Skins `default`, `black`, `chrome`, `damascus`. Impacts: profiles `punch` / `punch_heavy`, decals `punch_mark` (blocks:
four small dents in a row) and `bruise` (bodies). Third person: the right duster only (the left one is drawn there
only as an off-hand item), its knuckle line along the held model's grip axis (`rotation` [0, −90, 0],
`third_person.TUNING`). Files: `knuckles_model.py`, `knuckles_rig.py`, `knuckles_clips.py`, `gen_knuckles.py`,
`gen_knuckles_items.py`, `gen_knuckles_sounds.py`, `build_knuckles.py` (steps `knuckles`, `knuckles_items`,
`knuckles_sounds`), `pbr_models_knuckles.py`, `pbr_sets_knuckles.py`; checks `check_knuckles.py` (claims
`knuckles.glb`: nodes, materials, clips, contact times at the data's fractions with the landing fist in front of the
centre, the fingers in the rings, crosshair, eye distance, budget). 8.8k triangles (4.4k per duster).

## 43. Hand cannon (`models/gun/handcannon.glb`, weapon id `handcannon`, 手炮)

An original procedural **single-shot 40 mm grenade pistol**: a cut-down break-open launcher with a pistol grip. It is
built to the **P9 contract** (as the Desert Eagle, §48): `handcannon_model.py` defines the names the P9 rig and clips
read, and `gen_handcannon.py` re-runs `p9_rig`, `p9_clips` and `p9_loco` against it (`variants.load`), so nodes,
materials, events, the idle / draw / walk / sprint / tac_sprint clips and the hand anchors are the P9's. Its own fire,
reload and inspect are `handcannon_clips.py`. Nodes, by the contract's names:

* `barrel`: a 248 mm aluminium tube (olive paint, material `barrel`) with a counterbored chamber, two grip rings, a
  sight rib and a **tall front blade at the muzzle** between two protective ears, its top on the sight line. It hangs
  on the hinge pin under its breech (`HINGE`): the node's origin is the hinge and its rotation about X is part of the
  pose (negative = muzzle down; the stop `OPEN` is −44°);
* `frame` (material `polymer`): the short steel receiver (the standing breech and firing pin), the forward knuckle the
  barrel hinges on, the side latch, the squared trigger guard and the raked rubber `grip`; `slide` is the fixed top
  rail (no travel: `SLIDE_LOCK` 0), `sight_iron` the rear notch with two dots; an external spur `hammer`, a curved
  `trigger`; `slide_stop` is the barrel latch the thumb presses;
* `mag` / `mag_standard`: the 40 × 46 mm high-explosive round in the chamber (aluminium case `mag`, olive projectile
  `mag_base`, gilded band and fuze `brass`); `mag_extended` repeats it for the contract and is never shown;
  `fx_shell` is the spent case.

The iron sight line (`SIGHT_Y`) is low enough that the smallest red dot's window stays clear of the front blade.

Clips: **fire** — the hammer falls and the wrists are driven back 20 mm, the muzzle flipping 12° slowly (the arms
absorb it), the support hand clamping; `fire_ads` / `fire_last_ads` hold the sight picture (`lateral` 0.004,
`max_turn` 0.004°). **reload_empty** (the runtime plays it after every shot; 2.45 s): the gun rolls left and comes in,
the support hand leaves the grip for the barrel, the thumb presses the latch (`mag_release`), the hand pulls the barrel
down onto its stop (it bounces) and the hammer cocks with it; the gun is flicked up and the spent case springs out of
the chamber (`mag_out`: guns.json `ejectEvent` `mag_out` makes the runtime throw `fx_shell` from the `ejection` node,
which sits at the open chamber's mouth; the round hides, scale 0); the hand fetches a grenade on an arc, lines it up
behind the chamber and pushes it in with the thumb (`mag_in`, the round shows again); it swings the barrel shut with a
snap (`slide_forward`: the gun jolts, the camera nods) and re-grips. **reload** (2.2 s) is the same, shorter (a live
round is swapped). **inspect** turns the gun over, breaks it open a little to look at the round and snaps it shut.
The hip pose is 4 mm lower, 10 mm further right and 16 mm further back than the P9's.

Data (guns.json): category pistol, ammo **`grenade`** (榴弹, item `grenade_ammo`), one magazine `handcannon_1` of
capacity 1, semi, 60 rpm, heavy recoil and camera kick, reload 2.2 / 2.45 s, `ejectEvent` `mag_out`. **Slots:
`optic` only** (iron sights by default, every optic). The `grenade` ammo is a **launched round**: its `projectile`
object (`speed` 52 blocks/s, `gravity` 9.8, `armDistance` 6 blocks, `life` 8 s) and `blast` (the frag grenade's
parameters: radius 4.5, innerRadius 1.4, damage 28 → minDamage 4, knockback 1.0). Any gun whose ammo declares a
`projectile` fires it through `GunService` (no per-gun code): the server spawns a `GunGrenade` entity
(`ThrowableItemProjectile`, the 3D round model, synced gravity) from the eye along the aim with the gun's spread; it
flies on an arc with a smoke trail; on impact past the arming distance (or when its life runs out) it detonates through
the equipment module's public API (`EquipmentEffects.detonate(..., "frag", blast, owner)`: falloff, line of sight,
knockback, the frag explosion's effects and sound, no block damage, the kill credited to the shooter with the hand
cannon as the weapon, `allowDamage` respected); nearer than the arming distance it only strikes (the ammo's `damage`,
6, to an entity it hits) and drops with a clunk (`gun.handcannon.dud`). The shot packet carries face `LAUNCHED` (−4):
spectators see the flash and the fire clip, no tracer or bullet impacts.

Skins `default` (olive), `black` (tints), `gold` (legendary, tints), plus the shared gun skins. Regions: `polymer`
(frame) and `slide` → `body`, `barrel` → `barrel`, `grip` → `grip`, `steel_dark` → `detail`, the round → `magazine`.
Sounds `gun.handcannon.fire` (a deep hollow "thoomp", no supersonic crack) and `gun.handcannon.dud`
(`gen_handcannon_sounds.py`). Items: icons per skin, the held model, `grenade_ammo` (a flat icon in the GUI, the 3D
round elsewhere: `grenade_ammo_round`). Files: `handcannon_model.py`, `handcannon_clips.py`, `gen_handcannon.py`,
`gen_handcannon_items.py`, `gen_handcannon_sounds.py`, `build_handcannon.py` (steps `handcannon`, `handcannon_items`,
`handcannon_sounds`), `pbr_models_handcannon.py`; Java `gun/GunGrenade`, `client/gun/GrenadeRounds` (renderer and the
client explosion). Checks `check_handcannon.py` (claims `handcannon.glb`: the P9 contract via `check_glb.check_gun`,
the pistol budget, the optic-only slot, one single-round magazine of a launched round with a blast, `ejectEvent`
`mag_out`, the barrel shut at rest and at both reloads' end and at its stop while open, the round hidden from `mag_out`
to `mag_in`, the front blade on the sight line). 6.5k triangles drawn.

## 44. Vector (`models/gun/vector.glb`, weapon id `vector`)

An original procedural model after the KRISS Vector Gen II (sections 18–21, 31, built like the MP5 of §40;
`vector_model.py`, design frame +X right, +Y up, +Z back, bore on the axis, z = 0 at the breech face). Its proportions are
traced off an orthographic reference sheet (0.64 mm per reference pixel; the bore and the breech face as origin): 617 mm
with the stock unfolded, 355 mm receiver, 140 mm barrel, 17.0k triangles (both magazines; budget 18k). Flat dark earth
two-tone: receiver, lower and grip FDE, rails, sights, barrel, stock and magazines black.

* **Upper** (`receiver`, material `upper`, region `body`, set `vector/upper`: FDE ceramic coating over the anodised
  aluminium): a long slab — the top rail stands 52 mm over the bore, which runs low, level with the top of the
  shooter's hand; a core with two 3 mm flanks over it so the ejection port (right), the charging slot (left, at the
  front) and the window onto the recoil mechanism (left: guide rods and springs) are real openings; its underside low in
  front (the nose, the mechanism) and stepping up behind the lower to the top of the trigger-guard opening; a
  full-length rail (33 slots), the accessory rail under the square nose (underbarrel mount), a side-rail stub at the
  right of the nose (laser), the barrel collar, screw heads, the bolt catch; behind the rear face the stock adapter with
  its ribbed hinge knob on the left, kept low on top (36 mm over the bore) so the eye looks over it at ADS.
* **Lower** (`lower`, region `grip`, set `vector/lower`: FDE glass-filled polymer): a deep slab whose front slants down
  and back from under the nose to the magazine well's mouth (finger ribs along it), a flat underside, the strap rising
  into the grip's base, the big trigger-guard opening between the slab and the grip with the slim guard bar across it;
  a core with recessed panels in its flanks (a long slot along the slanted front, a shorter one by the opening), the
  raised chevron, pins, the well's mouth collar raked with the magazine; `pistol_grip` raked 16°, deep at the top where
  it grows out of the receiver, stippled pads, finger ribs down its front.
* **Parts**: `bolt` = the non-reciprocating charging handle's slider in the left slot (the slide-lock node, +Z, 56 mm
  stroke) with its child `handle_lever`, the knob that folds out about +Y (80°) from its hinge at the slider's rear;
  `carrier` = the bolt seen through the port (46 mm when firing); `trigger` (hanging from just under the upper, level
  with the bore), `selector` (left, over the grip), `paddle` = the ambidextrous magazine release tabs on the lower's
  flanks behind the well (pressed forward about X); flip-up irons on the rail (`sight_iron` post + `front_sight_hood`
  leaf, `sight_rear` aperture ring, 270 mm sight radius; both hidden by an optic), the three-prong flash hider
  `muzzle_brake` (hidden by a barrel attachment), the side-folding skeleton `stock` (the bar from the adapter — the
  cheek rest —, bending down into the vertical butt frame, the strut from under the bar to its foot, the rubber pad;
  hidden by a stock attachment), `mag_standard` 25 rounds / `mag_extended` 30 rounds (straight polymer Glock-style
  .45 magazines, 205 / 245 mm).
* **Magazine**: raked 17° back in its well (`vector_rig.MAG_Q`, the foot rearward), its pivot the top of the body under
  the chamber, the well's mouth `WELL_DEPTH` 103.5 mm down its axis. Clips move the magazine in its own frame
  (`vector_clips.mag_at`), so drops and seats follow the rake.
* **Mounts**: `mount_optic` on the rail 130 mm behind the breech face (every optic's eye then sits ≥ 2.5 cm behind the
  rail's end), `mount_laser` on the side rail, `mount_barrel` on the thread, `mount_stock` on the adapter's rear,
  `mount_underbarrel` on the rail under the nose, `mount_charm` under the adapter's left (sling point).
* **Clips** (`vector_clips.py`; rig `vector_rig.py`, MP5-style anchors: the support hand wraps the lower's slanted front
  from below): `idle`, `draw` 0.60, `fire` / `fire_last` 0.052 s (1150 rpm; the Super V turns the recoil down into the
  hand: a short shove straight back, almost no rise), `fire_ads` / `fire_last_ads` (`ads_fire`), `reload` 2.20 (thumb on
  the release, the magazine drops free down its rake out of the deep well and is tossed, the new one lined up and pushed
  home, a tug; the presentation held a little further out than for a short gun so the well shows), `reload_empty` 2.60
  (the same change, then the hand hooks the knob, flips it out, hauls the handle back and lets it fly), `inspect` 3.90,
  `walk`, `sprint`, `tac_sprint` (muzzle up at the right, the top turned in to the face). The iron-sight eye point is
  85 mm behind the rear aperture. No bolt hold-open (`slideLock` offset 0).
* **Data**: guns.json `vector` (smg ammo, magazines `vector_25` / `vector_30`, slots optic / barrel / laser / magazine /
  stock / underbarrel / charm), sounds `gun.vector.fire` / `fire_far` (`gen_vector_sounds.py`), texture sets
  `vector/upper`, `lower` (FDE), `stock`, `mag`, `barrel` (`pbr_sets_vector.py`, bound in `pbr_models_vector.py`; rails
  and small parts `common/steel_dark`, the butt pad `common/rubber`). Skins `default` (FDE), `woodland`, `desert`,
  `arctic`, `tiger` (camo on body / stock / grip), `gold`. Steps `vector`, `vector_sounds` (`build_vector.py`),
  `vector_items` (gen_gun_items); checks `check_vector.py` (the magazine's drop measured down its rake).

## 45. Baton (`models/knife/baton.glb`, weapon id `baton`, 警棍)

An original procedural friction-lock expandable baton on the knife contract (§11–§13, built like §41), after a common
21-inch police baton: a ribbed foam-rubber grip on the handle tube with a front collar, an anodised end cap with a
lanyard loop, and two nested black-oxide steel shafts — an 18 mm tube and a 13 mm rod ending in a 20 mm tip knob —
that slide out of the handle and lock on their tapered ends: 0.56 m from the cap to the tip open, 0.22 m collapsed.

```
viewmodel
├─ knife                  root at the right-hand grip (the middle of the rubber)
│  ├─ handle (mesh)       grip, handle tube and collar, end cap, lanyard loop: materials grip, hardware
│  ├─ shaft_1 (mesh)      the middle shaft; slides along the knife's Z (0 open .. +0.158 collapsed)
│  │  └─ shaft_2 (mesh)   the tip shaft and knob; slides in shaft_1 (0 open .. +0.168 collapsed)
│  │     └─ tip           empty at the knob's point
│  └─ mount_charm         through the lanyard loop (§19)
├─ camera, arm joints (§16)
```

Rig (`baton_rig.py`): a hammer grip on the rubber, the thumb wrapped towards the tip; the knife follows the IK hand
and the wrist limits turn the baton with it (as §41), it can `roll` in the fingers, and `ext1` / `ext2` (1 = locked
open) set the shafts' slide. The rest pose is open. Swings key the elbow's pole like the crowbar's
(`baton_clips.feasible`).

Clips (knives.json `baton`; light and quick, the strikes snapped by the wrist and forearm): `idle`, `draw` (0.65 s:
drawn collapsed from below, cocked beside the shoulder and flicked forward — the shafts fly out, the tip shaft a hair
behind, and lock with a crack at 0.31 s; the hand and the camera jolt), `light_1` (0.42 s forehand, the baton over the
screen centre at 0.14 s), `light_2` (0.42 s backhand, 0.14 s), `heavy` (0.80 s one-handed overhead strike landing on the
screen centre at 0.32 s and stopping dead), `inspect` (3.4 s: held up and turned in the fingers, driven tip-first at the
floor so the blow collapses it at 1.36 s, the collapsed baton looked at, flicked open again at 2.355 s), `walk`,
`sprint`, `tac_sprint`. Events `baton_equip`, `baton_open`, `baton_close`, `baton_swing`, `baton_swing_heavy` (sounds
`blockland:knife.baton_*`, `gen_baton_sounds.py`: thin steel tubes ringing ≈ 610 Hz, the lock's double crack).
Materials `shaft` (region `blade`, set `baton/shaft`), `grip` (region `handle`, `baton/grip`), `hardware` (region
`hardware`, `baton/hardware`); AO per mesh (the parts slide). Skins `default`, `chrome`, `red`, `damascus`
(legendary). Impacts: profiles `blunt` / `blunt_heavy`, decals `blunt_dent` / `bruise` (§41). Item assets as §41
(held model at 25 units per metre, display scales × 1.8).
Files: `baton_model.py`, `baton_rig.py`, `baton_clips.py`, `gen_baton.py`, `gen_baton_items.py`, `gen_baton_sounds.py`,
`build_baton.py` (steps `baton`, `baton_items`, `baton_sounds`), `pbr_models_baton.py`, `pbr_sets_baton.py`; checks
`check_baton.py` (claims `baton.glb`: nodes, the tip at the knob, the open length, materials, clips and their events'
sound events, contact times at the data's fractions, the shafts within their travel — open at rest and at every clip's
end, collapsed when the draw starts —, the right hand fixed on the grip, crosshair, eye distance, budget). 5.0k
triangles.

## 46. Ammo crate (`models/block/ammo_crate.glb`, block `blockland:ammo_crate`, 弹药箱) and the resupply

An original procedural military ammunition crate standing on the floor of its block: three-plank board walls on two
skids, corner and end cleats, manila rope handles through the end cleats, steel corner brackets, a framed lid on two
strap hinges at the back held shut by two over-centre latches at the front, olive-drab paint over pine with a yellow
band; inside, three steel ammo cans and a row of loaded magazines. 0.84 × 0.50 m, 0.41 m high (13.4 × 8 × 6.6 px),
front towards −Z (north). 7.8k triangles.

```
crate                     root at the middle of the block's floor (identity)
├─ body (mesh)            walls, skids, cleats, handles, brackets, hinge knuckles, latch plates
├─ contents (mesh)        ammo cans, magazines
├─ lid (mesh)             pivot on the hinge axis (back top edge, along X); +X rotation opens it
├─ latch_L, latch_R (mesh) pivots at the levers' feet; −X rotation swings them down and out
```

Clips `open` (0.55 s: the latches snap down, the lid swings up to 105° and settles; events `latch`, `lid_open`) and
`close` (0.42 s: the lid falls shut and bounces, the latches are pushed back; events `lid_shut`, `latch`); the rest
pose is shut. Materials `wood`, `marking`, `rope` (region `body`), `metal` (`hardware`), `can`, `mag`, `brass`
(`contents`); sets `ammo_crate/wood`, `ammo_crate/marking`, `ammo_crate/rope`, `common/parkerized`, `equipment/olive`,
`common/anodized_black`, `common/brass`; AO per mesh (the lid moves).

Block (`supply/AmmoCrateBlock`, `AmmoCrateBlockEntity`, `SupplyContent`): `FACING` horizontal (the front towards the
player who placed it), render shape invisible (the block entity renderer `client/supply/AmmoCrateRenderer` draws the
GLB with the weapon pipeline, lit like third-person weapons, through vanilla render types in the shadow pass); the
collision / outline box matches the shut crate (checked). The block model only carries the break particles; the
item shows the rendered icon in the GUI and a cuboid crate elsewhere; it drops itself. The lid: the server sends a
block event (`EVENT_OPEN`, the ticks it stays open), each client plays `open`, holds it and plays `close`.

Resupply (`server/supply/AmmoCrateService`, rules `data/blockland/supply/ammo_crate.json`): a held gun keeps vanilla's
block interaction to itself, so the client sends `SupplyPackets.C2SUse` when the use button is pressed on a crate within
`reach` (aiming is held off while a crate is under the crosshair). The server tops the gun's ammunition up to
`magazines` spare magazines of the fitted magazine on top of a full gun (loose rounds into the inventory), opens the lid,
plays the rummage, swings the player's off hand (the arm reaching, as others see it), answers `S2CResupplied` and says
what it gave; a gun with nothing to take gets "ammo full". `cooldown` seconds between uses.

First person (`client/supply/ResupplyOverlay` through `client/viewmodel/WeaponOverlays`, motion data
`assets/blockland/supply/resupply.json`, works for every gun): the viewmodel root is lowered and canted about `pivot`
(keys `[t, pitch, yaw, roll, x, y, z]`); the left arm's joints move rigidly about the shoulder — `down` swings it into
the crate, `up` turns it so the wrist comes to an offset from the gun's own magazine node (keys `[t, down, up]`); the
gun's fitted magazine mesh shows in the left hand while it comes back up; at the end the gun's ordinary reload starts
(`reach`, 0.95 s) or, the gun being full, the magazine goes into a pouch (`stow`, 1.45 s). The hands are busy
meanwhile (no firing, aiming, reloading or inspecting). Dual guns skip the arm. Sounds `supply.crate_open`,
`supply.crate_close`, `supply.rummage`, `supply.magazine` (`gen_ammo_crate_sounds.py`).

Files: `ammo_crate_model.py`, `gen_ammo_crate.py`, `gen_ammo_crate_items.py`, `gen_ammo_crate_sounds.py`,
`build_ammo_crate.py` (steps `ammo_crate`, `ammo_crate_items`, `ammo_crate_sounds`), `pbr_sets_ammo_crate.py`,
`pbr_models_ammo_crate.py`, `ammo_crate_preview.py` (first-person resupply sheets from the same motion data); checks
`check_ammo_crate.py` (claims `ammo_crate.glb`: nodes and pivots, materials, clips and their end poses, inside the
block, lid clear of the contents, the collision box, the resupply data and its sounds, budget). `check_glb.py` with
no arguments also checks `models/block/*.glb`.

## 47. Movement actions (slide, prone, dive)

One action system for the body's special movements — `movement/action` (common), `server/movement/ActionService`
(authority), `client/movement` (prediction, camera, third person) and the shared first-person layers in
`client/viewmodel` — so every gun and knife adapts without clips of its own.

| Action | Data | Mode setting | Collision pose | Eye (scale 1) | Trigger |
|---|---|---|---|---|---|
| `SLIDE` 滑铲 | `movement/slide.json` (`SlideRules`) | `allowSlide` | crouching | 0.97 | crouch (or the slide key, unbound) while sprinting on the ground |
| `PRONE` 匍匐 | `movement/prone.json` (`ProneRules`) | `allowProne` | crawl (vanilla's swimming pose out of water, 0.6 high) | 0.4 | the prone key (default C) on the ground, or out of a slide |
| `DIVE` 飞扑 | `movement/dive.json` (`DiveRules`) | `allowDive` | crawl | 0.4 | the prone key in the air within `windowTicks` of a sprint leaving the ground |

* **State.** `BodyActions` is the one facade every side asks: `state(entity)` → `ActionState(action, yaw)` (null when
  upright), `eyePosition(entity[, partialTick])` — the only place shots, cuts and throws take their eyes from —,
  `isActing` / `isSliding` / `isLying`, and each action's `hitBody` / `hitHead` (`BodyBoxes`). The server registers
  `ActionService` as its view; the client its prediction plus what the server reported for others. Prone's yaw is the
  body's (`yBodyRot`, it turns with the player); a slide and a dive keep the direction they started in.
* **Hit boxes.** `combat/HitShapes.bodyBoxes`: the body box from `back` behind to `forward` ahead of the position along
  the yaw, `height` high, `halfWidth` wide; the head a cube of `half` round `back` behind (negative: ahead — lying down
  the head is in front) at `height`. Prone and dive: body 1.05 back / 0.35 ahead, 0.45 high; head 0.55 ahead, 0.3 up.
* **Network.** `C2SAction(action, start, yaw)`, `S2CAction(entity, action | -1, yaw)` (to trackers; to the player
  itself on a refusal, an end or a change), `S2CRules(mask)` (bit per allowed action, re-sent every 10 ticks).
* **Server rules.** Not dead / spectating / mounted / gliding / in water / flying, the mode allows it, per-action
  cooldown and a 4-tick spam gap. Slide: a sprint on the ground fast enough (unchanged). Prone: on the ground; it ends
  a slide. Dive: in the air within the window after a sprint left the ground, fast enough. A dive that touches the
  ground becomes prone on the server (sound `movement.dive_land`). Ending an action releases the forced pose; under
  anything too low vanilla keeps the player crawling. Outside matches the config `movement.slideOutsideMatches` allows
  all three. Mode settings missing `allowProne` / `allowDive` follow `allowSlide` (backend `MovementSettings`, admin
  checkboxes; infection: humans only).
* **Client.** `ClientActions` routes the keys (prone key `key.blockland.prone`), the network and the camera to one
  local action at a time — `ClientSlide`, `ClientProne`, `ClientDive` — through one input wrapper (`ActionInput`:
  slide and dive keys do not push; crawling they push at `crawlSpeed` × vanilla's crawl, no jump, crouch or sprint).
  Prone: the prone key, jump or crouch get up (to a crouch while crouch is held) when there is room to stand. Dive:
  launch speed `boost` × the run speed within `minLaunchSpeed`…`maxLaunchSpeed`, `lift` upwards, `airDrag` per tick
  instead of vanilla's; on touchdown prone with a chest slide (`landSlideTicks`, `landFriction`, `landEndSpeed`).
  Camera: the slide's roll and FOV kick (unchanged), the dive's pitch / roll / FOV and its landing dip; lying down the
  camera is vanilla's crawl eye height with vanilla's smoothing.
* **First person** (`ViewmodelMotion.BodyMotion`: `slide()`, `prone()`, `dive()`, `crawl()`, `proneSway()`): the slide's
  tilt as before; `ProneLayer` (like `SwimPose`, style `GUN` / `KNIFE`): the drop onto the chest (a weapon dip and a
  camera jolt, harder landing a dive), lying still low and steady, aimed a slow wander of `aimSway` degrees, crawling the
  crawl carry (canted across the chest, muzzle down, rocking with each elbow, the free arm reaching forward at the lower
  left, camera heave on each elbow) that comes up at once for a shot, a reload, a cut or aiming; the dive throws the
  weapon forward and up.
* **Third person** (`ActionPoses`): the slide's lean (unchanged); prone is vanilla's crawl pose (forced and synced),
  with a weapon in hand the arms held forward on it and the body rocking with the crawl; the dive is the crawl's flat
  body with the arms thrown forward and the nose dipping `pose.bodyPitch`.
* **Sounds** (`gen_movement_sounds.py`, step `movement_sounds`): `movement.prone`, `movement.prone_up`,
  `movement.dive`, `movement.dive_land` (2 variants each); the slide keeps `movement.slide`.

## 48. Desert Eagle (`models/gun/deagle.glb`, weapon id `deagle`, 沙漠之鹰)

The heavy .50 gas-operated semi-automatic pistol after the Mark XIX (what §43 modelled before the hand cannon became a
grenade pistol), an original procedural model built to the **P9 contract** like the USP (§26): `deagle_model.py`
defines the names the P9 rig and clips read and `gen_deagle.py` re-runs the P9's rig, clips and locomotion against it
(`variants.load`). 267 mm long, 32 mm across the slide:

* `barrel` (fixed): a 6-inch barrel of the Mark XIX's triangular section (short upright flanks on the frame, long flats
  sloping in to the narrow top), the 12.7 mm bore open at the muzzle, a low rail with cross slots along its top, the
  **front sight blade at the muzzle** (on the fixed barrel, its top on the sight line);
* `slide`: the short heavy bolt-carrier housing behind the barrel (rear serrations, the ejection port on the right, the
  extractor, the ambidextrous safety levers); `sight_iron` is only the rear sight (two dots);
* `frame` (steel, material `polymer` by the contract's name): the dust cover, a big squared trigger guard with a hooked
  serrated front, a deep grip with wrap-around rubber `grip` panels, the magazine release, the takedown lever; a rounded
  spur `hammer`, a wide `trigger`, a long `slide_stop`;
* one single-stack 7-round magazine (`mag_standard`; `mag_extended` repeats it for the contract and is never shown).

Patched into the P9's rig and clips: slide stroke 40 mm (`SLIDE_LOCK`, guns.json `slideLock`), the optic plate on the
slide in front of the rear sight, the hip pose lower, further right and back, and a far heavier shot (`fire_kick`: the
wrists driven back and the muzzle flipping high, the arms absorbing it); `fire_ads` / `fire_last_ads` hold the sight
picture tighter. Data (guns.json): category pistol, **ammo `pistol`** with `damageScale` 1.9 (6 × 1.9 = 11.4 a body
shot, ×2 to the head: two body shots or one head shot; there is no magnum ammunition), magazine `deagle_7`, semi,
150 rpm, heavy recoil and camera kick, reload 2.0 / 2.45 s. **Slots: `optic` only.** Skins `default` (black),
`stainless` (regions `body` / `barrel`), `gold` (legendary), plus the shared gun skins. Sound `gun.deagle.fire`
(`gen_deagle_sounds.py`). Items: icons per skin, the held model. Files: `deagle_model.py`, `gen_deagle.py`,
`gen_deagle_items.py`, `gen_deagle_sounds.py`, `build_deagle.py` (steps `deagle`, `deagle_items`, `deagle_sounds`),
`pbr_models_deagle.py`; checks `check_deagle.py` (claims `deagle.glb`: the P9 contract, the pistol budget, the slide
stroke matching guns.json, the front sight lined up with the rear one, the optic-only slot list). 7.2k triangles drawn.

## 49. Revolver (`models/gun/revolver.glb`, weapon id `revolver`, 左轮)

(Owned by the revolver builder.) An original six-shot double-action .357 in the classic mould: a 4-inch barrel with a
full underlug and a ventilated rib (five slots), a red-insert front post and an adjustable rear sight on the top strap, a
fluted six-chamber cylinder swinging out to the left on its crane, the ejector rod under the barrel and its star, the
cylinder release latch behind the cylinder, a spur hammer and a smooth trigger, a wooden grip. Built by
`build_revolver.py` (`revolver_model.py` meshes, `revolver_rig.py` rig, `revolver_clips.py` clips, `gen_revolver.py`,
`gen_revolver_items.py`, `gen_revolver_sounds.py`); `check_revolver.py` checks it.

**Data** (`guns.json`): pistol ammunition, `damageScale` 1.75, 160 rpm (each pull is the long double-action stroke), the
`revolver_6` magazine (6, node `mag_standard`, no extended magazine), reload 2.7 s full or empty (`reloadEmpty` and
`fireLast` reuse `reload` / `fire`), slots `optic` (a small mount on the top strap; it hides `sight_rear`) and `charm`
only. `ejectEvent` `case_out`: no case on a shot (they stay in the cylinder); the reload's six `case_out` events throw the
six cases from `ejection`. `slideLock` is `cylinder` (nothing locks back on the last shot).

**Nodes.** `gun` → `frame` (`body`), `barrel`, `grip`, `sight_iron` (the front post), `sight_rear`, `hammer` (pivot behind
the cylinder; cocks back `HAMMER_COCKED` 38°), `trigger`, `latch` (slides forward to release), `crane` (its axis under the
cylinder's left front; swings `CRANE_OPEN` 86° out and down to the left) → `cylinder` (spins about the bore axis) →
`ejector` (rod and star, a 22 mm stroke along the axis) → `rounds` (six live rounds; scaled to 0 while empty), and
`ejection` (on the cylinder);
`mag` (the speedloader in the left hand, scale 0 outside the reload) → `mag_standard` (the loader), `loader_rounds`;
`muzzle`, `aim_iron`, `mount_optic`, `mount_charm`, `fx_shell`. The rig follows the P9 interface (§3–§6) so its
`idle`, `draw`, `walk`, `sprint` and `tac_sprint` are the P9's run on the revolver's rig; the hand anchors are its own
(`grip`, `support`, `cradle` under the open cylinder, `cylinder`, `ejector`, `loader`).

**Fire** (0.30 s): the trigger's stroke cocks the hammer and turns the cylinder one chamber, 60°, then the hammer falls at
22 ms and the heavy kick follows. The cylinder's index is a STEP channel (`step_nodes`): it advances 60° and snaps back
to 0 at the end of the clip, which looks identical by the cylinder's six-fold symmetry, so every shot plays the same
clip. `fire_ads` is `ads_fire.aimed` over the iron sight.

**Reload** (2.70 s, a speedloader): the thumb pushes the latch (`revolver_latch`), the left fingers push the cylinder out
(`cylinder_open` 0.22–0.36), the gun rolls muzzle-up, the left palm punches the ejector (`ejector` 0.60) and the six
cases fly (`case_out` ×6, 0.615–0.665) — the cylinder's `rounds` go to scale 0; the gun rolls the open cylinder up
toward the camera (−62° roll), the left hand brings the loader in from the belt, aligns its rounds with the chambers
(`speedloader` at 1.42, the loader's rounds coincide with the chambers), twists the knob (`speedloader_twist` 1.50), the
`rounds` come back and the empty loader leaves; the left fingers swing the cylinder shut (`cylinder_close` 2.16) and the
hands return to the grip.

**Inspect** (3.60 s): the cylinder swung out, the left fingers spin it (three turns, 0.70–1.75, `cylinder_spin`), the
right flank turned to the camera, the cylinder snapped shut (`cylinder_close` 2.24).

**Materials and skins.** `steel` (`body`, `revolver/blued`), `barrel` (`barrel`, `revolver/blued`), `grip` (`grip`,
`tommy/walnut`), `steel_dark` (`detail`), `loader` (`magazine`, `p9/polymer`), `brass` / `bullet` (`common/brass`, no
region), `bore` and `insert` (no PBR set; the insert glows faintly). Skins `default` (blued, walnut), `stainless`
(`revolver/stainless`), `ivory` (`revolver/ivory` grips) and `gold`.

**Sounds** (`gen_revolver_sounds.py`): `gun.revolver.fire` (three variants: the .357's ringing crack and the cylinder
gap's spit), `gun.revolver.dry` (the hammer on an empty chamber), and the clip events `gun.revolver_latch`,
`gun.cylinder_open`, `gun.ejector`, `gun.case_out`, `gun.speedloader`, `gun.speedloader_twist`, `gun.cylinder_close`,
`gun.cylinder_spin`.

## 50. Body armour (`vest`, 防弹衣) and armour plates (`armor_plate`, 防弹板)

A plate carrier worn in the chest slot (`blockland:vest`, chest-slot equippable with no vanilla armour points and no
armour model) holding up to three armour plates (`blockland:armor_plate`, stacks of 8). Rules are data:
`data/blockland/armor/armor.json` — `maxPlates` 3, `plateDurability` 10 (a player has 20 health), `vestProtection` 0.1,
`headshotsBypass` false, `insertSeconds` 1.1, `insertDip` 14°, the plate's first-person `handling`, and `covers`: damage
type id (or `#tag`) → the share of such a hit the armour takes (bullets 1.0, explosions 0.7, knives 0.6, a bare hand or a
monster's claw 0.5; falls, fire, the void and anything unlisted pass it by).

**Protection** (`server/armor/ArmorService`, one hook: the hurt event, before vanilla's own armour). Of a covered hit
`share × damage` meets the carrier: it wears the plates down, the last inserted (outermost) first, a plate breaking when
its durability runs out and the rest going on to the next; what gets past the plates is cut by `vestProtection`; the
uncovered part of the hit goes straight through. The plates live in the carrier's `blockland:vest` item component (the
durability left in each, synced with the stack, so others see the plates in it). A hit on the armour plays
`armor.plate_hit` for everyone around, a plate breaking `armor.plate_break`; the wearer gets `S2CHit` (the HUD flashes; a
break flashes the screen's edges and names it).

**Inserting.** Holding a plate with the carrier on, a left-click (no vanilla hit or mining with a plate in hand) starts the
insert at once on the client — the clip and the head bowing `insertDip` to watch it, the plate holding still against the
bow so it is seen going in — and sends `C2SInsert`; the server checks room (`maxPlates`), answers `STARTED` and puts the
plate in after `insertSeconds` if it is still in hand and the carrier still on (`DONE`, one plate used, `armor.plate_insert`),
else `CANCELLED`; `NO_VEST` / `FULL` come back at once with an action-bar message. After `DONE` the next plate is drawn.

**The carrier on the body** (`client/armor/VestLayer`, a player render layer): `models/armor/vest.glb` drawn in the body
part's frame — the model's +Y up and −Z forward are the torso's, the origin at the neck, one unit a block, so it bends and
turns with the torso in every pose (crouching, prone, swimming) — with the weapon pipeline like the third-person weapons,
through vanilla render types while the shadow map captures entities. Nodes: `armor` (root) → `carrier` (the bags,
cummerbund, shoulder straps, three magazine pouches, admin flap, drag handle; materials `carrier`, `webbing` (region
`body`), `hardware`), `plate_1` (the front plate's bulge of its bag), `plate_2` (back), `plate_3` (the two side plates):
a plate's node shows only while that many plates are in. No clips. The carrier lies on the torso (checked: no vertex
inside it) and never stands off more than ~0.1. 3.9k triangles.

**The plate in first person** (`client/armor/PlateViewmodel`, the shared `ViewmodelRig`): `models/armor/plate.glb` —
`viewmodel` → `plate` (root) → `plate_body` (mesh: a single-curve ESAPI-cut ceramic plate, 0.25 × 0.32 m, 22 mm, bent
round 0.45 m, polyurea coat `plate` (set `armor/polyurea`) and a `label`), `camera`, both arms. Both hands hold it by its
side edges (palms on the edges, fingers round onto the strike face), fixed on it in every frame (checked). Clips: `idle`
(2.0 s), `draw` (0.50 s, up from the pouch, event `plate_draw`), `insert` (1.0 s, stretched to `insertSeconds`: drawn in to
the chest, turned upright like the bag and pushed down into it, seated with a jolt at 0.72 and kept down until the next
draw), `walk`, `sprint` (`sprint` also serves the tactical sprint). Sounds `armor.vest_equip`, `plate_draw`, `plate_insert`,
`plate_hit`, `plate_break` (`gen_armor_sounds.py`).

**HUD** (`client/armor/ArmorHud`): over the hearts (vanilla's armour row — the carrier gives no vanilla armour), one
segment per plate slot filled with the plate's durability left (amber when low), empty slots outlined; a progress bar
under the crosshair while a plate goes in.

Items in the creative tab "防具" (an empty carrier, a plate, and a carrier with three plates in it). Kits give them by id:
`blockland:vest` at `chest`, `blockland:armor_plate` anywhere. Files: `armor_model.py`, `armor_rig.py`, `armor_clips.py`,
`gen_armor.py`, `gen_armor_items.py`, `gen_armor_sounds.py`, `build_armor.py` (steps `armor`, `armor_items`,
`armor_sounds`), `pbr_sets_armor.py` (`armor/nylon`, `armor/webbing`, `armor/polyurea`), `pbr_models_armor.py`; checks
`check_armor.py` (claims `models/armor/vest.glb` and `plate.glb`).
