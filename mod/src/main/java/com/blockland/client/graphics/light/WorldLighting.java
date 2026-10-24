package com.blockland.client.graphics.light;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * World lighting (rendering spec §2, §3, §5, §7): the scene uniforms of every frame — sun or moon on the shadow map's path,
 * sky, the frame's dynamic lights, the shadow cascades, block materials — and the lighting pack whose vanilla shader
 * overrides read them. Provides {@link SceneLighting} for BlockLand's own pipelines.
 * <p>
 * Phases: from the camera on, the world version of the block is bound (lit as set, emission encoded); after the level the
 * hand version (vanilla lighting, emission still encoded, so held items never fake glow); from the composite on, and in
 * every frame the pipeline does not run, the zeroed one — the GUI and the lobby always render exactly like vanilla.
 */
public final class WorldLighting {
    private static final SceneUniforms uniforms = new SceneUniforms();
    private static final Sky sky = new Sky();
    private static final DynamicLights lights = new DynamicLights();
    private static final BlockMaterials materials = new BlockMaterials();

    private WorldLighting() {
    }

    public static void init(BusGroup modBus) {
        LightingPack.register(modBus);
        SceneLighting.setProvider(uniforms);
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    materials.load(manager);
                    lights.load(manager);
                    VanillaPrograms.reset();
                    VanillaPrograms.update();
                    LightingPack.afterReload();
                }));
        // every frame, whether the pipeline runs or not: new programs patched before anything draws, the off block bound
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frameStart());
        Graphics.addStage(new Stage());
        Graphics.onSettingsChanged(WorldLighting::updateStatus);
        Graphics.onSettingsCommitted(() -> {
            LightingPack.apply();
            updateStatus();
        });
    }

    private static void frameStart() {
        VanillaPrograms.update();
        VanillaPrograms.prepareTransparency();
        uniforms.setMaterialMap(materials.resolve());
        uniforms.endFrame();
        updateStatus();
    }

    private static final class Stage implements FrameStage {
        @Override
        public void cameraReady(FrameContext frame) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) return;
            GraphicsSettings settings = Graphics.settings();
            Vec3 camera = new Vec3(frame.camX, frame.camY, frame.camZ);
            sky.update(level, camera, frame.partialTick, settings);
            boolean lit = settings.worldLighting;
            lights.collect(lit ? level : null, frame.camX, frame.camY, frame.camZ, frame.partialTick, settings);
            GpuTexture map = materials.resolve();
            boolean underwater = mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
            uniforms.write(frame, sky, lights, lit ? Graphics.shadows() : null, materials, map, lit, settings.waterReflections,
                    settings.waterRefraction && Minecraft.useShaderTransparency(), underwater);
            uniforms.bind(SceneUniforms.Phase.WORLD);
            Graphics.status().dynamicLightCount = lit ? lights.count() : 0;
        }

        @Override
        public void afterLevel(FrameContext frame) {
            uniforms.bind(SceneUniforms.Phase.HAND);
        }

        @Override
        public void beforeComposite(FrameContext frame) {
            uniforms.bind(SceneUniforms.Phase.OFF);
        }

        @Override
        public void endFrame(FrameContext frame) {
            uniforms.endFrame();
        }
    }

    /** What the 画质 tab shows about world lighting. */
    private static void updateStatus() {
        GraphicsStatus status = Graphics.status();
        String reason = reason();
        status.worldLightingReason = reason;
        status.worldLightingActive = reason == null;
        status.reloadPending = LightingPack.reloadPending();
    }

    @Nullable
    private static String reason() {
        GraphicsSettings settings = Graphics.settings();
        if (!settings.enabled || !settings.worldLighting) return "blockland.graphics.status.lighting.off";
        if (!VanillaPrograms.supported() || !GlSlots.supported()) return "blockland.graphics.status.lighting.unsupported";
        if (LightingPack.reloadPending()) {
            return LightingPack.dropped() ? "blockland.graphics.status.lighting.dropped" : "blockland.graphics.status.lighting.not_loaded";
        }
        if (VanillaPrograms.litPrograms() == 0) return "blockland.graphics.status.lighting.overridden";
        return null;
    }
}
