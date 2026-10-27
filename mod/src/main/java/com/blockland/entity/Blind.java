package com.blockland.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * 瞎子: an eyeless, pale infected with a rag tied over where its eyes were, hunched and listening. Its model and texture
 * are its own (a modified zombie); as a mob it behaves like a zombie that neither burns in daylight nor drowns into
 * another mob. Infected players are drawn as it (morph), so it never needs to spawn naturally.
 */
public class Blind extends Zombie {
    public Blind(EntityType<? extends Blind> type, Level level) {
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
