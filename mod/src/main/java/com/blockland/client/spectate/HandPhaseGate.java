package com.blockland.client.spectate;

import com.blockland.BlockLand;
import com.blockland.client.viewmodel.ViewSubject;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Lets the hand phase run while the local player spectates someone in first person. Vanilla skips the first-person
 * hand for a spectator (it checks the client's own game mode), so nothing of BlockLand's weapon modules — which draw
 * their viewmodels from that phase — would show the watched player's weapon. For exactly that phase (opened by a frame
 * pass that runs after the level, closed by the first layer of the HUD, at the latest at the frame's end) the client's
 * own game mode reads as adventure; nothing ticks in between, so nothing else sees it. The local spectator's own vanilla
 * arm never shows: whatever no weapon module drew is cancelled.
 */
final class HandPhaseGate {
    @Nullable
    private static final Field LOCAL_MODE = field();
    private static boolean passAdded;
    /** The mode the gate replaced while it is open, null when closed. */
    @Nullable
    private static GameType opened;

    private HandPhaseGate() {
    }

    static void init(BusGroup modBus) {
        AddFramePassEvent.BUS.addListener(HandPhaseGate::addPass);
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> ensurePass());
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> close());
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addBelow(BlockLand.id("spectate_hand_gate"), ForgeLayeredDraw.PRE_SLEEP_STACK, (g, delta) -> close()));
        // the spectator's own (empty) vanilla arm: never, while watching someone's view
        RenderHandEvent.BUS.addListener(Priority.LOWEST, event -> opened != null);
    }

    private static void open() {
        Minecraft mc = Minecraft.getInstance();
        MultiPlayerGameMode mode = mc.gameMode;
        if (opened != null || LOCAL_MODE == null || mode == null || ViewSubject.watched() == null) return;
        if (mode.getPlayerMode() != GameType.SPECTATOR) return;
        try {
            LOCAL_MODE.set(mode, GameType.ADVENTURE);
            opened = GameType.SPECTATOR;
        } catch (IllegalAccessException ignored) {
            // made accessible once; cannot happen afterwards
        }
    }

    private static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (opened == null) return;
        GameType restore = opened;
        opened = null;
        if (mc.gameMode == null || LOCAL_MODE == null) return;
        try {
            LOCAL_MODE.set(mc.gameMode, restore);
        } catch (IllegalAccessException ignored) {
            // made accessible once; cannot happen afterwards
        }
    }

    private static void addPass(AddFramePassEvent event) {
        if (passAdded) return;
        passAdded = true;
        event.addPass(BlockLand.id("spectate_hand_gate"), PASS);
    }

    /** In case the level renderer was built before this listened (the pass list is static). */
    private static void ensurePass() {
        if (passAdded) return;
        passAdded = true;
        ForgeHooksClient.addFramePass(BlockLand.id("spectate_hand_gate"), PASS);
    }

    private static final FramePassManager.PassDefinition PASS = new FramePassManager.PassDefinition() {
        @Override
        public void extracts(LevelTargetBundle bundle, FramePass pass, DeltaTracker tracker) {
            bundle.main = pass.readsAndWrites(bundle.main);
        }

        @Override
        public void executes() {
            open();
        }
    };

    @Nullable
    private static Field field() {
        try {
            Field field = MultiPlayerGameMode.class.getDeclaredField("localPlayerMode");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Spectating in first person shows no weapon: {}", e.toString());
            return null;
        }
    }
}
