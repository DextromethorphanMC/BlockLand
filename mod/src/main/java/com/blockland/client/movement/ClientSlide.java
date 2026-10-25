package com.blockland.client.movement;

import com.blockland.BlockLand;
import com.blockland.client.render.RenderClock;
import com.blockland.common.Sounds;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.SlideRules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * The slide (滑铲, {@link MovementAction#SLIDE}) of the local player, one of the actions {@link ClientActions} runs:
 * sprinting on the ground, crouch (or the slide key) drops the player into a fast, low slide along the sprint direction
 * that turns a little with the view and dies down with friction; jumping out keeps the momentum, prone drops flat out of
 * it. Minecraft movement belongs to the client, so the slide is predicted at once — speed curve, low pose, low camera —
 * and the server only confirms or cancels it; the server also says whether the current game mode allows slides at all,
 * so only slides it will accept are predicted. Tuning: {@link SlideRules}.
 */
public final class ClientSlide {
    /** Slides when pressed during a sprint, like crouching does; unbound by default. */
    public static final KeyMapping KEY = new KeyMapping("key.blockland.slide", InputConstants.UNKNOWN.getValue(), "key.categories.blockland");

    @Nullable
    private static final Field EYE_HEIGHT = cameraField("eyeHeight");
    @Nullable
    private static final Field EYE_HEIGHT_OLD = cameraField("eyeHeightOld");

    @Nullable
    private static LocalPlayer slider;
    private static boolean sliding;
    private static boolean cancelled;
    private static int ticks, airTicks;
    private static float startSpeed;
    /** This tick's speed along the slide (friction per tick, more slippery on wet ground). */
    private static float speed;
    /** Direction sent to the server (hit boxes, others' view) and the steered direction actually moved along. */
    private static float startYaw, yaw;
    private static long startNanos, endNanos;
    private static float endAmount;
    private static int readyTick;
    private static boolean shiftBefore;
    /**
     * How far (blocks) and which way the local player moved in the last tick. The velocity field cannot tell: by the
     * time keys are read, vanilla has already taken last tick's ground friction off it.
     */
    private static double moved;
    private static float movedYaw;

    private ClientSlide() {
    }

    // ---- the local slide ------------------------------------------------------------------------------

    /** Every tick, after the movement keys were read and before the player moves. */
    static void input(LocalPlayer player, ClientInput keys, boolean prone) {
        if (sliding && player != slider) reset(); // respawned or changed dimension: that slide is gone
        boolean shift = keys.keyPresses.shift();
        boolean pressed = shift && !shiftBefore;
        shiftBefore = shift;
        boolean key = false;
        while (KEY.consumeClick()) key = true;
        if (sliding) {
            if (prone && ClientActions.allowed(MovementAction.PRONE)) {
                // dropping flat out of the slide: prone takes over (and the slide's momentum dies on the chest)
                end(player, false);
                return;
            }
            slide(player, keys.keyPresses.jump());
        } else if ((pressed || key) && canStart(player)) {
            start(player);
        }
    }

    private static boolean canStart(LocalPlayer player) {
        if (!ClientActions.allowed(MovementAction.SLIDE) || !ClientActions.idle() || ClientActions.tickCount() < readyTick
                || Minecraft.getInstance().screen != null) return false;
        if (!player.isSprinting() || !player.onGround() || player.isPassenger() || player.isSpectator() || player.isFallFlying()
                || player.isInWater() || player.getAbilities().flying) return false;
        return moved >= SlideRules.get().entrySpeed;
    }

    private static void start(LocalPlayer player) {
        SlideRules rules = SlideRules.get();
        startSpeed = rules.startSpeed(moved);
        speed = startSpeed;
        // along the sprint (a strafing sprint slides diagonally)
        startYaw = movedYaw;
        yaw = startYaw;
        slider = player;
        sliding = true;
        cancelled = false;
        ticks = 0;
        airTicks = 0;
        startNanos = RenderClock.nanos();
        endNanos = 0;
        player.setSprinting(false);
        player.setForcedPose(Pose.CROUCHING);
        ClientActions.wrapInput(player, ActionInput.Mode.SLIDE);
        move(player, startSpeed);
        player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.slide")),
                SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
        ClientActions.send(MovementAction.SLIDE, true, startYaw);
    }

    /** One tick of the slide: end it, or steer and carry on at the curve's speed. */
    private static void slide(LocalPlayer player, boolean jump) {
        SlideRules rules = SlideRules.get();
        ticks++;
        airTicks = player.onGround() ? 0 : airTicks + 1;
        // rain on the ground under the slide: a film of water, less friction
        speed = rules.next(speed, ticks, player.level().isRainingAt(player.blockPosition()));
        if (jump) {
            // jumping out keeps the slide's speed; the jump itself is vanilla's
            move(player, speed);
            end(player, false);
            return;
        }
        boolean blocked = player.horizontalCollision && !player.minorHorizontalCollision;
        if (cancelled || !ClientActions.allowed(MovementAction.SLIDE) || blocked || airTicks > rules.airTicks || ticks >= rules.maxTicks
                || speed < rules.endSpeed || player.isPassenger() || player.isSpectator() || player.isInWater()
                || player.getAbilities().flying) {
            end(player, cancelled);
            return;
        }
        // turn a little towards the view, never far from where the slide started
        float toView = Mth.wrapDegrees(player.getYRot() - yaw);
        yaw += Mth.clamp(toView, -rules.steering, rules.steering);
        float offset = Mth.clamp(Mth.wrapDegrees(yaw - startYaw), -rules.maxSteer, rules.maxSteer);
        yaw = Mth.wrapDegrees(startYaw + offset);
        move(player, speed);
    }

    /** Horizontal velocity along the slide for this tick; gravity and jumps keep their vertical part. */
    private static void move(LocalPlayer player, float speed) {
        double rad = Math.toRadians(yaw);
        player.setDeltaMovement(-Math.sin(rad) * speed, player.getDeltaMovement().y, Math.cos(rad) * speed);
    }

    /** @param byServer the server ended or refused it (it needs no stop message) */
    private static void end(LocalPlayer player, boolean byServer) {
        ClientActions.unwrapInput(player);
        if (player.getForcedPose() == Pose.CROUCHING) player.setForcedPose(null);
        endAmount = amount();
        sliding = false;
        endNanos = RenderClock.nanos();
        readyTick = ClientActions.tickCount() + SlideRules.get().cooldownTicks;
        if (!byServer) ClientActions.send(MovementAction.SLIDE, false, 0);
    }

    /** Forget a slide whose player is gone (respawn, new dimension, logout); the server ends its side itself. */
    static void reset() {
        if (slider != null && sliding) {
            ClientActions.unwrapInput(slider);
            if (slider.getForcedPose() == Pose.CROUCHING) slider.setForcedPose(null);
        }
        slider = null;
        sliding = false;
        endNanos = 0;
    }

    /** The server's word on the local player's action: anything but a slide while sliding ends it. */
    static void serverSays(@Nullable MovementAction action) {
        if (sliding && action != MovementAction.SLIDE) cancelled = true;
    }

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player != null) {
            double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
            moved = Math.sqrt(dx * dx + dz * dz);
            if (moved > 1e-4) movedYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        }
        lowerCamera(mc);
    }

    /** How far (blocks) and which way the local player moved in the last tick (the dive reads it too). */
    static double moved() {
        return moved;
    }

    static float movedYaw() {
        return movedYaw;
    }

    /**
     * The camera follows the slide down to {@link SlideRules#eyeHeight} with vanilla's own smoothing (half the way
     * each tick, interpolated between ticks); after the slide vanilla brings it back up the same way.
     */
    private static void lowerCamera(Minecraft mc) {
        if (!sliding || EYE_HEIGHT == null || EYE_HEIGHT_OLD == null || mc.player != slider) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getEntity() != slider) return;
        try {
            float before = EYE_HEIGHT_OLD.getFloat(camera);
            float target = SlideRules.get().eyeHeight * slider.getScale();
            EYE_HEIGHT.setFloat(camera, before + (target - before) * 0.5F);
        } catch (IllegalAccessException | RuntimeException e) {
            // the camera keeps vanilla's crouch height
        }
    }

    @Nullable
    private static Field cameraField(String name) {
        try {
            Field field = Camera.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Slides keep the crouch camera height: Camera.{} is not reachable ({})", name, e.toString());
            return null;
        }
    }

    // ---- what other modules ask --------------------------------------------------------------------------

    static boolean active() {
        return sliding;
    }

    /** The slide's direction (the one the server knows). */
    static float yaw() {
        return startYaw;
    }

    /** 0..1 how far into a slide the local player is (eases in and out): camera, weapon tilt. */
    public static float amount() {
        long now = RenderClock.nanos();
        if (sliding) return ClientActions.rise(now - startNanos);
        if (endNanos == 0) return 0;
        return endAmount * (1 - ClientActions.fall(now - endNanos));
    }

    /** First-person camera roll of the slide (degrees). */
    static float cameraRoll() {
        return SlideRules.get().camera.roll * amount();
    }

    static float fovKick() {
        return SlideRules.get().camera.fovKick * amount();
    }
}
