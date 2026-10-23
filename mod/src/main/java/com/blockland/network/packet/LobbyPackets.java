package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Lobby messages added with the configuration-phase lobby: notices, announcements, privacy and admin world entry. */
public final class LobbyPackets {
    private LobbyPackets() {
    }

    public enum Level { INFO, SUCCESS, WARNING, ERROR }

    /** A server message for a player in the lobby (there is no chat there): shown as a toast. */
    public record S2CNotice(Level level, Component message) {
        public static void encode(S2CNotice msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.level);
            buf.writeUtf(TextJson.write(msg.message), 8192);
        }

        public static S2CNotice decode(FriendlyByteBuf buf) {
            return new S2CNotice(buf.readEnum(Level.class), TextJson.read(buf.readUtf(8192)));
        }
    }

    /** One announcement as the backend published it. Times are epoch millis. */
    public record Announcement(String id, String title, String content, boolean pinned, long publishedAt, long updatedAt) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(id, 64);
            buf.writeUtf(title, 256);
            buf.writeUtf(content, 8192);
            buf.writeBoolean(pinned);
            buf.writeLong(publishedAt);
            buf.writeLong(updatedAt);
        }

        static Announcement decode(FriendlyByteBuf buf) {
            return new Announcement(buf.readUtf(64), buf.readUtf(256), buf.readUtf(8192), buf.readBoolean(), buf.readLong(), buf.readLong());
        }
    }

    /** Every announcement (oldest first) and which of them this player has read (stored server-side). */
    public record S2CAnnouncements(List<Announcement> announcements, List<String> read) {
        public static void encode(S2CAnnouncements msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.announcements.size());
            for (Announcement a : msg.announcements) a.encode(buf);
            buf.writeVarInt(msg.read.size());
            for (String id : msg.read) buf.writeUtf(id, 64);
        }

        public static S2CAnnouncements decode(FriendlyByteBuf buf) {
            int size = Math.min(buf.readVarInt(), 512);
            List<Announcement> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(Announcement.decode(buf));
            int readCount = Math.min(buf.readVarInt(), 512);
            List<String> read = new ArrayList<>(readCount);
            for (int i = 0; i < readCount; i++) read.add(buf.readUtf(64));
            return new S2CAnnouncements(list, read);
        }
    }

    /** The player marked announcements as read. */
    public record C2SReadAnnouncements(List<String> ids) {
        public static void encode(C2SReadAnnouncements msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.ids.size());
            for (String id : msg.ids) buf.writeUtf(id, 64);
        }

        public static C2SReadAnnouncements decode(FriendlyByteBuf buf) {
            int size = Math.min(buf.readVarInt(), 64);
            List<String> ids = new ArrayList<>(size);
            for (int i = 0; i < size; i++) ids.add(buf.readUtf(64));
            return new C2SReadAnnouncements(ids);
        }
    }

    /** Server-side preferences (privacy); everything else in the settings page stays on the client. */
    public record C2SSettings(boolean allowPartyInvites) {
        public static void encode(C2SSettings msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.allowPartyInvites);
        }

        public static C2SSettings decode(FriendlyByteBuf buf) {
            return new C2SSettings(buf.readBoolean());
        }
    }

    /** An operator leaves the lobby for the world (building, map previews, commands). */
    public record C2SEnterWorld() {
        public static final C2SEnterWorld INSTANCE = new C2SEnterWorld();

        public static void encode(C2SEnterWorld msg, FriendlyByteBuf buf) {
        }

        public static C2SEnterWorld decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }
}
