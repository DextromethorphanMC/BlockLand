package com.blockland.client.craft;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.screen.lobby.LobbyTab;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Build;
import com.blockland.craft.Craftable;
import com.blockland.gun.GunData;
import com.blockland.melee.MeleeData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The lobby's 工匠 tab: every weapon in a (scrolling) list, the edited weapon's attachment slots with their option cards,
 * a live 3D preview and under it, in one scrolling area, the numbers (a gun's stat bars that show what an option
 * changes) and the looks (迷彩, 挂件, 特殊皮肤, 喷漆). Drawn inside the lobby screen's content area like its other tabs;
 * the logic is the {@link CraftSession}'s.
 */
public final class CraftPanel implements LobbyTab {
    private final CraftSession session;
    /** The attachment slot shown in the options column ("" when the weapon has none). */
    private String category = "";
    /** The look page shown under the numbers. */
    private String cosmetic = CraftSession.SKIN;
    @Nullable
    private String hoveredOption;
    private String hoveredCategory = "";

    private float yaw = -112, pitch = 12, zoom = 1;
    private boolean userRotated;
    private boolean dragging;
    private final Scroll list = new Scroll(), options = new Scroll(), info = new Scroll();
    private int listX = -1, listW;
    private int previewX, previewY, previewW, previewH;
    private final Map<String, Spring> hover = new HashMap<>();
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private final Spring lookTabX = new Spring(0).config(260, 24);
    private final Spring lookTabW = new Spring(0).config(260, 24);
    private final List<Hit> hits = new ArrayList<>();
    private final SprayEditor sprays = new SprayEditor();
    private final CraftStats stats = new CraftStats();
    /** 0 = the free preview angle, 1 = the spray page's side view. */
    private float sideBlend;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** A vertically scrolling view: where it is on screen, how tall its content is and how far it is scrolled. */
    private static final class Scroll {
        float offset, target;
        int top, height, content;

        /** Eases towards the target; call before drawing the content. @return the y the content starts at */
        int begin(int top, int height) {
            this.top = top;
            this.height = height;
            offset += (target - offset) * 0.35f;
            return top - Math.round(offset);
        }

        /** The content drawn from {@code start} ended at {@code end}: clamps the scroll and draws the bar at {@code barX}. */
        void end(GuiGraphics g, int start, int end, int barX, float a) {
            content = end - start;
            float max = Math.max(0, content - height);
            target = Math.max(0, Math.min(max, target));
            if (max > 0) {
                int barH = Math.max(12, Math.round(height * height / (float) content));
                int barY = top + Math.round((height - barH) * (Math.min(offset, max) / max));
                g.fill(barX, barY, barX + 2, barY + barH, ColorMath.fade(0x55FFFFFF, a));
            }
        }

        boolean contains(double mouseY) {
            return mouseY >= top && mouseY < top + height;
        }

        void reset() {
            offset = target = 0;
        }
    }

    private CraftPanel(CraftSession session) {
        this.session = session;
        if (session.weapon != null) resetPage(session.weapon);
    }

    /** The lobby's 工匠: every weapon, editing the saved builds. */
    public static CraftPanel lobby() {
        return new CraftPanel(CraftSession.lobby());
    }

    private void select(Craftable next) {
        session.select(next, null);
        resetPage(next);
    }

    private void resetPage(Craftable weapon) {
        List<String> gear = session.gearCategories();
        category = gear.isEmpty() ? "" : gear.get(0);
        if (!session.cosmeticCategories().contains(cosmetic)) cosmetic = CraftSession.SKIN;
        sprays.reset();
        options.reset();
        info.reset();
        userRotated = false;
        zoom = 1;
    }

    @Override
    public void open() {
        ClientBuilds.requestIfNeeded();
        // the saved builds may have changed in game meanwhile
        if (session.weapon != null && !session.changed()) session.edited = session.baseline(session.weapon);
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, int x0, int y0, int width, int height, float a) {
        Ticker.global().tick();
        hits.clear();
        session.checkSaveAnswer();
        Craftable weapon = session.weapon;
        if (weapon == null) {
            Draw.text(g, Component.translatable("blockland.craft.empty").getString(), x0, y0 + 8, 1f, ColorMath.fade(MUTED, a), false);
            return;
        }
        String name = weapon.displayName().getString();
        Draw.text(g, name, x0, y0 + 2, 1.3f, ColorMath.fade(TEXT, a), true);
        float nameEnd = x0 + UiFont.width(name) * 1.3f;
        if (weapon instanceof GunData.Gun gun && gun.modes().size() > 1) {
            String modes = gun.modes().stream().map(m -> Component.translatable("blockland.craft.fire_mode." + m.name().toLowerCase(Locale.ROOT)).getString())
                    .reduce((p, q) -> p + " / " + q).orElse("");
            Draw.text(g, modes, nameEnd + 8, y0 + 4, 1f, ColorMath.fade(MUTED, a), false);
        }
        String hint = Component.translatable("blockland.craft.hint").getString();
        Draw.text(g, hint, x0 + width - UiFont.width(hint), y0 + 4, 1f, ColorMath.fade(DIM, a), false);

        int top = y0 + 20, bottom = y0 + height;
        int x = x0;
        hoveredOption = null;
        hoveredCategory = "";
        listX = -1;
        if (session.weapons.size() > 1) {
            int listW = Math.min(118, (int) (width * 0.2f));
            drawWeaponList(g, mouseX, mouseY, x, top, listW, bottom - top, a);
            x += listW + 10;
        }
        int optW = Math.min(196, (int) (width * 0.32f));
        drawOptions(g, mouseX, mouseY, x, top, optW, bottom - top, a);

        // preview on the right, the numbers and the looks under it
        previewX = x + optW + 14;
        previewY = top - 2;
        previewW = x0 + width - previewX;
        previewH = Math.max(70, (int) ((bottom - previewY) * 0.46f));
        Draw.glow(g, previewX + previewW / 2f, previewY + previewH / 2f, previewW * 0.5f, previewH * 0.6f, ColorMath.fade(0x55A9C4E8, a));
        g.fill(previewX + previewW / 6, previewY + previewH - 2, previewX + previewW * 5 / 6, previewY + previewH - 1, ColorMath.fade(0x30FFFFFF, a));
        if (!userRotated && !dragging) yaw = -112 + (float) Math.sin(Ticker.global().time() * 0.35) * 14;
        // sprays are placed on a side seen square on (the weapon root's frame): the view turns there and back
        sideBlend += ((cosmetic.equals(CraftSession.SPRAY) ? 1 : 0) - sideBlend) * 0.3f;
        Quaternionf orientation = null;
        if (sideBlend > 0.002f) {
            Quaternionf side = sprays.sideView(weapon, session.edited);
            if (side != null) {
                orientation = sideBlend > 0.998f ? side : new Quaternionf().rotateX((float) Math.toRadians(pitch))
                        .rotateY((float) Math.toRadians(yaw)).slerp(side, sideBlend);
            }
        }
        boolean square = orientation != null && sideBlend > 0.998f;
        if (a > 0.05f) {
            g.getRenderState().submitPicturesInPictureState(WeaponPreviewRenderer.State.of(weapon.id(), session.edited, square ? 0 : yaw,
                    square ? 0 : pitch, zoom, orientation, previewX, previewY, previewX + previewW, previewY + previewH,
                    g.getScissorStack().peek()));
        }
        if (cosmetic.equals(CraftSession.SPRAY)) sprays.drawMarker(g, previewX, previewY, previewW, previewH, weapon, session.edited, a);
        int infoY = previewY + previewH + 6;
        drawInfo(g, mouseX, mouseY, weapon, previewX, infoY, previewW, bottom - infoY, a);

        float t = session.toast.get();
        if (t > 0.02f) {
            String text = session.toastText.getString();
            Draw.text(g, text, previewX + previewW / 2f - UiFont.width(text) / 2f, previewY + previewH - 16 - (1 - t) * 4, 1f,
                    ColorMath.fade(session.toastColor, t * a), true);
        }
    }

    private void drawWeaponList(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float a) {
        listX = x;
        listW = w;
        int cy = list.begin(y, h);
        int start = cy;
        g.enableScissor(x - 2, y, x + w + 6, y + h);
        Craftable.Kind kind = null;
        for (Craftable entry : session.weapons) {
            if (entry.kind() != kind) {
                kind = entry.kind();
                Draw.text(g, Component.translatable("blockland.craft.kind." + kind.name().toLowerCase(Locale.ROOT)).getString(),
                        x + 2, cy + 2, 0.75f, ColorMath.fade(DIM, a), false);
                cy += 12;
            }
            if (cy + 20 > y && cy < y + h) {
                boolean chosen = entry == session.weapon;
                boolean over = list.contains(mouseY) && inside(mouseX, mouseY, x, cy, w, 20);
                float hv = spring("w" + entry.id(), over ? 1 : 0, 320, 24).get();
                float sel = spring("ws" + entry.id(), chosen ? 1 : 0, 260, 24).get();
                Draw.rect(g, x, cy, w, 20, ColorMath.fade(ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel), a));
                if (sel > 0.01f) g.fill(x, cy, x + 2, cy + 20, ColorMath.fade(ACCENT, a * sel));
                String label = Draw.fit(entry.displayName().getString(), w - 12);
                Draw.text(g, label, x + 7 + Math.round(hv * 2), cy + 6, 1f, ColorMath.fade(chosen ? TEXT : ColorMath.mix(MUTED, TEXT, hv), a), false);
                if (!ClientBuilds.get(entry.id()).isEmpty()) {
                    Draw.rect(g, x + w - 7, cy + 8, 3, 3, ColorMath.fade(OK, a * 0.8f));
                }
                Craftable target = entry;
                addHit(x, cy, w, 20, list, () -> {
                    if (target != session.weapon) {
                        select(target);
                        CraftSession.playUi(1.4f, 0.25f);
                    }
                });
            }
            cy += 22;
        }
        g.disableScissor();
        list.end(g, start, cy - 2, x + w + 3, a);
    }

    /**
     * A row of page tabs (wrapped onto several rows when they do not fit) with an underline easing to the current one;
     * in a scrolling {@code view}, tabs scrolled out cannot be clicked.
     *
     * @return the y under the tabs' rule
     */
    private int drawTabs(GuiGraphics g, int mouseX, int mouseY, List<String> pages, String current, int x, int y, int w,
                         @Nullable Scroll view, Spring underX, Spring underW, float a, Consumer<String> pick) {
        int tx = x, ty = y;
        for (String c : pages) {
            String label = CraftSession.categoryName(c).getString();
            int tw = UiFont.width(label) + 14;
            if (tx + tw > x + w && tx > x) {
                tx = x;
                ty += 18;
            }
            boolean over = (view == null || view.contains(mouseY)) && inside(mouseX, mouseY, tx, ty, tw, 16);
            float hv = spring("tab" + c, over || c.equals(current) ? 1 : 0, 320, 26).get();
            Draw.text(g, label, tx + 7, ty + 4 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (c.equals(current)) {
                underX.setTarget(tx);
                underW.setTarget(tw);
                if (underW.get() == 0 || Math.abs(underX.get() - tx) > w) {
                    underX.set(tx);
                    underW.set(tw);
                }
                g.fill(Math.round(underX.get()), ty + 16, Math.round(underX.get() + underW.get()), ty + 17, ColorMath.fade(ACCENT, a));
            }
            String target = c;
            Runnable action = () -> {
                if (!target.equals(current)) {
                    pick.accept(target);
                    CraftSession.playUi(1.4f, 0.25f);
                }
            };
            if (view != null) addHit(tx, ty, tw, 16, view, action);
            else hits.add(new Hit(tx, ty, tw, 16, action));
            tx += tw + 2;
        }
        g.fill(x, ty + 17, x + w, ty + 18, ColorMath.fade(LINE, a));
        return ty + 18;
    }

    private void drawOptions(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float a) {
        List<String> gear = session.gearCategories();
        int below;
        if (gear.isEmpty()) {
            // nothing to fit (knives, most equipment): the looks under the preview are all there is
            Draw.text(g, Draw.fit(Component.translatable("blockland.craft.no_slots").getString(), w), x, y + 4, 1f, ColorMath.fade(MUTED, a), false);
            g.fill(x, y + 17, x + w, y + 18, ColorMath.fade(LINE, a));
            below = y + 18;
        } else {
            below = drawTabs(g, mouseX, mouseY, gear, category, x, y, w, null, tabX, tabW, a, c -> {
                category = c;
                options.reset();
            });
        }

        // option cards (scrolling list), the buttons pinned underneath
        int buttonsY = y + h - 20;
        int cy = options.begin(below + 6, Math.max(20, buttonsY - 22 - (below + 6)));
        int start = cy;
        if (!category.isEmpty()) {
            g.enableScissor(x - 2, options.top, x + w + 6, options.top + options.height);
            cy += drawCards(g, mouseX, mouseY, category, x, cy, w, 1, options, a);
            g.disableScissor();
        }
        options.end(g, start, cy, x + w + 3, a);

        // buttons
        boolean changed = session.changed();
        boolean waiting = session.waiting();
        button(g, mouseX, mouseY, x, buttonsY, w / 2 - 3, 20, Component.translatable("blockland.craft.save").getString(),
                changed && !waiting ? GO : DISABLED, changed && !waiting, a, session::save);
        button(g, mouseX, mouseY, x + w / 2 + 3, buttonsY, w / 2 - 3, 20, Component.translatable("blockland.craft.reset").getString(),
                0xFF2A3446, true, a, session::reset);
        if (changed) {
            Draw.text(g, Component.translatable("blockland.craft.unsaved").getString(), x, buttonsY - 12, 0.75f,
                    ColorMath.fade(ACCENT, a * 0.85f), false);
        }
    }

    /** Under the preview, scrolling as one: the weapon's numbers, then the look pages (迷彩, 挂件, 特殊皮肤, 喷漆). */
    private void drawInfo(GuiGraphics g, int mouseX, int mouseY, Craftable weapon, int x, int y, int w, int h, float a) {
        if (h < 20) return;
        int cy = info.begin(y, h);
        int start = cy;
        g.enableScissor(x - 2, y, x + w + 6, y + h);
        if (weapon instanceof GunData.Gun gun) {
            Build shown = hoveredOption != null ? CraftSession.with(hoveredCategory, session.edited, hoveredOption) : session.edited;
            int columns = w >= 300 ? 2 : 1;
            int statsH = CraftStats.gunHeight(columns, 12);
            stats.drawGun(g, gun, session.baseline(gun), shown, x, cy, w, statsH, a, false, columns);
            cy += statsH + 8;
        } else if (weapon instanceof MeleeData.Knife knife) {
            cy += CraftStats.drawKnife(g, knife, x, cy, w, a) + 8;
        }
        cy = drawTabs(g, mouseX, mouseY, session.cosmeticCategories(), cosmetic, x, cy, w, info, lookTabX, lookTabW, a, c -> {
            cosmetic = c;
            sprays.reset();
        }) + 6;
        if (cosmetic.equals(CraftSession.SPRAY)) {
            cy += sprays.draw(g, mouseX, mouseY, x, cy, w, info.top, info.height, a, weapon, session.edited, b -> session.edited = b);
        } else {
            cy += drawCards(g, mouseX, mouseY, cosmetic, x, cy, w, Math.max(1, Math.min(4, w / 150)), info, a);
        }
        g.disableScissor();
        info.end(g, start, cy, x + w + 3, a);
    }

    /**
     * The option cards of a slot or look page in {@code columns}, from {@code y}; only what shows in {@code view} is
     * drawn and clickable. Sets the hovered option.
     *
     * @return the cards' height
     */
    private int drawCards(GuiGraphics g, int mouseX, int mouseY, String page, int x, int y, int w, int columns, Scroll view, float a) {
        List<CraftSession.Option> list = session.options(page);
        String current = session.selected(page, session.edited);
        int columnW = (w - (columns - 1) * 4) / columns;
        int cy = y;
        for (int first = 0; first < list.size(); first += columns) {
            int last = Math.min(list.size(), first + columns);
            int rowH = 22;
            for (int i = first; i < last; i++) if (!list.get(i).description().getString().isEmpty()) rowH = 34;
            for (int i = first; i < last; i++) {
                CraftSession.Option option = list.get(i);
                drawCard(g, mouseX, mouseY, page, option, option.id().equals(current), x + (i - first) * (columnW + 4), cy, columnW, rowH, view, a);
            }
            cy += rowH + 4;
        }
        return cy - y;
    }

    private void drawCard(GuiGraphics g, int mouseX, int mouseY, String page, CraftSession.Option option, boolean chosen,
                          int x, int cy, int w, int ch, Scroll view, float a) {
        String description = option.description().getString();
        boolean visible = cy + ch > view.top && cy < view.top + view.height;
        boolean over = visible && inside(mouseX, mouseY, x, Math.max(cy, view.top), w,
                Math.min(cy + ch, view.top + view.height) - Math.max(cy, view.top));
        if (over) {
            hoveredOption = option.id();
            hoveredCategory = page;
        }
        float hv = spring(page + option.id(), over ? 1 : 0, 320, 24).get();
        float sel = spring("sel" + page + option.id(), chosen ? 1 : 0, 260, 24).get();
        if (!visible) return;
        int base = ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel);
        int ox = x + Math.round(hv * 3);
        Draw.rect(g, ox, cy, w, ch, ColorMath.fade(base, a));
        Draw.rect(g, ox, cy, Math.round(2 + 2 * Math.max(hv, sel)), ch,
                ColorMath.fade(CraftSession.rarityColor(option.rarity(), chosen), a * (0.4f + 0.6f * Math.max(hv, sel))));
        if (sel > 0.01f) Draw.outline(g, ox, cy, w, ch, ColorMath.fade(ACCENT, a * 0.6f * sel));
        int textX = x + 10 + Math.round(hv * 3);
        // a skin the player does not own: a padlock before its name (it can still be tried on)
        boolean locked = CraftSession.isSkinCategory(page) && session.locked(option.id());
        if (locked) CraftSession.lockIcon(g, textX, cy + 5, ColorMath.fade(CraftSession.LOCKED, a));
        int nameX = locked ? textX + 10 : textX;
        Draw.text(g, Draw.fit(option.name().getString(), w - 60 - (nameX - textX)), nameX, cy + 6, 1f,
                ColorMath.fade(locked ? MUTED : TEXT, a), false);
        if (!description.isEmpty()) {
            Draw.text(g, Draw.fit(description, w - 18), textX, cy + 20, 1f, ColorMath.fade(MUTED, a), false);
        }
        String tag = session.tag(page, option);
        if (tag != null) {
            Draw.text(g, tag, x + w - 8 - UiFont.width(tag) * 0.75f, cy + 7, 0.75f,
                    ColorMath.fade(session.tagColor(page, option), a), false);
        }
        String id = option.id();
        addHit(x, cy, w, ch, view, () -> session.choose(page, id));
    }

    /** A hit area clipped to a scrolling view (none while it is scrolled out). */
    private void addHit(int x, int y, int w, int h, Scroll view, Runnable action) {
        int top = Math.max(y, view.top), bottom = Math.min(y + h, view.top + view.height);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, action));
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color,
                        boolean enabled, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = spring("btn" + x, over ? 1 : 0, 320, 24).get();
        Draw.rect(g, x, y, w, h, ColorMath.fade(ColorMath.lighten(color, 0.1f * hv), a));
        String fitted = Draw.fit(label, w - 6);
        Draw.text(g, fitted, x + (w - UiFont.width(fitted)) / 2f, y + 6, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    private Spring spring(String key, float target, double stiffness, double damping) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(stiffness, damping));
        spring.setTarget(target);
        return spring;
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        Craftable weapon = session.weapon;
        if (cosmetic.equals(CraftSession.SPRAY) && weapon != null) {
            if (info.contains(mouseY) && sprays.mouseClicked(mouseX, mouseY)) {
                CraftSession.playUi(1.0f, 0.4f);
                return true;
            }
            // the spray page keeps the side view: a press on the preview places the spray instead of turning the weapon
            if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
                if (sprays.previewPressed(mouseX, mouseY, previewX, previewY, previewW, previewH, weapon, session.edited, b -> session.edited = b)) {
                    CraftSession.playUi(1.6f, 0.3f);
                }
                return true;
            }
        }
        if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        sprays.released();
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && sprays.isPlacing() && session.weapon != null) {
            sprays.previewDragged(mouseX, mouseY, previewX, previewY, previewW, previewH, session.weapon, session.edited, b -> session.edited = b);
            return true;
        }
        if (button == 0 && dragging) {
            userRotated = true;
            yaw += (float) dragX * 1.2f;
            pitch = Math.max(-60, Math.min(60, pitch + (float) dragY * 0.8f));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        boolean onPreview = inside(mouseX, mouseY, previewX, previewY, previewW, previewH);
        if (session.weapon != null && cosmetic.equals(CraftSession.SPRAY) && onPreview
                && sprays.previewScrolled(scrollY, Screen.hasShiftDown(), session.edited, b -> session.edited = b)) {
            return true;
        }
        if (onPreview) {
            zoom = Math.max(0.7f, Math.min(2.2f, zoom + (float) scrollY * 0.12f));
        } else if (mouseX >= previewX) {
            if (info.contains(mouseY)) info.target -= (float) scrollY * 26f;
        } else if (listX >= 0 && mouseX < listX + listW + 6) {
            if (list.contains(mouseY)) list.target -= (float) scrollY * 26f;
        } else if (options.contains(mouseY)) {
            options.target -= (float) scrollY * 26f;
        }
        return true;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
