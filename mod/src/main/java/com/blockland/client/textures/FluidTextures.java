package com.blockland.client.textures;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The built-in client resource pack "BlockLand 材质" (rendering spec §15, {@code resourcepacks/blockland_textures}):
 * high-resolution water and lava textures (made by {@code tools/assets/gen_fluid_textures.py}). It follows the
 * 高清流体材质 setting ({@code GraphicsSettings.fluidTextures}): selected at startup when the setting wants it, added or
 * removed (with one resource reload) when the settings are committed. An ordinary pack otherwise: the player may move it
 * in the pack list.
 */
public final class FluidTextures {
    static final String ID = "blockland_textures";

    private static boolean startupChecked;

    private FluidTextures() {
    }

    public static void init(BusGroup modBus) {
        AddPackFindersEvent.getBus(modBus).addListener(event -> {
            if (event.getPackType() == PackType.CLIENT_RESOURCES) event.addRepositorySource(FluidTextures::discover);
        });
        Graphics.onSettingsCommitted(FluidTextures::apply);
    }

    private static boolean wanted() {
        return Graphics.settings().fluidTextures;
    }

    private static void discover(Consumer<Pack> out) {
        Pack pack = create();
        if (pack == null) return;
        out.accept(pack);
        if (!startupChecked) {
            // the first discovery runs before the options pick the packs to load: put the pack on (or off) their list
            startupChecked = true;
            try {
                var selected = Minecraft.getInstance().options.resourcePacks;
                boolean want = wanted();
                if (want && !selected.contains(ID)) selected.add(ID);
                else if (!want) selected.remove(ID);
            } catch (RuntimeException e) {
                BlockLand.LOGGER.warn("[BlockLand] Cannot preselect the texture pack: {}", e.toString());
            }
        }
    }

    @Nullable
    private static Pack create() {
        try {
            IModFileInfo file = ModList.get().getModFileById(BlockLand.MODID);
            if (file == null) return null;
            Path root = file.getFile().findResource("resourcepacks", ID);
            if (!Files.isDirectory(root)) {
                BlockLand.LOGGER.warn("[BlockLand] Texture pack not found at {}", root);
                return null;
            }
            PackLocationInfo info = new PackLocationInfo(ID, Component.translatable("blockland.graphics.textures_pack.title"),
                    PackSource.BUILT_IN, Optional.empty());
            return Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(root), PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(false, Pack.Position.TOP, false));
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot create the texture pack", e);
            return null;
        }
    }

    /** The settings were committed: select or drop the pack to match them (reloads resources when that changes it). */
    private static void apply() {
        Minecraft mc = Minecraft.getInstance();
        PackRepository repository = mc.getResourcePackRepository();
        boolean want = wanted();
        if (want == repository.getSelectedIds().contains(ID)) return;
        boolean changed = want ? repository.addPack(ID) : repository.removePack(ID);
        if (changed) mc.options.updateResourcePacks(repository);
    }
}
