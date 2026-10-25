package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.BiomeTints;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biome colour blending ({@code BIOME_TINTS}): every change of which chunk data the client's biome lookups see (a chunk
 * dropped, loaded, given new biomes, or moved out of the cache's range) drops the remembered samples, after the change.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = {"drop", "replaceBiomes", "updateViewCenter", "updateViewRadius"}, at = @At("RETURN"))
    private void blockland$chunksChanged(CallbackInfo ci) {
        BiomeTints.biomesChanged();
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void blockland$chunkReplaced(CallbackInfoReturnable<LevelChunk> cir) {
        BiomeTints.biomesChanged();
    }
}
