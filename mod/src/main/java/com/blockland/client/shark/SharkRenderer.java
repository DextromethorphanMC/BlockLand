package com.blockland.client.shark;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ObjectMotion;
import com.blockland.client.render.ShadowPass;
import com.blockland.combat.HitShapes;
import com.blockland.shark.Shark;
import com.blockland.shark.SharkData;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Draws the shark (shark spec §4) as its GLB with the mod's own pipeline (PBR, lit by the world like the vehicles and
 * the weapons): the {@code body} mesh bent by its swimming wave ({@link SharkBend}), the {@code jaw} turned open in a
 * bite and carried by the head as it sways. While the sun's shadow map captures entities its triangles go through
 * vanilla render types, so it casts a shadow; its motion (and turning) goes to {@link ObjectMotion} for TAA, DLSS and
 * the motion blur. Until the model exists its hit boxes are drawn as outlines.
 */
final class SharkRenderer extends EntityRenderer<Shark, SharkRenderer.State> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("blockland", "models/creature/shark.glb");
    private static final DrawList draws = new DrawList().scene(true);

    static final class State extends EntityRenderState {
        @Nullable
        Shark shark;
        float partialTick;
    }

    private final SharkBend bend = new SharkBend();
    @Nullable
    private GpuModel posed;
    @Nullable
    private Pose pose;
    private int body = -1, jaw = -1;
    private final Matrix4f local = new Matrix4f(), base = new Matrix4f(), scratch = new Matrix4f(), carry = new Matrix4f();
    private final Quaternionf q = new Quaternionf(), motionTurn = new Quaternionf();
    private final Vector3f motionCenter = new Vector3f(0, 0, 0), motionHalf = new Vector3f(0.75f, 0.75f, 2.0f);

    SharkRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(Shark shark, State state, float partialTick) {
        super.extractRenderState(shark, state, partialTick);
        state.shark = shark;
        state.partialTick = partialTick;
    }

    /** The body reaches past the entity's square box. */
    @Override
    protected AABB getBoundingBoxForCulling(Shark shark) {
        return shark.getBoundingBox().inflate(2.2, 0.8, 2.2);
    }

    @Override
    public void render(State state, PoseStack stack, MultiBufferSource buffers, int light) {
        Shark shark = state.shark;
        if (shark == null) return;
        float pt = state.partialTick;
        GpuModel model = ModelCache.get(MODEL);
        if (model == null || model.isClosed()) {
            outline(shark, stack, buffers);
            return;
        }
        rig(model);
        SharkData.Animation a = SharkData.get().animation;
        SharkBend.Wave wave = new SharkBend.Wave(Mth.lerp(pt, shark.swayO, shark.sway), Mth.lerp(pt, shark.swimPhaseO, shark.swimPhase),
                a.waveLength, Mth.lerp(pt, shark.bendO, shark.bend));
        float yaw = Mth.rotLerp(pt, shark.yBodyRotO, shark.yBodyRot);
        float pitch = -Mth.lerp(pt, shark.xRotO, shark.getXRot());
        // a dying shark rolls belly up
        float roll = shark.deathTime > 0 ? Math.min(180, (shark.deathTime + pt) * 9) : 0;
        local.identity().translate(0, (float) Shark.AXIS, 0).rotateY((float) (Math.PI - Math.toRadians(yaw)))
                .rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll));
        boolean vanilla = ShadowPass.active();
        if (!vanilla) {
            Vec3 at = shark.getPosition(pt);
            ObjectMotion.drawn(shark.getId(), at.x, at.y, at.z, local.getNormalizedRotation(motionTurn), motionCenter.set(0, (float) Shark.AXIS, 0), motionHalf);
        }
        if (vanilla) base.set(local);
        else base.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()).mul(local);
        if (vanilla) stack.pushPose();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        // the body, bent
        if (body >= 0) {
            int mesh = model.source.nodes.get(body).mesh;
            if (mesh >= 0) bend.draw(draws, model, mesh, wave, scratch.set(base).mul(pose.world[body]), GpuModel.Style.NONE, block, sky);
        }
        // the jaw: open in a bite, carried by the head where it hinges
        if (jaw >= 0 && model.source.nodes.get(jaw).mesh >= 0) {
            float open = shark.biteTicks > 0 ? Mth.sin((shark.biteTicks - pt) / 10f * Mth.PI) : 0;
            float[] r = model.source.nodes.get(jaw).rotation;
            pose.reset();
            pose.setRotation(jaw, q.set(r[0], r[1], r[2], r[3]).rotateX((float) Math.toRadians(-a.jawOpen * Math.max(0, open))));
            pose.computeWorld(new Matrix4f());
            float hinge = model.restOrigin(jaw).z();
            scratch.set(base).mul(wave.at(hinge, carry)).mul(pose.world[jaw]);
            model.addMesh(draws, model.source.nodes.get(jaw).mesh, scratch, GpuModel.Style.NONE, block, sky, 1);
        }
        if (vanilla) {
            draws.flushVanilla(stack, buffers);
            stack.popPose();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null) {
            draws.clear();
            return;
        }
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand shark");
    }

    /** The model's nodes, found once per loaded model. */
    private void rig(GpuModel model) {
        if (model == posed) return;
        posed = model;
        GltfModel source = model.source;
        pose = new Pose(source);
        pose.computeWorld(new Matrix4f());
        body = source.node("body");
        jaw = source.node("jaw");
    }

    /** No model yet: the hit boxes as outlines (relative to where the entity is drawn). */
    private static void outline(Shark shark, PoseStack stack, MultiBufferSource buffers) {
        if (ShadowPass.active()) return;
        Vec3 now = shark.position();
        for (HitShapes.Box b : shark.hitBoxes(0)) {
            AABB box = b.bounds().move(-now.x, -now.y, -now.z);
            ShapeRenderer.renderLineBox(stack, buffers.getBuffer(RenderType.lines()), box, 0.4f, 0.6f, 0.8f, 1f);
        }
    }
}
