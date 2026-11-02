package com.blockland.mixin.terrain;

import com.blockland.client.render.TerrainVersion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every change to a section's mesh or place is counted ({@code client/render/TerrainVersion}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Inject(method = "setSectionMesh", at = @At("TAIL"))
    private void blockland$meshSet(SectionMesh mesh, CallbackInfo ci) {
        TerrainVersion.bump();
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void blockland$reset(CallbackInfo ci) {
        TerrainVersion.bump();
    }

    @Inject(method = "setSectionNode", at = @At("TAIL"))
    private void blockland$placed(long node, CallbackInfo ci) {
        TerrainVersion.bump();
    }
}
