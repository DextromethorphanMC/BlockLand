package com.blockland.client.hud.minimap;

import com.blockland.BlockLand;
import com.blockland.client.perf.FrameTimings;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * The minimap's picture (UI spec §3.4): the {@link MinimapGrid}'s columns as a {@value MinimapGrid#SIZE}-texel square
 * texture that wraps (texel = world column & 511), sampled a bounded amount each client tick by the
 * {@link ColumnSampler} and sent to the GPU in 64 × 64 tiles, only the tiles that changed, through Blaze3D's own
 * encoder. It reads the surface, or — with a roof over the player — the layer at their height; changing level, layer
 * or jumping far starts it afresh.
 */
final class MinimapCache {
    static final ResourceLocation TEXTURE = BlockLand.id("minimap");
    private static final int TILE = 64, TILES = MinimapGrid.SIZE / TILE;
    /** Per tick: columns the pass may visit, columns it may sample, and the time sampling may take. */
    private static final int MAX_CHECKS = 16_000, MAX_SAMPLES = 2048;
    private static final long MAX_NANOS = 500_000;
    /** A move further than this in one tick (a teleport, a respawn) clears the map. */
    private static final int JUMP = 128;

    private final MinimapGrid grid = new MinimapGrid();
    private final ColumnSampler sampler = new ColumnSampler();
    private final boolean[] dirty = new boolean[TILES * TILES];
    private final MinimapGrid.Painter painter = this::paint;
    @Nullable
    private DynamicTexture texture;
    @Nullable
    private ClientLevel level;
    private boolean layer;
    private int layerY, lastX, lastZ, roofCheck;

    /** Whether the picture shows the layer at the player's height (a roof over them). */
    boolean layer() {
        return layer;
    }

    void reset() {
        level = null;
        grid.clear();
        clearImage();
    }

    /** One client tick: follows the player, samples what is due and uploads what changed. */
    void tick(Minecraft mc, int range) {
        ClientLevel lvl = mc.level;
        Player player = mc.player;
        if (lvl == null || player == null) return;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/hud/minimap_tick")) {
            if (!ensureTexture()) return;
            int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ()), py = Mth.floor(player.getY());
            // another level or a long jump: the old picture goes at once; another layer: each texel is cleared as the
            // pass reaches it (the grid forgets every column)
            boolean moved = lvl != level || Math.abs(px - lastX) > JUMP || Math.abs(pz - lastZ) > JUMP, fresh = moved;
            if (--roofCheck <= 0 || moved) {
                roofCheck = 10;
                boolean roofed = roofed(lvl, player);
                if (roofed != layer) {
                    layer = roofed;
                    fresh = true;
                }
            }
            // the layer follows the player's height in steps (a few blocks of climbing keep the picture)
            if (layer && Math.abs(py - layerY) >= 4) fresh = true;
            if (fresh) {
                level = lvl;
                layerY = py;
                grid.clear();
                if (moved) clearImage();
            }
            lastX = px;
            lastZ = pz;
            sampler.use(lvl, layer, layerY);
            int half = (int) Math.ceil(range * 0.75f) + 2;
            grid.tick(px, pz, half, sampler, painter, MAX_CHECKS, MAX_SAMPLES, MAX_NANOS);
            upload();
        }
    }

    /**
     * Whether there is a roof over the player: a ceiling dimension, or the ground (leaves aside) well above their head
     * at their spot and at most of four spots six blocks round it — a cave, not a house's roof. Out again only once
     * most of those are open.
     */
    private boolean roofed(ClientLevel lvl, Player player) {
        if (lvl.dimensionType().hasCeiling()) return true;
        int x = Mth.floor(player.getX()), z = Mth.floor(player.getZ()), head = Mth.floor(player.getEyeY()) + 2;
        int covered = 0;
        int[][] spots = {{0, 0}, {6, 0}, {-6, 0}, {0, 6}, {0, -6}};
        for (int[] s : spots) {
            LevelChunk c = lvl.getChunkSource().getChunk((x + s[0]) >> 4, (z + s[1]) >> 4, ChunkStatus.FULL, false);
            if (c != null && c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + s[0], z + s[1]) > head) covered++;
        }
        return layer ? covered >= 2 : covered >= 4;
    }

    // ---- texture -------------------------------------------------------------------------------

    private boolean ensureTexture() {
        if (texture != null) return true;
        try {
            DynamicTexture t = new DynamicTexture(() -> "BlockLand minimap", MinimapGrid.SIZE, MinimapGrid.SIZE, true);
            // it wraps (the world runs on past the cache's edges) and is drawn smooth as it turns
            t.setClamp(false);
            t.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, t);
            texture = t;
            // the GPU copy starts undefined: the whole (clear) picture goes up once
            java.util.Arrays.fill(dirty, true);
            return true;
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Minimap texture unavailable", e);
            return false;
        }
    }

    boolean ready() {
        return texture != null;
    }

    private void paint(int tx, int tz, int argb) {
        NativeImage pixels = texture == null ? null : texture.getPixels();
        if (pixels == null) return;
        pixels.setPixel(tx, tz, argb);
        dirty[(tx / TILE) + (tz / TILE) * TILES] = true;
    }

    private void clearImage() {
        NativeImage pixels = texture == null ? null : texture.getPixels();
        if (pixels == null) return;
        pixels.fillRect(0, 0, MinimapGrid.SIZE, MinimapGrid.SIZE, 0);
        java.util.Arrays.fill(dirty, true);
    }

    /** Sends the tiles that changed to the GPU. */
    private void upload() {
        NativeImage pixels = texture == null ? null : texture.getPixels();
        if (pixels == null) return;
        CommandEncoder encoder = null;
        for (int i = 0; i < dirty.length; i++) {
            if (!dirty[i]) continue;
            dirty[i] = false;
            if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
            int x = (i % TILES) * TILE, y = (i / TILES) * TILE;
            encoder.writeToTexture(texture.getTexture(), pixels, 0, 0, x, y, TILE, TILE, x, y);
        }
    }
}
