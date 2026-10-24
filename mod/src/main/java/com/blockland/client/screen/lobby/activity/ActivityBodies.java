package com.blockland.client.screen.lobby.activity;

import com.blockland.network.packet.ActivityPackets;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Which body an activity's screen shows, by the activity's {@code kind}. Modules that add a kind register it at start-up;
 * unknown kinds (a newer backend than this client) fall back to the activity's text.
 */
public final class ActivityBodies {
    /** Text only: the activity's content in paragraphs. */
    public static final String INFO = "info";

    private static final Map<String, Function<ActivityPackets.Activity, ActivityBody>> kinds = new HashMap<>();

    static {
        register(INFO, TextBody::new);
    }

    private ActivityBodies() {
    }

    public static void register(String kind, Function<ActivityPackets.Activity, ActivityBody> factory) {
        kinds.put(kind.toLowerCase(Locale.ROOT), factory);
    }

    public static ActivityBody create(ActivityPackets.Activity activity) {
        return kinds.getOrDefault(activity.kind().toLowerCase(Locale.ROOT), TextBody::new).apply(activity);
    }
}
