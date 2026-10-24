package com.blockland.craft;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Every craftable weapon, independent of its kind. Weapon modules plug their catalogues in at start-up
 * ({@link #addSource}), so this module never depends on guns or knives. Also the one place that decides whether a
 * build is valid for a weapon — the server checks every request with it.
 */
public final class CraftCatalog {
    private static final List<Supplier<? extends Collection<? extends Craftable>>> SOURCES = new CopyOnWriteArrayList<>();

    private CraftCatalog() {
    }

    /** Adds a kind of weapon (called once per module during mod construction). */
    public static void addSource(Supplier<? extends Collection<? extends Craftable>> source) {
        SOURCES.add(source);
    }

    /** All craftable weapons: modules in registration order, each in its data order. */
    public static List<Craftable> all() {
        List<Craftable> all = new ArrayList<>();
        for (Supplier<? extends Collection<? extends Craftable>> source : SOURCES) all.addAll(source.get());
        return all;
    }

    public static Optional<Craftable> get(String weaponId) {
        for (Supplier<? extends Collection<? extends Craftable>> source : SOURCES) {
            for (Craftable craftable : source.get()) {
                if (craftable.id().equals(weaponId)) return Optional.of(craftable);
            }
        }
        return Optional.empty();
    }

    // ---- slots ----------------------------------------------------------------------------------------

    /**
     * Resolves a declared slot: without an explicit option list every attachment of the slot's kind fits (universal
     * attachments), with "none" first when the slot may stay empty.
     */
    public static Craftable.Slot resolve(String id, CraftData.SlotDef def) {
        String fallback = def.defaultOption == null || def.defaultOption.isEmpty() ? CraftData.NONE : def.defaultOption;
        List<String> options = new ArrayList<>();
        options.add(fallback);
        if (def.options != null) {
            for (String option : def.options) if (!options.contains(option)) options.add(option);
        } else {
            for (CraftData.Attachment attachment : AttachmentCatalog.get().ofSlot(id)) {
                if (!options.contains(attachment.id)) options.add(attachment.id);
            }
        }
        return new Craftable.Slot(id, def.node == null ? "" : def.node, fallback, options,
                def.hides == null ? List.of() : def.hides, Map.of(), def.noneName == null ? "" : def.noneName,
                def.excludes == null ? List.of() : def.excludes);
    }

    /** Slots declared in a weapon's data, in declaration order. */
    public static List<Craftable.Slot> resolveAll(@Nullable Map<String, CraftData.SlotDef> defs) {
        List<Craftable.Slot> slots = new ArrayList<>();
        if (defs != null) defs.forEach((id, def) -> slots.add(resolve(id, def)));
        return slots;
    }

    // ---- validation -----------------------------------------------------------------------------------

    /**
     * Why a build does not fit a weapon, or null when it does: the skin must exist, every slot named must be one of
     * the weapon's and hold one of its options. Slots the build leaves out simply keep their default.
     */
    @Nullable
    public static String check(Craftable weapon, Build build) {
        if (!weapon.skins().containsKey(build.skin())) return "unknown skin " + build.skin();
        if (build.slots().size() > Build.MAX_SLOTS) return "too many slots";
        for (Map.Entry<String, String> entry : build.slots().entrySet()) {
            Optional<Craftable.Slot> slot = weapon.slot(entry.getKey());
            if (slot.isEmpty()) return "unknown slot " + entry.getKey();
            if (!slot.get().allows(entry.getValue())) return "option " + entry.getValue() + " does not fit slot " + entry.getKey();
        }
        // sprays (asset spec §29): known, meant for this weapon, inside its view, within the size limits
        SprayCatalog sprays = SprayCatalog.get();
        if (build.sprays().size() > sprays.limits().perWeapon) return "too many sprays";
        for (Spray spray : build.sprays()) {
            String problem = sprays.check(weapon, spray);
            if (problem != null) return problem;
        }
        return null;
    }

    /**
     * The build with every slot set: anything missing or no longer valid falls back to the default; sprays that no longer
     * fit are dropped.
     */
    public static Build complete(Craftable weapon, @Nullable Build build) {
        String skin = build != null && weapon.skins().containsKey(build.skin()) ? build.skin() : "default";
        Map<String, String> slots = new LinkedHashMap<>();
        for (Craftable.Slot slot : weapon.slots()) {
            String option = build == null ? slot.defaultOption() : build.option(slot.id(), slot.defaultOption());
            slots.put(slot.id(), slot.allows(option) ? option : slot.defaultOption());
        }
        // a filled slot takes over the slots it excludes (they fall back to their defaults)
        for (Craftable.Slot slot : weapon.slots()) {
            if (slot.excludes().isEmpty() || slot.defaultOption().equals(slots.get(slot.id()))) continue;
            for (String other : slot.excludes()) weapon.slot(other).ifPresent(o -> slots.put(o.id(), o.defaultOption()));
        }
        List<Spray> sprays = new ArrayList<>();
        if (build != null) {
            SprayCatalog catalog = SprayCatalog.get();
            for (Spray spray : build.sprays()) {
                if (sprays.size() < catalog.limits().perWeapon && catalog.check(weapon, spray) == null) sprays.add(spray);
            }
        }
        return new Build(skin, slots, sprays);
    }

    /** The attachment fitted in a slot of a (completed) build, null for an empty slot or a weapon-specific part. */
    @Nullable
    public static CraftData.Attachment fitted(Build build, String slot) {
        String option = build.slots().get(slot);
        return option == null ? null : AttachmentCatalog.get().get(option);
    }

    // ---- names ----------------------------------------------------------------------------------------

    /** Name and description of an attachment option ("none" included) for the craft UI and tooltips. */
    public static Craftable.OptionInfo describeAttachment(Craftable.Slot slot, String option) {
        return describeAttachment(null, slot, option);
    }

    /**
     * Name and description of an attachment option of one weapon: "none" may have a name of the weapon's own
     * ({@code blockland.craft.none.<weapon>.<slot>}, e.g. the Thompson's compensator), else the slot's.
     */
    public static Craftable.OptionInfo describeAttachment(@Nullable Craftable weapon, Craftable.Slot slot, String option) {
        if (CraftData.NONE.equals(option)) {
            String own = weapon == null ? null : "blockland.craft.none." + weapon.id() + "." + slot.id();
            Component name = slot.noneName().isEmpty() ? Component.translatable("blockland.craft.none")
                    : own != null && Language.getInstance().has(own) ? Component.translatable(own)
                    : text("blockland.craft.none." + slot.id(), slot.noneName());
            return new Craftable.OptionInfo(name, Component.empty(), "common");
        }
        CraftData.Attachment attachment = AttachmentCatalog.get().get(option);
        if (attachment == null) return new Craftable.OptionInfo(Component.literal(option), Component.empty(), "common");
        return new Craftable.OptionInfo(text("blockland.attachment." + option, attachment.name),
                text("blockland.attachment." + option + ".desc", attachment.description), attachment.rarity);
    }

    /** A skin's display name: translated when the language has {@code blockland.skin.<weapon>.<skin>} or {@code blockland.skin.<skin>}. */
    public static Component skinName(Craftable weapon, CraftData.Skin skin) {
        String specific = "blockland.skin." + weapon.id() + "." + skin.id;
        if (Language.getInstance().has(specific)) return Component.translatable(specific);
        return text("blockland.skin." + skin.id, skin.name);
    }

    /** A translation when the language has the key, the data's own text otherwise. */
    public static Component text(String key, String fallback) {
        if (Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(fallback == null ? "" : fallback);
    }
}
