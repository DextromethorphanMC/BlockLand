package com.blockland.client.backpack;

import com.blockland.backpack.BackpackMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * BlockLand's inventory screens (UI spec §1) in place of vanilla's survival and creative ones, the backpack slot and
 * storage shown only there ({@link BackpackMenus.View}), and the backpack on players' backs ({@link BackpackLayer}).
 */
public final class ClientBackpack {
    private ClientBackpack() {
    }

    public static void init(BusGroup modBus) {
        // vanilla opens these from the inventory key and redirects between them as the game mode changes
        ScreenEvent.Opening.BUS.addListener(event -> {
            Screen screen = event.getNewScreen();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (screen == null || player == null) return false;
            if (screen.getClass() == InventoryScreen.class && !player.hasInfiniteMaterials()) {
                event.setNewScreen(new ModernInventoryScreen(player));
            } else if (screen.getClass() == CreativeModeInventoryScreen.class && player.hasInfiniteMaterials()) {
                event.setNewScreen(new BlockLandCreativeScreen(player, player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            return false;
        });
        BackpackMenus.setView(new BackpackMenus.View() {
            @Override
            public boolean backpackSlotShown() {
                BackpackScreen screen = shown();
                return screen != null && screen.backpackSlotShown();
            }

            @Override
            public boolean storageShown() {
                BackpackScreen screen = shown();
                return screen != null && screen.storageShown();
            }
        });
        EntityRenderersEvent.RegisterLayerDefinitions.getBus(modBus).addListener(BackpackLayer::registerLayers);
        EntityRenderersEvent.AddLayers.getBus(modBus).addListener(event -> {
            for (PlayerSkin.Model skin : event.getSkins()) {
                PlayerRenderer renderer = event.getPlayerSkin(skin);
                if (renderer != null) renderer.addLayer(new BackpackLayer(renderer, event.getEntityModels()));
            }
        });
    }

    /** BlockLand's inventory screen on screen now, or null. */
    @Nullable
    private static BackpackScreen shown() {
        return Minecraft.getInstance().screen instanceof BackpackScreen screen ? screen : null;
    }
}
