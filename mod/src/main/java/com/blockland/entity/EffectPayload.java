package com.blockland.entity;

import net.minecraft.resources.ResourceLocation;

/** A projectile that puts a status effect on whatever it hits; whoever fires it decides which one and how long. */
public interface EffectPayload {
    /**
     * @param effect    mob effect id
     * @param ticks     duration in ticks
     * @param amplifier level (0 = I)
     */
    void setPayload(ResourceLocation effect, int ticks, int amplifier);
}
