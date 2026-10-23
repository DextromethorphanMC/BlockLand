package com.blockland.client.font;

import com.blockland.BlockLand;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix3x2f;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BlockLand's own text renderer, used by every mod screen and HUD: Noto Sans SC (SIL OFL 1.1, {@code assets/blockland/
 * fonts/}) opened with FreeType, glyphs rasterised on demand as signed distance fields into {@link GlyphAtlas} pages
 * (on a worker thread, a few per frame on the render thread) and drawn through the GUI render state with the
 * {@code blockland:core/ui_text} shader — crisp at any GUI scale, fractional position and animated size.
 * <p>
 * Sizes follow vanilla text so layouts keep working: a line is {@link #LINE_HEIGHT} GUI pixels, text is placed by the
 * top of its line, colours, alpha and the drop shadow behave like {@code GuiGraphics.drawString}. Styles from
 * components and § codes apply (colour, bold — the real bold face —, italic, underline, strike-through). Characters the
 * font lacks (emoji, rare symbols) are drawn with the vanilla font; when the font cannot be loaded at all, everything is.
 * Render thread only.
 */
public final class UiFont {
    /** Height of a line of text in GUI pixels (as vanilla's font). */
    public static final int LINE_HEIGHT = 9;
    /** Em size of normal text in GUI pixels: CJK characters are as tall as vanilla's, Latin a little finer. */
    private static final float EM = 9f;
    /** Baseline below the top of the line: centres text where vanilla's sits. */
    private static final float BASELINE = 6.9f;
    private static final float ITALIC_SKEW = 0.2f;
    private static final float SHADOW_OFFSET = 1f;
    /** Underline and strike-through: top and thickness, from the baseline (down positive). */
    private static final float UNDERLINE = 1.2f, STRIKE = -2.6f, LINE = 0.8f;

    static final int REGULAR = 0, BOLD = 1;
    private static final String[] FILES = {"fonts/notosanssc-regular.otf", "fonts/notosanssc-bold.otf"};
    /** Distance-field tiers: em size and spread in pixels; small text uses the first, big text the second. */
    private static final int[] TIER_SIZE = {32, 64};
    private static final int[] TIER_SPREAD = {4, 8};
    /** Screen pixels per em above which the big tier is used. */
    private static final float BIG_TIER_FROM = 40;
    /** Rasterising on the render thread stops for the frame after this; the rest goes to the worker. */
    private static final long FRAME_BUDGET_NANOS = 3_000_000L;
    /** Most finished glyphs uploaded at once (the warm-up finishes hundreds while the game loads). */
    private static final int UPLOADS_PER_DRAIN = 256;
    private static final int CACHED_LAYOUTS = 2048;

    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(BlockLand.id("pipeline/ui_text"))
            .withVertexShader(BlockLand.id("core/ui_text"))
            .withFragmentShader(BlockLand.id("core/ui_text"))
            .build();

    private static final GlyphRasterizer rasterizer = new GlyphRasterizer();
    private static boolean loaded, available;
    private static int generation;
    private static FontFace[] faces = new FontFace[0];
    /** Font units → GUI pixels at {@link #EM}, per face. */
    private static float[] unitScale = new float[0];
    @Nullable
    private static GlyphAtlas atlas;
    @SuppressWarnings("unchecked")
    private static final Int2ObjectOpenHashMap<Metrics>[] metrics = new Int2ObjectOpenHashMap[]{new Int2ObjectOpenHashMap<>(), new Int2ObjectOpenHashMap<>()};
    private static final Long2ObjectOpenHashMap<Glyph> glyphs = new Long2ObjectOpenHashMap<>();
    /** Queued glyphs that were needed on screen before the worker got to them: moved to the front once. */
    private static final LongOpenHashSet promoted = new LongOpenHashSet();
    private static final Map<String, Laid> layouts = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Laid> eldest) {
            return size() > CACHED_LAYOUTS;
        }
    };
    private static long budgetLeft = FRAME_BUDGET_NANOS;

    // scratch for building quads (render thread)
    private static final List<Batch> batches = new ArrayList<>();
    private static int batchesUsed;
    private static final List<Integer> fallbacks = new ArrayList<>();

    private UiFont() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> reload()));
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> {
            budgetLeft = FRAME_BUDGET_NANOS;
            if (available) drainResults();
        });
    }

    // ---- measuring ------------------------------------------------------------------------------

    /** Width in GUI pixels, rounded up (as vanilla's {@code Font.width}). */
    public static int width(String text) {
        return ceil(widthF(text));
    }

    public static int width(FormattedText text) {
        if (!ensureLoaded()) return Minecraft.getInstance().font.width(text);
        return ceil(lay(text).width);
    }

    public static int width(FormattedCharSequence text) {
        if (text instanceof Line line) return ceil(line.width);
        if (!ensureLoaded()) return Minecraft.getInstance().font.width(text);
        return ceil(lay(text).width);
    }

    /** Exact width in GUI pixels. */
    public static float widthF(String text) {
        if (text.isEmpty()) return 0;
        if (!ensureLoaded()) return Minecraft.getInstance().font.width(text);
        return lay(text).width;
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /**
     * Draws text with the top of its line at {@code y}.
     *
     * @return x after the text
     */
    public static float draw(GuiGraphics g, String text, float x, float y, int color, boolean shadow) {
        return draw(g, text, x, y, 1, color, shadow);
    }

    /** Draws text scaled about its top-left corner. */
    public static float draw(GuiGraphics g, String text, float x, float y, float scale, int color, boolean shadow) {
        if (text.isEmpty()) return x;
        if (!ensureLoaded()) return vanilla(g, FormattedCharSequence.forward(text, Style.EMPTY), x, y, scale, color, shadow);
        Laid laid = lay(text);
        render(g, laid, 0, laid.count, x, y, scale, color, shadow);
        return x + laid.width * scale;
    }

    public static float draw(GuiGraphics g, FormattedText text, float x, float y, int color, boolean shadow) {
        if (!ensureLoaded()) return vanilla(g, net.minecraft.locale.Language.getInstance().getVisualOrder(text), x, y, 1, color, shadow);
        Laid laid = lay(text);
        render(g, laid, 0, laid.count, x, y, 1, color, shadow);
        return x + laid.width;
    }

    public static float draw(GuiGraphics g, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        return draw(g, text, x, y, 1, color, shadow);
    }

    public static float draw(GuiGraphics g, FormattedCharSequence text, float x, float y, float scale, int color, boolean shadow) {
        if (!ensureLoaded()) return vanilla(g, text, x, y, scale, color, shadow);
        if (text instanceof Line line) {
            render(g, line.laid, line.start, line.end, x, y, scale, color, shadow);
            return x + line.width * scale;
        }
        Laid laid = lay(text);
        render(g, laid, 0, laid.count, x, y, scale, color, shadow);
        return x + laid.width * scale;
    }

    /** Draws text centred on {@code centerX}. */
    public static void drawCentered(GuiGraphics g, String text, float centerX, float y, int color, boolean shadow) {
        draw(g, text, centerX - widthF(text) / 2, y, color, shadow);
    }

    public static void drawCentered(GuiGraphics g, FormattedText text, float centerX, float y, int color, boolean shadow) {
        float width = ensureLoaded() ? lay(text).width : Minecraft.getInstance().font.width(text);
        draw(g, text, centerX - width / 2, y, color, shadow);
    }

    // ---- layout -----------------------------------------------------------------------------------

    /** The text, cut with an ellipsis ("…") when it is wider than {@code maxWidth}. */
    public static String fit(String text, float maxWidth) {
        if (!ensureLoaded()) {
            var font = Minecraft.getInstance().font;
            if (font.width(text) <= maxWidth) return text;
            return font.plainSubstrByWidth(text, Math.max(0, (int) maxWidth - font.width("…"))) + "…";
        }
        Laid laid = lay(text);
        if (laid.width <= maxWidth) return text;
        int keep = TextLayout.fit(laid.chars, laid.advances, laid.count, maxWidth, lay("…").width);
        return text.substring(0, laid.source(keep)).stripTrailing() + "…";
    }

    /** The longest start of the text that fits {@code maxWidth} (no ellipsis). */
    public static String substrByWidth(String text, float maxWidth) {
        if (!ensureLoaded()) return Minecraft.getInstance().font.plainSubstrByWidth(text, (int) maxWidth);
        Laid laid = lay(text);
        float width = 0;
        int keep = 0;
        while (keep < laid.count && width + laid.advances[keep] <= maxWidth) width += laid.advances[keep++];
        return text.substring(0, laid.source(keep));
    }

    /** Wraps text into lines no wider than {@code maxWidth} (CJK-aware, see {@link TextLayout}); {@code \n} breaks. */
    public static List<FormattedCharSequence> split(String text, float maxWidth) {
        if (!ensureLoaded()) return Minecraft.getInstance().font.split(FormattedText.of(text), Math.max(1, (int) maxWidth));
        return lines(lay(text), maxWidth);
    }

    public static List<FormattedCharSequence> split(FormattedText text, float maxWidth) {
        if (!ensureLoaded()) return Minecraft.getInstance().font.split(text, Math.max(1, (int) maxWidth));
        return lines(lay(text), maxWidth);
    }

    private static List<FormattedCharSequence> lines(Laid laid, float maxWidth) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (TextLayout.Line line : TextLayout.wrap(laid.chars, laid.advances, laid.count, Math.max(1, maxWidth))) {
            lines.add(new Line(laid, line.start(), line.end(), line.width()));
        }
        return lines;
    }

    /** A wrapped line: a range of laid-out text (drawn without laying it out again). */
    private static final class Line implements FormattedCharSequence {
        final Laid laid;
        final int start, end;
        final float width;

        Line(Laid laid, int start, int end, float width) {
            this.laid = laid;
            this.start = start;
            this.end = end;
            this.width = width;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            for (int i = start; i < end; i++) {
                if (!sink.accept(i - start, laid.styles[i], laid.chars[i])) return false;
            }
            return true;
        }
    }

    // ---- loading ----------------------------------------------------------------------------------

    private static boolean ensureLoaded() {
        if (loaded) return available;
        loaded = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            byte[][] files = new byte[FILES.length][];
            for (int i = 0; i < FILES.length; i++) {
                try (InputStream in = mc.getResourceManager().getResourceOrThrow(BlockLand.id(FILES[i])).open()) {
                    files[i] = in.readAllBytes();
                }
            }
            faces = new FontFace[files.length];
            unitScale = new float[files.length];
            for (int i = 0; i < files.length; i++) {
                faces[i] = FontFace.open(files[i]);
                unitScale[i] = EM / faces[i].unitsPerEm;
            }
            generation++;
            rasterizer.setFonts(generation, files);
            atlas = new GlyphAtlas();
            available = true;
            warmUp();
            BlockLand.LOGGER.info("[BlockLand] UI font loaded ({} faces)", faces.length);
        } catch (Exception | LinkageError e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot load the UI font, using the vanilla font", e);
            closeFaces();
            available = false;
        }
        return available;
    }

    /**
     * Resource reload (also the first load and a language change): the font is opened again (resource packs may
     * replace it) and the worker starts on the language's characters while the game finishes loading.
     */
    private static void reload() {
        closeFaces();
        if (atlas != null) atlas.close();
        atlas = null;
        for (Int2ObjectOpenHashMap<Metrics> map : metrics) map.clear();
        glyphs.clear();
        promoted.clear();
        layouts.clear();
        loaded = false;
        available = false;
        ensureLoaded();
    }

    private static void closeFaces() {
        for (FontFace face : faces) if (face != null) face.close();
        faces = new FontFace[0];
    }

    /** Queues the characters of the current language file (and ASCII) for the worker, so screens open with their text ready. */
    private static void warmUp() {
        StringBuilder text = new StringBuilder();
        for (int c = 0x20; c < 0x7F; c++) text.append((char) c);
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        Optional<Resource> lang = Minecraft.getInstance().getResourceManager().getResource(BlockLand.id("lang/" + language + ".json"));
        if (lang.isPresent()) {
            try (Reader reader = new InputStreamReader(lang.get().open(), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, JsonElement> entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) text.append(entry.getValue().getAsString());
                }
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Cannot read {} for the UI font warm-up", lang, e);
            }
        }
        text.codePoints().distinct().forEach(c -> {
            warm(REGULAR, 0, c);
            if (c < 0x80) {
                warm(BOLD, 0, c);
                warm(REGULAR, 1, c);
            }
        });
    }

    private static void warm(int face, int tier, int codepoint) {
        // straight from the face: metrics may fall back to the vanilla font, which can still be reloading here
        int glyph = codepoint < 0x20 ? 0 : faces[face].glyph(codepoint);
        if (glyph <= 0) return;
        long key = key(face, tier, glyph);
        if (glyphs.containsKey(key)) return;
        glyphs.put(key, Glyph.PENDING);
        rasterizer.submit(new GlyphRasterizer.Request(key, face, glyph, TIER_SIZE[tier], TIER_SPREAD[tier], generation), false);
    }

    // ---- glyphs -----------------------------------------------------------------------------------

    /**
     * Glyph index (0 = not in the font, -1 = nothing to draw), advance and drawing offset of a character in a face.
     * The font's Chinese forms of the middle dot and the multiplication sign are a full em wide; as separators in UI text
     * ("全自动 · 红点镜", "×2") they get a narrow advance, the glyph centred in it.
     */
    private record Metrics(int glyph, float advance, float shift) {
    }

    /** Narrow advance (em) for full-width punctuation used in Latin-style UI text, 0 = the font's own. */
    private static float narrow(int codepoint) {
        return switch (codepoint) {
            case 0x00B7 -> 0.34f;
            case 0x00D7 -> 0.62f;
            default -> 0;
        };
    }

    private static Metrics metrics(int face, int codepoint) {
        Metrics m = metrics[face].get(codepoint);
        if (m == null) {
            if (codepoint < 0x20 || codepoint >= 0x7F && codepoint < 0xA0 || codepoint == 0x200B || codepoint == 0xFEFF) {
                m = new Metrics(-1, 0, 0);
            } else {
                int glyph = faces[face].glyph(codepoint);
                if (glyph != 0) {
                    float advance = faces[face].advance(glyph) * unitScale[face];
                    float narrow = narrow(codepoint) * EM;
                    m = narrow > 0 && narrow < advance ? new Metrics(glyph, narrow, (narrow - advance) / 2) : new Metrics(glyph, advance, 0);
                } else {
                    // not in our font: the vanilla font draws it, at vanilla's width
                    m = new Metrics(0, Minecraft.getInstance().font.width(FormattedText.of(new String(Character.toChars(codepoint)),
                            face == BOLD ? Style.EMPTY.withBold(true) : Style.EMPTY)), 0);
                }
            }
            metrics[face].put(codepoint, m);
        }
        return m;
    }

    private static long key(int face, int tier, int glyph) {
        return (long) face << 40 | (long) tier << 32 | (glyph & 0xFFFFFFFFL);
    }

    /** A rasterised glyph: atlas region and its box in GUI pixels at {@link #EM} (left of the pen, up from the baseline). */
    private static final class Glyph {
        static final Glyph PENDING = new Glyph(null, 0, 0, 0, 0);
        static final Glyph EMPTY = new Glyph(null, 0, 0, 0, 0);

        @Nullable
        final GlyphAtlas.Region region;
        final float left, top, width, height;

        Glyph(@Nullable GlyphAtlas.Region region, float left, float top, float width, float height) {
            this.region = region;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    /** The glyph at a tier: ready, or queued (null this frame), rasterised right here while the frame's budget lasts. */
    @Nullable
    private static Glyph glyph(int face, int tier, int glyph) {
        long key = key(face, tier, glyph);
        Glyph placed = glyphs.get(key);
        if (placed == Glyph.PENDING && promoted.add(key)) {
            rasterizer.submit(new GlyphRasterizer.Request(key, face, glyph, TIER_SIZE[tier], TIER_SPREAD[tier], generation), true);
        }
        if (placed == null) {
            if (budgetLeft > 0) {
                long start = System.nanoTime();
                FontFace.Sdf sdf = null;
                try {
                    sdf = faces[face].sdf(glyph, TIER_SIZE[tier], TIER_SPREAD[tier]);
                } catch (RuntimeException e) {
                    BlockLand.LOGGER.warn("[BlockLand] Cannot rasterise glyph {} of the UI font", glyph, e);
                }
                placed = place(key, sdf);
                budgetLeft -= System.nanoTime() - start;
            } else {
                placed = Glyph.PENDING;
                glyphs.put(key, placed);
                promoted.add(key);
                rasterizer.submit(new GlyphRasterizer.Request(key, face, glyph, TIER_SIZE[tier], TIER_SPREAD[tier], generation), true);
            }
        }
        return placed.region != null ? placed : null;
    }

    private static Glyph place(long key, @Nullable FontFace.Sdf sdf) {
        Glyph placed;
        if (sdf == null || atlas == null) {
            placed = Glyph.EMPTY;
        } else {
            float scale = EM / TIER_SIZE[(int) (key >>> 32) & 0xFF];
            placed = new Glyph(atlas.add(sdf.width(), sdf.height(), sdf.pixels()), sdf.left() * scale, sdf.top() * scale,
                    sdf.width() * scale, sdf.height() * scale);
        }
        glyphs.put(key, placed);
        return placed;
    }

    /** Uploads what the worker finished. */
    private static void drainResults() {
        GlyphRasterizer.Result result;
        for (int i = 0; i < UPLOADS_PER_DRAIN && (result = rasterizer.poll()) != null; i++) {
            if (result.generation() == generation) place(result.key(), result.sdf());
        }
    }

    // ---- laying out ---------------------------------------------------------------------------------

    /**
     * Text laid out at size 1: its characters, glyphs and pen positions in GUI pixels.
     *
     * @param sources  index of each character in the source string (§ codes skipped), for cutting it
     * @param advances advance of each character, kerning with the one before it included
     * @param shifts   where each glyph is drawn relative to its pen position
     */
    private record Laid(int count, int[] chars, int[] sources, int[] glyphs, byte[] faces, float[] x, float[] advances,
                        float[] shifts, Style[] styles, float width, int sourceLength) {
        /** Index in the source string where character {@code i} starts (its length for {@code count}). */
        int source(int i) {
            return i < count ? sources[i] : sourceLength;
        }
    }

    private static Laid lay(String text) {
        Laid laid = layouts.get(text);
        if (laid == null) {
            Builder builder = new Builder();
            StringDecomposer.iterateFormatted(text, Style.EMPTY, builder);
            laid = builder.build(text.length());
            layouts.put(text, laid);
        }
        return laid;
    }

    private static Laid lay(FormattedText text) {
        Builder builder = new Builder();
        StringDecomposer.iterateFormatted(text, Style.EMPTY, builder);
        return builder.build(builder.count);
    }

    private static Laid lay(FormattedCharSequence text) {
        Builder builder = new Builder();
        text.accept(builder);
        return builder.build(builder.count);
    }

    private static final class Builder implements FormattedCharSink {
        int count;
        int[] chars = new int[32];
        int[] sources = new int[32];
        Style[] styles = new Style[32];

        @Override
        public boolean accept(int index, Style style, int codepoint) {
            if (count == chars.length) {
                chars = Arrays.copyOf(chars, count * 2);
                sources = Arrays.copyOf(sources, count * 2);
                styles = Arrays.copyOf(styles, count * 2);
            }
            chars[count] = codepoint;
            sources[count] = index;
            styles[count] = style;
            count++;
            return true;
        }

        Laid build(int sourceLength) {
            int[] glyphIds = new int[count];
            byte[] faceIds = new byte[count];
            float[] x = new float[count];
            float[] advances = new float[count];
            float[] shifts = new float[count];
            float pen = 0;
            for (int i = 0; i < count; i++) {
                int face = styles[i].isBold() ? BOLD : REGULAR;
                Metrics m = metrics(face, chars[i]);
                float kern = 0;
                if (i > 0 && m.glyph > 0 && glyphIds[i - 1] > 0 && faceIds[i - 1] == face) {
                    kern = faces[face].kerning(glyphIds[i - 1], m.glyph) * unitScale[face];
                }
                glyphIds[i] = m.glyph;
                faceIds[i] = (byte) face;
                x[i] = pen + kern;
                advances[i] = kern + m.advance;
                shifts[i] = m.shift;
                pen = x[i] + m.advance;
            }
            return new Laid(count, Arrays.copyOf(chars, count), Arrays.copyOf(sources, count), glyphIds, faceIds, x, advances, shifts,
                    Arrays.copyOf(styles, count), pen, sourceLength);
        }
    }

    // ---- rendering ----------------------------------------------------------------------------------

    /** Quads of one draw call that share an atlas page: shadows first, then the text over them. */
    private static final class Batch {
        @Nullable
        GlyphAtlas.Page page;
        float[] corners = new float[64], uvs = new float[32];
        int[] colors = new int[8];
        int quads;
        float[] shadowCorners = new float[64], shadowUvs = new float[32];
        int[] shadowColors = new int[8];
        int shadowQuads;
        float minX, minY, maxX, maxY;

        void reset(GlyphAtlas.Page page) {
            this.page = page;
            quads = shadowQuads = 0;
            minX = minY = Float.MAX_VALUE;
            maxX = maxY = -Float.MAX_VALUE;
        }

        void add(boolean shadow, float x0, float y0, float x1, float y1, float lean0, float lean1, GlyphAtlas.Region region, int color) {
            int n = shadow ? shadowQuads : quads;
            float[] c = shadow ? shadowCorners : corners, t = shadow ? shadowUvs : uvs;
            int[] col = shadow ? shadowColors : colors;
            if ((n + 1) * 8 > c.length) {
                c = Arrays.copyOf(c, c.length * 2);
                t = Arrays.copyOf(t, t.length * 2);
                col = Arrays.copyOf(col, col.length * 2);
            }
            int o = n * 8;
            // lean: top and bottom edges shifted for italics
            c[o] = x0 + lean0;
            c[o + 1] = y0;
            c[o + 2] = x0 + lean1;
            c[o + 3] = y1;
            c[o + 4] = x1 + lean1;
            c[o + 5] = y1;
            c[o + 6] = x1 + lean0;
            c[o + 7] = y0;
            t[n * 4] = region.u0();
            t[n * 4 + 1] = region.v0();
            t[n * 4 + 2] = region.u1();
            t[n * 4 + 3] = region.v1();
            col[n] = color;
            if (shadow) {
                shadowCorners = c;
                shadowUvs = t;
                shadowColors = col;
                shadowQuads++;
            } else {
                corners = c;
                uvs = t;
                colors = col;
                quads++;
            }
            minX = Math.min(minX, Math.min(x0 + lean1, x0 + lean0));
            maxX = Math.max(maxX, Math.max(x1 + lean0, x1 + lean1));
            minY = Math.min(minY, y0);
            maxY = Math.max(maxY, y1);
        }

        void submit(GuiGraphics g, Matrix3x2f pose, @Nullable ScreenRectangle scissor) {
            int total = shadowQuads + quads;
            if (total == 0 || page == null) return;
            int x = (int) Math.floor(minX), y = (int) Math.floor(minY);
            ScreenRectangle area = new ScreenRectangle(x, y, Math.max(1, (int) Math.ceil(maxX) - x), Math.max(1, (int) Math.ceil(maxY) - y))
                    .transformMaxBounds(pose);
            ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
            if (bounds == null) return;
            float[] c = new float[total * 8], t = new float[total * 4];
            int[] col = new int[total];
            System.arraycopy(shadowCorners, 0, c, 0, shadowQuads * 8);
            System.arraycopy(corners, 0, c, shadowQuads * 8, quads * 8);
            System.arraycopy(shadowUvs, 0, t, 0, shadowQuads * 4);
            System.arraycopy(uvs, 0, t, shadowQuads * 4, quads * 4);
            System.arraycopy(shadowColors, 0, col, 0, shadowQuads);
            System.arraycopy(colors, 0, col, shadowQuads, quads);
            g.getRenderState().submitGuiElement(new TextQuads(PIPELINE, page.setup, pose, c, t, col, total, scissor, bounds));
        }
    }

    private static Batch batch(GlyphAtlas.Page page) {
        for (int i = 0; i < batchesUsed; i++) if (batches.get(i).page == page) return batches.get(i);
        if (batchesUsed == batches.size()) batches.add(new Batch());
        Batch batch = batches.get(batchesUsed++);
        batch.reset(page);
        return batch;
    }

    /** Draws characters {@code [from, to)} of laid-out text with the first one's pen at {@code x}. */
    private static void render(GuiGraphics g, Laid laid, int from, int to, float x, float y, float scale, int color, boolean shadow) {
        if (ARGB.alpha(color) == 0 || from >= to || atlas == null) return;
        drainResults();
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        float guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float poseScale = (float) Math.sqrt(Math.abs(pose.m00() * pose.m11() - pose.m01() * pose.m10()));
        int tier = EM * scale * poseScale * guiScale > BIG_TIER_FROM ? 1 : 0;
        // the baseline on a whole screen pixel: horizontal strokes stay sharp
        float baseline = y + BASELINE * scale;
        if (pose.m01() == 0 && pose.m10() == 0 && pose.m11() != 0) {
            float screen = (pose.m11() * baseline + pose.m21()) * guiScale;
            baseline = (Math.round(screen) / guiScale - pose.m21()) / pose.m11();
        }
        float origin = laid.x[from];
        batchesUsed = 0;
        fallbacks.clear();
        float shadowOffset = SHADOW_OFFSET * scale;
        for (int i = from; i < to; i++) {
            int glyphId = laid.glyphs[i];
            if (glyphId < 0) continue;
            Style style = laid.styles[i];
            int argb = colorOf(style, color);
            float pen = x + (laid.x[i] - origin) * scale;
            if (glyphId == 0) {
                fallbacks.add(i);
            } else {
                Glyph glyph = glyph(laid.faces[i], tier, glyphId);
                if (glyph != null) {
                    float x0 = pen + (glyph.left + laid.shifts[i]) * scale, y0 = baseline - glyph.top * scale;
                    float x1 = x0 + glyph.width * scale, y1 = y0 + glyph.height * scale;
                    float lean0 = style.isItalic() ? (baseline - y0) * ITALIC_SKEW : 0;
                    float lean1 = style.isItalic() ? (baseline - y1) * ITALIC_SKEW : 0;
                    Batch batch = batch(glyph.region.page());
                    if (shadow) batch.add(true, x0 + shadowOffset, y0 + shadowOffset, x1 + shadowOffset, y1 + shadowOffset, lean0, lean1,
                            glyph.region, ARGB.scaleRGB(argb, 0.25f));
                    batch.add(false, x0, y0, x1, y1, lean0, lean1, glyph.region, argb);
                }
            }
            if (style.isUnderlined() || style.isStrikethrough()) {
                GlyphAtlas.Region solid = atlas.solid();
                Batch batch = batch(solid.page());
                float x1 = pen + laid.advances[i] * scale;
                if (i + 1 < to) x1 = x + (laid.x[i + 1] - origin) * scale;
                for (int pass = 0; pass < 2; pass++) {
                    float top = pass == 0 ? UNDERLINE : STRIKE;
                    if (pass == 0 ? !style.isUnderlined() : !style.isStrikethrough()) continue;
                    float y0 = baseline + top * scale, y1 = y0 + LINE * scale;
                    if (shadow) batch.add(true, pen + shadowOffset, y0 + shadowOffset, x1 + shadowOffset, y1 + shadowOffset, 0, 0,
                            solid, ARGB.scaleRGB(argb, 0.25f));
                    batch.add(false, pen, y0, x1, y1, 0, 0, solid, argb);
                }
            }
        }
        ScreenRectangle scissor = g.getScissorStack().peek();
        for (int i = 0; i < batchesUsed; i++) batches.get(i).submit(g, pose, scissor);
        // vanilla glyphs sit 7 pixels above their baseline
        for (int i : fallbacks) {
            vanilla(g, FormattedCharSequence.codepoint(laid.chars[i], laid.styles[i]), x + (laid.x[i] - origin) * scale,
                    baseline - 7 * scale, scale, color, shadow);
        }
    }

    private static int colorOf(Style style, int color) {
        TextColor textColor = style.getColor();
        return textColor == null ? color : ARGB.color(ARGB.alpha(color), textColor.getValue());
    }

    /** Draws with the vanilla font at a fractional position and scale (fallback). */
    private static float vanilla(GuiGraphics g, FormattedCharSequence text, float x, float y, float scale, int color, boolean shadow) {
        var font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        if (scale != 1) g.pose().scale(scale, scale);
        g.drawString(font, text, 0, 0, color, shadow);
        g.pose().popMatrix();
        return x + font.width(text) * scale;
    }

    private static int ceil(float width) {
        return (int) Math.ceil(width - 0.001f);
    }
}
