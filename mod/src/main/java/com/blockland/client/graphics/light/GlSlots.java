package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * The fixed GL slots the lighting pack's vanilla programs read from (rendering spec §3): the scene block on uniform
 * binding point 15, the shadow atlas on texture unit 12 and the block material map on unit 13. Blaze3D only uses the low
 * binding points and units 0..11 (its state cache tracks no more), so whatever is bound here stays bound (and last
 * frame's world for reflections on unit 14, the shadow atlas as raw depth on unit 17); binding goes
 * through raw GL and the active texture unit is put back to what Blaze3D's cache believes.
 */
final class GlSlots {
    /** Texture unit of {@code BlMaterialMap} in the lighting pack's programs. */
    static final int MATERIAL_UNIT = 13;
    /** Texture unit of {@code BlPrevScene} (last frame's world for screen-space reflections). */
    static final int REFLECTION_UNIT = 14;
    /** Texture unit of {@code BlPlantColumns} (the wind's plant columns, rendering spec §18; read by the vertex shader). */
    static final int COLUMNS_UNIT = 15;
    /**
     * Texture unit of {@code noisetex} (Bliss's noise texture for the ported water, rendering spec §15): 16, past Blaze3D's
     * and the guaranteed 16 fragment units; where the driver has no unit 16 the uniform points at the material map instead
     * (always a valid texture; the water's waves are then wrong, never an invalid draw).
     */
    private static final int NOISE_UNIT = 16;
    private static int noiseUnit = -1;
    /**
     * Texture unit of {@code BlShadowDepth} (rendering spec §6): the shadow atlas once more, read as raw depth for the
     * soft shadows' blocker search through a sampler object of its own (no comparison, nearest), so the atlas keeps its
     * comparison mode for {@code BlShadowMap}. 17, past the guaranteed 16 fragment units: without it the uniform points at
     * the material map's unit and the scene block says so (no penumbra, the fixed filter as before).
     */
    private static final int SHADOW_DEPTH_UNIT = 17;
    private static int shadowDepthUnit = -1;
    private static int shadowDepthSampler;

    @Nullable
    private static final Field BUFFER_HANDLE = handleField();

    private GlSlots() {
    }

    static boolean supported() {
        return BUFFER_HANDLE != null;
    }

    /** Binds a whole uniform buffer to the scene block's binding point. */
    static void bindScene(GpuBuffer buffer) {
        int handle = handle(buffer);
        if (handle > 0) GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, SceneLighting.VANILLA_UBO_BINDING, handle);
    }

    /** The shadow atlas on unit 12 (comparison) and, where the driver has it, on unit 17 as raw depth. */
    static void bindShadow(GpuTexture texture) {
        bindTexture(SceneLighting.VANILLA_SHADOW_UNIT, texture);
        if (shadowDepthAvailable()) {
            bindTexture(SHADOW_DEPTH_UNIT, texture);
            // the sampler object overrides the texture's own parameters on this unit only (nothing else binds samplers)
            GL33.glBindSampler(SHADOW_DEPTH_UNIT, shadowDepthSampler);
        }
    }

    /** The unit {@code BlShadowDepth} is patched to: 17 when the driver has it, else the material map's (never read then). */
    static int shadowDepthUnit() {
        if (shadowDepthUnit < 0) {
            int units = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
            if (units > SHADOW_DEPTH_UNIT) {
                shadowDepthSampler = GL33.glGenSamplers();
                GL33.glSamplerParameteri(shadowDepthSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
                GL33.glSamplerParameteri(shadowDepthSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL33.glSamplerParameteri(shadowDepthSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL33.glSamplerParameteri(shadowDepthSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(shadowDepthSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            shadowDepthUnit = units > SHADOW_DEPTH_UNIT && shadowDepthSampler != 0 ? SHADOW_DEPTH_UNIT : MATERIAL_UNIT;
            if (shadowDepthUnit != SHADOW_DEPTH_UNIT) {
                BlockLand.LOGGER.warn("[BlockLand] only {} fragment texture units: shadows keep a fixed-width filter (no penumbra)", units);
            }
        }
        return shadowDepthUnit;
    }

    /** The raw shadow depth is on its unit: the soft shadows' blocker search can run (else the scene block turns it off). */
    static boolean shadowDepthAvailable() {
        return shadowDepthUnit() == SHADOW_DEPTH_UNIT;
    }

    static void bindMaterials(GpuTexture texture) {
        bindTexture(MATERIAL_UNIT, texture);
    }

    static void bindReflections(GpuTexture texture) {
        bindTexture(REFLECTION_UNIT, texture);
    }

    static void bindColumns(GpuTexture texture) {
        bindTexture(COLUMNS_UNIT, texture);
    }

    /** The unit {@code noisetex} is patched to: 16 when the driver has it, else the material map's. */
    static int noiseUnit() {
        if (noiseUnit < 0) {
            int units = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
            noiseUnit = units > NOISE_UNIT ? NOISE_UNIT : MATERIAL_UNIT;
            if (noiseUnit != NOISE_UNIT) {
                BlockLand.LOGGER.warn("[BlockLand] only {} fragment texture units: the water's noise texture is not bound", units);
            }
        }
        return noiseUnit;
    }

    static void bindNoise(GpuTexture texture) {
        if (noiseUnit() == NOISE_UNIT) bindTexture(NOISE_UNIT, texture);
    }

    private static void bindTexture(int unit, GpuTexture texture) {
        if (texture.isClosed()) return;
        int active = GlStateManager._getActiveTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((GlTexture) texture).glId());
        GL13.glActiveTexture(active);
    }

    private static int handle(GpuBuffer buffer) {
        if (BUFFER_HANDLE == null || buffer.isClosed() || !(buffer instanceof GlBuffer)) return -1;
        try {
            return BUFFER_HANDLE.getInt(buffer);
        } catch (IllegalAccessException e) {
            return -1;
        }
    }

    @Nullable
    private static Field handleField() {
        try {
            Field field = GlBuffer.class.getDeclaredField("handle");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] GL buffer handles are not readable ({}); world lighting stays off", e.toString());
            return null;
        }
    }
}
