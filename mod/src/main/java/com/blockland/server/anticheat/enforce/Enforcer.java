package com.blockland.server.anticheat.enforce;

import com.blockland.BlockLand;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.server.anticheat.check.CheckCatalog;
import com.blockland.server.anticheat.evidence.EvidenceReporter;
import com.blockland.server.anticheat.track.PlayerTrack;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * What the anti-cheat does to players (anti-cheat spec §8) besides undoing moves: kicks, automatic ban requests (the
 * backend decides and records them), and alerts for the operators online, who may switch their own alerts off. Works
 * by player id through {@link PlayerDirectory}, so it reaches players in the lobby as well as in the world.
 */
public final class Enforcer {
    private final MinecraftServer server;
    private final PlayerDirectory players;
    private final EvidenceReporter reporter;
    private final Set<UUID> alertsOff = new HashSet<>();

    public Enforcer(MinecraftServer server, PlayerDirectory players, EvidenceReporter reporter) {
        this.server = server;
        this.players = players;
        this.reporter = reporter;
    }

    /** A check's name for players and operators (translated; the catalogue's name when the language lacks it). */
    public static Component checkName(int check) {
        CheckCatalog.Entry entry = check >= 0 && check < CheckCatalog.get().size() ? CheckCatalog.get().at(check) : null;
        if (entry == null) return Component.literal("?");
        return Component.translatableWithFallback("blockland.anticheat.check." + entry.id(), entry.name());
    }

    public void kick(UUID player, int check) {
        players.kick(player, Component.translatable("blockland.anticheat.kick", checkName(check)));
    }

    /** Tells every operator online who wants alerts. */
    public void alert(String name, int check, double vl, double confidence, double rttMs, Component mode) {
        Component message = Component.translatable("blockland.anticheat.alert", name, checkName(check),
                String.format(Locale.ROOT, "%.1f", vl), String.format(Locale.ROOT, "%.0f%%", confidence * 100), (int) Math.round(rttMs), mode);
        for (UUID staff : players.online()) {
            if (players.permissionLevel(staff) >= 2 && !alertsOff.contains(staff)) players.notify(staff, LobbyPackets.Level.WARNING, message);
        }
    }

    /** Switches an operator's alerts; returns whether they are on now. */
    public boolean toggleAlerts(UUID staff) {
        if (alertsOff.remove(staff)) return true;
        alertsOff.add(staff);
        return false;
    }

    /** Asks the backend for a ban; a granted one disconnects the player at once. */
    public void requestBan(PlayerTrack track, UUID player, String name, String modeId, List<String> checks, double confidence, String detail) {
        reporter.requestBan(player.toString(), name, modeId, checks, confidence, detail).whenComplete((result, error) -> server.execute(() -> {
            if (error != null || result == null) {
                BlockLand.LOGGER.warn("[BlockLand] Anti-cheat ban request for {} failed: {}", name, error == null ? "no answer" : error.getMessage());
                // asked again at the next decisive violation
                track.banRequested = false;
                return;
            }
            BlockLand.LOGGER.info("[BlockLand] Anti-cheat ban request for {} ({} %): banned={} case={} {}", name,
                    Math.round(confidence * 100), result.banned, result.caseId, result.message);
            if (result.banned) players.kick(player, Component.translatable("blockland.anticheat.banned", result.caseId == null ? "" : result.caseId));
        }));
    }

    public void forget(UUID player) {
        alertsOff.remove(player);
    }
}
