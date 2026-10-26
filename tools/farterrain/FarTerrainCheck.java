package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarTile;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline checks of the far terrain's client side (tools/farterrain/check_far_terrain.py, rendering spec §22): the tile
 * codec and keys, the mesher (vertices in range, every quad's front face outward, tops and walls, merging), and the
 * selection (every point of the ring between vanilla's area and the reach drawn by exactly one tile, none coarser than
 * its distance allows, the wanted count bounded).
 */
public final class FarTerrainCheck {
    static int failures;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        mesh();
        codec();
        selection();
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static TileColors.Palette palette(FarTile tile) {
        int states = Math.max(1, tile.stateCount()), biomes = Math.max(1, tile.biomeCount());
        int[] top = new int[states * biomes], side = new int[states * biomes], emission = new int[states];
        boolean[] water = new boolean[states];
        for (int s = 0; s < states; s++) {
            for (int b = 0; b < biomes; b++) {
                top[s * biomes + b] = 0x102030 * (s + 1);
                side[s * biomes + b] = 0x050607 * (s + 1);
            }
            water[s] = tile.state(s) == 86;
        }
        int[] waterColor = new int[biomes];
        java.util.Arrays.fill(waterColor, 0x3F76E4);
        int[] tints = new int[states * biomes], sprites = new int[states];
        java.util.Arrays.fill(tints, 0xFFFFFF);
        // every other state textured (sprite at 16, 32 of 16 texels)
        for (int st = 0; st < states; st += 2) sprites[st] = TileColors.TEXTURED | 1 | 2 << 12;
        return new TileColors.Palette(biomes, top, side, tints, tints, sprites, sprites, emission, water, waterColor);
    }

    static void mesh() {
        Random random = new Random(7);
        for (int level = 0; level <= 9; level += 3) {
            FarTile tile = new FarTile(level, -3, 5);
            for (int j = 0; j < FarTile.SIZE; j++) {
                for (int i = 0; i < FarTile.SIZE; i++) {
                    int c = FarTile.index(i, j);
                    if (i > 50 && j < 6) continue; // a void corner
                    int h = 64 + (int) (12 * Math.sin(i * 0.2) * Math.cos(j * 0.15)) + random.nextInt(2);
                    boolean water = i < 10 && j < 10;
                    tile.set(c, water ? 62 : h, water ? 86 : 9, water ? 40 : 10, "minecraft:plains", water ? 62 - 50 : 0);
                }
            }
            short[] north = new short[64];
            java.util.Arrays.fill(north, (short) 200);
            MeshBuilder.Result r = MeshBuilder.build(tile, palette(tile), new MeshBuilder.Borders(north, null, null, null), -64);
            check(r.quads > 0, "level " + level + " has quads");
            check(r.data.remaining() == r.quads * 4 * FarVertex.BYTES, "level " + level + " byte count");
            long base = MemoryUtil.memAddress(r.data);
            int tops = 0, walls = 0, bad = 0;
            for (int q = 0; q < r.quads; q++) {
                int[][] v = new int[4][3];
                int face = -1;
                for (int k = 0; k < 4; k++) {
                    int p = MemoryUtil.memGetInt(base + (q * 4L + k) * FarVertex.BYTES);
                    int look = MemoryUtil.memGetInt(base + (q * 4L + k) * FarVertex.BYTES + 4);
                    v[k][0] = p & 127;
                    v[k][2] = (p >> 7) & 127;
                    v[k][1] = ((p >>> 14) & 0xFFFF) - 32768;
                    face = (look >>> 24) & 7;
                    if (v[k][0] > 64 || v[k][2] > 64 || v[k][1] < -64 || v[k][1] > 400) bad++;
                }
                // outward normal of A, B, C (counter-clockwise front face) must match the face
                int[] ab = sub(v[1], v[0]), ac = sub(v[2], v[0]);
                long nx = (long) ab[1] * ac[2] - (long) ab[2] * ac[1];
                long ny = (long) ab[2] * ac[0] - (long) ab[0] * ac[2];
                long nz = (long) ab[0] * ac[1] - (long) ab[1] * ac[0];
                boolean ok = switch (face) {
                    case FarVertex.UP -> ny > 0 && nx == 0 && nz == 0;
                    case FarVertex.NORTH -> nz < 0 && nx == 0 && ny == 0;
                    case FarVertex.SOUTH -> nz > 0 && nx == 0 && ny == 0;
                    case FarVertex.WEST -> nx < 0 && ny == 0 && nz == 0;
                    case FarVertex.EAST -> nx > 0 && ny == 0 && nz == 0;
                    default -> false;
                };
                if (!ok) bad++;
                if (face == FarVertex.UP) tops++;
                else walls++;
            }
            check(bad == 0, "level " + level + " vertices in range and winding outward (bad " + bad + ")");
            check(walls > 0 && tops > 0, "level " + level + " tops " + tops + " walls " + walls);
            System.out.println("mesh level " + level + ": " + r.quads + " quads (" + tops + " tops, " + walls + " walls), y "
                    + r.minY + ".." + r.maxY);
            r.free();
        }
        // a flat tile merges into one top quad plus skirts
        FarTile flat = new FarTile(2, 0, 0);
        for (int c = 0; c < FarTile.CELLS; c++) flat.set(c, 70, 9, 10, "minecraft:plains", 0);
        MeshBuilder.Result r = MeshBuilder.build(flat, palette(flat), new MeshBuilder.Borders(null, null, null, null), -64);
        check(r.quads == 1 + 8, "flat tile: 1 top + 4 merged skirts of band and under, got " + r.quads);
        r.free();
    }

    static int[] sub(int[] a, int[] b) {
        return new int[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static void codec() {
        FarTile tile = new FarTile(5, -123456, 98765);
        Random random = new Random(3);
        for (int c = 0; c < FarTile.CELLS; c++) {
            if (random.nextInt(10) == 0) continue;
            tile.set(c, random.nextInt(400) - 64, random.nextInt(30), random.nextInt(30), "minecraft:b" + random.nextInt(5), random.nextInt(3));
        }
        long version = tile.computeVersion();
        FarTile back = FarTile.decode(tile.encode());
        check(back.level == 5 && back.tx == -123456 && back.tz == 98765, "codec keeps the key");
        check(back.computeVersion() == version, "codec keeps the content (version)");
        long key = FarTile.key(9, -7, 3);
        check(FarTile.level(key) == 9 && FarTile.tx(key) == -7 && FarTile.tz(key) == 3, "key packing");
        System.out.println("codec: " + tile.encode().length + " bytes for a random tile");
    }

    static void selection() {
        TileStore store = new TileStore(new TileColors());
        Selection selection = new Selection();
        double camX = 1234.5, camZ = -9876.25, reach = 32768;
        int detail = 128;
        double inner = 6 * 16, outer = 9 * 16;
        long now = 1000;
        // first frame: only roots and what they want; complete everything wanted, repeat until stable
        int rounds = 0, previous = -1;
        while (rounds++ < 20) {
            selection.run(store, camX, camZ, reach, detail, inner, outer, 0, now);
            for (Selection.Draw d : selection.draws) check(d.entry().drawable(), "drawn entries are drawable");
            int wanted = selection.wanted;
            // every wanted tile completes (a mesh with something in it)
            List<TileEntry> all = new ArrayList<>();
            for (long k : keys(store)) all.add(store.peek(k));
            for (TileEntry e : all) if (e != null && e.mesh == null) e.mesh = new FarMesh(null, 10, 60, 80);
            if (wanted == previous) break;
            previous = wanted;
        }
        selection.run(store, camX, camZ, reach, detail, inner, outer, 0, now);
        int[] perLevel = new int[10];
        for (Selection.Draw d : selection.draws) perLevel[d.entry().level]++;
        System.out.println("selection: wanted " + selection.wanted + ", drawn " + selection.draws.size() + " per level "
                + java.util.Arrays.toString(perLevel));
        check(selection.wanted < 2000, "wanted tiles stay bounded");
        // drawn tiles never overlap and cover the ring between the vanilla area and the reach
        Random random = new Random(11);
        int holes = 0, overlaps = 0;
        for (int n = 0; n < 20000; n++) {
            double a = random.nextDouble() * Math.PI * 2, d = outer + random.nextDouble() * (reach - outer - 64);
            double x = camX + Math.cos(a) * d, z = camZ + Math.sin(a) * d;
            int covering = 0;
            for (Selection.Draw dr : selection.draws) {
                TileEntry e = dr.entry();
                int span = FarTile.span(e.level);
                if (x >= (double) e.tx * span && x < (double) (e.tx + 1) * span && z >= (double) e.tz * span && z < (double) (e.tz + 1) * span) covering++;
            }
            if (covering == 0) holes++;
            if (covering > 1) overlaps++;
        }
        check(holes == 0, "no holes in the ring (" + holes + ")");
        check(overlaps == 0, "no overlaps (" + overlaps + ")");
        // the level drawn at a distance roughly follows detail: cell / distance <= 1 / detail * 2 (quadtree slack)
        int coarse = 0;
        for (Selection.Draw dr : selection.draws) {
            TileEntry e = dr.entry();
            int span = FarTile.span(e.level);
            double dx = Math.max(0, Math.max(e.tx * (double) span - camX, camX - (e.tx + 1) * (double) span));
            double dz = Math.max(0, Math.max(e.tz * (double) span - camZ, camZ - (e.tz + 1) * (double) span));
            double near = Math.sqrt(dx * dx + dz * dz);
            if (e.level > 0 && FarTile.cell(e.level) * detail > Math.max(near, 1) * 1.0001) coarse++;
        }
        check(coarse == 0, "no tile coarser than its distance allows (" + coarse + ")");

        // while only the coarse levels have arrived, nothing far too coarse stands in near the camera
        TileStore loading = new TileStore(new TileColors());
        selection.run(loading, camX, camZ, reach, detail, inner, outer, 0, now);
        for (int round = 0; round < 12; round++) {
            for (long k : keys(loading)) {
                TileEntry e = loading.peek(k);
                if (e != null && e.level >= 6 && e.mesh == null) e.mesh = new FarMesh(null, 10, 60, 80);
            }
            selection.run(loading, camX, camZ, reach, detail, inner, outer, 0, now);
        }
        int tooCoarse = 0, standIns = 0;
        for (Selection.Draw dr : selection.draws) {
            TileEntry e = dr.entry();
            int span = FarTile.span(e.level);
            double dx = Math.max(0, Math.max(e.tx * (double) span - camX, camX - (e.tx + 1) * (double) span));
            double dz = Math.max(0, Math.max(e.tz * (double) span - camZ, camZ - (e.tz + 1) * (double) span));
            double near = Math.sqrt(dx * dx + dz * dz);
            if (FarTile.cell(e.level) * detail > Math.max(near, 1)) standIns++;
            if (FarTile.cell(e.level) * detail > Selection.COARSEST * Math.max(near, 1) * 1.0001) tooCoarse++;
        }
        System.out.println("loading: drawn " + selection.draws.size() + ", standing in " + standIns);
        check(tooCoarse == 0, "no stand-in more than " + Selection.COARSEST + "x too coarse (" + tooCoarse + ")");
    }

    static long[] keys(TileStore store) {
        try {
            java.lang.reflect.Field f = TileStore.class.getDeclaredField("entries");
            f.setAccessible(true);
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?> map = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) f.get(store);
            return map.keySet().toLongArray();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
