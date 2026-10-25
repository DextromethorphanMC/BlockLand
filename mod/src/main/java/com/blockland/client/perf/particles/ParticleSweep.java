package com.blockland.client.perf.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleGroup;

import java.util.Optional;
import java.util.Queue;

/**
 * {@code PARTICLES}: one particle layer's tick without vanilla's {@code iterator.remove()}. A layer is an
 * {@code EvictingQueue} over an {@code ArrayDeque}, where removing from the middle shifts the elements on one side — with
 * many particles dying in one tick that is up to quadratic. Here the layer is turned once round instead: every particle
 * is taken from the head, ticked, and put back at the tail while it lives, so after {@code size} steps the survivors are
 * in their old order, in one linear pass without garbage.
 * <p>
 * Exactly vanilla's {@code ParticleEngine.tickParticleList}: the same particles ticked in the same order, a dead one's
 * group count lowered at the same moment (right after its own tick, before the next particle's), and the same survivors
 * in the same order afterwards. Nothing is evicted when a survivor goes back: the layer is one shorter than before at that
 * point, so never full. New particles never join a layer during its tick (vanilla queues them in {@code particlesToAdd}).
 */
public final class ParticleSweep {
    /** What the sweep needs of the engine (its private tick and group count; the engine's mixin implements it). */
    public interface Engine {
        /** {@code ParticleEngine.tickParticle}: ticks one particle (a crash report if it throws). */
        void blockland$tickParticle(Particle particle);

        /** {@code ParticleEngine.updateCount(group, -1)}: a particle of the group died. */
        void blockland$particleDied(ParticleGroup group);
    }

    private ParticleSweep() {
    }

    /** Ticks the layer and drops the particles that died (the engine's tick, client thread). */
    public static void tick(Queue<Particle> layer, Engine engine) {
        for (int left = layer.size(); left > 0; left--) {
            Particle particle = layer.poll();
            engine.blockland$tickParticle(particle);
            if (particle.isAlive()) {
                layer.add(particle);
            } else {
                Optional<ParticleGroup> group = particle.getParticleGroup();
                if (group.isPresent()) engine.blockland$particleDied(group.get());
            }
        }
    }
}
