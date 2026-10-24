package com.blockland.client.graphics.wind;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.Std140Builder;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The wind (rendering spec §18), written into the scene block every frame for the lighting pack's terrain vertex shader,
 * which bends the plants by it. A steady wind whose heading wanders slowly and whose strength follows the weather; gusts
 * that are born upwind of the camera, travel across the world along the wind (so neighbouring plants bend one after the
 * other) and die downwind; the living entities near the camera, which push plants aside; the places they just left, where
 * the plants spring back; and the plant columns (sugar cane, kelp, vines …) around the camera, so a stacked plant bends as
 * one stem from its base. Tunables in {@code graphics/wind.json} ({@link WindData}). Client thread only (the render
 * thread is the same).
 */
public final class Wind {
    /** Entities within this many blocks push plants; their trails are left within {@link #SWAY_RANGE}. */
    private static final double PUSH_RANGE = 40, SWAY_RANGE = 28;
    /** Where a body was, the plants spring back for this long (s). */
    private static final float SWAY_LIFE = 1.4f;
    /** A walking body leaves a mark every this many blocks; one that stands keeps its mark fresh. */
    private static final double SWAY_STEP = 0.35;

    private static WindData data = new WindData();
    private static final PlantColumns columns = new PlantColumns();
    @Nullable
    private static GpuTexture columnTexture;

    private static final RandomSource random = RandomSource.create();
    private static final List<Gust> gusts = new ArrayList<>();
    private static final List<LivingEntity> pushers = new ArrayList<>();
    /** Where each nearby entity last left a mark (entity id → position) and which slot holds it. */
    private static final Int2ObjectOpenHashMap<Vec3> lastSway = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap lastSlot = new Int2IntOpenHashMap();

    static {
        lastSlot.defaultReturnValue(-1);
    }
    private static final double[] swayX = new double[SceneLighting.MAX_SWAYS], swayY = new double[SceneLighting.MAX_SWAYS],
            swayZ = new double[SceneLighting.MAX_SWAYS];
    private static final float[] swayRadius = new float[SceneLighting.MAX_SWAYS], swayDirX = new float[SceneLighting.MAX_SWAYS],
            swayDirZ = new float[SceneLighting.MAX_SWAYS], swayStrength = new float[SceneLighting.MAX_SWAYS];
    private static final long[] swayBorn = new long[SceneLighting.MAX_SWAYS];
    private static int nextSway;

    /** The steady wind: heading (radians, wandering), its rate of turn, strength (eased towards the weather's). */
    private static float heading = (float) (Math.random() * Math.PI * 2), turn, strength = 0.25f;
    private static boolean sky = true;
    private static long tick;
    private static ClientLevel level;

    /** A gust front: its centre (world xz) at {@code born}, travelling along its heading. */
    private static final class Gust {
        double x, z;
        float dirX, dirZ, speed, length, width, strength, seed;
        long born;
        float life;

        double x(long now) {
            return x + dirX * speed * age(now);
        }

        double z(long now) {
            return z + dirZ * speed * age(now);
        }

        float age(long now) {
            return (now - born) / 1e9f;
        }

        /** Grows in over two seconds and fades over the last two, so a gust never pops in or out. */
        float fade(long now) {
            float age = age(now);
            return Mth.clamp(Math.min(age, life - age) / 2f, 0, 1);
        }
    }

    private Wind() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    data = WindData.load(manager);
                    columns.configure(WindData.blocks(data.columns.up), WindData.blocks(data.columns.hang));
                }));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
    }

    private static boolean on() {
        GraphicsSettings settings = Graphics.settings();
        return settings.wind && settings.windStrength > 0.001f;
    }

    private static void clear() {
        gusts.clear();
        pushers.clear();
        lastSway.clear();
        lastSlot.clear();
        columns.clear();
        for (int i = 0; i < swayBorn.length; i++) swayBorn[i] = 0;
        level = null;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world != level) {
            clear();
            level = world;
        }
        if (world == null || mc.isPaused() || !on()) {
            pushers.clear();
            return;
        }
        tick++;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        columns.tick(world, Mth.floor(camera.x), Mth.floor(camera.y), Mth.floor(camera.z));
        sky = world.dimensionType().hasSkyLight();
        float rain = sky ? world.getRainLevel(1) : 0, thunder = sky ? world.getThunderLevel(1) : 0;

        // the steady wind: its heading wanders (faster in a storm), its strength follows the weather and breathes slowly
        turn = turn * 0.985f + (random.nextFloat() - 0.5f) * 0.0009f * (1 + 2 * thunder);
        heading += turn;
        float target = sky ? 0.22f + 0.42f * rain + 0.45f * thunder : 0.08f;
        target *= 0.85f + 0.15f * Mth.sin(tick * 0.0021f) + 0.08f * Mth.sin(tick * 0.0137f);
        strength += (target - strength) * 0.01f;

        // gusts: born upwind out of sight's middle distance, gone once they are well past
        long now = System.nanoTime();
        gusts.removeIf(g -> g.age(now) > g.life);
        float rate = sky ? 0.12f + 0.35f * rain + 0.5f * thunder : 0;
        if (gusts.size() < SceneLighting.MAX_GUSTS && random.nextFloat() < rate / 20f) spawnGust(camera, now, rain, thunder);

        // the entities pushing plants: the nearest living ones
        pushers.clear();
        for (Entity entity : world.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity.isSpectator() || entity.isRemoved()) continue;
            double dx = entity.getX() - camera.x, dz = entity.getZ() - camera.z, dy = entity.getY() - camera.y;
            if (dx * dx + dz * dz > PUSH_RANGE * PUSH_RANGE || Math.abs(dy) > 24) continue;
            pushers.add(living);
        }
        pushers.sort(Comparator.comparingDouble(e -> e.distanceToSqr(camera)));
        if (pushers.size() > SceneLighting.MAX_PUSHERS) pushers.subList(SceneLighting.MAX_PUSHERS, pushers.size()).clear();

        // where they are, they leave a mark: once they are gone from it the plants there spring back. A walker leaves one
        // every SWAY_STEP; one standing still keeps its last mark fresh (it springs back from the moment it leaves)
        float pad = data.push.radiusPad;
        for (LivingEntity entity : pushers) {
            if (entity.distanceToSqr(camera) > SWAY_RANGE * SWAY_RANGE) continue;
            Vec3 at = entity.position();
            Vec3 last = lastSway.get(entity.getId());
            int slot = lastSlot.get(entity.getId());
            double mx = last == null ? 0 : at.x - last.x, mz = last == null ? 0 : at.z - last.z;
            double moved = Math.sqrt(mx * mx + mz * mz);
            boolean grounded = entity.onGround() || entity.isInWater();
            if (grounded && last != null && moved < SWAY_STEP && slot >= 0 && swayBorn[slot] != 0) {
                swayBorn[slot] = now;
                continue;
            }
            if (!grounded || moved > 4) {
                // in the air, or a teleport: nothing to spring back from there
                lastSway.put(entity.getId(), at);
                lastSlot.remove(entity.getId());
                continue;
            }
            float dirX = moved > 1e-3 ? (float) (mx / moved) : 0, dirZ = moved > 1e-3 ? (float) (mz / moved) : 0;
            lastSlot.put(entity.getId(), addSway(at, dirX, dirZ, entity.getBbWidth() * 0.5f + pad, 1, now));
            lastSway.put(entity.getId(), at);
        }
        if (tick % 100 == 0) {
            lastSway.keySet().removeIf(id -> world.getEntity(id) == null);
            lastSlot.keySet().removeIf(id -> world.getEntity(id) == null);
        }
    }

    private static void spawnGust(Vec3 camera, long now, float rain, float thunder) {
        Gust g = new Gust();
        float angle = heading + (random.nextFloat() - 0.5f) * 0.4f;
        g.dirX = Mth.cos(angle);
        g.dirZ = Mth.sin(angle);
        g.speed = 5 + 4 * random.nextFloat() + 3 * thunder;
        g.length = 5 + 7 * random.nextFloat();
        g.width = 14 + 22 * random.nextFloat();
        g.strength = (0.5f + 0.6f * random.nextFloat()) * (1 + 0.6f * rain + 0.8f * thunder);
        g.seed = random.nextFloat() * 100;
        float upwind = 45 + 25 * random.nextFloat(), across = (random.nextFloat() - 0.5f) * 50;
        g.x = camera.x - g.dirX * upwind - g.dirZ * across;
        g.z = camera.z - g.dirZ * upwind + g.dirX * across;
        // until it is as far downwind as it was born upwind, and a little more
        g.life = (upwind * 2 + 15) / g.speed;
        g.born = now;
        gusts.add(g);
    }

    private static int addSway(Vec3 at, float dirX, float dirZ, float radius, float power, long now) {
        int i = nextSway;
        nextSway = (nextSway + 1) % SceneLighting.MAX_SWAYS;
        swayX[i] = at.x;
        swayY[i] = at.y;
        swayZ[i] = at.z;
        swayDirX[i] = dirX;
        swayDirZ[i] = dirZ;
        swayRadius[i] = radius;
        swayStrength[i] = power;
        swayBorn[i] = now;
        return i;
    }

    /** The plant columns' texture for the terrain shader ({@code BlPlantColumns}), null before the first upload. */
    @Nullable
    public static GpuTexture columns() {
        return columnTexture;
    }

    /**
     * BlWind, BlWindInfo, the tunables (BlWindBend, BlWindStack, BlWindPush, BlWindSpring, BlWindKinds), BlGusts[],
     * BlPushers[] and BlSways[] of the scene block (scene.glsl's order), camera-relative. With the wind off nothing moves.
     * Uploads the plant columns first (called before the level renders, outside any render pass).
     */
    public static void write(Std140Builder out, double camX, double camY, double camZ, float partialTick) {
        boolean on = on() && level != null && level == Minecraft.getInstance().level;
        float amount = on ? Graphics.settings().windStrength : 0;
        out.putVec4(Mth.cos(heading), Mth.sin(heading), on ? strength : 0, amount); // BlWind
        long now = System.nanoTime();
        int gustCount = on ? Math.min(gusts.size(), SceneLighting.MAX_GUSTS) : 0;
        int pusherCount = on ? Math.min(pushers.size(), SceneLighting.MAX_PUSHERS) : 0;
        int swayCount = 0;
        if (on) {
            for (long born : swayBorn) if (born != 0 && (now - born) / 1e9f < SWAY_LIFE) swayCount++;
        }
        out.putVec4(gustCount, pusherCount, swayCount, on ? 1 : 0); // BlWindInfo
        if (on) columnTexture = columns.upload();
        WindData.Bend bend = data.bend;
        out.putVec4(bend.lean, bend.flutter, bend.exponent, bend.lag); // BlWindBend
        WindData.Stack stack = data.stack;
        out.putVec4(stack.lean, stack.flutter, stack.pushCap, on && columnTexture != null ? 1 : 0); // BlWindStack
        WindData.Push push = data.push;
        out.putVec4((float) Math.toRadians(push.lean), push.flatten, push.core, 0); // BlWindPush
        float omega = (float) (2 * Math.PI / Math.max(0.05f, push.springPeriod));
        out.putVec4(omega, push.springDamping * omega, push.trail, 0); // BlWindSpring
        out.putVec4(push.kinds.bend, push.kinds.sway, push.kinds.leaves, push.kinds.water); // BlWindKinds
        for (int i = 0; i < SceneLighting.MAX_GUSTS; i++) {
            if (i < gustCount) {
                Gust g = gusts.get(i);
                out.putVec4((float) (g.x(now) - camX), (float) (g.z(now) - camZ), g.length, g.strength * g.fade(now));
                out.putVec4(g.dirX, g.dirZ, g.width, g.seed);
            } else {
                out.putVec4(0, 0, 1, 0);
                out.putVec4(1, 0, 1, 0);
            }
        }
        for (int i = 0; i < SceneLighting.MAX_PUSHERS; i++) {
            if (i < pusherCount) {
                LivingEntity e = pushers.get(i);
                double x = Mth.lerp(partialTick, e.xo, e.getX()), y = Mth.lerp(partialTick, e.yo, e.getY()),
                        z = Mth.lerp(partialTick, e.zo, e.getZ());
                // a body that is gone fades out instead of letting the plants snap back in one frame
                float presence = e.isRemoved() || e.isDeadOrDying() ? 0 : 1;
                out.putVec4((float) (x - camX), (float) (y - camY), (float) (z - camZ), e.getBbWidth() * 0.5f + data.push.radiusPad);
                out.putVec4((float) (e.getX() - e.xo) * 20, (float) (e.getZ() - e.zo) * 20, e.getBbHeight(), presence);
            } else {
                out.putVec4(0, 0, 0, 1);
                out.putVec4(0, 0, 0, 0);
            }
        }
        int written = 0;
        for (int i = 0; i < SceneLighting.MAX_SWAYS && on; i++) {
            if (swayBorn[i] == 0) continue;
            float age = (now - swayBorn[i]) / 1e9f;
            if (age >= SWAY_LIFE) continue;
            out.putVec4((float) (swayX[i] - camX), (float) (swayY[i] - camY), (float) (swayZ[i] - camZ), swayRadius[i]);
            out.putVec4(swayDirX[i], swayDirZ[i], age, swayStrength[i]);
            written++;
        }
        for (; written < SceneLighting.MAX_SWAYS; written++) {
            out.putVec4(0, 0, 0, 1);
            out.putVec4(0, 0, 0, 0);
        }
    }
}
