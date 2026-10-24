package com.blockland.client.movement;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.movement.SlideRules;
import com.blockland.movement.Slides;
import com.blockland.network.BLNetwork;
import com.blockland.network.MovementPacketListeners;
import com.blockland.network.packet.MovementPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * The slide (滑铲) on this client: sprinting on the ground, crouch (or the slide key) drops the player into a fast,
 * low slide along the sprint direction that turns a little with the view and dies down with friction; jumping out
 * keeps the momentum. Minecraft movement belongs to the client, so the slide is predicted at once — speed curve,
 * low pose, low camera — and the server only confirms or cancels it; the server also says whether the current game
 * mode allows slides at all, so only slides it will accept are predicted. Other players' slides arrive from the server
 * for their third-person pose ({@link SlidePose}) and for this client's hit prediction ({@link Slides}). Tuning:
 * {@link SlideRules}.
 */
public final class ClientSlide {
    /** Slides when pressed during a sprint, like crouching does; unbound by default. */
    public static final KeyMapping KEY = new KeyMapping("key.blockland.slide", InputConstants.UNKNOWN.getValue(), "key.categories.blockland");
    private static final long BLEND_IN = 120_000_000L;
    private static final long BLEND_OUT = 220_000_000L;

    @Nullable
    private static final Field EYE_HEIGHT = cameraField("eyeHeight");
    @Nullable
    private static final Field EYE_HEIGHT_OLD = cameraField("eyeHeightOld");

    // the server's word
    private static boolean allowed;

    // the local slide
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
    private static int tickCount, readyTick;
    private static boolean shiftBefore;
    /**
     * How far (blocks) and which way the local player moved in the last tick. The velocity field cannot tell: by the
     * time keys are read, vanilla has already taken last tick's ground friction off it.
     */
    private static double moved;
    private static float movedYaw;

    /** Other players' slides by entity id: for their pose while it plays and fades. */
    private static final Map<Integer, Remote> remote = new HashMap<>();
    @Nullable
    private static Level level;

    private static final class Remote {
        final float yaw;
        final long start;
        long end;
        float endAmount;

        Remote(float yaw, long start) {
            this.yaw = yaw;
            this.start = start;
        }

        float amount(long now) {
            return end == 0 ? rise(now - start) : endAmount * (1 - fall(now - end));
        }
    }

    /** The movement keys while sliding: they still reach everyone reading them, but no longer push the player. */
    private static final class SlideInput extends ClientInput {
        final ClientInput inner;

        SlideInput(ClientInput inner) {
            this.inner = inner;
            this.keyPresses = slideKeys(inner.keyPresses);
            this.moveVector = Vec2.ZERO;
        }

        @Override
        public void tick() {
            inner.tick();
            keyPresses = slideKeys(inner.keyPresses);
            moveVector = Vec2.ZERO;
        }

        /** No crouch while sliding (it would stop the slide at every ledge); the slide pose is forced anyway. */
        private static Input slideKeys(Input keys) {
            return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), false, keys.sprint());
        }
    }

    private ClientSlide() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(KEY));
        BLNetwork.setMovementClientListener(new MovementPacketListeners.Client() {
            @Override
            public void onSlide(MovementPackets.S2CSlide slide) {
                received(slide);
            }

            @Override
            public void onRules(MovementPackets.S2CRules rules) {
                allowed = rules.slideAllowed();
            }
        });
        Slides.setClientView(ClientSlide::slideYaw);
        MovementInputUpdateEvent.BUS.addListener(ClientSlide::input);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            float amount = amount();
            if (amount > 0 && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
                event.setRoll(event.getRoll() + SlideRules.get().camera.roll * amount);
            }
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            float amount = amount();
            if (amount > 0) event.setFOV(event.getFOV() * (1 + SlideRules.get().camera.fovKick * amount));
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            allowed = false;
            reset();
            remote.clear();
        });
    }

    // ---- the local slide ------------------------------------------------------------------------------

    /** Every tick, after the movement keys were read and before the player moves. */
    private static void input(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        if (sliding && player != slider) reset(); // respawned or changed dimension: that slide is gone
        ClientInput keys = event.getInput() instanceof SlideInput wrapped ? wrapped.inner : event.getInput();
        boolean shift = keys.keyPresses.shift();
        boolean pressed = shift && !shiftBefore;
        shiftBefore = shift;
        boolean key = false;
        while (KEY.consumeClick()) key = true;
        if (sliding) {
            slide(player, keys.keyPresses.jump());
        } else if ((pressed || key) && canStart(player)) {
            start(player);
        }
    }

    private static boolean canStart(LocalPlayer player) {
        if (!allowed || tickCount < readyTick || Minecraft.getInstance().screen != null) return false;
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
        startNanos = System.nanoTime();
        endNanos = 0;
        player.setSprinting(false);
        player.setForcedPose(Pose.CROUCHING);
        if (!(player.input instanceof SlideInput)) player.input = new SlideInput(player.input);
        move(player, startSpeed);
        player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), Sounds.byId(BlockLand.id("movement.slide")),
                SoundSource.PLAYERS, 0.9F, 0.94F + player.getRandom().nextFloat() * 0.12F, false);
        BLNetwork.sendToServer(new MovementPackets.C2SSlide(true, startYaw));
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
        if (cancelled || !allowed || blocked || airTicks > rules.airTicks || ticks >= rules.maxTicks || speed < rules.endSpeed
                || player.isPassenger() || player.isSpectator() || player.isInWater() || player.getAbilities().flying) {
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
        if (player.input instanceof SlideInput wrapped) player.input = wrapped.inner;
        if (player.getForcedPose() == Pose.CROUCHING) player.setForcedPose(null);
        endAmount = amount();
        sliding = false;
        endNanos = System.nanoTime();
        readyTick = tickCount + SlideRules.get().cooldownTicks;
        if (!byServer) BLNetwork.sendToServer(new MovementPackets.C2SSlide(false, 0));
    }

    /** Forget a slide whose player is gone (respawn, new dimension, logout); the server ends its side itself. */
    private static void reset() {
        if (slider != null) {
            if (slider.input instanceof SlideInput wrapped) slider.input = wrapped.inner;
            if (slider.getForcedPose() == Pose.CROUCHING) slider.setForcedPose(null);
        }
        slider = null;
        sliding = false;
        endNanos = 0;
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
        LocalPlayer player = mc.player;
        if (player != null) {
            double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
            moved = Math.sqrt(dx * dx + dz * dz);
            if (moved > 1e-4) movedYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
        }
        lowerCamera(mc);
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

    // ---- other players --------------------------------------------------------------------------------

    private static void received(MovementPackets.S2CSlide slide) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mc.player != null && slide.entityId() == mc.player.getId()) {
            // the server refused or ended our slide
            if (!slide.sliding() && sliding) cancelled = true;
            return;
        }
        if (slide.sliding()) {
            remote.put(slide.entityId(), new Remote(slide.yaw(), now));
        } else {
            Remote r = remote.get(slide.entityId());
            if (r != null && r.end == 0) {
                r.endAmount = r.amount(now);
                r.end = now;
            }
        }
    }

    // ---- what other modules ask --------------------------------------------------------------------------

    /** 0..1 how far into a slide the local player is (eases in and out): camera, weapon tilt. */
    public static float amount() {
        long now = System.nanoTime();
        if (sliding) return rise(now - startNanos);
        if (endNanos == 0) return 0;
        return endAmount * (1 - fall(now - endNanos));
    }

    /** How far into its slide pose a player is drawn (the local player in third person included), 0..1. */
    public static float poseAmount(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) return amount();
        Remote r = remote.get(entityId);
        return r == null ? 0 : r.amount(System.nanoTime());
    }

    /** The direction a player's slide pose faces (the slide direction the server knows). */
    public static float poseYaw(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) return startYaw;
        Remote r = remote.get(entityId);
        return r == null ? 0 : r.yaw;
    }

    /** This client's view for {@link Slides}: the local prediction, and the slides the server reported. */
    private static float slideYaw(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player) return sliding ? startYaw : Float.NaN;
        Remote r = remote.get(player.getId());
        return r != null && r.end == 0 ? r.yaw : Float.NaN;
    }

    private static float rise(long nanos) {
        float t = Mth.clamp(nanos / (float) BLEND_IN, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float fall(long nanos) {
        float t = Mth.clamp(nanos / (float) BLEND_OUT, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
