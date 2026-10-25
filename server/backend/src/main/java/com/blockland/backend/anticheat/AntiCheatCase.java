package com.blockland.backend.anticheat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the anti-cheat gathered on one player in one stretch of suspicion (ANTICHEAT_SPEC §9): the evidence the
 * game servers reported, per-check and per-mode tallies, the accumulated suspicion, punishments and staff notes. A
 * player has at most one active case ({@link Status#OPEN} or {@link Status#REVIEW}); a decision closes it and the next
 * report opens a new one.
 *
 * @param suspicion every report adds its confidence; the total halves every {@code suspicion.halfLifeDays}
 * @param watched   staff chose to keep watching: suspicion alone no longer sends the case to review
 * @param evidence  the latest reports, oldest first, at most {@code evidence.maxPerCase}
 * @param decision  the last review decision ({@code BAN}, {@code CLEAR}, {@code DISMISS}, {@code WATCH}, {@code AUTO_BAN})
 */
public record AntiCheatCase(String id, String playerUuid, String playerName, Status status, Instant openedAt, Instant updatedAt,
                            Instant closedAt, double suspicion, double maxConfidence, boolean watched,
                            Map<String, CheckStats> checks, Map<String, Integer> modes, List<Report> evidence,
                            List<Punishment> punishments, List<Note> notes, String decision, Instant reviewedAt) {
    public AntiCheatCase {
        checks = checks == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        modes = modes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(modes));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        punishments = punishments == null ? List.of() : List.copyOf(punishments);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public enum Status {
        /** Collecting evidence. */
        OPEN,
        /** Waiting for a staff decision. */
        REVIEW,
        BANNED,
        /** Staff confirmed a false positive. */
        CLEARED,
        /** Closed without a verdict. */
        DISMISSED;

        public boolean active() {
            return this == OPEN || this == REVIEW;
        }
    }

    /** How often a check flagged on this case, and how badly. */
    public record CheckStats(int count, double maxVl, double maxConfidence, Instant lastAt) {
    }

    /**
     * One report of a game server, as sent (§9 {@code POST /api/server/anticheat/reports}).
     *
     * @param action {@code none}, {@code setback}, {@code cancel}, {@code kick} or {@code ban}
     */
    public record Report(String uuid, String name, String modeId, String modeTitle, String mapId, String check, String category,
                         double vl, double confidence, boolean review, String action, String detail, int ping, int jitter,
                         String world, Double x, Double y, Double z, Instant at) {
    }

    /**
     * @param type    {@code BAN} or {@code KICK}
     * @param by      {@code auto} or {@code admin}
     * @param minutes 0 = permanent
     */
    public record Punishment(String type, String by, int minutes, String reason, Instant at) {
    }

    public record Note(String text, String by, Instant at) {
    }

    /** The list view: the case without its evidence. */
    public record Summary(String id, String playerUuid, String playerName, Status status, Instant openedAt, Instant updatedAt,
                          Instant closedAt, double suspicion, double maxConfidence, boolean watched,
                          Map<String, CheckStats> checks, Map<String, Integer> modes, int evidenceCount,
                          List<Punishment> punishments, List<Note> notes, String decision, Instant reviewedAt) {
    }

    public Summary summary() {
        return new Summary(id, playerUuid, playerName, status, openedAt, updatedAt, closedAt, suspicion, maxConfidence, watched,
                checks, modes, evidence.size(), punishments, notes, decision, reviewedAt);
    }
}
