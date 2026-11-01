package com.blockland.client.hud.chat;

import com.blockland.client.font.UiFont;
import net.minecraft.ChatFormatting;
import net.minecraft.client.ComponentCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chat messages laid out with BlockLand's font (UI spec §3.3): wrapped to the chat's width as vanilla wraps them with
 * its own font (colour codes stripped when the chat colours option is off, every line after a message's first indented
 * by a space), and the style under a point of a line, for the chat screen's hovering and clicking.
 */
final class ChatLines {
    private ChatLines() {
    }

    /** How far the lines after a message's first are indented (vanilla's leading space). */
    static float indent() {
        return UiFont.widthF(" ");
    }

    /** A message's lines, no wider than {@code width} (chat units) with the continuation indent. */
    static List<FormattedCharSequence> wrap(FormattedText text, int width) {
        boolean colours = Minecraft.getInstance().options.chatColors().get();
        ComponentCollector collector = new ComponentCollector();
        text.visit((style, part) -> {
            collector.append(FormattedText.of(colours ? part : ChatFormatting.stripFormatting(part), style));
            return Optional.empty();
        }, Style.EMPTY);
        List<FormattedCharSequence> lines = new ArrayList<>(UiFont.split(collector.getResultOrEmpty(), Math.max(1, width - indent())));
        if (lines.isEmpty()) lines.add(FormattedCharSequence.EMPTY);
        return lines;
    }

    /** The style of the character at {@code x} (chat units from the line's start), null before or past the text. */
    @Nullable
    static Style styleAt(FormattedCharSequence line, double x) {
        if (x < 0) return null;
        List<Style> styles = new ArrayList<>();
        line.accept((index, style, codepoint) -> {
            styles.add(style);
            return true;
        });
        int n = styles.size();
        if (n == 0 || UiFont.width(line) <= x) return null;
        // the first character whose end lies past x
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (UiFont.width(prefix(line, mid + 1)) > x) hi = mid;
            else lo = mid + 1;
        }
        return styles.get(lo);
    }

    /** The first {@code count} characters of a line. */
    private static FormattedCharSequence prefix(FormattedCharSequence line, int count) {
        return sink -> {
            int[] seen = {0};
            return line.accept((index, style, codepoint) -> seen[0]++ >= count || sink.accept(index, style, codepoint));
        };
    }
}
