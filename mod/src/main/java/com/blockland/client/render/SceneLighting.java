package com.blockland.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The world's lighting as BlockLand's own pipelines see it (rendering spec §2): the {@code BlockLandScene} uniform block
 * ({@code blockland:scene.glsl}) and the shadow atlas ({@code BlShadowMap}). The graphics module provides them each frame;
 * without it (graphics off, the lobby) a zeroed block and a fully lit 1×1 shadow map stand in, and the
 * shaders fall back to their studio lighting. Retained-model pipelines declare the uniform and the sampler and bind
 * {@link #uniforms()} / {@link #shadowMap()} on every pass.
 */
public final class SceneLighting {
    public static final int MAX_LIGHTS = 32;
    public static final int MAX_MATERIALS = 96;
    public static final int CASCADES = 4;
    /** Wind (rendering spec §18): travelling gusts, entities pushing plants aside, plants swinging back behind them. */
    public static final int MAX_GUSTS = 6, MAX_PUSHERS = 16, MAX_SWAYS = 48;
    /** Size of the {@code BlockLandScene} block (std140, see scene.glsl for the member order). */
    public static final int UBO_SIZE = layoutSize();
    /** Uniform buffer binding point the lighting pack's vanilla programs read the block from (rendering spec §3). */
    public static final int VANILLA_UBO_BINDING = 15;
    /** Texture unit the lighting pack's vanilla programs sample {@code BlShadowMap} from (rendering spec §3). */
    public static final int VANILLA_SHADOW_UNIT = 12;

    /** What the graphics module supplies. */
    public interface Provider {
        /** The scene block for BlockLand's pipelines this frame (an "off" block when there is no world lighting). */
        GpuBufferSlice uniforms();

        /** The shadow atlas (a depth texture with comparison sampling), or a fully lit stand-in. */
        GpuTextureView shadowMap();

        /** The world lighting runs this frame (sun, shadows, dynamic lights): models should be lit by it. */
        boolean worldLit();

        /**
         * Weapons (viewmodel, third person) are lit by the world this frame: {@link #worldLit()} and the player's
         * 武器受环境光照 setting.
         */
        default boolean weaponsLit() {
            return worldLit();
        }

        /**
         * Around an extra level render of the same frame (a scope's picture in picture): {@code true} before it, so the
         * extra view is lit like the main one, {@code false} after it, to put back what the frame's phase needs.
         */
        default void extraWorldView(boolean begin) {
        }
    }

    @Nullable
    private static Provider provider;
    @Nullable
    private static GpuBuffer offBuffer;
    @Nullable
    private static GpuTexture litTexture;
    @Nullable
    private static GpuTextureView litView;

    private SceneLighting() {
    }

    public static void setProvider(@Nullable Provider value) {
        provider = value;
    }

    public static GpuBufferSlice uniforms() {
        return provider != null ? provider.uniforms() : off();
    }

    public static GpuTextureView shadowMap() {
        return provider != null ? provider.shadowMap() : lit();
    }

    public static boolean worldLit() {
        return provider != null && provider.worldLit();
    }

    /** See {@link Provider#weaponsLit}. */
    public static boolean weaponsLit() {
        return provider != null && provider.weaponsLit();
    }

    /** See {@link Provider#extraWorldView}. */
    public static void extraWorldView(boolean begin) {
        if (provider != null) provider.extraWorldView(begin);
    }

    /** An all-zero scene block: world lighting and emission encoding off. */
    public static GpuBufferSlice off() {
        if (offBuffer == null || offBuffer.isClosed()) {
            ByteBuffer zeros = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());
            offBuffer = RenderSystem.getDevice().createBuffer(() -> "BlockLand scene (off)", GpuBuffer.USAGE_UNIFORM, zeros);
        }
        return offBuffer.slice();
    }

    /** A 1×1 depth texture at the far plane with comparison sampling: every lookup is lit. */
    public static GpuTextureView lit() {
        if (litView == null || litView.isClosed()) {
            litTexture = RenderSystem.getDevice().createTexture(() -> "BlockLand shadow (lit)", GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, TextureFormat.DEPTH32, 1, 1, 1, 1);
            litTexture.setTextureFilter(FilterMode.LINEAR, false);
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(litTexture, 1.0);
            compareMode(litTexture);
            litView = RenderSystem.getDevice().createTextureView(litTexture);
        }
        return litView;
    }

    /**
     * Switches a depth texture to comparison sampling ({@code sampler2DShadow}, reference ≤ stored passes). Blaze3D never
     * resets the compare mode, so once per texture is enough.
     */
    public static void compareMode(GpuTexture depth) {
        int unit = com.mojang.blaze3d.opengl.GlStateManager._getActiveTexture();
        int id = ((com.mojang.blaze3d.opengl.GlTexture) depth).glId();
        com.mojang.blaze3d.opengl.GlStateManager._bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        com.mojang.blaze3d.opengl.GlStateManager._bindTexture(0);
        com.mojang.blaze3d.opengl.GlStateManager._activeTexture(unit);
    }

    /** The block's std140 size, member by member as in scene.glsl. */
    private static int layoutSize() {
        Std140SizeCalculator size = new Std140SizeCalculator();
        // BlFlags, BlSunDir, BlSunColor, BlAmbient, BlSkyColor, BlCamera
        for (int i = 0; i < 6; i++) size.putVec4();
        size.putMat4f(); // BlViewRotation
        size.putMat4f(); // BlProjection
        for (int i = 0; i < CASCADES; i++) size.putMat4f(); // BlShadowMatrix[]
        size.putVec4(); // BlShadowSplits
        size.putVec4(); // BlShadowParams
        size.putVec4(); // BlWater
        for (int i = 0; i < MAX_LIGHTS * 2; i++) size.putVec4(); // BlLights[]
        size.putVec4(); // BlMaterialInfo
        for (int i = 0; i < MAX_MATERIALS * 2; i++) size.putVec4(); // BlMaterials[]
        size.putVec4(); // BlBlockLight
        size.putVec4(); // BlFog
        size.putVec4(); // BlClouds
        size.putVec4(); // BlCloudShape
        size.putVec4(); // BlGround
        size.putVec4(); // BlWaterColor
        size.putVec4(); // BlWeather
        for (int i = 0; i < WaterRipples.MAX * 2; i++) size.putVec4(); // BlRipples[]
        size.putMat4f(); // BlPrevView
        size.putVec4(); // BlPrevProj
        size.putVec4(); // BlWind
        size.putVec4(); // BlWindInfo
        for (int i = 0; i < 5; i++) size.putVec4(); // BlWindBend, BlWindStack, BlWindPush, BlWindSpring, BlWindKinds
        for (int i = 0; i < (MAX_GUSTS + MAX_PUSHERS + MAX_SWAYS) * 2; i++) size.putVec4(); // BlGusts[], BlPushers[], BlSways[]
        return size.get();
    }
}
