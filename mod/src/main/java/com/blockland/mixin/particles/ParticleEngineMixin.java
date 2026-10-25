package com.blockland.mixin.particles;

import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.particles.ParticleLight;
import com.blockland.client.perf.particles.ParticleSweep;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.particles.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Queue;

/**
 * {@code PARTICLES} in the particle engine: a layer's tick in one sweep ({@link ParticleSweep}) and the per-render light
 * cache's lifetime ({@link ParticleLight}).
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin implements ParticleSweep.Engine {
    @Shadow
    private void tickParticle(Particle particle) {
        throw new AssertionError();
    }

    @Shadow
    private void updateCount(ParticleGroup group, int change) {
        throw new AssertionError();
    }

    @Inject(method = "tickParticleList", at = @At("HEAD"), cancellable = true)
    private void blockland$sweep(Collection<Particle> particles, CallbackInfo ci) {
        if (Optimization.PARTICLES.on() && particles instanceof Queue<Particle> layer) {
            ParticleSweep.tick(layer, this);
            ci.cancel();
        }
    }

    @Override
    public void blockland$tickParticle(Particle particle) {
        tickParticle(particle);
    }

    @Override
    public void blockland$particleDied(ParticleGroup group) {
        updateCount(group, -1);
    }

    @Inject(method = "render(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("HEAD"))
    private void blockland$lightBegin(Camera camera, float partialTick, MultiBufferSource.BufferSource buffers,
                                      @Nullable Frustum frustum, CallbackInfo ci) {
        ParticleLight.begin();
    }

    @Inject(method = "render(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("RETURN"))
    private void blockland$lightEnd(Camera camera, float partialTick, MultiBufferSource.BufferSource buffers,
                                    @Nullable Frustum frustum, CallbackInfo ci) {
        ParticleLight.end();
    }
}
