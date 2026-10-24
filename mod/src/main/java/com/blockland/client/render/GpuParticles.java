package com.blockland.client.render;

import com.blockland.client.gltf.GltfModel;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * Stateless GPU particles (asset spec §38): a burst or a stream is ONE draw of a static mesh of quads whose vertices
 * carry nothing but random seeds; the vertex shader ({@code blockland:core/gun_particle}) works out every particle's
 * birth, path (speed, drag, gravity, orbit about the emitter's Z axis, a pull towards its origin, turbulence), size,
 * colour and fade from the seeds, the emitter's age and the {@link Emitter} parameters, and stretches fast ones into
 * streaks. Nothing is simulated or uploaded per frame: a draw is a model-view and sixteen numbers. The emitter's space
 * is a node of the weapon (particles ride with it); gravity is given in view space so it points down whatever the node
 * does. Additive and fullbright, drawn with the frame's blended draws. Render-side and data-free: features turn their
 * data into {@link Emitter}s.
 */
public final class GpuParticles {
    /** Most particles one draw can hold. */
    public static final int MAX = 128;
    // flags (TextureMat[3].w, see gun_particle.vsh)
    private static final int LOOP = 1, RING = 2, TEXTURED = 4;

    @Nullable
    private static GpuModel mesh;
    private static final Matrix4f params = new Matrix4f();
    private static final Vector4f color = new Vector4f();
    private static final Vector3f gravityView = new Vector3f();

    private GpuParticles() {
    }

    /**
     * What one emitter does (lengths in metres of the emitter's space, times in seconds).
     *
     * @param count       particles (≤ {@link #MAX})
     * @param life        seconds a particle lives; {@code lifeJitter} 0..1 shortens some by up to that share
     * @param spawn       births spread over these seconds from the emitter's start (0 = all at once); a looping
     *                    emitter spreads them over one life and keeps them coming
     * @param speed       launch speed (m/s); {@code speedJitter} 0..1 slows some by up to that share
     * @param drag        how fast the launch speed dies (1/s; 0 = none)
     * @param gravity     m/s² downwards in the view (negative = rising, like embers and heat)
     * @param radius      spawn sphere radius (a ring of this radius about the Z axis with {@code ring})
     * @param direction   0 = launched in every direction, 1 = all along the emitter's −Z (a nozzle)
     * @param orbit       turn about the emitter's Z axis (rad/s): swirls and orbits
     * @param gather      0..1 how far particles are pulled into the emitter's origin over their life (energy gathering)
     * @param turbulence  amplitude (m) of a wandering offset that grows over the life
     * @param sizeStart   half-size at birth (m); {@code sizeEnd} at death
     * @param stretch     streak length per m/s of screen speed (s; 0 = round sprites)
     * @param rgb         display colour (particles start hotter, towards white, and cool to it)
     * @param intensity   brightness multiplier (additive: > 1 for hot cores that bloom)
     * @param loop        a stream: particles are reborn after their life
     * @param ring        spawn on a ring about the Z axis instead of in a sphere
     * @param texture     a sprite instead of the round glow, or null
     */
    public record Emitter(int count, float life, float lifeJitter, float spawn, float speed, float speedJitter, float drag,
                          float gravity, float radius, float direction, float orbit, float gather, float turbulence,
                          float sizeStart, float sizeEnd, float stretch, int rgb, float intensity, boolean loop, boolean ring,
                          @Nullable GpuTextureView texture) {
    }

    /**
     * Queues one emitter.
     *
     * @param modelView emitter space (a node, a muzzle) → view space
     * @param age       seconds since the emitter started
     * @param alpha     overall fade (a stream fading in and out)
     * @param viewDown  the world's down direction in view space (unit), for gravity
     */
    public static void draw(DrawList list, Emitter e, Matrix4f modelView, float age, float alpha, Vector3fc viewDown) {
        int count = Math.max(0, Math.min(MAX, e.count()));
        if (count == 0 || alpha <= 0.002f) return;
        GpuModel m = mesh();
        int flags = (e.loop() ? LOOP : 0) | (e.ring() ? RING : 0) | (e.texture() != null ? TEXTURED : 0);
        // TextureMat columns (see gun_particle.vsh): (age, life, life jitter, spawn) (speed, speed jitter, drag,
        // direction) (radius, orbit, gather, turbulence) (size start, size end, stretch, flags)
        params.set(
                age, Math.max(0.01f, e.life()), clamp01(e.lifeJitter()), Math.max(0, e.spawn()),
                e.speed(), clamp01(e.speedJitter()), Math.max(0, e.drag()), clamp01(e.direction()),
                Math.max(0, e.radius()), e.orbit(), clamp01(e.gather()), Math.max(0, e.turbulence()),
                Math.max(0, e.sizeStart()), Math.max(0, e.sizeEnd()), Math.max(0, e.stretch()), flags);
        int c = e.rgb();
        color.set((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f, alpha * Math.max(0, e.intensity()));
        gravityView.set(viewDown).normalize().mul(e.gravity());
        list.addRaw(GunPipelines.PARTICLES, m.vertexBuffer(), m.indexBuffer(), m.indexType(), 0, count * 6, modelView, color,
                gravityView, params, e.texture() != null ? e.texture() : Textures.white());
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * {@link #MAX} quads: position = three seeds, uv = the corner, normal = two more seeds and the particle's index
     * share (births are spread by it, so the first {@code count} quads of any draw cover the spawn evenly).
     */
    private static GpuModel mesh() {
        if (mesh != null && !mesh.isClosed()) return mesh;
        java.util.Random random = new java.util.Random(0x5EEDL);
        GltfModel.Primitive p = new GltfModel.Primitive();
        p.positions = new float[MAX * 12];
        p.uvs = new float[MAX * 8];
        p.normals = new float[MAX * 12];
        p.indices = new int[MAX * 6];
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < MAX; i++) {
            float s1 = random.nextFloat(), s2 = random.nextFloat(), s3 = random.nextFloat();
            float s4 = random.nextFloat(), s5 = random.nextFloat();
            // the index share, bit-reversed (van der Corput): any first n quads are spread evenly over the spawn
            float share = (Integer.reverse(i) >>> 8) / (float) (1 << 24);
            for (int k = 0; k < 4; k++) {
                int v = i * 4 + k;
                p.positions[v * 3] = s1;
                p.positions[v * 3 + 1] = s2;
                p.positions[v * 3 + 2] = s3;
                p.uvs[v * 2] = corners[k][0];
                p.uvs[v * 2 + 1] = corners[k][1];
                // normals are stored as bytes of -1..1: the shader maps them back to 0..1
                p.normals[v * 3] = s4 * 2 - 1;
                p.normals[v * 3 + 1] = s5 * 2 - 1;
                p.normals[v * 3 + 2] = share * 2 - 1;
            }
            int b = i * 4;
            int[] quad = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(quad, 0, p.indices, i * 6, 6);
        }
        GltfModel model = new GltfModel();
        GltfModel.Mesh m = new GltfModel.Mesh();
        m.name = "particles";
        m.primitives.add(p);
        model.meshes.add(m);
        mesh = GpuModel.upload("blockland particles", model);
        return mesh;
    }

    /** A resource reload drops the mesh (it is rebuilt on the next draw). */
    public static void clear() {
        if (mesh != null) mesh.close();
        mesh = null;
    }
}
