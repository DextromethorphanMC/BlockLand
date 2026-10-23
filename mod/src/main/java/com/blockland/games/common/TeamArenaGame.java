package com.blockland.games.common;

import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchResult;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.map.MapBlueprint;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

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
 * Shared flow of team elimination games played on an imported map:
 * LOADING (download + paste map) → COUNTDOWN (players caged at their team spawns)
 * → PLAYING (last team standing, optional time limit) → ENDING → finished.
 * Concrete games customise it through the protected hooks.
 */
public abstract class TeamArenaGame implements GameInstance {
    private static final ChatFormatting[] TEAM_COLORS = {
            ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.YELLOW,
            ChatFormatting.AQUA, ChatFormatting.LIGHT_PURPLE, ChatFormatting.GOLD, ChatFormatting.WHITE,
            ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_PURPLE, ChatFormatting.GRAY};
    /** Team names, {@code blockland.team.<key>} (and {@code .short}), in the order of {@link #TEAM_COLORS}. */
    private static final String[] TEAM_KEYS = {"red", "blue", "green", "yellow", "aqua", "pink", "orange", "white", "dark_green",
            "dark_aqua", "purple", "gray"};
    private static final int DEFAULT_VOID_Y = -10;

    protected enum Phase { LOADING, COUNTDOWN, PLAYING, ENDING, FINISHED }

    protected final GameServices services;
    protected final GameModeDef mode;
    protected final TeamArenaSettings teamSettings;
    protected final MapDef map;
    protected final RandomSource random = RandomSource.create();

    private final UUID id = UUID.randomUUID();
    private final List<Team> teams = new ArrayList<>();
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<BlockPos> cageBlocks = new ArrayList<>();
    private final boolean competitive;

    private Phase phase = Phase.LOADING;
    private Arena arena;
    private int phaseTicks;
    private int elapsedSeconds;
    private boolean disposed;

    protected TeamArenaGame(GameServices services, GameModeDef mode, TeamArenaSettings teamSettings, MapDef map, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.teamSettings = teamSettings;
        this.map = map;

        // matchmaking decided who plays together (parties); only which island/colour each team gets is random
        for (int i = 0; i < teamSettings.teamCount(); i++) teams.add(new Team(i));
        List<List<MatchRoster.Member>> rosterTeams = new ArrayList<>(roster.teams());
        Collections.shuffle(rosterTeams, new java.util.Random(random.nextLong()));
        int overflow = 0;
        for (int i = 0; i < rosterTeams.size(); i++) {
            for (MatchRoster.Member member : rosterTeams.get(i)) {
                // more roster teams than arena teams can only come from a misconfigured caller: spread them out
                Team team = teams.get(i < teams.size() ? i : overflow++ % teams.size());
                team.members.add(member.id());
                players.put(member.id(), new PlayerState(member.id(), member.name(), team));
            }
        }
        this.competitive = teams.stream().filter(t -> !t.members.isEmpty()).count() >= 2;
    }

    // ---- hooks -----------------------------------------------------------------------------

    /** Shown in the intro line, e.g. "空岛战争". */
    protected abstract Component gameName();

    /** The map file was parsed; nothing is in the world yet. */
    protected void onBlueprintLoaded(MapBlueprint blueprint) {
    }

    /** The map is in the world, players are about to be placed. */
    protected void onArenaReady() {
    }

    /** A player was placed at their spawn for the countdown (already reset to adventure mode). */
    protected void onPlayerSpawned(ServerPlayer player) {
    }

    /** Called once per second while PLAYING, after {@link #elapsedSeconds()} increased. */
    protected void onSecond() {
    }

    /** Whether an eliminated player's inventory is dropped (false = items vanish). */
    protected boolean dropsItemsOnElimination() {
        return true;
    }

    protected boolean useCages() {
        return true;
    }

    /** Game mode while the match runs; adventure when the map may neither be broken nor built on (no mining on the client). */
    protected GameType playingGameMode() {
        return GameType.SURVIVAL;
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    public void start() {
        forEachOnline(p -> {
            p.sendSystemMessage(Component.empty()
                    .append(gameName().copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(mode.title).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("blockland.arena.intro_map", Component.literal(map.name).withStyle(ChatFormatting.YELLOW))
                            .withStyle(ChatFormatting.GRAY)));
            PlayerUtil.actionBar(p, MatchText.loadingMap());
        });
        ArenaLoader.load(services, map, () -> disposed, (allocated, blueprint) -> {
            arena = allocated;
            onBlueprintLoaded(blueprint);
        }, pasted -> onPasted(), problem -> {
            broadcast(problem.copy().withStyle(ChatFormatting.RED));
            phase = Phase.FINISHED;
        });
    }

    private void onPasted() {
        onArenaReady();
        setupScoreboardTeams();
        List<MapDef.TeamSpawnDef> groups = map.teamSpawns.stream().filter(t -> t.points != null && !t.points.isEmpty()).toList();
        int step = Math.max(1, groups.size() / teamSettings.teamCount());
        for (Team team : teams) {
            MapDef.TeamSpawnDef group = groups.get((team.index * step) % groups.size());
            int slot = 0;
            for (UUID memberId : team.members) {
                MapDef.SpawnPointDef point = group.points.get(slot++ % group.points.size());
                BlockPos spawn = arena.toWorld((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z));
                PlayerState state = players.get(memberId);
                state.spawn = spawn;
                state.spawnYaw = point.yaw;
                if (useCages()) buildCage(spawn);
            }
        }
        phase = Phase.COUNTDOWN;
        phaseTicks = teamSettings.countdownSeconds() * 20;
        // the players waited in the lobby while the map loaded: now they load straight into their spawns
        for (UUID memberId : List.copyOf(players.keySet())) services.players().bring(memberId, this::arrive);
    }

    @Override
    public void tick() {
        switch (phase) {
            case LOADING -> {
                if (++phaseTicks % 20 == 0) forEachOnline(p -> PlayerUtil.actionBar(p, MatchText.loadingMap()));
            }
            case COUNTDOWN -> tickCountdown();
            case PLAYING -> tickPlaying();
            case ENDING -> {
                if (--phaseTicks <= 0) phase = Phase.FINISHED;
            }
            case FINISHED -> {
            }
        }
    }

    private void tickCountdown() {
        if (phaseTicks % 20 == 0) {
            int seconds = phaseTicks / 20;
            if (seconds > 0) {
                ChatFormatting color = seconds <= 3 ? ChatFormatting.RED : seconds <= 5 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
                forEachOnline(p -> {
                    PlayerUtil.title(p, Component.literal(String.valueOf(seconds)).withStyle(color, ChatFormatting.BOLD),
                            Component.translatable("blockland.arena.get_ready").withStyle(ChatFormatting.GRAY), 0, 25, 0);
                    if (seconds <= 5) PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
                });
            }
        }
        if (--phaseTicks > 0) return;

        removeCages();
        phase = Phase.PLAYING;
        phaseTicks = 0;
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            if (state != null && state.alive) p.setGameMode(playingGameMode());
            PlayerUtil.title(p, Component.translatable("blockland.arena.start").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.translatable("blockland.arena.start_sub").withStyle(ChatFormatting.GRAY), 0, 30, 10);
            PlayerUtil.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        });
        checkWin();
    }

    private void tickPlaying() {
        double voidY = arena.origin().getY() + (map.voidY == null ? DEFAULT_VOID_Y : map.voidY);
        for (PlayerState state : new ArrayList<>(players.values())) {
            if (!state.alive) continue;
            ServerPlayer player = online(state.id);
            if (player == null) continue;
            if (player.level() != arena.level() || player.getY() < voidY) {
                eliminate(player, killerOf(player, null), true);
            }
        }
        if (phase != Phase.PLAYING) return;

        if (++phaseTicks % 20 != 0) return;
        elapsedSeconds++;
        onSecond();
        int limit = mode.timeLimitOrZero();
        long aliveTeams = teams.stream().filter(this::isAlive).count();
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            Component time = limit > 0
                    ? Component.translatable("blockland.arena.hud.time_left", PlayerUtil.formatTime(limit - elapsedSeconds))
                    : Component.translatable("blockland.arena.hud.time_elapsed", PlayerUtil.formatTime(elapsedSeconds));
            PlayerUtil.actionBar(p, Component.empty()
                    .append(Component.translatable("blockland.arena.hud.alive_teams", aliveTeams).withStyle(ChatFormatting.AQUA))
                    .append(MatchText.SEPARATOR)
                    .append(time.copy().withStyle(ChatFormatting.YELLOW))
                    .append(MatchText.SEPARATOR)
                    .append(Component.translatable("blockland.arena.hud.kills", state == null ? 0 : state.kills).withStyle(ChatFormatting.RED)));
        });
        if (limit > 0 && elapsedSeconds >= limit) endByTimeLimit();
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    @Override
    public boolean isEnding() {
        return phase == Phase.ENDING;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        phase = Phase.FINISHED;
        Scoreboard scoreboard = services.server().getScoreboard();
        for (Team team : teams) {
            if (team.scoreboardTeam != null && scoreboard.getPlayerTeam(team.scoreboardTeam.getName()) != null) {
                scoreboard.removePlayerTeam(team.scoreboardTeam);
            }
        }
        if (arena != null) services.arenas().release(arena);
    }

    // ---- players -------------------------------------------------------------------------

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
        return Collections.unmodifiableSet(players.keySet());
    }

    /** A player is in the world (now, or once loaded in from the lobby): to their spawn, unless the match is gone. */
    private void arrive(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (state == null || disposed || state.spawn == null || phase == Phase.ENDING || phase == Phase.FINISHED) {
            services.players().toLobby(player);
            return;
        }
        boolean fighting = phase == Phase.PLAYING && state.alive;
        PlayerUtil.reset(player, fighting ? playingGameMode() : state.alive ? GameType.ADVENTURE : GameType.SPECTATOR);
        PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(state.spawn), state.spawnYaw, 0);
        if (state.alive) onPlayerSpawned(player);
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        PlayerState state = players.get(id);
        if (state == null) return;
        if (state.alive && phase == Phase.PLAYING) {
            if (player != null && dropsItemsOnElimination()) player.getInventory().dropAll();
            broadcast(Component.translatable("blockland.arena.player_left", state.coloredName()).withStyle(ChatFormatting.GRAY));
        }
        state.alive = false;
        players.remove(id);
        state.team.members.remove(id);
        if (state.team.scoreboardTeam != null && services.server().getScoreboard().getPlayersTeam(state.name) == state.team.scoreboardTeam) {
            services.server().getScoreboard().removePlayerFromTeam(state.name, state.team.scoreboardTeam);
        }
        if (players.isEmpty()) {
            phase = Phase.FINISHED;
        } else if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN) {
            checkWin();
        }
    }

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        PlayerState state = players.get(player.getUUID());
        if (state == null) return false;
        if (phase == Phase.PLAYING && state.alive) {
            eliminate(player, killerOf(player, source), false);
        } else {
            // never let a participant really die (e.g. during the countdown)
            player.setHealth(player.getMaxHealth());
            if (state.spawn != null) PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(state.spawn), state.spawnYaw, 0);
        }
        return true;
    }

    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (phase != Phase.PLAYING) return false;
        PlayerState victimState = players.get(victim.getUUID());
        if (victimState == null || !victimState.alive) return false;
        if (source.getEntity() instanceof ServerPlayer attacker) {
            PlayerState attackerState = players.get(attacker.getUUID());
            return attackerState == null || attackerState.team != victimState.team;
        }
        return true;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return phase == Phase.PLAYING;
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return phase == Phase.PLAYING;
    }

    private void eliminate(ServerPlayer player, @Nullable ServerPlayer killer, boolean fellIntoVoid) {
        PlayerState state = players.get(player.getUUID());
        if (state == null || !state.alive) return;
        state.alive = false;

        if (!fellIntoVoid && dropsItemsOnElimination()) player.getInventory().dropAll();
        Component message;
        if (killer != null && killer != player && players.containsKey(killer.getUUID())) {
            PlayerState killerState = players.get(killer.getUUID());
            killerState.kills++;
            PlayerUtil.sound(killer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            message = Component.translatable(fellIntoVoid ? "blockland.arena.knocked_into_void" : "blockland.arena.killed_by",
                    state.coloredName(), killerState.coloredName()).withStyle(ChatFormatting.GRAY);
        } else {
            message = Component.translatable(fellIntoVoid ? "blockland.arena.fell_into_void" : "blockland.arena.died",
                    state.coloredName()).withStyle(ChatFormatting.GRAY);
        }
        broadcast(message);

        PlayerUtil.reset(player, GameType.SPECTATOR);
        Vec3 center = arena.center();
        PlayerUtil.teleport(player, arena.level(), new Vec3(center.x, arena.origin().getY() + arena.size().getY() + 5, center.z), 0, 60);
        PlayerUtil.title(player, Component.translatable("blockland.arena.you_died").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("blockland.arena.spectating").withStyle(ChatFormatting.GRAY), 5, 50, 10);

        checkWin();
    }

    @Nullable
    private ServerPlayer killerOf(ServerPlayer victim, @Nullable DamageSource source) {
        if (source != null) {
            Entity attacker = source.getEntity();
            if (attacker instanceof ServerPlayer p) return p;
        }
        LivingEntity credit = victim.getKillCredit();
        return credit instanceof ServerPlayer p ? p : null;
    }

    // ---- win conditions ------------------------------------------------------------------

    private void checkWin() {
        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;
        List<Team> alive = teams.stream().filter(this::isAlive).toList();
        if (alive.isEmpty()) {
            end(null, Component.translatable("blockland.arena.reason.all_dead"));
        } else if (competitive && alive.size() == 1) {
            end(alive.get(0), null);
        }
    }

    private void endByTimeLimit() {
        List<Team> alive = teams.stream().filter(this::isAlive)
                .sorted(Comparator.comparingInt(this::aliveCount).reversed()).toList();
        if (alive.isEmpty() || (alive.size() > 1 && aliveCount(alive.get(0)) == aliveCount(alive.get(1)))) {
            end(null, Component.translatable("blockland.arena.reason.time_draw"));
        } else {
            end(alive.get(0), Component.translatable("blockland.arena.reason.time_up"));
        }
    }

    private void end(@Nullable Team winner, @Nullable Component reason) {
        if (phase == Phase.COUNTDOWN) removeCages();
        phase = Phase.ENDING;
        phaseTicks = teamSettings.endingSeconds() * 20;

        Component winnerText = winner == null ? Component.translatable("blockland.arena.no_winner").withStyle(ChatFormatting.GRAY)
                : Component.translatable("blockland.arena.winner", winner.coloredName()).withStyle(ChatFormatting.GOLD);
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            boolean won = winner != null && state != null && state.team == winner;
            PlayerUtil.title(p, won ? Component.translatable("blockland.arena.victory").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
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
        players.values().stream().sorted(Comparator.comparingInt((PlayerState s) -> s.kills).reversed()).limit(5)
                .forEach(s -> summary.append(" ").append(s.coloredName()).append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                        .append(Component.translatable("blockland.arena.summary.kills", s.kills).withStyle(ChatFormatting.RED)).append("\n"));
        summary.append(Component.translatable("blockland.arena.summary.back_in", teamSettings.endingSeconds()).withStyle(ChatFormatting.GRAY));
        broadcast(summary);
        services.results().publish(result(winner, reason), teamSettings.endingSeconds());
    }

    /** The results panel: winners first, then by kills; the top killer is the MVP. */
    private MatchResult result(@Nullable Team winner, @Nullable Component reason) {
        List<PlayerState> ordered = players.values().stream()
                .sorted(Comparator.comparing((PlayerState s) -> s.team != winner).thenComparing(s -> -s.kills))
                .toList();
        PlayerState mvp = players.values().stream().filter(s -> s.kills > 0)
                .max(Comparator.comparingInt((PlayerState s) -> s.kills).thenComparing(s -> s.team == winner)).orElse(null);
        List<MatchResult.Row> rows = new ArrayList<>();
        for (PlayerState state : ordered) {
            MatchResult.Outcome outcome = winner == null ? MatchResult.Outcome.DRAW
                    : state.team == winner ? MatchResult.Outcome.VICTORY : MatchResult.Outcome.DEFEAT;
            Integer rgb = state.team.color().getColor();
            rows.add(new MatchResult.Row(state.id, state.name, state.team.name(), 0xFF000000 | (rgb == null ? 0xFFFFFF : rgb),
                    List.of(Component.literal(String.valueOf(state.kills)),
                            Component.translatable(state.alive ? "blockland.result.alive" : "blockland.result.dead")), outcome, state == mvp));
        }
        Component headline = winner == null ? Component.translatable("blockland.result.no_winner")
                : Component.translatable("blockland.result.winner", winner.plainName());
        return new MatchResult(mode.id, mode.title, gameName(), headline, reason, elapsedSeconds,
                List.of(Component.translatable("blockland.result.column.kills"), Component.translatable("blockland.result.column.status")), rows);
    }

    private boolean isAlive(Team team) {
        return aliveCount(team) > 0;
    }

    private int aliveCount(Team team) {
        int count = 0;
        for (UUID member : team.members) {
            PlayerState state = players.get(member);
            if (state != null && state.alive && online(member) != null) count++;
        }
        return count;
    }

    // ---- world setup ---------------------------------------------------------------------

    private void buildCage(BlockPos feet) {
        ServerLevel level = arena.level();
        List<BlockPos> shell = new ArrayList<>();
        shell.add(feet.below());
        shell.add(feet.above(2));
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos layer = feet.above(dy);
            shell.add(layer.north());
            shell.add(layer.south());
            shell.add(layer.east());
            shell.add(layer.west());
        }
        for (BlockPos pos : shell) {
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                cageBlocks.add(pos);
            }
        }
    }

    private void removeCages() {
        for (BlockPos pos : cageBlocks) {
            if (arena.level().getBlockState(pos).is(Blocks.GLASS)) {
                arena.level().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        cageBlocks.clear();
    }

    private void setupScoreboardTeams() {
        Scoreboard scoreboard = services.server().getScoreboard();
        String prefix = GameManager.MATCH_TEAM_PREFIX + id.toString().substring(0, 6) + "_";
        for (Team team : teams) {
            if (team.members.isEmpty()) continue;
            String name = prefix + team.index;
            PlayerTeam existing = scoreboard.getPlayerTeam(name);
            PlayerTeam playerTeam = existing != null ? existing : scoreboard.addPlayerTeam(name);
            playerTeam.setColor(team.color());
            playerTeam.setAllowFriendlyFire(false);
            playerTeam.setPlayerPrefix(Component.literal("[").append(team.shortName()).append("] ").withStyle(team.color()));
            team.scoreboardTeam = playerTeam;
            for (UUID member : team.members) {
                scoreboard.addPlayerToTeam(players.get(member).name, playerTeam);
            }
        }
    }

    // ---- the Tab board -------------------------------------------------------------------

    /** Teams with their players (alive first, then by kills), the time and how many of each team are still standing. */
    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        int limit = mode.timeLimitOrZero();
        Component status = switch (phase) {
            case LOADING, COUNTDOWN -> Component.translatable("blockland.board.status.starting");
            case PLAYING -> limit > 0
                    ? Component.translatable("blockland.arena.hud.time_left", PlayerUtil.formatTime(Math.max(0, limit - elapsedSeconds)))
                    : Component.translatable("blockland.arena.hud.time_elapsed", PlayerUtil.formatTime(elapsedSeconds));
            case ENDING, FINISHED -> Component.translatable("blockland.board.status.ended");
        };
        List<MatchBoard.Team> boardTeams = new ArrayList<>();
        for (Team team : teams) {
            List<MatchBoard.Row> rows = new ArrayList<>();
            for (UUID member : team.members) {
                PlayerState state = players.get(member);
                if (state == null) continue;
                rows.add(new MatchBoard.Row(member, state.name, state.kills, stats.deaths(member), List.of(),
                        state.alive ? MatchBoard.State.ALIVE : MatchBoard.State.SPECTATING));
            }
            if (rows.isEmpty()) continue;
            rows.sort(Comparator.comparing((MatchBoard.Row r) -> r.state() != MatchBoard.State.ALIVE).thenComparingInt(r -> -r.kills()));
            Integer rgb = team.color().getColor();
            boardTeams.add(new MatchBoard.Team(team.name(), 0xFF000000 | (rgb == null ? 0xFFFFFF : rgb),
                    Component.translatable("blockland.board.alive", aliveCount(team), rows.size()), rows));
        }
        return new MatchBoard(gameName(), Component.literal(mode.title + " · " + map.name), status, List.of(), boardTeams);
    }

    // ---- helpers for subclasses ----------------------------------------------------------

    protected Phase phase() {
        return phase;
    }

    protected Arena arena() {
        return arena;
    }

    protected int elapsedSeconds() {
        return elapsedSeconds;
    }

    @Nullable
    protected ServerPlayer online(UUID id) {
        return services.server().getPlayerList().getPlayer(id);
    }

    protected void forEachOnline(Consumer<ServerPlayer> action) {
        for (UUID id : new ArrayList<>(players.keySet())) {
            ServerPlayer player = online(id);
            if (player != null) action.accept(player);
        }
    }

    protected void broadcast(Component message) {
        forEachOnline(p -> p.sendSystemMessage(message));
    }

    private final class Team {
        final int index;
        final List<UUID> members = new ArrayList<>();
        PlayerTeam scoreboardTeam;

        Team(int index) {
            this.index = index;
        }

        ChatFormatting color() {
            return TEAM_COLORS[index % TEAM_COLORS.length];
        }

        Component name() {
            return index < TEAM_KEYS.length ? Component.translatable("blockland.team." + TEAM_KEYS[index])
                    : Component.translatable("blockland.team.numbered", index + 1);
        }

        Component shortName() {
            return index < TEAM_KEYS.length ? Component.translatable("blockland.team." + TEAM_KEYS[index] + ".short")
                    : Component.literal(String.valueOf(index + 1));
        }

        /** The team, or its only player in solo modes, without colour. */
        Component plainName() {
            if (teamSettings.teamSize() == 1 && members.size() == 1) {
                PlayerState only = players.get(members.get(0));
                if (only != null) return Component.literal(only.name);
            }
            return name();
        }

        Component coloredName() {
            if (teamSettings.teamSize() == 1 && members.size() == 1) {
                PlayerState only = players.get(members.get(0));
                if (only != null) return only.coloredName();
            }
            return name().copy().withStyle(color());
        }
    }

    private final class PlayerState {
        final UUID id;
        final String name;
        final Team team;
        boolean alive = true;
        int kills;
        BlockPos spawn;
        float spawnYaw;

        PlayerState(UUID id, String name, Team team) {
            this.id = id;
            this.name = name;
            this.team = team;
        }

        Component coloredName() {
            return Component.literal(name).withStyle(team.color());
        }
    }
}
