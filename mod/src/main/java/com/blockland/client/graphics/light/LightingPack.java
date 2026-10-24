package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
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

/**
 * The built-in client resource pack "BlockLand 光照" (rendering spec §3, {@code resourcepacks/blockland_lighting}): it
 * overrides vanilla's terrain, entity and transparency shaders. It follows the 世界光照 setting: selected at startup when
 * the setting wants it, added or removed (with one resource reload) when the settings are committed. It is an ordinary,
 * non-required pack, so if its shaders ever fail to load, vanilla's recovery drops it like any broken pack; the status
 * then says the pack is missing.
 */
final class LightingPack {
    static final String ID = "blockland_lighting";

    private static boolean startupChecked;
    /** The pack was selected after the last reload. */
    private static boolean loaded;
    /** Wanted and selected before the last reload but not after it: a failed load dropped it (or the player did). */
    private static boolean dropped;
    private static boolean toasted;

    private LightingPack() {
    }

    static void register(BusGroup modBus) {
        AddPackFindersEvent.getBus(modBus).addListener(event -> {
            if (event.getPackType() == PackType.CLIENT_RESOURCES) event.addRepositorySource(LightingPack::discover);
        });
    }

    /** The setting wants the pack (graphics on and world lighting on). */
    static boolean wanted() {
        GraphicsSettings settings = ClientSettings.get().graphics;
        return settings.enabled && settings.worldLighting;
    }

    static boolean loaded() {
        return loaded;
    }

    static boolean dropped() {
        return dropped;
    }

    /** The selection differs from the setting: committing the settings reloads the resources. */
    static boolean reloadPending() {
        return wanted() != selected();
    }

    private static void discover(java.util.function.Consumer<Pack> out) {
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
                BlockLand.LOGGER.warn("[BlockLand] Cannot preselect the lighting pack: {}", e.toString());
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
                BlockLand.LOGGER.warn("[BlockLand] Lighting pack not found at {}", root);
                return null;
            }
            PackLocationInfo info = new PackLocationInfo(ID, Component.translatable("blockland.graphics.pack.title"),
                    PackSource.BUILT_IN, Optional.empty());
            return Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(root), PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(false, Pack.Position.TOP, false));
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot create the lighting pack", e);
            return null;
        }
    }

    /** The settings were committed: select or drop the pack to match them (reloads resources when that changes it). */
    static void apply() {
        Minecraft mc = Minecraft.getInstance();
        PackRepository repository = mc.getResourcePackRepository();
        boolean want = wanted();
        if (want == selected()) return;
        boolean changed = want ? repository.addPack(ID) : repository.removePack(ID);
        dropped = false;
        toasted = false;
        if (changed) mc.options.updateResourcePacks(repository);
    }

    /** After a resource reload: remember whether the pack made it, tell the player once if it was dropped. */
    static void afterReload() {
        boolean before = loaded;
        loaded = selected();
        if (loaded) {
            dropped = false;
        } else if (before && wanted()) {
            dropped = true;
            if (!toasted) {
                toasted = true;
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                        Component.translatable("blockland.graphics.toast.lighting_dropped"),
                        Component.translatable("blockland.graphics.toast.lighting_dropped.detail")));
            }
        }
        Graphics.status().reloadPending = reloadPending();
    }

    private static boolean selected() {
        return Minecraft.getInstance().getResourcePackRepository().getSelectedIds().contains(ID);
    }
}
