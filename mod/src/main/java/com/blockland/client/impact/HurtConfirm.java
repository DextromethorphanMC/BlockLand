package com.blockland.client.impact;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Blood waits for the server's word (rendering spec §12): this client's own hit on a body that would bleed is traced at
 * once, but its blood, its wound and its flesh sound play only once the server says the target really lost health
 * ({@code combat/HurtCheck}) — a creative or protected player, a round soaked whole by body armour, a game that turned the
 * damage away: no blood. A weapon {@link #await}s its hit and {@link #settle}s it from its hit confirmation; each
 * confirmation plays the oldest hit held on that target, and one for a target nothing is held for (the server's trace
 * struck someone else, or the hold ran out) plays the weapon's version at the server's spot instead. Hits the server
 * never confirms are dropped after {@link #HOLD_NANOS}. Hits seen from others come confirmed (their packets say whether
 * the target was wounded) and never wait here.
 */
public final class HurtConfirm {
    /** How long a hit waits for its confirmation (a round trip, with a lag spike's room). */
    private static final long HOLD_NANOS = 1_500_000_000L;
    private static final int MAX_HELD = 64;

    /** {@code level}: the world it was traced in (a hit from another world never plays). */
    private record Held(int target, Object level, Runnable effects, long at) {
    }

    private static final ArrayDeque<Held> held = new ArrayDeque<>();

    private HurtConfirm() {
    }

    static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> expire());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> held.clear());
    }

    /** Holds the effects of this client's hit on {@code target} (a body that bleeds) until the server confirms it. */
    public static void await(Entity target, Runnable effects) {
        if (held.size() >= MAX_HELD) held.pollFirst();
        held.addLast(new Held(target.getId(), target.level(), effects, System.nanoTime()));
    }

    /**
     * The server's word on one hit on {@code target}: {@code hurt} plays the oldest hit held on it (or, with none held,
     * {@code unpredicted}: the hit as the server traced it); not hurt drops it.
     */
    public static void settle(int target, boolean hurt, Runnable unpredicted) {
        Held match = null;
        for (Iterator<Held> it = held.iterator(); it.hasNext(); ) {
            Held h = it.next();
            if (h.target == target) {
                it.remove();
                match = h;
                break;
            }
        }
        if (!hurt) return;
        if (match == null) unpredicted.run();
        else if (match.level == Minecraft.getInstance().level) match.effects.run();
    }

    private static void expire() {
        long now = System.nanoTime();
        while (!held.isEmpty() && now - held.peekFirst().at > HOLD_NANOS) held.pollFirst();
    }
}
