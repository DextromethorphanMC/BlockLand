package com.blockland.client.parachute;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.ParachutePacketListeners;
import com.blockland.network.packet.ParachutePackets;
import com.blockland.parachute.ParachuteContent;
import com.blockland.parachute.ParachuteRules;
import com.blockland.parachute.Parachutes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The parachute (降落伞) on this client: wearing one in the air, a press of jump opens the canopy and the next closes
 * it again. Minecraft movement belongs to the client, so the canopy is predicted at once — the fall braked to a slow,
 * steady descent that the movement keys steer, a gentle swing of the camera, the wind — and the server only confirms or
 * refuses it (and opens or closes it itself, e.g. a game deploying it or a landing). Other players' canopies arrive
 * from the server and are drawn by {@link CanopyRenderer}. Tuning: {@link ParachuteRules}.
 */
public final class ClientParachute {
    /** Seconds a canopy takes to fold away when it closes. */
    static final float CLOSE_SECONDS = 0.35f;

    // the local canopy
    private static boolean open;
    private static int openTicks;
    private static long changedNanos;
    private static boolean jumpBefore;
    private static int tickCount, readyTick;
    /** Smoothed sideways steering (−1 … 1) for the camera's bank. */
    private static float bank;
    @Nullable
    private static WindSound wind;

    /** Other players' canopies by entity id: whether open and since when (their inflation and folding). */
    private static final Map<Integer, Remote> remote = new HashMap<>();
    @Nullable
    private static Level level;

    private record Remote(boolean open, long since) {
    }

    private ClientParachute() {
    }

    public static void init(BusGroup modBus) {
        BLNetwork.setParachuteClientListener(new ParachutePacketListeners.Client() {
            @Override
            public void onState(ParachutePackets.S2CState state) {
                received(state);
            }
        });
        Parachutes.setClientView(ClientParachute::isOpen);
        MovementInputUpdateEvent.BUS.addListener(ClientParachute::input);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("parachute_hud"), ForgeLayeredDraw.CROSSHAIR, ParachuteHud::render));
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
            float a = amount(System.nanoTime());
            if (a <= 0) return;
            ParachuteRules.Camera c = ParachuteRules.get().camera;
            double t = System.nanoTime() / 1e9;
            event.setRoll(event.getRoll() + (float) (Math.sin(t * 1.15) * c.sway + bank * c.bank) * a);
            event.setPitch(event.getPitch() + (float) Math.sin(t * 0.8 + 1.3) * c.sway * 0.35f * a);
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || open || !ParachuteRules.wears(player) || player.onGround()) return;
            // free fall: the view widens a little with the speed
            float fall = (float) Mth.clamp((-player.getDeltaMovement().y - 0.6) / 1.6, 0, 1);
            if (fall > 0) event.setFOV(event.getFOV() * (1 + ParachuteRules.get().camera.fovKick * fall));
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            open = false;
            changedNanos = 0;
            remote.clear();
        });
        CanopyRenderer.init();
    }

    // ---- the local canopy -----------------------------------------------------------------------------

    /** Every tick, after the movement keys were read and before the player moves. */
    private static void input(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        boolean jump = event.getInput().keyPresses.jump();
        boolean pressed = jump && !jumpBefore;
        jumpBefore = jump;
        if (open && !ParachuteRules.mayBeOpen(player)) {
            // landed, swimming, mounted, lost it: the server closes its side by itself
            close(player, false);
        } else if (pressed && Minecraft.getInstance().screen == null && tickCount >= readyTick) {
            if (open) close(player, true);
            else if (ParachuteRules.mayOpen(player, 0)) open(player, true);
        }
        if (open) glide(player, event.getInput().getMoveVector());
    }

    /** One tick under the canopy: the fall braked towards the descent speed, the keys steering it. */
    private static void glide(LocalPlayer player, Vec2 keys) {
        ParachuteRules rules = ParachuteRules.get();
        openTicks++;
        float k = Mth.clamp(openTicks / (float) Math.max(1, rules.deployTicks), 0, 1);
        float filled = 1 - (1 - k) * (1 - k);
        Vec3 v = player.getDeltaMovement();
        // the canopy fills: the fastest fall it allows sinks from the jolt's speed to the steady descent
        double maxFall = Mth.lerp(filled, rules.deployFallSpeed, rules.descentSpeed);
        double vy = v.y < -maxFall ? -maxFall : v.y + (-rules.descentSpeed - v.y) * 0.3 * filled;
        // forward keys speed it up, back keys brake, sideways keys turn it off the line
        double yaw = Math.toRadians(player.getYRot());
        double forward = keys.y >= 0 ? rules.forwardGlide + keys.y * (rules.steerSpeed - rules.forwardGlide)
                : rules.forwardGlide * (1 + keys.y);
        double strafe = keys.x * rules.steerSpeed * 0.75;
        double tx = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double tz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        double response = rules.steerResponse * (0.3 + 0.7 * filled);
        player.setDeltaMovement(v.x + (tx - v.x) * response, vy, v.z + (tz - v.z) * response);
        bank += (Mth.clamp(-keys.x, -1, 1) - bank) * 0.08f;
        player.resetFallDistance();
    }

    private static void open(LocalPlayer player, boolean send) {
        open = true;
        openTicks = 0;
        changedNanos = System.nanoTime();
        readyTick = tickCount + ParachuteRules.get().cooldownTicks;
        play(player, "parachute.open", 1.1f);
        if (send) BLNetwork.sendToServer(new ParachutePackets.C2SToggle(true));
    }

    private static void close(LocalPlayer player, boolean send) {
        if (!open) return;
        open = false;
        changedNanos = System.nanoTime();
        readyTick = tickCount + ParachuteRules.get().cooldownTicks;
        play(player, "parachute.close", 0.8f);
        if (send) BLNetwork.sendToServer(new ParachutePackets.C2SToggle(false));
    }

    private static void play(LocalPlayer player, String sound, float volume) {
        player.level().playLocalSound(player.getX(), player.getY() + 2, player.getZ(), ParachuteContent.sound(sound), SoundSource.PLAYERS,
                volume, 0.95F + player.getRandom().nextFloat() * 0.1F, false);
    }

    private static void tick() {
        tickCount++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            remote.clear();
            open = false;
            changedNanos = 0;
        }
        long now = System.nanoTime();
        remote.values().removeIf(r -> !r.open && now - r.since > (long) (CLOSE_SECONDS * 1e9));
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!open) bank *= 0.9f;
        // the wind: under the canopy, or rushing past in free fall with a parachute on
        boolean falling = ParachuteRules.wears(player) && !player.onGround() && player.getDeltaMovement().y < -0.5;
        if ((open || falling) && (wind == null || wind.isStopped())) {
            wind = new WindSound(player);
            mc.getSoundManager().play(wind);
        }
    }

    private static void received(ParachutePackets.S2CState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && state.entityId() == mc.player.getId()) {
            // the server refused, closed or opened our canopy
            if (state.open() && !open) open(mc.player, false);
            else if (!state.open() && open) close(mc.player, false);
            return;
        }
        Remote before = remote.get(state.entityId());
        if (before != null && before.open == state.open()) return;
        remote.put(state.entityId(), new Remote(state.open(), System.nanoTime()));
    }

    // ---- what the renderer, the HUD and other modules ask ---------------------------------------------

    static boolean localOpen() {
        return open;
    }

    /** 0..1 how far the local canopy is filled (rises and spreads when opening, folds away when closing). */
    static float amount(long now) {
        if (changedNanos == 0) return 0;
        return amount(open, now - changedNanos);
    }

    /** The same for any player's canopy (the local player included). */
    static float amount(int entityId, long now) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) return amount(now);
        Remote r = remote.get(entityId);
        return r == null ? 0 : amount(r.open, now - r.since);
    }

    private static float amount(boolean opened, long nanos) {
        float seconds = nanos / 1e9f;
        if (opened) return Mth.clamp(seconds / (ParachuteRules.get().deployTicks / 20f), 0, 1);
        return Mth.clamp(1 - seconds / CLOSE_SECONDS, 0, 1);
    }

    /** This client's view for {@link Parachutes}: the local prediction, and the canopies the server reported. */
    private static boolean isOpen(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) return open;
        Remote r = remote.get(entity.getId());
        return r != null && r.open;
    }
}
