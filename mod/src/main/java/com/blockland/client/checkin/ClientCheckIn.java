package com.blockland.client.checkin;

import com.blockland.network.BLNetwork;
import com.blockland.network.CheckInPacketListeners;
import com.blockland.network.packet.CheckInPackets;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * Client side of the lobby's daily check-in (每日签到): what the server last said about the player's cycle, asked for
 * when the lobby opens and again when a new day begins. Nothing here decides anything: checking in is a request the
 * server (and the backend behind it) answers.
 */
public final class ClientCheckIn {
    @Nullable
    private static CheckInPackets.S2CState state;
    /** When {@link #state} arrived ({@link System#currentTimeMillis}), for the countdown to the next day. */
    private static long receivedAt;
    private static boolean claiming, asked;
    /** Bumped on every update, so screens can tell new data from old. */
    private static int version;

    private ClientCheckIn() {
    }

    public static void init(BusGroup modBus) {
        BLNetwork.setCheckInClientListener(new CheckInPacketListeners.Client() {
            @Override
            public void onState(CheckInPackets.S2CState received) {
                state = received;
                receivedAt = System.currentTimeMillis();
                claiming = false;
                asked = false;
                version++;
            }
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
    }

    private static void clear() {
        state = null;
        claiming = false;
        asked = false;
        version++;
    }

    /** Asks the server for the player's check-in. */
    public static void request() {
        asked = true;
        BLNetwork.sendToServer(CheckInPackets.C2SRequest.INSTANCE);
    }

    /** Checks in for today (once; the answer is a new state). */
    public static void claim() {
        if (claiming || !claimable()) return;
        claiming = true;
        BLNetwork.sendToServer(CheckInPackets.C2SClaim.INSTANCE);
    }

    /** Called while the lobby shows: when a new day began since the state arrived, asks for the new one. */
    public static void maintain() {
        if (state != null && state.error().isEmpty() && !asked && !claiming && millisToNextDay() == 0) request();
    }

    @Nullable
    public static CheckInPackets.S2CState state() {
        return state;
    }

    public static int version() {
        return version;
    }

    /** A check-in is on its way. */
    public static boolean claiming() {
        return claiming;
    }

    /** The player may check in now: check-ins are open and today is not claimed yet. */
    public static boolean claimable() {
        return state != null && state.error().isEmpty() && state.enabled() && !state.claimedToday() && !state.days().isEmpty();
    }

    /** Until the next day begins (0 once it has). */
    public static long millisToNextDay() {
        if (state == null) return 0;
        return Math.max(0, state.millisToNextDay() - (System.currentTimeMillis() - receivedAt));
    }
}
