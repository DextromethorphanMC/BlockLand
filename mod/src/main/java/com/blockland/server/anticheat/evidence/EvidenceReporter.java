package com.blockland.server.anticheat.evidence;

import com.blockland.BlockLand;
import com.blockland.server.anticheat.check.CheckCatalog;
import com.blockland.server.backend.BackendClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the anti-cheat's evidence to the backend (anti-cheat spec §9): reports are queued on the server thread and go
 * out in one batch every few seconds, off the server thread; a failed batch is tried once more with the next one and
 * then dropped (the log keeps it). Also publishes the check catalogue and asks for automatic bans.
 */
public final class EvidenceReporter {
    private static final int FLUSH_TICKS = 100;
    /** More than this waiting (backend unreachable for long): the oldest go. */
    private static final int MAX_QUEUED = 2000;

    /** One piece of evidence, as the backend takes it. */
    public record Report(String uuid, String name, String modeId, String modeTitle, String mapId, String check, String category,
                         double vl, double confidence, boolean review, String action, String detail, int ping, int jitter,
                         String world, double x, double y, double z, String at) {
    }

    /** The backend's answer to a ban request. */
    public static final class PunishResult {
        public boolean banned;
        public String caseId;
        public String message;
    }

    private record PunishRequest(String uuid, String name, String modeId, List<String> checks, double confidence, String detail) {
    }

    private final BackendClient backend;
    private final List<Report> queue = new ArrayList<>();
    private volatile List<Report> retry = List.of();
    private int ticks;

    public EvidenceReporter(BackendClient backend) {
        this.backend = backend;
    }

    public static String now() {
        return Instant.now().toString();
    }

    public void add(Report report) {
        queue.add(report);
        if (queue.size() > MAX_QUEUED) queue.remove(0);
    }

    public int queued() {
        return queue.size() + retry.size();
    }

    public void tick() {
        if (++ticks % FLUSH_TICKS != 0) return;
        flush();
    }

    public void flush() {
        if (queue.isEmpty() && retry.isEmpty()) return;
        List<Report> again = retry;
        List<Report> batch = new ArrayList<>(again);
        batch.addAll(queue);
        queue.clear();
        retry = List.of();
        backend.post("/api/server/anticheat/reports", Map.of("reports", batch), Void.class).whenComplete((v, error) -> {
            if (error == null) return;
            if (again.isEmpty()) {
                BlockLand.LOGGER.warn("[BlockLand] Anti-cheat evidence not sent ({} reports), retrying: {}", batch.size(), error.getMessage());
                // one more try with the next flush (picked up by the server thread)
                retry = batch;
            } else {
                BlockLand.LOGGER.warn("[BlockLand] Anti-cheat evidence dropped ({} reports): {}", batch.size(), error.getMessage());
            }
        });
    }

    public void publishRegistry() {
        backend.post("/api/server/registry/anticheat", CheckCatalog.get().registry(), Void.class).whenComplete((v, error) -> {
            if (error != null) BlockLand.LOGGER.warn("[BlockLand] Anti-cheat registry not published: {}", error.getMessage());
            else BlockLand.LOGGER.info("[BlockLand] Anti-cheat registry published: {} checks", CheckCatalog.get().size());
        });
    }

    /** Asks the backend to ban; it decides (its global auto-ban switch) and answers. Completes off the server thread. */
    public CompletableFuture<PunishResult> requestBan(String uuid, String name, String modeId, List<String> checks, double confidence,
                                                      String detail) {
        return backend.post("/api/server/anticheat/punish", new PunishRequest(uuid, name, modeId, checks, confidence, detail),
                PunishResult.class);
    }
}
