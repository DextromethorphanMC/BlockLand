package com.blockland.parachute;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * The parachute (降落伞): worn in the chest slot (right click equips it, like an elytra). Opening and closing the
 * canopy is the parachute module's (jump in the air); the item only describes itself.
 */
public class ParachuteItem extends Item {
    public ParachuteItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.accept(Component.translatable("blockland.parachute.tooltip.wear").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("blockland.parachute.tooltip.use").withStyle(ChatFormatting.GRAY));
    }
}
