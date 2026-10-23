package com.blockland.client.font;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.gui.render.TextureSetup;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-channel texture pages that glyph distance fields are packed into (shelf packing, one texel apart), sampled
 * with bilinear filtering. Pages are added as needed and dropped together on a resource reload. Render thread only.
 */
final class GlyphAtlas implements AutoCloseable {
    static final int SIZE = 1024;
    private static final int GAP = 1;
    /** Edge of the solid block (full coverage) on the first page, for underlines and strike-through. */
    private static final int SOLID = 6;

    private final List<Page> pages = new ArrayList<>();
    private Region solid;

    /** Where a glyph lives: its page and texture coordinates. */
    record Region(Page page, float u0, float v0, float u1, float v1) {
    }

    static final class Page {
        final GpuTexture texture;
        final GpuTextureView view;
        final TextureSetup setup;
        /** Shelves: y, height, next free x. */
        private final List<int[]> shelves = new ArrayList<>();
        private int nextY;

        Page(int index) {
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture(() -> "BlockLand UI font " + index,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, TextureFormat.RED8, SIZE, SIZE, 1, 1);
            texture.setTextureFilter(FilterMode.LINEAR, false);
            texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            try (NativeImage empty = new NativeImage(NativeImage.Format.LUMINANCE, SIZE, SIZE, true)) {
                device.createCommandEncoder().writeToTexture(texture, empty);
            }
            view = device.createTextureView(texture);
            setup = TextureSetup.singleTexture(view);
        }

        /** Top-left corner for a {@code width × height} block, or null when the page is full. */
        int[] place(int width, int height) {
            for (int[] shelf : shelves) {
                if (height <= shelf[1] && height * 4 >= shelf[1] * 3 && shelf[2] + width <= SIZE) {
                    int[] at = {shelf[2], shelf[0]};
                    shelf[2] += width + GAP;
                    return at;
                }
            }
            if (nextY + height > SIZE || width > SIZE) return null;
            int[] shelf = {nextY, height, width + GAP};
            shelves.add(shelf);
            nextY += height + GAP;
            return new int[]{0, shelf[0]};
        }
    }

    /** Packs and uploads a field ({@code width × height} bytes). */
    Region add(int width, int height, byte[] pixels) {
        int[] at = null;
        Page page = null;
        for (Page candidate : pages) {
            at = candidate.place(width, height);
            if (at != null) {
                page = candidate;
                break;
            }
        }
        if (page == null) {
            page = new Page(pages.size());
            pages.add(page);
            at = page.place(width, height);
            if (at == null) throw new IllegalArgumentException("glyph larger than an atlas page: " + width + "x" + height);
        }
        try (NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, width, height, false)) {
            MemoryUtil.memByteBuffer(image.getPointer(), width * height).put(0, pixels, 0, width * height);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(page.texture, image, 0, 0, at[0], at[1], width, height, 0, 0);
        }
        return new Region(page, (float) at[0] / SIZE, (float) at[1] / SIZE, (float) (at[0] + width) / SIZE, (float) (at[1] + height) / SIZE);
    }

    /** A block of full coverage (sampled well inside it, so filtering never reaches its border). */
    Region solid() {
        if (solid == null) {
            byte[] pixels = new byte[SOLID * SOLID];
            java.util.Arrays.fill(pixels, (byte) 255);
            Region block = add(SOLID, SOLID, pixels);
            float inset = 2f / SIZE;
            solid = new Region(block.page(), block.u0() + inset, block.v0() + inset, block.u1() - inset, block.v1() - inset);
        }
        return solid;
    }

    @Override
    public void close() {
        for (Page page : pages) {
            page.view.close();
            page.texture.close();
        }
        pages.clear();
        solid = null;
    }
}
