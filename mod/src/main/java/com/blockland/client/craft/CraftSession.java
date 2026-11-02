package com.blockland.client.craft;

import com.blockland.anim.AnimValue;
import com.blockland.anim.Ease;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinKeys;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentItem;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.melee.KnifeItem;
import com.blockland.melee.MeleeContent;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.CraftPackets;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.blockland.client.screen.lobby.LobbyTheme.ACCENT;
import static com.blockland.client.screen.lobby.LobbyTheme.ERROR;
import static com.blockland.client.screen.lobby.LobbyTheme.GOLD;
import static com.blockland.client.screen.lobby.LobbyTheme.INFO;
import static com.blockland.client.screen.lobby.LobbyTheme.OK;

/**
 * What a 工匠 (craft) editor works on, whatever it looks like: the weapons it offers, the one being edited and its
 * edited build, what each slot and skin tab offers, saving to the server and its answer. The lobby tab
 * ({@link CraftPanel}) and the in-game screen ({@link HeldCraftScreen}) only lay this out. In the lobby (no world) it
 * edits the saved builds of every weapon; in game it edits the held weapon, and saving also fits the build to it. The
 * server keeps and validates the builds.
 */
final class CraftSession {
    /** The 迷彩 (camo) tab: skins that only change the materials — colours, texture sets and shader effects (spec §21). */
    static final String SKIN = "skin";
    /** The 特殊皮肤 tab: skins with a model or shot effects of their own (asset spec §30). */
    static final String SPECIAL_SKIN = "skin_special";
    /** The 喷漆 page (asset spec §29): not a slot, the sprays placed on the weapon. */
    static final String SPRAY = "spray";
    /** The 挂件 slot: a cosmetic slot, listed with the skins rather than with the attachments. */
    static final String CHARM = "charm";
    /** Colour of special skins (their own model or shot effects, spec §30). */
    static final int SPECIAL = 0xFFFF4D5E;
    /** Colour of what marks a skin the player does not own. */
    static final int LOCKED = 0xFFB8C0CC;

    /** One choice of a slot or a skin tab. */
    record Option(String id, Component name, Component description, String rarity) {
    }

    final List<Craftable> weapons;
    /** Inventory slot of the held weapon to fit on save; -1 in the lobby. */
    final int fitSlot;
    @Nullable
    Craftable weapon;
    Build edited;
    /** The builds version a save was sent at; -1 while no save is waiting for its answer. */
    private int awaitingVersion = -1;
    final AnimValue toast = new AnimValue(0);
    Component toastText = Component.empty();
    int toastColor = OK;

    CraftSession(List<Craftable> weapons, int fitSlot, @Nullable Build current) {
        this.weapons = List.copyOf(weapons);
        this.fitSlot = fitSlot;
        if (!this.weapons.isEmpty()) select(this.weapons.get(0), current);
    }

    /** The lobby's 工匠: every weapon, editing the saved builds. */
    static CraftSession lobby() {
        return new CraftSession(CraftCatalog.all(), -1, null);
    }

    /** The 工匠 for the weapon in the player's hand (gun, knife or equipment); empty when the item is not craftable. */
    static Optional<CraftSession> forHeld(ItemStack stack, int slot) {
        Build current = heldBuild(stack);
        String id = heldWeaponId(stack);
        if (id == null) return Optional.empty();
        return CraftCatalog.get(id).map(weapon -> new CraftSession(List.of(weapon), slot, current));
    }

    void select(Craftable next, @Nullable Build current) {
        weapon = next;
        edited = CraftCatalog.complete(next, current != null ? current : baseline(next));
    }

    boolean isHeldMode() {
        return fitSlot >= 0;
    }

    /** What the weapon wears now: the held item's build in game, the saved build in the lobby. */
    Build baseline(Craftable target) {
        Minecraft mc = Minecraft.getInstance();
        if (isHeldMode() && mc.player != null) {
            Build held = heldBuild(mc.player.getInventory().getItem(fitSlot));
            if (held != null) return CraftCatalog.complete(target, held);
        }
        return CraftCatalog.complete(target, ClientBuilds.get(target.id()).orElse(null));
    }

    /** In game: the edited weapon is still in its slot (dropped, switched, died: nothing left to fit). */
    boolean stillHeld() {
        if (!isHeldMode()) return true;
        Minecraft mc = Minecraft.getInstance();
        return weapon != null && mc.player != null && CraftCatalog.get(weapon.id()).isPresent()
                && weapon.id().equals(heldWeaponId(mc.player.getInventory().getItem(fitSlot)));
    }

    @Nullable
    static Build heldBuild(ItemStack stack) {
        if (stack.getItem() instanceof GunItem) return Optional.ofNullable(GunContent.stateOf(stack)).map(GunState::build).orElse(null);
        if (stack.getItem() instanceof KnifeItem) return MeleeContent.buildOf(stack);
        if (stack.getItem() instanceof EquipmentItem) return EquipmentContent.buildOf(stack);
        return null;
    }

    @Nullable
    static String heldWeaponId(ItemStack stack) {
        if (stack.getItem() instanceof GunItem gun) return gun.gunId();
        if (stack.getItem() instanceof KnifeItem knife) return knife.knifeId();
        if (stack.getItem() instanceof EquipmentItem item) return item.equipmentId();
        return null;
    }

    // ---- slots and skins ----------------------------------------------------------------------------

    /** Every page of the weapon: its attachment slots, then its looks. */
    List<String> categories() {
        List<String> list = gearCategories();
        list.addAll(cosmeticCategories());
        return list;
    }

    /** The attachment slots (everything that may change the numbers): every slot but the charm. */
    List<String> gearCategories() {
        List<String> list = new ArrayList<>();
        if (weapon == null) return list;
        for (Craftable.Slot slot : weapon.slots()) if (!slot.id().equals(CHARM)) list.add(slot.id());
        return list;
    }

    /**
     * The looks: 迷彩 (colours, texture sets, shader effects), the 挂件 slot, 特殊皮肤 (own model or shot effects) when the
     * weapon has any, and the sprays. Both skin tabs set the build's one skin.
     */
    List<String> cosmeticCategories() {
        List<String> list = new ArrayList<>();
        if (weapon == null) return list;
        list.add(SKIN);
        if (weapon.slot(CHARM).isPresent()) list.add(CHARM);
        if (weapon.skins().values().stream().anyMatch(skin -> skinKind(skin).equals(SPECIAL_SKIN))) list.add(SPECIAL_SKIN);
        list.add(SPRAY);
        return list;
    }

    /** The slots a player can change (more than one option). */
    List<Craftable.Slot> replaceableSlots() {
        List<Craftable.Slot> list = new ArrayList<>();
        if (weapon == null) return list;
        for (Craftable.Slot slot : weapon.slots()) if (slot.options().size() > 1) list.add(slot);
        return list;
    }

    /** The skin tab a skin is listed in. */
    static String skinKind(CraftData.Skin skin) {
        return skin.isSpecial() ? SPECIAL_SKIN : SKIN;
    }

    static boolean isSkinCategory(String category) {
        return category.equals(SKIN) || category.equals(SPECIAL_SKIN);
    }

    static Component categoryName(String category) {
        if (category.equals(SPRAY)) return Component.translatable("blockland.craft.spray");
        if (category.equals(SPECIAL_SKIN)) return Component.translatable("blockland.craft.skin.special");
        return category.equals(SKIN) ? Component.translatable("blockland.craft.skin") : Component.translatable("blockland.craft.slot." + category);
    }

    List<Option> options(String category) {
        List<Option> list = new ArrayList<>();
        if (weapon == null) return list;
        if (isSkinCategory(category)) {
            for (CraftData.Skin skin : weapon.skins().values()) {
                if (!skinKind(skin).equals(category)) continue;
                // special skins (own model / shot effects) and camos with shader effects (animated materials) say so under
                // their name; a skin the player does not own says where to get it (it can still be tried on)
                Component tag = locked(skin.id) ? Component.translatable("blockland.craft.skin.locked_hint")
                        : skin.isSpecial() ? Component.translatable("blockland.craft.special_skin")
                        : skin.hasEffects() ? Component.translatable("blockland.craft.shader_skin") : Component.empty();
                list.add(new Option(skin.id, CraftCatalog.skinName(weapon, skin), tag, skin.rarity));
            }
            return list;
        }
        Craftable target = weapon;
        target.slot(category).ifPresent(slot -> {
            for (String id : slot.options()) {
                Craftable.OptionInfo info = target.describe(category, id);
                list.add(new Option(id, info.name(), info.description(), info.rarity()));
            }
        });
        return list;
    }

    /**
     * Whether a skin of the edited weapon must be owned and the player does not own it (asset spec §30): it is listed and
     * can be previewed, but not saved.
     */
    boolean locked(String skinId) {
        if (weapon == null) return false;
        CraftData.Skin skin = weapon.skins().get(skinId);
        return skin != null && !SkinKeys.owned(weapon, skin, ClientBuilds::owns);
    }

    /** What {@code category} holds in {@code build}. */
    String selected(String category, Build build) {
        if (isSkinCategory(category)) return build.skin();
        if (category.equals(SPRAY) || weapon == null) return "";
        return build.option(category, weapon.slot(category).map(Craftable.Slot::defaultOption).orElse(CraftData.NONE));
    }

    static Build with(String category, Build build, String id) {
        if (category.equals(SPRAY)) return build;
        return isSkinCategory(category) ? build.withSkin(id) : build.with(category, id);
    }

    /** Picks {@code id} for {@code category} in the edited build (with the attach sound). */
    void choose(String category, String id) {
        if (id.equals(selected(category, edited))) return;
        edited = with(category, edited, id);
        // the newest choice wins a shared mount: what it excludes, and what excludes it, go back to their defaults
        Craftable.Slot chosen = weapon == null ? null : weapon.slot(category).orElse(null);
        if (chosen != null && !id.equals(chosen.defaultOption())) {
            for (Craftable.Slot slot : weapon.slots()) {
                if (slot == chosen) continue;
                if (chosen.excludes().contains(slot.id()) || slot.excludes().contains(category)) {
                    edited = edited.with(slot.id(), slot.defaultOption());
                }
            }
        }
        playUi(1.0f, 0.5f);
    }

    void reset() {
        if (weapon == null) return;
        edited = weapon.defaultBuild();
        playUi(0.8f, 0.4f);
    }

    // ---- saving -------------------------------------------------------------------------------------

    boolean changed() {
        return weapon != null && !edited.equals(baseline(weapon));
    }

    boolean waiting() {
        return awaitingVersion >= 0;
    }

    void save() {
        if (weapon == null || waiting() || !changed()) return;
        if (locked(edited.skin())) {
            // the server would refuse it: say why right away
            showToast(Component.translatable("blockland.craft.error.skin_locked"), ERROR);
            playUi(0.6f, 0.5f);
            return;
        }
        awaitingVersion = ClientBuilds.version();
        BLNetwork.sendToServer(new CraftPackets.C2SSave(weapon.id(), edited, fitSlot));
        playUi(1.0f, 0.6f);
    }

    /** The server answers a save with the builds (or why it refused): shows it once it arrives. Call every frame. */
    void checkSaveAnswer() {
        if (awaitingVersion < 0 || ClientBuilds.version() == awaitingVersion) return;
        awaitingVersion = -1;
        String error = ClientBuilds.lastError();
        if (error.isEmpty()) {
            showToast(Component.translatable(isHeldMode() ? "blockland.craft.applied" : "blockland.craft.saved"), OK);
        } else {
            showToast(Component.translatable(error), ERROR);
            if (weapon != null) edited = baseline(weapon);
        }
    }

    void showToast(Component text, int color) {
        toastText = text;
        toastColor = color;
        toast.snap(1);
        toast.to(0, 0.5, Ease.QUAD_IN).delay(1.4);
    }

    // ---- shared look --------------------------------------------------------------------------------

    static int rarityColor(@Nullable String rarity, boolean chosen) {
        if ("legendary".equals(rarity)) return GOLD;
        if ("special".equals(rarity)) return SPECIAL;
        return chosen ? ACCENT : INFO;
    }

    /** The tag an option card shows (not owned, equipped / saved, legendary, special), or null. */
    @Nullable
    String tag(String category, Option option) {
        if (isSkinCategory(category) && locked(option.id())) return Component.translatable("blockland.craft.skin.locked").getString();
        if (weapon != null && option.id().equals(selected(category, baseline(weapon)))) {
            return Component.translatable(isHeldMode() ? "blockland.craft.equipped" : "blockland.craft.saved_tag").getString();
        }
        if ("legendary".equals(option.rarity())) return Component.translatable("blockland.craft.legendary").getString();
        if ("special".equals(option.rarity())) return Component.translatable("blockland.craft.special").getString();
        return null;
    }

    int tagColor(String category, Option option) {
        if (isSkinCategory(category) && locked(option.id())) return LOCKED;
        if (weapon != null && option.id().equals(selected(category, baseline(weapon)))) return OK;
        return "special".equals(option.rarity()) ? SPECIAL : GOLD;
    }

    /** A small padlock (7 × 9 px) at {@code x, y}: marks a skin the player does not own. */
    static void lockIcon(GuiGraphics g, int x, int y, int color) {
        // the shackle: an arch of three bars over the body
        g.fill(x + 1, y + 1, x + 2, y + 4, color);
        g.fill(x + 5, y + 1, x + 6, y + 4, color);
        g.fill(x + 2, y, x + 5, y + 1, color);
        Draw.rect(g, x, y + 4, 7, 5, color);
        // the keyhole
        g.fill(x + 3, y + 5, x + 4, y + 7, 0xC0101722);
    }

    static void playUi(float pitch, float volume) {
        SoundEvent attach = GunContent.sound("gun.attach");
        if (attach != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(attach, pitch, volume));
    }
}
