package com.blockland.client.battleroyale;

import com.blockland.network.packet.BattleRoyalePackets;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * The zone's edge in the world: a glowing blue curtain on the circle, fading out above and below, drawn in the
 * particle pass (additive, so it never hides what is behind it) only where it is near the camera. One custom particle
 * draws it as long as the client is in a 大逃杀 match with the zone on.
 */
final class ZoneWall extends Particle {
    private static final int SEGMENTS = 160;
    /** Only the part of the curtain this close to the camera is drawn (blocks). */
    private static final double REACH = 110;
    private static final float HEIGHT_BELOW = 40, HEIGHT_ABOVE = 70;

    @Nullable
    private static ZoneWall live;

    private final Supplier<BattleRoyalePackets.Circle> circle;
    private final Supplier<Double> topY;

    private ZoneWall(ClientLevel level, Supplier<BattleRoyalePackets.Circle> circle, Supplier<Double> topY) {
        super(level, 0, 0, 0);
        this.circle = circle;
        this.topY = topY;
        this.lifetime = Integer.MAX_VALUE;
        this.hasPhysics = false;
        this.gravity = 0;
    }

    /** Keeps exactly one curtain in the world while {@code wanted}. */
    static void keep(boolean wanted, Supplier<BattleRoyalePackets.Circle> circle, Supplier<Double> topY) {
        Minecraft mc = Minecraft.getInstance();
        if (live != null && (!wanted || !live.isAlive() || live.level != mc.level)) {
            live.remove();
            live = null;
        }
        if (wanted && live == null && mc.level != null) {
            live = new ZoneWall(mc.level, circle, topY);
            mc.particleEngine.add(live);
        }
    }

    @Override
    public void tick() {
    }

    @Override
    public void render(VertexConsumer consumer, Camera camera, float partialTick) {
    }

    @Override
    public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        BattleRoyalePackets.Circle zone = circle.get();
        if (zone.radius() <= 0.5) return;
        Vec3 cam = camera.getPosition();
        double centreDistance = Math.hypot(cam.x - zone.x(), cam.z - zone.z());
        if (Math.abs(centreDistance - zone.radius()) > REACH) return;
        Matrix4f m = pose.last().pose();
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        double top = topY.get();
        float y0 = (float) (top - HEIGHT_BELOW - cam.y), y1 = (float) (top - cam.y), y2 = (float) (top + HEIGHT_ABOVE - cam.y);
        float pulse = 0.85f + 0.15f * Mth.sin((float) (System.nanoTime() / 1e9 * 2.2));
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = i * Math.PI * 2 / SEGMENTS, a1 = (i + 1) * Math.PI * 2 / SEGMENTS;
            double x0 = zone.x() + Math.cos(a0) * zone.radius(), z0 = zone.z() + Math.sin(a0) * zone.radius();
            double x1 = zone.x() + Math.cos(a1) * zone.radius(), z1 = zone.z() + Math.sin(a1) * zone.radius();
            double mx = (x0 + x1) / 2 - cam.x, mz = (z0 + z1) / 2 - cam.z;
            double near = Math.hypot(mx, mz);
            if (near > REACH) continue;
            float fade = (float) (1 - near / REACH) * pulse;
            int mid = Math.round(150 * fade) << 24 | 0x3A8CFF, edge = 0x003A8CFF;
            float ax = (float) (x0 - cam.x), az = (float) (z0 - cam.z), bx = (float) (x1 - cam.x), bz = (float) (z1 - cam.z);
            band(out, m, ax, az, bx, bz, y0, y1, edge, mid);
            band(out, m, ax, az, bx, bz, y1, y2, mid, edge);
        }
    }

    /** One quad of the curtain from {@code yLow} ({@code low} colour) up to {@code yHigh}, both windings. */
    private static void band(VertexConsumer out, Matrix4f m, float ax, float az, float bx, float bz, float yLow, float yHigh, int low, int high) {
        out.addVertex(m, ax, yLow, az).setColor(low);
        out.addVertex(m, bx, yLow, bz).setColor(low);
        out.addVertex(m, bx, yHigh, bz).setColor(high);
        out.addVertex(m, ax, yHigh, az).setColor(high);
        out.addVertex(m, ax, yHigh, az).setColor(high);
        out.addVertex(m, bx, yHigh, bz).setColor(high);
        out.addVertex(m, bx, yLow, bz).setColor(low);
        out.addVertex(m, ax, yLow, az).setColor(low);
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
