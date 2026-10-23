package com.blockland.client.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * The settings page is reachable outside the lobby too: a key (O by default) and a button in the pause menu, so
 * crosshair, sensitivity and language can be changed in the middle of a match.
 */
public final class SettingsAccess {
    public static final KeyMapping OPEN = new KeyMapping("key.blockland.settings", InputConstants.KEY_O, "key.categories.blockland");

    private SettingsAccess() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(OPEN));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new SettingsScreen(null));
            }
        });
        ScreenEvent.Init.Post.BUS.addListener(event -> {
            if (!(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) return;
            int width = 120;
            event.addListener(Button.builder(Component.translatable("blockland.settings.open"),
                            button -> Minecraft.getInstance().setScreen(new SettingsScreen(pause)))
                    .bounds(pause.width - width - 6, 6, width, 20).build());
        });
    }
}
