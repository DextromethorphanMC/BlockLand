package com.blockland.backend.player;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Player accounts: online time accounting (servers report seconds played), bans, mutes, entitlements and the
 * preferences players choose in game (privacy, read announcements, weapon builds).
 * Other modules (e.g. the shop) grant entitlements through {@link #grant}; they never touch accounts directly.
 * Every read-modify-write is synchronized so concurrent requests (a heartbeat and a settings change) never
 * overwrite each other's changes.
 */
@Service
public class PlayerService {
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{32,36}$");
    /** Weapon, skin, slot and option ids of a build. */
    private static final Pattern BUILD_ID = Pattern.compile("^[a-z0-9_]{1,48}$");
    static final int MAX_BUILD_SLOTS = 16;
    /** Sprays one build may carry (the game's wire limit; its data allows at most this). */
    static final int MAX_BUILD_SPRAYS = 8;
    static final int MAX_BUILDS = 64;

    private final Repository<PlayerAccount> accounts;

    public PlayerService(@Qualifier("playerAccounts") Repository<PlayerAccount> accounts) {
        this.accounts = accounts;
    }

    /**
     * What a Minecraft server needs to know about a player.
     *
     * @param allowPartyInvites whether other players may invite this player to a party
     * @param readAnnouncements ids of existing announcements the player has read
     * @param builds            per weapon id, the player's 工匠 build
     */
    public record PlayerStatus(String uuid, String name, long totalOnlineSeconds,
                               PlayerAccount.Restriction ban, PlayerAccount.Restriction mute,
                               List<PlayerAccount.Entitlement> entitlements,
                               boolean allowPartyInvites, List<String> readAnnouncements,
                               Map<String, PlayerAccount.WeaponBuild> builds) {
    }

    /** @param seconds online time since the previous report for this player */
    public record SessionReport(String uuid, String name, long seconds) {
    }

    // ---- called by Minecraft servers ----------------------------------------------------------

    public synchronized PlayerStatus login(String uuid, String name) {
        Instant now = Instant.now();
        PlayerAccount account = find(uuid).map(a -> a.withName(name, now))
                .orElseGet(() -> newAccount(uuid, name, now))
                .withSession(now, 0);
        accounts.save(account);
        return status(account, now);
    }

    public synchronized List<PlayerStatus> heartbeat(List<SessionReport> reports) {
        Instant now = Instant.now();
        List<PlayerAccount> updated = new ArrayList<>();
        for (SessionReport report : reports) {
            if (report.uuid() == null || !UUID_PATTERN.matcher(report.uuid()).matches()) continue;
            PlayerAccount account = find(report.uuid()).map(a -> a.withName(report.name(), now))
                    .orElseGet(() -> newAccount(report.uuid(), report.name(), now));
            // a single report can never be longer than the reporting interval allows
            updated.add(account.withSession(now, Math.min(report.seconds(), 600)));
        }
        accounts.saveAll(updated);
        return updated.stream().map(a -> status(a, now)).toList();
    }

    public synchronized void logout(SessionReport report) {
        Instant now = Instant.now();
        find(report.uuid()).ifPresent(a -> accounts.save(a.withName(report.name(), now).withSession(null, Math.min(report.seconds(), 600))));
    }

    /** Privacy setting from the lobby: whether other players may invite this player to a party. */
    public synchronized PlayerStatus setAllowPartyInvites(String uuid, boolean allow) {
        PlayerAccount account = require(uuid);
        return saveStatus(account.withPreferences(account.preferences().withAllowPartyInvites(allow)));
    }

    /**
     * Marks announcements as read. The caller (the announcement module) passes only ids of announcements that
     * exist; ids already read are kept once.
     */
    public synchronized PlayerStatus addReadAnnouncements(String uuid, Collection<String> ids) {
        PlayerAccount account = require(uuid);
        Set<String> read = new LinkedHashSet<>(account.preferences().readAnnouncements());
        if (!read.addAll(ids)) return status(account, Instant.now());
        return saveStatus(account.withPreferences(account.preferences().withReadAnnouncements(List.copyOf(read))));
    }

    /** An announcement was deleted: it disappears from every player's read list. */
    public synchronized void forgetAnnouncement(String id) {
        List<PlayerAccount> changed = new ArrayList<>();
        for (PlayerAccount account : accounts.findAll()) {
            List<String> read = account.preferences().readAnnouncements();
            if (!read.contains(id)) continue;
            List<String> kept = read.stream().filter(r -> !r.equals(id)).toList();
            changed.add(account.withPreferences(account.preferences().withReadAnnouncements(kept)));
        }
        accounts.saveAll(changed);
    }

    /**
     * Replaces the player's 工匠 build for one weapon, or removes it when {@code build} is null. The backend only
     * checks the shape (ids, sizes); whether the attachments fit the weapon is the game server's business.
     */
    public synchronized PlayerStatus saveBuild(String uuid, String weapon, PlayerAccount.WeaponBuild build) {
        PlayerAccount account = require(uuid);
        List<String> errors = new ArrayList<>();
        checkBuildId(weapon, "武器 ID", errors);
        if (build != null) {
            checkBuildId(build.skin(), "皮肤 ID", errors);
            if (build.slots().size() > MAX_BUILD_SLOTS) errors.add("配件槽位不能超过 " + MAX_BUILD_SLOTS + " 个");
            build.slots().forEach((slot, option) -> {
                checkBuildId(slot, "配件槽位", errors);
                checkBuildId(option, "配件", errors);
            });
            if (build.sprays().size() > MAX_BUILD_SPRAYS) errors.add("喷漆不能超过 " + MAX_BUILD_SPRAYS + " 个");
            for (PlayerAccount.Spray spray : build.sprays()) checkSpray(spray, errors);
        }
        Map<String, PlayerAccount.WeaponBuild> builds = new LinkedHashMap<>(account.preferences().builds());
        if (build == null) builds.remove(weapon);
        else builds.put(weapon, build);
        if (builds.size() > MAX_BUILDS) errors.add("最多保存 " + MAX_BUILDS + " 把武器的改装");
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        return saveStatus(account.withPreferences(account.preferences().withBuilds(builds)));
    }

    // ---- admin ------------------------------------------------------------------------------------

    public record AccountView(@JsonUnwrapped PlayerAccount account, boolean online, boolean banned, boolean muted) {
    }

    public List<AccountView> list(String query) {
        Instant now = Instant.now();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return accounts.findAll().stream()
                .filter(a -> q.isEmpty() || a.name().toLowerCase(Locale.ROOT).contains(q) || a.uuid().contains(q))
                .sorted(Comparator.comparing((PlayerAccount a) -> !a.online(now))
                        .thenComparing(PlayerAccount::lastSeen, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(a -> view(a, now))
                .toList();
    }

    public AccountView get(String uuid) {
        return view(require(uuid), Instant.now());
    }

    public synchronized AccountView ban(String uuid, String reason, Integer minutes) {
        PlayerAccount account = require(uuid);
        return save(account.withBan(restriction(reason, minutes, "封禁")));
    }

    public synchronized AccountView unban(String uuid) {
        return save(require(uuid).withBan(null));
    }

    public synchronized AccountView mute(String uuid, String reason, Integer minutes) {
        PlayerAccount account = require(uuid);
        return save(account.withMute(restriction(reason, minutes, "禁言")));
    }

    public synchronized AccountView unmute(String uuid) {
        return save(require(uuid).withMute(null));
    }

    /**
     * Grants (or extends) an entitlement. A timed grant on top of an active timed one extends it;
     * a permanent grant replaces it.
     *
     * @param days null or 0 = permanent
     */
    public synchronized AccountView grant(String uuid, String key, Integer days, String source) {
        PlayerAccount account = require(uuid);
        Instant now = Instant.now();
        String normalizedKey = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (normalizedKey.isEmpty()) throw ApiException.badRequest("权益标识不能为空");
        List<PlayerAccount.Entitlement> list = new ArrayList<>();
        PlayerAccount.Entitlement existing = null;
        for (PlayerAccount.Entitlement e : account.entitlements()) {
            if (e.key().equals(normalizedKey)) existing = e;
            else list.add(e);
        }
        Instant until;
        if (days == null || days <= 0 || (existing != null && existing.until() == null && existing.active(now))) {
            until = null;
        } else {
            Instant base = existing != null && existing.active(now) ? existing.until() : now;
            until = base.plus(Duration.ofDays(days));
        }
        list.add(new PlayerAccount.Entitlement(normalizedKey, until, source, now));
        return save(account.withEntitlements(list));
    }

    public synchronized AccountView revoke(String uuid, String key) {
        PlayerAccount account = require(uuid);
        return save(account.withEntitlements(account.entitlements().stream()
                .filter(e -> !e.key().equalsIgnoreCase(key)).toList()));
    }

    public Optional<PlayerStatus> status(String uuid) {
        return find(uuid).map(a -> status(a, Instant.now()));
    }

    public int count() {
        return accounts.findAll().size();
    }

    public long onlineCount() {
        Instant now = Instant.now();
        return accounts.findAll().stream().filter(a -> a.online(now)).count();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private PlayerAccount newAccount(String uuid, String name, Instant now) {
        return new PlayerAccount(normalize(uuid), name, now, now, null, 0, null, null, List.of(), PlayerAccount.Preferences.DEFAULT);
    }

    private PlayerStatus status(PlayerAccount a, Instant now) {
        PlayerAccount.Preferences preferences = a.preferences();
        return new PlayerStatus(a.uuid(), a.name(), a.totalOnlineSeconds(),
                a.ban() != null && a.ban().active(now) ? a.ban() : null,
                a.mute() != null && a.mute().active(now) ? a.mute() : null,
                a.entitlements().stream().filter(e -> e.active(now)).toList(),
                preferences.allowPartyInvites(), preferences.readAnnouncements(), preferences.builds());
    }

    private AccountView view(PlayerAccount a, Instant now) {
        return new AccountView(a, a.online(now), a.ban() != null && a.ban().active(now), a.mute() != null && a.mute().active(now));
    }

    private AccountView save(PlayerAccount account) {
        accounts.save(account);
        return view(account, Instant.now());
    }

    private PlayerStatus saveStatus(PlayerAccount account) {
        accounts.save(account);
        return status(account, Instant.now());
    }

    private static void checkBuildId(String id, String field, List<String> errors) {
        if (id == null || !BUILD_ID.matcher(id).matches()) {
            errors.add(field + " 只能包含小写字母、数字和下划线（1-48 位）: " + id);
        }
    }

    /** The shape of a spray only (the game server checks it against its spray data and limits). */
    private static void checkSpray(PlayerAccount.Spray spray, List<String> errors) {
        if (spray == null) {
            errors.add("喷漆不能为空");
            return;
        }
        checkBuildId(spray.id(), "喷漆 ID", errors);
        if (!"left".equals(spray.side()) && !"right".equals(spray.side())) errors.add("喷漆位置只能是 left 或 right: " + spray.side());
        if (!within(spray.u(), 0, 1) || !within(spray.v(), 0, 1)) errors.add("喷漆坐标必须在 0 到 1 之间");
        if (!within(spray.size(), 0.001, 0.5)) errors.add("喷漆尺寸无效: " + spray.size());
        if (!within(spray.rotation(), -360, 360)) errors.add("喷漆角度无效: " + spray.rotation());
    }

    private static boolean within(Float value, double min, double max) {
        return value != null && Float.isFinite(value) && value >= min && value <= max;
    }

    private static PlayerAccount.Restriction restriction(String reason, Integer minutes, String what) {
        Instant now = Instant.now();
        String text = reason == null || reason.isBlank() ? "违反服务器规则" : reason.trim();
        if (minutes != null && minutes < 0) throw ApiException.badRequest(what + "时长不能为负数");
        return new PlayerAccount.Restriction(text, minutes == null || minutes == 0 ? null : now.plus(Duration.ofMinutes(minutes)), now);
    }

    private Optional<PlayerAccount> find(String uuid) {
        return uuid == null ? Optional.empty() : accounts.findById(normalize(uuid));
    }

    private PlayerAccount require(String uuid) {
        return find(uuid).orElseThrow(() -> ApiException.notFound("玩家", uuid));
    }

    private static String normalize(String uuid) {
        return uuid.toLowerCase(Locale.ROOT);
    }
}
