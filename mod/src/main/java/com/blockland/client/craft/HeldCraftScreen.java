package com.blockland.client.craft;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.Craftable;
import com.blockland.gun.GunData;
import com.blockland.melee.MeleeData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The in-game 工匠 for the held weapon: the weapon is raised from the hand to the middle of the screen as its real 3D
 * model with the current build, turnable with the mouse; every replaceable slot is a callout box beside it with a line
 * to where the slot sits on the model ({@link MountCallouts}); a box opens its options ({@link OptionPicker}) with the
 * numbers they change; the skins run along the bottom as a carousel of swatches ({@link SkinCarousel}); 喷漆 switches to
 * placing the weapon sprays on its side. Hovering anything previews it live. Saving fits the build to the held weapon
 * through the server (which validates it). The screen only lays the components out and wires them to the
 * {@link CraftSession}.
 */
public final class HeldCraftScreen extends Screen {
    private final CraftSession session;
    private final MountCallouts callouts = new MountCallouts();
    private final OptionPicker picker = new OptionPicker();
    private final SkinCarousel skins = new SkinCarousel();
    private final SprayEditor sprays = new SprayEditor();
    private final CraftStats stats = new CraftStats();
    /** 0 → 1: the weapon comes up from the hand; back to 0 when closing. */
    private final AnimValue raise = new AnimValue(0);
    /** 0 → 1: the boxes and the carousel fade in after the weapon. */
    private final AnimValue chrome = new AnimValue(0);
    private final Map<String, Spring> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private boolean closing;
    private boolean sprayMode;
    private float sideBlend;

    private float yaw = -100, pitch = 10, zoom = 1;
    private float idleTime;
    private boolean dragging;
    private boolean userRotated;
    private int previewX, previewY, previewW, previewH;

    @Nullable
    private WeaponAssembly assembly;
    @Nullable
    private Build assembled;
    @Nullable
    private float[] frame;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private HeldCraftScreen(CraftSession session) {
        super(Component.translatable("blockland.craft.title"));
        this.session = session;
    }

    /** The in-game 工匠 for the weapon in the player's hand; empty when the item is not craftable. */
    public static Optional<HeldCraftScreen> forHeld(ItemStack stack, int slot) {
        return CraftSession.forHeld(stack, slot).map(HeldCraftScreen::new);
    }

    @Override
    protected void init() {
        ClientBuilds.requestIfNeeded();
        if (raise.get() == 0 && !closing) {
            raise.to(1, 0.55, Ease.EXPO_OUT);
            chrome.to(1, 0.35, Ease.QUART_OUT).delay(0.22);
            CraftSession.playUi(0.7f, 0.35f);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc puts the weapon back down first (the hand takes it again), then the game is back. */
    @Override
    public void onClose() {
        if (picker.slot() != null) {
            picker.close();
            return;
        }
        if (sprayMode) {
            sprayMode = false;
            return;
        }
        lowerAndClose();
    }

    private void lowerAndClose() {
        if (closing) return;
        closing = true;
        chrome.to(0, 0.14, Ease.QUAD_IN);
        raise.to(0, 0.3, Ease.QUART_IN).delay(0.06);
    }

    @Override
    public void tick() {
        // the weapon must still be held (dropped, switched, died: nothing left to fit)
        if (!session.stillHeld()) lowerAndClose();
        if (closing && raise.get() <= 0.001f) minecraft.setScreen(null);
    }

    // ---- rendering --------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        float t = raise.get();
        // the game stays visible behind: a dark veil, heavier at the bottom (where the hand was), and a glow behind the weapon
        g.fillGradient(0, 0, width, height, ColorMath.fade(0xB0060910, t), ColorMath.fade(0xE0060910, t));
        Draw.glow(g, width / 2f, height * 0.40f, width * 0.36f, height * 0.36f, ColorMath.fade(0x3A3A6EA5, t));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hits.clear();
        // put away: the game takes over at the next tick
        if (closing && raise.get() <= 0.001f) return;
        session.checkSaveAnswer();
        Craftable weapon = session.weapon;
        if (weapon == null) return;
        float t = raise.get(), c = chrome.get();
        int pad = 12;

        // header
        String name = weapon.displayName().getString();
        Draw.text(g, name, pad, 10 - (1 - c) * 6, 1.6f, ColorMath.fade(TEXT, c), true);
        String kind = Component.translatable("blockland.craft.kind." + weapon.kind().name().toLowerCase(Locale.ROOT)).getString();
        Draw.text(g, kind, pad, 28, 0.75f, ColorMath.fade(ACCENT, c), false);
        String hint = Component.translatable(sprayMode ? "blockland.craft.ingame.hint_spray" : "blockland.craft.ingame.hint").getString();
        Draw.text(g, hint, width - pad - UiFont.width(hint) * 0.75f, 12, 0.75f, ColorMath.fade(DIM, c), false);

        // the weapon: from the hand (bottom right) to the middle
        int columnW = Mth.clamp(Math.round(width * 0.22f) - 14, 84, 140);
        int centreW = width - 2 * (columnW + pad + 18);
        int targetX = pad + columnW + 18, targetY = Math.round(height * 0.11f);
        int targetW = centreW, targetH = Math.round(height * 0.56f);
        int fromX = Math.round(width * 0.55f), fromY = Math.round(height * 0.58f), fromW = Math.round(width * 0.42f), fromH = Math.round(height * 0.42f);
        previewX = Math.round(Mth.lerp(t, fromX, targetX));
        previewY = Math.round(Mth.lerp(t, fromY, targetY));
        previewW = Math.max(20, Math.round(Mth.lerp(t, fromW, targetW)));
        previewH = Math.max(20, Math.round(Mth.lerp(t, fromH, targetH)));

        idleTime = (float) Ticker.global().time();
        float shownYaw = yaw, shownPitch = pitch;
        if (!userRotated && !dragging) {
            // a slow turn, leaning a little towards the mouse
            shownYaw = yaw + (float) Math.sin(idleTime * 0.4) * 12 + (mouseX - width / 2f) / Math.max(1, width) * 16;
            shownPitch = pitch + (mouseY - height / 2f) / Math.max(1, height) * 8;
        }
        // coming up from the hand it turns from a side view to the three-quarter view
        shownYaw = Mth.lerp(t, -168, shownYaw);
        shownPitch = Mth.lerp(t, 2, shownPitch);
        sideBlend += ((sprayMode ? 1 : 0) - sideBlend) * 0.3f;
        Build shown = previewBuild();
        Quaternionf orientation = null;
        if (sideBlend > 0.002f) {
            Quaternionf side = sprays.sideView(weapon, session.edited);
            if (side != null) {
                orientation = sideBlend > 0.998f ? side : new Quaternionf().rotateX((float) Math.toRadians(shownPitch))
                        .rotateY((float) Math.toRadians(shownYaw)).slerp(side, sideBlend);
            }
        }
        boolean square = orientation != null && sideBlend > 0.998f;
        float drawYaw = square ? 0 : shownYaw, drawPitch = square ? 0 : shownPitch;
        // framed like the edited build: an option tried on under the mouse must not move the picture, the callouts laid out
        // against it, or the option list docked to them (the list would slide under the mouse and try on the next option)
        g.getRenderState().submitPicturesInPictureState(WeaponPreviewRenderer.State.of(weapon.id(), shown, drawYaw, drawPitch, zoom,
                orientation, previewX, previewY, previewX + previewW, previewY + previewH, g.getScissorStack().peek())
                .framedBy(session.edited));
        g.fill(previewX + previewW / 5, previewY + previewH - 2, previewX + previewW * 4 / 5, previewY + previewH - 1, ColorMath.fade(0x30FFFFFF, t));

        int bottomBand = height - pad - SkinCarousel.HEIGHT;
        if (sprayMode) {
            renderSprays(g, mouseX, mouseY, weapon, pad, columnW, c);
        } else {
            // the callouts, to the real mounts of the edited build (never of the option under the mouse: see above)
            WeaponAssembly current = assembly(weapon, session.edited);
            if (current != null && frame != null) {
                Matrix4f view = WeaponPreviewRenderer.viewFor(frame, drawYaw, drawPitch, zoom, orientation);
                callouts.render(g, mouseX, mouseY, session, current, view, frame, previewX, previewY, previewW, previewH,
                        pad, width - pad - columnW, columnW, targetY, bottomBand - 30, picker.slot(), c);
            }
            MountCallouts.Box open = openBox();
            if (open != null) {
                picker.render(g, mouseX, mouseY, session, open.x(), open.y(), open.w(), open.h(), height, c);
                // what the option under the mouse changes, docked under the list
                if (weapon instanceof GunData.Gun gun && picker.bottom() > 0) {
                    int sy = picker.bottom() + 4, sh = CraftStats.gunRows(true) * 12 + 10;
                    if (sy + sh > bottomBand) sy = Math.max(targetY, bottomBand - sh);
                    stats.drawGun(g, gun, session.baseline(gun), shown, picker.x(), sy, OptionPicker.WIDTH, sh, c, true);
                }
            } else if (callouts.boxes().isEmpty() && weapon instanceof MeleeData.Knife knife) {
                CraftStats.drawKnife(g, knife, width - pad - columnW, targetY, columnW, c);
            }
            int carouselW = width - 2 * pad - 2 * 104;
            skins.render(g, mouseX, mouseY, session, pad + 104, bottomBand + Math.round((1 - c) * 12), carouselW, c);
        }
        drawButtons(g, mouseX, mouseY, pad, c);

        float toast = session.toast.get();
        if (toast > 0.02f) {
            String text = session.toastText.getString();
            Draw.text(g, text, width / 2f - UiFont.width(text) / 2f, previewY + previewH - 18 - (1 - toast) * 4, 1f,
                    ColorMath.fade(session.toastColor, toast), true);
        }
    }

    /** The spray page: the weapon turned square on, the sprays list in the right column. */
    private void renderSprays(GuiGraphics g, int mouseX, int mouseY, Craftable weapon, int pad, int columnW, float a) {
        sprays.drawMarker(g, previewX, previewY, previewW, previewH, weapon, session.edited, a);
        int w = Math.max(columnW, 170);
        int x = width - pad - w, y = previewY, h = height - pad - 34 - y;
        Draw.rect(g, x - 6, y - 6, w + 12, h + 12, ColorMath.fade(0xD0101722, a));
        Draw.text(g, CraftSession.categoryName(CraftSession.SPRAY).getString(), x, y - 2, 1f, ColorMath.fade(TEXT, a), false);
        g.enableScissor(x - 2, y + 12, x + w + 4, y + h);
        sprays.draw(g, mouseX, mouseY, x, y + 14, w, y + 12, h - 12, a, weapon, session.edited, b -> session.edited = b);
        g.disableScissor();
    }

    private void drawButtons(GuiGraphics g, int mouseX, int mouseY, int pad, float a) {
        int bw = 96, bh = 18;
        int x = width - pad - bw, y = height - pad - bh;
        boolean changed = session.changed(), waiting = session.waiting();
        button(g, mouseX, mouseY, x, y, bw, bh, Component.translatable("blockland.craft.save_apply").getString(),
                changed && !waiting ? GO : DISABLED, changed && !waiting, a, session::save);
        button(g, mouseX, mouseY, x, y - bh - 4, bw, bh, Component.translatable("blockland.craft.reset").getString(), 0xFF2A3446, true, a, () -> {
            session.reset();
            picker.close();
        });
        button(g, mouseX, mouseY, pad, y, bw, bh, Component.translatable(sprayMode ? "blockland.craft.ingame.back" : "blockland.craft.spray").getString(),
                sprayMode ? 0xFF6A4A16 : 0xFF2A3446, true, a, () -> {
                    sprayMode = !sprayMode;
                    picker.close();
                    sprays.reset();
                    CraftSession.playUi(1.4f, 0.25f);
                });
        if (changed) {
            String note = Component.translatable("blockland.craft.unsaved").getString();
            Draw.text(g, note, x + bw - UiFont.width(note) * 0.75f, y - 2 * bh - 14, 0.75f, ColorMath.fade(ACCENT, a * 0.85f), false);
        }
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color,
                        boolean enabled, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        Spring spring = hover.computeIfAbsent("btn" + x + "," + y, k -> new Spring(0).config(320, 24));
        spring.setTarget(over ? 1 : 0);
        float hv = spring.get();
        Draw.rect(g, x, y, w, h, ColorMath.fade(ColorMath.lighten(color, 0.1f * hv), a));
        String fitted = Draw.fit(label, w - 6);
        Draw.text(g, fitted, x + (w - UiFont.width(fitted)) / 2f, y + 5, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    /** What the preview shows: the option or skin under the mouse tried on, else the edited build. */
    private Build previewBuild() {
        String option = picker.hovered();
        if (option != null && picker.slot() != null) return CraftSession.with(picker.slot(), session.edited, option);
        String skin = skins.hovered();
        if (skin != null && !sprayMode) return session.edited.withSkin(skin);
        return session.edited;
    }

    @Nullable
    private MountCallouts.Box openBox() {
        if (picker.slot() == null) return null;
        for (MountCallouts.Box box : callouts.boxes()) {
            if (box.slot().id().equals(picker.slot())) return box;
        }
        return null;
    }

    /** The assembly of what is shown (rebuilt only when the build changes), and its framing. */
    @Nullable
    private WeaponAssembly assembly(Craftable weapon, Build build) {
        if (assembly == null || !build.equals(assembled)) {
            assembly = WeaponAssembly.of(weapon, CraftCatalog.complete(weapon, build), null, false);
            assembled = build;
            // framed like the preview renderer frames the same build, so the lines meet the picture
            frame = assembly == null ? null : WeaponPreviewRenderer.frameOf(assembly);
        }
        return assembly;
    }

    // ---- input ------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing) return true;
        if (button == 1) {
            if (picker.slot() != null) {
                picker.close();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        for (Hit hit : hits) {
            if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        Craftable weapon = session.weapon;
        if (sprayMode && weapon != null) {
            if (sprays.mouseClicked(mouseX, mouseY)) {
                CraftSession.playUi(1.0f, 0.4f);
                return true;
            }
            if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
                if (sprays.previewPressed(mouseX, mouseY, previewX, previewY, previewW, previewH, weapon, session.edited, b -> session.edited = b)) {
                    CraftSession.playUi(1.6f, 0.3f);
                }
                return true;
            }
            return true;
        }
        if (picker.mouseClicked(mouseX, mouseY, session)) return true;
        MountCallouts.Box box = callouts.at(mouseX, mouseY);
        if (box != null) {
            picker.toggle(box.slot().id(), box.left());
            CraftSession.playUi(1.3f, 0.3f);
            return true;
        }
        if (skins.mouseClicked(mouseX, mouseY)) return true;
        if (picker.slot() != null) {
            picker.close();
            return true;
        }
        if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) dragging = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        sprays.released();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && sprayMode && sprays.isPlacing() && session.weapon != null) {
            sprays.previewDragged(mouseX, mouseY, previewX, previewY, previewW, previewH, session.weapon, session.edited, b -> session.edited = b);
            return true;
        }
        if (button == 0 && dragging) {
            if (!userRotated) {
                // take over from the idle turn where it is
                yaw += (float) Math.sin(idleTime * 0.4) * 12;
                userRotated = true;
            }
            yaw += (float) dragX * 1.2f;
            pitch = Math.max(-60, Math.min(60, pitch + (float) dragY * 0.8f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (sprayMode && session.weapon != null && inside(mouseX, mouseY, previewX, previewY, previewW, previewH)
                && sprays.previewScrolled(scrollY, hasShiftDown(), session.edited, b -> session.edited = b)) {
            return true;
        }
        if (picker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (!sprayMode && skins.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
            zoom = Math.max(0.7f, Math.min(2.2f, zoom + (float) scrollY * 0.12f));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        // the craft key closes it again
        if (ClientCraft.KEY.matches(key, scanCode)) {
            lowerAndClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
