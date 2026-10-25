package com.blockland.movement.action;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Who is in which special movement ({@link MovementAction}: slide, prone, dive), as each logical side knows it: the
 * server's authoritative actions, and on the client what the server told it plus the local player's own prediction.
 * Combat code (shot origins, hit boxes), the camera and the poses ask here without knowing the movement module; each
 * side registers its view at start-up. Both views can live in one JVM (integrated server), so lookups pick the view by
 * the entity's level. {@link #eyePosition} is the one place weapons take their eyes from (spec §47).
 */
public final class BodyActions {
    /** One side's knowledge of the actions. */
    public interface View {
        View NONE = player -> null;

        /** The player's current action, null when upright and plain. */
        @Nullable
        ActionState state(Player player);
    }

    private static volatile View server = View.NONE;
    private static volatile View client = View.NONE;

    private BodyActions() {
    }

    public static void setServerView(@Nullable View view) {
        server = view == null ? View.NONE : view;
    }

    public static void setClientView(@Nullable View view) {
        client = view == null ? View.NONE : view;
    }

    /** The entity's current action (players only), null when it is upright and plain. */
    @Nullable
    public static ActionState state(Entity entity) {
        if (!(entity instanceof Player player)) return null;
        return (player.level().isClientSide() ? client : server).state(player);
    }

    @Nullable
    public static MovementAction action(Entity entity) {
        ActionState state = state(entity);
        return state == null ? null : state.action();
    }

    /** In any special movement: its own hit boxes, its own eye height. */
    public static boolean isActing(Entity entity) {
        return state(entity) != null;
    }

    public static boolean isSliding(Entity entity) {
        return action(entity) == MovementAction.SLIDE;
    }

    /** Lying flat: prone, or flying flat in a dive. */
    public static boolean isLying(Entity entity) {
        MovementAction action = action(entity);
        return action != null && action.lying();
    }

    /** The eye height (blocks at scale 1) of an action: where its camera is and its shots and cuts start. */
    public static float eyeHeight(MovementAction action) {
        return switch (action) {
            case SLIDE -> SlideRules.get().eyeHeight;
            case PRONE -> ProneRules.get().eyeHeight;
            case DIVE -> DiveRules.get().eyeHeight;
        };
    }

    /** The hit boxes of an action's body. */
    public static BodyBoxes.Body hitBody(MovementAction action) {
        return switch (action) {
            case SLIDE -> SlideRules.get().hitBody;
            case PRONE -> ProneRules.get().hitBody;
            case DIVE -> DiveRules.get().hitBody;
        };
    }

    public static BodyBoxes.Head hitHead(MovementAction action) {
        return switch (action) {
            case SLIDE -> SlideRules.get().hitHead;
            case PRONE -> ProneRules.get().hitHead;
            case DIVE -> DiveRules.get().hitHead;
        };
    }

    /**
     * Where the entity's eyes are for combat (shots, cuts, throws): in an action they are its eye height above the
     * position (low in a slide, near the ground lying down); everyone else's are vanilla's eye position.
     */
    public static Vec3 eyePosition(Entity entity) {
        MovementAction action = action(entity);
        if (action == null) return entity.getEyePosition();
        return entity.position().add(0, eyeHeight(action) * scaleOf(entity), 0);
    }

    /** {@link #eyePosition} between ticks (third-person effects drawn at the partial tick). */
    public static Vec3 eyePosition(Entity entity, float partialTick) {
        MovementAction action = action(entity);
        if (action == null) return entity.getEyePosition(partialTick);
        return entity.getPosition(partialTick).add(0, eyeHeight(action) * scaleOf(entity), 0);
    }

    /** The entity's size scale (the scale attribute), 1 for non-living entities. */
    public static float scaleOf(Entity entity) {
        return entity instanceof LivingEntity living ? living.getScale() : 1;
    }
}
