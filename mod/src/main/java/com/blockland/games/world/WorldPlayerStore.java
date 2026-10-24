package com.blockland.games.world;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where each player stood in an open world and what they carried, kept on the server
 * ({@code <save>/blockland/world/<mode>/<uuid>.dat}): the player's own save data (inventory, armour, health, food,
 * experience, effects…) plus the dimension and position. Written when they leave, read on their next visit. The game
 * mode is the mode's setting, never the saved one.
 */
final class WorldPlayerStore {
    /** What is restored; the rest of the tag is the entity's own data. */
    record Saved(CompoundTag data, ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {
    }

    private final MinecraftServer server;
    private final Path dir;

    WorldPlayerStore(MinecraftServer server, String modeId) {
        this.server = server;
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("blockland").resolve("world").resolve(safe(modeId));
    }

    boolean has(UUID player) {
        return Files.isRegularFile(file(player));
    }

    void save(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        player.saveWithoutId(output);
        CompoundTag root = new CompoundTag();
        root.put("player", output.buildResult());
        root.putString("dimension", player.level().dimension().location().toString());
        root.putDouble("x", player.getX());
        root.putDouble("y", player.getY());
        root.putDouble("z", player.getZ());
        root.putFloat("yaw", player.getYRot());
        root.putFloat("pitch", player.getXRot());
        Path file = file(player.getUUID());
        try {
            Files.createDirectories(dir);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Could not save open world state of {}", player.getGameProfile().getName(), e);
        }
    }

    @Nullable
    Saved load(UUID player) {
        Path file = file(player);
        if (!Files.isRegularFile(file)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ResourceLocation dimension = ResourceLocation.tryParse(root.getStringOr("dimension", ""));
            return new Saved(root.getCompoundOrEmpty("player"),
                    dimension == null ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, dimension),
                    new Vec3(root.getDoubleOr("x", 0), root.getDoubleOr("y", 0), root.getDoubleOr("z", 0)),
                    root.getFloatOr("yaw", 0), root.getFloatOr("pitch", 0));
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Could not read open world state {}", file, e);
            return null;
        }
    }

    /**
     * Puts the saved data back into a player who was just reset for the world (game mode already set). The client is
     * told what the load changed behind its back (selected slot, inventory, effects, abilities).
     */
    void restore(ServerPlayer player, Saved saved) {
        CompoundTag data = saved.data().copy();
        // the mode decides the game mode; a saved one must not override it
        data.remove("playerGameType");
        data.remove("previousPlayerGameType");
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), data));
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        player.inventoryMenu.sendAllDataToRemote();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
        }
        player.onUpdateAbilities();
    }

    /** The saved level, if it still exists. */
    @Nullable
    ServerLevel level(Saved saved) {
        return server.getLevel(saved.dimension());
    }

    private Path file(UUID player) {
        return dir.resolve(player + ".dat");
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
