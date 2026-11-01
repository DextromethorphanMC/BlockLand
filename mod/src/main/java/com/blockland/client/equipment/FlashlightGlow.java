package com.blockland.client.equipment;

import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentData;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * A switched-on flashlight seen from outside (asset spec §36): a soft glow on its lens and a faint beam fading out a few
 * blocks ahead, following the holder's hand and view — the light itself is the spot of {@code graphics/item_lights.json}
 * (and the volumetric haze shows its beam where there is haze). Not drawn for this client's own first-person view,
 * where the viewmodel's lens glows.
 */
final class FlashlightGlow {
    private static final double RANGE = 96;
    private static final int RGB = 0xFFF1DC;
    private static final float BEAM_LENGTH = 4.5f;
    private static final Map<Integer, Glow> glows = new HashMap<>();

    private FlashlightGlow() {
    }

    /** Every client tick: a glow for each player near the camera holding a lit flashlight. */
    static void tick(ClientLevel level, Player self) {
        Minecraft mc = Minecraft.getInstance();
        glows.values().removeIf(g -> !g.isAlive());
        for (Player player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(self) > RANGE * RANGE || litHand(player) == null) continue;
            Glow glow = glows.get(player.getId());
            if (glow == null || !glow.isAlive()) {
                glow = new Glow(level, player);
                glows.put(player.getId(), glow);
                mc.particleEngine.add(glow);
            }
        }
    }

    static void clear() {
        glows.clear();
    }

    /** The hand holding a switched-on flashlight, or null. */
    @Nullable
    static InteractionHand litHand(LivingEntity entity) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = entity.getItemInHand(hand);
            if (EquipmentContent.isLit(stack)
                    && EquipmentContent.equipmentOf(stack).map(EquipmentData.Equipment::toggles).orElse(false)) return hand;
        }
        return null;
    }

    /** One holder's glow: alive while they hold it lit; placed every frame from their hand and view. */
    private static final class Glow extends Particle {
        private final LivingEntity holder;

        Glow(ClientLevel level, LivingEntity holder) {
            super(level, holder.getX(), holder.getY(), holder.getZ());
            this.holder = holder;
            this.lifetime = Integer.MAX_VALUE;
            this.hasPhysics = false;
            this.gravity = 0;
        }

        @Override
        public void tick() {
            if (holder.isRemoved() || holder.level() != level || litHand(holder) == null) remove();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            InteractionHand hand = litHand(holder);
            if (hand == null) return;
            // this client's own first-person view has the viewmodel's lens instead
            if (camera.getEntity() == holder && !camera.isDetached()) return;
            boolean right = (hand == InteractionHand.MAIN_HAND) == (holder.getMainArm() == HumanoidArm.RIGHT);
            Vec3 view = holder.getViewVector(partialTick);
            Vec3 at = holder.getPosition(partialTick);
            double yaw = Math.toRadians(Mth.rotLerp(partialTick, holder.yBodyRotO, holder.yBodyRot));
            double scale = holder.getScale(), side = right ? 1 : -1;
            Vec3 lens = new Vec3(at.x - Math.cos(yaw) * 0.36 * side * scale + view.x * 0.6 * scale,
                    at.y + holder.getEyeHeight() - 0.38 * scale + view.y * 0.6 * scale,
                    at.z - Math.sin(yaw) * 0.36 * side * scale + view.z * 0.6 * scale);
            Vec3 cam = camera.getPosition();
            VertexConsumer out = buffers.getBuffer(RenderType.lightning());
            Matrix4f m = pose.last().pose();
            // facing the camera: how much of the lens it sees (a flashlight pointed at you glares)
            Vec3 toCamera = cam.subtract(lens).normalize();
            float facing = (float) Math.max(0, view.dot(toCamera));
            disc(out, m, lens.subtract(cam), toCamera, 0.12f + 0.35f * facing * facing, 0.55f + 0.45f * facing);
            beam(out, m, lens, view, cam);
        }

        /** A round glow facing the camera: bright in the middle, nothing at its rim. */
        private static void disc(VertexConsumer out, Matrix4f m, Vec3 centre, Vec3 normal, float radius, float alpha) {
            Vec3 a = Math.abs(normal.y) < 0.95 ? normal.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0);
            Vec3 b = normal.cross(a).normalize();
            int segments = 12;
            for (int i = 0; i < segments; i++) {
                double t0 = 2 * Math.PI * i / segments, t1 = 2 * Math.PI * (i + 1) / segments;
                Vec3 p0 = centre.add(a.scale(Math.cos(t0) * radius)).add(b.scale(Math.sin(t0) * radius));
                Vec3 p1 = centre.add(a.scale(Math.cos(t1) * radius)).add(b.scale(Math.sin(t1) * radius));
                vertex(out, m, centre, argb(alpha, RGB));
                vertex(out, m, p0, argb(0, RGB));
                vertex(out, m, p1, argb(0, RGB));
                vertex(out, m, centre, argb(alpha, RGB));
            }
        }

        /** The beam: a ribbon turned to the camera, widening and fading out along the view. */
        private static void beam(VertexConsumer out, Matrix4f m, Vec3 lens, Vec3 dir, Vec3 cam) {
            Vec3 end = lens.add(dir.scale(BEAM_LENGTH));
            Vec3 side = dir.cross(lens.add(end).scale(0.5).subtract(cam)).normalize();
            if (!Double.isFinite(side.x)) return;
            Vec3 a = lens.subtract(cam), b = end.subtract(cam);
            float w0 = 0.06f, w1 = 0.9f;
            vertex(out, m, a.add(side.scale(w0)), argb(0.16f, RGB));
            vertex(out, m, a.subtract(side.scale(w0)), argb(0.16f, RGB));
            vertex(out, m, b.subtract(side.scale(w1)), argb(0, RGB));
            vertex(out, m, b.add(side.scale(w1)), argb(0, RGB));
        }

        private static void vertex(VertexConsumer out, Matrix4f m, Vec3 p, int argb) {
            out.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(argb);
        }

        private static int argb(float a, int rgb) {
            return (Mth.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
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
