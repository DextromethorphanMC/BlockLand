package com.blockland.client.hud.vanilla;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The small pixel glyphs beside the bars (UI spec §3.2): a heart, a drumstick, a breastplate shield and a bubble, drawn
 * from tiny bitmaps with flat fills so they take any colour — no textures, no vanilla sprites.
 */
final class BarIcons {
    /** Rows of a 7 × 6 heart; {@code o}: the eyes of a hardcore heart. */
    static final String[] HEART = {
            ".XX.XX.",
            "XXXXXXX",
            "XoXXXoX",
            ".XXXXX.",
            "..XXX..",
            "...X..."};
    /** A drumstick, the bone ({@code o}) to the lower left. */
    static final String[] FOOD = {
            "...XXX.",
            "..XXXXX",
            "..XXXXX",
            "..XXXX.",
            ".oXX...",
            "o.o....",
            ".o....."};
    /** A breastplate. */
    static final String[] ARMOR = {
            "XX...XX",
            "XXXXXXX",
            "XXXXXXX",
            ".XXXXX.",
            ".XXXXX.",
            "..XXX.."};
    /** A bubble with its highlight ({@code o}). */
    static final String[] BUBBLE = {
            ".XXX.",
            "X..oX",
            "X...X",
            ".XXX."};

    private BarIcons() {
    }

    /**
     * Draws {@code rows} with its top-left corner at ({@code x}, {@code y}): {@code X} in {@code color}, {@code o} in
     * {@code second} (0 = skipped).
     */
    static void draw(GuiGraphics g, String[] rows, int x, int y, int color, int second) {
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                int fill = c == 'X' ? color : c == 'o' ? second : 0;
                if (fill != 0) g.fill(x + col, y + row, x + col + 1, y + row + 1, fill);
            }
        }
    }

    static int width(String[] rows) {
        return rows[0].length();
    }
}
