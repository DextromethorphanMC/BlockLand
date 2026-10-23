package com.blockland.client;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Announcements from the server and which of them this player has read. The read state lives on the server (it
 * follows the account); marking something read updates this copy at once and tells the server. Client thread only.
 */
public final class ClientAnnouncements {
    private static List<LobbyPackets.Announcement> all = List.of();
    private static final Set<String> read = new HashSet<>();
    private static long version;

    private ClientAnnouncements() {
    }

    static void update(LobbyPackets.S2CAnnouncements message) {
        all = List.copyOf(message.announcements());
        read.clear();
        read.addAll(message.read());
        version++;
    }

    static void clear() {
        all = List.of();
        read.clear();
        version++;
    }

    /** Changes whenever the list or the read state changes (for UI caches). */
    public static long version() {
        return version;
    }

    /** For the announcements page: pinned first, then newest first. */
    public static List<LobbyPackets.Announcement> forList() {
        List<LobbyPackets.Announcement> list = new ArrayList<>(all);
        list.sort(Comparator.comparing((LobbyPackets.Announcement a) -> !a.pinned())
                .thenComparing(Comparator.comparingLong(LobbyPackets.Announcement::publishedAt).reversed()));
        return list;
    }

    /** Unread announcements, oldest first: the reminder works through them in publishing order. */
    public static List<LobbyPackets.Announcement> unread() {
        return all.stream().filter(a -> !read.contains(a.id()))
                .sorted(Comparator.comparingLong(LobbyPackets.Announcement::publishedAt)).toList();
    }

    public static boolean isRead(String id) {
        return read.contains(id);
    }

    public static void markRead(Collection<String> ids) {
        List<String> fresh = ids.stream().filter(id -> !read.contains(id)).distinct().toList();
        if (fresh.isEmpty()) return;
        read.addAll(fresh);
        version++;
        BLNetwork.sendToServer(new LobbyPackets.C2SReadAnnouncements(fresh));
    }
}
