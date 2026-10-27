package com.blockland.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A block that answers a round striking it (bullets are traced, not projectile entities, so vanilla's
 * {@code onProjectileHit} never sees them): the gun service calls {@link #struck} for every round that ends on a block
 * face, without knowing which blocks care. Server thread only.
 */
public interface BulletReactive {
    /**
     * @param at   where the round struck the face (world space)
     * @param face the face it struck
     */
    void onBulletHit(ServerLevel level, BlockState state, BlockPos pos, Vec3 at, Direction face, @Nullable Entity shooter);

    /** A round ended on {@code face} at {@code at}: tells the block behind the face if it answers rounds. */
    static void struck(ServerLevel level, Vec3 at, Direction face, @Nullable Entity shooter) {
        BlockPos pos = BlockPos.containing(at.x - face.getStepX() * 0.01, at.y - face.getStepY() * 0.01, at.z - face.getStepZ() * 0.01);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BulletReactive reactive) reactive.onBulletHit(level, state, pos, at, face, shooter);
    }
}
