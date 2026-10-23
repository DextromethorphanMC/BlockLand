package com.blockland.craft;

import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A weapon the 工匠 (craft) system can customise — a gun, a knife or a piece of equipment (grenades, throwing knives, injectors). Implemented by the weapons' own data, so the craft
 * screen, the item state and the server validation work the same for every kind of weapon.
 */
public interface Craftable {
    enum Kind { GUN, KNIFE, EQUIPMENT }

    /**
     * A slot with its options resolved.
     *
     * @param mount       mount node in the weapon model where model attachments are drawn ("" = none)
     * @param options     allowed option ids, the default first
     * @param hides       weapon nodes hidden while the slot holds anything but its default
     * @param optionNodes option → weapon node shown only while that option is fitted (gun-specific parts such as
     *                    magazines); the nodes of the other options are hidden
     * @param noneName    display name of "none" in this slot ("" = the generic 无)
     */
    record Slot(String id, String mount, String defaultOption, List<String> options, List<String> hides,
                Map<String, String> optionNodes, String noneName) {
        public Slot {
            options = List.copyOf(options);
            hides = List.copyOf(hides);
            optionNodes = Map.copyOf(optionNodes);
        }

        public boolean allows(String option) {
            return options.contains(option);
        }
    }

    /** What the craft UI shows for one option. */
    record OptionInfo(Component name, Component description, String rarity) {
    }

    /** Weapon id, unique across guns and knives (also the item id). */
    String id();

    Kind kind();

    Component displayName();

    /** GLB path under {@code assets/blockland/}. */
    String model();

    /** Root node of the weapon itself in its model ({@code gun} / {@code knife}); previews draw that subtree. */
    String modelRoot();

    /** Slots in display order. */
    List<Slot> slots();

    /** Skins by id; always contains {@code default}. */
    Map<String, CraftData.Skin> skins();

    /** Name, description and rarity of an option of one of this weapon's slots. */
    OptionInfo describe(String slot, String option);

    default Optional<Slot> slot(String id) {
        return slots().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** The build a freshly handed out weapon has: default skin, every slot at its default. */
    default Build defaultBuild() {
        Map<String, String> slots = new LinkedHashMap<>();
        for (Slot slot : slots()) slots.put(slot.id(), slot.defaultOption());
        return new Build("default", slots);
    }
}
