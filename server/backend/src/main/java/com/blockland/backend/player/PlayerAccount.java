package com.blockland.backend.player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Minecraft player as seen by the network (all servers).
 *
 * @param lastHeartbeat last time a server reported the player online; null after logout
 * @param ban           null when never banned (an expired restriction stays for history)
 * @param entitlements  permissions bought in the shop or granted by admins (e.g. {@code PRIORITY_QUEUE})
 */
public record PlayerAccount(
        String uuid,
        String name,
        Instant firstSeen,
        Instant lastSeen,
        Instant lastHeartbeat,
        long totalOnlineSeconds,
        Restriction ban,
        Restriction mute,
        List<Entitlement> entitlements
) {
    public PlayerAccount {
        entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
    }

    /** @param until null = permanent */
    public record Restriction(String reason, Instant until, Instant createdAt) {
        public boolean active(Instant now) {
            return until == null || until.isAfter(now);
        }
    }

    /** @param until null = permanent */
    public record Entitlement(String key, Instant until, String source, Instant grantedAt) {
        public boolean active(Instant now) {
            return until == null || until.isAfter(now);
        }
    }

    public boolean online(Instant now) {
        return lastHeartbeat != null && lastHeartbeat.isAfter(now.minusSeconds(90));
    }

    PlayerAccount withName(String newName, Instant now) {
        return new PlayerAccount(uuid, newName == null ? name : newName, firstSeen, now, lastHeartbeat, totalOnlineSeconds, ban, mute, entitlements);
    }

    PlayerAccount withSession(Instant heartbeat, long addSeconds) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, heartbeat, totalOnlineSeconds + Math.max(0, addSeconds), ban, mute, entitlements);
    }

    PlayerAccount withBan(Restriction restriction) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, restriction, mute, entitlements);
    }

    PlayerAccount withMute(Restriction restriction) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, ban, restriction, entitlements);
    }

    PlayerAccount withEntitlements(List<Entitlement> list) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, ban, mute, new ArrayList<>(list));
    }
}
