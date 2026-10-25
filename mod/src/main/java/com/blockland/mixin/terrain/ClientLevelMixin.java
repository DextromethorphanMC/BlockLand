package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.BiomeTints;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Biome colour blending ({@code BIOME_TINTS}) and the moments its remembered samples have to be dropped. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "calculateBlockTint", at = @At("HEAD"), cancellable = true)
    private void blockland$blendTint(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
        int color = BiomeTints.blend((ClientLevel) (Object) this, pos, resolver);
        if (color != BiomeTints.VANILLA) cir.setReturnValue(color);
    }

    /** After a chunk's data changed, before the tint caches around it are invalidated. */
    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void blockland$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        BiomeTints.biomesChanged();
    }

    @Inject(method = "clearTintCaches", at = @At("HEAD"))
    private void blockland$tintCachesCleared(CallbackInfo ci) {
        BiomeTints.biomesChanged();
    }
}
