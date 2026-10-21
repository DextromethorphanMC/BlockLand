package com.blockland.backend.player;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Player accounts: online time accounting (servers report seconds played), bans, mutes and entitlements.
 * Other modules (e.g. the shop) grant entitlements through {@link #grant}; they never touch accounts directly.
 */
@Service
public class PlayerService {
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{32,36}$");

    private final Repository<PlayerAccount> accounts;

    public PlayerService(@Qualifier("playerAccounts") Repository<PlayerAccount> accounts) {
        this.accounts = accounts;
    }

    /** What a Minecraft server needs to know about a player. */
    public record PlayerStatus(String uuid, String name, long totalOnlineSeconds,
                               PlayerAccount.Restriction ban, PlayerAccount.Restriction mute,
                               List<PlayerAccount.Entitlement> entitlements) {
    }

    /** @param seconds online time since the previous report for this player */
    public record SessionReport(String uuid, String name, long seconds) {
    }

    // ---- called by Minecraft servers ----------------------------------------------------------

    public PlayerStatus login(String uuid, String name) {
        Instant now = Instant.now();
        PlayerAccount account = find(uuid).map(a -> a.withName(name, now))
                .orElseGet(() -> new PlayerAccount(normalize(uuid), name, now, now, null, 0, null, null, List.of()))
                .withSession(now, 0);
        accounts.save(account);
        return status(account, now);
    }

    public List<PlayerStatus> heartbeat(List<SessionReport> reports) {
        Instant now = Instant.now();
        List<PlayerAccount> updated = new ArrayList<>();
        for (SessionReport report : reports) {
            if (report.uuid() == null || !UUID_PATTERN.matcher(report.uuid()).matches()) continue;
            PlayerAccount account = find(report.uuid()).map(a -> a.withName(report.name(), now))
                    .orElseGet(() -> new PlayerAccount(normalize(report.uuid()), report.name(), now, now, null, 0, null, null, List.of()));
            // a single report can never be longer than the reporting interval allows
            updated.add(account.withSession(now, Math.min(report.seconds(), 600)));
        }
        accounts.saveAll(updated);
        return updated.stream().map(a -> status(a, now)).toList();
    }

    public void logout(SessionReport report) {
        Instant now = Instant.now();
        find(report.uuid()).ifPresent(a -> accounts.save(a.withName(report.name(), now).withSession(null, Math.min(report.seconds(), 600))));
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

    public AccountView ban(String uuid, String reason, Integer minutes) {
        PlayerAccount account = require(uuid);
        return save(account.withBan(restriction(reason, minutes, "封禁")));
    }

    public AccountView unban(String uuid) {
        return save(require(uuid).withBan(null));
    }

    public AccountView mute(String uuid, String reason, Integer minutes) {
        PlayerAccount account = require(uuid);
        return save(account.withMute(restriction(reason, minutes, "禁言")));
    }

    public AccountView unmute(String uuid) {
        return save(require(uuid).withMute(null));
    }

    /**
     * Grants (or extends) an entitlement. A timed grant on top of an active timed one extends it;
     * a permanent grant replaces it.
     *
     * @param days null or 0 = permanent
     */
    public AccountView grant(String uuid, String key, Integer days, String source) {
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

    public AccountView revoke(String uuid, String key) {
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

    private PlayerStatus status(PlayerAccount a, Instant now) {
        return new PlayerStatus(a.uuid(), a.name(), a.totalOnlineSeconds(),
                a.ban() != null && a.ban().active(now) ? a.ban() : null,
                a.mute() != null && a.mute().active(now) ? a.mute() : null,
                a.entitlements().stream().filter(e -> e.active(now)).toList());
    }

    private AccountView view(PlayerAccount a, Instant now) {
        return new AccountView(a, a.online(now), a.ban() != null && a.ban().active(now), a.mute() != null && a.mute().active(now));
    }

    private AccountView save(PlayerAccount account) {
        accounts.save(account);
        return view(account, Instant.now());
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
