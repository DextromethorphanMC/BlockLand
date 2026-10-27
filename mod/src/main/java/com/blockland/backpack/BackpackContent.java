package com.blockland.backpack;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the backpacks (UI spec §2): one item per entry of {@code backpack/backpacks.json} and a creative tab —
 * independent of the other modules. The backpack slot itself is the inventory menu's ({@link BackpackMenus}).
 */
public final class BackpackContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    public static final List<RegistryObject<Item>> BACKPACKS = new ArrayList<>();

    static {
        for (BackpackData.Pack pack : BackpackData.packs()) {
            BACKPACKS.add(ITEMS.register(pack.id(), () -> new BackpackItem(pack, new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(pack.id())))
                    .stacksTo(1))));
        }
    }

    private BackpackContent() {
    }

    public static void register(BusGroup modBus) {
        TABS.register("backpacks", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.backpacks"))
                .icon(() -> BACKPACKS.isEmpty() ? ItemStack.EMPTY : new ItemStack(BACKPACKS.get(BACKPACKS.size() - 1).get()))
                .displayItems((params, output) -> BACKPACKS.forEach(item -> output.accept(item.get())))
                .build());
        ITEMS.register(modBus);
        TABS.register(modBus);
        // vanilla copies the inventory to the respawned player when it is kept, but not the body slot the backpack is in
        PlayerEvent.Clone.BUS.addListener(event -> {
            boolean kept = !event.isWasDeath() || event.getOriginal().isSpectator()
                    || event.getEntity().level() instanceof ServerLevel level && level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
            ItemStack worn = Backpacks.worn(event.getOriginal());
            if (kept && !worn.isEmpty() && Backpacks.worn(event.getEntity()).isEmpty()) {
                Backpacks.wear(event.getEntity(), worn.copy());
            }
        });
    }
}
