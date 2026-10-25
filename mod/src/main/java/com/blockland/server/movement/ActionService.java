package com.blockland.server.movement;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.movement.action.ActionState;
import com.blockland.movement.action.BodyActions;
import com.blockland.movement.action.DiveRules;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.ProneRules;
import com.blockland.movement.action.SlideRules;
import com.blockland.network.BLNetwork;
import com.blockland.network.MovementPacketListeners;
import com.blockland.network.packet.MovementPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Server authority for the movement actions (spec §47: slide, prone, dive). Movement is the client's (Minecraft trusts
 * its positions), so the player predicts its action at once; this service decides whether it is legal — the game mode
 * allows it, the player really was sprinting / on the ground / in the air as the action needs, the cooldown is over —
 * and only then gives the player the action's pose (the slide's crouch box, the crawl box lying down: collision and,
 * through {@link BodyActions}, hit boxes) and shows it to everyone else. Illegal or overdue actions are ended and the
 * player is told. A dive that lands becomes prone here too. It also tells every player in the world which actions they
 * may use right now, so the client only predicts what the server will accept.
 */
public final class ActionService implements MovementPacketListeners.Server, BodyActions.View {
    /** Requests closer together than this are spam. */
    private static final int MIN_REQUEST_GAP = 4;
    /** Leeway for a sprint / ground contact / speed seen a moment before the request arrived (ticks). */
    private static final int SPRINT_GRACE = 5;
    private static final int GROUND_GRACE = 3;
    /** The request's direction may differ this much from where the player looks (a strafing sprint). */
    private static final float MAX_YAW_ERROR = 75;
    /** An action the client never ended is ended this long after it should be over (lost packets, crashed clients). */
    private static final int OVERDUE = 20;
    private static final int RULES_INTERVAL = 10;

    private final MinecraftServer server;
    private final BiPredicate<ServerPlayer, MovementAction> allowed;
    private final Map<UUID, Mover> movers = new HashMap<>();

    private static final class Mover {
        @Nullable
        MovementAction action;
        float yaw;
        int startTick;
        /** When each action last ended (cooldowns). */
        final int[] lastEndTick = {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2};
        /** When each action was last requested (spam). */
        final int[] lastRequestTick = {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2};
        /** The request tick the anti-cheat last let a refused prediction run for, per action. */
        final int[] graceTick = {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2};
        int lastSprintTick = Integer.MIN_VALUE / 2;
        int lastGroundTick = Integer.MIN_VALUE / 2;
        /** Horizontal speed of the last ticks (blocks per tick), newest first. */
        final double[] speeds = new double[3];
        double lastX, lastZ;
        boolean positioned;
        /** What the player was last told about the actions it may use: -1 nothing yet, else the allowed mask. */
        int rulesSent = -1;
    }

    /** @param allowed whether a player may use an action right now (their match's mode, a config option outside matches) */
    public ActionService(MinecraftServer server, BiPredicate<ServerPlayer, MovementAction> allowed) {
        this.server = server;
        this.allowed = allowed;
    }

    // ---- requests -------------------------------------------------------------------------------

    @Override
    public void onAction(ServerPlayer player, MovementPackets.C2SAction request) {
        MovementAction action = MovementAction.byIndex(request.action());
        if (action == null) return;
        Mover mover = movers.computeIfAbsent(player.getUUID(), id -> new Mover());
        if (!request.start()) {
            if (mover.action == action) end(player, mover, false);
            return;
        }
        int now = server.getTickCount();
        boolean spam = now - mover.lastRequestTick[action.ordinal()] < MIN_REQUEST_GAP;
        mover.lastRequestTick[action.ordinal()] = now;
        // going prone ends a slide (dropping flat out of it); nothing else interrupts an action
        boolean free = mover.action == null || action == MovementAction.PRONE && mover.action == MovementAction.SLIDE;
        if (spam || !free || !legal(player, mover, action, now)) {
            // the client predicted an action the server does not accept: it stops at once
            BLNetwork.sendTo(player, state(player, mover));
            return;
        }
        float yaw = Float.isFinite(request.yaw()) && Math.abs(Mth.wrapDegrees(request.yaw() - player.getYRot())) <= MAX_YAW_ERROR
                ? Mth.wrapDegrees(request.yaw()) : Mth.wrapDegrees(player.getYRot());
        if (mover.action != null) end(player, mover, false);
        start(player, mover, action, yaw, now);
    }

    private void start(ServerPlayer player, Mover mover, MovementAction action, float yaw, int now) {
        mover.action = action;
        mover.yaw = yaw;
        mover.startTick = now;
        // the action's pose: its collision box (vanilla keeps it synced; the crawl box lying down), and the hit boxes
        // follow BodyActions
        player.setForcedPose(pose(action));
        if (action != MovementAction.SLIDE) player.setSprinting(false);
        BLNetwork.sendToTracking(player, state(player, mover));
        String sound = switch (action) {
            case SLIDE -> "movement.slide";
            case PRONE -> "movement.prone";
            case DIVE -> "movement.dive";
        };
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id(sound)),
                SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F);
    }

    /** The collision pose of an action: crouching in a slide, vanilla's crawl (swimming out of water) lying down. */
    private static Pose pose(MovementAction action) {
        return action == MovementAction.SLIDE ? Pose.CROUCHING : Pose.SWIMMING;
    }

    /** Everything the client claims and the server can check. */
    private boolean legal(ServerPlayer player, Mover mover, MovementAction action, int now) {
        if (!player.isAlive() || player.isSpectator() || player.isPassenger() || player.isFallFlying() || player.isInWater()
                || player.getAbilities().flying || !allowed.test(player, action)) return false;
        double fastest = 0;
        for (double speed : mover.speeds) fastest = Math.max(fastest, speed);
        boolean sprinted = player.isSprinting() || now - mover.lastSprintTick <= SPRINT_GRACE;
        int since = now - mover.lastEndTick[action.ordinal()];
        return switch (action) {
            case SLIDE -> {
                SlideRules rules = SlideRules.get();
                if (since < rules.cooldownTicks - 2 || !sprinted) yield false;
                if (!player.onGround() && now - mover.lastGroundTick > GROUND_GRACE) yield false;
                // positions arrive once per client tick; a little leeway for jitter
                yield fastest >= rules.entrySpeed * 0.6;
            }
            case PRONE -> since >= ProneRules.get().cooldownTicks - 2
                    && (player.onGround() || now - mover.lastGroundTick <= GROUND_GRACE);
            case DIVE -> {
                DiveRules rules = DiveRules.get();
                // in the air shortly after a sprint left the ground, fast enough
                yield since >= rules.cooldownTicks - 2
                        && now - mover.lastGroundTick <= rules.windowTicks + GROUND_GRACE
                        && now - mover.lastSprintTick <= rules.windowTicks + SPRINT_GRACE
                        && fastest >= rules.minSpeed * 0.6;
            }
        };
    }

    // ---- ticking ------------------------------------------------------------------------------------

    public void tick() {
        int now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Mover mover = movers.computeIfAbsent(player.getUUID(), id -> new Mover());
            observe(player, mover, now);
            int mask = (now + player.getId()) % RULES_INTERVAL == 0 || mover.rulesSent < 0 ? allowedMask(player) : mover.rulesSent;
            if (mover.rulesSent != mask) {
                mover.rulesSent = mask;
                BLNetwork.sendTo(player, new MovementPackets.S2CRules(mask));
            }
            MovementAction action = mover.action;
            if (action == null) continue;
            boolean may = (mask >> action.ordinal() & 1) != 0;
            // dead, spectating, mounted, swimming, respawned (a new entity without the pose) or no longer allowed
            boolean broken = !player.isAlive() || player.isSpectator() || player.isPassenger() || player.isInWater()
                    || player.getForcedPose() != pose(action);
            int age = now - mover.startTick;
            boolean overdue = switch (action) {
                case SLIDE -> age > SlideRules.get().maxTicks + OVERDUE;
                case DIVE -> age > DiveRules.get().maxAirTicks + OVERDUE;
                case PRONE -> false;
            };
            if (overdue || broken || !may && action != MovementAction.DIVE) {
                end(player, mover, true);
            } else if (action == MovementAction.DIVE && age > 2 && player.onGround()) {
                // the dive landed: the body lies prone, sliding on with its momentum (the client's)
                mover.lastEndTick[MovementAction.DIVE.ordinal()] = now;
                mover.action = MovementAction.PRONE;
                mover.startTick = now;
                BLNetwork.sendToTracking(player, state(player, mover));
                player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
                        Sounds.byId(BlockLand.id("movement.dive_land")), SoundSource.PLAYERS, 1.0F,
                        0.94F + player.getRandom().nextFloat() * 0.12F);
            }
        }
        movers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    private int allowedMask(ServerPlayer player) {
        int mask = 0;
        for (MovementAction action : MovementAction.values()) if (allowed.test(player, action)) mask |= 1 << action.ordinal();
        return mask;
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

    private void end(ServerPlayer player, Mover mover, boolean tellPlayer) {
        MovementAction action = mover.action;
        if (action == null) return;
        mover.action = null;
        mover.lastEndTick[action.ordinal()] = server.getTickCount();
        // lying down under something low, vanilla keeps the player crawling on its own once the pose is released
        if (player.getForcedPose() == pose(action)) player.setForcedPose(null);
        if (action == MovementAction.PRONE) {
            player.level().playSound(player, player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.prone_up")),
                    SoundSource.PLAYERS, 0.8F, 0.94F + player.getRandom().nextFloat() * 0.12F);
        }
        MovementPackets.S2CAction stop = state(player, mover);
        BLNetwork.sendToTracking(player, stop);
        if (tellPlayer) BLNetwork.sendTo(player, stop);
    }

    private static MovementPackets.S2CAction state(ServerPlayer player, Mover mover) {
        return new MovementPackets.S2CAction(player.getId(), MovementAction.index(mover.action), mover.yaw);
    }

    // ---- what combat asks -------------------------------------------------------------------------

    @Nullable
    @Override
    public ActionState state(Player player) {
        Mover mover = movers.get(player.getUUID());
        return mover != null && mover.action != null ? new ActionState(mover.action, mover.yaw) : null;
    }

    // ---- what the anti-cheat asks (ActionAllowance) ---------------------------------------------------

    /** Ticks since the player's action of this kind ended; huge when it never ran. */
    public int ticksSinceEnd(Player player, MovementAction action) {
        Mover mover = movers.get(player.getUUID());
        return mover == null ? Integer.MAX_VALUE / 2 : server.getTickCount() - mover.lastEndTick[action.ordinal()];
    }

    /**
     * Whether the client may still be predicting a request of this kind that was refused within the last {@code window}
     * ticks (it slides on until it hears back). Granted once per request and at most once per the action's cooldown, so
     * refused requests cannot be spammed into a lasting allowance.
     */
    public boolean predictingRefused(Player player, MovementAction action, int window) {
        Mover mover = movers.get(player.getUUID());
        if (mover == null) return false;
        int i = action.ordinal();
        int requested = mover.lastRequestTick[i];
        if (server.getTickCount() - requested > window) return false;
        if (mover.graceTick[i] == requested) return true;
        if (requested - mover.graceTick[i] < cooldown(action) + window) return false;
        mover.graceTick[i] = requested;
        return true;
    }

    private static int cooldown(MovementAction action) {
        return switch (action) {
            case SLIDE -> SlideRules.get().cooldownTicks;
            case PRONE -> ProneRules.get().cooldownTicks;
            case DIVE -> DiveRules.get().cooldownTicks;
        };
    }

    /** The player left the world (lobby, disconnect). */
    public void forget(ServerPlayer player) {
        Mover mover = movers.remove(player.getUUID());
        if (mover != null && mover.action != null && player.getForcedPose() == pose(mover.action)) player.setForcedPose(null);
    }

    public void clear() {
        movers.clear();
    }
}
