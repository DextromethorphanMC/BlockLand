package com.blockland.client.ctm;

import com.blockland.BlockLand;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The built-in client pack "BlockLand 连接玻璃" ({@code resourcepacks/blockland_connected}, rendering spec §25): vanilla
 * glass with connected textures, an example of the system on a vanilla block. Off by default — the player turns it on
 * in the resource pack list like any other pack.
 */
final class ConnectedPack {
    static final String ID = "blockland_connected";

    private ConnectedPack() {
    }

    static void init(BusGroup modBus) {
        AddPackFindersEvent.getBus(modBus).addListener(event -> {
            if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
            event.addRepositorySource(out -> {
                Pack pack = create();
                if (pack != null) out.accept(pack);
            });
        });
    }

    private static Pack create() {
        try {
            IModFileInfo file = ModList.get().getModFileById(BlockLand.MODID);
            if (file == null) return null;
            Path root = file.getFile().findResource("resourcepacks", ID);
            if (!Files.isDirectory(root)) {
                BlockLand.LOGGER.warn("[BlockLand] Connected glass pack not found at {}", root);
                return null;
            }
            PackLocationInfo info = new PackLocationInfo(ID, Component.translatable("blockland.graphics.connected_pack.title"),
                    PackSource.BUILT_IN, Optional.empty());
            return Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(root), PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(false, Pack.Position.TOP, false));
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot create the connected glass pack", e);
            return null;
        }
    }
}
