package com.blockland.client.parachute;

import com.blockland.BlockLand;
import com.blockland.client.render.RenderClock;
import com.blockland.parachute.ParachuteItem;
import com.blockland.parachute.ParachuteRules;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Draws parachutes on players: the packed parachute on the back of anyone wearing one, and an open canopy — a ram-air
 * wing curving over its span, its cells bulging between the ribs and open at the front, the trailing edge fluttering —
 * flying {@code canopy.height} above the harness on its lines and risers. Opening, the canopy rises out of the pack and
 * spreads ({@link ClientParachute#amount}); closing, it folds back down. Other players (and the local one in third
 * person) are drawn after their model; the local player's own canopy in first person is drawn in the world pass, so
 * looking up shows it. One texture ({@code textures/entity/parachute.png}): canopy colours, lines, pack and webbing.
 */
final class CanopyRenderer {
    private static final ResourceLocation TEXTURE = BlockLand.id("textures/entity/parachute.png");
    /** Texture regions (pixels of the 64² texture). */
    private static final float TEX = 64f;
    /** Chord samples of a cell (front to back). */
    private static final int CHORD = 6;
    /** Where the lines meet above the shoulders (blocks above the feet, sideways). */
    private static final float RISER_Y = 2.05f, RISER_X = 0.24f, SHOULDER_Y = 1.42f, SHOULDER_X = 0.2f;

    @Nullable
    private static FirstPerson batch;

    private CanopyRenderer() {
    }

    static void init() {
        RenderPlayerEvent.Post.BUS.addListener(CanopyRenderer::afterPlayer);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
    }

    private static void afterPlayer(RenderPlayerEvent.Post event) {
        PlayerRenderState state = event.getState();
        if (state.isInvisible) return;
        boolean wearing = state.chestEquipment.getItem() instanceof ParachuteItem;
        float a = ClientParachute.amount(state.id, RenderClock.nanos());
        if (!wearing && a <= 0) return;
        draw(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), state.bodyRot, state.scale,
                state.isCrouching, wearing, a, RenderClock.nanos() / 1e9 + state.id * 0.37, false);
    }

    /** The local canopy in first person: kept in the particle pass while it is shown. */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel level) || mc.player == null) return;
        boolean shown = mc.options.getCameraType().isFirstPerson() && ClientParachute.amount(RenderClock.nanos()) > 0;
        if (shown && (batch == null || !batch.isAlive() || batch.level() != level)) {
            batch = new FirstPerson(level);
            mc.particleEngine.add(batch);
        }
    }

    /**
     * Everything at a player's feet ({@code pose} there, world axes): turned with the body so local −Z is forward and
     * +X the player's right.
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, int light, float bodyYaw, float scale, boolean crouching,
                     boolean wearing, float amount, double time, boolean firstPerson) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180 - bodyYaw));
        pose.scale(scale, scale, scale);
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        if (wearing && !firstPerson) pack(pose, out, light, crouching, amount);
        if (amount > 0) canopy(pose, out, light, amount, time);
        pose.popPose();
    }

    // ---- the pack -------------------------------------------------------------------------------------

    private static void pack(PoseStack pose, VertexConsumer out, int light, boolean crouching, float amount) {
        pose.pushPose();
        if (crouching) {
            // the torso leans forward about the neck like vanilla's crouch
            pose.translate(0, 1.25 - 0.2, 0);
            pose.mulPose(Axis.XP.rotation(-0.5f));
            pose.translate(0, -1.25, 0);
        }
        // the container on the back (opened: its flap hangs loose), the two shoulder straps and the chest strap
        float flap = amount > 0 ? 0.06f : 0;
        box(pose, out, light, -0.23f, 0.80f, 0.14f, 0.23f, 1.36f - flap, 0.34f, 0, 56, 16, 8);
        box(pose, out, light, -0.21f, 1.36f - flap, 0.15f, 0.21f, 1.42f, 0.30f, 0, 56, 16, 8);
        for (float x : new float[]{-0.17f, 0.11f}) {
            box(pose, out, light, x, 0.82f, -0.165f, x + 0.06f, 1.44f, -0.135f, 16, 56, 4, 8);
            box(pose, out, light, x, 1.42f, -0.165f, x + 0.06f, 1.46f, 0.16f, 16, 56, 4, 8);
        }
        box(pose, out, light, -0.17f, 1.08f, -0.17f, 0.17f, 1.12f, -0.14f, 16, 56, 4, 8);
        // the red ripcord handle on the right strap
        box(pose, out, light, 0.11f, 1.0f, -0.19f, 0.17f, 1.05f, -0.16f, 24, 56, 4, 4);
        pose.popPose();
    }

    // ---- the canopy -----------------------------------------------------------------------------------

    private static void canopy(PoseStack pose, VertexConsumer out, int light, float amount, double time) {
        ParachuteRules.Canopy c = ParachuteRules.get().canopy;
        int cells = Math.max(3, c.cells);
        float rise = smooth(amount), spread = Mth.clamp(amount * 1.15f, 0, 1);
        spread = 1 - (1 - spread) * (1 - spread);
        float span = c.span * (0.25f + 0.75f * spread);
        float arc = (float) Math.toRadians(c.arc) * (0.55f + 0.45f * spread);
        float radius = span / Math.max(0.1f, arc);
        float top = RISER_Y - 0.4f + c.height * (0.2f + 0.8f * rise);
        float chord = c.chord * (0.4f + 0.6f * spread);
        float thick = c.thickness * spread;
        // collapsing or still filling, the fabric flaps harder
        float flutter = 0.035f + 0.12f * (1 - amount);

        int ribs = cells * 2 + 1;  // ribs and the cell middles between them
        float[][][] up = new float[ribs][CHORD][3], lo = new float[ribs][CHORD][3];
        float[][] nUp = new float[ribs][3];
        for (int r = 0; r < ribs; r++) {
            float theta = -arc / 2 + arc * r / (ribs - 1);
            float sin = Mth.sin(theta), cos = Mth.cos(theta);
            float bx = radius * sin, by = top - radius * (1 - cos);
            boolean middle = (r & 1) == 1;
            nUp[r][0] = sin;
            nUp[r][1] = cos;
            for (int j = 0; j < CHORD; j++) {
                float u = j / (CHORD - 1f);
                float z = (-0.45f + u) * chord;
                float wave = flutter * Mth.sin((float) (time * 9.0 + r * 0.9 + u * 3.0)) * u * u;
                float upper = thick * (0.75f + 0.25f * Mth.sin((float) Math.PI * Math.min(1, u * 2) / 2)) * (float) Math.pow(1 - u, 0.7);
                if (middle) upper += thick * 0.22f * (1 - u * 0.6f);
                float lower = -thick * 0.3f * (1 - u) + (middle ? -thick * 0.08f * (1 - u) : 0);
                set(up[r][j], bx + sin * (upper + wave), by + cos * (upper + wave), z);
                set(lo[r][j], bx + sin * (lower + wave), by + cos * (lower + wave), z);
            }
        }
        Matrix4f m = pose.last().pose();
        PoseStack.Pose normals = pose.last();
        for (int r = 0; r + 1 < ribs; r++) {
            int cell = r / 2;
            // alternating colours per cell: the texture's left half and right half
            float u0 = (cell & 1) == 0 ? 0 : 32, u1 = u0 + 32;
            float cu0 = u0 + ((r & 1) == 0 ? 0 : 16), cu1 = cu0 + 16;
            for (int j = 0; j + 1 < CHORD; j++) {
                float v0 = j * 32f / (CHORD - 1), v1 = (j + 1) * 32f / (CHORD - 1);
                float nx = (nUp[r][0] + nUp[r + 1][0]) / 2, ny = (nUp[r][1] + nUp[r + 1][1]) / 2;
                quad(out, m, normals, light, up[r][j], up[r + 1][j], up[r + 1][j + 1], up[r][j + 1], cu0, v0, cu1, v1, nx, ny, 0);
                float lv0 = 32 + j * 16f / (CHORD - 1), lv1 = 32 + (j + 1) * 16f / (CHORD - 1);
                quad(out, m, normals, light, lo[r][j + 1], lo[r + 1][j + 1], lo[r + 1][j], lo[r][j], cu0, lv0, cu1, lv1, -nx, -ny, 0);
            }
            // the open cell mouths along the leading edge (the dark inside of the wing)
            quad(out, m, normals, light, lo[r][0], lo[r + 1][0], up[r + 1][0], up[r][0], u0, 46, u1, 48, 0, 0, -1);
        }
        // the closed wing tips
        for (int r : new int[]{0, ribs - 1}) {
            float side = r == 0 ? -1 : 1;
            for (int j = 0; j + 1 < CHORD; j++) {
                quad(out, m, normals, light, lo[r][j], lo[r][j + 1], up[r][j + 1], up[r][j], 0, 32, 32, 48, side, 0, 0);
            }
        }
        // the lines: from the ribs (front and back attachment rows) down to the risers, then the risers to the shoulders
        for (int r = 0; r < ribs; r += 2) {
            float side = r < ribs / 2 ? -1 : 1;
            float[] riser = {side * RISER_X, RISER_Y, 0.02f};
            line(out, m, normals, light, lo[r][1], riser, 0.008f);
            line(out, m, normals, light, lo[r][CHORD - 2], riser, 0.008f);
        }
        for (float side : new float[]{-1, 1}) {
            line(out, m, normals, light, new float[]{side * RISER_X, RISER_Y, 0.02f}, new float[]{side * SHOULDER_X, SHOULDER_Y, 0.06f}, 0.02f);
        }
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void set(float[] p, float x, float y, float z) {
        p[0] = x;
        p[1] = y;
        p[2] = z;
    }

    // ---- primitives -----------------------------------------------------------------------------------

    /** A line as two crossed thin quads (seen from any side), in the line colour. */
    private static void line(VertexConsumer out, Matrix4f m, PoseStack.Pose normals, int light, float[] a, float[] b, float w) {
        float[] a1 = {a[0] - w, a[1], a[2]}, a2 = {a[0] + w, a[1], a[2]}, b1 = {b[0] - w, b[1], b[2]}, b2 = {b[0] + w, b[1], b[2]};
        quad(out, m, normals, light, a1, a2, b2, b1, 0, 48, 8, 56, 0, 0, 1);
        float[] a3 = {a[0], a[1], a[2] - w}, a4 = {a[0], a[1], a[2] + w}, b3 = {b[0], b[1], b[2] - w}, b4 = {b[0], b[1], b[2] + w};
        quad(out, m, normals, light, a3, a4, b4, b3, 0, 48, 8, 56, 1, 0, 0);
    }

    /** An axis-aligned box textured with one texture region on every face. */
    private static void box(PoseStack pose, VertexConsumer out, int light, float x0, float y0, float z0, float x1, float y1, float z1,
                            float tu, float tv, float tw, float th) {
        Matrix4f m = pose.last().pose();
        PoseStack.Pose n = pose.last();
        float u0 = tu, v0 = tv, u1 = tu + tw, v1 = tv + th;
        quad(out, m, n, light, p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), u0, v1, u1, v0, 0, 0, 1);
        quad(out, m, n, light, p(x1, y0, z0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), u0, v1, u1, v0, 0, 0, -1);
        quad(out, m, n, light, p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), u0, v1, u1, v0, -1, 0, 0);
        quad(out, m, n, light, p(x1, y0, z1), p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), u0, v1, u1, v0, 1, 0, 0);
        quad(out, m, n, light, p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0), p(x0, y1, z0), u0, v1, u1, v0, 0, 1, 0);
        quad(out, m, n, light, p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1), u0, v1, u1, v0, 0, -1, 0);
    }

    private static float[] p(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    /** Corners a → b → c → d; texture u0…u1 along a→b, v0 at a/b, v1 at c/d (texture pixels). */
    private static void quad(VertexConsumer out, Matrix4f m, PoseStack.Pose normals, int light, float[] a, float[] b, float[] c, float[] d,
                             float u0, float v0, float u1, float v1, float nx, float ny, float nz) {
        vertex(out, m, normals, light, a, u0, v0, nx, ny, nz);
        vertex(out, m, normals, light, b, u1, v0, nx, ny, nz);
        vertex(out, m, normals, light, c, u1, v1, nx, ny, nz);
        vertex(out, m, normals, light, d, u0, v1, nx, ny, nz);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, PoseStack.Pose normals, int light, float[] p, float u, float v,
                               float nx, float ny, float nz) {
        out.addVertex(m, p[0], p[1], p[2]).setColor(0xFFFFFFFF).setUv(u / TEX, v / TEX).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(normals, nx, ny, nz);
    }

    // ---- first person -----------------------------------------------------------------------------------

    /** The local player's canopy seen from inside the body: drawn from the particle pass at the player's feet. */
    private static final class FirstPerson extends Particle {
        private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

        FirstPerson(ClientLevel level) {
            super(level, 0, 0, 0);
            this.lifetime = Integer.MAX_VALUE;
            this.hasPhysics = false;
            this.gravity = 0;
        }

        ClientLevel level() {
            return level;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != level || !mc.options.getCameraType().isFirstPerson() || ClientParachute.amount(RenderClock.nanos()) <= 0) remove();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            float a = ClientParachute.amount(RenderClock.nanos());
            if (player == null || a <= 0 || camera.getEntity() != player || camera.isDetached()) return;
            Vec3 cam = camera.getPosition();
            double x = Mth.lerp(partialTick, player.xo, player.getX()), y = Mth.lerp(partialTick, player.yo, player.getY()),
                    z = Mth.lerp(partialTick, player.zo, player.getZ());
            lightPos.set(x, y + ParachuteRules.get().canopy.height + 1.5, z);
            int light = LevelRenderer.getLightColor(level, lightPos);
            pose.pushPose();
            pose.translate(x - cam.x, y - cam.y, z - cam.z);
            draw(pose, buffers, light, Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot), player.getScale(), false,
                    false, a, RenderClock.nanos() / 1e9 + player.getId() * 0.37, true);
            pose.popPose();
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
