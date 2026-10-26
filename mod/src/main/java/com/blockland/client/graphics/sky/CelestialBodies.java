package com.blockland.client.graphics.sky;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.shadow.SunPath;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The sun and the moon drawn by BlockLand's shader (rendering spec §14) in place of vanilla's textures, in vanilla's own
 * sky batch (so everything vanilla draws over its sun — clouds, rain, the sunrise fan's order — stays as it is). Both sit
 * on {@link SunPath}, where the shadows come from (vanilla's sky turns its sun on an untilted arc); the sun is a
 * limb-darkened disc with a corona whose colour follows the air it shines through, the moon a sphere lit in vanilla's
 * phase with earthshine on its dark side and a faint halo. Each is one quad with its parameters in the vertex colour and
 * a uv range telling the shader which body it draws ({@code blockland:core/celestial}); the discs cover the stars behind
 * them (premultiplied alpha). Render thread, called from the sky renderer through {@code mixin/level}.
 */
public final class CelestialBodies {
    /** Angular half-size of each quad (the disc and its glow) and of the discs, in degrees (the shader's DISC fractions). */
    private static final float SUN_QUAD = 10f, MOON_QUAD = 4.4f;
    private static final float DISTANCE = 100f;

    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(BlockLand.id("pipeline/celestial"))
            .withVertexShader(BlockLand.id("core/celestial"))
            .withFragmentShader(BlockLand.id("core/celestial"))
            .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA))
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .build();
    private static final RenderType TYPE = RenderType.create("blockland_celestial", 1536, false, false, PIPELINE,
            RenderType.CompositeState.builder().createCompositeState(false));

    /** The sky's pose before vanilla turns it onto its sun arc, captured for this sky. */
    private static final Matrix4f base = new Matrix4f();
    private static int moonPhase;
    private static boolean captured;

    private CelestialBodies() {
    }

    /** Whether the shader draws the sun and the moon this frame (else vanilla does). */
    public static boolean active() {
        GraphicsSettings settings = Graphics.settings();
        return settings.enabled && settings.shaderSunMoon && Minecraft.getInstance().level != null;
    }

    /** {@code SkyRenderer.renderSunMoonAndStars} begins: the sky's pose and the moon's phase. */
    public static void begin(Matrix4f pose, int phase) {
        base.set(pose);
        moonPhase = phase;
        captured = true;
    }

    /** In place of vanilla's sun ({@code alpha}: vanilla's, 1 − rain); false when vanilla should draw it. */
    public static boolean sun(float alpha, MultiBufferSource buffers) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!captured || !active() || level == null) return false;
        Vector3f dir = SunPath.sun(level, partialTick(), new Vector3f());
        // the air the light crosses: thin overhead, many times thicker at the horizon, blue scattered out first
        float e = Math.max(dir.y, -0.05f);
        float air = (float) (1 / (Math.max(e, 0) + 0.025 * Math.exp(-11 * Math.max(e, 0))));
        air = Math.min(air, 40);
        float r = (float) Math.exp(-0.021 * air), g = (float) Math.exp(-0.052 * air), b = (float) Math.exp(-0.120 * air);
        float peak = Math.max(r, Math.max(g, b));
        quad(buffers, dir, SUN_QUAD, 0, r / peak, g / peak, b / peak, alpha);
        return true;
    }

    /** In place of vanilla's moon, opposite the sun. */
    public static boolean moon(float alpha, MultiBufferSource buffers) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!captured || !active() || level == null) return false;
        Vector3f dir = SunPath.sun(level, partialTick(), new Vector3f()).negate();
        quad(buffers, dir, MOON_QUAD, 4, (moonPhase & 7) / 8f, 0, 0, alpha);
        return true;
    }

    /** {@code renderSunMoonAndStars} ends: the pose is the next sky's to capture. */
    public static void end() {
        captured = false;
    }

    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** A quad facing the camera at {@code dir}, uv −1..1 offset by {@code uvShift} on x (the shader's body id). */
    private static void quad(MultiBufferSource buffers, Vector3f dir, float halfDegrees, float uvShift, float r, float g, float b, float a) {
        Vector3f up = Math.abs(dir.y) > 0.999f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f e1 = new Vector3f(dir).cross(up).normalize();
        Vector3f e2 = new Vector3f(e1).cross(dir).normalize();
        float h = (float) Math.tan(Math.toRadians(halfDegrees)) * DISTANCE;
        Vector3f c = new Vector3f(dir).mul(DISTANCE);
        VertexConsumer v = buffers.getBuffer(TYPE);
        corner(v, c, e1, e2, -h, -h, uvShift - 1, -1, r, g, b, a);
        corner(v, c, e1, e2, h, -h, uvShift + 1, -1, r, g, b, a);
        corner(v, c, e1, e2, h, h, uvShift + 1, 1, r, g, b, a);
        corner(v, c, e1, e2, -h, h, uvShift - 1, 1, r, g, b, a);
    }

    private static void corner(VertexConsumer v, Vector3f c, Vector3f e1, Vector3f e2, float s, float t, float u, float w, float r,
                               float g, float b, float a) {
        v.addVertex(base, c.x + e1.x * s + e2.x * t, c.y + e1.y * s + e2.y * t, c.z + e1.z * s + e2.z * t).setUv(u, w).setColor(r, g, b, a);
    }
}
