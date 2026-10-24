package com.blockland.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * A round's flight through fluids, shared by the server's hit registration and the shooter's prediction (so both stop
 * the round at the same place and the impacts the shooter sees are the ones the server counts). Air does not slow a
 * round over a gun's range; water and lava do, by far: water is about 800 times denser than air, and a rifle round is
 * spent in a metre or two of it (it yaws, tumbles and breaks up), a pistol round in a little more, buckshot in less,
 * and lava stops anything within a fraction of a block. A round meeting a level water surface at a grazing angle
 * skips off it instead of going in.
 *
 * <p>The model: along the path the round's energy (1 at the muzzle; damage scales with it) falls linearly with the
 * fluid it has gone through — {@code 1/waterRange} per block of water, {@code 1/lavaRange} per block of lava — and the
 * round is spent where it reaches 0. Each fluid block is its fluid's box (the block's width, up to the fluid's height),
 * walked voxel by voxel (Amanatides–Woo), so a puddle a few pixels deep is only those pixels.
 */
public final class Ballistics {
    /** Fluid intervals kept per path; a longer path through more separate pools keeps the first ones. */
    private static final int MAX_SPANS = 16;

    /**
     * How a round fares in fluids (per ammo, {@code guns.json}).
     *
     * @param waterRange blocks of water that spend it (0 = water does not slow it)
     * @param lavaRange  blocks of lava that spend it (0 = lava does not slow it)
     * @param skipAngle  below this angle (degrees) to a level water surface it skips off (0 = never)
     */
    public record Medium(float waterRange, float lavaRange, float skipAngle) {
        public static final Medium NONE = new Medium(0, 0, 0);
    }

    /**
     * What fills the blocks along a path: the height of the fluid standing in the block at (x, y, z), 0..1 of the block
     * (0 = none), negative for lava. The world's is {@link #fluids(BlockGetter)}; checks can make their own.
     */
    @FunctionalInterface
    public interface Fluids {
        double at(int x, int y, int z);
    }

    /** Where a round goes and what it goes through. */
    public static final class Path {
        public final Vec3 from, dir;
        /** How far it flies: to the block (or its range), or to where it is spent or skips. */
        public final double length;
        /** It was spent in a fluid before reaching {@link #length}'s block. */
        public final boolean spent;
        /** It skipped off a water surface at {@link #length}. */
        public final boolean skipped;
        /** The fluid intervals along the path (distances from {@link #from}): start, end, and whether it is lava. */
        private final double[] starts, ends;
        private final boolean[] lava;
        private final boolean[] surfaceEntry;
        private final int spans;
        private final Medium medium;

        private Path(Vec3 from, Vec3 dir, double length, boolean spent, boolean skipped, double[] starts, double[] ends,
                     boolean[] lava, boolean[] surfaceEntry, int spans, Medium medium) {
            this.from = from;
            this.dir = dir;
            this.length = length;
            this.spent = spent;
            this.skipped = skipped;
            this.starts = starts;
            this.ends = ends;
            this.lava = lava;
            this.surfaceEntry = surfaceEntry;
            this.spans = spans;
            this.medium = medium;
        }

        /** The point {@code distance} blocks along the path. */
        public Vec3 at(double distance) {
            return from.add(dir.scale(distance));
        }

        /** Where the round stops. */
        public Vec3 end() {
            return at(length);
        }

        /** It stopped short of the block (or the range) it was flying at: spent in a fluid or skipped off one. */
        public boolean stoppedInFluid() {
            return spent || skipped;
        }

        /** Fluid intervals on the way (cut at {@link #length}). */
        public int spans() {
            return spans;
        }

        public double spanStart(int i) {
            return starts[i];
        }

        public double spanEnd(int i) {
            return Math.min(ends[i], length);
        }

        public boolean spanLava(int i) {
            return lava[i];
        }

        /** The span was entered from the air through a level surface (a splash; NaN-free: false for side entries). */
        public boolean spanFromSurface(int i) {
            return surfaceEntry[i];
        }

        /** Whether the round starts in a fluid (fired from under water). */
        public boolean startsInFluid() {
            return spans > 0 && starts[0] <= 1e-6;
        }

        /** The round's energy {@code distance} blocks along (1 = as fired; damage and the impact scale with it). */
        public float energyAt(double distance) {
            double e = 1;
            for (int i = 0; i < spans && starts[i] < distance; i++) {
                float range = lava[i] ? medium.lavaRange() : medium.waterRange();
                if (range <= 0) continue;
                e -= (Math.min(ends[i], distance) - starts[i]) / range;
                if (e <= 0) return 0;
            }
            return (float) Math.max(0, e);
        }
    }

    private Ballistics() {
    }

    /**
     * The flight from {@code from} along {@code dir} (unit) towards whatever lies {@code distance} blocks on (the block
     * the round would hit, or its range): the fluids on the way, and where it is spent or skips.
     */
    public static Path trace(BlockGetter level, Vec3 from, Vec3 dir, double distance, Medium medium) {
        return trace(fluids(level), from, dir, distance, medium);
    }

    /** {@link #trace(BlockGetter, Vec3, Vec3, double, Medium)} through any {@link Fluids}. */
    public static Path trace(Fluids fluids, Vec3 from, Vec3 dir, double distance, Medium medium) {
        double[] starts = new double[MAX_SPANS], ends = new double[MAX_SPANS];
        boolean[] lava = new boolean[MAX_SPANS], surface = new boolean[MAX_SPANS];
        int spans = walk(fluids, from, dir, distance, starts, ends, lava, surface);

        double length = distance;
        boolean spent = false, skipped = false;
        double energy = 1;
        for (int i = 0; i < spans; i++) {
            // a level water surface met from the air at a grazing angle: the round skips off it
            if (!lava[i] && surface[i] && medium.skipAngle() > 0 && starts[i] > 1e-6
                    && -dir.y < Math.sin(Math.toRadians(medium.skipAngle()))) {
                length = starts[i];
                skipped = true;
                break;
            }
            float range = lava[i] ? medium.lavaRange() : medium.waterRange();
            if (range <= 0) continue;
            double left = energy * range;
            if (starts[i] + left < ends[i]) {
                length = starts[i] + left;
                spent = true;
                break;
            }
            energy -= (ends[i] - starts[i]) / range;
        }
        return new Path(from, dir, length, spent, skipped, starts, ends, lava, surface, spans, medium);
    }

    /** The world's fluids: water and lava (by their tags), standing as high as {@link FluidState#getHeight} says. */
    public static Fluids fluids(BlockGetter level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            pos.set(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) return 0;
            if (fluid.is(FluidTags.WATER)) return fluid.getHeight(level, pos);
            return fluid.is(FluidTags.LAVA) ? -fluid.getHeight(level, pos) : 0;
        };
    }

    /** The fluid intervals of the ray, merged where they touch; returns how many. */
    private static int walk(Fluids fluids, Vec3 from, Vec3 dir, double distance, double[] starts, double[] ends,
                            boolean[] lava, boolean[] surface) {
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int stepX = dir.x > 0 ? 1 : dir.x < 0 ? -1 : 0, stepY = dir.y > 0 ? 1 : dir.y < 0 ? -1 : 0,
                stepZ = dir.z > 0 ? 1 : dir.z < 0 ? -1 : 0;
        double deltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double deltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double deltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double maxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? x + 1 - from.x : from.x - x) * deltaX;
        double maxY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? y + 1 - from.y : from.y - y) * deltaY;
        double maxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? z + 1 - from.z : from.z - z) * deltaZ;
        int spans = 0;
        double enter = 0;
        int limit = (int) Math.ceil(distance * 3) + 4;
        for (int n = 0; n < limit && enter <= distance; n++) {
            double exit = Math.min(Math.min(maxX, maxY), Math.min(maxZ, distance));
            double height = fluids.at(x, y, z);
            if (height != 0) {
                boolean hot = height < 0;
                // the fluid's box in this block: the ray's part below its top
                double top = y + Math.abs(height);
                double a = enter, b = exit;
                double ya = from.y + dir.y * a, yb = from.y + dir.y * b;
                boolean fromTop = false;
                if (ya > top && yb > top) {
                    a = b;
                } else if (ya > top) {
                    a = (top - from.y) / dir.y;
                    fromTop = true;
                } else if (yb > top) {
                    b = (top - from.y) / dir.y;
                }
                if (b > a + 1e-7) {
                    if (spans > 0 && lava[spans - 1] == hot && Math.abs(ends[spans - 1] - a) < 1e-6) {
                        ends[spans - 1] = b;
                    } else if (spans < starts.length) {
                        starts[spans] = a;
                        ends[spans] = b;
                        lava[spans] = hot;
                        surface[spans] = fromTop && dir.y < 0;
                        spans++;
                    }
                }
            }
            if (exit >= distance) break;
            enter = exit;
            if (maxX <= maxY && maxX <= maxZ) {
                x += stepX;
                maxX += deltaX;
            } else if (maxY <= maxZ) {
                y += stepY;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }
        }
        if (spans < starts.length) {
            Arrays.fill(starts, spans, starts.length, Double.MAX_VALUE);
        }
        return spans;
    }
}
