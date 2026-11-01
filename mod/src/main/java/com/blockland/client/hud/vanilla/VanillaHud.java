package com.blockland.client.hud.vanilla;

import com.blockland.client.hud.layout.HudLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * BlockLand's health, food, armour and hotbar in place of vanilla's (UI spec §3.2): each one a movable HUD element
 * ({@link HealthBar}, {@link FoodBar}, {@link ArmorBar}, {@link Hotbar} with the experience bar, {@link ItemName}).
 * While the player keeps BlockLand's HUD ({@link HudLayout#replacesVanilla}) vanilla's own drawing of them is switched
 * off ({@code mixin/hud}: they only ask {@link #replaces}); the locator and horse-jump bars, a ridden animal's health and
 * the spectator's hotbar stay vanilla's. Also keeps what vanilla's Gui tracks for them tick by tick: the health trail
 * after a hit and the fading name of a newly selected item.
 */
public final class VanillaHud {
    /** The health shown fading behind the bar after a hit, and how long it holds before it runs down. */
    static float trail = -1;
    private static int trailHold;
    /** Ticks left of the flash after damage and of the glow after healing. */
    static int hurtFlash, healGlow;
    private static float lastHealth = -1;
    @Nullable
    private static Player tracked;
    /** The selected item whose name is shown and the ticks it has left (vanilla's tool highlight). */
    static ItemStack highlighted = ItemStack.EMPTY;
    static int highlightTimer;
    static int ticks;

    private VanillaHud() {
    }

    public static void init(BusGroup modBus) {
        HudLayout.register(new HealthBar());
        HudLayout.register(new FoodBar());
        HudLayout.register(new ArmorBar());
        HudLayout.register(new Hotbar());
        HudLayout.register(new ExperienceBar());
        HudLayout.register(new ItemName());
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
    }

    /** Whether vanilla's own health, food, armour, hotbar, experience bar and item name stay off (mixins ask). */
    public static boolean replaces() {
        return HudLayout.replacesVanilla();
    }

    /** The player the HUD is about: the one the camera follows (vanilla's camera player), or null. */
    @Nullable
    static Player cameraPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() instanceof Player player ? player : null;
    }

    /** Whether the game mode can hurt the player (survival, adventure): only then are health, food and armour shown. */
    static boolean hurtable() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode != null && mc.gameMode.canHurtPlayer();
    }

    static boolean spectating() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused()) return;
        ticks++;
        Player player = cameraPlayer();
        if (player != tracked) {
            tracked = player;
            lastHealth = -1;
        }
        if (player != null) health(player.getHealth());
        // a newly selected item's name shows for vanilla's notification time, then fades (vanilla's Gui.tick)
        ItemStack stack = mc.player.getInventory().getSelectedItem();
        if (stack.isEmpty()) {
            highlightTimer = 0;
        } else if (highlighted.isEmpty() || !stack.is(highlighted.getItem()) || !stack.getHoverName().equals(highlighted.getHoverName())
                || !stack.getHighlightTip(stack.getHoverName()).equals(highlighted.getHighlightTip(highlighted.getHoverName()))) {
            highlightTimer = (int) (40.0 * mc.options.notificationDisplayTime().get());
        } else if (highlightTimer > 0) {
            highlightTimer--;
        }
        highlighted = stack;
    }

    /** A hit leaves the lost health shown a moment and then runs it down; healing glows. */
    private static void health(float health) {
        if (lastHealth < 0) {
            lastHealth = trail = health;
            return;
        }
        if (health < lastHealth - 0.01f) {
            hurtFlash = 8;
            trailHold = 12;
            trail = Math.max(trail, lastHealth);
        } else if (health > lastHealth + 0.01f) {
            healGlow = 10;
        }
        if (trailHold > 0) trailHold--;
        else trail = Math.max(health, trail - Math.max(0.25f, (trail - health) * 0.15f));
        if (trail < health) trail = health;
        if (hurtFlash > 0) hurtFlash--;
        if (healGlow > 0) healGlow--;
        lastHealth = health;
    }
}
