package com.blockland.mixin.gl;

import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.gl.TerrainDraws;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/** {@code TERRAIN_DRAWS}: the frame's section draws prepared without garbage ({@link TerrainDraws#prepare}), timed either way. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Unique
    @Nullable
    private FrameTimings.Span blockland$span;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true)
    private void blockland$prepare(Matrix4fc modelView, double cameraX, double cameraY, double cameraZ,
                                   CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        FrameTimings.Span span = FrameTimings.span("bl/gl/prepare_sections");
        if (TerrainDraws.on()) {
            try {
                cir.setReturnValue(TerrainDraws.prepare(visibleSections, modelView, cameraX, cameraY, cameraZ));
            } finally {
                span.close();
            }
            return;
        }
        blockland$span = span;
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"))
    private void blockland$prepared(Matrix4fc modelView, double cameraX, double cameraY, double cameraZ,
                                    CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        FrameTimings.Span span = blockland$span;
        blockland$span = null;
        if (span != null) span.close();
    }
}
