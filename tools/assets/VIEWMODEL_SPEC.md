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

Root nodes (identity transforms) each carrying one mesh, named `<side>_<segment>_<variant>[_sleeve]`:
side `R`/`L`, segment `upper`/`fore`, variant `wide` (4 px skins) / `slim` (3 px skins, 3/4 as wide).
Real-arm proportions (v2): every segment is a box that tapers from its joint end to its far end —
upper arm 0.056 × 0.058 at the shoulder to 0.050 × 0.050 at the elbow, forearm 0.050 × 0.048 at the elbow to
0.043 × 0.038 at the wrist (width × depth, wide skin; v1 used 0.07 × 0.07 boxes, which read as far too thick in
first person). Meshes are in joint-local space (segment from y = 0 down to y = −0.30). UVs map to the 64×64 player
skin: right arm base (40,16) overlay (40,32), left arm base (32,48) overlay (48,48); the upper segment uses the top
6 px rows of the side faces, the forearm the bottom 6 px rows; the hand end is the bottom face. `_sleeve` meshes are
the overlay layer, inflated by 0.003 per side, material `skin_overlay` (MASK, cutoff 0.1). Base meshes use material
`skin`. The runtime binds the local player's skin texture to both.

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
  `{"joint": "<viewmodel joint name>", "variant": "wide" | "slim", "layer": "base" | "sleeve"}`.
  The runtime draws each mesh at that joint's matrix, with the variant that matches the player's skin model, and
  both layers. Joints can be added without code changes. A mesh whose joint is missing in a weapon file is not drawn.
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
| underbarrel | `mount_underbarrel` (only on weapons that offer it) | `vertical_grip.glb`, `angled_grip.glb`, `bipod.glb` (§35) | — |

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

Budgets (first person, every part of the weapon file, attachments not counted): rifles / SMGs / shotguns ≤ 9k
triangles, pistols ≤ 5k each, knives ≤ 3k.

## 24. Thompson SMG (`models/gun/tommy.glb`, weapon id `tommy`)

Based on *Gangster Tommy Gun* by Pieter Ferreira (CC-BY-4.0). A 1928-pattern .45 SMG at real scale (overall ≈ 0.85 m,
barrel ≈ 0.27 m): finned barrel with a compensator, receiver with the cocking knob on top, rear leaf sight, front
post, wooden buttstock, wooden vertical foregrip, wooden pistol grip, stick magazine and drum. Built like §18:

```
viewmodel
├─ gun                       pivot at the pistol grip
│  ├─ receiver, barrel, foregrip, grip, stock         meshes (stock: wooden butt; hidden by the stock slot)
│  ├─ muzzle_brake           compensator (hidden by the barrel slot)
│  ├─ bolt                   cocking knob on top of the receiver, +Z = rearward; cycles in `fire`, pulled in `reload_empty`
│  ├─ trigger, selector      meshes
│  ├─ sight_iron             front post; sight_rear: the rear leaf sight (hidden while an optic is fitted)
│  ├─ aim_iron               eye point (§5)
│  ├─ mag ─ mag_stick (30-round stick, default), mag_drum (50-round drum)
│  ├─ muzzle (at the compensator), ejection (right side of the receiver)
│  └─ mount_optic (on a riser rail ahead of the cocking slot), mount_laser (right of the receiver's front block),
│                            mount_barrel (crown), mount_stock (receiver rear), mount_charm (left of the frame)
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
| `wood` | stock | buttstock (split: `wood_foregrip` → handguard, `wood_grip` → grip) |
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
* **Frame-rate independence.** Every runtime layer runs on springs (fixed 240 Hz substeps) or elapsed time, input
  rates are clamped per second, not per frame, and clip curves are read smoothly (§15).

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
right, the LED emitter at the rear) and a thin frame round the window. The window is one flat `glass` face 32 × 22 mm
with 2 mm corner radii, centred 0.0242 over the mount point at z −0.012 (the frame's rear face), facing +Z, UV 0..1
across it (u along +X, v down); the frame's inner walls (`interior`) open forward (13 % sideways, 11 % up and down) more
steeply than the sight lines from `aim` to the window's edges, and the base housing stays under the sight lines to its
bottom edge, so from the eye only the window shows. A `reticle` quad (the dot when picture in picture is off) sits on the sight
line just behind the glass, `aim` 0.14 behind it. Region `optic`; materials `attachment`, `steel_dark`, `glass`,
`interior`, `reticle`.

**Underbarrel slot** (`underbarrel`, 下挂, skin region `underbarrel`): attachments clamped under the handguard or rail.
The weapon file carries an empty `mount_underbarrel` (§19 mount rules: −Z down the bore, +Y up; the attachment hangs
along −Y) on the underside of the handguard / forend rail, **ahead of the support hand**: the hand stays on the
handguard in every clip, no clip changes with the attachment. Models (`underbarrel_models.py`, frame as every
attachment, root `attachment`):

| File | Look | Data |
|---|---|---|
| `vertical_grip.glb` | rail clamp with a thumb screw, a tapered finger-grooved grip ≈ 0.10 m straight down, end cap | recoil × 0.88, ADS × 1.05 |
| `angled_grip.glb` | a long low clamp and a wedge whose rear face slopes down and forward (a palm stop), ribs on the ramp | recoil × 0.95, ADS × 0.93 |
| `bipod.glb` | clamp, hinge yoke, two legs folded forward along −Z (≈ 0.18 m), rubber feet | recoil × 0.82, ADS × 1.12, hip spread × 1.1 |

Materials `attachment`, `steel_dark` (region `underbarrel`), `stock_pad` (the bipod's feet, `none`). Mounts as built:
AK-47 under the lower handguard near its front (gun-local design point (0, −0.0315, −0.178), the grip's front edge at
the handguard's front), AWP on the accessory rail under the front of the forend. guns.json: ak47 `underbarrel`
[vertical_grip, angled_grip], awp [bipod]; a weapon without the slot needs no mount (`loadouts.OPTIONAL_SLOTS`).
Checks (`check_attach.py`): the mount where a weapon offers the slot, the builds `rect_full`, `angled_full`,
`bipod_full` ≥ 0.06 from the eye in every clip, and in the holding clips (idle, fire, fire_last, walk) no underbarrel
attachment within 4 mm of the support hand's skin (arms.glb hand and finger meshes); other clips are reported.

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
pulled, pin and cooked spoon gone in the hold, the lighter flame at the rag, crosshair, eye distance, budget 4000).

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
