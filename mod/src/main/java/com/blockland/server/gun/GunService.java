package com.blockland.server.gun;

import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunMath;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.network.BLNetwork;
import com.blockland.network.GunPacketListeners;
import com.blockland.network.packet.GunPackets;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server authority for guns: validates every shot (selected slot, fire rate, rounds left), traces it with the same
 * spread as the client, applies damage with falloff and headshots, runs timed reloads that consume ammo items and
 * applies gunsmith changes. Friendly fire and lobby protection stay with the existing damage rules.
 */
public final class GunService implements GunPacketListeners.Server {
    /** Shots that may arrive back to back (packets are batched per tick); the sustained rate is still capped. */
    private static final double RATE_BURST = 2.5;
    private static final float MAX_ANGLE_ERROR = 45;

    private final MinecraftServer server;
    private final Map<UUID, Shooter> shooters = new HashMap<>();

    private static final class Shooter {
        double shotCredit = 1;
        long creditNanos;
        float bloom;
        long bloomNanos;
        @Nullable
        Reload reload;

        float bloom(long now, GunData.Spread spread) {
            float decayed = bloom - spread.recovery * (now - bloomNanos) / 1e9f;
            return Math.max(0, decayed);
        }

        void addBloom(long now, GunData.Spread spread) {
            bloom = Math.min(spread.max, bloom(now, spread) + spread.perShot);
            bloomNanos = now;
        }
    }

    private record Reload(int slot, String gunId, int endTick, boolean empty) {
    }

    public GunService(MinecraftServer server) {
        this.server = server;
    }

    // ---- firing ---------------------------------------------------------------------------------

    @Override
    public void onFire(ServerPlayer player, GunPackets.C2SFire fire) {
        if (!player.isAlive() || player.isSpectator() || fire.slot() != player.getInventory().getSelectedSlot()) return;
        ItemStack stack = player.getMainHandItem();
        GunState state = GunContent.stateOf(stack);
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (state == null || resolved.isEmpty() || state.ammo() <= 0) return;
        GunStats stats = resolved.get();
        Shooter shooter = shooters.computeIfAbsent(player.getUUID(), id -> new Shooter());
        if (shooter.reload != null) return;
        long now = System.nanoTime();
        // refill one credit per fire interval: bursts of a couple of shots are fine, a faster sustained rate is not
        double refill = shooter.creditNanos == 0 ? RATE_BURST : (now - shooter.creditNanos) / (stats.gun().fireInterval() * 1e9);
        shooter.shotCredit = Math.min(RATE_BURST, shooter.shotCredit + refill);
        shooter.creditNanos = now;
        if (shooter.shotCredit < 1) return;
        shooter.shotCredit -= 1;
        GunContent.setState(stack, state.withAmmo(state.ammo() - 1));

        // trust the client's aim within reason (its rotation is a tick ahead of ours)
        float yaw = fire.yaw(), pitch = Mth.clamp(fire.pitch(), -90, 90);
        if (Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) > MAX_ANGLE_ERROR || Math.abs(pitch - player.getXRot()) > MAX_ANGLE_ERROR) {
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        float aim = Mth.clamp(fire.aim(), 0, 1);
        double moving = Math.sqrt(Mth.square(player.getX() - player.xo) + Mth.square(player.getZ() - player.zo));
        float spread = stats.spread(aim, moving, !player.onGround(), shooter.bloom(now, stats.gun().spread));
        shooter.addBloom(now, stats.gun().spread);

        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        for (int pellet = 0; pellet < Math.max(1, stats.ammo().pellets); pellet++) {
            Vec3 dir = GunMath.spreadDirection(yaw, pitch, spread, fire.seed(), pellet);
            Shot shot = trace(level, player, eye, dir, stats.gun().range);
            if (shot.entity != null) damage(level, player, stats, shot, eye);
            BLNetwork.sendToTracking(player, new GunPackets.S2CShot(player.getId(), shot.end.x, shot.end.y, shot.end.z, shot.hitBlock, shot.face));
        }
        SoundEvent sound = GunContent.sound(stats.gun().sounds.fire);
        if (sound != null) {
            // everyone around hears it; the shooter already played it locally
            level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), sound, SoundSource.PLAYERS, 4.0F,
                    0.96F + level.random.nextFloat() * 0.08F);
        }
    }

    /** Where one projectile ends: the first entity (not an ally) or block on its path ({@code face}: struck side). */
    private record Shot(Vec3 end, @Nullable Entity entity, boolean hitBlock, int face) {
    }

    private static Shot trace(ServerLevel level, ServerPlayer shooter, Vec3 from, Vec3 dir, double range) {
        Vec3 to = from.add(dir.scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3 end = hitBlock ? block.getLocation() : to;
        Entity best = null;
        Vec3 bestPoint = end;
        double bestDistance = from.distanceToSqr(end);
        AABB sweep = new AABB(from, end).inflate(1.0);
        for (Entity candidate : level.getEntities(shooter, sweep,
                e -> e.isPickable() && e.isAlive() && !e.isSpectator() && !shooter.isAlliedTo(e) && e != shooter.getVehicle())) {
            AABB box = candidate.getBoundingBox().inflate(Math.max(0.05, candidate.getPickRadius()));
            Optional<Vec3> point = box.contains(from) ? Optional.of(from) : box.clip(from, end);
            if (point.isEmpty()) continue;
            double distance = from.distanceToSqr(point.get());
            if (distance < bestDistance) {
                best = candidate;
                bestPoint = point.get();
                bestDistance = distance;
            }
        }
        return best != null ? new Shot(bestPoint, best, false, -1) : new Shot(end, null, hitBlock, hitBlock ? block.getDirection().ordinal() : -1);
    }

    private void damage(ServerLevel level, ServerPlayer shooter, GunStats stats, Shot shot, Vec3 eye) {
        Entity target = shot.entity;
        boolean head = GunMath.isHeadshot(target, shot.end.y);
        float amount = stats.damageAt(eye.distanceTo(shot.end)) * (head ? stats.ammo().headshotMultiplier : 1);
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(GunContent.BULLET),
                shooter, shooter);
        // bullets are fast: every one counts, not just the first in vanilla's hurt cooldown
        if (target instanceof LivingEntity living) living.invulnerableTime = 0;
        boolean wasSpectator = target.isSpectator();
        if (!target.hurtServer(level, source, amount)) return;
        // minigames cancel player deaths and turn the victim into a spectator instead
        boolean killed = !target.isAlive() || target instanceof LivingEntity living && living.isDeadOrDying()
                || !wasSpectator && target.isSpectator();
        GunPackets.HitKind kind = killed ? (head ? GunPackets.HitKind.HEAD_KILL : GunPackets.HitKind.KILL)
                : head ? GunPackets.HitKind.HEAD : GunPackets.HitKind.BODY;
        BLNetwork.sendTo(shooter, new GunPackets.S2CHit(kind, amount));
    }

    // ---- reloading ------------------------------------------------------------------------------

    @Override
    public void onReload(ServerPlayer player, int slot) {
        if (!player.isAlive() || slot != player.getInventory().getSelectedSlot()) return;
        ItemStack stack = player.getMainHandItem();
        GunState state = GunContent.stateOf(stack);
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (state == null || resolved.isEmpty()) return;
        GunStats stats = resolved.get();
        Shooter shooter = shooters.computeIfAbsent(player.getUUID(), id -> new Shooter());
        if (shooter.reload != null || state.ammo() >= stats.capacity()) return;
        if (!player.isCreative() && countAmmo(player, stats.ammo()) == 0) return;
        boolean empty = state.ammo() == 0;
        int ticks = Math.max(1, Math.round(stats.reloadTime(empty) * 20));
        shooter.reload = new Reload(slot, stats.gun().id, server.getTickCount() + ticks, empty);
    }

    public void tick() {
        if (shooters.isEmpty()) return;
        Iterator<Map.Entry<UUID, Shooter>> it = shooters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Shooter> entry = it.next();
            Reload reload = entry.getValue().reload;
            if (reload == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            ItemStack stack = player.getMainHandItem();
            boolean stillHolding = player.isAlive() && player.getInventory().getSelectedSlot() == reload.slot
                    && GunContent.gunOf(stack).map(g -> g.id.equals(reload.gunId)).orElse(false);
            if (!stillHolding) {
                entry.getValue().reload = null; // switching weapons cancels the reload, like in COD
                continue;
            }
            if (server.getTickCount() < reload.endTick) continue;
            entry.getValue().reload = null;
            finishReload(player, stack);
        }
    }

    private static void finishReload(ServerPlayer player, ItemStack stack) {
        GunState state = GunContent.stateOf(stack);
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (state == null || resolved.isEmpty()) return;
        GunStats stats = resolved.get();
        int needed = stats.capacity() - state.ammo();
        if (needed <= 0) return;
        int taken = player.isCreative() ? needed : takeAmmo(player, stats.ammo(), needed);
        if (taken > 0) GunContent.setState(stack, state.withAmmo(state.ammo() + taken));
    }

    private static int countAmmo(ServerPlayer player, GunData.Ammo ammo) {
        Item item = GunContent.ammoItem(ammo.id);
        return item == null ? 0 : player.getInventory().clearOrCountMatchingItems(s -> s.is(item), 0, player.inventoryMenu.getCraftSlots());
    }

    private static int takeAmmo(ServerPlayer player, GunData.Ammo ammo, int count) {
        Item item = GunContent.ammoItem(ammo.id);
        return item == null ? 0 : player.getInventory().clearOrCountMatchingItems(s -> s.is(item), count, player.inventoryMenu.getCraftSlots());
    }

    // ---- gunsmith -------------------------------------------------------------------------------

    @Override
    public void onGunsmith(ServerPlayer player, GunPackets.C2SGunsmith request) {
        if (request.slot() < 0 || request.slot() >= player.getInventory().getContainerSize()) return;
        ItemStack stack = player.getInventory().getItem(request.slot());
        GunState state = GunContent.stateOf(stack);
        GunData.Gun gun = GunContent.gunOf(stack).orElse(null);
        if (state == null || gun == null) return;
        GunCatalog catalog = GunCatalog.get();
        if (!gun.skins.containsKey(request.skin()) || !catalog.fits(gun, request.magazine())
                || !gun.sights.contains(request.sight()) || !gun.lasers.contains(request.laser())) {
            com.blockland.BlockLand.LOGGER.warn("[BlockLand] Gunsmith request rejected for {}: {}", player.getGameProfile().getName(), request);
            return;
        }
        Shooter shooter = shooters.get(player.getUUID());
        if (shooter != null && shooter.reload != null && shooter.reload.slot == request.slot()) shooter.reload = null;
        GunData.Magazine magazine = catalog.magazine(request.magazine());
        int ammo = state.ammo();
        if (magazine != null && ammo > magazine.capacity) {
            // a smaller magazine: the rounds that no longer fit go back to the inventory
            refund(player, gun, ammo - magazine.capacity);
            ammo = magazine.capacity;
        }
        GunContent.setState(stack, new GunState(request.skin(), request.magazine(), request.sight(), request.laser(), ammo));
        com.blockland.BlockLand.LOGGER.info("[BlockLand] Gunsmith: {} fitted {} to slot {}", player.getGameProfile().getName(), request, request.slot());
        SoundEvent attach = GunContent.sound("gun.attach");
        if (attach != null) player.playNotifySound(attach, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    private static void refund(ServerPlayer player, GunData.Gun gun, int rounds) {
        Item item = GunContent.ammoItem(gun.ammo);
        if (item == null || rounds <= 0) return;
        ItemStack loose = new ItemStack(item, rounds);
        if (!player.getInventory().add(loose)) player.drop(loose, false);
    }

    public void forget(ServerPlayer player) {
        shooters.remove(player.getUUID());
    }

    public void clear() {
        shooters.clear();
    }
}
