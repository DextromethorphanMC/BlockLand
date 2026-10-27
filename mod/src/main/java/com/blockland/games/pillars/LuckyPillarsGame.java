package com.blockland.games.pillars;

import com.blockland.games.common.MatchText;
import com.blockland.games.common.TeamArenaGame;
import com.blockland.games.common.WeightedItems;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.item.ItemStackFactory;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * 幸运高柱 on top of the shared team arena flow (one-player teams): everybody starts caged on top of a pillar of their
 * own; from the first second of the fight on, every player still standing gets random items from the mode's table at a
 * fixed interval, to bridge over, climb down or fight with. The pillars are part of the map (the demo maps use bedrock,
 * which survival cannot break); players may build only inside the map and a small margin around it.
 */
final class LuckyPillarsGame extends TeamArenaGame {
    /** Blocks around the map (and above it) players may still build in; the arena wipes a wider margin afterwards. */
    private static final int BUILD_MARGIN = 8;

    private final LuckyPillarsSettings settings;
    private final WeightedItems items;

    LuckyPillarsGame(GameServices services, GameModeDef mode, LuckyPillarsSettings settings, MapDef map, MatchRoster players) {
        super(services, mode, settings.team(), map, players);
        this.settings = settings;
        this.items = new WeightedItems(settings.items(), new ItemStackFactory(services.server().registryAccess()));
    }

    @Override
    protected Component gameName() {
        return Component.translatable("blockland.game.lucky_pillars");
    }

    @Override
    protected Component startSubtitle() {
        return Component.translatable("blockland.pillars.start_sub", settings.itemIntervalSeconds());
    }

    @Override
    protected void onSecond() {
        // the first drop comes one second into the fight, then every interval
        if ((elapsedSeconds() - 1) % settings.itemIntervalSeconds() == 0) dropItems();
    }

    @Override
    protected Component actionBar(ServerPlayer player) {
        int next = settings.itemIntervalSeconds() - (elapsedSeconds() - 1) % settings.itemIntervalSeconds();
        return Component.empty()
                .append(super.actionBar(player))
                .append(MatchText.SEPARATOR)
                .append(Component.translatable("blockland.pillars.hud.next_item", next).withStyle(ChatFormatting.GOLD));
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return super.allowBlockPlace(player, pos) && arena() != null && arena().cleanupBox(BUILD_MARGIN).isInside(pos);
    }

    /** Every player still standing gets {@code itemsPerDrop} items; what does not fit in the inventory drops at their feet. */
    private void dropItems() {
        if (items.isEmpty()) return;
        forEachOnline(player -> {
            if (!isAlive(player.getUUID()) || player.level() != arena().level()) return;
            MutableComponent received = Component.empty();
            boolean any = false;
            for (int i = 0; i < settings.itemsPerDrop(); i++) {
                ItemStack stack = items.draw(random);
                if (stack.isEmpty()) continue;
                if (any) received.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                // name and count before the inventory takes the stack (it may shrink it)
                received.append(stack.getDisplayName());
                if (stack.getCount() > 1) received.append(Component.literal(" ×" + stack.getCount()).withStyle(ChatFormatting.WHITE));
                player.getInventory().placeItemBackInInventory(stack);
                any = true;
            }
            if (!any) return;
            player.sendSystemMessage(Component.translatable("blockland.pillars.item_received", received).withStyle(ChatFormatting.GRAY));
            PlayerUtil.sound(player, SoundEvents.ITEM_PICKUP, 0.8f, 1.3f);
        });
    }
}
