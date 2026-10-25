package com.blockland.server.movement;

import com.blockland.movement.action.ActionState;
import com.blockland.movement.action.DiveRules;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.SlideRules;
import com.blockland.server.anticheat.api.MotionAllowance;
import com.blockland.server.anticheat.api.MotionBudget;
import com.blockland.server.anticheat.predict.Physics;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the movement actions (spec §47) let a player do beyond vanilla physics, for the anti-cheat (anti-cheat spec §6):
 * a slide's burst (its start speed, held, then decaying), a dive's launch and lift and its slide on the chest after
 * landing. Allowed while the action runs, for the player's latency window after it ended (their client stops a moment
 * later), and once per cooldown for a request the server refused (the client predicted it until it heard back).
 */
public final class ActionAllowance implements MotionAllowance {
    /** Slack over the data's top speeds (float steps of the client's prediction). */
    private static final double SLACK = 1.05;

    private final ActionService actions;

    public ActionAllowance(ActionService actions) {
        this.actions = actions;
    }

    @Override
    public void allow(ServerPlayer player, MotionBudget budget, int windowTicks) {
        ActionState state = actions.state(player);
        MovementAction current = state == null ? null : state.action();
        if (running(player, current, MovementAction.SLIDE, windowTicks)) {
            budget.allowHorizontal(SlideRules.get().maxStartSpeed * SLACK + 0.02, "滑铲");
        }
        DiveRules dive = DiveRules.get();
        if (running(player, current, MovementAction.DIVE, windowTicks)) {
            budget.allowHorizontal(dive.maxLaunchSpeed * SLACK + 0.02, "飞扑");
            // the dive sets the rise to the lift on top of whatever was still going up (at most a jump's)
            budget.allowUp(Physics.DEFAULT_JUMP + dive.lift + 0.02, "飞扑");
        } else if (current == MovementAction.PRONE && actions.ticksSinceEnd(player, MovementAction.DIVE) <= dive.landSlideTicks + windowTicks) {
            // a dive lands prone and slides on with its speed
            budget.allowHorizontal(dive.maxLaunchSpeed * SLACK + 0.02, "飞扑落地");
        }
    }

    private boolean running(ServerPlayer player, MovementAction current, MovementAction action, int windowTicks) {
        if (current == action || actions.ticksSinceEnd(player, action) <= windowTicks) return true;
        return actions.predictingRefused(player, action, windowTicks);
    }
}
