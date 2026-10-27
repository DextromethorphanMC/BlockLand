# BlockLand vehicle contract

Vehicles (载具) are entities players ride: one shared system configured by data, drawn by BlockLand's own render
pipeline from GLB models (COD-class models and PBR as `tools/assets/VIEWMODEL_SPEC.md` §53 asks of weapons). The first
four: `motorcycle` (摩托车, 1 seat), `speedboat` (发动机小游艇, 5 seats), `tank` (坦克, 3 seats: driver, cannon gunner,
machine gunner), `offroad` (越野车, 4 seats).

## 1. Modules and boundaries

| Module | What it owns |
|---|---|
| `com.blockland.vehicle` (shared) | `VehicleData` / `VehicleCatalog` (the data below), `VehicleEntity` (one class for every vehicle, configured by its data), `VehicleControl` (one tick of the driver's input), `VehicleFrame` (model space ↔ world, the weapon chain), `VehicleShell` (the cannon's round), `VehicleItem` (spawn items), `Vehicles` (the hooks each side sets), `VehicleContent` (entity types, items, sounds, creative tab 载具) |
| `server/vehicle` | `VehicleService` (wired by `BlockLandServer`): whether a player may use vehicles, the driver's inputs, riders' actions, the shelter of a hull, anti-cheat signals; `VehicleWeapons`: what a fired weapon does |
| `client/vehicle` | `ClientVehicles` (input, keys, cameras, sight zoom, rider poses, packets), `VehicleRenderer`, `VehicleHud`, `VehicleEffects` (exhaust, wake, wreck fire, engine sound, headlights, muzzle flashes, tracers, impacts, explosions) |
| `network/packet/VehiclePackets` + `network/VehiclePacketListeners` | messages only, dispatched to listener interfaces |

Other modules know vehicles only through this data and these classes' public surface. Weapons fired from a vehicle use
the shared combat contracts: hits through `combat/HitShapes`, blood only on a confirmed hurt (`combat/HurtCheck`; the
machine gun's impacts are the server's, so nothing is predicted and `HurtConfirm` is not needed), impacts through
`client/impact/Impacts`, what a round carries through `combat/RoundTraits`. Hulls are hit as their own yawed boxes
(`HitShapes.Shaped`, the data's `hitBoxes`), never as the square entity box. Vehicles soak damage like armour does:
their hull reads `RoundTraits.current().armorDamage()`.

## 2. Data (`data/blockland/vehicles/vehicles.json`, on the classpath like `guns.json`, the same on both sides)

Model space everywhere: metres, +Y up, front **−Z**, origin on the ground (a boat: the keel) under the middle of the
wheelbase. Speeds blocks/s, accelerations blocks/s², angles degrees, times seconds. Per vehicle id:

* `name`, `model` (`blockland:models/vehicle/<id>.glb`), `size` [width, height] of the (square) entity box, `mass`,
  `health`, `armor` (0..1: share of incoming damage the hull ignores; a round's `RoundTraits.armorDamage` divides it;
  blasts and fire meet half of it).
* `movement`: `kind` — `bike` | `wheeled` | `tracked` | `boat`; `maxSpeed`, `reverseSpeed`, `accel`, `brake`,
  `turnRate` (°/s at full steer; wheels and the boat need speed to turn, tracks turn on the spot), `grip` (share of the
  sideways slide removed per tick), `stepHeight`, `wheelRadius` (spins the wheels), `wadeDepth` (deeper water stalls a land
  vehicle), boats `draft` and `planing` (above it the hull rides higher, the bow lifts), bikes `lean` (into turns).
* `seats`: in order, each `{ "node": "seat_<i>", "role": "driver" | "gunner" | "mg" | "passenger", "exposed": bool,
  "camera": "first" | "third", "pos": [x, y, z] (the rider's hips), "mount": "hull" | "turret", "yawLimit": ° }`. Seat 0
  is the driver. A `turret` seat's `pos` is relative to weapon 0's yaw pivot and turns with it. `exposed` riders can be
  hit by rounds and blades; the others sit inside the hull (only blasts, fire, drowning and falls reach them, and they are
  not drawn). Riders who are not `exposed` passengers have their hands on the vehicle (`ActionLocks`: no own weapons).
* `hitBoxes`: `{ "center": [x, y, z], "half": [x, y, z] }` boxes yawed with the hull.
* `weapons` (at most 2, synced): `{ "id", "seat", "mount" ("turret": the pivot is in weapon 0's yaw space), "pivotYaw",
  "pivotPitch", "muzzle" (node names), "pivot" [yaw pivot], "pitchPivot" [relative to the yaw pivot], "muzzleOffset"
  [relative to the pitch pivot], "yawLimit" ([min, max] or absent = all round), "pitchLimit" [min, max] (+ = up),
  "turnRate", "kind": "shell" | "hitscan", "damage", "fireInterval", "range", "spread" (hitscan cone), "ammo"
  (impacts.json profile), "magazine", "reload", "shellSpeed", "shellGravity", "zoom" (the sight's magnification),
  "explosion" { radius, innerRadius, damage, minDamage, knockback, hullDamage } }`.
* `effects`: `exhaust` [[x, y, z] …], `headlights` [[x, y, z] …], `wake` [x, y, z].
* `sounds`: keys; each is the sound event **`blockland:vehicle.<id>.<key>`** (registered from the data; the files and
  `sounds.json` entries are the models' side): `engine` (looped, pitch by speed, volume by throttle), `start`, `horn`,
  `hit`, `destroyed`, `fire_<weapon id>`, `reload_<weapon id>` (tank: `fire_cannon`, `reload_cannon`, `fire_mg`,
  `reload_mg`). A key not listed is never played.

Whether vehicles may be used is the mode setting **`allowVehicles`** (backend `MovementSettings`, admin 允许载具,
default off; a missing one is "no") and outside matches the config `vehicles.outsideMatches` (default on). It gates
getting in and spawning. Spawning: the item `blockland:<id>` used on the ground (the boat: on water), facing the player.

## 3. Models (`models/vehicle/<id>.glb`)

Space and units as above; materials carry `pbr` / `region` extras as weapons do (VIEWMODEL_SPEC §6, §53, §57).
**The data is what the game runs on** (seats, muzzles, hit boxes, effect anchors): the GLB's nodes must sit at the same
points (within a couple of centimetres) — `python -I tools/vehicles/check_vehicles.py` compares them.

| Node | Vehicles | Meaning |
|---|---|---|
| `body` | all | root of the hull |
| `wheel_fl`, `wheel_fr`, `wheel_rl`, `wheel_rr` | offroad | pivot at the axle centre, spin about local X |
| `wheel_f`, `wheel_r` | motorcycle | same |
| `steer_fl`, `steer_fr` | offroad | steering knuckles: yaw about local Y (±30° at full steer); the front wheels are their children |
| `fork` → `handlebar` | motorcycle | the front fork turns about its steering axis (local Y); `wheel_f` is its child |
| `turret` | tank | yaw about Y at the turret ring centre (= weapon 0's `pivot`) |
| `cannon` | tank | pitch about X at the trunnions (= `pivot` + `pitchPivot`); child of `turret`; `muzzle_cannon` at the crown |
| `mg` | tank | the roof machine gun's yaw and pitch pivot (one node turns about both) on the turret; `muzzle_mg` |
| `track_l`, `track_r` | tank | track meshes; material `track` runs along v with its track's speed — its extras `scroll.vPerMetre` (20) say how far its v runs per metre of track (else the data's `movement.trackUvPerMetre`); `GpuModel.Style.uvScroll`, rendering spec §4 |
| `roadwheel_l<i>`, `roadwheel_r<i>`, `sprocket_l/r`, `idler_l/r` | tank | spin about X at their track's speed (the tracks differ while turning) |
| `propeller` | speedboat | spins about Z; child of `outboard` |
| `outboard` | speedboat | the motor: yaw about Y with the steer |
| `seat_<i>` | all | the rider's hips, one per seat in data order (a turret seat a child of `turret`) |
| `exhaust_<i>`, `headlight_<i>`, `taillight_<i>`, `wake` | optional | effect anchors (the data's `effects`) |

Animated parts are moved by the runtime from the vehicle's state; a pitch node's barrel kicks back along its own +Z by
its recoil. Until a vehicle's GLB exists its hull boxes are drawn as outlines.

## 4. Riding, control, physics

* Riders are vanilla passengers; the seats are synced occupant ids (`VehicleEntity.occupant(i)`), taken in order on use
  (right click; not while sneaking); the rider is placed with its hips at its seat (tilted with the hull), turns with
  the hull (and the turret on a turret seat) and may look `yawLimit` away from its front. Sneak to get out (beside the
  seat, else behind or in front).
* Keys: W/S throttle, A/D steer, space brake, sprint boost; H horn; riders working the vehicle (driver, gunners, anyone
  inside the hull) change seats with the number keys, everyone with J (the next free seat). Gunners: attack (held) fires,
  use (held) looks through the sight (the view from just ahead of the muzzle along the barrel, `zoom`×; the barrel
  follows the mouse at its turn rate), the guns' reload key reloads; empty magazines reload by themselves.
* **Movement is the server's.** The driver's client sends `C2SControl` (seq, throttle, steer, brake, boost) every tick;
  the server queues them on the vehicle (a backlog is cut short) and steps one per tick with `VehicleEntity.simulate`,
  then sends the driver `S2CState` (the seq it applied, position, yaw, velocity, tilt). The driver's own client runs the
  same `simulate` at once on its input (prediction) and, for each state, replays its newer inputs from the server's
  state (reconciliation; tiny corrections are smoothed). Everyone else interpolates (vanilla tracking, every tick).
  `getControllingPassenger` is null, so vanilla never moves a vehicle from a client.
* The step: thrust and braking along the front, the sideways slide cut by `grip`, steering by speed, gravity; boats float
  to their draft (only moving on water, dragging on land); a land vehicle in water deeper than `wadeDepth` stalls and
  sinks; the vanilla collision move with `stepHeight`; the hull's tilt from the ground under its corners (a bike leans
  into its turn, a boat lifts its bow at speed).
* Moving hulls hurt what they run into (speed over 4 blocks/s, by mass; damage type `blockland:vehicle_crash`, credited to
  the driver) and throw it aside. A vehicle falling more than 4 blocks wears its hull; riders feel only big falls.
* Anti-cheat: riders are passengers, which the movement checks leave alone (the vehicle's movement is the server's own
  step). Refused control packets (not the driver, values out of range) are `Suspicion.report`ed as `vehicle.control`,
  weapons fired well before their interval as `vehicle.fire_rate` (both in `data/blockland/anticheat/checks.json`);
  `packets.json` rate-limits `VehiclePackets.C2SControl` and `C2SAction`.

## 5. Drawing

`client/vehicle` draws the GLB through the `client/render` contracts like the ammo crate and the revive antenna (gun
pipeline, PBR, lit by `SceneLighting`, shadows through `ShadowPass` / `VanillaDraws`, headlights through `LightSources`).
Never through vanilla's entity model system except as the shadow pass needs. Each vehicle reports its pose and a box over
its hull and riders to `client/render/ObjectMotion`, so TAA, DLSS (Frame Generation, DLSS 5) and the motion blur move its
pixels by its own motion, turning included (rendering spec §11).
Seats with `camera: third` (inside a hull) switch to third person, pulled back to fit the vehicle; leaving the seat
restores the player's camera. Riders' bodies tip with the hull; riders inside a hull are not drawn.

## 6. Weapons and damage

* Aim: each weapon turns towards where its gunner looks, within its limits, at `turnRate` (the server's, synced; the
  local gunner turns its own at once). A gun on the turret turns with it.
* Fire: `C2SAction(FIRE)` → the server checks the seat, the interval and the magazine (`VehicleEntity.tryFire`) and fires
  (`VehicleWeapons`): a `shell` leaves the muzzle as a `VehicleShell` (flies and falls; never strikes its own vehicle or
  riders); what it strikes takes `damage` (damage type `blockland:vehicle_shell`, piercing armour: `RoundTraits`
  armorDamage 4), then the frag grenade's blast (`EquipmentEffects.detonate("frag")`) hurts bodies by `explosion` and
  the hulls round it take its `hullDamage` (below). A `hitscan` gun traces its round at once (blocks,
  then hit boxes; not its vehicle, riders or the gunner's allies), hurts what it struck as a bullet and sends `S2CShot`
  to everyone tracking the vehicle (tracer, impact, bleeding by `wounded`, the gunner's hit marker). Kills are credited to
  the gunner with the vehicle's item (`blockland:<id>`).
* Blasts that hurt bodies — the frag grenade's (thrown, launched, a shell's, a wreck's) and the bomb's — also tell
  `combat/Blasts`, which `VehicleEntity` answers as a `Blasts.Target`: its nearest hull box's distance gives the falloff
  (as for bodies), its armour halves it; a blast's `hullDamage` parameter (shells) replaces its body damage for hulls.
* `VehicleEntity.hurtServer` takes rounds, blades and blasts by `armor`; a creative player's punch removes the vehicle;
  riders cannot hurt their own. At 0 health it burns for 3 s (riders are thrown out), then explodes (the frag blast,
  credited to whoever hit it last) and is gone.
