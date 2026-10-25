package com.blockland.mixin.particles;

import com.blockland.client.perf.particles.ParticleScratch;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code PARTICLES}: a quad particle's rotation and corners without garbage ({@link ParticleScratch}). */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin {
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;"))
    private Quaternionf blockland$rotation() {
        return ParticleScratch.rotation();
    }

    @Redirect(method = "renderVertex", at = @At(value = "NEW", target = "(FFF)Lorg/joml/Vector3f;"))
    private Vector3f blockland$corner(float x, float y, float z) {
        return ParticleScratch.corner(x, y, z);
    }
}
