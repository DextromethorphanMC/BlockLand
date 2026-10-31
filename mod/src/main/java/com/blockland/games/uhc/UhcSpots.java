package com.blockland.games.uhc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Random dry surface spots inside a square, some distance apart — where UHC players appear. Found without stalling the
 * server: each candidate's chunk is generated in the background (a short-lived ticket, renewed every tick until the
 * chunk is there) and looked at once loaded; a few candidates are in flight at a time. Spots that keep failing (sea,
 * no room) make the search accept closer spots. {@link #finish} completes what is missing synchronously, for when
 * the time to look is up.
 */
final class UhcSpots {
    /** Candidate chunks generated at once. */
    private static final int PARALLEL = 6;
    /** Columns tried in a loaded candidate chunk. */
    private static final int COLUMNS = 12;
    /** Failed candidates before the spacing is relaxed by a quarter. */
    private static final int RELAX_AFTER = 8;
    /** Synchronous candidates per missing spot in {@link #finish}. */
    private static final int SYNC_TRIES = 24;

    private final ServerLevel level;
    private final double centreX, centreZ, half;
    private final int wanted;
    private final RandomSource random;
    private final List<BlockPos> found = new ArrayList<>();
    private final List<ChunkPos> pending = new ArrayList<>();
    private double spacing;
    private int failures;

    /**
     * @param half    half-width of the square spots lie in
     * @param spacing the distance spots keep from each other (relaxed when the terrain does not allow it)
     */
    UhcSpots(ServerLevel level, double centreX, double centreZ, double half, int wanted, double spacing, RandomSource random) {
        this.level = level;
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.half = Math.max(1, half);
        this.wanted = wanted;
        this.spacing = spacing;
        this.random = random;
    }

    boolean done() {
        return found.size() >= wanted;
    }

    int foundCount() {
        return Math.min(found.size(), wanted);
    }

    /** Server thread, every tick until {@link #done}: keeps the generation going and takes the chunks that are ready. */
    void tick() {
        ServerChunkCache chunks = level.getChunkSource();
        if (done()) {
            // the spots found stay loaded (with a chunk around them) until the players are there
            for (BlockPos spot : found) chunks.addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(spot), 1);
            return;
        }
        for (Iterator<ChunkPos> it = pending.iterator(); it.hasNext(); ) {
            ChunkPos pos = it.next();
            if (!chunks.hasChunk(pos.x, pos.z)) {
                chunks.addTicketWithRadius(TicketType.ENDER_PEARL, pos, 0);
                continue;
            }
            it.remove();
            take(spotIn(pos));
            if (done()) {
                pending.clear();
                return;
            }
        }
        while (pending.size() < PARALLEL && found.size() + pending.size() < wanted + PARALLEL) {
            BlockPos candidate = candidate();
            if (candidate == null) break;
            ChunkPos pos = new ChunkPos(candidate);
            if (pending.contains(pos)) break;
            pending.add(pos);
            chunks.addTicketWithRadius(TicketType.ENDER_PEARL, pos, 0);
        }
    }

    /**
     * The spots, completed synchronously when some are still missing (generating the chunks on the spot): closer
     * together, then anywhere dry, then the plain surface of the middle as a last resort.
     */
    List<BlockPos> finish() {
        pending.clear();
        for (int tries = 0; !done() && tries < SYNC_TRIES * wanted; tries++) {
            BlockPos candidate = candidate();
            if (candidate == null) continue;
            level.getChunk(candidate.getX() >> 4, candidate.getZ() >> 4);
            take(spotIn(new ChunkPos(candidate)));
        }
        spacing = 0;
        while (!done()) {
            BlockPos candidate = candidate();
            BlockPos spot = null;
            if (candidate != null) {
                level.getChunk(candidate.getX() >> 4, candidate.getZ() >> 4);
                spot = spotIn(new ChunkPos(candidate));
            }
            if (spot == null) {
                int x = (int) Math.floor(centreX), z = (int) Math.floor(centreZ);
                level.getChunk(x >> 4, z >> 4);
                spot = surface(x, z);
            }
            found.add(spot);
        }
        return List.copyOf(found.subList(0, wanted));
    }

    private void take(@Nullable BlockPos spot) {
        if (spot != null && apart(spot)) {
            found.add(spot);
            return;
        }
        if (++failures % RELAX_AFTER == 0) spacing *= 0.75;
    }

    /** A random column in the square away from the spots found so far, or null when none came up in a few tries. */
    @Nullable
    private BlockPos candidate() {
        for (int i = 0; i < 16; i++) {
            BlockPos at = BlockPos.containing(centreX + (random.nextDouble() * 2 - 1) * half, 0, centreZ + (random.nextDouble() * 2 - 1) * half);
            if (apart(at)) return at;
        }
        spacing *= 0.75;
        return null;
    }

    private boolean apart(BlockPos at) {
        double min = spacing * spacing;
        for (BlockPos spot : found) {
            double dx = spot.getX() - at.getX(), dz = spot.getZ() - at.getZ();
            if (dx * dx + dz * dz < min) return false;
        }
        return true;
    }

    /** A dry standing spot in a loaded chunk (a few random columns of it), inside the square, or null. */
    @Nullable
    private BlockPos spotIn(ChunkPos chunk) {
        for (int i = 0; i < COLUMNS; i++) {
            int x = chunk.getMinBlockX() + random.nextInt(16), z = chunk.getMinBlockZ() + random.nextInt(16);
            if (Math.abs(x + 0.5 - centreX) > half || Math.abs(z + 0.5 - centreZ) > half) continue;
            BlockPos feet = surface(x, z);
            if (dry(feet)) return feet;
        }
        return null;
    }

    /** The block above the highest solid one (leaves do not count) at (x, z). */
    private BlockPos surface(int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Standing on a full block that is not leaves, no fluid underfoot or at head height, room for a player. */
    private boolean dry(BlockPos feet) {
        if (feet.getY() <= level.getMinY() + 1) return false;
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LEAVES)
                || !Block.isFaceFull(ground.getCollisionShape(level, below), Direction.UP)) return false;
        for (BlockPos body : new BlockPos[]{feet, feet.above()}) {
            BlockState state = level.getBlockState(body);
            if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, body).isEmpty()) return false;
        }
        return true;
    }
}
