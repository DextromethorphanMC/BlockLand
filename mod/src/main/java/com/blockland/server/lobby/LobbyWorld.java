package com.blockland.server.lobby;

import com.blockland.BlockLand;
import com.blockland.server.config.BlockLandConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The staging spot of the world. Players wait in the lobby without any world (the configuration phase), so nobody
 * stands here for long: it is where a player is put on leaving the world (so the next world entry loads into the
 * empty {@code blockland:lobby} dimension before the match moves them) and where operators land when they enter
 * the world from the lobby. The overworld keeps no chunks loaded at all (its spawn chunks are disabled). The old
 * "stand in the overworld" spot stays available through the {@code lobby.voidWorld} config.
 */
public final class LobbyWorld {
    public static final ResourceKey<Level> LOBBY_DIMENSION = ResourceKey.create(Registries.DIMENSION, BlockLand.id("lobby"));
    private static final BlockPos STAND = new BlockPos(0, 64, 0);

    private final MinecraftServer server;

    public record Spot(ServerLevel level, Vec3 pos, float yaw) {
    }

    public LobbyWorld(MinecraftServer server) {
        this.server = server;
    }

    /** Called once at server start. */
    public void prepare() {
        if (!useVoid()) return;
        // nobody plays in the overworld, so it does not need to keep spawn chunks in memory
        server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server);
        BlockLand.LOGGER.info("[BlockLand] Staging in {}; overworld spawn chunks disabled", LOBBY_DIMENSION.location());
    }

    public Spot spot() {
        ServerLevel lobby = useVoid() ? server.getLevel(LOBBY_DIMENSION) : null;
        if (lobby != null) {
            if (!lobby.getBlockState(STAND).is(Blocks.BARRIER)) lobby.setBlockAndUpdate(STAND, Blocks.BARRIER.defaultBlockState());
            return new Spot(lobby, Vec3.atBottomCenterOf(STAND.above()), 0);
        }
        ServerLevel level = server.overworld();
        if (BlockLandConfig.LOBBY_USE_WORLD_SPAWN.get()) {
            return new Spot(level, Vec3.atBottomCenterOf(level.getSharedSpawnPos()), level.getSharedSpawnAngle());
        }
        return new Spot(level, new Vec3(BlockLandConfig.LOBBY_X.get(), BlockLandConfig.LOBBY_Y.get(), BlockLandConfig.LOBBY_Z.get()),
                BlockLandConfig.LOBBY_YAW.get().floatValue());
    }

    private static boolean useVoid() {
        return BlockLandConfig.LOBBY_VOID_WORLD.get();
    }
}
