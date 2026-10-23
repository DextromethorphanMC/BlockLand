package com.blockland.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Who is sliding, as each logical side knows it: the server's authoritative slides, and on the client what the server
 * told it plus the local player's own prediction. Combat code (shot origins, hit boxes) asks here without knowing the
 * movement module; each side registers its view at start-up. Both views can live in one JVM (integrated server),
 * so lookups pick the view by the entity's level.
 */
public final class Slides {
    /** One side's knowledge of slides. */
    public interface View {
        View NONE = player -> Float.NaN;

        /** The slide direction (degrees, Minecraft yaw) while {@code player} slides, NaN otherwise. */
        float slideYaw(Player player);
    }

    private static volatile View server = View.NONE;
    private static volatile View client = View.NONE;

    private Slides() {
    }

    public static void setServerView(@Nullable View view) {
        server = view == null ? View.NONE : view;
    }

    public static void setClientView(@Nullable View view) {
        client = view == null ? View.NONE : view;
    }

    /** The slide direction while {@code entity} is a sliding player, NaN otherwise. */
    public static float slideYaw(Entity entity) {
        if (!(entity instanceof Player player)) return Float.NaN;
        return (player.level().isClientSide() ? client : server).slideYaw(player);
    }

    public static boolean isSliding(Entity entity) {
        return !Float.isNaN(slideYaw(entity));
    }

    /**
     * Where the entity's eyes are for combat: a sliding player's are low (the camera's {@link SlideRules#eyeHeight}),
     * everyone else's are vanilla's eye position.
     */
    public static Vec3 eyePosition(Entity entity) {
        if (!isSliding(entity)) return entity.getEyePosition();
        return entity.position().add(0, SlideRules.get().eyeHeight * scaleOf(entity), 0);
    }

    /** The entity's size scale (the scale attribute), 1 for non-living entities. */
    public static float scaleOf(Entity entity) {
        return entity instanceof LivingEntity living ? living.getScale() : 1;
    }
}
