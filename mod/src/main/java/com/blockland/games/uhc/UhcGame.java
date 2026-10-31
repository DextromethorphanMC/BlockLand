package com.blockland.games.uhc;

import com.blockland.BlockLand;
import com.blockland.games.common.MatchText;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchResult;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
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
 * One 极限生存 (UHC) match: LOADING (a fresh square of the UHC dimension is picked, {@link UhcRegions}, and a spawn for
 * every player is found while the chunks generate in the background, {@link UhcSpots}) → COUNTDOWN (everyone is brought
 * in and held at their spawn) → NORMAL (survival inside the full border: gather, craft, fight; the first
 * {@code pvpGraceSeconds} without PVP) → FINAL (when no winner came out of the normal phase in time: the survivors are
 * teleported into the middle, the border closes in to that square and after a delay shrinks to its end size) → ENDING
 * → finished. The last one alive wins at any point.
 * <p>
 * The border is the match's own ({@link UhcBorder}): sent to its players as their world border, enforced by the
 * clients' collision and by damage every second outside it. Nobody can leave the dimension (portals cannot be lit
 * there; anything else that takes a player away brings them straight back).
 */
final class UhcGame implements GameInstance {
    private enum Phase { LOADING, COUNTDOWN, NORMAL, FINAL, ENDING, FINISHED }

    /** Blocks between the border and the nearest spawn (normal phase and final phase alike). */
    private static final int SPAWN_EDGE = 24, FINAL_EDGE = 8;
    /** Longest the background search for spawns may take before the rest is found on the spot. */
    private static final int LOADING_TICKS = 60 * 20;
    /** How long before the final phase its spawns are looked for. */
    private static final int FINAL_SEARCH_LEAD = 60 * 20;
    /** Ticks between sending everyone their border again (moving into a world resets the client's border). */
    private static final int BORDER_REFRESH = 100;
    /** Chat warnings before the final phase, in seconds. */
    private static final int[] FINAL_WARNINGS = {300, 60, 30, 10};
    /** How far a player may step away from their spawn during the countdown before being put back. */
    private static final double HOLD_SLACK = 0.6;

    private final GameServices services;
    private final GameModeDef mode;
    private final UhcSettings settings;
    private final ServerLevel level;
    private final UUID id = UUID.randomUUID();
    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final boolean competitive;
    private final ServerBossEvent bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);

    private Phase phase = Phase.LOADING;
    private int phaseTicks;
    /** Ticks since the fight began: the border's and the phases' clock. */
    private int clock;
    private int shrinkStart = -1, shrinkEnd = -1;
    @Nullable
    private UhcBorder border;
    @Nullable
    private UhcSpots spawnSearch, finalSearch;
    private boolean disposed;

    private static final class Fighter {
        final UUID id;
        final String name;
        boolean alive = true;
        boolean arrived;
        /** Has had the mode's kit (once, when the fight starts or when they arrive after that). */
        boolean equipped;
        int kills;
        /** 1 = winner; 0 while still in the game. */
        int placement;
        /** Where they appear: their normal phase spawn, replaced by their final phase spot. */
        @Nullable
        BlockPos spawn;
        /** Where they last stood in the UHC world (to bring them back if something takes them out of it). */
        @Nullable
        Vec3 last;

        Fighter(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    UhcGame(GameServices services, GameModeDef mode, UhcSettings settings, ServerLevel level, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.settings = settings;
        this.level = level;
        for (MatchRoster.Member member : roster.players()) fighters.put(member.id(), new Fighter(member.id(), member.name()));
        this.competitive = fighters.size() >= 2;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    @Override
    public void start() {
        BlockPos centre = UhcRegions.allocate(level, settings.borderRadius());
        border = new UhcBorder(centre.getX() + 0.5, centre.getZ() + 0.5, settings.borderRadius());
        spawnSearch = new UhcSpots(level, border.centreX(), border.centreZ(), settings.borderRadius() - SPAWN_EDGE,
                fighters.size(), settings.spawnSpacing(), random);
        BlockLand.LOGGER.info("[BlockLand] UHC match {} ({}) plays around {} {}", id, mode.id, centre.getX(), centre.getZ());
    }

    @Override
    public void tick() {
        switch (phase) {
            case LOADING -> {
                phaseTicks++;
                spawnSearch.tick();
                if (spawnSearch.done() || phaseTicks >= LOADING_TICKS) onSpawnsFound(spawnSearch.finish());
            }
            case COUNTDOWN -> {
                spawnSearch.tick();
                holdAtSpawns();
                if (phaseTicks % 20 == 0 && phaseTicks > 0) {
                    int seconds = phaseTicks / 20;
                    ChatFormatting color = seconds <= 3 ? ChatFormatting.RED : ChatFormatting.GOLD;
                    forEachArrived(p -> {
                        PlayerUtil.title(p, Component.literal(String.valueOf(seconds)).withStyle(color, ChatFormatting.BOLD),
                                Component.translatable("blockland.arena.get_ready").withStyle(ChatFormatting.GRAY), 0, 25, 0);
                        if (seconds <= 5) PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
                    });
                }
                if (--phaseTicks <= 0) beginFight();
            }
            case NORMAL, FINAL -> tickFight();
            case ENDING -> {
                if (--phaseTicks <= 0) phase = Phase.FINISHED;
            }
            case FINISHED -> {
            }
        }
        if (phase != Phase.LOADING && phase != Phase.FINISHED && services.server().getTickCount() % 20 == 0) updateBar();
    }

    private void onSpawnsFound(List<BlockPos> spots) {
        int i = 0;
        for (Fighter fighter : fighters.values()) fighter.spawn = spots.get(i++ % spots.size());
        phase = Phase.COUNTDOWN;
        phaseTicks = settings.countdownSeconds() * 20;
        for (UUID member : List.copyOf(fighters.keySet())) services.players().bring(member, this::arrive);
    }

    /** The countdown is over: everyone gets their kit and may go. */
    private void beginFight() {
        phase = Phase.NORMAL;
        clock = 0;
        Component subtitle = settings.pvpGraceSeconds() > 0
                ? Component.translatable("blockland.uhc.start_sub_grace", PlayerUtil.formatTime(settings.pvpGraceSeconds()))
                : Component.translatable("blockland.uhc.start_sub");
        for (Fighter fighter : fighters.values()) {
            ServerPlayer player = online(fighter.id);
            if (player == null || !fighter.arrived || !fighter.alive) continue;
            equip(player, fighter);
            PlayerUtil.title(player, Component.translatable("blockland.arena.start").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    subtitle.copy().withStyle(ChatFormatting.GRAY), 5, 40, 10);
            PlayerUtil.sound(player, SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        }
        updateBar();
    }

    private void tickFight() {
        clock++;
        int normalEnd = settings.normalPhaseSeconds() * 20;
        if (phase == Phase.NORMAL) {
            if (finalSearch == null && clock >= normalEnd - FINAL_SEARCH_LEAD) {
                finalSearch = new UhcSpots(level, border.centreX(), border.centreZ(), Math.max(4, settings.finalRadius() - FINAL_EDGE),
                        Math.max(1, aliveCount()), Math.min(settings.spawnSpacing(), settings.finalRadius() / 2.0), random);
            }
            if (finalSearch != null) finalSearch.tick();
            if (clock >= normalEnd) beginFinal();
        }
        if (phase == Phase.FINAL && clock == shrinkStart && shrinkEnd > shrinkStart) {
            forEachArrived(p -> {
                border.send(p, clock);
                PlayerUtil.title(p, Component.empty(), Component.translatable("blockland.uhc.shrinking").withStyle(ChatFormatting.RED), 5, 40, 10);
                PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_BASS.value(), 1f, 0.6f);
            });
            broadcast(Component.translatable("blockland.uhc.shrinking").withStyle(ChatFormatting.RED));
        }
        boolean second = clock % 20 == 0;
        for (Fighter fighter : List.copyOf(fighters.values())) {
            if (!fighter.alive || !fighter.arrived) continue;
            ServerPlayer player = online(fighter.id);
            if (player == null) continue;
            if (player.level() != level) {
                bringBack(player, fighter);
                continue;
            }
            if (!settings.naturalRegeneration()) NoNaturalRegen.hold(player);
            if (!second) continue;
            fighter.last = player.position();
            double outside = border.outside(player.getX(), player.getZ(), clock);
            float damage = settings.borderDamage() + settings.borderDamagePerBlock() * (float) Math.max(0, outside);
            if (outside > 0 && damage > 0) player.hurtServer(level, level.damageSources().outOfBorder(), damage);
        }
        // a death above may have ended the match
        if ((phase != Phase.NORMAL && phase != Phase.FINAL) || !second) return;
        if (settings.pvpGraceSeconds() > 0 && clock == settings.pvpGraceSeconds() * 20) {
            forEachArrived(p -> PlayerUtil.sound(p, SoundEvents.ENDER_DRAGON_GROWL, 0.6f, 1f));
            broadcast(Component.translatable("blockland.uhc.grace_over").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        if (phase == Phase.NORMAL) {
            int left = (normalEnd - clock) / 20;
            for (int warning : FINAL_WARNINGS) {
                if (left == warning && warning < settings.normalPhaseSeconds()) {
                    broadcast(Component.translatable("blockland.uhc.final_soon", PlayerUtil.formatTime(left)).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        forEachArrived(p -> PlayerUtil.actionBar(p, actionBar(p)));
        if (clock % BORDER_REFRESH == 0) forEachArrived(p -> border.send(p, clock));
        int limit = mode.timeLimitOrZero();
        if (limit > 0 && clock >= limit * 20) end(null, Component.translatable("blockland.arena.reason.time_draw"));
    }

    /**
     * No winner in time: the survivors are teleported into the middle (spread over spots found during the last minute
     * of the normal phase), the border jumps to the final square and shrinks after the delay.
     */
    private void beginFinal() {
        phase = Phase.FINAL;
        List<Fighter> alive = fighters.values().stream().filter(f -> f.alive).toList();
        UhcSpots search = finalSearch != null ? finalSearch : new UhcSpots(level, border.centreX(), border.centreZ(),
                Math.max(4, settings.finalRadius() - FINAL_EDGE), Math.max(1, alive.size()),
                Math.min(settings.spawnSpacing(), settings.finalRadius() / 2.0), random);
        List<BlockPos> spots = search.finish();
        shrinkStart = clock + settings.finalDelaySeconds() * 20;
        shrinkEnd = shrinkStart + settings.finalShrinkSeconds() * 20;
        border.lerp(settings.finalRadius(), settings.finalEndRadius(), shrinkStart, shrinkEnd);
        int i = 0;
        for (Fighter fighter : alive) {
            fighter.spawn = spots.get(i++ % spots.size());
            ServerPlayer player = online(fighter.id);
            if (player == null || !fighter.arrived) continue;
            PlayerUtil.teleport(player, level, Vec3.atBottomCenterOf(fighter.spawn), player.getYRot(), 0);
            fighter.last = player.position();
        }
        // the fallen watch from above the final square
        Vec3 over = Vec3.atBottomCenterOf(spots.get(0)).add(0, 25, 0);
        for (Fighter fighter : fighters.values()) {
            ServerPlayer player = online(fighter.id);
            if (player != null && fighter.arrived && !fighter.alive) PlayerUtil.teleport(player, level, over, player.getYRot(), 60);
        }
        Component subtitle = Component.translatable("blockland.uhc.final_sub", PlayerUtil.formatTime(settings.finalDelaySeconds()));
        forEachArrived(p -> {
            border.send(p, clock);
            PlayerUtil.title(p, Component.translatable("blockland.uhc.final_title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    subtitle.copy().withStyle(ChatFormatting.GRAY), 5, 60, 15);
            PlayerUtil.sound(p, SoundEvents.WITHER_SPAWN, 0.5f, 1.2f);
        });
        broadcast(Component.translatable("blockland.uhc.final_chat", settings.finalRadius() * 2, settings.finalRadius() * 2,
                PlayerUtil.formatTime(settings.finalDelaySeconds())).withStyle(ChatFormatting.RED));
        updateBar();
    }

    // ---- players --------------------------------------------------------------------------------------

    /** In the UHC world (from the lobby): at their spawn, given their border; held there until the countdown ends. */
    private void arrive(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || disposed || phase == Phase.ENDING || phase == Phase.FINISHED) {
            services.players().toLobby(player);
            return;
        }
        fighter.arrived = true;
        boolean fighting = phase == Phase.NORMAL || phase == Phase.FINAL;
        PlayerUtil.reset(player, fighting ? GameType.SURVIVAL : GameType.ADVENTURE);
        BlockPos at = fighter.spawn != null ? fighter.spawn : BlockPos.containing(border.centreX(), level.getSeaLevel() + 1, border.centreZ());
        PlayerUtil.teleport(player, level, Vec3.atBottomCenterOf(at), random.nextFloat() * 360 - 180, 0);
        fighter.last = player.position();
        border.send(player, clock);
        bar.addPlayer(player);
        if (fighting) equip(player, fighter);
        int size = settings.borderRadius() * 2;
        player.sendSystemMessage(Component.empty()
                .append(Component.translatable("blockland.game.uhc").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(mode.title == null ? "" : mode.title).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("blockland.uhc.intro", size, size, PlayerUtil.formatTime(settings.normalPhaseSeconds()))
                        .withStyle(ChatFormatting.GRAY)));
        updateBar();
    }

    /** The mode's kit (once) and the survival game mode. */
    private void equip(ServerPlayer player, Fighter fighter) {
        if (!fighter.equipped) {
            services.kits().apply(player, mode);
            fighter.equipped = true;
        }
        player.setGameMode(GameType.SURVIVAL);
    }

    /** During the countdown nobody walks off: whoever strays from their spawn is put back. */
    private void holdAtSpawns() {
        for (Fighter fighter : fighters.values()) {
            if (!fighter.arrived || fighter.spawn == null) continue;
            ServerPlayer player = online(fighter.id);
            if (player == null) continue;
            Vec3 spot = Vec3.atBottomCenterOf(fighter.spawn);
            if (player.level() == level && Math.abs(player.getX() - spot.x) <= HOLD_SLACK && Math.abs(player.getZ() - spot.z) <= HOLD_SLACK
                    && Math.abs(player.getY() - spot.y) <= 1.5) continue;
            PlayerUtil.teleport(player, level, spot, player.getYRot(), player.getXRot());
            if (player.level() == level) border.send(player, clock);
        }
    }

    /** Something took a player out of the UHC world (an end portal, a command): straight back to where they were. */
    private void bringBack(ServerPlayer player, Fighter fighter) {
        Vec3 to = fighter.last != null ? fighter.last : Vec3.atBottomCenterOf(fighter.spawn != null ? fighter.spawn : BlockPos.ZERO);
        PlayerUtil.teleport(player, level, to, player.getYRot(), player.getXRot());
        border.send(player, clock);
        player.sendSystemMessage(Component.translatable("blockland.uhc.no_leaving").withStyle(ChatFormatting.RED));
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        Fighter fighter = fighters.get(id);
        if (fighter == null) return;
        if (player != null) bar.removePlayer(player);
        if (fighter.alive && fighter.arrived && (phase == Phase.NORMAL || phase == Phase.FINAL)) {
            if (player != null) player.getInventory().dropAll();
            broadcast(Component.translatable("blockland.arena.player_left", Component.literal(fighter.name)).withStyle(ChatFormatting.GRAY));
        }
        fighter.alive = false;
        fighters.remove(id);
        if (fighters.isEmpty()) phase = Phase.FINISHED;
        else checkWin();
    }

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null) return false;
        if ((phase == Phase.NORMAL || phase == Phase.FINAL) && fighter.alive && fighter.arrived) {
            eliminate(player, source);
        } else {
            player.setHealth(player.getMaxHealth());
        }
        return true;
    }

    /** Only in the fight, only the living, and no player hurts another during the grace period. */
    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (phase != Phase.NORMAL && phase != Phase.FINAL) return false;
        Fighter fighter = fighters.get(victim.getUUID());
        if (fighter == null || !fighter.alive) return false;
        return clock >= settings.pvpGraceSeconds() * 20 || !(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return canBuild(player, pos);
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return canBuild(player, pos);
    }

    /** The living build and dig while the fight is on, anywhere inside the normal border (the clients keep to the current one). */
    private boolean canBuild(ServerPlayer player, BlockPos pos) {
        if (phase != Phase.NORMAL && phase != Phase.FINAL) return false;
        Fighter fighter = fighters.get(player.getUUID());
        return fighter != null && fighter.alive && Math.max(Math.abs(pos.getX() + 0.5 - border.centreX()),
                Math.abs(pos.getZ() + 0.5 - border.centreZ())) <= settings.borderRadius() + 1;
    }

    private void eliminate(ServerPlayer player, DamageSource source) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || !fighter.alive) return;
        // vanilla's own words for how they died (fall, lava, a creeper, a player's sword...)
        Component how = source.getLocalizedDeathMessage(player);
        fighter.alive = false;
        fighter.placement = aliveCount() + 1;
        player.getInventory().dropAll();
        ServerPlayer killer = killerOf(player, source);
        Fighter credit = killer == null || killer == player ? null : fighters.get(killer.getUUID());
        if (credit != null) {
            credit.kills++;
            PlayerUtil.sound(killer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
        broadcast(how.copy().withStyle(ChatFormatting.GRAY)
                .append(Component.translatable("blockland.uhc.left_alive", aliveCount()).withStyle(ChatFormatting.DARK_GRAY)));
        Vec3 at = player.position();
        PlayerUtil.reset(player, GameType.SPECTATOR);
        PlayerUtil.teleport(player, level, new Vec3(at.x, Math.max(at.y + 1, level.getMinY() + 1), at.z), player.getYRot(), player.getXRot());
        PlayerUtil.title(player, Component.translatable("blockland.arena.you_died").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("blockland.uhc.placed", fighter.placement).withStyle(ChatFormatting.GRAY), 5, 50, 10);
        checkWin();
    }

    @Nullable
    private ServerPlayer killerOf(ServerPlayer victim, @Nullable DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer p) return p;
        LivingEntity credit = victim.getKillCredit();
        return credit instanceof ServerPlayer p ? p : null;
    }

    private void checkWin() {
        if (phase != Phase.NORMAL && phase != Phase.FINAL) return;
        // a fighter still loading in counts as alive: leaving takes them out through removePlayer
        List<Fighter> alive = fighters.values().stream().filter(f -> f.alive).toList();
        if (alive.isEmpty()) end(null, Component.translatable("blockland.arena.reason.all_dead"));
        else if (competitive && alive.size() == 1) end(alive.get(0), null);
    }

    private void end(@Nullable Fighter winner, @Nullable Component reason) {
        if (phase == Phase.ENDING || phase == Phase.FINISHED) return;
        if (winner != null) winner.placement = 1;
        phase = Phase.ENDING;
        phaseTicks = settings.endingSeconds() * 20;
        bar.removeAllPlayers();
        Component winnerText = winner == null ? Component.translatable("blockland.arena.no_winner").withStyle(ChatFormatting.GRAY)
                : Component.translatable("blockland.arena.winner", name(winner)).withStyle(ChatFormatting.GOLD);
        forEachArrived(p -> {
            boolean won = winner != null && winner.id.equals(p.getUUID());
            PlayerUtil.title(p, won ? Component.translatable("blockland.uhc.victory").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                            : Component.translatable("blockland.arena.game_over").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    reason == null ? winnerText : Component.empty().append(reason.copy().withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)).append(winnerText), 5, 80, 20);
            PlayerUtil.sound(p, won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_PLING.value(), 1f, won ? 1f : 0.6f);
            if (won) {
                p.setGameMode(GameType.ADVENTURE);
                p.setHealth(p.getMaxHealth());
            }
        });
        MutableComponent summary = MatchText.summaryHeader(ChatFormatting.RED)
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
        return new MatchResult(mode.id, mode.title, Component.translatable("blockland.game.uhc"), headline, reason, clock / 20,
                List.of(Component.translatable("blockland.result.column.kills"), Component.translatable("blockland.uhc.column.placement")), rows);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        bar.removeAllPlayers();
        phase = Phase.FINISHED;
    }

    // ---- views ----------------------------------------------------------------------------------------

    /** Who is left, the player's kills, the border (its size and how far away, or that they are outside), the time limit. */
    private Component actionBar(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        MutableComponent text = Component.empty()
                .append(Component.translatable("blockland.arena.hud.alive_players", aliveCount()).withStyle(ChatFormatting.AQUA))
                .append(MatchText.SEPARATOR)
                .append(Component.translatable("blockland.arena.hud.kills", fighter == null ? 0 : fighter.kills).withStyle(ChatFormatting.RED))
                .append(MatchText.SEPARATOR);
        double outside = border.outside(player.getX(), player.getZ(), clock);
        int size = (int) Math.round(border.radius(clock) * 2);
        if (fighter != null && fighter.alive && outside > 0) {
            text.append(Component.translatable("blockland.uhc.hud.outside").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            text.append(Component.translatable("blockland.uhc.hud.border", size, size, (int) Math.max(0, Math.floor(-outside)))
                    .withStyle(ChatFormatting.YELLOW));
        }
        int limit = mode.timeLimitOrZero();
        if (limit > 0) {
            text.append(MatchText.SEPARATOR).append(Component.translatable("blockland.arena.hud.time_left",
                    PlayerUtil.formatTime(limit - clock / 20)).withStyle(ChatFormatting.WHITE));
        }
        return text;
    }

    /** The boss bar: the phase and how long until the next one. */
    private void updateBar() {
        int grace = settings.pvpGraceSeconds() * 20, normalEnd = settings.normalPhaseSeconds() * 20;
        switch (phase) {
            case COUNTDOWN -> show(Component.translatable("blockland.uhc.bar.countdown", seconds(phaseTicks)), BossEvent.BossBarColor.WHITE,
                    settings.countdownSeconds() <= 0 ? 0 : phaseTicks / (settings.countdownSeconds() * 20f));
            case NORMAL -> {
                if (clock < grace) {
                    show(Component.translatable("blockland.uhc.bar.grace", PlayerUtil.formatTime(seconds(grace - clock))),
                            BossEvent.BossBarColor.BLUE, (grace - clock) / (float) grace);
                } else {
                    show(Component.translatable("blockland.uhc.bar.normal", PlayerUtil.formatTime(seconds(normalEnd - clock))),
                            BossEvent.BossBarColor.GREEN, (normalEnd - clock) / (float) normalEnd);
                }
            }
            case FINAL -> {
                if (clock < shrinkStart) {
                    show(Component.translatable("blockland.uhc.bar.final_wait", PlayerUtil.formatTime(seconds(shrinkStart - clock))),
                            BossEvent.BossBarColor.YELLOW, (shrinkStart - clock) / (float) Math.max(1, settings.finalDelaySeconds() * 20));
                } else if (clock < shrinkEnd) {
                    show(Component.translatable("blockland.uhc.bar.final_shrink", PlayerUtil.formatTime(seconds(shrinkEnd - clock))),
                            BossEvent.BossBarColor.RED, (shrinkEnd - clock) / (float) Math.max(1, shrinkEnd - shrinkStart));
                } else {
                    show(Component.translatable("blockland.uhc.bar.final_done"), BossEvent.BossBarColor.RED, 0);
                }
            }
            default -> {
            }
        }
    }

    private void show(Component name, BossEvent.BossBarColor color, float progress) {
        bar.setName(name);
        bar.setColor(color);
        bar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    private static int seconds(int ticks) {
        return (Math.max(0, ticks) + 19) / 20;
    }

    /** One list of everyone: the living first, then by kills; the phase in the status. */
    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        Component status = switch (phase) {
            case LOADING, COUNTDOWN -> Component.translatable("blockland.board.status.starting");
            case NORMAL -> Component.translatable("blockland.uhc.board.normal", aliveCount());
            case FINAL -> Component.translatable("blockland.uhc.board.final", aliveCount());
            case ENDING, FINISHED -> Component.translatable("blockland.board.status.ended");
        };
        List<MatchBoard.Row> rows = new ArrayList<>();
        for (Fighter f : fighters.values()) {
            rows.add(new MatchBoard.Row(f.id, f.name, f.kills, stats.deaths(f.id), List.of(),
                    f.alive ? MatchBoard.State.ALIVE : MatchBoard.State.SPECTATING));
        }
        rows.sort(Comparator.comparing((MatchBoard.Row r) -> r.state() != MatchBoard.State.ALIVE).thenComparingInt(r -> -r.kills()));
        return new MatchBoard(Component.translatable("blockland.game.uhc"), Component.literal(mode.title == null ? "" : mode.title), status,
                List.of(), List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"), 0xFFE8ECF2,
                Component.translatable("blockland.board.alive", aliveCount(), rows.size()), rows)));
    }

    /** The normal border's square and a margin, the whole height of the world (the free camera and the far terrain stay in it). */
    @Nullable
    @Override
    public AABB spectateBounds() {
        if (border == null) return null;
        double r = settings.borderRadius() + 16;
        return new AABB(border.centreX() - r, level.getMinY(), border.centreZ() - r, border.centreX() + r, level.getMaxY(), border.centreZ() + r);
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

    /** The participants already in the UHC world (the others are still loading in from the lobby). */
    private void forEachArrived(Consumer<ServerPlayer> action) {
        for (Fighter fighter : new ArrayList<>(fighters.values())) {
            if (!fighter.arrived) continue;
            ServerPlayer player = online(fighter.id);
            if (player != null) action.accept(player);
        }
    }

    private void broadcast(Component message) {
        forEachArrived(p -> p.sendSystemMessage(message));
    }
}
