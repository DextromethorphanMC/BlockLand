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

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The lobby's 工匠 tab: every weapon in a list, the edited weapon's pages (slots, 喷漆, the skin tabs) with their option
 * cards, a live 3D preview and, for guns, stat bars that show what an option changes. Drawn inside the lobby screen's
 * content area like its other tabs; the logic is the {@link CraftSession}'s.
 */
public final class CraftPanel implements LobbyTab {
    private final CraftSession session;
    private String category = CraftSession.SKIN;
    @Nullable
    private String hoveredOption;

    private float yaw = -112, pitch = 12, zoom = 1;
    private boolean userRotated;
    private boolean dragging;
    private float optionScroll, optionScrollTarget;
    private int optionContentHeight, optionViewTop, optionViewHeight;
    private int previewX, previewY, previewW, previewH;
    private final Map<String, Spring> hover = new HashMap<>();
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private final List<Hit> hits = new ArrayList<>();
    private final SprayEditor sprays = new SprayEditor();
    private final CraftStats stats = new CraftStats();
    /** 0 = the free preview angle, 1 = the spray page's side view. */
    private float sideBlend;

    private record Hit(int x, int y, int w, int h, Runnable action) {
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
        category = weapon.slots().isEmpty() ? CraftSession.SKIN : weapon.slots().get(0).id();
        sprays.reset();
        optionScroll = optionScrollTarget = 0;
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
        if (session.weapons.size() > 1) {
            int listW = Math.min(118, (int) (width * 0.2f));
            drawWeaponList(g, mouseX, mouseY, x, top, listW, a);
            x += listW + 10;
        }
        int optW = Math.min(196, (int) (width * 0.32f));
        drawOptions(g, mouseX, mouseY, x, top, optW, bottom - top, a);

        // preview and numbers on the right
        previewX = x + optW + 14;
        previewY = top - 2;
        previewW = x0 + width - previewX;
        previewH = (int) ((bottom - previewY) * 0.56f);
        Draw.glow(g, previewX + previewW / 2f, previewY + previewH / 2f, previewW * 0.5f, previewH * 0.6f, ColorMath.fade(0x55A9C4E8, a));
        g.fill(previewX + previewW / 6, previewY + previewH - 2, previewX + previewW * 5 / 6, previewY + previewH - 1, ColorMath.fade(0x30FFFFFF, a));
        if (!userRotated && !dragging) yaw = -112 + (float) Math.sin(Ticker.global().time() * 0.35) * 14;
        // sprays are placed on a side seen square on (the weapon root's frame): the view turns there and back
        sideBlend += ((category.equals(CraftSession.SPRAY) ? 1 : 0) - sideBlend) * 0.3f;
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
        if (category.equals(CraftSession.SPRAY)) sprays.drawMarker(g, previewX, previewY, previewW, previewH, weapon, session.edited, a);
        int infoY = previewY + previewH + 6;
        if (weapon instanceof GunData.Gun gun) {
            Build shown = hoveredOption != null ? CraftSession.with(category, session.edited, hoveredOption) : session.edited;
            stats.drawGun(g, gun, session.baseline(gun), shown, previewX, infoY, previewW, bottom - infoY, a, false);
        } else if (weapon instanceof MeleeData.Knife knife) {
            CraftStats.drawKnife(g, knife, previewX, infoY, previewW, a);
        }

        float t = session.toast.get();
        if (t > 0.02f) {
            String text = session.toastText.getString();
            Draw.text(g, text, previewX + previewW / 2f - UiFont.width(text) / 2f, previewY + previewH - 16 - (1 - t) * 4, 1f,
                    ColorMath.fade(session.toastColor, t * a), true);
        }
    }

    private void drawWeaponList(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
        Craftable.Kind kind = null;
        for (Craftable entry : session.weapons) {
            if (entry.kind() != kind) {
                kind = entry.kind();
                Draw.text(g, Component.translatable("blockland.craft.kind." + kind.name().toLowerCase(Locale.ROOT)).getString(),
                        x + 2, y + 2, 0.75f, ColorMath.fade(DIM, a), false);
                y += 12;
            }
            boolean chosen = entry == session.weapon;
            boolean over = inside(mouseX, mouseY, x, y, w, 20);
            float hv = spring("w" + entry.id(), over ? 1 : 0, 320, 24).get();
            float sel = spring("ws" + entry.id(), chosen ? 1 : 0, 260, 24).get();
            Draw.rect(g, x, y, w, 20, ColorMath.fade(ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel), a));
            if (sel > 0.01f) g.fill(x, y, x + 2, y + 20, ColorMath.fade(ACCENT, a * sel));
            String label = Draw.fit(entry.displayName().getString(), w - 12);
            Draw.text(g, label, x + 7 + Math.round(hv * 2), y + 6, 1f, ColorMath.fade(chosen ? TEXT : ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (!ClientBuilds.get(entry.id()).isEmpty()) {
                Draw.rect(g, x + w - 7, y + 8, 3, 3, ColorMath.fade(OK, a * 0.8f));
            }
            Craftable target = entry;
            hits.add(new Hit(x, y, w, 20, () -> {
                if (target != session.weapon) {
                    select(target);
                    CraftSession.playUi(1.4f, 0.25f);
                }
            }));
            y += 22;
        }
    }

    private void drawOptions(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float a) {
        // category tabs, wrapped onto several rows when they do not fit
        int tx = x, ty = y;
        for (String c : session.categories()) {
            String label = CraftSession.categoryName(c).getString();
            int tw = UiFont.width(label) + 14;
            if (tx + tw > x + w && tx > x) {
                tx = x;
                ty += 18;
            }
            boolean over = inside(mouseX, mouseY, tx, ty, tw, 16);
            float hv = spring("tab" + c, over || c.equals(category) ? 1 : 0, 320, 26).get();
            Draw.text(g, label, tx + 7, ty + 4 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (c.equals(category)) {
                tabX.setTarget(tx);
                tabW.setTarget(tw);
                if (tabW.get() == 0 || Math.abs(tabX.get() - tx) > w) {
                    tabX.set(tx);
                    tabW.set(tw);
                }
                g.fill(Math.round(tabX.get()), ty + 16, Math.round(tabX.get() + tabW.get()), ty + 17, ColorMath.fade(ACCENT, a));
            }
            String target = c;
            hits.add(new Hit(tx, ty, tw, 16, () -> {
                if (!target.equals(category)) {
                    category = target;
                    optionScroll = optionScrollTarget = 0;
                    CraftSession.playUi(1.4f, 0.25f);
                }
            }));
            tx += tw + 2;
        }
        g.fill(x, ty + 17, x + w, ty + 18, ColorMath.fade(LINE, a));

        // option cards (scrolling list), the buttons pinned underneath
        int buttonsY = y + h - 20;
        optionViewTop = ty + 24;
        optionViewHeight = Math.max(20, buttonsY - 22 - optionViewTop);
        optionScroll += (optionScrollTarget - optionScroll) * 0.35f;
        hoveredOption = null;
        String current = session.selected(category, session.edited);
        g.enableScissor(x - 2, optionViewTop, x + w + 6, optionViewTop + optionViewHeight);
        int cy = optionViewTop - Math.round(optionScroll);
        int contentTop = cy;
        // the spray page draws itself in the same scrolling area
        if (category.equals(CraftSession.SPRAY) && session.weapon != null) {
            cy += sprays.draw(g, mouseX, mouseY, x, cy, w, optionViewTop, optionViewHeight, a, session.weapon, session.edited, b -> session.edited = b);
        }
        for (CraftSession.Option option : category.equals(CraftSession.SPRAY) ? List.<CraftSession.Option>of() : session.options(category)) {
            String description = option.description().getString();
            int ch = description.isEmpty() ? 22 : 34;
            boolean visible = cy + ch > optionViewTop && cy < optionViewTop + optionViewHeight;
            boolean over = visible && inside(mouseX, mouseY, x, Math.max(cy, optionViewTop), w,
                    Math.min(cy + ch, optionViewTop + optionViewHeight) - Math.max(cy, optionViewTop));
            if (over) hoveredOption = option.id();
            boolean chosen = option.id().equals(current);
            float hv = spring(category + option.id(), over ? 1 : 0, 320, 24).get();
            float sel = spring("sel" + category + option.id(), chosen ? 1 : 0, 260, 24).get();
            if (visible) {
                int base = ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel);
                int ox = x + Math.round(hv * 3);
                Draw.rect(g, ox, cy, w, ch, ColorMath.fade(base, a));
                Draw.rect(g, ox, cy, Math.round(2 + 2 * Math.max(hv, sel)), ch,
                        ColorMath.fade(CraftSession.rarityColor(option.rarity(), chosen), a * (0.4f + 0.6f * Math.max(hv, sel))));
                if (sel > 0.01f) Draw.outline(g, ox, cy, w, ch, ColorMath.fade(ACCENT, a * 0.6f * sel));
                int textX = x + 10 + Math.round(hv * 3);
                Draw.text(g, Draw.fit(option.name().getString(), w - 60), textX, cy + 6, 1f, ColorMath.fade(TEXT, a), false);
                if (!description.isEmpty()) {
                    Draw.text(g, Draw.fit(description, w - 18), textX, cy + 20, 1f, ColorMath.fade(MUTED, a), false);
                }
                String tag = session.tag(category, option);
                if (tag != null) {
                    Draw.text(g, tag, x + w - 8 - UiFont.width(tag) * 0.75f, cy + 7, 0.75f,
                            ColorMath.fade(session.tagColor(category, option), a), false);
                }
                String id = option.id();
                int hitTop = Math.max(cy, optionViewTop), hitBottom = Math.min(cy + ch, optionViewTop + optionViewHeight);
                hits.add(new Hit(x, hitTop, w, hitBottom - hitTop, () -> session.choose(category, id)));
            }
            cy += ch + 4;
        }
        g.disableScissor();
        optionContentHeight = cy - contentTop;
        float maxScroll = Math.max(0, optionContentHeight - optionViewHeight);
        optionScrollTarget = Math.max(0, Math.min(maxScroll, optionScrollTarget));
        if (maxScroll > 0) {
            int barH = Math.max(12, Math.round(optionViewHeight * optionViewHeight / (float) optionContentHeight));
            int barY = optionViewTop + Math.round((optionViewHeight - barH) * (optionScroll / maxScroll));
            g.fill(x + w + 3, barY, x + w + 5, barY + barH, ColorMath.fade(0x55FFFFFF, a));
        }

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
        if (category.equals(CraftSession.SPRAY) && weapon != null) {
            if (sprays.mouseClicked(mouseX, mouseY)) {
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
        if (session.weapon != null && category.equals(CraftSession.SPRAY) && inside(mouseX, mouseY, previewX, previewY, previewW, previewH)
                && sprays.previewScrolled(scrollY, Screen.hasShiftDown(), session.edited, b -> session.edited = b)) {
            return true;
        }
        if (mouseX >= previewX) {
            zoom = Math.max(0.7f, Math.min(2.2f, zoom + (float) scrollY * 0.12f));
        } else if (mouseY >= optionViewTop && mouseY < optionViewTop + optionViewHeight) {
            optionScrollTarget -= (float) scrollY * 26f;
        }
        return true;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
