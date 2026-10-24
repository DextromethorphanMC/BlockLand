package com.blockland.client;

import com.blockland.network.packet.ActivityPackets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The lobby's activities ("活动") as the server last sent them. Client thread only. */
public final class ClientActivities {
    private static List<ActivityPackets.Activity> all = List.of();
    private static long version;

    private ClientActivities() {
    }

    static void update(ActivityPackets.S2CActivities message) {
        all = List.copyOf(message.activities());
        version++;
    }

    static void clear() {
        all = List.of();
        version++;
    }

    /** Changes whenever the list changes (for UI caches). */
    public static long version() {
        return version;
    }

    /** For the activities page: pinned first, then by the configured order, then the soonest to open. */
    public static List<ActivityPackets.Activity> forList() {
        List<ActivityPackets.Activity> list = new ArrayList<>(all);
        list.sort(Comparator.comparing((ActivityPackets.Activity a) -> !a.pinned())
                .thenComparingInt(ActivityPackets.Activity::order)
                .thenComparingLong(ActivityPackets.Activity::startsAt));
        return list;
    }

    public static Optional<ActivityPackets.Activity> find(String id) {
        return all.stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
