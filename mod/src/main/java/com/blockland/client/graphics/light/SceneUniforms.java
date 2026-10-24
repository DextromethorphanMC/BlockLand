package com.blockland.client.graphics.light;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.render.SceneLighting;
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
 * same time); BlockLand's own pipelines get the world version in both the world and the hand phase. Buffers are written
 * with {@code glBufferSubData}, which the driver orders against the draws that read the previous contents.
 */
final class SceneUniforms implements SceneLighting.Provider {
    enum Phase { OFF, WORLD, HAND }

    private static final float WATER_WAVES = 1f, WATER_SPEED = 1f;
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final ByteBuffer scratch = ByteBuffer.allocateDirect(SceneLighting.UBO_SIZE).order(ByteOrder.nativeOrder());
    @Nullable
    private GpuBuffer world, hand;
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
            if (written) bind(Phase.WORLD);
        } else {
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
        upload(world, frame, sky, lights, shadows, materials, lit ? 1 : 0, lit ? lights.count() : 0, reflections, refraction, underwater);
        upload(hand, frame, sky, lights, shadows, materials, 0, 0, reflections, refraction, underwater);
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
            case WORLD -> world;
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
    }

    /** One version of the block, member by member in scene.glsl's order. */
    private void upload(GpuBuffer target, FrameContext frame, Sky sky, DynamicLights lights, @Nullable ShadowInfo shadows,
                        BlockMaterials materials, float lit, int lightCount, boolean reflections, boolean refraction,
                        boolean underwater) {
        scratch.clear();
        Std140Builder out = Std140Builder.intoBuffer(scratch);
        float time = (float) ((System.nanoTime() / 1e9) % 3600.0);
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
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), out.get());
    }

    private static float split(@Nullable ShadowInfo shadows, int cascade, int count) {
        return shadows == null || cascade >= count ? 0 : shadows.splits()[cascade];
    }

    /** World position wrapped to 0..2048 (float precision for world-anchored noise). */
    private static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 2048.0) * 2048.0);
    }
}
