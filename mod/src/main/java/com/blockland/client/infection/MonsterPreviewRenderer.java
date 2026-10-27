package com.blockland.client.infection;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the monster models on the select panel's cards. Vanilla's entity preview has one picture-in-picture renderer and
 * so one texture for every entity on screen: each card's model is drawn into it in turn and all cards then show the last
 * one. Here every card slot has a renderer (and texture) of its own — the GUI picks a renderer by the state's class, so
 * each slot is its own state class. Drawn as vanilla's entity preview draws (same light, no shadow).
 */
final class MonsterPreviewRenderer extends PictureInPictureRenderer<MonsterPreviewRenderer.State> {
    /** Cards with a model of their own; further cards (never more than the monster list) show none. */
    static final int SLOTS = 6;

    /** A card's model, as vanilla's {@code GuiEntityRenderState} carries it. */
    abstract static class State implements PictureInPictureRenderState {
        final EntityRenderState renderState;
        final Vector3f translation;
        final Quaternionf rotation;
        final Quaternionf cameraAngle;
        private final int x0, y0, x1, y1;
        private final float scale;
        private final ScreenRectangle scissor, bounds;

        State(EntityRenderState renderState, Vector3f translation, Quaternionf rotation, Quaternionf cameraAngle,
              int x0, int y0, int x1, int y1, float scale) {
            this.renderState = renderState;
            this.translation = translation;
            this.rotation = rotation;
            this.cameraAngle = cameraAngle;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.scale = scale;
            this.scissor = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
            this.bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor);
        }

        @Override public int x0() { return x0; }
        @Override public int y0() { return y0; }
        @Override public int x1() { return x1; }
        @Override public int y1() { return y1; }
        @Override public float scale() { return scale; }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }

    private interface Factory {
        State create(EntityRenderState renderState, Vector3f translation, Quaternionf rotation, Quaternionf cameraAngle,
                     int x0, int y0, int x1, int y1, float scale);
    }

    static final class Slot0 extends State { Slot0(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }
    static final class Slot1 extends State { Slot1(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }
    static final class Slot2 extends State { Slot2(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }
    static final class Slot3 extends State { Slot3(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }
    static final class Slot4 extends State { Slot4(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }
    static final class Slot5 extends State { Slot5(EntityRenderState r, Vector3f t, Quaternionf q, Quaternionf c, int a, int b, int x, int y, float s) { super(r, t, q, c, a, b, x, y, s); } }

    private static final Factory[] FACTORIES = {Slot0::new, Slot1::new, Slot2::new, Slot3::new, Slot4::new, Slot5::new};
    @SuppressWarnings("unchecked")
    private static final Class<? extends State>[] CLASSES = new Class[]{Slot0.class, Slot1.class, Slot2.class, Slot3.class,
            Slot4.class, Slot5.class};

    private final Class<State> stateClass;

    @SuppressWarnings("unchecked")
    private MonsterPreviewRenderer(MultiBufferSource.BufferSource buffers, Class<? extends State> stateClass) {
        super(buffers);
        this.stateClass = (Class<State>) stateClass;
    }

    static void init() {
        RegisterPictureInPictureRendererEvent.BUS.addListener(event -> {
            for (Class<? extends State> type : CLASSES) event.register(new MonsterPreviewRenderer(event.getBufferSource(), type));
        });
    }

    /**
     * Draws {@code entity} in the box as {@code InventoryScreen.renderEntityInInventoryFollowsMouse} does (turning
     * towards the mouse), into card {@code slot}'s own texture.
     */
    static void draw(GuiGraphics g, int slot, int x0, int y0, int x1, int y1, int size, float yOffset, float mouseX, float mouseY,
                     LivingEntity entity) {
        if (slot < 0 || slot >= SLOTS) return;
        float cx = (x0 + x1) / 2.0F, cy = (y0 + y1) / 2.0F;
        float yaw = (float) Math.atan((cx - mouseX) / 40.0F), pitch = (float) Math.atan((cy - mouseY) / 40.0F);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX(pitch * 20.0F * (float) (Math.PI / 180.0));
        rotation.mul(camera);
        float bodyRot = entity.yBodyRot, yRot = entity.getYRot(), xRot = entity.getXRot(), headRotO = entity.yHeadRotO, headRot = entity.yHeadRot;
        entity.yBodyRot = 180.0F + yaw * 20.0F;
        entity.setYRot(180.0F + yaw * 40.0F);
        entity.setXRot(-pitch * 20.0F);
        entity.yHeadRot = entity.getYRot();
        entity.yHeadRotO = entity.getYRot();
        float scale = entity.getScale();
        Vector3f translation = new Vector3f(0.0F, entity.getBbHeight() / 2.0F + yOffset * scale, 0.0F);
        EntityRenderer<? super LivingEntity, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 1.0F);
        state.hitboxesRenderState = null;
        g.getRenderState().submitPicturesInPictureState(FACTORIES[slot].create(state, translation, rotation, camera,
                x0, y0, x1, y1, size / scale));
        entity.yBodyRot = bodyRot;
        entity.setYRot(yRot);
        entity.setXRot(xRot);
        entity.yHeadRotO = headRotO;
        entity.yHeadRot = headRot;
    }

    @Override
    public Class<State> getRenderStateClass() {
        return stateClass;
    }

    @Override
    protected void renderToTexture(State state, PoseStack pose) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        pose.translate(state.translation.x, state.translation.y, state.translation.z);
        pose.mulPose(state.rotation);
        dispatcher.overrideCameraOrientation(state.cameraAngle.conjugate(new Quaternionf()).rotateY((float) Math.PI));
        dispatcher.setRenderShadow(false);
        dispatcher.render(state.renderState, 0.0, 0.0, 0.0, pose, bufferSource, 15728880);
        dispatcher.setRenderShadow(true);
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected String getTextureLabel() {
        return "blockland_monster_preview_" + stateClass.getSimpleName();
    }
}
