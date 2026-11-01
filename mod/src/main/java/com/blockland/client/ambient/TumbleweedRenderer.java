package com.blockland.client.ambient;

import com.blockland.ambient.TumbleweedEntity;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ObjectMotion;
import com.blockland.client.render.ShadowPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a tumbleweed (rendering spec §29) as one of the {@code models/ambient/tumbleweed_<i>.glb} variants through the
 * weapon pipeline — lit by the world ({@code SceneLighting}: sun, shadows, lights), its own motion reported to
 * {@link ObjectMotion} for TAA, DLSS and the motion blur, and through vanilla's render types while the sun's shadow map
 * captures entities, so it casts a shadow. Scaled to its radius and by how far it has grown in, turned by its roll.
 */
final class TumbleweedRenderer extends EntityRenderer<TumbleweedEntity, TumbleweedRenderer.State> {
    static final int VARIANTS = 3;
    /** The models' radius (metres): the runtime scales each to its tumbleweed's. */
    private static final float MODEL_RADIUS = 0.5f;
    /** The models are flattened a little: the ball's centre sits this share of its radius over the ground. */
    private static final float CENTRE = 0.86f;
    /** Motion keys apart from the vehicles' (entity ids). */
    private static final long MOTION_KEY = 0x5457_0000_0000_0000L;
    private static final ResourceLocation[] MODELS = new ResourceLocation[VARIANTS];

    static {
        for (int i = 0; i < VARIANTS; i++) MODELS[i] = ResourceLocation.parse("blockland:models/ambient/tumbleweed_" + i + ".glb");
    }

    private static final DrawList draws = new DrawList().scene(true);

    static final class State extends EntityRenderState {
        @Nullable
        TumbleweedEntity weed;
        float partialTick;
    }

    private final Map<GpuModel, Pose> poses = new WeakHashMap<>();
    private final Matrix4f local = new Matrix4f(), base = new Matrix4f(), scratch = new Matrix4f();
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f zero = new Vector3f(), half = new Vector3f();

    TumbleweedRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(TumbleweedEntity weed, State state, float partialTick) {
        super.extractRenderState(weed, state, partialTick);
        state.weed = weed;
        state.partialTick = partialTick;
    }

    @Override
    public void render(State state, PoseStack stack, MultiBufferSource buffers, int light) {
        try (FrameTimings.Span ignored = FrameTimings.span(ShadowPass.active() ? "bl/ambient/tumbleweed_shadow" : "bl/ambient/tumbleweed_draw")) {
            draw(state, stack, buffers, light);
        }
    }

    private void draw(State state, PoseStack stack, MultiBufferSource buffers, int light) {
        TumbleweedEntity weed = state.weed;
        if (weed == null) return;
        float pt = state.partialTick;
        float grown = Mth.lerp(pt, weed.fadeO, weed.fade);
        if (grown <= 0.01f) return;
        GpuModel model = ModelCache.get(MODELS[Math.floorMod(weed.variant, VARIANTS)]);
        if (model == null || model.isClosed()) return;
        // grows in and shrinks away from the ground up, eased
        float s = weed.radius / MODEL_RADIUS * (grown * grown * (3 - 2 * grown));
        float lift = weed.radius * CENTRE * (grown * grown * (3 - 2 * grown));
        weed.rotationO.slerp(weed.rotation, pt, turn);
        local.identity().translate(0, lift, 0).rotate(turn).scale(s);
        boolean vanilla = ShadowPass.active();
        if (!vanilla) {
            Vec3 at = weed.getPosition(pt);
            ObjectMotion.drawn(MOTION_KEY | (weed.getId() & 0xFFFFFFFFL), at.x, at.y + lift, at.z, turn, zero,
                    half.set(weed.radius * 1.05f));
        }
        Pose pose = poses.computeIfAbsent(model, m -> {
            Pose p = new Pose(m.source);
            p.reset();
            p.computeWorld(new Matrix4f());
            return p;
        });
        if (vanilla) base.set(local);
        else base.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()).mul(local);
        if (vanilla) stack.pushPose();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        GltfModel source = model.source;
        for (int index : source.order) {
            if (source.nodes.get(index).mesh < 0) continue;
            model.addNode(draws, index, scratch.set(base).mul(pose.world[index]), GpuModel.Style.NONE, "", block, sky, 1);
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
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand tumbleweed");
    }
}
