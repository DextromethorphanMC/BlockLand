package com.blockland.gun;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Loose rounds of one ammo type; reloading takes them from the inventory. */
public class AmmoItem extends Item {
    private final String ammoId;

    public AmmoItem(String ammoId, Properties properties) {
        super(properties);
        this.ammoId = ammoId;
    }

    public String ammoId() {
        return ammoId;
    }

    @Override
    public Component getName(ItemStack stack) {
        GunData.Ammo ammo = GunCatalog.get().ammo(ammoId);
        return ammo == null ? super.getName(stack) : Component.literal(ammo.name);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
                                TooltipFlag flag) {
        GunData.Ammo ammo = GunCatalog.get().ammo(ammoId);
        if (ammo == null) return;
        tooltip.accept(Component.literal("§7伤害 §f" + ammo.damage + (ammo.pellets > 1 ? " ×" + ammo.pellets : "")
                + " §8· §7爆头 ×" + ammo.headshotMultiplier));
        if (!ammo.magazines.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String id : ammo.magazines) {
                GunData.Magazine magazine = GunCatalog.get().magazine(id);
                if (magazine == null) continue;
                if (!names.isEmpty()) names.append("、");
                names.append(magazine.name);
            }
            tooltip.accept(Component.literal("§7适配弹匣：§f" + names));
        }
    }
}
