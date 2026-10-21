package com.blockland.server.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Resets a box to air (and removes non-player entities inside it). */
public final class ClearJob implements WorldJob {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final BoundingBox box;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private boolean entitiesRemoved;
    private boolean done;

    public ClearJob(ServerLevel level, BoundingBox box) {
        this.level = level;
        this.box = box;
        this.cursor.set(box.minX(), box.minY(), box.minZ());
    }

    @Override
    public int step(int budget) {
        if (!entitiesRemoved) {
            AABB area = AABB.of(box);
            for (Entity entity : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) {
                entity.discard();
            }
            entitiesRemoved = true;
        }
        int used = 0;
        while (!done && used < budget) {
            if (!level.getBlockState(cursor).isAir()) {
                // empty containers first so removing them does not drop their items
                if (level.getBlockEntity(cursor) instanceof Clearable clearable) clearable.clearContent();
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            used++;
            advance();
        }
        return used;
    }

    private void advance() {
        int x = cursor.getX() + 1;
        int y = cursor.getY();
        int z = cursor.getZ();
        if (x > box.maxX()) {
            x = box.minX();
            z++;
            if (z > box.maxZ()) {
                z = box.minZ();
                y++;
                if (y > box.maxY()) {
                    done = true;
                    return;
                }
            }
        }
        cursor.set(x, y, z);
    }

    @Override
    public boolean isDone() {
        return done;
    }
}
