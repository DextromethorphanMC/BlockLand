package com.blockland.client.battleroyale;

import com.blockland.BlockLand;
import com.blockland.battleroyale.BattleRoyaleContent;
import com.blockland.battleroyale.BrPlane;
import com.blockland.network.BLNetwork;
import com.blockland.network.BattleRoyalePacketListeners;
import com.blockland.network.packet.BattleRoyalePackets;
import com.blockland.parachute.Parachutes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Client side of 大逃杀: the opening fly-over (the camera sweeps around the island while everyone already sits in the
 * plane), the chase camera behind the plane, the jump key, the passengers kept out of sight, the engine and the wind,
 * the zone curtain and the HUD. Everything shown comes from the server's state and the plane's synced route; the
 * client only asks to jump.
 */
public final class ClientBattleRoyale {
    /** Blocks the chase camera stays behind and above the plane. */
    private static final double CHASE_DISTANCE = 24, CHASE_HEIGHT = 7;

    private static BattleRoyalePackets.S2CState state = BattleRoyalePackets.S2CState.NONE;
    private static int stateTick;
    private static int ticks;
    /** The intro's whole length (its first phaseTicks), for the camera path. */
    private static int introTotal = 1;
    private static float introAngle;
    private static boolean jumpWasDown;
    private static final Map<Integer, EngineSound> engines = new HashMap<>();
    @Nullable
    private static WindSound wind;
    private static final RandomSource random = RandomSource.create();

    @Nullable
    private static Method setPosition;
    private static boolean lookedUp;

    private ClientBattleRoyale() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(BattleRoyaleContent.PLANE.get(), BrPlaneRenderer::new));
        EntityRenderersEvent.RegisterLayerDefinitions.getBus(modBus).addListener(event ->
                event.registerLayerDefinition(BrPlaneModel.LAYER, BrPlaneModel::createLayer));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("battle_royale_hud"), ForgeLayeredDraw.CROSSHAIR, BrHud::render));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> reset());
        // the HUD's own map of the island stands in for the minimap while it shows
        com.blockland.client.hud.minimap.Minimap.hideWhile(() -> state.phase() == BattleRoyalePackets.Phase.FLIGHT
                || state.phase() == BattleRoyalePackets.Phase.FIGHT);
        ViewportEvent.ComputeCameraAngles.BUS.addListener(ClientBattleRoyale::camera);
        // passengers of the plane are out of sight: nobody sees players stacked in the cabin
        RenderPlayerEvent.Pre.BUS.addListener(event -> {
            Minecraft mc = Minecraft.getInstance();
            Entity entity = mc.level == null ? null : mc.level.getEntity(event.getState().id);
            return entity != null && entity.getVehicle() instanceof BrPlane;
        });
        RenderHandEvent.BUS.addListener(event -> aboard() || state.phase() == BattleRoyalePackets.Phase.INTRO);
        BLNetwork.setBattleRoyaleClientListener(new BattleRoyalePacketListeners.Client() {
            @Override
            public void onState(BattleRoyalePackets.S2CState received) {
                if (received.phase() == BattleRoyalePackets.Phase.INTRO && state.phase() != BattleRoyalePackets.Phase.INTRO) {
                    introTotal = Math.max(1, received.phaseTicks());
                    introAngle = random.nextFloat() * Mth.TWO_PI;
                }
                state = received;
                stateTick = ticks;
            }
        });
    }

    private static void reset() {
        state = BattleRoyalePackets.S2CState.NONE;
        engines.values().forEach(EngineSound::end);
        engines.clear();
        if (wind != null) wind.end();
        wind = null;
        ZoneWall.keep(false, () -> state.zoneFrom(), () -> state.topY());
    }

    // ---- read by the HUD ------------------------------------------------------------------------------

    static BattleRoyalePackets.S2CState state() {
        return state;
    }

    static float ticksSinceState(float partialTick) {
        return ticks - stateTick + partialTick;
    }

    /** The zone right now (counted on from the last state). */
    static BattleRoyalePackets.Circle zone(float partialTick) {
        return state.zoneAt(ticksSinceState(partialTick));
    }

    static boolean inMatch() {
        return state.phase() != BattleRoyalePackets.Phase.NONE;
    }

    /** The local player sits in the plane. */
    static boolean aboard() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof BrPlane;
    }

    @Nullable
    static BrPlane plane() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof BrPlane plane ? plane : null;
    }

    /** 0..1 of the intro played (1 outside it). */
    static float introProgress(float partialTick) {
        if (state.phase() != BattleRoyalePackets.Phase.INTRO) return 1;
        float left = state.phaseTicks() - ticksSinceState(partialTick);
        return Mth.clamp(1 - left / introTotal, 0, 1);
    }

    // ---- ticking --------------------------------------------------------------------------------------

    private static void clientTick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        boolean fighting = state.phase() == BattleRoyalePackets.Phase.FLIGHT || state.phase() == BattleRoyalePackets.Phase.FIGHT;
        ZoneWall.keep(fighting && state.zone(), () -> zone(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)),
                () -> state.topY());
        // the jump key leaves the plane while it flies
        boolean jumpDown = mc.options.keyJump.isDown() && mc.screen == null;
        if (jumpDown && !jumpWasDown && aboard() && state.phase() == BattleRoyalePackets.Phase.FLIGHT) {
            BLNetwork.sendToServer(BattleRoyalePackets.C2SJump.INSTANCE);
        }
        jumpWasDown = jumpDown;
        // an engine sound for every plane around
        for (BrPlane plane : mc.level.getEntitiesOfClass(BrPlane.class, player.getBoundingBox().inflate(400))) {
            EngineSound sound = engines.get(plane.getId());
            if (sound == null || sound.isStopped()) {
                sound = new EngineSound(plane);
                engines.put(plane.getId(), sound);
                mc.getSoundManager().play(sound);
            }
        }
        engines.values().removeIf(EngineSound::isStopped);
        // the roar of the free fall (until the canopy opens or the ground comes)
        boolean falling = inMatch() && !aboard() && !player.onGround() && !player.isSpectator() && player.getDeltaMovement().y < -0.5
                && !Parachutes.isOpen(player);
        if (falling && (wind == null || wind.isStopped())) {
            wind = new WindSound(player);
            mc.getSoundManager().play(wind);
        }
    }

    // ---- camera ---------------------------------------------------------------------------------------

    /** The fly-over during the intro, the chase view while aboard; nothing else is touched. */
    private static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !inMatch()) return;
        float partial = (float) event.getPartialTick();
        Camera camera = event.getCamera();
        if (state.phase() == BattleRoyalePackets.Phase.INTRO) {
            double cx = (state.minX() + state.maxX()) / 2, cz = (state.minZ() + state.maxZ()) / 2;
            double half = Math.hypot(state.maxX() - state.minX(), state.maxZ() - state.minZ()) / 2;
            float t = introProgress(partial);
            float eased = t * t * (3 - 2 * t);
            double angle = introAngle + eased * Math.toRadians(110);
            double radius = half * (1.15 - 0.35 * eased) + 12, height = state.topY() + 18 + half * 0.35 * (1 - eased);
            Vec3 eye = new Vec3(cx + Math.cos(angle) * radius, height, cz + Math.sin(angle) * radius);
            look(event, camera, eye, new Vec3(cx, state.topY() - 10, cz));
            return;
        }
        BrPlane plane = plane();
        if (plane == null) return;
        Vec3 at = plane.positionAt(plane.progress(partial));
        float yaw = event.getYaw(), pitch = event.getPitch();
        Vec3 view = Vec3.directionFromRotation(pitch, yaw);
        place(camera, at.subtract(view.scale(CHASE_DISTANCE)).add(0, CHASE_HEIGHT, 0));
    }

    private static void look(ViewportEvent.ComputeCameraAngles event, Camera camera, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        event.setYaw((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90);
        event.setPitch((float) -(Mth.atan2(d.y, Math.hypot(d.x, d.z)) * Mth.RAD_TO_DEG));
        event.setRoll(0);
        place(camera, eye);
    }

    private static void place(Camera camera, Vec3 at) {
        Method method = setter();
        if (method == null) return;
        try {
            method.invoke(camera, at);
        } catch (ReflectiveOperationException | RuntimeException e) {
            setPosition = null;
        }
    }

    @Nullable
    private static Method setter() {
        if (lookedUp) return setPosition;
        lookedUp = true;
        try {
            setPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
            setPosition.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Battle royale camera unavailable: {}", e.toString());
            setPosition = null;
        }
        return setPosition;
    }

    // ---- sounds ---------------------------------------------------------------------------------------

    /** The plane's engines, following it, loud near it and fading with distance. */
    private static final class EngineSound extends AbstractTickableSoundInstance {
        private final BrPlane plane;

        EngineSound(BrPlane plane) {
            super(BattleRoyaleContent.sound("battleroyale.plane_engine"), SoundSource.NEUTRAL, RandomSource.create());
            this.plane = plane;
            this.looping = true;
            this.delay = 0;
            this.volume = 3.0f;
            this.attenuation = Attenuation.LINEAR;
            follow();
        }

        void end() {
            stop();
        }

        private void follow() {
            x = plane.getX();
            y = plane.getY();
            z = plane.getZ();
        }

        @Override
        public void tick() {
            if (plane.isRemoved()) {
                stop();
                return;
            }
            follow();
            // inside the cabin it is a muffled drone
            volume = plane.hasPassenger(Minecraft.getInstance().player) ? 0.7f : 3.0f;
        }
    }

    /** The wind of the free fall, on the player, louder the faster they fall. */
    private static final class WindSound extends AbstractTickableSoundInstance {
        private final LocalPlayer player;

        WindSound(LocalPlayer player) {
            super(BattleRoyaleContent.sound("battleroyale.wind"), SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.1f;
            this.relative = true;
        }

        void end() {
            stop();
        }

        @Override
        public void tick() {
            if (player.isRemoved() || player.onGround() || player.isSpectator() || !inMatch()) {
                stop();
                return;
            }
            double speed = -player.getDeltaMovement().y;
            float target = Parachutes.isOpen(player) ? 0.15f : (float) Mth.clamp((speed - 0.3) / 2.5, 0, 1) * 0.9f;
            volume += (target - volume) * 0.2f;
            if (volume < 0.02f && Parachutes.isOpen(player)) stop();
        }
    }
}
