package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The lobby's 库存 (inventory) and 个人主页 (profile) tabs. All of them work in the lobby (configuration phase), so
 * they are registry-free: texts travel as {@link TextJson}, items as keys and display data.
 */
public final class ProfilePackets {
    private ProfilePackets() {
    }

    public enum Kind { INVENTORY, PROFILE, LEADERBOARD }

    /** The client opened a tab and wants its data ({@code modeId}: the ladder's mode for {@link Kind#LEADERBOARD}, else ""). */
    public record C2SRequest(Kind kind, String modeId) {
        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.kind);
            buf.writeUtf(msg.modeId, 64);
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            return new C2SRequest(buf.readEnum(Kind.class), buf.readUtf(64));
        }
    }

    /** Puts an owned item into a cosmetics slot ({@code key} empty = take the slot's item off). */
    public record C2SEquip(String slot, String key) {
        public static void encode(C2SEquip msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.slot, 32);
            buf.writeUtf(msg.key, 128);
        }

        public static C2SEquip decode(FriendlyByteBuf buf) {
            return new C2SEquip(buf.readUtf(32), buf.readUtf(128));
        }
    }

    /**
     * One owned item.
     *
     * @param category the category's translation key
     * @param icon     see {@code InventoryCatalog.Display#icon}
     * @param until    epoch millis the ownership ends, 0 = permanent
     * @param slots    the cosmetics slots it can go in
     * @param equipped the slots it is in now
     */
    public record Item(String key, String category, Component name, Component description, String icon, String rarity,
                       long until, List<String> slots, List<String> equipped) {
        public Item {
            slots = List.copyOf(slots);
            equipped = List.copyOf(equipped);
        }
    }

    /** Everything the player owns (entitlements and free items), grouped by category on the client. */
    public record S2CInventory(List<Item> items) {
        public S2CInventory {
            items = List.copyOf(items);
        }

        public static void encode(S2CInventory msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.items.size());
            for (Item item : msg.items) {
                buf.writeUtf(item.key, 128);
                buf.writeUtf(item.category, 128);
                buf.writeUtf(TextJson.write(item.name), 4096);
                buf.writeUtf(TextJson.write(item.description), 8192);
                buf.writeUtf(item.icon, 256);
                buf.writeUtf(item.rarity, 32);
                buf.writeLong(item.until);
                writeStrings(buf, item.slots);
                writeStrings(buf, item.equipped);
            }
        }

        public static S2CInventory decode(FriendlyByteBuf buf) {
            int count = Math.min(buf.readVarInt(), 1024);
            List<Item> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                items.add(new Item(buf.readUtf(128), buf.readUtf(128), TextJson.read(buf.readUtf(4096)), TextJson.read(buf.readUtf(8192)),
                        buf.readUtf(256), buf.readUtf(32), buf.readLong(), readStrings(buf), readStrings(buf)));
            }
            return new S2CInventory(items);
        }
    }

    /**
     * The player's standing in one ranked mode.
     *
     * @param tier     a {@code RankTier} id
     * @param position place on the mode's ladder, 0 = not placed
     */
    public record ModeRank(String modeId, String title, int score, String tier, int position, int matches, int wins) {
    }

    /**
     * The player's own profile.
     *
     * @param card  the equipped card's id ("" = the default card)
     * @param ranks the ranked modes (the open ones first, then others the player has played), each with its own ladder
     */
    public record S2CProfile(String name, String card, List<ModeRank> ranks, long playtimeSeconds, boolean ranked) {
        public S2CProfile {
            ranks = List.copyOf(ranks);
        }

        public static void encode(S2CProfile msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.name, 64);
            buf.writeUtf(msg.card, 64);
            buf.writeVarInt(msg.ranks.size());
            for (ModeRank r : msg.ranks) {
                buf.writeUtf(r.modeId, 64);
                buf.writeUtf(r.title, 128);
                buf.writeVarInt(r.score);
                buf.writeUtf(r.tier, 32);
                buf.writeVarInt(r.position);
                buf.writeVarInt(r.matches);
                buf.writeVarInt(r.wins);
            }
            buf.writeVarLong(msg.playtimeSeconds);
            buf.writeBoolean(msg.ranked);
        }

        public static S2CProfile decode(FriendlyByteBuf buf) {
            String name = buf.readUtf(64), card = buf.readUtf(64);
            int count = Math.min(buf.readVarInt(), 256);
            List<ModeRank> ranks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ranks.add(new ModeRank(buf.readUtf(64), buf.readUtf(128), buf.readVarInt(), buf.readUtf(32), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt()));
            }
            return new S2CProfile(name, card, ranks, buf.readVarLong(), buf.readBoolean());
        }
    }

    /** A line of the ladder ({@code card} = the player's equipped card id, "" = default). */
    public record Line(String uuid, String name, int score, String tier, int position, String card) {
    }

    /** The top of one mode's ladder, best first. */
    public record S2CLeaderboard(String modeId, List<Line> lines) {
        public S2CLeaderboard {
            lines = List.copyOf(lines);
        }

        public static void encode(S2CLeaderboard msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.modeId, 64);
            buf.writeVarInt(msg.lines.size());
            for (Line line : msg.lines) {
                buf.writeUtf(line.uuid, 64);
                buf.writeUtf(line.name, 64);
                buf.writeVarInt(line.score);
                buf.writeUtf(line.tier, 32);
                buf.writeVarInt(line.position);
                buf.writeUtf(line.card, 64);
            }
        }

        public static S2CLeaderboard decode(FriendlyByteBuf buf) {
            String modeId = buf.readUtf(64);
            int count = Math.min(buf.readVarInt(), 500);
            List<Line> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(new Line(buf.readUtf(64), buf.readUtf(64), buf.readVarInt(), buf.readUtf(32), buf.readVarInt(), buf.readUtf(64)));
            }
            return new S2CLeaderboard(modeId, lines);
        }
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> strings) {
        buf.writeVarInt(strings.size());
        for (String s : strings) buf.writeUtf(s, 64);
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 32);
        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) strings.add(buf.readUtf(64));
        return strings;
    }
}
