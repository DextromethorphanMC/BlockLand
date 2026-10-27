package com.blockland.mixin.inventory;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * What BlockLand's creative screen (UI spec §1) drives of vanilla's: its tab selection, search box, scroll position and
 * destroy-item slot — vanilla keeps all the creative behaviour, BlockLand only places and draws it.
 */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeScreenAccess {
    @Invoker("selectTab")
    void blockland$selectTab(CreativeModeTab tab);

    @Accessor("searchBox")
    EditBox blockland$searchBox();

    @Accessor("scrollOffs")
    float blockland$scrollOffs();

    @Accessor("scrollOffs")
    void blockland$setScrollOffs(float offs);

    @Accessor("destroyItemSlot")
    Slot blockland$destroyItemSlot();

    @Accessor("selectedTab")
    static CreativeModeTab blockland$selectedTab() {
        throw new AssertionError();
    }
}
