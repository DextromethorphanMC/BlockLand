package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.FaceGroups;
import com.blockland.client.perf.terrain.FaceIndex;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/** {@code FACE_GROUPS}: the facing groups of the buffers' mesh (null: drawn whole) and the index list of the quads drawn. */
@Mixin(SectionBuffers.class)
public abstract class SectionBuffersMixin implements FaceGroups.Holder {
    @Unique
    @Nullable
    private FaceGroups blockland$groups;
    @Unique
    @Nullable
    private FaceIndex blockland$index;

    @Override
    @Nullable
    public FaceGroups blockland$faceGroups() {
        return blockland$groups;
    }

    @Override
    public void blockland$setFaceGroups(@Nullable FaceGroups groups) {
        blockland$groups = groups;
        if (blockland$index != null) blockland$index.reset();
    }

    @Override
    public FaceIndex blockland$faceIndex() {
        if (blockland$index == null) blockland$index = new FaceIndex();
        return blockland$index;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void blockland$close(CallbackInfo ci) {
        if (blockland$index != null) blockland$index.close();
        blockland$index = null;
    }
}
