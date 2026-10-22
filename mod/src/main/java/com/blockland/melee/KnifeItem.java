package com.blockland.melee;

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
        return MeleeCatalog.get().knife(knifeId).<Component>map(knife -> Component.literal(knife.name)).orElseGet(() -> super.getName(stack));
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
            tooltip.accept(Component.literal("§7轻击（左键） §f" + format(knife.light.damage) + " §7伤害"));
            tooltip.accept(Component.literal("§7重击（右键） §f" + format(knife.heavy.damage) + " §7伤害"));
            tooltip.accept(Component.literal("§7背刺 §f×" + format(knife.heavy.backstabMultiplier) + " §8（重击）"));
            if (!knife.description.isEmpty()) tooltip.accept(Component.literal("§8" + knife.description));
        });
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }
}
