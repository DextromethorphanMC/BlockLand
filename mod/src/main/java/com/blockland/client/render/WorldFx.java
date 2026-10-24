package com.blockland.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * BlockLand's world effects inside vanilla's particle pass, in a fixed order (rendering spec §1): one {@code CUSTOM}
 * particle draws every registered layer, surfaces first, then what floats in front of them. Each effect module drawing
 * from a particle of its own would draw in the order its particle happened to be spawned — dust hit before the wall's
 * first bullet hole would then never cover the holes. A drawer gets the particle pass's pose (camera-relative world),
 * its buffer source and the camera; what it puts into the buffer source is drawn at the end of the pass unless it ends
 * its own batches. Render thread only.
 */
public final class WorldFx {
    /** Draw order, first to last. */
    public enum Layer {
        /** Painted on the world's faces (bullet holes, graffiti): under everything that floats in front of them. */
        SURFACE,
        /** Volumes and pieces in the air (impact dust and debris, explosion smoke). */
        VOLUME
    }

    @FunctionalInterface
    public interface Drawer {
        void draw(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick);
    }

    private static final Map<Layer, List<Drawer>> drawers = new EnumMap<>(Layer.class);
    @Nullable
    private static Pass pass;

    private WorldFx() {
    }

    /** Client setup: adds a drawer to its layer (drawn every frame while the level has its pass; empty drawers return). */
    public static void register(Layer layer, Drawer drawer) {
        drawers.computeIfAbsent(layer, l -> new ArrayList<>()).add(drawer);
    }

    /** Something to draw appeared in {@code level}: makes sure the pass's particle lives there. */
    public static void wake(ClientLevel level) {
        if (pass == null || !pass.isAlive() || pass.level() != level) {
            if (pass != null) pass.remove();
            pass = new Pass(level);
            Minecraft.getInstance().particleEngine.add(pass);
        }
    }

    private static final class Pass extends Particle {
        Pass(ClientLevel level) {
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
            if (Minecraft.getInstance().level != level) remove();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            for (Layer layer : Layer.values()) {
                List<Drawer> list = drawers.get(layer);
                if (list == null) continue;
                for (Drawer drawer : list) drawer.draw(pose, buffers, camera, partialTick);
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
