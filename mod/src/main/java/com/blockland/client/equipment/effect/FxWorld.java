package com.blockland.client.equipment.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The live equipment effects in the world (blasts, smoke clouds, fire patches): ticked with the client, drawn in the
 * particle pass (world matrices, depth of the solid world) through one batch particle, cleared on logout.
 */
final class FxWorld {
    /** One effect in the world. */
    interface Fx {
        /** Client tick; false when the effect is over. */
        boolean tick(ClientLevel level);

        void render(FxDraw draw);

        /** The thrown entity that carries it (smoke, fire), -1 for effects that carry on alone (blasts). */
        default int entityId() {
            return -1;
        }
    }

    private static final List<Fx> effects = new ArrayList<>();
    @Nullable
    private static ClientLevel level;
    @Nullable
    private static Batch batch;

    private FxWorld() {
    }

    static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && !effects.isEmpty()) {
                event.getText().add("[BlockLand] equipment effects: " + effects.size());
            }
            return false;
        });
    }

    static void add(Fx fx) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) return;
        if (world != level) clear();
        level = world;
        effects.add(fx);
        if (batch == null || !batch.isAlive()) {
            batch = new Batch(world);
            Minecraft.getInstance().particleEngine.add(batch);
        }
    }

    /** The effect of this type carried by the entity, or null. */
    @Nullable
    static <T extends Fx> T find(int entityId, Class<T> type) {
        for (Fx fx : effects) {
            if (fx.entityId() == entityId && type.isInstance(fx)) return type.cast(fx);
        }
        return null;
    }

    static List<Fx> all() {
        return effects;
    }

    private static void tick() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null || world != level) {
            if (!effects.isEmpty()) clear();
            return;
        }
        effects.removeIf(fx -> !fx.tick(world));
    }

    static void clear() {
        effects.clear();
        if (batch != null) batch.remove();
        batch = null;
        level = null;
    }

    /** Draws every effect from inside the particle pass. */
    private static final class Batch extends Particle {
        Batch(ClientLevel level) {
            super(level, 0, 0, 0);
            this.lifetime = Integer.MAX_VALUE;
            this.hasPhysics = false;
            this.gravity = 0;
        }

        @Override
        public void tick() {
            if (effects.isEmpty() || level != FxWorld.level) remove();
        }

        @Override
        public void render(VertexConsumer consumer, Camera camera, float partialTick) {
        }

        @Override
        public void renderCustom(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
            FxDraw draw = new FxDraw(level, camera, buffers, pose.last().pose(), partialTick);
            for (Fx fx : effects) fx.render(draw);
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
