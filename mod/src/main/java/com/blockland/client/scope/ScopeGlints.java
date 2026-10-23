package com.blockland.client.scope;

import com.blockland.craft.CraftData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Scope glint (like COD's): a player who raises a magnified scope shows a bright star at its objective to everyone the
 * scope points at — brightest straight down the barrel, gone beyond about 40° — so a camping sniper can be spotted and
 * countered. Who is aiming comes from the server ({@code S2CAim}, sent as aiming starts and stops and when a player comes
 * into view); which optic they hold is read from their held item (its build is synced). Drawn as an additive particle,
 * so blocks hide it and it also shows, magnified, in the viewer's own scope.
 */
final class ScopeGlints {
    /** Glints show within this angle of the scope's line of sight (cosine). */
    private static final float CONE = (float) Math.cos(Math.toRadians(40));
    /** ... at full strength within this one. */
    private static final float CORE = (float) Math.cos(Math.toRadians(5));

    private static final Map<Integer, Glint> glints = new HashMap<>();
    private static Function<Player, CraftData.Attachment> sightOf = player -> null;

    private ScopeGlints() {
    }

    static void init() {
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        ClientPlayerNetworkEvent.Clone.BUS.addListener(event -> clear());
    }

    static void setSightOf(Function<Player, CraftData.Attachment> resolver) {
        sightOf = resolver;
    }

    static void onAim(int playerId, boolean aiming) {
        Glint glint = glints.remove(playerId);
        if (glint != null) glint.remove();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!aiming || level == null) return;
        Entity entity = level.getEntity(playerId);
        if (!(entity instanceof Player player) || player == mc.player) return;
        Glint created = new Glint(level, player);
        glints.put(playerId, created);
        mc.particleEngine.add(created);
    }

    private static void clear() {
        glints.values().forEach(Particle::remove);
        glints.clear();
    }

    /** One aiming player's glint, following their eyes. */
    private static final class Glint extends Particle {
        private final Player owner;

        Glint(ClientLevel level, Player owner) {
            super(level, owner.getX(), owner.getEyeY(), owner.getZ());
            this.owner = owner;
            this.lifetime = Integer.MAX_VALUE;
            this.hasPhysics = false;
        }

        @Override
        public void tick() {
            if (owner.isRemoved() || !owner.isAlive() || owner.isSpectator() || owner.level() != level) {
                remove();
                glints.remove(owner.getId(), this);
                return;
            }
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
            CraftData.Attachment optic = sightOf.apply(owner);
            if (optic == null || !optic.isScope() || owner.isInvisible()) return;
            float strength = optic.scope.glintStrength();
            if (strength <= 0.01f) return;
            Vec3 look = owner.getViewVector(partialTick);
            Vec3 objective = owner.getEyePosition(partialTick).add(look.scale(0.32)).add(0, -0.03, 0);
            Vec3 toViewer = camera.getPosition().subtract(objective);
            double distance = toViewer.length();
            if (distance < 1.5) return;
            float facing = (float) look.dot(toViewer.scale(1 / distance));
            if (facing <= CONE) return;
            float k = Scopes.smooth((facing - CONE) / (CORE - CONE));
            float daylight = level.getSkyDarken(partialTick);
            float twinkle = 0.85f + 0.15f * (float) Math.sin((owner.tickCount + partialTick) * 1.3 + owner.getId());
            float brightness = strength * k * (0.55f + 0.45f * daylight) * twinkle;
            // a constant apparent size (about half a degree), so the glint reads at range
            float size = (float) Math.max(0.12, distance * 0.009) * (0.6f + 0.4f * k);
            Vec3 at = objective.subtract(camera.getPosition());
            Matrix4f m = pose.last().pose();
            VertexConsumer out = buffers.getBuffer(RenderType.lightning());
            Vector3f right = camera.getLeftVector().negate(new Vector3f());
            Vector3f up = new Vector3f(camera.getUpVector());
            int core = argb(Math.min(1, brightness), 1.0f, 0.96f, 0.88f);
            int clear = argb(0, 1.0f, 0.96f, 0.88f);
            glow(out, m, at, right, up, size * 0.55f, core, clear);
            // the star's spikes: thin streaks across and up
            streak(out, m, at, right, up, size * 2.4f, size * 0.07f, argb(Math.min(1, brightness * 0.8f), 1f, 0.97f, 0.9f), clear);
            streak(out, m, at, up, right, size * 1.5f, size * 0.06f, argb(Math.min(1, brightness * 0.6f), 1f, 0.97f, 0.9f), clear);
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

    /** A round glow: bright in the middle, clear at its rim (a fan of quads with a collapsed centre corner). */
    static void glow(VertexConsumer out, Matrix4f m, Vec3 at, Vector3f right, Vector3f up, float radius, int centre, int rim) {
        int segments = 16;
        float cx = (float) at.x, cy = (float) at.y, cz = (float) at.z;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            out.addVertex(m, cx, cy, cz).setColor(centre);
            out.addVertex(m, cx, cy, cz).setColor(centre);
            out.addVertex(m, cx + right.x * c1 + up.x * s1, cy + right.y * c1 + up.y * s1, cz + right.z * c1 + up.z * s1).setColor(rim);
            out.addVertex(m, cx + right.x * c0 + up.x * s0, cy + right.y * c0 + up.y * s0, cz + right.z * c0 + up.z * s0).setColor(rim);
        }
    }

    /** A thin streak along {@code along}, bright in the middle and fading towards both tips. */
    private static void streak(VertexConsumer out, Matrix4f m, Vec3 at, Vector3f along, Vector3f across, float length, float width,
                               int centre, int tip) {
        float cx = (float) at.x, cy = (float) at.y, cz = (float) at.z;
        for (int side = -1; side <= 1; side += 2) {
            float tx = cx + along.x * length * side, ty = cy + along.y * length * side, tz = cz + along.z * length * side;
            out.addVertex(m, cx + across.x * width, cy + across.y * width, cz + across.z * width).setColor(centre);
            out.addVertex(m, cx - across.x * width, cy - across.y * width, cz - across.z * width).setColor(centre);
            out.addVertex(m, tx, ty, tz).setColor(tip);
            out.addVertex(m, tx, ty, tz).setColor(tip);
        }
    }

    static int argb(float a, float r, float g, float b) {
        return (Math.round(a * 255) & 0xFF) << 24 | (Math.round(r * 255) & 0xFF) << 16 | (Math.round(g * 255) & 0xFF) << 8 | (Math.round(b * 255) & 0xFF);
    }
}
