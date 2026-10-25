package com.blockland.backend.anticheat;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anti-cheat cases (ANTICHEAT_SPEC §9): turns the evidence game servers report into one active case per player
 * (suspicion with a half-life, per-check tallies, the latest evidence), decides auto-ban requests by the global config
 * and carries out staff reviews. Bans go through {@link PlayerService#ban}; game servers kick banned players with their
 * next heartbeat. Closed cases are removed after {@code evidence.retentionDays} without an update.
 */
@Service
public class AntiCheatCaseService implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AntiCheatCaseService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$");
    private static final Pattern CATEGORY = Pattern.compile("^[a-z]{1,16}$");
    private static final Set<String> ACTIONS = Set.of("none", "setback", "cancel", "kick", "ban");
    /** Most reports one batch may carry. */
    static final int MAX_BATCH = 500;
    static final int MAX_NOTES = 200;
    private static final Duration CLEANUP_INTERVAL = Duration.ofDays(1);

    private final Repository<AntiCheatCase> cases;
    private final AntiCheatConfigStore config;
    private final PlayerService players;
    private final Clock clock;
    private Instant lastCleanup;

    @Autowired
    public AntiCheatCaseService(@Qualifier("antiCheatCases") Repository<AntiCheatCase> cases, AntiCheatConfigStore config,
                                PlayerService players) {
        this(cases, config, players, Clock.systemUTC());
    }

    AntiCheatCaseService(Repository<AntiCheatCase> cases, AntiCheatConfigStore config, PlayerService players, Clock clock) {
        this.cases = cases;
        this.config = config;
        this.players = players;
        this.clock = clock;
    }

    // ---- requests --------------------------------------------------------------------------------------

    /** One piece of evidence as a game server sends it ({@code at}: ISO-8601; missing or future = now). */
    public record ReportRequest(String uuid, String name, String modeId, String modeTitle, String mapId, String check,
                                String category, Double vl, Double confidence, Boolean review, String action, String detail,
                                Integer ping, Integer jitter, String world, Double x, Double y, Double z, String at) {
    }

    public record ReportBatch(List<ReportRequest> reports) {
    }

    public record PunishRequest(String uuid, String name, String modeId, List<String> checks, Double confidence, String detail) {
    }

    public record PunishResult(boolean banned, String caseId, String message) {
    }

    /**
     * @param decision {@code BAN}, {@code CLEAR}, {@code DISMISS} or {@code WATCH}
     * @param minutes  ban length, null or 0 = permanent
     */
    public record ReviewRequest(String decision, Integer minutes, String reason, String note) {
    }

    public record NoteRequest(String text) {
    }

    public record Counts(long open, long review, long bannedLast7Days, long reportsLast24h) {
    }

    // ---- game servers ----------------------------------------------------------------------------------

    /**
     * Adds a batch of reports to the players' active cases (opening new ones as needed).
     *
     * @return how many reports were accepted (malformed ones are skipped)
     */
    public synchronized int report(List<ReportRequest> requests) {
        if (requests == null || requests.isEmpty()) return 0;
        if (requests.size() > MAX_BATCH) throw ApiException.badRequest("一次最多上报 " + MAX_BATCH + " 条证据");
        maybeCleanup();
        AntiCheatConfig cfg = config.get();
        Instant now = clock.instant();
        Map<String, AntiCheatCase> touched = new LinkedHashMap<>();
        int accepted = 0;
        for (ReportRequest request : requests) {
            AntiCheatCase.Report report = clean(request, now);
            if (report == null) continue;
            AntiCheatCase current = touched.containsKey(report.uuid()) ? touched.get(report.uuid())
                    : active(report.uuid()).orElseGet(() -> open(report.uuid(), report.name(), now));
            touched.put(report.uuid(), add(current, report, cfg, now));
            accepted++;
        }
        cases.saveAll(List.copyOf(touched.values()));
        return accepted;
    }

    private AntiCheatCase add(AntiCheatCase c, AntiCheatCase.Report report, AntiCheatConfig cfg, Instant now) {
        double suspicion = decayed(c, cfg, now) + report.confidence();
        Map<String, AntiCheatCase.CheckStats> checks = new LinkedHashMap<>(c.checks());
        AntiCheatCase.CheckStats stats = checks.get(report.check());
        checks.put(report.check(), stats == null
                ? new AntiCheatCase.CheckStats(1, report.vl(), report.confidence(), report.at())
                : new AntiCheatCase.CheckStats(stats.count() + 1, Math.max(stats.maxVl(), report.vl()),
                Math.max(stats.maxConfidence(), report.confidence()), max(stats.lastAt(), report.at())));
        Map<String, Integer> modes = new LinkedHashMap<>(c.modes());
        if (report.modeId() != null) modes.merge(report.modeId(), 1, Integer::sum);
        List<AntiCheatCase.Report> evidence = new ArrayList<>(c.evidence());
        evidence.add(report);
        int keep = cfg.evidence().maxPerCase();
        if (evidence.size() > keep) evidence = new ArrayList<>(evidence.subList(evidence.size() - keep, evidence.size()));
        AntiCheatCase.Status status = c.status();
        // a watched case goes back to review only when a server asks for it, not by its suspicion alone
        if (status == AntiCheatCase.Status.OPEN && (report.review() || !c.watched() && suspicion >= cfg.suspicion().reviewScore())) {
            status = AntiCheatCase.Status.REVIEW;
        }
        String name = report.name() == null || report.name().isBlank() ? c.playerName() : report.name();
        return new AntiCheatCase(c.id(), c.playerUuid(), name, status, c.openedAt(), now, c.closedAt(), suspicion,
                Math.max(c.maxConfidence(), report.confidence()), c.watched(), checks, modes, evidence, c.punishments(),
                c.notes(), c.decision(), c.reviewedAt());
    }

    /**
     * An auto-ban request: carried out only when the global {@code autoBan.enabled} is on (the ban's reason ends with the
     * case number); otherwise the case goes to review with a note of the refused request.
     */
    public synchronized PunishResult punish(PunishRequest request) {
        String uuid = request == null ? null : normalizeUuid(request.uuid());
        if (uuid == null) throw ApiException.badRequest("无效的玩家 UUID");
        AntiCheatConfig cfg = config.get();
        Instant now = clock.instant();
        String name = text(request.name(), 32);
        AntiCheatCase c = active(uuid).orElseGet(() -> open(uuid, name, now));
        double confidence = clamp01(request.confidence());
        List<String> checks = request.checks() == null ? List.of() : request.checks().stream()
                .filter(id -> id != null && AntiCheatSettings.CHECK_ID.matcher(id).matches()).limit(8).toList();
        String summary = String.format(Locale.ROOT, "置信度 %.3f，检测 %s", confidence, checks.isEmpty() ? "-" : String.join("、", checks))
                + (request.detail() == null || request.detail().isBlank() ? "" : "。" + text(request.detail(), 500));
        if (!cfg.autoBan().enabled()) {
            save(withNote(asReview(c, confidence, name, now), "自动封禁请求未执行（未开启自动封禁）：" + summary, "auto", now));
            return new PunishResult(false, c.id(), "未开启自动封禁，案件已转人工复核");
        }
        String reason = cfg.autoBan().reason() + " #" + c.id();
        try {
            players.ban(uuid, reason, cfg.autoBan().durationMinutes());
        } catch (ApiException e) {
            save(withNote(asReview(c, confidence, name, now), "自动封禁失败（" + e.getMessage() + "）：" + summary, "auto", now));
            return new PunishResult(false, c.id(), e.getMessage());
        }
        List<AntiCheatCase.Punishment> punishments = new ArrayList<>(c.punishments());
        punishments.add(new AntiCheatCase.Punishment("BAN", "auto", cfg.autoBan().durationMinutes(), reason, now));
        AntiCheatCase banned = new AntiCheatCase(c.id(), c.playerUuid(), name == null || name.isBlank() ? c.playerName() : name,
                AntiCheatCase.Status.BANNED, c.openedAt(), now, now, c.suspicion(), Math.max(c.maxConfidence(), confidence), c.watched(),
                c.checks(), c.modes(), c.evidence(), punishments, c.notes(), "AUTO_BAN", c.reviewedAt());
        save(withNote(banned, "自动封禁：" + summary, "auto", now));
        LOG.info("Anti-cheat auto-ban of {} ({}), case {}: {}", banned.playerName(), uuid, c.id(), summary);
        return new PunishResult(true, c.id(), "已自动封禁");
    }

    private static AntiCheatCase asReview(AntiCheatCase c, double confidence, String name, Instant now) {
        return new AntiCheatCase(c.id(), c.playerUuid(), name == null || name.isBlank() ? c.playerName() : name,
                c.status().active() ? AntiCheatCase.Status.REVIEW : c.status(), c.openedAt(), now, c.closedAt(), c.suspicion(),
                Math.max(c.maxConfidence(), confidence), c.watched(), c.checks(), c.modes(), c.evidence(), c.punishments(),
                c.notes(), c.decision(), c.reviewedAt());
    }

    // ---- admin -----------------------------------------------------------------------------------------

    /**
     * @param status {@code ACTIVE} (default: open and review), {@code OPEN}, {@code REVIEW}, {@code CLOSED} or {@code ALL}
     * @param query  part of the player's name or uuid
     */
    public synchronized List<AntiCheatCase.Summary> list(String status, String query) {
        maybeCleanup();
        String filter = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "OPEN", "REVIEW", "CLOSED", "ALL").contains(filter)) throw ApiException.badRequest("无效的状态筛选: " + status);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return cases.findAll().stream()
                .filter(c -> switch (filter) {
                    case "ACTIVE" -> c.status().active();
                    case "OPEN" -> c.status() == AntiCheatCase.Status.OPEN;
                    case "REVIEW" -> c.status() == AntiCheatCase.Status.REVIEW;
                    case "CLOSED" -> !c.status().active();
                    default -> true;
                })
                .filter(c -> q.isEmpty() || c.playerUuid().contains(q)
                        || c.playerName() != null && c.playerName().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing((AntiCheatCase c) -> c.status() != AntiCheatCase.Status.REVIEW)
                        .thenComparing(AntiCheatCase::updatedAt, Comparator.reverseOrder()))
                .map(AntiCheatCase::summary)
                .toList();
    }

    public AntiCheatCase get(String id) {
        return cases.findById(id).orElseThrow(() -> ApiException.notFound("案件", id));
    }

    /** Every case of one player, newest first. */
    public List<AntiCheatCase.Summary> ofPlayer(String uuid) {
        String id = normalizeUuid(uuid);
        if (id == null) throw ApiException.badRequest("无效的玩家 UUID");
        return cases.findAll().stream().filter(c -> c.playerUuid().equals(id))
                .sorted(Comparator.comparing(AntiCheatCase::openedAt, Comparator.reverseOrder()))
                .map(AntiCheatCase::summary).toList();
    }

    /** A staff decision; {@code BAN} bans through {@link PlayerService#ban}, {@code WATCH} keeps (or reopens) the case. */
    public synchronized AntiCheatCase review(String id, ReviewRequest request) {
        AntiCheatCase c = get(id);
        String decision = request == null || request.decision() == null ? "" : request.decision().trim().toUpperCase(Locale.ROOT);
        Instant now = clock.instant();
        AntiCheatCase.Status status;
        Instant closedAt = now;
        boolean watched = c.watched();
        List<AntiCheatCase.Punishment> punishments = new ArrayList<>(c.punishments());
        switch (decision) {
            case "BAN" -> {
                int minutes = request.minutes() == null ? 0 : request.minutes();
                if (minutes < 0) throw ApiException.badRequest("封禁时长不能为负数");
                String reason = (request.reason() == null || request.reason().isBlank() ? "反作弊复核：确认作弊" : text(request.reason(), 200))
                        + " #" + c.id();
                players.ban(c.playerUuid(), reason, minutes);
                punishments.add(new AntiCheatCase.Punishment("BAN", "admin", minutes, reason, now));
                status = AntiCheatCase.Status.BANNED;
            }
            case "CLEAR" -> status = AntiCheatCase.Status.CLEARED;
            case "DISMISS" -> status = AntiCheatCase.Status.DISMISSED;
            case "WATCH" -> {
                if (!c.status().active() && active(c.playerUuid()).isPresent()) {
                    throw ApiException.conflict("该玩家已有进行中的案件，不能重新打开这个案件");
                }
                status = AntiCheatCase.Status.OPEN;
                closedAt = null;
                watched = true;
            }
            default -> throw ApiException.badRequest("处理决定只能是 BAN、CLEAR、DISMISS 或 WATCH");
        }
        AntiCheatCase next = new AntiCheatCase(c.id(), c.playerUuid(), c.playerName(), status, c.openedAt(), now, closedAt,
                c.suspicion(), c.maxConfidence(), watched, c.checks(), c.modes(), c.evidence(), punishments, c.notes(), decision, now);
        if (request.note() != null && !request.note().isBlank()) next = withNote(next, text(request.note(), 1000), "admin", now);
        return save(next);
    }

    public synchronized AntiCheatCase note(String id, String text) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("备注不能为空");
        AntiCheatCase c = get(id);
        if (c.notes().size() >= MAX_NOTES) throw ApiException.badRequest("备注不能超过 " + MAX_NOTES + " 条");
        return save(withNote(c, text(text, 1000), "admin", clock.instant()));
    }

    public synchronized void delete(String id) {
        if (!cases.deleteById(id)) throw ApiException.notFound("案件", id);
    }

    public Counts counts() {
        Instant now = clock.instant();
        Instant week = now.minus(Duration.ofDays(7)), day = now.minus(Duration.ofDays(1));
        long open = 0, review = 0, banned = 0, reports = 0;
        for (AntiCheatCase c : cases.findAll()) {
            if (c.status() == AntiCheatCase.Status.OPEN) open++;
            if (c.status() == AntiCheatCase.Status.REVIEW) review++;
            if (c.status() == AntiCheatCase.Status.BANNED && c.closedAt() != null && c.closedAt().isAfter(week)) banned++;
            for (AntiCheatCase.Report report : c.evidence()) if (report.at() != null && report.at().isAfter(day)) reports++;
        }
        return new Counts(open, review, banned, reports);
    }

    // ---- retention -------------------------------------------------------------------------------------

    @Override
    public void run(ApplicationArguments args) {
        int removed = cleanup();
        if (removed > 0) LOG.info("Removed {} closed anti-cheat case(s) past their retention", removed);
    }

    /** Removes closed cases not updated for {@code evidence.retentionDays}; returns how many. */
    public synchronized int cleanup() {
        Instant now = clock.instant();
        lastCleanup = now;
        Instant before = now.minus(Duration.ofDays(config.get().evidence().retentionDays()));
        int removed = 0;
        for (AntiCheatCase c : cases.findAll()) {
            if (!c.status().active() && c.updatedAt().isBefore(before) && cases.deleteById(c.id())) removed++;
        }
        return removed;
    }

    /** Scheduling is not enabled in this application: cleanup runs once a day on the next use instead. */
    private void maybeCleanup() {
        if (lastCleanup == null || clock.instant().isAfter(lastCleanup.plus(CLEANUP_INTERVAL))) cleanup();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private Optional<AntiCheatCase> active(String uuid) {
        return cases.findAll().stream().filter(c -> c.playerUuid().equals(uuid) && c.status().active()).findFirst();
    }

    private AntiCheatCase open(String uuid, String name, Instant now) {
        String id;
        do {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (cases.findById(id).isPresent());
        return new AntiCheatCase(id, uuid, name == null || name.isBlank() ? uuid : name, AntiCheatCase.Status.OPEN, now, now, null,
                0, 0, false, Map.of(), Map.of(), List.of(), List.of(), List.of(), null, null);
    }

    private AntiCheatCase save(AntiCheatCase c) {
        return cases.save(c);
    }

    private static AntiCheatCase withNote(AntiCheatCase c, String text, String by, Instant now) {
        List<AntiCheatCase.Note> notes = new ArrayList<>(c.notes());
        notes.add(new AntiCheatCase.Note(text, by, now));
        while (notes.size() > MAX_NOTES) notes.remove(0);
        return new AntiCheatCase(c.id(), c.playerUuid(), c.playerName(), c.status(), c.openedAt(), now, c.closedAt(), c.suspicion(),
                c.maxConfidence(), c.watched(), c.checks(), c.modes(), c.evidence(), c.punishments(), notes, c.decision(), c.reviewedAt());
    }

    /** The case's suspicion carried to {@code now}: halved every {@code halfLifeDays} since its last update. */
    static double decayed(AntiCheatCase c, AntiCheatConfig cfg, Instant now) {
        double days = Math.max(0, Duration.between(c.updatedAt(), now).toMillis() / 86_400_000.0);
        return c.suspicion() * Math.pow(0.5, days / cfg.suspicion().halfLifeDays());
    }

    /** A report with every field checked and trimmed; null when it cannot be used (no player or check). */
    private static AntiCheatCase.Report clean(ReportRequest r, Instant now) {
        if (r == null) return null;
        String uuid = normalizeUuid(r.uuid());
        if (uuid == null || r.check() == null || !AntiCheatSettings.CHECK_ID.matcher(r.check()).matches()) return null;
        String category = r.category() != null && CATEGORY.matcher(r.category()).matches() ? r.category() : "other";
        String action = r.action() != null && ACTIONS.contains(r.action()) ? r.action() : "none";
        double vl = r.vl() != null && Double.isFinite(r.vl()) ? Math.max(0, Math.min(1_000_000, r.vl())) : 0;
        return new AntiCheatCase.Report(uuid, text(r.name(), 32), text(r.modeId(), 48), text(r.modeTitle(), 64), text(r.mapId(), 48),
                r.check(), category, vl, clamp01(r.confidence()), Boolean.TRUE.equals(r.review()), action, text(r.detail(), 500),
                r.ping() == null ? 0 : Math.max(0, r.ping()), r.jitter() == null ? 0 : Math.max(0, r.jitter()), text(r.world(), 64),
                finite(r.x()), finite(r.y()), finite(r.z()), time(r.at(), now));
    }

    private static Instant time(String value, Instant now) {
        if (value == null || value.isBlank()) return now;
        try {
            Instant at = Instant.parse(value.trim());
            return at.isAfter(now.plusSeconds(60)) ? now : at;
        } catch (DateTimeParseException e) {
            return now;
        }
    }

    static String normalizeUuid(String uuid) {
        if (uuid == null) return null;
        String id = uuid.trim().toLowerCase(Locale.ROOT);
        return UUID_PATTERN.matcher(id).matches() ? id : null;
    }

    private static double clamp01(Double value) {
        return value == null || !Double.isFinite(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    private static Double finite(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    /** Trimmed and cut to {@code maxLength}; null when missing or blank. */
    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }
}
