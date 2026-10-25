package com.blockland.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ammo crate's lid. The server opens it with a block event ({@link #EVENT_OPEN}, the ticks it stays open) that every
 * client tracking the chunk receives; each client then plays the {@code open} clip, holds the lid open and plays
 * {@code close} once the time is up (asset spec §46). Nothing is saved: a crate always loads shut.
 */
public class AmmoCrateBlockEntity extends BlockEntity {
    public static final int EVENT_OPEN = 1;

    /** Client: game tick the lid started opening and the tick it starts closing (Long.MIN_VALUE = never opened). */
    private long openedAt = Long.MIN_VALUE;
    private long closesAt = Long.MIN_VALUE;

    public AmmoCrateBlockEntity(BlockPos pos, BlockState state) {
        super(SupplyContent.AMMO_CRATE_ENTITY.get(), pos, state);
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id != EVENT_OPEN) return super.triggerEvent(id, param);
        if (level != null && level.isClientSide) {
            long now = level.getGameTime();
            // open and not yet closing: it just stays open longer; shut or closing: it opens (again)
            if (openedAt == Long.MIN_VALUE || now >= closesAt) openedAt = now;
            closesAt = Math.max(closesAt, now + Math.max(1, param));
        }
        return true;
    }

    /** Ticks since the lid started opening (with the partial tick); negative while it has never opened. */
    public float openTime(float partialTick) {
        if (level == null || openedAt == Long.MIN_VALUE) return -1;
        return level.getGameTime() - openedAt + partialTick;
    }

    /** Ticks since the lid started closing (with the partial tick); negative while it opens or stays open. */
    public float closeTime(float partialTick) {
        if (level == null || openedAt == Long.MIN_VALUE) return -1;
        return level.getGameTime() - closesAt + partialTick;
    }

    static void clientTick(Level level, BlockPos pos, BlockState state, AmmoCrateBlockEntity crate) {
        if (crate.closesAt != Long.MIN_VALUE && level.getGameTime() == crate.closesAt) {
            level.playLocalSound(pos, SupplyContent.sound("supply.crate_close"), SoundSource.BLOCKS, 0.9f,
                    0.95f + level.random.nextFloat() * 0.1f, false);
        }
    }
}
