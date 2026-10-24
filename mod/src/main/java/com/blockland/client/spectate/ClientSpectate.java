package com.blockland.client.spectate;

import com.blockland.BlockLand;
import com.blockland.client.viewmodel.ViewSubject;
import com.blockland.network.BLNetwork;
import com.blockland.network.SpectatePacketListeners;
import com.blockland.network.packet.SpectatePackets;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Client side of spectating after death: what the server says the spectator is watching, the controls (the attack and
 * use buttons switch to the next / previous player — and leave the free camera —, jump opens the free camera, the
 * swap-hands key switches between the watched player's own eyes and a camera orbiting them) and that orbit camera: the
 * mouse turns it round the watched player, the scroll wheel brings it closer or further, blocks stop it. The first-person
 * view and the free camera are vanilla's spectator cameras, which the server drives; in the first-person view the weapon
 * modules show the watched player's own weapon and arms ({@link ViewSubject}, {@link HandPhaseGate}). Nothing here
 * decides anything.
 */
public final class ClientSpectate {
    private static final float MIN_DISTANCE = 1.5f, MAX_DISTANCE = 12f;

    private static SpectatePackets.S2CState state = SpectatePackets.S2CState.NONE;
    /** Client tick count when {@link #state} arrived (the HUD counts down from it). */
    private static int stateTick;
    private static int ticks;
    private static boolean thirdPerson;
    private static float distance = 4.5f;
    /** The player's own camera type before spectating changed it, restored when it ends (null = not changed). */
    @Nullable
    private static CameraType savedCameraType;
    private static Method setPosition;
    private static boolean lookedUp;

    private ClientSpectate() {
    }

    public static void init(BusGroup modBus) {
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("spectate_hud"), ForgeLayeredDraw.CROSSHAIR, SpectateHud::render));
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> tick());
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientSpectate::interaction);
        InputEvent.MouseScrollingEvent.BUS.addListener(ClientSpectate::scroll);
        ViewportEvent.ComputeCameraAngles.BUS.addListener(ClientSpectate::camera);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> end());
        // seeing through the watched player's eyes: their weapon and arms, not the spectator's empty hand
        ViewSubject.init();
        ViewSubject.setWatched(ClientSpectate::watchedInFirstPerson);
        HandPhaseGate.init(modBus);
        BLNetwork.setSpectateClientListener(received -> {
            boolean was = active();
            state = received;
            stateTick = ticks;
            if (was && !active()) end();
            else applyCameraType();
        });
    }

    // ---- read by the HUD ------------------------------------------------------------------------------

    static SpectatePackets.S2CState state() {
        return state;
    }

    static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return state.mode() != SpectatePackets.Mode.NONE && mc.player != null && mc.player.isSpectator();
    }

    static boolean thirdPerson() {
        return thirdPerson;
    }

    /** The watched player while the camera is in their eyes (not the orbit camera, not the free camera), else null. */
    @Nullable
    private static AbstractClientPlayer watchedInFirstPerson() {
        if (!active() || state.mode() != SpectatePackets.Mode.TARGET || thirdPerson) return null;
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() instanceof AbstractClientPlayer player && player != mc.player ? player : null;
    }

    /** Ticks left of the wait (respawn or the return to the lobby), counted on from the packet; -1 = no limit. */
    static float ticksLeft(float partialTick) {
        if (state.ticksLeft() < 0) return -1;
        return Math.max(0, state.ticksLeft() - (ticks - stateTick) - partialTick);
    }

    // ---- input ----------------------------------------------------------------------------------------

    private static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (!active()) {
            if (savedCameraType != null && mc.player != null && !mc.player.isSpectator()) end();
            return;
        }
        if (mc.screen != null) return;
        // read before vanilla does (the swap would only send a packet the server ignores for spectators)
        while (mc.options.keySwapOffhand.consumeClick()) {
            if (state.mode() == SpectatePackets.Mode.TARGET) {
                thirdPerson = !thirdPerson;
                applyCameraType();
            }
        }
        while (mc.options.keyJump.consumeClick()) {
            if (state.mode() == SpectatePackets.Mode.TARGET && state.canFree()) {
                BLNetwork.sendToServer(new SpectatePackets.C2SControl(SpectatePackets.Control.FREE));
            }
        }
    }

    /** The attack and use buttons switch players instead of vanilla's spectator click. */
    private static boolean interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active() || event.isPickBlock()) return false;
        event.setSwingHand(false);
        BLNetwork.sendToServer(new SpectatePackets.C2SControl(event.isAttack() ? SpectatePackets.Control.NEXT : SpectatePackets.Control.PREVIOUS));
        return true;
    }

    /** Watching a player the wheel zooms the orbit camera (vanilla's spectator menu and fly speed only in the free camera). */
    private static boolean scroll(InputEvent.MouseScrollingEvent event) {
        if (!active() || state.mode() != SpectatePackets.Mode.TARGET) return false;
        if (thirdPerson) distance = Mth.clamp(distance - (float) event.getDeltaY() * 0.6f, MIN_DISTANCE, MAX_DISTANCE);
        return true;
    }

    // ---- camera ---------------------------------------------------------------------------------------

    /**
     * The orbit view needs vanilla's detached camera (so the watched player's body is drawn); the first-person view and
     * the free camera vanilla's first person. The player's own choice comes back when spectating ends.
     */
    private static void applyCameraType() {
        Minecraft mc = Minecraft.getInstance();
        if (!active()) return;
        CameraType wanted = state.mode() == SpectatePackets.Mode.TARGET && thirdPerson ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
        if (mc.options.getCameraType() == wanted) return;
        if (savedCameraType == null) savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(wanted);
    }

    private static void end() {
        Minecraft mc = Minecraft.getInstance();
        if (savedCameraType != null) mc.options.setCameraType(savedCameraType);
        savedCameraType = null;
        state = SpectatePackets.S2CState.NONE;
    }

    /** Orbiting the watched player: the mouse (the spectator's own look) turns the camera round their eyes. */
    private static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!active() || state.mode() != SpectatePackets.Mode.TARGET || !thirdPerson) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        Entity target = mc.getCameraEntity();
        if (self == null || target == null || target == self) return;
        float partial = (float) event.getPartialTick();
        float yaw = self.getViewYRot(partial), pitch = Mth.clamp(self.getViewXRot(partial), -80, 80);
        Vec3 eye = target.getEyePosition(partial);
        Vec3 back = Vec3.directionFromRotation(pitch, yaw).scale(-distance);
        // blocks stop the camera (a little short of them, so it never looks through a wall)
        HitResult hit = target.level().clip(new ClipContext(eye, eye.add(back), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(back)
                : eye.add(hit.getLocation().subtract(eye).scale(Math.max(0, 1 - 0.3 / Math.max(0.3, hit.getLocation().distanceTo(eye)))));
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(0);
        place(event.getCamera(), at);
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
            BlockLand.LOGGER.warn("[BlockLand] Spectator orbit camera unavailable: {}", e.toString());
            setPosition = null;
        }
        return setPosition;
    }
}
