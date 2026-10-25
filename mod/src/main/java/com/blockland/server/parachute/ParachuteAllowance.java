package com.blockland.server.parachute;

import com.blockland.parachute.ParachuteRules;
import com.blockland.server.anticheat.api.MotionAllowance;
import com.blockland.server.anticheat.api.MotionBudget;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player wearing a parachute in the air is judged by {@link ParachuteFlightCheck} (canopy descent, steering, free
 * fall; it reports its failures to the anti-cheat), so the anti-cheat's own physics leaves them alone until they land
 * (anti-cheat spec §6).
 */
public final class ParachuteAllowance implements MotionAllowance {
    @Override
    public void allow(ServerPlayer player, MotionBudget budget, int windowTicks) {
        if (!player.onGround() && ParachuteRules.wears(player)) budget.exempt(true, true, "降落伞");
    }
}
