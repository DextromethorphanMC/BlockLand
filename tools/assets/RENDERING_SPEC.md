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
would only land at the end of the pass, over everything drawn there.

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
  carried through). The role comes from the pipeline (shader + blend function), never from the shader source.

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
  add nothing (the sky's light is in Minecraft's lighting already); occlusion within 3 blocks. Then BlockLand's denoiser
  (temporal accumulation by reprojection with linear-depth rejection, 32 frames at most, and three edge-avoiding à-trous
  passes) or none when DLSS Ray Reconstruction denoises; a joint-bilateral resolve to the internal resolution × albedo ×
  strength. Decode adds the light and multiplies by the traced occlusion, which replaces the screen-space one (that pass
  is skipped). Verified against a CPU march by `tools/raytrace/check_voxels.py`.
* DLSS Ray Reconstruction (Streamline feature 1001, `sl.dlss_d` + `nvngx_dlssd`): with ray tracing on, DLSS Super
  Resolution or DLAA chosen and RR as the denoiser, the rays run at the internal resolution without BlockLand's denoiser
  and DLSS evaluates RR instead of SR on the same viewport: the colour carries the noisy traced light, with three more
  tags — diffuse albedo (the scene's colour floored by the voxel albedo, so its texture detail is never taken for noise),
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

Entities are captured by running their own renderers into a capturing buffer source; while that runs
`client/render/ShadowPass.active()` is true, and renderers that would draw with their own pipeline (the third-person
weapons) take their vanilla-render-type path instead, so they are captured and cast shadows like everything else.

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
(`Graphics.settingsCommitted`).

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

What a round throws out of whatever it strikes, client-only and purely visual (no vanilla particles). Weapons only call
the facade `Impacts` with the hit and their ammo id (`guns.json` ammo: `pistol`, `smg`, `rifle`, `sniper`, `shotgun`):
`block(level, at, face, pos, dir, ammo)`, `entity(level, at, entity, dir, ammo)`, `fluid(level, at, fluidState, pos,
dir, ammo)`, `fluidOnPath(level, from, to, shooter, ammo)` (splashes where a shot first enters a fluid's surface) and
`entityAt(level, end, shooter)` (for shots reported without their target). The gun module calls them from its local
trace and for other players' shots; decals stay the weapon's (`Decals.onBlock` with the ammo's decal).

**Data** (`assets/blockland/impacts.json`, Gson, `client/impact/ImpactData`; speeds blocks/s, sizes blocks, times s):

* `materials.<id>`: `debris` {`count`, `shapes` (`chip` two crossed faces, `flake`, `splinter`, `shard` triangle,
  `grain`, `leaf`, `droplet`), `size`/`speed`/`life` ranges, `spread` (cone half-angle °), `gravity` (× 0.04 b/tick²),
  `drag`, `restitution`, `friction`, `spin` (°/s), `wind` 0..1}, `dust` (one cloud per hit, see below) {`count` (mass of
  powder without chunks), `perDebris` (mass per chunk), `friability` 0..1, `size` [radius just after the burst, radius at
  the end] for a mass of 1, `speed`, `life` (s; a puff is short: about a second for stone and concrete), `rise`, `settle` (sinking of the heavy part), `wind`, `opacity` (optical density
  scale), `trail` (share of the chunks shedding dust in flight), `landing` (mass of the poof a chunk raises where it
  lands)}, `sparks` {`count`, `speed`, `life`, `ricochet` 0..1, `spread`, `gravity`, `bounce`, `color`}, `splash` (fluids)
  {`kind` `water` | `lava`, `sheet` / `column` (heights for energy 1), `drops`, `dropSpeed`, `ripple` (ring speed),
  `rippleLife`, `foam` 0..1}, `blockTexture`
  (debris = a random quarter of the struck block's particle sprite, tinted by its block colour) or `sprite` + `color`,
  `blockDustColor` (dust from the block's map colour), `sound` (`block` = its hit sound, a sound id, or empty),
  `soundVolume`, `flash` / `flashColor` (a `LightSources` flash, e.g. sparks in the dark). Counts may be fractional:
  they are rounded at random.
* `blocks` / `entities` / `fluids`: ordered rules, first match wins. Blocks `id:`, `tag:`, `sound:<SoundType field name>`
  (e.g. `sound:metal`), else `fallbackBlock`; entities `id:`, `vehicle`, `armored` (armour ≥ 10), `living`, `any`, else
  `fallbackEntity`; fluids `id:` / `tag:`. Answers are cached per block state / fluid and dropped on reload and logout.
* `ammo.<id>` {`energy` (speeds), `count`, `size`, `dust`}, `fallbackAmmo`.
* `environment` {`wetDust`, `wetDarken`, `wetSplash` (rain on the struck surface: less, darker dust and muddy drops),
  `snowDust` (snowing biome: more, whiter powder), `waterDrag` and `bubbles` (under water: debris sinks slowly, bubbles
  rise instead of dust), `wind`, `rainWind`, `thunderWind` (a slowly turning, gusting wind that carries dust and light
  debris)}.

**Shaping.** The spray leaves in a cone round `normal·(1 − 0.85·g) + reflection·0.85·g` (`g` = 1 − cos of the angle
of incidence): square hits burst out round the surface normal, glancing ones streak along the surface towards the
ricochet; debris speed × (0.55 + 0.45·cos), sparks lean to the ricochet by `ricochet` and get more numerous the more
glancing the hit. Entity hits spray out of the body towards the shooter. Nothing is ever sent into the surface.

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

**Fluids** are their own thing, shaded like the water (`impact_fluid`). Water: a splash event drawn from its age alone —
the crown sheet rising round the hole for ~0.3·√E s and falling back (16 × 2 quads, flaring out, its rim ragged and
breaking up), the column (Worthington jet, 8 × 2 quads) shooting up after 0.09 s for rounds above energy 0.6, three ring
crests spreading at `ripple` blocks/s on the surface plane for `rippleLife`, and white water over heavy rounds (energy
above 0.9) with bubbles rising under it; drops thrown from the crown's rim and the column's top fly ballistically and,
falling back below the water's surface, make a small secondary splash (crown and rings, no column); on dry ground they are
gone. Energy E = ammo energy × (0.75 + 0.25 × ammo size). Lava: molten spatter (opaque, glowing by its temperature,
cooling over its life and crusting dark, lying as a splat where it lands, gone back into the pool), a glowing crater
spreading on the surface, and the material's embers and smoke.

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
| sparks | `blockland:core/impact_spark` | `ONE, ONE` colour, alpha × (1 − a) (at most 0.3 per spark); depth test, no write | additive streaks stretched over about a tick of motion, white-hot core, fogged; lower the target's alpha so they bloom (§3) |

Per-draw parameters ride in `DynamicTransforms` (`impact_fx.glsl`: camera axes, time, soft-depth flag, mode). Programs
`impact_debris`, `impact_dust`, `impact_spark`, `impact_fluid` in `tools/shaders/programs.txt`.

**Settings** (the 画质 tab, section 弹着效果; `config/blockland-settings.json` key `impacts`, `client/impact/ImpactSettings`):
quality `off` / `low` / `medium` / `high` / `ultra` — count multiplier 0 / 0.35 / 0.65 / 1 / 1.5, pool budget 0 / 500 /
1200 / 2500 / 5000 per kind, full detail up to 20 / 36 / 56 / 88 blocks fading to 15 % at twice that, nothing past 160
blocks — plus switches for dust, second collisions and weather / surroundings. At most three impact sounds play per tick.

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
