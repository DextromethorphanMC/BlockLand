package com.blockland.client.craft;

import com.blockland.client.spray.SprayDecals;
import com.blockland.network.BLNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Client module of the 工匠 (craft) system: the craft key (opens {@link HeldCraftScreen} for the held weapon), the
 * saved builds the server pushes ({@link ClientBuilds}) and the weapon preview renderer. The lobby's 工匠 tab is a
 * {@link CraftPanel}, installed into the lobby at start-up.
 */
public final class ClientCraft {
    public static final KeyMapping KEY = new KeyMapping("key.blockland.craft", InputConstants.KEY_G, "key.categories.blockland");

    private ClientCraft() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(KEY));
        RegisterPictureInPictureRendererEvent.BUS.addListener(event -> event.register(new WeaponPreviewRenderer(event.getBufferSource())));
        // spray decals are made from the weapon models: rebuilt with them after a resource reload
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    SprayDecals.clear();
                    SkinSwatches.clear();
                }));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> ClientBuilds.clear());
        BLNetwork.setCraftClientListener(ClientBuilds::update);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            LocalPlayer player = mc.player;
            if (player == null || mc.screen != null || player.isSpectator()) continue;
            HeldCraftScreen.forHeld(player.getMainHandItem(), player.getInventory().getSelectedSlot()).ifPresent(mc::setScreen);
        }
    }
}
