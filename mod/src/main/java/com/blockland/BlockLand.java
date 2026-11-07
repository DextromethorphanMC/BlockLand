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
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;

/**
 * Mod entry point. Only wires the independent modules together:
 * network (shared), server (game logic, runs on dedicated + integrated servers) and client (UI).
 */
@Mod(BlockLand.MODID)
public final class BlockLand {
    public static final String MODID = "blockland";
    public static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Composition root of the server half (server/, games/ and their mixins and data). The client jar is built without
     * it (build.gradle {@code clientJar}), so it is only ever named here, as a string.
     */
    private static final String SERVER_HALF = "com.blockland.server.BlockLandServer";
    /**
     * Composition root of the client half (client/, the client-only mixins and resources). The server jar is built
     * without it (build.gradle {@code jar}).
     */
    private static final String CLIENT_HALF = "com.blockland.client.BlockLandClient";

    public BlockLand(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) requireClientHalf();
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
        com.blockland.ambient.AmbientContent.register(context.getModBusGroup());
        com.blockland.backpack.BackpackContent.register(context.getModBusGroup());
        com.blockland.shark.SharkContent.register(context.getModBusGroup());
        BLEffects.register(context.getModBusGroup());
        BLEntities.register(context.getModBusGroup());
        BLNetwork.init();
        bootstrapServerHalf(context);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BlockLandClient.init(context.getModBusGroup());
        }
    }

    /** The server jar has no client half (build.gradle {@code jar}): on a client, say so before anything needs it. */
    private static void requireClientHalf() {
        try {
            Class.forName(CLIENT_HALF, false, BlockLand.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[BlockLand] This is the server jar (blockland-*-server.jar): "
                    + "players need blockland-*-client.jar", e);
        }
    }

    /**
     * Starts the server half when this jar has it. Players get the client jar, which has no game logic, anti-cheat or
     * server data to decompile; a world hosted from it (singleplayer, LAN) runs without BlockLand's services, and its
     * packet listeners stay the no-op defaults. A dedicated server must have it.
     */
    private static void bootstrapServerHalf(FMLJavaModLoadingContext context) {
        Class<?> serverHalf;
        try {
            serverHalf = Class.forName(SERVER_HALF, true, BlockLand.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
                throw new IllegalStateException("[BlockLand] This is the client jar (blockland-*-client.jar): "
                        + "a dedicated server needs blockland-*-server.jar", e);
            }
            LOGGER.info("[BlockLand] Client build: no server half, worlds hosted here run without BlockLand's game logic");
            return;
        }
        try {
            serverHalf.getMethod("bootstrap", FMLJavaModLoadingContext.class).invoke(null, context);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("[BlockLand] Server half failed to start", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[BlockLand] Server half has no bootstrap(FMLJavaModLoadingContext)", e);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
