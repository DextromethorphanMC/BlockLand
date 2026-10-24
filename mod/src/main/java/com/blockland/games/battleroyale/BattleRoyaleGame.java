package com.blockland.games.battleroyale;

import com.blockland.battleroyale.BattleRoyaleContent;
import com.blockland.battleroyale.BrPlane;
import com.blockland.games.common.ArenaLoader;
import com.blockland.games.common.MatchText;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.BattleRoyalePackets;
import com.blockland.parachute.Parachutes;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchResult;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.item.ItemStackFactory;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One 大逃杀 match: LOADING (map pasted, loot laid out, the plane parked at the start of its route) → COUNTDOWN and
 * INTRO (everyone already sits in the plane; the clients play the fly-over) → FLIGHT (the plane crosses the island;
 * the jump key takes a player out, the rest are dropped at the end) → FIGHT (loot, the shrinking zone, last one
 * standing wins) → ENDING → finished.
 * <p>
 * The server decides everything: the route and the plane's position (synced as a route, see {@link BrPlane}), who is
 * aboard and when they leave, where loot lies, the zone and its damage. The clients only ask to jump.
 */
final class BattleRoyaleGame implements GameInstance {
    private enum Phase { LOADING, COUNTDOWN, INTRO, FLIGHT, FIGHT, ENDING, FINISHED }

    private static final int DEFAULT_VOID_Y = -10;
    /** Blocks the route reaches beyond the island on either side. */
    private static final double ROUTE_MARGIN = 30;
    /** Ticks after arriving before a player is seated (the client has to load into the arena first). */
    private static final int BOARD_DELAY = 10;

    private final GameServices services;
    private final GameModeDef mode;
    private final BattleRoyaleSettings settings;
    private final MapDef map;
    private final UUID id = UUID.randomUUID();
    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final List<ItemEntity> loot = new ArrayList<>();
    private final boolean competitive;

    private Phase phase = Phase.LOADING;
    private int phaseTicks;
    @Nullable
    private Arena arena;
    @Nullable
    private BrPlane plane;
    private boolean disposed;
    private boolean dirty = true;

    /** Ticks since the plane left (the zone's clock). */
    private int fightTicks;
    private BattleRoyalePackets.Circle zoneFrom = new BattleRoyalePackets.Circle(0, 0, 0);
    private BattleRoyalePackets.Circle zoneTo = zoneFrom;
    private int shrinkStart, shrinkEnd;

    private static final class Fighter {
        final UUID id;
        final String name;
        boolean alive = true;
        boolean aboard = true;
        boolean arrived;
        int boardAt = -1;
        int kills;
        /** 1 = winner; 0 while still in the game. */
        int placement;
        /** The parachute opened by itself on this fall already (opened again only after landing). */
        boolean autoDeployed;

        Fighter(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    BattleRoyaleGame(GameServices services, GameModeDef mode, BattleRoyaleSettings settings, MapDef map, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.settings = settings;
        this.map = map;
        for (MatchRoster.Member member : roster.players()) fighters.put(member.id(), new Fighter(member.id(), member.name()));
        this.competitive = fighters.size() >= 2;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    @Override
    public void start() {
        forEachOnline(p -> {
            p.sendSystemMessage(Component.empty()
                    .append(Component.translatable("blockland.game.battle_royale").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(mode.title == null ? "" : mode.title).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("blockland.arena.intro_map", Component.literal(map.name).withStyle(ChatFormatting.YELLOW))
                            .withStyle(ChatFormatting.GRAY)));
            PlayerUtil.actionBar(p, MatchText.loadingMap());
        });
        ArenaLoader.load(services, map, () -> disposed, (allocated, blueprint) -> arena = allocated, pasted -> onPasted(), problem -> {
            broadcast(problem.copy().withStyle(ChatFormatting.RED));
            phase = Phase.FINISHED;
        });
    }

    private void onPasted() {
        ServerLevel level = arena.level();
        layOutLoot(level);
        setUpZone();
        plane = BattleRoyaleContent.PLANE.get().create(level, EntitySpawnReason.TRIGGERED);
        if (plane == null) {
            phase = Phase.FINISHED;
            return;
        }
        Vec3[] route = route();
        int duration = Math.max(20, (int) Math.round(route[0].distanceTo(route[1]) / settings.planeSpeed() * 20));
        plane.setRoute(route[0], route[1], duration);
        level.addFreshEntity(plane);
        phase = Phase.COUNTDOWN;
        phaseTicks = settings.countdownSeconds() * 20;
        dirty = true;
        for (UUID member : List.copyOf(fighters.keySet())) services.players().bring(member, this::arrive);
    }

    /** A straight line over the island through (near) its middle, at the plane's height. */
    private Vec3[] route() {
        Vec3 centre = arena.center();
        double half = Math.hypot(arena.size().getX(), arena.size().getZ()) / 2;
        double angle = random.nextDouble() * Math.PI * 2;
        double dx = Math.cos(angle), dz = Math.sin(angle);
        double offset = (random.nextDouble() - 0.5) * 0.5 * Math.min(arena.size().getX(), arena.size().getZ()) / 2;
        double cx = centre.x - dz * offset, cz = centre.z + dx * offset;
        double y = arena.origin().getY() + arena.size().getY() + settings.planeHeight();
        double reach = half + ROUTE_MARGIN;
        return new Vec3[]{new Vec3(cx - dx * reach, y, cz - dz * reach), new Vec3(cx + dx * reach, y, cz + dz * reach)};
    }

    private void setUpZone() {
        Vec3 centre = arena.center();
        double start = Math.hypot(arena.size().getX(), arena.size().getZ()) / 2 + 4;
        double end = Math.min(settings.zoneFinalRadius(), start);
        // the final circle lies anywhere it stays inside the first one
        double spread = Math.max(0, (start - end) * 0.5), angle = random.nextDouble() * Math.PI * 2, distance = Math.sqrt(random.nextDouble()) * spread;
        zoneFrom = new BattleRoyalePackets.Circle(centre.x, centre.z, start);
        zoneTo = new BattleRoyalePackets.Circle(centre.x + Math.cos(angle) * distance, centre.z + Math.sin(angle) * distance, end);
        shrinkStart = settings.zoneDelaySeconds() * 20;
        shrinkEnd = shrinkStart + settings.zoneShrinkSeconds() * 20;
    }

    private BattleRoyalePackets.Circle zone() {
        if (fightTicks <= shrinkStart) return zoneFrom;
        if (fightTicks >= shrinkEnd) return zoneTo;
        return zoneFrom.lerp(zoneTo, (fightTicks - shrinkStart) / (double) (shrinkEnd - shrinkStart));
    }

    /** Ground loot at the map's spots (or random surface spots when it has none). */
    private void layOutLoot(ServerLevel level) {
        BrLoot table = new BrLoot(settings.loot(), new ItemStackFactory(services.server().registryAccess()));
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos rel : BrLoot.spots(map)) spots.add(arena.toWorld(rel.getX(), rel.getY(), rel.getZ()));
        if (spots.isEmpty()) {
            for (int i = 0; i < settings.autoLootSpots(); i++) {
                BlockPos surface = surface(level, arena.origin().getX() + random.nextInt(Math.max(1, arena.size().getX())),
                        arena.origin().getZ() + random.nextInt(Math.max(1, arena.size().getZ())));
                if (surface != null) spots.add(surface);
            }
        }
        for (BlockPos spot : spots) {
            int count = settings.lootMin() + (settings.lootMax() > settings.lootMin() ? random.nextInt(settings.lootMax() - settings.lootMin() + 1) : 0);
            for (ItemStack stack : table.roll(count, random)) {
                ItemEntity item = new ItemEntity(level, spot.getX() + 0.3 + random.nextDouble() * 0.4, spot.getY() + 1.05,
                        spot.getZ() + 0.3 + random.nextDouble() * 0.4, stack, 0, 0, 0);
                item.setUnlimitedLifetime();
                item.setNoPickUpDelay();
                level.addFreshEntity(item);
                loot.add(item);
            }
        }
    }

    /** The top solid block of a column inside the map with room above, or null over the void / water. */
    @Nullable
    private BlockPos surface(ServerLevel level, int x, int z) {
        int top = arena.origin().getY() + arena.size().getY(), bottom = arena.origin().getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty() || state.getCollisionShape(level, pos).isEmpty()) return null;
            return level.getBlockState(pos.above()).isAir() ? pos.immutable() : null;
        }
        return null;
    }

    @Override
    public void tick() {
        switch (phase) {
            case LOADING -> {
                if (++phaseTicks % 20 == 0) forEachOnline(p -> PlayerUtil.actionBar(p, MatchText.loadingMap()));
            }
            case COUNTDOWN -> {
                board();
                if (phaseTicks % 20 == 0 && phaseTicks > 0) {
                    int seconds = phaseTicks / 20;
                    ChatFormatting color = seconds <= 3 ? ChatFormatting.RED : ChatFormatting.GOLD;
                    forEachOnline(p -> {
                        PlayerUtil.title(p, Component.literal(String.valueOf(seconds)).withStyle(color, ChatFormatting.BOLD),
                                Component.translatable("blockland.br.boarding").withStyle(ChatFormatting.GRAY), 0, 25, 0);
                        if (seconds <= 5) PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
                    });
                }
                if (--phaseTicks <= 0) {
                    phase = Phase.INTRO;
                    phaseTicks = settings.introSeconds() * 20;
                    dirty = true;
                }
            }
            case INTRO -> {
                board();
                if (--phaseTicks <= 0) takeOff();
            }
            case FLIGHT, FIGHT -> tickFight();
            case ENDING -> {
                if (--phaseTicks <= 0) phase = Phase.FINISHED;
            }
            case FINISHED -> {
            }
        }
        if (phase == Phase.FINISHED) return;
        if (dirty || services.server().getTickCount() % 20 == 0) sendStates();
    }

    private void takeOff() {
        phase = Phase.FLIGHT;
        fightTicks = 0;
        if (plane != null) plane.depart();
        forEachOnline(p -> PlayerUtil.title(p, Component.translatable("blockland.br.take_off").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.translatable("blockland.br.take_off_sub").withStyle(ChatFormatting.GRAY), 5, 40, 10));
        dirty = true;
    }

    private void tickFight() {
        fightTicks++;
        board();
        if (phase == Phase.FLIGHT && plane != null && plane.arrived()) {
            // the end of the line: whoever is still aboard goes now
            for (Fighter fighter : List.copyOf(fighters.values())) {
                ServerPlayer player = online(fighter.id);
                if (fighter.aboard && player != null) jump(player, fighter);
            }
            plane.discard();
            plane = null;
            phase = Phase.FIGHT;
            dirty = true;
        }
        double voidY = arena.origin().getY() + (map.voidY == null ? DEFAULT_VOID_Y : map.voidY);
        BattleRoyalePackets.Circle zone = zone();
        boolean damageTick = fightTicks % 20 == 0;
        for (Fighter fighter : List.copyOf(fighters.values())) {
            if (!fighter.alive || fighter.aboard) continue;
            ServerPlayer player = online(fighter.id);
            if (player == null) continue;
            if (player.level() != arena.level() || player.getY() < voidY) {
                eliminate(player, killerOf(player, null), true);
                continue;
            }
            autoDeploy(player, fighter);
            if (settings.zoneEnabled() && damageTick && settings.zoneDamage() > 0
                    && Math.hypot(player.getX() - zone.x(), player.getZ() - zone.z()) > zone.radius()) {
                player.hurtServer(arena.level(), arena.level().damageSources().outOfBorder(), settings.zoneDamage());
            }
        }
        if (phase != Phase.FLIGHT && phase != Phase.FIGHT) return;
        if (settings.zoneEnabled()) {
            if (fightTicks == shrinkStart - 30 * 20) broadcast(Component.translatable("blockland.br.zone_soon", 30).withStyle(ChatFormatting.YELLOW));
            if (fightTicks == shrinkStart) {
                forEachOnline(p -> {
                    PlayerUtil.title(p, Component.empty(), Component.translatable("blockland.br.zone_shrinking").withStyle(ChatFormatting.RED), 5, 40, 10);
                    PlayerUtil.sound(p, BattleRoyaleContent.sound("battleroyale.zone_shrink"), 1, 1);
                });
                dirty = true;
            }
        }
        if (fightTicks % 20 == 0) {
            forEachOnline(p -> PlayerUtil.actionBar(p, actionBar(p)));
            int limit = mode.timeLimitOrZero();
            if (limit > 0 && fightTicks >= limit * 20) end(null, Component.translatable("blockland.arena.reason.time_draw"));
        }
    }

    /** A falling player whose canopy is still closed gets it opened close to the ground (once per fall). */
    private void autoDeploy(ServerPlayer player, Fighter fighter) {
        if (player.onGround() || player.isInWater()) {
            fighter.autoDeployed = false;
            return;
        }
        if (settings.autoDeployHeight() <= 0 || fighter.autoDeployed || player.fallDistance < 4 || Parachutes.isOpen(player)) return;
        Vec3 from = player.position(), to = from.add(0, -settings.autoDeployHeight(), 0);
        HitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        if (hit.getType() == HitResult.Type.MISS) return;
        fighter.autoDeployed = true;
        Parachutes.server().deploy(player);
    }

    /** The players not yet seated sit down in the plane once they are in the arena. */
    private void board() {
        if (plane == null) return;
        for (Fighter fighter : fighters.values()) {
            if (!fighter.aboard || !fighter.arrived) continue;
            ServerPlayer player = online(fighter.id);
            if (player == null || player.getVehicle() == plane || player.level() != plane.level()) continue;
            if (fighter.boardAt < 0) fighter.boardAt = services.server().getTickCount() + BOARD_DELAY;
            if (services.server().getTickCount() < fighter.boardAt) continue;
            player.startRiding(plane, true);
            dirty = true;
        }
    }

    // ---- players --------------------------------------------------------------------------------------

    private void arrive(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || disposed || arena == null || phase == Phase.ENDING || phase == Phase.FINISHED) {
            services.players().toLobby(player);
            return;
        }
        fighter.arrived = true;
        PlayerUtil.reset(player, GameType.ADVENTURE);
        Parachutes.server().equip(player);
        if (plane != null && fighter.aboard) {
            Vec3 at = plane.position();
            PlayerUtil.teleport(player, arena.level(), at, plane.getYRot(), 10);
            // hangs where the plane is until seated a moment later
            player.setNoGravity(true);
        } else {
            // the plane is gone already: dropped high over the middle
            fighter.aboard = false;
            Vec3 centre = arena.center();
            PlayerUtil.teleport(player, arena.level(), new Vec3(centre.x, arena.origin().getY() + arena.size().getY() + settings.planeHeight(), centre.z), 0, 30);
            equipForLanding(player);
        }
        dirty = true;
    }

    /** The jump key, from the client: out of the plane while it flies. */
    void onJump(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        if (phase != Phase.FLIGHT || fighter == null || !fighter.aboard || plane == null) return;
        jump(player, fighter);
    }

    private void jump(ServerPlayer player, Fighter fighter) {
        fighter.aboard = false;
        fighter.autoDeployed = false;
        if (plane != null && player.getVehicle() == plane) plane.release(player);
        Vec3 at = plane != null ? plane.position().add(0, -3, 0) : player.position();
        Vec3 velocity = plane != null ? plane.velocity().scale(0.6) : Vec3.ZERO;
        PlayerUtil.teleport(player, arena.level(), at, player.getYRot(), player.getXRot());
        player.setNoGravity(false);
        player.resetFallDistance();
        equipForLanding(player);
        player.setDeltaMovement(velocity);
        player.hurtMarked = true;
        arena.level().playSound(null, at.x, at.y, at.z, BattleRoyaleContent.sound("battleroyale.jump"), SoundSource.PLAYERS, 1, 1);
        dirty = true;
    }

    /** What a player has once out of the plane: the mode's kit (if any), the parachute, the playing game mode. */
    private void equipForLanding(ServerPlayer player) {
        services.kits().apply(player, mode);
        Parachutes.server().equip(player);
        player.setGameMode(settings.allowBlockBreak() || settings.allowBlockPlace() ? GameType.SURVIVAL : GameType.ADVENTURE);
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        Fighter fighter = fighters.get(id);
        if (fighter == null) return;
        if (player != null) {
            if (plane != null && player.getVehicle() == plane) plane.release(player);
            player.setNoGravity(false);
        }
        if (fighter.alive && (phase == Phase.FLIGHT || phase == Phase.FIGHT)) {
            if (player != null && !fighter.aboard) player.getInventory().dropAll();
            broadcast(Component.translatable("blockland.arena.player_left", Component.literal(fighter.name)).withStyle(ChatFormatting.GRAY));
        }
        fighter.alive = false;
        fighters.remove(id);
        if (player != null && !disconnected) BLNetwork.sendTo(player, BattleRoyalePackets.S2CState.NONE);
        dirty = true;
        if (fighters.isEmpty()) phase = Phase.FINISHED;
        else checkWin();
    }

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null) return false;
        if ((phase == Phase.FLIGHT || phase == Phase.FIGHT) && fighter.alive && !fighter.aboard) {
            eliminate(player, killerOf(player, source), false);
        } else {
            player.setHealth(player.getMaxHealth());
        }
        return true;
    }

    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (phase != Phase.FLIGHT && phase != Phase.FIGHT) return false;
        Fighter fighter = fighters.get(victim.getUUID());
        return fighter != null && fighter.alive && !fighter.aboard;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockBreak() && phase == Phase.FIGHT;
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockPlace() && phase == Phase.FIGHT;
    }

    @Override
    public boolean allowSlide(ServerPlayer player) {
        return mode.flag("allowSlide", true);
    }

    private void eliminate(ServerPlayer player, @Nullable ServerPlayer killer, boolean fellIntoVoid) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || !fighter.alive) return;
        fighter.alive = false;
        fighter.placement = aliveCount() + 1;
        if (!fellIntoVoid) player.getInventory().dropAll();
        Fighter credit = killer == null || killer == player ? null : fighters.get(killer.getUUID());
        Component message;
        if (credit != null) {
            credit.kills++;
            PlayerUtil.sound(killer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            message = Component.translatable(fellIntoVoid ? "blockland.arena.knocked_into_void" : "blockland.arena.killed_by",
                    name(fighter), name(credit)).withStyle(ChatFormatting.GRAY);
        } else {
            message = Component.translatable(fellIntoVoid ? "blockland.arena.fell_into_void" : "blockland.arena.died", name(fighter))
                    .withStyle(ChatFormatting.GRAY);
        }
        broadcast(message.copy().append(Component.translatable("blockland.br.left_alive", aliveCount()).withStyle(ChatFormatting.DARK_GRAY)));
        PlayerUtil.reset(player, GameType.SPECTATOR);
        Vec3 centre = arena.center();
        PlayerUtil.teleport(player, arena.level(), new Vec3(centre.x, arena.origin().getY() + arena.size().getY() + 10, centre.z), 0, 60);
        PlayerUtil.title(player, Component.translatable("blockland.arena.you_died").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("blockland.br.placed", fighter.placement).withStyle(ChatFormatting.GRAY), 5, 50, 10);
        dirty = true;
        checkWin();
    }

    @Nullable
    private ServerPlayer killerOf(ServerPlayer victim, @Nullable DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer p) return p;
        LivingEntity credit = victim.getKillCredit();
        return credit instanceof ServerPlayer p ? p : null;
    }

    private void checkWin() {
        if (phase != Phase.FLIGHT && phase != Phase.FIGHT) return;
        // a fighter still loading in counts as alive: leaving takes them out through removePlayer
        List<Fighter> alive = fighters.values().stream().filter(f -> f.alive).toList();
        if (alive.isEmpty()) end(null, Component.translatable("blockland.arena.reason.all_dead"));
        else if (competitive && alive.size() == 1) end(alive.get(0), null);
    }

    private void end(@Nullable Fighter winner, @Nullable Component reason) {
        if (phase == Phase.ENDING || phase == Phase.FINISHED) return;
        if (winner != null) winner.placement = 1;
        releaseAll();
        phase = Phase.ENDING;
        phaseTicks = settings.endingSeconds() * 20;
        dirty = true;
        Component winnerText = winner == null ? Component.translatable("blockland.arena.no_winner").withStyle(ChatFormatting.GRAY)
                : Component.translatable("blockland.arena.winner", name(winner)).withStyle(ChatFormatting.GOLD);
        forEachOnline(p -> {
            boolean won = winner != null && winner.id.equals(p.getUUID());
            PlayerUtil.title(p, won ? Component.translatable("blockland.br.chicken").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                            : Component.translatable("blockland.arena.game_over").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    reason == null ? winnerText : Component.empty().append(reason.copy().withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)).append(winnerText), 5, 80, 20);
            PlayerUtil.sound(p, won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_PLING.value(), 1f, won ? 1f : 0.6f);
            if (won) {
                p.setGameMode(GameType.ADVENTURE);
                p.setHealth(p.getMaxHealth());
            }
        });
        MutableComponent summary = MatchText.summaryHeader(ChatFormatting.AQUA)
                .append(Component.translatable("blockland.arena.summary.winner", winnerText).withStyle(ChatFormatting.WHITE)).append("\n");
        fighters.values().stream().sorted(Comparator.comparingInt((Fighter f) -> f.kills).reversed()).limit(5)
                .forEach(f -> summary.append(" ").append(name(f)).append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                        .append(Component.translatable("blockland.arena.summary.kills", f.kills).withStyle(ChatFormatting.RED)).append("\n"));
        summary.append(Component.translatable("blockland.arena.summary.back_in", settings.endingSeconds()).withStyle(ChatFormatting.GRAY));
        broadcast(summary);
        services.results().publish(result(winner, reason), settings.endingSeconds());
    }

    /** The results panel: by placement (the winner first), the top killer is the MVP. */
    private MatchResult result(@Nullable Fighter winner, @Nullable Component reason) {
        List<Fighter> ordered = fighters.values().stream()
                .sorted(Comparator.comparingInt((Fighter f) -> f.placement == 0 ? 1 : f.placement).thenComparingInt(f -> -f.kills)).toList();
        Fighter mvp = fighters.values().stream().filter(f -> f.kills > 0).max(Comparator.comparingInt(f -> f.kills)).orElse(null);
        List<MatchResult.Row> rows = new ArrayList<>();
        for (Fighter f : ordered) {
            MatchResult.Outcome outcome = winner == null ? MatchResult.Outcome.DRAW : f == winner ? MatchResult.Outcome.VICTORY : MatchResult.Outcome.DEFEAT;
            int place = f.placement == 0 ? 1 : f.placement;
            rows.add(new MatchResult.Row(f.id, f.name, Component.literal("#" + place), f == winner ? 0xFFFFC24A : 0xFF9AA4B5,
                    List.of(Component.literal(String.valueOf(f.kills)), Component.literal("#" + place)), outcome, f == mvp));
        }
        Component headline = winner == null ? Component.translatable("blockland.result.no_winner")
                : Component.translatable("blockland.result.winner", winner.name);
        return new MatchResult(mode.id, mode.title, Component.translatable("blockland.game.battle_royale"), headline, reason, fightTicks / 20,
                List.of(Component.translatable("blockland.result.column.kills"), Component.translatable("blockland.br.column.placement")), rows);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        forEachOnline(p -> BLNetwork.sendTo(p, BattleRoyalePackets.S2CState.NONE));
        releaseAll();
        phase = Phase.FINISHED;
        if (plane != null) plane.discard();
        plane = null;
        loot.forEach(Entity::discard);
        loot.clear();
        if (arena != null) {
            // the loot nobody took and what the fallen dropped
            AABB box = AABB.of(arena.cleanupBox(8));
            for (ItemEntity item : arena.level().getEntitiesOfClass(ItemEntity.class, box)) item.discard();
            services.arenas().release(arena);
        }
    }

    private void releaseAll() {
        if (plane == null) return;
        for (Entity passenger : List.copyOf(plane.getPassengers())) {
            plane.release(passenger);
            passenger.setNoGravity(false);
        }
        for (Fighter fighter : fighters.values()) fighter.aboard = false;
    }

    // ---- views ----------------------------------------------------------------------------------------

    private Component actionBar(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        return Component.empty()
                .append(Component.translatable("blockland.br.hud.alive", aliveCount()).withStyle(ChatFormatting.AQUA))
                .append(MatchText.SEPARATOR)
                .append(Component.translatable("blockland.arena.hud.kills", fighter == null ? 0 : fighter.kills).withStyle(ChatFormatting.RED));
    }

    private void sendStates() {
        dirty = false;
        forEachOnline(p -> BLNetwork.sendTo(p, stateFor(p)));
    }

    private BattleRoyalePackets.S2CState stateFor(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || arena == null) return BattleRoyalePackets.S2CState.NONE;
        BattleRoyalePackets.Phase p = switch (phase) {
            case LOADING, COUNTDOWN -> BattleRoyalePackets.Phase.WAITING;
            case INTRO -> BattleRoyalePackets.Phase.INTRO;
            case FLIGHT -> BattleRoyalePackets.Phase.FLIGHT;
            case FIGHT -> BattleRoyalePackets.Phase.FIGHT;
            case ENDING, FINISHED -> BattleRoyalePackets.Phase.ENDED;
        };
        boolean fighting = phase == Phase.FLIGHT || phase == Phase.FIGHT;
        int sinceFight = fighting || phase == Phase.ENDING ? fightTicks : 0;
        BlockPos o = arena.origin();
        return new BattleRoyalePackets.S2CState(p, phase == Phase.COUNTDOWN || phase == Phase.INTRO ? phaseTicks : 0, map.name == null ? "" : map.name,
                o.getX(), o.getZ(), o.getX() + arena.size().getX(), o.getZ() + arena.size().getZ(), o.getY() + arena.size().getY(),
                fighter.aboard && plane != null, aliveCount(), fighters.size(), fighter.kills, settings.zoneEnabled(), zoneFrom, zoneTo,
                shrinkStart - sinceFight, shrinkEnd - sinceFight);
    }

    /** One list of everyone: the living first, then by kills; the zone in the status. */
    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        Component status = switch (phase) {
            case LOADING, COUNTDOWN, INTRO -> Component.translatable("blockland.board.status.starting");
            case FLIGHT -> Component.translatable("blockland.br.board.flight");
            case FIGHT -> Component.translatable("blockland.br.hud.alive", aliveCount());
            case ENDING, FINISHED -> Component.translatable("blockland.board.status.ended");
        };
        List<MatchBoard.Row> rows = new ArrayList<>();
        for (Fighter f : fighters.values()) {
            rows.add(new MatchBoard.Row(f.id, f.name, f.kills, stats.deaths(f.id), List.of(),
                    f.alive ? MatchBoard.State.ALIVE : MatchBoard.State.SPECTATING));
        }
        rows.sort(Comparator.comparing((MatchBoard.Row r) -> r.state() != MatchBoard.State.ALIVE).thenComparingInt(r -> -r.kills()));
        return new MatchBoard(Component.translatable("blockland.game.battle_royale"), Component.literal(mode.title + " · " + map.name), status,
                List.of(), List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"), 0xFFE8ECF2,
                Component.translatable("blockland.board.alive", aliveCount(), rows.size()), rows)));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private int aliveCount() {
        int count = 0;
        for (Fighter f : fighters.values()) if (f.alive) count++;
        return count;
    }

    private static Component name(Fighter fighter) {
        return Component.literal(fighter.name).withStyle(ChatFormatting.WHITE);
    }

    @Override
    public MapDef map() {
        return map;
    }

    /** The island and the air above it, up to the plane's height. */
    @Nullable
    @Override
    public net.minecraft.world.phys.AABB spectateBounds() {
        return arena == null ? null : net.minecraft.world.phys.AABB.of(arena.cleanupBox(16)).expandTowards(0, settings.planeHeight(), 0);
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public GameModeDef mode() {
        return mode;
    }

    @Override
    public Collection<UUID> participants() {
        return Collections.unmodifiableSet(fighters.keySet());
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    @Override
    public boolean isEnding() {
        return phase == Phase.ENDING;
    }

    @Nullable
    private ServerPlayer online(UUID player) {
        return services.server().getPlayerList().getPlayer(player);
    }

    private void forEachOnline(Consumer<ServerPlayer> action) {
        for (UUID member : new ArrayList<>(fighters.keySet())) {
            ServerPlayer player = online(member);
            if (player != null) action.accept(player);
        }
    }

    private void broadcast(Component message) {
        forEachOnline(p -> p.sendSystemMessage(message));
    }
}
