package com.blockland.client.vehicle;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.VehiclePacketListeners;
import com.blockland.network.packet.VehiclePackets;
import com.blockland.vehicle.VehicleContent;
import com.blockland.vehicle.VehicleControl;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.blockland.vehicle.VehicleFrame;
import com.blockland.vehicle.VehicleShell;
import com.blockland.vehicle.Vehicles;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Vehicles on this client (vehicle spec §1, §4, §5): their renderers; the driver's input (W/S throttle, A/D steer, space
 * brake, sprint boost), predicted at once by the vehicle and matched to the server's state; gunners firing (attack held)
 * and looking through their sight (use held: the view from the gun, zoomed, following the barrel as it turns); seats
 * (number keys for riders working the vehicle, J for the next free seat, sneak to get out), the horn (H); cameras —
 * seats inside a hull start in third person, pulled back to fit the vehicle; riders' bodies tilted with the hull, the
 * ones inside it hidden; the HUD; and the effects ({@link VehicleEffects}).
 */
public final class ClientVehicles {
    public static final KeyMapping HORN = new KeyMapping("key.blockland.vehicle_horn", InputConstants.KEY_H, "key.categories.blockland");
    public static final KeyMapping SEAT = new KeyMapping("key.blockland.vehicle_seat", InputConstants.KEY_J, "key.categories.blockland");

    /** The camera before a seat changed it (restored when leaving it), the camera before the sight. */
    @Nullable
    private static CameraType seatCamera, sightCamera;
    private static int lastSeat = -2;
    private static boolean sighting;
    private static int ticks;
    private static final int[] nextFire = new int[2];
    @Nullable
    private static KeyMapping reloadKey;
    private static boolean reloadLooked;
    @Nullable
    private static Method setPosition;
    private static boolean cameraLooked;
    private static final Deque<PlayerRenderState> tilted = new ArrayDeque<>();

    private ClientVehicles() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event -> {
            VehicleContent.TYPES.values().forEach(type -> event.registerEntityRenderer(type.get(), VehicleRenderer::new));
            event.registerEntityRenderer(VehicleContent.SHELL.get(), context -> new ThrownItemRenderer<>(context, 1.0F, true));
        });
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> {
            event.register(HORN);
            event.register(SEAT);
        });
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("vehicle_hud"), ForgeLayeredDraw.CROSSHAIR, VehicleHud::render));
        Vehicles.setClient(new Vehicles.Client() {
            @Override
            public Player localPlayer() {
                return Minecraft.getInstance().player;
            }

            @Override
            public VehicleControl input(VehicleEntity vehicle) {
                return driverInput();
            }

            @Override
            public void send(VehicleEntity vehicle, VehicleControl control) {
                BLNetwork.sendToServer(new VehiclePackets.C2SControl(vehicle.getId(), control.seq(), (byte) Math.round(control.throttle() * 100),
                        (byte) Math.round(control.steer() * 100), control.brake(), control.boost()));
            }

            @Override
            public void shellExploded(VehicleShell shell) {
                VehicleEffects.shellExploded(shell);
            }

            @Override
            public void event(VehicleEntity vehicle, byte event) {
                VehicleEffects.event(vehicle, event);
            }
        });
        BLNetwork.setVehicleClientListener(new VehiclePacketListeners.Client() {
            @Override
            public void onState(VehiclePackets.S2CState state) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null || !(mc.level.getEntity(state.vehicle()) instanceof VehicleEntity v) || v.driver() != mc.player) return;
                v.reconcile(state.seq(), new Vec3(state.x(), state.y(), state.z()), state.yaw(), new Vec3(state.vx(), state.vy(), state.vz()),
                        state.pitch(), state.roll());
            }

            @Override
            public void onShot(VehiclePackets.S2CShot shot) {
                VehicleEffects.shot(shot);
            }
        });
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> keys());
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> holdGunnerView());
        // attack and use belong to the vehicle while the hands are on it (driver, gunners, inside the hull)
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(event -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || event.isPickBlock() || !VehicleEntity.handsBusy(player)) return false;
            event.setSwingHand(false);
            return true;
        });
        RenderHandEvent.BUS.addListener(Priority.HIGHEST, event -> {
            LocalPlayer player = Minecraft.getInstance().player;
            return player != null && VehicleEntity.handsBusy(player);
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(ClientVehicles::camera);
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            VehicleData.Weapon w = sightWeapon();
            if (sighting && w != null && event.usedConfiguredFov()) event.setFOV(event.getFOV() / Math.max(1, w.zoom));
        });
        RenderPlayerEvent.Pre.BUS.addListener(ClientVehicles::riderPre);
        RenderPlayerEvent.Post.BUS.addListener(event -> {
            if (!tilted.isEmpty() && tilted.peek() == event.getState()) {
                tilted.pop();
                event.getPoseStack().popPose();
            }
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            VehicleEffects.clear();
            seatCamera = null;
            sightCamera = null;
            sighting = false;
            lastSeat = -2;
        });
    }

    /** Whether the local gunner looks through the sight now. */
    static boolean sighting() {
        return sighting;
    }

    // ---- input ----------------------------------------------------------------------------------

    private static VehicleControl driverInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return VehicleControl.NONE;
        Input k = mc.player.input.keyPresses;
        float throttle = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0);
        float steer = (k.right() ? 1 : 0) - (k.left() ? 1 : 0);
        return new VehicleControl(0, throttle, steer, k.jump(), k.sprint());
    }

    /** Seat keys, before vanilla reads the hotbar keys (riders working the vehicle use them for seats). */
    private static void keys() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || !(player.getVehicle() instanceof VehicleEntity v)) return;
        int seat = v.seatOf(player);
        if (VehicleEntity.handsBusy(player)) {
            for (int i = 0; i < Math.min(9, v.data.seats.size()); i++) {
                while (mc.options.keyHotbarSlots[i].consumeClick()) {
                    if (i != seat) BLNetwork.sendToServer(new VehiclePackets.C2SAction(v.getId(), VehiclePackets.Action.SEAT, i));
                }
            }
        }
        while (SEAT.consumeClick()) {
            for (int step = 1; step < v.data.seats.size(); step++) {
                int next = (seat + step) % v.data.seats.size();
                if (v.occupant(next) == null) {
                    BLNetwork.sendToServer(new VehiclePackets.C2SAction(v.getId(), VehiclePackets.Action.SEAT, next));
                    break;
                }
            }
        }
        while (HORN.consumeClick()) {
            if (seat == 0) BLNetwork.sendToServer(new VehiclePackets.C2SAction(v.getId(), VehiclePackets.Action.HORN, 0));
        }
        KeyMapping reload = reloadKey();
        int weapon = v.data.weaponIndexOf(seat);
        if (reload != null && weapon >= 0) {
            while (reload.consumeClick()) BLNetwork.sendToServer(new VehiclePackets.C2SAction(v.getId(), VehiclePackets.Action.RELOAD, weapon));
        }
    }

    /** The guns' reload key (looked up by its name: the gun module's own binding, so one key reloads everything). */
    @Nullable
    private static KeyMapping reloadKey() {
        if (!reloadLooked) {
            reloadLooked = true;
            for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
                if ("key.blockland.reload".equals(mapping.getName())) reloadKey = mapping;
            }
        }
        return reloadKey;
    }

    private static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        VehicleEffects.tick(mc.level, player);
        if (!(player.getVehicle() instanceof VehicleEntity v)) {
            leftVehicle(mc);
            return;
        }
        int seat = v.seatOf(player);
        if (seat < 0) return;
        VehicleData.Seat s = v.data.seats.get(seat);
        if (seat != lastSeat) {
            // a seat inside a hull starts in third person; back to the player's own camera in an open seat
            if (s.thirdPerson()) {
                if (seatCamera == null) seatCamera = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            } else if (seatCamera != null) {
                mc.options.setCameraType(seatCamera);
                seatCamera = null;
            }
            lastSeat = seat;
        }
        // inside a hull the first-person camera would sit in the armour: only the sight looks out from in there
        if (!s.exposed && !sighting && mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        int weapon = v.data.weaponIndexOf(seat);
        boolean wantSight = weapon >= 0 && mc.screen == null && mc.options.keyUse.isDown() && v.wreck() == 0;
        if (wantSight && !sighting) {
            sightCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            sighting = true;
        } else if (!wantSight && sighting) {
            endSight(mc);
        }
        if (weapon >= 0 && mc.screen == null && mc.options.keyAttack.isDown() && v.wreck() == 0 && ticks >= nextFire[weapon]
                && v.reloadLeft(weapon) == 0 && v.ammo(weapon) > 0) {
            BLNetwork.sendToServer(new VehiclePackets.C2SAction(v.getId(), VehiclePackets.Action.FIRE, weapon));
            nextFire[weapon] = ticks + v.data.weapons.get(weapon).fireTicks();
        }
    }

    private static void leftVehicle(Minecraft mc) {
        if (lastSeat == -2) return;
        if (sighting) endSight(mc);
        if (seatCamera != null) {
            mc.options.setCameraType(seatCamera);
            seatCamera = null;
        }
        lastSeat = -2;
    }

    private static void endSight(Minecraft mc) {
        sighting = false;
        if (sightCamera != null) mc.options.setCameraType(sightCamera);
        sightCamera = null;
    }

    @Nullable
    private static VehicleData.Weapon sightWeapon() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.getVehicle() instanceof VehicleEntity v)) return null;
        int weapon = v.data.weaponIndexOf(v.seatOf(player));
        return weapon < 0 ? null : v.data.weapons.get(weapon);
    }

    // ---- a gunner's aim ----------------------------------------------------------------------------

    /** This client's own weapon aim, stepped every frame (model-space yaw / pitch of {@link #predictedIndex}). */
    private static float predictedYaw, predictedPitch;
    private static int predictedIndex = -1;
    @Nullable
    private static VehicleEntity predictedVehicle;
    private static long predictedAt;

    /**
     * The mouse turns a gunner's weapon, at most at its turn rate: every frame the aim steps towards where the mouse
     * points and the view is held on it (mouse movement beyond the rate is dropped). So the weapon never runs on after
     * the mouse stops, and the server — which turns the weapon towards the gunner's view — follows the same path however
     * late its view arrives.
     */
    private static void holdGunnerView() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int index = player != null && player.getVehicle() instanceof VehicleEntity v ? v.data.weaponIndexOf(v.seatOf(player)) : -1;
        if (index < 0) {
            predictedIndex = -1;
            predictedVehicle = null;
            return;
        }
        VehicleEntity v = (VehicleEntity) player.getVehicle();
        VehicleData.Weapon w = v.data.weapons.get(index);
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = System.nanoTime();
        if (predictedVehicle != v || predictedIndex != index) {
            predictedVehicle = v;
            predictedIndex = index;
            predictedYaw = v.aimYawO[index] + Mth.wrapDegrees(v.shownYaw[index] - v.aimYawO[index]) * pt;
            predictedPitch = Mth.lerp(pt, v.aimPitchO[index], v.shownPitch[index]);
            predictedAt = now;
        }
        float dt = Mth.clamp((now - predictedAt) / 1e9f, 0, 0.1f);
        predictedAt = now;
        float hullYaw = Mth.rotLerp(pt, v.yRotO, v.getYRot()), hullPitch = v.hullPitch(pt);
        // a weapon on the turret (not the turret's own) turns from the turret's way
        float turret = v.aimYawO[0] + Mth.wrapDegrees(v.shownYaw[0] - v.aimYawO[0]) * pt;
        float base = hullYaw + (w.onTurret() && index != 0 ? turret : 0);
        float targetYaw = Mth.wrapDegrees(player.getYRot() - base);
        if (w.yawLimit != null && w.yawLimit.length == 2) targetYaw = Mth.clamp(targetYaw, w.yawLimit[0], w.yawLimit[1]);
        float targetPitch = Mth.clamp(-player.getXRot() - hullPitch, w.pitchLimit[0], w.pitchLimit[1]);
        float step = w.turnRate * dt;
        predictedYaw = Mth.wrapDegrees(Mth.approachDegrees(predictedYaw, targetYaw, step));
        predictedPitch = Mth.approach(predictedPitch, targetPitch, step);
        // the view on the aim (and its previous-frame value moved with it, so nothing jumps)
        float dy = Mth.wrapDegrees(base + predictedYaw - player.getYRot());
        float dp = -(predictedPitch + hullPitch) - player.getXRot();
        player.setYRot(player.getYRot() + dy);
        player.yRotO += dy;
        player.setYHeadRot(player.getYRot());
        player.setXRot(player.getXRot() + dp);
        player.xRotO += dp;
    }

    // ---- camera ---------------------------------------------------------------------------------

    /**
     * Through the sight: the view from just ahead of the muzzle, along the barrel (which follows the mouse at its turn
     * rate). In third person: pulled back from the vehicle's middle far enough for its size, stopped by blocks.
     */
    private static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !(player.getVehicle() instanceof VehicleEntity v) || event.getCamera().getEntity() != player) return;
        float pt = (float) event.getPartialTick();
        Camera camera = event.getCamera();
        int weapon = v.data.weaponIndexOf(v.seatOf(player));
        Vec3 origin = v.getPosition(pt);
        float yaw = Mth.rotLerp(pt, v.yRotO, v.getYRot());
        Quaterniond hull = VehicleFrame.hull(yaw, v.hullPitch(pt), v.hullRoll(pt));
        if (sighting && weapon >= 0) {
            float[] yaws = new float[v.aimYawO.length], pitches = new float[v.aimPitchO.length];
            for (int i = 0; i < yaws.length; i++) {
                yaws[i] = v.aimYawO[i] + Mth.wrapDegrees(v.shownYaw[i] - v.aimYawO[i]) * pt;
                pitches[i] = Mth.lerp(pt, v.aimPitchO[i], v.shownPitch[i]);
            }
            // through one's own sight: one's own aim (the server's comes a round trip later)
            if (predictedVehicle == v && predictedIndex == weapon) {
                yaws[weapon] = predictedYaw;
                pitches[weapon] = predictedPitch;
            }
            Vec3[] muzzle = VehicleFrame.muzzle(v.data, weapon, origin, hull, yaws, pitches);
            Vec3 dir = muzzle[1];
            event.setYaw((float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90);
            event.setPitch((float) -(Mth.atan2(dir.y, Math.hypot(dir.x, dir.z)) * Mth.RAD_TO_DEG));
            event.setRoll(0);
            place(camera, muzzle[0].add(dir.scale(0.3)));
            return;
        }
        if (mc.options.getCameraType().isFirstPerson()) return;
        double extent = 2;
        for (VehicleData.HitBox b : v.data.hitBoxes) extent = Math.max(extent, Math.max(b.half[0], b.half[2]));
        Vec3 pivot = origin.add(0, v.getBbHeight() * 0.75 + 0.8, 0);
        Vec3 look = Vec3.directionFromRotation(event.getPitch(), event.getYaw());
        Vec3 want = pivot.subtract(look.scale(Math.max(4, extent * 1.6 + 1.5)));
        BlockHitResult hit = mc.level.clip(new ClipContext(pivot, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) want = hit.getLocation().add(look.scale(0.3));
        place(camera, want);
    }

    private static void place(Camera camera, Vec3 at) {
        if (!cameraLooked) {
            cameraLooked = true;
            try {
                setPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
                setPosition.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                BlockLand.LOGGER.warn("[BlockLand] Vehicle camera unavailable: {}", e.toString());
            }
        }
        if (setPosition == null) return;
        try {
            setPosition.invoke(camera, at);
        } catch (ReflectiveOperationException | RuntimeException e) {
            setPosition = null;
        }
    }

    // ---- riders ---------------------------------------------------------------------------------

    /** Riders inside a hull are not drawn; the others lean and tip with the hull about their hips. */
    private static boolean riderPre(RenderPlayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        PlayerRenderState state = event.getState();
        Entity entity = mc.level.getEntity(state.id);
        if (entity == null || !(entity.getVehicle() instanceof VehicleEntity v)) return false;
        if (VehicleEntity.isSheltered(entity)) return true;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float pitch = v.hullPitch(pt), roll = v.hullRoll(pt);
        if (Math.abs(pitch) < 0.1f && Math.abs(roll) < 0.1f) return false;
        float yaw = Mth.rotLerp(pt, v.yRotO, v.getYRot());
        Vec3 f = VehicleFrame.forward(yaw), r = VehicleFrame.right(yaw);
        Quaternionf q = new Quaternionf()
                .rotateAxis((float) Math.toRadians(pitch), new Vector3f((float) r.x, 0, (float) r.z))
                .rotateAxis((float) Math.toRadians(-roll), new Vector3f((float) f.x, 0, (float) f.z));
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        tilted.push(state);
        float hip = VehicleEntity.HIP * state.scale;
        pose.translate(0, hip, 0);
        pose.mulPose(q);
        pose.translate(0, -hip, 0);
        return false;
    }
}
