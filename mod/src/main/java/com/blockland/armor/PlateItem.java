package com.blockland.armor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * An armour plate (防弹板, asset spec §50): held, a left-click puts it into the worn carrier (the client sends it, the
 * armour service checks and does it). The item only disables vanilla behaviour (mining, the vanilla hit, use) and
 * describes itself.
 */
public class PlateItem extends Item {
    public PlateItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
        return false;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.accept(Component.translatable("blockland.armor.tooltip.plate", Component.literal(String.valueOf(
                Math.round(ArmorRules.get().plateDurability))).withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("blockland.armor.tooltip.plate_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
