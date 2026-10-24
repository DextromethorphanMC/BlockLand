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
 * binding points and units 0..11 (its state cache tracks no more), so whatever is bound here stays bound; binding goes
 * through raw GL and the active texture unit is put back to what Blaze3D's cache believes.
 */
final class GlSlots {
    /** Texture unit of {@code BlMaterialMap} in the lighting pack's programs. */
    static final int MATERIAL_UNIT = 13;

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
