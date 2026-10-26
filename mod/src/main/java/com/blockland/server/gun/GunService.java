package com.blockland.server.gun;

import com.blockland.server.spectate.Watchers;
import com.blockland.network.packet.ViewmodelPackets;
import com.blockland.BlockLand;
import com.blockland.combat.Ballistics;
import com.blockland.combat.HitRecords;
import com.blockland.combat.HitShapes;
import com.blockland.combat.HurtCheck;
import com.blockland.server.anticheat.api.AntiCheatSignals;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.server.combat.HitHistory;
import com.blockland.effect.ActionLocks;
import com.blockland.entity.BLEntities;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunGrenade;
import com.blockland.gun.GunMath;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.movement.action.BodyActions;
import com.blockland.network.BLNetwork;
import com.blockland.network.GunPacketListeners;
import com.blockland.network.packet.GunPackets;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server authority for guns: validates every shot (selected slot, fire rate, rounds left), traces it with the same
 * spread as the client, applies damage with falloff and headshots (fitted attachments change range, damage and the
 * sound) and runs timed reloads that consume ammo items. A dual gun's volley is two rounds at once and costs more of the
 * fire rate (asset spec §26); a tube magazine reloads a shell at a time and a shot stops that reload (§25). Builds are
 * fitted by the craft service. Friendly fire and lobby protection stay with the existing damage rules.
 */
public final class GunService implements GunPacketListeners.Server {
    /** Shots that may arrive back to back (packets are batched per tick); the sustained rate is still capped. */
    private static final double RATE_BURST = 2.5;
    private static final float MAX_ANGLE_ERROR = 45;
    /**
     * Aiming is timed here, not trusted: the sights rise this much faster than the gun's ADS time allows (and fall this
     * much slower) before a shot's claimed aim is capped — room for packet batching and the client's frame clock.
     */
    private static final float AIM_RATE_TOLERANCE = 1.5f;
    private static final float AIM_HEADROOM = 0.15f;
    /**
     * A shot that arrives this close before the next shell of a shell reload is due counts that shell in first: the
     * client counts a round when its clip ends, our tick may come a little after.
     */
    private static final long SHELL_EARLY_NANOS = 120_000_000L;
    /**
     * Lag compensation: a client draws the others up to this many ticks behind the latest position it was sent
     * (position updates every 2-3 ticks, interpolated over 3) on top of the round trip; the shooter's claimed target is
     * tried from the round trip back to this many ticks more.
     */
    private static final int VIEW_DELAY_TICKS = 6;

    private final MinecraftServer server;
    private final Map<UUID, Shooter> shooters = new HashMap<>();
    @Nullable
    private HitHistory history;
    /** Players whose reloads take no ammo items (creative, or a practice mode's infinite ammo). */
    private Predicate<ServerPlayer> infiniteAmmo = player -> false;

    private static final class Shooter {
        double shotCredit = 1;
        long creditNanos;
        float bloom;
        long bloomNanos;
        @Nullable
        Reload reload;
        // aiming down sights, as last reported (C2SAim)
        boolean aiming;
        int aimSlot = -1;
        long aimChanged;
        /** How far up the sights could be when {@link #aiming} last changed. */
        float aimFrom;

        /** How far up the sights of the gun in {@code slot} can be now, at most. */
        float plausibleAim(long now, int slot, float adsTime) {
            if (slot != aimSlot) return 0;
            float elapsed = (now - aimChanged) / 1e9f / Math.max(0.05f, adsTime);
            float value = aiming ? aimFrom + elapsed * AIM_RATE_TOLERANCE : aimFrom - elapsed / AIM_RATE_TOLERANCE;
            return Mth.clamp(value, 0, 1);
        }

        float bloom(long now, GunData.Spread spread) {
            float decayed = bloom - spread.recovery * (now - bloomNanos) / 1e9f;
            return Math.max(0, decayed);
        }

        void addBloom(long now, GunData.Spread spread) {
            bloom = Math.min(spread.max, bloom(now, spread) + spread.perShot);
            bloomNanos = now;
        }
    }

    /**
     * A running reload: a magazine swap ends at {@code endTick}; a shell reload ({@code shells}) puts one round in at
     * {@code nextShell} and every {@code shellNanos} after it while there is room and ammo.
     */
    private static final class Reload {
        final int slot;
        final String gunId;
        final int endTick;
        final boolean shells;
        long nextShell;
        final long shellNanos;

        Reload(int slot, String gunId, int endTick, boolean shells, long nextShell, long shellNanos) {
            this.slot = slot;
            this.gunId = gunId;
            this.endTick = endTick;
            this.shells = shells;
            this.nextShell = nextShell;
            this.shellNanos = shellNanos;
        }
    }

    public GunService(MinecraftServer server) {
        this.server = server;
    }

    /** Who reloads without using ammo items besides creative players (the composition root asks the player's match). */
    /** Where entities stood over the last second: a shot is checked against the world as its shooter saw it. */
    public void setHistory(HitHistory history) {
        this.history = history;
    }

    public void setInfiniteAmmo(Predicate<ServerPlayer> infiniteAmmo) {
        this.infiniteAmmo = infiniteAmmo;
    }

    private boolean unlimited(ServerPlayer player) {
        return player.isCreative() || infiniteAmmo.test(player);
    }

    // ---- firing ---------------------------------------------------------------------------------

    @Override
    public void onFire(ServerPlayer player, GunPackets.C2SFire fire) {
        if (!player.isAlive() || player.isSpectator() || fire.slot() != player.getInventory().getSelectedSlot()) return;
        if (ActionLocks.isLocked(player)) return;
        ItemStack stack = player.getMainHandItem();
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (resolved.isEmpty()) return;
        GunStats stats = resolved.get();
        GunData.Gun gun = stats.gun();
        // a volley (two rounds) only from a dual pair that has one, the left gun only of a pair
        if (fire.shots() < 1 || fire.shots() > 2 || fire.shots() == 2 && (gun.dual == null || !gun.dual.volley)) return;
        if (fire.hand() < 0 || fire.hand() > 1 || fire.hand() == 1 && !gun.isDual()) return;
        Shooter shooter = shooters.computeIfAbsent(player.getUUID(), id -> new Shooter());
        long now = System.nanoTime();
        if (shooter.reload != null) {
            // a shell reload stops for a shot once a round is in; a magazine swap does not
            if (!shooter.reload.shells) return;
            loadDueShells(player, stack, shooter, now + SHELL_EARLY_NANOS, unlimited(player));
            GunState loaded = GunContent.stateOf(stack);
            if (loaded == null || loaded.ammo() <= 0) return;
            shooter.reload = null;
        }
        GunState state = GunContent.stateOf(stack);
        if (state == null || state.ammo() <= 0) return;
        int shots = Math.min(fire.shots(), state.ammo());
        boolean volley = shots == 2;
        double cost = volley ? Math.max(1, gun.dual.volleyCost) : 1;
        // refill one credit per fire interval: bursts of a couple of shots are fine, a faster sustained rate is not
        double refill = shooter.creditNanos == 0 ? RATE_BURST : (now - shooter.creditNanos) / (gun.fireInterval() * 1e9);
        shooter.shotCredit = Math.min(RATE_BURST, shooter.shotCredit + refill);
        shooter.creditNanos = now;
        if (shooter.shotCredit < cost) {
            Suspicion.report(player, AntiCheatSignals.FIRE_RATE, 1, () -> String.format(Locale.ROOT, "%s 射速超出（剩余额度 %.2f，需要 %.1f）",
                    gun.id, shooter.shotCredit, cost));
            return;
        }
        shooter.shotCredit -= cost;
        GunContent.setState(stack, state.withAmmo(state.ammo() - shots));

        // trust the client's aim within reason (its rotation is a tick ahead of ours)
        float yaw = fire.yaw(), pitch = Mth.clamp(fire.pitch(), -90, 90);
        if (Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) > MAX_ANGLE_ERROR || Math.abs(pitch - player.getXRot()) > MAX_ANGLE_ERROR) {
            Suspicion.report(player, AntiCheatSignals.AIM_MISMATCH, 0.5, String.format(Locale.ROOT, "射击方向 %.0f°/%.0f°，朝向 %.0f°/%.0f°",
                    yaw, pitch, player.getYRot(), player.getXRot()));
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        // the aimed spread only once the sights can really be up (see onAim)
        float aim = Math.min(Mth.clamp(fire.aim(), 0, 1),
                Math.min(1, shooter.plausibleAim(now, fire.slot(), stats.adsTime()) + AIM_HEADROOM));
        double moving = Math.sqrt(Mth.square(player.getX() - player.xo) + Mth.square(player.getZ() - player.zo));
        float spread = stats.spread(aim, moving, !player.onGround(), shooter.bloom(now, gun.spread)) * (volley ? gun.dual.volleySpread : 1);
        for (int i = 0; i < shots; i++) shooter.addBloom(now, gun.spread);

        ServerLevel level = player.level();
        // a sliding shooter's eyes are low (where its camera is)
        Vec3 eye = BodyActions.eyePosition(player);
        int pellets = Math.max(1, stats.ammo().pellets);
        if (stats.ammo().projectile != null) {
            // a launched round flies on its own (GunGrenade); the others see the shot, not a tracer or an impact
            Item roundItem = BuiltInRegistries.ITEM.getValue(BlockLand.id(stats.ammo().item));
            for (int round = 0; round < shots; round++) {
                Vec3 dir = GunMath.spreadDirection(yaw, pitch, spread, fire.seed(), round);
                GunGrenade grenade = new GunGrenade(BLEntities.GUN_GRENADE.get(), level);
                grenade.launch(player, gun, stats.ammo(), eye.add(dir.scale(0.4)), dir, new ItemStack(roundItem));
                level.addFreshEntity(grenade);
                Vec3 ahead = eye.add(dir);
                BLNetwork.sendToTracking(player, new GunPackets.S2CShot(player.getId(), ahead.x, ahead.y, ahead.z, false,
                        GunPackets.S2CShot.LAUNCHED, stats.suppressed(), volley ? round : fire.hand(), false));
            }
            playFire(level, player, stats, shots);
            return;
        }
        Rewind rewind = rewind(player);
        for (int round = 0; round < shots; round++) {
            // the client numbers a volley's pellets on from the first gun's, with the same seed
            int hand = volley ? round : fire.hand();
            for (int pellet = 0; pellet < pellets; pellet++) {
                int index = round * pellets + pellet;
                Vec3 dir = GunMath.spreadDirection(yaw, pitch, spread, fire.seed(), index);
                Shot shot = trace(level, player, eye, dir, stats.range(), stats.ammo().medium(), fire.seenAt(index), rewind);
                boolean wounded = shot.entity != null && damage(level, player, stats, shot, eye);
                BLNetwork.sendToTracking(player, new GunPackets.S2CShot(player.getId(), shot.end.x, shot.end.y, shot.end.z, shot.hitBlock,
                        shot.face, stats.suppressed(), hand, wounded));
            }
        }
        playFire(level, player, stats, shots);
    }

    /** Everyone around hears the shots (a suppressed shot carries a quarter as far); the shooter already played them. */
    private static void playFire(ServerLevel level, ServerPlayer player, GunStats stats, int shots) {
        SoundEvent sound = GunContent.sound(stats.fireSound());
        if (sound == null) return;
        for (int round = 0; round < shots; round++) {
            level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), sound, SoundSource.PLAYERS,
                    stats.suppressed() ? 1.0F : 4.0F, 0.96F + level.random.nextFloat() * 0.08F);
        }
    }

    /**
     * Where one projectile ends: the first entity (not an ally) or block on its path ({@code face}: struck side,
     * {@code head}: the entity was hit on the head).
     */
    private record Shot(Vec3 end, @Nullable Entity entity, boolean hitBlock, int face, boolean head, float energy) {
    }

    /** How far back the shooter's view of the others may be: from {@code first} to {@code last} ticks ago. */
    private record Rewind(@Nullable HitHistory history, int first, int last) {
    }

    private Rewind rewind(ServerPlayer shooter) {
        int trip = Math.round(Math.max(0, shooter.connection.latency()) / 50f);
        int first = Math.min(HitHistory.TICKS - 1, trip);
        return new Rewind(history, first, Math.min(HitHistory.TICKS - 1, trip + VIEW_DELAY_TICKS));
    }

    private static boolean hittable(ServerPlayer shooter, Entity e) {
        return e.isPickable() && e.isAlive() && !e.isSpectator() && !shooter.isAlliedTo(e) && e != shooter.getVehicle();
    }

    /**
     * The round's path: to the first block, unless water or lava on the way spends it first or it skips off a water
     * surface ({@link Ballistics}); what it strikes in a fluid it strikes with the energy it has left there. Entities
     * are where they are now, and the one the shooter's client saw this round strike ({@code seen}) also where it stood
     * when the shooter saw it ({@link Rewind}): it counts if the round passed through it then, short of the block and of
     * anything the round strikes now. A claim alone moves nothing: the round must really have crossed the target.
     */
    private static Shot trace(ServerLevel level, ServerPlayer shooter, Vec3 from, Vec3 dir, double range, Ballistics.Medium medium,
                              int seen, Rewind rewind) {
        Vec3 to = from.add(dir.scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Ballistics.Path path = Ballistics.trace(level, from, dir, hitBlock ? from.distanceTo(block.getLocation()) : range, medium);
        if (path.stoppedInFluid()) hitBlock = false;
        Vec3 end = hitBlock ? block.getLocation() : path.end();
        Entity best = null;
        HitShapes.Hit bestHit = null;
        double bestDistance = from.distanceToSqr(end);
        AABB sweep = new AABB(from, end).inflate(1.0);
        for (Entity candidate : level.getEntities(shooter, sweep, e -> hittable(shooter, e))) {
            // hit boxes, not the bounding box: a sliding player is low, with the head behind the hips
            HitShapes.Hit hit = HitShapes.clip(candidate, from, end, Math.max(0.05, candidate.getPickRadius()));
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit.point());
            if (distance < bestDistance) {
                best = candidate;
                bestHit = hit;
                bestDistance = distance;
            }
        }
        Entity claimed = seen == GunPackets.C2SFire.NONE ? null : level.getEntity(seen);
        if (claimed != null && claimed != best && claimed != shooter && rewind.history() != null && hittable(shooter, claimed)) {
            HitShapes.Hit then = rewound(claimed, from, end, rewind);
            if (then != null && from.distanceToSqr(then.point()) < bestDistance) {
                best = claimed;
                bestHit = then;
            } else if (then == null && rewind.first() < HitHistory.TICKS - 1) {
                // within the history's reach the round never crossed what the client says it hit
                Suspicion.report(shooter, AntiCheatSignals.HIT_CLAIM, 0.5, () -> "声称命中 " + claimed.getName().getString() + "，回溯后子弹未穿过目标");
            }
        }
        if (best != null) return new Shot(bestHit.point(), best, false, -1, bestHit.head(), path.energyAt(from.distanceTo(bestHit.point())));
        // what the others are told about a round that hit nothing: the face it struck, or how a fluid stopped it
        int face = hitBlock ? block.getDirection().ordinal()
                : path.skipped ? GunPackets.S2CShot.SKIPPED : path.spent ? GunPackets.S2CShot.SPENT : -1;
        return new Shot(end, null, hitBlock, face, false, path.energyAt(path.length));
    }

    /**
     * Where the segment met the entity as it stood over the rewind's ticks (from the round trip back), in world space
     * then; null when the segment passed it by every time. Its hit boxes are moved back as a whole.
     */
    @Nullable
    private static HitShapes.Hit rewound(Entity target, Vec3 from, Vec3 end, Rewind rewind) {
        double inflate = Math.max(0.05, target.getPickRadius());
        for (int ago = rewind.first(); ago <= rewind.last(); ago++) {
            Vec3 shift = target.position().subtract(rewind.history().positionAgo(target, ago));
            HitShapes.Hit hit = HitShapes.clip(target, from.add(shift), end.add(shift), inflate);
            if (hit != null) return new HitShapes.Hit(hit.point().subtract(shift), hit.head());
        }
        return null;
    }

    /** Hurts what the shot struck; returns whether it really took something off it (only then does a body bleed). */
    private boolean damage(ServerLevel level, ServerPlayer shooter, GunStats stats, Shot shot, Vec3 eye) {
        Entity target = shot.entity;
        boolean head = shot.head;
        // a round slowed by water or lava hits with what it has left
        float amount = stats.damageAt(eye.distanceTo(shot.end)) * (head ? stats.ammo().headshotMultiplier : 1) * shot.energy;
        if (amount <= 0.05f) return false;
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(GunContent.BULLET),
                shooter, shooter);
        // bullets are fast: every one counts, not just the first in vanilla's hurt cooldown
        if (target instanceof LivingEntity living) {
            living.invulnerableTime = 0;
            HitRecords.record(living, shooter, BlockLand.id(stats.gun().id).toString(), head);
        }
        boolean wasSpectator = target.isSpectator();
        float before = HurtCheck.vitality(target);
        if (!target.hurtServer(level, source, amount)) return false;
        // minigames cancel player deaths and turn the victim into a spectator instead
        boolean killed = !target.isAlive() || target instanceof LivingEntity living && living.isDeadOrDying()
                || !wasSpectator && target.isSpectator();
        boolean wounded = HurtCheck.hurt(target, before, killed);
        GunPackets.HitKind kind = killed ? (head ? GunPackets.HitKind.HEAD_KILL : GunPackets.HitKind.KILL)
                : head ? GunPackets.HitKind.HEAD : GunPackets.HitKind.BODY;
        BLNetwork.sendTo(shooter, new GunPackets.S2CHit(kind, amount, target.getId(), wounded, shot.end.x, shot.end.y, shot.end.z));
        return wounded;
    }

    // ---- aiming ---------------------------------------------------------------------------------

    /**
     * The player raised or lowered the sights: timed from now on (a shot's claimed aim is capped by it) and passed on to
     * the players who can see them, whose clients show a raised magnified scope as a glint.
     */
    @Override
    public void onAim(ServerPlayer player, GunPackets.C2SAim aim) {
        ItemStack stack = player.getMainHandItem();
        Optional<GunStats> stats = GunContent.statsOf(stack);
        // dual guns have no sights (their use key fires a volley)
        boolean aiming = aim.aiming() && player.isAlive() && !player.isSpectator() && stats.isPresent()
                && stats.get().gun().canAim() && aim.slot() == player.getInventory().getSelectedSlot();
        Shooter shooter = shooters.computeIfAbsent(player.getUUID(), id -> new Shooter());
        if (aiming == shooter.aiming && aim.slot() == shooter.aimSlot) return;
        long now = System.nanoTime();
        float adsTime = stats.map(GunStats::adsTime).orElse(0.3f);
        shooter.aimFrom = shooter.plausibleAim(now, aim.slot(), adsTime);
        shooter.aimSlot = aim.slot();
        shooter.aimChanged = now;
        boolean changed = aiming != shooter.aiming;
        shooter.aiming = aiming;
        if (changed) BLNetwork.sendToTracking(player, new GunPackets.S2CAim(player.getId(), aiming));
    }

    /** A player came into view of another: tell the viewer if their sights are up. */
    public void startTracking(ServerPlayer viewer, Entity target) {
        Shooter shooter = shooters.get(target.getUUID());
        if (shooter != null && shooter.aiming) BLNetwork.sendTo(viewer, new GunPackets.S2CAim(target.getId(), true));
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
        if (!unlimited(player) && countAmmo(player, stats.ammo()) == 0) return;
        GunData.Reload reload = stats.gun().reload;
        if (reload.shellMode()) {
            // a round goes in when the client's start clip and that round's clip have played
            long shell = (long) (Math.max(0.05f, reload.shell) * 1e9);
            shooter.reload = new Reload(slot, stats.gun().id, Integer.MAX_VALUE, true,
                    System.nanoTime() + (long) (Math.max(0, reload.start) * 1e9) + shell, shell);
            Watchers.action(player, ViewmodelPackets.Kind.RELOAD, 2, 0);
            return;
        }
        boolean empty = state.ammo() == 0;
        int ticks = Math.max(1, Math.round(stats.reloadTime(empty) * 20));
        shooter.reload = new Reload(slot, stats.gun().id, server.getTickCount() + ticks, false, 0, 0);
        // spectators seeing through this player's eyes play the reload on the watched gun
        Watchers.action(player, ViewmodelPackets.Kind.RELOAD, empty ? 1 : 0, 0);
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
            if (reload.shells) {
                loadDueShells(player, stack, entry.getValue(), System.nanoTime(), unlimited(player));
                continue;
            }
            if (server.getTickCount() < reload.endTick) continue;
            entry.getValue().reload = null;
            finishReload(player, stack, unlimited(player));
        }
    }

    /**
     * Puts in every round of a shell reload that is due by {@code until}, one ammo item each; the reload ends when the
     * tube is full or the ammo runs out.
     */
    private static void loadDueShells(ServerPlayer player, ItemStack stack, Shooter shooter, long until, boolean unlimited) {
        Reload reload = shooter.reload;
        while (reload != null && reload.shells && until >= reload.nextShell) {
            GunState state = GunContent.stateOf(stack);
            Optional<GunStats> resolved = GunContent.statsOf(stack);
            if (state == null || resolved.isEmpty() || state.ammo() >= resolved.get().capacity()) {
                shooter.reload = null;
                return;
            }
            int taken = unlimited ? 1 : takeAmmo(player, resolved.get().ammo(), 1);
            if (taken <= 0) {
                shooter.reload = null;
                return;
            }
            GunContent.setState(stack, state.withAmmo(state.ammo() + taken));
            if (state.ammo() + taken >= resolved.get().capacity()) {
                shooter.reload = null;
                return;
            }
            reload.nextShell += reload.shellNanos;
        }
    }

    private static void finishReload(ServerPlayer player, ItemStack stack, boolean unlimited) {
        GunState state = GunContent.stateOf(stack);
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (state == null || resolved.isEmpty()) return;
        GunStats stats = resolved.get();
        int needed = stats.capacity() - state.ammo();
        if (needed <= 0) return;
        int taken = unlimited ? needed : takeAmmo(player, stats.ammo(), needed);
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

    public void forget(ServerPlayer player) {
        shooters.remove(player.getUUID());
    }

    public void clear() {
        shooters.clear();
    }
}
