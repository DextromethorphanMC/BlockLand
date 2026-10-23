package com.blockland.client.equipment.effect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Sprite drawing for one frame of the effect pass: camera-facing quads (smoke, fireballs), upright quads that only
 * turn about the vertical (flames) and flat quads on the ground (dust rings, glows), in world coordinates.
 */
final class FxDraw {
    static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    final ClientLevel level;
    final Camera camera;
    final Vec3 cam;
    final float partialTick;
    private final MultiBufferSource buffers;
    private final Matrix4f m;
    private final Vector3f left, up;
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    FxDraw(ClientLevel level, Camera camera, MultiBufferSource buffers, Matrix4f pose, float partialTick) {
        this.level = level;
        this.camera = camera;
        this.cam = camera.getPosition();
        this.buffers = buffers;
        this.m = pose;
        this.partialTick = partialTick;
        this.left = new Vector3f(camera.getLeftVector());
        this.up = new Vector3f(camera.getUpVector());
    }

    /** Packed light at a point (sky and block light, as for particles). */
    int light(double x, double y, double z) {
        lightPos.set(x, y, z);
        return LevelRenderer.getLightColor(level, lightPos);
    }

    /** The brighter of two packed lights, per channel (a sprite poking into a block keeps the light around it). */
    static int brighter(int a, int b) {
        return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
    }

    double distanceSqr(double x, double y, double z) {
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** A camera-facing square of half-size {@code size} turned by {@code rotation} (radians) in the screen plane. */
    void billboard(RenderType type, double x, double y, double z, float size, float rotation,
                   float u0, float v0, float u1, float v1, int argb, int light) {
        float c = (float) Math.cos(rotation) * size, s = (float) Math.sin(rotation) * size;
        // corner = centre + left * a + up * b, the square's axes rotated in the screen plane
        float ax = left.x * c + up.x * s, ay = left.y * c + up.y * s, az = left.z * c + up.z * s;
        float bx = -left.x * s + up.x * c, by = -left.y * s + up.y * c, bz = -left.z * s + up.z * c;
        float px = (float) (x - cam.x), py = (float) (y - cam.y), pz = (float) (z - cam.z);
        VertexConsumer out = buffers.getBuffer(type);
        vertex(out, px - ax - bx, py - ay - by, pz - az - bz, u1, v1, argb, light);
        vertex(out, px - ax + bx, py - ay + by, pz - az + bz, u1, v0, argb, light);
        vertex(out, px + ax + bx, py + ay + by, pz + az + bz, u0, v0, argb, light);
        vertex(out, px + ax - bx, py + ay - by, pz + az - bz, u0, v1, argb, light);
    }

    /** An upright quad standing on (x, y, z), turned about the vertical to face the camera. */
    void upright(RenderType type, double x, double y, double z, float width, float height,
                 float u0, float v0, float u1, float v1, int argb, int light) {
        double dx = cam.x - x, dz = cam.z - z;
        double len = Math.sqrt(dx * dx + dz * dz);
        float rx = len < 1e-4 ? 1 : (float) (-dz / len) * width * 0.5f, rz = len < 1e-4 ? 0 : (float) (dx / len) * width * 0.5f;
        float px = (float) (x - cam.x), py = (float) (y - cam.y), pz = (float) (z - cam.z);
        VertexConsumer out = buffers.getBuffer(type);
        vertex(out, px - rx, py, pz - rz, u0, v1, argb, light);
        vertex(out, px - rx, py + height, pz - rz, u0, v0, argb, light);
        vertex(out, px + rx, py + height, pz + rz, u1, v0, argb, light);
        vertex(out, px + rx, py, pz + rz, u1, v1, argb, light);
    }

    /** A flat square lying on the ground (half-size {@code size}, turned by {@code rotation} about the vertical). */
    void flat(RenderType type, double x, double y, double z, float size, float rotation, int argb, int light) {
        float c = (float) Math.cos(rotation) * size, s = (float) Math.sin(rotation) * size;
        float px = (float) (x - cam.x), py = (float) (y - cam.y), pz = (float) (z - cam.z);
        VertexConsumer out = buffers.getBuffer(type);
        vertex(out, px - c + s, py, pz - s - c, 0, 0, argb, light);
        vertex(out, px - c - s, py, pz - s + c, 0, 1, argb, light);
        vertex(out, px + c - s, py, pz + s + c, 1, 1, argb, light);
        vertex(out, px + c + s, py, pz + s - c, 1, 0, argb, light);
    }

    private void vertex(VertexConsumer out, float x, float y, float z, float u, float v, int argb, int light) {
        out.addVertex(m, x, y, z).setUv(u, v).setColor(argb).setLight(light);
    }
}
