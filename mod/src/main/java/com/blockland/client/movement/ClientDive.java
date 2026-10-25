package com.blockland.client.movement;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.movement.action.DiveRules;
import com.blockland.movement.action.MovementAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;

/**
 * The dive (飞扑, {@link MovementAction#DIVE}) of the local player, one of the actions {@link ClientActions} runs:
 * sprinting, jump, and the prone key in the air shortly after leaving the ground throws the body flat forwards — the
 * crawl box, a burst of speed along the run and a little lift, air drag instead of vanilla's (which would stop it dead)
 * — the camera looking a little down and rolling into it. On landing it is prone, sliding on along the ground with the
 * dive's momentum ({@link ClientProne#land}). The server checks it (a sprint, in the air, fast enough) and turns it
 * prone when it lands. Tuning: {@link DiveRules}.
 */
public final class ClientDive {
    private static final long BLEND_IN = 160_000_000L;
    private static final long BLEND_OUT = 260_000_000L;

    @Nullable
    private static LocalPlayer diver;
    private static boolean diving;
    private static boolean cancelled;
    private static int airTicks;
    private static float speed, yaw;
    private static long startNanos, endNanos;
    private static float endAmount;
    private static int readyTick;
    /** Ticks since the local player last stood on the ground, and whether it was sprinting when it left it. */
    private static int sinceGround;
    private static boolean sprintJump;
    /** When the dive last touched down (the first-person camera's dip), 0 = never. */
    private static long landNanos;

    private ClientDive() {
    }

    static void input(LocalPlayer player, ClientInput keys, boolean key) {
        if (diving && player != diver) reset();
        if (diving) {
            fly(player);
        } else if (key && canStart(player)) {
            start(player);
        }
    }

    private static boolean canStart(LocalPlayer player) {
        DiveRules rules = DiveRules.get();
        if (!ClientActions.allowed(MovementAction.DIVE) || !ClientActions.idle() || ClientActions.tickCount() < readyTick
                || Minecraft.getInstance().screen != null) return false;
        if (player.onGround() || !sprintJump || sinceGround > rules.windowTicks || player.isPassenger() || player.isSpectator()
                || player.isFallFlying() || player.isInWater() || player.getAbilities().flying) return false;
        return ClientSlide.moved() >= rules.minSpeed;
    }

    private static void start(LocalPlayer player) {
        DiveRules rules = DiveRules.get();
        diver = player;
        diving = true;
        cancelled = false;
        airTicks = 0;
        yaw = ClientSlide.movedYaw();
        speed = rules.launchSpeed(ClientSlide.moved());
        startNanos = System.nanoTime();
        endNanos = 0;
        player.setSprinting(false);
        player.setForcedPose(Pose.SWIMMING);
        ClientActions.wrapInput(player, ActionInput.Mode.FLY);
        double rad = Math.toRadians(yaw);
        player.setDeltaMovement(-Math.sin(rad) * speed, Math.max(0, player.getDeltaMovement().y) + rules.lift, Math.cos(rad) * speed);
        player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.dive")),
                SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
        ClientActions.send(MovementAction.DIVE, true, yaw);
    }

    /** One tick in the air: land prone, give up (water, a long drop, the server), or fly on. */
    private static void fly(LocalPlayer player) {
        DiveRules rules = DiveRules.get();
        airTicks++;
        if (cancelled || player.isPassenger() || player.isSpectator() || player.isInWater() || player.getAbilities().flying
                || !player.isAlive() || airTicks > rules.maxAirTicks) {
            end(player, cancelled);
            return;
        }
        if (player.onGround() && airTicks > 1) {
            // touchdown: on the chest, sliding on; prone takes the pose and the keys over
            diving = false;
            endAmount = amount();
            endNanos = System.nanoTime();
            readyTick = ClientActions.tickCount() + rules.cooldownTicks;
            landNanos = System.nanoTime();
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.dive_land")),
                    SoundSource.PLAYERS, 1.0F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
            ClientProne.land(player, yaw, speed * 0.8f, rules.landSlideTicks);
            return;
        }
        if (player.horizontalCollision && !player.minorHorizontalCollision) speed *= 0.3f;
        speed *= rules.airDrag;
        double rad = Math.toRadians(yaw);
        player.setDeltaMovement(-Math.sin(rad) * speed, player.getDeltaMovement().y, Math.cos(rad) * speed);
    }

    /** @param byServer the server ended or refused it (it needs no stop message) */
    private static void end(LocalPlayer player, boolean byServer) {
        ClientActions.unwrapInput(player);
        if (player.getForcedPose() == Pose.SWIMMING) player.setForcedPose(null);
        endAmount = amount();
        diving = false;
        endNanos = System.nanoTime();
        readyTick = ClientActions.tickCount() + DiveRules.get().cooldownTicks;
        if (!byServer) ClientActions.send(MovementAction.DIVE, false, 0);
    }

    static void reset() {
        if (diver != null && diving) {
            ClientActions.unwrapInput(diver);
            if (diver.getForcedPose() == Pose.SWIMMING) diver.setForcedPose(null);
        }
        diver = null;
        diving = false;
        endNanos = 0;
    }

    /** The server's word: in a dive, anything but the dive (or prone: it landed) ends it. */
    static void serverSays(@Nullable MovementAction action) {
        if (diving && action != MovementAction.DIVE && action != MovementAction.PRONE) cancelled = true;
    }

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player != null) {
            if (player.onGround()) {
                sinceGround = 0;
                sprintJump = player.isSprinting();
            } else {
                sinceGround++;
                // the sprint survives the jump in vanilla; a fall off a ledge mid-run counts too
                sprintJump |= player.isSprinting();
            }
        }
    }

    // ---- what other modules ask --------------------------------------------------------------------------

    static boolean active() {
        return diving;
    }

    static float yaw() {
        return yaw;
    }

    /** 0..1 how far into the dive the local player is: the camera's tilt, the weapon thrown forward. */
    public static float amount() {
        long now = System.nanoTime();
        if (diving) return smooth((now - startNanos) / (float) BLEND_IN);
        if (endNanos == 0) return 0;
        return endAmount * (1 - smooth((now - endNanos) / (float) BLEND_OUT));
    }

    /** First-person camera: looking down into the dive, and the landing's dip. */
    static float cameraPitch() {
        DiveRules.Camera c = DiveRules.get().camera;
        float t = landNanos == 0 ? 1 : (System.nanoTime() - landNanos) / 450_000_000f;
        // the landing's dip: in quickly as the chest hits the ground, out slowly
        float dip = t >= 1 ? 0 : (float) Math.sin(Math.PI * Math.pow(t, 0.45)) * (1 - t);
        return c.pitch * amount() + c.landKick * dip;
    }

    static float cameraRoll() {
        return DiveRules.get().camera.roll * amount();
    }

    static float fovKick() {
        return DiveRules.get().camera.fovKick * amount();
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
