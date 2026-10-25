package com.blockland.client.movement;

import com.blockland.client.viewmodel.ViewmodelMotion;
import com.blockland.movement.action.ActionState;
import com.blockland.movement.action.BodyActions;
import com.blockland.movement.action.MovementAction;
import com.blockland.movement.action.ProneRules;
import com.blockland.network.BLNetwork;
import com.blockland.network.MovementPacketListeners;
import com.blockland.network.packet.MovementPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The movement actions on this client (spec §47): one place that owns the local player's action — the slide
 * ({@link ClientSlide}), prone ({@link ClientProne}) and the dive ({@link ClientDive}), at most one at a time — the
 * server's word on them (which actions the mode allows, refusals, a dive landing prone) and the actions of the other
 * players (their third-person poses, {@link ActionPoses}, and this client's hit prediction through {@link BodyActions}).
 * Each action predicts itself at once; this class routes the keys, the network and the camera to them and tells the
 * shared viewmodel motion ({@link ViewmodelMotion.BodyMotion}) what the body is doing, so every weapon follows.
 */
public final class ClientActions {
    private static final long BLEND_IN = 120_000_000L;
    private static final long BLEND_OUT = 220_000_000L;

    /** What the body does, for the first-person weapon (every gun and knife through the shared motion). */
    public static final ViewmodelMotion.BodyMotion BODY = new ViewmodelMotion.BodyMotion() {
        @Override
        public float slide() {
            return ClientSlide.amount();
        }

        @Override
        public float prone() {
            return ClientProne.amount();
        }

        @Override
        public float dive() {
            return ClientDive.amount();
        }

        @Override
        public float crawl() {
            return ClientProne.crawl();
        }

        @Override
        public float proneSway() {
            return ProneRules.get().aimSway;
        }
    };

    /** Which actions the server lets this player use right now (bit = {@link MovementAction} index). */
    private static int allowed;
    private static int tickCount;
    @Nullable
    private static Level level;

    /** Other players' actions by entity id: their pose while it plays and fades, and hit prediction. */
    private static final Map<Integer, Remote> remote = new HashMap<>();

    private static final class Remote {
        final MovementAction action;
        final float yaw;
        final long start;
        long end;
        float endAmount;

        Remote(MovementAction action, float yaw, long start) {
            this.action = action;
            this.yaw = yaw;
            this.start = start;
        }

        float amount(long now) {
            return end == 0 ? rise(now - start) : endAmount * (1 - fall(now - end));
        }
    }

    private ClientActions() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> {
            event.register(ClientSlide.KEY);
            event.register(ClientProne.KEY);
        });
        BLNetwork.setMovementClientListener(new MovementPacketListeners.Client() {
            @Override
            public void onAction(MovementPackets.S2CAction action) {
                received(action);
            }

            @Override
            public void onRules(MovementPackets.S2CRules rules) {
                allowed = rules.allowed();
            }
        });
        BodyActions.setClientView(ClientActions::state);
        MovementInputUpdateEvent.BUS.addListener(ClientActions::input);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
            event.setRoll(event.getRoll() + ClientSlide.cameraRoll() + ClientDive.cameraRoll());
            event.setPitch(event.getPitch() + ClientDive.cameraPitch());
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            float kick = ClientSlide.fovKick() + ClientDive.fovKick();
            if (kick > 0) event.setFOV(event.getFOV() * (1 + kick));
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            allowed = 0;
            ClientSlide.reset();
            ClientDive.reset();
            ClientProne.reset();
            remote.clear();
        });
    }

    // ---- the local player ------------------------------------------------------------------------

    /** Every tick, after the movement keys were read and before the player moves: the actions in turn. */
    private static void input(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        ClientInput keys = event.getInput() instanceof ActionInput wrapped ? wrapped.inner : event.getInput();
        boolean prone = false;
        while (ClientProne.KEY.consumeClick()) prone = true;
        // a dive turns into prone on landing, prone can be entered out of a slide: the order matters
        ClientDive.input(player, keys, prone);
        ClientSlide.input(player, keys, prone);
        ClientProne.input(player, keys, prone);
    }

    private static void tick() {
        tickCount++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            remote.clear();
        }
        long now = System.nanoTime();
        remote.values().removeIf(r -> r.end != 0 && now - r.end > BLEND_OUT);
        ClientSlide.tick(mc);
        ClientProne.tick(mc);
        ClientDive.tick(mc);
    }

    /** Whether the server lets this player use {@code action} right now. */
    static boolean allowed(MovementAction action) {
        return (allowed >> action.ordinal() & 1) != 0;
    }

    static int tickCount() {
        return tickCount;
    }

    /** Nothing else is going on: a new action may start (prone may also start out of a slide). */
    static boolean idle() {
        return !ClientSlide.active() && !ClientProne.active() && !ClientDive.active();
    }

    /** Tells the server the local player started or ended an action. */
    static void send(MovementAction action, boolean start, float yaw) {
        BLNetwork.sendToServer(new MovementPackets.C2SAction(action.ordinal(), start, yaw));
    }

    /** The movement keys go through the action's wrapper (installed once, its mode switched between actions). */
    static void wrapInput(LocalPlayer player, ActionInput.Mode mode) {
        if (player.input instanceof ActionInput wrapped) wrapped.setMode(mode);
        else player.input = new ActionInput(player.input, mode);
    }

    static void unwrapInput(@Nullable LocalPlayer player) {
        if (player != null && player.input instanceof ActionInput wrapped) player.input = wrapped.inner;
    }

    // ---- the server's word --------------------------------------------------------------------------

    private static void received(MovementPackets.S2CAction message) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        MovementAction action = MovementAction.byIndex(message.action());
        if (mc.player != null && message.entityId() == mc.player.getId()) {
            // the server refused, ended or changed our action
            ClientSlide.serverSays(action);
            ClientDive.serverSays(action);
            ClientProne.serverSays(action);
            return;
        }
        Remote before = remote.get(message.entityId());
        if (action != null) {
            // a dive landing prone carries straight on (no fade between them)
            boolean carried = before != null && before.end == 0 && before.action.lying() && action.lying();
            remote.put(message.entityId(), new Remote(action, message.yaw(), carried ? before.start : now));
        } else if (before != null && before.end == 0) {
            before.endAmount = before.amount(now);
            before.end = now;
        }
    }

    // ---- what other modules ask --------------------------------------------------------------------------

    /** This client's view for {@link BodyActions}: the local prediction, and the actions the server reported. */
    @Nullable
    private static ActionState state(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player) {
            if (ClientSlide.active()) return new ActionState(MovementAction.SLIDE, ClientSlide.yaw());
            if (ClientDive.active()) return new ActionState(MovementAction.DIVE, ClientDive.yaw());
            if (ClientProne.active()) return new ActionState(MovementAction.PRONE, player.yBodyRot);
            return null;
        }
        Remote r = remote.get(player.getId());
        if (r == null || r.end != 0) return null;
        // lying prone the body turns with the player (vanilla's body rotation); a slide and a dive keep their direction
        return new ActionState(r.action, r.action == MovementAction.PRONE ? player.yBodyRot : r.yaw);
    }

    /** The action a player is drawn in (the local player in third person included), null when none. */
    @Nullable
    public static MovementAction poseAction(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) {
            if (ClientSlide.amount() > 0) return MovementAction.SLIDE;
            if (ClientDive.active()) return MovementAction.DIVE;
            if (ClientProne.active()) return MovementAction.PRONE;
            return null;
        }
        Remote r = remote.get(entityId);
        return r == null ? null : r.action;
    }

    /** How far into its action's pose a player is drawn, 0..1. */
    public static float poseAmount(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) {
            return Math.max(ClientSlide.amount(), Math.max(ClientDive.amount(), ClientProne.amount()));
        }
        Remote r = remote.get(entityId);
        return r == null ? 0 : r.amount(System.nanoTime());
    }

    /** The direction a player's action pose faces (the direction the server knows). */
    public static float poseYaw(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) return ClientDive.active() ? ClientDive.yaw() : ClientSlide.yaw();
        Remote r = remote.get(entityId);
        if (r == null) return 0;
        Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
        return r.action == MovementAction.PRONE && entity instanceof Player player ? player.yBodyRot : r.yaw;
    }

    static float rise(long nanos) {
        float t = Mth.clamp(nanos / (float) BLEND_IN, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float fall(long nanos) {
        float t = Mth.clamp(nanos / (float) BLEND_OUT, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
