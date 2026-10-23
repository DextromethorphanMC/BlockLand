package com.blockland.server.party;

import com.blockland.network.packet.LobbyPackets;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Parties (组队): players who queue and play together. Invitations go by player name, expire, and respect the
 * invited player's privacy setting. Knows nothing about matchmaking or the lobby; it reports every membership change
 * to a listener (the composition root uses that to cancel the party's queue ticket and refresh lobby screens).
 */
public final class PartyService {
    public static final int INVITE_SECONDS = 60;

    private final PlayerDirectory players;
    private final IntSupplier maxSize;
    /** The target's privacy setting: whether they accept party invitations at all. */
    private final Predicate<UUID> acceptsInvites;
    private final Map<UUID, Party> byMember = new HashMap<>();
    /** invited player → (inviter → invitation) */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();
    private Listener listener = (ids, membership) -> { };
    private int tickCounter;

    public PartyService(PlayerDirectory players, IntSupplier maxSize, Predicate<UUID> acceptsInvites) {
        this.players = players;
        this.maxSize = maxSize;
        this.acceptsInvites = acceptsInvites;
    }

    public interface Listener {
        /**
         * @param players    everyone whose party view changed
         * @param membership true when party membership changed (not just invitations)
         */
        void onChanged(Collection<UUID> players, boolean membership);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** A party of two or more players; the first member is the leader. */
    public static final class Party {
        private final List<UUID> members = new ArrayList<>();

        public UUID leader() {
            return members.get(0);
        }

        public List<UUID> members() {
            return List.copyOf(members);
        }
    }

    public record Invite(UUID from, String fromName, long expiresAt) {
        public int secondsLeft() {
            return (int) Math.max(0, (expiresAt - System.currentTimeMillis() + 999) / 1000);
        }
    }

    // ---- queries --------------------------------------------------------------------------------

    @Nullable
    public Party partyOf(UUID player) {
        return byMember.get(player);
    }

    /** The player's party members (leader first), or just the player when solo. */
    public List<UUID> membersOf(UUID player) {
        Party party = byMember.get(player);
        return party == null ? List.of(player) : party.members();
    }

    /** Solo players lead themselves. */
    public boolean isLeader(UUID player) {
        Party party = byMember.get(player);
        return party == null || party.leader().equals(player);
    }

    public int maxSize() {
        return maxSize.getAsInt();
    }

    /** Invitations waiting for {@code player}'s answer. */
    public List<Invite> invitesTo(UUID player) {
        Map<UUID, Invite> map = invites.get(player);
        return map == null ? List.of() : List.copyOf(map.values());
    }

    /** Names of players invited by {@code player}'s party who have not answered. */
    public List<String> pendingFrom(UUID player) {
        Set<UUID> party = new HashSet<>(membersOf(player));
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Invite>> entry : invites.entrySet()) {
            boolean fromUs = entry.getValue().keySet().stream().anyMatch(party::contains);
            if (!fromUs || party.contains(entry.getKey())) continue;
            String name = players.name(entry.getKey());
            if (name != null) names.add(name);
        }
        return names;
    }

    // ---- actions (return an error message, or null on success) ------------------------------

    @Nullable
    public Component invite(UUID from, String targetName) {
        String name = targetName == null ? "" : targetName.trim();
        if (name.isEmpty()) return Component.translatable("blockland.party.enter_name");
        UUID target = players.byName(name).orElse(null);
        if (target == null) return Component.translatable("blockland.party.not_online", name);
        if (target.equals(from)) return Component.translatable("blockland.party.self");
        String targetShown = nameOf(target);
        List<UUID> members = membersOf(from);
        if (members.contains(target)) return Component.translatable("blockland.party.already_member", targetShown);
        if (!acceptsInvites.test(target)) return Component.translatable("blockland.party.no_invites", targetShown);
        if (members.size() >= maxSize()) return Component.translatable("blockland.party.full", maxSize());
        Map<UUID, Invite> forTarget = invites.computeIfAbsent(target, k -> new LinkedHashMap<>());
        if (forTarget.containsKey(from)) return Component.translatable("blockland.party.already_invited", targetShown);
        forTarget.put(from, new Invite(from, nameOf(from), System.currentTimeMillis() + INVITE_SECONDS * 1000L));
        players.notify(target, LobbyPackets.Level.INFO, Component.translatable("blockland.party.invited_you", nameOf(from), INVITE_SECONDS));
        invitesChanged(List.of(target, from));
        invitesChanged(members);
        return null;
    }

    @Nullable
    public Component accept(UUID player, UUID from) {
        Invite invite = takeInvite(player, from);
        if (invite == null || invite.secondsLeft() <= 0) return Component.translatable("blockland.party.invite_expired");
        if (!players.isOnline(from)) return Component.translatable("blockland.party.inviter_offline");
        if (membersOf(from).contains(player)) return null;
        if (membersOf(from).size() >= maxSize()) return Component.translatable("blockland.party.their_full");

        leave(player, false);
        Party party = byMember.get(from);
        if (party == null) {
            party = new Party();
            party.members.add(from);
            byMember.put(from, party);
        }
        party.members.add(player);
        byMember.put(player, party);
        // being in one party at a time: other invitations to this player are dropped
        Map<UUID, Invite> others = invites.remove(player);
        broadcast(party, LobbyPackets.Level.SUCCESS, Component.translatable("blockland.party.joined", nameOf(player)));
        membersChanged(party.members);
        if (others != null) invitesChanged(others.keySet());
        return null;
    }

    public void decline(UUID player, UUID from) {
        if (takeInvite(player, from) == null) return;
        if (players.isOnline(from)) {
            players.notify(from, LobbyPackets.Level.INFO, Component.translatable("blockland.party.declined", nameOf(player)));
        }
        invitesChanged(membersOf(from));
        invitesChanged(List.of(player));
    }

    /** Leaves the current party (no-op when solo). */
    public void leave(UUID player, boolean announce) {
        Party party = byMember.get(player);
        if (party == null) return;
        List<UUID> before = party.members();
        remove(party, player);
        if (announce) {
            players.notify(player, LobbyPackets.Level.INFO, Component.translatable("blockland.party.you_left"));
            broadcast(party, LobbyPackets.Level.INFO, Component.translatable("blockland.party.left", nameOf(player)));
        }
        membersChanged(before);
    }

    @Nullable
    public Component kick(UUID leader, UUID target) {
        Party party = byMember.get(leader);
        if (party == null || !party.leader().equals(leader)) return Component.translatable("blockland.party.leader_only_kick");
        if (target.equals(leader) || !party.members.contains(target)) return Component.translatable("blockland.party.not_member");
        List<UUID> before = party.members();
        remove(party, target);
        players.notify(target, LobbyPackets.Level.WARNING, Component.translatable("blockland.party.you_kicked"));
        broadcast(party, LobbyPackets.Level.INFO, Component.translatable("blockland.party.kicked", nameOf(target)));
        membersChanged(before);
        return null;
    }

    /** The leader hands leadership to another member. Queue tickets stay valid: the party is unchanged. */
    @Nullable
    public Component transfer(UUID leader, UUID target) {
        Party party = byMember.get(leader);
        if (party == null || !party.leader().equals(leader)) return Component.translatable("blockland.party.leader_only_transfer");
        if (target.equals(leader) || !party.members.contains(target)) return Component.translatable("blockland.party.not_member");
        party.members.remove(target);
        party.members.add(0, target);
        broadcast(party, LobbyPackets.Level.INFO, Component.translatable("blockland.party.new_leader", nameOf(target)));
        invitesChanged(party.members);
        return null;
    }

    /** The player disconnected. */
    public void onLeave(UUID player, String name) {
        Party party = byMember.get(player);
        leave(player, false);
        if (party != null) broadcast(party, LobbyPackets.Level.INFO, Component.translatable("blockland.party.went_offline", name));
        Set<UUID> affected = new HashSet<>();
        Map<UUID, Invite> received = invites.remove(player);
        if (received != null) affected.addAll(received.keySet());
        invites.forEach((target, map) -> {
            if (map.remove(player) != null) affected.add(target);
        });
        invites.values().removeIf(Map::isEmpty);
        invitesChanged(affected);
    }

    public void tick() {
        if (++tickCounter % 20 != 0 || invites.isEmpty()) return;
        Set<UUID> affected = new HashSet<>();
        invites.forEach((target, map) -> map.values().removeIf(invite -> {
            if (invite.secondsLeft() > 0) return false;
            affected.add(target);
            affected.addAll(membersOf(invite.from()));
            return true;
        }));
        invites.values().removeIf(Map::isEmpty);
        if (!affected.isEmpty()) invitesChanged(affected);
    }

    public void clear() {
        byMember.clear();
        invites.clear();
    }

    // ---- internals ----------------------------------------------------------------------------

    @Nullable
    private Invite takeInvite(UUID target, UUID from) {
        Map<UUID, Invite> map = invites.get(target);
        if (map == null) return null;
        Invite invite = map.remove(from);
        if (map.isEmpty()) invites.remove(target);
        return invite;
    }

    private void remove(Party party, UUID member) {
        party.members.remove(member);
        byMember.remove(member);
        if (party.members.size() < 2) {
            // a party of one is just a solo player
            party.members.forEach(byMember::remove);
        }
    }

    private void broadcast(Party party, LobbyPackets.Level level, Component message) {
        for (UUID id : party.members) players.notify(id, level, message);
    }

    private String nameOf(UUID id) {
        String name = players.name(id);
        return name == null ? "?" : name;
    }

    private void membersChanged(Collection<UUID> ids) {
        if (!ids.isEmpty()) listener.onChanged(List.copyOf(ids), true);
    }

    private void invitesChanged(Collection<UUID> ids) {
        if (!ids.isEmpty()) listener.onChanged(List.copyOf(ids), false);
    }
}
