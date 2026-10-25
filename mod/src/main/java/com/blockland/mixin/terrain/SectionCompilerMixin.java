package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.TerrainCompile;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A section compile reads the terrain switches once and keeps them for the whole section (rendering spec §20). */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void blockland$begin(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                                 CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.begin(section, region);
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void blockland$end(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                               CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.end();
    }
}
