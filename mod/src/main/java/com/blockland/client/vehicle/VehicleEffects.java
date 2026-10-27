package com.blockland.client.vehicle;

import com.blockland.BlockLand;
import com.blockland.client.decal.Decals;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.impact.Impacts;
import com.blockland.client.render.LightSources;
import com.blockland.common.Sounds;
import com.blockland.network.packet.VehiclePackets;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.blockland.vehicle.VehicleFrame;
import com.blockland.vehicle.VehicleShell;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * A vehicle's effects on this client (vehicle spec §5): exhaust smoke while its engine runs, the boat's wake and spray,
 * a wreck's fire and smoke, the engine's sound (pitch by speed), headlights ({@link LightSources}), muzzle flashes and
 * the barrel's recoil, the machine gun's tracers and impacts as the server traced them ({@link Impacts}: a body bleeds
 * only when the server says it was really hurt), the shell's and the wreck's explosions.
 */
final class VehicleEffects {
    private static final int TRACER_RGB = 0xFFD27A;
    private static final RandomSource random = RandomSource.create();
    private static final Map<VehicleEntity, EngineSound> engines = new WeakHashMap<>();
    private static final Map<VehicleEntity, Supplier<LightSources.Light>> headlights = new WeakHashMap<>();

    private VehicleEffects() {
    }

    static void tick(ClientLevel level, Player player) {
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof VehicleEntity v && v.distanceToSqr(player) < 128 * 128) tick(level, v);
        }
    }

    private static void tick(ClientLevel level, VehicleEntity v) {
        boolean running = v.driver() != null && v.wreck() == 0;
        Quaterniond hull = v.hull();
        Vec3 back = VehicleFrame.forward(v.getYRot()).scale(-1);
        VehicleData.Effects fx = v.data.effects;
        float speed = Math.abs(v.speed());
        if (running) {
            float rate = 0.35f + Math.max(0, v.throttle()) * 0.65f;
            for (float[] p : fx.exhaust) {
                if (random.nextFloat() > rate) continue;
                Vec3 at = VehicleFrame.toWorld(v.position(), hull, p[0], p[1], p[2]);
                level.addParticle(ParticleTypes.SMOKE, at.x, at.y, at.z, back.x * 0.03, 0.02, back.z * 0.03);
            }
            if (!engines.containsKey(v)) {
                SoundEvent sound = Sounds.byId(BlockLand.id(v.data.sound("engine")));
                if (v.data.sounds.contains("engine") && sound != null) {
                    EngineSound engine = new EngineSound(v, sound);
                    engines.put(v, engine);
                    Minecraft.getInstance().getSoundManager().play(engine);
                }
            }
            attachHeadlights(v);
        }
        if (fx.wake != null && v.isInWater() && speed > 2) {
            Vec3 at = VehicleFrame.toWorld(v.position(), hull, fx.wake[0], fx.wake[1], fx.wake[2]);
            int count = 1 + (int) (speed / 6);
            for (int i = 0; i < count; i++) {
                double side = (random.nextDouble() - 0.5) * 1.6;
                Vec3 right = VehicleFrame.right(v.getYRot());
                level.addParticle(ParticleTypes.SPLASH, at.x + right.x * side, at.y, at.z + right.z * side, back.x * 0.1, 0.12, back.z * 0.1);
            }
            level.addParticle(ParticleTypes.BUBBLE, at.x, at.y - 0.3, at.z, 0, 0.02, 0);
        }
        if (v.wreck() > 0) {
            Vec3 c = v.position().add(0, v.getBbHeight() * 0.6, 0);
            double spread = v.getBbWidth() * 0.4;
            level.addParticle(ParticleTypes.LARGE_SMOKE, c.x + (random.nextDouble() - 0.5) * spread, c.y, c.z + (random.nextDouble() - 0.5) * spread,
                    0, 0.06, 0);
            if (random.nextFloat() < 0.6f) {
                level.addParticle(ParticleTypes.FLAME, c.x + (random.nextDouble() - 0.5) * spread, c.y, c.z + (random.nextDouble() - 0.5) * spread,
                        0, 0.03, 0);
            }
        }
    }

    /** The vehicle's headlights while its engine runs (a light a little ahead of each lamp). */
    private static void attachHeadlights(VehicleEntity v) {
        Supplier<LightSources.Light> existing = headlights.get(v);
        if (existing != null && LightSources.isAttached(existing) || v.data.effects.headlights.isEmpty()) return;
        Supplier<LightSources.Light> light = () -> {
            Minecraft mc = Minecraft.getInstance();
            if (v.isRemoved() || v.level() != mc.level || v.driver() == null || v.wreck() > 0) return null;
            Vec3 sum = Vec3.ZERO;
            Quaterniond hull = v.hull();
            for (float[] p : v.data.effects.headlights) sum = sum.add(VehicleFrame.toWorld(v.position(), hull, p[0], p[1], p[2]));
            Vec3 at = sum.scale(1.0 / v.data.effects.headlights.size()).add(VehicleFrame.forward(v.getYRot()).scale(2.5));
            return new LightSources.Light(at.x, at.y, at.z, 1f, 0.94f, 0.82f, 1.3f, 12f);
        };
        headlights.put(v, light);
        LightSources.attach(light);
    }

    // ---- events from the server -----------------------------------------------------------------

    static void event(VehicleEntity v, byte id) {
        ClientLevel level = v.level() instanceof ClientLevel l ? l : null;
        if (level == null) return;
        Vec3 centre = v.position().add(0, v.getBbHeight() * 0.5, 0);
        if (id == VehicleEntity.EVENT_HIT) {
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.CRIT, centre.x + random.nextGaussian() * 0.4, centre.y + random.nextGaussian() * 0.3,
                        centre.z + random.nextGaussian() * 0.4, random.nextGaussian() * 0.2, 0.2, random.nextGaussian() * 0.2);
            }
        } else if (id == VehicleEntity.EVENT_DESTROYED) {
            level.addParticle(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 0, 0, 0);
            LightSources.flash(centre.x, centre.y, centre.z, 0xFF8A30, 3f, 10f, 0.4f);
        } else if (id == VehicleEntity.EVENT_EXPLODED) {
            explosion(level, centre, 1.4f);
        } else if (id >= VehicleEntity.EVENT_FIRE && id < VehicleEntity.EVENT_FIRE + v.data.weapons.size()) {
            int weapon = id - VehicleEntity.EVENT_FIRE;
            VehicleData.Weapon w = v.data.weapons.get(weapon);
            v.recoil[weapon] = 1;
            Vec3[] muzzle = v.muzzle(weapon);
            Vec3 at = muzzle[0], dir = muzzle[1];
            LightSources.flash(at.x, at.y, at.z, 0xFFB050, w.shell() ? 4f : 1.6f, w.shell() ? 14f : 6f, w.shell() ? 0.12f : 0.05f);
            int smoke = w.shell() ? 14 : 2;
            for (int i = 0; i < smoke; i++) {
                double s = w.shell() ? 0.25 : 0.05;
                level.addParticle(w.shell() ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, at.x, at.y, at.z,
                        dir.x * s * random.nextDouble() + random.nextGaussian() * 0.03, dir.y * s * random.nextDouble() + 0.02,
                        dir.z * s * random.nextDouble() + random.nextGaussian() * 0.03);
            }
            if (w.shell()) level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, dir.x * 0.3, dir.y * 0.3, dir.z * 0.3);
        }
    }

    /** A machine gun's round as the server traced it: tracer, impact, and the hit marker for its own gunner. */
    static void shot(VehiclePackets.S2CShot shot) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !(level.getEntity(shot.vehicle()) instanceof VehicleEntity v) || shot.weapon() >= v.data.weapons.size()) return;
        VehicleData.Weapon w = v.data.weapons.get(shot.weapon());
        Vec3 from = v.muzzle(shot.weapon())[0], end = new Vec3(shot.x(), shot.y(), shot.z());
        if (end.distanceToSqr(from) < 1e-4) return;
        Vec3 dir = end.subtract(from).normalize();
        mc.particleEngine.add(new Tracer(level, from, end));
        if (shot.face() >= 0 && shot.face() < Direction.values().length) {
            Direction face = Direction.values()[shot.face()];
            BlockPos pos = BlockPos.containing(end.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01));
            Impacts.block(level, end, face, pos, dir, w.ammo);
            Decals.onBlock("bullet_hole", end, face, pos, null);
        } else if (shot.target() >= 0) {
            Entity target = level.getEntity(shot.target());
            // a body bleeds only when the server says the round really hurt it
            if (target != null && (shot.wounded() || !Impacts.bleeds(target))) {
                Impacts.entity(level, end, target, dir, w.ammo);
                if (shot.wounded()) Decals.onEntity("bullet_wound", target, from, end, null);
            }
            Entity gunner = v.occupant(w.seat);
            if (gunner != null && gunner == mc.player && (shot.wounded() || target instanceof VehicleEntity)) HitMarker.show(false, false);
        }
    }

    static void shellExploded(VehicleShell shell) {
        if (shell.level() instanceof ClientLevel level) explosion(level, shell.position().add(0, 0.3, 0), 1);
    }

    private static void explosion(ClientLevel level, Vec3 at, float size) {
        level.addParticle(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 0, 0, 0);
        for (int i = 0; i < 12 * size; i++) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, at.x + random.nextGaussian() * size, at.y + random.nextDouble() * size,
                    at.z + random.nextGaussian() * size, random.nextGaussian() * 0.05, 0.08, random.nextGaussian() * 0.05);
        }
        LightSources.flash(at.x, at.y, at.z, 0xFFA040, 5f * size, 16f * size, 0.45f);
    }

    static void clear() {
        engines.clear();
        headlights.clear();
    }

    // ---- sound and tracer -----------------------------------------------------------------------

    /** The engine, following its vehicle: higher with speed, louder with the throttle; ends with the engine. */
    private static final class EngineSound extends AbstractTickableSoundInstance {
        private final VehicleEntity vehicle;

        EngineSound(VehicleEntity vehicle, SoundEvent sound) {
            super(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.vehicle = vehicle;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.8f;
            this.attenuation = Attenuation.LINEAR;
            follow();
        }

        @Override
        public void tick() {
            if (vehicle.isRemoved() || vehicle.driver() == null || vehicle.wreck() > 0) {
                engines.remove(vehicle);
                stop();
                return;
            }
            float share = Mth.clamp(Math.abs(vehicle.speed()) / Math.max(1, vehicle.data.movement.maxSpeed), 0, 1);
            pitch = 0.65f + share * 0.9f;
            volume = 0.6f + Math.abs(vehicle.throttle()) * 0.4f;
            follow();
        }

        private void follow() {
            x = vehicle.getX();
            y = vehicle.getY() + 1;
            z = vehicle.getZ();
        }
    }

    /** A short bright streak travelling from the muzzle to where the round stopped. */
    private static final class Tracer extends Particle {
        private static final double SPEED = 24;
        private final Vec3 from, to;
        private final double length;

        Tracer(ClientLevel level, Vec3 from, Vec3 to) {
            super(level, from.x, from.y, from.z);
            this.from = from;
            this.to = to;
            this.length = from.distanceTo(to);
            this.lifetime = Math.max(2, (int) Math.ceil(length / SPEED) + 1);
            this.hasPhysics = false;
            this.gravity = 0;
        }

        @Override
        public void tick() {
            if (age++ >= lifetime) remove();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            double head = Math.min(length, (age + partialTick) * SPEED);
            double tail = Math.max(0, head - Math.min(5, length * 0.5));
            if (head <= tail) return;
            Vec3 dir = to.subtract(from).normalize();
            float fade = 1 - (float) Math.max(0, age + partialTick - (lifetime - 1));
            beam(buffers.getBuffer(RenderType.lightning()), pose.last().pose(), from.add(dir.scale(tail)), from.add(dir.scale(head)),
                    camera.getPosition(), 0.035f, argb(0, TRACER_RGB), argb(0.9f * fade, TRACER_RGB));
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.CUSTOM;
        }

        @Override
        public boolean shouldCull() {
            return false;
        }

        private static void beam(VertexConsumer out, Matrix4f m, Vec3 a, Vec3 b, Vec3 camera, float width, int argbA, int argbB) {
            Vec3 side = b.subtract(a).cross(a.add(b).scale(0.5).subtract(camera)).normalize().scale(width * 0.5);
            if (!Double.isFinite(side.x)) return;
            Vec3 ra = a.subtract(camera), rb = b.subtract(camera);
            vertex(out, m, ra.add(side), argbA);
            vertex(out, m, ra.subtract(side), argbA);
            vertex(out, m, rb.subtract(side), argbB);
            vertex(out, m, rb.add(side), argbB);
        }

        private static void vertex(VertexConsumer out, Matrix4f m, Vec3 p, int argb) {
            out.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(argb);
        }

        private static int argb(float a, int rgb) {
            return (Mth.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
        }
    }
}
