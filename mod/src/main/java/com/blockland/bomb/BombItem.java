package com.blockland.bomb;

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
 * The bomb (炸药包). Planting is the 枪战爆破 game's business (server) and the client bomb module's (input, hand); the
 * item only disables vanilla behaviour (mining, the vanilla hit, block use) and describes itself. Tossing it (Q) never
 * makes a free-for-all item in a match: the drop is handed to {@link #setDropHandler the game}, which puts it down as a
 * bomb only the attackers can pick up.
 */
public class BombItem extends Item {
    /** What happens when a player tosses the bomb (set by the server at start-up). */
    public interface DropHandler {
        DropHandler NONE = player -> false;

        /** @return true when the game took the bomb (the vanilla drop is skipped), false for a plain drop */
        boolean tossed(Player player);
    }

    private static volatile DropHandler dropHandler = DropHandler.NONE;

    public BombItem(Properties properties) {
        super(properties);
    }

    public static void setDropHandler(DropHandler handler) {
        dropHandler = handler == null ? DropHandler.NONE : handler;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.blockland.c4");
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

    /** In a bomb match the game takes the stack away and puts the bomb down itself; elsewhere it drops as usual. */
    @Override
    public boolean onDroppedByPlayer(ItemStack item, Player player) {
        return player.level().isClientSide() || !dropHandler.tossed(player);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.accept(Component.translatable("blockland.bomb.tooltip.plant").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("blockland.bomb.tooltip.drop").withStyle(ChatFormatting.DARK_GRAY));
    }
}
