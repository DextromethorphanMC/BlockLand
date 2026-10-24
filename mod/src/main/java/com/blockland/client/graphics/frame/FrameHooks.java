package com.blockland.client.graphics.frame;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.post.PostChain;
import com.blockland.client.graphics.upscale.DlssUpscaler;
import com.blockland.client.graphics.upscale.FrameGenerator;
import com.blockland.client.graphics.upscale.NeuralRenderer;
import com.blockland.client.graphics.upscale.TemporalUpscaler;
import com.blockland.client.graphics.upscale.Upscalers;
import com.blockland.client.render.FrameJitter;
import com.blockland.client.render.HandMotion;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Drives the frame's phases ({@link FrameStage}, rendering spec §1) from Forge's events and owns what spans them: the
 * internal-resolution scene target that stands in for Minecraft's main target from the frame's start to the HUD, the
 * jittered projections, and the post chain that writes the finished picture back into the real main target before the
 * HUD is drawn at full resolution.
 * <ul>
 *   <li>frame start ({@code RenderTickEvent.Pre}): upscaler chosen, jitter, main target swapped for the scene target;</li>
 *   <li>camera placed ({@code ComputeCameraAngles}, last listener): position and final angles;</li>
 *   <li>after the level (a Forge frame pass that runs after every other pass of the level): the world depth copied;</li>
 *   <li>composite (the HUD root's first layer, also with F1; a screen's render as a fallback): post chain, swap back;</li>
 *   <li>frame end ({@code RenderTickEvent.Post}): the main target is always back, whatever happened; with DLSS Frame
 *   Generation the finished frame goes to the screen through its Vulkan swapchain ({@link FrameGenerator}).</li>
 * </ul>
 * A scope's picture in picture renders the level again inside the hand phase with its own target as the main one; the
 * Forge pass sees that and stays out of it. Any failure inside the chain switches the pipeline off for the session (the
 * frame is then copied as it is), so a driver problem never leaves the screen black.
 */
public final class FrameHooks {
    private static final float DEG = (float) (Math.PI / 180.0);
    /** A camera jump longer than this (blocks) is a cut: no history is reprojected across it. */
    private static final double CUT_DISTANCE = 8;

    @Nullable
    private static final Field MAIN_TARGET = field(Minecraft.class, "mainRenderTarget");
    @Nullable
    private static final Field LEVEL_PROJECTION = field(GameRenderer.class, "levelProjectionMatrixBuffer");
    @Nullable
    private static final Field HUD_PROJECTION = field(GameRenderer.class, "hud3dProjectionMatrixBuffer");

    private enum Phase { IDLE, BEGUN, CAMERA, AFTER_LEVEL, COMPOSITED }

    private static final FrameContext frame = new FrameContext();
    private static final float[] jitter = new float[2];
    private static final Set<FrameStage> failedStages = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Phase phase = Phase.IDLE;
    private static boolean active;
    @Nullable
    private static RenderTarget savedMain;
    @Nullable
    private static SceneTarget scene;
    @Nullable
    private static RenderTexture worldDepth;
    /** The viewmodel's motion (see {@link HandMotion}), at the internal size. */
    @Nullable
    private static RenderTexture handVelocity;
    @Nullable
    private static Upscalers upscalers;
    @Nullable
    private static PostChain post;
    @Nullable
    private static Upscalers.Choice choice;
    private static final FrameGenerator frameGenerator = new FrameGenerator();
    private static final NeuralRenderer neuralRenderer = new NeuralRenderer();
    private static boolean projectionsReplaced, projectionsFailed, passAdded, broken;
    private static boolean historyValid, lastFrameComposited;
    @Nullable
    private static ClientLevel lastLevel;
    private static float worldFov = 70;
    private static long frameStart, lastComposite;
    private static float cpuMillis;

    private FrameHooks() {
    }

    public static void init(BusGroup modBus) {
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> beginFrame());
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> endFrame());
        ViewportEvent.ComputeFov.BUS.addListener(Priority.MONITOR, event -> {
            if (event.usedConfiguredFov()) worldFov = event.getFOV();
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(Priority.MONITOR, FrameHooks::cameraReady);
        AddFramePassEvent.BUS.addListener(FrameHooks::addPass);
        // the very first layer of the HUD root: the finished frame is written back before any HUD element is drawn
        // (other features' screen passes, e.g. the flash white-out, are inserted after it and see the final picture)
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addBelow(BlockLand.id("graphics_composite"), ForgeLayeredDraw.PRE_SLEEP_STACK, (g, delta) -> composite()));
        ScreenEvent.Render.Pre.BUS.addListener(event -> {
            composite();
            return false;
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && Graphics.settings().showStats) {
                event.getText().addAll(Graphics.status().debugLines());
            }
            return false;
        });
        Graphics.onSettingsChanged(() -> {
            if (upscalers != null) upscalers.invalidate();
            if (post != null) post.invalidate();
        });
    }

    // ---- phases -----------------------------------------------------------------------------------------------------

    private static void beginFrame() {
        Minecraft mc = Minecraft.getInstance();
        GraphicsStatus status = Graphics.status();
        active = false;
        phase = Phase.IDLE;
        FrameJitter.set(0, 0);
        reportDevice(status);
        String blocked = blockedReason(mc);
        status.blockedReason = blocked;
        if (blocked != null) {
            status.running = false;
            lastFrameComposited = false;
            frameGenerator.beginFrame(false, 0, 0, 0, 0);
            release();
            return;
        }
        frameStart = System.nanoTime();
        try {
            ensurePass();
            ensureProjections(mc);
            RenderTarget main = (RenderTarget) MAIN_TARGET.get(mc);
            if (main instanceof SceneTarget) {
                // a frame that never reached its end: put the real target back first
                if (savedMain == null) throw new IllegalStateException("scene target left in place without the main target");
                MAIN_TARGET.set(mc, savedMain);
                main = savedMain;
            }
            GraphicsSettings settings = Graphics.settings();
            if (upscalers == null) upscalers = new Upscalers();
            if (post == null) post = new PostChain();
            choice = upscalers.choose(settings);
            int outW = main.width, outH = main.height;
            int inW = Math.max(1, Math.round(outW * choice.scale()));
            int inH = Math.max(1, Math.round(outH * choice.scale()));
            boolean resized = ensureTargets(inW, inH, main.isStencilEnabled());
            boolean sizeChanged = resized || frame.outputWidth != outW || frame.outputHeight != outH;

            frame.prevCamX = frame.camX;
            frame.prevCamY = frame.camY;
            frame.prevCamZ = frame.camZ;
            frame.prevViewRotation.set(frame.viewRotation);
            frame.prevProjection.set(frame.projection);
            historyValid = lastFrameComposited && !sizeChanged && mc.level == lastLevel;
            lastLevel = mc.level;
            frame.frame++;
            frame.partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            frame.internalWidth = inW;
            frame.internalHeight = inH;
            frame.outputWidth = outW;
            frame.outputHeight = outH;
            frame.scene = scene;
            frame.worldDepth = null;
            frame.handVelocity = null;
            frame.frameGenerating = frameGenerator.beginFrame(true, outW, outH, inW, inH);
            float mipBias = 0;
            if (choice.upscaler().temporal() && projectionsReplaced) {
                TemporalUpscaler.jitter(frame.frame, TemporalUpscaler.phases(choice.scale()), jitter);
                frame.jitterX = jitter[0] * 2f / inW;
                frame.jitterY = jitter[1] * 2f / inH;
                // textures at the output's detail: the temporal resolve gathers the render's missing pixels
                mipBias = (float) Math.min(0, Math.log((double) inW / outW) / Math.log(2));
            } else {
                frame.jitterX = frame.jitterY = 0;
            }
            FrameJitter.set(frame.jitterX, frame.jitterY, mipBias);

            scene.hand = SceneTarget.Hand.NONE;
            savedMain = main;
            MAIN_TARGET.set(mc, scene);
            active = true;
            phase = Phase.BEGUN;

            status.internalWidth = inW;
            status.internalHeight = inH;
            status.outputWidth = outW;
            status.outputHeight = outH;
            status.activeAa = choice.upscaler().id();
        } catch (Throwable t) {
            fail("frame start", t);
            return;
        }
        stages(FrameStage::beginFrame);
    }

    private static void cameraReady(ViewportEvent.ComputeCameraAngles event) {
        if (!active || phase != Phase.BEGUN) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 position = camera.getPosition();
        frame.camX = position.x;
        frame.camY = position.y;
        frame.camZ = position.z;
        // exactly Camera.setRotation with the final angles (the camera receives them right after this event)
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.PI - event.getYaw() * DEG, -event.getPitch() * DEG, event.getRoll() * DEG);
        frame.viewRotation.rotation(rotation.conjugate());
        frame.partialTick = (float) event.getPartialTick();
        frame.near = 0.05f;
        frame.far = mc.gameRenderer.getDepthFar();
        frame.submerged = camera.getFluidInCamera() != FogType.NONE;
        // until the level hands its exact matrix over (it is built right after this event), the plain perspective
        frame.projection.setPerspective(worldFov * DEG, frame.outputWidth / (float) Math.max(1, frame.outputHeight), frame.near, frame.far);
        double dx = frame.camX - frame.prevCamX, dy = frame.camY - frame.prevCamY, dz = frame.camZ - frame.prevCamZ;
        if (dx * dx + dy * dy + dz * dz > CUT_DISTANCE * CUT_DISTANCE) historyValid = false;
        phase = Phase.CAMERA;
        stages(FrameStage::cameraReady);
    }

    /** From the level's projection buffer: the frame's exact projection, without jitter. */
    static void levelProjection(Matrix4f projection) {
        if (active && phase == Phase.CAMERA && mainIsScene()) frame.projection.set(projection);
    }

    private static void afterLevel() {
        if (!active || phase != Phase.CAMERA) return;
        Minecraft mc = Minecraft.getInstance();
        // a scope's picture in picture renders the level again with its own target: not ours to touch
        if (mc.gameRenderer.isPanoramicMode() || !mainIsScene()) return;
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(scene.depth(), worldDepth.texture(), 0, 0, 0, 0, 0,
                    frame.internalWidth, frame.internalHeight);
            frame.worldDepth = worldDepth.view();
            // the hand phase draws the viewmodel's motion into this; still parts and everything else stay zero
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(handVelocity.texture(), 0);
            frame.handVelocity = handVelocity.view();
            HandMotion.setTarget(frame.handVelocity);
        } catch (Throwable t) {
            fail("world depth copy", t);
            return;
        }
        phase = Phase.AFTER_LEVEL;
        scene.hand = SceneTarget.Hand.ARMED;
        stages(FrameStage::afterLevel);
    }

    private static void composite() {
        if (!active || phase == Phase.IDLE || phase == Phase.COMPOSITED) return;
        Minecraft mc = Minecraft.getInstance();
        boolean levelDrawn = phase == Phase.CAMERA || phase == Phase.AFTER_LEVEL;
        FrameJitter.set(0, 0);
        HandMotion.setTarget(null);
        if (levelDrawn) {
            stages(FrameStage::beforeComposite);
            long now = System.nanoTime();
            float seconds = lastComposite == 0 ? 1 / 60f : Math.min(0.25f, (now - lastComposite) / 1e9f);
            lastComposite = now;
            try {
                GraphicsStatus status = Graphics.status();
                post.run(new PostChain.Inputs(frame, scene.getColorTextureView(), scene.getDepthTextureView(), frame.worldDepth,
                        reprojection(), historyValid, choice.upscaler(), choice.fxaa(), status.worldLightingActive,
                        Graphics.settings(), Graphics.worldEffects(), savedMain.getColorTextureView(), seconds, neuralRenderer::apply));
                frameGenerator.beforeHud(savedMain.getColorTextureView(), frame.outputWidth, frame.outputHeight);
                status.running = true;
            } catch (Throwable t) {
                fail("post chain", t);
                return;
            }
        }
        restoreMain(mc);
        phase = Phase.COMPOSITED;
        lastFrameComposited = levelDrawn && frame.worldDepth != null;
    }

    private static void endFrame() {
        Minecraft mc = Minecraft.getInstance();
        boolean gameFrame = active && phase == Phase.COMPOSITED && lastFrameComposited;
        if (active) {
            if (phase != Phase.COMPOSITED) {
                // the HUD never ran (nothing composited): show what was drawn rather than nothing
                if (phase != Phase.BEGUN) {
                    try {
                        PostChain.copy(scene.getColorTextureView(), savedMain.getColorTextureView());
                    } catch (Throwable t) {
                        BlockLand.LOGGER.warn("[BlockLand] Could not copy the scene to the main target", t);
                    }
                }
                restoreMain(mc);
                lastFrameComposited = false;
            }
            stages(FrameStage::endFrame);
            cpuMillis = cpuMillis * 0.9f + (System.nanoTime() - frameStart) / 1e6f * 0.1f;
            Graphics.status().cpuMillis = cpuMillis;
        } else if (savedMain != null && MAIN_TARGET != null && mainIsScene()) {
            restoreMain(mc);
        }
        frameGenerator.endFrame(mc.getMainRenderTarget().getColorTextureView(), frame, gameFrame && post != null ? post.decoded() : null,
                gameFrame ? reprojection() : null, historyValid);
        FrameJitter.set(0, 0);
        HandMotion.setTarget(null);
        active = false;
        phase = Phase.IDLE;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** While the pipeline does not run (switched off, the lobby) its screen-sized textures are given back. */
    private static void release() {
        if (scene == null && post == null && upscalers == null) return;
        if (mainIsScene()) restoreMain(Minecraft.getInstance());
        if (scene != null) scene.close();
        frame.scene = null;
        frame.worldDepth = null;
        frame.handVelocity = null;
        HandMotion.setTarget(null);
        if (worldDepth != null) worldDepth.close();
        if (handVelocity != null) handVelocity.close();
        handVelocity = null;
        if (post != null) post.close();
        if (upscalers != null) upscalers.close();
        neuralRenderer.close();
        scene = null;
        worldDepth = null;
        post = null;
        upscalers = null;
        choice = null;
    }

    /** The GPU line and what DLSS would need, also while the pipeline does not run (the 画质 tab shows them in the lobby). */
    private static void reportDevice(GraphicsStatus status) {
        try {
            if (status.renderer.isEmpty()) status.renderer = RenderSystem.getDevice().getRenderer() + " / " + RenderSystem.getDevice().getVersion();
            DlssUpscaler.Probe probe = DlssUpscaler.probe();
            status.dlssAvailable = probe.available();
            status.dlssReason = probe.reason();
            status.dlssDetail = probe.detail();
            frameGenerator.report(status);
            status.rayReconstructionReason = DlssUpscaler.rayReconstructionReason();
            status.rayReconstructionAvailable = probe.available() && status.rayReconstructionReason == null;
            neuralRenderer.report(status);
        } catch (RuntimeException e) {
            status.dlssAvailable = false;
            status.frameGenAvailable = false;
        }
    }

    /** Why the pipeline does not run this frame (translation key), or null when it does. */
    @Nullable
    private static String blockedReason(Minecraft mc) {
        if (!Graphics.settings().enabled) return "blockland.graphics.status.disabled";
        if (broken) return "blockland.graphics.status.error";
        if (MAIN_TARGET == null) return "blockland.graphics.status.unsupported";
        if (mc.level == null || mc.player == null || mc.noRender || mc.screen instanceof ReceivingLevelScreen) return "blockland.graphics.status.no_world";
        return null;
    }

    private static boolean mainIsScene() {
        try {
            return scene != null && MAIN_TARGET != null && MAIN_TARGET.get(Minecraft.getInstance()) == scene;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void restoreMain(Minecraft mc) {
        if (savedMain == null || MAIN_TARGET == null) return;
        try {
            if (MAIN_TARGET.get(mc) != savedMain) MAIN_TARGET.set(mc, savedMain);
        } catch (ReflectiveOperationException e) {
            broken = true;
            BlockLand.LOGGER.error("[BlockLand] Could not put Minecraft's main render target back", e);
        }
        if (scene != null) scene.hand = SceneTarget.Hand.NONE;
    }

    /** Scene target and world depth at the internal size; true when either was (re)created. */
    private static boolean ensureTargets(int width, int height, boolean stencil) {
        boolean created = false;
        if (scene == null || scene.isStencilEnabled() != stencil) {
            if (scene != null) scene.close();
            scene = new SceneTarget(width, height, stencil);
            if (worldDepth != null) worldDepth.close();
            worldDepth = null;
            created = true;
        } else if (scene.width != width || scene.height != height) {
            scene.resize(width, height);
            created = true;
        }
        if (worldDepth == null) worldDepth = new RenderTexture("BlockLand world depth", RenderTexture.Format.DEPTH, FilterMode.NEAREST, stencil);
        if (handVelocity == null) handVelocity = new RenderTexture("BlockLand hand motion", RenderTexture.Format.RG16F, FilterMode.LINEAR);
        handVelocity.ensure(width, height);
        return worldDepth.ensure(width, height) | created;
    }

    /** Current unjittered NDC → the previous frame's clip space (camera-relative positions, camera motion included). */
    private static Matrix4f reprojection() {
        Matrix4f current = new Matrix4f(frame.projection).mul(frame.viewRotation).invert();
        return new Matrix4f(frame.prevProjection).mul(frame.prevViewRotation)
                .translate((float) (frame.camX - frame.prevCamX), (float) (frame.camY - frame.prevCamY), (float) (frame.camZ - frame.prevCamZ))
                .mul(current);
    }

    private static void stages(BiConsumer<FrameStage, FrameContext> phaseCall) {
        for (FrameStage stage : Graphics.stages()) {
            if (failedStages.contains(stage)) continue;
            try {
                phaseCall.accept(stage, frame);
            } catch (Throwable t) {
                failedStages.add(stage);
                BlockLand.LOGGER.error("[BlockLand] Render stage {} failed and is switched off for this session", stage.getClass().getName(), t);
            }
        }
    }

    /** Anything going wrong inside the frame: the pipeline stays off for the session, the frame is put back as it is. */
    private static void fail(String what, Throwable t) {
        broken = true;
        BlockLand.LOGGER.error("[BlockLand] Render pipeline failed ({}); vanilla rendering for the rest of the session", what, t);
        Graphics.status().running = false;
        Graphics.status().blockedReason = "blockland.graphics.status.error";
        Minecraft mc = Minecraft.getInstance();
        if (active && phase != Phase.BEGUN && phase != Phase.IDLE && scene != null && savedMain != null) {
            try {
                PostChain.copy(scene.getColorTextureView(), savedMain.getColorTextureView());
            } catch (Throwable ignored) {
                // nothing more to save
            }
        }
        restoreMain(mc);
        FrameJitter.set(0, 0);
        active = false;
        phase = Phase.COMPOSITED;
    }

    /** The level's last frame pass (Forge passes run after vanilla's, in the order of their resources). */
    private static void addPass(AddFramePassEvent event) {
        if (passAdded) return;
        passAdded = true;
        event.addPass(BlockLand.id("graphics_after_level"), PASS);
    }

    /** In case the level renderer was built before this mod listened (the pass list is static). */
    private static void ensurePass() {
        if (passAdded) return;
        passAdded = true;
        ForgeHooksClient.addFramePass(BlockLand.id("graphics_after_level"), PASS);
    }

    private static final FramePassManager.PassDefinition PASS = new FramePassManager.PassDefinition() {
        @Override
        public void extracts(LevelTargetBundle bundle, FramePass pass, DeltaTracker tracker) {
            bundle.main = pass.readsAndWrites(bundle.main);
        }

        @Override
        public void executes() {
            afterLevel();
        }
    };

    /** Swaps GameRenderer's level and hand projection buffers for the jittering ones (once). */
    private static void ensureProjections(Minecraft mc) {
        if (projectionsReplaced || projectionsFailed) return;
        try {
            if (LEVEL_PROJECTION == null || HUD_PROJECTION == null) throw new NoSuchFieldException("projection buffers");
            if (!(LEVEL_PROJECTION.get(mc.gameRenderer) instanceof JitteredProjections.Level)) {
                LEVEL_PROJECTION.set(mc.gameRenderer, new JitteredProjections.Level());
            }
            if (!(HUD_PROJECTION.get(mc.gameRenderer) instanceof JitteredProjections.Hand)) {
                HUD_PROJECTION.set(mc.gameRenderer, new JitteredProjections.Hand());
            }
            // the replaced buffers are left alone (a few bytes): a slice of them may still be bound somewhere this frame
            projectionsReplaced = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            projectionsFailed = true;
            BlockLand.LOGGER.warn("[BlockLand] Cannot jitter the projections ({}): temporal anti-aliasing runs without jitter", e.toString());
        }
    }

    @Nullable
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] {}.{} not found: the render pipeline needs it", owner.getSimpleName(), name);
            return null;
        }
    }
}
