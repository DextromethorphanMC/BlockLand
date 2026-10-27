package com.blockland.backpack;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * A backpack (UI spec §2): worn in the backpack slot, it adds {@link BackpackData.Pack#rows} rows of storage to the
 * inventory. What it holds lives on the stack ({@code minecraft:container}), so it travels with the item when it is
 * taken off, dropped or traded. Used from the hand it goes on the back (swapping with the one worn).
 */
public class BackpackItem extends Item {
    private final BackpackData.Pack pack;

    public BackpackItem(BackpackData.Pack pack, Properties properties) {
        super(properties);
        this.pack = pack;
    }

    public BackpackData.Pack pack() {
        return pack;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack worn = Backpacks.worn(player);
        if (!level.isClientSide) {
            Backpacks.wear(player, held.copyAndClear());
            player.setItemInHand(hand, worn);
        }
        player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1.0F, 1.0F);
        return InteractionResult.SUCCESS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.translatable("blockland.backpack.capacity", pack.slots()).withStyle(ChatFormatting.GRAY));
        int used = Backpacks.usedSlots(stack);
        if (used > 0) lines.accept(Component.translatable("blockland.backpack.used", used, pack.slots()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
