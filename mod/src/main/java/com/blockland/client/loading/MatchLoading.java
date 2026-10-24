package com.blockland.client.loading;

import com.blockland.BlockLand;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ClientMedia;
import com.blockland.client.screen.LobbyScreen;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CMatchFound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * The way into a match: from "match found" until the player stands where the game put them. The server prepares the map
 * while the player still waits in the lobby, then the client switches into the world, lands first in the staging
 * dimension and is moved into the arena a moment later — each switch would open vanilla's "downloading terrain" screen;
 * while a match is loading those are replaced by one {@link MatchLoadingScreen} that stays up the whole way. Done when the
 * player has loaded in outside the staging dimension and stood still in that world for a moment (a short minimum so it
 * never flashes); given up when the match is aborted (back in the lobby without a world) or after a timeout.
 */
public final class MatchLoading {
    /** The staging dimension every login goes through before a game places the player. */
    private static final ResourceKey<Level> STAGING = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            BlockLand.id("lobby"));
    /** Seconds the screen stays at least (a fast drop-in must not flash it). */
    private static final float MIN_SECONDS = 2.5f;
    /** Ticks the player must have been loaded in the target world before the screen goes. */
    private static final int SETTLE_TICKS = 12;
    /** Seconds after which the screen gives up whatever happens. */
    private static final float TIMEOUT_SECONDS = 120;
    /** Seconds without a world before a match that left the player in the lobby counts as aborted. */
    private static final float ABORT_GRACE = 4;
    static final float FADE_SECONDS = 0.6f;

    public enum Stage { CONNECTING, LOADING_MAP, ENTERING, DONE }

    @Nullable
    private static Session session;

    /** One way in. */
    static final class Session {
        final S2CMatchFound match;
        final long startedAt = System.nanoTime();
        final MatchLoadingScreen screen;
        Stage stage = Stage.CONNECTING;
        int settled;
        @Nullable
        ResourceKey<Level> settledIn;
        /** nanoTime the fade-out began, 0 = still loading. */
        long fadingSince;

        Session(S2CMatchFound match) {
            this.match = match;
            this.screen = new MatchLoadingScreen(this);
        }

        float seconds() {
            return (System.nanoTime() - startedAt) / 1e9f;
        }

        /** 0..1 of the fade-out (0 while loading). */
        float fade() {
            return fadingSince == 0 ? 0 : Math.min(1, (System.nanoTime() - fadingSince) / 1e9f / FADE_SECONDS);
        }
    }

    private MatchLoading() {
    }

    public static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ScreenEvent.Opening.BUS.addListener(MatchLoading::opening);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> session = null);
    }

    /** The server found a match (or let the player into a drop-in world): the loading screen takes over. */
    public static void start(S2CMatchFound match) {
        Session started = new Session(match);
        session = started;
        // the cover takes a moment to arrive: ask for it now
        if (!match.mapId().isEmpty()) ClientMedia.get(MediaPackets.MAP, match.mapId(), match.coverVersion());
        Minecraft.getInstance().setScreen(started.screen);
    }

    public static boolean active() {
        return session != null;
    }

    /** Vanilla's terrain screens (and anything else opened for the switch) while loading become ours. */
    private static boolean opening(ScreenEvent.Opening event) {
        Session current = session;
        if (current == null) return false;
        Screen next = event.getNewScreen();
        if (next == current.screen) return false;
        if (next instanceof ReceivingLevelScreen || next instanceof LevelLoadingScreen) {
            // a further switch while fading (staging → arena): back to loading
            current.fadingSince = 0;
            current.settled = 0;
            event.setNewScreen(current.screen);
            return false;
        }
        if (next instanceof LobbyScreen && Minecraft.getInstance().level != null) {
            // the lobby asked to be shown while the world loads behind it: the loading screen stays
            event.setNewScreen(current.screen);
            return false;
        }
        // something the game opens on purpose (the lobby after an abort, a selection panel, a result): it wins
        session = null;
        return false;
    }

    private static void tick() {
        Session current = session;
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        float seconds = current.seconds();
        if (current.fadingSince != 0) {
            if (current.fade() >= 1) finish(current);
            return;
        }
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            current.stage = Stage.CONNECTING;
            current.settled = 0;
            // still in the lobby: the server loads the map meanwhile; an aborted match leaves us there
            if (seconds > ABORT_GRACE && !ClientLobbyState.inGame() || seconds > TIMEOUT_SECONDS) giveUp(current);
            return;
        }
        ResourceKey<Level> dimension = mc.level.dimension();
        if (dimension.equals(STAGING) || !player.hasClientLoaded()) {
            current.stage = dimension.equals(STAGING) ? Stage.LOADING_MAP : Stage.ENTERING;
            current.settled = 0;
            if (seconds > TIMEOUT_SECONDS) fadeOut(current);
            return;
        }
        current.stage = Stage.ENTERING;
        if (!dimension.equals(current.settledIn)) {
            current.settledIn = dimension;
            current.settled = 0;
        }
        if (++current.settled >= SETTLE_TICKS && seconds >= MIN_SECONDS || seconds > TIMEOUT_SECONDS) fadeOut(current);
    }

    private static void fadeOut(Session current) {
        current.stage = Stage.DONE;
        current.fadingSince = System.nanoTime();
    }

    /** The fade is over: into the game. */
    private static void finish(Session current) {
        if (session == current) session = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == current.screen) mc.setScreen(null);
    }

    /** Nothing came of it: back to the lobby (or into whatever world there is). */
    private static void giveUp(Session current) {
        if (session == current) session = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != current.screen) return;
        mc.setScreen(mc.level == null ? new LobbyScreen() : null);
    }
}
