package com.blockland.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * 苦力怕僵尸: a zombie full of gunpowder, drawn with the zombie model and its own creeper-green skin. Infected players
 * are drawn as it (morph), so it never needs to spawn naturally; as a mob it is a zombie that neither burns in daylight
 * nor drowns into another mob. While its player burns a fuse the client sets {@link #fuse} on the morph proxy, and
 * the renderer swells and flashes it like a creeper.
 */
public class CreeperZombie extends Zombie {
    /** Client only: when the fuse was lit ({@code System.nanoTime}) and how long it burns (0 = not burning). */
    private long fuseStart;
    private float fuseSeconds;

    public CreeperZombie(EntityType<? extends CreeperZombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    /** Client: a fuse of {@code seconds} lit at {@code startNanos} (0 seconds = not burning). */
    public void fuse(long startNanos, float seconds) {
        fuseStart = startNanos;
        fuseSeconds = seconds;
    }

    /** Client: 0..1 how far the fuse has burnt (0 = not burning), like a creeper's swelling. */
    public float swelling() {
        if (fuseSeconds <= 0) return 0;
        return Math.min(1f, (System.nanoTime() - fuseStart) / (fuseSeconds * 1e9f));
    }
}
