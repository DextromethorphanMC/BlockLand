package com.blockland.client.equipment;

import com.blockland.equipment.EquipmentData;
import com.blockland.movement.action.BodyActions;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A faint dotted arc of where a readied grenade would fly, with a ring where it comes down: the same flight as the
 * server's (gravity, drag, bounces), from the eye along the view, recomputed every frame. Drawn as one client
 * particle that lives while the item is held ready and removes itself after.
 */
final class TrajectoryArc extends Particle {
    private static final int MAX_TICKS = 80;
    private static final int COLOR = 0xFFFFFF;

    private final LocalPlayer player;
    private final EquipmentData.Equipment item;
    private final boolean lob;
    private final BooleanSupplier alive;
    private float fade;

    TrajectoryArc(ClientLevel level, LocalPlayer player, EquipmentData.Equipment item, boolean lob, BooleanSupplier alive) {
        super(level, player.getX(), player.getY(), player.getZ());
        this.player = player;
        this.item = item;
        this.lob = lob;
        this.alive = alive;
        this.lifetime = Integer.MAX_VALUE;
        this.hasPhysics = false;
        this.gravity = 0;
    }

    @Override
    public void tick() {
        if (!alive.getAsBoolean() || !player.isAlive()) remove();
        fade = Math.min(1, fade + 0.25f);
        setPos(player.getX(), player.getY(), player.getZ());
    }

    @Override
    public void render(VertexConsumer consumer, Camera camera, float partialTick) {
    }

    @Override
    public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        List<Vec3> points = new ArrayList<>();
        Vec3 landing = simulate(partialTick, points);
        if (points.size() < 2) return;
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        Vec3 eye = camera.getPosition();
        // dots every other tick, fading in near the hand and out towards the end
        for (int i = 1; i < points.size(); i++) {
            float t = i / (float) points.size();
            float alpha = 0.32f * fade * Math.min(1, i / 3f) * (1 - 0.6f * t);
            if (i % 2 == 0) dot(out, m, points.get(i), camera, 0.025f, alpha);
        }
        if (landing != null) ring(out, m, landing, eye, 0.35f, 0.3f * fade);
    }

    /** Steps the flight tick by tick; returns where it first touches ground (or null). */
    private Vec3 simulate(float partialTick, List<Vec3> points) {
        EquipmentData.Launch launch = item.launch(lob);
        EquipmentData.Throw t = item.throwing;
        float pitch = player.getViewXRot(partialTick) - launch.pitchUp, yaw = player.getViewYRot(partialTick);
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        Vec3 eye = BodyActions.eyePosition(player);
        Vec3 pos = eye.add(direction.scale(0.35)).add(0, -0.08, 0);
        Vec3 velocity = direction.scale(launch.speed);
        points.add(pos);
        Vec3 landing = null;
        int fuseTicks = item.fuse > 0 ? Math.round(item.fuse * 20) : MAX_TICKS;
        for (int tick = 0; tick < Math.min(MAX_TICKS, fuseTicks); tick++) {
            velocity = velocity.add(0, -t.gravity, 0);
            Vec3 to = pos.add(velocity);
            BlockHitResult hit = level.clip(new ClipContext(pos, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS) {
                Direction face = hit.getDirection();
                Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                pos = hit.getLocation().add(normal.scale(0.08));
                points.add(pos);
                if (landing == null && face == Direction.UP) landing = hit.getLocation().add(0, 0.02, 0);
                if (item.shatters() || landing != null) {
                    if (landing == null) landing = hit.getLocation();
                    break;
                }
                double into = velocity.dot(normal);
                velocity = velocity.subtract(normal.scale(into)).scale(t.friction).subtract(normal.scale(into * t.bounce));
                continue;
            }
            pos = to;
            velocity = velocity.scale(t.drag);
            points.add(pos);
        }
        return landing;
    }

    private static void dot(VertexConsumer out, Matrix4f m, Vec3 p, Camera camera, float r, float alpha) {
        Vec3 rel = p.subtract(camera.getPosition());
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        int argb = argb(alpha);
        float x = (float) rel.x, y = (float) rel.y, z = (float) rel.z;
        out.addVertex(m, x - left.x() * r - up.x() * r, y - left.y() * r - up.y() * r, z - left.z() * r - up.z() * r).setColor(argb);
        out.addVertex(m, x - left.x() * r + up.x() * r, y - left.y() * r + up.y() * r, z - left.z() * r + up.z() * r).setColor(argb);
        out.addVertex(m, x + left.x() * r + up.x() * r, y + left.y() * r + up.y() * r, z + left.z() * r + up.z() * r).setColor(argb);
        out.addVertex(m, x + left.x() * r - up.x() * r, y + left.y() * r - up.y() * r, z + left.z() * r - up.z() * r).setColor(argb);
    }

    /** A thin flat ring on the ground. */
    private static void ring(VertexConsumer out, Matrix4f m, Vec3 c, Vec3 eye, float r, float alpha) {
        int argb = argb(alpha), segments = 24;
        float w = 0.03f;
        float x = (float) (c.x - eye.x), y = (float) (c.y - eye.y), z = (float) (c.z - eye.z);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            out.addVertex(m, x + c0 * (r - w), y, z + s0 * (r - w)).setColor(argb);
            out.addVertex(m, x + c0 * (r + w), y, z + s0 * (r + w)).setColor(argb);
            out.addVertex(m, x + c1 * (r + w), y, z + s1 * (r + w)).setColor(argb);
            out.addVertex(m, x + c1 * (r - w), y, z + s1 * (r - w)).setColor(argb);
            // and the other way round, so it shows whichever way the render type culls
            out.addVertex(m, x + c1 * (r - w), y, z + s1 * (r - w)).setColor(argb);
            out.addVertex(m, x + c1 * (r + w), y, z + s1 * (r + w)).setColor(argb);
            out.addVertex(m, x + c0 * (r + w), y, z + s0 * (r + w)).setColor(argb);
            out.addVertex(m, x + c0 * (r - w), y, z + s0 * (r - w)).setColor(argb);
        }
    }

    private static int argb(float alpha) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | COLOR;
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
