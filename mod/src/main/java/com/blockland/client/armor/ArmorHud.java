package com.blockland.client.armor;

import com.blockland.armor.ArmorContent;
import com.blockland.armor.ArmorRules;
import com.blockland.armor.VestState;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import static com.blockland.client.ui.Tr.tr;

/**
 * The armour HUD (asset spec §50): over the health bar, one segment per plate slot of the worn carrier — filled with
 * the plate's durability left, empty slots outlined —, flashing when the armour takes a hit; a plate breaking flashes
 * the screen's edges and names it; while a plate goes in, a progress bar under the crosshair.
 */
final class ArmorHud {
    private static final int FILL = 0xFF8FC6F2, FILL_LOW = 0xFFF2B05A, EMPTY = 0x99000000, EDGE_FAINT = 0x44B8D4EE;
    private static final int TEXT = 0xFFF2F4F7, BROKEN = 0xFFFF6A5A;
    private static final long HIT_NANOS = 250_000_000L, BREAK_NANOS = 1_200_000_000L, DONE_NANOS = 400_000_000L;
    private static long hitAt, brokenAt, insertedAt;

    private ArmorHud() {
    }

    /** The armour took a hit: {@code broken} plates broke. */
    static void hit(float soaked, int broken) {
        long now = System.nanoTime();
        if (soaked > 0) hitAt = now;
        if (broken > 0) brokenAt = now;
    }

    static void inserted() {
        insertedAt = System.nanoTime();
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || player.isSpectator()) return;
        long now = System.nanoTime();
        int w = g.guiWidth(), h = g.guiHeight();
        // a plate breaking: the screen's edges flash cold, and it is named
        float broke = 1 - Mth.clamp((now - brokenAt) / (float) BREAK_NANOS, 0, 1);
        if (brokenAt != 0 && broke > 0) {
            int a = Math.round(110 * broke * broke);
            int edge = a << 24 | 0xC8E4FF;
            Draw.fadeY(g, 0, 0, w, h / 6, edge, true);
            Draw.fadeY(g, 0, h - h / 6, w, h / 6, edge, false);
            Draw.fadeX(g, 0, 0, w / 8, h, edge, true);
            Draw.fadeX(g, w - w / 8, 0, w / 8, h, edge, false);
            String text = tr("blockland.armor.broken");
            int alpha = Math.round(255 * Math.min(1, broke * 1.6f));
            Draw.text(g, text, w / 2f - UiFont.width(text) / 2f, h - 72, 1f, alpha << 24 | (BROKEN & 0xFFFFFF), true);
        }
        ItemStack vest = ArmorContent.wornVest(player);
        if (vest.isEmpty()) return;
        ArmorRules rules = ArmorRules.get();
        VestState state = ArmorContent.stateOf(vest);
        // over the hearts (vanilla's armour row: the carrier gives no vanilla armour points)
        int slots = Math.max(1, rules.maxPlates);
        int gap = 3, total = 81, segment = (total - gap * (slots - 1)) / slots;
        int x0 = w / 2 - 91, y = h - 49;
        boolean flash = now - hitAt < HIT_NANOS;
        boolean done = now - insertedAt < DONE_NANOS;
        for (int i = 0; i < slots; i++) {
            int x = x0 + i * (segment + gap);
            Draw.rect(g, x, y, segment, 5, EMPTY);
            if (i < state.count()) {
                float k = Mth.clamp(state.plates().get(i) / Math.max(0.01f, rules.plateDurability), 0, 1);
                int fill = flash && i == state.count() - 1 ? 0xFFFFFFFF : k < 0.34f ? FILL_LOW : FILL;
                Draw.rect(g, x + 1, y + 1, Math.max(1, Math.round((segment - 2) * k)), 3, fill);
                if (done && i == state.count() - 1) Draw.outline(g, x - 1, y - 1, segment + 2, 7, 0xFFFFFFFF);
            } else {
                Draw.outline(g, x, y, segment, 5, EDGE_FAINT);
            }
        }
        // a plate going in
        float progress = ClientArmor.insertProgress();
        if (progress > 0 && progress < 1) {
            int bw = 90, bx = w / 2 - bw / 2, by = h / 2 + 28;
            String text = tr("blockland.armor.inserting");
            Draw.text(g, text, w / 2f - UiFont.width(text) * 0.8f / 2f, by - 11, 0.8f, TEXT, true);
            Draw.rect(g, bx, by, bw, 4, EMPTY);
            Draw.rect(g, bx + 1, by + 1, Math.round((bw - 2) * progress), 2, FILL);
        }
    }
}
