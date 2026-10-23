package com.blockland.equipment;

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
 * A piece of equipment (grenade, molotov, throwing knife, injector). Throwing and using are handled by the equipment
 * service (server) and the client equipment module; the item itself only disables vanilla behaviour (mining, the
 * vanilla hit, block use) and describes itself.
 */
public class EquipmentItem extends Item {
    private final String equipmentId;

    public EquipmentItem(String equipmentId, Properties properties) {
        super(properties);
        this.equipmentId = equipmentId;
    }

    public String equipmentId() {
        return equipmentId;
    }

    @Override
    public Component getName(ItemStack stack) {
        return EquipmentCatalog.get().item(equipmentId).map(EquipmentData.Equipment::displayName).orElseGet(() -> super.getName(stack));
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
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        EquipmentCatalog.get().item(equipmentId).ifPresent(item -> {
            tooltip.accept(Component.translatable("blockland.equipment.tooltip." + item.kind).withStyle(ChatFormatting.GRAY));
            if (item.fuse > 0) {
                tooltip.accept(Component.translatable("blockland.equipment.tooltip.fuse", Component.literal(format(item.fuse) + "s")
                        .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
            }
            if (!item.description.isEmpty()) tooltip.accept(Component.literal("§8" + item.description));
            Build build = CraftCatalog.complete(item, EquipmentContent.buildOf(stack));
            for (Craftable.Slot slot : item.slots()) {
                String option = build.option(slot.id(), slot.defaultOption());
                if (option.equals(CraftData.NONE)) continue;
                tooltip.accept(Component.translatable("blockland.craft.slot." + slot.id()).append(": ")
                        .append(item.describe(slot.id(), option).name()).withStyle(ChatFormatting.GRAY));
            }
            CraftData.Skin skin = item.skins.getOrDefault(build.skin(), item.skins.get("default"));
            if (skin != null) {
                tooltip.accept(Component.translatable("blockland.craft.skin").append(": ")
                        .append(CraftCatalog.skinName(item, skin)).withStyle(ChatFormatting.GRAY));
            }
            tooltip.accept(Component.translatable("blockland.craft.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }
}
