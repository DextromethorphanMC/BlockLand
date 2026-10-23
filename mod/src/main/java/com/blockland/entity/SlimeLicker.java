package com.blockland.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * 粘液舔舐者: a slime-covered zombie variant. Its model and texture are its own (a modified zombie); as a mob it
 * behaves like a zombie that neither burns in daylight nor drowns into another mob. Infected players can be drawn
 * as it (morph), so it never needs to spawn naturally.
 */
public class SlimeLicker extends Zombie {
    public SlimeLicker(EntityType<? extends SlimeLicker> type, Level level) {
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
}
