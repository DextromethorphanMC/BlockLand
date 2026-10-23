package com.blockland.client.screen.lobby;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Draws the lobby figure: a bare player model with the account's skin. The lobby has no player entity, and the
 * vanilla entity preview runs every render layer — layers from other mods (OptiFine's player items, capes, ...)
 * read the entity behind the render state and crash on the lobby's entity-less state. This renderer draws only the
 * model, the same way the living-entity renderer places it.
 */
public final class FigureRenderer extends PictureInPictureRenderer<FigureRenderer.State> {
    private static final int FULL_BRIGHT = 15728880;

    @Nullable
    private EntityModelSet bakedFrom;
    private PlayerModel wide, slim;

    public record State(PlayerRenderState figure, Vector3f translation, Quaternionf rotation,
                        int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
                        @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
        public static State of(PlayerRenderState figure, float scale, Vector3f translation, Quaternionf rotation,
                               int x0, int y0, int x1, int y1) {
            return new State(figure, translation, rotation, x0, y0, x1, y1, scale, null,
                    PictureInPictureRenderState.getBounds(x0, y0, x1, y1, null));
        }
    }

    public static void init() {
        RegisterPictureInPictureRendererEvent.BUS.addListener(event -> event.register(new FigureRenderer(event.getBufferSource())));
    }

    private FigureRenderer(MultiBufferSource.BufferSource buffers) {
        super(buffers);
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected String getTextureLabel() {
        return "blockland_lobby_figure";
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected void renderToTexture(State state, PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        EntityModelSet models = mc.getEntityModels();
        if (models != bakedFrom) {
            wide = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
            slim = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
            bakedFrom = models;
        }
        PlayerRenderState figure = state.figure();
        PlayerModel model = figure.skin.model() == PlayerSkin.Model.SLIM ? slim : wide;

        mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        Vector3f t = state.translation();
        pose.translate(t.x, t.y, t.z);
        pose.mulPose(state.rotation());
        // LivingEntityRenderer.render for a standing player
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - figure.bodyRot));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.scale(0.9375F, 0.9375F, 0.9375F);
        pose.translate(0.0F, -1.501F, 0.0F);
        model.setupAnim(figure);
        model.renderToBuffer(pose, bufferSource.getBuffer(RenderType.entityTranslucent(figure.skin.texture())),
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
    }
}
