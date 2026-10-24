package com.blockland.client.bomb;

import com.blockland.BlockLand;
import com.blockland.bomb.BombContent;
import com.blockland.bomb.PlantedBomb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws a planted bomb with the C4 item model lying on the floor (its {@code fixed} display, asset spec §39), turned
 * the way the planter faced, and the red light on its keypad that flashes with every beep ({@link ClientBomb#led}).
 */
final class PlantedBombRenderer extends EntityRenderer<PlantedBomb, PlantedBombRenderer.State> {
    private static final ResourceLocation GLOW = BlockLand.id("textures/effect/glow.png");
    /** The light on the timer unit, in the held model's frame (blocks; its bottom face rests on the origin, §39). */
    private static final float LED_X = -0.028f, LED_Y = 0.215f, LED_Z = 0.078f;

    private final ItemModelResolver itemModelResolver;
    private final ItemStack stack = new ItemStack(BombContent.C4.get());

    static final class State extends EntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        float yaw, led;
        boolean exploded;
        final Quaternionf camera = new Quaternionf();
    }

    PlantedBombRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
        this.shadowRadius = 0.2f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PlantedBomb bomb, State state, float partialTick) {
        super.extractRenderState(bomb, state, partialTick);
        itemModelResolver.updateForNonLiving(state.item, stack, ItemDisplayContext.FIXED, bomb);
        state.yaw = bomb.getYRot();
        state.led = ClientBomb.led(bomb);
        state.exploded = bomb.state() == PlantedBomb.State.EXPLODED;
        state.camera.set(entityRenderDispatcher.cameraOrientation());
    }

    @Override
    public void render(State state, PoseStack pose, MultiBufferSource buffers, int light) {
        if (state.exploded || state.item.isEmpty()) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180 - state.yaw));
        pose.pushPose();
        pose.translate(0, 0.005, 0);
        state.item.render(pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        if (state.led > 0.01f) {
            pose.translate(LED_X, LED_Y, LED_Z);
            pose.mulPose(state.camera);
            float size = 0.05f + 0.07f * state.led;
            int alpha = Math.round(255 * state.led);
            VertexConsumer out = buffers.getBuffer(RenderType.eyes(GLOW));
            Matrix4f m = pose.last().pose();
            quad(out, m, size, alpha, 0xFF3020);
            quad(out, m, size * 0.35f, alpha, 0xFFD0C0);
        }
        pose.popPose();
        super.render(state, pose, buffers, light);
    }

    private static void quad(VertexConsumer out, Matrix4f m, float s, int alpha, int rgb) {
        int argb = alpha << 24 | rgb;
        vertex(out, m, -s, -s, 0, 1, argb);
        vertex(out, m, s, -s, 1, 1, argb);
        vertex(out, m, s, s, 1, 0, argb);
        vertex(out, m, -s, s, 0, 0, argb);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, float x, float y, float u, float v, int argb) {
        out.addVertex(m, x, y, 0).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
    }
}
