package com.blockland.client.graphics.gpu;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * A screen-sized texture a pass draws into and later passes sample: created on first use, re-created when the size
 * changes. Blaze3D only knows 8-bit colour and 32-bit depth, so the floating-point formats are made by creating an RGBA8
 * texture through Blaze3D and re-specifying its GL storage: Blaze3D keeps treating it as a texture id (attachments,
 * sampling, clears all work), only its CPU-side copy/readback helpers would assume 8 bits, and nothing here uses them.
 */
public final class RenderTexture implements AutoCloseable {
    public enum Format {
        RGBA8(0, 0, 0),
        /** HDR colour. */
        RGBA16F(GL30.GL_RGBA16F, GL11.GL_RGBA, GL30.GL_HALF_FLOAT),
        /** Two channels (velocities, moments). */
        RG16F(GL30.GL_RG16F, GL30.GL_RG, GL30.GL_HALF_FLOAT),
        /** One float channel (luminance, depth-like values). */
        R16F(GL30.GL_R16F, GL11.GL_RED, GL30.GL_HALF_FLOAT),
        /** Depth (same format as Minecraft's targets, so depth copies between them work). */
        DEPTH(0, 0, 0);

        final int internal, format, type;

        Format(int internal, int format, int type) {
            this.internal = internal;
            this.format = format;
            this.type = type;
        }
    }

    private final String label;
    private final Format format;
    private final FilterMode filter;
    private final boolean stencil;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    private int width, height;

    public RenderTexture(String label, Format format, FilterMode filter) {
        this(label, format, filter, false);
    }

    /** @param stencil depth only: a depth-stencil texture (match a target with stencil, or depth copies fail) */
    public RenderTexture(String label, Format format, FilterMode filter, boolean stencil) {
        this.label = label;
        this.format = format;
        this.filter = filter;
        this.stencil = stencil;
    }

    /** Makes sure the texture exists at this size; true when it was (re)created (its contents are undefined then). */
    public boolean ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (texture != null && !texture.isClosed() && w == width && h == height) return false;
        close();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        boolean depth = format == Format.DEPTH;
        texture = RenderSystem.getDevice().createTexture(label, usage, depth ? TextureFormat.DEPTH32 : TextureFormat.RGBA8, w, h, 1, 1,
                depth && stencil);
        if (format.internal != 0) respecify(texture, format, w, h);
        texture.setTextureFilter(filter, false);
        texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
        view = RenderSystem.getDevice().createTextureView(texture);
        width = w;
        height = h;
        return true;
    }

    public GpuTexture texture() {
        if (texture == null) throw new IllegalStateException(label + " used before ensure()");
        return texture;
    }

    public GpuTextureView view() {
        if (view == null) throw new IllegalStateException(label + " used before ensure()");
        return view;
    }

    public boolean exists() {
        return view != null && !view.isClosed();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    private static void respecify(GpuTexture texture, Format format, int w, int h) {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        GlStateManager._bindTexture(((GlTexture) texture).glId());
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format.internal, w, h, 0, format.format, format.type, (ByteBuffer) null);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(unit);
    }

    @Override
    public void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
        width = height = 0;
    }
}
