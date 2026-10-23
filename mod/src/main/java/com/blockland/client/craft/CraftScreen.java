package com.blockland.client.craft;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.melee.KnifeItem;
import com.blockland.melee.MeleeContent;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentItem;
import com.blockland.melee.MeleeData;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.CraftPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * 工匠 (craft): customise a weapon — every slot it has (optic, barrel, laser, magazine, stock, charm …), its sprays and
 * its skin (in the 迷彩 / 特效皮肤 / 特殊皮肤 tabs) — with a live 3D preview and, for guns, stat bars that show what an
 * option changes. Works for guns and knives alike.
 * From the lobby (no world) it lists every weapon and edits the saved builds; in game it opens for the held weapon and
 * saving also fits the build to it. Builds are kept and validated by the server.
 */
public final class CraftScreen extends Screen {
    /** The 迷彩 (camo) tab: ordinary skins — colours and texture sets. */
    private static final String SKIN = "skin";
    /** The 特效皮肤 tab: skins with shader effects (asset spec §21). */
    private static final String SHADER_SKIN = "skin_shader";
    /** The 特殊皮肤 tab: skins with a model or shot effects of their own (asset spec §30). */
    private static final String SPECIAL_SKIN = "skin_special";
    /** The 喷漆 page (asset spec §29): not a slot, the sprays placed on the weapon. */
    private static final String SPRAY = "spray";
    /** Colour of special skins (their own model or shot effects, spec §30). */
    private static final int SPECIAL = 0xFFFF4D5E;

    @Nullable
    private final Screen parent;
    private final List<Craftable> weapons;
    /** Inventory slot of the held weapon to fit on save; -1 in the lobby. */
    private final int fitSlot;
    private Craftable weapon;
    private Build edited;
    private String category;
    @Nullable
    private String hoveredOption;

    private float yaw = -112, pitch = 12, zoom = 1;
    private boolean userRotated;
    private boolean dragging;
    private float optionScroll, optionScrollTarget;
    private int optionContentHeight, optionViewTop, optionViewHeight;
    private int previewX, previewY, previewW, previewH;
    private final Map<String, Spring> hover = new HashMap<>();
    private final Map<String, Spring> bars = new HashMap<>();
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private final AnimValue intro = new AnimValue(0);
    private final AnimValue toast = new AnimValue(0);
    private Component toastText = Component.empty();
    private int toastColor = OK;
    /** The builds version a save was sent at; -1 while no save is waiting for its answer. */
    private int awaitingVersion = -1;
    private final List<Hit> hits = new ArrayList<>();
    private final SprayEditor sprays = new SprayEditor();
    /** 0 = the free preview angle, 1 = the spray page's side view. */
    private float sideBlend;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** The lobby's 工匠: every weapon, editing the saved builds. */
    public CraftScreen(@Nullable Screen parent) {
        this(parent, CraftCatalog.all(), -1, null);
    }

    private CraftScreen(@Nullable Screen parent, List<Craftable> weapons, int fitSlot, @Nullable Build current) {
        super(Component.translatable("blockland.craft.title"));
        this.parent = parent;
        this.weapons = List.copyOf(weapons);
        this.fitSlot = fitSlot;
        if (!this.weapons.isEmpty()) select(this.weapons.get(0), current);
    }

    /** The 工匠 for the weapon in the player's hand (gun, knife or equipment); empty when the item is not craftable. */
    public static Optional<CraftScreen> forHeld(ItemStack stack, int slot) {
        Build current = null;
        String id = null;
        if (stack.getItem() instanceof GunItem gun) {
            GunState state = GunContent.stateOf(stack);
            current = state == null ? null : state.build();
            id = gun.gunId();
        } else if (stack.getItem() instanceof KnifeItem knife) {
            current = MeleeContent.buildOf(stack);
            id = knife.knifeId();
        } else if (stack.getItem() instanceof EquipmentItem item) {
            current = EquipmentContent.buildOf(stack);
            id = item.equipmentId();
        }
        if (id == null) return Optional.empty();
        Build build = current;
        return CraftCatalog.get(id).map(weapon -> new CraftScreen(null, List.of(weapon), slot, build));
    }

    private void select(Craftable next, @Nullable Build current) {
        weapon = next;
        edited = CraftCatalog.complete(next, current != null ? current : baseline(next));
        category = next.slots().isEmpty() ? SKIN : next.slots().get(0).id();
        sprays.reset();
        optionScroll = optionScrollTarget = 0;
        userRotated = false;
        zoom = 1;
    }

    /** What the weapon wears now: the held item's build in game, the saved build in the lobby. */
    private Build baseline(Craftable target) {
        if (isHeldMode() && minecraft != null && minecraft.player != null) {
            ItemStack stack = minecraft.player.getInventory().getItem(fitSlot);
            Build held = stack.getItem() instanceof GunItem ? Optional.ofNullable(GunContent.stateOf(stack)).map(GunState::build).orElse(null)
                    : stack.getItem() instanceof EquipmentItem ? EquipmentContent.buildOf(stack) : MeleeContent.buildOf(stack);
            if (held != null) return CraftCatalog.complete(target, held);
        }
        return CraftCatalog.complete(target, ClientBuilds.get(target.id()).orElse(null));
    }

    private boolean isHeldMode() {
        return fitSlot >= 0;
    }

    @Override
    protected void init() {
        ClientBuilds.requestIfNeeded();
        if (intro.get() == 0) Anim.to(intro, 1, 0.45).ease(Ease.QUART_OUT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        // in game the weapon must still be held (dropped, switched, died: nothing left to fit)
        if (isHeldMode() && (minecraft.player == null || !CraftCatalog.get(weapon.id()).isPresent()
                || heldWeaponId(minecraft.player.getInventory().getItem(fitSlot)) == null)) {
            onClose();
        }
    }

    @Nullable
    private static String heldWeaponId(ItemStack stack) {
        if (stack.getItem() instanceof GunItem gun) return gun.gunId();
        if (stack.getItem() instanceof KnifeItem knife) return knife.knifeId();
        if (stack.getItem() instanceof EquipmentItem item) return item.equipmentId();
        return null;
    }

    // ---- options --------------------------------------------------------------------------------

    private record Option(String id, Component name, Component description, String rarity) {
    }

    private List<String> categories() {
        List<String> list = new ArrayList<>();
        for (Craftable.Slot slot : weapon.slots()) list.add(slot.id());
        list.add(SPRAY);
        // the skins in three tabs by kind: 迷彩 (colours, texture sets), 特效皮肤 (shader effects), 特殊皮肤 (own model or
        // shot effects); all of them set the build's one skin
        list.add(SKIN);
        for (String kind : List.of(SHADER_SKIN, SPECIAL_SKIN)) {
            if (weapon.skins().values().stream().anyMatch(skin -> skinKind(skin).equals(kind))) list.add(kind);
        }
        return list;
    }

    /** The skin tab a skin is listed in. */
    private static String skinKind(CraftData.Skin skin) {
        return skin.isSpecial() ? SPECIAL_SKIN : skin.hasEffects() ? SHADER_SKIN : SKIN;
    }

    private static boolean isSkinCategory(String category) {
        return category.equals(SKIN) || category.equals(SHADER_SKIN) || category.equals(SPECIAL_SKIN);
    }

    private static Component categoryName(String category) {
        if (category.equals(SPRAY)) return Component.translatable("blockland.craft.spray");
        if (category.equals(SHADER_SKIN)) return Component.translatable("blockland.craft.skin.shader");
        if (category.equals(SPECIAL_SKIN)) return Component.translatable("blockland.craft.skin.special");
        return category.equals(SKIN) ? Component.translatable("blockland.craft.skin") : Component.translatable("blockland.craft.slot." + category);
    }

    private List<Option> options(String category) {
        List<Option> list = new ArrayList<>();
        if (isSkinCategory(category)) {
            for (CraftData.Skin skin : weapon.skins().values()) {
                if (!skinKind(skin).equals(category)) continue;
                // special skins (own model / shot effects) and shader skins (animated materials) say so under their name
                Component tag = skin.isSpecial() ? Component.translatable("blockland.craft.special_skin")
                        : skin.hasEffects() ? Component.translatable("blockland.craft.shader_skin") : Component.empty();
                list.add(new Option(skin.id, CraftCatalog.skinName(weapon, skin), tag, skin.rarity));
            }
            return list;
        }
        weapon.slot(category).ifPresent(slot -> {
            for (String id : slot.options()) {
                Craftable.OptionInfo info = weapon.describe(category, id);
                list.add(new Option(id, info.name(), info.description(), info.rarity()));
            }
        });
        return list;
    }

    private static String selected(String category, Build build, Craftable weapon) {
        if (isSkinCategory(category)) return build.skin();
        if (category.equals(SPRAY)) return "";
        return build.option(category, weapon.slot(category).map(Craftable.Slot::defaultOption).orElse(CraftData.NONE));
    }

    private static Build with(String category, Build build, String id) {
        if (category.equals(SPRAY)) return build;
        return isSkinCategory(category) ? build.withSkin(id) : build.with(category, id);
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        g.fillGradient(0, 0, width, height, 0xF2070A10, 0xF20D1420);
        Draw.glow(g, width * 0.66f, height * 0.42f, width * 0.42f, height * 0.5f, 0x2A3A6EA5);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hits.clear();
        float a = intro.get();
        int pad = 14;
        checkSaveAnswer();
        String title = Component.translatable("blockland.craft.title").getString();
        Draw.text(g,title, pad, 10, 1.6f, ColorMath.fade(TEXT, a), true);
        if (weapon == null) {
            Draw.text(g,Component.translatable("blockland.craft.empty").getString(), pad, 40, 1f, ColorMath.fade(MUTED, a), false);
            return;
        }
        float nameX = pad + UiFont.width(title) * 1.6f + 10;
        String name = weapon.displayName().getString();
        Draw.text(g,name, nameX, 14, 1f, ColorMath.fade(ACCENT, a), false);
        if (weapon instanceof GunData.Gun gun && gun.modes().size() > 1) {
            String modes = gun.modes().stream().map(m -> Component.translatable("blockland.craft.fire_mode." + m.name().toLowerCase(java.util.Locale.ROOT)).getString())
                    .reduce((x, y) -> x + " / " + y).orElse("");
            Draw.text(g,modes, nameX + UiFont.width(name) + 8, 14, 1f, ColorMath.fade(MUTED, a), false);
        }
        String hint = Component.translatable("blockland.craft.hint").getString();
        Draw.text(g,hint, width - pad - UiFont.width(hint), 14, 1f, ColorMath.fade(DIM, a), false);

        int x = pad;
        if (weapons.size() > 1) {
            int listW = Math.min(118, (int) (width * 0.2f));
            drawWeaponList(g, mouseX, mouseY, x, 36, listW, a);
            x += listW + 10;
        }
        int optW = Math.min(196, (int) (width * 0.32f));
        drawOptions(g, mouseX, mouseY, x, 36, optW, height - 36 - pad, a);

        // preview and numbers on the right
        previewX = x + optW + pad;
        previewY = 34;
        previewW = width - previewX - pad;
        previewH = (int) ((height - previewY) * 0.52f);
        Draw.glow(g, previewX + previewW / 2f, previewY + previewH / 2f, previewW * 0.5f, previewH * 0.6f, ColorMath.fade(0x55A9C4E8, a));
        g.fill(previewX + previewW / 6, previewY + previewH - 2, previewX + previewW * 5 / 6, previewY + previewH - 1, ColorMath.fade(0x30FFFFFF, a));
        if (!userRotated && !dragging) yaw = -112 + (float) Math.sin(Ticker.global().time() * 0.35) * 14;
        // sprays are placed on a side seen square on (the weapon root's frame): the view turns there and back
        sideBlend += ((category.equals(SPRAY) ? 1 : 0) - sideBlend) * 0.3f;
        Quaternionf orientation = null;
        if (sideBlend > 0.002f) {
            Quaternionf side = sprays.sideView(weapon, edited);
            if (side != null) {
                orientation = sideBlend > 0.998f ? side : new Quaternionf().rotateX((float) Math.toRadians(pitch))
                        .rotateY((float) Math.toRadians(yaw)).slerp(side, sideBlend);
            }
        }
        boolean square = orientation != null && sideBlend > 0.998f;
        g.getRenderState().submitPicturesInPictureState(WeaponPreviewRenderer.State.of(weapon.id(), edited, square ? 0 : yaw,
                square ? 0 : pitch, zoom, orientation, previewX, previewY, previewX + previewW, previewY + previewH,
                g.getScissorStack().peek()));
        if (category.equals(SPRAY)) sprays.drawMarker(g, previewX, previewY, previewW, previewH, weapon, edited, a);
        int infoY = previewY + previewH + 6;
        if (weapon instanceof GunData.Gun gun) drawGunStats(g, gun, previewX, infoY, previewW, height - infoY - pad, a);
        else if (weapon instanceof MeleeData.Knife knife) drawKnifeInfo(g, knife, previewX, infoY, previewW, a);

        float t = toast.get();
        if (t > 0.02f) {
            String text = toastText.getString();
            Draw.text(g,text, previewX + previewW / 2f - UiFont.width(text) / 2f, previewY + previewH - 16 - (1 - t) * 4, 1f,
                    ColorMath.fade(toastColor, t), true);
        }
    }

    private void drawWeaponList(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
        Craftable.Kind kind = null;
        for (Craftable entry : weapons) {
            if (entry.kind() != kind) {
                kind = entry.kind();
                Draw.text(g,Component.translatable("blockland.craft.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)).getString(),
                        x + 2, y + 2, 0.75f, ColorMath.fade(DIM, a), false);
                y += 12;
            }
            boolean chosen = entry == weapon;
            boolean over = inside(mouseX, mouseY, x, y, w, 20);
            float hv = spring(hover, "w" + entry.id(), over ? 1 : 0, 320, 24).get();
            float sel = spring(hover, "ws" + entry.id(), chosen ? 1 : 0, 260, 24).get();
            Draw.rect(g, x, y, w, 20, ColorMath.fade(ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel), a));
            if (sel > 0.01f) g.fill(x, y, x + 2, y + 20, ColorMath.fade(ACCENT, a * sel));
            String label = Draw.fit(entry.displayName().getString(), w - 12);
            Draw.text(g,label, x + 7 + Math.round(hv * 2), y + 6, 1f, ColorMath.fade(chosen ? TEXT : ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (!ClientBuilds.get(entry.id()).isEmpty()) {
                Draw.rect(g, x + w - 7, y + 8, 3, 3, ColorMath.fade(OK, a * 0.8f));
            }
            Craftable target = entry;
            hits.add(new Hit(x, y, w, 20, () -> {
                if (target != weapon) {
                    select(target, null);
                    playUi(1.4f, 0.25f);
                }
            }));
            y += 22;
        }
    }

    private void drawOptions(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float a) {
        // category tabs, wrapped onto several rows when they do not fit
        int tx = x, ty = y;
        for (String c : categories()) {
            String label = categoryName(c).getString();
            int tw = UiFont.width(label) + 14;
            if (tx + tw > x + w && tx > x) {
                tx = x;
                ty += 18;
            }
            boolean over = inside(mouseX, mouseY, tx, ty, tw, 16);
            float hv = spring(hover, "tab" + c, over || c.equals(category), 320, 26).get();
            Draw.text(g,label, tx + 7, ty + 4 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
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
                    playUi(1.4f, 0.25f);
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
        String current = selected(category, edited, weapon);
        String equipped = selected(category, baseline(weapon), weapon);
        g.enableScissor(x - 2, optionViewTop, x + w + 6, optionViewTop + optionViewHeight);
        int cy = optionViewTop - Math.round(optionScroll);
        int contentTop = cy;
        // the spray page draws itself in the same scrolling area
        if (category.equals(SPRAY)) {
            cy += sprays.draw(g, mouseX, mouseY, x, cy, w, optionViewTop, optionViewHeight, a, weapon, edited, b -> edited = b);
        }
        for (Option option : category.equals(SPRAY) ? List.<Option>of() : options(category)) {
            String description = option.description().getString();
            int ch = description.isEmpty() ? 22 : 34;
            boolean visible = cy + ch > optionViewTop && cy < optionViewTop + optionViewHeight;
            boolean over = visible && inside(mouseX, mouseY, x, Math.max(cy, optionViewTop), w, Math.min(cy + ch, optionViewTop + optionViewHeight) - Math.max(cy, optionViewTop));
            if (over) hoveredOption = option.id();
            boolean chosen = option.id().equals(current);
            float hv = spring(hover, category + option.id(), over ? 1 : 0, 320, 24).get();
            float sel = spring(hover, "sel" + category + option.id(), chosen ? 1 : 0, 260, 24).get();
            if (visible) {
                int base = ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, sel);
                int ox = x + Math.round(hv * 3);
                Draw.rect(g, ox, cy, w, ch, ColorMath.fade(base, a));
                Draw.rect(g, ox, cy, Math.round(2 + 2 * Math.max(hv, sel)), ch,
                        ColorMath.fade(rarityColor(option.rarity(), chosen), a * (0.4f + 0.6f * Math.max(hv, sel))));
                if (sel > 0.01f) Draw.outline(g, ox, cy, w, ch, ColorMath.fade(ACCENT, a * 0.6f * sel));
                int textX = x + 10 + Math.round(hv * 3);
                Draw.text(g,Draw.fit(option.name().getString(), w - 60), textX, cy + 6, 1f, ColorMath.fade(TEXT, a), false);
                if (!description.isEmpty()) {
                    Draw.text(g,Draw.fit(description, w - 18), textX, cy + 20, 1f, ColorMath.fade(MUTED, a), false);
                }
                String tag = null;
                int tagColor = OK;
                if (option.id().equals(equipped)) {
                    tag = Component.translatable(isHeldMode() ? "blockland.craft.equipped" : "blockland.craft.saved_tag").getString();
                } else if ("legendary".equals(option.rarity())) {
                    tag = Component.translatable("blockland.craft.legendary").getString();
                    tagColor = GOLD;
                } else if ("special".equals(option.rarity())) {
                    tag = Component.translatable("blockland.craft.special").getString();
                    tagColor = SPECIAL;
                }
                if (tag != null) Draw.text(g,tag, x + w - 8 - UiFont.width(tag) * 0.75f, cy + 7, 0.75f, ColorMath.fade(tagColor, a), false);
                String id = option.id();
                int hitTop = Math.max(cy, optionViewTop), hitBottom = Math.min(cy + ch, optionViewTop + optionViewHeight);
                hits.add(new Hit(x, hitTop, w, hitBottom - hitTop, () -> {
                    if (!id.equals(selected(category, edited, weapon))) {
                        edited = with(category, edited, id);
                        playUi(1.0f, 0.5f);
                    }
                }));
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
        Build saved = baseline(weapon);
        boolean changed = !edited.equals(saved);
        boolean waiting = awaitingVersion >= 0;
        String saveLabel = Component.translatable(isHeldMode() ? "blockland.craft.save_apply" : "blockland.craft.save").getString();
        button(g, mouseX, mouseY, x, buttonsY, w / 2 - 3, 20, saveLabel, changed && !waiting ? GO : DISABLED, changed && !waiting, a, this::save);
        button(g, mouseX, mouseY, x + w / 2 + 3, buttonsY, w / 2 - 3, 20, Component.translatable("blockland.craft.reset").getString(),
                0xFF2A3446, true, a, () -> {
                    edited = weapon.defaultBuild();
                    playUi(0.8f, 0.4f);
                });
        if (changed) {
            String note = Component.translatable("blockland.craft.unsaved").getString();
            Draw.text(g,note, x, buttonsY - 12, 0.75f, ColorMath.fade(ACCENT, a * 0.85f), false);
        }
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color,
                        boolean enabled, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = spring(hover, "btn" + x, over ? 1 : 0, 320, 24).get();
        Draw.rect(g, x, y, w, h, ColorMath.fade(ColorMath.lighten(color, 0.1f * hv), a));
        String fitted = Draw.fit(label, w - 6);
        Draw.text(g,fitted, x + (w - UiFont.width(fitted)) / 2f, y + 6, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    // ---- numbers --------------------------------------------------------------------------------

    private record Stat(String key, float applied, float preview, String value) {
    }

    private void drawGunStats(GuiGraphics g, GunData.Gun gun, int x, int y, int w, int h, float a) {
        GunStats now = GunStats.of(gun.id, new GunState(baseline(gun), 0)).orElse(null);
        Build shown = hoveredOption != null ? with(category, edited, hoveredOption) : edited;
        GunStats next = GunStats.of(gun.id, new GunState(shown, 0)).orElse(null);
        if (now == null || next == null || h < 20) return;
        List<Stat> stats = List.of(
                new Stat("damage", damage(now), damage(next), String.format("%.1f", next.damageAt(0))),
                new Stat("rate", next.gun().rpm / 1100f, next.gun().rpm / 1100f, (int) next.gun().rpm + " RPM"),
                new Stat("range", now.range() / 150f, next.range() / 150f, String.format("%.0f", next.range())),
                new Stat("hip", 1 - now.baseSpread(false) / 8f, 1 - next.baseSpread(false) / 8f, String.format("%.1f°", next.baseSpread(false))),
                new Stat("ads_accuracy", 1 - now.baseSpread(true) / 1.5f, 1 - next.baseSpread(true) / 1.5f, String.format("%.2f°", next.baseSpread(true))),
                new Stat("recoil", 1 - now.recoilVertical() / 4f, 1 - next.recoilVertical() / 4f, String.format("%.2f", next.recoilVertical())),
                new Stat("ads_speed", 1 - now.adsTime() / 0.5f, 1 - next.adsTime() / 0.5f, Math.round(next.adsTime() * 1000) + " ms"),
                new Stat("reload", 1 - now.reloadTime(false) / 4f, 1 - next.reloadTime(false) / 4f, String.format("%.2f s", next.reloadTime(false))),
                new Stat("capacity", now.capacity() / 50f, next.capacity() / 50f, String.valueOf(next.capacity())));
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, a));
        int rowH = Math.max(9, Math.min(14, (h - 6) / stats.size()));
        int labelW = 0;
        for (Stat s : stats) labelW = Math.max(labelW, UiFont.width(Component.translatable("blockland.craft.stat." + s.key()).getString()));
        int valueW = 52;
        int barX = x + 8 + labelW + 6, barW = Math.max(20, w - labelW - valueW - 30);
        for (int i = 0; i < stats.size(); i++) {
            Stat s = stats.get(i);
            int ry = y + 6 + i * rowH;
            if (ry + 8 > y + h) break;
            Draw.text(g,Component.translatable("blockland.craft.stat." + s.key()).getString(), x + 8, ry, 1f, ColorMath.fade(MUTED, a), false);
            float base = spring01(bars, "a" + i, s.applied());
            float target = spring01(bars, "p" + i, s.preview());
            g.fill(barX, ry + 3, barX + barW, ry + 6, ColorMath.fade(0x30FFFFFF, a));
            int common = Math.round(barW * Math.min(base, target));
            g.fill(barX, ry + 3, barX + common, ry + 6, ColorMath.fade(TEXT, a * 0.85f));
            if (target > base + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * target), ry + 6, ColorMath.fade(OK, a));
            } else if (base > target + 0.002f) {
                g.fill(barX + common, ry + 3, barX + Math.round(barW * base), ry + 6, ColorMath.fade(ERROR, a));
            }
            Draw.text(g,s.value(), x + w - 8 - UiFont.width(s.value()), ry, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    private void drawKnifeInfo(GuiGraphics g, MeleeData.Knife knife, int x, int y, int w, float a) {
        List<Component> rows = List.of(
                Component.translatable("blockland.craft.knife.light", format(knife.light.damage)),
                Component.translatable("blockland.craft.knife.heavy", format(knife.heavy.damage)),
                Component.translatable("blockland.craft.knife.backstab", format(knife.heavy.backstabMultiplier)),
                Component.translatable("blockland.craft.knife.cosmetic"));
        int h = rows.size() * 13 + 10;
        Draw.rect(g, x, y, w, h, ColorMath.fade(PANEL, a));
        for (int i = 0; i < rows.size(); i++) {
            Draw.text(g,Draw.fit(rows.get(i).getString(), w - 16), x + 8, y + 6 + i * 13, 1f,
                    ColorMath.fade(i == rows.size() - 1 ? DIM : MUTED, a), false);
        }
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.format("%.2f", value);
    }

    private static float damage(GunStats s) {
        return Math.min(1, s.damageAt(0) / 20f);
    }

    private static float spring01(Map<String, Spring> map, String key, float target) {
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
        if ("special".equals(rarity)) return SPECIAL;
        return chosen ? ACCENT : INFO;
    }

    // ---- actions --------------------------------------------------------------------------------

    private void save() {
        if (weapon == null || awaitingVersion >= 0 || edited.equals(baseline(weapon))) return;
        awaitingVersion = ClientBuilds.version();
        BLNetwork.sendToServer(new CraftPackets.C2SSave(weapon.id(), edited, fitSlot));
        playUi(1.0f, 0.6f);
    }

    /** The server answers a save with the builds (or why it refused): show it once it arrives. */
    private void checkSaveAnswer() {
        if (awaitingVersion < 0 || ClientBuilds.version() == awaitingVersion) return;
        awaitingVersion = -1;
        String error = ClientBuilds.lastError();
        if (error.isEmpty()) {
            showToast(Component.translatable(isHeldMode() ? "blockland.craft.applied" : "blockland.craft.saved"), OK);
        } else {
            showToast(Component.translatable(error), ERROR);
            edited = baseline(weapon);
        }
    }

    private void showToast(Component text, int color) {
        toastText = text;
        toastColor = color;
        toast.snap(1);
        toast.to(0, 0.5, Ease.QUAD_IN).delay(1.4);
    }

    private static void playUi(float pitch, float volume) {
        SoundEvent attach = GunContent.sound("gun.attach");
        if (attach != null) net.minecraft.client.Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(attach, pitch, volume));
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit hit : hits) {
                if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                    hit.action.run();
                    return true;
                }
            }
            if (category.equals(SPRAY) && weapon != null) {
                if (sprays.mouseClicked(mouseX, mouseY)) {
                    playUi(1.0f, 0.4f);
                    return true;
                }
                // the spray page keeps the side view: a press on the preview places the spray instead of turning the weapon
                if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
                    if (sprays.previewPressed(mouseX, mouseY, previewX, previewY, previewW, previewH, weapon, edited, b -> edited = b)) {
                        playUi(1.6f, 0.3f);
                    }
                    return true;
                }
            }
            if (inside(mouseX, mouseY, previewX, previewY, previewW, previewH)) dragging = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        sprays.released();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && sprays.isPlacing() && weapon != null) {
            sprays.previewDragged(mouseX, mouseY, previewX, previewY, previewW, previewH, weapon, edited, b -> edited = b);
            return true;
        }
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
        if (mouseX >= previewX && category.equals(SPRAY) && weapon != null
                && inside(mouseX, mouseY, previewX, previewY, previewW, previewH)
                && sprays.previewScrolled(scrollY, hasShiftDown(), edited, b -> edited = b)) {
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
