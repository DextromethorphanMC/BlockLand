package com.blockland.client.gun;

import com.blockland.client.gltf.GltfModel;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunStats;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * World-space gun effects as client particles (no server traffic, culled and batched by Minecraft):
 * tracers, third-person muzzle flashes, physical shell casings and laser beams/dots.
 */
final class GunParticles {
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    private GunParticles() {
    }

    /** Glowing camera-facing quad strip from a to b (additive). */
    private static void beam(VertexConsumer out, Matrix4f m, Vec3 a, Vec3 b, Vec3 camera, float width, int argbA, int argbB) {
        Vec3 dir = b.subtract(a);
        Vec3 mid = a.add(b).scale(0.5).subtract(camera);
        Vec3 side = dir.cross(mid).normalize().scale(width * 0.5);
        if (!Double.isFinite(side.x)) return;
        Vec3 ra = a.subtract(camera), rb = b.subtract(camera);
        vertex(out, m, ra.add(side), argbA);
        vertex(out, m, ra.subtract(side), argbA);
        vertex(out, m, rb.subtract(side), argbB);
        vertex(out, m, rb.add(side), argbB);
    }

    /** Camera-facing square of half-size {@code r} at p. */
    private static void billboard(VertexConsumer out, Matrix4f m, Vec3 p, Camera camera, float r, int argb) {
        Vec3 rel = p.subtract(camera.getPosition());
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        float lx = left.x() * r, ly = left.y() * r, lz = left.z() * r, ux = up.x() * r, uy = up.y() * r, uz = up.z() * r;
        out.addVertex(m, (float) rel.x - lx - ux, (float) rel.y - ly - uy, (float) rel.z - lz - uz).setColor(argb);
        out.addVertex(m, (float) rel.x - lx + ux, (float) rel.y - ly + uy, (float) rel.z - lz + uz).setColor(argb);
        out.addVertex(m, (float) rel.x + lx + ux, (float) rel.y + ly + uy, (float) rel.z + lz + uz).setColor(argb);
        out.addVertex(m, (float) rel.x + lx - ux, (float) rel.y + ly - uy, (float) rel.z + lz - uz).setColor(argb);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, Vec3 p, int argb) {
        out.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(argb);
    }

    private static int argb(float a, int rgb) {
        return (Mth.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** Approximate muzzle of a third-person player: right hand raised forward (crossbow hold). */
    static Vec3 thirdPersonMuzzle(Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        float yaw = (float) Math.toRadians(player.getViewYRot(partialTick));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return eye.add(look.scale(0.75)).add(right.scale(0.22)).add(0, -0.28, 0);
    }

    // ---- tracer -------------------------------------------------------------------------------------

    /** A short bright streak travelling from the muzzle to where the bullet stopped. */
    static final class Tracer extends Particle {
        private static final double SPEED = 22; // blocks per tick (~440 m/s)
        private final Vec3 from;
        private final Vec3 to;
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
            double tail = Math.max(0, head - Math.min(3.5, length * 0.5));
            if (head <= tail) return;
            Vec3 dir = to.subtract(from).normalize();
            float fade = 1 - (float) Math.max(0, (age + partialTick - (lifetime - 1)));
            beam(buffers.getBuffer(RenderType.lightning()), pose.last().pose(), from.add(dir.scale(tail)), from.add(dir.scale(head)),
                    camera.getPosition(), 0.028f, argb(0, 0xFFE6A0), argb(0.85f * fade, 0xFFF4D0));
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.CUSTOM;
        }

        @Override
        public boolean shouldCull() {
            return false;
        }
    }

    // ---- third-person muzzle flash ------------------------------------------------------------------

    static final class Flash extends Particle {
        private final float size;

        Flash(ClientLevel level, Vec3 at) {
            super(level, at.x, at.y, at.z);
            this.lifetime = 2;
            this.hasPhysics = false;
            this.size = 0.16f + random.nextFloat() * 0.08f;
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
            float life = 1 - (age + partialTick) / (lifetime + 1);
            VertexConsumer out = buffers.getBuffer(RenderType.lightning());
            Vec3 p = new Vec3(x, y, z);
            billboard(out, pose.last().pose(), p, camera, size * 1.6f, argb(0.35f * life, 0xFF9A30));
            billboard(out, pose.last().pose(), p, camera, size, argb(0.9f * life, 0xFFE2A0));
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.CUSTOM;
        }
    }

    // ---- shell casing -------------------------------------------------------------------------------

    /** A spent case flying out of the ejection port, tumbling, bouncing once or twice with a tink. */
    static final class Shell extends Particle {
        @Nullable
        private final GltfModel.Primitive mesh;
        private final Quaternionf spin = new Quaternionf();
        private final Vector3f axis;
        private float angularSpeed;
        private boolean landed;

        Shell(ClientLevel level, Vec3 at, Vec3 velocity, @Nullable GltfModel.Primitive mesh) {
            super(level, at.x, at.y, at.z);
            this.mesh = mesh;
            this.xd = velocity.x;
            this.yd = velocity.y;
            this.zd = velocity.z;
            this.gravity = 1.1f;
            this.friction = 0.96f;
            this.lifetime = 50 + random.nextInt(20);
            this.hasPhysics = true;
            this.axis = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).normalize();
            this.angularSpeed = 0.6f + random.nextFloat() * 0.6f;
            this.spin.rotateAxis(random.nextFloat() * 6.28f, axis);
        }

        @Override
        public void tick() {
            super.tick();
            spin.rotateAxis(angularSpeed, axis);
            if (onGround && !landed) {
                landed = true;
                angularSpeed *= 0.25f;
                yd = 0.12;
                SoundEvent tink = GunContent.sound("gun.shell");
                if (tink != null) {
                    Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(tink, SoundSource.PLAYERS, 0.35f,
                            0.9f + random.nextFloat() * 0.25f, random, x, y, z));
                }
            } else if (onGround) {
                angularSpeed *= 0.7f;
            }
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            if (mesh == null) return;
            Vec3 cam = camera.getPosition();
            pose.pushPose();
            pose.translate(Mth.lerp(partialTick, xo, x) - cam.x, Mth.lerp(partialTick, yo, y) - cam.y, Mth.lerp(partialTick, zo, z) - cam.z);
            pose.mulPose(spin);
            PoseStack.Pose last = pose.last();
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
            VertexConsumer out = buffers.getBuffer(RenderType.entitySolid(WHITE));
            float[] p = mesh.positions, n = mesh.normals;
            int brass = 0xFFD8A84E;
            for (int t = 0; t + 2 < mesh.indices.length; t += 3) {
                // Minecraft's entity buffers are quads: emit each triangle as a quad with a repeated corner
                for (int corner = 0; corner < 4; corner++) {
                    int v = mesh.indices[t + Math.min(corner, 2)] * 3;
                    out.addVertex(last, p[v], p[v + 1], p[v + 2]).setColor(brass).setUv(0.5f, 0.5f)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, n[v], n[v + 1], n[v + 2]);
                }
            }
            pose.popPose();
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.CUSTOM;
        }
    }

    // ---- lasers -------------------------------------------------------------------------------------

    /**
     * A player's laser: the dot where it hits (always) and the beam (for other players and third person;
     * the local first-person beam is part of the viewmodel). Lives while the player holds a gun with a laser.
     */
    static final class Laser extends Particle {
        private final Player owner;

        Laser(ClientLevel level, Player owner) {
            super(level, owner.getX(), owner.getEyeY(), owner.getZ());
            this.owner = owner;
            this.lifetime = Integer.MAX_VALUE;
            this.hasPhysics = false;
        }

        static Optional<GunData.Laser> laserOf(Player player) {
            return GunContent.statsOf(player.getMainHandItem()).map(GunStats::laser).filter(GunData.Laser::hasBeam);
        }

        @Override
        public void tick() {
            if (owner.isRemoved() || !owner.isAlive() || owner.isSpectator() || laserOf(owner).isEmpty()) remove();
            xo = x;
            yo = y;
            zo = z;
            x = owner.getX();
            y = owner.getEyeY();
            z = owner.getZ();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            Optional<GunData.Laser> laser = laserOf(owner);
            if (laser.isEmpty()) return;
            int rgb = laser.get().argb() & 0xFFFFFF;
            Minecraft mc = Minecraft.getInstance();
            boolean firstPerson = owner == mc.player && !camera.isDetached();
            Vec3 eye = owner.getEyePosition(partialTick);
            Vec3 look = owner.getViewVector(partialTick);
            Vec3 end = eye.add(look.scale(48));
            HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            Vec3 dot = hitSomething ? hit.getLocation() : end;
            VertexConsumer out = buffers.getBuffer(RenderType.lightning());
            Matrix4f m = pose.last().pose();
            if (!firstPerson) {
                beam(out, m, thirdPersonMuzzle(owner, partialTick), dot, camera.getPosition(), 0.012f, argb(0.55f, rgb), argb(0.1f, rgb));
            }
            if (hitSomething) {
                // pull the dot slightly towards the viewer so it does not sink into the surface
                Vec3 towards = camera.getPosition().subtract(dot).normalize().scale(0.02);
                double distance = camera.getPosition().distanceTo(dot);
                float size = (float) Math.min(0.09, 0.02 + distance * 0.0025);
                billboard(out, m, dot.add(towards), camera, size * 2.6f, argb(0.25f, rgb));
                billboard(out, m, dot.add(towards), camera, size, argb(0.95f, rgb));
            }
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.CUSTOM;
        }

        @Override
        public boolean shouldCull() {
            return false;
        }
    }
}
