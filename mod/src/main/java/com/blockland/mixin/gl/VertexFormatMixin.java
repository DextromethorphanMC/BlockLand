package com.blockland.mixin.gl;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.IntList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

/**
 * {@code TERRAIN_DRAWS}: a vertex format's hash (the vertex array cache looks every draw's format up) is computed once.
 * Its inputs never change after construction (the element mask is final, only the constructor writes the offsets).
 */
@Mixin(VertexFormat.class)
public abstract class VertexFormatMixin {
    @Shadow
    @Final
    private int elementsMask;
    @Shadow
    @Final
    private int[] offsetsByElement;

    @Unique
    private int blockland$hash;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void blockland$hashOnce(List<VertexFormatElement> elements, List<String> names, IntList offsets, int vertexSize,
                                    CallbackInfo ci) {
        blockland$hash = this.elementsMask * 31 + Arrays.hashCode(this.offsetsByElement);
    }

    /**
     * @author BlockLand
     * @reason the same value, computed once (off: vanilla's expression)
     */
    @Overwrite
    public int hashCode() {
        return Optimization.TERRAIN_DRAWS.on() ? blockland$hash : this.elementsMask * 31 + Arrays.hashCode(this.offsetsByElement);
    }
}
