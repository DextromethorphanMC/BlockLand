package com.blockland.client.graffiti;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.network.BLNetwork;
import com.blockland.network.GraffitiPacketListeners;
import com.blockland.network.packet.GraffitiPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side of wall graffiti (喷漆): the graffiti key — a tap sprays slot 1, holding it opens a wheel of the four slots
 * (picked by moving the mouse — the view stays put meanwhile —, the scroll wheel or the number keys 1–4; letting go
 * sprays the pick, the right button cancels) — and what the server reports: the pieces up in the world
 * ({@link GraffitiWorld}) and the graffiti in the player's slots. The server decides whether a spray happens.
 */
public final class ClientGraffiti {
    public static final KeyMapping KEY = new KeyMapping("key.blockland.graffiti", InputConstants.KEY_Z, "key.categories.blockland");

    /** Holding the key longer than this opens the wheel (seconds). */
    private static final float HOLD = 0.22f;
    /** Degrees the view would have turned before the mouse picks a slot. */
    private static final float PICK_DEGREES = 3.5f;

    private static List<String> slots = List.of("", "", "", "");
    private static long downSince = -1;
    private static boolean open;
    private static boolean cancelled;
    private static int selected;
    private static float openYaw, openPitch;
    private static long openedAt;

    private ClientGraffiti() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(KEY));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("graffiti_wheel"), ForgeLayeredDraw.CROSSHAIR, GraffitiWheel::render));
        GraffitiWorld.init(modBus);
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            close(false);
            slots = List.of("", "", "", "");
        });
        // while the wheel is open the view does not turn: the mouse is picking
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!open) return;
            event.setYaw(openYaw);
            event.setPitch(openPitch);
        });
        InputEvent.MouseScrollingEvent.BUS.addListener(event -> {
            if (!open) return false;
            select(Math.floorMod(selected + (event.getDeltaY() < 0 ? 1 : -1), GraffitiCatalog.SLOTS));
            return true;
        });
        InputEvent.MouseButton.Pre.BUS.addListener(event -> {
            if (!open || event.getAction() != GLFW.GLFW_PRESS) return false;
            if (Minecraft.getInstance().options.keyUse.matchesMouse(event.getButton())) {
                cancelled = true;
                close(true);
                return true;
            }
            // the attack button does nothing while picking
            return Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton());
        });
        BLNetwork.setGraffitiClientListener(new GraffitiPacketListeners.Client() {
            @Override
            public void onPlace(GraffitiPackets.S2CPlace place) {
                GraffitiWorld.place(place.placement());
            }

            @Override
            public void onRemove(GraffitiPackets.S2CRemove remove) {
                GraffitiWorld.remove(remove.ids());
            }

            @Override
            public void onSync(GraffitiPackets.S2CSync sync) {
                GraffitiWorld.sync(sync.placements());
            }

            @Override
            public void onSlots(GraffitiPackets.S2CSlots received) {
                List<String> ids = new ArrayList<>(received.graffiti());
                while (ids.size() < GraffitiCatalog.SLOTS) ids.add("");
                slots = List.copyOf(ids);
            }
        });
    }

    // ---- read by the wheel ----------------------------------------------------------------------------

    static boolean isOpen() {
        return open;
    }

    static List<String> slots() {
        return slots;
    }

    static int selected() {
        return selected;
    }

    /** Seconds since the wheel opened (its pop-in). */
    static float openSeconds() {
        return (System.nanoTime() - openedAt) / 1e9f;
    }

    // ---- input ----------------------------------------------------------------------------------------

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || player.isSpectator()) {
            if (open) close(true);
            downSince = -1;
            return;
        }
        long now = System.nanoTime();
        if (KEY.isDown()) {
            if (downSince < 0) {
                downSince = now;
                cancelled = false;
            }
            if (!open && !cancelled && now - downSince > HOLD * 1e9) {
                open = true;
                openedAt = now;
                openYaw = player.getYRot();
                openPitch = player.getXRot();
                playUi(1.2f);
            }
            if (open) pick(mc, player);
        } else if (downSince >= 0) {
            boolean wasOpen = open;
            close(true);
            if (!cancelled) spray(wasOpen ? selected + 1 : 1);
            downSince = -1;
        }
        while (KEY.consumeClick()) {
            // held state is read with isDown(); the queue only has to stay empty
        }
    }

    /** The slot the mouse points at (the view's would-be turn since the wheel opened), or a number key. */
    private static void pick(Minecraft mc, LocalPlayer player) {
        for (int i = 0; i < GraffitiCatalog.SLOTS; i++) {
            while (mc.options.keyHotbarSlots[i].consumeClick()) select(i);
        }
        float dyaw = Mth.wrapDegrees(player.getYRot() - openYaw), dpitch = player.getXRot() - openPitch;
        if (dyaw * dyaw + dpitch * dpitch < PICK_DEGREES * PICK_DEGREES) return;
        // slots clockwise from the top: 1 up, 2 right, 3 down, 4 left
        double angle = Math.toDegrees(Math.atan2(dyaw, -dpitch));
        int slot = Math.floorMod((int) Math.round(angle / 90.0), GraffitiCatalog.SLOTS);
        select(slot);
        // the pick is made: the next move starts from here
        player.setYRot(openYaw);
        player.setXRot(openPitch);
    }

    private static void select(int slot) {
        if (slot == selected) return;
        selected = slot;
        playUi(1.0f + slot * 0.06f);
    }

    /** Closes the wheel; {@code restore} turns the player back to where they looked when it opened. */
    private static void close(boolean restore) {
        if (open && restore) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.setYRot(openYaw);
                player.setXRot(openPitch);
                player.yRotO = openYaw;
                player.xRotO = openPitch;
            }
        }
        open = false;
    }

    private static void spray(int slot) {
        if (slot < 1 || slot > GraffitiCatalog.SLOTS) return;
        BLNetwork.sendToServer(new GraffitiPackets.C2SSpray(slot));
    }

    private static void playUi(float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(Sounds.byId(BlockLand.id("graffiti.wheel")), pitch, 0.5f));
    }
}
