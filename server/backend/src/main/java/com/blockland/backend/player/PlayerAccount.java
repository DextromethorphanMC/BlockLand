package com.blockland.backend.player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Minecraft player as seen by the network (all servers).
 *
 * @param lastHeartbeat last time a server reported the player online; null after logout
 * @param ban           null when never banned (an expired restriction stays for history)
 * @param entitlements  permissions bought in the shop or granted by admins (e.g. {@code PRIORITY_QUEUE})
 * @param preferences   what the player chose in game; files written before it existed get the defaults
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
        List<Entitlement> entitlements,
        Preferences preferences
) {
    public PlayerAccount {
        entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
        preferences = preferences == null ? Preferences.DEFAULT : preferences;
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

    /**
     * Settings and state the player chose in the game lobby. Missing values (older files) fall back to the defaults.
     *
     * @param allowPartyInvites privacy: other players may invite this player to a party (隐私 → 允许被邀请组队)
     * @param readAnnouncements ids of the announcements the player marked as read (deleted ones are pruned)
     * @param builds            per weapon id, the 工匠 build (skin + attachments); opaque to the backend
     * @param cosmetics         what the player put on: slot ({@code card}, {@code graffiti.1} …) → owned item key
     */
    public record Preferences(Boolean allowPartyInvites, List<String> readAnnouncements, Map<String, WeaponBuild> builds,
                              Map<String, String> cosmetics) {
        public static final Preferences DEFAULT = new Preferences(true, List.of(), Map.of(), Map.of());

        public Preferences {
            allowPartyInvites = allowPartyInvites == null || allowPartyInvites;
            readAnnouncements = readAnnouncements == null ? List.of() : List.copyOf(readAnnouncements);
            builds = builds == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(builds));
            cosmetics = cosmetics == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(cosmetics));
        }

        Preferences withAllowPartyInvites(boolean allow) {
            return new Preferences(allow, readAnnouncements, builds, cosmetics);
        }

        Preferences withReadAnnouncements(List<String> ids) {
            return new Preferences(allowPartyInvites, ids, builds, cosmetics);
        }

        Preferences withBuilds(Map<String, WeaponBuild> map) {
            return new Preferences(allowPartyInvites, readAnnouncements, map, cosmetics);
        }

        Preferences withCosmetics(Map<String, String> map) {
            return new Preferences(allowPartyInvites, readAnnouncements, builds, map);
        }
    }

    /**
     * One weapon's 工匠 build: the skin, per slot (optic, barrel, charm …) the fitted option, and the sprays placed on
     * it (in drawing order).
     */
    public record WeaponBuild(String skin, Map<String, String> slots, List<Spray> sprays) {
        public WeaponBuild {
            slots = slots == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(slots));
            sprays = sprays == null ? List.of() : List.copyOf(sprays);
        }

        public WeaponBuild(String skin, Map<String, String> slots) {
            this(skin, slots, List.of());
        }
    }

    /**
     * A spray (喷漆) on a weapon: which one, which side, where in that side's view (u, v 0..1), its size in metres and
     * rotation in degrees. Opaque to the backend beyond its shape; the game server checks it against its data.
     */
    public record Spray(String id, String side, Float u, Float v, Float size, Float rotation) {
    }

    public boolean online(Instant now) {
        return lastHeartbeat != null && lastHeartbeat.isAfter(now.minusSeconds(90));
    }

    PlayerAccount withName(String newName, Instant now) {
        return new PlayerAccount(uuid, newName == null ? name : newName, firstSeen, now, lastHeartbeat, totalOnlineSeconds, ban, mute, entitlements, preferences);
    }

    PlayerAccount withSession(Instant heartbeat, long addSeconds) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, heartbeat, totalOnlineSeconds + Math.max(0, addSeconds), ban, mute, entitlements, preferences);
    }

    PlayerAccount withBan(Restriction restriction) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, restriction, mute, entitlements, preferences);
    }

    PlayerAccount withMute(Restriction restriction) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, ban, restriction, entitlements, preferences);
    }

    PlayerAccount withEntitlements(List<Entitlement> list) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, ban, mute, new ArrayList<>(list), preferences);
    }

    PlayerAccount withPreferences(Preferences next) {
        return new PlayerAccount(uuid, name, firstSeen, lastSeen, lastHeartbeat, totalOnlineSeconds, ban, mute, entitlements, next);
    }
}
