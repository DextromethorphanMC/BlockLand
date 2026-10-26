package com.blockland.client.graphics.post;

import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.RenderClock;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The distant atmosphere (rendering spec §24): one full-screen pass on the decoded linear-HDR picture, before the
 * anti-aliasing, that makes far land look the way the eye sees it — aerial perspective (blue-shifted, faded towards the
 * horizon's colour), detail lost to a depth-aware blur from about a kilometre on, and heat shimmer over far ground near the
 * horizon in warm, sunny, clear air. Distances come from the world depth and, where it is 1, the far terrain's depth.
 * Nothing is done (the picture is handed back as it is) when it is off, under water, or all three strengths are zero.
 */
final class FarAtmosphere implements AutoCloseable {
    private static final RenderPipeline PASS = Fullscreen.pipeline("gfx_far_atmosphere").withSampler("SceneSampler")
            .withSampler("WorldDepthSampler").withSampler("HandDepthSampler").withSampler("FarDepthSampler").build();

    private final RenderTexture out = new RenderTexture("BlockLand far atmosphere", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    @Nullable
    private Ubo ubo;
    /** Heat of the air at the camera (0..1), eased so it never jumps when the weather or the biome changes. */
    private float heat;

    /** The picture with the distant atmosphere, or {@code hdr} itself when nothing is to be done. */
    GpuTextureView apply(FrameContext frame, GraphicsSettings s, GpuTextureView hdr, GpuTextureView worldDepth, GpuTextureView handDepth) {
        ClientLevel level = Minecraft.getInstance().level;
        boolean any = s.aerialPerspective > 0.001f || s.farBlur > 0.001f || s.heatShimmer > 0.001f;
        if (!s.farAtmosphere || !any || frame.submerged || level == null) return hdr;
        if (ubo == null) ubo = Ubo.pass("far atmosphere");
        Vec3 cam = new Vec3(frame.camX, frame.camY, frame.camZ);
        heat += (heatAt(level, cam, frame.partialTick) - heat) * 0.05f;

        // the horizon's colour: the sky's, paler (looking through the most air), in linear light
        int sky = level.getSkyColor(cam, frame.partialTick);
        float r = (sky >> 16 & 0xFF) / 255f, g = (sky >> 8 & 0xFF) / 255f, b = (sky & 0xFF) / 255f;
        float grey = (0.2126f * r + 0.7152f * g + 0.0722f * b) * 1.1f;
        r = linear(Mth.lerp(0.4f, r, grey));
        g = linear(Mth.lerp(0.4f, g, grey));
        b = linear(Mth.lerp(0.4f, b, grey));

        Matrix4f toWorld = new Matrix4f(frame.projection).mul(frame.viewRotation).invert();
        float time = (float) (RenderClock.nanos() / 1e9 % 3600.0);
        boolean far = frame.farDepth != null;
        out.ensure(frame.internalWidth, frame.internalHeight);
        ubo.pass(toWorld, frame.farNear, frame.farFar, far ? 1 : 0, frame.internalWidth,
                s.aerialPerspective, s.farBlur, s.heatShimmer * heat, time,
                r, g, b, frame.internalHeight);
        Fullscreen.pass("BlockLand post: far atmosphere", out.view(), PASS)
                .sampler("SceneSampler", hdr)
                .sampler("WorldDepthSampler", worldDepth)
                .sampler("HandDepthSampler", handDepth)
                .sampler("FarDepthSampler", far ? frame.farDepth : worldDepth)
                .uniform(ubo)
                .draw();
        return out.view();
    }

    /** How much the ground heats the air: a warm biome in clear daylight (deserts and savannas most, none in snow). */
    private static float heatAt(ClientLevel level, Vec3 cam, float partial) {
        if (!level.dimensionType().hasSkyLight()) return 0;
        float temperature = level.getBiome(BlockPos.containing(cam)).value().getBaseTemperature();
        float warm = Mth.clamp((temperature - 0.5f) / 1.2f, 0, 1);
        float day = 1 - level.getSkyDarken() / 11f;
        float clear = 1 - level.getRainLevel(partial);
        return warm * Mth.clamp((day - 0.5f) * 2, 0, 1) * clear;
    }

    private static float linear(float display) {
        return (float) Math.pow(Math.max(0, display), 2.2);
    }

    @Override
    public void close() {
        out.close();
        if (ubo != null) ubo.close();
        ubo = null;
    }
}
