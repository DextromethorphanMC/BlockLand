package com.blockland.mixin.inventory;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a slot is drawn (UI spec §1): the inventory screens lay the player's slots out themselves. Only screen
 * positions — the server's menu never reads them.
 */
@Mixin(Slot.class)
public interface SlotAccess {
    @Mutable
    @Accessor("x")
    void blockland$setX(int x);

    @Mutable
    @Accessor("y")
    void blockland$setY(int y);
}
