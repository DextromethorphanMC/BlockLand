package com.blockland.client.perf.particles;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * {@code PARTICLES}: the quaternion {@code SingleQuadParticle.render} makes for every particle and the vector its
 * {@code renderVertex} makes for every corner, reused on the render thread instead of made anew each time.
 * <p>
 * Exact: a reused quaternion is first set to the identity, the very state of {@code new Quaternionf()}, before the facing
 * mode sets it (a mode that sets only part of it finds what a new one would hold); a reused vector is set to the three
 * values the constructor would store. The operations that follow are vanilla's own, on the same values. Neither leaves
 * its call: the quaternion lives until the particle's last corner is written, the vector until its corner is, and nothing
 * in between renders another particle. Off the render thread (or with the switch off) vanilla's new objects.
 */
public final class ParticleScratch {
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Vector3f CORNER = new Vector3f();

    private ParticleScratch() {
    }

    /** In place of {@code new Quaternionf()} in {@code SingleQuadParticle.render}. */
    public static Quaternionf rotation() {
        if (Optimization.PARTICLES.on() && RenderSystem.isOnRenderThread()) return ROTATION.identity();
        return new Quaternionf();
    }

    /** In place of {@code new Vector3f(x, y, z)} in {@code SingleQuadParticle.renderVertex}. */
    public static Vector3f corner(float x, float y, float z) {
        if (Optimization.PARTICLES.on() && RenderSystem.isOnRenderThread()) return CORNER.set(x, y, z);
        return new Vector3f(x, y, z);
    }
}
