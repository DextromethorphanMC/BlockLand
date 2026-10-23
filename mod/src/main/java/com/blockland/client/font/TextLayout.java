package com.blockland.client.font;

import java.util.ArrayList;
import java.util.List;

/**
 * Line breaking and ellipsis fitting for laid-out text, on plain arrays (characters and their advances) so it does not
 * depend on the renderer and can be tested on its own.
 * <p>
 * Lines break after spaces between words, between any two CJK characters, and inside a word only when the word alone
 * is wider than the line. CJK line-breaking rules (禁则) are kept: closing punctuation never starts a line and opening
 * punctuation never ends one. {@code \n} always breaks; spaces at the end of a line do not count.
 */
public final class TextLayout {
    /** Closing punctuation and small kana: never at the start of a line. */
    private static final String NO_START = "!%),.:;?]}¢°·’”‰′″℃∶、。〃〆〉》」』】〕〗〙〞〟）］，．：；？！％｝～"
            + "﹐﹑﹒﹔﹕﹖﹗…‥–—•‐゠〜ー・ヽヾゝゞ々〻ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ‼⁇⁈⁉»›";
    /** Opening punctuation: never at the end of a line. */
    private static final String NO_END = "$(£¥‘“〈《「『【〔〖〘〝（［｛＄￡￥[{«‹";

    private TextLayout() {
    }

    /** A line of {@code [start, end)}; {@code width} without the trailing spaces. */
    public record Line(int start, int end, float width) {
    }

    /** Spaces a line may break after (the no-break space U+00A0 is not one of them). */
    public static boolean isSpace(int c) {
        return c == ' ' || c == '\t' || c == 0x3000;
    }

    /** CJK ideographs, kana, CJK punctuation and full-width forms: a line may break on either side of them. */
    public static boolean isCjk(int c) {
        return c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFE30 && c <= 0xFE4F
                || c >= 0xFF00 && c <= 0xFFEF || c >= 0x20000 && c <= 0x3FFFF;
    }

    private static boolean noStart(int c) {
        return c < 0x10000 && NO_START.indexOf(c) >= 0;
    }

    private static boolean noEnd(int c) {
        return c < 0x10000 && NO_END.indexOf(c) >= 0;
    }

    /** Whether a line may break between {@code before} and {@code after} (no space between them). */
    static boolean breakBetween(int before, int after) {
        if (noStart(after) || noEnd(before)) return false;
        if (isCjk(before) || isCjk(after)) return true;
        // after a hyphen or slash inside Latin text
        return (before == '-' || before == '/') && !isSpace(after);
    }

    /**
     * Breaks text into lines no wider than {@code maxWidth}.
     *
     * @param chars    the characters (code points)
     * @param advances each character's advance (kerning with the one before it included)
     */
    public static List<Line> wrap(int[] chars, float[] advances, int count, float maxWidth) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start <= count) {
            // spaces at the start of a wrapped line are dropped (not after an explicit newline: they indent)
            int i = start;
            float width = 0;
            int breakAt = -1;        // last position a break may go before
            float breakWidth = 0;    // line width (trailing spaces excluded) when breaking there
            float contentWidth = 0;  // width without trailing spaces
            boolean done = false;
            while (i < count) {
                int c = chars[i];
                if (c == '\n') {
                    lines.add(new Line(start, i, contentWidth));
                    start = i + 1;
                    done = true;
                    break;
                }
                if (i > start) {
                    int before = chars[i - 1];
                    if (isSpace(before) && !isSpace(c) || !isSpace(before) && !isSpace(c) && breakBetween(before, c)) {
                        breakAt = i;
                        breakWidth = contentWidth;
                    }
                }
                float next = width + advances[i];
                if (!isSpace(c) && next > maxWidth && i > start) {
                    // too wide: break at the last opportunity, or inside the word when there is none
                    int at = breakAt > start ? breakAt : emergencyBreak(chars, start, i);
                    float w = breakAt > start ? breakWidth : measure(advances, start, at);
                    lines.add(new Line(start, trimEnd(chars, start, at), w));
                    start = skipSpaces(chars, at, count);
                    done = true;
                    break;
                }
                width = next;
                if (!isSpace(c)) contentWidth = width;
                i++;
            }
            if (!done) {
                lines.add(new Line(start, trimEnd(chars, start, count), contentWidth));
                break;
            }
        }
        return lines;
    }

    /** Break inside a word that does not fit: before {@code at}, moved left past characters that may not start a line. */
    private static int emergencyBreak(int[] chars, int start, int at) {
        int i = at;
        while (i > start + 1 && (noStart(chars[i]) || noEnd(chars[i - 1]))) i--;
        return i;
    }

    private static int trimEnd(int[] chars, int start, int end) {
        while (end > start && isSpace(chars[end - 1])) end--;
        return end;
    }

    private static int skipSpaces(int[] chars, int at, int count) {
        while (at < count && isSpace(chars[at])) at++;
        return at;
    }

    private static float measure(float[] advances, int start, int end) {
        float width = 0;
        for (int i = start; i < end; i++) width += advances[i];
        return width;
    }

    /**
     * How many characters fit into {@code maxWidth}, with room for an ellipsis of {@code ellipsisWidth} when they do
     * not all fit.
     *
     * @return {@code count} when everything fits, else the length of the prefix to keep (trailing spaces dropped)
     */
    public static int fit(int[] chars, float[] advances, int count, float maxWidth, float ellipsisWidth) {
        if (measure(advances, 0, count) <= maxWidth) return count;
        float budget = maxWidth - ellipsisWidth;
        float width = 0;
        int keep = 0;
        while (keep < count && width + advances[keep] <= budget) {
            width += advances[keep];
            keep++;
        }
        return trimEnd(chars, 0, keep);
    }
}
