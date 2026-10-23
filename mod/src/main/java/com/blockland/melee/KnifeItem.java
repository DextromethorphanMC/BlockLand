package com.blockland.melee;

import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
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
 * A knife. Light and heavy attacks are handled by the melee service (server) and the knife viewmodel (client); the
 * item itself only disables vanilla behaviour (mining, the vanilla hit, block use) and describes itself. The arm
 * swing stays on so other players see the attack in third person.
 */
public class KnifeItem extends Item {
    private final String knifeId;

    public KnifeItem(String knifeId, Properties properties) {
        super(properties);
        this.knifeId = knifeId;
    }

    public String knifeId() {
        return knifeId;
    }

    @Override
    public Component getName(ItemStack stack) {
        return MeleeCatalog.get().knife(knifeId).map(MeleeData.Knife::displayName).orElseGet(() -> super.getName(stack));
    }

    @Override
    public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
        return false;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    /** The vanilla hit never lands: knife damage only comes from the melee service. */
    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true;
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        MeleeCatalog.get().knife(knifeId).ifPresent(knife -> {
            tooltip.accept(Component.translatable("blockland.knife.tooltip.light", Component.literal(format(knife.light.damage))
                    .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.translatable("blockland.knife.tooltip.heavy", Component.literal(format(knife.heavy.damage))
                    .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.translatable("blockland.knife.tooltip.backstab", Component.literal("×" + format(knife.heavy.backstabMultiplier))
                    .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(" ").append(Component.translatable("blockland.knife.tooltip.heavy_only")).withStyle(ChatFormatting.DARK_GRAY)));
            if (!knife.description.isEmpty()) tooltip.accept(Component.literal("§8" + knife.description));
            Build build = CraftCatalog.complete(knife, MeleeContent.buildOf(stack));
            for (Craftable.Slot slot : knife.slots()) {
                String option = build.option(slot.id(), slot.defaultOption());
                if (option.equals(CraftData.NONE)) continue;
                tooltip.accept(Component.translatable("blockland.craft.slot." + slot.id()).append(": ")
                        .append(knife.describe(slot.id(), option).name()).withStyle(ChatFormatting.GRAY));
            }
            CraftData.Skin skin = knife.skins.getOrDefault(build.skin(), knife.skins.get("default"));
            if (skin != null) {
                tooltip.accept(Component.translatable("blockland.craft.skin").append(": ")
                        .append(CraftCatalog.skinName(knife, skin)).withStyle(ChatFormatting.GRAY));
            }
            tooltip.accept(Component.translatable("blockland.craft.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }
}
