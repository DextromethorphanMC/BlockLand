package com.blockland.client.graphics.light;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.ReflectionInfo;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.wind.Wind;
import com.blockland.client.render.RenderClock;
import com.blockland.client.render.SceneLighting;
import com.blockland.client.render.WaterRipples;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The {@code BlockLandScene} block of each frame (scene.glsl, rendering spec §2) and which version of it is bound where.
 * Two buffers are written once per frame — the world version (world lighting as set, emission encoding on) and the hand
 * version (lighting off, encoding on) — and a zeroed one stands for "off". A phase binds its version on binding point 15
 * for the lighting pack's vanilla programs (the shadow atlas and the block material map go to their fixed units at the
 * same time); BlockLand's own pipelines get the world version in both the world and the hand phase. A scope's extra level
 * render gets a third copy of the world version without the volumetric clouds flag: the clouds are only marched for the
 * main view, so vanilla's clouds stay in the scope's picture. Buffers are written
 * with {@code glBufferSubData}, which the driver orders against the draws that read the previous contents.
 */
final class SceneUniforms implements SceneLighting.Provider {
    enum Phase { OFF, WORLD, HAND }

    private static final float WATER_WAVES = 1f, WATER_SPEED = 1f;
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final ByteBuffer scratch = ByteBuffer.allocateDirect(SceneLighting.UBO_SIZE).order(ByteOrder.nativeOrder());
    @Nullable
    private GpuBuffer world, hand, scope;
    /** A scope's extra level render is running: the world phase binds {@link #scope}. */
    private boolean extraView;
    private Phase phase = Phase.OFF;
    /** The phase to return to after a scope's extra level render. */
    private Phase beforeExtra = Phase.OFF;
    /** This frame wrote the buffers (an extra level render may only use them then). */
    private boolean written;
    private boolean worldLit;
    @Nullable
    private GpuTextureView shadowView;
    @Nullable
    private GpuTexture shadowTexture;
    @Nullable
    private GpuTexture materialMap;

    // ---- SceneLighting.Provider ------------------------------------------------------------------------------------

    /**
     * BlockLand's own pipelines get the world version in the hand phase too: the viewmodel is drawn then and is lit by the
     * world (only vanilla's hand items, on binding point 15, get the unlit hand version).
     */
    @Override
    public GpuBufferSlice uniforms() {
        return phase != Phase.OFF && world != null && !world.isClosed() ? world.slice() : SceneLighting.off();
    }

    @Override
    public GpuTextureView shadowMap() {
        return shadowView != null && !shadowView.isClosed() ? shadowView : SceneLighting.lit();
    }

    @Override
    public boolean worldLit() {
        return phase == Phase.WORLD && worldLit;
    }

    /** The world phase (third-person weapons) and the hand phase (the viewmodel), when the player wants it. */
    @Override
    public boolean weaponsLit() {
        return phase != Phase.OFF && worldLit && Graphics.settings().weaponWorldLighting;
    }

    @Override
    public void extraWorldView(boolean begin) {
        if (begin) {
            beforeExtra = phase;
            extraView = true;
            if (written) bind(Phase.WORLD);
        } else {
            extraView = false;
            bind(beforeExtra);
        }
    }

    // ---- frame ------------------------------------------------------------------------------------------------------

    /**
     * Writes both versions of this frame's block.
     *
     * @param lit world lighting reaches the world (sun, shadows, lights); off = vanilla's look, emission still encoded
     */
    void write(FrameContext frame, Sky sky, DynamicLights lights, @Nullable ShadowInfo shadows, BlockMaterials materials,
               GpuTexture materialTexture, boolean lit, boolean reflections, boolean refraction, boolean underwater) {
        ensureBuffers();
        worldLit = lit;
        materialMap = materialTexture;
        shadowView = shadows != null ? shadows.atlas() : null;
        shadowTexture = shadowView != null ? shadowView.texture() : null;
        boolean clouds = Graphics.status().cloudsActive;
        ReflectionInfo reflection = lit ? Graphics.reflections() : null;
        reflectionTexture = reflection != null ? reflection.scene() : null;
        int steps = reflection != null ? reflection.steps() : 0;
        // the volumetric fog pass is the haze of the main view; a scope's picture has no such pass and keeps the forward one
        float fog = sky.fog[3] > 0 && Graphics.status().volumetricFogActive ? 2 : sky.fog[3];
        upload(world, frame, sky, lights, shadows, materials, lit ? 1 : 0, lit ? lights.count() : 0, reflections, refraction, underwater,
                clouds, fog, steps);
        upload(hand, frame, sky, lights, shadows, materials, 0, 0, reflections, refraction, underwater, clouds, fog, 0);
        // a scope looks from elsewhere: last frame's picture does not line up with its view
        upload(scope, frame, sky, lights, shadows, materials, lit ? 1 : 0, lit ? lights.count() : 0, reflections, refraction, underwater,
                false, sky.fog[3], 0);
        written = true;
    }

    /** End of a frame (or no frame): the next extra level render must not use stale data. */
    void endFrame() {
        written = false;
        bind(Phase.OFF);
    }

    /** Binds a phase's block, the shadow atlas and the material map for the vanilla programs. */
    void bind(Phase next) {
        phase = next;
        GpuBuffer buffer = switch (next) {
            case WORLD -> extraView ? scope : world;
            case HAND -> hand;
            case OFF -> null;
        };
        if (buffer != null && !buffer.isClosed()) {
            GlSlots.bindScene(buffer);
        } else {
            phase = Phase.OFF;
            GlSlots.bindScene(SceneLighting.off().buffer());
        }
        GlSlots.bindShadow(shadowTexture != null && !shadowTexture.isClosed() ? shadowTexture : SceneLighting.lit().texture());
        if (materialMap != null && !materialMap.isClosed()) GlSlots.bindMaterials(materialMap);
        // nothing on the unit when there are no reflections: the shader does not read it then (BlPrevProj.w = 0)
        if (reflectionTexture != null && !reflectionTexture.isClosed()) GlSlots.bindReflections(reflectionTexture);
        // likewise the wind's plant columns (BlWindStack.w = 0 without them)
        GpuTexture columns = Wind.columns();
        if (columns != null && !columns.isClosed()) GlSlots.bindColumns(columns);
    }

    /** The material map to bind before any frame wrote one (startup, lobby). */
    void setMaterialMap(GpuTexture texture) {
        materialMap = texture;
    }

    Phase phase() {
        return phase;
    }

    private void ensureBuffers() {
        if (world == null || world.isClosed()) {
            world = RenderSystem.getDevice().createBuffer(() -> "BlockLand scene (world)", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    SceneLighting.UBO_SIZE);
        }
        if (hand == null || hand.isClosed()) {
            hand = RenderSystem.getDevice().createBuffer(() -> "BlockLand scene (hand)", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    SceneLighting.UBO_SIZE);
        }
        if (scope == null || scope.isClosed()) {
            scope = RenderSystem.getDevice().createBuffer(() -> "BlockLand scene (scope)", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    SceneLighting.UBO_SIZE);
        }
    }

    /** One version of the block, member by member in scene.glsl's order. */
    private void upload(GpuBuffer target, FrameContext frame, Sky sky, DynamicLights lights, @Nullable ShadowInfo shadows,
                        BlockMaterials materials, float lit, int lightCount, boolean reflections, boolean refraction,
                        boolean underwater, boolean clouds, float fogMode, int reflectionSteps) {
        scratch.clear();
        Std140Builder out = Std140Builder.intoBuffer(scratch);
        float time = (float) ((RenderClock.nanos() / 1e9) % 3600.0);
        out.putVec4(lit, 1, lightCount, time); // BlFlags
        out.putVec4(sky.direction.x, sky.direction.y, sky.direction.z, sky.sun ? 1 : 0); // BlSunDir
        out.putVec4(sky.color[0], sky.color[1], sky.color[2], sky.strength); // BlSunColor
        out.putVec4(sky.shade[0], sky.shade[1], sky.shade[2], sky.shadeShare); // BlAmbient
        out.putVec4(sky.skyColor[0], sky.skyColor[1], sky.skyColor[2], sky.wetness); // BlSkyColor
        out.putVec4(wrap(frame.camX), wrap(frame.camY), wrap(frame.camZ), underwater ? 1 : 0); // BlCamera
        out.putMat4f(frame.viewRotation); // BlViewRotation
        out.putMat4f(frame.projection); // BlProjection
        int cascades = shadows == null ? 0 : Math.min(shadows.count(), SceneLighting.CASCADES);
        for (int c = 0; c < SceneLighting.CASCADES; c++) {
            out.putMat4f(c < cascades ? shadows.cascades()[c] : IDENTITY); // BlShadowMatrix[]
        }
        out.putVec4(split(shadows, 0, cascades), split(shadows, 1, cascades), split(shadows, 2, cascades), split(shadows, 3, cascades));
        if (shadows != null && cascades > 0) {
            out.putVec4(1f / Math.max(1, shadows.atlasSize()), shadows.filterRadius(), shadows.normalOffset(), cascades); // BlShadowParams
        } else {
            out.putVec4(0, 0, 0, 0);
        }
        out.putVec4(WATER_WAVES, WATER_SPEED, reflections ? 1 : 0, refraction ? 1 : 0); // BlWater
        lights.write(out); // BlLights[]
        materials.write(out); // BlMaterialInfo, BlMaterials[]
        out.putVec4(sky.blockLight[0], sky.blockLight[1], sky.blockLight[2], clouds ? 1 : 0); // BlBlockLight
        out.putVec4(sky.fog[0], sky.fog[1], sky.fog[2], fogMode); // BlFog
        float[] c = frame.clouds;
        out.putVec4(c[0], c[1], c[2], c[3]); // BlClouds
        out.putVec4(c[4], c[5], c[6], c[7]); // BlCloudShape
        ripples(out, frame, sky);
        // BlPrevView: camera-relative world now -> last frame's view (its rotation, the camera's move since)
        prevView.set(frame.prevViewRotation).translate((float) (frame.camX - frame.prevCamX), (float) (frame.camY - frame.prevCamY),
                (float) (frame.camZ - frame.prevCamZ));
        out.putMat4f(prevView);
        out.putVec4(frame.prevProjection.m00(), frame.prevProjection.m11(), reflectionSteps, reflectionSteps > 0 ? 1 : 0); // BlPrevProj
        Wind.write(out, frame.camX, frame.camY, frame.camZ, frame.partialTick); // BlWind, BlWindInfo, BlGusts[], BlPushers[], BlSways[]
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), out.get());
    }

    private final float[] rippleData = new float[WaterRipples.MAX * 8];
    private final Matrix4f prevView = new Matrix4f();
    @Nullable
    private GpuTexture reflectionTexture;

    /** BlWeather and BlRipples[]: the live rings, newest last, the rest zero. */
    private void ripples(Std140Builder out, FrameContext frame, Sky sky) {
        ClientLevel level = Minecraft.getInstance().level;
        int[] count = {0};
        if (level != null && Graphics.settings().waterRipples) {
            WaterRipples.forEach(level, frame.camX, frame.camY, frame.camZ, (x, y, z, age, strength, dirX, dirZ) -> {
                int k = count[0]++ * 8;
                rippleData[k] = x;
                rippleData[k + 1] = y;
                rippleData[k + 2] = z;
                rippleData[k + 3] = age;
                rippleData[k + 4] = strength;
                rippleData[k + 5] = dirX;
                rippleData[k + 6] = dirZ;
                rippleData[k + 7] = 0;
            });
        }
        out.putVec4(sky.weather[0], sky.weather[1], sky.weather[2], count[0]); // BlWeather
        for (int i = 0; i < WaterRipples.MAX; i++) {
            if (i < count[0]) {
                int k = i * 8;
                out.putVec4(rippleData[k], rippleData[k + 1], rippleData[k + 2], rippleData[k + 3]);
                out.putVec4(rippleData[k + 4], rippleData[k + 5], rippleData[k + 6], rippleData[k + 7]);
            } else {
                out.putVec4(0, 0, 0, 0);
                out.putVec4(0, 0, 0, 0);
            }
        }
    }

    private static float split(@Nullable ShadowInfo shadows, int cascade, int count) {
        return shadows == null || cascade >= count ? 0 : shadows.splits()[cascade];
    }

    /** World position wrapped to 0..2048 (float precision for world-anchored noise). */
    private static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 2048.0) * 2048.0);
    }
}
