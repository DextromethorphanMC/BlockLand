package com.blockland.client.graphics.water;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;

import javax.annotation.Nullable;

/**
 * Caustics under water (rendering spec §15) while the camera is in water: right after the level, every world pixel below
 * the water's surface that the sun reaches gets the light the waves above focus onto it ({@code gfx_underwater_caustics}:
 * Bliss's {@code waterCaustics}, ported — see {@code blockland:bliss_water.glsl}); the hand and the viewmodel, drawn later,
 * get none. Seen from above the water, the
 * Fabulous composite does the same for what lies under the surface. Needs world lighting; costs nothing out of the water.
 */
public final class UnderwaterCaustics implements FrameStage {
    private static final RenderPipeline PASS = Fullscreen.pipeline("gfx_underwater_caustics")
            .withSampler("SceneSampler").withSampler("DepthSampler").withSampler("BlShadowMap").withSampler("noisetex")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER).build();
    /** How far up from the camera the water's surface is looked for (blocks). */
    private static final int SEARCH = 48;

    private final RenderTexture copy = new RenderTexture("BlockLand underwater caustics (scene copy)", RenderTexture.Format.RGBA8, FilterMode.NEAREST);
    @Nullable
    private Ubo ubo;

    private UnderwaterCaustics() {
    }

    public static void init() {
        Graphics.addStage(new UnderwaterCaustics());
    }

    @Override
    public void afterLevel(FrameContext frame) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || frame.scene == null || frame.worldDepth == null || !SceneLighting.worldLit()
                || !level.dimensionType().hasSkyLight() || mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.WATER) {
            return;
        }
        double surface = surfaceOver(level, frame.camX, frame.camY, frame.camZ);
        GpuTextureView noise = BlissNoise.view();
        if (Double.isNaN(surface) || noise == null) return;
        GpuTextureView sceneColor = frame.scene.getColorTextureView();
        if (sceneColor == null) return;
        GpuTexture source = sceneColor.texture();
        int w = source.getWidth(0), h = source.getHeight(0);
        copy.ensure(w, h);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, copy.texture(), 0, 0, 0, 0, 0, w, h);
        if (ubo == null) ubo = Ubo.pass("underwater caustics");
        ubo.pass(frame.projection, (float) (surface - frame.camY));
        Fullscreen.pass("BlockLand underwater caustics", sceneColor, PASS)
                .sampler("SceneSampler", copy.view())
                .sampler("DepthSampler", frame.worldDepth)
                .sampler("BlShadowMap", SceneLighting.shadowMap())
                .sampler("noisetex", noise)
                .uniform("BlockLandScene", SceneLighting.uniforms())
                .uniform(ubo)
                .draw();
    }

    /** The height of the water's surface over the camera's water column (world y), or NaN when none is found. */
    private static double surfaceOver(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return Double.NaN;
        for (int i = 0; i < SEARCH; i++) {
            pos.move(0, 1, 0);
            if (!level.getFluidState(pos).is(FluidTags.WATER)) {
                pos.move(0, -1, 0);
                FluidState top = level.getFluidState(pos);
                return pos.getY() + top.getHeight(level, pos);
            }
        }
        return Double.NaN;
    }
}
