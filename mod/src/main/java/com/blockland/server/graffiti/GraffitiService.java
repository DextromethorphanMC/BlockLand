package com.blockland.server.graffiti;

import com.blockland.common.Sounds;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.graffiti.GraffitiData;
import com.blockland.movement.action.BodyActions;
import com.blockland.network.BLNetwork;
import com.blockland.network.GraffitiPacketListeners;
import com.blockland.network.packet.GraffitiPackets;
import com.blockland.server.profile.Cosmetics;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Server authority for wall graffiti (喷漆). A player asks to spray one of their four slots; this service checks
 * everything — the slot holds a graffiti they own (a free one or an entitlement), they are alive and playing in a world
 * whose mode allows graffiti, the cooldown is over, there is a surface within reach (or room in the air for floating
 * pieces) — then puts the piece up, tells the players in that world and plays the spray. Pieces live per world: each
 * player keeps at most {@code maxPerPlayer} (the oldest goes), a world at most {@code maxPerLevel}; whoever arrives in a
 * world later is sent what is up there, and an arena's pieces go when the arena is released.
 */
public final class GraffitiService implements GraffitiPacketListeners.Server {
    /** Players this far from a piece are not told about it (arenas share one dimension, far apart). */
    private static final double SEND_DISTANCE = 384;
    private static final int SYNC_TICKS = 20;

    private final MinecraftServer server;
    /** Whether graffiti may be sprayed where the player is (their match's mode allows it). */
    private final Predicate<ServerPlayer> allowed;
    /** Whether the player owns an inventory key (an active entitlement). */
    private final BiPredicate<UUID, String> owns;
    private final Map<ResourceKey<Level>, List<GraffitiPackets.Placement>> byLevel = new HashMap<>();
    private final Map<UUID, Integer> lastSpray = new HashMap<>();
    /** The world each player was last sent the pieces of. */
    private final Map<UUID, ResourceKey<Level>> synced = new HashMap<>();
    private int nextId = 1;
    private int tickCounter;

    public GraffitiService(MinecraftServer server, Predicate<ServerPlayer> allowed, BiPredicate<UUID, String> owns) {
        this.server = server;
        this.allowed = allowed;
        this.owns = owns;
    }

    // ---- spraying -------------------------------------------------------------------------------------

    @Override
    public void onSpray(ServerPlayer player, int slot) {
        if (!player.isAlive() || player.isSpectator() || slot < 1 || slot > GraffitiCatalog.SLOTS) return;
        if (!allowed.test(player)) {
            refuse(player, Component.translatable("blockland.graffiti.error.not_allowed"));
            return;
        }
        GraffitiCatalog catalog = GraffitiCatalog.get();
        GraffitiData.Graffiti graffiti = inSlot(player.getUUID(), slot);
        if (graffiti == null) {
            refuse(player, Component.translatable("blockland.graffiti.error.empty"));
            return;
        }
        if (!graffiti.free && !owns.test(player.getUUID(), graffiti.key())) {
            refuse(player, Component.translatable("blockland.graffiti.error.not_owned"));
            return;
        }
        GraffitiData.Rules rules = catalog.rules();
        int now = server.getTickCount();
        Integer last = lastSpray.get(player.getUUID());
        int cooldown = Math.round(rules.cooldownSeconds * 20);
        if (last != null && now - last < cooldown) {
            refuse(player, Component.translatable("blockland.graffiti.error.cooldown", (cooldown - (now - last) + 19) / 20));
            return;
        }
        GraffitiPackets.Placement placement = place(player, graffiti, rules);
        if (placement == null) {
            refuse(player, Component.translatable("blockland.graffiti.error.no_surface"));
            return;
        }
        lastSpray.put(player.getUUID(), now);
        ServerLevel level = player.level();
        List<GraffitiPackets.Placement> pieces = byLevel.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
        List<Integer> removed = new ArrayList<>();
        // the owner's oldest beyond their share, then the world's oldest beyond its share
        int own = 0;
        for (GraffitiPackets.Placement p : pieces) if (p.owner().equals(player.getUUID())) own++;
        for (Iterator<GraffitiPackets.Placement> it = pieces.iterator(); it.hasNext() && own >= rules.maxPerPlayer; ) {
            GraffitiPackets.Placement p = it.next();
            if (!p.owner().equals(player.getUUID())) continue;
            it.remove();
            removed.add(p.id());
            own--;
        }
        while (pieces.size() >= rules.maxPerLevel) removed.add(pieces.remove(0).id());
        pieces.add(placement);
        if (!removed.isEmpty()) send(level, new Vec3(placement.x(), placement.y(), placement.z()), new GraffitiPackets.S2CRemove(removed));
        send(level, new Vec3(placement.x(), placement.y(), placement.z()), new GraffitiPackets.S2CPlace(placement));
        ResourceLocation sound = ResourceLocation.tryParse(rules.sound);
        if (sound != null) {
            level.playSound(null, player.getX(), player.getEyeY() - 0.3, player.getZ(), Sounds.byId(sound), SoundSource.PLAYERS,
                    0.9f, 0.92f + level.random.nextFloat() * 0.16f);
        }
        BLNetwork.sendTo(player, new GraffitiPackets.S2CSlots(slots(player.getUUID())));
    }

    /**
     * Where the piece goes. Pieces on a surface: the face the player looks at within range, the picture upright on walls
     * and turned to the player on floors and ceilings. Floating pieces: standing on the floor looked at, in front of a
     * wall, or in the air at arm's length when nothing is in reach — as long as there is room for them.
     */
    @Nullable
    private GraffitiPackets.Placement place(ServerPlayer player, GraffitiData.Graffiti graffiti, GraffitiData.Rules rules) {
        ServerLevel level = player.level();
        Vec3 eye = BodyActions.eyePosition(player), look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(rules.range)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        float size = Math.max(0.2f, graffiti.size);
        long time = level.getGameTime();
        if (!graffiti.floating) {
            if (hit.getType() != HitResult.Type.BLOCK) return null;
            Direction face = hit.getDirection();
            Vec3 up;
            if (face.getAxis() == Direction.Axis.Y) {
                // on a floor or a ceiling the top of the picture points away from the player
                Vec3 flat = new Vec3(look.x, 0, look.z);
                up = flat.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, player.getYRot()) : flat.normalize();
                if (face == Direction.DOWN) up = up.scale(-1);
            } else {
                up = new Vec3(0, 1, 0);
            }
            Vec3 at = hit.getLocation();
            return new GraffitiPackets.Placement(nextId++, graffiti.id, player.getUUID(), at.x, at.y, at.z, face.getStepX(),
                    face.getStepY(), face.getStepZ(), (float) up.x, (float) up.y, (float) up.z, size, time, false);
        }
        Vec3 centre;
        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3 at = hit.getLocation();
            centre = hit.getDirection() == Direction.UP ? at.add(0, graffiti.lift + size / 2, 0)
                    : at.add(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(0.7));
        } else {
            centre = eye.add(look.scale(Math.min(rules.range, 3.0)));
        }
        // room for it: a box a little smaller than the piece must be free of blocks
        double half = size * 0.35;
        if (!level.noCollision(new AABB(centre.subtract(half, half, half), centre.add(half, half, half)))) return null;
        return new GraffitiPackets.Placement(nextId++, graffiti.id, player.getUUID(), centre.x, centre.y, centre.z, 0, 0, 0,
                0, 1, 0, size, time, true);
    }

    // ---- slots ----------------------------------------------------------------------------------------

    /** The graffiti in a player's slot: what they put there, else the slot's free default. */
    @Nullable
    private GraffitiData.Graffiti inSlot(UUID player, int slot) {
        GraffitiCatalog catalog = GraffitiCatalog.get();
        String key = Cosmetics.get().get(player, GraffitiCatalog.slot(slot));
        if (key != null) return catalog.byKey(key);
        List<GraffitiData.Graffiti> free = catalog.free();
        return slot <= free.size() ? free.get(slot - 1) : null;
    }

    private List<String> slots(UUID player) {
        List<String> ids = new ArrayList<>(GraffitiCatalog.SLOTS);
        for (int i = 1; i <= GraffitiCatalog.SLOTS; i++) {
            GraffitiData.Graffiti g = inSlot(player, i);
            ids.add(g == null ? "" : g.id);
        }
        return ids;
    }

    // ---- keeping clients up to date -------------------------------------------------------------------

    /** Once a second: players who arrived in another world get what is up there (and their slots). */
    public void tick() {
        if (++tickCounter % SYNC_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> level = player.level().dimension();
            if (level.equals(synced.get(player.getUUID()))) continue;
            synced.put(player.getUUID(), level);
            List<GraffitiPackets.Placement> near = new ArrayList<>();
            for (GraffitiPackets.Placement p : byLevel.getOrDefault(level, List.of())) {
                if (player.distanceToSqr(p.x(), p.y(), p.z()) < SEND_DISTANCE * SEND_DISTANCE) near.add(p);
            }
            BLNetwork.sendTo(player, new GraffitiPackets.S2CSync(near));
            BLNetwork.sendTo(player, new GraffitiPackets.S2CSlots(slots(player.getUUID())));
        }
    }

    /** An area is wiped (an arena released): its pieces go with it. */
    public void clearArea(ServerLevel level, BoundingBox box) {
        List<GraffitiPackets.Placement> pieces = byLevel.get(level.dimension());
        if (pieces == null || pieces.isEmpty()) return;
        List<Integer> removed = new ArrayList<>();
        pieces.removeIf(p -> {
            boolean inside = box.isInside((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
            if (inside) removed.add(p.id());
            return inside;
        });
        if (removed.isEmpty()) return;
        GraffitiPackets.S2CRemove packet = new GraffitiPackets.S2CRemove(removed);
        for (ServerPlayer player : level.players()) BLNetwork.sendTo(player, packet);
    }

    private void send(ServerLevel level, Vec3 at, Object packet) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) < SEND_DISTANCE * SEND_DISTANCE) BLNetwork.sendTo(player, packet);
        }
    }

    private static void refuse(ServerPlayer player, Component reason) {
        PlayerUtil.actionBar(player, reason.copy().withStyle(ChatFormatting.RED));
    }

    /** The player left the world (lobby, disconnect): the next world they enter is sent in full. */
    public void forget(ServerPlayer player) {
        synced.remove(player.getUUID());
        lastSpray.remove(player.getUUID());
    }

    public void clear() {
        byLevel.clear();
        synced.clear();
        lastSpray.clear();
    }
}
