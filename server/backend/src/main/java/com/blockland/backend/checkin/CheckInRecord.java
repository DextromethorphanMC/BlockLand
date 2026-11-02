package com.blockland.backend.checkin;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.LocalDate;
import java.util.List;

/**
 * One player's check-ins (runtime data).
 *
 * @param lastDay  the day (in the config's zone) the player last checked in; null after an admin cleared the record
 * @param streak   days in a row up to {@code lastDay}; it only counts while the next check-in comes the following day
 * @param best     the longest streak so far
 * @param total    days checked in altogether
 * @param paidOnce the one-time milestones ({@link CheckInConfig.Milestone#id}) already paid; repeating and date ones are
 *                 kept from paying twice by the once-a-day check-in itself
 */
public record CheckInRecord(String uuid, LocalDate lastDay, int streak, int best, int total, List<String> paidOnce) {
    @JsonCreator
    public CheckInRecord {
        paidOnce = paidOnce == null ? List.of() : List.copyOf(paidOnce);
    }

    public CheckInRecord(String uuid, LocalDate lastDay, int streak, int best, int total) {
        this(uuid, lastDay, streak, best, total, List.of());
    }
}
