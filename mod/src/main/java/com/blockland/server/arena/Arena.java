package com.blockland.server.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** A reserved region of the arena dimension in which one match is played. */
public record Arena(int slot, ServerLevel level, BlockPos origin, Vec3i size) {

    public BlockPos toWorld(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    public Vec3 toWorld(double x, double y, double z) {
        return new Vec3(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
    }

    public Vec3 center() {
        return new Vec3(origin.getX() + size.getX() / 2.0, origin.getY() + size.getY() / 2.0, origin.getZ() + size.getZ() / 2.0);
    }

    /** Area that gets wiped after the match: the map bounds plus a margin for things players built. */
    public BoundingBox cleanupBox(int margin) {
        int minY = Math.max(level.getMinY(), origin.getY() - margin);
        int maxY = Math.min(level.getMaxY(), origin.getY() + size.getY() + margin);
        return new BoundingBox(origin.getX() - margin, minY, origin.getZ() - margin,
                origin.getX() + size.getX() + margin, maxY, origin.getZ() + size.getZ() + margin);
    }
}
