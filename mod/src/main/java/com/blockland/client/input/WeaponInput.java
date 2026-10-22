package com.blockland.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Weapon input shared by every weapon module (guns, knives): real press edges of the attack and use buttons, and
 * the inspect key. Press edges are read from the raw mouse/key events because vanilla re-sends "attack" every tick
 * while a block is being mined, which must not turn a single click into a burst. Each module subscribes and acts
 * only while its own weapon is held.
 */
public final class WeaponInput {
    public static final KeyMapping INSPECT = new KeyMapping("key.blockland.inspect", InputConstants.KEY_I, "key.categories.blockland");

    private static final List<Runnable> attackListeners = new ArrayList<>();
    private static final List<Runnable> useListeners = new ArrayList<>();
    private static boolean inspectPressed;

    private WeaponInput() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(INSPECT));
        InputEvent.MouseButton.Pre.BUS.addListener(event -> {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.options.keyAttack.matchesMouse(event.getButton())) attackPressed();
                if (mc.options.keyUse.matchesMouse(event.getButton())) usePressed();
            }
            return false;
        });
        InputEvent.Key.BUS.addListener(event -> {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.keyAttack.matches(event.getKey(), event.getScanCode())) attackPressed();
            if (mc.options.keyUse.matches(event.getKey(), event.getScanCode())) usePressed();
        });
        // consumed once at the start of the tick, so every module sees the same press during the tick
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> {
            inspectPressed = false;
            while (INSPECT.consumeClick()) inspectPressed = true;
        });
    }

    public static void onAttackPressed(Runnable listener) {
        attackListeners.add(listener);
    }

    public static void onUsePressed(Runnable listener) {
        useListeners.add(listener);
    }

    /** The attack button went down. Also called by the dev test driver. */
    public static void attackPressed() {
        attackListeners.forEach(Runnable::run);
    }

    /** The use button went down. Also called by the dev test driver. */
    public static void usePressed() {
        useListeners.forEach(Runnable::run);
    }

    /** The inspect key was pressed since the previous tick (valid during the client tick). */
    public static boolean inspectPressed() {
        return inspectPressed;
    }
}
