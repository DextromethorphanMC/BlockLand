package com.blockland.server.party;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

/**
 * Parties (组队): players who queue and play together. Invitations go by player name and expire.
 * Knows nothing about matchmaking or the lobby; it reports every membership change to a listener
 * (the composition root uses that to cancel the party's queue ticket and refresh lobby screens).
 */
public final class PartyService {
    public static final int INVITE_SECONDS = 60;

    private final MinecraftServer server;
    private final IntSupplier maxSize;
    private final Map<UUID, Party> byMember = new HashMap<>();
    /** invited player → (inviter → invitation) */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();
    private Listener listener = (players, membership) -> { };
    private int tickCounter;

    public PartyService(MinecraftServer server, IntSupplier maxSize) {
        this.server = server;
        this.maxSize = maxSize;
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
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
            if (target != null) names.add(target.getGameProfile().getName());
        }
        return names;
    }

    // ---- actions (return an error message, or null on success) ------------------------------

    @Nullable
    public String invite(ServerPlayer from, String targetName) {
        String name = targetName == null ? "" : targetName.trim();
        if (name.isEmpty()) return "请输入玩家名称";
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) return "玩家 " + name + " 不在线";
        if (target == from) return "不能邀请自己";
        List<UUID> members = membersOf(from.getUUID());
        if (members.contains(target.getUUID())) return target.getGameProfile().getName() + " 已经在你的队伍中";
        if (members.size() >= maxSize()) return "队伍已满（最多 " + maxSize() + " 人）";
        Map<UUID, Invite> forTarget = invites.computeIfAbsent(target.getUUID(), k -> new LinkedHashMap<>());
        if (forTarget.containsKey(from.getUUID())) return "已经邀请过 " + target.getGameProfile().getName() + "，等待对方回应";
        forTarget.put(from.getUUID(), new Invite(from.getUUID(), from.getGameProfile().getName(),
                System.currentTimeMillis() + INVITE_SECONDS * 1000L));
        target.sendSystemMessage(Component.literal("§e" + from.getGameProfile().getName()
                + " §f邀请你组队，在大厅（按 §eM§f）中接受或拒绝（" + INVITE_SECONDS + " 秒内有效）"));
        invitesChanged(List.of(target.getUUID(), from.getUUID()));
        invitesChanged(members);
        return null;
    }

    @Nullable
    public String accept(ServerPlayer player, UUID from) {
        Invite invite = takeInvite(player.getUUID(), from);
        if (invite == null || invite.secondsLeft() <= 0) return "邀请已失效";
        ServerPlayer inviter = server.getPlayerList().getPlayer(from);
        if (inviter == null) return "邀请者已离线";
        if (membersOf(from).contains(player.getUUID())) return null;
        if (membersOf(from).size() >= maxSize()) return "对方队伍已满";

        leave(player, false);
        Party party = byMember.get(from);
        if (party == null) {
            party = new Party();
            party.members.add(from);
            byMember.put(from, party);
        }
        party.members.add(player.getUUID());
        byMember.put(player.getUUID(), party);
        // being in one party at a time: other invitations to this player are dropped
        Map<UUID, Invite> others = invites.remove(player.getUUID());
        broadcast(party, "§a" + player.getGameProfile().getName() + " 加入了队伍");
        membersChanged(party.members);
        if (others != null) invitesChanged(others.keySet());
        return null;
    }

    public void decline(ServerPlayer player, UUID from) {
        if (takeInvite(player.getUUID(), from) == null) return;
        ServerPlayer inviter = server.getPlayerList().getPlayer(from);
        if (inviter != null) inviter.sendSystemMessage(Component.literal("§7" + player.getGameProfile().getName() + " 拒绝了组队邀请"));
        invitesChanged(membersOf(from));
        invitesChanged(List.of(player.getUUID()));
    }

    /** Leaves the current party (no-op when solo). */
    public void leave(ServerPlayer player, boolean announce) {
        Party party = byMember.get(player.getUUID());
        if (party == null) return;
        List<UUID> before = party.members();
        remove(party, player.getUUID());
        if (announce) {
            player.sendSystemMessage(Component.literal("§7你离开了队伍"));
            broadcast(party, "§7" + player.getGameProfile().getName() + " 离开了队伍");
        }
        membersChanged(before);
    }

    @Nullable
    public String kick(ServerPlayer leader, UUID target) {
        Party party = byMember.get(leader.getUUID());
        if (party == null || !party.leader().equals(leader.getUUID())) return "只有队长可以移出队员";
        if (target.equals(leader.getUUID()) || !party.members.contains(target)) return "该玩家不在你的队伍中";
        List<UUID> before = party.members();
        remove(party, target);
        ServerPlayer kicked = server.getPlayerList().getPlayer(target);
        if (kicked != null) kicked.sendSystemMessage(Component.literal("§7你被移出了队伍"));
        broadcast(party, "§7" + (kicked == null ? "队员" : kicked.getGameProfile().getName()) + " 被移出了队伍");
        membersChanged(before);
        return null;
    }

    /** The leader hands leadership to another member. Queue tickets stay valid: the party is unchanged. */
    @Nullable
    public String transfer(ServerPlayer leader, UUID target) {
        Party party = byMember.get(leader.getUUID());
        if (party == null || !party.leader().equals(leader.getUUID())) return "只有队长可以转让队长";
        if (target.equals(leader.getUUID()) || !party.members.contains(target)) return "该玩家不在你的队伍中";
        party.members.remove(target);
        party.members.add(0, target);
        ServerPlayer next = server.getPlayerList().getPlayer(target);
        broadcast(party, "§e" + (next == null ? "队员" : next.getGameProfile().getName()) + " 成为了队长");
        invitesChanged(party.members);
        return null;
    }

    public void onLogout(ServerPlayer player) {
        Party party = byMember.get(player.getUUID());
        leave(player, false);
        if (party != null) broadcast(party, "§7" + player.getGameProfile().getName() + " 已离线，离开了队伍");
        Set<UUID> affected = new HashSet<>();
        Map<UUID, Invite> received = invites.remove(player.getUUID());
        if (received != null) affected.addAll(received.keySet());
        invites.forEach((target, map) -> {
            if (map.remove(player.getUUID()) != null) affected.add(target);
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

    private void broadcast(Party party, String message) {
        for (UUID id : party.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null) member.sendSystemMessage(Component.literal(message));
        }
    }

    private void membersChanged(Collection<UUID> ids) {
        if (!ids.isEmpty()) listener.onChanged(List.copyOf(ids), true);
    }

    private void invitesChanged(Collection<UUID> ids) {
        if (!ids.isEmpty()) listener.onChanged(List.copyOf(ids), false);
    }
}
