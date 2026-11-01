package com.blockland.server.ambient;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.AmbientPackets;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The ambient props a match's map asks for (rendering spec §29): a map's settings may carry
 * {@code "ambient": {"tumbleweeds": true}}. Arena maps live in the arena dimension, where the biome tells the client
 * nothing, so once a second every player in the world is told what the match they are in asks for, inside its bounds
 * (only when that changed: joining a match, its arena being placed, leaving it). Wired by {@code BlockLandServer}.
 */
public final class AmbientService {
    private final MinecraftServer server;
    private final Function<ServerPlayer, GameInstance> gameOf;
    /** What each player was last told. */
    private final Map<UUID, AmbientPackets.S2CAmbient> told = new HashMap<>();
    private int ticks;

    public AmbientService(MinecraftServer server, Function<ServerPlayer, GameInstance> gameOf) {
        this.server = server;
        this.gameOf = gameOf;
    }

    public void tick() {
        if (++ticks % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AmbientPackets.S2CAmbient now = of(gameOf.apply(player));
            AmbientPackets.S2CAmbient before = told.get(player.getUUID());
            if (now.equals(before) || before == null && now.effects() == 0) continue;
            told.put(player.getUUID(), now);
            BLNetwork.sendTo(player, now);
        }
    }

    /** The player left the world (their client forgets with it). */
    public void forget(ServerPlayer player) {
        told.remove(player.getUUID());
    }

    /** What a match asks for: its map's {@code ambient} settings, inside its bounds (none before its arena is placed). */
    static AmbientPackets.S2CAmbient of(@Nullable GameInstance game) {
        if (game == null) return AmbientPackets.S2CAmbient.NONE;
        MapDef map = game.map();
        AABB bounds = game.spectateBounds();
        if (map == null || bounds == null) return AmbientPackets.S2CAmbient.NONE;
        int effects = flag(map.settings, "tumbleweeds") ? AmbientPackets.TUMBLEWEEDS : 0;
        if (effects == 0) return AmbientPackets.S2CAmbient.NONE;
        return new AmbientPackets.S2CAmbient(effects, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    /** {@code settings.ambient.<key>} is {@code true}. */
    private static boolean flag(@Nullable JsonObject settings, String key) {
        if (settings == null || !(settings.get("ambient") instanceof JsonObject ambient)) return false;
        JsonElement value = ambient.get(key);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
