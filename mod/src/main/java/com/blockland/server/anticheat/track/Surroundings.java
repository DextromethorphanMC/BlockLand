package com.blockland.server.anticheat.track;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.HappyGhast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * What the blocks around a player do to their movement (anti-cheat spec §6): the slipperiness under them, liquids,
 * climbing, blocks that hold them (cobweb, powder snow, berry bushes), bubble columns and bouncy blocks. A handful of
 * block lookups at the feet and the head of a position; one instance per player, refilled per move. Also what a player
 * can stand on: blocks, and the entities their client collides with ({@link #platform}).
 */
public final class Surroundings {
    /**
     * The most and the least slippery block the client may take as its support: vanilla uses the block that actually holds
     * the player up, which at an edge can be any block under the box (slime or ice under one corner, the centre over
     * air or a lower block). The most slippery bounds the speed carried, the least slippery the acceleration.
     */
    public double slip = 0.6;
    public double slipAlt = 0.6;
    public boolean water;
    public boolean lava;
    public boolean climbable;
    /** Cobweb, powder snow, sweet berry bush: movement scaled down, vertical speed out of reach of the physics. */
    public boolean stuck;
    public boolean bubbleColumn;
    /** Slime block or bed under the feet (any corner: vanilla bounces off the block it stands on): a landing bounces. */
    public boolean bouncy;
    /** Against the side of a honey block, below its top: an airborne player slides down it slowly. */
    public boolean honey;

    /** Margin around a still platform (position rounding of the entity's sync, a boat settling on the water). */
    private static final double PLATFORM_SLACK = 0.05;
    /** The most a platform may be off the client's copy: beyond it, it is not what they stand on. */
    private static final double MAX_PLATFORM_SLACK = 0.6;
    /** Ticks the client interpolates an entity it is sent (boats: 3), on top of the latency. */
    private static final int INTERPOLATION_TICKS = 3;
    /** Widest margin around a pushing entity (a mob sprinting at the player over a long latency window). */
    public static final double MAX_PUSH_SLACK = 1.5;
    /** A shulker opening its lid lifts what stands on it by up to half a block, at its own pace on each side. */
    private static final double SHULKER_LID = 0.5;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /**
     * Samples around the player standing at {@code x, y, z} (their feet). {@code support} is the block vanilla's
     * movement uses below them (their position at the start of the tick), null to take the one under the centre only.
     * {@code backX, backZ} is the horizontal step that brought them here: with nothing under the box, vanilla takes the
     * block under the box moved back by it as the support ({@code Entity.checkSupportingBlock}) — a landing on a slime
     * block's edge that slides off it in the same tick still bounces and keeps the slime's friction.
     */
    public void sample(ServerLevel level, ServerPlayer player, double x, double y, double z, BlockPos support, double backX, double backZ) {
        double below = y - 0.5000001;
        cursor.set(x, below, z);
        double centre = friction(level, player, cursor);
        slip = centre;
        slipAlt = centre;
        if (support != null) widen(friction(level, player, support));
        // the corners' columns: the block that holds the box up at an edge, here and where the step came from
        double half = player.getBbWidth() * 0.5;
        corners(level, player, x, below, z, half);
        boolean sideways = backX * backX + backZ * backZ > 1e-8;
        if (sideways) corners(level, player, x - backX, below, z - backZ, half);
        water = false;
        lava = false;
        climbable = false;
        stuck = false;
        bubbleColumn = false;
        double height = player.getBbHeight();
        probe(level, x, y + 0.05, z);
        probe(level, x, y + height * 0.5, z);
        probe(level, x, y + Math.max(0.1, height - 0.1), z);
        honey = honey(level, player, x, y, z);
        bouncy = bouncy(level, x, y - 0.2, z) || bouncyCorners(level, x, y - 0.2, z, half)
                || sideways && bouncyCorners(level, x - backX, y - 0.2, z - backZ, half);
    }

    private void corners(ServerLevel level, ServerPlayer player, double x, double y, double z, double half) {
        corner(level, player, x - half, y, z - half);
        corner(level, player, x + half, y, z - half);
        corner(level, player, x - half, y, z + half);
        corner(level, player, x + half, y, z + half);
    }

    private boolean bouncyCorners(ServerLevel level, double x, double y, double z, double half) {
        return bouncy(level, x - half, y, z - half) || bouncy(level, x + half, y, z - half)
                || bouncy(level, x - half, y, z + half) || bouncy(level, x + half, y, z + half);
    }

    /**
     * Whether the box at {@code x, y, z} reaches into a honey block's cell from its side, no higher than vanilla's slide
     * allows ({@code HoneyBlock.isSlidingDown}: the block's collision is 1/16 inset, so a box touching it is inside it).
     */
    private boolean honey(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        double half = player.getBbWidth() * 0.5 + 0.07;
        int minX = (int) Math.floor(x - half), maxX = (int) Math.floor(x + half);
        int minZ = (int) Math.floor(z - half), maxZ = (int) Math.floor(z + half);
        int minY = (int) Math.floor(y), maxY = (int) Math.floor(y + player.getBbHeight());
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    if (y > by + 0.9375) continue;
                    cursor.set(bx, by, bz);
                    if (level.getBlockState(cursor).is(Blocks.HONEY_BLOCK)) return true;
                }
            }
        }
        return false;
    }

    private void corner(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        cursor.set(x, y, z);
        BlockState state = level.getBlockState(cursor);
        if (!state.isAir()) widen(state.getFriction(level, cursor, player));
    }

    private void widen(double friction) {
        slip = Math.max(slip, friction);
        slipAlt = Math.min(slipAlt, friction);
    }

    private boolean bouncy(ServerLevel level, double x, double y, double z) {
        cursor.set(x, y, z);
        BlockState below = level.getBlockState(cursor);
        return below.getBlock() instanceof SlimeBlock || below.getBlock() instanceof BedBlock;
    }

    private void probe(ServerLevel level, double x, double y, double z) {
        cursor.set(x, y, z);
        BlockState state = level.getBlockState(cursor);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.is(FluidTags.WATER)) water = true;
            if (fluid.is(FluidTags.LAVA)) lava = true;
        }
        if (state.is(BlockTags.CLIMBABLE)) climbable = true;
        if (state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)) stuck = true;
        if (state.is(Blocks.BUBBLE_COLUMN)) bubbleColumn = true;
    }

    private static double friction(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() ? 0.6 : state.getFriction(level, pos, player);
    }

    /** What lies under the box at {@code x, y, z} (its corners' blocks just below the feet), for evidence. */
    public static String below(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        StringBuilder text = new StringBuilder();
        java.util.Set<BlockPos> seen = new java.util.LinkedHashSet<>();
        for (double cx : new double[]{box.minX, box.maxX}) {
            for (double cz : new double[]{box.minZ, box.maxZ}) seen.add(BlockPos.containing(cx, y - 0.02, cz));
        }
        for (BlockPos pos : seen) {
            if (!text.isEmpty()) text.append("，");
            BlockState state = level.getBlockState(pos);
            text.append(pos.toShortString()).append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())
                    .append(state.getCollisionShape(level, pos).isEmpty() ? "（无碰撞）" : "");
        }
        return text.toString();
    }

    /**
     * Whether the player's collision box, placed at {@code x, y, z}, rests on something: blocks or collidable entities
     * (boats, shulkers) within a hair below its bottom. What vanilla's own client would call standing.
     */
    public static boolean supported(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        AABB below = new AABB(box.minX, box.minY - 0.04, box.minZ, box.maxX, box.minY, box.maxZ);
        return !level.noCollision(player, below);
    }

    /**
     * A collidable entity the box at {@code x, y, z} may stand on as its client sees it: a boat, a shulker, the back of an
     * adult happy ghast (whose client collides with it from above even where the server would not). The client shows a
     * moving entity where it was a few ticks ago (its interpolation plus the latency), so the entity's box is widened by
     * how far it moves in that time ({@link #platformSlack}). Null when there is none.
     *
     * @param lagTicks the player's latency window in ticks
     */
    public static Entity platform(ServerLevel level, ServerPlayer player, double x, double y, double z, int lagTicks) {
        AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        AABB feet = new AABB(box.minX, box.minY - 0.04, box.minZ, box.maxX, box.minY, box.maxZ);
        Entity best = null;
        double bestGap = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(player, feet.inflate(MAX_PLATFORM_SLACK), e -> isPlatform(player, e))) {
            double slack = platformSlack(entity, lagTicks);
            AABB top = entity.getBoundingBox();
            if (!top.inflate(slack).intersects(feet)) continue;
            // the feet at its top (within its slack), not somewhere down its side
            double gap = Math.abs(box.minY - top.maxY);
            if (gap <= slack + 0.04 && gap < bestGap) {
                best = entity;
                bestGap = gap;
            }
        }
        return best;
    }

    /** How far the client's copy of {@code entity} may be off the server's: what it moves in the lag, at least a hair. */
    public static double platformSlack(Entity entity, int lagTicks) {
        double moved = Math.sqrt(entity.distanceToSqr(entity.xo, entity.yo, entity.zo));
        double speed = Math.max(moved, entity.getDeltaMovement().length());
        double slack = PLATFORM_SLACK + speed * (lagTicks + INTERPOLATION_TICKS);
        if (entity instanceof Shulker) slack = Math.max(slack, SHULKER_LID + PLATFORM_SLACK);
        return Math.min(MAX_PLATFORM_SLACK, slack);
    }

    /**
     * How far the client's copy of a pushing entity may be off the server's: what it moves in the latency window and its
     * interpolation, plus vanilla's own push reach (boxes that touch push each other).
     */
    public static double pushSlack(Entity entity, int lagTicks) {
        double moved = Math.sqrt(entity.distanceToSqr(entity.xo, entity.yo, entity.zo));
        double speed = Math.max(moved, entity.getDeltaMovement().length());
        return Math.min(MAX_PUSH_SLACK, PLATFORM_SLACK + speed * (lagTicks + INTERPOLATION_TICKS));
    }

    private static boolean isPlatform(ServerPlayer player, Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) return false;
        if (entity instanceof HappyGhast ghast) return !ghast.isBaby();
        return player.canCollideWith(entity);
    }

    /** The collidable entities near the feet at {@code x, y, z} with their tops (for evidence), empty when none. */
    public static String platformsNear(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ()).inflate(1.5);
        StringBuilder text = new StringBuilder();
        for (Entity entity : level.getEntities(player, box, e -> isPlatform(player, e))) {
            if (!text.isEmpty()) text.append("，");
            text.append(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
                    .append(String.format(java.util.Locale.ROOT, " 顶 %.3f（每 tick 移动 %.3f）", entity.getBoundingBox().maxY,
                            Math.sqrt(entity.distanceToSqr(entity.xo, entity.yo, entity.zo))));
        }
        return text.toString();
    }
}
