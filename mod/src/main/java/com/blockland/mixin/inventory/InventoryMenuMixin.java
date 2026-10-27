package com.blockland.mixin.inventory;

import com.blockland.backpack.BackpackMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The backpack in the player's own inventory menu (UI spec §2): its slots after vanilla's, its shift-clicks, its
 * expand / collapse button, double-click collecting. Forwards to {@link BackpackMenus}.
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin extends AbstractCraftingMenu implements BackpackMenus.Host {
    @Unique
    private boolean blockland$backpackOpen;

    private InventoryMenuMixin() {
        super(null, 0, 2, 2);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void blockland$addBackpackSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        BackpackMenus.addSlots(owner, this);
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void blockland$quickMoveBackpack(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack moved = BackpackMenus.quickMove((InventoryMenu) (Object) this, player, index);
        if (moved != null) cir.setReturnValue(moved);
    }

    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true)
    private void blockland$collectBackpack(ItemStack stack, Slot slot, CallbackInfoReturnable<Boolean> cir) {
        if (!BackpackMenus.collects(this, slot)) cir.setReturnValue(false);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        return BackpackMenus.button(this, id) || super.clickMenuButton(player, id);
    }

    @Override
    public boolean blockland$backpackOpen() {
        return blockland$backpackOpen;
    }

    @Override
    public void blockland$setBackpackOpen(boolean open) {
        blockland$backpackOpen = open;
    }

    @Override
    public Slot blockland$addSlot(Slot slot) {
        return addSlot(slot);
    }

    @Override
    public boolean blockland$move(ItemStack stack, int from, int to, boolean backwards) {
        return moveItemStackTo(stack, from, to, backwards);
    }
}
