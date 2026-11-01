package com.blockland.client.armor;

import com.blockland.armor.ArmorContent;
import com.blockland.armor.ArmorRules;
import com.blockland.armor.VestState;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import static com.blockland.client.ui.Tr.tr;

/**
 * The armour HUD (asset spec §50): one segment per plate slot of the worn carrier — filled with the plate's durability
 * left, empty slots outlined —, flashing when the armour takes a hit (a {@link Plates} element of the movable HUD, over
 * the health bar by default); a plate breaking flashes the screen's edges and names it; while a plate goes in, a
 * progress bar under the crosshair.
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

    /**
     * The plate segments from ({@code x0}, {@code y}), 81 px across: {@code plates} holds each inserted plate's share of
     * its durability left.
     */
    private static void segments(GuiGraphics g, int x0, int y, int slots, float[] plates) {
        long now = System.nanoTime();
        int gap = 3, total = 81, segment = (total - gap * (slots - 1)) / slots;
        boolean flash = now - hitAt < HIT_NANOS;
        boolean done = now - insertedAt < DONE_NANOS;
        int count = plates.length;
        for (int i = 0; i < slots; i++) {
            int x = x0 + i * (segment + gap);
            Draw.rect(g, x, y, segment, 5, EMPTY);
            if (i < count) {
                float k = Mth.clamp(plates[i], 0, 1);
                int fill = flash && i == count - 1 ? 0xFFFFFFFF : k < 0.34f ? FILL_LOW : FILL;
                Draw.rect(g, x + 1, y + 1, Math.max(1, Math.round((segment - 2) * k)), 3, fill);
                if (done && i == count - 1) Draw.outline(g, x - 1, y - 1, segment + 2, 7, 0xFFFFFFFF);
            } else {
                Draw.outline(g, x, y, segment, 5, EDGE_FAINT);
            }
        }
    }

    /**
     * The worn carrier's plates as an element of the movable HUD (UI spec §3), by default over the hearts where
     * vanilla's armour row would be (the carrier gives no vanilla armour points); a sample in the editor.
     */
    static final class Plates implements HudElement {
        private static final int W = 82, H = 7;

        @Override
        public String id() {
            return "armor_plates";
        }

        @Override
        public String nameKey() {
            return "blockland.hud.element.armor_plates";
        }

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public HudAnchor defaultAnchor() {
            return HudAnchor.BOTTOM;
        }

        // above the food bar on the right of the hotbar, flush with its right end (the health and armour rows fill the left)
        @Override
        public int defaultX() {
            return 57;
        }

        @Override
        public int defaultY() {
            return -58;
        }

        @Override
        public boolean visible() {
            LocalPlayer player = Minecraft.getInstance().player;
            return player != null && !player.isSpectator() && !ArmorContent.wornVest(player).isEmpty();
        }

        @Override
        public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
            LocalPlayer player = Minecraft.getInstance().player;
            ArmorRules rules = ArmorRules.get();
            int slots = Math.max(1, rules.maxPlates);
            ItemStack vest = player == null ? ItemStack.EMPTY : ArmorContent.wornVest(player);
            if (!vest.isEmpty()) {
                VestState state = ArmorContent.stateOf(vest);
                float[] plates = new float[state.count()];
                for (int i = 0; i < plates.length; i++) plates[i] = state.plates().get(i) / Math.max(0.01f, rules.plateDurability);
                segments(g, x + 1, y + 1, slots, plates);
            } else if (editing) {
                float[] plates = new float[Math.max(1, slots - 1)];
                java.util.Arrays.fill(plates, 1f);
                segments(g, x + 1, y + 1, slots, plates);
            }
        }
    }
}
