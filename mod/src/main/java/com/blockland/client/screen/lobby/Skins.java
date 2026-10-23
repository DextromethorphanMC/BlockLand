package com.blockland.client.screen.lobby;

import com.blockland.client.ClientLobbyState;
import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Player skins for the lobby. The lobby has no world and no tab list, so skins are looked up by account: the local
 * player's own profile, the tab list while in a world, otherwise the session service (offline accounts and lookup
 * failures keep the default skin of their id).
 */
final class Skins {
    private static final Map<UUID, Supplier<PlayerSkin>> fetched = new ConcurrentHashMap<>();
    private static Supplier<PlayerSkin> own;

    private Skins() {
    }

    static PlayerSkin of(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (id.equals(ClientLobbyState.selfId())) {
            if (own == null) own = mc.getSkinManager().lookupInsecure(mc.getGameProfile());
            return own.get();
        }
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        if (info != null) return info.getSkin();
        Supplier<PlayerSkin> skin = fetched.computeIfAbsent(id, Skins::fetch);
        return skin.get();
    }

    private static Supplier<PlayerSkin> fetch(UUID id) {
        PlayerSkin fallback = DefaultPlayerSkin.get(id);
        CompletableFuture<Supplier<PlayerSkin>> lookup = CompletableFuture.supplyAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            var result = mc.getMinecraftSessionService().fetchProfile(id, false);
            GameProfile profile = result == null ? null : result.profile();
            return profile == null ? null : mc.getSkinManager().lookupInsecure(profile);
        }, Util.backgroundExecutor()).exceptionally(error -> null);
        return () -> {
            Supplier<PlayerSkin> found = lookup.getNow(null);
            return found == null ? fallback : found.get();
        };
    }
}
