package com.blockland.backend.checkin;

import java.time.LocalDate;

/**
 * One player's check-ins (runtime data).
 *
 * @param lastDay the day (in the config's zone) the player last checked in
 * @param streak  days in a row up to {@code lastDay}; it only counts while the next check-in comes the following day
 * @param best    the longest streak so far
 * @param total   days checked in altogether
 */
public record CheckInRecord(String uuid, LocalDate lastDay, int streak, int best, int total) {
}
