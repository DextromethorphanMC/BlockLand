package com.blockland.server.movement;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.movement.SlideRules;
import com.blockland.movement.Slides;
import com.blockland.network.BLNetwork;
import com.blockland.network.MovementPacketListeners;
import com.blockland.network.packet.MovementPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server authority for slides. Movement is the client's (Minecraft trusts its positions), so the slider predicts its
 * slide at once; this service decides whether the slide is legal — the game mode allows it, the player was really
 * sprinting on the ground fast enough, the cooldown is over — and only then gives the player the low slide pose
 * (collision and hit boxes) and shows the slide to everyone else. Illegal or overdue slides are ended and the slider is
 * told. It also tells every player in the world whether they may slide right now, so the client only predicts slides
 * the server will accept.
 */
public final class SlideService implements MovementPacketListeners.Server, Slides.View {
    /** Requests closer together than this are spam. */
    private static final int MIN_REQUEST_GAP = 4;
    /** Leeway for a sprint / ground contact / speed seen a moment before the request arrived (ticks). */
    private static final int SPRINT_GRACE = 5;
    private static final int GROUND_GRACE = 3;
    /** The request's direction may differ this much from where the player looks (a strafing sprint). */
    private static final float MAX_YAW_ERROR = 75;
    /** A slide the client never ended is ended this long after it should be over (lost packets, crashed clients). */
    private static final int OVERDUE = 20;
    private static final int RULES_INTERVAL = 10;

    private final MinecraftServer server;
    private final Predicate<ServerPlayer> allowed;
    private final Map<UUID, Mover> movers = new HashMap<>();

    private static final class Mover {
        boolean sliding;
        float yaw;
        int startTick;
        int lastEndTick = Integer.MIN_VALUE / 2;
        int lastRequestTick = Integer.MIN_VALUE / 2;
        int lastSprintTick = Integer.MIN_VALUE / 2;
        int lastGroundTick = Integer.MIN_VALUE / 2;
        /** Horizontal speed of the last ticks (blocks per tick), newest first. */
        final double[] speeds = new double[3];
        double lastX, lastZ;
        boolean positioned;
        /** What the player was last told about sliding: -1 nothing yet, 0 no, 1 yes. */
        int rulesSent = -1;
    }

    /** @param allowed whether a player may slide right now (their match's mode, a config option outside matches) */
    public SlideService(MinecraftServer server, Predicate<ServerPlayer> allowed) {
        this.server = server;
        this.allowed = allowed;
    }

    // ---- requests -------------------------------------------------------------------------------

    @Override
    public void onSlide(ServerPlayer player, MovementPackets.C2SSlide slide) {
        Mover mover = movers.computeIfAbsent(player.getUUID(), id -> new Mover());
        if (!slide.start()) {
            if (mover.sliding) end(player, mover, false);
            return;
        }
        int now = server.getTickCount();
        boolean spam = now - mover.lastRequestTick < MIN_REQUEST_GAP;
        mover.lastRequestTick = now;
        if (spam || mover.sliding || !legal(player, mover, now)) {
            // the client predicted a slide the server does not accept: it stops at once
            BLNetwork.sendTo(player, new MovementPackets.S2CSlide(player.getId(), false, 0));
            return;
        }
        float yaw = Float.isFinite(slide.yaw()) && Math.abs(Mth.wrapDegrees(slide.yaw() - player.getYRot())) <= MAX_YAW_ERROR
                ? Mth.wrapDegrees(slide.yaw()) : Mth.wrapDegrees(player.getYRot());
        mover.sliding = true;
        mover.yaw = yaw;
        mover.startTick = now;
        // the slide pose: low collision box (vanilla keeps it synced), and the hit boxes follow Slides
        player.setForcedPose(Pose.CROUCHING);
        BLNetwork.sendToTracking(player, new MovementPackets.S2CSlide(player.getId(), true, yaw));
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.slide")),
                SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F);
    }

    /** Everything the client claims and the server can check. */
    private boolean legal(ServerPlayer player, Mover mover, int now) {
        SlideRules rules = SlideRules.get();
        if (!player.isAlive() || player.isSpectator() || player.isPassenger() || player.isFallFlying() || player.isInWater()
                || player.getAbilities().flying || !allowed.test(player)) return false;
        if (now - mover.lastEndTick < rules.cooldownTicks - 2) return false;
        if (!player.isSprinting() && now - mover.lastSprintTick > SPRINT_GRACE) return false;
        if (!player.onGround() && now - mover.lastGroundTick > GROUND_GRACE) return false;
        double fastest = 0;
        for (double speed : mover.speeds) fastest = Math.max(fastest, speed);
        // positions arrive once per client tick; a little leeway for jitter
        return fastest >= rules.entrySpeed * 0.6;
    }

    // ---- ticking ------------------------------------------------------------------------------------

    public void tick() {
        int now = server.getTickCount();
        SlideRules rules = SlideRules.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Mover mover = movers.computeIfAbsent(player.getUUID(), id -> new Mover());
            observe(player, mover, now);
            boolean may = (now + player.getId()) % RULES_INTERVAL == 0 || mover.rulesSent < 0 ? allowed.test(player) : mover.rulesSent == 1;
            if (mover.rulesSent != (may ? 1 : 0)) {
                mover.rulesSent = may ? 1 : 0;
                BLNetwork.sendTo(player, new MovementPackets.S2CRules(may));
            }
            if (!mover.sliding) continue;
            boolean overdue = now - mover.startTick > rules.maxTicks + OVERDUE;
            // dead, spectating, mounted, respawned (a new entity without the pose) or no longer allowed
            boolean broken = !player.isAlive() || player.isSpectator() || player.isPassenger() || player.getForcedPose() != Pose.CROUCHING;
            if (overdue || broken || !may) end(player, mover, true);
        }
        movers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** What validation needs from the last ticks: sprint, ground contact and horizontal speed. */
    private static void observe(ServerPlayer player, Mover mover, int now) {
        if (player.isSprinting()) mover.lastSprintTick = now;
        if (player.onGround()) mover.lastGroundTick = now;
        double speed = mover.positioned ? Math.hypot(player.getX() - mover.lastX, player.getZ() - mover.lastZ) : 0;
        // a teleport is not a speed
        if (speed > 3) speed = 0;
        System.arraycopy(mover.speeds, 0, mover.speeds, 1, mover.speeds.length - 1);
        mover.speeds[0] = speed;
        mover.lastX = player.getX();
        mover.lastZ = player.getZ();
        mover.positioned = true;
    }

    private void end(ServerPlayer player, Mover mover, boolean tellSlider) {
        mover.sliding = false;
        mover.lastEndTick = server.getTickCount();
        if (player.getForcedPose() == Pose.CROUCHING) player.setForcedPose(null);
        MovementPackets.S2CSlide stop = new MovementPackets.S2CSlide(player.getId(), false, 0);
        BLNetwork.sendToTracking(player, stop);
        if (tellSlider) BLNetwork.sendTo(player, stop);
    }

    // ---- what combat asks -------------------------------------------------------------------------

    @Override
    public float slideYaw(Player player) {
        Mover mover = movers.get(player.getUUID());
        return mover != null && mover.sliding ? mover.yaw : Float.NaN;
    }

    /** The player left the world (lobby, disconnect). */
    public void forget(ServerPlayer player) {
        Mover mover = movers.remove(player.getUUID());
        if (mover != null && mover.sliding && player.getForcedPose() == Pose.CROUCHING) player.setForcedPose(null);
    }

    public void clear() {
        movers.clear();
    }
}
