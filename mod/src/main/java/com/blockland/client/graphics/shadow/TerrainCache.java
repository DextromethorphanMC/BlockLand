package com.blockland.client.graphics.shadow;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * {@link Optimization#SHADOW_REUSE}: each cascade's terrain depth, kept in a texture of the atlas's size, and what it was
 * drawn from. A cascade's terrain is drawn again only when any of that changed — its placement (the light, the snapped
 * box, its size), the camera position (the casters are drawn camera-relative), the meshes of the sections reaching it
 * (every rebuilt section is a new mesh), the block atlas, or a client tick passed (animated block textures change their
 * alpha on ticks). Otherwise drawing it again would rasterise the very same draws into the same place, so the kept tile
 * is that drawing, bit for bit.
 * <p>
 * Every frame the kept tile is copied into the atlas and the entities are drawn over it. Before, the tile was cleared and
 * the terrain and the entities drawn in turn; both end at the least depth of everything drawn into each texel (a
 * less-or-equal test, depth only), so the atlas is the same either way. The entities (the player's own arms move on
 * every frame) are never kept.
 */
final class TerrainCache {
    private static long ticks;

    /** What one cascade's kept terrain was drawn from. */
    private static final class Signature {
        boolean valid;
        float lightX, lightY, lightZ, split, half, up, depth;
        double centerX, centerY, centerZ, camX, camY, camZ;
        int tile;
        long tick;
        @Nullable
        GpuTextureView blocks;
        SectionMesh[] meshes = new SectionMesh[256];
        int count;
    }

    private final Signature[] signatures = {new Signature(), new Signature(), new Signature(), new Signature()};
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    private SectionMesh[] scratch = new SectionMesh[256];

    static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> ticks++);
    }

    /** The cache texture for an atlas of this size (made, or made again, as needed). */
    GpuTextureView ensure(int size) {
        if (texture != null && !texture.isClosed() && texture.getWidth(0) == size) return view;
        release();
        var device = RenderSystem.getDevice();
        texture = device.createTexture(() -> "BlockLand sun shadows (terrain kept)", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                TextureFormat.DEPTH32, size, size, 1, 1);
        view = device.createTextureView(texture);
        return view;
    }

    @Nullable
    GpuTexture texture() {
        return texture;
    }

    /** Whether cascade {@code c}'s kept terrain is what drawing it now would give. */
    boolean current(int c, Cascade cascade, double camX, double camY, double camZ, GpuTextureView blocks, TerrainCasters terrain) {
        Signature s = signatures[c];
        if (!s.valid) return false;
        if (s.tick != ticks) return false;
        if (s.blocks != blocks || s.tile != cascade.tile) return false;
        if (s.camX != camX || s.camY != camY || s.camZ != camZ) return false;
        if (s.lightX != cascade.light.x || s.lightY != cascade.light.y || s.lightZ != cascade.light.z) return false;
        if (s.centerX != cascade.centerX || s.centerY != cascade.centerY || s.centerZ != cascade.centerZ) return false;
        if (s.split != cascade.split || s.half != cascade.half || s.up != cascade.up || s.depth != cascade.depth) return false;
        int n = terrain.meshesReaching(c, scratch);
        if (n < 0) {
            scratch = new SectionMesh[-n];
            n = terrain.meshesReaching(c, scratch);
        }
        if (n != s.count) return false;
        for (int i = 0; i < n; i++) if (scratch[i] != s.meshes[i]) return false;
        return true;
    }

    /** Cascade {@code c}'s terrain was just drawn into the cache from this. */
    void drawn(int c, Cascade cascade, double camX, double camY, double camZ, GpuTextureView blocks, TerrainCasters terrain) {
        Signature s = signatures[c];
        int n = terrain.meshesReaching(c, s.meshes);
        if (n < 0) {
            s.meshes = new SectionMesh[-n];
            n = terrain.meshesReaching(c, s.meshes);
        }
        s.count = n;
        s.tick = ticks;
        s.blocks = blocks;
        s.tile = cascade.tile;
        s.camX = camX;
        s.camY = camY;
        s.camZ = camZ;
        s.lightX = cascade.light.x;
        s.lightY = cascade.light.y;
        s.lightZ = cascade.light.z;
        s.centerX = cascade.centerX;
        s.centerY = cascade.centerY;
        s.centerZ = cascade.centerZ;
        s.split = cascade.split;
        s.half = cascade.half;
        s.up = cascade.up;
        s.depth = cascade.depth;
        s.valid = true;
    }

    /** Nothing kept is trusted any more (restart, a new atlas, the switch turned off, a moving camera). */
    void invalidate() {
        boolean any = false;
        for (Signature s : signatures) any |= s.valid || s.count > 0;
        if (!any) return;
        for (Signature s : signatures) {
            s.valid = false;
            java.util.Arrays.fill(s.meshes, 0, s.count, null);
            s.count = 0;
            s.blocks = null;
        }
        java.util.Arrays.fill(scratch, null);
    }

    void release() {
        invalidate();
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
    }
}
