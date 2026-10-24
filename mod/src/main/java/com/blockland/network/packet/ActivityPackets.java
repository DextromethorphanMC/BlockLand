package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Lobby activities ("活动"): events configured in the backend and shown on the lobby's activities page. Travels in the
 * lobby (configuration phase), so only plain data — no registries.
 */
public final class ActivityPackets {
    private ActivityPackets() {
    }

    /**
     * One activity as the lobby shows it.
     *
     * @param kind         how the detail page presents it ({@code info} = text only); unknown kinds fall back to text
     * @param data         kind-specific settings as a JSON object string ({@code "{}"} when none)
     * @param accent       ARGB colour of the activity, 0 = the lobby's default
     * @param coverVersion version of the cover image ({@code activity/<id>}), empty when there is none
     * @param startsAt     epoch millis it opens, 0 = already open
     * @param endsAt       epoch millis it ends, 0 = no end
     */
    public record Activity(String id, String title, String summary, String content, String kind, String data, int accent,
                           String coverVersion, boolean pinned, int order, long startsAt, long endsAt) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(id, 64);
            buf.writeUtf(title, 256);
            buf.writeUtf(summary, 512);
            buf.writeUtf(content, 8192);
            buf.writeUtf(kind, 32);
            buf.writeUtf(data, 16384);
            buf.writeInt(accent);
            buf.writeUtf(coverVersion, 64);
            buf.writeBoolean(pinned);
            buf.writeVarInt(order);
            buf.writeLong(startsAt);
            buf.writeLong(endsAt);
        }

        static Activity decode(FriendlyByteBuf buf) {
            return new Activity(buf.readUtf(64), buf.readUtf(256), buf.readUtf(512), buf.readUtf(8192), buf.readUtf(32),
                    buf.readUtf(16384), buf.readInt(), buf.readUtf(64), buf.readBoolean(), buf.readVarInt(), buf.readLong(),
                    buf.readLong());
        }
    }

    /** Every activity the lobby may show right now (the whole list each time). */
    public record S2CActivities(List<Activity> activities) {
        public static void encode(S2CActivities msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.activities.size());
            for (Activity a : msg.activities) a.encode(buf);
        }

        public static S2CActivities decode(FriendlyByteBuf buf) {
            int size = Math.min(buf.readVarInt(), 256);
            List<Activity> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(Activity.decode(buf));
            return new S2CActivities(list);
        }
    }
}
