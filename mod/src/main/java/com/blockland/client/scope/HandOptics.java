package com.blockland.client.scope;

import com.blockland.client.render.RenderClock;
import com.blockland.client.settings.ScopeSensitivity;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A hand-held optic that is not on a weapon (binoculars, asset spec §52), behind {@link Optics}: the owning module
 * feeds it the optic and how far it is raised each frame; from {@link #VIEW_FROM} on the player looks through it — the
 * world's field of view narrows to the power (the wheel steps through the powers, eased like a turned focus ring), the
 * turning slows by the scope sensitivity of that power, the view sways a little (less when sneaking, more when walking),
 * the hands are not drawn and {@link HandOpticOverlay} is the view. The rangefinder measures along the view a few times a
 * second. All of it is this client's presentation; nothing is sent.
 */
final class HandOptics {
    /** From this raise amount the player looks through the optic (the jump of the view hides in a short blink). */
    static final float VIEW_FROM = 0.88f;

    @Nullable
    private static HandOptic optic;
    private static float raised;
    private static final Map<String, Integer> chosen = new HashMap<>();
    private static int level;
    private static float shown = 1;
    private static long zoomChanged;
    private static float clock;
    private static final Vector2f sway = new Vector2f();
    private static final Vector2f scratch = new Vector2f();
    /** The last measured distance (blocks), or NaN when nothing was in range; and seconds to the next measurement. */
    private static float range = Float.NaN;
    private static float rangeWait;
    /** When the view last switched between through the optic and around it (ns, render clock). */
    private static long switched;
    private static boolean wasThrough;

    private HandOptics() {
    }

    static void init() {
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (event.usedConfiguredFov() && viewing()) event.setFOV(zoomedFov((float) event.getFOV()));
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!viewing()) return;
            event.setPitch(event.getPitch() + sway.y);
            event.setYaw(event.getYaw() + sway.x);
        });
        // looking through it, the eyepieces are the view: no hands, no held item
        RenderHandEvent.BUS.addListener(event -> {
            return viewing();
        });
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> measure(0.05f));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            optic = null;
            raised = 0;
            range = Float.NaN;
        });
    }

    /** Called every frame by the owner: the optic held (null = none) and how far it is raised (0..1). */
    static void update(@Nullable HandOptic held, float amount, float dt) {
        if (held != optic) {
            optic = held;
            if (held != null) {
                level = Math.max(0, Math.min(held.powerCount() - 1, chosen.getOrDefault(held.id, held.startPower)));
                shown = held.power(level);
            }
            range = Float.NaN;
            rangeWait = 0;
        }
        raised = held == null ? 0 : Math.max(0, Math.min(1, amount));
        boolean through = raised >= VIEW_FROM;
        if (through != wasThrough) switched = RenderClock.nanos();
        wasThrough = through;
        if (held == null) {
            sway.set(0, 0);
            return;
        }
        float target = held.power(level);
        float k = 1 - (float) Math.exp(-dt * Math.max(0.1f, held.zoomEase));
        shown = (float) Math.exp(Math.log(shown) + (Math.log(target) - Math.log(shown)) * k);
        updateSway(held, dt);
    }

    /** Breathing drift (the weapons' scope sway), larger at higher power, braced when sneaking, shaky on the move. */
    private static void updateSway(HandOptic held, float dt) {
        clock += dt;
        LocalPlayer player = Minecraft.getInstance().player;
        float moving = 0, sneak = 1;
        if (player != null) {
            Vec3 v = player.getDeltaMovement();
            moving = (float) Math.min(1, Math.sqrt(v.x * v.x + v.z * v.z) / 0.13);
            if (player.isShiftKeyDown()) sneak = held.swaySneaking;
        }
        float top = held.power(held.powerCount() - 1);
        float amplitude = held.sway * (shown / top) * sneak * (1 + (held.swayMoving - 1) * moving) * view();
        ScopeOptics.sway(clock * (1 + 0.8f * moving), scratch);
        sway.set(scratch.x * amplitude, scratch.y * amplitude);
    }

    /** Looking through the optic (first person only, as vanilla's spyglass). */
    static boolean viewing() {
        Minecraft mc = Minecraft.getInstance();
        return optic != null && raised >= VIEW_FROM && mc.player != null && mc.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    /** 0 … 1 over the raise's last stretch up to {@link #VIEW_FROM} (the view darkens towards the switch). */
    static float approach() {
        Minecraft mc = Minecraft.getInstance();
        if (optic == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) return 0;
        return Scopes.smooth((raised - 0.55f) / (VIEW_FROM - 0.55f));
    }

    /** 0 below the switch, then 1: what the sway and the overlay scale with. */
    private static float view() {
        return raised >= VIEW_FROM ? 1 : 0;
    }

    @Nullable
    static HandOptic optic() {
        return optic;
    }

    static float magnification() {
        return shown;
    }

    static float targetMagnification() {
        return optic == null ? 1 : optic.power(level);
    }

    /** Seconds since the view switched to or from the optic (the overlay blinks to hide the jump). */
    static float sinceSwitch() {
        return switched == 0 ? Float.MAX_VALUE : (RenderClock.nanos() - switched) / 1e9f;
    }

    /** Seconds since the power was last stepped. */
    static float sinceZoomChange() {
        return zoomChanged == 0 ? Float.MAX_VALUE : (RenderClock.nanos() - zoomChanged) / 1e9f;
    }

    /** The world's field of view through the optic at the current power (the vertical half-angle shrinks by the power). */
    static float zoomedFov(float fov) {
        double half = Math.atan(Math.tan(Math.toRadians(fov) / 2) / Math.max(1, shown));
        return (float) Math.toDegrees(2 * half);
    }

    /** The wheel while looking through: one power step per notch; true when the optic took the wheel. */
    static boolean stepZoom(int direction) {
        if (!viewing()) return false;
        int next = Math.max(0, Math.min(optic.powerCount() - 1, level + Integer.signum(direction)));
        if (next != level) {
            level = next;
            chosen.put(optic.id, level);
            zoomChanged = RenderClock.nanos();
            SoundEvent sound = optic.zoomSound == null ? null : soundOf(optic.zoomSound);
            if (sound != null) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, direction > 0 ? 1.1f : 0.92f, 0.6f));
            }
        }
        return true; // at either end the wheel still belongs to the optic
    }

    @Nullable
    private static SoundEvent soundOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) return null;
        Optional<SoundEvent> event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getOptional(location);
        return event.orElse(null);
    }

    /** Multiplier on the look sensitivity while looking through (the scopes' setting for this power), 1 otherwise. */
    static float sensitivityScale() {
        return viewing() ? ScopeSensitivity.scopeScale(shown) : 1;
    }

    // ---- the rangefinder ----------------------------------------------------------------------------------------------

    /** The last measured distance in blocks, NaN when nothing was in range (or no rangefinder). */
    static float range() {
        return range;
    }

    /** A measurement along the view (blocks first, then anything that can be picked nearer) every {@code refresh} s. */
    private static void measure(float dt) {
        if (!viewing() || optic.rangefinder == null) return;
        rangeWait -= dt;
        if (rangeWait > 0) return;
        rangeWait = Math.max(0.05f, optic.rangefinder.refresh);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        Vec3 dir = new Vec3(camera.getLookVector());
        double max = Math.max(1, optic.rangefinder.max);
        Vec3 to = from.add(dir.scale(max));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
        double best = block.getType() == HitResult.Type.MISS ? Double.NaN : from.distanceTo(block.getLocation());
        double reach = Double.isNaN(best) ? max : best;
        Vec3 end = from.add(dir.scale(reach));
        for (Entity e : level.getEntities(mc.player, new AABB(from, end).inflate(1), e -> e.isPickable() && !e.isSpectator())) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(e.getPickRadius()).clip(from, end);
            if (hit.isEmpty()) continue;
            double d = from.distanceTo(hit.get());
            if (Double.isNaN(best) || d < best) best = d;
        }
        range = Double.isNaN(best) ? Float.NaN : (float) best;
    }
}
