package com.blockland.server.craft;

import com.blockland.BlockLand;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinKeys;
import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.melee.KnifeItem;
import com.blockland.melee.MeleeContent;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentItem;
import com.blockland.network.BLNetwork;
import com.blockland.network.CraftPacketListeners;
import com.blockland.network.packet.CraftPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Server authority for the 工匠 (craft) system: validates and saves builds (sent from the lobby or in game), fits them
 * to the weapons in a player's inventory and pushes the saved builds to the client. Weapon items only change here;
 * clients merely ask. Skins that must be owned (asset spec §30, {@link SkinKeys}) are checked against what the player
 * owns: a save with someone else's skin is refused, and a saved skin the player no longer owns (an expired entitlement)
 * is handed out as the weapon's default.
 */
public final class CraftService implements CraftPacketListeners.Server {
    /** Saves a player may send back to back; the bucket refills one save per second. */
    private static final double SAVE_BURST = 6;

    private final MinecraftServer server;
    private final BuildStore store;
    /** Whether a player owns an inventory item key (the skins' {@code skin:…} keys). */
    private final BiPredicate<UUID, String> owns;
    /** player → [credits, last refill nanos] */
    private final Map<UUID, double[]> saveCredits = new HashMap<>();
    /** player → the owned skin keys they were last sent (to resend only on a change) */
    private final Map<UUID, List<String>> sentUnlocked = new HashMap<>();

    /** @param owns whether a player owns an item key (an entitlement): decides the skins that must be owned */
    public CraftService(MinecraftServer server, BuildStore store, BiPredicate<UUID, String> owns) {
        this.server = server;
        this.store = store;
        this.owns = owns;
    }

    // ---- requests -------------------------------------------------------------------------------------

    @Override
    public void onRequest(UUID player) {
        sendBuilds(player);
    }

    @Override
    public void onSave(UUID player, CraftPackets.C2SSave save) {
        if (!takeCredit(player)) {
            send(player, "blockland.craft.error.too_fast");
            return;
        }
        Optional<Craftable> weapon = CraftCatalog.get(save.weaponId());
        if (weapon.isEmpty()) {
            send(player, "blockland.craft.error.unknown_weapon");
            return;
        }
        String problem = CraftCatalog.check(weapon.get(), save.build());
        if (problem != null) {
            BlockLand.LOGGER.warn("[BlockLand] Craft save rejected for {}: {} ({})", player, save, problem);
            send(player, "blockland.craft.error.invalid");
            return;
        }
        if (!SkinKeys.owned(weapon.get(), save.build().skin(), key -> owns.test(player, key))) {
            send(player, "blockland.craft.error.skin_locked");
            return;
        }
        Build build = CraftCatalog.complete(weapon.get(), save.build());
        // the default build needs no entry: weapons without one are handed out as they are
        store.save(player, weapon.get().id(), build.equals(weapon.get().defaultBuild()) ? null : build);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null && save.fitSlot() >= 0) fitSlot(online, save.fitSlot(), weapon.get(), build);
        send(player, "");
    }

    // ---- hooks for the rest of the server ----------------------------------------------------------

    /** Pushes the player's saved builds to their client (lobby or world). */
    public void sendBuilds(UUID player) {
        send(player, "");
    }

    /**
     * The player's entitlements may have changed (a purchase, a grant, an expiry): their client learns about skins they
     * now own (or no longer do).
     */
    public void entitlementsChanged(UUID player) {
        List<String> sent = sentUnlocked.get(player);
        if (sent != null && !sent.equals(unlocked(player))) send(player, "");
    }

    /**
     * Fits the player's saved builds to every craftable weapon in their inventory. Call after weapons were handed out
     * (match kits); weapons without a saved build keep their defaults.
     */
    public void applyBuilds(ServerPlayer player) {
        Map<String, Build> saved = store.builds(player.getUUID());
        if (saved.isEmpty()) return;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            Craftable weapon = weaponOf(stack);
            if (weapon == null) continue;
            Build build = saved.get(weapon.id());
            if (build == null) continue;
            // a skin the player no longer owns (an expired entitlement) is not handed out
            if (!SkinKeys.owned(weapon, build.skin(), key -> owns.test(player.getUUID(), key))) build = build.withSkin("default");
            fit(player, stack, weapon, CraftCatalog.complete(weapon, build));
        }
    }

    public void forget(UUID player) {
        saveCredits.remove(player);
        sentUnlocked.remove(player);
    }

    public void clear() {
        saveCredits.clear();
        sentUnlocked.clear();
    }

    // ---- internals ------------------------------------------------------------------------------------

    private void send(UUID player, String error) {
        Map<String, Build> builds = new LinkedHashMap<>(store.builds(player));
        while (builds.size() > CraftPackets.MAX_BUILDS) builds.remove(builds.keySet().iterator().next());
        List<String> unlocked = unlocked(player);
        sentUnlocked.put(player, unlocked);
        CraftPackets.S2CBuilds packet = new CraftPackets.S2CBuilds(builds, error, unlocked);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) BLNetwork.sendTo(online, packet);
        else BLNetwork.sendTo(player, packet);
    }

    /** The keys of skins that must be owned which the player owns (every key such a skin answers to). */
    private List<String> unlocked(UUID player) {
        List<String> keys = new ArrayList<>();
        for (String key : SkinKeys.allKeys()) {
            if (owns.test(player, key)) keys.add(key);
        }
        return keys;
    }

    private boolean takeCredit(UUID player) {
        long now = System.nanoTime();
        double[] credit = saveCredits.computeIfAbsent(player, id -> new double[]{SAVE_BURST, now});
        credit[0] = Math.min(SAVE_BURST, credit[0] + (now - credit[1]) / 1e9);
        credit[1] = now;
        if (credit[0] < 1) return false;
        credit[0] -= 1;
        return true;
    }

    /** A save made while holding the weapon fits it right away (only to that kind of weapon in that slot). */
    private static void fitSlot(ServerPlayer player, int slot, Craftable weapon, Build build) {
        Inventory inventory = player.getInventory();
        if (slot >= inventory.getContainerSize()) return;
        ItemStack stack = inventory.getItem(slot);
        Craftable held = weaponOf(stack);
        if (held == null || !held.id().equals(weapon.id())) return;
        fit(player, stack, weapon, build);
        SoundEvent attach = GunContent.sound("gun.attach");
        if (attach != null) player.playNotifySound(attach, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** Writes a valid, complete build into a weapon stack. Guns keep their rounds; a smaller magazine refunds the rest. */
    private static void fit(ServerPlayer player, ItemStack stack, Craftable weapon, Build build) {
        if (stack.getItem() instanceof GunItem && weapon instanceof GunData.Gun gun) {
            GunState state = GunContent.stateOf(stack);
            if (state == null) return;
            String magazineId = build.option(GunData.Gun.MAGAZINE_SLOT, gun.defaultMagazine());
            GunData.Magazine magazine = GunCatalog.get().magazine(GunCatalog.get().fits(gun, magazineId) ? magazineId : gun.defaultMagazine());
            int ammo = state.ammo();
            if (magazine != null && ammo > magazine.capacity) {
                refund(player, gun, ammo - magazine.capacity);
                ammo = magazine.capacity;
            }
            GunContent.setState(stack, new GunState(build, ammo));
        } else if (stack.getItem() instanceof KnifeItem) {
            MeleeContent.setBuild(stack, build);
        } else if (stack.getItem() instanceof EquipmentItem) {
            EquipmentContent.setBuild(stack, build);
        }
    }

    @Nullable
    private static Craftable weaponOf(ItemStack stack) {
        if (stack.getItem() instanceof GunItem gun) return CraftCatalog.get(gun.gunId()).orElse(null);
        if (stack.getItem() instanceof KnifeItem knife) return CraftCatalog.get(knife.knifeId()).orElse(null);
        if (stack.getItem() instanceof EquipmentItem item) return CraftCatalog.get(item.equipmentId()).orElse(null);
        return null;
    }

    private static void refund(ServerPlayer player, GunData.Gun gun, int rounds) {
        Item item = GunContent.ammoItem(gun.ammo);
        if (item == null || rounds <= 0) return;
        ItemStack loose = new ItemStack(item, rounds);
        if (!player.getInventory().add(loose)) player.drop(loose, false);
    }
}
