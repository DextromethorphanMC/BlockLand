package com.blockland.mixin.farterrain;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * The far terrain's background generation (rendering spec §22): {@code getChunkFuture} on the server thread waits until
 * the chunk is done (it runs the server's tasks meanwhile, the tick stands still); its inner step only adds the ticket and
 * schedules the generation, which is what the generation wants every tick.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccess {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> blockland$scheduleChunk(int x, int z, ChunkStatus status, boolean load);
}
