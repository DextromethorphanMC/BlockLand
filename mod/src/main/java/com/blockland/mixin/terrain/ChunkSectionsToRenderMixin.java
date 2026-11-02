package com.blockland.mixin.terrain;

import com.blockland.client.graphics.frame.LevelPasses;
import com.blockland.client.perf.gl.PagedTerrain;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code TERRAIN_PAGES}: the opaque group drawn from the frame's paged batches ({@link PagedTerrain#render}); and once the
 * opaque group is drawn, either way, the level passes that follow it ({@link LevelPasses#afterOpaque},
 * {@code FAR_GPU_CULLING}).
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void blockland$renderPaged(ChunkSectionLayerGroup group, CallbackInfo ci) {
        if (PagedTerrain.render((ChunkSectionsToRender) (Object) this, group)) {
            if (group == ChunkSectionLayerGroup.OPAQUE) LevelPasses.afterOpaque();
            ci.cancel();
        }
    }

    @Inject(method = "renderGroup", at = @At("RETURN"))
    private void blockland$afterGroup(ChunkSectionLayerGroup group, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) LevelPasses.afterOpaque();
    }
}
