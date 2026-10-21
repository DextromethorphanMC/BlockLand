package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Party (组队) messages. The party view itself travels inside {@link S2CLobbyState}. */
public final class PartyPackets {
    private static final int MAX_LIST = 32;

    private PartyPackets() {
    }

    public enum Action {
        /** argument: target player name */
        INVITE,
        /** argument: inviter UUID */
        ACCEPT,
        /** argument: inviter UUID */
        DECLINE,
        LEAVE,
        /** argument: member UUID; leader only */
        KICK
    }

    public record C2SAction(Action action, String argument) {
        public static void encode(C2SAction msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
            buf.writeUtf(msg.argument, 64);
        }

        public static C2SAction decode(FriendlyByteBuf buf) {
            return new C2SAction(buf.readEnum(Action.class), buf.readUtf(64));
        }
    }

    /** Where a party member is right now. */
    public enum Status { LOBBY, QUEUED, IN_GAME }

    public record Member(UUID id, String name, boolean leader, Status status) {
    }

    /** An invitation waiting for this player's answer. */
    public record Invite(UUID fromId, String fromName, int partySize, int secondsLeft) {
    }

    /**
     * The receiving player's party.
     *
     * @param members  empty when the player is not in a party (playing solo)
     * @param pending  names of players invited into this party who have not answered yet
     * @param invites  invitations to other parties
     */
    public record PartyInfo(List<Member> members, int maxSize, List<String> pending, List<Invite> invites) {
        public static final PartyInfo NONE = new PartyInfo(List.of(), 1, List.of(), List.of());

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(members.size());
            for (Member m : members) {
                buf.writeUUID(m.id());
                buf.writeUtf(m.name(), 32);
                buf.writeBoolean(m.leader());
                buf.writeEnum(m.status());
            }
            buf.writeVarInt(maxSize);
            buf.writeVarInt(pending.size());
            for (String name : pending) buf.writeUtf(name, 32);
            buf.writeVarInt(invites.size());
            for (Invite i : invites) {
                buf.writeUUID(i.fromId());
                buf.writeUtf(i.fromName(), 32);
                buf.writeVarInt(i.partySize());
                buf.writeVarInt(i.secondsLeft());
            }
        }

        public static PartyInfo decode(FriendlyByteBuf buf) {
            int count = Math.min(buf.readVarInt(), MAX_LIST);
            List<Member> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(new Member(buf.readUUID(), buf.readUtf(32), buf.readBoolean(), buf.readEnum(Status.class)));
            }
            int maxSize = buf.readVarInt();
            int pendingCount = Math.min(buf.readVarInt(), MAX_LIST);
            List<String> pending = new ArrayList<>(pendingCount);
            for (int i = 0; i < pendingCount; i++) pending.add(buf.readUtf(32));
            int inviteCount = Math.min(buf.readVarInt(), MAX_LIST);
            List<Invite> invites = new ArrayList<>(inviteCount);
            for (int i = 0; i < inviteCount; i++) {
                invites.add(new Invite(buf.readUUID(), buf.readUtf(32), buf.readVarInt(), buf.readVarInt()));
            }
            return new PartyInfo(members, maxSize, pending, invites);
        }
    }
}
