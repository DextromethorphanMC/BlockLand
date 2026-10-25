package com.blockland.client.perf.terrain;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.ARGB;
import net.minecraftforge.client.model.IQuadTransformer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.ByteOrder;

/**
 * A block quad into a section's vertex buffer ({@code ModelBlockRenderer.putQuadData}) without what vanilla's
 * {@code VertexConsumer.putBulkData} spends per vertex: a {@code MemoryStack} frame, a fresh byte buffer view the
 * vertex's eight ints are copied into to read them back, and two new vectors.
 * <p>
 * The same arithmetic in the same order on the same values. Vanilla copies the ints into a native-order byte buffer
 * and reads them back in that order, so a float element is {@code Float.intBitsToFloat} of its int and, on a
 * little-endian machine, the byte at offset {@code 4n + b} is byte {@code b} (from the low end) of int {@code n}: the
 * colour bytes 12–14, the baked light shorts 24/26 ({@code IQuadTransformer.UV2}) and the baked normal bytes 28–30.
 * The position goes through the same {@code Matrix4f.transformPosition(x, y, z, dest)} and the normal through the same
 * {@code Pose.transformNormal} / {@code Vector3f.set(...).mul(Matrix3f)} — only {@code dest} is a reused vector, which
 * those calls overwrite completely. Forge's {@code applyBakedLighting} and {@code applyBakedNormals} are written out
 * with their exact semantics, including the normal a vertex without a baked normal inherits from the vertex before it.
 * The vertex is handed to the same {@code addVertex(x, y, z, colour, u, v, overlay, light, nx, ny, nz)} of the
 * buffer. Used for {@link BufferBuilder} itself only (it overrides neither {@code putBulkData} nor Forge's two hooks);
 * on a big-endian machine vanilla's path runs.
 */
public final class QuadWriter {
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private QuadWriter() {
    }

    /** Writes the quad and returns true when the compile on this thread meshes the new way; false: run vanilla's. */
    public static boolean put(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                              float blue, float alpha, int[] lights, int overlay, boolean readExistingColor) {
        if (!LITTLE_ENDIAN || consumer.getClass() != BufferBuilder.class) return false;
        TerrainCompile c = TerrainCompile.meshing();
        if (c == null) return false;
        write(consumer, pose, quad, brightness, red, green, blue, alpha, lights, overlay, readExistingColor, c.quadNormal, c.quadPosition);
        return true;
    }

    /**
     * {@code VertexConsumer.putBulkData(Pose, BakedQuad, float[], float, float, float, float, int[], int, boolean)} with
     * Forge's baked lighting and normals (little-endian layout), through two caller-owned vectors.
     */
    static void write(VertexConsumer out, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue,
                      float alpha, int[] lights, int overlay, boolean readExistingColor, Vector3f normalScratch, Vector3f positionScratch) {
        int[] vertices = quad.vertices();
        Vector3fc direction = quad.direction().getUnitVec3f();
        Matrix4f matrix = pose.pose();
        Vector3f normal = pose.transformNormal(direction, normalScratch);
        int count = vertices.length / 8;
        int a = (int) (alpha * 255.0F);
        int emission = quad.lightEmission();

        for (int v = 0; v < count; v++) {
            int o = v * 8;
            float x = Float.intBitsToFloat(vertices[o]);
            float y = Float.intBitsToFloat(vertices[o + 1]);
            float z = Float.intBitsToFloat(vertices[o + 2]);
            float r;
            float g;
            float b;
            if (readExistingColor) {
                int colour = vertices[o + 3];
                float cr = colour & 255;
                float cg = colour >> 8 & 255;
                float cb = colour >> 16 & 255;
                r = cr * brightness[v] * red;
                g = cg * brightness[v] * green;
                b = cb * brightness[v] * blue;
            } else {
                r = brightness[v] * red * 255.0F;
                g = brightness[v] * green * 255.0F;
                b = brightness[v] * blue * 255.0F;
            }

            int color = ARGB.color(a, (int) r, (int) g, (int) b);
            int light = LightTexture.lightCoordsWithEmission(lights[v], emission);
            light = bakedLighting(light, vertices[o + IQuadTransformer.UV2]);
            float u = Float.intBitsToFloat(vertices[o + 4]);
            float vv = Float.intBitsToFloat(vertices[o + 5]);
            Vector3f position = matrix.transformPosition(x, y, z, positionScratch);
            bakedNormal(normal, vertices[o + 7], pose.normal());
            out.addVertex(position.x(), position.y(), position.z(), color, u, vv, overlay, light, normal.x(), normal.y(), normal.z());
        }
    }

    /** Forge's {@code IForgeVertexConsumer.applyBakedLighting}: the shorts at byte 0 and 2 of the UV2 int. */
    private static int bakedLighting(int packedLight, int baked) {
        int bl = packedLight & 0xFFFF;
        int sl = (packedLight >> 16) & 0xFFFF;
        int blBaked = baked & 0xFFFF;
        int slBaked = baked >>> 16 & 0xFFFF;
        bl = Math.max(bl, blBaked);
        sl = Math.max(sl, slBaked);
        return bl | (sl << 16);
    }

    /** Forge's {@code IForgeVertexConsumer.applyBakedNormals}: the bytes 0–2 of the normal int. */
    private static void bakedNormal(Vector3f generated, int packed, Matrix3f normalTransform) {
        byte nx = (byte) packed;
        byte ny = (byte) (packed >> 8);
        byte nz = (byte) (packed >> 16);
        if (nx != 0 || ny != 0 || nz != 0) {
            generated.set(nx / 127f, ny / 127f, nz / 127f);
            generated.mul(normalTransform);
        }
    }
}
