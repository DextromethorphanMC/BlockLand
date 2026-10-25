package com.blockland.client.movement;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.ProneRules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;

/**
 * Prone (匍匐, {@link MovementAction#PRONE}) for the local player, one of the actions {@link ClientActions} runs. The
 * prone key lays the player flat on the ground — vanilla's crawl box (0.6 high: under fences, through one-block gaps),
 * the crawl's eye height, the prone hit boxes — where it crawls slowly; guns fire and aim, sprinting and jumping are
 * off. The prone key again, jump or crouch get it up (to standing, or to a crouch while crouch is held) — not under
 * anything too low to stand in. A dive lands here with its momentum: the body slides on on its chest first. The server
 * confirms or ends it like a slide. Tuning: {@link ProneRules}.
 */
public final class ClientProne {
    /** Goes prone / gets up; also the dive's key in the air after a sprint jump (C: COD's crouch, free in vanilla). */
    public static final KeyMapping KEY = new KeyMapping("key.blockland.prone", InputConstants.KEY_C, "key.categories.blockland");

    @Nullable
    private static LocalPlayer lier;
    private static boolean prone;
    private static boolean cancelled;
    private static long startNanos, endNanos;
    private static float endAmount;
    private static int readyTick;
    private static boolean jumpBefore, shiftBefore;
    /** A dive's landing: the slide on the chest (speed per tick, along {@link #landYaw}, ticks left). */
    private static float landSpeed, landYaw;
    private static int landTicks;
    /** 0..1 how fast the player crawls (of the crawl's full speed): the weapon's crawl carry and its bob. */
    private static float crawl;

    private ClientProne() {
    }

    static void input(LocalPlayer player, ClientInput keys, boolean key) {
        if (prone && player != lier) reset();
        boolean jump = keys.keyPresses.jump(), shift = keys.keyPresses.shift();
        boolean jumpPressed = jump && !jumpBefore, shiftPressed = shift && !shiftBefore;
        jumpBefore = jump;
        shiftBefore = shift;
        if (prone) {
            lying(player, key || jumpPressed || shiftPressed, shift);
        } else if (key && canStart(player)) {
            // out of a slide the slide has just ended (its key handler runs first and hands over)
            start(player, Mth.wrapDegrees(player.getYRot()), true);
        }
    }

    private static boolean canStart(LocalPlayer player) {
        if (!ClientActions.allowed(MovementAction.PRONE) || !ClientActions.idle() || ClientActions.tickCount() < readyTick
                || Minecraft.getInstance().screen != null) return false;
        return player.onGround() && !player.isPassenger() && !player.isSpectator() && !player.isFallFlying() && !player.isInWater()
                && !player.getAbilities().flying;
    }

    /**
     * Lies down.
     *
     * @param request tell the server (a dive's landing needs no request: the server turns its dive prone itself)
     */
    static void start(LocalPlayer player, float yaw, boolean request) {
        lier = player;
        prone = true;
        cancelled = false;
        startNanos = System.nanoTime();
        endNanos = 0;
        player.setSprinting(false);
        player.setForcedPose(Pose.SWIMMING);
        ClientActions.wrapInput(player, ActionInput.Mode.CRAWL);
        if (request) {
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.prone")),
                    SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
            ClientActions.send(MovementAction.PRONE, true, yaw);
        }
    }

    /** A dive landed: prone, sliding on along {@code yaw} at {@code speed} blocks per tick, slowing on the chest. */
    static void land(LocalPlayer player, float yaw, float speed, int ticks) {
        start(player, yaw, false);
        landYaw = yaw;
        landSpeed = speed;
        landTicks = ticks;
    }

    /** One tick lying down: get up, or crawl (after a landing's slide). */
    private static void lying(LocalPlayer player, boolean getUp, boolean crouch) {
        ProneRules rules = ProneRules.get();
        player.setSprinting(false);
        if (cancelled || !ClientActions.allowed(MovementAction.PRONE) || player.isPassenger() || player.isSpectator()
                || player.isInWater() || player.getAbilities().flying || !player.isAlive()) {
            end(player, cancelled);
            return;
        }
        if (getUp && room(player, crouch ? Pose.CROUCHING : Pose.STANDING)) {
            end(player, false);
            return;
        }
        ActionInput input = player.input instanceof ActionInput wrapped ? wrapped : null;
        if (landTicks > 0) {
            // the dive's momentum carries the body on along the ground; the keys only crawl once it has died down
            landTicks--;
            double rad = Math.toRadians(landYaw);
            player.setDeltaMovement(-Math.sin(rad) * landSpeed, player.getDeltaMovement().y, Math.cos(rad) * landSpeed);
            landSpeed *= com.blockland.movement.action.DiveRules.get().landFriction;
            if (landSpeed < com.blockland.movement.action.DiveRules.get().landEndSpeed || player.horizontalCollision) landTicks = 0;
            if (input != null) input.setCrawl(0);
        } else if (input != null) {
            input.setCrawl(rules.crawlSpeed);
        }
    }

    /** Whether the player fits standing (or crouching) where it lies. */
    private static boolean room(LocalPlayer player, Pose pose) {
        return player.level().noCollision(player, player.getDimensions(pose).makeBoundingBox(player.position()).deflate(1.0E-7));
    }

    /** @param byServer the server ended or refused it (it needs no stop message) */
    private static void end(LocalPlayer player, boolean byServer) {
        ClientActions.unwrapInput(player);
        if (player.getForcedPose() == Pose.SWIMMING) player.setForcedPose(null);
        endAmount = amount();
        prone = false;
        landTicks = 0;
        endNanos = System.nanoTime();
        readyTick = ClientActions.tickCount() + ProneRules.get().cooldownTicks;
        if (!byServer) {
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.prone_up")),
                    SoundSource.PLAYERS, 0.8F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
            ClientActions.send(MovementAction.PRONE, false, 0);
        }
    }

    static void reset() {
        if (lier != null && prone) {
            ClientActions.unwrapInput(lier);
            if (lier.getForcedPose() == Pose.SWIMMING) lier.setForcedPose(null);
        }
        lier = null;
        prone = false;
        landTicks = 0;
        endNanos = 0;
    }

    /** The server's word on the local player's action: lying down, anything but prone (or the dive landing) ends it. */
    static void serverSays(@Nullable MovementAction action) {
        if (prone && action != MovementAction.PRONE && action != MovementAction.DIVE) cancelled = true;
    }

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        float target = 0;
        if (prone && player != null) {
            double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
            // the crawl's full speed: walking × sneaking × the crawl share
            target = Mth.clamp((float) Math.sqrt(dx * dx + dz * dz) / (0.215f * 0.3f * ProneRules.get().crawlSpeed), 0, 1);
        }
        crawl += (target - crawl) * 0.4f;
    }

    // ---- what other modules ask --------------------------------------------------------------------------

    static boolean active() {
        return prone;
    }

    /** 0..1 how far down the local player is (eases in over the drop, out over getting up). */
    public static float amount() {
        long now = System.nanoTime();
        ProneRules rules = ProneRules.get();
        if (prone) return ease((now - startNanos) / (rules.enterTicks * 50_000_000f));
        if (endNanos == 0) return 0;
        return endAmount * (1 - ease((now - endNanos) / (rules.exitTicks * 50_000_000f)));
    }

    /** 0..1 how fast the local player crawls (0 lying still or not prone). */
    public static float crawl() {
        return prone ? Mth.clamp(crawl, 0, 1) : 0;
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
