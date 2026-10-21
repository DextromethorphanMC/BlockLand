package com.blockland.client;

import com.blockland.network.packet.LobbyGroupEntry;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.S2CLobbyState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Last lobby state received from the server. Client thread only. */
public final class ClientLobbyState {
    private static final S2CLobbyState EMPTY = new S2CLobbyState(List.of(), List.of(), "", 0, false, false, 0, false,
            PartyPackets.PartyInfo.NONE);
    private static S2CLobbyState state = EMPTY;
    private static long receivedAt = System.currentTimeMillis();

    private ClientLobbyState() {
    }

    static void update(S2CLobbyState next) {
        state = next;
        receivedAt = System.currentTimeMillis();
    }

    static void clear() {
        update(EMPTY);
    }

    public static List<LobbyModeEntry> modes() {
        return state.modes();
    }

    public static List<LobbyGroupEntry> groups() {
        return state.groups();
    }

    public static Optional<LobbyGroupEntry> group(String id) {
        return state.groups().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    public static Optional<LobbyModeEntry> mode(String id) {
        return state.modes().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    public static boolean isQueued() {
        return !state.queuedModeId().isEmpty();
    }

    public static String queuedModeId() {
        return state.queuedModeId();
    }

    /** Seconds in queue, extrapolated locally between server updates. */
    public static int queuedSeconds() {
        return state.queuedSeconds() + (int) ((System.currentTimeMillis() - receivedAt) / 1000L);
    }

    public static boolean inGame() {
        return state.inGame();
    }

    /** Total network playtime, extrapolated locally between server updates. */
    public static long playtimeSeconds() {
        return state.playtimeSeconds() + (System.currentTimeMillis() - receivedAt) / 1000L;
    }

    public static boolean priorityQueue() {
        return state.priorityQueue();
    }

    public static PartyPackets.PartyInfo party() {
        return state.party();
    }

    /** Solo players count as their own leader. */
    public static boolean isPartyLeader(UUID self) {
        List<PartyPackets.Member> members = state.party().members();
        return members.isEmpty() || members.stream().anyMatch(m -> m.leader() && m.id().equals(self));
    }

    /** Seconds before an invitation expires, extrapolated locally. */
    public static int secondsLeft(PartyPackets.Invite invite) {
        return Math.max(0, invite.secondsLeft() - (int) ((System.currentTimeMillis() - receivedAt) / 1000L));
    }
}
