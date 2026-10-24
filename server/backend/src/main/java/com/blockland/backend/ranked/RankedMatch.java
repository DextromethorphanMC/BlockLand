package com.blockland.backend.ranked;

import java.time.Instant;
import java.util.List;

/**
 * A ranked match whose result was counted, kept so a server reporting it again (a retry) gets the same answer
 * instead of a second change.
 */
public record RankedMatch(String matchId, String modeId, int season, Instant at, List<RankedService.Change> changes) {
    public RankedMatch {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
