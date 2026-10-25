package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.Textures;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Loads what the player's weapons look like before they are first drawn: once a second the inventory's craftable
 * weapons have their models (the build's own, a special skin's, the fitted attachments') loaded and their skin's texture
 * sets started in the background ({@link Textures#prefetch}), so switching to a weapon does not stall a frame decoding
 * its textures. Anything already known costs a map lookup.
 */
final class WeaponPrefetch {
    private static final int INTERVAL_TICKS = 20;
    /** Weapons and builds already prefetched since the last resource reload (cleared when the cache is). */
    private static final Set<String> done = new HashSet<>();
    private static int ticks;

    private WeaponPrefetch() {
    }

    static void tick() {
        if (++ticks % INTERVAL_TICKS != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Inventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            String id = CraftSession.heldWeaponId(stack);
            if (id == null) continue;
            Craftable weapon = CraftCatalog.get(id).orElse(null);
            if (weapon == null) continue;
            Build build = CraftCatalog.complete(weapon, CraftSession.heldBuild(stack));
            if (done.add(id + "|" + build)) prefetch(weapon, build);
        }
    }

    private static void prefetch(Craftable weapon, Build build) {
        // a model prefetches its own materials' sets as it loads
        GpuModel model = ModelCache.get(WeaponLook.modelOf(weapon, build));
        if (model == null) ModelCache.get(BlockLand.id(weapon.model()));
        for (ViewmodelRig.Attachment attachment : WeaponLook.attachments(weapon, build)) ModelCache.get(attachment.model());
        // the skin re-textures regions with sets of its own
        CraftData.Skin skin = weapon.skins().get(build.skin());
        if (skin != null && skin.regions != null) {
            for (CraftData.Layer layer : skin.regions.values()) if (layer != null) Textures.prefetch(layer.pbr);
        }
    }

    /** Resource reload: models and textures are loaded afresh. */
    static void clear() {
        done.clear();
    }
}
