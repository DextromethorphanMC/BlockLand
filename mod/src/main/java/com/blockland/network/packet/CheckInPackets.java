package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Packets of the lobby's daily check-in (每日签到); sent in the lobby, so nothing here needs a registry. */
public final class CheckInPackets {
    private CheckInPackets() {
    }

    /** The lobby wants the player's check-in (the screen opened, a new day began). */
    public record C2SRequest() {
        public static final C2SRequest INSTANCE = new C2SRequest();

        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /** The player checks in for today. */
    public record C2SClaim() {
        public static final C2SClaim INSTANCE = new C2SClaim();

        public static void encode(C2SClaim msg, FriendlyByteBuf buf) {
        }

        public static C2SClaim decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /**
     * One day of the cycle.
     *
     * @param label        what the admin called it (shown as it is); empty = the item's own name, else just the day
     * @param itemName     the reward item's name as component JSON ({@link TextJson}), empty when it is no inventory item
     * @param icon         the reward item's icon (the client's {@code ItemIcons}), empty when there is none
     * @param rarity       the reward item's rarity (colour), empty when unknown
     * @param durationDays how long the reward lasts, 0 = permanent
     * @param reward       the day grants something
     * @param claimed      checked in on this day of the current cycle
     */
    public record Day(String label, String itemName, String icon, String rarity, int durationDays, boolean reward, boolean claimed) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(label, 128);
            buf.writeUtf(itemName, 1024);
            buf.writeUtf(icon, 256);
            buf.writeUtf(rarity, 32);
            buf.writeVarInt(durationDays);
            buf.writeBoolean(reward);
            buf.writeBoolean(claimed);
        }

        static Day decode(FriendlyByteBuf buf) {
            return new Day(buf.readUtf(128), buf.readUtf(1024), buf.readUtf(256), buf.readUtf(32), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean());
        }
    }

    /**
     * The player's check-in now.
     *
     * @param error           a translation key when the check-in could not be loaded (the rest is then empty), else ""
     * @param enabled         check-ins are open
     * @param claimedToday    the player checked in today already
     * @param streak          days in a row (0 once a day was missed)
     * @param position        today's day in the cycle (0-based)
     * @param millisToNextDay until a new day (and the next check-in) begins
     * @param justClaimed     this answers the player's own check-in that just went through: the day it was (else -1)
     */
    public record S2CState(String error, boolean enabled, boolean claimedToday, int streak, int best, int total, int position,
                           long millisToNextDay, List<Day> days, int justClaimed) {
        public static final int MAX_DAYS = 31;

        public static S2CState failed(String error) {
            return new S2CState(error, false, false, 0, 0, 0, 0, 0, List.of(), -1);
        }

        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.error, 128);
            buf.writeBoolean(msg.enabled);
            buf.writeBoolean(msg.claimedToday);
            buf.writeVarInt(msg.streak);
            buf.writeVarInt(msg.best);
            buf.writeVarInt(msg.total);
            buf.writeVarInt(msg.position);
            buf.writeVarLong(msg.millisToNextDay);
            int n = Math.min(MAX_DAYS, msg.days.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) msg.days.get(i).encode(buf);
            buf.writeVarInt(msg.justClaimed + 1);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            String error = buf.readUtf(128);
            boolean enabled = buf.readBoolean(), claimedToday = buf.readBoolean();
            int streak = buf.readVarInt(), best = buf.readVarInt(), total = buf.readVarInt(), position = buf.readVarInt();
            long next = buf.readVarLong();
            int n = Math.min(MAX_DAYS, buf.readVarInt());
            List<Day> days = new ArrayList<>(n);
            for (int i = 0; i < n; i++) days.add(Day.decode(buf));
            return new S2CState(error, enabled, claimedToday, streak, best, total, position, next, days, buf.readVarInt() - 1);
        }
    }
}
