package com.blockland.mixin.particles;

import com.blockland.client.perf.particles.ParticleLight;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code PARTICLES}: a particle's light looked up once per block and particle render ({@link ParticleLight}). */
@Mixin(Particle.class)
public abstract class ParticleMixin {
    @Redirect(method = "getLightColor(F)I", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int blockland$light(BlockAndTintGetter level, BlockPos pos) {
        return ParticleLight.light(level, pos);
    }
}
