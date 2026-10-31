package com.blockland.games.uhc;

import com.blockland.BlockLand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * UHC's classic rule for one player at a time: a full stomach does not heal (golden apples, potions and the like still
 * do). The {@code naturalRegeneration} game rule would turn it off for every world on the server, so instead the food
 * timer that counts towards the next heal is held at zero while the food bar is high enough to heal; starving, which
 * counts on the same timer with an empty bar, is left alone.
 */
final class NoNaturalRegen {
    /** Food level from which vanilla heals ({@link FoodData#tick}). */
    private static final int HEALING_FOOD = 18;
    @Nullable
    private static Field timer;
    private static boolean failed;

    private NoNaturalRegen() {
    }

    /** Every tick for each player it applies to. */
    static void hold(ServerPlayer player) {
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() < HEALING_FOOD || failed) return;
        try {
            if (timer == null) {
                timer = FoodData.class.getDeclaredField("tickTimer");
                timer.setAccessible(true);
            }
            timer.setInt(food, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            BlockLand.LOGGER.warn("[BlockLand] UHC cannot turn off natural regeneration (FoodData.tickTimer not found)", e);
        }
    }
}
