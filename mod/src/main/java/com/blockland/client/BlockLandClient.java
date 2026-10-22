package com.blockland.client;

import com.blockland.client.decal.Decals;
import com.blockland.client.gun.ClientGuns;
import com.blockland.client.gun.GunPreviewRenderer;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.melee.ClientKnives;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.Textures;
import com.blockland.client.screen.KitLayoutScreen;
import com.blockland.client.screen.LobbyScreen;
import com.blockland.network.BLNetwork;
import com.blockland.network.ClientPacketListener;
import com.blockland.network.packet.C2SLobbyRequest;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CKitData;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.C2SSubmitKit;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.network.packet.ShopPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Client module: key binding, lobby state cache and screens, and the wiring of the independent client features
 * (weapon input, guns, knives, decals). Never loaded on a dedicated server.
 */
public final class BlockLandClient {
    public static final KeyMapping OPEN_LOBBY = new KeyMapping("key.blockland.lobby", InputConstants.KEY_M, "key.categories.blockland");

    private BlockLandClient() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(OPEN_LOBBY));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            while (OPEN_LOBBY.consumeClick()) openLobby();
            openPendingLobby();
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            ClientLobbyState.clear();
            ClientShopState.clear();
            ClientMedia.resetRequests();
            pendingOpen = false;
        });
        BLNetwork.setClientListener(new Handler());
        // retained GPU models and textures belong to the resource packs: reloaded on demand after F3+T
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    ModelCache.clear();
                    Textures.clear();
                }));
        WeaponInput.init(modBus);
        Decals.init(modBus);
        ClientGuns.init(modBus);
        ClientKnives.init(modBus);
        com.blockland.client.dev.DevAutomation.init();
        RegisterPictureInPictureRendererEvent.BUS.addListener(event -> event.register(new GunPreviewRenderer(event.getBufferSource())));
    }

    /** Server asked to show the lobby while another screen (e.g. world loading) was open: show it once that closes. */
    private static boolean pendingOpen;

    private static void openPendingLobby() {
        Minecraft mc = Minecraft.getInstance();
        if (pendingOpen && mc.screen == null && mc.player != null && mc.level != null) {
            pendingOpen = false;
            mc.setScreen(new LobbyScreen());
        }
    }

    public static void openLobby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (!(mc.screen instanceof LobbyScreen)) mc.setScreen(new LobbyScreen());
        BLNetwork.sendToServer(C2SLobbyRequest.INSTANCE);
    }

    private static final class Handler implements ClientPacketListener {
        @Override
        public void onLobbyState(S2CLobbyState state) {
            String queuedBefore = ClientLobbyState.queuedModeId();
            ClientLobbyState.update(state);
            Minecraft mc = Minecraft.getInstance();
            if (!state.queuedModeId().isEmpty() && !state.queuedModeId().equals(queuedBefore) && mc.player != null
                    && !ClientLobbyState.isPartyLeader(mc.player.getUUID())) {
                // the leader queued the party: our own saved kit arrangement goes along
                ClientKitLayouts.any(state.queuedModeId()).ifPresent(layout -> BLNetwork.sendToServer(
                        new C2SSubmitKit(state.queuedModeId(), layout.signature(), layout.slots())));
            }
            if (state.openScreen() && !(mc.screen instanceof LobbyScreen)) {
                pendingOpen = true;
                openPendingLobby();
            }
        }

        @Override
        public void onKitData(S2CKitData kit) {
            if (Minecraft.getInstance().screen instanceof KitLayoutScreen screen && screen.modeId().equals(kit.modeId())) {
                screen.setData(kit);
            }
        }

        @Override
        public void onShopData(ShopPackets.S2CData data) {
            ClientShopState.update(data);
        }

        @Override
        public void onMedia(MediaPackets.S2CImage image) {
            ClientMedia.receive(image);
        }

        @Override
        public void onPurchaseResult(ShopPackets.S2CPurchaseResult result) {
            ClientShopState.purchaseResult(result);
        }

        @Override
        public void onMatchFound(S2CMatchFound match) {
            Minecraft mc = Minecraft.getInstance();
            pendingOpen = false;
            if (mc.screen instanceof LobbyScreen || mc.screen instanceof KitLayoutScreen) mc.setScreen(null);
            SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("匹配成功！"), Component.literal(match.modeTitle()));
        }
    }
}
