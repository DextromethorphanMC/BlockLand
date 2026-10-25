# BlockLand render pipeline contract

How BlockLand renders a frame on top of Minecraft 1.21.8 (Blaze3D, OpenGL), and the contracts between its parts. The
code lives in `client/graphics/*` (the pipeline), `client/render/*` (shared contracts every feature may use) and the
lighting pack `mod/src/main/resources/resourcepacks/blockland_lighting/` (vanilla core-shader overrides). No mixins:
everything hangs on Forge events, a few reflected fields and the lighting pack.

Rules that hold everywhere:

* Minecraft's look stays: textures, block shapes and vanilla's lighting model (lightmap, face shading, smooth lighting)
  are the base; the pipeline adds sun shadows, sky/sun colour, dynamic lights, emission, reflections and camera effects.
  With everything off the frame is pixel-identical to vanilla.
* Minecraft is BlockLand's engine, not a platform for other render mods: there is no OptiFine / shader-pack path. Code
  may rely on BlockLand's pipeline being the one that renders (vanilla render types only serve the shadow map's entity
  capture, `ShadowPass`).
* Every feature degrades: a subsystem whose preconditions fail (a reflected field missing, a program that did not
  compile) switches itself off, says why in `GraphicsStatus`, and the rest keeps running.
* Visual verification is the user's (AGENTS.md); `tools/shaders/check_shaders.py` compiles every shader against the
  real driver, and must pass before a shader change is handed over.

## 1. Frame phases (`client/graphics/frame`)

`FrameHooks` drives the phases and calls every registered `FrameStage` in registration order (shadow, then light):

| Phase | When (hook) | What happens |
|---|---|---|
| `beginFrame` | `TickEvent.RenderTickEvent.Pre` | settings applied, jitter chosen (`FrameJitter`), Minecraft's main target swapped for the internal-resolution **scene target** |
| `cameraReady` | `ViewportEvent.ComputeCameraAngles` (MONITOR) | camera placed: shadow cascades rendered, scene uniforms written and bound (world lighting on) |
| (level) | vanilla | terrain, entities, particles, weather — lit by the lighting pack; third-person weapons drawn in place |
| `afterLevel` | Forge frame pass added last (`AddFramePassEvent`) | world depth copied to `FrameContext.worldDepth`; ambient occlusion and volumetric light; scene uniforms switched to the hand state (world lighting off, emission encoding on) |
| (hand) | vanilla + viewmodels | drawn into the scene target with the jittered hand / viewmodel projections |
| `beforeComposite` → post chain | the first HUD layer (root of `ForgeLayeredDraw`, also with F1) | decode → AA / upscale → motion blur (§11) → bloom → exposure → tone map and grade → sharpen / FXAA, written into Minecraft's real main target; main target swapped back; scene uniforms off |
| `endFrame` | `TickEvent.RenderTickEvent.Post` | statistics; if the composite did not run, the main target is swapped back anyway |

**World effects in the particle pass** (`client/render/WorldFx`): BlockLand's effects on and in front of the world are
drawn by one `CUSTOM` particle, layer by layer — `SURFACE` (bullet holes and other decals, graffiti: painted on the
faces) before `VOLUME` (impact dust and debris, explosion smoke and sparks) — so what floats in front of a face always
covers what is painted on it, whichever was spawned first. Modules register a drawer once and `wake` the pass when
something appears in a level; a drawer that fills vanilla's buffer source ends its own batches (`Decals`), or its draws
would only land at the end of the pass, over everything drawn there. Vanilla's particle engine can drop that particle
without removing it (a resource reload clears its lists — the 世界光照 switch reloads — and a full layer evicts its
oldest), so the pass notes when it last drew and is put back, every client tick and on every `wake`, once it has not drawn
for half a second; only the current pass draws.

A scope's picture in picture (`ScopePicture`) renders the level a second time inside the hand phase: `FrameHooks`
ignores the Forge pass while it runs, and `SceneLighting.extraWorldView(true/false)` around it lights it like the main
view.

Spaces: **camera-relative world** = world translated so the camera is at the origin (vanilla terrain and entities are
drawn in it); **view** = that rotated by `FrameContext.viewRotation`. The level projection is captured unjittered in
`FrameContext.projection`; the previous frame's matrices and camera position are kept for reprojection.

## 2. Scene uniforms (`blockland:scene.glsl`, `client/render/SceneLighting`)

One std140 uniform block `BlockLandScene` per frame (layout and helpers in
`assets/blockland/shaders/include/scene.glsl`; Java size in `SceneLighting.UBO_SIZE`). Helpers every shader uses:
`bl_shadow(pos, normal, fragCoord)`, `bl_pointLights(pos, normal)`, `bl_material(uv)` / `bl_materialParams(i)`,
`bl_emissionAlpha(e)`, `bl_linear` / `bl_display`, `bl_fresnel`.

* `BlFlags.x` world lighting on, `.y` emission encoding on, `.z` dynamic light count, `.w` time.
* Sun: `BlSunDir` (towards the light: sun by day, moon by night), `BlSunColor` (rgb linear, `a` = shadow strength),
  `BlAmbient` (shadow tint, `a` = skylight share left in full shadow), `BlSkyColor` (reflections, `a` = wetness).
* Camera: `BlViewRotation` (camera-relative world → view), `BlProjection` (level projection without jitter;
  `bl_viewPosition(uv, depth)` / `bl_worldFromView(v)` rebuild positions from a depth buffer), `BlCamera`.
* Shadows: `BlShadowMatrix[4]`, `BlShadowSplits`, `BlShadowParams` (see §6).
* Lights: `BlLights[2i]` (camera-relative position, radius), `BlLights[2i+1]` (linear colour × intensity).
* Materials: `BlMaterials` (sprite rects of the block atlas with emission / kind / reflectivity, §7).
* Light colours and sky (§14): `BlBlockLight` (rgb block light tint × strength, 1 = vanilla's; `a` 1 = the volumetric
  clouds replace vanilla's this frame), `BlFog` (haze extinction at the camera's height, height falloff, sun in-scatter,
  `w` 1 = on). `BlSunColor` already carries the player's sun / moon colour temperature.
* Wind (§18), last in the block: `BlWind` (heading, steady strength, the setting), `BlWindInfo` (counts), the tunables
  `BlWindBend`, `BlWindStack`, `BlWindPush`, `BlWindSpring`, `BlWindKinds`, then `BlGusts[]`, `BlPushers[]`, `BlSways[]`;
  the plant columns are a texture, `BlPlantColumns` on unit 15 (patched like the material map, read by terrain.vsh).

BlockLand's own pipelines declare `withUniform("BlockLandScene", UNIFORM_BUFFER)` and `withSampler("BlShadowMap")`
and bind `SceneLighting.uniforms()` / `SceneLighting.shadowMap()` on every pass. When nothing provides them (graphics
off, lobby) a zeroed block and a lit 1×1 shadow map stand in, and shaders take their non-world path.

## 3. The lighting pack and vanilla programs (`client/graphics/light`)

`resourcepacks/blockland_lighting/` (built-in client pack "BlockLand 光照", registered with `AddPackFindersEvent`, on by
default, toggled by the 画质 setting 世界光照 with a resource reload) overrides `minecraft:core/terrain`,
`minecraft:core/entity` and `minecraft:post/transparency`. After every shader reload the programs of every vanilla
pipeline using those shaders are patched (once per program):

* uniform block `BlockLandScene` → binding point **15** (`glUniformBlockBinding`); the frame's block is bound there with
  `glBindBufferBase` (Blaze3D only uses the low binding points, sequentially per program);
* sampler `BlShadowMap` → texture unit **12** (`glProgramUniform1i` or use-and-restore); the shadow atlas is bound on unit
  12 with raw GL and the active unit restored (Blaze3D's state cache tracks units 0..11 only);
* sampler `BlMaterialMap` → texture unit **13**: the block materials (§7) as one texel per atlas cell, read with
  `texelFetch` (a per-fragment search of the uniform block's rectangles would be slow, a per-vertex one is ambiguous at
  sprite edges; `BlMaterials` in the block stays filled for other readers);
* `uniform int BlRole` → the program's role: **0** unpatched (exactly vanilla), **1** opaque (solid / cutout terrain,
  non-blended entities: lit, emission encoded in alpha), **2** blended (lit, alpha untouched), **3** translucent terrain
  (water and glass shading), **4** the Fabulous transparency composite (water refraction and absorption, emission alpha
  carried through), **5** vanilla's clouds (`minecraft:core/rendertype_clouds`, only the fragment shader is overridden:
  discarded while `BlBlockLight.a` says the volumetric clouds replace them, §14). The role comes from the pipeline
  (shader + blend function), never from the shader source.

Programs that do not declare the block (another resource pack's shaders) are left unpatched, and the status says so.
`BlWater.x` is the wave steepness (1 = default). Sun direction and shadow strength have one source for lit faces and
shadows alike: `client/graphics/shadow/SunPath` (path tilted 25° to the south).

Unpatched (role 0) or with `BlFlags.x = 0`, every override computes exactly what vanilla's shader computes. If a
patched pipeline fails to compile after a reload, the pack switches itself off, reloads, and the status says why.

**Emission alpha.** Opaque draws (role 1 programs and BlockLand's opaque model pipelines) write
`alpha = 1 − 0.5·e` into the main target, `e` = emission 0..1, while `BlFlags.y` is on (world and hand phases). The
post chain decodes `e = 2·(1 − a)` where depth < 1 and boosts those pixels in HDR (bloom picks them up). The hand phase
keeps encoding on, so held items never fake glow from their texture alpha.

## 4. Resolution, jitter, anti-aliasing, upscaling (`client/graphics/frame`, `post`, `upscale`)

* Render scale: the world, hand and viewmodel are drawn into the scene target at the internal resolution (upscaler
  quality × window size); the HUD is drawn afterwards at the window's resolution.
* Jitter: Halton(2,3) sub-pixel offsets (`FrameJitter`, NDC). GameRenderer's level and hand projection buffers are
  replaced by jittering subclasses; `ViewmodelRenderer` applies `FrameJitter.apply` to its own projection. Off when no
  temporal pass runs.
* Texture detail: with a temporal pass the frame also carries a mip bias, log2(internal / output width)
  (`FrameJitter.mipBias`), so retained-model textures are sampled at the output's detail, as the upscalers expect;
  `Textures.syncSampling` (called by `DrawList` before its draws) applies it to every mipmapped texture, which are also
  filtered anisotropically (up to 16×). Vanilla's own textures keep vanilla's sampling.
* Upscalers (`upscale.Upscaler`): none (native), TAAU (built-in temporal upscaler: reprojection from depth and camera
  motion, YCoCg neighbourhood clamping, viewmodel pixels reprojected by their own motion), DLSS Super Resolution and
  DLAA (through `upscale/dlss/DlssBridge` on the session's `upscale/dlss/SlRuntime`, held by `upscale/StreamlineHost`:
  Streamline on a Vulkan device of the same GPU, textures shared with OpenGL;
  quality modes Quality / Balanced / Performance / Ultra Performance, DLAA = native; model preset default or J / K / L / M
  for every mode; sharpening after DLSS is BlockLand's contrast-adaptive pass with its own strength, DLSS's own being
  retired; any failure switches it off for the session and TAAU takes over the same frame, the status says why).
  Native-resolution AA: off, FXAA, TAA (TAAU at scale 1).
* Ray tracing (`raytrace/RayTracing`, a frame stage after the level; settings: on/off, quality, distance, GI, AO,
  emission, sun bounce, denoiser): `raytrace/VoxelWorld` keeps the blocks around the camera (up to 16×8×16 chunk
  sections) as voxels in two RGBA8 atlases, one 64×64 tile per section (block (x, y, z) at (x + 16 (y mod 4),
  z + 16 (y div 4))), tiles addressed toroidally by section coordinate modulo the box, filled nearest first and refreshed
  round-robin within 2.5 ms per frame: colour atlas = the block's average sprite colour (tinted blocks: the top face's
  sprite × the biome tint) and its kind (0 nothing a ray stops at, 1 full cube, between: hit chance for leaves, partial
  blocks, plants — `raytrace/BlockVoxels`); light atlas = Minecraft's own sky / block light per cell and the block's
  emission. Passes (`rt_*`, `rt_common.glsl`, block `RtInfo`): guide (world normal from depth, linear depth) and albedo
  (the voxel's colour, else the scene's) at the internal resolution; rays at the quality's resolution — cosine-weighted
  DDA marches; a hit face shines with its colour lit by the light of the cell in front of it (Minecraft's brightness curve,
  sky light by the daylight), its own emission, and with sun bounce direct sun if a shadow ray reaches the sun; misses
  add nothing (the sky's light is in Minecraft's lighting already); occlusion within 3 blocks. Ray directions come from
  the R2 sequence over frames and rays, shifted per pixel by interleaved gradient noise (`rt_sample`: accumulation
  converges fast and what noise is left is fine-grained, never blotchy); the hit chance of leaves and partial blocks stays
  random. Then BlockLand's denoiser (SVGF-style: temporal accumulation by reprojection with linear-depth rejection, 32
  frames at most, carrying the light's first and second luminance moments; four edge-avoiding à-trous passes, 1/2/4/8 px,
  weighted by depth, normal and luminance against the pixel's variance — boosted while its history is short — the first
  pass written back as the next frame's history) or none when DLSS Ray Reconstruction denoises; a joint-bilateral resolve
  to the internal resolution × the plain albedo (the voxel's colour) × strength, faded out (light and occlusion) within
  0.35 × the ray length of the voxel box's faces, where rays leave the box early. A ray's light is capped at 3 per channel
  and a sample above the pixel's settled light (mean + 3σ of its moments, after 4 frames) is pulled down to it (fireflies);
  the history is read from its four nearest texels, each kept only where it saw the same surface (no leaking across
  silhouettes). When the box follows the camera into new sections, the tiles still holding sections from its far
  side are emptied at once (whatever the fill budget), so rays never meet phantom blocks there (blinking shadows); the
  fade is measured from the camera (the reach the box always has around it), not from the box's faces, so it does not
  jump when the box moves. The traced block light takes the
  player's block light colour (`RtBlockLight`), the sun bounce the sun's. Decode adds the light and multiplies by the traced occlusion, which replaces the screen-space one (that pass
  is skipped). Verified against a CPU march by `tools/raytrace/check_voxels.py`.
* DLSS Ray Reconstruction (Streamline feature 1001, `sl.dlss_d` + `nvngx_dlssd`): with ray tracing on, DLSS Super
  Resolution or DLAA chosen and RR as the denoiser, the rays run at the internal resolution without BlockLand's denoiser
  and DLSS evaluates RR instead of SR on the same viewport: the colour carries the noisy traced light, with three more
  tags — diffuse albedo (its own texture: the scene's colour floored by 0.15 × the voxel albedo, so its texture detail
  is never taken for noise and dark corners are not divided by almost nothing; the resolve keeps the plain albedo),
  specular albedo 0.04, world normals with roughness 1 packed in alpha — and `DLSSDOptions` (mode, output size, HDR,
  packed roughness, world ↔ view matrices) each frame. `tools/dlss/check_dlss.py --rr` runs it on a noisy input.
* DLSS 5 3D-guided neural rendering (`upscale/NeuralRenderer` around `upscale/dlss/NeuralRendering`, a `PostChain.Relight`
  right after the upscaler — output size, before motion blur, bloom and tone mapping, where RTX Remix runs it): NGX called
  directly (`upscale/dlss/Ngx`: the driver's `_nvngx.dll` exports the Vulkan entry points as C functions; the parameter
  block's C++ vtable is called by slot, the MSVC layout confirmed by typed round trips at start) on the Streamline
  runtime's device — NGX keeps one context per device and Super Resolution keeps running beside it. DLSS-NR's device
  extensions are asked from NGX before the device is created when `nvngx_dlssnr.dll` is installed. Per frame: the HDR
  picture × the manual exposure goes into SDR through `gfx_sdr` (identity below 0.8, an exponential shoulder above; the
  model works in SDR), the depth is the scene's after the hand phase (`gfx_nr_depth`: the hand and the weapon keep their
  shape, unlike Super Resolution's depth where they are flattened to 0), the motion vectors come from the Super Resolution
  pass at the render size, the control
  mask is uniform (R overall, G tone, B structure = 1), the model runs with the player's intensity, structure, tone,
  model A / B / C, automatic character mask and character strength (RTX Remix's options and defaults: 1, 0.7, 0.3, A, on,
  0.5; network preset B), and `gfx_sdr_inverse` brings it back with RTX Remix's highlight recovery (original HDR where
  it was exposed past 1..8 and the result sits in the shoulder 0.75..0.99). Needs an RTX 50 GPU, a driver with DLSS-NR
  (616.92+; older drivers do not know its capability keys at all) and the model from NVIDIA's DLSS-NR SDK package
  (`install_runtime.py --dlssnr`); any failure switches it off for the session with the reason on the 画质 tab. Checked
  outside the game with `tools/dlss/check_dlss.py --neural`.
* DLSS Frame Generation / Multi Frame Generation (`upscale/FrameGenerator` around `upscale/dlss/FrameGeneration`, any
  upscaler, 2× … 6× capped at what the GPU reports): Streamline inserts the generated frames at present, so while it is on
  the frames reach the screen through a Vulkan swapchain created through Streamline's hooks in a child window over the
  game window (a STATIC control: hit tests pass through, it never takes the focus, so input stays with GLFW's window);
  OpenGL still swaps its covered back buffer, with v-sync off. The runtime is brought up once per session, with DLSS-G,
  Reflex and PCL whenever their files are there, and never shut down before the game exits (Streamline's proxy crashes in
  vkCreateDevice when it is brought up again after Frame Generation was loaded; presenters come and go on it). Per frame: frame start — PCL simulation end / render submit start; before the
  HUD (after the composite) — the frame is copied as the HUD-less picture and the window target's alpha is cleared, so
  the HUD's blending leaves its coverage in alpha; frame end (`RenderTickEvent.Post`, before Minecraft's blit) — the
  frame, the HUD-less picture (the frame where alpha is 0, so coverage-less overlays such as the vanilla vignette stay
  scene; the copy under the HUD), the HUD alone premultiplied (frame − (1 − a) × copy, a) and, at the render size,
  depth and motion vectors drawn by the Super Resolution passes go into shared textures (rows flipped); the frame is
  blitted into the swapchain image, the four inputs are tagged `eOnlyValidNow` (Streamline copies them; UI as colour and
  alpha, since Streamline cannot copy single-channel 8-bit images), the frame's constants are set once (shared with
  Super Resolution), and the swapchain presents between the PCL present markers; Reflex sleeps before the next frame's
  simulation. Pause menus, loading screens and frames without a world present without generation (resources retained);
  generation pauses by itself while no window of the process is in front. V-sync does not apply (DLSS-G supports it on
  Direct3D 12 only; present mode immediate / mailbox), motion blur is halved. Any failure switches Frame Generation off
  for the session and OpenGL presents again. Checked outside the game with `tools/dlss/check_dlss.py --framegen`.
* DLSS inputs (`gfx_dlss_*`): the decoded HDR colour, hardware depth (hand / viewmodel = 0) and motion vectors drawn
  straight into the shared textures with their rows flipped (DLSS's textures run top to bottom); jitter (δx, −δy) and
  motion (screen x, −screen y) in render pixels, conventions measured with `tools/dlss/check_dlss.py`. The runtime is
  NVIDIA's and installed per game directory (`tools/dlss/install_runtime.py` → `<game>/blockland/native/streamline`).
* Motion vectors: world pixels — camera motion reprojected from the world depth; viewmodel pixels (hand depth < 1 after
  the hand phase) — their own motion from `client/render/HandMotion`: the viewmodel's `DrawList.motion(projection)` draws
  its opaque parts a second time into an RG16F velocity texture (uv per frame, current − previous, from this and the last
  flush's node matrices, depth-tested against its own depth). The temporal upscalers and DLSS reproject the weapon with
  it, and motion blur (§11) smears the weapon and arms by it with their own shutter (手臂与武器运动模糊); the weapon is
  always in front, so the world never smears into it.
  Skinned arms (asset spec §7, `client/viewmodel/ArmSkin`) are skinned on the CPU into per-rig vertex buffers and drawn
  with their anchor joint's model-view (`GpuModel.addMesh` with a vertex buffer of its own; a buffer per mesh and draw
  until the list flushes), so their motion is the anchor's: only the change of the elbow / wrist bend between two
  frames is not in the motion vectors.

## 5. Dynamic lights (`client/render/LightSources`)

Features add `LightSources.flash(...)` (muzzle flashes, blasts) and `LightSources.attach(supplier)` (fires). The light
module adds held light items (`assets/blockland/graphics/item_lights.json`: item id → colour, intensity, radius) of
every player and mob in range, picks the `MAX_LIGHTS` (32) strongest near the camera, and writes them camera-relative.
`LightSources.isAttached(supplier)` tells a feature whether its attached light is still there (it goes when the supplier
returns null and when leaving a world), so a light that comes and goes (a special skin's glow, asset spec §38) is
attached again only when it is gone.

## 6. Shadows (`client/graphics/shadow`)

Cascaded sun shadow map: one depth atlas of 2×2 tiles (`ShadowQuality.tile` texels each), up to 4 cascades split
logarithmically up to `shadowDistance`, texel-snapped orthographic boxes around the camera, the sun path tilted so noon
shadows are not axis-aligned. Casters: terrain sections in range (solid, cutout, cutout-mipped; alpha-tested), entities
near the camera; slope-scaled depth bias at render time, normal offset at lookup. Far cascades may update every other
frame. `ShadowInfo` hands the atlas and matrices to the uniforms and the volumetric light.

The sun's time of day is interpolated between ticks only while the day clock runs (`SunPath.timeOfDay`: the client
level's `tickDayTime` and an unfrozen tick rate); a stopped clock keeps the sun where it is. Two exact savings (§20):
casters are drawn nearest the sun first (`SHADOW_SORTING`: every texel ends at the least depth covering it, whatever the
order), and a cascade's terrain depth is kept in a texture of the atlas's size (`TerrainCache`, `SHADOW_REUSE`) while its
placement, the light, the camera position, the meshes of the sections reaching it, the block atlas and the client tick are
unchanged — the kept tile is copied in and the entities drawn over it each frame (the same least depths). Only frames
whose camera and light held still since the last one take that path; moving, the cascades are drawn as before.

Entities are captured by running their own renderers into a capturing buffer source; while that runs
`client/render/ShadowPass.active()` is true, and renderers that would draw with their own pipeline (the third-person
weapons) take their vanilla-render-type path instead, so they are captured and cast shadows like everything else.
The local player casts their whole shadow in first person too, head included. The first-person weapon (`gun.fsh`,
within 1.6 blocks of the eye) looks its shadow up 1 block towards the light, past the player's own body and the head
round the eye, so they shade the world but not what the player holds in front of their face.

## 7. Block materials (`assets/blockland/graphics/block_materials.json`)

Data, not code: block texture → material, resolved to sprite rects of the block atlas after every atlas stitch.

```json
{ "materials": [
  { "textures": ["minecraft:block/glowstone", "minecraft:block/sea_lantern"], "emission": 1.0 },
  { "textures": ["minecraft:block/torch"], "emission": 1.0, "threshold": 0.72 },
  { "textures": ["minecraft:block/water_still", "minecraft:block/water_flow"], "kind": "water", "reflectivity": 1.0 },
  { "textures": ["minecraft:block/iron_block", "minecraft:block/gold_block"], "kind": "metal", "reflectivity": 0.6 }
] }
```

`emission` 0..1; `threshold` = the texel's brightest channel from which it glows (only the flame of a torch; the
brightest channel, not luminance, so saturated reds and blues glow too); `kind` one of
`plain`, `water`, `glass`, `metal`; `reflectivity` 0..1. At most 96 sprites (`SceneLighting.MAX_MATERIALS`).
An entry may carry `"wind": {"mode": "bend" | "sway" | "leaves", "flex": 0.5..2, "upper": bool, "water": bool}` (§18);
an entry with only `wind` sets no material. In the material map's texel, B packs the kind (bits 0–1) and the wind
(bits 2–3 mode, 4 upper half, 5 water plant, 6–7 flex level: 0.5 / 1 / 1.5 / 2).

## 8. Weapons

The viewmodel and third-person weapons use the `blockland:core/gun` pipeline (`GunPipelines`): with world lighting on
they are lit by the sun (with the shadow at the weapon's world position), the sky, and the dynamic lights, on top of a
dimmed studio rig that keeps them readable; emissive parts and skin effects write emission alpha. The gate is
`SceneLighting.weaponsLit()` (world and hand phases, setting 武器受环境光照): in the hand phase BlockLand's pipelines still
get the world block while binding 15 serves vanilla's held items the hand block, so the viewmodel is lit by its own
muzzle flash and stands in the same shadow as the player. Third-person weapons
are drawn by the `blockland:weapon` special item model (asset spec §37); while the shadow map captures entities they go
through vanilla render types (`VanillaDraws`), so they cast shadows.

A skin effect's **surge** (asset spec §38: a special skin's inspect or kill sets its shader effects ablaze) is a style
value (`GpuModel.Style.surge()`, 0..1) that `DrawList` packs into the effect id's fraction (`effect + 0.98 × surge`;
gun.fsh: the floor is the effect, the fraction the surge), so it needs no uniform of its own; at 0 every effect is
exactly as before. **GPU particles** (`client/render/GpuParticles`, pipeline `blockland:core/gun_particle`) are
stateless: one draw per emitter of a static mesh of seed-only quads; the vertex shader works out each particle's
birth, path (speed, drag, view-space gravity, orbit, gather, turbulence), size, streak and colour from the seeds, the
emitter's age and sixteen numbers in TextureMat (`DrawList.addRaw`). Additive, fullbright, drawn with the blended
draws (no motion vectors, nothing for the vanilla path).

## 9. Settings (`client/graphics/GraphicsSettings`, the 画质 tab)

Presets 低 / 中 / 高 / 极高 / 自定义 fill in the values; any change makes the preset 自定义. Cheap changes apply live
(`Graphics.settingsChanged`); the world lighting switch reloads resources when the settings screen closes
(`Graphics.settingsCommitted`). Light colours (阳光色温, 月光色温, 方块光源色温 −1 cool … +1 warm, 方块光源亮度 0..2) are
temperature tints of luminance 1 (`light/LightColors`) used by the scene uniforms, the ray tracer and the clouds; 迷雾,
薄雾浓度, 体积云, 云层质量 and 云量 are §14's.

## 10. The bomb explosion (`client/bomb/fx`)

The 枪战爆破 bomb's explosion is a client-only effect (`BombBlastFx.play(level, at, radius, shake)`; the server deals
the damage and never breaks blocks). `BombBlast` stages it over about 12 s, scaled by the blast radius (16 blocks = 1):
a flash (0–0.3 s), some thirty puffs of fireball gas thrown out and swelling in ~0.1 s, cooling from white-yellow through
orange and red to black smoke within ~1.5 s and rolling up into a mushroom (a column and a cap whose puffs turn over in a
torus), a floor dust cloud and a dust ring pushed out by the shock, sparks (simulated per tick, bouncing off blocks) and
embers floating in the updraft. Around it: vanilla block particles of the floor (debris), two `LightSources.flash`es
(white flash, lingering orange fire), `scorch` decals over a wide patch and `BombShake` (springs + rumble, the shock
arriving later with distance, FOV punch, scaled by 镜头冲击强度).

**Drawing.** The world effects pass (§1, layer `VOLUME`) calls `BombFxRenderer.draw` inside the particle pass, which draws right away into the
frame's target (honouring `RenderSystem.output*TextureOverride`, like the third-person weapons): one vertex buffer per
frame in vanilla's particle layout (`Position, UV0, Color, UV2`, quads built on the CPU), depth tested (LEQUAL) against
the world, never writing depth, no culling. Per-draw parameters ride in `DynamicTransforms` (`bomb_fx.glsl`: camera
axes, time, soft-depth flag, mode, and the fireball's light on its own smoke). Order and pipelines:

| Draw | Pipeline | Blend | What |
|---|---|---|---|
| ring, then puffs back to front | `blockland:core/bomb_smoke` | premultiplied (`ONE, ONE_MINUS_SRC_ALPHA`) | each puff a sphere impostor: thickness × world-anchored fbm gives density and curled edges; lit by the lightmap at the puff, the sun (`BlSunDir`/`BlSunColor`, the puff's shadow looked up once per puff in the vertex shader), `bl_pointLights` and the fire inside; hot gas emits blackbody colour |
| hot puffs again | `bomb_smoke` + `GLOW_PASS` | none, alpha write only | writes `bl_emissionAlpha` of the hot gas (§3) so the post chain blooms the fireball; overwrite rather than multiply, so overlapping puffs never fall below 0.5 (= no emission) |
| sparks, glow discs | `blockland:core/bomb_spark` | `ONE, ONE` colour, alpha × (1 − a) | additive streaks (stretched along their motion) and round glows (flash, fire glow); at most 0.3 of alpha per spark |

Soft edges: while an explosion is live the target's depth (terrain and entities so far) is copied once per frame into a
texture of the same size and format (`DepthSampler`, read with `texelFetch`); a puff fades by how far the scene lies
behind its front, and fades out before the camera gets inside it (no full-screen overdraw). Without a copyable depth
(no `USAGE_COPY_SRC`) a far-plane stand-in disables the fade. With graphics off (`SceneLighting` stand-ins) the smoke is
lit by the lightmap only and nothing writes emission alpha. Programs: `bomb_smoke`, `bomb_smoke_glow`, `bomb_spark` in
`tools/shaders/programs.txt`.

## 11. Motion blur (`client/graphics/post/MotionBlur`, in the post chain after the upscaler / relighting)

A real shutter, not a share of one frame: settings `motionBlur` (world: camera and entities) and `motionBlurViewmodel`
(the first-person weapon and arms) are shutter angles, 0..1 = 0..360° against a 30 fps reference
(`GraphicsSettings.FULL_SHUTTER_SECONDS`: 1 = 1/30 s, the default 0.5 = 180° = 1/60 s), so the smear is the same at any
frame rate; with Frame Generation it is halved. `motionBlurQuality` low / medium / high = 8 / 16 / 24 taps. Older files
held per-frame strengths (`motionBlurModel` < 2): off stays off, anything else becomes at least 180°.

Four full-screen passes at the output resolution (McGuire et al. 2012, "A reconstruction filter for plausible motion
blur", with Jimenez 2014's alternating tap directions):

1. `gfx_mb_velocity` → RGBA16F: rg = half the smear in output pixels (the per-frame screen motion × exposure / frame
   time, clamped to the tile size), b = distance from the camera (sky 1e4, the weapon 0.02), a = 1 on weapon pixels.
   World pixels: depth → camera-relative position (`InvCurrent`), moved back by the motion of the entity whose box holds
   it, → previous clip (`PrevFromCurrent`, the camera's translation included). Entity motion: the 32 nearest entities
   (≤ 96 blocks, not the camera entity in first person, not invisible) that moved since the last rendered frame, their
   bounding boxes (camera-relative, inflated 0.12 / 0.06) and the difference of where they were drawn this frame and last
   frame (interpolated positions, so per rendered frame at any frame rate; a jump > 8 blocks is a cut). Rigid motion per
   box: limbs swinging inside a walking body are not separate. Weapon pixels: the hand motion texture (§4).
2. `gfx_mb_tile` → the longest blur per tile (tile = longest radius = 5 % of the output height, 8–96 px).
3. `gfx_mb_neighbor` → the longest blur of each tile and its 8 neighbours.
4. `gfx_motion_blur` (gather): taps along the neighbourhood's blur (odd taps along the pixel's own direction), each
   weighted by depth (a tap in front counts where its own blur reaches the pixel, a tap behind where the pixel's blur
   reaches it, equal blurs count both ways), so moving things smear over what is behind them and silhouettes and the
   weapon keep no halo. Alpha (the weapon marker) is the centre's.

Skipped (and the entity history dropped) without the world depth, on a camera cut or when both shutters are 0. The DLSS /
TAA motion vectors still carry only the camera's and the weapon's motion.

## 12. Bullet impacts (`client/impact`)

What a round or a blade throws out of whatever it strikes, client-only and purely visual (no vanilla particles).
Weapons only call the facade `Impacts` with the hit and their ammo id (`guns.json` ammo: `pistol`, `smg`, `rifle`,
`sniper`, `shotgun`) or their attack's impact profile (`knives.json` `impactProfile`: `slash`, `stab`):
`block(level, at, face, pos, dir, ammo[, energy])`, `entity(level, at, entity, dir, ammo[, energy])` (`energy` 0..1: what
the round has left after water, see *Rounds in fluids*), `fluid(level, at, fluidState, pos, dir, ammo)`,
`path(level, Ballistics.Path, stop, ammo)` (the round's way through fluids up to where it stopped), `slash(level, at, face,
pos, motion, profile)` / `slashEntity(level, at, entity, from, motion, profile)` (a blade; `motion` = the cut, a little
forward), `drip(level, at, motion)` (water falling off something soaked) and `entityAt(level, end, shooter)` (for shots
reported without their target). The gun module calls them from its local trace and for other players' shots (their
direction from `BodyActions.eyePosition` of the shooter, or straight into the struck face when the shooter is out of sight),
the knife module from its strike and other players' impacts; decals stay the weapon's (`Decals.onBlock` with the ammo's
decal, `Decals.onEntity` with its `bodyDecal`, `bullet_wound`, for rounds that still had a third of their energy; body
decals lie on the cube of the drawn model and follow its animation, `client/render/EntityPoses`, asset spec §14).

**Data** (`assets/blockland/impacts.json`, Gson, `client/impact/ImpactData`; speeds blocks/s, sizes blocks, times s):

* `materials.<id>`: `debris` {`count`, `shapes` (`chip` two crossed faces, `flake`, `splinter`, `shard` triangle,
  `grain`, `leaf`, `droplet`), `size`/`speed`/`life` ranges, `spread` (cone half-angle °), `gravity` (× 0.04 b/tick²),
  `drag`, `restitution`, `friction`, `spin` (°/s), `wind` 0..1}, `dust` (one cloud per hit, see below) {`count` (mass of
  powder without chunks), `perDebris` (mass per chunk), `friability` 0..1, `size` [radius just after the burst, radius at
  the end] for a mass of 1, `speed`, `life` (s; a puff is short: about a second for stone and concrete), `rise`, `settle` (sinking of the heavy part), `wind`, `opacity` (optical density
  scale), `trail` (share of the chunks shedding dust in flight), `landing` (mass of the poof a chunk raises where it
  lands)}, `sparks` {`count`, `speed`, `life`, `ricochet` 0..1, `spread`, `gravity`, `bounce`, `color`}, `debris.density` (relative to water: sinks above 1, floats below), `splash` (fluids)
  {`kind` `water` | `lava`, `sheet` / `column` (heights for energy 1), `drops`, `dropSpeed`, `ripple` (ring speed),
  `rippleLife`, `foam` 0..1}, `blockTexture`
  (debris = a random quarter of the struck block's particle sprite, tinted by its block colour) or `sprite` + `color`,
  `blockDustColor` (dust from the block's map colour), `sound` (`block` = its hit sound, a sound id, or empty),
  `soundVolume`, `flash` / `flashColor` (a `LightSources` flash, e.g. sparks in the dark). Counts may be fractional:
  they are rounded at random.
* `blocks` / `entities` / `fluids`: ordered rules, first match wins. Blocks `id:`, `tag:`, `sound:<SoundType field name>`
  (e.g. `sound:metal`), else `fallbackBlock`; entities `id:`, `vehicle`, `armored` (armour ≥ 10), `living`, `any`, else
  `fallbackEntity`; fluids `id:` / `tag:`. Answers are cached per block state / fluid and dropped on reload and logout.
* `ammo.<id>` {`energy` (speeds), `count`, `size`, `dust`, `sparks`, `blade` (thrown along the cut, no exit wound),
  `thrust` (a stab: back out along the blade)}, `fallbackAmmo`. Blade profiles: `slash`, `slash_heavy`, `stab`.
* `environment` {`wetDust`, `wetDarken`, `wetSplash` (rain on the struck surface: less, darker dust and muddy drops),
  `snowDust` (snowing biome: more, whiter powder), `waterDrag` and `bubbles` (under water: debris sinks slowly, bubbles
  rise instead of dust), `wind`, `rainWind`, `thunderWind` (a slowly turning, gusting wind that carries dust and light
  debris), `rainSparks` (wet surface: sparks' count and life ×), `wetBounce` (wet debris' restitution ×), `current`
  (blocks/s a fluid's flow carries what is in it), `bubbleRise` [smallest, largest] (blocks/s), `trailBubbles` (per block
  of a round's path under water)}.

**Shaping.** The spray leaves in a cone round `normal·(1 − 0.85·g) + reflection·0.85·g` (`g` = 1 − cos of the angle
of incidence): square hits burst out round the surface normal, glancing ones streak along the surface towards the
ricochet; the cone is drawn out downrange — along the round's path over the surface, i.e. the shooter's yaw and pitch —
by up to 1.2·g (an oblique hit's butterfly-shaped spray), and a share (22 % × cos) of a square hit's chunks is blown
straight back out of the hole along the round's path, a little faster. Debris speed × (0.55 + 0.45·cos), sparks lean to
the ricochet by `ricochet` and get more numerous the more glancing the hit. Under water the chunks leave at 0.3× the
speed with 0.6× the spread, sink and drag (`waterDrag`), and the round's cavity lets out more bubbles the harder it is.
Nothing is ever sent into the surface.

**Dust is made by the debris.** A hit's powder is one cloud whose mass is `(count + perDebris × debris.count × ammo.count)
× friability × ammo.dust × (0.6 + 0.4·energy)` (× `wetDust` on wet ground, × `snowDust` in snow; none under water), taken
from the material thrown out, not from the drawn count, so quality never thins it. The mass sets everything: the radius
grows with ∛mass (a burst within ~0.1 s, then a slow √t spread to `size[1]·∛mass`), the core's density with ∛mass too and
it hangs longer; it is densest in the first tenth of a second, dilutes as it spreads (∝ (r_burst/r)^1.8) and settles out
all along ((1 − t)^1.8: most of it is gone by half its life, a thin haze is the last of it). Right after the hit the
cloud is a jet drawn out along its throw (up to 1 + 1.3× its radius for a square, fast throw, a low smear for a glancing
one) that rounds off within ~0.4 s; as it ages its lower half hangs lower (the heavy powder sinking, more with
`settle`). A few chips of wood leave a faint wisp; a rifle round into concrete leaves a thick cloud with a dense core, a sniper
round a large lingering one. Dusty chunks shed small puffs in flight (`trail`) and raise a poof where they land
(`landing`); those small clouds only take free slots and never push out a hit's own cloud.

**Simulation** (`ImpactParticles`): fixed pools, never allocating while playing — debris and sparks (5000 each; a pool
at its quality budget reuses its oldest piece), 128 dust clouds, 96 splashes — ticked with the client. Debris flies with
gravity, drag and wind, tumbles (three angles), and on the second collision bounces axis by axis off the collision shape
it runs into (`restitution` against the surface, `friction` along it) until it is slow enough to lie flat; a landed drop
becomes a splat on the surface (blood stays for its life). A cloud's centre is thrown out along the spray, slows within a
few ticks, rises (`rise`, fading with age) or sinks (`settle`, growing with age), drifts with the wind and slides along
walls and floors. Sparks bounce once. With second collisions off, pieces vanish where they touch a block.

**Wet, snowy, under water.** On a wet surface (rain on it) the dust is `wetDust` of itself and darker, chunks clump
(restitution × `wetBounce`, no dust trails), muddy drops fly up with them, and sparks are fewer and short-lived
(`rainSparks`). Under water no sparks fly (a few bubbles hiss off metal instead), no flash, and the hit sounds a dull,
low knock (also when the listener's ear is under water).

**Rounds in fluids** (`combat/Ballistics`, shared by the server's `GunService` and the shooter's prediction, so both stop
a round at the same place): air does not slow a round over a gun's range; water and lava do. Along the path the round's
energy (1 as fired) falls linearly with the fluid it goes through — `1 / waterRange` per block of water, `1 / lavaRange` per
block of lava (`guns.json` ammo, defaults 2.5 and 0.3; 0 = that fluid does nothing) — and it is spent where it reaches 0;
a round meeting a level water surface from the air below `skipAngle` (default 7°) to it skips off and goes no further.
The path walks the voxels (Amanatides–Woo), each fluid block being its fluid's box up to its height, so a round passes
over a thin sheet of water and through the depth of a pool. The server only looks for targets up to where the round
stops and scales the damage by the energy left at the hit, and tells the others how a round that hit nothing ended
(`S2CShot.face`: the struck face, `SKIPPED` -2 or `SPENT` -3): their clients re-trace the path from the shooter's eyes for
the splashes and the trail, but take the server's word for the stop (`Impacts.stopped` where their own trace comes out a
hair different) and never look for a body at the end of such a round. The client's `Impacts.path` shows the way: a splash where it
goes into water from the air (scaled by its energy there), a skip — a low spray thrown forward along its way, rings and a
slap — where it skips, a torn spray where it goes into falling water from the side, a trail of bubbles from its cavity
along its path under water (dense at the entry, thinning as it slows), a spout where it leaves water upwards, and a spent
round's last bubbles and its slug sinking, tumbling, to the bottom. Blocks and bodies struck after water are struck with
the energy left (weaker pieces, no decal under a third of it). `python -I tools/combat/check_ballistics.py` checks the
walk against a fine march and the hand-worked cases (spent, energy halfway, skip, entries, under water, lava, shallow).

**Fluid physics** (setting 流体物理): water is ~800 times denser than air, so under water a piece reaches its terminal
speed within a tick or two — drag `0.35·√(0.03/size)` per tick, at least `waterDrag` — and sinks under its weight less its
buoyancy (`1 − 1/density`), or rises (lighter than water, at 0.4 of that) and floats at the surface, bobbing; the current
(vanilla's flow vector × `current`, refreshed every four ticks) carries it, its speed relaxing to the water's. A chunk
falling into water from the air throws a small splash and rings where it went in (free splash slots only) and loses two
thirds of its speed; one falling into lava burns up in a wisp of smoke (wood and leaves with a flame). Sparks die in
water. Bubbles rise at their size's speed (`bubbleRise`), wobbling and carried by the current, and the larger ones leave
a faint ring where they burst. Spent cases (`GunParticles.Shell`) splash in (none when ejected under water), sink
slowly, tumbling less, and settle without a tink. With fluid physics off, pieces only fall more slowly in water (the old behaviour).

**Blades** (`slash`, `slashEntity`): no crater — the chips leave along the cut, low over the surface and strung out along
the gouge (a thrust throws them back out of the hole), a little dust, sparks raked off metal and stone along the cut
(blade profile `sparks`). A cut into a living target flings blood off the blade along the cut and a little out of the
body (cast-off, `castOff` drops at `castSpeed`), a stab back out along the blade, a small mist; a plume under water.

**Fluids** are their own thing, shaded like the water (`impact_fluid`). Water: a splash event drawn from its age alone —
the crown sheet rising round the hole for ~0.3·√E s and falling back (16 × 2 quads, flaring out, its rim ragged and
breaking up), the column (Worthington jet, 8 × 2 quads) shooting up after 0.09 s for rounds above energy 0.6, three ring
crests spreading at `ripple` blocks/s on the surface plane for `rippleLife`, and white water over heavy rounds (energy
above 0.9) with bubbles rising under it; drops thrown from the crown's rim and the column's top fly ballistically and,
falling back below the water's surface, make a small secondary splash (crown and rings, no column); on dry ground they are
gone. Energy E = ammo energy × (0.75 + 0.25 × ammo size). Lava: molten spatter (opaque, glowing by its temperature,
cooling over its life and crusting dark, lying as a splat where it lands, gone back into the pool), a glowing crater
spreading on the surface, and the material's embers and smoke.

**Blood** (`materials.<id>.blood`: `backDrops`, `exitDrops`, `backSpeed`, `exitSpeed`, `backSpread`, `exitSpread`,
`dropSize`, `splatLife`, `backMist`, `exitMist`, `mistSize`, `mistLife`, `mistOpacity`, `mistColor`, `plume`,
`plumeSize`, `plumeLife`, `plumeOpacity`, `plumeColor`, `splashTint`, `fineDrops`, `fineSpeed`, `fineSize`,
`headMultiplier`, `runs`, `dryTime`, `rainWash`, `castOff`, `castSpeed`, `bleed`, `bleedRate`; settings 血液效果 0..2, 水中血液扩散 and 血迹停留时间
0..2). The wound's geometry is the struck body part's (`HitShapes.part`: a sliding player's body or head box, an upright
entity's bounding box — only its head band for a head hit): the entry's side (`faceNormal`) and where the round leaves
that part along its path (`exit`). A living target's material with `blood` replaces debris and dust with: drops sprayed
back out of the entry towards the shooter (between the reversed round and the struck side), a larger, faster spray out
of the exit along the round (× energy), the fine aerosol — `fineDrops` tiny, fast drops (two thirds out of the exit) in
a narrow cone, braked hard by the air within a few ticks: the red burst of the first tenth of a second — and a mist
cloud at both (the dust cloud machinery: entry `backMist`, exit `exitMist` × energy of mass, drawn out along their throw,
hanging about a second and sinking a little). A head hit throws `headMultiplier` times as much and a larger mist. A `radial` share (0.35) of each spray bursts out
sideways round the round's line instead, 60° to 95° off it — up, down, left, right, the crown a wound throws — and the
sprays are fast and braked by the air (back 2.5–6.5, exit 4.5–11, fine 8–16 blocks a second), so they reach out round
the target before gravity takes them.
Everything moves with the target (its motion over the last tick, at most 0.6 blocks a tick). A blood drop is drawn as a
small glossy sphere; where it lands — floor, wall or ceiling — it sticks as a stain: an ellipse on that face, wider the
harder it struck (1.4–3.2 × its radius) and drawn out along the way it was going by 1/sin of the angle (at most 3.2), its
rim ragged, satellite droplets ahead of it; on a wall the larger ones (`runs`) run down it, ending in a bead; on a
ceiling a big one may let a drop fall a moment later. A run only goes as far down as the wall does (it stops at the
top of a doorway, a window, a ledge). A stain lies on the faces as drawn (`client/render/BlockFaces`, from the blocks'
outline shapes): it moves onto the outermost visible side under where it struck (a snow layer's top over its lower
collision box, a fence post's top under its raised one; nothing visible there and the drop is gone), and the sides that
really cover its plane round it — one rectangle per box side, those under a full block left out — cut it and its run
(Sutherland–Hodgman on the CPU, the pieces drawn as fans), so it never hangs over an edge, a slab's or a step's side, a
corner or air, and carries on over a neighbouring block that continues the surface — and where a surface really ends its
outline turns to follow the edge rather than being cut straight (§19); the rectangles are found again every
half second (blocks broken or placed), and a stain that none hold any more is gone. Drops landing in blood already lying
there run together with it into pools, blood sticks to plants and to other bodies, and splashes back onto the weapon
that struck (§19). Stains lie off their face by 0.0025 + 0.00007 × their distance (the depth buffer's
precision); their occasional checks (rain, the block under them, light) run on the pool's own tick counter, not their
age, which jumps in the rain. With second collisions off, blood vanishes where it lands. Stains stay `splatLife` × the setting seconds (0 = drops vanish
where they land), dry matte and darker over `dryTime`, and in the rain thin out — paler, wider, more see-through — and
age `1 + rainWash` times as fast; they go with the block they lie on. The debris pool passes over live stains a few
times before reusing one, so a burst of debris does not wipe the walls clean. A blood drop falling into water clouds out
there (a small plume) instead of sinking. The wound goes on bleeding a moment (`Wounds`): for `bleed` seconds (× the
blood setting, a head hit's `headMultiplier`, the round's or blade's energy) drops fall from where it went in — kept on
the body in its own frame, turned with its yaw — at `bleedRate` a second at first, fewer and fewer (× (time left)²),
moving with the target: a short trail of stains where it runs; under water the wound seeps small plumes instead. At most
48 wounds bleed at once. In water (the
wound under the surface): no drops, no mist, a plume at each wound — a cloud in water mode: its burst slows within a
second, then it drifts in a slow eddy, sinks slightly, spreads to `plumeSize[1] × ∛mass` over `plumeLife`, has no wind
and no substance above the surface (it flattens against it), drifts with the water's current (fluid physics), and seen
from above it shows through the water (the
water's own surface in the depth copy does not clip it; dimmed ×0.8) — the water around a wound turns red. A target
standing in water hit just above it splashes water tinted by `splashTint` towards the plume colour. The cloud block has
four vec4 per cloud (the fourth: the water surface's height, camera-relative, and the water flag).

**Disturbances** (`Impacts.disturb`; below energy 1 only into a free splash slot, so wading, cases and drips never push
out a round's splash; a drop's secondary splash likewise, a tiny one only ringing the surface): fluids stirred without a round — wading, swimming, falling in, swinging at the
surface, blocks dropped in — make a splash event of energy 0.2 + 0.45·E (E 0..2; no column below 1.2, white water above
1), drops thrown along the horizontal motion as a bow spray or all round and upwards for a vertical one, and bubbles; no
sound. Rounds entering water and disturbances also add a ring to `client/render/WaterRipples` (drawn by the water shader).

**Drawing** (`ImpactRenderer`): the world effects pass (§1, layer `VOLUME`, over the bullet holes) draws everything inside the particle pass right away into the
frame's target (honouring `RenderSystem.output*TextureOverride`) in one render pass, from two vertex buffers per frame —
vanilla's particle layout (`Position, UV0, Color, UV2`: debris, cloud lobes, sparks) and the block layout (`Position,
Color, UV0, UV2, Normal`: fluids) — positions camera-relative, interpolated between ticks. The vertex buffers, the depth
copy, the cloud block (a `MappableRingBuffer`, rotated after the pass) and every uniform are made before the pass opens
(creating or clearing a texture inside an open pass throws). Order: debris, molten rock, water (rings and white water on
the surface, then sheets and columns, then drops), clouds back to front, sparks.

| Draw | Pipeline | Blend / depth | What |
|---|---|---|---|
| debris | `blockland:core/impact_debris` | none; depth test LEQUAL **and write** | block atlas (`Sampler0`) × tint, alpha test 0.1; facet normal from `dFdx`/`dFdy` of the position, turned to the camera; lightmap + (world lighting) sun with the per-vertex shadow + `bl_pointLights`; dissolves per texel (stable hash) as `Color.a` (life left) falls; writes alpha `bl_emissionAlpha(0)` (no emission) |
| molten rock (`pipeline/impact_molten`) | `blockland:core/impact_fluid` modes 4 (drops: sphere impostors) and 5 (splats, the crater) | none; depth test LEQUAL **and write** | temperature in `Color.a`: white-hot → orange → a dark crust breaking up the glow (noise), the crust lit like the world; writes its glow as emission (`bl_emissionAlpha`) so it blooms |
| water (`pipeline/impact_fluid`) | `blockland:core/impact_fluid` modes 2 rings, 3 white water, 0 sheets / columns, 1 drops | premultiplied; depth test, no write | the lighting pack's water look (terrain.fsh, `BL_KIND_WATER`): the biome-tinted body lit by the lightmap (+ dynamic lights), `BlSkyColor` reflected by Fresnel (f0 0.02, strength `BlWater.z`, less indoors and under water), the sun's glint (shadowed); thin parts more transparent. Rings are only the change of the surface normal (three Gaussian crests' slope) — the reflection and glint move, nothing is painted on the water. White water: noisy aerated foam. Without world lighting the fog colour stands in for the sky (the same look vanilla's water has then), so the splash matches the water around it either way |
| cloud lobes back to front | `blockland:core/impact_dust` | premultiplied (`ONE, ONE_MINUS_SRC_ALPHA`); depth test, no write | each lobe (a sphere, 1–8 per cloud by mass, fewer past 28 / 56 blocks) intersects the view ray with itself, clips the far end where the ray meets the world (the depth copy, §10), and integrates its cloud's shared density field with four samples: α = 1 − e^−τ. The field: a Gaussian of the cloud's radius, stretched along the throw and sagging below the centre, cut off smoothly inside the lobes; its edge eroded into billows by three octaves of cloud-anchored noise in blocks (the billows grow slower than the cloud, so it rolls outward instead of inflating), the erosion biting deeper with age so a puff breaks into wisps before it is gone. The `ImpactClouds` block per cloud: centre and radius; lobe density, cloud density, seed, age; throw direction × stretch, sag. `Color.a` = the cloud's index. Composited over one another the lobes multiply their transmittances, so they read as one cloud with a dense core thinning outward, thinning where it touches geometry. Lit like the world (`bl_skyShade`: the lightmap's block part as it is, its sky part shaded by the sun's reach), so dust in the sun is as bright as the sunlit wall it came off, never a dark film over it: the reach is the lobe's shadow × the light let through the cloud — the closed-form optical depth of the Gaussian towards the sun (`ρ·e^(−2d⊥²/r²)·√(π/8)·r·erfc(√2·s/r)`, erfc ≈ 1 − tanh(1.2x)) with a multiple-scattering tail (orders of ½ the light at ½ the depth), so its core and far side are shaded, not black — × a mild Henyey–Greenstein lobe (g 0.35, a third of the way from isotropic; above full sun the thin edges glow towards it), skylight dimmed under a thick cloud, dynamic lights; without world lighting the lightmap with the same self-shadowing; fades just in front of the camera |
| blood lying on things (`pipeline/impact_blood`) | `blockland:core/impact_blood` (its own vertex layout, `client/impact/BloodStains`), after the molten rock, before the water | premultiplied; depth test, no write, depth bias | stains on faces, runs, pools and stains on plants: §19 |
| bubbles, blood drops | `blockland:core/impact_fluid` modes 8 and 6, last of the fluid draws | premultiplied; depth test, no write | camera-facing spheres: a bubble clear in the middle, bright at its rim, a highlight; a blood drop the blood look with full gloss, stretched along its motion |
| sparks | `blockland:core/impact_spark` | `ONE, ONE` colour, alpha × (1 − a) (at most 0.3 per spark); depth test, no write | additive streaks stretched over about a tick of motion, white-hot core, fogged; lower the target's alpha so they bloom (§3) |

Per-draw parameters ride in `DynamicTransforms` (`impact_fx.glsl`: camera axes, time, soft-depth flag, mode). Programs
`impact_debris`, `impact_dust`, `impact_spark`, `impact_fluid` in `tools/shaders/programs.txt`.

**Settings** (the 画质 tab, section 弹着效果; `config/blockland-settings.json` key `impacts`, `client/impact/ImpactSettings`):
quality `off` / `low` / `medium` / `high` / `ultra` — count multiplier 0 / 0.35 / 0.65 / 1 / 1.5, pool budget 0 / 500 /
1200 / 2500 / 5000 per kind, full detail up to 20 / 36 / 56 / 88 blocks fading to 15 % at twice that, nothing past 160
blocks — plus switches for dust, second collisions, weather / surroundings and fluid physics (`fluidPhysics`), the blood
amount, blood in water and how long stains stay (`bloodStains` 0..2). At most three impact sounds play per tick.

## 13. Wall graffiti (`client/graffiti`)

Graffiti (喷漆, data `data/blockland/graffiti/graffiti.json`) are drawn by `GraffitiRenderer` from the world effects pass
(§1, layer `SURFACE`: under dust and smoke) straight into the frame's target in one render pass, one draw per piece and
layer; every texture is loaded and every transform written before the pass opens. Per-draw parameters ride in
`DynamicTransforms.TextureMat` (layout in `blockland:graffiti_fx.glsl`: time, reveal, fade, mode; colour, flicker;
frame centre, seed, frames).

| Draw | Program | Blend | What it does |
|---|---|---|---|
| paint on faces (normal, every layer of a layered piece at its own height off the wall, an animated piece's frame, a neon tube's glass) | `blockland:core/graffiti_paint` (vanilla block layout: Position, Color = face shade, UV0, UV2, Normal) | premultiplied colour (`ONE, ONE_MINUS_SRC_ALPHA`), target alpha kept (`ZERO, ONE`); depth test, no write, depth bias (−1, −10) | the square cut to every visible face of its plane (`GraffitiGeometry`, Sutherland–Hodgman per block face, re-laid every second); lit like the wall: face shade × lightmap, with world lighting the sun (per-vertex shadow with the face normal), `bl_pointLights`; builds up from the middle in blotches while sprayed, thins out when taken down |
| light (neon glow, hologram, particle points, projector ring) | `blockland:core/graffiti_glow` (vanilla particle layout) | `ONE, ONE` colour, alpha × (1 − a) with a ≤ 0.3 (emission, §3: it blooms) | neon: core line near white + glow channel in the tube's colour, flickering (`LightSources` follows the flicker); hologram: upright square turned to the viewer about the vertical, scan lines, roll, band glitches, grows out of the projector; points: round sprites facing the camera, flown in from a burst, then twinkling; ring: dashed circle on the floor under a hologram |

Neon, hologram and particle pieces attach a `LightSources` light for as long as they are up. Unlit (world lighting
off) the paint is texture × shade × lightmap, like vanilla terrain; the glow never depends on world lighting.

## 14. Fog and sky (`client/graphics/sky`, the lighting pack)

**Fog switch** (`FogSwitch`, setting 迷雾): with fog off, `ViewportEvent.RenderFog` pushes the open air's
(`FogType.ATMOSPHERIC`) environmental fog to infinity and narrows the render-distance fade to its last 4 % (chunks
never end with a hard edge); water, lava, powder snow, blindness and darkness keep their fog. With fog on vanilla's
numbers are untouched.

**Volumetric fog and light** (`shadow/WorldFx`, `fx_volumetric`, settings 迷雾, 雾的浓度, 雾的质量 16 / 28 / 44 steps,
体积光): the air as one participating medium, marched at half the internal resolution up to the shadow distance and
integrated in closed form beyond it (to the scene, or the render distance through the sky), energy-conserving. Medium: the
haze (`BlFog.x` at the camera's height, `light/Sky`: 0.002 / block × 雾的浓度, thinning by 1/48 per block, ×2.5 at dawn and
dusk, ×3.5 in rain, eased to the camera's openness to the sky — none in caves), ground mist near sea level (scale height 9
blocks, dawn, dusk and rain, broken into drifting value-noise patches) and a thin light-only shaft medium (体积光). Light:
the sun or moon through the shadow map × the clouds' shadow (Henyey-Greenstein g 0.7 with a quarter isotropic; ×(1 + 1.6 ×
体积光)), the sky and multiply scattered sunlight (isotropic, by the camera's openness) and the dynamic lights. Without a
shadow map the sun reaches the fog only by the camera's openness. Under water: dense, blue, light only. Output rgb
in-scatter and a transmittance; the decode pass multiplies by it and adds the light (after the clouds). Whether it runs is
decided at frame start (`GraphicsStatus.volumetricFogActive`) and then `BlFog.w = 2`: the lit programs keep only the soft
render-distance edge. A scope's picture (no fog pass) keeps the forward haze (`BlFog.w = 1`).

**Forward haze** (`blockland:bl_fog.glsl`, the lit terrain and entity programs instead of `apply_fog`): with world lighting
and fog on, `BlFog.w = 1` adds the haze in closed form (its colour vanilla's fog colour plus forward-scattered sun,
Henyey-Greenstein g 0.6), `BlFog.w` ≥ 1 turns the render-distance fade into a smoothstep over vanilla's band; the
environmental fog stays linear; the emission alpha is fogged by the combined amount. With `BlFog.w = 0` or without world
lighting it is exactly vanilla's `apply_fog`.

**Cloud shadows** (`scene.glsl`: `bl_cloudCoverage`, `bl_cloudShadow`, inside `bl_shadow`): the ray from a point towards the
sun meets the cloud layer's lower third; the coverage there (the clouds' weather map, `BlClouds` / `BlCloudShape`) as an
optical depth 7·c² × density, mixed by the shadow strength (0.9) and faded out for a low sun. Every lit surface (terrain,
entities, weapons, dust, graffiti) and the volumetric fog get it, with or without a shadow map; the shadows drift with the
clouds. Zero while the volumetric clouds do not run.

**Volumetric clouds** (`VolumetricClouds`, a frame stage after the level, registered before the light module; settings
体积云 + 云层质量 + 云量; needs world lighting): a layer from the dimension's cloud height up 240 blocks (none where the
dimension has no cloud height), marched per pixel at 1/3 or 1/2 of the internal resolution (32 / 48 / 72 steps, 3 / 4 / 6
towards the sun) in `gfx_clouds`, stopped by the world depth. Noise, made once on the GPU (`gfx_cloud_noise`): a 128³
Perlin-Worley shape volume tiling over 1024 blocks, a 32³ Worley detail volume tiling over 128 blocks (both stored as
slices in 2D atlases, sampled bilinearly in the two nearest slices) and a 1024² weather map of the 4096-block cloud noise
period (the raw coverage noise of `bl_cloudCoverage`, so the clouds and their shadows agree, and a cloud type). Density
(Schneider 2015): coverage from 云量 (+ rain and thunder) cuts the Perlin-Worley cells; flat stratocumulus sheets or
towering cumulus (flat base, rounded top, taller where the coverage is high) by the cloud type; the detail noise, drifting,
erodes the edges — wispy at the base, billowy above. Light: `BlSunColor` × multiple scattering by octaves (Wrenninge 2013:
three octaves of half the energy, a quarter of the optical depth, half the anisotropy) of a march towards `BlSunDir`, two
Henyey-Greenstein lobes (0.8 forward, −0.3 back), the powder darkening of thin edges seen from the sun's side, ambient from
`BlSkyColor` above and a ground bounce below; energy-conserving, early out at 1 % transmittance; aerial perspective tints
them towards the sky (9000 blocks) and fades them out (14 000). The layer's numbers (`FrameContext.clouds`: noise position
of the camera — camera + wind, 6 blocks/s, wrapped at 4096 — layer bottom and top relative to the camera, coverage,
density, shadow strength, drift time) go into the scene block at `cameraReady`. Then `gfx_clouds_temporal` blends 12 % of
the new march into last frame's (reprojected by the camera's turn only, clamped to this frame's 3×3 neighbourhood). The
decode pass multiplies the world and the sky by their transmittance and adds their light, before the volumetric fog.
Whether they run is decided at frame start (`GraphicsStatus.cloudsActive`) and written into `BlBlockLight.a`, so the
lighting pack's cloud shader hides vanilla's clouds in exactly those frames; a scope's extra level render gets a copy of
the world block with the flag off (the clouds are only marched for the main view). Programs `gfx_clouds`, `gfx_clouds_temporal`, `gfx_cloud_noise` and `clouds` (vanilla's, overridden) in
`tools/shaders/programs.txt`.

## 15. Rain and water (the lighting pack, `client/render/WaterRipples`, `client/fluid`)

**Fluid normals.** Vanilla's `LiquidBlockRenderer` gives every fluid face the normal (0, 1, 0), falling sheets
included; the lit terrain program takes the face's true normal from the screen derivatives of its position for water and
for lava (a glowing face whose claimed up normal is not level), so a waterfall is never shaded as a level surface seen
edge-on.

**Water** (terrain.fsh, role 3, kind water): level surfaces take their slope from `bl_waterSlope` — the travelling waves,
rain drops (`bl_rainSlope`: two offset grids of cells, each a drop landing at its own moment of a 0.77 s cycle, a ring
spreading from it; fewer cells in light rain, `BlWeather.y`) and ripples (`bl_rippleSlope`, below); falling sheets streak
downwards and churn white (foam: aerated water, lit like the world). The environment — the sky above, the horizon's fog
colour towards it, darker where sky light is low — is reflected by Fresnel (f0 0.02, at most 0.9) against the face's
true normal; the sun's highlight is GGX with Smith visibility (roughness 0.07, more on foam), shadowed (clouds included)
and capped. The Fabulous composite bends the world behind by the same slope.

**Ripples** (`client/render/WaterRipples`, a shared contract like `LightSources`): rings at a point of a surface with a
strength and a heading; the newest 24 live (3.5 s) go into the scene block (`BlRipples[]`, count in `BlWeather.w`) every
frame. A ring is a wave packet spreading at about a block a second, widening, fading with age and distance, stronger ahead
of its heading (a bow wave). `client/fluid/WaterInteractions` adds them, with splashes through `Impacts.disturb`, for
entities within 48 blocks: wading and swimming (rings along the heading, more often the faster, spray when running),
falling in (by the speed and direction of the fall), a swing whose reach meets the water (along the attacker's yaw and
pitch), a block placed into water; the impacts module adds them where rounds and splashes meet water. Setting 水面涟漪与水花.

**Soaking** (setting 浸湿与滴水, `GraphicsSettings.soaking`; `client/render/Wetness`, a shared contract like the ripples):
`WaterInteractions` records every living entity in water within 48 blocks as soaked through (under water) or to the
square of the depth it wades; out of it, it dries over 45 s (twice as long while rain falls on it, looked at twice a
second) and drips — `wet⁴ × 2.5` drops a tick, scaled by its width: water streams off at first, then a drop now and then
— from its sides, mostly low, with its motion (`Impacts.drip`; within 20 blocks, never off the body the camera is in).
How deep it stands is vanilla's own measure over its box (`getFluidHeight`), so an entity at the edge of a stream is
only wet to that depth. Running on rain-soaked ground (rain falling there, 60 % of full rain or more, a sturdy top under
the feet, within 20 blocks; setting 水面涟漪与水花) every stride of about a block kicks up a few small drops of the water
standing on it, up and back from the foot (`Impacts.kick`). The viewmodel (`ViewmodelRig`, every weapon) asks `Wetness.of(player)` each frame and hands it to its `DrawList.wetness`,
which packs it into ModelOffset.z's fraction (flags + 0.45 × wetness, so `int(z + 0.5)` is still the flags); gun.fsh
takes `max(rain wetness, soak)`: a weapon out of the water is wet all over, beads clinging to its sides too, until it dries.
Rain falling on a body (sky above its head, looked at twice a second) soaks it too, 0.03 more each time up to 0.4: the
weapon's rain wetness itself follows the rain *falling* (`BlWeather.y`, never the ground's slow `BlWeather.x`), so
when the rain stops (or the holder goes under a roof) what is left on the weapon and the hands is that soaking,
drying in under 20 s — not the minutes the ground takes.

**Rain wetness** (setting 雨天湿润 0..1; `light/Sky` → `BlWeather.x`): rain wets only where the camera's biome rains (not
snow, not deserts); surfaces soak in about 15 s and dry in about 90 s. `bl_wetness`: the wetness where sky light reaches
(≥ 14), tops soaked, sides streaked, undersides dry. Blocks (terrain.fsh, not water, not glowing texels): porous ones
darken (metal and glass hardly), a film of water reflects the environment by Fresnel and the sun (GGX roughness 0.3); flat
tops of opaque blocks gather puddles in their low spots (`bl_puddle`: world-anchored noise filling as the wetness grows)
with the rain's rings, mirror-smooth. Entities (entity.fsh, sky light from `UV2`): darker, a sheen of water. Weapons
(gun.fsh, every draw of the world's frame — viewmodel and third person — by the holder's sky light; needs world
lighting, which carries the weather): porous parts darker, a water film (roughness towards 0.08, a clear coat up to
0.6), and water standing on them as many tiny drops, each a shallow cap of water with its own normal for the coat's
sharp reflection (roughness 0.02) and the sun's glint, a faint dark rim (no lens brightening, no ripple rings). Laid out
in the weapon's own space on the plane of the surface normal's dominant axis, three cellular layers of beads: 260 cells
per block (droplets of 1–2 mm, the densest), 150 (2–5 mm) and 75 (5–11 mm, sparse: merged beads, rarer on steep faces);
radii mostly at the small end; more on the tops, few on the sides, 1.35× and steeper hanging on the undersides. While
rain falls (`BlWeather.y`) each bead lands suddenly, grows a little and is gone again (merged, run off); the coverage
follows the weapon's rain wetness (the rain falling now, plus the holder's soaking), so they gather as the weapon soaks. The bigger drops on sloping and upright faces run down
them (lanes 1.1 cm apart, each drop sliding in fits — sticking, letting go — wandering, a hair-thin wet trail above it;
the down direction is the world's, carried into the weapon's space through the model-view, so the drops run downhill
however the weapon is held). Anti-aliasing: a layer fades into the film (more gloss, no drops) where a pixel covers more
than ~0.12–0.4 of its cells (the footprint taken from `dFdx`/`dFdy` of the position before any branching), and a bead's
rim is as soft as a pixel, so distant and third-person weapons read as wet without sparkling. Soaked: beads on every
side. Nothing under water (the camera in it). Dry or wet, every weapon is a little glossier than its material says
(roughness eased 30 % towards its square; non-metals under a 0.12 clear coat).

## 16. The texture pack (`resourcepacks/blockland_textures`, `client/textures/FluidTextures`)

The built-in client pack "BlockLand 材质" replaces vanilla's water and lava textures with 1024-texel ones made by
`tools/assets/gen_fluid_textures.py` (spectral noise: every field tiles by construction and animates by whole phase turns
per loop; flow textures also translate along +v — the direction LiquidBlockRenderer's UVs give the current — by one
period of their content per loop, so their spectrum keeps only v frequencies that fit that period). Water stays greyscale
with vanilla's alpha 180 and mean luminance (the biome colour tints it); it is low-contrast on purpose, since the block
atlas has only ~4 mip levels: a slow swell, a soft caustic-like network, faint capillary ripples; the flow adds long soft
streaks and foam along the current. Lava: cooled basalt crust, near black and grey-brown with low ropy wrinkles, dull red
where thin beside a crack, broken into plates of very different sizes (warped jittered cells at three scales: big
plates, about half split again, fine cooling cracks; crack width varies) — light only in the cracks (white-yellow core,
orange edge, a few centimetres wide) and in molten channels and pools welling up along the big cracks (orange-yellow,
hottest in the middle, swirled), about a quarter of the area. The flow draws the plates out along the current. Only the
hot parts glow: the lava materials' threshold in `block_materials.json` is 0.42 (brightest channel), above the crust.
16 frames each, not interpolated (vanilla interpolates on the CPU per pixel, far too slow at this size); speeds as
vanilla's (water flow 1.25 blocks/s, lava flow 0.42). The pack follows 高清流体材质 (`GraphicsSettings.fluidTextures`):
preselected at startup, added or removed with one resource reload when the settings are committed; an ordinary pack
otherwise (the player may reorder it).

## 17. Screen-space reflections (`client/graphics/reflect`, `blockland:bl_ssr.glsl`)

Setting 屏幕空间反射 off / low (20 steps) / high (40), with world lighting. After the level of every frame (before the hand
and the viewmodel, so they are never reflected) `ScreenReflections` keeps the world's colour as drawn and its linear depth
(`gfx_ssr_capture`, RGBA16F at the internal resolution). The next frame (`Graphics.reflections()`, only when that picture
was taken in the frame just before) the scene uniforms carry `BlPrevView` (camera-relative world now → last frame's view:
its rotation and the camera's move since) and `BlPrevProj` (its P00, P11, the step count, on), and the picture is bound on
texture unit 14 (`BlPrevScene`, patched into the lighting pack's programs like the shadow atlas). The lit terrain
program's water — and puddles, by how much of a puddle the pixel is (the thin wet film keeps the sky's estimate) — march
the reflected ray from just above the surface: steps of growing length (from 1.2 % of the distance to the eye, ×1.15
each), each point carried into last frame's picture; a point just behind what was drawn there (within the step's length,
at least 0.35 blocks) is a hit, refined by five bisections. Its colour replaces the environment estimate by a confidence
fading at the picture's border, towards the last steps and for rays turned back towards the eye; rays leaving the
picture, crossing only sky or finding nothing keep the estimate (`bl_reflection`). The hand block and a scope's copy carry
no reflections (`BlPrevProj.w = 0`). Volumetric clouds and fog are post effects and are not in the picture.

## 18. Wind (`client/graphics/wind`, the lighting pack's terrain.vsh, `blockland:bl_wind.glsl`)

Setting 风吹植物 + 风力 0..2 (`GraphicsSettings.wind`, `windStrength`); needs world lighting — unpatched (`BlRole` 0), with
`BlFlags.x = 0` or with `BlWind.w = 0` the vertex shader moves nothing and is vanilla's. Which sprites move is data: the
`wind` of their `block_materials.json` entry (§7), in the material map; how hard is `assets/blockland/graphics/wind.json`
(`WindData`: `bend` lean / flutter / exponent / lag, `stack` lean / flutter / pushCap, `push` radiusPad / core / lean° /
flatten / springPeriod / springDamping / trail / kinds, `columns` up / hang block ids), in `BlWindBend`, `BlWindStack`,
`BlWindPush`, `BlWindSpring`, `BlWindKinds`. Modes: **bend** (grass, flowers, ferns, saplings, crops, berry bushes,
seagrass, both halves of two-block plants), **sway** (stacked and hanging plants: sugar cane, bamboo, kelp, vines, cave,
weeping and twisting vines, hanging roots and moss), **leaves** (a few centimetres of rustle, a smooth field of the
position, so neighbouring leaf blocks never part). `flex` scales the wind's effect. A `water` plant ignores the wind and
sways in a slow current (`BlWindInfo.w`).

**Which vertex is where.** A chunk section's vertex buffer holds whole quads from vertex 0 and is drawn with a sequential
quad index buffer, so `gl_VertexID & 3` is the corner in FaceBakery's order (corner 0 at the face's first u and v, 1 first
u second v, 2 second u and v, 3 second u first v; on a side face 0 and 3 are the upper edge, 0 and 1 its end along the
tangent (−n.z, n.x)). The sprite is looked up at the vertex's own uv: FaceBakery shrinks a face's uvs towards the
sprite's middle (`uvShrinkRatio`), so that uv lies inside its sprite whichever way u and v run (crops and vines run u
backwards). Never look past the uv: the cell beyond the sprite's edge is a neighbouring sprite, and a still block next to
a plant in the atlas would wave. The CPU gets the same bits per texture from `client/render/PlantWind` (published by the
block materials after every stitch), for whatever must move with a plant: a shader handed them calls `bl_wind` as the
terrain shader does (blood on plants and leaves, §19). The vertex's height in its block comes from the section-local `Position` (bottom edges snap down
within 0.25, tops within 0.1; crops start 1/16 below their block). The plant's **stem** is the middle of its block: the
face's midpoint (half a face width — 0.5, or 0.707 on a diagonal cross — back along the tangent) lies in it for all four
corners, so the whole plant shares one stem, one push and one height.

**Bending.** A bend plant is a cantilever anchored at its bottom: the wind displaces a vertex h blocks over the plant's
base (the upper half of a two-block plant adds 1, so it bends on from the lower half's top) by `bend × h^exponent`
(1.75), so taller plants bend much further. `bend` = the wind's lean (`lean × w`, slow and strong; a gust's tail lets it
swing back by half) plus a smaller, faster flutter (`flutter × (0.6 + 1.4 w)` along, 0.6 of it across, at the fixed
frequencies 1.4 / 2.1 Hz and 1 / 1.6 Hz — constants, or neighbours' phases would drift apart over time; phases vary
smoothly over the ground). The top feels the wind from `lag × h` blocks upwind (a gust reaches it later) and flutters
0.9 rad per block behind its foot. A stem leans, never stretches: the displacement is kept under 0.97 h and the top
sinks by h − √(h² − d²).

**Columns.** Stacked plants have no height of their own, so `PlantColumns` keeps, for each block column of a 128 × 128
window round the camera (world x, z modulo 128; texture `BlPlantColumns` on unit 15, RGBA8, `BlWindStack.w` = 1 when
bound), the longest run of `columns.up` blocks (bottom y and length) and of `columns.hang` blocks (top y and length) within
64 blocks of the camera's height: RG / BA = (y + 65) | length << 9. Four chunk columns are rescanned a tick, round robin
(the window about every 0.8 s; sections whose palette holds none of those blocks are skipped), and the texture is uploaded
when something changed. A sway vertex whose block lies in its column's up run bends as one stem from the run's base
(h = height over it, `stack.lean`, `stack.flutter`, the same exponent and lag), one in a hanging run from the run's top
(h = depth under it; the tip rises as it swings); one in no known column falls back to a smooth field of the position.
Flat sheets (vines) move only across their plane.

**Bodies.** The 16 nearest living entities (40 blocks, not spectators) are pushers: feet, radius = half width +
`radiusPad` (0.6), velocity, height. A plant whose stem lies within a pusher's radius, between its feet − 0.25 and its head,
is tipped over about its foot away from the pusher's middle — the whole stem rotated by up to `push.lean` (70°) × the
mode's `kinds` share, at full strength within `core` (15 %) of the radius, falling smoothly to nothing at its edge (the
falloff is the arrival: a walker bends a plant over in about 0.15 s) — and pressed towards `flatten` × 90° right under a
foot (the way the body moves). Where a body has been it leaves a mark (`BlSways`, 48, 1.4 s): one every 0.35 blocks while
it walks; one that stands keeps its mark fresh. Once the body has gone, the plants round a mark spring back from the lean
they had: `e^(−ζωτ) cos(ωτ)` (`springPeriod` 0.45 s, `springDamping` ζ 0.35: up in about 0.1 s, an overshoot of a third
the other way, settled within a second); the marks near a plant count as one release (their average, as strong as the
nearest), and while a body still holds the plant the spring is held back. A small wobble along the walker's heading
(`trail`) is left of the old trail sway. Stacked plants are pushed at most `stack.pushCap` (0.35 blocks), leaves 12 % as
far, each vertex on its own (the canopy stays whole).

**The rest.** The steady wind's heading wanders (faster in thunder) and its strength eases towards 0.22 + 0.42 × rain +
0.45 × thunder (0.08 without sky light), broken up by turbulence carried along with it; gusts are born 45–70 blocks
upwind of the camera (0.12 + 0.35 rain + 0.5 thunder a second, at most 6), 5–12 blocks deep and 14–36 wide, travel at
5–9 blocks/s (+3 in thunder) with a ragged front and fade in and out over 2 s. All of it is world-anchored with periods
dividing 2048 blocks (`BlCamera` wraps there) and real time (`BlFlags.w`). The fragment shader keeps the undisplaced
position (`worldPos`) for the sun's shadow and the lights: the shadow map's terrain casters (`shadow_terrain`) hold the
plants still, so a moved plant takes its own still shadow and casts a still one. Moving plants carry no motion vectors
(TAA, DLSS and motion blur treat them as still geometry).

## 19. Blood (`client/impact`, `client/decal`)

Blood is part of the impacts (§12: the drops, mist, plumes, wounds); this section is where it ends up. No stain is a
texture: every outline is made from the stain's seed in `blockland:core/impact_blood`, every relief is a height field.

**On faces.** A landed drop is a splat (§12). Its quad (`client/impact/BloodStains`, its own vertex layout: position,
colour, the stain's uv, lightmap, then `Stain` (distances to the edges, seed, wetness), `Shape` (kind × 16 + face × 2
(+1 a run), wind bits, radius, gloss), `Anchor` (the point the wind moves it with), `Atlas` (a plant's uv)) is cut to the
rectangles of the surface under it as before, and every corner also carries, for its rectangle, the signed distance to
the rectangle's **real edges** nearest the stain along the face's two axes. A real edge is one where the surface ends: not
shared for the most part with a neighbouring rectangle that carries the surface on, not where the search window cut it
(`ImpactParticles.exposed`). A distance to a line is linear, so it interpolates exactly over a piece. The shader keeps the
blood a hair (1.2 mm) short of such an edge and joins the outline's distance field and the edge's with a smooth
intersection (rounded over 0.22 × the radius): where a splat or a pool reaches the end of a floor, a step, a slab's side,
its outline turns to run along the edge, rounded where the two meet, and bulges a little against it (held back by its
surface tension); over a continued surface nothing changes. Satellites stop at the edge too.

**Pools.** A drop landing on blood already lying on the same face within `poolMerge` × its radius (on a wall half that:
drops run there rather than gather) runs into it: the stain grows area for area towards `poolMax` (radius) and creeps out
to that over `poolSpread` seconds (its clip window covers where it is creeping to), its middle shifting towards the new
drop, wet again and living on; on a floor it becomes a **pool**: rounder (its stretch eases off), slow round lobes
instead of spines, no satellites, thicker. A wound bleeding on the spot and a body bleeding out make pools: a wounded body
that dies lets `deathPool` drops fall where it lies (`Wounds.bleedOut`). A pool on a floor grown past `overflow` ×
`poolMax` that reaches a real edge **runs over it**: a run starts at the top of the side below (`ImpactParticles.spill`,
once per side), down as far as the wall goes.

**Relief and shading.** The height field: a meniscus rising steeply over 0.25 × the radius (at least 1.5 mm) from the
outline to a low dome, the thicker against an edge, faint ripples while wet; its height 1.1 mm for a splat, 2.2 mm a
pool, 0.8 mm a run, 0.5 mm on a plant, a third of that once dry. Its normal comes from the screen-space derivatives of the
height and the position (a surface gradient: a normal map without a texture or tangents), so the sun's glint, the sky's
Fresnel reflection (by the gloss: wet 1, washed 0.6) and the lights catch the film's rim and ripples; drying, it flattens,
darkens and goes matte, a darker coffee ring at its rim. Lit like the world as the drops are (§12).

**On plants.** A drop going through a block whose model has a quad the wind bends (`client/render/PlantWind`: grass,
flowers, crops, saplings; no collision box) sticks to that quad with the blood's `plants` chance (each block once): the
quad found in the block's baked model (with its random offset), the point projected onto it, the quad's level axis and the
block atlas uv under the stain (and its change per block along the quad and up) kept with it. Drawn on the quad (a little
towards the eye), the plant's own texture's alpha cuts it to the blades. In the vertex shader the whole stain moves with
the plant at its middle: `bl_wind` (the same function the terrain shader moves the plant's vertices with) at the anchor,
as the top corner of a cross quad at that height whose stem is the anchor's column — the lean, the flutter, the gusts and
bodies pushing the plant aside. Stains on leaf blocks (`leaves` wind) move with the canopy's smooth field the same way,
vertex by vertex. A plant broken, cut or trampled flat takes its stain with it; rain washes it out like any other.

**On bodies.** Flying blood is tested against the living bodies round where it flew last tick (not the camera's own body
in first person; at most 24): a drop entering one (not the body it came out of, for its first 12 ticks) stains it with the
blood's `bodies` chance — the decal type `bodyDecal` (`blood_spatter`: six variants, splats with a lobed rim, spines and a
tail thrown along the way it flew, satellites) laid on the drawn model's cube it strikes (the body decals, `client/decal`;
they move with the model). The impact module only calls `Impacts.BodyStains`, which the client wires to
`Decals.onEntity`; a drop the model does not take flies on.

**On the weapon and the hands.** A hit on a body splashes blood back onto the weapon of whoever struck it
(`client/render/WeaponBlood`, a contract like `Wetness`): a blade's attacker (the eye the cut came from) gets
`weaponBlade`, a round's shooter (looking down its line at the wound within `weaponRange` blocks) `weaponRound` ×
(1 − distance / range). The impact module records each splash — the way the blood flies (from the wound towards the
holder), how much, when, `weaponLife` / `weaponDry` — and whatever draws the holder's weapon takes the splashes it has not
laid yet. The first-person viewmodel (`client/viewmodel/ViewmodelBlood`, one hook in `ViewmodelRig.drawArms`) lays each as
2 + 6 × amount drops: each flies along the splash's way, falling a little and spread, and lands on a point picked over the
drawn weapon and hands by area × how squarely the surface faces it (more on the front, nearer the struck body) that
nothing nearer covers (a ray back towards the wound). There it becomes a decal: a square on the surface, its u the way the
drop slid over it (drawn out up to 2.6 × the more glancing it came in), every triangle of the hit part in a thin slab
round the surface and turned the same way clipped to it — stored as barycentric weights of the part's vertices, not
positions. Every frame the decal is rebuilt from the part's vertices as they are now (a weapon node's mesh; a finger's;
the skinned arm and palm as `ArmSkin` skinned them this frame) and drawn at that part's matrix, so it lies on the surface
through every animation and bend and never moves; old drops keep their place when new ones land. `gun.fsh` (effect 7,
params seed, wetness, opacity, stretch; the vertex AO byte the facing fade) draws the drop in the decal's square: a body
with a ragged rim behind the middle, a tail into the front and a few satellites thrown ahead; a dielectric film, dark red
and deepest where it pools, raised and glossy (a relief from its height field, a clear coat) while wet, a flat
brown-black crust with a darker rim when dry; the rain's water lies on top of it. Each drop dries and fades on its own;
at most 56 pieces per holder (the oldest go). Kept per holder and weapon model: the hands keep theirs across weapons, a
weapon's blood is back when it is drawn again (until it has faded). Glass, effects and see-through parts take none.

Data (`impacts.json`, a material's `blood`): `poolMerge`, `poolMax`, `poolSpread`, `overflow`, `plants`, `bodyDecal`,
`bodies`, `weaponRange`, `weaponBlade`, `weaponRound`, `weaponLife`, `weaponDry`, `deathPool`; the body decal in
`decals.json`, its texture from `tools/assets/gen_decals.py`.

## 20. Performance (`client/perf`, `com.blockland.mixin.*`)

Optimizations do less work for the same output: the same pixels, the same game. Nothing here lowers a quality, drops a
sample, skips an update that could leave something a frame stale, or reorders draws.

* **Switches** (`perf/Optimization`): every optimization is an entry with a group (vanilla / BlockLand pipeline), a
  label and note in the lang files (`blockland.settings.perf.opt.<id>`, `….note`), and `rebuildsSections` when its work
  lives in the section meshes. Code asks `Optimization.X.on()` (one volatile read, any thread) exactly where it chooses
  between the old path and the new one; off must be the old code path exactly, so the benchmark's off passes are
  vanilla / the previous pipeline. The player's choice is `PerfSettings` (`ClientSettings.Data.performance`, the 性能
  tab, all on by default); `Perf.applySettings` applies it and rebuilds the sections when a `rebuildsSections` entry
  changed. Nothing else of the perf module is reached from other modules.
* **Mixins**: one config per area of vanilla (`blockland.mixins.json` core, `blockland.gl.mixins.json`,
  `blockland.terrain.mixins.json`, `blockland.particles.mixins.json`; packages `com.blockland.mixin.<area>`), listed in
  `MIXIN_CONFIGS` in `build.gradle` (jar manifest `MixinConfigs`, `--mixin.config` for dev runs). Official names at
  runtime, so no refmap; Mixin 0.8.7 comes with Forge 58 (no MixinExtras: `@Inject` / `@Redirect` / `@ModifyArg` /
  `@Overwrite` only). A mixin stays thin: it checks its switch and calls a class of its area in `client/perf/<area>`.
* **Timings** (`perf/FrameTimings`): `try (var s = FrameTimings.span("bl/…")) { … }` measures a stretch of render-thread
  work on the CPU and on the GPU (timestamp queries read back 4 frames later — never a wait); spans nest, keys are paths.
  Every pipeline stage's phases are spans by themselves (`bl/<Stage>/<phase>`, `FrameHooks`), and so are vanilla's own
  profiler sections down to 5 levels (`mc/<path>`, through `Minecraft.constructProfiler`). A frame is one iteration of the
  client loop (ticks, render, swap). Spans cost a field read while nothing records (timings off, no benchmark).
* **Render clock** (`client/render/RenderClock`): everything on screen animates by `RenderClock.nanos()` (also the shared
  `anim/Ticker`); durations that measure or budget work keep `System.nanoTime()`. Normally it is the real clock.
* **Benchmark** (`perf/Benchmark`, the 性能 tab, in a world): the camera turns through 16 fixed views on the spot.
  Frame-time passes with every optimization off and on in the order off, on, on, off, off, on (after a warm-up pass);
  full section rebuilds off, on, on, off, timed until drained; then the frame comparison — the client's ticks are held
  (`PerfHooks.ticksHeld`: `Minecraft.tick` skipped, the delta tracker's partial tick 1, no delta), the render clock is
  pinned to fixed 1/60 s steps from the same origin, `Graphics.restartTemporalState()` starts the pipeline over
  (resources, frame count, `FrameStage.restart`), and 24 frames are rendered per view three times: off, off again after a
  rebuild (the control: it proves the scene and the pipeline are deterministic), on after a rebuild. Before each view's
  frames the camera waits on that view until vanilla's culling has found every section in view and all are built (after
  a turn it takes a few frames; a first frame without them would stay in the temporal history), and every temporal
  state with its own frame counter restarts with the pipeline (e.g. the shadow map's cascade turns). The 24th frame of
  each is read back right after the composite (before the HUD) and compared per pixel (RGB). Differing views save both
  frames and a map of the differences. With OP rights the world is frozen by `/tick freeze` meanwhile. "Rebuilt" means:
  the visible-section graph is complete, every visible section with loaded neighbours has a mesh, nothing is queued
  (cancelled tasks do not count), nothing waits for upload, and the view's section count held for five frames.
  `MeshCheck` compares vertex bytes the format uses (the block format's padding byte is never written and holds old
  memory). A pass starts with a garbage collection (the switch's rebuild leaves garbage); the 1% low is taken over all
  frames of a mode. The frame-time passes run twice: on the spot (the camera only turns), then with the camera circling
  3 blocks around it at the same height (everything that waits for a still camera is redone every frame); the summary
  gives both. `bench compare` (dev automation) runs the frame comparison alone, `bench <id>` compares one optimization
  (off and on, every other one on). Reports:
  `<game>/blockland/benchmarks/<time>.txt` (the player's language) and `.json` (every pass, every span).
