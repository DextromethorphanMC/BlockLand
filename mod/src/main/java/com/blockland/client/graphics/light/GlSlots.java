package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * The fixed GL slots the lighting pack's vanilla programs read from (rendering spec §3): the scene block on uniform
 * binding point 15, the shadow atlas on texture unit 12 and the block material map on unit 13. Blaze3D only uses the low
 * binding points and units 0..11 (its state cache tracks no more), so whatever is bound here stays bound (and last
 * frame's world for reflections on unit 14); binding goes
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

    static void bindShadow(GpuTexture texture) {
        bindTexture(SceneLighting.VANILLA_SHADOW_UNIT, texture);
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
