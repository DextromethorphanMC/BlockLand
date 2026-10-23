package com.blockland.gun;

import com.blockland.craft.CraftCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
        String damage = ammo.damage + (ammo.pellets > 1 ? " ×" + ammo.pellets : "");
        tooltip.accept(Component.translatable("blockland.ammo.tooltip.damage", Component.literal(damage).withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("blockland.ammo.tooltip.headshot", ammo.headshotMultiplier).withStyle(ChatFormatting.GRAY)));
        if (!ammo.magazines.isEmpty()) {
            MutableComponent names = Component.empty().withStyle(ChatFormatting.WHITE);
            boolean first = true;
            for (String id : ammo.magazines) {
                GunData.Magazine magazine = GunCatalog.get().magazine(id);
                if (magazine == null) continue;
                if (!first) names.append(Component.translatable("blockland.list.separator"));
                names.append(CraftCatalog.text("blockland.magazine." + id, magazine.name));
                first = false;
            }
            tooltip.accept(Component.translatable("blockland.ammo.tooltip.magazines", names).withStyle(ChatFormatting.GRAY));
        }
    }
}
