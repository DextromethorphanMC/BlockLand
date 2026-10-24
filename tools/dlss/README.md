# DLSS for BlockLand

BlockLand renders with Minecraft's OpenGL; NVIDIA DLSS only runs on Direct3D and Vulkan. The bridge
(`mod/src/main/java/com/blockland/client/graphics/upscale/dlss`) therefore runs NVIDIA's **Streamline** runtime on a
Vulkan device of its own — on the same GPU as the game's GL context (matched by device UUID) — and shares DLSS's four
textures with OpenGL (`GL_EXT_memory_object_win32`, `GL_EXT_semaphore_win32`). No native code of BlockLand's own: Vulkan
and Streamline are called through LWJGL's libffi, which Minecraft already ships.

```
GL: draw colour / depth / motion vectors (flipped upright) ─signal→ Vulkan: slSetTagForFrame + slEvaluateFeature ─signal→ GL: read the output
```

## Installing the runtime (once per game directory)

The binaries are NVIDIA's and are not in this repository. Download the Streamline SDK
(`streamline-sdk-v<version>.zip` from <https://github.com/NVIDIA-RTX/Streamline/releases>, MIT license; the DLSS DLL
inside carries NVIDIA's DLSS license) and install it into each game directory that should offer DLSS:

```bash
python -I tools/dlss/install_runtime.py path/to/streamline-sdk-v2.14.16.zip mod/run mod/run2
```

It copies `sl.interposer.dll`, `sl.common.dll`, `sl.dlss.dll`, `nvngx_dlss.dll` — and for DLSS Frame Generation
`sl.dlss_g.dll`, `nvngx_dlssg.dll`, `sl.reflex.dll`, `sl.pcl.dll`, `NvLowLatencyVk.dll` (with their license texts) — to
`<game>/blockland/native/streamline/`. A newer SDK, or a newer `nvngx_dlss.dll`, installs over the old one; the NVIDIA
App's DLSS override applies on top. Streamline's log goes to `<game>/logs/streamline/sl.log`.

On a laptop the game must run on the NVIDIA GPU (Windows Settings → System → Display → Graphics → `javaw.exe` → High
performance); otherwise the 画质 tab says DLSS is on another GPU.

## Checking it without the game

```bash
python -I tools/dlss/check_dlss.py path/to/folder/with/the/four/dlls
```

Hidden window, no focus. Renders a panning, aliasing-heavy procedural scene at DLSS's render size with jitter and exact
motion vectors, upscales it for 48 frames per mode and compares with a 16× supersampled reference. Every combination of
jitter and motion-vector sign is run, plus the game's own path (upright picture flipped into DLSS's rows, the formulas of
`DlssUpscaler`); the right conventions give the lowest error. Measured on an RTX 5090 Laptop GPU (SL 2.14.16, DLSS
310.9.16), quality mode, 853×480 → 1280×720:

| | error (RMS) |
|---|---|
| native 1280×720, one sample per pixel (no AA) | 0.047 |
| DLSS, jitter −sample offset, motion previous − current | 0.064 |
| **DLSS, the game's path** | **0.063** |
| DLSS, wrong jitter sign | 0.119 |
| DLSS, wrong motion sign | 0.217 – 0.225 |

The conventions in texel space (x right, rows down): motion vectors = previous position − current position, in render
pixels (`mvecScale` = 1 / render size); jitter = the projection's shift of the content in render pixels (the negated
sample offset). In OpenGL screen space that is jitter (δx, −δy) and motion (screen x, −screen y).

## Layout notes

Vulkan structures are written at their x64 offsets, checked against LWJGL's Vulkan bindings (`SIZEOF` / member offsets of
`VkImageCreateInfo`, `VkSubmitInfo`, … all match). Streamline structures follow the SDK headers and match the offsets
Streamline's own sources assert (`Preferences.renderAPI` 136, `ResourceTag.extent` 48, `Resource.internalFlags` 104,
`Constants.motionVectorsJittered` 450, `FeatureVersion.versionNGX` 44). Integration is Streamline's "manual hooking":
instance and device are created through Streamline's proxies (it adds what DLSS needs), resources are tagged per frame
(`eUseFrameBasedResourceTagging`), and while OpenGL presents, the common plugin's present hook is called directly once
per frame. Super Resolution (`DlssBridge`) and Frame Generation (`FrameGeneration`) share one `SlRuntime` — one device,
one sequence of frame tokens, constants set once per frame.

## Ray Reconstruction

With BlockLand's ray tracing on, DLSS Super Resolution or DLAA chosen and Ray Reconstruction as the denoiser,
`DlssBridge.evaluate(mode, preset, frame, worldToView, viewToWorld)` runs DLSS-RR (Streamline feature 1001: `sl.dlss_d.dll`,
`nvngx_dlssd.dll`, installed with the rest) on the same viewport instead of Super Resolution: `slDLSSDSetOptions` each
frame (mode, output size, HDR colour, roughness packed into the normals' alpha, the camera's world ↔ view matrices) and
three more tags — `kBufferTypeAlbedo`, `kBufferTypeSpecularAlbedo`, `kBufferTypeNormalRoughness` (render size, RGBA16F,
rows flipped like every input). If the GPU does not support it, the runtime comes up without it and Super Resolution keeps
running.

```bash
python -I tools/dlss/check_dlss.py --rr path/to/folder/with/the/runtime
```

Hidden window: a flat grey with strong per-pixel noise (one ray per pixel) through Ray Reconstruction for 40 frames, then
Super Resolution again. On the RTX 5090 Laptop GPU: deviation 0.173 → 0.003 with the mean kept (0.401 → 0.399), no
warnings in `sl.log`.

## DLSS 5 neural rendering

DLSS 5's 3D-guided neural rendering (DLSS-NR) is not wrapped by Streamline; RTX Remix (`dxvk-remix`,
`rtx_ngx_wrapper.cpp`) calls it through NGX, and so does `NeuralRendering` — without NGX's static library: the driver's
NGX core (`_nvngx.dll`, its folder in `HKLM\SOFTWARE\NVIDIA Corporation\Global\NGXCore\FullPath`) exports
`NVSDK_NGX_VULKAN_Init_ProjectID`, `GetCapabilityParameters`, `AllocateParameters`, `CreateFeature1`, `EvaluateFeature`,
`ReleaseFeature` and `GetFeatureDeviceExtensionRequirements` as C functions. Everything else goes through NGX's parameter
block, a C++ interface whose overloaded `Set` / `Get` are called by vtable slot (`Ngx.Params`): MSVC lays each overload
group out in reverse declaration order, and the bridge confirms that with typed round trips before using it. NGX is
initialised on the Streamline runtime's device (it keeps one context per device; Super Resolution keeps running), with
the runtime folder as the feature search path, so `nvngx_dlssnr.dll` sits next to the Streamline files:

```bash
python -I tools/dlss/install_runtime.py --dlssnr path/to/rtx-remix-ngx_sdk_dlnr@2.7z mod/run mod/run2
```

The package is NVIDIA's DLSS-NR SDK (headers, `nvngx_dlssnr.dll`, the NVIDIA RTX SDKs license), the one RTX Remix's
public build downloads: <https://d1oqksizpb0e3d.cloudfront.net/rtx-remix-ngx_sdk_dlnr@2.7z> (368 MB, 2026-10-16). Feature
id 18 (`NVSDK_NGX_Feature_Reserved18`), parameters `DLSSNR.*`: colour in and output at the output size (RGBA16F, SDR),
depth / motion vectors / control mask at the render size with subrects, `Intensity`, `LocalStructureStrength`,
`LocalToneStrength`, `Style` (model), `UseAutoMask` + `MaskBlendMode`, `SkinStructureStrength`, `Jitter.Offset.X/Y`,
`MVecScaleX/Y` = 1 for render-pixel motion vectors.

```bash
python -I tools/dlss/check_dlss.py --neural path/to/folder/with/the/runtime
```

Hidden window: NGX on the shared device, the parameter block layout, DLSS-NR's capability as the driver reports it,
Super Resolution before and after — and, where the driver and GPU support DLSS-NR, the feature created and evaluated for
30 frames on a lit test pattern, measuring how far the output moves from the input. On the RTX 5090 Laptop GPU: driver
591.91 does not know DLSS-NR (616.92+ needed); with 621.01 the model runs, and the mean change is 0.0000 at intensity 0,
0.0024 at the defaults, 0.0042 with tone and structure at 100% (the settings reach the model; it relights subtly). With
a grainy pattern: 0.0042 / 0.0073, the same for the default network and preset B and with NVIDIA's character mask on or
off, fine detail kept (sharpness ratio 0.99–1.00) — on Minecraft's flat lighting the model changes little.

## Frame Generation

DLSS Frame Generation inserts its frames at present, so it needs a swapchain Streamline owns: `FrameGeneration` creates a
child window over the game window (a STATIC control — hit tests pass through and it never takes the focus) and a Vulkan
swapchain on it through Streamline's hooks (`vkCreateSwapchainKHR`, `vkAcquireNextImageKHR`, `vkQueuePresentKHR` from its
`vkGetDeviceProcAddr`). Per frame OpenGL draws the finished frame, the HUD-less frame, the HUD alone (premultiplied colour
and alpha) and the render-size depth and motion vectors into shared images; Vulkan blits the frame into the swapchain
image, tags the four inputs `eOnlyValidNow` (Streamline copies them inside the same command buffer — it cannot copy
single-channel 8-bit images, hence UI colour and alpha rather than UI alpha), and presents between the PC-latency
markers; Reflex (required) sleeps before the next frame. DLSS-G needs Windows' hardware-accelerated GPU scheduling, an RTX
40 or newer GPU, and generates only while a window of the process is in the foreground.

```bash
python -I tools/dlss/check_dlss.py --framegen path/to/folder/with/the/runtime
```

Runs the presenter on a small window that never takes the focus (Super Resolution on the same runtime): 240 frames at 2×
and at the most the GPU allows, every input tagged. It passes when Streamline accepts everything with a clean status (on
the RTX 5090 Laptop GPU: up to 6×, status 0, no warnings in `sl.log` besides the optional back-buffer extent). Without the
focus DLSS-G logs "window not focused" and shows one frame per rendered frame; `visible` shows the window, and clicking
it lets the generated frames through.

DLSS 5 (neural rendering, announced 2026-03) is not in Streamline 2.14.16 as a usable feature (only an id); the bridge runs
DLSS Super Resolution / DLAA (NVIDIA's default model per mode, or presets J / K / L / M) and DLSS Frame Generation / Multi
Frame Generation.
