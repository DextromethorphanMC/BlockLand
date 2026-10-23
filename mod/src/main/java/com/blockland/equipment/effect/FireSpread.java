package com.blockland.equipment.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a molotov's fire lies: the cells it floods from the impact, along floors (stepping one block up or two down),
 * stopped by walls, within the radius. Pure function of the blocks around, so the server (damage) and the client
 * (flames) compute the same patch from the same impact point without sending it.
 */
public final class FireSpread {
    /** Most cells one patch covers. */
    private static final int MAX_CELLS = 96;

    /**
     * A burning cell: the air block {@code x y z} over a floor whose top is at {@code floorY}.
     *
     * @param delay 0..1, when the cell catches fire as a share of the spread time (nearer cells first)
     */
    public record Cell(int x, int y, int z, double floorY, float delay) {
        public double centreX() {
            return x + 0.5;
        }

        public double centreZ() {
            return z + 0.5;
        }
    }

    private FireSpread() {
    }

    /**
     * @param impact   where the bottle broke
     * @param maxFall  how far below the impact the fire may land
     * @return the cells, nearest first; empty when the bottle broke in water or over the void
     */
    public static List<Cell> cells(BlockGetter level, Vec3 impact, float radius, int maxFall) {
        BlockPos start = BlockPos.containing(impact);
        if (!passable(level, start)) start = start.above();
        if (!passable(level, start)) return List.of();
        int fell = 0;
        while (!hasFloor(level, start)) {
            if (++fell > maxFall) return List.of();
            start = start.below();
            if (!passable(level, start)) return List.of();
        }
        if (!level.getFluidState(start).isEmpty()) return List.of();
        List<Cell> cells = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        double r2 = radius * radius, sx = start.getX() + 0.5, sz = start.getZ() + 0.5;
        while (!queue.isEmpty() && cells.size() < MAX_CELLS) {
            BlockPos at = queue.poll();
            double dx = at.getX() + 0.5 - sx, dz = at.getZ() + 0.5 - sz;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            // a little jitter so the front does not advance as a perfect circle
            float jitter = (Math.floorMod(at.getX() * 734287 + at.getZ() * 912271 + at.getY() * 438289, 1000) / 1000f - 0.5f) * 0.18f;
            cells.add(new Cell(at.getX(), at.getY(), at.getZ(), floorTop(level, at), Math.max(0, Math.min(1, distance / Math.max(radius, 0.5f) + jitter))));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = step(level, at, at.relative(direction));
                if (next == null || !seen.add(next)) continue;
                double nx = next.getX() + 0.5 - sx, nz = next.getZ() + 0.5 - sz;
                if (nx * nx + nz * nz > r2) continue;
                if (!level.getFluidState(next).isEmpty()) continue;
                queue.add(next);
            }
        }
        return cells;
    }

    /** The cell fire reaches from {@code from} going towards {@code side}: level, a step up, or up to two down. */
    private static BlockPos step(BlockGetter level, BlockPos from, BlockPos side) {
        if (passable(level, side)) {
            if (hasFloor(level, side)) return side;
            BlockPos down = side.below();
            if (passable(level, down)) {
                if (hasFloor(level, down)) return down;
                BlockPos down2 = down.below();
                if (passable(level, down2) && hasFloor(level, down2)) return down2;
            }
            return null;
        }
        BlockPos up = side.above();
        return passable(level, from.above()) && passable(level, up) && hasFloor(level, up) ? up : null;
    }

    private static boolean passable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean hasFloor(BlockGetter level, BlockPos pos) {
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static double floorTop(BlockGetter level, BlockPos pos) {
        BlockPos below = pos.below();
        VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
        return shape.isEmpty() ? pos.getY() : below.getY() + Math.min(1, shape.max(Direction.Axis.Y));
    }
}
