package com.blockland.client;

import com.blockland.network.packet.LobbyGroupEntry;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.S2CLobbyState;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Last lobby state received from the server, plus the player's map choice per mode. Client thread only. */
public final class ClientLobbyState {
    private static final S2CLobbyState EMPTY = new S2CLobbyState(List.of(), List.of(), "", "", 0, false, false, 0, false,
            PartyPackets.PartyInfo.NONE, true, false, 0);
    private static S2CLobbyState state = EMPTY;
    private static long receivedAt = System.currentTimeMillis();
    /** mode id → chosen map id (missing = any map) */
    private static final Map<String, String> chosenMaps = new HashMap<>();

    private ClientLobbyState() {
    }

    static void update(S2CLobbyState next) {
        state = next;
        receivedAt = System.currentTimeMillis();
    }

    static void clear() {
        update(EMPTY);
    }

    /** We are connected to a BlockLand server and have heard from its lobby. */
    public static boolean known() {
        return state != EMPTY;
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

    /** The map the current queue ticket asks for, empty = any. */
    public static String queuedMapId() {
        return state.queuedMapId();
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

    /** Privacy setting as the server stores it. */
    public static boolean allowInvites() {
        return state.allowInvites();
    }

    /** Players connected to the server, lobby and matches. */
    public static int online() {
        return state.online();
    }

    /** Operators may leave the lobby for the world. */
    public static boolean canEnterWorld() {
        return state.canEnterWorld();
    }

    /** Solo players count as their own leader. */
    public static boolean isPartyLeader(UUID self) {
        List<PartyPackets.Member> members = state.party().members();
        return members.isEmpty() || members.stream().anyMatch(m -> m.leader() && m.id().equals(self));
    }

    /** This client's own account (the lobby has no player entity). */
    public static UUID selfId() {
        return Minecraft.getInstance().getUser().getProfileId();
    }

    public static String selfName() {
        return Minecraft.getInstance().getUser().getName();
    }

    public static boolean isLeader() {
        return isPartyLeader(selfId());
    }

    /** Seconds before an invitation expires, extrapolated locally. */
    public static int secondsLeft(PartyPackets.Invite invite) {
        return Math.max(0, invite.secondsLeft() - (int) ((System.currentTimeMillis() - receivedAt) / 1000L));
    }

    // ---- map choice ------------------------------------------------------------------------------

    /** The map chosen for a mode, empty = any; a map the mode no longer offers counts as any. */
    public static String chosenMap(String modeId) {
        String chosen = chosenMaps.getOrDefault(modeId, "");
        if (chosen.isEmpty()) return "";
        return mode(modeId).filter(m -> m.maps().stream().anyMatch(map -> map.id().equals(chosen))).isPresent() ? chosen : "";
    }

    public static void chooseMap(String modeId, String mapId) {
        if (mapId == null || mapId.isEmpty()) chosenMaps.remove(modeId);
        else chosenMaps.put(modeId, mapId);
    }
}
