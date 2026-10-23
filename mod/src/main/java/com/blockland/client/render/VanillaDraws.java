package com.blockland.client.render;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * The shader-pack path of {@link DrawList}: with an OptiFine shader pack the pack renders the hand pass into its own
 * buffers with its own programs, so our pipeline would draw outside its lighting (or be overwritten). Here the frame's
 * triangles go through vanilla entity render types into the hand pass's buffers instead — transformed on the CPU,
 * baked AO in the vertex colour, textures bound by id so the pack finds a texture set's {@code _n} / {@code _s} next to
 * its albedo. Effects become their static textures.
 */
final class VanillaDraws {
    private static final ResourceLocation WHITE = BlockLand.id("dynamic/white");
    /** Resource textures already switched to linear filtering (vanilla loads them nearest-filtered). */
    private static final Set<ResourceLocation> filtered = new HashSet<>();
    private static boolean whiteRegistered;

    private VanillaDraws() {
    }

    static void emit(DrawList.Entry[] entries, PoseStack hand, MultiBufferSource buffers, float fovScale) {
        for (DrawList.Entry e : entries) {
            GltfModel.Primitive source = e.source;
            if (source == null || source.indices == null || source.indices.length < 3) continue;
            ResourceLocation texture = e.texture != null ? e.texture : white();
            if (!e.managedTexture) prepare(texture);
            RenderType type = switch (e.blend) {
                case OPAQUE -> RenderType.entityCutoutNoCull(texture);
                case TRANSLUCENT -> RenderType.entityTranslucent(texture);
                case ADDITIVE -> RenderType.energySwirl(texture, 0, 0);
                case EMISSIVE -> RenderType.eyes(texture);
            };
            VertexConsumer consumer = buffers.getBuffer(type);
            PoseStack.Pose pose = hand.last().copy();
            pose.scale(fovScale, fovScale, 1);
            pose.mulPose(e.modelView);
            int light = e.blend == DrawList.Blend.OPAQUE || e.blend == DrawList.Blend.TRANSLUCENT
                    ? LightTexture.pack(e.blockLight, e.skyLight) : LightTexture.FULL_BRIGHT;
            float r = e.color.x, g = e.color.y, b = e.color.z, a = e.color.w;
            if (e.blend == DrawList.Blend.ADDITIVE) {
                // additive blending ignores alpha: fade the colour instead
                r *= a;
                g *= a;
                b *= a;
                a = 1;
            }
            int[] indices = source.indices;
            // a spray decal's AO channel is its facing fade (asset spec §29): it fades the alpha, not the colour
            boolean decal = Math.round(e.params.m33()) == MaterialLayer.DECAL;
            for (int t = 0; t + 2 < indices.length; t += 3) {
                // entity render types draw quads: each triangle is a quad with its last corner doubled
                vertex(consumer, pose, source, indices[t], r, g, b, a, light, e.uvScale, decal);
                vertex(consumer, pose, source, indices[t + 1], r, g, b, a, light, e.uvScale, decal);
                vertex(consumer, pose, source, indices[t + 2], r, g, b, a, light, e.uvScale, decal);
                vertex(consumer, pose, source, indices[t + 2], r, g, b, a, light, e.uvScale, decal);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, GltfModel.Primitive p, int i,
                               float r, float g, float b, float a, int light, float uvScale, boolean decal) {
        float ao = p.ao == null ? 1 : p.ao[i];
        float shade = decal ? 1 : ao, fade = decal ? ao : 1;
        // vertex colours are bytes: a recolouring tint above 1 is clipped on this path
        consumer.addVertex(pose, p.positions[i * 3], p.positions[i * 3 + 1], p.positions[i * 3 + 2])
                .setColor(Math.min(1, r * shade), Math.min(1, g * shade), Math.min(1, b * shade), Math.min(1, a * fade))
                .setUv(p.uvs[i * 2] * uvScale, p.uvs[i * 2 + 1] * uvScale)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, p.normals[i * 3], p.normals[i * 3 + 1], p.normals[i * 3 + 2]);
    }

    /** Loads a resource texture through the TextureManager once and filters it linearly (HD textures alias otherwise). */
    private static void prepare(ResourceLocation texture) {
        if (texture.equals(WHITE) || !filtered.add(texture)) return;
        try {
            AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(texture);
            loaded.setFilter(true, false);
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot prepare {} for the shader-pack path: {}", texture, e.toString());
        }
    }

    private static ResourceLocation white() {
        if (!whiteRegistered) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelABGR(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, image));
            whiteRegistered = true;
        }
        return WHITE;
    }

    /** Resource reload: the TextureManager reloads its textures, our filtering marks and registrations go. */
    static void clear() {
        filtered.clear();
        if (whiteRegistered) Minecraft.getInstance().getTextureManager().release(WHITE);
        whiteRegistered = false;
    }
}
