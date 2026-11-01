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
| `beforeComposite` → post chain | the first HUD layer (root of `ForgeLayeredDraw`, also with F1) | decode → AA / upscale → motion blur (§11) → water on the lens (§27) → bloom → exposure → tone map and grade → sharpen / FXAA, written into Minecraft's real main target; main target swapped back; scene uniforms off |
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
* Lights: `BlLights[3i]` (camera-relative position, radius), `BlLights[3i+1]` (linear colour × intensity, a spot's inner
  cosine), `BlLights[3i+2]` (a spot's direction, outer cosine; w −2 = a point light). Read through `bl_pointLights`
  (scene.glsl) and `bl_spot`; nothing else indexes the array.
* Materials: `BlMaterials` (sprite rects of the block atlas with emission / kind / reflectivity, §7).
* Light colours and sky (§14): `BlBlockLight` (rgb block light tint × strength, 1 = vanilla's; `a` 1 = the volumetric
  clouds replace vanilla's this frame), `BlFog` (haze extinction at the camera's height, height falloff, sun in-scatter,
  `w` 1 = on). `BlSunColor` already carries the player's sun / moon colour temperature.
* Ground (colour bleeding): `BlGround` rgb the albedo of the ground round the camera (linear; `light/Sky` samples 7 × 7
  columns 2 blocks apart, the first block within 10 below eye height, map colour × 0.6, nearer columns weigh more, every
  4 frames, eased), `a` the share of columns that found ground (0 in the air). Weapons take their bounce light from it,
  entities tint their undersides with it.
* Water: `BlWaterColor` rgb the biome's water colour round the camera (linear; §15's water optics), `a` unused.
* Wind (§18), last in the block: `BlWind` (heading, steady strength, the setting), `BlWindInfo` (counts), the tunables
  `BlWindBend`, `BlWindStack`, `BlWindPush`, `BlWindSpring`, `BlWindKinds`, then `BlGusts[]`, `BlPushers[]`, `BlSways[]`;
  the plant columns are a texture, `BlPlantColumns` on unit 15 (patched like the material map, read by terrain.vsh).

* Soft shadows: `BlCloudShape.w` (a free component of the clouds' vec4) is the light size: the tangent of the
  light's effective angular radius, 0 = the fixed filter (§6).

BlockLand's own pipelines declare `withUniform("BlockLandScene", UNIFORM_BUFFER)` and `withSampler("BlShadowMap")`
and bind `SceneLighting.uniforms()` / `SceneLighting.shadowMap()` on every pass.
The raw shadow depth `BlShadowDepth` (sampler2D, declared in scene.glsl) is never bound by a pipeline: every program
that has it is pointed at unit 17 (§3), BlockLand's own included. When nothing provides them (graphics
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
* sampler `noisetex` → texture unit **16**: Bliss's noise texture for the ported water (§15; `client/graphics/water/
  BlissNoise`, `assets/blockland/textures/bliss/noises.png`, linear and repeating), bound with raw GL like the others
  (unit 16 lies past Blaze3D's cache and past the 16 fragment units GL guarantees; with fewer units the uniform points at
  the material map's unit instead — a valid draw, wrong waves — and the log says so);
* sampler `BlShadowDepth` → texture unit **17**: the shadow atlas once more (the very texture on unit 12, the 1×1 lit
  stand-in included, bound together by `GlSlots.bindShadow`), read as raw depth for the soft shadows' blocker search
  (§6) through a GL sampler object of its own on that unit (`GL_TEXTURE_COMPARE_MODE` none, nearest, clamp to edge), so
  the texture keeps its comparison mode for `BlShadowMap`. BlockLand's own pipelines that include scene.glsl but do not
  list the sampler get the same unit from the per-frame program scan (like `noisetex`). With 17 or fewer fragment units
  the uniform points at the material map's unit, `BlCloudShape.w` is written 0 (the fixed filter) and the log says so.
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
* Texture scrolling: `DrawList.uvScroll` (through `GpuModel.Style.uvScroll(material)`, in the material's uvs before its uv
  scale) shifts a draw's texture coordinates along v by a fraction of a repeat — a tank track's links running round. It
  rides in ModelOffset.x's fraction (the block-light texel there is whole; gun.vsh adds `fract(ModelOffset.x)`), so every
  other draw is exactly as before.
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

A light is a point light or a **spot** (`LightSources.Light`: optional direction and inner / outer cone cosines; build one
with `light.spot(dx, dy, dz, innerDegrees, outerDegrees)`; the 8-value constructor is a point light, exactly as
before). The shaders (`bl_spot` in scene.glsl, the volumetric haze) give a spot a soft edge from the inner to the outer
cone and a long throw — falloff `window² / (1 + 0.05·d²)` instead of a point light's `window² / (1 + d²)`, a focused beam
does not spread — and nothing outside its cone; in the volumetric haze a spot is a visible beam. An item light may be a
spot (`"cone": [inner, outer]` half-angles in degrees): held, it shines along its holder's view from the hand (this
client's own first-person view: from just below and beside the camera along its look); lying on the ground it is a
faint glow round the item. An item light may need a switch (`"switch": "<data component id>"`: a boolean component of
the stack that must be true — the flashlight's `blockland:lit`, asset spec §36), so the light module knows nothing of
the item. A switched item light is gameplay, not decoration: players' switched-on lights are collected (and shine in the
volumetric haze) even with 动态光源 or 手持光源 off — those settings govern the features' lights and the other light
items; only world lighting (光照) must be on. Unlit worlds and unpatched shaders read none of this (pixel-identical to
vanilla). Dynamic light adds to vanilla's light and the sum is capped at full brightness, so in full daylight a lamp
cannot brighten what the sun already lights. F3: `N lights (M spots)` this frame.
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

**Soft shadows (percentage-closer soft shadows, `bl_shadowSoft` in scene.glsl).** Real shadows are sharp where the
caster touches the receiver and widen with the distance between them (the sun is a disc; the sky's light softens them
further). While `BlCloudShape.w` (= tan θ, θ the light's effective angular radius) is above 0, a cascade lookup:

1. Blocker search: 12 raw-depth taps of `BlShadowDepth` — the centre, three at the fixed filter's radius, eight on a
   spiral out to the search radius (min(1.5 blocks, tan θ × 48 blocks), never below the fixed radius) — all rotated by
   the per-pixel noise. A tap is a blocker when its depth is nearer the light than the receiver at that tap; the
   average of (receiver − blocker) is the caster's height above the surface. No blocker: fully lit (no filtering);
   every tap a blocker: fully shadowed.
2. Penumbra radius = that height (blocks) × tan θ, clamped between the fixed filter's radius (`BlShadowParams.y`
   texels — the 阴影柔和度 setting — so contact edges look as before) and the search radius.
3. Filter: 16 comparison taps of `BlShadowMap` (a Vogel disc, the same rotation) over that radius.

Units come from the cascade matrix, which is BIAS × ortho(±half, 0..depth) × a rigid light view
(`shadow/Cascade.update`): its x and y rows are the light's axes × 0.5 / half (local units per block), its z row the
axis along the rays × 1 / box depth. So depth units per block = length of the z row
`(M[0][2], M[1][2], M[2][2])`, and atlas uv per block = 0.5 × length of the x row (the tile is half the atlas).
Every tap is compared against the receiver plane (the surface's normal in the light's axes gives its depth slope per uv
offset, the normal's component along the light clamped to ≥ 0.25), so wide taps neither shadow a surface tilted against
the light nor see through it; callers that pass a zero normal get a flat plane. Cascade selection, normal offset, depth
bias and the last cascade's fade are unchanged.

Light size: the 画质 setting 阴影半影 (`GraphicsSettings.shadowPenumbra`, 0..1, default 0.5) maps to θ = 0.6° + 3.4° ×
setting (sun and moon alike; the true sun disc is 0.27°, the rest stands for the sky's softening); 0 = off, exactly the
previous fixed 8-tap filter. Cost per lookup: lit 12 raw taps, penumbra 12 + 16, deep shadow 12 (was 8 everywhere);
vertex-stage callers (particles, graffiti, bomb smoke) run the same with an unrotated pattern.

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

`reflectivity` 0..1: for `water`, `glass` and `metal` how strongly they reflect; for `plain` blocks it is a smoothness
hint (0 = rough, no sheen — grass, dirt, wood, leaves stay as they are). The material map (unit 13) holds every entry; only
the first 96 sprites (`SceneLighting.MAX_MATERIALS`) also go into `BlMaterials` of the scene block (no shader reads that
list today), so new entries go after the existing ones.

**Smooth blocks** (terrain.fsh, kind `plain` with a reflectivity, any role, not on glowing texels, dry or wet): the
reflectivity `s` (× (1 − emission)) gives the roughness `mix(0.7, 0.08, s)` (`bl_blockRoughness`); the sky
(`bl_reflection(reflect(−v, n), outdoors, FogColor)`) is mixed in by Schlick's Fresnel with f0 0.04 × `s²` × outdoors ×
0.7, and the sun adds `BlSunColor × bl_sunSpecularF0(n, v, roughness, 0.04) × sun × shadow strength` (GGX, Smith
visibility, capped at 8). Data: blue ice 0.85, packed ice 0.7, glazed terracotta (16) 0.75, obsidian 0.65, polished
andesite / diorite / granite / deepslate / blackstone / tuff / basalt 0.5, quartz (block, pillar, chiseled, bricks) 0.45,
prismarine (3) 0.4, concrete (16) and smooth stone 0.35. Water, glass and metal keep their own shading (ice is `glass`).
Cost: nothing for blocks without a reflectivity; one GGX and a sky gradient for the smooth ones.

**Leaves and plants let the sun through** (terrain.fsh; what counts as foliage is data: every sprite with a `wind` entry
(§18) except water plants — leaves, grass, ferns, flowers, crops, saplings, vines, sugar cane, bamboo). Foliage is
shaded on the face's true plane turned towards the viewer (from the screen derivatives of the undisplaced position, so a
crossed plant is lit as its diagonal, not as the nearest axis its vertex normal claims). `bl_foliageSun` does it with one
shadow lookup:

* sun on the viewer's side (`n·L ≥ 0`): `facing × bl_shadow(pos, n)` with a wider wrap than other blocks,
  `facing = clamp(1.5 n·L + 0.55, 0, 1)` (others: `2.5 n·L + 0.35`);
* sun behind the face (`n·L < 0`): the shadow is looked up at the face's back — for a leaf block its other side
  (`pos − n`), for a blade the face itself — with the normal `−n` (offset towards the light), so the outer leaves of a
  canopy glow and the inner ones stay dark under the leaves in front of the sun. The front takes `facing × back`; the
  light let through is `back × (pow(max(−v·L, 0), 6) + 0.35 · max(−n·L, 0))` (a forward-scattering lobe round the sun
  plus a diffuse part).

The transmitted light (`bl_foliageTransmission`) is added to `bl_skyShade`'s display-space light: the face's vanilla
sky light (`vertexColor − blockColor`: biome tint, shade, the sky's brightness) × `bl_sunTint()` × 0.4
(`BL_FOLIAGE_TRANSMIT`) × the shadow strength (overcast and moonless: none) × `smoothstep(0.5, 0.95, sky light)` ×
a chroma that makes it look like light through a leaf (the surface colour saturated 1.4× and shifted yellow
(1.06, 1.04, 0.82), as a per-channel ratio clamped to 0.5–1.6). It never raises the light more than a fifth over
vanilla's sky light on that face (`min(light + t, max(light, 1.2 · sky))`, then the usual `min(light, 1)`). Cost:
foliage fragments do at most one shadow lookup, as before (faces turned more than ~8° away from the sun did none and now
do one).

## 8. Weapons

The viewmodel and third-person weapons use the `blockland:core/gun` pipeline (`GunPipelines`): with world lighting on
they take the world's light the way the terrain does (§3's split of the lightmap into its sky and block parts): the sun's
share warm and directional (`SUN_SHARE` of the sky part × `BlSunColor`, with the shadow at the weapon's world position),
the sky's share cool (`BlAmbient.rgb` × `SKY_SHARE`) over the part of the dome a face sees (`SKY_UP` overhead,
`SKY_SIDE` upright — the bright band round the horizon — nothing facing down), and the ground's bounce (`BlGround` ×
the ground's irradiance: the sun on it, `SUN_SHARE × sun elevation`, where the weapon's shadow says it reaches and
partly even then, plus the sky's) over the half of the world below a face's horizon — in the ground's colour (colour
bleeding: green over grass, warm over sand). A side face turned to neither sun nor sky lives on the horizon and that
bounce, as outdoors (about 40 % of a sunlit top at noon). The block light tinted (`BlBlockLight`), the dynamic lights, and
reflections of the sky above and the lit ground below (Fresnel through the split-sum response) — so a weapon turned to the
sun is bright and warm, one in shade dim and cool, as the blocks round it. A studio rig in view space is left only as a faint fill (`STUDIO_FILL`, 3.5 %) so a weapon in the dark
keeps its shape; without world lighting the rig lights it alone. Emissive parts and skin effects write emission alpha. The gate is
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

阴影半影 (`shadowPenumbra`, 0..1, default 0.5, after 阴影柔和度): soft shadows' light size (§6), 0 = off.

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

Skipped (and the entity history dropped) without the world depth, on a camera cut or when both shutters are 0.

**Objects' own motion** (`client/render/ObjectMotion`, a contract like `HandMotion`): rigid things in the world that move
on their own — vehicles — report, each frame they are drawn in the main view (not the shadow pass), their pose (model →
world) and a box in their model space that holds them and whatever rides on them. At the start of the post chain
`post/ObjectMotionBlock` pairs each with its pose a frame before (drawn both frames, moved or turned, not a teleport over 8
blocks, within 160 blocks; the nearest 8) and writes one uniform block, `BlObjectMotion` of `blockland:bl_object_motion.glsl`
(the current projection's inverse, the camera reprojection, per mover current camera-relative world → box space and box
space → where that point was a frame ago). TAA (`gfx_taa`, the world pixels), the DLSS motion input (`gfx_dlss_motion`,
also for Frame Generation and DLSS 5) and the motion blur's velocity (`gfx_mb_velocity`, before its entity boxes) move a
world pixel that lies in a mover's box back by the mover's own motion, turning included, then reproject it; every other
pixel is exactly as before. So TAA / DLSS / motion blur carry the camera's, the weapon's and the vehicles' motion.

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

**A body bleeds only when it was really hurt.** Whether a hit took anything off its target is the server's word
(`combat/HurtCheck`: health + absorption before against after, or the target killed; `hurtServer` returning true is not
enough — body armour can soak a round whole, a game can turn the damage down to nothing, a protected or creative player
takes none). The gun and knife services send it to the attacker in their hit confirmation (`S2CHit`: target id,
`wounded`, the hit point as the attacker saw it) and to everyone else in the shot / impact packet (`S2CShot.wounded`,
`S2CImpact.wounded`). On the attacker's client a hit on a body that bleeds (`Impacts.bleeds`: its material has `blood`)
is held (`client/impact/HurtConfirm.await`: blood, mist, wound, flesh sound, `bodyDecal`) and played by the confirmation
(`HurtConfirm.settle`: the oldest hit held on that target; with none held — the server's trace struck someone else, or
the 1.5 s hold ran out — the weapon plays it at the server's point); a hit never confirmed, or confirmed unhurt, never
bleeds. Other players' hits play at once by the packet's flag. Bloodless targets (their material's debris, sparks,
decals) and the attacker's feedback (hit sound, viewmodel kick) do not wait.

**The rounds** (the gun's `ammo` attachment, VIEWMODEL_SPEC §56). A round's impact profile is its caliber's with the
round's modifier multiplied in: guns pass `GunStats.impactProfile()` (`rifle+incendiary`), and `ImpactCatalog` combines
`impacts.json` ammo `base+modifier` field by field (a modifier names only what it changes; `incendiary`: 3.5× the sparks,
`armor_piercing`: 2× the sparks, a little more energy). Every shot's streak (`GunParticles.Tracer`, local and others')
takes the round's colour and width when the skin brings none (an incendiary's orange); a tracer round draws the long,
bright streak (9 blocks, full opacity) in its own colour for everyone, whatever the skin. A waterproof round's path
(`GunStats.medium()`: water does not slow it, it never skips) is the same on the server and in the client's prediction,
so its splashes and the trail under water follow it.

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

**Sun and moon** (`CelestialBodies`, setting 着色器日月, on by default; `mixin/level/SkyRendererMixin` only forwards):
vanilla's `renderSun` / `renderMoon` are replaced by one quad each in the same sky batch (`blockland:core/celestial`,
premultiplied alpha: the discs cover the stars, the glow adds), at `SunPath`'s sun and opposite it — where the shadows come
from (vanilla's sky turns its sun on an untilted arc). Sun: a limb-darkened disc (1.6° radius) in a corona fading over
10°, its colour the light after the air it crossed (`exp(−τ·airmass)`, τ = 0.021 / 0.052 / 0.120, Kasten-style airmass),
so it reddens towards the horizon. Moon (2.5° radius): a sphere lit from vanilla's phase (0 full … 4 new; waning lit from
the left), a soft terminator, Lommel–Seeliger brightness (as bright at its edge as at its centre when full), maria and
craters from fixed noise, earthshine on the dark side, a halo as bright as its lit share. Both take vanilla's alpha (1 −
rain). The decode pass already lifts the brightest sky pixels into HDR (bloom). Off, or graphics off: vanilla's textures.

**Physical sky** (物理天空, `blockland:bl_sky.glsl` in the decode pass, `post/PostChain` sets its parameters; on by default):
the overworld's sky pixels — world depth 1, no far terrain, not the hand (the decode's sun-glow test) — are re-shaded by
single Rayleigh + Mie scattering before the sun's HDR lift, the volumetric clouds and the volumetric fog are composited
over them. Model (km): planet 6360, atmosphere top 6420, eye 0.3 above the ground (the same sky at any build height);
Rayleigh β (5.8, 13.5, 33.1)·10⁻³/km, scale height 8; Mie β 21·10⁻³/km, albedo 0.9, scale height 1.2, Henyey-Greenstein
g 0.76; Rayleigh phase 3/16π (1 + μ²). 8 samples along the view ray (quadratically spaced, dense near the eye, midpoint
optical depth from the eye), the light's optical depth to space in closed form (Chapman's function, Schüler's
approximation, with the grazing branch below the local horizontal), the planet's shadow by a ray–sphere test, so the sun
keeps lighting the high air until 8° under the horizon. View directions come from inverse(level projection × view
rotation), the sun from `SunPath` (where `CelestialBodies` draws it), the moon opposite it. No disc is drawn here.
* **Anchored to vanilla.** Day: vanilla's colour straight up (the sky colour fogged as vanilla's disc is at its centre,
  16 / sky fog end) is the zenith; elsewhere its luminance × (scattered luminance along the ray / straight up)^0.35
  (at most 2.2), its colour vanilla's chroma × the scattered light's chroma ratio (ray / zenith, 0.05 … 4), mixed with the
  scattered light's own chroma by 0.6 (1 − y)² towards the horizon. So the zenith is exactly vanilla's (biome tint, time,
  weather, lightning), the horizon pales, the anti-sun sky deepens, the sky round the sun brightens (Mie aureole, on top of
  the shader sun's corona) and the sunset side turns orange. Night: the moon lights a dark blue sky of its own,
  (0.30, 0.45, 1.0) × 0.003 linear at a full moon high up, × (scattered luminance / 0.01)^0.35, half the scattered
  light's chroma, × the moon's brightness (phase), fading with its height; 12 % of it stays on moonless nights (airglow).
* **Weather.** Overcast = 0.7 rain + 0.3 thunder: Mie × (1 + 5 overcast), g − 0.25 overcast, the brightness exponent
  down to 0.12, the colour 75 % back to vanilla's grey; the night sky × (1 − 0.6 overcast).
* **The horizon and the fog.** Towards the horizon the result fades into vanilla's own gradient there — the sky colour
  fogged to the fog colour, the fog colour itself round and below the disc — over a band 2.5 × as high as the one vanilla
  fully fogs at the render distance (0.06 … 0.4 in the ray's height), so the sky meets the fogged terrain exactly as before;
  at and below the horizon the pixel is vanilla's.
* **What vanilla drew over its sky is kept.** Vanilla's gradient is rebuilt per pixel from the numbers it was drawn
  with — the sky disc (octagon fan 16 blocks up, corners 512 out, fog distance interpolated over it, fogged by the sky fog
  end towards the fog colour, the clear colour outside it) and the sunrise fan (its colour and alpha, its side by the
  sun angle's sign; its 16-gon taken as the cone through its rim, within 0.005 alpha) — and replaced:
  pixel − vanilla gradient + physical sky, the difference dropped where it is within vanilla's 8-bit steps (1.5 … 4 / 255,
  so no banding is brought back). Stars, the sun and the moon (shader or vanilla textures), rain streaks and anything
  else drawn over the sky stay as they were, with the new sky under them. Below the horizon the dark void disc is left alone.
* **Inputs.** Vanilla's fog of the frame's level render (colour, sky fog end, open-air type) is read by `PostChain` from
  `ViewportEvent.RenderFog` at the lowest priority (after `FogSwitch` and the far terrain); the sky colour
  (`getSkyColor` at the camera), the sunrise colour, the moon's brightness, rain and thunder from the level. Decode
  parameters: `Matrix` = NDC → camera-relative world, `Params[2]` (sky colour, display; sky fog end, 0 = off),
  `Params[3]` (fog colour, display; moon brightness), `Params[4]` (sunrise fan colour; alpha, negative on +x),
  `Params[5]` (towards the sun; overcast).
* Off (vanilla's sky as drawn) with the setting off, outside the overworld sky type or without sky light (Nether, End),
  with the camera in water, lava or powder snow, in dimension or boss fog, or blind (blindness, darkness: no sky is drawn).
  Cost: sky pixels only, two marches by day (ray and zenith; three in twilight with the moon), one by night: about
  0.03–0.1 ms at 1080p on a fast GPU depending on how much sky is in view (span `bl/post/decode`).

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

**The clouds are Bliss's code** (Bliss v2.1.2, a Chocapic13' Shaders edit by Xonk, based on Chocapic13's shaders; license
and credits in `shaders/include/bliss/license.md`). `lib/volumetricClouds.glsl` is ported as `blockland:bliss_clouds.glsl`
— not a re-implementation: its layer constants, `cloudCov` / `cloudVol` / `GetCumulusDensity` / `GetAltostratusDensity`,
`densityAtPos` (3D noise from the 2D noise texture), `phaseg` / `DoCloudLighting`, `renderLayer` / `renderClouds` (with
its blending order, distant fog and curvature tweak) and `GetCloudShadow` / `GetCloudShadow_VLFOG`, kept as written with
Bliss's `settings.glsl` defaults; BlockLand's changes are marked "BlockLand:" in the file and are only the interface:
* Iris's uniforms → BlockLand's: `cameraPosition` = `BlClouds.xyz` (the camera's world position, y shifted by 192 − the
  dimension's cloud height, so Bliss's absolute layer heights 250 / 500 / 2000 sit as in the overworld), `worldTime` +
  `worldDay % 100` = `BlClouds.w` (game time in ticks modulo 100 days: the clouds move where the day clock is stopped),
  `rainStrength` = `BlCloudShape.y`, `WsunVec` / `sunElevation` = `BlSunDir` / the sun's elevation, `gbufferModelViewInverse`
  = identity (positions are camera-relative world), `noisetex` = Bliss's `shaders/texture/noises.png` shipped byte for byte
  (`textures/bliss/noises.png`, linear, repeating; `client/graphics/water/BlissNoise`, shared with the ported water, §15);
  the renamed names are `#undef`'d at the file's end.
* Settings: Bliss's defaults (layers 0 / 1 / 2 coverage 0.7 / 0.7 / 0.3, density 0.5 / 0.5 / 0.1, `Rain_coverage` 0.95,
  `fbmAmount` 0.5, `Cloud_Speed` 1, `CLOUD_SHADOW_STRENGTH` 1 = `BlCloudShape.z`); 云量 scales the three coverages by
  setting / 0.45 (the default setting is Bliss's default); `Daily_Weather` is off (its fixed-coverage branch); 云层质量
  picks Bliss's ray-march steps (`minRayMarchSteps` / `maxRayMarchSteps`): 低 its LQ 10 / 30, 中 its HQ 15 / 15, 高 30 / 30.
* Globals Bliss initialises from uniforms are assigned in `blissClouds_init()` (GLSL wants constant global initialisers),
  called first by `renderClouds`, `GetCloudShadow` and `GetCloudShadow_VLFOG`.

**Cloud shadows** (`scene.glsl`, which imports `bliss_clouds.glsl` — with `CLOUDSHADOWSONLY` except in the clouds' own pass):
`bl_cloudShadow(pos)` is Bliss's `GetCloudShadow` (surfaces: inside `bl_shadow`, so terrain, entities, weapons, dust,
graffiti and the far terrain get it, with or without a shadow map), `bl_cloudShadowFog(pos)` its `GetCloudShadow_VLFOG`
(the volumetric fog, as Bliss's fog takes it); both 1 while the volumetric clouds do not run or the light is on the
horizon. They need `noisetex`: on texture unit 16 (bound with the scene uniforms by `light/GlSlots`, raw GL like units
12–15), which `light/VanillaPrograms` points the lighting pack's programs and every BlockLand pipeline that does not
declare the sampler itself at (a pipeline compiled during a frame reads it from the next frame on).

**Volumetric clouds** (`VolumetricClouds`, a frame stage after the level, registered before the light module; settings
体积云 + 云层质量 + 云量; needs world lighting): `gfx_clouds` is Bliss's cloud pass (`composite2.fsh` with
`CLOUDS_INTERSECT_TERRAIN`: its R2 and blue-noise dithers and the `renderClouds` call), marched per pixel at 1/3 or 1/2 of
the internal resolution; Bliss's `viewPos0` is the world's distance along the ray (world depth, else the far terrain's, else
the sky), so the clouds stop at the world. The light handed to `renderClouds` is `BlSunColor` (Bliss: `lightCol / 80`) and
`BlSkyColor` (Bliss: `averageSkyCol / 30`), so they follow the time of day, the weather and the player's light colours.
The scene block carries the inputs (`FrameContext.clouds`, written at `cameraReady`): `BlClouds` = Bliss's
`cameraPosition` and clock, `BlCloudShape` = the 云量 setting, rain, the shadow strength. Then `gfx_clouds_temporal` blends
12 % of the new march into last frame's (reprojected by the camera's turn only, clamped to this frame's 3×3
neighbourhood). The decode pass multiplies the world and the sky by their transmittance and adds their light (Bliss's
`color * clouds.a + clouds.rgb`), before the volumetric fog. Whether they run is decided at frame start
(`GraphicsStatus.cloudsActive`) and written into `BlBlockLight.a`, so the lighting pack's cloud shader hides vanilla's
clouds in exactly those frames; a scope's extra level render gets a copy of the world block with the flag off (the clouds
are only marched for the main view). Programs `gfx_clouds`, `gfx_clouds_temporal` and `clouds` (vanilla's, overridden) in
`tools/shaders/programs.txt`.

## 15. Rain and water (the lighting pack, `client/render/WaterRipples`, `client/fluid`)

**Fluid normals.** Vanilla's `LiquidBlockRenderer` gives every fluid face the normal (0, 1, 0), falling sheets
included; the lit terrain program takes the face's true normal from the screen derivatives of its position for water and
for lava (a glowing face whose claimed up normal is not level), so a waterfall is never shaded as a level surface seen
edge-on.

**Water is Bliss's code, ported** (Bliss v2.1.2, "Chocapic13' Shaders edit" by Xonk, based on Chocapic13's shaders; not
BlockLand's own code — licence and credit in `assets/blockland/shaders/include/bliss/license.md`). The shared parts live in
`blockland:bliss_water.glsl`, kept as close to the original as the interface allows (Bliss's names and structure; BlockLand's
changes marked `BlockLand:` in the source): `lib/waterBump.glsl` (`getWaterHeightmap`, `getWaveNormal`, `waterCaustics`),
`dimensions/all_translucent.fsh` (`getParallaxDisplacement` with `PW_DEPTH` 1.5 / `PW_POINTS` 2, `applyBump`, `GGX`),
`lib/overworld_fog.glsl` (`fogPhase`), `dimensions/fogBehindTranslucent_pass.fsh` (`waterVolumetrics_test`), with Bliss's
`settings.glsl` defaults (`WATER_WAVE_STRENGTH` 1, `WATER_WAVE_SPEED` 1, `WATER_CAUSTICS_BRIGHTNESS` 1, `Water_Absorb_*`
0.2629 / 0.0565 / 0.01011, `Dirt_Amount` 0.14, `Dirt_Absorb_*` 0.65 / 0.85 / 1.05, `Dirt_Scatter_*` 0.7, `rayMarchSampleCount`
2, `SNELLS_WINDOW` on) and `Vanilla_like_water` on, but the surface is drawn by the shader, not a texture (§16): its grey `BL_WATER_GREY` 0.62 × the biome
colour (vertex colour), alpha `BL_WATER_ALPHA` 0.72 — one colour, the waves carry the detail. Where the composite draws the
water's volume (above water, `BlWater.w` on) the surface's own colour is only a film (alpha 0.06 before the Fresnel),
from below 0.18; else vanilla's alpha.
Interface: Iris's `cameraPosition` → `BlCamera.xyz` (wrapped every 2048 blocks), `frameTimeCounter` → `bl_time()`, `noisetex`
→ Bliss's own `shaders/texture/noises.png` shipped unchanged (texture unit 16, §3), its shadow map → `bl_shadowMap` ×
`bl_cloudShadow`, its sky texture `colortex4` → `bl_reflection`, its screen-space `rayTrace` → BlockLand's `bl_ssr` (§17).

* **The surface** (terrain.fsh, role 3, kind water; Bliss's NORMALS, ALBEDO and SPECULAR LIGHTING blocks): the wave normal
  (three noise octaves turned by the golden angle, wave sizes 48 × 12, 12 × 48, 32 × 32, the swell by a 600-block noise; the
  derivative step growing with distance), a parallax displacement along the view in tangent space, waves flowing along
  every face that is not exactly level (`posxz.xz -= (posxz.y + t·3) · normalize(normal.xz)`, so falling sheets stream
  down), bump × 10. Vanilla's vertices carry no tangents: a level face's tangent runs along world x (binormal z), a side
  face's horizontally along it; the face normal is the true one facing the camera (from the screen derivatives). On level
  faces BlockLand's rain drops (`bl_rainSlope`) and ripples (`bl_rippleSlope`, below) are added on top, faded by the pixel's
  footprint (rain between 0.04 and 0.16 blocks per pixel, ripples 0.08–0.35). Specular: f0 0.02, roughness 0, Fresnel
  `(1 + n·v)⁵` mixed from f0; under water (`BlCamera.w`) Snell's window `(1.5 + n·v)²⁵`; the sun by `GGX` (roughness at least
  0.035) × the direct light (`BlSunColor`, N·L remapped as Bliss does, the shadow map falling back to the sky light beyond
  the shadow distance, the clouds' shadow cubed); above water the sky reflection, under water (outside Snell's window: total
  internal reflection) the water body round the eye (`bl_waterBody`, BlockLand; Bliss used a fixed `exp(−8 · Water_Absorb)`,
  which turned the surface seen from below into a flat blue); mixed with the water's own colour by Bliss's `indoors` (sky light), the screen-space
  reflection over it, the alpha raised by the Fresnel (`a + f − a·f`) and the colour divided by it so blending does not
  darken the reflection. Computed in linear light from the lit vanilla colour (display → linear and back); `BlWater.z`
  (the reflections setting) scales the Fresnel and the sun.
* **Water optics** (`blockland:bl_lighting.glsl`, shared by the surface, the composite and the under-water pass): per
  block, absorption `BL_WATER_ABSORB` (0.30, 0.075, 0.045) plus 0.06 × (1 − the biome's water tint) — the biome's colour
  takes a little more of what it lacks — and scattering `BL_WATER_SCATTER` 0.035; the biome's water colour round the camera
  is `BlWaterColor` (`light/Sky`: vanilla's `BiomeColors.getAverageWaterColor`, linear, eased). The water body (what deep
  water looks like, `bl_waterBody`): scattering / extinction × the tint × (sky light by the camera's openness + 0.35 of the
  sun) × 0.6. Caustics by depth under the surface (`bl_causticStrength`): `smoothstep(0.05, 0.9, d) · e^(−d/5)` — none at
  the surface, strongest a block or two down, gone by about fifteen.
* **What lies under it** (Fabulous composite, role 4, `bl_throughWater`; camera above water, `BlWater.w` on): water is told
  from other translucent blocks by the translucent layer's alpha (terrain.fsh makes water's lowest alpha bit 1, the rest 0:
  one 255th at most), so a surface reflecting a sunset or a green swamp is still water, and so are falling sheets. Refracted
  by the wave normal as before, with dispersion (red at 0.95, blue at 1.05 of the offset; two extra taps, only where the
  bent tap was accepted). Then the view ray's real way through the water (`way`, to the floor, 64 blocks where nothing is
  behind) and the floor's depth under the surface (`d`, world heights): the floor is lit by light that came down through
  `d` (`e^(−absorb · d · (0.5 + 0.5 / max(sun y, 0.25)))`, the sun's slanted way and the sky's straight one) and focused
  into Bliss's caustic pattern (`waterCaustics + 0.25`, ported from Bliss as before) by `bl_causticStrength(d)` × the sun's
  shadow at the floor; then `colour · T + body · (1 − T)` with `T = e^(−(absorb + scatter) · way)`. So a floor a block down
  is plain (a little cyan), five down blue-green, fifteen down the water body. Without Fabulous graphics or with
  refraction off there is no composite and the surface keeps vanilla's alpha.
* **Under water** (`client/graphics/water/UnderwaterCaustics`, a frame stage right after the level, before the hand): with the
  camera in water and world lighting on, the surface's height over the camera is found on the CPU (up the camera's water
  column, at most 48 blocks), the scene's colour copied and every world pixel below that height is lit as the composite
  lights a floor: the light's way down through its depth and the caustics by `bl_causticStrength` (`gfx_underwater_caustics`;
  the sun's share by its shadow and facing), its alpha (emission) untouched. Vanilla's water fog stays the fog under water.

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

## 16. Fluids drawn by the shader (the lighting pack's terrain.fsh)

Water and lava keep vanilla's textures in the atlas but, with world lighting, neither is drawn from them. Water (role 3,
kind `water`): one colour, `BL_WATER_GREY` × the biome colour, alpha `BL_WATER_ALPHA` (§15); the waves, reflections and the
volume give it its detail. Lava (`block_materials.json` marks it `kind: water` with an emission — a combination nothing else
has; it is drawn in the solid layer, so the water shading never applies): `bl_lava` in `bl_lighting.glsl`, in world space on
the face's plane (falling faces stream down at 0.9 blocks/s): a cooled crust of warped jittered cells at two scales, light
only in the cracks between plates (white-yellow core, orange edge, the fine cracks dimmer) and in molten pools welling up
where two slow noise fields overlap, churning in time; dark crust otherwise, a dull red near the cracks. Its heat is the
emission (the colour kept against the light, the emission alpha for bloom); no rain wetness. Without world lighting both
are vanilla's textures as drawn. (The former built-in pack "BlockLand 材质" with 1024-texel fluid textures and its setting
高清流体材质 are gone.)

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
dividing 2048 blocks (`BlCamera` wraps there) and real time (`BlFlags.w`, wrapped every 3600 s: every frequency of
time is a whole number of turns per hour, `BL_TIME_K`, so nothing jumps at the wrap). How far the turbulence has been
carried is integrated on the CPU every frame (3.5 blocks/s along the heading at that moment, wrapped at 2048:
`BlWindPush.w`, `BlWindSpring.w`) — never heading × time, which swung the whole pattern by up to thousands of blocks
whenever the heading wandered a little (the plants jerked 20 times a second). The fragment shader keeps the undisplaced
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
* **Chunk geometry** (entries that leave out work the picture never shows):
  `ENCLOSED_BLOCKS` — a section compile reads its section and one-block border as full-face bits
  (`terrain/FullFaces`: `getFaceOcclusionShape(side) == Shapes.block()`, `Block.shouldRenderFace`'s first test, which culls
  whatever the block); a block whose six neighbours are full against it and whose parts have no `getQuads(null)` quads is
  not handed to `renderBatched` (same mesh). `FACE_GROUPS` — at the end of a compile each quad of the solid and cutout
  meshes (back-face culled, unblended; translucent and tripwire keep vanilla's sort) gets one of seven groups
  (`terrain/FaceGroups`: quads in one axis plane whose two triangles wind towards one axis direction, and the rest —
  including every quad on a texture the wind moves, `client/render/PlantWind.moves`, since a bent quad can face a camera
  far off to the side; groups made before the moving textures changed are not used); the mesh itself is never reordered (a reorder changes which of two fragments at equal depth wins — the benchmark saw it on
  distant leaves). The groups are attached to the section's `SectionBuffers` at upload; `TerrainDraws.prepare` finds the
  groups the camera may see (it is not behind all of their planes by more than `FaceGroups.MARGIN`, 1 block, for
  bobbing) and draws the section through an index list of just those quads in the mesh's order, vanilla's `0 1 2 2 3 0`
  per quad (`terrain/FaceIndex`, one per section layer, in shared 8 MB index buffers so neighbouring draws keep one bound;
  the visible groups are worked out again once the camera moved a quarter block, with the margin widened by as much; a
  list is written again only when they change, within 1.5 ms per view; a list not ready draws the whole mesh). Back-facing quads make no fragments, so the same fragments reach the
  screen in the same order. Other loops (shadow casters, Blaze3D's validating loop, the entry off) draw the whole mesh.
  The real far chunks use the same groups per unit, one index list per region layer (§23).
  `FAR_HIDDEN_GEOMETRY` — a far unit is flooded from its border through cells that are not full on all six faces; cells
  neither reachable nor next to a reachable one (and not on the border) are not meshed (`far/HiddenCells`): the camera is
  never inside a far unit, so no line of sight reaches them. Switching the far entries meshes the real far chunks again.
  Offline check: `python -I tools/perf/check_mesh_opt.py` (FaceInfo windings, grouping, flood).
* **Entity shadows from GPU buffers** (`SHADOW_GPU_CASTERS`): the shadow map's entity capture (§6) used to rebuild
  every BlockLand model draw (vehicles, third-person weapons, tumbleweeds) as entity vertices on the CPU — a quad per
  triangle, each corner transformed by its matrix — and upload them every frame. A draw whose buffers hold exactly its
  source triangles (`DrawList.Entry.staticSource`: a model's own vertex and index buffers, nothing skinned or substituted)
  is instead handed to the capture (`ShadowPass.Capture.gpuCaster`: render type, the very matrix `VanillaDraws` would have
  used, buffers, index range, uv scale) and drawn by `ShadowPipelines.ENTITY_GPU` (`shadow_entity_gpu.vsh`). The shader
  places each vertex with JOML's `Matrix4f.transformPosition` operations as Minecraft runs them (`joml.useMathFma` unset:
  `m00·x + (m10·y + (m20·z + m30))`, every product and sum rounded on its own) under `precise` (ARB_gpu_shader5; without
  it the switch falls back), so the positions are the CPU's bit for bit; the shadow map is depth-only with `LEQUAL`
  (each texel ends at the least depth covering it, whatever the order or batching), and the old quads were the same
  triangles plus a degenerate twin — the same map. The render type's buffer is still asked for first, so the capture's
  mesh slots fill as before. Measured (§29, 1080p, 30 tumbleweeds in view): `bl/shadow/entities` 14.6 → 0.08 ms CPU,
  the frame 18.3 → 3.2 ms; `bench shadow_gpu_casters` with 40 still tumbleweeds round the camera: 16.10 → 2.72 ms
  (−83%), moving 16.99 → 3.41 ms, every compared view identical.
* **Quick measurements** (`perf/TimingProbe`, dev automation `timings <frames>`): every span averaged over the next frames
  as the scene is — no camera path, no switches — for comparing a scene with and without something in it.
* **Freezes** (`perf/Hitches`): a frame over 100 ms in a world is logged with its garbage collections, heap, window state
  and the time vanilla's frame flip spent in the buffer swap and in the window's event polls (`RenderSystemMixin` →
  `PerfHooks.displayStep`); the timings then record for a minute, so the next one is also logged span by span.
* **Background frame rate** (`perf/BackgroundLimit`, `PerfSettings.backgroundFrameLimit`, the 性能 tab, default 60 fps):
  while the window is not focused, vanilla's frame limiter (`FramerateLimitTracker.getFramerateLimit`) returns at most
  this (260 = no cap; never during a benchmark). Vanilla only slows a minimized window or an idle player: a covered
  window kept drawing flat out and, with two clients on one GPU, made the one in front wait in its buffer swap for up
  to 2 s. Not an `Optimization` (it changes how often the background window draws, nothing in a frame).

## 21. Display: aspect ratio and resolution (`client/graphics/display`, `com.blockland.mixin.display`)

The 画质 tab's 显示 section picks the shape and the size of the picture the whole game renders, independently of the
window (CS-style 4:3 stretched, black bars, lower or higher render resolutions). Not part of the presets; applies live.

* **Settings** (`GraphicsSettings`): `aspectRatio` (`AspectRatio`: native, 4:3, 5:4, 3:2, 16:10, 16:9, 21:9, 32:9),
  `aspectFit` (`AspectFit`: stretch = the picture fills the window, bars = it keeps its shape, centred, black around it),
  `resolution` (`native`, a preset `WIDTHxHEIGHT`, or `custom` with `customWidth` × `customHeight`, 640×360 … 7680×4320).
  The preset list is the common resolutions of the chosen ratio (the window's ratio for native), any size up to twice the
  window per axis (above the window = supersampling).
* **The virtual framebuffer.** What Minecraft takes for the window's framebuffer (`Window.getWidth/getHeight`: the main
  target, the GUI scale, the projection's aspect, screenshots, BlockLand's pipeline output) is the *virtual* size: the
  chosen resolution, or for native resolution with a ratio the largest box of that ratio the window holds (1920×1080 at
  4:3 → 1440×1080). Native ratio and native resolution = the real framebuffer, exactly vanilla. The real size is kept by
  `display/DisplayScaling` from GLFW's framebuffer callbacks (`WindowMixin`: `onFramebufferResize` stores the virtual size
  in place of the real one, so vanilla's own resize check runs `Minecraft.resizeDisplay` only when the virtual size
  changes; `refreshFramebufferSize`). A settings change takes effect at the start of the next frame (`MinecraftDisplayMixin`,
  head of `runTick`: nothing of the frame is drawn yet and the main target is Minecraft's own).
* **Presentation** (`GlCommandEncoderPresentMixin` in `presentTexture`): a presented texture of the window's size keeps
  vanilla's 1:1 blit untouched; otherwise the blit's destination becomes the rectangle — the whole window (stretch) or the
  centred fit (bars, the default framebuffer cleared black first) — with `GL_LINEAR` (nearest for a 1:1 picture in bars).
  Only Blaze3D's own state calls (`GlStateManager`'s framebuffer binding, put back), so `GL_STATE`'s caches stay true
  (§20). DLSS Frame Generation blits into its swapchain image with the same rectangle (`vkCmdClearColorImage` for the
  bars).
* **Mouse** (`MouseHandlerMixin`): GUI coordinates go through the destination rectangle (`x_gui = (x − rect.x) × guiWidth /
  rect.width`, screen coordinates; drags scale by `guiWidth / rect.width`); a click in a bar lands outside the GUI. A
  stretched picture fills the window, so it maps exactly like vanilla. Turning (raw deltas) is unchanged.

## 22. Far terrain (远景地平线: `com.blockland.farterrain`, `server/farterrain`, `client/graphics/far`)

Distant terrain beyond the loaded chunks, up to 32 768 blocks away, close to the real terrain in shape and colour, at a
cost that does not grow with the distance: the farther a tile, the coarser its cells. The server knows the terrain (real
chunks where they exist, the world generator's estimate where they do not) and streams compact tiles; the client caches
them, meshes them into Minecraft-looking block columns and draws them behind the vanilla terrain.

### Tiles (`com.blockland.farterrain.FarTile`, shared)

* Level `k` = 0 … 9, cell = 2^k blocks, a tile = 64 × 64 cells = 64·2^k blocks; tile `(tx, tz)` covers x ∈
  [tx·64·2^k, (tx + 1)·64·2^k). Key: `FarTile.key(level, tx, tz)` (level in the top 6 bits, tx and tz 29 bits each).
* Cell `(i, j)` (index `i + 64·j`, i along x) is sampled at **one column**, its centre: x = x0 + i·2^k + ⌊2^k / 2⌋ (the
  block itself at level 0). Point samples, so a coarse cell is a column of the real terrain, never an average.
* Per cell: `height` (y of the top face: the topmost motion-blocking block's y + 1, leaves and fluids included;
  `FarTile.EMPTY` = no terrain: void, unknown, outside the allowed region), `top` (state palette index: the top block),
  `under` (state palette index: on land the block 3 below the top — dirt under grass, stone in cliffs; leaves under
  leaves; under water the floor block), `biome` (biome palette index at the top), `water` (0 = no water, else the water's
  depth to the floor, 1 … 255).
* Palettes: block states as `Block.BLOCK_STATE_REGISTRY` ids (≤ 256 per tile), biomes as resource ids (≤ 256).
  `version` = a 64-bit hash of the content (identical content, identical version). Codec `encode` / `decode`: a small
  header, the palettes, heights delta-coded along rows, the byte planes, deflated.

### Server (`server/farterrain`, `FarTerrainService`, wired by `BlockLandServer`)

* **Sources**, per dimension, best first: (1) *live* — full chunks loaded on the server, queued when they load and when
  they are saved (`ChunkDataEvent.Save`: every change ends in a save), captured on the server thread within 1 ms a tick,
  loaded and modified ones swept again every 30 s, a changed chunk captured as it unloads; a snow layer on the top
  counts as the top (plants do not); (2) *saved* — full-status chunks of the current data version in the dimension's
  region files, read lazily when a tile needs them (the region's offset table says which exist, re-read when its file
  changes; at most 8 reads at once through `ChunkMap.read`, only the heightmaps and the needed sections decoded); (3)
  *estimate* — for noise generators the column's height in tiers by cell size (`Estimator`, measured with
  `/bl far probe [columns]`, which also logs its lines): single-block cells the generator's exact column (`getBaseHeight`,
  `OCEAN_FLOOR_WG`, about 2 ms a column), cells from 2 blocks a guided search of the uninterpolated final density started
  from the fast guess (about 0.3 ms, −0.7 ± 2 blocks), cells from 64 blocks the fast guess alone — bisection of the
  jagged-less initial density at −0.4, +4 blocks (about 0.07 ms, ± 4.4); the sea over anything below sea level, the biome at the surface from the biome source, and
  `data/blockland/farterrain/surfaces.json` (a data-pack resource): per biome or `#tag` (first match, else `default`) the
  surface, under and underwater floor blocks, ice on water, a stone line and a snow line, and a canopy (height, leaves,
  cover share: a deterministic hash of the column decides; cells 8 blocks and wider take the canopy wherever the cover is at
  least half, so a forest is one green roof). Flat generators: their layers; void: empty. Dimensions with a ceiling (the
  Nether) are not offered (`enabled = false`).
* **Coarse tiles never wait**: tiles of level 3 and coarser answer at once with what is captured and the estimate; the
  saved chunks they sample are read behind them (24 reads at once) and the refreshed tile is pushed to its holders. Finer
  tiles wait for up to 96 saved chunks, so near the camera the real terrain comes first.
* **Generation** (`Generation`, as Distant Horizons generates): in noise-generated levels every chunk within
  `generateRadius` (config, default 1536 blocks) of a player that does not exist yet is generated by the game's own world
  generation — terrain, surface, caves, trees — nearest first (a spiral per player, the region files' tables say what is
  missing, looked up on a worker), `generateConcurrency` at a time (default 8), each kept alive by a fresh one-tick ticket
  every tick until it is done, none started while the server's tick takes over 40 ms. A finished chunk loads, is captured,
  unloads and is saved like any other, so round the players the far terrain is the real terrain (and the world file
  grows by those chunks). `/bl far status` shows it.
* **Cache** (`DimensionTerrain`): every cell remembers whether it is real, estimated or empty and every tile the stamp it
  was built at (a counter of captures that only grows), so a request copies newly real cells and only estimates unknown
  ones (version bumped when anything changed); 1024 tiles per dimension in memory, on disk under
  `<world>/blockland/farterrain/<namespace>/<path>/L<level>/` with an index of the real columns (`real.bin`); void
  dimensions (arena, lobby) only in memory. A finished paste or clear (`WorldJobScheduler` completion) captures its box
  again. A tile a player holds this session that changes is pushed to them unasked (`DATA` / `EMPTY`).
* **Requests** run on a worker pool (6 tiles in work per player), nearest first per
  player in a queue of 512 (beyond it the farthest are dropped unanswered: the client asks again after its timeout);
  answers go out on the server thread within a per-player byte budget per second (config, default 384 KiB/s), packed
  into `S2CTiles` of at most 128 KiB. A request outside the allowed reach (config `maxDistance`, default 32 768, + one
  tile), wholly outside the player's view limit, while far terrain is off, or whose tile could not be built is answered
  `DENIED`. **View limit**: a player in an arena match only gets cells inside their match's bounds
  (`GameInstance.spectateBounds`; the rest `EMPTY`), so neighbouring matches 1024 blocks away never show; a new limit is
  a new session (hello).
* Config (`blockland-common.toml`, section `farTerrain`): `enabled`, `maxDistance`, `threads` (0 = half the cores, 2 … 8),
  `bytesPerSecond`, `estimate` (allow generator estimates), `importSaved`, `generateRadius`, `generateConcurrency`.

### Protocol (`network/packet/FarTerrainPackets`, play phase only)

* `S2CHello(session, enabled, maxDistance, worldId, dimension, minY, maxY)` — when a player enters a level (login,
  respawn into another dimension, teleport across dimensions). `session` numbers the stay in that level; anything of an
  older session is ignored by both sides. `worldId` keys the client's disk cache (the world's seed hash and the view
  limit: two arena slots are two caches).
* `C2SRequest(session, keys[], versions[])` — ≤ 64 tiles, each with the version the client holds (0 = none); rate
  limited (`anticheat/packets.json`). A request with no keys clears the player's queue (the client moved far away or
  changed settings).
* `S2CTiles(session, answers[])` — per tile: `DATA` (version + encoded tile), `UNCHANGED` (the client's copy is
  current), `EMPTY` (nothing there at all: the client remembers it), `DENIED`.

### Client (`client/graphics/far`)

* **Selection** (a quadtree from level-9 roots round the camera): out to the full-detail distance (`farTerrainFullDetail`
  chunks, at least the render distance, default 24) every cell is a block; beyond it a node is split while its cell is
  coarser than `(distance − full) / detail` (`FarTerrainQuality.detail`: 96 / 128 / 192 / 256 — at 1080p and 70° about 7 / 5 / 3.5 / 2.6 pixels per
  cell at the near edge of its ring), out to `farTerrainDistance` (1024 … 32 768 blocks); nodes wholly inside the vanilla
  area are dropped (`Selection`, every frame at frame start). Every node of the desired tree is wanted, nearest first
  (blocks; coarser first on ties). While some of a node's children are not complete the node stands in for all four, but
  only when it is at most `Selection.COARSEST` (4) times coarser than its place asks for; otherwise the children that are
  ready are drawn and the rest stays open until it arrives — a far tile's huge cells never stand in right beyond the
  loaded chunks. Checked offline by `python -I tools/farterrain/check_far_terrain.py` (the codec, the mesher's winding and
  merging, the selection: no holes, no overlaps, levels by distance).
* **Store** (`TileStore`, render thread): decoded tiles while wanted, dropped 8 s after they stop being wanted; the encoded
  bytes cached on disk under `<game>/blockland/farterrain/<server>/<worldId>-<state count>/<dimension>/<level>/<tx>_<tz>.bin`
  (read before asking, shown at once, then validated: the request carries the version held). Requests: at most 192 in
  flight, 8 packets a second, re-asked after 20 s. Decoding, the disk and meshing run on `FarWorkers` (1–3 low-priority
  daemons, most urgent first); finished meshes are uploaded at frame start, at most 16 or 6 MB a frame.
* **Looks** (`TileColors`, render thread, per palette entry): the block's top-face and side sprites in the block atlas
  (`spriteCode`: x / 16, y / 16, log2 of size / 16 — square power-of-two sprites on the 16-texel grid; others fall back to
  the colour), their tints at the cell's biome (`BlockColors` through a biome-only tint getter), the sprites' alpha-weighted
  averages × tint as the colour without a sprite, the light emission; water: the biome's water colour × the water sprite,
  mixed towards the floor by depth (drawn without a sprite).
* **Meshes** (worker threads): block columns as Minecraft draws them — tops greedily merged where height and colour
  agree, walls between neighbours of different height (a one-block band of the top block's side colour, below it the
  `under` block; the band is left out where cells are wider than 4 blocks), skirts at tile borders down to the
  neighbour's height where the same-level neighbour is known and at least max(8, 2 cells) deep either way (so a coarser
  or finer neighbour drawn beside it never leaves a gap), the same next to empty cells; a tile whose same-level
  neighbour arrives later is meshed again; tops merge only where the sprite agrees too. **Vertex** (12 bytes, element
  `Packed`, `UINT × 3`, GENERIC): word 0 = x (bits 0–6, cells 0 … 64), z (7–13), y + 32768 (14–29), the foot of a wall
  (30); word 2 = the face's sprite code (0 = none); word 1 = RGB (0–23: the tint with a sprite, else the colour), face
  (24–26: 0 up, 1 north −z, 2 south +z, 3 west −x, 4 east +x), water (27), block light emission (28–31).
* **Look**: a face with a sprite is drawn from the block atlas block by block, like the real terrain (`Sampler0`, the
  face's coordinate within its block, the mip level from the texels a pixel covers, up to the atlas's last level, where a
  block is its average colour) × the tint; faces without one get ± 8 % per block (a hash of its position) where a pixel
  covers under about two blocks. Walls darken towards their foot (vertex bit 30, × 0.72).
* **Drawing**: a frame pass after vanilla's sky and before its main pass (`LevelRendererMixin` in
  `com.blockland.mixin.level` → `client/graphics/frame/LevelPasses`), into the main target's colour with the far
  terrain's own depth texture (`FrameContext.farDepth`), with the level's own projection (bobbing and nausea included)
  whose near and far planes are replaced (`FarRenderer.farProjection`: the depth row rebuilt from the w row; `farNear` =
  half the vanilla render distance less a chunk, 8 … 256, `farFar` = 1.5 × the far distance + 4096) and the frame's
  jitter. Per tile one draw: `DynamicTransforms` (view rotation, the tile's corner relative to the camera, the cell size in
  `TextureMat[0][0]`), the shared quad index buffer; the view's own `FarView` block (projection, coverage mask placement,
  the camera within its chunk) is written with `writeToBuffer` into a buffer per view (main, scope), never a slot the
  other view is drawing with. Vanilla's terrain then draws over it with its own depth, so whatever vanilla draws
  wins; the far terrain only shows where vanilla left the sky. Right after, `far_depth` (a full-screen pass, colour writes
  off) carries the far terrain's depth into the target's own wherever it lies within the level projection's far plane
  (the level's projection × the inverse of the far one, both jittered alike): vanilla's terrain is still drawn over it
  where nearer, and what vanilla draws later — clouds, rain, particles, the Fabulous layers — is hidden behind far terrain
  standing in front of it (without this the clouds were drawn over the land as if it were sky: it looked see-through). Tiles front to back; tiles touching the vanilla area use
  the masked program (`far_terrain` with `MASKED`), which discards fragments over chunks vanilla has compiled
  (`CoverageMask`: one texel per chunk, 128 × 128 round the camera); the rest the plain one (early depth test). Lit like
  the lighting pack's terrain (`bl_skyShade` with sky light 15, the face shade, cloud shadows; water: Fresnel sky
  reflection and the sun's glint), fogged with `bl_fog`. A scope's picture draws it too (its own depth texture).
* **Fog**: while far terrain is drawn, vanilla's render-distance fade moves out to the far distance (vanilla's terrain no
  longer fades into the sky where the far terrain carries on), the environmental fog runs from half the far distance to
  its end (vanilla's 0 … 1024 stretched over the second half, rain pulling both in), and BlockLand's haze starts from
  `1.2 × 远景雾 / far` per block instead of 0.002 (at most): about 70 % haze at the far end, the land clear to half way. `GraphicsStatus.farTerrainReach` (blocks, 0 = not drawn) is what the fog and the light module read.
* **Post** (`FrameContext.farDepth` / `farNear` / `farFar`, null when nothing was drawn this frame): within the level's far
  plane the world depth already holds the far terrain (`far_depth`); beyond it the decode pass gives far-terrain pixels
  (world depth 1, far depth < 1) no sun glow (`gfx_decode` `FarDepthSampler`, `Params[1].w`); the
  volumetric fog (`fx_volumetric`: `FarTerrain` in its block, `fx_farDistance`) and the volumetric clouds (`gfx_clouds`,
  `Params[1].yzw`) end their rays at the far terrain, and through the sky the fog reaches as far as the far terrain does.
  The temporal passes treat it as sky (camera rotation only: at hundreds of blocks the parallax of a moving camera stays
  under a fraction of a pixel); screen-space reflections and ambient occlusion do not see it.
* **Minecraft's video settings** (render distance, simulation distance, chunk updates, graphics mode, smooth lighting,
  biome blend, entity distance and shadows, clouds and their distance, particles, mipmaps, frame rate, v-sync, fullscreen,
  field of view, brightness, view bobbing) are rows of the 画质 tab too (`SettingsScreen.vanillaRows`): each sets the game's
  own option (its range and reactions) and saves options.txt.
* **Settings** (the 画质 tab, section 远景地平线; in the presets: 低 off, 中 4096 / low, 高 8192 / medium, 极高 16 384 /
  high): `farTerrain`, `farTerrainDistance`, `farTerrainQuality`, `farTerrainFullDetail` (chunks, 0 … 64),
  `farTerrainHaze` (0 … 2). Status: tiles drawn / wanted,
  cells, GPU memory, the server's offer (`GraphicsStatus.far*`, also on F3 with 显示统计).
* **Cost**: per frame the selection (a few hundred nodes), at most a handful of mesh jobs and uploads, one draw per
  visible tile (front to back, early depth test outside the masked ring), one full-screen depth pass. Memory follows the
  view: about 500–700 tiles are wanted at 32 768 blocks and medium detail (nearly the same count at every level) plus the
  full-detail ring's single-block tiles, at 12 bytes a vertex.

## 23. Real far chunks (真实远景区块: `com.blockland.farterrain.FarColumn`, `server/farterrain`, `client/graphics/far`)

Beyond the render distance, out to `farChunkDistance` chunks (8 … 4096, presets 512 / 1024 / 2048), the world is drawn
from its real chunks with Minecraft's own block renderer and vanilla's terrain pipelines (so the lighting pack lights it
like the near world): the same block models, textures, tints, fluids and light. Out to `farChunkFullDetail` chunks
(16 … 256, at least the render distance + 4) block for block; beyond it in **units** of scale k — 2^k × 2^k chunks whose
blocks are cubes of 2^k real blocks, each cube the real block that best represents it, drawn as that block scaled up —
the scale doubling as the distance doubles. That far a block is smaller than a pixel, so the cubes look like the blocks
themselves while the cost per distance band stays the same (block-for-block geometry for 1000 chunks would need hundreds of
GB). The far terrain (§22) is drawn only where no unit holds a real chunk: beyond the far chunk distance, and where the
server has not generated a chunk yet.

* **Unit** (`FarColumn`, shared): scale (0 … 6), position in units of its scale, its lowest cube-section
  (`FarColumn.minSection` / `sectionCount`: sections of 16³ cubes), per section the bytes `LevelChunkSection.write` gives
  (block count, block state and biome palettes: global ids — the same as vanilla's chunk packet) or null for air, the
  section's sky and block light (2048 bytes each, null when there is none), and `present`: which of its chunks exist (a
  missing chunk is never drawn as real air). Key: x bits 0–27, z 28–55, scale 56–59 (not `ChunkPos.asLong`). Version =
  content hash. Codec FORMAT 2: header, presence words, per section flags (1 states, 2 sky, 4 block) and arrays, deflated.
* **Protocol** (`FarChunkPackets`, the far terrain's session): `C2SRequest(session, keys[], versions[])` ≤ 128 units;
  `S2CColumns(session, answers[])` with `FarTerrainPackets.Answer` kinds (DATA, UNCHANGED, EMPTY = none of its chunks
  exists, DENIED). Rate limited (`packets.json`).
* **Server** (`server/farterrain`): scale 0 from full-status chunks only — loaded chunks on the server thread (sections
  and the light engine's data layers), saved ones from the region files on workers (older saves upgraded with the game's
  data fixers first), never generated on request: the background generation of §22 follows the farthest unit a player
  asks for up to `columnDistance`. Scale k ≥ 1 built from its four children on workers (per 2 × 2 × 2 cubes: the most
  common top-exposed non-air block when at least half is non-air, else air; flowing fluid counts as air; light the
  maximum; biomes sampled), cached on the world's disk (`<world>/blockland/farchunks/…`) with the stamps of the chunks
  under it (their write times from the region file headers), rebuilt only when one of them was saved again. An existing
  all-air chunk is DATA without sections (real air); EMPTY means no chunk exists. A unit only partly inside the view limit
  has the chunks outside marked absent. Units outside the view limit or beyond `columnDistance` (config, chunks,
  default 1024) are DENIED; answers go out nearest first within `columnBytesPerSecond` per player; a unit a player holds
  that changes is pushed to them (scale 0 after 2 s quiet, coarser later).
* **Client store** (`ColumnStore`): wanted = the leaves of a quadtree from scale-6 roots round the camera's chunk: a unit
  splits while its nearest chunk is closer than `farChunkFullDetail` × 2^(k−1) chunks; nothing farther than
  `farChunkDistance` or within the render distance − 2; a chunk vanilla draws (it holds it, it is within vanilla's view
  distance, its surface section is compiled: `CoverageMask.vanillaDraws`) is left to vanilla, looked at again along
  vanilla's edge every 250 ms. Disk cache (`…/farchunks2/<server>/<world>/<dim>/s<scale>/`), versions sent back. A unit is
  kept as its compressed bytes; its `LevelChunkSection`s (light arrays all 0 or all 15 shared) only while a mesh needs
  them, decoded again when one does. **Swaps:** a unit that leaves the tree stays drawn until every wanted unit over it
  (finer or coarser) is meshed or known to hold nothing, then they change places in one frame (after 20 s it goes anyway);
  a new unit is not placed while one it overlaps is still drawn. Real terrain never drops to the far tiles because its
  replacement is still loading.
* **Meshing** (`ColumnMesher`, workers): in the unit's grid (a position = a cube), once its eight neighbours of the same
  scale are known; a neighbour of another scale counts as air, so borders between scales are closed by the units' own
  faces. The getter answers blocks, fluids, biome tints (vanilla's blend radius) and light from the store, and from the
  client level for chunks vanilla holds (scale 0); Minecraft's own block renderer (`renderBatched` / `renderLiquid`, Forge
  model data and render types) writes each layer's quads, scaled by 2^k, positions relative to the unit's region. Sky light
  missing: 15 above the unit's highest non-air cube, 0 below.
* **Drawing** (`RegionRenderer`): units packed into regions of 4 × 4 units of one scale, one vertex buffer per region and
  layer (a unit's place in it kept; a unit leaving is zeroed, a full buffer rebuilt by GPU copies), drawn in the level's
  pass before vanilla's terrain (§22) with each layer's vanilla pipeline and atlas, the far projection (`Projection` block
  of the pass), `DynamicTransforms` with the region's corner, frustum-culled, solid layers front to back before the far
  tiles, translucent ones back to front after them, into the far terrain's depth; `far_depth` then carries it into the
  level's own.
* **Coverage** (`CoverageMask`): one texel per chunk out to 320 chunks round the camera (vanilla's chunks and placed
  units), one per 16 × 16 chunks out to 4352 (set when all its chunks are held by placed units). The masked far tile
  program drops fragments either marks; far tiles are also drawn 0.1 % farther than they are (same pixel), so where a
  tile and a real chunk share a surface the real one wins.
* **Settings** (the 画质 tab, 远景地平线): `farChunks`, `farChunkDistance`, `farChunkFullDetail`. Status: units drawn /
  placed, held in memory, waiting, GPU MB.

## 24. The distant atmosphere (远景大气: `client/graphics/post/FarAtmosphere`, in the post chain right after the decode)

How far land looks to the eye, by its distance from the camera: one full-screen pass (`gfx_far_atmosphere`) on the
decoded linear-HDR picture at the internal resolution, before the anti-aliasing / upscaling (span `bl/post/far_atmosphere`).
Distance per pixel: the world depth through the inverse of the level projection × view rotation, or, where the world
depth is 1, the far terrain's depth (`FrameContext.farDepth`, the same projection with `farNear` / `farFar`); the sky
(both 1), the hand and the viewmodel (hand depth < 1, decode alpha 1) are passed through. In order:

* **Heat shimmer** (`heatShimmer` 0 … 1 × the air's heat): where the pixel is read wobbles in fine, rising ripples (two
  sines of screen position and time, up to about a pixel), from 250 blocks on (full at 2500) and only within ±0.1 of the
  horizon in the view ray's height; a read whose distance is under 0.85 of the pixel's (nearer things) or the sky is
  refused. The heat (eased) = warm biome at the camera (base temperature 0.5 → 0, 1.7 → 1) × daylight (sky darkness) ×
  clear weather; none in dimensions without sky light.
* **Detail loss** (`farBlur` 0 … 1): a rotated 8-tap disk, radius growing from 700 to 7000 blocks up to 2.2 pixels, of
  taps that are at least 0.8 of the pixel's distance away and not sky or hand — fine detail melts and outlines merge,
  silhouettes keep their edge against nearer things and the sky.
* **Aerial perspective** (`aerialPerspective` 0 … 2): beyond 150 blocks, transmittance `exp(−(0.36, 0.6, 1.0) ×
  strength × (d − 150) / 6000)` (blue scattered most: Rayleigh), saturation pulled towards luminance by 35 % of the
  green channel's loss, and the horizon's colour scattered in (the sky's colour paled 40 % towards its grey, linear) —
  a few percent within the render distance, the far terrain's mountains blue and soft; on top of the haze (§14, §22).
* Off with `farAtmosphere` (preset 低), with all three at 0, with the camera under water, or without the pipeline. Settings
  rows: the end of the 画质 tab's 远景地平线 section.

## 25. Connected textures (连接贴图: `client/ctm`, data `assets/<ns>/ctm/*.json`)

Blocks named by resource-pack data pick each face's tile from their neighbours, so a wall of them reads as one surface
with a frame round its outside (OptiFine's CTM idea, our own format). Any pack may add rules, for vanilla blocks too;
block code never knows it is connected.

**Data** (`assets/<any namespace>/ctm/<name>.json`, every pack, read with each model bake; files in sorted order, the
first rule of a block matching a quad wins). One rule, or several under `"rules": [...]`:

```json
{ "rules": [
  { "blocks": ["blockland:container_red"], "faces": ["sides"], "textures": ["blockland:block/container/red/side/0"],
    "method": "ctm", "tiles": "blockland:block/container/red/side" },
  { "blocks": ["blockland:container_red"], "faces": ["top", "bottom"], "textures": ["blockland:block/container/red/top/0"],
    "method": "ctm", "tiles": "blockland:block/container/red/top" }
] }
```

* `blocks` — block ids whose models are wrapped (no tags: models bake before tags exist).
* `faces` — `all` (default), `sides`, `top`, `bottom` or direction names; `textures` — the source sprites replaced
  (empty: every quad on those faces).
* `method` — `ctm` (47 tiles), `horizontal` (4: alone, joined left, right, both), `vertical` (4: alone, joined above,
  below, both), `random` (needs `count` with a base path; `weights` optional; stable per block and face).
* `tiles` — a base path (tile *i* is `<base>/<i>`) or an explicit list; they must lie under `textures/block/` (the block
  atlas's `block/` directory source picks them up in every namespace).
* `connect` — `block` (default: the same block, any state) or `state` (the very same state); `connectTo` — further
  block ids or `#tags` that join it (tags are fine here: evaluated while meshing).
* `exposedOnly` (default true) — a neighbour whose own face here is covered by a joined block does not join (the
  surface turns a corner there, so the frame stays).

**Tile layout** (`client/ctm/CtmTiles`, twin `tools/ctm/ctm_layout.py`). In-plane directions 0..7: up, right, down,
left, up-right, down-right, down-left, up-left; mask bit *i* = that neighbour joins. `ctm` reduces the mask (a corner
counts only when both its edges join) and the tile is the rank of the reduced mask among the 47 reduced masks in
ascending order: tile 0 is a block alone (frame all round), 46 fully surrounded. A tile draws a frame on every edge whose
bit is clear and an inner-corner piece where both edges join but the corner does not. Directions are in the
**texture's frame** (image right = +u, image up = −v): each face has a fixed world frame (`right`/`up` seen from
outside), and every quad's uv gradient (from its vertices) gives the permutation between the two, so rotated, uv-locked
or mirrored models get the frame on the right world side. Quads whose uvs do not run along the face's axes are left
alone.

**Hook** (`ConnectedTextures`, no mixin). On `ModelEvent.ModifyBakingResult` (a worker thread of the bake; the new packs
are already the resource manager's) the rules are loaded and each listed block's `BlockStateModel` is wrapped
(`ConnectedModel`, one per distinct baked model). Forge's `getModelData(level, pos, state, data)` — called by the section
compile with its `RenderSectionRegion` and by the far terrain's `ColumnMesher` with its column getter — reads the 26
blocks round the block once and stores, per rule, eight bits per face plus the block's seed in a `ModelProperty`.
`collectParts(random, parts, data, layer)` wraps the wrapped model's parts; their `getQuads` return the source quads with
the uvs moved to the chosen tile (same place within the sprite), cached per source quad (keyed by its vertex array) and
tile. Without that data (items, the breaking overlay, shadow casters, voxels, impact sprites) the model is the wrapped
one. Tiles are looked up in the block atlas on first use (after its upload); a missing tile switches the rule off with a
warning. A block change dirties the sections of every block within one block (vanilla), which covers every neighbour
whose mask can depend on it (all reads lie within ±1 on each axis).

**Performance switches.** Everything below the parts — vanilla's tesselation, `SECTION_MESHING` (`QuadWriter`, the
per-block face decisions), `SECTION_COPIES`, `BIOME_TINTS` — sees ordinary quads, and the masks come from the same
region snapshot either way, so the mesh is identical with every `Optimization` on or off. Cost: 27 block reads and a
few bit operations per wrapped block in a compile, plus one list per part with a replaced quad.

**Shipping containers** (`cargo/CargoContent`, 集装箱): eight colours (`container_<red|orange|yellow|green|blue|white|
gray|brown>`), full metal cubes (strength 5, pickaxe), their own creative tab. `tools/assets/gen_containers.py` makes
everything per colour: two 47-tile sets at 16 px (`side`: corrugated steel with vertical ribs every 4 px; `top`: the
roof panel, also underneath) whose frames are the rails (top and bottom edges), corner posts (left and right), corner
castings where two frame edges meet and an inner-corner piece; weathering repeats every 16 px so tiles continue each
other. The model names tile 0; the ctm rule joins a container to containers of its own colour only. Models, block
states, item models, loot tables and the `minecraft:mineable/pickaxe` tag come from the same script (the tag merged under
a file lock).

**Example pack** "BlockLand 连接玻璃" (`resourcepacks/blockland_connected`, `ConnectedPack`, off by default):
`minecraft:glass` with a light frame only round the outside of a window (`tools/assets/gen_connected_glass.py`).

Check: `python -I tools/ctm/check_ctm.py` (the layout, the Java twin's numbering, all 8 uv orientations put the frame on
exactly the free world sides, every rule's tiles exist under `textures/block/`).

## 26. Placement preview (`client/placement`, `common/block/MultiBlockFootprint`)

While the player holds a block that occupies more than one block and the crosshair is on a spot where vanilla would
place it, the blocks it would take are outlined: a faint blue-white fill (inset 4 mm, so it never fights with the faces
it rests on; drawn both sides, no depth write) and the combined outline of the cells (one box for a door or the
antenna, no line between the cells) as a light line over a translucent dark halo (vanilla's thick secondary outline),
so it reads on snow, sand and in the dark. Nothing when the block would not be placed there.

* **Contract.** A block that occupies several blocks implements `common/block/MultiBlockFootprint.footprint(state, pos)`:
  the absolute positions its placement state takes when placed at `pos`, `pos` first (the revive antenna, §51 of the
  asset spec: `pos` and the block above; the target stand, §28: the same; the vending machine, §28: four cells, `pos`
  the lower one on the viewer's left, one to the viewer's right and the two above). The preview's adapter (`client/placement/Footprints`) covers vanilla's doors
  and two-block plants (`DoorBlock`, `DoublePlantBlock`: the other half above or below) and beds (`BedBlock`: the head
  towards its facing). No block gets preview code of its own.
* **When** (`PlacementPreview.placement`, vanilla's own decision in `MultiPlayerGameMode.useItemOn` and
  `BlockItem.place`, evaluated without side effects): the main hand's block item, or the off hand's while the main hand is
  empty; inside the world border; not when the click would use the block clicked (its class overrides `useWithoutItem`
  — chests, doors, levers, the ammo crate) unless sneaking; the adventure-mode `can_place_on` rule; the block enabled;
  `BlockPlaceContext.canPlace`, `updatePlacementContext`, a non-null `getStateForPlacement` (which for these blocks
  already checks the other cells), `canSurvive` and no entity in the way (`isUnobstructed`); every cell inside the
  build height and the border. Only footprints of two or more cells are drawn.
* **Where in the frame.** From vanilla's block outline (Forge's `RenderHighlightEvent.Block`, never cancelled), with its
  pose stack and buffers: the same point of the level's frame as the outline beside it (after the block entities, or
  after translucent terrain when the targeted block is translucent), jittered with the level like everything vanilla
  draws there, so TAA and the upscalers treat it as they treat the outline. It follows wherever vanilla shows an outline
  (not with the HUD hidden, not in spectator mode) and is never in the shadow map (no level render there). Render types
  `debugStructureQuads` (fill), `secondaryBlockOutline` and `lines`; all blend translucently, which keeps the main
  target's alpha at 1 over opaque pixels (no false emission, §3).

## 27. Water on the lens (`client/graphics/post/LensWater`, after leaving water)

Setting 镜头水滴 (`GraphicsSettings.lensWater`, default on, in the 画质 tab's rain and water section). One full-screen
pass (`gfx_lens_water`) on the upscaled linear-HDR picture, in the post chain after motion blur and before bloom and
exposure (the HUD is never wet; the hand and the viewmodel are, as on a real lens). The idea is Chocapic13's Bliss
shaders' water on the camera (`exitWater`, smoothed over 5 s after the eye leaves water); the drops are BlockLand's own.

* **When.** While the camera is in water (`Camera.getFluidInCamera() == WATER`): a gentle wavering of the picture
  (≈ 0.2 % of the screen, more towards the edges). The moment it leaves, the clock of this exit starts (render clock,
  `RenderClock`); after 5.6 s the last drop is gone and the pass is skipped (the picture handed back as it is). Every exit
  lays different drops (a seed counted per exit, never random: the benchmark's frames repeat exactly). Changed settings
  clear the lens.
* **The film** (the first 0.8 s): below a line sweeping down the screen at 2.3 screen heights a second the water still
  running bends the picture in streaming ripples, strongest at the line's meniscus.
* **Sliding drops**: lanes 0.05 screen heights wide, three slots each (70 % taken); a drop's radius 0.008–0.024 (most
  small), life 2.2–5.5 s (bigger ones longer), shrinking with √ of the life left. It sits for 0.2–2.2 s (bigger ones
  less), then slides down at 0.05–0.42 screen heights a second by size, in fits — 35 % steady creep, 65 % lurches
  (stick, let go) — wandering a little sideways, drawn out 30 % while it moves, losing a third of its size over the
  first 0.6 screen heights. Behind it a hair-thin wet trail (a sideways smear, a touch darker) that dries within 1.6 s
  of the drop passing, with a bead left every 0.018 here and there (45 %).
* **Sitting drops**: a grid of 0.035 cells, 55 % taken, radii 0.002–0.009 (mostly the smallest), drying in 1.2–5 s.
* **The lens.** Each drop is a cap of water: through it the picture turned over and squeezed (seen at
  `centre − (p − centre) × 1.3…1.6`, a 4-tap blur of 1.5 pixels), a dark ring at the rim (light leaves it at a steep
  angle, down to 45 %), a glint of the light above on its upper left; the nearest-on-top drop (the thickest at the pixel)
  wins; the rim is anti-aliased over 1.5 pixels. All sizes are in screen heights, so drops are round at any aspect.

## 28. Vanilla-style decor blocks (`decor/DecorContent`, `tools/assets/gen_decor.py`)

Plain Minecraft blocks in vanilla's own style: JSON element models, 16 px pixel art from `gen_decor.py` (deterministic,
own art), vanilla shading and lighting — no GLB, no PBR. `tools/assets/check_decor.py` checks ids, block states, models,
textures, loot tables, lang, tags and that the model boxes are the collision shapes.

* **Vending machine** (`vending_machine`, 自动贩卖机): 2 wide × 2 tall × 15 px deep, its front towards the placer; four
  parts (`part` = `lower_left`, `lower_right`, `upper_left`, `upper_right`, seen from the front), each a box with its
  quarter of one 32 px front picture (a lit window of drinks, the payment panel, the flap); broken as one, only the lower
  left drops. Light level 9; the window, the drinks and the panel's LEDs glow through the block materials (§7, threshold
  0.82 above the red body). Decoration only. Item icon flat, as vanilla's doors.
* **Target stand** (`target_stand`, 靶子): an easel stand and a straw board printed with archery rings, two tall, broken as
  one. A hit on the board — vanilla projectiles (`onProjectileHit`) and rounds (`common/block/BulletReactive`, called by
  the gun service for every round that ends on a block face) — gives a redstone pulse of 1..15 by the distance from the
  bullseye (vanilla's target block's rule over the 7 px disc; 20 ticks for arrows, 8 otherwise) from both halves, and a
  thump.
* **Swim ring** (`swim_ring`, 游泳圈): an octagonal lifebuoy (white sides, red corners), 3 px high; its item lays it on a
  water surface like a lily pad (in the block above the water) or on any block; waterloggable.
* **Grass and stone layers** (`grass_layer` 草台 with the grass block's biome-tinted top on every face, `stone_layer`
  石台 with stone): 1–8 layers of 2 px, stacked with their own item like snow, solid at their full height. Another block
  placed into a layer lower than half a block replaces it; a block put on top breaks one lower than half a block and
  presses one between half and whole down to half a block (4 layers); half a block and a whole block stay. Drops as many
  as its layers.
* **Corals** (alive and dead, the dead bleached grey; alive ones die out of water as vanilla's coral plants do):
  `staghorn`, `sea_whip` (cross plants), `table`, `pillar`, `brain_dome`, `tube_cluster` (element shapes). **Reef coral**
  (`reef_coral`, 礁珊瑚, and its dead twin): a knobbly core with an arm to every neighbouring reef coral and down to a
  solid face (multipart, chorus-plant style), so a heap grows into one branching reef; alive while water touches it.

## 29. Tumbleweeds (风滚草: `client/ambient`, `ambient/TumbleweedEntity`, `server/ambient/AmbientService`)

Dried tumbleweeds rolling with the wind, a purely visual ambient prop.

* **What they are.** Client-only entities (`ambient/TumbleweedEntity`, type `blockland:tumbleweed`, registered on both
  sides but never saved, summoned or made by a server — one on a server removes itself): never pickable, hit, pushed or
  collided with by anything, so they never block players, shots or blades; they collide with blocks only, through
  vanilla's collision move. The client's ambient module (`client/ambient/Tumbleweeds`) adds them to the client level with
  negative ids the server never hands out, and its physics drives them (`TumbleweedEntity.ticker`).
* **Wind.** `client/render/WindField` is the wind for things that move with it besides the plants: direction and
  strength at a point, the same steady wind, slow turbulence and travelling gusts §18 bends the plants by (published by
  `client/graphics/wind/Wind`, not scaled by the plants' 风 setting). A tumbleweed's speed follows the local strength
  (at least the data's `minWind`: a desert's wind never quite drops), so gusts send them bounding across one after the
  other.
* **Movement** (`TumbleweedPhysics`): the wind brings it up to speed — quickly on the ground, slowly in the air; it rolls
  without slipping (spin = up × velocity / radius), hops now and then (the faster, the more) and bounces when it lands
  hard; a wall turns it back a little and it tries to hop over (pressed into a corner by the wind it stays until the wind
  turns); rolling into a catcher plant (cactus, dead bush, dry grass …) may hold it a while until a strong gust tears it
  free; water or lava sends it away. It grows in when it comes and shrinks away when it goes.
* **Where.** (1) The player in one of the data's desert-like biomes (desert, badlands variants) under daylight sky, on
  open ground of the data's `ground` blocks (sand, red sand, terracotta, coarse dirt, sandstone …) with nothing
  motion-blocking above. (2) A match whose map's settings carry **`"ambient": {"tumbleweeds": true}`**: arena maps live in
  the arena dimension, where the biome tells nothing, so the server's `AmbientService` tells each player once a second
  (only on a change) what their match asks for, inside the match's bounds (`GameInstance.spectateBounds`), with
  `AmbientPackets.S2CAmbient` (effects bit `TUMBLEWEEDS` + the box; none outside a match); there any open, sturdy
  ground with nothing above inside the box will do. Leaving the match (or the world) forgets it.
* **How many, and when.** At most the data's `maxCount` (biomes) or `matchCount` (a match), times the 画质 tab's 风滚草数量
  (环境 section; 风滚草 turns them off — the ones there shrink away). Every half second: one comes upwind of the player,
  22–56 blocks off and out of sight (or beyond 40 blocks); they leave when farther than `despawnDistance`, or — only where
  the player is not looking — out of their area, past their lifetime or over the count.
* **Drawing** (`TumbleweedRenderer`): one of the `models/ambient/tumbleweed_<i>.glb` variants (`tools/assets/
  gen_tumbleweed.py`: tangled tapered stems curling round a slightly flattened shell, side twigs, three straw / grey-brown
  tones, 9–12k triangles) through the weapon pipeline, lit by `SceneLighting`; its pose and a box round it go to
  `ObjectMotion` (keys apart from the vehicles'), so TAA, DLSS and the motion blur move its pixels with it; while the sun's
  shadow map captures entities it goes through `VanillaDraws`, so it casts a shadow.
* **Data** (`assets/blockland/ambient/tumbleweeds.json`): counts, spawn radius, despawn distance, lifetime, size range,
  speed per unit of wind, least wind, top speed, gravity, bounce, hops, ground / air grip, rolling friction, snagging
  (chance, ticks, the wind that frees), the biome list and the ground and catcher block lists (ids and `#tags`).
* **Cost** (measured with `TimingProbe` at 1920×1080, shadows ultra, a fixed desert view; dev automation
  `tumbleweeds <n> [front]` places still ones the same way every time). Nothing in view: 2.63 ms a frame. Before the
  optimizations, 10 in view cost +12.2 ms and 30 +15.7 ms — almost all of it the shadow capture rebuilding ~80k vertices
  per tumbleweed on the CPU every frame (`bl/shadow/entities` 11.4 / 26.2 ms). Two steps: the models went from 15–20k to
  9–12k triangles (stems four-sided, twigs three-sided in two lengths, no hidden base caps; every point of a stem's path
  kept — halving them showed corners in the arcs; renders before and after at gameplay distance compare as the same:
  mean difference 1.4 / 255, coverage −1.8 %), then `SHADOW_GPU_CASTERS` (§20). Now: 10 in view +0.38 ms, 30 in view
  +0.52 ms (`bl/ambient/tumbleweed_draw` 0.12 ms CPU / 0.21 ms GPU for 30, their shadow draws 0.15 ms GPU,
  `bl/shadow/entities` 0.08 ms); the physics of about 20 rolling ones 0.009 ms a frame (`bl/ambient/tumbleweed_physics`),
  the spawner under 0.001 ms (`bl/ambient/tumbleweeds`). Each tumbleweed's tick is one wind sample, a block lookup ahead
  and vanilla's collision move for a box under a block wide; drawing is one model per tumbleweed.
