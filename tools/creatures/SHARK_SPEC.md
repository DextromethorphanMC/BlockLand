# BlockLand shark contract (鲨鱼)

An ocean predator, `blockland:shark`: one shared entity class configured by data, drawn by BlockLand's own pipeline
from a GLB whose body the client bends as it swims.

## 1. Modules and boundaries

| Module | What it owns |
|---|---|
| `com.blockland.shark` (shared) | `SharkData` (the data below), `Shark` (the entity: movement, senses, bite, hit shapes, spawn rules), `SharkHuntGoal` (circle, charge, bite, retreat), `SharkContent` (entity type, attributes, spawn placement, spawn egg, sound events) |
| `client/shark` | `ClientShark` (registers the renderer), `SharkRenderer` (the GLB through the gun/vehicle pipeline: PBR, `SceneLighting`, shadows via `ShadowPass`, its motion via `ObjectMotion`), `SharkBend` (the body's swimming wave on the CPU) |

Other modules know the shark only as a `LivingEntity` and through the shared contracts: weapons hit it through
`combat/HitShapes` (it is `HitShapes.Shaped`: boxes along its body, the head a head hit), bullets in water through
`combat/Ballistics`, its blood is the server-confirmed `HurtCheck` → `Impacts` flow of every living target (impacts.json
`living`), vehicles run it over like any body.

## 2. Data

* `data/blockland/shark/shark.json` (classpath, both sides): `health`, `armor`, `speed` (vanilla attribute values),
  `damage` per difficulty (`easy`/`normal`/`hard`; peaceful never bites players), `knockback`, `xp`, `aggroRange` and
  `bloodRange` (blocks; a target below `bloodBelowHealth` of its health or hurt in the last two seconds is noticed from
  `bloodRange`), `circleSeconds` [min, max], `circleRadius`, `chargeSpeed`, `biteCooldown`, `retreatSeconds`,
  `ignoreRiders`, `huntFish`, `spawn` (`minDepth`: the water column at the spot; `belowSeaLevel` [min, max];
  `maxNearby` sharks within `nearbyRadius`), and the client's `animation` (`waveLength`, `amplitude` +
  `speedAmplitude` at full speed, `beatsPerBlock`, `idleBeatsPerSecond`, `turnBend`, `jawOpen` degrees).
* Where it spawns: `data/blockland/forge/biome_modifier/shark_spawns.json` (Forge `add_spawns`: ocean, deep ocean,
  lukewarm, deep lukewarm and warm ocean, weight 2, alone), category `water_creature` (the vanilla cap it shares with
  squid and dolphins), placement `in_water`; natural spawns also obey `spawn` above (spawn eggs, spawners and commands
  always work).
* Drops: `data/blockland/loot_table/entities/shark.json` (1–3 cod, cooked when it burned, looting adds; now and then a
  prismarine shard when a player killed it).

## 3. Behaviour

* Swims like a dolphin (`SmoothSwimmingMoveControl`, water-bound paths), cruising at random; out of the water it can
  only flop about and runs out of air (vanilla `WaterAnimal`: 15 s, then 1 heart a second).
* **Prey** (`Shark.isPrey`): a player or fish in open water — water under its feet (wading in the shallows is safe) —,
  not riding anything (boats and vehicles are left alone: `ignoreRiders`), within `aggroRange`, or `bloodRange` when it
  bleeds; players never on peaceful or in creative/spectator. Hurt, it turns on its attacker (if that one is fair prey).
* **The hunt** (`SharkHuntGoal`): it circles the prey at `circleRadius` a little under it for `circleSeconds` — the
  warning, a fin cutting round —, charges at `chargeSpeed`, bites once in reach (damage by difficulty, knockback; entity
  event 4 opens the jaw on clients; sound `shark.bite`), turns away for `retreatSeconds` and comes round again, each
  circle 30 % shorter; fish get a short circle. It gives up the moment the prey stops being fair prey.
* Sounds: `shark.bite`, `shark.hurt`, `shark.death`, `shark.swim` (its tail breaking the surface while it goes fast near
  the top), `shark.flop` (on land).

## 4. Model and drawing

`assets/blockland/models/creature/shark.glb`, built by `tools/assets/creature_shark.py` (`python -I
tools/assets/build_all.py creature_shark`; texture sets `creature/shark_back` / `creature/shark_belly` in
`pbr_sets_creatures.py`, bound in `pbr_models_creatures.py`; checked by `check_creature_models.py` through
`check_glb.py`). Space: metres, +Y up, the nose towards −Z at z = −1.75, the tail fin's tips at about +1.75, the origin
on the body's axis (the entity's feet + `Shark.AXIS`).

| Node | Meaning |
|---|---|
| `body` | the root at the origin, unturned: body, fins, eyes, gills, nostrils, upper teeth — every vertex bent by its z |
| `jaw` | the lower jaw, hinged behind the mouth (z ≈ −1.03); opens about its local X; carried by the head's bend |

* **The swimming wave** (`SharkBend`): every vertex of `body` is carried by the spine point at its own z — moved
  sideways by `sway · envelope(s) · sin(phase − 2π(z + 1.75)/waveLength) + bend · s²` (s = 0 at the nose, 1 at the tail;
  the envelope is mostly the tail's) and turned by the spine's slope there, normals included — into a vertex buffer
  laid out like the model's own (as the skinned arms are), drawn with its static index ranges. The phase advances with
  the ground covered (`beatsPerBlock`) plus a slow idle beat (faster on land: thrashing), the sway grows with speed,
  the bend follows the body's turning. The jaw is moved by the same rigid motion as the body at its hinge.
* Pitch follows the entity's (the swim control tilts it), a dying shark rolls belly up. The bend is not in the motion
  vectors (only the whole body's motion, through `ObjectMotion`), like the arms' skin.
* Countershading: a ring of the body's loft lies exactly on the countershading line on each side, so the off-white
  belly meets the slate back along a smooth wavy curve.

## 5. Items and sounds

* Spawn egg `blockland:shark_spawn_egg` (vanilla `SpawnEggItem`, listed in the spawn egg tab); its icon from
  `gen_creature_items.py` (`creature_items` step).
* Sounds from `gen_creature_sounds.py` (`creature_sounds` step: `sounds/creature/shark_<key>.ogg`, the events merged
  into `sounds.json`), subtitles `subtitles.blockland.shark.<key>`.
