package com.blockland.client.gun;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ui.Draw;
import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * 枪匠 (gunsmith): pick sight, laser, magazine and skin for the held gun with a live 3D preview and stat bars that
 * show what an option changes. Applying saves the build on this computer and asks the server to fit it.
 */
public final class GunsmithScreen extends Screen {
    private enum Category {
        SIGHT("瞄具"), LASER("激光"), MAGAZINE("弹匣"), SKIN("皮肤");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    private record Option(String id, String name, String description, @Nullable String rarity) {
    }

    private final int slot;
    private Category category = Category.SIGHT;
    @Nullable
    private GunData.Gun gun;
    private GunState applied;
    private GunState edited;
    @Nullable
    private String hoveredOption;

    private float yaw = -112, pitch = 12, zoom = 1;
    private boolean userRotated;
    private boolean dragging;
    private final Map<String, Spring> hover = new HashMap<>();
    private final Map<String, Spring> bars = new HashMap<>();
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private final AnimValue intro = new AnimValue(0);
    private final AnimValue appliedToast = new AnimValue(0);
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public GunsmithScreen(int slot) {
        super(Component.literal("枪匠"));
        this.slot = slot;
    }

    @Override
    protected void init() {
        ItemStack stack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof GunItem)) {
            onClose();
            return;
        }
        gun = GunContent.gunOf(stack).orElse(null);
        GunState state = GunContent.stateOf(stack);
        if (gun == null || state == null) {
            onClose();
            return;
        }
        if (applied == null) {
            applied = state;
            edited = state;
            Anim.to(intro, 1, 0.45).ease(Ease.QUART_OUT);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- options --------------------------------------------------------------------------------

    private List<Option> options(Category c) {
        GunCatalog catalog = GunCatalog.get();
        List<Option> list = new ArrayList<>();
        switch (c) {
            case SIGHT -> gun.sights.forEach(id -> {
                GunData.Sight s = catalog.sight(id);
                if (s != null) list.add(new Option(id, s.name, s.description, null));
            });
            case LASER -> gun.lasers.forEach(id -> {
                GunData.Laser l = catalog.laser(id);
                if (l != null) list.add(new Option(id, l.name, l.description, null));
            });
            case MAGAZINE -> gun.magazines.forEach(id -> {
                GunData.Magazine m = catalog.magazine(id);
                if (m != null) list.add(new Option(id, m.name + "  " + m.capacity + " 发", m.description, null));
            });
            case SKIN -> gun.skins.values().forEach(s -> list.add(new Option(s.id, s.name, "", s.rarity)));
        }
        return list;
    }

    private String selected(Category c, GunState s) {
        return switch (c) {
            case SIGHT -> s.sight();
            case LASER -> s.laser();
            case MAGAZINE -> s.magazine();
            case SKIN -> s.skin();
        };
    }

    private GunState with(Category c, GunState s, String id) {
        return switch (c) {
            case SIGHT -> s.withLoadout(s.skin(), s.magazine(), id, s.laser());
            case LASER -> s.withLoadout(s.skin(), s.magazine(), s.sight(), id);
            case MAGAZINE -> s.withLoadout(s.skin(), id, s.sight(), s.laser());
            case SKIN -> s.withLoadout(id, s.magazine(), s.sight(), s.laser());
        };
    }

    // ---- rendering -------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        g.fillGradient(0, 0, width, height, 0xF2070A10, 0xF20D1420);
        Draw.glow(g, width * 0.66f, height * 0.42f, width * 0.42f, height * 0.5f, 0x2A3A6EA5);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (gun == null) return;
        hits.clear();
        float a = intro.get();
        int pad = 14;
        int leftW = Math.min(190, (int) (width * 0.38f));
        // header
        Draw.text(g, font, "枪匠", pad, 10, 1.6f, ColorMath.fade(TEXT, a), true);
        Draw.text(g, font, gun.name, pad + font.width("枪匠") * 1.6f + 10, 14, 1f, ColorMath.fade(ACCENT, a), false);
        String modes = gun.modes().stream().map(m -> m.label).reduce((x, y) -> x + " / " + y).orElse("");
        Draw.text(g, font, modes + (gun.modes().size() > 1 ? "（B 切换）" : ""),
                pad + font.width("枪匠") * 1.6f + 16 + font.width(gun.name), 14, 1f, ColorMath.fade(MUTED, a), false);
        String hint = "拖动旋转 · 滚轮缩放 · ESC 关闭";
        Draw.text(g, font, hint, width - pad - font.width(hint), 14, 1f, ColorMath.fade(DIM, a), false);

        // preview (right side)
        int px = leftW + pad * 2, py = 34, pw = width - px - pad, ph = (int) ((height - py) * 0.5f);
        Draw.glow(g, px + pw / 2f, py + ph / 2f, pw * 0.5f, ph * 0.6f, ColorMath.fade(0x55A9C4E8, a));
        g.fill(px + pw / 6, py + ph - 2, px + pw * 5 / 6, py + ph - 1, ColorMath.fade(0x30FFFFFF, a));
        if (!userRotated && !dragging) yaw = -112 + (float) Math.sin(Ticker.global().time() * 0.35) * 14;
        g.getRenderState().submitPicturesInPictureState(GunPreviewRenderer.State.of(gun.id, edited, yaw, pitch, zoom, px, py, px + pw, py + ph,
                g.getScissorStack().peek()));
        drawStats(g, px, py + ph + 6, pw, height - (py + ph + 6) - pad, a);

        // categories
        int tx = pad, ty = 36;
        for (Category c : Category.values()) {
            int w = font.width(c.label) + 14;
            boolean over = inside(mouseX, mouseY, tx, ty, w, 16);
            float hv = spring(hover, "tab" + c, over || c == category, 320, 26).get();
            Draw.text(g, font, c.label, tx + 7, ty + 4 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (c == category) {
                tabX.setTarget(tx);
                tabW.setTarget(w);
                if (tabW.get() == 0) {
                    tabX.set(tx);
                    tabW.set(w);
                }
            }
            Category target = c;
            hits.add(new Hit(tx, ty, w, 16, () -> {
                category = target;
                ClientGuns.playUi("gun.attach", 1.4f, 0.25f);
            }));
            tx += w + 2;
        }
        g.fill(Math.round(tabX.get()), ty + 16, Math.round(tabX.get() + tabW.get()), ty + 17, ColorMath.fade(ACCENT, a));
        g.fill(pad, ty + 17, pad + leftW, ty + 18, ColorMath.fade(LINE, a));

        // option cards
        int cy = ty + 24;
        hoveredOption = null;
        String current = selected(category, edited);
        String equipped = selected(category, applied);
        for (Option option : options(category)) {
            int ch = option.description().isEmpty() ? 22 : 34;
            boolean over = inside(mouseX, mouseY, pad, cy, leftW, ch);
            if (over) hoveredOption = option.id();
            boolean chosen = option.id().equals(current);
            float hv = spring(hover, category + option.id(), over ? 1 : 0, 320, 24).get();
            float sel = spring(hover, "sel" + category + option.id(), chosen ? 1 : 0, 260, 24).get();
            int base = ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel);
            Draw.rect(g, pad + Math.round(hv * 3), cy, leftW, ch, ColorMath.fade(base, a));
            Draw.rect(g, pad + Math.round(hv * 3), cy, Math.round(2 + 2 * Math.max(hv, sel)), ch,
                    ColorMath.fade(rarityColor(option.rarity(), chosen), a * (0.4f + 0.6f * Math.max(hv, sel))));
            if (sel > 0.01f) Draw.outline(g, pad + Math.round(hv * 3), cy, leftW, ch, ColorMath.fade(ACCENT, a * 0.6f * sel));
            int textX = pad + 10 + Math.round(hv * 3);
            Draw.text(g, font, Draw.fit(font, option.name(), leftW - 60), textX, cy + 6, 1f, ColorMath.fade(TEXT, a), false);
            if (!option.description().isEmpty()) {
                Draw.text(g, font, Draw.fit(font, option.description(), leftW - 18), textX, cy + 20, 1f,
                        ColorMath.fade(MUTED, a), false);
            }
            if (option.id().equals(equipped)) {
                String tag = "已装备";
                Draw.text(g, font, tag, pad + leftW - 8 - font.width(tag) * 0.75f, cy + 7, 0.75f, ColorMath.fade(OK, a), false);
            } else if (option.rarity() != null && !"common".equals(option.rarity())) {
                String tag = "传说";
                Draw.text(g, font, tag, pad + leftW - 8 - font.width(tag) * 0.75f, cy + 7, 0.75f, ColorMath.fade(GOLD, a), false);
            }
            String id = option.id();
            hits.add(new Hit(pad, cy, leftW, ch, () -> {
                edited = with(category, edited, id);
                ClientGuns.playUi("gun.attach", 1.0f, 0.5f);
            }));
            cy += ch + 4;
        }

        // buttons
        int by = height - pad - 20;
        boolean changed = !edited.equals(applied);
        button(g, mouseX, mouseY, pad, by, leftW / 2 - 3, 20, "应用改装", changed ? GO : DISABLED, changed, a, this::apply);
        button(g, mouseX, mouseY, pad + leftW / 2 + 3, by, leftW / 2 - 3, 20, "恢复默认", 0xFF2A3446, true, a, () -> {
            GunState fresh = GunState.fresh(gun);
            edited = edited.withLoadout(fresh.skin(), fresh.magazine(), fresh.sight(), fresh.laser());
        });
        float toast = appliedToast.get();
        if (toast > 0.02f) {
            String text = "改装已应用";
            Draw.text(g, font, text, pad + leftW / 2f - font.width(text) / 2f, by - 14 - (1 - toast) * 4, 1f, ColorMath.fade(OK, toast), true);
        }
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color,
                        boolean enabled, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = spring(hover, "btn" + label, over ? 1 : 0, 320, 24).get();
        Draw.rect(g, x, y, w, h, ColorMath.fade(ColorMath.lighten(color, 0.1f * hv), a));
        Draw.text(g, font, label, x + (w - font.width(label)) / 2f, y + 6, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    // ---- stats ------------------------------------------------------------------------------------

    private record Stat(String label, float applied, float preview, String value) {
    }

    private void drawStats(GuiGraphics g, int x, int y, int w, int h, float a) {
        GunStats now = GunStats.of(gun.id, applied).orElse(null);
        GunState shown = hoveredOption != null ? with(category, edited, hoveredOption) : edited;
        GunStats next = GunStats.of(gun.id, shown).orElse(null);
        if (now == null || next == null) return;
        List<Stat> stats = List.of(
                new Stat("伤害", damage(now), damage(next), String.format("%.1f", next.ammo().damage * next.gun().damageScale)),
                new Stat("射速", next.gun().rpm / 1000f, next.gun().rpm / 1000f, (int) next.gun().rpm + " RPM"),
                new Stat("腰射精度", 1 - now.baseSpread(false) / 8f, 1 - next.baseSpread(false) / 8f, String.format("%.1f°", next.baseSpread(false))),
                new Stat("瞄准精度", 1 - now.baseSpread(true) / 1.5f, 1 - next.baseSpread(true) / 1.5f, String.format("%.2f°", next.baseSpread(true))),
                new Stat("后坐力控制", 1 - now.gun().recoil.vertical / 4f, 1 - next.gun().recoil.vertical / 4f, String.format("%.1f", next.gun().recoil.vertical)),
                new Stat("开镜速度", 1 - now.adsTime() / 0.5f, 1 - next.adsTime() / 0.5f, Math.round(next.adsTime() * 1000) + " ms"),
                new Stat("换弹速度", 1 - now.reloadTime(false) / 4f, 1 - next.reloadTime(false) / 4f, String.format("%.2f s", next.reloadTime(false))),
                new Stat("弹匣容量", now.capacity() / 40f, next.capacity() / 40f, next.capacity() + " 发"));
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, a));
        int rowH = Math.max(9, Math.min(14, (h - 6) / stats.size()));
        int labelW = 52, valueW = 50;
        int barX = x + 8 + labelW, barW = w - labelW - valueW - 24;
        for (int i = 0; i < stats.size(); i++) {
            Stat s = stats.get(i);
            int ry = y + 6 + i * rowH;
            Draw.text(g, font, s.label(), x + 8, ry, 1f, ColorMath.fade(MUTED, a), false);
            float base = Spring01(bars, "a" + i, s.applied());
            float target = Spring01(bars, "p" + i, s.preview());
            g.fill(barX, ry + 3, barX + barW, ry + 6, ColorMath.fade(0x30FFFFFF, a));
            int common = Math.round(barW * Math.min(base, target));
            g.fill(barX, ry + 3, barX + common, ry + 6, ColorMath.fade(TEXT, a * 0.85f));
            if (target > base + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * target), ry + 6, ColorMath.fade(OK, a));
            } else if (base > target + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * base), ry + 6, ColorMath.fade(ERROR, a));
            }
            Draw.text(g, font, s.value(), x + w - 8 - font.width(s.value()), ry, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    private static float damage(GunStats s) {
        return Math.min(1, s.ammo().damage * s.gun().damageScale / 20f);
    }

    private static float Spring01(Map<String, Spring> map, String key, float target) {
        Spring spring = map.computeIfAbsent(key, k -> new Spring(target).config(200, 24));
        spring.setTarget(Math.max(0, Math.min(1, target)));
        return Math.max(0, Math.min(1, spring.get()));
    }

    private static Spring spring(Map<String, Spring> map, String key, boolean on, double stiffness, double damping) {
        return spring(map, key, on ? 1 : 0, stiffness, damping);
    }

    private static Spring spring(Map<String, Spring> map, String key, float target, double stiffness, double damping) {
        Spring spring = map.computeIfAbsent(key, k -> new Spring(0).config(stiffness, damping));
        spring.setTarget(target);
        return spring;
    }

    private static int rarityColor(@Nullable String rarity, boolean chosen) {
        if ("legendary".equals(rarity)) return GOLD;
        return chosen ? ACCENT : INFO;
    }

    // ---- actions ------------------------------------------------------------------------------------

    private void apply() {
        if (gun == null || edited.equals(applied)) return;
        GunLoadouts.apply(slot, gun.id, new GunLoadouts.Loadout(edited.skin(), edited.magazine(), edited.sight(), edited.laser()));
        applied = edited;
        appliedToast.snap(1);
        appliedToast.to(0, 0.5, Ease.QUAD_IN).delay(1.2);
    }

    // ---- input --------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit hit : hits) {
                if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                    hit.action.run();
                    return true;
                }
            }
            dragging = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragging) {
            userRotated = true;
            yaw += (float) dragX * 1.2f;
            pitch = Math.max(-60, Math.min(60, pitch + (float) dragY * 0.8f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom = Math.max(0.7f, Math.min(2.2f, zoom + (float) scrollY * 0.12f));
        return true;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
