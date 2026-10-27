package com.blockland;

import com.blockland.bomb.BombContent;
import com.blockland.client.BlockLandClient;
import com.blockland.effect.BLEffects;
import com.blockland.entity.BLEntities;
import com.blockland.gun.GunContent;
import com.blockland.melee.MeleeContent;
import com.blockland.equipment.EquipmentContent;
import com.blockland.network.BLNetwork;
import com.blockland.parachute.ParachuteContent;
import com.blockland.server.BlockLandServer;
import com.blockland.server.config.BlockLandConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Mod entry point. Only wires the independent modules together:
 * network (shared), server (game logic, runs on dedicated + integrated servers) and client (UI).
 */
@Mod(BlockLand.MODID)
public final class BlockLand {
    public static final String MODID = "blockland";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BlockLand(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, BlockLandConfig.SPEC);
        GunContent.register(context.getModBusGroup());
        MeleeContent.register(context.getModBusGroup());
        EquipmentContent.register(context.getModBusGroup());
        BombContent.register(context.getModBusGroup());
        ParachuteContent.register(context.getModBusGroup());
        com.blockland.supply.SupplyContent.register(context.getModBusGroup());
        com.blockland.armor.ArmorContent.register(context.getModBusGroup());
        com.blockland.battleroyale.BattleRoyaleContent.register(context.getModBusGroup());
        com.blockland.cargo.CargoContent.register(context.getModBusGroup());
        com.blockland.revive.ReviveContent.register(context.getModBusGroup());
        com.blockland.binoculars.BinocularsContent.register(context.getModBusGroup());
        com.blockland.vehicle.VehicleContent.register(context.getModBusGroup());
        com.blockland.decor.DecorContent.register(context.getModBusGroup());
        com.blockland.backpack.BackpackContent.register(context.getModBusGroup());
        BLEffects.register(context.getModBusGroup());
        BLEntities.register(context.getModBusGroup());
        BLNetwork.init();
        BlockLandServer.bootstrap();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BlockLandClient.init(context.getModBusGroup());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
