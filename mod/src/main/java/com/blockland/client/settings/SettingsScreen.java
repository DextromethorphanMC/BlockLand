package com.blockland.client.settings;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.scope.ScopeQuality;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The lobby's settings page (设置): 通用 (language), 枪械 (look and aim-down-sights sensitivity, crosshair and hit
 * marker style, with a live preview) and 隐私 (party invitations). Client preferences are saved on this computer as
 * they change; the privacy choice is sent to the server, which stores and enforces it.
 */
public final class SettingsScreen extends Screen {
    private static final int PAD = 16;
    private static final int ROW_H = 22;
    private static final int[] SWATCHES = {0xFFFFFF, 0x4CFF6A, 0x4CE6FF, 0xFFE34C, 0xFF4C4C, 0xFF4CF5, 0xFFA235, 0x8C7CFF};
    private static final String[][] LANGUAGES = {{"zh_cn", "简体中文"}, {"en_us", "English"}};

    private enum Tab {
        GENERAL("blockland.settings.tab.general"), WEAPONS("blockland.settings.tab.weapons"), PRIVACY("blockland.settings.tab.privacy");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static Tab tab = Tab.WEAPONS;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Spring> hover = new HashMap<>();
    private final AnimValue intro = new AnimValue(0);
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private float scroll;
    private int contentTop, contentBottom, contentHeight;
    private int columnX, columnW;
    private Row dragging;
    /** Which colour the RGB sliders edit. */
    private ColorTarget colorTarget = ColorTarget.CROSSHAIR;
    private double nextPreviewHit;
    private int previewKind;

    private enum ColorTarget {
        CROSSHAIR("blockland.settings.crosshair.color"), HIT("blockland.settings.hitmarker.hit_color"),
        CRITICAL("blockland.settings.hitmarker.critical_color"), KILL("blockland.settings.hitmarker.kill_color");

        final String key;

        ColorTarget(String key) {
            this.key = key;
        }
    }

    public SettingsScreen(Screen parent) {
        super(Component.translatable("blockland.settings.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        Anim.to(intro, 1, 0.4).ease(Ease.QUART_OUT);
        rebuild();
    }

    @Override
    public void onClose() {
        ClientSettings.save();
        minecraft.setScreen(parent);
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    private void rebuild() {
        rows.clear();
        ClientSettings.Data data = ClientSettings.get();
        switch (tab) {
            case GENERAL -> {
                rows.add(new Header("blockland.settings.section.language"));
                rows.add(new Choice("blockland.settings.language", () -> {
                    String code = Minecraft.getInstance().options.languageCode;
                    return code.equals("en_us") ? 1 : 0;
                }, index -> setLanguage(LANGUAGES[index][0]), LANGUAGES[0][1], LANGUAGES[1][1]));
                rows.add(new Note("blockland.settings.language_note"));
            }
            case WEAPONS -> {
                rows.add(new Header("blockland.settings.section.sensitivity"));
                rows.add(new Slider("blockland.settings.look_sensitivity", 0, 1, 0.005,
                        () -> Minecraft.getInstance().options.sensitivity().get(),
                        v -> {
                            Minecraft.getInstance().options.sensitivity().set(v);
                            Minecraft.getInstance().options.save();
                        },
                        v -> Math.round(v * 200) + "%"));
                rows.add(new Slider("blockland.settings.ads_sensitivity", 0.1, 2, 0.05,
                        () -> data.adsSensitivity, v -> data.adsSensitivity = (float) v, v -> String.format("%.2f×", v)));

                rows.add(new Header("blockland.settings.section.scope"));
                rows.add(new Choice("blockland.settings.scope.quality", () -> ScopeQuality.of(data.scopeQuality).ordinal(),
                        i -> data.scopeQuality = ScopeQuality.values()[i].id(),
                        tr("blockland.settings.scope.quality.off"), tr("blockland.settings.scope.quality.low"),
                        tr("blockland.settings.scope.quality.medium"), tr("blockland.settings.scope.quality.high")));
                rows.add(new Toggle("blockland.settings.scope.blur_outside", () -> data.scopeBlurOutside, v -> data.scopeBlurOutside = v));
                rows.add(new Toggle("blockland.settings.scope.dim_outside", () -> data.scopeDimOutside, v -> data.scopeDimOutside = v));
                rows.add(new Slider("blockland.settings.scope.sensitivity", 0.2, 2, 0.05,
                        () -> data.scopeSensitivity, v -> data.scopeSensitivity = (float) v, v -> String.format("%.2f×", v)));
                rows.add(new Note("blockland.settings.scope.note"));

                rows.add(new Header("blockland.settings.section.camera"));
                rows.add(new Slider("blockland.settings.camera.impact", 0, 1.5, 0.05,
                        () -> data.cameraImpact, v -> data.cameraImpact = (float) v, v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.camera.motion", 0, 1.5, 0.05,
                        () -> data.cameraMotion, v -> data.cameraMotion = (float) v, v -> Math.round(v * 100) + "%"));
                rows.add(new Note("blockland.settings.camera.note"));

                Crosshair.Style c = data.crosshair;
                rows.add(new Header("blockland.settings.section.crosshair"));
                rows.add(new Choice("blockland.settings.crosshair.shape", () -> Crosshair.Shape.of(c.shape).ordinal(),
                        i -> c.shape = Crosshair.Shape.values()[i].id(),
                        tr("blockland.settings.crosshair.shape.cross"), tr("blockland.settings.crosshair.shape.cross_dot"),
                        tr("blockland.settings.crosshair.shape.t"), tr("blockland.settings.crosshair.shape.dot"),
                        tr("blockland.settings.crosshair.shape.circle")));
                rows.add(new Swatches(ColorTarget.CROSSHAIR, () -> c.color, v -> c.color = v));
                rows.add(new Slider("blockland.settings.crosshair.opacity", 0.1, 1, 0.05, () -> c.opacity, v -> c.opacity = (float) v,
                        v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.crosshair.length", 1, 14, 1, () -> c.length, v -> c.length = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.crosshair.thickness", 1, 4, 1, () -> c.thickness, v -> c.thickness = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.crosshair.gap", 0, 12, 1, () -> c.gap, v -> c.gap = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Toggle("blockland.settings.crosshair.outline", () -> c.outline, v -> c.outline = v));
                rows.add(new Toggle("blockland.settings.crosshair.dynamic", () -> c.dynamic, v -> c.dynamic = v));

                HitMarker.Style h = data.hitmarker;
                rows.add(new Header("blockland.settings.section.hitmarker"));
                rows.add(new Choice("blockland.settings.hitmarker.shape", () -> HitMarker.Shape.of(h.shape).ordinal(),
                        i -> h.shape = HitMarker.Shape.values()[i].id(),
                        tr("blockland.settings.hitmarker.shape.x"), tr("blockland.settings.hitmarker.shape.cross"),
                        tr("blockland.settings.hitmarker.shape.ring")));
                rows.add(new Swatches(ColorTarget.HIT, () -> h.hitColor, v -> h.hitColor = v));
                rows.add(new Swatches(ColorTarget.CRITICAL, () -> h.criticalColor, v -> h.criticalColor = v));
                rows.add(new Swatches(ColorTarget.KILL, () -> h.killColor, v -> h.killColor = v));
                rows.add(new Slider("blockland.settings.hitmarker.size", 0.5, 2, 0.05, () -> h.size, v -> h.size = (float) v,
                        v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.hitmarker.thickness", 1, 3, 1, () -> h.thickness, v -> h.thickness = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.hitmarker.duration", 0.5, 2, 0.05, () -> h.duration, v -> h.duration = (float) v,
                        v -> Math.round(v * 100) + "%"));

                rows.add(new Header("blockland.settings.section.rgb"));
                rows.add(new Slider("blockland.settings.rgb.r", 0, 255, 1, () -> channel(16), v -> setChannel(16, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.rgb.g", 0, 255, 1, () -> channel(8), v -> setChannel(8, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.rgb.b", 0, 255, 1, () -> channel(0), v -> setChannel(0, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Action("blockland.settings.reset_weapons", () -> {
                    data.adsSensitivity = 1;
                    data.scopeQuality = ScopeQuality.MEDIUM.id();
                    data.scopeBlurOutside = true;
                    data.scopeDimOutside = false;
                    data.scopeSensitivity = 1;
                    data.cameraImpact = 1;
                    data.cameraMotion = 1;
                    data.crosshair = new Crosshair.Style();
                    data.hitmarker = new HitMarker.Style();
                    ClientSettings.save();
                    rebuild();
                }));
            }
            case PRIVACY -> {
                rows.add(new Header("blockland.settings.section.party"));
                rows.add(new Toggle("blockland.settings.allow_invites", ClientLobbyState::allowInvites,
                        v -> BLNetwork.sendToServer(new LobbyPackets.C2SSettings(v))));
                rows.add(new Note("blockland.settings.allow_invites_note"));
            }
        }
    }

    private int colorOf(ColorTarget target) {
        ClientSettings.Data data = ClientSettings.get();
        return switch (target) {
            case CROSSHAIR -> data.crosshair.color;
            case HIT -> data.hitmarker.hitColor;
            case CRITICAL -> data.hitmarker.criticalColor;
            case KILL -> data.hitmarker.killColor;
        };
    }

    private void setColor(ColorTarget target, int rgb) {
        ClientSettings.Data data = ClientSettings.get();
        switch (target) {
            case CROSSHAIR -> data.crosshair.color = rgb;
            case HIT -> data.hitmarker.hitColor = rgb;
            case CRITICAL -> data.hitmarker.criticalColor = rgb;
            case KILL -> data.hitmarker.killColor = rgb;
        }
    }

    private double channel(int shift) {
        return colorOf(colorTarget) >> shift & 0xFF;
    }

    private void setChannel(int shift, double value) {
        int rgb = colorOf(colorTarget);
        int v = (int) Math.round(Math.max(0, Math.min(255, value)));
        setColor(colorTarget, rgb & ~(0xFF << shift) | v << shift);
    }

    private void setLanguage(String code) {
        Minecraft mc = Minecraft.getInstance();
        if (code.equals(mc.options.languageCode)) return;
        mc.options.languageCode = code;
        mc.getLanguageManager().setSelected(code);
        mc.options.save();
        // the resource reload brings the new language to every screen, the lobby included
        mc.reloadResourcePacks();
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        g.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        Draw.glow(g, width * 0.7f, height * 0.4f, width * 0.45f, height * 0.5f, 0x223A6EA5);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        float a = intro.get();
        // header
        Draw.text(g,tr("blockland.settings.title"), PAD, 12, 1.6f, ColorMath.fade(TEXT, a), true);
        String hint = tr("blockland.settings.hint");
        Draw.text(g,hint, width - PAD - UiFont.width(hint), 16, 1f, ColorMath.fade(DIM, a), false);

        // tabs
        int tx = PAD, ty = 38;
        for (Tab t : Tab.values()) {
            String label = tr(t.key);
            int w = UiFont.width(label) + 16;
            boolean over = inside(mouseX, mouseY, tx, ty, w, 18);
            float hv = spring("tab" + t, over || t == tab);
            Draw.text(g,label, tx + 8, ty + 5 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (t == tab) {
                tabX.setTarget(tx);
                tabW.setTarget(w);
                if (tabW.get() == 0) {
                    tabX.set(tx);
                    tabW.set(w);
                }
            }
            tx += w + 4;
        }
        g.fill(Math.round(tabX.get()), ty + 18, Math.round(tabX.get() + tabW.get()), ty + 20, ColorMath.fade(ACCENT, a));
        g.fill(PAD, ty + 20, width - PAD, ty + 21, ColorMath.fade(LINE, a));

        // rows (scrollable), with the preview on the right for the weapons tab
        boolean preview = tab == Tab.WEAPONS && width > 520;
        columnX = PAD;
        columnW = preview ? Math.min(420, (int) (width * 0.56f)) : Math.min(560, width - PAD * 2);
        contentTop = ty + 30;
        contentBottom = height - PAD;
        contentHeight = 0;
        for (Row row : rows) contentHeight += row.height();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (contentBottom - contentTop))));
        g.enableScissor(columnX - 4, contentTop, columnX + columnW + 4, contentBottom);
        int y = contentTop - Math.round(scroll);
        for (Row row : rows) {
            int h = row.height();
            if (y + h > contentTop && y < contentBottom) row.render(g, mouseX, mouseY, columnX, y, columnW, a);
            row.y = y;
            y += h;
        }
        g.disableScissor();
        if (contentHeight > contentBottom - contentTop) {
            int view = contentBottom - contentTop;
            int barH = Math.max(16, view * view / contentHeight);
            int barY = contentTop + Math.round((view - barH) * scroll / Math.max(1, contentHeight - view));
            g.fill(columnX + columnW + 6, barY, columnX + columnW + 8, barY + barH, ColorMath.fade(0x55FFFFFF, a));
        }
        if (preview) renderPreview(g, mouseX, mouseY, columnX + columnW + 24, contentTop, width - PAD - (columnX + columnW + 24), a);
    }

    /** A dim scene with the crosshair in the middle; a hit marker plays now and then (hit, critical, kill in turn). */
    private void renderPreview(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
        int h = Math.min(w, contentBottom - y);
        Draw.rect(g, x, y, w, h, ColorMath.fade(0xFF0A0F17, a));
        g.fillGradient(x, y, x + w, y + h, ColorMath.fade(0xFF5B7C99, a * 0.7f), ColorMath.fade(0xFF2E3B2C, a * 0.7f));
        Draw.glow(g, x + w * 0.3f, y + h * 0.7f, w * 0.4f, h * 0.2f, ColorMath.fade(0x44000000, a));
        Draw.outline(g, x, y, w, h, ColorMath.fade(LINE, a));
        String label = tr("blockland.settings.preview");
        Draw.text(g,label, x + 6, y + 6, 1f, ColorMath.fade(MUTED, a), false);
        double now = Ticker.global().time();
        if (now >= nextPreviewHit) {
            nextPreviewHit = now + 1.4;
            previewKind = (previewKind + 1) % 3;
            HitMarker.show(previewKind == 1, previewKind == 2);
        }
        float cx = x + w / 2f, cy = y + h / 2f;
        // the spread breathes so a dynamic crosshair shows how it opens
        float spread = 4 + (float) (Math.sin(now * 1.7) * 0.5 + 0.5) * 8;
        Crosshair.render(g, cx, cy, spread, a);
        HitMarker.render(g, cx, cy);
        String kind = tr(previewKind == 2 ? "blockland.settings.preview.kill" : previewKind == 1 ? "blockland.settings.preview.critical"
                : "blockland.settings.preview.hit");
        Draw.text(g,kind, x + (w - UiFont.width(kind)) / 2f, y + h - 16, 1f, ColorMath.fade(DIM, a), false);
    }

    // ---- input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int tx = PAD, ty = 38;
        for (Tab t : Tab.values()) {
            int w = UiFont.width(tr(t.key)) + 16;
            if (inside(mouseX, mouseY, tx, ty, w, 18)) {
                if (t != tab) {
                    tab = t;
                    scroll = 0;
                    clickSound();
                    rebuild();
                }
                return true;
            }
            tx += w + 4;
        }
        if (mouseY >= contentTop && mouseY < contentBottom) {
            for (Row row : rows) {
                if (mouseY >= row.y && mouseY < row.y + row.height() && row.click(mouseX, mouseY)) {
                    ClientSettings.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != null) {
            dragging.drag(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            dragging = null;
            ClientSettings.save();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (float) scrollY * 24;
        return true;
    }

    // ---- row kinds ---------------------------------------------------------------------------------------------

    private abstract class Row {
        int y;

        int height() {
            return ROW_H;
        }

        abstract void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a);

        boolean click(double mouseX, double mouseY) {
            return false;
        }

        void drag(double mouseX) {
        }

        /** Where controls start: labels sit left of this. */
        int controlX(int x, int w) {
            return x + Math.min(170, w / 2);
        }
    }

    private final class Header extends Row {
        private final String key;

        Header(String key) {
            this.key = key;
        }

        @Override
        int height() {
            return 26;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            g.fill(x, y + 10, x + 3, y + 20, ColorMath.fade(ACCENT, a));
            Draw.text(g,tr(key), x + 8, y + 11, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    private final class Note extends Row {
        private final String key;

        Note(String key) {
            this.key = key;
        }

        @Override
        int height() {
            return 6 + Draw.wrap(tr(key), columnW - 8).size() * 11;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            int ly = y + 2;
            for (var line : Draw.wrap(tr(key), w - 8)) {
                Draw.text(g,line, x + 8, ly, ColorMath.fade(DIM, a));
                ly += 11;
            }
        }
    }

    private final class Slider extends Row {
        private final String key;
        private final double min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final java.util.function.DoubleFunction<String> format;
        private int barX, barW;

        Slider(String key, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
               java.util.function.DoubleFunction<String> format) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.format = format;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            barX = controlX(x, w);
            barW = w - (barX - x) - 46;
            double value = get.getAsDouble();
            float frac = (float) ((value - min) / (max - min));
            boolean over = inside(mouseX, mouseY, barX - 4, y, barW + 8, ROW_H) || dragging == this;
            float hv = spring("s" + key, over);
            g.fill(barX, y + 10, barX + barW, y + 12, ColorMath.fade(0x30FFFFFF, a));
            g.fill(barX, y + 10, barX + Math.round(barW * frac), y + 12, ColorMath.fade(ACCENT, a));
            int knob = barX + Math.round(barW * frac);
            Draw.glow(g, knob, y + 11, 5 + 3 * hv, 5 + 3 * hv, ColorMath.fade(ACCENT, a * 0.35f * hv));
            g.fill(knob - 3, y + 6, knob + 3, y + 16, ColorMath.fade(ColorMath.mix(TEXT, ACCENT, hv), a));
            String text = format.apply(value);
            Draw.text(g,text, x + w - 4 - UiFont.width(text), y + 7, 1f, ColorMath.fade(TEXT, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            if (mouseX < barX - 4 || mouseX > barX + barW + 4) return false;
            dragging = this;
            drag(mouseX);
            return true;
        }

        @Override
        void drag(double mouseX) {
            double frac = Math.max(0, Math.min(1, (mouseX - barX) / Math.max(1, barW)));
            double value = min + frac * (max - min);
            value = Math.round(value / step) * step;
            set.accept(Math.max(min, Math.min(max, value)));
            ClientSettings.get().crosshair.sanitize();
            ClientSettings.get().hitmarker.sanitize();
        }
    }

    private final class Toggle extends Row {
        private final String key;
        private final Supplier<Boolean> get;
        private final Consumer<Boolean> set;

        Toggle(String key, Supplier<Boolean> get, Consumer<Boolean> set) {
            this.key = key;
            this.get = get;
            this.set = set;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            boolean on = get.get();
            float knob = spring("t" + key, on);
            int sx = x + w - 34, sy = y + 5;
            Draw.rect(g, sx, sy, 28, 12, ColorMath.fade(ColorMath.mix(0xFF2A3344, GO, knob), a));
            int kx = sx + 2 + Math.round(14 * knob);
            Draw.rect(g, kx, sy + 2, 10, 8, ColorMath.fade(TEXT, a));
            String state = tr(on ? "blockland.settings.on" : "blockland.settings.off");
            Draw.text(g,state, sx - 6 - UiFont.width(state), y + 7, 1f, ColorMath.fade(on ? OK : DIM, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            set.accept(!get.get());
            clickSound();
            return true;
        }
    }

    private final class Choice extends Row {
        private final String key;
        private final IntSupplier get;
        private final IntConsumer set;
        private final String[] options;
        private final List<int[]> spans = new ArrayList<>();

        Choice(String key, IntSupplier get, IntConsumer set, String... options) {
            this.key = key;
            this.get = get;
            this.set = set;
            this.options = options;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            spans.clear();
            int cx = controlX(x, w);
            int current = get.getAsInt();
            for (int i = 0; i < options.length; i++) {
                int bw = UiFont.width(options[i]) + 12;
                if (cx + bw > x + w) break;
                boolean chosen = i == current;
                float hv = spring("c" + key + i, inside(mouseX, mouseY, cx, y + 3, bw, 16));
                int bg = chosen ? ColorMath.withAlpha(ACCENT, 0x55) : ColorMath.mix(0xFF1A2333, 0xFF2A3650, hv);
                Draw.rect(g, cx, y + 3, bw, 16, ColorMath.fade(bg, a));
                if (chosen) Draw.outline(g, cx, y + 3, bw, 16, ColorMath.fade(ACCENT, a));
                Draw.text(g,options[i], cx + 6, y + 7, 1f, ColorMath.fade(chosen ? TEXT : MUTED, a), false);
                spans.add(new int[]{cx, bw, i});
                cx += bw + 4;
            }
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            for (int[] span : spans) {
                if (mouseX >= span[0] && mouseX < span[0] + span[1]) {
                    set.accept(span[2]);
                    clickSound();
                    return true;
                }
            }
            return false;
        }
    }

    /** Preset colours for one target; picking one also points the RGB sliders at it. */
    private final class Swatches extends Row {
        private final ColorTarget target;
        private final IntSupplier get;
        private final IntConsumer set;
        private final List<int[]> spans = new ArrayList<>();

        Swatches(ColorTarget target, IntSupplier get, IntConsumer set) {
            this.target = target;
            this.get = get;
            this.set = set;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            boolean editing = colorTarget == target;
            Draw.text(g,tr(target.key), x + 8, y + 7, 1f, ColorMath.fade(editing ? TEXT : MUTED, a), false);
            if (editing) Draw.text(g,"RGB", x + 8 + UiFont.width(tr(target.key)) + 6, y + 7, 1f, ColorMath.fade(ACCENT, a), false);
            spans.clear();
            int cx = controlX(x, w);
            int current = get.getAsInt();
            for (int rgb : SWATCHES) {
                if (cx + 14 > x + w - 24) break;
                boolean chosen = rgb == current;
                Draw.rect(g, cx, y + 4, 14, 14, ColorMath.fade(0xFF000000 | rgb, a));
                if (chosen) Draw.outline(g, cx - 1, y + 3, 16, 16, ColorMath.fade(TEXT, a));
                spans.add(new int[]{cx, rgb});
                cx += 18;
            }
            // the current colour (custom ones too)
            Draw.rect(g, x + w - 18, y + 4, 14, 14, ColorMath.fade(0xFF000000 | current, a));
            Draw.outline(g, x + w - 19, y + 3, 16, 16, ColorMath.fade(editing ? ACCENT : LINE, a));
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            colorTarget = target;
            for (int[] span : spans) {
                if (mouseX >= span[0] && mouseX < span[0] + 14) {
                    set.accept(span[1]);
                    break;
                }
            }
            clickSound();
            return true;
        }
    }

    private final class Action extends Row {
        private final String key;
        private final Runnable action;

        Action(String key, Runnable action) {
            this.key = key;
            this.action = action;
        }

        @Override
        int height() {
            return 30;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            String label = tr(key);
            int bw = UiFont.width(label) + 16, bx = x + 8, by = y + 8;
            float hv = spring("a" + key, inside(mouseX, mouseY, bx, by, bw, 16));
            Draw.rect(g, bx, by, bw, 16, ColorMath.fade(ColorMath.mix(0xFF2A3446, 0xFF3A4660, hv), a));
            Draw.text(g,label, bx + 8, by + 4, 1f, ColorMath.fade(TEXT, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            String label = tr(key);
            int bw = UiFont.width(label) + 16;
            if (mouseX < columnX + 8 || mouseX > columnX + 8 + bw) return false;
            action.run();
            clickSound();
            return true;
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(on ? 1 : 0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
