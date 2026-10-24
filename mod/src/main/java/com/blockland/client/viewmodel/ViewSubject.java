package com.blockland.client.viewmodel;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ViewmodelPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Whose first-person view the weapon modules draw: normally the local player's own (their modules' usual path), while
 * spectating someone in first person that player's ({@link #watched()}). The spectator module says whom it watches;
 * each weapon module then shows the watched player's held weapon with its build, moves it with their movement, and plays
 * what they do as far as the server tells — shots and aiming through the packets every tracker gets, the hand's other
 * actions (reloads, swings, throws) through {@link #onAction}. The local player's own input never drives it.
 */
public final class ViewSubject {
    /** One of the watched player's hand actions (see {@link ViewmodelPackets.Kind} for {@code a} / {@code b}). */
    public interface Listener {
        void action(AbstractClientPlayer player, ViewmodelPackets.Kind kind, int a, int b);
    }

    private static volatile Supplier<AbstractClientPlayer> source = () -> null;
    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean initialised;

    private ViewSubject() {
    }

    /** Hooks the action packets up (once; called by the module that sets the subject). */
    public static void init() {
        if (initialised) return;
        initialised = true;
        BLNetwork.setViewmodelClientListener(action -> {
            AbstractClientPlayer watched = watched();
            if (watched == null || watched.getId() != action.playerId()) return;
            for (Listener listener : listeners) listener.action(watched, action.kind(), action.a(), action.b());
        });
    }

    /** Who is watched in first person right now (null = nobody: the local player's own view). Spectator module only. */
    public static void setWatched(@Nullable Supplier<AbstractClientPlayer> watched) {
        source = watched == null ? () -> null : watched;
    }

    /**
     * The player whose eyes the camera is in while spectating in first person, or null. Always another player, alive,
     * and the camera's entity this frame.
     */
    @Nullable
    public static AbstractClientPlayer watched() {
        AbstractClientPlayer player = source.get();
        if (player == null || !player.isAlive() || player.isRemoved()) return null;
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        return camera == player && player != mc.player && mc.options.getCameraType().isFirstPerson() ? player : null;
    }

    /** Hears the watched player's hand actions (reloads, knife swings, throws). */
    public static void onAction(Listener listener) {
        listeners.add(listener);
    }
}
