package com.blockland.client.backpack;

import com.blockland.anim.Spring;
import com.blockland.mixin.inventory.ContainerScreenAccess;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the inventory screens draw of slots (UI spec §1), as vanilla's {@code renderSlot} / {@code renderCarriedItem}
 * decide it — the half picked up while splitting, the spread a drag will leave, the stack under the mouse — but with
 * BlockLand's decorations (counts, durability, cooldown) and no vanilla sprite; hover states ease in and out.
 */
final class SlotPainter {
    private final Map<Slot, Spring> hover = new HashMap<>();

    /** How much {@code slot} is hovered (0..1, eased). */
    float hover(Slot slot, boolean hovered) {
        Spring spring = hover.computeIfAbsent(slot, s -> new Spring(0).config(420, 30));
        spring.setTarget(hovered ? 1 : 0);
        return Mth.clamp(spring.get(), 0, 1);
    }

    /** The active slot under the mouse (relative to the screen's corner {@code left, top}), as vanilla finds it. */
    @Nullable
    static Slot under(AbstractContainerMenu menu, double mouseX, double mouseY, int left, int top) {
        double x = mouseX - left, y = mouseY - top;
        for (Slot slot : menu.slots) {
            if (slot.isActive() && x >= slot.x - 1 && x < slot.x + 17 && y >= slot.y - 1 && y < slot.y + 17) return slot;
        }
        return null;
    }

    /** A slot's stack (the item and its decorations) as vanilla's {@code renderSlot} would show it. */
    static void slot(GuiGraphics g, AbstractContainerScreen<?> screen, AbstractContainerMenu menu, Slot slot,
                     boolean quickCrafting, Set<Slot> quickSlots, int seedWidth) {
        ContainerScreenAccess state = (ContainerScreenAccess) screen;
        ItemStack stack = slot.getItem();
        ItemStack dragging = state.blockland$draggingItem();
        boolean takenAway = slot == state.blockland$clickedSlot() && !dragging.isEmpty() && !state.blockland$isSplittingStack();
        ItemStack carried = menu.getCarried();
        boolean preview = false;
        String count = null;
        if (slot == state.blockland$clickedSlot() && !dragging.isEmpty() && state.blockland$isSplittingStack() && !stack.isEmpty()) {
            stack = stack.copyWithCount(stack.getCount() / 2);
        } else if (quickCrafting && quickSlots.contains(slot) && !carried.isEmpty()) {
            if (quickSlots.size() == 1) return;
            if (AbstractContainerMenu.canItemQuickReplace(slot, carried, true) && menu.canDragTo(slot)) {
                preview = true;
                int max = Math.min(carried.getMaxStackSize(), slot.getMaxStackSize(carried));
                int there = slot.getItem().isEmpty() ? 0 : slot.getItem().getCount();
                int n = AbstractContainerMenu.getQuickCraftPlaceCount(quickSlots, state.blockland$quickCraftingType(), carried) + there;
                if (n > max) {
                    n = max;
                    count = String.valueOf(max);
                }
                stack = carried.copyWithCount(n);
            }
        }
        if (takenAway || stack.isEmpty()) return;
        if (preview) InventoryStyle.round(g, slot.x - 1, slot.y - 1, 18, 18, 3, InventoryStyle.grey(1, 0.35f));
        int seed = slot.x + slot.y * seedWidth;
        if (slot.isFake()) g.renderFakeItem(stack, slot.x, slot.y, seed);
        else g.renderItem(stack, slot.x, slot.y, seed);
        InventoryStyle.decorations(g, stack, slot.x, slot.y, count);
    }

    /** The stack on the mouse, as vanilla's {@code renderCarriedItem} shows it. */
    static void carried(GuiGraphics g, AbstractContainerScreen<?> screen, AbstractContainerMenu menu, int mouseX, int mouseY,
                        boolean quickCrafting, Set<Slot> quickSlots) {
        ContainerScreenAccess state = (ContainerScreenAccess) screen;
        ItemStack dragging = state.blockland$draggingItem();
        ItemStack stack = dragging.isEmpty() ? menu.getCarried() : dragging;
        if (stack.isEmpty()) return;
        int up = dragging.isEmpty() ? 8 : 16;
        String count = null;
        if (!dragging.isEmpty() && state.blockland$isSplittingStack()) {
            stack = stack.copyWithCount(Mth.ceil(stack.getCount() / 2.0F));
        } else if (quickCrafting && quickSlots.size() > 1) {
            int left = state.blockland$quickCraftingRemainder();
            if (left <= 0) count = "0";
            else stack = stack.copyWithCount(left);
        }
        g.nextStratum();
        g.renderItem(stack, mouseX - 8, mouseY - up);
        InventoryStyle.decorations(g, stack, mouseX - 8, mouseY - up - (dragging.isEmpty() ? 0 : 8), count);
    }

    /** The tooltip of the stack under the mouse (nothing while carrying one), in BlockLand's style. */
    static void tooltip(GuiGraphics g, @Nullable Slot hovered, ItemStack carried, Function<ItemStack, List<Component>> lines,
                        int mouseX, int mouseY, int width, int height) {
        if (hovered == null || !hovered.hasItem() || !carried.isEmpty()) return;
        ItemStack stack = hovered.getItem();
        // the lines in their own colours, and the item's tooltip image (a bundle's contents) under them
        ClientTooltipComponent image = stack.getTooltipImage().map(ClientTooltipComponent::create).orElse(null);
        InventoryStyle.tooltip(g, lines.apply(stack), image, mouseX, mouseY, width, height);
    }
}
