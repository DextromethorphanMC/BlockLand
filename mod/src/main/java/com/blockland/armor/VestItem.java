package com.blockland.armor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * The plate carrier (防弹衣, asset spec §50): worn in the chest slot; it protects through the plates in it (the armour
 * service) and is drawn on the body by the client's armour module. The item itself only describes itself.
 */
public class VestItem extends Item {
    public VestItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        ArmorRules rules = ArmorRules.get();
        VestState state = ArmorContent.stateOf(stack);
        tooltip.accept(Component.translatable("blockland.armor.tooltip.plates", Component.literal(state.count() + " / " + rules.maxPlates)
                .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        if (state.count() > 0) {
            tooltip.accept(Component.translatable("blockland.armor.tooltip.durability",
                    Component.literal(Math.round(state.total()) + " / " + Math.round(state.count() * rules.plateDurability))
                            .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(Component.translatable("blockland.armor.tooltip.vest_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
