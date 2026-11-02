package com.blockland.client.graphics.shadow;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * {@link Optimization#SHADOW_REUSE}: what each cascade's atlas tile holds, so that it is kept rather than drawn again,
 * and a texture of the atlas's size with each cascade's static terrain alone. A tile is the least depth of three kinds
 * of casters, each written with a less-or-equal test and nothing else (so the order never matters, and a draw repeated
 * over texels that already hold it changes nothing): the static terrain — the solid layers of every section reaching
 * the cascade and the cutout layers of those without animated sprites ({@code client/render/TerrainAnimation}) — which
 * only changes with the cascade's placement (the light, the snapped box, its size), the meshes of the sections
 * reaching it (every rebuilt section is a new mesh) or the block atlas; the cutout layers of the animated sections,
 * which may change on every client tick (the block atlas cycles its animated frames then, which may change their
 * alpha); and the entities, which move every frame (the player's own arms do). The camera does not count: the terrain
 * is placed relative to the box ({@link Cascade#casterView}).
 * <p>
 * So a kept tile is brought up to date by touching only the texels that could have changed. Every frame, the texels
 * last frame's entities could have touched (their box through the cascade, {@link #entityRect}; nothing of them lands
 * outside) are cleared, and the sections whose geometry reaches those texels ({@code client/render/MeshBounds}) are
 * drawn into them again, then this frame's entities — no copy, the few sections round the player are cheap to draw
 * again. After a tick, the texels every animated section can touch ({@link TerrainCasters#animatedRect}) are copied
 * back from the static texture (drawing the static sections reaching them again would be most of the tile in the far
 * cascades) and the animated sections are drawn into them again. The rectangles are bounded by boxes through the
 * cascade's projection ({@link Cascade#tileRect}), never by what was actually rasterised, so they only ever cover
 * more; everything outside them is untouched and held nothing of what changed. A tile that holds something else
 * (drawn whole without this, a restart, a placement change) takes the static drawing whole and everything else on
 * top; when only some sections' meshes changed (a block broken, a chunk loaded, a neighbour rebuilt with it), the
 * texels those sections' old and new boxes can touch are cleared in the static texture and drawn again with the
 * sections reaching them, and the tile takes that patch ({@link #check}).
 * <p>
 * The static drawing is a set of sections ({@link Signature#drawn}, by {@link TerrainCasters#key}), each with the mesh
 * it was drawn from: what the static texture holds is exactly their least depth. With
 * {@link Optimization#SHADOW_VIEW_CASTERS} the set starts as the sections that can shadow something in view and grows
 * as the camera turns ({@code TerrainCasters.NEW}: drawn into the static texture and copied over, {@link #added});
 * since every caster only ever lowers depth, a section added later gives the same texture as drawing it with the first
 * ones. The set is checked against the view area only when the caster walk was made anew
 * ({@link TerrainCasters#gatherSerial}: between walks nothing in it can have changed), and its membership is kept per
 * gathered section in the casters ({@link TerrainCasters#setCached}) for the draws to read.
 */
final class TerrainCache {
    private static long ticks;

    /** One section of a cascade's kept static drawing: the mesh it was drawn from, its origin and box (the texels it can touch). */
    private static final class Entry {
        SectionMesh mesh;
        int x, y, z;
        final float[] box = new float[6];
    }

    /** What one cascade's kept terrain was drawn from. */
    private static final class Signature {
        boolean valid;
        float lightX, lightY, lightZ, split, half, up, depth;
        double centerX, centerY, centerZ;
        int tile;
        @Nullable
        GpuTextureView blocks;
        /** The sections drawn, by {@link TerrainCasters#key}. */
        final Long2ObjectOpenHashMap<Entry> drawn = new Long2ObjectOpenHashMap<>();
        /** The walk ({@link TerrainCasters#gatherSerial}) the sections' cached bits were last projected for. */
        int gather = -1;
    }

    /** {@link #check}: the kept static terrain is current, is current but for the texels in the patch, or has to be drawn whole. */
    static final int KEPT = 0, PATCH = 1, WHOLE = 2;
    /** A patch wider than this share of the tile's texels is drawn whole instead (then most sections reach it anyway). */
    private static final float PATCH_SHARE = 0.5f;

    private final Signature[] signatures = {new Signature(), new Signature(), new Signature(), new Signature()};
    /** Per cascade: the atlas tile is the kept drawing plus animated sections (as of {@link #atlasTick}) plus entities (within {@link #entityRect}). */
    private final boolean[] atlasValid = new boolean[4];
    private final long[] atlasTick = new long[4];
    /** Per cascade: the static texture holds the static terrain its signature describes (false: only the atlas tile does). */
    private final boolean[] cached = new boolean[4];
    /** Per cascade: the atlas texels (x, y, w, h) last frame's entities were drawn within (w 0: none). */
    private final int[][] entityRect = new int[4][4];
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    /** Entries taken out of a signature, for reuse. */
    private final java.util.ArrayDeque<Entry> spare = new java.util.ArrayDeque<>();

    static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> ticks++);
    }

    /** The client tick now (the block atlas's animated frames change only between ticks). */
    static long ticks() {
        return ticks;
    }

    /** Whether cascade {@code c}'s atlas tile is known to be the static terrain plus animated sections plus entities. */
    boolean atlasValid(int c) {
        return atlasValid[c];
    }

    /** Whether the static texture holds cascade {@code c}'s static terrain as its signature describes it. */
    boolean cached(int c) {
        return cached[c];
    }

    /** The tick whose animated frames cascade {@code c}'s atlas tile shows. */
    long atlasTick(int c) {
        return atlasTick[c];
    }

    /** The atlas texels cascade {@code c}'s tile holds last frame's entities within (width 0: none). */
    int[] entityRect(int c) {
        return entityRect[c];
    }

    /** Cascade {@code c}'s atlas tile was just completed: animated sections as of {@code tick}, entities within {@code rect}. */
    void atlasDrawn(int c, long tick, int[] rect) {
        atlasValid[c] = true;
        atlasTick[c] = tick;
        System.arraycopy(rect, 0, entityRect[c], 0, 4);
    }

    /** The atlas tiles hold something else (drawn whole without this, the pipeline restarted): the next reuse draws them whole. */
    void invalidateAtlas() {
        java.util.Arrays.fill(atlasValid, false);
    }

    /** The static texture for an atlas of this size (made, or made again, as needed). */
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

    /**
     * Whether cascade {@code c}'s kept static terrain is what drawing its sections now would give ({@link #KEPT}), or
     * would be but for the sections whose meshes changed, came or went — then the texels those sections' old and new
     * boxes can touch, into {@code patch}, are all that has to be cleared and drawn again ({@link #PATCH}: every kept
     * section reaching them; outside them no changed mesh ever drew anything) — or has to be drawn whole
     * ({@link #WHOLE}: the placement, the atlas, a box unknown, or a patch too wide to pay). Also projects the kept
     * set onto the current caster walk ({@link TerrainCasters#setCached}): the changed sections leave the set here.
     */
    int check(int c, Cascade cascade, GpuTextureView blocks, TerrainCasters terrain, int[] patch) {
        Signature s = signatures[c];
        int reason = why(s, cascade, blocks);
        if (reason != KEPT_MATCH) {
            reset(c, terrain);
            misses[c][reason]++;
            return WHOLE;
        }
        int serial = terrain.gatherSerial();
        if (s.gather == serial) {
            // the same walk: no section's mesh or place changed since the set was last checked
            misses[c][KEPT_MATCH]++;
            return KEPT;
        }
        terrain.clearCached(c);
        patch[2] = patch[3] = 0;
        boolean known = true, changed = false;
        ObjectIterator<Long2ObjectMap.Entry<Entry>> it = s.drawn.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Entry> kv = it.next();
            Entry e = kv.getValue();
            int i = terrain.indexOf(kv.getLongKey());
            if (i >= 0 && terrain.reaches(i, c) && terrain.mesh(i) == e.mesh) {
                terrain.setCached(i, c, true);
                continue;
            }
            // changed, came with another mesh, or went: the texels its old drawing could touch, and those the new mesh can
            changed = true;
            if (Float.isNaN(e.box[0])) {
                known = false;
            } else if (terrain.rectOf(cascade, e.x, e.y, e.z, e.box, 0, rectScratch)) {
                Cascade.union(patch, rectScratch);
            }
            if (i >= 0 && terrain.reaches(i, c)) {
                if (!terrain.boxKnown(i)) known = false;
                else if (terrain.rect(i, cascade, rectScratch)) Cascade.union(patch, rectScratch);
            }
            spare.push(e);
            it.remove();
        }
        s.gather = serial;
        if (!changed) {
            misses[c][KEPT_MATCH]++;
            return KEPT;
        }
        long tile = cascade.tile;
        if (!known || (long) patch[2] * patch[3] > tile * tile * PATCH_SHARE) {
            reset(c, terrain);
            misses[c][NONE_MATCH]++;
            return WHOLE;
        }
        misses[c][PATCHED]++;
        return PATCH;
    }

    /** Why cascade {@code c}'s kept terrain is not what its placement would give ({@link #REASONS}), or {@link #KEPT_MATCH}. */
    private static int why(Signature s, Cascade cascade, GpuTextureView blocks) {
        if (!s.valid) return 1;
        if (s.blocks != blocks || s.tile != cascade.tile) return 2;
        if (s.lightX != cascade.light.x || s.lightY != cascade.light.y || s.lightZ != cascade.light.z) return 3;
        if (s.centerX != cascade.centerX || s.centerY != cascade.centerY || s.centerZ != cascade.centerZ) return 4;
        if (s.split != cascade.split || s.half != cascade.half || s.up != cascade.up || s.depth != cascade.depth) return 5;
        return KEPT_MATCH;
    }

    private static final int KEPT_MATCH = 0, NONE_MATCH = 6, PATCHED = 7;
    private static final String[] REASONS = {"kept", "none", "atlas", "light", "placed", "size", "sections", "patched"};
    private final int[] rectScratch = new int[4];
    /** Per cascade: how often each outcome of {@link #check} came up since the last {@link #stats}. */
    private final int[][] misses = new int[4][REASONS.length];

    /** The outcomes of the checks since the last call (F3 with the graphics stats on), per cascade. */
    String stats() {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < 4; c++) {
            int[] m = misses[c];
            int total = 0;
            for (int v : m) total += v;
            if (total == 0) continue;
            if (b.length() > 0) b.append(" | ");
            b.append('c').append(c).append(' ').append(signatures[c].drawn.size()).append(" kept ");
            for (int r = 0; r < m.length; r++) if (m[r] > 0) b.append(REASONS[r]).append(' ').append(m[r]).append(' ');
            java.util.Arrays.fill(m, 0);
        }
        return b.length() == 0 ? "no checks" : b.toString().trim();
    }

    /**
     * Cascade {@code c}'s static terrain was just drawn whole from the sections a draw of {@code mode} takes
     * ({@link TerrainCasters#picks}): into the static texture ({@code inCache}), or straight into the atlas tile.
     */
    void drawn(int c, Cascade cascade, GpuTextureView blocks, TerrainCasters terrain, int mode, boolean inCache) {
        reset(c, terrain);
        Signature s = signatures[c];
        cached[c] = inCache;
        put(s, c, terrain, mode);
        s.blocks = blocks;
        s.tile = cascade.tile;
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
        s.gather = terrain.gatherSerial();
        s.valid = true;
    }

    /** Sections were just drawn into cascade {@code c}'s kept static drawing: those a draw of {@code mode} takes join the set. */
    void added(int c, TerrainCasters terrain, int mode) {
        put(signatures[c], c, terrain, mode);
    }

    private void put(Signature s, int c, TerrainCasters terrain, int mode) {
        int n = terrain.count();
        for (int i = 0; i < n; i++) {
            if (!terrain.picks(i, c, mode)) continue;
            Entry e = spare.isEmpty() ? new Entry() : spare.pop();
            e.mesh = terrain.mesh(i);
            terrain.origin(i, originScratch);
            e.x = originScratch[0];
            e.y = originScratch[1];
            e.z = originScratch[2];
            terrain.box(i, e.box);
            Entry old = s.drawn.put(TerrainCasters.key(e.x, e.y, e.z), e);
            if (old != null) spare.push(old);
            terrain.setCached(i, c, true);
        }
    }

    private final int[] originScratch = new int[3];

    /** Cascade {@code c}'s kept set is dropped (nothing of it is trusted any more). */
    private void reset(int c, @Nullable TerrainCasters terrain) {
        Signature s = signatures[c];
        s.valid = false;
        s.gather = -1;
        s.blocks = null;
        if (!s.drawn.isEmpty()) {
            for (Entry e : s.drawn.values()) {
                e.mesh = null;
                if (spare.size() < 4096) spare.push(e);
            }
            s.drawn.clear();
        }
        cached[c] = false;
        if (terrain != null) terrain.clearCached(c);
    }

    /** Nothing kept is trusted any more (restart, a new atlas, the switch turned off). */
    void invalidate() {
        invalidateAtlas();
        for (int c = 0; c < 4; c++) reset(c, null);
    }

    void release() {
        invalidate();
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
    }
}
